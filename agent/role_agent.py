from __future__ import annotations

# ─────────────────────────────────────────────────────────────────────────────
# role_agent.py  —  ANALYTICS / DATA LAYER
#
# Responsibilities:
#   • Normalise raw MongoDB log records into a consistent shape
#   • Compute summary statistics (counts, service breakdown, timeline)
#   • Answer natural-language queries directly from log data
#   • Build the analytics dict that is handed to the PDF presentation layer
#
# This module does NOT render PDFs.  All PDF generation is delegated to
# summary.py (the presentation layer) via generate_pdf_report().
# ─────────────────────────────────────────────────────────────────────────────

from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
import logging
import re

from db import fetch_logs

# summary.py is the single source of truth for PDF rendering.
from summary import generate_pdf_report  # noqa: F401 — re-exported for callers

REPORT_DIR = Path(__file__).resolve().parent / 'generated_reports'
REPORT_DIR.mkdir(parents=True, exist_ok=True)
MAX_ANALYSIS_LOGS = 1000
ERROR_LEVELS = {'ERROR', 'WARN', 'WARNING'}

logger = logging.getLogger(__name__)


def _normalize_timestamp(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        timestamp = value
    elif isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            timestamp = datetime.fromisoformat(text.replace('Z', '+00:00'))
        except ValueError:
            return None
    else:
        return None

    if timestamp.tzinfo is None:
        return timestamp.replace(tzinfo=timezone.utc)
    return timestamp.astimezone(timezone.utc)


def _normalize_logs(logs: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    skipped_no_ts = 0

    for item in logs or []:
        if not isinstance(item, dict):
            continue
        timestamp = _normalize_timestamp(item.get('timestamp'))
        if timestamp is None:
            skipped_no_ts += 1
            continue
        service = str(item.get('service') or 'Unknown Service').strip() or 'Unknown Service'
        level = str(item.get('level') or 'INFO').strip().upper() or 'INFO'
        message = str(item.get('message') or item.get('errorDetails') or item.get('errorCode') or '').strip()
        if not message:
            message = 'No message provided'

        response_time = item.get('responseTime')
        try:
            response_time_value = float(response_time) if response_time is not None and str(response_time).strip() else 0.0
        except (TypeError, ValueError):
            response_time_value = 0.0

        normalized.append(
            {
                'timestamp': timestamp,
                'service': service,
                'level': level,
                'message': message,
                'responseTime': response_time_value,
                'statusCode': item.get('statusCode'),
                'traceId': item.get('traceId'),
                'errorCode': item.get('errorCode'),
                'errorDetails': item.get('errorDetails'),
            }
        )

    if skipped_no_ts:
        import logging as _logging
        _logging.getLogger(__name__).warning(
            "_normalize_logs: skipped %d log(s) with missing/unparseable timestamp",
            skipped_no_ts,
        )

    normalized.sort(key=lambda row: row['timestamp'])
    return normalized


def _limit_logs_for_analysis(logs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if len(logs) <= MAX_ANALYSIS_LOGS:
        return logs

    headroom = max(50, MAX_ANALYSIS_LOGS // 2)
    latest = logs[-headroom:]
    aggregate_by_service: dict[str, int] = defaultdict(int)
    for item in logs[:-headroom]:
        if str(item.get('level', '')).upper() in ERROR_LEVELS:
            aggregate_by_service[str(item.get('service') or 'Unknown Service')] += 1

    aggregates: list[dict[str, Any]] = []
    anchor_timestamp = latest[0]['timestamp'] if latest else datetime.now(timezone.utc)
    for service, count in sorted(aggregate_by_service.items(), key=lambda row: row[1], reverse=True)[:20]:
        aggregates.append(
            {
                'timestamp': anchor_timestamp,
                'service': service,
                'level': 'ERROR',
                'message': f'Aggregated {count} earlier error logs',
                'responseTime': 0,
                'statusCode': None,
                'traceId': None,
                'errorCode': None,
                'errorDetails': None,
                'aggregateCount': count,
            }
        )

    return aggregates + latest


def generate_summary(logs: list[dict[str, Any]] | None) -> dict[str, Any]:
    """
    Analytics layer: build a structured summary dict from raw log records.

    The returned dict uses the key schema expected by summary.py's
    generate_pdf_report() so the presentation layer can render it directly
    without any further transformation.

    Keys produced
    -------------
    totalLogs, errorCount (→ total_errors), warningCount (→ total_warnings),
    infoCount (→ total_info), services, top_errors, unstable_services,
    timeline, insights, recommendations, generatedAt,
    total_errors, total_warnings, total_logs, total_info,
    affected_services, time_range_label, environment
    """
    generated_at = datetime.now(timezone.utc).isoformat()

    _empty: dict[str, Any] = {
        # camelCase keys (used by main.py / API layer)
        'totalLogs': 0,
        'errorCount': 0,
        'warningCount': 0,
        'infoCount': 0,
        # snake_case keys (used by summary.py PDF layer)
        'total_logs': 0,
        'total_errors': 0,
        'total_warnings': 0,
        'total_info': 0,
        'affected_services': 0,
        'services': [],
        'top_errors': [],
        'unstable_services': [],
        'timeline': [],
        'insights': ['No data available for your role'],
        'recommendations': ['No data available for your role'],
        'time_range_label': 'N/A',
        'environment': 'Production',
        'generatedAt': generated_at,
    }

    if not logs or not isinstance(logs, list):
        return _empty

    normalized = _limit_logs_for_analysis(_normalize_logs(logs))
    if not normalized:
        return _empty

    # ── Aggregate counts ──────────────────────────────────────────────────────
    total_logs    = len(normalized)
    error_count   = sum(1 for r in normalized if r['level'] == 'ERROR')
    warning_count = sum(1 for r in normalized if r['level'] in {'WARN', 'WARNING'})
    info_count    = sum(1 for r in normalized if r['level'] == 'INFO')

    service_errors:  dict[str, int]      = defaultdict(int)
    service_warnings: dict[str, int]     = defaultdict(int)
    service_totals:  dict[str, int]      = defaultdict(int)
    service_rt:      dict[str, list[float]] = defaultdict(list)
    timeline_counts: dict[str, int]      = defaultdict(int)
    message_counter: Counter[str]        = Counter()
    message_service: dict[str, str]      = {}   # top error → service name

    span_hours = max(1, int(
        (normalized[-1]['timestamp'] - normalized[0]['timestamp']).total_seconds() / 3600
    ))
    bucket_format = '%Y-%m-%d %H:00' if span_hours <= 48 else '%Y-%m-%d'

    for row in normalized:
        svc = str(row['service'])
        service_totals[svc] += 1
        if row['responseTime']:
            service_rt[svc].append(row['responseTime'])
        if row['level'] == 'ERROR':
            service_errors[svc] += 1
            message_counter[row['message']] += 1
            message_service.setdefault(row['message'], svc)
            bucket = row['timestamp'].astimezone(timezone.utc).strftime(bucket_format)
            timeline_counts[bucket] += 1
        elif row['level'] in {'WARN', 'WARNING'}:
            service_warnings[svc] += 1

    # ── Services list (for PDF service table) ─────────────────────────────────
    all_services = sorted(service_totals.keys(),
                          key=lambda s: service_errors.get(s, 0), reverse=True)

    def _p99(rt_list: list[float]) -> str:
        if not rt_list:
            return '—'
        s = sorted(rt_list)
        idx = max(0, int(len(s) * 0.99) - 1)
        return str(round(s[idx]))

    services_list = [
        {
            'name':               svc,
            'errors':             service_errors.get(svc, 0),
            'warnings':           service_warnings.get(svc, 0),
            'total':              service_totals[svc],
            # admin-specific columns (0 when not available from MongoDB)
            'requests_approved':  0,
            'requests_rejected':  0,
            'users_added':        0,
            # dev-specific columns
            'requests_total':     0,
            'p99_latency_ms':     _p99(service_rt.get(svc, [])),
        }
        for svc in all_services
    ]

    # ── Top errors (for PDF top-errors table) ─────────────────────────────────
    top_errors_list = [
        {
            'name':    msg,
            'error':   msg,
            'count':   cnt,
            'service': message_service.get(msg, ''),
        }
        for msg, cnt in message_counter.most_common(10)
    ]

    # ── Unstable services (services with errors, for charts) ──────────────────
    unstable_services_list = [
        {'service': svc, 'count': cnt}
        for svc, cnt in sorted(service_errors.items(), key=lambda x: x[1], reverse=True)
        if cnt > 0
    ]

    # ── Timeline ──────────────────────────────────────────────────────────────
    timeline = [
        {'time': bucket, 'errors': count}
        for bucket, count in sorted(timeline_counts.items())
    ]

    # ── Insights (plain text, shown in PDF insights section) ──────────────────
    top_err_msg, top_err_cnt = (
        message_counter.most_common(1)[0] if message_counter
        else ('No recurring error found', 0)
    )
    peak_service = services_list[0]['name'] if services_list else 'Unknown Service'

    insights = [
        f'Total scoped logs: {total_logs}.',
        (f'Top recurring error: "{top_err_msg}" ({top_err_cnt} occurrences).'
         if top_err_cnt else 'No recurring error pattern was detected.'),
        f'Most impacted service: {peak_service}.',
    ]

    # ── Recommendations (plain text, also used by PDF recommendations section) ─
    recommendations: list[str] = [
        f'Inspect recent {peak_service} deploys, dependencies, and retries '
        'if its error count keeps rising.',
        'Correlate the top error message with infrastructure changes and '
        'upstream service failures.',
    ]
    if warning_count > error_count and warning_count > 0:
        recommendations.append(
            'Warnings outnumber errors — inspect deprecation and saturation signals early.'
        )

    affected = len([s for s in services_list if s['errors'] > 0])

    return {
        # ── camelCase keys (API / main.py layer) ──────────────────────────────
        'totalLogs':    total_logs,
        'errorCount':   error_count,
        'warningCount': warning_count,
        'infoCount':    info_count,
        # ── snake_case keys (summary.py PDF layer) ────────────────────────────
        'total_logs':      total_logs,
        'total_errors':    error_count,
        'total_warnings':  warning_count,
        'total_info':      info_count,
        'affected_services': affected,
        'services':           services_list,
        'top_errors':         top_errors_list,
        'unstable_services':  unstable_services_list,
        'timeline':           timeline,
        'insights':           insights,
        'recommendations':    recommendations[:6],
        'time_range_label':   'N/A',   # overwritten by main.py after this call
        'environment':        'Production',
        'generatedAt':        generated_at,
    }


def answer_query(query: str, logs: list[dict[str, Any]] | None) -> str:
    """
    Answer a natural-language question directly from log data.
    Computes factual answers from the logs rather than returning a generic summary.
    """
    summary = generate_summary(logs)
    if not summary or summary['totalLogs'] <= 0:
        return 'No data available for your role'

    normalized = re.sub(r'\s+', ' ', str(query or '').strip().lower())
    services = summary['services']   # list of {name, errors, total}
    timeline = summary['timeline']   # list of {time, errors}

    # ------------------------------------------------------------------ #
    # Helper: build a ranked error-message counter from raw logs          #
    # ------------------------------------------------------------------ #
    def _top_errors() -> list[tuple[str, int]]:
        counter: Counter[str] = Counter()
        for row in _normalize_logs(logs):
            if row['level'] == 'ERROR':
                counter[row['message']] += 1
        return counter.most_common(5)

    def _top_services_by_errors() -> list[tuple[str, int]]:
        return [(s['name'], s['errors']) for s in services if s.get('errors', 0) > 0]

    def _top_services_by_total() -> list[tuple[str, int]]:
        return [(s['name'], s['total']) for s in services]

    # ------------------------------------------------------------------ #
    # Intent: most frequent / common error                                #
    # (check AFTER service intents so "which service has most errors"    #
    #  doesn't accidentally match here)                                  #
    # ------------------------------------------------------------------ #
    if any(kw in normalized for kw in [
        'frequent error', 'common error', 'top error',
        'recurring error', 'repeated error', 'error type', 'what error',
        'which error', 'error message', 'most frequent'
    ]) or ('most error' in normalized and 'service' not in normalized):
        top = _top_errors()
        if top:
            msg, count = top[0]
            others = ', '.join(f'"{m}" ({c})' for m, c in top[1:3]) if len(top) > 1 else ''
            answer = f'"{msg}" is the most frequent error with {count} occurrence{"s" if count != 1 else ""}.'
            if others:
                answer += f' Other recurring errors: {others}.'
            return answer
        return 'No ERROR-level logs found in the current scope.'

    # ------------------------------------------------------------------ #
    # Intent: most unstable / failing / problematic service               #
    # ------------------------------------------------------------------ #
    if any(kw in normalized for kw in [
        'unstable', 'failing service', 'most errors', 'highest error',
        'problematic', 'worst service', 'most unstable', 'error rate',
        'most failures', 'most issues'
    ]):
        ranked = _top_services_by_errors()
        if ranked:
            name, count = ranked[0]
            total = next((s['total'] for s in services if s['name'] == name), count)
            rate = round((count / total) * 100) if total > 0 else 0
            answer = f'"{name}" is the most unstable service with {count} error{"s" if count != 1 else ""} ({rate}% error rate).'
            if len(ranked) > 1:
                runner_up = f'"{ranked[1][0]}" ({ranked[1][1]} errors)'
                answer += f' Runner-up: {runner_up}.'
            return answer
        return 'No services with errors found in the current scope.'

    # ------------------------------------------------------------------ #
    # Intent: error count / how many errors                               #
    # ------------------------------------------------------------------ #
    if any(kw in normalized for kw in [
        'how many error', 'error count', 'total error', 'number of error',
        'count of error', 'error total'
    ]):
        return (
            f'There are {summary["errorCount"]} error{"s" if summary["errorCount"] != 1 else ""} '
            f'out of {summary["totalLogs"]} total log entries '
            f'({summary["warningCount"]} warning{"s" if summary["warningCount"] != 1 else ""}, '
            f'{summary["infoCount"]} info).'
        )

    # ------------------------------------------------------------------ #
    # Intent: which services / affected services                          #
    # ------------------------------------------------------------------ #
    if any(kw in normalized for kw in [
        'which service', 'what service', 'affected service', 'services affected',
        'list service', 'all service', 'services with error'
    ]):
        ranked = _top_services_by_errors()
        if ranked:
            parts = [f'"{n}" ({c} errors)' for n, c in ranked[:5]]
            return f'Services with errors: {", ".join(parts)}.'
        all_svcs = _top_services_by_total()
        if all_svcs:
            parts = [f'"{n}"' for n, _ in all_svcs[:5]]
            return f'Active services in scope (no errors detected): {", ".join(parts)}.'
        return 'No service data found in the current scope.'

    # ------------------------------------------------------------------ #
    # Intent: busiest / most active service (by log volume)              #
    # ------------------------------------------------------------------ #
    if any(kw in normalized for kw in [
        'busiest', 'most active', 'most logs', 'highest volume',
        'most traffic', 'noisiest'
    ]):
        ranked = _top_services_by_total()
        if ranked:
            name, count = ranked[0]
            return f'"{name}" is the busiest service with {count} log {"entry" if count == 1 else "entries"} in scope.'
        return 'No service data found in the current scope.'

    # ------------------------------------------------------------------ #
    # Intent: time / when / peak                                          #
    # ------------------------------------------------------------------ #
    if any(kw in normalized for kw in [
        'when', 'time', 'timestamp', 'peak', 'spike', 'busiest time',
        'most errors when', 'error peak'
    ]):
        if timeline:
            peak = max(timeline, key=lambda t: t.get('errors', 0))
            first = timeline[0].get('time', 'unknown')
            last = timeline[-1].get('time', 'unknown')
            return (
                f'Errors span from {first} to {last}. '
                f'Peak error bucket: {peak["time"]} with {peak["errors"]} error{"s" if peak["errors"] != 1 else ""}.'
            )
        return 'No timeline data available in the current scope.'

    # ------------------------------------------------------------------ #
    # Intent: summary / overview                                          #
    # ------------------------------------------------------------------ #
    if any(kw in normalized for kw in [
        'summary', 'overview', 'status', 'health', 'overall'
    ]):
        top_err = _top_errors()
        top_svc = _top_services_by_errors()
        parts = [
            f'{summary["totalLogs"]} total logs: {summary["errorCount"]} errors, '
            f'{summary["warningCount"]} warnings, {summary["infoCount"]} info.'
        ]
        if top_err:
            parts.append(f'Most frequent error: "{top_err[0][0]}" ({top_err[0][1]} times).')
        if top_svc:
            parts.append(f'Most impacted service: "{top_svc[0][0]}" ({top_svc[0][1]} errors).')
        return ' '.join(parts)

    # ------------------------------------------------------------------ #
    # Intent: warnings                                                    #
    # ------------------------------------------------------------------ #
    if any(kw in normalized for kw in ['warning', 'warn']):
        counter: Counter[str] = Counter()
        for row in _normalize_logs(logs):
            if row['level'] in {'WARN', 'WARNING'}:
                counter[row['message']] += 1
        top_warn = counter.most_common(3)
        if top_warn:
            parts = [f'"{m}" ({c})' for m, c in top_warn]
            return f'Top warnings: {", ".join(parts)}. Total: {summary["warningCount"]}.'
        return f'No warnings found. Total warning count: {summary["warningCount"]}.'

    # ------------------------------------------------------------------ #
    # Fallback: compute and return the most useful direct answer          #
    # ------------------------------------------------------------------ #
    top_err = _top_errors()
    top_svc = _top_services_by_errors()

    if top_err and top_svc:
        return (
            f'Most frequent error: "{top_err[0][0]}" ({top_err[0][1]} occurrences). '
            f'Most impacted service: "{top_svc[0][0]}" ({top_svc[0][1]} errors out of {summary["totalLogs"]} total logs).'
        )
    if top_err:
        return f'Most frequent error: "{top_err[0][0]}" ({top_err[0][1]} occurrences) across {summary["totalLogs"]} total logs.'
    if top_svc:
        return f'Most impacted service: "{top_svc[0][0]}" ({top_svc[0][1]} errors). Total logs in scope: {summary["totalLogs"]}.'

    return (
        f'{summary["totalLogs"]} logs in scope: {summary["errorCount"]} errors, '
        f'{summary["warningCount"]} warnings, {summary["infoCount"]} info. '
        f'No specific error patterns detected.'
    )


def _save_chart(fig, prefix: str) -> str:
    # ── REMOVED ──────────────────────────────────────────────────────────────
    # Chart generation has moved to summary.py (presentation layer).
    # This stub is kept only so any stale external callers don't crash.
    raise NotImplementedError("Chart generation is handled by summary.py")


def _create_service_bar_chart(data):
    raise NotImplementedError("Chart generation is handled by summary.py")


def _create_timeline_line_chart(data):
    raise NotImplementedError("Chart generation is handled by summary.py")


def ask_ai(question: str, services: list[str], role: str | None = None, logs: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    _ = services
    if logs is None:
        logs = fetch_logs(role or 'dev', services)

    if not logs:
        return {'type': 'text', 'answer': 'No data available for your role'}

    text = str(question or '').strip().lower()
    if any(keyword in text for keyword in ['report', 'summary', 'download']):
        summary = generate_summary(logs)
        # generate_pdf_report is imported from summary.py (presentation layer)
        pdf_path = generate_pdf_report(summary, role or 'dev')
        return {'type': 'file', 'file': pdf_path}

    return {'type': 'text', 'answer': answer_query(question, logs)}


answerQuery = answer_query
generateSummary = generate_summary
fetchLogs = fetch_logs

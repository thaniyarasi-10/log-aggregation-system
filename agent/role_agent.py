from __future__ import annotations

from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from tempfile import mkstemp
from typing import Any
import os
import re

import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt
from reportlab.lib import colors
from reportlab.lib.colors import HexColor
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import Image, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from db import fetch_logs

REPORT_DIR = Path(__file__).resolve().parent / 'generated_reports'
REPORT_DIR.mkdir(parents=True, exist_ok=True)
MAX_ANALYSIS_LOGS = 1000
ERROR_LEVELS = {'ERROR', 'WARN', 'WARNING'}


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
    for item in logs or []:
        if not isinstance(item, dict):
            continue
        timestamp = _normalize_timestamp(item.get('timestamp'))
        if timestamp is None:
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


def _build_summary_context(logs: list[dict[str, Any]] | None) -> dict[str, Any]:
    normalized = _limit_logs_for_analysis(_normalize_logs(logs))
    generated_at = datetime.now(timezone.utc).isoformat()

    if not normalized:
        return {
            'totalLogs': 0,
            'errorCount': 0,
            'warningCount': 0,
            'infoCount': 0,
            'services': [],
            'timeline': [],
            'insights': ['No data available for your role'],
            'recommendations': ['No data available for your role'],
            'generatedAt': generated_at,
        }

    total_logs = len(normalized)
    error_count = sum(1 for row in normalized if row['level'] == 'ERROR')
    warning_count = sum(1 for row in normalized if row['level'] in {'WARN', 'WARNING'})
    info_count = sum(1 for row in normalized if row['level'] == 'INFO')

    service_errors: dict[str, int] = defaultdict(int)
    service_totals: dict[str, int] = defaultdict(int)
    timeline_counts: dict[str, int] = defaultdict(int)
    message_counter: Counter[str] = Counter()

    span_hours = max(1, int((normalized[-1]['timestamp'] - normalized[0]['timestamp']).total_seconds() / 3600))
    bucket_format = '%Y-%m-%d %H:00' if span_hours <= 48 else '%Y-%m-%d'

    for row in normalized:
        service = str(row['service'])
        service_totals[service] += 1
        if row['level'] == 'ERROR':
            service_errors[service] += 1
            message_counter[row['message']] += 1
            bucket = row['timestamp'].astimezone(timezone.utc).strftime(bucket_format)
            timeline_counts[bucket] += 1

    services = [
        {'name': service, 'errors': count, 'total': service_totals[service]}
        for service, count in sorted(service_errors.items(), key=lambda row: row[1], reverse=True)
    ]
    if not services:
        services = [
            {'name': service, 'errors': 0, 'total': total}
            for service, total in sorted(service_totals.items(), key=lambda row: row[1], reverse=True)
        ]

    timeline = [
        {'time': bucket, 'errors': count}
        for bucket, count in sorted(timeline_counts.items(), key=lambda row: row[0])
    ]

    top_error_message, top_error_count = message_counter.most_common(1)[0] if message_counter else ('No recurring error found', 0)
    peak_service = services[0]['name'] if services else 'Unknown Service'

    insights = [
        f'Total scoped logs: {total_logs}.',
        f'Top recurring error: {top_error_message} ({top_error_count} occurrences).' if top_error_count else 'No recurring error pattern was detected.',
        f'Most impacted service: {peak_service}.',
    ]

    recommendations = [
        f'Inspect recent {peak_service} deploys, dependencies, and retries if its error count keeps rising.',
        'Correlate the top error message with infrastructure changes and upstream service failures.',
    ]

    if warning_count > error_count and warning_count > 0:
        recommendations.append('Warnings outnumber errors, so inspect deprecation and saturation signals early.')

    return {
        'totalLogs': total_logs,
        'errorCount': error_count,
        'warningCount': warning_count,
        'infoCount': info_count,
        'services': services,
        'timeline': timeline,
        'insights': insights,
        'recommendations': recommendations[:6],
        'generatedAt': generated_at,
    }


def answer_query(query: str, logs: list[dict[str, Any]] | None) -> str:
    """
    Answer a natural-language question directly from log data.
    Computes factual answers from the logs rather than returning a generic summary.
    """
    summary = _build_summary_context(logs)
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



def generate_summary(logs: list[dict[str, Any]] | None) -> dict[str, Any]:
    # Safety check for inputs
    if not logs or (isinstance(logs, list) and len(logs) == 0):
        return {
            'totalLogs': 0,
            'errorCount': 0,
            'warningCount': 0,
            'infoCount': 0,
            'services': [],
            'timeline': [],
            'insights': ['No data available for your role'],
            'recommendations': ['No data available for your role'],
            'generatedAt': datetime.now(timezone.utc).isoformat(),
        }
    
    if not isinstance(logs, list):
        return {
            'totalLogs': 0,
            'errorCount': 0,
            'warningCount': 0,
            'infoCount': 0,
            'services': [],
            'timeline': [],
            'insights': ['Invalid log data format'],
            'recommendations': ['No data available for your role'],
            'generatedAt': datetime.now(timezone.utc).isoformat(),
        }
    
    return _build_summary_context(logs)



def _save_chart(fig: plt.Figure, prefix: str) -> str:
    fd, path = mkstemp(prefix=prefix, suffix='.png', dir=str(REPORT_DIR))
    os.close(fd)
    fig.savefig(path, dpi=140, bbox_inches='tight')
    plt.close(fig)
    return path


def _create_service_bar_chart(data: dict[str, Any]) -> str | None:
    services = data.get('services', [])
    if not isinstance(services, list) or not services:
        return None

    labels: list[str] = []
    counts: list[int] = []
    for item in services[:8]:
        if isinstance(item, dict):
            labels.append(str(item.get('name') or 'Unknown Service'))
            counts.append(int(item.get('errors', 0) or 0))

    if not labels:
        return None

    fig, ax = plt.subplots(figsize=(7.2, 4.1), dpi=140)
    ax.bar(labels, counts, color='#4EA5F5')
    ax.set_title('Errors by Service', fontsize=12, fontweight='bold')
    ax.set_ylabel('Error Count', fontsize=10)
    ax.tick_params(axis='x', rotation=25, labelsize=8)
    ax.tick_params(axis='y', labelsize=8)
    fig.tight_layout()
    return _save_chart(fig, 'service-errors-')


def _create_timeline_line_chart(data: dict[str, Any]) -> str | None:
    timeline = data.get('timeline', [])
    if not isinstance(timeline, list) or not timeline:
        return None

    labels: list[str] = []
    values: list[int] = []
    for item in timeline[:30]:
        if isinstance(item, dict):
            labels.append(str(item.get('time') or 'Unknown'))
            values.append(int(item.get('errors', 0) or 0))

    if not labels:
        return None

    fig, ax = plt.subplots(figsize=(7.2, 4.1), dpi=140)
    ax.plot(range(len(values)), values, color='#10B981', linewidth=2.2, marker='o', markersize=3.5)
    ax.fill_between(range(len(values)), values, color='#10B981', alpha=0.14)
    ax.set_title('Error Timeline', fontsize=12, fontweight='bold')
    ax.set_ylabel('Error Count', fontsize=10)
    ax.set_xlabel('Time Bucket', fontsize=10)
    step = max(1, len(labels) // 8)
    tick_positions = list(range(0, len(labels), step))
    ax.set_xticks(tick_positions)
    ax.set_xticklabels([labels[index] for index in tick_positions], rotation=25, ha='right', fontsize=8)
    ax.tick_params(axis='y', labelsize=8)
    fig.tight_layout()
    return _save_chart(fig, 'timeline-errors-')


def generate_pdf_report(data: dict[str, Any], role: str) -> str:
    if not isinstance(data, dict):
        return 'Invalid data provided for PDF generation.'

    if not isinstance(role, str) or not role.strip():
        role = 'developer'

    pdf_path = REPORT_DIR / f"role-based-log-summary-{datetime.utcnow().strftime('%Y%m%d%H%M%S%f')}.pdf"
    doc = SimpleDocTemplate(str(pdf_path), topMargin=0.55 * inch, bottomMargin=0.55 * inch)
    styles = getSampleStyleSheet()
    styles.add(ParagraphStyle(
        name='SectionTitle',
        parent=styles['Heading2'],
        fontSize=12,
        textColor=HexColor('#4EA5F5'),
        spaceAfter=6,
        spaceBefore=6,
        fontName='Helvetica-Bold',
    ))
    styles.add(ParagraphStyle(
        name='SectionContent',
        parent=styles['Normal'],
        fontSize=9,
        spaceAfter=4,
        leading=11,
    ))

    elements: list[Any] = []
    total_logs = int(data.get('totalLogs', 0) or 0)
    error_count = int(data.get('errorCount', 0) or 0)
    warning_count = int(data.get('warningCount', 0) or 0)
    info_count = int(data.get('infoCount', 0) or 0)
    generated_at = str(data.get('generatedAt') or datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S UTC'))
    role_label = str(role or 'developer').strip().title()

    elements.append(Paragraph('Role-based Log Summary', styles['Title']))
    elements.append(Spacer(1, 6))
    elements.append(Paragraph(f'<b>Role:</b> {role_label}', styles['SectionContent']))
    elements.append(Paragraph(f'<b>Generated:</b> {generated_at}', styles['SectionContent']))
    elements.append(Spacer(1, 10))

    elements.append(Paragraph('Metrics', styles['SectionTitle']))
    metrics_table_data = [
        [Paragraph('<b>Metric</b>', styles['SectionContent']), Paragraph('<b>Value</b>', styles['SectionContent'])],
        [Paragraph('Total Logs', styles['SectionContent']), Paragraph(str(total_logs), styles['SectionContent'])],
        [Paragraph('Errors', styles['SectionContent']), Paragraph(str(error_count), styles['SectionContent'])],
        [Paragraph('Warnings', styles['SectionContent']), Paragraph(str(warning_count), styles['SectionContent'])],
        [Paragraph('Info', styles['SectionContent']), Paragraph(str(info_count), styles['SectionContent'])],
    ]
    metrics_table = Table(metrics_table_data, colWidths=[2.4 * inch, 1.7 * inch])
    metrics_table.setStyle(TableStyle([
        ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
        ('BACKGROUND', (0, 0), (-1, 0), colors.lightgrey),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 0), (-1, -1), 9),
    ]))
    elements.append(metrics_table)
    elements.append(Spacer(1, 12))

    elements.append(Paragraph('Insights', styles['SectionTitle']))
    for insight in data.get('insights', []) if isinstance(data.get('insights', []), list) else []:
        elements.append(Paragraph(f'• {insight}', styles['SectionContent']))
    elements.append(Spacer(1, 12))

    elements.append(Paragraph('Service Breakdown', styles['SectionTitle']))
    service_rows = [[Paragraph('<b>Service</b>', styles['SectionContent']), Paragraph('<b>Errors</b>', styles['SectionContent'])]]
    for item in data.get('services', []) if isinstance(data.get('services', []), list) else []:
        if isinstance(item, dict):
            service_rows.append([
                Paragraph(str(item.get('name') or 'Unknown Service'), styles['SectionContent']),
                Paragraph(str(int(item.get('errors', 0) or 0)), styles['SectionContent']),
            ])
    service_table = Table(service_rows, colWidths=[3.6 * inch, 1.0 * inch])
    service_table.setStyle(TableStyle([
        ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
        ('BACKGROUND', (0, 0), (-1, 0), colors.lightgrey),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 0), (-1, -1), 9),
    ]))
    elements.append(service_table)
    elements.append(Spacer(1, 12))

    service_chart = _create_service_bar_chart(data)
    if service_chart and os.path.exists(service_chart):
        elements.append(PageBreak())
        elements.append(Paragraph('Errors by Service', styles['SectionTitle']))
        elements.append(Spacer(1, 8))
        elements.append(Image(service_chart, width=6.6 * inch, height=3.7 * inch))

    timeline_chart = _create_timeline_line_chart(data)
    if timeline_chart and os.path.exists(timeline_chart):
        elements.append(PageBreak())
        elements.append(Paragraph('Error Timeline', styles['SectionTitle']))
        elements.append(Spacer(1, 8))
        elements.append(Image(timeline_chart, width=6.6 * inch, height=3.7 * inch))

    elements.append(PageBreak())
    elements.append(Paragraph('Recommendations', styles['SectionTitle']))
    recommendations = data.get('recommendations', [])
    if isinstance(recommendations, list) and recommendations:
        for recommendation in recommendations:
            elements.append(Paragraph(f'• {recommendation}', styles['SectionContent']))
    else:
        elements.append(Paragraph('• Continue monitoring the scoped services for sustained error growth.', styles['SectionContent']))

    try:
        doc.build(elements)
    finally:
        for image_path in [service_chart, timeline_chart]:
            if image_path and os.path.exists(image_path):
                try:
                    os.remove(image_path)
                except OSError:
                    pass

    return str(pdf_path)


def ask_ai(question: str, services: list[str], role: str | None = None, logs: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    _ = services
    if logs is None:
        logs = fetch_logs(role or 'developer', services)

    if not logs:
        return {'type': 'text', 'answer': 'No data available for your role'}

    text = str(question or '').strip().lower()
    if any(keyword in text for keyword in ['report', 'summary', 'download']):
        summary = generate_summary(logs)
        pdf_path = generate_pdf_report(summary, role or 'developer')
        return {'type': 'file', 'file': pdf_path}

    return {'type': 'text', 'answer': answer_query(question, logs)}


answerQuery = answer_query
generateSummary = generate_summary
fetchLogs = fetch_logs

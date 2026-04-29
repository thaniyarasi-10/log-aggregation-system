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
    summary = _build_summary_context(logs)
    if not summary or summary['totalLogs'] <= 0:
        return 'No data available for your role'

    normalized_query = re.sub(r'\s+', ' ', str(query or '').strip().lower())
    services = summary['services']
    timeline = summary['timeline']

    lines = [
        f"I reviewed {summary['totalLogs']} scoped logs and found {summary['errorCount']} errors, {summary['warningCount']} warnings, and {summary['infoCount']} info entries.",
    ]

    if any(keyword in normalized_query for keyword in ['cause', 'why', 'root']):
        top_service = services[0].get('name', 'Unknown Service') if services and isinstance(services[0], dict) else 'Unknown Service'
        recurring_services = ', '.join(str(item.get('name', 'Unknown')) for item in services[:3] if isinstance(item, dict) and item.get('errors', 0)) or 'no recurring service-level spike'
        lines.append(f'Likely causes are concentrated in {top_service}; recurring error pressure is visible in {recurring_services}.')

    if any(keyword in normalized_query for keyword in ['service', 'where', 'affected']):
        affected = ', '.join(str(item.get('name', 'Unknown')) for item in services[:5] if isinstance(item, dict)) or 'No affected services identified'
        lines.append(f'Affected services: {affected}.')

    if any(keyword in normalized_query for keyword in ['time', 'when', 'timestamp']) or timeline:
        first_bucket = timeline[0].get('time', 'unknown time') if timeline and isinstance(timeline[0], dict) else 'unknown time'
        last_bucket = timeline[-1].get('time', 'unknown time') if timeline and isinstance(timeline[-1], dict) else 'unknown time'
        peak_bucket = max(timeline, key=lambda item: item.get('errors', 0) if isinstance(item, dict) else 0).get('time', 'unknown time') if timeline else 'unknown time'
        lines.append(f'Timestamps span from {first_bucket} to {last_bucket}, with the busiest error bucket at {peak_bucket}.')

    if not any(keyword in normalized_query for keyword in ['cause', 'why', 'root', 'service', 'where', 'affected', 'time', 'when', 'timestamp']):
        first_service = services[0].get('name', 'Unknown Service') if services and isinstance(services[0], dict) else 'Unknown Service'
        lines.append(f'The noisiest scoped service is {first_service}, so start there for investigation.')

    lines.append('Use the summary mode for a downloadable PDF with charts and recommendations.')
    return ' '.join(str(line) for line in lines)



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

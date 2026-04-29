from datetime import datetime
import os
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch
from reportlab.lib.colors import HexColor
from reportlab.platypus import (
    Paragraph, SimpleDocTemplate, Spacer, PageBreak, Table, TableStyle, KeepTogether, Image
)
from reportlab.lib import colors
import matplotlib.pyplot as plt
import matplotlib
matplotlib.use('Agg')  # Use non-interactive backend


def _as_list(value: object) -> list:
    if isinstance(value, list):
        return value
    return []


def _to_text(value: object, default: str = "N/A") -> str:
    if value is None:
        return default
    text = str(value).strip()
    return text if text else default


def _sanitize_service_name(service: str) -> str:
    """Replace None/empty service names with 'Unknown Service'"""
    if service is None or not service or str(service).strip() == "None":
        return "Unknown Service"
    return str(service).strip()


def _categorize_error(error_msg: str) -> str:
    """Categorize error into groups"""
    error_lower = error_msg.lower()
    if any(x in error_lower for x in ["kafka", "connection", "network", "timeout", "refused"]):
        return "Infrastructure"
    if any(x in error_lower for x in ["db", "database", "sql", "mongo", "query", "index"]):
        return "Database"
    if any(x in error_lower for x in ["elasticsearch", "elastic", "search", "index"]):
        return "External"
    return "Application"


def _generate_insights(data: dict) -> list[str]:
    """Generate actionable insights from data"""
    insights = []
    
    total_errors = int(_to_text(data.get("total_errors", "0"), "0") or "0")
    total_warnings = int(_to_text(data.get("total_warnings", "0"), "0") or "0")
    
    # Insight 1: Error trend
    if total_errors > 100:
        insights.append(f"Critical error spike detected with {total_errors} errors in the timeframe - immediate investigation required.")
    elif total_errors > 50:
        insights.append(f"Elevated error count ({total_errors}) indicates potential system instability.")
    elif total_errors > 0:
        insights.append(f"Moderate error activity ({total_errors}) detected - monitor for escalation.")
    
    # Insight 2: Top error analysis
    top_errors = _as_list(data.get("top_errors", []))
    if top_errors and isinstance(top_errors[0], dict):
        top_error = top_errors[0]
        top_error_name = _to_text(top_error.get("name") or top_error.get("error"), "Unknown")
        top_error_count = int(_to_text(top_error.get("count", "0"), "0") or "0")
        if top_error_count > 0:
            percentage = (top_error_count / max(total_errors, 1)) * 100
            insights.append(f"Most frequent error is '{top_error_name}' accounting for {percentage:.1f}% of total errors.")
    
    # Insight 3: Service health
    affected_services = int(_to_text(data.get("affected_services", "0"), "0") or "0")
    if affected_services > 0:
        insights.append(f"{affected_services} service(s) currently experiencing errors or warnings.")
    
    # Ensure we never return empty insights
    if not insights:
        insights.append("System is operating within normal parameters.")
    
    return insights


def _get_overall_status(total_errors: int) -> str:
    """Determine overall status based on error count"""
    if total_errors > 100:
        return "CRITICAL"
    elif total_errors >= 20:
        return "WARNING"
    else:
        return "HEALTHY"


def _create_error_distribution_chart(data: dict) -> str:
    """Create bar chart of error distribution by category"""
    top_errors = _as_list(data.get("top_errors", []))
    
    if not top_errors:
        return None
    
    categories = {}
    for item in top_errors:
        if isinstance(item, dict):
            error_name = _to_text(item.get("name") or item.get("error"), "Unknown")
            count = int(_to_text(item.get("count", "0"), "0") or "0")
            category = _categorize_error(error_name)
            categories[category] = categories.get(category, 0) + count
    
    if not categories:
        return None
    
    fig, ax = plt.subplots(figsize=(3.5, 2.8), dpi=100)
    ax.bar(categories.keys(), categories.values(), color=['#FF6B6B', '#4ECDC4', '#45B7D1', '#FFA07A'][:len(categories)])
    ax.set_xlabel("Error Type", fontsize=9)
    ax.set_ylabel("Count", fontsize=9)
    ax.set_title("Error Distribution by Category", fontsize=10, fontweight='bold')
    plt.xticks(rotation=45, ha='right', fontsize=8)
    plt.yticks(fontsize=8)
    
    chart_file = "error_chart.png"
    plt.tight_layout()
    plt.savefig(chart_file, dpi=100, bbox_inches='tight')
    plt.close()
    return chart_file


def _create_service_stability_chart(data: dict) -> str:
    """Create bar chart of service stability"""
    unstable_services = _as_list(data.get("unstable_services", []))
    
    if not unstable_services:
        return None
    
    services = []
    counts = []
    
    for item in unstable_services[:8]:  # Limit to top 8 services
        if isinstance(item, dict):
            service = _sanitize_service_name(item.get("service", "Unknown"))
            count = int(_to_text(item.get("count", "0"), "0") or "0")
            services.append(service)
            counts.append(count)
    
    if not services:
        return None
    
    fig, ax = plt.subplots(figsize=(3.5, 2.8), dpi=100)
    ax.barh(services, counts, color='#45B7D1')
    ax.set_xlabel("Error/Warning Count", fontsize=9)
    ax.set_title("Service Stability", fontsize=10, fontweight='bold')
    ax.invert_yaxis()
    plt.yticks(fontsize=8)
    plt.xticks(fontsize=8)
    
    chart_file = "service_chart.png"
    plt.tight_layout()
    plt.savefig(chart_file, dpi=100, bbox_inches='tight')
    plt.close()
    return chart_file


def generate_pdf_report(data: dict, role: str) -> str:
    """Generate production-level PDF report with structured sections and side-by-side graphs"""
    file_name = "report.pdf"
    doc = SimpleDocTemplate(file_name, topMargin=0.5*inch, bottomMargin=0.5*inch)
    styles = getSampleStyleSheet()
    styles.add(ParagraphStyle(
        name='SectionTitle',
        parent=styles['Heading2'],
        fontSize=12,
        textColor=HexColor('#1F4788'),
        spaceAfter=6,
        spaceBefore=6,
        fontName='Helvetica-Bold'
    ))
    styles.add(ParagraphStyle(
        name='SectionContent',
        parent=styles['Normal'],
        fontSize=9,
        spaceAfter=4,
        leading=11
    ))
    
    elements: list = []
    
    # ==================== HEADER SECTION ====================
    env = _to_text(data.get("environment"), "Production")
    date_text = _to_text(data.get("date"), datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    time_range_label = _to_text(data.get("time_range_label"), "Last 24 hours")
    
    elements.append(Paragraph("<b>Log Analysis Report</b>", styles["Title"]))
    elements.append(Spacer(1, 6))
    elements.append(Paragraph(f"<b>Date:</b> {date_text}", styles["SectionContent"]))
    elements.append(Paragraph(f"<b>Time Range:</b> {time_range_label}", styles["SectionContent"]))
    elements.append(Spacer(1, 12))
    
    # ==================== SYSTEM HEALTH SECTION ====================
    total_errors = int(_to_text(data.get("total_errors", "0"), "0") or "0")
    total_warnings = int(_to_text(data.get("total_warnings", "0"), "0") or "0")
    affected_services = int(_to_text(data.get("affected_services", "0"), "0") or "0")
    overall_status = _get_overall_status(total_errors)
    
    elements.append(Paragraph("System Health", styles["SectionTitle"]))
    health_content = [
        [Paragraph("<b>Metric</b>", styles["SectionContent"]), Paragraph("<b>Value</b>", styles["SectionContent"])],
        [Paragraph("Total Errors", styles["SectionContent"]), Paragraph(str(total_errors), styles["SectionContent"])],
        [Paragraph("Total Warnings", styles["SectionContent"]), Paragraph(str(total_warnings), styles["SectionContent"])],
        [Paragraph("Affected Services", styles["SectionContent"]), Paragraph(str(affected_services), styles["SectionContent"])],
        [Paragraph("Overall Status", styles["SectionContent"]), 
         Paragraph(f"<font color='{('red' if overall_status == 'CRITICAL' else 'orange' if overall_status == 'WARNING' else 'green')}'><b>{overall_status}</b></font>", styles["SectionContent"])]
    ]
    health_table = Table(health_content, colWidths=[2.5*inch, 1.5*inch])
    health_table.setStyle(TableStyle([
        ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
        ('BACKGROUND', (0, 0), (-1, 0), colors.lightgrey),
        ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 0), (-1, -1), 9),
    ]))
    elements.append(health_table)
    elements.append(Spacer(1, 12))
    
    # ==================== KEY INSIGHTS SECTION ====================
    elements.append(Paragraph("Key Insights", styles["SectionTitle"]))
    insights = _generate_insights(data)
    for insight in insights:
        elements.append(Paragraph(f"• {insight}", styles["SectionContent"]))
    elements.append(Spacer(1, 12))
    
    # ==================== ERROR BREAKDOWN (GROUPED) ====================
    elements.append(Paragraph("Error Breakdown by Category", styles["SectionTitle"]))
    
    # Group errors by category
    error_groups = {
        "Application Errors": [],
        "Infrastructure Errors": [],
        "Database Errors": [],
        "External/System Errors": []
    }
    
    for item in _as_list(data.get("top_errors", [])):
        if isinstance(item, dict):
            error_name = _to_text(item.get("name") or item.get("error"), "Unknown")
            count = int(_to_text(item.get("count", "0"), "0") or "0")
            category = _categorize_error(error_name)
            if category == "Infrastructure":
                group_key = "Infrastructure Errors"
            elif category == "Database":
                group_key = "Database Errors"
            elif category == "External":
                group_key = "External/System Errors"
            else:
                group_key = "Application Errors"
            error_groups[group_key].append((error_name, count))
    
    for group_name, errors in error_groups.items():
        if errors:
            elements.append(Paragraph(f"<b>{group_name}</b>", styles["SectionContent"]))
            for error_name, count in errors:
                elements.append(Paragraph(f"  • {error_name}: {count}", styles["SectionContent"]))
            elements.append(Spacer(1, 6))
    
    elements.append(Spacer(1, 6))
    
    # ==================== TOP ERRORS TABLE ====================
    elements.append(Paragraph("Top Errors (Sorted by Frequency)", styles["SectionTitle"]))
    
    top_errors_data = [
        [Paragraph("<b>Error Message</b>", styles["SectionContent"]), 
         Paragraph("<b>Count</b>", styles["SectionContent"])]
    ]
    
    for item in _as_list(data.get("top_errors", []))[:10]:  # Limit to top 10
        if isinstance(item, dict):
            error_name = _to_text(item.get("name") or item.get("error"), "Unknown")
            # Shorten error message for readability
            if len(error_name) > 50:
                error_name = error_name[:47] + "..."
            count = int(_to_text(item.get("count", "0"), "0") or "0")
            top_errors_data.append([
                Paragraph(error_name, styles["SectionContent"]),
                Paragraph(str(count), styles["SectionContent"])
            ])
    
    errors_table = Table(top_errors_data, colWidths=[3.5*inch, 1*inch])
    errors_table.setStyle(TableStyle([
        ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
        ('BACKGROUND', (0, 0), (-1, 0), colors.lightgrey),
        ('ALIGN', (0, 0), (0, -1), 'LEFT'),
        ('ALIGN', (1, 0), (1, -1), 'CENTER'),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 0), (-1, -1), 9),
    ]))
    elements.append(errors_table)
    elements.append(Spacer(1, 12))
    
    # ==================== GRAPHS SIDE-BY-SIDE ====================
    error_dist_chart = _create_error_distribution_chart(data)
    service_stab_chart = _create_service_stability_chart(data)
    
    if error_dist_chart and os.path.exists(error_dist_chart) and service_stab_chart and os.path.exists(service_stab_chart):
        elements.append(Paragraph("Analysis Visualizations", styles["SectionTitle"]))
        
        # Create side-by-side layout with table
        img1 = Image(error_dist_chart, width=250, height=200)
        img2 = Image(service_stab_chart, width=250, height=200)
        
        graph_table = Table(
            [[img1, img2]],
            colWidths=[2.75*inch, 2.75*inch]
        )
        graph_table.setStyle(TableStyle([
            ('ALIGN', (0, 0), (-1, -1), 'CENTER'),
            ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ]))
        
        elements.append(KeepTogether(graph_table))
        elements.append(Spacer(1, 12))
    
    # ==================== ROOT CAUSE SECTION ====================
    elements.append(Paragraph("Root Cause Analysis", styles["SectionTitle"]))
    
    # Infer root cause from top error
    root_cause_text = "Unable to determine root cause from available data."
    top_errors_list = _as_list(data.get("top_errors", []))
    correlations = _as_list(data.get("correlation", []))
    
    if top_errors_list and isinstance(top_errors_list[0], dict):
        top_error = top_errors_list[0]
        error_name = _to_text(top_error.get("name") or top_error.get("error"), "Unknown Error")
        error_count = int(_to_text(top_error.get("count", "0"), "0") or "0")
        
        # Find correlation data for this error
        for corr in correlations:
            if isinstance(corr, dict) and corr.get("error") == error_name:
                services = _as_list(corr.get("services", []))
                service_names = [_sanitize_service_name(s) for s in services]
                service_str = ", ".join(service_names) if service_names else "Unknown"
                root_cause_text = (
                    f"<b>Primary Issue:</b> '{error_name}' is the most frequent error occurring {error_count} times. "
                    f"<b>Impact:</b> Affecting {len(service_names)} service(s): {service_str}. "
                    f"This error type requires immediate attention to restore system stability."
                )
                break
        else:
            root_cause_text = (
                f"<b>Primary Issue:</b> '{error_name}' is the most frequent error occurring {error_count} times. "
                f"<b>Impact:</b> This high-frequency error is significantly impacting system stability and requires investigation."
            )
    
    elements.append(Paragraph(root_cause_text, styles["SectionContent"]))
    elements.append(Spacer(1, 12))
    
    # ==================== RECOMMENDATIONS SECTION ====================
    elements.append(Paragraph("Recommendations", styles["SectionTitle"]))
    
    recommendations = _as_list(data.get("recommendations", []))
    if recommendations:
        for rec in recommendations:
            rec_text = _to_text(rec)
            # Parse priority if included
            if "high" in rec_text.lower():
                priority_tag = "<font color='red'><b>[HIGH]</b></font>"
            elif "medium" in rec_text.lower():
                priority_tag = "<font color='orange'><b>[MEDIUM]</b></font>"
            elif "low" in rec_text.lower():
                priority_tag = "<font color='green'><b>[LOW]</b></font>"
            else:
                priority_tag = ""
            
            elements.append(Paragraph(f"• {priority_tag} {rec_text}", styles["SectionContent"]))
    else:
        # Generate actionable recommendations based on data
        elements.append(Paragraph("• Monitor the identified high-frequency errors for patterns and trends.", styles["SectionContent"]))
        elements.append(Paragraph("• Review logs from the affected services to identify root causes.", styles["SectionContent"]))
        elements.append(Paragraph("• Consider implementing automated alerts for critical errors.", styles["SectionContent"]))
    
    elements.append(Spacer(1, 12))
    
    # ==================== BUILD PDF ====================
    doc.build(elements)
    
    # Clean up temporary chart files
    for chart_file in [error_dist_chart, service_stab_chart]:
        if chart_file and os.path.exists(chart_file):
            try:
                os.remove(chart_file)
            except:
                pass
    
    return file_name

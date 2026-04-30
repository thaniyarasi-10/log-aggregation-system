from datetime import datetime
import os
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors
from reportlab.lib.colors import HexColor
import matplotlib.pyplot as plt


# ------------------ HELPERS ------------------

def _as_list(value):
    return value if isinstance(value, list) else []


def _get_status(errors):
    if errors > 100:
        return "CRITICAL"
    elif errors >= 20:
        return "WARNING"
    return "HEALTHY"


# ------------------ SYSTEM SUMMARY ------------------

def _generate_system_summary(data, role):
    total_errors = data.get("total_errors", 0)
    total_warnings = data.get("total_warnings", 0)

    status = _get_status(total_errors)

    top_error = "None"
    if data.get("top_errors"):
        top_error = data["top_errors"][0]["error"]

    return (
        f"<b>Status:</b> {status}<br/>"
        f"<b>Errors:</b> {total_errors} | <b>Warnings:</b> {total_warnings}<br/>"
        f"<b>Top Error:</b> {top_error}<br/>"
        f"<b>Role:</b> {role.upper()}"
    )


# ------------------ ERROR ANALYSIS ------------------

def _analyze_error(error_msg):
    e = error_msg.lower()

    if "kafka" in e or "broker" in e:
        return {
            "cause": "Kafka broker unreachable / network issue",
            "fix": [
                "Check Kafka broker status",
                "Verify bootstrap servers",
                "Test network connectivity",
                "Check container/Docker network"
            ]
        }

    if "timeout" in e:
        return {
            "cause": "Service timeout / slow dependency",
            "fix": [
                "Check downstream latency",
                "Increase timeout config",
                "Inspect thread pool"
            ]
        }

    if "database" in e or "sql" in e:
        return {
            "cause": "Database connectivity issue",
            "fix": [
                "Verify DB connection string",
                "Check connection pool",
                "Analyze slow queries"
            ]
        }

    return {
        "cause": "Application error",
        "fix": [
            "Check logs",
            "Enable debug mode"
        ]
    }


# ------------------ GRAPHS ------------------

def _create_error_chart(data):
    errors = _as_list(data.get("top_errors"))

    if not errors:
        return None

    labels = [e["error"][:20] for e in errors]
    counts = [e["count"] for e in errors]

    plt.figure()
    plt.bar(labels, counts)
    plt.xticks(rotation=30)

    file = "error_chart.png"
    plt.tight_layout()
    plt.savefig(file)
    plt.close()

    return file


def _create_service_chart(data):
    services = _as_list(data.get("service_errors"))

    if not services:
        return None

    names = [s["service"] for s in services]
    counts = [s["count"] for s in services]

    plt.figure()
    plt.barh(names, counts)

    file = "service_chart.png"
    plt.tight_layout()
    plt.savefig(file)
    plt.close()

    return file


# ------------------ MAIN PDF ------------------

def generate_pdf_report(data, role):
    file_name = "summary.pdf"

    doc = SimpleDocTemplate(file_name)
    styles = getSampleStyleSheet()

    styles.add(ParagraphStyle(
        name='SectionTitle',
        fontSize=12,
        textColor=HexColor('#1F4788')
    ))

    elements = []

    # HEADER
    elements.append(Paragraph("<b>Log Summary Report</b>", styles["Title"]))
    elements.append(Spacer(1, 10))

    # TIME RANGE
    time_label = data.get("time_range_label", "N/A")
    elements.append(Paragraph(f"<b>Time Range:</b> {time_label}", styles["Normal"]))
    elements.append(Spacer(1, 10))

    # SYSTEM SUMMARY
    elements.append(Paragraph("System Summary", styles["SectionTitle"]))
    elements.append(Paragraph(_generate_system_summary(data, role), styles["Normal"]))
    elements.append(Spacer(1, 10))

    # ROLE SECTION
    elements.append(Paragraph("Access Overview", styles["SectionTitle"]))

    if role.lower() == "admin":
        for s in _as_list(data.get("services")):
            elements.append(Paragraph(f"• {s}", styles["Normal"]))

        for r in _as_list(data.get("requests")):
            elements.append(Paragraph(
                f"• {r['user']} → {r['service']} → {r['status']}",
                styles["Normal"]
            ))
    else:
        for s in _as_list(data.get("mapped_services")):
            elements.append(Paragraph(f"• {s}", styles["Normal"]))

    elements.append(Spacer(1, 10))

    # SERVICE TABLE
    elements.append(Paragraph("Service Health", styles["SectionTitle"]))

    table_data = [["Service", "Errors"]]

    for s in _as_list(data.get("service_errors")):
        table_data.append([s["service"], str(s["count"])])

    table = Table(table_data)
    table.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.5, colors.grey)
    ]))

    elements.append(table)
    elements.append(Spacer(1, 10))

    # GRAPHS
    err_chart = _create_error_chart(data)
    svc_chart = _create_service_chart(data)

    if err_chart and svc_chart:
        elements.append(Paragraph("Visual Analysis", styles["SectionTitle"]))
        elements.append(Image(err_chart, width=250, height=200))
        elements.append(Image(svc_chart, width=250, height=200))
        elements.append(Spacer(1, 10))

    # TOP ERROR ANALYSIS
    elements.append(Paragraph("Top Error Analysis", styles["SectionTitle"]))

    if data.get("top_errors"):
        top_error = data["top_errors"][0]["error"]
        analysis = _analyze_error(top_error)

        elements.append(Paragraph(f"<b>Error:</b> {top_error}", styles["Normal"]))
        elements.append(Paragraph(f"<b>Cause:</b> {analysis['cause']}", styles["Normal"]))

        elements.append(Paragraph("<b>Fix:</b>", styles["Normal"]))
        for f in analysis["fix"]:
            elements.append(Paragraph(f"• {f}", styles["Normal"]))

    elements.append(Spacer(1, 10))

    # ACTION ITEMS
    elements.append(Paragraph("Action Items", styles["SectionTitle"]))

    for err in _as_list(data.get("top_errors"))[:3]:
        analysis = _analyze_error(err["error"])

        elements.append(Paragraph(f"<b>{err['error']}</b>", styles["Normal"]))
        for step in analysis["fix"]:
            elements.append(Paragraph(f"→ {step}", styles["Normal"]))

        elements.append(Spacer(1, 5))

    # BUILD
    doc.build(elements)

    return file_name
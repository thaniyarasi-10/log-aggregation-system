"""
generate_report.py
==================
Production-ready AI agent log-analysis PDF report.

─────────────────────────────────────────────────────────────────────────────
summary.py  —  PRESENTATION / PDF LAYER

Responsibilities:
  • Render the enhanced PDF report from a structured analytics dict
  • All styling, layout, charts, tables, KPI cards, recommendation cards
  • Role-based column selection (admin vs dev)

This module does NOT fetch logs or compute analytics.
All analytics are computed by role_agent.py (the data layer) and passed in
via the `data` dict argument to generate_pdf_report().
─────────────────────────────────────────────────────────────────────────────

Role behaviour
--------------
  admin : ALL services visible.
          Columns: Service | Errors | Warnings | Req Approved | Req Rejected | Users Added
          All columns always rendered; shows 0 when no requests/users.

  dev   : Only dev-mapped services visible.
          Columns: Service | Errors | Warnings | Service Requests | P99 Latency (ms)
          All columns always rendered; shows 0 / "—" when no data.

Sections (in order)
-------------------
  1  Header
  2  Services            <- role-based, listed FIRST
  3  System Health Overview  (KPI strip)
  4  Key Insights
  5  Top Errors          <- includes Service column
  6  Analysis Visualisations
  7  Recommendations & Fixes  <- per-error Why + numbered How-to-fix cards
  8  Footer
"""

from datetime import datetime, timezone
from pathlib import Path
import os

from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import letter
from reportlab.platypus import (
    Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle,
    KeepTogether, Image, HRFlowable,
)
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

# Output directory — same location used by role_agent.py
REPORT_DIR = Path(__file__).resolve().parent / "generated_reports"
REPORT_DIR.mkdir(parents=True, exist_ok=True)

# ── Colour palette ─────────────────────────────────────────────────────────────
C_NAVY    = HexColor("#0D1B2A")
C_BLUE    = HexColor("#1A56DB")
C_BLUE_LT = HexColor("#EBF3FF")
C_TEAL    = HexColor("#0E9F6E")
C_TEAL_LT = HexColor("#E3FCEF")
C_RED     = HexColor("#E02424")
C_RED_LT  = HexColor("#FDE8E8")
C_AMBER   = HexColor("#D97706")
C_AMBER_LT= HexColor("#FEF3C7")
C_GREY    = HexColor("#6B7280")
C_GREY_LT = HexColor("#F9FAFB")
C_BORDER  = HexColor("#E5E7EB")
C_WHITE   = HexColor("#FFFFFF")
C_DARK    = HexColor("#111827")

# ── Utility helpers ────────────────────────────────────────────────────────────
def _as_list(v):
    return v if isinstance(v, list) else []

def _to_text(v, default="N/A"):
    if v is None: return default
    s = str(v).strip()
    return s if s else default

def _int(v, default=0):
    try: return int(str(v).strip() or default)
    except (ValueError, TypeError): return default

def _sanitize(s):
    if not s or str(s).strip() in ("", "None"): return "Unknown Service"
    return str(s).strip()

def _overall_status(errors):
    if errors > 100: return "CRITICAL"
    if errors >= 20: return "WARNING"
    return "HEALTHY"

def _status_color(status):
    return {"CRITICAL":"#E02424","WARNING":"#D97706","HEALTHY":"#0E9F6E"}.get(status,"#6B7280")

def _categorize_error(msg):
    m = msg.lower()
    if any(x in m for x in ["kafka","connection","network","timeout","refused","socket"]):
        return "Infrastructure"
    if any(x in m for x in ["db","database","sql","mongo","query","deadlock"]):
        return "Database"
    if any(x in m for x in ["elasticsearch","elastic","redis","cache"]):
        return "External"
    return "Application"

def _colored(text, hex_color, bold=False):
    inner = f"<b>{text}</b>" if bold else text
    return f"<font color='{hex_color}'>{inner}</font>"

# ── Role-based service data ────────────────────────────────────────────────────
def _admin_service_rows(data):
    rows = []
    for svc in _as_list(data.get("services", [])):
        if not isinstance(svc, dict): continue
        rows.append({
            "name":         _sanitize(svc.get("name")),
            "errors":       _int(svc.get("errors",            0)),
            "warnings":     _int(svc.get("warnings",          0)),
            "req_approved": _int(svc.get("requests_approved", 0)),
            "req_rejected": _int(svc.get("requests_rejected", 0)),
            "users_added":  _int(svc.get("users_added",       0)),
        })
    if not rows:
        for svc in _as_list(data.get("unstable_services", [])):
            if isinstance(svc, dict):
                rows.append({"name":_sanitize(svc.get("service")),
                             "errors":_int(svc.get("count",0)),
                             "warnings":0,"req_approved":0,"req_rejected":0,"users_added":0})
    return rows

def _dev_service_rows(data):
    rows = []
    for svc in _as_list(data.get("services", [])):
        if not isinstance(svc, dict): continue
        rows.append({
            "name":      _sanitize(svc.get("name")),
            "errors":    _int(svc.get("errors",         0)),
            "warnings":  _int(svc.get("warnings",       0)),
            "req_total": _int(svc.get("requests_total", 0)),
            "p99":       _to_text(svc.get("p99_latency_ms"), "—"),
        })
    if not rows:
        for svc in _as_list(data.get("unstable_services", [])):
            if isinstance(svc, dict):
                rows.append({"name":_sanitize(svc.get("service")),
                             "errors":_int(svc.get("count",0)),
                             "warnings":0,"req_total":0,"p99":"—"})
    return rows

# ── Insights ───────────────────────────────────────────────────────────────────
def _generate_insights(data, role):
    insights = []
    total_errors = _int(data.get("total_errors",   0))
    total_warn   = _int(data.get("total_warnings", 0))
    total_logs   = _int(data.get("total_logs",     0))
    top_errors   = _as_list(data.get("top_errors", []))
    error_rate   = (total_errors / max(total_logs, 1)) * 100

    if error_rate > 5:
        insights.append(("🔴 High Error Rate",
            f"Error rate is {error_rate:.1f}% ({total_errors}/{total_logs} logs) — "
            "exceeds the 5% critical threshold. Immediate investigation required."))
    elif error_rate > 1:
        insights.append(("🟡 Elevated Error Rate",
            f"Error rate is {error_rate:.1f}% ({total_errors}/{total_logs} logs) — "
            "above the 1% caution threshold. Monitor closely."))
    else:
        insights.append(("🟢 Error Rate Normal",
            f"Error rate is {error_rate:.2f}% ({total_errors}/{total_logs} logs) — "
            "within acceptable bounds."))

    if total_errors > 0 and total_warn > total_errors * 10:
        ratio = total_warn // max(total_errors, 1)
        insights.append(("⚠️ Warning Saturation",
            f"{total_warn} warnings vs {total_errors} errors ({ratio}:1 ratio). "
            "High warning volume often precedes failures — inspect deprecation "
            "and saturation signals now."))
    elif total_warn > 50 and total_errors == 0:
        insights.append(("⚠️ Warning Saturation",
            f"{total_warn} warnings with no current errors. "
            "Review deprecation notices and resource-saturation signals proactively."))

    if top_errors and isinstance(top_errors[0], dict):
        top  = top_errors[0]
        name = _to_text(top.get("name") or top.get("error"), "Unknown")
        cnt  = _int(top.get("count", 0))
        pct  = (cnt / max(total_errors, 1)) * 100
        insights.append(("🔁 Dominant Error Pattern",
            f"'{name[:70]}' accounts for {pct:.0f}% of all errors ({cnt} occurrences). "
            "Resolving this single error type would eliminate the majority of error volume."))

    if role == "admin":
        affected = _int(data.get("affected_services", 0))
        if affected > 0:
            insights.append(("🏢 Cross-Service Impact",
                f"{affected} service(s) are currently reporting errors or warnings. "
                "Review the service dependency graph for potential cascading failures."))

    if not insights:
        insights.append(("✅ System Healthy",
            "All metrics are within normal operating parameters."))
    return insights

# ── Recommendation knowledge-base ──────────────────────────────────────────────
_ERROR_KB = {
    "aggregated": (
        "The log-controller-service batched a large number of historical entries into a "
        "single event — typically caused by a log pipeline restart or catch-up flush "
        "after a service crash or rolling restart.",
        [
            "Cross-reference the timestamp with recent deployments, pod restarts, or "
            "crash events: kubectl describe pod <log-controller-pod>.",
            "Check log-controller-service buffer config (max_buffer_size, flush_interval) — "
            "large buffers produce bulk flushes on restart.",
            "Review container restart history to confirm whether a crash triggered the flush.",
            "If recurring, reduce the buffer window or migrate to a real-time log shipper "
            "(Fluentd / Logstash) to avoid batching.",
        ],
    ),
    "kafka": (
        "The Kafka broker is unreachable or the consumer group has significant lag. "
        "Common causes: broker downtime, network partition, or misconfigured topic/ACL.",
        [
            "Verify broker availability: "
            "kafka-broker-api-versions.sh --bootstrap-server <host>:9092",
            "Inspect consumer group lag: kafka-consumer-groups.sh "
            "--bootstrap-server <host>:9092 --describe --group <group-name>",
            "Review broker logs for leader election failures or ISR shrinkage.",
            "If lag is high, increase consumer instances or tune max.poll.records "
            "and max.poll.interval.ms in consumer config.",
            "Verify security groups / firewall rules allow port 9092 between "
            "service pods and broker nodes.",
        ],
    ),
    "connection": (
        "The service cannot open a TCP connection to a downstream dependency. "
        "Common causes: crashed downstream service, wrong hostname/port in config, "
        "or a blocked firewall / NetworkPolicy rule.",
        [
            "Verify the target is reachable from the pod: nc -zv <host> <port>",
            "Confirm env vars / ConfigMaps for host and port are correct in the "
            "failing service Deployment.",
            "Check Kubernetes NetworkPolicy and cloud security groups for ingress/egress "
            "rules between affected namespaces.",
            "Inspect connection pool settings (max-size, timeout, idle-eviction) — "
            "an exhausted pool presents identically to an unreachable host.",
            "Restart the downstream dependency if crashed and confirm it passes its "
            "readiness probe before re-routing traffic.",
        ],
    ),
    "timeout": (
        "A request exceeded the configured timeout. Common causes: slow downstream "
        "queries or APIs, overloaded services, or timeout values set too low for "
        "actual p99 latency.",
        [
            "Correlate the timeout timestamp with APM traces or request logs to "
            "identify the exact slow call.",
            "Profile the downstream operation (DB query, API call) to find the bottleneck.",
            "Tune connect_timeout and read_timeout in service config based on measured "
            "p99 latency with appropriate headroom.",
            "Add a circuit breaker (Resilience4j / Hystrix) so repeated timeouts trip "
            "the breaker and prevent thread exhaustion.",
            "Scale the downstream service horizontally if it is consistently overloaded.",
        ],
    ),
    "database": (
        "Database query failure, connection pool exhaustion, or schema / migration "
        "mismatch. Common causes: missing indexes, pool saturation, or an unapplied migration.",
        [
            "Run EXPLAIN ANALYZE on the failing query to identify full-table scans "
            "or missing indexes.",
            "Check DB connection pool metrics — a high 'waiting' count indicates "
            "exhaustion; increase pool size or reduce query latency.",
            "Verify pending migrations are applied: compare the schema version in "
            "code against the live database.",
            "Review recent DDL changes (ALTER TABLE, DROP INDEX) for regressions.",
            "Enable slow-query logging (threshold > 500 ms) for continuous visibility.",
        ],
    ),
    "sql": (
        "SQL syntax error or constraint violation. Common causes: bad migration, "
        "unexpected null value, data-type mismatch, or a dropped column still "
        "referenced in application code.",
        [
            "Capture the full failing SQL statement from the error logs.",
            "Reproduce the query in staging with equivalent data to isolate the failure.",
            "Check for NOT NULL or UNIQUE constraint violations in recent inserts / updates.",
            "Review recent migration scripts for: dropped columns still referenced in "
            "queries, renamed fields, or type changes.",
        ],
    ),
    "mongo": (
        "MongoDB operation failure. Common causes: missing index causing a collection "
        "scan, a replica set member down, or a document exceeding the 16 MB BSON limit.",
        [
            "Run db.currentOp() to identify long-running operations and kill blockers.",
            "Use explain('executionStats') on the failing query — COLLSCAN instead of "
            "IXSCAN indicates a missing index.",
            "Check replica set health: rs.status() — look for RECOVERING or DOWN members.",
            "Verify document sizes are within the 16 MB BSON limit.",
        ],
    ),
    "elasticsearch": (
        "Elasticsearch cluster error. Common causes: shard allocation failure, "
        "JVM heap pressure > 85%, or an index mapping conflict.",
        [
            "Check cluster health: GET /_cluster/health — red = data loss risk, "
            "yellow = replica unassigned.",
            "Identify unassigned shards: "
            "GET /_cat/shards?h=index,shard,state,unassigned.reason&s=state",
            "Check JVM heap: GET /_nodes/stats/jvm — if heap > 85%, increase node "
            "memory or reduce index count.",
            "Look for mapping conflicts: GET /<index>/_mapping and compare field types "
            "across index versions.",
            "If disk is full, add nodes or delete old indices via an ILM policy.",
        ],
    ),
    "null": (
        "NullPointerException or missing expected value — an object was accessed before "
        "being initialised, or optional data was assumed to be present.",
        [
            "Locate the full stack trace in the logs to identify the exact class and line.",
            "Add null checks or use Optional<> / Optional.ofNullable() before "
            "dereferencing the object.",
            "Add input validation at the API / service boundary so null values are "
            "rejected early with a descriptive error.",
            "Write a unit test reproducing the null condition to prevent regression.",
        ],
    ),
    "memory": (
        "JVM or process out-of-memory error. Common causes: memory leak, unbounded "
        "collection or cache, or an undersized heap.",
        [
            "Capture a heap dump on the next occurrence: "
            "jmap -dump:live,format=b,file=heap.hprof <pid>",
            "Analyse with Eclipse MAT or VisualVM to find the largest retained "
            "object graph.",
            "Look for unbounded collections (Maps, Lists) that grow without eviction.",
            "Set -Xms and -Xmx to the same value to prevent resize pauses "
            "(e.g. -Xms512m -Xmx1g).",
            "Replace unbounded maps with a bounded cache with eviction policy "
            "(e.g. Caffeine, Guava Cache).",
        ],
    ),
}

def _generate_recommendations(data):
    recs  = []
    added = set()
    total_warn   = _int(data.get("total_warnings", 0))
    total_errors = _int(data.get("total_errors",   0))

    for item in _as_list(data.get("top_errors", []))[:5]:
        if not isinstance(item, dict): continue
        error_name  = _to_text(item.get("name") or item.get("error"), "Unknown Error")
        error_count = _int(item.get("count", 0))
        svc_raw     = _sanitize(item.get("service", item.get("affected_service", "")))
        svc         = svc_raw if svc_raw != "Unknown Service" else None
        error_lower = error_name.lower()
        matched_key = next((k for k in _ERROR_KB if k in error_lower), None)

        if matched_key and matched_key not in added:
            why, steps = _ERROR_KB[matched_key]
            recs.append({"priority":"HIGH","error":error_name,"service":svc,
                         "count":error_count,"why":why,"steps":steps})
            added.add(matched_key)
        elif not matched_key and error_name not in added:
            recs.append({"priority":"MEDIUM","error":error_name,"service":svc,
                         "count":error_count,
                         "why":"Application-level error — exact root cause requires "
                               "log inspection to identify the originating module.",
                         "steps":[
                             f"Search for the full stack trace of '{error_name[:55]}' "
                             "in the log management system.",
                             "Identify the service, class, and method that originates this error.",
                             "Determine if the error is transient (add retry with exponential "
                             "back-off) or deterministic (fix the root cause directly).",
                             "Add a targeted alert rule so future occurrences are flagged immediately.",
                         ]})
            added.add(error_name)

    if total_warn > total_errors * 5:
        recs.append({"priority":"MEDIUM","error":"High Warning Volume","service":None,
                     "count":total_warn,
                     "why":"Warning count is disproportionately high relative to errors, "
                           "which often signals impending failures or unresolved deprecations.",
                     "steps":[
                         "Filter warning logs by message pattern to identify the top warning types.",
                         "Investigate deprecation warnings — APIs or configs that will break "
                         "in a future version.",
                         "Look for resource-saturation warnings (disk, memory, CPU) and "
                         "provision capacity if needed.",
                         "Set up a warning-rate alert (e.g. > 50 per minute) to catch "
                         "spikes before they escalate to errors.",
                     ]})

    for r in _as_list(data.get("recommendations", [])):
        rt = _to_text(r)
        if rt:
            p = ("HIGH" if "high" in rt.lower() else
                 "MEDIUM" if "medium" in rt.lower() else "LOW")
            recs.append({"priority":p,"error":"Operational Recommendation",
                         "service":None,"count":0,"why":"","steps":[rt]})

    if not recs:
        recs.append({"priority":"LOW","error":"No Active Issues","service":None,"count":0,
                     "why":"System is operating within normal parameters.",
                     "steps":[
                         "Continue monitoring. Set alerts for error rate > 1% and "
                         "warning rate > 10%.",
                         "Schedule a routine weekly log review to catch slow-growing "
                         "issues early.",
                     ]})
    return recs

# ── Charts ─────────────────────────────────────────────────────────────────────
def _chart_error_dist(data):
    top_errors = _as_list(data.get("top_errors", []))
    if not top_errors: return None
    cats = {}
    for item in top_errors:
        if isinstance(item, dict):
            n = _to_text(item.get("name") or item.get("error"), "Unknown")
            c = _int(item.get("count", 0))
            cats[_categorize_error(n)] = cats.get(_categorize_error(n), 0) + c
    if not cats: return None
    fig, ax = plt.subplots(figsize=(3.2, 2.5), dpi=110)
    palette = ["#E02424","#1A56DB","#0E9F6E","#D97706"]
    bars = ax.bar(list(cats.keys()), list(cats.values()),
                  color=palette[:len(cats)], width=0.5, edgecolor="white")
    for bar in bars:
        h = bar.get_height()
        ax.text(bar.get_x()+bar.get_width()/2, h+0.05, str(int(h)),
                ha="center", va="bottom", fontsize=7, fontweight="bold")
    ax.set_xlabel("Category", fontsize=8); ax.set_ylabel("Count", fontsize=8)
    ax.set_title("Error Distribution by Category", fontsize=9, fontweight="bold", pad=8)
    ax.spines["top"].set_visible(False); ax.spines["right"].set_visible(False)
    plt.xticks(rotation=30, ha="right", fontsize=7); plt.yticks(fontsize=7)
    path = str(REPORT_DIR / "error_dist_tmp.png")
    plt.tight_layout(); plt.savefig(path, dpi=110, bbox_inches="tight"); plt.close()
    return path

def _chart_service_health(data):
    svcs = _as_list(data.get("unstable_services", []))
    if not svcs:
        svcs = [{"service":_sanitize(s.get("name","?")),"count":_int(s.get("errors",0))}
                for s in _as_list(data.get("services",[]))
                if isinstance(s,dict) and _int(s.get("errors",0)) > 0]
    if not svcs: return None
    names, counts = [], []
    for item in svcs[:7]:
        if isinstance(item, dict):
            names.append(_sanitize(item.get("service", item.get("name","?"))))
            counts.append(_int(item.get("count", 0)))
    if not names: return None
    fig, ax = plt.subplots(figsize=(3.2, 2.5), dpi=110)
    clrs = ["#E02424" if c>5 else "#D97706" if c>2 else "#1A56DB" for c in counts]
    ax.barh(names, counts, color=clrs, edgecolor="white")
    for bar in ax.patches:
        w = bar.get_width()
        ax.text(w+0.02, bar.get_y()+bar.get_height()/2, str(int(w)),
                va="center", ha="left", fontsize=7, fontweight="bold")
    ax.set_xlabel("Error Count", fontsize=8)
    ax.set_title("Errors by Service", fontsize=9, fontweight="bold", pad=8)
    ax.invert_yaxis()
    ax.spines["top"].set_visible(False); ax.spines["right"].set_visible(False)
    plt.yticks(fontsize=7); plt.xticks(fontsize=7)
    path = str(REPORT_DIR / "service_health_tmp.png")
    plt.tight_layout(); plt.savefig(path, dpi=110, bbox_inches="tight"); plt.close()
    return path

# ── Style sheet ────────────────────────────────────────────────────────────────
def _build_styles():
    base = getSampleStyleSheet()
    for sty in [
        ParagraphStyle("RTitle",   parent=base["Normal"], fontSize=20,
                                              fontName="Helvetica-Bold", textColor=C_NAVY,
                       spaceAfter=2, leading=24),
        ParagraphStyle("SecHdr",   parent=base["Normal"], fontSize=11,
                       fontName="Helvetica-Bold", textColor=C_BLUE,
                       spaceBefore=10, spaceAfter=4, leading=14),
        ParagraphStyle("SubHdr",   parent=base["Normal"], fontSize=9,
                       fontName="Helvetica-Bold", textColor=C_DARK,
                       spaceBefore=3, spaceAfter=2, leading=12),
        ParagraphStyle("Body",     parent=base["Normal"], fontSize=8.5,
                       fontName="Helvetica", textColor=C_DARK,
                       spaceAfter=3, leading=12),
        ParagraphStyle("BodySm",   parent=base["Normal"], fontSize=7.5,
                       fontName="Helvetica", textColor=C_GREY,
                       spaceAfter=2, leading=10),
        ParagraphStyle("StepBody", parent=base["Normal"], fontSize=8,
                       fontName="Helvetica", textColor=C_DARK,
                       spaceAfter=2, leading=11, leftIndent=2),
        ParagraphStyle("InsTitle", parent=base["Normal"], fontSize=8.5,
                       fontName="Helvetica-Bold", textColor=C_DARK,
                       spaceAfter=1, leading=11),
        ParagraphStyle("TblHdr",   parent=base["Normal"], fontSize=8,
                       fontName="Helvetica-Bold", textColor=C_WHITE,
                       leading=10),
        ParagraphStyle("RecTitle", parent=base["Normal"], fontSize=9,
                       fontName="Helvetica-Bold", textColor=C_DARK,
                       spaceAfter=1, leading=12),
        ParagraphStyle("WhyBody",  parent=base["Normal"], fontSize=8,
                       fontName="Helvetica", textColor=HexColor("#374151"),
                       spaceAfter=2, leading=11),
        ParagraphStyle("TSLabel",  parent=base["Normal"], fontSize=7,
                       fontName="Helvetica-Bold", textColor=C_WHITE,
                       leading=9),
    ]:
        base.add(sty)
    return base

# ── Layout helpers ─────────────────────────────────────────────────────────────
_PAGE_W  = 6.6 * inch

_BASE_TBL = [
    ("GRID",           (0, 0), (-1, -1), 0.4, C_BORDER),
    ("FONTSIZE",       (0, 0), (-1, -1), 8),
    ("VALIGN",         (0, 0), (-1, -1), "MIDDLE"),
    ("TOPPADDING",     (0, 0), (-1, -1), 5),
    ("BOTTOMPADDING",  (0, 0), (-1, -1), 5),
    ("LEFTPADDING",    (0, 0), (-1, -1), 6),
    ("RIGHTPADDING",   (0, 0), (-1, -1), 6),
    ("ROWBACKGROUNDS", (0, 1), (-1, -1), [C_WHITE, C_GREY_LT]),
]

def _hdr_style(bg=None):
    bg = bg or C_NAVY
    return [
        ("BACKGROUND", (0, 0), (-1, 0), bg),
        ("TEXTCOLOR",  (0, 0), (-1, 0), C_WHITE),
        ("FONTNAME",   (0, 0), (-1, 0), "Helvetica-Bold"),
    ]

def _divider():
    return HRFlowable(width="100%", thickness=0.5, color=C_BORDER,
                      spaceAfter=6, spaceBefore=4)

def _section_header(title, S):
    return [Paragraph(title, S["SecHdr"]), _divider()]

def _priority_badge_colors(p):
    return {
        "HIGH":   ("#FDE8E8", "#E02424"),
        "MEDIUM": ("#FEF3C7", "#D97706"),
        "LOW":    ("#E3FCEF", "#0E9F6E"),
    }.get(p, ("#F9FAFB", "#6B7280"))

# ── Main generator ─────────────────────────────────────────────────────────────
def generate_pdf_report(data: dict, role: str = "admin") -> str:
    role       = role.lower()
    role_label = role.upper()
    file_name  = str(REPORT_DIR / f"role-based-log-summary-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S%f')}.pdf")

    # Proper timestamp: prefer data["date"], fallback to now in IST-friendly UTC
    raw_date  = data.get("date", "")
    if raw_date:
        try:
            # Parse ISO format and reformat nicely
            dt = datetime.fromisoformat(str(raw_date).replace("Z", "+00:00"))
            date_text = dt.strftime("%d %b %Y, %H:%M UTC")
        except Exception:
            date_text = str(raw_date)
    else:
        date_text = datetime.now(timezone.utc).strftime("%d %b %Y, %H:%M UTC")

    generated_at = datetime.now(timezone.utc).strftime("%d %b %Y, %H:%M UTC")

    doc = SimpleDocTemplate(
        file_name, pagesize=letter,
        topMargin=0.5*inch, bottomMargin=0.5*inch,
        leftMargin=0.6*inch, rightMargin=0.6*inch,
    )
    S   = _build_styles()
    els = []

    # Pre-compute shared values
    env          = _to_text(data.get("environment"),      "Production")
    time_label   = _to_text(data.get("time_range_label"), "Last 24 hours")
    total_errors = _int(data.get("total_errors",   0))
    total_warn   = _int(data.get("total_warnings", 0))
    total_logs   = _int(data.get("total_logs",     0))
    total_info   = _int(data.get("total_info",     0))
    status       = _overall_status(total_errors)
    s_color      = _status_color(status)
    s_bg         = (C_RED_LT   if status == "CRITICAL" else
                    C_AMBER_LT if status == "WARNING"  else C_TEAL_LT)
    top_errors   = _as_list(data.get("top_errors", []))

    # ══ 1. HEADER ══════════════════════════════════════════════════════════════
    # Role badge pill
    role_bg  = {"admin": "#0D1B2A", "dev": "#1A56DB"}.get(role, "#6B7280")
    role_pill = Table(
        [[Paragraph(f"<b>{role_label}</b>", S["TSLabel"])]],
        colWidths=[0.6 * inch],
    )
    role_pill.setStyle(TableStyle([
        ("BACKGROUND",    (0, 0), (-1, -1), HexColor(role_bg)),
        ("ALIGN",         (0, 0), (-1, -1), "CENTER"),
        ("VALIGN",        (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING",    (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ("ROUNDEDCORNERS", [3, 3, 3, 3]),
    ]))

    hdr = Table([[
        Table([
            [Paragraph("<b>Log Analysis Report</b>", S["RTitle"])],
            [Table([[role_pill,
                     Paragraph(f"<font color='#6B7280'>{env}  •  {time_label}</font>",
                               S["BodySm"])]],
                   colWidths=[0.7*inch, 3.1*inch])],
        ], colWidths=[4.0*inch]),
        Table([
            [Paragraph("<b>Report Date</b>",   S["BodySm"])],
            [Paragraph(date_text,              S["Body"])],
            [Paragraph("<b>Generated At</b>",  S["BodySm"])],
            [Paragraph(generated_at,           S["Body"])],
            [Paragraph(f"<b>Role:</b> {role_label}  |  <b>Env:</b> {env}", S["BodySm"])],
        ], colWidths=[2.6*inch]),
    ]], colWidths=[4.2*inch, 2.6*inch])
    hdr.setStyle(TableStyle([
        ("VALIGN",       (0, 0), (-1, -1), "TOP"),
        ("ALIGN",        (1, 0), (1,  0),  "RIGHT"),
        ("BACKGROUND",   (0, 0), (-1, -1), C_BLUE_LT),
        ("TOPPADDING",   (0, 0), (-1, -1), 10),
        ("BOTTOMPADDING",(0, 0), (-1, -1), 10),
        ("LEFTPADDING",  (0, 0), (-1, -1), 10),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
        ("LINEBEFORE",   (0, 0), (0, -1),  4, C_BLUE),
    ]))
    els.append(hdr)
    els.append(Spacer(1, 14))

    # ══ 2. SERVICES
    access_note = ("(All Services — Admin Access)" if role == "admin"
                   else "(Mapped Services — DEV Access)")
    els += _section_header(f"Services  {access_note}", S)

    if role == "admin":
        col_headers = ["Service", "Errors", "Warnings",
                       "Req Approved", "Req Rejected", "Users Added"]
        col_widths  = [1.9*inch, 0.65*inch, 0.75*inch, 1.0*inch, 1.0*inch, 0.9*inch]
        tbl_data    = [[Paragraph(h, S["TblHdr"]) for h in col_headers]]

        svc_rows = _admin_service_rows(data)
        if svc_rows:
            for r in svc_rows:
                e_str   = _colored(str(r["errors"]),       "#E02424", bold=True) if r["errors"]       > 0 else "0"
                w_str   = _colored(str(r["warnings"]),     "#D97706", bold=True) if r["warnings"]     > 0 else "0"
                rej_str = _colored(str(r["req_rejected"]), "#E02424", bold=True) if r["req_rejected"] > 0 else "0"
                usr_str = _colored(str(r["users_added"]),  "#0E9F6E", bold=True) if r["users_added"]  > 0 else "0"
                tbl_data.append([
                    Paragraph(r["name"],              S["Body"]),
                    Paragraph(e_str,                  S["Body"]),
                    Paragraph(w_str,                  S["Body"]),
                    Paragraph(str(r["req_approved"]), S["Body"]),
                    Paragraph(rej_str,                S["Body"]),
                    Paragraph(usr_str,                S["Body"]),
                ])
        else:
            # Always render all columns with 0 — never drop columns
            tbl_data.append([
                Paragraph("No service data available", S["BodySm"]),
                Paragraph("0", S["BodySm"]),
                Paragraph("0", S["BodySm"]),
                Paragraph("0", S["BodySm"]),
                Paragraph("0", S["BodySm"]),
                Paragraph("0", S["BodySm"]),
            ])

    else:  # dev — only mapped services
        col_headers = ["Service", "Errors", "Warnings",
                       "Service Requests", "P99 Latency (ms)"]
        col_widths  = [2.1*inch, 0.75*inch, 0.80*inch, 1.3*inch, 1.25*inch]
        tbl_data    = [[Paragraph(h, S["TblHdr"]) for h in col_headers]]

        # Filter: only services in the dev's mapped_services list (if provided)
        mapped       = _as_list(data.get("mapped_services", []))
        svc_rows     = _dev_service_rows(data)
        if mapped:
            svc_rows = [r for r in svc_rows if r["name"] in mapped]

        if svc_rows:
            for r in svc_rows:
                e_str = _colored(str(r["errors"]),   "#E02424", bold=True) if r["errors"]   > 0 else "0"
                w_str = _colored(str(r["warnings"]), "#D97706", bold=True) if r["warnings"] > 0 else "0"
                tbl_data.append([
                    Paragraph(r["name"],           S["Body"]),
                    Paragraph(e_str,               S["Body"]),
                    Paragraph(w_str,               S["Body"]),
                    Paragraph(str(r["req_total"]), S["Body"]),
                    Paragraph(str(r["p99"]),       S["Body"]),
                ])
        else:
            # Always render all columns with 0 / — — never drop columns
            tbl_data.append([
                Paragraph("No mapped services available", S["BodySm"]),
                Paragraph("0", S["BodySm"]),
                Paragraph("0", S["BodySm"]),
                Paragraph("0", S["BodySm"]),
                Paragraph("—", S["BodySm"]),
            ])

    svc_tbl = Table(tbl_data, colWidths=col_widths)
    svc_tbl.setStyle(TableStyle(
        _BASE_TBL + _hdr_style(C_BLUE) + [
            ("ALIGN", (0, 0), (0, -1),  "LEFT"),
            ("ALIGN", (1, 0), (-1, -1), "CENTER"),
        ]
    ))
    els.append(svc_tbl)
    els.append(Spacer(1, 14))

    # ══ 3. SYSTEM HEALTH OVERVIEW (KPI strip) ══════════════════════════════════
    els += _section_header(" System Health Overview", S)

    kpis = [
        ("Total Logs",     str(total_logs),   C_BLUE_LT,  "#1A56DB"),
        ("Errors",         str(total_errors), C_RED_LT,   "#E02424"),
        ("Warnings",       str(total_warn),   C_AMBER_LT, "#D97706"),
        ("Info",           str(total_info),   C_TEAL_LT,  "#0E9F6E"),
        ("Overall Status", status,            s_bg,        s_color),
    ]
    kpi_cells = []
    for label, value, bg, fg in kpis:
        cell = Table([
            [Paragraph(f"<font color='{fg}'><b>{value}</b></font>", S["SecHdr"])],
            [Paragraph(label, S["BodySm"])],
        ], colWidths=[1.28 * inch])
        cell.setStyle(TableStyle([
            ("BACKGROUND",    (0, 0), (-1, -1), bg),
            ("ALIGN",         (0, 0), (-1, -1), "CENTER"),
            ("VALIGN",        (0, 0), (-1, -1), "MIDDLE"),
            ("TOPPADDING",    (0, 0), (-1, -1), 8),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
        ]))
        kpi_cells.append(cell)

    kpi_row = Table([kpi_cells], colWidths=[1.35 * inch] * 5)
    kpi_row.setStyle(TableStyle([
        ("ALIGN",        (0, 0), (-1, -1), "CENTER"),
        ("LEFTPADDING",  (0, 0), (-1, -1), 3),
        ("RIGHTPADDING", (0, 0), (-1, -1), 3),
    ]))
    els.append(kpi_row)
    els.append(Spacer(1, 14))

    # ══ 4. KEY INSIGHTS ════════════════════════════════════════════════════════
    els += _section_header("Key Insights", S)
    for title, body in _generate_insights(data, role):
        card = Table([
            [Paragraph(title, S["InsTitle"])],
            [Paragraph(body,  S["Body"])],
        ], colWidths=[_PAGE_W])
        card.setStyle(TableStyle([
            ("BACKGROUND",    (0, 0), (-1, -1), C_BLUE_LT),
            ("LINEBEFORE",    (0, 0), (0, -1),  3, C_BLUE),
            ("TOPPADDING",    (0, 0), (-1, -1), 6),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ("LEFTPADDING",   (0, 0), (-1, -1), 10),
            ("RIGHTPADDING",  (0, 0), (-1, -1), 10),
        ]))
        els.append(card)
        els.append(Spacer(1, 5))
    els.append(Spacer(1, 9))

    # ══ 5. TOP ERRORS (# | Error Message | Service | Category | Count | %) ════
    els += _section_header(" Top Errors (by Frequency)", S)

    err_hdr = [Paragraph(h, S["TblHdr"]) for h in
               ["#", "Error Message", "Service", "Category", "Count", "% of Total"]]
    err_rows_data = [err_hdr]

    for i, item in enumerate(top_errors[:10], 1):
        if not isinstance(item, dict): continue
        name    = _to_text(item.get("name") or item.get("error"), "Unknown")
        count   = _int(item.get("count", 0))
        svc_raw = _sanitize(item.get("service", item.get("affected_service", "")))
        svc_disp= svc_raw if svc_raw != "Unknown Service" else "—"
        cat     = _categorize_error(name)
        pct     = f"{(count / max(total_errors, 1)) * 100:.1f}%"
        short   = name[:44] + "…" if len(name) > 44 else name
        cat_clr = {"Infrastructure": "#1A56DB", "Database": "#D97706",
                   "External": "#0E9F6E"}.get(cat, "#6B7280")
        err_rows_data.append([
            Paragraph(str(i),                                        S["BodySm"]),
            Paragraph(short,                                          S["Body"]),
            Paragraph(svc_disp,                                       S["BodySm"]),
            Paragraph(_colored(cat, cat_clr, bold=True),              S["BodySm"]),
            Paragraph(f"<b>{count}</b>",                              S["Body"]),
            Paragraph(pct,                                            S["BodySm"]),
        ])

    if len(err_rows_data) == 1:
        err_rows_data.append(
            [Paragraph("No error data", S["BodySm"])] + [Paragraph("—", S["BodySm"])] * 5
        )

    err_tbl = Table(
        err_rows_data,
        colWidths=[0.25*inch, 2.2*inch, 1.35*inch, 1.0*inch, 0.55*inch, 0.75*inch],
    )
    err_tbl.setStyle(TableStyle(
        _BASE_TBL + _hdr_style(C_NAVY) + [
            ("ALIGN",      (0,  0), (0,  -1), "CENTER"),
            ("ALIGN",      (4,  0), (5,  -1), "CENTER"),
            ("ALIGN",      (0,  0), (3,  -1), "LEFT"),
            ("BACKGROUND", (0,  1), (-1,  1), C_RED_LT),   # highlight #1 row
        ]
    ))
    els.append(err_tbl)
    els.append(Spacer(1, 14))

    # ══ 6. CHARTS 
    c1 = _chart_error_dist(data)
    c2 = _chart_service_health(data)
    if c1 or c2:
        els += _section_header("📈 Analysis Visualisations", S)
        imgs = []
        if c1 and os.path.exists(c1): imgs.append(Image(c1, width=220, height=175))
        if c2 and os.path.exists(c2): imgs.append(Image(c2, width=220, height=175))
        while len(imgs) < 2: imgs.append(Spacer(220, 175))
        g = Table([imgs], colWidths=[3.3 * inch, 3.3 * inch])
        g.setStyle(TableStyle([
            ("ALIGN",  (0, 0), (-1, -1), "CENTER"),
            ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ]))
        els.append(KeepTogether(g))
        els.append(Spacer(1, 14))

    # ══ 7. RECOMMENDATIONS & FIXES 

    els += _section_header(" Recommendations & Fixes", S)
    recs = _generate_recommendations(data)

    for idx, rec in enumerate(recs, 1):
        p              = rec["priority"]
        bg_hex, fg_hex = _priority_badge_colors(p)

        # Priority badge
        badge = Table(
            [[Paragraph(f"<b>{p}</b>", S["BodySm"])]],
            colWidths=[0.62 * inch],
        )
        badge.setStyle(TableStyle([
            ("BACKGROUND",    (0, 0), (-1, -1), HexColor(bg_hex)),
            ("TEXTCOLOR",     (0, 0), (-1, -1), HexColor(fg_hex)),
            ("FONTNAME",      (0, 0), (-1, -1), "Helvetica-Bold"),
            ("ALIGN",         (0, 0), (-1, -1), "CENTER"),
            ("VALIGN",        (0, 0), (-1, -1), "MIDDLE"),
            ("TOPPADDING",    (0, 0), (-1, -1), 3),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ]))

        # Title row: badge | idx. Error  [service]  (N occurrences)
        error_label = rec["error"][:65]
        svc_tag     = (f"  <font color='#1A56DB' size='8'>[{rec['service']}]</font>"
                       if rec.get("service") else "")
        count_str   = (f"  <font color='#6B7280' size='7'>({rec['count']} occurrences)</font>"
                       if rec.get("count", 0) > 0 else "")
        title_para  = Paragraph(
            f"<b>{idx}. {error_label}</b>{svc_tag}{count_str}", S["RecTitle"]
        )
        title_row = Table(
            [[badge, title_para]],
            colWidths=[0.72 * inch, 5.75 * inch],
        )
        title_row.setStyle(TableStyle([
            ("VALIGN",        (0, 0), (-1, -1), "MIDDLE"),
            ("LEFTPADDING",   (0, 0), (-1, -1), 0),
            ("RIGHTPADDING",  (0, 0), (-1, -1), 0),
            ("TOPPADDING",    (0, 0), (-1, -1), 0),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
        ]))

        card_els = [title_row]

        # Why it happened — shaded band
        if rec.get("why"):
            why_tbl = Table(
                [[Paragraph(f"<b>Why it happened:</b>  {rec['why']}", S["WhyBody"])]],
                colWidths=[_PAGE_W],
            )
            why_tbl.setStyle(TableStyle([
                ("BACKGROUND",    (0, 0), (-1, -1), HexColor(bg_hex + "55")),
                ("TOPPADDING",    (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
                ("LEFTPADDING",   (0, 0), (-1, -1), 10),
                ("RIGHTPADDING",  (0, 0), (-1, -1), 10),
            ]))
            card_els.append(Spacer(1, 4))
            card_els.append(why_tbl)

        # How to fix — numbered steps with left accent bar
        if rec.get("steps"):
            card_els.append(Spacer(1, 4))
            card_els.append(Paragraph("<b>How to fix:</b>", S["SubHdr"]))
            step_rows = [
                [Paragraph(f"<b>{si}.</b>", S["StepBody"]),
                 Paragraph(step.strip(),    S["StepBody"])]
                for si, step in enumerate(rec["steps"], 1)
            ]
            step_tbl = Table(step_rows, colWidths=[0.22 * inch, 6.15 * inch])
            step_tbl.setStyle(TableStyle([
                ("VALIGN",        (0, 0), (-1, -1), "TOP"),
                ("TOPPADDING",    (0, 0), (-1, -1), 3),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
                ("LEFTPADDING",   (0, 0), (-1, -1), 4),
                ("RIGHTPADDING",  (0, 0), (-1, -1), 4),
                ("BACKGROUND",    (0, 0), (-1, -1), C_GREY_LT),
                ("LINEBEFORE",    (0, 0), (0, -1),  3, HexColor(fg_hex)),
            ]))
            card_els.append(step_tbl)

        # Outer card — border box + left accent bar in priority colour
        card = Table(
            [[el] for el in card_els],
            colWidths=[_PAGE_W],
        )
        card.setStyle(TableStyle([
            ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
            ("LINEBEFORE",    (0, 0), (-1, -1), 4,   HexColor(fg_hex)),
            ("TOPPADDING",    (0, 0), (-1, -1), 6),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ("LEFTPADDING",   (0, 0), (-1, -1), 10),
            ("RIGHTPADDING",  (0, 0), (-1, -1), 8),
        ]))
        els.append(KeepTogether(card))
        els.append(Spacer(1, 10))

    # ══ 8. FOOTER ══════════════════════════════════════════════════════════════
    els.append(Spacer(1, 10))
    els.append(_divider())
    els.append(Paragraph(
        f"<font color='#6B7280'>"
        f"Generated by AI Log Agent  •  Role: {role_label}  •  "
        f"Report Date: {date_text}  •  Generated At: {generated_at}  •  Confidential"
        f"</font>",
        S["BodySm"],
    ))

    doc.build(els)

    for p in [str(REPORT_DIR / "error_dist_tmp.png"), str(REPORT_DIR / "service_health_tmp.png")]:
        if os.path.exists(p):
            try: os.remove(p)
            except OSError: pass

    return file_name


# # ── Demo / smoke-test ──────────────────────────────────────────────────────────
# if __name__ == "__main__":
#     sample_data = {
#         "environment":       "Production",
#         "date":              "2026-04-28T18:13:05Z",
#         "time_range_label":  "Last 24 hours",
#         "total_logs":        503,
#         "total_errors":      3,
#         "total_warnings":    112,
#         "total_info":        388,
#         "affected_services": 3,
#         # Dev allowlist — only these services shown for dev role
#         "mapped_services": ["log-controller-service", "ide-service"],
#         "services": [
#             {
#                 "name":               "log-controller-service",
#                 "errors":             1,  "warnings": 45,
#                 "requests_approved":  120, "requests_rejected": 4, "users_added": 2,
#                 "requests_total":     890, "p99_latency_ms": "234",
#             },
#             {
#                 "name":               "asset-management-service",
#                 "errors":             1,  "warnings": 32,
#                 "requests_approved":  0,  "requests_rejected": 0, "users_added": 0,
#                 "requests_total":     450, "p99_latency_ms": "189",
#             },
#             {
#                 "name":               "ide-service",
#                 "errors":             1,  "warnings": 35,
#                 "requests_approved":  200, "requests_rejected": 8, "users_added": 5,
#                 "requests_total":     1200, "p99_latency_ms": "310",
#             },
#         ],
#         "unstable_services": [
#             {"service": "log-controller-service",   "count": 1},
#             {"service": "asset-management-service", "count": 1},
#             {"service": "ide-service",              "count": 1},
#         ],
#         "top_errors": [
#             {"name": "Aggregated 4791 earlier error logs", "count": 1,
#              "service": "log-controller-service"},
#             {"name": "Connection refused to kafka broker",  "count": 0,
#              "service": "asset-management-service"},
#             {"name": "Database query timeout exceeded",     "count": 0,
#              "service": "ide-service"},
#         ],
#         "correlation":     [],
#         "recommendations": [
#             "Inspect recent log-controller-service deploys and retries.",
#             "Warnings outnumber errors — inspect deprecation signals early.",
#         ],
#     }

#     for role in ["admin", "dev"]:
#         out  = generate_pdf_report(sample_data, role)
#         dest = f"/mnt/user-data/outputs/report_{role}.pdf"
#         shutil.copy(out, dest)
#         print(f"Generated: {dest}")
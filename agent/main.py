from pathlib import Path
import logging
import re
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse

from models import AgentQueryRequest, AskRequest, HealthResponse
from role_agent import answer_query, ask_ai, generate_summary
from db import fetch_logs
from summary import generate_pdf_report
from typing import Any

app = FastAPI(title="AI Log Analysis Agent")
REPORT_DIR = Path(__file__).resolve().parent / "generated_reports"

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


# ── Time range parser (mirrors ai.py logic) ──────────────────────────────────

def _parse_time_range(text: str) -> dict[str, Any]:
    """
    Parse a natural-language time expression and return UTC-aware start/end datetimes.

    All arithmetic is done in UTC so that comparisons against MongoDB's UTC-stored
    timestamps are exact — no IST offset shift.  The frontend is responsible for
    displaying times in IST; this layer must stay UTC-only.

    Falls back to last 24 hours when no pattern is matched.
    """
    lower = (text or "").lower()
    # Always anchor to UTC — never use a local or IST timezone here
    now = datetime.now(timezone.utc)

    match = re.search(r"last\s+(\d+)\s+(minute|hour|day|week)s?", lower)
    if match:
        value = int(match.group(1))
        unit = match.group(2)
        if unit == "minute":
            delta = timedelta(minutes=value)
        elif unit == "hour":
            delta = timedelta(hours=value)
        elif unit == "week":
            delta = timedelta(weeks=value)
        else:
            delta = timedelta(days=value)
        return {"start": now - delta, "end": now, "label": f"Last {value} {unit}(s)"}

    # "today" — midnight UTC to now
    if "today" in lower:
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        return {"start": start, "end": now, "label": "Today"}

    # "this week" — Monday 00:00 UTC to now
    if "this week" in lower:
        start = (now - timedelta(days=now.weekday())).replace(
            hour=0, minute=0, second=0, microsecond=0
        )
        return {"start": start, "end": now, "label": "This week"}

    # Default: last 24 hours
    return {"start": now - timedelta(hours=24), "end": now, "label": "Last 24 hours"}


def _normalize_role(role: str | None) -> str:
    """
    Normalize the role string to the agent's two internal values: "admin" or "dev".

    The Spring Boot backend's UserRole enum has ADMIN and DEV.
    The frontend sends role.name() from the enum, so non-admin users send "DEV".
    Accept any reasonable variant and map to the canonical value.
    """
    raw = str(role or "").strip().lower()
    if raw == "admin":
        return "admin"
    # "dev" or any other non-admin value → dev scope
    return "dev"


def _normalize_services(services: list[str] | None) -> list[str]:
    normalized: list[str] = []
    for service in services or []:
        text = str(service or "").strip()
        if text and text != "*" and text not in normalized:
            normalized.append(text)
    return normalized


def _success_response(answer: str = "", pdf_url: str = "") -> dict[str, Any]:
    """Create a standardized success response"""
    return {
        "success": True,
        "answer": answer.strip() if answer else "",
        "pdf_url": pdf_url.strip() if pdf_url else "",
    }


def _error_response(message: str = "An error occurred") -> dict[str, Any]:
    """Create a standardized error response"""
    return {
        "success": False,
        "message": message.strip() if message else "An error occurred",
    }

@app.get("/api/agent/reports/{filename}", name="download_agent_report")
def download_agent_report(filename: str):
    try:
        report_path = (REPORT_DIR / filename).resolve()
        if REPORT_DIR.resolve() not in report_path.parents or not report_path.is_file():
            raise HTTPException(status_code=404, detail="Report not found")

        return FileResponse(
            path=str(report_path),
            filename=report_path.name,
            media_type="application/pdf",
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error downloading report: {str(e)}")
        raise HTTPException(status_code=500, detail="Failed to download report")


@app.post("/api/agent/query")
def agent_query(payload: AgentQueryRequest):
    query = (payload.query or "").strip()
    if not query:
        raise HTTPException(status_code=400, detail="query is required")

    role = _normalize_role(payload.role)

    services = _normalize_services(payload.services)
    if role == "dev" and not services:
        raise HTTPException(status_code=400, detail="dev requests require assigned services")

    # Parse time range from the query text
    time_range = _parse_time_range(query)
    start_dt = time_range["start"]
    end_dt = time_range["end"]
    time_label = time_range["label"]

    try:
        # Fetch logs scoped by role, services, AND time range
        logs = fetch_logs(role, services, start=start_dt, end=end_dt)
        raw_count = len(logs) if logs else 0

        logger.info(
            "SUMMARY QUERY | role=%s | time=%s | UTC range: %s → %s | raw_count=%d",
            role, time_label,
            start_dt.strftime("%Y-%m-%dT%H:%M:%SZ"),
            end_dt.strftime("%Y-%m-%dT%H:%M:%SZ"),
            raw_count,
        )

        if raw_count > 0:
            # Log first and last timestamps to verify the window is correct
            timestamps = [
                log.get("timestamp") for log in logs
                if log.get("timestamp")
            ]
            if timestamps:
                logger.info(
                    "SUMMARY QUERY | first_ts=%s | last_ts=%s",
                    timestamps[0], timestamps[-1],
                )

        if not logs:
            if payload.mode == "summary":
                return _error_response(message=f"No logs found for {time_label}.")
            return _success_response(answer=f"No data available for {time_label}.")

        # Process based on mode
        if payload.mode == "qa":
            try:
                answer = answer_query(query, logs)
                answer_str = str(answer or "").strip()
                if not answer_str:
                    answer_str = "Unable to process request. Please try again."
                logger.info("QA mode: answer generated successfully")
                return _success_response(answer=answer_str)
            except Exception as e:
                logger.error(f"Error in QA mode: {str(e)}", exc_info=True)
                return _error_response(message="Unable to answer query")

        elif payload.mode == "summary":
            try:
                summary = generate_summary(logs)
                if not summary or not isinstance(summary, dict):
                    logger.error("Summary generation returned invalid result")
                    return _error_response(message="Unable to generate summary")

                # Inject time metadata so the PDF header shows the correct range.
                # summary.py reads: time_range_label, date, timeLabel, timeStart, timeEnd
                summary["time_range_label"] = time_label
                summary["date"]             = end_dt.isoformat()
                summary["timeLabel"]        = time_label
                summary["timeStart"]        = start_dt.strftime("%Y-%m-%dT%H:%M:%SZ")
                summary["timeEnd"]          = end_dt.strftime("%Y-%m-%dT%H:%M:%SZ")

                pdf_path = generate_pdf_report(summary, role)
                if not pdf_path:
                    logger.error("PDF generation returned empty path")
                    return _error_response(message="Failed to generate PDF")

                pdf_file = Path(pdf_path)
                if not pdf_file.exists():
                    logger.error(f"PDF file does not exist at path: {pdf_path}")
                    return _error_response(message="PDF generation failed")

                pdf_name = pdf_file.name
                logger.info(f"Summary mode: PDF generated at {pdf_name}")
                return _success_response(pdf_url=f"/api/agent/reports/{pdf_name}")
            except Exception as e:
                logger.error(f"Error in summary mode: {str(e)}", exc_info=True)
                return _error_response(message="Unable to generate summary")

        else:
            return _error_response(message="Invalid mode specified")

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Unexpected error in agent_query: {str(e)}", exc_info=True)
        return _error_response(message="Unable to process request")



@app.post("/ask")
def ask(payload: AskRequest):
    try:
        question = (payload.question or "").strip()
        if not question:
            raise HTTPException(status_code=400, detail="question is required")

        role = _normalize_role(payload.role)
        services = _normalize_services(payload.services)
        
        try:
            logs = fetch_logs(role or "dev", services)
        except Exception as e:
            logger.error(f"Error fetching logs: {str(e)}")
            return {"type": "text", "answer": "Unable to fetch logs. Please try again."}

        try:
            response = ask_ai(question, services, payload.role, logs=logs)

            if isinstance(response, dict) and response.get("type") == "file":
                file_path = response.get("file")
                if file_path and Path(file_path).exists():
                    return FileResponse(
                        path=file_path,
                        filename="report.pdf",
                        media_type="application/pdf",
                    )
                else:
                    return {"type": "text", "answer": "Failed to generate PDF report"}

            return response or {"type": "text", "answer": "Unable to process request"}
        except Exception as ex:
            logger.error(f"Error in ask_ai: {str(ex)}")
            return {"type": "text", "answer": "Unable to process request. Please try again."}
    except HTTPException:
        raise
    except Exception as ex:
        logger.error(f"Unexpected error in /ask: {str(ex)}")
        return {"type": "text", "answer": "Service temporarily unavailable. Please try again."}

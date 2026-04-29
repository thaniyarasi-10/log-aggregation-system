from pathlib import Path
import logging

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse

from models import AgentQueryRequest, AskRequest, HealthResponse
from role_agent import answer_query, ask_ai, generate_summary
from db import fetch_logs
from role_agent import generate_pdf_report
from typing import Any

app = FastAPI(title="AI Log Analysis Agent")
REPORT_DIR = Path(__file__).resolve().parent / "generated_reports"

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


def _normalize_role(role: str | None) -> str:
    return str(role or "").strip().lower()


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
    if role not in {"admin", "developer"}:
        raise HTTPException(status_code=403, detail="Unsupported role")

    services = _normalize_services(payload.services)
    if role == "developer" and not services:
        raise HTTPException(status_code=400, detail="developer requests require assigned services")

    try:
        # Fetch logs for the role
        logs = fetch_logs(role, services)
        logger.info(f"Fetched {len(logs) if logs else 0} logs for role={role}")
        
        if not logs:
            return _success_response(answer="No data available for your role")

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
                
                pdf_path = generate_pdf_report(summary, role)
                if not pdf_path:
                    logger.error("PDF generation returned empty path")
                    return _error_response(message="Failed to generate PDF")
                
                pdf_file = Path(pdf_path)
                if not pdf_file.exists():
                    logger.error(f"PDF file does not exist at path: {pdf_path}")
                    return _error_response(message="PDF generation failed")
                
                pdf_name = Path(pdf_path).name
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


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    try:
        return HealthResponse(status="ok")
    except Exception as e:
        logger.error(f"Health check error: {str(e)}")
        return HealthResponse(status="error")


@app.post("/ask")
def ask(payload: AskRequest):
    try:
        question = (payload.question or "").strip()
        if not question:
            raise HTTPException(status_code=400, detail="question is required")

        role = _normalize_role(payload.role)
        services = _normalize_services(payload.services)
        
        try:
            logs = fetch_logs(role or "developer", services)
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

import os
import json
import time
import re
from typing import Any
from datetime import datetime, timedelta, timezone

import dateparser
from google import genai

from db import execute_query
from summary import generate_pdf_report


client = genai.Client(api_key=os.getenv("GOOGLE_API_KEY"))


#  TIME PARSER 

def parse_time_range(question: str):
    """
    Parse a natural-language time expression and return UTC-aware start/end datetimes.

    All arithmetic is done in UTC so that comparisons against MongoDB's UTC-stored
    timestamps are exact.  The frontend handles IST display; this layer is UTC-only.
    """
    text = (question or "").lower()
    # Always anchor to UTC — never use a local or IST timezone here
    now = datetime.now(timezone.utc)

    match = re.search(r"last (\d+) (minute|hour|day)s?", text)
    if match:
        value = int(match.group(1))
        unit = match.group(2)

        if unit == "minute":
            delta = timedelta(minutes=value)
        elif unit == "hour":
            delta = timedelta(hours=value)
        else:
            delta = timedelta(days=value)

        return {
            "start": now - delta,
            "end": now,
            "label": f"Last {value} {unit}(s)"
        }

    return {
        "start": now - timedelta(hours=24),
        "end": now,
        "label": "Last 24 hours"
    }


#  LLM RETRY 

def _generate_with_retry(prompt: str):
    for _ in range(3):
        try:
            return client.models.generate_content(
                model="gemini-2.5-flash",
                contents=prompt,
            )
        except Exception as e:
            if "503" in str(e):
                time.sleep(2)
            else:
                raise e
    return None


#  SAFE JSON 

def safe_parse_json(text: str):
    try:
        return json.loads(text)
    except:
        match = re.search(r"\{.*\}", text, re.DOTALL)
        if match:
            try:
                return json.loads(match.group(0))
            except:
                pass
    return None


#  VALIDATION 

def validate_pipeline(pipeline: list[dict]):
    if not isinstance(pipeline, list):
        raise ValueError("Pipeline must be a list")

    allowed = {"$match", "$group", "$sort", "$limit", "$project"}

    for stage in pipeline:
        if not isinstance(stage, dict):
            raise ValueError("Invalid stage")

        key = list(stage.keys())[0]

        if key not in allowed:
            raise ValueError(f"{key} not allowed")

    if not any("$limit" in s for s in pipeline):
        pipeline.append({"$limit": 5})

    return pipeline


#  AGENT 

def ask_ai(question: str, services: list[str], role: str | None = None):
    time_range = parse_time_range(question)

    #  PDF 
    if any(k in question.lower() for k in ["report", "summary", "download"]):
        data = {
            "top_errors": [],
            "unstable_services": [],
            "anomalies": [],
            "correlation": [],
            "insights": [],
            "recommendations": [],
            "total_errors": 0,
            "total_warnings": 0,
            "affected_services": 0,
            "overall_status": "UNKNOWN",
            "time_range_label": time_range.get("label"),
        }

        pdf = generate_pdf_report(data, role or "DEV")
        return {"type": "file", "file": pdf}

    context = {
        "question": question,
        "services": services,
        "time_range": {
            "start": str(time_range["start"]),
            "end": str(time_range["end"]),
            "label": time_range["label"],
        },
        "data": None,
        "feedback": None,
    }

    #  AGENT LOOP 
    for _ in range(3):
        prompt = f"""
You are an AI log analysis agent.

Your job:
- Understand the question
- Generate MongoDB aggregation pipeline
- Analyze results
- Give final answer

Schema:
- service (string)
- level (ERROR, WARN, INFO)
- message (string)
- errorType (string)
- timestamp (date)

STRICT RULES:
- ALWAYS use aggregation (no raw logs)
- ALWAYS include limit (max 5)
- Prefer grouping and sorting
- NEVER hallucinate fields
- If data exists in context, USE it

Return STRICT JSON:

{{
  "thought": "...",
  "action": "QUERY" or "RESPOND",
  "pipeline": []
}}

Context:
{context}
"""

        response = _generate_with_retry(prompt)

        if response is None:
            return {"type": "text", "answer": "System busy"}

        step = safe_parse_json(response.text)

        if not step:
            return {"type": "text", "answer": "AI parsing failed"}

        action = step.get("action")

        #  FINAL 
        if action == "RESPOND":
            return {
                "type": "text",
                "answer": step.get("thought", "No answer"),
            }

        #  QUERY 
        pipeline = step.get("pipeline", [])

        try:
            pipeline = validate_pipeline(pipeline)
        except Exception as e:
            context["feedback"] = str(e)
            continue

        #  Inject constraints
        base_match = {
            "service": {"$in": services},
            "timestamp": {
                "$gte": time_range["start"],
                "$lte": time_range["end"],
            },
        }

        if pipeline and "$match" in pipeline[0]:
            pipeline[0]["$match"].update(base_match)
        else:
            pipeline.insert(0, {"$match": base_match})

        result = execute_query(
            pipeline,
            services,
            time_range["start"],
            time_range["end"],
        )



    return {
        "type": "text",
        "answer": "Could not process request",
    }
import os
import time
from datetime import datetime, timedelta
from typing import Any
import pytz
import dateparser
import re


from google import genai
from google.genai import types
from mcp_mongo import execute_query
from summary import generate_pdf_report

client = genai.Client(api_key=os.getenv("GOOGLE_API_KEY"))

SYSTEM_PROMPT = """You are a conversational log analysis assistant.
You can:
- chat naturally
- ask clarifying questions if needed
- use tools to fetch real data
Rules:
- Use tools when answering factual questions about logs, errors, or services
- If question is unclear, ask a follow-up
- Prefer tool usage for accuracy
- Do not hallucinate data
- If data is unavailable, say 'I couldn’t find relevant data'"""

TOP_ERRORS_PIPELINE = [
    {"$group": {"_id": {"$ifNull": ["$errorType", "$message"]}, "count": {"$sum": 1}}},
    {"$sort": {"count": -1}},
    {"$limit": 5},
]
TOP_SERVICES_PIPELINE = [
    {"$match": {"level": {"$in": ["ERROR", "WARN"]}}},
    {"$group": {"_id": "$service", "count": {"$sum": 1}}},
    {"$sort": {"count": -1}},
    {"$limit": 5},
]   
SERVICE_INSTABILITY_PIPELINE = [
    {"$match": {"level": {"$in": ["ERROR", "WARN", "WARNING"]}}},  
    {"$group": {"_id": "$service", "count": {"$sum": 1}}},
    {"$sort": {"count": -1}},
    {"$limit": 5},
]

SERVICE_INSTABILITY_PIPELINE = [
    {"$group": {"_id": "$service", "count": {"$sum": 1}}},
    {"$sort": {"count": -1}},
    {"$limit": 5},
]

ERROR_CORRELATION_PIPELINE = [
    {
        "$group": {
            "_id": "$errorType",
            "services": {"$addToSet": "$service"},
            "count": {"$sum": 1},
        }
    },
    {
        "$project": {
            "error": "$_id",
            "service_count": {"$size": "$services"},
            "services": 1,
            "count": 1,
        }
    },
    {"$match": {"service_count": {"$gte": 2}}},
    {"$sort": {"service_count": -1}},
    {"$limit": 5},
]

ERROR_ANOMALY_PIPELINE = [
    {
        "$match": {
            "timestamp": {
                "$gte": {
                    "$dateSubtract": {
                        "startDate": "$$NOW",
                        "unit": "minute",
                        "amount": 20,
                    }
                }
            }
        }
    },
    {
        "$group": {
            "_id": {"$ifNull": ["$errorType", "$message"]},
            "recent_count": {
                "$sum": {
                    "$cond": [
                        {
                            "$gte": [
                                "$timestamp",
                                {
                                    "$dateSubtract": {
                                        "startDate": "$$NOW",
                                        "unit": "minute",
                                        "amount": 10,
                                    }
                                },
                            ]
                        },
                        1,
                        0,
                    ]
                }
            },
            "previous_count": {
                "$sum": {
                    "$cond": [
                        {
                            "$and": [
                                {
                                    "$gte": [
                                        "$timestamp",
                                        {
                                            "$dateSubtract": {
                                                "startDate": "$$NOW",
                                                "unit": "minute",
                                                "amount": 20,
                                            }
                                        },
                                    ]
                                },
                                {
                                    "$lt": [
                                        "$timestamp",
                                        {
                                            "$dateSubtract": {
                                                "startDate": "$$NOW",
                                                "unit": "minute",
                                                "amount": 10,
                                            }
                                        },
                                    ]
                                },
                            ]
                        },
                        1,
                        0,
                    ]
                }
            },
        }
    },
    {
        "$project": {
            "_id": 0,
            "error": "$_id",
            "recent_count": 1,
            "previous_count": 1,
            "increase_percent": {
                "$cond": [
                    {"$gt": ["$previous_count", 0]},
                    {
                        "$multiply": [
                            {
                                "$divide": [
                                    {"$subtract": ["$recent_count", "$previous_count"]},
                                    "$previous_count",
                                ]
                            },
                            100,
                        ]
                    },
                    0,
                ]
            },
        }
    },
    {"$match": {"increase_percent": {"$gt": 100}}},
    {"$sort": {"increase_percent": -1}},
    {"$limit": 5},
]


def _to_plain_dict(value: Any) -> dict[str, Any]:
    if value is None:
        return {}
    if isinstance(value, dict):
        return dict(value)
    if hasattr(value, "items"):
        return {k: v for k, v in value.items()}
    return {}


def _extract_tool_call(response: Any) -> tuple[str, dict[str, Any]] | None:
    # Primary extraction path for current SDK response shape.
    candidates = getattr(response, "candidates", None) or []
    if candidates:
        parts = getattr(getattr(candidates[0], "content", None), "parts", None) or []
        for part in parts:
            function_call = getattr(part, "function_call", None)
            if function_call:
                name = getattr(function_call, "name", None)
                if name:
                    return name, _to_plain_dict(getattr(function_call, "args", None))

    # Compatibility fallback.
    function_calls = getattr(response, "function_calls", None)
    if function_calls:
        first_call = function_calls[0]
        name = getattr(first_call, "name", None)
        if name:
            return name, _to_plain_dict(getattr(first_call, "args", None))

    return None


# pip install dateparser

def parse_time_range(question: str) -> dict[str, Any]:
    text = (question or "").strip().lower()
    tz = pytz.timezone("Asia/Kolkata")
    now = datetime.now(tz)

    # 1. Handle "last N unit" — dateparser can't resolve these as ranges
    match = re.search(r"last\s+(\d+)\s+(minute|hour|day|week|month)s?", text, re.IGNORECASE)
    if match:
        amount = int(match.group(1))
        unit = match.group(2).lower()
        delta_map = {
            "minute": timedelta(minutes=amount),
            "hour":   timedelta(hours=amount),
            "day":    timedelta(days=amount),
            "week":   timedelta(weeks=amount),
            "month":  timedelta(days=amount * 30),
        }
        start = now - delta_map[unit]
        label = f"Last {amount} {unit}{'s' if amount > 1 else ''}"
        return {"start": start, "end": now, "label": label}

    # 2. Handle "N units ago"
    match = re.search(r"(\d+)\s+(minute|hour|day|week|month)s?\s+ago", text, re.IGNORECASE)
    if match:
        amount = int(match.group(1))
        unit = match.group(2).lower()
        delta_map = {
            "minute": timedelta(minutes=amount),
            "hour":   timedelta(hours=amount),
            "day":    timedelta(days=amount),
            "week":   timedelta(weeks=amount),
            "month":  timedelta(days=amount * 30),
        }
        start = now - delta_map[unit]
        label = f"Last {amount} {unit}{'s' if amount > 1 else ''}"
        return {"start": start, "end": now, "label": label}

    # 3. Use dateparser for everything else (yesterday, today, this week, April 25th, etc.)
    parsed_date = dateparser.parse(
        text,
        settings={
            "RETURN_AS_TIMEZONE_AWARE": True,
            "TIMEZONE": "Asia/Kolkata",
            "RELATIVE_BASE": now,
            "TO_TIMEZONE": "Asia/Kolkata",
        }
    )
    if parsed_date:
        if parsed_date.date() < now.date():
            start = parsed_date.replace(hour=0, minute=0, second=0, microsecond=0)
            end = parsed_date.replace(hour=23, minute=59, second=59, microsecond=999999)
            label = start.strftime("%B %d, %Y")
            return {"start": start, "end": end, "label": label}
        if parsed_date.date() == now.date():
            start = now.replace(hour=0, minute=0, second=0, microsecond=0)
            return {"start": start, "end": now, "label": "Today"}

    # 4. Fallback
    return {"start": now - timedelta(hours=24), "end": now, "label": "Last 24 hours"}

def format_time_range_label(time_range: dict[str, Any]) -> str:
    return str(time_range.get("label") or "Last 24 hours")


def _with_time_range_prefix(answer: str, label: str, fallback: bool = False) -> str:
    lines = [f"Time Range: {label}"]
    if fallback:
        lines.append("No logs found in selected time range. Showing latest available data.")
    lines.append(answer)
    return "\n".join(lines)


def execute_tool(tool_name: str, args: dict[str, Any]) -> dict[str, Any]:
    # Enforce strict predefined tool-to-pipeline mapping (no dynamic query execution).
    tool_map = {
        "get_top_errors": TOP_ERRORS_PIPELINE,
        "get_top_services": TOP_SERVICES_PIPELINE,
        "get_error_anomaly": ERROR_ANOMALY_PIPELINE,
        "get_service_instability": SERVICE_INSTABILITY_PIPELINE,
        "get_error_correlation": ERROR_CORRELATION_PIPELINE,
    }

    base_pipeline = tool_map.get(tool_name)
    if base_pipeline is None:
        return {
            "data": [],
            "fallback": False,
        }

    pipeline = list(base_pipeline)

    services = args.get("services")
    if not isinstance(services, list):
        services = []

    start = args.get("start")
    end = args.get("end")
    query_result = execute_query(
        pipeline,
        services,
        start if isinstance(start, datetime) else None,
        end if isinstance(end, datetime) else None,
    )
    result = query_result.get("data", []) if isinstance(query_result, dict) else []
    fallback = bool(query_result.get("fallback", False)) if isinstance(query_result, dict) else False
    if not isinstance(result, list):
        return {
            "data": [],
            "fallback": fallback,
        }

    if tool_name == "get_error_anomaly":
        anomalies: list[dict[str, Any]] = []
        for item in result[:5]:
            if not isinstance(item, dict):
                continue
            anomalies.append(
                {
                    "error": str(item.get("error", "Unknown")),
                    "increase_percent": int(round(float(item.get("increase_percent", 0)))),
                    "recent_count": int(item.get("recent_count", 0)),
                    "previous_count": int(item.get("previous_count", 0)),
                }
            )
        return {
            "data": anomalies,
            "fallback": fallback,
        }

    if tool_name == "get_service_instability":
        service_rows: list[dict[str, Any]] = []
        normalized_counts: list[tuple[str, int]] = []
        for item in result[:5]:
            if not isinstance(item, dict):
                continue
            service_name = str(item.get("_id", "Unknown"))
            count = int(item.get("count", 0))
            normalized_counts.append((service_name, count))

        total_count = sum(count for _, count in normalized_counts)
        if total_count <= 0:
            return {
                "data": [],
                "fallback": fallback,
            }

        for service_name, count in normalized_counts:
            percentage = int(round((count / total_count) * 100))
            if percentage > 40:
                severity = "HIGH"
            elif 20 <= percentage <= 40:
                severity = "MEDIUM"
            else:
                severity = "LOW"
            service_rows.append(
                {
                    "service": service_name,
                    "count": count,
                    "percentage": percentage,
                    "severity": severity,
                }
            )
        return {
            "data": service_rows,
            "fallback": fallback,
        }

    if tool_name == "get_error_correlation":
        correlation_rows: list[dict[str, Any]] = []
        for item in result[:5]:
            if not isinstance(item, dict):
                continue
            services_list = item.get("services", [])
            if not isinstance(services_list, list):
                services_list = []
            correlation_rows.append(
                {
                    "error": str(item.get("error", "Unknown")),
                    "services": [str(svc) for svc in services_list],
                    "service_count": int(item.get("service_count", 0)),
                    "count": int(item.get("count", 0)),
                }
            )
        return {
            "data": correlation_rows,
            "fallback": fallback,
        }

    # Return only compact aggregated rows to avoid exposing raw log records.
    rows: list[dict[str, Any]] = []
    for item in result[:5]:
        if not isinstance(item, dict):
            continue
        rows.append(
            {
                "name": str(item.get("_id", "Unknown")),
                "count": int(item.get("count", 0)),
            }
        )
    return {
        "data": rows,
        "fallback": fallback,
    }


tools = [
    types.Tool(
        function_declarations=[
            types.FunctionDeclaration(
                name="get_top_errors",
                description="Get most frequent error types from logs",
                parameters={
                    "type": "object",
                    "properties": {
                        "services": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            }
                        }
                    },
                    "required": ["services"]
                },
            ),
            types.FunctionDeclaration(
                name="get_top_services",
                description="Get services producing most errors",
                parameters={
                    "type": "object",
                    "properties": {
                        "services": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            }
                        }
                    },
                    "required": ["services"]
                },
            ),
            types.FunctionDeclaration(
                name="get_error_anomaly",
                description=(
                    "Detect error types with sudden increase in the last 10 minutes "
                    "compared to the previous 10-minute window"
                ),
                parameters={
                    "type": "object",
                    "properties": {
                        "services": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            }
                        }
                    },
                    "required": ["services"]
                },
            ),
            types.FunctionDeclaration(
                name="get_service_instability",
                description="Get services with highest error contribution and instability severity",
                parameters={
                    "type": "object",
                    "properties": {
                        "services": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            }
                        }
                    },
                    "required": ["services"]
                },
            ),
            types.FunctionDeclaration(
                name="get_error_correlation",
                description=(
                    "Identify error types affecting multiple services to detect shared root causes"
                ),
                parameters={
                    "type": "object",
                    "properties": {
                        "services": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            }
                        }
                    },
                    "required": ["services"]
                },
            ),
        ]
    )
]


def _generate_with_retry(contents: str, config: Any | None = None) -> Any | None:
    for _ in range(3):
        try:
            return client.models.generate_content(
                model="gemini-2.5-flash",
                contents=contents,
                config=config,
            )
        except Exception as e:
            if "503" in str(e):
                time.sleep(2)
            else:
                raise e

    try:
        return client.models.generate_content(
            model="gemini-1.5-flash",
            contents=contents,
            config=config,
        )
    except Exception:
        return None


def _is_anomaly_question(question: str) -> bool:
    text = (question or "").lower()
    return any(keyword in text for keyword in ["increase", "spike", "anomaly", "sudden"])


def _is_instability_question(question: str) -> bool:
    text = (question or "").lower()
    return any(
        keyword in text
        for keyword in ["unstable", "failing service", "highest error rate"]
    )


def _is_correlation_question(question: str) -> bool:
    text = (question or "").lower()
    return any(
        keyword in text
        for keyword in [
            "root cause",
            "correlation",
            "affecting multiple services",
            "common issue",
        ]
    )


def _is_report_question(question: str) -> bool:
    text = (question or "").lower()
    return any(keyword in text for keyword in ["report", "summary", "download"])


def _derive_overall_status(
    top_errors: list[dict[str, Any]],
    unstable_services: list[dict[str, Any]],
    anomalies: list[dict[str, Any]],
) -> str:
    max_error_count = max((int(item.get("count", 0)) for item in top_errors), default=0)
    high_unstable = any(str(item.get("severity", "")) == "HIGH" for item in unstable_services)
    if high_unstable or anomalies or max_error_count >= 100:
        return "CRITICAL"
    if unstable_services or max_error_count > 0:
        return "WARNING"
    return "STABLE"


def _build_recommendations(
    unstable_services: list[dict[str, Any]],
    anomalies: list[dict[str, Any]],
    correlations: list[dict[str, Any]],
) -> list[str]:
    recommendations: list[str] = []

    for item in unstable_services:
        if str(item.get("severity", "")) == "HIGH":
            recommendations.append(
                f"Prioritize incident response for {item.get('service', 'unknown service')} due to high instability."
            )

    for item in anomalies:
        recommendations.append(
            f"Investigate spike for {item.get('error', 'unknown error')} by comparing recent deployments and infrastructure changes."
        )

    for item in correlations:
        if int(item.get("service_count", 0)) >= 2:
            recommendations.append(
                f"Review shared dependencies for {item.get('error', 'unknown error')} across impacted services."
            )

    if not recommendations:
        recommendations.append("Continue monitoring current trends; no immediate action is required.")

    return recommendations[:6]


def _build_report_data(services: list[str], time_range: dict[str, Any]) -> dict[str, Any]:
    query_args = {
        "services": services,
        "start": time_range["start"],
        "end": time_range["end"],
    }
    top_errors_result = execute_tool("get_top_errors", query_args)
    unstable_services_result = execute_tool("get_service_instability", query_args)
    anomalies_result = execute_tool("get_error_anomaly", query_args)
    correlation_result = execute_tool("get_error_correlation", query_args)

    top_errors = top_errors_result.get("data", [])
    unstable_services = unstable_services_result.get("data", [])
    anomalies = anomalies_result.get("data", [])
    correlation = correlation_result.get("data", [])

    used_fallback = any(
        bool(item.get("fallback", False))
        for item in [top_errors_result, unstable_services_result, anomalies_result, correlation_result]
        if isinstance(item, dict)
    )

    total_errors = sum(int(item.get("count", 0)) for item in top_errors)
    affected_services = len(
        {str(item.get("service", "")).strip() for item in unstable_services if str(item.get("service", "")).strip()}
    )
    overall_status = _derive_overall_status(top_errors, unstable_services, anomalies)

    insights: list[str] = []
    if top_errors:
        insights.append(
            f"Top recurring error is {top_errors[0].get('name', 'Unknown')} with {top_errors[0].get('count', 0)} occurrences."
        )
    if unstable_services:
        insights.append(
            f"Most unstable service is {unstable_services[0].get('service', 'Unknown')} contributing {unstable_services[0].get('percentage', 0)}% of scoped errors."
        )
    if anomalies:
        insights.append(
            f"Recent anomaly detected for {anomalies[0].get('error', 'Unknown')} with {anomalies[0].get('increase_percent', 0)}% increase."
        )
    if correlation:
        insights.append(
            f"{correlation[0].get('error', 'Unknown')} affects {correlation[0].get('service_count', 0)} services, indicating shared failure patterns."
        )
    if not insights:
        insights.append("No significant issues detected in selected time range.")

    recommendations = _build_recommendations(unstable_services, anomalies, correlation)

    recent_logs: list[str] = []
    for item in top_errors[:3]:
        recent_logs.append(
            f"Error {item.get('name', 'Unknown')} observed {item.get('count', 0)} times in current scope."
        )
    if not recent_logs:
        recent_logs.append("No recent error entries available in current scope.")

    return {
        "top_errors": top_errors,
        "unstable_services": unstable_services,
        "anomalies": anomalies,
        "correlation": correlation,
        "insights": insights,
        "recommendations": recommendations,
        "service_breakdown": unstable_services,
        "recent_logs": recent_logs,
        "total_errors": total_errors,
        "total_warnings": 0,
        "affected_services": affected_services,
        "overall_status": overall_status,
        "time_range_label": format_time_range_label(time_range),
        "fallback": used_fallback,
    }


def ask_ai(question: str, services: list[str], role: str | None = None) -> dict[str, Any]:
    time_range = parse_time_range(question)
    time_label = format_time_range_label(time_range)

    if _is_report_question(question):
        report_data = _build_report_data(services, time_range)
        pdf_path = generate_pdf_report(report_data, role or "DEV")
        return {
            "type": "file",
            "file": pdf_path,
        }

    prompt = f"""
User Question: {question}
Available Services: {services}
Respond conversationally. Use tools if needed.
"""

    response = _generate_with_retry(
        contents=f"{SYSTEM_PROMPT}\n\n{prompt}",
        config=types.GenerateContentConfig(tools=tools),
    )
    if response is None:
        return {
            "type": "text",
            "answer": _with_time_range_prefix(
                "System is busy. Please try again in a few seconds.",
                time_label,
            ),
        }

    tool_call = _extract_tool_call(response)
    forced_tool_name = None
    if _is_anomaly_question(question):
        forced_tool_name = "get_error_anomaly"
    elif _is_instability_question(question):
        forced_tool_name = "get_service_instability"
    elif _is_correlation_question(question):
        forced_tool_name = "get_error_correlation"

    if tool_call:
        tool_name, arguments = tool_call
    elif forced_tool_name:
        tool_name = forced_tool_name
        arguments = {}
    else:
        tool_name = ""
        arguments = {}

    if tool_call:
        # Always enforce caller-provided service scope.
        arguments["services"] = services
        arguments["start"] = time_range["start"]
        arguments["end"] = time_range["end"]
        tool_result = execute_tool(tool_name, arguments)
        result = tool_result.get("data", []) if isinstance(tool_result, dict) else []
        used_fallback = bool(tool_result.get("fallback", False)) if isinstance(tool_result, dict) else False

        if not result:
            return {
                "type": "text",
                "answer": _with_time_range_prefix("I couldn’t find relevant data", time_label),
            }

        if tool_name == "get_error_anomaly":
            primary = result[0]
            return {
                "type": "text",
                "answer": _with_time_range_prefix(
                    (
                        f"{primary.get('error', 'Unknown')} errors increased by "
                        f"{primary.get('increase_percent', 0)}% in last 10 minutes"
                    ),
                    time_label,
                    used_fallback,
                ),
            }

        if tool_name == "get_service_instability":
            primary = result[0]
            service_name = str(primary.get("service", "Unknown"))
            percentage = int(primary.get("percentage", 0))
            severity = str(primary.get("severity", "LOW"))
            intensity = "highly" if severity == "HIGH" else "moderately" if severity == "MEDIUM" else "slightly"
            return {
                "type": "text",
                "answer": _with_time_range_prefix(
                    (
                        f"{service_name} is {intensity} unstable, contributing "
                        f"{percentage}% of total errors"
                    ),
                    time_label,
                    used_fallback,
                ),
            }

        if tool_name == "get_error_correlation":
            primary = result[0]
            return {
                "type": "text",
                "answer": _with_time_range_prefix(
                    (
                        f"{primary.get('error', 'Unknown')} errors are affecting "
                        f"{primary.get('service_count', 0)} services, indicating a shared database issue"
                    ),
                    time_label,
                    used_fallback,
                ),
            }

        final_response = _generate_with_retry(
            contents=(
                f"Question: {question}\n"
                f"Tool Result: {result}\n"
                "Respond conversationally, using only this data. Do not guess."
            ),
        )
        if final_response is None:
            return {
                "type": "text",
                "answer": _with_time_range_prefix(
                    "System is busy. Please try again in a few seconds.",
                    time_label,
                    used_fallback,
                ),
            }

        return {
            "type": "text",
            "answer": _with_time_range_prefix(
                final_response.text or "I couldn’t find relevant data",
                time_label,
                used_fallback,
            ),
        }

    if forced_tool_name:
        arguments["services"] = services
        arguments["start"] = time_range["start"]
        arguments["end"] = time_range["end"]
        tool_result = execute_tool(tool_name, arguments)
        result = tool_result.get("data", []) if isinstance(tool_result, dict) else []
        used_fallback = bool(tool_result.get("fallback", False)) if isinstance(tool_result, dict) else False

        if not result:
            return {
                "type": "text",
                "answer": _with_time_range_prefix("I couldn’t find relevant data", time_label),
            }

        primary = result[0]
        if tool_name == "get_service_instability":
            service_name = str(primary.get("service", "Unknown"))
            percentage = int(primary.get("percentage", 0))
            severity = str(primary.get("severity", "LOW"))
            intensity = "highly" if severity == "HIGH" else "moderately" if severity == "MEDIUM" else "slightly"
            return {
                "type": "text",
                "answer": _with_time_range_prefix(
                    (
                        f"{service_name} is {intensity} unstable, contributing "
                        f"{percentage}% of total errors"
                    ),
                    time_label,
                    used_fallback,
                ),
            }

        if tool_name == "get_error_correlation":
            return {
                "type": "text",
                "answer": _with_time_range_prefix(
                    (
                        f"{primary.get('error', 'Unknown')} errors are affecting "
                        f"{primary.get('service_count', 0)} services, indicating a shared database issue"
                    ),
                    time_label,
                    used_fallback,
                ),
            }

        return {
            "type": "text",
            "answer": _with_time_range_prefix(
                (
                    f"{primary.get('error', 'Unknown')} errors increased by "
                    f"{primary.get('increase_percent', 0)}% in last 10 minutes"
                ),
                time_label,
                used_fallback,
            ),
        }

    return {
        "type": "text",
        "answer": _with_time_range_prefix(
            response.text or "I couldn’t find relevant data",
            time_label,
        ),
    }

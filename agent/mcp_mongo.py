from typing import Any
from datetime import datetime, timezone

from db import get_collection


def execute_query(
    pipeline: list[dict[str, Any]],
    services: list[str],
    start: datetime | None = None,
    end: datetime | None = None,
) -> dict[str, Any]:
    collection = get_collection()

    service_scope = [
    svc for svc in services
    if isinstance(svc, str) and svc.strip() and svc.strip() != "*"
    ]
    
    match_stage: dict[str, Any] = {
        "level": {"$in": ["ERROR", "WARN", "WARNING"]}
    }
    if service_scope:
        match_stage["service"] = {"$in": service_scope}

    base_pipeline = [{"$match": match_stage}, *pipeline]

    if start is not None and end is not None:
        start_utc = start.astimezone(timezone.utc).replace(tzinfo=None)
        end_utc = end.astimezone(timezone.utc).replace(tzinfo=None)

        time_match = {
            "$match": {
                "timestamp": {
                    "$gte": start_utc,
                    "$lte": end_utc,
                }
            }
        }
        time_filtered_pipeline = [time_match, *base_pipeline]
        filtered_result = list(collection.aggregate(time_filtered_pipeline))
        if filtered_result:
            return {
                "data": filtered_result,
                "fallback": False,
            }

        fallback_result = list(collection.aggregate(base_pipeline))
        return {
            "data": fallback_result,
            "fallback": True,
        }

    return {
        "data": list(collection.aggregate(base_pipeline)),
        "fallback": False,
    }
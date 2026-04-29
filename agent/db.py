from datetime import datetime
from typing import Any

from bson import ObjectId
from pymongo import MongoClient
from pymongo.collection import Collection
import os

MONGO_URI = os.getenv("MONGO_URI")

DB_NAME = "logs_db"
COLLECTION_NAME = "logs"

def get_collection() -> Collection:
    client = MongoClient(MONGO_URI)
    db = client[DB_NAME]
    return db[COLLECTION_NAME]


def _normalize_services(services: list[str] | None) -> list[str]:
    normalized: list[str] = []
    for service in services or []:
        text = str(service or "").strip()
        if text and text != "*" and text not in normalized:
            normalized.append(text)
    return normalized


def _serialize_value(value: Any) -> Any:
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, ObjectId):
        return str(value)
    if isinstance(value, dict):
        return {key: _serialize_value(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_serialize_value(item) for item in value]
    return value


def _serialize_document(document: dict[str, Any]) -> dict[str, Any]:
    return {key: _serialize_value(value) for key, value in document.items()}



def _normalize_document_fields(document: dict[str, Any]) -> dict[str, Any]:
    """
    Normalize MongoDB document field names to standard format.
    Handles variations like 'serviceName' vs 'service', 'logLevel' vs 'level'.
    """
    normalized = document.copy()
    
    # Normalize service field
    if 'serviceName' in normalized and 'service' not in normalized:
        normalized['service'] = normalized.pop('serviceName')
    
    # Normalize level field
    if 'logLevel' in normalized and 'level' not in normalized:
        normalized['level'] = normalized.pop('logLevel')
    
    return normalized

def fetch_logs(role: str, services: list[str] | None) -> list[dict[str, Any]]:
    role_name = str(role or "").strip().lower()
    collection = get_collection()
    projection = {
        "service": 1,
        "timestamp": 1,
        "level": 1,
        "message": 1,
        "responseTime": 1,
        "statusCode": 1,
        "traceId": 1,
        "spanId": 1,
        "userId": 1,
        "endpoint": 1,
        "method": 1,
        "environment": 1,
        "errorCode": 1,
        "errorDetails": 1,
        "errorType": 1,
    }
    # Also include alternate field names in projection
    projection.update({
        "serviceName": 1,
        "logLevel": 1,
    })

    if role_name == "admin":
        query: dict[str, Any] = {}
    elif role_name == "developer":
        scoped_services = _normalize_services(services)
        if not scoped_services:
            return []
        # Query both possible field names
        query = {
            "$or": [
                {"service": {"$in": scoped_services}},
                {"serviceName": {"$in": scoped_services}},
            ]
        }
    else:
        raise ValueError("Unsupported role")

    cursor = collection.find(query, projection).sort("timestamp", 1)
    return [_serialize_document(_normalize_document_fields(document)) for document in cursor]


fetchLogs = fetch_logs
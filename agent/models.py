from typing import Literal

from pydantic import BaseModel


class HealthResponse(BaseModel):
    status: str


class AskRequest(BaseModel):
    question: str
    role: str | None = None
    services: list[str] | None = None


class AgentQueryRequest(BaseModel):
    query: str
    mode: Literal["qa", "summary"]
    role: str
    services: list[str] | None = None

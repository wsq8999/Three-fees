from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class SkeletonAgentResult(BaseModel):
    facts: dict[str, Any] = Field(default_factory=dict)
    calculations: dict[str, Any] = Field(default_factory=dict)
    evidence: list[dict[str, Any]] = Field(default_factory=list)
    judgment: dict[str, Any] = Field(default_factory=dict)
    report_draft: dict[str, Any] = Field(default_factory=dict)

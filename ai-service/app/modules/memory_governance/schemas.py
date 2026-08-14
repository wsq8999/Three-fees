from __future__ import annotations

"""统一记忆管理REST契约。"""

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, model_validator

MemoryType = Literal["audit_case", "correction_memory"]
MemoryStatus = Literal["active", "paused", "invalidated"]


class MemoryView(BaseModel):
    """把两张来源表转换成页面可统一展示的记忆资源。"""

    id: str
    memory_type: MemoryType
    city_code: str
    site_id: str
    site_name: str
    source_label: str
    process_status: str
    memory_status: MemoryStatus
    reason: str | None
    reason_category: str | None
    conditions: list[object]
    evidence_element_ids: list[object]
    version: int
    source_id: str
    created_at: datetime
    updated_at: datetime
    open_flag_id: str | None = None


class MemoryList(BaseModel):
    """当前城市的统一记忆列表。"""

    items: list[MemoryView]
    total: int
    page: int
    page_size: int


class MemoryStatusUpdate(BaseModel):
    """日常暂停或恢复操作；无效记忆必须修订，不能直接恢复。"""

    memory_status: Literal["active", "paused"]


class MemoryFlagCreate(BaseModel):
    """业务员标记既有记忆错误时提交的原因。"""

    flag_type: Literal[
        "wrong_reason",
        "wrong_site",
        "wrong_evidence",
        "wrong_scope",
        "duplicate",
        "outdated",
        "other",
    ]
    description: str = Field(min_length=2, max_length=2000)


class MemoryFlagResolve(BaseModel):
    """复核标错：误标则恢复，确认错误则永久隔离旧版本。"""

    resolution: Literal["dismissed", "invalidated"]
    note: str | None = Field(default=None, max_length=2000)


class MemoryFlagView(BaseModel):
    """标错记录响应。"""

    id: str
    memory_type: MemoryType
    memory_id: str
    city_code: str
    flag_type: str
    description: str
    status: str
    previous_memory_status: MemoryStatus
    resolution_action: str | None
    resolution_note: str | None
    reported_at: datetime
    resolved_at: datetime | None


class MemoryRevisionCreate(BaseModel):
    """修改错误记忆并重新启用时允许替换的业务内容。"""

    reason: str = Field(min_length=2, max_length=1000)
    reason_category: str = Field(min_length=1, max_length=100)
    conditions: list[str] = Field(default_factory=list, max_length=30)
    evidence_element_ids: list[int] | None = Field(default=None, max_length=50)
    change_reason: str = Field(min_length=2, max_length=2000)

    @model_validator(mode="after")
    def require_change_reason(self) -> MemoryRevisionCreate:
        """拒绝仅包含空白的说明，保证版本记录具备审计价值。"""
        if not self.change_reason.strip():
            raise ValueError("change_reason不能为空")
        return self


class MemoryRevisionView(BaseModel):
    """修订后的新记忆以及旧版本号。"""

    memory: MemoryView
    replaced_version: int

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field


class AnalysisRunCreate(BaseModel):
    """一次分析显式选择的本次任务截图ID。"""

    material_refs: list[str] = Field(default_factory=list, max_length=50)


class AnalysisRunResume(BaseModel):
    """恢复“系统是否超标”人工中断时的REST输入。"""

    decision: Literal["confirm_over_limit", "confirm_not_over_limit"]
    # 可选说明只用于本次运行审计，不会自动写入城市记忆库。
    note: str | None = Field(default=None, max_length=500)


class AnalysisEvent(BaseModel):
    sequence: int
    node: str
    message: str
    created_at: datetime


class AnalysisRunView(BaseModel):
    id: str
    task_id: str
    city_code: str
    status: str
    progress: int = Field(ge=0, le=100)
    current_node: str
    material_refs: list[str]
    events: list[AnalysisEvent]
    # 非空表示流程已安全暂停；前端只根据type展示对应确认面板。
    pending_interrupt: dict[str, Any] | None
    result: dict[str, Any] | None
    error: dict[str, str] | None
    created_by: str
    created_at: datetime
    started_at: datetime | None
    finished_at: datetime | None


class AnalysisRunList(BaseModel):
    items: list[AnalysisRunView]
    next_cursor: str | None = None

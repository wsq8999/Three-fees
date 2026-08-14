from __future__ import annotations

"""人工纠错记忆的模型输出与REST接口契约。"""

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class CorrectionInterpretation(BaseModel):
    """Kimi对业务员大白话纠错的结构化理解。

    该对象只代表待确认草稿。即使置信度很高，也必须由业务员再次确认后才能进入RAG。
    ``extra='forbid'`` 防止模型输出未定义字段后被应用静默忽略。
    """

    model_config = ConfigDict(extra="forbid")

    corrected_reason: str = Field(min_length=2, max_length=1000)
    reason_category: str = Field(min_length=1, max_length=100)
    applicability_conditions: list[str] = Field(max_length=20)
    supporting_current_element_ids: list[int] = Field(max_length=50)
    interpretation_summary: str = Field(min_length=2, max_length=1000)
    uncertain_items: list[str] = Field(max_length=20)
    confidence: float = Field(ge=0, le=1)


class CorrectionMemoryCreate(BaseModel):
    """业务员针对一次已完成分析提交的原始纠错话语。"""

    # 保留足够长度让Kimi理解上下文，同时限制异常超长输入进入模型。
    message: str = Field(min_length=4, max_length=2000)


class CorrectionMemoryUpdate(BaseModel):
    """业务员确认或驳回草稿时允许修改的字段。"""

    # 不允许通过本接口恢复draft或直接归档，避免状态机出现含糊回退。
    status: Literal["confirmed", "rejected"]
    corrected_reason: str | None = Field(default=None, min_length=2, max_length=1000)
    reason_category: str | None = Field(default=None, min_length=1, max_length=100)
    applicability_conditions: list[str] | None = Field(default=None, max_length=20)


class CorrectionMemoryView(BaseModel):
    """页面确认和历史记忆查询使用的安全响应。"""

    id: str
    city_code: str
    site_id: str
    site_name: str
    task_id: str
    analysis_run_id: str
    status: str
    # 与纠错流程状态分离的统一记忆使用状态。
    memory_status: str
    original_reason: str | None
    original_reason_category: str | None
    user_message: str
    corrected_reason: str
    reason_category: str
    applicability_conditions: list[object]
    supporting_element_ids: list[object]
    interpretation_summary: str
    uncertain_items: list[object]
    confidence: float
    model_name: str
    prompt_version: str
    created_by: str
    confirmed_by: str | None
    version: int
    created_at: datetime
    updated_at: datetime
    confirmed_at: datetime | None


class CorrectionMemoryList(BaseModel):
    items: list[CorrectionMemoryView]

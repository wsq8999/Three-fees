from __future__ import annotations

"""稽核前置筛查的严格数据契约。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ScreeningMetric(BaseModel):
    """一项用于前置筛查和人工确认展示的可追溯指标。"""

    model_config = ConfigDict(extra="forbid")

    metric_name: str = Field(min_length=1, max_length=100)
    actual_value: str | None = Field(default=None, max_length=100)
    benchmark_upper_value: str | None = Field(default=None, max_length=100)
    over_limit_rate_percent: str | None = Field(default=None, max_length=100)
    evidence_text: str | None = Field(default=None, max_length=500)
    evidence_element_ids: list[int] = Field(default_factory=list, max_length=20)
    verification_status: Literal["verified", "reported", "conflict", "unverified"]


class ScreeningDecision(BaseModel):
    """记录是否进入稽核以及该决定的来源和证据。

    ``unknown`` 不会被当作超标或未超标，而是进入人工确认中断。这样可以防止模型
    看不清页面时继续生成一份貌似完整但前提错误的报告。
    """

    model_config = ConfigDict(extra="forbid")

    status: Literal["yes", "no", "unknown"]
    source: Literal["system_material", "human_confirmation"]
    triggered_items: list[str] = Field(max_length=50)
    # default_factory兼容新功能上线前已经暂停的旧检查点。
    detected_metrics: list[ScreeningMetric] = Field(default_factory=list, max_length=50)
    evidence_document_ids: list[str] = Field(max_length=50)
    evidence_element_ids: list[int] = Field(max_length=100)
    evidence_texts: list[str] = Field(max_length=50)
    conflict_reasons: list[str] = Field(max_length=20)


class OverLimitResumeInput(BaseModel):
    """人工恢复中断时允许提交的最小输入，禁止前端覆盖流程状态或证据。"""

    model_config = ConfigDict(extra="forbid")

    decision: Literal["confirm_over_limit", "confirm_not_over_limit"]
    note: str | None = Field(default=None, max_length=500)

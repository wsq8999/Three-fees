from __future__ import annotations

"""当前超标原因与本市历史案例相似性判断契约。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ReasonJudgment(BaseModel):
    """只保存业务可解释结论和证据引用，不要求模型输出思维链。

    该结构同时引用本次报告和历史报告证据，避免“历史上经常如此”被错误地当成本次原因。
    ``insufficient_evidence`` 是正常业务结果，不应被当作系统异常。
    """

    model_config = ConfigDict(extra="forbid")

    # completed表示证据足以判断；insufficient_evidence表示应交给业务员补充。
    status: Literal["completed", "insufficient_evidence"]
    # 本次超标原因，只能由本次报告事实支持，不能直接复制历史原因。
    primary_reason: str | None = Field(max_length=1000)
    # 用于城市内统计与检索的稳定原因类别。
    reason_category: str | None = Field(max_length=100)
    # 历史不足时为null；有可比历史时才给出true或false。
    similarity_to_history: bool | None
    # 命中的结构化案例ID和原始历史文档ID分别保存，兼顾高效检索与原文追溯。
    matched_case_ids: list[str] = Field(max_length=30)
    matched_history_document_ids: list[str] = Field(max_length=30)
    # 只有模型判断适用于本次事实的confirmed记忆才能出现在该列表。
    matched_correction_memory_ids: list[str] = Field(max_length=30)
    correction_memory_applied: bool
    correction_memory_explanation: str | None = Field(max_length=1000)
    # 当前、历史证据元素分开保存，页面能明确展示结论两侧各自依据。
    supporting_current_element_ids: list[int] = Field(max_length=50)
    supporting_history_element_ids: list[int] = Field(max_length=100)
    # 面向业务员的简洁说明，不保存或索取模型隐藏思维链。
    reasoning_summary: str = Field(min_length=1, max_length=1500)
    # 差异与不确定项决定是否需要人工复核，也是后续纠错入口的重要上下文。
    differences_from_history: list[str] = Field(max_length=20)
    uncertain_items: list[str] = Field(max_length=30)
    confidence: float = Field(ge=0, le=1)

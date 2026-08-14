from __future__ import annotations

"""历史稽核案例的REST与AI结构化契约。"""

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class HistoricalCaseExtraction(BaseModel):
    """Kimi从一份历史报告中提取的可追溯案例事实。

    ``extra='forbid'`` 会拒绝模型擅自增加的字段，避免提示词变化后未知数据悄悄入库。
    可空字段表示“原报告未提供”，与空字符串和模型猜测严格区分。
    """

    model_config = ConfigDict(extra="forbid")

    # 历史报告明确覆盖的缴费期或稽核期。
    billing_period: str | None = Field(max_length=100)
    # 报告中被判定超标的指标名称，可同时包含多项。
    over_limit_items: list[str] = Field(max_length=30)
    # 报告明确说明或多项可见证据直接支持的首要原因。
    primary_reason: str | None = Field(max_length=1000)
    # 用于聚合检索的短分类，不替代更完整的primary_reason。
    reason_category: str | None = Field(max_length=100)
    # RAG阶段使用的精简事实，减少反复发送整份历史报告。
    key_facts: list[str] = Field(max_length=30)
    # 只能引用本次输入中出现的document_element.id，服务层会再次核验归属。
    evidence_element_ids: list[int] = Field(max_length=50)
    # 内部矛盾、图片模糊或报告没有说清的内容必须在此显式披露。
    uncertain_items: list[str] = Field(max_length=30)
    # 模型对整条历史案例结构化结果的综合置信度，统一为0~1。
    confidence: float = Field(ge=0, le=1)


class AuditCaseCreate(BaseModel):
    """指定要结构化的历史报告。"""

    # 业务资格、UUID格式和城市归属仍由服务层校验，不能只信任字符串长度。
    source_document_id: str = Field(min_length=36, max_length=36)


class AuditCaseView(BaseModel):
    """供检索审计和前端查看的案例资源。

    响应包含原报告ID与证据元素ID，但不暴露storage_key和服务器绝对路径。
    """

    id: str
    city_code: str
    site_id: str
    source_document_id: str
    source_title: str
    status: str
    # 与解析状态分离：active参与判断，paused暂不使用，invalidated已确认错误。
    memory_status: str
    billing_period: str | None
    over_limit_items: list[object]
    primary_reason: str | None
    reason_category: str | None
    key_facts: list[object]
    evidence_element_ids: list[object]
    uncertain_items: list[object]
    confidence: float | None
    model_name: str | None
    prompt_version: str | None
    error_code: str | None
    error_message: str | None
    created_at: datetime
    analyzed_at: datetime | None


class AuditCaseList(BaseModel):
    items: list[AuditCaseView]

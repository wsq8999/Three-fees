from __future__ import annotations

"""AI稽核报告草稿的严格结构化输出契约。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

REQUIRED_SECTION_CODES = {
    "over_limit_summary",
    "metric_verification",
    "historical_comparison",
    "reason_analysis",
    "audit_conclusion",
    "remediation_summary",
}

# 正文面向业务人员阅读，不把模型的完整分析过程倾倒进Word。不同章节按业务价值
# 分配篇幅：原因和整改可以稍长，历史比较只保留与本次判断直接有关的一句话。
SECTION_CONTENT_LIMITS = {
    "over_limit_summary": 180,
    "metric_verification": 180,
    "historical_comparison": 150,
    "reason_analysis": 220,
    "audit_conclusion": 160,
    "remediation_summary": 220,
}
TOTAL_REPORT_CONTENT_LIMIT = 850


class ReportSection(BaseModel):
    """一段可独立审核、可追溯且适合直接写入Word的精简正文。"""

    model_config = ConfigDict(extra="forbid")

    section_code: Literal[
        "over_limit_summary",
        "metric_verification",
        "historical_comparison",
        "reason_analysis",
        "audit_conclusion",
        "remediation_summary",
    ]
    title: str = Field(min_length=2, max_length=100)
    # 先用所有章节的统一硬上限约束模型JSON，再由整份报告校验器执行章节专属上限。
    content: str = Field(min_length=2, max_length=max(SECTION_CONTENT_LIMITS.values()))
    # 当前与历史元素分开引用，页面和未来Word内部追踪不会混淆证据来源。
    supporting_current_element_ids: list[int] = Field(max_length=50)
    supporting_history_element_ids: list[int] = Field(max_length=100)
    # 数值结论必须引用程序生成的calculation_id，不能只引用模型自己写出的算式。
    calculation_references: list[str] = Field(max_length=50)


class AuditReportDraft(BaseModel):
    """Kimi生成、等待业务员审核的完整报告草稿。"""

    model_config = ConfigDict(extra="forbid")

    status: Literal["draft", "needs_review"]
    title: str = Field(min_length=2, max_length=200)
    sections: list[ReportSection] = Field(min_length=6, max_length=6)
    uncertain_items: list[str] = Field(max_length=30)
    requires_human_review: bool
    review_reasons: list[str] = Field(max_length=30)
    confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def require_all_sections_once(self) -> AuditReportDraft:
        """校验固定章节完整性和篇幅，防止模型生成冗长、重复的业务正文。"""
        codes = [section.section_code for section in self.sections]
        if set(codes) != REQUIRED_SECTION_CODES or len(codes) != len(set(codes)):
            raise ValueError("报告必须且只能包含规定的六个章节")
        for section in self.sections:
            limit = SECTION_CONTENT_LIMITS[section.section_code]
            if len(section.content.strip()) > limit:
                raise ValueError(f"章节{section.section_code}正文不得超过{limit}字")
        total_length = sum(len(section.content.strip()) for section in self.sections)
        if total_length > TOTAL_REPORT_CONTENT_LIMIT:
            raise ValueError(f"报告六个章节正文合计不得超过{TOTAL_REPORT_CONTENT_LIMIT}字")
        return self

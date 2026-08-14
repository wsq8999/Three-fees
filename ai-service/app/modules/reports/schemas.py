from __future__ import annotations

"""报告编辑、审核和下载REST契约。"""

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

SectionCode = Literal[
    "over_limit_summary",
    "metric_verification",
    "historical_comparison",
    "reason_analysis",
    "audit_conclusion",
    "remediation_summary",
]
REQUIRED_SECTION_CODES = {
    "over_limit_summary",
    "metric_verification",
    "historical_comparison",
    "reason_analysis",
    "audit_conclusion",
    "remediation_summary",
}


class ReportSectionEdit(BaseModel):
    """页面可编辑字段；证据引用由服务端从原版本继承。"""

    model_config = ConfigDict(extra="forbid")
    section_code: SectionCode
    title: str = Field(min_length=2, max_length=100)
    content: str = Field(min_length=2, max_length=5000)


class ReportUpdate(BaseModel):
    """保存一个完整的新版本，避免局部更新留下章节缺失。"""

    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=2, max_length=200)
    sections: list[ReportSectionEdit] = Field(min_length=6, max_length=6)
    change_summary: str = Field(min_length=2, max_length=500)

    @model_validator(mode="after")
    def validate_section_set(self) -> ReportUpdate:
        """六个规定章节必须各出现一次。"""
        codes = [section.section_code for section in self.sections]
        if set(codes) != REQUIRED_SECTION_CODES or len(codes) != len(set(codes)):
            raise ValueError("报告必须且只能包含规定的六个章节")
        return self


class ReportReview(BaseModel):
    """审核状态动作；退回时必须说明原因。"""

    model_config = ConfigDict(extra="forbid")
    action: Literal["submit", "return", "approve"]
    note: str | None = Field(default=None, max_length=1000)

    @model_validator(mode="after")
    def require_return_note(self) -> ReportReview:
        """退回修改必须留下业务员可见的理由。"""
        if self.action == "return" and not (self.note or "").strip():
            raise ValueError("退回报告时必须填写原因")
        return self


class ReportSectionView(BaseModel):
    section_code: SectionCode
    title: str
    content: str
    supporting_current_element_ids: list[int]
    supporting_history_element_ids: list[int]
    calculation_references: list[str]


class ReportVersionView(BaseModel):
    version_no: int
    title: str
    sections: list[ReportSectionView]
    uncertain_items: list[str]
    review_reasons: list[str]
    change_summary: str
    has_docx: bool
    docx_sha256: str | None
    docx_size_bytes: int | None
    created_by: str
    created_at: datetime
    generated_at: datetime | None


class ReportVersionSummary(BaseModel):
    version_no: int
    change_summary: str
    created_by: str
    created_at: datetime
    has_docx: bool


class ReportView(BaseModel):
    id: str
    city_code: str
    site_id: str
    task_id: str
    analysis_run_id: str
    status: Literal["draft", "in_review", "returned", "approved"]
    current_version: int
    approved_version: int | None
    review_note: str | None
    current: ReportVersionView
    versions: list[ReportVersionSummary]
    content_url: str | None
    created_by: str
    reviewed_by: str | None
    created_at: datetime
    updated_at: datetime
    reviewed_at: datetime | None

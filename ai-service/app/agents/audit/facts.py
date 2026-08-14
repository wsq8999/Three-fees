from __future__ import annotations

"""本次截图或整份DOCX报告的严格业务事实输出模型。"""

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

DECIMAL_TEXT_PATTERN = re.compile(r"^-?\d+(?:\.\d+)?$")
DECIMAL_WITH_UNIT_PATTERN = re.compile(
    r"^(?P<number>-?\d+(?:\.\d+)?)\s*"
    r"(?:%|元|度|瓦|千瓦|千瓦时|kwh|kw|w)?$",
    re.IGNORECASE,
)
NULL_VALUE_MARKERS = {"", "-", "--", "null", "n/a", "未知", "未识别", "无法识别", "不适用"}


class ExtractedMetric(BaseModel):
    """截图、正文或表格中一项可核对的标杆指标。

    这里刻意区分“实际值”“标杆上下限”和“截图显示超标率”。历史同比/环比
    描述的是标杆来源，不代表可以把实际值直接与历史值计算普通增长率。完整标杆
    生成需要三费系统的历史审核数据和逐日额定功率；当前报告只有截图时，只允许
    校验截图内的实际值、标杆上限、超标结论和超标率是否互相一致。
    """

    model_config = ConfigDict(extra="forbid")

    metric_name: str = Field(min_length=1, max_length=100)
    # actual_value和标杆上下限必须使用同一单位，来源于截图或报告直接显示值。
    actual_value: str | None
    benchmark_lower_value: str | None
    benchmark_upper_value: str | None
    # 这是截图已经显示的“超标率”，不是同比/环比的普通增长率。
    reported_over_limit_rate_percent: str | None
    unit: str | None
    comparison_type: str | None = Field(max_length=50)
    # 只有材料明确说明首期缴费、无审核通过历史等规则条件时，才可判为not_applicable。
    comparison_applicability: Literal["applicable", "not_applicable", "unknown"]
    applicability_reason: str | None = Field(max_length=300)
    is_over_limit: bool | None
    evidence_text: str | None = Field(max_length=500)
    evidence_element_ids: list[int] = Field(max_length=20)
    confidence: float = Field(ge=0, le=1)

    @field_validator(
        "actual_value",
        "benchmark_lower_value",
        "benchmark_upper_value",
        "reported_over_limit_rate_percent",
        mode="before",
    )
    @classmethod
    def validate_decimal_text(cls, value: object) -> str | None:
        """规范化明确的千分位和单位，同时拒绝公式、区间等含糊数值。"""
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError("数值字段必须是字符串或null")
        normalized = value.strip().replace(",", "").replace("，", "")
        if normalized.casefold() in NULL_VALUE_MARKERS:
            return None
        matched = DECIMAL_WITH_UNIT_PATTERN.fullmatch(normalized)
        if matched is not None:
            normalized = matched.group("number")
        if not DECIMAL_TEXT_PATTERN.fullmatch(normalized):
            # 这些字段本身可空；说明文字、区间或公式不能被强行截取成一个数值。
            return None
        return normalized

    @model_validator(mode="after")
    def require_not_applicable_reason(self) -> ExtractedMetric:
        """不适用必须携带材料中的明确原因，防止模型把看不清误判为业务不适用。"""
        if (
            self.comparison_applicability == "not_applicable"
            and not (self.applicability_reason or "").strip()
        ):
            raise ValueError("比较状态为not_applicable时必须说明材料中的适用性原因")
        return self


class ScreenshotFacts(BaseModel):
    """一张业务截图的完整视觉识别结果。"""

    model_config = ConfigDict(extra="forbid")

    # 这里只记录三费系统页面明确展示的总体状态；不能根据单个百分比自行推断。
    system_over_limit_status: Literal["yes", "no", "unknown"]
    system_over_limit_evidence_text: str | None = Field(max_length=500)
    # 独立截图没有DOCX元素编号，因此该字段必须为空；截图材料ID本身就是可追溯证据。
    system_over_limit_evidence_element_ids: list[int] = Field(max_length=20)
    site_name_in_image: str | None = Field(max_length=200)
    billing_period: str | None = Field(max_length=100)
    over_limit_items: list[str] = Field(max_length=20)
    metrics: list[ExtractedMetric] = Field(max_length=30)
    observations: list[str] = Field(max_length=20)
    uncertain_items: list[str] = Field(max_length=20)
    overall_confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def forbid_element_ids_for_standalone_screenshot(self) -> ScreenshotFacts:
        """独立截图不存在DOCX元素编号，禁止模型伪造无法追溯的引用。"""
        if self.system_over_limit_evidence_element_ids:
            raise ValueError("独立截图的系统超标状态证据元素编号必须为空")
        return self


class ExplicitStatement(BaseModel):
    """DOCX中已经明确写出的事实或原因陈述，不代表Agent已经认可其结论。"""

    model_config = ConfigDict(extra="forbid")

    statement: str = Field(min_length=1, max_length=500)
    statement_type: str = Field(min_length=1, max_length=50)
    source_element_ids: list[int] = Field(min_length=1, max_length=20)
    confidence: float = Field(ge=0, le=1)


class ReportFacts(BaseModel):
    """整份本次DOCX的文字、表格和图片联合提取结果。"""

    model_config = ConfigDict(extra="forbid")

    # 总体状态用于决定是否进入稽核；unknown必须转人工确认，不能由模型猜测。
    system_over_limit_status: Literal["yes", "no", "unknown"]
    system_over_limit_evidence_text: str | None = Field(max_length=500)
    system_over_limit_evidence_element_ids: list[int] = Field(max_length=20)
    document_title_in_content: str | None = Field(max_length=200)
    site_name_in_content: str | None = Field(max_length=200)
    billing_period: str | None = Field(max_length=100)
    document_summary: str | None = Field(max_length=1000)
    over_limit_items: list[str] = Field(max_length=30)
    metrics: list[ExtractedMetric] = Field(max_length=50)
    explicit_statements: list[ExplicitStatement] = Field(max_length=30)
    observations: list[str] = Field(max_length=30)
    uncertain_items: list[str] = Field(max_length=30)
    overall_confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def require_traceable_system_status(self) -> ReportFacts:
        """明确的系统状态必须引用本次DOCX元素，避免条件分支建立在不可追溯文本上。"""
        if self.system_over_limit_status in {"yes", "no"} and not (
            self.system_over_limit_evidence_element_ids
        ):
            raise ValueError("系统超标状态为yes或no时必须提供证据元素编号")
        return self

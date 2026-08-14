from __future__ import annotations

"""从模型提取值生成可复算、可审计的标杆指标校验结果。"""

from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

# 报告展示保留最多四位小数；差异判断使用0.1个百分点，避免纯舍入造成假冲突。
FOUR_DECIMALS = Decimal("0.0001")
RATE_TOLERANCE = Decimal("0.1")


class MetricCalculation(BaseModel):
    """一项模型识别指标对应的截图算术复算和一致性校验结果。

    本结构不宣称已经重新生成三费系统的标杆值。完整标杆值还依赖历史审核记录、
    逐日额定功率和城市空调参数；当前阶段仅校验报告中已经显示的实际值、标杆上限、
    超标率和超标结论，避免把同比/环比误解成普通增长率。
    """

    model_config = ConfigDict(extra="forbid")

    calculation_id: str
    document_id: str
    metric_name: str
    comparison_type: str | None
    unit: str | None
    actual_value: str | None
    benchmark_lower_value: str | None
    benchmark_upper_value: str | None
    reported_over_limit_rate_percent: str | None
    calculated_over_limit_rate_percent: str | None
    reported_is_over_limit: bool | None
    calculated_is_over_limit: bool | None
    # not_applicable是业务规则的正常结果，不能与材料缺失导致的unverified混为一谈。
    verification_status: Literal["verified", "unverified", "conflict", "not_applicable"]
    calculation_formula: str | None
    issues: list[str] = Field(max_length=10)
    evidence_element_ids: list[int] = Field(max_length=20)


class CalculationSummary(BaseModel):
    """供报告节点和前端直接使用的计算汇总。"""

    model_config = ConfigDict(extra="forbid")

    status: Literal["completed"] = "completed"
    metrics: list[MetricCalculation]
    verified_count: int = Field(ge=0)
    unverified_count: int = Field(ge=0)
    conflict_count: int = Field(ge=0)
    not_applicable_count: int = Field(ge=0)
    requires_human_review: bool


def _decimal(value: object) -> Decimal | None:
    """只接受事实提取阶段已经规范化的十进制字符串。"""
    if value is None:
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def _decimal_text(value: Decimal) -> str:
    """输出无科学计数法的稳定字符串，同时去掉无意义末尾零。"""
    normalized = value.quantize(FOUR_DECIMALS, rounding=ROUND_HALF_UP)
    return format(normalized, "f").rstrip("0").rstrip(".") or "0"


def calculate_metrics(facts: dict[str, object]) -> CalculationSummary:
    """复算截图超标率和标杆上限判断，不对缺失字段做任何猜测。

    模型只负责识别“哪个值是什么”；本函数统一使用“实际值>标杆上限”判断是否
    超标，并在超标时使用“(实际值-标杆上限)/标杆上限×100%”复算截图超标率。
    同比、环比只作为标杆类型保留，绝不再触发普通增长率公式。完整A/B/C标杆生成
    尚未接入原始业务数据，因此不能在这里假装完成标杆来源复算。
    """
    results: list[MetricCalculation] = []
    documents = facts.get("documents")
    if not isinstance(documents, list):
        documents = []
    for document in documents:
        if not isinstance(document, dict):
            continue
        document_id = str(document.get("document_id") or "")
        metrics = document.get("metrics")
        if not isinstance(metrics, list):
            continue
        for index, metric in enumerate(metrics):
            if not isinstance(metric, dict):
                continue
            actual = _decimal(metric.get("actual_value"))
            benchmark_lower = _decimal(metric.get("benchmark_lower_value"))
            benchmark_upper = _decimal(metric.get("benchmark_upper_value"))
            reported_rate = _decimal(metric.get("reported_over_limit_rate_percent"))
            comparison_type = str(metric.get("comparison_type") or "") or None
            applicability = str(metric.get("comparison_applicability") or "unknown")
            applicability_reason = str(metric.get("applicability_reason") or "").strip()
            issues: list[str] = []
            calculated_rate: Decimal | None = None
            calculated_over: bool | None = None
            formula: str | None = None

            reported_over = metric.get("is_over_limit")
            if not isinstance(reported_over, bool):
                reported_over = None

            if applicability == "not_applicable":
                # 不适用必须来自材料中的明确规则事实；原因文本随结果保留，供业务员追溯。
                issues.append(applicability_reason or "材料明确标记该比较不适用")
                status: Literal["verified", "unverified", "conflict", "not_applicable"] = (
                    "not_applicable"
                )
            else:
                if actual is not None and benchmark_upper is not None:
                    calculated_over = actual > benchmark_upper
                    formula = "实际值>标杆上限；超标率=(实际值-标杆上限)/标杆上限×100%"
                    if calculated_over:
                        if benchmark_upper > 0:
                            calculated_rate = (
                                (actual - benchmark_upper) / benchmark_upper * Decimal("100")
                            )
                        else:
                            issues.append("标杆上限小于或等于0，无法计算超标率")
                else:
                    issues.append("缺少实际值或标杆上限，无法完成截图结果复算")

                if (
                    calculated_rate is not None
                    and reported_rate is not None
                    and abs(calculated_rate - reported_rate) > RATE_TOLERANCE
                ):
                    issues.append("材料显示的超标率与程序复算结果不一致")
                if calculated_over is False and reported_rate not in {None, Decimal("0")}:
                    issues.append("实际值未超过标杆上限，但材料显示了非零超标率，结果不一致")
                if (
                    calculated_over is not None
                    and reported_over is not None
                    and calculated_over != reported_over
                ):
                    issues.append("材料显示的超标结论与程序标杆上限判断不一致")

                completed_calculation = calculated_over is not None
                has_conflict = any("不一致" in issue for issue in issues)
                if has_conflict:
                    status = "conflict"
                elif completed_calculation:
                    status = "verified"
                else:
                    status = "unverified"
            evidence_ids = metric.get("evidence_element_ids")
            if not isinstance(evidence_ids, list):
                evidence_ids = []
            results.append(
                MetricCalculation(
                    calculation_id=f"{document_id}:metric:{index}",
                    document_id=document_id,
                    metric_name=str(metric.get("metric_name") or f"指标{index + 1}"),
                    comparison_type=comparison_type,
                    unit=str(metric.get("unit")) if metric.get("unit") is not None else None,
                    actual_value=str(metric.get("actual_value")) if actual is not None else None,
                    benchmark_lower_value=(
                        str(metric.get("benchmark_lower_value"))
                        if benchmark_lower is not None
                        else None
                    ),
                    benchmark_upper_value=(
                        str(metric.get("benchmark_upper_value"))
                        if benchmark_upper is not None
                        else None
                    ),
                    reported_over_limit_rate_percent=(
                        str(metric.get("reported_over_limit_rate_percent"))
                        if reported_rate is not None
                        else None
                    ),
                    calculated_over_limit_rate_percent=(
                        _decimal_text(calculated_rate) if calculated_rate is not None else None
                    ),
                    reported_is_over_limit=reported_over,
                    calculated_is_over_limit=calculated_over,
                    verification_status=status,
                    calculation_formula=formula,
                    issues=issues,
                    evidence_element_ids=[
                        int(item) for item in evidence_ids if isinstance(item, int)
                    ],
                )
            )

    verified_count = sum(item.verification_status == "verified" for item in results)
    unverified_count = sum(item.verification_status == "unverified" for item in results)
    conflict_count = sum(item.verification_status == "conflict" for item in results)
    not_applicable_count = sum(item.verification_status == "not_applicable" for item in results)
    return CalculationSummary(
        metrics=results,
        verified_count=verified_count,
        unverified_count=unverified_count,
        conflict_count=conflict_count,
        not_applicable_count=not_applicable_count,
        requires_human_review=conflict_count > 0,
    )

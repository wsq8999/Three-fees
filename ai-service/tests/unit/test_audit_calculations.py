"""标杆截图确定性复算测试。"""

from app.agents.audit.calculations import calculate_metrics


def _facts(metric: dict[str, object]) -> dict[str, object]:
    """构造只包含一项指标的最小事实快照。"""
    return {
        "documents": [
            {
                "document_id": "document-1",
                "metrics": [metric],
            }
        ]
    }


def test_over_limit_rate_and_upper_bound_are_recalculated() -> None:
    """程序应按实际值与标杆上限复算20.5%，不得使用普通同比增长率。"""
    summary = calculate_metrics(
        _facts(
            {
                "metric_name": "历史日均电量标杆-同比",
                "actual_value": "120.50",
                "benchmark_lower_value": "80.00",
                "benchmark_upper_value": "100.00",
                "reported_over_limit_rate_percent": "20.5",
                "unit": "度",
                "comparison_type": "同比",
                "comparison_applicability": "applicable",
                "applicability_reason": None,
                "is_over_limit": True,
                "evidence_element_ids": [8],
            }
        )
    )

    metric = summary.metrics[0]
    assert metric.calculated_over_limit_rate_percent == "20.5"
    assert metric.calculated_is_over_limit is True
    assert metric.verification_status == "verified"
    assert "标杆上限" in (metric.calculation_formula or "")
    assert summary.conflict_count == 0


def test_inconsistent_reported_over_limit_rate_requires_review() -> None:
    """截图超标率与确定性复算冲突时，不能静默采用其中一个。"""
    summary = calculate_metrics(
        _facts(
            {
                "metric_name": "历史日均电量标杆-环比",
                "actual_value": "120",
                "benchmark_lower_value": None,
                "benchmark_upper_value": "100",
                "reported_over_limit_rate_percent": "12",
                "unit": "度",
                "comparison_type": "环比",
                "comparison_applicability": "applicable",
                "applicability_reason": None,
                "is_over_limit": True,
                "evidence_element_ids": [9],
            }
        )
    )

    assert summary.metrics[0].calculated_over_limit_rate_percent == "20"
    assert summary.metrics[0].verification_status == "conflict"
    assert summary.conflict_count == 1
    assert summary.requires_human_review is True


def test_display_rounding_within_tolerance_is_not_a_conflict() -> None:
    """截图标杆只显示两位小数时，0.1个百分点内的差异应视为展示舍入。"""
    summary = calculate_metrics(
        _facts(
            {
                "metric_name": "历史日均电量标杆-环比",
                "actual_value": "68.64",
                "benchmark_lower_value": "0.39",
                "benchmark_upper_value": "43.18",
                "reported_over_limit_rate_percent": "58.94",
                "unit": "度",
                "comparison_type": "环比",
                "comparison_applicability": "applicable",
                "applicability_reason": None,
                "is_over_limit": True,
                "evidence_element_ids": [10],
            }
        )
    )

    assert summary.metrics[0].calculated_over_limit_rate_percent == "58.9625"
    assert summary.metrics[0].verification_status == "verified"


def test_missing_benchmark_upper_bound_is_unverified() -> None:
    """看不清标杆上限时必须保持未验证，不能拿历史值替代。"""
    summary = calculate_metrics(
        _facts(
            {
                "metric_name": "历史日均电量标杆-同比",
                "actual_value": "50",
                "benchmark_lower_value": None,
                "benchmark_upper_value": None,
                "reported_over_limit_rate_percent": None,
                "unit": "度",
                "comparison_type": "同比",
                "comparison_applicability": "unknown",
                "applicability_reason": None,
                "is_over_limit": None,
                "evidence_element_ids": [],
            }
        )
    )

    metric = summary.metrics[0]
    assert metric.calculated_over_limit_rate_percent is None
    assert metric.verification_status == "unverified"
    assert metric.issues == ["缺少实际值或标杆上限，无法完成截图结果复算"]


def test_first_billing_period_is_not_applicable_instead_of_unverified() -> None:
    """材料明确为首期缴费时，环比应归类为不适用而不是输入不足。"""
    summary = calculate_metrics(
        _facts(
            {
                "metric_name": "历史日均电量标杆-环比",
                "actual_value": None,
                "benchmark_lower_value": None,
                "benchmark_upper_value": None,
                "reported_over_limit_rate_percent": None,
                "unit": "度",
                "comparison_type": "环比",
                "comparison_applicability": "not_applicable",
                "applicability_reason": "材料明确说明这是首期缴费",
                "is_over_limit": None,
                "evidence_element_ids": [11],
            }
        )
    )

    metric = summary.metrics[0]
    assert metric.verification_status == "not_applicable"
    assert metric.issues == ["材料明确说明这是首期缴费"]
    assert summary.not_applicable_count == 1
    assert summary.unverified_count == 0

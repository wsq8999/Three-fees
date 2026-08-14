"""AI报告精简输出契约测试。"""

import pytest
from pydantic import ValidationError

from app.agents.audit.report import AuditReportDraft

SECTION_TITLES = {
    "over_limit_summary": "超标情况说明",
    "metric_verification": "指标复算",
    "historical_comparison": "历史情况对比",
    "reason_analysis": "原因分析",
    "audit_conclusion": "稽核结论",
    "remediation_summary": "整改小结",
}


def _report(section_contents: dict[str, str] | None = None) -> dict[str, object]:
    """构造六章完整的最小报告，允许测试按章节覆盖正文。"""
    contents = section_contents or {}
    return {
        "status": "draft",
        "title": "测试报账点三费标杆超标稽核报告",
        "sections": [
            {
                "section_code": code,
                "title": title,
                "content": contents.get(code, "经核查，本项结论正常。"),
                "supporting_current_element_ids": [],
                "supporting_history_element_ids": [],
                "calculation_references": [],
            }
            for code, title in SECTION_TITLES.items()
        ],
        "uncertain_items": [],
        "requires_human_review": False,
        "review_reasons": [],
        "confidence": 0.8,
    }


def test_concise_six_section_report_is_valid() -> None:
    """六章均使用简短业务结论时，报告应正常进入后续审核流程。"""
    report = AuditReportDraft.model_validate(_report())

    assert len(report.sections) == 6


def test_section_specific_length_limit_rejects_verbose_history() -> None:
    """历史比较只能保留必要结论，超过专属篇幅时必须拒绝模型输出。"""
    with pytest.raises(ValidationError, match="historical_comparison正文不得超过150字"):
        AuditReportDraft.model_validate(_report({"historical_comparison": "历" * 151}))


def test_total_length_limit_rejects_repetition_across_sections() -> None:
    """各章单独未超限但全文重复堆砌时，仍应由总篇幅约束阻止。"""
    contents = {
        "over_limit_summary": "超" * 145,
        "metric_verification": "核" * 145,
        "historical_comparison": "历" * 145,
        "reason_analysis": "因" * 145,
        "audit_conclusion": "结" * 145,
        "remediation_summary": "改" * 145,
    }

    with pytest.raises(ValidationError, match="正文合计不得超过850字"):
        AuditReportDraft.model_validate(_report(contents))

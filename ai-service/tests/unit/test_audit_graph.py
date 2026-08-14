import json

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from app.agents.audit.context import AuditAgentContext
from app.agents.audit.graph import audit_graph, build_audit_graph
from app.integrations.ai.base import AIProviderError
from app.modules.documents.runtime_reader import MaterialPayload


class FakeProvider:
    """单元测试模型，验证LangGraph数据流而不访问网络。"""

    model_name = "fake-kimi"

    def generate_structured(self, **kwargs) -> dict[str, object]:
        if kwargs.get("schema_name") == "electricity_audit_report_draft":
            return {
                "status": "draft",
                "title": "苏州测试点电费超标稽核报告草稿",
                "sections": [
                    {
                        "section_code": code,
                        "title": title,
                        "content": "现有测试材料不足，需业务员补充并复核。",
                        "supporting_current_element_ids": [],
                        "supporting_history_element_ids": [],
                        "calculation_references": [],
                    }
                    for code, title in (
                        ("over_limit_summary", "超标情况说明"),
                        ("metric_verification", "指标复算"),
                        ("historical_comparison", "历史情况对比"),
                        ("reason_analysis", "原因分析"),
                        ("audit_conclusion", "稽核结论"),
                        ("remediation_summary", "整改小结"),
                    )
                ],
                "uncertain_items": ["测试图片没有业务文字"],
                "requires_human_review": False,
                "review_reasons": [],
                "confidence": 0.2,
            }
        if kwargs.get("schema_name") == "electricity_audit_reason_judgment":
            return {
                "status": "insufficient_evidence",
                "primary_reason": None,
                "reason_category": None,
                "similarity_to_history": None,
                "matched_case_ids": [],
                "matched_history_document_ids": [],
                "matched_correction_memory_ids": [],
                "correction_memory_applied": False,
                "correction_memory_explanation": None,
                "supporting_current_element_ids": [],
                "supporting_history_element_ids": [],
                "reasoning_summary": "测试材料不足以判断具体原因。",
                "differences_from_history": [],
                "uncertain_items": ["测试图片没有业务文字"],
                "confidence": 0.2,
            }
        return {
            "system_over_limit_status": "yes",
            "system_over_limit_evidence_text": "截图标记为超标",
            "system_over_limit_evidence_element_ids": [],
            "site_name_in_image": "苏州测试点",
            "billing_period": None,
            "over_limit_items": [],
            "metrics": [],
            "observations": [],
            "uncertain_items": ["测试图片没有业务文字"],
            "overall_confidence": 0.2,
        }


class FakeMaterialReader:
    """提供一张最小图片签名字节。"""

    def read(self, document_id: str) -> MaterialPayload:
        return MaterialPayload(
            document_id=document_id,
            media_type="image/png",
            content=b"\x89PNG\r\n\x1a\nunit-test",
        )


class OutOfScopeReportProvider(FakeProvider):
    """故意让报告引用不存在的本次元素，验证本地白名单不会信任模型。"""

    def generate_structured(self, **kwargs) -> dict[str, object]:
        result = super().generate_structured(**kwargs)
        if kwargs.get("schema_name") == "electricity_audit_report_draft":
            sections = result["sections"]
            assert isinstance(sections, list)
            sections[0]["supporting_current_element_ids"] = [999]
        return result


class ScreeningProvider(FakeProvider):
    """按测试参数返回系统总体状态，并记录是否进入后续模型节点。"""

    def __init__(self, status: str) -> None:
        self.status = status
        self.schema_calls: list[str] = []

    def generate_structured(self, **kwargs) -> dict[str, object]:
        schema_name = str(kwargs.get("schema_name"))
        self.schema_calls.append(schema_name)
        result = super().generate_structured(**kwargs)
        if schema_name == "electricity_audit_screenshot_facts":
            result["system_over_limit_status"] = self.status
            result["system_over_limit_evidence_text"] = (
                "系统明确显示未超标" if self.status == "no" else None
            )
        return result


class MetricOverLimitProvider(ScreeningProvider):
    """总体状态未知，但返回一项可由程序复算确认的截图超标指标。"""

    def __init__(self) -> None:
        super().__init__("unknown")

    def generate_structured(self, **kwargs) -> dict[str, object]:
        result = super().generate_structured(**kwargs)
        if kwargs.get("schema_name") == "electricity_audit_screenshot_facts":
            result["over_limit_items"] = ["额定功率标杆"]
            result["metrics"] = [
                {
                    "metric_name": "额定功率标杆",
                    "actual_value": "172.62",
                    "benchmark_lower_value": None,
                    "benchmark_upper_value": "100",
                    "reported_over_limit_rate_percent": "72.62",
                    "unit": "度",
                    "comparison_type": None,
                    "comparison_applicability": "applicable",
                    "applicability_reason": None,
                    "is_over_limit": True,
                    "evidence_text": "高于上限超标，超标率72.62%",
                    "evidence_element_ids": [],
                    "confidence": 0.98,
                }
            ]
        return result


class CorrectionMemoryRetryProvider(FakeProvider):
    """首次误引纠错来源元素，第二次按白名单修正，模拟真实Kimi偶发错误。"""

    def __init__(self, *, always_invalid: bool = False) -> None:
        self.always_invalid = always_invalid
        self.reason_calls = 0
        self.reason_inputs: list[list[dict[str, object]]] = []

    def generate_structured(self, **kwargs) -> dict[str, object]:
        if kwargs.get("schema_name") != "electricity_audit_reason_judgment":
            return super().generate_structured(**kwargs)

        self.reason_calls += 1
        self.reason_inputs.append(kwargs["user_content"])
        # 729是形成纠错的旧运行元素；它既不是本次材料，也没有作为历史报告被检索。
        invalid_history_ids = [729] if self.reason_calls == 1 or self.always_invalid else []
        return {
            "status": "completed",
            "primary_reason": "资管系统未及时更新",
            "reason_category": "标杆配置异常",
            "similarity_to_history": None,
            "matched_case_ids": [],
            "matched_history_document_ids": [],
            "matched_correction_memory_ids": ["memory-1"],
            "correction_memory_applied": True,
            "correction_memory_explanation": "本次情况符合人工确认记忆的适用条件。",
            "supporting_current_element_ids": [],
            "supporting_history_element_ids": invalid_history_ids,
            "reasoning_summary": "采用该报账点已经人工确认的纠错记忆。",
            "differences_from_history": [],
            "uncertain_items": [],
            "confidence": 0.9,
        }


def _correction_memory_state() -> dict:
    """构造无历史报告但已有确认纠错的冷启动状态。"""
    return {
        "task_id": "task-correction",
        "city_code": "taizhou",
        "material_refs": ["document-current"],
        "task_context": {"site_name": "陈堡花沈搬迁", "title": "纠错记忆回归测试"},
        "current_materials": [{"document_id": "document-current"}],
        "history_candidates": [],
        "correction_memories": [
            {
                "memory_id": "memory-1",
                "source_analysis_run_id": "old-run",
                "corrected_reason": "资管系统未及时更新",
                "reason_category": "标杆配置异常",
                "applicability_conditions": ["资管功率未及时更新"],
                "supporting_element_ids": [729, 730, 734, 736],
                "interpretation_summary": "人工确认实际额定功率未超标。",
                "confidence": 0.95,
            }
        ],
        "retrieval_summary": {
            "scope": "city_fallback",
            "selected_history_count": 0,
            "confirmed_correction_count": 1,
        },
        "facts": {},
        "calculations": {},
        "screening": {},
        "evidence": [],
        "judgment": {},
        "report_draft": {},
        "events": [],
    }


def _screening_state() -> dict:
    """构造不带历史记忆的最小截图流程状态。"""
    return {
        "task_id": "task-screening",
        "city_code": "suzhou",
        "material_refs": ["document-screening"],
        "task_context": {"site_name": "苏州测试点", "title": "前置筛查测试"},
        "current_materials": [{"document_id": "document-screening"}],
        "history_candidates": [],
        "correction_memories": [],
        "retrieval_summary": {"scope": "city_fallback"},
        "facts": {},
        "calculations": {},
        "screening": {},
        "evidence": [],
        "judgment": {},
        "report_draft": {},
        "events": [],
    }


def _screening_context(provider: FakeProvider, run_id: str) -> AuditAgentContext:
    """构造筛查测试运行依赖。"""
    return AuditAgentContext(
        run_id=run_id,
        user_id="user-1",
        ai_provider=provider,
        material_reader=FakeMaterialReader(),
        extract_reasoning_effort="low",
        judge_reasoning_effort="high",
    )


def test_not_over_limit_finishes_without_reason_or_report_model() -> None:
    """系统明确未超标时必须短路，避免无意义调用RAG和报告模型。"""
    provider = ScreeningProvider("no")
    graph = build_audit_graph(InMemorySaver())
    result = graph.invoke(
        _screening_state(),
        config={"configurable": {"thread_id": "screening-no"}},
        context=_screening_context(provider, "screening-no"),
    )

    assert result["screening"]["status"] == "no"
    assert result["judgment"]["status"] == "not_over_limit"
    assert result["report_draft"] == {}
    assert "electricity_audit_reason_judgment" not in provider.schema_calls
    assert "electricity_audit_report_draft" not in provider.schema_calls


def test_unknown_status_interrupts_and_resumes_same_graph_run() -> None:
    """状态不明确时先中断，人工确认超标后从同一thread继续生成报告。"""
    provider = ScreeningProvider("unknown")
    graph = build_audit_graph(InMemorySaver())
    config = {"configurable": {"thread_id": "screening-unknown"}}
    interrupted = graph.invoke(
        _screening_state(),
        config=config,
        context=_screening_context(provider, "screening-unknown"),
    )

    assert interrupted["screening"]["status"] == "unknown"
    assert interrupted["__interrupt__"][0].value["type"] == "confirm_system_over_limit"
    assert "electricity_audit_reason_judgment" not in provider.schema_calls

    resumed = graph.invoke(
        Command(resume={"decision": "confirm_over_limit", "note": "人工核对系统页面"}),
        config=config,
        context=_screening_context(provider, "screening-unknown"),
    )
    assert resumed["screening"]["status"] == "yes"
    assert resumed["screening"]["source"] == "human_confirmation"
    assert resumed["report_draft"]["status"] == "needs_review"


def test_verified_metric_over_limit_does_not_require_overall_status() -> None:
    """图片单项超标且数值复算一致时应自动继续，不强求系统总体汇总文字。"""
    provider = MetricOverLimitProvider()
    graph = build_audit_graph(InMemorySaver())
    result = graph.invoke(
        _screening_state(),
        config={"configurable": {"thread_id": "screening-metric-over"}},
        context=_screening_context(provider, "screening-metric-over"),
    )

    assert result["screening"]["status"] == "yes"
    assert result["screening"]["triggered_items"] == ["额定功率标杆"]
    assert result["screening"]["detected_metrics"][0]["verification_status"] == "verified"
    assert result["report_draft"]["status"] == "needs_review"
    assert "__interrupt__" not in result


def test_evidence_graph_refuses_to_invent_business_result() -> None:
    state = {
        "task_id": "task-1",
        "city_code": "suzhou",
        "material_refs": ["document-1"],
        "task_context": {"site_name": "苏州测试点", "title": "单元测试任务"},
        "current_materials": [{"document_id": "document-1"}],
        "history_candidates": [
            {
                "document_id": "history-1",
                "title": "历史报告",
                "elements": [],
            }
        ],
        "correction_memories": [],
        "retrieval_summary": {"scope": "same_site"},
        "facts": {},
        "calculations": {},
        "screening": {},
        "evidence": [],
        "judgment": {},
        "report_draft": {},
        "events": [],
    }

    result = audit_graph.invoke(
        state,
        config={"configurable": {"thread_id": "unit-test-run"}},
        context=AuditAgentContext(
            run_id="unit-test-run",
            user_id="user-1",
            ai_provider=FakeProvider(),
            material_reader=FakeMaterialReader(),
            extract_reasoning_effort="low",
            judge_reasoning_effort="high",
        ),
    )

    assert result["facts"]["status"] == "completed"
    assert result["facts"]["model"] == "fake-kimi"
    assert result["judgment"]["primary_reason"] is None
    assert result["calculations"]["metrics"] == []
    assert result["report_draft"]["status"] == "needs_review"
    assert result["report_draft"]["requires_human_review"] is True
    assert len(result["report_draft"]["sections"]) == 6
    assert len(result["events"]) == 8


def test_report_draft_rejects_out_of_scope_evidence() -> None:
    """报告草稿引用任何未授权元素ID时，整次工作流必须失败而不是保存脏结论。"""
    state = {
        "task_id": "task-2",
        "city_code": "suzhou",
        "material_refs": ["document-2"],
        "task_context": {"site_name": "苏州测试点", "title": "证据越界测试"},
        "current_materials": [{"document_id": "document-2"}],
        "history_candidates": [],
        "correction_memories": [],
        "retrieval_summary": {"scope": "city_fallback"},
        "facts": {},
        "calculations": {},
        "screening": {},
        "evidence": [],
        "judgment": {},
        "report_draft": {},
        "events": [],
    }

    with pytest.raises(AIProviderError, match="本次材料范围外"):
        audit_graph.invoke(
            state,
            config={"configurable": {"thread_id": "out-of-scope-report"}},
            context=AuditAgentContext(
                run_id="out-of-scope-report",
                user_id="user-1",
                ai_provider=OutOfScopeReportProvider(),
                material_reader=FakeMaterialReader(),
                extract_reasoning_effort="low",
                judge_reasoning_effort="high",
            ),
        )


def test_correction_memory_hides_old_elements_and_repairs_invalid_reference() -> None:
    """纠错旧元素只用于追溯；模型误引后应按空历史白名单自动修正一次。"""
    provider = CorrectionMemoryRetryProvider()
    result = audit_graph.invoke(
        _correction_memory_state(),
        config={"configurable": {"thread_id": "correction-memory-retry"}},
        context=AuditAgentContext(
            run_id="correction-memory-retry",
            user_id="user-1",
            ai_provider=provider,
            material_reader=FakeMaterialReader(),
            extract_reasoning_effort="low",
            judge_reasoning_effort="high",
        ),
    )

    assert provider.reason_calls == 2
    # 两次模型输入都不能出现纠错来源运行或旧元素ID，数据库原始记忆并未因此被修改。
    serialized_inputs = json.dumps(provider.reason_inputs, ensure_ascii=False)
    assert "supporting_element_ids" not in serialized_inputs
    assert "source_analysis_run_id" not in serialized_inputs
    assert "729" not in serialized_inputs
    assert result["judgment"]["matched_correction_memory_ids"] == ["memory-1"]
    assert result["judgment"]["supporting_history_element_ids"] == []


def test_correction_memory_still_rejects_second_invalid_reference() -> None:
    """受控重试后仍越界时必须失败，不能静默删除模型给出的错误证据。"""
    provider = CorrectionMemoryRetryProvider(always_invalid=True)
    with pytest.raises(AIProviderError, match="未检索的历史证据元素"):
        audit_graph.invoke(
            _correction_memory_state(),
            config={"configurable": {"thread_id": "correction-memory-invalid"}},
            context=AuditAgentContext(
                run_id="correction-memory-invalid",
                user_id="user-1",
                ai_provider=provider,
                material_reader=FakeMaterialReader(),
                extract_reasoning_effort="low",
                judge_reasoning_effort="high",
            ),
        )
    assert provider.reason_calls == 2

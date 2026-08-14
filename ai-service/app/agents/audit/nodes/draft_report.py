from __future__ import annotations

"""使用已验证事实、程序复算和RAG判断生成可审核报告草稿。"""

import json
from typing import Any

from langgraph.runtime import Runtime
from pydantic import ValidationError

from app.agents.audit.context import AuditAgentContext
from app.agents.audit.prompt_loader import load_prompt
from app.agents.audit.report import AuditReportDraft
from app.agents.audit.state import AuditAgentState
from app.integrations.ai.base import AIProviderError


def _allowed_current_ids(state: AuditAgentState) -> set[int]:
    """取得本次材料全部可引用元素ID，独立截图模式自然返回空集合。"""
    return {
        int(element["element_id"])
        for material in state["current_materials"]
        if isinstance(material, dict) and isinstance(material.get("elements"), list)
        for element in material["elements"]
        if isinstance(element, dict) and isinstance(element.get("element_id"), int)
    }


def _allowed_history_ids(state: AuditAgentState) -> set[int]:
    """合并RAG随附原文元素和结构化案例证据，不扩大到未检索历史。"""
    allowed: set[int] = set()
    for item in state["evidence"]:
        if not isinstance(item, dict):
            continue
        for element in item.get("elements", []):
            if isinstance(element, dict) and isinstance(element.get("element_id"), int):
                allowed.add(int(element["element_id"]))
        audit_case = item.get("audit_case")
        if isinstance(audit_case, dict):
            allowed.update(
                int(element_id)
                for element_id in audit_case.get("evidence_element_ids", [])
                if isinstance(element_id, int)
            )
    return allowed


def _compact_evidence(evidence: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """报告节点只需要历史结论和可引用ID，不重复发送图片和大段原文。"""
    compact: list[dict[str, Any]] = []
    for item in evidence:
        if not isinstance(item, dict):
            continue
        compact.append(
            {
                "document_id": item.get("document_id"),
                "title": item.get("title"),
                "match_scope": item.get("match_scope"),
                "match_score": item.get("match_score"),
                "audit_case": item.get("audit_case"),
                "available_element_ids": [
                    element.get("element_id")
                    for element in item.get("elements", [])
                    if isinstance(element, dict)
                ],
            }
        )
    return compact


def draft_report(
    state: AuditAgentState,
    runtime: Runtime[AuditAgentContext],
) -> dict[str, Any]:
    """调用Kimi撰写六个固定章节，并在进入状态前执行本地证据白名单校验。"""
    input_snapshot = {
        "task_context": state["task_context"],
        "facts": state["facts"],
        "calculations": state["calculations"],
        "judgment": state["judgment"],
        "history_evidence": _compact_evidence(state["evidence"]),
    }
    raw = runtime.context.ai_provider.generate_structured(
        system_prompt=load_prompt("draft_report_v2.md"),
        user_content=[
            {
                "type": "text",
                "text": "请根据以下冻结证据生成待审核报告：\n"
                + json.dumps(input_snapshot, ensure_ascii=False),
            }
        ],
        schema_name="electricity_audit_report_draft",
        json_schema=AuditReportDraft.model_json_schema(),
        reasoning_effort=runtime.context.judge_reasoning_effort,
    )
    try:
        draft = AuditReportDraft.model_validate(raw)
    except ValidationError as exc:
        raise AIProviderError("ai_output_schema_invalid", "Kimi报告草稿字段不合规") from exc

    allowed_current = _allowed_current_ids(state)
    allowed_history = _allowed_history_ids(state)
    allowed_calculations = {
        str(item["calculation_id"])
        for item in state["calculations"].get("metrics", [])
        if isinstance(item, dict) and item.get("calculation_id")
    }
    for section in draft.sections:
        if not set(section.supporting_current_element_ids) <= allowed_current:
            raise AIProviderError(
                "report_current_evidence_invalid", "报告引用了本次材料范围外的证据"
            )
        if not set(section.supporting_history_element_ids) <= allowed_history:
            raise AIProviderError("report_history_evidence_invalid", "报告引用了未检索的历史证据")
        if not set(section.calculation_references) <= allowed_calculations:
            raise AIProviderError("report_calculation_invalid", "报告引用了不存在的程序计算结果")

    # 无论模型措辞如何，程序冲突和原因证据不足都必须强制进入人工复核状态。
    review_reasons = list(draft.review_reasons)
    if state["calculations"].get("conflict_count", 0) > 0:
        review_reasons.append("存在程序复算与材料显示不一致的指标")
    if state["judgment"].get("status") != "completed":
        review_reasons.append("现有证据不足以确认具体超标原因")
    review_reasons = list(dict.fromkeys(review_reasons))
    if review_reasons:
        draft.status = "needs_review"
        draft.requires_human_review = True
        draft.review_reasons = review_reasons

    result = draft.model_dump(mode="json")
    result["model"] = runtime.context.ai_provider.model_name
    result["prompt_version"] = "draft_report_v2"
    return {
        "report_draft": result,
        "events": [
            {
                "node": "draft_report",
                "message": (
                    f"Kimi已生成{len(draft.sections)}个报告章节；"
                    f"状态为{'待人工复核' if draft.requires_human_review else '草稿待确认'}"
                ),
            }
        ],
    }

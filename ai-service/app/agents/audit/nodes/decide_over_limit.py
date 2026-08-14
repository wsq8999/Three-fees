from __future__ import annotations

"""根据本次材料事实决定是否进入正式稽核。"""

from typing import Any

from app.agents.audit.screening import ScreeningDecision, ScreeningMetric
from app.agents.audit.state import AuditAgentState


def _allowed_current_element_ids(state: AuditAgentState) -> set[int]:
    """收集本次材料元素白名单，确保筛查证据不能引用历史材料或其他城市。"""
    return {
        int(element["element_id"])
        for material in state["current_materials"]
        if isinstance(material, dict)
        for element in material.get("elements", [])
        if isinstance(element, dict) and isinstance(element.get("element_id"), int)
    }


def _collect_fact_items(state: AuditAgentState) -> list[dict[str, Any]]:
    """兼容整份DOCX和独立截图事实，但不读取历史案例来决定本次是否超标。"""
    documents = state.get("facts", {}).get("documents", [])
    return [item for item in documents if isinstance(item, dict)]


def decide_over_limit(state: AuditAgentState) -> dict[str, Any]:
    """综合系统汇总状态和可靠单项指标，只有证据不足或冲突时才人工确认。

    “存在一项可靠超标”已经足以证明本次需要稽核，不应再强求页面存在总体汇总文字。
    可靠性优先采用程序复算通过的指标；只有图片明确标注且证据引用合法时，才使用模型
    抄录结论。这样既能理解真实业务截图，又不会单凭模型猜测或一个孤立百分比放行。
    """
    allowed_elements = _allowed_current_element_ids(state)
    statuses: set[str] = set()
    triggered_items: set[str] = set()
    document_ids: set[str] = set()
    element_ids: set[int] = set()
    evidence_texts: set[str] = set()
    conflict_reasons: list[str] = []
    detected_metrics: list[ScreeningMetric] = []
    reliable_metric_over_limit = False
    any_metric_over_limit = False

    calculations = state.get("calculations", {}).get("metrics", [])
    calculation_by_id = {
        (str(item.get("document_id", "")), str(item.get("metric_name", ""))): item
        for item in calculations
        if isinstance(item, dict)
    }

    for item in _collect_fact_items(state):
        document_id = str(item.get("document_id", ""))
        document_ids_for_item = {document_id} if document_id else set()
        has_docx_elements = any(
            isinstance(material, dict)
            and str(material.get("document_id", "")) == document_id
            and bool(material.get("elements"))
            for material in state["current_materials"]
        )
        status = item.get("system_over_limit_status")
        if status in {"yes", "no"}:
            statuses.add(str(status))
            document_ids.update(document_ids_for_item)
            evidence_text = item.get("system_over_limit_evidence_text")
            if isinstance(evidence_text, str) and evidence_text.strip():
                evidence_texts.add(evidence_text.strip())
            for element_id in item.get("system_over_limit_evidence_element_ids", []):
                if isinstance(element_id, int) and element_id in allowed_elements:
                    element_ids.add(element_id)
                else:
                    conflict_reasons.append("系统总体状态引用了本次材料范围外的元素")

        for metric in item.get("metrics", []):
            if not isinstance(metric, dict):
                continue
            name = str(metric.get("metric_name") or "未命名指标")
            calculation = calculation_by_id.get((document_id, name), {})
            verification = str(calculation.get("verification_status") or "unverified")
            reported_over = metric.get("is_over_limit") is True
            calculated_over = calculation.get("calculated_is_over_limit") is True
            metric_element_ids = [
                int(element_id)
                for element_id in metric.get("evidence_element_ids", [])
                if isinstance(element_id, int)
            ]
            references_valid = set(metric_element_ids) <= allowed_elements
            # 独立截图没有DOCX元素编号，以当前材料document_id作为证据；DOCX指标则必须
            # 引用合法元素，避免跨任务图片或旧运行元素参与流程分支。
            traceable = (not has_docx_elements and bool(document_id)) or (
                bool(metric_element_ids) and references_valid
            )
            metric_conflict = verification == "conflict" or not references_valid
            metric_over = reported_over or calculated_over
            if not metric_over and not metric_conflict:
                continue

            any_metric_over_limit = any_metric_over_limit or metric_over
            if metric_conflict:
                display_status = "conflict"
            elif verification == "verified" and calculated_over:
                display_status = "verified"
                reliable_metric_over_limit = True
            elif reported_over and traceable:
                display_status = "reported"
                reliable_metric_over_limit = True
            else:
                display_status = "unverified"

            if metric_over:
                triggered_items.add(name)
                document_ids.update(document_ids_for_item)
                element_ids.update(metric_element_ids if references_valid else [])
            detected_metrics.append(
                ScreeningMetric(
                    metric_name=name,
                    actual_value=(
                        str(metric.get("actual_value"))
                        if metric.get("actual_value") is not None
                        else None
                    ),
                    benchmark_upper_value=(
                        str(metric.get("benchmark_upper_value"))
                        if metric.get("benchmark_upper_value") is not None
                        else None
                    ),
                    over_limit_rate_percent=(
                        str(metric.get("reported_over_limit_rate_percent"))
                        if metric.get("reported_over_limit_rate_percent") is not None
                        else None
                    ),
                    evidence_text=(
                        str(metric.get("evidence_text"))
                        if metric.get("evidence_text") is not None
                        else None
                    ),
                    evidence_element_ids=metric_element_ids if references_valid else [],
                    verification_status=display_status,
                )
            )

    explicit_status_conflict = statuses == {"yes", "no"}
    explicit_no_metric_conflict = statuses == {"no"} and any_metric_over_limit
    if explicit_status_conflict:
        conflict_reasons.append("本次材料同时出现系统超标和未超标状态")
    if explicit_no_metric_conflict:
        conflict_reasons.append("系统总体状态显示未超标，但单项指标显示存在超标")

    has_reliable_metric_over_limit = (
        reliable_metric_over_limit
        and not explicit_status_conflict
        and not explicit_no_metric_conflict
    )
    has_explicit_over_limit = statuses == {"yes"} and not conflict_reasons
    if has_reliable_metric_over_limit or has_explicit_over_limit:
        status = "yes"
    elif statuses == {"no"} and not any_metric_over_limit and not conflict_reasons:
        status = "no"
    else:
        status = "unknown"
        if not statuses and not any_metric_over_limit:
            conflict_reasons.append("本次材料没有可确认的总体状态或单项超标指标")
        elif any_metric_over_limit and not reliable_metric_over_limit:
            conflict_reasons.append("识别到可能超标的指标，但证据引用或数值校验不足")

    decision = ScreeningDecision(
        status=status,
        source="system_material",
        triggered_items=sorted(triggered_items),
        detected_metrics=detected_metrics,
        evidence_document_ids=sorted(item for item in document_ids if item),
        evidence_element_ids=sorted(element_ids),
        evidence_texts=sorted(evidence_texts),
        conflict_reasons=list(dict.fromkeys(conflict_reasons)),
    )
    message = {
        "yes": f"识别到{len(triggered_items)}项可靠超标指标，继续历史检索和原因稽核",
        "no": "系统明确显示未超标，本次流程不生成稽核报告",
        "unknown": "系统是否超标无法可靠确认，流程等待人工选择",
    }[decision.status]
    return {
        "screening": decision.model_dump(mode="json"),
        "events": [{"node": "decide_over_limit", "message": message}],
    }


def route_after_over_limit_decision(state: AuditAgentState) -> str:
    """把严格三态映射到LangGraph分支，unknown只能进入人工中断。"""
    return str(state["screening"]["status"])

from __future__ import annotations

"""系统状态不明确时的LangGraph人工中断节点。"""

from typing import Any

from langgraph.types import interrupt

from app.agents.audit.screening import OverLimitResumeInput, ScreeningDecision
from app.agents.audit.state import AuditAgentState


def confirm_over_limit(state: AuditAgentState) -> dict[str, Any]:
    """暂停流程并等待人工确认，恢复后只接受“超标/未超标”二选一。

    LangGraph恢复时会从该节点开头重新执行，因此中断前不写数据库、不调用模型，保证
    重复恢复不会产生重复副作用。真正的运行状态由外层服务在收到中断事件后持久化。
    """
    screening = ScreeningDecision.model_validate(state["screening"])
    response = interrupt(
        {
            "type": "confirm_system_over_limit",
            "message": "材料中无法可靠确认三费系统是否超标，请人工确认后继续。",
            "screening": screening.model_dump(mode="json"),
            "choices": ["confirm_over_limit", "confirm_not_over_limit"],
        }
    )
    resume_input = OverLimitResumeInput.model_validate(response)
    status = "yes" if resume_input.decision == "confirm_over_limit" else "no"
    evidence_texts = list(screening.evidence_texts)
    if resume_input.note and resume_input.note.strip():
        evidence_texts.append(f"人工确认说明：{resume_input.note.strip()}")
    confirmed = screening.model_copy(
        update={
            "status": status,
            "source": "human_confirmation",
            "evidence_texts": evidence_texts,
        }
    )
    return {
        "screening": confirmed.model_dump(mode="json"),
        "events": [
            {
                "node": "confirm_over_limit",
                "message": (
                    "人工已确认系统超标，继续稽核"
                    if status == "yes"
                    else "人工已确认系统未超标，流程结束"
                ),
            }
        ],
    }

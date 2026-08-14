from __future__ import annotations

from app.agents.audit.state import AuditAgentState


def validate_input(state: AuditAgentState) -> dict:
    """确认任务、城市和当前材料完整，阻止无材料的Agent运行。"""
    if not state["task_id"] or not state["city_code"]:
        raise ValueError("Agent缺少任务或城市上下文")
    if not state["current_materials"]:
        raise ValueError("Agent缺少本次稽核报告或截图")
    return {
        "events": [
            {
                "node": "validate_input",
                "message": "任务、报账点、城市和本次材料校验完成",
            }
        ]
    }

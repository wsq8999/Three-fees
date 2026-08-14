from __future__ import annotations

"""LangGraph确定性指标复算节点。"""

from app.agents.audit.calculations import calculate_metrics
from app.agents.audit.state import AuditAgentState


def validate_metrics(state: AuditAgentState) -> dict:
    """在原因判断前校验截图标杆结果，并把冲突显式交给人工审核。

    该节点不生成三费系统标杆值；它只对本次材料已经显示的实际值、标杆上限、
    超标率和结论做确定性校验。不适用项单独计数，避免被误报为输入不足。
    """
    summary = calculate_metrics(state["facts"])
    return {
        "calculations": summary.model_dump(mode="json"),
        "events": [
            {
                "node": "validate_metrics",
                "message": (
                    f"指标校验完成：{summary.verified_count}项通过、"
                    f"{summary.unverified_count}项输入不足、"
                    f"{summary.not_applicable_count}项不适用、"
                    f"{summary.conflict_count}项冲突"
                ),
            }
        ],
    }

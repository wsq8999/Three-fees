from __future__ import annotations

"""系统未超标时的短路结束节点。"""

from typing import Any

from app.agents.audit.state import AuditAgentState


def finish_without_audit(state: AuditAgentState) -> dict[str, Any]:
    """在未超标时结束，不检索记忆、不调用原因模型，也不生成待审核报告。"""
    return {
        "judgment": {
            "status": "not_over_limit",
            "message": "三费系统未显示超标，本次无需进入稽核报告流程。",
        },
        "events": [
            {
                "node": "finish_without_audit",
                "message": "系统未超标，已跳过历史检索、原因判断和报告生成",
            }
        ],
    }

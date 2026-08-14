from __future__ import annotations

from app.agents.audit.state import AuditAgentState


def parse_documents(state: AuditAgentState) -> dict:
    """确认工作流已收到受控的正文、表格和图片引用。"""
    element_count = sum(
        int(material.get("element_count", 0)) for material in state["current_materials"]
    )
    return {
        "events": [
            {
                "node": "parse_documents",
                "message": (
                    f"已加载 {len(state['current_materials'])} 份本次材料、"
                    f"{element_count} 个有序文档元素和 "
                    f"{len(state['history_candidates'])} 份历史候选报告"
                ),
            }
        ]
    }

from __future__ import annotations

from app.agents.audit.state import AuditAgentState


def retrieve_evidence(state: AuditAgentState) -> dict:
    """采用运行开始前固定的城市证据快照，不在节点内直接查询数据库。"""
    evidence = state["history_candidates"]
    scope = state["retrieval_summary"]["scope"]
    return {
        "evidence": evidence,
        "events": [
            {
                "node": "retrieve_evidence",
                "message": f"历史证据检索完成，范围 {scope}，命中 {len(evidence)} 份报告",
            }
        ],
    }

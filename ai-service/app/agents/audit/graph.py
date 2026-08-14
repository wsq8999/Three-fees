from __future__ import annotations

"""电费稽核LangGraph拓扑，只定义节点和执行顺序。"""

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph

from app.agents.audit.context import AuditAgentContext
from app.agents.audit.nodes.confirm_over_limit import confirm_over_limit
from app.agents.audit.nodes.decide_over_limit import (
    decide_over_limit,
    route_after_over_limit_decision,
)
from app.agents.audit.nodes.draft_report import draft_report
from app.agents.audit.nodes.extract_facts import extract_facts
from app.agents.audit.nodes.finish_without_audit import finish_without_audit
from app.agents.audit.nodes.judge_reason import judge_reason
from app.agents.audit.nodes.parse_documents import parse_documents
from app.agents.audit.nodes.retrieve_evidence import retrieve_evidence
from app.agents.audit.nodes.validate_input import validate_input
from app.agents.audit.nodes.validate_metrics import validate_metrics
from app.agents.audit.state import AuditAgentState


def build_audit_graph(checkpointer: BaseCheckpointSaver | None = None):
    """构建带条件分支和人工中断的可恢复稽核工作流。

    生产环境注入PostgreSQL检查点；单元测试可注入内存检查点，既保持拓扑一致，也避免
    测试依赖外部数据库。默认内存实现仅用于本地测试，不承担跨进程恢复。
    """
    builder = StateGraph(AuditAgentState, context_schema=AuditAgentContext)
    builder.add_node("validate_input", validate_input)
    builder.add_node("parse_documents", parse_documents)
    builder.add_node("extract_facts", extract_facts)
    builder.add_node("validate_metrics", validate_metrics)
    builder.add_node("decide_over_limit", decide_over_limit)
    builder.add_node("confirm_over_limit", confirm_over_limit)
    builder.add_node("finish_without_audit", finish_without_audit)
    builder.add_node("retrieve_evidence", retrieve_evidence)
    builder.add_node("judge_reason", judge_reason)
    builder.add_node("draft_report", draft_report)

    builder.add_edge(START, "validate_input")
    builder.add_edge("validate_input", "parse_documents")
    builder.add_edge("parse_documents", "extract_facts")
    builder.add_edge("extract_facts", "validate_metrics")
    builder.add_edge("validate_metrics", "decide_over_limit")
    builder.add_conditional_edges(
        "decide_over_limit",
        route_after_over_limit_decision,
        {
            "yes": "retrieve_evidence",
            "no": "finish_without_audit",
            "unknown": "confirm_over_limit",
        },
    )
    builder.add_conditional_edges(
        "confirm_over_limit",
        route_after_over_limit_decision,
        {"yes": "retrieve_evidence", "no": "finish_without_audit"},
    )
    builder.add_edge("finish_without_audit", END)
    builder.add_edge("retrieve_evidence", "judge_reason")
    builder.add_edge("judge_reason", "draft_report")
    builder.add_edge("draft_report", END)
    return builder.compile(checkpointer=checkpointer or InMemorySaver())


audit_graph = build_audit_graph()

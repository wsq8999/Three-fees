from __future__ import annotations

"""LangGraph节点共享的轻量状态定义。"""

from operator import add
from typing import Annotated, Any, TypedDict


class AuditAgentState(TypedDict):
    """材料只保存资源引用，禁止把图片和文档二进制写入状态。"""

    task_id: str
    city_code: str
    material_refs: list[str]
    task_context: dict[str, Any]
    current_materials: list[dict[str, Any]]
    history_candidates: list[dict[str, Any]]
    # 仅包含当前城市、当前报账点且经过人工确认的纠错记忆。
    correction_memories: list[dict[str, Any]]
    retrieval_summary: dict[str, Any]
    facts: dict[str, Any]
    # 程序复算结果与模型提取事实分开保存，报告可以同时展示原值和校验结论。
    calculations: dict[str, Any]
    # 稽核前置筛查采用yes/no/unknown三态；unknown必须通过LangGraph中断由人工确认。
    screening: dict[str, Any]
    evidence: list[dict[str, Any]]
    judgment: dict[str, Any]
    report_draft: dict[str, Any]
    events: Annotated[list[dict[str, Any]], add]

from __future__ import annotations

"""使用Kimi对本次事实和当前城市历史证据进行RAG原因判断。"""

import base64
import json
import logging
from typing import Any

from langgraph.runtime import Runtime
from pydantic import ValidationError

from app.agents.audit.context import AuditAgentContext
from app.agents.audit.judgment import ReasonJudgment
from app.agents.audit.prompt_loader import load_prompt
from app.agents.audit.state import AuditAgentState
from app.integrations.ai.base import AIProviderError
from app.modules.documents.runtime_reader import MaterialReadError

logger = logging.getLogger(__name__)

# 人工纠错的来源运行和旧元素ID属于审计追溯信息，不是以后任务可以直接引用的证据。
# 这里使用显式白名单生成模型输入，避免数据库将来新增字段后又无意间泄漏给模型。
CORRECTION_MEMORY_MODEL_FIELDS = (
    "memory_id",
    "corrected_reason",
    "reason_category",
    "applicability_conditions",
    "interpretation_summary",
    "confidence",
)


def _history_content(
    evidence: list[dict[str, Any]], runtime: Runtime[AuditAgentContext]
) -> list[dict[str, Any]]:
    """把结构化案例和补充原文证据按文档组织，并在需要时附历史图片。

    结构化案例是主要检索内容；少量原文元素用于补充上下文和核对。图片通过运行时
    白名单读取，节点无法凭空猜测元素ID去读取其他城市或其他任务的图片。
    """
    content: list[dict[str, Any]] = []
    for item in evidence:
        # elements可能包含较长正文和图片引用，先从元数据中剥离，避免JSON重复发送。
        compact = {key: value for key, value in item.items() if key != "elements"}
        content.append(
            {
                "type": "text",
                "text": "历史报告元数据/案例：\n" + json.dumps(compact, ensure_ascii=False),
            }
        )
        for element in item.get("elements", []):
            # 每段证据前写入文档ID和元素ID，使模型返回值可以反向校验来源。
            label = (
                f"[历史文档ID={item['document_id']}；元素ID={element['element_id']}；"
                f"类型={element['element_type']}]"
            )
            content.append({"type": "text", "text": label})
            if element.get("element_type") == "image":
                # 状态中只保存asset_url；图片字节仅在此调用期间进入内存。
                try:
                    payload = runtime.context.material_reader.read_element(
                        int(element["element_id"])
                    )
                except MaterialReadError as exc:
                    raise AIProviderError("history_image_read_failed", str(exc)) from exc
                encoded = base64.b64encode(payload.content).decode("ascii")
                content.append(
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:{payload.media_type};base64,{encoded}"},
                    }
                )
            elif element.get("content_text"):
                content.append({"type": "text", "text": str(element["content_text"])})
    return content


def _correction_memories_for_model(
    correction_memories: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """只把可复用的业务经验交给模型，旧报告元素ID仍留在数据库供追溯。

    ``supporting_element_ids`` 指向形成纠错的那次旧报告。以后即使上传同一个Word，
    新解析也会产生新的元素ID；把旧ID交给模型会诱导它误填为本次历史证据。因此这里
    不修改原状态和数据库，只为单次模型调用构造最小业务视图。
    """
    return [
        {field: item.get(field) for field in CORRECTION_MEMORY_MODEL_FIELDS}
        for item in correction_memories
        if isinstance(item, dict)
    ]


def _parse_judgment(raw: dict[str, Any]) -> ReasonJudgment:
    """把供应商结果转换成严格原因判断契约，不合规则拒绝进入Agent状态。"""
    try:
        return ReasonJudgment.model_validate(raw)
    except ValidationError as exc:
        raise AIProviderError("ai_output_schema_invalid", "Kimi原因判断结果字段不合规") from exc


def _validate_judgment_references(
    judgment: ReasonJudgment,
    *,
    allowed_documents: set[str],
    allowed_cases: set[str],
    allowed_corrections: set[str],
    allowed_current_elements: set[int],
    allowed_history_elements: set[int],
) -> None:
    """校验模型动态引用白名单，防止跨城市、跨材料或旧运行证据混入结论。"""
    if not set(judgment.matched_history_document_ids) <= allowed_documents:
        raise AIProviderError("history_reference_invalid", "原因判断引用了未检索的历史报告")
    if not set(judgment.matched_case_ids) <= allowed_cases:
        raise AIProviderError("case_reference_invalid", "原因判断引用了未检索的历史案例")
    if not set(judgment.matched_correction_memory_ids) <= allowed_corrections:
        raise AIProviderError("correction_reference_invalid", "原因判断引用了未检索的纠错记忆")
    if judgment.correction_memory_applied != bool(judgment.matched_correction_memory_ids):
        raise AIProviderError("correction_application_invalid", "纠错记忆采用状态与引用列表不一致")
    if not set(judgment.supporting_current_element_ids) <= allowed_current_elements:
        raise AIProviderError("current_evidence_invalid", "原因判断引用了本次材料范围外的证据")
    if not set(judgment.supporting_history_element_ids) <= allowed_history_elements:
        raise AIProviderError("history_evidence_invalid", "原因判断引用了未检索的历史证据元素")


def _dynamic_reference_instruction(
    *,
    allowed_documents: set[str],
    allowed_cases: set[str],
    allowed_corrections: set[str],
    allowed_current_elements: set[int],
    allowed_history_elements: set[int],
) -> str:
    """生成一次运行专属的引用白名单，JSON Schema无法表达这种动态约束。"""
    return (
        "本次运行动态引用白名单（各引用字段只能从对应列表选择）：\n"
        f"matched_history_document_ids={sorted(allowed_documents)}\n"
        f"matched_case_ids={sorted(allowed_cases)}\n"
        f"matched_correction_memory_ids={sorted(allowed_corrections)}\n"
        f"supporting_current_element_ids={sorted(allowed_current_elements)}\n"
        f"supporting_history_element_ids={sorted(allowed_history_elements)}\n"
        "纠错记忆只通过matched_correction_memory_ids引用；它的来源运行和旧元素不得作为"
        "本次历史证据。列表为空时对应输出必须是空数组。"
    )


def judge_reason(state: AuditAgentState, runtime: Runtime[AuditAgentContext]) -> dict[str, Any]:
    """检索结果增强Kimi输入，输出带当前与历史证据ID的原因判断。

    这里体现完整RAG：``state['evidence']`` 是检索结果(Retrieval)，与本次事实共同组成
    增强输入(Augmentation)，Kimi产生原因判断(Generation)。节点不访问数据库，保证
    一次运行从开始到结束使用同一份历史证据快照。
    """
    evidence = state["evidence"]
    correction_memories = state["correction_memories"]
    model_correction_memories = _correction_memories_for_model(correction_memories)
    # 动态白名单必须在调用模型前计算并明确告知；之后仍由本地代码做最终安全校验。
    allowed_documents = {str(item["document_id"]) for item in evidence}
    allowed_cases = {
        str(item["audit_case"]["case_id"])
        for item in evidence
        if isinstance(item.get("audit_case"), dict)
    }
    allowed_corrections = {
        str(item["memory_id"])
        for item in correction_memories
        if isinstance(item, dict) and item.get("memory_id")
    }
    allowed_current_elements = {
        int(element["element_id"])
        for material in state["current_materials"]
        if isinstance(material, dict) and isinstance(material.get("elements"), list)
        for element in material["elements"]
        if isinstance(element, dict) and isinstance(element.get("element_id"), int)
    }
    allowed_history_elements = {
        int(element["element_id"])
        for item in evidence
        if isinstance(item, dict)
        for element in item.get("elements", [])
        if isinstance(element, dict) and isinstance(element.get("element_id"), int)
    }
    allowed_history_elements.update(
        int(element_id)
        for item in evidence
        if isinstance(item, dict) and isinstance(item.get("audit_case"), dict)
        for element_id in item["audit_case"].get("evidence_element_ids", [])
        if isinstance(element_id, int)
    )
    reference_instruction = _dynamic_reference_instruction(
        allowed_documents=allowed_documents,
        allowed_cases=allowed_cases,
        allowed_corrections=allowed_corrections,
        allowed_current_elements=allowed_current_elements,
        allowed_history_elements=allowed_history_elements,
    )
    # 本次事实放在最前面，提示词要求模型先证明本次原因，再与历史案例比较。
    content: list[dict[str, Any]] = [
        {
            "type": "text",
            "text": (
                "任务上下文：\n"
                + json.dumps(state["task_context"], ensure_ascii=False)
                + "\n本次报告结构化事实：\n"
                + json.dumps(state["facts"], ensure_ascii=False)
                + "\n程序复算与一致性校验：\n"
                + json.dumps(state["calculations"], ensure_ascii=False)
                + "\n当前报账点已经人工确认的纠错记忆：\n"
                + json.dumps(model_correction_memories, ensure_ascii=False)
                + "\n"
                + reference_instruction
            ),
        },
        *_history_content(evidence, runtime),
    ]
    # 原因判断比事实提取需要更强推理，推理强度由独立配置项控制。
    raw = runtime.context.ai_provider.generate_structured(
        system_prompt=load_prompt("judge_reason_v1.md"),
        user_content=content,
        schema_name="electricity_audit_reason_judgment",
        json_schema=ReasonJudgment.model_json_schema(),
        reasoning_effort=runtime.context.judge_reasoning_effort,
    )
    # JSON Schema是供应商约束，Pydantic和动态引用白名单是进入业务状态前的本地防线。
    judgment = _parse_judgment(raw)
    try:
        _validate_judgment_references(
            judgment,
            allowed_documents=allowed_documents,
            allowed_cases=allowed_cases,
            allowed_corrections=allowed_corrections,
            allowed_current_elements=allowed_current_elements,
            allowed_history_elements=allowed_history_elements,
        )
    except AIProviderError as first_error:
        # 动态ID无法写死在供应商JSON Schema中。模型偶发引用越界时只重试一次，既避免
        # 直接让长流程失败，也不通过静默删除ID掩盖证据问题；供应商调用错误不在此重试。
        logger.warning(
            "Kimi judgment reference validation failed; retrying once: run_id=%s code=%s",
            runtime.context.run_id,
            first_error.code,
        )
        repaired_raw = runtime.context.ai_provider.generate_structured(
            system_prompt=load_prompt("judge_reason_v1.md"),
            user_content=[
                *content,
                {
                    "type": "text",
                    "text": (
                        f"上一次结果未通过证据范围校验：{first_error}。"
                        "请重新生成完整JSON，严格使用以下白名单，不得猜测或复用旧运行元素。\n"
                        + reference_instruction
                    ),
                },
            ],
            schema_name="electricity_audit_reason_judgment",
            json_schema=ReasonJudgment.model_json_schema(),
            reasoning_effort=runtime.context.judge_reasoning_effort,
        )
        judgment = _parse_judgment(repaired_raw)
        _validate_judgment_references(
            judgment,
            allowed_documents=allowed_documents,
            allowed_cases=allowed_cases,
            allowed_corrections=allowed_corrections,
            allowed_current_elements=allowed_current_elements,
            allowed_history_elements=allowed_history_elements,
        )
    # message是前端通用展示字段；保留reasoning_summary作为正式契约字段。
    result = judgment.model_dump(mode="json")
    result["message"] = judgment.reasoning_summary
    return {
        "judgment": result,
        "events": [
            {
                "node": "judge_reason",
                "message": (
                    f"Kimi已结合 {len(correction_memories)} 条确认纠错和 "
                    f"{len(evidence)} 份本市历史证据完成RAG原因判断"
                ),
            }
        ],
    }

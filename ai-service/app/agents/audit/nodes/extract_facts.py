from __future__ import annotations

"""调用Kimi联合提取本次DOCX或辅助截图中的可核对事实。"""

import base64
import logging
from typing import Any

from langgraph.runtime import Runtime
from pydantic import ValidationError

from app.agents.audit.context import AuditAgentContext
from app.agents.audit.facts import ReportFacts, ScreenshotFacts
from app.agents.audit.prompt_loader import load_prompt
from app.agents.audit.state import AuditAgentState
from app.integrations.ai.base import AIProviderError
from app.modules.documents.runtime_reader import MaterialReadError

MAX_REPORT_TEXT_CHARS = 120_000
MAX_REPORT_IMAGES = 30
logger = logging.getLogger(__name__)


def _validation_locations(exc: ValidationError) -> str:
    """只返回字段位置，不包含模型原文或业务数据。"""
    locations = [".".join(str(part) for part in item["loc"]) for item in exc.errors()]
    return "、".join(locations[:5]) or "未知字段"


def _data_url(media_type: str, content: bytes) -> str:
    """只在模型调用期间把受控图片转换为内存Data URL。"""
    if not media_type.startswith("image/"):
        raise AIProviderError("unsupported_vision_material", "模型视觉识别只接受图片内容")
    encoded = base64.b64encode(content).decode("ascii")
    return f"data:{media_type};base64,{encoded}"


def _extract_screenshot(
    state: AuditAgentState,
    material: dict[str, Any],
    runtime: Runtime[AuditAgentContext],
) -> dict[str, Any]:
    """保留独立截图识别能力，作为开发排查和特殊材料的辅助入口。"""
    document_id = str(material["document_id"])
    try:
        payload = runtime.context.material_reader.read(document_id)
    except MaterialReadError as exc:
        raise AIProviderError("material_read_failed", str(exc)) from exc
    user_text = (
        f"当前任务报账点：{state['task_context']['site_name']}\n"
        f"任务标题：{state['task_context']['title']}\n"
        "请识别所附本次电费超标截图中的可见事实。"
    )
    raw_result = runtime.context.ai_provider.generate_structured(
        system_prompt=load_prompt("extract_facts_v1.md"),
        user_content=[
            {
                "type": "image_url",
                "image_url": {"url": _data_url(payload.media_type, payload.content)},
            },
            {"type": "text", "text": user_text},
        ],
        schema_name="electricity_audit_screenshot_facts",
        json_schema=ScreenshotFacts.model_json_schema(),
        reasoning_effort=runtime.context.extract_reasoning_effort,
    )
    try:
        validated = ScreenshotFacts.model_validate(raw_result)
    except ValidationError as exc:
        logger.warning(
            "Kimi screenshot schema validation failed: %s",
            exc.errors(include_input=False),
        )
        raise AIProviderError(
            "ai_output_schema_invalid",
            f"Kimi截图识别结果字段不合规：{_validation_locations(exc)}",
        ) from exc
    return {
        "document_id": document_id,
        "document_type": "evidence_screenshot",
        "element_count": 0,
        "text_element_count": 0,
        "image_element_count": 1,
        **validated.model_dump(mode="json"),
    }


def _report_user_content(
    state: AuditAgentState,
    material: dict[str, Any],
    runtime: Runtime[AuditAgentContext],
) -> list[dict[str, Any]]:
    """按DOCX原始顺序构造文字、表格和图片交错的多模态消息。"""
    elements = material.get("elements")
    if not isinstance(elements, list) or not elements:
        raise AIProviderError("current_report_has_no_elements", "本次DOCX没有可分析的解析元素")

    text_chars = sum(
        len(str(element.get("content_text") or ""))
        for element in elements
        if isinstance(element, dict)
    )
    image_count = sum(
        element.get("element_type") == "image" for element in elements if isinstance(element, dict)
    )
    # 当前版本不静默截断业务材料；超过安全边界时明确失败，后续再引入分块归并。
    if text_chars > MAX_REPORT_TEXT_CHARS or image_count > MAX_REPORT_IMAGES:
        raise AIProviderError(
            "current_report_too_large",
            "本次DOCX内容超过单次完整分析范围，请拆分报告后重试",
        )

    content: list[dict[str, Any]] = [
        {
            "type": "text",
            "text": (
                f"当前任务报账点：{state['task_context']['site_name']}\n"
                f"任务标题：{state['task_context']['title']}\n"
                "以下内容按DOCX原始顺序提供。请联合分析全部标题、正文、表格和图片。"
            ),
        }
    ]
    for element in elements:
        if not isinstance(element, dict):
            continue
        element_id = int(element["element_id"])
        element_type = str(element["element_type"])
        section = str(element.get("section_title") or "未标注章节")
        if element_type == "image":
            try:
                payload = runtime.context.material_reader.read_element(element_id)
            except MaterialReadError as exc:
                raise AIProviderError("material_read_failed", str(exc)) from exc
            content.extend(
                [
                    {
                        "type": "text",
                        "text": f"[元素ID={element_id}；类型=图片；章节={section}]",
                    },
                    {
                        "type": "image_url",
                        "image_url": {"url": _data_url(payload.media_type, payload.content)},
                    },
                ]
            )
        else:
            content.append(
                {
                    "type": "text",
                    "text": (
                        f"[元素ID={element_id}；类型={element_type}；章节={section}]\n"
                        f"{element.get('content_text') or ''}"
                    ),
                }
            )
    return content


def _extract_report(
    state: AuditAgentState,
    material: dict[str, Any],
    runtime: Runtime[AuditAgentContext],
) -> dict[str, Any]:
    """用一次受Schema约束的多模态调用联合分析整份DOCX。"""
    raw_result = runtime.context.ai_provider.generate_structured(
        system_prompt=load_prompt("extract_report_facts_v1.md"),
        user_content=_report_user_content(state, material, runtime),
        schema_name="electricity_audit_report_facts",
        json_schema=ReportFacts.model_json_schema(),
        reasoning_effort=runtime.context.extract_reasoning_effort,
    )
    try:
        validated = ReportFacts.model_validate(raw_result)
    except ValidationError as exc:
        logger.warning(
            "Kimi report schema validation failed: %s",
            exc.errors(include_input=False),
        )
        raise AIProviderError(
            "ai_output_schema_invalid",
            f"Kimi整份报告识别结果字段不合规：{_validation_locations(exc)}",
        ) from exc
    return {
        "document_id": str(material["document_id"]),
        "document_type": "current_report",
        "element_count": int(material.get("element_count", 0)),
        "text_element_count": int(material.get("text_element_count", 0)),
        "image_element_count": int(material.get("image_element_count", 0)),
        **validated.model_dump(mode="json"),
    }


def extract_facts(
    state: AuditAgentState,
    runtime: Runtime[AuditAgentContext],
) -> dict[str, Any]:
    """按材料模式提取事实，并在写入LangGraph状态前执行Pydantic二次校验。"""
    document_results: list[dict[str, Any]] = []
    for material in state["current_materials"]:
        if material.get("document_type") == "current_report":
            document_results.append(_extract_report(state, material, runtime))
        else:
            document_results.append(_extract_screenshot(state, material, runtime))

    total_elements = sum(item["element_count"] for item in document_results)
    return {
        "facts": {
            "status": "completed",
            "provider": "kimi",
            "model": runtime.context.ai_provider.model_name,
            "documents": document_results,
        },
        "events": [
            {
                "node": "extract_facts",
                "message": (
                    f"Kimi已联合分析 {len(document_results)} 份本次材料中的 "
                    f"{total_elements} 个文档元素"
                ),
            }
        ],
    }

from __future__ import annotations

import html
import json
import re
from typing import Any

from app.core.config import get_settings
from app.integrations.ai.base import AIProviderError
from app.integrations.ai.factory import get_ai_provider
from app.modules.report_image_analysis.schemas import (
    ReportImageAnalysisRequest,
    ReportImageAnalysisResponse,
)

SECTION_ANALYSIS = "\u4e8c\u3001\u6392\u67e5\u5206\u6790"
SECTION_RECTIFICATION = "\u4e09\u3001\u6574\u6539\u5c0f\u7ed3"

ANALYSIS_SECTION_PATTERN = re.compile(
    rf"(?is)(<h2[^>]*>\s*{SECTION_ANALYSIS}\s*</h2>)(.*?)(<h2[^>]*>\s*{SECTION_RECTIFICATION}\s*</h2>)"
)


def analyze_report_images(payload: ReportImageAnalysisRequest) -> ReportImageAnalysisResponse:
    settings = get_settings()
    if settings.ai_provider == "fake":
        result = _fake_analysis(payload)
    else:
        result = _kimi_analysis(payload)
    return ReportImageAnalysisResponse(
        metadata=payload.metadata,
        answer=result["answer"],
        analysis_text=result["analysis_text"],
        updated_content_html=result["updated_content_html"],
    )


def _fake_analysis(payload: ReportImageAnalysisRequest) -> dict[str, str]:
    names = "\u3001".join(image.file_name for image in payload.images)
    text = (
        "\u5df2\u6839\u636e\u73b0\u573a\u56fe\u7247\u5f62\u6210\u521d\u6b65\u6392\u67e5\u5206\u6790\u3002"
        f"\u672c\u6b21\u5171\u5206\u6790 {len(payload.images)} \u5f20\u56fe\u7247\uff1a{names}\u3002"
        "\u8bf7\u7ed3\u5408\u73b0\u573a\u5b9e\u9645\u60c5\u51b5\u590d\u6838\u540e\u518d\u751f\u6210\u6b63\u5f0f\u62a5\u544a\u3002"
    )
    return {
        "answer": "\u56fe\u7247\u5206\u6790\u5df2\u5b8c\u6210\uff0c\u7ed3\u679c\u5df2\u8865\u5145\u5230\u201c\u4e8c\u3001\u6392\u67e5\u5206\u6790\u201d\u3002",
        "analysis_text": text,
        "updated_content_html": _append_analysis(payload.content_html, text),
    }


def _kimi_analysis(payload: ReportImageAnalysisRequest) -> dict[str, str]:
    provider = get_ai_provider()
    schema: dict[str, Any] = {
        "type": "object",
        "additionalProperties": False,
        "required": ["answer", "analysis_text", "updated_content_html"],
        "properties": {
            "answer": {"type": "string"},
            "analysis_text": {"type": "string"},
            "updated_content_html": {"type": "string"},
        },
    }
    result = provider.generate_structured(
        system_prompt=_system_prompt(),
        user_content=_user_content(payload),
        schema_name="report_image_analysis_result",
        json_schema=schema,
        reasoning_effort="low",
    )
    answer = str(result.get("answer") or "").strip()
    analysis_text = str(result.get("analysis_text") or "").strip()
    updated_html = str(result.get("updated_content_html") or "").strip()
    if not answer:
        answer = "\u56fe\u7247\u5206\u6790\u5df2\u5b8c\u6210\u3002"
    if not analysis_text:
        analysis_text = answer
    if not updated_html:
        updated_html = _append_analysis(payload.content_html, analysis_text)
    return {
        "answer": answer,
        "analysis_text": analysis_text,
        "updated_content_html": updated_html,
    }


def _system_prompt() -> str:
    return (
        "\u4f60\u662f\u6c5f\u82cf\u7535\u8d39\u7a3d\u6838\u62a5\u544a\u52a9\u624b\u3002"
        "\u4f60\u53ea\u80fd\u57fa\u4e8e\u7528\u6237\u63d0\u4f9b\u7684\u4e1a\u52a1\u4e8b\u5b9e\u3001\u5f53\u524d\u62a5\u544a\u6b63\u6587\u548c\u56fe\u7247\u5185\u5bb9"
        "\u8865\u5145\u6392\u67e5\u5206\u6790\uff0c\u4e0d\u5f97\u7f16\u9020\u672a\u63d0\u4f9b\u7684\u6570\u636e\u3002"
        "\u8fd4\u56de\u5fc5\u987b\u662f JSON \u5bf9\u8c61\uff0c\u4e0d\u8981 Markdown \u4ee3\u7801\u5757\u3002"
        "updated_content_html \u5fc5\u987b\u4fdd\u7559\u539f\u62a5\u544a\u7684\u6807\u9898\u3001\u4e00\u3001\u60c5\u51b5\u8bf4\u660e\u3001\u4e09\u3001\u6574\u6539\u5c0f\u7ed3\u7b49\u5df2\u6709\u5185\u5bb9\uff0c"
        "\u53ea\u5728\u201c\u4e8c\u3001\u6392\u67e5\u5206\u6790\u201d\u4e2d\u8865\u5145\u6216\u4f18\u5316\u4e0e\u56fe\u7247\u76f8\u5173\u7684\u5206\u6790\u3002"
    )


def _user_content(payload: ReportImageAnalysisRequest) -> list[dict[str, Any]]:
    facts = {fact.field_name: fact.value for fact in payload.facts}
    text_payload = {
        "billing_point_code": payload.billing_point_code,
        "period": payload.period,
        "instruction": payload.instruction,
        "facts": facts,
        "content_html": payload.content_html,
        "expected_output": {
            "answer": "\u7ed9\u7528\u6237\u770b\u7684\u7b80\u77ed\u5904\u7406\u7ed3\u679c",
            "analysis_text": "\u53ef\u76f4\u63a5\u653e\u5165\u4e8c\u3001\u6392\u67e5\u5206\u6790\u7684\u4e2d\u6587\u6bb5\u843d",
            "updated_content_html": "\u66f4\u65b0\u540e\u7684\u5b8c\u6574\u62a5\u544a HTML",
        },
    }
    content: list[dict[str, Any]] = [
        {"type": "text", "text": json.dumps(text_payload, ensure_ascii=False)}
    ]
    content.extend(
        {
            "type": "image_url",
            "image_url": {
                "url": f"data:{image.media_type};base64,{image.base64_data}",
                "detail": "high",
            },
        }
        for image in payload.images
    )
    return content


def _append_analysis(content_html: str, analysis_text: str) -> str:
    if not analysis_text:
        return content_html
    paragraph = "<p>" + html.escape(analysis_text).replace("\n", "<br />") + "</p>"
    match = ANALYSIS_SECTION_PATTERN.search(content_html)
    if match:
        return (
            content_html[: match.start()]
            + match.group(1)
            + match.group(2)
            + paragraph
            + match.group(3)
            + content_html[match.end() :]
        )
    return content_html + paragraph


def normalize_ai_error(error: AIProviderError) -> tuple[int, dict[str, str]]:
    status = 503 if error.code in {"ai_not_configured", "ai_connection_failed"} else 502
    return status, {"code": error.code, "message": str(error)}

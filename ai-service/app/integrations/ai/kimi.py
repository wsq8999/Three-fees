from __future__ import annotations

"""通过LangChain的OpenAI兼容客户端调用Kimi多模态模型。"""

import json
import logging
from typing import Any, Literal

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from openai import (
    APIConnectionError,
    APITimeoutError,
    AuthenticationError,
    BadRequestError,
    RateLimitError,
)

from app.integrations.ai.base import AIProviderError

logger = logging.getLogger(__name__)


class KimiProvider:
    """封装Kimi特有配置，并向业务层只暴露严格结构化生成接口。"""

    def __init__(
        self,
        *,
        api_key: str,
        base_url: str,
        model: str,
        timeout_seconds: float,
    ) -> None:
        if not api_key:
            raise AIProviderError("ai_not_configured", "Kimi API Key尚未配置")
        self._model_name = model
        self._client = ChatOpenAI(
            api_key=api_key,
            base_url=base_url,
            model=model,
            timeout=timeout_seconds,
            # Agent运行本身可显式重试；SDK隐式重试会让页面无法预测等待上限。
            max_retries=0,
        )

    @property
    def model_name(self) -> str:
        """返回写入结果审计信息的实际模型标识。"""
        return self._model_name

    def generate_structured(
        self,
        *,
        system_prompt: str,
        user_content: list[dict[str, Any]],
        schema_name: str,
        json_schema: dict[str, Any],
        reasoning_effort: Literal["low", "high", "max"],
    ) -> dict[str, Any]:
        """优先使用严格Schema；偶发无效JSON时降级到JSON Mode重试一次。

        严格调用仍是默认路径。只有供应商已经成功响应、但最终content无法解析时，
        才使用同一份业务输入和显式Schema说明重试；认证、限流、超时等错误不会被
        隐式重试，避免扩大额度消耗和页面等待时间。无论走哪条路径，业务节点仍会
        使用Pydantic执行本地Schema校验，降级不会放宽字段或证据约束。
        """
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=user_content),
        ]
        try:
            response = self._client.bind(
                reasoning_effort=reasoning_effort,
                response_format={
                    "type": "json_schema",
                    "json_schema": {
                        "name": schema_name,
                        "strict": True,
                        "schema": json_schema,
                    },
                },
            ).invoke(messages)
            content = self._final_text(response.content)
            try:
                parsed = self._parse_json_object(content)
            except (json.JSONDecodeError, TypeError, ValueError):
                metadata = getattr(response, "response_metadata", {})
                logger.warning(
                    "Kimi strict structured output was invalid; retrying with JSON mode "
                    "(schema=%s, content_length=%s, finish_reason=%s)",
                    schema_name,
                    len(content),
                    metadata.get("finish_reason") if isinstance(metadata, dict) else None,
                )
                fallback_prompt = (
                    system_prompt
                    + "\n\n本次响应必须只返回一个可解析的JSON对象，不得使用Markdown代码块。"
                    + "必须严格符合以下JSON Schema，禁止增加字段：\n"
                    + json.dumps(json_schema, ensure_ascii=False, separators=(",", ":"))
                )
                fallback_response = self._client.bind(
                    reasoning_effort=reasoning_effort,
                    response_format={"type": "json_object"},
                ).invoke(
                    [
                        SystemMessage(content=fallback_prompt),
                        HumanMessage(content=user_content),
                    ]
                )
                parsed = self._parse_json_object(self._final_text(fallback_response.content))
        except AIProviderError:
            raise
        except AuthenticationError as exc:
            raise AIProviderError(
                "ai_authentication_failed",
                "Kimi认证失败，请检查API Key与Base URL是否属于同一开放平台",
            ) from exc
        except RateLimitError as exc:
            raise AIProviderError("ai_rate_limited", "Kimi调用频率或账户额度受限") from exc
        except APITimeoutError as exc:
            raise AIProviderError("ai_request_timeout", "Kimi模型调用超时") from exc
        except APIConnectionError as exc:
            raise AIProviderError("ai_connection_failed", "无法连接Kimi API服务") from exc
        except BadRequestError as exc:
            raise AIProviderError("ai_request_invalid", "Kimi不接受当前模型请求参数") from exc
        except (json.JSONDecodeError, TypeError, ValueError) as exc:
            raise AIProviderError(
                "ai_output_invalid_json",
                "Kimi返回内容不是有效的结构化JSON",
            ) from exc
        except Exception as exc:
            # 不把第三方异常正文向上抛出，防止响应体或请求信息进入业务日志。
            raise AIProviderError("ai_request_failed", "Kimi模型调用失败") from exc
        if not isinstance(parsed, dict):
            raise AIProviderError("ai_output_invalid_type", "Kimi结构化结果必须是JSON对象")
        return parsed

    def _parse_json_object(self, content: str) -> Any:
        """解析纯JSON，并兼容供应商偶发包裹整个响应的单层Markdown围栏。

        只允许“完整响应即JSON”或“完整响应被一个代码围栏包裹”两种形式，不从任意
        文本中截取花括号，避免把模型解释文字误当成可信业务结构。
        """
        stripped = content.strip()
        try:
            return json.loads(stripped)
        except json.JSONDecodeError as original_error:
            if not stripped.startswith("```") or not stripped.endswith("```"):
                raise
            first_newline = stripped.find("\n")
            if first_newline < 0:
                raise original_error
            fenced_content = stripped[first_newline + 1 : -3].strip()
            return json.loads(fenced_content)

    def _final_text(self, content: str | list[str | dict[str, Any]]) -> str:
        """兼容LangChain字符串或内容块响应，但忽略额外思考字段。"""
        if isinstance(content, str):
            return content
        text_parts: list[str] = []
        for part in content:
            if isinstance(part, str):
                text_parts.append(part)
            elif isinstance(part, dict) and part.get("type") == "text":
                value = part.get("text")
                if isinstance(value, str):
                    text_parts.append(value)
        if not text_parts:
            raise AIProviderError("ai_output_empty", "Kimi没有返回最终结构化内容")
        return "".join(text_parts)

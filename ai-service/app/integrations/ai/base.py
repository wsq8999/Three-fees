from __future__ import annotations

from typing import Any, Literal, Protocol


class AIProviderError(Exception):
    """不包含密钥和供应商响应正文的安全模型调用异常。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class AIProvider(Protocol):
    """具体公网或私有模型都必须实现的供应商无关接口。"""

    @property
    def model_name(self) -> str: ...

    def generate_structured(
        self,
        *,
        system_prompt: str,
        user_content: list[dict[str, Any]],
        schema_name: str,
        json_schema: dict[str, Any],
        reasoning_effort: Literal["low", "high", "max"],
    ) -> dict[str, Any]: ...

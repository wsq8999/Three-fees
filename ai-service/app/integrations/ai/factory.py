from __future__ import annotations

"""根据环境配置创建模型供应商，业务节点不感知具体SDK。"""

from functools import lru_cache

from app.core.config import get_settings
from app.integrations.ai.base import AIProvider, AIProviderError
from app.integrations.ai.kimi import KimiProvider


@lru_cache
def get_ai_provider() -> AIProvider:
    """创建进程级无状态模型客户端，连接池由底层SDK复用。"""
    settings = get_settings()
    if settings.ai_provider != "kimi":
        raise AIProviderError("ai_provider_unsupported", "当前只配置了Kimi模型供应商")
    return KimiProvider(
        api_key=settings.kimi_api_key.get_secret_value(),
        base_url=settings.kimi_base_url,
        model=settings.kimi_model,
        timeout_seconds=settings.kimi_timeout_seconds,
    )

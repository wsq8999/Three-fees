from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, Protocol

from app.integrations.ai.base import AIProvider
from app.modules.documents.runtime_reader import AssetPayload, MaterialPayload


class MaterialReader(Protocol):
    """节点只依赖材料读取协议，不依赖本地目录或对象存储实现。"""

    def read(self, document_id: str) -> MaterialPayload: ...

    def read_element(self, element_id: int) -> AssetPayload: ...


@dataclass(frozen=True)
class AuditAgentContext:
    """不进入checkpoint的单次运行依赖。"""

    run_id: str
    user_id: str
    ai_provider: AIProvider
    material_reader: MaterialReader
    extract_reasoning_effort: Literal["low", "high", "max"]
    judge_reasoning_effort: Literal["low", "high", "max"]

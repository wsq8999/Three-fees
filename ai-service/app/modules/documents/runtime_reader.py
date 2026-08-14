from __future__ import annotations

"""为单次Agent运行提供受限材料读取，不把存储键放入LangGraph状态。"""

from dataclasses import dataclass

from app.modules.documents.model import DocumentElementModel, SourceDocumentModel
from app.modules.documents.storage import LocalDocumentStorage


class MaterialReadError(Exception):
    """材料不在本次运行白名单或无法读取。"""


@dataclass(frozen=True)
class MaterialPayload:
    """只在节点执行期间存在的原始材料内容。"""

    document_id: str
    media_type: str
    content: bytes


@dataclass(frozen=True)
class AssetPayload:
    """只在节点执行期间存在的DOCX内嵌图片内容。"""

    element_id: int
    media_type: str
    content: bytes


class LocalRuntimeMaterialReader:
    """只允许读取构造时明确授权的材料ID。"""

    def __init__(
        self,
        storage: LocalDocumentStorage,
        documents: list[SourceDocumentModel],
        elements: list[DocumentElementModel] | None = None,
    ) -> None:
        self.storage = storage
        self.allowed = {
            str(document.id): (document.storage_key, document.media_type) for document in documents
        }
        # 只对白名单解析元素开放读取，避免Agent通过猜测ID跨文档访问图片。
        self.allowed_elements = {
            element.id: (element.asset_storage_key, element.media_type)
            for element in elements or []
            if element.asset_storage_key and element.media_type
        }

    def read(self, document_id: str) -> MaterialPayload:
        """读取白名单材料；路径边界继续由存储适配器校验。"""
        metadata = self.allowed.get(document_id)
        if metadata is None:
            raise MaterialReadError("材料不属于本次Agent运行")
        storage_key, media_type = metadata
        try:
            content = self.storage.resolve(storage_key).read_bytes()
        except OSError as exc:
            raise MaterialReadError("本次稽核截图无法读取") from exc
        return MaterialPayload(
            document_id=document_id,
            media_type=media_type,
            content=content,
        )

    def read_element(self, element_id: int) -> AssetPayload:
        """读取本次运行中当前DOCX明确授权的内嵌图片。"""
        metadata = self.allowed_elements.get(element_id)
        if metadata is None:
            raise MaterialReadError("文档图片不属于本次Agent运行")
        storage_key, media_type = metadata
        try:
            content = self.storage.resolve(storage_key).read_bytes()
        except OSError as exc:
            raise MaterialReadError("本次报告中的图片无法读取") from exc
        return AssetPayload(element_id=element_id, media_type=media_type, content=content)

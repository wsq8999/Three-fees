from __future__ import annotations

"""原始材料数据访问层。"""

from uuid import UUID

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.modules.documents.model import SourceDocumentModel


def _parse_uuid(value: str | UUID) -> UUID | None:
    """安全解析外部UUID，非法值由服务层统一表现为资源不存在。"""
    if isinstance(value, UUID):
        return value
    try:
        return UUID(value)
    except ValueError:
        return None


class SourceDocumentRepository:
    """所有材料查询都显式限制城市ID。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, document: SourceDocumentModel) -> SourceDocumentModel:
        """在当前事务中新增材料记录。"""
        self.session.add(document)
        self.session.flush()
        return document

    def get(
        self, document_id: str | UUID, city_id: int, *, include_archived: bool = False
    ) -> SourceDocumentModel | None:
        """获取当前城市材料，默认不返回已归档记录。"""
        normalized_id = _parse_uuid(document_id)
        if normalized_id is None:
            return None
        conditions = [
            SourceDocumentModel.id == normalized_id,
            SourceDocumentModel.city_id == city_id,
        ]
        if not include_archived:
            conditions.append(SourceDocumentModel.status != "archived")
        return self.session.scalar(select(SourceDocumentModel).where(*conditions))

    def find_duplicate(
        self, city_id: int, document_type: str, file_hash: str
    ) -> SourceDocumentModel | None:
        """在同城市同材料类型内识别尚未归档的重复文件。"""
        statement = select(SourceDocumentModel).where(
            SourceDocumentModel.city_id == city_id,
            SourceDocumentModel.document_type == document_type,
            SourceDocumentModel.sha256 == file_hash,
            SourceDocumentModel.status != "archived",
        )
        return self.session.scalar(statement)

    def list_by_ids(self, document_ids: list[UUID], city_id: int) -> list[SourceDocumentModel]:
        """按城市批量读取尚未归档的材料，供分析运行校验输入引用。"""
        if not document_ids:
            return []
        statement = select(SourceDocumentModel).where(
            SourceDocumentModel.id.in_(document_ids),
            SourceDocumentModel.city_id == city_id,
            SourceDocumentModel.status != "archived",
        )
        return list(self.session.scalars(statement))

    def list_parsed_history(self, city_id: int, limit: int = 100) -> list[SourceDocumentModel]:
        """返回当前城市可参与证据检索的已解析历史报告。"""
        statement = (
            select(SourceDocumentModel)
            .where(
                SourceDocumentModel.city_id == city_id,
                SourceDocumentModel.document_type == "historical_report",
                SourceDocumentModel.status == "parsed",
            )
            .order_by(SourceDocumentModel.updated_at.desc(), SourceDocumentModel.id.desc())
            .limit(limit)
        )
        return list(self.session.scalars(statement))

    def list_page(
        self,
        *,
        city_id: int,
        document_type: str | None,
        status: str | None,
        site_id: UUID | None,
        keyword: str | None,
        page: int,
        page_size: int,
    ) -> tuple[list[SourceDocumentModel], int]:
        """按稳定过滤条件分页查询当前城市材料。"""
        conditions = [SourceDocumentModel.city_id == city_id]
        if document_type:
            conditions.append(SourceDocumentModel.document_type == document_type)
        if status:
            conditions.append(SourceDocumentModel.status == status)
        else:
            conditions.append(SourceDocumentModel.status != "archived")
        if site_id:
            conditions.append(SourceDocumentModel.site_id == site_id)
        if keyword:
            pattern = f"%{keyword.strip()}%"
            conditions.append(
                or_(
                    SourceDocumentModel.title.ilike(pattern),
                    SourceDocumentModel.original_filename.ilike(pattern),
                )
            )
        total = self.session.scalar(
            select(func.count()).select_from(SourceDocumentModel).where(*conditions)
        )
        statement = (
            select(SourceDocumentModel)
            .where(*conditions)
            .order_by(SourceDocumentModel.created_at.desc(), SourceDocumentModel.id.desc())
            .offset((page - 1) * page_size)
            .limit(page_size)
        )
        return list(self.session.scalars(statement)), total or 0

from __future__ import annotations

"""文档解析运行和顺序元素数据访问层。"""

from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.modules.documents.model import DocumentElementModel, DocumentParseRunModel


def _parse_uuid(value: str | UUID) -> UUID | None:
    """安全解析外部UUID。"""
    if isinstance(value, UUID):
        return value
    try:
        return UUID(value)
    except ValueError:
        return None


class DocumentParseRepository:
    """解析查询同时限制城市、文档和运行范围。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def next_run_no(self, document_id: UUID) -> int:
        """生成同一文档内的递增解析序号。"""
        maximum = self.session.scalar(
            select(func.max(DocumentParseRunModel.run_no)).where(
                DocumentParseRunModel.document_id == document_id
            )
        )
        return (maximum or 0) + 1

    def has_active_run(self, document_id: UUID, city_id: int) -> bool:
        """避免同一文档被两个解析过程同时写入。"""
        statement = select(DocumentParseRunModel.id).where(
            DocumentParseRunModel.document_id == document_id,
            DocumentParseRunModel.city_id == city_id,
            DocumentParseRunModel.status.in_(["queued", "running"]),
        )
        return self.session.scalar(statement) is not None

    def add_run(self, run: DocumentParseRunModel) -> DocumentParseRunModel:
        """新增解析运行。"""
        self.session.add(run)
        self.session.flush()
        return run

    def get_run(self, run_id: str | UUID, city_id: int) -> DocumentParseRunModel | None:
        """读取当前城市的一次解析运行。"""
        normalized_id = _parse_uuid(run_id)
        if normalized_id is None:
            return None
        return self.session.scalar(
            select(DocumentParseRunModel).where(
                DocumentParseRunModel.id == normalized_id,
                DocumentParseRunModel.city_id == city_id,
            )
        )

    def latest_completed_run(self, document_id: UUID, city_id: int) -> DocumentParseRunModel | None:
        """返回文档最近一次成功解析。"""
        statement = (
            select(DocumentParseRunModel)
            .where(
                DocumentParseRunModel.document_id == document_id,
                DocumentParseRunModel.city_id == city_id,
                DocumentParseRunModel.status == "completed",
            )
            .order_by(DocumentParseRunModel.run_no.desc())
            .limit(1)
        )
        return self.session.scalar(statement)

    def add_element(self, element: DocumentElementModel) -> None:
        """追加一个不可变解析元素。"""
        self.session.add(element)

    def list_elements(self, parse_run_id: UUID, city_id: int) -> list[DocumentElementModel]:
        """按原文顺序返回一次解析的全部元素。"""
        statement = (
            select(DocumentElementModel)
            .where(
                DocumentElementModel.parse_run_id == parse_run_id,
                DocumentElementModel.city_id == city_id,
            )
            .order_by(DocumentElementModel.sequence_no)
        )
        return list(self.session.scalars(statement))

    def get_element(self, element_id: int, city_id: int) -> DocumentElementModel | None:
        """读取当前城市的单个解析元素。"""
        return self.session.scalar(
            select(DocumentElementModel).where(
                DocumentElementModel.id == element_id,
                DocumentElementModel.city_id == city_id,
            )
        )

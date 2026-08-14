from __future__ import annotations

"""历史稽核案例数据访问层。"""

from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.modules.audit_cases.model import AuditCaseModel
from app.modules.documents.model import SourceDocumentModel


class AuditCaseRepository:
    """所有案例读写都显式限制城市，形成13市查询边界。

    即使上层错误传入了其他城市的案例ID，SQL的city_id条件和复合外键仍会阻止串读串写。
    """

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, case: AuditCaseModel) -> AuditCaseModel:
        """把新案例加入当前事务；flush用于尽早发现唯一键和外键错误。"""
        self.session.add(case)
        self.session.flush()
        return case

    def get_by_document(self, document_id: UUID, city_id: int) -> AuditCaseModel | None:
        """按原报告取得唯一案例，同时限制城市；用于幂等创建和失败重试。"""
        return self.session.scalar(
            select(AuditCaseModel).where(
                AuditCaseModel.source_document_id == document_id,
                AuditCaseModel.city_id == city_id,
            )
        )

    def list_by_city(
        self, city_id: int, *, site_id: UUID | None = None, ready_only: bool = False
    ) -> list[tuple[AuditCaseModel, SourceDocumentModel]]:
        """按城市、报账点和状态读取案例，并带回可展示的原报告标题。

        ``ready_only`` 用于Agent检索，确保pending/failed案例不会影响业务判断；管理接口
        不启用该选项，因此仍能看到失败记录并安排重试。
        """
        conditions = [AuditCaseModel.city_id == city_id]
        if site_id is not None:
            conditions.append(AuditCaseModel.site_id == site_id)
        if ready_only:
            conditions.append(AuditCaseModel.status == "ready")
            # 标错或人工暂停的案例即使已经解析成功，也必须在数据库查询阶段排除。
            conditions.append(AuditCaseModel.memory_status == "active")
        # 在数据库中完成联表和排序，避免服务层逐条查询原报告造成N+1问题。
        statement = (
            select(AuditCaseModel, SourceDocumentModel)
            .join(
                SourceDocumentModel,
                (SourceDocumentModel.id == AuditCaseModel.source_document_id)
                & (SourceDocumentModel.city_id == AuditCaseModel.city_id),
            )
            .where(*conditions)
            .order_by(
                AuditCaseModel.analyzed_at.desc().nullslast(), AuditCaseModel.created_at.desc()
            )
        )
        return list(self.session.execute(statement).all())

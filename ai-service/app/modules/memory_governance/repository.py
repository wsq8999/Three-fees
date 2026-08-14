from __future__ import annotations

"""统一记忆治理的数据访问层。"""

from uuid import UUID

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.modules.audit_cases.model import AuditCaseModel
from app.modules.correction_memories.model import CorrectionMemoryModel
from app.modules.documents.model import SourceDocumentModel
from app.modules.memory_governance.model import MemoryFlagModel
from app.modules.sites.model import SiteModel


def parse_uuid(value: str | UUID) -> UUID | None:
    """安全解析外部ID，使错误格式稳定表现为资源不存在。"""
    if isinstance(value, UUID):
        return value
    try:
        return UUID(value)
    except ValueError:
        return None


class MemoryGovernanceRepository:
    """使用统一入口读取两类记忆，并对每次查询重复施加城市边界。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def get_audit_case(
        self, memory_id: str | UUID, city_id: int
    ) -> tuple[AuditCaseModel, SourceDocumentModel, SiteModel] | None:
        """取得历史案例、原报告和报账点，用于统一详情与治理操作。"""
        normalized = parse_uuid(memory_id)
        if normalized is None:
            return None
        statement = (
            select(AuditCaseModel, SourceDocumentModel, SiteModel)
            .join(
                SourceDocumentModel,
                (SourceDocumentModel.id == AuditCaseModel.source_document_id)
                & (SourceDocumentModel.city_id == AuditCaseModel.city_id),
            )
            .join(
                SiteModel,
                (SiteModel.id == AuditCaseModel.site_id)
                & (SiteModel.city_id == AuditCaseModel.city_id),
            )
            .where(AuditCaseModel.id == normalized, AuditCaseModel.city_id == city_id)
            # 治理操作锁定目标主记忆，避免并发请求同时创建两个open标错。
            .with_for_update(of=AuditCaseModel)
        )
        return self.session.execute(statement).one_or_none()

    def get_correction(
        self, memory_id: str | UUID, city_id: int
    ) -> tuple[CorrectionMemoryModel, SiteModel] | None:
        """取得人工纠错和报账点，跨城市ID不返回任何信息。"""
        normalized = parse_uuid(memory_id)
        if normalized is None:
            return None
        statement = (
            select(CorrectionMemoryModel, SiteModel)
            .join(
                SiteModel,
                (SiteModel.id == CorrectionMemoryModel.site_id)
                & (SiteModel.city_id == CorrectionMemoryModel.city_id),
            )
            .where(
                CorrectionMemoryModel.id == normalized,
                CorrectionMemoryModel.city_id == city_id,
            )
            .with_for_update(of=CorrectionMemoryModel)
        )
        return self.session.execute(statement).one_or_none()

    def list_audit_cases(
        self,
        city_id: int,
        site_id: UUID | None = None,
        memory_status: str | None = None,
        limit: int = 100,
    ) -> list[tuple[AuditCaseModel, SourceDocumentModel, SiteModel]]:
        """列出城市内全部历史案例，包括暂停和已标错记录。"""
        conditions = [AuditCaseModel.city_id == city_id]
        if site_id is not None:
            conditions.append(AuditCaseModel.site_id == site_id)
        if memory_status is not None:
            conditions.append(AuditCaseModel.memory_status == memory_status)
        statement = (
            select(AuditCaseModel, SourceDocumentModel, SiteModel)
            .join(SourceDocumentModel, SourceDocumentModel.id == AuditCaseModel.source_document_id)
            .join(SiteModel, SiteModel.id == AuditCaseModel.site_id)
            .where(*conditions)
            .order_by(AuditCaseModel.updated_at.desc())
            .limit(limit)
        )
        return list(self.session.execute(statement).all())

    def list_corrections(
        self,
        city_id: int,
        site_id: UUID | None = None,
        memory_status: str | None = None,
        limit: int = 100,
    ) -> list[tuple[CorrectionMemoryModel, SiteModel]]:
        """列出城市内全部人工纠错，包括草稿和已停用记录。"""
        conditions = [CorrectionMemoryModel.city_id == city_id]
        if site_id is not None:
            conditions.append(CorrectionMemoryModel.site_id == site_id)
        if memory_status is not None:
            conditions.append(CorrectionMemoryModel.memory_status == memory_status)
        statement = (
            select(CorrectionMemoryModel, SiteModel)
            .join(SiteModel, SiteModel.id == CorrectionMemoryModel.site_id)
            .where(*conditions)
            .order_by(CorrectionMemoryModel.updated_at.desc())
            .limit(limit)
        )
        return list(self.session.execute(statement).all())

    def count_audit_cases(
        self, city_id: int, site_id: UUID | None = None, memory_status: str | None = None
    ) -> int:
        """统计历史案例筛选结果，不为取得总数加载完整行。"""
        conditions = [AuditCaseModel.city_id == city_id]
        if site_id is not None:
            conditions.append(AuditCaseModel.site_id == site_id)
        if memory_status is not None:
            conditions.append(AuditCaseModel.memory_status == memory_status)
        return int(
            self.session.scalar(select(func.count()).select_from(AuditCaseModel).where(*conditions))
            or 0
        )

    def count_corrections(
        self, city_id: int, site_id: UUID | None = None, memory_status: str | None = None
    ) -> int:
        """统计人工纠错筛选结果，不为取得总数加载完整行。"""
        conditions = [CorrectionMemoryModel.city_id == city_id]
        if site_id is not None:
            conditions.append(CorrectionMemoryModel.site_id == site_id)
        if memory_status is not None:
            conditions.append(CorrectionMemoryModel.memory_status == memory_status)
        return int(
            self.session.scalar(
                select(func.count()).select_from(CorrectionMemoryModel).where(*conditions)
            )
            or 0
        )

    def list_open_flags(
        self,
        city_id: int,
        audit_case_ids: list[UUID],
        correction_memory_ids: list[UUID],
    ) -> list[MemoryFlagModel]:
        """一次取得当前页待处理标错，避免列表按行产生N+1查询。"""
        targets = []
        if audit_case_ids:
            targets.append(MemoryFlagModel.audit_case_id.in_(audit_case_ids))
        if correction_memory_ids:
            targets.append(MemoryFlagModel.correction_memory_id.in_(correction_memory_ids))
        if not targets:
            return []
        return list(
            self.session.scalars(
                select(MemoryFlagModel).where(
                    MemoryFlagModel.city_id == city_id,
                    MemoryFlagModel.status == "open",
                    or_(*targets),
                )
            ).all()
        )

    def get_open_flag(
        self, memory_type: str, memory_id: UUID, city_id: int
    ) -> MemoryFlagModel | None:
        """查找目标尚未处理的标错，防止重复标记造成状态恢复歧义。"""
        target = (
            MemoryFlagModel.audit_case_id
            if memory_type == "audit_case"
            else MemoryFlagModel.correction_memory_id
        )
        return self.session.scalar(
            select(MemoryFlagModel).where(
                MemoryFlagModel.city_id == city_id,
                target == memory_id,
                MemoryFlagModel.status == "open",
            )
        )

    def get_flag(self, flag_id: str | UUID, city_id: int) -> MemoryFlagModel | None:
        """按城市取得一条标错记录。"""
        normalized = parse_uuid(flag_id)
        if normalized is None:
            return None
        return self.session.scalar(
            select(MemoryFlagModel)
            .where(
                MemoryFlagModel.id == normalized,
                MemoryFlagModel.city_id == city_id,
            )
            # 同一条标错只能被处理一次；第二个并发请求等待后会看到已完成状态。
            .with_for_update()
        )

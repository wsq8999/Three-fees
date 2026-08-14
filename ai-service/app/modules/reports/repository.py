from __future__ import annotations

"""报告和报告版本的数据访问层。"""

from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.modules.reports.model import ReportModel, ReportVersionModel


def _parse_uuid(value: str | UUID) -> UUID | None:
    """安全解析路径UUID，格式错误统一按资源不存在处理。"""
    if isinstance(value, UUID):
        return value
    try:
        return UUID(value)
    except ValueError:
        return None


class ReportRepository:
    """所有读取都带城市条件，前端城市选择不是唯一隔离边界。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, report: ReportModel, version: ReportVersionModel) -> None:
        """在同一事务中写入报告主记录和首个版本。"""
        self.session.add_all([report, version])
        self.session.flush()

    def add_version(self, version: ReportVersionModel) -> None:
        """只追加新版本，不更新旧版本正文。"""
        self.session.add(version)
        self.session.flush()

    def get(self, report_id: str | UUID, city_id: int) -> ReportModel | None:
        """读取当前城市的一份报告。"""
        normalized = _parse_uuid(report_id)
        if normalized is None:
            return None
        return self.session.scalar(
            select(ReportModel).where(
                ReportModel.id == normalized,
                ReportModel.city_id == city_id,
            )
        )

    def get_by_run(self, run_id: str | UUID, city_id: int) -> ReportModel | None:
        """按分析运行查询已创建报告，用于幂等创建。"""
        normalized = _parse_uuid(run_id)
        if normalized is None:
            return None
        return self.session.scalar(
            select(ReportModel).where(
                ReportModel.analysis_run_id == normalized,
                ReportModel.city_id == city_id,
            )
        )

    def get_version(
        self, report_id: UUID, version_no: int, city_id: int
    ) -> ReportVersionModel | None:
        """读取指定报告的指定版本。"""
        return self.session.scalar(
            select(ReportVersionModel).where(
                ReportVersionModel.report_id == report_id,
                ReportVersionModel.version_no == version_no,
                ReportVersionModel.city_id == city_id,
            )
        )

    def list_versions(self, report_id: UUID, city_id: int) -> list[ReportVersionModel]:
        """按版本号倒序返回版本历史。"""
        statement = (
            select(ReportVersionModel)
            .where(
                ReportVersionModel.report_id == report_id,
                ReportVersionModel.city_id == city_id,
            )
            .order_by(ReportVersionModel.version_no.desc())
        )
        return list(self.session.scalars(statement))

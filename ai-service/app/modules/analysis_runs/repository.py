from __future__ import annotations

"""Agent运行和节点事件的数据访问层。"""

from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.modules.analysis_runs.model import AnalysisEventModel, AnalysisRunModel


class AnalysisRunRepository:
    """所有运行查询强制包含城市ID。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def next_run_no(self, task_id: str | UUID) -> int:
        """在单任务范围内生成可读的递增运行序号。"""
        maximum = self.session.scalar(
            select(func.max(AnalysisRunModel.run_no)).where(AnalysisRunModel.task_id == task_id)
        )
        return (maximum or 0) + 1

    def has_active_run(self, task_id: str | UUID, city_id: int) -> bool:
        """判断任务是否已有排队、执行中或等待人工输入的运行。"""
        statement = select(AnalysisRunModel.id).where(
            AnalysisRunModel.task_id == task_id,
            AnalysisRunModel.city_id == city_id,
            AnalysisRunModel.status.in_(["queued", "running", "waiting_input"]),
        )
        return self.session.scalar(statement) is not None

    def add(self, run: AnalysisRunModel) -> AnalysisRunModel:
        """新增运行记录。"""
        self.session.add(run)
        self.session.flush()
        return run

    def get(self, run_id: str | UUID, city_id: int) -> AnalysisRunModel | None:
        """读取当前城市运行。"""
        try:
            normalized_id = run_id if isinstance(run_id, UUID) else UUID(run_id)
        except ValueError:
            # 非法路径参数不进入数据库查询，统一由服务层转换成404。
            return None
        statement = select(AnalysisRunModel).where(
            AnalysisRunModel.id == normalized_id,
            AnalysisRunModel.city_id == city_id,
        )
        return self.session.scalar(statement)

    def list_by_city(self, city_id: int) -> list[AnalysisRunModel]:
        """按创建时间倒序列出当前城市运行。"""
        statement = (
            select(AnalysisRunModel)
            .where(AnalysisRunModel.city_id == city_id)
            .order_by(AnalysisRunModel.created_at.desc())
        )
        return list(self.session.scalars(statement))

    def list_events(self, run_id: str | UUID, city_id: int) -> list[AnalysisEventModel]:
        """按序号返回一次运行的全部节点事件。"""
        try:
            normalized_id = run_id if isinstance(run_id, UUID) else UUID(run_id)
        except ValueError:
            return []
        statement = (
            select(AnalysisEventModel)
            .where(
                AnalysisEventModel.run_id == normalized_id,
                AnalysisEventModel.city_id == city_id,
            )
            .order_by(AnalysisEventModel.sequence_no)
        )
        return list(self.session.scalars(statement))

    def append_event(
        self,
        *,
        run: AnalysisRunModel,
        node_name: str,
        event_type: str,
        message: str,
    ) -> None:
        """以当前最大序号追加不可变节点事件。"""
        current = self.session.scalar(
            select(func.max(AnalysisEventModel.sequence_no)).where(
                AnalysisEventModel.run_id == run.id
            )
        )
        self.session.add(
            AnalysisEventModel(
                city_id=run.city_id,
                run_id=run.id,
                sequence_no=(current or 0) + 1,
                node_name=node_name,
                event_type=event_type,
                message=message,
                payload={},
            )
        )

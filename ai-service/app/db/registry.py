from __future__ import annotations

"""集中导入全部模型，确保Alembic能发现完整MetaData。"""

from app.db.base import Base
from app.modules.analysis_runs.model import AnalysisEventModel, AnalysisRunModel
from app.modules.audit_cases.model import AuditCaseModel
from app.modules.audit_logs.model import AuditLogModel
from app.modules.audit_tasks.model import AuditTaskModel
from app.modules.cities.model import CityModel
from app.modules.correction_memories.model import CorrectionMemoryModel
from app.modules.documents.model import (
    DocumentElementModel,
    DocumentParseRunModel,
    SourceDocumentModel,
)
from app.modules.identity.model import AppUserModel
from app.modules.memory_governance.model import MemoryFlagModel, MemoryRevisionModel
from app.modules.reports.model import ReportModel, ReportVersionModel
from app.modules.sites.model import SiteModel

__all__ = [
    "AnalysisEventModel",
    "AnalysisRunModel",
    "AppUserModel",
    "AuditLogModel",
    "AuditCaseModel",
    "AuditTaskModel",
    "Base",
    "CityModel",
    "CorrectionMemoryModel",
    "DocumentElementModel",
    "DocumentParseRunModel",
    "MemoryFlagModel",
    "MemoryRevisionModel",
    "ReportModel",
    "ReportVersionModel",
    "SiteModel",
    "SourceDocumentModel",
]

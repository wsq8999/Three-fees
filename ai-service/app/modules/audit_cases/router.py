from __future__ import annotations

"""历史案例REST资源。"""

from typing import Annotated

from fastapi import APIRouter, Depends, Query, status

from app.api.dependencies import CityContextDep, CurrentUserDep, DbSessionDep
from app.modules.audit_cases.schemas import AuditCaseCreate, AuditCaseList, AuditCaseView
from app.modules.audit_cases.service import AuditCaseService
from app.modules.documents.storage import LocalDocumentStorage, get_document_storage

router = APIRouter(prefix="/audit-cases", tags=["audit-cases"])
# 存储适配器作为依赖注入，测试可替换为临时目录，未来也能替换为对象存储。
DocumentStorageDep = Annotated[LocalDocumentStorage, Depends(get_document_storage)]


@router.post("", response_model=AuditCaseView, status_code=status.HTTP_201_CREATED)
def create_audit_case(
    payload: AuditCaseCreate,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> AuditCaseView:
    """同步结构化一份历史报告。

    当前材料规模较小，因此接口同步返回201；重复调用会更新同一案例并递增版本。
    将来改为后台批处理时会新增异步入口，不改变现有接口的状态码语义。
    """
    return AuditCaseService(session, storage).analyze(
        payload.source_document_id, city, current_user
    )


@router.get("", response_model=AuditCaseList)
def list_audit_cases(
    city: CityContextDep,
    session: DbSessionDep,
    site_id: str | None = Query(default=None),
) -> AuditCaseList:
    """查询当前城市可审计的历史案例；X-City-Code由公共依赖统一校验。"""
    return AuditCaseList(items=AuditCaseService(session).list(city, site_id=site_id))

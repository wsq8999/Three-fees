from __future__ import annotations

"""历史报告结构化服务：读取全部解析元素，调用Kimi并保存案例记忆。"""

import base64
from datetime import datetime, timezone
from decimal import Decimal
from uuid import UUID, uuid4

from pydantic import ValidationError
from sqlalchemy.orm import Session

from app.agents.audit.prompt_loader import load_prompt
from app.core.config import get_settings
from app.core.exceptions import AppError, ResourceNotFoundError
from app.core.identity import CurrentUser
from app.integrations.ai.base import AIProviderError
from app.integrations.ai.factory import get_ai_provider
from app.modules.audit_cases.model import AuditCaseModel
from app.modules.audit_cases.repository import AuditCaseRepository
from app.modules.audit_cases.schemas import AuditCaseView, HistoricalCaseExtraction
from app.modules.cities.schemas import CityContext
from app.modules.documents.model import DocumentElementModel, SourceDocumentModel
from app.modules.documents.parse_repository import DocumentParseRepository
from app.modules.documents.repository import SourceDocumentRepository
from app.modules.documents.storage import LocalDocumentStorage

# 当前Kimi单次联合分析的业务安全上限。超过时明确失败，绝不静默截断报告。
MAX_HISTORY_TEXT_CHARS = 120_000
MAX_HISTORY_IMAGES = 30
# 结果落库时保存提示词版本，后续修改提示词后能判断哪些案例需要重建。
PROMPT_VERSION = "extract_history_case_v1"


def _data_url(storage: LocalDocumentStorage, element: DocumentElementModel) -> str:
    """只在模型调用期间读取历史图片并转换成内存Data URL。

    数据库和LangGraph状态都不保存base64图片，避免状态膨胀与敏感图片重复落库。
    ``storage.resolve`` 还会校验路径必须位于项目存储根目录内，防止路径越界读取。
    """
    if not element.asset_storage_key or not element.media_type:
        raise AIProviderError("history_image_missing", "历史报告图片资产不完整")
    if not element.media_type.startswith("image/"):
        raise AIProviderError("unsupported_history_image", "历史报告包含不支持的图片格式")
    content = storage.resolve(element.asset_storage_key).read_bytes()
    encoded = base64.b64encode(content).decode("ascii")
    return f"data:{element.media_type};base64,{encoded}"


def _to_view(case: AuditCaseModel, document: SourceDocumentModel, city_code: str) -> AuditCaseView:
    """把数据库模型转换成稳定REST响应，并隐藏内部存储路径等实现字段。"""
    return AuditCaseView(
        id=str(case.id),
        city_code=city_code,
        site_id=str(case.site_id),
        source_document_id=str(case.source_document_id),
        source_title=document.title,
        status=case.status,
        memory_status=case.memory_status,
        billing_period=case.billing_period,
        over_limit_items=case.over_limit_items,
        primary_reason=case.primary_reason,
        reason_category=case.reason_category,
        key_facts=case.key_facts,
        evidence_element_ids=case.evidence_element_ids,
        uncertain_items=case.uncertain_items,
        confidence=float(case.confidence) if case.confidence is not None else None,
        model_name=case.model_name,
        prompt_version=case.prompt_version,
        error_code=case.error_code,
        error_message=case.error_message,
        created_at=case.created_at,
        analyzed_at=case.analyzed_at,
    )


class AuditCaseService:
    """创建和查询当前城市的长期历史案例记忆。

    服务层负责业务资格校验、模型调用、Schema校验和事务状态切换；Repository只负责
    带城市条件的SQL查询。拆开后，未来替换模型或对象存储不会改变REST契约。
    """

    def __init__(self, session: Session, storage: LocalDocumentStorage | None = None) -> None:
        # storage允许测试注入临时目录，生产环境默认读取配置中的data/uploads。
        self.session = session
        self.storage = storage or LocalDocumentStorage()
        self.repository = AuditCaseRepository(session)
        self.document_repository = SourceDocumentRepository(session)
        self.parse_repository = DocumentParseRepository(session)

    def analyze(self, document_id: str, city: CityContext, user: CurrentUser) -> AuditCaseView:
        """整份读取一份已解析历史DOCX并保存Kimi结构化结果。

        处理顺序：校验城市和材料状态 → 获取最新成功解析元素 → 创建/重置案例状态 →
        调用Kimi → 校验证据ID → 写入ready结果。任何模型阶段错误都会留下failed记录，
        使批处理可以安全续跑，也让业务员知道哪份报告尚未进入城市记忆。
        """
        # Repository.get同时限制document_id和city.id，跨城市访问统一表现为不存在。
        document = self.document_repository.get(document_id, city.id)
        if document is None:
            raise ResourceNotFoundError("历史报告不存在")
        if document.document_type != "historical_report" or document.status != "parsed":
            raise AppError(
                status=400,
                code="history_report_not_ready",
                title="历史报告尚不可结构化",
                detail="只有成功解析且已关联报账点的历史报告才能形成案例",
            )
        if document.site_id is None:
            raise AppError(
                status=400,
                code="history_site_required",
                title="历史报告缺少报账点",
                detail="请先为历史报告关联数据库中的报账点",
            )
        # 只使用最近一次成功解析，失败重跑不会覆盖此前仍可用的元素版本。
        parse_run = self.parse_repository.latest_completed_run(document.id, city.id)
        elements = self.parse_repository.list_elements(parse_run.id, city.id) if parse_run else []
        if not elements:
            raise AppError(
                status=400,
                code="history_elements_required",
                title="历史报告没有解析元素",
                detail="请先重新解析该历史报告",
            )

        # source_document_id唯一：重复执行更新同一条记忆，而不是制造相互冲突的案例。
        case = self.repository.get_by_document(document.id, city.id)
        if case is None:
            case = self.repository.add(
                AuditCaseModel(
                    id=uuid4(),
                    city_id=city.id,
                    site_id=document.site_id,
                    source_document_id=document.id,
                    status="pending",
                    created_by=UUID(user.id),
                )
            )
        else:
            case.status = "pending"
            # 重建期间旧版本也不继续参与RAG，避免模型使用正在被替换的结论。
            case.memory_status = "paused"
            case.error_code = None
            case.error_message = None
        # 先提交pending，使长时间模型调用期间数据库可以展示真实进度。
        self.session.commit()

        try:
            extracted = self._call_model(document, elements)
            # Schema只能验证“是整数列表”，这里进一步验证每个ID确实属于当前历史报告。
            valid_ids = {element.id for element in elements}
            if any(item not in valid_ids for item in extracted.evidence_element_ids):
                raise AIProviderError(
                    "history_evidence_invalid", "Kimi返回了不属于该历史报告的证据元素ID"
                )
            # 经过供应商JSON Schema与Pydantic双重校验后，才允许覆盖案例业务字段。
            case.billing_period = extracted.billing_period
            case.over_limit_items = extracted.over_limit_items
            case.primary_reason = extracted.primary_reason
            case.reason_category = extracted.reason_category
            case.key_facts = extracted.key_facts
            case.evidence_element_ids = extracted.evidence_element_ids
            case.uncertain_items = extracted.uncertain_items
            case.confidence = Decimal(str(extracted.confidence))
            case.model_name = get_ai_provider().model_name
            case.prompt_version = PROMPT_VERSION
            case.status = "ready"
            # 成功重建形成可信的新版本，因此恢复为可参与RAG的active状态。
            case.memory_status = "active"
            case.analyzed_at = datetime.now(timezone.utc)
            # 成功才递增版本；失败重试不会伪装成一个新的可用版本。
            case.version += 1
            self.session.commit()
            self.session.refresh(case)
            return _to_view(case, document, city.code)
        except Exception as exc:
            # 回滚模型结果写入，再用独立事务保存稳定失败状态，避免半成品参与RAG。
            self.session.rollback()
            case = self.repository.get_by_document(document.id, city.id)
            if case is not None:
                case.status = "failed"
                case.error_code = (
                    exc.code if isinstance(exc, AIProviderError) else "case_build_failed"
                )
                case.error_message = str(exc)[:500]
                self.session.commit()
            raise

    def _call_model(
        self, document: SourceDocumentModel, elements: list[DocumentElementModel]
    ) -> HistoricalCaseExtraction:
        """按原文顺序把文字、表格和图片交给Kimi，且不静默截断。

        文本和图片交错发送非常重要：报告中的“如下图”与紧随其后的截图存在上下文关系，
        如果分别调用模型，会丢失这种对应关系并增加原因误判概率。
        """
        text_chars = sum(len(item.content_text or "") for item in elements)
        image_count = sum(item.element_type == "image" for item in elements)
        if text_chars > MAX_HISTORY_TEXT_CHARS or image_count > MAX_HISTORY_IMAGES:
            raise AIProviderError("history_report_too_large", "历史报告超过单次完整分析范围")
        # 第一段告诉模型业务范围；后续每个元素都带真实ID，供结果返回证据引用。
        content: list[dict[str, object]] = [
            {
                "type": "text",
                "text": (
                    f"历史报告标题：{document.title}\n"
                    "以下内容按DOCX原始顺序提供。请联合分析全部正文、表格和图片，"
                    "只提取报告中有证据支持的超标原因。"
                ),
            }
        ]
        for element in elements:
            # 保持sequence_no查询顺序，不按类型重排，最大限度还原Word阅读顺序。
            label = (
                f"[元素ID={element.id}；类型={element.element_type}；"
                f"章节={element.section_title or '未标注章节'}]"
            )
            content.append({"type": "text", "text": label})
            if element.element_type == "image":
                content.append(
                    {"type": "image_url", "image_url": {"url": _data_url(self.storage, element)}}
                )
            elif element.content_text:
                content.append({"type": "text", "text": element.content_text})
        # Provider内部封装Kimi的OpenAI兼容协议；业务层只依赖供应商无关接口。
        raw = get_ai_provider().generate_structured(
            system_prompt=load_prompt("extract_history_case_v1.md"),
            user_content=content,
            schema_name="historical_audit_case",
            json_schema=HistoricalCaseExtraction.model_json_schema(),
            reasoning_effort=get_settings().kimi_extract_reasoning_effort,
        )
        try:
            # 即使供应商声称遵守JSON Schema，也必须在本地再验证一次后才能入库。
            return HistoricalCaseExtraction.model_validate(raw)
        except ValidationError as exc:
            raise AIProviderError("ai_output_schema_invalid", "Kimi历史案例结果字段不合规") from exc

    def list(self, city: CityContext, *, site_id: str | None = None) -> list[AuditCaseView]:
        """列出当前城市案例；可按报账点过滤，且Repository仍会再次限制城市。"""
        normalized_site_id = UUID(site_id) if site_id else None
        return [
            _to_view(case, document, city.code)
            for case, document in self.repository.list_by_city(city.id, site_id=normalized_site_id)
        ]

from __future__ import annotations

"""把数据库中的可信任务、本次报告和历史报告整理成LangGraph纯JSON上下文。"""

import re
from difflib import SequenceMatcher
from typing import Any
from uuid import UUID

from sqlalchemy.orm import Session

from app.core.exceptions import ResourceNotFoundError
from app.modules.audit_cases.repository import AuditCaseRepository
from app.modules.audit_tasks.repository import AuditTaskRepository
from app.modules.correction_memories.repository import CorrectionMemoryRepository
from app.modules.documents.model import DocumentElementModel, SourceDocumentModel
from app.modules.documents.parse_repository import DocumentParseRepository
from app.modules.documents.repository import SourceDocumentRepository

# 只有没有同报账点历史时才退回同城市候选，并限制数量防止无关材料挤占上下文。
MAX_CITY_FALLBACK_CANDIDATES = 5
# 已有audit_case提供完整精简结论；少量原文元素用于复核，而不是代替整份案例构建。
MAX_TEXT_ELEMENTS_PER_DOCUMENT = 6
MAX_IMAGE_ELEMENTS_PER_DOCUMENT = 3


def _normalize_name(value: str) -> str:
    """去除标点和空白，得到只用于确定性名称比较的文本。

    该函数只在没有人工site_id关联时作为降级排序，绝不宣称是语义理解。
    """
    return "".join(re.findall(r"[\w\u4e00-\u9fff]", value.casefold()))


def _title_similarity(query: str, title: str) -> float:
    """计算可解释的名称相似度，不把它包装成AI语义匹配。"""
    normalized_query = _normalize_name(query)
    normalized_title = _normalize_name(title)
    if not normalized_query or not normalized_title:
        return 0.0
    if normalized_query in normalized_title:
        return 1.0
    return round(SequenceMatcher(None, normalized_query, normalized_title).ratio(), 4)


class AnalysisContextBuilder:
    """在工作流开始前读取数据库，使LangGraph节点保持无数据库依赖。

    一次性冻结上下文有两个目的：其一，运行过程中数据库新增案例不会导致前后节点
    使用不同证据；其二，LangGraph节点更容易测试、重放和将来接入checkpoint。
    """

    def __init__(self, session: Session) -> None:
        self.session = session
        self.document_repository = SourceDocumentRepository(session)
        self.parse_repository = DocumentParseRepository(session)

    def build(
        self,
        *,
        task_id: str,
        city_id: int,
        city_code: str,
        material_refs: list[str],
    ) -> dict[str, Any]:
        """生成一次运行固定使用的任务、当前材料和历史证据候选快照。

        ``city_id`` 来自已校验的请求头上下文，所有Repository查询都会再次使用它；
        ``material_refs`` 在运行创建时已经校验属于当前任务，这里只构造模型所需快照。
        """
        task_row = AuditTaskRepository(self.session).get(task_id, city_id)
        if task_row is None:
            raise ResourceNotFoundError("稽核任务不存在")
        task, site = task_row
        material_ids = [UUID(item) for item in material_refs]
        current_materials = self.document_repository.list_by_ids(material_ids, city_id)
        # 先取得当前城市全部已解析历史，再取得ready且active案例；其他城市数据不会进入内存。
        all_history = self.document_repository.list_parsed_history(city_id)
        case_rows = AuditCaseRepository(self.session).list_by_city(city_id, ready_only=True)
        # 以原报告ID建立映射，组装证据时避免每份历史报告再发一次数据库查询。
        cases_by_document = {case.source_document_id: case for case, _ in case_rows}
        # 原报告只是证据载体，是否能用于RAG由其案例记忆决定。若这里继续把所有原报告
        # 交给Agent，已标错案例仍会通过原文旁路影响判断，因此必须在构造上下文前一并过滤。
        history = [item for item in all_history if item.id in cases_by_document]
        # 纠错记忆必须同时满足当前城市、当前报账点和confirmed状态，草稿绝不进入Agent。
        correction_rows = CorrectionMemoryRepository(self.session).list_by_city(
            city_id,
            site_id=task.site_id,
            confirmed_only=True,
        )
        selected, retrieval_scope = self._select_history(history, task.site_id, site.site_name)
        return {
            "task_context": {
                "task_id": str(task.id),
                "city_code": city_code,
                "site_id": str(site.id),
                "site_name": site.site_name,
                "title": task.title,
                "question": task.question,
            },
            "current_materials": [
                self._current_material_summary(item) for item in current_materials
            ],
            "history_candidates": [
                self._history_summary(
                    item, task.site_id, site.site_name, cases_by_document.get(item.id)
                )
                for item in selected
            ],
            "correction_memories": [
                {
                    "memory_id": str(memory.id),
                    "source_analysis_run_id": str(memory.analysis_run_id),
                    "corrected_reason": memory.corrected_reason,
                    "reason_category": memory.reason_category,
                    "applicability_conditions": memory.applicability_conditions,
                    "supporting_element_ids": memory.supporting_element_ids,
                    "interpretation_summary": memory.interpretation_summary,
                    "confidence": float(memory.confidence),
                    "confirmed_at": (
                        memory.confirmed_at.isoformat() if memory.confirmed_at else None
                    ),
                }
                for memory, _ in correction_rows
            ],
            "retrieval_summary": {
                "scope": retrieval_scope,
                "available_history_count": len(history),
                "selected_history_count": len(selected),
                "same_site_history_count": sum(item.site_id == task.site_id for item in history),
                "confirmed_correction_count": len(correction_rows),
            },
        }

    def _select_history(
        self,
        history: list[SourceDocumentModel],
        site_id: UUID,
        site_name: str,
    ) -> tuple[list[SourceDocumentModel], str]:
        """有人工关联时只用同点全部历史，否则退回同城市名称相似排序。

        “同点”只认稳定site_id，不依赖名称猜测；这正是13个城市各学各的、每个报账点
        优先学习自身历史的关键规则。名称相似度只用于尚无同点历史的冷启动场景。
        """
        same_site = [item for item in history if item.site_id == site_id]
        if same_site:
            # 同报账点历史案例必须全部纳入；结构化案例负责把长报告压缩到可控长度。
            return same_site, "same_site"
        ranked = sorted(
            history,
            key=lambda item: (_title_similarity(site_name, item.title), item.updated_at),
            reverse=True,
        )
        return ranked[:MAX_CITY_FALLBACK_CANDIDATES], "city_fallback"

    def _current_material_summary(self, document: SourceDocumentModel) -> dict[str, Any]:
        """传递本次材料及其全部有序解析元素，不把文件/图片二进制写入状态。"""
        run = self.parse_repository.latest_completed_run(document.id, document.city_id)
        elements = self.parse_repository.list_elements(run.id, document.city_id) if run else []
        summary = {
            "document_id": str(document.id),
            "document_type": document.document_type,
            "title": document.title,
            "media_type": document.media_type,
            "site_id": str(document.site_id) if document.site_id else None,
            "task_id": str(document.task_id) if document.task_id else None,
            "content_url": f"/api/v1/documents/{document.id}/content",
            "element_count": len(elements),
            "text_element_count": sum(item.element_type != "image" for item in elements),
            "image_element_count": sum(item.element_type == "image" for item in elements),
            "elements": [self._current_element(item) for item in elements],
        }
        return summary

    def _current_element(self, element: DocumentElementModel) -> dict[str, Any]:
        """保留本次报告的完整文字和表格文本，并用受城市保护URL引用图片。"""
        return {
            "element_id": element.id,
            "sequence_no": element.sequence_no,
            "element_type": element.element_type,
            "section_title": element.section_title,
            "content_text": element.content_text,
            "media_type": element.media_type,
            "asset_url": (
                f"/api/v1/document-elements/{element.id}/content"
                if element.asset_storage_key
                else None
            ),
        }

    def _history_summary(
        self,
        document: SourceDocumentModel,
        task_site_id: UUID,
        site_name: str,
        audit_case,
    ) -> dict[str, Any]:
        """生成带结构化案例和原文元素定位的单份历史证据摘要。

        audit_case存在时提供精简长期记忆；不存在时仍保留少量原文证据，使系统可以
        明确返回“历史案例尚未构建”，而不是跨城市找一个看似相近的结论冒充。
        """
        is_same_site = document.site_id == task_site_id
        score = 1.0 if is_same_site else _title_similarity(site_name, document.title)
        if is_same_site:
            reason = "历史报告已人工关联到当前报账点"
            scope = "same_site"
        elif score == 1.0:
            reason = "报告标题包含当前报账点名称"
            scope = "city_title_match"
        else:
            reason = "同城市历史报告，按报账点名称相似度排序"
            scope = "city_fallback"
        run = self.parse_repository.latest_completed_run(document.id, document.city_id)
        elements = self.parse_repository.list_elements(run.id, document.city_id) if run else []
        return {
            "document_id": str(document.id),
            "title": document.title,
            "site_id": str(document.site_id) if document.site_id else None,
            "match_scope": scope,
            "match_score": score,
            "match_reason": reason,
            "content_url": f"/api/v1/documents/{document.id}/content",
            "audit_case": (
                {
                    "case_id": str(audit_case.id),
                    "billing_period": audit_case.billing_period,
                    "over_limit_items": audit_case.over_limit_items,
                    "primary_reason": audit_case.primary_reason,
                    "reason_category": audit_case.reason_category,
                    "key_facts": audit_case.key_facts,
                    "evidence_element_ids": audit_case.evidence_element_ids,
                    "uncertain_items": audit_case.uncertain_items,
                    "confidence": (
                        float(audit_case.confidence) if audit_case.confidence is not None else None
                    ),
                }
                if audit_case is not None
                else None
            ),
            "elements": self._select_elements(elements),
        }

    def _select_elements(self, elements: list[DocumentElementModel]) -> list[dict[str, Any]]:
        """按原文顺序选取有限文字和图片，控制运行状态大小。

        这里的限制只作用于原因判断时的“补充原文”。历史案例构建阶段已经分析过该
        报告的全部元素，所以不会因此丢失案例事实；若无案例，结果应降低置信度。
        """
        selected: list[dict[str, Any]] = []
        text_count = 0
        image_count = 0
        for element in elements:
            if element.element_type == "image":
                if image_count >= MAX_IMAGE_ELEMENTS_PER_DOCUMENT:
                    continue
                image_count += 1
            else:
                if text_count >= MAX_TEXT_ELEMENTS_PER_DOCUMENT:
                    continue
                text_count += 1
            selected.append(
                {
                    "element_id": element.id,
                    "sequence_no": element.sequence_no,
                    "element_type": element.element_type,
                    "section_title": element.section_title,
                    "content_text": (
                        element.content_text[:800] if element.content_text is not None else None
                    ),
                    "asset_url": (
                        f"/api/v1/document-elements/{element.id}/content"
                        if element.asset_storage_key
                        else None
                    ),
                }
            )
        return selected

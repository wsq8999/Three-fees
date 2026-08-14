"""测试数据库和FastAPI客户端的公共夹具。"""

import re
from collections.abc import Generator
from contextlib import contextmanager
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import InMemorySaver
from sqlalchemy import text
from sqlalchemy.orm import Session, sessionmaker

from app.agents.audit.graph import build_audit_graph
from app.db.registry import Base
from app.db.session import engine, get_db
from app.main import create_app
from app.modules.analysis_runs import service as analysis_run_service
from app.modules.audit_cases import service as audit_case_service
from app.modules.cities.model import CityModel
from app.modules.correction_memories import service as correction_memory_service
from app.modules.documents.storage import LocalDocumentStorage, get_document_storage
from app.modules.identity.model import AppUserModel

TEST_SCHEMA = "audit_test"
DEVELOPMENT_USER_ID = UUID("00000000-0000-0000-0000-000000000001")
CITY_ROWS = [
    (1, "nanjing", "南京"),
    (2, "wuxi", "无锡"),
    (3, "xuzhou", "徐州"),
    (4, "changzhou", "常州"),
    (5, "suzhou", "苏州"),
    (6, "nantong", "南通"),
    (7, "lianyungang", "连云港"),
    (8, "huaian", "淮安"),
    (9, "yancheng", "盐城"),
    (10, "yangzhou", "扬州"),
    (11, "zhenjiang", "镇江"),
    (12, "taizhou", "泰州"),
    (13, "suqian", "宿迁"),
]


class FakeAIProvider:
    """接口测试使用的确定性视觉模型，避免消耗真实Kimi额度。"""

    model_name = "fake-vision-model"

    def generate_structured(self, **kwargs) -> dict[str, object]:
        """按正式Schema返回固定结果，并保留DOCX元素引用。"""
        if kwargs.get("schema_name") == "electricity_audit_report_draft":
            return {
                "status": "draft",
                "title": "电费超标稽核报告草稿",
                "sections": [
                    {
                        "section_code": code,
                        "title": title,
                        "content": "依据本次已识别事实形成的待审核报告内容。",
                        "supporting_current_element_ids": [],
                        "supporting_history_element_ids": [],
                        "calculation_references": [],
                    }
                    for code, title in (
                        ("over_limit_summary", "超标情况说明"),
                        ("metric_verification", "指标复算"),
                        ("historical_comparison", "历史情况对比"),
                        ("reason_analysis", "原因分析"),
                        ("audit_conclusion", "稽核结论"),
                        ("remediation_summary", "整改小结"),
                    )
                ],
                "uncertain_items": [],
                "requires_human_review": False,
                "review_reasons": [],
                "confidence": 0.88,
            }
        if kwargs.get("schema_name") == "electricity_audit_reason_judgment":
            serialized = str(kwargs.get("user_content", ""))
            memory_ids = re.findall(r'["\']memory_id["\']:\s*["\']([0-9a-f-]{36})["\']', serialized)
            return {
                "status": "completed" if memory_ids else "insufficient_evidence",
                "primary_reason": "业务员确认的设备扩容原因" if memory_ids else None,
                "reason_category": "用量增长" if memory_ids else None,
                "similarity_to_history": None,
                "matched_case_ids": [],
                "matched_history_document_ids": [],
                "matched_correction_memory_ids": memory_ids,
                "correction_memory_applied": bool(memory_ids),
                "correction_memory_explanation": (
                    "本次事实符合已确认纠错的适用条件。" if memory_ids else None
                ),
                "supporting_current_element_ids": [],
                "supporting_history_element_ids": [],
                "reasoning_summary": (
                    "本次事实符合业务员已经确认的设备扩容纠错。"
                    if memory_ids
                    else "本次材料能证明超标，但尚不能证明具体原因。"
                ),
                "differences_from_history": [],
                "uncertain_items": [] if memory_ids else ["缺少原因证据"],
                "confidence": 0.9 if memory_ids else 0.2,
            }
        if kwargs.get("schema_name") == "electricity_audit_correction_interpretation":
            serialized = str(kwargs.get("user_content", ""))
            matched_ids = [
                int(item) for item in re.findall(r"element_id[\"']?\s*:\s*(\d+)", serialized)
            ]
            return {
                "corrected_reason": "新增通信设备扩容导致用电量增长",
                "reason_category": "用量增长",
                "applicability_conditions": ["存在新增通信设备", "日均用电量增长"],
                "supporting_current_element_ids": matched_ids[:1],
                "interpretation_summary": "我理解你的意思是实际原因是设备扩容。",
                "uncertain_items": [],
                "confidence": 0.94,
            }
        if kwargs.get("schema_name") == "historical_audit_case":
            serialized = str(kwargs.get("user_content", ""))
            matched_ids = [int(item) for item in re.findall(r"元素ID=(\d+)", serialized)]
            return {
                "billing_period": "2026-06",
                "over_limit_items": ["日均电费同比"],
                "primary_reason": "报告仅说明用电增长，未给出更细原因。",
                "reason_category": "用量增长",
                "key_facts": ["本期用电同比超标"],
                "evidence_element_ids": matched_ids[:1],
                "uncertain_items": [],
                "confidence": 0.8,
            }
        if kwargs.get("schema_name") == "electricity_audit_report_facts":
            serialized = str(kwargs.get("user_content", ""))
            matched_ids = [int(item) for item in re.findall(r"元素ID=(\d+)", serialized)]
            source_id = matched_ids[0]
            return {
                "system_over_limit_status": "yes",
                "system_over_limit_evidence_text": "三费系统显示日均电费同比超标",
                "system_over_limit_evidence_element_ids": [source_id],
                "document_title_in_content": "苏州测试稽核报告",
                "site_name_in_content": "苏州工业园区测试报账点",
                "billing_period": "2026-07",
                "document_summary": "报告包含同比超标说明、指标表格和业务截图。",
                "over_limit_items": ["日均电费同比"],
                "metrics": [
                    {
                        "metric_name": "日均电费同比",
                        "actual_value": "120.50",
                        "benchmark_lower_value": "80.00",
                        "benchmark_upper_value": "100.00",
                        "reported_over_limit_rate_percent": "20.5",
                        "unit": "度",
                        "comparison_type": "同比",
                        "comparison_applicability": "applicable",
                        "applicability_reason": None,
                        "is_over_limit": True,
                        "evidence_text": "本期用电同比超标",
                        "evidence_element_ids": [source_id],
                        "confidence": 0.96,
                    }
                ],
                "explicit_statements": [
                    {
                        "statement": "本期用电同比超标",
                        "statement_type": "超标说明",
                        "source_element_ids": [source_id],
                        "confidence": 0.95,
                    }
                ],
                "observations": ["DOCX同时包含文字、表格和图片"],
                "uncertain_items": [],
                "overall_confidence": 0.93,
            }
        serialized = str(kwargs.get("user_content", ""))
        requires_confirmation = "人工中断测试" in serialized
        return {
            "system_over_limit_status": "unknown" if requires_confirmation else "yes",
            "system_over_limit_evidence_text": None if requires_confirmation else "截图标记为超标",
            "system_over_limit_evidence_element_ids": [],
            "site_name_in_image": None,
            "billing_period": "2026-07",
            "over_limit_items": [] if requires_confirmation else ["日均电费同比"],
            "metrics": []
            if requires_confirmation
            else [
                {
                    "metric_name": "日均电费同比",
                    "actual_value": "120.50",
                    "benchmark_lower_value": "80.00",
                    "benchmark_upper_value": "100.00",
                    "reported_over_limit_rate_percent": "20.5",
                    "unit": "度",
                    "comparison_type": "同比",
                    "comparison_applicability": "applicable",
                    "applicability_reason": None,
                    "is_over_limit": True,
                    "evidence_text": "日均电费同比20.5%",
                    "evidence_element_ids": [],
                    "confidence": 0.96,
                }
            ],
            "observations": ["截图标记为超标"],
            "uncertain_items": ["截图未显示完整报账点名称"],
            "overall_confidence": 0.93,
        }


# 模型仍声明在audit schema；测试引擎在执行时把它安全映射到独立schema。
test_engine = engine.execution_options(schema_translate_map={"audit": TEST_SCHEMA})
TestingSessionLocal = sessionmaker(
    bind=test_engine,
    autoflush=False,
    expire_on_commit=False,
)


def _seed_reference_data() -> None:
    """写入接口测试所需且稳定不变的城市、用户主数据。"""
    with TestingSessionLocal.begin() as session:
        session.add_all(
            [CityModel(id=city_id, code=code, name=name) for city_id, code, name in CITY_ROWS]
        )
        session.add(
            AppUserModel(
                id=DEVELOPMENT_USER_ID,
                account="development-user",
                display_name="开发用户",
                status="active",
                identity_provider="local",
            )
        )


@pytest.fixture(scope="session", autouse=True)
def isolated_test_database() -> Generator[None]:
    """创建并最终删除专用测试schema，绝不清理开发数据表。"""
    with engine.begin() as connection:
        connection.execute(text(f'DROP SCHEMA IF EXISTS "{TEST_SCHEMA}" CASCADE'))
        connection.execute(text(f'CREATE SCHEMA "{TEST_SCHEMA}"'))
    Base.metadata.create_all(test_engine)
    _seed_reference_data()
    yield
    with engine.begin() as connection:
        connection.execute(text(f'DROP SCHEMA IF EXISTS "{TEST_SCHEMA}" CASCADE'))


@pytest.fixture
def db_session() -> Generator[Session]:
    """为需要核对持久化副作用的测试提供独立只读会话。"""
    with TestingSessionLocal() as session:
        yield session


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch, tmp_path) -> Generator[TestClient]:
    """让HTTP请求和后台Agent任务共同使用隔离测试数据库。"""
    # 合约测试注入内存Saver，不应在测试生命周期中初始化开发库的PostgreSQL检查点。
    application = create_app(initialize_checkpoints=False)
    test_storage = LocalDocumentStorage(root=tmp_path / "uploads", max_size_bytes=1024 * 1024)
    test_graph = build_audit_graph(InMemorySaver())

    @contextmanager
    def open_test_graph():
        """接口测试跨两次HTTP请求复用同一内存检查点，模拟生产PostgreSQL恢复。"""
        yield test_graph

    def override_get_db() -> Generator[Session]:
        with TestingSessionLocal() as session:
            try:
                yield session
            except Exception:
                session.rollback()
                raise

    application.dependency_overrides[get_db] = override_get_db
    application.dependency_overrides[get_document_storage] = lambda: test_storage
    monkeypatch.setattr(analysis_run_service, "SessionLocal", TestingSessionLocal)
    monkeypatch.setattr(analysis_run_service, "get_ai_provider", lambda: FakeAIProvider())
    monkeypatch.setattr(analysis_run_service, "open_persistent_audit_graph", open_test_graph)
    monkeypatch.setattr(audit_case_service, "get_ai_provider", lambda: FakeAIProvider())
    monkeypatch.setattr(correction_memory_service, "get_ai_provider", lambda: FakeAIProvider())
    with TestClient(application) as test_client:
        yield test_client

    # 按外键依赖顺序清理易变业务数据，保留13市和开发用户供下一测试使用。
    with TestingSessionLocal.begin() as session:
        session.execute(text(f'TRUNCATE TABLE "{TEST_SCHEMA}".document_element CASCADE'))
        session.execute(text(f'TRUNCATE TABLE "{TEST_SCHEMA}".document_parse_run CASCADE'))
        session.execute(text(f'TRUNCATE TABLE "{TEST_SCHEMA}".audit_log CASCADE'))
        session.execute(text(f'TRUNCATE TABLE "{TEST_SCHEMA}".source_document CASCADE'))
        session.execute(text(f'TRUNCATE TABLE "{TEST_SCHEMA}".analysis_event CASCADE'))
        session.execute(text(f'TRUNCATE TABLE "{TEST_SCHEMA}".analysis_run CASCADE'))
        session.execute(text(f'TRUNCATE TABLE "{TEST_SCHEMA}".audit_task CASCADE'))
        session.execute(text(f'TRUNCATE TABLE "{TEST_SCHEMA}".site CASCADE'))

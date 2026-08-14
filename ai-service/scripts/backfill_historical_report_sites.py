"""为已导入的27份历史稽核材料创建报账点并建立可审计关联。"""

# 独立脚本必须先把backend加入搜索路径，后端模块导入因此有意位于路径设置之后。
# ruff: noqa: E402

import argparse
import hashlib
import json
import sys
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID, uuid5

from sqlalchemy import select

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "backend"))

from app.db import registry as _model_registry  # noqa: F401
from app.db.session import SessionLocal
from app.modules.audit_logs.repository import AuditLogRepository
from app.modules.cities.model import CityModel
from app.modules.documents.model import SourceDocumentModel
from app.modules.sites.model import SiteModel

SITE_NAMESPACE = UUID("88a07b68-4a23-4f45-a253-8ef032958452")


@dataclass(frozen=True)
class HistoricalSiteMapping:
    """一份历史材料与正文中真实报账点名称的人工复核映射。"""

    city_code: str
    filename: str
    site_name: str
    extraction_source: str


@dataclass(frozen=True)
class BackfillItemResult:
    """单份材料的预演或执行结果。"""

    city_code: str
    filename: str
    document_id: str | None
    site_id: str | None
    site_code: str | None
    site_name: str
    action: str
    message: str


# 名称优先取报告正文标题；旧DOC通过本机Word只读提取后人工复核。
HISTORICAL_SITE_MAPPINGS = [
    HistoricalSiteMapping(
        "changzhou",
        "（额定超标）常州武进区兰新大厦铁塔资源点点电费稽核说明-2023-11-01-2024-01-31.docx",
        "常州武进区兰新大厦铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "changzhou",
        "常州溧阳市常州溧阳嵘麒钢构资源点稽核说明20240101-20240131.docx",
        "常州溧阳市常州溧阳嵘麒钢构资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "changzhou",
        "常州武进区翰林雅居花苑铁塔资源点稽核说明20240301.docx",
        "常州武进区翰林雅居花苑铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "changzhou",
        "常州新北区常州薛家蒋沟村铁塔资源点电费稽核说明20221201至20230228.docx",
        "常州新北区常州薛家蒋沟村铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "changzhou",
        "常州新北区孟河商贸广场搬迁位置点电费稽核说明20231101-20240229.docx",
        "常州新北区孟河商贸广场搬迁位置点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "changzhou",
        "常州钟楼区常州华林路资源点电费稽核说明20240101-20240331.docx",
        "常州钟楼区常州华林路资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "昆山新开河铁塔资源点（202404-10）稽核报告.docx",
        "昆山新开河铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州虎丘区电信易程股份资源点（2020.5.12-2022.8.23）稽核说明.docx",
        "苏州虎丘区电信易程股份资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州虎丘区市区山水印象资源点2021-06-232022-08-11.docx",
        "苏州虎丘区市区山水印象资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州市虎丘区电信龙景花园铁塔资源点202410稽核报告.docx",
        "苏州市虎丘区电信龙景花园铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州市相城区钻石路与珍珠湖路交叉西南铁塔资源点202411稽核报告.docx",
        "苏州市相城区钻石路与珍珠湖路交叉西南铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州吴中区光福柴巷村资源点情况说明.docx",
        "苏州吴中区光福柴巷村资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州吴中区市区联通华池街铁塔资源点稽核报告2024-10.docx",
        "苏州吴中区市区联通华池街铁塔资源点",
        "docx_filename_and_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州吴中区市区网新科技园铁塔资源点2022-06-292022-11-30.docx",
        "苏州吴中区市区网新科技园铁塔资源点",
        "docx_filename_and_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州相城区古巷村2栋资源点转供电202408稽核报告.docx",
        "苏州相城区古巷村2栋资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州相城区盛源科技铁塔资源点202411稽核报告.docx",
        "苏州相城区盛源科技铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "suzhou",
        "苏州相城区市区徐庄节点铁塔资源点2022721-2022119.docx",
        "苏州相城区市区徐庄节点铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "zhenjiang",
        "额定超标（三费修复后，截图稽核）.docx",
        "江苏镇江京口区五凤口高架入口位置点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "zhenjiang",
        "同比，（空调稽核）.docx",
        "镇江丹阳市豪华装饰铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "zhenjiang",
        "同比，环比（分摊变动稽核）.docx",
        "镇江润州区镇江跑马山公园南资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "zhenjiang",
        "同比，环比（机房站点，其他运营商退租，导致日均增大稽核）.docx",
        "镇江润州区镇扬汽渡铁塔资源点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "zhenjiang",
        "同比，环比，额定（添加设备稽核）.docx",
        "镇江大港新区大港韩桥路东位置点",
        "docx_heading",
    ),
    HistoricalSiteMapping(
        "taizhou",
        "额定功率超标.doc",
        "泰州兴化市金花苑农贸市场位置点",
        "legacy_doc_text",
    ),
    HistoricalSiteMapping(
        "taizhou",
        "日均电费同比超标.doc",
        "泰州竹泓镇北张村资源点",
        "legacy_doc_text",
    ),
    HistoricalSiteMapping(
        "taizhou",
        "日均电费同比环比超标.doc",
        "钓鱼双吉铁塔资源点",
        "legacy_doc_text",
    ),
    HistoricalSiteMapping(
        "taizhou",
        "日均电量环比超标.doc",
        "泰州老圩镇屯北村金高1-3组资源点",
        "legacy_doc_text",
    ),
    HistoricalSiteMapping(
        "taizhou",
        "日均电量同比超标.doc",
        "戴窑竹元铁塔资源点",
        "legacy_doc_text",
    ),
]


def _site_code(city_code: str, site_name: str) -> str:
    """在正式业务编码缺失时生成稳定且明显可识别的历史占位编码。"""
    digest = hashlib.sha256(f"{city_code}:{site_name}".encode()).hexdigest()[:16].upper()
    return f"HIST-{digest}"


def _validate_mapping_catalog() -> None:
    """阻止脚本目录自身出现重复文件映射或同城同名冲突。"""
    document_keys = [(item.city_code, item.filename) for item in HISTORICAL_SITE_MAPPINGS]
    if len(document_keys) != len(set(document_keys)):
        raise RuntimeError("历史材料映射包含重复的城市和文件名")
    if len(HISTORICAL_SITE_MAPPINGS) != 27:
        raise RuntimeError("历史材料映射必须完整覆盖27份文件")


def backfill(*, apply: bool) -> list[BackfillItemResult]:
    """预演或事务性写入报账点和历史报告关联。"""
    _validate_mapping_catalog()
    results: list[BackfillItemResult] = []
    with SessionLocal() as session:
        cities = {item.code: item for item in session.scalars(select(CityModel))}
        audit_logs = AuditLogRepository(session)
        for mapping in HISTORICAL_SITE_MAPPINGS:
            city = cities.get(mapping.city_code)
            if city is None:
                raise RuntimeError(f"数据库缺少城市：{mapping.city_code}")
            document = session.scalar(
                select(SourceDocumentModel).where(
                    SourceDocumentModel.city_id == city.id,
                    SourceDocumentModel.document_type == "historical_report",
                    SourceDocumentModel.original_filename == mapping.filename,
                    SourceDocumentModel.archived_at.is_(None),
                )
            )
            if document is None:
                results.append(
                    BackfillItemResult(
                        mapping.city_code,
                        mapping.filename,
                        None,
                        None,
                        None,
                        mapping.site_name,
                        "failed",
                        "数据库中未找到对应历史材料",
                    )
                )
                continue

            site = session.scalar(
                select(SiteModel).where(
                    SiteModel.city_id == city.id,
                    SiteModel.site_name == mapping.site_name,
                    SiteModel.status == "active",
                )
            )
            site_id = uuid5(SITE_NAMESPACE, f"{mapping.city_code}:{mapping.site_name}")
            site_code = _site_code(mapping.city_code, mapping.site_name)
            if site is None:
                site = SiteModel(
                    id=site_id,
                    city_id=city.id,
                    site_code=site_code,
                    site_name=mapping.site_name,
                    status="active",
                    extra_metadata={
                        "source": "historical_report_backfill",
                        "business_code_status": "unknown",
                        "extraction_source": mapping.extraction_source,
                    },
                )
                if apply:
                    session.add(site)
                    session.flush()
                    audit_logs.append(
                        city_id=city.id,
                        user_id=document.created_by,
                        action="site.created_from_history",
                        entity_type="site",
                        entity_id=str(site.id),
                        after_data={
                            "site_code": site.site_code,
                            "site_name": site.site_name,
                            "source_document_id": str(document.id),
                        },
                    )
            else:
                site_id = site.id
                site_code = site.site_code

            if document.site_id is not None and document.site_id != site_id:
                results.append(
                    BackfillItemResult(
                        mapping.city_code,
                        mapping.filename,
                        str(document.id),
                        str(site_id),
                        site_code,
                        mapping.site_name,
                        "failed",
                        "历史材料已经关联到另一个报账点，未自动覆盖",
                    )
                )
                continue

            already_linked = document.site_id == site_id
            if apply and not already_linked:
                previous_site_id = document.site_id
                document.site_id = site_id
                document.updated_at = datetime.now(UTC)
                document.version += 1
                audit_logs.append(
                    city_id=city.id,
                    user_id=document.created_by,
                    action="document.site_updated",
                    entity_type="source_document",
                    entity_id=str(document.id),
                    before_data={
                        "site_id": str(previous_site_id) if previous_site_id else None
                    },
                    after_data={
                        "site_id": str(site_id),
                        "backfill_source": "historical_report_site_catalog",
                    },
                )
            action = "skipped" if already_linked else ("linked" if apply else "would_link")
            results.append(
                BackfillItemResult(
                    mapping.city_code,
                    mapping.filename,
                    str(document.id),
                    str(site_id),
                    site_code,
                    mapping.site_name,
                    action,
                    "已存在正确关联" if already_linked else "报账点映射已复核",
                )
            )

        failures = [item for item in results if item.action == "failed"]
        if failures:
            session.rollback()
        elif apply:
            session.commit()
    return results


def parse_arguments() -> argparse.Namespace:
    """解析执行开关和复核清单位置。"""
    parser = argparse.ArgumentParser(description="回填历史材料报账点")
    parser.add_argument("--apply", action="store_true", help="实际写入；缺省仅预演")
    parser.add_argument("--output", type=Path, help="自定义JSON复核清单路径")
    return parser.parse_args()


def main() -> None:
    """执行回填并输出不含数据库密钥的JSON汇总。"""
    args = parse_arguments()
    results = backfill(apply=args.apply)
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    output = args.output or (
        root / "data" / "import-results" / f"historical-site-backfill-{timestamp}.json"
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "applied": args.apply,
        "mapping_count": len(HISTORICAL_SITE_MAPPINGS),
        "linked": sum(item.action == "linked" for item in results),
        "would_link": sum(item.action == "would_link" for item in results),
        "skipped": sum(item.action == "skipped" for item in results),
        "failed": sum(item.action == "failed" for item in results),
        "items": [asdict(item) for item in results],
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = {**payload, "items": "omitted", "report": str(output.resolve())}
    print(json.dumps(summary, ensure_ascii=False))
    if payload["failed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

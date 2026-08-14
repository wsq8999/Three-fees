from __future__ import annotations

"""历史报告目录发现与批量导入业务流程。"""

from dataclasses import asdict, dataclass
from pathlib import Path

from fastapi import UploadFile
from sqlalchemy.orm import Session

from app.core.exceptions import AppError, ConflictError
from app.core.identity import CurrentUser
from app.modules.cities.service import CityService
from app.modules.documents.parse_service import DocumentParsingService
from app.modules.documents.service import SourceDocumentService
from app.modules.documents.storage import LocalDocumentStorage

CITY_FOLDER_PREFIXES = {
    "南京": "nanjing",
    "无锡": "wuxi",
    "徐州": "xuzhou",
    "常州": "changzhou",
    "苏州": "suzhou",
    "南通": "nantong",
    "连云港": "lianyungang",
    "淮安": "huaian",
    "盐城": "yancheng",
    "扬州": "yangzhou",
    "镇江": "zhenjiang",
    "泰州": "taizhou",
    "宿迁": "suqian",
}
SUPPORTED_IMPORT_EXTENSIONS = {".doc", ".docx"}


@dataclass(frozen=True)
class DiscoveredDocument:
    """从目录层级识别出的待导入材料。"""

    source_path: Path
    relative_path: str
    city_code: str
    title: str


@dataclass(frozen=True)
class ImportItemResult:
    """单个文件的导入与解析结果。"""

    relative_path: str
    city_code: str
    import_status: str
    document_id: str | None
    parse_status: str | None
    error_code: str | None
    message: str | None


@dataclass(frozen=True)
class BatchImportReport:
    """可写入JSON文件的完整批量导入报告。"""

    source_root: str
    dry_run: bool
    discovered_count: int
    imported_count: int
    skipped_count: int
    failed_count: int
    parsed_count: int
    parse_failed_count: int
    items: list[ImportItemResult]

    def to_dict(self) -> dict[str, object]:
        """转换成不包含Path对象的JSON兼容结构。"""
        return asdict(self)


def discover_documents(source_root: Path) -> list[DiscoveredDocument]:
    """按一级城市目录发现Word材料，不根据正文做未经确认的城市猜测。"""
    root = source_root.resolve()
    if not root.is_dir():
        raise ValueError(f"历史报告目录不存在：{root}")
    discovered: list[DiscoveredDocument] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in SUPPORTED_IMPORT_EXTENSIONS:
            continue
        relative = path.relative_to(root)
        folder_name = relative.parts[0]
        city_code = next(
            (
                code
                for prefix, code in CITY_FOLDER_PREFIXES.items()
                if folder_name.startswith(prefix)
            ),
            "",
        )
        if not city_code:
            continue
        discovered.append(
            DiscoveredDocument(
                source_path=path,
                relative_path=relative.as_posix(),
                city_code=city_code,
                title=path.stem[:200],
            )
        )
    return discovered


class HistoricalReportBatchImporter:
    """复用单份上传和解析服务，避免批量入口形成第二套业务逻辑。"""

    def __init__(self, session: Session, storage: LocalDocumentStorage) -> None:
        self.session = session
        self.storage = storage

    def run(
        self,
        *,
        source_root: Path,
        user: CurrentUser,
        apply: bool,
        parse_documents: bool = True,
    ) -> BatchImportReport:
        """发现全部文件；只有显式apply时才复制文件并写入数据库。"""
        discovered = discover_documents(source_root)
        if not apply:
            return self._build_report(source_root, True, discovered, [])

        results: list[ImportItemResult] = []
        for item in discovered:
            city = CityService(self.session).get_context(item.city_code)
            if city is None:
                results.append(
                    ImportItemResult(
                        relative_path=item.relative_path,
                        city_code=item.city_code,
                        import_status="failed",
                        document_id=None,
                        parse_status=None,
                        error_code="city_not_configured",
                        message="目录对应城市未在数据库启用",
                    )
                )
                continue
            try:
                with item.source_path.open("rb") as source:
                    upload = UploadFile(
                        file=source,
                        size=item.source_path.stat().st_size,
                        filename=item.source_path.name,
                    )
                    document = SourceDocumentService(self.session, self.storage).create(
                        city=city,
                        user=user,
                        upload=upload,
                        document_type="historical_report",
                        title=item.title,
                        site_id=None,
                        task_id=None,
                        ingestion_method="batch_import",
                    )
                parse_status = None
                error_code = None
                message = None
                if parse_documents:
                    parse_run = DocumentParsingService(
                        self.session, self.storage
                    ).create_and_execute(document.id, city, user)
                    parse_status = parse_run.status
                    error_code = parse_run.error_code
                    message = parse_run.error_message
                results.append(
                    ImportItemResult(
                        relative_path=item.relative_path,
                        city_code=item.city_code,
                        import_status="imported",
                        document_id=document.id,
                        parse_status=parse_status,
                        error_code=error_code,
                        message=message,
                    )
                )
            except ConflictError as exc:
                self.session.rollback()
                results.append(
                    ImportItemResult(
                        relative_path=item.relative_path,
                        city_code=item.city_code,
                        import_status="skipped",
                        document_id=None,
                        parse_status=None,
                        error_code=exc.code,
                        message=exc.detail,
                    )
                )
            except (AppError, OSError) as exc:
                self.session.rollback()
                error_code = exc.code if isinstance(exc, AppError) else "file_read_failed"
                message = exc.detail if isinstance(exc, AppError) else str(exc)
                results.append(
                    ImportItemResult(
                        relative_path=item.relative_path,
                        city_code=item.city_code,
                        import_status="failed",
                        document_id=None,
                        parse_status=None,
                        error_code=error_code,
                        message=message[:500],
                    )
                )
        return self._build_report(source_root, False, discovered, results)

    def _build_report(
        self,
        source_root: Path,
        dry_run: bool,
        discovered: list[DiscoveredDocument],
        results: list[ImportItemResult],
    ) -> BatchImportReport:
        """汇总导入、跳过和解析结果。"""
        return BatchImportReport(
            source_root=str(source_root.resolve()),
            dry_run=dry_run,
            discovered_count=len(discovered),
            imported_count=sum(item.import_status == "imported" for item in results),
            skipped_count=sum(item.import_status == "skipped" for item in results),
            failed_count=sum(item.import_status == "failed" for item in results),
            parsed_count=sum(item.parse_status == "completed" for item in results),
            parse_failed_count=sum(item.parse_status == "failed" for item in results),
            items=results,
        )

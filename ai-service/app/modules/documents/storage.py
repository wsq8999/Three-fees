from __future__ import annotations

"""本地文件存储适配器，数据库只保存可迁移的相对存储键。"""

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from uuid import UUID
from zipfile import ZipFile, is_zipfile

from fastapi import UploadFile

from app.core.config import get_settings
from app.core.exceptions import AppError, ConflictError, ResourceNotFoundError

ALLOWED_MEDIA_TYPES = {
    ".doc": "application/msword",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".jpeg": "image/jpeg",
    ".jpg": "image/jpeg",
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".webp": "image/webp",
}
EMBEDDED_IMAGE_MEDIA_TYPES = {
    ".bmp": "image/bmp",
    ".emf": "image/emf",
    ".gif": "image/gif",
    ".jpeg": "image/jpeg",
    ".jpg": "image/jpeg",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".tif": "image/tiff",
    ".tiff": "image/tiff",
    ".webp": "image/webp",
    ".wmf": "image/wmf",
}


@dataclass(frozen=True)
class StoredDocumentFile:
    """一次成功落盘后写入数据库的不可变文件元数据。"""

    original_filename: str
    media_type: str
    size_bytes: int
    sha256: str
    storage_key: str


@dataclass(frozen=True)
class StoredAssetFile:
    """从文档中提取并落盘的图片资产信息。"""

    storage_key: str
    media_type: str
    size_bytes: int


@dataclass(frozen=True)
class StoredReportFile:
    """审核通过后生成的正式Word文件元数据。"""

    storage_key: str
    size_bytes: int
    sha256: str


class LocalDocumentStorage:
    """以城市和文档ID分目录保存文件，后续可替换为对象存储实现。"""

    def __init__(self, root: Path | None = None, max_size_bytes: int | None = None) -> None:
        settings = get_settings()
        self.root = root.resolve() if root else settings.storage_root_path()
        self.max_size_bytes = max_size_bytes or settings.upload_size_limit_bytes()

    def save(self, upload: UploadFile, city_code: str, document_id: UUID) -> StoredDocumentFile:
        """流式保存并校验文件，避免一次性把大文件读入内存。"""
        original_filename = Path(upload.filename or "").name.strip()
        suffix = Path(original_filename).suffix.lower()
        if not original_filename or suffix not in ALLOWED_MEDIA_TYPES:
            raise AppError(
                status=400,
                code="unsupported_document_type",
                title="不支持的文件类型",
                detail="仅支持 DOC、DOCX、PDF、PNG、JPG、JPEG 和 WEBP 文件",
            )
        if len(original_filename) > 255:
            raise AppError(
                status=400,
                code="filename_too_long",
                title="文件名过长",
                detail="文件名长度不能超过255个字符",
            )

        target_dir = (self.root / city_code / str(document_id)).resolve()
        self._ensure_within_root(target_dir)
        target_dir.mkdir(parents=True, exist_ok=False)
        target = target_dir / f"original{suffix}"
        partial = target_dir / f"original{suffix}.part"
        digest = sha256()
        size = 0
        try:
            with partial.open("wb") as output:
                while chunk := upload.file.read(1024 * 1024):
                    size += len(chunk)
                    if size > self.max_size_bytes:
                        raise AppError(
                            status=413,
                            code="file_too_large",
                            title="文件过大",
                            detail=f"单个文件不能超过{self.max_size_bytes // 1024 // 1024}MB",
                        )
                    digest.update(chunk)
                    output.write(chunk)
            if size == 0:
                raise AppError(
                    status=400,
                    code="empty_file",
                    title="文件内容为空",
                    detail="不能上传空文件",
                )
            self._validate_file_signature(partial, suffix)
            partial.replace(target)
        except Exception:
            # 失败时只清理本次UUID目录中的临时内容，不触碰其他材料。
            partial.unlink(missing_ok=True)
            target.unlink(missing_ok=True)
            target_dir.rmdir()
            raise

        storage_key = target.relative_to(self.root).as_posix()
        return StoredDocumentFile(
            original_filename=original_filename,
            media_type=ALLOWED_MEDIA_TYPES[suffix],
            size_bytes=size,
            sha256=digest.hexdigest(),
            storage_key=storage_key,
        )

    def resolve(self, storage_key: str) -> Path:
        """把数据库相对键解析成受根目录约束的现有文件。"""
        path = (self.root / storage_key).resolve()
        self._ensure_within_root(path)
        if not path.is_file():
            raise ResourceNotFoundError("材料文件不存在")
        return path

    def save_asset(
        self,
        *,
        city_code: str,
        document_id: UUID,
        parse_run_id: UUID,
        sequence_no: int,
        source_name: str,
        content: bytes,
    ) -> StoredAssetFile:
        """把DOCX内嵌图片保存到本次解析运行的独立目录。"""
        suffix = Path(source_name).suffix.lower()
        if suffix not in EMBEDDED_IMAGE_MEDIA_TYPES or not content:
            raise AppError(
                status=422,
                code="unsupported_embedded_image",
                title="内嵌图片无法解析",
                detail=f"不支持的内嵌图片格式：{suffix or '无扩展名'}",
            )
        asset_dir = (
            self.root / city_code / str(document_id) / "assets" / str(parse_run_id)
        ).resolve()
        self._ensure_within_root(asset_dir)
        asset_dir.mkdir(parents=True, exist_ok=True)
        target = asset_dir / f"{sequence_no:04d}{suffix}"
        if target.exists():
            raise ConflictError("解析图片序号发生冲突")
        target.write_bytes(content)
        return StoredAssetFile(
            storage_key=target.relative_to(self.root).as_posix(),
            media_type=EMBEDDED_IMAGE_MEDIA_TYPES[suffix],
            size_bytes=len(content),
        )

    def save_report(
        self,
        *,
        city_code: str,
        report_id: UUID,
        version_no: int,
        content: bytes,
    ) -> StoredReportFile:
        """原子保存已通过审核的DOCX；同一版本禁止静默覆盖。"""
        target_dir = (self.root / city_code / "reports" / str(report_id)).resolve()
        self._ensure_within_root(target_dir)
        target_dir.mkdir(parents=True, exist_ok=True)
        target = target_dir / f"v{version_no}.docx"
        partial = target_dir / f"v{version_no}.docx.part"
        if target.exists():
            raise ConflictError("该报告版本已经生成过正式Word")
        try:
            partial.write_bytes(content)
            self._validate_file_signature(partial, ".docx")
            partial.replace(target)
        except Exception:
            partial.unlink(missing_ok=True)
            raise
        return StoredReportFile(
            storage_key=target.relative_to(self.root).as_posix(),
            size_bytes=len(content),
            sha256=sha256(content).hexdigest(),
        )

    def discard_asset_group(self, city_code: str, document_id: UUID, parse_run_id: UUID) -> None:
        """解析失败时只清理本次运行创建的图片资产。"""
        asset_dir = (
            self.root / city_code / str(document_id) / "assets" / str(parse_run_id)
        ).resolve()
        self._ensure_within_root(asset_dir)
        if not asset_dir.exists():
            return
        for child in asset_dir.iterdir():
            if child.is_file():
                child.unlink()
        asset_dir.rmdir()

    def discard(self, storage_key: str) -> None:
        """仅在数据库创建失败时撤销刚保存的文件。"""
        path = (self.root / storage_key).resolve()
        self._ensure_within_root(path)
        path.unlink(missing_ok=True)
        if path.parent != self.root and path.parent.exists():
            path.parent.rmdir()

    def _ensure_within_root(self, path: Path) -> None:
        """拒绝任何可能逃逸出配置存储根目录的路径。"""
        if not path.is_relative_to(self.root):
            raise AppError(
                status=400,
                code="invalid_storage_path",
                title="存储路径无效",
                detail="材料存储路径超出允许范围",
            )

    def _validate_file_signature(self, path: Path, suffix: str) -> None:
        """校验常见文件签名，阻止仅修改扩展名的伪装文件。"""
        with path.open("rb") as source:
            header = source.read(16)
        valid = {
            ".doc": header.startswith(bytes.fromhex("D0CF11E0A1B11AE1")),
            ".pdf": header.startswith(b"%PDF-"),
            ".png": header.startswith(b"\x89PNG\r\n\x1a\n"),
            ".jpg": header.startswith(b"\xff\xd8\xff"),
            ".jpeg": header.startswith(b"\xff\xd8\xff"),
            ".webp": header.startswith(b"RIFF") and header[8:12] == b"WEBP",
        }
        if suffix == ".docx":
            valid_docx = is_zipfile(path)
            if valid_docx:
                with ZipFile(path) as archive:
                    names = set(archive.namelist())
                valid_docx = {"[Content_Types].xml", "word/document.xml"} <= names
            if valid_docx:
                return
        elif valid.get(suffix, False):
            return
        raise AppError(
            status=400,
            code="invalid_file_content",
            title="文件内容无效",
            detail="文件内容与扩展名不匹配或文件已经损坏",
        )


def get_document_storage() -> LocalDocumentStorage:
    """为接口提供可替换的文件存储依赖。"""
    return LocalDocumentStorage()

from __future__ import annotations

"""基于OOXML标准库的DOCX确定性解析器，不调用AI也不改写原文件。"""

import posixpath
import re
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree
from zipfile import BadZipFile, ZipFile

WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
W = f"{{{WORD_NS}}}"
R = f"{{{OFFICE_REL_NS}}}"
PR = f"{{{PACKAGE_REL_NS}}}"
MAX_XML_BYTES = 20 * 1024 * 1024
MAX_ASSET_BYTES = 25 * 1024 * 1024
MAX_ELEMENTS = 5000
KNOWN_SECTION_PATTERN = re.compile(
    r"^(?:[一二三四五六七八九十]+[、.．])?"
    r"(?:情况说明|排查分析|稽核结论|审核结论|分析结论)$"
)


class DocumentParseError(Exception):
    """带稳定错误码的文档解析异常。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class ImagePayload:
    """等待存储适配器落盘的内嵌图片。"""

    source_name: str
    content: bytes


@dataclass(frozen=True)
class ParsedDocumentElement:
    """与数据库无关的顺序化解析结果。"""

    sequence_no: int
    element_type: str
    section_title: str | None
    content_text: str | None
    content_data: dict[str, object] = field(default_factory=dict)
    source_locator: dict[str, object] = field(default_factory=dict)
    image: ImagePayload | None = None


class DocxParser:
    """提取DOCX正文段落、表格和图片，并保留它们的原始先后顺序。"""

    name = "docx_ooxml"
    version = "1.0.0"

    def parse(self, path: Path) -> list[ParsedDocumentElement]:
        """解析一个经过上传校验的DOCX文件。"""
        try:
            with ZipFile(path) as archive:
                document_root = ElementTree.fromstring(
                    self._read_limited(archive, "word/document.xml", MAX_XML_BYTES)
                )
                relationships = self._read_relationships(archive)
                styles = self._read_styles(archive)
                return self._parse_body(archive, document_root, relationships, styles)
        except DocumentParseError:
            raise
        except (BadZipFile, ElementTree.ParseError, KeyError) as exc:
            raise DocumentParseError("invalid_docx_structure", "DOCX内部结构无法解析") from exc

    def _parse_body(
        self,
        archive: ZipFile,
        document_root: ElementTree.Element,
        relationships: dict[str, str],
        styles: dict[str, str],
    ) -> list[ParsedDocumentElement]:
        """遍历正文顶层节点，生成稳定顺序号。"""
        body = document_root.find(f"{W}body")
        if body is None:
            raise DocumentParseError("missing_document_body", "DOCX缺少正文内容")

        elements: list[ParsedDocumentElement] = []
        current_section: str | None = None
        paragraph_index = 0
        table_index = 0
        first_text = True

        for body_index, child in enumerate(body):
            if child.tag == f"{W}p":
                paragraph_index += 1
                text = self._extract_text(child)
                style_id = self._paragraph_style_id(child)
                style_name = styles.get(style_id, style_id) if style_id else None
                if text:
                    is_heading = self._is_heading(text, style_name, first_text)
                    if is_heading:
                        current_section = text
                    elements.append(
                        ParsedDocumentElement(
                            sequence_no=len(elements) + 1,
                            element_type="heading" if is_heading else "paragraph",
                            section_title=current_section,
                            content_text=text,
                            content_data={
                                "style_id": style_id,
                                "style_name": style_name,
                            },
                            source_locator={
                                "body_index": body_index,
                                "paragraph_index": paragraph_index,
                            },
                        )
                    )
                    first_text = False
                self._append_images(
                    archive=archive,
                    container=child,
                    relationships=relationships,
                    elements=elements,
                    section_title=current_section,
                    locator={
                        "body_index": body_index,
                        "paragraph_index": paragraph_index,
                    },
                )
            elif child.tag == f"{W}tbl":
                table_index += 1
                rows = self._table_rows(child)
                if rows:
                    elements.append(
                        ParsedDocumentElement(
                            sequence_no=len(elements) + 1,
                            element_type="table",
                            section_title=current_section,
                            content_text="\n".join(" | ".join(row) for row in rows),
                            content_data={"rows": rows},
                            source_locator={
                                "body_index": body_index,
                                "table_index": table_index,
                            },
                        )
                    )
                self._append_images(
                    archive=archive,
                    container=child,
                    relationships=relationships,
                    elements=elements,
                    section_title=current_section,
                    locator={"body_index": body_index, "table_index": table_index},
                )
            self._check_element_limit(elements)
        return elements

    def _append_images(
        self,
        *,
        archive: ZipFile,
        container: ElementTree.Element,
        relationships: dict[str, str],
        elements: list[ParsedDocumentElement],
        section_title: str | None,
        locator: dict[str, object],
    ) -> None:
        """提取当前段落或表格引用的图片，过滤非图片关系和回退重复引用。"""
        seen_relationships: set[str] = set()
        for node in container.iter():
            relationship_id = node.attrib.get(f"{R}embed") or node.attrib.get(f"{R}id")
            if not relationship_id or relationship_id in seen_relationships:
                continue
            package_path = relationships.get(relationship_id)
            if package_path is None:
                continue
            seen_relationships.add(relationship_id)
            content = self._read_limited(archive, package_path, MAX_ASSET_BYTES)
            source_name = Path(package_path).name
            elements.append(
                ParsedDocumentElement(
                    sequence_no=len(elements) + 1,
                    element_type="image",
                    section_title=section_title,
                    content_text=None,
                    content_data={"source_name": source_name, "size_bytes": len(content)},
                    source_locator={
                        **locator,
                        "relationship_id": relationship_id,
                        "package_path": package_path,
                    },
                    image=ImagePayload(source_name=source_name, content=content),
                )
            )
            self._check_element_limit(elements)

    def _read_relationships(self, archive: ZipFile) -> dict[str, str]:
        """只保留内部图片关系，并规范成安全的ZIP包路径。"""
        relationship_path = "word/_rels/document.xml.rels"
        if relationship_path not in archive.namelist():
            return {}
        root = ElementTree.fromstring(self._read_limited(archive, relationship_path, MAX_XML_BYTES))
        relationships: dict[str, str] = {}
        for item in root.findall(f"{PR}Relationship"):
            if item.attrib.get("TargetMode") == "External":
                continue
            if not item.attrib.get("Type", "").endswith("/image"):
                continue
            relationship_id = item.attrib.get("Id")
            target = item.attrib.get("Target")
            if not relationship_id or not target:
                continue
            # 关系目标既可能是相对的 media/x.png，也可能是包根绝对路径 /word/media/x.png。
            package_path = (
                posixpath.normpath(target.lstrip("/"))
                if target.startswith("/")
                else posixpath.normpath(posixpath.join("word", target))
            )
            if not package_path.startswith("word/") or ".." in package_path.split("/"):
                raise DocumentParseError("unsafe_ooxml_path", "DOCX包含不安全的图片路径")
            relationships[relationship_id] = package_path
        return relationships

    def _read_styles(self, archive: ZipFile) -> dict[str, str]:
        """读取样式ID到可读名称的映射，便于后续人工核对标题识别。"""
        styles_path = "word/styles.xml"
        if styles_path not in archive.namelist():
            return {}
        root = ElementTree.fromstring(self._read_limited(archive, styles_path, MAX_XML_BYTES))
        result: dict[str, str] = {}
        for style in root.findall(f"{W}style"):
            style_id = style.attrib.get(f"{W}styleId")
            name_node = style.find(f"{W}name")
            style_name = name_node.attrib.get(f"{W}val") if name_node is not None else None
            if style_id and style_name:
                result[style_id] = style_name
        return result

    def _read_limited(self, archive: ZipFile, name: str, limit: int) -> bytes:
        """在解压前检查声明大小，降低压缩炸弹和超大元素风险。"""
        try:
            info = archive.getinfo(name)
        except KeyError as exc:
            raise DocumentParseError("missing_ooxml_part", f"DOCX缺少必要内容：{name}") from exc
        if info.file_size > limit:
            raise DocumentParseError("ooxml_part_too_large", f"DOCX内部内容过大：{name}")
        content = archive.read(name)
        if len(content) > limit:
            raise DocumentParseError("ooxml_part_too_large", f"DOCX内部内容过大：{name}")
        return content

    def _extract_text(self, container: ElementTree.Element) -> str:
        """按XML顺序提取文字、换行和制表符。"""
        parts: list[str] = []
        for node in container.iter():
            if node.tag == f"{W}t" and node.text:
                parts.append(node.text)
            elif node.tag == f"{W}tab":
                parts.append("\t")
            elif node.tag in {f"{W}br", f"{W}cr"}:
                parts.append("\n")
        return "".join(parts).strip()

    def _paragraph_style_id(self, paragraph: ElementTree.Element) -> str | None:
        """返回段落样式ID；缺少样式时保持为空。"""
        style = paragraph.find(f"{W}pPr/{W}pStyle")
        return style.attrib.get(f"{W}val") if style is not None else None

    def _table_rows(self, table: ElementTree.Element) -> list[list[str]]:
        """把Word表格转成保序二维数组，同时保留可全文检索的扁平文字。"""
        rows: list[list[str]] = []
        for row in table.findall(f"{W}tr"):
            cells = [self._extract_text(cell) for cell in row.findall(f"{W}tc")]
            if any(cells):
                rows.append(cells)
        return rows

    def _is_heading(self, text: str, style_name: str | None, first_text: bool) -> bool:
        """结合Word样式和少量稳定中文标题规则识别章节。"""
        normalized_style = (style_name or "").lower()
        if normalized_style.startswith(("heading", "title")) or "标题" in normalized_style:
            return True
        compact_text = re.sub(r"\s+", "", text)
        if KNOWN_SECTION_PATTERN.fullmatch(compact_text):
            return True
        return first_text and len(compact_text) <= 120 and compact_text.endswith(("报告", "说明"))

    def _check_element_limit(self, elements: list[ParsedDocumentElement]) -> None:
        """限制单文档元素数量，避免异常文件消耗过多数据库资源。"""
        if len(elements) > MAX_ELEMENTS:
            raise DocumentParseError("too_many_document_elements", "单个文档元素数量超过限制")

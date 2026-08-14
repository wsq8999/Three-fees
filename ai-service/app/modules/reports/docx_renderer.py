from __future__ import annotations

"""在不破坏原始DOCX结构的前提下追加审核通过的AI稽核正文。"""

from io import BytesIO
from xml.sax.saxutils import escape
from zipfile import ZIP_DEFLATED, BadZipFile, ZipFile

from app.core.exceptions import AppError


def _text_run(text: str, *, font: str, size: int, bold: bool = False) -> str:
    """生成一个包含中西文字体声明的WordprocessingML文本片段。"""
    bold_xml = "<w:b/>" if bold else ""
    return (
        "<w:r><w:rPr>"
        f'<w:rFonts w:ascii="{font}" w:eastAsia="{font}" w:hAnsi="{font}"/>'
        f'{bold_xml}<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>'
        f'</w:rPr><w:t xml:space="preserve">{escape(text)}</w:t></w:r>'
    )


def _title_paragraph(title: str) -> str:
    """生成居中的黑体报告标题。"""
    return (
        '<w:p><w:pPr><w:jc w:val="center"/><w:spacing w:after="240"/></w:pPr>'
        f"{_text_run(title, font='黑体', size=32, bold=True)}</w:p>"
    )


def _heading_paragraph(title: str) -> str:
    """生成每个固定章节的黑体小标题。"""
    return (
        '<w:p><w:pPr><w:spacing w:before="180" w:after="100"/></w:pPr>'
        f"{_text_run(title, font='黑体', size=28, bold=True)}</w:p>"
    )


def _body_paragraph(content: str) -> str:
    """生成首行缩进两字符、1.5倍行距的宋体正文。"""
    return (
        '<w:p><w:pPr><w:ind w:firstLineChars="200"/>'
        '<w:spacing w:line="360" w:lineRule="auto" w:after="100"/></w:pPr>'
        f"{_text_run(content, font='宋体', size=24)}</w:p>"
    )


def render_report_docx(
    source_content: bytes, title: str, sections: list[dict[str, object]]
) -> bytes:
    """复制原DOCX全部包部件，仅在正文末尾追加一页已审核的AI报告。"""
    try:
        source_stream = BytesIO(source_content)
        output_stream = BytesIO()
        with ZipFile(source_stream, "r") as source:
            names = set(source.namelist())
            if {"[Content_Types].xml", "word/document.xml"} - names:
                raise ValueError("缺少DOCX核心部件")
            document_xml = source.read("word/document.xml").decode("utf-8")
            body_end = document_xml.rfind("</w:body>")
            if body_end < 0:
                raise ValueError("正文XML结构不完整")
            section_index = document_xml.rfind("<w:sectPr", 0, body_end)
            insertion_index = section_index if section_index >= 0 else body_end

            fragments = ['<w:p><w:r><w:br w:type="page"/></w:r></w:p>', _title_paragraph(title)]
            for section in sections:
                section_title = str(section.get("title", "")).strip()
                content = str(section.get("content", "")).strip()
                fragments.append(_heading_paragraph(section_title))
                # 保留用户在编辑框中输入的自然段；空行不生成无意义段落。
                fragments.extend(
                    _body_paragraph(line.strip()) for line in content.splitlines() if line.strip()
                )
            document_xml = (
                document_xml[:insertion_index] + "".join(fragments) + document_xml[insertion_index:]
            )
            with ZipFile(output_stream, "w", compression=ZIP_DEFLATED) as output:
                for info in source.infolist():
                    payload = (
                        document_xml.encode("utf-8")
                        if info.filename == "word/document.xml"
                        else source.read(info.filename)
                    )
                    output.writestr(info, payload)
        return output_stream.getvalue()
    except (BadZipFile, KeyError, OSError, UnicodeError, ValueError) as exc:
        raise AppError(
            status=422,
            code="report_docx_render_failed",
            title="正式Word生成失败",
            detail="本次原始DOCX结构无法用于生成正式报告，请检查文件是否损坏",
        ) from exc

"""批量导入现有历史稽核报告，并输出可复核的JSON结果清单。"""

# 独立脚本必须先把backend加入搜索路径，后端模块导入因此有意位于路径设置之后。
# ruff: noqa: E402

import argparse
import json
import sys
from datetime import UTC, datetime
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "backend"))

from app.core.identity import DEVELOPMENT_USER
from app.db import registry as _model_registry  # noqa: F401
from app.db.session import SessionLocal
from app.modules.documents.batch_import import (
    HistoricalReportBatchImporter,
)
from app.modules.documents.storage import LocalDocumentStorage


def parse_arguments() -> argparse.Namespace:
    """解析目录、执行开关和输出位置。"""
    parser = argparse.ArgumentParser(description="批量导入历史稽核报告")
    parser.add_argument("source", type=Path, help="历史报告根目录")
    parser.add_argument(
        "--apply",
        action="store_true",
        help="实际复制文件并写入数据库；缺省仅预览",
    )
    parser.add_argument(
        "--skip-parse",
        action="store_true",
        help="只导入原文件，不立即解析DOCX",
    )
    parser.add_argument("--output", type=Path, help="自定义JSON结果文件")
    return parser.parse_args()


def main() -> None:
    """执行预览或正式导入，并始终保存完整结果清单。"""
    args = parse_arguments()
    with SessionLocal() as session:
        report = HistoricalReportBatchImporter(session, LocalDocumentStorage()).run(
            source_root=args.source,
            user=DEVELOPMENT_USER,
            apply=args.apply,
            parse_documents=not args.skip_parse,
        )
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    output = args.output or root / "data" / "import-results" / f"historical-{timestamp}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report.to_dict(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    summary = {
        "report": str(output.resolve()),
        "dry_run": report.dry_run,
        "discovered": report.discovered_count,
        "imported": report.imported_count,
        "skipped": report.skipped_count,
        "failed": report.failed_count,
        "parsed": report.parsed_count,
        "parse_failed": report.parse_failed_count,
    }
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()

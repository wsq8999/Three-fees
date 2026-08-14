"""批量把已解析、已关联报账点的历史DOCX结构化为长期案例记忆。"""

import argparse
import sys
from pathlib import Path

from sqlalchemy import select

ROOT = Path(__file__).resolve().parents[1]
BACKEND_ROOT = ROOT / "backend"
# 脚本位于仓库scripts目录，显式加入backend后才能复用正式业务服务而不复制实现。
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.core.identity import DEVELOPMENT_USER  # noqa: E402
from app.db import registry as _model_registry  # noqa: E402, F401
from app.db.session import SessionLocal  # noqa: E402
from app.modules.audit_cases.model import AuditCaseModel  # noqa: E402
from app.modules.audit_cases.service import AuditCaseService  # noqa: E402
from app.modules.cities.model import CityModel  # noqa: E402
from app.modules.cities.schemas import CityContext  # noqa: E402
from app.modules.documents.model import SourceDocumentModel  # noqa: E402


def parse_args() -> argparse.Namespace:
    """声明可恢复、低风险的批处理参数；默认不写数据库也不调用收费模型。"""
    parser = argparse.ArgumentParser(description="构建历史稽核案例")
    parser.add_argument("--apply", action="store_true", help="实际调用模型并写入数据库")
    parser.add_argument("--city", help="只处理指定城市代码，例如 suzhou")
    parser.add_argument("--document-id", help="只处理指定历史报告UUID")
    parser.add_argument("--limit", type=int, default=0, help="最多处理数量，0表示不限制")
    parser.add_argument("--retry-failed", action="store_true", help="重新处理已经失败的案例")
    return parser.parse_args()


def main() -> int:
    """默认只预览；--apply 后逐份提交，失败不会阻断其他历史报告。

    可重复执行是脚本的重要性质：ready案例自动跳过，pending案例继续处理，failed案例
    只有显式传入 ``--retry-failed`` 才重试，避免配置错误时反复消耗模型额度。
    """
    args = parse_args()
    with SessionLocal() as session:
        # 旧DOC解析失败、未关联报账点或被归档的材料都不具备案例构建资格。
        conditions = [
            SourceDocumentModel.document_type == "historical_report",
            SourceDocumentModel.status == "parsed",
            SourceDocumentModel.site_id.is_not(None),
        ]
        if args.city:
            conditions.append(CityModel.code == args.city.strip().lower())
        if args.document_id:
            conditions.append(SourceDocumentModel.id == args.document_id)
        # 外连接已有案例，使“哪些需要处理”的判断在一次查询中完成。
        statement = (
            select(SourceDocumentModel, CityModel, AuditCaseModel)
            .join(CityModel, CityModel.id == SourceDocumentModel.city_id)
            .outerjoin(
                AuditCaseModel,
                AuditCaseModel.source_document_id == SourceDocumentModel.id,
            )
            .where(*conditions)
            .order_by(CityModel.id, SourceDocumentModel.created_at)
        )
        # ready记录不重建；这样脚本中断后可直接再次执行并从断点继续。
        rows = [
            row
            for row in session.execute(statement).all()
            if row[2] is None
            or (args.retry_failed and row[2].status == "failed")
            or row[2].status == "pending"
        ]
        if args.limit > 0:
            rows = rows[: args.limit]
        print(f"待结构化历史报告：{len(rows)} 份；模式：{'写入' if args.apply else '预览'}")
        if not args.apply:
            # 预览模式不调用Kimi、不修改案例状态，适合正式批量执行前人工核对范围。
            for document, city, _ in rows:
                print(f"[{city.name}] {document.title} ({document.id})")
            return 0

        success = 0
        failed = 0
        for index, (document, city, _) in enumerate(rows, start=1):
            # 事务回滚会使ORM对象过期，先保存输出所需的普通字符串。
            city_name = city.name
            document_title = document.title
            context = CityContext(id=city.id, code=city.code, name=city.name)
            try:
                result = AuditCaseService(session).analyze(
                    str(document.id), context, DEVELOPMENT_USER
                )
                success += 1
                print(
                    f"[{index}/{len(rows)}] 成功 {city_name}｜{result.source_title}｜"
                    f"{result.reason_category or '原因未明确'}"
                )
            except Exception as exc:  # 单份失败已经由服务记录为failed。
                # 显式回滚保证当前Session可以继续下一份，不让一份坏材料阻断整个城市。
                failed += 1
                session.rollback()
                print(f"[{index}/{len(rows)}] 失败 {city_name}｜{document_title}｜{exc}")
        print(f"完成：成功 {success}，失败 {failed}")
        return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

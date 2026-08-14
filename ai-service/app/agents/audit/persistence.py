from __future__ import annotations

"""生产环境LangGraph检查点连接生命周期。"""

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from app.agents.audit.graph import build_audit_graph
from app.core.config import get_settings


def _postgres_saver_class():
    """延迟导入可选依赖，让缺少安装时返回清晰的本地操作提示。"""
    try:
        from langgraph.checkpoint.postgres import PostgresSaver
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "缺少langgraph-checkpoint-postgres依赖，请先在backend目录执行uv sync --all-groups"
        ) from exc
    return PostgresSaver


def initialize_checkpoint_storage() -> None:
    """在应用接收请求前完成LangGraph内部表迁移。

    PostgreSQL Saver的setup包含并发索引DDL。它若在某次业务请求已经开启事务后执行，
    会等待该业务事务结束，而业务事务又在等待setup返回，形成相互等待。因此初始化
    必须放在应用启动边界，每次分析运行只打开Saver，绝不重复执行DDL。
    """
    settings = get_settings()
    postgres_saver = _postgres_saver_class()
    with postgres_saver.from_conn_string(settings.langgraph_postgres_uri()) as checkpointer:
        checkpointer.setup()


@contextmanager
def open_persistent_audit_graph() -> Iterator[Any]:
    """为一次服务操作打开PostgreSQL检查点图并在结束后释放连接。

    检查点和业务表共用数据库，但连接的 ``search_path`` 固定为 ``langgraph`` Schema。
    框架内部表在FastAPI启动阶段统一初始化；这里不执行DDL，避免和已经开始的业务事务
    相互等待。业务迁移只创建Schema，不复制框架私有表结构，避免版本升级时定义漂移。
    """
    settings = get_settings()
    postgres_saver = _postgres_saver_class()
    with postgres_saver.from_conn_string(settings.langgraph_postgres_uri()) as checkpointer:
        yield build_audit_graph(checkpointer)

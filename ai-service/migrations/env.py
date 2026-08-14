"""Alembic运行环境，从应用配置安全读取数据库连接。"""

from logging.config import fileConfig

from alembic import context
from sqlalchemy import create_engine, pool

from app.core.config import get_settings
from app.db.registry import Base

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

settings = get_settings()
config.set_main_option(
    "sqlalchemy.url", settings.sqlalchemy_url().render_as_string(hide_password=False)
)
target_metadata = Base.metadata


def include_name(name: str | None, type_: str, parent_names: dict[str, str | None]) -> bool:
    """只比较业务schema，并忽略由Alembic自身维护的版本表。"""
    if type_ == "schema":
        return name == settings.db_schema
    if type_ == "table":
        return name != "alembic_version"
    return True


def run_migrations_offline() -> None:
    """生成离线SQL；生产发布前可用于人工审阅迁移内容。"""
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        include_schemas=True,
        include_name=include_name,
        version_table_schema=settings.db_schema,
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    """使用短连接执行在线迁移，不复用Web服务连接池。"""
    connectable = create_engine(
        settings.sqlalchemy_url(),
        poolclass=pool.NullPool,
        # 让audit始终作为显式schema反射，保留跨表外键的schema限定。
        connect_args={"options": "-csearch_path=pg_catalog"},
    )
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            include_schemas=True,
            include_name=include_name,
            version_table_schema=settings.db_schema,
            compare_type=True,
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()

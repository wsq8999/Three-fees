from __future__ import annotations

"""应用配置，所有环境差异都通过环境变量注入。"""

import re
from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import URL

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
BACKEND_ROOT = Path(__file__).resolve().parents[2]
POSTGRES_IDENTIFIER_PATTERN = re.compile(r"^[a-z_][a-z0-9_]*$")


class Settings(BaseSettings):
    app_name: str = "江苏 13 市 AI 稽核助手"
    app_env: str = "development"
    app_log_level: str = "INFO"
    frontend_origin: str = "http://localhost:5173"
    db_host: str = "127.0.0.1"
    db_port: int = 5432
    db_name: str = "jiangsu_audit_agent"
    db_user: str = "jiangsu_audit_app"
    db_password: SecretStr = SecretStr("")
    db_schema: str = "audit"
    langgraph_schema: str = "langgraph"
    file_storage_root: Path = Path("data/uploads")
    max_upload_size_mb: int = 25
    ai_provider: str = "kimi"
    kimi_api_key: SecretStr = SecretStr("")
    kimi_base_url: str = "https://api.moonshot.cn/v1"
    kimi_model: str = "kimi-k3"
    kimi_extract_reasoning_effort: Literal["low", "high", "max"] = "low"
    kimi_judge_reasoning_effort: Literal["low", "high", "max"] = "high"
    kimi_timeout_seconds: float = 90.0

    @field_validator("db_schema", "langgraph_schema")
    @classmethod
    def validate_postgres_schema_name(cls, value: str) -> str:
        """限制Schema名称为安全标识符，避免将配置内容拼接进连接参数时产生注入风险。"""
        normalized = value.lower()
        if POSTGRES_IDENTIFIER_PATTERN.fullmatch(normalized) is None:
            raise ValueError("PostgreSQL Schema名称只能包含字母、数字和下划线，且不能以数字开头")
        return normalized

    # 固定从backend目录加载本地配置，使API、迁移和独立脚本从任意目录启动都一致。
    model_config = SettingsConfigDict(env_file=BACKEND_ROOT / ".env", extra="ignore")

    def sqlalchemy_url(self) -> URL:
        """用URL对象构造连接，避免密码特殊字符被错误解析或输出。"""
        return URL.create(
            drivername="postgresql+psycopg",
            username=self.db_user,
            password=self.db_password.get_secret_value(),
            host=self.db_host,
            port=self.db_port,
            database=self.db_name,
        )

    def langgraph_postgres_uri(self) -> str:
        """生成检查点专用连接串，并把LangGraph内部表隔离到独立Schema。

        业务表仍位于 ``audit`` Schema；检查点表只保存流程恢复所需状态。通过
        ``search_path`` 隔离两类数据，可以共用一个数据库而不会混淆业务表与框架表。
        """
        checkpoint_url = (
            self.sqlalchemy_url()
            .set(drivername="postgresql")
            .update_query_dict({"options": f"-csearch_path={self.langgraph_schema}"})
        )
        return checkpoint_url.render_as_string(hide_password=False)

    def upload_size_limit_bytes(self) -> int:
        """把便于配置的MB上限转换成文件流校验使用的字节数。"""
        return self.max_upload_size_mb * 1024 * 1024

    def storage_root_path(self) -> Path:
        """相对路径始终以仓库根目录解析，避免启动目录改变存储位置。"""
        if self.file_storage_root.is_absolute():
            return self.file_storage_root.resolve()
        return (REPOSITORY_ROOT / self.file_storage_root).resolve()


@lru_cache
def get_settings() -> Settings:
    return Settings()

# Three Fees AI Service

该服务只提供 `/internal/v1` 原子能力，不访问 MySQL，不保存会话，也不包含真实模型密钥或业务材料。
当前唯一 provider 是完全离线、确定性的 `fake`，用于验证 Java 编排与 JSON 契约。

## 运行

使用 Python 3.12，并在进程环境中设置 `AI_SERVICE_TOKEN`（至少 16 个字符）：

```powershell
python -m uvicorn app.main:app --host 127.0.0.1 --port 8100
```

不要把 8100 端口或 `/internal` 路由暴露到 IIS 公网入口。

## 验证

```powershell
python -m pytest
python -m ruff check .
python -m ruff format --check .
python -m scripts.export_schemas
```

最后一条命令必须在 `ai-service/` 目录执行，用于把五组请求/响应模型导出到
`../contracts/ai/v1/`。生成物仍需随契约测试一同提交。

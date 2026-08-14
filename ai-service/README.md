# 江苏 AI Agent Sidecar

本目录来自用户提供的 `jiangsu-audit-agent.zip`，作为 Three-fees 主系统的独立 AI sidecar 服务使用。

当前已接入主系统 `/reports/generate` 页面中的【分析图片】按钮：

- Three-fees Java 后端仍运行在 `8080`，前端只调用 Java 后端。
- Java 后端通过内部令牌调用本服务的 `POST /api/v1/report-image-analysis`。
- 本服务负责读取当前正文、业务事实和图片，调用 zip 项目中的 Kimi Provider，并返回结构化结果。
- 该流程不创建草稿、不写正式报告、不依赖本机 WPS/Word/Office。

## 本地启动

建议使用 Python 3.12+。

```powershell
cd D:\Three-fees\ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .
copy .env.example .env
```

本地联调可先使用 fake 模式：

```powershell
$env:AI_SERVICE_TOKEN="change-me-at-least-16-chars"
$env:AI_PROVIDER="fake"
$env:AI_INITIALIZE_CHECKPOINTS="false"
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8100
```

真实 Kimi 模式：

```powershell
$env:AI_SERVICE_TOKEN="change-me-at-least-16-chars"
$env:AI_PROVIDER="kimi"
$env:KIMI_API_KEY="你的KimiKey"
$env:AI_INITIALIZE_CHECKPOINTS="false"
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8100
```

Three-fees Java 后端需要使用相同令牌：

```powershell
$env:AI_SERVICE_ENABLED="true"
$env:AI_SERVICE_BASE_URL="http://127.0.0.1:8100"
$env:AI_SERVICE_TOKEN="change-me-at-least-16-chars"
```

## 说明

`AI_INITIALIZE_CHECKPOINTS=false` 只关闭 LangGraph/PostgreSQL 检查点初始化，适用于当前“分析图片”按钮。zip 原项目完整工作流如果需要启用 PostgreSQL、历史案例、文档管理等能力，可以再按原项目迁移和数据库配置启动。

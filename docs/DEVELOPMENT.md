# 开发指南

## 1. 必备工具

- JDK 21
- MySQL 8.0 或更高版本
- Node.js 24 + Corepack
- pnpm 11.9（由 Corepack 按 `frontend/package.json` 锁定）

## 2. 真实本地运行环境

本项目不再使用本地 Demo/H2 启动链路。开发和人工测试时使用：

- Spring Boot `dev` profile
- MySQL 数据库 `three_fees`
- Vue Vite 开发服务器
- Spring AI 直连 Kimi（不需要 FastAPI sidecar）

## 3. 后端环境变量

环境变量只在本机终端、IDE 运行配置或 Windows 服务环境中设置，不写入源码。

| 变量 | 用途 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | 本地真实开发使用 `dev` |
| `THREE_FEES_PROCESS_ROLE` | 本地可用 `all`；正式部署用 `api` / `worker` 分离 |
| `DB_URL` | MySQL JDBC 地址 |
| `DB_USERNAME` | MySQL 用户名 |
| `DB_PASSWORD` | MySQL 密码 |
| `INITIAL_ACCOUNT_BOOTSTRAP_ENABLED` | 空库首次启动时设为 `true` |
| `INITIAL_ACCOUNT_PASSWORD` | 首批账号临时密码，当前约定为 `123456` |
| `APP_FILE_ROOT` | 上传文件、报告文件存储根目录 |
| `AI_ENABLED` | 是否启用生成报告 AI 助手，本地联调默认使用 `true` |
| `KIMI_API_KEY` | Kimi 密钥，仅通过本机或服务环境注入，不要写入仓库 |
| `KIMI_BASE_URL` | Kimi OpenAI 兼容接口，默认 `https://api.moonshot.cn/v1` |
| `KIMI_MODEL` | Kimi 模型名，默认 `kimi-k3`，可按实际账号可用模型调整 |
| `KIMI_REASONING_EFFORT` | 推理强度，交互默认 `low`，可按需改为 `high` 或 `max` |
| `KIMI_MAX_COMPLETION_TOKENS` | 单次最大输出 token，默认 `8192` |
| `KIMI_REQUEST_TIMEOUT` | Java 等待 Kimi 的时限，默认 `10m` |

## 4. 推荐本地启动

本地真实联调优先使用统一脚本，避免不同 CMD/PowerShell 进程没有继承 Kimi
环境变量。脚本会检查 `KIMI_API_KEY` 是否存在，只输出是否配置，不回显密钥。

```powershell
cd D:\Three-fees
.\scripts\check-kimi-env.ps1
.\scripts\start-local.ps1
```

## 5. 手动后端启动

```cmd
cd /d D:\Three-fees\backend

set "SPRING_PROFILES_ACTIVE=dev"
set "THREE_FEES_PROCESS_ROLE=all"
set "DB_URL=jdbc:mysql://127.0.0.1:3306/three_fees?characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED"
set "DB_USERNAME=root"
set "DB_PASSWORD=你的数据库密码"
set "INITIAL_ACCOUNT_BOOTSTRAP_ENABLED=true"
set "INITIAL_ACCOUNT_PASSWORD=123456"
set "APP_FILE_ROOT=D:\Three-fees\runtime\files"
set "AI_ENABLED=true"
set "KIMI_BASE_URL=https://api.moonshot.cn/v1"
set "KIMI_MODEL=kimi-k3"
set "KIMI_API_KEY=你的Kimi密钥"

mvnw.cmd spring-boot:run
```

验证：

```cmd
curl http://127.0.0.1:8080/actuator/health
```

## 6. 前端启动

```cmd
cd /d D:\Three-fees\frontend

set "VITE_API_PROXY_TARGET=http://127.0.0.1:8080"
corepack pnpm install --frozen-lockfile
corepack pnpm dev --host 127.0.0.1 --port 5173
```

访问：

```text
http://127.0.0.1:5173/
```

## 7. 验证命令

```cmd
cd /d D:\Three-fees\backend
mvnw.cmd -DskipTests compile

cd /d D:\Three-fees\frontend
set "NODE_OPTIONS=--max-old-space-size=4096"
corepack pnpm typecheck
```

## 7. 数据和安全要求

- 不提交 `.env`、真实业务文件、数据库备份、上传文件、日志和报告产物。
- 不把数据库密码、Cookie、Token、AI Key 写入 Markdown 或代码。
- 数据库表结构变更必须通过 `backend/src/main/resources/db/migration` 下的 Flyway 脚本。
- 正式部署时 API 和 worker 必须拆分；`all` 只用于本机开发和自动化测试。

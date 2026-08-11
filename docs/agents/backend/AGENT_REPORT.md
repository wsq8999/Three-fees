# Backend Agent Report

## 2026-08-10 Commander Assignment

- [x] 已读 `docs/PROJECT_MANUAL.md`
- [x] 已读本报告
- 当前任务：创建 Java 21 / Spring Boot / MySQL 工程与无状态 Python AI sidecar；完成会话登录、RBAC、城市、用户和仪表盘最小纵向切片。
- 可修改：`backend/`、`ai-service/`、`contracts/`、本报告，必要时在手册追加已批准的后端事实。
- 不可修改：`frontend/`、`deploy/`；不得接入真实外部模型或复制 DEMO 敏感内容。
- RED：先建立领域/API/安全契约测试和 AI JSON Schema 测试。
- GREEN：最小实现通过测试、格式检查和构建。
- REFACTOR：消除重复、检查包边界、再次执行完整验证。
- 交付时填写：迁移表、初始化账号策略、API、环境变量、验证证据、风险和前端/部署交接。

## 2026-08-10 Backend Agent 启动记录

- 身份：固定 Backend Agent。
- 已完整阅读：`docs/PROJECT_MANUAL.md`、本报告、`docs/standards/API_STYLE.md`、`docs/standards/JAVA_STYLE.md`、`docs/standards/DATABASE_STYLE.md`，以及 xiaobai 的项目记忆、多代理和后端韧性规范。
- 采用的全局规则：Java/MySQL 是唯一业务事实源；公开 API 使用 `/api/v1` 与 RFC 9457；同源 Cookie 会话启用 CSRF；数据库只保存自适应口令哈希；Python sidecar 无状态且不访问数据库；禁止真实付费 AI 调用。
- 执行派单：创建 Java 21 / Spring Boot 4.1 / MySQL/Flyway 基线，完成会话、RBAC、江苏 13 地市、14 个初始化账号、用户列表、仪表盘、健康检查与 AI 原子契约。
- 严格边界：仅修改 `backend/`、`ai-service/`、`contracts/` 与本报告；不修改 `frontend/`、`deploy/`，不复制 DEMO 内容、密钥、数据、虚拟环境或 PostgreSQL 实现。
- 验收标准：先提交会失败的契约/安全/AI 测试（RED），再完成最小实现（GREEN），最后执行格式化、静态检查、完整测试和构建（REFACTOR/verification）。
- 规范冲突检查：三份新增规范与项目手册目前无冲突。

## 2026-08-10 Backend Agent 交付报告

### 身份、时间与范围

- 身份：固定 Backend Agent。
- 完成时间：2026-08-10 18:54（Asia/Shanghai）。
- 已完成：Java 21 / Spring Boot 4.1 模块化单体基线、MySQL/Flyway 身份与会话纵向切片、无状态 Python AI sidecar、公开 OpenAPI 与内部 JSON Schema 契约。
- 严格边界：仅修改 `backend/`、`ai-service/`、`contracts/` 与本报告；未修改 `frontend/`、`deploy/`，未复制 DEMO 源码、数据库、材料、虚拟环境或密钥，未调用真实付费 AI。

### 关键技术决策

- Java/MySQL 是唯一业务事实源；Python sidecar 不连接数据库、不保存会话、不编排业务流程。
- 后端采用 package-by-feature 的模块化单体，每个功能按 `api/application/domain/infrastructure` 分层；ArchUnit 阻止领域层依赖框架、阻止 API 直接访问 Mapper。
- 浏览器认证采用服务端 JDBC Session Cookie，Cookie 名为 `THREE_FEES_SESSION`，`HttpOnly`、`SameSite=Lax`，生产环境通过变量启用 `Secure`；所有状态变更请求使用 `XSRF-TOKEN` Cookie 和 `X-XSRF-TOKEN` Header。
- 公开接口统一 `/api/v1`，错误统一 `application/problem+json`（RFC 9457），扩展字段为 `code`、`traceId`、`fieldErrors`；认证失败不泄露账号是否存在，服务端日志不记录口令。
- 初始化账号仅在 API 进程、空用户表且显式允许引导时创建；临时初始口令只从进程环境读取。每个账号独立生成 BCrypt work factor 12 的随机盐哈希，迁移、源码、契约和文档均不保存其明文。
- AI 内部调用采用回环地址和 Bearer Token；请求包含契约版本、工作流版本、任务 ID、幂等键、输入 SHA-256 与 trace ID。当前 provider 固定为离线、确定性的 `fake`。

### 数据库迁移与初始化

Flyway `V1__create_core_identity_tables.sql` 创建以下表：

| 表 | 用途 |
| --- | --- |
| `city` | 江苏 13 个设区市字典，按行政区划代码唯一 |
| `app_user` | 用户、城市归属、BCrypt 哈希、启用状态与改密标记 |
| `app_user_role` | `SUPER_ADMIN` / `CITY_USER` 多角色关系 |
| `operation_log` | 登录成功、登录失败、退出等安全审计事件 |
| `business_task` | 后续 Java 编排/Worker 使用的幂等任务与租约状态骨架 |
| `spring_session` | 服务端会话主体 |
| `spring_session_attributes` | 服务端会话属性 |

- 城市字典随 Flyway 迁移写入；用户和口令不写入迁移。
- 初始化账号共 14 个：`admin`，以及 `nanjing_user`、`wuxi_user`、`xuzhou_user`、`changzhou_user`、`suzhou_user`、`nantong_user`、`lianyungang_user`、`huaian_user`、`yancheng_user`、`yangzhou_user`、`zhenjiang_user`、`taizhou_user`、`suqian_user`。
- `admin` 为 `SUPER_ADMIN` 且无城市归属；其余账号为对应城市的 `CITY_USER`。
- `operation_log` 当前实际记录 `SESSION_LOGIN` 的 `SUCCEEDED` / `FAILED` 以及 `SESSION_LOGOUT` 的 `SUCCEEDED`；字段只含 trace、用户引用/用户名快照、动作、结果与时间，不含口令列。审计写入异常会记录脱敏告警，不使已经成功的会话变成部分失败响应。

### 公开 REST 契约

完整手工契约位于 `contracts/openapi/v1.yaml`，运行时 `/v3/api-docs` 与 Swagger UI 仅允许 `SUPER_ADMIN` 访问。

| 方法 | 路径 | 权限 | 结果 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/sessions` | 匿名但必须携带 CSRF | 创建服务端会话，返回 `201` 与 `Location` |
| `GET` | `/api/v1/sessions/current` | 已登录 | 当前用户、角色、城市与改密标记 |
| `DELETE` | `/api/v1/sessions/current` | 已登录且必须携带 CSRF | 注销并使服务端会话失效，返回 `204` |
| `GET` | `/api/v1/cities` | 已登录 | 江苏 13 城市字典 |
| `GET` | `/api/v1/users?page=0&size=20` | `SUPER_ADMIN` | 标准分页用户列表 |
| `GET` | `/api/v1/dashboard/summary` | 已登录 | 最小仪表盘；管理员城市数 13，城市用户城市数 1，阶段一业务计数为 0 |
| `GET` | `/actuator/health` | 匿名 | 最小健康状态，不暴露详细组件 |

### AI 原子契约

AI sidecar 入口为 `app.main:app`，建议仅监听 `127.0.0.1:8100`。`/health` 匿名可用；以下 `/internal/v1` 路径都要求 `Authorization: Bearer ...`：

| 方法 | 路径 | 原子能力 |
| --- | --- | --- |
| `POST` | `/internal/v1/document-parses` | 文本/文档内容的确定性解析基线 |
| `POST` | `/internal/v1/fact-extractions` | 结构化事实提取 |
| `POST` | `/internal/v1/reason-judgments` | 原因判断 |
| `POST` | `/internal/v1/report-compositions` | 基于证据白名单的报告组合 |
| `POST` | `/internal/v1/correction-interpretations` | 受限字段的修正解释 |

- 五组请求/响应共 10 份 Draft 2020-12 JSON Schema 位于 `contracts/ai/v1/`。
- 在 `ai-service/` 下执行 `python -m scripts.export_schemas` 可重生成契约；契约测试会校验全部生成物。
- sidecar 禁用文档 UI，不包含数据库驱动和外部模型 SDK；缺少或过短的 `AI_SERVICE_TOKEN` 会启动失败，令牌不会被错误响应回显。

### 环境变量交接

| 变量 | 进程 | 必需性/用途 |
| --- | --- | --- |
| `DB_URL` | API / 后续 Worker | MySQL JDBC URL；生产必填 |
| `DB_USERNAME` | API / 后续 Worker | MySQL 最小权限账号；生产必填 |
| `DB_PASSWORD` | API / 后续 Worker | MySQL 密码；仅由部署 secret 注入 |
| `DB_POOL_MAX_SIZE` | API / 后续 Worker | Hikari 最大连接数，默认 20 |
| `DB_POOL_MIN_IDLE` | API / 后续 Worker | Hikari 最小空闲连接，默认 2 |
| `SERVER_PORT` | API | HTTP 监听端口，默认 8080 |
| `SESSION_COOKIE_SECURE` | API | HTTPS 生产环境设为 `true` |
| `INITIAL_ACCOUNT_BOOTSTRAP_ENABLED` | API | 是否允许空库账号引导；完成初始化后建议关闭 |
| `INITIAL_ACCOUNT_PASSWORD` | API 首次启动 | 初始化账号的临时口令；不落源码、迁移和文档 |
| `THREE_FEES_PROCESS_ROLE` | Java | `api` 或 `worker`；Worker 角色禁止账号引导 |
| `AI_SERVICE_TOKEN` | AI sidecar（未来 Java 客户端同值） | 内部 Bearer Token，至少 16 字符，仅 secret 注入 |

`APP_FILE_ROOT` 由部署方案保留给后续文件模块，本次后端尚未读取该变量。

### RED / GREEN / REFACTOR 证据

- RED：最初后端契约测试因生产类尚不存在而失败；AI 测试因 `app` 模块尚不存在而失败。
- GREEN：最小实现后，HTTP 会话、CSRF、RBAC、城市/用户/仪表盘、操作日志与 AI 五组契约测试通过。
- Worker RED：新增无 Web 上下文测试先后准确暴露 `SessionController` 缺少 `AuthenticationManager`、安全异常处理器缺少 `HandlerExceptionResolver`；把 Servlet 组件限制为 Web 应用后定向测试通过，且确认 Worker 角色不创建 `InitialAccountBootstrapper`。
- REFACTOR：执行 Google Java Format、Ruff format，增加 ArchUnit 包边界测试；最终全量构建发现一处 120 字符门禁失败，拆分测试 URL 常量后再次完整验证为全绿。

### 最终验证结果

- `backend\\mvnw.cmd -version`：Maven 3.9.16，Java 21.0.11。
- `backend\\mvnw.cmd verify`：`BUILD SUCCESS`；10 tests，0 failures，0 errors，0 skipped；Enforcer、Spotless、Checkstyle（0 violations）、2 条 ArchUnit 规则和 JaCoCo 门禁全部通过；44 个类总行覆盖率 88.77%；可执行 Jar 已生成。
- Java 集成测试使用随机端口和真实 HTTP 客户端，覆盖哈希校验、统一未认证错误、CSRF 缺失、无账号枚举登录失败、管理员会话、13 城市、14 用户分页、仪表盘、城市用户 403、OpenAPI 管理员/城市用户边界、注销失效与操作日志。
- `python -m pytest`：7 passed；10 份 JSON Schema 均通过元模式与样例验证。
- `python -m ruff check .`：通过；`python -m ruff format --check .`：8 files already formatted。
- 凭据字面量、旧 DEMO API Key 引用、`.env` 文件扫描：均无匹配；构建崩溃日志已清理，`.venv`、`target` 和缓存均由根 `.gitignore` 排除。

### 已完成文件组

- `backend/`：Maven Wrapper、依赖与质量门禁、模块化 Java 源码、配置、Flyway V1、README、HTTP/架构/Worker 测试。
- `ai-service/`：严格 Pydantic 模型、Bearer 认证、fake provider、FastAPI 原子接口、Schema 生成脚本、README 与测试。
- `contracts/`：公开 OpenAPI 3.1 契约、AI v1 说明和 10 份 JSON Schema。

### 尚未完成、风险与后续交接

- 未使用授权的真实 MySQL 凭据，因此本轮只在 H2 MySQL 兼容模式验证迁移。发布前必须在目标 MySQL 8.0.31 或 8.4 上执行空库迁移、升级迁移、索引和排序规则验收。
- H2 2.4.240 高于当前 Flyway 明示验证的 H2 2.3.232，测试会输出兼容性警告；这不替代真实 MySQL 验证。
- 初始化账号策略适用于单实例首次引导；多 API 实例并发首次启动前应通过部署顺序或数据库锁确保只有一个引导者，并在成功后关闭引导开关。
- `worker` 角色目前只验证“无 Web、无账号引导”。`business_task` 只有表骨架，尚无轮询、租约续约、重试、死信和 Java→AI 客户端；部署服务必须保持 Manual/不启动，不得宣称 Worker 已可运行。
- AI 当前只提供 fake provider 和文本基线，不解析真实 DOCX/图片，不调用 Kimi/OpenAI，不输出真实审计结论。后续接入真实模型必须在获得预算与密钥授权后新增 provider，并保持相同 JSON Schema、证据白名单和脱敏边界。
- Spring Security 对显式 `AuthenticationProvider` 的警告是当前手工 ProviderManager 配置的预期提示；Springdoc 会提示端点默认启用，虽然应用层仅管理员可访问，IIS 仍必须禁止公网转发 `/internal` 并按部署方案限制 Swagger。
- 测试依赖提示 FastAPI/Starlette `TestClient` 的 `httpx` 路径将迁移到 `httpx2`，Mockito 也提示未来 JDK 将限制动态 agent；本轮按锁定依赖保持不变，后续依赖升级任务应单独验证后处理，禁止无审查追新。
- 前端可直接以 `contracts/openapi/v1.yaml` 对接；部署侧需注入上表 secret、只公开 API/健康检查、AI 仅回环监听，并保持 Worker 服务 Manual。

## 2026-08-10 Commander 独立复验

- 独立执行 `backend\mvnw.cmd -B verify`：10 tests、0 failure/error/skip；Enforcer、Spotless、Checkstyle、ArchUnit 和 JaCoCo 全部通过。
- 随最终发布脚本再次执行 clean verify，结果保持通过；可执行 Jar 已进入带 SHA-256 manifest 的自校验发布包。
- 独立执行 `scripts\verify.ps1 -Scope ai`：pytest 7/7 与 Ruff 通过；Starlette `httpx2` 迁移提示保留为依赖观察项，不在本阶段无审查升级。

## 2026-08-10 Commander 完整系统派单

- Task：实现需求正文与附录中的剩余完整业务：73/198/42/39 列合同、Excel/CSV 导入、批次替换、current master/monthly snapshot、稽核重算、Excel/ZIP 导出、durable worker、AI 工作稿/版本/对话、Word/PDF、历史报告导入/预览/下载、更正、用户新增/编辑/重置/启停/改密和操作日志。
- Scope：`backend/`、`ai-service/`、`contracts/` 与本报告；不修改前端。
- Architecture：MySQL 是生产事实源；大宽表可采用版本化字段目录 + 原始 JSON 与必要强类型索引列，禁止建立 352 列的重复脆弱 DTO；文件位于临时写入后原子落盘的仓库外目录。
- Acceptance：OpenAPI 先行；核心算法覆盖闰年、缺失、B≤0、零标杆、多缴费单、审核资格、重复与精度；worker 具备租约/重试/恢复；测试配置只使用 H2 内存库和临时目录并自动销毁。
- Final test gate：实现阶段执行 RED/GREEN/REFACTOR，但暂不宣称全系统流程跑通；等待 Commander 在 Frontend 完成后统一执行真实 API E2E。

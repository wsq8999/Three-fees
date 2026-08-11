# Deploy Agent Report

## 2026-08-10 Commander Assignment

- [x] 已读 `docs/PROJECT_MANUAL.md`
- [x] 已读本报告
- 当前任务：建立纯 Windows Server 的 IIS + WinSW 部署基线、环境样例和可重复构建/安装说明。
- 可修改：`deploy/windows/`、部署文档、本报告，必要时在手册追加已批准的部署事实。
- 不可修改：业务源码；不得引入 Docker、Kubernetes 或未批准的付费服务。
- 验收前置：配置必须引用环境变量，不含真实凭据；服务名为 `three-fees-api`、`three-fees-worker`、`three-fees-ai`；AI 仅监听回环地址。
- 完成时记录 PowerShell 语法、XML/JSON 解析、路径与权限检查命令及结果。

### Deploy Agent 启动记录与验收条件

- 身份与日期：Deploy Agent，2026-08-10。
- 已读规则：项目统一使用 UTF-8/LF；配置与部署工作先定义验收条件；不得在仓库记录真实口令、令牌或数据库 URL；不得修改业务源码；不得执行本机 IIS、WinSW 服务或系统级安装。
- 本次派单：为纯 Windows Server 创建 IIS + WinSW 的构建、安装、升级、回滚基线，以及安全环境变量样例。
- 明确不触碰：`backend/`、`frontend/`、`ai-service/`、其他角色报告、本机 IIS 配置、本机 Windows 服务和真实部署目录。

验收条件（实现前固化）：

1. `deploy/windows/` 包含可重复的构建、安装、升级、回滚 PowerShell 脚本，并且默认以验证/预演方式运行；涉及 IIS 或服务状态变更的动作必须显式使用 `-Apply`。
2. 三个 WinSW XML 服务 ID 精确为 `three-fees-api`、`three-fees-worker`、`three-fees-ai`，日志与工作目录位于仓库外部署根目录；XML 必须可被 .NET XML 解析器读取。
3. AI 服务启动参数或环境配置明确绑定 `127.0.0.1:8100`，IIS 仅对外暴露 SPA 与 `/api`，不得代理 AI 内部端口。
4. IIS `web.config` 可被 XML 解析；SPA 回退规则不得吞掉真实文件、目录、`/api` 或 `/actuator`；`/api` 与 `/actuator` 代理到本机 Java API。
5. 仓库只包含占位环境变量样例，敏感值为空或使用明确占位符；静态扫描不得发现形似真实密码、令牌或带凭据的数据库 URL。
6. 安装/升级脚本在写入前检查管理员权限、依赖、绝对路径、发布包结构和 SHA-256；升级先创建可回滚版本，再原子切换 `current` 目录联接；回滚只允许选择部署根目录 `releases` 下的已验证版本。
7. 所有 PowerShell 脚本均通过 PowerShell AST parser，所有 XML/JSON 文件均可解析；路径与服务名规则由不修改系统的 `Test-DeploymentBaseline.ps1` 验证。
8. 部署手册说明先决条件、构建、首次安装、升级、回滚、环境变量注入、IIS/WinSW 手工边界和故障排查，不要求 Docker、Kubernetes 或付费服务。

## 2026-08-10 Deploy Agent 完成报告

### 身份、任务与边界

- 身份：Deploy Agent。
- 接收任务：为纯 Windows Server 建立 IIS + WinSW 的构建、首次安装、环境注入、升级、失败回切和人工回滚基线。
- 已遵循边界：仅修改 `deploy/windows/`、新建部署运行手册和本报告；未修改业务源码、其他角色报告或本机系统配置。
- 未执行：未安装/修改本机 IIS、ARR、URL Rewrite、WinSW 服务、注册表、ACL、防火墙或正式部署目录；未下载/运行 WinSW；未使用真实证书或秘密。

### 已读取并遵循

- `docs/PROJECT_MANUAL.md`。
- 本 Deploy 报告的 Commander 派单。
- `docs/standards/API_STYLE.md`、`docs/standards/DATABASE_STYLE.md`。
- xiaobai 的 `SKILL.md`、`references/project-memory.md`、`references/agent-system.md`。
- Backend 的 `application.yml` 和进程角色条件，仅用于部署契约对齐。

### 文件清单

| 文件 | 作用 |
|---|---|
| `deploy/windows/README.md` | 部署资产入口、安全默认值和目录索引 |
| `deploy/windows/config/deployment-layout.json` | 服务、端口、启动模式和 IIS 暴露面的机器可读基线 |
| `deploy/windows/config/environment.example.psd1` | 非敏感环境变量样例；所有秘密值为空 |
| `deploy/windows/config/iis/web.config` | SPA fallback、100 MB IIS 上限、安全头、静态缓存、API/health 回环反代、内部路径阻断 |
| `deploy/windows/config/winsw/three-fees-api.xml` | API 服务，Automatic，`127.0.0.1:8080` |
| `deploy/windows/config/winsw/three-fees-worker.xml` | worker 服务，Manual，本阶段不启动 |
| `deploy/windows/config/winsw/three-fees-ai.xml` | AI sidecar，Automatic，`127.0.0.1:8100` |
| `deploy/windows/scripts/Common.ps1` | 绝对路径/父目录防护、ZIP/manifest/SHA-256、ready marker、Junction、安全切换与健康回切 |
| `deploy/windows/scripts/Build-Release.ps1` | Maven/pnpm/pytest 构建、敏感材料排除、秘密扫描、发布清单与 ZIP |
| `deploy/windows/scripts/Install-ThreeFees.ps1` | 默认只预检；`-Apply` 后安装版本、Python venv、ACL、WinSW、IIS |
| `deploy/windows/scripts/Set-ServiceEnvironment.ps1` | 默认只预检；交互读取秘密并写入单个 Windows 服务环境，不回显值 |
| `deploy/windows/scripts/Upgrade-ThreeFees.ps1` | 准备完整新版本、切换 Junction、只恢复原运行服务、失败自动回切 |
| `deploy/windows/scripts/Rollback-ThreeFees.ps1` | 只允许选择 `releases` 下已校验且 ready 的版本，数据库不自动回退 |
| `deploy/windows/scripts/Test-DeploymentBaseline.ps1` | AST、XML/JSON/PSD1、服务契约、端口、反代、Apply 门、安全占位与秘密扫描 |
| `deploy/windows/scripts/Test-DeploymentScripts.ps1` | 临时发布夹具、SHA 篡改、ZIP 穿越、越界路径、凭据 URL 与无副作用预检行为测试 |
| `docs/deployment/WINDOWS_SERVER_RUNBOOK.md` | 构建、安装、秘密注入、启动、升级、回滚、故障排查和后端占位边界 |

### 关键决策

1. 固定 WinSW x64 2.12.0 稳定基线，不采用 WinSW 3 预发行线。XML 使用 2.12 的 `domain`/`user` 服务账号语法并移除 v3 专属项；安装使用同名 exe/xml 的 bundled 模式。
2. 三个服务均以 `NetworkService` 运行。目录 ACL 使用稳定 SID，避免中文 Windows 上本地化组名失效；秘密不写 XML。
3. IIS 只代理 `http://127.0.0.1:8080/api/*` 与 health；显式阻断 `/internal/*`、其他 Actuator 路径和 AI 的 8100 端口。
4. 发布目录使用 `releases/<version>` + `current` Junction。ZIP 条目先做数量、2 GiB 展开上限、绝对路径、`..`、冒号和重复项检查；提取后逐项验证长度与 SHA-256。
5. 任何会改变服务、IIS、注册表、ACL、目录或 Junction 的入口都要求 `-Apply`。构建脚本是唯一默认写输出的脚本，只写指定 `artifacts`/临时 staging。
6. 升级不盲目启动全部服务，而是记录切换前的运行集合并原样恢复；健康失败自动恢复旧 Junction 与旧运行集合。
7. Backend 确认本阶段 worker 没有 durable task consumer/keep-alive。为避免正常退出后被 WinSW 连续拉起，worker 固定 `Manual`，首次安装不启动；消费者落地后再联合验收并改 Automatic。
8. Backend 已实现 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`SESSION_COOKIE_SECURE`、`INITIAL_ACCOUNT_BOOTSTRAP_ENABLED`、`INITIAL_ACCOUNT_PASSWORD` 与 `THREE_FEES_PROCESS_ROLE`。部署变量已完全对齐；worker 固定关闭账号 bootstrap。
9. `APP_FILE_ROOT` 遵循项目手册保留，但 Backend 当前尚未读取，运行手册和报告均明确为文件模块占位。
10. API 首次账号口令只通过 SecureString 交互注入，不在文档或命令出现；账号建成后重跑 `bootstrap=false` 会从服务 Environment 值中移除 `INITIAL_ACCOUNT_PASSWORD`。
11. AI 默认 `fake`；旧 DEMO 密钥视为已暴露。只有轮换后才允许将新密钥交互注入 AI 服务，Java/API/worker永远不持有 Kimi 密钥。
12. 发布构建排除 `.env`、`.venv`、`node_modules`、缓存、整个 AI `data/`、上传材料和前端 source map，并扫描疑似密钥、带凭据数据库 URL 和 `sk-` 形式提供商密钥。

### 环境与接口契约

| 契约 | 状态 |
|---|---|
| API `127.0.0.1:8080` | WinSW 已固化；后端 `SERVER_ADDRESS`/`SERVER_PORT` 标准映射 |
| AI `127.0.0.1:8100` | WinSW 已固化；Backend 正按 `app.main:app` 与 `AI_SERVICE_TOKEN` 实施 |
| 公网路由 `/api/*` | IIS 回环代理，保留公开 `/api/v1` 语义 |
| 公网健康 `/actuator/health` | IIS 单独开放；其余 Actuator 404 |
| `/internal/v1` 与 8100 | IIS 不代理；`/internal` 显式 404 |
| MySQL | 服务接收无凭据 `DB_URL` + 独立 username/password；默认 URL 仅适合同机回环 |
| 文件目录 | `APP_FILE_ROOT=<root>\shared\files`，当前代码占位 |
| worker | 角色条件已实现，但消费者未实现；Manual/Stopped |

### 验证证据

所有验证均为只读检查或在系统临时目录创建并安全清理的测试夹具，不修改 IIS/服务/注册表。

1. 静态基线：

   ```powershell
   powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "D:\Three-fees\deploy\windows\scripts\Test-DeploymentBaseline.ps1"
   ```

   结果：退出码 0；62 项 PASS。覆盖 8 个 PowerShell 文件 AST、4 个 XML/config、1 个 JSON、PSD1 解析、4 个秘密空值、三个精确服务 ID、WinSW v2 NetworkService 语法、worker Manual、API/AI 回环端口、IIS 路由和秘密扫描。

2. 行为测试：

   ```powershell
   powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "D:\Three-fees\deploy\windows\scripts\Test-DeploymentScripts.ps1"
   ```

   结果：退出码 0；9 项 PASS。验证有效 manifest/ZIP、文件篡改拒绝、非法版本/盘根/父目录逃逸拒绝、带凭据 JDBC URL 拒绝、ZIP path traversal 拒绝、安装预检不创建目标目录。

3. 三服务环境预检：分别不带 `-Apply` 运行 `Set-ServiceEnvironment.ps1`。

   结果：三个命令退出码均为 0；均明确输出 `PreflightOnly` 与“不修改注册表/服务”，只列变量名，不输出值。

4. 解析失败曾真实触发并修复：Windows PowerShell 5.1 对无 BOM UTF-8 PSD1 的中文注释解析失败，样例注释改为 ASCII 后保持项目 UTF-8/LF 规则且解析通过；WinSW 2/v3 服务账号语法漂移也由官方文档审查发现并修复。

### 未完成与风险

- 未执行完整 `Build-Release.ps1`：验证时 `frontend/dist` 与 `ai-service` 依赖元数据尚未由并行角色交付。构建脚本自身 AST/行为边界已通过；Commander 在前后端完成后必须实际生成 ZIP并复跑 release manifest 检查。
- 未在真实 Windows Server 上运行 WinSW 2.12、IIS URL Rewrite/ARR、证书绑定、ACL、Python venv 或服务健康；这是部署前必须完成的预生产验收，不能用 XML 可解析代替。
- IIS ARR `proxy enabled` 是服务器级设置，脚本只在 `-Apply` 后修改；正式服务器应专用或先审查对现有站点的影响。
- 升级不自动覆盖已安装 WinSW XML。新模板与已安装 XML 有差异时须单独评审、备份并在预生产执行 WinSW refresh/重启验证。
- 二进制回滚不回退 Flyway 数据库。任何非向后兼容迁移都必须采用前向修复或一致性备份恢复。
- worker 消费者、租约、重试和恢复未落地前不得把服务改为 Automatic。

### 交接给 Commander/其他角色

- Backend：保持已经确认的环境变量、`THREE_FEES_PROCESS_ROLE` 和 `app.main:app` 契约；实现 durable task consumer 后通知 Deploy/Ops 做 worker 常驻与恢复验收。
- Frontend：生产构建必须使用 history 路由并确保 hashed assets 位于 `assets/`；不得把秘密注入 Vite 构建变量；source map 不进入发布包。
- Ops：基于固定 `shared/files`、MySQL 和版本目录制定 7 日备 + 4 周备，执行隔离恢复演练；监控 API/AI 服务，但当前不对 worker 常驻做成功承诺。
- Commander：前端 dist 与 AI 依赖锁交付后，执行真实 Build-Release；在预生产 Windows Server 完成 WinSW/IIS/证书/服务/健康/自动回切验收后才可标记“可部署”。

## 2026-08-10 Commander 一致性更新

- AI sidecar 的代码与全套测试实际以 Python 3.12 为基线；Commander 将安装/升级脚本默认路径和部署手册由 Python314 统一为 Python312。
- 修改后独立复跑 `Test-DeploymentBaseline.ps1`（62 PASS）与 `Test-DeploymentScripts.ps1`（9 PASS），结果保持通过。

## 2026-08-10 Commander 发布制品验收

- 实际执行完整 `Build-Release.ps1`，最终单命令完成后端 clean verify、Corepack/pnpm 11.9.0 锁定安装与前端生产构建、AI pytest、生产文件筛选、秘密扫描、manifest 生成、逐文件 SHA-256 和 ZIP 自校验。
- 全链路首次暴露服务器全局 pnpm 版本漂移；构建脚本已改为通过 Corepack 读取仓库 `packageManager`。随后暴露秘密扫描器对安全处理代码/拒绝凭据正则的误报；规则已收紧为高置信字面量、环境值、合法凭据 URL、供应商 key 和私钥材料。
- 生产包现在排除部署测试脚本、AI tests、虚拟环境和各类缓存。最终验收 ZIP 共 67 个条目、无禁入条目，根 manifest 存在并通过自校验。
- 修改后 Commander 再次独立复跑静态 62 PASS 与行为 9 PASS。真实 Windows Server/IIS/WinSW/证书/ACL 仍属于预生产门槛，不因本次制品成功而视为完成。

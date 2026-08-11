# Windows Server 部署运行手册

## 1. 适用范围

本手册用于智能物业管理系统的单机可试运行部署。运行形态固定为：

- IIS：HTTPS、Vue SPA、静态压缩和反向代理。
- `three-fees-api`：Java API，监听 `127.0.0.1:8080`。
- `three-fees-worker`：Java/MySQL 持久任务工作进程，不开放端口；与 API 分进程运行并自动启动，负责租约、重试和重启恢复。
- `three-fees-ai`：无状态 Python sidecar，监听 `127.0.0.1:8100`。
- MySQL：生产目标 8.4 LTS，业务事实源；数据库安装与备份由数据库/运维流程负责。

不使用 Docker、Kubernetes、外部消息队列、对象存储或付费部署服务。本仓库中的脚本默认只预检；只有显式增加 `-Apply` 才会修改部署目录、ACL、IIS、服务或注册表。

## 2. 验收条件

部署前必须同时满足：

1. 发布 ZIP 的根目录包含 `manifest.json`，所有清单文件的长度与 SHA-256 一致，且没有路径穿越条目。
2. WinSW 二进制来自批准的官方发行版，安装时传入独立渠道核对的 64 位 SHA-256；仓库不携带二进制。
3. 三个服务 ID 精确为 `three-fees-api`、`three-fees-worker`、`three-fees-ai`，以低权限 `NetworkService` 运行。
4. API 与 AI 只监听回环地址；防火墙不得新增 8080/8100 入站规则。
5. IIS 只代理 `/api/*` 与 `/actuator/health`，阻断 `/internal/*` 和其他 Actuator 路径。
6. 数据库口令、AI 内部令牌、Kimi 密钥和证书私钥均不在 Git、Markdown、命令历史或发布 ZIP 中。
7. 升级前已完成数据库与文件备份；迁移保持向后兼容，否则不得依赖二进制回滚。
8. 静态基线测试与行为测试全部通过。

## 3. 服务器先决条件

建议从 Windows Server 2022/2025 x64 的干净实例开始，并在预生产环境复现正式环境版本。

运行服务器：

- IIS（Static Content、Default Document、HTTP Errors、Static Content Compression；Dynamic Content Compression 可按 CPU 基线启用）。
- IIS URL Rewrite 2.x 与 Application Request Routing（ARR），并启用代理功能。
- Java 21 x64，仅需 JRE/JDK 运行时。
- Python 3.12 x64，安装时为每个发布版本建立独立 `.venv`。sidecar 契约允许 3.12–3.14，但当前只把完成全套验证的 3.12 作为生产基线。
- PDFBox 可直接加载并覆盖中文字符的 TrueType/OpenType 字体文件。默认 `C:\Windows\Fonts\simhei.ttf`；安装预检会实际以只读方式打开。未经 Backend 专项验证不得用 `.ttc` 集合字体替代。
- WinSW x64 2.12.0。模板使用该稳定版的 `domain`/`user` 服务账号语法；WinSW 3 当前为预发行迁移线，升级前必须单独做服务安装、停止、失败重启和日志轮转验收。
- 可连接的 MySQL 8.4 LTS；试运行期间 SQL 需兼容 8.0.31。
- 受信任的 TLS 证书，安装在 `Cert:\LocalMachine\My`。

构建机器另需 Node 24（含 Corepack）、Maven Wrapper 与 Git；pnpm 11.9.0 由 Corepack 按仓库声明解析。服务器不需要 Node 或 Maven。

官方依据：WinSW 支持环境变量展开、低权限服务账号和 XML 配置，见 [WinSW 2.12 XML 配置](https://github.com/winsw/winsw/blob/v2.12.0/doc/xmlConfigFile.md) 与 [WinSW 项目说明](https://github.com/winsw/winsw/tree/v2.12.0)；IIS 反向代理需要 URL Rewrite 与 ARR，见 [Microsoft ARR 反向代理指南](https://learn.microsoft.com/en-us/iis/extensions/url-rewrite-module/reverse-proxy-with-url-rewrite-v2-and-application-request-routing)；压缩开关见 [IIS URL Compression](https://learn.microsoft.com/en-us/iis/configuration/system.webserver/urlcompression)。

脚本不会安装上述组件，也不会打开防火墙。管理员必须按组织的软件供应链流程下载、查毒、核对发布签名/哈希并安装。

## 4. 固定目录结构

默认根目录为 `C:\ProgramData\ThreeFees`：

```text
C:\ProgramData\ThreeFees\
  current -> releases\<version>        受控 NTFS Junction
  releases\<version>\
    manifest.json
    release-state.json
    backend\three-fees-api.jar
    frontend\index.html
    frontend\web.config
    ai-service\.venv\...
    deployment\windows\...
  services\
    three-fees-api\three-fees-api.exe|xml
    three-fees-worker\three-fees-worker.exe|xml
    three-fees-ai\three-fees-ai.exe|xml
  shared\
    files\
    logs\api|worker|ai\
    tmp\api|worker|ai\
  staging\
```

`releases` 不自动清理；保留期需与 Ops 的 7 个日备、4 个周备策略协调。业务文件只放 `shared\files`，不得放进版本目录。

## 5. 构建发布包

在仓库根目录以普通用户运行；构建不需要管理员权限：

```powershell
Set-ExecutionPolicy -Scope Process Bypass

.\deploy\windows\scripts\Test-DeploymentBaseline.ps1
.\deploy\windows\scripts\Test-DeploymentScripts.ps1
.\deploy\windows\scripts\Build-Release.ps1 `
  -Version '0.1.0' `
  -PnpmExe 'C:\Program Files\nodejs\corepack.cmd' `
  -PythonExe 'D:\ApprovedRuntime\Python312\python.exe'
```

`Build-Release.ps1` 会执行 Maven `verify`，通过显式 `PnpmExe` 使用 `packageManager` 锁定的 pnpm 版本完成冻结安装与生产构建，并通过显式 `PythonExe` 执行 AI pytest。`PnpmExe` 可以是 Corepack 或经批准的 pnpm 可执行文件。发布包只复制一个可运行 JAR、前端 `dist`、脱敏后的 AI 运行源码、部署工具与运行手册；排除 `.env`、虚拟环境、依赖目录、测试脚本/数据、运行时目录、上传/备份、日志、缓存和 DEMO 材料。脚本扫描高置信疑似秘密并生成逐文件 SHA-256 清单。

将以下内容作为同一发布审批记录保存，但不要写入 Git：

- `three-fees-<version>.zip` 的 SHA-256。
- Git commit ID。
- 构建机 Java/Node/pnpm/Python 版本。
- 审批人、发布时间窗和对应备份 ID。

## 6. 首次安装

### 6.1 预检

把发布 ZIP 与经批准的 WinSW x64 二进制放到受控临时目录。先从官方发布页面和独立审批记录核对 WinSW SHA-256，再运行不带 `-Apply` 的预检：

```powershell
$approvedWinSwHash = '<64-hex-approved-sha256>'
$certificateThumbprint = '<local-machine-certificate-thumbprint>'

.\Install-ThreeFees.ps1 `
  -ReleaseArchive 'D:\Release\three-fees-0.1.0.zip' `
  -WinSWExecutable 'D:\Release\WinSW-x64.exe' `
  -WinSWExpectedSha256 $approvedWinSwHash `
  -JavaExe 'C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe' `
  -PythonExe 'C:\Program Files\Python312\python.exe' `
  -ReportFontPath 'C:\Windows\Fonts\simhei.ttf' `
  -HostName 'property.example.internal' `
  -CertificateThumbprint $certificateThumbprint
```

预检只读取发布包、哈希、运行时路径和 IIS 依赖，不创建目标目录、不安装服务、不改 IIS。隔离试运行确实没有证书时才可显式使用 `-AllowHttp`；对真实用户开放前必须改为 HTTPS。

### 6.2 应用安装

确认预检结果后，在提升权限的 PowerShell 中重复同一命令并加 `-Apply`。安装流程会：

1. 安全解压并复核清单/SHA-256。
2. 建立版本目录及版本专属 Python `.venv`。
3. 写入 `release-state.json` 后创建 `current` Junction。
4. 为 `NetworkService`、IIS 授予最小目录权限。
5. 渲染并安装三个 WinSW 服务，但不启动。
6. 建立 IIS 站点、证书绑定并启用 ARR 代理。

若生产网络禁止在线安装 Python 包，准备内部、已查毒并锁定哈希的 wheelhouse，然后增加 `-Wheelhouse 'D:\ApprovedWheelhouse'`。正式发布优先提交 `requirements.lock`；只有 `requirements.txt`/`pyproject.toml` 时脚本会警告可重复性降低。

## 7. 安全注入环境变量

样例文件 [`environment.example.psd1`](../../deploy/windows/config/environment.example.psd1) 的秘密值必须保持为空。真实秘密通过提升权限的交互式脚本写入各服务的 `HKLM:\SYSTEM\CurrentControlSet\Services\<service>\Environment`；脚本不回显值。

```powershell
.\Set-ServiceEnvironment.ps1 -ServiceId three-fees-api `
  -DatabaseUsername 'three_fees_app' -ReportFontPath 'C:\Windows\Fonts\simhei.ttf' `
  -InitialAccountBootstrapEnabled $true -Apply

.\Set-ServiceEnvironment.ps1 -ServiceId three-fees-worker `
  -DatabaseUsername 'three_fees_app' -ReportFontPath 'C:\Windows\Fonts\simhei.ttf' -Apply

.\Set-ServiceEnvironment.ps1 -ServiceId three-fees-ai `
  -AiProvider fake -Apply
```

三次输入的 `AI_SERVICE_TOKEN` 必须是同一高熵随机值。`DB_PASSWORD` 只提供给 API/worker；`KIMI_API_KEY` 只提供给 AI。AI 默认 `fake`，切换 `kimi` 前必须轮换 DEMO 中已暴露的旧密钥，并明确传入 `-AiProvider kimi -KimiModel '<approved-model>'`。

首次 API 启动还会交互输入用户指定的临时初始账号口令，但口令值不出现在命令、文档或仓库。确认账号已生成后，再以 `-InitialAccountBootstrapEnabled $false` 重跑 API 环境脚本并重启 API，使注册表中不再保留 `INITIAL_ACCOUNT_PASSWORD`。worker 始终写入 `INITIAL_ACCOUNT_BOOTSTRAP_ENABLED=false`。

注册表值仍属于本机敏感材料：只允许本机管理员与 SYSTEM 读取；不得截图、导出到工单或纳入普通配置备份。变更后在维护窗口重启对应服务。

默认 `DB_URL` 只适用于同机回环 MySQL，因此使用 `sslMode=DISABLED`。若数据库迁到其他主机，必须配置服务器证书验证（例如 `VERIFY_IDENTITY`）并先做驱动/证书链测试；无论哪种模式都禁止把用户名或口令写进 URL。

变量契约：

| 变量 | 服务 | 是否秘密 | 用途 |
|---|---|---:|---|
| `DB_URL` | API、worker | 否 | JDBC MySQL URL；不得内嵌用户或口令 |
| `DB_USERNAME`/`DB_PASSWORD` | API、worker | 是 | 最小权限数据库账号 |
| `APP_FILE_ROOT` | API、worker | 否 | 仓库外业务文件根；导入、导出、报告 Word/PDF 与历史文件均由真实文件模块使用 |
| `REPORT_FONT_PATH` | API、worker | 否 | PDFBox 中文报告字体的绝对 `.ttf`/`.otf` 路径；安装和环境注入均检查可读 |
| `AI_SERVICE_BASE_URL` | API、worker | 否 | 固定回环 AI 地址 |
| `AI_SERVICE_TOKEN` | 三个服务 | 是 | Java/Python 内部认证共享值 |
| `SESSION_COOKIE_SECURE` | API | 否 | 生产固定 `true`，仅通过 HTTPS 发送会话 Cookie |
| `INITIAL_ACCOUNT_BOOTSTRAP_ENABLED` | API、worker | 否 | API 首次启用后关闭；worker 固定关闭 |
| `INITIAL_ACCOUNT_PASSWORD` | API 首次启动 | 是 | 仅首次建号，完成后从服务环境移除 |
| `AI_MODEL_PROVIDER` | AI | 否 | `fake` 或经批准的 `kimi`；默认固定 deterministic fake |
| `KIMI_BASE_URL`/`KIMI_MODEL` | AI | 否 | 供应商端点与模型名 |
| `KIMI_API_KEY` | AI | 是 | 已轮换的模型密钥 |

WinSW XML 固定注入 `SPRING_PROFILES_ACTIVE`、回环监听和进程角色，不包含秘密。

## 8. 启动与验证

首次部署按 AI → API → worker 的顺序启动。API 完成 Flyway/首次账号初始化后，worker 才开始领取持久任务；worker 不启动 Web，也不执行账号初始化：

```powershell
Start-Service three-fees-ai
Start-Service three-fees-api
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health'
Start-Service three-fees-worker

Get-Service three-fees-api, three-fees-worker, three-fees-ai
Invoke-WebRequest 'https://property.example.internal/actuator/health' -UseBasicParsing
```

验证清单：

- 三个服务状态均为 `Running`，Windows Event Log 无连续重启；worker 日志出现轮询就绪且没有“处理器缺失”循环错误。
- API 健康返回 HTTP 200；外部 HTTPS 健康返回 200。
- 浏览器刷新任意 SPA 路由仍返回 `index.html`。
- `/internal/v1/...` 与 `/actuator/env` 从 IIS 返回 404。
- `netstat -ano` 显示 8080、8100 只绑定 `127.0.0.1`。
- 100 MB 以上上传在 IIS 层拒绝；应用层仍需执行自身大小、MIME、签名和压缩炸弹校验。
- IIS 与 WinSW 日志中没有口令、令牌、模型原始敏感输入。

## 9. 升级

升级前完成数据库与 `shared\files` 备份、确认恢复点、验证新迁移向后兼容，并在维护窗口先跑预检：

```powershell
.\Upgrade-ThreeFees.ps1 `
  -ReleaseArchive 'D:\Release\three-fees-0.2.0.zip' `
  -PythonExe 'C:\Program Files\Python312\python.exe'
```

确认后加 `-Apply`。脚本准备完整新版本并写 ready marker，随后停止三个服务、切换 `current` Junction，只恢复切换前实际处于运行状态的服务，并等待 API 健康。正常运行集合是 AI、API、worker；新版本不健康时自动恢复旧 Junction 和原运行服务，失败版本目录保留用于取证。

升级脚本不会自动覆盖已安装的 WinSW XML。若发布包内 `deployment\windows\config\winsw` 与 `services\*\*.xml` 有差异，必须单独评审、备份旧 XML，并在预生产验证 WinSW `refresh`/重启后再变更正式环境。这样可避免二进制升级意外改变服务账号或启动策略。

## 10. 回滚

仅可回滚到 `releases` 下具有有效 manifest、SHA-256、ready marker 和 Python 运行时的版本：

```powershell
.\Rollback-ThreeFees.ps1 -Version '0.1.0'
.\Rollback-ThreeFees.ps1 -Version '0.1.0' -Apply
```

第一条只预检，第二条才切换。回滚脚本不会回退数据库。若新版本执行了不向后兼容的 Flyway 迁移，禁止直接回滚二进制；应执行经评审的前向修复或整库/文件一致性恢复。

## 11. 故障排查

| 现象 | 检查 | 处理边界 |
|---|---|---|
| IIS 500.19 | URL Rewrite/ARR 是否安装，`web.config` 是否可解析 | 安装缺失模块后复测，不删除安全规则 |
| IIS 502.3 | API 服务状态、8080 回环监听、ARR proxy enabled | 先查 API/WinSW 日志，不把 8080 开到公网 |
| 服务 1067/连续重启 | Windows Event Log、`shared\logs`、运行时路径、环境变量名 | 不在工单粘贴环境变量值 |
| API 数据库失败 | MySQL 监听、最小权限账号、数据库迁移 | 不改用 root 账号规避权限问题 |
| AI 401/403 | 三个服务的内部令牌是否一致 | 轮换并同步重启，不打印令牌 |
| AI 无法启动 | 版本 `.venv`、锁文件、`app.main:app` 导入 | 保留失败版本，切回旧版本 |
| 升级健康失败 | 脚本自动回滚输出、旧服务健康、失败版本日志 | 不删除失败目录直至取证完成 |

## 12. 完整系统启动契约

Backend Agent 已确认：

- Java 使用同一 JAR，通过 `THREE_FEES_PROCESS_ROLE=api|worker` 区分进程；worker 同时设置 `SPRING_MAIN_WEB_APPLICATION_TYPE=none`，不会启动 Web 或账号初始化。
- Spring 生产配置已确认读取 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`SESSION_COOKIE_SECURE`、`INITIAL_ACCOUNT_BOOTSTRAP_ENABLED`、`INITIAL_ACCOUNT_PASSWORD`。
- `APP_FILE_ROOT` 是真实文件模块根目录；只存业务文件，数据库记录元数据、SHA-256 和关联。任何版本升级都不得把它迁入 `releases`。
- `REPORT_FONT_PATH` 必须在 API/worker 两个服务中一致，且是可读的 `.ttf`/`.otf` 中文字体；不得依赖 Helvetica 显示中文。
- Python ASGI 入口按 `app.main:app`、`AI_SERVICE_TOKEN` 与 `127.0.0.1:8100` 实施，不访问 MySQL；最终构建仍需以实际 sidecar 测试确认。
- AI 健康不从 IIS 公开；Java/API 健康必须覆盖 sidecar 依赖状态但不得泄露细节。

worker 已作为 durable task consumer 常驻，WinSW `startmode` 为 `Automatic` 并延迟启动。API 只提交/查询任务，worker 独立领取、续租、成功、失败和重试；两者不得合并为生产单进程。`THREE_FEES_PROCESS_ROLE=all` 只允许 `dev/e2e/test` 临时环境。

Backend Agent 完成实现后必须逐项确认；不一致时由 Commander 决定修改代码契约或 WinSW 模板，并重新运行全部部署测试。

## 13. 文件备份一致性契约

`APP_FILE_ROOT=<DeploymentRoot>\shared\files` 与 MySQL 必须进入同一恢复点。当前生产备份采用 `Quiesced`：维护流程先停止会产生业务写入的 API/worker或建立经验证的停写标记，在整个 MySQL 快照与文件复制窗口内保持停写，随后再按 AI → API → worker 恢复。备份脚本不得只复制版本目录，也不得遗漏 Word、PDF、导入源文件、导出文件及其数据库元数据。

只有 Backend/Commander 用自动化证明临时写入、校验、原子落盘、不可变文件、数据库最后提交引用和备份期间不物理删除后，才可启用 append-only 无停写模式。发布升级/二进制回滚不删除 `shared\files`；恢复必须在隔离环境逐一核对文件 SHA-256、报告预览/下载和任务引用。

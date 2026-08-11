# Ops Agent Report

## 2026-08-10 待命记录

- [ ] 已读 `docs/PROJECT_MANUAL.md`
- 当前任务：首个可运行构建完成后，验证健康检查、结构化日志、备份与恢复基线。
- 可修改：运维脚本/文档与本报告；不得修改业务行为。
- 目标：每日备份、7 个日备 + 4 个周备；试运行 RPO 24 小时、RTO 4 小时；恢复流程必须可演练。
- 当前状态：等待 Backend 与 Deploy 的第一阶段产物。

## 2026-08-10 Ops Agent 启动记录

- 身份：Ops Agent。
- 已完整阅读：`docs/PROJECT_MANUAL.md`、本报告、Deploy Agent 报告、`docs/deployment/WINDOWS_SERVER_RUNBOOK.md`，以及 xiaobai 的项目记忆与固定角色协议。
- 当前派单：在纯 Windows Server 边界内建立健康巡检、日志/磁盘检查、MySQL 与业务文件一致时间窗备份、7 个日备 + 4 个周备保留、隔离恢复演练，以及 RPO 24 小时 / RTO 4 小时验收基线。
- 只修改：`deploy/windows/ops/`、`docs/operations/` 和本报告。
- 明确不触碰：Deploy Agent 已完成的 `deploy/windows/scripts/` 与 `deploy/windows/config/`、业务源码、其他角色报告、本机服务、本机计划任务和真实用户数据。

### 实现前验收条件

1. 所有会创建备份、恢复沙箱、标记文件或删除过期备份的脚本默认只预检；只有显式 `-Apply` 才会写入或删除。
2. 备份根、恢复演练根和所有删除候选必须是绝对路径；盘符根、共享根、重解析点、越界路径和未带受管标记的目录一律拒绝。删除仅允许发生在已标记备份根的 `daily` / `weekly` 直接子目录内。
3. MySQL 口令只从指定环境变量或交互式安全输入取得，绝不进入参数、配置、Markdown、日志或报告；外部命令失败也不回显秘密。
4. 一致性备份先取得 MySQL 单事务逻辑快照，再复制不可变文件；在文件不可变契约尚未获批时，必须持有有效的外部停写标记，不能宣称自动一致。
5. 每个完整备份包含元数据、逐文件 SHA-256 清单和最后写入的 READY 证明；周备与日备各自独立，轮转后精确保留最新 7 个日备和 4 个周备。
6. 恢复只允许写入已标记、且与部署根/文件根/备份根互不包含的隔离目录；数据库名必须使用恢复演练前缀、目标库必须为空，脚本不停止服务、不覆盖正式库、不删除演练库。
7. 健康巡检覆盖 API/AI/worker 期望状态、回环监听、HTTP 健康、日志新鲜度/错误与疑似秘密计数、磁盘余量和最新备份年龄，但不输出命中的敏感日志正文。
8. RPO 验收使用备份恢复点到基准时刻的小时数，阈值 24 小时；RTO 验收使用最近一次成功隔离恢复演练耗时，阈值 4 小时，并检查演练新鲜度。
9. 所有 PowerShell 文件通过 Windows PowerShell AST parser，PSD1/JSON 可解析；行为测试仅在带随机标记的系统临时目录中使用假 MySQL 客户端，证明 dry-run 无写入、路径越界拒绝、备份/轮转/恢复与 RPO/RTO 逻辑。
10. 仓库静态检查不得发现计划任务或服务变更命令、明文秘密、带口令数据库 URL，且不会执行真实备份、真实删除或系统级配置修改。

## 2026-08-10 Ops Agent 完成报告

### 身份、任务与边界

- 身份：Ops Agent。
- 完成任务：建立纯 Windows Server 的健康/日志/磁盘巡检、MySQL + 文件一致时间窗备份、7 个日备 + 4 个周备轮转、隔离恢复演练和 RPO 24h / RTO 4h 验收基线。
- 已遵循范围：只新增 `deploy/windows/ops/`、`docs/operations/` 并更新本报告。
- 未修改：Deploy Agent 的 `deploy/windows/scripts/`、`deploy/windows/config/`、部署手册、业务源码、项目手册或其他角色报告。
- 未执行：未连接真实 MySQL，未读取或备份真实业务文件，未删除真实备份/用户数据，未修改 Windows 服务、IIS、注册表、防火墙或计划任务。

### 已读取并遵循

- `docs/PROJECT_MANUAL.md`。
- 本 Ops 报告原有派单。
- `docs/agents/deploy/AGENT_REPORT.md`。
- `docs/deployment/WINDOWS_SERVER_RUNBOOK.md`。
- xiaobai 的 `SKILL.md`、`references/project-memory.md` 和 `references/agent-system.md`。
- 配置/运维任务使用“先写验收条件，再给出可重复命令和证据”的验证纪律；本节前的 10 条验收条件在实现前已经固化。

### 文件清单

| 文件 | 作用 |
|---|---|
| `deploy/windows/ops/README.md` | Ops 资产入口、默认 dry-run 与安全边界 |
| `deploy/windows/ops/config/operations.example.psd1` | 非敏感路径、服务、MySQL 客户端、7+4、RPO/RTO 和阈值样例 |
| `deploy/windows/ops/scripts/Ops.Common.ps1` | 绝对路径/包含关系/重解析点防护、受管根标记、秘密读取、外部进程、robocopy、备份 proof 与唯一删除入口 |
| `deploy/windows/ops/scripts/Initialize-OperationsRoots.ps1` | 默认预检；`-Apply` 后仅初始化空的备份/恢复根和受管标记 |
| `deploy/windows/ops/scripts/Test-ThreeFeesOperationsHealth.ps1` | 只读服务、回环监听、HTTP、路径、日志、疑似秘密、磁盘与备份新鲜度检查 |
| `deploy/windows/ops/scripts/Invoke-ThreeFeesBackup.ps1` | 默认预检；日/周 MySQL 单事务 dump + 文件快照 + SHA-256 + READY + 原子就绪 |
| `deploy/windows/ops/scripts/Invoke-ThreeFeesBackupRetention.ps1` | 默认列出候选；`-Apply` 后精确保留 7 个日备和 4 个周备 |
| `deploy/windows/ops/scripts/Invoke-ThreeFeesRestoreDrill.ps1` | 默认验证计划；`-Apply` 后只向标记恢复根和前缀空库恢复，生成结果 hash proof |
| `deploy/windows/ops/scripts/Test-ThreeFeesRecoveryObjectives.ps1` | 只读计算最新有效恢复点 RPO 与最近有 proof 演练 RTO/新鲜度 |
| `deploy/windows/ops/scripts/Test-OperationsBaseline.ps1` | PowerShell parser、PSD1、安全默认、Apply 门、服务/计划任务/秘密/删除静态检查 |
| `deploy/windows/ops/scripts/Test-OperationsScripts.ps1` | 默认预检；`-Apply` 后仅在带随机标记的临时目录中使用假 MySQL 客户端跑全链路 |
| `docs/operations/OPERATIONS_RUNBOOK.md` | 权限、秘密、停写/不可变一致性、日周备、轮转、隔离恢复、RPO/RTO、建议频率和限制 |

### 关键决策

1. 备份 proof 固定为 `backup-metadata.json` + 逐 payload `manifest.json` + 最后写入的 `READY.json`。READY 同时保存 metadata/manifest SHA-256；证明不完整或哈希不符的目录不参与轮转、健康或恢复。
2. 备份恢复点采用 `databaseSnapshotStartedAtUtc` 的保守时间。流程先用 `mysqldump --single-transaction --quick --skip-lock-tables` 取得数据库逻辑快照，再复制文件并逐项校验。
3. 默认 `Quiesced`。外部停写标记必须匹配部署根、未过期且覆盖数据库 + 文件全过程；脚本不会把标记本身伪装成停写机制。只有 Backend 证明文件“临时写入 → 校验 → 原子落盘 → 不可变 → 数据库最后提交引用，备份期间不物理删除”，并经 Commander 批准后，才允许打开 `AppendOnlyFileContractApproved`。
4. 源文件树包含任一重解析点即拒绝备份；robocopy 也使用 `/XJ`，避免快照逃逸配置根。
5. 所有受管根必须是绝对、非盘符/共享根、非重解析点且不与生产路径重叠。初始化不采用已有非空目录，避免误认领用户数据。
6. 永久删除只有 `Remove-OpsManagedBackupArtifact` 一个入口。它在删除前再次验证受管根标记、绝对包含关系、`daily`/`weekly` 直接子目录、非重解析点、ID、metadata 与 READY 哈希；生产入口脚本不直接调用 `Remove-Item`。
7. 日备与周备为独立完整恢复点；轮转只按有效备份的 `completedAtUtc` 排序，无效目录保留供人工取证。
8. 隔离恢复要求回环 host allowlist、`three_fees_restore_drill_` 数据库前缀、显式隔离确认和空数据库。dump 出现 `CREATE/DROP DATABASE` 或 `USE` 即拒绝；脚本不创建、清空、DROP 或清理数据库。
9. 成功恢复写 `restore-drill-result.json` 和包含其 SHA-256 的 `RESTORE_READY.json`；RTO 只接受二者匹配的成功演练，失败演练不生成 READY。
10. 健康脚本只输出聚合错误/疑似秘密数量，不输出命中日志正文。worker 按当前项目手册继续要求 Manual/Stopped。
11. 本仓库不创建计划任务。运行手册只给频率基线：健康 15 分钟、日备失败每小时有限重试、20h 预警/24h 失败、每周周备、季度及大版本前恢复演练。

### 环境、API 与数据契约

- 新增秘密来源名称（仅名称，无值）：
  - `THREE_FEES_BACKUP_DB_USERNAME` / `THREE_FEES_BACKUP_DB_PASSWORD`。
  - `THREE_FEES_RESTORE_DB_USERNAME` / `THREE_FEES_RESTORE_DB_PASSWORD`。
- 口令只从进程环境或 `Read-Host -AsSecureString` 读取，不写 PSD1/Markdown，不使用 `--password` 参数；仅在子进程期间临时设置进程级 `MYSQL_PWD`，随后恢复原值。
- 无公共 REST API 变化，无 MySQL 业务表/迁移变化，无服务启动契约变化。
- 备份元数据 schemaVersion 为 1；恢复结果 schemaVersion 为 1。RPO 阈值 24 小时，RTO 阈值 4 小时，恢复演练默认最长新鲜度 90 天。

### 验证证据

1. 最终静态基线（Windows PowerShell 5.1）：

   ```powershell
   powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
     -File "D:\Three-fees\deploy\windows\ops\scripts\Test-OperationsBaseline.ps1"
   ```

   结果：退出码 0；31 项 PASS、0 FAIL。覆盖 9 个 PowerShell 文件 AST parser、PSD1、安全默认值、5 个 Apply 门、服务/计划任务零变更、无 `--password`、无带凭据数据库 URL、生产入口不直接删除，以及唯一删除函数的复核链。

2. 行为测试默认 dry-run：

   ```powershell
   powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
     -File "D:\Three-fees\deploy\windows\ops\scripts\Test-OperationsScripts.ps1"
   ```

   结果：退出码 0；只输出 `WouldRunTemporaryBehaviorTests`，声明不连接真实数据库、不修改系统；没有创建测试目录。

3. 临时目录全链路行为测试：

   ```powershell
   powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
     -File "D:\Three-fees\deploy\windows\ops\scripts\Test-OperationsScripts.ps1" -Apply
   ```

   结果：退出码 0；16 项 PASS。覆盖：根初始化 dry-run/Apply、备份 dry-run、缺少停写标记拒绝、有效停写时间窗、8 个模拟日备、5 个模拟周备、轮转 dry-run、精确 7+4、越界删除拒绝、恢复 dry-run、数据库 + 文件恢复、非空隔离库拒绝、日志/磁盘/备份健康，以及 RPO/RTO 通过。

   测试动态编译假 `mysqldump/mysql`，只在 `GetTempPath()` 下带 `ThreeFeesOpsTest-<随机ID>` 和专用标记的目录运行；清理前验证绝对路径、名称和标记。最终检查：遗留测试根 0。

4. 编码与仓库卫生检查：Ops 文件 CR 字节 0、UTF-8 BOM 0；静态秘密模式未发现私钥、供应商 key 或带凭据 MySQL URL。

5. 实现中真实发现并修复：第一次行为测试触发了 Windows 反斜杠路径穿越正则转义错误；修复后连续完整行为测试通过。没有掩盖失败或只依赖 parser 声明完成。

### 未完成、风险与上线门槛

- 尚未在真实 Windows Server、真实 MySQL 8.4、真实业务文件量和真实磁盘吞吐下运行。服务状态、8080/8100 回环监听、API/AI HTTP 健康和真实恢复耗时必须在预生产复验。
- 当前文件模块仍是占位，不能批准生产 `AppendOnly`。在此之前无人值守一致备份会被默认 Quiesced 门阻断；要满足每日 RPO，必须先交付可靠的外部停写维护流程，或由 Backend/Commander 正式批准并验证不可变文件契约。
- 脚本不创建隔离数据库，也不删除演练数据库/目录。DBA 必须用只拥有该沙箱 schema 权限的账号预建空库，并通过独立审批流程清理。
- 日备和周备都是全量，会重复占用磁盘。正式容量基线需按 11 个完整恢复点、staging 峰值和失败取证空间测量；低空间时脚本不应被改成越界删除。
- 单一备份根仍处于同一服务器/故障域。正式业务应把已验证恢复点复制到受控异机或离线介质；付费对象存储未经用户批准，不在本阶段引入。
- 数据规模增长后逻辑恢复可能接近 4 小时。必须以真实全量数据演练决定是否需要并行恢复、存储升级或经批准的备份产品。
- 计划任务尚未创建，这是有意边界。RPO 达标依赖服务器管理流程按手册建立专用账号、错过补跑、有限重试和告警。

### 交接

- Backend Agent：文件模块落地时必须证明并测试不可变/原子落盘/数据库最后提交/备份期间不物理删除，才能申请开启 AppendOnly；worker 消费者完成后通知 Deploy/Ops 更新健康期望。
- Deploy Agent：无需修改现有部署脚本。预生产时提供实际路径、MySQL CLI、专用账号和 Windows 服务/IIS 环境供联合验收；计划任务由服务器变更流程另行创建。
- Commander Agent：请独立复跑两条最终验证命令、审查默认 Quiesced 决策，并把已批准的 Ops 事实更新到 `docs/PROJECT_MANUAL.md`。本 Agent 因派单边界没有修改项目手册。
- 上线结论：仓库级 Ops 基线已完成并通过模拟验证；在真实 Windows Server 全量恢复演练完成前，不应标记“灾备已生产验收”。

## 2026-08-10 Commander 独立复验

- 独立执行 `Test-OperationsBaseline.ps1`：31 PASS。
- 独立执行 `Test-OperationsScripts.ps1 -Apply`：16 PASS；只使用随机命名、带专用标记的临时目录和假 MySQL 客户端，未连接真实数据库，测试根清理完成。
- Commander 批准继续以 `Quiesced` 为默认一致性模式；文件模块完成原子落盘、不可变和数据库最后提交契约测试前，不批准 `AppendOnlyFileContractApproved`。

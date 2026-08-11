# Windows Server 运维、备份与恢复手册

## 1. 范围与不变量

本手册承接 `docs/deployment/WINDOWS_SERVER_RUNBOOK.md` 的单机 Windows Server 形态，覆盖：

- `three-fees-api`、`three-fees-ai` 与当前保持 Manual/Stopped 的 `three-fees-worker` 健康巡检。
- 结构化日志、疑似秘密、磁盘余量和最新备份新鲜度检查。
- MySQL 逻辑快照与 `shared\files` 的一致时间窗备份。
- 最新 7 个日备 + 4 个周备的独立保留。
- 只写隔离目录和隔离空库的恢复演练。
- 试运行 RPO 24 小时、RTO 4 小时的可重复验收。

这些脚本不安装或修改 Windows 服务、IIS、注册表、防火墙或计划任务；本次也不会操作真实用户数据。所有写操作默认 dry-run，只有显式 `-Apply` 才执行。不要把“脚本可解析”当作生产恢复已经成功，正式上线前必须在预生产 Windows Server 完成一次全量演练。

## 2. 验收条件

上线前同时满足：

1. 配置文件来自 `deploy/windows/ops/config/operations.example.psd1` 的服务器本地副本，路径均为绝对路径，凭据值不在文件中。
2. 备份根和恢复演练根互不包含，也不与部署根、业务文件根或日志根重叠；二者都具有由初始化脚本创建且路径匹配的受管标记。
3. 备份先生成 MySQL `--single-transaction` 逻辑快照，再复制业务文件；文件不可变契约未获 Commander/Backend 批准时，必须有覆盖整个过程的有效外部停写标记。
4. 完整备份含 `database/database.sql`、`files/`、逐文件 SHA-256 `manifest.json`、`backup-metadata.json` 和最后写入的 `READY.json`；任一证明不匹配即不参与轮转或恢复。
5. 轮转 dry-run 清单经复核后才加 `-Apply`；删除只允许针对受管备份根 `daily` / `weekly` 中通过证明校验的直接子目录。
6. 恢复演练数据库主机必须在回环 allowlist 中，数据库名使用 `three_fees_restore_drill_` 前缀且库为空；脚本不创建、清空或删除数据库，也不覆盖已有表。
7. 最近恢复点年龄不超过 24 小时；最近一次成功隔离恢复耗时不超过 4 小时，且演练时间不早于配置的 90 天新鲜度阈值。
8. 静态与行为测试通过，行为测试只使用带随机标记的系统临时目录和假 MySQL 客户端。

## 3. 目录与权限

推荐把备份和恢复演练放在不同于系统盘的专用受控目录；示例：

```text
D:\ThreeFeesBackups\
  .three-fees-ops-root.json
  .staging\
  daily\<backup-id>\
  weekly\<backup-id>\

D:\ThreeFeesRestoreDrills\
  .three-fees-ops-root.json
  drill-<timestamp>-<random>\
```

ACL 至少满足：运行备份任务的专用低权限账号可读取 `shared\files`、执行 MySQL 客户端并写备份根；恢复演练账号只可写恢复根和指定隔离数据库。不要给备份账号正式库的 DDL/DML 权限，也不要使用 MySQL `root`。

先复制配置到服务器本地受控位置并修改路径。仓库样例保持默认安全值：`Quiesced`、`AppendOnlyFileContractApproved = $false`。

初始化先 dry-run，再显式 Apply：

```powershell
$opsConfig = 'D:\ThreeFeesConfig\operations.psd1'

.\deploy\windows\ops\scripts\Initialize-OperationsRoots.ps1 `
  -ConfigPath $opsConfig

.\deploy\windows\ops\scripts\Initialize-OperationsRoots.ps1 `
  -ConfigPath $opsConfig -Apply
```

脚本只会采用不存在或完全空的目录；不会把已有非空目录“认领”为备份根。

## 4. 秘密注入

安全索引：

| 用途 | 环境变量 | 使用者 | 保存位置 |
|---|---|---|---|
| 逻辑备份账号 | `THREE_FEES_BACKUP_DB_USERNAME` / `THREE_FEES_BACKUP_DB_PASSWORD` | 备份任务账号 | 受保护的任务运行环境或交互输入；值不入库 |
| 隔离恢复账号 | `THREE_FEES_RESTORE_DB_USERNAME` / `THREE_FEES_RESTORE_DB_PASSWORD` | 演练操作员 | 临时进程环境或交互输入；值不入库 |

配置可以改环境变量名称，但不能填秘密。密码不会作为 `mysqldump`/`mysql` 参数传递；脚本只在子进程运行期间设置进程级 `MYSQL_PWD`，随后恢复原值。命令失败仅返回退出码和受保护日志位置，不回显错误正文。

人工运行时可只设置用户名，并使用 `-PromptForDatabasePassword` 通过 `Read-Host -AsSecureString` 输入。无人值守任务应由组织批准的秘密注入方式设置进程环境，禁止把密码写进 `.ps1`、任务参数、Markdown 或普通日志。

备份账号最小权限由 DBA 按 MySQL 版本验证，一般需要目标库的 `SELECT`、`SHOW VIEW`、`TRIGGER`、`EVENT` 和执行例程所需权限；脚本使用 `--no-tablespaces`，不得为了绕过权限错误直接改用 root。

## 5. 健康、日志和磁盘巡检

只读巡检：

```powershell
.\deploy\windows\ops\scripts\Test-ThreeFeesOperationsHealth.ps1 `
  -ConfigPath $opsConfig -AsJson
```

用于监控任务时增加 `-FailOnUnhealthy`，Pass 退出 0，Warning/Fail 退出 2。检查项包括：

- API/AI 为 Running + Auto；worker 在持久消费者交付前为 Stopped + Manual。
- 8080/8100 只监听 `127.0.0.1` 或 `::1`，API/AI 健康返回 `status=UP`。
- 部署、文件、日志、备份和恢复目录存在，备份受管标记有效。
- 各盘可用 GB 与百分比均不低于阈值。
- API/AI 日志有新写入；worker 停止期间不要求日志新鲜。
- 最近日志只统计 ERROR/FATAL 和疑似秘密行数量，不打印命中正文。
- 最新经证明备份在 20 小时进入 Warning、超过 24 小时进入 Fail。

日志超过阈值时先调查来源、导出必要审计证据，再按组织日志保留策略处理。本基线不自动删除日志，避免误删取证材料。疑似秘密计数大于 0 时，应限制日志访问、轮换相关凭据并由 Backend 修复脱敏；不要把原日志粘贴到工单。

## 6. 一致时间窗备份

### 6.1 一致性模型

逻辑顺序固定为：

1. 记录保守恢复点并执行 MySQL 单事务逻辑快照。
2. 数据库快照成功后复制 `shared\files`，禁用 Junction 跟随。
3. 对数据库 dump 与业务文件逐项计算长度和 SHA-256。
4. 写元数据与清单，最后写 `READY.json`，校验后把 `.staging` 目录原子移动到 `daily` 或 `weekly`。

允许两种模式：

- `Quiesced`（默认）：外部维护流程已经停止所有业务写入，并提供有效 JSON 标记。标记只是证据，不会停止服务；如果标记过期或没有覆盖数据库 + 文件复制全过程，备份失败并保留 staging 取证。
- `AppendOnly`：只有 Backend 已证明“文件先写临时名、校验后原子落盘、落盘后不可变、备份期间不物理删除，最后才提交数据库引用”，并由 Commander 把配置批准项改为 `$true` 后才能使用。数据库快照在前、文件快照在后，因此数据库恢复点引用的文件都应存在于随后文件快照中。

停写标记格式（由已实际停止写入的外部维护流程生成，不由备份脚本伪造）：

```json
{
  "schemaVersion": 1,
  "purpose": "ThreeFeesWritesQuiesced",
  "deploymentRoot": "C:\\ProgramData\\ThreeFees",
  "writesQuiescedAtUtc": "2026-08-10T01:00:00Z",
  "expiresAtUtc": "2026-08-10T02:00:00Z"
}
```

### 6.2 日备与周备

先 dry-run：

```powershell
.\deploy\windows\ops\scripts\Invoke-ThreeFeesBackup.ps1 `
  -ConfigPath $opsConfig -BackupClass Daily `
  -QuiesceMarkerPath 'D:\Maintenance\writes-quiesced.json'
```

确认来源、目标、数据库和一致性模式后才 Apply：

```powershell
.\deploy\windows\ops\scripts\Invoke-ThreeFeesBackup.ps1 `
  -ConfigPath $opsConfig -BackupClass Daily `
  -QuiesceMarkerPath 'D:\Maintenance\writes-quiesced.json' -Apply

.\deploy\windows\ops\scripts\Invoke-ThreeFeesBackup.ps1 `
  -ConfigPath $opsConfig -BackupClass Weekly `
  -QuiesceMarkerPath 'D:\Maintenance\writes-quiesced.json' -Apply
```

日备和周备是独立全量恢复点。失败的 `.staging` 不参与恢复和轮转，也不会被脚本自动删除；调查失败原因和敏感日志后由受控流程处理。

## 7. 7 日备 + 4 周备轮转

轮转按 `completedAtUtc` 排序，只统计完整且 proof 有效的备份。无效目录保留并警告，不会被当作候选删除。

```powershell
.\deploy\windows\ops\scripts\Invoke-ThreeFeesBackupRetention.ps1 `
  -ConfigPath $opsConfig

.\deploy\windows\ops\scripts\Invoke-ThreeFeesBackupRetention.ps1 `
  -ConfigPath $opsConfig -Apply -Confirm:$false
```

第二条会永久删除超过最新 7 个日备、4 个周备的完整备份。执行前必须保存 dry-run 输出并确认路径。每个删除候选都会再次校验：绝对路径、受管根标记、非重解析点、`daily`/`weekly` 直接子目录、ID、元数据和 READY 哈希。脚本拒绝盘符根、共享根和任何越界路径。

## 8. 隔离恢复演练

### 8.1 DBA 先决条件

在与正式库逻辑隔离的本机演练实例/端口上，由 DBA 新建空数据库，例如：

```text
three_fees_restore_drill_2026q3
```

数据库主机必须在配置 allowlist 中；默认只允许回环。数据库必须为空。脚本不会创建、DROP、TRUNCATE 或清理数据库；若发现任何表，立即拒绝。不要把正式库名加入前缀或 allowlist。

### 8.2 执行

先对选定备份完整复核 SHA-256 并 dry-run：

```powershell
$backup = 'D:\ThreeFeesBackups\daily\20260810T010000000Z-1234abcd'

.\deploy\windows\ops\scripts\Invoke-ThreeFeesRestoreDrill.ps1 `
  -ConfigPath $opsConfig -BackupPath $backup `
  -SandboxDatabaseName 'three_fees_restore_drill_2026q3'
```

确认数据库实例、空库名和恢复目录后：

```powershell
.\deploy\windows\ops\scripts\Invoke-ThreeFeesRestoreDrill.ps1 `
  -ConfigPath $opsConfig -BackupPath $backup `
  -SandboxDatabaseName 'three_fees_restore_drill_2026q3' `
  -IsolationAcknowledgement 'ISOLATED-RESTORE-ONLY' -Apply
```

演练会：确认目标空库、导入 SQL、确认恢复后表数大于 0、复制文件并逐项复核 SHA-256，最后写 `restore-drill-result.json` 和包含结果哈希的 `RESTORE_READY.json`。RTO 验收只接受二者匹配的成功演练。失败只保留最小失败结果和受保护 stderr，不会伪造 READY，也不清理现场。演练结束后由 DBA 的独立审批流程删除隔离数据库和演练目录；本仓库不自动删除恢复证据。

## 9. RPO 24h / RTO 4h 验收

```powershell
.\deploy\windows\ops\scripts\Test-ThreeFeesRecoveryObjectives.ps1 `
  -ConfigPath $opsConfig -AsJson
```

- RPO 使用当前基准时刻减去最新有效备份的 `recoveryPointUtc`；阈值 24 小时。
- RTO 使用最近成功隔离恢复演练的实测开始/完成时间；阈值 4 小时。
- 演练超过 90 天即使历史耗时合格也判失败。
- 监控使用 `-FailOnBreach` 获得非零退出码。

验收记录至少保存：发布版本/commit、备份 ID、备份类型与 proof 哈希、恢复演练 ID、隔离实例标识、开始/完成时间、RPO/RTO 结果、操作者和复核人。不要保存密码、令牌、完整环境变量或敏感日志正文。

## 10. 建议频率（本仓库不创建计划任务）

| 作业 | 建议频率 | 失败处理 |
|---|---|---|
| 健康巡检 | 每 15 分钟 | 非零立即告警；10 分钟内人工确认 |
| 日备 | 每日低峰；失败每小时重试，最多 6 次 | 备份年龄 20h 预警，24h 阻断 RPO 验收 |
| 周备 | 每周一次，避开日备并发 | 下一个维护窗补跑 |
| 轮转 | 每次成功备份后，先 dry-run 证据再 Apply | 无效备份不删除，人工调查 |
| RPO/RTO 检查 | 每小时 | 任一失败升级为 P1 运维事件 |
| 隔离恢复演练 | 每季度及大版本上线前 | 失败不得发布“可恢复”结论 |

计划任务应由服务器管理流程以专用账号建立，动作只引用固定绝对脚本/配置路径，启用“错过后尽快运行”和有限重试；禁止在任务参数中写密码。本仓库没有注册任务的脚本，也不会在验证时触碰本机任务计划程序。

## 11. 验证命令

仓库静态验收：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File .\deploy\windows\ops\scripts\Test-OperationsBaseline.ps1
```

临时目录行为验收：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File .\deploy\windows\ops\scripts\Test-OperationsScripts.ps1 -Apply
```

行为测试默认也只输出预检；显式 `-Apply` 后动态编译假 MySQL 客户端，只写系统临时目录。测试根包含随机 ID 和专用标记，清理前再次验证绝对路径位于系统临时目录。它不会连接 MySQL、读取正式业务文件、删除真实备份、修改服务或创建计划任务。

## 12. 当前限制与升级条件

- Backend 的文件模块当前仍是占位，因此自动 `AppendOnly` 模式不得在生产启用；先用经过批准的停写维护窗，或等待不可变文件契约与并发测试交付。
- worker 尚无 durable consumer，健康契约继续要求 Manual/Stopped；消费者、租约、重试和重启恢复通过 Backend/Deploy/Ops 联合测试后再变更。
- `mysqldump` 是低成本逻辑备份，数据库规模增长导致 RTO 接近 4 小时时，应先测量并优化并行恢复/存储吞吐；引入付费备份产品需用户批准。
- 单机备份仍应复制到具备离线或异机故障域的受控介质；当前脚本只管理一个配置根，跨主机复制和云对象存储不在本阶段范围。

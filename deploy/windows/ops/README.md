# Windows Ops 基线

本目录只包含纯 Windows Server 的运维检查、备份、轮转和隔离恢复演练工具。它不会安装或修改 IIS、WinSW 服务、Windows 服务、注册表、防火墙或计划任务。

安全默认值：

- 健康与恢复目标检查始终只读。
- 初始化目录、创建备份、轮转和恢复演练默认只输出预检计划；必须显式增加 `-Apply` 才写入。
- 备份和恢复目录必须先由 `Initialize-OperationsRoots.ps1 -Apply` 建立受管标记。
- 删除只存在于受保护的备份轮转函数中，且只接受已标记备份根内 `daily` / `weekly` 的直接子目录。
- 数据库凭据只从配置指定的环境变量或 `Read-Host -AsSecureString` 取得，不进入命令参数、配置或报告。
- 本仓库不创建计划任务；建议频率和人工审批边界见运维手册。

入口：

```text
config/operations.example.psd1
scripts/Initialize-OperationsRoots.ps1
scripts/Test-ThreeFeesOperationsHealth.ps1
scripts/Invoke-ThreeFeesBackup.ps1
scripts/Invoke-ThreeFeesBackupRetention.ps1
scripts/Invoke-ThreeFeesRestoreDrill.ps1
scripts/Test-ThreeFeesRecoveryObjectives.ps1
scripts/Test-OperationsBaseline.ps1
scripts/Test-OperationsScripts.ps1
```

完整流程见 [`docs/operations/OPERATIONS_RUNBOOK.md`](../../../docs/operations/OPERATIONS_RUNBOOK.md)。

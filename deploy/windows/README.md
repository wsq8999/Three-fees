# Windows 部署基线

此目录只包含纯 Windows Server 部署资产，不会自动修改开发机。正式操作请从
[`docs/deployment/WINDOWS_SERVER_RUNBOOK.md`](../../docs/deployment/WINDOWS_SERVER_RUNBOOK.md)
开始。

## 目录

```text
config/
  environment.example.psd1   非敏感配置与秘密变量名样例
  iis/web.config             IIS SPA、反向代理与安全头
  winsw/*.xml                两个 Windows 服务模板
scripts/
  Common.ps1                 路径、清单、进程与联接安全函数
  Build-Release.ps1          构建可校验发布包
  Install-ThreeFees.ps1      首次安装（默认只预检）
  Set-ServiceEnvironment.ps1 按服务写入环境变量（秘密交互输入）
  Upgrade-ThreeFees.ps1      版本化升级与失败自动切回
  Rollback-ThreeFees.ps1     选择已校验旧版本回滚
  Test-DeploymentBaseline.ps1 静态验收，不修改系统
```

## 安全默认值

- 所有会改变 IIS、Windows 服务、注册表或部署目录的脚本都要求显式传入 `-Apply`。
- API 监听 `127.0.0.1:8080`；生成报告 AI 助手由 Java 后端直接调用 Kimi。
- durable worker 以独立 `three-fees-worker` 自动服务运行；`role=all` 只用于本地临时测试。
- `APP_FILE_ROOT` 是真实业务文件根；报告字体必须通过绝对、可读的 `.ttf`/`.otf` `REPORT_FONT_PATH` 注入。
- IIS 仅代理 `/api/*` 与 `/actuator/health`；`/internal/*` 和其他 Actuator 端点不对外开放。
- 仓库不保存真实数据库口令、模型密钥或证书私钥。
- 不下载 WinSW、不安装 IIS 模块、不更改防火墙；管理员必须按运行手册验证来源与 SHA-256 后提供依赖。

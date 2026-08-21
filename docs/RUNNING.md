# 真实启动说明

本文档记录当前智能物业管理系统的真实本地启动方式。此方式用于人工测试和实地联调，不使用 H2 演示库，不启动 Python/FastAPI 服务，不走 mock 页面入口。

## 当前运行链路

```text
浏览器
  -> Vue 3 / Vite: http://127.0.0.1:5173
  -> Spring Boot: http://127.0.0.1:8080
  -> MySQL: three_fees
```

## 推荐启动方式

以后本机联调优先使用统一启动脚本。脚本会检查端口占用，读取本机
`KIMI_API_KEY`，并强制给后端进程注入 `AI_ENABLED=true`、Kimi 接口和模型配置。
脚本不会打印密钥值。

```powershell
cd D:\Three-fees
.\scripts\check-kimi-env.ps1
.\scripts\start-local.ps1
```

启动后访问：

```text
http://127.0.0.1:5173/
```

## 手动后端启动

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

## 前端启动

```cmd
cd /d D:\Three-fees\frontend

set "VITE_API_PROXY_TARGET=http://127.0.0.1:8080"
corepack pnpm install --frozen-lockfile
corepack pnpm dev --host 127.0.0.1 --port 5173
```

## 验证

后端：

```cmd
curl http://127.0.0.1:8080/actuator/health
```

前端：

```text
http://127.0.0.1:5173/
```

默认登录：

```text
用户名：admin
密码：123456
```

## 约束

- `dev` 是本地真实开发 profile，连接 MySQL。
- `THREE_FEES_PROCESS_ROLE=all` 只用于本机开发和自动化测试。
- 正式部署时必须拆分 API 和 worker。
- AI 由 Spring AI 在本项目内直接调用 Kimi；本机联调默认启用。
- 前端不直接调用 Kimi，Kimi 只由 Java 后端调用。
- `KIMI_API_KEY` 不写入源码、Markdown、日志或提交记录。
- 密码、Token、数据库凭据不写入源码、Markdown、日志或提交记录。

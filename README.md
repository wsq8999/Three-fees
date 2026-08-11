# 智能物业管理系统

本项目是智能物业管理系统的前后端工程，当前优先目标是让本机真实环境稳定运行，并按需求文档原型完成页面、流程和交互。

## 技术栈

- 后端：Java 21、Spring Boot、Maven、MySQL、Flyway
- 前端：Vue 3、TypeScript、Vite、Element Plus、ECharts
- 数据库：MySQL，业务数据从真实数据库读取
- AI：暂不启动独立 AI Demo；仅保留“生成报告 → 分析图片 → AI报告助手 → 回填报告区 → 人工确认 → 生成正式报告并下载 Word”的业务入口

## 项目结构

```text
backend/     Spring Boot 后端、REST API、数据库迁移、业务任务
frontend/    Vue 前端页面、路由、API 客户端、业务组件
contracts/   接口和数据契约
docs/        项目说明、架构、运行和验收文档
runtime/     本地运行日志、上传文件等运行期目录
```

## 本地真实启动方式（cmd）

### 1. 启动后端

打开一个 cmd 窗口：

```cmd
cd /d D:\Three-fees\backend

set "SPRING_PROFILES_ACTIVE=dev"
set "THREE_FEES_PROCESS_ROLE=all"
set "DB_URL=jdbc:mysql://127.0.0.1:3306/three_fees?characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED"
set "DB_USERNAME=root"
set "DB_PASSWORD=<你的数据库密码>"
set "INITIAL_ACCOUNT_BOOTSTRAP_ENABLED=true"
set "INITIAL_ACCOUNT_PASSWORD=123456"
set "APP_FILE_ROOT=D:\Three-fees\runtime\files"
set "AI_SERVICE_ENABLED=false"

mvnw.cmd spring-boot:run
```

后端启动成功后访问：

```text
http://127.0.0.1:8080/actuator/health
```

返回 `UP` 表示后端已连接数据库并正常运行。

### 2. 启动前端

再打开一个新的 cmd 窗口：

```cmd
cd /d D:\Three-fees\frontend

corepack pnpm install --frozen-lockfile
corepack pnpm dev --host 127.0.0.1 --port 5173
```

前端开发环境已在 `frontend/.env.development` 固定代理到后端：

```text
VITE_API_PROXY_TARGET=http://127.0.0.1:8080
```

浏览器打开：

```text
http://127.0.0.1:5173/
```

默认登录：

```text
用户名：admin
密码：123456
```

## 当前已验证

- 前端 `corepack pnpm typecheck` 通过。
- 后端 `mvnw.cmd -q -DskipTests compile` 通过。
- 本机浏览器从 `http://127.0.0.1:5173/login?redirect=/reports/generate` 登录后，可进入生成报告页。
- 生成报告页已从 MySQL 读取真实报账点任务，例如 `BP-NT-001`，并显示中文报账点名称、所属区域、超标类型和“分析图片”按钮。

## 开发约束

- API 统一使用 RESTful 风格和 `/api/v1` 前缀。
- 前端不得写死业务假数据；页面数据必须来自后端 API。
- 数据库存储使用 UTF-8/utf8mb4，页面不得出现乱码或 `????`。
- 密码、数据库凭据、AI Token 等敏感信息只允许通过环境变量提供，不写入源码和文档。
- 页面样式、字段、按钮、跳转和交互以需求文档原型图为准。

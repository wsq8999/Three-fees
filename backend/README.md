# Three Fees Backend

Java 21 / Spring Boot 4.1 模块化单体。MySQL 是业务事实源，浏览器通过服务端 Cookie
会话访问 `/api/v1`。

## 本地启动

1. 创建 MySQL 8.0.31 或 8.4 数据库，字符集使用 `utf8mb4`。
2. 在当前终端设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。
3. 首次空库启动时设置 `INITIAL_ACCOUNT_PASSWORD` 为项目约定的临时初始口令。
4. 启用 AI 稽核时设置 `AI_ENABLED=true`、`KIMI_API_KEY`、`KIMI_MODEL`，密钥只放进进程环境。
5. 执行 `./mvnw spring-boot:run`（Windows 使用 `mvnw.cmd`）。

AI 能力由 Spring AI 直接在 Spring Boot 内调用 Kimi 的 OpenAI 兼容接口，不需要启动 Python/FastAPI 服务。

初始口令仅从进程环境读取。迁移、源码和文档都不保存其明文；每个初始化账号在首次启动时
分别生成 BCrypt work factor 12 的随机盐哈希。

## 验证

```powershell
.\mvnw.cmd verify
```

默认验证使用 H2 的 MySQL 兼容模式覆盖安全与 HTTP 契约。发布前仍须在目标 MySQL 版本执行
Flyway 空库迁移和升级验证。

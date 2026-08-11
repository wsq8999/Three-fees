# REST API 规范

## 1. 目标

本规范约束公开浏览器 API。内部 AI API 使用相同的 HTTP 基础语义，但位于 `/internal/v1`，不得暴露到 IIS 公网路由。

## 2. URI 与命名

- 公开前缀固定为 `/api/v1`。
- 使用资源复数名词：`/users`、`/import-jobs`，不用 `/getUsers`、`/createTask`。
- 多词路径使用 kebab-case；路径变量是稳定标识，不使用用户名等可变字段。
- 子资源只表达真实从属关系，嵌套不超过两层。
- 查询条件使用 camelCase：`?cityCode=320100&page=0&size=20`。
- `page` 从 0 开始；`size` 默认 20，最大值由端点明确限制。

## 3. 方法与状态码

| 动作 | 方法 | 成功状态 | 说明 |
|---|---|---|---|
| 查询单个/列表 | `GET` | `200` | 不改变服务端状态 |
| 创建资源 | `POST` | `201` | 返回实体并提供 `Location` |
| 提交异步任务 | `POST` | `202` | 返回任务摘要并提供 `Location` |
| 全量替换 | `PUT` | `200`/`204` | 请求体包含完整可编辑表示 |
| 局部修改 | `PATCH` | `200`/`204` | 仅修改请求字段 |
| 删除/注销 | `DELETE` | `204` | 响应体为空 |

业务校验失败不得返回 `200`；认证失败 `401`，权限/数据范围失败 `403`，不存在 `404`，版本冲突 `409`/`412`，语义校验 `422`，限流 `429`。

## 4. JSON

- 字段 camelCase，枚举值 UPPER_SNAKE_CASE。
- 时间戳为带时区 ISO 8601，例如 `2026-08-10T08:30:00Z`；业务年月使用 `YYYY-MM`。
- 金额、用电量、倍率等精确十进制输出字符串，避免 JavaScript 浮点误差。
- 无值使用 `null` 或省略字段，端点契约必须固定；禁止以空字符串代替所有空值。
- ID 在超过 JavaScript 安全整数范围前即按字符串传输；公开 DTO 不暴露数据库内部字段。

## 5. 分页、排序与筛选

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

- 排序使用可重复参数：`sort=createdAt,desc&sort=id,desc`。
- 服务端对排序字段使用白名单，不把客户端字段直接拼接到 SQL。
- 大数据导出不得同步返回全量 JSON，必须创建 `export-jobs` 异步资源。

## 6. 错误

Content-Type 固定为 `application/problem+json`：

```json
{
  "type": "https://three-fees.example/problems/validation-failed",
  "title": "请求参数校验失败",
  "status": 422,
  "detail": "请修正标记字段后重试",
  "instance": "/api/v1/users",
  "code": "VALIDATION_FAILED",
  "traceId": "01J...",
  "fieldErrors": [
    {"field": "username", "code": "NotBlank", "message": "用户名不能为空"}
  ]
}
```

- `code` 是稳定的程序契约；`title`/`detail` 可本地化，前端不得依赖其精确文字。
- 不向客户端返回堆栈、SQL、文件绝对路径、密钥或下游原始敏感响应。
- 未知异常映射为通用 `INTERNAL_ERROR`，详细原因只进入脱敏服务端日志。

## 7. 会话与 CSRF

- 浏览器同源请求通过 HttpOnly Cookie 维持会话。
- 除安全方法外的请求必须携带 CSRF token；前端从可读 CSRF Cookie 或引导端点获取并放入约定请求头。
- `POST /sessions` 的失败统一使用通用认证错误，不提示账号是否存在。
- 注销必须使服务端会话失效并清理 Cookie。

## 8. 幂等与并发

- 导入、导出、AI、报告生成等可重试提交要求 `Idempotency-Key`。
- 同一操作者、端点和键在有效期内必须返回同一业务结果；请求摘要不同则返回冲突。
- 可编辑资源返回 `ETag`；客户端更新使用 `If-Match`，版本过期返回 `412 Precondition Failed`。

## 9. 上传与下载

- 上传使用 `multipart/form-data`，元数据和文件字段名固定；客户端文件名只作展示，存储名由服务端生成。
- 后端验证扩展名、MIME、文件签名、大小、压缩比和解压条目数。
- 下载通过受权 API 或短时一次性地址；响应设置安全文件名、正确 MIME 和 `X-Content-Type-Options: nosniff`。

## 10. 契约变更

- OpenAPI 是公共 API 的机器可读事实源；JSON Schema 是 AI 内部接口事实源。
- 兼容性新增字段默认可选；删除/改义/改类型必须进入新版本。
- 每次变更同步契约测试、后端、生成的前端类型、项目手册与相关角色报告。


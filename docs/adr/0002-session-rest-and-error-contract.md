# ADR-0002：同源会话、REST 资源与 RFC 9457 错误

- 状态：已接受
- 日期：2026-08-10

## 背景

系统是内部管理端，前后端同源部署，需要从第一天统一登录安全、资源命名、状态码、分页和错误结构。

## 决策

- 使用 Spring Session JDBC 与 HttpOnly/SameSite Cookie，生产启用 Secure；保留 CSRF 防护。
- 公共 REST 资源统一位于 `/api/v1`，资源名使用复数 kebab-case。
- 成功响应不套通用 `Result<T>`；创建、异步和删除分别使用标准 `201`、`202`、`204`。
- 错误统一为 `application/problem+json`，在标准字段外增加稳定 `code`、`traceId` 和可选 `fieldErrors`。
- 地市数据权限必须由后端强制实施；前端路由守卫仅改善体验，不作为安全边界。

## 结果

API 更容易被 OpenAPI 描述和生成类型；浏览器会话不暴露给 JavaScript；前后端可以围绕稳定错误码和标准 HTTP 语义联调。


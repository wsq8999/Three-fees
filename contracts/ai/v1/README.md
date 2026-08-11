# AI Internal Contract v1

该目录由 `ai-service/scripts/export_schemas.py` 从 Pydantic 严格模型生成，使用 JSON Schema
Draft 2020-12。Java 只能通过这些契约调用回环地址上的 sidecar；Python 不访问 MySQL。

所有 `/internal/v1` 请求使用 `Authorization: Bearer ...`，令牌来自双方进程环境中的
`AI_SERVICE_TOKEN`。请求必须携带契约版本、工作流版本、任务 ID、幂等键、输入 SHA-256
与 trace ID。目录和示例均不保存令牌值。

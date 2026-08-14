# REST API 规范

## 基础约定

- API 前缀：`/api/v1`。
- URL 使用复数名词和 kebab-case。
- JSON 字段使用 snake_case。
- 资源标识使用 UUID 字符串。
- 时间使用带时区的 RFC 3339 格式。
- FastAPI OpenAPI 是接口契约唯一来源。
- 业务请求使用 `X-City-Code` 表达当前城市，后端必须校验。

## 状态码

| 状态码 | 使用场景 |
|---|---|
| `200` | 查询或更新成功 |
| `201` | 同步创建资源成功 |
| `202` | 已接收异步任务 |
| `204` | 成功且无响应体 |
| `400` | 缺少城市上下文等业务请求错误 |
| `404` | 资源不存在或不属于当前城市 |
| `409` | 资源状态冲突 |
| `422` | 请求结构校验失败 |
| `500` | 未处理的服务端错误 |

创建分析运行返回 `202 Accepted`，并通过 `Location` 响应头给出运行资源地址。

## 错误响应

统一使用 `application/problem+json`：

```json
{
  "type": "urn:jiangsu-audit-agent:error:city_context_required",
  "title": "缺少城市上下文",
  "status": 400,
  "detail": "业务请求必须提供 X-City-Code 请求头",
  "instance": "/api/v1/audit-tasks",
  "code": "city_context_required",
  "trace_id": "..."
}
```

响应头始终返回 `X-Trace-Id`，客户端也可以传入同名请求头贯穿一次调用。

## 当前公共接口

| 方法 | 路径 | 状态码 | 用途 |
|---|---|---:|---|
| GET | `/health/live` | 200 | 服务存活检查 |
| GET | `/health/ready` | 200 | 数据库就绪检查 |
| GET | `/cities` | 200 | 获取13个城市助手 |
| GET | `/sites` | 200 | 搜索当前城市报账点 |
| POST | `/audit-cases` | 201 | 将一份已解析历史报告结构化为案例 |
| GET | `/audit-cases` | 200 | 查询当前城市历史案例，可按报账点过滤 |
| GET | `/document-types` | 200 | 获取后端统一材料类型 |
| POST | `/documents` | 201 | 上传并登记当前城市材料 |
| GET | `/documents` | 200 | 分页查询当前城市材料 |
| GET | `/documents/{document_id}` | 200 | 获取材料详情 |
| PATCH | `/documents/{document_id}` | 200 | 关联或解除历史报告的报账点 |
| GET | `/documents/{document_id}/content` | 200 | 下载原始材料 |
| POST | `/documents/{document_id}/parse-runs` | 201 | 创建并同步执行一次确定性解析 |
| GET | `/documents/{document_id}/elements` | 200 | 获取最近一次成功解析的顺序元素 |
| GET | `/document-parse-runs/{run_id}` | 200 | 获取解析状态、版本和失败原因 |
| GET | `/document-elements/{element_id}/content` | 200 | 读取受城市保护的图片元素 |
| DELETE | `/documents/{document_id}` | 204 | 软归档材料，保留原文件 |
| POST | `/audit-tasks` | 201 | 在当前城市创建稽核任务 |
| GET | `/audit-tasks` | 200 | 查询当前城市任务 |
| GET | `/audit-tasks/{task_id}` | 200 | 获取当前城市任务详情 |
| POST | `/audit-tasks/{task_id}/analysis-runs` | 202 | 创建一次Agent分析运行 |
| GET | `/analysis-runs` | 200 | 查询当前城市分析运行 |
| GET | `/analysis-runs/{run_id}` | 200 | 获取运行进度与结果 |
| POST | `/analysis-runs/{run_id}/correction-memories` | 201 | 将业务员原话解析成待确认纠错草稿 |
| PATCH | `/correction-memories/{memory_id}` | 200 | 确认或驳回纠错草稿 |
| GET | `/correction-memories` | 200 | 查询当前城市纠错，可按报账点过滤 |
| GET | `/correction-memories/{memory_id}` | 200 | 查看纠错来源、理解和确认状态 |

## 兼容策略

- `/api/v1` 内只能增加向后兼容字段和接口。
- 删除字段、改变含义或修改必填性时创建新的 API 主版本。
- 枚举值只增加不复用；废弃值保留解析能力直至主版本升级。
- 前端类型从 `contracts/openapi.json` 自动生成，禁止复制粘贴 DTO。

文件上传使用 `multipart/form-data`，由浏览器生成 boundary。数据库只保存相对存储键，REST 响应禁止返回服务器物理路径。

解析运行当前是同步操作，因此创建成功或失败记录均返回 `201`；响应中的 `status` 表达业务结果。待大文件转为后台任务时，契约将改为新增异步入口，不悄悄改变本接口语义。

创建 Agent 分析运行时，主流程的 `material_refs` 必须且只能包含一份已经成功解析的 `current_report`；辅助调试模式可包含一张或多张 `evidence_screenshot`，两种模式不能混用。后端逐项验证材料属于当前城市、当前任务和当前报账点；跨城市材料表现为资源不存在，跨任务材料返回稳定业务错误。

创建任务必须提交数据库已有的 `site_id`。后端会校验该报账点处于有效状态且属于 `X-City-Code` 指定城市，不再接受自由文本并隐式创建报账点。

纠错创建接口只接受已经完成的分析运行。创建结果固定为 `draft`，不会参与RAG；只有业务员通过PATCH明确改为 `confirmed` 后，后续同城市、同报账点运行才会读取。`rejected`、`draft` 和其他城市记忆均不可参与判断。

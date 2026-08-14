# 系统架构规范

## 当前形态

项目采用单仓库和模块化单体。Vue 前端只通过 REST API 访问后端；FastAPI 负责接口、城市上下文和任务入口；LangGraph 负责固定稽核流程编排。

```text
Vue Web
   │ REST / OpenAPI
   ▼
FastAPI API
   │
   ├── 业务模块 modules
   ├── LangGraph agents
   └── 外部适配 integrations
```

## 依赖方向

```text
api → modules / agents → integrations / repositories
```

- API 不包含业务规则。
- 业务模块不依赖 HTTP Request/Response。
- Agent 节点不直接访问数据库或模型 SDK。
- Integration 不反向依赖 API。
- 前端 `shared` 不依赖具体 `features`。

## 城市边界

所有用户可使用全部城市助手，但每次业务请求必须携带 `X-City-Code`。后端验证城市后生成可信上下文，任务、历史案例、检索和纠错始终以该上下文为过滤边界。

当前骨架已使用 PostgreSQL，并在所有城市业务查询中强制携带 `city_id`。数据库 Row-Level Security 作为上线前的纵深防护项，在业务表稳定后统一补充。

原始材料首期保存在已被版本控制忽略的 `data/uploads/{city_code}/{document_id}` 目录；DOCX 提取图片保存在该材料目录下按解析运行隔离的 `assets` 子目录。数据库只保存相对键。存储逻辑通过适配器隔离，后续切换 MinIO 或其他对象存储时不改变 REST 契约和业务表含义。

人工上传和历史目录批量导入共用 `source_document`、文件签名校验、城市隔离、去重、审计日志和解析服务。批量导入只是受控入口，不另建没有查询价值的“导入批次表”；一次性运行的文件级结果保存在 `data/import-results` JSON 清单中。

DOCX 解析是确定性预处理：按正文顺序提取标题、段落、表格和内嵌图片，写入 `document_element`。这一层不调用模型、不猜测超标原因；Kimi 随后按同样顺序联合分析文字、表格和图片，并以元素ID作为事实来源。

分析运行开始时，`AnalysisContextBuilder` 从数据库一次性构建可信 JSON 快照，包括任务、报账点、本次报告的全部文字/表格和图片引用、历史报告候选及原文元素定位。LangGraph 节点只消费该快照，不直接查询数据库，保证一次运行内部证据稳定，也保持工作流与仓储实现解耦。

LangGraph 是唯一流程编排层；LangChain Core 和 `langchain-openai` 只提供消息、模型与结构化输出组件，不使用第二套 Agent 编排。Kimi Provider 通过 OpenAI 兼容接口调用 `kimi-k3`，其特有的 Base URL、推理强度和错误映射均封装在 Integration 层。

DOCX 原文件、内嵌图片二进制和模型客户端通过 LangGraph Runtime 注入，仅在单次节点执行期间存在，不进入 state、checkpoint 或 API 响应。持久化结果只包含材料ID、元素ID、可核对事实、置信度、文档依据和不确定项。

历史检索首版遵守明确降级规则：有人工关联的同报账点历史报告时，只选择同点报告；没有时才在同城市已解析报告中按报账点名称相似度排序。名称相似度属于确定性候选排序，不宣称为语义理解或 AI 判断。

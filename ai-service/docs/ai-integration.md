# Kimi AI 接入规范

## 组件边界

- LangGraph：控制节点顺序、状态、进度和后续人工审核。
- LangChain：提供标准消息与模型调用组件，不负责业务编排。
- `KimiProvider`：封装 Kimi 地址、模型、严格结构化输出和安全错误。
- Pydantic：在供应商 JSON Schema 之后执行第二次业务校验。
- 业务服务：负责城市、任务、报账点和材料归属校验。

## 本地配置

真实值只写入已忽略的 `backend/.env`：

```dotenv
AI_PROVIDER=kimi
KIMI_API_KEY=
KIMI_BASE_URL=https://api.moonshot.cn/v1
KIMI_MODEL=kimi-k3
KIMI_EXTRACT_REASONING_EFFORT=low
KIMI_JUDGE_REASONING_EFFORT=high
```

国内 Kimi API 开放平台使用 `.cn` 服务地址；国际平台 Key 使用 `.ai` 地址。Key 与 Base URL 必须来自同一平台。

## 整份报告事实输出

`extract_facts` 当前联合分析 DOCX 中的标题、正文、表格和图片，提取：文档标题、报账点、账期、内容摘要、超标项目、指标数值、比较值、变化率、阈值、单位、原文明确陈述、单项置信度、整体置信度和不确定项。

- 看不清或文档没有提供的字段必须为 `null`，不能用任务信息替代报告事实。
- 数值使用纯十进制字符串，单位单独保存，避免浮点误差和单位混写。
- 指标和原文陈述必须引用真实 `document_element.id`，便于业务员定位核对。
- 已有原因文字只作为 `explicit_statements` 提取，不等于Agent已经验证其正确性。
- 模型内部思考内容不保存，只解析最终结构化 `content`。
- 图片二进制按本次运行元素白名单通过 Runtime 读取，不写入 LangGraph state。
- 超过 120,000 个文字字符或 30 张图片时明确拒绝，不静默丢弃材料；后续通过分块归并支持超大报告。

独立截图仍可通过辅助模式识别；其指标 `evidence_element_ids` 为空数组，避免伪造DOCX元素定位。

## 历史案例与RAG判断

`AuditCaseService` 使用同一套多模态适配器分析历史 DOCX 的全部解析元素，形成 `audit_case`。LangGraph 的 `retrieve_evidence` 只取当前城市数据：存在同报账点历史时全部纳入，没有时才选最多5份同城名称候选。`judge_reason` 将本次事实与这些历史案例组合后调用 Kimi，并同时校验当前元素ID、历史文档ID和案例ID，防止模型引用未检索证据。

## 人工纠错记忆

业务员原话由 `interpret_correction_v1` 整理为正确原因、原因类别、适用条件和本次证据元素ID，但初始状态始终为 `draft`。业务员可以修改AI理解后确认或驳回。只有同城市、同报账点的 `confirmed` 记忆进入原因判断；Kimi必须先判断本次事实是否满足适用条件，并返回实际采用的记忆ID。服务端会校验该ID确实属于本次检索快照。

## 测试与费用

自动化测试全部使用 Fake Provider，不访问公网、不消耗额度。真实 API 只在人工运行稽核任务或显式执行连通性测试时调用。

稳定错误码包括：`ai_authentication_failed`、`ai_rate_limited`、`ai_request_timeout`、`ai_connection_failed`、`ai_request_invalid`、`ai_output_invalid_json` 和 `ai_output_schema_invalid`。

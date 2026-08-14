# LangGraph 稽核工作流规范

## 固定流程

```text
validate_input
→ parse_documents
→ extract_facts
→ retrieve_evidence
→ judge_reason
→ draft_report
→ END
```

当前 LangGraph 已通过 Kimi K3 联合读取本次 DOCX 的文字、表格和图片，检索当前城市同报账点的全部历史报告及结构化案例，并返回 `rag_judgment_ready` 原因与历史相似性判断。报告生成尚未接入，因此报告保持 `not_generated`。

## 当前节点职责

- `validate_input`：确认城市、任务、报账点和本次 DOCX 已成功解析。
- `parse_documents`：确认标题、正文、表格、图片引用和历史报告元素已载入运行上下文。
- `extract_facts`：按 DOCX 原始顺序构造多模态消息，Kimi 联合识别全部元素，并用 Pydantic 二次校验。
- `retrieve_evidence`：使用同点优先、同城市降级的固定历史证据快照，并携带当前报账点已确认纠错记忆。
- `judge_reason`：把本次结构化事实、已确认纠错、历史案例和补充原文证据交给 Kimi。模型先判断纠错适用条件，再输出带双侧证据ID的原因与相似性判断；证据不足时明确返回 `insufficient_evidence`。
- `draft_report`：证据准备阶段不生成报告正文。

## 状态原则

- 状态只保存 JSON 可序列化的小型数据。
- 文字和表格可保存在状态快照中；原始 DOCX 与图片二进制只通过受控资源读取，不进入状态。
- 每个节点仅返回自己新增或修改的字段。
- 纠错采用独立REST草稿和确认资源；未来报告审核再通过checkpoint暂停和恢复。
- 每次运行使用独立 `run_id` 作为 LangGraph thread ID。
- Prompt 按节点和版本保存，不在 Python 文件中堆放长提示词。

## 后续节点

真实业务接入时在不改变公共运行资源的前提下补充：`validate_facts`、`calculate_metrics`、`rerank_evidence`、`human_review`、`write_memory` 和 `render_report`。

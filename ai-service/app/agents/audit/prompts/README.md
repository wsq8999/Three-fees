# Prompt版本规范

每个模型节点使用独立、可审计的版本文件，例如：

```text
extract_report_facts_v1.md
judge_reason_v1.md
draft_report_v1.md
draft_report_v2.md
```

Python代码只引用Prompt版本，不内嵌大段业务提示词；运行结果同时保存模型名和版本。

`draft_report_v2.md`在v1证据与计算边界上增加章节级、全文级精简要求。保留旧版本文件是为了能够解释历史分析运行，不允许直接覆盖后伪装成同一Prompt版本。

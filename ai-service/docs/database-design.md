# PostgreSQL 数据库设计规范

## 1. 数据库与Schema

项目使用独立数据库，不使用默认 `postgres` 数据库，也不与其他项目混库。

```text
数据库名：jiangsu_audit_agent
业务Schema：audit
LangGraph Schema：langgraph
应用连接账号：jiangsu_audit_app
```

- `audit` 保存本项目业务数据。
- `langgraph` 保存LangGraph checkpoint，由官方持久化组件管理，业务代码不得直接修改。
- `public` 只用于必要扩展；历史向量检索阶段在数据库中启用 `vector` 扩展。
- 数据库账号与系统登录用户无关。首期业务用户权限相同，但程序仍使用独立数据库账号连接。

## 2. 通用字段规范

- 业务主键使用 `uuid`，由应用生成。
- 固定13市的主键使用 `smallint`。
- 追加型日志主键使用 `bigint generated always as identity`。
- 时间使用 `timestamptz`，统一保存UTC时间。
- 金额、电量、功率使用 `numeric`，禁止使用浮点数。
- 可扩展元数据使用 `jsonb`，核心查询字段必须独立成列。
- 状态字段使用小写英文 `varchar`，由数据库约束和后端枚举共同校验。
- 城市业务表必须直接包含 `city_id`，不能只通过上级资源间接推导。
- 城市资源增加 `unique(city_id, id)`，下级表使用复合外键阻止跨城市错误关联。
- 可修改业务表包含 `created_at`、`updated_at` 和 `version`，其中 `version` 用于并发更新控制。
- 业务数据原则上不物理删除，使用状态、停用时间或版本链保留审计历史。

## 3. 第一阶段核心表

第一阶段已创建下列表，任务、运行与节点事件均由 PostgreSQL 持久化；LangGraph 原生检查点将在后续阶段接入 `langgraph` schema。

### 3.1 `city`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `smallint` | PK | 固定城市ID |
| `code` | `varchar(32)` | UNIQUE, NOT NULL | 南京为 `nanjing`，苏州为 `suzhou` |
| `name` | `varchar(32)` | UNIQUE, NOT NULL | 中文城市名 |
| `status` | `varchar(16)` | NOT NULL | `active`、`disabled` |
| `settings` | `jsonb` | NOT NULL DEFAULT `{}` | 城市级可扩展配置 |
| `created_at` | `timestamptz` | NOT NULL | 创建时间 |
| `updated_at` | `timestamptz` | NOT NULL | 更新时间 |

初始化迁移写入江苏13市，业务代码不得动态创建第14个城市。

### 3.2 `app_user`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK | 用户ID |
| `account` | `varchar(64)` | UNIQUE, NOT NULL | 登录账号或员工号 |
| `display_name` | `varchar(64)` | NOT NULL | 展示姓名 |
| `status` | `varchar(16)` | NOT NULL | `active`、`disabled` |
| `identity_provider` | `varchar(32)` | NOT NULL | 当前为 `local`，后续可改为统一认证来源 |
| `last_login_at` | `timestamptz` | NULL | 最近登录时间 |
| `created_at` | `timestamptz` | NOT NULL | 创建时间 |
| `updated_at` | `timestamptz` | NOT NULL | 更新时间 |

首期不创建角色、用户角色或用户城市权限表，所有登录用户功能相同。

### 3.3 `site`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK | 报账点ID |
| `city_id` | `smallint` | FK, NOT NULL | 所属城市 |
| `site_code` | `varchar(64)` | NOT NULL | 业务系统报账点编码 |
| `site_name` | `varchar(160)` | NOT NULL | 报账点名称 |
| `address` | `varchar(300)` | NULL | 地址 |
| `status` | `varchar(16)` | NOT NULL | `active`、`disabled` |
| `metadata` | `jsonb` | NOT NULL DEFAULT `{}` | 非核心扩展信息 |
| `version` | `integer` | NOT NULL DEFAULT 1 | 乐观锁版本 |
| `created_at` | `timestamptz` | NOT NULL | 创建时间 |
| `updated_at` | `timestamptz` | NOT NULL | 更新时间 |

唯一约束：`unique(city_id, site_code)`、`unique(city_id, id)`。

### 3.4 `audit_task`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK | 稽核任务ID |
| `city_id` | `smallint` | FK, NOT NULL | 可信城市上下文 |
| `site_id` | `uuid` | NOT NULL | 报账点ID，与 `city_id` 组成复合外键 |
| `task_no` | `varchar(40)` | NOT NULL | 城市内可读任务编号 |
| `audit_type` | `varchar(32)` | NOT NULL | 首期为 `electricity_over_limit` |
| `title` | `varchar(200)` | NOT NULL | 任务标题 |
| `question` | `text` | NOT NULL | 用户分析要求 |
| `status` | `varchar(32)` | NOT NULL | `draft`、`queued`、`analyzing`、`awaiting_review`、`completed`、`failed` |
| `created_by` | `uuid` | FK, NOT NULL | 创建用户 |
| `version` | `integer` | NOT NULL DEFAULT 1 | 乐观锁版本 |
| `created_at` | `timestamptz` | NOT NULL | 创建时间 |
| `updated_at` | `timestamptz` | NOT NULL | 更新时间 |

唯一约束：`unique(city_id, task_no)`、`unique(city_id, id)`。

### 3.5 `analysis_run`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK | 单次Agent运行ID |
| `city_id` | `smallint` | FK, NOT NULL | 所属城市 |
| `task_id` | `uuid` | NOT NULL | 与 `city_id` 组成复合外键 |
| `run_no` | `integer` | NOT NULL | 同一任务内运行序号 |
| `status` | `varchar(24)` | NOT NULL | `queued`、`running`、`completed`、`failed`、`cancelled` |
| `progress` | `smallint` | NOT NULL DEFAULT 0 | 0至100 |
| `current_node` | `varchar(64)` | NOT NULL | 当前LangGraph节点 |
| `workflow_version` | `varchar(32)` | NOT NULL | 工作流版本 |
| `state_snapshot` | `jsonb` | NOT NULL DEFAULT `{}` | 业务状态摘要，不保存文件二进制 |
| `result` | `jsonb` | NULL | 本次结构化结果 |
| `error_code` | `varchar(64)` | NULL | 稳定错误码 |
| `error_message` | `text` | NULL | 脱敏后的错误说明 |
| `created_by` | `uuid` | FK, NOT NULL | 发起用户 |
| `created_at` | `timestamptz` | NOT NULL | 创建时间 |
| `started_at` | `timestamptz` | NULL | 开始时间 |
| `finished_at` | `timestamptz` | NULL | 完成时间 |

唯一约束：`unique(task_id, run_no)`、`unique(city_id, id)`；检查约束：`progress between 0 and 100`。

### 3.6 `analysis_event`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `bigint identity` | PK | 追加型事件ID |
| `city_id` | `smallint` | FK, NOT NULL | 所属城市 |
| `run_id` | `uuid` | NOT NULL | 与 `city_id` 组成复合外键 |
| `sequence_no` | `integer` | NOT NULL | 本次运行内递增序号 |
| `node_name` | `varchar(64)` | NOT NULL | LangGraph节点名 |
| `event_type` | `varchar(32)` | NOT NULL | `started`、`progress`、`completed`、`failed` |
| `message` | `varchar(500)` | NOT NULL | 可展示进度文字 |
| `payload` | `jsonb` | NOT NULL DEFAULT `{}` | 节点补充信息 |
| `created_at` | `timestamptz` | NOT NULL | 发生时间 |

唯一约束：`unique(run_id, sequence_no)`；事件只追加，不更新和删除。

### 3.7 `audit_log`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `bigint identity` | PK | 审计日志ID |
| `city_id` | `smallint` | NULL | 系统级事件可为空 |
| `user_id` | `uuid` | NULL | 系统任务可为空 |
| `action` | `varchar(64)` | NOT NULL | 操作代码 |
| `entity_type` | `varchar(64)` | NOT NULL | 资源类型 |
| `entity_id` | `varchar(64)` | NOT NULL | 资源ID |
| `before_data` | `jsonb` | NULL | 修改前脱敏快照 |
| `after_data` | `jsonb` | NULL | 修改后脱敏快照 |
| `trace_id` | `uuid` | NULL | HTTP链路ID |
| `created_at` | `timestamptz` | NOT NULL | 操作时间 |

审计日志只追加，不通过普通业务接口修改或删除。

### 3.8 `source_document`

该表把“用户认识的一份材料”和“服务器保存的原始文件”作为一对一资源管理。当前不拆分文件表，因为首版每份材料只有一个原文件；等实际出现同一材料多版本或多文件需求时再新增从表。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK | 材料ID，同时用于隔离存储目录 |
| `city_id` | `smallint` | FK, NOT NULL | 所属城市，所有查询的隔离边界 |
| `site_id` | `uuid` | NULL | 可选报账点，与 `city_id` 组成复合外键 |
| `task_id` | `uuid` | NULL | 可选稽核任务，与 `city_id` 组成复合外键 |
| `document_type` | `varchar(32)` | NOT NULL, CHECK | `historical_report`、`current_report`、`evidence_screenshot`、`report_template` |
| `title` | `varchar(200)` | NOT NULL | 用户可检索、可理解的材料标题 |
| `original_filename` | `varchar(255)` | NOT NULL | 下载时恢复用户上传文件名 |
| `media_type` | `varchar(100)` | NOT NULL | 服务端按扩展名确认的响应媒体类型 |
| `size_bytes` | `bigint` | NOT NULL, CHECK | 文件大小和上传限制审计依据 |
| `sha256` | `varchar(64)` | NOT NULL | 内容完整性校验与同城重复上传识别 |
| `storage_key` | `varchar(500)` | UNIQUE, NOT NULL | 相对存储键，不保存机器绝对路径 |
| `ingestion_method` | `varchar(24)` | NOT NULL, CHECK | `manual_upload` 或 `batch_import`，区分材料进入方式 |
| `status` | `varchar(24)` | NOT NULL, CHECK | `uploaded`、`parsing`、`parsed`、`failed`、`archived` |
| `created_by` | `uuid` | FK, NOT NULL | 上传用户 |
| `version` | `integer` | NOT NULL DEFAULT 1 | 状态修改的版本号 |
| `created_at` | `timestamptz` | NOT NULL | 上传时间 |
| `updated_at` | `timestamptz` | NOT NULL | 最近状态变更时间 |
| `archived_at` | `timestamptz` | NULL | 软归档时间；归档不删除原文件 |

文件签名会在写库前校验；数据库响应不暴露 `storage_key`。上传和归档操作会写入 `audit_log`。

`current_report` 表示任务本次待分析的 DOCX，必须关联 `task_id` 并成功解析后才能进入 Agent。它复用现有解析运行和顺序元素表，不为单一入口新增重复表；相同文件可用于不同任务的重新分析。

历史报告可通过 `PATCH /documents/{id}` 关联 `site_id`。该操作不改变原文件，修改前后值写入 `audit_log`；LangGraph 检索时优先使用与任务 `site_id` 完全一致的历史报告。

### 3.9 `document_parse_run`

一份材料可能因解析器升级而重复解析，因此解析状态不能只覆盖在材料表上。本表保存每次尝试和准确失败原因，让运维人员能判断是文件损坏、旧 DOC 待转换，还是解析器缺失。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK | 单次解析ID |
| `city_id` | `smallint` | FK, NOT NULL | 城市隔离边界 |
| `document_id` | `uuid` | NOT NULL | 与 `city_id` 组成复合外键 |
| `run_no` | `integer` | NOT NULL, CHECK | 同一材料从1开始的解析序号 |
| `status` | `varchar(24)` | NOT NULL, CHECK | `queued`、`running`、`completed`、`failed` |
| `parser_name` | `varchar(64)` | NOT NULL | 实际解析器名称，如 `docx_ooxml` |
| `parser_version` | `varchar(32)` | NOT NULL | 结果可复现所需的解析器版本 |
| `element_count` | `integer` | NOT NULL, CHECK | 本次成功生成的元素数量 |
| `error_code` | `varchar(64)` | NULL | 稳定失败分类，供前端和重试策略使用 |
| `error_message` | `text` | NULL | 可展示且不含敏感信息的失败说明 |
| `created_by` | `uuid` | FK, NOT NULL | 发起解析的用户 |
| `created_at` | `timestamptz` | NOT NULL | 创建时间 |
| `started_at` | `timestamptz` | NULL | 实际开始时间 |
| `finished_at` | `timestamptz` | NULL | 完成或失败时间 |

唯一约束：`unique(document_id, run_no)`、`unique(city_id, id)`。同一材料不允许同时存在多个 `queued/running` 运行。

### 3.10 `document_element`

该表是原始报告与后续 AI/OCR 之间的证据层。正文、表格和图片按原文顺序入库；AI 后续只能引用这些可追溯元素，不能只保存一段无法定位来源的总结。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `bigint identity` | PK | 元素ID |
| `city_id` | `smallint` | FK, NOT NULL | 城市隔离边界 |
| `document_id` | `uuid` | NOT NULL | 原始材料，与城市组成复合外键 |
| `parse_run_id` | `uuid` | NOT NULL | 产生本元素的解析运行，与城市组成复合外键 |
| `sequence_no` | `integer` | NOT NULL, CHECK | 元素在文档正文中的稳定顺序 |
| `element_type` | `varchar(24)` | NOT NULL, CHECK | `heading`、`paragraph`、`table`、`image` |
| `section_title` | `varchar(200)` | NULL | 元素所属章节，便于检索与审阅 |
| `content_text` | `text` | NULL | 正文或表格的可检索文字 |
| `content_data` | `jsonb` | NOT NULL | 表格二维数组、Word样式、图片原名等结构信息 |
| `asset_storage_key` | `varchar(500)` | NULL | 图片元素的相对存储键 |
| `media_type` | `varchar(100)` | NULL | 图片响应媒体类型 |
| `source_locator` | `jsonb` | NOT NULL | 原正文索引、关系ID等可追溯位置 |
| `created_at` | `timestamptz` | NOT NULL | 生成时间 |

唯一约束：`unique(parse_run_id, sequence_no)`。文字元素必须有 `content_text`，图片元素必须有 `asset_storage_key`，由数据库检查约束保证。

### 3.11 `audit_case`

一份已解析历史报告唯一对应一条结构化案例。该表是RAG的精简长期记忆，不替代原始Word和 `document_element` 证据。

| 字段组 | 用途 |
|---|---|
| `city_id`、`site_id`、`source_document_id` | 城市隔离、报账点归属和原报告追溯 |
| `billing_period`、`over_limit_items` | 历史事件的时间与超标对象 |
| `primary_reason`、`reason_category` | 可检索的历史原因及稳定分类 |
| `key_facts`、`evidence_element_ids`、`uncertain_items` | 结论依据、原文定位和不确定信息 |
| `confidence`、`model_name`、`prompt_version` | 质量判断与结果可复现性 |
| `status`、`error_code`、`error_message` | 批处理重试与运维定位 |
| `created_by`、`version`、时间字段 | 创建责任、重分析版本和审计时间 |

### 3.12 `correction_memory`

一条纠错对应一次来源分析运行。它保留原判断、业务员原话、AI结构化理解和人工确认状态，只有 `confirmed` 才能作为当前报账点的长期记忆。

| 字段组 | 用途 |
|---|---|
| `city_id`、`site_id` | 将纠错严格限制在一个城市和报账点 |
| `task_id`、`analysis_run_id` | 追溯产生误判的任务、材料、事实和模型结果 |
| `original_reason`、`original_judgment` | 保存AI被纠正前的完整判断快照 |
| `user_message` | 原样保存业务员大白话，避免审计信息丢失 |
| `corrected_reason`、`reason_category` | 经人工确认的标准原因和分类 |
| `applicability_conditions` | 下次判断这条记忆是否适用的条件 |
| `supporting_element_ids` | 与本次Word证据元素建立可追溯关系 |
| `status` | `draft`、`confirmed`、`rejected`、`archived`状态机 |
| `model_name`、`prompt_version`、`confidence` | 记录AI如何理解这句纠错 |
| 创建人、确认人、版本和时间 | 人工确认责任与变更审计 |

## 4. 后续迁移表

### 材料处理下一阶段

- `extracted_fact`：AI/OCR提取字段、值、单位、置信度和证据位置。
- `calculated_metric`：规则公式、输入事实、规则版本和计算结果。

### 历史知识与纠错阶段

- `evidence_chunk`：可检索证据、全文索引、向量、页码和图片坐标。
- `reason_category`：城市原因分类。
- `rule_definition`：城市规则和版本。

### 报告阶段

- `report`：任务报告主记录和当前版本。
- `report_version`：AI草稿、人工修改、模板版本、DOCX和PDF位置。
- `report_template`：通用或城市专属模板版本。

## 5. 迁移顺序

1. `0001_core_identity_and_tasks`：核心7表及13市初始化数据。
2. `0002_source_document`：原始材料登记、存储和归档。
3. `0003_document_parsing`：解析运行和文档顺序元素。
4. `0004_current_report_type`：增加任务本次 DOCX 材料类型，不新增业务表。
5. `0005_audit_case`：历史报告结构化案例。
6. `0006_correction_memory`：业务员纠错、AI理解和人工确认状态。
7. `0007_langgraph_persistence`：LangGraph checkpoint和证据索引。
8. `0008_reports`：报告、版本和模板。

每次迁移必须包含中文说明、约束名称、升级路径和可验证的回滚策略。数据库密码只存放在本地 `.env` 或正式密钥系统，不写入代码、迁移文件和Git。

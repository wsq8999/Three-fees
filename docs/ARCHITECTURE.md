# 系统架构

## 1. 组件边界

```mermaid
flowchart LR
    Browser["浏览器：Vue 管理端"]
    IIS["IIS：HTTPS、静态资源、反向代理"]
    API["Java API：认证、REST、业务规则"]
    Worker["Java Worker：导入、导出、AI、文档任务"]
    MySQL[("MySQL：唯一业务事实源")]
    Files[("ACL 文件目录：上传、Word、PDF")]
    AI["Python AI Sidecar：无状态原子能力"]
    Provider["外部 AI Provider：可选、脱敏输入"]

    Browser --> IIS
    IIS -->|/api/v1| API
    API --> MySQL
    API --> Files
    Worker --> MySQL
    Worker --> Files
    Worker -->|/internal/v1 + 服务令牌| AI
    AI -.->|仅明确启用时| Provider
```

公开网络只到 IIS。MySQL、文件目录和 AI sidecar 不对浏览器开放。Python 不访问 MySQL；Java 负责授权、业务状态、重试和审计。

## 2. 后端模块

```mermaid
flowchart TB
    Identity["identity：会话、账号、角色"]
    Organization["organization：省/地市范围"]
    Importing["importing：四类导入、批次、校验"]
    Billing["billingpoint：主数据与月快照"]
    Audit["audit：资格、计算、超标结论"]
    Dashboard["dashboard：聚合概览"]
    Report["report：草稿、正式报告、历史与修正"]
    AIModule["ai：任务编排、sidecar 适配、记忆选择"]
    FileModule["file：安全存储、摘要、下载"]
    OperationLog["operationlog：关键操作审计"]

    Identity --> Organization
    Importing --> Billing
    Billing --> Audit
    Audit --> Dashboard
    Audit --> Report
    Report --> AIModule
    Report --> FileModule
    Identity -.授权上下文.-> Importing
    Identity -.授权上下文.-> Billing
    Identity -.授权上下文.-> Report
    OperationLog -.审计切面.-> Identity
    OperationLog -.审计切面.-> Importing
    OperationLog -.审计切面.-> Report
```

模块依赖必须指向对方公开的 application API，不得跨模块直接调用 Mapper 或操作表对象。

## 3. 登录流

```mermaid
sequenceDiagram
    participant B as 浏览器
    participant I as IIS
    participant J as Java API
    participant D as MySQL

    B->>I: POST /api/v1/sessions
    I->>J: 同源代理
    J->>D: 按用户名读取账号/角色/地市
    J->>J: 自适应哈希校验、账号状态检查
    J->>D: 写 JDBC Session 与登录审计
    J-->>B: 201 + HttpOnly Cookie + 当前账号
    B->>J: GET /api/v1/sessions/current
    J-->>B: 角色与数据范围
```

认证失败不暴露账号是否存在。除安全方法外的浏览器请求必须经过 CSRF 校验。

## 4. 导入与激活流

```mermaid
sequenceDiagram
    participant B as 浏览器
    participant A as Java API
    participant D as MySQL
    participant W as Java Worker
    participant F as 文件目录

    B->>A: POST /api/v1/import-jobs + Idempotency-Key
    A->>F: 流式保存并计算 SHA-256
    A->>D: 创建批次/任务 PENDING
    A-->>B: 202 + Location: /api/v1/tasks/{id}
    W->>D: 领取租约，状态 RUNNING
    W->>F: 读取文件
    W->>D: 分块写 staging 与错误行
    W->>D: 校验完整后事务激活新批次、替代旧批次
    W->>D: 创建重新计算任务，当前任务 SUCCEEDED
    B->>A: GET /api/v1/tasks/{id}
    A-->>B: 进度、统计、错误摘要
```

解析失败或字段错误不会产生半激活批次；旧批次保留以便审计。

## 5. AI 报告流

```mermaid
sequenceDiagram
    participant B as 浏览器
    participant J as Java API/Worker
    participant D as MySQL
    participant P as Python Sidecar
    participant M as 外部模型（可选）

    B->>J: 创建报告草稿/发送消息
    J->>D: 校验权限、保存任务与确定性审计事实
    J->>D: 选择同地市记忆（同计费点优先，最多 5 条回退）
    J->>P: /internal/v1 原子请求（脱敏、版本、摘要、trace）
    P->>M: 最小必要上下文（仅真实 Provider 启用时）
    M-->>P: 严格 JSON
    P->>P: Schema 与证据白名单校验
    P-->>J: 结构化结果或稳定错误码
    J->>D: 再校验、保存任务和草稿版本
    J-->>B: 轮询可见结果
```

确定性数值由 Java 计算，AI 不得覆盖。问答不创建草稿版本；编辑、图片分析和历史恢复才创建版本。只有人工确认的修正/最终结论进入后续记忆。

## 6. 部署进程

| 服务 | 入口 | 职责 | 公开性 |
|---|---|---|---|
| IIS Site | `443` | HTTPS、SPA、压缩、缓存、`/api` 反代 | 公开 |
| `three-fees-api` | 回环 Java 端口 | REST 与认证 | 仅 IIS |
| `three-fees-worker` | 无公开端口 | 持久任务消费 | 不公开 |
| MySQL | `3306` | 业务数据库 | 仅受控主机/网段 |

首期为单机可试运行部署。Redis、外部 MQ、对象存储、微服务和 Kubernetes 均不是当前依赖。

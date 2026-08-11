# Java 后端开发规范

## 1. 模块与包

顶层包按业务特性拆分，不按技术层建立全局 `controller/service/mapper`：

```text
com.threefees
  identity/
    api/
    application/
    domain/
    infrastructure/
  organization/
  importing/
  billingpoint/
  audit/
  dashboard/
  report/
  ai/
  file/
  operationlog/
```

- `api`：HTTP DTO、Controller、参数校验和状态码映射。
- `application`：用例编排、事务边界、数据范围和端口接口。
- `domain`：业务规则、值对象和领域错误，不依赖 Web/MyBatis。
- `infrastructure`：MyBatis、文件、HTTP 客户端和框架适配。
- 跨模块只能调用对方公开 application API；禁止直接访问对方 Mapper/表对象。

## 2. 类型与命名

- 类/枚举 PascalCase，方法/字段 camelCase，常量 UPPER_SNAKE_CASE。
- API 请求/响应优先使用不可变 `record`，不得直接暴露数据库行对象。
- 精确业务数值使用 `BigDecimal`，明确 scale 和 RoundingMode；禁止 `double`。
- 标识使用值对象或明确的 `Long`/`UUID`，不要以裸 `String` 传遍所有层。
- 时间点使用 `Instant`，业务本地日期使用 `LocalDate`，年月使用 `YearMonth`。
- 返回集合不使用 `null`；方法入参和返回值不滥用 `Optional`，它主要用于单值查询结果。

## 3. 依赖与对象创建

- 使用构造器注入；禁止字段注入。
- 不使用 Lombok，避免隐藏构造、相等性和日志字段。
- 不建立万能 `BeanUtils`/`Map<String,Object>`；映射函数放在所属特性边界并保持显式。
- 重复出现且具有业务含义时才抽象；不要为了“复用”制造无业务语义的基类。

## 4. Controller 与应用服务

- Controller 只做协议转换、校验和调用用例，不写 SQL、事务和业务分支。
- 事务位于 application 用例边界；只读用例显式标记只读事务。
- 权限在进入业务读取/写入前校验；地市范围必须进入查询条件，不能先查全量再在 Java 过滤。
- 外部调用设置连接/读取超时；只有可恢复错误按有界退避重试。

## 5. MyBatis 与 SQL

- SQL 参数全部绑定，动态排序和列名必须白名单；禁止字符串拼接用户输入。
- 查询明确列名，不使用 `SELECT *`。
- Mapper 方法只表达持久化操作，复杂业务条件由 application/domain 决定。
- 批量操作分块并设置上限；导入先进入 staging，再在同一激活事务完成批次切换。

## 6. 异常与日志

- 领域/应用错误携带稳定错误码，由统一异常处理映射 Problem Details。
- 不以异常处理可预期循环或分支；捕获异常后必须转换、补充安全上下文或恢复，否则继续抛出。
- 日志使用参数化模板和结构字段；每个请求携带 traceId。
- 口令、Cookie、Authorization、AI key、完整上传内容和个人敏感字段不得进入日志。

## 7. 测试

- domain：纯单元测试，覆盖边界、精度、闰年、空值和非法状态。
- application：用例测试，验证权限、事务意图、幂等与状态机。
- api：MockMvc/安全测试，验证状态码、Content-Type、CSRF 和错误码。
- infrastructure：MySQL 兼容集成测试；若当前环境不能运行容器，至少保持可显式启用的真实 MySQL 测试配置并记录限制。
- 测试名说明行为，例如 `cityUserCannotReadAnotherCity()`，不写 `test1()`。

## 8. 构建门禁

`mvnw verify` 必须包含编译、测试、格式、静态规则和覆盖率检查。任何跳过都必须是显式 profile，并不得成为默认 CI 路径。


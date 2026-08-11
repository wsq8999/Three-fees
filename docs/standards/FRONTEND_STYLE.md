# Vue 前端开发规范

## 1. 目录与职责

```text
src/
  api/           生成类型、HTTP 客户端和端点适配
  assets/        构建期静态资产
  components/    跨特性可复用组件
  composables/   可组合状态与副作用
  layouts/       页面骨架
  router/        路由与权限元数据
  stores/        Pinia 业务状态
  styles/        设计令牌和全局基础样式
  types/         非 OpenAPI 的前端类型
  views/         路由页面，按业务特性分目录
```

- 页面负责组装，不把所有逻辑塞入单个 `.vue`。
- 组件只有在两个真实场景复用或边界清晰时抽取；禁止过早建立万能表格/表单框架。
- 特性私有组件放在对应 `views/<feature>/components`，避免污染全局组件目录。

## 2. TypeScript

- 开启 strict；禁止无理由的 `any`、非空断言和类型强转。
- API 类型来自契约或集中声明，不在页面内重复手写不同版本。
- 运行时外部数据必须在边界校验或通过可信客户端解析，类型声明不能代替校验。
- 枚举/状态使用字面量联合或生成类型；显示文案通过映射表，不在模板散落条件表达式。

## 3. 命名

- Vue 组件 PascalCase，composable 以 `use` 开头，Pinia store 以 `use...Store` 命名。
- 事件表达已发生事实，例如 `session-created`；处理函数使用 `handleSubmit` 等动作名。
- 布尔值使用 `is/has/can/should` 前缀。
- CSS class 使用业务语义或组件作用域，不以视觉位置命名业务含义。

## 4. API 与会话

- 所有请求经过统一客户端，负责 base URL、CSRF、trace、超时和 Problem Details 解析。
- 不在 `localStorage`/`sessionStorage` 保存 JWT、口令或会话 Cookie。
- `401` 清理本地会话状态并引导登录；`403` 展示无权限，不误报登录失效。
- 搜索使用防抖和请求取消；禁止旧请求覆盖新筛选结果。

## 5. 状态管理

- 服务端列表/详情优先保持在页面或查询抽象，只有跨路由共享且有生命周期的状态才进入 Pinia。
- store 不操作 DOM、不显示 Element Plus 消息；返回结构化结果，由页面决定交互反馈。
- 派生值使用 computed，不复制第二份可漂移状态。

## 6. 样式与性能

- 色彩、字体、间距、圆角、阴影和 z-index 使用集中设计令牌。
- 路由和 ECharts/编辑器等重模块按需加载；不要把所有图标和 Element Plus 组件打进首屏。
- 图片提供尺寸、懒加载和替代文本；业务图标优先 SVG。
- 生产静态文件使用内容哈希长期缓存，HTML 不长期缓存；IIS 开启 gzip/Brotli（可用时）。
- 首期不引入 CDN；未经用户批准不得新增付费前端服务。

## 7. 可访问性与状态

- 表单控件有可见标签；键盘可达；焦点样式不能移除。
- 颜色不是唯一状态提示；错误关联到具体字段并有页面级摘要。
- 所有数据页面设计 loading、empty、error、forbidden 四种状态。
- 表格在窄屏允许受控横向滚动，但页面骨架本身不得溢出。

## 8. 测试门禁

- Vitest：store、API 错误映射、权限判断和关键组件行为。
- Playwright：登录、会话恢复、无权限、注销和核心页面导航。
- ESLint、Prettier、Stylelint、`vue-tsc` 和生产 build 必须通过。
- 对 1366×768 关键页面做浏览器或截图验收；快照只能辅助，不能替代行为断言。


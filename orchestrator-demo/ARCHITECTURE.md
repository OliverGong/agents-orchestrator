# Orchestrator 架构说明

基于 **Spring Boot 4 + Spring AI 2.0** 的多 Agent 编排示例：以"旅行规划"为业务载体，
演示 **Orchestrator-Workflow（编排者模式）** 与 **Blackboard（黑板模式）** 的组合用法，
并引入**协作模板（WorkflowTemplate）**、**AOP 横切切面**与**全链路可观测性**
（Actor 事件时间线 + OpenTelemetry 追踪 + Prometheus 指标 + Grafana 可视化）。

**v1** 在 v0 控制台基础上解耦升级：**Docker 化部署**（多阶段构建，jar 封装为容器服务）、
**内置并行模板示例**、**控制平面（Control Plane）**——前端可视化配置 Agent 人格/
模型/校准规则/MCP 服务与工作流拓扑并热生效、**SSE 实时事件流**（详见第 9 节）。

**v3.0** 将控制器按领域包归位（`trip/` `observability/` `instance/`，`api/` 仅留横切），
并把前端解耦为独立 **TypeScript 控制台工程**（Vue 3 + Vite + Naive UI，独立 nginx
容器 + 反代，详见第 14 节）；随后完成**前后端目录系统性重组**——后端
"shared 内核 + contract 契约 + 领域模块三层（web/包根/infra）"、前端 feature-based
分区（详见第 3 节与第 15 节），console 切换 **pnpm** 工作流并引入 **Playwright
E2E 功能闭环**（mock 后端，见 §15.5）；随后按 **3~5 抽象法则**把顶层归组为
**kernel / core / governance / ops** 四 zone（§3），依赖规则由**架构守护测试**固化
（零依赖 fitness functions，§7.3/§15.6），核心层与 contract 契约面超阈值
按内聚子域再细分（contract → `spi/` `model/` `config/` `port/` 四子包，§15.3）。

## 1. 总体架构

```
                 POST /api/trips/plan
                          │
                          ▼
               ┌─────────────────────┐
               │ TripPlanningController │   Web 入口（DTO 进出）
               └──────────┬──────────┘     GET /api/trips/runs[/id] ◀── RunObservabilityController
                          ▼
               ┌─────────────────────────┐   ┌───────────────────────────┐
               │ TripPlanningOrchestrator │◀──│ WorkflowTemplate 协作模板  │
               │  （按模板波次执行）        │   │ stages + dependsOn + mode │
               └──────────┬──────────────┘   │ (orchestrator.workflow.*) │
                          │                  └───────────────────────────┘
                          │  OrchestratorContext（不可变，波次间合并）
        ┌─────────────┬───┴─────────┬─────────────┐
        ▼             ▼             ▼             ▼        ◀── AgentExecutionAspect (AOP)
  ┌───────────┐ ┌───────────┐ ┌────────────┐ ┌───────────┐      耗时日志 + span + Timer 指标
  │ Research  │▶│  Budget   │▶│ Itinerary  │▶│  Review   │
  │  Agent    │ │  Agent    │ │   Agent    │ │   Agent   │
  └─────┬─────┘ └─────┬─────┘ └──────┬─────┘ └─────┬─────┘
        │             │              │             │
  researchChat  budgetChat    itineraryChat  reviewChat      （各自 ChatClient Bean）
        │             │              │             │
        └─────────────┴──────┬───────┴─────────────┘
                             ▼
                     Ollama（本地 LLM，qwen:7b）

  观测链路：编排者/AOP ──tell──▶ ObservabilityActor（Actor 邮箱，事件时间线）
        ├─▶ GET /api/trips/runs/{runId}（整个 Agent 上下文回放）
        ├─▶ Micrometer Observation ──OTLP──▶ Grafana Tempo（追踪瀑布图）
        └─▶ MeterRegistry ──Prometheus──▶ Grafana（Agent 耗时/失败率仪表盘）
```

设计要点：

- **每个 Agent 都是抽象 `OrchestratorContext` 上的单一职责小组件**：
  上下文进、上下文出，只读上游贡献、只写自己的产出。
- **`OrchestratorContext` 是不可变黑板**：record + `withXxx()` 派生新实例，
  任何 Agent 都无法篡改前序结果，天然线程安全、可回溯。
- **编排者不懂业务，只认模板**：`TripPlanningOrchestrator` 按
  `WorkflowTemplate` 定义的阶段与依赖执行；阶段引用的 Agent 按 `name()`
  从容器解析，新增 Agent/模板无需改编排者代码。
- **串行/并发可配置**：`orchestrator.workflow.mode` 选择执行模式；
  并发模式下无依赖关系的阶段（同一波次）在虚拟线程上并行执行，
  贡献结果按模板声明序合并回快照。
- **AOP 承载横切关注点**：Agent 执行耗时/失败日志、span 与 Timer 指标由
  `AgentExecutionAspect` 统一织入，业务组件零观测噪音。
- **可观测性贯穿全链路**：编排者把每次运行折叠为事件时间线
  （mode/波次/并行同伴/上下文 JSON）交给 Actor 风格的 `ObservabilityActor`，
  REST 即可回放整个 Agent 上下文；追踪经 OTLP 进 Grafana Tempo，指标进 Prometheus。

## 2. 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Java | 25 | 运行时（虚拟线程承载并发波次与 Actor 消费者） |
| Spring Boot | 4.1.2 | Web（spring-boot-starter-webmvc）、AOP（aspectjweaver）、Actuator/OpenTelemetry 自动配置与测试 |
| Spring AI | 2.0.1 | ChatClient 抽象、结构化输出（entity → record） |
| Micrometer / OpenTelemetry | 1.17 / 1.62 | Observation→span（OTLP 导出）、Prometheus 指标注册表 |
| Grafana / Tempo / Prometheus | docker | 可视化栈：追踪瀑布图 + 指标仪表盘（`docker/` 一键启动） |
| Vue 3 + Vite + Naive UI / pnpm | 3.5 / 6 / 2.41 | v3.0 独立控制台工程（TypeScript strict，corepack 钉版 pnpm） |
| Playwright | 1.64 | 控制台 E2E 功能闭环：网络级 mock 后端 + SSE 回放，无需真实服务（§15.5） |
| Ollama | 本地 | LLM 推理（qwen:7b，可替换） |
| Maven Wrapper | — | 构建 |

## 3. 包结构

**工程根目录布局**（`orchestrator-demo/`，Maven 单模块 + 独立前端工程；
每层 3~5 个关键条目，细节 drill down 到注释与对应章节）：

```
orchestrator-demo/
├── pom.xml · mvnw · .mvn/            # 构建：Maven wrapper（钉 Java 25 / Spring Boot 4）
├── src/                              # 后端：main 源码（zone 包树见下）+ resources + test 镜像
├── console/                          # 前端：TypeScript 控制台工程（pnpm 工作区，§14 / §15.4）
├── Dockerfile · docker/ · config/    # 交付：镜像构建（§9）+ 可视化栈 + 运行时文件状态（§10）
└── docs/                             # 文档：BRANCHING.md 等工程文档
```

**后端 zone 包树**（`com.oli.orchestrator_demo`，v3.1 分组层）——
顶层只有**装配根 + 4 个 zone**，每个 zone 2~5 个模块（3~5 抽象法则），
每模块一行点出核心职责，类级细节经模块内三层与 §4/§9-13 各章 drill down：

```
com.oli.orchestrator_demo
├── OrchestratorDemoApplication      # 装配根：组件扫描四 zone，自身不含业务
├── kernel/                          # 内核层：零业务语义（R1 守护：禁 import 业务模块）
│   ├── shared                       # 横切件：web 异常/缓存 · aop 执行切面 · context 运行归因
│   └── contract                     # 契约面（单模块，子包仅导航）：spi/ Agent SPI · model/ 黑板+运行数据 · config/ 控制面配置 · port/ 跨域端口
├── core/                            # 编排核心：旅行规划问题域的运行时
│   ├── trip                         # 应用入口：POST /api/trips/plan → 按模板波次执行
│   ├── agent                        # 协作方：4 业务 Agent · 模板基类 · advisor/ 链 · config/
│   ├── workflow                     # 协作模板：stage / dependsOn / mode · 热切换单点
│   └── mcp                          # 外部集成：MCP 连接注册表 · 插桩工具（EIP 网关）
├── governance/                      # 治理面：SOP 数字化（配置/场景/元数据/发布/审计）
│   ├── control                      # 控制面：Agent 人格/模型/校准/MCP 热配置 + 加密
│   ├── scenario                     # 场景：零 token 推演 → 激活 → 运行
│   ├── meta                         # 元信息补录
│   ├── release                      # 发布流水线
│   └── teamlog                      # 团队审计日志
└── ops/                             # 观测运维面：运行状态的生产与消费
    ├── observability                # 事件总线 · SSE 通道 · 指标 · 健康（events/ metrics/ health/）
    └── instance                     # 运行实例注册与业务关联
```

**模块内分层约定**（v3.0 起，每个领域模块一致表达 MVC；zone 归属不改变本约定）：

- `web/` — 表现层（MVC-C）：Controller / SSE 端点，只做协议适配与参数校验；
- 包根 — 核心层（MVC-M）：领域模型、注册表、应用服务/引擎、持久化端口（`*Store` 接口）；
- `infra/` — 基础设施层：持久化适配器（`Db*` / `File*` / `Noop*`）与其装配配置（`*StoreConfig`）；
- `V`（视图）= `console/` 前端 SPA（见 §15.4），经 REST/SSE 与 `web/` 层同构对齐；
- 横切关注点（异常映射、缓存控制、AOP）统一收拢 `kernel/shared/`，不与任何业务域耦合；
- **drill down 路径**：zone（本树）→ 模块三层（本约定）→ 内聚子域子包
  （§15.3：advisor/config、events/metrics/health、contract 的 spi/model/config/port）→ 类（§4 与各版本章节）。

**前端工作区**（v3.0，feature-based，与后端模块树并列，见 §14 / §15.4）：

```
orchestrator-demo/console                 # Vue 3 + TypeScript SPA（pnpm 工作区）
├── src/app/                              # 应用装配层：main.ts · App.vue（壳）
│   ├── router/index.ts                   # 11 路由（history 模式）+ 通配兜底
│   └── stores/session.ts · control.ts    # 跨 feature 全局状态（Pinia）
├── src/shared/                           # 跨领域复用资产（横切件 ≈ kernel/shared + 契约 ≈ kernel/contract）
│   ├── api/http.ts · sse.ts              # 类型化 fetch + EventSource 单例
│   ├── types/api.ts · dag.ts             # REST/SSE 契约 DTO 单一事实源（镜像后端 record）
│   ├── components/DagDiagram · SwimlaneChart · JsonDetails
│   ├── styles/base.css                   # 全局样式（横切资产，与后端 kernel/shared 同构归位）
│   └── utils/format.ts
├── src/features/                         # 领域特性（顶层 = 侧栏分组 ≈ zone，组内子目录 ≈ 后端模块）
│   ├── scenario/                         # run/（trip）· dryrun/ · scenarios/（scenario）
│   ├── metadata/                         # agents/（control）· workflow/（workflow）· mcp/（mcp）· meta/（meta）
│   └── observability/                    # runs/ · logs/ · metrics/（observability）· instances/（instance）
├── e2e/                                  # Playwright 功能闭环（mock 后端，见 §15.5）
├── nginx.conf                            # SPA fallback + /api、/actuator 反代（SSE 关缓冲）
└── Dockerfile                            # node:22-alpine（corepack/pnpm）→ nginx:1.27-alpine
```

## 4. 核心抽象

### 4.1 OrchestratorContext（黑板）

```java
OrchestratorContext.start(request)              // 初始态：仅携带 TripRequest
    .withResearchResponse(research)             // Step1 追加
    .withBudgetResponse(budget)                 // Step2 追加
    .withItineraryResponse(itinerary)           // Step3 追加
    .withReviewResponse(review)                 // Step4 追加
    .mergeFrom(other)                           // 并发波次贡献合并（null 槽位从 other 补齐）
    .end();                                     // 终态：返回完整结果
```

- record 不可变：`withXxx()` 返回新实例，原状态永不被修改。
- `mergeFrom(other)` 服务于并发执行：所有仍为 null 的槽位从 `other` 补齐，
  已有贡献保持不变（先应用者胜出），合并结果与完成顺序无关、确定可复现。
- `end()` 是显式的终结语义，返回最终状态供 Controller 序列化输出。

### 4.2 Agent SPI 与模板基类

```java
public interface Agent<I, O> {
  String name();            // 模板按此名引用 Agent
  O execute(I input);       // 单一职责的工作单元
}
```

`AbstractContextAgent<R>` 把公共骨架收敛为一处：

| 方法 | 职责 | 子类是否实现 |
|---|---|---|
| `name()` / `execute()` | 终态：调用 respond → merge | 否 |
| `respond(ctx)` | 从黑板读取上游数据 → 构造 prompt → `entity(R.class)` | 是 |
| `merge(ctx, r)` | 把自家响应写回黑板 | 是 |

新增一个 Agent 只需：一个响应 record + 一个系统人格 Bean + 一个继承基类的组件。

**AOP 兼容性坑位**（改错即复发）：四个方法都不能是 `final`。CGLIB 代理由
Objenesis 实例化、**不执行构造器**——final 方法在代理实例上会直接读到
未初始化字段（实测 `name()` 返回 null 导致编排者 "Duplicate key null" 启动失败），
`execute()` 则完全无法被切面拦截。方法在代理上被覆写后委托给真实 target，
字段访问与通知织入才都正确。

### 4.3 结构化输出

每个 Agent 通过 `.call().entity(XxxResponse.class)` 让 Spring AI 自动完成
**JSON Schema 注入 → LLM 输出 → 反序列化为 record** 的闭环，
无需手写 JSON 解析；`AgentConfiguration` 中的人格提示词保证各 Agent 职责互不越界。

### 4.4 Agent 职责矩阵

| Agent | 读取 | 写入 | 系统人格 |
|---|---|---|---|
| ResearchAgent | `request` | `researchResponse` | 只做调研，不做预算/行程 |
| BudgetAgent | `request` + `researchResponse` | `budgetResponse` | 依据调研估成本，不许无声超支 |
| ItineraryAgent | `request` + `researchResponse` + `budgetResponse` | `itineraryResponse` | 只用给定素材排行程 |
| ReviewAgent | 全部 | `reviewResponse` | 校验一致性，不虚构价格/景点 |

### 4.5 响应净化（ResponseSanitizerAdvisor）

本地小模型（如 `qwen:7b`）的输出存在三类已实测的失败模式，均会导致
`entity()` 的 `BeanOutputConverter` 反序列化失败：

| 失败模式 | 实测样例 | 内置兜底 |
|---|---|---|
| Markdown 围栏包裹 | \```json … ```\ | Spring AI 2.0.1 自带 `MarkdownCodeBlockCleaner`，但仅当文本**首尾恰好**是围栏时生效；围栏前有说明文字则失效 |
| 结构损坏 JSON | 数组缺逗号、字符串未加引号 | 无 |
| 形状不符 | 期望对象 `{"dayPlans":[…]}`，返回裸数组 `[…]` | 无 |

`ResponseSanitizerAdvisor`（实现 `CallAdvisor`）注册在所有 Agent 共用的
ChatClient 上，在响应返回给转换器前按序执行三步净化：

1. **剥围栏**：正则剥离 ```` ```json ````/```` ``` ```` 围栏取其内容
   （覆盖内置 Cleaner 处理不了的"围栏前后有文字"场景）；
2. **提 JSON**：从说明文字中截取最外层 `{...}`/`[...]` 子串；
3. **形状修正**：输出为裸数组且目标 Schema 恰好只含**一个数组字段**时
   （Schema 从 `entity()` 注入的 `ChatClientAttributes.OUTPUT_FORMAT`
   context 属性读取），包装为 `{"dayPlans":[…]}`；多字段 Schema 无法
   消歧时不处理，交由重试兜底。

净化后通过 `ChatClientResponse.mutate().chatResponse(...)` 写回响应。

**两个关键坑位**（排障实录，改错即复发）：

- **注册顺序不能是 `Ordered.LOWEST_PRECEDENCE`**。终端 `ChatModelCallAdvisor`
  （真正调用模型的 advisor）的 order 同为 `Integer.MAX_VALUE`，同序会被其
  遮蔽导致本 advisor **一次都不会执行**；故取 `LOWEST_PRECEDENCE - 100`。
- **Schema 不在用户消息里**。2.0 的 `entity()` 把格式指令放入 request
  context（键 `spring.ai.chat.client.output.format`），而非拼接进
  prompt 文本；advisor 必须从 context 读取。

### 4.6 解析失败重试（AbstractContextAgent）

净化只做确定性修复；小模型仍可能产出**结构损坏**的 JSON（如数组缺逗号、
字符串未加引号），此时 `entity()` 抛 `JacksonException`。
`AbstractContextAgent.execute()` 对此类异常做一次自动重试
（`MAX_ATTEMPTS=2`，模型重新采样通常即可恢复），重试耗尽后异常向上传播，
由 `GlobalExceptionHandler` 映射为 502。三层防线依次为：

1. **净化**（advisor 层）：剥围栏、提 JSON、修形状；
2. **重试**（agent 模板层）：解析失败再采样一次；
3. **映射**（Web 层）：仍失败则 502，结构化错误信息返回客户端。

### 4.7 协作模板与执行模式（workflow 包）

模板与编排者**平级定义**，纯数据 + 纯计算，不含任何执行逻辑：

| 类型 | 职责 |
|---|---|
| `StageSpec`（record） | 一个阶段：`name`（阶段名，被其他阶段的 dependsOn 引用）、`agent`（要执行的 Agent 名）、`dependsOn`（前置阶段列表） |
| `ExecutionMode`（enum） | `SEQUENTIAL`（按声明序逐个执行）/ `CONCURRENT`（无依赖阶段并行） |
| `WorkflowTemplate`（record） | 模板整体：`name` + `mode` + `stages`；提供 `validate(availableAgents)` 与 `waves()` |
| `WorkflowProperties` | `orchestrator.workflow.*` 绑定；未配置 stages 时回落到内置模板 |

**校验规则**（`validate`，启动时 fail-fast）：阶段不得为空、阶段名不得重复、
每个阶段的 agent 必须存在于容器、依赖必须是**声明序中位于其前**的阶段——
该规则同时排除依赖环，并保证串行执行顺序良定义。

**波次分解**（`waves()`，Kahn 算法）：第一波是所有无未决依赖的阶段，
后续每波是依赖已被前序波覆盖的阶段；同一波内的阶段彼此独立，可并行。
分解不动时抛出依赖环异常。

**执行语义**（`TripPlanningOrchestrator`）：

- `SEQUENTIAL`：按声明序逐波执行，波内阶段**累积传递**上下文（与旧版
  固定流水线行为一致——内置模板下每个 Agent 恰好看到其全部前驱的贡献）。
- `CONCURRENT`：同一波内各阶段从**同一快照**出发，在
  `Executors.newVirtualThreadPerTaskExecutor()` 的虚拟线程上并行执行
  （`CompletableFuture.supplyAsync`）；全部完成后按**模板声明序**依次
  `mergeFrom` 合并贡献，先应用者胜出，结果与完成顺序无关。
  并发阶段的异常从 `CompletionException` 解包后按原类型向上传播，
  保证 502/503 映射语义与串行模式一致；失败瞬间立即 `shutdownNow()`
  中断同波兄弟任务——错误**即刻**到达调用方，不等慢任务收尾
  （注意 `CompletableFuture.cancel(true)` 并不中断运行中的任务，实测会
  让调用方在 `executor.close()` 上白等数分钟，故必须用 `shutdownNow`）。
- 内置默认模板（未配置 `orchestrator.workflow.stages` 时）：
  `research → budget → itinerary → review`，每个阶段依赖其全部前驱。

### 4.8 AOP：Agent 执行切面（aop 包）

`AgentExecutionAspect`（`@Aspect` + `@Around`）以切点
`execution(* com.oli.orchestrator_demo.kernel.contract.spi.Agent+.execute(..))` 织入**所有** Agent 调用
（SPI 在 kernel/contract/spi 包，故切点指向契约接口）：

- 成功：`{agent} completed in {n} ms`（INFO）；
- 失败：`{agent} failed after {n} ms: {message}`（WARN）后原样重抛；
- 观测信号：`agent.execute` Micrometer Observation（经 OTel 桥接为 span，
  属性 `agent`，OTLP 导出到 Tempo）+ `agent.execution.duration` Timer
  （tag `agent`/`outcome`，Prometheus 抓取）。

它替代了旧版散落在编排者里的逐 Agent 计时逻辑，且与模板/执行模式无关——
无论串行还是并发、哪个模板调度，耗时统计与观测信号自动生效。

**启用方式**：Spring Boot 4 已移除 `spring-boot-starter-aop`，直接依赖
`org.aspectj:aspectjweaver`（版本由 BOM 管理）即可；`AopAutoConfiguration`
仍在 spring-boot-autoconfigure 中，检测到 AspectJ 后自动启用注解式切面
（默认 CGLIB 代理，`proxyTargetClass=true`）。

### 4.9 可观测性（observability 包）

四路信号，串行/并发模式自动区分，均由编排者与 AOP 切面自动产出：

**① 事件时间线（Actor 风格，进程内）**。编排者在 run/stage 边界 `tell(...)`
一条 `AgentObservabilityEvent` 进 `ObservabilityActor` 的有界邮箱（生产者
永不阻塞）；单一消费者虚拟线程是唯一状态拥有者，把事件折叠进每运行的
`RunTimeline`（超过 100 个运行按插入序淘汰最旧）。事件携带：

- `mode` / `waveIndex` / `parallelPeers`：**中间状态是串行还是并发**由此一目
  了然（`parallelPeers` 非空即同波并行阶段）；
- `threadName` / `durationMs` / `errorMessage`：执行载体与失败信息；
- `inputContextJson` / `outputContextJson`：阶段前后黑板快照（Jackson 3
  序列化，截断至 20KB）——**整个 Agent 上下文可回放**。

`RunObservabilityController`（observability 包）暴露只读视图：`GET /api/trips/runs`
（运行列表摘要）与 `GET /api/trips/runs/{runId}`（按依赖波次分组，
`parallel=true` 标记并发波次，未知 runId 返回 404）。

**② 分布式追踪（Micrometer Observation → OTLP → Tempo）**。编排者为每次
运行开启 `orchestrator.planTrip` observation（属性 `template`/`mode`）；AOP
切面为每次 Agent 执行开启 `agent.execute` 子 span。并发波次通过
`ContextSnapshot.captureAll().wrapExecutor(executor)` 把线程本地观测上下文
传播进虚拟线程——并发阶段的 span 仍是运行 span 的孩子，Tempo 瀑布图中
父子关系完整。

**③ 过程指标（Micrometer → Prometheus）**。`OrchestratorMetrics` 集中登记
编排过程级指标目录（编排者在 run/stage 边界调用；Agent 级 Timer 由 AOP 切面
承载），全部经 `/actuator/prometheus` 暴露：

| 指标 | 类型 | 标签 | 语义 |
|---|---|---|---|
| `orchestrator.run.duration` | Timer | template/mode/outcome | 单次运行总耗时（开启直方图桶，Grafana 用 `histogram_quantile` 算 p95） |
| `orchestrator.runs.started` / `completed` / `failed` | Counter | template/mode | 运行吞吐与失败率 |
| `orchestrator.stage.completed` / `failed` | Counter | agent/mode | 阶段级吞吐与失败定位 |
| `agent.execution.duration` | Timer | agent/outcome | 单个 Agent 执行耗时（AOP 切面，见 4.8） |
| `orchestrator.observability.retained.runs` | Gauge | — | Actor 当前保留的运行时间线数 |

Grafana 仪表盘（`docker/grafana/dashboards/agent-observability.json`）预置
7 面板：Agent 平均耗时 / Agent p95 / Agent 失败 / 运行 p95（按 template/mode）/
运行吞吐（按 mode）/ 阶段失败 / 保留时间线数。

**④ Actuator 端点**（`management.endpoints.web.exposure.include` 暴露四端点）：

- `GET /actuator/health`：自定义 `orchestrator` 组件
  （`OrchestratorHealthIndicator`，实现 Boot 4 新包
  `org.springframework.boot.health.contributor.HealthIndicator`）报告 UP，
  详情含模板名/mode/阶段列表/注册 Agent/保留运行数——运维无需翻配置即可
  确认生效的 workflow（`management.endpoint.health.show-details=always`）；
- `GET /actuator/info`：`OrchestratorInfoContributor` 在 `workflow` 键下贡献
  模板名/mode/"阶段 -> agent(依赖)"/Agent 名单；
- `GET /actuator/metrics`：单指标查询（如 `?name=agent.execution.duration`）；
- `GET /actuator/prometheus`：文本格式全量指标，供 Prometheus 抓取。

**并发的边界（重要）**：`concurrent` 模式只让**同一波次内无依赖**的阶段并行；
内置默认模板是四级依赖链（每阶段依赖全部前驱），即便 `concurrent` 模式也是
逐波串行——要观察真实并行，需配置自定义模板（如 budget、itinerary 仅依赖
research），见 6.3。

## 5. REST API

```bash
curl -X POST http://localhost:8080/api/trips/plan \
  -H "Content-Type: application/json" \
  -d '{"destination":"Tokyo","numberOfDays":5,"budget":15000,"preferences":"food and culture"}'
```

响应为最终 `OrchestratorContext`（含 request 与四个响应）。

### 5.1 异常映射（GlobalExceptionHandler）

`@RestControllerAdvice` 将内部异常统一映射为结构化 JSON
（`{timestamp, status, error, message}`），前端无需猜测 500 白页：

| 异常 | HTTP | 语义 |
|---|---|---|
| `HttpMessageNotReadableException` / `IllegalArgumentException` | 400 | 请求体畸形 / 入参校验失败（Controller 的 `validate()`：destination 非空、numberOfDays>0、budget>0） |
| `tools.jackson.core.JacksonException` | 502 | 模型输出经净化后仍不可解析（含并发模式解包后） |
| `RestClientException` / `ResourceAccessException` | 503 | 模型后端（Ollama/OpenAI）不可达、拒绝调用或**超时**（`ModelHttpClientConfig` 给共享 `RestClient.Builder` 绑定 connect 5s / read 5min——无超时则一次卡死的模型调用会让调用方永远等不到返回；read 是空闲超时，非流式调用下即生成总时长上限） |
| `NoResourceFoundException` | 404 | 未知路由（避免落到兜底 500 造成误导） |
| 其余 `Exception` | 500 | 兜底，记录全栈日志 |

每个映射分支都打 `WARN` 日志（兜底 500 打 `ERROR`），保证异常在终端可见、
不静默丢失；调用方在任何路径下都会拿到结构化 JSON 响应。

### 5.2 运行时间线（RunObservabilityController）

```bash
curl http://localhost:8080/api/trips/runs            # 最近的运行列表（摘要）
curl http://localhost:8080/api/trips/runs/{runId}    # 完整时间线：波次 + 每阶段上下文 JSON
```

详情视图按依赖波次分组（`waves[].parallel` 标记并发波次），每个阶段包含
`inputContext`/`outputContext`（黑板快照 JSON）、`threadName`、`durationMs`、
`parallelPeers` 与失败信息；`finalContext` 为运行终态。进程内保留最近 100 次
运行，重启即清零（长期存储走 Tempo/Prometheus，见 4.9）。

### 5.3 前端控制台（`static/`）

应用自带一个**零依赖、零构建**的单页控制台（原生 HTML/CSS/JS，位于
`src/main/resources/static/`，启动即由 Boot 作为欢迎页提供，
**http://localhost:8080/**），把整个工程闭环在一个界面里：

| Tab | 内容 | 依赖端点 |
|---|---|---|
| 规划工作台 | 用户请求表单（工作流入口）→「本次请求」回显 → **实时执行进度**（轮询运行时间线，逐阶段显示完成/失败徽章与耗时；v1 另有 SSE 实时黑板卡，见 9.5）→ 四张结果卡片（行程结论/逐日行程/预算核算/景点调研），失败时渲染结构化错误卡（400/404/502/503/500） | `POST /api/trips/plan`、`GET /api/trips/runs[/{id}]` |
| 运行回放 | 运行列表（状态/耗时/阶段数）→ 点开单次运行：**用户最初提出的请求** + 依赖波次与每阶段输入/输出黑板快照（折叠 JSON）+ 终态上下文 | `GET /api/trips/runs[/{id}]` |
| 系统观测 | 过程指标卡片（运行发起/成功/失败、阶段计数、运行总时长、保留时间线）、Agent 维度表格（`agent.execution.duration` 按标签查询）、健康检查与工作流配置 JSON 面板、Grafana/Tempo/Prometheus 链接 | `/actuator/health`、`/actuator/info`、`/actuator/metrics/{name}?tag=…` |
| 控制平面（v1） | Agent 卡片编辑（system prompt/模型/温度/校准规则/MCP 引用）+ 工作流编辑器（mode/阶段/agent/依赖，增删行）+ MCP 服务面板 + 版本徽章与刷新 | `/api/v1/control/*` |

v2.1 起控制台重构为**左侧导航布局**（三组八项），替代 v0–v1 的顶部 tab：

| 导航组 | 页面 | 内容 |
|---|---|---|
| 场景 | 场景运行 | 顶栏场景选择与**激活**（覆盖热应用到控制平面，激活徽章）+ 原规划工作台全部能力 |
| 场景 | 场景推演 | dry-run 报告：校验徽章与问题清单、**依赖 DAG（SVG）**、波次展开（有效模型/温度/槽位/pending-safe）、黑板槽位流表 |
| 场景 | 场景配置 | 场景 CRUD 表格 + 编辑器（请求模板 / workflow 覆盖 JSON / agent 覆盖 JSON / MCP 依赖复选 / 产物类型 / 启停） |
| 元数据 | Agent 配置 / 工作流编排 / MCP 服务器 | 原「控制平面」tab 的三块内容原样承接（id 不变） |
| 可观测 | 运行回放 | 详情升级为**三视图切换**：时间线（含 `↻ retry` 第 N 次尝试链与工具明细）/ **DAG 依赖图**（模板精确依赖，回退波次推导，节点带状态填色与 ⚠retry 徽章）/ **泳道图**（agent 泳道 × 时间轴，⚠ 重试时刻定位、▲工具刻度，无完成时间戳时退化为顺序布局） |
| 可观测 | 系统观测 | 原「系统观测」tab 原样承接 |

实现要点（改错即复发）：

- **实时进度**：`POST /plan` 同步阻塞至工作流结束；执行期间前端每 2.5s 轮询
  运行列表找到 `RUNNING` 的运行并拉取详情——事件时间线由 `ObservabilityActor`
  边执行边折叠，因此无需 WebSocket 即可看到阶段逐个完成。
- **数据刷新**：`ObservabilityActor` 时间线与 Micrometer 指标都是即时快照，
  「运行回放」「系统观测」tab 在**每次切换时自动重新拉取**（页面加载时的
  旧数据无意义）。
- **Actuator 统计名**：`/actuator/metrics` 的 Timer 测量统计名是 `TOTAL_TIME`
  （不是 `TOTAL`），Gauge 是 `VALUE`；读错名字会渲染成 0/「—」。
- **静态资源缓存**：开发期改动 `static/` 后普通刷新可能命中启发式缓存——
  已配置 `spring.web.resources.cache.cachecontrol.no-cache=true` 强制再验证，
  且 HTML 引用带版本参数（`app.js?v=N`），改动后记得同步递增。

## 6. 配置（application.properties / application.yaml）

配置分四层：**后端（provider）选择**交给 Spring AI 标准属性，
**Agent 级模型选择**由 `orchestrator.agents.*` 承接，
**协作模板**由 `orchestrator.workflow.*` 承接，
**可观测性**由 `management.*` 承接。

### 6.1 后端选择（provider 级）

| 配置 | 说明 |
|---|---|
| `spring.ai.model.chat` | 选择 chat 后端：`ollama` / `openai`，仅该后端被自动配置 |
| `spring.ai.ollama.base-url` | Ollama 地址，默认 `http://localhost:11434` |
| `spring.ai.ollama.chat.options.model` | 后端默认模型（agent 未覆写时使用） |
| `spring.ai.openai.api-key` / `spring.ai.openai.chat.options.model` | OpenAI 后端凭据与默认模型 |

切换后端只需三步：`spring.ai.model.chat=openai` + 提供 api-key + 换默认模型名。
pom 中同时保留 ollama/openai 两个 starter；不使用的子模态一律
`spring.ai.model.<modality>=none` 显式禁用——否则 OpenAI starter 的
audio/image/moderation 自动配置会在无凭据时阻断启动。

### 6.2 Agent 级模型选择（`orchestrator.agents.*`）

```properties
orchestrator.agents.research.model=qwen:7b
orchestrator.agents.research.temperature=0.7
orchestrator.agents.budget.model=qwen:7b
orchestrator.agents.budget.temperature=0.3
...
```

- 每个 agent 可独立指定模型与温度；缺省（null）继承后端默认值。
- `AgentConfiguration` 将 spec 经 `ChatClient.defaultOptions`（provider 无关的
  通用 `ChatOptions`）挂到对应 ChatClient 上，agent 代码零改动。
- 已知 agent 名固定为 research/budget/itinerary/review，未知名字 fail-fast。
- 注意：同一时刻只有一个后端 ChatModel 生效；跨后端混排（如 research 走 OpenAI、
  budget 走 Ollama）需要手动构建双 ChatModel，属扩展方向（见第 8 节）。

### 6.3 协作模板（`orchestrator.workflow.*`）

```properties
# 执行模式：sequential（默认）或 concurrent
orchestrator.workflow.mode=concurrent

# 自定义模板（可选；未配置时使用内置 research -> budget -> itinerary -> review）
# 列表索引即声明序（串行执行序 + 依赖校验的参照）
orchestrator.workflow.stages[0].name=research
orchestrator.workflow.stages[1].name=budget
orchestrator.workflow.stages[1].depends-on=research
orchestrator.workflow.stages[2].name=itinerary
orchestrator.workflow.stages[2].depends-on=research,budget
orchestrator.workflow.stages[3].name=review
orchestrator.workflow.stages[3].depends-on=research,budget,itinerary
```

- 阶段的 `agent` 缺省等于阶段名；也可显式指定其他 Agent（如
  `orchestrator.workflow.stages[1].agent=research`）。
- 并发示例：把 budget 改为 `depends-on=`（空）即可与 research 同波并行，
  两者写不同黑板槽位，`mergeFrom` 自动合并。
- 未知 Agent 引用 / 依赖未在前序声明 / 依赖环 → 启动即失败（fail-fast）。

### 6.4 可观测性（`management.*`）

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
management.opentelemetry.tracing.sampler=always-on
# gRPC transport: Boot 4 的 OTLP/HTTP 导出器对 Tempo receiver 反复收到 404
#（首批收下、后续整批被拒），span 永远到不了 Tempo；gRPC(4317) 稳定导出。
management.opentelemetry.tracing.export.otlp.transport=grpc
management.opentelemetry.tracing.export.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}
# Boot 4 还会自动开启 OTLP 指标推送（每分钟 POST /v1/metrics），Tempo 不收
#（404 刷日志）；指标已走 Prometheus，显式关闭。
management.otlp.metrics.export.enabled=false
# 直方图桶：Grafana 的 p95（histogram_quantile）依赖 *_seconds_bucket 序列
management.metrics.distribution.percentiles-histogram.agent.execution.duration=true
management.metrics.distribution.percentiles-histogram.orchestrator.run.duration=true
```

- Boot 4 的 OTLP 追踪自动配置位于 `spring-boot-starter-opentelemetry`
  （聚合 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` +
  `spring-boot-micrometer-tracing-opentelemetry` 模块；Boot 4 不再有旧
  `management.otlp.tracing.*` 属性路径）。
- 采样器取 `always-on`（枚举 `OpenTelemetryTracingProperties$Sampler`，默认
  parent-based-trace-id-ratio 对 demo 场景过保守）；采样概率不再是
  `management.tracing.sampling.probability`。
- 可视化栈一键启动：`docker compose -f docker/docker-compose.yml up`——
  Tempo :4317 收 OTLP/gRPC（HTTP :4318 仅部分端点可用，勿依赖）、:3200 查询；
  Prometheus 抓 `host.docker.internal:8080/actuator/prometheus`；Grafana :3000
  匿名登录，预配 Tempo/Prometheus 双数据源与
  "Orchestrator Demo / Agent Orchestration" 仪表盘。

## 7. 测试体系

共 214 个用例，全部离线运行（不依赖本地 Ollama），`mvn test` 数秒完成。
下表为 v0 核心用例；v1–v3 的增量见各版本章节的「测试增量」小节。

| 测试类 | 层次 | 验证点 |
|---|---|---|
| `OrchestratorContextTest`（6 例） | model | start 仅含请求；with\* 逐步追加；原状态不可变；mergeFrom 补齐 null 槽位/保留已有贡献；end() 终态 |
| `AgentModelPropertiesTest`（3 例） | config | 逐 agent 模型/温度绑定；未配置项继承后端默认；未知 agent 名 fail-fast |
| `WorkflowTemplateTest`（7 例） | workflow | 内置模板校验通过且分解为 4 个单阶段波次；独立阶段同波；未知 agent/依赖后置/依赖环/空模板 fail-fast；**内置 parallel 模板**：budget/itinerary 同波、review 汇聚的三波分解 |
| `WorkflowPropertiesTest`（5 例） | workflow | 缺省回落内置模板（SEQUENTIAL）；mode + 自定义 stages 列表绑定（agent 缺省=阶段名、可覆写）；内置 parallel 模板可选；custom stages 优先于内置模板开关；未知内置模板 fail-fast |
| `TripPlanningOrchestratorTest`（6 例） | orchestrator | 串行：模板定义顺序（Bean 乱序注入仍按模板执行）+ 上下文逐级累积 + 事件时间线（mode/waveIndex/peers/上下文 JSON）；并发：虚拟线程并行（latch 会合验证真并行）+ 快照一致 + 贡献合并 + 并发事件（wave 0 两阶段互为 peers）；**并行模板**：budget/itinerary 同快照并行（互为 peers）、review 汇聚两者、timeline 模板名 parallel-trip-planning；阶段失败 → STAGE_FAILED + RUN_FAILED；未知 agent 启动 fail-fast；**并发波次失败**：`shutdownNow` 中断 straggler，错误即刻返回（<5s，实测 47ms）；**过程指标**：runs.started/completed/failed（template/mode 标签）、stage.completed/failed（agent/mode 标签）、run.duration（success/failure Timer）、retained-runs Gauge |
| `ObservabilityActorTest`（4 例） | observability | 事件折叠为 RunTimeline（RUN_STARTED→stage→RUN_COMPLETED）；失败运行标记 FAILED 且保留错误信息；未知运行的事件被丢弃；超出 100 个运行按插入序淘汰最旧 |
| `OrchestratorHealthIndicatorTest` | observability | /actuator/health 的 orchestrator 组件：UP + template/mode/stages/agents/retainedRuns 详情 |
| `OrchestratorInfoContributorTest` | observability | /actuator/info 贡献 workflow 键：模板名/mode/"阶段 -> agent(依赖)"/Agent 名单 |
| `AgentExecutionAspectTest`（3 例） | aop | AspectJProxyFactory 织入：成功透传并记录耗时（INFO）；失败记录并原样重抛（WARN）+ failure Timer；成功执行产出 `agent.execute` Observation（tag agent）+ success Timer |
| `ResearchAgentTest` | agent | prompt 含全部需求字段；JSON → record；写入正确槽位 |
| `BudgetAgentTest` | agent | 消费上游调研结果；成本解析与 isBudgetMet；prompt 含调研内容 |
| `ItineraryAgentTest`（2 例） | agent | 消费调研+预算；DayPlan 列表解析；prompt 含双上游；**并行模板**：预算未就绪时 prompt 注入 "not on the blackboard yet / stay conservative" 提示 |
| `ReviewAgentTest` | agent | 消费全部三类上游；最终结论；prompt 含行程 |
| `AbstractContextAgentTest`（3 例） | agent | 首次解析失败重试成功；重试耗尽后抛 `JacksonException`；首次成功不重试 |
| `TripPlanningControllerTest` | trip | POST JSON → 200；入参绑定；全量上下文序列化 |
| `RunObservabilityControllerTest`（2 例） | observability | GET /api/trips/runs[/id]：时间线按波次分组 + parallel 标记 + 上下文 JSON；未知 runId → 404 |
| `ResponseSanitizerAdvisorTest`（10 例） | agent | 纯 JSON 透传；剥 Markdown 围栏；prose 中提取 JSON；纯文本不动；null/blank 容错；**回归**：围栏输出 → `entity()` 成功；裸数组按 Schema 单数组字段包装；多属性 Schema 与无 Schema 时不动；真实链路裸数组 → `ItineraryResponse` 解析成功 |
| `GlobalExceptionHandlerTest`（6 例） | api | 校验失败 → 400；畸形 body → 400；`JacksonException` → 502；`RestClientException`/`ResourceAccessException` → 503；未知路由 → 404 |
| `AgentConfigRegistryTest`（6 例） | control | 播种默认态并持久化；重启重载并重新热应用模板；updateAgent 校验（未知 agent/空 prompt/悬空 MCP 引用）+ 版本单调 + 持久化跨"重启"保留；updateWorkflow 校验失败不 bump 版本、成功热切换；MCP 记录唯一名/传输/端点校验 |
| `ControlPlaneControllerTest`（7 例） | control | REST 契约：GET 播种态；PUT agent 热生效 + 版本 +1；未知 agent 404 / 非法更新 400；PUT workflow 热切换 + 非法模板 400；PUT mcp 全量替换；模型列表回落配置（离线，不查 Ollama） |
| `CalibrationAdvisorTest`（4 例） | agent | 规则在净化后的输出上按声明序套用（围栏 JSON → 规则生效）；无规则原样透传；坏正则跳过且顺序保持；null 容错 |
| `ObservabilitySsePublisherTest`（2 例） | observability | 无订阅者时发布为 no-op；注册缓冲 connected 事件、完成时解绑 emitter |
| `OrchestratorDemoApplicationTests` | 集成 | Spring 上下文装配（Bean/Qualifier/自动配置/AOP 代理/观测组件） |

### 7.1 测试支撑：ChatClientStubs 与 Await

位于 `src/test/java/com/oli/orchestrator_demo/`：

- `ChatClientStubs.modelReturning(json)`：mock `ChatModel` 固定返回 JSON，
  真实的 `ChatClient` + `entity()` 转换链路完整参与测试
  （含 `getOptions()` 合并、格式注入、record 反序列化）。
- `ChatClientStubs.userTextOf(model)`：捕获发往模型的用户消息，用于断言 prompt
  内容（占位符替换结果 + 结构化输出格式指令）。
- `ChatClientStubs.actor()`：组装带真实 SSE publisher（无人订阅，no-op）的
  `ObservabilityActor`，供编排者/控制器测试注入。
- `Await.until(condition)`：轮询断言异步折叠的状态（Actor 消费者是独立线程，
  `tell` 之后事件稍后才可见）。

### 7.2 运行

```bash
./mvnw test                                     # 全量测试（214 例，离线）
./mvnw spring-boot:run                          # 启动（需本地 Ollama + qwen:7b）
docker compose -f docker/docker-compose.yml up  # 可选：Grafana + Tempo + Prometheus
```

观测验证四步：

1. 发一次 `POST /api/trips/plan`；
2. `GET /api/trips/runs/{runId}` 回放整个 Agent 上下文（串行/并发/波次一目了然）；
3. `curl localhost:8080/actuator/health`（orchestrator 组件详情）与
   `curl localhost:8080/actuator/info`（生效的 workflow 配置）；
4. Grafana（http://localhost:3000）→ Explore → Tempo 查 `agent.execute` span
   瀑布图（并发波次的兄弟 span 起止重叠）；Dashboards → "Agent Orchestration"
   看耗时/失败率/运行吞吐曲线。

### 7.3 架构守护测试（fitness functions）

`ArchitectureConventionTest`（`src/test/java/.../architecture/`）以**零依赖源码扫描**
（正则抽取 import 建图，无 ArchUnit、完全离线）固化 §15.3 的依赖规则，规则漂移即测试失败：

| 规则 | 断言 |
|---|---|
| R1 | `kernel/shared/` 零业务模块 import（业务 SPI 一律在 `kernel/contract/`） |
| R2 | `kernel/contract/` 只 import 自身（纯净数据面 + SPI 包；`spi/model/config/port` 子包同属单模块，互引视为包内） |
| R3 | 模块依赖图（zone 限定名，如 core.agent）无环（**零豁免**，反转记录见 §15.6.2） |
| R4 | `infra/` 适配器不被跨模块引用（端口与适配器本地性） |
| R5 | `web/` 表现层不触碰任何 `infra/` 适配器 |

豁免（waiver）必须成对出现在 R3 测试注释与 §15.6.2 反转计划中——禁止无计划的静默豁免。

## 8. 已知边界与扩展方向

- **跨后端混排**：当前同一时刻只有一个 chat 后端生效（`spring.ai.model.chat` 选中者）。
  若需 per-agent 跨后端（如 research 走 OpenAI、budget 走 Ollama），需手动构建双
  ChatModel：注意 Spring AI 2.0 的 Ollama 侧 `OllamaChatModel.builder().ollamaApi(...)
  .options(OllamaChatOptions)`，OpenAI 侧已迁移至官方 `com.openai` SDK
  （`OpenAiSetup.setupSyncClient`）。
- **并发写入同一槽位**：模板不阻止两个并行阶段写同一黑板槽位；
  合并采用"声明序先应用者胜出"，语义确定但不告警，可在 `validate` 中
  增加同波槽位冲突检测（需引入槽位元数据）。
- **失败处理**：已具备三层兼容（净化 → 重试 → 502 映射，见 4.5/4.6/5.1）；
  进一步可引入 per-agent 可配置重试次数或降级策略。
- **流程终止**：可基于 `ReviewResponse.isValid` 实现条件重排（如不合格回炉 Itinerary）。
- **可观测性**：已落地四路信号（见 4.9）——AOP span/Timer 指标、Actor 事件
  时间线（REST 回放上下文）、过程指标目录与 Actuator health/info 端点
  （OrchestratorMetrics / OrchestratorHealthIndicator / OrchestratorInfoContributor）、
  Grafana/Tempo/Prometheus 可视化栈（`docker/`）。
  可扩展：完整 `RunTimeline` 的长期持久化（v2.2 已落库阶段级 LM 指标与运行
  日志，见 12.1/12.3）、`orchestrator.planTrip` 内增加波次级子 span。

## 9. v1：Docker 化、并行示例与控制平面（Control Plane）

v1 与 v0（旅行规划控制台）解耦演进：v0 的 REST API 与前端三个 tab 全部保留，
v1 增量引入四块能力——容器化部署、并行模式官方示例、控制平面与实时事件流。
版本边界一句话：**v0 = 只读观测**（时间线回放 + 指标），**v1 = 可写控制**
（运行时改配置）。

### 9.1 Docker 化（多阶段构建）

`Dockerfile` 将 Maven 产物（jar）封装为容器服务，两阶段构建：

- **build 阶段**：`eclipse-temurin:25-jdk`，`RUN --mount=type=cache,target=/root/.m2
  ./mvnw -q -DskipTests package`——BuildKit cache mount 复用本地仓库，
  依赖不随源码变更重下；
- **runtime 阶段**：`eclipse-temurin:25-jre` + 非 root 用户（uid 1000），
  `ENTRYPOINT ["java", "-jar", "app.jar"]`。

镜像内默认环境：`SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434`
（容器内访问宿主 Ollama）、`OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317`
（接入可视化栈）、`ORCHESTRATOR_CONTROL_CONFIG_FILE=/app/config/control-plane.json`
（控制平面状态落卷）。`.dockerignore` 排除 `target/ config/ docker/ .git/` 等
——尤其 `config/`，宿主的运行时状态不得烤进镜像。

一键启动：`docker compose -f docker/docker-compose.yml up`——`app` 服务与
tempo/prometheus/grafana 同栈编排（`extra_hosts: host.docker.internal:host-gateway`
使容器可达宿主 Ollama；`orchestrator-config` 卷挂 `/app/config`，控制平面状态
跨容器重建保留），控制台 http://localhost:8080/ 。

### 9.2 并行模式内置示例（parallel 模板）

v0 的内置模板是四级依赖链，`concurrent` 模式下也无波次可并行（见 4.9 边界）。
v1 提供官方并行示例，两条配置即启用：

```properties
orchestrator.workflow.template=parallel
orchestrator.workflow.mode=concurrent
```

模板拓扑（`WorkflowTemplate.parallelTripPlanningStages()`，模板名
`parallel-trip-planning`）与执行时序：

```
wave 0:  research
wave 1:  budget    ┐ 同波并行，各自从同一黑板快照出发
         itinerary ┘
wave 2:  review   （依赖前三者，汇聚调和）
```

**并行下的信息缺失兼容**：budget 与 itinerary 同波并行、共享同一快照，
itinerary 看不到 budget 的产出。为避免模型编造，`ItineraryAgent` 在
`budgetResponse == null` 时向 prompt 注入固定提示——预算正在并行运行中、
尚不在黑板上，仅依据用户声明的预算保守规划；`ReviewAgent` 对两者缺一的
情况同理守护。review 阶段（wave 2）拿到全量贡献后负责合并调和。

这是"并行扇出 + 汇聚点收拢"的标准写法：**依赖不完整的 Agent 必须 null-safe，
并把不确定性显式写进 prompt**——数据流的不完整不能留给模型脑补。

### 9.3 控制平面（control 包）

控制平面把 v0 中"编译期固化"的 Agent 人格与工作流拓扑升级为**运行时可写**。

| 数据模型（record） | 内容 | 校验 |
|---|---|---|
| `AgentRuntimeConfig` | systemPrompt / model / temperature / calibrationRules / mcpRefs | agent 存在、prompt 非空、mcpRefs ⊆ 已录入 MCP 服务 |
| `CalibrationRule` | 正则 pattern → replacement（+ 描述） | 坏正则运行期跳过并告警（配置错误不砸运行） |
| `McpServerConfig` | name / transport(SSE\|STDIO) / endpoint / enabled / description | 名字唯一、transport 已知、endpoint 非空 |
| `WorkflowControlState` | mode + 阶段列表（name/agent/dependsOn） | 复用 `WorkflowTemplate.validate`（未知 agent/依赖后置/环） |
| `ControlPlaneState` | version + updatedAt + agents + workflow + mcpServers | version 每次成功更新 +1，单调递增 |

**架构与热生效机制**：

- `AgentConfigRegistry` 是文件持久化的状态仓库（默认 `config/control-plane.json`，
  `orchestrator.control.config-file` 可改）：启动时文件存在则加载 + 校验 +
  热应用，否则从 `AgentDefaults`（内置人格）与 `orchestrator.agents.*` /
  `orchestrator.workflow.*` 播种；此后**状态文件是唯一事实源**。所有更新
  `synchronized`（单写者），持久化为原子写（temp 文件 + `ATOMIC_MOVE`），
  崩溃不会留下半截 JSON。
- **Agent 配置热生效**：agents 不再持有编译期 ChatClient Bean，构造时拿到
  `ChatModel` + `AgentRuntimeStore`（registry 实现），**每次调用**从 store
  读 system prompt / model / temperature 动态构建 ChatClient——PUT 一保存，
  下一次调用即生效，无需重启。
- **工作流拓扑热生效**：`WorkflowTemplateHolder` 以 volatile 引用持有当前
  模板（启动时从 properties 初始化）；`updateWorkflow` 校验通过后
  `holder.replace(...)` 原子换引用，orchestrator 每次 `planTrip` 读一次——
  下一次请求即用新拓扑，进行中的运行不受影响。
- **花名册来源**：registry 校验模板/agent 引用使用 `AgentDefaults.KNOWN_AGENTS`
  （与播种同源），**不**注入 orchestrator——agents → store(registry) →
  orchestrator → agents 是构造器循环依赖，静态花名册是解环后的自然结论
  （实测 BeanCurrentlyInCreationException 的教训）。

**REST API**（`/api/v1/control`，前端控制平面 tab 全部经此）：

| 方法与路径 | 作用 |
|---|---|
| `GET /state` | 全量状态快照（version/agents/workflow/mcpServers） |
| `GET /agents` · `GET /agents/{name}` | Agent 配置读；未知名字 404 |
| `PUT /agents/{name}` | 热更新单个 Agent（prompt/模型/温度/校准规则/MCP 引用） |
| `GET /workflow` · `PUT /workflow` | 读/热切换工作流模板（非法模板 400，原模板不受影响） |
| `GET /mcp/servers` · `PUT /mcp/servers` | 读/全量替换 MCP 服务记录 |
| `GET /models` | 可选模型列表：优先实时查询后端（Ollama `/api/tags`），失败回落 `orchestrator.agents.*` 已引用模型（离线可用） |

### 9.4 响应校准与 Advisor 链顺序

v1 在净化 advisor 之外新增 `CalibrationAdvisor`（实现 `CallAdvisor`），
把用户在控制平面录入的校准规则（正则 → 替换）套用到**模型输出**上——
规范化/格式化/校准（去尾随空白、统一地名写法、替换措辞等）。

Advisor 链顺序由 order 决定（低 = 外层 = 响应路径上更晚经过）：

```
请求 → [CalibrationAdvisor (-200)] → [ResponseSanitizerAdvisor (-100)] → [ChatModelCallAdvisor 终端] → 模型
响应 ← 校准（规则套在"干净的 JSON 文本"上） ← 净化（剥围栏/提 JSON/修形状） ←
```

校准必须排在净化**之后**（响应路径上）：规则面对的应是剥掉围栏、提出 JSON
之后的干净文本，否则正则可能匹配到围栏标记。坏正则被 try/catch 跳过并
`WARN`——**配置错误绝不能 fail 一次运行**。解析仍失败时三层防线兜底
（净化 → 重试 → 502，见 4.5/4.6）。

**MCP v1 边界**：控制平面仅承载 MCP 的**配置录入/校验/Agent 映射**
（mcpRefs），真正的 MCP 客户端接线（`spring-ai-starter-mcp-client`）与
工具调用明细展示列入 v1.1 路线图（前端 hint 亦有说明）——先把配置面做对，
再接执行面。

### 9.5 SSE 实时事件流

`GET /api/v1/events/stream`（`text/event-stream`）把 Actor 折叠的事件实时
推给前端：

- `ObservabilityActor.apply()`（单消费者线程）在折叠事件后调用
  `ObservabilitySsePublisher.publish(event)`——发布在 actor 线程只做序列化
  与任务提交，每个订阅者由**独立虚拟线程**发送，慢客户端不阻塞事件管线；
- 事件名即前端监听名：`connected`（注册即发）/ `run_started` /
  `stage_completed` / `stage_failed` / `run_completed` / `run_failed`；
- 订阅者生命周期：`SseEmitter(0L)`（无超时），completion/timeout/error 自动
  摘除；无订阅者时 publish 是 no-op。

前端规划工作台据此新增**实时黑板卡**：执行期间逐阶段显示输入/输出与完整
Context JSON（v0 的 2.5s 轮询保留作为兜底），运行回放与控制平面仍走 REST。

### 9.6 热更新边界（hotfix 路线）

v1 已落地的热更新是**配置级**的（进程内热生效，零重启）：

| 可热更 | 机制 | 生效时机 |
|---|---|---|
| Agent system prompt / model / temperature | 每次调用从 registry 读取 | 下一次调用 |
| 校准规则 / MCP 引用 | 同上（Advisor 每次调用读取） | 下一次调用 |
| 工作流拓扑（mode/stages/dependsOn） | `WorkflowTemplateHolder` volatile 换引用 | 下一次请求 |
| MCP 服务记录 | registry 状态（v1 不接执行面） | 接线后生效 |

**不在 v1 范围内的 JVM 字节码级热替换（hotfix 场景）与推荐路径**：

- **标准 HotSwap（JVM 内建）**：仅支持**方法体内**变更；加/删字段、改方法
  签名等类结构变更直接 `UnsupportedOperationException`——适合改一行日志的微修；
- **DCEVM / JetBrains Runtime（JBR）**：增强 HotSwap 支持结构变更（加方法/
  字段/类）。开发期可用 JBR 运行时 + DCEVM（或带 DCEVM 补丁的 JDK）验证
  hotfix；生产仍建议滚动重建镜像——容器化后重建即部署，成本已很低；
- **spring-boot-devtools**：类路径变更自动重启上下文（秒级，非真热替换）。
  控制平面状态文件（卷持久化）保证重启后配置不丢——与 v1 的"文件事实源"
  设计天然契合，是最稳的"伪热更"；
- **JRebel 等商业热部署**：开发期体验最好，demo 不引入。

结论：**配置热更走控制平面（已实现）；代码热修走"容器重建 + 状态卷"**
（镜像重建分钟级、状态零丢失）；开发期需要结构级热替换时优先 devtools
重启，其次 JBR + DCEVM。生产环境的热修复以不可变镜像 + 滚动替换为准。

### 9.7 版本解耦策略

- v0 的 API（`/api/trips/*`、`/actuator/*`）与前端三个 tab 原样保留；v1 新增
  `/api/v1/control/*` 与 `/api/v1/events/stream` 独立命名空间——**前向兼容**，
  旧客户端不受影响；
- 控制平面状态文件的 `version` 字段是"配置代数"而非 API 版本；后续大版本
  若引入不兼容 schema，可按文件内容探测迁移；
- Agent 从"编译期 Bean 人格"迁移到"运行时 store 人格"是**唯一**的 v0→v1
  破坏性改动（`AgentConfiguration` 不再构建 ChatClient Bean）——对外 API
  无感，仅影响直接 new Agent 的测试（已同步）。

## 10. v2.0：控制平面持久化基座（数据库 + 缓存 + 加密）

### 10.1 存储分层

控制平面的"写入权"与"存放地"解耦：`AgentConfigRegistry` 仍是唯一写者
（校验 → 热生效 → bump version），持久化下沉到 `ControlPlaneStore` 接口：

```
AgentConfigRegistry（单写者，内存快照 = 运行时读侧事实源）
        │ load() / save() / currentVersion()
        ▼
ControlPlaneStore
  ├─ FileControlPlaneStore   JSON 文件（默认；seed 源 + 回退后端，原子写）
  └─ CachedControlPlaneStore Caffeine 缓存（仅 db 模式）
       └─ DbControlPlaneStore  PostgreSQL（生产）/ H2（测试）
            └─ ControlPlaneCrypto  AES-GCM 字段级加密（env 值）
```

- **选择**：`orchestrator.control.store=file|db`（默认 file，零迁移成本）；
  `ControlPlaneStoreConfig` 按配置装配，db 模式启动时建表 + 一次性导入。
- **读路径**：热路径（每次模型调用、控制台轮询）读 registry 内存快照，
  不触库；缓存服务于**二级读**（后续场景匹配、多实例部署），用廉价
  `currentVersion()`（单行查询）做 version 戳失效 + 写透 + TTL 兜底。
- **一致性**：db 保存为单事务（小表 delete + 全量 insert），借 PostgreSQL
  WAL 获得崩溃安全——控制平面更新要么整体生效要么不生效。
- **热加载**：沿用 version-diff reconcile（MCP 连接重建、模板换引用），
  **不引入**字节码热替换；DB 带来的只是事实源的更换，热生效机制不变。

### 10.2 表结构（cp_*，PostgreSQL/H2 可移植）

| 表 | 说明 |
|---|---|
| `cp_meta` | 单行版本戳（version, updated_at）——缓存失效探针 |
| `cp_agent` | agent 运行时配置（prompt/model/temperature/calibration_rules/mcp_refs，display_order 保序） |
| `cp_workflow` / `cp_workflow_stage` | 模板 mode + 有序 stage（depends_on） |
| `cp_mcp_server` | MCP 记录（transport/endpoint/command/args/**env 加密**/enabled） |
| `cp_agent_descriptor` | 槽位契约（output/input/pending_safe slots + 默认模型信息） |

嵌套列表/映射以 JSON 文本列存储（跨库可移植）；实体本身规范化建表，
为 v2.1 场景元模型（Scenario = workflow + agents + MCP 映射 + 产物模板）
保留直接 SQL 查询能力。Schema 由应用幂等创建
（`CREATE TABLE IF NOT EXISTS`），后续可交 Flyway 演进。

### 10.3 加密与脱敏闭环

- **字段级 AES-256-GCM**：`cp_mcp_server.env` 的每个非空值在入库前加密为
  `enc:v1:<iv>:<ciphertext>`（随机 IV、128 位认证标签），读取时解密；
  前缀版本化，旧明文值兼容加载，未来可平滑升 `v2`。
- **密钥**：`ORCHESTRATOR_CRYPTO_KEY`（任意口令，SHA-256 派生 AES-256）；
  未设置时为明文直存（file 模式默认），db 模式启动大声 WARN。
- **脱敏闭环**：`GET /api/v1/control/state` 与 `/mcp/servers` 的 env 值一律
  `******`；PUT 回传 `******` 由 registry 依名字从既有状态**还原**真实值
  （`mergeMaskedEnv`）——控制台整表回写不会破坏密钥；连接层始终拿到明文。
- 日志与时间线不输出 env 值（MCP 注册表仅将其传给子进程）。

### 10.4 迁移与运行

- db 模式首次启动：DB 为空且 JSON 文件存在 → 整体导入（版本号不变）；
  DB 为空且无文件 → 从 `orchestrator.agents.*` / `orchestrator.workflow.*`
  种子直落库；此后 JSON 文件仅作回退。
- Docker compose（`docker/`）已切 db 模式：`postgres:16-alpine` +
  `pg_isready` 健康检查 + `postgres-data` 卷；app 经
  `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 与 `ORCHESTRATOR_CONTROL_STORE=db`
  接入，`orchestrator-config` 卷保留（迁移源/回退后端）。
- 测试：H2 内存库跑同一份可移植 schema（`DbControlPlaneStoreTest` 六例：
  往返、保序、密文落库断言、无密钥明文回退、一次性导入、缓存失效）。

## 11. v2.1：场景元模型、重试事件链与控制台导航

v2.0 回答"配置存在哪"，v2.1 回答"如何把一整套编排逻辑作为资产切换"。
三块增量：**场景平面**（scenario 包）、**解析重试事件链**（observability）与
**控制台 v2.1**（左侧导航 + DAG/泳道可视化，见 5.3）。

### 11.1 场景平面（scenario 包）

场景（Scenario）= 一套可持久化的编排逻辑组，**镜像控制平面的既有模式**
（单写者 registry + store 接缝 + version 单调递增），但不复用
`ControlPlaneState`——避免破坏控制平面"单一事实源"的不变量：

| 组成 | 说明 |
|---|---|
| `requestTemplate` | 目的地/天数/预算/偏好——场景运行页可作请求预填与后续一键运行的模板 |
| `workflowOverride` | 可空 `WorkflowControlState`（mode + stages）——null = 继承控制平面当前模板 |
| `agentOverrides` | agent 名 → `{systemPrompt?, model?, temperature?, calibrationRules?, mcpRefs?}`，null 字段保留现值（`applyTo` null-keep 合并） |
| `mcpServerRefs` | 场景要求的 MCP 服务器（未在控制平面录入则 dry-run 判 invalid） |
| `artifactType` | 产物类型：HTML / SPRING_BOOT_PROJECT / JAR / AUDIO（jar/工程包/音频的实际生成列入 v2.2） |
| `active` | 启停标记 |

- **存储接缝**：`ScenarioStore`（load/save/name）→ `FileScenarioStore`
  （`config/scenarios.json`，temp + `ATOMIC_MOVE`）或 `DbScenarioStore`
  （`cp_scenario_meta` 单行版本戳 + `cp_scenario` 记录表，@Transactional
  delete+insert 单事务写）。跟随 `orchestrator.control.store` 同一开关装配
  （`ScenarioStoreConfig`），`orchestrator.scenarios.file` 可改文件路径。
  `workflowOverride` 恒序列化（Jackson 写字面 `"null"`），避免 SQL NULL
  反序列化崩溃——H2/PostgreSQL 同一份可移植 schema。
- **校验**：id slug 格式与唯一性、名称非空、requestTemplate.days ≥ 1、
  agentOverrides 键须是已知 agent、mcpServerRefs 须已录入、workflowOverride
  构造 `WorkflowTemplate` 试校验（未知 agent / 依赖后置 / 环 → 400）。
- **activate = 动态实例化**：先 validateReferences，再
  `updateWorkflow`（模板热切换）+ 逐 agent `updateAgent(applyTo)`（null 字段
  保留现值）——场景覆盖落进控制平面后，热生效机制与 v1/v2.0 完全一致
  （每次调用读、下一次请求生效）。
- **dry-run = 零 token 推演**：`DryRunEngine` 解析生效模板
  （override ?? 控制平面）→ `WorkflowTemplate.validate` 捕 `IllegalStateException`
  入 issues（不抛）→ `waves()` 波次展开（校验失败仍展开，报告"能走多远"）→
  每阶段有效模型/温度（override.applyTo(config)）/ MCP / 输入输出槽位 /
  pending-safe / warnings → 槽位流（每个黑板槽位的产出方与消费方）。
  **不调用任何模型、不改变任何状态**（测试断言 version 不变）。
- 内置种子场景 `kyoto-classic`（京都经典四阶段）首启自动落库。

### 11.2 解析重试事件链（STAGE_RETRIED）

v1 的解析重试（4.6）对可观测性是黑盒：时间线只见最终成败。v2.1 让每次
尝试可见：

- `AbstractContextAgent.respondWithRetry` 解析失败进入重试前，发布
  `STAGE_RETRIED` 事件（attempt 序号 + 原始错误消息）；
- 归因：agent 内部拿不到 stage/run 上下文 → 复用 MCP 工具插桩同款
  `RunContext`（ThreadLocal，orchestrator 执行阶段前设置）读
  runId/stage/agent/waveIndex；**工作流之外（单测直调）归因为 null，事件
  丢弃**——不污染时间线；
- 传输：agent 无法注入 `ObservabilityActor` bean → 新建轻量静态
  `ObservabilityEventBus`（CopyOnWriteArrayList 订阅者），Actor 构造时
  `subscribe(this::tell)`，事件走既有 fold 管线（时间线 / SSE
  `stage_retried` / REST 回放）零改动接入；
- 消费：回放详情每阶段新增 `retries[]`（attempt/errorMessage/timestamp），
  时间线与泳道图渲染 `↻ retry` / `⚠` 标记。

### 11.3 本地 file 模式启动与 DataSource 排除

JDBC starter（store=db 所需）常驻 classpath，而本地 file 模式没有
`spring.datasource.url`——`DataSourceAutoConfiguration` 会直接炸掉启动。
处置：`spring.autoconfigure.exclude` 默认排除该自动配置，
`ORCHESTRATOR_SPRING_EXCLUDE=`（空）由 docker compose（db 模式）置空恢复。

### 11.4 测试增量

`ScenarioRegistryTest`（seed/重启重载/CRUD 版本递增/全套引用校验/activate
热应用/dry-run 不改状态）、`DbScenarioStoreTest`（H2 往返/一次性导入/空库）、
`DryRunEngineTest`（默认模板 4 波 6 边 + 槽位流/CONCURRENT 覆盖并行/校验
问题报告/槽位缺口/覆盖生效/未配置 MCP 判 invalid）、`AbstractContextAgentTest`
追加重试事件归因与工作流外丢弃两例——合计 **140 例全绿**。

## 12. v2.2：观测落库、元信息补录、团队日志、发布管道与导航-画布联动

v2.1 回答"编排逻辑如何作为资产切换"，v2.2 补齐运营闭环的另一半：**观测数据
落库**（每场景每阶段 LM 耗时/token 可查）、**控制层面元信息补录**（运行时配置/
发布门户/按场景发布配置）、**运行时团队日志**（不限于业务日志的全量审计与
检索）、**发布管道**（可移植包 + 注册表）与**左侧导航-画布联动**（生命周期
闭合链）。五块共享同一模式：新存储平面一律镜像既有
"单写者 registry + Store 接缝 + 跟随 `orchestrator.control.store` 装配"，
不另设开关。

### 12.1 观测落库与 LM 指标（obs_run_metrics）

- **用量采集**：`UsageCaptureAdvisor`（agent 包，`CallAdvisor`）从原始
  `ChatResponse` metadata 读取 prompt/completion/total tokens 与服务模型名，
  写入 `LmUsageCollector` ThreadLocal，编排者在阶段边界汇成
  `AgentObservabilityEvent.LmUsage` 随事件进 Actor。注册序取
  `LOWEST_PRECEDENCE - 1`（紧贴终端 `ChatModelCallAdvisor` 之前）——净化/校准
  advisor 会重建响应并丢弃 metadata，**采集必须离模型最近**；全链路 null-safe，
  后端不报用量或工作流之外的调用不贡献数据。
- **WAL 顺序落库**：`ObservabilityActor.apply()` 对每个事件先 `recordLog`
  （`team_log` 追加）再 `recordMetrics`（`obs_run_metrics` 追加），二者先于
  内存折叠与 SSE 推送；任一 store 失败降级 WARN，不破坏时间线。
- **表**：`obs_run_metrics`（run_id/scenario_id/stage/agent/model/wave_index/
  attempt/outcome/duration_ms/prompt·completion·total_tokens/created_at，
  created_at 与 scenario_id 双索引）。装配 `RunMetricsConfig` 同样跟随
  `orchestrator.control.store`：db 模式为 `DbRunMetricsStore`（PostgreSQL/H2
  同一可移植 schema），file 模式为 `NoopRunMetricsStore`（进程内时间线照常，
  落库关闭）。
- **查询 API**：`GET /api/v1/scenarios/{id}/metrics`（单场景聚合）与
  `GET /api/v1/metrics/lm`（全场景，`since`/`scenarioId` 过滤）——按
  scenario/stage/agent/model 汇总调用数、总耗时与 token 消耗，即
  "每个业务场景对应模型 LM 的耗时与 token 消耗"历史。
- 回放侧 `RunTimeline`/`RunSummary`/`RunDetails` 均携带 `scenarioId`
  （RUN_STARTED 归因，见 12.5），运行与场景可互查。

### 12.2 元信息补录（meta 平面）

控制平面回答"当前生效的编排配置"，meta 平面补录其**周边元信息**，三组数据
一个快照（`MetaState`，version 单调递增）：

| 组成 | 内容 | mp_* 表 |
|---|---|---|
| `runtime` | 运行时配置补录：modelBackend / requestTimeoutSeconds / maxWaveConcurrency / temperature / topP | `mp_runtime` |
| `portals` | 发布门户注册表：id/name/kind/endpoint/enabled | `mp_portal` |
| `releases` | 按场景发布配置：scenarioId/portalId/version/changelog/artifactType | `mp_release` |

- `MetaRegistry` 单写者 + `MetaStore` 接缝：`FileMetaStore`
  （`config/meta-plane.json`）│ `DbMetaStore`（mp_* 四表，另有 `mp_version`
  单行版本戳），跟随 `orchestrator.control.store` 装配，db 首启一次性导入
  既有 JSON；`orchestrator.meta.file` 可改路径。
- REST（`/api/v1/meta`）：GET 状态/门户/发布配置 + `PUT /runtime`、
  `POST /portals`、`DELETE /portals/{id}`、`PUT|DELETE /releases/{scenarioId}`，
  5 个变更端点全部写审计（见 12.3）。左侧「元信息补录」页三卡对应三组数据。

### 12.3 团队日志（teamlog 平面）

运行时团队的日志平面，**不限于业务日志**——三类条目同一 `team_log` 表
（ts/category/level/source/run_id/scenario_id/action/message/detail，
ts 与 (category, level) 双索引）：

| 类别 | 产出方 | 内容 |
|---|---|---|
| `RUN` | `ObservabilityActor` 直接经 Store 接缝（先于指标落库，见 12.1） | 运行/阶段边界事件流（含场景归因） |
| `AUDIT` | `TeamLogRecorder.audit(source, action, target, detail)` | 控制面 5 + meta 面 5 + 场景面 4 共 **14 个变更端点**的全量审计（动作/目标/JSON 明细；v2.2 发布管道再加 1，见 12.4） |
| `SYSTEM` | `TeamLogRecorder.system(...)` | 启动、接线等系统通知 |

- 存储接缝：`FileTeamLogStore`（JSONL `config/team-log.jsonl`，追加写）│
  `DbTeamLogStore`（`team_log` 表），跟随 `orchestrator.control.store`；
  `orchestrator.teamlog.file` 可改路径。
- **日志永不砸业务**：任何 append 失败降级 WARN，被记录的操作照常完成
  （TeamLogRecorder 的唯一不变量）。
- 检索：`GET /api/v1/logs?category=&level=&since=&until=&runId=&scenarioId=&q=`
  （时间倒序）；控制台「日志检索」页按类别/级别/关键字筛选，覆盖整体场景
  观测与可观测性回顾。

### 12.4 发布管道（release 平面）

发布 = 把场景资产打包为**可移植 JSON 包**并登记，门户信息从 meta 平面整合：

- `ReleaseService.publish(scenarioId)`：场景 → meta 平面读该场景的发布配置
  （无配置 400）→ 校验门户已注册且启用 → 组装 `ReleasePackage`
  （FORMAT `orchestrator-release-package` / 版本 `1.0` / 工具 `orchestrator`：
  **场景快照 + dry-run 证据** + 门户/版本/changelog/产物类型元数据）→
  序列化计算 **SHA-256** → 追加 `ReleaseRecord` 进注册表 → 审计
  `SCENARIO_PUBLISHED`。artifactType 未单独配置时回落场景自身的类型。
- 存储接缝：`FileReleaseStore`（`config/release-registry.json`，id = 内部
  version+1，重启续号）│ `DbReleaseStore`（`rl_release` 表），跟随
  `orchestrator.control.store`；`orchestrator.release.file` 可改路径。
- REST（`/api/v1/releases`）：`GET ?scenarioId=`（最新在前）、
  `POST /{scenarioId}/publish`、`GET /{id}/package`（attachment 重新导出，
  包体逐字节一致，哈希可对外校验）。控制台「发布注册表」卡支持行内一键发布。

### 12.5 导航-画布联动与生命周期闭合（控制台 v2.2）

- **全局上下文条**：画布顶部 `ctx-bar` 显示当前激活场景徽章 + 生命周期闭合链
  四步（推演 → 激活·运行 → 回放 → 场景指标）；步进按钮携带场景上下文跨页
  跳转，当前页对应步骤高亮。
- **激活状态持久化**：`ScenarioState` 新增第 4 分量 `activeScenarioId`——
  activate 是场景面一等状态变更（bump version + 落库，跨重启/跨客户端可见，
  空白归一为 null）；激活场景被删除时自动重置（闭合）。旧 JSON 状态文件缺该
  字段加载为 null，向后兼容。
- **场景归因贯通**：`RUN_STARTED` 携带 scenarioId → `RunTimeline`/
  `RunSummary`/`RunDetails`/`obs_run_metrics`/`team_log` 全链路带场景字段；
  回放列表加「场景」列，LM 指标页加场景筛选下拉（保留跨页携带的筛选值）。
- **跨页动作闭合**：运行完成卡 →「查看回放」「场景指标」；场景配置行内 →
  「推演」「激活」；dry-run 报告尾部 →「激活并运行」。全部经
  `gotoPage(page, {scenarioId})` 程序化导航，目标页按携带上下文预选。
- 静态资源缓存戳 `app.js?v=15`。

### 12.6 测试增量

M1 指标落库（RunMetricsStore file/db/UsageCapture/LmUsageCollector/指标 API）、
M2 元数据平面（MetaRegistry/MetaStore file+db/MetaController）、M3 团队日志
（TeamLogStore file+db/TeamLogRecorder/TeamLogController）、M4 发布管道
（ReleaseStore file+db/ReleaseService/ReleaseController）、M5 联动
（activeScenarioId 往返/activate 记录与删除重置/回放视图 scenarioId）——
合计 **195 例全绿**（`./mvnw test`，DB 用例跑 H2 内存库）。

## 13. v2.3：业务触发层、运行实例注册表与监控日志闭环

v2.2 回答"观测数据如何落库"，v2.3 对照 iPaaS 白皮书的**数据溯源**要求闭合
最后一环：从请求进入（data plan 层面的业务触发）起跟踪每次实例化过程，记录
多 Agent / 多模型的具体调用信息，并以**请求唯一 ID + 业务 ID** 关联展示完整
上下文。三块共享 v2.2 的既有模式：新存储平面镜像
"Store 接缝 + 跟随 `orchestrator.control.store` 装配"，Actor 侧沿用 WAL
写序，前端沿用导航-画布联动。

### 13.1 实例注册表（instance 平面）

- **生命周期**：`TripPlanningOrchestrator.planTrip(request, scenarioId,
  businessId)` 在编排开始前构造 `RunInstance`（12 字段 record：runId/
  businessId/scenarioId/templateName/mode/trigger/status/requestJson/
  startedAt/completedAt/durationMs/errorMessage），经 Actor 以
  `RUN_STARTED` 事件写 RUNNING 行；`RUN_COMPLETED`/`RUN_FAILED` 事件写终态
  （complete 携带 `Duration.between(startedAt, timestamp).toMillis()`，fail
  携带 errorMessage）。注册表写入位于 WAL 最前（先于 teamlog/metrics/折叠/
  SSE），失败降级 WARN，不砸业务。
- **存储接缝**：`InstanceStore` 接口 + 接口级静态 `matches()`（查询语义的
  单一定义：since/businessId/scenarioId/status/自由文本五 facet，文件后端
  与测试 fake 直接复用，DB 后端翻译为 SQL）。`FileInstanceStore`（JSONL
  `config/run-instances.json`，锁内原子重写，容量上限 1000 条按插入序裁剪）
  │ `DbInstanceStore`（`obs_run_instance` 表，run_id 主键 + business/
  scenario/started 索引；trigger_type 列规避 PostgreSQL 保留字），跟随
  `orchestrator.control.store` 装配。
- **businessId 贯穿**：`AgentObservabilityEvent` 升至 19 字段（runStarted
  新增 businessId 槽位），`RunTimeline`/`RunSummary`/`RunDetails`/日志归因
  全链路携带；控制器 `POST /api/trips/plan?businessId=`（可空，调用方业务
  关联键）。runId（UUID）与 businessId 一起构成"唯一 + 业务"双键关联。
- **per-run 指标读取**：`RunMetricsStore.byRun(runId)` 新增接口方法
  （db 实现按 created_at 排序回放 `obs_run_metrics` 行），为实例详情的
  多 Agent/多模型明细供数。

### 13.2 实例关联 REST（/api/v1/instances）

- `GET /api/v1/instances`：businessId/scenarioId/status 精确过滤 +
  `q` 自由文本（runId/businessId/场景/错误）+ `window` 时间窗速记
  （1h/24h/7d/all）+ limit（默认 100，上限 500），最新在前。
- `GET /api/v1/instances/{runId}`：聚合完整上下文——持久注册表行 +
  可回放内存时间线（`RunObservabilityController.toDetails` 复用投影，
  被淘汰后为 null）+ `obs_run_metrics` 每阶段行 + `team_log` 该 run 的
  日志尾随（limit 200）；未知 runId 返回 404。

### 13.3 控制台闭环（v2.3）

- **业务触发入口**：场景运行表单新增「业务关联 ID」输入，提交时以查询
  参数携带；「本次请求」回显与运行完成卡（新增「实例追踪 →」按钮，按
  businessId 过滤并直达该次实例详情）体现 businessId。
- **实例追踪页**（左侧「可观测」组首位，数据溯源入口）：五 facet 筛选 +
  实例表 + 详情视图——触发载荷（结构化 + 原始 JSON）、多 Agent/多模型
  调用明细表（wave/阶段/Agent/模型/结果/耗时/三段 tokens/尝试次数；
  优先 `obs_run_metrics` 持久行，时间线仍在时回退聚合 usage）、黑板终态、
  关联团队日志列表。
- **双向互链**：实例详情 ↔ 运行回放（时间线/DAG/泳道）↔ 团队日志
  （按 runId 预过滤）；日志页 runId 渲染为可点击 chip → 实例详情，
  scenarioId chip → 按场景过滤日志；运行回放头部显示 businessId 并可
  跳实例追踪。`gotoPage` 支持 `opts.businessId` 跨页携带。
- 静态资源缓存戳 `app.js?v=16` / `app.css?v=11`。

### 13.4 测试增量

M1/M2 实例注册表与关联 REST（RunInstance 生命周期、File/Db store 的
facet 检索与重启持久化、Actor 的实例写入与失败隔离、业务归因、
InstanceController 列表/筛选/详情聚合/404）+ M3 控制台适配
（controller 测试适配三参 planTrip）——合计 **209 例全绿**
（`./mvnw test`，DB 用例跑 H2 内存库）。

## 14. v3.0：前端解耦（TypeScript 控制台工程）

v0–v2.3 的控制台是打进 Spring Boot fat jar 的静态资源（`static/`，原生
JS 单文件 1838 行）。v3.0 将其解耦为独立 npm 工作区 `console/`：Vue 3 +
TypeScript + Vite + Pinia + Vue Router + Naive UI，与后端以 REST/SSE 契约
类型对接，独立构建、独立镜像、独立发布节奏。

### 14.1 动机：legacy 静态资源的四类局限

- **无类型契约**：fetch 返回 `any`，后端 DTO 演进（19 字段事件、实例聚合
  明细）无编译期对账，字段改名只能靠运行时发现。
- **单文件难维护**：11 个页面的数据流、SVG 算法、SSE 处理挤在 app.js，
  全局变量（`ctxScenarioId` 等）承担跨页上下文，刷新即失、不可分享链接。
- **无组件化**：DAG/泳道 SVG 在多处复制渲染逻辑；`syncMcpRows` 等手写
  DOM 同步与业务逻辑纠缠。
- **不可单测/静态检查**：无 lint、无 type-check，回归全靠人工点检。

### 14.2 框架选型论证

| 维度 | **Vue 3 + TS（选定）** | React + TS | Svelte | 保留原生 JS |
|---|---|---|---|---|
| 契约类型化 | `strict` 全量 TS，DTO 单一事实源 | 同左 | 同左 | 无 |
| 中后台组件库 | Naive UI：表格/表单/树/抽屉齐全，内置暗色主题 | 成熟（AntD/MUI）但更重 | 企业级表格生态薄弱 | 手写 |
| 与 legacy 迁移心智 | SFC 模板 ≈ 手写 DOM 的声明式版本，SVG/样式近乎原样平移 | JSX 化改写面更大 | 改写面大 | — |
| 产物体积 | gzip ~60 kB（全 11 页懒加载分包） | 类似偏大 | 最小 | 最小但无收益 |
| 学习/团队成本 | 低（模板语法贴近 HTML） | 中 | 中 | 零 |

选型要点：本控制台是典型"表格 + 表单 + 可视化"中后台，框架间的性能
差异不构成决策变量；决策变量是**类型契约、组件库成熟度与迁移成本**。
React 生态更强但对本场景无增益且 JSX 化改写面更大；Svelte 产物最小却
缺企业级表格组件；保留原生无法获得类型化与组件化收益。最终
**Vue 3（`<script setup>` + strict TS）+ Naive UI**：组件覆盖度足够、
暗色主题调色到 legacy 配色（`#58a6ff` 主色 / `#0d1117` 背景）、
DAG 与泳道 SVG 以 computed 布局原样移植（波次列 + 贝塞尔依赖边、
时间轴/顺序双模式 + 重试 ⚠/工具 tick）。

### 14.3 运行时拓扑：独立 nginx 容器 vs 内嵌 jar

**选定：独立容器 + nginx 反代**（`docker/docker-compose.yml` 的
`console` 服务，默认 `CONSOLE_PORT:-8081`）：

- **发布节奏解耦**：`orchestrator-console:${APP_VERSION}` 与后端镜像
  版本对齐但构建/发布独立——前端改版不必重打 fat jar。
- **关注点分离**：SPA fallback（history 路由深链）、SSE 反代
  （`proxy_buffering off` + 1h read timeout）、`/api` `/actuator`
  转发全部收敛在 `console/nginx.conf`；upstream 经 Docker 内嵌 DNS
  （`127.0.0.11`）请求时解析，后端宕机时控制台仍可启动（API 返回 502
  而非整体不可用）。
- **可扩展性**：SPA 静态资源可独立前置 CDN/负载均衡，后端按需扩容。

**内嵌 jar（legacy 路径）保留为零构建回退**：`static/` 原样保留并在
`index.html` 头部标注弃用（v3.1 移除计划），本地 `./mvnw spring-boot:run`
不依赖 Node 工具链仍可用。

**CORS：两种形态下浏览器都只见单一来源**——开发期 Vite dev server 将
`/api` `/actuator` 代理到 `localhost:8080`，生产期 nginx 反代到 `app:8080`，
后端无需任何 CORS 配置。

### 14.4 工程结构与契约实践

- **契约单一事实源**：`src/types/api.ts` 手工镜像后端 record（Java 侧
  无 OpenAPI 生成链，接口面收敛可控；后续可按需引入 openapi-typescript）。
- **跨页上下文**：legacy 全局变量改为 **route query**——
  `instances?businessId=&runId=`、`metrics?scenarioId=`、
  `logs?runId=&scenarioId=&window=`、`runs?runId=`、
  `run/dryrun?scenarioId=`；链接可分享、刷新可还原，生命周期闭合链
  （推演 → 激活·运行 → 回放 → 场景指标）在 Pinia session store 中承接。
- **严格构建链**：`vue-tsc --noEmit && vite build`（strict +
  noUnusedLocals/Parameters + verbatimModuleSyntax），类型错误即构建失败，
  前端纳入与后端同级的 CI 门槛。
- **交互升级**：`alert/confirm` → useMessage/useDialog；手写 DOM 同步 →
  Vue 响应式；每页懒加载分包。

### 14.5 部署与提醒事项

- **镜像**：`console/Dockerfile` 两阶段——`node:22-alpine` 内
  `pnpm install --frozen-lockfile + vue-tsc + vite build`（pnpm 由
  corepack 按 `packageManager` 字段钉版；`.dockerignore` 排除 node_modules/
  dist），运行时 `nginx:1.27-alpine` 仅携带静态产物 + nginx.conf。
- **APP_VERSION**：`orchestrator` 与 `orchestrator-console` 两镜像共用
  同一版本戳（`docker/.env.example`），后端 pom 版本与前端
  `package.json` version 需同步推进（发布检查项）。
- **Node 工具链**：容器内固定 Node 22 LTS；本机开发需 Node ≥ 20
  （pnpm ≥ 10，`packageManager` 字段钉版，注意 PATH），`pnpm dev` 的
  Vite 代理写死 `localhost:8080`。
- **SSE 代理**：任何前置代理层（nginx/网关/CDN）都必须对
  `/api/v1/events/stream` 关闭缓冲并拉长读超时，否则事件将成块延迟。
- **移除计划**：v3.1 删除 `static/` legacy 控制台（保留期间仅修致命
  缺陷，不新增功能）。


## 15. 目录组织原则（v3.0 重组方法论）

v3.0 前后端各自经历了一次**系统性目录重组**（后端 `d99bd42`、前端 `18a945d`）。
重组的出发点：按技术层分包（package-by-layer：controller / service / config / model …）
在单仓库内会随领域数量增长退化为"横向扫描地狱"——一次功能变更要横跨 5+ 个互不相邻的目录。
v3.0 采用 **package-by-feature**（按业务域分包）+ **模块内三层**：每个业务域自包含，
跨域关系只剩显式依赖，重组后 209 个测试零修改全绿，证明行为等价。

### 15.1 后端：模块内三层与 MVC 映射

经典 MVC 只解决"表现层与业务分离"一个问题；落到多域后端还需要回答
"业务域内部怎么组织、持久化怎么替换"。v3.0 的答案是每个领域模块内固定三层，
并以**端口-适配器**衔接持久化：

| 层 | 位置 | 职责 | MVC / 六边形对应 |
| --- | --- | --- | --- |
| 表现层 | `*/web/` | Controller、SSE 端点：协议适配、参数校验、DTO 进出，**无业务逻辑** | MVC-C |
| 核心层 | 包根 | 领域模型、注册表、应用服务/引擎、`*Store` 端口接口（依赖倒置的"端口"） | MVC-M |
| 基础设施层 | `*/infra/` | `Db*` / `File*` / `Noop*` 持久化适配器 + `*StoreConfig` 装配（"适配器"） | — |
| 视图 | `console/`（独立工程） | SPA，与 `web/` 层 REST/SSE 同构对齐 | MVC-V |

- 依赖方向固定：`web → 包根 ← infra`（infra 实现包根声明的端口，永不反向）。
- 同一抽象的多个适配器并存（DB / 文件 / Noop），由配置切换——存储后端对核心层透明。
- `V` 不在后端仓库树内，但通过 `types/api.ts` ↔ Java record 的契约镜像保持同构（见 §15.4）。

### 15.2 概念落位映射（AOP / SOP / EIP）

三个高频概念在本仓库的"物理落位"，回答"这个东西到底在哪个包里"：

| 概念 | 落位 | 具体体现 |
| --- | --- | --- |
| **AOP**（面向切面） | `kernel/shared/aop/` | `@Around` 织入全部 Agent 执行：耗时日志、span、Timer 指标一次织入，业务组件零观测噪音；横切面收拢 `kernel/shared/`，不与任何业务域耦合 |
| **SOP**（标准作业流程）的数字化 | `governance/scenario/` + `governance/release/` + `governance/teamlog/` | scenario = 作业指导书：推演（`DryRunEngine` 零 token 验证）→ 激活（热应用覆盖）→ 运行；release = 发布流水线（可移植 JSON 包 + SHA-256 + 注册表追加）；teamlog = 审计轨迹（14 个变更端点全量记录，先落库再推送 WAL 顺序） |
| **EIP**（企业集成模式） | `ops/observability/` + `core/mcp/` | observability = 事件总线：`ObservabilityEventBus`（pub-sub）+ `ObservabilitySsePublisher`（SSE 出站通道），Actor 邮箱解耦生产/消费速率；mcp = 外部系统适配器/网关：`McpConnectionRegistry` 建连、`InstrumentedMcpTool` 插桩（超时/重试/归因） |

### 15.3 分组层（zone）、配置就近与依赖方向

- **3~5 抽象法则**：目录树的每一层只呈现 3~5 个关键条目，超出即归组或细分——
  顶层 13 个业务模块归入 4 个 zone（`kernel` 内核 / `core` 编排核心 /
  `governance` 治理面 / `ops` 观测运维面），每 zone 2~5 个模块，一眼可读；
  细节沿 drill down 路径下钻（§3）。
- **zone 是导航分组，不是新的依赖边界**：依赖规则仍在**模块级**由 R1–R5 守护
  （§7.3）；模块环在模块级检测且当前零豁免（§15.6.2）。
- **配置就近归属**：`AgentConfiguration` / `AgentModelProperties` /
  `ModelHttpClientConfig` 位于 `core.agent/config/` 而非集中 `config/`——配置属于拥有它的模块，
  删除模块时配置随之消失，不会留下幽灵 Bean。
- **kernel/contract/ 稳定契约包（四子包）**：契约整体仍是**单模块**（R2 守护
  "只 import 自身"，子包互引视为包内），子包按角色导航——
  `spi/`：**Agent SPI**（`Agent<I,O>` + 自描述元数据 `AgentDescriptor`）；
  `model/`：契约数据模型（黑板 `OrchestratorContext`、`TripRequest`、四个响应 record、
  运行数据 `OrchestratorSnapshot`/`RunInstance`）；
  `config/`：控制面配置数据 record（`AgentRuntimeConfig`、`AgentDefaults`、
  `CalibrationRule`、`McpServerConfig`、`McpServerStatus`）；
  `port/`：跨域端口接口（`AgentRuntimeStore`、`ModelDefaultsReader`、`ControlPlaneReader`、
  `McpConnectionGateway`、`RunInstanceSink`、`RunStatusReader`）——
  数据面打破潜在循环，端口反转打破治理/观测域指向运行时实现的逆向依赖；
  契约类型变更必然是一次显式的跨域决策。
- **kernel/shared/ 横切内核**：异常映射、缓存控制、AOP、`RunContext` 运行归因上下文——
  无业务语义、零业务模块 import（R1 守护），进入 shared 需满足"被 ≥2 个域使用"的门槛。
- **核心层内聚子域再分层**：包根核心层类数超阈值（经验值 ~12）时按内聚子域细分
  （`agent/` → `advisor/` `config/`；`observability/` → `events/` `metrics/` `health/`；
  `contract/` → `spi/` `model/` `config/` `port/`，21 个契约类型按角色归组）；
  细分只是包内导航辅助，不改变依赖边界——contract 子包间互引仍按单模块判定（R2）。
- **依赖方向总规则**：`web → 包根 ← infra`；跨域只经 `kernel/contract/` 或对方 `web/`
  端口；`kernel/` 两包不依赖任何业务模块。规则由 §7.3 守护测试固化。

### 15.4 前端：feature-based 分区（console/）

前端采用与后端同构的组织逻辑（§3 树），三层各司其职：

| 分区 | 职责 | 与后端的对应 |
| --- | --- | --- |
| `src/app/` | 应用装配：main.ts（naive-ui 全局注册）、App.vue 壳、router、跨 feature 全局 store | 装配 ≈ `*StoreConfig` |
| `src/shared/` | 复用资产：类型化 `http`/`sse` 客户端、契约 DTO（`types/api.ts` 镜像后端 record，单一事实源）、通用组件（DagDiagram/SwimlaneChart/JsonDetails）、全局样式（`styles/base.css`）、格式化工具 | ≈ `kernel/contract/` + `kernel/shared/` |
| `src/features/<域>/` | 页面级特性（scenario / metadata / observability 三组），顶层对应侧栏分组 ≈ 后端 zone，组内子目录按后端模块再分层（如 `metadata/agents/` ≈ `governance.control`、`observability/instances/` ≈ `ops.instance`） | ≈ 后端 zone（core / governance / ops）内模块 |

- **`@` 路径别名**（vite `resolve.alias` + tsconfig `paths`）：所有跨目录 import 走
  `@/features/...`、`@/shared/...`，杜绝 `../../..` 深层相对路径；目录移动时只需改别名映射。
- **新增页面的落位规则**：新页面进对应 feature 组 + 按后端模块选子目录（无对应模块
  则先问"这是不是新领域模块"，是则前后端同步开）+ router 注册一行；若与现有
  侧栏分组都无关，先问"这是不是新领域域"，是则开新 feature 目录（前后端同步开）。
- **契约变更路径**：后端 record → `types/api.ts` → 视图层，单一方向，无手写散落类型。

### 15.5 Playwright 功能闭环（console/e2e/）

控制台的**功能性测试闭环**：`pnpm test:e2e` 在网络层拦截全部后端流量，
无需 Spring Boot 进程与真实模型，16 个 spec 约 8s 跑完（见 `console/e2e/`）。

- **确定性后端**：`e2e/mocks.ts` 以单一 `page.route('**/*')` 处理器拦截——非 `/api`、
  `/actuator` 路径放行（SPA 资源），SSE 路径整段 `text/event-stream` 回放，
  其余按 URL 匹配回 fixtures（日志接口还按 query 参数过滤）——响应形状镜像
  `types/api.ts` 契约，后端契约变更会在 mock 处直接暴露。
- **自动起服**：`playwright.config.ts` 的 `webServer` 自动 `pnpm dev --strictPort`
  （CI 外复用已运行实例），失败用例保留 trace 可回放。
- **覆盖面**：11 条路由冒烟 + SSE 黑板折叠（run_started → stage_completed →
  run_completed）+ 场景推演闭环（选场景 → 推演 → 波次/槽位流断言）+
  回放/实例详情打开 + 日志级别筛选打到后端 query 参数。
- **实操经验**（写新用例前先读）：
  - 断言**稳定标记**而非瞬态——mock 的 SSE 流播完即关闭，EventSource 立即转入
    重连，连接状态标签在"已连接/重连中"间振荡；应断言 run banner、stage 行等
    一次性写入后不再变化的文本。
  - Naive UI 控件交互走类选择器：下拉 `.n-select` + 选项 `.n-base-select-option`；
    可编辑单元格的**值**用 `toHaveValue()` 断言（`getByText` 匹配不到 input value）。
  - 表格断言注意列内容（运行列表无 runId 列，只有截断显示的场景/状态）；
    同名文本多处出现时用 `.first()` 或 `{ exact: true }` 消除 strict mode 歧义。

### 15.6 架构演进指南（守护测试的配套决策记录）

#### 15.6.1 依赖规则与豁免机制

- 规则本体见 §7.3（R1–R5）；R3 的模块环检测当前为**零豁免**状态（历史 4 对已反转，
  见 §15.6.2）——新环不得通过加豁免消化。
- 出现新环时按序问：公共依赖能否**下沉 `kernel/contract/`**（SPI/数据面）、
  **上浮 `kernel/shared/`**（横切语义）、或**经事件解耦**（EIP 发布/订阅）？
  三者皆不适用时先重构消费面（窄端口），仍无法消解再评估豁免（须同步登记测试注释与本节）。

#### 15.6.2 历史双向耦合反转记录（已清零）

R3 曾登记 4 对豁免；2026-11 已全部按下方记录执行反转，守护测试现为**零豁免**。
新的环必须按 §15.6.1 决策树反转消化，不得重新引入豁免。

| 已反转对 | 反转手段（下沉数据面 + 窄端口） |
|---|---|
| core.agent ⇄ governance.control | 纯数据下沉 `kernel.contract`（AgentRuntimeConfig/CalibrationRule/AgentRuntimeStore/AgentDefaults）；模型默认值经 `ModelDefaultsReader` 端口注入（agent 侧 `ModelDefaultsBridge` 桥接）；Agent SPI 自描述（`descriptor()`），control 不再 import agent 模块 |
| governance.control ⇄ core.mcp | `McpServerConfig`/`McpServerStatus` 下沉 contract；mcp 读控制面经 `ControlPlaneReader` 端口；control 的 HTTP 面经 `McpConnectionGateway` 端口消费连接状态 |
| ops.instance ⇄ ops.observability | `RunInstance` 下沉 contract；Actor 写实例经 `RunInstanceSink` 写端口（instance 侧配置类桥接），instance→observability 读侧保持单向 |
| ops.observability ⇄ core.trip | health/info 探针经 `RunStatusReader` 端口读取 `OrchestratorSnapshot`（contract 快照 record），trip 侧编排者提供实现，探针不 import trip 模块 |

#### 15.6.3 新域模块接入清单

1. **选 zone 归属**：零业务语义且被 ≥2 域用 → `kernel/`（shared 或 contract）；
   编排运行时 → `core/`；SOP 治理 → `governance/`；观测运维 → `ops/`；
   某 zone 超 5 个模块时先问是否该开新 zone；
2. 模块内按三层落位：`web/`（协议适配）+ 包根（核心）+ `infra/`（适配器）；
3. 与既有域的依赖只经 `kernel/contract/` 端口或对方 `web/`；数据/SPI 需求优先下沉 contract；
4. 把新模块名加入 `ArchitectureConventionTest.MODULES`，跑 R3 确认无新环；
5. 若出现新环：按 §15.6.1 决策树处理；确需豁免则同步登记测试注释 + 本节表格；
6. 更新 §3 zone 包树与 console 侧对应 feature（若有 UI 面）。

#### 15.6.4 结构阈值

- 核心层（包根）类数 > ~12 → 按内聚子域开子包（参照 agent、observability 的拆法）；
- 子包不承载新的跨域依赖方向——它只是包内导航，跨域规则仍按顶层模块判定；
- `contract/` 只进"被 ≥2 个域消费"的类型；单域私有类型不得上浮。

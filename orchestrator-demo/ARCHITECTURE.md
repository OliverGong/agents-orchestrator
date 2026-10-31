# Orchestrator 架构说明

基于 **Spring Boot 4 + Spring AI 2.0** 的多 Agent 编排示例：以"旅行规划"为业务载体，
演示 **Orchestrator-Workflow（编排者模式）** 与 **Blackboard（黑板模式）** 的组合用法，
并引入**协作模板（WorkflowTemplate）**、**AOP 横切切面**与**全链路可观测性**
（Actor 事件时间线 + OpenTelemetry 追踪 + Prometheus 指标 + Grafana 可视化）。

**v1** 在 v0 控制台基础上解耦升级：**Docker 化部署**（多阶段构建，jar 封装为容器服务）、
**内置并行模板示例**、**控制平面（Control Plane）**——前端可视化配置 Agent 人格/
模型/校准规则/MCP 服务与工作流拓扑并热生效、**SSE 实时事件流**（详见第 9 节）。

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
| Ollama | 本地 | LLM 推理（qwen:7b，可替换） |
| Maven Wrapper | — | 构建 |

## 3. 包结构

```
com.oli.orchestrator_demo
├── api                          # REST 层
│   ├── TripPlanningController   # REST 入口：POST /api/trips/plan
│   ├── RunObservabilityController # GET /api/trips/runs[/id]：运行时间线与上下文回放
│   ├── ControlPlaneController   # GET/PUT /api/v1/control/*：控制平面读写（v1，见 9.3）
│   ├── ObservabilitySseController # GET /api/v1/events/stream：SSE 实时事件流（v1，见 9.5）
│   └── GlobalExceptionHandler   # 异常 → 400/502/503/500 结构化映射
├── agent                        # Agent SPI 与实现
│   ├── Agent<I, O>              # Agent SPI：name() + execute(I)
│   ├── AbstractContextAgent<R>  # 模板方法基类：respond() + merge() + 解析重试
│   ├── ResearchAgent            # Step1：需求 → 景点调研
│   ├── BudgetAgent              # Step2：调研结果 → 成本估算
│   ├── ItineraryAgent           # Step3：调研+预算 → 逐日行程
│   ├── ReviewAgent              # Step4：全量审查 → 最终结论
│   ├── ResponseSanitizerAdvisor # LLM 输出净化（剥围栏/提 JSON/修形状）
│   └── CalibrationAdvisor       # 控制平面校准规则套用（v1，见 9.4）
├── orchestrator                 # 编排者（执行引擎）
│   ├── TripPlanningOrchestrator # 按协作模板波次执行，串行或并发
│   └── WorkflowTemplateHolder   # 模板热切换单点：volatile 引用（v1，见 9.3）
├── workflow                     # 协作模板（与 orchestrator 平级）
│   ├── ExecutionMode            # SEQUENTIAL | CONCURRENT
│   ├── StageSpec                # 阶段定义：name + agent + dependsOn
│   ├── WorkflowTemplate         # 模板：校验 + 依赖波次分解（纯数据+纯计算）
│   └── WorkflowProperties       # `orchestrator.workflow.*` 配置绑定
├── observability                # 运行时可观测性（Actor 事件存储 + 过程指标 + Actuator）
│   ├── AgentObservabilityEvent  # 事件：run/stage 边界 + mode/wave/peers + 上下文 JSON
│   ├── RunTimeline              # 单次运行的时间线投影（不可变，fold-only）
│   ├── ObservabilityActor       # 有界邮箱 + 单消费者线程折叠事件（容量/保留上限）
│   ├── ObservabilitySsePublisher  # 事件广播到 SSE 订阅者（虚拟线程 per-emitter，v1）
│   ├── OrchestratorMetrics      # run/stage 过程指标（计数器 + 时长 Timer + 保留数 Gauge）
│   ├── OrchestratorHealthIndicator  # /actuator/health 的 orchestrator 组件
│   └── OrchestratorInfoContributor  # /actuator/info 的 workflow 配置
├── aop                          # 横切关注点
│   └── AgentExecutionAspect     # @Around：Agent 耗时日志 + span + Timer 指标
├── control                      # 控制平面（v1，见第 9 节；v2.0 存储分层，见第 10 节）
│   ├── AgentRuntimeConfig       # Agent 运行时配置：prompt/model/温度/校准规则/MCP 引用
│   ├── CalibrationRule          # 校准规则：正则 pattern → replacement
│   ├── McpServerConfig          # MCP 服务记录：名称/传输(SSE|STDIO)/端点/启停
│   ├── WorkflowControlState     # 工作流控制态：mode + 阶段列表（前端编辑形态）
│   ├── ControlPlaneState        # 全量状态快照（version 单调递增，JSON 持久化）
│   ├── AgentDefaults            # 内置人格（状态文件种子）+ 已知 Agent 花名册
│   ├── AgentRuntimeStore        # Agent 读取运行时配置的 SPI
│   ├── AgentConfigRegistry      # 生产实现：文件持久化 + 校验 + 热应用 + 原子写
│   ├── ControlPlaneStore        # 持久化接缝（v2.0）：load/save/currentVersion
│   ├── FileControlPlaneStore / DbControlPlaneStore / CachedControlPlaneStore
│   │                            # JSON 原子写 │ cp_* 表单事务写 │ Caffeine 读缓存
│   └── ControlPlaneCrypto       # AES-256-GCM 字段加密 + GET 脱敏/PUT 还原（v2.0）
├── scenario                     # 场景平面（v2.1，见第 11 节）
│   ├── Scenario                 # 场景记录：请求模板 + workflow/agent/MCP 覆盖 + 产物类型
│   ├── ScenarioState / ScenarioStore # 版本化快照与持久化接缝（镜像 ControlPlaneStore 模式）
│   ├── FileScenarioStore / DbScenarioStore # config/scenarios.json │ cp_scenario* 两表
│   ├── ScenarioRegistry         # 单写者：CRUD + 引用校验 + activate 热应用 + dry-run
│   ├── DryRunEngine / DryRunReport # 零 token 推演：模板校验 + 波次展开 + 槽位流
│   └── ScenarioController       # /api/v1/scenarios CRUD + dry-run + activate
├── config                       # 配置
│   ├── AgentConfiguration       # @EnableConfigurationProperties 激活（人格已迁控制平面）
│   ├── ModelHttpClientConfig    # 模型后端 HTTP 超时（connect 5s / read 5min）
│   └── AgentModelProperties     # `orchestrator.agents.*` 逐 Agent 模型/温度
└── model                        # 数据模型（黑板 + DTO，均为 record）
    ├── OrchestratorContext      # 不可变黑板（request + 四个响应槽位 + mergeFrom）
    ├── TripRequest              # 用户输入：目的地/天数/预算/偏好
    ├── ResearchResponse         # Step1 产出：景点/美食/旅行贴士
    ├── BudgetResponse           # Step2 产出：分项成本/总额/是否达标
    ├── ItineraryResponse        # Step3 产出：逐日计划（DayPlan）
    └── ReviewResponse           # Step4 产出：校验结论/问题/最终摘要
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
`execution(* com.oli..agent.Agent+.execute(..))` 织入**所有** Agent 调用：

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

`RunObservabilityController`（api 包）暴露只读视图：`GET /api/trips/runs`
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

共 83 个用例，全部离线运行（不依赖本地 Ollama），`mvn test` 数秒完成。

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
| `TripPlanningControllerTest` | api | POST JSON → 200；入参绑定；全量上下文序列化 |
| `RunObservabilityControllerTest`（2 例） | api | GET /api/trips/runs[/id]：时间线按波次分组 + parallel 标记 + 上下文 JSON；未知 runId → 404 |
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
./mvnw test                                     # 全量测试（83 例，离线）
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
  可扩展：`RunTimeline` 持久化、`orchestrator.planTrip` 内增加波次级子 span、
  或为模板元数据挂 LLM token 用量。

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

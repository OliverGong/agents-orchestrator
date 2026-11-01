# Orchestrator

多 Agent 旅行规划编排服务 —— 以「编排者 + 黑板」模式演示 Spring AI 2.0 的多 Agent 协作、
运行时控制平面、场景平面、MCP 动态工具接入与端到端可观测性（Traces / Metrics / 运行时间线回放）。

- 技术基线：Java 25 · Spring Boot 4.1.2 · Spring AI 2.0.1（BOM）· MCP Java SDK · Vue 3 + TypeScript（v3.0 控制台）
- Maven 坐标：`com.oli:orchestrator:3.0.0-SNAPSHOT`（版本经 `/actuator/info` 暴露，控制台侧栏可见）
- 功能版本：**v3.0（开发中，`feat/v3.0-console-decoupling` 分支）**，主干 `main`
- 深入设计细节见 [ARCHITECTURE.md](./ARCHITECTURE.md)（本文为其索引入口）

---

## 1. 当前版本

| 里程碑 | 提交 | 内容 |
| --- | --- | --- |
| v0 基线 | `8287435` | research → budget → itinerary → review 四 Agent 串行链、黑板上下文（OrchestratorContext）、可观测性三件套 |
| v1 | `7b01c53` / `5211e4f` | Docker 多阶段镜像、parallel 并行模板、SSE 实时事件流、控制平面 v1（agent 配置热更新 + 响应校准规则）、非 root 运行时镜像 |
| v1.1 · Phase 1 | `7cf2587` | Agent 槽位契约描述符（input/output/pending-safe slots）+ 工作流逻辑闭环校验（保存模板即验证数据依赖可满足，否则 400） |
| v1.1 · Phase 2 | `d1e261a` | **MCP 解耦**：连接注册表从控制平面动态建连（Streamable HTTP 默认 / SSE 兼容 / STDIO 子进程）、版本 diff 热重建、失败冷却重试与手动重连；**工具注入**：映射服务器的工具以 ToolCallback 进入每次模型调用；**工具调用可观测**：耗时 / 结果 / 归因进时间线与 SSE；前端 MCP 面板与运行回放工具明细 |
| v2.0 | `14fe222` / `1aaa4c6` | **控制平面持久化基座**：`ControlPlaneStore` 抽象——文件（默认/seed/回退）或 PostgreSQL（规范化 `cp_*` 表 + 单事务写 + WAL 崩溃安全）；Caffeine 缓存（version 戳失效 + 写透 + TTL）；`cp_mcp_server.env` 字段级 AES-256-GCM 加密（`ORCHESTRATOR_CRYPTO_KEY`），GET 脱敏 `******` / PUT 回写还原；首次启动 JSON → DB 一次性导入；compose 栈切 db 模式（postgres:16 + 健康检查） |
| v2.1 | `90f76a8` + console | **场景元模型（Scenario Plane）**：场景 = 请求模板 + 工作流覆盖 + Agent 覆盖 + MCP 映射 + 产物类型，`ScenarioRegistry` 单写者 + `ScenarioStore` 接缝（file / `cp_scenario*` 表，跟随同一 store 开关）；`activate` 将覆盖热应用到控制平面（动态实例化）；`dry-run` 零 token 推演（模板校验 + 波次展开 + 黑板槽位流）；**解析重试事件链**：`STAGE_RETRIED` 经 RunContext 归因进时间线 / SSE / 回放；**控制台 v2.1**：左侧三组导航（场景运行·推演·配置 / agents·workflow·MCP / 回放·观测）+ 运行回放时间线 / DAG / 泳道三视图（原生 SVG）+ retry 链可视化 |
| v2.2 | `b4b7121` → `050e587` | **观测落库 + LM 指标**（M1）：`ObservabilityActor` WAL 顺序先落库再推送，`obs_run_metrics` 记录每场景每阶段的 LM 耗时 / prompt·completion·total tokens / 模型 / 重试（`UsageCaptureAdvisor` 采集），场景维度指标 API；**元信息补录**（M2）：`meta/` 平面——运行时配置（modelBackend / 超时 / 波次并发 / temperature / topP）、发布门户注册表、按场景发布配置（file / `mp_*` 表），左侧「元信息补录」页三卡；**团队日志**（M3）：`team_log` 持久化运行时日志（RUN / AUDIT / SYSTEM，不限于业务日志）+ 控制面/元数据/场景面 14 个变更端点全量审计 + Log Explorer 筛选页；**发布管道**（M4）：可移植 JSON 发布包（场景快照 + dry-run 证据 + 门户/版本元数据，SHA-256）追加到 `rl_release` 注册表，发布记录随时导出；**导航-画布联动**（M5）：全局上下文条 + 生命周期闭合链 推演→激活·运行→回放→场景指标，激活场景持久化（`activeScenarioId`），运行/回放携带场景归因 |
| v2.3 | `6a14f2f` → `f8bcc76` | **业务触发层 + 实例注册表**（M1/M2）：每次业务触发（`POST /api/trips/plan`，可携带调用方 `businessId`）在请求进入时实例化一条 RUNNING 记录（唯一 runId + businessId），终态由 `ObservabilityActor` WAL 写入 `instance/` 注册表（JSONL / `obs_run_instance` 表，跟随 store 开关）；`GET /api/v1/instances` 按 businessId / 场景 / 状态 / 关键字 / 时间窗检索，`GET /api/v1/instances/{runId}` 聚合完整上下文（注册表行 + 可回放时间线 + 每阶段多 Agent/多模型 LM 明细 + 关联团队日志）；**控制台闭环**（M3）：左侧「可观测」新增「实例追踪」页（数据溯源入口），运行表单支持业务 ID，日志页 runId/场景可点击互链，实例 ↔ 回放 ↔ 日志 ↔ 指标双向跳转 |
| v3.0（开发中） | `b132679` → | **控制器归位**（M1）：controllers 迁入各自领域包 `trip/` `observability/` `instance/`，`api/` 仅留横切（异常映射 + 缓存策略）；**前端解耦**（M2）：`console/` 独立 TypeScript 控制台工程（Vue 3 + Vite + Pinia + Vue Router + Naive UI，strict 构建链），契约 DTO 单一事实源（`types/api.ts`）、11 页面全量迁移（DAG/泳道 SVG 组件化移植）、legacy 全局变量 → route query 跨页上下文、独立 nginx 容器（compose `console` 服务，SPA fallback + SSE 关缓冲反代）；`static/` legacy 保留为零构建回退并标注弃用（v3.1 移除）；**目录重组**（M3）：后端重组为「shared 横切内核 + contract 契约 + 领域模块三层（web/ 包根 / infra，端口-适配器）」、前端 feature-based 分区 + `@` 路径别名（MVC 映射与 AOP/SOP/EIP 落位见 ARCHITECTURE §15）；**工具链**（M4）：console 切换 pnpm（corepack 按 `packageManager` 钉版 + Dockerfile store cache mount）+ Playwright E2E 功能闭环（16 spec，网络级 mock 后端 + SSE 回放）；分支/hotfix 策略见 `docs/BRANCHING.md` |

当前测试规模：**209 个用例全绿**（`./mvnw test`，DB 相关用例跑 H2 内存库），覆盖编排、控制平面校验、场景注册表与零 token 推演、MCP 注册表与工具插桩、观测控制器与落库指标、运行实例注册表、元数据平面、团队日志、发布管道、存储后端与加密；前端另有 **16 个 Playwright E2E**（`cd console && pnpm test:e2e`，网络级 mock 后端 + SSE 回放，无需真实服务），覆盖 11 条路由冒烟与推演 / 运行 / 回放 / 日志筛选功能闭环。

## 2. 体系架构

```
浏览器控制台 console/（Vue 3 + TS SPA，nginx 容器反代 REST + SSE；
        static/ legacy 原生 JS 同源直连，保留为零构建回退至 v3.1）
        │
        ▼
┌────────────────── Spring Boot 4.1.2（Java 25 虚拟线程）──────────────────┐
│ trip/       TripPlanningController（v3.0 领域包归位）                     │
│ observability/  RunObservabilityController · ObservabilitySseController  │
│ instance/   InstanceController                                            │
│ api/        GlobalExceptionHandler · ApiCacheControlConfig（横切）        │
│   │ POST /api/trips/plan              │ GET /api/trips/runs[/{id}]       │
│   ▼                                   ▲                                  │
│ orchestrator/  TripPlanningOrchestrator ── 依赖波次执行（wave）           │
│   │  · 工作流模板：SEQUENTIAL / CONCURRENT，模板热切换                    │
│   │  · 槽位契约闭环校验（AgentDescriptorRegistry）                        │
│   ▼                                                                      │
│ agent/  Research / Budget / Itinerary / Review                           │
│   │  AbstractContextAgent 模板基类：prompt 组装 · 结构化输出 · 解析重试     │
│   │  ChatOptions（模型 · 温度，控制平面热更新）+ MCP 工具注入               │
│   ▼                                                                      │
│ Spring AI 2.0.1 ChatModel ──► Ollama（默认）/ OpenAI                      │
│                                                                          │
│ control/ 控制平面（version 戳，逐次调用读取 → 即时生效）                    │
│   AgentConfigRegistry（单写者）· AgentDescriptorRegistry · WorkflowConfig  │
│   └─ ControlPlaneStore：file（JSON，默认/seed/回退）│ db（PostgreSQL）      │
│      DbControlPlaneStore：cp_* 规范化表 · 单事务写（WAL 崩溃安全）          │
│      CachedControlPlaneStore：Caffeine，version 戳失效 + TTL               │
│      ControlPlaneCrypto：AES-256-GCM 字段加密（env）+ GET 脱敏/PUT 还原     │
│                                                                          │
│ scenario/ 场景平面（v2.1，镜像控制平面模式）                                 │
│   ScenarioRegistry（单写者）· DryRunEngine（零 token 推演）                 │
│   └─ ScenarioStore：file（scenarios.json）│ db（cp_scenario* 两表）         │
│      Scenario = 请求模板 + workflow/agent/MCP 覆盖 + 产物类型；             │
│      activate → 覆盖热应用到控制平面（动态实例化，v2.2 记录 activeScenarioId）│
│                                                                          │
│ meta/ 元信息补录平面（v2.2）：运行时配置 · 发布门户 · 按场景发布配置           │
│   └─ MetaStore：file（meta-plane.json）│ db（mp_* 四表）                    │
│ release/ 发布管道（v2.2）：可移植 JSON 包（SHA-256）→ 发布注册表              │
│   └─ ReleaseStore：file（release-registry.json）│ db（rl_release 表）       │
│ teamlog/ 团队日志（v2.2）：RUN / AUDIT / SYSTEM 先落库再推送（WAL 顺序）      │
│   └─ TeamLogStore：file（team-log.jsonl）│ db（team_log 表）                │
│ instance/ 运行实例注册表（v2.3）：业务触发 → 唯一 runId + businessId 实例化    │
│   └─ InstanceStore：file（run-instances.jsonl）│ db（obs_run_instance 表）   │
│                                                                          │
│ mcp/ MCP 动态连接层（解耦核心）                                            │
│   McpConnectionRegistry：版本 diff reconcile · 异步建连 · 30s 冷却重试     │
│   ├─ Streamable HTTP（默认） ├─ SSE（SDK 已弃用，保留兼容） ├─ STDIO 子进程 │
│   InstrumentedMcpTool：计时 · mcp.tool.call.duration 指标 · RunContext 归因│
│                                                                          │
│ observability/  ObservabilityActor（时间线折叠）· RunContext · SSE         │
│   Micrometer ─► /actuator/prometheus    OpenTelemetry ─► OTLP gRPC :4317  │
└──────────────────────────────────────────────────────────────────────────┘
        │                                        │
        ▼                                        ▼
 Prometheus :9090（指标抓取）            Tempo :4317（链路） → Grafana :3000（预置仪表盘）
```

核心机制速览（详见 ARCHITECTURE.md 对应章节）：

- **黑板模式**：每个阶段读取前置阶段的黑板快照、写入自己的结构化输出；并行波次内各阶段从同一快照出发。
- **串行 / 并行执行**：模板按声明序校验依赖，依赖相同的阶段在 CONCURRENT 模式下组成同一波次，于虚拟线程上并行执行并合并结果（§4.7）。
- **逻辑闭环**：控制平面保存工作流时，按各 Agent 的槽位契约验证「输入槽位必须由前置阶段产出或声明为 pending-safe」，否则拒绝（Phase 1）。
- **MCP 解耦**：服务器连接由 `McpConnectionRegistry` 全生命周期管理——控制平面编辑即 bump 版本，下一次 reconcile 按差异重建；连接失败不阻塞 Agent 调用，工具在连接成功后的下一次请求自动出现（Phase 2）。
- **场景平面**：场景把「一套编排逻辑组」持久化为可切换资产——请求模板 + 工作流/Agent/MCP 覆盖（空字段继承控制平面当前值）；`activate` 经 registry 校验后热应用到控制平面，下一次运行即按场景执行；`dry-run` 复用工作流校验与波次分解做零 token 推演，报告含依赖 DAG、波次展开与黑板槽位流（v2.1）。
- **重试事件链**：Agent 解析重试不再静默——`AbstractContextAgent` 经 `ObservabilityEventBus`（RunContext 归因）发布 `STAGE_RETRIED`，折叠进时间线、SSE 推送、回放视图按阶段展示每次尝试（v2.1）。
- **语音回放定位**：时间线事件带 runId / stage / agent / waveIndex / 工具调用明细，回放与实时 SSE 共用同一折叠器，可精确对位到每次模型与工具调用。

## 3. REST API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/trips/plan` | 发起规划工作流（同步返回黑板终态） |
| GET | `/api/trips/runs` · `/api/trips/runs/{runId}` | 运行列表 / 单次运行完整回放（波次、阶段、工具调用、黑板快照） |
| GET | `/api/v1/events/stream` | SSE 实时事件流（阶段完成、工具调用） |
| GET/PUT | `/api/v1/control/agents[/{name}]` | Agent 运行时配置（prompt / 模型 / 温度 / 校准规则 / MCP 映射） |
| GET/PUT | `/api/v1/control/agents/{name}/descriptor` | Agent 槽位契约描述符 |
| GET/PUT | `/api/v1/control/workflow` | 工作流模板热切换（保存时闭环校验） |
| GET/PUT | `/api/v1/control/mcp/servers` | MCP 服务器配置（三传输，保存即差异重建连接） |
| GET | `/api/v1/control/mcp/status` | MCP 实时连接状态（含工具清单） |
| POST | `/api/v1/control/mcp/servers/{name}/reconnect` | 手动重连（绕过冷却） |
| GET | `/api/v1/control/state` · `/models` | 控制平面全量状态 / 可选模型 |
| GET/POST | `/api/v1/scenarios` | 场景列表 / 创建（创建即校验引用与模板合法性） |
| GET/PUT/DELETE | `/api/v1/scenarios/{id}` | 场景读 / 更新 / 删除（version 单调递增） |
| POST | `/api/v1/scenarios/{id}/dry-run` | 零 token 推演：校验 + 波次展开 + DAG 边 + 槽位流 |
| POST | `/api/v1/scenarios/{id}/activate` | 激活：覆盖热应用到控制平面（返回脱敏后状态，v2.2 起记录激活场景） |
| GET | `/api/v1/scenarios/{id}/metrics` | 场景维度 LM 指标（耗时 / tokens 聚合，v2.2） |
| GET | `/api/v1/metrics/lm` | 全场景 LM 指标聚合（obs_run_metrics，v2.2） |
| GET/PUT/POST/DELETE | `/api/v1/meta[...]` | 元信息补录：状态 / 运行时配置 / 发布门户 / 按场景发布配置（v2.2） |
| GET | `/api/v1/logs` | 团队日志检索（类别 / 级别 / 时间窗 / run / 场景 / 关键字，v2.2） |
| GET | `/api/v1/instances` | 运行实例注册表检索（businessId / 场景 / 状态 / 关键字 / 时间窗，v2.3） |
| GET | `/api/v1/instances/{runId}` | 单次实例化完整上下文（注册表行 + 回放时间线 + 每阶段 LM 明细 + 关联日志，v2.3） |
| GET | `/api/v1/releases` | 发布注册表（可按场景过滤，v2.2） |
| POST | `/api/v1/releases/{scenarioId}/publish` | 发布：打包（快照 + dry-run 证据 + 门户元数据）→ 哈希 → 注册表（v2.2） |
| GET | `/api/v1/releases/{id}/package` | 重新导出发布包（attachment 下载，v2.2） |
| — | `/actuator/health` · `/info` · `/metrics` · `/prometheus` | 健康检查（含 orchestrator 组件）、活工作流配置、指标 |

## 4. Docker 服务

`Dockerfile` 采用多阶段构建：`eclipse-temurin:25-jdk` 内跑 `./mvnw package`（`/root/.m2` 走 BuildKit cache mount，仅 pom 变更才失效依赖层），运行时镜像仅含 `25-jre` + Spring Boot fat jar，以非 root 用户 `10001` 运行；控制平面状态写在 `/app/config`（挂卷持久化）。不使用外部 `# syntax=` 前端镜像，避免构建期外网依赖。

`docker/docker-compose.yml` 本地栈：

| 服务 | 镜像 / 构建 | 端口 | 职责 |
| --- | --- | --- | --- |
| app | 本地多阶段构建（根目录 Dockerfile） | 8080 | 编排器本体；Ollama 默认走 `host.docker.internal:11434` |
| console | 本地两阶段构建（`console/Dockerfile`：node 构建 → nginx 运行） | 8081 (v3.0) | 解耦控制台 SPA：SPA fallback + `/api`、`/actuator` 反代到 app（SSE 关缓冲） |
| postgres | `postgres:16-alpine` | 5432 | 控制平面持久化（v2.0 db 模式）：`cp_*` 表 + `pg_isready` 健康检查 |
| tempo | `grafana/tempo:2.7.2` | 4317 (OTLP gRPC) / 4318 (HTTP) / 3200 (查询) | 接收并存储链路 trace |
| prometheus | `prom/prometheus:v3.2.1` | 9090 | 抓取 app 的 `/actuator/prometheus` |
| grafana | `grafana/grafana:11.5.2` | 3000 | 可视化：匿名 Admin 登录，预置 Tempo/Prometheus 数据源与 `agent-observability` 仪表盘 |

- **卷**：compose 项目名 `orchestrator` 前缀到实际卷名——`orchestrator_postgres-data` → 控制平面表存储、`orchestrator_orchestrator-config` → `/app/config`（JSON 状态：迁移源/回退后端）、`orchestrator_tempo-data` → Tempo 存储。跨环境迁移时直接整卷拷贝（同版本 Postgres 数据文件可平移）。
- **环境变量契约**：所有宿主相关值都经 compose 变量插值（`docker/.env.example` 有完整清单与默认值——复制为 `.env` 即可按环境覆盖，无需改动 compose 文件）：`SPRING_AI_OLLAMA_BASE_URL`（模型后端，部署到其他环境时指向可达的推理服务）、`APP_VERSION`（两镜像共同 tag，与 pom / package.json 版本一致）、`APP_PORT`（宿主端口，Prometheus 经 compose 网络内 `app:8080` 抓取，不随端口映射变化）、`CONSOLE_PORT`（v3.0 控制台宿主端口，默认 8081）、`OTEL_EXPORTER_OTLP_ENDPOINT`（默认 `http://tempo:4317`，gRPC 传输——HTTP 传输对 Tempo receiver 会 404）、`ORCHESTRATOR_CONTROL_STORE`（`db`：PostgreSQL / `file`：JSON）、`POSTGRES_DB|USER|PASSWORD`、`ORCHESTRATOR_CRYPTO_KEY`（可选，开启 MCP secret 的 AES-GCM 字段加密）、`ORCHESTRATOR_SPRING_EXCLUDE`（compose 置空以恢复 DataSource 自动配置——本地 file 模式默认排除它，避免无 JDBC url 启动失败）。
- **可迁移部署**：整套服务以 `name: orchestrator` 固定项目名，镜像 `orchestrator:${APP_VERSION:-3.0.0}` / `orchestrator-console:${APP_VERSION:-3.0.0}` 版本化命名——在任何有 Docker 的机器上 clone 仓库 + `docker compose up -d --build` 即得一致部署，或将镜像推送到 registry 后在其他环境直接 pull 启动；除 Ollama 地址外无宿主耦合。
- **前置条件**：模型后端就绪（本地为宿主机 Ollama，默认 `qwen3-vl:4b` / 各 agent 覆盖为 `qwen:7b`，可用 `ollama pull` 调整）。

```bash
cd docker
cp .env.example .env    # 按环境调整（可选，默认值即本地开发栈）
docker build -t orchestrator:3.0.0 ..   # 构建镜像（可选，compose up 会自动构建）

# 启动完整栈（app + console + postgres + tempo + prometheus + grafana）
docker compose up -d --build

# 查看可视化
#   Grafana     http://localhost:3000   （Tempo trace search + 指标仪表盘）
#   Prometheus  http://localhost:9090
#   控制台      http://localhost:8081   （v3.0 解耦 SPA；8080 为 legacy 回退，v3.1 移除）
```

## 5. 本地运行与测试

```bash
./mvnw spring-boot:run        # 需要 localhost:11434 有 Ollama；legacy 控制台 http://localhost:8080
./mvnw test                   # 209 个用例（无需真实模型，ChatClient 打桩；DB 用例跑 H2）
./mvnw clean package          # 产出 fat jar

cd console && pnpm install    # v3.0 解耦控制台（Node ≥ 20，pnpm 由 corepack 钉版）
pnpm dev                      # 开发服务器（/api、/actuator 代理到 localhost:8080）
pnpm build                    # vue-tsc 严格类型检查 + vite 产物构建
```

后端切换：`spring.ai.model.chat=openai` + `spring.ai.openai.api-key`；Agent 级模型 / 温度见
`application.properties` §2，或直接在控制平面 UI / `PUT /api/v1/control/agents/{name}` 热更新。

## 6. CI/CD

当前 CI/CD 以「本地可重复流水线」形态落地（Maven wrapper + 多阶段 Docker 构建 + compose 编排），
尚未接入托管 CI（仓库暂无 `.github/workflows/`）。流水线阶段与职责如下，均可原样平移到
GitHub Actions / GitLab CI：

| 阶段 | 命令 / 机制 | 说明 |
| --- | --- | --- |
| 1. 编译 + 测试 | `./mvnw clean test` | 209 用例，ChatClient 打桩 + H2 内存库，无外部依赖，天然适合 CI |
| 2. 前端构建（v3.0） | `cd console && pnpm install --frozen-lockfile && pnpm build` | vue-tsc 严格类型检查 + vite 打包，类型错误即流水线失败 |
| 2b. 前端 E2E（v3.0） | `cd console && pnpm test:e2e` | Playwright 功能闭环（16 spec，mock 后端 + SSE 回放），无真实服务依赖 |
| 3. 打包 | `./mvnw clean package` | Spring Boot fat jar |
| 4. 镜像构建 | `docker build .` + `docker build console/` | 多阶段构建 + 层缓存（pom/pnpm-lock 先行）+ cache mount；无外部 syntax 前端，CI 无需额外拉取 |
| 5. 部署 | `docker compose -f docker/docker-compose.yml up -d` | app + console + PostgreSQL + Tempo + Prometheus + Grafana 一键编排，数据卷持久化 |
| 6. 冒烟验证 | `/actuator/health`、`GET /api/trips/runs`、`GET /api/v1/control/mcp/status`、`POST /api/v1/scenarios/kyoto-classic/dry-run` | 健康、控制平面状态与场景推演即冒烟断言点（db 模式下 `postgres` 容器的 `pg_isready` 先行） |

工程约定：

- **提交规范**：Conventional Commits（`feat:` / `fix:`，scope 标注子系统如 `feat(mcp):`），
  便于由提交历史自动生成 changelog 与语义化版本号。
- **分支模型**：功能分支 → 合并主干；版本发布与 hotfix 策略见 `docs/BRANCHING.md`
  （大版本发布后经 `hotfix/*` 分支补丁修复并回灌主干/维护分支）。
- **运行时与代码分离**：`config/control-plane.json` 为 gitignored 的运行时事实源，代码不写死
  任何 Agent / 模板 / MCP 配置——镜像不可变、行为由控制平面数据驱动，这是 CI/CD 中
  「同一镜像多环境部署」的关键前提。
- **可观测性即验收**：部署后 Grafana 仪表盘 / Tempo 链路 / 运行时间线可直接作为集成验收证据，
  无需额外埋点脚本。

## 7. 文档索引

- [ARCHITECTURE.md](./ARCHITECTURE.md) —— 完整架构说明：包结构、核心抽象、执行模式、控制平面、
  可观测性、测试体系与版本演进（§9 v1 起含 Docker / 控制平面细节）
- [HELP.md](./HELP.md) —— Spring Boot 参考文档入口
- [docker/](./docker/) —— Tempo / Prometheus / Grafana 配置与仪表盘定义

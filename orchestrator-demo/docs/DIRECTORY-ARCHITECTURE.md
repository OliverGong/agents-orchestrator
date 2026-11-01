# 目录结构架构设计（Directory Architecture）

> 适用版本：v3.0+（zone 分组层引入于 feat/v3.0-console-decoupling；contract 子包化
> `spi/model/config/port` 与前端样式归位 `shared/styles` 于同分支后续提交）
> 配套文档：[ARCHITECTURE.md](../ARCHITECTURE.md) §3（包树速览）/ §7.3（守护测试）/ §15（组织方法论）
> 本文档回答一个问题：**这套目录为什么长这样，以及如何让它一直长这样。**

---

## 1. 设计动机（第一性原理）

目录结构的第一使命不是"分类收纳"，而是**降低认知负荷**：

1. **工作记忆约束**：人脑一次可靠处理 4±1 个组块。任何一层目录展开超过 5 个条目，
   阅读者就必须滚动、记忆和猜测——导航成本从"看一眼"退化为"查半天"。
2. **结构即架构**：目录树是新人、评审者、工具链看到的第一份架构图。物理布局若不
   表达逻辑分层，架构就只存在于文档里，随时间必然漂移。
3. **重组前的问题**：v3.0 重组后顶层曾有 **13 个业务包扁平铺开**——一眼扫不完，
   "哪些是内核、哪些是业务、哪些是治理"完全靠读包名猜测。

结论：把"每层 3~5 个关键条目"作为硬性设计法则，超出即**归组**（提一层抽象）或
**细分**（降一层 drill down）。

## 2. 设计目标

| 编号 | 目标 | 验证方式 |
|---|---|---|
| G1 | 每层 3~5：任何一层目录展开，关键条目 ≤5 | 人工评审 + 本文档 §8 清单 |
| G2 | 可 drill down：zone → module → layer → subdomain → class，每级一句话职责 | §3 分层模型 |
| G3 | 结构守护：布局规则可执行，漂移即测试失败 | `ArchitectureConventionTest` R1–R5 |
| G4 | 演进有路径：新模块有接入清单，耦合有豁免 + 反转计划 | §7 / §8 |
| G5 | 前后端同构：前端分区与后端 zone 概念对齐 | §6 映射表 |

## 3. 分层模型：五级 drill down

```
Level 1  zone        kernel / core / governance / ops        （+ 装配根，顶层 ≤5 条目）
Level 2  module      每 zone 2~5 个模块                       （每模块一句话职责）
Level 3  layer       web/（表现）· 包根（核心）· infra/（适配）  （模块内三层，见 §5）
Level 4  subdomain   包根类数 >~12 时按内聚子域开子包            （advisor/ events/ contract 的 spi/model/config/port）
Level 5  class       类级细节归 §4 与各版本章节                 （树中不展开）
```

**阅读路径**：拿到陌生代码，先定位 zone（它属于哪一面）→ 再看模块（它管什么）→
再看层（它是协议、逻辑还是存储）→ 需要时下钻子域与类。
**反向路径**：写新代码时同样自上而下落位——先答 zone，再答模块与层。

## 4. Zone 设计（Level 1）

判定标准只有一个第一性原理问题：**这段代码的本质职责服务于谁？**

| Zone | 本质职责 | 模块（2~5 个） | 依赖约束 |
|---|---|---|---|
| `kernel/` | 无业务语义的公共内核——服务于**所有人** | `shared`（横切件：web 异常/缓存 · aop 切面 · context 归因）<br>`contract`（契约面单模块，4 导航子包：`spi/` Agent SPI · `model/` 黑板+运行数据 · `config/` 控制面配置 · `port/` 跨域端口） | **零业务依赖**（R1/R2 守护）；准入门槛：被 ≥2 个域使用 |
| `core/` | 编排运行时——回答"**系统是什么**" | `trip`（应用入口：plan → 按模板波次执行）<br>`agent`（协作方：4 业务 Agent · 模板基类 · advisor · config）<br>`workflow`（协作模板：stage/dependsOn/mode · 热切换）<br>`mcp`（外部集成：MCP 注册表 · 插桩工具） | 依赖 kernel；跨域经 contract 端口 |
| `governance/` | 治理面——回答"**系统如何被管**"（SOP 数字化） | `control`（控制面：人格/模型/校准/MCP 热配置）<br>`scenario`（作业指导书：推演 → 激活 → 运行）<br>`meta`（元信息补录）<br>`release`（发布流水线）<br>`teamlog`（审计轨迹） | 依赖 kernel；经端口消费 core |
| `ops/` | 观测运维——回答"**系统如何被看**" | `observability`（事件总线 · SSE · 指标 · 健康）<br>`instance`（运行实例注册与业务关联） | 依赖 kernel/core/governance（读侧组合） |

**依赖总图**（箭头 = "允许依赖"；豁免对见 §8）：

```
            ┌─────────────────────────────────────────┐
            │                ops（观测）                │
            └───────┬─────────────────┬───────────────┘
                    │                 │
            ┌───────┴───────┐         │
            │  governance（治理）│     │
            └───────┬───────┘         │
                    │                 │
            ┌───────┴─────────────────┴───────┐
            │           core（编排运行时）        │
            └───────────────┬─────────────────┘
                            │
            ┌───────────────┴─────────────────┐
            │        kernel（内核：零业务）       │
            └─────────────────────────────────┘
```

> 注意：zone 是**导航分组**，不是新的依赖边界——依赖规则与豁免全部登记在
> **模块级**（如 `core.agent ⇄ governance.control`），由守护测试按模块粒度执行。

## 5. 模块内三层（Level 3）

每个模块固定表达 MVC，与持久化以**端口-适配器**衔接：

| 层 | 位置 | 职责 | 命名约定 |
|---|---|---|---|
| 表现层 | `web/` | 协议适配、参数校验、DTO 进出，**无业务逻辑** | `*Controller` / SSE 端点 |
| 核心层 | 包根 | 领域模型、注册表、应用服务、持久化**端口** | `*Store`（接口）、`*Registry`、`*Engine` |
| 基础设施层 | `infra/` | 持久化**适配器** + 装配 | `Db*` / `File*` / `Noop*` + `*StoreConfig` |
| 视图 | `console/`（独立工程） | SPA，与 `web/` REST/SSE 同构 | `types/api.ts` 镜像后端 record |

依赖方向固定：`web → 包根 ← infra`（infra 实现包根声明的端口，永不反向）。

**两条落位判据**：

- **配置就近**：模块的配置类进 `模块/config/`（如 `core.agent/config/`），不做集中
  `config/` 包——删除模块时配置随之消失，不留幽灵 Bean。
- **SPI 下沉**：被 ≥2 个模块实现或引用的接口下沉 `kernel/contract/`，打破跨域循环
  （先例：`Agent<I,O>`、`AgentDescriptor`）。下沉类型按角色落位到 contract 的四个
  导航子包：接口进 `port/`（Reader/Sink/Gateway/Store）、Agent SPI 进 `spi/`、
  控制面配置数据 record 进 `config/`、领域/运行数据 record 进 `model/`。

## 6. 前端对齐（console/）

前端与后端共享同一组织逻辑，三层与后端 zone 概念一一对应：

| 前端分区 | 职责 | 对应后端 |
|---|---|---|
| `src/app/` | 装配：main.ts、App.vue 壳、router、全局 store | 装配 ≈ `*StoreConfig` |
| `src/shared/` | http/sse 客户端、契约 DTO、通用组件、全局样式（`styles/base.css`）、工具 | ≈ `kernel/contract/` + `kernel/shared/` |
| `src/features/<域>/<模块>/` | 页面特性：顶层三组（scenario / metadata / observability）≈ zone，组内子目录 ≈ 后端模块（`scenario/{run→trip, dryrun·scenarios→scenario}`、`metadata/{agents→control, workflow→workflow, mcp→mcp, meta→meta}`、`observability/{runs·logs·metrics→observability, instances→instance}`） | ≈ 后端 zone（core/governance/ops）内模块 |

**条目数法则（前端与后端同一把尺）**：每个目录的可见条目（文件 + 子目录）
保持 **2~5 个**，超限先问"是否该按逻辑角色分组"再动手：

- `src/`（3 分区 + env.d.ts）、`shared/`（5 子包）、`features/`（3 组）——全部达标；
- 单文件模块目录（如 `metadata/agents/AgentsView.vue`）保留——目录名即模块名，
  与后端"包根"同构，新增组合文件时再分层，不为拆而拆；
- `e2e/` 布局同构：根只留跨组 spec（`console-navigation`、`observability`）与
  两个子目录——`support/mocks.ts`（测试基建 ≈ 后端 test fixture）、
  `scenario/`（场景组 3 个 spec，镜像 `features/scenario/`）；某组 spec ≥2 且
  根目录条目超限时按 features 组继续分包。

**工程根（console/）按 5 类阅读**（构建工具要求入口文件留在根，类别即分组）：

| 类别 | 条目 |
|---|---|
| 装配入口 | `index.html`（Vite 挂载点） |
| 构建与工具配置 | `vite.config.ts`、`tsconfig.json`、`playwright.config.ts`、`package.json`、`pnpm-lock.yaml` |
| 部署 | `Dockerfile`、`nginx.conf`、`.dockerignore` |
| 源码 | `src/`、`e2e/` |
| 本地生成（gitignore） | `dist/`、`node_modules/`、`test-results/` |

> 样式等横切资产归 `shared/`（与后端 `kernel/shared` 同构），`app/` 只留装配——
> 判断依据与后端一致：这段资产的本质职责服务于谁？所有页面 → `shared/`；仅装配用 → `app/`。

### 6.1 资产落位（assets）

**资产与代码同一把尺**：图标、字体、图片、仪表盘 JSON、provisioning 配置等
非代码资产同样遵循 2~5 条目法则，按**资产的服务对象**选落位（与 §6 判断依据
同构），并预定义落位避免"随手一放"：

| 资产类型 | 落位 | 分组方式 |
|---|---|---|
| 前端静态资源（favicon、图标、字体、图片） | `console/public/` | 按子类型分目录（`icons/`、`fonts/`、`images/`），每目录 ≤5 |
| 可观测栈资产（仪表盘、provisioning、采集配置） | `docker/grafana/`、`docker/prometheus/`、`docker/tempo/` | 仪表盘进 `dashboards/`，provisioning 按 Grafana 约定二分（datasources/dashboards） |
| 文档配图与示意资源 | `docs/assets/` | 按所属文档主题分组 |
| 后端 resources | `src/main/resources/` | 仅配置文件；当前无静态资源（v3.1 已移除 legacy static，不再回归） |

**当前盘点（2026-11）**：源码级零散资产为零——console 无 public/（index.html
无外链图标），后端 resources 仅 application.properties；已落位资产全部达标：
docker/ 根 5 条目、grafana 2 子目录（dashboards 1 / provisioning 2）、
prometheus 与 tempo 各 1 配置文件。新增资产时按上表落位，条目超限先分组。

## 7. 演进指南：新模块接入清单

1. **选 zone 归属**（§4 判定问题）：零业务语义且被 ≥2 域用 → `kernel/`；
   编排运行时 → `core/`；SOP 治理 → `governance/`；观测运维 → `ops/`；
   **某 zone 超 5 个模块时，先问是否该开新 zone**（回到 G1）。
2. 模块内按三层落位：`web/` + 包根 + `infra/`。
3. 跨域依赖只经 `kernel/contract/` 端口或对方 `web/`；数据/SPI 需求优先下沉 contract。
4. 把新模块加入守护测试的 `MODULES` 集合，跑 R3 确认无新环。
5. 出现新环 → 按决策树处理：下沉 contract / 上浮 shared / 事件解耦；确需豁免则
   同步登记测试注释 + 反转计划表（禁止无计划的静默豁免）。
6. 资产按 §6.1 落位表归位（前端 public/、docker 可观测栈、docs/assets/），目录条目超限先分组。
7. 更新 ARCHITECTURE §3 zone 包树与 console 侧对应 feature（若有 UI 面）。

**反模式清单**（重组过程中明确拒绝的方案）：

- ❌ 按技术类型分包（`controllers/` `services/` `repositories/`）——改动一个用例要横跨 N 个包；
- ❌ 集中式 `config/`、`utils/` "垃圾抽屉"——依赖方向不可判读；
- ❌ 为消环强行端口化一切——三行重复代码好过一个不必要的抽象，豁免 + 反转计划更诚实；
- ❌ 无注释豁免——环还在，只是看不见了。

## 8. 结构守护（fitness functions）

`src/test/java/.../architecture/ArchitectureConventionTest.java` 以**零依赖源码扫描**
（正则抽取 import 建图，无 ArchUnit、完全离线）把本文档的规则变成可执行断言：

| 规则 | 断言 | 守护的文档章节 |
|---|---|---|
| R1 | `kernel/shared/` 零业务模块 import | §4 kernel 约束 |
| R2 | `kernel/contract/` 只 import 自身（`spi/model/config/port` 及其角色内子包同属单模块，互引视为包内） | §5 SPI 下沉判据 |
| R3 | 模块依赖图（zone 限定名）无环，**零豁免** | §8 反转记录 |
| R4 | `infra/` 适配器不被跨模块引用 | §5 三层依赖方向 |
| R5 | `web/` 不触碰任何 `infra/` 适配器 | §5 三层依赖方向 |

**历史豁免反转记录（已全部执行，2026-11）**——反转手法统一为"纯数据下沉 contract + 窄端口注入"：

| 已反转对 | 反转手段 |
|---|---|
| `core.agent ⇄ governance.control` | 数据下沉（AgentRuntimeConfig/CalibrationRule/AgentRuntimeStore/AgentDefaults）+ `ModelDefaultsReader` 端口 + Agent SPI `descriptor()` 自描述 |
| `governance.control ⇄ core.mcp` | 数据下沉（McpServerConfig/McpServerStatus）+ mcp 侧 `ControlPlaneReader` / control 侧 `McpConnectionGateway` 端口 |
| `ops.instance ⇄ ops.observability` | 数据下沉（RunInstance）+ Actor 写侧 `RunInstanceSink` 端口（instance 配置类桥接），读侧保持单向 |
| `ops.observability ⇄ core.trip` | 探针侧 `RunStatusReader` 端口 + `OrchestratorSnapshot` 快照 record（trip 编排者实现） |

新环必须按 §7 决策树反转消化，不得重新引入豁免。

**阈值备忘**：包根核心层类数 >~12 → 按内聚子域开子包（先例：agent → advisor/config，
observability → events/metrics/health，contract → spi/model/config/port 四角色子包）；
子包不承载新的跨域依赖方向——contract 子包间互引仍按单模块判定（R2）。

**第二轮收敛（2026-11，叶子目录 2~5 化）**：首轮归组后仍有 9 个目录条目 6~8 个，
按逻辑角色二次开包，全部收敛至 2~5（测试树与主树同构镜像）：

| 目录（前→后条目数） | 角色分组 |
|---|---|
| contract/model（8→3） | `context/` 黑板（OrchestratorContext/Snapshot）· `run/` 请求与实例（TripRequest/RunInstance）· `llm/` 四响应 record |
| contract/port（6→2） | `controlplane/` 控制面读端口 · `runtime/` 数据面写/读端口 |
| governance/control（8→5） | `registry/` 配置注册 · `state/` 状态 · `store/` 持久化+加密 |
| governance/scenario（8→5） | `model/` 元模型 · `store/` 注册+存储 · `dryrun/` 零token推演 |
| governance/release（6→5） | `model/` 包与记录 |
| core/agent（7→4） | `agents/` 4 业务 Agent（基类/横切/配置留包根） |
| 测试根（8→6）、test core/agent（7→4）、test ops/observability（6→5） | `support/` 测试基建 · `agents/`、`web/` 镜像主树 |

要点：纯移动重构（git rename 保留历史），包声明与 import 同步重写；
守护测试 R2/R3 先后抓出并修复"注释类名误插 import"造成的假性依赖——
与零豁免原则一致；测试根 6 条目为 4 zone 镜像 + 2 横切（fitness/support），
因镜像主树不再细分。

## 9. 效果验证

- 全量 **214 个测试离线全绿**（含 5 条架构守护规则），两轮重组前后行为等价；
- 顶层 4 zone × 2~5 模块，§1 的"13 个包扫不完"问题消除；contract 21 个契约类型
  按角色归组 4 个导航子包（spi 2 / model 8 / config 5 / port 6，model/port 内再按
  角色分组），单文件目录不再铺开；全树叶子目录（含测试树）条目数 2~5 化（§8 二轮收敛）；
- 前端 17 个 Playwright E2E spec 不受影响（契约经 `types/api.ts` 镜像，未触碰）；
  `pnpm build`（vue-tsc strict + vite）与 E2E 全绿。

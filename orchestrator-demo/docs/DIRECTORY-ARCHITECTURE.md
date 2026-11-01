# 目录结构架构设计（Directory Architecture）

> 适用版本：v3.0+（zone 分组层引入于 feat/v3.0-console-decoupling）
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
Level 4  subdomain   包根类数 >~12 时按内聚子域开子包            （advisor/ events/ …）
Level 5  class       类级细节归 §4 与各版本章节                 （树中不展开）
```

**阅读路径**：拿到陌生代码，先定位 zone（它属于哪一面）→ 再看模块（它管什么）→
再看层（它是协议、逻辑还是存储）→ 需要时下钻子域与类。
**反向路径**：写新代码时同样自上而下落位——先答 zone，再答模块与层。

## 4. Zone 设计（Level 1）

判定标准只有一个第一性原理问题：**这段代码的本质职责服务于谁？**

| Zone | 本质职责 | 模块（2~5 个） | 依赖约束 |
|---|---|---|---|
| `kernel/` | 无业务语义的公共内核——服务于**所有人** | `shared`（横切件：web 异常/缓存 · aop 切面 · context 归因）<br>`contract`（契约面：黑板 + DTO · Agent SPI · AgentDescriptor） | **零业务依赖**（R1/R2 守护）；准入门槛：被 ≥2 个域使用 |
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
  （先例：`Agent<I,O>`、`AgentDescriptor`）。

## 6. 前端对齐（console/）

前端与后端共享同一组织逻辑，三层与后端 zone 概念一一对应：

| 前端分区 | 职责 | 对应后端 |
|---|---|---|
| `src/app/` | 装配：main.ts、App.vue 壳、router、全局 store | 装配 ≈ `*StoreConfig` |
| `src/shared/` | http/sse 客户端、契约 DTO、通用组件 | ≈ `kernel/contract/` + `kernel/shared/` |
| `src/features/<域>/` | 页面特性（scenario / metadata / observability 三组） | ≈ 后端 zone（core/governance/ops）内模块 |

## 7. 演进指南：新模块接入清单

1. **选 zone 归属**（§4 判定问题）：零业务语义且被 ≥2 域用 → `kernel/`；
   编排运行时 → `core/`；SOP 治理 → `governance/`；观测运维 → `ops/`；
   **某 zone 超 5 个模块时，先问是否该开新 zone**（回到 G1）。
2. 模块内按三层落位：`web/` + 包根 + `infra/`。
3. 跨域依赖只经 `kernel/contract/` 端口或对方 `web/`；数据/SPI 需求优先下沉 contract。
4. 把新模块加入守护测试的 `MODULES` 集合，跑 R3 确认无新环。
5. 出现新环 → 按决策树处理：下沉 contract / 上浮 shared / 事件解耦；确需豁免则
   同步登记测试注释 + 反转计划表（禁止无计划的静默豁免）。
6. 更新 ARCHITECTURE §3 zone 包树与 console 侧对应 feature（若有 UI 面）。

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
| R2 | `kernel/contract/` 只 import 自身 | §5 SPI 下沉判据 |
| R3 | 模块依赖图（zone 限定名）无环，豁免对显式登记 | §8 豁免机制 |
| R4 | `infra/` 适配器不被跨模块引用 | §5 三层依赖方向 |
| R5 | `web/` 不触碰任何 `infra/` 适配器 | §5 三层依赖方向 |

**当前豁免（4 对）与反转计划**——豁免是带还债计划的临时状态，任一侧重构时顺带执行：

| 豁免对 | 双向成因 | 反转计划 |
|---|---|---|
| `core.agent ⇄ governance.control` | 描述符内省 vs 运行时配置回读 | 描述符已下沉 contract；剩余边抽只读端口 |
| `governance.control ⇄ core.mcp` | 注册表读控制面状态 vs 状态端点组合 | 注册表发事件，控制面订阅（EIP 反转直读） |
| `ops.instance ⇄ ops.observability` | 回放读侧组合 vs Actor 写实例 | 实例写入改经事件端口 |
| `ops.observability ⇄ core.trip` | health 探针读活状态 vs 事件/指标发布 | 抽只读 `RunStatusReader` 端口（contract SPI） |

**阈值备忘**：包根核心层类数 >~12 → 按内聚子域开子包（先例：agent → advisor/config，
observability → events/metrics/health）；子包不承载新的跨域依赖方向。

## 9. 效果验证

- 全量 **214 个测试离线全绿**（含 5 条架构守护规则），重组前后行为等价；
- 顶层 4 zone × 2~5 模块，§1 的"13 个包扫不完"问题消除；
- 前端 16 个 Playwright E2E spec 不受影响（契约经 `types/api.ts` 镜像，未触碰）。

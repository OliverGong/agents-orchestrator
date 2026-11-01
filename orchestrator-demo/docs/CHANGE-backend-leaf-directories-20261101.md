# 变更摘要：后端叶子目录二轮收敛（2~5 条目化）

> 日期：2026-11-01 ｜ 提交：`7307392`（main）｜ 类型：结构重构（纯移动，行为零变更）
> 依据标准：[DIRECTORY-ARCHITECTURE.md](DIRECTORY-ARCHITECTURE.md)（每层 2~5 条目 + 逻辑角色分组）

## 1. 背景与盘点

首轮归组（v3.0 顶层 4 zone + contract 四子包）后，全树扫描发现仍有 **9 个目录**
条目数为 6~8，违反「每层 2~5 条目」法则（工程根与 console 根属已分类的生成物/五类
归组，不计入）：

```
8  test/java/.../orchestrator_demo      8  kernel/contract/model
8  governance/scenario                  8  governance/control
7  test core/agent                      7  core/agent
6  test ops/observability               6  kernel/contract/port
6  governance/release
```

## 2. 分组方案与执行（按逻辑角色开包）

| 目录（前→后条目数） | 角色分组 |
|---|---|
| `kernel/contract/model`（8→3） | `context/` 黑板（OrchestratorContext/Snapshot）· `run/` 请求与实例（TripRequest/RunInstance）· `llm/` 四响应 record |
| `kernel/contract/port`（6→2） | `controlplane/` 控制面读端口（ControlPlaneReader/ModelDefaultsReader）· `runtime/` 数据面端口（AgentRuntimeStore/McpConnectionGateway/RunInstanceSink/RunStatusReader） |
| `governance/control`（8→5） | `registry/` 配置注册 · `state/` 状态 · `store/` 持久化+加密（AES-GCM 归存储关注点） |
| `governance/scenario`（8→5） | `model/` 元模型 · `store/` 注册+存储 · `dryrun/` 零 token 推演 |
| `governance/release`（6→5） | `model/` 包与记录（ReleasePackage/ReleaseRecord） |
| `core/agent`（7→4） | `agents/` 4 业务 Agent（基类 `AbstractContextAgent`、`advisor/`、`config/` 留包根） |
| 测试根（8→6） | `support/` 测试基建（Await/ChatClientStubs/装配冒烟） |
| test core/agent（7→4） | `agents/` 4 个 Agent 测试，镜像主树 |
| test ops/observability（6→5） | `web/` 2 个 Controller 测试，镜像主树三层 |

执行方式：`git mv`（rename 历史全保留）→ package 声明与 import 全量重写
（FQN 导入替换 + 同包互引自动补 import）→ 清理 75 个 perl 残留临时文件。
contract 21 个契约类型不变，仅导航层级加深：`spi/model/config/port` 四角色子包
+ model 内三分、port 内二分（共 8 个子包）。

## 3. 守护测试的价值：两次报警、零豁免保持

- **R2（contract 纯净性）+ R3（模块无环）报警**：自动补 import 脚本把 javadoc
  注释中提到的类名（`AgentDescriptorRegistry`、`AbstractContextAgent` 等）误判为
  代码引用，给 contract 文件插入了 3 条违规 import，制造了假性依赖
  `contract → governance.control / core.agent`——fitness function 立即抓住并定位。
  修复 = 删除误插 import，无需任何豁免。
- **时间炸弹测试**：`FileTeamLogStoreTest.searchAppliesEveryFacet` 写死
  `2026-11-01T09:00:00Z` 作为 from 断言为空，UTC 过点必挂。改为
  `Instant.now().plusSeconds(3600)`，语义不变（"未来时刻之前无日志"）且永不过期。

## 4. 文档同步

| 文档 | 变更 |
|---|---|
| `ARCHITECTURE.md` §1 | 补"model/port 内再按角色分组" |
| `ARCHITECTURE.md` §3 zone 包树 | contract/control/scenario/release/agent 行更新为子包结构表达 |
| `ARCHITECTURE.md` §15.3 | 契约包描述重写：四角色子包 + 角色内分组（context/run/llm、controlplane/runtime） |
| `docs/DIRECTORY-ARCHITECTURE.md` §8 | 新增「第二轮收敛」记录表（本变更全量留痕）+ R2 措辞扩展 |
| `docs/DIRECTORY-ARCHITECTURE.md` §9 | 效果验证补"两轮重组行为等价、全树叶子目录 2~5 化" |

## 5. 验证

- `./mvnw test`：**214/214 全绿**（含 R1~R5 架构守护，零豁免）✅
- `git mv` rename 检测：所有搬移文件相似度 77%~99%，历史可追溯 ✅
- 前端未触碰（`types/api.ts` 镜像 record 类型名，与包路径无关），E2E 17/17 不受影响
- 复核：9 个目标目录条目数全部落入 2~5（测试根 6 条目为 4 zone 镜像 + 2 横切，
  因镜像主树不再细分，已文档注明）

**全栈闭合复验（2026-11-01，重组 + 资产 + 生成物维护全部落地后）**：
后端 `./mvnw test` 214/214 ✅ · console `pnpm build`（strict）✅ ·
`pnpm test:e2e` **17/17**（9.0s）✅ · 工作树干净，对应提交链
`182f100 → 8809915 → 7307392 → bd40329`（均已推送 origin/main）。

## 6. 效果

- 全工程（后端主树 + 测试树 + 前端 + e2e + 资产）叶子目录条目数收敛至 2~5，
  drill down 阅读路径在全树一致；
- 契约面 21 类型按「角色 → 子角色」两级导航，新契约类型的落位无需判断；
- 测试树与主树同构镜像（`support/`、`agents/`、`web/`），测试归位与主代码同规则；
- 守护测试再次证明零豁免纪律的有效性：结构错误在提交前被 fitness function 拦截。

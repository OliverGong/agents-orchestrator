# 变更摘要：前端目录矫正（console/）

> 日期：2026-11-01 ｜ 提交：`182f100`（main）｜ 类型：结构重构（行为零变更）
> 依据标准：[DIRECTORY-ARCHITECTURE.md](DIRECTORY-ARCHITECTURE.md) §6（3~5 条目法则 + 逻辑角色分组）

## 1. 背景与盘点

v3.0/v3.1 已完成前端 feature-based 重组与后端 zone 架构对齐，本次按同一把尺
（每个目录可见条目 2~5 个、文件按逻辑角色分类）对 `console/` 做全面盘点：

| 区域 | 盘点结果 | 处置 |
|---|---|---|
| `src/app/`（router/stores + 2 文件） | 4 条目，达标 | 不动 |
| `src/shared/`（api/components/styles/types/utils） | 5 子包，各 1~3 文件，达标 | 不动 |
| `src/features/`（scenario 3 / metadata 4 / observability 4） | 达标 | 不动 |
| `e2e/` | **6 文件平铺，超限** | 重组（见 §2） |
| `console/` 工程根 | 10 个跟踪条目未成文 | 按 5 类归组成文（见 §3） |

## 2. e2e/ 重组（核心变更）

重组前 → 重组后：

```
e2e/                          e2e/
├── mocks.ts                  ├── console-navigation.spec.ts   # 跨组：路由冒烟
├── console-navigation.spec   ├── observability.spec.ts        # 跨组：回放/实例/日志
├── observability.spec.ts     ├── scenario/                    # 场景组（3 spec）
├── run-live.spec.ts          │   ├── run-live.spec.ts
├── scenario-dryrun.spec.ts   │   ├── scenario-dryrun.spec.ts
└── scenario-lifecycle.spec   │   └── scenario-lifecycle.spec.ts
                              └── support/
                                  └── mocks.ts                 # 测试基建：确定性 mock 后端
```

- **6 条目 → 4 条目**，恢复达标；`scenario/` 与 `features/scenario/` 同构，drill down 路径一致；
- `support/` 承载跨 spec 测试基建（mocks.ts，git rename 100% 保留历史）；
- 5 个 spec 的 `import { installBackendMocks }` 相对路径同步修正；
- `playwright.config.ts` 注释同步（`e2e/mocks.ts` → `e2e/support/mocks.ts`）；
- 演进规则成文：某组 spec 累积 ≥2 且根目录条目超限时，按 features 组继续分包
  （observability 目前 1 个 spec，暂留根，不为拆而拆）。

## 3. 工程根 5 类归组（成文于 §6，不挪文件）

Vite/Docker 约定入口文件必须留在工程根，因此以**类别即分组**的方式成文：

| 类别 | 条目 |
|---|---|
| 装配入口 | `index.html` |
| 构建与工具配置 | `vite.config.ts`、`tsconfig.json`、`playwright.config.ts`、`package.json`、`pnpm-lock.yaml` |
| 部署 | `Dockerfile`、`nginx.conf`、`.dockerignore` |
| 源码 | `src/`、`e2e/` |
| 本地生成（gitignore） | `dist/`、`node_modules/`、`test-results/` |

顺手清理：console 根残留的 `.DS_Store`（三层 .gitignore 均已覆盖该模式）。

## 4. 资产矫正（assets 同一原则）

资产盘点（代码之外的所有非生成物）：源码级零散资产为零——console 无 `public/`
（index.html 无外链图标），后端 resources 仅 application.properties；唯一的
资产类资源是 docker/ 下的可观测栈配置，逐项核验全部达标：

| 资产位置 | 条目数 | 结论 |
|---|---|---|
| `docker/` 根 | 5（compose + env + 3 栈目录） | 达标 |
| `docker/grafana/` | 2 子目录（dashboards 1 / provisioning 2） | 达标 |
| `docker/prometheus/`、`docker/tempo/` | 各 1 配置文件 | 达标 |

落位规则成文于 [DIRECTORY-ARCHITECTURE.md](DIRECTORY-ARCHITECTURE.md) **§6.1**：
资产与代码同一把尺，按服务对象预定义落位——前端静态资源 → `console/public/`
（按 icons/fonts/images 分组）、可观测栈资产 → `docker/{grafana,prometheus,tempo}/`、
文档配图 → `docs/assets/`、后端 resources 仅配置（legacy static 已移除不再回归）；
条目超限先按逻辑角色分组再新增。

**常设约定**（已登记项目记忆）：所有项目相关的持续迭代或优化（功能、重构、
测试、资产、文档）都必须遵循「每层 2~5 条目、按逻辑角色分组」的目录架构原则。

## 5. 文档同步

| 文档 | 变更 |
|---|---|
| `docs/DIRECTORY-ARCHITECTURE.md` §6 | 新增「条目数法则（前端与后端同一把尺）」、单文件模块目录保留原则、e2e 布局与分包演进规则、工程根 5 类表 |
| `docs/DIRECTORY-ARCHITECTURE.md` §9 | E2E 数量 16 → 17 |
| `ARCHITECTURE.md` §15.5 | mock 路径更新、覆盖面补生命周期闭环（激活→运行→回放）、新增 e2e 目录布局说明 |

## 6. 验证

- `pnpm build`（vue-tsc strict + vite）✅
- `pnpm test:e2e` **17/17 全绿**（~9.5s）✅
- 后端无触碰（纯前端结构变更，214 个单元测试不受影响）

## 7. 效果

- 全工程（前端 + e2e）每层条目数收敛至 2~5，与后端 zone→module 树使用同一阅读法则；
- 测试目录与特性目录同构：`e2e/scenario/` ↔ `features/scenario/`，新 spec 的归位不再需要判断；
- 资产（静态资源、可观测栈配置、文档配图）有预定义落位，新增资产不再"随手一放"；
- 规则进入文档 §6/§6.1 并登记项目记忆，后续所有持续迭代按演进清单执行。

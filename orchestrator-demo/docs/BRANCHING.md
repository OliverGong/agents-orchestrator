# 分支与版本发布策略

本仓库遵循 **语义化版本（SemVer）** 与轻量级分支模型：`main` 是唯一稳定线，
功能开发走特性分支，发布后通过 hotfix 分支修补缺陷并打补丁 tag。

## 1. 版本号语义

`MAJOR.MINOR.PATCH`（如 `2.3.0`），对应：

| 段 | 何时递增 | 示例 |
| --- | --- | --- |
| MAJOR | 架构级重构、破坏性变更（REST 契约不兼容、部署形态变化、前端栈更替） | v2 → v3：控制台独立容器化 |
| MINOR | 向后兼容的新功能里程碑（新平面、新页面、新 API） | v2.2 → v2.3 |
| PATCH | 缺陷修复，不引入新功能 | 2.3.0 → 2.3.1 |

开发期间版本号带 `-SNAPSHOT` 后缀（如 `3.0.0-SNAPSHOT`），发布时去除
并打 tag `vX.Y.Z`。Maven 坐标版本（`pom.xml`）与功能版本（README 里程碑表）同步维护。

## 2. 分支模型

```
main ──────────●───────────●────────────●─────────▶  稳定线（每个 merge 点 = 一次发布）
               ▲           ▲            ▲
feat/vX.Y-*    │           │            │
   ├──●──●──●──┘           │            │
                           │            │
hotfix/vX.Y.Z ───●─────────┼────────────┘
                 │         │        （从 tag 切出，修复合回 main）
tag v2.3.0 ──────┘         │
tag v2.3.1 ────────────────┘
```

| 分支 | 命名 | 基点 | 归宿 | 用途 |
| --- | --- | --- | --- | --- |
| `main` | — | — | — | 唯一稳定线，始终保持可构建、测试全绿；受保护，仅经 PR/merge 进入 |
| 特性分支 | `feat/vX.Y-<topic>` | `main` | 合回 `main`（`--no-ff` merge commit） | 里程碑级开发单元，如 `feat/v2.3-observability`、`feat/v3.0-console-decoupling` |
| 热修分支 | `hotfix/vX.Y.Z` | **发布 tag `vX.Y.Z`** | 合回 `main` 并打新 tag `vX.Y.Z` | 大版本发布后的线上缺陷修补 |
| （可选）发布分支 | `release/vX.Y` | 特性分支合流后 | 合回 `main` + tag | 需要长周期稳定化/并行维护多个大版本时才启用 |

## 3. Hotfix 流程（大版本发布后的补丁）

线上缺陷出现在已发布版本 `vX.Y.Z` 时：

```bash
# 1. 从发布 tag 切出热修分支（不是从 main —— 修复目标是被发布的那份代码）
git checkout -b hotfix/v2.3.1 v2.3.0

# 2. 最小化修复 + 回归测试（./mvnw test 全绿）
git commit -m "fix(scope): ..."

# 3. 合回 main（保持版本线一致），打补丁 tag
git checkout main
git merge --no-ff hotfix/v2.3.1
git tag v2.3.1 && git push origin main --tags

# 4. 清理分支
git branch -d hotfix/v2.3.1
```

规则：

- **只修不增**：hotfix 分支仅允许缺陷修复与安全补丁，禁止夹带新功能——新功能进下一
  个 `feat/vX.(Y+1)-*` 分支。
- **基点是 tag 而非 main**：若 main 已有未发布提交，从 tag 切出可确保补丁精确作用于
  已发布代码；合并回 main 时 Git 会自动处理差异。
- **版本三处同步**：`pom.xml` 版本、`README.md` 里程碑表、发布 tag 同步推进。
- 若同一大版本需要多轮修补，重复上述流程递增 PATCH 号（2.3.1 → 2.3.2）。

## 4. 里程碑开发节奏

1. 从 `main` 切出 `feat/vX.Y-<topic>`，`pom.xml` 升至 `X.Y.0-SNAPSHOT`。
2. 里程碑内按 M1/M2/… 分主题提交（conventional commits，英文），每个主题保持测试全绿。
3. 功能闭环后：去除 SNAPSHOT、更新 README 里程碑表与 ARCHITECTURE 章节、
   `--no-ff` 合回 `main`、打 tag `vX.Y.0`。
4. CI（后续里程碑引入）在 merge 前强制执行构建 + 测试 + 前端 lint/build 门禁。

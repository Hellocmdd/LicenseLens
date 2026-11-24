# git-workflow.md — 分支、提交、审查与发布规范

前置：任何 git 写操作还要读 `commit-push-confirm.md`。本文档只规定"怎么组织历史"和"怎么走流程"。

## 1. 分支模型

| 分支 | 用途 | 命名 | 从哪来、回哪去 |
| --- | --- | --- | --- |
| `main` / `master` | 主干，保护分支，只接受 PR 合并；当前仓库以 `master` 为主，`ci.yml` 同时覆盖两者 | 固定 | — |
| `feat/*` | 新功能 | `feat/scanner-engine`、`feat/npm-parser` | 从 `main` 切出，合回 `main` |
| `fix/*` | 缺陷修复 | `fix/sort-order`、`fix/exit-code-4` | 同上 |
| `test/*` | 测试补充 | `test/t1-normal-scan` | 同上 |
| `docs/*` | 文档更新 | `docs/contract-v1`、`docs/readme` | 同上 |
| `chore/*` | 构建、CI、依赖等杂项 | `chore/checkstyle` | 同上 |

- 禁止直接向 `main` 提交或强推；`main` 上不 `rebase`、不 force push。
- 一个分支只做一件事，分支名用短横线连接的小写英文。
- 长期不合并的个人分支要拆分或废弃，不留在远端堆积。
- 契约变更不新建一套分支命名，仍用 `feat/*` 或 `fix/*`，但 PR 标题加前缀 `[CONTRACT-CHANGE]`。

## 2. 提交粒度

- **一次提交只做一件事**。判断标准：能否用一句 `<type>(<scope>): <subject>` 说清。
- 重构与功能分开；纯格式化与逻辑修改分开。
- 契约变更单独提交，且同一次提交内同步更新 `docs/CONTRACT.md`（含变更记录表）。
- 一次 PR 通常 5–10 个可回滚的小提交；不要把一周的改动压成一个巨型提交，也不要为凑数量拆出无意义的原子提交。
- 提交前本地跑通对应命令，测试不过不许继续叠加提交。

```powershell
mvn -B clean verify                              # 全模块构建、测试与装配
mvn -B test -pl licenselens-core,licenselens-cli # 仅核心与 CLI 测试
mvn -B test -f plugins/licenselens-npm/pom.xml   # 扩展单独测试（需先安装 parent 与 spi）
mvn -B checkstyle:check                          # 已接入 Checkstyle 后必跑
```

## 3. 提交信息格式

```text
<type>(<scope>): <subject>

<body，可选，写为什么>

<footer，可选：Refs/Closes、兼容性说明>
```

| type | 用于 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `test` | 测试相关 |
| `docs` | 文档 |
| `refactor` | 重构，不改外部行为 |
| `chore` | 构建、CI、依赖、杂项 |

- `scope` 取模块或领域：`spi`、`core`、`cli`、`npm`、`go`、`contract`、`docs`、`ci`、`build`、`samples`、`agent`；没有合适 scope 时省略括号，不硬造新词。
- `subject` 中文，动词开头，不超过 50 字，句末不加句号；说清"做了什么"，不写"修改了一些东西"。
- `body` 只写动机与取舍，不重复 diff 内容；跨模块影响、兼容性风险必须写。
- 需要新 `type` 或新 `scope` 时，先在 `docs/LicenseLens分工合作书.md` 的提交规范一节登记，再使用。

```text
feat(core): 实现 ScanEngine 调度与风险排序

按契约顺序选择解析器，报告按风险降序、名称升序列出，保证同输入同输出。

Refs: #12
```

```text
fix(cli): 无可用解析器时返回退出码 4

原先统一返回 2，与 CONTRACT.md 退出码表不符，T8 用例覆盖。
```

```text
docs(contract): 冻结 v1 退出码与错误契约
```

## 4. PR 与 Review

```text
1. 建分支（feat/… fix/… test/… docs/…）
2. 提交（5–10 个小提交，每步可回滚）
3. 推送并创建 PR
4. 标题沿用提交格式；描述写：做了什么 / 怎么验证的 / 需要 Review 什么
5. 指定非作者的 Reviewer；契约变更需全员 Review
6. Reviewer 必须给出具体意见（至少一条指向行为或边界）
7. 作者逐条处理，或有依据地拒绝；处理完推新提交
8. Reviewer 复查新提交后再决定合并
9. Squash Merge，保持 main 历史干净
10. 删除已合并分支
```

Review 最低要求：

- 至少一条意见指向**具体行为、边界、失败处理、测试或扩展接口**，能说清"什么输入会怎样"。
- 示例："`parse()` 在 `<dependencies>` 为空时应返回空 `List` 而不是 `null`，T3 断言的就是这个，当前实现会 NPE。"
- 禁止只有 "LGTM" / "Looks good" / "+1" / "没问题"；这类记录不算有效 Review。
- 契约变更的 PR 必须组内全员 Approve 后才能合并。
- Reviewer 合并前自行跑：`mvn -B clean verify` + 一条实际演示命令，确认通过再 Approve。

## 5. 契约变更流程

```text
1. 先改 docs/CONTRACT.md
2. 在变更记录表加一行：日期、变更内容、影响、审批
3. 全组 Review 文档变更
4. 三人同意后再改 Java 代码
5. PR 标题前缀 [CONTRACT-CHANGE]，描述里贴契约 diff
6. 合并后同步更新 docs/DESIGN.docx 对应章节与 README 的接入说明
```

- **禁止反向操作**：先改代码再补契约。发现即打回重写。
- 契约变更要评估兼容性并按 `CONTRACT.md` 第五节声明：新增默认方法=兼容；新增 `Dependency` 字段=不兼容；新增 `RiskLevel` 枚举=兼容但实现需 `default` 分支；删方法或改退出码=不兼容，升主版本。

## 6. 标签与发布

- 版本号用 `vX.Y.Z`，课程交付通常 `0.1.0`，不强行凑 `1.0.0`；版本号按实际成熟度选，并在 `RELEASE.md` 写清选择理由。
- 标签必须是**附注标签**（`git tag -a`）并带说明信息，禁止无说明的轻量标签。
- Tag 打在最终交付的 Commit 上，打完后**不再改动仓库**（否则交付物 SHA-256 失效，只能删 Tag 重打并额外记录）。
- 标签逐个推送（`git push origin v0.1.0`），不用 `--tags` 批量推。
- 发布记录写进仓库外的 `release/RELEASE.md`：版本、最终 Commit 完整 SHA、Tag 与目标 Commit、工作区与构建状态、交付物与运行命令、SHA-256、实际运行结果、已知限制、RELEASE/HOLD 决定。
- 发布前检查见 `release.md`。

## 7. 该提交与不该提交

- **提交**：源码、`pom.xml`、测试、`samples/`、`docs/`、`.github/workflows/`、`.gitignore`、`README.md`、`LICENSE`。
- **提交（团队共享）**：`.agent/` 提示词目录随仓库入库，全组共用；不要把它加进 `.gitignore`。
- **不提交**：`target/`、`*.class`、`*.jar`、IDE 配置（`.idea/`、`.vscode/` 本地设置）、真实密钥或 token、本地临时脚本、日志与截图、`release/` 下的交付物原件（按分工放在仓库外目录，记录进 `RELEASE.md`）。
- 大于 5 MB 的文件不入库；确需时先讨论。
- 提交前确认 `git status` 只包含本次相关文件，不夹带无关改动。

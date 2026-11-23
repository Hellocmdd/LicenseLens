# LicenseLens 大作业分工合作书

> 项目：LicenseLens 开源依赖许可证合规审计器
> 课程：《开源软件生态与开发技术》
> 团队：3 人组（组号：待填）
> 版本：v1.0（冻结于 Gate 0）
> 本文档与 `docs/CONTRACT.md` 共同锁定开发边界，任何变更须走 Review

---

## 一、成员与角色

| 成员 | 学号 | 主要角色 | GitHub 用户名 |
| --- | --- | --- | --- |
| A | 2024302111213 | 核心架构与 SPI 契约 | zzb（张展博） |
| B | 2024302111237 | npm 独立扩展开发者 | Hellocmdd（谭博友） |
| C | 2024302111046 | Maven 解析器 + CLI + 发布组织者 | GeneralDXL（段绪礼） |

---

## 二、模块分工

### 2.1 模块归属总览

```
licenselens/
├─ licenselens-spi/             ← A 负责（契约，无第三方依赖）
├─ licenselens-core/            ← A 负责（调度、策略、报告、licenses.json）
│   └─ internal/MavenManifestParser.java  ← C 负责（Maven 解析器，放在 core 内但独立）
├─ licenselens-cli/             ← C 负责（入口、参数、退出码、插件加载）
├─ plugins/
│   ├─ licenselens-npm/         ← B 负责（独立扩展，独立 reactor，只依赖 spi）
│   └─ licenselens-go/          ← B 负责（独立扩展，独立 reactor，只依赖 spi）
├─ docs/
│   ├─ CONTRACT.md              ← A 起草，全组 Review
│   └─ DESIGN.docx              ← A 主笔，B/C 各写负责模块章节
├─ samples/                     ← 三人协作，各自准备自己模块的样例
└─ .github/workflows/ci.yml     ← C 负责
```

### 2.2 详细分工表

| # | 交付项 | 类型 | 负责人 | 具体内容 | 不得触碰 |
| --- | --- | --- | --- | --- | --- |
| 1 | `licenselens-spi` | 代码 | A | 全部：`Dependency`、`DependencySource`、`Policy`、`Decision`、`RiskLevel`、`SourceException` | npm / Go 扩展实现 |
| 2 | `licenselens-core/ScanEngine` | 代码 | A | 扫描调度：选择解析器、收集结果、触发策略 | npm / Go 扩展实现 |
| 3 | `licenselens-core/PolicyEngine` | 代码 | A | 策略引擎：campus + permissive-only 两种策略实现 | npm / Go 扩展实现 |
| 4 | `licenselens-core/ReportRenderer` | 代码 | A | 报告渲染：Markdown 生成、确定性排序（风险降序→名称升序） | npm / Go 扩展实现 |
| 5 | `licenselens-core/licenses.json` | 数据 | A | 许可证快照：当前 4 条 Maven 坐标（规划约 40 条未达成），精确匹配 groupId:artifactId | npm / Go 扩展实现 |
| 6 | `licenselens-core/internal/MavenManifestParser` | 代码 | C | Maven 清单解析：pom.xml → `List<Dependency>`，JDK 自带 XML | 核心调度、策略、报告渲染 |
| 7 | `licenselens-cli` 全部 | 代码 | C | 入口类、参数解析（`--manifest/--policy/--plugins/--out`）、退出码、插件目录加载（URLClassLoader + ServiceLoader） | npm / Go 扩展实现 |
| 8 | `plugins/licenselens-npm` | 代码 | B | 已实现并合入 `master`：`package-lock.json`（v1/v2/v3）→ `List<Dependency>`，39 个契约测试通过 | core 内部任何类；Maven 解析器 |
| 8a | `plugins/licenselens-go` | 代码 | B | 已实现并合入 `master`：`go.mod`（require 块 / 单行 require / `/vN` 主版本）→ `List<Dependency>`，40 个契约测试通过 | core 内部任何类；Maven 解析器 |
| 9 | 核心单测（T1-T6） | 测试 | A + C | T1 正常流程、T2 策略差异、T3 空输入、T4 非预期输入、T5 文件不存在、T6 XML 语法错误 | npm / Go 扩展测试 |
| 10 | 扩展契约测试（T9） | 测试 | B | npm 39 例 + Go 40 例已通过（不依赖 core），验证输入输出字段、错误码与发现规则 | core 内部测试 |
| 11 | 扩展集成测试（T7-T8） | 测试 | A + B | T7 放入已构建插件 jar 后 CLI 自动发现（用例待补）、T8 移走全部插件后退出码 4 | — |
| 12 | `docs/CONTRACT.md` | 文档 | A 起草 | 一页纸：接口签名 + 退出码表 + 错误契约 + 兼容策略 | — |
| 13 | `docs/DESIGN.docx` | 文档 | A 主笔 | 架构图（A）、核心流程图（A）、npm / Go 扩展接入示例（B）、CLI 退出码表（C）、测试矩阵（A+C） | — |
| 14 | `samples/` | 样例 | 三人各出 | A：demo-maven/pom.xml；B：demo-npm/package-lock.json + v1/v2 fixture、demo-go/go.mod 及变体与 edge/bad-go.mod、edge/empty-go.mod；C：edge/empty.xml、bad-pom.xml、bad-lock.json、placeholder-pom.xml、management-pom.xml（不设 expected/report.md） | — |
| 15 | GitHub Actions CI | 配置 | C | `mvn -B verify` push 触发，保留一次绿色运行记录 | — |
| 16 | Checkstyle 配置 | 配置 | C | `verify` 阶段绑定 `maven-checkstyle-plugin:check`；规则集在 `config/checkstyle/checkstyle.xml`，L08 原件未提供前用按代码风格约束落的最小集合，拿到 L08 后替换 | — |
| 17 | README.md | 文档 | 三人协作 | 用户场景 + 快速开始（C）+ 核心与扩展（A）+ 测试与质量（A+C）+ 协作规则（C）+ 成员贡献表（全员填）+ AI 核验（全员填） | — |
| 18 | RELEASE.md | 文档 | C 主填 | 版本 + Commit + Tag + SHA-256 + 运行结果，仓库外 release/ 目录 | — |

### 2.3 接口边界图

```
DependencySource (spi)                    ← A 写接口契约
    ↑ 实现
    │
    ├── MavenManifestParser (core/internal)   ← C 写（内置，不是扩展）
    │
    ├── NpmManifestParser (plugins/npm)       ← B 写（独立扩展，只依赖 spi）
    │
    └── GoModManifestParser (plugins/go)      ← B 写（独立扩展，只依赖 spi）

Policy (spi)                              ← A 写接口契约
    ↑ 实现
    └── CampusPolicy + PermissiveOnlyPolicy (core)  ← A 写

ScanEngine (core)                         ← A 写调度逻辑
    ├── 调用 DependencySource.supports()/parse()
    ├── 调用 Policy.evaluate()
    └── 调用 ReportRenderer.render()

CLI (licenselens-cli)                     ← C 写入口
    ├── 加载 plugins/ 目录下的所有 jar
    ├── ServiceLoader.load(DependencySource.class)
    └── 调用 ScanEngine.scan()
```

**铁律**：
- B 的 npm / Go 扩展 pom.xml 里**只允许**依赖 `licenselens-spi`，编译期阻断越界引用 core；
- C 的 MavenManifestParser 虽然放在 core 内部，但它是**内置基础能力**，不是独立扩展；
- A 不得修改 B 写的任何 npm / Go 扩展文件（包括测试）——这是"扩展独立性"的直接证据。

---

## 三、协作规则

### 3.1 Git 分支模型

| 分支 | 用途 | 命名 |
| --- | --- | --- |
| `main` / `master` | 主干，保护分支，只接受合并；当前仓库与 CI 以 `master` 为主，`ci.yml` 同时覆盖两者 | 固定 |
| `feat/*` | 新功能开发 | `feat/scanner-engine`、`feat/npm-parser` |
| `fix/*` | Bug 修复 | `fix/sort-order`、`fix/exit-code-4` |
| `test/*` | 测试编写 | `test/t1-normal-scan` |
| `docs/*` | 文档更新 | `docs/contract-v1`、`docs/readme` |

### 3.2 提交规范

```
<type>(<scope>): <description>

feat(spi): add DependencySource interface
fix(core): correct sort comparator from ascending to descending
test(cli): add T5 file-not-found exit-code test
docs(contract): freeze v1 error codes
chore(ci): add checkstyle step to verify
```

| type | 说明 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `test` | 测试相关 |
| `docs` | 文档 |
| `refactor` | 重构（不改行为） |
| `chore` | 构建/配置/杂项 |

### 3.3 PR 与 Review 流程

```text
1. 创建分支 → feat/npm-parser
2. 提交代码 → 5-10 commits，每步可回滚
3. 推送 → git push -u origin feat/npm-parser
4. 创建 PR → 标题: feat(npm): implement v1/v2/v3 parser
              描述: 做了什么、怎么测的、需要 Review 什么
5. 指定 Reviewer → A（必选）
6. A 提交 Review → 必须有具体意见（不能只有 "LGTM"）
7. B 回应 → 逐条处理或有依据地拒绝，更新 commits
8. A 复核 → Approve
9. 合并 → Squash Merge，保持 main 历史干净
10. 删除分支 → git branch -d feat/npm-parser
```

**Review 最低要求**：
- 至少一条意见指向**具体行为或边界**（不是代码风格）；
- 示例："parse() 在空 dependencies 时应该返回空 List 而不是 null，T3 测的就是这个"；
- 禁止只有 "Looks good" / "LGTM" / "+1"；
- 契约变更的 PR 必须全组 Review，三人都 Approve 才能合并。

### 3.4 契约变更流程

```text
1. 某人发现需要改接口 → 先改 docs/CONTRACT.md
2. 在 CONTRACT.md 顶部加一条变更记录：
   ### 变更 2026-11-18：RiskLevel 新增 DEPRECATED
   - 原因：需要标记即将废弃的许可证
   - 影响：core 的 Policy 实现需加 default 分支；npm / Go 扩展不受影响
3. 全组 Review CONTRACT.md 变更
4. 三人都同意 → 再改 Java 代码
5. PR 标题前缀 [CONTRACT-CHANGE]
6. 合并后同步更新 DESIGN.docx 对应章节
```

**禁止反向操作**：先改代码再改 CONTRACT.md。一旦发现，PR 打回重写。

---

## 四、时间线与 Gate

### 4.1 阶段与出口标准

| 阶段 | 负责人 | 时间 | 出口标准 |
| --- | --- | --- | --- |
| **P0 契约冻结** | A 起草，全组 Review | Day 1 | CONTRACT.md 三人签字；任何业务代码不得开始 |
| **P1 骨架搭建** | C 主建，A/B 配合 | Day 2 | parent pom + 三模块 + npm 空插件；`mvn -B compile` 全过；Checkstyle 配置就绪；CI workflow 推送 |
| **P2 核心功能** | A + C 并行 | Day 3-4 | MavenManifestParser 能解析 demo-maven → 4 条 Dependency；ScanEngine + PolicyEngine + ReportRenderer 跑通；核心/CLI 26 个测试全绿；故意改错演示准备好 |
| **P3 扩展接入** | B 主写，A 接线 | Day 5 | npm / Go 插件均已实现并合入 master（39 / 40 个契约测试通过）；构建 jar 放入 plugins/ 后 CLI 自动发现；移走全部插件后退出码 4；T7 CLI 集成用例待补；A Review B 的扩展（必须提具体意见） |
| **P4 CLI 完善** | C | Day 5-6 | 参数解析 + 退出码 + 插件加载；四种失败路径退出码正确；终端摘要格式确认 |
| **P5 故意改错演示** | A 准备，三人排练 | Day 6 | 改排序比较器 → T1 红 → 恢复 → 绿；完整流程跑一遍，计时 < 6 分钟 |
| **P6 质量与发布** | C 主理，全组配合 | Day 6-7 | `mvn -B clean verify` 全过；CI 绿色记录；Tag v0.1.0 附注；SHA-256 计算；RELEASE.md 填写 |
| **P7 文档与交付** | 三人各写自己负责的章节 | Day 7 | DESIGN.docx 八章齐全 + 架构图 + 流程图；README 无占位符；成员贡献表填写；ZIP 打包；解压自检 |

### 4.2 并行度说明

```
P0：串行（三人一起 Review 契约）
P1：串行（先建骨架再编译）
P2：A 和 C 可以并行 ← MavenManifestParser（C）和 ScanEngine（A）互不依赖
P3：B 可以独立写 npm / Go 扩展（只依赖 spi，spi 在 P1 已落地）
P4：CLI 完善依赖 P2 的 ScanEngine，但 C 可以先写参数解析框架
P5-P7：基本串行，按质量门禁推进
```

**关键依赖关系**：
```
spi (P1) ← core (P2) ← cli (P4)
spi (P1) ← npm / Go 扩展 (P3) ← 可以和 core 并行
```

---

## 五、约束条款

### 5.1 模块不可触碰声明

| 负责人 | 不得触碰的内容 | 原因 |
| --- | --- | --- |
| A（核心架构） | npm / Go 扩展任何文件；扩展的契约测试 | 独立扩展必须由组内另一位成员从零实现，A 未参与编写才能证明扩展接口"足以支持他人使用" |
| B（npm / Go 扩展） | core 内部任何类；MavenManifestParser；CLI 入口 | 扩展只能依赖 spi，编译期阻断越界 |
| C（Maven + CLI） | npm / Go 扩展任何文件；ScanEngine/PolicyEngine/ReportRenderer | core 的业务逻辑由 A 负责，C 负责解析器和入口 |

### 5.2 禁止事项

| # | 禁止行为 | 原因 | 违反后果 |
| --- | --- | --- | --- |
| 1 | 在 CONTRACT.md 冻结前写业务代码 | 契约是开发边界，没冻结就开写会反复返工 | PR 直接打回，不合并 |
| 2 | 扩展 pom 里添加 core 依赖 | 编译期阻断越界，这是架构铁律 | CI 红，PR 打回 |
| 3 | Review 只写 "LGTM" | 任务书要求"至少一条 Review 指向具体行为/边界" | Review 记录无效，不算贡献 |
| 4 | 先改代码再改 CONTRACT.md | 契约必须先行 | PR 打回重写 |
| 5 | 手动合并而不经过 PR | 任务书要求"问题描述→分支→PR→Review→合并" | 该提交不算贡献，重走流程 |
| 6 | 打完 Tag 后改仓库 | SHA-256 会失效 | Tag 需删除重打，额外记录 |
| 7 | B 修改 npm / Go 扩展以外的任何源码 | 独立扩展的独立性无法验证 | 扩展资格取消，重分模块 |

### 5.3 范围边界（写进 README）

```text
本次实现：Maven 清单解析、npm 清单解析（扩展，已合入）、Go 清单解析（扩展，已合入）、许可证快照匹配、两种策略、Markdown 报告、CLI 入口、退出码、Checkstyle + CI
本次不做：传递依赖分析、联网查询许可证、SBOM 导出、源码扫描、GUI、Gradle 解析器、插件市场、热加载、多版本兼容
结论声明：许可证快照为课程级数据，结论不构成法律意见
```

---

## 六、质量门禁

### 6.1 提交前检查（每人自己跑）

```powershell
# 编译
mvn -B compile

# 跑自己模块的测试
# A 跑 core + cli 测试
mvn -B test -pl licenselens-core,licenselens-cli
# B 跑 npm / Go 扩展测试
mvn -B test -f plugins/licenselens-npm/pom.xml
mvn -B test -f plugins/licenselens-go/pom.xml
# C 跑 cli + Maven 解析器测试
mvn -B test -pl licenselens-core,licenselens-cli

# 风格检查
mvn -B checkstyle:check
```

**任何一项失败不得提交 PR。**

### 6.2 合并前检查（Reviewer 跑）

```powershell
# 全量 verify
mvn -B clean verify

# 跑演示脚本
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-maven/pom.xml
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-maven/pom.xml --policy permissive-only
```

**Reviewer 自己确认通过后才能 Approve。**

### 6.3 发布前检查（全体）

```powershell
git status                    # 必须干净
mvn -B clean verify           # 必须通过
git rev-list -n 1 v0.1.0      # Tag 必须指向 HEAD
# 实跑演示命令（npm / Go 场景需先构建插件 jar 并复制到 plugins/）
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-maven/pom.xml
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-maven/pom.xml --policy permissive-only
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-npm/package-lock.json --plugins plugins
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-go/go.mod --plugins plugins
Get-FileHash release\licenselens-0.1.0.zip -Algorithm SHA256
```

**发布前必须全员签字确认，任何一人有异议则 HOLD。**

---

## 七、AI 使用与核验

每人在 README 的成员贡献表中填写一条：

| 项 | 内容 |
| --- | --- |
| 建议 | AI 给的具体建议内容 |
| 是否采用 | 采用 / 拒绝 |
| 验证方式 | 编译通过？测试通过？手动核对了什么？ |
| 验证结论 | 正确 / 部分正确 / 拒绝理由 |

示例：
> A：建议用 record 承载 Dependency。采用。验证：编译通过；检查 equals/hashCode 行为符合测试预期。结论：减少样板代码，规则清晰。
> B：建议用 org.json 解析 package-lock.json。拒绝。理由：引入第三方依赖，改用 JDK 自带 javax.json。验证：编译通过，解析三种 lock 格式均正确。
> C：建议 MavenManifestParser 用 DOM 解析。采用。验证：解析 4 条样例依赖，字段全部正确提取。

---

## 八、答辩准备分工

| 成员 | 主讲内容 | 时长 |
| --- | --- | --- |
| A | SPI 契约设计 + ScanEngine 调度逻辑 + 故意改错演示（改排序比较器） | 2 分钟 |
| B | npm / Go 扩展怎么写 + 怎么证明扩展独立（pom 只依赖 spi + 核心零改动） + 移走扩展报错演示 | 2 分钟 |
| C | CLI 退出码 + 插件加载（URLClassLoader + ServiceLoader） + 构建/发布流程 + SHA-256 | 2 分钟 |

**每人必须能讲清**：
1. 自己写的模块做了什么、输入输出是什么；
2. 一条完整运行流程（从 CLI 入口到报告输出）；
3. 至少一个其他成员负责的模块/接口（交叉理解）。

---

## 九、变更记录

| 日期 | 变更 | 原因 | 审批 |
| --- | --- | --- | --- |
| 2026-11-17 | v1.0 初始版本 | Gate 0 契约冻结 | A/B/C 全员签字 |
| 2026-11-18 | 对齐 Go 扩展的模块归属、测试、门禁与答辩分工；Checkstyle 纳入 verify | 仓库已有 `plugins/licenselens-go`；L08 原件未提供，先按代码风格约束落最小规则集 | 待全员 Review |
| 2026-11-22 | 按当前 master 同步文档状态：npm / Go 均已合入，快照 4 条，测试 26+39+40=105，T7 待补，命令与样例对齐 | 本地 master 已快进至 origin/master `9c865ea`（含 PR #5 / #6），README/DESIGN/规划/案例/.agent/工作流同步 | 待全员 Review |

---

## 十、成员签字

我已阅读并同意以上分工、约束与协作规则：

| 成员 | 签名 | 日期 |
| --- | --- | --- |
| A | | |
| B | | |
| C | GeneralDXL | 2026-11-17 |

**签字后本文档正式生效。** 任何变更须走 CONTRACT.md 变更流程，三人都同意后才能修改。

# LicenseLens

开源依赖许可证合规审计器。输入项目的依赖清单，输出带许可证风险判定的 Markdown 报告。

- 仓库地址：[https://github.com/Hellocmdd/LicenseLens](https://github.com/Hellocmdd/LicenseLens)（SSH：`git@github.com:Hellocmdd/LicenseLens.git`）
- 系统设计：`docs/DESIGN.docx`（系统设计文档）、`docs/CONTRACT.md`（扩展契约）、`docs/LicenseLens分工合作书.md`（分工与协作规则）
- 项目状态：**核心链路已跑通**。Maven 清单解析、许可证快照匹配、两种策略、Markdown 报告与 CLI 退出码均已实现并有测试覆盖；npm / Go 扩展在独立分支开发，尚未合并，详见"已知限制"。

## 用户、场景与范围

开发者在引入第三方依赖时，需要快速判断许可证是否合规。LicenseLens 的做法是：

```text
依赖清单（pom.xml / package-lock.json / go.mod）
    → 核心调度选择解析器 → 解析为统一依赖列表
    → 查许可证快照 → 策略判定 → 可核对的 Markdown 报告 + 退出码
```

**基础能力**（内置于核心）：Maven `pom.xml` 解析、许可证快照匹配、`campus` 与 `permissive-only` 两种策略、Markdown 报告、CLI 入口与退出码。

**扩展新增能力**：通过独立 jar 接入新的清单格式。仓库已预留 npm `package-lock.json` 与 Go `go.mod` 两个扩展的位置，实现正在独立分支开发，合并后放入 `--plugins` 目录即可启用，核心源码无需改动。

**本次不做**：传递依赖分析、联网查询许可证、SBOM 导出、源码扫描、GUI、Gradle 解析器、插件市场、热加载、多版本兼容。许可证快照为课程级数据，结论不构成法律意见。

**项目起点**：课程提供的 `LicenseLens骨架.zip`（原始压缩包已移除，初始提交保留在 Git 历史中）。本次在其上完成核心链路实现、测试、Checkstyle 与 CI，并补齐样例与本文档。

## 快速开始

环境：JDK 21、Maven 3.9+。Go / Node 仅在需要重新生成 go.mod / package-lock.json 样例时用到，运行项目不需要。

`mvn` 使用的是 `JAVA_HOME` 指向的 JDK：若 `JAVA_HOME` 仍指向 17/11，构建会报"不支持发行版本 21"。先确认 `mvn -v` 输出里的 `Java version` 一行是 21。

以下命令在**项目根目录**执行：

```bash
# 构建核心 reactor（spi / core / cli）
mvn -B clean verify

# 独立构建 npm 扩展（先安装 parent 与 spi）
mvn -B -N install -DskipTests
mvn -B -pl licenselens-spi install -DskipTests
cd plugins/licenselens-npm && mvn -B clean verify && cd ../..
```

Go 扩展同样是独立 reactor：

```bash
cd plugins/licenselens-go && mvn -B clean verify && cd ../..
```

CLI 用法（以下命令在**项目根目录**执行）：

```bash
# 默认 campus 策略：GPL-2.0 依赖被判阻断，退出码 1
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan \
  --manifest samples/demo-maven/pom.xml --out report.md

# 换 permissive-only：弱 copyleft 也被阻断
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan \
  --manifest samples/demo-maven/pom.xml --policy permissive-only --out report.md
```

终端摘要是一行，便于从 CI 日志里定位结果：

```text
策略: campus | 依赖数: 4 | 阻断: 1 | 未知: 0
```

报告写到 `--out` 指定的路径（默认 `./report.md`），其中按风险降序、同风险按名称升序列出每个依赖的许可证、判定与理由。

参数：`--manifest <路径>`（必填）、`--policy campus|permissive-only`（默认 campus）、`--plugins <目录>`（默认 `./plugins`，目录不存在只告警）、`--out <路径>`（默认 `./report.md`，父目录不存在会自动创建）。

退出码：`0` 正常 / `1` 存在阻断依赖 / `2` 清单文件不存在 / `3` 清单语法错误 / `4` 无可用解析器。参数错误（缺值、未知参数、未知策略）当前也返回 `2`——契约退出码表暂无"用法错误"一档。详见 `docs/CONTRACT.md`。

交付物运行方式参见并列 `release/RELEASE.md`（发布阶段创建）。

推送 `v*` 版本的 Tag 会触发 `.github/workflows/release.yml`：在 Ubuntu 与 Windows 双平台上分别构建并打包平台专属发行物——Linux 出 `.tar.gz` + `run.sh`，Windows 出 `.zip` + `run.bat`，两者都含 CLI fat jar、插件 jars、samples 和 `RUN.md`，并各生成同名 `.sha256` 校验文件。两份产物经 Artifact 汇总后，以 Tag 为版本号一次性发布到同一个 GitHub Release（自动生成 Release Notes）。课程要求的 `release/RELEASE.md` 与 ZIP 内最终 SHA-256 仍按交付约定人工维护，不依赖流水线代填。

## 核心与扩展

| 模块                        | 职责                                                                                | 负责人                     |
| --------------------------- | ----------------------------------------------------------------------------------- | -------------------------- |
| `licenselens-spi`         | 公开契约：`DependencySource` / `Policy` / `Decision` / 数据结构，零第三方依赖 | A                          |
| `licenselens-core`        | 扫描调度、策略引擎、报告渲染、许可证快照；内置 Maven 解析器                         | A（Maven 解析器由 C 负责） |
| `licenselens-cli`         | 参数解析、退出码、`URLClassLoader + ServiceLoader` 插件加载                       | C                          |
| `plugins/licenselens-npm` | 独立扩展：npm `package-lock.json` 解析（实现进行中）                                | B                          |
| `plugins/licenselens-go`  | 独立扩展：Go `go.mod` 解析（实现进行中）                                           | B                          |

扩展只依赖 `licenselens-spi`，pom 中不得出现 `licenselens-core`。接入方式：在扩展 jar 的 `META-INF/services/edu.course.licenselens.spi.DependencySource` 中声明实现类全限定名，将 jar 放入 `--plugins` 指定目录即可；禁用则移走 jar。是否需要重启取决于 CLI 调用方式（每次运行都会重新加载插件目录）。核验"核心源码不变"的方法：对比接入前后 `licenselens-core/` 与 `licenselens-cli/` 的 Git 历史，应无改动。

接口契约、注册规则、错误约定与兼容策略见 `docs/CONTRACT.md`。

## 样例

| 路径                                      | 说明                                                       |
| ----------------------------------------- | ---------------------------------------------------------- |
| `samples/demo-maven/pom.xml`            | Maven 基础样例，覆盖多种许可证风险等级                     |
| `samples/demo-npm/package-lock.json`    | 真实 npm 生成的 lockfileVersion 3                          |
| `samples/demo-npm/package-lock.v2.json` | v2 格式变体，用于兼容性测试                                |
| `samples/demo-go/go.mod`                | 真实 `go mod tidy` 产出，直接依赖与 `// indirect` 分块 |
| `samples/demo-go/go.single-require.mod` | 单行 require 与 `/v8` 主版本后缀变体                     |
| `samples/edge/empty.xml`                | 没有 `<dependencies>` 的 Maven 清单（T3 空输入）          |
| `samples/edge/bad-pom.xml`              | 未闭合的 XML，用于 T6 的语法错误与行号断言                 |
| `samples/edge/placeholder-pom.xml`      | 坐标是 `${...}` 占位符，验证原样保留与 UNKNOWN 处理（T4）  |
| `samples/edge/management-pom.xml`       | 只有 `dependencyManagement` 条目，验证不计入依赖总数       |
| `samples/edge/bad-lock.json`            | 未闭合的 JSON，供 npm 扩展的错误路径使用                   |

## 测试与检查

构建即检查：`mvn -B clean verify` 同时执行编译、测试与 Checkstyle（`verify` 阶段绑定 `maven-checkstyle-plugin:check`）。规则集在 `config/checkstyle/checkstyle.xml`，依据项目代码风格硬约束落的最小集合；课程 L08 原始规则集未随仓库提供，拿到后以 L08 为准替换并单独说明。三个 reactor（parent、npm、Go）共用这份规则集，独立插件通过 `../../config/checkstyle/checkstyle.xml` 引用。检测到告警即构建失败，不使用 suppression 绕过。

CI 配置在 `.github/workflows/ci.yml`，push / PR 到 `main` 或 `master` 时触发，在 Ubuntu 与 Windows 双平台（matrix，`fail-fast: false`）上分别构建核心 reactor、安装 parent+spi、独立构建 npm 与 Go 扩展；上述 `verify` 会同时触发各 reactor 的 Checkstyle。本地命令序列见"快速开始"；远端真实运行记录以 GitHub Actions 页面为准。

核心测试已就位：`mvn -B test` 运行 26 个用例。`licenselens-core` 16 个，覆盖 T1 正常流程、T2 策略差异、T3 空清单、T4 占位符坐标、T5 文件不存在、T6 语法错误带行号、T8 无可用解析器；`licenselens-cli` 10 个，覆盖五种退出码、空清单、选项缺值、未知参数、未知策略与插件目录缺失。T7（放入插件生效）与 T9（扩展契约）的集成用例待 npm / Go 扩展合并后补齐。故意改错演示：把报告排序比较器由风险降序改成升序，T1 变红；`git checkout --` 恢复后变绿。

## 协作与贡献入口

问题与改动通过 GitHub Issue / PR 协作。流程：建分支（`feat/*` / `fix/*` / `test/*` / `docs/*`）→ 提交 → 推送 → PR → 指定 Reviewer（B 的扩展由 A 必审）→ 针对具体行为提意见 → 处理后复核 → Squash Merge。契约变更须先改 `CONTRACT.md` 并经三人同意。完整规则见 `docs/LicenseLens分工合作书.md`。

代表性 PR/Review 记录：PR #2（`chore/pre-dev-setup` → `master`，合并 `.agent/`、核心实现与 Checkstyle）已合并；`feat/cli-maven-parser` 的 PR、Review 意见与处理结果在合并后补充。

## 成员贡献与AI核验

| 姓名/学号                 | 本人实质改动及Commit/PR | 审查他人的改动及结果 | 关键决策与AI核验 |
| ------------------------- | ----------------------- | -------------------- | ---------------- |
| zzb/张展博(2024302111213)         | 待填                    | 待填                 | 待填             |
| Hellocmdd/谭博友(2024302111237)   | 待填                    | 待填                 | 待填             |
| GeneralDXL/段绪礼(2024302111046)  | 待填                    | 待填                 | 待填             |

## 许可与已知限制

项目采用 MIT License，理由：课程作业以开放协作为目标，MIT 条款简单、对依赖方限制最少。关键依赖：`org.json`（core 解析 `licenses.json`）、JUnit（测试）与 Maven 插件，均按其各自许可证使用，未在仓库中捆绑第三方二进制；`org.json` 的许可证条款（JSON License 及其历史附加条款）需在发布前核对。

已知限制：npm / Go 两个扩展仍在独立分支开发，尚未合并进 `master`，因此 `--plugins` 目前发现不到外部解析器（扫描 `package-lock.json` / `go.mod` 返回退出码 4 属预期行为）；`licenses.json` 仅收录 4 条常见依赖，未命中按 `UNKNOWN` 处理；参数错误复用退出码 `2`，契约暂无"用法错误"一档；报告含生成时间，两次运行不会字节一致，跨平台路径分隔符也不同；`docs/DESIGN.docx` 已随工程提交，`release/RELEASE.md` 尚未完成。当前版本可对 Maven 清单产出完整审计报告，不覆盖传递依赖分析、联网查询与源码扫描。

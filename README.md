# LicenseLens

开源依赖许可证合规审计器。输入项目的依赖清单，输出带许可证风险判定的 Markdown 报告。

- 仓库地址：[https://github.com/Hellocmdd/LicenseLens](https://github.com/Hellocmdd/LicenseLens)（SSH：`git@github.com:Hellocmdd/LicenseLens.git`）
- 系统设计：`docs/CONTRACT.md`（扩展契约）、`docs/LicenseLens分工合作书.md`（分工与协作规则）
- 项目状态：**骨架阶段**。公开契约、模块结构与构建流程已就绪，业务方法仍为 TODO，详见"已知限制"。

## 用户、场景与范围

开发者在引入第三方依赖时，需要快速判断许可证是否合规。LicenseLens 的做法是：

```text
依赖清单（pom.xml / package-lock.json / go.mod）
    → 核心调度选择解析器 → 解析为统一依赖列表
    → 查许可证快照 → 策略判定 → 可核对的 Markdown 报告 + 退出码
```

**基础能力**（内置于核心）：Maven `pom.xml` 解析、许可证快照匹配、`campus` 与 `permissive-only` 两种策略、Markdown 报告、CLI 入口与退出码。

**扩展新增能力**：通过独立 jar 接入新的清单格式，目前提供 npm `package-lock.json` 与 Go `go.mod` 两个扩展，核心源码无需改动。

**本次不做**：传递依赖分析、联网查询许可证、SBOM 导出、源码扫描、GUI、Gradle 解析器、插件市场、热加载、多版本兼容。许可证快照为课程级数据，结论不构成法律意见。

**项目起点**：课程提供的 `LicenseLens骨架.zip`（原始压缩包已移除，初始提交保留在 Git 历史中）。本次在其上新增 Go 扩展、真实工具生成的样例与本 README。

## 快速开始

环境：JDK 21、Maven 3.9+。Go / Node 仅在需要重新生成 go.mod / package-lock.json 样例时用到，运行项目不需要。

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

CLI 用法（业务方法尚未实现，当前运行会提示骨架待实现，不会产出完整报告）：

```bash
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan \
  --manifest samples/demo-maven/pom.xml
```

参数：`--manifest <路径>`（必填）、`--policy campus|permissive-only`（默认 campus）、`--plugins <目录>`（默认 `./plugins`）、`--out <路径>`（默认 `./report.md`）。

退出码：`0` 正常 / `1` 存在阻断依赖 / `2` 清单文件不存在 / `3` 清单语法错误 / `4` 无可用解析器。详见 `docs/CONTRACT.md`。

交付物运行方式参见并列 `release/RELEASE.md`（发布阶段创建）。

推送 `v*` 版本的 Tag 会触发 `.github/workflows/release.yml`：在 Ubuntu 与 Windows 双平台上分别构建并打包平台专属发行物——Linux 出 `.tar.gz` + `run.sh`，Windows 出 `.zip` + `run.bat`，两者都含 CLI fat jar、插件 jars、samples 和 `RUN.md`，并各生成同名 `.sha256` 校验文件。两份产物经 Artifact 汇总后，以 Tag 为版本号一次性发布到同一个 GitHub Release（自动生成 Release Notes）。课程要求的 `release/RELEASE.md` 与 ZIP 内最终 SHA-256 仍按交付约定人工维护，不依赖流水线代填。

## 核心与扩展

| 模块                        | 职责                                                                                | 负责人                     |
| --------------------------- | ----------------------------------------------------------------------------------- | -------------------------- |
| `licenselens-spi`         | 公开契约：`DependencySource` / `Policy` / `Decision` / 数据结构，零第三方依赖 | A                          |
| `licenselens-core`        | 扫描调度、策略引擎、报告渲染、许可证快照；内置 Maven 解析器                         | A（Maven 解析器由 C 负责） |
| `licenselens-cli`         | 参数解析、退出码、`URLClassLoader + ServiceLoader` 插件加载                       | C                          |
| `plugins/licenselens-npm` | 独立扩展：npm `package-lock.json` 解析                                            | B                          |
| `plugins/licenselens-go`  | 独立扩展：Go `go.mod` 解析                                                        | B                          |

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
| `samples/edge/`                         | 空清单与语法错误边界样例                                   |

## 测试与检查

构建即检查：`mvn -B clean verify` 同时执行编译、测试与 Checkstyle（`verify` 阶段绑定 `maven-checkstyle-plugin:check`）。规则集在 `config/checkstyle/checkstyle.xml`，依据项目代码风格硬约束落的最小集合；课程 L08 原始规则集未随仓库提供，拿到后以 L08 为准替换并单独说明。三个 reactor（parent、npm、Go）共用这份规则集，独立插件通过 `../../config/checkstyle/checkstyle.xml` 引用。检测到告警即构建失败，不使用 suppression 绕过。

CI 配置在 `.github/workflows/ci.yml`，push / PR 到 `main` 或 `master` 时触发，在 Ubuntu 与 Windows 双平台（matrix，`fail-fast: false`）上分别构建核心 reactor、安装 parent+spi、独立构建 npm 与 Go 扩展；上述 `verify` 会同时触发各 reactor 的 Checkstyle。本地命令序列见"快速开始"；远端真实运行记录以 GitHub Actions 页面为准。

当前测试文件尚未编写，计划覆盖：正常流程、策略差异、空输入、非预期输入、文件不存在、语法错误、扩展接入生效与移走、扩展契约。故意改错演示计划中：修改报告排序比较器使测试失败，恢复后通过。

## 协作与贡献入口

问题与改动通过 GitHub Issue / PR 协作。流程：建分支（`feat/*` / `fix/*` / `test/*` / `docs/*`）→ 提交 → 推送 → PR → 指定 Reviewer（B 的扩展由 A 必审）→ 针对具体行为提意见 → 处理后复核 → Squash Merge。契约变更须先改 `CONTRACT.md` 并经三人同意。完整规则见 `docs/LicenseLens分工合作书.md`。

代表性 PR/Review 记录：当前暂无，随开发补充。

## 成员贡献与AI核验

| 姓名/学号                 | 本人实质改动及Commit/PR | 审查他人的改动及结果 | 关键决策与AI核验 |
| ------------------------- | ----------------------- | -------------------- | ---------------- |
| zzb/张展博(2024302111213)         | 待填                    | 待填                 | 待填             |
| Hellocmdd/谭博友(2024302111237)   | 待填                    | 待填                 | 待填             |
| GeneralDXL/段绪礼(2024302111046)  | 待填                    | 待填                 | 待填             |

## 许可与已知限制

项目采用 MIT License，理由：课程作业以开放协作为目标，MIT 条款简单、对依赖方限制最少。关键依赖（JUnit、Maven 插件）按其各自许可证使用，未在仓库中捆绑第三方二进制。

已知限制：核心 `ScanEngine.scan`、Maven/npm/Go 解析器的 `parse`、报告渲染、许可证快照加载仍为 TODO，扫描不会产生完整报告；测试与设计文档（`docs/DESIGN.docx`）尚未完成；发布记录待创建。当前版本适合审查架构、契约与扩展接入方式，不宜作为可运行的合规审计工具使用。

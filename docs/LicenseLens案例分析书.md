# LicenseLens 案例分析书

> 案例类型：课程大作业自拟选题（可扩展开源产品工程）
> 案例定位：以一个"依赖许可证合规审计器"为例，完整走一遍 **需求 → 架构 → 契约 → 测试 → 协作 → 发布** 的设计推演，可直接作为 `docs/DESIGN.docx` 的写作底稿。
> 配套材料：《大作业任务书》《项目选题与参考案例》《项目交付模板与检查清单》《大作业分析指南》。

---

## 1. 案例概述

### 1.1 一句话描述

> 学生开发者在提交课程项目或开源作业前，用一条命令扫描项目的依赖清单，得到一份按风险分级的许可证合规报告，避免无意引入与项目许可证冲突的依赖。

### 1.2 案例背景

课程作业、开源课程项目、毕业设计常常直接复用第三方库。多数学生只关心"能不能用"，不关心"能不能这样用"：

- 项目声明 MIT，却依赖了一个 GPL 库；
- 依赖里混入 AGPL，导致整个作品被要求开源；
- 团队里没人知道依赖的许可证是什么，直到答辩被问到。

商业工具（SCA 平台）通常面向企业、需要联网和账号，课程场景用不上。本案例要做的，是一个**离线、可解释、可扩展**的最小工具。

### 1.3 案例价值

| 维度 | 说明 |
| --- | --- |
| 真实问题 | 许可证冲突是开源协作中的高频问题，不是虚构需求 |
| 可验证 | 输出是文本报告，逐行可断言，不依赖网络与模型 |
| 可扩展 | 新增一个生态（npm / Gradle）只需新增一个解析器，核心零改动 |
| 范围可控 | 不需要数据库、登录、Web、热加载 |

### 1.4 本案例不解决的问题

- 不做传递依赖分析（只看清单里声明的直接依赖）；
- 不联网查询许可证（使用本地快照，保证可复现）；
- 不扫描源码文件里的许可证头；
- 不生成标准 SBOM，不做 SPDX 全量合规；
- 结论不构成法律意见。

---

## 2. 目标用户与使用场景

### 2.1 用户画像

| 用户 | 特征 | 关心什么 |
| --- | --- | --- |
| 学生开发者（主） | 有 1—2 个课程项目，会写 pom.xml，不熟悉许可证 | 我这个项目能不能安全提交/开源 |
| 课程助教（次） | 需要快速抽查作业的依赖合规 | 哪些项目存在高风险依赖 |
| 开源新手（次） | 第一次给项目加依赖 | 这个库能不能用 |

### 2.2 用户故事

**故事一（主路径）**

> 作为学生开发者，我在提交课程项目前运行 `licenselens scan --manifest pom.xml`，希望在 1 秒内看到每个依赖的许可证与风险等级，并知道有没有必须处理的高风险项。

**故事二（策略差异）**

> 作为准备开源的学生，我希望用更严格的策略复查一遍：任何弱 copyleft 也要提示，而不是只拦强 copyleft。

**故事三（新生态）**

> 作为同时写前端的学生，我希望同一个工具也能扫 `package-lock.json`，而不是换一个工具、换一套结论。

### 2.3 核心场景完成条件

一条命令执行后，必须同时满足：

1. 终端打印依赖总数、风险分布、阻断项数量；
2. 生成一份 Markdown 报告，逐条列出依赖、版本、许可证、风险、判定、理由；
3. 存在被策略阻断的依赖时，退出码为 1；
4. 输入错误时给出可读提示（含路径或行号），不打印堆栈。

---

## 3. 需求分析

### 3.1 功能需求

| 编号 | 需求 | 优先级 | 验收方式 |
| --- | --- | --- | --- |
| FR-1 | 解析 Maven `pom.xml`，提取直接依赖的四元组 | 必须 | 样例工程解析出 4 条 |
| FR-2 | 识别依赖的许可证与风险等级 | 必须 | 报告列与快照一致 |
| FR-3 | 按策略给出允许/警告/阻断判定 | 必须 | 两种策略结果不同 |
| FR-4 | 输出 Markdown 报告文件 | 必须 | 文件存在且内容逐行比对 |
| FR-5 | 终端摘要与退出码 | 必须 | 5 种退出码（0/1/2/3/4）分别可复现 |
| FR-6 | 未知许可证标记为 UNKNOWN 并汇总 | 必须 | 虚构 artifact 用例 |
| FR-7 | 支持外部解析器插件（目录加载） | 必须 | 放入 npm 插件后生效 |
| FR-8 | 支持第二种生态（npm） | 必须（扩展） | 扫 package-lock.json 成功 |
| FR-9 | 多清单批量扫描 | 可选 | 未实现则写入限制 |

### 3.2 非功能需求

| 编号 | 需求 | 指标 |
| --- | --- | --- |
| NFR-1 | 离线运行 | 全程无网络请求 |
| NFR-2 | 确定性 | 同一输入两次排序一致；报告含生成时间，字节不完全一致 |
| NFR-3 | 性能 | 200 条依赖扫描 < 2 秒 |
| NFR-4 | 环境 | JDK 21，命令行可运行，不依赖 IDE |
| NFR-5 | 可诊断 | 错误含文件路径；解析错误含行号 |
| NFR-6 | 可扩展 | 新增生态不改核心源码 |

### 3.3 范围边界（写进 README）

```text
本次实现：Maven 清单解析、许可证快照匹配、两种策略、Markdown 报告、npm 扩展（已合入）、Go 扩展（已合入）
本次不做：传递依赖、联网查询、SBOM 导出、源码扫描、GUI、插件市场、热加载
```

---

## 4. 同类工具与本案例的差异

| 类型 | 典型形态 | 本案例的取舍 |
| --- | --- | --- |
| 企业 SCA 平台 | 联网、账号、数据库、Web 报告 | 完全不要，改为离线 CLI |
| 构建插件 | 绑定 Maven/Gradle 生命周期 | 独立 CLI，可对任意目录运行 |
| 通用 SPDX 工具 | 追求规范完整 | 只做课程级判断，明确不追求法律准确性 |

**定位差异一句话**：商业工具回答"企业是否合规"，本案例回答"这个课程项目能不能安全提交"。

---

## 5. 总体架构

### 5.1 分层与依赖方向

```text
┌─────────────────────────────────────────────┐
│  licenselens-cli                             │
│  参数解析 / 插件目录加载 / 退出码 / 终端输出   │
└───────────────┬─────────────────────────────┘
                │ 依赖
┌───────────────▼─────────────────────────────┐
│  licenselens-core                            │
│  扫描调度 / 许可证匹配 / 策略引擎 / 报告渲染   │
│  内置 Maven 解析器                            │
└───────────────┬─────────────────────────────┘
                │ 只依赖（单向）
┌───────────────▼─────────────────────────────┐
│  licenselens-spi                             │
│  Dependency / DependencySource / LicenseInfo │
│  Policy / Decision / SourceException         │
└───────────────▲─────────────────────────────┘
                │ 只依赖
┌───────────────┴─────────────────────────────┐
│  plugins/licenselens-npm（独立扩展）          │
│  解析 package-lock.json → List<Dependency>   │
└─────────────────────────────────────────────┘
```

**依赖方向铁律**：`spi ← core ← cli`，`spi ← npm 插件`。扩展与核心之间**没有**依赖边，靠 SPI 声明在运行时连接。

### 5.2 目录结构

```text
licenselens/
├─ pom.xml                      # parent，packaging=pom
├─ licenselens-spi/             # 契约，无第三方依赖
├─ licenselens-core/            # 核心实现 + Maven 解析器 + licenses.json
├─ licenselens-cli/             # 入口，打可执行 jar
├─ plugins/
│  ├─ licenselens-npm/          # 独立扩展：v1/v2/v3 已实现，不属于 parent reactor
│  └─ licenselens-go/           # 独立扩展：go.mod 解析已实现，不属于 parent reactor
├─ samples/
│  ├─ demo-maven/pom.xml
│  ├─ demo-npm/package-lock.json
│  ├─ demo-npm/package-lock.v1.json
│  ├─ demo-npm/package-lock.v2.json
│  ├─ demo-go/go.mod
│  ├─ edge/empty.xml
│  └─ edge/bad-lock.json
├─ docs/
│  ├─ CONTRACT.md
│  └─ DESIGN.docx
└─ .github/workflows/ci.yml
```

### 5.3 为什么这样切

| 决策 | 理由 |
| --- | --- |
| 契约单独成模块 | 扩展只依赖它，编译期就阻止越界引用核心 |
| 内置 Maven 解析器放在 core | 它是基础能力，不要求做成插件 |
| 插件目录与 reactor 分离 | 扩展可独立构建、独立发布，验证"核心不改" |
| CLI 与 core 分离 | CLI 只做参数与输出，便于对 core 做单元测试 |

---

## 6. 扩展机制设计（本案例的核心考点）

### 6.1 契约定义

```java
package edu.course.licenselens.spi;

public interface DependencySource {
    String id();                          // "maven" / "npm"
    boolean supports(ManifestRef ref);    // 依据文件名与首行特征判断
    List<Dependency> parse(Path file) throws SourceException;
}

public record Dependency(
        String ecosystem,   // maven | npm
        String name,        // groupId:artifactId 或包名
        String version,
        String scope) {}

public record LicenseInfo(String spdxId, String name, RiskLevel risk) {}

public enum RiskLevel { PERMISSIVE, WEAK_COPYLEFT, STRONG_COPYLEFT, UNKNOWN }

public interface Policy {
    String id();
    Decision evaluate(LicenseInfo license, Dependency dep);
}

public record Decision(boolean allowed, boolean warning, String reason) {}

public final class SourceException extends Exception {
    public enum Code { FILE_NOT_FOUND, PARSE_ERROR, UNSUPPORTED_FORMAT }
    public Code code();
    public int line();
}
```

### 6.2 注册与发现

- 扩展在 `META-INF/services/edu.course.licenselens.spi.DependencySource` 中声明实现类；
- CLI 启动时用独立 `URLClassLoader` 加载 `--plugins` 目录下所有 jar，再 `ServiceLoader.load(DependencySource.class, loader)`；
- 选择顺序：按注入顺序取第一个 `supports()` 返回 true 的解析器（内置 Maven 在前，插件按目录项顺序）；冻结契约仍写 `id()` 字典序，待按变更流程对齐；
- 无法识别的 jar 跳过并告警，不中断扫描。

### 6.3 错误契约（写入 CONTRACT.md 并被测试覆盖）

| 场景 | 抛出 | 核心处理 | 退出码 |
| --- | --- | --- | --- |
| 文件不存在 | `FILE_NOT_FOUND` | 打印路径，终止 | 2 |
| XML/JSON 语法错误 | `PARSE_ERROR`（含行号） | 打印行号，终止 | 3 |
| 无解析器支持该文件 | 无 | 提示安装插件 | 4 |
| 插件类加载失败 | 运行时异常 | 记录插件名，跳过该插件 | 继续；若因此无可用解析器仍为 4 |
| 依赖未命中快照 | 无 | 标记 UNKNOWN，计入汇总 | 0 |

### 6.4 兼容策略

| 变更类型 | 处理 |
| --- | --- |
| 契约新增**默认方法** | 兼容，旧插件无需改动 |
| 新增 `Dependency` 字段 | 不兼容（record 构造变化），需升主版本并公告 |
| 新增 `RiskLevel` 枚举值 | 兼容，但策略实现需给出默认分支 |
| 删除方法 | 不兼容，升主版本 |

声明：本课程版本仅保证 0.1.x 内部兼容，不承诺跨大版本兼容。

---

## 7. 关键业务流程

### 7.1 主流程

```text
用户 → CLI: licenselens scan --manifest pom.xml --policy campus
CLI  → 解析参数，校验路径
CLI  → 加载插件目录，收集 DependencySource
CLI  → core.scan(file)
core → 选择 supports() 为 true 的解析器
core → parser.parse(file) → List<Dependency>
core → 逐条查 licenses.json → LicenseInfo
core → policy.evaluate(...) → Decision
core → 排序（风险降序，名称升序）→ 渲染报告
core → 返回 ScanResult（含统计与阻断数）
CLI  → 写 report.md，打印摘要，按结果设置退出码
```

### 7.2 失败分支（必须演示其中两条）

```text
分支 A：文件不存在
  CLI 校验路径失败 → 打印 "清单文件不存在: <路径>" → 退出码 2

分支 B：XML 语法错误
  解析器抛 PARSE_ERROR(line=15) → 打印 "第 15 行附近格式错误" → 退出码 3

分支 C：没有可用解析器
  所有 supports() 返回 false → 打印 "未找到可解析该文件的插件，请安装 npm 解析器" → 退出码 4

分支 D：插件损坏
  加载 jar 抛异常 → 打印 "插件加载失败: xxx.jar（已跳过）" → 继续扫描 → 退出码 0
```

### 7.3 时序要点

| 步骤 | 关键约束 |
| --- | --- |
| 插件发现 | 必须使用独立类加载器，避免污染应用 classpath |
| 解析 | 解析器只负责"文件 → 依赖列表"，不做许可证判断 |
| 匹配 | 匹配逻辑只在 core，确保不同生态结论一致 |
| 渲染 | 排序稳定，保证同一输入输出排序一致；报告含生成时间，字节不完全一致 |

---

## 8. 数据设计

### 8.1 许可证快照格式（`licenses.json`）

```json
{
  "org.apache.commons:commons-lang3": {
    "spdxId": "Apache-2.0",
    "name": "Apache License 2.0",
    "risk": "PERMISSIVE"
  },
  "com.google.guava:guava": {
    "spdxId": "Apache-2.0",
    "name": "Apache License 2.0",
    "risk": "PERMISSIVE"
  },
  "org.junit.jupiter:junit-jupiter": {
    "spdxId": "EPL-2.0",
    "name": "Eclipse Public License 2.0",
    "risk": "WEAK_COPYLEFT"
  },
  "mysql:mysql-connector-java": {
    "spdxId": "GPL-2.0",
    "name": "GNU General Public License v2.0",
    "risk": "STRONG_COPYLEFT",
    "note": "含 FOSS 例外条款，课程场景按强 copyleft 提示"
  }
}
```

匹配规则：精确匹配 `groupId:artifactId`；未命中即 `UNKNOWN`，不猜测。

### 8.2 策略定义

| 策略 ID | PERMISSIVE | WEAK_COPYLEFT | STRONG_COPYLEFT | UNKNOWN |
| --- | --- | --- | --- | --- |
| `campus` | 允许 | 允许（提示） | 阻断 | 警告 |
| `permissive-only` | 允许 | 阻断 | 阻断 | 阻断 |

### 8.3 报告格式（`report.md`）

```markdown
# 许可证合规报告

- 清单：samples/demo-maven/pom.xml
- 策略：campus
- 依赖数：4
- 阻断：1

| 依赖 | 版本 | 许可证 | 风险 | 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| mysql:mysql-connector-java | 8.0.33 | GPL-2.0 | 强 copyleft | 阻断 | 与 MIT 项目不兼容 |
| org.junit.jupiter:junit-jupiter | 5.10.2 | EPL-2.0 | 弱 copyleft | 允许 | 测试范围可接受 |
| com.google.guava:guava | 33.0.0-jre | Apache-2.0 | 宽松 | 允许 | 无附加义务 |
| org.apache.commons:commons-lang3 | 3.14.0 | Apache-2.0 | 宽松 | 允许 | 无附加义务 |
```

### 8.4 配置项

| 参数 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `--manifest` | 是 | 无 | 清单文件路径 |
| `--policy` | 否 | `campus` | 策略 ID |
| `--plugins` | 否 | `./plugins` | 插件目录 |
| `--out` | 否 | `./report.md` | 报告输出路径 |

不涉及任何密钥；如需扩展外部数据源，通过环境变量约定，不写入仓库。

---

## 9. 关键设计决策与取舍

### 决策一：本地许可证快照 vs 在线查询

| 方案 | 优点 | 代价 |
| --- | --- | --- |
| 本地快照（采用） | 离线、可复现、无速率限制 | 数据会过期，需人工维护 |
| 在线 API | 数据新 | 依赖网络与第三方服务，不可复现 |

**结论**：课程场景优先可复现。缓解措施：快照文件版本化，允许 `--catalog` 指定外部映射覆盖。

### 决策二：只解析声明层 vs 解析传递依赖

| 方案 | 优点 | 代价 |
| --- | --- | --- |
| 声明层（采用） | 实现简单，结论可解释 | 漏掉间接引入的高风险依赖 |
| 传递依赖 | 更完整 | 需要解析依赖树、处理冲突与版本仲裁，超出课程范围 |

**结论**：明确写入限制，避免"看起来完整但不准确"。

### 决策三：ServiceLoader + 独立目录 vs 编译期注册

| 方案 | 优点 | 代价 |
| --- | --- | --- |
| 独立目录 + SPI（采用） | 插件可单独发布、单独禁用，核心无需重打包 | 需要类加载器隔离，发现失败要给出明确提示 |
| 编译期注册 | 简单 | 新生态必须改核心，扩展不成立 |

**结论**：扩展能力是作业核心要求，必须选前者；用退出码 4 和插件加载告警弥补可诊断性。

### 决策四：文本报告 vs 结构化输出

**结论**：只做 Markdown（可读性优先）。JSON 输出列为后续演进，不进入本次范围，避免范围膨胀。

---

## 10. 测试与质量方案

### 10.1 测试矩阵

| 编号 | 类型 | 输入 | 断言 | 覆盖 |
| --- | --- | --- | --- | --- |
| T1 | 正常 | `samples/demo-maven/pom.xml` | 报告 4 行；顺序为 GPL → EPL → Apache | 主流程 + 排序 |
| T2 | 正常 | 同上 + `--policy permissive-only` | EPL 行判定为阻断，退出码 1 | 策略差异 |
| T3 | 边界 | `samples/edge/empty.xml`（无 dependencies） | 0 行，退出码 0，摘要显示“依赖数: 0” | 空输入 |
| T4 | 边界 | 依赖名含 `$` 占位符 | 保留原样并标记 UNKNOWN | 非预期输入 |
| T5 | 失败 | 不存在的路径 | 退出码 2，提示含路径 | 错误反馈 |
| T6 | 失败 | 损坏的 XML（`samples/edge/bad-pom.xml`） | 退出码 3，提示含行号（实测第 15 行附近） | 错误定位 |
| T7 | 扩展 | 放入已构建的 npm 插件 jar，扫 `package-lock.json` | 报告含 npm 依赖行（如 `lodash:4.17.21`）；CLI 级集成用例待补 | 扩展接入（计划） |
| T8 | 扩展 | 移走 npm 插件后扫同一文件 | 退出码 4，提示安装插件 | 降级行为 |
| T9 | 扩展 | npm 39 例 + Go 40 例（不依赖 core） | 解析结果字段、错误码与发现规则正确 | 契约稳定性（两个扩展均已覆盖） |

### 10.2 故意改错演示

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 将报告排序比较器由降序改升序 | T1 红 |
| 2 | 运行 `mvn -B test` | 失败信息指向顺序断言 |
| 3 | `git checkout -- <file>` 恢复 | T1 绿 |

### 10.3 质量控制

| 项 | 选择 |
| --- | --- |
| 构建 | `mvn -B clean verify` |
| 基础检查 | Checkstyle（`mvn checkstyle:check`） |
| CI | GitHub Actions，push 触发 `mvn -B verify`，保留一次成功记录 |
| 覆盖率 | 不强制，不做硬门槛 |

---

## 11. 项目治理与协作

### 11.1 分工

| 成员 | 角色 | 负责 | 不得触碰 |
| --- | --- | --- | --- |
| A | 核心 | spi + core（调度、策略、报告、licenses.json） | npm 插件实现 |
| B | 扩展 | `plugins/licenselens-npm` 及其测试 | core 内部代码 |
| C | 配套 | Maven 解析器、CLI、集成测试、CI、发布 | npm 插件实现 |

### 11.2 协作规则

| 项 | 规则 |
| --- | --- |
| 分支 | `feat/*`、`fix/*`、`test/*` |
| 提交 | `feat:` / `fix:` / `test:` / `docs:` / `chore:` |
| 合并 | 至少 1 名非作者审查通过 |
| 契约变更 | 先改 `docs/CONTRACT.md`，全组确认后再改代码 |
| 审查要求 | 必须指出具体行为、边界或测试缺口 |

### 11.3 AI 使用与核验

每人记录一条：建议内容 → 是否采用 → 如何验证 → 结论。示例：

> 建议：用 `record` 承载 Dependency。
> 采用。验证：编译通过；检查 `equals/hashCode` 行为符合测试预期。
> 结论：减少样板代码，规则清晰。

---

## 12. 发布与交付

### 12.1 版本与制品

| 项 | 内容 |
| --- | --- |
| 版本 | 0.1.0（首个可用版本） |
| 制品 | `licenselens-0.1.0.zip`：cli jar + npm 插件 jar + samples + 启动脚本 |
| 环境 | JDK 21，无需 IDE |
| 运行 | `java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-maven/pom.xml`（发布版以 `RELEASE.md` 中记录的产物名为准） |

### 12.2 发布步骤

```bash
git status                    # 干净
mvn -B clean verify           # 通过
git tag -a v0.1.0 -m "Release 0.1.0"
git rev-list -n 1 v0.1.0      # 与 HEAD 一致
java -jar ... scan ...        # 实跑三条场景
Get-FileHash release\licenselens-0.1.0.zip -Algorithm SHA256
```

`release/RELEASE.md` 记录：版本、最终 Commit、Tag 目标、构建与 CI 状态、运行结果、SHA-256、已知限制、RELEASE/HOLD 决定。

### 12.3 提交结构

```text
组号_LicenseLens.zip
└─ 组号_LicenseLens/
   ├─ project/        # 含 .git、源码、测试、CI、README、LICENSE、docs/DESIGN.docx
   └─ release/        # 交付 ZIP + RELEASE.md
```

---

## 13. 风险清单与应对

| 风险 | 影响 | 概率 | 应对 |
| --- | --- | --- | --- |
| 契约不稳定，扩展反复返工 | 高 | 中 | 先冻结 CONTRACT.md，再并行开发 |
| 许可证快照数据偏差被质疑 | 中 | 中 | README 声明非法律意见，快照可替换 |
| Maven 属性占位符解析不全 | 中 | 高 | 明确只支持直接声明，占位符标记 UNKNOWN |
| npm lock 文件格式多版本 | 中 | 高 | 扩展内做格式嗅探，三种结构各一个用例 |
| 插件类加载失败难排查 | 中 | 中 | 打印插件名与异常类型，不吞异常 |
| 范围膨胀（想加 Web/数据库） | 高 | 中 | 范围边界写进 README，评审时拒绝 |
| 发布后补记录导致 SHA 失效 | 中 | 中 | RELEASE.md 放仓库外 |

---

## 14. 与任务书七项指标的对齐

| 任务书要求 | 本案例的落点 | 证据 |
| --- | --- | --- |
| 一个核心场景 | `scan` 一条命令 → 报告 + 退出码 | 演示脚本 |
| 一种扩展机制 + 一个独立扩展 | `DependencySource` SPI + npm 插件 | 插件 jar + `git log` 证明核心未改 |
| 有效测试 | 9 个用例覆盖三类行为 | 测试报告 |
| 基础质量控制 | Checkstyle + CI | CI 运行记录 |
| 真实协作 | 每人一个 PR + 一次实质 Review | README 协作表 |
| 可追溯发布 | 附注 Tag + SHA-256 | RELEASE.md |
| 设计文档 | 本文档 + 架构图/流程图 | DESIGN.docx |

---

## 15. 演示脚本（约 6 分钟）

| 步骤 | 命令 | 讲解要点 |
| --- | --- | --- |
| 1 | `licenselens scan --manifest samples/demo-maven/pom.xml` | 展示 4 行报告与风险排序 |
| 2 | 加 `--policy permissive-only` | 同一输入判定变化，退出码 1 |
| 3 | 先构建 npm 插件 jar 放入 `plugins/`，再扫 `package-lock.json` | 新生态生效，核心零改动 |
| 4 | 移走插件后重跑 | 退出码 4，提示安装解析器 |
| 5 | 把 `samples/edge/bad-lock.json` 复制为 `package-lock.json` 后扫描 | 退出码 3，提示第 8 行附近；原名返回 4 |
| 6 | 改一行排序代码 → 测试红 → 恢复 → 绿 | 证明测试有效 |

---

## 16. 答辩问题库

| 问题 | 回答要点 |
| --- | --- |
| 许可证数据准确吗？ | 课程级快照当前 4 条 Maven 坐标（规划 40 条未达成）；未知一律 UNKNOWN；README 声明非法律意见；快照可替换 |
| 这跟依赖检查工具有什么区别？ | 统一模型 + 可插拔生态适配；新增生态只写一个类，核心零改动 |
| 为什么不做传递依赖？ | 需要依赖树与版本仲裁，超出课程范围；明确声明限制优于不准确 |
| 怎么证明扩展独立？ | 扩展 pom 只依赖 spi；开发期间作者未读 core；`git log` 显示接入无核心提交 |
| 插件失败会怎样？ | 加载失败跳过并告警；若因此无可用解析器仍返回 4；清单语法错误返回 3 |
| 为什么用独立类加载器？ | 避免插件依赖污染宿主 classpath，支持插件单独替换 |
| 契约变更怎么处理？ | 记录在 CONTRACT.md，新增默认方法兼容；字段变更升主版本并公告 |
| 报告为什么排序？ | 保证同一输入输出排序一致，便于测试与 diff；报告含生成时间，字节不完全一致 |
| 覆盖率多少？ | 不追求数字；以三类行为的断言覆盖为准，故意改错能被测试发现 |
| 你们的已知限制？ | 声明层、离线快照（4 条 Maven 坐标）、两个扩展都需构建 jar 才能被 CLI 发现、T7 集成测试待补、无 SBOM、非法律意见 |

---

## 17. 后续演进

| 阶段 | 方向 | 前置条件 |
| --- | --- | --- |
| v0.2 | 增加 Gradle 解析器 | 契约无需变更 |
| v0.3 | 支持传递依赖（解析依赖树） | 需要新的解析能力与缓存策略 |
| v0.4 | JSON 结构化输出 | 报告模型抽出 |
| v1.0 | 可替换许可证目录（外置 catalog） | 明确数据版本与校验方式 |

---

## 18. 附：样例输入

`samples/demo-maven/pom.xml`（节选）

```xml
<project>
  <dependencies>
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-lang3</artifactId>
      <version>3.14.0</version>
    </dependency>
    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>33.0.0-jre</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>mysql</groupId>
      <artifactId>mysql-connector-java</artifactId>
      <version>8.0.33</version>
    </dependency>
  </dependencies>
</project>
```

预期结果：4 条依赖，1 条阻断（GPL），2 条宽松，1 条弱 copyleft；`campus` 策略下退出码 1。

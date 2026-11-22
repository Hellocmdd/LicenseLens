# LicenseLens 大作业：方向分析与实施规划

> 选题：开源依赖许可证合规审计器（课程场景专用）
> 团队：3 人 | 交付：一个 ZIP（含工程 + release + 设计文档）
> 本文档解决两件事：**方向怎么收**、**接下来按什么顺序做**。
> 配套材料：《LicenseLens案例分析书》《大作业任务书》《项目交付模板与检查清单》

---

## 一、方向定位

### 1.1 一句话产品定义

> 学生开发者在提交课程项目或开源作业前，用一条命令扫描项目的依赖清单，得到一份按风险分级的许可证合规报告，避免无意引入与项目许可证冲突的依赖。

### 1.2 产品名与形态

- 名称：**LicenseLens**
- 形态：命令行工具（Java 21 + Maven），离线运行，可重复
- 一条命令：`licenselens scan --manifest pom.xml --policy campus`

### 1.3 为什么是这个方向

附录 A 把 LicenseLens 列为推荐方案不是偶然——它在任务书框架内是**规格最成熟、风险最低、演示最容易**的选题：

| 对比维度 | LicenseLens | 其他选题 |
| --- | --- | --- |
| 交付稳定性 | **高**：Java/Maven 全栈熟悉，无工具链门槛 | VexRiscv 需过 Scala/Verilator；OpenConvert/LogInsight 虽稳但规格不如 LicenseLens 完整 |
| 扩展机制真实性 | **高**：ServiceLoader SPI，标准 Java 做法 | VexRiscv V2 自建契约；SeatMap/TaskGraph 扩展语义偏弱 |
| 测试可演示性 | **高**：毫秒级，改一行比较器即红，恢复即绿 | VexRiscv 反馈分钟级；SeatMap 需随机种子保证稳定 |
| 协作结构 | **高**：spi/core/cli/plugins 四模块自然切分 | VexRiscv 工具链一人熟悉易形成瓶颈 |
| 答辩素材 | 高：许可证话题贴合课程主题（开源治理） | 各有千秋，打平 |
| 环境风险 | **低**：JDK 21 + Maven 全搞定 | VexRiscv Windows 装 Verilator 麻烦 |

**结论**：如果目标是"一次交付成功、答辩有据可讲"，LicenseLens 是当前最优选择。

---

## 二、核心场景设计

### 2.1 用户旅程（四步）

```text
1. licenselens scan --manifest samples/demo-maven/pom.xml
   → 解析清单 → 匹配许可证快照 → 应用策略 → 生成 report.md
   → 终端打印摘要（依赖数、风险分布、阻断数）
   → 退出码：有阻断=1，否则=0

2. licenselens scan --manifest samples/demo-maven/pom.xml --policy permissive-only
   → 同一输入，更严格策略
   → EPL-2.0 从"允许"变"阻断"
   → 退出码：1

3. 放入 npm 插件后：
   licenselens scan --manifest samples/demo-npm/package-lock.json --plugins plugins
   → 新生态自动生效，report.md 含 npm 依赖行（如 lodash:4.17.21）
   → 核心零改动

4. 故意给一个坏 JSON（npm 插件只认文件名 package-lock.json）：
   cp samples/edge/bad-lock.json /tmp/package-lock.json
   licenselens scan --manifest /tmp/package-lock.json --plugins plugins
   → 退出码 3，提示第 8 行附近格式错误；若直接用 bad-lock.json 则因无解析器返回 4
```

### 2.2 输入与输出

| 项 | 内容 |
| --- | --- |
| 输入 | `pom.xml` 或 `package-lock.json`（通过 `supports()` 自动识别） |
| 参数 | `--manifest`（必填）、`--policy`（默认 campus）、`--plugins`（默认 ./plugins）、`--out`（默认 ./report.md） |
| 中间产物 | 无（纯内存处理，确定性输出） |
| 输出 | `report.md` + 终端摘要 + 退出码 |
| 耗时 | 未实测，不设为验收指标 |

### 2.3 核心能力具体化（三条铁规则）

| 规则 | 说明 | 失败示例 |
| --- | --- | --- |
| R1 策略判定 | campus 策略：STRONG_COPYLEFT 阻断，WEAK_COPYLEFT 警告；permissive-only 策略只允许 PERMISSIVE | GPL 依赖 + campus → 阻断；EPL 依赖 + permissive-only → 阻断 |
| R2 许可证匹配 | 精确匹配 groupId:artifactId；未命中一律 UNKNOWN，不猜测 | 虚构 artifact → 标记 UNKNOWN，计入汇总 |
| R3 确定性排序 | 报告按风险降序（STRONG → WEAK → PERMISSIVE → UNKNOWN），同风险内名称升序 | 两次运行同一输入排序一致；报告含生成时间，字节不完全一致 |

**这三条规则是本项目"核心能力"的实体**，不是空壳。每条规则都要有对应用例验证。

---

## 三、扩展机制设计

### 3.1 契约

```java
package edu.course.licenselens.spi;

public interface DependencySource {
    String id();                          // "maven" / "npm"
    boolean supports(ManifestRef ref);    // 依据文件名与首行特征判断
    List<Dependency> parse(Path file) throws SourceException;
}

public record Dependency(
        String ecosystem,   // "maven" | "npm"
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

### 3.2 注册与发现

- 扩展在 `META-INF/services/edu.course.licenselens.spi.DependencySource` 中声明实现类；
- CLI 用独立 `URLClassLoader` 加载 `--plugins` 目录下所有 jar；
- 选择顺序：按注入顺序取第一个 `supports()` 返回 true 的解析器（内置 Maven 在前，插件按目录项顺序）；冻结契约仍写 `id()` 字典序，待按变更流程对齐；
- 无法识别的 jar 跳过并告警，不中断扫描。

### 3.3 错误契约

| 场景 | 抛出/行为 | 核心处理 | 退出码 |
| --- | --- | --- | --- |
| 文件不存在 | `FILE_NOT_FOUND` | 打印路径，终止 | 2 |
| XML/JSON 语法错误 | `PARSE_ERROR`（含行号） | 打印行号，终止 | 3 |
| 无解析器支持该文件 | 无异常 | 提示安装插件 | 4 |
| 插件类加载失败 | 运行时异常 | 记录插件名，跳过该插件 | 继续；若因此无可用解析器仍为 4 |
| 依赖未命中快照 | 无异常 | 标记 UNKNOWN，计入汇总 | 0 |
| 存在阻断依赖 | 无异常 | 报告含阻断行，退出码 1 | 1 |

### 3.4 兼容策略

| 变更类型 | 处理 |
| --- | --- |
| 契约新增**默认方法** | 兼容，旧插件无需改动 |
| 新增 `Dependency` 字段 | 不兼容（record 构造变化），需升主版本并公告 |
| 新增 `RiskLevel` 枚举值 | 兼容，但策略实现需给出默认分支 |
| 删除方法 | 不兼容，升主版本 |

声明：本课程版本仅保证 0.1.x 内部兼容，不承诺跨大版本兼容。

---

## 四、系统结构

```text
┌─────────────────────────────────────────────┐
│  licenselens-cli                             │
│  参数解析 / 插件目录加载 / 退出码 / 终端输出   │
└───────────────┬─────────────────────────────┘
                │ 依赖
┌───────────────▼─────────────────────────────┐
│  licenselens-core                            │
│  扫描调度 / 许可证匹配 / 策略引擎 / 报告渲染   │
│  内置 Maven 解析器 + licenses.json 快照       │
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

目录结构：

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
│  ├─ edge/bad-pom.xml
│  ├─ edge/bad-lock.json
│  └─ edge/bad-go.mod
├─ docs/
│  ├─ CONTRACT.md
│  └─ DESIGN.docx
└─ .github/workflows/ci.yml
```

---

## 五、Gate：先冻结契约，再并行开发

### Gate 0：契约冻结（Day 1 必须完成）

| 检查 | 通过标准 |
| --- | --- |
| `docs/CONTRACT.md` 写完 | 一页纸：接口签名 + 错误码表 + 兼容策略 |
| 全组 Review 签名 | 每人至少一条意见或"同意" |
| 三张表一致 | 退出码表、错误契约表、兼容策略表三处措辞完全一致 |

**Gate 0 不通过，任何人不得写业务代码。** 契约冻结后，A/B/C 可以三向并行，互不阻塞。

### Gate 1：骨架编译（Day 2）

- Maven parent pom 能解析；三模块各自 `mvn compile` 过
- npm 插件 pom 只依赖 spi，编译能过（空实现，后续填充）
- CLI 入口能打印帮助

### Gate 2：最小闭环（Day 3）

- MavenManifestParser 能解析 demo-maven/pom.xml → 4 条 Dependency
- ScanEngine 能跑通 → 生成 report.md
- campus 策略下 mysql-connector-java 判定为阻断，退出码 1

### Gate 3：扩展接入（Day 4-5）

- npm 与 Go 插件均已实现并合入 `master`，分别通过 39 / 40 个契约测试
- 先独立构建插件 jar，再放入 plugins/ 目录后 CLI 自动发现
- 移除插件后扫同一文件 → 退出码 4，提示安装

### Gate 4：故意改错演示（Day 6）

- 把报告排序比较器由降序改升序 → 跑测试变红
- `git checkout -- <file>` 恢复 → 测试变绿
- 完整演示一次

---

## 六、三人分工

| 成员 | 负责 | 交付证据 | 审查对象 |
| --- | --- | --- | --- |
| A | spi + core 核心 | `DependencySource`/`Policy` 接口、`licenses.json` 快照、`ScanEngine` 调度、`PolicyEngine`、`ReportRenderer`、三类测试（正常/边界/失败） | B 的扩展实现 |
| B | npm 独立扩展 | `plugins/licenselens-npm`：解析 `package-lock.json` → `List<Dependency>`、契约测试（不依赖 core） | A 或 C |
| C | Maven 解析器 + CLI + 发布 | `core/internal/MavenManifestParser`、`licenselens-cli` 入口、参数解析、退出码、集成测试、GitHub Actions CI、发布流程 | A 或 B |

**约束**：

- B 的扩展 pom 只依赖 spi，编译期就阻断越界引用 core；
- 契约变更必须先改 `docs/CONTRACT.md` 并全组确认，任何人不得直接改接口；
- 每人至少一次实质 Review，意见必须指向具体行为或边界（不能只有 "LGTM"）；
- 独立扩展（B 写的）不得被 A 或 C 修改——这是"扩展独立性"的直接证据。

---

## 七、实施阶段（按出口标准推进，不按天数）

| 阶段 | 内容 | 出口标准 |
| --- | --- | --- |
| P0 | Gate 0：契约冻结 | CONTRACT.md 全组签字，任何代码不得开始 |
| P1 | 骨架搭建 | parent pom + 三模块 + npm 空插件 + Checkstyle + CI；`mvn -B compile` 全过 |
| P2 | 核心功能 | Maven 解析 + 扫描引擎 + 策略引擎 + 报告渲染；demo-maven 闭环跑通；26 个核心/CLI 测试全绿（扩展另计 npm 39 + Go 40 个） |
| P3 | 扩展接入 | npm 插件实现 + 契约测试；放入插件生效，移走报错；核心零新增提交 |
| P4 | CLI 完善 | 参数解析 + 退出码 + 插件目录加载；四种策略切换验证 |
| P5 | 故意改错演示 | 排序改升降序 → T1 红 → 恢复 → T1 绿；完整演示一次 |
| P6 | 质量与发布 | `mvn -B clean verify` 全过；CI 绿色记录；附注 Tag v0.1.0；SHA-256 |
| P7 | 文档 | DESIGN.docx 八章齐全；README 无占位符；RELEASE.md 完整 |

**关键顺序约束**：

1. P0 完成前不启动任何其他阶段；
2. P2 完成前不启动 P3（否则契约可能还要变）；
3. P6 之前不做任何"美化"工作；
4. 打完 Tag 后不再改仓库。

---

## 八、测试方案

| 编号 | 类型 | 输入 | 断言 | 覆盖 |
| --- | --- | --- | --- | --- |
| T1 | 正常 | `samples/demo-maven/pom.xml` | 报告 4 行；顺序为 GPL → EPL → Apache | 主流程 + 排序 |
| T2 | 正常 | 同上 + `--policy permissive-only` | EPL 行判定为阻断，退出码 1 | 策略差异 |
| T3 | 边界 | `samples/edge/empty.xml`（无 dependencies） | 0 行，退出码 0，摘要显示“依赖数: 0” | 空输入 |
| T4 | 边界 | 依赖名含 `$` 占位符 | 保留原样并标记 UNKNOWN | 非预期输入 |
| T5 | 失败 | 不存在的路径 | 退出码 2，提示含路径 | 错误反馈 |
| T6 | 失败 | 损坏的 XML（`samples/edge/bad-pom.xml`） | 退出码 3，提示含行号（> 0） | 错误定位 |
| T7 | 扩展 | 放入已构建的 npm 插件 jar，扫 `package-lock.json` | 报告含 npm 依赖行（如 `lodash:4.17.21`）；CLI 级集成用例待补 | 扩展接入（计划） |
| T8 | 扩展 | 移走 npm 插件后扫同一文件 | 退出码 4，提示安装插件 | 降级行为 |
| T9 | 契约 | npm 39 例 + Go 40 例（不依赖 core） | 解析结果字段、错误码与发现规则正确 | 契约稳定性（两个扩展均已覆盖） |

**故意改错演示**：把 R3 排序比较器由 `Comparator.comparing(Dependency::riskLevel).reversed()` 改成自然序，T1 变红；`git checkout -- <file>` 恢复后变绿。

**质量控制**：

| 项 | 选择 |
| --- | --- |
| 构建 | `mvn -B clean verify` |
| 基础检查 | Checkstyle（复用 L08 配置） |
| CI | GitHub Actions，push 触发 `mvn -B verify` |
| 覆盖率 | 不强制硬门槛，以三类行为断言覆盖为准 |

---

## 九、风险与对策

| 风险 | 影响 | 概率 | 应对 |
| --- | --- | --- | --- |
| 契约没冻结就开写，B 反复返工 | 高 | 高 | Gate 0 强制执行，不通过不许写代码 |
| npm lock 文件格式多版本（v1/v2/v3） | 中 | 高 | 扩展里做格式嗅探，三种结构各一个 fixture |
| 许可证快照被追问准确性 | 中 | 中 | README 声明"课程级快照，非法律意见，可替换" |
| Maven 属性占位符 `${project.version}` 解析不全 | 中 | 高 | 明确只支持直接声明，占位符标记 UNKNOWN |
| 范围膨胀想加 Web/Gradle/传递依赖 | 高 | 高 | 范围边界写进 README，评审时直接拒绝 |
| 插件类加载报错难排查 | 中 | 中 | 打印插件名 + 异常类型 + 跳过继续 |
| 发布后补记录导致 SHA 失效 | 中 | 中 | RELEASE.md 放仓库外 release/ 目录 |

---

## 十、交付物与任务书映射

| 任务书要求 | 本项目落点 |
| --- | --- |
| 一个核心场景 | `scan` 一条命令 → 报告 + 退出码 |
| 一种扩展机制 + 一个独立扩展 | `DependencySource` SPI + npm 插件 |
| 有效测试 | T1—T9（T7 待补、T9 已由 npm 39 例覆盖）；故意改错演示 |
| 基础质量控制 | Checkstyle + `mvn -B verify` |
| 真实协作 | 每人一条分支 + 一次实质 Review + README 协作表 |
| CI | GitHub Actions 保留一次绿色运行记录 |
| 可追溯发布 | 附注 Tag v0.1.0 + 制品 SHA-256 + RELEASE.md（仓库外） |
| 设计文档 | `docs/DESIGN.docx`，含架构图 + 流程图 + 契约表 |

**README 必须包含一张表**：

| 能力 | 来源 | 本组增量 |
| --- | --- | --- |
| 许可证判断逻辑 | 本组（licenses.json 快照） | 全部 |
| Maven 解析器 | 本组 | 全部 |
| npm 解析器（独立扩展） | 本组 | 全部 |
| 扫描引擎 / 策略引擎 / 报告渲染 | 本组 | 全部 |
| CLI / 退出码 / 插件加载 | 本组 | 全部 |

README 同时声明：许可证快照为课程级数据，当前仅 4 条 Maven 坐标（规划中的约 40 条未达成），结论不构成法律意见，快照可替换。

---

## 十一、演示脚本（6 分钟）

| 步骤 | 操作 | 讲解要点 |
| --- | --- | --- |
| 1 | `licenselens scan --manifest samples/demo-maven/pom.xml` | 展示 4 行报告与风险排序；mysql-connector-java 阻断 |
| 2 | 加 `--policy permissive-only` | 同一输入，EPL-2.0 从"允许"变"阻断"；退出码 1 |
| 3 | 先构建 npm 插件 jar 放入 `plugins/`，再扫 `package-lock.json` | 新生态自动生效，报告含 npm 依赖行（如 `lodash:4.17.21`）；核心零改动 |
| 4 | 移走插件后重跑 | 退出码 4，提示安装 npm 解析器 |
| 5 | 把 `samples/edge/bad-lock.json` 复制为 `package-lock.json` 后扫描 | 退出码 3，提示第 8 行附近格式错误；直接用原名会返回 4 |
| 6 | 改一行排序代码 → 测试红 → 恢复 → 绿 | 证明测试有效；展示 T1 预期顺序 vs 实际顺序 |

**演示前必做**：提前跑一遍所有命令，确认产物存在；准备好插件在/不在两种状态的目录。

---

## 十二、首日行动清单

```bash
# 1. 建好空仓库骨架
licenselens/
├─ pom.xml                      # parent，packaging=pom
├─ licenselens-spi/             # 空
├─ licenselens-core/            # 空
├─ licenselens-cli/             # 空
├─ plugins/licenselens-npm/     # 空（独立 reactor）
├─ docs/                        # 空
├─ samples/demo-maven/          # 放样例 pom.xml
└─ .github/workflows/ci.yml     # 空

# 2. 写 docs/CONTRACT.md（一页纸，只有接口签名和错误码）

# 3. 全组 Review，签字确认

# 4. 起草 licenses.json（当前 4 条，按样例命中情况扩充）

# 5. 起草 demo-maven/pom.xml 样例（4 条依赖：GPL + EPL + 2× Apache）
```

同时完成：

- [ ] 三人在各自机器上验证 JDK 21 + Maven 可用
- [ ] 把 CONTRACT.md 初稿全员 Review，得出"冻结/修改"结论
- [ ] 建好 GitHub 仓库，推一次空骨架
- [ ] 把两页选题说明发给老师确认（可复用本文档前两节）

---

## 十三、需要提前和老师确认的一句话

> 我们计划做一个开源依赖许可证合规审计器（LicenseLens），核心是本组实现的 Maven 清单解析 + 许可证快照匹配 + 策略引擎，扩展是一个独立的 npm 解析器插件；核心与扩展通过公开 SPI 契约协作，扩展 jar 可独立构建、按目录加载接入，核心源码不改。这样的选题是否可行？

**这句话必须在 P0 结束前发出。** 如果老师不认可，越早换题损失越小。

---

## 十四、当前假设与待验证项

| 假设 | 验证方式 | 若不成立的后果 |
| --- | --- | --- |
| Maven pom.xml 解析只用 JDK 自带 XML 即可 | Gate 2 实测 | 加一个轻量库，但不影响核心架构 |
| npm lock 文件格式能稳定解析 | B 单独实测 v1/v2/v3 三种格式 | 明确只支持 v2/v3，写入范围边界 |
| 三人能统一环境（JDK 21.0.2 + Maven 3.9.x） | 各自验证 | 统一脚本，记录环境差异 |
| Checkstyle 配置能复用 L08 | 直接复制跑一次 | 微调规则，不影响其他 |
| 老师认可这个选题 | 邮件确认 | 换题（备选 OpenConvert，规格类似） |
| 当前 4 条许可证快照够答辩用 | 实跑 demo-maven 4 条依赖均命中 | 按未命中项补录常见依赖 |

---

## 十五、已知限制（写进 README）

```text
本次实现：Maven 清单解析、npm 清单解析（扩展，已合入）、Go 清单解析（扩展，已合入）、许可证快照匹配、两种策略、Markdown 报告、CLI 入口、退出码、Checkstyle + CI
本次不做：传递依赖分析、联网查询许可证、SBOM 导出、源码扫描、GUI、Gradle 解析器、插件市场、热加载、多版本兼容
结论声明：许可证快照为课程级数据，结论不构成法律意见
```

这些限制是**主动选择**，不是做不出来。写进 README 是为了：
1. 明确交付范围，防止范围膨胀；
2. 答辩时被追问"为什么不做"时有准备好的答案；
3. 把"不做"转化为设计决策，而不是缺陷。

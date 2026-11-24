# code-style.md — Java 代码风格与实现约束

适用：`licenselens-spi`、`licenselens-core`、`licenselens-cli`、`plugins/*`。注释与 Javadoc 细则见 `comment-style.md`。

## 1. 语言与文件

- Java 21，UTF-8 源文件，4 空格缩进，不用 Tab。
- 换行符跟随仓库现状；**不要在功能 PR 里顺手改整文件的换行符**，否则 diff 无法审阅。
- 一个文件一个 public 顶层类型，文件名为类名；内部类型放类内或同包的 `internal` 包。
- 行宽建议 120 字符以内；超长表达式换行时按语义分组，不强行按运算符对齐。

## 2. 命名

| 元素 | 规则 | 示例 |
| --- | --- | --- |
| 包 | 全小写，按模块分层 | `edu.course.licenselens.spi`、`edu.course.licenselens.core.internal` |
| 类 / 接口 / record / enum | 大驼峰 | `ScanEngine`、`DependencySource`、`LicenseInfo` |
| 方法 / 变量 | 小驼峰 | `parse`、`manifestRef`、`licenseCatalog` |
| 常量 | 全大写下划线 | `MAX_LINE`、`UNKNOWN_SPDX` |
| 布尔 | `is` / `has` / `can` / `should` 开头 | `isSupported`、`hasWarning` |
| 缩写 | 统一小写或首字母大写，不混写 | `parseXml`、`licenseId`，不写 `parseXML`、`licenseID` |
| 测试类 | 被测类名 + `Test` | `ScanEngineTest` |
| 测试方法 | 见第 8 节 | `shouldReturnExitCode4_whenNoSourceSupports` |

- 不使用 `I` 前缀命名接口；不用 `Utils` / `Common` / `Helper` 这类名字建类，按职责命名。
- record 组件名用名词，不用缩写造词；`Dependency(ecosystem, name, version, scope)` 这类字段名不要改动。
- 避免 `data`、`info`、`manager`、`flag` 这类空泛命名。

## 3. 结构与可见性

- 成员顺序：静态常量 → 实例字段 → 构造器 → public 方法 → private 方法 → 内部类型。
- 字段尽量 `final`；无状态的工具方法用 `static`；禁止可变静态状态（`static` 集合或计数器），需要共享状态时用显式参数或实例字段。
- 依赖通过构造器注入（如 `ScanEngine(LicenseCatalog, List<DependencySource>, List<Policy>)`），不在方法里 `new` 出可替换的协作者。
- 不可变数据一律用 record：`ManifestRef`、`Dependency`、`LicenseInfo`、`Decision`。不要给 record 加 setter 或可变字段，也不要在外部提供"就地修改"方法。
- `switch` 覆盖枚举分支时保留 `default`：新增 `RiskLevel` 值按契约属兼容变更，缺 `default` 的实现在升级后会静默漏判。
- 可见性最小化：实现细节放 `internal` 包或包私有；`internal` 包内类型不得被其他模块 import。
- 接口只放契约允许的方法；新增方法一律用 `default` 方法并走契约变更流程。

## 4. 集合、排序与确定性

- **输出必须确定**：报告按风险降序、同风险内名称升序排列；风险序为 `STRONG_COPYLEFT` → `WEAK_COPYLEFT` → `PERMISSIVE` → `UNKNOWN`，与 `RiskLevel` 的声明顺序不同，**不要用 `ordinal()` 排序**。排序用显式 `Comparator`，不要依赖插入顺序或哈希顺序。
- 需要保持声明顺序时用 `List` 或 `LinkedHashMap`；需要按键有序时用 `TreeMap`；禁止依赖 `HashMap` / `HashSet` 的迭代顺序。
- 返回集合时优先返回不可变视图（`List.copyOf`、`Collections.unmodifiableList`），避免调用方意外修改内部状态。
- **不要返回 `null` 集合**：无依赖、无解析器、无命中都返回空集合，契约错误才抛异常。
- 字符串比较、大小写、排序规则显式指定（`Locale.ROOT`、`String.CASE_INSENSITIVE_ORDER`），不依赖系统默认 locale。

## 5. 异常与错误处理

- 清单解析失败统一抛 `SourceException(Code, message[, line])`：`FILE_NOT_FOUND` 用于路径不存在，`PARSE_ERROR` 用于语法错误并带上行号，`UNSUPPORTED_FORMAT` 用于没有可用解析器。
- 消息写清对象与位置：`"pom.xml:42 无法解析 <dependency> 的 version"`，不要只写 `"解析失败"`。
- 不吞异常（空 `catch`）、不 `printStackTrace()`、不用 `RuntimeException` 代替契约异常。
- 不把异常转成 `null` 或空集合继续执行；调用方需要降级时在调用处显式处理。
- 插件加载失败按契约降级：打印插件名到 stderr 后跳过，继续扫描，不中断、不改变退出码。
- CLI 层负责把异常映射为退出码并在 stderr 给出可操作提示；core 不调用 `System.exit`。
- 资源使用 try-with-resources；不要关闭 `System.out` / `System.err`；文件访问用 `Files` / `Path`，不用 `File`。
- 路径不得硬编码；测试里的路径通过参数或 `@TempDir` 提供，不写死盘符与分隔符。

## 6. 输入输出与日志

- core 只负责计算与生成报告文本，不直接写文件、不打印报告正文；`ReportRenderer` 返回 Markdown 字符串，CLI 负责落盘和终端摘要。
- 诊断信息走 `System.err`，正常结论文本走约定输出；禁止留下调试 `System.out.println`。
- 不打印路径以外的环境信息（用户名、绝对家目录）；输出中的路径使用仓库相对路径或调用方给的路径。
- 报告文本中的许可证名称使用 SPDX 标识或 `licenses.json` 中的名称，不自行改写大小写。

## 7. 依赖与模块边界

- `licenselens-spi`：只允许 JDK 类型，零第三方依赖，不引入日志实现、JSON 库或工具库。
- `licenselens-core` / `licenselens-cli`：默认只用 JDK 标准库（XML 用 `javax.xml.parsers` 等 JDK 自带 API）。新增依赖必须有理由并经 Review。
- `plugins/*`：`pom.xml` 只依赖 `licenselens-spi`（JUnit 为 test 范围），不得依赖 `licenselens-core` / `licenselens-cli`；源码不得 import 这两个包。
- 扩展不复制 core 的策略、渲染或快照逻辑；扩展只做"清单 → `List<Dependency>`"。
- core 不得反向依赖任何 `plugins/*`；内置 Maven 解析器通过构造注入，不走 ServiceLoader。
- 依赖方向固定：`cli → core → spi`，`plugins → spi`；`distribution → cli / plugins` 仅用于装配，不被核心或插件反向依赖。

## 8. 测试代码

- JUnit 5（`junit-jupiter`），测试放 `src/test/java`，包与被测类一致。
- 测试方法名表达"输入 + 期望"，或中文 `@DisplayName` 说明场景；一个测试只断言一个行为。
- 断言具体值：依赖条数、字段内容、退出码、报告中的关键行；不要只断言 `assertNotNull` / `assertTrue(size > 0)`。
- 临时文件用 `@TempDir` 构造，样例优先读 `samples/` 下的真实文件；不联网、不依赖执行顺序、不 `Thread.sleep`。
- 只 mock 外部不可控资源；不要 mock 掉被测对象本身或 spi 契约的核心行为。
- 覆盖正常、边界/失败、扩展接入三类行为，对应 T1–T9（见 `../test-writer.md`）。

## 9. 风格检查

- 当前强制门禁是 `mvn -B clean verify`（编译 + 测试 + Checkstyle + `distribution` 装配）。提交前必须本地通过。
- Checkstyle 已接入 `verify` 阶段，规则集为 `config/checkstyle/checkstyle.xml`（按代码风格约束落的最小集合；L08 原件未提供，拿到后替换）。必须零告警，禁止用 suppression 文件绕过。
- 出现风格告警时优先改代码，不改规则；确需调整规则时单独提交并说明原因。

## 10. 禁止事项（硬性）

- 禁止通配符 import（`import java.util.*`），按需逐个引入。
- 禁止注释掉的死代码入库；要么删，要么留 `TODO(模块): 恢复条件`。
- 禁止超过 3 层的嵌套与超过约 40 行的方法，超了就提前返回或抽方法。
- 禁止重复粘贴策略/解析分支，公共逻辑抽到私有方法。
- 禁止把许可证判定写成魔法字符串散落在代码里，统一走 `Policy` 与 `licenses.json`。
- 禁止在代码里写死版本号、路径、URL、端口；需要时走参数或资源文件。
- 禁止修改他人负责模块的实现来"让我的测试通过"，测试与实现的问题分别归属处理。

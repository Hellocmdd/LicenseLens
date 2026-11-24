# test-writer.md — 测试编写专项提示词

叠加在 `AGENT.md` 与 `conventions/code-style.md` 之上，用于补测试、改测试、跑测试。

## 1. 测试矩阵

| 编号 | 验证什么 | 放置位置 | 关键断言 |
| --- | --- | --- | --- |
| T1 | 正常流程：解析 → 匹配 → 判定 → 报告 | `licenselens-core/src/test` | 依赖条数、许可证匹配结果、报告行内容与顺序；demo-maven 含 GPL 阻断，退出码 1 |
| T2 | 策略差异：`campus` 与 `permissive-only` 对同一输入结论不同 | core | 两策略下的 `Decision`、报告标注；两个策略在 demo-maven 上均为退出码 1，差异在 EPL 是否阻断 |
| T3 | 空输入：空 `<dependencies>`、空 `packages`、空 `require` | core | 返回空 `List` 而非 `null`；报告显示 0 条；终端摘要显示“依赖数: 0”，退出码 0 |
| T4 | 非预期输入：缺 version、未知字段、未知许可证、非常规 scope | core | 不崩溃；未命中走 `UNKNOWN`；行为与契约一致 |
| T5 | 文件不存在 | core + cli | `SourceException.Code.FILE_NOT_FOUND`；退出码 2；stderr 含路径 |
| T6 | 清单语法错误 | core + cli | `PARSE_ERROR` 且 `line() > 0`；退出码 3 |
| T7 | 扩展接入后生效：jar 放入插件目录被自动发现 | `licenselens-it` 集成测试 | 新格式清单能解析出预期依赖；退出码按判定结果 |
| T8 | 移走扩展后无可用解析器 | `licenselens-it` 集成测试 | 退出码 4；提示安装插件；不抛未捕获异常 |
| T9 | 扩展契约：只依赖 spi 验证输入输出与错误码 | `plugins/*/src/test` | npm 39 例 + Go 40 例 + Python 31 例已覆盖 `id()`、`supports()`、`parse()` 与 `SourceException` 码 |

- T1–T6 由 core/cli 侧维护；T7/T8 由 `licenselens-it` 基于装配产物验证；T9 由扩展侧维护，**不得依赖 core**。
- T7 同时覆盖插件加载失败降级：损坏 jar 与服务声明错误的 jar 告警后跳过，正常插件仍生效。
- 同一用例可以覆盖多类行为，但矩阵里的每一项都要有对应用例；不要用"一个超级测试"覆盖全部。

## 2. 断言要求

- 断言具体值：`assertEquals(4, deps.size())`、`assertEquals("maven", dep.ecosystem())`、`assertEquals("org.apache.commons:commons-lang3", dep.name())`、`assertEquals(ExitCode.BLOCKED, code)`。
- 报告断言到关键行：依赖名 + 许可证 + 风险等级，必要时用 `assertTrue(report.contains("..."))` 并同时断言顺序。
- 排序断言不能只看"包含"，要断言完整顺序（例如把名称列表整体比较）。
- 失败路径要断言错误码与行号，不能只断言抛出异常。
- 不写空测试、不写只断言 `assertNotNull` 的测试、不用 `Thread.sleep` 等异步、不依赖测试执行顺序。

## 3. 测试数据

- 优先使用 `samples/` 下真实产物：`demo-maven/pom.xml`、`demo-npm/package-lock.json`（v3）与 `package-lock.v2.json`（v2）、`demo-go/go.mod` 与 `go.single-require.mod`、`edge/` 下的空清单与语法错误样例。
- 需要临时文件时用 `@TempDir`，写文件显式指定 `StandardCharsets.UTF_8`。
- 路径从测试资源或参数获得，不写死盘符；换行符用 `System.lineSeparator()` 或按行解析，避免 Windows/Linux 差异。
- 新增样例要同时说明"它覆盖什么边界"，放进 `samples/` 对应目录，并在 README 的样例表中登记。

## 4. 故意改错演示

课程要求现场演示"测试能发现错误"：

1. 选一个逻辑点（推荐报告排序比较器）。
2. 改成错误实现（把风险降序改为升序）。
3. 运行对应测试，确认**变红**，记录失败的断言信息。
4. 恢复代码，确认**变绿**。
5. 该演示不需要提交日志或截图，但演示前必须自己跑通过一遍，确保 6 分钟内能完成。

## 5. 运行命令

```bash
mvn -B clean verify                                          # 全模块构建、测试与装配
mvn -B test -pl licenselens-core,licenselens-cli            # 仅核心与 CLI
mvn -B test -f plugins/licenselens-npm/pom.xml              # npm 扩展单独测试
mvn -B test -f plugins/licenselens-go/pom.xml               # Go 扩展单独测试
mvn -B test -f plugins/licenselens-python/pom.xml           # Python 扩展单独测试
```

- 单独构建扩展时才需要先 `mvn -B -N install -DskipTests` 和 `mvn -B -pl licenselens-spi install -DskipTests`；根 reactor 构建不需要手工安装或复制 jar。
- 测试不联网、不依赖本地 Maven 仓库之外的私有产物。

## 6. 禁止

- 不为通过测试而放宽断言、删除用例或 `@Disabled` 掉失败用例；失败先定位实现问题。
- 不 mock 掉被测对象或契约本身，不复制实现的逻辑到测试里做"等价断言"。
- 不写依赖当前时间、随机数、网络、系统 locale 的测试。
- 不把测试写成对私有方法的直接调用；通过公开契约验证行为。
- 不声称"测试通过"而没有实际执行；新增/修改测试时要贴出运行命令与结果摘要。

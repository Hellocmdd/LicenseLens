# review.md — 代码审查专项提示词

叠加在 `AGENT.md` 之上，用于提交前自查、PR Review 前的预审。

## 1. 审查顺序（按风险从高到低）

1. **契约一致性**：接口签名、字段名、退出码、错误码与 `docs/CONTRACT.md` 是否一致。spi 被改动的，PR 是否标了 `[CONTRACT-CHANGE]` 并同步改了契约文档。
2. **模块边界**：扩展 `pom.xml` 与源码是否只依赖/import `licenselens-spi`；core 是否为新格式加了 `if/else` 分支；内置 Maven 解析器是否仍定位为"内置能力"而不是扩展；是否有人"顺手"改了别人负责的模块。
3. **正确性**：
   - Maven：`<dependencyManagement>`、`test`/`provided` scope、属性占位符、无 version 的依赖如何处理。
   - npm：`lockfileVersion` 1/2/3 差异（v1 用嵌套 `dependencies`，v2/v3 用 `packages`）、`dev` 标记、`""` 根包是否被误当依赖。
   - go.mod：`require` 单行与块、`// indirect`、`/vN` 主版本后缀、注释行。
   - 许可证快照：先按 `ecosystem:name` 精确匹配，未命中回退纯 `name`，最后返回 `UNKNOWN` 而不是猜。
   - 输出确定性：排序键是否为"风险降序 → 名称升序"，集合是否用了不稳定的哈希顺序。
4. **失败路径与退出码**：文件不存在→2，语法错误→3（带行号），无解析器→4，存在阻断→1，插件加载失败→告警后继续（不改变退出码）。CLI 是否在 stderr 给出可操作提示，core 是否误调 `System.exit`。
5. **测试有效性**：断言是否落在具体值（条数、字段、退出码、报告关键行）；是否覆盖 T1–T9 中相关项；故意改错（如把排序比较器方向改反）能否让测试变红。
6. **可维护性**：命名与 `code-style.md` 一致；注释与 `comment-style.md` 一致；TODO 有模块名 owner；有无重复代码、过深嵌套、死代码。
7. **文档与交付同步**：`README.md`、`docs/CONTRACT.md`、`docs/DESIGN.docx`、样例是否需要同步更新；新增命令是否写进 README。

## 2. 输出格式

```text
审查结论：通过 / 需修改 / 阻断

1. licenselens-core/src/main/java/.../ScanEngine.java:78 — 排序比较器方向反了，
   报告会把阻断项排到最后，与 CONTRACT.md 的排序约定不符；T1 断言的顺序会失败。
   依据：docs/CONTRACT.md（报告确定性）、T1 用例
   建议：改为 riskRank 降序、name 升序，用 Comparator.comparing(...).thenComparing(...)。

2. ...

未覆盖项：本轮未检查 plugins/licenselens-go 的 go.mod 边界样例。
```

- 每条意见必须给"位置 + 现象 + 依据 + 建议"，位置精确到 `文件:行号`。
- 依据可以是契约条款、分工规则、测试用例编号，或一条可复现命令；不写"AI 认为""通常不建议"。
- 结论只有三档：**通过**（可以合并）、**需修改**（有具体问题，改完再复核）、**阻断**（违反硬约束 N1–N12，必须重做）。

## 3. 审查前必须实际执行

```bash
mvn -B clean verify
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-maven/pom.xml --out target/report.md
java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-maven/pom.xml --policy permissive-only --out target/report-permissive.md
```

- 涉及扩展的 PR 还要按 README 的独立构建命令构建该扩展，并验证"放入 plugins/ 后被自动发现、移走后退出码 4"。
- 命令失败或未执行，要在结论里写明"未执行"，不能默认通过。

## 4. 禁止

- 不允许只写 "LGTM"/"看起来没问题"；这类输出不算有效 Review，也是课程明确禁止的。
- 不直接重写作者的业务实现；给位置、依据和建议，由作者改。
- 不评价个人风格偏好（缩进、命名喜好），除非与 `code-style.md` 冲突。
- 不把 AI 意见当成人工审查记录；AI 结论必须由实际审查人核对后才能写进 README 的审查记录。
- 不虚构测试结果、CI 状态或"已跑通"；没跑就写没跑。
- 不在审查中扩大范围，要求作者实现本次范围外的功能。

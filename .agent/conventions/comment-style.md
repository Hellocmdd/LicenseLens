# comment-style.md — 注释与 Javadoc 规范

适用：`licenselens-spi`、`licenselens-core`、`licenselens-cli`、`plugins/*` 的全部 Java 代码。

## 1. 语言与用词

- 注释用中文；专业术语首次出现附英文原文，如"许可证快照（license snapshot）""服务提供接口（SPI）"。
- 类名、方法名、退出码、SPDX 标识保持代码中的写法，不翻译、不改大小写。
- 句末加句号；一段注释内不混用中英文标点。

## 2. 写什么、不写什么

- **写"为什么"和"契约边界"**，不写"是什么"。代码能自解释的不加注释。
- 值得写注释的地方：
  - 与契约相关的约束：为什么这里返回空集合而不是抛异常、为什么退出码是 4。
  - 非显然的取舍：为什么按 `id()` 字典序选解析器、为什么排序键是这个组合。
  - 容易踩坑的边界：`package-lock.json` v1 没有 `packages` 字段、`go.mod` 的 `// indirect` 与 `/v8` 后缀。
- 不写：`// 循环`、`// 返回结果`、`// 自增`、`// 如果为空`这类复述代码的注释。

## 3. 必须写 Javadoc 的位置

以下成员没有 Javadoc 视为未完成：

- `licenselens-spi` 的全部公开类型与成员（接口、record、enum、异常），这是对外契约。
- `core` 与 `cli` 的 public 类、public 方法。
- 任何抛出 `SourceException` 或影响退出码的方法。

格式：

```java
/**
 * 扫描调度入口：选择解析器、查询许可证、执行策略并渲染报告。
 *
 * <p>排序规则固定为风险等级降序、依赖名称升序，保证同一输入产生相同报告。
 *
 * @param manifest 清单文件路径，不能为 {@code null}
 * @param policyId 策略标识，支持 {@code "campus"} 与 {@code "permissive-only"}
 * @return 扫描结果，含报告文本与退出码
 * @throws SourceException 清单不存在（{@link SourceException.Code#FILE_NOT_FOUND}）、
 *                         语法错误（{@link SourceException.Code#PARSE_ERROR}）
 *                         或没有解析器支持该格式（{@link SourceException.Code#UNSUPPORTED_FORMAT}）
 */
```

- 首句一行摘要，以动词开头，不复述方法名。
- 段落用 `<p>` 分隔；列表用 `<ul><li>`。
- 引用类型用 `{@link}`，引用字面量用 `{@code}`；泛型尖括号写成 `&lt;Dependency&gt;`。
- `@param` 写约束（能否为 null、取值范围），`@return` 写语义（空集合还是 null），`@throws` 只列契约允许的异常。
- 枚举常量写单行 `/** ... */`，说明它在判定中的含义；`RiskLevel` 的每个值都要写。
- record 的类注释写字段语义与来源，如"`scope` 为 Maven 的 test/provided，npm 的 runtime/dev"。

## 4. 不要写的注释

- 不写 `@author`、`@version`、`@since`、日期和修改历史——分工与历史由 Git 记录。
- 不写"待补充""见文档"这类没有落点的注释；要引用就写具体位置，如 `见 docs/CONTRACT.md 第二节退出码表`。
- 不写与实现不符的注释；改了代码必须同步改注释，否则按缺陷处理。
- 不把大段设计写进类注释；超过 5 行的设计说明放 `docs/`，代码里留一行指向它。
- 不用注释替代错误处理（`// ignore` 后接空 `catch`）。

## 5. TODO 与 FIXME

统一格式，`owner` 写模块名而不是人名：

```java
// TODO(core): 实现 ScanResult 的退出码聚合
// TODO(npm): 支持 lockfileVersion 1 的嵌套 dependencies 结构
// TODO(cli): 插件目录不存在时给出 stderr 提示
```

- `owner` 取值：`spi`、`core`、`cli`、`npm`、`go`、`docs`、`ci`、`build`。禁止 `TODO(TBD)`、`TODO(A)`、`TODO(待定)`。
- 一句话写清楚"要做什么"，能直接被一个提交关闭；跨模块的大 TODO 拆成多条。
- TODO 必须紧跟被延后的代码位置，不要集中堆在文件头。
- 已知缺陷用 `// FIXME(<模块>): <现象>（触发条件：<输入或环境>）`，并在 PR 或 Issue 里跟踪，不长期留在主干。

## 6. 注释与文档的分工

- 契约语义（接口签名、退出码、错误码、兼容策略）写在 `docs/CONTRACT.md`；代码注释只写该类型的实现侧约束，不复制整张表。
- 面向使用者的说明写 `README.md`；设计理由写 `docs/DESIGN.docx`；发布记录写 `release/RELEASE.md`。同一内容用链接引用，不在注释里重复。
- 代码注释不出现本地绝对路径、用户名、令牌或内部聊天记录里的表述。

## 7. 自检

- [ ] 新增的公开类型/方法都有 Javadoc，且 `@param` / `@return` / `@throws` 完整
- [ ] 注释解释的是"为什么"，不是"是什么"
- [ ] 每个 TODO 有模块名 owner 和可执行的一句话
- [ ] 改过的代码附近没有过期注释
- [ ] 没有 `@author`、日期、修改历史、注释掉的死代码

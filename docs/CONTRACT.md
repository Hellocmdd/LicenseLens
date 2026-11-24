# LicenseLens 扩展契约（v1，冻结于 Gate 0）

> 本文档与 `LICENSELENS方向与实施规划.md` 和 `LicenseLens分工合作书.md` 共同锁定开发边界。
> **任何变更须先改此文档并经全员 Review，再改代码。**

---

## 一、核心接口签名

### 1.1 DependencySource（清单解析器）

```java
package edu.course.licenselens.spi;

public interface DependencySource {
    String id();                          // 如 "maven" / "npm" / "go" / "python"
    boolean supports(ManifestRef ref);    // 文件名 + 首行特征判断
    List<Dependency> parse(Path file) throws SourceException;
}
```

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `id()` | String | 解析器唯一标识，日志和错误提示用 |
| `supports(ManifestRef)` | boolean | 是否能处理此清单文件 |
| `parse(Path)` | List\<Dependency\> | 解析清单 → 依赖列表，按声明顺序返回 |

### 1.2 ManifestRef

```java
public record ManifestRef(Path path, String fileName, String firstLine) {}
```

### 1.3 Dependency

```java
public record Dependency(String ecosystem, String name, String version, String scope) {}
```

| 字段 | Maven 填充 | npm 填充 |
| --- | --- | --- |
| ecosystem | "maven" | "npm" |
| name | groupId:artifactId | 包名 |
| version | version | version |
| scope | scope（test/provided 等） | "runtime"/"dev" |

### 1.4 Policy（策略）

```java
public interface Policy {
    String id();
    Decision evaluate(LicenseInfo license, Dependency dep);
}
```

### 1.5 Decision

```java
public record Decision(boolean allowed, boolean warning, String reason) {
    public static Decision allow(String reason) { ... }
    public static Decision warn(String reason)  { ... }
    public static Decision block(String reason) { ... }
}
```

### 1.6 LicenseInfo

```java
public record LicenseInfo(String spdxId, String name, RiskLevel risk) {}
```

### 1.7 RiskLevel

```java
public enum RiskLevel { PERMISSIVE, WEAK_COPYLEFT, STRONG_COPYLEFT, UNKNOWN }
```

### 1.8 SourceException

```java
public final class SourceException extends Exception {
    public enum Code { FILE_NOT_FOUND, PARSE_ERROR, UNSUPPORTED_FORMAT }
    public Code code();   // 三选一
    public int line();    // PARSE_ERROR 时有效，其他返回 -1
}
```

---

## 二、退出码

CLI 根据以下退出码在 main 中 `System.exit(code)`。

| 退出码 | 场景 | 来源 |
| --- | --- | --- |
| 0 | 正常，无阻断依赖 | 一切 OK |
| 1 | 存在阻断依赖 | 策略判定结果 |
| 2 | 清单文件不存在 | CLI 参数校验 / SourceException.FILE_NOT_FOUND |
| 3 | 清单语法错误（XML / JSON / 文本） | SourceException.PARSE_ERROR |
| 4 | 无可用解析器 | 所有 DependencySource.supports() 返回 false |

---

## 三、错误契约

| 场景 | 抛出 | CLI 处理 | 退出码 |
| --- | --- | --- | --- |
| 清单路径不存在 | SourceException(FILE_NOT_FOUND) | 打印路径，终止 | 2 |
| 清单语法错误（XML / JSON / 文本） | SourceException(PARSE_ERROR, line=N) | 打印行号 + 消息，终止 | 3 |
| 所有解析器 supports=false | 无异常 | 提示安装插件，终止 | 4 |
| 插件 jar 加载失败 | 运行时异常 | 打印插件名 + 跳过，继续扫描 | 不改变退出码（由策略判定决定） |
| 依赖未命中许可证快照 | 无异常 | LicenseCatalog 返回 UNKNOWN 占位 | 0 或 1（取决于策略） |
| 策略判定存在阻断 | 无异常 | 报告含阻断行，CLI 返回 1 | 1 |

---

## 四、注册方式

- **内置 Maven 解析器**：直接在 ScanEngine 构造时注入，不需要 ServiceLoader
- **外部插件**：jar 内 `META-INF/services/edu.course.licenselens.spi.DependencySource` 声明实现类全限定名
- CLI 启动时用**独立 URLClassLoader**加载 `--plugins` 目录下所有 jar，再 ServiceLoader 发现
- 选择顺序：按 `id()` 字典序，取第一个 `supports()` 返回 true 的解析器
- 加载失败的 jar 跳过并 `System.err.println` 告警，不中断扫描

---

## 五、兼容策略

| 变更类型 | 处理 |
| --- | --- |
| 契约新增**默认方法** | 兼容，旧插件无需改动 |
| 新增 `Dependency` 字段 | 不兼容（record 构造变化），升主版本并公告 |
| 新增 `RiskLevel` 枚举值 | 兼容，但 Policy 实现 switch 需有 default 分支 |
| 删除方法 | 不兼容，升主版本 |
| 退出码表变更 | 视为不兼容，升主版本 |

**声明**：本课程版本仅保证 0.1.x 内部兼容，不承诺跨大版本兼容。

---

## 六、变更记录

| 日期 | 变更 | 影响 | 审批 |
| --- | --- | --- | --- |
| 2026-11-17 | v1 初始冻结 | 所有接口签名、退出码表、错误契约 | A/B/C 全员签字 |
| 2026-11-23 | 澄清性修正：解析器 id 示例覆盖四类生态；退出码 3 场景改为“清单语法错误”；插件加载失败不再写死退出码 0 | 仅修正描述，不改变接口签名、退出码数值或错误契约行为 | 待全员 Review |

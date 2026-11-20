package edu.course.licenselens.go;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.SourceException;

import java.nio.file.Path;
import java.util.List;

/**
 * 独立扩展：Go go.mod 解析器。B 负责实现。
 * <p>
 * 与 npm 插件一样和 core 完全解耦——只依赖 {@code licenselens-spi}。
 * 这是第二个独立扩展，用来验证"核心源码不变即可接入新生态"。
 *
 * <h3>go.mod 语法要点（parse 实现参考）</h3>
 * <ul>
 *   <li>首行为 {@code module <模块路径>}，如 {@code module github.com/foo/bar}</li>
 *   <li>块式依赖：{@code require ( ... )}，括号内每行 {@code <path> <version> [// indirect]}</li>
 *   <li>单行依赖：{@code require <path> <version>}</li>
 *   <li>{@code // indirect} 是注释，按 scope 语义可映射为 "indirect" 或忽略</li>
 *   <li>{@code replace} / {@code exclude} / {@code retract} 本次不处理</li>
 *   <li>主版本后缀（如 {@code /v2}）是 module path 的一部分，原样保留</li>
 * </ul>
 *
 * <h3>字段填充约定（见 CONTRACT.md 1.3）</h3>
 * <ul>
 *   <li>ecosystem = "go"</li>
 *   <li>name = module path（如 "github.com/gin-gonic/gin"）</li>
 *   <li>version = 语义版本字符串（如 "v1.9.1"，含 +incompatible 时原样保留）</li>
 *   <li>scope = "runtime" 或 "indirect"</li>
 * </ul>
 *
 * <h3>依赖方向铁律</h3>
 * 本插件 pom 里**只有** {@code licenselens-spi} 依赖，不得出现 core。
 */
public class GoModManifestParser implements DependencySource {

    @Override
    public String id() {
        return "go";
    }

    @Override
    public boolean supports(ManifestRef ref) {
        if (!"go.mod".equalsIgnoreCase(ref.fileName())) {
            return false;
        }
        // TODO: B 可选增强——校验首行以 "module " 开头，避免同名误判
        return true;
    }

    @Override
    public List<Dependency> parse(Path file) throws SourceException {
        // TODO: B 实现——逐行解析 go.mod 的 require 块/单行 require → List<Dependency>
        // 文件不存在 → SourceException(FILE_NOT_FOUND)
        // 格式错误（如 require 块不闭合）→ SourceException(PARSE_ERROR, line=N)
        throw new UnsupportedOperationException("GoModManifestParser.parse 待实现");
    }
}

package edu.course.licenselens.npm;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.SourceException;

import java.nio.file.Path;
import java.util.List;

/**
 * 独立扩展：npm package-lock.json 解析器。B 负责实现。
 * <p>
 * 这是 LicenseLens 的**第一个独立扩展**，和 core 完全解耦——
 * 只依赖 {@code licenselens-spi}，不引用 {@code licenselens-core}。
 *
 * <h3>要处理的三种 lock 格式</h3>
 * <ul>
 *   <li>lock v1（npm 5.x）：顶层 dependencies 字段直接映射</li>
 *   <li>lock v2（npm 7+）：顶层 dependencies + packages 两种结构并存</li>
 *   <li>lock v3（npm 9+）：只有 packages 字段，根节点是 ""</li>
 * </ul>
 *
 * <h3>依赖方向铁律</h3>
 * 本插件 pom 里**只有** {@code licenselens-spi} 依赖。
 * 编译期如果出现 core 依赖，说明越界，B 必须退回。
 */
public class NpmManifestParser implements DependencySource {

    @Override
    public String id() {
        return "npm";
    }

    @Override
    public boolean supports(ManifestRef ref) {
        // TODO: B 实现——文件名 package-lock.json
        return "package-lock.json".equalsIgnoreCase(ref.fileName());
    }

    @Override
    public List<Dependency> parse(Path file) throws SourceException {
        // TODO: B 实现——解析 package-lock.json（v1/v2/v3）→ List<Dependency>
        throw new UnsupportedOperationException("NpmManifestParser.parse 待实现");
    }
}

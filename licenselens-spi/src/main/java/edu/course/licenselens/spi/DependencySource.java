package edu.course.licenselens.spi;

import java.nio.file.Path;
import java.util.List;

/**
 * 依赖清单解析器接口。
 * <p>
 * 扩展方（如 npm、Gradle）通过实现此接口接入 LicenseLens。
 * 核心模块只依赖此接口，不感知具体文件格式。
 *
 * <h3>注册方式</h3>
 * 在 jar 的 {@code META-INF/services/edu.course.licenselens.spi.DependencySource}
 * 中声明实现类全限定名。CLI 启动时通过 ServiceLoader 发现。
 *
 * <h3>契约稳定性</h3>
 * 此接口是课程 0.1.x 版本的唯一扩展点，字段变更须升主版本。
 *
 * @see ManifestRef
 * @see Dependency
 */
public interface DependencySource {

    /**
     * 解析器唯一标识，用于日志与错误提示。
     * 内置 Maven 解析器返回 "maven"，npm 扩展返回 "npm"。
     */
    String id();

    /**
     * 判断此解析器能否处理给定清单。
     * <p>
     * 建议依据：文件名后缀 + 文件首行特征。
     * 如 {@code pom.xml} 以 {@code "maven"} 的 {@code "<project"} 开头；
     * {@code package-lock.json} 以 {@code "npm"} 的 {@code "\"name\""} 开头。
     */
    boolean supports(ManifestRef ref);

    /**
     * 解析清单文件，返回其中声明的直接依赖列表。
     * <p>
     * 解析器只负责"文件 → 依赖列表"，**不做许可证判断**——那是 core 的职责。
     *
     * @param file 清单文件路径
     * @return 依赖列表，按文件中声明顺序返回（不排序）
     * @throws SourceException 文件不存在 / 语法错误 / 格式不支持
     */
    List<Dependency> parse(Path file) throws SourceException;
}

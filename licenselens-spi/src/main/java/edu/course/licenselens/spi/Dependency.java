package edu.course.licenselens.spi;

/**
 * 一个依赖项的四元组。
 * <p>
 * Maven 解析器填充：ecosystem="maven"、name="groupId:artifactId"、version、scope；
 * npm 解析器填充：ecosystem="npm"、name 为包名、version、scope="runtime"/"dev"。
 *
 * @param ecosystem  生态标识（"maven" 或 "npm"）
 * @param name       依赖名（Maven 为 groupId:artifactId，npm 为包名）
 * @param version    版本号
 * @param scope      作用域（Maven scope 或 npm 的 dependencies/devDependencies）
 */
public record Dependency(String ecosystem, String name, String version, String scope) {
}

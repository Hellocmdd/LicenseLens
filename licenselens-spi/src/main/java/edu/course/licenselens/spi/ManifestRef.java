package edu.course.licenselens.spi;

import java.nio.file.Path;

/**
 * 清单文件的元信息，用于 {@link DependencySource#supports} 判断。
 *
 * @param path      清单文件绝对路径
 * @param fileName  文件名（如 "pom.xml"、"package-lock.json"）
 * @param firstLine 文件首行内容（截取前 200 字符，用于嗅探格式特征）
 */
public record ManifestRef(Path path, String fileName, String firstLine) {
}

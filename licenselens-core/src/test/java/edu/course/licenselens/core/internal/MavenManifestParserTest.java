package edu.course.licenselens.core.internal;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.SourceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MavenManifestParser 的行为测试，覆盖 T1 / T3 / T5 / T6。
 */
class MavenManifestParserTest {

    private final MavenManifestParser parser = new MavenManifestParser();

    @Test
    @DisplayName("T1 解析 demo-maven：4 条依赖，字段按契约填充")
    void shouldParseDemoMaven() throws SourceException {
        List<Dependency> deps = parser.parse(sample("demo-maven/pom.xml"));

        assertEquals(4, deps.size());
        Dependency first = deps.get(0);
        assertEquals("maven", first.ecosystem());
        assertEquals("org.apache.commons:commons-lang3", first.name());
        assertEquals("3.14.0", first.version());
        assertEquals("compile", first.scope());
        assertEquals("test", deps.get(2).scope());
    }

    @Test
    @DisplayName("T3 没有 <dependencies>：返回空 List，不是 null")
    void shouldReturnEmptyListWhenNoDependencies() throws SourceException {
        List<Dependency> deps = parser.parse(sample("edge/empty.xml"));

        assertNotNull(deps);
        assertTrue(deps.isEmpty());
    }

    @Test
    @DisplayName("T3 首行是 XML 声明的自定义文件名：supports() 也应认领")
    void shouldSupportXmlFileWhoseFirstLineIsDeclaration() throws IOException {
        Path file = sample("edge/empty.xml");
        ManifestRef ref = new ManifestRef(file, file.getFileName().toString(), firstLine(file));

        assertTrue(parser.supports(ref),
                "empty.xml 首行是 <?xml ...?>，但根元素是 <project>，Maven 解析器应接受");
    }

    @Test
    @DisplayName("T5 文件不存在：FILE_NOT_FOUND")
    void shouldThrowFileNotFound() {
        SourceException e = assertThrows(SourceException.class,
                () -> parser.parse(sample("edge/does-not-exist.xml")));

        assertEquals(SourceException.Code.FILE_NOT_FOUND, e.code());
    }

    @Test
    @DisplayName("T6 损坏的 XML：PARSE_ERROR 且带行号")
    void shouldReportParseErrorWithLine() {
        SourceException e = assertThrows(SourceException.class,
                () -> parser.parse(sample("edge/bad-pom.xml")));

        assertEquals(SourceException.Code.PARSE_ERROR, e.code());
        assertTrue(e.line() > 0, "PARSE_ERROR 应带行号，实际为 " + e.line());
    }

    @Test
    @DisplayName("非 pom 清单不认领")
    void shouldRejectNonPomManifest() {
        ManifestRef ref = new ManifestRef(Path.of("package-lock.json"), "package-lock.json", "{");

        assertFalse(parser.supports(ref));
    }

    @Test
    @DisplayName("T4 坐标是 ${...} 占位符：原样保留，不解析 Maven 属性")
    void shouldKeepPlaceholderCoordinates() throws SourceException {
        List<Dependency> deps = parser.parse(sample("edge/placeholder-pom.xml"));

        assertEquals(2, deps.size());
        Dependency placeholderName = deps.get(0);
        assertEquals("${project.groupId}:sibling-module", placeholderName.name());
        assertEquals("${project.version}", placeholderName.version());
        assertEquals("${guava.version}", deps.get(1).version());
    }

    @Test
    @DisplayName("dependencyManagement 里的条目不是实际依赖，不应计入")
    void shouldIgnoreDependencyManagementEntries() throws SourceException {
        List<Dependency> deps = parser.parse(sample("edge/management-pom.xml"));

        assertEquals(1, deps.size());
        assertEquals("org.apache.commons:commons-lang3", deps.get(0).name());
    }

    @Test
    @DisplayName("依赖内嵌 <exclusions> 时只取依赖自身坐标")
    void shouldReadOnlyDirectChildCoordinates(@TempDir Path tmp) throws IOException, SourceException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>demo</artifactId>
                      <version>1.0</version>
                      <scope>runtime</scope>
                      <exclusions>
                        <exclusion>
                          <groupId>org.excluded</groupId>
                          <artifactId>bad</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """, StandardCharsets.UTF_8);

        List<Dependency> deps = parser.parse(pom);

        assertEquals(1, deps.size());
        assertEquals("org.example:demo", deps.get(0).name());
        assertEquals("runtime", deps.get(0).scope());
    }

    private static String firstLine(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).lines().findFirst().orElse("");
    }

    private static Path sample(String relative) {
        return repoRoot().resolve("samples").resolve(relative);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("samples"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("找不到 samples/，测试需在仓库内运行");
        }
        return dir;
    }
}

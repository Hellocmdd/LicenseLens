package edu.course.licenselens.python;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.SourceException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Python 扩展契约测试：只依赖 licenselens-spi 与 JUnit，不引用 core 任何类。
 * <p>
 * 覆盖：SPI 契约、ServiceLoader 注册、固定版本与范围约束、extras、环境标记、注释与选项行、
 * 续行、dev scope、直接引用，以及 CONTRACT.md 中 FILE_NOT_FOUND / PARSE_ERROR 路径。
 */
class RequirementsManifestParserTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    private final RequirementsManifestParser parser = new RequirementsManifestParser();

    @TempDir
    Path tempDir;

    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private List<Dependency> parse(String content) throws Exception {
        return parser.parse(write("requirements.txt", content));
    }

    private static Dependency dep(String name, String version, String scope) {
        return new Dependency("python", name, version, scope);
    }

    @Nested
    class SpiContract {

        @Test
        void idIsPython() {
            assertEquals("python", parser.id());
        }

        @Test
        void supportsRequirementsTxt() {
            assertTrue(parser.supports(new ManifestRef(
                    Path.of("x"), "requirements.txt", "requests==2.31.0")));
        }

        @Test
        void supportsDevRequirementsTxt() {
            assertTrue(parser.supports(new ManifestRef(
                    Path.of("x"), "dev-requirements.txt", "pytest==8.0.0")));
        }

        @Test
        void supportsRequirementsIn() {
            assertTrue(parser.supports(new ManifestRef(
                    Path.of("x"), "requirements.in", "django")));
        }

        @Test
        void rejectsPomXml() {
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "pom.xml", "<project>")));
        }

        @Test
        void rejectsPackageLock() {
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "package-lock.json", "{")));
        }

        @Test
        void rejectsProseInRequirementsFile() {
            assertFalse(parser.supports(new ManifestRef(
                    Path.of("x"), "requirements.txt", "This file lists our dependencies")));
        }

        @Test
        void supportsNullRef() {
            assertFalse(parser.supports(null));
        }

        @Test
        void isDiscoverableViaServiceLoader() {
            boolean found = ServiceLoader.load(DependencySource.class).stream()
                    .anyMatch(p -> "python".equals(p.get().id()));
            assertTrue(found, "META-INF/services 未声明 RequirementsManifestParser");
        }
    }

    @Nested
    class BasicParsing {

        @Test
        void parsesPinnedVersion() throws Exception {
            assertEquals(List.of(dep("requests", "2.31.0", "runtime")), parse("requests==2.31.0\n"));
        }

        @Test
        void keepsRangeSpecifier() throws Exception {
            assertEquals(List.of(dep("flask", ">=2.2,<3.0", "runtime")), parse("flask>=2.2,<3.0\n"));
        }

        @Test
        void bareNameHasEmptyVersion() throws Exception {
            assertEquals(List.of(dep("pytest", "", "runtime")), parse("pytest\n"));
        }

        @Test
        void ignoresExtras() throws Exception {
            assertEquals(List.of(dep("requests", "2.31.0", "runtime")),
                    parse("requests[socks,security]==2.31.0\n"));
        }

        @Test
        void ignoresEnvironmentMarker() throws Exception {
            assertEquals(List.of(dep("pywin32", "306", "runtime")),
                    parse("pywin32==306 ; sys_platform == \"win32\"\n"));
        }

        @Test
        void skipsCommentsAndBlankLines() throws Exception {
            String content = "# 依赖清单\n\nrequests==2.31.0   # HTTP 客户端\n";
            assertEquals(List.of(dep("requests", "2.31.0", "runtime")), parse(content));
        }

        @Test
        void skipsOptionLines() throws Exception {
            String content = "-r base.txt\n--index-url https://pypi.org/simple\n"
                    + "-e git+https://example.com/pkg#egg=pkg\nrequests==2.31.0\n";
            assertEquals(List.of(dep("requests", "2.31.0", "runtime")), parse(content));
        }

        @Test
        void dropsInlineHashOption() throws Exception {
            assertEquals(List.of(dep("requests", "2.31.0", "runtime")),
                    parse("requests==2.31.0 --hash=sha256:abcd\n"));
        }

        @Test
        void joinsContinuationLine() throws Exception {
            assertEquals(List.of(dep("requests", "2.31.0", "runtime")),
                    parse("requests==2.31.0 \\\n--hash=sha256:abcd\n"));
        }

        @Test
        void devFileNameSetsDevScope() throws Exception {
            List<Dependency> deps = parser.parse(write("dev-requirements.txt", "pytest==8.0.0\n"));
            assertEquals(List.of(dep("pytest", "8.0.0", "dev")), deps);
        }

        @Test
        void keepsDirectUrlAsVersion() throws Exception {
            assertEquals(List.of(dep("pkg", "https://example.com/pkg-1.0.whl", "runtime")),
                    parse("pkg @ https://example.com/pkg-1.0.whl\n"));
        }

        @Test
        void preservesDeclarationOrder() throws Exception {
            assertEquals(List.of(dep("zeta", "1.0", "runtime"), dep("alpha", "2.0", "runtime")),
                    parse("zeta==1.0\nalpha==2.0\n"));
        }
    }

    @Nested
    class Boundaries {

        @Test
        void emptyFileYieldsEmptyList() throws Exception {
            assertTrue(parse("").isEmpty());
        }

        @Test
        void handlesBomAndCrlf() throws Exception {
            assertEquals(List.of(dep("requests", "2.31.0", "runtime")),
                    parse("\uFEFFrequests==2.31.0\r\n"));
        }

        @Test
        void missingFileIsNotFound() {
            SourceException error = assertThrows(SourceException.class,
                    () -> parser.parse(tempDir.resolve("absent.txt")));
            assertEquals(SourceException.Code.FILE_NOT_FOUND, error.code());
        }

        @Test
        void duplicateNameIsRejected() {
            SourceException error = assertThrows(SourceException.class,
                    () -> parse("requests==2.31.0\nrequests==2.30.0\n"));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
            assertEquals(2, error.line());
        }

        @Test
        void invalidLineReportsLineNumber() {
            SourceException error = assertThrows(SourceException.class,
                    () -> parse("requests==2.31.0\nthis is not a requirement\n"));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
            assertEquals(2, error.line());
        }

        @Test
        void unclosedExtrasIsRejected() {
            SourceException error = assertThrows(SourceException.class,
                    () -> parse("requests[socks==2.31.0\n"));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
            assertEquals(1, error.line());
        }

        @Test
        void invalidSpecifierIsRejected() {
            SourceException error = assertThrows(SourceException.class,
                    () -> parse("requests=>=1.0\n"));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
        }

        @Test
        void danglingContinuationIsRejected() {
            SourceException error = assertThrows(SourceException.class,
                    () -> parse("requests==2.31.0 \\"));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
        }
    }

    @Nested
    class Samples {

        @Test
        void demoPythonSampleHasThreeDependencies() throws Exception {
            List<Dependency> deps = parser.parse(SAMPLES.resolve("demo-python/requirements.txt"));
            assertEquals(List.of(
                    dep("requests", "2.31.0", "runtime"),
                    dep("flask", ">=2.2,<3.0", "runtime"),
                    dep("numpy", "1.26.4", "runtime")), deps);
        }

        @Test
        void badPythonSampleReportsThirdLine() {
            SourceException error = assertThrows(SourceException.class,
                    () -> parser.parse(SAMPLES.resolve("edge/bad-requirements.txt")));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
            assertEquals(3, error.line());
        }
    }
}

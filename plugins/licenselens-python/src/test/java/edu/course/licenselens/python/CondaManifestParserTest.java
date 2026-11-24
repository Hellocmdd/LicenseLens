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
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * conda 扩展契约测试：只依赖 licenselens-spi 与 JUnit，不引用 core 任何类。
 * <p>
 * 覆盖：SPI 契约与 ServiceLoader 注册、environment.yml 的 conda / pip 两种条目、
 * conda-lock.yml 的 package 列表与多平台去重、字段填充，以及 CONTRACT.md 中
 * FILE_NOT_FOUND / PARSE_ERROR 路径。
 */
class CondaManifestParserTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    private final CondaManifestParser parser = new CondaManifestParser();

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Dependency conda(String name, String version, String scope) {
        return new Dependency("conda", name, version, scope);
    }

    private static Dependency python(String name, String version, String scope) {
        return new Dependency("python", name, version, scope);
    }

    @Nested
    class SpiContract {

        @Test
        void idIsPythonConda() {
            assertEquals("python-conda", parser.id());
        }

        @Test
        void supportsEnvironmentYml() {
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "environment.yml", "")));
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "environment.yaml", "")));
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "ENVIRONMENT.YML", "")));
        }

        @Test
        void supportsCondaLock() {
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "conda-lock.yml", "")));
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "conda-lock.yaml", "")));
        }

        @Test
        void rejectsOtherManifests() {
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "requirements.txt", "")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "pyproject.toml", "")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "uv.lock", "")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "pom.xml", "")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "environment.yml.bak", "")));
            assertFalse(parser.supports(null));
        }

        @Test
        void discoveredByServiceLoader() {
            Set<String> ids = new HashSet<>();
            ServiceLoader.load(DependencySource.class).forEach(source -> ids.add(source.id()));
            assertTrue(ids.contains("python"));
            assertTrue(ids.contains("python-uv"));
            assertTrue(ids.contains("python-conda"));
        }
    }

    @Nested
    class EnvironmentYml {

        @Test
        void parsesCondaPins() throws IOException, SourceException {
            Path file = write("environment.yml", """
                    name: demo
                    dependencies:
                      - python=3.11
                      - numpy=1.26.4
                    """);
            assertEquals(List.of(conda("python", "3.11", "runtime"),
                    conda("numpy", "1.26.4", "runtime")), parser.parse(file));
        }

        @Test
        void keepsRangesAndDropsBuild() throws IOException, SourceException {
            Path file = write("environment.yml", """
                    dependencies:
                      - pandas>=2.1
                      - pytorch=2.1.0=cuda11.8_0
                    """);
            assertEquals(List.of(conda("pandas", ">=2.1", "runtime"),
                    conda("pytorch", "2.1.0", "runtime")), parser.parse(file));
        }

        @Test
        void dropsChannelPrefix() throws IOException, SourceException {
            Path file = write("environment.yml", """
                    dependencies:
                      - conda-forge::scipy=1.11.0
                    """);
            assertEquals(List.of(conda("scipy", "1.11.0", "runtime")), parser.parse(file));
        }

        @Test
        void parsesSpaceFormVersion() throws IOException, SourceException {
            Path file = write("environment.yml", """
                    dependencies:
                      - "numpy 1.26.4 py311_0"
                    """);
            assertEquals(List.of(conda("numpy", "1.26.4", "runtime")), parser.parse(file));
        }

        @Test
        void pipSectionUsesPythonEcosystem() throws IOException, SourceException {
            Path file = write("environment.yml", """
                    dependencies:
                      - numpy=1.26.4
                      - pip:
                          - requests==2.31.0
                          - flask>=2.2,<3.0
                    """);
            assertEquals(List.of(conda("numpy", "1.26.4", "runtime"),
                    python("requests", "2.31.0", "runtime"),
                    python("flask", ">=2.2,<3.0", "runtime")), parser.parse(file));
        }

        @Test
        void devFileNameMarksScope() throws IOException, SourceException {
            Path file = write("dev-environment.yml", """
                    dependencies:
                      - pytest=7.4.0
                      - pip:
                          - black==23.0.0
                    """);
            assertEquals(List.of(conda("pytest", "7.4.0", "dev"),
                    python("black", "23.0.0", "dev")), parser.parse(file));
        }

        @Test
        void ignoresSectionsOutsideDependencies() throws IOException, SourceException {
            Path file = write("environment.yml", """
                    name: demo
                    channels:
                      - conda-forge
                    dependencies:
                      - numpy=1.26.4
                    """);
            assertEquals(List.of(conda("numpy", "1.26.4", "runtime")), parser.parse(file));
        }
    }

    @Nested
    class CondaLock {

        @Test
        void parsesPackageEntries() throws IOException, SourceException {
            Path file = write("conda-lock.yml", """
                    version: 1
                    package:
                      - name: numpy
                        version: "1.26.4"
                        manager: conda
                      - name: requests
                        version: "2.31.0"
                        manager: pip
                    """);
            assertEquals(List.of(conda("numpy", "1.26.4", "runtime"),
                    python("requests", "2.31.0", "runtime")), parser.parse(file));
        }

        @Test
        void deduplicatesAcrossPlatforms() throws IOException, SourceException {
            Path file = write("conda-lock.yml", """
                    package:
                      - name: numpy
                        version: "1.26.4"
                        manager: conda
                        platform: linux-64
                      - name: numpy
                        version: "1.26.4"
                        manager: conda
                        platform: osx-64
                    """);
            assertEquals(List.of(conda("numpy", "1.26.4", "runtime")), parser.parse(file));
        }

        @Test
        void skipsUnnamedEntries() throws IOException, SourceException {
            Path file = write("conda-lock.yml", """
                    package:
                      - version: "1.26.4"
                        manager: conda
                      - name: numpy
                        version: "1.26.4"
                        manager: conda
                    """);
            assertEquals(List.of(conda("numpy", "1.26.4", "runtime")), parser.parse(file));
        }

        @Test
        void unquotesVersion() throws IOException, SourceException {
            Path file = write("conda-lock.yml", """
                    package:
                      - name: numpy
                        version: '1.26.4'
                        manager: conda
                    """);
            assertEquals(List.of(conda("numpy", "1.26.4", "runtime")), parser.parse(file));
        }
    }

    @Nested
    class Boundaries {

        @Test
        void emptyFileReturnsEmptyList() throws IOException, SourceException {
            assertEquals(List.of(), parser.parse(write("environment.yml", "")));
        }

        @Test
        void missingFileThrowsFileNotFound() {
            SourceException error = assertThrows(SourceException.class,
                    () -> parser.parse(tempDir.resolve("absent.yml")));
            assertEquals(SourceException.Code.FILE_NOT_FOUND, error.code());
        }

        @Test
        void invalidEntryReportsLine() throws IOException {
            Path file = write("environment.yml", """
                    dependencies:
                      - numpy=1.26.4
                      - @@@invalid@@@
                    """);
            SourceException error = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
            assertEquals(3, error.line());
        }

        @Test
        void duplicateNameReportsLine() throws IOException {
            Path file = write("environment.yml", """
                    dependencies:
                      - numpy=1.26.4
                      - numpy=1.26.5
                    """);
            SourceException error = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
            assertEquals(3, error.line());
        }
    }

    @Nested
    class Samples {

        @Test
        void demoEnvironment() throws SourceException {
            List<Dependency> dependencies = parser.parse(
                    SAMPLES.resolve("demo-python").resolve("environment.yml"));
            assertEquals(List.of(
                    conda("python", "3.11", "runtime"),
                    conda("numpy", "1.26.4", "runtime"),
                    conda("pandas", ">=2.1", "runtime"),
                    conda("scipy", "1.11.0", "runtime"),
                    python("requests", "2.31.0", "runtime"),
                    python("flask", ">=2.2,<3.0", "runtime")), dependencies);
        }

        @Test
        void demoCondaLock() throws SourceException {
            List<Dependency> dependencies = parser.parse(
                    SAMPLES.resolve("demo-python").resolve("conda-lock.yml"));
            assertEquals(List.of(
                    conda("numpy", "1.26.4", "runtime"),
                    python("requests", "2.31.0", "runtime")), dependencies);
        }
    }
}

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
 * uv 扩展契约测试：只依赖 licenselens-spi 与 JUnit，不引用 core 任何类。
 * <p>
 * 覆盖：SPI 契约与 ServiceLoader 注册、pyproject.toml 的 PEP 621 / PEP 735 子集、
 * uv.lock 的包与 dev 分组、字段填充、声明顺序，以及 CONTRACT.md 中
 * FILE_NOT_FOUND / PARSE_ERROR 路径。
 */
class UvManifestParserTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    private final UvManifestParser parser = new UvManifestParser();

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Dependency dep(String name, String version, String scope) {
        return new Dependency("python", name, version, scope);
    }

    @Nested
    class SpiContract {

        @Test
        void idIsPythonUv() {
            assertEquals("python-uv", parser.id());
        }

        @Test
        void supportsUvLock() {
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "uv.lock", "version = 1")));
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "UV.LOCK", "")));
        }

        @Test
        void supportsPyprojectToml() {
            assertTrue(parser.supports(new ManifestRef(
                    Path.of("x"), "pyproject.toml", "[project]")));
        }

        @Test
        void rejectsOtherManifests() {
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "requirements.txt", "")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "package-lock.json", "")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "pom.xml", "")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "pyproject.toml.bak", "")));
            assertFalse(parser.supports(null));
        }

        @Test
        void discoveredByServiceLoader() {
            Set<String> ids = new HashSet<>();
            for (DependencySource source : ServiceLoader.load(DependencySource.class)) {
                ids.add(source.id());
            }
            assertTrue(ids.contains("python"));
            assertTrue(ids.contains("python-uv"));
        }
    }

    @Nested
    class Pyproject {

        @Test
        void parsesProjectDependenciesInOrder() throws IOException, SourceException {
            Path file = write("pyproject.toml", String.join("\n",
                    "[project]",
                    "name = \"demo\"",
                    "dependencies = [",
                    "    \"requests==2.31.0\",",
                    "    \"flask>=2.2,<3.0\",",
                    "    \"numpy\",",
                    "]"));

            assertEquals(List.of(
                    dep("requests", "2.31.0", "runtime"),
                    dep("flask", ">=2.2,<3.0", "runtime"),
                    dep("numpy", "", "runtime")), parser.parse(file));
        }

        @Test
        void parsesSingleLineArrayWithComments() throws IOException, SourceException {
            Path file = write("pyproject.toml", String.join("\n",
                    "[project]",
                    "dependencies = [\"requests>=2\", \"flask\"]  # 运行时依赖"));

            assertEquals(List.of(
                    dep("requests", ">=2", "runtime"),
                    dep("flask", "", "runtime")), parser.parse(file));
        }

        @Test
        void dropsExtrasAndEnvironmentMarkers() throws IOException, SourceException {
            Path file = write("pyproject.toml", String.join("\n",
                    "[project]",
                    "dependencies = [",
                    "    \"requests[socks]==2.31.0 ; python_version >= \\\"3.9\\\"\",",
                    "    \"uvicorn[standard] @ https://example.com/uvicorn.whl\",",
                    "]"));

            assertEquals(List.of(
                    dep("requests", "2.31.0", "runtime"),
                    dep("uvicorn", "https://example.com/uvicorn.whl", "runtime")), parser.parse(file));
        }

        @Test
        void dependencyGroupsAreDev() throws IOException, SourceException {
            Path file = write("pyproject.toml", String.join("\n",
                    "[dependency-groups]",
                    "dev = [",
                    "    \"pytest>=7.0\",",
                    "    \"ruff\",",
                    "]"));

            assertEquals(List.of(
                    dep("pytest", ">=7.0", "dev"),
                    dep("ruff", "", "dev")), parser.parse(file));
        }

        @Test
        void optionalDependenciesAreRuntime() throws IOException, SourceException {
            Path file = write("pyproject.toml", String.join("\n",
                    "[project.optional-dependencies]",
                    "test = [\"pytest==7.4.0\"]"));

            assertEquals(List.of(dep("pytest", "7.4.0", "runtime")), parser.parse(file));
        }

        @Test
        void ignoresUnrelatedTables() throws IOException, SourceException {
            Path file = write("pyproject.toml", String.join("\n",
                    "[build-system]",
                    "requires = [\"setuptools>=61\"]",
                    "",
                    "[tool.ruff]",
                    "line-length = 120"));

            assertTrue(parser.parse(file).isEmpty());
        }
    }

    @Nested
    class UvLock {

        @Test
        void parsesAllPackagesExceptRoot() throws IOException, SourceException {
            Path file = write("uv.lock", String.join("\n",
                    "version = 1",
                    "",
                    "[[package]]",
                    "name = \"demo\"",
                    "version = \"0.1.0\"",
                    "source = { virtual = \".\" }",
                    "",
                    "[[package]]",
                    "name = \"requests\"",
                    "version = \"2.31.0\"",
                    "source = { registry = \"https://pypi.org/simple\" }",
                    "",
                    "[[package]]",
                    "name = \"urllib3\"",
                    "version = \"2.0.7\"",
                    "source = { registry = \"https://pypi.org/simple\" }"));

            assertEquals(List.of(
                    dep("requests", "2.31.0", "runtime"),
                    dep("urllib3", "2.0.7", "runtime")), parser.parse(file));
        }

        @Test
        void devDependenciesGroupIsDevScope() throws IOException, SourceException {
            Path file = write("uv.lock", String.join("\n",
                    "[[package]]",
                    "name = \"demo\"",
                    "version = \"0.1.0\"",
                    "source = { virtual = \".\" }",
                    "",
                    "[[package]]",
                    "name = \"requests\"",
                    "version = \"2.31.0\"",
                    "",
                    "[package.dev-dependencies]",
                    "dev = [",
                    "    { name = \"pytest\" },",
                    "]",
                    "",
                    "[[package]]",
                    "name = \"pytest\"",
                    "version = \"7.4.0\""));

            assertEquals(List.of(
                    dep("requests", "2.31.0", "runtime"),
                    dep("pytest", "7.4.0", "dev")), parser.parse(file));
        }

        @Test
        void keepsFirstVersionWhenPackageRepeats() throws IOException, SourceException {
            Path file = write("uv.lock", String.join("\n",
                    "[[package]]",
                    "name = \"requests\"",
                    "version = \"2.31.0\"",
                    "",
                    "[[package]]",
                    "name = \"requests\"",
                    "version = \"2.32.0\""));

            assertEquals(List.of(dep("requests", "2.31.0", "runtime")), parser.parse(file));
        }
    }

    @Nested
    class Boundaries {

        @Test
        void emptyPyprojectReturnsEmptyList() throws IOException, SourceException {
            Path file = write("pyproject.toml", "[project]\nname = \"demo\"\n");
            assertTrue(parser.parse(file).isEmpty());
        }

        @Test
        void missingFileIsFileNotFound() {
            SourceException error = assertThrows(SourceException.class,
                    () -> parser.parse(tempDir.resolve("absent.toml")));
            assertEquals(SourceException.Code.FILE_NOT_FOUND, error.code());
        }

        @Test
        void invalidRequirementIsParseErrorWithLine() throws IOException {
            Path file = write("pyproject.toml", String.join("\n",
                    "[project]",
                    "dependencies = [",
                    "    \"requests==2.31.0\",",
                    "    \"not a valid requirement!\",",
                    "]"));

            SourceException error = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
            assertEquals(4, error.line());
        }

        @Test
        void unterminatedArrayIsParseError() throws IOException {
            Path file = write("pyproject.toml", String.join("\n",
                    "[project]",
                    "dependencies = [",
                    "    \"requests==2.31.0\","));

            SourceException error = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
            assertEquals(2, error.line());
        }

        @Test
        void duplicateDependencyIsParseError() throws IOException {
            Path file = write("pyproject.toml", String.join("\n",
                    "[project]",
                    "dependencies = [",
                    "    \"requests==2.31.0\",",
                    "    \"Requests==2.32.0\",",
                    "]"));

            SourceException error = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, error.code());
            assertEquals(4, error.line());
        }
    }

    @Nested
    class Samples {

        @Test
        void parsesDemoPyproject() throws SourceException {
            List<Dependency> dependencies = parser.parse(
                    SAMPLES.resolve("demo-python").resolve("pyproject.toml"));

            assertEquals(List.of(
                    dep("requests", "2.31.0", "runtime"),
                    dep("flask", ">=2.2,<3.0", "runtime"),
                    dep("numpy", "1.26.4", "runtime"),
                    dep("pytest", ">=7.0", "dev")), dependencies);
        }

        @Test
        void parsesDemoUvLock() throws SourceException {
            List<Dependency> dependencies = parser.parse(
                    SAMPLES.resolve("demo-python").resolve("uv.lock"));

            assertEquals(List.of(
                    dep("requests", "2.31.0", "runtime"),
                    dep("flask", "2.2.5", "runtime"),
                    dep("numpy", "1.26.4", "runtime"),
                    dep("pytest", "7.4.0", "dev")), dependencies);
        }
    }
}

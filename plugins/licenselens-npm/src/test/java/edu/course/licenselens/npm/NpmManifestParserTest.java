package edu.course.licenselens.npm;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T9 扩展契约测试：只依赖 licenselens-spi 与 JUnit，不引用 core 任何类。
 * <p>
 * 覆盖：接口方法契约、ServiceLoader 注册、三种 lockfile 格式、字段填充、
 * 声明顺序、边界输入，以及 CONTRACT.md 中 FILE_NOT_FOUND / PARSE_ERROR / UNSUPPORTED_FORMAT 三条错误路径。
 */
class NpmManifestParserTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    private final NpmManifestParser parser = new NpmManifestParser();

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Dependency dep(String name, String version, String scope) {
        return new Dependency("npm", name, version, scope);
    }

    @Nested
    class SpiContract {

        @Test
        void idIsNpm() {
            assertEquals("npm", parser.id());
        }

        @Test
        void supportsPackageLockFileName() {
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "package-lock.json", "")));
        }

        @Test
        void supportsIsCaseInsensitive() {
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "PACKAGE-LOCK.JSON", "{")));
        }

        @Test
        void supportsUsesFileNameNotFirstLine() {
            // 首行没有 npm 特征时也不能误判：文件名才是判据
            assertTrue(parser.supports(new ManifestRef(Path.of("x"), "package-lock.json", "<project")));
        }

        @Test
        void rejectsOtherManifestNames() {
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "package.json", "{")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "npm-shrinkwrap.json", "{")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "pom.xml", "<project")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "package-lock.json.bak", "{")));
        }

        @Test
        void rejectsNullRefAndNullFileName() {
            assertFalse(parser.supports(null));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), null, "{")));
        }

        @Test
        void serviceLoaderDiscoversTheParser() {
            boolean found = ServiceLoader.load(DependencySource.class).stream()
                    .anyMatch(provider -> provider.type() == NpmManifestParser.class
                            && "npm".equals(provider.get().id()));
            assertTrue(found, "META-INF/services 必须声明 NpmManifestParser");
        }

        @Test
        void pomDoesNotDependOnCore() throws IOException {
            String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
            assertTrue(pom.contains("<artifactId>licenselens-spi</artifactId>"));
            assertFalse(pom.contains("<artifactId>licenselens-core</artifactId>"),
                    "扩展铁律：pom 不得声明 core 依赖");
        }
    }

    @Nested
    class LockfileV1 {

        @Test
        void parsesSharedV1Sample() throws Exception {
            List<Dependency> actual = parser.parse(SAMPLES.resolve("demo-npm/package-lock.v1.json"));
            assertEquals(List.of(
                    dep("lodash", "4.17.21", "runtime"),
                    dep("express", "4.18.2", "runtime"),
                    dep("jest", "29.7.0", "dev"),
                    dep("@types/node", "20.11.0", "dev")
            ), actual);
        }

        @Test
        void nestedTransitiveDependenciesAreNotReturned() throws Exception {
            List<Dependency> actual = parser.parse(SAMPLES.resolve("demo-npm/package-lock.v1.json"));
            assertTrue(actual.stream().noneMatch(d -> d.name().equals("accepts")),
                    "嵌套在 express 下的 accepts 是传递依赖，不应出现在直接依赖中");
        }

        @Test
        void emptyDependenciesReturnsEmptyList() throws Exception {
            Path file = write("empty-v1.json", """
                    {"name": "x", "lockfileVersion": 1, "dependencies": {}}
                    """);
            List<Dependency> actual = parser.parse(file);
            assertNotNull(actual);
            assertTrue(actual.isEmpty());
        }

        @Test
        void devFlagTrueMapsToDevScope() throws Exception {
            Path file = write("dev-flags.json", """
                    {
                      "lockfileVersion": 1,
                      "dependencies": {
                        "a": {"version": "1.0.0"},
                        "b": {"version": "2.0.0", "dev": true},
                        "c": {"version": "3.0.0", "dev": false}
                      }
                    }
                    """);
            assertEquals(List.of(
                    dep("a", "1.0.0", "runtime"),
                    dep("b", "2.0.0", "dev"),
                    dep("c", "3.0.0", "runtime")
            ), parser.parse(file));
        }

        @Test
        void missingVersionInEntryIsParseErrorWithLine() throws Exception {
            Path file = write("no-version.json", """
                    {
                      "name": "x",
                      "lockfileVersion": 1,
                      "dependencies": {
                        "foo": {}
                      }
                    }
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(5, e.line());
        }
    }

    @Nested
    class LockfileV2 {

        @Test
        void parsesSharedV2Sample() throws Exception {
            assertEquals(List.of(
                    dep("lodash", "4.17.21", "runtime"),
                    dep("express", "4.18.2", "runtime"),
                    dep("jest", "29.7.0", "dev")
            ), parser.parse(SAMPLES.resolve("demo-npm/package-lock.v2.json")));
        }

        @Test
        void onlyRootPackageDeclarationsAreDirectDependencies() throws Exception {
            Path file = write("v2-transitive.json", """
                    {
                      "lockfileVersion": 2,
                      "packages": {
                        "": {"dependencies": {"a": "^1.0.0"}},
                        "node_modules/a": {"version": "1.0.0"},
                        "node_modules/a/node_modules/b": {"version": "2.0.0"}
                      }
                    }
                    """);
            assertEquals(List.of(dep("a", "1.0.0", "runtime")), parser.parse(file));
        }

        @Test
        void fallsBackToTopLevelDependenciesWhenRootPackageMissing() throws Exception {
            Path file = write("v2-no-root.json", """
                    {
                      "lockfileVersion": 2,
                      "packages": {"node_modules/a": {"version": "1.0.0"}},
                      "dependencies": {
                        "a": {"version": "1.0.0", "dev": true}
                      }
                    }
                    """);
            assertEquals(List.of(dep("a", "1.0.0", "dev")), parser.parse(file));
        }

        @Test
        void emptyRootDependenciesWinsOverTopLevelList() throws Exception {
            Path file = write("v2-empty-root.json", """
                    {
                      "lockfileVersion": 2,
                      "packages": {
                        "": {"dependencies": {}},
                        "node_modules/a": {"version": "1.0.0"}
                      },
                      "dependencies": {"a": {"version": "1.0.0"}}
                    }
                    """);
            assertTrue(parser.parse(file).isEmpty(),
                    "根包没声明依赖时，顶层 dependencies 中的提升包不算直接依赖");
        }

        @Test
        void runtimeWinsWhenAlsoListedAsDevDependency() throws Exception {
            Path file = write("v2-both-scopes.json", """
                    {
                      "lockfileVersion": 2,
                      "packages": {
                        "": {
                          "dependencies": {"a": "^1.0.0"},
                          "devDependencies": {"a": "^2.0.0"}
                        },
                        "node_modules/a": {"version": "1.5.0"}
                      }
                    }
                    """);
            assertEquals(List.of(dep("a", "1.5.0", "runtime")), parser.parse(file));
        }

        @Test
        void versionFallsBackToDeclaredRangeWhenLockEntryMissing() throws Exception {
            Path file = write("v2-no-entry.json", """
                    {
                      "lockfileVersion": 2,
                      "packages": {"": {"dependencies": {"a": "^1.2.3"}}}
                    }
                    """);
            assertEquals(List.of(dep("a", "^1.2.3", "runtime")), parser.parse(file));
        }

        @Test
        void nonStringDeclarationIsParseError() throws Exception {
            Path file = write("v2-bad-declaration.json", """
                    {
                      "lockfileVersion": 2,
                      "packages": {
                        "": {
                          "dependencies": {
                            "a": 1
                          }
                        }
                      }
                    }
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertTrue(e.line() >= 1);
        }
    }

    @Nested
    class LockfileV3 {

        @Test
        void parsesSharedV3Sample() throws Exception {
            assertEquals(List.of(dep("lodash", "4.17.21", "runtime")),
                    parser.parse(SAMPLES.resolve("demo-npm/package-lock.json")));
        }

        @Test
        void resolvesScopedPackageVersion() throws Exception {
            Path file = write("v3-scoped.json", """
                    {
                      "lockfileVersion": 3,
                      "packages": {
                        "": {"devDependencies": {"@types/node": "^20.0.0"}},
                        "node_modules/@types/node": {"version": "20.11.0"}
                      }
                    }
                    """);
            assertEquals(List.of(dep("@types/node", "20.11.0", "dev")), parser.parse(file));
        }

        @Test
        void preservesDeclarationOrder() throws Exception {
            Path file = write("v3-order.json", """
                    {
                      "lockfileVersion": 3,
                      "packages": {
                        "": {"dependencies": {"c": "^1.0.0", "a": "^2.0.0", "b": "^3.0.0"}}
                      }
                    }
                    """);
            assertEquals(List.of("c", "a", "b"),
                    parser.parse(file).stream().map(Dependency::name).toList());
        }

        @Test
        void decodesEscapedUnicodeInPackageNames() throws Exception {
            Path file = write("v3-unicode.json", """
                    {
                      "lockfileVersion": 3,
                      "packages": {
                        "": {"dependencies": {"\\u0061bc": "^1.0.0"}}
                      }
                    }
                    """);
            assertEquals(List.of(dep("abc", "^1.0.0", "runtime")), parser.parse(file));
        }

        @Test
        void duplicateJsonKeysKeepLastValue() throws Exception {
            Path file = write("v3-duplicate-keys.json", """
                    {
                      "lockfileVersion": 3,
                      "packages": {"": {"dependencies": {"a": "^9.9.9"}}},
                      "packages": {"": {"dependencies": {"b": "^1.0.0"}}}
                    }
                    """);
            assertEquals(List.of(dep("b", "^1.0.0", "runtime")), parser.parse(file));
        }

        @Test
        void bomAndCrLfAreAccepted() throws Exception {
            Path file = write("v3-bom.json",
                    "\uFEFF{\r\n  \"lockfileVersion\": 3,\r\n"
                            + "  \"packages\": {\r\n"
                            + "    \"\": {\"dependencies\": {\"a\": \"^1.0.0\"}}\r\n"
                            + "  }\r\n}\r\n");
            assertEquals(List.of(dep("a", "^1.0.0", "runtime")), parser.parse(file));
        }
    }

    @Nested
    class ErrorContract {

        @Test
        void missingFileIsFileNotFound() {
            SourceException e = assertThrows(SourceException.class,
                    () -> parser.parse(tempDir.resolve("not-there.json")));
            assertEquals(SourceException.Code.FILE_NOT_FOUND, e.code());
            assertEquals(-1, e.line());
        }

        @Test
        void nullPathIsFileNotFound() {
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(null));
            assertEquals(SourceException.Code.FILE_NOT_FOUND, e.code());
        }

        @Test
        void directoryIsFileNotFound() {
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(tempDir));
            assertEquals(SourceException.Code.FILE_NOT_FOUND, e.code());
        }

        @Test
        void sharedBadLockReportsParseErrorWithLineNumber() {
            SourceException e = assertThrows(SourceException.class,
                    () -> parser.parse(SAMPLES.resolve("edge/bad-lock.json")));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(8, e.line(), "错误应定位到样例中的 <!-- 故意不闭合 ... --> 所在行");
        }

        @Test
        void emptyFileIsParseError() throws Exception {
            Path file = write("empty-file.json", "");
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(1, e.line());
        }

        @Test
        void rootArrayIsParseError() throws Exception {
            Path file = write("root-array.json", "[]");
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
        }

        @Test
        void missingBothStructuresIsUnsupportedFormat() throws Exception {
            Path file = write("unsupported.json", """
                    {"name": "x", "version": "1.0.0"}
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.UNSUPPORTED_FORMAT, e.code());
            assertEquals(-1, e.line());
        }

        @Test
        void trailingCommaIsParseError() throws Exception {
            Path file = write("trailing-comma.json", """
                    {"lockfileVersion": 3, "packages": {"": {"dependencies": {"a": "^1",}}}}
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
        }

        @Test
        void trailingGarbageIsParseError() throws Exception {
            Path file = write("trailing-garbage.json", """
                    {"dependencies": {}} oops
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
        }

        @Test
        void invalidEscapeIsParseError() throws Exception {
            Path file = write("invalid-escape.json", """
                    {"dependencies": {"a": {"version": "bad\\q"}}}
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
        }

        @Test
        void unclosedStringIsParseError() throws Exception {
            Path file = write("unclosed-string.json", """
                    {"dependencies": {"a": "unterminated}}
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
        }

        @Test
        void invalidUtf8IsParseError() throws Exception {
            Path file = tempDir.resolve("invalid-utf8.json");
            Files.write(file, new byte[]{'{', (byte) 0xFF, '}'});
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
        }

        @Test
        void crLfLineNumberIsCountedCorrectly() throws Exception {
            Path file = write("crlf-line.json",
                    "{\r\n  \"dependencies\": {\r\n    \"a\": {\r\n"
                            + "      \"version\" \"1.0.0\"\r\n    }\r\n  }\r\n}");
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(4, e.line());
        }
    }
}

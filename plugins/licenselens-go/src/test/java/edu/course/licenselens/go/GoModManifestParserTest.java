package edu.course.licenselens.go;

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
 * Go 扩展契约测试：只依赖 licenselens-spi 与 JUnit，不引用 core 任何类。
 * <p>
 * 覆盖：SPI 契约、ServiceLoader 注册、块式/单行 require、字段填充、声明顺序、
 * ignore/replace 指令隔离，以及 CONTRACT.md 中 FILE_NOT_FOUND / PARSE_ERROR 路径。
 */
class GoModManifestParserTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    private final GoModManifestParser parser = new GoModManifestParser();

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Dependency dep(String name, String version, String scope) {
        return new Dependency("go", name, version, scope);
    }

    @Nested
    class SpiContract {

        @Test
        void idIsGo() {
            assertEquals("go", parser.id());
        }

        @Test
        void supportsGoModFileName() {
            assertTrue(parser.supports(new ManifestRef(
                    Path.of("x"), "go.mod", "module github.com/example/demo")));
        }

        @Test
        void supportsIsCaseInsensitive() {
            assertTrue(parser.supports(new ManifestRef(
                    Path.of("x"), "GO.MOD", "module github.com/example/demo")));
        }

        @Test
        void supportsChecksModuleFirstLine() {
            assertTrue(parser.supports(new ManifestRef(
                    Path.of("x"), "go.mod", "module\tgithub.com/example/demo")));
            assertFalse(parser.supports(new ManifestRef(
                    Path.of("x"), "go.mod", "go 1.21")));
            assertFalse(parser.supports(new ManifestRef(
                    Path.of("x"), "go.mod", "module")));
            assertFalse(parser.supports(new ManifestRef(
                    Path.of("x"), "go.mod", "modulegithub.com/example/demo")));
            assertFalse(parser.supports(new ManifestRef(
                    Path.of("x"), "go.mod", "// module github.com/example/demo")));
        }

        @Test
        void supportsAcceptsBomAndLeadingWhitespaceInFirstLine() {
            assertTrue(parser.supports(new ManifestRef(
                    Path.of("x"), "go.mod", "\uFEFF  module github.com/example/demo")));
        }

        @Test
        void rejectsOtherManifestNames() {
            assertFalse(parser.supports(new ManifestRef(
                    Path.of("x"), "go.sum", "module github.com/example/demo")));
            assertFalse(parser.supports(new ManifestRef(
                    Path.of("x"), "pom.xml", "<project")));
            assertFalse(parser.supports(new ManifestRef(
                    Path.of("x"), "package-lock.json", "{")));
            assertFalse(parser.supports(new ManifestRef(
                    Path.of("x"), "go.mod.bak", "module github.com/example/demo")));
        }

        @Test
        void rejectsNullRefFileNameAndFirstLine() {
            assertFalse(parser.supports(null));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), null, "module example.com/m")));
            assertFalse(parser.supports(new ManifestRef(Path.of("x"), "go.mod", null)));
        }

        @Test
        void serviceLoaderDiscoversTheParser() {
            boolean found = ServiceLoader.load(DependencySource.class).stream()
                    .anyMatch(provider -> provider.type() == GoModManifestParser.class
                            && "go".equals(provider.get().id()));
            assertTrue(found, "META-INF/services 必须声明 GoModManifestParser");
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
    class BlockRequire {

        @Test
        void parsesSharedGoModSample() throws Exception {
            assertEquals(List.of(
                    dep("github.com/go-redis/redis/v8", "v8.11.5", "runtime"),
                    dep("github.com/google/uuid", "v1.6.0", "runtime"),
                    dep("github.com/stretchr/testify", "v1.12.1", "runtime"),
                    dep("github.com/cespare/xxhash/v2", "v2.1.2", "indirect"),
                    dep("github.com/dgryski/go-rendezvous",
                            "v0.0.0-20200823014737-9f7001d12a5f", "indirect"),
                    dep("go.yaml.in/yaml/v3", "v3.0.5", "indirect")
            ), parser.parse(SAMPLES.resolve("demo-go/go.mod")));
        }

        @Test
        void indirectScopeComesFromTrailingComment() throws Exception {
            Path file = write("indirect.mod", """
                    module example.com/m

                    go 1.21

                    require (
                        github.com/a/a v1.0.0
                        github.com/b/b v2.0.0 // indirect
                    )
                    """);
            assertEquals(List.of(
                    dep("github.com/a/a", "v1.0.0", "runtime"),
                    dep("github.com/b/b", "v2.0.0", "indirect")
            ), parser.parse(file));
        }

        @Test
        void preservesDeclarationOrder() throws Exception {
            Path file = write("order.mod", """
                    module example.com/m

                    require (
                        github.com/c/c v3.0.0
                        github.com/a/a v1.0.0
                        github.com/b/b v2.0.0
                    )
                    """);
            assertEquals(List.of("github.com/c/c", "github.com/a/a", "github.com/b/b"),
                    parser.parse(file).stream().map(Dependency::name).toList());
        }

        @Test
        void keepsMajorSuffixAndPseudoVersion() throws Exception {
            Path file = write("versions.mod", """
                    module example.com/m

                    require (
                        github.com/gin-gonic/gin v1.9.1
                        example.com/lib/v2 v2.3.4+incompatible
                        example.com/pseudo v0.0.0-20240101000000-abcdefabcdef // indirect
                    )
                    """);
            assertEquals(List.of(
                    dep("github.com/gin-gonic/gin", "v1.9.1", "runtime"),
                    dep("example.com/lib/v2", "v2.3.4+incompatible", "runtime"),
                    dep("example.com/pseudo", "v0.0.0-20240101000000-abcdefabcdef", "indirect")
            ), parser.parse(file));
        }

        @Test
        void emptyRequireBlockReturnsEmptyList() throws Exception {
            Path file = write("empty-block.mod", """
                    module example.com/m

                    require ()
                    """);
            List<Dependency> actual = parser.parse(file);
            assertNotNull(actual);
            assertTrue(actual.isEmpty());
        }

        @Test
        void blankLinesAndCommentsAreIgnored() throws Exception {
            Path file = write("comments.mod", """
                    module example.com/m

                    // 顶层注释
                    require (
                        // 块内注释

                        github.com/a/a v1.0.0 // 行尾注释

                    )
                    """);
            assertEquals(List.of(dep("github.com/a/a", "v1.0.0", "runtime")),
                    parser.parse(file));
        }

        @Test
        void openingLineTrailingCommentIsIgnored() throws Exception {
            Path file = write("opening-comment.mod", """
                    module example.com/m

                    require ( // 直接依赖
                        github.com/a/a v1.0.0
                    )
                    """);
            assertEquals(List.of(dep("github.com/a/a", "v1.0.0", "runtime")),
                    parser.parse(file));
        }

        @Test
        void ignoredReplaceBlockDoesNotLeakRequires() throws Exception {
            Path file = write("replace-block.mod", """
                    module example.com/m

                    replace (
                        github.com/a/a v1.0.0 => github.com/fork/a v1.0.1
                    )

                    require github.com/c/c v1.2.3
                    """);
            assertEquals(List.of(dep("github.com/c/c", "v1.2.3", "runtime")),
                    parser.parse(file));
        }
    }

    @Nested
    class SingleRequire {

        @Test
        void parsesSharedSingleRequireSample() throws Exception {
            assertEquals(List.of(
                    dep("github.com/gin-gonic/gin", "v1.9.1", "runtime"),
                    dep("github.com/go-redis/redis/v8", "v8.11.5", "indirect")
            ), parser.parse(SAMPLES.resolve("demo-go/go.single-require.mod")));
        }

        @Test
        void multipleSingleRequiresKeepOrder() throws Exception {
            Path file = write("single-order.mod", """
                    module example.com/m

                    require github.com/c/c v3.0.0
                    require github.com/a/a v1.0.0
                    require github.com/b/b v2.0.0
                    """);
            assertEquals(List.of("github.com/c/c", "github.com/a/a", "github.com/b/b"),
                    parser.parse(file).stream().map(Dependency::name).toList());
        }

        @Test
        void indirectSuffixWithReasonCountsAsIndirect() throws Exception {
            Path file = write("single-indirect.mod", """
                    module example.com/m

                    require github.com/a/a v1.0.0 // indirect; pinned by policy
                    """);
            assertEquals(List.of(dep("github.com/a/a", "v1.0.0", "indirect")),
                    parser.parse(file));
        }

        @Test
        void inlineCommentWithoutIndirectRemainsRuntime() throws Exception {
            Path file = write("single-comment.mod", """
                    module example.com/m

                    require github.com/a/a v1.0.0 // pinned by hand
                    """);
            assertEquals(List.of(dep("github.com/a/a", "v1.0.0", "runtime")),
                    parser.parse(file));
        }

        @Test
        void leadingWhitespaceAndTabsAreAccepted() throws Exception {
            Path file = write("single-whitespace.mod", """
                    module example.com/m

                      require\tgithub.com/a/a\tv1.0.0
                    """);
            assertEquals(List.of(dep("github.com/a/a", "v1.0.0", "runtime")),
                    parser.parse(file));
        }
    }

    @Nested
    class Directives {

        @Test
        void ignoresGoToolchainExcludeAndRetractDirectives() throws Exception {
            Path file = write("directives.mod", """
                    module example.com/m

                    go 1.21
                    toolchain go1.22.0
                    exclude github.com/x/x v1.0.0
                    retract v1.0.0

                    require github.com/a/a v1.0.0
                    """);
            assertEquals(List.of(dep("github.com/a/a", "v1.0.0", "runtime")),
                    parser.parse(file));
        }

        @Test
        void replaceSingleLineIsIgnored() throws Exception {
            Path file = write("replace-single.mod", """
                    module example.com/m

                    replace github.com/a/a => github.com/b/b v1.0.0
                    require github.com/c/c v1.0.0
                    """);
            assertEquals(List.of(dep("github.com/c/c", "v1.0.0", "runtime")),
                    parser.parse(file));
        }

        @Test
        void excludeBlockIsIgnored() throws Exception {
            Path file = write("exclude-block.mod", """
                    module example.com/m

                    exclude (
                        github.com/a/a v1.0.0
                    )
                    require github.com/c/c v1.0.0
                    """);
            assertEquals(List.of(dep("github.com/c/c", "v1.0.0", "runtime")),
                    parser.parse(file));
        }

        @Test
        void moduleDirectiveWithInlineCommentIsAccepted() throws Exception {
            Path file = write("module-comment.mod", """
                    module example.com/m // 示例模块

                    require github.com/a/a v1.0.0
                    """);
            assertEquals(List.of(dep("github.com/a/a", "v1.0.0", "runtime")),
                    parser.parse(file));
        }
    }

    @Nested
    class ErrorContract {

        @Test
        void missingFileIsFileNotFound() {
            SourceException e = assertThrows(SourceException.class,
                    () -> parser.parse(tempDir.resolve("not-there.mod")));
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
        void sharedBadGoModReportsBlockStartLine() {
            SourceException e = assertThrows(SourceException.class,
                    () -> parser.parse(SAMPLES.resolve("edge/bad-go.mod")));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(5, e.line(), "未闭合块应定位到 require ( 所在行");
        }

        @Test
        void emptyFileIsParseErrorWithLineOne() throws Exception {
            Path file = write("empty.mod", "");
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(1, e.line());
        }

        @Test
        void missingModuleDirectiveIsParseError() throws Exception {
            Path file = write("no-module.mod", """
                    go 1.21

                    require github.com/a/a v1.0.0
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(1, e.line());
        }

        @Test
        void unclosedRequireBlockReportsOpeningLine() throws Exception {
            Path file = write("unclosed.mod", """
                    module example.com/m

                    require (
                        github.com/a/a v1.0.0
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(3, e.line());
        }

        @Test
        void requireEntryWithoutVersionIsParseErrorWithLine() throws Exception {
            Path file = write("no-version.mod", """
                    module example.com/m

                    require (
                        github.com/a/a
                    )
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(4, e.line());
        }

        @Test
        void requireEntryWithTooManyTokensIsParseError() throws Exception {
            Path file = write("too-many.mod", """
                    module example.com/m

                    require (
                        github.com/a/a v1.0.0 extra
                    )
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(4, e.line());
        }

        @Test
        void duplicateRequireIsParseError() throws Exception {
            Path file = write("duplicate.mod", """
                    module example.com/m

                    require (
                        github.com/a/a v1.0.0
                        github.com/a/a v2.0.0
                    )
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(5, e.line());
        }

        @Test
        void strayClosingParenthesisIsParseError() throws Exception {
            Path file = write("stray.mod", """
                    module example.com/m
                    )
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(2, e.line());
        }

        @Test
        void invalidUtf8IsParseError() throws Exception {
            Path file = tempDir.resolve("invalid-utf8.mod");
            Files.write(file, new byte[]{'m', 'o', 'd', (byte) 0xFF});
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(1, e.line());
        }

        @Test
        void crLfLineNumberIsCountedCorrectly() throws Exception {
            Path file = write("crlf.mod",
                    "module example.com/m\r\n"
                            + "require (\r\n"
                            + "  github.com/a/a\r\n"
                            + ")\r\n");
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(3, e.line());
        }

        @Test
        void unclosedIgnoredBlockIsParseError() throws Exception {
            Path file = write("unclosed-replace.mod", """
                    module example.com/m

                    replace (
                        github.com/a/a v1.0.0 => github.com/b/b v1.0.1
                    """);
            SourceException e = assertThrows(SourceException.class, () -> parser.parse(file));
            assertEquals(SourceException.Code.PARSE_ERROR, e.code());
            assertEquals(3, e.line());
        }
    }
}

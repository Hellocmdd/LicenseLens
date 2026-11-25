package edu.course.licenselens.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于装配产物的 CLI 插件集成测试，覆盖 T7 / T8 与插件加载失败降级。
 */
class CliPluginIntegrationTest {

    private static final String NPM_JAR = "licenselens-npm.jar";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("T7：插件 jar 放入插件目录后被自动发现并解析 npm 清单")
    void shouldDiscoverPluginJar() throws Exception {
        Path plugins = pluginsWithNpmJar();

        RunResult result = run(plugins, "t7");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("依赖数: 1"), result.stdout());
        assertTrue(result.report().contains("lodash"), result.report());
        assertTrue(result.report().contains("MIT"), result.report());
    }

    @Test
    @DisplayName("T8：移走插件后无可用解析器，返回退出码 4")
    void shouldReturnNoParserWhenPluginRemoved() throws Exception {
        Path plugins = tempDir.resolve("empty-plugins");
        Files.createDirectories(plugins);

        RunResult result = run(plugins, "t8");

        assertEquals(4, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("未找到可解析该文件的插件"), result.stderr());
        assertFalse(result.stderr().contains("Exception in thread"), result.stderr());
    }

    @Test
    @DisplayName("插件加载失败降级：损坏 jar 告警但正常插件仍生效")
    void shouldSkipCorruptJarAndContinue() throws Exception {
        Path plugins = pluginsWithNpmJar();
        Files.write(plugins.resolve("broken.jar"), "not a zip".getBytes(StandardCharsets.UTF_8));

        RunResult result = run(plugins, "broken");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("broken.jar"), result.stderr());
        assertTrue(result.stdout().contains("依赖数: 1"), result.stdout());
    }

    @Test
    @DisplayName("插件服务声明错误：告警跳过且不影响正常插件")
    void shouldSkipJarWithBrokenService() throws Exception {
        Path plugins = pluginsWithNpmJar();
        Path badJar = plugins.resolve("bad-service.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(badJar))) {
            out.putNextEntry(new JarEntry(
                    "META-INF/services/edu.course.licenselens.spi.DependencySource"));
            out.write("no.such.PluginImpl\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        RunResult result = run(plugins, "bad-service");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("bad-service.jar"), result.stderr());
        assertTrue(result.stdout().contains("依赖数: 1"), result.stdout());
    }

    private Path pluginsWithNpmJar() throws IOException {
        Path plugins = tempDir.resolve("plugins");
        Files.createDirectories(plugins);
        Files.copy(dist().resolve("plugins").resolve(NPM_JAR), plugins.resolve(NPM_JAR));
        return plugins;
    }

    private RunResult run(Path plugins, String caseName) throws IOException, InterruptedException {
        Path stdout = tempDir.resolve(caseName + ".stdout");
        Path stderr = tempDir.resolve(caseName + ".stderr");
        Path report = tempDir.resolve(caseName + ".md");
        Path manifest = dist().resolve("samples/demo-npm/package-lock.json");

        ProcessBuilder builder = new ProcessBuilder(
                "java", "-jar", dist().resolve("licenselens-cli.jar").toString(),
                "scan",
                "--manifest", manifest.toString(),
                "--plugins", plugins.toString(),
                "--out", report.toString()
        );
        builder.environment().put("JAVA_TOOL_OPTIONS",
                "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        builder.directory(dist().toFile());
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());

        int exitCode = builder.start().waitFor();
        return new RunResult(exitCode,
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8),
                Files.exists(report) ? Files.readString(report, StandardCharsets.UTF_8) : "");
    }

    private static Path dist() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(
                dir.resolve("distribution/target/dist/licenselens-cli.jar"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("找不到 distribution/target/dist，请先执行 mvn -B clean verify");
        }
        return dir.resolve("distribution/target/dist");
    }

    private record RunResult(int exitCode, String stdout, String stderr, String report) {
    }
}

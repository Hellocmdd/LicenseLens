package edu.course.licenselens.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI 退出码与报告落盘的端到端测试，覆盖 T1 / T2 / T5 / T6 / T8。
 */
class MainTest {

    @Test
    @DisplayName("T1 正常扫描：退出码 1（存在阻断依赖）并写出报告")
    void shouldReturnBlockedAndWriteReport(@TempDir Path tmp) {
        Path out = tmp.resolve("report.md");

        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("demo-maven/pom.xml").toString(),
                "--out", out.toString()
        });

        assertEquals(ExitCode.BLOCKED, code);
        assertTrue(Files.exists(out), "报告文件应被写出");
    }

    @Test
    @DisplayName("T2 permissive-only：退出码 1")
    void shouldReturnBlockedUnderPermissiveOnly(@TempDir Path tmp) {
        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("demo-maven/pom.xml").toString(),
                "--policy", "permissive-only",
                "--out", tmp.resolve("report.md").toString()
        });

        assertEquals(ExitCode.BLOCKED, code);
    }

    @Test
    @DisplayName("T5 清单不存在：退出码 2")
    void shouldReturnFileNotFound(@TempDir Path tmp) {
        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("edge/does-not-exist.xml").toString(),
                "--out", tmp.resolve("report.md").toString()
        });

        assertEquals(ExitCode.FILE_NOT_FOUND, code);
    }

    @Test
    @DisplayName("T6 清单语法错误：退出码 3")
    void shouldReturnParseError(@TempDir Path tmp) {
        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("edge/bad-pom.xml").toString(),
                "--out", tmp.resolve("report.md").toString()
        });

        assertEquals(ExitCode.PARSE_ERROR, code);
    }

    @Test
    @DisplayName("T8 没有插件目录里的解析器：退出码 4")
    void shouldReturnNoParser(@TempDir Path tmp) throws IOException {
        Path plugins = tmp.resolve("plugins");
        Files.createDirectories(plugins);

        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("demo-npm/package-lock.json").toString(),
                "--plugins", plugins.toString(),
                "--out", tmp.resolve("report.md").toString()
        });

        assertEquals(ExitCode.NO_PARSER, code);
    }

    @Test
    @DisplayName("T3 空清单：退出码 0，报告仍被写出")
    void shouldReturnOkForEmptyManifest(@TempDir Path tmp) {
        Path out = tmp.resolve("report.md");

        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("edge/empty.xml").toString(),
                "--out", out.toString()
        });

        assertEquals(ExitCode.OK, code);
        assertTrue(Files.exists(out));
    }

    @Test
    @DisplayName("参数缺值（--manifest 后面没有内容）应报参数错误，不能抛异常")
    void shouldRejectMissingOptionValue() {
        ExitCode code = Main.run(new String[]{"scan", "--manifest"});

        assertEquals(ExitCode.FILE_NOT_FOUND, code);
    }

    @Test
    @DisplayName("未知参数：报参数错误而不是崩溃")
    void shouldRejectUnknownFlag(@TempDir Path tmp) {
        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("demo-maven/pom.xml").toString(), "--bogus"
        });

        assertEquals(ExitCode.FILE_NOT_FOUND, code);
    }

    @Test
    @DisplayName("未知策略：在扫描前拦下，退出码 2 而不是抛 IllegalArgumentException")
    void shouldRejectUnknownPolicy(@TempDir Path tmp) {
        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("demo-maven/pom.xml").toString(),
                "--policy", "no-such-policy",
                "--out", tmp.resolve("report.md").toString()
        });

        assertEquals(ExitCode.FILE_NOT_FOUND, code);
    }

    @Test
    @DisplayName("插件目录不存在：只告警，扫描照常进行")
    void shouldScanWhenPluginsDirMissing(@TempDir Path tmp) {
        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("demo-maven/pom.xml").toString(),
                "--plugins", tmp.resolve("no-such-dir").toString(),
                "--out", tmp.resolve("report.md").toString()
        });

        assertEquals(ExitCode.BLOCKED, code);
    }

    @Test
    @DisplayName("--format json：写出可解析的 JSON 报告且退出码不变")
    void shouldWriteJsonReport(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("report.json");

        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("demo-maven/pom.xml").toString(),
                "--format", "json", "--out", out.toString()
        });

        assertEquals(ExitCode.BLOCKED, code);
        String content = Files.readString(out);
        assertTrue(content.startsWith("{"), "JSON 报告应以 { 开头");
        assertTrue(content.contains("\"result\": \"BLOCKED\""), "应包含判定结果");
        assertTrue(content.contains("\"policy\": \"campus\""), "应包含策略");
    }

    @Test
    @DisplayName("--format 非法：复用用法错误退出码 2")
    void shouldRejectUnknownFormat(@TempDir Path tmp) {
        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("demo-maven/pom.xml").toString(),
                "--format", "yaml", "--out", tmp.resolve("report.md").toString()
        });

        assertEquals(ExitCode.FILE_NOT_FOUND, code);
    }

    @Test
    @DisplayName("--catalog：外置快照缺失时回退内置快照")
    void shouldFallBackWhenCatalogMissing(@TempDir Path tmp) {
        Path out = tmp.resolve("report.md");

        ExitCode code = Main.run(new String[]{
                "scan", "--manifest", sample("demo-maven/pom.xml").toString(),
                "--catalog", tmp.resolve("missing.json").toString(),
                "--out", out.toString()
        });

        assertEquals(ExitCode.BLOCKED, code);
        assertTrue(Files.exists(out), "报告文件应被写出");
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

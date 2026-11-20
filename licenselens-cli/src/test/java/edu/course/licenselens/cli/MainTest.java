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

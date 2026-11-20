package edu.course.licenselens.core;

import edu.course.licenselens.core.internal.MavenManifestParser;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.Policy;
import edu.course.licenselens.spi.SourceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫描管线端到端测试，覆盖 T1 / T2 / T3 / T5 / T8。
 */
class ScanEngineTest {

    private ScanEngine engine;

    @BeforeEach
    void setUp() {
        List<DependencySource> sources = List.of(new MavenManifestParser());
        List<Policy> policies = List.of(new CampusPolicy(), new PermissiveOnlyPolicy());
        engine = new ScanEngine(new LicenseCatalog(), sources, policies);
    }

    @Test
    @DisplayName("T1 campus：4 条依赖、1 条阻断，报告按风险降序")
    void shouldScanDemoMavenWithCampusPolicy() throws SourceException {
        ScanResult result = engine.scan(sample("demo-maven/pom.xml"), "campus");

        assertEquals(4, result.totalCount());
        assertEquals(1, result.blockedCount());
        assertEquals(1, result.exitCode());

        String report = result.reportContent();
        int mysql = report.indexOf("mysql:mysql-connector-java");
        int junit = report.indexOf("org.junit.jupiter:junit-jupiter");
        int guava = report.indexOf("com.google.guava:guava");
        int commons = report.indexOf("org.apache.commons:commons-lang3");
        assertTrue(mysql > 0, "报告缺少 mysql 行");
        assertTrue(junit > mysql, "GPL 应排在 EPL 之前");
        assertTrue(guava > junit, "EPL 应排在 Apache 之前");
        assertTrue(commons > guava, "同风险内应按名称升序");
    }

    @Test
    @DisplayName("T2 permissive-only：弱 copyleft 也被阻断，阻断数 2")
    void shouldBlockWeakCopyleftUnderPermissiveOnly() throws SourceException {
        ScanResult result = engine.scan(sample("demo-maven/pom.xml"), "permissive-only");

        assertEquals(2, result.blockedCount());
        assertEquals(1, result.exitCode());
        assertTrue(result.reportContent().contains("org.junit.jupiter:junit-jupiter:5.10.2"),
                "报告应包含 junit 行");
        assertTrue(result.reportContent().contains("permissive-only 不允许"),
                "junit 行的理由应来自 permissive-only 策略");
    }

    @Test
    @DisplayName("T3 空清单：0 条依赖、退出码 0")
    void shouldScanEmptyManifest() throws SourceException {
        ScanResult result = engine.scan(sample("edge/empty.xml"), "campus");

        assertEquals(0, result.totalCount());
        assertEquals(0, result.blockedCount());
        assertEquals(0, result.exitCode());
    }

    @Test
    @DisplayName("T5 清单不存在：FILE_NOT_FOUND")
    void shouldFailWhenManifestMissing() {
        SourceException e = assertThrows(SourceException.class,
                () -> engine.scan(sample("edge/does-not-exist.xml"), "campus"));

        assertEquals(SourceException.Code.FILE_NOT_FOUND, e.code());
    }

    @Test
    @DisplayName("T8 没有解析器认领：UNSUPPORTED_FORMAT")
    void shouldFailWhenNoSourceSupports() {
        SourceException e = assertThrows(SourceException.class,
                () -> engine.scan(sample("demo-npm/package-lock.json"), "campus"));

        assertEquals(SourceException.Code.UNSUPPORTED_FORMAT, e.code());
    }

    @Test
    @DisplayName("未知策略 id：抛 IllegalArgumentException")
    void shouldRejectUnknownPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.scan(sample("demo-maven/pom.xml"), "no-such-policy"));
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

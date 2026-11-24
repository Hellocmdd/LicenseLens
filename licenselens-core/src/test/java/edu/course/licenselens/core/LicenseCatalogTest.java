package edu.course.licenselens.core;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Issue #11：跨生态许可证匹配与命中率自检测试。
 */
@DisplayName("LicenseCatalog 跨生态匹配")
class LicenseCatalogTest {

    private LicenseCatalog catalog;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        catalog = new LicenseCatalog();
    }

    // ---- 1. Maven 仍按旧方式命中（纯 name） ----

    @Nested
    @DisplayName("Maven 依赖：纯 name 向后兼容")
    class MavenCompatibility {

        @Test
        @DisplayName("Maven groupId:artifactId 纯 name 查找仍命中")
        void mavenByPureNameStillWorks() {
            LicenseInfo info = catalog.lookup("mysql:mysql-connector-java");
            assertEquals("GPL-2.0", info.spdxId());
            assertEquals(RiskLevel.STRONG_COPYLEFT, info.risk());
        }

        @Test
        @DisplayName("lookup(Dependency) 对 Maven 先查 ecosystem:name，回退纯 name 也命中")
        void mavenByDependencyKeyedFirst() {
            Dependency dep = new Dependency("maven", "mysql:mysql-connector-java", "8.0.33", "runtime");
            LicenseInfo info = catalog.lookup(dep);
            assertEquals("GPL-2.0", info.spdxId());
            assertEquals(RiskLevel.STRONG_COPYLEFT, info.risk());
        }
    }

    // ---- 2. 跨生态 lookup(Dependency) 精确命中 ----

    @Nested
    @DisplayName("跨生态 lookup(Dependency) 精确命中 ecosystem:name")
    class CrossEcosystemHit {

        @Test
        @DisplayName("npm:lodash 精确命中 MIT")
        void npmLodashHit() {
            Dependency dep = new Dependency("npm", "lodash", "4.17.21", "runtime");
            LicenseInfo info = catalog.lookup(dep);
            assertEquals("MIT", info.spdxId());
            assertEquals(RiskLevel.PERMISSIVE, info.risk());
        }

        @Test
        @DisplayName("python:requests 精确命中 Apache-2.0")
        void pythonRequestsHit() {
            Dependency dep = new Dependency("python", "requests", "2.31.0", "runtime");
            LicenseInfo info = catalog.lookup(dep);
            assertEquals("Apache-2.0", info.spdxId());
            assertEquals(RiskLevel.PERMISSIVE, info.risk());
        }

        @Test
        @DisplayName("go:github.com/google/uuid 精确命中 BSD-3-Clause")
        void goGoogleUuidHit() {
            Dependency dep = new Dependency("go", "github.com/google/uuid", "v1.6.0", "runtime");
            LicenseInfo info = catalog.lookup(dep);
            assertEquals("BSD-3-Clause", info.spdxId());
            assertEquals(RiskLevel.PERMISSIVE, info.risk());
        }
    }

    // ---- 3. 回退机制 ----

    @Nested
    @DisplayName("回退机制：ecosystem:name 未命中 → 纯 name")
    class Fallback {

        @Test
        @DisplayName("纯 name lookup 仍可使用（向后兼容）")
        void pureNameLookupStillAvailable() {
            LicenseInfo info = catalog.lookup("org.json:json");
            assertEquals("JSON", info.spdxId());
        }

        @Test
        @DisplayName("未知依赖 → UNKNOWN")
        void unknownDepFallsBackToUnknown() {
            Dependency dep = new Dependency("npm", "some-nonexistent-pkg-xyz", "1.0.0", "runtime");
            LicenseInfo info = catalog.lookup(dep);
            assertEquals(RiskLevel.UNKNOWN, info.risk());
            assertNull(info.spdxId());
        }
    }

    // ---- 4. 命中率自检（Issue #11 防回归）----

    @Nested
    @DisplayName("每个生态至少有一个样例能命中（防回归）")
    class HitRateSelfCheck {

        @Test
        @DisplayName("Maven 样例至少 1 条命中")
        void mavenSampleHits() {
            Dependency dep = new Dependency("maven", "mysql:mysql-connector-java", "8.0.33", "runtime");
            LicenseInfo info = catalog.lookup(dep);
            assertNotEquals(RiskLevel.UNKNOWN, info.risk(), "Maven 样例 mysql:mysql-connector-java 应命中");
        }

        @Test
        @DisplayName("npm 样例至少 1 条命中")
        void npmSampleHits() {
            Dependency dep = new Dependency("npm", "lodash", "4.17.21", "runtime");
            LicenseInfo info = catalog.lookup(dep);
            assertNotEquals(RiskLevel.UNKNOWN, info.risk(), "npm 样例 lodash 应命中");
        }

        @Test
        @DisplayName("python 样例至少 1 条命中")
        void pythonSampleHits() {
            Dependency dep = new Dependency("python", "requests", "2.31.0", "runtime");
            LicenseInfo info = catalog.lookup(dep);
            assertNotEquals(RiskLevel.UNKNOWN, info.risk(), "python 样例 requests 应命中");
        }

        @Test
        @DisplayName("go 样例至少 1 条命中")
        void goSampleHits() {
            Dependency dep = new Dependency("go", "github.com/google/uuid", "v1.6.0", "runtime");
            LicenseInfo info = catalog.lookup(dep);
            assertNotEquals(RiskLevel.UNKNOWN, info.risk(), "go 样例 uuid 应命中");
        }
    }

    // ---- 5. 外置快照 ----

    @Test
    @DisplayName("外置快照：自定义映射覆盖内置结果")
    void shouldUseExternalCatalog() throws IOException {
        Path external = tempDir.resolve("licenses.json");
        Files.writeString(external, """
                {
                  "npm:lodash": {
                    "spdxId": "GPL-3.0",
                    "name": "GNU GPL v3",
                    "risk": "STRONG_COPYLEFT"
                  }
                }
                """, StandardCharsets.UTF_8);

        LicenseInfo info = new LicenseCatalog(external).lookup(
                new Dependency("npm", "lodash", "4.17.21", "runtime"));

        assertEquals("GPL-3.0", info.spdxId());
        assertEquals(RiskLevel.STRONG_COPYLEFT, info.risk());
    }

    @Test
    @DisplayName("外置快照不存在：回退内置快照")
    void shouldFallBackWhenExternalCatalogMissing() {
        LicenseInfo info = new LicenseCatalog(tempDir.resolve("missing.json")).lookup(
                new Dependency("npm", "lodash", "4.17.21", "runtime"));

        assertEquals("MIT", info.spdxId());
    }

    @Test
    @DisplayName("外置快照风险枚举非法：回退内置快照")
    void shouldFallBackWhenExternalCatalogHasUnknownRisk() throws IOException {
        Path external = tempDir.resolve("licenses.json");
        Files.writeString(external, """
                {
                  "npm:lodash": {
                    "spdxId": "GPL-3.0",
                    "name": "GNU GPL v3",
                    "risk": "STRONG_COPYLFT"
                  }
                }
                """, StandardCharsets.UTF_8);

        LicenseInfo info = new LicenseCatalog(external).lookup(
                new Dependency("npm", "lodash", "4.17.21", "runtime"));

        assertEquals("MIT", info.spdxId());
        assertEquals(RiskLevel.PERMISSIVE, info.risk());
    }

    @Test
    @DisplayName("外置快照缺少必需字段：回退内置快照")
    void shouldFallBackWhenExternalCatalogMissingRequiredField() throws IOException {
        Path external = tempDir.resolve("licenses.json");
        Files.writeString(external, """
                {
                  "npm:lodash": {
                    "name": "GNU GPL v3",
                    "risk": "STRONG_COPYLEFT"
                  }
                }
                """, StandardCharsets.UTF_8);

        LicenseInfo info = new LicenseCatalog(external).lookup(
                new Dependency("npm", "lodash", "4.17.21", "runtime"));

        assertEquals("MIT", info.spdxId());
        assertEquals(RiskLevel.PERMISSIVE, info.risk());
    }
}

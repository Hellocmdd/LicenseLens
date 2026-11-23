package edu.course.licenselens.core;

import edu.course.licenselens.spi.Decision;
import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.RiskLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CampusPolicy / PermissiveOnlyPolicy 的纯策略测试（不依赖 ScanEngine）。
 * <p>
 * 输入：RiskLevel × Policy
 * 输出：allow/warn/block
 */
class PolicyTest {

    private static final Dependency DUMMY =
            new Dependency("maven", "test:dummy", "1.0.0", "compile");

    @Nested
    @DisplayName("CampusPolicy")
    class CampusPolicyTest {

        private final CampusPolicy policy = new CampusPolicy();

        @Test
        @DisplayName("PERMISSIVE → allow")
        void permissiveShouldAllow() {
            Decision d = policy.evaluate(lic(RiskLevel.PERMISSIVE), DUMMY);
            assertTrue(d.allowed(), "宽松许可应通过");
            assertFalse(d.warning(), "不应警告");
        }

        @Test
        @DisplayName("WEAK_COPYLEFT → warn（不阻断，但提示）")
        void weakCopyleftShouldWarn() {
            Decision d = policy.evaluate(lic(RiskLevel.WEAK_COPYLEFT), DUMMY);
            assertTrue(d.allowed(), "弱 copyleft 允许但警告");
            assertTrue(d.warning(), "应警告");
        }

        @Test
        @DisplayName("STRONG_COPYLEFT → block")
        void strongCopyleftShouldBlock() {
            Decision d = policy.evaluate(lic(RiskLevel.STRONG_COPYLEFT), DUMMY);
            assertFalse(d.allowed(), "强 copyleft 应阻断");
            assertFalse(d.warning(), "不应警告（已经阻断）");
        }

        @Test
        @DisplayName("UNKNOWN → warn（不能判定的，先警告）")
        void unknownShouldWarn() {
            Decision d = policy.evaluate(lic(RiskLevel.UNKNOWN), DUMMY);
            assertTrue(d.allowed(), "未知许可证允许但警告");
            assertTrue(d.warning(), "应提示人工核对");
        }

        @Test
        @DisplayName("id() 应返回 campus")
        void shouldHaveCorrectId() {
            assertEquals("campus", policy.id());
        }
    }

    @Nested
    @DisplayName("PermissiveOnlyPolicy")
    class PermissiveOnlyPolicyTest {

        private final PermissiveOnlyPolicy policy = new PermissiveOnlyPolicy();

        @Test
        @DisplayName("PERMISSIVE → allow")
        void permissiveShouldAllow() {
            Decision d = policy.evaluate(lic(RiskLevel.PERMISSIVE), DUMMY);
            assertTrue(d.allowed());
            assertFalse(d.warning());
        }

        @Test
        @DisplayName("WEAK_COPYLEFT → block（permissive-only 连弱 copyleft 也不让）")
        void weakCopyleftShouldBlockUnderPermissiveOnly() {
            Decision d = policy.evaluate(lic(RiskLevel.WEAK_COPYLEFT), DUMMY);
            assertFalse(d.allowed());
        }

        @Test
        @DisplayName("STRONG_COPYLEFT → block")
        void strongCopyleftShouldBlock() {
            Decision d = policy.evaluate(lic(RiskLevel.STRONG_COPYLEFT), DUMMY);
            assertFalse(d.allowed());
        }

        @Test
        @DisplayName("UNKNOWN → block（不能判定的一律拒绝）")
        void unknownShouldBlockUnderPermissiveOnly() {
            Decision d = policy.evaluate(lic(RiskLevel.UNKNOWN), DUMMY);
            assertFalse(d.allowed(), "permissive-only 下 UNKNOWN 应阻断");
        }

        @Test
        @DisplayName("id() 应返回 permissive-only")
        void shouldHaveCorrectId() {
            assertEquals("permissive-only", policy.id());
        }
    }

    @Nested
    @DisplayName("PolicyEngine")
    class PolicyEngineTest {

        private final PolicyEngine engine = new PolicyEngine(
                List.of(new CampusPolicy(), new PermissiveOnlyPolicy()));

        @Test
        @DisplayName("select('campus') → CampusPolicy")
        void shouldSelectCampusById() {
            assertTrue(engine.select("campus") instanceof CampusPolicy);
        }

        @Test
        @DisplayName("select('permissive-only') → PermissiveOnlyPolicy")
        void shouldSelectPermissiveOnlyById() {
            assertTrue(engine.select("permissive-only") instanceof PermissiveOnlyPolicy);
        }

        @Test
        @DisplayName("select('no-such') → IllegalArgumentException")
        void shouldThrowForUnknownPolicy() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> engine.select("no-such"));
        }
    }

    private static LicenseInfo lic(RiskLevel risk) {
        return new LicenseInfo(null, "test", risk);
    }
}

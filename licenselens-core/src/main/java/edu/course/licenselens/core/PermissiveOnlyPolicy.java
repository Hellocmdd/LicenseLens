package edu.course.licenselens.core;

import edu.course.licenselens.spi.Decision;
import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.Policy;
import edu.course.licenselens.spi.RiskLevel;

/**
 * permissive-only 策略：只允许宽松许可，其他一律阻断。
 * <ul>
 *   <li>PERMISSIVE     → 允许</li>
 *   <li>WEAK_COPYLEFT  → 阻断</li>
 *   <li>STRONG_COPYLEFT → 阻断</li>
 *   <li>UNKNOWN         → 阻断</li>
 * </ul>
 */
public class PermissiveOnlyPolicy implements Policy {

    @Override
    public String id() {
        return "permissive-only";
    }

    @Override
    public Decision evaluate(LicenseInfo license, Dependency dep) {
        RiskLevel risk = license.risk();
        return switch (risk) {
            case PERMISSIVE -> Decision.allow("宽松许可");
            case WEAK_COPYLEFT  -> Decision.block("弱 copyleft，permissive-only 不允许");
            case STRONG_COPYLEFT -> Decision.block("强 copyleft，permissive-only 不允许");
            case UNKNOWN        -> Decision.block("许可证未知，permissive-only 不允许");
        };
    }
}

package edu.course.licenselens.core;

import edu.course.licenselens.spi.Decision;
import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.Policy;
import edu.course.licenselens.spi.RiskLevel;

/**
 * campus 策略：课程场景的宽松合规判定。
 * <ul>
 *   <li>PERMISSIVE     → 允许</li>
 *   <li>WEAK_COPYLEFT  → 允许但警告</li>
 *   <li>STRONG_COPYLEFT → 阻断（与 MIT/Apache 项目不兼容）</li>
 *   <li>UNKNOWN         → 警告</li>
 * </ul>
 */
public class CampusPolicy implements Policy {

    @Override
    public String id() {
        return "campus";
    }

    @Override
    public Decision evaluate(LicenseInfo license, Dependency dep) {
        RiskLevel risk = license.risk();
        return switch (risk) {
            case PERMISSIVE     -> Decision.allow("宽松许可，无附加义务");
            case WEAK_COPYLEFT  -> Decision.warn("弱 copyleft，测试范围内可接受");
            case STRONG_COPYLEFT -> Decision.block("强 copyleft，与 MIT/Apache 项目不兼容");
            case UNKNOWN        -> Decision.warn("未命中许可证快照，已标记未知");
        };
    }
}

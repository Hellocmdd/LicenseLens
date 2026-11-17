package edu.course.licenselens.spi;

/**
 * 评估许可证合规性的策略接口。
 * <p>
 * 内置两种策略：
 * <ul>
 *   <li>{@code campus}：强 copyleft 阻断，弱 copyleft 警告——课程场景的宽松策略</li>
 *   <li>{@code permissive-only}：只允许 PERMISSIVE，其他一律阻断</li>
 * </ul>
 */
public interface Policy {

    /** 策略唯一标识，CLI 通过 {@code --policy} 参数选择。 */
    String id();

    /**
     * 评估一个依赖在本策略下的合规决策。
     *
     * @param license 许可证匹配结果
     * @param dep     依赖项
     * @return 决策（允许/警告/阻断 + 理由）
     */
    Decision evaluate(LicenseInfo license, Dependency dep);
}

package edu.course.licenselens.spi;

/**
 * 许可证风险等级。
 * <p>
 * **兼容策略**：新增枚举值是向后兼容的，但 {@link Policy} 实现需给出默认分支（switch default）。
 */
public enum RiskLevel {
    /** 宽松许可：Apache / MIT / BSD / ISC —— 无附加义务 */
    PERMISSIVE,
    /** 弱 copyleft：EPL / LGPL / MPL —— 通常允许作为库使用 */
    WEAK_COPYLEFT,
    /** 强 copyleft：GPL / AGPL —— 与 MIT/Apache 项目不兼容 */
    STRONG_COPYLEFT,
    /** 许可证未命中快照，core 标记为未知 */
    UNKNOWN
}

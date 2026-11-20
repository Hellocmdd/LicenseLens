package edu.course.licenselens.spi;

/**
 * 许可证的匹配结果。
 *
 * @param spdxId  SPDX 标识符（如 "Apache-2.0"、"GPL-2.0"）；未匹配为 null
 * @param name    许可证全称（如 "Apache License 2.0"）
 * @param risk    风险等级（core 根据 licenses.json 快照填充；未匹配时为 UNKNOWN）
 */
public record LicenseInfo(String spdxId, String name, RiskLevel risk) {
}

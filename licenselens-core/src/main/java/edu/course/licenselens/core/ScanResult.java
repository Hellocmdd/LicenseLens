package edu.course.licenselens.core;

/**
 * 扫描结果，CLI 根据 exitCode 决定退出码，根据 reportContent 写文件。
 *
 * @param exitCode      退出码（0=正常/无阻断，1=有阻断，2=文件缺失，3=解析错误，4=无解析器）
 * @param totalCount    依赖总数
 * @param blockedCount  阻断数量
 * @param unknownCount  UNKNOWN 数量
 * @param reportContent Markdown 报告完整内容（UTF-8）
 */
public record ScanResult(int exitCode, int totalCount, int blockedCount,
                         int unknownCount, String reportContent) {
}

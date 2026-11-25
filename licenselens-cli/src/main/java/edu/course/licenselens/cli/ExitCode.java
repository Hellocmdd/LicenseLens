package edu.course.licenselens.cli;

/**
 * CLI 退出码，和 {@code docs/CONTRACT.md} 严格一致。
 * <ul>
 *   <li>0 = 正常，无阻断依赖</li>
 *   <li>1 = 存在阻断依赖（业务拒绝）</li>
 *   <li>2 = 清单文件不存在</li>
 *   <li>3 = 清单语法错误（XML / JSON / 文本）</li>
 *   <li>4 = 无可用解析器（需安装 npm / Go / Python 等插件）</li>
 * </ul>
 */
public enum ExitCode {

    OK(0, "正常/无阻断"),
    BLOCKED(1, "存在阻断依赖"),
    FILE_NOT_FOUND(2, "清单文件不存在"),
    PARSE_ERROR(3, "清单语法错误"),
    NO_PARSER(4, "无可用解析器");

    public final int code;
    public final String message;

    ExitCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 把 {@code ScanResult.exitCode} 映射为 CLI 退出码枚举。
     *
     * @param value 契约退出码，范围 0 到 4
     * @return 对应的退出码枚举
     * @throws IllegalStateException 传入契约范围外的值
     */
    public static ExitCode fromValue(int value) {
        return switch (value) {
            case 0 -> OK;
            case 1 -> BLOCKED;
            case 2 -> FILE_NOT_FOUND;
            case 3 -> PARSE_ERROR;
            case 4 -> NO_PARSER;
            default -> throw new IllegalStateException("ScanResult 返回未知退出码: " + value);
        };
    }
}

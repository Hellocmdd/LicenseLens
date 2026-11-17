package edu.course.licenselens.spi;

/**
 * 解析器抛出的异常。core 捕获后转成退出码：
 * <ul>
 *   <li>{@link Code#FILE_NOT_FOUND} → 退出码 2</li>
 *   <li>{@link Code#PARSE_ERROR}     → 退出码 3</li>
 *   <li>{@link Code#UNSUPPORTED_FORMAT} → 退出码 4</li>
 * </ul>
 */
public final class SourceException extends Exception {

    public enum Code {
        /** 文件路径不存在 */
        FILE_NOT_FOUND,
        /** XML/JSON 语法错误，{@link #line()} 记录问题行号 */
        PARSE_ERROR,
        /** 没有任何解析器能处理此文件格式 */
        UNSUPPORTED_FORMAT
    }

    private final Code code;
    private final int line;

    public SourceException(Code code, String message) {
        super(message);
        this.code = code;
        this.line = -1;
    }

    public SourceException(Code code, String message, int line) {
        super(message);
        this.code = code;
        this.line = line;
    }

    public Code code() {
        return code;
    }

    /** PARSE_ERROR 时有效；其他情况下返回 -1。 */
    public int line() {
        return line;
    }
}

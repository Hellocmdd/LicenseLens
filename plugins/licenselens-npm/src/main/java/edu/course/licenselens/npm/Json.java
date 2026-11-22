package edu.course.licenselens.npm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件内置的极简 JSON 解析器。
 * <p>
 * 为什么不用第三方库：
 * <ul>
 *   <li>JDK 21 并没有内置 javax.json / jakarta.json；</li>
 *   <li>CLI 为每个插件 jar 单独建 URLClassLoader，插件引入的外部 jar 不在加载路径上，
 *       除非额外 shade，会让"单个 jar 放进 plugins 目录即可用"的接入方式失效；</li>
 *   <li>扩展契约要求 pom 只依赖 licenselens-spi。</li>
 * </ul>
 *
 * <h3>与契约相关的保证</h3>
 * <ul>
 *   <li>对象用 {@link LinkedHashMap}，保留清单里的声明顺序；</li>
 *   <li>语法错误携带 1 基行号，供 {@code SourceException(PARSE_ERROR, msg, line)} 使用；</li>
 *   <li>拒绝尾随逗号、多余内容、未转义控制字符等非法写法。</li>
 * </ul>
 */
final class Json {

    private Json() {
    }

    /**
     * 解析 JSON 文本。返回类型只可能是 Map / List / String / BigDecimal / Boolean / null。
     */
    static Object parse(String text) throws JsonSyntaxException {
        Parser parser = new Parser(text);
        return parser.parseDocument();
    }

    /** JSON 语法错误，line 为 1 基行号。 */
    static final class JsonSyntaxException extends Exception {
        private final int line;

        JsonSyntaxException(String message, int line) {
            super(message);
            this.line = line;
        }

        int line() {
            return line;
        }
    }

    private static final class Parser {
        private final String src;
        private int pos;
        private int line = 1;

        Parser(String src) {
            this.src = src;
            if (!src.isEmpty() && src.charAt(0) == '\uFEFF') {
                this.pos = 1; // 允许 UTF-8 BOM
            }
        }

        Object parseDocument() throws JsonSyntaxException {
            skipWhitespace();
            Object value = readValue();
            skipWhitespace();
            if (pos != src.length()) {
                throw error("根值后存在多余内容");
            }
            return value;
        }

        private Object readValue() throws JsonSyntaxException {
            if (pos >= src.length()) {
                throw error("意外的文件结尾");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("非法的 JSON 值");
                }
            };
        }

        private Map<String, Object> readObject() throws JsonSyntaxException {
            pos++; // '{'
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != '"') {
                    throw error("对象的键必须是字符串");
                }
                String key = readString();
                skipWhitespace();
                if (!consume(':')) {
                    throw error("键与值之间缺少 ':'");
                }
                skipWhitespace();
                result.put(key, readValue());
                skipWhitespace();
                if (consume(',')) {
                    continue; // 下一次循环遇到 '}' 会报"键必须是字符串"，即尾随逗号非法
                }
                if (consume('}')) {
                    return result;
                }
                throw error("对象缺少 ',' 或 '}'");
            }
        }

        private List<Object> readArray() throws JsonSyntaxException {
            pos++; // '['
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(readValue());
                skipWhitespace();
                if (consume(',')) {
                    continue;
                }
                if (consume(']')) {
                    return result;
                }
                throw error("数组缺少 ',' 或 ']'");
            }
        }

        private String readString() throws JsonSyntaxException {
            pos++; // 起始引号
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error("字符串中包含未转义的控制字符");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= src.length()) {
                    throw error("字符串转义不完整");
                }
                char escape = src.charAt(pos++);
                switch (escape) {
                    case '"', '\\', '/' -> sb.append(escape);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(readUnicodeEscape());
                    default -> throw error("非法的字符串转义 \\" + escape);
                }
            }
            throw error("字符串未闭合");
        }

        private char readUnicodeEscape() throws JsonSyntaxException {
            if (pos + 4 > src.length()) {
                throw error("\\u 转义不足 4 位十六进制字符");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(src.charAt(pos++), 16);
                if (digit < 0) {
                    throw error("\\u 转义含非法十六进制字符");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Object readLiteral(String literal, Object value) throws JsonSyntaxException {
            if (!src.startsWith(literal, pos)) {
                throw error("非法的 JSON 字面量");
            }
            pos += literal.length();
            return value;
        }

        private BigDecimal readNumber() throws JsonSyntaxException {
            int start = pos;
            if (consume('-') && pos >= src.length()) {
                throw error("数字不完整");
            }
            if (consume('0')) {
                // 整数部分为 0，后面不能紧跟数字（如 01）
            } else if (pos < src.length() && src.charAt(pos) >= '1' && src.charAt(pos) <= '9') {
                while (pos < src.length() && isDigit(src.charAt(pos))) {
                    pos++;
                }
            } else {
                throw error("非法的数字");
            }
            if (consume('.')) {
                if (pos >= src.length() || !isDigit(src.charAt(pos))) {
                    throw error("小数点后缺少数字");
                }
                while (pos < src.length() && isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= src.length() || !isDigit(src.charAt(pos))) {
                    throw error("指数部分缺少数字");
                }
                while (pos < src.length() && isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            try {
                return new BigDecimal(src.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("非法的数字");
            }
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\n') {
                    line++;
                    pos++;
                } else if (c == ' ' || c == '\t' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private boolean consume(char expected) {
            if (pos < src.length() && src.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private JsonSyntaxException error(String message) {
            return new JsonSyntaxException(message + "（行 " + line + "）", line);
        }
    }
}

package edu.course.licenselens.python;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.SourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Python 生态中 uv 项目的依赖清单：{@code pyproject.toml} 与 {@code uv.lock}。
 * <p>
 * 本插件仅依赖 {@code licenselens-spi}，不引用 core 或 cli。与 {@link RequirementsManifestParser}
 * 分开注册（{@code id() = "python-uv"}），避免在同一个解析器里按文件内容分派。
 *
 * <h3>支持的语法子集</h3>
 * <ul>
 *   <li>{@code pyproject.toml}：PEP 621 的 {@code [project].dependencies}、
 *       {@code [project.optional-dependencies]}，以及 PEP 735 的 {@code [dependency-groups]}；
 *       不要求项目包含 {@code [tool.uv]}；</li>
 *   <li>{@code uv.lock}：按 {@code [[package]]} 块读取 {@code name} 与 {@code version}，
 *       根包（{@code source = { virtual = "." }}）不进入结果；{@code [package.dev-dependencies]}
 *       中出现的包 scope 记为 {@code dev}；</li>
 *   <li>同一个包在锁文件里出现多次时只保留第一次出现的记录；</li>
 *   <li>空文件、只有注释的文件返回空列表。</li>
 * </ul>
 *
 * <h3>字段与口径</h3>
 * <ul>
 *   <li>ecosystem 固定为 "python"；</li>
 *   <li>单一 {@code ==} 固定版本取版本号本身，范围约束保留表达式，裸包名 version 为空串；</li>
 *   <li>extras 丢弃，环境标记（{@code ;} 之后）截断，直接引用（{@code name @ url}）把 URL 记入 version；</li>
 *   <li>{@code pyproject.toml} 是声明清单，返回声明依赖；{@code uv.lock} 是锁文件，返回解析后的完整集合，
 *       不区分直接依赖与传递依赖（Javadoc 与 README 均按此口径描述）；</li>
 *   <li>重复包名、无法识别的依赖声明、未闭合的 TOML 数组均为 PARSE_ERROR 并带行号。</li>
 * </ul>
 */
public class UvManifestParser implements DependencySource {

    private static final String ID = "python-uv";
    private static final String PYTHON = "python";
    private static final String RUNTIME = "runtime";
    private static final String DEV = "dev";

    private static final Pattern NAME = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)");
    private static final Pattern PINNED = Pattern.compile("^==\\s*([^,;\\s]+)$");
    private static final Pattern SPECIFIER = Pattern.compile(
            "^(==|===|!=|<=|>=|~=|<|>)\\s*[A-Za-z0-9.*+!_-]+"
                    + "(\\s*,\\s*(==|===|!=|<=|>=|~=|<|>)\\s*[A-Za-z0-9.*+!_-]+)*$");
    private static final Pattern UV_NAME = Pattern.compile("^name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern UV_VERSION = Pattern.compile("^version\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern INLINE_NAME = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"");

    @Override
    public String id() {
        return ID;
    }

    /**
     * 仅接受文件名恰为 {@code uv.lock} 或 {@code pyproject.toml} 的清单。
     *
     * @param ref 清单引用，允许为 null
     * @return 是否能解析该清单
     */
    @Override
    public boolean supports(ManifestRef ref) {
        if (ref == null || ref.fileName() == null) {
            return false;
        }
        String lower = ref.fileName().toLowerCase(Locale.ROOT);
        return lower.equals("uv.lock") || lower.equals("pyproject.toml");
    }

    /**
     * 解析 pyproject.toml 或 uv.lock 中的依赖声明。
     *
     * @param file 清单路径，不能为 null，且必须是普通文件
     * @return 按出现顺序排列的依赖列表；空文件返回空列表
     * @throws SourceException 路径无效时为 FILE_NOT_FOUND，语法问题为 PARSE_ERROR 并带行号
     */
    @Override
    public List<Dependency> parse(Path file) throws SourceException {
        if (file == null) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND, "清单路径为 null");
        }
        if (!Files.isRegularFile(file)) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND,
                    "清单文件不存在或不是普通文件: " + file);
        }
        List<String> lines = List.of(read(file).split("\\R", -1));
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).equals("uv.lock")) {
            return parseUvLock(lines);
        }
        return parsePyproject(lines);
    }

    private static List<Dependency> parsePyproject(List<String> lines) throws SourceException {
        List<Dependency> dependencies = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String section = "";
        for (int i = 0; i < lines.size(); i++) {
            String code = stripComment(lines.get(i)).strip();
            if (code.isEmpty()) {
                continue;
            }
            if (code.startsWith("[") && code.endsWith("]")) {
                section = code.substring(1, code.length() - 1).strip().toLowerCase(Locale.ROOT);
                continue;
            }
            if (!section.equals("project")
                    && !section.equals("project.optional-dependencies")
                    && !section.equals("dependency-groups")) {
                continue;
            }
            int equals = topLevelEquals(code);
            if (equals < 0 || !code.substring(equals + 1).strip().startsWith("[")) {
                continue;
            }
            if (section.equals("project")
                    && !code.substring(0, equals).strip().equals("dependencies")) {
                continue;
            }
            List<String> values = new ArrayList<>();
            List<Integer> valueLines = new ArrayList<>();
            i = collectArray(lines, i, values, valueLines) - 1;
            boolean dev = section.equals("dependency-groups");
            for (int k = 0; k < values.size(); k++) {
                add(dependencies, seen, values.get(k), valueLines.get(k), dev);
            }
        }
        return List.copyOf(dependencies);
    }

    private static List<Dependency> parseUvLock(List<String> lines) throws SourceException {
        List<PackageEntry> packages = new ArrayList<>();
        Set<String> devNames = new LinkedHashSet<>();
        PackageEntry current = null;
        boolean devTable = false;
        for (int i = 0; i < lines.size(); i++) {
            String code = stripComment(lines.get(i)).strip();
            if (code.isEmpty()) {
                continue;
            }
            if (code.equals("[[package]]")) {
                if (current != null) {
                    packages.add(current);
                }
                current = new PackageEntry();
                devTable = false;
                continue;
            }
            if (code.startsWith("[") && code.endsWith("]")) {
                String header = code.substring(1, code.length() - 1).strip();
                devTable = header.equals("package.dev-dependencies")
                        || header.startsWith("package.dev-dependencies.");
                continue;
            }
            if (current == null) {
                continue;
            }
            if (devTable) {
                if (code.contains("= [") || code.endsWith("[")) {
                    List<String> values = new ArrayList<>();
                    List<Integer> valueLines = new ArrayList<>();
                    i = collectArray(lines, i, values, valueLines) - 1;
                    for (String value : values) {
                        Matcher inline = INLINE_NAME.matcher(value);
                        if (inline.find()) {
                            devNames.add(inline.group(1).toLowerCase(Locale.ROOT));
                        }
                    }
                }
                continue;
            }
            Matcher nameMatcher = UV_NAME.matcher(code);
            if (nameMatcher.find()) {
                current.name = nameMatcher.group(1);
                continue;
            }
            Matcher versionMatcher = UV_VERSION.matcher(code);
            if (versionMatcher.find()) {
                current.version = versionMatcher.group(1);
                continue;
            }
            // 根包由 uv 以 virtual / editable 源码形式记录，不进入依赖结果。
            current.root = code.startsWith("source")
                    && (code.contains("virtual = \".\"") || code.contains("editable = \".\""));
        }
        if (current != null) {
            packages.add(current);
        }

        Map<String, Dependency> byName = new LinkedHashMap<>();
        for (PackageEntry pkg : packages) {
            if (pkg.name == null || pkg.root) {
                continue;
            }
            String key = pkg.name.toLowerCase(Locale.ROOT);
            if (byName.containsKey(key)) {
                continue;
            }
            boolean dev = devNames.contains(key);
            byName.put(key, new Dependency(PYTHON, pkg.name,
                    pkg.version == null ? "" : pkg.version, dev ? DEV : RUNTIME));
        }
        return List.copyOf(byName.values());
    }

    /**
     * 从 {@code start} 行开始扫描一个 TOML 数组，收集其中的字符串或内联表元素。
     *
     * @param lines     清单全文
     * @param start     数组起始行下标（该行含左方括号）
     * @param values    输出：每个元素的原始文本
     * @param valueLines 输出：每个元素起始行号（1 起）
     * @return 数组闭合行之后的下标
     * @throws SourceException 数组直到文件末尾仍没有闭合时为 PARSE_ERROR
     */
    private static int collectArray(List<String> lines, int start,
                                    List<String> values, List<Integer> valueLines)
            throws SourceException {
        StringBuilder element = new StringBuilder();
        boolean inString = false;
        boolean opened = false;
        int square = 0;
        int brace = 0;
        int elementLine = 0;
        for (int i = start; i < lines.size(); i++) {
            String line = stripComment(lines.get(i));
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (inString) {
                    element.append(ch);
                    if (ch == '\\' && c + 1 < line.length()) {
                        element.append(line.charAt(++c));
                    } else if (ch == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (ch == '"') {
                    inString = true;
                    elementLine = element.toString().isBlank() ? i + 1 : elementLine;
                    element.append(ch);
                    continue;
                }
                if (!opened) {
                    if (ch == '[') {
                        opened = true;
                        square = 1;
                    }
                    continue;
                }
                if (ch == '[') {
                    square++;
                } else if (ch == ']') {
                    square--;
                    if (square == 0) {
                        flush(element, elementLine, values, valueLines);
                        return i + 1;
                    }
                } else if (ch == '{') {
                    brace++;
                } else if (ch == '}') {
                    brace--;
                } else if (ch == ',' && square == 1 && brace == 0) {
                    flush(element, elementLine, values, valueLines);
                    elementLine = 0;
                    continue;
                }
                if (element.toString().isBlank() && !Character.isWhitespace(ch)) {
                    elementLine = i + 1;
                }
                element.append(ch);
            }
            element.append(' ');
        }
        throw new SourceException(SourceException.Code.PARSE_ERROR, "数组未闭合", start + 1);
    }

    private static void flush(StringBuilder element, int line,
                              List<String> values, List<Integer> valueLines) {
        String value = element.toString().strip();
        element.setLength(0);
        if (value.isEmpty()) {
            return;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        values.add(value);
        valueLines.add(line);
    }

    private static void add(List<Dependency> dependencies, Set<String> seen,
                            String value, int line, boolean dev) throws SourceException {
        String code = value.strip();
        if (code.isEmpty() || code.startsWith("{")) {
            // PEP 735 的 include-group 等内联表不产生具体依赖。
            return;
        }
        Dependency dependency = toDependency(code, line, dev);
        if (!seen.add(dependency.name().toLowerCase(Locale.ROOT))) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "重复的依赖: " + dependency.name(), line);
        }
        dependencies.add(dependency);
    }

    private static Dependency toDependency(String code, int lineNo, boolean dev)
            throws SourceException {
        Matcher matcher = NAME.matcher(code);
        if (!matcher.find()) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "无法识别的依赖声明: " + code, lineNo);
        }
        String name = matcher.group(1);
        String rest = code.substring(matcher.end()).strip();

        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                throw new SourceException(SourceException.Code.PARSE_ERROR,
                        "extras 未闭合: " + code, lineNo);
            }
            rest = rest.substring(close + 1).strip();
        }
        int marker = rest.indexOf(';');
        if (marker >= 0) {
            rest = rest.substring(0, marker).strip();
        }

        String version;
        if (rest.startsWith("@")) {
            version = rest.substring(1).strip();
            if (version.isEmpty()) {
                throw new SourceException(SourceException.Code.PARSE_ERROR,
                        "直接引用缺少 URL: " + code, lineNo);
            }
        } else {
            if (rest.isEmpty()) {
                version = "";
            } else if (SPECIFIER.matcher(rest).matches()) {
                version = normalize(rest);
            } else {
                throw new SourceException(SourceException.Code.PARSE_ERROR,
                        "无法识别的版本约束: " + rest, lineNo);
            }
        }
        return new Dependency(PYTHON, name, version, dev ? DEV : RUNTIME);
    }

    private static String normalize(String specifier) {
        Matcher matcher = PINNED.matcher(specifier);
        return matcher.matches() ? matcher.group(1) : specifier;
    }

    private static int topLevelEquals(String code) {
        boolean inString = false;
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '=') {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String read(Path file) throws SourceException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND,
                    "清单文件不存在: " + file);
        } catch (IOException e) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "读取清单失败: " + e.getMessage(), 1);
        }
    }

    /** uv.lock 中一个 {@code [[package]]} 块的关键字段。 */
    private static final class PackageEntry {
        private String name;
        private String version;
        private boolean root;
    }
}

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Python 依赖清单 requirements.txt 的独立扩展。
 * <p>
 * 本插件仅依赖 {@code licenselens-spi}，不引用 core 或 cli。
 *
 * <h3>支持的语法子集</h3>
 * <ul>
 *   <li>每行一个依赖：{@code name}、{@code name==1.0}、{@code name>=1.0,<2.0}；</li>
 *   <li>可选的 extras（{@code name[extra]==1.0}）与环境标记（{@code name==1.0 ; python_version >= "3.9"}）；</li>
 *   <li>直接引用 {@code name @ https://...}，URL 原样记入 version；</li>
 *   <li>{@code #} 注释、空行、行尾续行符 {@code \}；</li>
 *   <li>{@code -r} / {@code -c} / {@code --index-url} 等选项行忽略，行内 {@code --hash=...} 丢弃；</li>
 *   <li>文件名含 {@code dev} 时 scope 为 {@code dev}，其余为 {@code runtime}。</li>
 * </ul>
 *
 * <h3>字段与边界</h3>
 * <ul>
 *   <li>ecosystem = "python"，name 为包名原样保留，version 为版本约束；</li>
 *   <li>单一 {@code ==} 固定版本取版本号本身（如 "2.31.0"），范围约束保留表达式（如 ">=2.2,<3.0"）；</li>
 *   <li>按声明顺序返回，不排序；空文件与纯注释文件返回空列表；</li>
 *   <li>重复包名、无法识别的行、未闭合 extras、续行符悬空均为 PARSE_ERROR，行号指向问题行。</li>
 * </ul>
 */
public class RequirementsManifestParser implements DependencySource {

    private static final String PYTHON = "python";
    private static final String RUNTIME = "runtime";
    private static final String DEV = "dev";
    private static final String CONTINUATION = "\\";

    private static final Pattern NAME = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)");
    private static final Pattern PINNED = Pattern.compile("^==\\s*([^,;\\s]+)$");
    private static final Pattern SPECIFIER = Pattern.compile(
            "^(==|===|!=|<=|>=|~=|<|>)\\s*[A-Za-z0-9.*+!_-]+"
                    + "(\\s*,\\s*(==|===|!=|<=|>=|~=|<|>)\\s*[A-Za-z0-9.*+!_-]+)*$");
    private static final Pattern REQUIREMENT_LINE = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]*(\\[[^\\]]*\\])?"
                    + "(\\s*@\\s*\\S+|\\s*(==|===|!=|<=|>=|~=|<|>).*)?$");

    @Override
    public String id() {
        return PYTHON;
    }

    /**
     * 仅接受文件名含 {@code requirement} 且内容首行像依赖声明（或为注释、空行）的清单。
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
        if (!lower.contains("requirement")) {
            return false;
        }
        if (!(lower.endsWith(".txt") || lower.endsWith(".in") || lower.endsWith(".pip"))) {
            return false;
        }
        String first = ref.firstLine();
        if (first == null || first.isBlank()) {
            // 空文件只凭文件名认领，扫描后得到空依赖列表。
            return true;
        }
        String line = stripComment(stripBom(first)).strip();
        if (line.isEmpty() || line.startsWith("-")) {
            return true;
        }
        return REQUIREMENT_LINE.matcher(line).matches();
    }

    /**
     * 解析 requirements.txt 中的依赖声明。
     *
     * @param file 清单路径，不能为 null，且必须是普通文件
     * @return 按声明顺序排列的依赖列表；空文件返回空列表
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

        String[] lines = read(file).split("\\R", -1);
        boolean dev = isDevFileName(file.getFileName().toString());
        List<Dependency> dependencies = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder buffer = new StringBuilder();
        int startLine = 0;

        for (int i = 0; i < lines.length; i++) {
            String raw = stripBom(lines[i]);
            if (startLine == 0) {
                startLine = i + 1;
            }
            String trimmed = raw.stripTrailing();
            if (trimmed.endsWith(CONTINUATION)) {
                buffer.append(trimmed, 0, trimmed.length() - 1).append(' ');
                continue;
            }
            buffer.append(raw);
            consume(buffer.toString(), startLine, dev, dependencies, seen);
            buffer.setLength(0);
            startLine = 0;
        }
        if (buffer.length() > 0) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "续行符后没有内容", startLine);
        }
        return List.copyOf(dependencies);
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

    private static void consume(String rawLine, int lineNo, boolean dev,
                                List<Dependency> dependencies, Set<String> seen)
            throws SourceException {
        String code = stripComment(rawLine).strip();
        if (code.isEmpty()) {
            return;
        }
        if (code.startsWith("-")) {
            // -r / -c / --index-url / --hash 等选项行与依赖无关。
            return;
        }
        code = cutInlineOptions(code).strip();
        if (code.isEmpty()) {
            return;
        }
        Dependency dep = toDependency(code, lineNo, dev);
        if (!seen.add(dep.name().toLowerCase(Locale.ROOT))) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "重复的依赖: " + dep.name(), lineNo);
        }
        dependencies.add(dep);
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

        String version;
        if (rest.startsWith("@")) {
            version = rest.substring(1).strip();
            if (version.isEmpty()) {
                throw new SourceException(SourceException.Code.PARSE_ERROR,
                        "直接引用缺少 URL: " + code, lineNo);
            }
        } else {
            int marker = rest.indexOf(';');
            if (marker >= 0) {
                rest = rest.substring(0, marker).strip();
            }
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

    private static boolean isDevFileName(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).contains("dev");
    }

    private static String cutInlineOptions(String code) {
        int index = code.indexOf(" --");
        return index < 0 ? code : code.substring(0, index);
    }

    private static String stripComment(String line) {
        int index = line.indexOf('#');
        if (index < 0) {
            return line;
        }
        if (index == 0) {
            return "";
        }
        // 直接引用（name @ url）的 # 可能是 URL 片段，只有在空白后才视为注释。
        if (!line.contains("@")) {
            return line.substring(0, index);
        }
        return Character.isWhitespace(line.charAt(index - 1)) ? line.substring(0, index) : line;
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }
}

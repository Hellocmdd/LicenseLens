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
 * 解析 conda 生态的依赖清单：{@code environment.yml} 与 {@code conda-lock.yml}。
 * <p>
 * 本插件仅依赖 {@code licenselens-spi}，不引用 core 或 cli。与 requirements / uv 解析器分开注册
 * （{@code id() = "python-conda"}），认领范围按文件名互斥。
 *
 * <h3>支持的语法子集</h3>
 * <ul>
 *   <li>{@code environment.yml}（及 {@code .yaml}）：只读取 {@code dependencies:} 下的条目；
 *       {@code - numpy=1.26.4}、{@code - python=3.11}、{@code - conda-forge::numpy=1.26.4}、
 *       {@code - "numpy 1.26.4 py311_0"}、{@code - numpy>=1.20} 均可识别；</li>
 *   <li>{@code - pip:} 子列表按 PEP 508 解析，ecosystem 记为 "python"（这些是 PyPI 包），
 *       conda 条目 ecosystem 记为 "conda"；</li>
 *   <li>{@code conda-lock.yml}（及 {@code .yaml}）：读取 {@code package:} 列表的
 *       {@code name} / {@code version} / {@code manager}；manager 为 pip 的条目 ecosystem 为 "python"，
 *       其余为 "conda"；同一 name + version 在多个平台重复时只保留第一次出现；</li>
 *   <li>空清单与没有任何依赖的清单返回空列表；文件名含 {@code dev} 时 scope 记为 {@code dev}。</li>
 * </ul>
 *
 * <h3>字段与边界</h3>
 * <ul>
 *   <li>channel 前缀（{@code conda-forge::}）丢弃；conda 的 build 串（{@code =py311_0}）丢弃，
 *       只保留版本号；范围约束原样保留（如 ">=1.20"）；</li>
 *   <li>重复包名、无法识别的条目标记为 PARSE_ERROR 并带行号；</li>
 *   <li>conda 包在快照里若没有 {@code conda:} 键会落到 {@code UNKNOWN}，这是数据覆盖问题，
 *       不是解析错误。</li>
 * </ul>
 */
public class CondaManifestParser implements DependencySource {

    private static final String ID = "python-conda";
    private static final String CONDA = "conda";
    private static final String PYTHON = "python";
    private static final String RUNTIME = "runtime";
    private static final String DEV = "dev";

    private static final Pattern NAME = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)");
    private static final Pattern PINNED = Pattern.compile("^==\\s*([^,;\\s]+)$");
    private static final Pattern SPECIFIER = Pattern.compile(
            "^(==|===|!=|<=|>=|~=|<|>)\\s*[A-Za-z0-9.*+!_-]+"
                    + "(\\s*,\\s*(==|===|!=|<=|>=|~=|<|>)\\s*[A-Za-z0-9.*+!_-]+)*$");
    private static final Pattern YAML_NAME = Pattern.compile("^name:\\s*(.+)$");
    private static final Pattern YAML_VERSION = Pattern.compile("^version:\\s*(.+)$");
    private static final Pattern YAML_MANAGER = Pattern.compile("^manager:\\s*(.+)$");

    @Override
    public String id() {
        return ID;
    }

    /**
     * 仅接受文件名恰为 conda 相关清单的引用：{@code environment.yml/.yaml} 与
     * {@code conda-lock.yml/.yaml}。
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
        return lower.equals("environment.yml") || lower.equals("environment.yaml")
                || lower.equals("conda-lock.yml") || lower.equals("conda-lock.yaml");
    }

    /**
     * 解析 environment.yml 或 conda-lock.yml 中的依赖声明。
     *
     * @param file 清单路径，不能为 null，且必须是普通文件
     * @return 按声明顺序排列的依赖列表；空清单返回空列表
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
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean dev = fileName.contains("dev");
        if (fileName.startsWith("conda-lock")) {
            return parseCondaLock(lines, dev);
        }
        return parseEnvironment(lines, dev);
    }

    private static List<Dependency> parseEnvironment(List<String> lines, boolean dev)
            throws SourceException {
        List<Dependency> dependencies = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int depsIndent = -1;
        boolean inPip = false;
        int pipIndent = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i));
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int indent = indentOf(line);
            if (depsIndent < 0) {
                if (trimmed.equals("dependencies:")) {
                    depsIndent = indent;
                }
                continue;
            }
            if (indent <= depsIndent) {
                break;
            }
            if (!trimmed.startsWith("- ")) {
                continue;
            }
            String item = trimmed.substring(2).strip();
            if (item.endsWith(":")) {
                boolean pipSection = item.substring(0, item.length() - 1).strip().equals("pip");
                inPip = pipSection;
                pipIndent = indent;
                continue;
            }
            if (inPip && indent > pipIndent) {
                Dependency dependency = pipDependency(item, i + 1, dev);
                add(dependencies, seen, dependency, i + 1);
            } else {
                inPip = false;
                Dependency dependency = condaDependency(item, i + 1, dev);
                add(dependencies, seen, dependency, i + 1);
            }
        }
        return List.copyOf(dependencies);
    }

    private static List<Dependency> parseCondaLock(List<String> lines, boolean dev)
            throws SourceException {
        List<Dependency> dependencies = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        PackageEntry current = null;
        boolean inPackages = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = stripComment(lines.get(i)).strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equals("package:")) {
                inPackages = true;
                continue;
            }
            if (!inPackages) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                if (current != null) {
                    addLockEntry(dependencies, seen, current, dev);
                }
                current = new PackageEntry();
                applyLockField(current, trimmed.substring(2).strip(), i + 1);
                continue;
            }
            if (current != null) {
                applyLockField(current, trimmed, i + 1);
            }
        }
        if (current != null) {
            addLockEntry(dependencies, seen, current, dev);
        }
        return List.copyOf(dependencies);
    }

    private static void applyLockField(PackageEntry entry, String field, int lineNo)
            throws SourceException {
        Matcher name = YAML_NAME.matcher(field);
        if (name.matches()) {
            entry.name = unquote(name.group(1).strip());
            return;
        }
        Matcher version = YAML_VERSION.matcher(field);
        if (version.matches()) {
            entry.version = unquote(version.group(1).strip());
            return;
        }
        Matcher manager = YAML_MANAGER.matcher(field);
        if (manager.matches()) {
            entry.manager = unquote(manager.group(1).strip());
        }
    }

    private static void addLockEntry(List<Dependency> dependencies, Set<String> seen,
                                     PackageEntry entry, boolean dev) throws SourceException {
        if (entry.name == null || entry.name.isBlank()) {
            return;
        }
        boolean pipManager = entry.manager != null
                && entry.manager.equalsIgnoreCase("pip");
        String ecosystem = pipManager ? PYTHON : CONDA;
        String version = entry.version == null ? "" : entry.version;
        String key = ecosystem + ":" + entry.name.toLowerCase(Locale.ROOT) + ":" + version;
        if (!seen.add(key)) {
            return;
        }
        dependencies.add(new Dependency(ecosystem, entry.name, version, dev ? DEV : RUNTIME));
    }

    private static void add(List<Dependency> dependencies, Set<String> seen,
                            Dependency dependency, int lineNo) throws SourceException {
        String key = dependency.ecosystem() + ":"
                + dependency.name().toLowerCase(Locale.ROOT);
        if (!seen.add(key)) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "重复的依赖: " + dependency.name(), lineNo);
        }
        dependencies.add(dependency);
    }

    private static Dependency condaDependency(String spec, int lineNo, boolean dev)
            throws SourceException {
        String value = unquote(spec.strip());
        int channel = value.indexOf("::");
        if (channel >= 0) {
            value = value.substring(channel + 2).strip();
        }
        if (value.isEmpty()) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "conda 依赖为空", lineNo);
        }
        String name;
        String version = "";
        int op = firstOperator(value);
        if (op > 0) {
            name = value.substring(0, op).strip();
            version = normalizeCondaVersion(value.substring(op).strip());
        } else if (value.contains(" ")) {
            String[] parts = value.split("\\s+");
            name = parts[0];
            version = parts.length > 1 ? parts[1] : "";
        } else {
            name = value;
        }
        if (!NAME.matcher(name).matches()) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "无法识别的 conda 依赖: " + spec, lineNo);
        }
        return new Dependency(CONDA, name, version, dev ? DEV : RUNTIME);
    }

    private static Dependency pipDependency(String spec, int lineNo, boolean dev)
            throws SourceException {
        String code = spec.strip();
        Matcher matcher = NAME.matcher(code);
        if (!matcher.find()) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "无法识别的 pip 依赖: " + spec, lineNo);
        }
        String name = matcher.group(1);
        String rest = code.substring(matcher.end()).strip();
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                throw new SourceException(SourceException.Code.PARSE_ERROR,
                        "extras 未闭合: " + spec, lineNo);
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
                        "直接引用缺少 URL: " + spec, lineNo);
            }
        } else {
            if (rest.isEmpty()) {
                version = "";
            } else if (SPECIFIER.matcher(rest).matches()) {
                Matcher pinned = PINNED.matcher(rest);
                version = pinned.matches() ? pinned.group(1) : rest;
            } else {
                throw new SourceException(SourceException.Code.PARSE_ERROR,
                        "无法识别的版本约束: " + rest, lineNo);
            }
        }
        return new Dependency(PYTHON, name, version, dev ? DEV : RUNTIME);
    }

    private static int firstOperator(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '<' || ch == '>' || ch == '=' || ch == '!' || ch == '~') {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeCondaVersion(String constraint) {
        String value = constraint.strip();
        if (value.startsWith(">=") || value.startsWith("<=") || value.startsWith("!=")
                || value.startsWith("~=") || value.startsWith("==")
                || value.startsWith(">") || value.startsWith("<")) {
            return value;
        }
        if (value.startsWith("=")) {
            String rest = value.substring(1);
            int build = rest.indexOf('=');
            return (build >= 0 ? rest.substring(0, build) : rest).strip();
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inSingle) {
                if (ch == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (ch == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (ch == '\'') {
                inSingle = true;
            } else if (ch == '"') {
                inDouble = true;
            } else if (ch == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
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

    /** conda-lock.yml 中一条 package 记录的关键字段。 */
    private static final class PackageEntry {
        private String name;
        private String version;
        private String manager;
    }
}

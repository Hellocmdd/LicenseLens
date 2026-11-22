package edu.course.licenselens.go;

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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 解析 Go 模块清单 go.mod 的独立扩展。
 * <p>
 * 本插件仅依赖 {@code licenselens-spi}，不引用 core 或 cli。
 *
 * <h3>支持的语法子集</h3>
 * <ul>
 *   <li>首行 {@code module <模块路径>}，允许 BOM、前导空白与行尾注释；</li>
 *   <li>块式 {@code require ( ... )} 与单行 {@code require <path> <version>}；</li>
 *   <li>{@code // indirect} 映射为 {@code scope = "indirect"}，其余为 {@code "runtime"}；</li>
 *   <li>{@code replace} / {@code exclude} / {@code retract} / {@code go} / {@code toolchain} 指令忽略；</li>
 *   <li>主版本后缀与伪版本字符串原样保留。</li>
 * </ul>
 *
 * <h3>字段与边界</h3>
 * <ul>
 *   <li>字段填充见 docs/CONTRACT.md 1.3：ecosystem = "go"，name = module path；</li>
 *   <li>按 require 声明顺序返回，不排序；空 require 块返回空列表；</li>
 *   <li>重复模块路径、缺字段、未闭合块、游离右括号均为 PARSE_ERROR，行号指向问题行。</li>
 * </ul>
 */
public class GoModManifestParser implements DependencySource {

    private static final String GO = "go";
    private static final String RUNTIME = "runtime";
    private static final String INDIRECT = "indirect";
    private static final Pattern INDIRECT_COMMENT = Pattern.compile("^indirect\\b");

    @Override
    public String id() {
        return GO;
    }

    /**
     * 仅接受文件名为 go.mod 且首行包含 module 指令的清单。
     *
     * @param ref 清单引用，允许为 null
     * @return 是否能解析该清单
     */
    @Override
    public boolean supports(ManifestRef ref) {
        if (ref == null || ref.fileName() == null || ref.firstLine() == null) {
            return false;
        }
        if (!"go.mod".equalsIgnoreCase(ref.fileName())) {
            return false;
        }
        // CONTRACT 1.1 要求同时判断文件名与首行特征，避免同名文件误判。
        String first = stripBom(ref.firstLine()).stripLeading();
        if (!first.startsWith("module")) {
            return false;
        }
        int rest = "module".length();
        return rest < first.length()
                && Character.isWhitespace(first.charAt(rest))
                && !first.substring(rest).strip().isEmpty();
    }

    /**
     * 解析 go.mod 中的 require 声明。
     *
     * @param file 清单路径，不能为 null，且必须是普通文件
     * @return 按声明顺序排列的依赖列表；无 require 时返回空列表
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
        ParseState state = new ParseState();
        for (int i = 0; i < lines.length; i++) {
            consumeLine(lines[i], i + 1, state);
        }
        verifyComplete(state);
        return List.copyOf(state.dependencies);
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

    private static void consumeLine(String rawLine, int lineNo, ParseState state)
            throws SourceException {
        String raw = lineNo == 1 ? stripBom(rawLine) : rawLine;
        String code = stripComment(raw).strip();
        if (code.isEmpty()) {
            return;
        }
        if (state.ignoredBlockStart > 0) {
            if (")".equals(code)) {
                state.ignoredBlockStart = -1;
            }
            return;
        }
        if (state.requireBlockStart > 0) {
            consumeRequireBlockLine(code, raw, lineNo, state);
            return;
        }
        consumeDirective(code, raw, lineNo, state);
    }

    private static void consumeRequireBlockLine(String code, String raw, int lineNo, ParseState state)
            throws SourceException {
        if (")".equals(code)) {
            state.requireBlockStart = -1;
            return;
        }
        String[] tokens = code.split("\\s+");
        if ("require".equals(tokens[0])) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "require 块内不允许再写 require 指令", lineNo);
        }
        addDependency(state, requireEntry(tokens, 0, raw, lineNo), lineNo);
    }

    private static void consumeDirective(String code, String raw, int lineNo, ParseState state)
            throws SourceException {
        String[] tokens = code.split("\\s+");
        if ("module".equals(tokens[0])) {
            requireModulePath(tokens, lineNo);
            state.sawModule = true;
            return;
        }
        if ("require".equals(tokens[0])) {
            consumeRequireDirective(tokens, raw, lineNo, state);
            return;
        }
        if (tokens.length == 2 && "(".equals(tokens[1])) {
            // replace / exclude / retract 等块与本插件无关，整块跳过。
            state.ignoredBlockStart = lineNo;
            return;
        }
        if (")".equals(code)) {
            throw new SourceException(SourceException.Code.PARSE_ERROR, "游离的右括号", lineNo);
        }
        // go / toolchain / 单行 replace / 单行 exclude 等指令均忽略。
    }

    private static void requireModulePath(String[] tokens, int lineNo) throws SourceException {
        if (tokens.length != 2) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "module 指令必须恰好指定一个模块路径", lineNo);
        }
    }

    private static void consumeRequireDirective(String[] tokens, String raw, int lineNo,
                                                ParseState state) throws SourceException {
        if (tokens.length == 2 && "(".equals(tokens[1])) {
            state.requireBlockStart = lineNo;
            return;
        }
        if (tokens.length == 2 && "()".equals(tokens[1])) {
            return;
        }
        addDependency(state, requireEntry(tokens, 1, raw, lineNo), lineNo);
    }

    private static void verifyComplete(ParseState state) throws SourceException {
        if (state.requireBlockStart > 0) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "require 块未闭合", state.requireBlockStart);
        }
        if (state.ignoredBlockStart > 0) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "指令块未闭合", state.ignoredBlockStart);
        }
        if (!state.sawModule) {
            throw new SourceException(SourceException.Code.PARSE_ERROR, "go.mod 缺少 module 指令", 1);
        }
    }

    private static Dependency requireEntry(String[] tokens, int offset, String raw, int line)
            throws SourceException {
        if (tokens.length < offset + 2) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "require 条目需要模块路径和版本", line);
        }
        if (tokens.length > offset + 2) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "require 条目字段过多: " + String.join(" ", tokens), line);
        }
        String scope = hasIndirectComment(raw) ? INDIRECT : RUNTIME;
        return new Dependency(GO, tokens[offset], tokens[offset + 1], scope);
    }

    private static void addDependency(ParseState state, Dependency dep, int line)
            throws SourceException {
        if (!state.seenModules.add(dep.name())) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "重复的 require 模块: " + dep.name(), line);
        }
        state.dependencies.add(dep);
    }

    private static boolean hasIndirectComment(String raw) {
        int index = raw.indexOf("//");
        if (index < 0) {
            return false;
        }
        return INDIRECT_COMMENT.matcher(raw.substring(index + 2).strip()).find();
    }

    private static String stripComment(String line) {
        int index = line.indexOf("//");
        return index < 0 ? line : line.substring(0, index);
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static final class ParseState {
        private final List<Dependency> dependencies = new ArrayList<>();
        private final Set<String> seenModules = new LinkedHashSet<>();
        private int requireBlockStart = -1;
        private int ignoredBlockStart = -1;
        private boolean sawModule;
    }
}

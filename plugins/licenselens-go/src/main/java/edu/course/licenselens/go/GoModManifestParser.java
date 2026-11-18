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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 独立扩展：Go go.mod 解析器。
 * <p>
 * 与 npm 插件一样和 core 完全解耦——只依赖 {@code licenselens-spi}。
 * 这是第二个独立扩展，用来验证“核心源码不变即可接入新生态”。
 *
 * <h3>支持的语法子集</h3>
 * <ul>
 *   <li>首行 {@code module <模块路径>}（允许 BOM、前导空白与行尾注释）；</li>
 *   <li>块式依赖 {@code require ( ... )}，含单行 {@code require <path> <version>}；</li>
 *   <li>{@code // indirect} 映射为 scope = "indirect"，其余为 "runtime"；</li>
 *   <li>{@code replace} / {@code exclude} / {@code retract} / {@code go} / {@code toolchain} 等指令忽略；</li>
 *   <li>主版本后缀（如 {@code /v8}）与伪版本字符串原样保留。</li>
 * </ul>
 *
 * <h3>字段填充约定（CONTRACT.md 1.3）</h3>
 * <ul>
 *   <li>ecosystem = "go"；</li>
 *   <li>name = module path；</li>
 *   <li>version = require 行中的版本字符串；</li>
 *   <li>scope = "runtime" 或 "indirect"。</li>
 * </ul>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>按 require 声明顺序返回，不排序；</li>
 *   <li>空 require 块返回空 List，不返回 null；</li>
 *   <li>重复的 module path、缺字段、未闭合块、游离右括号均按 PARSE_ERROR 处理，
 *       行号尽量指向问题行；未闭合块指向块起始行。</li>
 * </ul>
 *
 * <h3>依赖方向铁律</h3>
 * 本插件 pom 里**只有** {@code licenselens-spi} 依赖，不得出现 core。
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

    @Override
    public boolean supports(ManifestRef ref) {
        if (ref == null || ref.fileName() == null || ref.firstLine() == null) {
            return false;
        }
        if (!"go.mod".equalsIgnoreCase(ref.fileName())) {
            return false;
        }
        // CONTRACT 1.1：supports 同时看文件名与首行特征，避免同名文件误判。
        String first = stripBom(ref.firstLine()).stripLeading();
        if (!first.startsWith("module")) {
            return false;
        }
        int rest = "module".length();
        return rest < first.length()
                && Character.isWhitespace(first.charAt(rest))
                && !first.substring(rest).strip().isEmpty();
    }

    @Override
    public List<Dependency> parse(Path file) throws SourceException {
        if (file == null) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND, "清单路径为 null");
        }
        if (!Files.isRegularFile(file)) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND,
                    "清单文件不存在或不是普通文件: " + file);
        }

        String text = read(file);
        String[] lines = text.split("\\R", -1);
        List<Dependency> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int requireBlockStart = -1;
        int ignoredBlockStart = -1;
        boolean sawModule = false;

        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = i == 0 ? stripBom(lines[i]) : lines[i];
            String code = stripComment(raw).strip();
            if (code.isEmpty()) {
                continue;
            }

            if (ignoredBlockStart > 0) {
                if (")".equals(code)) {
                    ignoredBlockStart = -1;
                }
                continue;
            }

            if (requireBlockStart > 0) {
                if (")".equals(code)) {
                    requireBlockStart = -1;
                    continue;
                }
                String[] tokens = code.split("\\s+");
                if ("require".equals(tokens[0])) {
                    throw new SourceException(SourceException.Code.PARSE_ERROR,
                            "require 块内不允许再写 require 指令", lineNo);
                }
                addDependency(result, seen, requireEntry(tokens, 0, raw, lineNo), lineNo);
                continue;
            }

            String[] tokens = code.split("\\s+");
            if ("module".equals(tokens[0])) {
                if (tokens.length != 2) {
                    throw new SourceException(SourceException.Code.PARSE_ERROR,
                            "module 指令必须恰好指定一个模块路径", lineNo);
                }
                sawModule = true;
                continue;
            }
            if ("require".equals(tokens[0])) {
                if (tokens.length == 2 && "(".equals(tokens[1])) {
                    requireBlockStart = lineNo;
                    continue;
                }
                if (tokens.length == 2 && "()".equals(tokens[1])) {
                    continue;
                }
                addDependency(result, seen, requireEntry(tokens, 1, raw, lineNo), lineNo);
                continue;
            }
            if (tokens.length == 2 && "(".equals(tokens[1])) {
                // replace / exclude / retract 等块与本插件无关，整块跳过。
                ignoredBlockStart = lineNo;
                continue;
            }
            if (")".equals(code)) {
                throw new SourceException(SourceException.Code.PARSE_ERROR,
                        "游离的右括号", lineNo);
            }
            // go / toolchain / exclude 单行 / replace 单行 / retract 单行等指令均忽略。
        }

        if (requireBlockStart > 0) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "require 块未闭合", requireBlockStart);
        }
        if (ignoredBlockStart > 0) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "指令块未闭合", ignoredBlockStart);
        }
        if (!sawModule) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "go.mod 缺少 module 指令", 1);
        }
        return result;
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

    private static void addDependency(List<Dependency> result, Set<String> seen,
                                      Dependency dep, int line) throws SourceException {
        if (!seen.add(dep.name())) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "重复的 require 模块: " + dep.name(), line);
        }
        result.add(dep);
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
}

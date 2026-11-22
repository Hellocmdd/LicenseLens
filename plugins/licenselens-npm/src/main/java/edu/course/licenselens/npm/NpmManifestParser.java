package edu.course.licenselens.npm;

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
import java.util.List;
import java.util.Map;

/**
 * 独立扩展：npm package-lock.json 解析器。B 负责实现。
 * <p>
 * 这是 LicenseLens 的第一个独立扩展，和 core 完全解耦——
 * 只依赖 {@code licenselens-spi}，不引用 {@code licenselens-core}。
 *
 * <h3>支持的三种 lock 格式</h3>
 * <ul>
 *   <li>v1（npm 5.x）：顶层 {@code dependencies} 是声明来源，条目的 {@code dev} 决定 scope；</li>
 *   <li>v2（npm 7+）：{@code packages[""]} 是声明来源，{@code packages["node_modules/<name>"]} 提供安装版本；</li>
 *   <li>v3（npm 9+）：同 v2，但没有顶层 {@code dependencies}。</li>
 * </ul>
 *
 * <h3>字段填充约定（CONTRACT.md 1.3）</h3>
 * <ul>
 *   <li>ecosystem = "npm"；name = 包名（含 scope 的 {@code @a/b} 原样保留）；</li>
 *   <li>version = lock 条目中实际安装的版本；找不到条目时退化为根节点的声明范围；</li>
 *   <li>scope = "runtime" / "dev"。同时出现在 dependencies 和 devDependencies 时按 runtime 计。</li>
 * </ul>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>只返回根包声明的直接依赖，不展开传递依赖；</li>
 *   <li>按 JSON 中的声明顺序返回，不排序；</li>
 *   <li>空的 dependencies 返回空 List，不返回 null。</li>
 * </ul>
 *
 * <h3>依赖方向铁律</h3>
 * 本插件 pom 里**只有** {@code licenselens-spi} 依赖（JUnit 仅 test 作用域）。
 */
public class NpmManifestParser implements DependencySource {

    private static final String RUNTIME = "runtime";
    private static final String DEV = "dev";
    private static final String NPM = "npm";

    @Override
    public String id() {
        return NPM;
    }

    @Override
    public boolean supports(ManifestRef ref) {
        if (ref == null || ref.fileName() == null) {
            return false;
        }
        // 首行通常只是 "{" 或 BOM，没有 npm 特征，因此只按文件名判断，避免误判。
        return "package-lock.json".equalsIgnoreCase(ref.fileName());
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
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.JsonSyntaxException e) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "JSON 语法错误: " + e.getMessage(), e.line());
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "package-lock.json 根节点必须是 JSON 对象", 1);
        }

        Map<String, Object> rootMap = stringKeyed(root);
        Map<String, Object> packages = asObject(rootMap.get("packages"));
        Map<String, Object> topLevelDependencies = asObject(rootMap.get("dependencies"));
        if (!(rootMap.get("packages") instanceof Map<?, ?>)
                && !(rootMap.get("dependencies") instanceof Map<?, ?>)) {
            throw new SourceException(SourceException.Code.UNSUPPORTED_FORMAT,
                    "不是有效的 package-lock.json：缺少 packages / dependencies 对象");
        }

        Map<String, String> declaredRanges = new LinkedHashMap<>();
        Map<String, String> scopes = new LinkedHashMap<>();
        collectDirectDependencies(packages, topLevelDependencies, text, declaredRanges, scopes);

        List<Dependency> result = new ArrayList<>(declaredRanges.size());
        for (Map.Entry<String, String> declaration : declaredRanges.entrySet()) {
            String name = declaration.getKey();
            String version = resolveInstalledVersion(packages, topLevelDependencies, name);
            if (version == null) {
                version = declaration.getValue();
            }
            result.add(new Dependency(NPM, name, version, scopes.get(name)));
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

    private static void collectDirectDependencies(
            Map<String, Object> packages,
            Map<String, Object> topLevelDependencies,
            String text,
            Map<String, String> declaredRanges,
            Map<String, String> scopes) throws SourceException {

        if (packages.containsKey("")) {
            // v2 / v3：根包的 dependencies + devDependencies 才是直接依赖
            addDeclarations(asObject(asObject(packages.get("")).get("dependencies")),
                    RUNTIME, text, declaredRanges, scopes);
            addDeclarations(asObject(asObject(packages.get("")).get("devDependencies")),
                    DEV, text, declaredRanges, scopes);
        } else {
            // v1：顶层 dependencies 包含所有提升后的包，dev 标记区分 scope
            for (Map.Entry<String, Object> entry : topLevelDependencies.entrySet()) {
                String name = entry.getKey();
                Map<String, Object> item = asObject(entry.getValue());
                String version = stringValue(item.get("version"));
                if (version == null) {
                    throw new SourceException(SourceException.Code.PARSE_ERROR,
                            "依赖 " + name + " 缺少字符串 version", lineOfKey(text, name));
                }
                declaredRanges.put(name, version);
                scopes.put(name, Boolean.TRUE.equals(item.get("dev")) ? DEV : RUNTIME);
            }
        }
    }

    private static void addDeclarations(
            Map<String, Object> declarations,
            String scope,
            String text,
            Map<String, String> declaredRanges,
            Map<String, String> scopes) throws SourceException {

        for (Map.Entry<String, Object> entry : declarations.entrySet()) {
            String name = entry.getKey();
            String range = stringValue(entry.getValue());
            if (range == null) {
                throw new SourceException(SourceException.Code.PARSE_ERROR,
                        "依赖 " + name + " 的版本声明必须是字符串", lineOfKey(text, name));
            }
            if (!declaredRanges.containsKey(name)) {
                declaredRanges.put(name, range);
                scopes.put(name, scope);
            }
        }
    }

    private static String resolveInstalledVersion(
            Map<String, Object> packages, Map<String, Object> topLevelDependencies, String name) {
        String version = stringValue(asObject(packages.get("node_modules/" + name)).get("version"));
        if (version == null) {
            version = stringValue(asObject(topLevelDependencies.get(name)).get("version"));
        }
        return version;
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return stringKeyed(map);
        }
        return Map.of();
    }

    /** 尽力定位出错依赖所在行，让 PARSE_ERROR 的 line 对调用方真正有用。 */
    private static int lineOfKey(String text, String key) {
        int index = text.indexOf('"' + key + '"');
        if (index < 0) {
            return 1;
        }
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}

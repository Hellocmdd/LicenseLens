package edu.course.licenselens.core;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.RiskLevel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 许可证快照匹配。
 * <p>
 * 数据来源：{@code licenses.json}，放在 {@code src/main/resources/}。
 * 匹配规则：精确匹配 {@link Dependency#name()}。未命中返回 UNKNOWN，**不猜测**。
 */
public class LicenseCatalog {

    private final Map<String, LicenseInfo> entries;

    /** 使用仓库内置的 {@code licenses.json} 快照。 */
    public LicenseCatalog() {
        this(null);
    }

    /**
     * 使用外置许可证快照；路径为空、文件不存在或解析失败时回退内置快照。
     *
     * @param externalCatalog 外置 {@code licenses.json} 路径，可为 {@code null}
     */
    public LicenseCatalog(Path externalCatalog) {
        this.entries = externalCatalog == null ? load() : loadExternal(externalCatalog);
    }

    /**
     * 根据依赖名查许可证（向后兼容的纯名称查找）。
     *
     * @param depName 依赖名（Maven groupId:artifactId，npm 包名）
     * @return 匹配到的 LicenseInfo；未命中返回 UNKNOWN 占位
     */
    public LicenseInfo lookup(String depName) {
        LicenseInfo info = entries.get(depName);
        if (info != null) return info;
        return new LicenseInfo(null, "未知许可证", RiskLevel.UNKNOWN);
    }

    /**
     * 跨生态许可证查找（Issue #11）。
     * <p>
     * 匹配顺序：
     * <ol>
     *   <li>先按 {@code ecosystem:name} 精确命中（如 {@code npm:lodash}、{@code python:requests}）</li>
     *   <li>未命中再按纯 {@code name} 回退（向后兼容 Maven 坐标）</li>
     *   <li>都没命中 → UNKNOWN</li>
     * </ol>
     */
    public LicenseInfo lookup(Dependency dep) {
        String keyed = dep.ecosystem() + ":" + dep.name();
        LicenseInfo info = entries.get(keyed);
        if (info != null) return info;
        return lookup(dep.name());
    }

    Map<String, LicenseInfo> getEntries() {
        return entries;
    }

    private Map<String, LicenseInfo> load() {
        try (InputStream is = getClass().getResourceAsStream("/licenses.json")) {
            if (is == null) {
                System.err.println("WARN: licenses.json 未找到，许可证快照为空");
                return new HashMap<>();
            }
            return parse(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("WARN: licenses.json 加载失败: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private Map<String, LicenseInfo> loadExternal(Path path) {
        if (!Files.isRegularFile(path)) {
            System.err.println("WARN: 外置许可证快照不存在，回退内置快照: " + path);
            return load();
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            System.err.println("WARN: 外置许可证快照读取失败，回退内置快照: " + e.getMessage());
            return load();
        }
    }

    private static Map<String, LicenseInfo> parse(String content) {
        Map<String, LicenseInfo> map = new HashMap<>();
        JSONObject root = new JSONObject(content);
        for (String key : root.keySet()) {
            JSONObject obj = root.getJSONObject(key);
            String spdxId = obj.optString("spdxId", null);
            String name = obj.optString("name", null);
            String riskStr = obj.optString("risk", "UNKNOWN");
            RiskLevel risk;
            try {
                risk = RiskLevel.valueOf(riskStr);
            } catch (IllegalArgumentException e) {
                risk = RiskLevel.UNKNOWN;
            }
            map.put(key, new LicenseInfo(spdxId, name, risk));
        }
        return map;
    }
}

package edu.course.licenselens.core;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.RiskLevel;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

/**
 * 许可证快照匹配。
 * <p>
 * 数据来源：{@code licenses.json}，放在 {@code src/main/resources/}。
 * 匹配规则：精确匹配 {@link Dependency#name()}。未命中返回 UNKNOWN，**不猜测**。
 */
public class LicenseCatalog {

    private final Map<String, LicenseInfo> entries;

    public LicenseCatalog() {
        this.entries = load();
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
        Map<String, LicenseInfo> map = new HashMap<>();
        try (InputStream is = getClass().getResourceAsStream("/licenses.json")) {
            if (is == null) {
                System.err.println("WARN: licenses.json 未找到，许可证快照为空");
                return map;
            }
            String content = new String(is.readAllBytes());
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
        } catch (IOException e) {
            System.err.println("WARN: licenses.json 加载失败: " + e.getMessage());
        }
        return map;
    }
}

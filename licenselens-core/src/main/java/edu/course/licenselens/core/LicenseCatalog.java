package edu.course.licenselens.core;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.RiskLevel;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 许可证快照匹配。A 负责实现。
 * <p>
 * 数据来源：{@code licenses.json}，放在 {@code src/main/resources/}。
 * 匹配规则：精确匹配 {@link Dependency#name()}（Maven 的 groupId:artifactId 或 npm 包名）。
 * 未命中返回 UNKNOWN，**不猜测**。
 */
public class LicenseCatalog {

    private final Map<String, LicenseInfo> entries;

    public LicenseCatalog() {
        this.entries = load();
    }

    /**
     * 根据依赖名查许可证。
     *
     * @param depName 依赖名（Maven groupId:artifactId，npm 包名）
     * @return 匹配到的 LicenseInfo；未命中返回 UNKNOWN 占位
     */
    public LicenseInfo lookup(String depName) {
        LicenseInfo info = entries.get(depName);
        if (info != null) return info;
        // 未命中 → UNKNOWN
        return new LicenseInfo(null, "未知许可证", RiskLevel.UNKNOWN);
    }

    private Map<String, LicenseInfo> load() {
        // TODO: A 实现——读 classpath:licenses.json，解析为 Map
        // 暂时返回空 Map，让骨架编译通过
        return new HashMap<>();
    }
}

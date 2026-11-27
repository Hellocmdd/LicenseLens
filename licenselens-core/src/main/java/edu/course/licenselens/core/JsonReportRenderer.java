package edu.course.licenselens.core;

import edu.course.licenselens.spi.Decision;
import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.Policy;

import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 报告渲染器。
 * <p>
 * 字段顺序固定，不依赖 {@link JSONObject} 的键迭代顺序；
 * 依赖行排序与 {@link ReportRenderer} 完全一致。
 */
public class JsonReportRenderer {

    /**
     * 渲染完整 JSON 报告。
     *
     * @param rows     扫描结果行
     * @param policy   使用的策略
     * @param manifest 清单文件路径
     * @return JSON 字符串
     */
    public String render(List<ReportRenderer.ScanRow> rows, Policy policy, String manifest) {
        List<ReportRenderer.ScanRow> sorted = new ArrayList<>(rows);
        sorted.sort(ReportRenderer.SORTER);

        ReportRenderer.ScanStats stats = ReportRenderer.ScanStats.from(sorted);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"manifest\": ").append(quote(manifest)).append(",\n");
        sb.append("  \"policy\": ").append(quote(policy.id())).append(",\n");
        sb.append("  \"generatedAt\": ").append(quote(Instant.now().toString())).append(",\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"total\": ").append(stats.total()).append(",\n");
        sb.append("    \"blocked\": ").append(stats.blocked()).append(",\n");
        sb.append("    \"warned\": ").append(stats.warned()).append(",\n");
        sb.append("    \"unknown\": ").append(stats.unknown()).append("\n");
        sb.append("  },\n");
        sb.append("  \"dependencies\": [\n");
        for (int i = 0; i < sorted.size(); i++) {
            ReportRenderer.ScanRow row = sorted.get(i);
            appendRow(sb, row, i == sorted.size() - 1);
        }
        sb.append("  ],\n");
        sb.append("  \"result\": ").append(quote(stats.blocked() > 0 ? "BLOCKED" : "PASS")).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, ReportRenderer.ScanRow row, boolean last) {
        Dependency dep = row.dep();
        LicenseInfo lic = row.license();
        Decision dec = row.decision();

        sb.append("    {\n");
        sb.append("      \"ecosystem\": ").append(quote(dep.ecosystem())).append(",\n");
        sb.append("      \"name\": ").append(quote(dep.name())).append(",\n");
        sb.append("      \"version\": ").append(quote(dep.version())).append(",\n");
        sb.append("      \"scope\": ").append(quote(dep.scope())).append(",\n");
        sb.append("      \"license\": {\n");
        sb.append("        \"spdxId\": ").append(quote(lic.spdxId())).append(",\n");
        sb.append("        \"name\": ").append(quote(lic.name())).append(",\n");
        sb.append("        \"risk\": ").append(quote(lic.risk().name())).append("\n");
        sb.append("      },\n");
        sb.append("      \"decision\": {\n");
        sb.append("        \"allowed\": ").append(dec.allowed()).append(",\n");
        sb.append("        \"warning\": ").append(dec.warning()).append(",\n");
        sb.append("        \"reason\": ").append(quote(dec.reason())).append("\n");
        sb.append("      }\n");
        sb.append("    }").append(last ? "\n" : ",\n");
    }

    private static String quote(String value) {
        return JSONObject.quote(value == null ? "" : value);
    }
}

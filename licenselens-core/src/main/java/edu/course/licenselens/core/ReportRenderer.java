package edu.course.licenselens.core;

import edu.course.licenselens.spi.Decision;
import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.Policy;
import edu.course.licenselens.spi.RiskLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Markdown 报告渲染器。
 * <p>
 * 关键要求：**确定性排序**——同一输入两次输出字节完全一致，保证测试可 diff。
 * 排序规则：风险降序（STRONG_COPYLEFT → WEAK_COPYLEFT → PERMISSIVE → UNKNOWN），
 * 同风险内名称升序（按 {@code Dependency::name}）。
 */
public class ReportRenderer {

    private static final List<RiskLevel> RISK_ORDER = List.of(
            RiskLevel.STRONG_COPYLEFT,
            RiskLevel.WEAK_COPYLEFT,
            RiskLevel.PERMISSIVE,
            RiskLevel.UNKNOWN
    );

    private static final Comparator<ScanRow> SORTER =
            Comparator.comparingInt((ScanRow r) -> RISK_ORDER.indexOf(r.license().risk()))
                    .thenComparing(r -> r.dep().name());

    /**
     * 一行报告数据。
     */
    public record ScanRow(Dependency dep, LicenseInfo license, Decision decision) {}

    /**
     * 渲染完整 Markdown 报告。
     *
     * @param rows     扫描结果行（排序在此方法内完成）
     * @param policy   使用的策略
     * @param manifest 清单文件路径
     * @return Markdown 字符串
     */
    public String render(List<ScanRow> rows, Policy policy, String manifest) {
        List<ScanRow> sorted = new ArrayList<>(rows);
        sorted.sort(SORTER);

        int total = sorted.size();
        long blocked = sorted.stream().filter(r -> !r.decision().allowed()).count();
        long warned = sorted.stream().filter(r -> r.decision().warning()).count();
        long unknown = sorted.stream().filter(r -> r.license().risk() == RiskLevel.UNKNOWN).count();

        StringBuilder sb = new StringBuilder();
        sb.append("# LicenseLens 审计报告\n\n");
        sb.append("**清单**: ").append(manifest).append("\n");
        sb.append("**策略**: ").append(policy.id()).append("\n");
        sb.append("**生成时间**: ").append(Instant.now()).append("\n\n");

        sb.append("## 摘要\n\n");
        sb.append("| 指标 | 数值 |\n|---|---|\n");
        sb.append("| 依赖总数 | ").append(total).append(" |\n");
        sb.append("| 阻断依赖 | ").append(blocked).append(" |\n");
        sb.append("| 警告依赖 | ").append(warned).append(" |\n");
        sb.append("| 未知许可证 | ").append(unknown).append(" |\n\n");

        sb.append("## 详细结果\n\n");
        sb.append("| 风险 | 依赖 | 许可证 | 策略决策 | 理由 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (ScanRow row : sorted) {
            Dependency dep = row.dep();
            LicenseInfo lic = row.license();
            Decision dec = row.decision();
            String risk = lic.risk().name();
            String status = dec.allowed() ? (dec.warning() ? "⚠️ 警告" : "✅ 通过") : "❌ 阻断";
            String licenseDisplay = lic.spdxId() != null ? lic.spdxId() : lic.name();
            sb.append("| ").append(risk)
                    .append(" | ").append(dep.name()).append(":").append(dep.version())
                    .append(" | ").append(licenseDisplay)
                    .append(" | ").append(status)
                    .append(" | ").append(dec.reason())
                    .append(" |\n");
        }
        sb.append("\n");

        if (blocked > 0) {
            sb.append("## ⚠️ 审计未通过\n\n");
            sb.append("存在 ").append(blocked).append(" 个阻断依赖，请在合并或发布前处理。\n");
        } else {
            sb.append("## ✅ 审计通过\n\n");
            sb.append("所有依赖符合策略要求。\n");
        }

        return sb.toString();
    }
}

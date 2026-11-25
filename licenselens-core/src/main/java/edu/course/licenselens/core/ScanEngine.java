package edu.course.licenselens.core;

import edu.course.licenselens.spi.Decision;
import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.Policy;
import edu.course.licenselens.spi.RiskLevel;
import edu.course.licenselens.spi.SourceException;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 扫描调度引擎。
 * <p>
 * 完整扫描流程：
 * <ol>
 *   <li>校验文件路径</li>
 *   <li>读取首行（用于 supports 判断）</li>
 *   <li>按 id() 字典序选择 DependencySource（契约 §四）</li>
 *   <li>调用 parser.parse() 拿到 List&lt;Dependency&gt;</li>
 *   <li>逐条查 LicenseCatalog → LicenseInfo</li>
 *   <li>调用 Policy.evaluate() → Decision</li>
 *   <li>ReportRenderer 生成 Markdown 报告（含确定性排序）</li>
 * </ol>
 */
public class ScanEngine {

    private final LicenseCatalog catalog;
    private final List<DependencySource> sources;
    private final PolicyEngine policyEngine;

    /**
     * 用策略列表构造扫描引擎，策略选择统一交给 {@link PolicyEngine}。
     */
    public ScanEngine(LicenseCatalog catalog,
                      List<DependencySource> sources,
                      List<Policy> policies) {
        this(catalog, sources, new PolicyEngine(policies));
    }

    /**
     * 用策略引擎构造扫描引擎，便于 CLI 在扫描前复用同一份策略校验。
     */
    public ScanEngine(LicenseCatalog catalog,
                      List<DependencySource> sources,
                      PolicyEngine policyEngine) {
        this.catalog = catalog;
        this.sources = sources;
        this.policyEngine = policyEngine;
    }

    /**
     * 执行扫描。
     *
     * @param manifest 清单文件路径
     * @param policyId 策略标识（"campus" 或 "permissive-only"）
     * @return 扫描结果（含退出码和报告）
     */
    public ScanResult scan(Path manifest, String policyId) throws SourceException {
        return scan(manifest, policyId, "markdown");
    }

    /**
     * 执行扫描并按指定格式生成报告。
     *
     * @param manifest 清单文件路径
     * @param policyId 策略标识（"campus" 或 "permissive-only"）
     * @param format   报告格式，"markdown" 或 "json"
     * @return 扫描结果（含退出码和报告）
     * @throws SourceException 清单不存在、语法错误或没有解析器支持该格式
     */
    public ScanResult scan(Path manifest, String policyId, String format) throws SourceException {
        if (!"markdown".equals(format) && !"json".equals(format)) {
            throw new IllegalArgumentException("不支持的报告格式: " + format);
        }

        // 1. 文件校验
        if (!Files.exists(manifest)) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND,
                    "清单文件不存在: " + manifest);
        }

        // 2. 构建 ManifestRef（含首行嗅探）
        String fileName = manifest.getFileName().toString();
        String firstLine;
        try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            firstLine = line == null ? "" : line;
        } catch (FileNotFoundException e) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND,
                    "清单文件不存在: " + manifest);
        } catch (IOException e) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "无法读取清单文件: " + e.getMessage());
        }
        ManifestRef ref = new ManifestRef(manifest, fileName, firstLine);

        // 3. 选择 DependencySource
        //    契约 §四：按 id() 字典序取第一个 supports() 为 true 的解析器。
        //    List.sort 是稳定排序，同 id 时保持注入顺序，结果仍然确定。
        List<DependencySource> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparing(DependencySource::id));
        DependencySource selected = null;
        for (DependencySource src : ordered) {
            try {
                if (src.supports(ref)) {
                    selected = src;
                    break;
                }
            } catch (RuntimeException e) {
                System.err.println("WARN: 解析器 " + src.id()
                        + " 的 supports 抛异常（已跳过）: " + e.getMessage());
            } catch (LinkageError e) {
                System.err.println("WARN: 解析器 " + src.id()
                        + " 的 supports 链接失败（已跳过）: " + e.getMessage());
            }
        }
        if (selected == null) {
            throw new SourceException(SourceException.Code.UNSUPPORTED_FORMAT,
                    "没有可用解析器处理此文件格式: " + fileName);
        }

        // 4. 解析依赖列表
        List<Dependency> deps = selected.parse(manifest);

        // 5. 选策略：统一走 PolicyEngine，避免与 CLI 各自实现一份选择逻辑
        Policy policy = policyEngine.select(policyId);

        // 6. 逐条匹配许可证 + 策略判定
        ReportRenderer renderer = new ReportRenderer();
        List<ReportRenderer.ScanRow> rows = new ArrayList<>();
        int blockedCount = 0;
        int unknownCount = 0;

        for (Dependency dep : deps) {
            LicenseInfo info = catalog.lookup(dep);
            Decision dec = policy.evaluate(info, dep);
            rows.add(new ReportRenderer.ScanRow(dep, info, dec));
            if (!dec.allowed()) blockedCount++;
            if (info.risk() == RiskLevel.UNKNOWN) unknownCount++;
        }

        // 7. 生成报告
        String report = "json".equals(format)
                ? new JsonReportRenderer().render(rows, policy, manifest.toString())
                : renderer.render(rows, policy, manifest.toString());

        // 8. 计算退出码
        int exitCode = blockedCount > 0 ? 1 : 0;

        return new ScanResult(exitCode, deps.size(), blockedCount, unknownCount, report);
    }
}

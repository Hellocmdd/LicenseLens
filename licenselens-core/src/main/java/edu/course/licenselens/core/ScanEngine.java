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
import java.io.IOException;
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
    private final List<Policy> policies;

    public ScanEngine(LicenseCatalog catalog,
                      List<DependencySource> sources,
                      List<Policy> policies) {
        this.catalog = catalog;
        this.sources = sources;
        this.policies = policies;
    }

    /**
     * 执行扫描。
     *
     * @param manifest 清单文件路径
     * @param policyId 策略标识（"campus" 或 "permissive-only"）
     * @return 扫描结果（含退出码和报告）
     */
    public ScanResult scan(Path manifest, String policyId) throws SourceException {
        // 1. 防御性文件存在性检查（即使 CLI 层已检查，ScanEngine 作为公共 API 不能假设调用方做过）
        if (!Files.exists(manifest)) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND,
                    "清单文件不存在: " + manifest);
        }

        // 2. 构建 ManifestRef（首行嗅探）—— 流式读避免大文件 OOM
        String fileName = manifest.getFileName().toString();
        String firstLine;
        try (BufferedReader br = Files.newBufferedReader(manifest)) {
            firstLine = br.readLine();
            if (firstLine == null) {
                firstLine = ""; // 空文件
            }
        } catch (java.io.FileNotFoundException e) {
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
            } catch (Throwable t) {
                // 某个解析器 supports 抛异常 → 跳过继续找
                System.err.println("WARN: 解析器 " + src.id()
                        + " supports() 抛异常（已跳过）: " + t.getMessage());
            }
        }
        if (selected == null) {
            throw new SourceException(SourceException.Code.UNSUPPORTED_FORMAT,
                    "没有可用解析器处理此文件格式: " + fileName);
        }

        // 3. 解析依赖列表
        List<Dependency> deps = selected.parse(manifest);

        // 4. 选策略
        Policy policy = policies.stream()
                .filter(p -> p.id().equals(policyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知策略: " + policyId));

        // 5. 逐条匹配许可证 + 策略判定
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

        // 6. 生成报告
        String report = renderer.render(rows, policy, manifest.toString());

        // 7. 计算退出码
        int exitCode = blockedCount > 0 ? 1 : 0;

        return new ScanResult(exitCode, deps.size(), blockedCount, unknownCount, report);
    }
}

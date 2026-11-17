package edu.course.licenselens.core;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.Policy;
import edu.course.licenselens.spi.SourceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 扫描调度引擎。A 负责实现。
 * <p>
 * 职责：
 * <ol>
 *   <li>校验文件路径</li>
 *   <li>按 supports() 选择 DependencySource</li>
 *   <li>调用 parser.parse() 拿到 List&lt;Dependency&gt;</li>
 *   <li>逐条查 LicenseCatalog → LicenseInfo</li>
 *   <li>调用 Policy.evaluate() → Decision</li>
 *   <li>排序（风险降序，名称升序）</li>
 *   <li>ReportRenderer 生成报告</li>
 * </ol>
 *
 * @see LicenseCatalog
 * @see Policy
 * @see ReportRenderer
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
        // TODO: A 实现
        throw new UnsupportedOperationException("ScanEngine.scan 待实现");
    }
}

package edu.course.licenselens.cli;

import edu.course.licenselens.core.CampusPolicy;
import edu.course.licenselens.core.LicenseCatalog;
import edu.course.licenselens.core.PermissiveOnlyPolicy;
import edu.course.licenselens.core.ScanEngine;
import edu.course.licenselens.core.ScanResult;
import edu.course.licenselens.core.internal.MavenManifestParser;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.Policy;
import edu.course.licenselens.spi.SourceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLI 入口类。C 负责。
 * <p>
 * 命令行：
 * <pre>
 * licenselens scan --manifest pom.xml [--policy campus] [--plugins ./plugins] [--out ./report.md]
 * </pre>
 *
 * 退出码：见 {@link ExitCode}。
 */
public class Main {

    private static final String USAGE = """
            LicenseLens - 开源依赖许可证合规审计器
            用法: licenselens scan --manifest <文件> [选项]
            
            选项:
              --manifest <路径>    清单文件（pom.xml 或 package-lock.json）  [必填]
              --policy <策略>      campus | permissive-only                    [默认: campus]
              --plugins <目录>     插件目录                                       [默认: ./plugins]
              --out <路径>         报告输出路径                                   [默认: ./report.md]
              -h, --help           显示帮助""";

    public static void main(String[] args) {
        ExitCode code = run(args);
        System.exit(code.code);
    }

    /**
     * 运行主逻辑，返回退出码（测试可调用此方法，不直接 System.exit）。
     */
    static ExitCode run(String[] args) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.println(USAGE);
            return ExitCode.OK;
        }
        if (!"scan".equals(args[0])) {
            System.err.println("未知子命令: " + args[0]);
            System.out.println(USAGE);
            return ExitCode.FILE_NOT_FOUND; // 语义接近"输入错误"
        }

        // --- 参数解析 ---
        String manifestPath = null;
        String policyId = "campus";
        String pluginsDir = "./plugins";
        String outPath = "./report.md";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifestPath = args[++i];
                case "--policy"   -> policyId = args[++i];
                case "--plugins"  -> pluginsDir = args[++i];
                case "--out"      -> outPath = args[++i];
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    System.out.println(USAGE);
                    return ExitCode.FILE_NOT_FOUND;
                }
            }
        }

        if (manifestPath == null) {
            System.err.println("缺少必填参数: --manifest");
            System.out.println(USAGE);
            return ExitCode.FILE_NOT_FOUND;
        }

        // --- 文件校验 ---
        Path manifest = Path.of(manifestPath);
        if (!Files.exists(manifest)) {
            System.err.println("清单文件不存在: " + manifestPath);
            return ExitCode.FILE_NOT_FOUND;
        }

        // --- 组装依赖 ---
        // 内置 Maven 解析器 + 插件目录加载的外部解析器
        List<DependencySource> sources = new ArrayList<>();
        sources.add(new MavenManifestParser());
        sources.addAll(new PluginLoader().loadAll(new java.io.File(pluginsDir)));

        // 两种内置策略
        List<Policy> policies = Arrays.asList(new CampusPolicy(), new PermissiveOnlyPolicy());

        ScanEngine engine = new ScanEngine(new LicenseCatalog(), sources, policies);

        // --- 执行扫描 ---
        try {
            ScanResult result = engine.scan(manifest, policyId);

            // 写报告文件
            try {
                Files.writeString(Path.of(outPath), result.reportContent());
            } catch (IOException e) {
                System.err.println("报告写入失败: " + e.getMessage());
            }

            // 打印终端摘要
            System.out.printf("依赖数: %d | 阻断: %d | 未知: %d%n",
                    result.totalCount(), result.blockedCount(), result.unknownCount());

            if (result.blockedCount() > 0) {
                return ExitCode.BLOCKED;
            }
            return ExitCode.OK;

        } catch (SourceException e) {
            return switch (e.code()) {
                case FILE_NOT_FOUND    -> {
                    System.err.println("清单文件不存在: " + manifestPath);
                    yield ExitCode.FILE_NOT_FOUND;
                }
                case PARSE_ERROR       -> {
                    if (e.line() > 0) {
                        System.err.println("清单第 " + e.line() + " 行附近格式错误: " + e.getMessage());
                    } else {
                        System.err.println("清单格式错误: " + e.getMessage());
                    }
                    yield ExitCode.PARSE_ERROR;
                }
                case UNSUPPORTED_FORMAT -> {
                    System.err.println("未找到可解析该文件的插件，请检查 plugins/ 目录");
                    yield ExitCode.NO_PARSER;
                }
            };
        } catch (UnsupportedOperationException e) {
            System.err.println("骨架代码，功能待实现: " + e.getMessage());
            return ExitCode.OK; // 骨架阶段先不报致命错误
        }
    }
}

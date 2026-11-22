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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI 入口类。
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
              --manifest <路径>    清单文件（pom.xml / package-lock.json / go.mod）  [必填]
              --policy <策略>      campus | permissive-only                        [默认: campus]
              --plugins <目录>     插件目录                                         [默认: ./plugins]
              --out <路径>         报告输出路径                                     [默认: ./report.md]
              -h, --help           显示帮助""";

    private static final String DEFAULT_POLICY = "campus";
    private static final String DEFAULT_PLUGINS = "./plugins";
    private static final String DEFAULT_OUT = "./report.md";

    public static void main(String[] args) {
        ExitCode code = run(args);
        System.exit(code.code);
    }

    /**
     * 运行主逻辑，返回退出码（测试可调用此方法，不直接 System.exit）。
     *
     * @param args 命令行参数
     * @return 退出码，不抛出运行时异常
     */
    static ExitCode run(String[] args) {
        if (args.length == 0 || containsHelp(args)) {
            System.out.println(USAGE);
            return ExitCode.OK;
        }
        if (!"scan".equals(args[0])) {
            return usageError("未知子命令: " + args[0]);
        }

        Options options;
        try {
            options = parseOptions(args);
        } catch (IllegalArgumentException e) {
            return usageError(e.getMessage());
        }

        Path manifest = options.manifest();
        if (!Files.exists(manifest)) {
            System.err.println("清单文件不存在: " + manifest);
            return ExitCode.FILE_NOT_FOUND;
        }
        if (!Files.isDirectory(options.pluginsDir())) {
            System.err.println("WARN: 插件目录不存在，跳过插件加载: " + options.pluginsDir());
        }

        List<DependencySource> sources = new ArrayList<>();
        sources.add(new MavenManifestParser());
        sources.addAll(new PluginLoader().loadAll(options.pluginsDir().toFile()));

        List<Policy> policies = List.of(new CampusPolicy(), new PermissiveOnlyPolicy());
        if (policies.stream().noneMatch(p -> p.id().equals(options.policyId()))) {
            return usageError("未知策略: " + options.policyId() + "，可选: " + policyIds(policies));
        }

        ScanEngine engine = new ScanEngine(new LicenseCatalog(), sources, policies);

        try {
            ScanResult result = engine.scan(manifest, options.policyId());

            if (!writeReport(options.out(), result.reportContent())) {
                System.err.println("WARN: 报告未写出，退出码只反映审计结果");
            }

            System.out.printf("策略: %s | 依赖数: %d | 阻断: %d | 未知: %d%n",
                    options.policyId(), result.totalCount(),
                    result.blockedCount(), result.unknownCount());

            return result.blockedCount() > 0 ? ExitCode.BLOCKED : ExitCode.OK;

        } catch (SourceException e) {
            return switch (e.code()) {
                case FILE_NOT_FOUND -> {
                    System.err.println("清单文件不存在: " + manifest);
                    yield ExitCode.FILE_NOT_FOUND;
                }
                case PARSE_ERROR -> {
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
        }
    }

    /**
     * 解析 {@code scan} 之后的参数；非法输入抛 {@link IllegalArgumentException}。
     */
    private static Options parseOptions(String[] args) {
        String manifestPath = null;
        String policyId = DEFAULT_POLICY;
        String pluginsDir = DEFAULT_PLUGINS;
        String outPath = DEFAULT_OUT;

        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--manifest" -> manifestPath = requireValue(args, ++i, flag);
                case "--policy" -> policyId = requireValue(args, ++i, flag);
                case "--plugins" -> pluginsDir = requireValue(args, ++i, flag);
                case "--out" -> outPath = requireValue(args, ++i, flag);
                default -> throw new IllegalArgumentException("未知参数: " + flag);
            }
        }

        if (manifestPath == null) {
            throw new IllegalArgumentException("缺少必填参数: --manifest");
        }
        return new Options(Path.of(manifestPath), policyId, Path.of(pluginsDir), Path.of(outPath));
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少取值");
        }
        return args[index];
    }

    private static boolean writeReport(Path out, String content) {
        try {
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            System.err.println("报告写入失败: " + out + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private static String policyIds(List<Policy> policies) {
        return String.join(", ", policies.stream().map(Policy::id).toList());
    }

    /**
     * 参数错误的统一出口。
     * <p>
     * 契约退出码表里没有"用法错误"这一档，暂复用 2（清单文件不存在）并给出明确提示；
     * 是否新增独立退出码属于契约变更，需全组确认后再改。
     */
    private static ExitCode usageError(String message) {
        System.err.println("参数错误: " + message);
        System.err.println("运行 licenselens --help 查看用法");
        return ExitCode.FILE_NOT_FOUND;
    }

    private static boolean containsHelp(String[] args) {
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /** 解析后的命令参数。 */
    private record Options(Path manifest, String policyId, Path pluginsDir, Path out) {
    }
}

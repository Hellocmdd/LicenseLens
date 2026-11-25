package edu.course.licenselens.cli;

import edu.course.licenselens.spi.DependencySource;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * 插件目录加载器，负责发现外部 {@link DependencySource} 实现并管理其类加载器。
 * <p>
 * 加载失败的 jar 跳过并告警，不中断扫描；类加载器在调用方结束扫描后关闭，
 * 避免插件类在 {@code parse()} 期间失效。
 */
public class PluginLoader implements AutoCloseable {

    private final List<URLClassLoader> loaders = new ArrayList<>();

    /**
     * 扫描插件目录下的一级 {@code *.jar}，返回发现的解析器列表。
     *
     * @param pluginDir 插件目录；为 {@code null} 或不存在时返回空列表
     * @return 发现的 DependencySource 列表；顺序不稳定，调用方按 {@code id()} 排序后使用
     */
    public List<DependencySource> loadAll(Path pluginDir) {
        List<DependencySource> sources = new ArrayList<>();
        if (pluginDir == null || !Files.isDirectory(pluginDir)) {
            return sources;
        }

        try (Stream<Path> jars = Files.list(pluginDir)) {
            jars.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .forEach(jar -> loadOne(jar, sources));
        } catch (IOException e) {
            System.err.println("WARN: 无法列出插件目录 " + pluginDir + ": " + e.getMessage());
        }
        return sources;
    }

    private void loadOne(Path jar, List<DependencySource> sources) {
        try (ZipFile ignored = new ZipFile(jar.toFile())) {
            // 损坏 jar 会被 URLClassLoader 静默忽略，先校验结构以便按 jar 名告警
        } catch (IOException e) {
            warn(jar, "无法打开", e);
            return;
        }

        try {
            URL url = jar.toUri().toURL();
            URLClassLoader loader = new URLClassLoader(new URL[]{url},
                    PluginLoader.class.getClassLoader());
            loaders.add(loader);
            ServiceLoader<DependencySource> serviceLoader =
                    ServiceLoader.load(DependencySource.class, loader);
            Iterator<DependencySource> it = serviceLoader.iterator();
            while (it.hasNext()) {
                try {
                    sources.add(it.next());
                } catch (Exception | ServiceConfigurationError | LinkageError e) {
                    warn(jar, "实例创建失败", e);
                }
            }
        } catch (Exception | ServiceConfigurationError | LinkageError e) {
            warn(jar, "无法打开", e);
        }
    }

    private static void warn(Path jar, String action, Throwable error) {
        System.err.println("WARN: 插件 " + jar.getFileName() + " " + action
                + "（已跳过）: " + error.getClass().getSimpleName() + " - " + error.getMessage());
    }

    /** 关闭本加载器创建的全部类加载器；可重复调用。 */
    @Override
    public void close() {
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException e) {
                System.err.println("WARN: 关闭插件加载器失败: " + e.getMessage());
            }
        }
        loaders.clear();
    }
}

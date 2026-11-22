package edu.course.licenselens.cli;

import edu.course.licenselens.spi.DependencySource;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 插件目录加载器。C 负责。
 * <p>
 * 职责：
 * <ol>
 *   <li>扫描 plugins/ 目录下所有 .jar</li>
 *   <li>为每个 jar 创建独立 URLClassLoader（隔离，避免污染宿主 classpath）</li>
 *   <li>用 {@link ServiceLoader} 发现每个 jar 中声明的 {@link DependencySource} 实现</li>
 *   <li>加载失败的 jar 跳过并告警，不中断扫描</li>
 * </ol>
 *
 * <h3>为什么独立类加载器</h3>
 * 插件 jar 可能引入和宿主不同版本的依赖（如 org.json 版本冲突）。
 * URLClassLoader 隔离让宿主和插件各自用自己的依赖版本，互不干扰。
 */
public class PluginLoader {

    /**
     * 扫描插件目录，返回所有发现的 DependencySource 实现。
     *
     * @param pluginDir 插件目录路径（如 {@code "./plugins"}）
     * @return 发现的 DependencySource 列表；目录不存在或无插件时返回空 List
     */
    public List<DependencySource> loadAll(File pluginDir) {
        List<DependencySource> sources = new ArrayList<>();
        if (pluginDir == null || !pluginDir.isDirectory()) {
            return sources;
        }

        File[] jars = pluginDir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jars == null) return sources;

        for (File jar : jars) {
            try {
                URL url = jar.toURI().toURL();
                URLClassLoader loader = new URLClassLoader(new URL[]{url},
                        PluginLoader.class.getClassLoader());
                final URLClassLoader toClose = loader;
                // 插件类在 parse() 时才首次加载，不能在 loadAll() 内提前关闭 loader，
                // 否则抛出 NoClassDefFoundError。改为 JVM 退出时统一关闭。
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        toClose.close();
                    } catch (Exception e) {
                        System.err.println("WARN: 关闭插件加载器失败: " + e.getMessage());
                    }
                }, "plugin-loader-close"));
                ServiceLoader<DependencySource> sl =
                        ServiceLoader.load(DependencySource.class, loader);
                Iterator<DependencySource> it = sl.iterator();
                while (it.hasNext()) {
                    try {
                        sources.add(it.next());
                    } catch (Throwable t) {
                        System.err.println("WARN: 插件 " + jar.getName()
                                + " 加载失败（已跳过）: " + t.getMessage());
                    }
                }
            } catch (Throwable t) {
                System.err.println("WARN: 插件文件 " + jar.getName()
                        + " 无法打开（已跳过）: " + t.getMessage());
            }
        }

        return sources;
    }
}

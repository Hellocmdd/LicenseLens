package edu.course.licenselens.core.internal;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.SourceException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 内置 Maven pom.xml 解析器。C 负责实现。
 * <p>
 * 注意：它是 core 的**内置基础能力**，不是独立扩展——不需要通过 ServiceLoader 发现，
 * 直接在 ScanEngine 构造时注入。但它实现了 {@link DependencySource} 接口，
 * 所以可以和外部插件并列，统一调度。
 */
public class MavenManifestParser implements DependencySource {

    @Override
    public String id() {
        return "maven";
    }

    @Override
    public boolean supports(ManifestRef ref) {
        // TODO: C 实现——文件名 pom.xml 且首行含 "<project"
        return "pom.xml".equals(ref.fileName().toLowerCase());
    }

    @Override
    public List<Dependency> parse(Path file) throws SourceException {
        // TODO: C 实现——用 JDK 自带 javax.xml.parsers 解析 pom.xml，
        //        提取 <dependencies> 下每个 <dependency> 的
        //        groupId / artifactId / version / scope
        throw new UnsupportedOperationException("MavenManifestParser.parse 待实现");
    }
}

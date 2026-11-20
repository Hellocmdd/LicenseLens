package edu.course.licenselens.core.internal;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.SourceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 内置 Maven pom.xml 解析器。
 * <p>
 * 用 JDK 自带 javax.xml.parsers（DOM）解析 pom.xml，
 * 提取 {@code <dependencies>} 下每个 {@code <dependency>} 的
 * groupId / artifactId / version / scope。
 */
public class MavenManifestParser implements DependencySource {

    @Override
    public String id() {
        return "maven";
    }

    @Override
    public boolean supports(ManifestRef ref) {
        return "pom.xml".equalsIgnoreCase(ref.fileName());
    }

    @Override
    public List<Dependency> parse(Path file) throws SourceException {
        if (!Files.exists(file)) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND,
                    "pom.xml 不存在: " + file);
        }

        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 防止 XXE 攻击
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(file.toFile());
        } catch (IOException e) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND,
                    "无法读取文件: " + e.getMessage());
        } catch (SAXException e) {
            // SAXException 不直接给行号，从消息里尝试提取
            int line = extractLineFromSaxMessage(e.getMessage());
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "XML 格式错误: " + e.getMessage(), line);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "XML 解析器配置错误: " + e.getMessage());
        }

        doc.getDocumentElement().normalize();

        List<Dependency> result = new ArrayList<>();

        // 找所有 <dependencies> 节点（可能出现在多处，如 dependencies 直接和 management 下的）
        NodeList depLists = doc.getElementsByTagName("dependencies");
        for (int i = 0; i < depLists.getLength(); i++) {
            Element depsContainer = (Element) depLists.item(i);
            NodeList depNodes = depsContainer.getElementsByTagName("dependency");

            for (int j = 0; j < depNodes.getLength(); j++) {
                Node depNode = depNodes.item(j);
                if (depNode.getNodeType() != Node.ELEMENT_NODE) continue;
                Element dep = (Element) depNode;

                String groupId = childText(dep, "groupId");
                String artifactId = childText(dep, "artifactId");
                String version = childText(dep, "version");
                String scope = childText(dep, "scope");
                if (scope == null || scope.isEmpty()) scope = "compile";

                if (groupId != null && artifactId != null) {
                    result.add(new Dependency(
                            "maven",
                            groupId + ":" + artifactId,
                            version != null ? version : "",
                            scope
                    ));
                }
            }
        }

        return result;
    }

    private static String childText(Element parent, String tagName) {
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl.getLength() == 0) return null;
        // 只取直接子节点，避免嵌套
        Node node = nl.item(0);
        if (node.getParentNode() != parent) return null;
        return node.getTextContent().trim();
    }

    private static int extractLineFromSaxMessage(String msg) {
        if (msg == null) return -1;
        // SAXException 消息通常形如 "... (line N, column M)"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("line\\s+(\\d+)").matcher(msg);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }
}

package edu.course.licenselens.core.internal;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.SourceException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * 内置 Maven pom.xml 解析器。
 * <p>
 * 用 JDK 自带 javax.xml.parsers（DOM）解析 pom.xml，
 * 提取 {@code <dependencies>} 下每个 {@code <dependency>} 的
 * groupId / artifactId / version / scope。
 */
public class MavenManifestParser implements DependencySource {

    /** 判定根元素时读取的头部字符数，足够覆盖 XML 声明与 {@code <project ...>} 开始标签。 */
    private static final int HEAD_CHARS = 512;

    @Override
    public String id() {
        return "maven";
    }

    /**
     * 认领规则：文件名以 {@code pom.xml} 结尾，或文件头部根元素是 {@code <project>}。
     * <p>
     * 契约要求"文件名 + 首行特征"，但 pom 常以 {@code <?xml ...?>} 声明开头，
     * 首行看不到 {@code <project>}；因此这里读头部若干字符判断根元素，
     * 让 {@code samples/edge/empty.xml} 这类自定义命名的清单也能被认领。
     */
    @Override
    public boolean supports(ManifestRef ref) {
        if (ref == null || ref.fileName() == null) {
            return false;
        }
        if (ref.fileName().toLowerCase(Locale.ROOT).endsWith("pom.xml")) {
            return true;
        }
        return looksLikeProject(ref.path());
    }

    private static boolean looksLikeProject(Path path) {
        if (path == null) {
            return false;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            char[] buffer = new char[HEAD_CHARS];
            int read = reader.read(buffer);
            if (read <= 0) {
                return false;
            }
            String head = new String(buffer, 0, read).replace("\uFEFF", "");
            return head.matches("(?s).*<project[\\s>].*");
        } catch (IOException e) {
            return false;
        }
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
        } catch (SAXParseException e) {
            // SAXParseException 自带行号，优先用它；消息里的行号只是兜底
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "XML 格式错误: " + e.getMessage(), e.getLineNumber());
        } catch (SAXException e) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "XML 格式错误: " + e.getMessage(), extractLineFromSaxMessage(e.getMessage()));
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

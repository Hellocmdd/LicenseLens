package edu.course.licenselens.core.internal;

import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.DependencySource;
import edu.course.licenselens.spi.ManifestRef;
import edu.course.licenselens.spi.SourceException;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
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

    /**
     * 解析 POM 及其 {@code <modules>} 子 POM，返回合并后的直接依赖（按四元组去重）。
     *
     * @param file POM 路径
     * @return 直接依赖列表，按首次出现顺序返回
     * @throws SourceException 文件不存在、XML 语法错误或模块 POM 缺失
     */
    @Override
    public List<Dependency> parse(Path file) throws SourceException {
        return parse(file, new LinkedHashSet<>());
    }

    private List<Dependency> parse(Path file, Set<Path> visited) throws SourceException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!visited.add(normalized)) {
            return List.of();
        }
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
            // 默认 ErrorHandler 会把 "[Fatal Error] ..." 直接打到 stderr，
            // 这里改成静默抛出，由调用方统一转成 SourceException 的友好提示
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    // 警告不影响解析结果，忽略
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            doc = builder.parse(file.toFile());
        } catch (FileNotFoundException e) {
            throw new SourceException(SourceException.Code.FILE_NOT_FOUND,
                    "POM file not found: " + file);
        } catch (IOException e) {
            throw new SourceException(SourceException.Code.PARSE_ERROR,
                    "Cannot read POM file: " + e.getMessage());
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

        Set<Dependency> collected = new LinkedHashSet<>();

        // 只认真正的依赖声明：跳过 <dependencyManagement> 里被"管理"的条目，
        // 它们不是实际依赖，计入会造成虚报；<profiles> 内的依赖暂不展开。
        NodeList depLists = doc.getElementsByTagName("dependencies");
        for (int i = 0; i < depLists.getLength(); i++) {
            Element depsContainer = (Element) depLists.item(i);
            if (isInsideDependencyManagement(depsContainer) || isInsideProfiles(depsContainer)) {
                continue;
            }

            for (Element dep : childElements(depsContainer, "dependency")) {
                // ${...} 属性占位符原样保留：本工具不解析 Maven 属性，
                // 原样输出才能在报告里看出"版本未确定"，匹配不到快照时按 UNKNOWN 处理。
                String groupId = childText(dep, "groupId");
                String artifactId = childText(dep, "artifactId");
                String version = childText(dep, "version");
                String scope = childText(dep, "scope");
                if (scope == null || scope.isEmpty()) {
                    scope = "compile";
                }

                if (groupId != null && artifactId != null) {
                    collected.add(new Dependency(
                            "maven",
                            groupId + ":" + artifactId,
                            version != null ? version : "",
                            scope
                    ));
                }
            }
        }

        // 递归展开 <modules>：只取 project 直接子节点，不进入 <profiles>。
        // 同一 POM 只访问一次，模块互相引用时不会无限递归。
        for (Element modules : childElements(doc.getDocumentElement(), "modules")) {
            for (Element module : childElements(modules, "module")) {
                String moduleText = module.getTextContent().trim();
                if (moduleText.isEmpty()) {
                    continue;
                }
                if (moduleText.contains("${")) {
                    System.err.println("WARN: 跳过含占位符的模块路径: " + moduleText);
                    continue;
                }
                Path modulePom = normalized.getParent().resolve(moduleText).normalize();
                if (!moduleText.endsWith(".xml")) {
                    modulePom = modulePom.resolve("pom.xml");
                }
                if (!Files.isRegularFile(modulePom)) {
                    throw new SourceException(SourceException.Code.PARSE_ERROR,
                            "模块 POM 不存在: " + moduleText + "（相对 " + file + "）");
                }
                collected.addAll(parse(modulePom, visited));
            }
        }

        return new ArrayList<>(collected);
    }

    private static boolean isInsideDependencyManagement(Element element) {
        for (Node parent = element.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (parent.getNodeType() == Node.ELEMENT_NODE
                    && "dependencyManagement".equals(parent.getNodeName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideProfiles(Element element) {
        for (Node parent = element.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (parent.getNodeType() == Node.ELEMENT_NODE
                    && "profiles".equals(parent.getNodeName())) {
                return true;
            }
        }
        return false;
    }

    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> found = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                found.add((Element) child);
            }
        }
        return found;
    }

    private static String childText(Element parent, String tagName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE || !tagName.equals(child.getNodeName())) {
                continue;
            }
            String text = child.getTextContent().trim();
            return text.isEmpty() ? null : text;
        }
        return null;
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

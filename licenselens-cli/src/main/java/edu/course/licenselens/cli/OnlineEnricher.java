package edu.course.licenselens.cli;

import edu.course.licenselens.spi.Dependency;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可选的联网补充信息。
 * <p>
 * 只做终端展示：为每条依赖查注册表里的许可证与最新版本，结果不写入 Markdown / JSON 报告，
 * 因此报告仍然只由本地快照决定、保持确定性。默认关闭，由 {@code --online} 开启。
 * <p>
 * 数据源国内优先、失败再回退官方源；任何网络失败都降级为 OFFLINE，不影响审计结论与退出码。
 */
public final class OnlineEnricher {

    /** 单次 HTTP 请求超时。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    /** 建立连接超时。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** 并发查询数。 */
    private static final int PARALLELISM = 4;

    private static final String NPM_MIRROR = "https://registry.npmmirror.com";
    private static final String NPM_OFFICIAL = "https://registry.npmjs.org";
    private static final String PYPI_MIRROR = "https://pypi.tuna.tsinghua.edu.cn/pypi";
    private static final String PYPI_OFFICIAL = "https://pypi.org/pypi";
    private static final String PYPI_ALIYUN = "https://mirrors.aliyun.com/pypi/web/json";
    private static final String MAVEN_MIRROR = "https://maven.aliyun.com/repository/public";
    private static final String MAVEN_OFFICIAL = "https://repo1.maven.org/maven2";
    private static final String GO_MIRROR = "https://goproxy.cn";
    private static final String GO_OFFICIAL = "https://proxy.golang.org";
    private static final String GITHUB_API = "https://api.github.com/repos";

    private static final Pattern RELEASE_TAG = Pattern.compile("<release>([^<]+)</release>");
    private static final Pattern LATEST_TAG = Pattern.compile("<latest>([^<]+)</latest>");
    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern LICENSE_BLOCK = Pattern.compile("<licenses>(.*?)</licenses>", Pattern.DOTALL);
    private static final Pattern LICENSE_NAME = Pattern.compile("<name>([^<]+)</name>");

    private final Fetcher fetcher;

    /** 默认实现：真实 HTTP，请求超时 3 秒。 */
    public OnlineEnricher() {
        this(new HttpFetcher());
    }

    OnlineEnricher(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * 并行查询各依赖的注册表信息，返回顺序与入参一致。
     *
     * @param dependencies 清单解析出的依赖
     * @return 与依赖一一对应的补充信息
     */
    public List<Row> enrich(List<Dependency> dependencies) {
        if (dependencies.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            List<Future<Row>> futures = new ArrayList<>(dependencies.size());
            for (Dependency dep : dependencies) {
                futures.add(pool.submit(() -> lookup(dep)));
            }
            List<Row> rows = new ArrayList<>(dependencies.size());
            for (int i = 0; i < futures.size(); i++) {
                Dependency dep = dependencies.get(i);
                try {
                    rows.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    rows.add(Row.offline(dep.ecosystem() + ":" + dep.name()));
                } catch (java.util.concurrent.ExecutionException e) {
                    rows.add(new Row(dep.ecosystem() + ":" + dep.name(), "", "", "", "UNREADABLE"));
                }
            }
            return rows;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 把补充信息打印到终端；没有任何可查询条目时也说明原因。
     *
     * @param out   终端输出
     * @param rows  补充信息
     */
    public void print(PrintStream out, List<Row> rows) {
        out.println();
        out.println("在线补充信息（国内源优先，仅终端展示，不写入报告）:");
        for (Row row : rows) {
            if ("SKIPPED".equals(row.status())) {
                out.printf("  %s -> 暂不支持该生态的联网查询%n", row.dependency());
            } else if ("OFFLINE".equals(row.status())) {
                out.printf("  %s -> 注册表不可达（离线快照结果不受影响）%n", row.dependency());
            } else if ("NOT_FOUND".equals(row.status())) {
                out.printf("  %s -> 注册表未找到该依赖%n", row.dependency());
            } else if ("UNREADABLE".equals(row.status())) {
                out.printf("  %s -> 注册表响应无法解析%n", row.dependency());
            } else {
                String license = row.license();
                if (license.isBlank()) {
                    license = row.dependency().startsWith("maven:")
                            ? "pom 未声明（可能继承自 parent）" : "未声明";
                }
                out.printf("  %s -> 许可证 %s | 最新版本 %s | 来源 %s%n",
                        row.dependency(), license, row.latestVersion(), row.source());
            }
        }
    }

    private Row lookup(Dependency dep) {
        String ecosystem = dep.ecosystem().toLowerCase(Locale.ROOT);
        try {
            return switch (ecosystem) {
                case "npm" -> lookupNpm(dep);
                case "python" -> lookupPypi(dep);
                case "maven" -> lookupMaven(dep);
                case "go" -> lookupGo(dep);
                default -> new Row(dep.ecosystem() + ":" + dep.name(), "", "", "", "SKIPPED");
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Row.offline(dep.ecosystem() + ":" + dep.name());
        } catch (IOException e) {
            // 网络不可达按离线降级；响应无法解析（JSON/XML 异常）才算 UNREADABLE。
            return Row.offline(dep.ecosystem() + ":" + dep.name());
        } catch (RuntimeException e) {
            return new Row(dep.ecosystem() + ":" + dep.name(), "", "", "", "UNREADABLE");
        }
    }

    private Row lookupNpm(Dependency dep) throws IOException, InterruptedException {
        String path = encode(dep.name()) + "/latest";
        String body = getFirst(NPM_MIRROR + "/" + path, NPM_OFFICIAL + "/" + path);
        JSONObject json = new JSONObject(body);
        String license = licenseOf(json.opt("license"));
        String version = json.optString("version", "");
        return new Row("npm:" + dep.name(), normalizeLicense(license), version, "npmmirror", "OK");
    }

    private Row lookupPypi(Dependency dep) throws IOException, InterruptedException {
        String path = encode(dep.name()) + "/json";
        String body = getFirst(PYPI_MIRROR + "/" + path, PYPI_ALIYUN + "/" + path,
                PYPI_OFFICIAL + "/" + path);
        JSONObject info = new JSONObject(body).getJSONObject("info");
        // PyPI 的 license 字段可能是整篇全文，classifiers 更结构化，优先使用。
        String license = info.has("classifiers")
                ? licenseFromClassifiers(info.getJSONArray("classifiers")) : "";
        if (license.isBlank()) {
            license = info.optString("license", "");
        }
        return new Row("python:" + dep.name(), normalizeLicense(license),
                info.optString("version", ""), "PyPI", "OK");
    }

    private Row lookupMaven(Dependency dep) throws IOException, InterruptedException {
        String coordinate = dep.name();
        int colon = coordinate.indexOf(':');
        if (colon <= 0 || colon == coordinate.length() - 1) {
            return new Row("maven:" + coordinate, "", "", "", "NOT_FOUND");
        }
        String groupId = coordinate.substring(0, colon);
        String artifactId = coordinate.substring(colon + 1);
        String path = groupId.replace('.', '/') + "/" + artifactId;

        FetchResult metadata = fetchWithFallback(MAVEN_MIRROR, MAVEN_OFFICIAL,
                path + "/maven-metadata.xml");
        String version = firstGroup(metadata.body(), RELEASE_TAG);
        if (version == null) {
            version = firstGroup(metadata.body(), LATEST_TAG);
        }
        if (version == null) {
            version = lastGroup(metadata.body(), VERSION_TAG);
        }
        if (version == null) {
            return new Row("maven:" + coordinate, "", "", "Maven", "NOT_FOUND");
        }

        String pomPath = path + "/" + version + "/" + artifactId + "-" + version + ".pom";
        String pom = fetcher.fetch(metadata.base() + "/" + pomPath);
        String license = firstLicenseName(pom);
        return new Row("maven:" + coordinate, normalizeLicense(license), version,
                metadata.base().equals(MAVEN_MIRROR) ? "Aliyun Maven" : "Maven Central", "OK");
    }

    private FetchResult fetchWithFallback(String primary, String fallback, String path)
            throws IOException, InterruptedException {
        try {
            return new FetchResult(fetcher.fetch(primary + "/" + path), primary);
        } catch (IOException e) {
            return new FetchResult(fetcher.fetch(fallback + "/" + path), fallback);
        }
    }

    private record FetchResult(String body, String base) {
    }

    private Row lookupGo(Dependency dep) throws IOException, InterruptedException {
        String module = dep.name();
        String list = fetchGoList(module);
        String latest = lastNonBlankLine(list);
        String license = "";
        String source = "goproxy.cn";
        if (module.startsWith("github.com/")) {
            String[] parts = module.split("/");
            if (parts.length >= 3) {
                String repo = parts[1] + "/" + parts[2].replace(".git", "");
                try {
                    JSONObject json = new JSONObject(fetcher.fetch(GITHUB_API + "/" + repo));
                    if (json.has("license") && json.get("license") instanceof JSONObject licenseObj) {
                        license = licenseObj.optString("spdx_id", "");
                        source = "GitHub";
                    }
                } catch (IOException e) {
                    // 拿不到 GitHub 许可证时保留 goproxy 的版本信息即可
                }
            }
        }
        return new Row("go:" + module, normalizeLicense(license), latest, source, "OK");
    }

    private String fetchGoList(String module) throws IOException, InterruptedException {
        try {
            return fetcher.fetch(GO_MIRROR + "/" + module + "/@v/list");
        } catch (IOException e) {
            return fetcher.fetch(GO_OFFICIAL + "/" + module + "/@v/list");
        }
    }

    private String getFirst(String... urls) throws IOException, InterruptedException {
        IOException last = null;
        for (String url : urls) {
            try {
                return fetcher.fetch(url);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("没有可用的数据源") : last;
    }

    private static String licenseOf(Object license) {
        if (license instanceof String text) {
            return text;
        }
        if (license instanceof JSONObject object) {
            return object.optString("type", "");
        }
        return "";
    }

    private static String licenseFromClassifiers(JSONArray classifiers) {
        for (int i = 0; i < classifiers.length(); i++) {
            String classifier = classifiers.optString(i, "");
            if (classifier.startsWith("License ::")) {
                String[] parts = classifier.split("::");
                return parts[parts.length - 1].trim();
            }
        }
        return "";
    }

    private static String firstLicenseName(String pom) {
        Matcher block = LICENSE_BLOCK.matcher(pom);
        if (block.find()) {
            Matcher name = LICENSE_NAME.matcher(block.group(1));
            if (name.find()) {
                return name.group(1);
            }
        }
        return "";
    }

    private static String firstGroup(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String lastGroup(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1).trim();
        }
        return last;
    }

    private static String lastNonBlankLine(String text) {
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    /**
     * 把注册表返回的许可证文本归一为 SPDX 标识；无法识别时原样返回。
     */
    static String normalizeLicense(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        if (text.length() > 80 || text.indexOf('\n') >= 0) {
            // 注册表常把整篇许可证全文塞进 license 字段，此时按关键词猜测会误判，直接返回空由调用方提示"未声明"。
            return "";
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if ("BSD LICENSE".equals(upper)) {
            return "BSD-3-Clause";
        }
        if ("APACHE SOFTWARE LICENSE".equals(upper)) {
            return "Apache-2.0";
        }
        if (upper.contains("LGPL") || upper.contains("LESSER GENERAL PUBLIC")) {
            if (upper.contains("2.1")) {
                return "LGPL-2.1-only";
            }
            return upper.contains("2") ? "LGPL-2.0-only" : "LGPL-3.0-only";
        }
        if (upper.contains("AGPL")) {
            return "AGPL-3.0-only";
        }
        if (upper.contains("GPL") && upper.contains("3")) {
            return "GPL-3.0-only";
        }
        if (upper.contains("GPL") && upper.contains("2")) {
            return "GPL-2.0-only";
        }
        if (upper.contains("APACHE") && upper.contains("2")) {
            return "Apache-2.0";
        }
        if (upper.contains("BSD") && upper.contains("3")) {
            return "BSD-3-Clause";
        }
        if (upper.contains("BSD") && upper.contains("2")) {
            return "BSD-2-Clause";
        }
        if (upper.contains("MIT") && !upper.contains("MIT-0")) {
            return "MIT";
        }
        if (upper.contains("ISC")) {
            return "ISC";
        }
        if (upper.contains("MPL") && upper.contains("2")) {
            return "MPL-2.0";
        }
        if (upper.contains("EPL") && upper.contains("2")) {
            return "EPL-2.0";
        }
        if (upper.contains("UNLICENSE")) {
            return "Unlicense";
        }
        if (upper.contains("CC0")) {
            return "CC0-1.0";
        }
        if (upper.equals("PYTHON SOFTWARE FOUNDATION LICENSE")) {
            return "Python-2.0";
        }
        return text;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 联网抓取的最小抽象，便于测试注入固定响应而不访问网络。 */
    interface Fetcher {
        String fetch(String url) throws IOException, InterruptedException;
    }

    /** 真实 HTTP 实现。 */
    static final class HttpFetcher implements Fetcher {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public String fetch(String url) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "LicenseLens/online")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + ": " + url);
            }
            return response.body();
        }
    }

    /**
     * 单条依赖的补充信息。
     *
     * @param dependency    依赖标识（生态:名称）
     * @param license       归一后的许可证，未查到为空串
     * @param latestVersion 注册表最新版本
     * @param source        数据来源
     * @param status        OK / NOT_FOUND / OFFLINE / SKIPPED
     */
    public record Row(String dependency, String license, String latestVersion,
                      String source, String status) {

        static Row offline(String dependency) {
            return new Row(dependency, "", "", "", "OFFLINE");
        }
    }
}

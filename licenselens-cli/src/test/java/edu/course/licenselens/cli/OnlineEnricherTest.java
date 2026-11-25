package edu.course.licenselens.cli;

import edu.course.licenselens.spi.Dependency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联网补充信息的单元测试：全部使用桩数据，不访问网络。
 */
class OnlineEnricherTest {

    private final Map<String, String> bodies = new LinkedHashMap<>();
    private final List<String> requested = new ArrayList<>();
    private final OnlineEnricher enricher = new OnlineEnricher(url -> {
        requested.add(url);
        String body = bodies.get(url);
        if (body == null) {
            throw new IOException("桩数据未配置: " + url);
        }
        return body;
    });

    @Test
    @DisplayName("npm：优先国内镜像，许可证取 license 字段")
    void shouldResolveNpmFromMirror() {
        bodies.put("https://registry.npmmirror.com/express/latest",
                "{\"version\":\"5.3.0\",\"license\":\"MIT\"}");

        OnlineEnricher.Row row = enricher.enrich(List.of(dep("npm", "express"))).get(0);

        assertEquals("npm:express", row.dependency());
        assertEquals("MIT", row.license());
        assertEquals("5.3.0", row.latestVersion());
        assertEquals("npmmirror", row.source());
        assertEquals("OK", row.status());
    }

    @Test
    @DisplayName("npm：license 为对象时取 type")
    void shouldResolveNpmLicenseObject() {
        bodies.put("https://registry.npmmirror.com/react/latest",
                "{\"version\":\"19.2.0\",\"license\":{\"type\":\"Apache-2.0\"}}");

        OnlineEnricher.Row row = enricher.enrich(List.of(dep("npm", "react"))).get(0);

        assertEquals("Apache-2.0", row.license());
    }

    @Test
    @DisplayName("PyPI：license 为空时回退到 classifiers")
    void shouldFallbackToPypiClassifiers() {
        bodies.put("https://pypi.tuna.tsinghua.edu.cn/pypi/requests/json",
                "{\"info\":{\"version\":\"2.32.5\",\"license\":\"\","
                        + "\"classifiers\":[\"License :: OSI Approved :: MIT License\"]}}");

        OnlineEnricher.Row row = enricher.enrich(List.of(dep("python", "requests"))).get(0);

        assertEquals("MIT", row.license());
        assertEquals("2.32.5", row.latestVersion());
        assertEquals("PyPI", row.source());
    }

    @Test
    @DisplayName("Maven：metadata 取版本，pom 取 licenses 名称")
    void shouldResolveMavenFromMirror() {
        bodies.put("https://maven.aliyun.com/repository/public/com/google/guava/guava/maven-metadata.xml",
                "<metadata><version>32.0.0</version><release>33.4.8-jre</release></metadata>");
        bodies.put("https://maven.aliyun.com/repository/public/com/google/guava/guava/33.4.8-jre/"
                        + "guava-33.4.8-jre.pom",
                "<project><licenses><license><name>Apache License, Version 2.0</name></license></licenses>"
                        + "</project>");

        OnlineEnricher.Row row = enricher.enrich(List.of(dep("maven", "com.google.guava:guava"))).get(0);

        assertEquals("33.4.8-jre", row.latestVersion());
        assertEquals("Apache-2.0", row.license());
        assertEquals("Aliyun Maven", row.source());
    }

    @Test
    @DisplayName("Maven：国内镜像不可达时回退官方源")
    void shouldFallbackToMavenCentral() {
        bodies.put("https://repo1.maven.org/maven2/org/json/json/maven-metadata.xml",
                "<metadata><release>20250107</release></metadata>");
        bodies.put("https://repo1.maven.org/maven2/org/json/json/20250107/json-20250107.pom",
                "<project><licenses><license><name>Public Domain</name></license></licenses></project>");

        OnlineEnricher.Row row = enricher.enrich(List.of(dep("maven", "org.json:json"))).get(0);

        assertEquals("20250107", row.latestVersion());
        assertEquals("Maven Central", row.source());
        assertEquals("Public Domain", row.license());
    }

    @Test
    @DisplayName("Maven：坐标不完整时标记 NOT_FOUND")
    void shouldMarkInvalidMavenCoordinate() {
        OnlineEnricher.Row row = enricher.enrich(List.of(dep("maven", "guava"))).get(0);

        assertEquals("NOT_FOUND", row.status());
    }

    @Test
    @DisplayName("Go：goproxy 取最新版本，GitHub 取许可证")
    void shouldResolveGoFromProxyAndGitHub() {
        bodies.put("https://goproxy.cn/github.com/google/uuid/@v/list", "v1.0.0\nv1.6.0\n");
        bodies.put("https://api.github.com/repos/google/uuid",
                "{\"license\":{\"spdx_id\":\"BSD-3-Clause\"}}");

        OnlineEnricher.Row row = enricher.enrich(List.of(dep("go", "github.com/google/uuid"))).get(0);

        assertEquals("v1.6.0", row.latestVersion());
        assertEquals("BSD-3-Clause", row.license());
        assertEquals("GitHub", row.source());
    }

    @Test
    @DisplayName("PyPI：classifiers 优先于 license 全文（避免从全文里猜错许可证）")
    void shouldPreferClassifiersOverLicenseText() {
        bodies.put("https://pypi.tuna.tsinghua.edu.cn/pypi/numpy/json",
                "{\"info\":{\"version\":\"2.0.1\","
                        + "\"license\":\"Copyright ... bundled libquadmath is LGPL-2.1-or-later ...\","
                        + "\"classifiers\":[\"License :: OSI Approved :: BSD License\"]}}");

        OnlineEnricher.Row row = enricher.enrich(List.of(dep("python", "numpy"))).get(0);

        assertEquals("BSD-3-Clause", row.license());
    }

    @Test
    @DisplayName("未知生态：跳过并说明，不发起请求")
    void shouldSkipUnsupportedEcosystem() {
        OnlineEnricher.Row row = enricher.enrich(List.of(dep("conda", "numpy"))).get(0);

        assertEquals("SKIPPED", row.status());
        assertTrue(requested.isEmpty(), "不支持的生态不应发起网络请求");
    }

    @Test
    @DisplayName("网络失败：降级为 OFFLINE，异常不外抛")
    void shouldDegradeToOffline() {
        OnlineEnricher.Row row = enricher.enrich(List.of(dep("npm", "express"))).get(0);

        assertEquals("OFFLINE", row.status());
        assertEquals("", row.license());
    }

    @Test
    @DisplayName("返回顺序与依赖顺序一致")
    void shouldPreserveInputOrder() {
        bodies.put("https://registry.npmmirror.com/express/latest",
                "{\"version\":\"5.3.0\",\"license\":\"MIT\"}");
        bodies.put("https://pypi.tuna.tsinghua.edu.cn/pypi/requests/json",
                "{\"info\":{\"version\":\"2.32.5\",\"license\":\"Apache-2.0\"}}");

        List<OnlineEnricher.Row> rows = enricher.enrich(List.of(
                dep("python", "requests"), dep("npm", "express"), dep("conda", "numpy")));

        assertEquals("python:requests", rows.get(0).dependency());
        assertEquals("npm:express", rows.get(1).dependency());
        assertEquals("conda:numpy", rows.get(2).dependency());
    }

    @Test
    @DisplayName("许可证文本归一为 SPDX 标识")
    void shouldNormalizeLicenseNames() {
        assertEquals("MIT", OnlineEnricher.normalizeLicense("MIT License"));
        assertEquals("Apache-2.0", OnlineEnricher.normalizeLicense("Apache License 2.0"));
        assertEquals("BSD-3-Clause",
                OnlineEnricher.normalizeLicense("BSD 3-Clause \"New\" or \"Revised\" License"));
        assertEquals("LGPL-2.1-only",
                OnlineEnricher.normalizeLicense("GNU Lesser General Public License v2.1"));
        assertEquals("LGPL-2.0-only",
                OnlineEnricher.normalizeLicense("GNU Lesser General Public License v2 (LGPLv2)"));
        assertEquals("BSD-3-Clause", OnlineEnricher.normalizeLicense("BSD License"));
        assertEquals("Apache-2.0", OnlineEnricher.normalizeLicense("Apache Software License"));
        assertEquals("", OnlineEnricher.normalizeLicense(
                "这是一整篇许可证全文，长度超过八十个字符就不应该再按关键词去猜它的 SPDX 标识，否则很容易误判成某种 copyleft 许可证。"
                        + "这里再补一句让总长度稳定超过阈值，避免测试因为字符数变化而失效。"));
        assertEquals("GPL-3.0-only", OnlineEnricher.normalizeLicense("GPLv3"));
        assertEquals("自定义许可证", OnlineEnricher.normalizeLicense("自定义许可证"));
        assertEquals("", OnlineEnricher.normalizeLicense(null));
    }

    private static Dependency dep(String ecosystem, String name) {
        return new Dependency(ecosystem, name, "1.0.0", "runtime");
    }
}

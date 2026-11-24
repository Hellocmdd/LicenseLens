package edu.course.licenselens.core;

import edu.course.licenselens.spi.Decision;
import edu.course.licenselens.spi.Dependency;
import edu.course.licenselens.spi.LicenseInfo;
import edu.course.licenselens.spi.RiskLevel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonReportRendererTest {

    @Test
    @DisplayName("JSON 报告：字段完整、依赖按风险降序、摘要与判定一致")
    void shouldRenderSortedJsonWithSummary() {
        ReportRenderer.ScanRow permissive = new ReportRenderer.ScanRow(
                new Dependency("maven", "g:permissive", "1.0", "compile"),
                new LicenseInfo("MIT", "MIT License", RiskLevel.PERMISSIVE),
                Decision.allow("允许"));
        ReportRenderer.ScanRow strong = new ReportRenderer.ScanRow(
                new Dependency("maven", "g:strong", "2.0", "compile"),
                new LicenseInfo("GPL-2.0", "GPL v2", RiskLevel.STRONG_COPYLEFT),
                Decision.block("强 copyleft"));

        String json = new JsonReportRenderer().render(
                List.of(permissive, strong), new CampusPolicy(), "pom.xml");

        JSONObject root = new JSONObject(json);
        assertEquals("campus", root.getString("policy"));
        assertEquals("pom.xml", root.getString("manifest"));
        assertEquals("BLOCKED", root.getString("result"));

        JSONObject summary = root.getJSONObject("summary");
        assertEquals(2, summary.getInt("total"));
        assertEquals(1, summary.getInt("blocked"));
        assertEquals(0, summary.getInt("unknown"));

        JSONArray deps = root.getJSONArray("dependencies");
        assertEquals(2, deps.length());
        assertEquals("g:strong", deps.getJSONObject(0).getString("name"));
        assertEquals("STRONG_COPYLEFT",
                deps.getJSONObject(0).getJSONObject("license").getString("risk"));
        assertEquals("g:permissive", deps.getJSONObject(1).getString("name"));
    }
}

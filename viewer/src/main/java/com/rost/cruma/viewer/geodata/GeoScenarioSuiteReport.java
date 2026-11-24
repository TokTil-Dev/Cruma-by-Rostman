package com.rost.cruma.viewer.geodata;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.geodata.GeoScenarioSuiteRunner.Report;
import java.io.IOException;
import java.nio.file.Path;

public final class GeoScenarioSuiteReport {
    private GeoScenarioSuiteReport() {}

    public static String encode(Report report) {
        var root = new JSONObject();
        root.put("kind", "geodata-scenario-suite-report");
        root.put("version", 1);
        root.put("passed", report.hasPassed());
        root.put("counts", JSON.toJSON(report.counts()));
        var cases = new JSONArray();
        for (var item : report.cases()) {
            cases.add(JSON.parseObject(GeoScenarioReport.encode(item.scenario(), item.result())));
        }
        root.put("cases", cases);
        return JSON.toJSONString(root);
    }

    public static void write(Path path, Report report, boolean replaceExisting) throws IOException {
        GeoScenarioStore.writeText(path, encode(report), replaceExisting);
    }
}

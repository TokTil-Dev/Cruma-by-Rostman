package com.rost.cruma.viewer.geodata;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.geodata.GeoScenarioRunner.Result;
import java.io.IOException;
import java.nio.file.Path;

public final class GeoScenarioReport {
    private GeoScenarioReport() {}

    public static String encode(GeoScenario scenario, Result result) {
        var report = new JSONObject();
        report.put("version", 1);
        report.put("scenario", JSON.parseObject(GeoScenarioStore.encode(scenario)));
        report.put("result", JSON.toJSON(result));
        return JSON.toJSONString(report);
    }

    public static void write(Path path, GeoScenario scenario, Result result, boolean replaceExisting)
            throws IOException {
        GeoScenarioStore.writeText(path, encode(scenario, result), replaceExisting);
    }
}

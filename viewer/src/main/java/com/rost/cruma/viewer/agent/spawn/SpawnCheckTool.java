package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.after;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.area;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.document;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.limit;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.point;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.spawnsReady;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.spawn.SpawnAreaCheck;
import com.rost.cruma.core.spawn.SpawnAreaCheck.Report;
import com.rost.cruma.core.spawn.SpawnChecks.Severity;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.SpawnTools.Desk;

final class SpawnCheckTool {
    private static final double GEODATA_PADDING = 4096;

    private SpawnCheckTool() {}

    static Step spawnCheck(ToolArgs args, Desk desk) throws ToolProblem {
        var text = args.getRequired("target");
        var limit = limit(args);
        return after(() -> spawnsReady(desk), () -> {
            var area = area(args, desk.places().locate(text));
            var spawns = desk.spawns();
            var hasGeodata = spawns.hasGeodata();
            var state = document(desk).getState();
            var npcs = spawns.getSource().getNpcs();
            return Step.onWorker(() -> {
                var data = !hasGeodata
                        ? null
                        : spawns.readArea(
                                area.minX() - GEODATA_PADDING,
                                area.minY() - GEODATA_PADDING,
                                area.maxX() + GEODATA_PADDING,
                                area.maxY() + GEODATA_PADDING);
                var report = SpawnAreaCheck.of(
                        state, area, npcs, data, () -> Thread.currentThread().isInterrupted());
                return ToolResult.data(describeCheck(report, hasGeodata, limit));
            });
        });
    }

    private static JSONObject describeCheck(Report report, boolean hasGeodata, int limit) {
        var result = new JSONObject();
        result.put("makers", report.makers());
        result.put("zones", report.zones());
        if (report.geodataChecked()) {
            result.put("geodata", "checked");
        } else if (hasGeodata) {
            result.put(
                    "geodata", "not checked: the area needs more geodata than one read holds. Check a smaller area.");
        } else {
            result.put(
                    "geodata",
                    "not checked: no geodata folder is set. Floors, walls and spawn points of this area are unknown,"
                            + " so no findings of those kinds does not mean they are fine.");
        }
        result.put("errors", report.errors());
        result.put("warnings", report.warnings());
        result.put("by_kind", SpawnAreaCheck.titled(report.health()));
        var list = new JSONArray();
        for (var item : report.items().subList(0, Math.min(limit, report.items().size()))) {
            var finding = new JSONObject();
            finding.put("text", item.where() + ": " + item.detail());
            finding.put("target", point(item.x(), item.y(), item.z()));
            finding.put("severity", item.severity() == Severity.ERROR ? "error" : "warning");
            list.add(finding);
        }
        result.put("more", report.items().size() - list.size());
        result.put("findings", list);
        return result;
    }
}

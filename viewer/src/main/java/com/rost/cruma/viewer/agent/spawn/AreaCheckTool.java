package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.AreaSteps.document;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.findArea;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.isAreaDataReady;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.kindWord;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.middleOf;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.region;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.after;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.limit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.area.AreaChecks;
import com.rost.cruma.core.area.AreaChecks.Finding;
import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.AreaSteps.Region;
import com.rost.cruma.viewer.agent.spawn.AreaTools.Desk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class AreaCheckTool {
    private AreaCheckTool() {}

    static Step areaCheck(ToolArgs args, Desk desk) throws ToolProblem {
        var limit = limit(args);
        var one = args.getText("area");
        return after(() -> isAreaDataReady(desk), () -> {
            var document = document(desk);
            var state = document.getState();
            var types = document.getSavedTypes();
            var unsaved = document.isModified();
            var area = one == null ? null : findArea(state, one).index();
            var region = region(args, desk);
            return Step.onWorker(() -> {
                var result = describeCheck(state, types, area, region, limit);
                result.put("unsaved", unsaved);
                return ToolResult.data(result);
            });
        });
    }

    private static JSONObject describeCheck(
            State state, Set<String> types, @Nullable Integer one, @Nullable Region region, int limit) {
        var checked = 0;
        for (var area : state.areas()) {
            if (area != null && inScope(area.index(), state, one, region)) {
                checked++;
            }
        }
        var found = new ArrayList<Finding>();
        for (var finding : AreaChecks.findAll(state, types)) {
            if (inScope(finding.area(), state, one, region)) {
                found.add(finding);
            }
        }
        found.sort(Comparator.comparing((Finding finding) -> !finding.isError()));
        var byKind = new LinkedHashMap<String, Integer>();
        for (var finding : found) {
            byKind.merge(finding.title(), 1, Integer::sum);
        }
        var shown = found.subList(0, Math.min(limit, found.size()));
        var result = new JSONObject();
        result.put("areas", checked);
        if (one == null && region == null) {
            result.put("scope", "the whole file, also tiles the viewer does not hold");
        }
        result.put("errors", found.stream().filter(Finding::isError).count());
        result.put(
                "warnings", found.stream().filter(finding -> !finding.isError()).count());
        result.put("by_kind", byKind);
        result.put("findings", items(state, shown));
        result.put("checks", checks(shown));
        result.put("more", found.size() - shown.size());
        return result;
    }

    private static boolean inScope(int index, State state, @Nullable Integer one, @Nullable Region region) {
        if (one != null) {
            return index == one;
        }
        var area = state.area(index);
        return area != null && (region == null || region.reaches(area));
    }

    private static JSONArray items(State state, List<Finding> findings) {
        var list = new JSONArray();
        for (var finding : findings) {
            var item = new JSONObject();
            item.put("text", finding.name() + ": " + finding.title() + ". " + finding.detail());
            var area = state.area(finding.area());
            item.put("target", area == null ? "camera" : middleOf(area));
            item.put("severity", finding.isError() ? "error" : "warning");
            list.add(item);
        }
        return list;
    }

    private static JSONArray checks(List<Finding> findings) {
        var list = new JSONArray();
        for (var finding : findings) {
            var item = new JSONObject();
            item.put("area", SpawnEditor.number(finding.area()));
            item.put("name", finding.name());
            item.put("kind", kindWord(finding.kind()));
            item.put("title", finding.title());
            item.put("detail", finding.detail());
            if (finding.hasFix()) {
                item.put("fix", finding.kind().getFixLabel());
            }
            list.add(item);
        }
        return list;
    }
}

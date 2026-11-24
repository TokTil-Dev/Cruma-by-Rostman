package com.rost.cruma.viewer.agent.geo;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.geodata.GeoBookmarkStore;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCase;
import com.rost.cruma.viewer.geodata.GeoInspection.Bookmark;
import com.rost.cruma.viewer.geodata.GeoScenario;
import com.rost.cruma.viewer.geodata.GeoScenario.Kind;
import com.rost.cruma.viewer.geodata.GeoScenarioFingerprints;
import com.rost.cruma.viewer.geodata.GeoScenarioRunner.Context;
import com.rost.cruma.viewer.geodata.GeoScenarioSource;
import com.rost.cruma.viewer.geodata.GeoScenarioStore;
import com.rost.cruma.viewer.geodata.GeoScenarioSuiteRunner;
import com.rost.cruma.viewer.geodata.GeoScenarioSuiteRunner.Source;
import com.rost.cruma.viewer.geodata.GeodataDiagnosticJobs;
import com.rost.cruma.viewer.geodata.GeodataDiagnosticJobs.Result;
import com.rost.cruma.viewer.render.GeodataState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class GeoCases {
    static final String BOOKMARKS = "geodata-bookmarks.txt";

    static final int MOST_BOOKMARKS = 128;

    private static final int MOST_SCENARIOS = 256;
    private static final long POLL_MILLIS = 50;

    private GeoCases() {}

    static JSONObject run(Path file, Path folder, @Nullable GeoScenarioSource edited, BooleanSupplier stopped)
            throws ToolProblem {
        var scenarios = new ArrayList<GeoScenario>();
        try {
            if (Files.isDirectory(file)) {
                try (var files = Files.list(file)) {
                    for (var each : files.filter(path -> path.getFileName()
                                    .toString()
                                    .toLowerCase(Locale.ROOT)
                                    .endsWith(".json"))
                            .sorted()
                            .toList()) {
                        if (scenarios.size() >= MOST_SCENARIOS) {
                            break;
                        }
                        try {
                            scenarios.add(GeoScenarioStore.read(each));
                        } catch (IOException | RuntimeException other) {
                        }
                    }
                }
            } else {
                scenarios.add(GeoScenarioStore.read(file));
            }
        } catch (IOException | RuntimeException unreadable) {
            throw new ToolProblem("The scenario cannot be read: " + unreadable.getMessage());
        }
        if (scenarios.isEmpty()) {
            throw new ToolProblem("The folder holds no query scenario. The panel saves them under Advanced of Check.");
        }
        var report = GeoScenarioSuiteRunner.run(
                scenarios,
                (scenario, cancelled) -> {
                    var source = edited != null ? edited : GeoScenarioSource.loadOriginal(folder, scenario, cancelled);
                    var graph = scenario.profile().kind() == Kind.PATH
                                    && Files.isRegularFile(folder.resolve("pathnode.idx"))
                            ? PathNodeGraph.open(folder, cancelled)
                            : null;
                    var context = new Context(
                            source.data(),
                            graph,
                            GeoScenarioFingerprints.capture(
                                    source.fingerprints(),
                                    graph == null ? "" : graph.computeContentFingerprint(cancelled),
                                    source.editedPreview()),
                            source.editedPreview());
                    return new Source(context, graph);
                },
                1,
                true,
                stopped);
        var answer = new JSONObject();
        answer.put("action", "run");
        answer.put("scenarios", report.cases().size());
        answer.put("passed", report.hasPassed());
        var counts = new JSONObject();
        report.counts().forEach((verdict, count) -> counts.put(word(verdict.name()), count));
        answer.put("verdicts", counts);
        var cases = new JSONArray();
        for (var each : report.cases()) {
            var row = new JSONObject();
            row.put(
                    "name",
                    each.scenario().name().isBlank()
                            ? each.scenario().id()
                            : each.scenario().name());
            row.put("kind", word(each.scenario().profile().kind().name()));
            row.put("verdict", word(each.result().verdict().name()));
            row.put("detail", each.result().detail());
            if (each.result().dataChanged()) {
                row.put("data_changed", true);
            }
            if (!each.result().runs().isEmpty()) {
                var run = each.result().runs().getFirst();
                row.put("status", run.status());
                if (run.finalPosition() != null) {
                    row.put("ends", GeoWords.point(run.finalPosition()));
                }
            }
            row.put("a", GeoWords.point(each.scenario().a().point()));
            if (each.scenario().b() != null) {
                row.put("b", GeoWords.point(each.scenario().b().point()));
            }
            cases.add(row);
        }
        answer.put("cases", cases);
        answer.put(
                "note",
                edited != null
                        ? "The scenarios ran on the edits. A path keeps the stored graph, which does not hold them."
                        : "The scenarios ran on the region files of the folder.");
        return answer;
    }

    static JSONObject saveCase(GeoDataAccess data, Bounds area, Path file, boolean replace, BooleanSupplier stopped)
            throws ToolProblem, InterruptedException {
        try (var jobs = new GeodataDiagnosticJobs()) {
            jobs.scan(data, area, GeoScans.MOST_CELLS, GeoDiagnosticCase.MAX_REPORTS, 2_000_000, GeoScans.DROP);
            var scanned = await(jobs, data, stopped);
            if (!scanned.error().isBlank()) {
                throw new ToolProblem("The scan failed: " + scanned.error());
            }
            jobs.saveCase(data, file, replace);
            var saved = await(jobs, data, stopped);
            if (!saved.error().isBlank()) {
                throw new ToolProblem("The case was not saved: "
                        + saved.error()
                        + (Files.exists(file) && !replace ? ". Give replace to replace it." : ""));
            }
            var answer = new JSONObject();
            answer.put("action", "save_case");
            answer.put("file", file.toString());
            answer.put("cells", scanned.scan().scannedCells());
            answer.put("findings", scanned.scan().findings().size());
            answer.put("note", "replay with this file says later whether the same cells give the same findings.");
            return answer;
        }
    }

    static JSONObject replay(GeoDataAccess data, Path file, @Nullable Path folderB, BooleanSupplier stopped)
            throws ToolProblem, InterruptedException {
        try (var jobs = new GeodataDiagnosticJobs()) {
            if (folderB != null) {
                jobs.setComparisonSource(folderB);
            }
            jobs.replayCase(data, file);
            var result = await(jobs, data, stopped);
            if (!result.error().isBlank()) {
                throw new ToolProblem("The case did not replay: "
                        + result.error()
                        + (folderB == null ? ". A comparison case needs from, folder B." : ""));
            }
            var answer = new JSONObject();
            answer.put("action", "replay");
            answer.put("file", file.toString());
            answer.put(
                    "result",
                    result.replayStatus() == null
                            ? "unknown"
                            : word(result.replayStatus().name()));
            answer.put(
                    "means",
                    result.replayStatus() == null
                            ? ""
                            : switch (result.replayStatus()) {
                                case REPRODUCED -> "The same cells give the same outcome.";
                                case REPRODUCED_PARTIAL ->
                                    "The outcome is the same, but a limit or missing data cut the case short.";
                                case INPUT_CHANGED -> "The cells the case reads changed since it was saved.";
                                case OUTCOME_CHANGED -> "The same cells now give another outcome: the checks changed.";
                            });
            if (result.scan() != null) {
                answer.put("findings", result.scan().findings().size());
            }
            if (result.difference() != null) {
                answer.put("changed_cells", result.difference().changedCells());
            }
            return answer;
        }
    }

    static JSONObject bookmarks(List<Bookmark> bookmarks, Path folder) {
        var answer = new JSONObject();
        answer.put("action", "bookmarks");
        answer.put("folder", folder.toString());
        var rows = new JSONArray();
        for (var bookmark : bookmarks) {
            var row = new JSONObject();
            row.put("name", bookmark.name());
            row.put("target", GeoWords.point(bookmark.position()));
            rows.add(row);
        }
        answer.put("list", rows);
        return answer;
    }

    static JSONObject mark(GeodataState state, Path config, Path folder, String name, @Nullable GeoPoint at)
            throws ToolProblem {
        var store = new GeoBookmarkStore(config.resolve(BOOKMARKS));
        List<Bookmark> list;
        try {
            list = new ArrayList<>(store.load(folder));
        } catch (IOException unreadable) {
            throw new ToolProblem("The bookmarks cannot be read: " + unreadable.getMessage());
        }
        if (at == null) {
            if (!list.removeIf(bookmark -> bookmark.name().equalsIgnoreCase(name))) {
                throw new ToolProblem("There is no bookmark named \"" + name + "\". action bookmarks lists them.");
            }
        } else {
            if (list.size() >= MOST_BOOKMARKS) {
                throw new ToolProblem(MOST_BOOKMARKS + " bookmarks is the most for a folder. Remove one first.");
            }
            list.removeIf(bookmark -> bookmark.name().equalsIgnoreCase(name));
            list.add(new Bookmark(name, at));
        }
        try {
            store.save(folder, list);
        } catch (IOException failed) {
            throw new ToolProblem("The bookmarks were not saved: " + failed.getMessage());
        }
        if (Objects.equals(
                state.path == null ? null : state.path.toAbsolutePath().normalize(), folder)) {
            state.inspection.bookmarks.clear();
            state.inspection.bookmarks.addAll(list);
        }
        var answer = bookmarks(list, folder);
        answer.put("action", at == null ? "unbookmark" : "bookmark");
        return answer;
    }

    private static Result await(GeodataDiagnosticJobs jobs, GeoDataAccess data, BooleanSupplier stopped)
            throws ToolProblem, InterruptedException {
        while (jobs.getProgress().busy()) {
            if (stopped.getAsBoolean()) {
                jobs.cancel();
                throw new InterruptedException();
            }
            Thread.sleep(POLL_MILLIS);
        }
        var result = jobs.poll(data);
        if (result == null) {
            throw new ToolProblem("The geodata changed during the case. Call it again.");
        }
        return result;
    }

    private static String word(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}

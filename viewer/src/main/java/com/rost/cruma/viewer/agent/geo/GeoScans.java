package com.rost.cruma.viewer.agent.geo;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataDiff;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataScanner;
import com.rost.cruma.core.geodata.GeoDataScanner.Confidence;
import com.rost.cruma.core.geodata.GeoDataScanner.Finding;
import com.rost.cruma.core.geodata.GeoDataScanner.Limits;
import com.rost.cruma.core.geodata.GeoDataScanner.Rule;
import com.rost.cruma.core.geodata.GeoEditCheck;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoFindingFixes;
import com.rost.cruma.core.geodata.GeoFindingFixes.Fix;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.spawn.SpawnHealth.Impact;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCase;
import com.rost.cruma.viewer.geodata.GeoFindingWords;
import com.rost.cruma.viewer.geodata.GeoRegionCatalog.Catalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class GeoScans {
    static final int MOST_CELLS = 262_144;

    static final int DROP = 128;

    static final int RADIUS = 512;

    static final int COMPARED_REPORTS = 4096;

    static final int REVIEW_CELLS = 400_000;

    private static final int CELL = 16;
    private static final String NO_CLIENT =
            "Client ground is not compared, so Off the client ground is not looked for.";

    private GeoScans() {}

    record Item(String text, String target, String severity) {
        JSONObject json() {
            var item = new JSONObject();
            item.put("text", text);
            item.put("target", target);
            item.put("severity", severity);
            return item;
        }
    }

    record Chosen(Map<Fix, List<Finding>> byFix, int withoutFix, int findings) {}

    static GeoDataScanner.Result scan(GeoDataAccess data, Bounds area, BooleanSupplier stopped) {
        var limits = new Limits(MOST_CELLS, GeoDiagnosticCase.MAX_REPORTS, 2_000_000, DROP, 64, stopped, cells -> {});
        return new GeoDataScanner(data).scan(area, limits);
    }

    static void requireScanSize(Bounds area) throws ToolProblem {
        if ((long) (area.maxX() - area.minX()) / CELL * ((area.maxY() - area.minY()) / CELL) > MOST_CELLS) {
            throw new ToolProblem("That area has more than " + MOST_CELLS
                    + " cells, the most one scan takes: a square of 8192 units. Scan it in parts.");
        }
    }

    static JSONObject scanned(GeoDataScanner.Result result, Bounds area, int limit, boolean information) {
        var answer = new JSONObject();
        answer.put("kind", "scan");
        answer.put("area", area.minX() + "," + area.minY() + " to " + area.maxX() + "," + area.maxY());
        answer.put("cells", result.scannedCells());
        answer.put("search", word(result.completeness().name()));
        if (result.completeness() == Completeness.LIMIT) {
            answer.put(
                    "partial",
                    "The scan reached its limit of cells or steps before the end of the area. Scan smaller parts.");
        }
        if (result.unavailableCells() > 0) {
            answer.put("cells_without_data", result.unavailableCells());
        }
        var kinds = new LinkedHashMap<String, JSONObject>();
        var items = new JSONArray();
        var listed = 0;
        var left = 0;
        for (var level : Confidence.values()) {
            for (var finding : result.findings()) {
                if (finding.confidence() != level) {
                    continue;
                }
                var title = GeoFindingWords.title(finding.rule(), finding.confidence());
                var kind = kinds.computeIfAbsent(title, unused -> kind(finding));
                kind.put("count", kind.getIntValue("count") + 1);
                if (level == Confidence.INFORMATION && !information) {
                    continue;
                }
                if (listed < limit) {
                    items.add(item(finding).json());
                    listed++;
                } else {
                    left++;
                }
            }
        }
        answer.put("kinds", new JSONArray(new ArrayList<>(kinds.values())));
        answer.put("findings", items);
        if (left > 0) {
            answer.put("findings_left_out", left);
        }
        if (result.findingsTruncated()) {
            answer.put(
                    "truncated",
                    "The scan stopped at " + GeoDiagnosticCase.MAX_REPORTS + " findings. Scan a smaller area.");
        }
        answer.put(
                "note",
                (information ? "" : "Information kinds are counted only; give information to list them. ") + NO_CLIENT
                        + " geo_edit action fix applies the first fix of each finding, as Fix all does.");
        return answer;
    }

    static Item item(Finding finding) {
        var title = GeoFindingWords.title(finding.rule(), finding.confidence());
        String more;
        if (!finding.joined().isEmpty()) {
            more = " · " + finding.edges() + (finding.rule() == Rule.CLIENT_SURFACE_MISMATCH ? " cells" : " edges");
        } else if (finding.direction() != null) {
            more = " · " + GeoWords.name(finding.direction());
        } else {
            more = "";
        }
        var detail = finding.detail() == null || finding.detail().isBlank() ? "" : ": " + finding.detail();
        return new Item(title + more + detail, GeoWords.point(finding.position()), severity(finding.confidence()));
    }

    static String fixWord(Fix fix) {
        return fix.label.toLowerCase(Locale.ROOT);
    }

    static Fix fix(String word) throws ToolProblem {
        var wanted = word.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
        var words = new ArrayList<String>();
        for (var fix : Fix.values()) {
            if (fixWord(fix).equals(wanted)) {
                return fix;
            }
            words.add(fixWord(fix));
        }
        throw new ToolProblem("There is no fix \"" + word + "\". Use one of " + words + ".");
    }

    static Chosen choose(List<Finding> findings, List<String> kinds, @Nullable Fix named, @Nullable CellKey cell)
            throws ToolProblem {
        var wanted = new ArrayList<String>();
        for (var kind : kinds) {
            wanted.add(kind.strip().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        for (var kind : wanted) {
            if (!isKind(kind)) {
                throw new ToolProblem("There is no kind of finding \"" + kind
                        + "\". Use the kinds geo_scan names, such as " + kindTitles() + ".");
            }
        }
        var byFix = new LinkedHashMap<Fix, List<Finding>>();
        var withoutFix = 0;
        var count = 0;
        for (var finding : findings) {
            if (!matches(finding, wanted) || cell != null && !touches(finding, cell)) {
                continue;
            }
            count++;
            var fixes = GeoFindingFixes.findFixes(finding);
            var fix = named != null ? fixes.contains(named) ? named : null : fixes.isEmpty() ? null : fixes.getFirst();
            if (fix == null) {
                withoutFix++;
                continue;
            }
            byFix.computeIfAbsent(fix, unused -> new ArrayList<>()).add(finding);
        }
        return new Chosen(byFix, withoutFix, count);
    }

    static Map<CellKey, List<Layer>> edits(GeoDataAccess data, Chosen chosen) {
        var changes = new LinkedHashMap<CellKey, List<Layer>>();
        var view = new Overlay(data, changes);
        for (var entry : chosen.byFix().entrySet()) {
            changes.putAll(GeoFindingFixes.computeEdits(view, entry.getValue(), entry.getKey()));
        }
        return changes;
    }

    private record Overlay(GeoDataAccess under, Map<CellKey, List<Layer>> changes) implements GeoDataAccess {
        @Override
        public Cell cellAt(int worldX, int worldY) {
            var x = Math.floorDiv(worldX, CELL) * CELL;
            var y = Math.floorDiv(worldY, CELL) * CELL;
            var changed = changes.get(new CellKey(x, y));
            return changed == null ? under.cellAt(worldX, worldY) : new Cell(x, y, Availability.AVAILABLE, changed, "");
        }

        @Override
        public long revision() {
            return under.revision();
        }

        @Override
        public String getSourceId() {
            return under.getSourceId();
        }
    }

    static JSONObject compared(GeoDataDiff.Result difference, Bounds area, String folderB, int limit) {
        var answer = new JSONObject();
        answer.put("kind", "compare");
        answer.put("area", area.minX() + "," + area.minY() + " to " + area.maxX() + "," + area.maxY());
        answer.put("b", folderB);
        answer.put("cells", difference.comparedCells());
        answer.put("changed", difference.changedCells());
        answer.put("ambiguous", difference.ambiguousCells());
        if (difference.unavailableCells() > 0) {
            answer.put("cells_without_data", difference.unavailableCells());
        }
        answer.put("search", word(difference.completeness().name()));
        var regions = new TreeMap<String, Integer>();
        var items = new JSONArray();
        var changed = 0;
        for (var cell : difference.cells()) {
            if (!cell.hasChanged()) {
                continue;
            }
            changed++;
            regions.merge(GeoWords.region(cell.x(), cell.y()), 1, Integer::sum);
            if (items.size() < limit) {
                var top = !cell.before().layers().isEmpty()
                        ? cell.before().layers().getFirst().height()
                        : cell.after().layers().isEmpty()
                                ? 0
                                : cell.after().layers().getFirst().height();
                var how = cell.storageSemanticsChanged()
                        ? " · stored "
                                + word(cell.before().storage().name())
                                + " in A, "
                                + word(cell.after().storage().name())
                                + " in B"
                        : "";
                var order = cell.selectionOrderChanged() ? " · layer order" : "";
                items.add(new Item(
                                "A "
                                        + layers(cell.before())
                                        + " · B "
                                        + layers(cell.after())
                                        + how
                                        + order
                                        + (cell.ambiguous() ? " · ambiguous" : ""),
                                (cell.x() + CELL / 2) + "," + (cell.y() + CELL / 2) + "," + top,
                                "info")
                        .json());
            }
        }
        answer.put("by_region", regions);
        answer.put("findings", items);
        if (changed > items.size()) {
            answer.put("findings_left_out", changed - items.size());
        }
        if (difference.reportsTruncated()) {
            answer.put(
                    "truncated",
                    "Only the first " + COMPARED_REPORTS + " changed cells are listed. Compare a smaller area.");
        }
        answer.put(
                "note",
                "A is this folder, B the other. Layers match by height and ways, not by their place in the cell."
                        + " geo_edit action take with from takes a whole region from B.");
        return answer;
    }

    static String layers(Cell cell) {
        if (cell.availability() != Availability.AVAILABLE) {
            return word(cell.availability().name());
        }
        if (cell.layers().isEmpty()) {
            return "no floor";
        }
        var parts = new ArrayList<String>();
        for (var layer : cell.layers()) {
            parts.add(layer.height() + " " + GeoWords.ways(layer.nswe()));
        }
        return String.join("; ", parts);
    }

    static JSONObject reviewed(
            GeoEditCheck.Result check,
            @Nullable Impact spawns,
            GeoDataAccess before,
            Map<CellKey, List<Layer>> changed,
            int limit) {
        var answer = new JSONObject();
        answer.put("kind", "review");
        answer.put("changed_cells", changed.size());
        var items = new JSONArray();
        var left = 0;
        for (var finding : check.introduced()) {
            if (items.size() < limit) {
                items.add(item(finding).json());
            } else {
                left++;
            }
        }
        answer.put("new_problems", check.introduced().size());
        answer.put("new_information", check.information());
        if (check.partial()) {
            answer.put(
                    "partial", "The scan stopped early, so it may miss problems. Edits in a smaller area check fully.");
        }
        if (spawns == null) {
            answer.put("spawns", "NPC Spawns has no npcpos.txt open, so spawns were not checked.");
        } else {
            answer.put("npcs_on_blocked_ground", spawns.blocked().size());
            answer.put("zones_with_no_point", spawns.emptied().size());
            for (var finding : spawns.blocked()) {
                if (items.size() < limit) {
                    items.add(new Item(
                                    "Stands on blocked ground: " + finding.where() + ": " + finding.detail(),
                                    Math.round(finding.x())
                                            + ","
                                            + Math.round(finding.y())
                                            + ","
                                            + Math.round(finding.z()),
                                    "warning")
                            .json());
                } else {
                    left++;
                }
            }
            for (var finding : spawns.emptied()) {
                if (items.size() < limit) {
                    items.add(new Item(
                                    "Lost every point to spawn on: " + finding.where() + ": " + finding.detail(),
                                    Math.round(finding.x())
                                            + ","
                                            + Math.round(finding.y())
                                            + ","
                                            + Math.round(finding.z()),
                                    "warning")
                            .json());
                } else {
                    left++;
                }
            }
        }
        answer.put("findings", items);
        if (left > 0) {
            answer.put("findings_left_out", left);
        }
        var regions = new TreeMap<String, Integer>();
        var keys = new ArrayList<>(changed.keySet());
        keys.sort(Comparator.comparingInt(CellKey::x).thenComparingInt(CellKey::y));
        var cells = new JSONArray();
        for (var key : keys) {
            regions.merge(GeoWords.region(key.x(), key.y()), 1, Integer::sum);
            if (cells.size() < limit) {
                var cell = new JSONObject();
                cell.put("at", (key.x() + CELL / 2) + "," + (key.y() + CELL / 2));
                cell.put("before", layers(before.cellAt(key.x(), key.y())));
                cell.put("after", layers(new Cell(key.x(), key.y(), Availability.AVAILABLE, changed.get(key), "")));
                cells.add(cell);
            }
        }
        answer.put("by_region", regions);
        answer.put("cells", cells);
        answer.put(
                "note",
                "Neither stops a save. geo_edit action revert drops the edits of a region, geo_history undo the last"
                        + " steps.");
        return answer;
    }

    static JSONObject regions(Catalog catalog, int limit) {
        var answer = new JSONObject();
        answer.put("kind", "regions");
        answer.put("folder", catalog.folder().toString());
        answer.put("regions", catalog.count());
        var rows = new JSONArray();
        var left = 0;
        for (var region : catalog.regions()) {
            if (!region.isPresent()) {
                continue;
            }
            if (rows.size() >= limit) {
                left++;
                continue;
            }
            var row = new JSONObject();
            row.put("region", GeoWords.region(region.tile()));
            row.put("file", region.file());
            row.put("format", region.format() == null ? "" : region.format().name());
            row.put("megabytes", Math.round(region.bytes() / 104857.6) / 10.0);
            if (region.layers() >= 0) {
                row.put("layers", region.layers());
            }
            if (region.built()) {
                row.put("built_from_client", true);
            }
            if (catalog.isPathnodeStale(region.tile())) {
                row.put("newer_than_graph", true);
            }
            if (catalog.compared() && catalog.differs().contains(region.tile())) {
                row.put("differs_in_b", true);
            }
            rows.add(row);
        }
        answer.put("list", rows);
        if (left > 0) {
            answer.put("list_left_out", left);
        }
        if (catalog.folderB() != null) {
            var names = new ArrayList<String>();
            for (var tile : catalog.differs()) {
                names.add(GeoWords.region(tile));
            }
            names.sort(null);
            answer.put("b", catalog.folderB().toString());
            answer.put("differ_in_b", catalog.compared() ? String.join(", ", names) : "not compared yet");
        }
        answer.put("graph", catalog.pathnodeModified() > 0 ? "pathnode.bin is there" : "no pathnode.bin");
        return answer;
    }

    private static JSONObject kind(Finding finding) {
        var kind = new JSONObject();
        kind.put("kind", GeoFindingWords.title(finding.rule(), finding.confidence()));
        kind.put("severity", severity(finding.confidence()));
        kind.put("count", 0);
        var fixes = new JSONArray();
        for (var fix : GeoFindingFixes.findFixes(finding)) {
            fixes.add(fixWord(fix));
        }
        if (!fixes.isEmpty()) {
            kind.put("fixes", fixes);
        }
        kind.put("means", GeoFindingWords.help(finding.rule(), finding.confidence()));
        return kind;
    }

    private static String severity(Confidence confidence) {
        return switch (confidence) {
            case ERROR -> "error";
            case HEURISTIC -> "warning";
            case INFORMATION -> "info";
        };
    }

    private static boolean isKind(String kind) {
        for (var rule : Rule.values()) {
            if (word(rule.name()).equals(kind)) {
                return true;
            }
        }
        return kindTitles().stream()
                .anyMatch(title -> title.toLowerCase(Locale.ROOT).equals(kind));
    }

    private static TreeSet<String> kindTitles() {
        var titles = new TreeSet<String>();
        for (var rule : Rule.values()) {
            for (var confidence : Confidence.values()) {
                titles.add(GeoFindingWords.title(rule, confidence));
            }
        }
        return titles;
    }

    private static boolean matches(Finding finding, List<String> wanted) {
        if (wanted.isEmpty()) {
            return finding.confidence() != Confidence.INFORMATION;
        }
        var title = GeoFindingWords.title(finding.rule(), finding.confidence()).toLowerCase(Locale.ROOT);
        return wanted.contains(title) || wanted.contains(word(finding.rule().name()));
    }

    private static boolean touches(Finding finding, CellKey cell) {
        for (var part : finding.joined().isEmpty() ? List.of(finding) : finding.joined()) {
            if (GeoQueryEngine.cellMinimum(part.position().x()) == cell.x()
                    && GeoQueryEngine.cellMinimum(part.position().y()) == cell.y()) {
                return true;
            }
        }
        return false;
    }

    private static String word(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}

package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataDiff;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataScanner;
import com.rost.cruma.core.geodata.GeoDataScanner.Confidence;
import com.rost.cruma.core.geodata.GeoDataScanner.Finding;
import com.rost.cruma.core.geodata.GeoDataScanner.Rule;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoFindingFixes;
import com.rost.cruma.core.geodata.GeoFindingFixes.Fix;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCase;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCase.ReplayStatus;
import com.rost.cruma.viewer.geodata.GeoFindingWords;
import com.rost.cruma.viewer.geodata.GeodataDiagnosticJobs;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.GeodataState;
import com.rost.cruma.viewer.ui.FindingWidgets.FindingList;
import com.rost.cruma.viewer.ui.FindingWidgets.Severity;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

public final class GeodataDiagnosticsPanel implements AutoCloseable {
    private static final int PAGE_SIZE = 10;
    private static final int ERROR_COLOR = ImColor.rgba(255, 80, 80, 240);
    private static final int PROBLEM_COLOR = ImColor.rgba(255, 170, 60, 240);
    private static final int INFO_COLOR = ImColor.rgba(110, 170, 255, 220);
    private static final int ADDED_COLOR = ImColor.rgba(80, 225, 125, 240);
    private static final int REMOVED_COLOR = ImColor.rgba(245, 80, 80, 240);
    private static final int HEIGHT_COLOR = ImColor.rgba(80, 190, 250, 240);
    private static final int FLAGS_COLOR = ImColor.rgba(255, 165, 65, 240);
    private static final int ORDER_COLOR = ImColor.rgba(185, 135, 255, 240);
    private static final int STORAGE_COLOR = ImColor.rgba(255, 115, 185, 240);
    private static final int AMBIGUOUS_COLOR = ImColor.rgba(250, 225, 65, 240);
    private static final int INCOMPLETE_COLOR = ImColor.rgba(190, 190, 190, 220);

    private final GeodataDiagnosticJobs jobs = new GeodataDiagnosticJobs();
    private final FolderPicker folders = new FolderPicker();
    private final ImString sourceB = new ImString(4096);
    private final ImString casePath = new ImString(4096);
    private final FolderPicker casePicker = FolderPicker.file(
            "Choose the case file", "Pick the folder of the case file, and its name below.", "geodata-case.json");
    private String appliedSourceB = "";
    private final ImBoolean replaceCase = new ImBoolean();
    private final ImInt radius = new ImInt(512);
    private final ImInt maximumCells = new ImInt(65536);
    private final ImInt abruptHeight = new ImInt(128);
    private final ImBoolean compareClient = new ImBoolean();
    private final ImInt surfaceTolerance = new ImInt(64);
    private final FindingList list = new FindingList(PAGE_SIZE);

    private Consumer<GeoDataAccess> lastScan;

    private boolean rescan;
    private boolean keepShown;
    private String fixMessage = "";
    private boolean fixFailed;
    private GeodataEditingPanel editor;
    private GeoDataScanner.Result scanned;
    private Finding selectedFinding;
    private ViewMode viewMode = ViewMode.SOURCE_A;
    private String error = "";
    private int page;

    public enum ViewMode {
        SOURCE_A,
        SOURCE_B,
        DIFFERENCES
    }

    static String title(Finding finding) {
        return new Group(finding.rule(), finding.confidence()).title();
    }

    static Severity severity(Finding finding) {
        return new Group(finding.rule(), finding.confidence()).severity();
    }

    private record Group(Rule rule, Confidence confidence) {
        String title() {
            return GeoFindingWords.title(rule, confidence);
        }

        String help() {
            return GeoFindingWords.help(rule, confidence);
        }

        int color() {
            return severity().color;
        }

        Severity severity() {
            return switch (confidence) {
                case ERROR -> Severity.ERROR;
                case HEURISTIC -> Severity.WARNING;
                case INFORMATION -> Severity.INFO;
            };
        }

        String key() {
            return rule + "/" + confidence;
        }
    }

    public void draw(GeodataState state, Camera camera) {
        draw(state, camera, state.queryData());
    }

    public void draw(GeodataState state, Camera camera, GeoDataAccess queryData) {
        if (queryData == null) {
            jobs.cancel();
            GeodataWidgets.hint("Load geodata before running diagnostics.");
            return;
        }
        jobs.poll(queryData);
        if (rescan && lastScan != null && !jobs.getProgress().busy()) {
            rescan = false;
            keepShown = true;
            invoke(() -> lastScan.accept(queryData));
        }
        var center = state.inspection.pointA != null
                ? state.inspection.pointA.center()
                : state.viewFocus != null
                        ? state.viewFocus
                        : new GeoPoint(
                                camera.getPosition().getX(),
                                camera.getPosition().getY(),
                                camera.getPosition().getZ());
        state.clientSurfaces.focus(center.x(), center.y());
        if (GeodataWidgets.header("Scan current area", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(
                    GeodataWidgets.MUTED,
                    "Around "
                            + (state.inspection.pointA == null ? "the view focus" : "route end A")
                            + " · "
                            + Math.round(center.x())
                            + ", "
                            + Math.round(center.y()));
            GeodataWidgets.integer("Radius", radius, 16);
            GeodataWidgets.tip("Half the side of the scanned square, in world units. One cell is 16 units.");
            radius.set(Math.clamp(radius.get(), 16, 4096));
            if (GeodataWidgets.header("Scan limits and thresholds")) {
                GeodataWidgets.integer("Cells at most", maximumCells, 1024);
                maximumCells.set(Math.clamp(maximumCells.get(), 1, 262144));
                GeodataWidgets.integer("Long drop", abruptHeight, 8);
                abruptHeight.set(Math.clamp(abruptHeight.get(), 8, 4096));
                GeodataWidgets.tip(
                        "An open edge that falls at least this far is listed as a long drop, or as a region seam on a"
                                + " region border.");
            }
            ImGui.checkbox("Compare client ground", compareClient);
            if (compareClient.get()) {
                GeodataWidgets.integer("Tolerance", surfaceTolerance, 8);
                GeodataWidgets.tip(
                        "How far geodata may lie from walkable client ground before a cell counts as off it, in"
                                + " units.");
                surfaceTolerance.set(Math.clamp(surfaceTolerance.get(), 0, 4096));
                var client = state.clientSurfaces.getStatus();
                GeodataWidgets.hint("Client reference: "
                        + client.maps()
                        + " maps · "
                        + client.triangles()
                        + " triangles · "
                        + client.retainedBytes() / (1024 * 1024)
                        + " MiB · "
                        + client.truncatedMaps()
                        + " truncated maps");
                if (client.maps() == 0) {
                    GeodataWidgets.note(
                            "No client ground is cached yet. Reload the scene with geodata open.",
                            GeodataWidgets.WARNING);
                }
            }
            if (GeodataWidgets.actionButton("geo-scan", Glyph.SHIELD, "Scan area", -1, true)) {
                var area = boundsAround(center);
                var cells = maximumCells.get();
                var drop = abruptHeight.get();
                var client = compareClient.get();
                var tolerance = surfaceTolerance.get();
                lastScan = data -> jobs.scan(
                        data,
                        area,
                        cells,
                        GeoDiagnosticCase.MAX_REPORTS,
                        2_000_000,
                        drop,
                        client ? state.clientSurfaces.snapshot(2_000_000) : null,
                        tolerance);
                fixMessage = "";
                invoke(() -> lastScan.accept(queryData));
            }
        }
        if (GeodataWidgets.header("Compare another source")) {
            if (GeodataWidgets.pathField(
                    "geo-folder-b",
                    "Folder B",
                    sourceB,
                    "Another geodata folder, such as an older copy or another server's files. Paste a path or"
                            + " browse.")) {
                invoke(() -> folders.open(sourceB.get().isBlank() ? null : Path.of(sourceB.get())));
            }
            if (!sourceB.get().equals(appliedSourceB)) {
                appliedSourceB = sourceB.get();
                applySourceB();
                page = 0;
            }
            ImGui.beginDisabled(jobs.getComparisonSource() == null);
            if (GeodataWidgets.rowButton("Compare area", 0, 2)) {
                invoke(() -> jobs.compare(queryData, boundsAround(center), maximumCells.get(), 4096, 20_000_000));
                page = 0;
            }
            GeodataWidgets.tip("Compares A and B cell by cell in the scan area.");
            if (GeodataWidgets.rowButton("Reload B", 1, 2)) {
                jobs.refreshComparisonSource();
            }
            GeodataWidgets.tip("Reads folder B again after its files changed.");
            ImGui.endDisabled();
            GeodataWidgets.space(GeodataWidgets.ROW);
            var shown = GeodataWidgets.segmented(
                    "geo-compare-view", new String[] {"Source A", "Source B", "Differences"}, viewMode.ordinal());
            if (shown >= 0) {
                viewMode = ViewMode.values()[shown];
            }
            GeodataWidgets.tip("What the view shows: this folder, folder B, or the differences of the last comparison."
                    + " Differences match layers by content and height, not by layer index; equal candidates are"
                    + " marked ambiguous.");
        }
        var selected = folders.draw();
        if (selected != null) {
            sourceB.set(selected.toString());
            appliedSourceB = sourceB.get();
            applySourceB();
        }
        var progress = jobs.getProgress();
        if (progress.busy()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.progress(
                    "geo-diagnostics-progress",
                    progress.phase(),
                    String.format(Locale.ROOT, "%,d of %,d cells", progress.completedCells(), progress.maxCells()),
                    progress.maxCells() > 0 ? (double) progress.completedCells() / progress.maxCells() : -1,
                    "Stop")) {
                jobs.cancel();
            }
        }
        if (!error.isBlank()) {
            GeodataWidgets.note(error, GeodataWidgets.WARNING);
        }
        var result = jobs.poll(queryData);
        if (result == null) {
            if (viewMode != ViewMode.SOURCE_A) {
                GeodataWidgets.hint("Run Compare to make source B and the differences available.");
            }
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        text(
                GeodataWidgets.FAINT,
                String.format(
                        Locale.ROOT, "%.2f ms · source revision %d", result.elapsedNanos() / 1e6, result.revision()));
        if (!result.error().isBlank()) {
            GeodataWidgets.note(result.error(), GeodataWidgets.WARNING);
        }
        if (!result.message().isBlank()) {
            GeodataWidgets.hint(result.message());
            var saved = savedCase(result.message());
            if (saved != null) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.showFolder("geo-case-written", saved);
            }
        }
        if (result.replayStatus() != null) {
            GeodataWidgets.hint("Case replay: "
                    + GeodataRoutes.toReadable(result.replayStatus().name()));
            if (result.replayStatus() == ReplayStatus.REPRODUCED_PARTIAL) {
                GeodataWidgets.hint(
                        "The same partial result was reproduced. This does not confirm a complete diagnostic of the"
                                + " area.");
            }
        }
        if (result.scan() != null) {
            drawScan(state, camera, queryData, result.scan());
        } else if (result.difference() != null) {
            drawDiff(state, camera, queryData, result.difference());
        }
    }

    void drawCase(GeodataState state) {
        var queryData = state.queryData();
        if (queryData == null) {
            return;
        }
        if (GeodataWidgets.pathField(
                "geo-case-file",
                "Case JSON file",
                casePath,
                "Where a diagnostic case is saved, or the case to replay.")) {
            casePicker.openPath(casePath.get());
        }
        var chosen = casePicker.draw();
        if (chosen != null) {
            casePath.set(chosen.toString());
        }
        ImGui.checkbox("Replace an existing case file", replaceCase);
        var current = jobs.poll(queryData);
        ImGui.beginDisabled(casePath.get().isBlank()
                || current == null
                || current.diagnosticCase() == null
                || jobs.getProgress().busy());
        if (GeodataWidgets.rowButton("Save case", 0, 2)) {
            invoke(() -> jobs.saveCase(queryData, Path.of(casePath.get()), replaceCase.get()));
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                "Saves the last scan or comparison: its bounds, work limits, algorithm and hashes of the visited"
                        + " cells.");
        ImGui.beginDisabled(casePath.get().isBlank());
        if (GeodataWidgets.rowButton("Load and replay", 1, 2)) {
            invoke(() -> jobs.replayCase(queryData, Path.of(casePath.get()), state.clientSurfaces::snapshot));
            page = 0;
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                "Replays a saved case on the loaded A/B data and edit preview. Reload sources after files change"
                        + " outside.");
    }

    void setEditor(GeodataEditingPanel editing) {
        editor = editing;
    }

    @Nullable
    Path getComparisonFolder() {
        return jobs.getComparisonSource();
    }

    public ViewMode getViewMode() {
        return viewMode;
    }

    public void showSourceA() {
        viewMode = ViewMode.SOURCE_A;
    }

    public GeoDataSnapshot getSourceB(GeoDataAccess currentSourceA) {
        var result = jobs.poll(currentSourceA);
        return result == null ? null : result.sourceB();
    }

    public GeoDataDiff.Result pollComparison(GeoDataAccess currentSourceA) {
        var result = jobs.poll(currentSourceA);
        return result == null ? null : result.difference();
    }

    public GeoDataScanner.Result scanResult(GeoDataAccess currentSourceA) {
        var result = jobs.poll(currentSourceA);
        return result == null ? null : result.scan();
    }

    public boolean pick(GeoDataAccess sourceA, Camera camera, float x, float y, int width, int height) {
        var scan = scanResult(sourceA);
        if (scan == null || scan != scanned) {
            return false;
        }
        var shown = shownFindings(scan);
        Finding best = null;
        var bestDistance = 14f * 14f;
        for (var finding : shown) {
            for (var part : finding.joined().isEmpty() ? List.of(finding) : finding.joined()) {
                var screen = camera.projectToScreenRaw(toVector(part.position()), width, height);
                if (screen == null) {
                    continue;
                }
                var dx = screen.getX() - x;
                var dy = screen.getY() - y;
                if (dx * dx + dy * dy < bestDistance) {
                    bestDistance = dx * dx + dy * dy;
                    best = finding;
                }
            }
        }
        if (best == null) {
            return false;
        }
        selectedFinding = best;
        list.showPageOf(shown.indexOf(best));
        return true;
    }

    public void drawOverlay(GeoDataAccess sourceA, Camera camera, ImDrawList draw, int width, int height) {
        var scan = scanResult(sourceA);
        if (scan != null && scan == scanned) {
            drawFindings(scan, camera, draw, width, height);
        }
        var difference = pollComparison(sourceA);
        if (viewMode != ViewMode.DIFFERENCES || difference == null) {
            return;
        }
        var count = 0;
        for (var cell : difference.cells()) {
            if (count++ >= 2048) {
                break;
            }
            var z = firstHeight(cell.before(), firstHeight(cell.after(), 0));
            var screen = camera.projectToScreenRaw(new Vec3(cell.x() + 8f, cell.y() + 8f, z + 4f), width, height);
            if (screen != null) {
                var color = !cell.complete()
                        ? INCOMPLETE_COLOR
                        : cell.ambiguous()
                                ? AMBIGUOUS_COLOR
                                : cell.storageSemanticsChanged()
                                        ? STORAGE_COLOR
                                        : cell.selectionOrderChanged() ? ORDER_COLOR : HEIGHT_COLOR;
                draw.addCircle(screen.getX(), screen.getY(), 4, color, 8, 1.5f);
                if (!cell.complete()
                        || cell.ambiguous()
                        || cell.selectionOrderChanged()
                        || cell.storageSemanticsChanged()) {
                    draw.addText(
                            screen.getX() + 5,
                            screen.getY() - 7,
                            color,
                            !cell.complete()
                                    ? "?"
                                    : cell.ambiguous() ? "~" : cell.storageSemanticsChanged() ? "S" : "O");
                }
            }
            var shownLayers = 0;
            for (var match : cell.matches()) {
                if (shownLayers++ >= 8) {
                    break;
                }
                var before =
                        match.beforeIndex() < 0 ? null : cell.before().layers().get(match.beforeIndex());
                var after =
                        match.afterIndex() < 0 ? null : cell.after().layers().get(match.afterIndex());
                var from = before == null
                        ? null
                        : camera.projectToScreenRaw(
                                new Vec3(cell.x() + 8f, cell.y() + 8f, before.height() + 4f), width, height);
                var to = after == null
                        ? null
                        : camera.projectToScreenRaw(
                                new Vec3(cell.x() + 8f, cell.y() + 8f, after.height() + 4f), width, height);
                if (before == null && to != null) {
                    draw.addText(to.getX() - 4, to.getY() - 7, ADDED_COLOR, "+");
                } else if (after == null && from != null) {
                    draw.addText(from.getX() - 4, from.getY() - 7, REMOVED_COLOR, "x");
                } else if (!match.ambiguous()) {
                    if (match.heightChanged() && from != null && to != null) {
                        draw.addLine(from.getX(), from.getY(), to.getX(), to.getY(), HEIGHT_COLOR, 2);
                        draw.addCircle(to.getX(), to.getY(), 3, HEIGHT_COLOR, 8, 2);
                        draw.addText(to.getX() + 4, to.getY() - 7, HEIGHT_COLOR, "Z");
                    }
                    if (match.nsweChanged() && to != null) {
                        draw.addCircle(to.getX(), to.getY(), 6, FLAGS_COLOR, 8, 2);
                        for (var direction : Direction.values()) {
                            if (((before.nswe() ^ after.nswe()) & direction.getMask()) == 0) {
                                continue;
                            }
                            var end = camera.projectToScreenRaw(
                                    new Vec3(
                                            cell.x() + 8f + direction.getDx() * .4f,
                                            cell.y() + 8f + direction.getDy() * .4f,
                                            after.height() + 4f),
                                    width,
                                    height);
                            if (end != null) {
                                draw.addLine(
                                        to.getX(),
                                        to.getY(),
                                        end.getX(),
                                        end.getY(),
                                        (after.nswe() & direction.getMask()) != 0 ? ADDED_COLOR : REMOVED_COLOR,
                                        2);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        jobs.close();
    }

    private void drawFindings(GeoDataScanner.Result scan, Camera camera, ImDrawList draw, int width, int height) {
        var count = 0;
        for (var finding : shownFindings(scan)) {
            if (count++ >= 4096) {
                break;
            }
            var color = new Group(finding.rule(), finding.confidence()).color();
            var from = camera.projectToScreenRaw(toVector(finding.position()), width, height);
            if (from == null) {
                continue;
            }
            var radius = finding == selectedFinding ? 7f : 4f;
            for (var edge : finding.joined()) {
                drawEdge(edge, camera, draw, width, height, color, finding == selectedFinding);
            }
            if (finding.target() != null && finding.direction() != null) {
                var to = camera.projectToScreenRaw(toVector(finding.target()), width, height);
                if (to != null) {
                    var dx = to.getX() - from.getX();
                    var dy = to.getY() - from.getY();
                    var length = (float) Math.sqrt(dx * dx + dy * dy);
                    draw.addLine(from.getX(), from.getY(), to.getX(), to.getY(), color, 2);
                    if (length > 6) {
                        var ux = dx / length;
                        var uy = dy / length;
                        draw.addTriangleFilled(
                                to.getX(),
                                to.getY(),
                                to.getX() - ux * 7 - uy * 4,
                                to.getY() - uy * 7 + ux * 4,
                                to.getX() - ux * 7 + uy * 4,
                                to.getY() - uy * 7 - ux * 4,
                                color);
                    }
                }
            }
            draw.addCircleFilled(from.getX(), from.getY(), radius, ImColor.rgba(8, 14, 20, 200), 12);
            draw.addCircleFilled(from.getX(), from.getY(), radius - 1.5f, color, 12);
            if (finding == selectedFinding) {
                draw.addCircle(from.getX(), from.getY(), radius + 5, ImColor.rgba(255, 255, 255, 230), 20, 2f);
            }
        }
    }

    private void drawScan(GeodataState state, Camera camera, GeoDataAccess data, GeoDataScanner.Result scan) {
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var groups = new LinkedHashMap<Group, Integer>();
        for (var confidenceLevel : Confidence.values()) {
            for (var finding : scan.findings()) {
                if (finding.confidence() == confidenceLevel) {
                    groups.merge(new Group(finding.rule(), finding.confidence()), 1, Integer::sum);
                }
            }
        }
        if (scanned != scan) {
            scanned = scan;
            selectedFinding = null;
            var hiddenKeys = new ArrayList<String>();
            for (var group : groups.keySet()) {
                if (group.confidence() == Confidence.INFORMATION) {
                    hiddenKeys.add(group.key());
                }
            }
            if (keepShown) {
                keepShown = false;
            } else {
                list.reset(hiddenKeys);
            }
        }
        var problems = 0;
        for (var entry : groups.entrySet()) {
            if (entry.getKey().confidence() != Confidence.INFORMATION) {
                problems += entry.getValue();
            }
        }
        GeodataWidgets.dot(problems == 0 ? GeodataWidgets.OPEN : PROBLEM_COLOR);
        ImGui.textUnformatted(
                problems == 0
                        ? "No problems found"
                        : problems == 1
                                ? "1 possible problem"
                                : String.format(Locale.ROOT, "%,d possible problems", problems));
        hint(String.format(
                Locale.ROOT,
                "%,d cells · %s",
                scan.scannedCells(),
                GeodataWidgets.completenessLabel(scan.completeness())));
        if (scan.clientGeometryChecked()) {
            hint(String.format(
                    Locale.ROOT,
                    "Client ground compared in %,d cells, %,d incomplete",
                    scan.clientCheckedCells(),
                    scan.clientUnavailableCells()));
        }
        if (scan.findingsTruncated()) {
            warning("Too many findings: the list is incomplete. Scan a smaller area.");
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var rows = new ArrayList<FindingList.Group>();
        for (var entry : groups.entrySet()) {
            var group = entry.getKey();
            rows.add(new FindingList.Group(
                    group.key(), group.title(), group.severity(), entry.getValue(), group.help()));
        }
        list.groupCheckboxes("geo-scan-groups", rows);
        var filtered = shownFindings(scan);
        if (!fixMessage.isBlank()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.pushTextWrapPos(0);
            text(fixFailed ? GeodataWidgets.WARNING : GeodataWidgets.OPEN, fixMessage);
            ImGui.popTextWrapPos();
        }
        if (filtered.isEmpty()) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var range = list.drawPager("geo-scan-pager", filtered.size());
        GeodataWidgets.space(GeodataWidgets.ROW);
        for (var index = range[0]; index < range[1]; index++) {
            var finding = filtered.get(index);
            var group = new Group(finding.rule(), finding.confidence());
            var fixes = GeoFindingFixes.findFixes(finding);
            var labels = new ArrayList<String>();
            for (var fix : fixes) {
                labels.add(fix.label);
            }
            var kind = groups.getOrDefault(group, 0);
            if (!fixes.isEmpty() && kind > 1) {
                labels.add("Fix all " + kind);
            }
            var title = group.title()
                    + (finding.joined().isEmpty()
                            ? finding.direction() == null
                                    ? ""
                                    : " · "
                                            + GeodataWidgets.name(finding.direction())
                                                    .toLowerCase(Locale.ROOT)
                            : " · " + finding.edges() + " " + unit(finding));
            var detail = formatCoordinates(finding.position()) + " · " + finding.detail();
            var pressed = FindingWidgets.card(
                    "geo-finding-" + index, group.severity(), title, detail, labels, finding == selectedFinding);
            if (pressed == FindingWidgets.FLY) {
                selectedFinding = finding;
                GeodataWorkspace.focus(camera, finding.position());
            } else if (pressed >= 0 && pressed < fixes.size()) {
                fix(state, List.of(finding), fixes.get(pressed));
            } else if (pressed >= 0) {
                var all = new ArrayList<Finding>();
                for (var each : scan.findings()) {
                    if (new Group(each.rule(), each.confidence()).equals(group)
                            && GeoFindingFixes.findFixes(each).contains(fixes.getFirst())) {
                        all.add(each);
                    }
                }
                fix(state, all, fixes.getFirst());
            }
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        var finding = selectedFinding;
        if (finding == null || !scan.findings().contains(finding)) {
            return;
        }
        GeodataWidgets.section("SELECTED FINDING");
        GeodataWidgets.value("At", formatCoordinates(finding.position()));
        if (!finding.joined().isEmpty()) {
            GeodataWidgets.value(
                    finding.rule() == Rule.CLIENT_SURFACE_MISMATCH ? "Cells" : "Edges",
                    String.valueOf(finding.edges()));
        }
        if (finding.target() != null) {
            GeodataWidgets.value("To", formatCoordinates(finding.target()));
        }
        GeodataWidgets.value("Layers", formatLayers(finding.source()));
        if (finding.neighbor() != null) {
            GeodataWidgets.value("Neighbor", formatLayers(finding.neighbor()));
        }
        if (GeodataWidgets.rowButton("Route from here", 0, 2)) {
            pin(state, data, finding.position(), true);
            if (finding.target() != null) {
                pin(state, data, finding.target(), false);
            }
        }
        GeodataWidgets.tip("Sets A here and B at the target, for the Route tool.");
        if (GeodataWidgets.rowButton("Copy", 1, 2)) {
            ImGui.setClipboardText(finding.rule()
                    + " "
                    + formatCoordinates(finding.position())
                    + (finding.target() == null ? "" : " -> " + formatCoordinates(finding.target())));
        }
        GeodataWidgets.tip("Copies the kind and the coordinates.");
    }

    private void fix(GeodataState state, List<Finding> findings, Fix fix) {
        var data = state.queryData();
        if (editor == null || data == null) {
            return;
        }
        var cells = GeoFindingFixes.computeEdits(data, findings, fix);
        if (cells.isEmpty()) {
            fixMessage = "Nothing to change: the cells no longer match the finding. Scan again.";
            fixFailed = true;
            return;
        }
        var label = findings.size() == 1 ? fix.label : fix.label + " · " + findings.size() + " findings";
        var changed = editor.apply(state, label, cells);
        if (changed < 0) {
            fixMessage = editor.getProblem();
            fixFailed = true;
            return;
        }
        fixMessage = label + ": " + changed + (changed == 1 ? " cell" : " cells") + " changed. Undo takes it back.";
        fixFailed = false;
        rescan = true;
    }

    private List<Finding> shownFindings(GeoDataScanner.Result scan) {
        var filtered = new ArrayList<Finding>();
        for (var finding : scan.findings()) {
            if (list.isShown(new Group(finding.rule(), finding.confidence()).key())) {
                filtered.add(finding);
            }
        }
        return filtered;
    }

    private void drawDiff(GeodataState state, Camera camera, GeoDataAccess data, GeoDataDiff.Result difference) {
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        GeodataWidgets.hint("Comparison: "
                + difference.completeness()
                + " · checked "
                + difference.comparedCells()
                + " · changed "
                + difference.changedCells()
                + " · ambiguous "
                + difference.ambiguousCells()
                + " · unavailable "
                + difference.unavailableCells());
        if (difference.reportsTruncated()) {
            GeodataWidgets.note(
                    "Difference storage reached its limit. Counts include additional checked cells.",
                    GeodataWidgets.WARNING);
        }
        GeodataWidgets.hint(
                "Marks: + added, x removed, Z height, ring directions (green opens, red closes), O order, S storage, ~"
                        + " ambiguous, ? incomplete.");
        GeodataWidgets.tip("Up to 2048 cells and the first eight layer matches per cell are drawn.");
        drawPager(difference.cells().size());
        for (var index = page * PAGE_SIZE;
                index < Math.min(difference.cells().size(), (page + 1) * PAGE_SIZE);
                index++) {
            var cell = difference.cells().get(index);
            ImGui.pushID(index);
            if (ImGui.treeNode(cell.x()
                    + ", "
                    + cell.y()
                    + " · "
                    + (cell.ambiguous() ? "ambiguous" : cell.complete() ? "changed" : "incomplete"))) {
                GeodataWidgets.hint("A: " + formatLayers(cell.before()));
                GeodataWidgets.hint("B: " + formatLayers(cell.after()));
                if (cell.selectionOrderChanged()) {
                    GeodataWidgets.hint(
                            "Relative layer order changed. Server base-layer selection may choose a different"
                                    + " surface.");
                }
                if (cell.storageSemanticsChanged()) {
                    GeodataWidgets.hint("Storage changed "
                            + cell.before().storage()
                            + " -> "
                            + cell.after().storage()
                            + ". Server pointer identity or sight selection can change even when layer values"
                            + " match. This is separate from layer edits.");
                }
                for (var match : cell.matches()) {
                    GeodataWidgets.hint((match.beforeIndex() < 0
                                    ? "Added layer " + match.afterIndex()
                                    : match.afterIndex() < 0
                                            ? "Removed layer " + match.beforeIndex()
                                            : "Layer " + match.beforeIndex() + " -> " + match.afterIndex())
                            + (match.heightChanged() ? " height changed" : "")
                            + (match.nsweChanged() ? " directions changed" : "")
                            + (match.ambiguous() ? " ambiguous match" : ""));
                }
                var position = new GeoPoint(
                        cell.x() + 8.0, cell.y() + 8.0, firstHeight(cell.before(), firstHeight(cell.after(), 0)));
                if (GeodataWidgets.rowButton("Go to cell", 0, 2)) {
                    focus(camera, position);
                }
                if (GeodataWidgets.rowButton("Inspect A", 1, 2)) {
                    pin(state, data, position, true);
                }
                drawSaveCase(data, "Save comparison case including this cell");
                ImGui.treePop();
            }
            ImGui.popID();
        }
    }

    private void drawPager(int total) {
        var pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.clamp(page, 0, pages - 1);
        if (pages == 1) {
            ImGui.textColored(GeodataWidgets.MUTED, total == 1 ? "1 result" : total + " results");
            return;
        }
        ImGui.beginDisabled(page == 0);
        if (GeodataWidgets.rowButton("Previous", 0, 2)) {
            page--;
        }
        ImGui.endDisabled();
        ImGui.beginDisabled(page + 1 >= pages);
        if (GeodataWidgets.rowButton("Next", 1, 2)) {
            page++;
        }
        ImGui.endDisabled();
        text(GeodataWidgets.FAINT, "Page " + (page + 1) + " of " + pages + " · " + total + " results");
    }

    private void drawSaveCase(GeoDataAccess data, String label) {
        ImGui.beginDisabled(casePath.get().isBlank() || jobs.getProgress().busy());
        if (GeodataWidgets.actionButton("geo-save-case-" + label.hashCode(), null, "Save diagnostic case", -1, false)) {
            invoke(() -> jobs.saveCase(data, Path.of(casePath.get()), replaceCase.get()));
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(label + ". Set the file under Advanced, Save or replay diagnostic case.");
    }

    private Bounds boundsAround(GeoPoint center) {
        var x = GeoQueryEngine.serverInt(center.x());
        var y = GeoQueryEngine.serverInt(center.y());
        return new Bounds(
                (int) Math.max(Integer.MIN_VALUE, (long) x - radius.get()),
                (int) Math.max(Integer.MIN_VALUE, (long) y - radius.get()),
                (int) Math.min(Integer.MAX_VALUE, (long) x + radius.get()),
                (int) Math.min(Integer.MAX_VALUE, (long) y + radius.get()));
    }

    private void applySourceB() {
        try {
            jobs.setComparisonSource(sourceB.get().isBlank() ? null : Path.of(sourceB.get()));
            error = "";
        } catch (RuntimeException exception) {
            jobs.setComparisonSource(null);
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private void invoke(Runnable action) {
        try {
            action.run();
            error = "";
        } catch (RuntimeException exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private @Nullable Path savedCase(String message) {
        if (!message.startsWith("Diagnostic case saved") || casePath.get().isBlank()) {
            return null;
        }
        try {
            var file = Path.of(casePath.get().trim());
            return Files.isRegularFile(file) ? file : null;
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    private static void hint(String value) {
        text(GeodataWidgets.MUTED, value);
    }

    private static void warning(String value) {
        ImGui.pushTextWrapPos(0);
        text(GeodataWidgets.WARNING, value);
        ImGui.popTextWrapPos();
    }

    private static void text(int color, String value) {
        ImGui.pushStyleColor(ImGuiCol.Text, color);
        ImGui.textUnformatted(value);
        ImGui.popStyleColor();
    }

    private static void drawEdge(
            Finding edge, Camera camera, ImDrawList draw, int width, int height, int color, boolean selected) {
        var direction = edge.direction();
        if (direction == null) {
            var point = camera.projectToScreenRaw(toVector(edge.position()), width, height);
            if (point != null) {
                draw.addCircleFilled(point.getX(), point.getY(), selected ? 4 : 3, color, 8);
            }
            return;
        }
        var center = edge.position();
        var x = center.x() + direction.getDx() / 2.0;
        var y = center.y() + direction.getDy() / 2.0;
        var alongX = direction.getDy() == 0 ? 0 : 7;
        var alongY = direction.getDx() == 0 ? 0 : 7;
        var start =
                camera.projectToScreenRaw(toVector(new GeoPoint(x - alongX, y - alongY, center.z())), width, height);
        var end = camera.projectToScreenRaw(toVector(new GeoPoint(x + alongX, y + alongY, center.z())), width, height);
        if (start != null && end != null) {
            draw.addLine(
                    start.getX(), start.getY(), end.getX(), end.getY(), ImColor.rgba(8, 14, 20, 200), selected ? 7 : 5);
            draw.addLine(start.getX(), start.getY(), end.getX(), end.getY(), color, selected ? 4 : 2.5f);
        }
    }

    private static Vec3 toVector(GeoPoint point) {
        return new Vec3((float) point.x(), (float) point.y(), (float) point.z() + 6);
    }

    private static String unit(Finding finding) {
        return finding.rule() == Rule.CLIENT_SURFACE_MISMATCH ? "cells" : "edges";
    }

    private static void pin(GeodataState state, GeoDataAccess data, GeoPoint position, boolean first) {
        var selected = new GeoQueryEngine(data).findBaseCell(position);
        state.inspection.select(
                data,
                GeoQueryEngine.serverInt(position.x()),
                GeoQueryEngine.serverInt(position.y()),
                selected == null ? -1 : selected.layer().index());
        if (first) {
            state.inspection.pointA = state.inspection.selected;
        } else {
            state.inspection.pointB = state.inspection.selected;
        }
    }

    private static int firstHeight(Cell cell, int fallback) {
        return cell.layers().isEmpty() ? fallback : cell.layers().getFirst().height();
    }

    private static String formatLayers(Cell cell) {
        if (cell.availability() != Availability.AVAILABLE) {
            return cell.availability() + ": " + cell.detail();
        }
        var text = new StringBuilder();
        for (var layer : cell.layers()) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append(layer.index())
                    .append(" Z=")
                    .append(layer.height())
                    .append(" NSWE=")
                    .append(layer.nswe());
        }
        return text.isEmpty() ? "no ground layers" : text.toString();
    }

    private static String formatCoordinates(GeoPoint point) {
        return String.format(Locale.ROOT, "%.0f, %.0f, %.0f", point.x(), point.y(), point.z());
    }

    private static void focus(Camera camera, GeoPoint position) {
        camera.setPosition((float) position.x(), (float) position.y(), (float) position.z() + 256);
    }
}

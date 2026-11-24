package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoEditDocument;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.viewer.geodata.GeoScenario;
import com.rost.cruma.viewer.geodata.GeoScenario.Fingerprints;
import com.rost.cruma.viewer.geodata.GeoScenarioFingerprints;
import com.rost.cruma.viewer.geodata.GeoScenarioRunner.Context;
import com.rost.cruma.viewer.geodata.GeoScenarioScreenshot;
import com.rost.cruma.viewer.geodata.GeoScenarioScreenshot.Marker;
import com.rost.cruma.viewer.geodata.GeoScenarioSource;
import com.rost.cruma.viewer.geodata.GeoScenarioStore;
import com.rost.cruma.viewer.geodata.GeoScenarioSuiteReport;
import com.rost.cruma.viewer.geodata.GeoScenarioSuiteRunner;
import com.rost.cruma.viewer.geodata.GeoScenarioSuiteRunner.Report;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import com.rost.cruma.viewer.render.GeodataState;
import com.rost.cruma.viewer.render.api.ReadbackRequest;
import com.rost.cruma.viewer.render.api.ReadbackTicket;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import glm_.vec3.Vec3;
import imgui.ImGui;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class GeodataScenarioSuitePanel implements AutoCloseable {
    private final ImString files = new ImString(32768);
    private final ImString reportFile = new ImString(4096);
    private final ImString screenshotFile = new ImString(4096);
    private final FolderPicker filesPicker = FolderPicker.file(
            "Add a scenario file",
            "Pick the folder of a saved case, and its file name below.",
            "geodata-scenario.json");
    private final FolderPicker reportPicker = FolderPicker.file(
            "Choose the suite report file",
            "Pick the folder for the report, and its name below.",
            "geodata-suite-report.json");
    private final FolderPicker screenshotPicker = FolderPicker.file(
            "Choose the PNG file", "Pick the folder for the image, and its name below.", "geodata-scene.png");
    private final ImInt repetitions = new ImInt(3);
    private final ImInt selected = new ImInt();
    private final Map<String, Path> bindings = new HashMap<>();
    private List<GeoScenario> cases = List.of();
    private Report report;
    private boolean allowChanged, useOriginal, replaceExisting;

    private @Nullable Path messageFile;

    private String message = "Load up to 256 saved cases to run a reproducible suite.";
    private volatile boolean busy, cancelRequested;
    private volatile long generation;
    private volatile Completion completed;
    private Future<?> pending;
    private ReadbackTicket ticket;
    private Capture capture;
    private Extent captureExtent;
    private List<Marker> captureMarkers;
    private boolean captureBottomLeft;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "geodata-scenario-suite");
        thread.setDaemon(true);
        return thread;
    });

    public void draw(GeodataState state, Camera camera, ViewerRenderBackend backend) {
        tick(state, camera, backend);
        ImGui.textColored(GeodataWidgets.MUTED, "Scenario JSON files, one per line");
        ImGui.inputTextMultiline("##Scenario JSON files", files, ImGui.getContentRegionAvailX(), 90);
        if (GeodataWidgets.actionButton("suite-add-file", Glyph.FOLDER, "Add a file\u2026", -1, false)) {
            var lines = files.get().strip().lines().toList();
            filesPicker.openPath(lines.isEmpty() ? "" : lines.getLast());
        }
        var added = filesPicker.draw();
        if (added != null) {
            var current = files.get().strip();
            files.set(current.isEmpty() ? added.toString() : current + "\n" + added);
        }
        ImGui.beginDisabled(busy || capture != null || files.get().isBlank());
        if (GeodataWidgets.actionButton("suite-load-suite-files", null, "Load suite files", -1, false)) {
            var paths = files.get();
            start(cancelled -> {
                var loaded = new ArrayList<GeoScenario>();
                for (var line : paths.lines()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList()) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Suite loading cancelled");
                    }
                    if (loaded.size() == 256) {
                        throw new IOException("Suite limit is 256 cases");
                    }
                    loaded.add(GeoScenarioStore.read(Path.of(line)));
                }
                if (loaded.isEmpty()) {
                    throw new IOException("No scenario files selected");
                }
                return new Completion(
                        List.copyOf(loaded), null, "Suite loaded. Bind its aliases locally before running.");
            });
        }
        ImGui.endDisabled();
        for (var alias :
                cases.stream().map(GeoScenario::sourceAlias).distinct().sorted().toList()) {
            ImGui.pushID(alias);
            GeodataWidgets.hint(alias + " → " + bindings.getOrDefault(alias, Path.of("unbound")));
            ImGui.beginDisabled(busy || capture != null || state.path == null);
            if (GeodataWidgets.actionButton(
                    "suite-bind-alias-to-current-source-a", null, "Bind alias to current source A", -1, false)) {
                bindings.put(alias, state.path.toAbsolutePath().normalize());
            }
            ImGui.endDisabled();
            ImGui.popID();
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.integer("Repeat each", repetitions, 1);
        if (ImGui.checkbox("Compare against changed data", allowChanged)) {
            allowChanged = !allowChanged;
        }
        if (ImGui.checkbox("Use original source files", useOriginal)) {
            useOriginal = !useOriginal;
        }
        ImGui.beginDisabled(busy || capture != null || cases.isEmpty());
        if (GeodataWidgets.actionButton(
                "suite-run-saved-scenario-suite", null, "Run saved scenario suite", -1, false)) {
            var saved = cases;
            var aliases = Map.copyOf(bindings);
            var repeats = Math.clamp(repetitions.get(), 1, 100);
            var allow = allowChanged;
            var document = state.editing == null || useOriginal ? null : state.editing.getDocument();
            var original = document == null ? null : state.getSourceSnapshot();
            var preview = document == null ? null : state.queryData();
            start(cancelled -> {
                var edited = document == null ? null : GeoScenarioSource.prepare(original, preview, document);
                var result = GeoScenarioSuiteRunner.run(
                        saved,
                        (scenario, stopped) -> {
                            var path = aliases.get(scenario.sourceAlias());
                            if (path == null) {
                                return null;
                            }
                            var source = edited != null && Objects.equals(path, original.getSourcePath())
                                    ? edited
                                    : GeoScenarioSource.loadOriginal(path, scenario, stopped);
                            var graph = GeodataScenarioPanel.openGraph(
                                    path, scenario.profile().kind(), stopped);
                            try {
                                var fingerprints = GeoScenarioFingerprints.capture(
                                        source.fingerprints(),
                                        graph == null ? "" : graph.computeContentFingerprint(stopped),
                                        source.editedPreview());
                                return new GeoScenarioSuiteRunner.Source(
                                        new Context(source.data(), graph, fingerprints, source.editedPreview()), graph);
                            } catch (Exception error) {
                                if (graph != null) {
                                    graph.close();
                                }
                                throw error;
                            }
                        },
                        repeats,
                        allow,
                        cancelled);
                return new Completion(null, result, "Suite finished: " + result.counts());
            });
        }
        ImGui.endDisabled();
        if (report != null) {
            GeodataWidgets.hint("Suite results: " + report.counts());
            for (var item : report.cases()) {
                GeodataWidgets.hint(
                        item.scenario().name() + ": " + item.result().verdict());
            }
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (GeodataWidgets.pathField(
                "suite-report", "Suite report JSON file", reportFile, "Where the portable suite report is saved.")) {
            reportPicker.openPath(reportFile.get());
        }
        var reportPath = reportPicker.draw();
        if (reportPath != null) {
            reportFile.set(reportPath.toString());
        }
        if (ImGui.checkbox("Replace existing exports", replaceExisting)) {
            replaceExisting = !replaceExisting;
        }
        ImGui.beginDisabled(
                busy || capture != null || report == null || reportFile.get().isBlank());
        if (GeodataWidgets.actionButton("suite-export-suite-report", null, "Export suite report", -1, false)) {
            var saved = report;
            var path = reportFile.get();
            var replace = replaceExisting;
            start(cancelled -> {
                GeoScenarioSuiteReport.write(Path.of(path), saved, replace);
                return new Completion(null, null, "Portable suite report saved.", Path.of(path));
            });
        }
        ImGui.endDisabled();
        if (!cases.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataFields.choice(
                    "Screenshot case",
                    selected,
                    cases.stream().map(GeoScenario::name).toArray(String[]::new));
            if (GeodataWidgets.pathField(
                    "suite-png",
                    "Annotated scene PNG file",
                    screenshotFile,
                    "Where the image of the case's camera is saved.")) {
                screenshotPicker.openPath(screenshotFile.get());
            }
            var png = screenshotPicker.draw();
            if (png != null) {
                screenshotFile.set(png.toString());
            }
            GeodataWidgets.hint(
                    "Capture restores the selected case camera. Hold the view still until the export completes. The"
                        + " image holds the world, the geodata and the A/B labels; the panels and trace lines are not"
                        + " in it.");
            ImGui.beginDisabled(busy
                    || capture != null
                    || screenshotFile.get().isBlank()
                    || state.queryData() == null
                    || state.getComparisonDisplay() != null);
            if (GeodataWidgets.actionButton(
                    "suite-export-annotated-scene-png", null, "Export annotated scene PNG", -1, false)) {
                beginCapture(state, camera, backend);
            }
            ImGui.endDisabled();
        }
        if (busy && GeodataWidgets.actionButton("suite-cancel", null, "Cancel", -1, false)) {
            cancelRequested = true;
            message = "Cancellation requested; completed case results will be retained.";
            messageFile = null;
        }
        if (capture != null
                && GeodataWidgets.actionButton("suite-cancel-capture", null, "Cancel scene capture", -1, false)) {
            cancelCapture("Scene capture cancelled.");
        }
        GeodataWidgets.hint(message);
        if (messageFile != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.showFolder("suite-written", messageFile);
        }
    }

    public void tick(GeodataState state, Camera camera, ViewerRenderBackend backend) {
        var done = completed;
        if (done != null) {
            completed = null;
            if (done.cases() != null) {
                cases = done.cases();
                report = null;
                bindings.clear();
                selected.set(0);
            }
            if (done.report() != null) {
                report = done.report();
            }
            message = done.message();
            messageFile = done.written();
        }
        if (capture == null) {
            return;
        }
        var request = capture;
        var data = state.queryData();
        if (state.getComparisonDisplay() != null) {
            cancelCapture("Switch the displayed source back to A before capturing an A scenario.");
            return;
        }
        if (data == null
                || data.getContentRevision() != request.view().getContentRevision()
                || !data.getSourceId().equals(request.sourceId())
                || !camera.snapshot().equals(request.pose())) {
            cancelCapture("Camera or geodata revision changed during capture. Export again after the view settles.");
            return;
        }
        if (ticket != null && !backend.getStats().extents().render().equals(captureExtent)) {
            cancelCapture("Scene extent changed during capture. Export again.");
            return;
        }
        if (System.nanoTime() - request.started() > 15_000_000_000L) {
            cancelCapture("Scene readback timed out.");
            return;
        }
        if (ticket == null) {
            if (backend.getStats().frameNumber() <= request.frame() || state.loading) {
                return;
            }
            var extent = backend.getStats().extents().render();
            if (extent.isZero() || extent.pixels() > GeoScenarioScreenshot.MAX_PIXELS) {
                cancelCapture("Scene extent is unavailable or exceeds the 16-million-pixel export limit.");
                return;
            }
            ticket = backend.requestReadback(
                    new ReadbackRequest(ReadbackRequest.Source.WORLD_COLOR, extent, "geodata-case"));
            captureExtent = extent;
            captureMarkers = createMarkers(camera, request.scenario(), extent);
            captureBottomLeft = backend.isReadbackOriginBottomLeft();
        }
        switch (ticket.getState()) {
            case READY -> {
                var pixels = ticket.getData();
                if (pixels == null) {
                    return;
                }
                var extent = captureExtent;
                var labels = captureMarkers;
                var bottomLeft = captureBottomLeft;
                capture = null;
                ticket = null;
                start(cancelled -> {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Scene export cancelled");
                    }
                    var source = GeoScenarioSource.prepare(request.original(), request.view(), request.document());
                    var actual = GeoScenarioFingerprints.capture(source.fingerprints(), "", source.editedPreview());
                    GeoScenarioScreenshot.write(
                            request.path(),
                            extent.width(),
                            extent.height(),
                            pixels,
                            bottomLeft,
                            labels,
                            createNotes(request, actual),
                            request.replace());
                    return new Completion(null, null, "Annotated scene PNG saved locally.", request.path());
                });
            }
            case FAILED -> cancelCapture("Scene readback failed: " + ticket.getFailure());
            case CANCELLED -> cancelCapture("Scene readback cancelled.");
            case QUEUED, SUBMITTED -> {}
        }
    }

    @Override
    public void close() {
        generation++;
        cancelRequested = true;
        if (pending != null) {
            pending.cancel(true);
        }
        cancelCapture("Closed");
        worker.shutdownNow();
    }

    private void beginCapture(GeodataState state, Camera camera, ViewerRenderBackend backend) {
        try {
            if (state.getComparisonDisplay() != null) {
                message = "Switch the displayed source back to A before capturing an A scenario.";
                messageFile = null;
                return;
            }
            var scenario = cases.get(Math.clamp(selected.get(), 0, cases.size() - 1));
            var path = Path.of(screenshotFile.get());
            GeodataScenarioPanel.restoreCamera(camera, scenario.camera());
            backend.onCameraRestored();
            var data = state.queryData();
            var verdict = report == null
                    ? "not run"
                    : report.cases().stream()
                            .filter(item -> item.scenario().id().equals(scenario.id()))
                            .map(item -> item.result().verdict().name())
                            .findFirst()
                            .orElse("not run");
            capture = new Capture(
                    scenario,
                    path,
                    replaceExisting,
                    camera.snapshot(),
                    data.getSourceId(),
                    data.revision(),
                    backend.getStats().frameNumber(),
                    System.nanoTime(),
                    verdict,
                    state.getSourceSnapshot(),
                    data,
                    state.editing == null ? null : state.editing.getDocument());
            message = "Waiting for the restored camera frame and GPU readback.";
            messageFile = null;
        } catch (RuntimeException error) {
            message = error.getMessage();
            messageFile = null;
        }
    }

    private void cancelCapture(String text) {
        if (ticket != null) {
            ticket.cancel();
        }
        ticket = null;
        capture = null;
        message = text;
        messageFile = null;
    }

    private record Completion(
            List<GeoScenario> cases,
            Report report,
            String message,
            @Nullable Path written) {
        Completion(List<GeoScenario> cases, Report report, String message) {
            this(cases, report, message, null);
        }
    }

    private record Capture(
            GeoScenario scenario,
            Path path,
            boolean replace,
            Snapshot pose,
            String sourceId,
            long revision,
            long frame,
            long started,
            String verdict,
            GeoDataSnapshot original,
            GeoDataAccess view,
            GeoEditDocument document) {}

    @FunctionalInterface
    private interface Operation {
        Completion run(BooleanSupplier cancelled) throws Exception;
    }

    private void start(Operation operation) {
        var request = ++generation;
        cancelRequested = false;
        busy = true;
        pending = worker.submit(() -> {
            try {
                var result = operation.run(() -> request != generation
                        || cancelRequested
                        || Thread.currentThread().isInterrupted());
                if (request == generation) {
                    completed = result;
                }
            } catch (Exception error) {
                if (request == generation) {
                    completed = new Completion(
                            null,
                            null,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                }
            } finally {
                if (request == generation) {
                    busy = false;
                }
            }
        });
    }

    private static List<Marker> createMarkers(Camera camera, GeoScenario scenario, Extent extent) {
        var markers = new ArrayList<Marker>();
        addMarker(markers, camera, scenario.a().point(), "A", 0x72e98d, extent);
        if (scenario.b() != null) {
            addMarker(markers, camera, scenario.b().point(), "B", 0xffc568, extent);
        }
        return List.copyOf(markers);
    }

    private static void addMarker(
            List<Marker> markers, Camera camera, GeoPoint point, String label, int color, Extent extent) {
        var pixel = camera.projectToScreenRaw(
                new Vec3((float) point.x(), (float) point.y(), (float) point.z()), extent.width(), extent.height());
        if (pixel != null) {
            markers.add(new Marker(label, pixel.getX(), pixel.getY(), color));
        }
    }

    private static List<String> createNotes(Capture capture, Fingerprints actual) {
        var scenario = capture.scenario();
        return List.of(
                scenario.name() + " | " + scenario.profile().kind() + " | last suite verdict: " + capture.verdict(),
                "A "
                        + scenario.a().point()
                        + (scenario.b() == null ? "" : " | B " + scenario.b().point()),
                "Saved data: "
                        + scenario.fingerprints().data()
                        + (scenario.fingerprints().editedPreview() ? " (edited preview)" : " (original)"),
                "Captured data: " + actual.data() + (actual.editedPreview() ? " (edited preview)" : " (original)"),
                "Camera: "
                        + capture.pose().x()
                        + ", "
                        + capture.pose().y()
                        + ", "
                        + capture.pose().z()
                        + " | "
                        + (capture.pose().ortho() ? "orthographic" : "perspective")
                        + " | captured data revision "
                        + capture.revision(),
                "WORLD_COLOR + A/B annotations; ImGui trace/inspection overlays absent. Visibility queries cover"
                        + " geodata only.");
    }
}

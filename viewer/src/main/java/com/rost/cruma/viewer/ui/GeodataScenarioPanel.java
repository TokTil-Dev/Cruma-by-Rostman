package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.viewer.geodata.GeoInspection.Selection;
import com.rost.cruma.viewer.geodata.GeoScenario;
import com.rost.cruma.viewer.geodata.GeoScenario.Endpoint;
import com.rost.cruma.viewer.geodata.GeoScenario.Kind;
import com.rost.cruma.viewer.geodata.GeoScenario.Layer;
import com.rost.cruma.viewer.geodata.GeoScenario.Profile;
import com.rost.cruma.viewer.geodata.GeoScenarioFingerprints;
import com.rost.cruma.viewer.geodata.GeoScenarioReport;
import com.rost.cruma.viewer.geodata.GeoScenarioRunner;
import com.rost.cruma.viewer.geodata.GeoScenarioRunner.Context;
import com.rost.cruma.viewer.geodata.GeoScenarioRunner.Result;
import com.rost.cruma.viewer.geodata.GeoScenarioSource;
import com.rost.cruma.viewer.geodata.GeoScenarioStore;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.GeodataState;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class GeodataScenarioPanel implements AutoCloseable {
    private final ImString name = new ImString("Geodata case", 256);
    private final ImString alias = new ImString("source-A", 128);
    private final ImString file = new ImString(4096);
    private final ImString reportFile = new ImString(4096);
    private final FolderPicker filePicker = FolderPicker.file(
            "Choose the case file", "Pick the folder of the case file, and its name below.", "geodata-scenario.json");
    private final FolderPicker reportPicker = FolderPicker.file(
            "Choose the report file",
            "Pick the folder for the report, and its name below.",
            "geodata-scenario-report.json");
    private final ImInt kind = new ImInt();
    private final ImInt radius = new ImInt(1024);
    private final ImInt repetitions = new ImInt(3);
    private boolean replaceExisting;
    private boolean allowChanged;
    private boolean replayOriginal;
    private boolean bound;
    private Path boundPath;
    private GeoScenario scenario;
    private Result result;

    private @Nullable Path messageFile;

    private String message =
            "Capture a case to preserve its camera, selected layers, query profile and expected result.";
    private volatile boolean busy;
    private volatile long generation;
    private volatile Completion completed;
    private Future<?> pending;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "geodata-scenarios");
        thread.setDaemon(true);
        return thread;
    });

    public static GeoScenario.Camera capturePose(Camera camera) {
        var position = camera.getWorldFocusPosition();
        var direction = camera.getPerspectiveLookDir();
        return new GeoScenario.Camera(
                new GeoPoint(position.getX(), position.getY(), position.getZ()),
                (Math.toDegrees(Math.atan2(direction.getY(), direction.getX())) + 360) % 360,
                Math.toDegrees(Math.atan2(direction.getZ(), Math.hypot(direction.getX(), direction.getY()))),
                camera.snapshot());
    }

    public static void restoreCamera(Camera camera, GeoScenario.Camera pose) {
        if (pose.state() != null) {
            camera.restoreSnapshot(pose.state());
            return;
        }
        if (camera.isOrtho()) {
            camera.setOrthoProjection(false);
        }
        camera.setPosition((float) pose.position().x(), (float) pose.position().y(), (float)
                pose.position().z());
        camera.setDirection(pose.yaw(), pose.pitch());
    }

    static PathNodeGraph openGraph(Path path, Kind kind, BooleanSupplier cancelled) throws IOException {
        if (kind != Kind.PATH || path == null || !Files.isRegularFile(path.resolve("pathnode.idx"))) {
            return null;
        }
        return PathNodeGraph.open(path, cancelled);
    }

    public void draw(GeodataState state, Camera camera) {
        draw(state, camera, null);
    }

    public void draw(GeodataState state, Camera camera, ViewerRenderBackend backend) {
        publish();
        GeodataWidgets.textField("Case name", name);
        GeodataWidgets.textField("Portable source alias", alias);
        GeodataWidgets.tip(
                "A name for the source folder that travels with the case instead of its path. Bind it to a folder on"
                        + " each computer.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Query");
        var query = GeodataWidgets.segmented(
                "scenario-kind", new String[] {"Straight", "Sight", "Path", "Reach"}, kind.get());
        if (query >= 0) {
            kind.set(query);
        }
        GeodataWidgets.tip(
                "Straight: ground movement. Sight: geodata visibility. Path: the server's path replay. Reach: directed"
                        + " reachability.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.integer("Radius", radius, 16);
        GeodataWidgets.integer("Repeat", repetitions, 1);
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (GeodataWidgets.pathField(
                "scenario-file", "Case JSON file", file, "Where the case is saved, or the case to load.")) {
            filePicker.openPath(file.get());
        }
        var picked = filePicker.draw();
        if (picked != null) {
            file.set(picked.toString());
        }
        if (ImGui.checkbox("Replace an existing file on save", replaceExisting)) {
            replaceExisting = !replaceExisting;
        }
        var inspection = state.inspection;
        var ready = state.queryData() != null
                && inspection.pointA != null
                && (kind.get() == 3 || inspection.pointB != null);
        ImGui.beginDisabled(busy || !ready);
        if (GeodataWidgets.actionButton("scenario-capture", Glyph.CAMERA, "Capture current case", -1, true)) {
            var snapshot = state.getSourceSnapshot();
            var frozenView = state.queryData();
            var document = state.editing == null ? null : state.editing.getDocument();
            var a = toEndpoint(inspection.pointA);
            var b = toEndpoint(inspection.pointB);
            var pose = capturePose(camera);
            var label = name.get();
            var sourceAlias = alias.get();
            var selectedKind = Kind.values()[kind.get()];
            var selectedRadius = Math.max(16, Math.min(32768, radius.get()));
            start(cancelled -> {
                var source = GeoScenarioSource.prepare(snapshot, frozenView, document);
                var profile = Profile.defaults(selectedKind);
                profile = new Profile(
                        profile.kind(),
                        profile.movementMode(),
                        profile.distance(),
                        profile.maxSteps(),
                        profile.maxTrace(),
                        selectedRadius,
                        profile.maxNodes(),
                        profile.maxPathVisits(),
                        profile.maxWaypoints());
                try (var graph = openGraph(snapshot.getSourcePath(), profile.kind(), cancelled)) {
                    var fingerprints = GeoScenarioFingerprints.capture(
                            source.fingerprints(),
                            graph == null ? "" : graph.computeContentFingerprint(cancelled),
                            source.editedPreview());
                    var context = new Context(source.data(), graph, fingerprints, source.editedPreview());
                    var observation = GeoScenarioRunner.observe(context, a, b, profile, cancelled);
                    var captured = new GeoScenario(
                            GeoScenario.VERSION,
                            UUID.randomUUID().toString(),
                            label,
                            sourceAlias,
                            fingerprints,
                            pose,
                            a,
                            b,
                            profile,
                            observation.toExpectation(1e-9));
                    return new Completion(
                            captured,
                            null,
                            (source.editedPreview() ? "Edited preview captured. " : "Original data captured. ")
                                    + (observation.complete()
                                            ? "Ready to save."
                                            : "Result is incomplete and cannot pass regression replay."),
                            true);
                }
            });
        }
        ImGui.endDisabled();
        ImGui.beginDisabled(busy || file.get().isBlank());
        if (GeodataWidgets.actionButton("scenario-load-case", null, "Load case", -1, false)) {
            var target = file.get();
            start(cancelled -> new Completion(
                    GeoScenarioStore.read(Path.of(target)),
                    null,
                    "Loaded. Bind the saved source alias to current source A before replay.",
                    false));
        }
        ImGui.beginDisabled(scenario == null);
        if (GeodataWidgets.actionButton("scenario-save-case", null, "Save case", -1, false)) {
            var saved = scenario;
            var target = file.get();
            var replace = replaceExisting;
            start(cancelled -> {
                GeoScenarioStore.write(Path.of(target), saved, replace);
                return new Completion(null, null, "Case saved.", bound, Path.of(target));
            });
        }
        ImGui.endDisabled();
        ImGui.endDisabled();
        if (scenario != null) {
            GeodataWidgets.hint("Loaded case: " + scenario.name() + " · "
                    + scenario.profile().kind());
            GeodataWidgets.hint("Source alias: "
                    + scenario.sourceAlias()
                    + " · "
                    + scenario.fingerprints().regions().size()
                    + " required regions");
            GeodataWidgets.hint("Algorithm: " + scenario.fingerprints().algorithm());
            GeodataWidgets.hint(
                    scenario.fingerprints().editedPreview()
                            ? "Captured from edited preview; the original path graph is stale."
                            : "Captured from original geodata.");
            ImGui.beginDisabled(busy || state.path == null);
            if (GeodataWidgets.actionButton(
                    "scenario-bind-saved-alias-to-current-source-a",
                    null,
                    "Bind saved alias to current source A",
                    -1,
                    false)) {
                boundPath = state.path.toAbsolutePath().normalize();
                bound = true;
                message = "Alias bound locally. Fingerprints will be checked during replay.";
                messageFile = null;
            }
            ImGui.endDisabled();
            GeodataWidgets.hint(bound ? "Local mapping: " + boundPath : "Alias is not bound on this device.");
            var currentPreview = state.editing != null
                    && Objects.equals(boundPath, state.getSourceSnapshot().getSourcePath());
            if (currentPreview && ImGui.checkbox("Replay original source files", replayOriginal)) {
                replayOriginal = !replayOriginal;
            }
            GeodataWidgets.hint(
                    currentPreview && !replayOriginal
                            ? "Replay uses the current frozen edit preview; changed path graphs are unavailable."
                            : "Replay reads original source files from the local mapping.");
            if (ImGui.checkbox("Compare against changed data", allowChanged)) {
                allowChanged = !allowChanged;
            }
            GeodataWidgets.hint(
                    "Changed-data comparison keeps the saved expectation and reports whether source content changed.");
            ImGui.beginDisabled(busy || !bound);
            if (GeodataWidgets.actionButton("scenario-replay-saved-case", null, "Replay saved case", -1, false)) {
                var saved = scenario;
                var sourcePath = boundPath;
                var repeats = Math.max(1, Math.min(100, repetitions.get()));
                var allow = allowChanged;
                var previewView = currentPreview && !replayOriginal ? state.queryData() : null;
                var original = previewView == null ? null : state.getSourceSnapshot();
                var document = previewView == null ? null : state.editing.getDocument();
                start(cancelled -> {
                    var source = previewView == null
                            ? GeoScenarioSource.loadOriginal(sourcePath, saved, cancelled)
                            : GeoScenarioSource.prepare(original, previewView, document);
                    try (var graph = openGraph(sourcePath, saved.profile().kind(), cancelled)) {
                        var context = new Context(
                                source.data(),
                                graph,
                                GeoScenarioFingerprints.capture(
                                        source.fingerprints(),
                                        graph == null ? "" : graph.computeContentFingerprint(cancelled),
                                        source.editedPreview()),
                                source.editedPreview());
                        var result = new GeoScenarioRunner(
                                        localAlias -> Objects.equals(localAlias, saved.sourceAlias()) ? context : null)
                                .run(saved, repeats, allow, cancelled);
                        return new Completion(null, result, result.verdict() + ": " + result.detail(), true);
                    }
                });
            }
            ImGui.endDisabled();
            if (GeodataWidgets.actionButton("scenario-restore-saved-camera", null, "Restore saved camera", -1, false)) {
                restoreCamera(camera, scenario.camera());
                if (backend != null) {
                    backend.onCameraRestored();
                }
            }
        }
        if (result != null) {
            GeodataWidgets.hint(result.verdict()
                    + " · "
                    + result.runs().size()
                    + " runs"
                    + (result.dataChanged() ? " · source changed" : ""));
            for (var run : result.runs()) {
                GeodataWidgets.hint(run.status() + " · " + (run.complete() ? "complete" : "incomplete"));
            }
            if (!result.runs().isEmpty()) {
                var run = result.runs().getFirst();
                if (scenario.a().selectedLayer() != null
                        && !scenario.a().selectedLayer().equals(run.resolvedA())) {
                    GeodataWidgets.hint(
                            "A: selected layer differs from the server's Z + 26 base layer: " + run.resolvedA());
                }
                if (scenario.b() != null
                        && scenario.b().selectedLayer() != null
                        && !scenario.b().selectedLayer().equals(run.resolvedB())) {
                    GeodataWidgets.hint(
                            "B: selected layer differs from the server's Z + 26 base layer: " + run.resolvedB());
                }
            }
            if (GeodataWidgets.pathField(
                    "scenario-report",
                    "Report JSON file",
                    reportFile,
                    "Where the case report is saved, without local source paths.")) {
                reportPicker.openPath(reportFile.get());
            }
            var report = reportPicker.draw();
            if (report != null) {
                reportFile.set(report.toString());
            }
            ImGui.beginDisabled(busy || reportFile.get().isBlank());
            if (GeodataWidgets.actionButton("scenario-export-case-report", null, "Export case report", -1, false)) {
                var saved = scenario;
                var savedResult = result;
                var target = reportFile.get();
                var replace = replaceExisting;
                start(cancelled -> {
                    GeoScenarioReport.write(Path.of(target), saved, savedResult, replace);
                    return new Completion(
                            null, null, "Report saved without local source paths.", bound, Path.of(target));
                });
            }
            ImGui.endDisabled();
        }
        if (busy && GeodataWidgets.actionButton("scenario-cancel", null, "Cancel", -1, false)) {
            cancel();
        }
        GeodataWidgets.hint(message);
        if (messageFile != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.showFolder("scenario-written", messageFile);
        }
    }

    @Override
    public void close() {
        cancel();
        worker.shutdownNow();
    }

    @FunctionalInterface
    private interface Operation {
        Completion run(BooleanSupplier cancelled) throws Exception;
    }

    private record Completion(
            GeoScenario scenario,
            Result result,
            String message,
            boolean bound,
            @Nullable Path written) {
        Completion(GeoScenario scenario, Result result, String message, boolean bound) {
            this(scenario, result, message, bound, null);
        }
    }

    private void start(Operation operation) {
        cancel();
        busy = true;
        var request = generation;
        pending = worker.submit(() -> {
            BooleanSupplier cancelled =
                    () -> generation != request || Thread.currentThread().isInterrupted();
            try {
                var completion = operation.run(cancelled);
                if (!cancelled.getAsBoolean()) {
                    completed = completion;
                }
            } catch (Exception error) {
                if (!cancelled.getAsBoolean()) {
                    completed = new Completion(
                            null,
                            null,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                            bound);
                }
            } finally {
                if (generation == request) {
                    busy = false;
                }
            }
        });
    }

    private void cancel() {
        generation++;
        if (pending != null) {
            pending.cancel(true);
        }
        pending = null;
        completed = null;
        busy = false;
    }

    private void publish() {
        var completion = completed;
        if (completion == null) {
            return;
        }
        completed = null;
        if (completion.scenario() != null) {
            scenario = completion.scenario();
            result = null;
            bound = false;
            boundPath = null;
        }
        if (completion.result() != null) {
            result = completion.result();
        }
        message = completion.message();
        messageFile = completion.written();
    }

    private static Endpoint toEndpoint(Selection selection) {
        return selection == null
                ? null
                : new Endpoint(
                        selection.center(),
                        new Layer(
                                selection.ref().x(),
                                selection.ref().y(),
                                selection.ref().layer(),
                                selection.height()));
    }
}

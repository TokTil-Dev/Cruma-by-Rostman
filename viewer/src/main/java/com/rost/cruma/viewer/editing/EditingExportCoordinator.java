package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.NativePackageChanges;
import com.rost.cruma.core.editing.TerrainActorShadows;
import com.rost.cruma.core.editing.TerrainActorShadows.Owner;
import com.rost.cruma.core.editing.TerrainDecorationExport;
import com.rost.cruma.core.editing.TerrainEditExport.Report;
import com.rost.cruma.viewer.editing.EditingSession.ExportSnapshot;
import com.rost.cruma.viewer.editing.PackageExportService.Plan;
import com.rost.cruma.viewer.editing.TerrainEditingController.ExportInput;
import com.rost.cruma.viewer.editing.TerrainEditingController.PreparedChanges;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class EditingExportCoordinator implements AutoCloseable {
    private final EditingSession session;
    private final Thread ownerThread = Thread.currentThread();
    private TerrainEditingController terrain;
    private BooleanSupplier terrainBusy = () -> false;
    private Plan plan;
    private PreparedChanges nativeChanges;
    private long terrainRevision;
    private long observedTerrainRevision = -1;
    private boolean terrainExportable;
    private ExecutorService worker;
    private CompletableFuture<Completion> pending;
    private AtomicBoolean cancellation;
    private Request pendingRequest;
    private boolean saving;
    private boolean published;
    private Path pendingOutput, lastOutput;
    private volatile boolean closed;
    private final AtomicReference<CompletableFuture<Void>> publicationApproval = new AtomicReference<>();
    private String status = "", error = "";
    private volatile String progress = "";

    public EditingExportCoordinator(EditingSession session) {
        this.session = session;
    }

    private record Request(ExportSnapshot actors, ExportInput terrain, long terrainRevision) {}

    private record Prepared(Plan plan, PreparedChanges terrain) {}

    static String savedText(List<Path> saved, Path folder) {
        var maps = (int) saved.stream()
                .filter(path -> path.getFileName() != null
                        && EditingPanel.isMap(path.getFileName().toString()))
                .count();
        var others = saved.size() - maps;
        String what;
        if (maps == 0) {
            what = EditorUi.plural(others, "package", "packages");
        } else if (others == 0) {
            what = EditorUi.plural(maps, "map", "maps");
        } else {
            what = EditorUi.plural(maps, "map", "maps") + " and " + EditorUi.plural(others, "package", "packages");
        }
        var name = folder == null || folder.getFileName() == null ? "" : " to " + folder.getFileName();
        return "Saved " + what + name + ".";
    }

    public Report getTerrainReport() {
        return nativeChanges == null ? null : nativeChanges.getReport();
    }

    public List<TerrainDecorationExport.Edit> getTerrainDecorations() {
        return nativeChanges == null ? List.of() : nativeChanges.getDecorations();
    }

    public List<Report> getTerrainReports() {
        return nativeChanges == null ? List.of() : nativeChanges.getReports();
    }

    private record Completion(Prepared prepared, List<Path> saved) {}

    public void setTerrain(TerrainViewportSession viewport) {
        if (viewport == null && terrain != null) {
            configureTerrain(null, () -> false);
        } else if (viewport != null && terrain != viewport.getController()) {
            configureTerrain(viewport.getController(), viewport::isBusy);
        }
    }

    void configureTerrain(TerrainEditingController controller, BooleanSupplier busy) {
        requireOwner();
        if (pending != null) {
            throw new IllegalStateException("Cancel export before replacing its Terrain session");
        }
        terrain = controller;
        terrainExportable = false;
        terrainBusy = busy;
        observedTerrainRevision = -1;
        plan = null;
    }

    public boolean isBusy() {
        return pending != null;
    }

    public boolean isSaving() {
        return pending != null && saving;
    }

    public boolean hasPublished() {
        return published;
    }

    public String getStatus() {
        var live = progress;
        return pending != null && !live.isEmpty() ? live : status;
    }

    public String getError() {
        return error;
    }

    public Plan getPlan() {
        return plan;
    }

    public Path getLastOutput() {
        return lastOutput;
    }

    public boolean hasExportableChanges() {
        if (terrain != null
                && !terrainBusy.getAsBoolean()
                && !terrain.isRecoveryGestureActive()
                && observedTerrainRevision != terrain.getRecoveryRevision()) {
            observedTerrainRevision = terrain.getRecoveryRevision();
            terrainExportable = terrain.isOpen() && (terrain.isDirty() || terrain.hasSourceChanges());
        }
        return session.hasExportableChanges() || terrainExportable;
    }

    public Plan prepare() throws IOException {
        var request = capture();
        plan = null;
        nativeChanges = null;
        adopt(request, build(request, () -> false));
        return plan;
    }

    public void beginPrepare() throws IOException {
        var request = capture();
        plan = null;
        nativeChanges = null;
        start(request, false, () -> new Completion(build(request, cancellation::get), List.of()));
    }

    public boolean isCurrent() {
        return plan != null
                && session.isPreparedExportCurrent(plan)
                && !terrainBusy.getAsBoolean()
                && (terrain == null
                        || !terrain.isRecoveryGestureActive()
                                && terrain.getRecoveryRevision() == terrainRevision
                                && (nativeChanges == null || terrain.isPreparedCurrent(nativeChanges)));
    }

    public void beginSave(Path output) throws IOException {
        requireIdle();
        if (!isCurrent()) {
            throw new IOException("Actor or Terrain state changed. Validate the combined export again.");
        }
        var request = new Request(
                session.captureExport(), nativeChanges == null ? null : nativeChanges.getInput(), terrainRevision);
        var frozenPlan = plan;
        var frozenNative = nativeChanges;
        pendingOutput = output;
        start(
                request,
                true,
                () -> new Completion(
                        new Prepared(frozenPlan, frozenNative),
                        session.publishCapturedExport(frozenPlan, output, cancellation::get, () -> {
                            approvePublication();
                            if (frozenNative != null) {
                                frozenNative.verifySourcesUnchanged();
                            }
                        })));
    }

    @FunctionalInterface
    private interface Work {
        Completion run() throws IOException;
    }

    public void poll() {
        requireOwner();
        var approval = publicationApproval.getAndSet(null);
        if (approval != null) {
            if (!closed && !cancellation.get() && matches(pendingRequest) && isCurrent()) {
                approval.complete(null);
            } else {
                approval.completeExceptionally(
                        new IOException("Export cancelled or editing state changed before publication"));
            }
        }
        if (pending == null || !pending.isDone()) {
            return;
        }
        var completed = pending;
        pending = null;
        unlock();
        try {
            var result = completed.join();
            if (closed) {
                return;
            }
            if (saving) {
                if (!matches(pendingRequest) || !isCurrent()) {
                    throw new IOException(
                            "Export was published, but editing state changed. Both Save checkpoints remain unchanged.");
                }
                markSaved(result.saved());
                lastOutput = pendingOutput;
                published = true;
                status = savedText(result.saved(), lastOutput);
            } else if (cancellation.get()) {
                status = "Check cancelled.";
            } else {
                adopt(pendingRequest, result.prepared());
                var errors = plan.getErrors().size();
                status = errors == 0
                        ? "Check passed."
                        : "Check found " + errors + (errors == 1 ? " problem." : " problems.");
            }
        } catch (Exception failure) {
            var cause =
                    failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            error = cancellation.get() ? "" : UserMessages.plain(cause, UserMessages.WRITE_FAILED);
            if (cancellation.get()) {
                status = saving ? "Save cancelled. Nothing was saved." : "Check cancelled.";
            } else {
                status = saving
                        ? "Save failed. Nothing was saved, and your changes are kept."
                        : "Check failed. Your changes are kept.";
            }
            plan = null;
            nativeChanges = null;
        } finally {
            pendingRequest = null;
            pendingOutput = null;
        }
    }

    public void cancel() {
        requireOwner();
        if (cancellation != null && pending != null) {
            cancellation.set(true);
        }
        var approval = publicationApproval.getAndSet(null);
        if (approval != null) {
            approval.completeExceptionally(new IOException("Export cancelled"));
        }
    }

    public List<Path> saveAs(Path output) throws IOException {
        return saveAs(output, () -> false);
    }

    public List<Path> saveAs(Path output, BooleanSupplier cancelled) throws IOException {
        requireIdle();
        if (!isCurrent()) {
            throw new IOException("Actor or Terrain state changed. Validate the combined export again.");
        }
        var saved = session.publishCapturedExport(plan, output, () -> cancelled.getAsBoolean() || !isCurrent(), () -> {
            if (nativeChanges != null) {
                nativeChanges.verifySourcesUnchanged();
            }
        });
        if (!isCurrent()) {
            throw new IOException(
                    "Export was published, but the editing state changed. Both Save checkpoints remain unchanged.");
        }
        markSaved(saved);
        return saved;
    }

    @Override
    public void close() {
        requireOwner();
        closed = true;
        cancel();
        if (worker != null) {
            worker.shutdownNow();
        }
        unlock();
        plan = null;
        nativeChanges = null;
    }

    private Request capture() throws IOException {
        requireIdle();
        session.finishGesture();
        hasExportableChanges();
        var input = terrain != null && terrainExportable ? terrain.captureExport() : null;
        var actors = session.captureExport();
        return new Request(actors, input, terrain == null ? 0 : terrain.getRecoveryRevision());
    }

    private Prepared build(Request request, BooleanSupplier cancelled) throws IOException {
        var shadows = new HashMap<Path, TerrainActorShadows>();
        var nativeInput = request.terrain() == null
                ? null
                : request.terrain()
                        .prepare(
                                map -> {
                                    var actors = session.getTerrainActorShadows(request.actors(), map);
                                    shadows.put(map.toAbsolutePath().normalize(), actors);
                                    return actors;
                                },
                                cancelled);
        if (cancelled.getAsBoolean()) {
            throw new InterruptedIOException("Export cancelled");
        }
        var changes = nativeInput == null ? List.<NativePackageChanges>of() : nativeInput.getChanges();
        var result = session.prepareCapturedExport(
                request.actors(),
                changes,
                List.of(),
                List.of(),
                nativeInput == null ? List.of() : nativeInput.getReplaced(),
                nativeInput == null ? Map.of() : nativeInput.getBspChanges(),
                nativeInput == null ? Map.of() : nativeInput.getOwners(),
                cancelled,
                (map, done, total) ->
                        progress = "Rebuilding lighting in " + map + ": " + done + " of " + total + " actors...",
                (map, done, total) -> progress =
                        "Rebuilding BSP lightmaps in " + map + ": " + done + " of " + total + " lightmaps...",
                (map, done, total) -> progress = "Rebuilding Terrain lighting in "
                        + map
                        + ": "
                        + done
                        + " of "
                        + total
                        + (total == 1 ? " Terrain owner..." : " Terrain owners..."));
        progress = "";
        if (nativeInput != null) {
            nativeInput.verifySourcesUnchanged();
            var reports = nativeInput.getReports();
            var maps = nativeInput.getMaps();
            var vertices = nativeInput.getActorShadowVertices();
            var entries = new ArrayList<Map<String, Object>>();
            var lines = new ArrayList<>(result.getLighting());
            var tips = new ArrayList<>(result.getLightingTips());
            for (var index = 0; index < reports.size(); index++) {
                var report = reports.get(index);
                entries.add(TerrainReports.manifestEntry(report, vertices.get(index)));
                var actors = shadows.getOrDefault(maps.get(index), TerrainActorShadows.NONE);
                var line = EditingSession.terrainLine(new TerrainActorShadows.Report(
                        mapName(maps.get(index)),
                        actors.isFullRebuild(),
                        vertices.get(index) == 0
                                ? List.of()
                                : List.of(new Owner(report.owner(), vertices.get(index), 0, 0, 0, 0)),
                        List.of()));
                if (line != null) {
                    lines.add(line);
                    tips.add("");
                }
            }
            TerrainReports.addDecorations(entries, nativeInput.getDecorations());
            result = result.withTerrainReports(entries);
            if (lines.size() != result.getLighting().size()) {
                result = result.withLighting(lines, tips);
            }
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedIOException("Export cancelled");
        }
        return new Prepared(result, nativeInput);
    }

    private boolean matches(Request request) {
        return session.getRevision() == request.actors().revision()
                && !terrainBusy.getAsBoolean()
                && (terrain == null
                        ? request.terrainRevision() == 0
                        : !terrain.isRecoveryGestureActive()
                                && terrain.getRecoveryRevision() == request.terrainRevision()
                                && (request.terrain() == null || terrain.isCapturedCurrent(request.terrain())));
    }

    private void adopt(Request request, Prepared prepared) throws IOException {
        if (!matches(request)) {
            throw new IOException("Actor or Terrain changed while export was being validated. Validate again.");
        }
        session.adoptPreparedExport(request.actors(), prepared.plan());
        plan = prepared.plan();
        nativeChanges = prepared.terrain();
        terrainRevision = request.terrainRevision();
    }

    private void start(Request request, boolean save, Work work) {
        cancellation = new AtomicBoolean();
        progress = "";
        pendingRequest = request;
        saving = save;
        published = false;
        error = "";
        status = save ? "Saving…" : "Checking…";
        session.exportLocked(true);
        if (terrain != null) {
            terrain.exportLocked(true);
        }
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("editor-export").factory());
        }
        pending = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return work.run();
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                },
                worker);
    }

    private void approvePublication() throws IOException {
        var approval = new CompletableFuture<Void>();
        if (!publicationApproval.compareAndSet(null, approval)) {
            throw new IOException("Export publication already awaiting approval");
        }
        if (closed || cancellation.get()) {
            publicationApproval.compareAndSet(approval, null);
            throw new InterruptedIOException("Export cancelled");
        }
        try {
            approval.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Export cancelled");
        } catch (ExecutionException failure) {
            throw new IOException(failure.getCause().getMessage(), failure.getCause());
        } finally {
            publicationApproval.compareAndSet(approval, null);
        }
    }

    private void markSaved(List<Path> saved) {
        if (!saved.isEmpty()) {
            session.markPreparedExportSaved(plan);
            if (nativeChanges != null) {
                terrain.markExported(nativeChanges);
            }
        }
        plan = null;
        nativeChanges = null;
    }

    private void unlock() {
        session.exportLocked(false);
        if (terrain != null) {
            terrain.exportLocked(false);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Unified export requires the editing owner thread");
        }
    }

    private void requireIdle() throws IOException {
        requireOwner();
        if (closed || pending != null) {
            throw new IOException("Wait for export to finish or cancel it");
        }
        if (terrainBusy.getAsBoolean() || terrain != null && terrain.isRecoveryGestureActive()) {
            throw new IOException("Finish the Terrain operation before exporting packages");
        }
    }

    private static String mapName(Path map) {
        var file = map.getFileName().toString();
        var dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }
}

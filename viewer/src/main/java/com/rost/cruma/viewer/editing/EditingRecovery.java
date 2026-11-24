package com.rost.cruma.viewer.editing;

import com.rost.cruma.viewer.editing.EditingRecoveryStore.Candidate;
import com.rost.cruma.viewer.editing.EditingRecoveryStore.UnsupportedFormat;
import com.rost.cruma.viewer.editing.TerrainRecoveryCodec.State;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class EditingRecovery implements AutoCloseable {
    private final EditingSession session;
    private final EditingRecoveryStore store;
    private final long debounceNanos;
    private final int checkpointCommands;
    private final Runnable refreshPreview;
    private ExecutorService worker;
    private Future<Candidate> pendingWrite;
    private FileChannel lockChannel;
    private FileLock lock;
    private Available available;
    private long discoveryRevision;
    private long observedRevision;
    private long queuedRevision;
    private long queuedCommands;
    private Supplier<TerrainEditingController> currentTerrain = () -> null;
    private Supplier<TerrainEditingController> createTerrain;
    private long discoveryTerrainRevision;
    private long observedTerrainRevision;
    private long queuedTerrainRevision;
    private long changeAt;
    private long retryAfter;
    private boolean closed;
    private boolean discardOnClose;
    private String status = "Recovery ready";
    private String error = "";

    public EditingRecovery(
            EditingSession session,
            Path recoveryRoot,
            long debounceMillis,
            int checkpointCommands,
            int generations,
            long maximumBytes,
            Runnable refreshPreview) {
        this(
                session,
                new EditingRecoveryStore(
                        clientDirectory(recoveryRoot, session.getClientRoot()), maximumBytes, generations),
                debounceMillis,
                checkpointCommands,
                refreshPreview);
    }

    EditingRecovery(
            EditingSession session,
            EditingRecoveryStore store,
            long debounceMillis,
            int checkpointCommands,
            Runnable refreshPreview) {
        this.session = session;
        this.store = store;
        this.debounceNanos = TimeUnit.MILLISECONDS.toNanos(debounceMillis);
        this.checkpointCommands = checkpointCommands;
        this.refreshPreview = refreshPreview;
        observedRevision = queuedRevision = session.getDraftRevision();
        queuedCommands = session.getCommittedCommands();
        try {
            Files.createDirectories(store.getDirectory());
            lockChannel = FileChannel.open(
                    store.getDirectory().resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw new IOException("Another editor is using this recovery directory.");
            }
            var candidates = store.getCandidates();
            if (!candidates.isEmpty()) {
                var candidate = candidates.getFirst();
                available = new Available(
                        candidate.savedAt(),
                        candidate.revision(),
                        candidate.maps(),
                        candidate.bytes(),
                        candidate.directory(),
                        true);
                discoveryRevision = session.getRevision();
                status = "A previous Edit session can be recovered";
            }
        } catch (UnsupportedFormat future) {
            available = new Available(Instant.now(), 0, 0, 0, store.getDirectory(), false);
            discoveryRevision = session.getRevision();
            error = failureMessage(future);
            status = "Recovery draft requires another application version";
        } catch (IOException | OverlappingFileLockException failure) {
            error = failureMessage(failure);
            status = "Recovery unavailable";
        }
    }

    public record Available(Instant savedAt, long revision, int maps, long bytes, Path directory, boolean supported) {}

    static Path clientDirectory(Path root, Path client) {
        var identity = client.toAbsolutePath().normalize().toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            identity = identity.toLowerCase(Locale.ROOT);
        }
        return root.resolve(EditingRecoveryCodec.hash(identity.getBytes(StandardCharsets.UTF_8)));
    }

    public Available getAvailable() {
        return available;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public boolean isWriting() {
        return pendingWrite != null;
    }

    public boolean hasWorker() {
        return worker != null;
    }

    public void configureTerrain(
            Supplier<TerrainEditingController> current, Supplier<TerrainEditingController> create) {
        if (createTerrain != null || worker != null) {
            throw new IllegalStateException("Configure Terrain recovery before autosave starts");
        }
        currentTerrain = Objects.requireNonNull(current);
        createTerrain = Objects.requireNonNull(create);
        discoveryTerrainRevision = terrainRevision();
    }

    public void tick(long now) {
        if (closed || lock == null) {
            return;
        }
        poll(now);
        if (available != null || discardOnClose || isTerrainGestureActive()) {
            return;
        }
        var revision = session.getDraftRevision();
        var terrainRevision = terrainRevision();
        if (revision != observedRevision || terrainRevision != observedTerrainRevision) {
            observedRevision = revision;
            observedTerrainRevision = terrainRevision;
            changeAt = now;
        }
        if ((revision == queuedRevision && terrainRevision == queuedTerrainRevision)
                || session.isGestureActive()
                || pendingWrite != null
                || now < retryAfter) {
            return;
        }
        if (now - changeAt >= debounceNanos
                || session.getCommittedCommands() - queuedCommands >= checkpointCommands
                || terrainRevision - queuedTerrainRevision >= checkpointCommands) {
            submit();
        }
    }

    public void recover() throws IOException {
        requireWritable();
        if (available == null) {
            throw new IOException("No previous session is waiting for recovery.");
        }
        if (session.getRevision() != discoveryRevision
                || session.isDirty()
                || session.isGestureActive()
                || isTerrainGestureActive()
                || terrainRevision() != discoveryTerrainRevision
                || currentTerrain.get() != null && currentTerrain.get().isDirty()) {
            throw new IOException(
                    "The current editor changed after recovery was offered. Keep or export those edits before"
                            + " restoring a previous session.");
        }
        var restored = store.restore(session.getClientRoot());
        var previous = session.getRecoveryState();
        var previousTerrain = terrainState();
        if (restored.terrain() != null && createTerrain == null) {
            throw new IOException("Terrain recovery is unavailable in this workspace.");
        }
        session.installRecovery(restored.state());
        try {
            refreshPreview.run();
            restoreTerrain(restored.terrain());
        } catch (IOException | RuntimeException failure) {
            session.installRecovery(previous);
            try {
                refreshPreview.run();
                restoreTerrain(previousTerrain);
                discoveryRevision = session.getRevision();
                discoveryTerrainRevision = terrainRevision();
            } catch (IOException | RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            throw new IOException("The recovered packages could not be previewed.", failure);
        }
        available = null;
        queuedRevision = -1;
        queuedTerrainRevision = -1;
        observedRevision = session.getDraftRevision();
        observedTerrainRevision = terrainRevision();
        changeAt = System.nanoTime();
        error = "";
        status = restored.warning().isEmpty() ? "Recovered the Edit session, with Undo and Redo" : restored.warning();
    }

    public void discardAvailable() throws IOException {
        requireWritable();
        awaitWrite();
        store.discard();
        available = null;
        queuedRevision = observedRevision = session.getDraftRevision();
        queuedTerrainRevision = observedTerrainRevision = terrainRevision();
        status = "Previous recovery discarded";
        error = "";
    }

    public void discardAndClose() throws IOException {
        discardAvailable();
        discardOnClose = true;
    }

    public void flush() throws IOException {
        requireWritable();
        if (available != null || discardOnClose) {
            return;
        }
        session.finishGesture();
        awaitWrite();
        if (isTerrainGestureActive()) {
            throw new IOException("Finish or cancel the Terrain operation before flushing recovery.");
        }
        if (session.getDraftRevision() != queuedRevision || terrainRevision() != queuedTerrainRevision) {
            if (!submit()) {
                throw new IOException(error);
            }
            awaitWrite();
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        IOException failure = null;
        try {
            if (lock != null) {
                flush();
            }
        } catch (IOException invalid) {
            failure = invalid;
        }
        closed = true;
        if (worker != null) {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                    worker.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                worker.shutdownNow();
            }
        }
        try {
            if (lock != null) {
                lock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException invalid) {
            if (failure == null) {
                failure = invalid;
            } else {
                failure.addSuppressed(invalid);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private boolean submit() {
        if (!session.isDirty() && !isTerrainDirty()) {
            return discardExported();
        }
        try {
            var capture =
                    EditingRecoveryCodec.capture(session.getClientRoot(), session.getRecoveryState(), terrainState());
            if (capture.documents().isEmpty()
                    && capture.terrain() == null
                    && capture.assetFiles().isEmpty()
                    && terrainRevision() == 0
                    && queuedTerrainRevision == 0) {
                queuedRevision = session.getDraftRevision();
                return true;
            }
            if (worker == null) {
                worker = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                        .daemon()
                        .name("cruma-editor-recovery")
                        .factory());
            }
            queuedRevision = session.getDraftRevision();
            queuedCommands = capture.state().committedCommands();
            queuedTerrainRevision = terrainRevision();
            pendingWrite = worker.submit(() -> store.write(capture));
            status = "Saving recovery draft";
            return true;
        } catch (RuntimeException failure) {
            error = failureMessage(failure);
            status = "Recovery draft failed";
            retryAfter = System.nanoTime() + debounceNanos;
            return false;
        }
    }

    private boolean discardExported() {
        try {
            store.discard();
        } catch (IOException | RuntimeException failure) {
            error = failureMessage(failure);
            status = "Recovery draft failed";
            retryAfter = System.nanoTime() + debounceNanos;
            return false;
        }
        queuedRevision = session.getDraftRevision();
        queuedCommands = session.getCommittedCommands();
        queuedTerrainRevision = terrainRevision();
        status = "All changes are saved";
        error = "";
        return true;
    }

    private boolean isTerrainDirty() {
        var terrain = currentTerrain.get();
        return terrain != null && terrain.isDirty();
    }

    private long terrainRevision() {
        var terrain = currentTerrain.get();
        return terrain == null ? 0 : terrain.getRecoveryRevision();
    }

    private boolean isTerrainGestureActive() {
        var terrain = currentTerrain.get();
        return terrain != null && terrain.isRecoveryGestureActive();
    }

    private State terrainState() {
        var terrain = currentTerrain.get();
        return terrain == null ? null : terrain.getRecoveryState();
    }

    private void restoreTerrain(State state) throws IOException {
        var terrain = currentTerrain.get();
        if (terrain == null && state != null) {
            terrain = createTerrain.get();
        }
        if (terrain != null) {
            terrain.restoreRecovery(state);
        }
    }

    private void poll(long now) {
        if (pendingWrite == null || !pendingWrite.isDone()) {
            return;
        }
        try {
            awaitWrite();
        } catch (IOException failure) {
            error = failureMessage(failure);
            status = "Recovery draft failed";
            queuedRevision = -1;
            retryAfter = now + debounceNanos;
        }
    }

    private void awaitWrite() throws IOException {
        if (pendingWrite == null) {
            return;
        }
        var pending = pendingWrite;
        try {
            pending.get();
            status = "Recovery draft saved";
            error = "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Recovery write was interrupted.", interrupted);
        } catch (ExecutionException failure) {
            queuedRevision = -1;
            throw new IOException("Recovery write failed: " + failureMessage(failure.getCause()), failure.getCause());
        } finally {
            if (pending.isDone()) {
                pendingWrite = null;
            }
        }
    }

    private void requireWritable() throws IOException {
        if (closed || lock == null) {
            throw new IOException(error.isEmpty() ? "Recovery storage is unavailable." : error);
        }
    }

    private static String failureMessage(Throwable failure) {
        if (failure instanceof OverlappingFileLockException) {
            return "Another editor is using this recovery directory.";
        }
        return UserMessages.plain(failure, "The draft could not be saved or recovered. The log names the cause.");
    }
}

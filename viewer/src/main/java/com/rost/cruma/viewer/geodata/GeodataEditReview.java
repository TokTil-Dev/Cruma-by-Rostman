package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoEditCheck;
import com.rost.cruma.core.geodata.GeoEditCheck.Result;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class GeodataEditReview implements AutoCloseable {
    static final long QUIET_NANOS = 600_000_000L;

    static final int MAX_CELLS = 400_000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "geodata-edit-review");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> pending;
    private volatile long generation;
    private long seenRevision = Long.MIN_VALUE;
    private long seenAt;
    private long requested = Long.MIN_VALUE;
    private Review result;

    public record SpawnImpact(List<SpawnProblem> blocked, List<SpawnProblem> emptied) {
        public static final SpawnImpact NONE = new SpawnImpact(List.of(), List.of());

        public SpawnImpact {
            blocked = List.copyOf(blocked);
            emptied = List.copyOf(emptied);
        }
    }

    public record SpawnProblem(String title, String detail, double x, double y, double z) {}

    @FunctionalInterface
    public interface SpawnCheck {
        SpawnImpact check(GeoDataAccess before, GeoDataAccess after, Bounds area, BooleanSupplier cancelled);
    }

    public record Review(
            long revision, Result scan, @Nullable SpawnImpact spawns, long elapsedNanos, String error) {}

    public static Bounds editedArea(List<CellKey> changed) {
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        for (var key : changed) {
            minX = Math.min(minX, key.x());
            minY = Math.min(minY, key.y());
            maxX = Math.max(maxX, key.x() + 16);
            maxY = Math.max(maxY, key.y() + 16);
        }
        return changed.isEmpty()
                ? new Bounds(0, 0, 0, 0)
                : new Bounds(
                        minX - GeoEditCheck.MARGIN,
                        minY - GeoEditCheck.MARGIN,
                        maxX + GeoEditCheck.MARGIN,
                        maxY + GeoEditCheck.MARGIN);
    }

    public synchronized void request(GeoEditingSession session, @Nullable SpawnCheck spawns) {
        var document = session.getDocument();
        var revision = document.revision();
        var now = System.nanoTime();
        if (revision != seenRevision) {
            seenRevision = revision;
            seenAt = now;
            return;
        }
        if (revision == requested || now - seenAt < QUIET_NANOS || !document.isModified()) {
            return;
        }
        requested = revision;
        if (pending != null) {
            pending.cancel(true);
        }
        var before = document.getBase();
        var after = document.snapshot();
        var changed = List.copyOf(document.getChangedCells().keySet());
        var generation = ++this.generation;
        BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted() || this.generation != generation;
        pending = worker.submit(() -> {
            var started = System.nanoTime();
            Review review;
            try {
                var scan = GeoEditCheck.check(before, after, changed, MAX_CELLS, 128, cancelled);
                SpawnImpact impact = null;
                if (spawns != null) {
                    impact = spawns.check(before, after, editedArea(changed), cancelled);
                }
                review = new Review(revision, scan, impact, System.nanoTime() - started, "");
            } catch (RuntimeException failure) {
                review = new Review(
                        revision,
                        new Result(List.of(), 0, 0, true),
                        null,
                        System.nanoTime() - started,
                        failure.getMessage() == null ? failure.toString() : failure.getMessage());
            }
            synchronized (this) {
                if (!cancelled.getAsBoolean()) {
                    result = review;
                }
            }
        });
    }

    public synchronized @Nullable Review getResultFor(long revision) {
        return result != null && result.revision() == revision ? result : null;
    }

    public synchronized boolean isChecking(long revision) {
        return getResultFor(revision) == null;
    }

    @Override
    public synchronized void close() {
        generation++;
        worker.shutdownNow();
    }
}

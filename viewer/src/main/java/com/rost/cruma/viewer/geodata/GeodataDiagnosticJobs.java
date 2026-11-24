package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataDiff;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataReader;
import com.rost.cruma.core.geodata.GeoDataRepository;
import com.rost.cruma.core.geodata.GeoDataRepository.Reader;
import com.rost.cruma.core.geodata.GeoDataScanner;
import com.rost.cruma.core.geodata.GeoDataScanner.SurfaceProbe;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCase.ReplayStatus;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCase.Request;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCase.SurfaceTrace;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCase.Trace;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

public final class GeodataDiagnosticJobs implements AutoCloseable {
    private final GeoDataRepository repository;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
                var thread = new Thread(task, "geodata-diagnostics");
                thread.setDaemon(true);
                return thread;
            });
    private Future<?> pending;
    private Result result;
    private Source active;
    private SurfaceProbe activeSurfaces;
    private boolean closed;
    private volatile long generation;
    private volatile boolean busy;
    private volatile int completedCells;
    private volatile int maximumCells;
    private volatile String phase = "";

    public GeodataDiagnosticJobs() {
        this(GeoDataReader::read);
    }

    GeodataDiagnosticJobs(Reader reader) {
        repository = new GeoDataRepository(null, 4, 128L * 1024 * 1024, reader);
    }

    public enum Kind {
        SCAN,
        COMPARE
    }

    public record Result(
            Kind kind,
            GeoDataScanner.Result scan,
            GeoDataDiff.Result difference,
            GeoDataSnapshot sourceB,
            Bounds bounds,
            String sourceId,
            long revision,
            long elapsedNanos,
            String error,
            GeoDiagnosticCase diagnosticCase,
            ReplayStatus replayStatus,
            String message) {}

    public record Progress(boolean busy, String phase, int completedCells, int maxCells) {}

    public synchronized void setComparisonSource(Path path) {
        var normalized = path == null ? null : path.toAbsolutePath().normalize();
        if (!Objects.equals(repository.getSourcePath(), normalized)) {
            cancel();
            repository.setSource(normalized);
        }
    }

    public synchronized Path getComparisonSource() {
        return repository.getSourcePath();
    }

    public synchronized void refreshComparisonSource() {
        cancel();
        repository.refresh();
    }

    public synchronized void scan(
            GeoDataAccess source, Bounds bounds, int maxCells, int maxFindings, int maxTransitions, int abruptHeight) {
        scan(source, bounds, maxCells, maxFindings, maxTransitions, abruptHeight, null, 32);
    }

    public synchronized void scan(
            GeoDataAccess source,
            Bounds bounds,
            int maxCells,
            int maxFindings,
            int maxTransitions,
            int abruptHeight,
            SurfaceProbe surfaces,
            int surfaceTolerance) {
        submit(
                source,
                new Request(
                        Kind.SCAN,
                        bounds,
                        maxCells,
                        maxFindings,
                        maxTransitions,
                        abruptHeight,
                        20_000_000,
                        surfaces != null,
                        surfaceTolerance,
                        surfaces == null ? 2_000_000 : surfaces.getTriangleTestLimit()),
                surfaces);
    }

    public synchronized void compare(
            GeoDataAccess source, Bounds bounds, int maxCells, int maxReportedCells, long maxLayerComparisons) {
        if (repository.getSourcePath() == null) {
            throw new IllegalStateException("Choose comparison source B first");
        }
        submit(
                source,
                new Request(Kind.COMPARE, bounds, maxCells, maxReportedCells, 2_000_000, 128, maxLayerComparisons),
                null);
    }

    public synchronized void saveCase(GeoDataAccess source, Path path, boolean replaceExisting) {
        var saved = poll(source);
        if (saved == null || saved.diagnosticCase() == null) {
            throw new IllegalStateException("Run a diagnostic before saving a case");
        }
        var savedSurfaces = activeSurfaces;
        var request = begin(source, saved.diagnosticCase().request().maxCells(), "Saving diagnostic case");
        activeSurfaces = savedSurfaces;
        pending = worker.submit(() -> {
            try {
                if (generation != request || Thread.currentThread().isInterrupted()) {
                    return;
                }
                GeoDiagnosticCaseStore.write(path, saved.diagnosticCase(), replaceExisting);
                publish(request, source, withMessage(saved, "Diagnostic case saved", ""));
            } catch (IOException | RuntimeException exception) {
                publish(request, source, withMessage(saved, "", failureMessage(exception)));
            }
        });
    }

    public synchronized void replayCase(GeoDataAccess source, Path path) {
        replayCase(source, path, null);
    }

    public synchronized void replayCase(GeoDataAccess source, Path path, IntFunction<SurfaceProbe> clientProvider) {
        var request = begin(source, 0, "Reading diagnostic case");
        pending = worker.submit(() -> {
            var started = System.nanoTime();
            try {
                var expected = GeoDiagnosticCaseStore.read(path);
                if (generation == request && !Thread.currentThread().isInterrupted()) {
                    maximumCells = expected.request().maxCells();
                    var surfaces = expected.request().clientGeometry() && clientProvider != null
                            ? clientProvider.apply(expected.request().maxClientTriangleTests())
                            : null;
                    if (expected.request().clientGeometry() && surfaces == null) {
                        throw new IllegalStateException("This case requires the client triangle index");
                    }
                    synchronized (this) {
                        if (generation != request) {
                            return;
                        }
                        activeSurfaces = surfaces;
                    }
                    run(request, source, expected.request(), expected, surfaces);
                }
            } catch (IOException | RuntimeException exception) {
                publish(request, source, failedResult(Kind.SCAN, source, new Bounds(0, 0, 0, 0), started, exception));
            }
        });
    }

    public synchronized Result poll(GeoDataAccess source) {
        if (active != null
                && (source == null
                        || source.getContentRevision() != active.revision()
                        || !source.getSourceId().equals(active.sourceId())
                        || activeSurfaces != null && !activeSurfaces.isValid())) {
            cancel();
        }
        return result;
    }

    public Progress getProgress() {
        return new Progress(busy, phase, completedCells, maximumCells);
    }

    public synchronized void cancel() {
        generation++;
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
        worker.getQueue().clear();
        result = null;
        active = null;
        activeSurfaces = null;
        busy = false;
        completedCells = 0;
        phase = "";
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            cancel();
            closed = true;
            worker.shutdownNow();
        }
    }

    private record Source(String sourceId, long revision) {}

    private void submit(GeoDataAccess source, Request input, SurfaceProbe surfaces) {
        var request = begin(source, input.maxCells(), "Starting diagnostics");
        activeSurfaces = surfaces;
        pending = worker.submit(() -> run(request, source, input, null, surfaces));
    }

    private void run(
            long request, GeoDataAccess source, Request input, GeoDiagnosticCase expected, SurfaceProbe surfaces) {
        BooleanSupplier cancelled =
                () -> generation != request || Thread.currentThread().isInterrupted();
        var started = System.nanoTime();
        try {
            if (cancelled.getAsBoolean()) {
                return;
            }
            var first = new Trace(source);
            GeoDataScanner.Result scan = null;
            GeoDataDiff.Result difference = null;
            GeoDataSnapshot second = null;
            GeoDiagnosticCase diagnostic;
            if (input.kind() == Kind.SCAN) {
                phase = "Scanning source data";
                var limits = new GeoDataScanner.Limits(
                        input.maxCells(),
                        input.maxReports(),
                        input.maxTransitions(),
                        input.abruptHeight(),
                        input.surfaceTolerance(),
                        cancelled,
                        cells -> {
                            if (generation == request) {
                                completedCells = cells;
                            }
                        });
                var surfaceTrace = surfaces == null ? null : new SurfaceTrace(surfaces);
                scan = new GeoDataScanner(first, surfaceTrace).scan(input.bounds(), limits);
                diagnostic = GeoDiagnosticCase.scan(input, first, surfaceTrace, scan);
            } else {
                if (repository.getSourcePath() == null) {
                    throw new IllegalStateException("Choose comparison source B before replaying this case");
                }
                phase = "Loading comparison source B";
                var bounds = input.bounds();
                second = repository.loadBounds(bounds.minX(), bounds.minY(), bounds.maxX(), bounds.maxY());
                if (cancelled.getAsBoolean()) {
                    return;
                }
                phase = "Comparing source layers";
                var secondTrace = new Trace(second);
                difference = GeoDataDiff.compare(
                        first,
                        secondTrace,
                        bounds,
                        new GeoDataDiff.Limits(
                                input.maxCells(), input.maxReports(), input.maxLayerComparisons(), cancelled));
                completedCells = difference.comparedCells();
                diagnostic = GeoDiagnosticCase.comparison(input, first, secondTrace, difference);
            }
            var replay = expected == null
                    ? null
                    : expected.compare(diagnostic, GeoDiagnosticCase.isComplete(scan, difference));
            publish(
                    request,
                    source,
                    new Result(
                            input.kind(),
                            scan,
                            difference,
                            second,
                            input.bounds(),
                            source.getSourceId(),
                            source.revision(),
                            System.nanoTime() - started,
                            "",
                            diagnostic,
                            replay,
                            ""));
        } catch (RuntimeException exception) {
            publish(request, source, failedResult(input.kind(), source, input.bounds(), started, exception));
        }
    }

    private long begin(GeoDataAccess source, int maxCells, String phase) {
        if (closed) {
            throw new IllegalStateException("Diagnostic worker is closed");
        }
        Objects.requireNonNull(source, "source");
        cancel();
        active = new Source(source.getSourceId(), source.getContentRevision());
        busy = true;
        maximumCells = maxCells;
        this.phase = phase;
        return generation;
    }

    private synchronized void publish(long request, GeoDataAccess source, Result next) {
        if (request == generation
                && !Thread.currentThread().isInterrupted()
                && active != null
                && source.getContentRevision() == active.revision()
                && source.getSourceId().equals(active.sourceId())
                && (activeSurfaces == null || activeSurfaces.isValid())) {
            result = next;
            busy = false;
            phase = "Finished";
        } else if (request == generation) {
            cancel();
        }
    }

    private static Result failedResult(
            Kind kind, GeoDataAccess source, Bounds bounds, long start, Exception exception) {
        return new Result(
                kind,
                null,
                null,
                null,
                bounds,
                source.getSourceId(),
                source.revision(),
                System.nanoTime() - start,
                failureMessage(exception),
                null,
                null,
                "");
    }

    private static Result withMessage(Result result, String message, String error) {
        return new Result(
                result.kind(),
                result.scan(),
                result.difference(),
                result.sourceB(),
                result.bounds(),
                result.sourceId(),
                result.revision(),
                result.elapsedNanos(),
                error,
                result.diagnosticCase(),
                result.replayStatus(),
                message);
    }

    private static String failureMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}

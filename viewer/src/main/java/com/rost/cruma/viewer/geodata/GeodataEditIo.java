package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataSourceSave;
import com.rost.cruma.core.geodata.GeoDataWriter;
import com.rost.cruma.core.geodata.GeoDataWriter.ExportCancelledException;
import com.rost.cruma.core.geodata.GeoDataWriter.ExportControl;
import com.rost.cruma.core.geodata.GeoEditDocument;
import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoPathNodeExporter;
import com.rost.cruma.core.geodata.GeoPathNodeExporter.Control;
import com.rost.cruma.core.geodata.GeoPathNodeExporter.Phase;
import com.rost.cruma.core.geodata.GeoPathNodeExporter.Progress;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import com.rost.cruma.viewer.geodata.GeoEditingSession.Patch;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jetbrains.annotations.Nullable;

public final class GeodataEditIo implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "geodata-edit-files");
        thread.setDaemon(true);
        return thread;
    });
    private Future<Completed> pending;
    private final Exporter exporter;
    private final PathNodeExporter pathNodeExporter;
    private GeoEditingSession activeSession;
    private long activeRevision;
    private Path activePath;
    private Operation activeOperation;
    private ExportControl exportControl;
    private GeoFormat exportFormat;
    private boolean exportReplace;
    private Control pathNodeControl;
    private boolean closed;
    private boolean backup = true;

    public GeodataEditIo() {
        this(GeoDataWriter::export, GeoPathNodeExporter::export);
    }

    GeodataEditIo(Exporter exporter) {
        this(exporter, GeoPathNodeExporter::export);
    }

    GeodataEditIo(Exporter exporter, PathNodeExporter pathNodeExporter) {
        this.exporter = Objects.requireNonNull(exporter);
        this.pathNodeExporter = Objects.requireNonNull(pathNodeExporter);
    }

    public enum Operation {
        SAVE_PATCH,
        LOAD_PATCH,
        EXPORT,
        EXPORT_PATH_NODES,
        SAVE_SOURCE,
        SAVE_SOURCE_PATH_NODES
    }

    public record Result(Operation operation, Path path, boolean applied, String message) {}

    public boolean isBusy() {
        return pending != null;
    }

    public Operation getActiveOperation() {
        return pending == null ? null : activeOperation;
    }

    public boolean canCancelExport() {
        return pending != null
                && (exportControl != null && exportControl.canCancel()
                        || pathNodeControl != null && pathNodeControl.canCancel());
    }

    public boolean exportCancellationRequested() {
        return pending != null
                && (exportControl != null && exportControl.isCancellationRequested()
                        || pathNodeControl != null && pathNodeControl.isCancellationRequested());
    }

    public boolean cancelExport() {
        return pending != null
                && (exportControl != null && exportControl.cancel()
                        || pathNodeControl != null && pathNodeControl.cancel());
    }

    public ExportControl.@Nullable Progress getExportProgress() {
        if (pending == null) {
            return null;
        }
        if (exportControl != null) {
            return exportControl.getProgress();
        }
        return pathNodeControl == null ? null : pathNodeControl.getGeodataProgress();
    }

    public Progress getPathNodeProgress() {
        return pending == null || pathNodeControl == null ? null : pathNodeControl.getProgress();
    }

    public void export(GeoEditingSession session, Path destination, @Nullable GeoFormat format, boolean replace) {
        exportFormat = format;
        exportReplace = replace;
        try {
            submit(Operation.EXPORT, session, destination, false, null);
        } finally {
            exportFormat = null;
            exportReplace = false;
        }
    }

    public void exportPathNodes(GeoEditingSession session, Path destination, ZoneTable table, boolean replace) {
        exportReplace = replace;
        try {
            submit(Operation.EXPORT_PATH_NODES, session, destination, false, table);
        } finally {
            exportReplace = false;
        }
    }

    public void saveToSource(GeoEditingSession session, boolean pathNodes, boolean backup, ZoneTable table) {
        this.backup = backup;
        submit(
                pathNodes ? Operation.SAVE_SOURCE_PATH_NODES : Operation.SAVE_SOURCE,
                session,
                session.getDocument().getBase().getSourcePath(),
                false,
                table);
    }

    public void submit(Operation operation, GeoEditingSession session, Path path, boolean replace) {
        submit(operation, session, path, replace, null);
    }

    public Result poll(GeoEditingSession current) {
        if (pending == null || !pending.isDone()) {
            return null;
        }
        try {
            var completed = pending.get();
            var same = current == activeSession && current.getDocument().revision() == activeRevision;
            if (same) {
                if (completed.operation() == Operation.LOAD_PATCH) {
                    current.restorePatch(completed.patch());
                } else {
                    current.markSaved();
                }
            }
            if (completed.saved() != null) {
                var saved = completed.saved();
                return new Result(
                        completed.operation(),
                        completed.path(),
                        same,
                        "Saved "
                                + saved.replaced().size()
                                + (saved.replaced().size() == 1 ? " file" : " files")
                                + " into "
                                + saved.source()
                                + (saved.backup() == null
                                        ? ". No backup was made."
                                        : ". The old files are in " + saved.backup() + ".")
                                + (completed.operation() == Operation.SAVE_SOURCE
                                        ? " The pathnode graph in the folder was not rebuilt."
                                        : " The pathnode graph was rebuilt.")
                                + (same ? "" : " Later edits remain unsaved."));
            }
            var label = switch (completed.operation()) {
                case SAVE_PATCH -> "Patch saved";
                case LOAD_PATCH -> "Patch loaded";
                case EXPORT -> "Export completed";
                default -> "Export with pathnode completed";
            };
            return new Result(
                    completed.operation(),
                    completed.path(),
                    same,
                    (same
                                    ? label + ": " + completed.path()
                                    : label + " for an older revision. Current edits remain unsaved.")
                            + (completed.operation() == Operation.EXPORT_PATH_NODES
                                    ? " Load the exported directory to use its rebuilt path graph. The current source"
                                            + " graph is unchanged."
                                    : ""));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(null, null, false, "Geodata file operation interrupted");
        } catch (Exception exception) {
            var cause = exception.getCause() == null ? exception : exception.getCause();
            var graphProgress = pathNodeControl == null ? null : pathNodeControl.getProgress();
            if (graphProgress != null && graphProgress.phase() == Phase.FAILED) {
                return new Result(
                        null,
                        activePath,
                        false,
                        "Geodata file operation failed: " + cause.getMessage() + ". " + graphProgress.detail());
            }
            if (cause instanceof ExportCancelledException
                    || cause instanceof GeoPathNodeExporter.ExportCancelledException) {
                return new Result(
                        activeOperation,
                        activePath,
                        false,
                        "Geodata export cancelled. Temporary files removed; edit state preserved.");
            }
            return new Result(null, null, false, "Geodata file operation failed: " + cause.getMessage());
        } finally {
            pending = null;
            activeSession = null;
            activePath = null;
            activeOperation = null;
            exportControl = null;
            pathNodeControl = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        cancelExport();
        worker.shutdown();
    }

    @FunctionalInterface
    interface Exporter {
        void export(
                GeoEditDocument document,
                Path destination,
                @Nullable GeoFormat format,
                boolean replace,
                ExportControl control)
                throws IOException;
    }

    @FunctionalInterface
    interface PathNodeExporter {
        void export(GeoEditDocument document, Path destination, Control control, ZoneTable table, boolean replace)
                throws IOException;
    }

    private record Completed(Operation operation, Path path, Patch patch, GeoDataSourceSave.Result saved) {}

    private void submit(Operation operation, GeoEditingSession session, Path path, boolean replace, ZoneTable table) {
        if (pending != null) {
            throw new IllegalStateException("A geodata file operation is already running");
        }
        if (closed) {
            throw new IllegalStateException("Geodata file worker is closed");
        }
        var revision = session.getDocument().revision();
        var patch = operation == Operation.LOAD_PATCH ? null : session.getPatch();
        var base = session.getDocument().getBase();
        var destination = path.toAbsolutePath().normalize();
        activeSession = session;
        activeRevision = revision;
        activePath = destination;
        activeOperation = operation;
        exportControl =
                operation == Operation.EXPORT || operation == Operation.SAVE_SOURCE ? new ExportControl() : null;
        pathNodeControl = operation == Operation.EXPORT_PATH_NODES || operation == Operation.SAVE_SOURCE_PATH_NODES
                ? new Control()
                : null;
        var keepBackup = backup;
        var format = exportFormat;
        var replaceFiles = exportReplace;
        var control = exportControl;
        var graphControl = pathNodeControl;
        pending = worker.submit(() -> {
            switch (operation) {
                case SAVE_PATCH -> GeoEditPatchStore.write(destination, patch, replace);
                case LOAD_PATCH -> {
                    return new Completed(operation, destination, GeoEditPatchStore.read(destination), null);
                }
                case EXPORT -> {
                    var frozen = new GeoEditDocument(base);
                    frozen.loadChanges(patch.cells());
                    exporter.export(frozen, destination, format, replaceFiles, control);
                }
                case EXPORT_PATH_NODES -> {
                    var frozen = new GeoEditDocument(base);
                    frozen.loadChanges(patch.cells());
                    pathNodeExporter.export(frozen, destination, graphControl, table, replaceFiles);
                }
                case SAVE_SOURCE, SAVE_SOURCE_PATH_NODES -> {
                    var frozen = new GeoEditDocument(base);
                    frozen.loadChanges(patch.cells());
                    var pathNodes = operation == Operation.SAVE_SOURCE_PATH_NODES;
                    var saved =
                            GeoDataSourceSave.save(frozen, pathNodes, keepBackup, LocalDateTime.now(), directory -> {
                                if (pathNodes) {
                                    pathNodeExporter.export(frozen, directory, graphControl, table, false);
                                } else {
                                    exporter.export(frozen, directory, null, false, control);
                                }
                            });
                    return new Completed(operation, destination, null, saved);
                }
            }
            return new Completed(operation, destination, null, null);
        });
    }
}

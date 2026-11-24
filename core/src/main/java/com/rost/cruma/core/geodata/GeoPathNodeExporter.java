package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataWriter.ExportControl;
import com.rost.cruma.core.geodata.GeoEditDocument.CellEdit;
import com.rost.cruma.core.geodata.PathNodeGraph.Audit;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

public final class GeoPathNodeExporter {
    public static final List<String> GRAPH_FILES = List.of("pathnode.idx", "pathnode.bin", "pathnode.txt");

    private static final Generator EMBEDDED = new Generator() {
        @Override
        public void generate(Path directory, GeoPathNodeGenerator.Control control) throws IOException {
            GeoPathNodeGenerator.generate(directory, control);
        }

        @Override
        public void generate(Path directory, ZoneTable table, GeoPathNodeGenerator.Control control) throws IOException {
            GeoPathNodeGenerator.generate(directory, table, control);
        }
    };

    private GeoPathNodeExporter() {}

    public enum Phase {
        PREPARING,
        EXPORTING_GEODATA,
        HASHING_INPUTS,
        GENERATING_GRAPH,
        VERIFYING_INPUTS,
        AUDITING_GRAPH,
        WRITING_MANIFEST,
        COMMITTING,
        CLEANING_UP,
        COMPLETE,
        CANCELLED,
        FAILED
    }

    public record Progress(Phase phase, long completed, long total, String detail) {}

    public record Result(
            Path directory,
            long documentRevision,
            int changedCells,
            String algorithmId,
            Audit graphAudit,
            Map<String, String> generatedHashes) {
        public Result {
            generatedHashes = Map.copyOf(generatedHashes);
        }
    }

    public static final class Control {
        private static final long GENERATION_PROGRESS_NANOS = 50_000_000;

        private final Consumer<Progress> observer;
        private final LongSupplier nanoTime;
        private boolean generationReported;
        private long lastGenerationReport;
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<ExportControl> writerControl = new AtomicReference<>();
        private final AtomicReference<Progress> progress =
                new AtomicReference<>(new Progress(Phase.PREPARING, 0, 0, "Preparing path-node export"));

        public Control() {
            this(progress -> {});
        }

        Control(Consumer<Progress> observer) {
            this(observer, System::nanoTime);
        }

        Control(Consumer<Progress> observer, LongSupplier nanoTime) {
            this.observer = Objects.requireNonNull(observer);
            this.nanoTime = Objects.requireNonNull(nanoTime);
        }

        public boolean cancel() {
            var accepted = state.compareAndSet(State.RUNNING, State.CANCEL_REQUESTED);
            if (accepted) {
                var writer = writerControl.get();
                if (writer != null) {
                    writer.cancel();
                }
            }
            return accepted;
        }

        public boolean canCancel() {
            return state.get() == State.RUNNING;
        }

        public boolean isCancellationRequested() {
            return state.get() == State.CANCEL_REQUESTED;
        }

        public boolean isFinished() {
            return finished.get();
        }

        public Progress getProgress() {
            return progress.get();
        }

        public GeoDataWriter.ExportControl.@Nullable Progress getGeodataProgress() {
            var writer = writerControl.get();
            return writer == null ? null : writer.getProgress();
        }

        private void checkCancellation() throws ExportCancelledException {
            if (Thread.currentThread().isInterrupted()) {
                cancel();
            }
            if (isCancellationRequested()) {
                throw new ExportCancelledException();
            }
        }

        private void report(Phase phase, long completed, long total, String detail) {
            var next = new Progress(phase, completed, total, detail);
            progress.set(next);
            observer.accept(next);
        }

        private void reportProgress(Phase phase, long completed, long total, String detail)
                throws ExportCancelledException {
            checkCancellation();
            report(phase, completed, total, detail);
            checkCancellation();
        }

        private void reportGenerationProgress(long completed, long total, String detail)
                throws ExportCancelledException {
            checkCancellation();
            var now = nanoTime.getAsLong();
            if (!generationReported
                    || (total > 0 && completed == total)
                    || now - lastGenerationReport >= GENERATION_PROGRESS_NANOS) {
                generationReported = true;
                lastGenerationReport = now;
                report(Phase.GENERATING_GRAPH, completed, total, detail);
            }
            checkCancellation();
        }

        private void beginCommit() throws ExportCancelledException {
            checkCancellation();
            if (!state.compareAndSet(State.RUNNING, State.COMMITTING)) {
                throw new ExportCancelledException();
            }
            report(Phase.COMMITTING, 0, 0, "Publishing verified geodata and path nodes");
        }

        private enum State {
            RUNNING,
            CANCEL_REQUESTED,
            COMMITTING,
            CLEANING_UP,
            FINISHED
        }
    }

    public static final class ExportCancelledException extends IOException {
        private ExportCancelledException() {
            super("Path-node export cancelled");
        }
    }

    public static Result export(GeoEditDocument document, Path outputDirectory, Control control, ZoneTable table)
            throws IOException {
        return export(document, outputDirectory, control, table, false);
    }

    public static Result export(
            GeoEditDocument document, Path outputDirectory, Control control, ZoneTable table, boolean replace)
            throws IOException {
        return export(document, outputDirectory, control, table, EMBEDDED, GeoPathNodeExporter::audit, replace);
    }

    public static ZoneTable sourceZoneTable(Path source) throws IOException {
        Optional<ZoneTable> table;
        try {
            table = PathNodeGraph.zoneTable(source);
        } catch (IOException error) {
            throw new IOException(
                    "The source pathnode.idx does not show which server reads it; choose the pathnode server", error);
        }
        return table.orElseThrow(() -> new IOException(
                "The source has no pathnode.idx to show which server reads it; choose the pathnode server"));
    }

    @FunctionalInterface
    interface Generator {
        void generate(Path directory, GeoPathNodeGenerator.Control control) throws IOException;

        default void generate(Path directory, ZoneTable table, GeoPathNodeGenerator.Control control)
                throws IOException {
            generate(directory, control);
        }
    }

    @FunctionalInterface
    interface Auditor {
        Audit audit(Path directory, BooleanSupplier cancelled) throws IOException;
    }

    static Result export(GeoEditDocument document, Path outputDirectory, Control control, Generator generator)
            throws IOException {
        return export(document, outputDirectory, control, generator, GeoPathNodeExporter::audit);
    }

    static Result export(
            GeoEditDocument document, Path outputDirectory, Control control, Generator generator, boolean replace)
            throws IOException {
        return export(
                document,
                outputDirectory,
                control,
                ZoneTable.Y10_TO_26,
                generator,
                GeoPathNodeExporter::audit,
                replace);
    }

    static Result export(
            GeoEditDocument document, Path outputDirectory, Control control, Generator generator, Auditor auditor)
            throws IOException {
        return export(document, outputDirectory, control, ZoneTable.Y10_TO_26, generator, auditor);
    }

    static Result export(
            GeoEditDocument document,
            Path outputDirectory,
            Control control,
            ZoneTable requestedTable,
            Generator generator,
            Auditor auditor)
            throws IOException {
        return export(document, outputDirectory, control, requestedTable, generator, auditor, false);
    }

    static Result export(
            GeoEditDocument document,
            Path outputDirectory,
            Control control,
            ZoneTable requestedTable,
            Generator generator,
            Auditor auditor,
            boolean replace)
            throws IOException {
        Objects.requireNonNull(document);
        Objects.requireNonNull(control);
        Objects.requireNonNull(generator);
        Objects.requireNonNull(auditor);
        if (!control.started.compareAndSet(false, true)) {
            throw new IllegalStateException("A path-node export control can only be used once");
        }
        Path stage = null;
        var published = false;
        var cleanupFailed = false;
        IOException failure = null;
        Result result = null;
        try {
            control.reportProgress(Phase.PREPARING, 0, 0, "Checking output directory");
            var frozen = freeze(document);
            var source = frozen.document.getBase().getSourcePath();
            var target = checkedTarget(source, outputDirectory);
            var table = requestedTable != null ? requestedTable : sourceZoneTable(source);
            stage = Files.createTempDirectory(target.getParent(), ".cruma-pathnode-export-");
            var geo = stage.resolve("GeoData");
            control.reportProgress(Phase.EXPORTING_GEODATA, 0, 0, "Exporting a frozen geodata copy");
            var inner = new ExportControl(phase -> {
                if (control.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
                    var current = control.writerControl.get();
                    if (current != null) {
                        current.cancel();
                    }
                }
            });
            control.writerControl.set(inner);
            try {
                control.checkCancellation();
                var format = frozen.document.getBase().getFormat() == GeoFormat.L2OFF ? null : GeoFormat.L2OFF;
                GeoDataWriter.export(frozen.document, geo, format, inner);
            } catch (GeoDataWriter.ExportCancelledException cancelled) {
                control.cancel();
                throw new ExportCancelledException();
            } finally {
                control.writerControl.set(null);
            }
            control.reportProgress(Phase.HASHING_INPUTS, 0, 0, "Hashing every staged input");
            var inputHashes = inputHashes(geo, control);
            control.reportProgress(Phase.GENERATING_GRAPH, 0, 0, "Building path nodes in Cruma");
            generator.generate(geo, table, control::reportGenerationProgress);
            control.checkCancellation();
            control.reportProgress(
                    Phase.VERIFYING_INPUTS, 0, inputHashes.size(), "Checking that generator inputs are unchanged");
            var checked = 0;
            for (var entry : inputHashes.entrySet()) {
                if (!hash(geo.resolve(entry.getKey()), control).equals(entry.getValue())) {
                    throw new IOException("Path-node generator changed a geodata input: " + entry.getKey());
                }
                control.reportProgress(Phase.VERIFYING_INPUTS, ++checked, inputHashes.size(), entry.getKey());
            }
            verifyGeneratedFiles(geo, inputHashes.keySet());
            control.reportProgress(Phase.AUDITING_GRAPH, 0, 0, "Auditing every path-node record and neighbor");
            var audit = auditor.audit(geo, control::isCancellationRequested);
            if (audit.cancelled()) {
                control.cancel();
                throw new ExportCancelledException();
            }
            if (!audit.isValid()) {
                throw new IOException("Generated path-node graph is invalid: " + audit.issues());
            }
            var generatedHashes = new LinkedHashMap<String, String>();
            for (var name : GRAPH_FILES) {
                if (Files.isRegularFile(geo.resolve(name))) {
                    generatedHashes.put(name, hash(geo.resolve(name), control));
                }
            }
            control.reportProgress(Phase.WRITING_MANIFEST, 0, 0, "Recording generator and graph verification");
            writeManifest(geo, frozen.revision, table, audit, generatedHashes);
            var regionHashes = new LinkedHashMap<String, String>();
            for (var name : GeoPathNodeFolder.indexedRegions(geo)) {
                regionHashes.put(name, inputHashes.get(name));
            }
            GeoPathNodeFolder.writeRecord(geo, geo, table, regionHashes);
            control.beginCommit();
            GeoDataWriter.publish(geo, target, replace);
            published = true;
            result = new Result(
                    target,
                    frozen.revision,
                    frozen.document.getChangedCellCount(),
                    GeoPathNodeGenerator.ALGORITHM_ID,
                    audit,
                    generatedHashes);
        } catch (IOException error) {
            if (error instanceof InterruptedIOException && control.isCancellationRequested()) {
                var cancelled = new ExportCancelledException();
                cancelled.initCause(error);
                failure = cancelled;
                throw cancelled;
            }
            failure = error;
            throw error;
        } finally {
            control.state.compareAndSet(Control.State.RUNNING, Control.State.CLEANING_UP);
            try {
                if (stage != null) {
                    control.report(Phase.CLEANING_UP, 0, 0, "Removing temporary generation files");
                    deleteStage(stage);
                }
            } catch (IOException cleanup) {
                cleanupFailed = true;
                var error = new IOException(
                        published
                                ? "Export was published, but private staging cleanup failed: " + stage
                                : "Path-node staging cleanup failed; private staging retained at " + stage,
                        cleanup);
                if (failure != null) {
                    error.addSuppressed(failure);
                }
                throw error;
            } finally {
                var phase = cleanupFailed
                        ? Phase.FAILED
                        : published
                                ? Phase.COMPLETE
                                : control.isCancellationRequested() ? Phase.CANCELLED : Phase.FAILED;
                control.state.updateAndGet(
                        state -> state == Control.State.CANCEL_REQUESTED ? state : Control.State.FINISHED);
                control.progress.set(new Progress(
                        phase,
                        0,
                        0,
                        cleanupFailed
                                ? "Cleanup failed; private staging retained at " + stage
                                : published
                                        ? "Verified export complete"
                                        : control.isCancellationRequested()
                                                ? "Export cancelled and staging removed"
                                                : "Export failed"));
                control.finished.set(true);
            }
        }
        return result;
    }

    private record Frozen(GeoEditDocument document, long revision) {}

    private static Audit audit(Path directory, BooleanSupplier cancelled) throws IOException {
        try (var graph = PathNodeGraph.open(directory, cancelled)) {
            return graph.audit(cancelled);
        }
    }

    private static Frozen freeze(GeoEditDocument document) {
        synchronized (document) {
            var changes = document.getChangedCells();
            var edits = new ArrayList<CellEdit>();
            var values = (long) changes.size();
            for (var entry : changes.entrySet()) {
                edits.add(new CellEdit(entry.getKey().x(), entry.getKey().y(), entry.getValue()));
                values += entry.getValue().size();
            }
            var copy = new GeoEditDocument(document.getBase(), 1, Math.max(1, changes.size()), Math.max(1, values));
            copy.loadChanges(edits);
            return new Frozen(copy, document.revision());
        }
    }

    private static Path checkedTarget(Path source, Path output) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            throw new IOException("A source directory is required for path-node export");
        }
        try {
            return GeoDataWriter.exportTarget(source.toRealPath(), output);
        } catch (IOException failure) {
            throw failure.getMessage().startsWith("Export directory must be outside")
                    ? new IOException("Path-node export must be outside the source directory")
                    : failure;
        }
    }

    private static Map<String, String> inputHashes(Path geo, Control control) throws IOException {
        var hashes = new LinkedHashMap<String, String>();
        try (var files = Files.newDirectoryStream(geo)) {
            for (var file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected staged geodata input: " + file.getFileName());
                }
                var name = file.getFileName().toString();
                if (!name.matches("[12][0-9]_[12][0-9]_conv\\.dat")
                        && !name.equals("geo_index.txt")
                        && !name.equals("cruma-geodata-export.properties")) {
                    throw new IOException("Unexpected staged geodata input: " + name);
                }
                hashes.put(name, hash(file, control));
                control.reportProgress(Phase.HASHING_INPUTS, hashes.size(), 0, name);
            }
        }
        return hashes;
    }

    private static void verifyGeneratedFiles(Path geo, Set<String> inputs) throws IOException {
        try (var files = Files.newDirectoryStream(geo)) {
            for (var file : files) {
                var name = file.getFileName().toString();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || (!inputs.contains(name) && !GRAPH_FILES.contains(name))) {
                    throw new IOException("Unexpected generator output: " + name);
                }
            }
        }
        for (var name : List.of("pathnode.idx", "pathnode.bin")) {
            if (!Files.isRegularFile(geo.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Path-node generator output is missing: " + name);
            }
        }
    }

    private static void writeManifest(
            Path geo, long revision, ZoneTable table, Audit audit, Map<String, String> generatedHashes)
            throws IOException {
        var file = geo.resolve("cruma-geodata-export.properties");
        var metadata = new Properties();
        try (var input = Files.newInputStream(file)) {
            metadata.load(input);
        }
        metadata.setProperty("pathGraph.status", "generated-structurally-verified");
        metadata.setProperty("pathGraph.semanticVerification", "not-exhaustive");
        metadata.setProperty("pathGraph.generator.implementation", "embedded");
        metadata.setProperty("pathGraph.generator.algorithm", GeoPathNodeGenerator.ALGORITHM_ID);
        metadata.setProperty("pathGraph.document.revision", Long.toString(revision));
        metadata.setProperty("pathGraph.index.zones", Integer.toString(table.getZones()));
        metadata.setProperty("pathGraph.audit.nodes", Long.toString(audit.nodesChecked()));
        metadata.setProperty("pathGraph.audit.edges", Long.toString(audit.edgesChecked()));
        metadata.setProperty("pathGraph.audit.issues", Long.toString(audit.issueCount()));
        for (var entry : generatedHashes.entrySet()) {
            metadata.setProperty(entry.getKey() + ".sha256", entry.getValue());
        }
        try (var output = Files.newOutputStream(file)) {
            metadata.store(
                    output,
                    "Geodata and path nodes generated together. Structural audit is not exhaustive navigation"
                            + " verification.");
        }
    }

    private static String hash(Path file, Control control) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular file: " + file.getFileName());
        }
        try (var input = Files.newInputStream(file)) {
            var buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                control.checkCancellation();
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteStage(Path stage) throws IOException {
        Files.walkFileTree(stage, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

public final class GeoDataWriter {
    private GeoDataWriter() {}

    public static final class ExportControl {
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
        private final AtomicBoolean started = new AtomicBoolean();
        private final Consumer<Phase> observer;
        private volatile Progress progress = new Progress(0, 0, "");

        public ExportControl() {
            this(phase -> {});
        }

        ExportControl(Consumer<Phase> observer) {
            this.observer = Objects.requireNonNull(observer);
        }

        public boolean cancel() {
            return state.compareAndSet(State.RUNNING, State.CANCEL_REQUESTED);
        }

        public boolean canCancel() {
            return state.get() == State.RUNNING;
        }

        public boolean isCancellationRequested() {
            return state.get() == State.CANCEL_REQUESTED;
        }

        public Progress getProgress() {
            return progress;
        }

        public record Progress(int done, int total, String file) {}

        private enum State {
            RUNNING,
            CANCEL_REQUESTED,
            COMMITTING,
            FINISHED
        }

        private void reportProgress(Phase phase) throws ExportCancelledException {
            checkCancellation();
            observer.accept(phase);
            checkCancellation();
        }

        private void checkCancellation() throws ExportCancelledException {
            if (Thread.currentThread().isInterrupted()) {
                cancel();
            }
            if (isCancellationRequested()) {
                throw new ExportCancelledException();
            }
        }

        private void beginCommit() throws ExportCancelledException {
            reportProgress(Phase.BEFORE_COMMIT);
            if (!state.compareAndSet(State.RUNNING, State.COMMITTING)) {
                throw new ExportCancelledException();
            }
            observer.accept(Phase.COMMITTING);
        }

        private void finish() {
            state.updateAndGet(value -> value == State.CANCEL_REQUESTED ? value : State.FINISHED);
        }
    }

    public static final class ExportCancelledException extends IOException {
        private ExportCancelledException() {
            super("Geodata export cancelled");
        }
    }

    enum Phase {
        HASHING,
        COPYING,
        ENCODING,
        WRITING,
        VERIFYING,
        BEFORE_COMMIT,
        COMMITTING
    }

    public record ExportResult(Path directory, List<Path> files, boolean pathGraphStale, int changedCells) {
        public ExportResult {
            files = List.copyOf(files);
        }
    }

    public static byte[] encode(GeoDataRegion source, Map<CellKey, List<Layer>> edits) throws IOException {
        return encode(source, edits, null);
    }

    public static ExportResult export(GeoEditDocument document, Path outputDirectory) throws IOException {
        return export(document, outputDirectory, new ExportControl());
    }

    public static ExportResult export(GeoEditDocument document, Path outputDirectory, ExportControl control)
            throws IOException {
        return export(document, outputDirectory, null, control);
    }

    public static ExportResult export(
            GeoEditDocument document, Path outputDirectory, @Nullable GeoFormat format, ExportControl control)
            throws IOException {
        return export(document, outputDirectory, format, false, control);
    }

    public static ExportResult export(
            GeoEditDocument document,
            Path outputDirectory,
            @Nullable GeoFormat format,
            boolean replace,
            ExportControl control)
            throws IOException {
        Objects.requireNonNull(control, "control");
        if (!control.started.compareAndSet(false, true)) {
            throw new IllegalStateException("An export control can only be used once");
        }
        try {
            control.checkCancellation();
            return exportControlled(document, outputDirectory, format, replace, control);
        } finally {
            control.finish();
        }
    }

    public static Path exportRegion(
            GeoDataSnapshot base,
            Map<CellKey, List<Layer>> edits,
            GeoTile tile,
            @Nullable GeoFormat format,
            Path folder)
            throws IOException {
        var source = base.getSourcePath();
        var target = folder.toAbsolutePath().normalize();
        if (source != null
                && Files.isDirectory(source)
                && Files.isDirectory(target)
                && Files.isSameFile(source, target)) {
            throw new IOException("Export the region into another folder than the source");
        }
        var region = base.getRegion(tile.x(), tile.y());
        var input = region == null && source != null ? GeoFormat.find(source, tile) : null;
        if (region == null && input == null) {
            throw new IOException("The folder has no file for region " + tile.x() + "_" + tile.y());
        }
        if (region == null) {
            region = GeoDataReader.read(input);
        }
        var regionEdits = new HashMap<CellKey, List<Layer>>();
        for (var entry : edits.entrySet()) {
            if (GeoTile.fromWorld(entry.getKey().x(), entry.getKey().y()).equals(tile)) {
                regionEdits.put(entry.getKey(), entry.getValue());
            }
        }
        var outputFormat = format == null ? region.getFormat() : format;
        byte[] bytes;
        if (regionEdits.isEmpty()) {
            bytes = outputFormat.encode(region);
        } else {
            bytes = encode(region, regionEdits);
            if (outputFormat != GeoFormat.L2OFF) {
                bytes = outputFormat.encode(GeoDataReader.read(bytes));
            }
        }
        GeoDataReader.read(outputFormat == GeoFormat.L2OFF ? bytes : outputFormat.toL2off(bytes, tile.x(), tile.y()));
        Files.createDirectories(target);
        var file = target.resolve(outputFormat.getFileName(tile));
        var temporary = target.resolve(".cruma-region-" + file.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    public static List<String> exportFileNames(GeoDataSnapshot base, @Nullable GeoFormat format, boolean pathNodes)
            throws IOException {
        var names = new TreeSet<String>();
        var source = base.getSourcePath();
        if (source != null && Files.isDirectory(source)) {
            try (var files = Files.newDirectoryStream(source)) {
                for (var file : files) {
                    var named = GeoFormat.parse(file.getFileName().toString());
                    if (named == null || !Files.isRegularFile(file)) {
                        continue;
                    }
                    var tile = new GeoTile(named.tileX(), named.tileY());
                    var region = base.getRegion(tile.x(), tile.y());
                    var own = region != null
                            ? region.getFormat()
                            : GeoFormat.parse(GeoFormat.find(source, tile)
                                            .getFileName()
                                            .toString())
                                    .format();
                    names.add((format == null ? own : format).getFileName(tile));
                }
            }
        }
        if (names.stream().anyMatch(name -> GeoFormat.parse(name).format() == GeoFormat.L2OFF)) {
            names.add("geo_index.txt");
        }
        names.add("cruma-geodata-export.properties");
        if (pathNodes) {
            names.addAll(GeoPathNodeFolder.FILES);
        }
        return List.copyOf(names);
    }

    public static List<String> existingFiles(Path folder, List<String> names) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        return names.stream()
                .filter(name -> Files.exists(folder.resolve(name), LinkOption.NOFOLLOW_LINKS))
                .toList();
    }

    static Path exportTarget(Path realSource, Path outputDirectory) throws IOException {
        var target = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Export target is a file, not a folder: " + target);
        }
        var parent = target.getParent();
        if (parent == null) {
            throw new IOException("Export target needs a parent folder for temporary files");
        }
        if (target.startsWith(realSource)
                || Files.isDirectory(parent)
                        && parent.toRealPath().resolve(target.getFileName()).startsWith(realSource)) {
            throw new IOException("Export directory must be outside the source directory");
        }
        Files.createDirectories(parent);
        return parent.toRealPath().resolve(target.getFileName());
    }

    static void publish(Path staged, Path target, boolean replace) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(staged, target);
            return;
        }
        List<Path> files;
        try (var stream = Files.list(staged)) {
            files = stream.sorted().toList();
        }
        if (!replace) {
            var names =
                    files.stream().map(file -> file.getFileName().toString()).toList();
            var existing = existingFiles(target, names);
            if (!existing.isEmpty()) {
                throw new IOException("The folder already has "
                        + existing.size()
                        + (existing.size() == 1 ? " file" : " files")
                        + " of these names, such as "
                        + existing.getFirst()
                        + ". Confirm replacing them to export there.");
            }
        }
        for (var file : files) {
            var name = file.getFileName().toString();
            var temporary = target.resolve(".cruma-export-" + name + ".tmp");
            Files.move(file, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(
                        temporary,
                        target.resolve(name),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.deleteIfExists(staged);
    }

    public static String fileFingerprint(Path path) throws IOException {
        return fileFingerprint(path, null);
    }

    public static String fileFingerprint(byte[] data) {
        return HexFormat.of().formatHex(sha256().digest(data));
    }

    private static byte[] encode(GeoDataRegion source, Map<CellKey, List<Layer>> edits, ExportControl control)
            throws IOException {
        var sectors = new HashMap<Integer, Map<Integer, List<Layer>>>();
        for (var entry : edits.entrySet()) {
            reportProgress(control, Phase.ENCODING);
            var key = entry.getKey();
            if (!GeoTile.fromWorld(key.x(), key.y()).equals(new GeoTile(source.getTileX(), source.getTileY()))) {
                throw new IllegalArgumentException("Edit belongs to another region: " + key);
            }
            var x = (key.x() - source.getWorldMinX()) / 16;
            var y = (key.y() - source.getWorldMinY()) / 16;
            var index = x / 8 * 256 + y / 8;
            sectors.computeIfAbsent(index, unused -> new HashMap<>())
                    .put((x & 7) * 8 + (y & 7), GeoEditDocument.normalizeLayers(entry.getValue()));
        }
        if (sectors.isEmpty()) {
            return source.getEncodedBytes();
        }
        var output = new ByteArrayOutputStream();
        output.write(source.getHeaderBytes());
        var layers = 0;
        var singles = 0;
        var flats = 0;
        for (var index = 0; index < 65536; index++) {
            reportProgress(control, Phase.ENCODING);
            var editsInSector = sectors.get(index);
            var bytes = editsInSector == null
                    ? source.getSectorBytes(index / 256, index % 256)
                    : encodeSector(source, index / 256, index % 256, editsInSector, control);
            var count = (bytes[0] & 255) | ((bytes[1] & 255) << 8);
            layers += count;
            singles += count == 0 || count == 64 ? 1 : 0;
            flats += count == 0 ? 1 : 0;
            output.write(bytes);
            if (output.size() > GeoDataReader.MAX_FILE_BYTES) {
                throw new IOException("Edited region exceeds supported file size");
            }
        }
        var bytes = output.toByteArray();
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(6, layers)
                .putInt(10, singles)
                .putInt(14, flats);
        reportProgress(control, Phase.VERIFYING);
        var decoded = GeoDataReader.read(bytes);
        for (var entry : sectors.entrySet()) {
            reportProgress(control, Phase.VERIFYING);
            var sectorX = entry.getKey() / 256;
            var sectorY = entry.getKey() % 256;
            for (var cell = 0; cell < 64; cell++) {
                var x = sectorX * 8 + cell / 8;
                var y = sectorY * 8 + cell % 8;
                var edited = entry.getValue().get(cell);
                var count = edited == null ? source.getLayerCount(x, y) : edited.size();
                if (decoded.getLayerCount(x, y) != count) {
                    throw new IOException("Export verification failed for cell layer count");
                }
                for (var layer = 0; layer < count; layer++) {
                    if ((layer & 255) == 0) {
                        reportProgress(control, Phase.VERIFYING);
                    }
                    var height = edited == null
                            ? source.getHeight(x, y, layer)
                            : edited.get(layer).height();
                    var nswe = edited == null
                            ? source.getNswe(x, y, layer)
                            : edited.get(layer).nswe();
                    if (decoded.getHeight(x, y, layer) != height || decoded.getNswe(x, y, layer) != nswe) {
                        throw new IOException("Export verification failed for cell height or directions");
                    }
                }
            }
        }
        return bytes;
    }

    private static ExportResult exportControlled(
            GeoEditDocument document,
            Path outputDirectory,
            @Nullable GeoFormat format,
            boolean replace,
            ExportControl control)
            throws IOException {
        GeoDataSnapshot base;
        Map<CellKey, List<Layer>> changes;
        synchronized (document) {
            base = document.getBase();
            changes = document.getChangedCells();
        }
        var source = base.getSourcePath();
        if (source == null || !Files.isDirectory(source)) {
            throw new IOException("A source directory is required for a complete export");
        }
        source = source.toRealPath();
        var target = exportTarget(source, outputDirectory);
        var changedTiles = new HashMap<GeoTile, Map<CellKey, List<Layer>>>();
        for (var entry : changes.entrySet()) {
            var tile = GeoTile.fromWorld(entry.getKey().x(), entry.getKey().y());
            changedTiles.computeIfAbsent(tile, unused -> new HashMap<>()).put(entry.getKey(), entry.getValue());
        }
        var metadata = new Properties();
        metadata.setProperty("format", "cruma-geodata-export-1");
        metadata.setProperty("source", source.toString());
        metadata.setProperty("source.revision", Long.toString(base.revision()));
        metadata.setProperty("pathGraph.status", changes.isEmpty() ? "not-included" : "stale-rebuild-required");
        metadata.setProperty("changed.cells", Integer.toString(changes.size()));
        metadata.setProperty("regions.format", format == null ? "source" : format.name());
        var total = 0;
        for (var tileX = 10; tileX <= 29; tileX++) {
            for (var tileY = 10; tileY <= 26; tileY++) {
                if (base.getRegion(tileX, tileY) != null || GeoFormat.find(source, new GeoTile(tileX, tileY)) != null) {
                    total++;
                }
            }
        }
        control.progress = new ExportControl.Progress(0, total, "");
        var stage = Files.createTempDirectory(target.getParent(), ".cruma-geodata-export-");
        var fileNames = new ArrayList<Path>();
        var exported = new HashSet<GeoTile>();
        var finished = false;
        try {
            for (var tileX = 10; tileX <= 29; tileX++) {
                for (var tileY = 10; tileY <= 26; tileY++) {
                    control.checkCancellation();
                    var tile = new GeoTile(tileX, tileY);
                    var edits = changedTiles.get(tile);
                    var frozenRegion = base.getRegion(tileX, tileY);
                    var found = frozenRegion != null
                            ? source.resolve(frozenRegion.getFormat().getFileName(tile))
                            : GeoFormat.find(source, tile);
                    var input = found != null ? found : source.resolve(tile.fileName());
                    if (!Files.isRegularFile(input)) {
                        if (edits != null || frozenRegion != null) {
                            throw new IOException("Frozen source region is missing: " + input.getFileName());
                        }
                        continue;
                    }
                    var inputFormat = frozenRegion != null
                            ? frozenRegion.getFormat()
                            : GeoFormat.parse(input.getFileName().toString()).format();
                    var outputFormat = format == null ? inputFormat : format;
                    var name = outputFormat.getFileName(tile);
                    var destination = stage.resolve(name);
                    control.progress = new ExportControl.Progress(fileNames.size(), total, name);
                    if (edits == null && outputFormat == inputFormat) {
                        var before = fileFingerprint(input, control);
                        if (frozenRegion != null && !before.equals(frozenRegion.getFileFingerprint())) {
                            throw new IOException("Source changed since the document was opened: " + name);
                        }
                        copy(input, destination, control);
                        if (!before.equals(fileFingerprint(destination, control))
                                || !before.equals(fileFingerprint(input, control))) {
                            throw new IOException("Source changed during export: " + name);
                        }
                        metadata.setProperty(name + ".source.sha256", before);
                        metadata.setProperty(name + ".export.sha256", before);
                    } else {
                        if (edits != null && frozenRegion == null) {
                            throw new IOException("Changed source region is no longer available in the document");
                        }
                        var before = fileFingerprint(input, control);
                        var region = frozenRegion != null ? frozenRegion : GeoDataReader.read(input);
                        if (!before.equals(region.getFileFingerprint())) {
                            throw new IOException(
                                    "Source changed since the document was opened: " + input.getFileName());
                        }
                        byte[] bytes;
                        if (edits == null) {
                            bytes = outputFormat.encode(region);
                        } else {
                            bytes = encode(region, edits, control);
                            if (outputFormat != GeoFormat.L2OFF) {
                                bytes = outputFormat.encode(GeoDataReader.read(bytes));
                            }
                        }
                        write(destination, bytes, control);
                        control.reportProgress(Phase.VERIFYING);
                        GeoDataReader.read(destination);
                        if (!before.equals(fileFingerprint(input, control))) {
                            throw new IOException("Source changed during export: " + input.getFileName());
                        }
                        metadata.setProperty(
                                name + ".source", input.getFileName().toString());
                        metadata.setProperty(name + ".source.sha256", before);
                        metadata.setProperty(name + ".export.sha256", fileFingerprint(bytes, control));
                    }
                    fileNames.add(Path.of(name));
                    control.progress = new ExportControl.Progress(fileNames.size(), total, name);
                    exported.add(tile);
                }
            }
            if (!exported.containsAll(changedTiles.keySet()) || exported.isEmpty()) {
                throw new IOException("Export does not contain every edited region");
            }
            var l2off = fileNames.stream()
                    .map(name -> name.getFileName().toString())
                    .filter(name -> GeoFormat.parse(name).format() == GeoFormat.L2OFF)
                    .toList();
            if (!l2off.isEmpty()) {
                var index = new StringBuilder().append(l2off.size()).append('\n');
                for (var name : l2off) {
                    index.append(name).append('\n');
                }
                Files.writeString(stage.resolve("geo_index.txt"), index, StandardCharsets.UTF_8);
            }
            try (var output = Files.newOutputStream(stage.resolve("cruma-geodata-export.properties"))) {
                metadata.store(output, "Geodata export. Rebuild pathnode files before server pathfinding.");
            }
            control.beginCommit();
            publish(stage, target, replace);
            finished = true;
            var paths = new ArrayList<Path>();
            for (var name : fileNames) {
                paths.add(target.resolve(name));
            }
            return new ExportResult(target, paths, !changes.isEmpty(), changes.size());
        } finally {
            if (!finished) {
                try (var files = Files.newDirectoryStream(stage)) {
                    for (var file : files) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(stage);
            }
        }
    }

    private static String fileFingerprint(Path path, ExportControl control) throws IOException {
        var digest = sha256();
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                reportProgress(control, Phase.HASHING);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fileFingerprint(byte[] data, ExportControl control) throws IOException {
        var digest = sha256();
        for (var offset = 0; offset < data.length; offset += 65536) {
            digest.update(data, offset, Math.min(65536, data.length - offset));
            reportProgress(control, Phase.HASHING);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void copy(Path source, Path destination, ExportControl control) throws IOException {
        try (var input = Files.newInputStream(source);
                var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            var buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                control.reportProgress(Phase.COPYING);
            }
        }
    }

    private static void write(Path destination, byte[] bytes, ExportControl control) throws IOException {
        try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            for (var offset = 0; offset < bytes.length; offset += 65536) {
                output.write(bytes, offset, Math.min(65536, bytes.length - offset));
                control.reportProgress(Phase.WRITING);
            }
        }
    }

    private static void reportProgress(ExportControl control, Phase phase) throws ExportCancelledException {
        if (control != null) {
            control.reportProgress(phase);
        }
    }

    private static byte[] encodeSector(
            GeoDataRegion source, int sectorX, int sectorY, Map<Integer, List<Layer>> edits, ExportControl control)
            throws ExportCancelledException {
        var cells = new ArrayList<List<Layer>>(64);
        var total = 0;
        var single = true;
        for (var index = 0; index < 64; index++) {
            reportProgress(control, Phase.ENCODING);
            var layers = edits.get(index);
            if (layers == null) {
                var x = sectorX * 8 + index / 8;
                var y = sectorY * 8 + index % 8;
                var values = new ArrayList<Layer>();
                for (var layer = 0; layer < source.getLayerCount(x, y); layer++) {
                    if ((layer & 255) == 0) {
                        reportProgress(control, Phase.ENCODING);
                    }
                    values.add(new Layer(layer, source.getHeight(x, y, layer), source.getNswe(x, y, layer)));
                }
                layers = values;
            }
            cells.add(layers);
            total += layers.size();
            single &= layers.size() == 1;
        }
        if (total > Short.MAX_VALUE || (!single && (total == 0 || total == 64))) {
            throw new IllegalArgumentException("Ambiguous or overflowing sector layer total: " + total);
        }
        var output = new ByteArrayOutputStream();
        writeShort(output, total);
        for (var layers : cells) {
            if (!single) {
                writeShort(output, layers.size());
            }
            for (var layer : layers) {
                writeShort(output, (layer.height() << 1) | layer.nswe());
                if ((layer.index() & 255) == 0) {
                    reportProgress(control, Phase.ENCODING);
                }
            }
        }
        return output.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 255);
        output.write((value >>> 8) & 255);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

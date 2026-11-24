package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoPathNodeGenerator.Control;
import com.rost.cruma.core.geodata.PathNodeGraph.Audit;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class GeoPathNodeFolder {
    public static final String RECORD = "cruma-pathnode.properties";

    public static final List<String> FILES = List.of("pathnode.idx", "pathnode.bin", "pathnode.txt", RECORD);

    private static final Pattern REGION_FILE = Pattern.compile("[12][0-9]_[12][0-9]_conv\\.dat");
    private static final int MAX_INDEX_BYTES = 65536;

    private GeoPathNodeFolder() {}

    public enum State {
        NONE,
        CURRENT,
        STALE,
        UNKNOWN
    }

    public record Status(State state, @Nullable ZoneTable table, List<String> changed) {
        public Status {
            changed = List.copyOf(changed);
        }
    }

    public record Result(
            Path folder,
            ZoneTable table,
            Audit audit,
            int regions,
            List<Path> replaced,
            @Nullable Path backup) {
        public Result {
            replaced = List.copyOf(replaced);
        }
    }

    public static Status check(Path folder, BooleanSupplier cancelled) throws IOException {
        if (!Files.isRegularFile(folder.resolve("pathnode.idx"), LinkOption.NOFOLLOW_LINKS)) {
            return new Status(State.NONE, null, List.of());
        }
        ZoneTable table;
        try {
            table = PathNodeGraph.zoneTable(folder).orElse(null);
        } catch (IOException unreadable) {
            table = null;
        }
        var file = folder.resolve(RECORD);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Status(State.UNKNOWN, table, List.of());
        }
        var record = new Properties();
        try (var input = Files.newInputStream(file)) {
            record.load(input);
        }
        for (var name : List.of("pathnode.idx", "pathnode.bin")) {
            if (!matchesRecord(folder, name, record, cancelled)) {
                return new Status(State.UNKNOWN, table, List.of());
            }
        }
        var recorded = new TreeSet<String>();
        for (var name : record.getProperty("regions", "").split(",")) {
            if (!name.isBlank()) {
                recorded.add(name);
            }
        }
        var changed = new ArrayList<String>();
        var current = new TreeSet<>(table == null ? indexedRegions(folder) : graphRegions(folder, table));
        for (var name : current) {
            if (!recorded.contains(name) || !matchesRecord(folder, name, record, cancelled)) {
                changed.add(name);
            }
        }
        for (var name : recorded) {
            if (!current.contains(name)) {
                changed.add(name);
            }
        }
        return new Status(changed.isEmpty() ? State.CURRENT : State.STALE, table, changed);
    }

    public static Result rebuild(
            Path folder,
            @Nullable ZoneTable requested,
            boolean backup,
            LocalDateTime now,
            Control control,
            BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(control);
        Objects.requireNonNull(cancelled);
        var source = folder.toRealPath();
        var parent = source.getParent();
        if (parent == null) {
            throw new IOException("The geodata folder needs a parent folder for temporary files");
        }
        var table = requested != null ? requested : GeoPathNodeExporter.sourceZoneTable(source);
        var names = graphRegions(source, table);
        if (names.isEmpty()) {
            throw new IOException("The folder has no L2OFF regions for a pathnode graph");
        }
        var hashes = new LinkedHashMap<String, String>();
        for (var name : names) {
            control.checkpoint(hashes.size(), names.size(), "Reading " + name);
            hashes.put(name, GeoDataWriter.fileFingerprint(source.resolve(name)));
        }
        var stage = parent.resolve(".cruma-pathnode-" + UUID.randomUUID());
        Files.createDirectory(stage);
        try {
            GeoPathNodeGenerator.generate(source, names, stage, table, control);
            for (var entry : hashes.entrySet()) {
                if (!GeoDataWriter.fileFingerprint(source.resolve(entry.getKey()))
                        .equals(entry.getValue())) {
                    throw new IOException("A region changed while the graph was built: " + entry.getKey());
                }
            }
            Audit audit;
            try (var graph = PathNodeGraph.open(stage, cancelled)) {
                audit = graph.audit(cancelled);
            }
            if (audit.cancelled()) {
                throw new InterruptedIOException("Path-node rebuild stopped");
            }
            if (!audit.isValid()) {
                throw new IOException("Generated path-node graph is invalid: " + audit.issues());
            }
            writeRecord(stage, source, table, hashes);
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("Path-node rebuild stopped");
            }
            Path backupFolder = null;
            if (backup) {
                for (var name : FILES) {
                    var current = source.resolve(name);
                    if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                        if (backupFolder == null) {
                            backupFolder = GeoDataSourceSave.backupFolder(source, now);
                            Files.createDirectory(backupFolder);
                        }
                        Files.copy(current, backupFolder.resolve(name));
                    }
                }
            }
            var replaced = new ArrayList<Path>();
            for (var name : FILES) {
                replaced.add(GeoDataSourceSave.replace(source, name, stage.resolve(name)));
            }
            return new Result(source, table, audit, names.size(), replaced, backupFolder);
        } finally {
            GeoDataSourceSave.delete(stage);
        }
    }

    static void writeRecord(Path into, Path regions, ZoneTable table, Map<String, String> hashes) throws IOException {
        var record = new Properties();
        record.setProperty("algorithm", GeoPathNodeGenerator.ALGORITHM_ID);
        record.setProperty("zones", Integer.toString(table.getZones()));
        record.setProperty("regions", String.join(",", hashes.keySet()));
        for (var entry : hashes.entrySet()) {
            put(record, regions, entry.getKey(), entry.getValue());
        }
        for (var name : List.of("pathnode.idx", "pathnode.bin")) {
            put(record, into, name, GeoDataWriter.fileFingerprint(into.resolve(name)));
        }
        try (var output = Files.newOutputStream(into.resolve(RECORD))) {
            record.store(output, "The regions this pathnode graph was built from");
        }
    }

    static List<String> indexedRegions(Path folder) throws IOException {
        var index = folder.resolve("geo_index.txt");
        if (!Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
            var names = new TreeSet<String>();
            try (var files = Files.newDirectoryStream(folder)) {
                for (var file : files) {
                    var name = file.getFileName().toString();
                    if (REGION_FILE.matcher(name).matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        names.add(name);
                    }
                }
            }
            return List.copyOf(names);
        }
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS) || Files.size(index) > MAX_INDEX_BYTES) {
            throw new IOException(
                    "geo_index.txt is not a regular file or is larger than " + MAX_INDEX_BYTES + " bytes");
        }
        var names = new LinkedHashSet<String>();
        for (var token :
                Files.readString(index, StandardCharsets.US_ASCII).strip().split("\\s+")) {
            if (REGION_FILE.matcher(token).matches()) {
                names.add(token);
            }
        }
        return List.copyOf(names);
    }

    public static List<String> graphRegions(Path folder, ZoneTable table) throws IOException {
        var names = new ArrayList<String>();
        for (var name : indexedRegions(folder)) {
            if (Integer.parseInt(name.substring(3, 5)) <= table.lastTileY) {
                names.add(name);
            }
        }
        return names;
    }

    private static void put(Properties record, Path folder, String name, String hash) throws IOException {
        var file = folder.resolve(name);
        record.setProperty(name + ".sha256", hash);
        record.setProperty(name + ".size", Long.toString(Files.size(file)));
        record.setProperty(
                name + ".modified",
                Long.toString(Files.getLastModifiedTime(file).toMillis()));
    }

    private static boolean matchesRecord(Path folder, String name, Properties record, BooleanSupplier cancelled)
            throws IOException {
        var file = folder.resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Long.toString(Files.size(file)).equals(record.getProperty(name + ".size"))) {
            return false;
        }
        if (Long.toString(Files.getLastModifiedTime(file).toMillis()).equals(record.getProperty(name + ".modified"))) {
            return true;
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedIOException("Path-node check stopped");
        }
        return GeoDataWriter.fileFingerprint(file).equals(record.getProperty(name + ".sha256"));
    }
}

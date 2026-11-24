package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.generation.ClientGeodataBuild;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jetbrains.annotations.Nullable;

public final class GeoRegionCatalog implements AutoCloseable {
    public static final int COLUMNS = 20;

    public static final int ROWS = 17;

    public static final String REBUILT_FILE = "cruma-geodata-rebuilt.properties";

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "geodata-regions");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, String> hashes = new HashMap<>();
    private Future<?> pending;
    private volatile long generation;
    private String requested;
    private @Nullable Catalog catalog;

    public record Region(
            GeoTile tile,
            @Nullable String file,
            @Nullable GeoFormat format,
            long bytes,
            long layers,
            long modified,
            boolean built) {
        public boolean isPresent() {
            return file != null;
        }
    }

    public record Catalog(
            Path folder,
            List<Region> regions,
            long pathnodeModified,
            @Nullable Path folderB,
            Set<GeoTile> differs,
            boolean compared) {
        public Catalog {
            regions = List.copyOf(regions);
            differs = Set.copyOf(differs);
        }

        public Region region(GeoTile tile) {
            return regions.get(index(tile));
        }

        public boolean isPathnodeStale(GeoTile tile) {
            var region = region(tile);
            return pathnodeModified > 0 && region.isPresent() && region.modified() > pathnodeModified;
        }

        public int count() {
            var count = 0;
            for (var region : regions) {
                count += region.isPresent() ? 1 : 0;
            }
            return count;
        }
    }

    public static int index(GeoTile tile) {
        return (tile.x() - 10) * ROWS + tile.y() - 10;
    }

    public static GeoTile tileAt(int index) {
        return new GeoTile(index / ROWS + 10, index % ROWS + 10);
    }

    public static void markRebuilt(Path folder, Collection<GeoTile> tiles) throws IOException {
        if (tiles.isEmpty()) {
            return;
        }
        var names = new TreeSet<>(readRebuilt(folder));
        for (var tile : tiles) {
            names.add(tile.x() + "_" + tile.y());
        }
        var properties = new Properties();
        properties.setProperty("regions", String.join(",", names));
        properties.setProperty("builder", ClientGeodataBuild.BUILDER_ID);
        try (var output = Files.newOutputStream(folder.resolve(REBUILT_FILE))) {
            properties.store(output, "Regions of this folder rebuilt from the client's maps by Cruma");
        }
    }

    static Catalog read(Path folder, @Nullable Path folderB) throws IOException {
        var builtAll = Files.isRegularFile(folder.resolve(ClientGeodataBuild.SETTINGS));
        var rebuilt = readRebuilt(folder);
        var regions = new ArrayList<Region>();
        for (var index = 0; index < COLUMNS * ROWS; index++) {
            var tile = tileAt(index);
            var file = Files.isDirectory(folder) ? GeoFormat.find(folder, tile) : null;
            if (file == null) {
                regions.add(new Region(tile, null, null, 0, -1, 0, false));
                continue;
            }
            var named = GeoFormat.parse(file.getFileName().toString());
            var format = named == null ? GeoFormat.L2OFF : named.format();
            var bytes = Files.size(file);
            var modified = Files.getLastModifiedTime(file).toMillis();
            regions.add(new Region(
                    tile,
                    file.getFileName().toString(),
                    format,
                    bytes,
                    format == GeoFormat.L2OFF ? headerLayers(file) : -1,
                    modified,
                    builtAll || rebuilt.contains(tile.x() + "_" + tile.y())));
        }
        var pathnode = folder.resolve("pathnode.bin");
        var pathnodeModified = Files.isRegularFile(pathnode)
                ? Files.getLastModifiedTime(pathnode).toMillis()
                : 0;
        return new Catalog(folder, regions, pathnodeModified, folderB, Set.of(), false);
    }

    static long headerLayers(Path file) {
        try (var input = Files.newInputStream(file)) {
            var header = input.readNBytes(18);
            if (header.length < 18) {
                return -1;
            }
            var buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            return Integer.toUnsignedLong(buffer.getInt(6)) + Integer.toUnsignedLong(buffer.getInt(14)) * 64;
        } catch (IOException unreadable) {
            return -1;
        }
    }

    public synchronized void request(@Nullable Path folder, long reload, @Nullable Path folderB) {
        var key = folder + "|" + reload + "|" + folderB;
        if (key.equals(requested)) {
            return;
        }
        requested = key;
        if (pending != null) {
            pending.cancel(true);
        }
        if (folder == null) {
            catalog = null;
            return;
        }
        var generation = ++this.generation;
        pending = worker.submit(() -> {
            try {
                var read = read(folder, folderB);
                publish(generation, read);
                if (folderB != null) {
                    publish(generation, compare(read, folderB, generation));
                }
            } catch (IOException | RuntimeException failure) {
                publish(generation, new Catalog(folder, empty(), 0, folderB, Set.of(), false));
            }
        });
    }

    public synchronized @Nullable Catalog getCatalog() {
        return catalog;
    }

    @Override
    public synchronized void close() {
        generation++;
        worker.shutdownNow();
    }

    private Catalog compare(Catalog read, Path folderB, long generation) throws IOException {
        var differs = new TreeSet<GeoTile>(
                (a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.y(), b.y()));
        for (var region : read.regions()) {
            if (this.generation != generation || Thread.currentThread().isInterrupted()) {
                return read;
            }
            var other = Files.isDirectory(folderB) ? GeoFormat.find(folderB, region.tile()) : null;
            if (!region.isPresent() && other == null) {
                continue;
            }
            if (!region.isPresent() || other == null) {
                differs.add(region.tile());
                continue;
            }
            var mine = read.folder().resolve(region.file());
            if (Files.size(mine) != Files.size(other) || !hash(mine).equals(hash(other))) {
                differs.add(region.tile());
            }
        }
        return new Catalog(read.folder(), read.regions(), read.pathnodeModified(), folderB, differs, true);
    }

    private String hash(Path file) throws IOException {
        var key = file.toAbsolutePath()
                + "|"
                + Files.size(file)
                + "|"
                + Files.getLastModifiedTime(file).toMillis();
        synchronized (hashes) {
            var known = hashes.get(key);
            if (known != null) {
                return known;
            }
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            var buffer = new byte[1 << 16];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Comparing was stopped");
                }
                digest.update(buffer, 0, read);
            }
        }
        var value = HexFormat.of().formatHex(digest.digest());
        synchronized (hashes) {
            hashes.put(key, value);
        }
        return value;
    }

    private synchronized void publish(long generation, Catalog read) {
        if (this.generation == generation) {
            catalog = read;
        }
    }

    private static Set<String> readRebuilt(Path folder) {
        var file = folder.resolve(REBUILT_FILE);
        if (!Files.isRegularFile(file)) {
            return Set.of();
        }
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            properties.load(reader);
        } catch (IOException unreadable) {
            return Set.of();
        }
        var names = new TreeSet<String>();
        for (var name : properties.getProperty("regions", "").split(",")) {
            if (!name.isBlank()) {
                names.add(name.strip());
            }
        }
        return names;
    }

    private static List<Region> empty() {
        var regions = new ArrayList<Region>();
        for (var index = 0; index < COLUMNS * ROWS; index++) {
            regions.add(new Region(tileAt(index), null, null, 0, -1, 0, false));
        }
        return regions;
    }
}

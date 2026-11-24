package com.rost.cruma.core.geodata.generation;

import com.rost.cruma.core.geodata.GeoDataReader;
import com.rost.cruma.core.geodata.GeoDataRegion;
import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.generation.CollisionWorld.Builder;
import com.rost.cruma.core.geodata.generation.GeoWorldBuilder.Settings;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.MapCollision;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class ClientGeodataBuild {
    public static final String FOLDER = "geodata";

    public static final String SETTINGS = "cruma-geodata-build.properties";

    public static final String BUILDER_ID = "cruma-geodata-builder-2";

    private static final int MARGIN = 512;

    private static final Pattern REGION_FILE = Pattern.compile("\\d{2}_\\d{2}_conv\\.dat");

    private static final Logger log = LogManager.getLogger(ClientGeodataBuild.class);

    private int lastIndexedTileY = 26;

    private final Path folder;
    private final GeoFormat format;
    private final Settings settings;
    private final MapReader maps;
    private final ExtraStartPoints extraStarts;

    public ClientGeodataBuild(Path clientRoot, Path folder, int threads) {
        this(clientRoot, folder, GeoFormat.L2OFF, threads);
    }

    public ClientGeodataBuild(Path clientRoot, Path folder, GeoFormat format, int threads) {
        this(clientRoot, WorldVariant.MAIN, folder, format, threads);
    }

    public ClientGeodataBuild(Path clientRoot, WorldVariant variant, Path folder, GeoFormat format, int threads) {
        this(clientRoot, variant, folder, format, threads, ExtraStartPoints.NONE);
    }

    public ClientGeodataBuild(
            Path clientRoot,
            WorldVariant variant,
            Path folder,
            GeoFormat format,
            int threads,
            ExtraStartPoints extraStarts) {
        this(folder, format, Settings.forClient(threads), clientMaps(clientRoot, variant), extraStarts);
    }

    ClientGeodataBuild(Path folder, GeoFormat format, Settings settings, MapReader maps) {
        this(folder, format, settings, maps, ExtraStartPoints.NONE);
    }

    ClientGeodataBuild(Path folder, GeoFormat format, Settings settings, MapReader maps, ExtraStartPoints extraStarts) {
        this.folder = Objects.requireNonNull(folder);
        this.format = Objects.requireNonNull(format);
        this.settings = Objects.requireNonNull(settings);
        this.maps = Objects.requireNonNull(maps);
        this.extraStarts = Objects.requireNonNull(extraStarts);
    }

    @FunctionalInterface
    interface MapReader {
        @Nullable
        MapCollision read(GeoTile tile) throws IOException;
    }

    public interface Listener {
        void started(GeoTile tile, int index, int total);

        void progress(GeoTile tile, String stage, double fraction);

        void finished(GeoTile tile, @Nullable Path file);

        default void failed(GeoTile tile, Exception failure) {}
    }

    public static int defaultThreads() {
        var logical = Runtime.getRuntime().availableProcessors();
        var arch = System.getProperty("os.arch", "");
        var twoPerCore = arch.contains("amd64") || arch.contains("x86");
        return Math.max(1, twoPerCore ? logical / 2 : logical - 2);
    }

    public static Path outputFolder(Path clientRoot) {
        return clientRoot.resolve(FOLDER);
    }

    public static Path outputFolder(Path clientRoot, GeoFormat format) {
        return outputFolder(clientRoot, format, WorldVariant.MAIN);
    }

    public static Path outputFolder(Path clientRoot, GeoFormat format, WorldVariant variant) {
        var name = format == GeoFormat.L2OFF
                ? FOLDER
                : FOLDER + "_" + format.name().toLowerCase(Locale.ROOT);
        return clientRoot.resolve(name + variant.getMapSuffix().toLowerCase(Locale.ROOT));
    }

    public static List<GeoTile> findRegions(Path clientRoot, GeoTile near) throws IOException {
        return findRegions(clientRoot, WorldVariant.MAIN, near);
    }

    public static List<GeoTile> findRegions(Path clientRoot, WorldVariant variant, GeoTile near) throws IOException {
        var tiles = new ArrayList<GeoTile>();
        try {
            for (var name : WorldMapCatalog.discover(clientRoot, variant)) {
                var map = WorldTile.fromMapName(name);
                var tile = new GeoTile(map.x(), map.y());
                if (tile.containsWorld()) {
                    tiles.add(tile);
                }
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
        tiles.sort(Comparator.<GeoTile>comparingInt(tile -> squaredDistance(tile, near))
                .thenComparingInt(GeoTile::x)
                .thenComparingInt(GeoTile::y));
        return tiles;
    }

    public static List<GeoTile> findMissingRegions(Path clientRoot, Path folder, GeoTile near) throws IOException {
        return findMissingRegions(clientRoot, folder, GeoFormat.L2OFF, near);
    }

    public static List<GeoTile> findMissingRegions(Path clientRoot, Path folder, GeoFormat format, GeoTile near)
            throws IOException {
        return findMissingRegions(clientRoot, WorldVariant.MAIN, folder, format, near);
    }

    public static List<GeoTile> findMissingRegions(
            Path clientRoot, WorldVariant variant, Path folder, GeoFormat format, GeoTile near) throws IOException {
        var missing = new ArrayList<GeoTile>();
        for (var tile : findRegions(clientRoot, variant, near)) {
            if (!Files.isRegularFile(folder.resolve(format.getFileName(tile)))) {
                missing.add(tile);
            }
        }
        return missing;
    }

    public static @Nullable GeoDataRegion buildRegion(
            Path clientRoot, GeoTile tile, int threads, Listener listener, BooleanSupplier cancelled)
            throws IOException {
        return buildRegion(clientRoot, WorldVariant.MAIN, tile, threads, listener, cancelled);
    }

    public static @Nullable GeoDataRegion buildRegion(
            Path clientRoot,
            WorldVariant variant,
            GeoTile tile,
            int threads,
            Listener listener,
            BooleanSupplier cancelled)
            throws IOException {
        return buildRegion(clientRoot, variant, tile, threads, ExtraStartPoints.NONE, listener, cancelled);
    }

    public static @Nullable GeoDataRegion buildRegion(
            Path clientRoot,
            WorldVariant variant,
            GeoTile tile,
            int threads,
            ExtraStartPoints extraStarts,
            Listener listener,
            BooleanSupplier cancelled)
            throws IOException {
        var build = new ClientGeodataBuild(clientRoot, variant, clientRoot, GeoFormat.L2OFF, threads, extraStarts);
        listener.started(tile, 0, 1);
        listener.progress(tile, "Reading the map", 0);
        var prepared = build.prepare(tile);
        if (prepared == null) {
            listener.finished(tile, null);
            return null;
        }
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Geodata build stopped");
        }
        var region = GeoWorldBuilder.build(
                prepared.world(),
                tile.x(),
                tile.y(),
                build.settings,
                prepared.starts(),
                build.extraStarts.forRegion(tile),
                (stage, done, total) -> listener.progress(tile, stage, workFraction(stage, done, total)),
                cancelled);
        var result = GeoDataReader.read(ServerRegion.convert(region).encode());
        listener.finished(tile, null);
        return result;
    }

    private record Prepared(CollisionWorld world, float[] starts) {}

    public void run(List<GeoTile> tiles, Listener listener, BooleanSupplier cancelled) throws IOException {
        run(tiles, false, listener, cancelled);
    }

    public void run(List<GeoTile> tiles, boolean replace, Listener listener, BooleanSupplier cancelled)
            throws IOException {
        Files.createDirectories(folder);
        var saved = new Properties();
        saved.setProperty("builder", BUILDER_ID);
        saved.setProperty("format", format.name());
        if (!extraStarts.describe().isEmpty()) {
            saved.setProperty("start_points", extraStarts.describe());
        }
        var text = new StringWriter();
        saved.store(text, "Geodata built by Cruma from the client's maps");
        write(folder.resolve(SETTINGS), text.toString().getBytes(StandardCharsets.ISO_8859_1));
        try (var reader =
                Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            Future<Prepared> next = null;
            var nextIndex = -1;
            for (var index = 0; index < tiles.size(); index++) {
                var tile = tiles.get(index);
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Geodata build stopped");
                }
                var target = folder.resolve(format.getFileName(tile));
                if (!replace && Files.isRegularFile(target)) {
                    continue;
                }
                listener.started(tile, index, tiles.size());
                listener.progress(tile, "Reading the map", 0);
                var read = next != null && nextIndex == index ? next : null;
                next = null;
                Path file;
                try {
                    var prepared = read != null ? await(read) : prepare(tile);
                    nextIndex = nextMissingIndex(tiles, index, replace);
                    if (nextIndex >= 0) {
                        var following = tiles.get(nextIndex);
                        next = reader.submit(() -> prepare(following));
                    }
                    file = prepared != null && build(tile, prepared, listener, cancelled) ? target : null;
                } catch (IOException | RuntimeException failure) {
                    if (failure instanceof CancellationException stopped) {
                        throw stopped;
                    }
                    log.warn(
                            "Could not build geodata region {}: {}",
                            format.getFileName(tile),
                            failure.toString(),
                            failure);
                    listener.failed(tile, failure);
                    continue;
                }
                if (file != null) {
                    writeIndex();
                }
                listener.finished(tile, file);
            }
        }
    }

    public void setLastIndexedTileY(int lastTileY) {
        this.lastIndexedTileY = lastTileY;
    }

    public void writeIndex() throws IOException {
        if (format != GeoFormat.L2OFF || !Files.isDirectory(folder)) {
            return;
        }
        var names = new TreeSet<String>();
        try (var files = Files.newDirectoryStream(folder, "*_conv.dat")) {
            for (var file : files) {
                var matcher = REGION_FILE.matcher(file.getFileName().toString());
                if (matcher.matches()
                        && Integer.parseInt(file.getFileName().toString().substring(3, 5)) <= lastIndexedTileY) {
                    names.add(file.getFileName().toString());
                }
            }
        }
        var index = new StringBuilder().append(names.size()).append('\n');
        for (var name : names) {
            index.append(name).append('\n');
        }
        write(folder.resolve("geo_index.txt"), index.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private @Nullable Prepared prepare(GeoTile tile) throws IOException {
        var collision = maps.read(tile);
        if (collision == null || collision.triangleCount() == 0) {
            return null;
        }
        var builder = new Builder();
        ClientCollision.addMapCollision(builder, collision);
        return new Prepared(
                ClientCollision.buildRegionWorld(builder, tile.x(), tile.y(), MARGIN), collision.playerStarts());
    }

    private int nextMissingIndex(List<GeoTile> tiles, int index, boolean replace) {
        for (var i = index + 1; i < tiles.size(); i++) {
            if (replace || !Files.isRegularFile(folder.resolve(format.getFileName(tiles.get(i))))) {
                return i;
            }
        }
        return -1;
    }

    private boolean build(GeoTile tile, Prepared prepared, Listener listener, BooleanSupplier cancelled)
            throws IOException {
        var region = GeoWorldBuilder.build(
                prepared.world(),
                tile.x(),
                tile.y(),
                settings,
                prepared.starts(),
                extraStarts.forRegion(tile),
                (stage, done, total) -> listener.progress(tile, stage, workFraction(stage, done, total)),
                cancelled);
        var bytes = ServerRegion.convert(region).encode();
        if (format != GeoFormat.L2OFF) {
            bytes = format.encode(GeoDataReader.read(bytes));
        }
        write(folder.resolve(format.getFileName(tile)), bytes);
        return true;
    }

    private static MapReader clientMaps(Path clientRoot, WorldVariant variant) {
        Objects.requireNonNull(clientRoot);
        Objects.requireNonNull(variant);
        return tile -> {
            var loader = new UnrealLoader(clientRoot, new Options(false, 0), false);
            try {
                loader.worldVariant(variant);
                return loader.loadMapCollision(tile.x() + "_" + tile.y());
            } finally {
                loader.trimCaches();
            }
        };
    }

    private static Prepared await(Future<Prepared> next) throws IOException {
        try {
            return next.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Geodata build interrupted");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) {
                throw io;
            }
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(failure.getCause());
        }
    }

    private static double workFraction(String stage, long done, long total) {
        var part = total <= 0 ? 0 : Math.min(1, (double) done / total);
        if (stage.startsWith("Dropping")) {
            return .1 * part;
        }
        if (stage.startsWith("Walking")) {
            return .1 + .8 * part;
        }
        return .9 + .1 * part;
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        var temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int squaredDistance(GeoTile tile, GeoTile near) {
        var dx = tile.x() - near.x();
        var dy = tile.y() - near.y();
        return dx * dx + dy * dy;
    }
}

package com.rost.cruma.core.world;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class WorldMapCatalog {
    private static final Pattern TILE =
            Pattern.compile("(0|[1-9][0-9]*)_(0|[1-9][0-9]*)(_classic)?\\.unr", Pattern.CASE_INSENSITIVE);

    private static final String SKY = "SkyLevel";

    private static final String ARENA = "_Arena";
    private static final Comparator<Tile> ORDER =
            Comparator.comparingInt(Tile::x).thenComparingInt(Tile::y);

    private WorldMapCatalog() {}

    private record Tile(int x, int y) {
        @Override
        public String toString() {
            return x + "_" + y;
        }
    }

    public static List<String> discover(Path clientRoot) {
        return discover(clientRoot, WorldVariant.MAIN);
    }

    public static List<String> discover(Path clientRoot, WorldVariant variant) {
        return discover(
                clientRoot, variant, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static List<String> discover(Path clientRoot, int minX, int maxX, int minY, int maxY) {
        return discover(clientRoot, WorldVariant.MAIN, minX, maxX, minY, maxY);
    }

    public static List<String> discover(Path clientRoot, WorldVariant variant, int minX, int maxX, int minY, int maxY) {
        if (minX > maxX || minY > maxY) {
            throw new IllegalArgumentException("Inverted world tile bounds");
        }
        return findPackages(clientRoot, variant).keySet().stream()
                .filter(name -> {
                    var tile = WorldTile.fromMapName(name);
                    return tile.x() >= minX && tile.x() <= maxX && tile.y() >= minY && tile.y() <= maxY;
                })
                .toList();
    }

    public static Map<String, String> findPackages(Path clientRoot, WorldVariant variant) {
        var main = new TreeMap<Tile, String>(ORDER);
        var classic = new TreeMap<Tile, String>(ORDER);
        scan(clientRoot, main, classic);
        if (variant.getMapWorld() == WorldVariant.CLASSIC) {
            for (var entry : classic.entrySet()) {
                main.put(entry.getKey(), entry.getValue());
            }
        }
        var result = new LinkedHashMap<String, String>();
        for (var entry : main.entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue());
        }
        return result;
    }

    public static boolean hasClassic(Path clientRoot) {
        var classic = new TreeMap<Tile, String>(ORDER);
        scan(clientRoot, new TreeMap<>(ORDER), classic);
        return !classic.isEmpty();
    }

    public static List<String> namedMaps(Path clientRoot) {
        var names = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (var directory : mapDirectories(clientRoot)) {
            try (var files = Files.list(directory)) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    var fileName = file.getFileName().toString();
                    if (!fileName.toLowerCase(Locale.ROOT).endsWith(".unr")) {
                        continue;
                    }
                    var name = fileName.substring(0, fileName.length() - ".unr".length());
                    if (!WorldTile.isMapName(name) && !name.equalsIgnoreCase(SKY)) {
                        names.putIfAbsent(name, name);
                    }
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot list client maps in " + directory, failure);
            }
        }
        return List.copyOf(names.values());
    }

    public static @Nullable String arenaMap(Path clientRoot, String tile) {
        for (var name : namedMaps(clientRoot)) {
            if (name.equalsIgnoreCase(tile + ARENA)) {
                return name;
            }
        }
        return null;
    }

    public static boolean hasSharedSky(Path clientRoot) {
        for (var directory : mapDirectories(clientRoot)) {
            try (var files = Files.list(directory)) {
                if (files.anyMatch(file -> file.getFileName().toString().equalsIgnoreCase(SKY + ".unr"))) {
                    return true;
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot list client maps in " + directory, failure);
            }
        }
        return false;
    }

    public static @Nullable String findStandaloneMap(List<String> maps) {
        return maps.size() == 1 && !WorldTile.isMapName(maps.getFirst()) ? maps.getFirst() : null;
    }

    private static List<Path> mapDirectories(Path clientRoot) {
        if (!Files.isDirectory(clientRoot)) {
            return List.of();
        }
        try (var directories = Files.list(clientRoot)) {
            return directories
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("maps"))
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot list client world maps in " + clientRoot, failure);
        }
    }

    private static void scan(Path clientRoot, Map<Tile, String> main, Map<Tile, String> classic) {
        try {
            for (var directory : mapDirectories(clientRoot)) {
                try (var files = Files.list(directory)) {
                    for (var file : files.filter(Files::isRegularFile).toList()) {
                        var fileName = file.getFileName().toString();
                        var match = TILE.matcher(fileName);
                        if (!match.matches()) {
                            continue;
                        }
                        final int x, y;
                        try {
                            x = Integer.parseInt(match.group(1));
                            y = Integer.parseInt(match.group(2));
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        var name = fileName.substring(0, fileName.length() - ".unr".length());
                        (match.group(3) == null ? main : classic).putIfAbsent(new Tile(x, y), name);
                    }
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot list client world maps in " + clientRoot, failure);
        }
    }
}

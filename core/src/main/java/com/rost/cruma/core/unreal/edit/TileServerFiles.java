package com.rost.cruma.core.unreal.edit;

import com.rost.cruma.core.geodata.GeoDataRegion;
import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.core.spawn.NpcPosFile;
import com.rost.cruma.core.spawn.NpcPosWriter;
import com.rost.cruma.core.spawn.SpawnTileMove;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Extra;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Kind;
import com.rost.cruma.core.unreal.edit.MapTileOperation.TileRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class TileServerFiles {
    private static final String INDEX = "geo_index.txt";
    private static final String PATH_NODES = "pathnode.idx";

    private TileServerFiles() {}

    public record Part(List<Extra> extras, String summary, boolean warning) {}

    public static Part planGeodata(Path folder, Kind kind, TileRef source, TileRef target, boolean replace)
            throws IOException {
        var sourceFile = GeoFormat.find(folder, new GeoTile(source.x(), source.y()));
        if (sourceFile == null) {
            return new Part(List.of(), "The geodata folder has no region " + source.x() + "_" + source.y() + ".", true);
        }
        var format = GeoFormat.parse(sourceFile.getFileName().toString()).format();
        var targetFile = folder.resolve(format.getFileName(new GeoTile(target.x(), target.y())));
        var targetExists = Files.isRegularFile(targetFile);
        if (kind == Kind.SWAP && !targetExists) {
            return new Part(
                    List.of(),
                    "The geodata folder has no region " + target.x() + "_" + target.y() + " to swap with.",
                    true);
        }
        if (kind != Kind.SWAP && targetExists && !replace) {
            return new Part(
                    List.of(),
                    targetFile.getFileName() + " is already in the geodata folder, so the geodata stays.",
                    true);
        }
        var extras = new ArrayList<Extra>();
        extras.add(new Extra(
                targetFile,
                null,
                retile(Files.readAllBytes(sourceFile), format, target),
                "geodata/" + targetFile.getFileName(),
                "geodata " + targetFile.getFileName()));
        if (kind == Kind.SWAP) {
            extras.add(new Extra(
                    sourceFile,
                    null,
                    retile(Files.readAllBytes(targetFile), format, source),
                    "geodata/" + sourceFile.getFileName(),
                    "geodata " + sourceFile.getFileName()));
        } else if (kind == Kind.MOVE) {
            extras.add(new Extra(sourceFile, null, null, "", "geodata " + sourceFile.getFileName()));
        }
        var index = index(
                folder,
                format,
                kind,
                sourceFile.getFileName().toString(),
                targetFile.getFileName().toString());
        if (index != null) {
            extras.add(index);
        }
        var verb = switch (kind) {
            case COPY -> "Copies ";
            case MOVE -> "Moves ";
            case SWAP -> "Swaps ";
        };
        var summary = verb
                + "geodata "
                + sourceFile.getFileName()
                + (kind == Kind.SWAP ? " and " : " to ")
                + targetFile.getFileName()
                + ".";
        return new Part(List.copyOf(extras), summary, false);
    }

    @FunctionalInterface
    public interface RegionBuilder {
        @Nullable
        GeoDataRegion build(Path clientRoot, TileRef tile) throws IOException;
    }

    public static Part plannedGeodata(Path folder, Kind kind, TileRef source, TileRef target, boolean replace)
            throws IOException {
        var files = regionFiles(folder, source, target);
        if (kind != Kind.SWAP && Files.isRegularFile(files.target()) && !replace) {
            return new Part(
                    List.of(),
                    files.target().getFileName() + " is already in the geodata folder, so the geodata stays.",
                    true);
        }
        var summary = kind == Kind.SWAP
                ? "Builds geodata "
                        + files.source().getFileName()
                        + " and "
                        + files.target().getFileName()
                        + " from each other's map when it runs."
                : "Builds geodata "
                        + files.target().getFileName()
                        + " from the map of "
                        + source.x()
                        + "_"
                        + source.y()
                        + " when it runs.";
        if (kind == Kind.MOVE && Files.isRegularFile(files.source())) {
            summary += " Removes " + files.source().getFileName() + ".";
        }
        return new Part(List.of(), summary + " A region takes a few seconds.", false);
    }

    public static Part builtGeodata(
            Path folder,
            Kind kind,
            Path sourceRoot,
            TileRef source,
            Path clientRoot,
            TileRef target,
            boolean replace,
            RegionBuilder builder)
            throws IOException {
        var files = regionFiles(folder, source, target);
        if (kind != Kind.SWAP && Files.isRegularFile(files.target()) && !replace) {
            return new Part(
                    List.of(),
                    files.target().getFileName() + " is already in the geodata folder, so the geodata stays.",
                    true);
        }
        var extras = new ArrayList<Extra>();
        var built = new ArrayList<String>();
        var empty = new ArrayList<String>();
        var forTarget = builder.build(sourceRoot, source);
        if (forTarget != null) {
            extras.add(newRegionExtra(files.format(), forTarget, files.target(), target));
            built.add(files.target().getFileName().toString());
        } else {
            empty.add(source.x() + "_" + source.y());
        }
        if (kind == Kind.SWAP) {
            var forSource = builder.build(clientRoot, target);
            if (forSource != null) {
                extras.add(newRegionExtra(files.format(), forSource, files.source(), source));
                built.add(files.source().getFileName().toString());
            } else {
                empty.add(target.x() + "_" + target.y());
            }
        } else if (kind == Kind.MOVE && forTarget != null && Files.isRegularFile(files.source())) {
            extras.add(new Extra(
                    files.source(), null, null, "", "geodata " + files.source().getFileName()));
        }
        if (forTarget != null) {
            var index = index(
                    folder,
                    files.format(),
                    kind,
                    files.source().getFileName().toString(),
                    files.target().getFileName().toString());
            if (index != null) {
                extras.add(index);
            }
        }
        var summary = built.isEmpty()
                ? "No geodata was built."
                : "Built geodata " + String.join(" and ", built) + " from the map" + (built.size() == 1 ? "." : "s.");
        if (!empty.isEmpty()) {
            summary += " The map of " + String.join(" and ", empty) + " has nothing to build, so that region stays.";
        }
        return new Part(List.copyOf(extras), summary, !empty.isEmpty());
    }

    public static boolean hasPathNodes(Path folder) {
        return Files.isRegularFile(folder.resolve(PATH_NODES));
    }

    private record RegionFiles(GeoFormat format, Path source, Path target) {}

    public static Part planSpawns(Path scripts, Kind kind, TileRef source, TileRef target) throws IOException {
        var file = find(scripts, "npcpos.txt");
        if (file == null) {
            return new Part(List.of(), "The script folder has no npcpos.txt.", true);
        }
        var positions = NpcPosFile.read(file);
        var state = new NpcPosDocument(positions).getState();
        var result = switch (kind) {
            case COPY -> SpawnTileMove.copy(state, source.x(), source.y(), target.x(), target.y());
            case MOVE -> SpawnTileMove.move(state, source.x(), source.y(), target.x(), target.y());
            case SWAP -> SpawnTileMove.swap(state, source.x(), source.y(), target.x(), target.y());
        };
        if (result.zones() == 0 && result.makers() == 0) {
            return new Part(
                    List.of(),
                    "No spawns lie inside "
                            + source.x()
                            + "_"
                            + source.y()
                            + (kind == Kind.SWAP ? " or " + target.x() + "_" + target.y() : "")
                            + ".",
                    false);
        }
        var bytes = NpcPosWriter.write(positions, result.state()).encode();
        var verb = switch (kind) {
            case COPY -> "Copies ";
            case MOVE -> "Moves ";
            case SWAP -> "Swaps ";
        };
        var summary = verb
                + count(result.zones(), "zone")
                + " and "
                + count(result.makers(), "maker")
                + " of npcpos.txt"
                + (kind == Kind.COPY ? ", with new names." : ".");
        return new Part(
                List.of(new Extra(
                        file,
                        null,
                        bytes,
                        "script/" + file.getFileName(),
                        file.getFileName().toString())),
                summary,
                false);
    }

    private static RegionFiles regionFiles(Path folder, TileRef source, TileRef target) throws IOException {
        var format = formatOf(folder, source, target);
        return new RegionFiles(
                format,
                folder.resolve(format.getFileName(new GeoTile(source.x(), source.y()))),
                folder.resolve(format.getFileName(new GeoTile(target.x(), target.y()))));
    }

    private static GeoFormat formatOf(Path folder, TileRef source, TileRef target) throws IOException {
        for (var tile : List.of(source, target)) {
            var file = GeoFormat.find(folder, new GeoTile(tile.x(), tile.y()));
            if (file != null) {
                return GeoFormat.parse(file.getFileName().toString()).format();
            }
        }
        if (Files.isDirectory(folder)) {
            try (var entries = Files.list(folder)) {
                for (var entry : entries.toList()) {
                    var named = GeoFormat.parse(entry.getFileName().toString());
                    if (named != null) {
                        return named.format();
                    }
                }
            }
        }
        return GeoFormat.L2OFF;
    }

    private static Extra newRegionExtra(GeoFormat format, GeoDataRegion region, Path file, TileRef tile)
            throws IOException {
        return new Extra(
                file,
                null,
                retile(format.encode(region), format, tile),
                "geodata/" + file.getFileName(),
                "geodata " + file.getFileName());
    }

    private static byte[] retile(byte[] bytes, GeoFormat format, TileRef tile) {
        if (format == GeoFormat.L2OFF && bytes.length >= 2) {
            bytes[0] = (byte) tile.x();
            bytes[1] = (byte) tile.y();
        }
        return bytes;
    }

    private static @Nullable Extra index(Path folder, GeoFormat format, Kind kind, String sourceName, String targetName)
            throws IOException {
        var file = find(folder, INDEX);
        if (file == null || format != GeoFormat.L2OFF || kind == Kind.SWAP) {
            return null;
        }
        var names = new ArrayList<String>();
        var lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        for (var index = 1; index < lines.size(); index++) {
            var name = lines.get(index).strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        var changed = false;
        if (kind == Kind.MOVE) {
            changed |= names.removeIf(name -> name.equalsIgnoreCase(sourceName));
        }
        if (names.stream().noneMatch(name -> name.equalsIgnoreCase(targetName))) {
            names.add(targetName);
            changed = true;
        }
        if (!changed) {
            return null;
        }
        var text = new StringBuilder().append(names.size()).append('\n');
        for (var name : names) {
            text.append(name).append('\n');
        }
        return new Extra(
                file,
                null,
                text.toString().getBytes(StandardCharsets.ISO_8859_1),
                "geodata/" + file.getFileName(),
                file.getFileName().toString());
    }

    private static @Nullable Path find(Path folder, String name) throws IOException {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (var entries = Files.list(folder)) {
            return entries.filter(path -> path.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .equals(name.toLowerCase(Locale.ROOT)))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        }
    }

    private static String count(int value, String noun) {
        return value + " " + noun + (value == 1 ? "" : "s");
    }
}

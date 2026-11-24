package com.rost.cruma.core.unreal.edit;

import com.rost.cruma.core.unreal.ClientFiles;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Extra;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

public final class TilePackages {
    private static final Set<String> EXTENSIONS = Set.of("u", "usx", "utx", "uax", "ukx", "unr", "umx");

    private TilePackages() {}

    public record Part(List<Extra> extras, List<String> notes, List<String> warnings) {}

    public static Part plan(Path sourceRoot, Path clientRoot, Path sourceMap) throws IOException {
        var source = index(sourceRoot);
        var target = index(clientRoot);
        var extras = new ArrayList<Extra>();
        var differ = new TreeSet<String>();
        var code = new TreeSet<String>();
        var nowhere = new TreeSet<String>();
        var seen = new HashSet<String>();
        var spelled = new HashMap<String, String>();
        var queue = new ArrayDeque<String>(readImports(sourceRoot, sourceMap, spelled));
        while (!queue.isEmpty()) {
            var name = queue.poll();
            if (!seen.add(name)) {
                continue;
            }
            var from = source.get(name);
            var here = target.get(name);
            if (here != null) {
                if (from != null && Files.size(from) != Files.size(here)) {
                    differ.add(toRelative(clientRoot, here));
                }
                continue;
            }
            if (from == null) {
                nowhere.add(spelled.getOrDefault(name, name));
                continue;
            }
            var file = from.getFileName().toString();
            if (file.toLowerCase(Locale.ROOT).endsWith(".u")) {
                code.add(file);
                continue;
            }
            var folder = findFolder(clientRoot, from.getParent().getFileName().toString());
            var patchPath = folder.getFileName() + "/" + file;
            extras.add(new Extra(folder.resolve(file), from, null, patchPath, patchPath));
            queue.addAll(readImports(sourceRoot, from, spelled));
        }
        var notes = new ArrayList<String>();
        if (!extras.isEmpty()) {
            var names = extras.stream().map(Extra::label).toList();
            notes.add("Copies "
                    + extras.size()
                    + (extras.size() == 1 ? " package" : " packages")
                    + " this client lacks: "
                    + shortList(names)
                    + ".");
        }
        var warnings = new ArrayList<String>();
        if (!differ.isEmpty()) {
            warnings.add("This client has its own "
                    + shortList(List.copyOf(differ))
                    + ", which differ"
                    + (differ.size() == 1 ? "s" : "")
                    + " from the other client's. They stay, so the map may look different.");
        }
        if (!code.isEmpty()) {
            warnings.add("The map needs code packages this client lacks, which are not copied: "
                    + shortList(List.copyOf(code)) + ".");
        }
        if (!nowhere.isEmpty()) {
            warnings.add("Neither client has " + shortList(List.copyOf(nowhere)) + ".");
        }
        var version = readVersion(sourceMap);
        var ownVersion = newestMapVersion(clientRoot);
        if (version > 0 && ownVersion > 0 && version > ownVersion) {
            warnings.add("The map is from a newer client: package version " + version
                    + ", while this client's maps are " + ownVersion + ". The game may not load it.");
        }
        return new Part(List.copyOf(extras), List.copyOf(notes), List.copyOf(warnings));
    }

    static Map<String, Path> index(Path root) throws IOException {
        var result = new LinkedHashMap<String, Path>();
        try (var folders = Files.list(root)) {
            for (var folder : (Iterable<Path>) folders::iterator) {
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                try (var files = Files.list(folder)) {
                    for (var file : (Iterable<Path>) files::iterator) {
                        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        var dot = name.lastIndexOf('.');
                        if (dot > 0 && EXTENSIONS.contains(name.substring(dot + 1)) && Files.isRegularFile(file)) {
                            result.putIfAbsent(name.substring(0, dot), file);
                        }
                    }
                } catch (IOException unreadable) {
                }
            }
        }
        return result;
    }

    private static List<String> readImports(Path root, Path file, Map<String, String> spelled) {
        var name = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        var loader = MapTileShift.newLoader(root);
        try {
            var archive = new UArchive(name, UDecryptor.decrypt(file), loader);
            var result = new ArrayList<String>();
            for (var entry : archive.getImportMap()) {
                var imported = entry.getObjectName().toLowerCase(Locale.ROOT);
                if (entry.getPackageIndex() == 0 && !imported.equals(name.toLowerCase(Locale.ROOT))) {
                    result.add(imported);
                    spelled.putIfAbsent(imported, entry.getObjectName());
                }
            }
            return result;
        } catch (RuntimeException unreadable) {
            return List.of();
        } finally {
            loader.clearArchives();
        }
    }

    private static int readVersion(Path file) {
        var loader = MapTileShift.newLoader(file.getParent().getParent());
        try {
            return new UArchive(
                            file.getFileName().toString().replaceFirst("\\.[^.]+$", ""),
                            UDecryptor.decrypt(file),
                            loader)
                    .getHeader()
                    .getFileVersion();
        } catch (RuntimeException unreadable) {
            return 0;
        } finally {
            loader.clearArchives();
        }
    }

    private static int newestMapVersion(Path root) {
        try {
            var maps = MapTileOperation.mapsFolder(root);
            try (var files = Files.list(maps)) {
                return files.filter(file -> !ClientFiles.isAppleDouble(file)
                                && file.getFileName()
                                        .toString()
                                        .toLowerCase(Locale.ROOT)
                                        .endsWith(".unr"))
                        .limit(3)
                        .mapToInt(TilePackages::readVersion)
                        .max()
                        .orElse(0);
            }
        } catch (IOException unreadable) {
            return 0;
        }
    }

    private static Path findFolder(Path root, String name) throws IOException {
        try (var folders = Files.list(root)) {
            for (var folder : (Iterable<Path>) folders::iterator) {
                if (Files.isDirectory(folder) && folder.getFileName().toString().equalsIgnoreCase(name)) {
                    return folder;
                }
            }
        }
        return root.resolve(name);
    }

    private static String toRelative(Path root, @Nullable Path file) {
        return file == null ? "" : root.relativize(file).toString().replace('\\', '/');
    }

    private static String shortList(List<String> names) {
        return names.size() <= 4
                ? String.join(", ", names)
                : String.join(", ", names.subList(0, 4)) + " and " + (names.size() - 4) + " more";
    }
}

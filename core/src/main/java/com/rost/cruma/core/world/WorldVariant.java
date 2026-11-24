package com.rost.cruma.core.world;

import com.rost.cruma.core.schema.TableFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public enum WorldVariant {
    MAIN("", "Main"),
    CLASSIC("_Classic", "Classic"),
    CLASSIC_ADEN("_ClassicAden", "Essence");

    private final String suffix;
    private final String label;

    WorldVariant(String suffix, String label) {
        this.suffix = suffix;
        this.label = label;
    }

    public static WorldVariant parse(String text) {
        for (var variant : values()) {
            if (variant.getKey().equalsIgnoreCase(text.strip())) {
                return variant;
            }
        }
        throw new IllegalArgumentException("World variant must be " + describeKeys() + ", got " + text);
    }

    public static String describeKeys() {
        var keys = new ArrayList<String>();
        for (var variant : values()) {
            keys.add(variant.getKey());
        }
        return String.join(", ", keys.subList(0, keys.size() - 1)) + " or " + keys.getLast();
    }

    public static List<WorldVariant> findWorlds(Path clientRoot) {
        var worlds = new ArrayList<WorldVariant>(values().length);
        for (var world : values()) {
            if (world.isOfferedBy(clientRoot)) {
                worlds.add(world);
            }
        }
        return List.copyOf(worlds);
    }

    public boolean isOfferedBy(Path clientRoot) {
        return switch (this) {
            case MAIN -> true;
            case CLASSIC -> WorldMapCatalog.hasClassic(clientRoot);
            case CLASSIC_ADEN -> TableFiles.hasOwnTables(clientRoot.resolve("system"), CLASSIC_ADEN);
        };
    }

    public String getTableSuffix() {
        return suffix;
    }

    public String getMapSuffix() {
        return getMapWorld().suffix;
    }

    public WorldVariant getMapWorld() {
        return this == CLASSIC_ADEN ? CLASSIC : this;
    }

    public List<WorldVariant> getTableWorlds() {
        return switch (this) {
            case MAIN -> List.of(MAIN);
            case CLASSIC -> List.of(CLASSIC, MAIN);
            case CLASSIC_ADEN -> List.of(CLASSIC_ADEN, CLASSIC, MAIN);
        };
    }

    public String getKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String getLabel() {
        return label;
    }

    public String getFileName(String name) {
        if (this == MAIN) {
            return name;
        }
        var dot = name.lastIndexOf('.');
        var stem = dot < 0 ? name : name.substring(0, dot);
        var extension = dot < 0 ? "" : name.substring(dot);
        var dash = stem.lastIndexOf('-');
        if (dash > 0) {
            return stem.substring(0, dash) + suffix + stem.substring(dash) + extension;
        }
        return stem + suffix + extension;
    }

    public @Nullable Path find(Path folder, String name) {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        var worlds = getTableWorlds();
        var wanted = new String[worlds.size()];
        for (var index = 0; index < wanted.length; index++) {
            wanted[index] = worlds.get(index).getFileName(name);
        }
        var found = new Path[wanted.length];
        try (var entries = Files.list(folder)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                var file = entry.getFileName().toString();
                for (var index = 0; index < wanted.length; index++) {
                    if (found[index] == null && file.equalsIgnoreCase(wanted[index])) {
                        found[index] = entry;
                    }
                }
            }
        } catch (IOException unreadable) {
            return null;
        }
        for (var file : found) {
            if (file != null) {
                return file;
            }
        }
        return null;
    }
}

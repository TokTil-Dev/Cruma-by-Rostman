package com.rost.cruma.viewer.launch;

import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ConfigurationProblem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record ClientInstallation(Path root, List<String> tiles, WorldVariant variant, List<WorldVariant> worlds) {
    public static final String ALL_TILES = "all";

    public ClientInstallation(Path root, List<String> tiles) {
        this(root, tiles, WorldVariant.MAIN, List.of(WorldVariant.MAIN));
    }

    public ClientInstallation {
        tiles = List.copyOf(tiles);
        worlds = List.copyOf(worlds);
    }

    public static ClientInstallation inspect(Path folder) {
        return inspect(folder, WorldVariant.MAIN);
    }

    public static ClientInstallation inspect(Path folder, WorldVariant variant) {
        try {
            var root = folder.toRealPath();
            if (!Files.isDirectory(root)) {
                throw new ConfigurationProblem("Choose a game folder, not a file.");
            }
            Set<String> directories;
            try (var children = Files.list(root)) {
                directories = children.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
            }
            if (!directories.containsAll(Set.of("maps", "textures", "staticmeshes", "system"))) {
                throw new ConfigurationProblem(
                        "Choose the game folder containing Maps, Textures, StaticMeshes and system.");
            }
            var worlds = WorldVariant.findWorlds(root);
            var world = worlds.contains(variant) ? variant : WorldVariant.MAIN;
            var tiles = WorldMapCatalog.discover(root, world);
            if (tiles.isEmpty()) {
                throw new ConfigurationProblem("No world maps found. Expected files such as Maps/22_22.unr.");
            }
            return new ClientInstallation(root, tiles, world, worlds);
        } catch (IOException | UncheckedIOException failure) {
            throw new ConfigurationProblem(
                    "Cannot read this folder. Check that it exists and you have access.", failure);
        }
    }

    public ClientInstallation withVariant(WorldVariant world) {
        return world == variant ? this : inspect(root, world);
    }

    public boolean hasWorldChoice() {
        return worlds.size() > 1;
    }

    public boolean hasClassicMaps() {
        return worlds.contains(WorldVariant.CLASSIC);
    }

    public List<String> tilesIn(int minX, int maxX, int minY, int maxY) {
        return tiles.stream()
                .filter(tile -> {
                    var parts = tile.split("_");
                    var x = Integer.parseInt(parts[0]);
                    var y = Integer.parseInt(parts[1]);
                    return x >= minX && x <= maxX && y >= minY && y <= maxY;
                })
                .toList();
    }
}

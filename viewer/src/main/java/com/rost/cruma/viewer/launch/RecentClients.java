package com.rost.cruma.viewer.launch;

import com.rost.cruma.viewer.config.ConfigurationProblem;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValueFactory;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecentClients {
    public static final String FILE_NAME = "clients.conf";
    private static final int LIMIT = 12;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public RecentClients(Path configDirectory) {
        file = configDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return;
        }
        try {
            var config = ConfigFactory.parseFile(file.toFile()).resolve();
            if (config.getInt("version") != 1) {
                throw new IllegalArgumentException("Unsupported recent-client file version");
            }
            var unique = new LinkedHashMap<Path, Entry>();
            for (var value : config.getConfigList("clients")) {
                var entry = new Entry(
                        Path.of(value.getString("path")),
                        value.getString("tile"),
                        value.hasPath("capture_tile") ? value.getString("capture_tile") : "auto");
                unique.putIfAbsent(entry.path(), entry);
            }
            entries.addAll(unique.values().stream().limit(LIMIT).toList());
        } catch (RuntimeException failure) {
            throw new ConfigurationProblem(
                    "Cannot read recent clients from " + file + ". The file was left unchanged.", failure);
        }
    }

    public record Entry(Path path, String tile, String captureTile) {
        public Entry(Path path, String tile) {
            this(path, tile, "auto");
        }

        public Entry {
            path = path.toAbsolutePath().normalize();
            if (captureTile == null
                    || !captureTile.isEmpty()
                            && !captureTile.equals("auto")
                            && !captureTile.equals(ClientInstallation.ALL_TILES)
                            && !TileSelection.NONE.equals(captureTile)
                            && !TileSelection.isExplicit(captureTile)) {
                throw new IllegalArgumentException("Invalid capture tile: " + captureTile);
            }
            if (tile == null
                    || !tile.isEmpty()
                            && !tile.equals(ClientInstallation.ALL_TILES)
                            && !TileSelection.NONE.equals(tile)
                            && !TileSelection.isExplicit(tile)) {
                throw new IllegalArgumentException("Invalid tile: " + tile);
            }
        }
    }

    public List<Entry> getEntries() {
        return List.copyOf(entries);
    }

    public void remember(Entry entry) throws IOException {
        var updated = new ArrayList<>(entries);
        updated.removeIf(value -> value.path().equals(entry.path()));
        updated.addFirst(entry);
        write(updated.stream().limit(LIMIT).toList());
    }

    public void remove(Path path) throws IOException {
        write(entries.stream().filter(entry -> !entry.path().equals(path)).toList());
    }

    private void write(List<Entry> updated) throws IOException {
        var values = updated.stream()
                .map(entry -> Map.of(
                        "path", entry.path().toString(), "tile", entry.tile(), "capture_tile", entry.captureTile()))
                .toList();
        var text = ConfigValueFactory.fromMap(Map.of("version", 1, "clients", values))
                .render(ConfigRenderOptions.defaults().setJson(false).setOriginComments(false));
        Files.createDirectories(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), ".clients-", ".tmp");
        try {
            Files.writeString(temporary, text + "\n");
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            entries.clear();
            entries.addAll(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

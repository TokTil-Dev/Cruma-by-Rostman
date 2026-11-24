package com.rost.cruma.viewer.launch;

import com.rost.cruma.viewer.config.ConfigurationProblem;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class SessionHistory {
    public static final String FILE_NAME = "sessions.conf";

    public static final int LIMIT = 12;

    private final Path file;
    private final List<Session> sessions = new ArrayList<>();
    private int skipped;

    public SessionHistory(Path configDirectory, List<Session> seed) {
        file = configDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            sessions.addAll(seed.stream().limit(LIMIT).toList());
            return;
        }
        List<? extends ConfigValue> rows;
        try {
            var config = ConfigFactory.parseFile(file.toFile()).resolve();
            if (config.getInt("version") != 1) {
                throw new IllegalArgumentException("Unsupported session file version");
            }
            rows = config.getList("sessions");
        } catch (RuntimeException failure) {
            throw new ConfigurationProblem(
                    "Cannot read the recent sessions from " + file + ". The file was left unchanged.", failure);
        }
        for (var row : rows) {
            if (sessions.size() == LIMIT) {
                break;
            }
            try {
                sessions.add(readSession(((ConfigObject) row).toConfig()));
            } catch (RuntimeException unreadable) {
                skipped++;
            }
        }
    }

    public enum Kind {
        TILES("tiles"),
        MAP("map"),
        EFFECTS("effects");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        static Kind parse(String key) {
            for (var kind : values()) {
                if (kind.key.equals(key.toLowerCase(Locale.ROOT))) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown session kind: " + key);
        }

        public String getKey() {
            return key;
        }
    }

    public record Session(
            Path client,
            Kind kind,
            String tiles,
            String map,
            String title,
            String place,
            boolean edit,
            @Nullable Instant time) {
        public Session {
            client = client.toAbsolutePath().normalize();
            if (kind == Kind.TILES && !ClientInstallation.ALL_TILES.equals(tiles) && !TileSelection.isExplicit(tiles)) {
                throw new IllegalArgumentException("Invalid session tiles: " + tiles);
            }
            if (kind == Kind.MAP && map.isBlank()) {
                throw new IllegalArgumentException("A session of a map needs the map's name.");
            }
        }

        public boolean isSameStart(Session other) {
            return client.equals(other.client)
                    && kind == other.kind
                    && tiles.equals(other.tiles)
                    && map.equals(other.map);
        }
    }

    public static List<Session> seed(List<RecentClients.Entry> clients, StartPlaces titles) {
        var seeded = new ArrayList<Session>();
        for (var entry : clients) {
            var tile = entry.tile();
            if (ClientInstallation.ALL_TILES.equals(tile)) {
                seeded.add(new Session(entry.path(), Kind.TILES, tile, "", "Whole world", "", false, null));
            } else if (TileSelection.isExplicit(tile)) {
                var tiles = TileSelection.parse(tile);
                seeded.add(new Session(
                        entry.path(),
                        Kind.TILES,
                        String.join(",", tiles),
                        "",
                        titles.describe(tiles, null, Integer.MAX_VALUE),
                        "",
                        false,
                        null));
            }
        }
        return seeded;
    }

    public List<Session> getSessions() {
        return List.copyOf(sessions);
    }

    public int getSkipped() {
        return skipped;
    }

    public @Nullable Session findNewestTiles(Path client) {
        var root = client.toAbsolutePath().normalize();
        for (var session : sessions) {
            if (session.kind() == Kind.TILES && session.client().equals(root)) {
                return session;
            }
        }
        return null;
    }

    public void removeClient(Path client) throws IOException {
        var root = client.toAbsolutePath().normalize();
        if (sessions.stream().anyMatch(session -> session.client().equals(root))) {
            write(sessions.stream()
                    .filter(session -> !session.client().equals(root))
                    .toList());
        }
    }

    public void record(Session session) throws IOException {
        var updated = new ArrayList<Session>();
        updated.add(session);
        for (var old : sessions) {
            if (!old.isSameStart(session)) {
                updated.add(old);
            }
        }
        write(updated.stream().limit(LIMIT).toList());
    }

    public void remove(Session session) throws IOException {
        write(sessions.stream().filter(old -> !old.equals(session)).toList());
    }

    private void write(List<Session> updated) throws IOException {
        var values = new ArrayList<Map<String, Object>>();
        for (var session : updated) {
            var value = new LinkedHashMap<String, Object>();
            value.put("client", session.client().toString());
            value.put("kind", session.kind().getKey());
            value.put("tiles", session.tiles());
            value.put("map", session.map());
            value.put("title", session.title());
            value.put("place", session.place());
            value.put("edit", session.edit());
            value.put("time", session.time() == null ? "" : session.time().toString());
            values.add(value);
        }
        var text = ConfigValueFactory.fromMap(Map.of("version", 1, "sessions", values))
                .render(ConfigRenderOptions.defaults().setJson(false).setOriginComments(false));
        Files.createDirectories(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), ".sessions-", ".tmp");
        try {
            Files.writeString(temporary, text + "\n");
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            sessions.clear();
            sessions.addAll(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Session readSession(Config value) {
        var time = value.hasPath("time") ? value.getString("time") : "";
        return new Session(
                Path.of(value.getString("client")),
                Kind.parse(value.getString("kind")),
                value.getString("tiles"),
                value.getString("map"),
                value.getString("title"),
                value.hasPath("place") ? value.getString("place") : "",
                value.getBoolean("edit"),
                time.isEmpty() ? null : Instant.parse(time));
    }
}

package com.rost.cruma.viewer.editing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.EffectEdits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class EffectEditStore {
    static final String FILE = "edits.json";
    private static final String SCHEMA = "cruma.effect-edits/v1";
    private static final String IMAGES = "images";
    private static final DateTimeFormatter KEPT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;

    EffectEditStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    record Saved(EffectEdits edits, Map<String, String> sources) {}

    Path getDirectory() {
        return directory;
    }

    Path getImageDirectory() {
        return directory.resolve(IMAGES);
    }

    @Nullable
    Saved read() throws IOException {
        return read(directory.resolve(FILE));
    }

    @Nullable
    Saved read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            var json = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (json == null || !SCHEMA.equals(json.getString("schema"))) {
                throw new IOException("Not a file of saved edits: " + file);
            }
            var sources = new LinkedHashMap<String, String>();
            var listed = json.getJSONObject("sources");
            if (listed != null) {
                listed.forEach((name, hash) -> sources.put(name, String.valueOf(hash)));
            }
            return new Saved(json.getObject("edits", EffectEdits.class), sources);
        } catch (RuntimeException unreadable) {
            throw new IOException("Unreadable saved edits: " + unreadable.getMessage(), unreadable);
        }
    }

    void write(Path client, EffectEdits edits, Map<String, String> sources) throws IOException {
        var json = new JSONObject();
        json.put("schema", SCHEMA);
        json.put("client", client.toString());
        json.put("sources", sources);
        json.put("edits", edits);
        Files.createDirectories(directory);
        var temporary = Files.createTempFile(directory, ".edits-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJSONString(json), StandardCharsets.UTF_8);
            var target = directory.resolve(FILE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void delete() throws IOException {
        Files.deleteIfExists(directory.resolve(FILE));
    }

    void keep() throws IOException {
        var kept = directory.resolve("edits-" + KEPT.format(LocalDateTime.now()) + ".json");
        Files.move(directory.resolve(FILE), kept, StandardCopyOption.REPLACE_EXISTING);
    }

    List<Path> getKept() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().matches("edits-.+\\.json"))
                    .sorted(Comparator.comparing(
                                    (Path file) -> file.getFileName().toString())
                            .reversed())
                    .toList();
        }
    }
}

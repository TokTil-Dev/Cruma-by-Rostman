package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnMaker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class SpawnBookmarks {
    private final Path file;

    public SpawnBookmarks(Path file) {
        this.file = file;
    }

    public static String bookmarkKey(SpawnMaker maker) {
        return maker.name().isEmpty() ? String.join(";", maker.territoryNames()) : maker.name();
    }

    public static int find(State state, String key) {
        for (var i = 0; i < state.makers().size(); i++) {
            var maker = state.makers().get(i);
            if (maker != null && bookmarkKey(maker).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    public List<String> load(Path folder) throws IOException {
        var keys = new ArrayList<String>();
        if (!Files.isRegularFile(file)) {
            return keys;
        }
        var prefix = folderKey(folder) + "\t";
        for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith(prefix) && line.length() > prefix.length()) {
                keys.add(line.substring(prefix.length()));
            }
        }
        return keys;
    }

    public void save(Path folder, List<String> keys) throws IOException {
        var prefix = folderKey(folder) + "\t";
        var lines = new ArrayList<String>();
        if (Files.isRegularFile(file)) {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.startsWith(prefix)) {
                    lines.add(line);
                }
            }
        }
        for (var key : keys) {
            lines.add(prefix + key.replace('\t', ' ').replace('\n', ' '));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String folderKey(Path folder) {
        return folder.toAbsolutePath().normalize().toString();
    }
}

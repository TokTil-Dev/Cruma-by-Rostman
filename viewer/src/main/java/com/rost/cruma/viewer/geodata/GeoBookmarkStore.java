package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.viewer.geodata.GeoInspection.Bookmark;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GeoBookmarkStore {
    private final Path file;

    public GeoBookmarkStore(Path file) {
        this.file = file;
    }

    public List<Bookmark> load(Path folder) throws IOException {
        var result = new ArrayList<Bookmark>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        var key = key(folder);
        for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            var parts = line.split("\t", 5);
            if (parts.length != 5 || !parts[0].equals(key)) {
                continue;
            }
            try {
                result.add(new Bookmark(
                        parts[4],
                        new GeoPoint(
                                Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2]),
                                Double.parseDouble(parts[3]))));
            } catch (IllegalArgumentException broken) {
            }
        }
        return result;
    }

    public void save(Path folder, List<Bookmark> bookmarks) throws IOException {
        var key = key(folder);
        var lines = new ArrayList<String>();
        if (Files.isRegularFile(file)) {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.startsWith(key + "\t")) {
                    lines.add(line);
                }
            }
        }
        for (var bookmark : bookmarks) {
            var position = bookmark.position();
            lines.add(String.join(
                    "\t",
                    key,
                    formatNumber(position.x()),
                    formatNumber(position.y()),
                    formatNumber(position.z()),
                    bookmark.name().replace('\t', ' ').replace('\n', ' ')));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String key(Path folder) {
        return folder.toAbsolutePath().normalize().toString();
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }
}

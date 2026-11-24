package com.rost.cruma.core.unreal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClientSearchPaths {
    public static final List<Entry> DEFAULT = List.of(
            new Entry("system", "u"),
            new Entry("staticmeshes", "usx"),
            new Entry("textures", "utx"),
            new Entry("sounds", "uax"),
            new Entry("voice", "uax"),
            new Entry("maps", "unr"),
            new Entry("animations", "ukx"),
            new Entry("animations", "uix"),
            new Entry("systextures", "utx"),
            new Entry("animations", "usk"),
            new Entry("saves", "uvx"));

    private ClientSearchPaths() {}

    public record Entry(String directory, String extension) {}

    public static List<Entry> read(Path clientRoot) {
        var ini = clientRoot.resolve("system").resolve("l2.ini");
        if (!Files.isRegularFile(ini)) {
            return DEFAULT;
        }
        try {
            var bytes = L2DataFile.read(ini);
            var text = bytes.length > 1 && bytes[1] == 0
                    ? new String(bytes, StandardCharsets.UTF_16LE)
                    : new String(bytes, StandardCharsets.ISO_8859_1);
            var entries = parse(text);
            return entries.isEmpty() ? DEFAULT : entries;
        } catch (IOException | RuntimeException unreadable) {
            return DEFAULT;
        }
    }

    static List<Entry> parse(String text) {
        var entries = new ArrayList<Entry>();
        for (var line : text.split("\\R")) {
            var trimmed = line.trim();
            if (!trimmed.regionMatches(true, 0, "Paths=", 0, 6)) {
                continue;
            }
            var value = trimmed.substring(6).replace('\\', '/');
            var star = value.lastIndexOf("/*.");
            if (star < 0) {
                continue;
            }
            var folder = Path.of("system").resolve(value.substring(0, star)).normalize();
            if (folder.getNameCount() != 1 || folder.toString().isEmpty() || folder.startsWith("..")) {
                continue;
            }
            entries.add(new Entry(
                    folder.toString().toLowerCase(Locale.ROOT),
                    value.substring(star + 3).toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(entries);
    }

    public static int rank(List<Entry> paths, Path clientRoot, Path file) {
        var relative = clientRoot
                .toAbsolutePath()
                .normalize()
                .relativize(file.toAbsolutePath().normalize());
        if (relative.getNameCount() != 2) {
            return Integer.MAX_VALUE;
        }
        var folder = relative.getName(0).toString().toLowerCase(Locale.ROOT);
        var name = relative.getName(1).toString();
        var dot = name.lastIndexOf('.');
        var extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (var i = 0; i < paths.size(); i++) {
            if (paths.get(i).directory().equals(folder)
                    && paths.get(i).extension().equals(extension)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}

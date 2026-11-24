package com.rost.cruma.viewer.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

public final class LogFiles {
    public static final String PROPERTY = "cruma.log.file";

    public static final String PART_PROPERTY = "cruma.log.part";

    static final int KEPT_RUNS = 3;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private LogFiles() {}

    public static Path logDirectory(Path configDirectory) {
        return configDirectory.resolve("logs");
    }

    public static @Nullable Path start(Path configDirectory, LocalDateTime now) {
        var directory = logDirectory(configDirectory);
        try {
            Files.createDirectories(directory);
            pruneRuns(directory, KEPT_RUNS - 1);
            var name = "cruma-" + STAMP.format(now);
            for (int run = 2; Files.exists(directory.resolve(name + ".log")); run++) {
                name = "cruma-" + STAMP.format(now) + "-" + run;
            }
            var file = directory.resolve(name + ".log");
            System.setProperty(
                    PART_PROPERTY, directory.resolve(name + ".%i.log").toString());
            System.setProperty(PROPERTY, file.toString());
            return file;
        } catch (IOException | RuntimeException failure) {
            System.err.println("Cannot write log files in " + directory + ": " + failure);
            return null;
        }
    }

    public static @Nullable Path current() {
        var file = System.getProperty(PROPERTY);
        return file == null || file.isBlank() ? null : Path.of(file);
    }

    static void pruneRuns(Path directory, int keep) throws IOException {
        var runs = new TreeMap<String, List<Path>>(Comparator.reverseOrder());
        try (var stream = Files.list(directory)) {
            stream.forEach(path -> {
                var name = path.getFileName().toString();
                if (name.startsWith("cruma-") && name.endsWith(".log")) {
                    runs.computeIfAbsent(name.substring(0, name.indexOf('.')), run -> new ArrayList<>())
                            .add(path);
                }
            });
        }
        int index = 0;
        for (var files : runs.values()) {
            if (index++ >= keep) {
                for (var file : files) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    static void prune(Path directory, String prefix, int keep) throws IOException {
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(path -> {
                        var name = path.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".log");
                    })
                    .sorted(Comparator.comparing(
                                    (Path path) -> path.getFileName().toString())
                            .reversed())
                    .toList();
        }
        for (int index = Math.max(0, keep); index < files.size(); index++) {
            Files.deleteIfExists(files.get(index));
        }
    }

    static String formatStamp(LocalDateTime time) {
        return STAMP.format(time);
    }
}

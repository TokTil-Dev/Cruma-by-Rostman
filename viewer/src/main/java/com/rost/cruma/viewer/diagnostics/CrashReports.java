package com.rost.cruma.viewer.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class CrashReports {
    static final int KEPT_REPORTS = 3;
    private static final Pattern NAME = Pattern.compile("hs_err_pid(\\d+)\\.log");
    private static final String MARKER = "com.rost.cruma";
    private static final int HEADER_BYTES = 64 * 1024;

    private CrashReports() {}

    public static List<Path> searchDirectories() {
        return List.of(Path.of("").toAbsolutePath(), Path.of(System.getProperty("java.io.tmpdir")));
    }

    public static List<Path> collect(Path logsDirectory, List<Path> searchDirectories) {
        var collected = new ArrayList<Path>();
        for (var directory : searchDirectories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var reports = Files.newDirectoryStream(directory, "hs_err_pid*.log")) {
                for (var report : reports) {
                    var moved = collect(logsDirectory, report);
                    if (moved != null) {
                        collected.add(moved);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        if (!collected.isEmpty()) {
            try {
                LogFiles.prune(logsDirectory, "crash-", KEPT_REPORTS);
            } catch (IOException ignored) {
            }
        }
        return collected;
    }

    private static @Nullable Path collect(Path logsDirectory, Path report) {
        var name = NAME.matcher(report.getFileName().toString());
        if (!name.matches()) {
            return null;
        }
        try {
            if (!fromCruma(report)) {
                return null;
            }
            var modified =
                    LocalDateTime.ofInstant(Files.getLastModifiedTime(report).toInstant(), ZoneId.systemDefault());
            var target =
                    logsDirectory.resolve("crash-" + LogFiles.formatStamp(modified) + "-pid" + name.group(1) + ".log");
            if (Files.exists(target)) {
                Files.deleteIfExists(report);
                return null;
            }
            Files.createDirectories(logsDirectory);
            Files.move(report, target);
            return target;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static boolean fromCruma(Path report) throws IOException {
        try (var stream = Files.newInputStream(report)) {
            var header = new String(stream.readNBytes(HEADER_BYTES), StandardCharsets.ISO_8859_1);
            return header.contains(MARKER);
        }
    }
}

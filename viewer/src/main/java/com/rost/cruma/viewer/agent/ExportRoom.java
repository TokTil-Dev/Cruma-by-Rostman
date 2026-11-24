package com.rost.cruma.viewer.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

public final class ExportRoom {
    static final long LEAST_FREE_BYTES = 5L << 30;

    static final long MOST_BYTES_PER_RUN = 20L << 30;

    private static final double GIGABYTE = 1 << 30;

    private final ToLongFunction<Path> free;
    private final long mostBytes;
    private final AtomicLong written = new AtomicLong();

    ExportRoom() {
        this(ExportRoom::usable, MOST_BYTES_PER_RUN);
    }

    ExportRoom(ToLongFunction<Path> free, long mostBytes) {
        this.free = free;
        this.mostBytes = mostBytes;
    }

    public void require(Path folder, Access access) throws ToolProblem {
        var left = free.applyAsLong(folder);
        if (left < LEAST_FREE_BYTES) {
            throw new ToolProblem("The disk of "
                    + folder
                    + " has only "
                    + gigabytes(left)
                    + " GB free, and an export leaves "
                    + gigabytes(LEAST_FREE_BYTES)
                    + " GB. Ask the person to clear space. No tool deletes files.");
        }
        var total = written.get();
        if (total >= mostBytes && !access.allows(Access.WRITE)) {
            throw new ToolProblem("You wrote "
                    + gigabytes(total)
                    + " GB of exports in this run, and the limit below Write is "
                    + gigabytes(mostBytes)
                    + " GB. Tell the person: they can look through "
                    + folder
                    + ", give Write access, or start a new run.");
        }
    }

    public void wrote(Path path) {
        written.addAndGet(size(path));
    }

    private static long size(Path path) {
        try (var files = Files.walk(path)) {
            return files.filter(Files::isRegularFile)
                    .mapToLong(ExportRoom::length)
                    .sum();
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
    }

    private static long length(Path file) {
        try {
            return Files.size(file);
        } catch (IOException gone) {
            return 0;
        }
    }

    private static long usable(Path folder) {
        var existing = folder.toAbsolutePath();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        try {
            return existing == null
                    ? Long.MAX_VALUE
                    : Files.getFileStore(existing).getUsableSpace();
        } catch (IOException unknown) {
            return Long.MAX_VALUE;
        }
    }

    private static String gigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / GIGABYTE);
    }
}

package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoPathNodeFolder;
import com.rost.cruma.core.geodata.GeoPathNodeFolder.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class PathNodeChecks {
    private static final Pattern CHECKED =
            Pattern.compile("[12][0-9]_[12][0-9]_conv\\.dat|geo_index\\.txt|pathnode\\.(idx|bin)|"
                    + Pattern.quote(GeoPathNodeFolder.RECORD));
    private static final Pattern REGIONS = Pattern.compile("(?m)^[12][0-9]_[12][0-9]_conv\\.dat:");

    private final Map<Path, Entry> entries = new HashMap<>();

    public record Check(Status status, boolean regions) {}

    public synchronized @Nullable Check getStatus(Path folder) {
        var key = folder.toAbsolutePath().normalize();
        var entry = entries.computeIfAbsent(key, ignored -> new Entry());
        var now = System.nanoTime();
        if (entry.listedAt != 0 && now - entry.listedAt < 1_000_000_000L) {
            return entry.check;
        }
        entry.listedAt = now;
        var listing = listCheckedFiles(key);
        if (!listing.equals(entry.listing) && !entry.running) {
            entry.listing = listing;
            entry.running = true;
            var regions = REGIONS.matcher(listing).find();
            Thread.ofVirtual().name("geodata-pathnode-check").start(() -> {
                Check check = null;
                try {
                    check = new Check(GeoPathNodeFolder.check(key, () -> false), regions);
                } catch (IOException | RuntimeException unreadable) {
                    LogManager.getLogger(PathNodeChecks.class)
                            .debug("Pathnode check of {} failed: {}", key, unreadable.toString());
                }
                synchronized (this) {
                    entry.check = check;
                    entry.running = false;
                }
            });
        }
        return entry.check;
    }

    private static String listCheckedFiles(Path folder) {
        var text = new StringBuilder();
        try (var files = Files.newDirectoryStream(folder)) {
            for (var file : files) {
                var name = file.getFileName().toString();
                if (CHECKED.matcher(name).matches()) {
                    text.append(name)
                            .append(':')
                            .append(Files.size(file))
                            .append(':')
                            .append(Files.getLastModifiedTime(file).toMillis())
                            .append('\n');
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
        return text.toString();
    }

    private static final class Entry {
        long listedAt;
        String listing;
        boolean running;

        @Nullable
        Check check;
    }
}

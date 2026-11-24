package com.rost.cruma.core.schema;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class SchemaFolder {
    static final int MAX_FILES = 256;
    static final int MAX_DEPTH = 8;
    static final int MAX_ENTRIES = 10_000;
    static final int MAX_FOLDER_FIELDS = 200_000;

    private static final Pattern FILE_LINE = Pattern.compile("(?m)^[ \\t]*file[ \\t]+(\\S+)");

    private final Path folder;
    private @Nullable List<Stamp> stamps;
    private Snapshot snapshot = new Snapshot(List.of(), List.of());

    SchemaFolder(Path folder) {
        this.folder = folder;
    }

    record Entry(
            String source,
            @Nullable Schema schema,
            @Nullable SchemaException problem,
            @Nullable String target) {
        boolean targets(String fileName, String mainName) {
            if (schema != null) {
                return schema.matchesFile(fileName) || schema.matchesFile(mainName);
            }
            if (target == null) {
                return false;
            }
            var pattern = SchemaParser.globPattern(target);
            return pattern.matcher(fileName).matches()
                    || pattern.matcher(mainName).matches();
        }
    }

    record Snapshot(List<Entry> entries, List<String> problems) {}

    private record Stamp(Path file, long size, long time) {}

    private record Scan(List<Stamp> stamps, List<String> problems) {}

    synchronized Snapshot load() {
        var scan = scan();
        if (scan.stamps().equals(stamps)) {
            return snapshot;
        }
        stamps = scan.stamps();
        snapshot = new Snapshot(List.of(), scan.problems());
        var entries = new ArrayList<Entry>();
        var fields = 0L;
        for (var stamp : scan.stamps()) {
            var entry = fields > MAX_FOLDER_FIELDS ? tooMany(stamp.file()) : readEntry(stamp.file());
            if (entry.schema() != null) {
                fields += entry.schema().getFieldCount();
                if (fields > MAX_FOLDER_FIELDS) {
                    entry = tooMany(stamp.file());
                }
            }
            entries.add(entry);
        }
        snapshot = new Snapshot(List.copyOf(entries), scan.problems());
        return snapshot;
    }

    private Scan scan() {
        var found = new ArrayList<Stamp>();
        var problems = new ArrayList<String>();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return new Scan(found, problems);
        }
        var seen = new int[1];
        try {
            Files.walkFileTree(folder, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH + 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return count();
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (attributes.isRegularFile() && name.endsWith(".schema")) {
                        found.add(new Stamp(
                                file,
                                attributes.size(),
                                attributes.lastModifiedTime().toMillis()));
                    }
                    return count();
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return count();
                }

                private FileVisitResult count() {
                    if (++seen[0] > MAX_ENTRIES) {
                        problems.add(folder + ": the folder holds more than 10 000 files and folders, so"
                                + " Cruma looked at the first 10 000 only");
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException unreadable) {
            problems.add(folder + ": the folder cannot be read: " + unreadable.getMessage());
        }
        found.sort(Comparator.comparing(stamp -> stamp.file().toString()));
        if (found.size() > MAX_FILES) {
            problems.add(folder + ": the folder holds " + found.size()
                    + " schema files, and Cruma reads the first 256 by name");
            return new Scan(List.copyOf(found.subList(0, MAX_FILES)), problems);
        }
        return new Scan(List.copyOf(found), problems);
    }

    private static Entry tooMany(Path file) {
        var problem = new SchemaException(
                file.toString(),
                0,
                "the schemas of this folder up to this file hold more than 200 000 fields, so it is not read");
        String target = null;
        try {
            if (Files.size(file) <= SchemaParser.MAX_BYTES) {
                target = findTarget(SchemaParser.decode(Files.readAllBytes(file)));
            }
        } catch (IOException | RuntimeException unreadable) {
            target = null;
        }
        return new Entry(file.toString(), null, problem, target);
    }

    private static Entry readEntry(Path file) {
        var source = file.toString();
        String text;
        try {
            if (Files.size(file) > SchemaParser.MAX_BYTES) {
                return new Entry(
                        source,
                        null,
                        new SchemaException(source, 0, "the file is over 1 MB, so it is not a schema"),
                        null);
            }
            text = SchemaParser.decode(Files.readAllBytes(file));
        } catch (IOException | RuntimeException unreadable) {
            var problem = new SchemaException(source, 0, "the file cannot be read: " + unreadable.getMessage());
            return new Entry(source, null, problem, null);
        }
        try {
            return new Entry(source, Schema.parse(text, source, file, false), null, null);
        } catch (SchemaException mistake) {
            return new Entry(source, null, mistake, findTarget(text));
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError failure) {
            var problem = new SchemaException(source, 0, "the schema cannot be read: " + failure);
            return new Entry(source, null, problem, findTarget(text));
        }
    }

    private static @Nullable String findTarget(String text) {
        var matcher = FILE_LINE.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}

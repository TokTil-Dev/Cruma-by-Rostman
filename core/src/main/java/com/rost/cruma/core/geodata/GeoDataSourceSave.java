package com.rost.cruma.core.geodata;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

public final class GeoDataSourceSave {
    private GeoDataSourceSave() {}

    public record Result(Path source, List<Path> replaced, Path backup) {
        public Result {
            replaced = List.copyOf(replaced);
        }
    }

    @FunctionalInterface
    public interface Export {
        void into(Path directory) throws IOException;
    }

    public static Result save(
            GeoEditDocument document, boolean pathNodes, boolean backup, LocalDateTime now, Export export)
            throws IOException {
        Objects.requireNonNull(export);
        GeoDataSnapshot base;
        TreeSet<GeoTile> edited =
                new TreeSet<>((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.y(), b.y()));
        synchronized (document) {
            base = document.getBase();
            for (var key : document.getChangedCells().keySet()) {
                edited.add(GeoTile.fromWorld(key.x(), key.y()));
            }
        }
        if (base.getSourcePath() == null || !Files.isDirectory(base.getSourcePath())) {
            throw new IOException("A source folder is required to save in place");
        }
        var source = base.getSourcePath().toRealPath();
        var parent = source.getParent();
        if (parent == null) {
            throw new IOException("The source folder needs a parent folder for temporary files");
        }
        var stage = parent.resolve(".cruma-save-" + UUID.randomUUID());
        try {
            export.into(stage);
            var names = new ArrayList<String>();
            for (var tile : edited) {
                var region = base.getRegion(tile.x(), tile.y());
                if (region == null) {
                    throw new IOException("Edited region is no longer available: " + tile.fileName());
                }
                var name = region.getFormat().getFileName(tile);
                var current = source.resolve(name);
                if (!Files.isRegularFile(current)
                        || !GeoDataWriter.fileFingerprint(current).equals(region.getFileFingerprint())) {
                    throw new IOException("Source changed since editing began: " + name);
                }
                names.add(name);
            }
            if (pathNodes) {
                for (var name : GeoPathNodeFolder.FILES) {
                    if (Files.isRegularFile(stage.resolve(name))) {
                        names.add(name);
                    }
                }
            }
            for (var name : names) {
                if (!Files.isRegularFile(stage.resolve(name))) {
                    throw new IOException("The export is missing " + name);
                }
            }
            Path backupFolder = null;
            if (backup && !names.isEmpty()) {
                backupFolder = backupFolder(source, now);
                Files.createDirectory(backupFolder);
                for (var name : names) {
                    var current = source.resolve(name);
                    if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                        Files.copy(current, backupFolder.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            var replaced = new ArrayList<Path>();
            for (var name : names) {
                replaced.add(replace(source, name, stage.resolve(name)));
            }
            return new Result(source, replaced, backupFolder);
        } finally {
            delete(stage);
        }
    }

    static Path replace(Path folder, String name, Path from) throws IOException {
        var target = folder.resolve(name);
        var temporary = folder.resolve(".cruma-save-" + name + ".tmp");
        Files.copy(from, temporary, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    static Path backupFolder(Path source, LocalDateTime now) {
        var name = source.getFileName() + "-backup-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var candidate = source.resolveSibling(name);
        for (var index = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); index++) {
            candidate = source.resolveSibling(name + "-" + index);
        }
        return candidate;
    }

    static void delete(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

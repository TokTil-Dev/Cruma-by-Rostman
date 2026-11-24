package com.rost.cruma.core.unreal.edit;

import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public final class MapTileOperation {
    public static final String MANIFEST = "cruma-tile-operation.properties";
    private static final String NONE = "none";
    private static final Set<String> PACKAGE_EXTENSIONS = Set.of("u", "usx", "utx", "uax", "ukx", "unr", "umx");

    private MapTileOperation() {}

    public enum Kind {
        COPY,
        MOVE,
        SWAP;

        public String getLabel() {
            return switch (this) {
                case COPY -> "Copy";
                case MOVE -> "Move";
                case SWAP -> "Swap";
            };
        }
    }

    public record TileRef(int x, int y, WorldVariant variant) {
        public String packageName() {
            return x + "_" + y + variant.getMapSuffix();
        }

        @Override
        public String toString() {
            return packageName();
        }
    }

    public record Write(TileRef from, TileRef to, Path source, String fileName) {}

    public record Extra(Path file, @Nullable Path from, byte @Nullable [] content, String patchPath, String label) {
        public boolean isRemoval() {
            return from == null && content == null;
        }
    }

    public record Plan(
            Path sourceRoot,
            Path clientRoot,
            Path maps,
            Kind kind,
            TileRef source,
            TileRef target,
            List<Write> writes,
            List<Path> replaced,
            List<Path> removed,
            List<String> fingerprints,
            List<String> warnings,
            List<Extra> extras,
            List<String> extraFingerprints) {
        public Plan {
            writes = List.copyOf(writes);
            replaced = List.copyOf(replaced);
            removed = List.copyOf(removed);
            fingerprints = List.copyOf(fingerprints);
            warnings = List.copyOf(warnings);
            extras = List.copyOf(extras);
            extraFingerprints = List.copyOf(extraFingerprints);
        }
    }

    public record Result(
            List<Path> written,
            List<Path> removed,
            @Nullable Path backup) {
        public Result {
            written = List.copyOf(written);
            removed = List.copyOf(removed);
        }
    }

    public record Backup(
            Path folder, String operation, String source, String target, LocalDateTime time, boolean undone) {}

    public static final class Refused extends IOException {
        public Refused(String message) {
            super(message);
        }
    }

    public static Plan plan(Path clientRoot, Kind kind, TileRef source, TileRef target, boolean replace)
            throws IOException {
        return plan(clientRoot, clientRoot, kind, source, target, replace);
    }

    public static Plan plan(
            Path sourceRoot, Path clientRoot, Kind kind, TileRef source, TileRef target, boolean replace)
            throws IOException {
        var maps = mapsFolder(clientRoot);
        var otherClient = !isSameFolder(sourceRoot, clientRoot);
        if (otherClient && kind != Kind.COPY) {
            throw new Refused("From another client only Copy works, so that client does not change.");
        }
        if (source.equals(target) && !otherClient) {
            throw new Refused("The source and the target are the same tile.");
        }
        var sourceFile = find(otherClient ? mapsFolder(sourceRoot) : maps, source);
        if (sourceFile == null) {
            throw new Refused("There is no map "
                    + source.packageName()
                    + ".unr in "
                    + (otherClient ? "the other client." : "this client."));
        }
        var targetFile = find(maps, target);
        if (kind == Kind.SWAP && targetFile == null) {
            throw new Refused("Swap needs a map in both tiles, and " + target.packageName() + " has none.");
        }
        if (kind != Kind.SWAP && targetFile != null && !replace) {
            throw new Refused(targetFile.getFileName() + " already exists. Choose Swap, or allow it to be replaced.");
        }
        for (var file : targetFile == null ? List.of(sourceFile) : List.of(sourceFile, targetFile)) {
            if (!MapTileShift.isSupported(file)) {
                throw new Refused(
                        file.getFileName() + " is not a Lineage2Ver111 package, so Cruma cannot write it back.");
            }
        }

        var writes = new ArrayList<Write>();
        var replaced = new ArrayList<Path>();
        var removed = new ArrayList<Path>();
        var targetName = targetFile != null ? targetFile.getFileName().toString() : target.packageName() + ".unr";
        writes.add(new Write(source, target, sourceFile, targetName));
        if (targetFile != null) {
            replaced.add(targetFile);
        }
        if (kind == Kind.SWAP) {
            writes.add(new Write(
                    target, source, targetFile, sourceFile.getFileName().toString()));
            replaced.add(sourceFile);
        } else if (kind == Kind.MOVE) {
            removed.add(sourceFile);
        }

        var fingerprints = new ArrayList<String>();
        for (var file : new TreeSet<>(targetFile == null ? List.of(sourceFile) : List.of(sourceFile, targetFile))) {
            var name = file.getParent().equals(maps)
                    ? file.getFileName().toString()
                    : file.toAbsolutePath().toString();
            fingerprints.add(name + "=" + sha256(file));
        }
        var warnings = new ArrayList<String>();
        var packages = otherClient ? Set.<String>of() : installedPackages(clientRoot);
        for (var write : otherClient ? List.<Write>of() : writes) {
            var missing = missingImports(sourceRoot, write.source(), packages);
            if (!missing.isEmpty()) {
                warnings.add(write.from().packageName() + " uses packages this client does not have: "
                        + String.join(", ", missing));
            }
        }
        return new Plan(
                sourceRoot,
                clientRoot,
                maps,
                kind,
                source,
                target,
                writes,
                replaced,
                removed,
                fingerprints,
                warnings,
                List.of(),
                List.of());
    }

    public static Plan withExtras(Plan plan, List<Extra> extras) throws IOException {
        var all = new ArrayList<>(plan.extras());
        all.addAll(extras);
        var fingerprints = new ArrayList<>(plan.extraFingerprints());
        for (var extra : extras) {
            var file = extra.file().toAbsolutePath();
            fingerprints.add(file + "=" + (Files.isRegularFile(file) ? sha256(file) : NONE));
            if (extra.from() != null) {
                fingerprints.add(extra.from().toAbsolutePath() + "=" + sha256(extra.from()));
            }
        }
        return new Plan(
                plan.sourceRoot(),
                plan.clientRoot(),
                plan.maps(),
                plan.kind(),
                plan.source(),
                plan.target(),
                plan.writes(),
                plan.replaced(),
                plan.removed(),
                plan.fingerprints(),
                plan.warnings(),
                all,
                fingerprints);
    }

    public static Result run(Plan plan, boolean backup, LocalDateTime now) throws IOException {
        var stage = plan.maps().resolveSibling(".cruma-tiles-" + UUID.randomUUID());
        try {
            var staged = stage(plan, stage);
            checkUnchanged(plan);
            Path backupFolder = null;
            if (backup) {
                backupFolder = backupFolder(plan.maps(), now);
                writeBackup(plan, staged, backupFolder, now);
            }
            var written = new ArrayList<Path>();
            for (var write : plan.writes()) {
                written.add(replace(plan.maps(), write.fileName(), stage.resolve(write.fileName())));
            }
            for (var file : plan.removed()) {
                Files.deleteIfExists(file);
            }
            var removed = new ArrayList<>(plan.removed());
            for (var extra : plan.extras()) {
                if (extra.isRemoval()) {
                    Files.deleteIfExists(extra.file());
                    removed.add(extra.file());
                } else {
                    written.add(putExtra(extra, extra.file(), stage));
                }
            }
            return new Result(written, removed, backupFolder);
        } finally {
            delete(stage);
        }
    }

    public static Result export(Plan plan, Path folder) throws IOException {
        Files.createDirectories(folder);
        for (var write : plan.writes()) {
            if (Files.exists(folder.resolve(write.fileName()), LinkOption.NOFOLLOW_LINKS)) {
                throw new Refused(write.fileName() + " already exists in " + folder.getFileName() + ".");
            }
        }
        for (var extra : plan.extras()) {
            if (!extra.isRemoval()
                    && !extra.patchPath().isEmpty()
                    && Files.exists(folder.resolve(extra.patchPath()), LinkOption.NOFOLLOW_LINKS)) {
                throw new Refused(extra.patchPath() + " already exists in " + folder.getFileName() + ".");
            }
        }
        var stage = folder.resolve(".cruma-tiles-" + UUID.randomUUID());
        try {
            stage(plan, stage);
            var written = new ArrayList<Path>();
            for (var write : plan.writes()) {
                written.add(replace(folder, write.fileName(), stage.resolve(write.fileName())));
            }
            for (var extra : plan.extras()) {
                if (!extra.isRemoval() && !extra.patchPath().isEmpty()) {
                    written.add(putExtra(extra, folder.resolve(extra.patchPath()), stage));
                }
            }
            return new Result(written, List.of(), null);
        } finally {
            delete(stage);
        }
    }

    public static List<Path> undo(Path backupFolder) throws IOException {
        var manifestFile = backupFolder.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestFile)) {
            throw new Refused(backupFolder.getFileName() + " is not a tile backup.");
        }
        var manifest = new Properties();
        try (InputStream input = Files.newInputStream(manifestFile)) {
            manifest.load(input);
        }
        if (manifest.getProperty("undone") != null) {
            throw new Refused("This backup was already restored.");
        }
        var maps = Path.of(manifest.getProperty("maps"));
        var count = Integer.parseInt(manifest.getProperty("files", "0"));
        var changed = new ArrayList<String>();
        for (var i = 0; i < count; i++) {
            var name = manifest.getProperty("file." + i + ".name");
            var after = manifest.getProperty("file." + i + ".after");
            var current = Path.of(manifest.getProperty("file." + i + ".folder", maps.toString()))
                    .resolve(name);
            var now = Files.isRegularFile(current) ? sha256(current) : NONE;
            if (!now.equals(after)) {
                changed.add(name);
            }
        }
        if (!changed.isEmpty()) {
            throw new Refused("Changed since the operation, so not restored: " + String.join(", ", changed) + ".");
        }
        var restored = new ArrayList<Path>();
        for (var i = 0; i < count; i++) {
            var name = manifest.getProperty("file." + i + ".name");
            var before = manifest.getProperty("file." + i + ".before");
            var folder = Path.of(manifest.getProperty("file." + i + ".folder", maps.toString()));
            if (NONE.equals(before)) {
                var created = folder.resolve(name);
                Files.deleteIfExists(created);
                restored.add(created);
            } else {
                var saved = backupFolder.resolve(manifest.getProperty("file." + i + ".saved", name));
                if (!sha256(saved).equals(before)) {
                    throw new Refused("The backup copy of " + name + " is damaged.");
                }
                restored.add(replace(folder, name, saved));
            }
        }
        manifest.setProperty("undone", LocalDateTime.now().toString());
        try (OutputStream output = Files.newOutputStream(manifestFile)) {
            manifest.store(output, "Cruma map tile operation");
        }
        return restored;
    }

    public static List<Backup> listBackups(Path clientRoot) throws IOException {
        var maps = mapsFolder(clientRoot);
        var prefix = maps.getFileName() + "-backup-";
        var result = new ArrayList<Backup>();
        try (var entries = Files.list(maps.getParent())) {
            for (var folder : (Iterable<Path>) entries::iterator) {
                var manifestFile = folder.resolve(MANIFEST);
                if (!folder.getFileName().toString().startsWith(prefix) || !Files.isRegularFile(manifestFile)) {
                    continue;
                }
                var manifest = new Properties();
                try (InputStream input = Files.newInputStream(manifestFile)) {
                    manifest.load(input);
                    result.add(new Backup(
                            folder,
                            manifest.getProperty("operation", ""),
                            manifest.getProperty("source", ""),
                            manifest.getProperty("target", ""),
                            LocalDateTime.parse(manifest.getProperty("time")),
                            manifest.getProperty("undone") != null));
                } catch (IOException | RuntimeException unreadable) {
                }
            }
        }
        result.sort((a, b) -> b.time().compareTo(a.time()));
        return result;
    }

    public static @Nullable Path findMapFile(Path clientRoot, TileRef tile) throws IOException {
        return find(mapsFolder(clientRoot), tile);
    }

    public static Path mapsFolder(Path clientRoot) throws IOException {
        if (Files.isDirectory(clientRoot)) {
            try (var entries = Files.list(clientRoot)) {
                for (var entry : (Iterable<Path>) entries::iterator) {
                    if (Files.isDirectory(entry)
                            && entry.getFileName().toString().equalsIgnoreCase("maps")) {
                        return entry;
                    }
                }
            }
        }
        throw new Refused("This client has no Maps folder.");
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }

    static String sha256(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1 << 16];
            for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }

    private static boolean isSameFolder(Path first, Path second) {
        try {
            return first.toRealPath().equals(second.toRealPath());
        } catch (IOException missing) {
            return first.toAbsolutePath()
                    .normalize()
                    .equals(second.toAbsolutePath().normalize());
        }
    }

    private static Path putExtra(Extra extra, Path target, Path stage) throws IOException {
        Files.createDirectories(target.getParent());
        var from = extra.from();
        if (from == null) {
            from = stage.resolve("extra-" + UUID.randomUUID());
            Files.write(from, extra.content());
        }
        return replace(target.getParent(), target.getFileName().toString(), from);
    }

    private static List<Path> stage(Plan plan, Path stage) throws IOException {
        Files.createDirectories(stage);
        var staged = new ArrayList<Path>();
        for (var write : plan.writes()) {
            var shifted = MapTileShift.shift(
                    plan.sourceRoot(),
                    write.source(),
                    write.from().x(),
                    write.from().y(),
                    write.to().x(),
                    write.to().y());
            if (!shifted.report().problems.isEmpty()) {
                throw new Refused(write.from().packageName() + " cannot be moved: "
                        + shifted.report().problems.getFirst());
            }
            var file = stage.resolve(write.fileName());
            Files.write(file, shifted.bytes());
            var problems = MapTileCheck.compare(
                    plan.sourceRoot(),
                    write.source(),
                    file,
                    write.to().x() - write.from().x(),
                    write.to().y() - write.from().y());
            if (!problems.isEmpty()) {
                throw new Refused("The moved " + write.from().packageName() + " did not pass its check: "
                        + String.join("; ", problems));
            }
            staged.add(file);
        }
        return staged;
    }

    private static void checkUnchanged(Plan plan) throws IOException {
        for (var entry : plan.fingerprints()) {
            var split = entry.lastIndexOf('=');
            var file = plan.maps().resolve(entry.substring(0, split));
            if (!Files.isRegularFile(file) || !sha256(file).equals(entry.substring(split + 1))) {
                throw new Refused(
                        file.getFileName() + " changed after the operation was planned. Nothing was written.");
            }
        }
        for (var write : plan.writes()) {
            var target = plan.maps().resolve(write.fileName());
            if (!plan.replaced().contains(target) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new Refused(write.fileName() + " appeared after the operation was planned. Nothing was written.");
            }
        }
        for (var entry : plan.extraFingerprints()) {
            var split = entry.lastIndexOf('=');
            var file = Path.of(entry.substring(0, split));
            var expected = entry.substring(split + 1);
            var now = Files.isRegularFile(file) ? sha256(file) : NONE;
            if (!now.equals(expected)) {
                throw new Refused(
                        file.getFileName() + " changed after the operation was planned. Nothing was written.");
            }
        }
    }

    private static void writeBackup(Plan plan, List<Path> staged, Path backupFolder, LocalDateTime now)
            throws IOException {
        Files.createDirectory(backupFolder);
        var manifest = new Properties();
        manifest.setProperty("operation", plan.kind().getLabel());
        manifest.setProperty("source", plan.source().packageName());
        manifest.setProperty("target", plan.target().packageName());
        manifest.setProperty("maps", plan.maps().toAbsolutePath().toString());
        manifest.setProperty("time", now.toString());
        var index = 0;
        for (var i = 0; i < plan.writes().size(); i++) {
            var name = plan.writes().get(i).fileName();
            var current = plan.maps().resolve(name);
            var before = NONE;
            if (Files.isRegularFile(current)) {
                Files.copy(current, backupFolder.resolve(name));
                before = sha256(current);
            }
            manifest.setProperty("file." + index + ".name", name);
            manifest.setProperty("file." + index + ".before", before);
            manifest.setProperty("file." + index + ".after", sha256(staged.get(i)));
            index++;
        }
        for (var file : plan.removed()) {
            var name = file.getFileName().toString();
            Files.copy(file, backupFolder.resolve(name));
            manifest.setProperty("file." + index + ".name", name);
            manifest.setProperty("file." + index + ".before", sha256(file));
            manifest.setProperty("file." + index + ".after", NONE);
            index++;
        }
        for (var i = 0; i < plan.extras().size(); i++) {
            var extra = plan.extras().get(i);
            var file = extra.file().toAbsolutePath();
            var name = file.getFileName().toString();
            var saved = "extra-" + i + "-" + name;
            var before = NONE;
            if (Files.isRegularFile(file)) {
                Files.copy(file, backupFolder.resolve(saved));
                before = sha256(file);
            }
            var after =
                    extra.isRemoval() ? NONE : extra.from() != null ? sha256(extra.from()) : sha256(extra.content());
            manifest.setProperty("file." + index + ".name", name);
            manifest.setProperty("file." + index + ".folder", file.getParent().toString());
            manifest.setProperty("file." + index + ".saved", saved);
            manifest.setProperty("file." + index + ".before", before);
            manifest.setProperty("file." + index + ".after", after);
            index++;
        }
        manifest.setProperty("files", Integer.toString(index));
        try (OutputStream output = Files.newOutputStream(backupFolder.resolve(MANIFEST))) {
            manifest.store(output, "Cruma map tile operation");
        }
    }

    private static @Nullable Path find(Path maps, TileRef tile) throws IOException {
        var wanted = tile.packageName() + ".unr";
        try (var entries = Files.list(maps)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                if (entry.getFileName().toString().equalsIgnoreCase(wanted) && Files.isRegularFile(entry)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static Set<String> installedPackages(Path clientRoot) throws IOException {
        var names = new HashSet<String>();
        try (var folders = Files.list(clientRoot)) {
            for (var folder : (Iterable<Path>) folders::iterator) {
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                try (var files = Files.list(folder)) {
                    for (var file : (Iterable<Path>) files::iterator) {
                        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        var dot = name.lastIndexOf('.');
                        if (dot > 0 && PACKAGE_EXTENSIONS.contains(name.substring(dot + 1))) {
                            names.add(name.substring(0, dot));
                        }
                    }
                } catch (IOException unreadable) {
                }
            }
        }
        return names;
    }

    private static List<String> missingImports(Path clientRoot, Path map, Set<String> installed) {
        var loader = MapTileShift.newLoader(clientRoot);
        try {
            var name = map.getFileName().toString().replaceFirst("(?i)\\.unr$", "");
            var archive = new UArchive(name, UDecryptor.decrypt(map), loader);
            var missing = new TreeSet<String>();
            for (var entry : archive.getImportMap()) {
                if (entry.getPackageIndex() == 0
                        && !installed.contains(entry.getObjectName().toLowerCase(Locale.ROOT))) {
                    missing.add(entry.getObjectName());
                }
            }
            return List.copyOf(missing);
        } catch (RuntimeException unreadable) {
            return List.of();
        } finally {
            loader.clearArchives();
        }
    }

    private static Path replace(Path folder, String name, Path from) throws IOException {
        var target = folder.resolve(name);
        var temporary = folder.resolve(".cruma-tiles-" + name + ".tmp");
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

    private static Path backupFolder(Path maps, LocalDateTime now) {
        var name = maps.getFileName() + "-backup-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var candidate = maps.resolveSibling(name);
        for (var index = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); index++) {
            candidate = maps.resolveSibling(name + "-" + index);
        }
        return candidate;
    }

    private static void delete(Path directory) throws IOException {
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

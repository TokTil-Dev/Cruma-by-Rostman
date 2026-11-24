package com.rost.cruma.core.editing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152MapInventory {
    private static final int MAX_MAP_BYTES = 512 * 1024 * 1024;
    private static final int MAX_FILES = 8192;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024;

    private final Path root;
    private final Path directory;
    private final Path realDirectory;
    private final List<Entry> maps;
    private final List<Entry> sidecars;
    private final List<Path> namespace;
    private final long totalBytes;

    private P152MapInventory(
            Path root,
            Path directory,
            Path realDirectory,
            List<Entry> maps,
            List<Entry> sidecars,
            List<Path> namespace,
            long totalBytes) {
        this.root = root;
        this.directory = directory;
        this.realDirectory = realDirectory;
        this.maps = List.copyOf(maps);
        this.sidecars = List.copyOf(sidecars);
        this.namespace = List.copyOf(namespace);
        this.totalBytes = totalBytes;
    }

    record Entry(Path logicalPath, Path realPath, String sha256, long size) {}

    private record Read(String sha256, long size, byte[] bytes) {}

    static P152MapInventory capture(Path clientRoot, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var root = clientRoot.toRealPath();
        var directory = root.resolve("Maps");
        var realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(root)) {
            throw new IOException("BSP map directory is outside its client root");
        }
        var paths = enumerate(directory, cancelled);
        var names = new HashSet<String>();
        paths.forEach(path -> names.add(lowerCaseName(path)));
        var targets = new HashSet<Path>();
        var maps = new ArrayList<Entry>();
        var sidecars = new ArrayList<Entry>();
        long total = 0;
        for (var path : paths) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var real = path.toRealPath();
            if (!real.startsWith(root) || !targets.add(real)) {
                throw new IOException("BSP map inventory has an outside or aliased source: " + path.getFileName());
            }
            var name = path.getFileName().toString();
            var sidecar = name.startsWith("._");
            if (sidecar && !names.contains(name.substring(2).toLowerCase(Locale.ROOT))) {
                throw new IOException("Unpaired AppleDouble map sidecar: " + path.getFileName());
            }
            var read = read(path, real, sidecar, cancelled);
            total = Math.addExact(total, read.size());
            if (total > MAX_TOTAL_BYTES) {
                throw new IOException("BSP map inventory exceeds its source byte budget");
            }
            var entry = new Entry(path, real, read.sha256(), read.size());
            if (sidecar) {
                validateSidecar(read.bytes());
                sidecars.add(entry);
            } else {
                maps.add(entry);
            }
        }
        if (maps.isEmpty()) {
            throw new IOException("BSP map inventory has no maps");
        }
        var result = new P152MapInventory(root, directory, realDirectory, maps, sidecars, paths, total);
        result.verifyNamespace(cancelled);
        return result;
    }

    List<Entry> getMaps() {
        return maps;
    }

    int getSidecarCount() {
        return sidecars.size();
    }

    long getTotalBytes() {
        return totalBytes;
    }

    byte[] readCaptured(Entry entry, BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!maps.contains(entry)) {
            throw new IOException("BSP map does not belong to this captured inventory");
        }
        verifyDirectory();
        var read = read(entry.logicalPath(), entry.realPath(), true, cancelled);
        requireSame(entry, read);
        return read.bytes();
    }

    void verifyUnchanged(BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        verifyNamespace(cancelled);
        for (var entry : maps) {
            requireSame(entry, read(entry.logicalPath(), entry.realPath(), false, cancelled));
        }
        for (var entry : sidecars) {
            requireSame(entry, read(entry.logicalPath(), entry.realPath(), false, cancelled));
        }
        verifyNamespace(cancelled);
    }

    private void verifyNamespace(BooleanSupplier cancelled) throws IOException {
        verifyDirectory();
        if (!namespace.equals(enumerate(directory, cancelled))) {
            throw new IOException("BSP map inventory changed after capture");
        }
        verifyDirectory();
    }

    private void verifyDirectory() throws IOException {
        if (!root.toRealPath().equals(root) || !directory.toRealPath().equals(realDirectory)) {
            throw new IOException("BSP map directory identity changed after capture");
        }
    }

    private static void requireSame(Entry entry, Read read) throws IOException {
        if (entry.size() != read.size() || !entry.sha256().equals(read.sha256())) {
            throw new IOException("BSP receiver map changed after capture: "
                    + entry.logicalPath().getFileName());
        }
    }

    private static List<Path> enumerate(Path directory, BooleanSupplier cancelled) throws IOException {
        var paths = new ArrayList<Path>();
        var names = new HashSet<String>();
        try (var stream = Files.newDirectoryStream(directory)) {
            for (var path : stream) {
                P152FreshHashQuery.checkCancelled(cancelled);
                if (!lowerCaseName(path).endsWith(".unr")) {
                    continue;
                }
                if (!Files.isRegularFile(path) || !names.add(lowerCaseName(path))) {
                    throw new IOException(
                            "BSP map inventory has an ambiguous or non-file entry: " + path.getFileName());
                }
                paths.add(path);
                if (paths.size() > MAX_FILES) {
                    throw new IOException("BSP map inventory exceeds its file budget");
                }
            }
        }
        paths.sort(Comparator.comparing(P152MapInventory::lowerCaseName));
        return List.copyOf(paths);
    }

    private static String lowerCaseName(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static Read read(Path logical, Path real, boolean retain, BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        requireTarget(logical, real);
        var expectedSize = Files.size(logical);
        if (expectedSize <= 0 || expectedSize > MAX_MAP_BYTES) {
            throw new IOException("BSP map source size is outside its bounded profile: " + logical.getFileName());
        }
        var digest = digest();
        var buffer = new byte[64 * 1024];
        var bytes = retain ? new byte[(int) expectedSize] : null;
        long total = 0;
        try (var input = Files.newInputStream(logical)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                P152FreshHashQuery.checkCancelled(cancelled);
                total += count;
                if (total > expectedSize) {
                    throw new IOException("BSP map source grew during capture: " + logical.getFileName());
                }
                digest.update(buffer, 0, count);
                if (bytes != null) {
                    System.arraycopy(buffer, 0, bytes, (int) total - count, count);
                }
            }
        }
        requireTarget(logical, real);
        if (total != expectedSize || Files.size(logical) != expectedSize) {
            throw new IOException("BSP map source size changed during capture: " + logical.getFileName());
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Read(HexFormat.of().formatHex(digest.digest()), total, bytes);
    }

    private static void requireTarget(Path logical, Path real) throws IOException {
        if (!logical.toRealPath().equals(real)) {
            throw new IOException("BSP map source identity changed: " + logical.getFileName());
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void validateSidecar(byte[] bytes) throws IOException {
        if (bytes.length < 26) {
            throw new IOException("Truncated AppleDouble map sidecar");
        }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (input.getInt() != 0x00051607 || input.getInt() != 0x00020000) {
            throw new IOException("Unauthenticated AppleDouble map sidecar header");
        }
        input.position(24);
        var count = Short.toUnsignedInt(input.getShort());
        var end = 26L + count * 12L;
        if (count == 0 || end > bytes.length) {
            throw new IOException("Invalid AppleDouble map sidecar entry table");
        }
        var ids = new HashSet<Integer>();
        var ranges = new ArrayList<long[]>();
        for (var i = 0; i < count; i++) {
            var id = input.getInt();
            var offset = Integer.toUnsignedLong(input.getInt());
            var length = Integer.toUnsignedLong(input.getInt());
            if (id <= 0 || !ids.add(id) || offset < end || offset + length > bytes.length) {
                throw new IOException("Invalid AppleDouble map sidecar entry");
            }
            if (length != 0) {
                ranges.add(new long[] {offset, offset + length});
            }
        }
        ranges.sort(Comparator.comparingLong(range -> range[0]));
        for (var i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0] < ranges.get(i - 1)[1]) {
                throw new IOException("Overlapping AppleDouble map sidecar entries");
            }
        }
    }
}

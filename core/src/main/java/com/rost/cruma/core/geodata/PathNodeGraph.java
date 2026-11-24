package com.rost.cruma.core.geodata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.foreign.ValueLayout.OfInt;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

public final class PathNodeGraph implements AutoCloseable {
    public static final int NODE_BYTES = 44;
    public static final int SECTION_SIZE = 256;

    private static final int SECTION_COUNT = 20 * 17 * 128 * 128;
    private static final Pattern TOTAL = Pattern.compile("Total\\s*=\\s*(\\d+)");
    private static final Pattern ZONE = Pattern.compile("---\\s*Zone\\((\\d+),(\\d+)\\)\\[(\\d+)]\\s*---");
    private static final OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private final Path indexPath;
    private final Path binaryPath;
    private final FileStamp indexStamp;
    private final FileStamp binaryStamp;
    private final int[] sections;
    private final String indexSha256;
    private final Metadata metadata;
    private final Arena arena;
    private final MemorySegment data;
    private volatile boolean closed;
    private String contentFingerprint = "";

    private PathNodeGraph(
            Path indexPath,
            Path binaryPath,
            FileStamp indexStamp,
            FileStamp binaryStamp,
            int[] sections,
            String indexSha256,
            Metadata metadata,
            Arena arena,
            MemorySegment data) {
        this.indexPath = indexPath;
        this.binaryPath = binaryPath;
        this.indexStamp = indexStamp;
        this.binaryStamp = binaryStamp;
        this.sections = sections;
        this.indexSha256 = indexSha256;
        this.metadata = metadata;
        this.arena = arena;
        this.data = data;
    }

    public record Section(int firstIndex, int count) {}

    public record Node(int index, GeoPoint position, List<Integer> neighbors) {
        public Node {
            neighbors = List.copyOf(neighbors);
        }
    }

    public record Metadata(String sourceId, String fingerprint, int maximumIndex, long binaryBytes) {}

    public record Audit(
            long nodesChecked,
            long edgesChecked,
            long issueCount,
            List<String> issues,
            String binarySha256,
            boolean cancelled) {
        public Audit {
            issues = List.copyOf(issues);
        }

        public boolean isValid() {
            return !cancelled && issueCount == 0;
        }
    }

    public enum ZoneTable {
        Y10_TO_25(25),
        Y10_TO_26(26);

        public final int lastTileY;

        ZoneTable(int lastTileY) {
            this.lastTileY = lastTileY;
        }

        public int getZones() {
            return 20 * (lastTileY - 9);
        }
    }

    public static Optional<ZoneTable> zoneTable(Path directory) throws IOException {
        var path = directory.resolve("pathnode.idx");
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (var reader = Files.newBufferedReader(path, StandardCharsets.US_ASCII)) {
            if (!TOTAL.matcher(requiredLine(reader)).matches()) {
                throw new IOException("Invalid pathnode.idx total header");
            }
            var afterLastC4Zone = false;
            String line;
            while ((line = nextLine(reader)) != null) {
                var zone = ZONE.matcher(line);
                if (!zone.matches()) {
                    continue;
                }
                var x = parseNumber(zone.group(1));
                var y = parseNumber(zone.group(2));
                if (afterLastC4Zone) {
                    if (x == 10 && y == 26) {
                        return Optional.of(ZoneTable.Y10_TO_26);
                    }
                    if (x == 11 && y == 10) {
                        return Optional.of(ZoneTable.Y10_TO_25);
                    }
                    break;
                }
                afterLastC4Zone = x == 10 && y == 25;
            }
        }
        throw new IOException("pathnode.idx has neither the 320-zone nor the 340-zone table");
    }

    public static PathNodeGraph open(Path directory) throws IOException {
        return open(directory, () -> false);
    }

    public static PathNodeGraph open(Path directory, BooleanSupplier cancelled) throws IOException {
        var indexPath = directory.resolve("pathnode.idx").toAbsolutePath().normalize();
        var binaryPath = directory.resolve("pathnode.bin").toAbsolutePath().normalize();
        var indexStamp = fileStamp(indexPath);
        var binaryStamp = fileStamp(binaryPath);
        var index = readIndex(indexPath, cancelled);
        var expectedBytes = (index.maximumIndex + 1L) * NODE_BYTES;
        if (binaryStamp.size != expectedBytes) {
            throw new IOException("pathnode.bin has " + binaryStamp.size + " bytes, expected " + expectedBytes);
        }
        var arena = Arena.ofShared();
        try (var file = FileChannel.open(binaryPath, StandardOpenOption.READ)) {
            var data = file.map(MapMode.READ_ONLY, 0, expectedBytes, arena);
            if (!indexStamp.equals(fileStamp(indexPath)) || !binaryStamp.equals(fileStamp(binaryPath))) {
                throw new IOException("Path-node files changed while opening");
            }
            var fingerprint = index.sha256 + ":" + indexStamp + ":" + binaryStamp;
            return new PathNodeGraph(
                    indexPath,
                    binaryPath,
                    indexStamp,
                    binaryStamp,
                    index.sections,
                    index.sha256,
                    new Metadata(
                            directory.toAbsolutePath().normalize().toString(),
                            fingerprint,
                            index.maximumIndex,
                            expectedBytes),
                    arena,
                    data);
        } catch (IOException | RuntimeException | Error error) {
            arena.close();
            throw error;
        }
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public boolean isSourceUnchanged() throws IOException {
        return !closed && indexStamp.equals(fileStamp(indexPath)) && binaryStamp.equals(fileStamp(binaryPath));
    }

    public synchronized String computeContentFingerprint(BooleanSupplier cancelled) throws IOException {
        requireOpen();
        if (!isSourceUnchanged()) {
            throw new IOException("Path-node source changed since loading");
        }
        if (contentFingerprint.isEmpty()) {
            var result = audit(cancelled);
            if (result.cancelled()) {
                throw new InterruptedIOException("Path-node fingerprint cancelled");
            }
            if (!result.isValid()) {
                throw new IOException("Invalid path-node graph: " + result.issues());
            }
        }
        return contentFingerprint;
    }

    public synchronized Section getSection(int worldX, int worldY) {
        requireOpen();
        var section = sectionOffset(worldX, worldY);
        if (section < 0) {
            return new Section(0, 0);
        }
        var packed = sections[section];
        return new Section(packed >>> 8, packed & 255);
    }

    public synchronized Node getNode(int index) throws IOException {
        requireOpen();
        validateIndex(index);
        var offset = (long) index * NODE_BYTES;
        var x = data.get(INT, offset);
        var y = data.get(INT, offset + 4);
        var z = data.get(INT, offset + 8);
        validatePosition(index, x, y);
        var neighbors = new ArrayList<Integer>(8);
        for (var slot = 0; slot < 8; slot++) {
            var neighbor = data.get(INT, offset + 12 + 4L * slot);
            validateNeighbor(index, slot, neighbor);
            neighbors.add(neighbor);
        }
        return new Node(index, new GeoPoint(x, y, z), neighbors);
    }

    public synchronized Audit audit(BooleanSupplier cancelled) {
        requireOpen();
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return new Audit(0, 0, 0, List.of(), "", true);
        }
        try {
            if (!isSourceUnchanged()) {
                return new Audit(0, 0, 1, List.of("Path-node source changed before the audit"), "", false);
            }
        } catch (IOException error) {
            return new Audit(0, 0, 1, List.of("Path-node source is unavailable: " + error.getMessage()), "", false);
        }
        var issues = new ArrayList<String>();
        var issueCount = 0L;
        var edges = 0L;
        var digest = sha256();
        var buffer = new byte[64 * 1024];
        var hashOffset = 0L;
        for (var index = 1; index <= metadata.maximumIndex; index++) {
            if ((index & 4095) == 0
                    && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) {
                return new Audit(index - 1L, edges, issueCount, issues, "", true);
            }
            var offset = (long) index * NODE_BYTES;
            try {
                validatePosition(index, data.get(INT, offset), data.get(INT, offset + 4));
                for (var slot = 0; slot < 8; slot++) {
                    var neighbor = data.get(INT, offset + 12 + slot * 4L);
                    validateNeighbor(index, slot, neighbor);
                    if (neighbor != 0) {
                        edges++;
                    }
                }
            } catch (IOException error) {
                issueCount++;
                if (issues.size() < 100) {
                    issues.add(error.getMessage());
                }
            }
            if (offset - hashOffset >= buffer.length || index == metadata.maximumIndex) {
                var limit = index == metadata.maximumIndex ? data.byteSize() : offset;
                while (hashOffset < limit) {
                    var count = (int) Math.min(buffer.length, limit - hashOffset);
                    MemorySegment.copy(data, ValueLayout.JAVA_BYTE, hashOffset, buffer, 0, count);
                    digest.update(buffer, 0, count);
                    hashOffset += count;
                }
            }
        }
        if (metadata.maximumIndex == 0) {
            digest.update(data.toArray(ValueLayout.JAVA_BYTE));
        }
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return new Audit(metadata.maximumIndex, edges, issueCount, issues, "", true);
        }
        try {
            if (!isSourceUnchanged()) {
                issueCount++;
                if (issues.size() < 100) {
                    issues.add("Path-node source changed during the audit");
                }
            }
        } catch (IOException error) {
            issueCount++;
            if (issues.size() < 100) {
                issues.add("Path-node source is unavailable: " + error.getMessage());
            }
        }
        var binaryHash = HexFormat.of().formatHex(digest.digest());
        if (issueCount == 0) {
            contentFingerprint = HexFormat.of()
                    .formatHex(sha256().digest((indexSha256 + ":" + binaryHash).getBytes(StandardCharsets.US_ASCII)));
        }
        return new Audit(metadata.maximumIndex, edges, issueCount, issues, binaryHash, false);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            arena.close();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Path-node graph is closed");
        }
    }

    private void validateIndex(int index) throws IOException {
        if (index < 1 || index > metadata.maximumIndex) {
            throw new IOException("Invalid path-node index " + Integer.toUnsignedString(index));
        }
    }

    private void validateNeighbor(int node, int slot, int neighbor) throws IOException {
        if (neighbor < 0 || neighbor > metadata.maximumIndex) {
            throw new IOException(
                    "Node " + node + " slot " + slot + " has invalid neighbor " + Integer.toUnsignedString(neighbor));
        }
    }

    private void validatePosition(int index, int x, int y) throws IOException {
        var section = sectionOffset(x, y);
        var packed = section < 0 ? 0 : sections[section];
        var first = packed >>> 8;
        if (index < first || index >= first + (packed & 255)) {
            throw new IOException("Node " + index + " position does not belong to its indexed section");
        }
    }

    private static int sectionOffset(int x, int y) {
        var mapX = ((long) x + 0x50000) >> 15;
        var mapY = ((long) y + 0x40000) >> 15;
        if (mapX < 0 || mapX >= 20 || mapY < 0 || mapY >= 17) {
            return -1;
        }
        return (((int) mapX * 17 + (int) mapY) * 128 + ((x >>> 8) & 127)) * 128 + ((y >>> 8) & 127);
    }

    private static Index readIndex(Path path, BooleanSupplier cancelled) throws IOException {
        var digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(path), digest);
                var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII), 64 * 1024)) {
            var header = TOTAL.matcher(requiredLine(reader));
            if (!header.matches()) {
                throw new IOException("Invalid pathnode.idx total header");
            }
            var maximum = parseNumber(header.group(1));
            if (maximum > 0xffffff) {
                throw new IOException("Path-node index exceeds the server's 24-bit section offset");
            }
            var sections = new int[SECTION_COUNT];
            var accumulated = 0;
            var lastMapY = 26;
            String pendingZone = null;
            for (var mapX = 10; mapX < 30; mapX++) {
                for (var mapY = 10; mapY <= lastMapY; mapY++) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Path-node loading cancelled");
                    }
                    var line = pendingZone == null ? requiredLine(reader) : pendingZone;
                    pendingZone = null;
                    var zone = ZONE.matcher(line);
                    if (mapX == 10
                            && mapY == 26
                            && zone.matches()
                            && parseNumber(zone.group(1)) == 11
                            && parseNumber(zone.group(2)) == 10) {
                        lastMapY = 25;
                        pendingZone = line;
                        break;
                    }
                    if (!zone.matches() || parseNumber(zone.group(1)) != mapX || parseNumber(zone.group(2)) != mapY) {
                        throw new IOException("Invalid pathnode.idx zone, expected " + mapX + "," + mapY);
                    }
                    if (parseNumber(zone.group(3)) == 0) {
                        continue;
                    }
                    var worldX = (mapX - 20) * 32768;
                    var worldY = (mapY - 18) * 32768;
                    for (var x = worldX; x < worldX + 32768; x += SECTION_SIZE) {
                        for (var y = worldY; y < worldY + 32768; y += SECTION_SIZE) {
                            var cursor = new Numbers(requiredLine(reader));
                            var fileX = cursor.next();
                            var fileY = cursor.next();
                            var count = cursor.next();
                            if (!cursor.isAtEnd()
                                    || fileX != x
                                    || fileY != y
                                    || count < 0
                                    || count > 255
                                    || accumulated + (long) count > maximum) {
                                throw new IOException("Invalid pathnode.idx section at " + x + "," + y);
                            }
                            if (count != 0) {
                                sections[sectionOffset(x, y)] = ((accumulated + 1) << 8) | count;
                            }
                            accumulated += count;
                        }
                    }
                }
            }
            if (accumulated != maximum || nextLine(reader) != null) {
                throw new IOException("Path-node total mismatch or trailing index data");
            }
            return new Index(sections, maximum, HexFormat.of().formatHex(digest.digest()));
        }
    }

    private static int parseNumber(String value) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IOException("Invalid path-node integer", error);
        }
    }

    private static String requiredLine(BufferedReader reader) throws IOException {
        var line = nextLine(reader);
        if (line == null) {
            throw new IOException("Truncated pathnode.idx");
        }
        return line;
    }

    private static String nextLine(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return null;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static FileStamp fileStamp(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attrs.isRegularFile()) {
            throw new IOException("Not a regular path-node file: " + path.getFileName());
        }
        return new FileStamp(attrs.size(), attrs.lastModifiedTime().toString(), String.valueOf(attrs.fileKey()));
    }

    private record FileStamp(long size, String modified, String fileKey) {}

    private record Index(int[] sections, int maximumIndex, String sha256) {}

    private static final class Numbers {
        private final String text;
        private int position;

        private Numbers(String text) {
            this.text = text;
        }

        private int next() throws IOException {
            skipSpace();
            var start = position;
            if (position < text.length() && text.charAt(position) == '-') {
                position++;
            }
            var digits = position;
            var value = 0L;
            while (position < text.length()) {
                var character = text.charAt(position);
                if (character < '0' || character > '9') {
                    break;
                }
                value = value * 10 + character - '0';
                if (value > 2147483648L) {
                    throw new IOException("Path-node integer is out of range");
                }
                position++;
            }
            if (digits == position) {
                throw new IOException("Missing path-node integer");
            }
            if (position < text.length() && !Character.isWhitespace(text.charAt(position))) {
                throw new IOException("Invalid path-node integer separator");
            }
            if (text.charAt(start) == '-') {
                value = -value;
            }
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IOException("Path-node integer is out of range");
            }
            return (int) value;
        }

        private boolean isAtEnd() {
            skipSpace();
            return position == text.length();
        }

        private void skipSpace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }
}

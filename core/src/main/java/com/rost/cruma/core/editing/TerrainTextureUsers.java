package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TerrainTextureUsers {
    private static final Map<Path, TerrainTextureUsers> clients = new ConcurrentHashMap<>();
    private static final int FIRST_READ = 256 * 1024;
    private static final int MOST_READ = 64 * 1024 * 1024;

    private final Map<String, List<String>> mapsByTexture;
    private final List<String> unread;
    private final int maps;
    private final long nanos;
    private final String folder;

    private TerrainTextureUsers(
            Map<String, List<String>> mapsByTexture, List<String> unread, int maps, long nanos, String folder) {
        this.mapsByTexture = mapsByTexture;
        this.unread = List.copyOf(unread);
        this.maps = maps;
        this.nanos = nanos;
        this.folder = folder;
    }

    public static TerrainTextureUsers of(Path clientRoot) throws IOException {
        var key = clientRoot.toAbsolutePath().normalize();
        var known = clients.get(key);
        if (known != null && known.folder.equals(fingerprint(key.resolve("Maps")))) {
            return known;
        }
        var scanned = scan(key.resolve("Maps"));
        clients.put(key, scanned);
        return scanned;
    }

    public static TerrainTextureUsers scan(Path mapsFolder) throws IOException {
        var started = System.nanoTime();
        var folder = fingerprint(mapsFolder);
        var byTexture = new HashMap<String, List<String>>();
        var unread = new ArrayList<String>();
        var count = 0;
        if (Files.isDirectory(mapsFolder)) {
            List<Path> files;
            try (var listing = Files.list(mapsFolder)) {
                files = listing.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT)
                                .endsWith(".unr"))
                        .sorted()
                        .toList();
            }
            for (var file : files) {
                var name = file.getFileName().toString();
                var map = name.substring(0, name.length() - 4);
                count++;
                try {
                    for (var texture : importedTextures(file)) {
                        var users = byTexture.computeIfAbsent(texture, ignored -> new ArrayList<>());
                        if (!users.contains(map)) {
                            users.add(map);
                        }
                    }
                } catch (IOException | RuntimeException unreadable) {
                    unread.add(map);
                }
            }
        }
        var frozen = new HashMap<String, List<String>>();
        byTexture.forEach((texture, users) -> frozen.put(texture, List.copyOf(users)));
        return new TerrainTextureUsers(Map.copyOf(frozen), unread, count, System.nanoTime() - started, folder);
    }

    public static boolean isTwin(String map, String other) {
        return !map.equalsIgnoreCase(other) && withoutClassic(map).equalsIgnoreCase(withoutClassic(other));
    }

    public List<String> findMaps(String objectPath) {
        return mapsByTexture.getOrDefault(objectPath.toLowerCase(Locale.ROOT), List.of());
    }

    public List<String> getUnread() {
        return unread;
    }

    public int getMapCount() {
        return maps;
    }

    public long getScanNanos() {
        return nanos;
    }

    private static String fingerprint(Path mapsFolder) throws IOException {
        if (!Files.isDirectory(mapsFolder)) {
            return "";
        }
        var text = new StringBuilder();
        try (var listing = Files.list(mapsFolder)) {
            for (var file : listing.sorted().toList()) {
                var name = file.getFileName().toString();
                if (Files.isRegularFile(file) && name.toLowerCase(Locale.ROOT).endsWith(".unr")) {
                    text.append(name)
                            .append(':')
                            .append(Files.size(file))
                            .append(':')
                            .append(Files.getLastModifiedTime(file).toMillis())
                            .append('\n');
                }
            }
        }
        return text.toString();
    }

    private static List<String> importedTextures(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var size = channel.size();
            var head = read(channel, 0, (int) Math.min(size, 28));
            var container = 0;
            var key = 0;
            if (head.length >= 4 && PackageBytes.littleEndian(head).getInt(0) == PackageBytes.MAGIC) {
                container = 0;
            } else {
                if (head.length < 28) {
                    throw new IOException("Truncated Lineage container");
                }
                var header = new String(head, 0, 28, StandardCharsets.UTF_16LE);
                container = switch (header) {
                    case "Lineage2Ver111" -> 28;
                    case "Lineage2Ver121" -> 28;
                    default -> throw new IOException("Unsupported Lineage container: " + header);
                };
                key = header.equals("Lineage2Ver111")
                        ? 0xac
                        : PackageBytes.filenameKey(file.getFileName().toString());
            }
            var summary = body(channel, container, key, 0, 64, size);
            var buffer = PackageBytes.littleEndian(summary);
            if (buffer.getInt(0) != PackageBytes.MAGIC) {
                throw new IOException("Invalid Unreal package signature");
            }
            var nameCount = PackageBytes.count(buffer.getInt(12));
            var nameOffset = buffer.getInt(16);
            var importCount = PackageBytes.count(buffer.getInt(28));
            var importOffset = buffer.getInt(32);
            var names = new ArrayList<String>(nameCount);
            readTable(channel, container, key, nameOffset, size, cursor -> {
                names.clear();
                for (var i = 0; i < nameCount; i++) {
                    names.add(cursor.readString());
                    cursor.readI32();
                }
            });
            var classNames = new int[importCount];
            var outers = new int[importCount];
            var objectNames = new int[importCount];
            readTable(channel, container, key, importOffset, size, cursor -> {
                for (var i = 0; i < importCount; i++) {
                    cursor.readIndex();
                    classNames[i] = cursor.readIndex();
                    outers[i] = cursor.readI32();
                    objectNames[i] = cursor.readIndex();
                }
            });
            var textures = new ArrayList<String>();
            for (var i = 0; i < importCount; i++) {
                if (!name(names, classNames[i]).equalsIgnoreCase("Texture")) {
                    continue;
                }
                var path = new StringBuilder(name(names, objectNames[i]));
                var outer = outers[i];
                for (var depth = 0; outer < 0 && depth < 16; depth++) {
                    var at = -outer - 1;
                    if (at >= importCount) {
                        throw new IOException("Invalid import outer");
                    }
                    path.insert(0, name(names, objectNames[at]) + '.');
                    outer = outers[at];
                }
                textures.add(path.toString().toLowerCase(Locale.ROOT));
            }
            return textures;
        }
    }

    @FunctionalInterface
    private interface TableReader {
        void read(Cursor cursor) throws IOException;
    }

    private static void readTable(
            FileChannel channel, int container, int key, int offset, long size, TableReader reader) throws IOException {
        var length = FIRST_READ;
        while (true) {
            var bytes = body(channel, container, key, offset, length, size);
            try {
                reader.read(new Cursor(bytes, 0, bytes.length));
                return;
            } catch (IOException shortRead) {
                if (bytes.length < length || length >= MOST_READ) {
                    throw shortRead;
                }
                length *= 4;
            }
        }
    }

    private static byte[] body(FileChannel channel, int container, int key, long offset, int length, long size)
            throws IOException {
        if (offset < 0 || container + offset > size) {
            throw new IOException("Package table lies outside the file");
        }
        var bytes = read(channel, container + offset, (int) Math.min(length, size - container - offset));
        if (key != 0) {
            for (var i = 0; i < bytes.length; i++) {
                bytes[i] ^= (byte) key;
            }
        }
        return bytes;
    }

    private static byte[] read(FileChannel channel, long position, int length) throws IOException {
        var buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                break;
            }
        }
        var bytes = new byte[buffer.position()];
        buffer.flip().get(bytes);
        return bytes;
    }

    private static String name(List<String> names, int index) throws IOException {
        if (index < 0 || index >= names.size()) {
            throw new IOException("Invalid name index");
        }
        return names.get(index);
    }

    private static String withoutClassic(String map) {
        return map.toLowerCase(Locale.ROOT).endsWith("_classic") ? map.substring(0, map.length() - 8) : map;
    }
}

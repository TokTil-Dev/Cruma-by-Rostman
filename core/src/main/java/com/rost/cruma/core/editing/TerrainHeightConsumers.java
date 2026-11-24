package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

public final class TerrainHeightConsumers {
    private static final int NAME_CHUNK = 1 << 20;
    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    private TerrainHeightConsumers() {}

    public static List<String> scan(Path clientRoot, String heightObjectPath, Path sourceMap, BooleanSupplier cancelled)
            throws IOException {
        var dot = heightObjectPath.indexOf('.');
        if (dot < 1) {
            throw new IOException("Invalid height texture path " + heightObjectPath);
        }
        var packageName = heightObjectPath.substring(0, dot);
        var maps = listMaps(clientRoot);
        var key = new StringBuilder(clientRoot.toAbsolutePath().normalize().toString())
                .append('|')
                .append(heightObjectPath.toLowerCase(Locale.ROOT))
                .append('|')
                .append(sourceMap.getFileName().toString().toLowerCase(Locale.ROOT));
        for (var map : maps) {
            key.append('|')
                    .append(map.getFileName())
                    .append(':')
                    .append(Files.size(map))
                    .append(':')
                    .append(Files.getLastModifiedTime(map).toMillis());
        }
        var cached = CACHE.get(key.toString());
        if (cached != null) {
            return cached;
        }
        var result = new ArrayList<String>();
        for (var map : maps) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Height texture scan cancelled");
            }
            if (map.getFileName()
                    .toString()
                    .equalsIgnoreCase(sourceMap.getFileName().toString())) {
                continue;
            }
            try {
                if (!hasPackageName(map, packageName)) {
                    continue;
                }
                var source = new PackageBytes(map, Files.readAllBytes(map));
                for (var i = 1; i <= source.imports.size(); i++) {
                    if (source.getObjectPath(-i).equalsIgnoreCase(heightObjectPath)) {
                        result.add(map.getFileName().toString());
                        break;
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
            }
        }
        var frozen = List.copyOf(result);
        CACHE.put(key.toString(), frozen);
        return frozen;
    }

    private static List<Path> listMaps(Path clientRoot) throws IOException {
        var directory = clientRoot.resolve("Maps");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".unr") && !name.startsWith("._");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static boolean hasPackageName(Path map, String packageName) throws IOException {
        try (var channel = FileChannel.open(map, StandardOpenOption.READ)) {
            var head = read(channel, 0, 28 + 64);
            var prefix = 0;
            var key = 0;
            var magic = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            if (magic != PackageBytes.MAGIC) {
                var container = new String(head, 0, 28, StandardCharsets.UTF_16LE);
                prefix = 28;
                key = switch (container) {
                    case "Lineage2Ver111" -> 0xac;
                    case "Lineage2Ver121" ->
                        PackageBytes.filenameKey(map.getFileName().toString());
                    default -> throw new IOException("Unsupported container");
                };
            }
            var header = decrypt(read(channel, prefix, 36), key);
            var buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.getInt(0) != PackageBytes.MAGIC) {
                throw new IOException("Invalid package");
            }
            var nameCount = buffer.getInt(12);
            var nameOffset = buffer.getInt(16);
            var size = (int) Math.min(channel.size() - prefix - nameOffset, NAME_CHUNK);
            while (true) {
                var names = decrypt(read(channel, prefix + nameOffset, size), key);
                var cursor = new Cursor(names, 0, names.length);
                try {
                    for (var i = 0; i < nameCount; i++) {
                        if (cursor.readString().equalsIgnoreCase(packageName)) {
                            return true;
                        }
                        cursor.readI32();
                    }
                    return false;
                } catch (IOException | RuntimeException truncated) {
                    var limit = channel.size() - prefix - nameOffset;
                    if (size >= limit) {
                        throw truncated instanceof IOException io ? io : new IOException(truncated);
                    }
                    size = (int) Math.min(limit, (long) size * 4);
                }
            }
        }
    }

    private static byte[] read(FileChannel channel, long position, int length) throws IOException {
        var buffer = ByteBuffer.allocate(Math.max(0, length));
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                break;
            }
        }
        return buffer.array();
    }

    private static byte[] decrypt(byte[] bytes, int key) {
        if (key != 0) {
            for (var i = 0; i < bytes.length; i++) {
                bytes[i] ^= (byte) key;
            }
        }
        return bytes;
    }
}

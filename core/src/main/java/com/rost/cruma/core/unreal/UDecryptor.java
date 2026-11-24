package com.rost.cruma.core.unreal;

import static java.lang.Character.toLowerCase;

import com.rost.cruma.core.world.NativeMemory;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;

public class UDecryptor {
    private static final int LINEAGE_SIZE = 22;
    private static final int VERSION_SIZE = 6;
    private static final int READ_CHUNK_SIZE = 1 << 20;
    private static final int PACKAGE_TAG = 0x9E2A83C1;

    private static final byte[] LINEAGE_HEADER = {
        0x4c, 0x00, 0x69, 0x00, 0x6e, 0x00, 0x65, 0x00, 0x61, 0x00, 0x67, 0x00, 0x65, 0x00, 0x32, 0x00, 0x56, 0x00,
        0x65, 0x00, 0x72, 0x00
    };

    private static final int XOR_CHUNK_LONGS = 8192;

    public static ByteBuffer decrypt(Path path) {
        try {
            return decryptHeap(path).getBuffer();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read client file " + path, e);
        }
    }

    public static DecryptedBuffer decryptManaged(Path path) {
        var arena = Arena.ofShared();
        try (var fileChannel = FileChannel.open(path, StandardOpenOption.READ)) {
            var size = Math.toIntExact(fileChannel.size());
            var buffer =
                    NativeMemory.allocateUnzeroed(size, arena).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            readFully(fileChannel, buffer);
            buffer.clear();
            decryptBuffer(buffer, path);
            return new DecryptedBuffer(buffer, arena);
        } catch (IOException failure) {
            arena.close();
            throw new UncheckedIOException("Cannot read client file " + path, failure);
        } catch (RuntimeException | Error failure) {
            arena.close();
            throw failure;
        }
    }

    public static ByteBuffer decrypt(byte[] file, Path path) {
        var plain = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (file.length >= 4 && plain.getInt(0) == PACKAGE_TAG) {
            return plain;
        }
        var buffer = ByteBuffer.wrap(file.clone()).order(ByteOrder.LITTLE_ENDIAN);
        decryptBuffer(buffer, path);
        return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void readFully(FileChannel fileChannel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            var slice = buffer.slice(buffer.position(), Math.min(READ_CHUNK_SIZE, buffer.remaining()));
            var read = fileChannel.read(slice);
            if (read < 0) {
                throw new EOFException("package ended before its announced size");
            }
            buffer.position(buffer.position() + read);
        }
    }

    private static DecryptedBuffer decryptHeap(Path path) throws IOException {
        try (var fileChannel = FileChannel.open(path, StandardOpenOption.READ)) {
            var size = Math.toIntExact(fileChannel.size());
            var buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.hasRemaining() && fileChannel.read(buffer) >= 0) {
            }
            buffer.flip();
            decryptBuffer(buffer, path);
            return new DecryptedBuffer(buffer, null);
        }
    }

    private static void decryptBuffer(ByteBuffer buffer, Path path) {
        var version = extractVersion(buffer);
        if (version.isEmpty()) {
            if (buffer.limit() >= 4 && buffer.getInt(0) == PACKAGE_TAG) {
                buffer.position(0);
                return;
            }
            throw new RuntimeException("Can't detect Lineage 2 encryption version of " + path);
        }
        switch (version.get()) {
            case 111 -> decryptV111(buffer);
            case 121 -> decryptV121(buffer, path);
            default ->
                throw new UnsupportedOperationException("Unsupported Lineage 2 encryption version: " + version.get());
        }
    }

    private static Optional<Integer> extractVersion(ByteBuffer buffer) {
        var lineageBuffer = new byte[LINEAGE_SIZE];
        var versionBuffer = new byte[VERSION_SIZE];
        buffer.get(lineageBuffer, 0, LINEAGE_SIZE);
        buffer.get(versionBuffer, 0, VERSION_SIZE);

        if (!Arrays.equals(lineageBuffer, LINEAGE_HEADER)) {
            return Optional.empty();
        }
        var version = (versionBuffer[0] - '0') * 100 + (versionBuffer[2] - '0') * 10 + (versionBuffer[4] - '0');
        return Optional.of(version);
    }

    private static void decryptXor(ByteBuffer buffer, int key) {
        var xor = (byte) key;
        var word = (xor & 0xFFL) * 0x0101010101010101L;
        var segment = MemorySegment.ofBuffer(buffer);
        var limit = segment.byteSize();
        var chunk = new long[XOR_CHUNK_LONGS];
        var index = 0L;
        while (index + Long.BYTES <= limit) {
            var count = (int) Math.min(XOR_CHUNK_LONGS, (limit - index) / Long.BYTES);
            MemorySegment.copy(segment, ValueLayout.JAVA_LONG_UNALIGNED, index, chunk, 0, count);
            for (var i = 0; i < count; i++) {
                chunk[i] ^= word;
            }
            MemorySegment.copy(chunk, 0, segment, ValueLayout.JAVA_LONG_UNALIGNED, index, count);
            index += (long) count * Long.BYTES;
        }
        for (; index < limit; index++) {
            segment.set(ValueLayout.JAVA_BYTE, index, (byte) (segment.get(ValueLayout.JAVA_BYTE, index) ^ xor));
        }
    }

    private static void decryptV111(ByteBuffer buffer) {
        decryptXor(buffer, (byte) 0xAC);
    }

    private static void decryptV121(ByteBuffer buffer, Path path) {
        var filename = path.getFileName().toString();
        int key = 0;
        for (char character : filename.toCharArray()) {
            key += toLowerCase(character);
        }
        decryptXor(buffer, (byte) key);
    }

    public static final class DecryptedBuffer implements AutoCloseable {
        private final ByteBuffer buffer;
        private final Arena arena;
        private final Runnable onClose;
        private boolean closed;

        private DecryptedBuffer(ByteBuffer buffer, Arena arena, Runnable onClose) {
            this.buffer = buffer;
            this.arena = arena;
            this.onClose = onClose;
        }

        private DecryptedBuffer(ByteBuffer buffer, Arena arena) {
            this(buffer, arena, null);
        }

        public ByteBuffer getBuffer() {
            if (closed) {
                throw new IllegalStateException("Decrypted package buffer is closed");
            }
            return buffer;
        }

        DecryptedBuffer shared(Runnable onClose) {
            var view = getBuffer().asReadOnlyBuffer().order(buffer.order());
            return new DecryptedBuffer(view, null, onClose);
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                if (arena != null) {
                    arena.close();
                }
                if (onClose != null) {
                    onClose.run();
                }
            }
        }
    }
}

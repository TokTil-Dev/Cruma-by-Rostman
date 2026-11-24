package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.L2DataKeys.Key;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.jetbrains.annotations.Nullable;

public final class L2DataFile {
    static final int AFTER_SIZE = -1;

    private static final Set<String> HEADERS = Set.of("Lineage2Ver411", "Lineage2Ver412", "Lineage2Ver413");
    private static final int HEADER_BYTES = "Lineage2Ver413".length() * 2;
    private static final int BLOCK_BYTES = 128;
    private static final int SIZE_BYTES = 4;

    private static final List<Key> KEYS = List.of(
            new Key(
                    BigInteger.valueOf(0x1D),
                    new BigInteger(
                            "75b4d6de5c016544068a1acf125869f43d2e09fc55b8b1e289556daf9b8757635593446288b3653da1ce91c87bb1a5c1"
                                + "8f16323495c55d7d72c0890a83f69bfd1fd9434eb1c02f3e4679edfa43309319070129c267c85604d87bb65bae205de3"
                                + "707af1d2108881abb567c3b3d069ae67c3a4c6a3aa93d26413d4c66094ae2039",
                            16)),
            new Key(
                    BigInteger.valueOf(0x35),
                    new BigInteger(
                            "97df398472ddf737ef0a0cd17e8d172f0fef1661a38a8ae1d6e829bc1c6e4c3cfc19292dda9ef90175e46e7394a18850"
                                + "b6417d03be6eea274d3ed1dde5b5d7bde72cc0a0b71d03608655633881793a02c9a67d9ef2b45eb7c08d4be329083ce4"
                                + "50e68f7867b6749314d40511d09bc5744551baa86a89dc38123dc1668fd72d83",
                            16)),
            new Key(
                    BigInteger.valueOf(0x25),
                    new BigInteger(
                            "a465134799cf2c45087093e7d0f0f144e6d528110c08f674730d436e40827330eccea46e70acf10cdda7d8f710e3b44d"
                                + "cca931812d76cd7494289bca8b73823f57efc0515b97e4a2a02612ccfa719cf7885104b06f2e7e2cc967b62e3d3b1aad"
                                + "b925db94cbc8cd3070a4bb13f7e202c7733a67b1b94c1ebc0afcbe1a63b448cf",
                            16)));
    private static final int MAX_GAP = 16;
    private static final long MAX_TABLE = 512L << 20;

    private static final String SYSTEM = "system";
    private static final String SCHEMAS = "cruma/schemas";
    private static volatile @Nullable Path userSchemaFolder;

    private L2DataFile() {}

    public static byte[] read(Path path) throws IOException {
        return decode(Files.readAllBytes(path), path.toString(), path);
    }

    public static void setUserSchemaFolder(@Nullable Path folder) {
        userSchemaFolder = folder;
    }

    public static List<String> findKeyProblems(Path system) {
        var problems = new ArrayList<String>();
        for (var file : keyFiles(system)) {
            problems.addAll(L2DataKeys.read(file).problems());
        }
        return problems;
    }

    public static boolean isDataFile(byte[] data) {
        return data.length >= HEADER_BYTES
                && HEADERS.contains(new String(data, 0, HEADER_BYTES, StandardCharsets.UTF_16LE));
    }

    static byte[] decode(byte[] data, String source) throws IOException {
        return decode(data, source, null);
    }

    static byte[] decode(byte[] data, String source, @Nullable Path file) throws IOException {
        if (!isDataFile(data)) {
            throw new IOException(source + " is not a Lineage2Ver411, Lineage2Ver412 or Lineage2Ver413 file");
        }
        var reasons = new ArrayList<String>();
        var table = decode(data, source, KEYS, reasons);
        if (table == null && file != null) {
            var keys = keysOf(file);
            table = decode(data, source, keys.subList(KEYS.size(), keys.size()), reasons);
        }
        if (table == null) {
            throw new IOException(String.join("; ", reasons));
        }
        return table;
    }

    static List<Key> keysOf(Path file) {
        var keys = new ArrayList<>(KEYS);
        var folder = file.toAbsolutePath().normalize().getParent();
        if (folder == null) {
            return keys;
        }
        for (var keysFile : keyFiles(folder)) {
            for (var key : L2DataKeys.read(keysFile).keys()) {
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    static byte[] inflate(List<byte[]> blocks, int gap, String source) throws IOException {
        var payload = new ByteArrayOutputStream(blocks.size() * BLOCK_BYTES);
        for (var clear : blocks) {
            var size = ((clear[0] & 0xff) << 24)
                    | ((clear[1] & 0xff) << 16)
                    | ((clear[2] & 0xff) << 8)
                    | (clear[3] & 0xff);
            if (size <= 0 || size > BLOCK_BYTES - SIZE_BYTES) {
                continue;
            }
            var from = gap == AFTER_SIZE ? SIZE_BYTES : Math.max(SIZE_BYTES, BLOCK_BYTES - gap - size);
            payload.write(clear, from, size);
        }
        var joined = payload.toByteArray();
        if (joined.length < SIZE_BYTES) {
            throw new IOException(source + " decrypted to no payload");
        }
        var expected = (joined[0] & 0xff)
                | ((joined[1] & 0xff) << 8)
                | ((joined[2] & 0xff) << 16)
                | ((joined[3] & 0xff) << 24);
        if (expected <= 0) {
            throw new IOException(source + " declares an invalid uncompressed size " + expected);
        }
        var compressed = joined.length - SIZE_BYTES;
        var most = Math.min(MAX_TABLE, compressed * 1032L + 1024);
        if (expected > most) {
            throw new IOException(source + " declares " + expected + " bytes, more than its " + compressed
                    + " bytes of zlib can give");
        }
        var inflater = new Inflater();
        try {
            inflater.setInput(joined, SIZE_BYTES, joined.length - SIZE_BYTES);
            var result = new byte[expected];
            var produced = 0;
            while (produced < expected && !inflater.finished()) {
                var count = inflater.inflate(result, produced, expected - produced);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                produced += count;
            }
            if (produced != expected) {
                throw new IOException(source + " inflated to " + produced + " bytes, header says " + expected);
            }
            if (!inflater.finished() && inflater.inflate(new byte[1]) != 0 || !inflater.finished()) {
                throw new IOException(source + " holds more data than its header says, or a stream without its end");
            }
            return result;
        } catch (DataFormatException e) {
            throw new IOException(source + " holds a corrupt zlib stream", e);
        } finally {
            inflater.end();
        }
    }

    private static byte @Nullable [] decode(byte[] data, String source, List<Key> keys, List<String> reasons) {
        for (var key : keys) {
            var blocks = decrypt(data, key);
            for (var attempt = 0; attempt <= MAX_GAP + 1; attempt++) {
                var gap = attempt <= MAX_GAP ? attempt : AFTER_SIZE;
                try {
                    return inflate(blocks, gap, source);
                } catch (IOException failure) {
                    if (gap == AFTER_SIZE || gap == 0) {
                        reasons.add(failure.getMessage());
                    }
                }
            }
        }
        return null;
    }

    private static List<Path> keyFiles(Path folder) {
        var files = new ArrayList<Path>(2);
        var absolute = folder.toAbsolutePath().normalize();
        var parent = absolute.getParent();
        if (parent != null
                && parent.getFileName() != null
                && parent.getFileName().toString().equalsIgnoreCase(SYSTEM)) {
            absolute = parent;
        }
        var name = absolute.getFileName();
        var client = absolute.getParent();
        if (name != null && client != null && name.toString().equalsIgnoreCase(SYSTEM)) {
            files.add(client.resolve(SCHEMAS).resolve(L2DataKeys.FILE_NAME));
        }
        var user = userSchemaFolder;
        if (user != null) {
            files.add(user.resolve(L2DataKeys.FILE_NAME));
        }
        return files;
    }

    private static List<byte[]> decrypt(byte[] data, Key key) {
        var blocks = new ArrayList<byte[]>();
        for (var offset = HEADER_BYTES; offset + BLOCK_BYTES <= data.length; offset += BLOCK_BYTES) {
            var block = new BigInteger(1, Arrays.copyOfRange(data, offset, offset + BLOCK_BYTES));
            var number = block.modPow(key.exponent(), key.modulus()).toByteArray();
            var clear = new byte[BLOCK_BYTES];
            var used = Math.min(number.length, BLOCK_BYTES);
            System.arraycopy(number, number.length - used, clear, BLOCK_BYTES - used, used);
            blocks.add(clear);
        }
        return blocks;
    }
}

package com.rost.cruma.core.editing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152CaptureIo {
    private static final int CHUNK = 64 * 1024;

    private P152CaptureIo() {}

    static byte[] read(Path path, int maximum, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        if (maximum < 0 || Files.size(path) > maximum) {
            throw new IOException("P152 input source exceeds capture size bound");
        }
        try (var input = Files.newInputStream(path)) {
            var output = new ByteArrayOutputStream(Math.min(maximum, CHUNK));
            var buffer = new byte[CHUNK];
            while (true) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var count = input.read(buffer);
                P152FreshHashQuery.checkCancelled(cancelled);
                if (count < 0) {
                    break;
                }
                if (count > maximum - output.size()) {
                    throw new IOException("P152 input source exceeds capture size bound");
                }
                output.write(buffer, 0, count);
            }
            var bytes = output.toByteArray();
            P152FreshHashQuery.checkCancelled(cancelled);
            return bytes;
        }
    }

    static String hash(byte[] bytes, BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        for (var offset = 0; offset < bytes.length; ) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var count = Math.min(CHUNK, bytes.length - offset);
            digest.update(bytes, offset, count);
            offset += count;
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return HexFormat.of().formatHex(digest.digest());
    }
}

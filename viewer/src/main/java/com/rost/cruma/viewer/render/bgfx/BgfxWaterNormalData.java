package com.rost.cruma.viewer.render.bgfx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class BgfxWaterNormalData {
    private static final int HEADER_BYTES = 8;
    private static final int BYTES_PER_TEXEL = 2;
    private static final int MAX_DIMENSION = 16_384;

    private BgfxWaterNormalData() {}

    static Data read(Path clientRoot) throws IOException {
        return decode(Files.readAllBytes(locate(clientRoot)));
    }

    static Data decode(byte[] file) throws IOException {
        if (file.length < HEADER_BYTES) {
            throw new IOException("Short Wform.bm header: " + file.length + " bytes");
        }
        var header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        var width = header.getInt();
        var height = header.getInt();
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("Invalid Wform.bm dimensions " + width + 'x' + height);
        }
        final int payloadBytes;
        try {
            payloadBytes = Math.multiplyExact(Math.multiplyExact(width, height), BYTES_PER_TEXEL);
        } catch (ArithmeticException overflow) {
            throw new IOException("Overflowing Wform.bm dimensions " + width + 'x' + height, overflow);
        }
        if (file.length < HEADER_BYTES + payloadBytes) {
            throw new IOException("Short Wform.bm payload: " + (file.length - HEADER_BYTES) + " < " + payloadBytes);
        }
        var signed = Arrays.copyOfRange(file, HEADER_BYTES, HEADER_BYTES + payloadBytes);
        var rgba = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        for (var pixel = 0; pixel < width * height; pixel++) {
            rgba[pixel * 4] = (byte) (Math.max(-127, signed[pixel * 2]) + 128);
            rgba[pixel * 4 + 1] = (byte) (Math.max(-127, signed[pixel * 2 + 1]) + 128);
            rgba[pixel * 4 + 2] = (byte) 128;
            rgba[pixel * 4 + 3] = (byte) 255;
        }
        return new Data(width, height, rgba);
    }

    private static Path locate(Path clientRoot) throws IOException {
        for (var relative : new String[] {"system/wform.bm", "system/Wform.bm", "System/Wform.bm"}) {
            var candidate = clientRoot.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("HF water normal file not found below " + clientRoot + " (system/Wform.bm)");
    }

    record Data(int width, int height, byte[] rgba) {}
}

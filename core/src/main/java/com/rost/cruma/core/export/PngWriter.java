package com.rost.cruma.core.export;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

final class PngWriter {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngWriter() {}

    static byte[] encode(int width, int height, byte[] rgba, boolean alpha) {
        if (width < 1 || height < 1 || rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
            throw new IllegalArgumentException("pixels do not match a " + width + "x" + height + " image");
        }
        return encode(width, height, rgba, 4, alpha ? 4 : 3, alpha ? 6 : 2);
    }

    static byte[] encodeGray(int width, int height, byte[] gray) {
        if (width < 1 || height < 1 || gray.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("pixels do not match a " + width + "x" + height + " image");
        }
        return encode(width, height, gray, 1, 1, 0);
    }

    static boolean hasAlpha(byte[] rgba) {
        for (var i = 3; i < rgba.length; i += 4) {
            if (rgba[i] != (byte) 0xFF) {
                return true;
            }
        }
        return false;
    }

    private static byte[] encode(int width, int height, byte[] pixels, int stored, int channels, int colorType) {
        var stride = width * channels;
        var filtered = new byte[Math.multiplyExact(height, stride + 1)];
        var row = new byte[stride];
        for (var y = 0; y < height; y++) {
            var source = y * width * stored;
            for (var x = 0; x < width; x++) {
                for (var channel = 0; channel < channels; channel++) {
                    row[x * channels + channel] = pixels[source + x * stored + channel];
                }
            }
            var target = y * (stride + 1);
            filtered[target] = 1;
            for (var i = 0; i < stride; i++) {
                var left = i >= channels ? row[i - channels] : 0;
                filtered[target + 1 + i] = (byte) (row[i] - left);
            }
        }
        var out = new ByteArrayOutputStream(filtered.length / 2 + 1024);
        out.writeBytes(SIGNATURE);
        var header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8;
        header[9] = (byte) colorType;
        writeChunk(out, "IHDR", header, header.length);
        var deflater = new Deflater(6);
        try {
            deflater.setInput(filtered);
            deflater.finish();
            var compressed = new ByteArrayOutputStream(filtered.length / 2 + 1024);
            var buffer = new byte[1 << 16];
            while (!deflater.finished()) {
                var count = deflater.deflate(buffer);
                compressed.write(buffer, 0, count);
            }
            var data = compressed.toByteArray();
            writeChunk(out, "IDAT", data, data.length);
        } finally {
            deflater.end();
        }
        writeChunk(out, "IEND", new byte[0], 0);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data, int length) {
        var lengthBytes = new byte[4];
        putInt(lengthBytes, 0, length);
        out.writeBytes(lengthBytes);
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.write(data, 0, length);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data, 0, length);
        var crcBytes = new byte[4];
        putInt(crcBytes, 0, (int) crc.getValue());
        out.writeBytes(crcBytes);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}

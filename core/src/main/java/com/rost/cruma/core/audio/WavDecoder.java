package com.rost.cruma.core.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WavDecoder {
    private static final int RIFF = 0x46464952;
    private static final int WAVE = 0x45564157;
    private static final int FMT = 0x20746d66;
    private static final int DATA = 0x61746164;
    private static final int PCM = 1;
    private static final int EXTENSIBLE = 0xFFFE;

    private WavDecoder() {}

    public static SoundClip decode(String name, byte[] file) {
        var buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (file.length < 12 || buffer.getInt(0) != RIFF || buffer.getInt(8) != WAVE) {
            throw new IllegalArgumentException(name + " is not a RIFF WAVE file");
        }
        var format = -1;
        var channels = 0;
        var sampleRate = 0;
        var bits = 0;
        var position = 12;
        while (position + 8 <= file.length) {
            var id = buffer.getInt(position);
            var size = buffer.getInt(position + 4);
            var body = position + 8;
            if (size < 0 || body + size > file.length) {
                size = file.length - body;
            }
            if (id == FMT) {
                format = buffer.getShort(body) & 0xffff;
                channels = buffer.getShort(body + 2) & 0xffff;
                sampleRate = buffer.getInt(body + 4);
                bits = buffer.getShort(body + 14) & 0xffff;
                if (format == EXTENSIBLE && size >= 26) {
                    format = buffer.getShort(body + 24) & 0xffff;
                }
            } else if (id == DATA) {
                if (format != PCM) {
                    throw new IllegalArgumentException(name + ": unsupported WAVE format " + format);
                }
                return new SoundClip(
                        name, channels, sampleRate, decodeSamples(name, buffer, body, size, bits, channels));
            }
            position = body + size + (size & 1);
        }
        throw new IllegalArgumentException(name + " has no data chunk");
    }

    private static short[] decodeSamples(String name, ByteBuffer buffer, int offset, int size, int bits, int channels) {
        var bytesPerSample = switch (bits) {
            case 8 -> 1;
            case 16 -> 2;
            case 24 -> 3;
            default -> throw new IllegalArgumentException(name + ": " + bits + "-bit PCM");
        };
        var frameBytes = bytesPerSample * channels;
        var count = size / frameBytes * channels;
        var samples = new short[count];
        var cursor = offset;
        for (var i = 0; i < count; i++) {
            samples[i] = switch (bytesPerSample) {
                case 1 -> (short) (((buffer.get(cursor) & 0xff) - 128) << 8);
                case 2 -> buffer.getShort(cursor);
                default -> (short) (buffer.get(cursor + 2) << 8 | (buffer.get(cursor + 1) & 0xff));
            };
            cursor += bytesPerSample;
        }
        return samples;
    }
}

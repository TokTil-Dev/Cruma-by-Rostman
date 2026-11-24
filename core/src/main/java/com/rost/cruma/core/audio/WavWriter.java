package com.rost.cruma.core.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WavWriter {
    private static final int HEADER = 44;

    private WavWriter() {}

    public static byte[] encode(SoundClip clip) {
        var samples = clip.samples();
        var data = samples.length * 2;
        var buffer = ByteBuffer.allocate(HEADER + data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'R', 'I', 'F', 'F'});
        buffer.putInt(HEADER - 8 + data);
        buffer.put(new byte[] {'W', 'A', 'V', 'E'});
        buffer.put(new byte[] {'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) clip.channels());
        buffer.putInt(clip.sampleRate());
        buffer.putInt(clip.sampleRate() * clip.channels() * 2);
        buffer.putShort((short) (clip.channels() * 2));
        buffer.putShort((short) 16);
        buffer.put(new byte[] {'d', 'a', 't', 'a'});
        buffer.putInt(data);
        for (var sample : samples) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }
}

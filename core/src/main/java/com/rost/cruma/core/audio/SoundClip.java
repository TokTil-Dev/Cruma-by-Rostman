package com.rost.cruma.core.audio;

public record SoundClip(String name, int channels, int sampleRate, short[] samples) {
    public SoundClip {
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException(name + ": " + channels + " channels");
        }
        if (sampleRate < 1_000 || sampleRate > 192_000) {
            throw new IllegalArgumentException(name + ": " + sampleRate + " Hz");
        }
        if (samples.length % channels != 0) {
            throw new IllegalArgumentException(name + ": sample count is not a whole number of frames");
        }
    }

    public int frames() {
        return samples.length / channels;
    }

    public long bytes() {
        return samples.length * 2L;
    }

    public float seconds() {
        return (float) frames() / sampleRate;
    }
}

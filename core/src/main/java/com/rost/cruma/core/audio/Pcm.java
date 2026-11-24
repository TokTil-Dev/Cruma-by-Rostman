package com.rost.cruma.core.audio;

interface Pcm extends AutoCloseable {
    int getChannels();

    int getSampleRate();

    int read(short[] out);

    long skip(long frames);

    @Override
    void close();
}

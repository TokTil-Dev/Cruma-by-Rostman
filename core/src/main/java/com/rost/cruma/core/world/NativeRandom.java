package com.rost.cruma.core.world;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

public final class NativeRandom implements IntSupplier, DoubleSupplier {
    private int state;

    public NativeRandom() {
        this(1);
    }

    public NativeRandom(int seed) {
        state = seed;
    }

    @Override
    public int getAsInt() {
        state = state * 214013 + 2531011;
        return (state >>> 16) & 32767;
    }

    @Override
    public double getAsDouble() {
        return getAsInt() / 32767f;
    }

    public int getState() {
        return state;
    }
}

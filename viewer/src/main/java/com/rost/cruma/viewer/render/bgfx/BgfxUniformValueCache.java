package com.rost.cruma.viewer.render.bgfx;

import java.lang.foreign.MemorySegment;

final class BgfxUniformValueCache {
    private final float[] previous;
    private final MemorySegment previousBytes;
    private int validFloats;

    BgfxUniformValueCache(int floats) {
        if (floats <= 0) {
            throw new IllegalArgumentException("uniform cache must contain values");
        }
        previous = new float[floats];
        previousBytes = MemorySegment.ofArray(previous);
    }

    void invalidate() {
        validFloats = 0;
    }

    boolean needsWrite(float[] values) {
        return needsWrite(values, previous.length);
    }

    boolean needsWrite(float[] values, int floats) {
        if (values == null || floats < 1 || floats > previous.length || values.length < floats) {
            throw new IllegalArgumentException("uniform payload is shorter than its declaration");
        }
        var bytes = (long) floats * Float.BYTES;
        if (validFloats == floats
                && MemorySegment.mismatch(previousBytes, 0, bytes, MemorySegment.ofArray(values), 0, bytes) < 0) {
            return false;
        }
        System.arraycopy(values, 0, previous, 0, floats);
        validFloats = floats;
        return true;
    }
}

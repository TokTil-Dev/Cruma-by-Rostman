package com.rost.cruma.core.world;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntPredicate;

public final class NativeStaticMeshLightSelection {
    public static final int ROOT_ENVIRONMENT = -1;

    private int[] sources = new int[9];
    private int size;
    private float actorIntensity;

    public int select(
            boolean acceptedInstance,
            int lod,
            boolean rootEnvironment,
            int lightLimit,
            int sourceCount,
            IntPredicate dynamicOrChanged,
            float intensity) {
        if (lod < 0 || lod > 2) {
            throw new IllegalArgumentException("LOD must be in [0,2]");
        }
        if (sourceCount < 0) {
            throw new IllegalArgumentException("negative light count");
        }
        Objects.requireNonNull(dynamicOrChanged);
        size = 0;
        actorIntensity = intensity;
        if (!acceptedInstance && rootEnvironment) {
            append(ROOT_ENVIRONMENT);
        }
        if (lod != 2) {
            for (var index = 0; index < sourceCount && size < lightLimit; index++) {
                if (!acceptedInstance || dynamicOrChanged.test(index)) {
                    append(index);
                }
            }
        }
        return size;
    }

    public int size() {
        return size;
    }

    public boolean isSetLightCalled() {
        return size != 0;
    }

    public int getSource(int slot) {
        return sources[Objects.checkIndex(slot, size)];
    }

    public float getActorIntensity() {
        return actorIntensity;
    }

    private void append(int source) {
        if (size == sources.length) {
            sources = Arrays.copyOf(sources, Math.multiplyExact(size, 2));
        }
        sources[size++] = source;
    }
}

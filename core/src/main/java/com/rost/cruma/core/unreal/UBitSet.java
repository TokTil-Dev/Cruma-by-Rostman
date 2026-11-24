package com.rost.cruma.core.unreal;

public class UBitSet {
    private final short[] data;

    public UBitSet(short[] data) {
        this.data = data;
    }

    public int size() {
        return data.length * 8;
    }

    public boolean get(int index) {
        int byteIndex = index / 8;
        var bits = data[byteIndex];
        int bitIndex = index % 8;
        return (bits & (1 << bitIndex)) != 0;
    }
}

package com.rost.cruma.core.editing;

public enum BspSurfaceFlag {
    TWO_SIDED(BspSurfaceEdit.TWO_SIDED),
    UNLIT(BspSurfaceEdit.UNLIT);

    private final int bit;

    BspSurfaceFlag(int bit) {
        this.bit = bit;
    }

    public int getBit() {
        return bit;
    }
}

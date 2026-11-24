package com.rost.cruma.core.unreal.primitives;

import com.rost.cruma.core.unreal.UArchive;

public record UMatrix(float[][] m) {
    public static UMatrix of(UArchive archive) {
        var m = new float[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                m[i][j] = archive.readFloat();
            }
        }
        return new UMatrix(m);
    }
}

package com.rost.cruma.core.unreal.primitives;

import com.rost.cruma.core.unreal.UArchive;

public record UColor(byte r, byte g, byte b, byte a) {
    private static final int UPACKAGE_UNREAL2_VERSION = 100;

    public static UColor of(UArchive archive) {
        if (archive.getHeader().getFileVersion() > UPACKAGE_UNREAL2_VERSION) {
            var b = archive.readByte();
            var g = archive.readByte();
            var r = archive.readByte();
            var a = archive.readByte();
            return new UColor(r, g, b, a);
        }
        return new UColor(archive.readByte(), archive.readByte(), archive.readByte(), archive.readByte());
    }
}

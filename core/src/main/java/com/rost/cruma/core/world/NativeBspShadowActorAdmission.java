package com.rost.cruma.core.world;

public final class NativeBspShadowActorAdmission {
    private NativeBspShadowActorAdmission() {}

    public static boolean accepts(int optionsFlags, int actorFlags64, int actorFlags68) {
        return (actorFlags64 & 0x200) != 0 && ((optionsFlags & 0x10) == 0 || (actorFlags68 & 0x600000) == 0);
    }
}

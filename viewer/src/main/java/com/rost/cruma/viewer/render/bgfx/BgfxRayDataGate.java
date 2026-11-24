package com.rost.cruma.viewer.render.bgfx;

final class BgfxRayDataGate {
    private volatile boolean collecting;
    private boolean mapLeftOut;
    private boolean reloadWanted;

    boolean isCollecting() {
        return collecting;
    }

    boolean ask() {
        if (collecting) {
            return false;
        }
        collecting = true;
        reloadWanted = mapLeftOut;
        return true;
    }

    void leftOut() {
        mapLeftOut = true;
        reloadWanted |= collecting;
    }

    boolean takeReload() {
        var wanted = reloadWanted;
        reloadWanted = false;
        return wanted;
    }
}

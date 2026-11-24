package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.render.bgfx.BgfxNativeWaterLighting.Draw;
import java.util.Arrays;

final class BgfxWaterInstanceBatching {
    private BgfxWaterInstanceBatching() {}

    static boolean hasSameLighting(Draw first, Draw next) {
        return first == next
                || first != null
                        && next != null
                        && first.features().equals(next.features())
                        && Arrays.equals(first.uniforms(), next.uniforms());
    }
}

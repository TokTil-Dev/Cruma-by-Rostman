package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152ShadowTexturePass.Argument;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Channel;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Combine;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Framebuffer;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Lighting;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Pass;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Stage;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Texture;
import com.rost.cruma.core.editing.ShadowColorBlend.Factor;
import java.util.List;
import java.util.Objects;

final class P152TerrainShadowPass {
    private P152TerrainShadowPass() {}

    static Pass compile(Texture layer, Lighting lighting, boolean firstPass) {
        Objects.requireNonNull(layer);
        Objects.requireNonNull(lighting);
        var framebuffer = new Framebuffer(
                !firstPass,
                firstPass ? Factor.ONE : Factor.SOURCE_ALPHA,
                firstPass ? Factor.ZERO : Factor.INVERSE_SOURCE_ALPHA,
                false,
                Factor.ONE,
                Factor.ZERO,
                true,
                true,
                false,
                !firstPass,
                0,
                false,
                0);
        var texture = new Stage(
                true,
                new Channel(
                        lighting.modulate2x() ? Combine.MODULATE_2X : Combine.MODULATE,
                        Argument.TEXTURE,
                        Argument.DIFFUSE),
                new Channel(Combine.SELECT_ARGUMENT_1, Argument.TEXTURE, Argument.CURRENT));
        var alpha = new Stage(
                true,
                new Channel(Combine.SELECT_ARGUMENT_1, Argument.CURRENT, Argument.CURRENT),
                new Channel(
                        layer.masked() || layer.alphaTexture() ? Combine.MODULATE : Combine.SELECT_ARGUMENT_1,
                        Argument.TEXTURE,
                        Argument.CURRENT));
        return new Pass(framebuffer, 0, List.of(texture, alpha));
    }
}

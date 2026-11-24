package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152ShadowTexturePass.ColorModifier;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Lighting;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Material;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Modifier;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Pass;
import java.util.ArrayList;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

final class P152BspShadowMaterial {
    private P152BspShadowMaterial() {}

    enum RootKind {
        TEXTURE,
        COLOR_MODIFIER,
        FINAL_BLEND,
        SHADER
    }

    record State(Lighting lighting, @Nullable ColorModifier outerColor) {}

    record TextureBinding(Material material, Lighting lighting) {
        Pass compileLegacy() {
            return P152ShadowTexturePass.compile(material, lighting);
        }
    }

    static State computeState(
            boolean beast, boolean sunAffected, RootKind rootKind, boolean textureTwoSided, int bspAmbientArgb) {
        Objects.requireNonNull(rootKind);
        var lighting = new Lighting(beast, false, rootKind != RootKind.COLOR_MODIFIER);
        var color = !beast && sunAffected
                ? new ColorModifier(bspAmbientArgb | 0xff000000, false, rootKind == RootKind.TEXTURE && textureTwoSided)
                : null;
        return new State(lighting, color);
    }

    static TextureBinding bindTexture(Material authored, boolean beast, boolean sunAffected, int bspAmbientArgb) {
        Objects.requireNonNull(authored);
        var kind = authored.modifiers().isEmpty()
                ? RootKind.TEXTURE
                : authored.modifiers().getFirst() instanceof ColorModifier
                        ? RootKind.COLOR_MODIFIER
                        : RootKind.FINAL_BLEND;
        var state = computeState(beast, sunAffected, kind, authored.texture().twoSided(), bspAmbientArgb);
        if (state.outerColor == null) {
            return new TextureBinding(authored, state.lighting);
        }
        var modifiers = new ArrayList<Modifier>(authored.modifiers().size() + 1);
        modifiers.add(state.outerColor);
        modifiers.addAll(authored.modifiers());
        return new TextureBinding(new Material(authored.texture(), modifiers), state.lighting);
    }
}

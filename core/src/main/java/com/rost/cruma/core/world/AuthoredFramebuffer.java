package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.FrameBufferBlending;
import com.rost.cruma.core.unreal.UMaterial.OutputBlending;
import com.rost.cruma.core.unreal.UMaterial.UBitMapMaterial;
import com.rost.cruma.core.unreal.UMaterial.UColorModifier;
import com.rost.cruma.core.unreal.UMaterial.UCombiner;
import com.rost.cruma.core.unreal.UMaterial.UConstantColor;
import com.rost.cruma.core.unreal.UMaterial.UFadeColor;
import com.rost.cruma.core.unreal.UMaterial.UFinalBlend;
import com.rost.cruma.core.unreal.UMaterial.UModifier;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UMaterial.UTexCoordSource;
import com.rost.cruma.core.unreal.UMaterial.UTexOscillator;
import com.rost.cruma.core.unreal.UMaterial.UTexPanner;
import com.rost.cruma.core.unreal.UMaterial.UTexRotator;
import com.rost.cruma.core.unreal.UMaterial.UTexScaler;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UMaterial.UVertexColor;
import com.rost.cruma.core.world.Entity.BlendMode;
import java.util.IdentityHashMap;
import org.jetbrains.annotations.Nullable;

public record AuthoredFramebuffer(
        boolean blending,
        Factors color,
        @Nullable Factors alpha,
        boolean depthTest,
        boolean depthWrite,
        int alphaReference,
        boolean twoSided,
        int fogColor) {
    public enum Factor {
        ZERO,
        ONE,
        SOURCE_COLOR,
        INVERSE_SOURCE_COLOR,
        SOURCE_ALPHA,
        INVERSE_SOURCE_ALPHA,
        DESTINATION_COLOR
    }

    public record Factors(Factor source, Factor destination) {}

    public static @Nullable AuthoredFramebuffer resolve(@Nullable UMaterial material) {
        return resolve(material, null);
    }

    public static @Nullable AuthoredFramebuffer resolveBspWater(@Nullable UMaterial material) {
        return resolve(
                material,
                new AuthoredFramebuffer(
                        true,
                        new Factors(Factor.SOURCE_ALPHA, Factor.INVERSE_SOURCE_ALPHA),
                        null,
                        true,
                        false,
                        0,
                        true,
                        -1));
    }

    public static AuthoredFramebuffer of(UFinalBlend material) {
        var mode = material.getFbBlending();
        var color = switch (mode) {
            case FB_Overwrite, FB_Capture -> new Factors(Factor.ONE, Factor.ZERO);
            case FB_Modulate -> new Factors(Factor.DESTINATION_COLOR, Factor.SOURCE_COLOR);
            case FB_AlphaBlend -> new Factors(Factor.SOURCE_ALPHA, Factor.INVERSE_SOURCE_ALPHA);
            case FB_AlphaModulate_MightNotFogCorrectly -> new Factors(Factor.ONE, Factor.INVERSE_SOURCE_ALPHA);
            case FB_Translucent -> new Factors(Factor.ONE, Factor.INVERSE_SOURCE_COLOR);
            case FB_Darken -> new Factors(Factor.ZERO, Factor.INVERSE_SOURCE_COLOR);
            case FB_Brighten -> new Factors(Factor.SOURCE_ALPHA, Factor.ONE);
            case FB_Invisible, FB_OverwriteAlpha -> new Factors(Factor.ZERO, Factor.ONE);
            case FB_Add -> new Factors(Factor.ONE, Factor.ONE);
            case FB_InWaterBlend -> new Factors(Factor.DESTINATION_COLOR, Factor.ZERO);
            case FB_DarkenInv -> new Factors(Factor.ZERO, Factor.SOURCE_COLOR);
        };
        var alpha = switch (mode) {
            case FB_InWaterBlend -> new Factors(Factor.SOURCE_ALPHA, Factor.ZERO);
            case FB_OverwriteAlpha -> new Factors(Factor.ONE, Factor.ZERO);
            default -> null;
        };
        var fog = switch (mode) {
            case FB_Modulate -> 0x7f7f7f;
            case FB_AlphaModulate_MightNotFogCorrectly,
                    FB_Translucent,
                    FB_Darken,
                    FB_Brighten,
                    FB_Invisible,
                    FB_DarkenInv -> 0;
            default -> -1;
        };
        return new AuthoredFramebuffer(
                mode != FrameBufferBlending.FB_Overwrite,
                color,
                alpha,
                material.isZTest(),
                material.isZWrite(),
                material.isAlphaTest() ? material.getAlphaRef() : -1,
                material.isTwoSided(),
                fog);
    }

    public BlendMode drawOrderBlend() {
        if (color.source() == Factor.ONE && color.destination() == Factor.ZERO && alpha == null) {
            return alphaReference >= 0 ? BlendMode.MASKED : BlendMode.OPAQUE;
        }
        if (color.source() == Factor.SOURCE_ALPHA
                && color.destination() == Factor.INVERSE_SOURCE_ALPHA
                && alphaReference >= 0
                && depthWrite) {
            return BlendMode.MASKED_BLEND;
        }
        return BlendMode.TRANSLUCENT;
    }

    private static AuthoredFramebuffer of(UShader shader) {
        var mode = shader.getOutputBlending();
        var opacity = shader.getOpacity().peekFullName() != null;
        var color = switch (mode) {
            case OB_Normal ->
                opacity
                        ? new Factors(Factor.SOURCE_ALPHA, Factor.INVERSE_SOURCE_ALPHA)
                        : new Factors(Factor.ONE, Factor.ZERO);
            case OB_Masked, OB_MAX -> new Factors(Factor.ONE, Factor.ZERO);
            case OB_Modulate -> new Factors(Factor.DESTINATION_COLOR, Factor.SOURCE_COLOR);
            case OB_Translucent -> new Factors(Factor.ONE, Factor.ONE);
            case OB_Invisible -> new Factors(Factor.ZERO, Factor.ONE);
            case OB_Brighten -> new Factors(Factor.ONE, Factor.INVERSE_SOURCE_COLOR);
            case OB_Darken -> new Factors(Factor.ZERO, Factor.INVERSE_SOURCE_COLOR);
        };
        var depthWrite = switch (mode) {
            case OB_Normal -> !opacity || shader.isZWrite();
            case OB_Masked, OB_MAX -> true;
            default -> false;
        };
        var alphaReference = mode == OutputBlending.OB_Masked
                ? 127
                : mode == OutputBlending.OB_Normal && opacity
                        ? !shader.isZWrite() ? 0 : shader.isAlphaTest() ? shader.getAlphaRef() : -1
                        : -1;
        var fog = switch (mode) {
            case OB_Modulate -> 0x7f7f7f;
            case OB_Translucent, OB_Brighten, OB_Darken -> 0;
            default -> -1;
        };
        return new AuthoredFramebuffer(
                color.source() != Factor.ONE || color.destination() != Factor.ZERO,
                color,
                null,
                true,
                depthWrite,
                alphaReference,
                !shader.isTreatAsTwoSided() && shader.isTwoSided(),
                fog);
    }

    private static @Nullable AuthoredFramebuffer resolve(
            @Nullable UMaterial material, @Nullable AuthoredFramebuffer outer) {
        var visited = new IdentityHashMap<UMaterial, Boolean>();
        var selected = (UFinalBlend) null;
        var hasColor = false;
        var colorAlphaBlend = false;
        var twoSided = outer != null && outer.twoSided();
        while (material instanceof UModifier modifier) {
            if (visited.put(material, Boolean.TRUE) != null) {
                return null;
            }
            if (material instanceof UFinalBlend finalBlend) {
                selected = finalBlend;
                twoSided = finalBlend.isTwoSided();
            } else if (material instanceof UColorModifier color) {
                hasColor = true;
                colorAlphaBlend |= color.isAlphaBlend();
                twoSided |= color.isRenderTwoSided();
            } else if (!(material instanceof UTexOscillator
                    || material instanceof UTexRotator
                    || material instanceof UTexScaler
                    || material instanceof UTexPanner
                    || material instanceof UTexCoordSource)) {
                return null;
            }
            material = modifier.getMaterial().get();
        }
        var state = selected == null ? outer : of(selected);
        if (state == null && material instanceof UShader shader) {
            state = of(shader);
        }
        if (!hasColor) {
            return state;
        }
        if (!(material instanceof UBitMapMaterial
                || material instanceof UCombiner
                || material instanceof UConstantColor
                || material instanceof UFadeColor
                || material instanceof UVertexColor
                || material instanceof UShader)) {
            return null;
        }
        if (state == null) {
            var texture = material instanceof UTexture t ? t : null;
            var masked = texture != null && texture.isMasked();
            var translucent = texture != null && texture.isAlphaTexture() && !masked;
            state = new AuthoredFramebuffer(
                    translucent,
                    translucent
                            ? new Factors(Factor.SOURCE_ALPHA, Factor.INVERSE_SOURCE_ALPHA)
                            : new Factors(Factor.ONE, Factor.ZERO),
                    null,
                    true,
                    true,
                    masked ? 127 : translucent ? 0 : -1,
                    twoSided,
                    -1);
        }
        var factors = state.color();
        if (colorAlphaBlend && factors.source() == Factor.ONE && factors.destination() == Factor.ZERO) {
            factors = new Factors(Factor.SOURCE_ALPHA, Factor.INVERSE_SOURCE_ALPHA);
        }
        return new AuthoredFramebuffer(
                state.blending() || colorAlphaBlend,
                factors,
                state.alpha(),
                state.depthTest(),
                state.depthWrite(),
                state.alphaReference(),
                twoSided,
                state.fogColor());
    }
}

package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.EColorOperation;
import com.rost.cruma.core.unreal.UMaterial.UCombiner;
import com.rost.cruma.core.unreal.UMaterial.UConstantColor;
import com.rost.cruma.core.unreal.UMaterial.UFadeColor;
import com.rost.cruma.core.unreal.UMaterial.UModifier;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import org.jetbrains.annotations.Nullable;

public final class MeshParticleMaterials {
    private MeshParticleMaterials() {}

    public static boolean drawsConstantAsParticleColor(@Nullable UMaterial node) {
        try {
            var diffuse = node;
            if (node instanceof UShader shader) {
                if (shader.getSpecular().get() != null
                        || shader.getSelfIllumination().get() != null
                        || shader.getDetail().get() != null) {
                    return false;
                }
                diffuse = shader.getDiffuse().get();
            }
            return diffuse instanceof UCombiner combiner
                    && combiner.getCombineOperation() == EColorOperation.CO_Multiply
                    && isConstant(combiner.getMaterial1().get())
                            != isConstant(combiner.getMaterial2().get());
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    public static boolean takesNoLight(@Nullable UMaterial node) {
        try {
            var inner = node;
            for (var depth = 0; depth < 8 && inner instanceof UModifier modifier; depth++) {
                inner = modifier.getMaterial().get();
            }
            return inner instanceof UShader shader
                    && shader.getSelfIllumination().get() != null
                    && shader.getSelfIlluminationMask().get() == null;
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    public static boolean drawsInSeveralPasses(@Nullable UMaterial node) {
        try {
            var inner = node;
            for (var depth = 0; depth < 8 && inner instanceof UModifier modifier; depth++) {
                inner = modifier.getMaterial().get();
            }
            return inner instanceof UShader shader
                    && shader.getOpacity().get() != null
                    && (shader.getSpecular().get() != null
                            || shader.getSelfIllumination().get() != null
                                    && shader.getSelfIlluminationMask().get() != null);
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    public static Entity.BlendMode blendUnderColorModifier(Entity.BlendMode blend) {
        return switch (blend) {
            case OPAQUE -> Entity.BlendMode.TRANSLUCENT;
            case MASKED -> Entity.BlendMode.MASKED_BLEND;
            default -> blend;
        };
    }

    static boolean isConstant(@Nullable UMaterial node) {
        return node instanceof UFadeColor || node instanceof UConstantColor;
    }
}

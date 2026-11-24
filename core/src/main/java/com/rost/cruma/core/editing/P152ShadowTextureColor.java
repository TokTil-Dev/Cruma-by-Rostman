package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152ShadowTexturePass.Argument;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Combine;
import com.rost.cruma.core.editing.P152ShadowTexturePass.Pass;
import java.util.Objects;

final class P152ShadowTextureColor {
    private P152ShadowTextureColor() {}

    static int evaluateOpaque(Pass pass, int textureArgb, int diffuseArgb) {
        return evaluateOpaque(pass, textureArgb, textureArgb, diffuseArgb);
    }

    static int evaluateOpaque(Pass pass, int firstTextureArgb, int secondTextureArgb, int diffuseArgb) {
        Objects.requireNonNull(pass);
        if (pass.framebuffer().blending() || pass.framebuffer().alphaTest()) {
            throw new IllegalArgumentException("Shadow texture target requires an opaque pass without alpha testing");
        }
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            result |=
                    quantize(computeComponent(pass, firstTextureArgb, secondTextureArgb, diffuseArgb, shift)) << shift;
        }
        return result;
    }

    static float computeComponent(Pass pass, int textureArgb, int diffuseArgb, int shift) {
        return computeComponent(pass, textureArgb, textureArgb, diffuseArgb, shift);
    }

    static float computeComponent(Pass pass, int firstTextureArgb, int secondTextureArgb, int diffuseArgb, int shift) {
        Objects.requireNonNull(pass);
        if (shift < 0 || shift > 24 || shift % 8 != 0) {
            throw new IllegalArgumentException("Invalid shadow color component");
        }
        float diffuse = channel(diffuseArgb, shift);
        float factor = channel(pass.factorArgb(), shift), current = diffuse;
        for (int index = 0; index < pass.stages().size(); index++) {
            float texture = channel(index == 0 ? firstTextureArgb : secondTextureArgb, shift);
            var stage = pass.stages().get(index);
            var operation = shift == 24 ? stage.alpha() : stage.rgb();
            float first = selectArgument(operation.first(), stage.texture(), texture, diffuse, factor, current);
            if (operation.combine() == Combine.SELECT_ARGUMENT_1) {
                current = first;
            } else {
                float second = selectArgument(operation.second(), stage.texture(), texture, diffuse, factor, current);
                current = first * second;
                if (operation.combine() == Combine.MODULATE_2X) {
                    current *= 2;
                }
            }
            current = Math.min(current, 1);
        }
        return current;
    }

    static int quantize(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite shadow fragment component");
        }
        if (value <= 0) {
            return 0;
        }
        if (value >= 1) {
            return 255;
        }
        int fixed = (int) (value * 4096);
        return (fixed * 255 + 2047) >>> 12;
    }

    private static float channel(int argb, int shift) {
        return (argb >>> shift & 255) / 255f;
    }

    private static float selectArgument(
            Argument argument, boolean bound, float texture, float diffuse, float factor, float current) {
        return switch (argument) {
            case DIFFUSE -> diffuse;
            case CURRENT -> current;
            case TEXTURE_FACTOR -> factor;
            case TEXTURE -> {
                if (!bound) {
                    throw new IllegalArgumentException("Shadow stage reads an unbound texture");
                }
                yield texture;
            }
        };
    }
}

package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ShadowColorBlend.Factor;
import com.rost.cruma.core.editing.ShadowColorBlend.Operation;
import com.rost.cruma.core.editing.ShadowColorBlend.State;
import com.rost.cruma.core.editing.ShadowMaskRasterizer.Cull;
import com.rost.cruma.core.editing.ShadowMaskRasterizer.DepthFunction;
import com.rost.cruma.core.editing.ShadowMaskRasterizer.OpaqueState;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

final class P152ShadowTexturePass {
    private P152ShadowTexturePass() {}

    record Texture(boolean masked, boolean alphaTexture, boolean twoSided) {}

    record Lighting(boolean dynamicLight, boolean vertexColor, boolean modulate2x) {}

    record ShaderLighting(boolean dynamicLight, boolean vertexColor, boolean modulate2x) {}

    sealed interface Modifier permits FinalBlend, ColorModifier {}

    record FinalBlend(
            int mode, boolean depthWrite, boolean depthTest, boolean twoSided, boolean alphaTest, int alphaReference)
            implements Modifier {
        FinalBlend {
            if (mode < 0 || mode > 12) {
                throw new IllegalArgumentException("Unsupported shadow framebuffer mode: " + mode);
            }
            checkAlpha(alphaReference);
        }
    }

    record ColorModifier(int argb, boolean alphaBlend, boolean twoSided) implements Modifier {}

    record Material(Texture texture, List<Modifier> modifiers) {
        Material {
            Objects.requireNonNull(texture);
            modifiers = List.copyOf(modifiers);
        }
    }

    record ShaderFeatures(boolean vertexColor, boolean modulate2x, boolean colorModifier, boolean dynamicLight) {}

    record ProgrammableState(
            Framebuffer framebuffer, int materialFactorArgb, int modifierFactorArgb, ShaderFeatures features) {
        ProgrammableState {
            Objects.requireNonNull(framebuffer);
            Objects.requireNonNull(features);
        }

        Bound bind(int fragmentArgb, Inherited inherited) {
            return bindFramebuffer(framebuffer, fragmentArgb, inherited);
        }
    }

    enum Argument {
        DIFFUSE,
        CURRENT,
        TEXTURE,
        TEXTURE_FACTOR
    }

    enum Combine {
        SELECT_ARGUMENT_1,
        MODULATE,
        MODULATE_2X
    }

    record Channel(Combine combine, Argument first, Argument second) {
        Channel {
            Objects.requireNonNull(combine);
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
        }
    }

    record Stage(boolean texture, Channel rgb, Channel alpha) {
        Stage {
            Objects.requireNonNull(rgb);
            Objects.requireNonNull(alpha);
        }
    }

    record Framebuffer(
            boolean blending,
            Factor source,
            Factor destination,
            boolean separateAlpha,
            Factor alphaSource,
            Factor alphaDestination,
            boolean depthTest,
            boolean depthWrite,
            boolean twoSided,
            boolean alphaTest,
            int alphaReference,
            boolean fogOverride,
            int fogColor) {
        Framebuffer {
            Objects.requireNonNull(source);
            Objects.requireNonNull(destination);
            Objects.requireNonNull(alphaSource);
            Objects.requireNonNull(alphaDestination);
            checkAlpha(alphaReference);
        }
    }

    record Inherited(
            boolean depthEnabled, Cull cull, Operation blendOperation, Operation alphaOperation, int writeMask) {
        Inherited {
            Objects.requireNonNull(cull);
            Objects.requireNonNull(blendOperation);
            Objects.requireNonNull(alphaOperation);
            if (writeMask < 0 || writeMask > 15) {
                throw new IllegalArgumentException("Invalid shadow color write mask");
            }
        }
    }

    record Bound(OpaqueState color, State blend) {}

    record Pass(Framebuffer framebuffer, int factorArgb, List<Stage> stages) {
        Pass {
            Objects.requireNonNull(framebuffer);
            stages = List.copyOf(stages);
            if (stages.isEmpty() || stages.size() > 2) {
                throw new IllegalArgumentException("Unsupported shadow texture stage count");
            }
        }

        Bound bind(int fragmentArgb, Inherited inherited) {
            return bindFramebuffer(framebuffer, fragmentArgb, inherited);
        }
    }

    static Pass compile(Material material, Lighting lighting) {
        Objects.requireNonNull(material);
        var resolved = resolve(material.modifiers);
        return compile(material.texture, lighting, resolved.finalBlend, resolved.color);
    }

    static ProgrammableState compileProgrammableState(Material material, ShaderLighting lighting) {
        Objects.requireNonNull(material);
        Objects.requireNonNull(lighting);
        var resolved = resolve(material.modifiers);
        var color = resolved.color;
        return new ProgrammableState(
                buildFramebuffer(material.texture, resolved.finalBlend, color),
                0,
                color == null ? 0 : color.argb,
                new ShaderFeatures(lighting.vertexColor, lighting.modulate2x, color != null, lighting.dynamicLight));
    }

    static Pass compile(
            Texture texture, Lighting lighting, @Nullable FinalBlend finalBlend, @Nullable ColorModifier modifier) {
        Objects.requireNonNull(texture);
        Objects.requireNonNull(lighting);
        var f = buildFramebuffer(texture, finalBlend, modifier);
        boolean lit = lighting.vertexColor || lighting.dynamicLight;
        var rgb = new Channel(
                lit ? lighting.modulate2x ? Combine.MODULATE_2X : Combine.MODULATE : Combine.SELECT_ARGUMENT_1,
                Argument.TEXTURE,
                lit ? Argument.DIFFUSE : Argument.CURRENT);
        var alpha = new Channel(
                lit ? Combine.MODULATE : Combine.SELECT_ARGUMENT_1,
                Argument.TEXTURE,
                lit ? Argument.DIFFUSE : Argument.CURRENT);
        var bitmap = new Stage(true, rgb, alpha);
        if (modifier == null) {
            return new Pass(f, 0, List.of(bitmap));
        }
        var multiply = new Stage(
                false,
                new Channel(
                        lighting.modulate2x ? Combine.MODULATE_2X : Combine.MODULATE,
                        Argument.CURRENT,
                        Argument.TEXTURE_FACTOR),
                new Channel(Combine.MODULATE, Argument.CURRENT, Argument.TEXTURE_FACTOR));
        return new Pass(f, modifier.argb, List.of(bitmap, multiply));
    }

    private record Resolved(
            @Nullable FinalBlend finalBlend, @Nullable ColorModifier color) {}

    private static Resolved resolve(List<Modifier> modifiers) {
        FinalBlend lastBlend = null;
        boolean colored = false, alphaBlend = false, twoSided = false;
        int argb = 0;
        for (var modifier : modifiers) {
            switch (modifier) {
                case FinalBlend blend -> {
                    lastBlend = blend;
                    twoSided = blend.twoSided;
                }
                case ColorModifier color -> {
                    colored = true;
                    argb = color.argb;
                    alphaBlend |= color.alphaBlend;
                    twoSided |= color.twoSided;
                }
            }
        }
        if (lastBlend != null) {
            lastBlend = new FinalBlend(
                    lastBlend.mode,
                    lastBlend.depthWrite,
                    lastBlend.depthTest,
                    twoSided,
                    lastBlend.alphaTest,
                    lastBlend.alphaReference);
        }
        return new Resolved(lastBlend, colored ? new ColorModifier(argb, alphaBlend, twoSided) : null);
    }

    private static Framebuffer buildFramebuffer(
            Texture texture, @Nullable FinalBlend finalBlend, @Nullable ColorModifier modifier) {
        boolean colored = modifier != null;
        boolean alphaBlend = colored && modifier.alphaBlend;
        boolean twoSided = (finalBlend != null ? finalBlend.twoSided : colored ? false : texture.twoSided)
                || colored && modifier.twoSided;
        int mode = 0, alphaReference = 0;
        boolean alphaTest = false, depthTest = true, depthWrite = true;
        if (finalBlend != null) {
            mode = finalBlend.mode;
            alphaTest = finalBlend.alphaTest;
            alphaReference = finalBlend.alphaReference;
            depthTest = finalBlend.depthTest;
            depthWrite = finalBlend.depthWrite;
        } else if (texture.masked) {
            mode = alphaBlend ? 2 : 0;
            alphaTest = true;
            alphaReference = 127;
        } else if (texture.alphaTexture) {
            mode = 2;
            alphaTest = true;
        }

        var source = switch (mode) {
            case 1, 9 -> Factor.DESTINATION_COLOR;
            case 2, 6 -> Factor.SOURCE_ALPHA;
            case 5, 7, 11, 12 -> Factor.ZERO;
            default -> Factor.ONE;
        };
        var destination = switch (mode) {
            case 1, 12 -> Factor.SOURCE_COLOR;
            case 2, 3 -> Factor.INVERSE_SOURCE_ALPHA;
            case 4, 5 -> Factor.INVERSE_SOURCE_COLOR;
            case 6, 7, 8, 11 -> Factor.ONE;
            default -> Factor.ZERO;
        };
        if (alphaBlend && source == Factor.ONE && destination == Factor.ZERO) {
            source = Factor.SOURCE_ALPHA;
            destination = Factor.INVERSE_SOURCE_ALPHA;
        }
        return new Framebuffer(
                mode != 0 || alphaBlend,
                source,
                destination,
                mode == 9 || mode == 11,
                mode == 9 ? Factor.SOURCE_ALPHA : Factor.ONE,
                Factor.ZERO,
                depthTest,
                depthWrite,
                twoSided,
                alphaTest,
                alphaReference,
                mode == 1 || mode >= 3 && mode <= 7 || mode == 12,
                mode == 1 ? 0x7f7f7f : 0);
    }

    private static Bound bindFramebuffer(Framebuffer f, int fragmentArgb, Inherited inherited) {
        Objects.requireNonNull(inherited);
        var color = new OpaqueState(
                f.twoSided ? Cull.NONE : inherited.cull,
                fragmentArgb,
                inherited.depthEnabled,
                f.depthWrite,
                f.alphaTest ? f.alphaReference : -1,
                f.depthTest ? DepthFunction.LESS_EQUAL : DepthFunction.ALWAYS);
        var blend = new State(
                f.blending,
                f.source,
                f.destination,
                inherited.blendOperation,
                f.separateAlpha,
                f.alphaSource,
                f.alphaDestination,
                inherited.alphaOperation,
                -1,
                inherited.writeMask);
        return new Bound(color, blend);
    }

    private static void checkAlpha(int alphaReference) {
        if (alphaReference < 0 || alphaReference > 255) {
            throw new IllegalArgumentException("Invalid shadow material alpha reference");
        }
    }
}

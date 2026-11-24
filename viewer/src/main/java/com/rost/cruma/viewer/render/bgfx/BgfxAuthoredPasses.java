package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;

import com.rost.cruma.core.world.AuthoredFramebuffer;
import com.rost.cruma.core.world.AuthoredMaterialPassProgram;
import com.rost.cruma.core.world.AuthoredMaterialRuntime;
import com.rost.cruma.core.world.AuthoredMaterialRuntime.Binding;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.NativeStaticMeshLightingFeatures;
import com.rost.cruma.core.world.TextureSequence;
import com.rost.cruma.core.world.TextureSequence.Clock;
import com.rost.cruma.core.world.TextureSequence.Clocks;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

final class BgfxAuthoredPasses {
    static final int MAX_PASSES = 8;
    static final int MAX_INSTRUCTIONS = 64;
    static final int SAMPLE_VECTOR = 3;
    static final int PASS_VECTOR = SAMPLE_VECTOR + 8 * 2;
    static final int INSTRUCTION_VECTOR = PASS_VECTOR + MAX_PASSES;
    static final int VECTORS = INSTRUCTION_VECTOR + MAX_INSTRUCTIONS * 2;
    static final int SOURCE_LANE = (SAMPLE_VECTOR + 11) * 4 + 3;
    static final int SPECULAR_MAP_BIT = 1 << 16;

    private static final int SHADER_MAP_SAMPLER =
            BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT | BGFX_SAMPLER_MIP_POINT;
    private final AuthoredMaterialPassProgram program;
    private final AuthoredMaterialRuntime runtime;
    private final List<Bitmap> bitmaps = new ArrayList<>();
    private final TextureRef[] active = new TextureRef[8];
    private final Bitmap[] shaderMaps = new Bitmap[2];
    private final TextureRef[] activeShaderMaps = new TextureRef[2];
    private final float[] shaderMapUniforms = new float[4];
    private boolean texturesEnabled;
    private final float[] uniforms = new float[VECTORS * 4];
    private Binding binding;

    BgfxAuthoredPasses(
            AuthoredMaterialPassProgram program,
            BiFunction<Texture, String, TextureRef> load,
            Clocks clocks,
            AuthoredMaterialRuntime runtime) {
        this.program = program;
        this.runtime = runtime;
        var passes = program.passes();
        var instructions =
                passes.passes().stream().mapToInt(p -> p.instructions().size()).sum();
        if (!program.hasCompleteTextureBindings()
                || passes.passes().size() > MAX_PASSES
                || instructions > MAX_INSTRUCTIONS) {
            throw new IllegalArgumentException("material exceeds native pass bindings");
        }
        for (var draw = 0; draw < passes.drawCount(); draw++) {
            if (!passes.isDrawDefined(draw)) {
                throw new IllegalArgumentException("material has an undefined native pass result");
            }
        }
        uniforms[0] = passes.passes().size();
        uniforms[2] = passes.separateDraws() ? 1 : 0;
        uniforms[3] = passes.samples().size();
        putColor(2, program.colorModifierFactor());
        uniforms[(SAMPLE_VECTOR + 1) * 4 + 3] = program.colorModifier() ? 1 : 0;
        uniforms[(SAMPLE_VECTOR + 3) * 4 + 3] = program.specularPower();
        uniforms[(SAMPLE_VECTOR + 5) * 4 + 3] = program.specularScale();
        var sources = 0;
        var bound = program.boundSamples();
        for (var index = 0; index < bound.size(); index++) {
            sources |= switch (bound.get(index).coordinates().source()) {
                        case MESH_UV -> 0;
                        case WORLD_REFLECTION -> 1;
                        case CAMERA_REFLECTION -> 2;
                        case SPHERE_MAP -> 3;
                    }
                    << (index * 2);
        }
        uniforms[SOURCE_LANE] = sources | (program.shaderMaps().specular() != null ? SPECULAR_MAP_BIT : 0);
        var instructionIndex = 0;
        for (var passIndex = 0; passIndex < passes.passes().size(); passIndex++) {
            var pass = passes.passes().get(passIndex);
            var at = (PASS_VECTOR + passIndex) * 4;
            uniforms[at] = instructionIndex;
            uniforms[at + 1] = instructionIndex + pass.instructions().size();
            uniforms[at + 2] = pass.sourceBlend();
            uniforms[at + 3] = pass.destinationBlend();
            for (var instruction : pass.instructions()) {
                var op = instruction.operation();
                if (op.result() != 1) {
                    throw new IllegalArgumentException("unsupported material result register");
                }
                var colorSupported = switch (op.color()) {
                    case 2, 3, 4, 5, 6, 7, 10, 12, 13, 14, 16, 18, 25, 26 -> true;
                    default -> false;
                };
                var alphaSupported = op.alpha() == 2 || op.alpha() == 3 || op.alpha() == 4 || op.alpha() == 7;
                if (!colorSupported || !alphaSupported) {
                    throw new IllegalArgumentException("unsupported material operation");
                }
                if (op.color() == 25 || op.color() == 26) {
                    requireArgument(op.color0());
                }
                if (op.color() != 3) {
                    requireArgument(op.color1());
                }
                if (op.color() != 2) {
                    requireArgument(op.color2());
                }
                if (op.alpha() != 3) {
                    requireArgument(op.alpha1());
                }
                if (op.alpha() != 2) {
                    requireArgument(op.alpha2());
                }
                at = (INSTRUCTION_VECTOR + instructionIndex++ * 2) * 4;
                uniforms[at] = op.color();
                uniforms[at + 1] = op.alpha();
                uniforms[at + 2] = op.color1();
                uniforms[at + 3] = op.color2();
                uniforms[at + 4] = op.alpha1();
                uniforms[at + 5] = op.alpha2();
                uniforms[at + 6] = instruction.sample();
                uniforms[at + 7] = (op.factorGate() == 0 ? 0 : 1)
                        | (op.captureAlpha() ? 2 : 0)
                        | (op.captureFactorAlpha() ? 4 : 0)
                        | (op.color0() & 63) << 3;
            }
        }
        var shared = new IdentityHashMap<TextureSequence, Bitmap>();
        for (var sample : program.boundSamples()) {
            bitmaps.add(loadBitmap(sample.bitmap(), shared, load, clocks));
        }
        shaderMaps[0] = loadBitmap(program.shaderMaps().normal(), shared, load, clocks);
        shaderMaps[1] = loadBitmap(program.shaderMaps().specular(), shared, load, clocks);
    }

    static boolean supports(AuthoredMaterialPassProgram program) {
        var passes = program.passes();
        if (!program.hasCompleteTextureBindings()
                || passes.passes().size() > MAX_PASSES
                || passes.samples().size() > 8
                || passes.passes().stream()
                                .mapToInt(p -> p.instructions().size())
                                .sum()
                        > MAX_INSTRUCTIONS) {
            return false;
        }
        for (var draw = 0; draw < passes.drawCount(); draw++) {
            if (!passes.isDrawDefined(draw)) {
                return false;
            }
        }
        return true;
    }

    static int uploadVectors(float[] values) {
        if (values == null || values.length < VECTORS * 4) {
            throw new IllegalArgumentException("authored pass block must contain its declared vectors");
        }
        var passCount = (int) values[0];
        if (passCount < 1 || passCount > MAX_PASSES || values[0] != passCount) {
            throw new IllegalArgumentException("invalid authored pass count");
        }
        var lastEnd = values[(PASS_VECTOR + passCount - 1) * 4 + 1];
        var instructions = (int) lastEnd;
        if (instructions < 0 || instructions > MAX_INSTRUCTIONS || lastEnd != instructions) {
            throw new IllegalArgumentException("invalid authored instruction endpoint");
        }
        return INSTRUCTION_VECTOR + instructions * 2;
    }

    long getState(long materialState, int draw) {
        if (draw == 0) {
            return materialState;
        }
        var pass = program.passes().passes().get(draw);
        return BgfxSceneState.authoredPass(materialState, pass.sourceBlend(), pass.destinationBlend());
    }

    AuthoredFramebuffer getFramebuffer(int draw) {
        Objects.checkIndex(draw, getDrawCount());
        return program.passes().passes().get(draw).framebuffer();
    }

    boolean hasColorModifier() {
        return program.colorModifier();
    }

    int getDrawCount() {
        return program.passes().drawCount();
    }

    void prepare(float seconds, double bitmapSeconds, boolean texturesEnabled, TextureRef disabled) {
        this.texturesEnabled = texturesEnabled;
        binding = runtime.bind(program, seconds);
        for (var index = 0; index < shaderMaps.length; index++) {
            var bitmap = shaderMaps[index];
            var frame = bitmap == null ? 0 : bitmap.clock().frameAt(bitmapSeconds);
            activeShaderMaps[index] =
                    texturesEnabled && bitmap != null ? bitmap.frames().get(frame) : disabled;
        }
        for (var index = 0; index < 8; index++) {
            if (index >= bitmaps.size()) {
                active[index] = disabled;
                continue;
            }
            var bitmap = bitmaps.get(index);
            var frame = bitmap.clock().frameAt(bitmapSeconds);
            active[index] = texturesEnabled ? bitmap.frames().get(frame) : disabled;
            var matrix = binding.matrices().get(index);
            var at = (SAMPLE_VECTOR + index * 2) * 4;
            uniforms[at] = matrix.m00();
            uniforms[at + 1] = matrix.m10();
            uniforms[at + 2] = matrix.m20();
            uniforms[at + 3] = active[index].getBaseLayer();
            uniforms[at + 4] = matrix.m01();
            uniforms[at + 5] = matrix.m11();
            uniforms[at + 6] = matrix.m21();
        }
    }

    float[] draw(int index) {
        return draw(index, null);
    }

    float[] draw(int index, NativeStaticMeshLightingFeatures lighting) {
        Objects.checkIndex(index, getDrawCount());
        if (binding == null) {
            throw new IllegalStateException("material has not been prepared");
        }
        uniforms[1] = index;
        putColor(1, binding.textureFactors().get(index));
        uniforms[10 * 4 + 3] = lighting == null ? 0 : lighting.materialFactor();
        uniforms[12 * 4 + 3] = lighting == null ? 0 : lighting.colorModifierFactor();
        return uniforms;
    }

    void bindTextures(BgfxSceneUniforms scene, boolean texturesEnabled) {
        for (var index = 0; index < 8; index++) {
            var sampler = index == 0 ? scene.getSceneSampler(0) : scene.getAuthoredSampler(index);
            scene.bindTexture(
                    BgfxAuthoredMaterial.SLOTS[index],
                    sampler.getHandle(),
                    getSampleTexture(index).getTexture().getHandle(),
                    getSampleFlags(index, texturesEnabled));
        }
        for (var index = 0; index < shaderMaps.length; index++) {
            var texture = activeShaderMaps[index];
            scene.bindTexture(
                    14 + index,
                    scene.getNativeShaderMapSampler(index).getHandle(),
                    texture.getTexture().getHandle(),
                    SHADER_MAP_SAMPLER);
        }
    }

    TextureRef getSampleTexture(int index) {
        return active[index];
    }

    int getSampleFlags(int index, boolean texturesEnabled) {
        return texturesEnabled && index < bitmaps.size()
                ? bitmaps.get(index).frames().getFirst().getSamplerFlags()
                : active[index].getSamplerFlags();
    }

    float[] getShaderMaps(boolean nativeLighting, boolean useTangent) {
        if (binding == null) {
            throw new IllegalStateException("material has not been prepared");
        }
        for (var index = 0; index < shaderMaps.length; index++) {
            shaderMapUniforms[index] = activeShaderMaps[index].getBaseLayer();
            shaderMapUniforms[index + 2] =
                    texturesEnabled && nativeLighting && shaderMaps[index] != null && (index != 0 || useTangent)
                            ? 1
                            : 0;
        }
        return shaderMapUniforms;
    }

    private void putColor(int vector, int color) {
        var at = vector * 4;
        uniforms[at] = (color >>> 16 & 255) / 255f;
        uniforms[at + 1] = (color >>> 8 & 255) / 255f;
        uniforms[at + 2] = (color & 255) / 255f;
        uniforms[at + 3] = (color >>> 24) / 255f;
    }

    private static Bitmap loadBitmap(
            TextureSequence sequence,
            IdentityHashMap<TextureSequence, Bitmap> shared,
            BiFunction<Texture, String, TextureRef> load,
            Clocks clocks) {
        if (sequence == null) {
            return null;
        }
        return shared.computeIfAbsent(sequence, value -> {
            var frames = new ArrayList<TextureRef>();
            for (var frame : value.frames()) {
                frames.add(load.apply(frame, "authored-pass/" + shared.size() + '/' + frames.size()));
            }
            return new Bitmap(frames, clocks.getClockFor(value, 0));
        });
    }

    private static void requireArgument(int argument) {
        var source = argument & 15;
        if (source != 0 && source != 1 && source != 2 && source != 3 && source != 5) {
            throw new IllegalArgumentException("unsupported material argument");
        }
    }

    private record Bitmap(List<TextureRef> frames, Clock clock) {}
}

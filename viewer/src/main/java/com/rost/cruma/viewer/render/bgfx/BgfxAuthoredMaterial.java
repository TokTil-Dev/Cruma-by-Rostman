package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.AuthoredMaterialProgram;
import com.rost.cruma.core.world.AuthoredMaterialRuntime;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.TextureSequence;
import com.rost.cruma.core.world.TextureSequence.Clock;
import com.rost.cruma.core.world.TextureSequence.Clocks;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

final class BgfxAuthoredMaterial {
    static final int TEXTURE_FACTOR_VECTOR = 1 + AuthoredMaterialProgram.MAX_STAGES * 4;
    static final int COLOR_MODIFIER_VECTOR = TEXTURE_FACTOR_VECTOR + 1;
    static final int VECTORS = COLOR_MODIFIER_VECTOR + 1;
    static final int[] SLOTS = {0, 1, 2, 4, 5, 7, 12, 13};
    private final AuthoredMaterialProgram program;
    private final List<Bitmap> bitmaps = new ArrayList<>();
    private final float[] uniforms = new float[VECTORS * 4];
    private final TextureRef[] active = new TextureRef[AuthoredMaterialProgram.MAX_STAGES];
    private final AuthoredMaterialRuntime coordinates;

    BgfxAuthoredMaterial(AuthoredMaterialProgram program, BiFunction<Texture, String, TextureRef> load) {
        this(program, load, new Clocks());
    }

    BgfxAuthoredMaterial(AuthoredMaterialProgram program, BiFunction<Texture, String, TextureRef> load, Clocks clocks) {
        this(program, load, clocks, new AuthoredMaterialRuntime());
    }

    BgfxAuthoredMaterial(
            AuthoredMaterialProgram program,
            BiFunction<Texture, String, TextureRef> load,
            Clocks clocks,
            AuthoredMaterialRuntime coordinates) {
        this.program = program;
        this.coordinates = Objects.requireNonNull(coordinates);
        uniforms[2] = program.colorModifier() ? 1f : 0f;
        uniforms[1] = program.stages().stream()
                        .anyMatch(stage -> (stage.colorArgument1() & 15) == 0
                                || (stage.colorArgument2() & 15) == 0
                                || (stage.alphaArgument1() & 15) == 0
                                || (stage.alphaArgument2() & 15) == 0)
                ? 1f
                : 0f;
        var shared = new IdentityHashMap<TextureSequence, Bitmap>();
        for (var stage : program.stages()) {
            if (stage.bitmap() == null) {
                bitmaps.add(null);
                continue;
            }
            var bitmap = shared.computeIfAbsent(stage.bitmap(), sequence -> {
                var frames = new ArrayList<TextureRef>();
                for (var frame : sequence.frames()) {
                    frames.add(load.apply(frame, "authored/" + shared.size() + '/' + frames.size()));
                }
                return new Bitmap(frames, clocks.getClockFor(sequence, 0.0));
            });
            bitmaps.add(bitmap);
        }
    }

    float[] prepare(float time, boolean texturesEnabled, TextureRef disabled) {
        return prepare(time, time, texturesEnabled, disabled);
    }

    float[] prepare(float time, double bitmapTime, boolean texturesEnabled, TextureRef disabled) {
        coordinates.begin(program, time);
        uniforms[0] = program.stages().size();
        putColor(TEXTURE_FACTOR_VECTOR, program.textureFactor(time));
        putColor(COLOR_MODIFIER_VECTOR, program.colorModifierFactor());
        for (var index = 0; index < program.stages().size(); index++) {
            var stage = program.stages().get(index);
            var bitmap = bitmaps.get(index);
            if (bitmap == null) {
                active[index] = disabled;
            } else {
                var frame = bitmap.clock().frameAt(bitmapTime);
                active[index] = texturesEnabled ? bitmap.frames().get(frame) : disabled;
            }
            var matrix = coordinates.getMatrix(stage.coordinates(), time);
            var at = (1 + index * 4) * 4;
            uniforms[at] = stage.colorOperation();
            uniforms[at + 1] = stage.colorArgument1();
            uniforms[at + 2] = stage.colorArgument2();
            uniforms[at + 3] = stage.alphaOperation();
            uniforms[at + 4] = stage.alphaArgument1();
            uniforms[at + 5] = stage.alphaArgument2();
            uniforms[at + 6] = active[index].getBaseLayer();
            uniforms[at + 8] = matrix.m00();
            uniforms[at + 9] = matrix.m10();
            uniforms[at + 10] = matrix.m20();
            uniforms[at + 12] = matrix.m01();
            uniforms[at + 13] = matrix.m11();
            uniforms[at + 14] = matrix.m21();
        }
        return uniforms;
    }

    void bind(BgfxSceneUniforms scene, float time, boolean texturesEnabled, BgfxTextureCache cache) {
        bind(scene, time, cache.getBitmapSeconds(), texturesEnabled, cache);
    }

    void bind(BgfxSceneUniforms scene, float time, double bitmapTime, boolean texturesEnabled, BgfxTextureCache cache) {
        scene.bindAuthoredMaterial(prepare(time, bitmapTime, texturesEnabled, cache.getPagedBlack()));
        for (var index = 0; index < SLOTS.length; index++) {
            var texture = index < program.stages().size() ? active[index] : cache.getPagedBlack();
            var sampler = index == 0 ? scene.getSceneSampler(0) : scene.getAuthoredSampler(index);
            var flags = texturesEnabled && index < bitmaps.size() && bitmaps.get(index) != null
                    ? bitmaps.get(index).frames().getFirst().getSamplerFlags()
                    : texture.getSamplerFlags();
            scene.bindTexture(
                    SLOTS[index], sampler.getHandle(), texture.getTexture().getHandle(), flags);
        }
    }

    private record Bitmap(List<TextureRef> frames, Clock clock) {}

    private void putColor(int vector, int color) {
        var at = vector * 4;
        uniforms[at] = (color >>> 16 & 255) / 255f;
        uniforms[at + 1] = (color >>> 8 & 255) / 255f;
        uniforms[at + 2] = (color & 255) / 255f;
        uniforms[at + 3] = (color >>> 24) / 255f;
    }
}

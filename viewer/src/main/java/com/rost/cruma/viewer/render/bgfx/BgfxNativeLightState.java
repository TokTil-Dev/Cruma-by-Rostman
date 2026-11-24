package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.unreal.primitives.USphere;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

final class BgfxNativeLightState {
    static final int SLOTS = 8;
    static final int PROGRAMMABLE_SLOTS = 4;
    static final int RECORD_WORDS = 26;

    private final int[] records = new int[SLOTS * RECORD_WORDS];
    private final int[] programmableRecords = new int[PROGRAMMABLE_SLOTS * RECORD_WORDS];
    private final boolean[] active = new boolean[SLOTS];
    private final boolean[] fixedActive = new boolean[SLOTS];
    private final Constants vertex = new Constants();
    private final Constants pixel = new Constants();
    private final ArrayList<Saved> saved = new ArrayList<>();
    private int depth;
    private final Vec3 sunDirection = new Vec3();

    record Input(int effect, Vec3 color, Vec3 position, Vec3 direction, float radius) {}

    static final class Constants {
        final float[] flags = new float[4];
        final float[] sun = new float[4];
        final float[] pointTypes = new float[16];
        final float[] positions = new float[16];
        final float[] colors = new float[16];
        final float[] attenuation = new float[16];
    }

    static float kernel(float distance, float radius) {
        if (!(radius >= distance)) {
            return 0f;
        }
        var t = distance / radius;
        var polynomial = ((2f * t) * t) * t - ((3f * t) * t) + 1f;
        return ((polynomial / t) * t) * 2f;
    }

    void push() {
        if (depth == 127) {
            throw new IllegalStateException("native render-state stack exhausted");
        }
        if (depth == saved.size()) {
            saved.add(new Saved());
        }
        var target = saved.get(depth++);
        System.arraycopy(records, 0, target.records, 0, records.length);
        System.arraycopy(fixedActive, 0, target.fixedActive, 0, fixedActive.length);
    }

    void pop(boolean restoredProgrammable) {
        if (depth == 0) {
            throw new IllegalStateException("native render-state stack is empty");
        }
        var source = saved.get(--depth);
        System.arraycopy(source.records, 0, records, 0, records.length);
        System.arraycopy(source.fixedActive, 0, fixedActive, 0, fixedActive.length);
        System.arraycopy(fixedActive, 0, active, 0, active.length);
        if (restoredProgrammable) {
            Arrays.fill(programmableRecords, 0);
        }
    }

    int getDepth() {
        return depth;
    }

    private static final class Saved {
        final int[] records = new int[SLOTS * RECORD_WORDS];
        final boolean[] fixedActive = new boolean[SLOTS];
    }

    void setLight(
            int slot,
            @Nullable Input light,
            boolean programmable,
            boolean modulate2x,
            boolean bypassSunScale,
            float actorIntensity,
            float ordinaryScale,
            USphere bounds) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        if (light == null || programmable && slot >= PROGRAMMABLE_SLOTS) {
            active[slot] = false;
            fixedActive[slot] = false;
            return;
        }
        var base = slot * RECORD_WORDS;
        Arrays.fill(records, base, base + RECORD_WORDS, 0);
        var sun = light.effect() == 19;
        if (sun) {
            sunDirection.put(light.direction());
        }
        var spot = light.effect() == 8 || light.effect() == 12;
        records[base] = sun ? 3 : spot ? 2 : 1;
        if (sun || spot) {
            putVector(base + 16, light.direction());
        }
        if (spot) {
            put(base + 25, 1.5707964f);
        }
        var firstScale = sun ? bypassSunScale ? 1f : .8f : ordinaryScale;
        var rawScale = sun ? modulate2x ? .875f : .5f : modulate2x ? .5f : 1f;
        put(base + 1, ((light.color().getX() * actorIntensity) * firstScale) * rawScale);
        put(base + 2, ((light.color().getY() * actorIntensity) * firstScale) * rawScale);
        put(base + 3, ((light.color().getZ() * actorIntensity) * firstScale) * rawScale);
        put(base + 4, 1f);
        active[slot] = true;
        if (!sun) {
            putVector(base + 13, light.position());
            put(base + 19, light.radius());
            var dx = bounds.location().getX() - light.position().getX();
            var dy = bounds.location().getY() - light.position().getY();
            var dz = bounds.location().getZ() - light.position().getZ();
            var distance = (float) Math.sqrt((dx * dx + dy * dy) + dz * dz);
            var near = distance - bounds.radius();
            if (near < 0) {
                near = 0;
            }
            near += 1;
            var far = bounds.radius() + distance;
            if (light.radius() < far) {
                far = light.radius();
            }
            far -= 1;
            var nearValue = kernel(near, light.radius());
            var farValue = kernel(far, light.radius());
            if (nearValue == 0 || farValue == 0) {
                put(base + 21, 1f);
                active[slot] = false;
            } else {
                var inverseNear = 1f / nearValue;
                var a0 = near == far ? 0f : inverseNear - ((1f / farValue - inverseNear) / (far - near)) * near;
                if (a0 <= 0f) {
                    a0 = 0f;
                }
                var a1 = (inverseNear - a0) / near;
                if (a0 == 0f && a1 == 0f) {
                    a0 = 1f;
                }
                put(base + 21, a0);
                put(base + 22, a1);
            }
        }
        if (programmable) {
            System.arraycopy(records, base, programmableRecords, base, RECORD_WORDS);
        } else {
            fixedActive[slot] = active[slot];
        }
    }

    Constants getVertexConstants() {
        return collect(vertex, true);
    }

    Vec3 getSunDirection() {
        return new Vec3(sunDirection);
    }

    Constants getPixelConstants() {
        return collect(pixel, false);
    }

    boolean isActive(int slot) {
        return active[slot];
    }

    boolean isFixedActive(int slot) {
        return fixedActive[slot];
    }

    int recordWord(int slot, int word) {
        return records[slot * RECORD_WORDS + word];
    }

    int getProgrammableWord(int slot, int word) {
        return programmableRecords[slot * RECORD_WORDS + word];
    }

    private Constants collect(Constants output, boolean vertexConsumer) {
        Arrays.fill(output.flags, 0f);
        var count = 0;
        for (var slot = 0; slot < PROGRAMMABLE_SLOTS; slot++) {
            if (vertexConsumer && !active[slot]) {
                continue;
            }
            var base = slot * RECORD_WORDS;
            var type = programmableRecords[base];
            if (type == 3) {
                output.flags[0] = 1;
                copy(base + 1, output.sun, 0, 4);
            } else if (type == 1 || type == 2) {
                if (count < 2) {
                    output.flags[count + 1] = 1;
                }
                var target = count * 4;
                if (vertexConsumer) {
                    Arrays.fill(output.pointTypes, target, target + 4, (float) type);
                    copy(base + 13, output.positions, target, 3);
                    output.positions[target + 3] = 0;
                }
                copy(base + 1, output.colors, target, 4);
                output.attenuation[target] = readFloat(base + 21);
                output.attenuation[target + 1] = readFloat(base + 22);
                output.attenuation[target + 2] = readFloat(base + 19);
                output.attenuation[target + 3] = vertexConsumer ? 0 : 1;
                count++;
            }
        }
        output.flags[3] = count;
        return output;
    }

    private void put(int word, float value) {
        records[word] = Float.floatToRawIntBits(value);
    }

    private void putVector(int word, Vec3 value) {
        put(word, value.getX());
        put(word + 1, value.getY());
        put(word + 2, value.getZ());
    }

    private float readFloat(int word) {
        return Float.intBitsToFloat(programmableRecords[word]);
    }

    private void copy(int word, float[] target, int offset, int length) {
        for (var i = 0; i < length; i++) {
            target[offset + i] = readFloat(word + i);
        }
    }
}

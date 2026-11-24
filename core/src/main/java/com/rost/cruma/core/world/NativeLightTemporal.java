package com.rost.cruma.core.world;

import glm_.vec3.Vec3;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.jetbrains.annotations.Nullable;

public final class NativeLightTemporal {
    private final DoubleSupplier random;
    private float strobeTime;
    private int strobeState;

    public NativeLightTemporal(DoubleSupplier random, float strobeTime, int strobeState) {
        this.random = Objects.requireNonNull(random);
        this.strobeTime = strobeTime;
        this.strobeState = strobeState;
    }

    public static final class Palette {
        private final int[] colors;

        public Palette(int[] colors) {
            if (colors.length != 256) {
                throw new IllegalArgumentException("a light palette needs 256 colors");
            }
            this.colors = colors.clone();
        }

        public int getColor(int index) {
            return colors[index];
        }
    }

    public record Inputs(
            int type,
            int period,
            int phase,
            float lifeSpan,
            float defaultLifeSpan,
            float fadeRate,
            boolean spawnedLight,
            float spawnedPower,
            @Nullable Palette palette) {}

    public static final class ActorState {
        private float fadePhase;

        public ActorState(float lightOnTime) {
            fadePhase = lightOnTime;
        }

        public float getFadePhase() {
            return fadePhase;
        }
    }

    public record Sample(float power, @Nullable Vec3 paletteColor, int paletteIndex) {}

    public static float lifeFraction(float remaining, float classDefault) {
        var fraction = 1f - remaining / classDefault;
        if (fraction < 0f) {
            return 0f;
        }
        return fraction < 1f ? fraction : 1f;
    }

    public static float worldRadius(int type, float authoredRadius) {
        return type == 12 ? authoredRadius : (authoredRadius + 1f) * 25f;
    }

    public static float flicker(float random) {
        return random >= .5f ? random : 0f;
    }

    public Sample update(Inputs input, ActorState actor, float levelTime, @Nullable Float engineDelta) {
        if (input.type() == 8 || input.type() == 9) {
            return samplePalette(input, levelTime);
        }
        var power = switch (input.type()) {
            case 1 -> 1f;
            case 2 -> LightPulse.sample(levelTime, input.period(), input.phase());
            case 3 -> (angle(levelTime, (input.period() & 255) + 1, input.phase()) & 1) == 0 ? 1f : 0f;
            case 4 -> flicker((float) random.getAsDouble());
            case 5 -> {
                if (levelTime != strobeTime) {
                    strobeState ^= 1;
                    strobeTime = levelTime;
                }
                yield strobeState == 0 ? 1f : 0f;
            }
            case 7 -> {
                var index = (angle(levelTime, Math.max(input.period() & 255, 1), input.phase()) >> 2) & 0x3fff;
                var sine = (float) Math.sin((index * 2.0) * Math.PI / 16_384.0);
                yield sine * .09f + .9f;
            }
            case 10 -> Math.min((1f - lifeFraction(input.lifeSpan(), input.defaultLifeSpan())) * 1.5f, 1f);
            case 11 -> {
                if (engineDelta != null) {
                    actor.fadePhase += input.fadeRate() * engineDelta;
                }
                var sine = (float) Math.sin((double) actor.fadePhase);
                yield sine >= 1f ? 1f : sine;
            }
            case 12 -> input.spawnedLight() ? input.spawnedPower() : 0f;
            default -> 0f;
        };
        return new Sample(power, null, -1);
    }

    public float getStrobeTime() {
        return strobeTime;
    }

    public int getStrobeState() {
        return strobeState;
    }

    private static Sample samplePalette(Inputs input, float levelTime) {
        if (input.palette() == null) {
            return new Sample(0f, null, -1);
        }
        var once = input.type() == 8;
        var index = once
                ? (int) Math.floor(lifeFraction(input.lifeSpan(), input.defaultLifeSpan()) * 255f)
                : (truncate((levelTime * 35f / Math.max(input.period() & 255, 1) + (input.phase() & 255)) * 256f) & 255)
                        % 255;
        var color = input.palette().getColor(index);
        var red = (float) ((color >>> 16) & 255);
        var green = (float) ((color >>> 8) & 255);
        var blue = (float) (color & 255);
        var square = red * red + green * green + blue * blue;
        var inverse = once ? square == 0f ? 0f : 1f / (float) Math.sqrt(square) : (float) (1.0 / Math.sqrt(square));
        var normalized = new Vec3(red * inverse, green * inverse, blue * inverse);
        var power = ((red * 2f + green * 3f + blue) / 1536f) * 2.8f;
        return new Sample(power, normalized, index);
    }

    private static int angle(float time, int period, int phase) {
        return truncate(time * 35f * 65_536f / period + ((phase & 255) << 8));
    }

    private static int truncate(float value) {
        return value >= -0x1p31f && value < 0x1p31f ? (int) value : Integer.MIN_VALUE;
    }
}

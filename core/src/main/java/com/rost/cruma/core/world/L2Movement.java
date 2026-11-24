package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.mat4x4.Mat4;
import glm_.quat.Quat;
import glm_.vec3.Vec3;
import java.util.Objects;
import java.util.Random;

public record L2Movement(
        Vec3 location,
        URotator rotation,
        Vec3 scale,
        Vec3 prePivot,
        float drawScale,
        Vec3 originLocation,
        URotator originRotation,
        Vec3 originScale,
        Channel translation,
        Channel turning,
        Channel scaling,
        boolean initialized,
        boolean randomStart,
        boolean randomMax,
        boolean rotatedTranslation,
        long seed,
        Deltas deltas) {
    public L2Movement(
            Vec3 location,
            URotator rotation,
            Vec3 scale,
            Vec3 prePivot,
            float drawScale,
            Vec3 originLocation,
            URotator originRotation,
            Vec3 originScale,
            Channel translation,
            Channel turning,
            Channel scaling,
            boolean initialized,
            boolean randomStart,
            boolean randomMax,
            boolean rotatedTranslation,
            long seed) {
        this(
                location,
                rotation,
                scale,
                prePivot,
                drawScale,
                originLocation,
                originRotation,
                originScale,
                translation,
                turning,
                scaling,
                initialized,
                randomStart,
                randomMax,
                rotatedTranslation,
                seed,
                new Deltas(
                        location.minus(originLocation),
                        new URotator(
                                rotation.pitch() - originRotation.pitch(),
                                rotation.yaw() - originRotation.yaw(),
                                rotation.roll() - originRotation.roll()),
                        scale.minus(originScale),
                        originLocation,
                        translation.phase()));
    }

    public record Channel(Vec3 max, Vec3 rate, Vec3 phase, Vec3 ratio) {}

    public record Deltas(Vec3 location, URotator rotation, Vec3 scale, Vec3 origin, Vec3 translationPhase) {}

    public static boolean movesPlace(Channel translation, Channel turning) {
        return !State.isWrappedZero(turning.max) && !State.isWrappedZero(turning.rate)
                || !State.isZero(translation.max) && !State.isZero(translation.rate);
    }

    public L2Movement withPlacement(
            Vec3 newLocation, URotator newRotation, Vec3 newScale, Vec3 newPrePivot, float newDrawScale) {
        var turningOrigin = new URotator(
                newRotation.pitch() - deltas.rotation().pitch(),
                newRotation.yaw() - deltas.rotation().yaw(),
                newRotation.roll() - deltas.rotation().roll());
        var origin = newLocation.minus(deltas.location());
        var phase = deltas.translationPhase();
        if (!deltas.origin().equals(origin)) {
            origin = newLocation;
            phase = new Vec3();
        }
        return new L2Movement(
                newLocation,
                newRotation,
                newScale,
                newPrePivot,
                newDrawScale,
                origin,
                turningOrigin,
                newScale.minus(deltas.scale()),
                new Channel(translation.max(), translation.rate(), phase, translation.ratio()),
                turning,
                scaling,
                false,
                randomStart,
                randomMax,
                rotatedTranslation,
                seed,
                deltas);
    }

    public State newState() {
        return new State(this);
    }

    public boolean movesPlace() {
        return movesPlace(translation, turning);
    }

    public static final class State implements ActorMotion, MotionCarrier {
        private static final Mat4 IDENTITY = new Mat4();
        private static final int TURN_MASK = 0xfffc;
        private final L2Movement source;
        private final Mat4 rotationMatrix = new Mat4();
        private final Random random;
        private final Vec3[] phases;
        private final Vec3[] ratios;
        private boolean initialized;
        private Vec3 location;
        private URotator rotation;
        private Vec3 scale;
        private final Quat startInverse;

        private State(L2Movement source) {
            this.source = source;
            random = new Random(source.seed);
            phases = new Vec3[] {
                new Vec3(source.translation.phase), new Vec3(source.turning.phase), new Vec3(source.scaling.phase)
            };
            ratios = new Vec3[] {
                new Vec3(source.translation.ratio), new Vec3(source.turning.ratio), new Vec3(source.scaling.ratio)
            };
            initialized = source.initialized;
            location = source.location;
            rotation = source.rotation;
            scale = source.scale;
            startInverse = new Quat(source.rotation.vector()).inverse();
        }

        public L2Movement getSource() {
            return source;
        }

        public Mat4 getModelMatrix() {
            return getModelMatrix(new Mat4());
        }

        @Override
        public float getLocationX() {
            return location.getX();
        }

        @Override
        public float getLocationY() {
            return location.getY();
        }

        @Override
        public URotator getRotation() {
            return rotation;
        }

        @Override
        public Mat4 writeSavedMatrix(Mat4 output) {
            IDENTITY.translate(source.location, output);
            output.times(glm_.glm.INSTANCE.mat4_cast(new Quat(source.rotation.vector()), new Mat4()), output);
            output.scale(
                    source.scale.getX() * source.drawScale,
                    source.scale.getY() * source.drawScale,
                    source.scale.getZ() * source.drawScale,
                    output);
            return output.translate(-source.prePivot.getX(), -source.prePivot.getY(), -source.prePivot.getZ(), output);
        }

        public Mat4 advance(float seconds) {
            return advance(seconds, new Mat4());
        }

        public Mat4 writeModelMatrix(Mat4 output) {
            return getModelMatrix(output);
        }

        @Override
        public Vec3 follow(Vec3 start) {
            var offset = start.minus(source.location);
            return location.plus(new Quat(rotation.vector()).times(startInverse.times(offset)));
        }

        @Override
        public URotator follow(URotator start) {
            var from = source.rotation;
            return new URotator(
                    start.pitch() + (rotation.pitch() & TURN_MASK) - (from.pitch() & TURN_MASK),
                    start.yaw() + (rotation.yaw() & TURN_MASK) - (from.yaw() & TURN_MASK),
                    start.roll() + (rotation.roll() & TURN_MASK) - (from.roll() & TURN_MASK));
        }

        @Override
        public Mat4 advance(float seconds, Mat4 output) {
            Objects.requireNonNull(output, "movement output matrix");
            if (!Float.isFinite(seconds) || seconds < 0f) {
                throw new IllegalArgumentException("invalid movement delta");
            }
            if (seconds == 0f) {
                return getModelMatrix(output);
            }
            if (!initialized) {
                for (var index = 0; index < 3; index++) {
                    var channel = channel(index);
                    ratios[index] = new Vec3(1f);
                    if (source.randomStart) {
                        for (var axis = 0; axis < 3; axis++) {
                            var product = index == 1
                                    ? (float) (channel.max.get(axis).intValue()
                                            * channel.rate.get(axis).intValue())
                                    : channel.max.get(axis) * channel.rate.get(axis);
                            phases[index].set(axis, product * random.nextFloat());
                        }
                    }
                }
                initialized = true;
                return getModelMatrix(output);
            }
            var scaleDelta = evaluate(2, seconds);
            if (!isZero(scaleDelta)) {
                scale = source.originScale.plus(scaleDelta);
            }
            var rotationDelta = evaluate(1, seconds);
            var translationDelta = evaluate(0, seconds);
            if (source.rotatedTranslation) {
                translationDelta = new Quat(rotation.vector()).times(translationDelta);
            }
            if (!isWrappedZero(rotationDelta) || !isZero(translationDelta)) {
                location = source.originLocation.plus(translationDelta);
                rotation = new URotator(
                        source.originRotation.pitch() + (int) rotationDelta.getX(),
                        source.originRotation.yaw() + (int) rotationDelta.getY(),
                        source.originRotation.roll() + (int) rotationDelta.getZ());
            }
            return getModelMatrix(output);
        }

        private Mat4 getModelMatrix(Mat4 output) {
            IDENTITY.translate(location, output);
            glm_.glm.INSTANCE.mat4_cast(new Quat(rotation.vector()), rotationMatrix);
            output.times(rotationMatrix, output);
            output.scale(
                    scale.getX() * source.drawScale,
                    scale.getY() * source.drawScale,
                    scale.getZ() * source.drawScale,
                    output);
            return output.translate(-source.prePivot.getX(), -source.prePivot.getY(), -source.prePivot.getZ(), output);
        }

        private Channel channel(int index) {
            return switch (index) {
                case 0 -> source.translation;
                case 1 -> source.turning;
                default -> source.scaling;
            };
        }

        private Vec3 evaluate(int index, float seconds) {
            var channel = channel(index);
            var result = new Vec3();
            if (index == 1
                    ? isWrappedZero(channel.max) || isWrappedZero(channel.rate)
                    : isZero(channel.max) || isZero(channel.rate)) {
                return result;
            }
            for (var axis = 0; axis < 3; axis++) {
                var max = channel.max.get(axis);
                var phase = phases[index].get(axis);
                phase += (index == 1 ? max > 0f : max != 0f) ? channel.rate.get(axis) * seconds / max : 0f;
                if (phase >= 1f) {
                    phase -= (float) Math.floor(phase);
                    if (source.randomMax) {
                        ratios[index].set(axis, random.nextFloat() * 0.5f + 0.5f);
                    }
                }
                phases[index].set(axis, phase);
                var wave = ratios[index].get(axis) * (float) Math.sin(phase * (2.0 * Math.PI));
                var value = index == 2 ? max * (wave + (wave == 0f ? 0f : 1f)) * 0.5f : max * wave;
                result.set(axis, index == 1 ? (int) value : value);
            }
            return result;
        }

        private static boolean isZero(Vec3 value) {
            return value.getX() == 0f && value.getY() == 0f && value.getZ() == 0f;
        }

        private static boolean isWrappedZero(Vec3 value) {
            return ((int) value.getX() & 0xffff) == 0
                    && ((int) value.getY() & 0xffff) == 0
                    && ((int) value.getZ() & 0xffff) == 0;
        }
    }
}

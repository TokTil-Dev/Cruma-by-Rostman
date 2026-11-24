package com.rost.cruma.core.world;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.jetbrains.annotations.Nullable;

public record TextureCoordinates(List<Modifier> modifiers, Source source) {
    public static final TextureCoordinates IDENTITY = new TextureCoordinates(List.of());

    public TextureCoordinates {
        modifiers = List.copyOf(modifiers);
        Objects.requireNonNull(source);
        if (source == Source.WORLD_REFLECTION && !modifiers.isEmpty()) {
            throw new IllegalArgumentException("world reflection coordinates take no modifiers");
        }
    }

    public TextureCoordinates(List<Modifier> modifiers) {
        this(modifiers, Source.MESH_UV);
    }

    public enum Source {
        MESH_UV,
        WORLD_REFLECTION,
        CAMERA_REFLECTION,
        SPHERE_MAP
    }

    public Matrix matrix(float seconds, State state) {
        var result = Matrix.IDENTITY;
        for (var modifier : modifiers) {
            result = result.multiply(modifier.matrix(seconds, state));
        }
        return result;
    }

    public sealed interface Modifier permits Oscillator, Rotator, Scaler, Panner {
        Matrix matrix(float seconds, State state);
    }

    public record Matrix(
            float m00, float m01, float m02, float m10, float m11, float m12, float m20, float m21, float m22) {
        public static final Matrix IDENTITY = new Matrix(1, 0, 0, 0, 1, 0, 0, 0, 1);

        public static Matrix translation(float u, float v) {
            return new Matrix(1, 0, 0, 0, 1, 0, u, v, 1);
        }

        public float u(float u, float v) {
            return u * m00 + v * m10 + m20;
        }

        public float v(float u, float v) {
            return u * m01 + v * m11 + m21;
        }

        public Matrix multiply(Matrix b) {
            return new Matrix(
                    m00 * b.m00 + m01 * b.m10 + m02 * b.m20,
                    m00 * b.m01 + m01 * b.m11 + m02 * b.m21,
                    m00 * b.m02 + m01 * b.m12 + m02 * b.m22,
                    m10 * b.m00 + m11 * b.m10 + m12 * b.m20,
                    m10 * b.m01 + m11 * b.m11 + m12 * b.m21,
                    m10 * b.m02 + m11 * b.m12 + m12 * b.m22,
                    m20 * b.m00 + m21 * b.m10 + m22 * b.m20,
                    m20 * b.m01 + m21 * b.m11 + m22 * b.m21,
                    m20 * b.m02 + m21 * b.m12 + m22 * b.m22);
        }

        public Matrix around(float u, float v) {
            if (u == 0f && v == 0f) {
                return this;
            }
            return translation(-u, -v).multiply(this).multiply(translation(u, v));
        }
    }

    public record Axis(int type, float rate, float phase, float amplitude) {}

    public record Trigger(int action, float stopAfterPeriod, float time, boolean reverse, boolean triggered) {}

    public record Jitter(float lastU, float lastV, float currentU, float currentV) {}

    public record Oscillator(
            Axis u,
            Axis v,
            float offsetU,
            float offsetV,
            Jitter initialJitter,
            @Nullable Trigger trigger) implements Modifier {
        @Override
        public Matrix matrix(float seconds, State state) {
            var runtime = state.oscillators.computeIfAbsent(state.identityOf(this), key -> new OscillatorState(this));
            var time = effectiveTime(seconds, runtime, state.editor);
            var su = u.rate() * time;
            var sv = v.rate() * time;
            var x = oscillation(u, su);
            var y = oscillation(v, sv);
            if (u.type() == 3) {
                if (runtime.lastU < 1f || su + 1f < runtime.lastU) {
                    runtime.lastU = floor(su) + u.phase();
                }
                if (1f < su - runtime.lastU) {
                    runtime.currentU = (float) state.random.getAsDouble() * u.amplitude();
                    runtime.lastU = floor(su) + u.phase();
                }
                x = runtime.currentU;
            }
            if (v.type() == 3) {
                if (runtime.lastV < 1f || sv + 1f < runtime.lastU) {
                    runtime.lastV = floor(sv) + v.phase();
                }
                if (1f < sv - runtime.lastV) {
                    runtime.currentV = (float) state.random.getAsDouble() * v.amplitude();
                    runtime.lastV = floor(sv) + v.phase();
                }
                y = runtime.currentV;
            }
            var scaleU = u.type() == 1 || u.type() == 2;
            var scaleV = v.type() == 1 || v.type() == 2;
            return new Matrix(
                            scaleU ? 1f + x : 1f,
                            0,
                            0,
                            0,
                            scaleV ? 1f + y : 1f,
                            0,
                            u.type() == 0 || u.type() == 3 ? x : 0f,
                            v.type() == 0 || v.type() == 3 ? y : 0f,
                            1)
                    .around(offsetU, offsetV);
        }

        private float effectiveTime(float time, OscillatorState runtime, boolean editor) {
            if (trigger == null || editor) {
                return time;
            }
            var rate = u.rate() < v.rate() ? v.rate() : u.rate();
            return triggeredTime(time, runtime, trigger.stopAfterPeriod() / rate);
        }

        private static float oscillation(Axis axis, float s) {
            if (axis.type() < 0 || axis.type() > 2) {
                return 0f;
            }
            var frequency = axis.type() == 2 ? Math.PI / 2.0 : Math.PI * 2.0;
            return (float) (Math.sin((double) (s - floor(s)) * frequency + (double) axis.phase() * (Math.PI * 2.0))
                    * axis.amplitude());
        }
    }

    public record Angles(int pitch, int yaw, int roll) {}

    public record Rotator(
            int type, Angles rotation, Angles rate, Angles amplitude, Angles phase, float offsetU, float offsetV)
            implements Modifier {
        @Override
        public Matrix matrix(float seconds, State state) {
            var angles = switch (type) {
                case 1 ->
                    new Angles(
                            truncate(rotation.pitch() * seconds) & 65535,
                            truncate(rotation.yaw() * seconds) & 65535,
                            truncate(rotation.roll() * seconds) & 65535);
                case 2 ->
                    new Angles(
                            rotation.pitch()
                                    + oscillatingAngle(rate.pitch(), amplitude.pitch(), phase.pitch(), seconds),
                            rotation.yaw() + oscillatingAngle(rate.yaw(), amplitude.yaw(), phase.yaw(), seconds),
                            rotation.roll() + oscillatingAngle(rate.roll(), amplitude.roll(), phase.roll(), seconds));
                default -> rotation;
            };
            var sr = angleSin(angles.roll());
            var cr = angleSin(angles.roll() + 16384);
            var sp = angleSin(angles.pitch());
            var cp = angleSin(angles.pitch() + 16384);
            var sy = angleSin(angles.yaw());
            var cy = angleSin(angles.yaw() + 16384);
            return new Matrix(
                            cy * cp,
                            cp * sy,
                            sp,
                            sp * sr * cy - cr * sy,
                            sp * sr * sy + cy * cr,
                            -(cp * sr),
                            -(cr * sp * cy + sy * sr),
                            cy * sr - cr * sp * sy,
                            cp * cr)
                    .around(offsetU, offsetV);
        }

        private static int oscillatingAngle(int rate, int amplitude, int phase, float seconds) {
            var cycles = (rate * seconds) / 65535f;
            var angle = ((cycles - floor(cycles)) + (float) phase) * (Math.PI * 2.0);
            return truncate((float) (Math.sin(angle) * amplitude));
        }
    }

    public record Scaler(float u, float v, float offsetU, float offsetV) implements Modifier {
        @Override
        public Matrix matrix(float seconds, State state) {
            return new Matrix(1f / u, 0, 0, 0, 1f / v, 0, offsetU, offsetV, 1);
        }
    }

    public record Panner(
            float rate, Angles direction, @Nullable Trigger trigger) implements Modifier {
        public Panner(float rate, Angles direction) {
            this(rate, direction, null);
        }

        @Override
        public Matrix matrix(float seconds, State state) {
            if (trigger != null && !state.editor) {
                var runtime = state.panners.computeIfAbsent(state.identityOf(this), key -> new TriggerState(trigger));
                seconds = triggeredTime(seconds, runtime, trigger.stopAfterPeriod() / rate);
            }
            var cp = angleSin(direction.pitch() + 16384);
            var x = rate * seconds * (cp * angleSin(direction.yaw() + 16384)) * (1f / 1024f);
            var y = rate * seconds * (cp * angleSin(direction.yaw())) * (1f / 1024f);
            return Matrix.translation((x - floor(x)) * 1024f, (y - floor(y)) * 1024f);
        }
    }

    public static final class State {
        private final IdentityHashMap<Object, OscillatorState> oscillators = new IdentityHashMap<>();
        private final IdentityHashMap<Object, TriggerState> panners = new IdentityHashMap<>();
        private final IdentityHashMap<Modifier, Object> identities = new IdentityHashMap<>();
        private final DoubleSupplier random;
        private final boolean editor;

        public State(DoubleSupplier random, boolean editor) {
            this.random = Objects.requireNonNull(random);
            this.editor = editor;
        }

        public void bindIdentity(Modifier modifier, Object identity) {
            identities.put(Objects.requireNonNull(modifier), Objects.requireNonNull(identity));
        }

        public void trigger(Oscillator oscillator, float seconds) {
            if (oscillator.trigger() == null) {
                return;
            }
            trigger(
                    oscillators.computeIfAbsent(identityOf(oscillator), key -> new OscillatorState(oscillator)),
                    oscillator.trigger().action(),
                    seconds);
        }

        public void trigger(Panner panner, float seconds) {
            if (panner.trigger() == null) {
                return;
            }
            trigger(
                    panners.computeIfAbsent(identityOf(panner), key -> new TriggerState(panner.trigger())),
                    panner.trigger().action(),
                    seconds);
        }

        public void reset(Oscillator oscillator) {
            if (oscillator.trigger() == null) {
                return;
            }
            reset(oscillators.computeIfAbsent(identityOf(oscillator), key -> new OscillatorState(oscillator)));
        }

        public void reset(Panner panner) {
            if (panner.trigger() == null) {
                return;
            }
            reset(panners.computeIfAbsent(identityOf(panner), key -> new TriggerState(panner.trigger())));
        }

        private Object identityOf(Modifier modifier) {
            return identities.getOrDefault(modifier, modifier);
        }

        private static void trigger(TriggerState runtime, int action, float seconds) {
            if (runtime.triggered) {
                if (action == 0 || action == 1) {
                    runtime.triggered = false;
                    runtime.triggeredTime = action == 0 ? seconds : -1f;
                    runtime.reverse = true;
                }
            } else {
                if (action != 3) {
                    runtime.triggered = true;
                }
                runtime.triggeredTime = seconds;
                runtime.reverse = false;
            }
        }

        private static void reset(TriggerState runtime) {
            runtime.triggered = false;
            runtime.triggeredTime = -1f;
            runtime.reverse = false;
        }
    }

    private static class TriggerState {
        float triggeredTime;
        boolean triggered;
        boolean reverse;

        TriggerState(@Nullable Trigger trigger) {
            if (trigger != null) {
                triggeredTime = trigger.time();
                triggered = trigger.triggered();
                reverse = trigger.reverse();
            }
        }
    }

    private static final class OscillatorState extends TriggerState {
        float lastU;
        float lastV;
        float currentU;
        float currentV;

        OscillatorState(Oscillator oscillator) {
            super(oscillator.trigger());
            var jitter = oscillator.initialJitter();
            lastU = jitter.lastU();
            lastV = jitter.lastV();
            currentU = jitter.currentU();
            currentV = jitter.currentV();
        }
    }

    private static float triggeredTime(float time, TriggerState runtime, float duration) {
        if (!(runtime.triggeredTime >= 0f)) {
            return 0f;
        }
        if (runtime.reverse) {
            var remaining = duration - (time - runtime.triggeredTime);
            return remaining < 0f ? 0f : remaining;
        }
        var elapsed = time - runtime.triggeredTime;
        return duration < elapsed ? duration : elapsed;
    }

    private static float floor(float value) {
        return truncate((float) Math.floor(value));
    }

    private static int truncate(float value) {
        return !Float.isFinite(value) || value >= 0x1p31f || value < -0x1p31f ? Integer.MIN_VALUE : (int) value;
    }

    private static float angleSin(int angle) {
        return (float) Math.sin(((angle >> 2) & 0x3fff) * (Math.PI * 2.0 / 16384.0));
    }
}

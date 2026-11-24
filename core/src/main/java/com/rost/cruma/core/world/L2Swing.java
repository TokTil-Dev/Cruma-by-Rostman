package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.quat.Quat;
import glm_.vec3.Vec3;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record L2Swing(
        String actorPath,
        Vec3 location,
        URotator rotation,
        Axis pitch,
        Axis roll,
        Axis yaw,
        boolean randomMax,
        boolean randomStart,
        @Nullable Saved saved,
        @Nullable String carrier,
        List<Follower> followers,
        int seed) {
    public static final int MOST_TAGS = 12;

    private static final int TURN_MASK = 0xfffc;

    public L2Swing {
        Objects.requireNonNull(actorPath, "actorPath");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(rotation, "rotation");
        followers = List.copyOf(followers);
    }

    public record Axis(int max, int rate, float accel) {
        public boolean starts() {
            return max > 0 && rate > 0;
        }
    }

    public record Saved(URotator origin, Vec3 currentMax, Vec3 cycle, Vec3 time) {}

    public enum Kind {
        EMITTER,
        LIGHT,
        MESH,
        SWING
    }

    public record Follower(String actorPath, Kind kind, Vec3 location, URotator rotation) {}

    public boolean isMoving() {
        return movesAxis(0) || movesAxis(1) || movesAxis(2);
    }

    public State newState() {
        return new State(this);
    }

    public L2Swing withPlacement(Vec3 location, URotator rotation, @Nullable Saved saved) {
        return new L2Swing(
                actorPath,
                location,
                rotation,
                pitch,
                roll,
                yaw,
                randomMax,
                randomStart,
                saved,
                carrier,
                followers,
                seed);
    }

    public Axis axis(int index) {
        return switch (index) {
            case 0 -> pitch;
            case 1 -> roll;
            default -> yaw;
        };
    }

    public boolean movesAxis(int index) {
        var axis = axis(index);
        if (axis.accel() == 0f || axis.max() == 0) {
            return false;
        }
        return saved == null ? axis.starts() : component(saved.currentMax(), index) > 0f;
    }

    public static final class State implements MotionCarrier {
        private final int[] origin = new int[3];
        private final float[] currentMax = new float[3];
        private final float[] cycle = new float[3];
        private final float[] time = new float[3];
        private L2Swing swing;
        private Quat startInverse;
        private boolean started;
        private int random;
        private URotator rotation;
        private Vec3 location;
        private Quat turn;
        private long revision;
        private boolean resting;

        private State(L2Swing swing) {
            begin(swing);
        }

        public L2Swing getSwing() {
            return swing;
        }

        public void rebase(L2Swing next) {
            if (resting
                    || !next.rotation().equals(swing.rotation())
                    || !Objects.equals(next.saved(), swing.saved())
                    || !next.pitch().equals(swing.pitch())
                    || !next.roll().equals(swing.roll())
                    || !next.yaw().equals(swing.yaw())
                    || next.randomMax() != swing.randomMax()
                    || next.randomStart() != swing.randomStart()
                    || next.seed() != swing.seed()) {
                begin(next);
            } else {
                swing = next;
                if (next.carrier() == null) {
                    location = next.location();
                }
            }
            revision++;
        }

        public void restart(L2Swing next) {
            begin(next);
            revision++;
        }

        public void rest() {
            rotation = swing.rotation();
            location = swing.location();
            turn = new Quat(rotation.vector());
            startInverse = turn.inverse();
            resting = true;
            revision++;
        }

        public boolean isResting() {
            return resting;
        }

        public URotator getRotation() {
            return rotation;
        }

        public Vec3 getLocation() {
            return location;
        }

        public long getRevision() {
            return revision;
        }

        public boolean advance(float seconds) {
            if (!(seconds > 0f) || resting) {
                return false;
            }
            if (!started) {
                start(seconds);
                return false;
            }
            var next = new int[3];
            for (var index = 0; index < 3; index++) {
                next[index] = tick(index, seconds);
            }
            var turned = new URotator(next[0], next[2], next[1]);
            if (turned.equals(rotation)) {
                return false;
            }
            rotation = turned;
            turn = new Quat(rotation.vector());
            revision++;
            return true;
        }

        public void carry(Vec3 place) {
            if (!resting && !place.equals(location)) {
                location = place;
                revision++;
            }
        }

        @Override
        public Vec3 follow(Vec3 start) {
            var offset = start.minus(swing.location());
            return location.plus(turn.times(startInverse.times(offset)));
        }

        @Override
        public URotator follow(URotator start) {
            var from = swing.rotation();
            return new URotator(
                    start.pitch() + (rotation.pitch() & TURN_MASK) - (from.pitch() & TURN_MASK),
                    start.yaw() + (rotation.yaw() & TURN_MASK) - (from.yaw() & TURN_MASK),
                    start.roll() + (rotation.roll() & TURN_MASK) - (from.roll() & TURN_MASK));
        }

        private void begin(L2Swing next) {
            swing = next;
            resting = false;
            started = false;
            random = next.seed();
            rotation = next.rotation();
            location = next.location();
            turn = new Quat(rotation.vector());
            startInverse = turn.inverse();
            var saved = next.saved();
            if (saved != null) {
                started = true;
                for (var index = 0; index < 3; index++) {
                    origin[index] = angle(saved.origin(), index);
                    currentMax[index] = component(saved.currentMax(), index);
                    cycle[index] = component(saved.cycle(), index);
                    time[index] = component(saved.time(), index);
                }
            }
        }

        private void start(float seconds) {
            started = true;
            for (var index = 0; index < 3; index++) {
                origin[index] = angle(swing.rotation(), index);
                var axis = swing.axis(index);
                if (!axis.starts()) {
                    currentMax[index] = -1f;
                    continue;
                }
                var max = axis.max();
                if (swing.randomMax()) {
                    var first = rand() % max;
                    var second = rand() % max;
                    currentMax[index] = second * first % max;
                } else {
                    currentMax[index] = max;
                }
                cycle[index] = (float) (currentMax[index] * 4.0 / axis.rate());
                time[index] = swing.randomStart() ? (float) ((rand() % 1000) / 1000.0) : seconds;
            }
        }

        private int tick(int index, float seconds) {
            var current = currentMax[index];
            if (current < 0f) {
                return origin[index];
            }
            var axis = swing.axis(index);
            var elapsed = time[index] + seconds;
            time[index] = elapsed;
            var max = (float) axis.max();
            double ratio = max / (double) current;
            var period = cycle[index] * 4.0 / (axis.accel() * ratio);
            if (!(period <= elapsed)) {
                var phase = elapsed * (double) axis.accel() * Math.PI * (float) ratio / cycle[index];
                var swingAngle = (float) (current * Math.sin(phase));
                return (int) (origin[index] + (double) swingAngle);
            }
            time[index] = (float) (elapsed - period);
            if (swing.randomMax()) {
                var low = Math.clamp((float) ((rand() % 100) / 200.0), 0.2f, 0.4f);
                var high = Math.clamp((float) ((rand() % 100) / 200.0), 0f, 0.5f);
                currentMax[index] = (float) ((double) (low + high) * axis.max());
            } else {
                currentMax[index] = max;
            }
            cycle[index] = (float) (currentMax[index] * 4.0 / axis.rate());
            return origin[index];
        }

        private int rand() {
            random = random * 214013 + 2531011;
            return (random >>> 16) & 0x7fff;
        }
    }

    private static float component(Vec3 vector, int index) {
        return switch (index) {
            case 0 -> vector.getX();
            case 1 -> vector.getY();
            default -> vector.getZ();
        };
    }

    private static int angle(URotator rotator, int index) {
        return switch (index) {
            case 0 -> rotator.pitch();
            case 1 -> rotator.roll();
            default -> rotator.yaw();
        };
    }
}

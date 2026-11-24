package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record PhysicsRotation(
        String actorPath,
        Vec3 location,
        URotator rotation,
        URotator rate,
        URotator desired,
        boolean fixedDirection,
        boolean toDesired,
        @Nullable String carrier) {
    public static final int PHYS_NONE = 0;

    public static final int PHYS_ROTATING = 5;

    private static final int TURN = 65536;
    private static final int HALF_TURN = 32768;
    private static final int UNITS = 0xffff;

    public PhysicsRotation {
        Objects.requireNonNull(actorPath, "actorPath");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(desired, "desired");
    }

    public static boolean turns(
            int physics,
            boolean staticActor,
            boolean movable,
            URotator rate,
            boolean fixedDirection,
            boolean toDesired,
            boolean interpolating) {
        return physics != PHYS_NONE
                && !staticActor
                && movable
                && !isZero(rate)
                && !interpolating
                && (fixedDirection || toDesired);
    }

    public static boolean isZero(URotator rotator) {
        return (rotator.pitch() & UNITS) == 0 && (rotator.yaw() & UNITS) == 0 && (rotator.roll() & UNITS) == 0;
    }

    public static int step(int rate, float seconds) {
        return (int) (rate * seconds);
    }

    public static int fixedTurn(int current, int desired, int delta, boolean fixedDirection, boolean toDesired) {
        current &= UNITS;
        if (delta == 0) {
            return current;
        }
        desired &= UNITS;
        int result;
        if (fixedDirection && !toDesired) {
            result = current + delta;
        } else if (fixedDirection) {
            if (delta > 0) {
                if (current > desired) {
                    desired += TURN;
                }
                result = current + Math.min(delta, desired - current);
            } else {
                var from = current < desired ? current + TURN : current;
                result = current + Math.max(delta, desired - from);
            }
        } else if (toDesired) {
            var most = Math.abs(delta);
            if (current > desired) {
                result = current - desired < HALF_TURN
                        ? current - Math.min(current - desired, most)
                        : current + Math.min(desired - current + TURN, most);
            } else {
                result = desired - current < HALF_TURN
                        ? current + Math.min(desired - current, most)
                        : current - Math.min(current - desired + TURN, most);
            }
        } else {
            result = current;
        }
        return result & UNITS;
    }

    public State newState() {
        return new State(this);
    }

    public PhysicsRotation withPlacement(Vec3 location, URotator rotation) {
        return new PhysicsRotation(actorPath, location, rotation, rate, desired, fixedDirection, toDesired, carrier);
    }

    public PhysicsRotation withCarrier(@Nullable String newCarrier) {
        return new PhysicsRotation(actorPath, location, rotation, rate, desired, fixedDirection, toDesired, newCarrier);
    }

    public static final class State {
        private static final URotator NO_CHANGE = new URotator(0, 0, 0);
        private PhysicsRotation turn;
        private int pitch;
        private int yaw;
        private int roll;
        private int carriedPitch;
        private int carriedYaw;
        private int carriedRoll;
        private @Nullable URotator rotation;
        private Vec3 location;
        private long revision;

        private State(PhysicsRotation turn) {
            this.turn = turn;
            rotation = turn.rotation();
            pitch = rotation.pitch();
            yaw = rotation.yaw();
            roll = rotation.roll();
            location = turn.location();
        }

        public PhysicsRotation getTurn() {
            return turn;
        }

        public void rebase(PhysicsRotation next) {
            if (!Objects.equals(next.carrier(), turn.carrier())) {
                carry(location, NO_CHANGE);
            }
            if (!next.rotation().equals(turn.rotation())
                    || !next.rate().equals(turn.rate())
                    || !next.desired().equals(turn.desired())
                    || next.fixedDirection() != turn.fixedDirection()
                    || next.toDesired() != turn.toDesired()) {
                pitch = next.rotation().pitch();
                yaw = next.rotation().yaw();
                roll = next.rotation().roll();
                carriedPitch = 0;
                carriedYaw = 0;
                carriedRoll = 0;
            }
            turn = next;
            location = next.location();
            markChanged();
        }

        public void restart(PhysicsRotation next) {
            turn = next;
            pitch = next.rotation().pitch();
            yaw = next.rotation().yaw();
            roll = next.rotation().roll();
            carriedPitch = 0;
            carriedYaw = 0;
            carriedRoll = 0;
            location = next.location();
            markChanged();
        }

        public URotator getRotation() {
            var now = rotation;
            if (now == null) {
                now = new URotator(pitch, yaw, roll);
                rotation = now;
            }
            return now;
        }

        public int getPitch() {
            return pitch;
        }

        public int getYaw() {
            return yaw;
        }

        public int getRoll() {
            return roll;
        }

        public Vec3 getLocation() {
            return location;
        }

        public long getRevision() {
            return revision;
        }

        public boolean advance(float seconds) {
            if (!(seconds > 0f) || !turn.fixedDirection() && !turn.toDesired()) {
                return false;
            }
            var desired = turn.desired();
            if (turn.toDesired() && pitch == desired.pitch() && yaw == desired.yaw() && roll == desired.roll()) {
                return false;
            }
            var rate = turn.rate();
            var nextYaw = turnAngle(yaw, desired.yaw(), step(rate.yaw(), seconds));
            var nextPitch = turnAngle(pitch, desired.pitch(), step(rate.pitch(), seconds));
            var nextRoll = turnAngle(roll, desired.roll(), step(rate.roll(), seconds));
            if (nextPitch == pitch && nextYaw == yaw && nextRoll == roll) {
                return false;
            }
            pitch = nextPitch;
            yaw = nextYaw;
            roll = nextRoll;
            markChanged();
            return true;
        }

        public void carry(Vec3 place, URotator change) {
            if (!place.equals(location)) {
                location = place;
                revision++;
            }
            if (change.pitch() == carriedPitch && change.yaw() == carriedYaw && change.roll() == carriedRoll) {
                return;
            }
            pitch += change.pitch() - carriedPitch;
            yaw += change.yaw() - carriedYaw;
            roll += change.roll() - carriedRoll;
            carriedPitch = change.pitch();
            carriedYaw = change.yaw();
            carriedRoll = change.roll();
            markChanged();
        }

        private void markChanged() {
            rotation = null;
            revision++;
        }

        private int turnAngle(int angle, int desired, int delta) {
            if (delta == 0 || turn.toDesired() && angle == desired) {
                return angle;
            }
            return fixedTurn(angle, desired, delta, turn.fixedDirection(), turn.toDesired());
        }
    }
}

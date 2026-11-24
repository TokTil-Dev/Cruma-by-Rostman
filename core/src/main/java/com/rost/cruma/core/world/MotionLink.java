package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record MotionLink(
        @Nullable L2Swing swing,
        L2Swing.@Nullable Follower follower,
        @Nullable PhysicsRotation turn,
        Vec3 scale,
        Vec3 prePivot,
        @Nullable MovementCarrier movement) {
    public MotionLink {
        Objects.requireNonNull(scale, "scale");
        Objects.requireNonNull(prePivot, "prePivot");
        if (swing == null && turn == null && movement == null) {
            throw new IllegalArgumentException("a motion link needs a swing, a turn or a carrier");
        }
        if (follower != null && swing == null && movement == null) {
            throw new IllegalArgumentException("a carried mesh needs the swing that carries it");
        }
        if (movement != null && (follower == null || swing != null || turn != null)) {
            throw new IllegalArgumentException("an L2Movement carries only a mesh that does not swing or turn here");
        }
        if (swing != null && follower == null && turn != null) {
            throw new IllegalArgumentException("a swinging mesh does not turn by its RotationRate here");
        }
    }

    public MotionLink(
            @Nullable L2Swing swing,
            L2Swing.@Nullable Follower follower,
            @Nullable PhysicsRotation turn,
            Vec3 scale,
            Vec3 prePivot) {
        this(swing, follower, turn, scale, prePivot, null);
    }

    public static MotionLink ofSwing(L2Swing swing, Vec3 scale, Vec3 prePivot) {
        return new MotionLink(swing, null, null, scale, prePivot);
    }

    public static MotionLink ofCarried(
            L2Swing swing, L2Swing.Follower follower, @Nullable PhysicsRotation turn, Vec3 scale, Vec3 prePivot) {
        return new MotionLink(swing, follower, turn, scale, prePivot);
    }

    public static MotionLink ofMovementCarried(
            MovementCarrier carrier, L2Swing.Follower follower, Vec3 scale, Vec3 prePivot) {
        return new MotionLink(null, follower, null, scale, prePivot, carrier);
    }

    public static MotionLink ofTurn(PhysicsRotation turn, Vec3 scale, Vec3 prePivot) {
        return new MotionLink(null, null, turn, scale, prePivot);
    }

    public static Mat4 writeRotation(Mat4 output, int pitch, int yaw, int roll) {
        double z = URotator.toRadians(yaw);
        double y = -URotator.toRadians(pitch);
        double x = -URotator.toRadians(roll);
        var cz = Math.cos(z);
        var sz = Math.sin(z);
        var cy = Math.cos(y);
        var sy = Math.sin(y);
        var cx = Math.cos(x);
        var sx = Math.sin(x);
        output.put(
                (float) (cz * cy),
                (float) (sz * cy),
                (float) -sy,
                0f,
                (float) (cz * sy * sx - sz * cx),
                (float) (sz * sy * sx + cz * cx),
                (float) (cy * sx),
                0f,
                (float) (cz * sy * cx + sz * sx),
                (float) (sz * sy * cx - cz * sx),
                (float) (cy * cx),
                0f,
                0f,
                0f,
                0f,
                1f);
        return output;
    }

    public Motion newMotion(L2Swing.@Nullable State swingState, PhysicsRotation.@Nullable State turnState) {
        var placement = new Placement();
        placement.set(this, swingState, turnState);
        return new Motion(placement);
    }

    public String getActorPath() {
        if (follower != null) {
            return follower.actorPath();
        }
        return turn != null ? turn.actorPath() : Objects.requireNonNull(swing).actorPath();
    }

    public MotionLink withPlacement(
            Vec3 location, URotator rotation, Vec3 newScale, Vec3 newPrePivot, L2Swing.@Nullable Saved saved) {
        var placedTurn = turn == null ? null : turn.withPlacement(location, rotation);
        if (follower != null) {
            return new MotionLink(
                    swing,
                    new L2Swing.Follower(follower.actorPath(), follower.kind(), location, rotation),
                    placedTurn,
                    newScale,
                    newPrePivot,
                    movement);
        }
        var placedSwing = swing == null ? null : swing.withPlacement(location, rotation, saved);
        return new MotionLink(placedSwing, null, placedTurn, newScale, newPrePivot);
    }

    public static final class Placement {
        private MotionLink link;
        private L2Swing.@Nullable State swingState;
        private PhysicsRotation.@Nullable State turnState;
        private @Nullable MotionCarrier movementState;

        public MotionLink getLink() {
            return link;
        }

        public L2Swing.@Nullable State getSwingState() {
            return swingState;
        }

        public PhysicsRotation.@Nullable State getTurnState() {
            return turnState;
        }

        public void set(
                MotionLink newLink,
                L2Swing.@Nullable State newSwingState,
                PhysicsRotation.@Nullable State newTurnState) {
            if (newLink.swing() != null && newSwingState == null || newLink.turn() != null && newTurnState == null) {
                throw new IllegalArgumentException(
                        "the motion of " + newLink + " needs the state of its swing and turn");
            }
            link = newLink;
            swingState = newLink.swing() == null ? null : newSwingState;
            turnState = newLink.turn() == null ? null : newTurnState;
            if (newLink.movement() == null) {
                movementState = null;
            }
        }

        public void setMovementState(MotionCarrier state) {
            movementState = Objects.requireNonNull(state, "movement state");
        }

        public Motion createMotion() {
            return new Motion(this);
        }
    }

    public static final class Motion implements ActorMotion {
        private static final Mat4 IDENTITY = new Mat4();
        private final Placement placement;
        private final Mat4 rotationMatrix = new Mat4();
        private URotator rotation = new URotator(0, 0, 0);
        private int pitch;
        private int yaw;
        private int roll;
        private Vec3 location;

        private Motion(Placement placement) {
            this.placement = placement;
            place();
        }

        @Override
        public Mat4 advance(float seconds, Mat4 output) {
            place();
            var link = placement.link;
            IDENTITY.translate(location, output);
            writeRotation(rotationMatrix, pitch, yaw, roll);
            output.times(rotationMatrix, output);
            output.scale(link.scale(), output);
            var pivot = link.prePivot();
            return output.translate(-pivot.getX(), -pivot.getY(), -pivot.getZ(), output);
        }

        @Override
        public URotator getRotation() {
            var turnState = placement.turnState;
            return turnState != null ? turnState.getRotation() : rotation;
        }

        @Override
        public float getLocationX() {
            return location.getX();
        }

        @Override
        public float getLocationY() {
            return location.getY();
        }

        public Placement getPlacement() {
            return placement;
        }

        @Override
        public Mat4 writeSavedMatrix(Mat4 output) {
            var link = placement.link;
            var follower = link.follower();
            var swing = link.swing();
            var turn = link.turn();
            Vec3 place;
            URotator angles;
            if (follower != null) {
                place = follower.location();
                angles = follower.rotation();
            } else if (turn != null) {
                place = turn.location();
                angles = turn.rotation();
            } else {
                place = Objects.requireNonNull(swing).location();
                angles = swing.rotation();
            }
            IDENTITY.translate(place, output);
            output.times(writeRotation(new Mat4(), angles.pitch(), angles.yaw(), angles.roll()), output);
            output.scale(link.scale(), output);
            var pivot = link.prePivot();
            return output.translate(-pivot.getX(), -pivot.getY(), -pivot.getZ(), output);
        }

        private void place() {
            var turnState = placement.turnState;
            if (turnState != null) {
                location = turnState.getLocation();
                pitch = turnState.getPitch();
                yaw = turnState.getYaw();
                roll = turnState.getRoll();
                return;
            }
            var follower = placement.link.follower();
            if (follower == null) {
                var swing = Objects.requireNonNull(placement.swingState, "swing state");
                location = swing.getLocation();
                rotation = swing.getRotation();
            } else {
                MotionCarrier carrier = placement.link.movement() != null
                        ? Objects.requireNonNull(placement.movementState, "movement state")
                        : Objects.requireNonNull(placement.swingState, "swing state");
                location = carrier.follow(follower.location());
                rotation = carrier.follow(follower.rotation());
            }
            pitch = rotation.pitch();
            yaw = rotation.yaw();
            roll = rotation.roll();
        }
    }
}

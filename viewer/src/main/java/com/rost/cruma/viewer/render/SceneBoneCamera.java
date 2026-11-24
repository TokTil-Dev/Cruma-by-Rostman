package com.rost.cruma.viewer.render;

import com.rost.cruma.core.unreal.UMeshAnimation.CameraMark;
import com.rost.cruma.core.world.SceneRig;
import com.rost.cruma.core.world.SceneRig.Frame;
import com.rost.cruma.viewer.render.GameScenePlayer.CastPlay;
import com.rost.cruma.viewer.render.GameScenePlayer.SceneActor;
import com.rost.cruma.viewer.render.SceneCamera.Target;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class SceneBoneCamera {
    private static final int TURN = 0x10000;
    private static final double DISAPPEAR_SECONDS = .5;

    private final List<Pawn> pawns = new ArrayList<>();
    private final Map<SceneActor, CastPlay> casts = new IdentityHashMap<>();
    private @Nullable Held held;
    private boolean ran;

    static double roll(double[] look, double[] up) {
        var flat = Math.hypot(look[0], look[1]);
        var yaw = Math.atan2(look[1], look[0]);
        var level = new double[] {-look[2] * Math.cos(yaw), -look[2] * Math.sin(yaw), flat};
        var cosine = level[0] * up[0] + level[1] * up[1] + level[2] * up[2];
        var degrees = Math.toDegrees(Math.acos(Math.clamp(cosine, -1, 1)));
        var left = (look[1] * level[2] - look[2] * level[1]) * up[0]
                + (look[2] * level[0] - look[0] * level[2]) * up[1]
                + (look[0] * level[1] - look[1] * level[0]) * up[2];
        return left >= 0 ? -degrees : degrees;
    }

    static boolean passes(double at, double from, double to, double length, boolean loops) {
        if (at <= 0) {
            return false;
        }
        if (!loops) {
            return from < at && at <= to;
        }
        return length > 0 && Math.floor((to - at) / length) > Math.floor((from - at) / length);
    }

    void reset() {
        pawns.clear();
        casts.clear();
        held = null;
        ran = false;
    }

    void shot(SceneActor target) {
        if (held != null && !(held.around() && held.body().getTarget() == target)) {
            held = null;
        }
    }

    void left(SceneActor actor) {
        casts.remove(actor);
        if (held != null && held.body() instanceof ActorBody body && body.actor() == actor) {
            held = null;
        }
    }

    void played(
            SceneActor actor,
            @Nullable SceneRig rig,
            @Nullable SceneRig cameraRig,
            double from,
            double to,
            @Nullable CastPlay cast) {
        var last = cast == null ? casts.remove(actor) : casts.put(actor, cast);
        if (rig == null) {
            return;
        }
        if (cast != null) {
            var sequence = rig.findSequence(cast.sequence());
            if (sequence >= 0) {
                var goesOn = last != null
                        && last.sequence().equalsIgnoreCase(cast.sequence())
                        && last.seconds() <= cast.seconds();
                run(
                        new ActorBody(actor, casts),
                        rig,
                        sequence,
                        goesOn ? last.seconds() : 0,
                        cast.seconds(),
                        false,
                        cameraRig);
            }
        } else if (!actor.getAnimation().isEmpty() && !actor.isCasting()) {
            var sequence = rig.findSequence(actor.getAnimation());
            if (sequence >= 0) {
                run(new ActorBody(actor, casts), rig, sequence, from, to, actor.loops(), cameraRig);
            }
        }
    }

    void advance(double delta, @Nullable SceneRig cameraRig) {
        for (var pawn : List.copyOf(pawns)) {
            var from = pawn.seconds;
            pawn.seconds += delta;
            run(pawn, pawn.rig, pawn.sequence, from, pawn.seconds, false, cameraRig);
        }
        pawns.removeIf(pawn -> pawn.seconds > pawn.rig.getSeconds(pawn.sequence) + DISAPPEAR_SECONDS);
    }

    boolean hasRun() {
        var any = ran;
        ran = false;
        return any;
    }

    boolean holds() {
        return held != null;
    }

    @Nullable
    Pose getPose() {
        var riding = held;
        if (riding == null || riding.around()) {
            return null;
        }
        var body = riding.body();
        var frame = frame(riding);
        if (frame == null) {
            return null;
        }
        var turn = body.getYaw() * 2 * Math.PI / TURN;
        var cos = Math.cos(turn);
        var sin = Math.sin(turn);
        var at = inWorld(body, frame);
        var look = turned(frame.look().getX(), frame.look().getY(), frame.look().getZ(), cos, sin);
        var up = turned(frame.up().getX(), frame.up().getY(), frame.up().getZ(), cos, sin);
        return new Pose(
                at[0],
                at[1],
                at[2],
                Math.toDegrees(Math.atan2(look[1], look[0])),
                Math.toDegrees(Math.atan2(look[2], Math.hypot(look[0], look[1]))),
                roll(look, up));
    }

    @Nullable
    Around getAround() {
        var riding = held;
        if (riding == null || !riding.around()) {
            return null;
        }
        var frame = frame(riding);
        if (frame == null) {
            return null;
        }
        var at = inWorld(riding.body(), frame);
        return new Around(riding.body().getTarget(), at[0], at[1], at[2]);
    }

    record Around(Target body, float x, float y, float z) implements Target {
        @Override
        public int getYaw() {
            return body.getYaw();
        }
    }

    record Pose(float x, float y, float z, double yawDegrees, double pitchDegrees, double rollDegrees) {}

    private Frame frame(Held riding) {
        var body = riding.body();
        var frame = body.plays(riding.name())
                ? riding.rig().frame(riding.sequence(), (float) body.getSeconds(), riding.bone())
                : null;
        if (frame == null) {
            held = null;
        }
        return frame;
    }

    private void run(
            Body body,
            SceneRig rig,
            int sequence,
            double from,
            double to,
            boolean loops,
            @Nullable SceneRig cameraRig) {
        var length = rig.getSeconds(sequence);
        for (var mark : rig.getMarks(sequence)) {
            if (!mark.ownCamera() && passes(mark.time() * length, from, to, length, loops)) {
                take(mark, body, rig, sequence, cameraRig);
            }
        }
    }

    private void take(CameraMark mark, Body body, SceneRig rig, int sequence, @Nullable SceneRig cameraRig) {
        ran = true;
        switch (mark.kind()) {
            case RELEASE -> held = null;
            case AROUND_BONE -> held = new Held(body, rig, sequence, mark.bone(), body.getPlaying(), true);
            case ON_BONE -> held = new Held(body, rig, sequence, mark.bone(), body.getPlaying(), false);
            case CAMERA_PAWN -> {
                var played = cameraRig == null ? -1 : cameraRig.findSequence(mark.pawnSequence());
                if (played >= 0) {
                    var offset = mark.pawnOffset();
                    pawns.add(new Pawn(
                            body.x() + offset.getX(),
                            body.y() + offset.getY(),
                            body.z() + offset.getZ(),
                            body.getYaw(),
                            cameraRig.scaled(mark.pawnScale()),
                            played));
                }
            }
            default -> {
            }
        }
    }

    private static float[] inWorld(Body body, Frame frame) {
        var turn = body.getYaw() * 2 * Math.PI / TURN;
        var cos = Math.cos(turn);
        var sin = Math.sin(turn);
        var at = frame.place();
        return new float[] {
            (float) (body.x() + at.getX() * cos - at.getY() * sin),
            (float) (body.y() + at.getX() * sin + at.getY() * cos),
            body.z() + at.getZ()
        };
    }

    private static double[] turned(float x, float y, float z, double cos, double sin) {
        return new double[] {x * cos - y * sin, x * sin + y * cos, z};
    }

    private record Held(Body body, SceneRig rig, int sequence, int bone, String name, boolean around) {}

    private sealed interface Body extends Target permits ActorBody, Pawn {
        Target getTarget();

        double getSeconds();

        String getPlaying();

        boolean plays(String name);
    }

    private record ActorBody(SceneActor actor, Map<SceneActor, CastPlay> casts) implements Body {
        @Override
        public Target getTarget() {
            return actor;
        }

        @Override
        public float x() {
            return actor.x();
        }

        @Override
        public float y() {
            return actor.y();
        }

        @Override
        public float z() {
            return actor.z();
        }

        @Override
        public int getYaw() {
            return actor.getYaw();
        }

        @Override
        public double getSeconds() {
            var cast = casts.get(actor);
            return cast == null ? actor.getAnimationSeconds() : cast.seconds();
        }

        @Override
        public String getPlaying() {
            var cast = casts.get(actor);
            return cast == null ? actor.getAnimation() : cast.sequence();
        }

        @Override
        public boolean plays(String name) {
            var cast = casts.get(actor);
            return cast == null
                    ? !actor.isCasting() && actor.getAnimation().equalsIgnoreCase(name)
                    : cast.sequence().equalsIgnoreCase(name);
        }
    }

    private static final class Pawn implements Body {
        private final float x;
        private final float y;
        private final float z;
        private final int yaw;
        private final SceneRig rig;
        private final int sequence;
        private double seconds;

        Pawn(float x, float y, float z, int yaw, SceneRig rig, int sequence) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.rig = rig;
            this.sequence = sequence;
        }

        @Override
        public Target getTarget() {
            return this;
        }

        @Override
        public float x() {
            return x;
        }

        @Override
        public float y() {
            return y;
        }

        @Override
        public float z() {
            return z;
        }

        @Override
        public int getYaw() {
            return yaw;
        }

        @Override
        public double getSeconds() {
            return seconds;
        }

        @Override
        public String getPlaying() {
            return "";
        }

        @Override
        public boolean plays(String name) {
            return true;
        }
    }
}

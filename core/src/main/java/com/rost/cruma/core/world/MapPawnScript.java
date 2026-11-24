package com.rost.cruma.core.world;

import com.rost.cruma.core.npc.script.ScriptPawn;
import com.rost.cruma.core.npc.script.ScriptPawn.Host;
import com.rost.cruma.core.npc.script.ScriptValues.Coords;
import com.rost.cruma.core.npc.script.ScriptValues.Rotator;
import com.rost.cruma.core.npc.script.ScriptValues.Vector;
import com.rost.cruma.core.npc.script.ScriptedClass;
import com.rost.cruma.core.npc.script.ScriptedClass.Default;
import com.rost.cruma.core.world.MapPawnPlayer.Moment;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Nullable;

public final class MapPawnScript {
    public static final String LOGON = "Logon_Warp";

    private final @Nullable ScriptPawn pawn;
    private final boolean ownAnimEnd;
    private final MapPawnChannel channel;
    private final String tag;
    private final String wait;
    private boolean begun;
    private boolean ended;

    public MapPawnScript(
            @Nullable ScriptedClass type,
            List<Default> saved,
            String tag,
            ToDoubleFunction<String> lengths,
            String wait,
            Host world) {
        this.tag = tag;
        this.wait = wait;
        channel = new MapPawnChannel(lengths);
        pawn = type == null ? null : new ScriptPawn(type, new Played(world), saved);
        ownAnimEnd = type != null && type.declares("AnimEnd");
    }

    public void begin(boolean lobby) {
        if (begun || ended) {
            return;
        }
        begun = true;
        if (!wait.isEmpty()) {
            channel.play(wait, 1);
        }
        if (pawn != null) {
            pawn.postBeginPlay();
        }
        if (lobby) {
            trigger(LOGON);
        }
    }

    public void trigger(String event) {
        if (begun && !ended && pawn != null && tag.equalsIgnoreCase(event)) {
            pawn.trigger();
        }
    }

    public void runTo(double time) {
        if (!begun || ended) {
            return;
        }
        var step = time - channel.getClock();
        if (pawn != null && step > 0) {
            pawn.tick((float) step);
        }
        channel.runTo(time, this::animEnd);
    }

    public void end() {
        if (begun && !ended && pawn != null) {
            pawn.destroyed();
        }
        ended = true;
    }

    public @Nullable Moment getMoment() {
        return channel.getMoment();
    }

    public @Nullable ScriptPawn getPawn() {
        return pawn;
    }

    private void animEnd() {
        if (ownAnimEnd && pawn != null) {
            pawn.animEnd(0);
        } else if (!wait.isEmpty()) {
            channel.loop(wait, 1);
        }
    }

    private final class Played implements Host {
        private final Host world;

        Played(Host world) {
            this.world = world;
        }

        @Override
        public Vector getLocation() {
            return world.getLocation();
        }

        @Override
        public Rotator getRotation() {
            return world.getRotation();
        }

        @Override
        public float getCollisionHeight() {
            return world.getCollisionHeight();
        }

        @Override
        public float getCollisionRadius() {
            return world.getCollisionRadius();
        }

        @Override
        public boolean hasBone(String bone) {
            return world.hasBone(bone);
        }

        @Override
        public @Nullable Coords getBoneCoords(String bone) {
            return world.getBoneCoords(bone);
        }

        @Override
        public boolean canSpawn(String effectClass) {
            return world.canSpawn(effectClass);
        }

        @Override
        public float random() {
            return world.random();
        }

        @Override
        public boolean playAnim(String sequence, float rate, float tweenTime, int number) {
            return number == 0 && channel.play(sequence, rate);
        }

        @Override
        public boolean loopAnim(String sequence, float rate, float tweenTime, int number) {
            return number == 0 && channel.loop(sequence, rate);
        }

        @Override
        public @Nullable String findBone(int index) {
            return world.findBone(index);
        }
    }
}

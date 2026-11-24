package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.MapScenes.Scene;
import com.rost.cruma.core.world.MapScenes.Timed;
import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

public final class ScenePlayer {
    private static final double FIRST_MOMENT = 1e-4;
    private static final int WAITING = 0;
    private static final int RUNNING = 1;
    private static final int ENDED = 2;
    private static final int DONE = 3;

    private final Camera camera;
    private final Consumer<String> events;
    private @Nullable Scene scene;
    private List<Timed> timed = List.of();
    private int[] states = new int[0];
    private double[] starts = new double[0];
    private double seconds;
    private double speed = 1;
    private float fade;
    private float freeFov;
    private boolean loop;

    public ScenePlayer(Camera camera) {
        this(camera, _ -> {});
    }

    public ScenePlayer(Camera camera, Consumer<String> events) {
        this.camera = camera;
        this.events = events;
    }

    public void play(Scene scene) {
        if (this.scene == null) {
            freeFov = camera.getFov();
        } else {
            camera.setFov(freeFov);
        }
        if (camera.isInteractiveOrtho()) {
            camera.setView(CameraView.PERSPECTIVE);
        }
        this.scene = scene;
        restart();
        apply();
        events.accept(scene.tag());
        if (scene.seconds() <= 0) {
            stop();
        }
    }

    public void stop() {
        if (scene == null) {
            return;
        }
        scene = null;
        timed = List.of();
        camera.setFov(freeFov);
        fade = 0;
    }

    public void update(double delta) {
        var playing = scene;
        if (playing == null) {
            return;
        }
        seconds += Math.max(0, delta) * speed;
        var length = playing.seconds();
        if (seconds <= length) {
            apply();
            return;
        }
        seconds = length;
        apply();
        if (loop || playing.looping()) {
            camera.setFov(freeFov);
            restart();
            apply();
        } else {
            stop();
        }
    }

    public boolean isPlaying() {
        return scene != null;
    }

    public @Nullable Scene getScene() {
        return scene;
    }

    public double getSeconds() {
        return seconds;
    }

    public float getFade() {
        return fade;
    }

    public boolean loops() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    private void restart() {
        var playing = scene;
        timed = playing == null ? List.of() : playing.timed();
        states = new int[timed.size()];
        starts = new double[timed.size()];
        seconds = 0;
        speed = 1;
        fade = 0;
    }

    private void apply() {
        var playing = scene;
        if (playing == null) {
            return;
        }
        var length = playing.seconds();
        var at = Math.clamp(seconds, length * FIRST_MOMENT, length);
        var pose = playing.pose(at);
        camera.setPosition((float) pose.x(), (float) pose.y(), (float) pose.z());
        camera.setDirection(pose.yawDegrees(), pose.pitchDegrees());
        for (var i = 0; i < timed.size(); i++) {
            runSubAction(i, at);
        }
    }

    private void runSubAction(int index, double at) {
        var state = states[index];
        if (state == DONE) {
            return;
        }
        if (state == ENDED) {
            states[index] = DONE;
            return;
        }
        var item = timed.get(index);
        if (item.start() < at && at <= item.end()) {
            state = RUNNING;
        } else if (at > item.end()) {
            state = ENDED;
        }
        if (state == WAITING) {
            return;
        }
        var subAction = item.subAction();
        if (states[index] == WAITING) {
            starts[index] = switch (subAction.effect()) {
                case FOV -> camera.getFov();
                case SCENE_SPEED -> speed;
                case FADE -> fade;
            };
        }
        states[index] = state;
        var value = subAction.value(state == ENDED ? 1 : item.progress(at), starts[index]);
        switch (subAction.effect()) {
            case FOV -> camera.setFov((float) Math.clamp(value, 1, 170));
            case SCENE_SPEED -> speed = Math.max(0, value);
            case FADE -> fade = (float) Math.clamp(value, 0, 1);
        }
    }
}

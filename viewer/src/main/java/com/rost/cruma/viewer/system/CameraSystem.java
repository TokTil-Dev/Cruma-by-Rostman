package com.rost.cruma.viewer.system;

import com.rost.cruma.viewer.application.OrthoSlice;
import com.rost.cruma.viewer.application.UiContext;
import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.application.WindowContext.Keyboard;
import com.rost.cruma.viewer.application.WindowContext.Keyboard.Key;
import com.rost.cruma.viewer.config.CaptureView;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.CameraView;
import com.rost.cruma.viewer.render.RenderingContext;
import imgui.ImGui;

public class CameraSystem implements System {
    static final double WHEEL_ZOOM = 1.1;
    static final double WHEEL_SPEED = 1.25;
    static final double KEY_ZOOM_PER_SECOND = 2;
    static final double SLICE_NOTCH = 1d / 20;

    static final double LOOK_DEGREES = 0.08;

    private static final float[] TRANSLATION_SPEED = {1000f, 2000f, 3000f, 4000f, 5000f};
    private static final float MAX_TRANSLATION_SPEED = 50000f;

    private final RenderingContext renderingContext;
    private final WindowContext windowContext;
    private final UiContext uiContext;

    private int tileIndex = 0;

    public CameraSystem(RenderingContext renderingContext, WindowContext windowContext, UiContext uiContext) {
        this.renderingContext = renderingContext;
        this.windowContext = windowContext;
        this.uiContext = uiContext;
    }

    static float wheelSpeed(float speed, double wheel) {
        if (wheel == 0 || !Double.isFinite(wheel)) {
            return speed;
        }
        return (float) Math.clamp(speed * Math.pow(WHEEL_SPEED, wheel), 100, MAX_TRANSLATION_SPEED);
    }

    static void look(Camera camera, double dx, double dy, double sensitivity) {
        if (dx == 0 && dy == 0 || !Double.isFinite(dx) || !Double.isFinite(dy)) {
            return;
        }
        var degrees = LOOK_DEGREES * sensitivity;
        var pitch = Math.clamp(
                camera.getPitchDegrees() + dy * degrees, -Camera.MAX_PITCH_DEGREES, Camera.MAX_PITCH_DEGREES);
        camera.setDirection(camera.getYawDegrees() - dx * degrees, pitch);
    }

    static void zoomAtPointer(Camera camera, double wheel, double x, double y, int width, int height) {
        if (wheel == 0 || !Double.isFinite(wheel) || width <= 0 || height <= 0) {
            return;
        }
        var ndcX = (float) (x / width * 2 - 1);
        var ndcY = (float) (1 - y / height * 2);
        camera.zoomOrthoAt((float) Math.pow(WHEEL_ZOOM, -wheel), ndcX, ndcY);
    }

    static void moveSlice(Camera camera, Rendering rendering, double wheel) {
        var view = camera.getView();
        if (wheel == 0 || !Double.isFinite(wheel) || !view.isOrtho()) {
            return;
        }
        var delta = (int) Math.round(wheel * camera.getOrthoVisibleSize() * SLICE_NOTCH) * view.getDepthDirection();
        if (view == CameraView.TOP) {
            OrthoSlice.move(
                    rendering.orthoCutTop,
                    rendering.orthoTop,
                    rendering.orthoCutBottom,
                    rendering.orthoBottom,
                    delta,
                    CaptureView.HEIGHT_LIMIT);
        } else {
            var slice = rendering.getSlice(view);
            OrthoSlice.move(slice.cutNear, slice.near, slice.cutFar, slice.far, delta, OrthoSlice.LIMIT);
        }
    }

    static void moveOrtho(Camera camera, Keyboard keyboard, float seconds, float modifier) {
        moveOrtho(camera, keyboard, seconds, modifier, true);
    }

    static void toggleTop(Camera camera, Keyboard keyboard, boolean effectsOnly) {
        if (keyboard.isClicked(Key.O) && !effectsOnly) {
            camera.toggleInteractiveOrthoProjection();
        }
    }

    @Override
    public void frameBegin(long frameTime) {
        if (uiContext.worldMapOpen
                || renderingContext.scenes.isPlaying()
                || renderingContext.gameScenes.holdsCamera()
                || uiContext.dialogOpen
                || ImGui.getIO().getWantTextInput()
                || renderingContext.playback.isComparisonLocked()
                || renderingContext.editorInputCaptured
                || uiContext.editingPrepared && ImGui.getIO().getKeyCtrl()) {
            return;
        }
        var mouse = windowContext.mouse;
        var keyboard = windowContext.keyboard;
        var camera = renderingContext.camera;

        toggleTop(camera, keyboard, uiContext.effectsOnly);
        var isOrtho = camera.isOrtho();

        if (keyboard.isPressed(Key.DIGIT_1)) {
            uiContext.rendering.cameraSpeed.set(TRANSLATION_SPEED[0]);
        } else if (keyboard.isPressed(Key.DIGIT_2)) {
            uiContext.rendering.cameraSpeed.set(TRANSLATION_SPEED[1]);
        } else if (keyboard.isPressed(Key.DIGIT_3)) {
            uiContext.rendering.cameraSpeed.set(TRANSLATION_SPEED[2]);
        } else if (keyboard.isPressed(Key.DIGIT_4)) {
            uiContext.rendering.cameraSpeed.set(TRANSLATION_SPEED[3]);
        } else if (keyboard.isPressed(Key.DIGIT_5)) {
            uiContext.rendering.cameraSpeed.set(TRANSLATION_SPEED[4]);
        }

        if (keyboard.isClicked(Key.PLUS)) {
            uiContext.rendering.cameraSpeed.set(
                    Math.min(MAX_TRANSLATION_SPEED, uiContext.rendering.cameraSpeed.get() * 1.5f));
        } else if (keyboard.isClicked(Key.MINUS)) {
            uiContext.rendering.cameraSpeed.set(Math.max(100f, uiContext.rendering.cameraSpeed.get() / 1.5f));
        }

        var currentTranslationSpeed =
                Math.max(100f, Math.min(MAX_TRANSLATION_SPEED, uiContext.rendering.cameraSpeed.get()));
        uiContext.rendering.cameraSpeed.set(currentTranslationSpeed);

        var seconds = frameTime * 1f / 1000;
        var translationSpeed = currentTranslationSpeed * seconds;
        var pointerFree = !ImGui.getIO().getWantCaptureMouse();

        var rightDir = camera.getRightDir();
        var upDir = camera.getUpDir();
        if (mouse.right && !isOrtho && pointerFree) {
            look(camera, mouse.dx, mouse.dy, uiContext.rendering.mouseSensitivity);
            if (mouse.wheel != 0) {
                uiContext.rendering.cameraSpeed.set(wheelSpeed(uiContext.rendering.cameraSpeed.get(), mouse.wheel));
                currentTranslationSpeed = uiContext.rendering.cameraSpeed.get();
                translationSpeed = currentTranslationSpeed * seconds;
            }
        }

        if (isOrtho && pointerFree && !keyboard.isPressed(Key.COMMAND) && !keyboard.isPressed(Key.SHIFT)) {
            if (keyboard.isPressed(Key.ALT)) {
                moveSlice(camera, uiContext.rendering, mouse.wheel);
            } else {
                zoomAtPointer(
                        camera, mouse.wheel, mouse.x, mouse.y, windowContext.window.width, windowContext.window.height);
            }
        }

        if (uiContext.editingPrepared && (!mouse.right || !pointerFree)) {
            return;
        }
        var shiftModifier = keyboard.isPressed(Key.SHIFT) ? 10.0f : 1.0f;
        var altModifier = keyboard.isPressed(Key.ALT) ? 0.2f : 1.0f;

        if (isOrtho) {
            moveOrtho(camera, keyboard, seconds, shiftModifier * altModifier, !uiContext.directorShown);
        } else {
            var speed = translationSpeed * shiftModifier * altModifier;
            var forwardDir = camera.getForwardDir();
            if (keyboard.isPressed(Key.W)) {
                camera.translate(forwardDir.negate().times(speed));
            }
            if (keyboard.isPressed(Key.S)) {
                camera.translate(forwardDir.times(speed));
            }
            if (keyboard.isPressed(Key.A)) {
                camera.translate(rightDir.negate().times(speed));
            }
            if (keyboard.isPressed(Key.D)) {
                camera.translate(rightDir.times(speed));
            }
            if (keyboard.isPressed(Key.E)) {
                camera.translate(upDir.times(speed));
            }
            if (keyboard.isPressed(Key.SPACE) && !uiContext.directorShown) {
                camera.translate(upDir.negate().times(speed));
            }
        }

        var tiles = windowContext.tiles;
        if (tiles.isEmpty()) {
            return;
        }
        if (keyboard.isClicked(Key.NEXT)) {
            var tile = tiles.get(tileIndex = ++tileIndex % tiles.size());
            camera.setPosition(
                    tile.centerX(), tile.centerY(), camera.getPosition().getZ());
        } else if (keyboard.isClicked(Key.PREV)) {
            tileIndex = tileIndex == 0 ? tiles.size() - 1 : --tileIndex % tiles.size();
            var tile = tiles.get(tileIndex);
            camera.setPosition(
                    tile.centerX(), tile.centerY(), camera.getPosition().getZ());
        }
    }

    private static void moveOrtho(Camera camera, Keyboard keyboard, float seconds, float modifier, boolean spaceZooms) {
        var pan = camera.getOrthoPanSpeed() * seconds * modifier;
        var up = camera.getForwardDir().negate();
        var right = camera.getRightDir();
        if (keyboard.isPressed(Key.W)) {
            camera.translate(up.times(pan));
        }
        if (keyboard.isPressed(Key.S)) {
            camera.translate(up.negate().times(pan));
        }
        if (keyboard.isPressed(Key.A)) {
            camera.translate(right.negate().times(pan));
        }
        if (keyboard.isPressed(Key.D)) {
            camera.translate(right.times(pan));
        }
        var out = (keyboard.isPressed(Key.E) ? 1 : 0) - (keyboard.isPressed(Key.SPACE) && spaceZooms ? 1 : 0);
        if (out != 0) {
            camera.updateOrthoZoomFactor((float) Math.pow(KEY_ZOOM_PER_SECOND, out * seconds));
        }
    }
}

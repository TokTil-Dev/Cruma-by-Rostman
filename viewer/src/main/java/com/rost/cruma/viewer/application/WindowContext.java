package com.rost.cruma.viewer.application;

import static java.lang.System.getProperty;

import com.rost.cruma.viewer.render.api.RenderExtents;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;

public class WindowContext {
    public static final boolean IS_MAC_OS = getProperty("os.name").toLowerCase().contains("mac");

    public BooleanSupplier allowClose;

    public boolean closeApproved;

    public final Size framebuffer = new Size();
    public final Size window = new Size();

    public final Size capture = new Size();

    public final Mouse mouse = new Mouse();
    public final Keyboard keyboard = new Keyboard();
    public final Input input = new Input(System.nanoTime());
    public final List<Tile> tiles = new ArrayList<>();

    public final List<Path> droppedFiles = new ArrayList<>();

    public long windowHandle;

    public boolean screenshot;
    public boolean screenshotOtherKind;
    public int mapIndex = -1;

    public int mapCaptureSubmitted = -1;

    public Tile pendingCaptureTile;

    public String pendingCaptureMap = "";

    public WindowContext() {
        capture.width = RenderExtents.CAPTURE_WIDTH;
        capture.height = RenderExtents.CAPTURE_HEIGHT;
    }

    public record Tile(int mapX, int mapY, float centerX, float centerY) {
        public String toString() {
            return mapX + "_" + mapY;
        }
    }

    public static class Size {
        public int width;
        public int height;
    }

    public static class Mouse {
        public double x;
        public double y;
        public double dx;
        public double dy;
        public boolean right;

        public double wheel;
    }

    public static class Keyboard {
        private final EnumSet<Key> pressedKeys = EnumSet.noneOf(Key.class);
        private final EnumSet<Key> releasedKeys = EnumSet.noneOf(Key.class);

        public enum Key {
            W,
            A,
            S,
            D,
            E,
            B,
            N,
            V,
            M,
            SHIFT,
            ALT,
            SPACE,
            DIGIT_1,
            DIGIT_2,
            DIGIT_3,
            DIGIT_4,
            DIGIT_5,
            PLUS,
            MINUS,
            NEXT,
            PREV,
            O,
            H,
            TAB,
            ESCAPE,
            F1,
            F2,
            F3,
            F4,
            F5,
            F6,
            F12,
            G,
            C,
            X,
            F,
            I,
            Q,
            R,
            Z,
            T,
            LEFT_BRACKET,
            RIGHT_BRACKET,
            DELETE,
            K,
            COMMA,
            PERIOD,
            L,
            COMMAND,
        }

        public void setPressed(Key key, boolean status) {
            if (status) {
                pressedKeys.add(key);
                return;
            }
            if (pressedKeys.contains(key)) {
                releasedKeys.add(key);
            }
            pressedKeys.remove(key);
        }

        public boolean isPressed(Key key) {
            return pressedKeys.contains(key);
        }

        public boolean isClicked(Key key) {
            return releasedKeys.contains(key);
        }

        public void reset() {
            releasedKeys.clear();
        }
    }

    public static class Input {
        private static final int BUTTONS = 1024;
        private final BitSet held = new BitSet();
        private long lastNanos;

        public Input(long nowNanos) {
            lastNanos = nowNanos;
        }

        public void key(int key, boolean down, long nanos) {
            if (key >= 0 && key < BUTTONS) {
                held.set(key, down);
            }
            setUsed(nanos);
        }

        public void button(int button, boolean down, long nanos) {
            if (button >= 0) {
                held.set(BUTTONS + button, down);
            }
            setUsed(nanos);
        }

        public void setUsed(long nanos) {
            lastNanos = nanos;
        }

        public void releaseAll(long nanos) {
            held.clear();
            setUsed(nanos);
        }

        public boolean isHeld() {
            return !held.isEmpty();
        }

        public long getLastNanos() {
            return lastNanos;
        }
    }

    public void drop(String[] names) {
        for (var name : names) {
            try {
                droppedFiles.add(Path.of(name));
            } catch (InvalidPathException invalid) {
            }
        }
    }
}

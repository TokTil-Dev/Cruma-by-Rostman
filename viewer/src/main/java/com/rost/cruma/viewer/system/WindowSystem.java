package com.rost.cruma.viewer.system;

import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetMouseButton;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;

import com.rost.cruma.viewer.application.ApplicationContext;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.application.WindowContext.Keyboard.Key;
import com.rost.cruma.viewer.config.InterfaceScale;
import com.rost.cruma.viewer.ui.CrumaBranding;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;

public class WindowSystem implements System {
    private static boolean noDisplay;

    private final WindowContext windowContext;
    private final ApplicationContext applicationContext;
    private final boolean interactive;
    private final boolean restored;
    private final long windowHandle;
    private final int requestedWidth;
    private final int requestedHeight;
    private final List<Callback> inputCallbacks = new ArrayList<>();
    private @Nullable Placement placement;

    public WindowSystem(
            WindowContext windowContext, ApplicationContext applicationContext, String title, int width, int height) {
        this(windowContext, applicationContext, title, width, height, true);
    }

    public WindowSystem(
            WindowContext windowContext,
            ApplicationContext applicationContext,
            String title,
            int width,
            int height,
            boolean interactive) {
        this(windowContext, applicationContext, title, width, height, interactive, false);
    }

    public WindowSystem(
            WindowContext windowContext,
            ApplicationContext applicationContext,
            String title,
            int width,
            int height,
            boolean interactive,
            boolean maximized) {
        this(windowContext, applicationContext, title, width, height, interactive, maximized, null);
    }

    public WindowSystem(
            WindowContext windowContext,
            ApplicationContext applicationContext,
            String title,
            int width,
            int height,
            boolean interactive,
            boolean maximized,
            @Nullable Placement previous) {
        this.windowContext = windowContext;
        this.applicationContext = applicationContext;
        this.interactive = interactive;
        this.requestedWidth = width;
        this.requestedHeight = height;
        if (noDisplay) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_NULL);
        }
        if (!glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }
        var restore = interactive && previous != null && isOnScreen(previous, workAreas()) ? previous : null;
        this.restored = restore != null;
        this.windowHandle = createWindow(title, width, height, interactive, maximized, restore);
        windowContext.windowHandle = windowHandle;
        if (interactive) {
            GLFW.glfwSetDropCallback(windowHandle, (window, count, names) -> {
                var paths = new String[count];
                for (var i = 0; i < count; i++) {
                    paths[i] = GLFWDropCallback.getName(names, i);
                }
                windowContext.drop(paths);
            });
            watchInput();
        }
        updateSizes();
    }

    public record Placement(int x, int y, int width, int height, boolean maximized) {}

    public static void useNoDisplay() {
        noDisplay = true;
    }

    static int @Nullable [] contentArea(int[] workArea, int left, int top, int right, int bottom) {
        int width = workArea[2] - left - right;
        int height = workArea[3] - top - bottom;
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[] {workArea[0] + left, workArea[1] + top, width, height};
    }

    static int[] centered(int width, int height, int[] area) {
        int fittedWidth = Math.min(width, area[2]);
        int fittedHeight = Math.min(height, area[3]);
        return new int[] {
            area[0] + (area[2] - fittedWidth) / 2, area[1] + (area[3] - fittedHeight) / 2, fittedWidth, fittedHeight
        };
    }

    static boolean isOnScreen(Placement placement, List<int[]> workAreas) {
        int x = placement.x() + placement.width() / 2;
        int y = placement.y();
        for (var area : workAreas) {
            if (x >= area[0] && x < area[0] + area[2] && y >= area[1] && y < area[1] + area[3]) {
                return true;
            }
        }
        return false;
    }

    public float getInterfaceFactor(InterfaceScale scale) {
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            var framebufferWidth = stack.mallocInt(1);
            var framebufferHeight = stack.mallocInt(1);
            var contentX = stack.mallocFloat(1);
            var contentY = stack.mallocFloat(1);
            GLFW.glfwGetWindowSize(windowHandle, width, height);
            GLFW.glfwGetFramebufferSize(windowHandle, framebufferWidth, framebufferHeight);
            GLFW.glfwGetWindowContentScale(windowHandle, contentX, contentY);
            float ratio = Math.max(
                    framebufferWidth.get(0) / (float) Math.max(1, width.get(0)),
                    framebufferHeight.get(0) / (float) Math.max(1, height.get(0)));
            return scale.factor(Math.max(contentX.get(0), contentY.get(0)), ratio);
        }
    }

    public void fitInterfaceScale(InterfaceScale scale) {
        if (!interactive || restored || GLFW.glfwGetWindowAttrib(windowHandle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) {
            return;
        }
        float factor = getInterfaceFactor(scale);
        if (factor == 1f) {
            return;
        }
        int width = Math.round(requestedWidth * factor);
        int height = Math.round(requestedHeight * factor);
        var area = contentArea();
        if (area == null) {
            GLFW.glfwSetWindowSize(windowHandle, width, height);
        } else {
            var bounds = centered(width, height, area);
            GLFW.glfwSetWindowSize(windowHandle, bounds[2], bounds[3]);
            if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
                GLFW.glfwSetWindowPos(windowHandle, bounds[0], bounds[1]);
            }
        }
        updateSizes();
    }

    public void limitInterfaceSize(int minimumWidth, int minimumHeight, float factor) {
        var area = contentArea();
        int width = Math.round(minimumWidth * factor);
        int height = Math.round(minimumHeight * factor);
        if (area != null) {
            width = Math.min(width, area[2]);
            height = Math.min(height, area[3]);
        }
        GLFW.glfwSetWindowSizeLimits(windowHandle, width, height, GLFW.GLFW_DONT_CARE, GLFW.GLFW_DONT_CARE);
    }

    public @Nullable Placement getPlacement() {
        return placement;
    }

    @Override
    public void start() {
        if (!interactive) {
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            var xpos = stack.mallocDouble(1);
            var ypos = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(windowHandle, xpos, ypos);
            windowContext.mouse.x = xpos.get();
            windowContext.mouse.y = ypos.get();
        }
    }

    @Override
    public void stop(WindowContext windowContext) {
        placement = currentPlacement();
        var drop = GLFW.glfwSetDropCallback(windowHandle, null);
        if (drop != null) {
            drop.free();
        }
        if (!inputCallbacks.isEmpty()) {
            GLFW.glfwSetKeyCallback(windowHandle, null);
            GLFW.glfwSetCharCallback(windowHandle, null);
            GLFW.glfwSetMouseButtonCallback(windowHandle, null);
            GLFW.glfwSetCursorPosCallback(windowHandle, null);
            GLFW.glfwSetScrollCallback(windowHandle, null);
            GLFW.glfwSetWindowFocusCallback(windowHandle, null);
            for (var callback : inputCallbacks) {
                callback.free();
            }
            inputCallbacks.clear();
        }
        glfwDestroyWindow(windowHandle);
        glfwTerminate();
    }

    @Override
    public void frameBegin(long frameTime) {
        glfwPollEvents();

        updateSizes();

        if (!interactive) {
            return;
        }

        windowContext.mouse.right = glfwGetMouseButton(windowHandle, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;

        var oldMousePositionX = windowContext.mouse.x;
        var oldMousePositionY = windowContext.mouse.y;
        try (var stack = MemoryStack.stackPush()) {
            var xpos = stack.mallocDouble(1);
            var ypos = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(windowHandle, xpos, ypos);
            windowContext.mouse.x = xpos.get();
            windowContext.mouse.y = ypos.get();
        }

        if (windowContext.mouse.right) {
            windowContext.mouse.dx = oldMousePositionX - windowContext.mouse.x;
            windowContext.mouse.dy = oldMousePositionY - windowContext.mouse.y;
        }

        if (windowContext.mouse.right) {
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        } else {
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }

        handleKey(GLFW.GLFW_KEY_W, Key.W);
        handleKey(GLFW.GLFW_KEY_A, Key.A);
        handleKey(GLFW.GLFW_KEY_S, Key.S);
        handleKey(GLFW.GLFW_KEY_D, Key.D);
        handleKey(GLFW.GLFW_KEY_E, Key.E);
        handleKey(GLFW.GLFW_KEY_B, Key.B);
        handleKey(GLFW.GLFW_KEY_N, Key.N);
        handleKey(GLFW.GLFW_KEY_V, Key.V);
        handleKey(GLFW.GLFW_KEY_M, Key.M);

        handleEitherKey(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT, Key.SHIFT);
        handleEitherKey(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT, Key.ALT);
        handleKey(GLFW.GLFW_KEY_SPACE, Key.SPACE);

        handleKey(GLFW.GLFW_KEY_1, Key.DIGIT_1);
        handleKey(GLFW.GLFW_KEY_2, Key.DIGIT_2);
        handleKey(GLFW.GLFW_KEY_3, Key.DIGIT_3);
        handleKey(GLFW.GLFW_KEY_4, Key.DIGIT_4);
        handleKey(GLFW.GLFW_KEY_5, Key.DIGIT_5);

        handleEitherKey(GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD, Key.PLUS);
        handleEitherKey(GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT, Key.MINUS);

        handleKey(GLFW.GLFW_KEY_RIGHT, Key.NEXT);
        handleKey(GLFW.GLFW_KEY_LEFT, Key.PREV);

        handleKey(GLFW.GLFW_KEY_O, Key.O);

        handleKey(GLFW.GLFW_KEY_H, Key.H);
        handleKey(GLFW.GLFW_KEY_TAB, Key.TAB);
        handleKey(GLFW.GLFW_KEY_F1, Key.F1);
        handleKey(GLFW.GLFW_KEY_F2, Key.F2);
        handleKey(GLFW.GLFW_KEY_F3, Key.F3);
        handleKey(GLFW.GLFW_KEY_F4, Key.F4);
        handleKey(GLFW.GLFW_KEY_F5, Key.F5);
        handleKey(GLFW.GLFW_KEY_F6, Key.F6);

        handleKey(GLFW.GLFW_KEY_F12, Key.F12);
        if (windowContext.keyboard.isClicked(Key.F12)) {
            windowContext.screenshot = true;
            windowContext.screenshotOtherKind = windowContext.keyboard.isPressed(Key.SHIFT);
        }

        handleKey(GLFW.GLFW_KEY_G, Key.G);
        handleKey(GLFW.GLFW_KEY_C, Key.C);
        handleKey(GLFW.GLFW_KEY_X, Key.X);
        handleKey(GLFW.GLFW_KEY_F, Key.F);
        handleKey(GLFW.GLFW_KEY_I, Key.I);
        handleKey(GLFW.GLFW_KEY_Q, Key.Q);
        handleKey(GLFW.GLFW_KEY_R, Key.R);
        handleKey(GLFW.GLFW_KEY_Z, Key.Z);
        handleKey(GLFW.GLFW_KEY_T, Key.T);
        handleKey(GLFW.GLFW_KEY_LEFT_BRACKET, Key.LEFT_BRACKET);
        handleKey(GLFW.GLFW_KEY_RIGHT_BRACKET, Key.RIGHT_BRACKET);
        handleEitherKey(GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE, Key.DELETE);
        handleKey(GLFW.GLFW_KEY_K, Key.K);
        handleKey(GLFW.GLFW_KEY_COMMA, Key.COMMA);
        handleKey(GLFW.GLFW_KEY_PERIOD, Key.PERIOD);
        handleKey(GLFW.GLFW_KEY_L, Key.L);
        var command = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;
        windowContext.keyboard.setPressed(Key.COMMAND, command);

        handleKey(GLFW.GLFW_KEY_ESCAPE, Key.ESCAPE);
        if (windowContext.closeApproved) {
            applicationContext.running = false;
        } else if (glfwWindowShouldClose(windowHandle)) {
            if (windowContext.allowClose == null || windowContext.allowClose.getAsBoolean()) {
                applicationContext.running = false;
            } else {
                GLFW.glfwSetWindowShouldClose(windowHandle, false);
            }
        }
    }

    @Override
    public void frameEnd(long frameTime) {
        if (windowContext.screenshot) {
            windowContext.screenshot = false;
            windowContext.screenshotOtherKind = false;
        }

        windowContext.keyboard.reset();
        windowContext.mouse.wheel = 0;
    }

    private int @Nullable [] contentArea() {
        var monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == 0) {
            return null;
        }
        try (var stack = MemoryStack.stackPush()) {
            var x = stack.mallocInt(1);
            var y = stack.mallocInt(1);
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            GLFW.glfwGetMonitorWorkarea(monitor, x, y, width, height);
            var left = stack.mallocInt(1);
            var top = stack.mallocInt(1);
            var right = stack.mallocInt(1);
            var bottom = stack.mallocInt(1);
            GLFW.glfwGetWindowFrameSize(windowHandle, left, top, right, bottom);
            return contentArea(
                    new int[] {x.get(0), y.get(0), width.get(0), height.get(0)},
                    left.get(0),
                    top.get(0),
                    right.get(0),
                    bottom.get(0));
        }
    }

    private void watchInput() {
        var input = windowContext.input;
        var mouse = windowContext.mouse;
        var key = GLFWKeyCallback.create((window, code, scancode, action, mods) ->
                input.key(code, action != GLFW.GLFW_RELEASE, java.lang.System.nanoTime()));
        var character = GLFWCharCallback.create((window, codepoint) -> input.setUsed(java.lang.System.nanoTime()));
        var button = GLFWMouseButtonCallback.create(
                (window, code, action, mods) -> input.button(code, action == GLFW_PRESS, java.lang.System.nanoTime()));
        var cursor = GLFWCursorPosCallback.create((window, x, y) -> input.setUsed(java.lang.System.nanoTime()));
        var scroll = GLFWScrollCallback.create((window, x, y) -> {
            mouse.wheel += y;
            input.setUsed(java.lang.System.nanoTime());
        });
        var focus = GLFWWindowFocusCallback.create((window, focused) -> {
            if (!focused) {
                input.releaseAll(java.lang.System.nanoTime());
            }
        });
        inputCallbacks.addAll(List.of(key, character, button, cursor, scroll, focus));
        GLFW.glfwSetKeyCallback(windowHandle, key);
        GLFW.glfwSetCharCallback(windowHandle, character);
        GLFW.glfwSetMouseButtonCallback(windowHandle, button);
        GLFW.glfwSetCursorPosCallback(windowHandle, cursor);
        GLFW.glfwSetScrollCallback(windowHandle, scroll);
        GLFW.glfwSetWindowFocusCallback(windowHandle, focus);
    }

    private long createWindow(
            String title, int width, int height, boolean interactive, boolean maximized, @Nullable Placement previous) {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, interactive ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        glfwWindowHint(GLFW_VISIBLE, interactive ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        if (previous != null) {
            glfwWindowHint(GLFW.GLFW_POSITION_X, previous.x());
            glfwWindowHint(GLFW.GLFW_POSITION_Y, previous.y());
            maximized = previous.maximized();
            if (!maximized) {
                width = previous.width();
                height = previous.height();
            }
        }
        glfwWindowHint(GLFW.GLFW_MAXIMIZED, interactive && maximized ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        var window = glfwCreateWindow(width, height, title, 0, 0);
        if (window == 0) {
            glfwTerminate();
            throw new IllegalStateException("Failed to create GLFW window");
        }

        CrumaBranding.setWindowIcon(window);
        return window;
    }

    private @Nullable Placement currentPlacement() {
        if (!interactive
                || GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND
                || GLFW.glfwGetWindowAttrib(windowHandle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
            return null;
        }
        try (var stack = MemoryStack.stackPush()) {
            var x = stack.mallocInt(1);
            var y = stack.mallocInt(1);
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(windowHandle, x, y);
            GLFW.glfwGetWindowSize(windowHandle, width, height);
            return new Placement(
                    x.get(0),
                    y.get(0),
                    width.get(0),
                    height.get(0),
                    GLFW.glfwGetWindowAttrib(windowHandle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE);
        }
    }

    private void updateSizes() {
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(windowHandle, width, height);
            windowContext.window.width = width.get(0);
            windowContext.window.height = height.get(0);
            GLFW.glfwGetFramebufferSize(windowHandle, width, height);
            windowContext.framebuffer.width = width.get(0);
            windowContext.framebuffer.height = height.get(0);
        }
    }

    private void handleKey(int keyId, Key key) {
        windowContext.keyboard.setPressed(key, GLFW.glfwGetKey(windowHandle, keyId) == GLFW.GLFW_PRESS);
    }

    private void handleEitherKey(int firstKeyId, int secondKeyId, Key key) {
        windowContext.keyboard.setPressed(
                key,
                GLFW.glfwGetKey(windowHandle, firstKeyId) == GLFW.GLFW_PRESS
                        || GLFW.glfwGetKey(windowHandle, secondKeyId) == GLFW.GLFW_PRESS);
    }

    private static List<int[]> workAreas() {
        var areas = new ArrayList<int[]>();
        var monitors = GLFW.glfwGetMonitors();
        if (monitors == null) {
            return areas;
        }
        try (var stack = MemoryStack.stackPush()) {
            var x = stack.mallocInt(1);
            var y = stack.mallocInt(1);
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            for (var index = 0; index < monitors.limit(); index++) {
                GLFW.glfwGetMonitorWorkarea(monitors.get(index), x, y, width, height);
                if (width.get(0) > 0 && height.get(0) > 0) {
                    areas.add(new int[] {x.get(0), y.get(0), width.get(0), height.get(0)});
                }
            }
        }
        return areas;
    }
}

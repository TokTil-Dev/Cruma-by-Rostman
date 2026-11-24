package com.rost.cruma.viewer.system;

import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_FOCUSED;
import static org.lwjgl.glfw.GLFW.GLFW_HOVERED;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_PASSTHROUGH;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetWindowAttrib;
import static org.lwjgl.glfw.GLFW.glfwGetWindowContentScale;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowAttrib;

import com.rost.cruma.viewer.config.InterfaceScale;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiBackendFlags;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiViewportFlags;
import imgui.glfw.ImGuiImplGlfw;

final class SingleWindowImGuiGlfw extends ImGuiImplGlfw {
    private final boolean pollWindowHover;
    private final ImVec2 previousMousePosition = new ImVec2();
    private final ImVec2 displayValue = new ImVec2();
    private final double[] mouseX = new double[1];
    private final double[] mouseY = new double[1];
    private final int[] windowWidth = new int[1];
    private final int[] windowHeight = new int[1];
    private final int[] framebufferWidth = new int[1];
    private final int[] framebufferHeight = new int[1];
    private final float[] contentScaleX = new float[1];
    private final float[] contentScaleY = new float[1];
    private InterfaceScale interfaceScale = InterfaceScale.AUTOMATIC;
    private float factor = 1f;
    private float framebufferRatio = 1f;
    private boolean hoverCapabilityKnown;
    private boolean hoverCapability;

    SingleWindowImGuiGlfw() {
        this(false);
    }

    SingleWindowImGuiGlfw(boolean pollWindowHover) {
        this.pollWindowHover = pollWindowHover;
    }

    boolean pollsWindowHover() {
        return pollWindowHover;
    }

    void setInterfaceScale(InterfaceScale scale) {
        interfaceScale = scale;
    }

    float getFactor() {
        return factor;
    }

    float getPixelDensity(long window) {
        resolve(window);
        return factor * framebufferRatio;
    }

    @Override
    protected Data newData() {
        hoverCapabilityKnown = false;
        return new MouseData();
    }

    @Override
    public void newFrame() {
        var io = ImGui.getIO();
        float previous = factor;
        if (hasMultipleViewports()) {
            factor = 1f;
        } else {
            resolve(((MouseData) data).getWindow());
        }
        if (factor != previous) {
            io.getMousePos(previousMousePosition);
            if (previousMousePosition.x > -Float.MAX_VALUE / 2) {
                float x = previousMousePosition.x * previous / factor;
                float y = previousMousePosition.y * previous / factor;
                io.addMousePosEvent(x, y);
                ((MouseData) data).rememberPosition(x, y);
            }
        }
        super.newFrame();
        if (factor != 1f) {
            io.getDisplaySize(displayValue);
            io.setDisplaySize(displayValue.x / factor, displayValue.y / factor);
            io.getDisplayFramebufferScale(displayValue);
            io.setDisplayFramebufferScale(displayValue.x * factor, displayValue.y * factor);
        }
    }

    @Override
    public void cursorPosCallback(long window, double x, double y) {
        if (factor == 1f || hasMultipleViewports()) {
            super.cursorPosCallback(window, x, y);
            return;
        }
        var mouse = (MouseData) data;
        if (shouldChainCallback(window)) {
            mouse.chainCursorPosition(window, x, y);
        }
        float scaledX = (float) (x / factor);
        float scaledY = (float) (y / factor);
        ImGui.getIO().addMousePosEvent(scaledX, scaledY);
        mouse.rememberPosition(scaledX, scaledY);
    }

    @Override
    protected void updateMouseData() {
        var io = ImGui.getIO();
        var platform = ImGui.getPlatformIO();
        if (!hoverCapabilityKnown) {
            hoverCapability = io.hasBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport);
            hoverCapabilityKnown = true;
        }
        if (hasMultipleViewports()) {
            if (hoverCapability) {
                io.addBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport);
            }
            super.updateMouseData();
            return;
        }

        boolean reportHover = pollWindowHover && hoverCapability;
        if (reportHover) {
            io.addBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport);
        } else {
            io.removeBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport);
        }
        var viewport = platform.getViewports(0);
        var window = viewport.getPlatformHandle();
        var mouse = (MouseData) data;
        if (glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0) {
            if (io.getWantSetMousePos()) {
                io.getMousePos(previousMousePosition);
                glfwSetCursorPos(
                        window,
                        (previousMousePosition.x - viewport.getPosX()) * factor,
                        (previousMousePosition.y - viewport.getPosY()) * factor);
            }
            if (mouse.needsPositionFallback()) {
                glfwGetCursorPos(window, mouseX, mouseY);
                float x = (float) (mouseX[0] / factor);
                float y = (float) (mouseY[0] / factor);
                mouse.rememberPosition(x, y);
                io.addMousePosEvent(x, y);
            }
        }
        if (glfwHasMousePassthrough) {
            glfwSetWindowAttrib(
                    window,
                    GLFW_MOUSE_PASSTHROUGH,
                    viewport.hasFlags(ImGuiViewportFlags.NoInputs) ? GLFW_TRUE : GLFW_FALSE);
        }
        if (reportHover && (glfwHasMousePassthrough || glfwHasWindowHovered)) {
            io.addMouseViewportEvent(glfwGetWindowAttrib(window, GLFW_HOVERED) != 0 ? viewport.getID() : 0);
        }
    }

    private void resolve(long window) {
        glfwGetWindowSize(window, windowWidth, windowHeight);
        glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
        if (windowWidth[0] <= 0 || windowHeight[0] <= 0 || framebufferWidth[0] <= 0 || framebufferHeight[0] <= 0) {
            return;
        }
        glfwGetWindowContentScale(window, contentScaleX, contentScaleY);
        framebufferRatio =
                Math.max(framebufferWidth[0] / (float) windowWidth[0], framebufferHeight[0] / (float) windowHeight[0]);
        factor = interfaceScale.factor(Math.max(contentScaleX[0], contentScaleY[0]), framebufferRatio);
    }

    private static final class MouseData extends Data {
        long getWindow() {
            return window;
        }

        boolean needsPositionFallback() {
            return mouseWindow == -1;
        }

        void rememberPosition(float x, float y) {
            lastValidMousePos.set(x, y);
        }

        void chainCursorPosition(long target, double x, double y) {
            if (prevUserCallbackCursorPos != null) {
                prevUserCallbackCursorPos.invoke(target, x, y);
            }
        }
    }

    private static boolean hasMultipleViewports() {
        return ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)
                || ImGui.getPlatformIO().getViewportsSize() != 1;
    }
}

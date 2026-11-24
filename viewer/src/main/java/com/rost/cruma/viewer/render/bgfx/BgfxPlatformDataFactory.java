package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_NATIVE_WINDOW_HANDLE_TYPE_DEFAULT;
import static org.lwjgl.bgfx.BGFX.BGFX_NATIVE_WINDOW_HANDLE_TYPE_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_COCOA;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_NULL;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WIN32;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11;
import static org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow;
import static org.lwjgl.glfw.GLFWNativeWayland.glfwGetWaylandDisplay;
import static org.lwjgl.glfw.GLFWNativeWayland.glfwGetWaylandWindow;
import static org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window;
import static org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Display;
import static org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Window;

import org.lwjgl.bgfx.BGFXPlatformData;
import org.lwjgl.glfw.GLFW;

final class BgfxPlatformDataFactory {
    private BgfxPlatformDataFactory() {}

    static void populate(BGFXPlatformData data, long glfwWindow) {
        var platform = GLFW.glfwGetPlatform();
        data.set(0L, 0L, 0L, 0L, 0L, 0L, BGFX_NATIVE_WINDOW_HANDLE_TYPE_DEFAULT);
        switch (platform) {
            case GLFW_PLATFORM_NULL -> {}
            case GLFW_PLATFORM_COCOA -> data.nwh(require(glfwGetCocoaWindow(glfwWindow), "Cocoa NSWindow"));
            case GLFW_PLATFORM_WIN32 -> data.nwh(require(glfwGetWin32Window(glfwWindow), "Win32 HWND"));
            case GLFW_PLATFORM_X11 ->
                data.ndt(require(glfwGetX11Display(), "X11 Display"))
                        .nwh(require(glfwGetX11Window(glfwWindow), "X11 Window"));
            case GLFW_PLATFORM_WAYLAND ->
                data.ndt(require(glfwGetWaylandDisplay(), "Wayland display"))
                        .nwh(require(glfwGetWaylandWindow(glfwWindow), "Wayland surface"))
                        .type(BGFX_NATIVE_WINDOW_HANDLE_TYPE_WAYLAND);
            default -> throw new IllegalStateException("Unsupported GLFW platform: " + platform);
        }
    }

    private static long require(long handle, String label) {
        if (handle == 0L) {
            throw new IllegalStateException("GLFW returned a null " + label + " handle");
        }
        return handle;
    }
}

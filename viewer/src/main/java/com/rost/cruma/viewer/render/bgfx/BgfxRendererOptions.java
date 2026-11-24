package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_COUNT;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D11;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D12;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_METAL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.bgfx_get_supported_renderers;

import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Renderer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

public final class BgfxRendererOptions {
    private BgfxRendererOptions() {}

    public static List<String> available() {
        var supported = EnumSet.noneOf(Renderer.class);
        try (var stack = MemoryStack.stackPush()) {
            var types = stack.mallocInt(BGFX_RENDERER_TYPE_COUNT);
            int count = Byte.toUnsignedInt(bgfx_get_supported_renderers(types));
            for (int i = 0; i < count; i++) {
                Renderer renderer = switch (types.get(i)) {
                    case BGFX_RENDERER_TYPE_METAL -> Renderer.METAL;
                    case BGFX_RENDERER_TYPE_VULKAN -> Renderer.VULKAN;
                    case BGFX_RENDERER_TYPE_DIRECT3D11 -> Renderer.DIRECT3D11;
                    case BGFX_RENDERER_TYPE_DIRECT3D12 -> Renderer.DIRECT3D12;
                    default -> null;
                };
                if (renderer != null) {
                    supported.add(renderer);
                }
            }
        }
        return listChoices(Platform.get(), supported, BgfxShaderResources::hasBackend);
    }

    static List<String> listChoices(Platform platform, Set<Renderer> nativeBackends, Predicate<String> shaders) {
        var result = new ArrayList<String>();
        result.add("auto");
        for (var renderer : Renderer.values()) {
            boolean platformSupported = switch (renderer) {
                case AUTO, NOOP -> false;
                case METAL -> platform == Platform.MACOSX;
                case DIRECT3D11, DIRECT3D12 -> platform == Platform.WINDOWS;
                case VULKAN -> platform == Platform.WINDOWS || platform == Platform.LINUX;
            };
            String backend = switch (renderer) {
                case METAL -> "metal";
                case DIRECT3D11, DIRECT3D12 -> "dx11";
                case VULKAN -> "spirv";
                case AUTO, NOOP -> "";
            };
            if (platformSupported && nativeBackends.contains(renderer) && shaders.test(backend)) {
                result.add(renderer.name().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(result);
    }
}

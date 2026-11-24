package cruma.bgfx

object CrumaNatives {
    const val RAY_QUERY_LIBRARY = "libcruma_metal_ray_query.dylib"
    const val RAY_QUERY_RESOURCE = "native/macos/$RAY_QUERY_LIBRARY"
    const val METAL_FENCE_SYMBOL = "cruma_bgfx_metal_enqueue_shared_event_fence"
    const val METAL_PATCH = "cruma-metal-shared-event.patch"
    const val COPY_POOL_PATCH = "cruma-copy-pool.patch"
    const val VULKAN_MEMORY_PATCH = "cruma-vulkan-memory-fallback.patch"
    const val D3D12_STAGING_PATCH = "cruma-d3d12-staging-pool.patch"
    const val COPY_POOL_SYMBOL = "cruma_bgfx_set_copy_pool"
    const val VIDEO_MEMORY_FALLBACK_SYMBOL = "cruma_bgfx_video_memory_fallbacks"
    const val BGFX_WINDOWS_RESOURCE = "windows/x64/org/lwjgl/bgfx/bgfx.dll"
    const val BGFX_LINUX_RESOURCE = "linux/x64/org/lwjgl/bgfx/libbgfx.so"
    val BGFX_METAL_RESOURCE_PATHS = listOf(
        "macos/arm64/org/lwjgl/bgfx/libbgfx.dylib",
        "macos/x64/org/lwjgl/bgfx/libbgfx.dylib",
    )
}

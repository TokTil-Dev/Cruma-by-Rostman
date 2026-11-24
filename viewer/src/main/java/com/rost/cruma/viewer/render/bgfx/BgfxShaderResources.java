package com.rost.cruma.viewer.render.bgfx;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.lwjgl.system.Platform;

public final class BgfxShaderResources {
    private static final String MANIFEST_RESOURCE = "bgfx/shaders/manifest.properties";
    private static final List<String> TOOLCHAIN_KEYS = List.of(
            "lwjgl",
            "bgfx.api",
            "bgfx.revision",
            "bimg.revision",
            "bx.revision",
            "bgfx.cmake.revision",
            "shaderc",
            "shader.count",
            "targets");
    private static final List<String> SHADERS = List.of(
            "cs_buffer_copy",
            "cs_visibility_copy_depth",
            "cs_visibility_cull",
            "cs_visibility_downsample",
            "cs_visibility_finalize",
            "cs_visibility_order_count",
            "cs_visibility_order_move",
            "cs_visibility_reset",
            "fs_bloom_blur",
            "fs_bloom_extract",
            "fs_brand_crystal",
            "fs_brand_crystal_resolve",
            "fs_copy",
            "fs_fsr1_easu",
            "fs_loading_scene",
            "fs_loading_scene_shadow",
            "fs_fsr1_rcas",
            "fs_fx",
            "fs_fx_refraction",
            "fs_fx_refraction_region",
            "fs_imgui",
            "fs_post_composite",
            "fs_post_fxaa",
            "fs_post_scene",
            "fs_ray_visibility_temporal",
            "fs_scene_csg",
            "fs_scene_csg_depth_mrt",
            "fs_scene_csg_gpu",
            "fs_scene_csg_gpu_depth_mrt",
            "fs_scene_entity",
            "fs_scene_entity_depth_mrt",
            "fs_scene_entity_gpu",
            "fs_scene_entity_gpu_depth_mrt",
            "fs_scene_ordinary",
            "fs_scene_ordinary_depth_mrt",
            "fs_scene_ordinary_gpu",
            "fs_scene_ordinary_gpu_depth_mrt",
            "fs_scene_terrain",
            "fs_scene_terrain_depth_mrt",
            "fs_scene_water",
            "fs_projector_entity",
            "fs_projector_entity_depth_mrt",
            "fs_projector_terrain",
            "fs_projector_terrain_depth_mrt",
            "fs_sky_gradient",
            "fs_sky_mesh",
            "fs_sky_sprite",
            "fs_solid",
            "vs_brand_crystal",
            "vs_fullscreen",
            "vs_fx_billboard",
            "vs_fx_ribbon",
            "vs_imgui",
            "vs_scene_entity",
            "vs_scene_entity_blend",
            "vs_scene_entity_gpu",
            "vs_scene_water",
            "vs_scene_water_blend",
            "vs_scene_terrain",
            "vs_loading_scene",
            "vs_loading_scene_shadow",
            "vs_sky_gradient",
            "vs_sky_mesh",
            "vs_sky_sprite",
            "vs_smoke");

    private BgfxShaderResources() {}

    static boolean hasBackend(String backend) {
        var loader = BgfxShaderResources.class.getClassLoader();
        return SHADERS.stream()
                .allMatch(shader ->
                        loader.getResource("bgfx/shaders/release/" + backend + "/" + shader + ".bin") != null);
    }

    public static ByteBuffer load(BgfxCapabilities capabilities, String shaderName) {
        if (shaderName == null || shaderName.isBlank() || shaderName.indexOf('/') >= 0) {
            throw new IllegalArgumentException("shader name must be a non-empty basename");
        }
        var resourceName = resourceName(capabilities, shaderName);
        try (var input = BgfxShaderResources.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing compiled bgfx shader resource: " + resourceName);
            }
            var bytes = input.readAllBytes();
            return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load bgfx shader " + resourceName, e);
        }
    }

    static Map<String, Object> releaseManifest(BgfxCapabilities capabilities) {
        var hashes = new LinkedHashMap<String, String>();
        var packaged = packagedManifest();
        var backend = backendDirectory(capabilities.renderer());
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var shader : SHADERS) {
                var resource = resourceName(capabilities, shader);
                try (var input = BgfxShaderResources.class.getClassLoader().getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new IllegalStateException("Missing compiled bgfx shader resource: " + resource);
                    }
                    var actual = HexFormat.of().formatHex(digest.digest(input.readAllBytes()));
                    var expected =
                            packaged.properties.getProperty("shader.release." + backend + "." + shader + ".sha256");
                    if (!actual.equals(expected)) {
                        throw new IllegalStateException("Packaged shader/manifest mismatch for "
                                + backend
                                + "/"
                                + shader
                                + ": expected "
                                + expected
                                + ", got "
                                + actual);
                    }
                    hashes.put(shader, actual);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fingerprint bgfx shaders", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("variant", "release");
        result.put("backend", backend);
        result.put("manifest_sha256", packaged.sha256);
        result.put("toolchain", toolchainMetadata(packaged.properties));
        result.put("sha256", hashes);
        return result;
    }

    static Map<String, String> toolchainManifest() {
        return toolchainMetadata(packagedManifest().properties);
    }

    static String backendDirectory(String renderer) {
        if (renderer.equals("Metal")) {
            return "metal";
        }
        if (renderer.equals("Vulkan")) {
            return "spirv";
        }
        if (renderer.startsWith("Direct3D")) {
            return "dx11";
        }
        if (renderer.equals("Noop")) {
            return switch (Platform.get()) {
                case MACOSX -> "metal";
                case WINDOWS -> "dx11";
                default -> "spirv";
            };
        }
        throw new IllegalStateException("No compiled shader set for bgfx renderer " + renderer);
    }

    private record PackagedManifest(Properties properties, String sha256) {}

    private static PackagedManifest packagedManifest() {
        try (var input = BgfxShaderResources.class.getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing compiled bgfx shader manifest: " + MANIFEST_RESOURCE);
            }
            var bytes = input.readAllBytes();
            var properties = new Properties();
            properties.load(new ByteArrayInputStream(bytes));
            var sha256 = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new PackagedManifest(properties, sha256);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load bgfx shader manifest", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Map<String, String> toolchainMetadata(Properties properties) {
        var result = new LinkedHashMap<String, String>();
        for (var key : TOOLCHAIN_KEYS) {
            var value = properties.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Incomplete bgfx shader manifest: missing " + key);
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String resourceName(BgfxCapabilities capabilities, String shaderName) {
        return "bgfx/shaders/release/" + backendDirectory(capabilities.renderer()) + "/" + shaderName + ".bin";
    }
}

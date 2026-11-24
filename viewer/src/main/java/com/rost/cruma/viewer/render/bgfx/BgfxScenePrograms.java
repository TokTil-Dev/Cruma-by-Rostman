package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.ProgramFamily;
import java.util.ArrayList;
import java.util.List;

final class BgfxScenePrograms implements AutoCloseable {
    final BgfxShader entityVertexShader;
    final BgfxShader waterVertexShader;
    final BgfxShader gpuEntityVertexShader;
    final BgfxShader terrainVertexShader;
    final BgfxProgram ordinaryProgram;
    final BgfxProgram complexProgram;
    final BgfxProgram csgProgram;
    final BgfxProgram ordinaryDepthMrtProgram;
    final BgfxProgram complexDepthMrtProgram;
    final BgfxProgram csgDepthMrtProgram;
    final BgfxProgram gpuOrdinaryProgram;
    final BgfxProgram gpuComplexProgram;
    final BgfxProgram gpuCsgProgram;
    final BgfxProgram gpuOrdinaryDepthMrtProgram;
    final BgfxProgram gpuComplexDepthMrtProgram;
    final BgfxProgram gpuCsgDepthMrtProgram;
    final BgfxProgram waterProgram;
    final BgfxProgram waterDepthMrtProgram;
    final BgfxProgram waterReceiverMrtProgram;
    final BgfxProgram terrainProgram;
    final BgfxProgram terrainDepthMrtProgram;
    final BgfxProgram terrainOverlayProgram;
    final BgfxProgram terrainOverlayDepthMrtProgram;
    final MeshPrograms meshPrograms;

    final MeshPrograms frameBlendMeshPrograms;

    private final List<AutoCloseable> owned = new ArrayList<>(35);
    private boolean closed;

    BgfxScenePrograms(BgfxDevice device) {
        try {
            entityVertexShader = own(BgfxShader.create(
                    device.getResources(),
                    "scene/entity-shared-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_scene_entity")));
            ordinaryProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-ordinary",
                    entityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_ordinary")));
            complexProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-complex",
                    entityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_entity")));
            csgProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-csg",
                    entityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_csg")));
            ordinaryDepthMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-ordinary-depth-mrt",
                    entityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_ordinary_depth_mrt")));
            complexDepthMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-complex-depth-mrt",
                    entityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_entity_depth_mrt")));
            csgDepthMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-csg-depth-mrt",
                    entityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_csg_depth_mrt")));
            gpuEntityVertexShader = own(BgfxShader.create(
                    device.getResources(),
                    "scene/entity-gpu-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_scene_entity_gpu")));
            gpuOrdinaryProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-gpu-ordinary",
                    gpuEntityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_ordinary_gpu")));
            gpuComplexProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-gpu-complex",
                    gpuEntityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_entity_gpu")));
            gpuCsgProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-gpu-csg",
                    gpuEntityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_csg_gpu")));
            gpuOrdinaryDepthMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-gpu-ordinary-depth-mrt",
                    gpuEntityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_ordinary_gpu_depth_mrt")));
            gpuComplexDepthMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-gpu-complex-depth-mrt",
                    gpuEntityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_entity_gpu_depth_mrt")));
            gpuCsgDepthMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-gpu-csg-depth-mrt",
                    gpuEntityVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_csg_gpu_depth_mrt")));
            waterVertexShader = own(BgfxShader.create(
                    device.getResources(),
                    "scene/water-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_scene_water")));
            waterProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-water",
                    waterVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_water")));
            waterDepthMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-water-depth-mrt",
                    waterVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_water_depth_mrt")));
            waterReceiverMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/entity-water-receiver-mrt",
                    waterVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_water_receiver_mrt")));
            terrainVertexShader = own(BgfxShader.create(
                    device.getResources(),
                    "scene/terrain-shared-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_scene_terrain")));
            terrainProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/terrain",
                    terrainVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_terrain")));
            terrainDepthMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/terrain-depth-mrt",
                    terrainVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_terrain_depth_mrt")));
            terrainOverlayProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/terrain-overlay",
                    terrainVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_terrain_overlay")));
            terrainOverlayDepthMrtProgram = own(BgfxProgram.graphics(
                    device.getResources(),
                    "scene/terrain-overlay-depth-mrt",
                    terrainVertexShader,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_scene_terrain_overlay_depth_mrt")));
            meshPrograms = new MeshPrograms(
                    ordinaryProgram,
                    complexProgram,
                    csgProgram,
                    waterProgram,
                    ordinaryDepthMrtProgram,
                    complexDepthMrtProgram,
                    csgDepthMrtProgram,
                    waterDepthMrtProgram);
            var blendEntityVertexShader = own(BgfxShader.create(
                    device.getResources(),
                    "scene/entity-blend-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_scene_entity_blend")));
            var blendWaterVertexShader = own(BgfxShader.create(
                    device.getResources(),
                    "scene/water-blend-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_scene_water_blend")));
            frameBlendMeshPrograms = new MeshPrograms(
                    own(BgfxProgram.graphics(
                            device.getResources(),
                            "scene/entity-blend-ordinary",
                            blendEntityVertexShader,
                            BgfxShaderResources.load(device.getCapabilities(), "fs_scene_ordinary"))),
                    own(BgfxProgram.graphics(
                            device.getResources(),
                            "scene/entity-blend-complex",
                            blendEntityVertexShader,
                            BgfxShaderResources.load(device.getCapabilities(), "fs_scene_entity"))),
                    own(BgfxProgram.graphics(
                            device.getResources(),
                            "scene/entity-blend-csg",
                            blendEntityVertexShader,
                            BgfxShaderResources.load(device.getCapabilities(), "fs_scene_csg"))),
                    own(BgfxProgram.graphics(
                            device.getResources(),
                            "scene/entity-blend-water",
                            blendWaterVertexShader,
                            BgfxShaderResources.load(device.getCapabilities(), "fs_scene_water"))),
                    own(BgfxProgram.graphics(
                            device.getResources(),
                            "scene/entity-blend-ordinary-depth-mrt",
                            blendEntityVertexShader,
                            BgfxShaderResources.load(device.getCapabilities(), "fs_scene_ordinary_depth_mrt"))),
                    own(BgfxProgram.graphics(
                            device.getResources(),
                            "scene/entity-blend-complex-depth-mrt",
                            blendEntityVertexShader,
                            BgfxShaderResources.load(device.getCapabilities(), "fs_scene_entity_depth_mrt"))),
                    own(BgfxProgram.graphics(
                            device.getResources(),
                            "scene/entity-blend-csg-depth-mrt",
                            blendEntityVertexShader,
                            BgfxShaderResources.load(device.getCapabilities(), "fs_scene_csg_depth_mrt"))),
                    own(BgfxProgram.graphics(
                            device.getResources(),
                            "scene/entity-blend-water-depth-mrt",
                            blendWaterVertexShader,
                            BgfxShaderResources.load(device.getCapabilities(), "fs_scene_water_depth_mrt"))));
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var index = owned.size() - 1; index >= 0; index--) {
            try {
                owned.get(index).close();
            } catch (Exception closeFailure) {
                var next = closeFailure instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("failed to close bgfx scene program", closeFailure);
                if (failure == null) {
                    failure = next;
                } else {
                    failure.addSuppressed(next);
                }
            }
        }
        owned.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private <T extends AutoCloseable> T own(T resource) {
        owned.add(resource);
        return resource;
    }

    record MeshPrograms(
            BgfxProgram ordinary,
            BgfxProgram complex,
            BgfxProgram csg,
            BgfxProgram water,
            BgfxProgram ordinaryDepthMrt,
            BgfxProgram complexDepthMrt,
            BgfxProgram csgDepthMrt,
            BgfxProgram waterDepthMrt) {
        BgfxProgram program(ProgramFamily family, boolean depthMrt) {
            return switch (family) {
                case ORDINARY -> depthMrt ? ordinaryDepthMrt : ordinary;
                case COMPLEX -> depthMrt ? complexDepthMrt : complex;
                case CSG -> depthMrt ? csgDepthMrt : csg;
                case WATER -> depthMrt ? waterDepthMrt : water;
            };
        }
    }
}

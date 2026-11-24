package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.USphere;
import com.rost.cruma.core.world.Entity.EntityMesh;
import glm_.vec3.Vec3;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public record NpcModel(
        String mesh,
        EntityMesh entityMesh,
        String pose,
        float lowest,
        Map<String, Vec3> bones,
        List<String> boneNames,
        @Nullable USphere litSphere,
        Map<Integer, NpcPartPasses> surfacePasses) {
    public NpcModel {
        bones = Map.copyOf(bones);
        boneNames = List.copyOf(boneNames);
        surfacePasses = Map.copyOf(surfacePasses);
    }

    public NpcModel(
            String mesh,
            EntityMesh entityMesh,
            String pose,
            float lowest,
            Map<String, Vec3> bones,
            List<String> boneNames,
            @Nullable USphere litSphere) {
        this(mesh, entityMesh, pose, lowest, bones, boneNames, litSphere, Map.of());
    }

    public NpcModel(
            String mesh,
            EntityMesh entityMesh,
            String pose,
            float lowest,
            Map<String, Vec3> bones,
            List<String> boneNames) {
        this(mesh, entityMesh, pose, lowest, bones, boneNames, null);
    }

    public NpcModel(String mesh, EntityMesh entityMesh, String pose, float lowest, Map<String, Vec3> bones) {
        this(mesh, entityMesh, pose, lowest, bones, List.of());
    }

    public NpcModel(String mesh, EntityMesh entityMesh, String pose, float lowest) {
        this(mesh, entityMesh, pose, lowest, Map.of());
    }
}

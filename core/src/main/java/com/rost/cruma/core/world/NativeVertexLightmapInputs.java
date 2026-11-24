package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UStaticMeshInstance;
import com.rost.cruma.core.unreal.UStaticMeshInstance.LightInfo;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record NativeVertexLightmapInputs(
        String instanceId,
        List<Reference> sceneLights,
        List<Reference> environmentLights,
        Vec3 drawScale,
        float intensity,
        boolean sunAffected) {
    public NativeVertexLightmapInputs {
        sceneLights = List.copyOf(sceneLights);
        environmentLights = List.copyOf(environmentLights);
        drawScale = new Vec3(drawScale);
    }

    public record Reference(
            boolean actorReference, @Nullable String actorId, byte[] visibility, int applied) {
        public Reference {
            visibility = visibility.clone();
        }
    }

    static NativeVertexLightmapInputs capture(UStaticMeshInstance instance, StaticMeshActor actor) {
        return new NativeVertexLightmapInputs(
                instance.getFullName(),
                capture(instance.getSceneLights()),
                capture(instance.getEnvironmentLights()),
                actor.getDrawScale3d().times(actor.getDrawScale()),
                actor.getLightIntensity(),
                actor.isSunAffect());
    }

    private static List<Reference> capture(LightInfo[] lights) {
        var references = new ArrayList<Reference>(lights.length);
        for (var light : lights) {
            var flags = light.getVertexFlags();
            var mask = new byte[flags.length];
            for (var index = 0; index < flags.length; index++) {
                mask[index] = (byte) flags[index];
            }
            references.add(
                    new Reference(light.getLightIndex() != 0, light.getLightName(), mask, light.getAppliedValue()));
        }
        return references;
    }
}

package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.viewer.render.ParticleEmitter;
import glm_.vec3.Vec3;
import java.util.List;
import java.util.stream.Collectors;

public record DebugSelection(DebugSceneObject sceneObject, ParticleEmitter emitter, float hitDistance) {
    public DebugSelection {
        if ((sceneObject == null) == (emitter == null)) {
            throw new IllegalArgumentException("one selection source required");
        }
    }

    public static DebugSelection scene(DebugSceneObject object, float distance) {
        return new DebugSelection(object, null, distance);
    }

    public static DebugSelection fx(ParticleEmitter emitter, float distance) {
        return new DebugSelection(null, emitter, distance);
    }

    public static String shortName(String value) {
        var end = Math.max(value.lastIndexOf('.'), value.lastIndexOf('/'));
        return value.substring(end + 1);
    }

    public String key() {
        return emitter == null
                ? sceneObject.key()
                : "FX/" + emitter.getRenderActorName() + "/" + emitter.getRenderChildOrder();
    }

    public String actor() {
        return emitter == null ? sceneObject.actorName() : emitter.getRenderActorName();
    }

    public String child() {
        return emitter == null
                ? "Not applicable"
                : DebugMaterialSlot.orUnavailable(emitter.getDiagnosticSource().child());
    }

    public String owner() {
        return emitter == null ? sceneObject.owner() : emitter.getSceneMap();
    }

    public String assetName() {
        if (emitter == null) {
            return sceneObject.assetName();
        }
        var asset = emitter.getDiagnosticSource().asset();
        return DebugMaterialSlot.orUnavailable(asset == null ? emitter.getDebugName() : asset);
    }

    public String shortName() {
        return emitter == null ? sceneObject.shortName() : shortName(assetName());
    }

    public String typeName() {
        return emitter != null
                ? emitter.getDiagnosticSource().type()
                : switch (sceneObject.category()) {
                    case CSG -> "CSG surface";
                    case STATIC_MESH -> "Static mesh";
                    case TERRAIN -> "Terrain";
                };
    }

    public List<DebugMaterialSlot> materials() {
        return emitter == null
                ? sceneObject.materials()
                : emitter.getDiagnosticSource().materials();
    }

    public boolean hasAnimation() {
        return emitter != null
                || sceneObject.actorAnimated()
                || materials().stream().anyMatch(DebugMaterialSlot::animated);
    }

    public String materialName() {
        return emitter == null
                ? sceneObject.materialName()
                : materials().isEmpty()
                        ? "Unavailable"
                        : materials().stream()
                                .map(DebugMaterialSlot::name)
                                .distinct()
                                .collect(Collectors.joining(", "));
    }

    public String fullName() {
        return emitter == null
                ? sceneObject.fullName()
                : actor() + " / " + child() + " [child " + emitter.getRenderChildOrder() + "] / " + assetName();
    }

    public int surfaceOrdinal() {
        return emitter == null ? sceneObject.surfaceOrdinal() : -1;
    }

    public int instanceOrdinal() {
        return emitter == null ? sceneObject.instanceOrdinal() : -1;
    }

    public String lighting() {
        return emitter == null ? sceneObject.lighting() : null;
    }

    public Bounds3f bounds() {
        if (emitter == null) {
            return sceneObject.bounds();
        }
        var box = new Box();
        if (emitter.computeWorldBounds(box)) {
            var min = box.getMin();
            var max = box.getMax();
            return new Bounds3f(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        }
        var p = emitter.getActorPos();
        return new Bounds3f(p.getX() - 8, p.getY() - 8, p.getZ() - 8, p.getX() + 8, p.getY() + 8, p.getZ() + 8);
    }

    public Vec3 center() {
        var b = bounds();
        return new Vec3(b.centerX(), b.centerY(), b.centerZ());
    }

    public Vec3 min() {
        var b = bounds();
        return new Vec3(b.minX(), b.minY(), b.minZ());
    }

    public Vec3 max() {
        var b = bounds();
        return new Vec3(b.maxX(), b.maxY(), b.maxZ());
    }
}

package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.primitives.UBox;
import com.rost.cruma.core.world.BspVisibilityVolumes.View;
import com.rost.cruma.core.world.NativeLightScene;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Primitive;
import glm_.vec3.Vec3;
import java.util.IdentityHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class BgfxSceneZones {
    private static final long EVERY_ZONE = -1L;
    private static final long SOLID_ZONE = 1L;

    private final Map<BgfxSceneObject, long[]> instanceZones = new IdentityHashMap<>();
    private final Map<BgfxSceneObject, long[]> partZones = new IdentityHashMap<>();
    private @Nullable Reach reach;
    private int[] nodeStack = new int[0];

    record Reach(String owner, NativeLightScene scene, View view) {}

    void update(@Nullable Reach next) {
        if (next == null) {
            instanceZones.clear();
            partZones.clear();
            nodeStack = new int[0];
        } else if (reach == null || reach.scene() != next.scene()) {
            instanceZones.clear();
            partZones.clear();
            nodeStack = new int[next.scene().getNodeCount()];
        }
        reach = next;
    }

    boolean isActive() {
        return reach != null;
    }

    boolean draws(BgfxSceneObject object, Primitive primitive, int ordinal) {
        var now = reach;
        if (now == null) {
            return true;
        }
        if (!object.getOwner().equalsIgnoreCase(now.owner())
                || object.getGeometry().getKind() == Kind.TERRAIN) {
            return false;
        }
        if (primitive.zones() != EVERY_ZONE) {
            return primitive.zones() == SOLID_ZONE || shows(primitive.zones(), object.getPrimitiveBounds(ordinal));
        }
        if (!object.getSourceParts().isEmpty()) {
            return true;
        }
        for (var instance = 0; instance < object.getInstanceCount(); instance++) {
            if (drawsInstance(object, instance)) {
                return true;
            }
        }
        return false;
    }

    boolean drawsInstance(BgfxSceneObject object, int instance) {
        var zones = instanceZones.computeIfAbsent(object, this::readInstances);
        return shows(zones[instance], object.getInstanceBounds(instance));
    }

    boolean drawsPart(BgfxSceneObject object, int part) {
        var zones = partZones.computeIfAbsent(object, this::readParts);
        return shows(zones[part], object.getSourceParts().get(part).bounds());
    }

    boolean drawsEmitter(ParticleEmitter emitter) {
        var now = reach;
        if (now == null) {
            return true;
        }
        var box = new Box();
        if (!emitter.computeWorldBounds(box)) {
            var actor = emitter.getActorPos();
            box = new Box(
                    new Vec3(actor.getX() - 1, actor.getY() - 1, actor.getZ() - 1),
                    new Vec3(actor.getX() + 1, actor.getY() + 1, actor.getZ() + 1));
        }
        var bounds =
                new Bounds3f(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
        return shows(now.scene().getZoneMask(box, nodeStack), bounds);
    }

    boolean drawsPoint(String owner, Vec3 point) {
        var now = reach;
        if (now == null) {
            return true;
        }
        if (!owner.equalsIgnoreCase(now.owner())) {
            return false;
        }
        var box = new Box(
                new Vec3(point.getX() - 1, point.getY() - 1, point.getZ() - 1),
                new Vec3(point.getX() + 1, point.getY() + 1, point.getZ() + 1));
        var bounds =
                new Bounds3f(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
        return shows(now.scene().getZoneMask(box, nodeStack), bounds);
    }

    private long[] readInstances(BgfxSceneObject object) {
        var zones = new long[object.getInstanceCount()];
        for (var instance = 0; instance < zones.length; instance++) {
            zones[instance] = zonesOf(object.getInstanceBounds(instance));
        }
        return zones;
    }

    private long[] readParts(BgfxSceneObject object) {
        var zones = new long[object.getSourceParts().size()];
        for (var part = 0; part < zones.length; part++) {
            zones[part] = zonesOf(object.getSourceParts().get(part).bounds());
        }
        return zones;
    }

    private long zonesOf(Bounds3f bounds) {
        var box = new Box(
                new Vec3(bounds.minX(), bounds.minY(), bounds.minZ()),
                new Vec3(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
        return reach.scene().getZoneMask(box, nodeStack);
    }

    private boolean shows(long zones, Bounds3f bounds) {
        var view = reach.view();
        if ((zones & view.getVisibleZoneMask()) == 0L) {
            return false;
        }
        var box = new UBox(
                new Vec3(bounds.minX(), bounds.minY(), bounds.minZ()),
                new Vec3(bounds.maxX(), bounds.maxY(), bounds.maxZ()),
                true);
        if ((zones & 1L << view.getViewZone()) != 0L) {
            return true;
        }
        for (var zone : view.getVisibleZones()) {
            if ((zones & 1L << zone) != 0L && view.intersectsBox(box, zone)) {
                return true;
            }
        }
        return false;
    }
}

package com.rost.cruma.core.world;

import com.rost.cruma.core.world.BspVisibilityVolumes.View;
import com.rost.cruma.core.world.BspVisibilityVolumes.Volume;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;

public final class NativePortalVisibility {
    private NativePortalVisibility() {}

    public record Zone(Object identity, boolean blocksPortals, List<Object> excludedActors) {}

    public record Surface(
            Vec4 nodePlane,
            int backZone,
            int frontZone,
            Vec4 sphere,
            Vec3 worldNormal,
            List<Vec3> worldVertices,
            boolean polygonAvailable,
            boolean alreadyProcessed) {}

    public static void visit(View view, Vec3 origin, Surface surface, IntFunction<Zone> zones) {
        var p = surface.nodePlane();
        var distance = origin.getY() * p.getY() + origin.getX() * p.getX() + origin.getZ() * p.getZ() - p.getW();
        var front = distance > 0f;
        var source = front ? surface.frontZone() : surface.backZone();
        var destination = front ? surface.backZone() : surface.frontZone();
        var visibilityZone = view.isZoned() ? source : 0;
        if (!view.intersectsSphere(surface.sphere(), visibilityZone)
                || !view.isZoned()
                || !surface.polygonAvailable()
                || surface.alreadyProcessed()
                || source == destination) {
            return;
        }
        var currentZone = zones.apply(view.getViewZone());
        var otherZone = zones.apply(destination);
        if (currentZone.blocksPortals()
                || otherZone.blocksPortals()
                || currentZone.excludedActors().stream().anyMatch(actor -> actor == otherZone.identity())) {
            return;
        }
        var normal = new Vec3(surface.worldNormal());
        var vertices = new ArrayList<>(surface.worldVertices());
        if (!front) {
            normal = normal.negate();
            Collections.reverse(vertices);
        }
        var originalArea = NativePortalVolume.area(vertices);
        for (var volume : view.getExcludingVolumes(source)) {
            var clipped = NativePortalVolume.clip(vertices, planesOf(volume));
            var area = NativePortalVolume.area(clipped);
            if (originalArea <= area) {
                return;
            }
            if (area > 0f) {
                view.prependExcluding(destination, volume);
            }
        }
        for (var volume : view.getIncludingVolumes(source)) {
            if (distance > 1f || distance < -1f) {
                var clipped = NativePortalVolume.clip(vertices, planesOf(volume));
                if (clipped.isEmpty()) {
                    continue;
                }
                view.prependIncluding(destination, new Volume(NativePortalVolume.planes(normal, clipped, origin)));
            } else {
                view.prependIncluding(destination, volume);
            }
        }
        view.activateZone(destination);
    }

    private static List<Vec4> planesOf(Volume volume) {
        var result = new ArrayList<Vec4>(volume.getPlaneCount());
        for (var i = 0; i < volume.getPlaneCount(); i++) {
            result.add(volume.getPlane(i));
        }
        return result;
    }
}

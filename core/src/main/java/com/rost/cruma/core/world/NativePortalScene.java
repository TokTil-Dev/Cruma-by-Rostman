package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.Bsp.BspPolygonFlags;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.BrushActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UObject;
import com.rost.cruma.core.world.NativePortalVisibility.Surface;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class NativePortalScene {
    private static final NativePortalScene EMPTY = capture(null, null);
    private final Zone[] zones;
    private final Portal[] portals;

    private NativePortalScene(Zone[] zones, Portal[] portals) {
        this.zones = zones;
        this.portals = portals;
    }

    public record ActorIdentity(String name) {}

    public record Zone(boolean ordinary, NativePortalVisibility.Zone visibility) {}

    public record Polygon(Vec3 normal, List<Vec3> vertices) {}

    public record Portal(
            int nodeIndex,
            int surfaceIndex,
            int brushPolygonIndex,
            @Nullable String brushActor,
            long flags,
            Surface surface) {}

    public static NativePortalScene getEmpty() {
        return EMPTY;
    }

    public static NativePortalScene capture(@Nullable ULevel level, @Nullable UModel model) {
        var info = level == null ? null : level.getLevelInfo();
        var identities = new IdentityHashMap<UObject, ActorIdentity>();
        var nativeZones = model == null ? null : model.getZones();
        var zones = new Zone[64];
        for (var i = 0; i < zones.length; i++) {
            UObject actor = nativeZones != null && i < nativeZones.length
                    ? nativeZones[i].getZoneActor().get()
                    : null;
            if (actor == null) {
                actor = info;
            }
            var token = identityOf(identities, actor);
            var exclusions = new ArrayList<Object>();
            var lonely = false;
            if (actor instanceof ZoneInfoActor zone) {
                lonely = zone.isLonelyZone();
                for (var excluded : zone.getManualExcludes()) {
                    exclusions.add(identityOf(identities, excluded));
                }
            }
            zones[i] = new Zone(
                    actor instanceof ZoneInfoActor,
                    new NativePortalVisibility.Zone(token, lonely, Collections.unmodifiableList(exclusions)));
        }
        var nodes = model == null ? null : model.getNodes();
        var surfaces = model == null ? null : model.getSurfaces();
        var portals = new Portal[nodes == null ? 0 : nodes.length];
        if (nodes != null && surfaces != null) {
            for (var n = 0; n < nodes.length; n++) {
                var node = nodes[n];
                var surfaceIndex = node.getSurfaceIndex();
                if (surfaceIndex < 0 || surfaceIndex >= surfaces.length) {
                    continue;
                }
                var surface = surfaces[surfaceIndex];
                if ((surface.getPolygonFlags() & BspPolygonFlags.PF_Portal) == 0) {
                    continue;
                }
                var index = surface.getBrushPolygonIndex();
                var actor =
                        surface.getActor().isNull() ? null : surface.getActor().get();
                Polygon polygon = null;
                if (actor instanceof BrushActor brush) {
                    var originalModel = brush.getBrush().get();
                    var polys = originalModel == null
                            ? null
                            : originalModel.getPolys().get();
                    if (polys != null
                            && index >= 0
                            && index < polys.getPolygons().size()) {
                        var original = polys.getPolygons().get(index);
                        var matrix = BrushTransforms.actorMatrix(
                                brush.getLocation(), brush.getPrePivot(), brush.getRotation(), brush.getScale());
                        polygon = transformPolygon(original.normal(), original.vertices(), matrix);
                    }
                }
                var data = new Surface(
                        new Vec4(node.getPlane()),
                        node.getZone()[0],
                        node.getZone()[1],
                        new Vec4(node.getExclusiveSphereBound()),
                        polygon == null ? new Vec3(0) : polygon.normal(),
                        polygon == null ? List.of() : polygon.vertices(),
                        polygon != null,
                        false);
                portals[n] = new Portal(
                        n,
                        surfaceIndex,
                        index,
                        actor == null ? null : actor.getFullName(),
                        surface.getPolygonFlags(),
                        data);
            }
        }
        return new NativePortalScene(zones, portals);
    }

    public static Polygon transformPolygon(Vec3 normal, List<Vec3> vertices, Mat4 matrix) {
        var m = matrix.to(new float[16]);
        var transformed = new ArrayList<Vec3>(vertices.size());
        for (var vertex : vertices) {
            transformed.add(transform(vertex, m, false));
        }
        return new Polygon(transform(normal, m, true), List.copyOf(transformed));
    }

    public Zone getZone(int index) {
        return zones[index];
    }

    public int getNodeCount() {
        return portals.length;
    }

    public @Nullable Portal getPortal(int nodeIndex) {
        return portals[nodeIndex];
    }

    private static @Nullable ActorIdentity identityOf(
            IdentityHashMap<UObject, ActorIdentity> identities, @Nullable UObject actor) {
        return actor == null
                ? null
                : identities.computeIfAbsent(actor, value -> new ActorIdentity(value.getFullName()));
    }

    private static Vec3 transform(Vec3 v, float[] m, boolean normal) {
        return new Vec3(
                (m[0] * v.getX() + m[4] * v.getY()) + m[8] * v.getZ() + (normal ? m[12] * 0f : m[12]),
                (normal ? m[5] * v.getY() + m[1] * v.getX() : m[1] * v.getX() + m[5] * v.getY())
                        + m[9] * v.getZ()
                        + (normal ? m[13] * 0f : m[13]),
                (normal ? m[6] * v.getY() + m[2] * v.getX() : m[2] * v.getX() + m[6] * v.getY())
                        + m[10] * v.getZ()
                        + (normal ? m[14] * 0f : m[14]));
    }
}

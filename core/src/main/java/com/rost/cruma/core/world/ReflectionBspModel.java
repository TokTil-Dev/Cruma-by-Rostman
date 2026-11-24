package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Frustum;
import com.rost.cruma.core.unreal.Bsp.BspPolygonFlags;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.primitives.UColor;
import com.rost.cruma.core.world.Entity.DistanceFog;
import glm_.vec3.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class ReflectionBspModel {
    private final String id;
    private final float[] planes;
    private final int[] front;
    private final int[] back;
    private final int[] frontZone;
    private final int[] backZone;
    private final long[] connectivity;
    private final long[] zoneMask;
    private final int[] planeLink;
    private final int[] renderBound;
    private final int[] surfaceIndex;
    private final long[] surfaceFlags;
    private final float[] exclusiveSphere;
    private final float[] bounds;
    private final boolean[] validBound;
    private final boolean nativeVisibilityData;
    private final ZoneFog[] zoneFog;
    private final String[] zoneNames;
    private final int[] zoneStarts;
    private final String map;

    public ReflectionBspModel(UModel model) {
        id = model.getFullName();
        map = ZoneStateChoice.key(ZoneStateChoice.mapOf(id));
        var modelZones = model.getZones();
        zoneFog = new ZoneFog[modelZones == null ? 0 : modelZones.length];
        zoneNames = new String[zoneFog.length];
        zoneStarts = fill(zoneFog.length, ZoneRender.NO_ZONE_STATES);
        for (var slot = 0; slot < zoneFog.length; slot++) {
            ZoneInfoActor zone;
            try {
                zone = modelZones[slot].getZoneActor().get();
            } catch (RuntimeException e) {
                zone = null;
            }
            if (zone == null) {
                continue;
            }
            var volumeFallbackFog =
                    fogOf(zone.getDistanceFogColor(), zone.getDistanceFogStart(), zone.getDistanceFogEnd());
            var fog = zone.isDistanceFog() && zone.getDistanceFogEnd() > zone.getDistanceFogStart()
                    ? volumeFallbackFog
                    : null;
            var stateFogs = new LinkedHashMap<Integer, DistanceFog>();
            for (var entry : zone.getStateFogs()) {
                stateFogs.putIfAbsent(entry.state(), fogOf(entry.color(), entry.start(), entry.end()));
            }
            zoneFog[slot] = new ZoneFog(
                    fog,
                    zone.isTerrainZone(),
                    volumeFallbackFog,
                    zone.isSunAffect(),
                    zone.isDistanceFog(),
                    zone.getDefaultZoneState(),
                    stateFogs);
            zoneNames[slot] = zone.getFullName();
            if (zone.isUseZoneState()) {
                zoneStarts[slot] = zone.getCurZoneState();
            }
        }
        var nodes = model.getNodes();
        planes = new float[nodes.length * 4];
        front = new int[nodes.length];
        back = new int[nodes.length];
        frontZone = new int[nodes.length];
        backZone = new int[nodes.length];
        zoneMask = new long[nodes.length];
        planeLink = new int[nodes.length];
        renderBound = new int[nodes.length];
        surfaceIndex = new int[nodes.length];
        exclusiveSphere = new float[nodes.length * 4];
        for (int i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var plane = node.getPlane();
            var p = i * 4;
            planes[p] = plane.getX();
            planes[p + 1] = plane.getY();
            planes[p + 2] = plane.getZ();
            planes[p + 3] = plane.getW();
            front[i] = node.getFrontIndex();
            back[i] = node.getBackIndex();
            zoneMask[i] = node.getZoneMask();
            planeLink[i] = node.getPlaneIndex();
            renderBound[i] = node.getRenderBound();
            surfaceIndex[i] = node.getSurfaceIndex();
            var sphere = node.getExclusiveSphereBound();
            exclusiveSphere[p] = sphere.getX();
            exclusiveSphere[p + 1] = sphere.getY();
            exclusiveSphere[p + 2] = sphere.getZ();
            exclusiveSphere[p + 3] = sphere.getW();
            var zones = node.getZone();
            backZone[i] = zones != null && zones.length > 0 ? zones[0] : -1;
            frontZone[i] = zones != null && zones.length > 1 ? zones[1] : -1;
        }
        var surfaces = model.getSurfaces();
        surfaceFlags = surfaces == null ? new long[0] : new long[surfaces.length];
        for (int i = 0; i < surfaceFlags.length; i++) {
            surfaceFlags[i] = surfaces[i].getPolygonFlags();
        }
        var modelBounds = model.getBounds();
        bounds = modelBounds == null ? new float[0] : new float[modelBounds.length * 6];
        validBound = modelBounds == null ? new boolean[0] : new boolean[modelBounds.length];
        if (modelBounds != null) {
            for (int i = 0; i < modelBounds.length; i++) {
                var bound = modelBounds[i];
                if (bound == null || !bound.isValid()) {
                    continue;
                }
                validBound[i] = true;
                var b = i * 6;
                bounds[b] = bound.min().getX();
                bounds[b + 1] = bound.min().getY();
                bounds[b + 2] = bound.min().getZ();
                bounds[b + 3] = bound.max().getX();
                bounds[b + 4] = bound.max().getY();
                bounds[b + 5] = bound.max().getZ();
            }
        }
        var zones = model.getZones();
        connectivity = zones == null ? new long[0] : new long[zones.length];
        for (int i = 0; i < connectivity.length; i++) {
            connectivity[i] = zones[i].getConnectivity();
        }
        nativeVisibilityData = nodes.length > 0 && surfaces != null;
    }

    public ReflectionBspModel(
            String id, float[] planes, int[] front, int[] back, int[] frontZone, int[] backZone, long[] connectivity) {
        this.zoneFog = new ZoneFog[0];
        this.zoneNames = new String[0];
        this.zoneStarts = new int[0];
        this.id = id;
        map = ZoneStateChoice.key(ZoneStateChoice.mapOf(id));
        this.planes = planes.clone();
        this.front = front.clone();
        this.back = back.clone();
        this.frontZone = frontZone.clone();
        this.backZone = backZone.clone();
        this.connectivity = connectivity.clone();
        zoneMask = new long[front.length];
        planeLink = fill(front.length, -1);
        renderBound = fill(front.length, -1);
        surfaceIndex = fill(front.length, -1);
        surfaceFlags = new long[0];
        exclusiveSphere = new float[front.length * 4];
        bounds = new float[0];
        validBound = new boolean[0];
        nativeVisibilityData = false;
    }

    public String getId() {
        return id;
    }

    public String getMap() {
        return map;
    }

    public @Nullable ZoneFog getFogAt(Vec3 point) {
        var slot = pointRegion(point);
        return slot >= 0 && slot < zoneFog.length ? zoneFog[slot] : null;
    }

    public @Nullable ZoneFog getFogAt(Vec3 point, ZoneStateChoice choice) {
        var zone = getFogAt(point);
        return zone == null ? null : zone.inState(choice.getState(map, MapEventState.AS_THE_MAP));
    }

    public int getZoneStartAt(Vec3 point) {
        var slot = pointRegion(point);
        return slot >= 0 && slot < zoneStarts.length ? zoneStarts[slot] : ZoneRender.NO_ZONE_STATES;
    }

    public List<ZoneStateFog> getZoneStateFogs() {
        var fogs = new ArrayList<ZoneStateFog>();
        for (var slot = 0; slot < zoneFog.length; slot++) {
            var zone = zoneFog[slot];
            if (zone == null || zone.zoneDefault() == 0 || zone.stateFogs().isEmpty()) {
                continue;
            }
            var name = zoneNames[slot];
            var dot = name.indexOf('.');
            var map = dot > 0 ? name.substring(0, dot) : name;
            for (var entry : zone.stateFogs().entrySet()) {
                fogs.add(new ZoneStateFog(name, map, entry.getKey(), zone.zoneDefault(), entry.getValue()));
            }
        }
        return List.copyOf(fogs);
    }

    public record ZoneFog(
            @Nullable DistanceFog fog,
            boolean terrainZone,
            DistanceFog volumeFallbackFog,
            boolean sunAffected,
            boolean distanceFog,
            int zoneDefault,
            Map<Integer, DistanceFog> stateFogs) {
        public ZoneFog {
            stateFogs = stateFogs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stateFogs));
        }

        public ZoneFog(
                @Nullable DistanceFog fog, boolean terrainZone, DistanceFog volumeFallbackFog, boolean sunAffected) {
            this(fog, terrainZone, volumeFallbackFog, sunAffected, fog != null, 0, Map.of());
        }

        public ZoneFog inState(int choice) {
            if (stateFogs.isEmpty() || zoneDefault < 1 || zoneDefault > MapEventState.MAX_ZONE_STATE) {
                return this;
            }
            var state = choice >= 1 && choice <= MapEventState.MAX_ZONE_STATE ? choice : zoneDefault;
            var values = stateFogs.get(state);
            if (values == null) {
                values = stateFogs.get(zoneDefault);
            }
            if (values == null) {
                return this;
            }
            var fog = distanceFog && values.end() > values.start() ? values : null;
            return new ZoneFog(fog, terrainZone, values, sunAffected, distanceFog, zoneDefault, stateFogs);
        }
    }

    public int pointRegion(Vec3 point) {
        if (front.length == 0) {
            return 0;
        }
        var index = 0;
        var guard = 0;
        while (index >= 0 && index < front.length && guard++ < front.length) {
            var p = index * 4;
            var side = planes[p + 1] * point.getY()
                    + point.getX() * planes[p]
                    + planes[p + 2] * point.getZ()
                    - planes[p + 3];
            if (side >= 0f) {
                if (front[index] < 0) {
                    return connectivity.length == 0 ? 0 : frontZone[index];
                }
                index = front[index];
            } else {
                if (back[index] < 0) {
                    return connectivity.length == 0 ? 0 : backZone[index];
                }
                index = back[index];
            }
        }
        return -1;
    }

    public long getConnectedZones(Vec3 point) {
        return getConnectedZones(pointRegion(point));
    }

    public long getVisibilityZones(Vec3 point) {
        var zone = pointRegion(point);
        return zone > 0 ? getConnectedZones(zone) : -1L;
    }

    public boolean hasVisibleFakeBackdrop(Vec3 viewOrigin, Frustum parentFrustum, WaterPlane mirrorPlane) {
        if (!nativeVisibilityData || front.length == 0) {
            return false;
        }
        var viewZone = pointRegion(viewOrigin);
        var activeZones = getVisibilityZones(viewOrigin);
        if (viewZone > 0 && viewZone < Long.SIZE && activeZones == -1L) {
            activeZones = 1L << viewZone;
        }

        var stack = new ArrayDeque<Integer>();
        var visited = new boolean[front.length];
        stack.push(0);
        while (!stack.isEmpty()) {
            var nodeIndex = stack.pop();
            if (nodeIndex < 0 || nodeIndex >= front.length || visited[nodeIndex]) {
                continue;
            }
            visited[nodeIndex] = true;
            if (viewZone > 0 && activeZones != -1L && (zoneMask[nodeIndex] & activeZones) == 0L) {
                continue;
            }
            var boundIndex = renderBound[nodeIndex];
            if (boundIndex >= 0 && boundIndex < validBound.length && validBound[boundIndex]) {
                var b = boundIndex * 6;
                if (!doesReflectedBoundIntersect(boundIndex, parentFrustum, mirrorPlane)) {
                    continue;
                }
            }

            if (hasVisibleFakeBackdropInPlaneChain(
                    nodeIndex, viewOrigin, viewZone, activeZones, parentFrustum, mirrorPlane, visited)) {
                return true;
            }
            if (front[nodeIndex] >= 0) {
                stack.push(front[nodeIndex]);
            }
            if (back[nodeIndex] >= 0) {
                stack.push(back[nodeIndex]);
            }
        }
        return false;
    }

    public boolean hasNativeVisibilityData() {
        return nativeVisibilityData;
    }

    private long getConnectedZones(int zone) {
        if (zone < 0 || zone >= connectivity.length || zone >= Long.SIZE) {
            return -1L;
        }
        var connected = 1L << zone;
        long previous;
        do {
            previous = connected;
            var scan = connected;
            while (scan != 0L) {
                var bit = Long.numberOfTrailingZeros(scan);
                scan &= scan - 1L;
                if (bit < connectivity.length) {
                    connected |= connectivity[bit];
                }
            }
        } while (connected != previous);
        return connected;
    }

    private boolean hasVisibleFakeBackdropInPlaneChain(
            int firstNode,
            Vec3 viewOrigin,
            int viewZone,
            long activeZones,
            Frustum parentFrustum,
            WaterPlane mirrorPlane,
            boolean[] visited) {
        var nodeIndex = firstNode;
        while (nodeIndex >= 0 && nodeIndex < front.length) {
            if (nodeIndex != firstNode) {
                if (visited[nodeIndex]) {
                    return false;
                }
                visited[nodeIndex] = true;
            }
            var surface = surfaceIndex[nodeIndex];
            var flags = surface >= 0 && surface < surfaceFlags.length ? surfaceFlags[surface] : 0L;
            if ((flags & BspPolygonFlags.PF_FakeBackdrop) != 0) {
                var p = nodeIndex * 4;
                var planeDot = planes[p] * viewOrigin.getX()
                        + planes[p + 1] * viewOrigin.getY()
                        + planes[p + 2] * viewOrigin.getZ()
                        - planes[p + 3];
                var isFront = planeDot > 0f;
                if (isFront
                        || (flags & (BspPolygonFlags.PF_TwoSided | BspPolygonFlags.PF_Portal)) != 0
                        || viewZone == 0) {
                    var nodeZone = viewZone == 0 ? 0 : (isFront ? frontZone[nodeIndex] : backZone[nodeIndex]);
                    var activeNodeZone = nodeZone >= 0
                            && nodeZone < Long.SIZE
                            && (activeZones == -1L || (activeZones & (1L << nodeZone)) != 0L);
                    var radius = exclusiveSphere[p + 3];
                    var reflectedCenter = mirrorPlane.reflect(
                            new Vec3(exclusiveSphere[p], exclusiveSphere[p + 1], exclusiveSphere[p + 2]));
                    if (activeNodeZone
                            && radius >= 0f
                            && parentFrustum.intersectsSphere(
                                    reflectedCenter.getX(), reflectedCenter.getY(), reflectedCenter.getZ(), radius)) {
                        return true;
                    }
                }
            }
            nodeIndex = planeLink[nodeIndex];
        }
        return false;
    }

    private boolean doesReflectedBoundIntersect(int boundIndex, Frustum frustum, WaterPlane plane) {
        var b = boundIndex * 6;
        var minX = Float.POSITIVE_INFINITY;
        var minY = Float.POSITIVE_INFINITY;
        var minZ = Float.POSITIVE_INFINITY;
        var maxX = Float.NEGATIVE_INFINITY;
        var maxY = Float.NEGATIVE_INFINITY;
        var maxZ = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            var point = plane.reflect(new Vec3(
                    (corner & 1) == 0 ? bounds[b] : bounds[b + 3],
                    (corner & 2) == 0 ? bounds[b + 1] : bounds[b + 4],
                    (corner & 4) == 0 ? bounds[b + 2] : bounds[b + 5]));
            minX = Math.min(minX, point.getX());
            minY = Math.min(minY, point.getY());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxY = Math.max(maxY, point.getY());
            maxZ = Math.max(maxZ, point.getZ());
        }
        return frustum.intersects(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static DistanceFog fogOf(UColor color, float start, float end) {
        return new DistanceFog(
                new Vec3((color.r() & 0xFF) / 255f, (color.g() & 0xFF) / 255f, (color.b() & 0xFF) / 255f), start, end);
    }

    private static int[] fill(int length, int value) {
        var values = new int[length];
        Arrays.fill(values, value);
        return values;
    }
}

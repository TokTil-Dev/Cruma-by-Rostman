package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.UBox;
import com.rost.cruma.core.world.BspLightPublication.Visibility;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

public final class BspVisibilityVolumes {
    private BspVisibilityVolumes() {}

    public static final class Volume {
        private final float[] planes;
        private final float[] absoluteNormals;

        public Volume(List<Vec4> planes) {
            if (planes.size() > 32) {
                throw new IllegalArgumentException("native convex volume exceeds 32 planes");
            }
            this.planes = new float[planes.size() * 4];
            absoluteNormals = new float[planes.size() * 3];
            for (var i = 0; i < planes.size(); i++) {
                var plane = planes.get(i);
                this.planes[i * 4] = plane.getX();
                this.planes[i * 4 + 1] = plane.getY();
                this.planes[i * 4 + 2] = plane.getZ();
                this.planes[i * 4 + 3] = plane.getW();
                absoluteNormals[i * 3] = Math.abs(plane.getX());
                absoluteNormals[i * 3 + 1] = Math.abs(plane.getY());
                absoluteNormals[i * 3 + 2] = Math.abs(plane.getZ());
            }
        }

        public int getPlaneCount() {
            return planes.length / 4;
        }

        public Vec4 getPlane(int index) {
            var offset = Objects.checkIndex(index, getPlaneCount()) * 4;
            return new Vec4(planes[offset], planes[offset + 1], planes[offset + 2], planes[offset + 3]);
        }

        private boolean accepts(float x, float y, float z, float ex, float ey, float ez, boolean containment) {
            for (var i = 0; i < absoluteNormals.length / 3; i++) {
                var plane = i * 4;
                var normal = i * 3;
                var distance = planes[plane + 1] * y + planes[plane] * x + z * planes[plane + 2] - planes[plane + 3];
                var radius = absoluteNormals[normal + 1] * ey
                        + absoluteNormals[normal] * ex
                        + absoluteNormals[normal + 2] * ez;
                if (-radius < distance && (containment || radius < distance)) {
                    return false;
                }
            }
            return true;
        }
    }

    public static class View implements Visibility {
        private final IntFunction<UBox> nodeBounds;
        private final boolean zoned;
        private final int viewZone;
        private final boolean antiPortalClipping;
        private final List<List<Volume>> include = new ArrayList<>(64);
        private final List<List<Volume>> exclude = new ArrayList<>(64);
        private final List<List<Volume>> extra = new ArrayList<>(64);
        private int[] visibleZones;
        private long mask;

        public View(
                IntFunction<UBox> nodeBounds,
                int zoneCount,
                int viewZone,
                boolean precaching,
                List<Vec4> frustum,
                boolean antiPortalClipping) {
            if (zoneCount < 0 || zoneCount > 64 || viewZone < 0 || viewZone >= 64) {
                throw new IllegalArgumentException("native visibility requires at most 64 zones");
            }
            this.nodeBounds = nodeBounds;
            this.viewZone = viewZone;
            this.antiPortalClipping = antiPortalClipping;
            zoned = viewZone != 0;
            for (var i = 0; i < 64; i++) {
                include.add(List.of());
                exclude.add(List.of());
                extra.add(List.of());
            }
            if (precaching) {
                mask = zoneCount == 64 ? -1L : (1L << zoneCount) - 1L;
                visibleZones = new int[Math.max(0, zoneCount - 1)];
                for (var i = 0; i < visibleZones.length; i++) {
                    visibleZones[i] = zoneCount - i - 1;
                }
                for (var i = 0; i < zoneCount; i++) {
                    include.set(i, List.of(new Volume(List.of())));
                }
            } else {
                mask = 1L << viewZone;
                visibleZones = new int[] {viewZone};
                include.set(viewZone, List.of(new Volume(frustum)));
            }
        }

        public boolean isZoned() {
            return zoned;
        }

        public int getViewZone() {
            return viewZone;
        }

        public long getVisibleZoneMask() {
            return mask;
        }

        public int[] getVisibleZones() {
            return visibleZones;
        }

        public List<Volume> getIncludingVolumes(int zone) {
            return include.get(zone);
        }

        public List<Volume> getExcludingVolumes(int zone) {
            return exclude.get(zone);
        }

        public List<Volume> getAdditionalVolumes(int zone) {
            return extra.get(zone);
        }

        public boolean intersectsSphere(Vec4 sphere, int zone) {
            return isSphereVisible(sphere, include.get(zone), exclude.get(zone), extra.get(zone), antiPortalClipping);
        }

        void prependIncluding(int zone, Volume volume) {
            prepend(include, zone, volume);
        }

        void prependExcluding(int zone, Volume volume) {
            prepend(exclude, zone, volume);
        }

        void activateZone(int zone) {
            var bit = 1L << zone;
            if ((mask & bit) != 0) {
                return;
            }
            var values = new int[visibleZones.length + 1];
            values[0] = zone;
            System.arraycopy(visibleZones, 0, values, 1, visibleZones.length);
            visibleZones = values;
            mask |= bit;
            zoneActivated(zone);
        }

        protected void zoneActivated(int zone) {}

        public void replaceVisibleZones(int[] zones, long mask) {
            visibleZones = zones.clone();
            this.mask = mask;
        }

        public void replaceVolumes(int zone, List<Volume> including, List<Volume> excluding, List<Volume> additional) {
            include.set(zone, List.copyOf(including));
            exclude.set(zone, List.copyOf(excluding));
            extra.set(zone, List.copyOf(additional));
        }

        public boolean intersectsBound(int node, int zone) {
            return intersectsBox(nodeBounds.apply(node), zone);
        }

        public boolean intersectsBox(UBox box, int zone) {
            var slot = zone == -1 ? 0 : zone;
            return isVisible(box, include.get(slot), exclude.get(slot), extra.get(slot), antiPortalClipping);
        }

        private static void prepend(List<List<Volume>> lists, int zone, Volume volume) {
            var values = new ArrayList<Volume>(lists.get(zone).size() + 1);
            values.add(volume);
            values.addAll(lists.get(zone));
            lists.set(zone, List.copyOf(values));
        }
    }

    public static boolean isSphereVisible(
            Vec4 sphere, List<Volume> include, List<Volume> exclude, List<Volume> extra, boolean antiPortalClipping) {
        var included = false;
        for (var volume : include) {
            if (acceptsSphere(volume, sphere, false)) {
                included = true;
                break;
            }
        }
        if (!included) {
            return false;
        }
        if (antiPortalClipping) {
            for (var volume : exclude) {
                if (acceptsSphere(volume, sphere, true)) {
                    return false;
                }
            }
            for (var volume : extra) {
                if (acceptsSphere(volume, sphere, true)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isVisible(
            UBox box, List<Volume> include, List<Volume> exclude, List<Volume> extra, boolean antiPortalClipping) {
        var x = (box.max().getX() + box.min().getX()) * .5f;
        var y = (box.max().getY() + box.min().getY()) * .5f;
        var z = (box.max().getZ() + box.min().getZ()) * .5f;
        var ex = box.max().getX() - x;
        var ey = box.max().getY() - y;
        var ez = box.max().getZ() - z;
        var included = false;
        for (var volume : include) {
            if (volume.accepts(x, y, z, ex, ey, ez, false)) {
                included = true;
                break;
            }
        }
        if (!included) {
            return false;
        }
        if (antiPortalClipping) {
            for (var volume : exclude) {
                if (volume.accepts(x, y, z, ex, ey, ez, true)) {
                    return false;
                }
            }
            for (var volume : extra) {
                if (volume.accepts(x, y, z, ex, ey, ez, true)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean acceptsSphere(Volume volume, Vec4 sphere, boolean containment) {
        for (var i = 0; i < volume.getPlaneCount(); i++) {
            var p = volume.getPlane(i);
            var xy = containment
                    ? p.getX() * sphere.getX() + p.getY() * sphere.getY()
                    : p.getY() * sphere.getY() + p.getX() * sphere.getX();
            var distance = xy + sphere.getZ() * p.getZ() - p.getW();
            if (-sphere.getW() < distance && (containment || sphere.getW() < distance)) {
                return false;
            }
        }
        return true;
    }
}

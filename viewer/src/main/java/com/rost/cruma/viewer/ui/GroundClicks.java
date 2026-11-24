package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.spawn.SpawnGround;
import imgui.ImGui;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class GroundClicks {
    private static final int WAIT = 12;

    private final SpawnGround ground;
    private @Nullable Click pending;
    private int frames;
    private double @Nullable [] pointer;

    GroundClicks(SpawnGround ground) {
        this.ground = ground;
    }

    private record Click(
            float ndcX, float ndcY, double ox, double oy, double oz, double dx, double dy, double dz, boolean snap) {}

    List<double[]> update(
            ViewerRenderBackend backend,
            @Nullable Camera camera,
            boolean pointerFree,
            float ndcX,
            float ndcY,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            boolean snap,
            double groundUnder) {
        var placed = new ArrayList<double[]>();
        var waiting = pending;
        if (waiting != null) {
            var hit = backend.getSceneRayDistance(waiting.ndcX(), waiting.ndcY(), 60_000);
            var moved = hasCameraMoved(camera, waiting);
            if (moved || Float.isFinite(hit) && hit > 0 || ++frames >= WAIT) {
                addResolved(placed, waiting, moved ? Float.NaN : hit, groundUnder);
            }
        }
        var hit = pointerFree && pending == null ? backend.getSceneRayDistance(ndcX, ndcY, 60_000) : Float.NaN;
        pointer = pointerFree ? findPoint(hit, ox, oy, oz, dx, dy, dz, snap, groundUnder) : null;
        if (pointerFree && pointer != null && ImGui.isMouseClicked(0)) {
            var click = new Click(ndcX, ndcY, ox, oy, oz, dx, dy, dz, snap);
            if (pending != null) {
                addResolved(placed, pending, Float.NaN, groundUnder);
            }
            if (Float.isFinite(hit) && hit > 0) {
                addResolved(placed, click, hit, groundUnder);
            } else {
                pending = click;
                frames = 0;
            }
        }
        return placed;
    }

    double @Nullable [] getPointer() {
        return pointer;
    }

    boolean isPending() {
        return pending != null;
    }

    double @Nullable [] resolveNow(double groundUnder) {
        var waiting = pending;
        if (waiting == null) {
            return null;
        }
        var placed = new ArrayList<double[]>();
        addResolved(placed, waiting, Float.NaN, groundUnder);
        return placed.isEmpty() ? null : placed.getFirst();
    }

    void cancel() {
        pending = null;
    }

    void clear() {
        pending = null;
        pointer = null;
    }

    private void addResolved(List<double[]> placed, Click click, float hit, double groundUnder) {
        pending = null;
        var point = findPoint(
                hit, click.ox(), click.oy(), click.oz(), click.dx(), click.dy(), click.dz(), click.snap(), groundUnder);
        if (point != null) {
            placed.add(point);
        }
    }

    private double @Nullable [] findPoint(
            float hit, double ox, double oy, double oz, double dx, double dy, double dz, boolean snap, double under) {
        double x;
        double y;
        double z;
        var geodata = Float.isFinite(hit) && hit > 0
                ? null
                : ground.findRayGround(new GeoPoint(ox, oy, oz), new GeoPoint(dx, dy, dz), 60_000);
        if (Float.isFinite(hit) && hit > 0) {
            x = ox + dx * hit;
            y = oy + dy * hit;
            z = oz + dz * hit;
        } else if (geodata != null) {
            x = geodata.x();
            y = geodata.y();
            z = geodata.z();
        } else {
            var plane = Double.isFinite(under) ? under : oz - 200;
            if (Math.abs(dz) < 1e-6) {
                return null;
            }
            var t = (plane - oz) / dz;
            if (t <= 0 || t > 60_000) {
                return null;
            }
            x = ox + dx * t;
            y = oy + dy * t;
            z = plane;
        }
        if (snap) {
            x = Math.round(x / 16) * 16;
            y = Math.round(y / 16) * 16;
        }
        var floor = ground.getGroundAt(x, y, z + 64);
        return new double[] {x, y, Double.isNaN(floor) ? z : floor, z};
    }

    private static boolean hasCameraMoved(@Nullable Camera camera, Click click) {
        if (camera == null) {
            return true;
        }
        var origin = camera.getCursorRayOrigin(click.ndcX(), click.ndcY());
        var direction = camera.getCursorRayDirection(click.ndcX(), click.ndcY());
        return Math.hypot(
                                Math.hypot(origin.getX() - click.ox(), origin.getY() - click.oy()),
                                origin.getZ() - click.oz())
                        > .5
                || direction.getX() * click.dx() + direction.getY() * click.dy() + direction.getZ() * click.dz()
                        < .99999;
    }
}

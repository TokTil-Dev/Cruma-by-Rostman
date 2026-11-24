package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UStaticMesh;
import org.jetbrains.annotations.Nullable;

public final class NativeStaticMeshLod {
    private NativeStaticMeshLod() {}

    public record Mesh(boolean enabled, float range1, float range2, int impostorThresholdMask) {
        public Mesh(boolean enabled, float range1, float range2) {
            this(enabled, range1, range2, 0);
        }

        public Mesh withImpostorDistanceRatio(float ratio) {
            if (ratio == 1f || impostorThresholdMask == 0) {
                return this;
            }
            return new Mesh(
                    enabled,
                    range1 * ((impostorThresholdMask & 1) != 0 ? ratio : 1f),
                    range2 * ((impostorThresholdMask & 2) != 0 ? ratio : 1f),
                    impostorThresholdMask);
        }
    }

    public record Actor(float x, float y, boolean skyMesh, boolean ignoredRange, float rangeForIgnoredRange) {}

    public record View(float x, float y, boolean hasViewportActor, int editor, int renderMode) {}

    public static int select(Mesh mesh, @Nullable View view, @Nullable Actor actor) {
        if (view == null || actor == null) {
            return -1;
        }
        if (actor.skyMesh()) {
            return 0;
        }
        if (!view.hasViewportActor()) {
            throw new IllegalArgumentException("native LOD view requires a viewport actor to initialize its origin");
        }
        var dx = actor.x() - view.x();
        var dy = actor.y() - view.y();
        var distance = (float) Math.sqrt((double) (dy * dy + dx * dx));
        if (actor.ignoredRange() && distance > actor.rangeForIgnoredRange()) {
            return -1;
        }
        if (!mesh.enabled()) {
            return 0;
        }
        var mode = view.renderMode();
        if (view.editor() != 0 && (mode >= 13 && mode <= 17 || mode == 35 || mode == 36)) {
            return 0;
        }
        if (distance > mesh.range2() * UStaticMesh.LOD_RANGE_UNIT) {
            return mesh.range2() == mesh.range1() ? 1 : 2;
        }
        return distance > mesh.range1() * UStaticMesh.LOD_RANGE_UNIT ? 1 : 0;
    }
}

package com.rost.cruma.viewer.agent.view;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class SceneRead {
    private static final long NANOS_PER_FRAME = 3_000_000L;

    private final @Nullable Bounds3f box;
    private final Predicate<DebugSceneObject> keep;
    private final Map<String, ScenePlacement> placements = new LinkedHashMap<>();
    private int group;
    private int groups = -1;
    private long revision;

    SceneRead(@Nullable Bounds3f box, Predicate<DebugSceneObject> keep) {
        this.box = box;
        this.keep = keep;
    }

    static boolean meets(Bounds3f a, Bounds3f b) {
        return a.minX() <= b.maxX()
                && a.maxX() >= b.minX()
                && a.minY() <= b.maxY()
                && a.maxY() >= b.minY()
                && a.minZ() <= b.maxZ()
                && a.maxZ() >= b.minZ();
    }

    @Nullable
    List<ScenePlacement> step(ViewerRenderBackend backend) {
        var now = backend.getDebugSceneRevision();
        if (groups < 0 || now != revision) {
            revision = now;
            groups = backend.getDebugSceneObjectGroups();
            group = 0;
            placements.clear();
        }
        var deadline = System.nanoTime() + NANOS_PER_FRAME;
        while (group < groups) {
            if (box == null || backend.doesDebugSceneGroupMeet(group, box)) {
                for (var row : backend.getDebugSceneObjects(group)) {
                    add(row);
                }
            }
            group++;
            if (System.nanoTime() >= deadline) {
                return null;
            }
        }
        return new ArrayList<>(placements.values());
    }

    private void add(DebugSceneObject row) {
        if ((box != null && !meets(row.bounds(), box)) || !keep.test(row)) {
            return;
        }
        placements.merge(ScenePlacement.key(row), ScenePlacement.of(row), (known, added) -> known.with(row));
    }
}

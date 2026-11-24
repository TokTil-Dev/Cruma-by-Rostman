package com.rost.cruma.viewer.render.bgfx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

final class BgfxProjectorReceiverCache<T> {
    private final Map<String, List<BgfxSceneObject>> objectsByOwner = new HashMap<>();
    private final Map<String, List<T>> drawsByOwner = new HashMap<>();
    private final List<T> draws = new ArrayList<>();
    private long sceneRevision = Long.MIN_VALUE;
    private long sourceRevision = Long.MIN_VALUE;

    List<T> update(
            List<BgfxSceneObject> objects,
            long nextSceneRevision,
            long nextSourceRevision,
            Set<String> projectorOwners,
            Set<String> movedOwners,
            BiFunction<String, List<BgfxSceneObject>, List<T>> build) {
        var sceneChanged = sceneRevision != nextSceneRevision;
        var sourcesChanged = sourceRevision != nextSourceRevision;
        if (sceneChanged) {
            objectsByOwner.clear();
            for (var object : objects) {
                objectsByOwner
                        .computeIfAbsent(object.getOwner(), _ -> new ArrayList<>())
                        .add(object);
            }
            sceneRevision = nextSceneRevision;
        }
        var rebuildAll = sceneChanged || sourcesChanged;
        if (rebuildAll) {
            drawsByOwner.clear();
        }
        var changed = rebuildAll;
        for (var owner : projectorOwners) {
            if (rebuildAll || movedOwners.contains(owner)) {
                drawsByOwner.put(owner, build.apply(owner, objectsByOwner.getOrDefault(owner, List.of())));
                changed = true;
            }
        }
        if (changed) {
            draws.clear();
            for (var owner : projectorOwners) {
                draws.addAll(drawsByOwner.get(owner));
            }
        }
        sourceRevision = nextSourceRevision;
        return draws;
    }

    void clear() {
        objectsByOwner.clear();
        drawsByOwner.clear();
        draws.clear();
        sceneRevision = Long.MIN_VALUE;
        sourceRevision = Long.MIN_VALUE;
    }
}

package com.rost.cruma.viewer.render;

import com.rost.cruma.viewer.render.SceneDoors.Moved;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class SceneDoorPreview {
    private final Map<String, float[]> waiting = new HashMap<>();
    private boolean used;

    void apply(Moved doors, boolean editing, ViewerRenderBackend backend) {
        if (doors == null && waiting.isEmpty()) {
            return;
        }
        Set<String> restored = doors == null ? Set.of() : doors.restored();
        if (doors != null) {
            waiting.putAll(doors.moved());
            waiting.keySet().removeAll(restored);
        }
        var ready = new HashMap<String, float[]>();
        if (editing) {
            waiting.clear();
        }
        for (var door : waiting.entrySet()) {
            used = true;
            if (backend.canPreviewActorTransform(door.getKey())) {
                ready.put(door.getKey(), door.getValue());
            }
        }
        waiting.keySet().removeAll(ready.keySet());
        if (!ready.isEmpty() || !restored.isEmpty()) {
            backend.previewActorTransformDeltaChanges(ready, restored);
        }
        if (used && !editing && ready.isEmpty() && waiting.isEmpty() && !restored.isEmpty()) {
            backend.clearEditorPreview();
            used = false;
        }
    }
}

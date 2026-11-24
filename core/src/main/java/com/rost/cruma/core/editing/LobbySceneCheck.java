package com.rost.cruma.core.editing;

import com.rost.cruma.core.world.MapScenes.Scene;
import java.util.ArrayList;
import java.util.List;

public record LobbySceneCheck(
        String map, List<Change> changes, List<SceneChange> scenes, List<Refusal> refused, int inPlace, int grown) {
    public LobbySceneCheck {
        changes = List.copyOf(changes);
        scenes = List.copyOf(scenes);
        refused = List.copyOf(refused);
    }

    public record Change(String object, String field, String before, String after) {}

    public record SceneChange(Scene before, Scene after, boolean asked) {
        public String tag() {
            return before.tag();
        }
    }

    public record Refusal(LobbySceneEdit edit, String reason) {}

    public boolean canWrite() {
        return refused.isEmpty();
    }

    public List<String> alsoChanged() {
        var tags = new ArrayList<String>();
        for (var scene : scenes) {
            if (!scene.asked()) {
                tags.add(scene.tag());
            }
        }
        return List.copyOf(tags);
    }
}

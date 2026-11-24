package com.rost.cruma.viewer.ui;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class SceneGuideActions {
    private static final String TOOL = "scenes";
    private static final String NO_SCENES =
            "This view has no Scenes tool. A map of the world has it, and a lobby once its scenes are read.";

    private SceneGuideActions() {}

    static void addTo(Map<String, GuideAction> actions) {
        actions.put(
                "scenes-open-panel",
                new NotedGuideAction(
                        "scenes-open-panel", host -> NotedGuideAction.withoutTool(host, TOOL, NO_SCENES), host -> {
                            host.openTool(TOOL);
                            return null;
                        }));
        actions.put(
                "scenes-open-director",
                new NotedGuideAction("scenes-open-director", SceneGuideActions::directorBlocked, host -> {
                    var scenes = host.getScenes();
                    if (scenes == null) {
                        return null;
                    }
                    scenes.openDirector();
                    return "The tray of the video director stands under the world. Fly the camera and press K to set a"
                            + " key.";
                }));
        actions.put(
                "scenes-play-example",
                new NotedGuideAction("scenes-play-example", SceneGuideActions::exampleBlocked, host -> {
                    var scenes = host.getScenes();
                    var name = scenes == null ? null : scenes.playExample();
                    return name == null
                            ? "No cut scene could play here."
                            : "Playing " + name + ". Esc or Stop in the Scenes panel ends it.";
                }));
    }

    private static @Nullable String directorBlocked(GuideHost host) {
        var missing = NotedGuideAction.withoutTool(host, TOOL, NO_SCENES);
        if (missing != null) {
            return missing;
        }
        var scenes = host.getScenes();
        return scenes == null ? NO_SCENES : scenes.getDirectorUnavailable();
    }

    private static @Nullable String exampleBlocked(GuideHost host) {
        var missing = NotedGuideAction.withoutTool(host, TOOL, NO_SCENES);
        if (missing != null) {
            return missing;
        }
        var scenes = host.getScenes();
        return scenes == null ? NO_SCENES : scenes.getExampleUnavailable();
    }
}

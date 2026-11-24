package com.rost.cruma.viewer.agent.film;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.world.MapScenes;
import com.rost.cruma.core.world.MapScenes.Scene;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.view.Pose;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class LobbyScenes {
    static final String KIND = "lobby";
    private static final String ARENA = "_arena";
    private static final Map<Path, Read> READ = new ConcurrentHashMap<>();

    private LobbyScenes() {}

    record Found(String map, Scene scene) {}

    private record Read(long size, long modified, List<Scene> scenes) {}

    public static List<Found> find(Path client) {
        var found = new ArrayList<Found>();
        for (var map : WorldMapCatalog.namedMaps(client)) {
            if (map.toLowerCase(Locale.ROOT).endsWith(ARENA)) {
                continue;
            }
            for (var scene : scenes(client, map)) {
                found.add(new Found(map, scene));
            }
        }
        return found;
    }

    static JSONObject row(Found found) {
        var scene = found.scene();
        var row = new JSONObject();
        row.put("name", scene.tag());
        row.put("kind", KIND);
        row.put("map", found.map());
        row.put("seconds", Math.round(scene.seconds() * 100) / 100.0);
        if (scene.warp()) {
            row.put("warp", true);
        }
        if (scene.looping()) {
            row.put("loops", true);
        }
        return row;
    }

    static @Nullable Step play(String name, ViewerContext viewer) {
        for (var scene : viewer.system().getLobbyScenes()) {
            if (scene.tag().equalsIgnoreCase(name)) {
                return new LobbyPlay(viewer, scene);
            }
        }
        return null;
    }

    private static List<Scene> scenes(Path client, String map) {
        var file = file(client, map);
        if (file == null) {
            return List.of();
        }
        try {
            var size = Files.size(file);
            var modified = Files.getLastModifiedTime(file).toMillis();
            var known = READ.get(file);
            if (known != null && known.size() == size && known.modified() == modified) {
                return known.scenes();
            }
            var scenes = MapScenes.find(client, map);
            READ.put(file, new Read(size, modified, scenes));
            return scenes;
        } catch (IOException | RuntimeException unreadable) {
            LogManager.getLogger(LobbyScenes.class)
                    .warn("Could not read the scenes of {}: {}", map, unreadable.toString());
            return List.of();
        }
    }

    private static @Nullable Path file(Path client, String map) {
        var wanted = map + ".unr";
        try (var folders = Files.list(client)) {
            for (var folder : folders.filter(Files::isDirectory).toList()) {
                if (!folder.getFileName().toString().equalsIgnoreCase("maps")) {
                    continue;
                }
                try (var files = Files.list(folder)) {
                    for (var file : files.toList()) {
                        if (file.getFileName().toString().equalsIgnoreCase(wanted)) {
                            return file;
                        }
                    }
                }
            }
        } catch (IOException unreadable) {
            return null;
        }
        return null;
    }

    private static final class LobbyPlay implements Step {
        private final ViewerContext viewer;
        private final Scene scene;
        private boolean started;
        private boolean paused;
        private double speed;
        private double reached;

        LobbyPlay(ViewerContext viewer, Scene scene) {
            this.viewer = viewer;
            this.scene = scene;
        }

        @Override
        public @Nullable ToolResult step() throws ToolProblem {
            var player = viewer.scene().scenes;
            if (!started) {
                FilmScenes.requireFree(viewer);
                started = true;
                var playback = viewer.scene().playback;
                paused = playback.isPaused();
                speed = playback.getSpeed();
                playback.setSpeed(1);
                playback.setPaused(false);
                player.play(scene);
                return null;
            }
            if (player.getScene() == scene) {
                if (player.getSeconds() + 1e-6 < reached) {
                    reached = scene.seconds();
                    player.stop();
                    return answer();
                }
                reached = player.getSeconds();
                return null;
            }
            if (scene.warp() || reached >= scene.seconds() - .1) {
                reached = scene.seconds();
            }
            return answer();
        }

        @Override
        public void cancel() {
            if (viewer.scene().scenes.getScene() == scene) {
                viewer.scene().scenes.stop();
            }
            giveBack();
        }

        private ToolResult answer() {
            giveBack();
            var data = new JSONObject();
            data.put("scene", scene.tag());
            data.put("kind", KIND);
            data.put("map", viewer.scene().getStandaloneMap());
            data.put("seconds", Math.round(scene.seconds() * 100) / 100.0);
            data.put("played", Math.round(reached * 100) / 100.0);
            data.put(
                    "ended",
                    reached >= scene.seconds() ? "at its end" : "before its end, as something else stopped it");
            data.put("camera", Pose.write(viewer.scene().camera));
            data.put(
                    "note",
                    scene.warp()
                            ? "A warp puts the camera on its point, and the camera stays there."
                            : "The camera stays where the scene left it, as Stop in the Scenes tool leaves it.");
            return ToolResult.data(data);
        }

        private void giveBack() {
            if (!started) {
                return;
            }
            var playback = viewer.scene().playback;
            playback.setSpeed(speed);
            playback.setPaused(paused);
        }
    }
}

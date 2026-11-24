package com.rost.cruma.viewer.agent.film;

import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.ServerScenes;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.application.SceneRequest;
import com.rost.cruma.viewer.editing.SceneFileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jetbrains.annotations.Nullable;

record FilmScenes(
        Path folder,
        Path client,
        WorldVariant variant,
        @Nullable Path scripts) {
    enum Kind {
        OWN,
        CLIENT,
        SERVER;

        String getWord() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Found(Scene scene, Kind kind, @Nullable Path file) {}

    record Fitted(Found found, List<String> ring) {}

    static @Nullable Double hourOf(Scene scene, @Nullable Double asked) {
        if (asked != null) {
            return asked;
        }
        var own = scene.hourOr(Double.NaN);
        return Double.isNaN(own) ? null : Double.valueOf(own);
    }

    static Fitted taken(CompletableFuture<Fitted> finding) throws ToolProblem {
        try {
            return finding.join();
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof ToolProblem problem) {
                throw problem;
            }
            throw failed;
        }
    }

    static void requireFree(ViewerContext viewer) throws ToolProblem {
        var scene = viewer.scene();
        if (!viewer.ready()) {
            throw new ToolProblem(
                    "The tiles of this viewer still load. Call again when cruma_status says it is ready.");
        }
        if (scene.gameScenes.isPlaying() || scene.scenes.isPlaying()) {
            throw new ToolProblem("A scene plays in this viewer, and it holds the camera. Call again after its end.");
        }
        if (scene.playback.isComparisonLocked()) {
            throw new ToolProblem("A comparison holds the clock of this viewer. Ask the person to end it.");
        }
    }

    static List<String> notes(Fitted fitted, boolean floorsRead) {
        var notes = new ArrayList<String>();
        if (!fitted.ring().isEmpty()) {
            notes.add("The film also holds " + String.join(", ", fitted.ring())
                    + " for its far view, and this viewer does not, so the horizon differs. Open them with"
                    + " world_open to see it.");
        }
        var scene = fitted.found().scene();
        if (!floorsRead
                && scene.items().stream().anyMatch(item -> !item.actors().isEmpty())) {
            notes.add("This viewer did not read the client's floors, so actors stand at the heights of the scene, and a"
                    + " film can stand them a little differently.");
        }
        return notes;
    }

    Found find(String name) throws ToolProblem {
        var store = new SceneFileStore(folder, folder);
        var file = store.getFile(name);
        if (Files.isRegularFile(file)) {
            try {
                return new Found(SceneFileStore.read(file).scene(), Kind.OWN, file);
            } catch (IOException refused) {
                throw new ToolProblem("The scene " + name + " does not open: " + refused.getMessage());
            }
        }
        for (var found : cuts()) {
            if (found.scene().name().equalsIgnoreCase(name)) {
                return found;
            }
        }
        throw new ToolProblem("There is no scene named \"" + name + "\". Call scene_list to search.");
    }

    List<Found> cuts() {
        var scenes = new ArrayList<Found>();
        for (var scene : GameScenes.find(client)) {
            scenes.add(new Found(scene, Kind.CLIENT, null));
        }
        for (var scene : ServerScenes.find(scripts, client, variant)) {
            scenes.add(new Found(scene, Kind.SERVER, null));
        }
        return scenes;
    }

    Fitted fit(String name, List<String> held) throws ToolProblem {
        var found = find(name);
        var scene = found.scene();
        var close = SceneRequest.close(scene, client, variant);
        var lack = close.lack(scene);
        if (lack != null) {
            throw new ToolProblem(lack);
        }
        var holds = new HashSet<>(held);
        var missing = lacking(close.tiles(), holds);
        if (!missing.isEmpty()) {
            throw new ToolProblem("This viewer holds "
                    + (held.size() > 6 ? held.size() + " tiles" : String.join(", ", held))
                    + " and not "
                    + String.join(", ", missing)
                    + ", which "
                    + scene.name()
                    + " needs. Call world_open with tiles "
                    + String.join(", ", close.tiles())
                    + ".");
        }
        return new Fitted(found, lacking(SceneRequest.of(scene, client, variant).tiles(), holds));
    }

    CompletableFuture<Fitted> fitLater(String name, List<String> held) {
        var future = new CompletableFuture<Fitted>();
        Thread.ofPlatform().name("cruma-agent-scene").daemon().start(() -> {
            try {
                future.complete(fit(name, held));
            } catch (ToolProblem | RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private static List<String> lacking(List<String> wanted, HashSet<String> holds) {
        var missing = new ArrayList<String>();
        for (var map : wanted) {
            if (!holds.contains(map)) {
                missing.add(map);
            }
        }
        return missing;
    }
}

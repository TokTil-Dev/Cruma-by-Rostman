package com.rost.cruma.viewer.agent.film;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.film.FilmScenes.Fitted;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

final class FilmPlay implements Step {
    private final ViewerContext viewer;
    private final CompletableFuture<Fitted> finding;
    private final @Nullable Double hour;
    private @Nullable Fitted fitted;
    private @Nullable FilmHold hold;
    private double reached;

    FilmPlay(ViewerContext viewer, CompletableFuture<Fitted> finding, @Nullable Double hour) {
        this.viewer = viewer;
        this.finding = finding;
        this.hour = hour;
    }

    @Override
    public @Nullable ToolResult step() throws ToolProblem {
        if (hold == null) {
            if (finding.isDone()) {
                begin();
            }
            return null;
        }
        var player = viewer.scene().gameScenes;
        if (player.isPlaying()) {
            reached = player.getSeconds();
            return null;
        }
        var ended = player.hasRunOut();
        var shown = hold.getHour();
        hold.giveBack();
        return answer(ended, shown);
    }

    @Override
    public void cancel() {
        if (hold != null) {
            hold.giveBack();
        }
    }

    private void begin() throws ToolProblem {
        var found = FilmScenes.taken(finding);
        FilmScenes.requireFree(viewer);
        fitted = found;
        hold = new FilmHold(viewer, FilmScenes.hourOf(found.found().scene(), hour));
        var playback = viewer.scene().playback;
        playback.setSpeed(1);
        playback.setPaused(false);
        viewer.system().playScene(found.found().scene());
    }

    private ToolResult answer(boolean ended, double shown) {
        var scene = fitted.found().scene();
        var data = new JSONObject();
        data.put("scene", scene.name());
        data.put("kind", fitted.found().kind().getWord());
        data.put("seconds", Math.round(scene.seconds() * 100) / 100.0);
        data.put("played", Math.round(reached * 100) / 100.0);
        data.put("ended", ended ? "at its end" : "before its end, as something else stopped it");
        data.put("hour", shown);
        var notes = FilmScenes.notes(fitted, viewer.scene().spawns.wanted);
        if (!notes.isEmpty()) {
            data.put("notes", notes);
        }
        return ToolResult.data(data);
    }
}

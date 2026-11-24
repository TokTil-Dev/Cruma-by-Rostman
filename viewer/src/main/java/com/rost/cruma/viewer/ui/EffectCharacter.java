package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.render.SpawnOverlayState.LivePose;
import com.rost.cruma.viewer.spawn.SpawnModels.Animated;
import imgui.type.ImString;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

final class EffectCharacter {
    final CharacterForm characterForm;
    final ImString characterSearch = new ImString(64);
    boolean onCharacter;
    boolean playerCharacter;
    int characterId;

    @Nullable
    String wantedRace;

    @Nullable
    CompletableFuture<@Nullable Animated> figureLoading;

    String figureLoadingFor = "";

    @Nullable
    Animated figure;

    String figureFor = "";

    @Nullable
    LivePose pose;

    EffectCharacter(String id) {
        characterForm = new CharacterForm(id);
    }

    void forgetClient() {
        figureLoading = null;
        figureLoadingFor = "";
        figure = null;
        figureFor = "";
        characterForm.reset();
    }
}

package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.viewer.geodata.ClientFloors;
import org.jetbrains.annotations.Nullable;

interface SceneBaker {
    void start(Scene scene, int rate);

    void cancel();

    @Nullable
    BakedScene getLatest();

    boolean isBusy();

    double getProgress();

    String getProblem();

    default @Nullable ClientFloors getFloors() {
        return null;
    }

    default void readFloors(String tile) {}

    default boolean isReadingFloors() {
        return false;
    }

    void release();

    void close();
}

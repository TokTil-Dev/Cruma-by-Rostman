package com.rost.cruma.core.editing;

import com.rost.cruma.core.world.MapScenes;
import com.rost.cruma.core.world.MapScenes.Pose;
import com.rost.cruma.core.world.MapScenes.Style;
import com.rost.cruma.core.world.MapScenes.SubAction;
import com.rost.cruma.core.world.MapScenes.Vector;

public sealed interface LobbySceneEdit {
    String scene();

    int action();

    record Point(String scene, int action, Pose pose) implements LobbySceneEdit {}

    record Time(String scene, int action, double seconds) implements LobbySceneEdit {}

    record PathStyle(String scene, int action, Style style) implements LobbySceneEdit {}

    record Handles(String scene, int action, Vector control1, Vector control2) implements LobbySceneEdit {}

    record SubValues(String scene, int action, int index, SubAction value) implements LobbySceneEdit {}
}

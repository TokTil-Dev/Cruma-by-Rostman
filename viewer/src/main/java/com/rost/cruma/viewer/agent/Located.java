package com.rost.cruma.viewer.agent;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import org.jetbrains.annotations.Nullable;

public record Located(
        Target target, @Nullable Bounds3f bounds, @Nullable DebugSceneObject object) {}

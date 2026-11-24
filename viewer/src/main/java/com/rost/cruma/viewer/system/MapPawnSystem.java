package com.rost.cruma.viewer.system;

import com.rost.cruma.viewer.application.UiContext;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.spawn.MapPawnStage.Figures;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public final class MapPawnSystem implements System {
    private final RenderingContext rendering;
    private final UiContext ui;
    private final Figures figures;
    private final Supplier<@Nullable EffectPreview> effects;

    public MapPawnSystem(
            RenderingContext rendering, UiContext ui, Figures figures, Supplier<@Nullable EffectPreview> effects) {
        this.rendering = rendering;
        this.ui = ui;
        this.figures = figures;
        this.effects = effects;
    }

    @Override
    public void start() {
        rendering.mapPawns.open(figures);
        rendering.mapPawns.setEffects(effects);
    }

    @Override
    public void stop(WindowContext windowContext) {
        rendering.mapPawns.close();
        figures.close();
    }

    @Override
    public void frameEnd(long frameTime) {
        rendering.mapPawns.update(
                rendering.scene,
                rendering.camera,
                rendering.spawns,
                rendering.playback.getPreciseSeconds(),
                ui.rendering.staticMeshes);
    }
}

package com.rost.cruma.viewer.render.api;

public final class RenderState {
    public final RenderSettings settings;
    public final RenderTelemetry stats;
    public final WorldLoadProgress loading;
    private boolean sceneLit = true;

    public RenderState(RenderSettings settings, RenderTelemetry stats, WorldLoadProgress loading) {
        this.settings = settings;
        this.stats = stats;
        this.loading = loading;
    }

    public void setSceneLit(boolean lit) {
        sceneLit = lit;
    }

    public boolean isSceneLit() {
        return sceneLit;
    }

    public boolean isCustomLightingEffective() {
        return sceneLit && settings.isCustomLightingEnabled() && !stats.customLightingSuppressedByAuthoredInterior;
    }

    public boolean isCustomRealisticLightingEffective() {
        return isCustomLightingEffective()
                && (settings.getCustomLightingModel() == 1 || settings.getCustomLightingModel() == 2);
    }

    public boolean isGlobalIlluminationEffective() {
        return isCustomLightingEffective() && settings.getCustomLightingModel() == 2;
    }
}

package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.render.GeodataState;

public final class SceneSettingsDefaults {
    private static final World WORLD = ConfigLoader.builtInDefaults().viewer().world();
    private static final Rendering DEFAULTS = defaults();
    private static final ViewSettingsSnapshot VIEW = ViewSettingsSnapshot.capture(DEFAULTS);

    private SceneSettingsDefaults() {}

    public static void restore(Rendering settings) {
        VIEW.apply(settings);
        settings.objectPickMaxDist.set(DEFAULTS.objectPickMaxDist.get());
        settings.setMusicDefaults(WORLD.music());
        settings.setSoundDefaults(WORLD.sound());
    }

    public static void restore(GeodataState settings) {
        var path = settings.path;
        settings.setDefaults(WORLD.geodata());
        settings.path = path;
    }

    private static Rendering defaults() {
        var settings = new Rendering();
        settings.setDefaults(WORLD.rendering());
        return settings;
    }
}

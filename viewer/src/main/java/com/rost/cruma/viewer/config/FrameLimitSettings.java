package com.rost.cruma.viewer.config;

import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import java.io.IOException;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class FrameLimitSettings {
    public static final String PATH = "viewer.world.rendering.graphics.frame_rate_cap";

    public static final List<Integer> PRESETS = List.of(30, 60, 120, 144, 240);

    private final @Nullable ConfigLoader loader;
    private int kept;

    private FrameLimitSettings(@Nullable ConfigLoader loader, int start) {
        this.loader = loader;
        kept = start;
    }

    public static FrameLimitSettings in(ConfigLoader loader, int start) {
        return new FrameLimitSettings(loader, start);
    }

    public static FrameLimitSettings session(int start) {
        return new FrameLimitSettings(null, start);
    }

    public static boolean isAllowed(long cap) {
        return cap >= ViewerGraphicsConfig.MIN_FRAME_RATE_CAP && cap <= ViewerGraphicsConfig.MAX_FRAME_RATE_CAP;
    }

    public void follow(int cap) {
        if (!isAllowed(cap)) {
            throw new IllegalArgumentException("The frame limit must be from "
                    + ViewerGraphicsConfig.MIN_FRAME_RATE_CAP + " to " + ViewerGraphicsConfig.MAX_FRAME_RATE_CAP
                    + " FPS, got " + cap);
        }
        if (cap == kept) {
            return;
        }
        kept = cap;
        if (loader == null) {
            return;
        }
        try {
            loader.reload();
            var model = new OptionsModel(loader);
            model.set(PATH, cap);
            if (model.hasChanges()) {
                model.save();
            }
        } catch (IOException | RuntimeException failure) {
            LogManager.getLogger(FrameLimitSettings.class)
                    .warn("Could not save the frame limit: {}", failure.toString());
        }
    }
}

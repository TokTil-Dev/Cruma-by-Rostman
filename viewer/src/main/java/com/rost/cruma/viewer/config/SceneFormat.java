package com.rost.cruma.viewer.config;

import java.util.Locale;

public enum SceneFormat {
    BLENDER,
    MP4;

    static SceneFormat parse(String text) {
        for (var format : values()) {
            if (format.getKey().equalsIgnoreCase(text.strip())) {
                return format;
            }
        }
        throw new IllegalArgumentException("exports.scene_format must be blender or mp4, got " + text);
    }

    public String getKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}

package com.rost.cruma.viewer.application;

import com.rost.cruma.core.export.LocationExport;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SceneVideoFile {
    private static final String EXTENSION = ".mp4";

    private SceneVideoFile() {}

    public static Path in(Path parent, String scene, WorldVariant variant) {
        return free(parent.resolve(LocationExport.toSafeName(scene) + variant.getTableSuffix() + EXTENSION));
    }

    public static Path free(Path wanted) {
        if (!Files.exists(wanted)) {
            return wanted;
        }
        var name = wanted.getFileName().toString();
        var dot = name.lastIndexOf('.');
        var stem = dot < 0 ? name : name.substring(0, dot);
        var extension = dot < 0 ? "" : name.substring(dot);
        for (var number = 2; ; number++) {
            var next = wanted.resolveSibling(stem + "_" + number + extension);
            if (!Files.exists(next)) {
                return next;
            }
        }
    }

    public static Path part(Path video) {
        return video.resolveSibling(video.getFileName() + ".part");
    }

    public static Path sound(Path video) {
        return video.resolveSibling(video.getFileName() + ".part.wav");
    }

    public static void clean(Path video) throws IOException {
        Files.deleteIfExists(part(video));
        Files.deleteIfExists(sound(video));
    }
}

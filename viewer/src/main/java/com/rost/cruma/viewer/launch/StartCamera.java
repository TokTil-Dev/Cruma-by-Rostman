package com.rost.cruma.viewer.launch;

import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class StartCamera {
    private static final float FALLBACK_HEIGHT = 8000;

    private StartCamera() {}

    public record Start(float x, float y, float z, boolean fallback) {
        public String toSetting() {
            return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
        }
    }

    public static @Nullable Start choose(
            List<String> tiles, Location configured, StartPlaces places, @Nullable String placeName) {
        var chosen = Set.copyOf(tiles);
        if (placeName != null) {
            var place = places.findPlace(placeName);
            if (place != null && chosen.contains(StartPlaces.tileOf(place))) {
                return new Start(place.x(), place.y(), place.z(), false);
            }
        }
        if (tiles.isEmpty() || chosen.contains(StartPlaces.tileOf(configured))) {
            return null;
        }
        var naming = places.findNamingPlace(tiles);
        if (naming != null) {
            return new Start(naming.x(), naming.y(), naming.z(), false);
        }
        var first = tiles.getFirst();
        return new Start(
                (TileBounds.x(first) - 20L) * 32768 + 16384f,
                (TileBounds.y(first) - 18L) * 32768 + 16384f,
                FALLBACK_HEIGHT,
                true);
    }
}

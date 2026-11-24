package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.config.CaptureRadarLayout;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.TileSelection;
import imgui.ImGui;
import java.util.List;
import java.util.Locale;

public final class CaptureRadarDetails {
    private CaptureRadarDetails() {}

    public static CaptureRadarLayout forSelection(
            World world, int tileResolution, String selection, List<String> tiles) {
        if (TileSelection.NONE.equals(selection)) {
            throw new IllegalArgumentException("Select at least one tile.");
        }
        if (!selection.isEmpty()) {
            var selected = ClientInstallation.ALL_TILES.equals(selection) ? tiles : TileSelection.parse(selection);
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("No map tiles are available.");
            }
            var x = selected.stream()
                    .mapToInt(tile -> Integer.parseInt(tile.split("_")[0]))
                    .summaryStatistics();
            var y = selected.stream()
                    .mapToInt(tile -> Integer.parseInt(tile.split("_")[1]))
                    .summaryStatistics();
            return CaptureRadarLayout.forBounds(tileResolution, x.getMin(), x.getMax(), y.getMin(), y.getMax());
        }
        return CaptureRadarLayout.forBounds(
                tileResolution, world.minXTile(), world.maxXTile(), world.minYTile(), world.maxYTile());
    }

    public static CaptureRadarLayout forTiles(int tileResolution, List<String> tiles) {
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("Select at least one tile.");
        }
        return forSelection(null, tileResolution, String.join(",", tiles), tiles);
    }

    public static String summarize(CaptureRadarLayout layout) {
        return String.format(Locale.ROOT, "Radar map %,d px wide · about ", layout.width())
                + formatBytes(layout.workingBytes());
    }

    public static void draw(CaptureRadarLayout layout) {
        ImGui.textDisabled(String.format(Locale.ROOT, "Radar map: %,d × %,d px", layout.width(), layout.height()));
        ImGui.textDisabled("Radar RAM: about " + formatBytes(layout.workingBytes()) + " · Temporary disk: up to "
                + formatBytes(layout.temporaryBytes()));
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip("The radar map is built in a temporary file. These numbers cover the radar only."
                + " The world and tile images need more RAM. The output image and separate tile images need more disk"
                + " space. The temporary file is removed when capture ends.");
        }
    }

    static String formatBytes(long value) {
        if (value >= 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f GiB", value / (1024.0 * 1024 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024));
    }
}

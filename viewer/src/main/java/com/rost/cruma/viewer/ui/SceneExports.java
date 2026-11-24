package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.SceneFormat;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import org.jetbrains.annotations.Nullable;

final class SceneExports {
    private static final String[] FORMATS = {"Blender", "MP4"};
    private static final String[] FORMAT_TIPS = {
        "A folder for Blender: the tiles, the NPCs with their animations, the camera, the sounds and marks of the"
                + " effects.",
        "A video with the sound, the fades, the bars, the slides and the lines, as the scene plays here. It needs"
                + " ffmpeg."
    };
    private static final int[][] SIZES = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
    private static final String[] SIZE_LABELS = {"720p", "1080p", "1440p", "4K"};
    private static final int[] RATES = {30, 60};
    private static final String[] RATE_LABELS = {"30", "60"};

    private final SceneBlenderExport blender;
    private final SceneVideoExport video;
    private final ExportSettings settings;
    private boolean cardOpen;
    private float cardTop;
    private float formatTop;
    private float sizeTop;
    private float rateTop;
    private float buttonTop;
    private float left;
    private float width;

    SceneExports(SceneBlenderExport blender, SceneVideoExport video, ExportSettings settings) {
        this.blender = blender;
        this.video = video;
        this.settings = settings;
    }

    static String label(SceneFormat format) {
        return format == SceneFormat.MP4 ? SceneVideoExport.LABEL : SceneBlenderExport.LABEL;
    }

    static int sizeIndex(int width, int height) {
        for (var index = 0; index < SIZES.length; index++) {
            if (SIZES[index][0] == width && SIZES[index][1] == height) {
                return index;
            }
        }
        return -1;
    }

    static int rateIndex(int rate) {
        for (var index = 0; index < RATES.length; index++) {
            if (RATES[index] == rate) {
                return index;
            }
        }
        return -1;
    }

    static String detail(SceneFormat format, int width, int height, int rate) {
        if (format != SceneFormat.MP4) {
            return "Blender";
        }
        var size = sizeIndex(width, height);
        return "MP4 · " + (size < 0 ? width + " x " + height : SIZE_LABELS[size]) + " · " + rate + " fps";
    }

    void drawCard(@Nullable Scene scene) {
        cardTop = ImGui.getCursorScreenPosY();
        var busy = blender.isBusy() || video.isBusy();
        cardOpen = GeodataWidgets.disclosureCard(
                        "scene-export",
                        Glyph.GO,
                        "Export",
                        detail(
                                settings.getSceneFormat(),
                                settings.getVideoWidth(),
                                settings.getVideoHeight(),
                                settings.getVideoRate()),
                        busy ? GeodataWidgets.ACCENT : GeodataWidgets.MUTED,
                        cardOpen || busy)
                || busy;
        GeodataWidgets.tip("Writes this scene for Blender or as an MP4 video.");
        if (cardOpen) {
            draw(scene);
        } else {
            formatTop = 0;
            buttonTop = 0;
        }
    }

    void draw(@Nullable Scene scene) {
        var format = settings.getSceneFormat();
        space(GeodataWidgets.ROW);
        ImGui.beginDisabled(blender.isBusy() || video.isBusy());
        left = ImGui.getCursorScreenPosX();
        width = ImGui.getContentRegionAvailX();
        formatTop = ImGui.getCursorScreenPosY();
        var picked = GeodataWidgets.segmented("scene-format", FORMATS, format.ordinal());
        GeodataWidgets.tip(FORMAT_TIPS[GeodataWidgets.hoveredSegment(left, width, FORMATS.length)]);
        if (picked >= 0) {
            settings.setSceneFormat(SceneFormat.values()[picked]);
            format = settings.getSceneFormat();
        }
        if (format == SceneFormat.MP4) {
            drawVideoChoices();
        }
        ImGui.endDisabled();
        buttonTop = ImGui.getCursorScreenPosY() + GeodataWidgets.ROW;
        if (format == SceneFormat.MP4) {
            video.draw(scene);
        } else {
            blender.draw(scene);
        }
    }

    void close() {
        video.close();
    }

    private void drawVideoChoices() {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Size");
        sizeTop = ImGui.getCursorScreenPosY();
        var size = GeodataWidgets.segmented(
                "scene-video-size", SIZE_LABELS, sizeIndex(settings.getVideoWidth(), settings.getVideoHeight()));
        var hovered = SIZES[GeodataWidgets.hoveredSegment(left, width, SIZES.length)];
        GeodataWidgets.tip(hovered[0] + " by " + hovered[1] + " pixels, also on a smaller screen.");
        if (size >= 0) {
            settings.videoSize(SIZES[size][0], SIZES[size][1]);
        }
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Frames a second");
        rateTop = ImGui.getCursorScreenPosY();
        var rate = GeodataWidgets.segmented("scene-video-rate", RATE_LABELS, rateIndex(settings.getVideoRate()));
        GeodataWidgets.tip("The motion of the film: 60 is smoother and takes twice as long to make.");
        if (rate >= 0) {
            settings.setVideoRate(RATES[rate]);
        }
    }
}

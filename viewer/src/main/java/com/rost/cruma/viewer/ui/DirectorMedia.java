package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.PostEffects;
import com.rost.cruma.core.world.PostEffects.Effect;
import com.rost.cruma.core.world.SceneFit;
import com.rost.cruma.core.world.SoundSpans.Kind;
import com.rost.cruma.viewer.ui.DirectorCatalog.Media;
import com.rost.cruma.viewer.ui.DirectorCatalog.Row;
import com.rost.cruma.viewer.ui.DirectorCatalog.Slide;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.type.ImString;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class DirectorMedia {
    static final String NEW_LINE = "A line of its own";
    private static final int FILTER_ROOM = 64;
    private static final float THUMBNAIL = 48;

    private final ImString clipFilter = new ImString(FILTER_ROOM);
    private final ImString lineFilter = new ImString(FILTER_ROOM);
    private final ImString slideFilter = new ImString(FILTER_ROOM);
    private final ImString postFilter = new ImString(FILTER_ROOM);
    private final SlideThumbnails thumbnails = new SlideThumbnails();

    static String postName(Effect row) {
        var name = row.name().isEmpty() ? "" : " · " + row.name();
        return switch (row.type()) {
            case PostEffects.MOTION_BLUR ->
                String.format(
                                Locale.ROOT,
                                "%s in %.1f s",
                                row.blurAlpha() >= 255 ? "No motion blur" : "Motion blur " + row.blurAlpha(),
                                row.blurMillis() / 1000)
                        + name;
            case PostEffects.YCBCR ->
                (row.mode() == 2
                                ? "Ends a colour change"
                                : String.format(Locale.ROOT, "Colour change in %.1f s", row.seconds()))
                        + name;
            default -> "Colour row of type " + row.type() + name;
        };
    }

    static boolean playsPost(Effect row) {
        return row.type() == PostEffects.MOTION_BLUR || row.type() == PostEffects.YCBCR;
    }

    SlideThumbnails getThumbnails() {
        return thumbnails;
    }

    @Nullable
    String clipList(Kind kind, String id, String preview, int color, Media media, float width) {
        var rows = switch (kind) {
            case TRACK -> media.tracks();
            case VOICE -> media.voices();
            case SOUND -> media.sounds();
        };
        ImGui.beginDisabled(rows.isEmpty());
        var filter = GeodataWidgets.searchList(id, preview, color, clipFilter, "Search by name", width);
        String chosen = null;
        if (filter != null) {
            for (var row : DirectorCatalog.matching(rows, filter)) {
                if (GeodataWidgets.listItem(Integer.toString(row.id()), row.name(), "", false)) {
                    chosen = row.name();
                }
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
        return chosen;
    }

    @Nullable
    Row drawLineList(String id, String preview, int color, Media media, float width) {
        ImGui.beginDisabled(media.lines().isEmpty());
        var filter =
                GeodataWidgets.searchList(id, preview, color, lineFilter, "Search lines by words or number", width);
        Row chosen = null;
        if (filter != null) {
            if (filter.isEmpty() && GeodataWidgets.listItem("own", NEW_LINE, "", false)) {
                chosen = Row.of(0, NEW_LINE, "");
            }
            var room = ImGui.getContentRegionAvailX();
            for (var row : DirectorCatalog.matching(media.lines(), filter)) {
                var number = Integer.toString(row.id());
                var label = HudWidgets.truncateToWidth(
                        row.name(), room - ImGui.calcTextSizeX(number) - GeodataWidgets.ROW * 2);
                if (GeodataWidgets.listItem(number, label, number, false)) {
                    chosen = row;
                }
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
        return chosen;
    }

    @Nullable
    Slide drawSlideList(String id, String preview, int color, Media media, float width) {
        ImGui.beginDisabled(media.slides().isEmpty());
        var filter = GeodataWidgets.searchList(id, preview, color, slideFilter, "Search slides by name", width);
        Slide chosen = null;
        if (filter != null) {
            for (var slide : media.slides()) {
                if (filter.isEmpty() || slide.name().toLowerCase(Locale.ROOT).contains(filter)) {
                    var texture = thumbnails.getTexture(slide.name(), slide.partWidth(), slide.partHeight());
                    if (GeodataWidgets.pictureItem(
                            slide.name(), texture, Glyph.CLAPPERBOARD, slide.shortName(), slide.size(), THUMBNAIL)) {
                        chosen = slide;
                    }
                }
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
        return chosen;
    }

    @Nullable
    Row postList(String id, String preview, int color, Media media, float width) {
        ImGui.beginDisabled(media.posts().isEmpty());
        var filter = GeodataWidgets.searchList(id, preview, color, postFilter, "Search rows by words or number", width);
        Row chosen = null;
        if (filter != null) {
            for (var row : DirectorCatalog.matching(media.posts(), filter)) {
                if (GeodataWidgets.listItem(
                        Integer.toString(row.id()), row.name(), Integer.toString(row.id()), false)) {
                    chosen = row;
                }
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
        return chosen;
    }
}

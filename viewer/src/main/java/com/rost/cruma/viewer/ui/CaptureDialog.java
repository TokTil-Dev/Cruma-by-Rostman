package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.viewer.config.CaptureRadarLayout;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.OptionsModel;
import com.rost.cruma.viewer.ui.GeodataWidgets.FooterButton;
import com.rost.cruma.viewer.ui.GeodataWidgets.Picture;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.typesafe.config.ConfigList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class CaptureDialog {
    public static final String TITLE = "Capture map images";

    static final List<String> TILE_IMAGES = List.of(
            "capture.output.write_tiles",
            "capture.output.format",
            "capture.output.jpeg_quality",
            "capture.output.tile_resolution");

    static final List<String> RADAR_MAP = List.of(
            "capture.output.radar.enabled",
            "capture.output.radar.file",
            "capture.output.radar.format",
            "capture.output.radar.jpeg_quality",
            "capture.output.radar.tile_resolution");

    static final List<String> OUTPUT = List.of(
            "capture.output.directory",
            "capture.output.client_subfolder",
            "capture.output.delete_old_images",
            "capture.output.open_folder_on_complete");

    static final List<String> PATHS;

    static {
        var paths = new ArrayList<String>();
        paths.addAll(TILE_IMAGES);
        paths.addAll(RADAR_MAP);
        paths.addAll(OUTPUT);
        paths.add(TileViewsEditor.PATH);
        PATHS = List.copyOf(paths);
    }

    private static final float WIDTH = 640;
    private static final float VIEWS_WIDTH = 1040;
    private static final float MARGIN = 32;

    private static final float BODY = 620;

    private static final float PICTURE = 40;

    private static final String RADAR = "capture.output.radar.enabled";
    private static final String FORMAT = "capture.output.format";
    private static final String RADAR_FORMAT = "capture.output.radar.format";
    private static final String RADAR_RESOLUTION = "capture.output.radar.tile_resolution";

    private final ConfigLoader loader;
    private final Map<String, ImString> text = new HashMap<>();
    private final Map<String, String> invalid = new HashMap<>();
    private final Map<String, float[]> places = new HashMap<>();
    private @Nullable OptionsModel model;
    private @Nullable SettingRows rows;
    private @Nullable TileViewsEditor views;
    private boolean viewsPage;
    private boolean asking;
    private boolean popupBefore;
    private boolean saved;
    private boolean scrollToTop;
    private float firstPageHeight;
    private float top;
    private float placedFor;
    private String error = "";
    private String shownArea = "";
    private @Nullable CaptureRadarLayout radar;

    public record Area(
            Picture picture,
            String title,
            String detail,
            String events,
            String eventsTip,
            List<String> tiles,
            MapEventState mapEvents) {}

    public enum Result {
        CLOSED,
        OPEN,
        CANCELLED,
        STARTED
    }

    public CaptureDialog(ConfigLoader loader) {
        this.loader = loader;
    }

    public void open(Path client, Collection<String> tiles) throws IOException {
        var opened = new OptionsModel(loader, client);
        text.clear();
        invalid.clear();
        model = opened;
        rows = new SettingRows(opened, loader.getConfigDirectory(), text, invalid, places, () -> error = "");
        views = new TileViewsEditor(opened, () -> error = "");
        views.setClientTiles(Set.copyOf(tiles));
        viewsPage = false;
        asking = false;
        popupBefore = false;
        firstPageHeight = 0;
        placedFor = 0;
        saved = false;
        scrollToTop = true;
        error = "";
    }

    public boolean isSaved() {
        return saved;
    }

    public Map<String, float[]> getPlaces() {
        return Map.copyOf(places);
    }

    public Result draw(float width, float height, FontManager fonts, Area area) {
        var model = this.model;
        var rows = this.rows;
        var views = this.views;
        if (model == null || rows == null || views == null) {
            return Result.CLOSED;
        }
        views.setClientEvents(area.mapEvents());
        var fieldHadKeys = ImGui.getIO().getWantTextInput();
        var dialogWidth = Math.min(viewsPage ? VIEWS_WIDTH : WIDTH, width - MARGIN);
        var most = height - MARGIN - GeodataWidgets.DIALOG_CHROME;
        var firstBody = Math.min(firstPageHeight > 0 ? firstPageHeight : BODY, most);
        var body = viewsPage ? most : firstBody;
        var dialogHeight = body + GeodataWidgets.DIALOG_CHROME;
        if (firstPageHeight == 0 || placedFor != height) {
            top = (height - firstBody - GeodataWidgets.DIALOG_CHROME) * .5f;
            placedFor = firstPageHeight == 0 ? 0 : height;
        }
        var shownTop = Math.clamp(top, MARGIN * .5f, Math.max(MARGIN * .5f, height - MARGIN * .5f - dialogHeight));
        ImGui.setNextWindowPos(width * .5f, shownTop, ImGuiCond.Always, .5f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, GeodataWidgets.DIALOG_SIDE, GeodataWidgets.DIALOG_EDGE);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 9, 7);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 7, GeodataWidgets.ROW);
        ImGui.pushStyleColor(ImGuiCol.PopupBg, ImGui.getColorU32(ImGuiCol.WindowBg));
        var visible = GeodataWidgets.beginDialog(TITLE, dialogWidth, dialogHeight);
        ImGui.popStyleColor();
        if (!visible) {
            ImGui.popStyleVar(3);
            return Result.CLOSED;
        }
        var popupAbove = GeodataWidgets.isPopupOver(popupBefore);
        places.clear();
        places.put("capture", new float[] {
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowPosX() + ImGui.getWindowWidth(),
            ImGui.getWindowPosY() + ImGui.getWindowHeight()
        });
        var close = drawTitle(fonts);
        var bodyTop = ImGui.getCursorPosY();
        if (scrollToTop) {
            ImGui.setNextWindowScroll(-1, 0);
            scrollToTop = false;
        }
        if (viewsPage) {
            ImGui.beginChild("##capture-views", 0, body, 0, 0);
            views.draw();
            ImGui.endChild();
        } else {
            GeodataWidgets.beginDialogPanel(
                    "##capture-body", ImGui.getContentRegionAvailX() + GeodataWidgets.DIALOG_SIDE * .5f, body);
            drawFirstPage(model, rows, area);
            firstPageHeight = Math.max(firstPageHeight, (float)
                    Math.ceil(ImGui.getCursorPosY() - ImGui.getStyle().getItemSpacingY()));
            GeodataWidgets.endDialogPanel();
        }
        rows.drawPickers();
        GeodataWidgets.dialogRule(bodyTop + body);
        var pressed = drawFooter(model, views);
        var escape = GeodataWidgets.isEscapePressed() && !fieldHadKeys && !popupAbove;
        var unsaved =
                model.getChangeCount() + invalid.size() + views.getInvalid().size();
        var result = Result.OPEN;
        if (pressed == FooterAction.START) {
            try {
                if (model.hasChanges()) {
                    model.save();
                    saved = true;
                }
                result = Result.STARTED;
            } catch (IOException | RuntimeException failure) {
                error = failure.getMessage() == null ? "Could not save the settings." : failure.getMessage();
            }
        } else if (pressed == FooterAction.CANCEL || pressed == FooterAction.DROP) {
            result = Result.CANCELLED;
        } else if (pressed == FooterAction.KEEP || (asking && escape)) {
            asking = false;
        } else if (close || escape) {
            asking = unsaved > 0;
            result = asking ? Result.OPEN : Result.CANCELLED;
        } else if (!popupAbove && unsaved == 0 && GeodataWidgets.wasClickedOutside()) {
            result = Result.CANCELLED;
        }
        if (result != Result.OPEN) {
            ImGui.closeCurrentPopup();
            this.model = null;
            this.rows = null;
            this.views = null;
        }
        popupBefore = result == Result.OPEN && GeodataWidgets.isPopupOver(false);
        ImGui.endPopup();
        ImGui.popStyleVar(3);
        return result;
    }

    private enum FooterAction {
        NONE,
        CANCEL,
        START,
        KEEP,
        DROP
    }

    private boolean drawTitle(FontManager fonts) {
        if (!viewsPage) {
            var close = GeodataWidgets.dialogTitle(fonts, TITLE, "close-capture");
            mark("capture.close");
            return close;
        }
        var titleTop = ImGui.getCursorPosY();
        ImGui.setCursorPosY(titleTop + (GeodataWidgets.DIALOG_TITLE - HudWidgets.CONTROL_HEIGHT) * .5f);
        if (HudWidgets.iconButton("capture-back", Glyph.BACK, false, HudWidgets.TEXT)) {
            viewsPage = false;
            scrollToTop = true;
        }
        mark("capture.back");
        GeodataWidgets.tip("Back to Capture map images");
        ImGui.setCursorPos(GeodataWidgets.DIALOG_SIDE + HudWidgets.CONTROL_HEIGHT + GeodataWidgets.ROW, titleTop);
        var close = GeodataWidgets.dialogTitle(fonts, "Tile views", "close-capture");
        mark("capture.close");
        return close;
    }

    private void drawFirstPage(OptionsModel model, SettingRows rows, Area area) {
        GeodataWidgets.section("AREA", 0);
        drawArea(area);
        GeodataWidgets.beginSettingRow("Map events", "", ImGui.calcTextSizeX(area.events()));
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.getWindowDrawList()
                .addText(
                        x,
                        y + (HudWidgets.CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                        ImGui.getColorU32i(GeodataWidgets.MUTED),
                        area.events());
        if (GeodataWidgets.endSettingRow()) {
            HudWidgets.showTooltip(area.eventsTip());
        }
        GeodataWidgets.section("TILE IMAGES");
        for (var path : TILE_IMAGES) {
            if (!path.endsWith(".jpeg_quality") || isJpeg(model, FORMAT)) {
                rows.drawRow(path);
            }
        }
        GeodataWidgets.section("RADAR MAP");
        var radarOn = isOn(model, RADAR);
        for (var path : RADAR_MAP) {
            if (path.equals(RADAR) || (radarOn && (!path.endsWith(".jpeg_quality") || isJpeg(model, RADAR_FORMAT)))) {
                rows.drawRow(path);
            }
        }
        radar = null;
        if (radarOn) {
            try {
                var layout = CaptureRadarDetails.forTiles(readInt(model, RADAR_RESOLUTION), area.tiles());
                radar = layout;
                CaptureRadarDetails.draw(layout);
                rows.mark("capture.radar");
            } catch (RuntimeException failure) {
                GeodataWidgets.note(String.valueOf(failure.getMessage()), GeodataWidgets.WARNING);
            }
        }
        GeodataWidgets.section("OUTPUT");
        for (var path : OUTPUT) {
            rows.drawRow(path);
        }
        var count = ((ConfigList) model.getValue(TileViewsEditor.PATH)).size();
        var top = ImGui.getCursorScreenPosY();
        if (GeodataWidgets.settingChevronRow(
                "capture-tile-views",
                "Tile views",
                "Cuts by height, such as a floor of a tower",
                count == 1 ? "1 view" : count + " views",
                false)) {
            views.selectFirst();
            viewsPage = true;
        }
        rows.markRow("row/" + TileViewsEditor.PATH, top);
        GeodataWidgets.tip(
                LaunchOptionCatalog.presentation(TileViewsEditor.PATH).description());
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(
                "Time of day, lighting and texture detail of the images are in Settings, Capture rendering.");
    }

    private void drawArea(Area area) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        GeodataWidgets.picture(draw, x, y, PICTURE, area.picture(), false);
        var left = x + PICTURE + GeodataWidgets.BLOCK;
        var room = ImGui.getContentRegionAvailX() - PICTURE - GeodataWidgets.BLOCK;
        var lineGap = (PICTURE - 2 * ImGui.getFontSize()) / 3;
        draw.addText(left, y + lineGap, HudWidgets.TEXT, HudWidgets.truncateToWidth(area.title(), room));
        draw.addText(
                left,
                y + 2 * lineGap + ImGui.getFontSize(),
                HudWidgets.MUTED,
                HudWidgets.truncateToWidth(area.detail(), room));
        shownArea = area.detail();
        places.put("capture.area", new float[] {x, y, left + room, y + PICTURE});
        ImGui.setCursorScreenPos(x, y + PICTURE);
        ImGui.dummy(0, 0);
    }

    private FooterAction drawFooter(OptionsModel model, TileViewsEditor views) {
        var wrong = invalid.size() + views.getInvalid().size();
        var actions = new ArrayList<FooterAction>();
        var buttons = new ArrayList<FooterButton>();
        String status;
        int color;
        if (asking) {
            var count = model.getChangeCount() + wrong;
            status = "Drop " + count + (count == 1 ? " unsaved change?" : " unsaved changes?");
            color = GeodataWidgets.WARNING;
            actions.add(FooterAction.KEEP);
            buttons.add(new FooterButton("capture-keep", null, "Keep editing", buttonWidth("Keep editing"), true));
            actions.add(FooterAction.DROP);
            buttons.add(new FooterButton("capture-drop", null, "Drop changes", buttonWidth("Drop changes"), false));
        } else {
            status = !error.isEmpty()
                    ? error
                    : wrong > 0
                            ? "Fix " + wrong + (wrong == 1 ? " invalid value" : " invalid values")
                                    + " before the capture."
                            : radar == null ? "" : CaptureRadarDetails.summarize(radar);
            color = !error.isEmpty() || wrong > 0 ? GeodataWidgets.WARNING : GeodataWidgets.MUTED;
            actions.add(FooterAction.CANCEL);
            buttons.add(new FooterButton("cancel-capture", null, "Cancel", buttonWidth("Cancel"), false));
            actions.add(FooterAction.START);
            buttons.add(new FooterButton(
                    "start-capture", Glyph.PLAY, "Start capture", 150, true, wrong == 0, wrong == 0 ? "" : status));
        }
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var right = x + ImGui.getContentRegionAvailX();
        var buttonTop = y + (GeodataWidgets.FOOTER - 32) * .5f;
        for (var index = buttons.size() - 1; index >= 0; index--) {
            var button = buttons.get(index);
            places.put(
                    "capture." + actions.get(index).name().toLowerCase(Locale.ROOT),
                    new float[] {right - button.width(), buttonTop, right, buttonTop + 32});
            right -= button.width() + GeodataWidgets.ROW;
        }
        var pressed = GeodataWidgets.footer(status, color, buttons);
        return pressed < 0 ? FooterAction.NONE : actions.get(pressed);
    }

    private void mark(String name) {
        places.put(name, new float[] {
            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
        });
    }

    private static boolean isOn(OptionsModel model, String path) {
        return Boolean.TRUE.equals(model.getValue(path).unwrapped());
    }

    private static boolean isJpeg(OptionsModel model, String path) {
        return SettingRows.choiceKey(model.getValue(path).unwrapped().toString())
                .equals("jpg");
    }

    private static int readInt(OptionsModel model, String path) {
        return ((Number) model.getValue(path).unwrapped()).intValue();
    }

    private static float buttonWidth(String label) {
        return Math.max(64, ImGui.calcTextSizeX(label) + 32);
    }
}

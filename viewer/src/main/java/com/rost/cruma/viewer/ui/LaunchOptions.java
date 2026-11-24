package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.AgentConnect;
import com.rost.cruma.viewer.config.AgentSettings;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.InterfaceScale;
import com.rost.cruma.viewer.config.OptionsModel;
import com.rost.cruma.viewer.config.OptionsModel.Field;
import com.rost.cruma.viewer.launch.ClientFolders;
import com.rost.cruma.viewer.launch.RecentClients;
import com.rost.cruma.viewer.ui.GeodataBuildControls.Outcome;
import com.rost.cruma.viewer.ui.GeodataWidgets.FooterButton;
import com.rost.cruma.viewer.ui.GeodataWidgets.LineLook;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueFactory;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class LaunchOptions {
    public static final String TITLE = "Settings";

    private static final float WIDTH = 940;
    private static final float HEIGHT = 720;
    private static final float MARGIN = 24;

    private static final float NAV_WIDTH = 200;
    private static final float NAV_GAP = 4;

    private static final float PAGE_GAP = 24;
    private static final float SEARCH_WIDTH = 280;

    private static final float FOLDER_FIELD = 260;
    private static final float BOUND_FIELD = 72;

    private static final float SHOW_FOLDER = 132;

    private static final float LOCATIONS_LEAST = 124;
    private static final float LOCATION_ROW = 24;

    private static final String LOCATIONS = "viewer.camera.locations";

    private static final String RESET_TIP = "Puts the settings of this page back to their defaults. Folders and named"
            + " locations are kept. Nothing is saved until you press Save.";
    private static final String UNSAVED_TIP = "Save or cancel your changes first.";

    private static @Nullable String lastPage;

    public enum Host {
        MENU,
        VIEWER
    }

    private final OptionsModel model;
    private final List<Field> fields;
    private final Path configDirectory;
    private final Host host;
    private final @Nullable Path client;
    private final String clientLabel;
    private final GeodataBuildControls geodataBuild = new GeodataBuildControls();
    private Outcome geodataBuildOutcome;

    private boolean buildOpen;

    private List<GeoTile> selectedTiles = List.of();
    private final Map<String, ImString> text = new HashMap<>();
    private final Map<String, String> invalid = new HashMap<>();
    private final Map<String, float[]> places = new HashMap<>();
    private final SettingRows rows;
    private final ImString search = new ImString(128);
    private String page;
    private String error = "";
    private boolean saved;
    private boolean reloadRequested;
    private boolean mapImageRequested;
    private boolean closeRequested;
    private boolean closeAsked;
    private boolean asking;
    private boolean scrollToTop;
    private float pageHeight;
    private final ImString locationSearch = new ImString(128);
    private int selectedLocation;
    private @Nullable AgentConnect agentConnect;
    private final @Nullable ClientTablesPage tablesPage;

    private final LatestAnswer<String, ClientFolderStatus> geodataStatus;
    private final LatestAnswer<String, ClientFolderStatus> scriptStatus;
    private final LatestAnswer<WorldVariant, Boolean> mapImage;
    private @Nullable WorldVariant shownWorld;
    private boolean popupBefore;

    public LaunchOptions(ConfigLoader loader, Host host, @Nullable Path client) throws IOException {
        model = new OptionsModel(loader, client);
        configDirectory = loader.getConfigDirectory();
        var base = configDirectory;
        var chosen = model.getSelectedClient();
        geodataStatus = new LatestAnswer<>(
                typed -> ClientFolderStatus.ofGeodata(ClientFolderStatus.resolve(typed, base)),
                ClientFolderStatus.NONE);
        scriptStatus = new LatestAnswer<>(
                typed -> ClientFolderStatus.ofScripts(ClientFolderStatus.resolve(typed, base)),
                ClientFolderStatus.NONE);
        mapImage = new LatestAnswer<>(world -> ClientFolderStatus.isMapImageReady(chosen, world, base), null);
        rows = new SettingRows(model, configDirectory, text, invalid, places, () -> error = "");
        fields = model.getFields();
        this.host = host;
        this.client = model.getSelectedClient();
        clientLabel = this.client == null ? "" : labelOf(this.client, configDirectory);
        tablesPage = this.client == null ? null : new ClientTablesPage(this.client, configDirectory, places);
        var last = lastPage;
        var clientsPage =
                last != null && (last.equals(LaunchOptionCatalog.CLIENT) || last.equals(LaunchOptionCatalog.TABLES));
        page = last == null || (clientsPage && this.client == null)
                ? (this.client == null ? LaunchOptionCatalog.GRAPHICS : LaunchOptionCatalog.CLIENT)
                : last;
    }

    public void selectTiles(List<String> names) {
        var tiles = new ArrayList<GeoTile>();
        for (var name : names) {
            var parts = name.split("_");
            if (parts.length != 2) {
                continue;
            }
            try {
                tiles.add(new GeoTile(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            } catch (NumberFormatException notTile) {
            }
        }
        selectedTiles = List.copyOf(tiles);
    }

    public void showClientPage() {
        if (client != null) {
            page = LaunchOptionCatalog.CLIENT;
            search.set("");
            scrollToTop = true;
        }
    }

    public void showTablesPage() {
        if (client != null) {
            page = LaunchOptionCatalog.TABLES;
            search.set("");
            scrollToTop = true;
        }
    }

    public boolean draw(float width, float height, FontManager fonts) {
        var fieldHadKeys = ImGui.getIO().getWantTextInput();
        var dialogWidth = Math.min(WIDTH, width - MARGIN);
        var dialogHeight = Math.min(HEIGHT, height - MARGIN);
        ImGui.setNextWindowPos(width * .5f, height * .5f, ImGuiCond.Always, .5f, .5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, GeodataWidgets.DIALOG_SIDE, GeodataWidgets.DIALOG_EDGE);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 9, 7);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 7, GeodataWidgets.ROW);
        ImGui.pushStyleColor(ImGuiCol.PopupBg, ImGui.getColorU32(ImGuiCol.WindowBg));
        var visible = GeodataWidgets.beginDialog(TITLE, dialogWidth, dialogHeight);
        ImGui.popStyleColor();
        if (!visible) {
            ImGui.popStyleVar(3);
            return false;
        }
        var popupAbove = GeodataWidgets.isPopupOver(popupBefore);
        followWorld();
        var windowX = ImGui.getWindowPosX();
        var windowY = ImGui.getWindowPosY();
        var windowWidth = ImGui.getWindowWidth();
        var windowHeight = ImGui.getWindowHeight();
        places.clear();
        places.put("dialog", new float[] {windowX, windowY, windowX + windowWidth, windowY + windowHeight});
        var titleTop = ImGui.getCursorPosY();
        var close = GeodataWidgets.dialogTitle(fonts, TITLE, "settings-close");
        mark("close");
        var controlTop = titleTop + (GeodataWidgets.DIALOG_TITLE - HudWidgets.CONTROL_HEIGHT) * .5f;
        var searchRight = windowWidth - GeodataWidgets.DIALOG_SIDE - HudWidgets.CONTROL_HEIGHT - GeodataWidgets.BLOCK;
        var searchWidth = Math.min(
                SEARCH_WIDTH,
                searchRight - GeodataWidgets.DIALOG_SIDE - 2 * ImGui.calcTextSizeX(TITLE) - GeodataWidgets.BLOCK);
        ImGui.setCursorPos(searchRight - searchWidth, controlTop);
        if (GeodataWidgets.searchField(
                "##settings-search", "Search every setting", search, searchWidth, false, Glyph.SEARCH)) {
            scrollToTop = true;
        }
        mark("search");
        var ruleY = controlTop + HudWidgets.CONTROL_HEIGHT + GeodataWidgets.BLOCK;
        var footerRuleY = windowHeight - GeodataWidgets.DIALOG_EDGE - GeodataWidgets.FOOTER - GeodataWidgets.BLOCK - 1;
        var dividerX = GeodataWidgets.DIALOG_SIDE + NAV_WIDTH;
        var rules = ImGui.getWindowDrawList();
        var ruleColor = ImGui.getColorU32(ImGuiCol.Separator);
        rules.pushClipRect(windowX, windowY, windowX + windowWidth, windowY + windowHeight, false);
        rules.addLine(windowX, windowY + ruleY, windowX + windowWidth, windowY + ruleY, ruleColor, 1);
        rules.addLine(windowX, windowY + footerRuleY, windowX + windowWidth, windowY + footerRuleY, ruleColor, 1);
        rules.addLine(windowX + dividerX, windowY + ruleY, windowX + dividerX, windowY + footerRuleY, ruleColor, 1);
        rules.popClipRect();
        var bodyTop = ruleY + 1;
        var bodyHeight = footerRuleY - bodyTop;
        pageHeight = bodyHeight;
        ImGui.setCursorPos(GeodataWidgets.DIALOG_SIDE - GeodataWidgets.LINE_INSET, bodyTop);
        drawNavigation(bodyHeight);
        var pageLeft = dividerX + PAGE_GAP;
        ImGui.setCursorPos(pageLeft, bodyTop);
        if (scrollToTop) {
            ImGui.setNextWindowScroll(-1, 0);
            scrollToTop = false;
        }
        GeodataWidgets.beginDialogPanel(
                "##settings-page", windowWidth - GeodataWidgets.DIALOG_SIDE * .5f - pageLeft, bodyHeight);
        ImGui.setCursorPosY(GeodataWidgets.SECTION);
        drawPage(fonts);
        rows.drawPickers();
        GeodataWidgets.space(GeodataWidgets.SECTION);
        GeodataWidgets.endDialogPanel();
        ImGui.setCursorPos(GeodataWidgets.DIALOG_SIDE, footerRuleY + 1 + GeodataWidgets.BLOCK);
        var pressed = drawFooter();
        var escape = GeodataWidgets.isEscapePressed() && !fieldHadKeys && !popupAbove;
        close |= closeAsked;
        closeAsked = false;
        var open = true;
        if (pressed == FooterAction.REVEAL) {
            revealError();
        } else if (pressed == FooterAction.SAVE || pressed == FooterAction.RELOAD) {
            try {
                if (model.hasChanges()) {
                    model.save();
                    saved = true;
                }
                reloadRequested = pressed == FooterAction.RELOAD;
                open = false;
            } catch (IOException | RuntimeException failure) {
                error = failure.getMessage() == null ? "Could not save settings." : failure.getMessage();
            }
        } else if (pressed == FooterAction.CANCEL || pressed == FooterAction.DROP || closeRequested) {
            open = false;
        } else if (pressed == FooterAction.KEEP || (asking && escape)) {
            asking = false;
        } else if (close || escape) {
            asking = countUnsaved() > 0;
            open = asking;
        } else if (!popupAbove && countUnsaved() == 0 && GeodataWidgets.wasClickedOutside()) {
            open = false;
        }
        if (!open) {
            ImGui.closeCurrentPopup();
        }
        popupBefore = open && GeodataWidgets.isPopupOver(false);
        lastPage = page;
        ImGui.endPopup();
        ImGui.popStyleVar(3);
        return open;
    }

    public boolean isSaved() {
        return saved;
    }

    public boolean isReloadRequested() {
        return reloadRequested;
    }

    public boolean hasUnsavedChanges() {
        return countUnsaved() > 0;
    }

    public void requestClose() {
        closeAsked = true;
    }

    public boolean isMapImageRequested() {
        return mapImageRequested;
    }

    public InterfaceScale getInterfaceScale() {
        return rows.getInterfaceScale();
    }

    public void setAutomaticScalePercent(int percent) {
        rows.setAutomaticScalePercent(percent);
    }

    private enum FooterAction {
        NONE,
        REVEAL,
        CANCEL,
        SAVE,
        RELOAD,
        KEEP,
        DROP
    }

    private void drawNavigation(float height) {
        GeodataWidgets.beginDialogPanel("##settings-nav", NAV_WIDTH, height);
        var titles = (client == null ? 1 : 2) * (ImGui.getFontSize() + GeodataWidgets.ROW);
        var rows = LaunchOptionCatalog.PAGES.size() + (client == null ? 0 : 2);
        var room = height - (client == null ? 1 : 2) * GeodataWidgets.SECTION - titles;
        var gap = rows * (GeodataWidgets.LINE_ROW + NAV_GAP) <= room ? NAV_GAP : 0;
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, gap);
        var top = GeodataWidgets.SECTION;
        if (client != null) {
            drawNavTitle("THIS CLIENT", top);
            drawNavItem(LaunchOptionCatalog.CLIENT, Glyph.FOLDER, clientLabel);
            drawNavItem(LaunchOptionCatalog.TABLES, Glyph.TABLE, LaunchOptionCatalog.TABLES);
            top = ImGui.getCursorPosY() - gap + GeodataWidgets.SECTION;
        }
        drawNavTitle("CRUMA", top);
        for (var each : LaunchOptionCatalog.PAGES) {
            drawNavItem(each, null, each);
        }
        ImGui.popStyleVar();
        GeodataWidgets.space(GeodataWidgets.SECTION);
        GeodataWidgets.endDialogPanel();
    }

    private void drawNavTitle(String title, float top) {
        ImGui.setCursorPos(GeodataWidgets.LINE_INSET, top);
        ImGui.textColored(GeodataWidgets.MUTED, title);
        ImGui.setCursorPos(0, top + ImGui.getFontSize() + GeodataWidgets.ROW);
    }

    private void drawNavItem(String target, @Nullable Glyph glyph, String label) {
        var current = search.get().isBlank() && page.equals(target);
        if (GeodataWidgets.lineRow(
                "nav/" + target, glyph, label, "", current ? LineLook.CURRENT : LineLook.PLAIN, false)) {
            page = target;
            search.set("");
            scrollToTop = true;
        }
        mark("nav/" + target);
        if (target.equals(LaunchOptionCatalog.CLIENT) && client != null) {
            GeodataWidgets.tip(client.toString());
        }
    }

    private void drawPage(FontManager fonts) {
        if (!search.get().isBlank()) {
            drawSearch(fonts);
        } else if (page.equals(LaunchOptionCatalog.CLIENT) && client != null) {
            drawClientPage(fonts);
        } else if (page.equals(LaunchOptionCatalog.TABLES) && tablesPage != null) {
            drawTablesPage(fonts, tablesPage);
        } else if (page.equals(LaunchOptionCatalog.AI_AGENT)) {
            drawHeader(fonts, page, "", LaunchOptionCatalog.fieldsOn(fields, page));
            drawAgentPage(fonts);
        } else {
            var shown = LaunchOptionCatalog.fieldsOn(fields, page);
            drawHeader(fonts, page, intro(page), shown);
            drawFields(shown, false);
        }
    }

    private void drawHeader(FontManager fonts, String title, String description, @Nullable List<Field> resetFields) {
        if (drawHeader(fonts, title, description, resetFields == null ? null : "Reset page", true, RESET_TIP)
                && resetFields != null) {
            resetPage(resetFields);
        }
    }

    private boolean drawHeader(
            FontManager fonts,
            String title,
            String description,
            @Nullable String action,
            boolean enabled,
            String actionTip) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var resetWidth = action == null ? 0 : ImGui.calcTextSizeX(action) + 24;
        var titleRoom = action == null ? width : width - resetWidth - GeodataWidgets.ROW;
        var size = GeodataWidgets.DIALOG_TITLE;
        ImGui.pushFont(fonts.getHeadingFont((int) size), size);
        ImGui.getWindowDrawList().addText(x, y, HudWidgets.TEXT, HudWidgets.truncateToWidth(title, titleRoom));
        ImGui.popFont();
        var pressed = false;
        if (action != null) {
            ImGui.setCursorScreenPos(x + width - resetWidth, y + (size - HudWidgets.CONTROL_HEIGHT) * .5f);
            ImGui.beginDisabled(!enabled);
            pressed = HudWidgets.button(
                    "settings-reset", null, action, resetWidth, HudWidgets.CONTROL_HEIGHT, false, false);
            ImGui.endDisabled();
            mark("reset");
            GeodataWidgets.tip(actionTip);
        }
        var bottom = y + size;
        if (!description.isEmpty()) {
            ImGui.setCursorScreenPos(x, bottom + 6);
            GeodataWidgets.hint(description);
            bottom = ImGui.getItemRectMaxY();
        }
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
        return pressed;
    }

    private void drawTablesPage(FontManager fonts, ClientTablesPage tables) {
        var world = model.getVariant();
        var checking = tables.isChecking(world);
        if (drawHeader(
                fonts,
                LaunchOptionCatalog.TABLES,
                "How Cruma reads the tables of this client, its system/*.dat files, and which schema read each.",
                "Check again",
                !checking,
                checking
                        ? "Cruma is reading the tables now."
                        : "Reads every table of this client again, with the schema files as they are now.")) {
            tables.checkAgain(world);
        }
        tables.draw(world, fonts);
    }

    private void drawClientPage(FontManager fonts) {
        drawHeader(fonts, clientLabel, "What Cruma keeps for this client. Every client has its own.", null);
        GeodataWidgets.section("WORLD");
        drawField(fieldOf(ClientProfiles.VARIANT));
        GeodataWidgets.section("MAP EVENTS");
        GeodataWidgets.hint(sectionHint(LaunchOptionCatalog.CLIENT, "Map events"));
        drawField(fieldOf(ClientProfiles.TIME_WINDOWS));
        drawField(fieldOf(ClientProfiles.CASTLE_SIDES));
        GeodataWidgets.section("SERVER DATA");
        drawGeodataRow();
        GeodataWidgets.space(GeodataWidgets.ROW + GeodataWidgets.ROW);
        drawScriptsRow();
        GeodataWidgets.section("CLIENT FOLDER");
        drawClientFolderRow();
        drawMapImageRow();
    }

    private void followWorld() {
        var world = model.getVariant();
        if (shownWorld != null && world != shownWorld) {
            for (var path : List.of(ClientProfiles.GEODATA_PATH, ClientProfiles.SCRIPT_PATH)) {
                text.remove(path);
                invalid.remove(path);
            }
        }
        shownWorld = world;
    }

    private void drawGeodataRow() {
        var path = ClientProfiles.GEODATA_PATH;
        drawFolderRow(
                path,
                "Geodata folder",
                "For the Geodata tool",
                List.of("Folder with *_conv.dat or *.l2j files", "*_conv.dat or *.l2j files", "*.dat or *.l2j"),
                geodataStatus.get(rows.getText(path).get()));
        if (client != null) {
            drawGeodataBuild(path, client);
        }
    }

    private void drawScriptsRow() {
        var path = ClientProfiles.SCRIPT_PATH;
        var input = rows.getText(path);
        drawFolderRow(
                path,
                "Server scripts folder",
                "For NPC Spawns, NPCs and Areas",
                List.of("Folder with npcpos.txt", "npcpos.txt"),
                scriptStatus.get(input.get()));
    }

    private void drawFolderRow(
            String path, String label, String detail, List<String> hints, ClientFolderStatus status) {
        var input = rows.getText(path);
        var available = ImGui.getContentRegionAvailX();
        var statusColumn = 0f;
        for (var longest : ClientFolderStatus.LONGEST) {
            statusColumn = Math.max(statusColumn, GeodataWidgets.statusWidth(longest, GeodataWidgets.WARNING));
        }
        var fieldWidth = Math.max(140, Math.min(FOLDER_FIELD, available * .5f - statusColumn - GeodataWidgets.ROW));
        var controlWidth = fieldWidth + GeodataWidgets.ROW + statusColumn;
        var room = GeodataWidgets.folderInputRoom(fieldWidth);
        var hint = hints.getLast();
        for (var each : hints) {
            if (ImGui.calcTextSizeX(each) <= room) {
                hint = each;
                break;
            }
        }
        places.put("hint/" + path, new float[] {ImGui.calcTextSizeX(hint), room});
        var top = ImGui.getCursorScreenPosY();
        GeodataWidgets.beginSettingRow(label, detail, controlWidth);
        var fieldLeft = ImGui.getCursorScreenPosX();
        var fieldTop = ImGui.getCursorScreenPosY();
        var before = input.get();
        var field = GeodataWidgets.folderInput(path, hint, input, rows.describe(path), fieldWidth);
        places.put(
                "field/" + path,
                new float[] {fieldLeft, fieldTop, fieldLeft + fieldWidth, fieldTop + ImGui.getFrameHeight()});
        if (!input.get().equals(before)) {
            rows.setText(path, input.get());
        }
        if (field.action()) {
            rows.openPicker(path, input.get());
        }
        if (!status.text().isEmpty()) {
            ImGui.setCursorScreenPos(
                    fieldLeft + controlWidth - GeodataWidgets.statusWidth(status.text(), status.dot()), fieldTop);
            GeodataWidgets.status(status.text(), status.dot());
        }
        var hovered = GeodataWidgets.endSettingRow();
        markRow("row/" + path, top);
        if (hovered && !ImGui.isAnyItemHovered()) {
            HudWidgets.showTooltip(rows.describe(path) + "\n\n" + path);
        }
        rows.drawInvalid(path);
    }

    private void drawClientFolderRow() {
        var label = "Game client";
        var room = ImGui.getContentRegionAvailX()
                - ImGui.calcTextSizeX(label)
                - 2 * GeodataWidgets.BLOCK
                - GeodataWidgets.ROW
                - SHOW_FOLDER;
        var textWidth = Math.clamp(room, 120, FOLDER_FIELD);
        GeodataWidgets.beginSettingRow(label, "", textWidth + GeodataWidgets.ROW + SHOW_FOLDER);
        GeodataWidgets.folderText(client, textWidth);
        ImGui.sameLine(0, GeodataWidgets.ROW);
        GeodataWidgets.showFolder("settings-client-folder", client);
        GeodataWidgets.endSettingRow();
    }

    private void drawMapImageRow() {
        var known = mapImage.get(model.getVariant());
        var menu = host == Host.MENU;
        if (known == null) {
            GeodataWidgets.beginSettingRow("Map image", menu ? "" : "Made from the main menu.", 0);
            GeodataWidgets.endSettingRow();
            return;
        }
        var ready = known;
        var status = ClientFolderStatus.ofMapImage(ready);
        var state = status.text();
        var dot = status.dot();
        var action = ready ? "Rebuild" : "Create";
        var buttonWidth = ImGui.calcTextSizeX(action) + 32;
        var width = GeodataWidgets.statusWidth(state, dot) + (menu ? GeodataWidgets.ROW + buttonWidth : 0);
        GeodataWidgets.beginSettingRow("Map image", menu ? "" : "Made from the main menu.", width);
        GeodataWidgets.status(state, dot);
        if (menu) {
            ImGui.sameLine(0, GeodataWidgets.ROW);
            var unsaved = countUnsaved() > 0;
            ImGui.beginDisabled(unsaved);
            if (HudWidgets.button(
                    "settings-map-image", null, action, buttonWidth, HudWidgets.CONTROL_HEIGHT, false, true)) {
                mapImageRequested = true;
                closeRequested = true;
            }
            ImGui.endDisabled();
            mark("map-image");
            GeodataWidgets.tip(
                    unsaved
                            ? UNSAVED_TIP
                            : ready
                                    ? "Draws the map image of this client again, from its maps as they are now."
                                    : "Draws the map image of this client from its maps, for the map of the start"
                                            + " menu.");
        }
        GeodataWidgets.endSettingRow();
    }

    private void drawFields(List<Field> shown, boolean searching) {
        var section = "";
        var boundsDrawn = new ArrayList<String>();
        for (var field : shown) {
            var path = field.path();
            var fieldPage = LaunchOptionCatalog.pageOf(path);
            var heading = (searching ? pageTitle(fieldPage) + " / " : "") + LaunchOptionCatalog.sectionOf(path);
            if (!heading.equals(section)) {
                GeodataWidgets.section(heading.toUpperCase(Locale.ROOT));
                var hint = searching ? "" : sectionHint(fieldPage, LaunchOptionCatalog.sectionOf(path));
                if (!hint.isEmpty()) {
                    GeodataWidgets.hint(hint);
                }
                section = heading;
            }
            if (LaunchOptionCatalog.isTileBound(path)) {
                var prefix = path.substring(0, path.indexOf(".world.") + ".world.".length());
                if (!boundsDrawn.contains(prefix)) {
                    boundsDrawn.add(prefix);
                    drawTileBounds(prefix);
                }
            } else {
                drawField(field);
            }
        }
    }

    private void drawSearch(FontManager fonts) {
        var query = search.get();
        var shown = LaunchOptionCatalog.search(fields, query, client != null);
        drawHeader(
                fonts,
                "Search",
                shown.isEmpty()
                        ? "No setting matches. Try a shorter search."
                        : shown.size() == 1 ? "1 setting matches." : shown.size() + " settings match.",
                null);
        drawFields(shown, true);
        var moved = LaunchOptionCatalog.countCaptureDialogMatches(fields, query);
        if (moved > 0) {
            GeodataWidgets.space(GeodataWidgets.SECTION);
            GeodataWidgets.hint(moved + " more in Capture map images, in the start menu.");
            mark("moved");
        }
    }

    private void drawField(@Nullable Field field) {
        if (field == null) {
            return;
        }
        var path = field.path();
        if (!path.equals(LOCATIONS)) {
            rows.drawRow(path);
            return;
        }
        ImGui.pushID(path);
        var top = ImGui.getCursorScreenPosY();
        drawLocations(path, (ConfigList) model.getValue(path));
        markRow("row/" + path, top);
        ImGui.popID();
    }

    private void drawTileBounds(String prefix) {
        var minWidth = ImGui.calcTextSizeX("Min");
        var maxWidth = ImGui.calcTextSizeX("Max");
        var width = minWidth + maxWidth + 2 * BOUND_FIELD + 2 * 6 + 16;
        for (String axis : List.of("x", "y")) {
            var top = ImGui.getCursorScreenPosY();
            GeodataWidgets.beginSettingRow(axis.toUpperCase(Locale.ROOT) + " tiles", "", width);
            ImGui.alignTextToFramePadding();
            ImGui.textColored(GeodataWidgets.MUTED, "Min");
            ImGui.sameLine(0, 6);
            drawTileBound(prefix + "min_" + axis + "_tile");
            ImGui.sameLine(0, 16);
            ImGui.alignTextToFramePadding();
            ImGui.textColored(GeodataWidgets.MUTED, "Max");
            ImGui.sameLine(0, 6);
            drawTileBound(prefix + "max_" + axis + "_tile");
            var hovered = GeodataWidgets.endSettingRow();
            markRow("row/" + prefix + axis, top);
            if (hovered && !ImGui.isAnyItemHovered()) {
                HudWidgets.showTooltip(rows.describe(prefix + "min_" + axis + "_tile"));
            }
            for (String bound : List.of("min_", "max_")) {
                rows.drawInvalid(prefix + bound + axis + "_tile");
            }
        }
    }

    private void drawTileBound(String path) {
        var input = rows.getText(path);
        ImGui.setNextItemWidth(BOUND_FIELD);
        if (ImGui.inputText("##" + path, input)) {
            try {
                model.set(path, Integer.parseInt(input.get().trim()));
                invalid.remove(path);
                error = "";
            } catch (NumberFormatException failure) {
                invalid.put(path, "Enter a whole tile number.");
            } catch (IllegalArgumentException failure) {
                invalid.put(path, failure.getMessage());
            }
        }
        mark("field/" + path);
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(rows.describe(path));
        }
    }

    private void drawLocations(String path, ConfigList values) {
        var top = ImGui.getCursorScreenPosY();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 74 - GeodataWidgets.ROW);
        ImGui.inputTextWithHint("##location-search", "Find a location…", locationSearch);
        ImGui.sameLine(0, GeodataWidgets.ROW);
        if (HudWidgets.button("add-location", Glyph.PLUS, "Add", 74, 28, false, true)) {
            var locations = new ArrayList<>(values);
            int suffix = locations.size() + 1;
            var names = locations.stream()
                    .map(v -> ((ConfigObject) v).toConfig().getString("name"))
                    .toList();
            String name;
            do {
                name = "New location " + suffix++;
            } while (names.contains(name));
            locations.add(ConfigValueFactory.fromMap(Map.of("name", name, "position", List.of(0, 0, 0))));
            model.setValue(path, ConfigValueFactory.fromIterable(locations));
            selectedLocation = locations.size() - 1;
            locationSearch.set("");
            values = (ConfigList) model.getValue(path);
        }
        GeodataWidgets.tip(rows.describe(path));
        if (values.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "Save places here to find them again in the world.");
            return;
        }
        selectedLocation = Math.min(selectedLocation, values.size() - 1);
        var below = ImGui.getFrameHeight() + 2 * ImGui.getStyle().getItemSpacingY() + GeodataWidgets.SECTION;
        var free = Math.max(LOCATIONS_LEAST, pageHeight - ImGui.getCursorPosY() - below);
        var step = LOCATION_ROW + ImGui.getStyle().getItemSpacingY();
        var listHeight = Math.max(1, (float) Math.floor((free + ImGui.getStyle().getItemSpacingY()) / step)) * step
                - ImGui.getStyle().getItemSpacingY();
        ImGui.beginChild("##location-list", 0, listHeight, ImGuiChildFlags.NavFlattened, 0);
        var inset = ImGui.getStyle().getFramePaddingX();
        boolean any = false;
        for (int i = 0; i < values.size(); i++) {
            var location = ((ConfigObject) values.get(i)).toConfig();
            String name = location.getString("name");
            if (!name.toLowerCase(Locale.ROOT).contains(locationSearch.get().toLowerCase(Locale.ROOT))) {
                continue;
            }
            any = true;
            var rowX = ImGui.getCursorScreenPosX();
            var rowY = ImGui.getCursorScreenPosY();
            if (ImGui.selectable("##location-" + i, i == selectedLocation, 0, 0, LOCATION_ROW)) {
                selectedLocation = i;
            }
            ImGui.getWindowDrawList()
                    .addText(
                            rowX + inset,
                            rowY + (LOCATION_ROW - ImGui.getFontSize()) * .5f,
                            ImGui.getColorU32(ImGuiCol.Text),
                            name);
        }
        if (!any) {
            ImGui.textColored(GeodataWidgets.FAINT, "No matching locations.");
        }
        ImGui.endChild();
        mark("location-list");
        var location = ((ConfigObject) values.get(selectedLocation)).toConfig();
        var prefix = path + "/" + selectedLocation;
        var name = text.computeIfAbsent(prefix + "/name", ignored -> new ImString(location.getString("name"), 512));
        float rowWidth = ImGui.getContentRegionAvailX();
        float nameWidth = Math.max(120, rowWidth * .34f);
        float spacing = ImGui.getStyle().getItemSpacingX();
        float axes = ImGui.calcTextSizeX("X") + ImGui.calcTextSizeX("Y") + ImGui.calcTextSizeX("Z");
        float coordinateWidth =
                (float) Math.floor((rowWidth - nameWidth - axes - 7 * spacing - HudWidgets.CONTROL_HEIGHT) / 3);
        nameWidth = rowWidth - 3 * coordinateWidth - axes - 7 * spacing - HudWidgets.CONTROL_HEIGHT;
        ImGui.setNextItemWidth(nameWidth);
        boolean changed = ImGui.inputTextWithHint("##location-name", "Location name", name);
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip("The name shown in Places and the starting-location picker.");
        }
        var coords = new ArrayList<ImString>();
        for (int axis = 0; axis < 3; axis++) {
            int component = axis;
            var input = text.computeIfAbsent(
                    prefix + "/" + axis,
                    ignored -> new ImString(
                            location.getDoubleList("position")
                                    .get(component)
                                    .toString()
                                    .replaceFirst("\\.0$", ""),
                            64));
            coords.add(input);
            ImGui.sameLine();
            ImGui.alignTextToFramePadding();
            ImGui.textColored(GeodataWidgets.MUTED, List.of("X", "Y", "Z").get(axis));
            ImGui.sameLine();
            ImGui.setNextItemWidth(coordinateWidth);
            changed |= ImGui.inputText("##coordinate-" + axis, input);
            mark("location/" + List.of("x", "y", "z").get(axis));
        }
        ImGui.sameLine();
        boolean remove = HudWidgets.button("remove-location", Glyph.CLOSE, "", 28, 28, false, true);
        mark("location/remove");
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip("Remove this location from saved places.");
        }
        if (changed) {
            try {
                model.updateLocation(
                        selectedLocation,
                        name.get(),
                        coords.stream().map(v -> Double.parseDouble(v.get())).toList());
                text.remove(SettingRows.CAMERA_START);
                invalid.remove(prefix);
                error = "";
            } catch (NumberFormatException failure) {
                invalid.put(prefix, "Enter a number for each coordinate.");
            } catch (IllegalArgumentException failure) {
                invalid.put(prefix, failure.getMessage());
            }
        }
        rows.drawInvalid(prefix);
        if (remove) {
            model.removeLocation(selectedLocation);
            text.remove(SettingRows.CAMERA_START);
            text.keySet().removeIf(key -> key.startsWith(path + "/"));
            invalid.keySet().removeIf(key -> key.startsWith(path + "/"));
            selectedLocation = Math.max(0, selectedLocation - 1);
        }
        markRow("locations", top);
    }

    private void drawAgentPage(FontManager fonts) {
        GeodataWidgets.space(GeodataWidgets.SECTION);
        var enabled = Boolean.TRUE.equals(model.getValue(AgentSettings.ENABLED).unwrapped());
        var next = HudWidgets.switchRow(AgentControls.SWITCH, enabled, true, rows.describe(AgentSettings.ENABLED));
        mark("agent/switch");
        if (next != enabled) {
            model.set(AgentSettings.ENABLED, next);
            error = "";
        }
        GeodataWidgets.section("ACCESS");
        var access =
                Access.parse(model.getValue(AgentSettings.ACCESS).unwrapped().toString());
        var accessLeft = ImGui.getCursorScreenPosX();
        var accessTop = ImGui.getCursorScreenPosY();
        places.put(
                "agent/access",
                new float[] {accessLeft, accessTop, accessLeft + ImGui.getContentRegionAvailX(), accessTop + 26});
        var chosen = AgentControls.access("options-agent-access", access);
        if (chosen != access) {
            model.set(AgentSettings.ACCESS, chosen.getWord());
            error = "";
        }
        GeodataWidgets.section("CONNECT");
        GeodataWidgets.hint(
                "Add Cruma to your agent's program once. It starts Cruma by itself, and no window has to be open.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (agentConnect == null) {
            agentConnect = AgentConnect.ofThisRun();
        }
        AgentControls.connect("options-agent-connect", agentConnect, fonts.getMonoFont());
    }

    private void drawGeodataBuild(String path, Path client) {
        geodataBuild.setVariant(model.getVariant());
        var folder = geodataBuild.getFolder(client);
        geodataBuild.startPointSources(typedFolder(ClientProfiles.SCRIPT_PATH));
        var outcome = geodataBuild.getOutcome(folder);
        if (outcome != null) {
            geodataBuildOutcome = outcome;
            buildOpen = true;
        }
        var running = geodataBuild.isRunning(folder);
        var open = buildOpen || running;
        if (GeodataWidgets.settingChevronRow(
                        "options-geodata-build-open", "Build geodata from this client", "", "", open)
                && !running) {
            buildOpen = !open;
        }
        mark("geodata-build");
        GeodataWidgets.tip("Builds geodata from the client's maps into " + folder
                + ", for a server without its own files. The folder above is kept until you choose the built one.");
        if (!open) {
            return;
        }
        var partTop = ImGui.getCursorScreenPosY();
        drawGeodataBuildPart(path, client, folder);
        markRow("geodata-build-part", partTop);
    }

    private void drawGeodataBuildPart(String path, Path client, Path folder) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (geodataBuild.progressCard("options-geodata-build-stop", folder)) {
            geodataBuild.about("options-geodata-about");
            return;
        }
        if (geodataBuildOutcome != null) {
            ImGui.textColored(geodataBuildOutcome.color(), geodataBuildOutcome.text());
            if (geodataBuildOutcome.folder() != null) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.showFolder("options-geodata-build-folder", geodataBuildOutcome.folder());
            }
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        geodataBuild.formatSwitch("options-geodata-format", client);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var built = geodataBuild.getBuilt(client);
        var missing = geodataBuild.getMissing(client);
        if (built > 0 && !namesFolder(path, folder)) {
            if (GeodataWidgets.actionButton(
                    "options-geodata-use-built",
                    Glyph.FOLDER,
                    "Use the " + built + (built == 1 ? " region" : " regions") + " built from this client",
                    -1,
                    false)) {
                rows.setFolder(path, folder);
            }
            GeodataWidgets.tip(
                    "Sets the folder to " + folder + ", where the viewer built geodata from this client's maps.");
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        if (geodataBuild.buildActions("options-geodata-build", client, selectedTiles, startTile(), true)) {
            geodataBuildOutcome = null;
            rows.setFolder(path, folder);
        }
        if (missing == 0 && !geodataBuild.isPartial(client, selectedTiles)) {
            ImGui.textColored(GeodataWidgets.MUTED, "Every map of this client has a region file.");
        }
        if (geodataBuild.pathNodeCallout("options-geodata-pathnode", folder, true)) {
            geodataBuildOutcome = null;
        }
        geodataBuild.about("options-geodata-about");
    }

    private FooterAction drawFooter() {
        var failed = !error.isEmpty() || !invalid.isEmpty();
        String status = !error.isEmpty()
                ? error
                : !invalid.isEmpty()
                        ? "Fix " + invalid.size() + (invalid.size() == 1 ? " invalid value" : " invalid values")
                                + " before saving."
                        : model.hasChanges()
                                ? model.getChangeCount()
                                        + (model.getChangeCount() == 1 ? " unsaved change" : " unsaved changes")
                                : "No unsaved changes";
        int color =
                failed ? GeodataWidgets.BLOCKED : model.hasChanges() ? GeodataWidgets.WARNING : GeodataWidgets.MUTED;
        var canSave = invalid.isEmpty() && model.hasChanges();
        var actions = new ArrayList<FooterAction>();
        var buttons = new ArrayList<FooterButton>();
        if (asking) {
            var count = countUnsaved();
            status = "Drop " + count + (count == 1 ? " unsaved change?" : " unsaved changes?");
            color = GeodataWidgets.WARNING;
            actions.add(FooterAction.KEEP);
            buttons.add(new FooterButton("settings-keep", null, "Keep editing", buttonWidth("Keep editing"), true));
            actions.add(FooterAction.DROP);
            buttons.add(new FooterButton("settings-drop", null, "Drop changes", buttonWidth("Drop changes"), false));
        } else {
            addFooterButtons(actions, buttons, canSave);
        }
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var right = x + ImGui.getContentRegionAvailX();
        var buttonTop = y + (GeodataWidgets.FOOTER - 32) * .5f;
        for (var index = buttons.size() - 1; index >= 0; index--) {
            var button = buttons.get(index);
            places.put(
                    "footer/" + actions.get(index).name().toLowerCase(Locale.ROOT),
                    new float[] {right - button.width(), buttonTop, right, buttonTop + 32});
            right -= button.width() + GeodataWidgets.ROW;
        }
        var pressed = host == Host.VIEWER
                ? GeodataWidgets.footer(
                        status, "Changes apply when the world loads again. Map events apply at once.", color, buttons)
                : GeodataWidgets.footer(status, color, buttons);
        return pressed < 0 ? FooterAction.NONE : actions.get(pressed);
    }

    private void addFooterButtons(List<FooterAction> actions, List<FooterButton> buttons, boolean canSave) {
        if (!invalid.isEmpty()) {
            actions.add(FooterAction.REVEAL);
            buttons.add(new FooterButton(
                    "settings-reveal", null, "Show invalid setting", buttonWidth("Show invalid setting"), false));
        }
        actions.add(FooterAction.CANCEL);
        buttons.add(new FooterButton("settings-cancel", null, "Cancel", buttonWidth("Cancel"), false));
        actions.add(FooterAction.SAVE);
        buttons.add(
                new FooterButton("settings-save", null, "Save", buttonWidth("Save"), host == Host.MENU, canSave, ""));
        if (host == Host.VIEWER) {
            actions.add(FooterAction.RELOAD);
            buttons.add(new FooterButton(
                    "settings-reload",
                    null,
                    "Save and load again",
                    buttonWidth("Save and load again"),
                    true,
                    invalid.isEmpty(),
                    "Saves any changes, then loads the world again with the saved settings."));
        }
    }

    private int countUnsaved() {
        return model.getChangeCount() + invalid.size();
    }

    private void resetPage(List<Field> shown) {
        var paths = LaunchOptionCatalog.resetPaths(shown);
        model.resetToDefaults(paths);
        for (var path : paths) {
            text.remove(path);
            invalid.remove(path);
        }
        error = "";
    }

    private void revealError() {
        String path = invalid.keySet().stream()
                .findFirst()
                .orElseGet(() -> fields.stream()
                        .map(Field::path)
                        .filter(error::contains)
                        .findFirst()
                        .orElse(""));
        if (path.isEmpty()) {
            return;
        }
        if (path.contains("/")) {
            path = path.substring(0, path.indexOf('/'));
        }
        search.set(path);
        scrollToTop = true;
    }

    private @Nullable Path resolve(String typed) {
        return ClientFolderStatus.resolve(typed, configDirectory);
    }

    private String typedText(String path) {
        return model.getValue(path).unwrapped().toString();
    }

    private @Nullable Path typedFolder(String path) {
        return resolve(typedText(path));
    }

    private boolean namesFolder(String path, Path folder) {
        var typed = typedFolder(path);
        return typed != null && GeodataBuildControls.isSameFolder(typed, folder);
    }

    private @Nullable Field fieldOf(String path) {
        for (var field : fields) {
            if (field.path().equals(path)) {
                return field;
            }
        }
        return null;
    }

    private GeoTile startTile() {
        try {
            var x = (readInt("viewer.world.min_x_tile") + readInt("viewer.world.max_x_tile")) / 2;
            var y = (readInt("viewer.world.min_y_tile") + readInt("viewer.world.max_y_tile")) / 2;
            return new GeoTile(x, y);
        } catch (RuntimeException unset) {
            return new GeoTile(22, 22);
        }
    }

    private int readInt(String path) {
        return ((Number) model.getValue(path).unwrapped()).intValue();
    }

    private String pageTitle(@Nullable String target) {
        return LaunchOptionCatalog.CLIENT.equals(target) ? clientLabel : String.valueOf(target);
    }

    private void mark(String name) {
        places.put(name, new float[] {
            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
        });
    }

    private void markRow(String name, float top) {
        var x = ImGui.getCursorScreenPosX();
        places.put(name, new float[] {x, top, x + ImGui.getContentRegionAvailX(), ImGui.getCursorScreenPosY()});
    }

    private static String intro(String page) {
        return switch (page) {
            case LaunchOptionCatalog.INTERFACE ->
                "Automatic uses the display scale of your operating system. Choose a larger value if text is too"
                        + " small.";
            case LaunchOptionCatalog.TOOLS -> "How the Viewer's tools start. The tools change these while you work.";
            case LaunchOptionCatalog.CAPTURE_RENDERING ->
                "What Capture map images draws its images with. Capture draws every object of a tile at full"
                        + " resolution, so distance and size limits have no setting here. Files, the radar map and"
                        + " tile views are set in Capture map images, in the start menu.";
            case LaunchOptionCatalog.ADVANCED -> "Technical controls for rendering, memory and diagnostics.";
            default -> "";
        };
    }

    private static String sectionHint(@Nullable String page, String section) {
        if (LaunchOptionCatalog.CLIENT.equals(page) && section.equals("Map events")) {
            return "Effects that a game server turns on and off.";
        }
        if (LaunchOptionCatalog.PLACES.equals(page) && section.equals("Starting camera")) {
            return "The camera starts here when this place lies in the tiles you open. A place you pick by name on"
                    + " the start menu's map comes first.";
        }
        if (LaunchOptionCatalog.ADVANCED.equals(page) && section.equals("Default area")) {
            return "The tiles Cruma loads when it starts without the start menu and no tiles are given. The start"
                    + " menu chooses the tiles on its map.";
        }
        return "";
    }

    private static String labelOf(Path client, Path configDirectory) {
        var known = new ArrayList<Path>();
        try {
            for (var entry : new RecentClients(configDirectory).getEntries()) {
                known.add(entry.path());
            }
        } catch (RuntimeException unreadable) {
        }
        if (!known.contains(client)) {
            known.add(client);
        }
        return ClientFolders.label(client, known);
    }

    private static float buttonWidth(String label) {
        return Math.max(64, ImGui.calcTextSizeX(label) + 32);
    }
}

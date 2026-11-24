package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.env.LobbyTimeEnv;
import com.rost.cruma.core.export.LocationExport;
import com.rost.cruma.core.export.LocationExport.Area;
import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.export.LocationExport.Result;
import com.rost.cruma.core.export.LocationExport.Settings;
import com.rost.cruma.core.world.MapEventCatalog;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Start;
import com.rost.cruma.core.world.ServerEvents;
import com.rost.cruma.core.world.SkyZones;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.launch.BlenderApp;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.launch.LocationExportRun;
import com.rost.cruma.viewer.render.bgfx.ParticleStill;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.ui.GeodataWidgets.FooterButton;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.TileMapPicker.Zoom;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;

public final class LocationExportDialog {
    public static final String TITLE = "Export location";

    public static final String MAP_TITLE = "Export map";

    static final String GUIDE = "export/export-a-location";

    private static final float PANEL_WIDTH = 320;

    private static final float MAP_PANEL_WIDTH = 600;

    private static final float LEAST_BESIDE = MAP_PANEL_WIDTH + 40;

    private static final float MAP_BODY_HEIGHT = 660;

    private static final float CHROME = GeodataWidgets.DIALOG_CHROME;

    private static final int[] AREA_SIDES = {200, 400, 800, 1300};

    private static final String[] EVENT_STARTS = {"All shown", "Game start", "Server start"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-location-export");
        thread.setDaemon(true);
        return thread;
    });

    private final ExportSettings settings;
    private final MapEventChoices mapEventChoices;
    private final ExportScaleChoice scale;
    private final Zoom tileZoom = new Zoom();
    private final Zoom areaZoom = new Zoom();
    private final ImString folder = new ImString(4096);

    private String offeredFolder = "";

    private final FolderPicker folderPicker = new FolderPicker(
            "Choose export folder",
            "Select the folder that gets the export. A folder named after the tiles is made in it.",
            false);
    private final ImBoolean grass = new ImBoolean(true);
    private final ImBoolean clientLight = new ImBoolean(true);
    private final ImBoolean effects = new ImBoolean(true);
    private final ImBoolean movingEffects = new ImBoolean(false);
    private final ImBoolean sounds = new ImBoolean(true);
    private final ImBoolean music = new ImBoolean(true);

    private MapEventState mapEvents = MapEventState.ALL_SHOWN;

    private final ImString hideIds = new ImString(1024);

    private final ImString showIds = new ImString(1024);

    private String serverScripts = "";

    private ServerEvents server = ServerEvents.NONE;

    private String eventIdsError = "";

    private final ImInt hour = new ImInt(Settings.DEFAULT_HOUR);

    private final ImBoolean replace = new ImBoolean(false);
    private final ImBoolean npcs = new ImBoolean(false);
    private final ImString scripts = new ImString(4096);
    private final ImString geodata = new ImString(4096);
    private final FolderPicker scriptPicker = FolderPicker.scripts();
    private final FolderPicker geodataPicker = new FolderPicker();

    private String checkedScripts = "";

    private boolean scriptsReady;

    private String checkedGeodata = "";

    private boolean geodataReady;

    private boolean opening;
    private ClientInstallation installation;

    private @Nullable String namedMap;

    private @Nullable String lobbyLight;

    private final List<String> tiles = new ArrayList<>();

    private boolean areaMode;

    private float areaX;
    private float areaY;
    private int areaSide = 1;
    private String areaLabel = "";
    private final ImString placeSearch = new ImString(128);
    private List<Place> places = List.of();

    private WorldVariant variant = WorldVariant.MAIN;
    private int terrainSize = LocationExportRun.FIRST_TERRAIN_SIZE;

    private String existingKey = "";
    private List<Path> existing = List.of();

    private @Nullable CompletableFuture<Result> running;
    private volatile String stage = "";
    private volatile double fraction = -1;
    private volatile boolean stopping;
    private String outcome = "";

    private List<String[]> outcomeRows = List.of();

    private String outcomeKey = "";

    private boolean failed;
    private @Nullable Path written;
    private String exporting = "";

    private boolean shown;

    public LocationExportDialog(ExportSettings settings, MapEventChoices mapEventChoices) {
        this.settings = settings;
        this.mapEventChoices = mapEventChoices;
        scale = new ExportScaleChoice(settings);
    }

    public record Place(String name, float x, float y) {}

    static String withoutPlace(int spawns) {
        return spawns == 0
                ? ""
                : " "
                        + formatNumber(spawns)
                        + (spawns == 1 ? " random spawn" : " random spawns")
                        + " left out: no geodata.";
    }

    static String mapEventsHint(MapEventState events, ServerEvents server, boolean effects, boolean sounds) {
        if (effects) {
            return eventNotes(events, server)
                    + "Map events in the viewer shows which effects each event has, and shares this choice.";
        }
        var notes = new StringBuilder();
        if (sounds && !events.clocks().isEmpty()) {
            notes.append("Sounds by time of day: ").append(events.clockWords()).append(". ");
        }
        notes.append(zoneNote(events));
        return notes.isEmpty() ? "" : notes + "Map events in the viewer sets this choice.";
    }

    public void open(ClientInstallation installation, List<String> selected) {
        open(installation, selected, "", "", List.of());
    }

    public void open(
            ClientInstallation installation, List<String> selected, String scriptFolder, String geodataFolder) {
        open(installation, selected, scriptFolder, geodataFolder, List.of());
    }

    public void open(
            ClientInstallation installation,
            List<String> selected,
            String scriptFolder,
            String geodataFolder,
            List<Place> places) {
        var sameClient = this.installation != null && this.installation.root().equals(installation.root());
        this.installation = installation;
        this.places = List.copyOf(places);
        namedMap = null;
        if (!sameClient || scripts.get().isBlank()) {
            scripts.set(scriptFolder);
        }
        if (!sameClient || geodata.get().isBlank()) {
            geodata.set(geodataFolder);
        }
        if (!sameClient) {
            reset(selected);
        }
        prepare();
    }

    public void openMap(ClientInstallation installation, String map) {
        var sameClient = this.installation != null && this.installation.root().equals(installation.root());
        this.installation = installation;
        if (!sameClient) {
            reset(List.of());
        }
        namedMap = map;
        var lobby = LobbyTimeEnv.files(installation.root(), map);
        lobbyLight = lobby.isEmpty() ? null : lobby.getFirst().getFileName().toString();
        prepare();
    }

    public void openMap(ClientInstallation installation, String map, int hour) {
        openMap(installation, map);
        this.hour.set(Math.floorMod(hour, 24));
    }

    public void openTile(
            ClientInstallation installation,
            String tile,
            String scriptFolder,
            String geodataFolder,
            List<Place> places,
            int hour) {
        open(installation, List.of(tile), scriptFolder, geodataFolder, places);
        tiles.clear();
        toggle(tile);
        areaMode = false;
        this.hour.set(Math.floorMod(hour, 24));
    }

    public void openArea(
            ClientInstallation installation,
            float x,
            float y,
            String scriptFolder,
            String geodataFolder,
            List<Place> places,
            int hour) {
        open(installation, List.of(), scriptFolder, geodataFolder, places);
        areaMode = true;
        areaX = x;
        areaY = y;
        areaLabel = "";
        this.hour.set(Math.floorMod(hour, 24));
    }

    public boolean isOpen() {
        return opening || shown;
    }

    public void draw(FontManager fonts, long previewTexture) {
        if (installation == null) {
            return;
        }
        if (opening) {
            opening = false;
            ImGui.openPopup(TITLE);
        }
        var io = ImGui.getIO();
        var mapMode = namedMap != null;
        var room = GuideWindow.dialogRoom(LEAST_BESIDE);
        var layout = mapMode
                ? null
                : TileMapPicker.layout(
                        installation.tiles(),
                        previewTexture,
                        Math.min(1280, room[1] - 2 * GuideWindow.DIALOG_MARGIN) - 40 - PANEL_WIDTH - 20,
                        Math.min(1120, io.getDisplaySizeY() - 32) - CHROME);
        var mapWidth = mapMode ? 0 : layout.width() + 20;
        var panelWidth = mapMode ? MAP_PANEL_WIDTH : PANEL_WIDTH;
        var bodyHeight = mapMode
                ? Math.min(MAP_BODY_HEIGHT, io.getDisplaySizeY() - 32 - CHROME)
                : Math.max(layout.height(), Math.min(600, io.getDisplaySizeY() - 32 - CHROME));
        ImGui.setNextWindowPos(room[0] + room[1] * .5f, io.getDisplaySizeY() * .5f, ImGuiCond.Always, .5f, .5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, GeodataWidgets.DIALOG_SIDE, GeodataWidgets.DIALOG_EDGE);
        var visible = GeodataWidgets.beginDialog(TITLE, mapWidth + panelWidth + 40, bodyHeight + CHROME);
        ImGui.popStyleVar();
        shown = visible;
        if (!visible) {
            return;
        }
        poll();
        var typing = ImGui.isAnyItemActive();
        if (GeodataWidgets.dialogTitle(fonts, mapMode ? MAP_TITLE : TITLE, "close-export-location", GUIDE)
                && !isBusy()) {
            ImGui.closeCurrentPopup();
        }

        var top = ImGui.getCursorPosY();
        if (!mapMode && areaMode) {
            var current = area();
            var picked = TileMapPicker.drawArea(
                    installation.tiles(),
                    current == null
                            ? null
                            : new float[] {current.minX(), current.minY(), current.maxX(), current.maxY()},
                    layout,
                    previewTexture,
                    areaZoom);
            if (picked != null && !isBusy()) {
                areaX = Math.round(picked[0]);
                areaY = Math.round(picked[1]);
            }
        } else if (!mapMode) {
            var clicked =
                    TileMapPicker.draw(installation.tiles(), List.copyOf(tiles), "", layout, previewTexture, tileZoom);
            if (clicked != null && !isBusy()) {
                toggle(clicked);
            }
        }
        ImGui.setCursorPos(20 + mapWidth, top);
        GeodataWidgets.beginDialogPanel("##export-location-panel", panelWidth, bodyHeight);
        drawPanel();
        GeodataWidgets.endDialogPanel();
        GeodataWidgets.dialogRule(top + bodyHeight);
        drawFooter();
        if (!isBusy() && (!typing && GeodataWidgets.isEscapePressed() || GeodataWidgets.wasClickedOutside())) {
            ImGui.closeCurrentPopup();
        }
        var chosen = folderPicker.draw();
        if (chosen != null) {
            folder.set(chosen.toString());
        }
        var chosenScripts = scriptPicker.draw();
        if (chosenScripts != null) {
            scripts.set(chosenScripts.toString());
        }
        var chosenGeodata = geodataPicker.draw();
        if (chosenGeodata != null) {
            geodata.set(chosenGeodata.toString());
        }
        GuideWindow.drawOverDialog();
        ImGui.endPopup();
    }

    private void reset(List<String> selected) {
        tiles.clear();
        if (selected.size() <= LocationExport.COMFORTABLE_TILES) {
            for (var tile : selected) {
                toggle(tile);
            }
        }
        variant = installation.variant();
        outcome = "";
        written = null;
        var first = selected.isEmpty()
                ? installation.tiles().isEmpty() ? null : installation.tiles().getFirst()
                : selected.getFirst();
        if (first != null) {
            var coordinates = first.split("_");
            areaX = (Integer.parseInt(coordinates[0]) - 20 + .5f) * 32768;
            areaY = (Integer.parseInt(coordinates[1]) - 18 + .5f) * 32768;
        }
        areaLabel = "";
    }

    private void prepare() {
        settings.refresh();
        scale.load();
        mapEvents = mapEventChoices.of(installation.root());
        serverScripts = "";
        hideIds.set(MapEventState.formatIds(mapEvents.hidden()));
        showIds.set(MapEventState.formatIds(mapEvents.shown()));
        eventIdsError = "";
        var offered = settings.getFolder().toString();
        if (folder.get().isBlank() || !offered.equals(offeredFolder)) {
            folder.set(offered);
            offeredFolder = offered;
        }
        existingKey = "";
        opening = true;
    }

    private void drawPanel() {
        var first = namedMap != null ? "MAP" : "AREA";
        if (!failed && written != null && isOutcomeShown()) {
            GeodataWidgets.section("RESULT", 0);
            for (var row : outcomeRows) {
                GeodataWidgets.value(row[0], row[1]);
            }
            GeodataWidgets.section(first);
        } else {
            GeodataWidgets.section(first, 0);
        }
        if (namedMap != null) {
            GeodataWidgets.value("Map", namedMap);
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.hint(
                    "The whole map, as the game shows it alone. The files have their origin where the viewer's camera"
                            + " starts in it.");
            drawSettings();
            return;
        }
        ImGui.beginDisabled(isBusy());
        var pickedMode =
                GeodataWidgets.segmented("export-area-mode", new String[] {"Whole tiles", "A part"}, areaMode ? 1 : 0);
        if (pickedMode >= 0) {
            areaMode = pickedMode == 1;
        }
        GeodataWidgets.tip(
                "A part is a square around a place: smaller files, and the terrain gets all the texture's pixels.");
        ImGui.endDisabled();
        if (areaMode) {
            drawArea();
        } else {
            drawTiles();
        }
        drawSettings();
    }

    private void drawTiles() {
        GeodataWidgets.section("TILES");
        if (tiles.isEmpty()) {
            GeodataWidgets.hint("Click tiles on the map. A second click takes one out.");
        } else if (tiles.size() == 1) {
            GeodataWidgets.value("Tile", tiles.getFirst() + variant.getMapSuffix());
        } else {
            GeodataWidgets.value(
                    "Chosen",
                    tiles.size() <= 6 ? String.join(", ", tiles) : tiles.size() + " tiles, each in its place");
            GeodataWidgets.value("Name", name());
        }
        if (tiles.size() >= LocationExport.MAX_TILES) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    "An export takes at most " + LocationExport.MAX_TILES + " tiles.", GeodataWidgets.WARNING);
        } else if (tiles.size() > LocationExport.COMFORTABLE_TILES) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    tiles.size() + " tiles make a large file that 3D programs open slowly. Up to "
                            + LocationExport.COMFORTABLE_TILES + " work well.",
                    GeodataWidgets.WARNING);
        }
    }

    private void drawArea() {
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.beginDisabled(isBusy());
        var fields = Math.max(
                        ImGui.calcTextSizeX("Centre"),
                        Math.max(ImGui.calcTextSizeX("Place"), ImGui.calcTextSizeX("Side")))
                + GeodataWidgets.ROW * 2;
        rowLabel("Place", fields);
        var typed = GeodataWidgets.searchList(
                "##export-area-place",
                areaLabel.isBlank() ? "Find a town or a hunting ground" : areaLabel,
                placeSearch,
                "Find a place",
                -1);
        if (typed != null) {
            var query = typed.toLowerCase(Locale.ROOT);
            for (var place : places) {
                if (!place.name().toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                var tile = (int) Math.floor(place.x() / 32768) + 20 + "_" + ((int) Math.floor(place.y() / 32768) + 18);
                var here = installation.tiles().contains(tile);
                if (GeodataWidgets.listItem(
                        place.name(),
                        place.name(),
                        here ? tile : "not in this client",
                        place.name().equals(areaLabel))) {
                    areaLabel = place.name();
                    areaX = Math.round(place.x());
                    areaY = Math.round(place.y());
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip("The named places of the client's settings, the same as the viewer's Go to list.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        rowLabel("Side", fields);
        var labels = new String[AREA_SIDES.length];
        for (var i = 0; i < labels.length; i++) {
            labels[i] = AREA_SIDES[i] + " m";
        }
        var pickedSide = GeodataWidgets.segmented("export-area-side", labels, areaSide);
        if (pickedSide >= 0) {
            areaSide = pickedSide;
        }
        GeodataWidgets.tip("Metres at real size, 4 cm a unit. The same square is exported at any scale.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        rowLabel("Centre", fields);
        var x = new ImInt(Math.round(areaX));
        var y = new ImInt(Math.round(areaY));
        var axisX = ImGui.calcTextSizeX("X");
        var axisY = ImGui.calcTextSizeX("Y");
        var field = (ImGui.getContentRegionAvailX() - axisX - axisY - GeodataWidgets.ROW * 3) / 2;
        ImGui.textColored(GeodataWidgets.MUTED, "X");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        ImGui.setNextItemWidth(field);
        if (ImGui.inputInt("##export-area-x", x, 0, 0)) {
            areaX = x.get();
        }
        GeodataWidgets.tip("The centre's X in client units.");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Y");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        ImGui.setNextItemWidth(field);
        if (ImGui.inputInt("##export-area-y", y, 0, 0)) {
            areaY = y.get();
        }
        GeodataWidgets.tip("The centre's Y in client units.");
        ImGui.endDisabled();
        GeodataWidgets.space(GeodataWidgets.ROW);
        var reads = exportTiles();
        GeodataWidgets.hint(
                reads.isEmpty()
                        ? "The area is outside this client's tiles."
                        : "Reads "
                                + (reads.size() == 1
                                        ? "tile " + reads.getFirst()
                                        : reads.size() + " tiles: " + String.join(", ", reads))
                                + ". Click or drag on the map to move it.");
    }

    private void drawSettings() {
        if (installation.hasWorldChoice() && namedMap == null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.beginDisabled(isBusy());
            var offered = installation.worlds();
            var worlds = new String[offered.size()];
            for (var index = 0; index < worlds.length; index++) {
                worlds[index] = offered.get(index).getLabel();
            }
            var picked = GeodataWidgets.segmented("export-world", worlds, offered.indexOf(variant));
            if (picked >= 0) {
                variant = offered.get(picked);
            }
            GeodataWidgets.tip(
                    "Which world: Main reads X_Y.unr, Classic X_Y_Classic.unr. Essence reads the Classic maps with"
                            + " its own NPCs and music.");
            ImGui.endDisabled();
        }

        GeodataWidgets.section("TERRAIN TEXTURE");
        ImGui.beginDisabled(isBusy());
        var sizes = LocationExport.TERRAIN_SIZES;
        var labels = new String[sizes.length];
        for (var i = 0; i < sizes.length; i++) {
            labels[i] = sizes[i] + " px";
        }
        var pickedSize = GeodataWidgets.segmented("export-terrain", labels, terrainSize);
        if (pickedSize >= 0) {
            terrainSize = pickedSize;
        }
        GeodataWidgets.tip(
                "Every terrain layer of a tile is baked into one texture of this size. 8192 px is sharper up close and"
                        + " adds about 50 MB a tile.");
        var exported = exportTiles();
        if (LocationExport.TERRAIN_SIZES[terrainSize] == 8192 && exported.size() > 4) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    String.format(
                            Locale.ROOT,
                            "8192 px on %d tiles needs about %.1f GB of video memory. 4096 px needs a quarter.",
                            exported.size(),
                            exported.size() * .35),
                    GeodataWidgets.WARNING);
        }
        ImGui.endDisabled();

        GeodataWidgets.section("INCLUDE");
        ImGui.beginDisabled(isBusy());
        GeodataWidgets.columns(
                () -> check("Grass and flowers", grass, "The small plants the terrain scatters, as one mesh per kind."),
                () -> check(
                        "Client light",
                        clientLight,
                        "The light the client bakes into the map: lamps with their shadows, lightmaps and the sun's"
                            + " shadows of the hour. Blender then shows the place as the viewer does. Turn it off to"
                            + " light the place only with Blender's sun and sky, for a light of your own."));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.columns(
                () -> check(
                        "Effects",
                        effects,
                        "A still of the particle effects, such as glows, fire, smoke and light shafts, as the viewer"
                                + " shows them once they have warmed up. Blender turns the sprites to its camera."),
                () -> {
                    ImGui.beginDisabled(!effects.get());
                    var clicked = check(
                            "Moving effects",
                            movingEffects,
                            String.format(
                                    Locale.ROOT,
                                    "A loop of %d stills over %.0f seconds instead of one, so the effects move with"
                                        + " Blender's timeline. The effects take %d times as much room in the file.",
                                    LocationExport.EFFECT_STILLS,
                                    LocationExport.EFFECT_STILLS * LocationExport.EFFECT_INTERVAL,
                                    LocationExport.EFFECT_STILLS));
                    ImGui.endDisabled();
                    return clicked;
                });
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.columns(
                () -> check(
                        "Sounds",
                        sounds,
                        "The map's sounds as WAVE files, and " + LocationExport.SOUNDS_FILE
                                + " with the place, radius, volume and pitch of each, so a game engine can place them. "
                                + LocationExport.UNITY_SCRIPT + " places them in Unity."),
                () -> check(
                        "Music",
                        music,
                        "The tracks of the map's music zones, as Ogg Vorbis files or, for the early tracks that Unity"
                                + " does not read, as WAVE files, and "
                                + LocationExport.MUSIC_FILE
                                + " with each zone's tracks by day and by night and its shape. In Unity, "
                                + LocationExport.UNITY_MUSIC + " plays the music of the zone the listener is in."));
        ImGui.endDisabled();

        if (effects.get()) {
            drawMapEvents();
        } else {
            var hint = mapEventsHint(mapEvents, ServerEvents.NONE, false, sounds.get());
            if (!hint.isEmpty()) {
                GeodataWidgets.section("MAP EVENTS");
                GeodataWidgets.hint(hint);
            }
        }

        GeodataWidgets.section("SCALE");
        ImGui.beginDisabled(isBusy());
        scale.draw("export-scale");
        ImGui.endDisabled();

        GeodataWidgets.section("TIME OF DAY");
        ImGui.beginDisabled(isBusy());
        if (GeodataWidgets.integer("Hour", hour, 1)) {
            hour.set(Math.floorMod(hour.get(), 24));
        }
        GeodataWidgets.tip(
                "The client's sun, sky and fog at this hour, as the viewer shows them at the middle of the export. The"
                        + " effects by time of day come when they show in this hour, or as Map events sets them.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var light =
                lobbyLight != null && namedMap != null ? " in the light the game gives its lobby, " + lobbyLight : "";
        var skyZones = installation != null && SkyZones.isDeclaredBy(installation.root())
                ? " Where a sky zone gives the middle its own time of day, the sun, sky and fog take that hour and its"
                        + " light."
                : "";
        GeodataWidgets.hint(String.format(
                Locale.ROOT,
                "%d:00%s. The sun becomes a light and the sky, with its clouds, the panorama sky.png. Open in Blender"
                        + " sets them up with the fog.%s",
                hour.get(),
                light,
                skyZones));
        ImGui.endDisabled();

        if (namedMap == null) {
            drawNpcs();
        }

        GeodataWidgets.section("SAVE TO");
        ImGui.beginDisabled(isBusy());
        if (GeodataWidgets.pathField(
                "export-location-folder",
                "Folder",
                folder,
                namedMap != null
                        ? "A folder named after the map is made here."
                        : "A folder named after the tiles is made here.")) {
            folderPicker.openPath(folder.get());
        }
        refreshExisting();
        if (!existing.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var justWritten =
                    written != null && parent() != null && written.equals(LocationExport.tileFolder(parent(), name()));
            if (!justWritten) {
                GeodataWidgets.note("An earlier export of " + name() + " is in this folder.", GeodataWidgets.WARNING);
            }
            ImGui.checkbox("Replace it", replace);
            GeodataWidgets.tip("Export again over the files in " + name() + ".");
        }
        ImGui.endDisabled();

        GeodataWidgets.section("WHAT IS EXPORTED");
        var parts = "Terrain, level geometry, static meshes, doors, lamps and torch flames, with their textures";
        var npcsIn = npcs.get() && namedMap == null;
        var effectsText = (movingEffects.get() ? "a moving loop of the effects" : "a still of the effects")
                + (mapEvents.isEmpty() ? "" : " the map events show");
        var extras = new ArrayList<String>();
        if (effects.get()) {
            extras.add(effectsText);
        }
        if (sounds.get()) {
            extras.add("the sounds");
        }
        if (music.get()) {
            extras.add("the music");
        }
        if (npcsIn) {
            extras.add("the NPCs");
        }
        var added = extras.isEmpty()
                ? ". NPCs stay out."
                : (extras.size() == 1
                                ? ", and " + extras.getFirst()
                                : ", " + String.join(", ", extras.subList(0, extras.size() - 1)) + " and "
                                        + extras.getLast())
                        + (npcsIn ? "." : ". NPCs stay out.");
        GeodataWidgets.hint(parts + added
                + " One glTF file (.glb), which Blender, Unity and Unreal open. For 3ds Max, Blender saves it as FBX.");
    }

    private void drawFooter() {
        if (running != null) {
            if (GeodataWidgets.busyBar(
                    "export-location-running",
                    "Exporting " + exporting + " · " + (stopping ? "Stopping…" : stage),
                    fraction,
                    "Stop",
                    !stopping)) {
                stopping = true;
            }
            return;
        }
        var shown = isOutcomeShown();
        var missing = missing();
        var status = shown
                ? outcome
                : !missing.isEmpty()
                        ? missing
                        : namedMap != null
                                ? "Map " + namedMap + " · " + scale.getUnitText()
                                : installation.tiles().size()
                                        + " tiles in this client · "
                                        + variant.getLabel()
                                        + " world · "
                                        + scale.getUnitText();
        var color = !shown ? GeodataWidgets.MUTED : failed ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN;
        var buttons = new ArrayList<FooterButton>();
        var done = shown && !failed && written != null;
        var blender = done && BlenderApp.canOpen(written) ? BlenderApp.find() : null;
        if (done) {
            if (blender != null) {
                buttons.add(new FooterButton(
                        "export-location-blender",
                        Glyph.PLAY,
                        "Open in Blender",
                        156,
                        true,
                        true,
                        "Starts Blender with the export as a scene, the NPCs' rigs where they stand, a sun and"
                                + " a camera."));
            }
            buttons.add(new FooterButton("show-export-location", Glyph.FOLDER, "Show folder", 132, blender == null));
        } else {
            var exported = exportTiles();
            var label = namedMap != null
                    ? "Export " + name()
                    : exported.isEmpty()
                            ? "Export"
                            : areaMode || exported.size() == 1
                                    ? "Export " + name()
                                    : "Export " + exported.size() + " tiles";
            buttons.add(new FooterButton(
                    "run-export-location",
                    Glyph.FILE,
                    label,
                    Math.max(120, ImGui.calcTextSizeX(label) + 56),
                    true,
                    missing.isEmpty(),
                    missing));
        }
        buttons.add(new FooterButton("done-export-location", null, "Done", 96, false));
        var pressed = GeodataWidgets.footer(status, color, buttons);
        if (pressed < 0) {
            return;
        }
        switch (buttons.get(pressed).id()) {
            case "export-location-blender" -> {
                try {
                    BlenderApp.open(blender, written);
                    outcome = "Blender is opening " + written.getFileName() + ". A large export takes a while.";
                } catch (IOException failure) {
                    outcome = "Blender did not start: " + failure.getMessage();
                }
            }
            case "show-export-location" -> FileManager.tryOpenFolder(written);
            case "done-export-location" -> ImGui.closeCurrentPopup();
            default -> start();
        }
    }

    private boolean isOutcomeShown() {
        return !outcome.isEmpty() && outcomeKey.equals(choicesKey()) && !replace.get();
    }

    private String missing() {
        if (namedMap != null) {
            return missingTarget();
        }
        if (areaMode && exportTiles().isEmpty()) {
            return "Put the area on this client's tiles: find a place or click the map.";
        }
        if (tiles.isEmpty() && !areaMode) {
            return "Click tiles on the map to export them.";
        }
        if (exportTiles().size() > LocationExport.MAX_TILES) {
            return "An export takes at most " + LocationExport.MAX_TILES + " tiles.";
        }
        var target = missingTarget();
        if (!target.isEmpty()) {
            return target;
        }
        if (npcs.get() && !scriptsReady) {
            return "Choose the server's script folder for the NPCs.";
        }
        if (effects.get() && !eventIdsError.isEmpty()) {
            return "Correct the event ids under Map events.";
        }
        return "";
    }

    private String missingTarget() {
        if (parent() == null) {
            return "Choose the folder to save to.";
        }
        if (!existing.isEmpty() && !replace.get()) {
            return "Choose Replace it to export over the earlier files.";
        }
        return "";
    }

    private String choicesKey() {
        return namedMap
                + "|"
                + exportTiles()
                + "|"
                + area()
                + "|"
                + variant
                + "|"
                + folder.get().trim()
                + "|"
                + terrainSize
                + "|"
                + grass.get()
                + "|"
                + clientLight.get()
                + "|"
                + effects.get()
                + "|"
                + movingEffects.get()
                + "|"
                + sounds.get()
                + "|"
                + music.get()
                + "|"
                + mapEvents
                + "|"
                + npcs.get()
                + "|"
                + hour.get()
                + "|"
                + scale.getScale();
    }

    private void drawMapEvents() {
        GeodataWidgets.section("MAP EVENTS");
        ImGui.beginDisabled(isBusy());
        var server = serverEvents();
        var starts = server.isEmpty() && mapEvents.start() != Start.SERVER_START ? 2 : 3;
        var start = GeodataWidgets.segmented(
                "export-event-start",
                Arrays.copyOf(EVENT_STARTS, starts),
                mapEvents.start().ordinal());
        if (start >= 0 && start != mapEvents.start().ordinal()) {
            mapEvents = (switch (Start.values()[start]) {
                        case ALL_SHOWN -> MapEventState.ALL_SHOWN;
                        case GAME_START -> MapEventState.GAME_START;
                        case SERVER_START -> MapEventState.serverStart(server.onAtStart());
                    })
                    .withClocks(mapEvents.clocks())
                    .withZoneStates(mapEvents.zoneStates());
            hideIds.set("");
            showIds.set("");
            eventIdsError = "";
        }
        GeodataWidgets.tip(
                "Effects that a game server turns on and off, such as a town's festival or a castle's side. All shown:"
                        + " every effect. Game start: hidden, as the game hides them until the server turns them on."
                        + (starts == 3
                                ? " Server start: what the server turns on when it starts, from areadata.txt of the"
                                        + " script folder."
                                : ""));
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (mapEvents.start() != Start.GAME_START) {
            idsField("Hide", hideIds, "The events whose effects stay out.");
        }
        if (mapEvents.start() != Start.ALL_SHOWN) {
            if (mapEvents.start() == Start.SERVER_START) {
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
            idsField("Show", showIds, "The events whose effects come.");
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (!eventIdsError.isEmpty()) {
            GeodataWidgets.note(eventIdsError, GeodataWidgets.WARNING);
        } else {
            GeodataWidgets.hint(mapEventsHint(mapEvents, server, true, sounds.get()));
        }
        ImGui.endDisabled();
    }

    private void idsField(String label, ImString ids, String tip) {
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(label);
        ImGui.sameLine(48);
        ImGui.setNextItemWidth(-1);
        if (ImGui.inputTextWithHint("##export-event-" + label, "Event ids, such as 22226292", ids)) {
            try {
                var hidden = mapEvents.start() == Start.GAME_START
                        ? List.<Integer>of()
                        : MapEventState.parseIds(hideIds.get());
                var shown = mapEvents.start() == Start.ALL_SHOWN
                        ? List.<Integer>of()
                        : MapEventState.parseIds(showIds.get());
                mapEvents = mapEvents.withHidden(hidden).withShown(shown);
                eventIdsError = "";
            } catch (IllegalArgumentException notIds) {
                eventIdsError = notIds.getMessage();
            }
        }
        GeodataWidgets.tip(tip + " Ids split by commas. Map events in the viewer shows each event's effects and id.");
    }

    private ServerEvents serverEvents() {
        var text = scripts.get().trim();
        if (!text.equals(serverScripts)) {
            serverScripts = text;
            server = ServerEvents.of(folderOf(text));
        }
        return server;
    }

    private void drawNpcs() {
        GeodataWidgets.section("NPCS");
        ImGui.beginDisabled(isBusy());
        ImGui.checkbox("Place NPCs from npcpos", npcs);
        GeodataWidgets.tip(
                "Each NPC stands where the server spawns it, as a still figure in its idle pose. Each kind of NPC also"
                        + " gets a rigged file with all its animations.");
        if (npcs.get()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.pathField(
                    "export-location-scripts",
                    "Script folder",
                    scripts,
                    "The server's folder with npcpos.txt and npcdata.txt.")) {
                scriptPicker.openPath(scripts.get());
            }
            checkScripts();
            if (!scriptsReady) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.note(
                        scripts.get().isBlank()
                                ? "Choose the server's script folder."
                                : "This folder has no npcpos.txt and npcdata.txt.",
                        GeodataWidgets.WARNING);
            }
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.pathField(
                    "export-location-geodata",
                    "Geodata folder",
                    geodata,
                    "Random spawns stand on the server's geodata.")) {
                geodataPicker.openPath(geodata.get());
            }
            if (!isGeodataReady()) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.hint(
                        "Without geodata only NPCs with a fixed place are exported. The result says how many random"
                                + " spawns stayed out.");
            }
        }
        ImGui.endDisabled();
    }

    private boolean isGeodataReady() {
        var text = geodata.get().trim();
        if (!text.equals(checkedGeodata)) {
            checkedGeodata = text;
            geodataReady = folderOf(text) != null;
        }
        return geodataReady;
    }

    private void checkScripts() {
        var text = scripts.get().trim();
        if (text.equals(checkedScripts)) {
            return;
        }
        checkedScripts = text;
        var chosen = folderOf(text);
        var resolved = chosen == null ? null : SpawnSource.resolveFolder(chosen);
        scriptsReady = resolved != null
                && SpawnSource.find(resolved, SpawnSource.POSITIONS) != null
                && SpawnSource.find(resolved, SpawnSource.NPCS) != null;
    }

    private void start() {
        var root = installation.root();
        var world = variant;
        var chosen = exportTiles();
        var target = parent();
        var settings = settings();
        var overwrite = replace.get();
        scale.commit();
        var events = mapEvents;
        if (settings.effects()) {
            mapEventChoices.save(root, events);
        }
        if (target != null) {
            rememberFolder(target);
        }
        var map = namedMap;
        var scriptFolder = settings.npcs() ? folderOf(scripts.get()) : null;
        var geodataFolder = settings.npcs() ? folderOf(geodata.get()) : null;
        stage = "Starting";
        fraction = 0;
        stopping = false;
        outcome = "";
        outcomeKey = choicesKey();
        written = null;
        exporting = name();
        running = CompletableFuture.supplyAsync(
                () -> {
                    Progress progress = (text, done) -> {
                        stage = text;
                        fraction = done;
                    };
                    try {
                        if (map != null) {
                            return LocationExport.exportMap(
                                    root,
                                    map,
                                    target,
                                    settings,
                                    overwrite,
                                    new ParticleStill(events, settings.hour()).litBy(root),
                                    progress,
                                    () -> stopping);
                        }
                        return LocationExportRun.tiles(
                                root,
                                world,
                                chosen,
                                target,
                                settings,
                                overwrite,
                                events,
                                scriptFolder,
                                geodataFolder,
                                progress,
                                () -> stopping);
                    } catch (CancellationException stopped) {
                        throw stopped;
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                },
                worker);
    }

    private void poll() {
        if (running == null || !running.isDone()) {
            return;
        }
        try {
            var result = running.join();
            failed = false;
            written = result.folder();
            outcome = "Exported " + result.folder().getFileName() + "." + withoutPlace(result.withoutPlace());
            var rows = new ArrayList<String[]>();
            rows.add(new String[] {"Objects", formatNumber(result.objects())});
            rows.add(new String[] {"Triangles", formatNumber(result.triangles())});
            rows.add(new String[] {"Textures", formatNumber(result.textures())});
            if (result.sounds() > 0) {
                rows.add(new String[] {"Sounds", formatNumber(result.sounds())});
            }
            if (result.musicZones() > 0) {
                rows.add(new String[] {"Music zones", formatNumber(result.musicZones())});
                rows.add(new String[] {"Music tracks", formatNumber(result.musicTracks())});
            }
            if (result.figures() > 0 || result.rigs() > 0) {
                rows.add(new String[] {"NPC figures", formatNumber(result.figures())});
                rows.add(new String[] {"Rigs", formatNumber(result.rigs())});
            }
            if (result.withoutPlace() > 0) {
                rows.add(new String[] {"Left out", formatNumber(result.withoutPlace())});
            }
            if (result.mapFigures() > 0) {
                rows.add(new String[] {"Map figures", formatNumber(result.mapFigures())});
            }
            rows.add(new String[] {"Folder", result.folder().getFileName().toString()});
            outcomeRows = List.copyOf(rows);
        } catch (CancellationException | CompletionException failure) {
            failed = true;
            written = null;
            outcome = isCancellation(failure) ? "Stopped. Nothing was written." : failureMessage(failure);
        }
        running = null;
        replace.set(false);
        existingKey = "";
    }

    private void refreshExisting() {
        var parent = parent();
        var key = choicesKey();
        if (key.equals(existingKey)) {
            return;
        }
        existingKey = key;
        existing = namedMap == null && exportTiles().isEmpty() || parent == null
                ? List.of()
                : LocationExport.findExistingFiles(parent, name(), settings());
    }

    private void toggle(String tile) {
        if (tiles.remove(tile)) {
            return;
        }
        if (tiles.size() >= LocationExport.MAX_TILES) {
            return;
        }
        tiles.add(tile);
        tiles.sort(Comparator.comparingInt((String name) -> Integer.parseInt(name.split("_")[1]))
                .thenComparingInt(name -> Integer.parseInt(name.split("_")[0])));
    }

    private String name() {
        return namedMap != null
                ? LocationExport.mapName(namedMap)
                : LocationExport.name(exportTiles(), variant, area());
    }

    private Settings settings() {
        return LocationExportRun.settings(
                LocationExport.TERRAIN_SIZES[terrainSize],
                grass.get(),
                npcs.get() && namedMap == null,
                area(),
                hour.get(),
                scale.getScale(),
                clientLight.get(),
                effects.get(),
                movingEffects.get(),
                sounds.get(),
                music.get(),
                mapEvents);
    }

    private Area area() {
        if (!areaMode || namedMap != null) {
            return null;
        }
        var side = AREA_SIDES[areaSide] / LocationExport.METERS_PER_UNIT;
        return Area.around(areaLabel, areaX, areaY, side);
    }

    private List<String> exportTiles() {
        if (namedMap != null) {
            return List.of();
        }
        var area = area();
        if (area == null) {
            return List.copyOf(tiles);
        }
        var installed = installation.tiles();
        return area.tiles().stream().filter(installed::contains).toList();
    }

    private @Nullable Path parent() {
        var text = folder.get().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Path.of(text);
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    private boolean isBusy() {
        return running != null;
    }

    private void rememberFolder(Path chosen) {
        settings.setFolder(chosen);
        offeredFolder = settings.getFolder().toString();
    }

    private static void rowLabel(String label, float fieldStart) {
        ImGui.alignTextToFramePadding();
        ImGui.textColored(GeodataWidgets.MUTED, label);
        ImGui.sameLine(fieldStart);
    }

    private static String eventNotes(MapEventState events, ServerEvents server) {
        var notes = new StringBuilder();
        var named = 0;
        for (var id : events.exceptions()) {
            var name = server.name(id);
            if (!name.isEmpty() && named++ < 3) {
                notes.append(id).append(" is ").append(name).append(". ");
            }
        }
        if (named > 3) {
            notes.append(named - 3).append(named - 3 == 1 ? " more is named. " : " more are named. ");
        }
        var castles = events.castleSides().keySet().stream()
                .sorted()
                .map(castle ->
                        MapEventCatalog.castleName(castle) + " " + MapEventState.sideName(events.castleSide(castle)))
                .toList();
        if (!castles.isEmpty()) {
            notes.append("Castles: ").append(String.join(", ", castles)).append(". ");
        }
        if (!events.clocks().isEmpty()) {
            notes.append("By time of day: ").append(events.clockWords()).append(". ");
        }
        return notes + zoneNote(events);
    }

    private static String zoneNote(MapEventState events) {
        var chosen = events.zoneStates().getStates();
        if (chosen.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        chosen.forEach((map, state) ->
                parts.add((state == MapEventState.AS_THE_MAP ? "as the map" : "state " + state) + " in " + map));
        return "Zone " + String.join(", ", parts) + "; the other maps in the state they start in. ";
    }

    private static boolean check(String label, ImBoolean value, String tip) {
        var clicked = ImGui.checkbox(label, value);
        GeodataWidgets.tip(tip);
        return clicked;
    }

    private static @Nullable Path folderOf(String typed) {
        var text = typed.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            var path = Path.of(text);
            return Files.isDirectory(path) ? path : null;
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    private static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static boolean isCancellation(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    private static String failureMessage(Throwable failure) {
        var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}

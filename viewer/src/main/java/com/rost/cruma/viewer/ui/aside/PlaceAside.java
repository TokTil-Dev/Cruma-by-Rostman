package com.rost.cruma.viewer.ui.aside;

import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.core.spawn.NpcPosFile;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.StartPlaces;
import com.rost.cruma.viewer.launch.TileBounds;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.ui.CardWidgets;
import com.rost.cruma.viewer.ui.ClientFolderStatus;
import com.rost.cruma.viewer.ui.FontManager;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.LatestAnswer;
import com.rost.cruma.viewer.ui.aside.ChoiceSpawns.Census;
import com.rost.cruma.viewer.ui.aside.MenuMusic.Phase;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;

public final class PlaceAside implements StartAside {
    public static final float PICTURE_HEIGHT = 190;

    private static final float PAD = CardWidgets.PAD;
    private static final float GAP = CardWidgets.GAP;
    private static final int TITLE_SIZE = 28;
    private static final int KINDS = 6;
    private static final int NEIGHBORS = 6;
    private static final float ROOM = .5f;
    private static final float SHADE_HEIGHT = 72;
    private static final int SHADE = ImColor.rgba(15, 21, 27, 0);
    private static final int SHADE_DARK = ImColor.rgba(15, 21, 27, 235);
    private static final int EMPTY_TILE = ImColor.rgb(19, 26, 33);

    private static final ExecutorService SPAWN_WORKER = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "cruma-place-spawns");
        thread.setDaemon(true);
        return thread;
    });

    private record Spawns(@Nullable ChoiceSpawns tiles, String problem) {}

    private record NamesKey(Path root, WorldVariant world) {}

    private @Nullable MenuMusic music;
    private long mapTexture;
    private @Nullable TileBounds mapBounds;
    private @Nullable Location placeRequested;

    private LatestAnswer<String, Spawns> spawns = newSpawns();
    private LatestAnswer<String, Set<String>> regions = newRegions();
    private LatestAnswer<NamesKey, Map<Integer, ClientCatalog.Npc>> gameNames = newGameNames();
    private @Nullable Census census;
    private @Nullable List<String> censusTiles;
    private @Nullable ChoiceSpawns censusSpawns;

    private @Nullable Object settingsSeen;
    private @Nullable ClientInstallation clientSeen;
    private StartPlaces places = new StartPlaces(List.of());
    private String geodataFolder = "";
    private String scriptFolder = "";

    private final Map<String, float[]> placesDrawn = new TreeMap<>();
    private String shownState = "";
    private List<String> shownNumbers = List.of();
    private String shownThemeLine = "";

    @Override
    public String getLabel() {
        return "Place";
    }

    public void setMusic(MenuMusic shared) {
        music = shared;
    }

    public void setMapImage(long texture, @Nullable TileBounds bounds) {
        mapTexture = texture;
        mapBounds = bounds;
    }

    public @Nullable Location takePlaceRequest() {
        var asked = placeRequested;
        placeRequested = null;
        return asked;
    }

    @Override
    public boolean isAnimating() {
        return music != null && music.isPlaying();
    }

    @Override
    public void draw(Context context, float width, float height) {
        placesDrawn.clear();
        var left = ImGui.getCursorScreenPosX();
        var client = context.client();
        var shared = music;
        if (shared != null) {
            var playing = shared.getCurrent();
            if (playing != null
                    && shared.getPhase() != Phase.WAITING
                    && (client == null || !playing.file().startsWith(client.root()))) {
                shared.stop();
            }
            shared.tick(List.of());
        }
        if (client == null) {
            shownState = "no client";
            CardWidgets.emptyCard(
                    placesDrawn,
                    left,
                    width,
                    "No client chosen",
                    "Choose a client in the list at the left, then a tile on its map.",
                    null,
                    "place.empty");
            return;
        }
        follow(context.loader(), client);
        var tiles = context.tiles();
        if (tiles.isEmpty()) {
            shownState = "no choice";
            CardWidgets.emptyCard(
                    placesDrawn,
                    left,
                    width,
                    "No tiles chosen",
                    "Click a tile on the map, or find a place with the search at the left.",
                    null,
                    "place.empty");
            if (shared != null) {
                drawNowPlaying(shared, left, width);
            }
            return;
        }
        drawPicture(context.fonts(), tiles, context.title(), client.tiles().size(), left, width);
        var base = context.loader().getConfigDirectory();
        var script = ClientFolderStatus.resolve(scriptFolder, base);
        var found = script == null ? null : spawnsOf(script);
        drawNumbers(context.fonts(), tiles, base, script != null, found, left, width);
        if (shared != null) {
            drawTheme(shared, client, tiles, left, width);
        }
        drawLiving(client, script, found, left, width);
        drawNeighbors(tiles, client.tiles(), left, width);
    }

    public void forget() {
        spawns = newSpawns();
        regions = newRegions();
        census = null;
        censusTiles = null;
        censusSpawns = null;
    }

    @Override
    public void close() {
        if (music != null) {
            music.close();
        }
        spawns = newSpawns();
        regions = newRegions();
        gameNames = newGameNames();
        census = null;
        censusTiles = null;
        censusSpawns = null;
        placeRequested = null;
        placesDrawn.clear();
    }

    private void drawPicture(
            FontManager fonts, List<String> tiles, String title, int installed, float left, float width) {
        var top = ImGui.getCursorScreenPosY();
        var bottom = top + PICTURE_HEIGHT;
        var right = left + width;
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(left, top, right, bottom, EMPTY_TILE, HudWidgets.PANEL_RADIUS);
        var chosen = TileBounds.of(tiles);
        var aspect = width / PICTURE_HEIGHT;
        var across = chosen.maxX() - chosen.minX() + 1 + 2 * ROOM;
        var down = chosen.maxY() - chosen.minY() + 1 + 2 * ROOM;
        if (across / down < aspect) {
            across = down * aspect;
        } else {
            down = across / aspect;
        }
        var fromX = (chosen.minX() + chosen.maxX() + 1) * .5f - across * .5f;
        var fromY = (chosen.minY() + chosen.maxY() + 1) * .5f - down * .5f;
        var bounds = mapBounds;
        if (mapTexture != 0 && bounds != null) {
            fromX = within(fromX, across, bounds.minX(), bounds.maxX() + 1);
            fromY = within(fromY, down, bounds.minY(), bounds.maxY() + 1);
        }
        var scale = width / across;
        ImGui.pushClipRect(left, top, right, bottom, true);
        var image = mapBounds;
        if (mapTexture != 0 && image != null) {
            var imageLeft = Math.max(fromX, image.minX());
            var imageTop = Math.max(fromY, image.minY());
            var imageRight = Math.min(fromX + across, image.maxX() + 1);
            var imageBottom = Math.min(fromY + down, image.maxY() + 1);
            if (imageRight > imageLeft && imageBottom > imageTop) {
                var columns = image.maxX() - image.minX() + 1f;
                var rows = image.maxY() - image.minY() + 1f;
                draw.addImage(
                        mapTexture,
                        left + (imageLeft - fromX) * scale,
                        top + (imageTop - fromY) * scale,
                        left + (imageRight - fromX) * scale,
                        top + (imageBottom - fromY) * scale,
                        (imageLeft - image.minX()) / columns,
                        (imageTop - image.minY()) / rows,
                        (imageRight - image.minX()) / columns,
                        (imageBottom - image.minY()) / rows);
            }
            shownState = "picture";
        } else {
            for (var x = (int) Math.floor(fromX); x < fromX + across; x++) {
                for (var y = (int) Math.floor(fromY); y < fromY + down; y++) {
                    var cellLeft = left + (x - fromX) * scale;
                    var cellTop = top + (y - fromY) * scale;
                    draw.addRect(
                            cellLeft + 1,
                            cellTop + 1,
                            cellLeft + scale - 1,
                            cellTop + scale - 1,
                            GeodataWidgets.DIVIDER);
                    var name = x + "_" + y;
                    var nameWidth = ImGui.calcTextSizeX(name);
                    var nameTop = cellTop + 8;
                    if (nameWidth + 8 <= scale
                            && nameTop >= top + 4
                            && nameTop + ImGui.getFontSize() <= bottom - SHADE_HEIGHT) {
                        var nameLeft = cellLeft + (scale - nameWidth) * .5f;
                        draw.addText(
                                nameLeft, nameTop, tiles.contains(name) ? HudWidgets.TEXT : GeodataWidgets.FAINT, name);
                        placesDrawn.put(
                                "place.cell/" + name,
                                new float[] {nameLeft, nameTop, nameLeft + nameWidth, nameTop + ImGui.getFontSize()});
                    }
                }
            }
            shownState = "no picture";
        }
        for (var tile : tiles) {
            var cellLeft = left + (TileBounds.x(tile) - fromX) * scale;
            var cellTop = top + (TileBounds.y(tile) - fromY) * scale;
            draw.addRect(cellLeft, cellTop, cellLeft + scale, cellTop + scale, HudWidgets.ACCENT, 0, 0, 1.5f);
        }
        drawPlaceNames(
                draw, left, top, right, bottom - SHADE_HEIGHT, fromX, fromY, scale, mapTexture != 0 && image != null);
        var shadeTop = bottom - SHADE_HEIGHT;
        draw.addRectFilledMultiColor(left, shadeTop, right, bottom, SHADE, SHADE, SHADE_DARK, SHADE_DARK);
        ImGui.popClipRect();
        draw.addRect(left, top, right, bottom, GeodataWidgets.DIVIDER, HudWidgets.PANEL_RADIUS, 0, 1);
        var font = fonts.getHeadingFont(TITLE_SIZE);
        var detail = tiles.size() == 1 ? "1 tile · " + tiles.getFirst() : StartPlaces.detail(tiles, installed);
        var titleTop = bottom - PAD - ImGui.getFontSize() - 4 - TITLE_SIZE;
        if (font != null) {
            ImGui.pushFont(font, TITLE_SIZE);
            var shown = HudWidgets.truncateToWidth(title, width - 2 * PAD);
            ImGui.popFont();
            draw.addText(font, TITLE_SIZE, left + PAD, titleTop, HudWidgets.TEXT, shown);
        } else {
            draw.addText(left + PAD, titleTop, HudWidgets.TEXT, title);
        }
        draw.addText(left + PAD, bottom - PAD - ImGui.getFontSize(), GeodataWidgets.MUTED, detail);
        placesDrawn.put("place.picture", new float[] {left, top, right, bottom});
        ImGui.setCursorScreenPos(left, bottom + GAP);
        ImGui.dummy(0, 0);
    }

    private void drawPlaceNames(
            ImDrawList draw,
            float left,
            float top,
            float right,
            float bottom,
            float fromX,
            float fromY,
            float scale,
            boolean named) {
        var taken = new ArrayList<float[]>();
        for (var place : places.getPlaces()) {
            var dotX =
                    left + (place.x() / WorldDimensions.TILE_SIZE + WorldDimensions.TILE_ZERO_COORD_X - fromX) * scale;
            var dotY =
                    top + (place.y() / WorldDimensions.TILE_SIZE + WorldDimensions.TILE_ZERO_COORD_Y - fromY) * scale;
            if (dotX < left + 4 || dotX > right - 4 || dotY < top + 4 || dotY > bottom) {
                continue;
            }
            draw.addCircleFilled(dotX, dotY, 3, HudWidgets.TEXT, 12);
            var name = StartPlaces.shortName(place.name());
            var labelWidth = ImGui.calcTextSizeX(name) + 10;
            var box = new float[] {dotX - labelWidth * .5f, dotY - 24, dotX + labelWidth * .5f, dotY - 4};
            var inside = box[0] >= left + 4 && box[2] <= right - 4 && box[1] >= top + 4;
            var free = taken.stream()
                    .noneMatch(
                            other -> box[0] < other[2] && other[0] < box[2] && box[1] < other[3] && other[1] < box[3]);
            if (named && inside && free) {
                taken.add(box);
                draw.addRectFilled(box[0], box[1], box[2], box[3], ImColor.rgba(13, 18, 23, 200), 4f);
                draw.addText(box[0] + 5, box[1] + (20 - ImGui.getFontSize()) * .5f, HudWidgets.TEXT, name);
            }
        }
    }

    private void drawNumbers(
            FontManager fonts,
            List<String> tiles,
            Path base,
            boolean hasScripts,
            @Nullable Spawns found,
            float left,
            float width) {
        var numbers = new ArrayList<CardWidgets.Number>();
        if (hasScripts && (found == null || found.tiles() != null)) {
            var counted = censusOf(tiles, found);
            numbers.add(new CardWidgets.Number(
                    counted == null ? "…" : CardWidgets.compact(counted.npcs()),
                    counted != null && counted.npcs() == 1 ? "NPC spawn" : "NPC spawns",
                    "NPCs of the makers whose zones or fixed NPCs lie in the chosen tiles, in npcpos.txt, events"
                            + " too, as the NPC Spawns panel counts them."));
        }
        var named = 0;
        var chosen = Set.copyOf(tiles);
        for (var place : places.getPlaces()) {
            if (chosen.contains(StartPlaces.tileOf(place))) {
                named++;
            }
        }
        numbers.add(new CardWidgets.Number(
                Integer.toString(named),
                named == 1 ? "named place" : "named places",
                "Named places of Settings, Places, that lie in the chosen tiles."));
        var folder = ClientFolderStatus.resolve(geodataFolder, base);
        if (folder == null) {
            numbers.add(new CardWidgets.Number(
                    "not set", "geodata regions", "Set the geodata folder in Settings of this client."));
        } else {
            var key = folder.toString();
            var known = regions.isAnswered(key) ? regions.get(key) : null;
            if (!regions.isAnswered(key)) {
                regions.get(key);
            }
            var have = 0;
            if (known != null) {
                for (var tile : tiles) {
                    if (known.contains(tile)) {
                        have++;
                    }
                }
            }
            numbers.add(new CardWidgets.Number(
                    known == null ? "…" : have + " of " + tiles.size(),
                    "geodata regions",
                    "Region files of the chosen tiles in the geodata folder of this client: " + key));
        }
        CardWidgets.numberRow(placesDrawn, fonts, numbers, left, width, "place.numbers");
        shownNumbers = numbers.stream()
                .map(number -> number.label() + "=" + number.value())
                .toList();
    }

    private void drawTheme(MenuMusic shared, ClientInstallation client, List<String> tiles, float left, float width) {
        var naming = places.findNamingPlace(tiles);
        var tile = naming == null ? tiles.getFirst() : StartPlaces.tileOf(naming);
        var theme = shared.getTheme(client, tile, places.findPlacesIn(tile));
        if (theme == null || theme.track() == null) {
            drawNowPlaying(shared, left, width);
            return;
        }
        var track = theme.track();
        var inner = width - 2 * PAD;
        var top = CardWidgets.beginCard(left);
        ImGui.textColored(GeodataWidgets.MUTED, "THEME OF THIS PLACE");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var current = shared.getCurrent();
        var ours = current != null && current.file().equals(track.file());
        var playing = ours && (shared.getPhase() == Phase.PLAYING || shared.getPhase() == Phase.STARTING);
        var size = HudWidgets.ROUND_SMALL;
        var pressed = HudWidgets.roundButton(
                "place-theme",
                playing ? Glyph.PAUSE : Glyph.PLAY,
                false,
                playing ? "Pause" : "Play the music of this place");
        if (pressed) {
            if (ours && shared.getPhase() != Phase.WAITING && shared.getPhase() != Phase.FAILED) {
                shared.toggle();
            } else {
                shared.play(track, theme);
            }
        }
        placesDrawn.put("place.theme-play", new float[] {x, y, x + size, y + size});
        var draw = ImGui.getWindowDrawList();
        var textX = x + size + 12;
        var room = inner - size - 12;
        var time = ours
                ? MenuMusic.clock(shared.getPosition()) + " / " + MenuMusic.clock(track.seconds())
                : MenuMusic.clock(track.seconds());
        draw.addText(textX + room - ImGui.calcTextSizeX(time), y + 2, GeodataWidgets.MUTED, time);
        draw.addText(
                textX,
                y + 2,
                HudWidgets.TEXT,
                HudWidgets.truncateToWidth(track.name(), room - ImGui.calcTextSizeX(time) - 12));
        var barY = y + size - 8;
        draw.addRectFilled(textX, barY - 2, textX + room, barY + 2, GeodataWidgets.DIVIDER, 2f);
        var length = track.seconds();
        if (ours && !Double.isNaN(length) && length > 0) {
            var share = (float) Math.clamp(shared.getPosition() / length, 0, 1);
            draw.addRectFilled(textX, barY - 2, textX + room * share, barY + 2, HudWidgets.ACCENT, 2f);
        }
        ImGui.setCursorScreenPos(x, y + size + 6);
        var line = ours && shared.getPhase() == Phase.FAILED
                ? "This track cannot be played."
                : ours && shared.getPhase() == Phase.NO_OUTPUT
                        ? "This computer gives no sound output."
                        : theme.place().isEmpty()
                                ? "The music of the largest music zone of tile " + theme.tile() + ", by day."
                                : "The client's own music, as the game plays it in " + theme.place() + ".";
        shownThemeLine = line;
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + inner);
        ImGui.textColored(GeodataWidgets.FAINT, line);
        ImGui.popTextWrapPos();
        if (!ours && shared.isPlaying()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            pauseLink(shared);
        }
        CardWidgets.endCard(placesDrawn, left, width, top, "place.theme");
    }

    private void drawNowPlaying(MenuMusic shared, float left, float width) {
        if (!shared.isPlaying() || shared.getCurrent() == null) {
            return;
        }
        var top = CardWidgets.beginCard(left);
        ImGui.textColored(GeodataWidgets.MUTED, "NOW PLAYING");
        pauseLink(shared);
        CardWidgets.endCard(placesDrawn, left, width, top, "place.now-playing");
    }

    private void pauseLink(MenuMusic shared) {
        var current = shared.getCurrent();
        if (current == null) {
            return;
        }
        var label = "Pause " + current.name();
        var linkX = ImGui.getCursorScreenPosX();
        var linkY = ImGui.getCursorScreenPosY();
        if (CardWidgets.link("place-pause", label, "Pauses the music that plays now.")) {
            shared.toggle();
        }
        placesDrawn.put(
                "place.theme-pause",
                new float[] {linkX, linkY, linkX + ImGui.calcTextSizeX(label), linkY + ImGui.getFontSize()});
    }

    private void drawLiving(
            ClientInstallation client, @Nullable Path script, @Nullable Spawns found, float left, float width) {
        if (script == null) {
            CardWidgets.emptyCard(
                    placesDrawn,
                    left,
                    width,
                    "No server script folder",
                    "Set it in Settings of this client to see the NPC spawns of the chosen tiles.",
                    null,
                    "place.living");
            return;
        }
        if (found != null && found.tiles() == null) {
            CardWidgets.emptyCard(
                    placesDrawn, left, width, "Cannot read the NPC spawns", found.problem(), null, "place.living");
            return;
        }
        var counted = census;
        var inner = width - 2 * PAD;
        var top = CardWidgets.beginCard(left);
        ImGui.textColored(GeodataWidgets.MUTED, "WHO SPAWNS HERE");
        if (counted == null) {
            ImGui.textColored(GeodataWidgets.FAINT, "…");
        } else if (counted.kinds().isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No NPC spawns in the chosen tiles.");
        } else {
            var key = new NamesKey(client.root(), client.variant());
            var names = gameNames.isAnswered(key) ? gameNames.get(key) : Map.<Integer, ClientCatalog.Npc>of();
            if (!gameNames.isAnswered(key)) {
                gameNames.get(key);
            }
            for (var index = 0; index < Math.min(KINDS, counted.kinds().size()); index++) {
                var kind = counted.kinds().get(index);
                var x = ImGui.getCursorScreenPosX();
                var y = ImGui.getCursorScreenPosY();
                var note = (kind.level() > 0 ? "Lv " + kind.level() + " · " : "") + kind.count();
                var noteWidth = ImGui.calcTextSizeX(note);
                var game = kind.id() < 0 ? null : names.get(kind.id());
                var gameName = game == null ? "" : game.name().strip();
                var shown = gameName.isEmpty() ? kind.name() : gameName;
                ImGui.alignTextToFramePadding();
                ImGui.textColored(
                        HudWidgets.TEXT, HudWidgets.truncateToWidth(shown, inner - noteWidth - GeodataWidgets.BLOCK));
                if (ImGui.isItemHovered()) {
                    HudWidgets.showTooltip(otherName(kind, gameName));
                }
                ImGui.getWindowDrawList()
                        .addText(
                                x + inner - noteWidth,
                                y + (ImGui.getFrameHeight() - ImGui.getFontSize()) * .5f,
                                GeodataWidgets.MUTED,
                                note);
                placesDrawn.put("place.kind/" + kind.name(), new float[] {x, y, x + inner, y + ImGui.getFrameHeight()});
            }
            var more = counted.kinds().size() - KINDS;
            if (more > 0) {
                ImGui.alignTextToFramePadding();
                ImGui.textColored(GeodataWidgets.MUTED, "and " + more + (more == 1 ? " kind more" : " kinds more"));
            }
        }
        if (counted != null && counted.eventNpcs() > 0) {
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + inner);
            ImGui.textColored(
                    GeodataWidgets.FAINT,
                    "Not counting " + CardWidgets.compact(counted.eventNpcs())
                            + (counted.eventNpcs() == 1 ? " NPC" : " NPCs")
                            + " of server events, which come only while an event runs.");
            ImGui.popTextWrapPos();
        }
        CardWidgets.endCard(placesDrawn, left, width, top, "place.living");
    }

    private void drawNeighbors(List<String> tiles, List<String> installed, float left, float width) {
        var available = Set.copyOf(installed);
        var chosen = Set.copyOf(tiles);
        var bounds = TileBounds.of(tiles);
        var middleX = (bounds.minX() + bounds.maxX() + 1) * .5 - WorldDimensions.TILE_ZERO_COORD_X;
        var middleY = (bounds.minY() + bounds.maxY() + 1) * .5 - WorldDimensions.TILE_ZERO_COORD_Y;
        var outside = new ArrayList<Location>();
        for (var place : places.getPlaces()) {
            var tile = StartPlaces.tileOf(place);
            if (!chosen.contains(tile) && available.contains(tile)) {
                outside.add(place);
            }
        }
        if (outside.isEmpty()) {
            return;
        }
        outside.sort(Comparator.comparingDouble(place -> {
            var dx = place.x() / WorldDimensions.TILE_SIZE - middleX;
            var dy = place.y() / WorldDimensions.TILE_SIZE - middleY;
            return dx * dx + dy * dy;
        }));
        var x = left + PAD;
        ImGui.setCursorScreenPos(x, ImGui.getCursorScreenPosY() + 4);
        ImGui.textColored(GeodataWidgets.MUTED, "NEXT TO IT");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var rowLeft = x;
        var rowRight = left + width - PAD;
        var cursorX = rowLeft;
        var cursorY = ImGui.getCursorScreenPosY();
        var shown = new HashSet<String>();
        var rows = 1;
        for (var place : outside) {
            if (shown.size() >= NEIGHBORS) {
                break;
            }
            var name = place.name();
            if (!shown.add(name)) {
                continue;
            }
            var chipWidth = ImGui.calcTextSizeX(name) + 16 + 6 + 22;
            if (cursorX + chipWidth > rowRight && cursorX > rowLeft) {
                if (rows == 2) {
                    break;
                }
                rows++;
                cursorX = rowLeft;
                cursorY += HudWidgets.CONTROL_HEIGHT - 2 + GeodataWidgets.ROW;
            }
            ImGui.setCursorScreenPos(cursorX, cursorY);
            if (GeodataWidgets.chip(
                    "##place-next/" + name, Glyph.PLACE, name, "", false, chipWidth, HudWidgets.CONTROL_HEIGHT - 2)) {
                placeRequested = place;
            }
            GeodataWidgets.tip("Chooses " + name + " on the map, tile " + StartPlaces.tileOf(place) + ".");
            placesDrawn.put(
                    "place.next/" + name,
                    new float[] {cursorX, cursorY, cursorX + chipWidth, cursorY + HudWidgets.CONTROL_HEIGHT - 2});
            cursorX += chipWidth + GeodataWidgets.ROW;
        }
        ImGui.setCursorScreenPos(left, cursorY + HudWidgets.CONTROL_HEIGHT);
        ImGui.dummy(0, 0);
    }

    private @Nullable Census censusOf(List<String> tiles, @Nullable Spawns found) {
        var laid = found == null ? null : found.tiles();
        if (laid == null) {
            census = null;
            return null;
        }
        if (laid != censusSpawns || !tiles.equals(censusTiles)) {
            census = laid.getCensus(tiles);
            censusSpawns = laid;
            censusTiles = List.copyOf(tiles);
        }
        return census;
    }

    private @Nullable Spawns spawnsOf(Path script) {
        var key = script.toString();
        if (spawns.isAnswered(key)) {
            return spawns.get(key);
        }
        spawns.get(key);
        return null;
    }

    private void follow(ConfigLoader loader, ClientInstallation client) {
        var settings = loader.getEffectiveSettings();
        if (settings == settingsSeen && client.equals(clientSeen)) {
            return;
        }
        settingsSeen = settings;
        clientSeen = client;
        var base = loader.getConfigDirectory();
        try {
            geodataFolder = ClientProfiles.geodataPath(settings, client.root(), base, client.variant());
            scriptFolder = ClientProfiles.scriptPath(settings, client.root(), base, client.variant());
        } catch (RuntimeException unreadable) {
            geodataFolder = "";
            scriptFolder = "";
        }
        try {
            places = new StartPlaces(loader.load().viewer().camera().locations());
        } catch (RuntimeException unreadable) {
            places = new StartPlaces(List.of());
        }
    }

    private LatestAnswer<String, Spawns> newSpawns() {
        return new LatestAnswer<>(folder -> readSpawns(Path.of(folder)), new Spawns(null, ""), SPAWN_WORKER);
    }

    private LatestAnswer<NamesKey, Map<Integer, ClientCatalog.Npc>> newGameNames() {
        return new LatestAnswer<>(key -> ClientCatalog.readNpcNames(key.root(), key.world()), Map.of());
    }

    private LatestAnswer<String, Set<String>> newRegions() {
        return new LatestAnswer<>(folder -> readRegions(Path.of(folder)), Set.of());
    }

    private static String otherName(ChoiceSpawns.Kind kind, String gameName) {
        if (kind.id() < 0) {
            return "npcdata.txt gives no id for " + kind.name() + ", so the game's name is not known.";
        }
        if (gameName.isEmpty()) {
            return "id " + kind.id() + " · the client has no name for it";
        }
        return kind.name() + " · id " + kind.id();
    }

    private static float within(float start, float length, float least, float most) {
        if (length >= most - least) {
            return start;
        }
        return Math.clamp(start, least, most - length);
    }

    private static Spawns readSpawns(Path chosen) {
        var folder = SpawnSource.resolveFolder(chosen);
        var positions = folder == null ? null : SpawnSource.find(folder, SpawnSource.POSITIONS);
        if (positions == null) {
            return new Spawns(null, "No " + SpawnSource.POSITIONS + " in " + chosen + ".");
        }
        try {
            var state = new NpcPosDocument(NpcPosFile.read(positions)).getState();
            var npcPath = SpawnSource.find(folder, SpawnSource.NPCS);
            var npcs = npcPath == null ? NpcDataTable.empty() : NpcDataTable.read(npcPath);
            return new Spawns(new ChoiceSpawns(state, npcs), "");
        } catch (IOException | RuntimeException failure) {
            return new Spawns(null, "Cannot read " + positions + ": " + failure.getMessage());
        }
    }

    private static Set<String> readRegions(Path folder) {
        var found = new HashSet<String>();
        try (var files = Files.list(folder)) {
            files.forEach(file -> {
                var named = GeoFormat.parse(file.getFileName().toString());
                if (named != null && Files.isRegularFile(file)) {
                    found.add(named.tileX() + "_" + named.tileY());
                }
            });
        } catch (IOException | UncheckedIOException unreadable) {
        }
        return Set.copyOf(found);
    }
}

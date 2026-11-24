package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.AmbientSound;
import com.rost.cruma.core.world.AmbientVolume;
import com.rost.cruma.core.world.EmitterTimeWindows;
import com.rost.cruma.core.world.MapEventCatalog;
import com.rost.cruma.core.world.MapEventCatalog.Actor;
import com.rost.cruma.core.world.MapEventCatalog.Group;
import com.rost.cruma.core.world.MapEventCatalog.Timed;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Clock;
import com.rost.cruma.core.world.MapEventState.Start;
import com.rost.cruma.core.world.ServerEvents;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.core.world.ZoneStateFog;
import com.rost.cruma.core.world.ZoneStateItem;
import com.rost.cruma.core.world.ZoneStateMesh;
import com.rost.cruma.viewer.application.WindowContext.Keyboard;
import com.rost.cruma.viewer.application.WindowContext.Keyboard.Key;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.MapEventTiles.Folds;
import com.rost.cruma.viewer.ui.MapEventTiles.Row;
import com.rost.cruma.viewer.ui.MapEventTiles.Section;
import com.rost.cruma.viewer.ui.MapEventTiles.SectionFolds;
import com.rost.cruma.viewer.ui.MapEventTiles.Tile;
import com.rost.cruma.viewer.ui.MarkerDepth.Spot;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class MapEventsPanel {
    private static final int EVENT = SpawnStyle.EVENT.argb;
    private static final String[] SIDES = {"Both", "Light", "Dark", "None"};
    private static final int[] SIDE_VALUES = {
        MapEventState.BOTH_SIDES, MapEventState.LIGHT, MapEventState.DARK, MapEventState.NO_SIDE
    };
    private static final String[] SWITCH = {"Off", "On"};
    private static final String[] STARTS = {"All shown", "Game start", "Server start"};
    private static final String[] CLOCKS = {Clock.FOLLOW.label, Clock.ALWAYS.label, Clock.OFF.label};
    private static final float MARKER_DISTANCE = 20000f;
    private static final int MARK_BUDGET = 16_384;
    private static final float MIN_BODY = 96f;
    private static final float SCROLL_BAR_ROOM = 8f;
    private static final float CARD_PAD = 10f;
    private static final float CARD_CONTROL = 26f;
    private static final double FRAME_PITCH = -35;
    private static final float CHECK_ROOM = 22;
    private static final int OFF = 0xFF5C6E7E;
    private static final Comparator<Marker> NEAREST_MARKER = Comparator.comparingDouble(Marker::distance)
            .thenComparing(marker -> marker.actor().name());

    private final ImString search = new ImString(64);
    private List<Tile> tiles = List.of();
    private List<Row> eventRows = List.of();
    private final Folds folds = new Folds();
    private final SectionFolds sections = new SectionFolds();
    private final List<Group> listedCastles = new ArrayList<>();
    private final List<Marker> markers = new ArrayList<>();
    private final float[] viewProjection = new float[16];
    private final List<Timed> listedTimed = new ArrayList<>();
    private final ImGuiListClipper clipper = new ImGuiListClipper();
    private MapEventCatalog catalog = MapEventCatalog.EMPTY;
    private List<ZoneStateMesh> zoneMeshes = List.of();
    private List<ZoneStateFog> zoneFogs = List.of();
    private List<ZoneStateRows.Sound> zoneSounds = List.of();
    private List<ZoneStateItem> zoneItems = List.of();
    private Map<String, ZoneStateRows> zoneMaps = Map.of();
    private final Folds zoneFolds = new Folds();
    private final List<ZoneLine> zoneLines = new ArrayList<>();
    private ServerEvents server = ServerEvents.NONE;
    private @Nullable Pick selected;
    private @Nullable Pick hovered;
    private @Nullable Marker pointerMark;
    private @Nullable Pick scrollTo;
    private @Nullable Viewport viewport;
    private float cameraX;
    private float cameraY;
    private float cameraZ;
    private float hour;
    private float requestedHour = Float.NaN;

    private record Pick(Kind kind, int id) {}

    private record Marker(
            Actor actor,
            Pick pick,
            float x,
            float y,
            float distance,
            float pixelsPerUnit,
            float dotDiameter,
            boolean shows)
            implements Spot {}

    private record MarkerView(
            float eyeX,
            float eyeY,
            float eyeZ,
            float forwardX,
            float forwardY,
            float forwardZ,
            boolean ortho,
            float scale,
            int width,
            int height,
            Viewport area) {}

    private record ZoneLine(ZoneStateRows rows, int state) {
        static final int HEADER = -1;
    }

    private enum Kind {
        EVENT,
        CASTLE,
        TIME
    }

    static MapEventState stepEvent(MapEventState state, int id) {
        return state.withEvent(id, !state.isOn(id));
    }

    static MapEventState stepCastle(MapEventState state, int castle) {
        return state.withCastleSide(castle, SIDE_VALUES[(sideIndex(state, castle) + 1) % SIDE_VALUES.length]);
    }

    static MapEventState stepClock(MapEventState state, String windows) {
        var clocks = Clock.values();
        return state.withClock(windows, clocks[(state.clock(windows).ordinal() + 1) % clocks.length]);
    }

    static String timeStatus(MapEventState state, Timed timed, float hour) {
        return switch (state.clock(timed.windows().label())) {
            case FOLLOW -> timed.isOn(state, hour) ? "Showing" : "Hidden";
            case ALWAYS -> "Always";
            case OFF -> "Off";
        };
    }

    static String timedDetail(Timed timed) {
        var parts = new ArrayList<String>();
        var effects = timed.effectCount();
        if (effects > 0) {
            parts.add(String.format(Locale.ROOT, "%,d %s", effects, effects == 1 ? "effect" : "effects"));
        }
        var sounds = timed.soundCount();
        if (sounds > 0) {
            parts.add(String.format(Locale.ROOT, "%,d %s", sounds, sounds == 1 ? "sound" : "sounds"));
        }
        var names = timed.effects().isEmpty() ? timed.soundNames() : timed.effects();
        if (!names.isEmpty()) {
            parts.add(String.join(", ", names));
        }
        parts.add(String.join(", ", timed.maps()));
        return String.join(" · ", parts);
    }

    static String timedWords(boolean effects, boolean sounds) {
        if (effects && sounds) {
            return "these effects show and these sounds play";
        }
        return sounds ? "these sounds play" : "these effects show";
    }

    static String timedHint(boolean effects, boolean sounds, float hour) {
        var words = timedWords(effects, sounds);
        return Character.toUpperCase(words.charAt(0))
                + words.substring(1)
                + " only in their game hours. Time of day is now "
                + EmitterTimeWindows.clock(hour)
                + ".";
    }

    public void setCatalog(MapEventCatalog catalog) {
        this.catalog = catalog;
        if (selected != null && group(selected) == null) {
            selected = null;
        }
    }

    public MapEventCatalog getCatalog() {
        return catalog;
    }

    public void setZoneStateMeshes(List<ZoneStateMesh> meshes) {
        zoneMeshes = List.copyOf(meshes);
        refreshZoneStates();
    }

    public List<ZoneStateMesh> getZoneStateMeshes() {
        return zoneMeshes;
    }

    public void setZoneStateFogs(List<ZoneStateFog> fogs) {
        zoneFogs = List.copyOf(fogs);
        refreshZoneStates();
    }

    public void setZoneStateSounds(List<AmbientSound> sounds) {
        setZoneStateSounds(sounds, List.of());
    }

    public void setZoneStateSounds(List<AmbientSound> sounds, List<AmbientVolume> volumes) {
        zoneSounds = List.copyOf(ZoneStateRows.soundsOf(sounds, volumes));
        refreshZoneStates();
    }

    public void setZoneStateItems(List<ZoneStateItem> items) {
        zoneItems = List.copyOf(items);
        refreshZoneStates();
    }

    public void setServerEvents(ServerEvents server) {
        this.server = server;
    }

    public float consumeRequestedHour() {
        var requested = requestedHour;
        requestedHour = Float.NaN;
        return requested;
    }

    public MapEventState drawPanel(MapEventState state, Camera camera, float hour) {
        this.hour = hour;
        keepCameraPlace(camera);
        hovered = null;
        if (catalog.isEmpty() && zoneMaps.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No map events in the loaded tiles.");
            ImGui.sameLine(0, 4);
            HudWidgets.helpMark("terms/map-event");
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint("Towns, castle towns and some dungeons have them.");
            space(GeodataWidgets.BLOCK);
            HudWidgets.guideLink("What map events are and where they show", "events/what-are-map-events");
            return state;
        }
        var next = drawTop(state);
        var query = currentQuery();
        filter(query, next);
        var pick = selected;
        var picked = pick == null ? null : group(pick);
        var card = pick == null || picked == null ? 0 : cardHeight(pick.kind());
        space(GeodataWidgets.BLOCK);
        var body = Math.max(MIN_BODY, ImGui.getContentRegionAvailY() - (card > 0 ? card + GeodataWidgets.BLOCK : 0));
        var width = ImGui.getContentRegionAvailX();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.setNextWindowContentSize(width, 0);
        ImGui.beginChild("##mapEventsBody", width + SCROLL_BAR_ROOM, body, false);
        ImGui.popStyleVar();
        next = drawSections(next, query, camera);
        space(GeodataWidgets.ROW);
        ImGui.endChild();
        if (pick != null && picked != null) {
            space(GeodataWidgets.BLOCK);
            next = drawCard(next, picked, pick, camera, card);
        }
        return next;
    }

    public void drawViewport(
            Camera camera,
            Viewport viewport,
            MapEventState state,
            float hour,
            boolean panelOpen,
            boolean helpOpen,
            boolean pointerFree,
            GeoOverlayLines marks) {
        this.viewport = viewport;
        this.hour = hour;
        pointerMark = null;
        var io = ImGui.getIO();
        var width = (int) io.getDisplaySizeX();
        var height = (int) io.getDisplaySizeY();
        if (!panelOpen || catalog.isEmpty() || width <= 0 || height <= 0) {
            markers.clear();
            marks.clear();
            return;
        }
        keepCameraPlace(camera);
        collectMarkers(camera, state, width, height, viewport);
        var mouseX = ImGui.getMousePosX();
        var mouseY = ImGui.getMousePosY();
        var inViewport = mouseX >= viewport.left()
                && mouseX < viewport.right()
                && mouseY >= viewport.top()
                && mouseY < viewport.bottom();
        var captured = !pointerFree
                || !inViewport
                || io.getWantCaptureMouse()
                || ImGui.isMouseDown(1)
                || ImGui.isMouseDown(2)
                || ImGui.isMouseDragging(0);
        var under = MarkerDepth.pick(markers, mouseX, mouseY, captured);
        pointerMark = under < 0 ? null : markers.get(under);
        if (pointerMark != null) {
            hovered = pointerMark.pick();
        }
        publishMarks(marks, io.getDisplaySizeX(), camera.isOrtho());
        var draw = ImGui.getBackgroundDrawList();
        draw.pushClipRect(viewport.left(), viewport.top(), viewport.right(), viewport.bottom(), false);
        var marked = hovered != null ? hovered : selected;
        var group = marked == null ? null : group(marked);
        if (group != null) {
            drawFocus(draw, camera, group, marked, state, width, height, pointerMark == null);
        }
        if (pointerMark != null) {
            drawHoverCard(pointerMark);
        }
        var target = pointerMark != null ? pointerMark.pick() : selected;
        if (!helpOpen && target != null) {
            var items = new ArrayList<String[]>();
            if (selected != null) {
                items.add(new String[] {"F", "frame"});
            }
            items.add(new String[] {"T", stepWords(target.kind())});
            if (selected != null) {
                items.add(new String[] {"Esc", "deselect"});
            }
            GeodataWidgets.hintStrip(draw, viewport, "MAP EVENTS", items, new ArrayList<>());
        }
        draw.popClipRect();
    }

    public boolean isPointerOnMarker() {
        return pointerMark != null;
    }

    public void clickMarker(Camera camera, boolean twice) {
        var mark = pointerMark;
        if (mark == null) {
            return;
        }
        var pick = mark.pick();
        reveal(pick);
        if (twice) {
            frame(camera, pick);
        }
    }

    public @Nullable String showNearest(Camera camera) {
        keepCameraPlace(camera);
        Pick best = null;
        String name = null;
        var bestDistance = Double.MAX_VALUE;
        var kinds = new Kind[] {Kind.EVENT, Kind.CASTLE, Kind.TIME};
        var groups = List.of(
                catalog.getEvents(),
                catalog.getCastles(),
                catalog.getTimed().stream().map(Timed::places).toList());
        for (var k = 0; k < kinds.length; k++) {
            for (var group : groups.get(k)) {
                if (group.actors().isEmpty()) {
                    continue;
                }
                var actor = group.nearest(cameraX, cameraY, cameraZ);
                var distance = Math.hypot(Math.hypot(actor.x() - cameraX, actor.y() - cameraY), actor.z() - cameraZ);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new Pick(kinds[k], group.id());
                    name = title(group, best);
                }
            }
        }
        if (best == null) {
            return null;
        }
        reveal(best);
        frame(camera, best);
        return name;
    }

    public Map<String, List<Integer>> getZoneStateMaps() {
        var maps = new LinkedHashMap<String, List<Integer>>();
        zoneMaps.forEach((map, rows) -> maps.put(map, rows.getStates()));
        return maps;
    }

    public Map<String, Integer> getZoneStateDefaults() {
        var defaults = new LinkedHashMap<String, Integer>();
        zoneMaps.forEach((map, rows) -> {
            if (rows.getDefaultState() != MapEventState.AS_THE_MAP) {
                defaults.put(map, rows.getDefaultState());
            }
        });
        return defaults;
    }

    public boolean isEmpty() {
        return catalog.isEmpty() && zoneMaps.isEmpty();
    }

    public MapEventState handleKeys(Keyboard keys, Camera camera, boolean panelOpen, MapEventState state) {
        if (!panelOpen || ImGui.getIO().getWantTextInput()) {
            return state;
        }
        if (selected != null && keys.isClicked(Key.F)) {
            frame(camera, selected);
        }
        var target = pointerMark != null ? pointerMark.pick() : selected;
        return target != null && keys.isClicked(Key.T) ? step(state, target) : state;
    }

    public boolean handleEscape() {
        if (selected == null) {
            return false;
        }
        selected = null;
        return true;
    }

    private void refreshZoneStates() {
        zoneMaps = ZoneStateRows.byMap(zoneMeshes, zoneFogs, zoneSounds, zoneItems);
    }

    private void reveal(Pick pick) {
        selected = pick;
        scrollTo = pick;
        var query = currentQuery();
        sections.open(
                switch (pick.kind()) {
                    case CASTLE -> Section.CASTLES;
                    case TIME -> Section.BY_TIME;
                    case EVENT -> Section.EVENTS;
                },
                query);
        var group = group(pick);
        if (pick.kind() == Kind.EVENT && group != null) {
            folds.open(group.nearest(cameraX, cameraY, cameraZ).map(), query);
        }
    }

    private String currentQuery() {
        return search.get().trim().toLowerCase(Locale.ROOT);
    }

    private MapEventState drawTop(MapEventState state) {
        var next = state;
        var effects = 0;
        for (var group : catalog.getEvents()) {
            effects += group.actors().size();
        }
        for (var group : catalog.getCastles()) {
            effects += group.actors().size();
        }
        for (var timed : catalog.getTimed()) {
            effects += timed.group().actors().size();
        }
        ImGui.textColored(GeodataWidgets.MUTED, summary(effects));
        space(GeodataWidgets.ROW);
        var starts = server.isEmpty() && state.start() != Start.SERVER_START ? 2 : 3;
        var start = GeodataWidgets.segmented(
                "map-event-start", Arrays.copyOf(STARTS, starts), state.start().ordinal());
        if (start >= 0 && start != state.start().ordinal()) {
            next = (switch (Start.values()[start]) {
                        case ALL_SHOWN -> MapEventState.ALL_SHOWN;
                        case GAME_START -> MapEventState.GAME_START;
                        case SERVER_START -> MapEventState.serverStart(server.onAtStart());
                    })
                    .withClocks(state.clocks())
                    .withZoneStates(state.zoneStates());
        }
        GeodataWidgets.tip(
                "All shown: every effect shows. Game start: the effects are hidden as the game hides them until the "
                        + "server turns them on. "
                        + (starts == 3
                                ? "Server start: what the server turns on when it starts, from areadata.txt of its"
                                        + " script folder. "
                                : "With the server's script folder in Settings, Server start shows what the server"
                                        + " turns on when it starts. ")
                        + "Another start clears the changes of events and castles.");
        space(GeodataWidgets.ROW);
        next = drawChanges(next);
        space(GeodataWidgets.BLOCK);
        GeodataWidgets.searchField(
                "##mapEventSearch", "Search events by id, name or map", search, ImGui.getContentRegionAvailX(), false);
        GeodataWidgets.tip(
                "An event id such as 22226292, a map such as 22_22, a castle such as Giran, a name of the server's"
                        + " scripts such as eventzone, or hours or an effect such as 02:00 or bloodrain.");
        return next;
    }

    private MapEventState drawSections(MapEventState state, String query, Camera camera) {
        var next = state;
        var first = true;
        if (!catalog.getCastles().isEmpty()) {
            if (sectionHeader(
                    Section.CASTLES,
                    "CASTLES",
                    listedCastles.size(),
                    catalog.getCastles().size(),
                    query,
                    first)) {
                next = drawCastles(next, query, camera);
            }
            first = false;
        }
        if (!catalog.getTimed().isEmpty()) {
            if (sectionHeader(
                    Section.BY_TIME,
                    "BY TIME OF DAY",
                    listedTimed.size(),
                    catalog.getTimed().size(),
                    query,
                    first)) {
                next = drawTimed(next, query, camera);
            }
            first = false;
        }
        if (!zoneMaps.isEmpty()) {
            var listedMaps = listedZoneMaps(query);
            if (sectionHeader(Section.ZONE_STATES, "ZONE STATES", listedMaps.size(), zoneMaps.size(), query, first)) {
                next = drawZoneStates(next, listedMaps, query);
            }
            first = false;
        }
        var listed = 0;
        for (var tile : tiles) {
            listed += tile.events().size();
        }
        if (sectionHeader(Section.EVENTS, "EVENTS", listed, catalog.getEvents().size(), query, first)) {
            next = drawEvents(next, query, camera);
        }
        scrollTo = null;
        return next;
    }

    private boolean sectionHeader(Section section, String title, int listed, int total, String query, boolean first) {
        if (!first) {
            space(GeodataWidgets.SECTION);
        }
        var matches = listed > 0;
        var open = sections.isOpen(section, matches, query);
        var count = listed < total
                ? String.format(Locale.ROOT, "%,d of %,d", listed, total)
                : String.format(Locale.ROOT, "%,d", total);
        var article = switch (section) {
            case BY_TIME -> "terms/game-day";
            case ZONE_STATES -> "terms/zone-state";
            case EVENTS -> "terms/map-event";
            default -> null;
        };
        if (SpawnPanelWidgets.sectionRow("map-event-section/" + section, open, title, count, article)) {
            sections.toggle(section, matches, query);
            open = !open;
        }
        if (open) {
            space(GeodataWidgets.ROW);
        }
        return open;
    }

    private MapEventState drawCastles(MapEventState state, String query, Camera camera) {
        var next = state;
        var side = GeodataWidgets.segmented("map-event-sides", SIDES, commonSide(next));
        if (side >= 0) {
            for (var castle : catalog.getCastles()) {
                next = next.withCastleSide(castle.id(), SIDE_VALUES[side]);
            }
        }
        GeodataWidgets.tip(
                "The side of every castle. Light effects show on the light side, dark effects on the dark side, Both"
                        + " shows them all.");
        space(GeodataWidgets.ROW);
        for (var castle : listedCastles) {
            castleRow(castle, next, query, camera);
        }
        if (listedCastles.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No castle matches.");
        }
        return next;
    }

    private MapEventState drawTimed(MapEventState state, String query, Camera camera) {
        var next = state;
        var effects = false;
        var sounds = false;
        for (var timed : catalog.getTimed()) {
            effects |= timed.effectCount() > 0;
            sounds |= timed.soundCount() > 0;
        }
        GeodataWidgets.hint(timedHint(effects, sounds, hour));
        space(GeodataWidgets.ROW);
        var clock = GeodataWidgets.segmented("map-event-clocks", CLOCKS, commonClock(next));
        if (clock >= 0) {
            next = next.withClockForAll(Clock.values()[clock]);
        }
        GeodataWidgets.tip("What every effect and sound by time of day does, also in tiles that load later and in"
                + " exports. By clock: it shows or plays in its own hours, as in the game. Always: it shows or plays"
                + " at every hour. Off: it never shows or plays. It is Effects by time of day in Settings too,"
                + " and it clears the choices of single rows.");
        space(GeodataWidgets.ROW);
        for (var timed : listedTimed) {
            timedRow(timed, next, query, camera);
        }
        if (listedTimed.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, sounds ? "No effect or sound matches." : "No effect matches.");
        }
        return next;
    }

    private MapEventState drawEvents(MapEventState state, String query, Camera camera) {
        if (catalog.getEvents().isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No events in the loaded tiles.");
            return state;
        }
        if (eventRows.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No event matches.");
            return state;
        }
        var next = state;
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        var step = rowHeight + ImGui.getStyle().getItemSpacingY();
        var nearest = tiles.getFirst().map();
        var rows = eventRows;
        var wanted = scrollTo;
        if (wanted != null && wanted.kind() == Kind.EVENT) {
            for (var i = 0; i < rows.size(); i++) {
                var event = rows.get(i).event();
                if (event != null && event.id() == wanted.id()) {
                    keepInView(ImGui.getCursorPosY() + i * step, rowHeight);
                    break;
                }
            }
        }
        clipper.begin(rows.size(), step);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                var row = rows.get(i);
                if (row.event() == null) {
                    tileRow(row.tile(), nearest, query, rowHeight);
                } else {
                    next = eventRow(row.event(), next, query, rowHeight, camera);
                }
            }
        }
        clipper.end();
        return next;
    }

    private List<ZoneStateRows> listedZoneMaps(String query) {
        var every = query.isEmpty() || "zone states".contains(query);
        var listed = new ArrayList<ZoneStateRows>(zoneMaps.size());
        for (var rows : zoneMaps.values()) {
            if (every || rows.getMap().toLowerCase(Locale.ROOT).contains(query)) {
                listed.add(rows);
            }
        }
        var nearest = nearestZoneMap();
        listed.sort(Comparator.comparing((ZoneStateRows rows) -> !rows.getMap().equals(nearest)));
        return listed;
    }

    private String nearestZoneMap() {
        return WorldTile.mapAt(cameraX, cameraY, zoneMaps.keySet());
    }

    private void tileRow(Tile tile, String nearest, String query, float rowHeight) {
        var open = folds.isOpen(tile.map(), nearest, query);
        var count = tile.events().size();
        var events = count < tile.total()
                ? String.format(Locale.ROOT, "%,d of %,d events", count, tile.total())
                : String.format(Locale.ROOT, "%,d %s", count, count == 1 ? "event" : "events");
        var on = tile.on() == count
                ? count == 1 ? "on" : "all on"
                : tile.on() == 0 ? count == 1 ? "off" : "none on" : String.format(Locale.ROOT, "%,d on", tile.on());
        if (SpawnPanelWidgets.groupRow(
                "map-event-tile/" + tile.map(),
                open,
                tile.map(),
                events + " · " + on,
                away(tile.distance()),
                query,
                rowHeight)) {
            folds.toggle(tile.map(), nearest, query);
        }
        GeodataWidgets.tip(open ? "Fold the events of " + tile.map() + "." : "Show the events of " + tile.map() + ".");
    }

    private MapEventState drawChanges(MapEventState state) {
        var next = state;
        var changes = changes(state);
        var left = ImGui.getCursorPosX();
        var width = ImGui.getContentRegionAvailX();
        var reset = !changes.isEmpty();
        var resetWidth = 64f;
        ImGui.alignTextToFramePadding();
        ImGui.textColored(
                reset ? HudWidgets.TEXT : GeodataWidgets.FAINT,
                HudWidgets.truncateToWidth(reset ? changes : "No changes", reset ? width - resetWidth - 8 : width));
        if (reset) {
            ImGui.sameLine(left + width - resetWidth);
            if (HudWidgets.button("map-event-reset", null, "Reset", resetWidth, 24, false, true)) {
                next = new MapEventState(state.start(), state.startOn(), Map.of(), Map.of());
            }
            GeodataWidgets.tip("Clear the changes and go back to the start.");
        }
        GeodataWidgets.hint("Export location and Capture use this choice.");
        return next;
    }

    private String changes(MapEventState state) {
        var parts = new ArrayList<String>();
        var loaded = new HashSet<Integer>();
        for (var event : catalog.getEvents()) {
            loaded.add(event.id());
        }
        for (var on : new boolean[] {false, true}) {
            var events = on ? state.shown() : state.hidden();
            if (events.isEmpty()) {
                continue;
            }
            var outside = events.stream().filter(id -> !loaded.contains(id)).count();
            parts.add(String.format(
                            Locale.ROOT,
                            "%d %s %s",
                            events.size(),
                            events.size() == 1 ? "event" : "events",
                            on ? "on" : "off")
                    + (outside == 0 ? "" : String.format(Locale.ROOT, " (%d not loaded)", outside)));
        }
        var castles = state.castleSides().keySet().stream().sorted().toList();
        if (castles.size() == 1) {
            parts.add(MapEventCatalog.castleName(castles.getFirst()) + " "
                    + MapEventState.sideName(state.castleSide(castles.getFirst())));
        } else if (!castles.isEmpty()) {
            parts.add(castles.size() + " castles set");
        }
        if (state.clockForAll() != Clock.FOLLOW) {
            parts.add("time windows " + state.clockForAll().key);
        }
        var apart = state.clocksApart();
        if (apart.size() == 1) {
            var windows = apart.keySet().iterator().next();
            parts.add(windows + " " + apart.get(windows).key);
        } else if (!apart.isEmpty()) {
            parts.add(apart.size() + " time windows set");
        }
        if (!state.zoneStates().isEmpty()) {
            parts.add(state.zoneStates().getSummary());
        }
        return String.join(" · ", parts);
    }

    private MapEventState drawZoneStates(MapEventState state, List<ZoneStateRows> listed, String query) {
        var next = state;
        GeodataWidgets.hint("Each map has its own zone state, as the server sends one for each map.");
        space(GeodataWidgets.ROW);
        if (listed.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No map matches.");
            return next;
        }
        var nearest = nearestZoneMap();
        zoneLines.clear();
        for (var rows : listed) {
            zoneLines.add(new ZoneLine(rows, ZoneLine.HEADER));
            if (zoneFolds.isOpen(rows.getMap(), nearest, query)) {
                zoneLines.add(new ZoneLine(rows, MapEventState.AS_THE_MAP));
                for (var zoneState : rows.getStates()) {
                    zoneLines.add(new ZoneLine(rows, zoneState));
                }
            }
        }
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        clipper.begin(zoneLines.size(), rowHeight + ImGui.getStyle().getItemSpacingY());
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                var line = zoneLines.get(i);
                if (line.state() == ZoneLine.HEADER) {
                    zoneMapRow(line.rows(), next, nearest, query, rowHeight);
                } else if (line.state() == MapEventState.AS_THE_MAP) {
                    next = asTheMapRow(line.rows(), next);
                } else {
                    next = zoneStateRow(line.rows(), line.state(), next);
                }
            }
        }
        clipper.end();
        return next;
    }

    private void zoneMapRow(ZoneStateRows rows, MapEventState state, String nearest, String query, float rowHeight) {
        var map = rows.getMap();
        var open = zoneFolds.isOpen(map, nearest, query);
        var chosen = rows.getChoice(state.zoneStates());
        if (SpawnPanelWidgets.groupRow(
                "map-event-zone-map/" + map,
                open,
                map + " · " + (chosen == MapEventState.AS_THE_MAP ? "As the map" : "State " + chosen),
                rows.getStatesText(),
                "",
                query,
                rowHeight)) {
            zoneFolds.toggle(map, nearest, query);
            open = !open;
        }
        if (ImGui.isItemHovered()) {
            GeodataWidgets.tip((open ? "Fold" : "Show") + " the zone states of " + map + ". The client shows some"
                    + " meshes only in one state of their zone, such as the two looks of the Ice Queen's Castle, and"
                    + " the server sends a state for each map. The choice holds also when the tiles load later and in"
                    + " exports. In Essence effects, terrain layers, fogs, water and the sky have states too, and a"
                    + " zone in state 0 shows none of the meshes and effects of a state. So a map of Essence whose"
                    + " zones start in state 0 starts in State 1 here, until you choose another.");
        }
    }

    private MapEventState asTheMapRow(ZoneStateRows rows, MapEventState state) {
        var map = rows.getMap();
        var chosen = rows.getChoice(state.zoneStates()) == MapEventState.AS_THE_MAP;
        var stateless = rows.getDefaultState() != MapEventState.AS_THE_MAP;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var next = choiceRow("map-event-zone/" + map + "/0", chosen, state, rows, MapEventState.AS_THE_MAP);
        GeodataWidgets.tip((stateless
                        ? "Each zone of " + map + " is in the state the map gives it: state 0, where the game"
                                + " shows nothing of a state. The game shows the map this way only until the"
                                + " server sends a state."
                        : "Each zone of " + map + " is in the state the map gives it, as when the game" + " starts.")
                + (chosen ? "" : " The other maps keep their states."));
        SpawnPanelWidgets.listRow(
                ImGui.getWindowDrawList(),
                x,
                y,
                width,
                chosen ? EVENT : OFF,
                "As the map",
                stateless ? "State 0, as before the server sends a state" : "Each zone in the state the map gives it",
                "",
                "");
        return next;
    }

    private MapEventState zoneStateRow(ZoneStateRows rows, int zoneState, MapEventState state) {
        var map = rows.getMap();
        var chosen = rows.getChoice(state.zoneStates()) == zoneState;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var next = choiceRow("map-event-zone/" + map + "/" + zoneState, chosen, state, rows, zoneState);
        var row = rows.describe(zoneState, state.zoneStates());
        GeodataWidgets.tip(row.tip());
        SpawnPanelWidgets.listRow(
                ImGui.getWindowDrawList(),
                x,
                y,
                width,
                row.showing() ? EVENT : OFF,
                "State " + zoneState,
                row.detail(),
                row.status(),
                "");
        return next;
    }

    private String summary(int effects) {
        var events = catalog.getEvents().size();
        var castles = catalog.getCastles().size();
        var text = String.format(Locale.ROOT, "%,d %s", events, events == 1 ? "event" : "events");
        if (castles > 0) {
            text += String.format(Locale.ROOT, " · %d %s", castles, castles == 1 ? "castle" : "castles");
        }
        if (!catalog.getTimed().isEmpty()) {
            text += String.format(
                    Locale.ROOT, " · %d by time", catalog.getTimed().size());
        }
        return text + String.format(Locale.ROOT, " · %,d %s", effects, effects == 1 ? "effect" : "effects");
    }

    private MapEventState drawCard(MapEventState state, Group group, Pick pick, Camera camera, float height) {
        var next = state;
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x + 14, y + CARD_PAD);
        ImGui.beginGroup();
        var inner = width - 24;
        if (pick.kind() != Kind.EVENT) {
            ImGui.textUnformatted(HudWidgets.truncateToWidth(group.name(), inner));
        } else {
            ImGui.alignTextToFramePadding();
            ImGui.textUnformatted(HudWidgets.truncateToWidth(title(group, pick), inner - 30));
            ImGui.sameLine(inner - 26);
            if (HudWidgets.button("map-event-copy", Glyph.COPY, "", CARD_CONTROL, CARD_CONTROL, false, false)) {
                ImGui.setClipboardText(Integer.toString(pick.id()));
            }
            GeodataWidgets.tip("Copy the event id");
        }
        ImGui.textColored(GeodataWidgets.MUTED, HudWidgets.truncateToWidth(detail(group, pick), inner));
        space(GeodataWidgets.ROW);
        if (pick.kind() == Kind.CASTLE) {
            var side = GeodataWidgets.segmented("map-event-card-side", SIDES, sideIndex(state, pick.id()), inner);
            if (side >= 0) {
                next = state.withCastleSide(pick.id(), SIDE_VALUES[side]);
            }
            GeodataWidgets.tip(
                    "The castle's side. Light effects show on the light side, dark effects on the dark side.");
        } else if (pick.kind() == Kind.TIME) {
            var timed = timed(pick);
            var label = timed.windows().label();
            var clock = state.clock(label);
            var chosen = GeodataWidgets.segmented("map-event-card-clock", CLOCKS, clock.ordinal(), inner);
            if (chosen >= 0) {
                next = state.withClock(label, Clock.values()[chosen]);
            }
            var words = timedWords(timed.effectCount() > 0, timed.soundCount() > 0);
            GeodataWidgets.tip(
                    "By clock: " + words + " in their hours, as in the game. Always: at every hour. Off:" + " never.");
            space(GeodataWidgets.ROW);
            var window = timed.nextWindow(hour);
            var middle = window.middle();
            ImGui.beginDisabled(next.clock(label) != Clock.FOLLOW);
            if (HudWidgets.button(
                    "map-event-time",
                    Glyph.SUN,
                    "Set time to " + EmitterTimeWindows.clock(middle),
                    inner,
                    CARD_CONTROL,
                    false,
                    true)) {
                requestedHour = middle;
            }
            ImGui.endDisabled();
            GeodataWidgets.tip("Time of day goes to the middle of the next window, " + window.label() + ", when "
                    + words + ". Atmosphere in Scene settings changes it back.");
        } else {
            var on = state.isOn(pick.id());
            var chosen = GeodataWidgets.segmented("map-event-card-switch", SWITCH, on ? 1 : 0, inner);
            if (chosen >= 0) {
                next = state.withEvent(pick.id(), chosen == 1);
            }
            GeodataWidgets.tip("Off hides the effects of this event, as before the server turns it on.");
        }
        space(GeodataWidgets.ROW);
        var half = (inner - ImGui.getStyle().getItemSpacingX()) / 2;
        if (HudWidgets.button("map-event-frame", Glyph.FOCUS, "Frame", half, CARD_CONTROL, false, true)) {
            frame(camera, pick);
        }
        GeodataWidgets.tip("Look at its effects nearest to the camera.", "F");
        ImGui.sameLine();
        if (HudWidgets.button("map-event-close", null, "Close", half, CARD_CONTROL, false, true)) {
            selected = null;
        }
        GeodataWidgets.tip("Deselect.", "Esc");
        ImGui.endGroup();
        var bottom = y + height;
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.addRectFilled(
                x, y, x + 4, bottom, SpawnStyle.getImguiColor(EVENT, 1f, 1f), 8f, ImDrawFlags.RoundCornersLeft);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
        return next;
    }

    private void castleRow(Group castle, MapEventState state, String query, Camera camera) {
        var pick = new Pick(Kind.CASTLE, castle.id());
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        if (pick.equals(scrollTo)) {
            keepInView(ImGui.getCursorPosY(), rowHeight);
        }
        if (ImGui.invisibleButton("map-event-castle/" + castle.id(), width, rowHeight)) {
            select(pick, camera);
        }
        rowBackground(pick, x, y, width, rowHeight);
        var draw = ImGui.getWindowDrawList();
        SpawnPanelWidgets.listRow(
                draw,
                x,
                y,
                width,
                EVENT,
                castle.name(),
                detail(castle, pick),
                SIDES[sideIndex(state, castle.id())],
                query);
    }

    private void timedRow(Timed timed, MapEventState state, String query, Camera camera) {
        var group = timed.group();
        var pick = new Pick(Kind.TIME, group.id());
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        if (pick.equals(scrollTo)) {
            keepInView(ImGui.getCursorPosY(), rowHeight);
        }
        if (ImGui.invisibleButton("map-event-time/" + group.id(), width, rowHeight)) {
            select(pick, camera);
        }
        rowBackground(pick, x, y, width, rowHeight);
        SpawnPanelWidgets.listRow(
                ImGui.getWindowDrawList(),
                x,
                y,
                width,
                timed.isOn(state, hour) ? EVENT : OFF,
                group.name(),
                timedDetail(timed),
                timeStatus(state, timed, hour),
                query);
    }

    private int commonClock(MapEventState state) {
        var common = -1;
        for (var timed : catalog.getTimed()) {
            var clock = state.clock(timed.windows().label()).ordinal();
            if (common >= 0 && common != clock) {
                return -1;
            }
            common = clock;
        }
        return common;
    }

    private MapEventState eventRow(Group event, MapEventState state, String query, float rowHeight, Camera camera) {
        var pick = new Pick(Kind.EVENT, event.id());
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var next = state;
        var on = state.isOn(event.id());
        if (ImGui.invisibleButton("map-event/" + event.id(), width, rowHeight)) {
            if (ImGui.getMousePosX() < x + CHECK_ROOM + 4) {
                next = state.withEvent(event.id(), !on);
            } else {
                select(pick, camera);
            }
        }
        rowBackground(pick, x, y, width, rowHeight);
        if (ImGui.isItemHovered() && ImGui.getMousePosX() < x + CHECK_ROOM + 4) {
            HudWidgets.showTooltip(on ? "Turn the event off" : "Turn the event on");
        }
        var draw = ImGui.getWindowDrawList();
        SpawnPanelWidgets.checkBox(draw, x + 4, y + (rowHeight - 16) * .5f, on);
        SpawnPanelWidgets.listRow(
                draw,
                x + CHECK_ROOM,
                y,
                width - CHECK_ROOM,
                on ? EVENT : OFF,
                title(event, pick),
                detail(event, pick),
                away(event),
                query);
        return next;
    }

    private void rowBackground(Pick pick, float x, float y, float width, float rowHeight) {
        var rowHovered = ImGui.isItemHovered();
        if (rowHovered) {
            hovered = pick;
        }
        var pointed = pointerMark != null && pick.equals(pointerMark.pick());
        if (rowHovered || pointed || pick.equals(selected)) {
            ImGui.getWindowDrawList()
                    .addRectFilled(
                            x,
                            y,
                            x + width,
                            y + rowHeight,
                            pick.equals(selected) ? ImColor.rgb(30, 58, 70) : GeodataWidgets.HOVER,
                            6f);
        }
    }

    private String title(Group group, Pick pick) {
        var name = pick.kind() == Kind.EVENT ? server.name(group.id()) : "";
        return name.isEmpty() ? group.name() : name;
    }

    private String detail(Group group, Pick pick) {
        var maps = String.join(", ", group.maps());
        var count = group.actors().size();
        if (pick.kind() == Kind.TIME) {
            return timedDetail(timed(pick));
        }
        if (pick.kind() == Kind.CASTLE) {
            var dark = group.darkCount();
            return String.format(
                    Locale.ROOT, "Castle %d · %s · %d light, %d dark", group.id(), maps, count - dark, dark);
        }
        return (server.name(group.id()).isEmpty() ? "" : group.id() + " · ")
                + maps
                + " · "
                + count
                + (count == 1 ? " effect" : " effects");
    }

    private String away(Group group) {
        var nearest = group.nearest(cameraX, cameraY, cameraZ);
        var dx = nearest.x() - cameraX;
        var dy = nearest.y() - cameraY;
        var dz = nearest.z() - cameraZ;
        return away(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private void filter(String query, MapEventState state) {
        tiles = MapEventTiles.group(
                catalog.getEvents(),
                event -> matches(event, query) || !query.isEmpty() && server.nameContains(event.id(), query),
                state::isOn,
                cameraX,
                cameraY,
                cameraZ);
        eventRows = folds.getRows(tiles, query);
        listedCastles.clear();
        for (var castle : catalog.getCastles()) {
            if (matches(castle, query)) {
                listedCastles.add(castle);
            }
        }
        listedTimed.clear();
        for (var timed : catalog.getTimed()) {
            if (matches(timed.places(), query)
                    || timed.maps().stream()
                            .anyMatch(map -> map.toLowerCase(Locale.ROOT).contains(query))
                    || timed.effects().stream()
                            .anyMatch(effect -> effect.toLowerCase(Locale.ROOT).contains(query))
                    || timed.soundNames().stream()
                            .anyMatch(sound -> sound.toLowerCase(Locale.ROOT).contains(query))) {
                listedTimed.add(timed);
            }
        }
    }

    private void select(Pick pick, Camera camera) {
        selected = pick;
        frame(camera, pick);
    }

    private void frame(Camera camera, Pick pick) {
        var group = group(pick);
        if (group == null) {
            return;
        }
        var position = camera.getWorldFocusPosition();
        Bounds3f bounds = group.boundsNear(position.getX(), position.getY(), position.getZ());
        if (!camera.isOrtho()) {
            var look = camera.getPerspectiveLookDir();
            camera.setDirection(Math.toDegrees(Math.atan2(look.getY(), look.getX())), FRAME_PITCH);
        }
        var io = ImGui.getIO();
        var width = io.getDisplaySizeX();
        var height = io.getDisplaySizeY();
        var area = viewport;
        if (area == null || width <= 0 || height <= 0 || area.right() <= area.left() || area.bottom() <= area.top()) {
            camera.focus(bounds);
            return;
        }
        camera.focus(
                bounds,
                (area.left() + area.right()) / width - 1f,
                1f - (area.top() + area.bottom()) / height,
                (area.right() - area.left()) / width,
                (area.bottom() - area.top()) / height);
    }

    private void keepCameraPlace(Camera camera) {
        var place = camera.getWorldFocusPosition();
        cameraX = place.getX();
        cameraY = place.getY();
        cameraZ = place.getZ();
    }

    private void collectMarkers(Camera camera, MapEventState state, int width, int height, Viewport area) {
        markers.clear();
        camera.copyViewProjection(viewProjection);
        var eye = camera.getPosition();
        var forward = camera.getCursorRayDirection(0, 0);
        var view = new MarkerView(
                eye.getX(),
                eye.getY(),
                eye.getZ(),
                forward.getX(),
                forward.getY(),
                forward.getZ(),
                camera.isOrtho(),
                camera.getProjectionMatrix().get(5),
                width,
                height,
                area);
        for (var castle : catalog.getCastles()) {
            addMarkers(castle, new Pick(Kind.CASTLE, castle.id()), state, view);
        }
        for (var event : catalog.getEvents()) {
            addMarkers(event, new Pick(Kind.EVENT, event.id()), state, view);
        }
        for (var timed : catalog.getTimed()) {
            addMarkers(timed.group(), new Pick(Kind.TIME, timed.group().id()), state, view);
        }
        markers.sort(NEAREST_MARKER);
    }

    private void addMarkers(Group group, Pick pick, MapEventState state, MarkerView view) {
        var reach = MARKER_DISTANCE * MARKER_DISTANCE;
        var m = viewProjection;
        var showsLight = shows(state, pick, false);
        var showsDark = shows(state, pick, true);
        var area = view.area();
        for (var actor : group.actors()) {
            var x = actor.x();
            var y = actor.y();
            var z = actor.z();
            var dx = x - view.eyeX();
            var dy = y - view.eyeY();
            var dz = z - view.eyeZ();
            var squared = dx * dx + dy * dy + dz * dz;
            if (!view.ortho() && squared > reach) {
                continue;
            }
            var w = m[3] * x + m[7] * y + m[11] * z + m[15];
            if (w <= 0) {
                continue;
            }
            var screenX = ((m[0] * x + m[4] * y + m[8] * z + m[12]) / w * .5f + .5f) * view.width();
            var screenY = (.5f - (m[1] * x + m[5] * y + m[9] * z + m[13]) / w * .5f) * view.height();
            var pixelsPerUnit = MarkerDepth.pixelsPerUnit(view.scale(), view.height(), w);
            var margin = MarkerDepth.MAX_DOT;
            if (screenX < area.left() - margin
                    || screenX > area.right() + margin
                    || screenY < area.top() - margin
                    || screenY > area.bottom() + margin) {
                continue;
            }
            var distance = view.ortho()
                    ? dx * view.forwardX() + dy * view.forwardY() + dz * view.forwardZ()
                    : (float) Math.sqrt(squared);
            markers.add(new Marker(
                    actor,
                    pick,
                    screenX,
                    screenY,
                    distance,
                    pixelsPerUnit,
                    MarkerDepth.dotDiameter(pixelsPerUnit),
                    actor.dark() ? showsDark : showsLight));
        }
    }

    private void publishMarks(GeoOverlayLines marks, float viewportPoints, boolean ortho) {
        var kept = 0;
        var budget = MARK_BUDGET;
        while (kept < markers.size()) {
            var marker = markers.get(kept);
            budget -= MarkerDepth.primitives(marker.shows());
            if (budget < 0) {
                break;
            }
            kept++;
        }
        marks.begin();
        for (var index = kept - 1; index >= 0; index--) {
            var marker = markers.get(index);
            var actor = marker.actor();
            var alpha = ortho ? 1f : MarkerDepth.alpha(marker.distance(), MARKER_DISTANCE);
            MarkerDepth.mark(
                    marks,
                    actor.x(),
                    actor.y(),
                    actor.z(),
                    marker.pixelsPerUnit(),
                    SpawnStyle.getImguiColor(EVENT, alpha, 1.1f),
                    marker.shows(),
                    marker == pointerMark);
        }
        marks.publish(viewportPoints);
    }

    private void drawHoverCard(Marker mark) {
        var pick = mark.pick();
        var group = group(pick);
        if (group == null) {
            return;
        }
        var actor = mark.actor();
        var details = new ArrayList<String>(3);
        details.add(
                switch (pick.kind()) {
                    case EVENT -> "Event " + pick.id() + " · " + actor.map();
                    case CASTLE -> "Castle " + pick.id() + " · " + actor.map();
                    case TIME -> String.join(", ", timed(pick).effects()) + " · " + actor.map();
                });
        var count = group.actors().size();
        var effect = actor.name().substring(actor.name().lastIndexOf('.') + 1);
        details.add(effect
                + (pick.kind() == Kind.CASTLE ? (actor.dark() ? " · dark" : " · light") : "")
                + " · "
                + (count == 1
                        ? "1 effect"
                        : String.format(
                                Locale.ROOT, "%d of %,d effects", group.actors().indexOf(actor) + 1, count)));
        var dx = actor.x() - cameraX;
        var dy = actor.y() - cameraY;
        var dz = actor.z() - cameraZ;
        details.add((mark.shows() ? "Shows now · " : "Hidden now · ") + away(Math.sqrt(dx * dx + dy * dy + dz * dz)));
        HudWidgets.showHoverCard(
                ImGui.getID("map-event-mark/" + pick + actor.name()),
                title(group, pick),
                details,
                "Click: open · Double-click: frame · T: " + stepWords(pick.kind()));
    }

    private MapEventState step(MapEventState state, Pick pick) {
        return switch (pick.kind()) {
            case EVENT -> stepEvent(state, pick.id());
            case CASTLE -> stepCastle(state, pick.id());
            case TIME -> {
                var timed = findTimed(pick.id());
                yield timed == null ? state : stepClock(state, timed.windows().label());
            }
        };
    }

    private void drawFocus(
            ImDrawList draw,
            Camera camera,
            Group group,
            Pick pick,
            MapEventState state,
            int width,
            int height,
            boolean named) {
        var nearest = group.nearest(cameraX, cameraY, cameraZ);
        for (var actor : group.actors()) {
            if (!actor.map().equals(nearest.map())) {
                continue;
            }
            var screen = camera.projectToScreenRaw(new Vec3(actor.x(), actor.y(), actor.z()), width, height);
            if (screen != null) {
                draw.addCircle(screen.getX(), screen.getY(), 11, GeodataWidgets.ACCENT, 20, 2);
            }
        }
        var label = camera.projectToScreenRaw(new Vec3(nearest.x(), nearest.y(), nearest.z()), width, height);
        if (label == null || !named) {
            return;
        }
        var text = title(group, pick) + " · "
                + switch (pick.kind()) {
                    case CASTLE -> SIDES[sideIndex(state, pick.id())];
                    case TIME -> timeStatus(state, timed(pick), hour).toLowerCase(Locale.ROOT);
                    case EVENT -> state.isOn(pick.id()) ? "on" : "off";
                };
        var textWidth = ImGui.calcTextSizeX(text);
        var left = label.getX() - textWidth * .5f - 8;
        var top = label.getY() - 20 - ImGui.getFontSize() - 8;
        draw.addRectFilled(
                left, top, left + textWidth + 16, top + ImGui.getFontSize() + 8, ImColor.rgba(16, 24, 31, 230), 6f);
        draw.addText(left + 8, top + 4, HudWidgets.TEXT, text);
    }

    private boolean shows(MapEventState state, Pick pick, boolean dark) {
        return switch (pick.kind()) {
            case CASTLE -> state.showsCastle(pick.id(), dark);
            case TIME -> state.timeRatio(timed(pick).windows(), hour) > 0f;
            case EVENT -> state.isOn(pick.id());
        };
    }

    private int commonSide(MapEventState state) {
        var common = -1;
        for (var castle : catalog.getCastles()) {
            var side = sideIndex(state, castle.id());
            if (common >= 0 && side != common) {
                return -1;
            }
            common = side;
        }
        return common;
    }

    private @Nullable Group group(Pick pick) {
        if (pick.kind() == Kind.TIME) {
            var timed = findTimed(pick.id());
            return timed == null ? null : timed.places();
        }
        for (var group : pick.kind() == Kind.CASTLE ? catalog.getCastles() : catalog.getEvents()) {
            if (group.id() == pick.id()) {
                return group;
            }
        }
        return null;
    }

    private @Nullable Timed findTimed(int id) {
        for (var timed : catalog.getTimed()) {
            if (timed.group().id() == id) {
                return timed;
            }
        }
        return null;
    }

    private Timed timed(Pick pick) {
        return Objects.requireNonNull(findTimed(pick.id()));
    }

    private static MapEventState choiceRow(
            String id, boolean chosen, MapEventState state, ZoneStateRows rows, int zoneState) {
        var next = state;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        if (ImGui.invisibleButton(id, width, rowHeight)) {
            next = state.withZoneState(rows.getMap(), zoneState, rows.getDefaultState());
        }
        if (ImGui.isItemHovered() || chosen) {
            ImGui.getWindowDrawList()
                    .addRectFilled(
                            x,
                            y,
                            x + width,
                            y + rowHeight,
                            chosen ? ImColor.rgb(30, 58, 70) : GeodataWidgets.HOVER,
                            6f);
        }
        return next;
    }

    private static void keepInView(float top, float height) {
        var scroll = ImGui.getScrollY();
        var view = ImGui.getWindowHeight();
        if (top < scroll || top + height > scroll + view) {
            ImGui.setScrollY(Math.max(0, top - (view - height) * .5f));
        }
    }

    private static float cardHeight(Kind kind) {
        var line = ImGui.getTextLineHeight();
        var spacing = ImGui.getStyle().getItemSpacingY();
        var gap = Math.max(0, GeodataWidgets.ROW - 2 * spacing) + spacing;
        var title = kind == Kind.EVENT ? CARD_CONTROL : line;
        var rows = title + spacing + line + spacing + gap + CARD_CONTROL + spacing + gap + CARD_CONTROL;
        if (kind == Kind.TIME) {
            rows += spacing + gap + CARD_CONTROL;
        }
        return CARD_PAD + rows + CARD_PAD;
    }

    private static String away(double distance) {
        return SpawnPanelWidgets.compact(Math.round(distance)) + " away";
    }

    private static boolean matches(Group group, String query) {
        if (query.isEmpty() || group.name().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (var map : group.maps()) {
            if (map.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private static String stepWords(Kind kind) {
        return switch (kind) {
            case EVENT -> "turn on or off";
            case CASTLE -> "next side";
            case TIME -> "next choice";
        };
    }

    private static int sideIndex(MapEventState state, int castle) {
        var side = state.castleSide(castle);
        for (var index = 0; index < SIDE_VALUES.length; index++) {
            if (SIDE_VALUES[index] == side) {
                return index;
            }
        }
        return 3;
    }
}

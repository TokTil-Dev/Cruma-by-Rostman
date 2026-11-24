package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Start;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.CaptureView;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.CutCircle;
import com.rost.cruma.viewer.config.KeptLayers;
import com.rost.cruma.viewer.config.OptionsModel;
import com.rost.cruma.viewer.config.ViewContent;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueFactory;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiComboFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class TileViewsEditor {
    static final int NO_CHOICE = -1;

    public static final String PATH = "capture.output.views";

    private static final float COLUMN_GAP = 32;
    private static final Integer[] SIDES = {
        MapEventState.BOTH_SIDES, MapEventState.LIGHT, MapEventState.DARK, MapEventState.NO_SIDE
    };
    private static final String[] SIDE_NAMES = {"Both", "Light", "Dark", "None"};

    private final OptionsModel model;
    private final Runnable changed;
    private final Map<String, ImString> text = new HashMap<>();
    private final Map<String, String> invalid = new HashMap<>();
    private final Map<String, float[]> places = new HashMap<>();
    private final ImString viewFilter = new ImString(64);
    private int selectedView;
    private boolean focusViewName;
    private boolean revealView;
    private String clientTilesFolder;
    private Set<String> clientTiles = Set.of();
    private @Nullable Set<String> givenTiles;
    private MapEventState clientEvents = MapEventState.ALL_SHOWN;

    public TileViewsEditor(OptionsModel model, Runnable changed) {
        this.model = model;
        this.changed = changed;
    }

    static String freeName(List<CaptureView> views, String tile, String name) {
        var base = name.length() > 56 ? name.substring(0, 56) : name;
        var candidate = base;
        for (int n = 2; isNameTaken(views, tile, candidate); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    static String summary(CaptureView view) {
        var parts = new ArrayList<String>();
        if (view.top() != null && view.bottom() != null) {
            parts.add(view.bottom() + " to " + view.top());
        } else if (view.top() != null) {
            parts.add("below " + view.top());
        } else if (view.bottom() != null) {
            parts.add("above " + view.bottom());
        }
        if (view.hidePassable()) {
            parts.add("no passable");
        }
        if (view.kept().hasAny()) {
            var kept = new ArrayList<String>();
            if (view.kept().terrain()) {
                kept.add("terrain");
            }
            if (view.kept().staticMeshes()) {
                kept.add("meshes");
            }
            if (view.kept().csg()) {
                kept.add("CSG");
            }
            parts.add("keeps " + String.join(", ", kept));
        }
        if (view.circle() != null) {
            parts.add("circle " + view.circle().radius());
        }
        if (view.resolution() != null) {
            parts.add(view.resolution() + " px");
        }
        var content = view.content();
        if (!content.effects()) {
            parts.add("no effects");
        } else {
            var hidden = content.events().size();
            if (content.mapEvents() != null) {
                parts.add(content.mapEvents().label);
            } else if (hidden > 0) {
                parts.add(hidden == 1 ? "1 event hidden" : hidden + " events hidden");
            }
            if (content.castleSides() != null) {
                parts.add("castles "
                        + SIDE_NAMES[List.of(SIDES).indexOf(content.castleSides())].toLowerCase(Locale.ROOT));
            }
        }
        if (content.zoneState() != null) {
            parts.add(content.zoneState() == MapEventState.AS_THE_MAP ? "as the map" : "state " + content.zoneState());
        }
        return String.join(" · ", parts);
    }

    static int clientZoneState(MapEventState client, String tile) {
        var prefix = tile.toLowerCase(Locale.ROOT);
        for (var entry : client.zoneStates().getStates().entrySet()) {
            var map = entry.getKey().toLowerCase(Locale.ROOT);
            if (map.equals(prefix) || map.startsWith(prefix + "_")) {
                return entry.getValue();
            }
        }
        return NO_CHOICE;
    }

    static List<Integer> listOrder(List<CaptureView> views) {
        var order = new ArrayList<Integer>();
        for (int i = 0; i < views.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> views.get(i).mapX())
                .thenComparingInt(i -> views.get(i).mapY())
                .thenComparing(i -> !views.get(i).main()));
        return order;
    }

    public Map<String, String> getInvalid() {
        return Collections.unmodifiableMap(invalid);
    }

    public void setClientTiles(Set<String> tiles) {
        givenTiles = Set.copyOf(tiles);
    }

    public void setClientEvents(MapEventState events) {
        clientEvents = events == null ? MapEventState.ALL_SHOWN : events;
    }

    public void reveal(int index) {
        selectedView = index;
        revealView = true;
    }

    public void selectFirst() {
        var views = parseViews();
        if (views.isEmpty()) {
            return;
        }
        var first = listOrder(views).getFirst();
        if (first != selectedView) {
            forgetViews();
        }
        reveal(first);
    }

    public void draw() {
        ImGui.textWrapped(
                "Extra images of single tiles: tower floors, dungeons under the ground, a tile without passable"
                        + " surfaces such as bushes, or a tile with its own effects, map events and zone state. A main"
                        + " view replaces the tile's image in the radar map. Other views are saved next to it as"
                        + " X_Y_name.");
        ImGui.spacing();
        var views = parseViews();
        selectedView = Math.max(0, Math.min(selectedView, views.size() - 1));
        if (HudWidgets.button("add-view", Glyph.PLUS, "Add view", 104, 28, false, true)) {
            var tile = views.isEmpty() ? "22_22" : views.get(selectedView).tile();
            views = insertView(views, new CaptureView(tile, freeName(views, tile, "view"), null, null, false, false));
        }
        mark("add");
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip("Add a view of the selected view's tile.");
        }
        ImGui.sameLine();
        ImGui.beginDisabled(views.isEmpty());
        if (HudWidgets.button("duplicate-view", Glyph.COPY, "Duplicate", 112, 28, false, true)) {
            var source = views.get(selectedView);
            var name = freeName(views, source.tile(), source.main() ? "view" : source.name() + "-copy");
            views = insertView(
                    views,
                    new CaptureView(
                            source.tile(),
                            name,
                            source.top(),
                            source.bottom(),
                            source.hidePassable(),
                            false,
                            source.resolution(),
                            source.kept(),
                            source.circle(),
                            source.content()));
        }
        mark("duplicate");
        ImGui.sameLine();
        if (HudWidgets.button("remove-view", Glyph.CLOSE, "Remove", 96, 28, false, true)) {
            var next = new ArrayList<>(views);
            next.remove(selectedView);
            storeViews(next);
            forgetViews();
            selectedView = Math.max(0, selectedView - 1);
            views = next;
        }
        mark("remove");
        ImGui.endDisabled();
        ImGui.spacing();
        if (views.isEmpty()) {
            ImGui.textDisabled("No views · every tile gets its normal image");
            return;
        }
        float height = Math.max(160, ImGui.getContentRegionAvailY() - 2);
        float listWidth = Math.min(330, ImGui.getContentRegionAvailX() * .48f);
        ImGui.beginChild(
                "##view-list",
                listWidth,
                height,
                ImGuiChildFlags.Borders | ImGuiChildFlags.NavFlattened,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##view-filter", "Filter by tile or name", viewFilter);
        ImGui.spacing();
        ImGui.beginChild("##view-rows", 0, 0, ImGuiChildFlags.NavFlattened, 0);
        drawViewList(views);
        ImGui.endChild();
        ImGui.endChild();
        ImGui.sameLine(0, 16);
        ImGui.beginChild("##view-form", 0, height, ImGuiChildFlags.NavFlattened, 0);
        drawViewForm(views);
        ImGui.endChild();
    }

    private void mark(String name) {
        places.put(name, new float[] {
            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
        });
    }

    private void drawViewList(List<CaptureView> views) {
        var filter = viewFilter.get().strip().toLowerCase(Locale.ROOT);
        var order = listOrder(views);
        var missing = clientTiles();
        String tile = null;
        ImGui.pushStyleVar(ImGuiStyleVar.SelectableTextAlign, 0f, .5f);
        for (int index : order) {
            var view = views.get(index);
            if (!filter.isEmpty()
                    && !view.tile().contains(filter)
                    && !view.name().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            if (!view.tile().equals(tile)) {
                if (tile != null) {
                    ImGui.spacing();
                }
                tile = view.tile();
                var group = tile;
                long count = views.stream()
                        .filter(other -> other.tile().equals(group))
                        .count();
                ImGui.textColored(HudWidgets.ACCENT, tile);
                ImGui.sameLine();
                ImGui.textDisabled(count + (count == 1 ? " view" : " views"));
                if (!missing.isEmpty() && !missing.contains(tile)) {
                    ImGui.sameLine();
                    ImGui.textColored(ImGui.getColorU32(1f, .65f, .55f, 1f), "not in this client");
                }
            }
            float rowX = ImGui.getCursorPosX();
            float rowY = ImGui.getCursorPosY();
            float rowWidth = ImGui.getContentRegionAvailX();
            ImGui.setCursorPosX(rowX + 10);
            var label = view.main() ? "Main image" : view.name();
            if (ImGui.selectable(label + "##view-" + index, index == selectedView, 0, rowWidth - 10, 24)) {
                if (selectedView != index) {
                    forgetViews();
                }
                selectedView = index;
            }
            if (index == selectedView && revealView) {
                ImGui.setScrollHereY();
                revealView = false;
            }
            var summary = summary(view);
            float room = rowWidth - 10 - ImGui.calcTextSizeX(label) - 16 - 8;
            var shown = summary.isEmpty() || ImGui.calcTextSizeX("…") > room
                    ? ""
                    : HudWidgets.truncateToWidth(summary, room);
            if (!shown.equals(summary) && ImGui.isItemHovered()) {
                HudWidgets.showTooltip(summary);
            }
            if (!shown.isEmpty()) {
                ImGui.sameLine(rowX + rowWidth - ImGui.calcTextSizeX(shown) - 8);
                ImGui.setCursorPosY(rowY + (24 - ImGui.getFontSize()) * .5f);
                ImGui.textDisabled(shown);
            }
        }
        ImGui.popStyleVar();
    }

    private void drawViewForm(List<CaptureView> views) {
        var view = views.get(selectedView);
        var prefix = PATH + "/" + selectedView;
        var tile = text.computeIfAbsent(prefix + "/tile", ignored -> new ImString(view.tile(), 16));
        var name = text.computeIfAbsent(prefix + "/name", ignored -> new ImString(view.name(), 64));
        var top = text.computeIfAbsent(
                prefix + "/top",
                ignored -> new ImString(view.top() == null ? "" : view.top().toString(), 16));
        var bottom = text.computeIfAbsent(
                prefix + "/bottom",
                ignored ->
                        new ImString(view.bottom() == null ? "" : view.bottom().toString(), 16));
        var circle = view.circle();
        var circleX = text.computeIfAbsent(
                prefix + "/circle-x", ignored -> new ImString(circle == null ? "" : String.valueOf(circle.x()), 16));
        var circleY = text.computeIfAbsent(
                prefix + "/circle-y", ignored -> new ImString(circle == null ? "" : String.valueOf(circle.y()), 16));
        var circleRadius = text.computeIfAbsent(
                prefix + "/circle-radius",
                ignored -> new ImString(circle == null ? "" : String.valueOf(circle.radius()), 16));
        var size = text.computeIfAbsent(
                prefix + "/resolution",
                ignored -> new ImString(
                        view.resolution() == null ? "" : view.resolution().toString(), 16));
        var hidePassable = new ImBoolean(view.hidePassable());
        var keepTerrain = new ImBoolean(view.kept().terrain());
        var keepStaticMeshes = new ImBoolean(view.kept().staticMeshes());
        var keepCsg = new ImBoolean(view.kept().csg());
        var main = new ImBoolean(view.main());
        var content = view.content();
        var effects = new ImBoolean(content.effects());
        var eventsStart = content.mapEvents();
        var castleSides = content.castleSides();
        var zoneState = content.zoneState();
        var eventIds = text.computeIfAbsent(
                prefix + "/events", ignored -> new ImString(MapEventState.formatIds(content.events()), 512));
        float formWidth = ImGui.getContentRegionAvailX();
        float input = Math.min(200, formWidth - 96);
        var rowWidth = 96 + input;
        var beside = formWidth >= 2 * rowWidth + COLUMN_GAP;
        ImGui.beginGroup();
        GeodataWidgets.section("IMAGE AND CUT", 0);
        GeodataWidgets.space(GeodataWidgets.ROW);
        boolean changed = labeledInput(
                "Tile",
                "##view-tile",
                "23_18",
                tile,
                input,
                96,
                "Map tile as X_Y. The coordinates are shown on the start menu's map.");
        var missing = clientTiles();
        if (!missing.isEmpty() && !missing.contains(view.tile())) {
            ImGui.pushStyleColor(ImGuiCol.Text, 1, .65f, .55f, 1);
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + (beside ? rowWidth : formWidth));
            ImGui.textUnformatted("This client has no map " + view.tile() + ". Capture skips this view.");
            ImGui.popTextWrapPos();
            ImGui.popStyleColor();
        }
        ImGui.beginDisabled(main.get());
        if (focusViewName) {
            ImGui.setKeyboardFocusHere();
            focusViewName = false;
        }
        changed |= labeledInput(
                "Name",
                "##view-name",
                main.get() ? "not used" : "floor-01",
                name,
                input,
                96,
                "File name suffix: the view of tile 23_18 named floor-01 is saved as 23_18_floor-01. A main view keeps"
                        + " the tile's own name.");
        ImGui.endDisabled();
        changed |= labeledSwitchRow(
                "main",
                "Main image",
                main,
                rowWidth,
                true,
                "Replace the tile's normal image, which also goes into the radar map. Each tile can have one main"
                        + " view.");
        ImGui.spacing();
        changed |= labeledInput(
                "Top Z",
                "##view-top",
                "none",
                top,
                input,
                96,
                "Nothing above this world height is drawn. Place it in the air of a room, below its ceiling, to show"
                        + " that floor. Leave empty to keep the normal camera height.");
        changed |= labeledInput(
                "Bottom Z",
                "##view-bottom",
                "none",
                bottom,
                input,
                96,
                "Optional. Nothing below this world height is drawn. Use it when holes, stairwells or open courtyards"
                        + " show the floor below. Leave empty to keep everything below.");
        changed |= labeledInput(
                "Center X",
                "##view-circle-x",
                "none",
                circleX,
                input,
                96,
                "Optional. Cut only inside a circle around this world X and Center Y, for example one tower, so the"
                        + " land, trees, rocks and water around it stay. Find it with Cut Inside Circle in the Viewer's"
                        + " Height cut.");
        changed |= labeledInput(
                "Center Y", "##view-circle-y", "none", circleY, input, 96, "Optional. World Y of the circle's centre.");
        changed |= labeledInput(
                "Radius",
                "##view-circle-radius",
                "none",
                circleRadius,
                input,
                96,
                "Optional. Radius of the circle in world units, like the heights. A map tile is 32768 units wide."
                        + " Enter Center X, Center Y and Radius together.");
        changed |= labeledInput(
                "Size",
                "##view-resolution",
                String.valueOf(model.getValue("capture.output.tile_resolution").unwrapped()),
                size,
                input,
                96,
                "Optional. Image width and height in pixels, for example 8192 for a detailed floor plan. Leave empty"
                        + " to use the tile resolution of the export.");
        changed |= labeledSwitchRow(
                "passable",
                "Hide passable surfaces",
                hidePassable,
                rowWidth,
                true,
                "Hide surfaces you can walk through: bushes, small plants and effect meshes without collision. It"
                        + " is the same as turning off PASSABLE surfaces in the Viewer's Scene panel.");
        var cut = !top.get().isBlank() || !bottom.get().isBlank();
        changed |= labeledSwitchRow(
                "keep-terrain",
                "Keep terrain",
                keepTerrain,
                rowWidth,
                cut,
                "Top Z and Bottom Z leave the terrain whole and cut only the other layers, so the ground stays"
                        + " around a cut building. It is Keep Terrain in the Viewer's Height cut.");
        changed |= labeledSwitchRow(
                "keep-meshes",
                "Keep static meshes",
                keepStaticMeshes,
                rowWidth,
                cut,
                "Top Z and Bottom Z leave static meshes whole, with grass and particle effects. It is Keep Static"
                        + " Meshes in the Viewer's Height cut.");
        changed |= labeledSwitchRow(
                "keep-csg",
                "Keep CSG",
                keepCsg,
                rowWidth,
                cut,
                "Top Z and Bottom Z leave CSG/BSP building geometry whole. With Keep terrain too, only static"
                        + " meshes are cut. It is Keep CSG Geometry in the Viewer's Height cut.");
        ImGui.endGroup();
        if (beside) {
            ImGui.sameLine(0, COLUMN_GAP);
        }
        ImGui.beginGroup();
        GeodataWidgets.section("EFFECTS AND EVENTS", beside ? 0 : GeodataWidgets.SECTION);
        GeodataWidgets.space(GeodataWidgets.ROW);
        changed |= labeledSwitchRow(
                "effects",
                "Effects",
                effects,
                rowWidth,
                true,
                "Draw the particle effects of the tile, such as fires, smoke and waterfalls. Off leaves them all out"
                        + " of this image.");
        ImGui.beginDisabled(!effects.get());
        var startChoice = labeledChoice(
                "map-events",
                "Map events",
                new String[] {"As the client", "All shown", "Game start"},
                eventsStart == null ? 0 : eventsStart == Start.ALL_SHOWN ? 1 : 2,
                input,
                clientEvents.summary(),
                "Which effects of map events this image shows. As the client follows the choice of the Viewer's Map"
                        + " events tool. All shown shows every one. Game start hides them, as the game does before"
                        + " the server sends an event. Hide or Show under it takes single events.");
        var nextStart = startChoice == 0 ? null : startChoice == 1 ? Start.ALL_SHOWN : Start.GAME_START;
        if (nextStart != eventsStart) {
            changed = true;
            eventIds.set("");
        }
        changed |= labeledInput(
                nextStart == Start.GAME_START ? "Show" : "Hide",
                "##view-events",
                "Event ids, such as 22226292",
                eventIds,
                input,
                96,
                nextStart == Start.GAME_START
                        ? "Optional. The events whose effects come into this image. The Viewer's Map events panel"
                                + " shows the id of each event."
                        : nextStart == Start.ALL_SHOWN
                                ? "Optional. The events whose effects stay out of this image. The Viewer's Map"
                                        + " events panel shows the id of each event."
                                : "Optional. The events whose effects stay out of this image, also when the client"
                                        + " shows them. An event that the client hides stays hidden. The Viewer's"
                                        + " Map events panel shows the id of each event.");
        mark("field/events");
        var startSide = nextStart == null ? null : Integer.valueOf(ViewContent.sideOf(nextStart));
        var sides = new ArrayList<Integer>();
        var sideNames = new ArrayList<String>();
        if (nextStart == null) {
            sides.add(null);
            sideNames.add("As the client");
        }
        for (var index = 0; index < SIDES.length; index++) {
            sides.add(SIDES[index]);
            sideNames.add(SIDE_NAMES[index]);
        }
        var clientSide = clientEvents.castleSideForAll();
        var sideChoice = labeledChoice(
                "castle-sides",
                "Castle sides",
                sideNames.toArray(String[]::new),
                Math.max(0, sides.indexOf(castleSides != null ? castleSides : startSide)),
                input,
                nextStart != null
                        ? null
                        : clientSide == null ? "a side for each castle" : MapEventState.sideName(clientSide),
                "The side of every castle in this image: the light and dark effects of the castle towns, in P152 and"
                        + " later clients. Older clients have none. As the client follows the choice of the Viewer's"
                        + " Map events tool.");
        var nextSides = sides.get(sideChoice);
        if (Objects.equals(nextSides, startSide)) {
            nextSides = null;
        }
        changed |= !Objects.equals(nextSides, castleSides);
        ImGui.endDisabled();
        var clientState = clientZoneState(clientEvents, view.tile());
        var states = new String[MapEventState.MAX_ZONE_STATE + 2];
        states[0] = "As the client";
        states[1] = "As the map";
        for (var state = 1; state <= MapEventState.MAX_ZONE_STATE; state++) {
            states[state + 1] = "State " + state;
        }
        var stateChoice = labeledChoice(
                "zone-state",
                "Zone state",
                states,
                zoneState == null ? 0 : zoneState + 1,
                input,
                clientState == NO_CHOICE
                        ? "the map's default"
                        : clientState == MapEventState.AS_THE_MAP ? "as the map" : "state " + clientState,
                "The state of the zones of this tile's map, which decides which of its meshes, effects, fog and water"
                        + " show. As the client follows the choice of the Viewer's Map events tool: without a choice"
                        + " the map takes its default, which is as the map, or State 1 for a map of Essence whose"
                        + " zones start in state 0. As the map keeps the state the map starts each zone in. The"
                        + " Viewer's Map events panel lists the states of a map under ZONE STATES.");
        var nextState = stateChoice == 0 ? null : Integer.valueOf(stateChoice - 1);
        changed |= !Objects.equals(nextState, zoneState);
        if (!main.get()
                && (!effects.get()
                        || nextStart != null
                        || nextState != null
                        || nextSides != null
                        || !eventIds.get().isBlank())) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + (beside ? rowWidth : formWidth));
            ImGui.textDisabled("This view is an extra image. With Main image on it is the tile's own image, also in"
                    + " the radar map.");
            ImGui.popTextWrapPos();
        }
        GeodataWidgets.space(GeodataWidgets.SECTION);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + (beside ? rowWidth : formWidth));
        ImGui.textDisabled(
                "Find the numbers in the Viewer. For heights press O for the top-down camera, then use Scene → World →"
                        + " Height cut: its Top Z and Bottom Z are the same numbers. The Map events panel shows the"
                        + " ids of the events and the zone states of a map.");
        ImGui.popTextWrapPos();
        ImGui.endGroup();
        if (changed) {
            try {
                var updated = new CaptureView(
                        tile.get().strip(),
                        name.get(),
                        parseHeight(top.get()),
                        parseHeight(bottom.get()),
                        hidePassable.get(),
                        main.get(),
                        parseHeight(size.get()),
                        KeptLayers.of(keepTerrain.get(), keepStaticMeshes.get(), keepCsg.get()),
                        parseCircle(circleX.get(), circleY.get(), circleRadius.get()),
                        new ViewContent(
                                effects.get(),
                                nextStart,
                                MapEventState.parseIds(eventIds.get()),
                                nextSides,
                                nextState));
                var next = new ArrayList<>(views);
                next.set(selectedView, updated);
                CaptureView.requireDistinct(next);
                storeViews(next);
                invalid.remove(prefix);
            } catch (NumberFormatException failure) {
                invalid.put(
                        prefix, "Enter Top, Bottom, Center, Radius and Size as whole numbers, or leave them empty.");
            } catch (IllegalArgumentException failure) {
                invalid.put(prefix, failure.getMessage());
            }
        }
        if (invalid.containsKey(prefix)) {
            ImGui.spacing();
            ImGui.pushStyleColor(ImGuiCol.Text, 1, .65f, .55f, 1);
            ImGui.textWrapped(invalid.get(prefix));
            ImGui.popStyleColor();
        }
    }

    private List<CaptureView> insertView(List<CaptureView> views, CaptureView view) {
        var next = new ArrayList<>(views);
        int at = next.size();
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).tile().equals(view.tile())) {
                at = i + 1;
            }
        }
        next.add(at, view);
        storeViews(next);
        forgetViews();
        selectedView = at;
        focusViewName = true;
        revealView = true;
        return next;
    }

    private void forgetViews() {
        text.keySet().removeIf(key -> key.startsWith(PATH + "/"));
        invalid.keySet().removeIf(key -> key.startsWith(PATH + "/"));
    }

    private Set<String> clientTiles() {
        if (givenTiles != null) {
            return givenTiles;
        }
        var folder = String.valueOf(model.getValue("capture.world.client_path").unwrapped());
        var variant = String.valueOf(model.getValue(ClientProfiles.VARIANT).unwrapped());
        if (!(folder + "|" + variant).equals(clientTilesFolder)) {
            clientTilesFolder = folder + "|" + variant;
            try {
                clientTiles = Set.copyOf(ClientInstallation.inspect(Path.of(folder), WorldVariant.parse(variant))
                        .tiles());
            } catch (RuntimeException unreadable) {
                clientTiles = Set.of();
            }
        }
        return clientTiles;
    }

    private List<CaptureView> parseViews() {
        var result = new ArrayList<CaptureView>();
        for (var item : (ConfigList) model.getValue(PATH)) {
            result.add(CaptureView.parse(((ConfigObject) item).toConfig()));
        }
        return result;
    }

    private void storeViews(List<CaptureView> views) {
        model.setValue(
                PATH,
                ConfigValueFactory.fromIterable(
                        views.stream().map(CaptureView::toConfigValue).toList()));
        changed.run();
    }

    private boolean labeledSwitchRow(
            String name, String label, ImBoolean value, float width, boolean enabled, String help) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var next = GeodataWidgets.settingSwitchRow("view-" + name, label, "", value.get(), enabled, help, width);
        places.put("switch/" + name, new float[] {x, y, x + width, y + HudWidgets.CONTROL_HEIGHT});
        var changed = next != value.get();
        value.set(next);
        return changed;
    }

    private int labeledChoice(
            String name, String label, String[] choices, int selected, float width, String now, String help) {
        float x = ImGui.getCursorPosX();
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(label);
        ImGui.sameLine();
        ImGui.setCursorPosX(x + 96);
        ImGui.setNextItemWidth(width);
        var chosen = selected;
        if (HudWidgets.beginCombo("##view-" + name, choices[selected], ImGuiComboFlags.HeightRegular)) {
            for (var index = 0; index < choices.length; index++) {
                if (ImGui.selectable(choices[index], index == selected)) {
                    chosen = index;
                }
                mark("choice/" + name + "/" + index);
            }
            ImGui.endCombo();
        }
        mark("choice/" + name);
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(help);
        }
        if (selected == 0 && now != null) {
            ImGui.setCursorPosX(x + 96);
            ImGui.textDisabled(HudWidgets.truncateToWidth("now: " + now, width));
            if (ImGui.isItemHovered()) {
                HudWidgets.showTooltip("The client has " + now + ". The Viewer's Map events tool changes it.");
            }
        }
        return chosen;
    }

    private static boolean labeledInput(
            String label, String id, String hint, ImString value, float width, float labelWidth, String help) {
        float x = ImGui.getCursorPosX();
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(label);
        ImGui.sameLine();
        if (labelWidth > 0) {
            ImGui.setCursorPosX(x + labelWidth);
        }
        ImGui.setNextItemWidth(width);
        boolean changed = ImGui.inputTextWithHint(id, hint, value);
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(help);
        }
        return changed;
    }

    private static boolean isNameTaken(List<CaptureView> views, String tile, String name) {
        return views.stream()
                .anyMatch(view ->
                        !view.main() && view.tile().equals(tile) && view.name().equalsIgnoreCase(name));
    }

    private static Integer parseHeight(String text) {
        return text.isBlank() ? null : Integer.valueOf(text.strip());
    }

    private static @Nullable CutCircle parseCircle(String x, String y, String radius) {
        if (x.isBlank() && y.isBlank() && radius.isBlank()) {
            return null;
        }
        if (x.isBlank() || y.isBlank() || radius.isBlank()) {
            throw new IllegalArgumentException(
                    "Enter Center X, Center Y and Radius together, or leave all three empty.");
        }
        return new CutCircle(
                Integer.parseInt(x.strip()), Integer.parseInt(y.strip()), Integer.parseInt(radius.strip()));
    }
}

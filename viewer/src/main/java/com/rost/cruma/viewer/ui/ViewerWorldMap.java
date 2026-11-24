package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigLoader.Options;
import com.rost.cruma.viewer.config.OptionsModel;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.MapLayer.Click;
import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class ViewerWorldMap {
    private static final int SHOWN = "worldMap/shown".hashCode();

    private static final float DRAG_PIXELS = 5;

    private final World world;
    private final List<Location> locations;
    private final List<String> selectedTiles;
    private final TileMapPreview preview;
    private final FontManager fonts;
    private final ConfigLoader locationLoader;
    private final ImString query = new ImString(128);
    private final ImString destinationInput = new ImString(128);
    private final ImString locationName = new ImString(512);
    private ClientInstallation installation;
    private WorldMapCoordinates coordinates;
    private Set<String> loaded = Set.of();
    private boolean inspected;
    private String problem = "";
    private float zoom = 1;
    private float panX;
    private float panY;
    private List<Location> cluster = List.of();
    private boolean addingLocation;
    private Location pendingLocation;
    private boolean focusName;
    private String saveError = "";
    private String savedName = "";
    private String navigationError = "";

    private List<MapLayer> layers = List.of();

    private float pressX = Float.NaN;

    private float pressY = Float.NaN;

    private float rightX = Float.NaN;

    private float rightY = Float.NaN;

    private @Nullable MapLayer menuLayer;

    private @Nullable Location menuPoint;

    public ViewerWorldMap(
            World world,
            List<Location> locations,
            List<String> selectedTiles,
            Path configDirectory,
            ViewerUiRenderer renderer,
            FontManager fonts) {
        this.world = world;
        this.locations = locations;
        this.selectedTiles = selectedTiles;
        this.preview = new TileMapPreview(configDirectory, renderer);
        this.fonts = fonts;
        this.locationLoader = new ConfigLoader(new Options(configDirectory, false, false));
    }

    public enum Command {
        CLOSE,
        MENU,
        MOVE,
        EXPORT_AREA,
        EXPORT_TILE
    }

    public record Action(Command command, Location destination) {}

    private record Rect(float x, float y, float width, float height) {
        boolean contains(float px, float py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }

        boolean overlaps(Rect other) {
            return x < other.x + other.width + 3
                    && x + width + 3 > other.x
                    && y < other.y + other.height + 3
                    && y + height + 3 > other.y;
        }
    }

    static Location parseDestination(String text, float currentHeight) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var parts = text.trim().split("[\\s,_]+");
        try {
            if (parts.length == 3) {
                var x = Float.parseFloat(parts[0]);
                var y = Float.parseFloat(parts[1]);
                var z = Float.parseFloat(parts[2]);
                return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                        ? new Location("Coordinates", x, y, z)
                        : null;
            }
            if (parts.length == 2 && Float.isFinite(currentHeight)) {
                var tileX = Integer.parseInt(parts[0]);
                var tileY = Integer.parseInt(parts[1]);
                var x = (tileX - (float) WorldDimensions.TILE_ZERO_COORD_X) * WorldDimensions.TILE_SIZE
                        + WorldDimensions.TILE_SIZE / 2f;
                var y = (tileY - (float) WorldDimensions.TILE_ZERO_COORD_Y) * WorldDimensions.TILE_SIZE
                        + WorldDimensions.TILE_SIZE / 2f;
                return new Location(tileX + "_" + tileY, x, y, currentHeight);
            }
        } catch (NumberFormatException invalid) {
            return null;
        }
        return null;
    }

    public void setLayers(List<MapLayer> mapLayers) {
        layers = List.copyOf(mapLayers);
    }

    public List<MapLayer> getLayers() {
        return layers;
    }

    public boolean isEditingLocation() {
        return pendingLocation != null;
    }

    public boolean dismiss() {
        if (addingLocation || isEditingLocation()) {
            cancelCreation();
            return false;
        }
        return true;
    }

    public void cancelCreation() {
        addingLocation = false;
        pendingLocation = null;
        locationName.clear();
        saveError = "";
        focusName = false;
        savedName = "";
    }

    public Action draw(Camera camera, boolean locked) {
        var io = ImGui.getIO();
        var width = Math.min(1120f, io.getDisplaySizeX() - 32);
        var height = Math.min(860f, io.getDisplaySizeY() - 32);
        var shown = Motion.appear(SHOWN, .28f);
        ImGui.getBackgroundDrawList()
                .addRectFilled(
                        0,
                        0,
                        io.getDisplaySizeX(),
                        io.getDisplaySizeY(),
                        ImColor.rgba(0, 0, 0, Math.round(125 * Math.min(1, shown * 2))));
        ImGui.setNextWindowPos((io.getDisplaySizeX() - width) / 2, (io.getDisplaySizeY() - height) / 2);
        ImGui.setNextWindowSize(width, height);
        ImGui.setNextWindowBgAlpha(1f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 18, 16);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, HudWidgets.PANEL_RADIUS);
        ImGui.begin(
                "##viewerWorldMap",
                ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoSavedSettings);
        Motion.open(shown, 0, 0, 1.04f);
        Action action = null;
        ImGui.pushFont(fonts.getHeadingFont(20), 20);
        ImGui.text("World map");
        ImGui.popFont();
        ImGui.setCursorPos(width - 48, 12);
        if (HudWidgets.button("closeMap", Glyph.CLOSE, "", 28, 28, false, false)) {
            action = new Action(Command.CLOSE, null);
        }
        tooltip("Close map · M / Escape");
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8, (28f - ImGui.getFontSize()) / 2);
        var texture = texture();
        ImGui.setCursorPos(18, 50);
        ImGui.setNextItemWidth(width - 194 - (layers.isEmpty() ? 0 : 36));
        ImGui.inputTextWithHint("##mapPlacesFilter", "Find a place on the map…", query);
        if (!layers.isEmpty()) {
            ImGui.sameLine(0, 8);
            drawLayerMenu();
        }
        ImGui.sameLine(0, 8);
        ImGui.beginDisabled(locked || texture == 0);
        if (HudWidgets.button(
                "addMapLocation",
                addingLocation ? Glyph.CLOSE : Glyph.PLUS,
                addingLocation ? "Cancel" : "Add location",
                150,
                28,
                addingLocation,
                true)) {
            if (addingLocation) {
                cancelCreation();
            } else {
                addingLocation = true;
                savedName = "";
            }
        }
        tooltip("Choose a point on the map and save it as a named location.");
        ImGui.endDisabled();
        ImGui.separator();
        var navigation = drawNavigation(camera, locked);
        if (navigation != null) {
            action = navigation;
        }
        ImGui.separator();
        if (texture == 0) {
            ImGui.spacing();
            ImGui.textWrapped(
                    "No map image is available for this client. Create it in the start menu with Create map image,"
                            + " over the map.");
            if (!problem.isEmpty()) {
                ImGui.textWrapped(problem);
            } else if (!preview.getProblem().isEmpty()) {
                ImGui.textWrapped(preview.getProblem());
            }
            ImGui.spacing();
            if (HudWidgets.button("mapReturnToMenu", null, "Back to main menu", 180, 32, false, true)) {
                action = new Action(Command.MENU, null);
            }
        } else {
            ImGui.beginDisabled(locked);
            var mapAction = drawCanvas(
                    texture,
                    camera,
                    width - 36,
                    Math.max(40, height - ImGui.getCursorPosY() - (isEditingLocation() ? 132 : 88)),
                    locked);
            ImGui.endDisabled();
            if (mapAction != null) {
                action = mapAction;
            }
            ImGui.separator();
            if (isEditingLocation()) {
                drawLocationEditor(locked);
            } else {
                var p = camera.getWorldFocusPosition();
                ImGui.textColored(
                        HudWidgets.ACCENT,
                        String.format(Locale.ROOT, "Camera  X %.0f  Y %.0f  Z %.0f", p.getX(), p.getY(), p.getZ()));
                var hint = locked
                        ? "Exit A/B comparison to navigate."
                        : addingLocation
                                ? "Click a point to name it · Uses current camera height · Escape cancels."
                                : selectsArea()
                                        ? "Click to teleport · Drag to select · Right-click for actions · Wheel to"
                                                + " zoom · Right-drag to pan. Map clicks keep camera height."
                                        : "Click to teleport · Wheel to zoom · Right-drag to pan. Map clicks keep"
                                                + " camera height; places use their saved height.";
                if (!locked && !savedName.isEmpty()) {
                    hint = "Saved “" + savedName + "”. Find it using the map search.";
                }
                ImGui.textWrapped(hint);
                ImGui.textDisabled("Shaded tiles are not loaded in this session. Load them from the main menu.");
            }
        }
        ImGui.popStyleVar();
        ImGui.end();
        ImGui.popStyleVar(2);
        return action;
    }

    public @Nullable ClientInstallation getInstallation() {
        texture();
        return installation;
    }

    public long getPreviewTexture() {
        return texture();
    }

    private Action drawNavigation(Camera camera, boolean locked) {
        ImGui.beginDisabled(locked);
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 80);
        ImGui.inputTextWithHint("##mapDestination", "Go to X Y Z or tile X_Y", destinationInput);
        var entered = ImGui.isItemDeactivated()
                && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter));
        if (ImGui.isItemEdited()) {
            navigationError = "";
        }
        tooltip("Enter three world coordinates or a tile such as 22_22. Tile jumps keep the current camera height.");
        ImGui.sameLine(0, 8);
        var go = HudWidgets.button("goMapDestination", Glyph.ARROW, "Go", 72, 28, false, true);
        ImGui.endDisabled();
        if (!locked && (entered || go)) {
            var destination = parseDestination(
                    destinationInput.get(), camera.getPosition().getZ());
            if (destination != null) {
                navigationError = "";
                return new Action(Command.MOVE, destination);
            }
            navigationError = "Use X Y Z (for example 83336 147972 -3204), or a tile such as 22_22.";
        }
        if (!navigationError.isEmpty()) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(ImColor.rgb(255, 143, 137), navigationError);
            ImGui.popTextWrapPos();
        }
        return null;
    }

    private void drawLocationEditor(boolean locked) {
        var point = pendingLocation;
        ImGui.textColored(
                HudWidgets.ACCENT,
                String.format(Locale.ROOT, "New location  X %.0f  Y %.0f  Z %.0f", point.x(), point.y(), point.z()));
        ImGui.beginDisabled(locked);
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 176);
        if (focusName && !ImGui.isMouseDown(0)) {
            ImGui.setKeyboardFocusHere();
            focusName = false;
        }
        ImGui.inputTextWithHint("##newMapLocationName", "Location name…", locationName);
        var enter = ImGui.isItemFocused() && ImGui.isKeyPressed(ImGuiKey.Enter);
        if (ImGui.isItemEdited()) {
            saveError = "";
        }
        ImGui.sameLine(0, 8);
        ImGui.beginDisabled(locationName.get().isBlank());
        var save = HudWidgets.button("saveMapLocation", null, "Save", 72, 28, true, true);
        ImGui.endDisabled();
        ImGui.sameLine(0, 8);
        var cancel = HudWidgets.button("cancelMapLocation", null, "Cancel", 88, 28, false, true);
        ImGui.endDisabled();
        if (!saveError.isEmpty()) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(ImColor.rgb(255, 143, 137), saveError);
            ImGui.popTextWrapPos();
        } else {
            ImGui.textDisabled("Enter to save · Escape to cancel · Click the map to adjust the point.");
        }
        if (!locked && (save || enter)) {
            saveLocation(point);
        } else if (cancel) {
            cancelCreation();
        }
    }

    private void saveLocation(Location point) {
        try {
            locationLoader.reload();
            var model = new OptionsModel(locationLoader);
            var name = locationName.get().strip();
            model.addLocation(name, List.of((double) point.x(), (double) point.y(), (double) point.z()));
            model.save();
            var saved = locationLoader.load().viewer().camera().locations();
            locations.clear();
            locations.addAll(saved);
            query.clear();
            cancelCreation();
            savedName = name;
        } catch (IOException | RuntimeException failure) {
            saveError =
                    failure.getMessage() == null ? "Could not save this location. Try again." : failure.getMessage();
        }
    }

    private long texture() {
        if (!inspected) {
            inspected = true;
            try {
                installation = ClientInstallation.inspect(world.clientPath(), world.variant());
                coordinates = new WorldMapCoordinates(installation.tiles());
                loaded = Set.copyOf(
                        selectedTiles == null
                                ? installation.tilesIn(
                                        world.minXTile(), world.maxXTile(), world.minYTile(), world.maxYTile())
                                : selectedTiles);
            } catch (RuntimeException failure) {
                problem = failure.getMessage();
            }
        }
        return installation == null ? 0 : preview.getTextureFor(installation);
    }

    private Action drawCanvas(long texture, Camera camera, float width, float height, boolean locked) {
        var left = ImGui.getCursorScreenPosX();
        var top = ImGui.getCursorScreenPosY();
        var area = new Rect(left, top, width, height);
        ImGui.invisibleButton("##worldMapCanvas", width, height);
        var hovered = ImGui.isItemHovered();
        var io = ImGui.getIO();
        var mouseX = ImGui.getMousePosX();
        var mouseY = ImGui.getMousePosY();
        var fitWidth = Math.min(width, height * coordinates.columns / coordinates.rows);
        var fitHeight = fitWidth * coordinates.rows / coordinates.columns;
        var image = imageRect(area, fitWidth, fitHeight);
        if (hovered && io.getMouseWheel() != 0) {
            var u = (mouseX - image.x) / image.width;
            var v = (mouseY - image.y) / image.height;
            zoom = Math.clamp(zoom * (float) Math.pow(1.2, io.getMouseWheel()), 1f, 8f);
            panX = mouseX - left - u * fitWidth * zoom - (width - fitWidth * zoom) / 2;
            panY = mouseY - top - v * fitHeight * zoom - (height - fitHeight * zoom) / 2;
        }
        if (hovered && ImGui.isMouseDragging(1)) {
            panX += io.getMouseDeltaX();
            panY += io.getMouseDeltaY();
        }
        panX = Math.clamp(panX, -(width + fitWidth * zoom) / 2 + 40, (width + fitWidth * zoom) / 2 - 40);
        panY = Math.clamp(panY, -(height + fitHeight * zoom) / 2 + 40, (height + fitHeight * zoom) / 2 - 40);
        image = imageRect(area, fitWidth, fitHeight);
        var draw = ImGui.getWindowDrawList();
        ImGui.pushClipRect(left, top, left + width, top + height, true);
        draw.addRectFilled(left, top, left + width, top + height, ImColor.rgb(11, 17, 22), 6f);
        draw.addImage(texture, image.x, image.y, image.x + image.width, image.y + image.height);
        var cellWidth = image.width / coordinates.columns;
        var cellHeight = image.height / coordinates.rows;
        for (int y = 0; y < coordinates.rows; y++) {
            for (int x = 0; x < coordinates.columns; x++) {
                var tile = (coordinates.minX + x) + "_" + (coordinates.minY + y);
                var cellX = image.x + x * cellWidth;
                var cellY = image.y + y * cellHeight;
                if (!loaded.contains(tile)) {
                    draw.addRectFilled(
                            cellX,
                            cellY,
                            cellX + cellWidth,
                            cellY + cellHeight,
                            ImColor.rgba(8, 13, 18, coordinates.isInstalled(tile) ? 95 : 225));
                }
                draw.addRect(cellX, cellY, cellX + cellWidth, cellY + cellHeight, ImColor.rgba(180, 211, 220, 38));
                if (cellWidth >= 70 && cellHeight >= 36) {
                    draw.addText(cellX + 4, cellY + 3, ImColor.rgba(226, 237, 240, 150), tile);
                }
            }
        }
        var transform = new MapTransform(
                coordinates, image.x, image.y, image.width, image.height, left, top, left + width, top + height, zoom);
        for (var layer : layers) {
            if (layer.isShown()) {
                layer.draw(draw, transform);
            }
        }
        var cameraPosition = camera.getWorldFocusPosition();
        var cameraX = image.x + coordinates.toU(cameraPosition.getX()) * image.width;
        var cameraY = image.y + coordinates.toV(cameraPosition.getY()) * image.height;
        var occupied = new ArrayList<Rect>();
        occupied.add(new Rect(cameraX - 20, cameraY - 20, 40, 40));
        if (pendingLocation != null) {
            var x = image.x + coordinates.toU(pendingLocation.x()) * image.width;
            var y = image.y + coordinates.toV(pendingLocation.y()) * image.height;
            draw.addCircleFilled(x, y, 10, ImColor.rgb(12, 19, 24));
            draw.addCircle(x, y, 8, HudWidgets.ACCENT, 24, 2);
            draw.addLine(x - 5, y, x + 5, y, HudWidgets.ACCENT, 2);
            draw.addLine(x, y - 5, x, y + 5, HudWidgets.ACCENT, 2);
            occupied.add(new Rect(x - 12, y - 12, 24, 24));
        }
        var nearby = new ArrayList<Location>();
        Location labelHover = null;
        var filter = query.get().trim().toLowerCase(Locale.ROOT);
        for (var location : locations) {
            var u = coordinates.toU(location.x());
            var v = coordinates.toV(location.y());
            var x = image.x + u * image.width;
            var y = image.y + v * image.height;
            if (location.name().toLowerCase(Locale.ROOT).contains(filter)
                    && u >= 0
                    && u < 1
                    && v >= 0
                    && v < 1
                    && area.contains(x, y)) {
                occupied.add(new Rect(x - 4, y - 4, 8, 8));
            }
        }
        for (var location : locations) {
            if (!location.name().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            var u = coordinates.toU(location.x());
            var v = coordinates.toV(location.y());
            if (u < 0 || u >= 1 || v < 0 || v >= 1) {
                continue;
            }
            var x = image.x + u * image.width;
            var y = image.y + v * image.height;
            if (!area.contains(x, y)) {
                continue;
            }
            var color = loaded.contains(coordinates.getTileAt(u, v))
                    ? ImColor.rgb(255, 213, 136)
                    : ImColor.rgb(189, 184, 169);
            draw.addCircleFilled(x, y, 4, ImColor.rgb(12, 19, 24));
            draw.addCircleFilled(x, y, 2.5f, color);
            if (hovered && Math.hypot(mouseX - x, mouseY - y) <= 10) {
                nearby.add(location);
            }
            var textWidth = ImGui.calcTextSizeX(location.name());
            var labelHeight = ImGui.getFontSize() + 6;
            for (var candidate : new Rect[] {
                new Rect(x + 7, y - labelHeight / 2, textWidth + 10, labelHeight),
                new Rect(x - textWidth - 17, y - labelHeight / 2, textWidth + 10, labelHeight),
                new Rect(x + 7, y + 7, textWidth + 10, labelHeight),
                new Rect(x + 7, y - labelHeight - 7, textWidth + 10, labelHeight)
            }) {
                if (!area.contains(candidate.x, candidate.y)
                        || !area.contains(candidate.x + candidate.width, candidate.y + candidate.height)
                        || occupied.stream().anyMatch(candidate::overlaps)) {
                    continue;
                }
                var highlighted = hovered && candidate.contains(mouseX, mouseY);
                draw.addRectFilled(
                        candidate.x,
                        candidate.y,
                        candidate.x + candidate.width,
                        candidate.y + candidate.height,
                        highlighted ? ImColor.rgba(36, 66, 72, 245) : ImColor.rgba(11, 18, 23, 205),
                        3f);
                draw.addText(candidate.x + 5, candidate.y + 3, color, location.name());
                occupied.add(candidate);
                if (highlighted) {
                    labelHover = location;
                }
                break;
            }
        }
        if (area.contains(cameraX, cameraY)
                && coordinates.getTileAt(coordinates.toU(cameraPosition.getX()), coordinates.toV(cameraPosition.getY()))
                        != null) {
            draw.addCircleFilled(cameraX, cameraY, 8, ImColor.rgb(12, 19, 24));
            draw.addCircle(cameraX, cameraY, 7, HudWidgets.ACCENT, 24, 2);
            draw.addCircleFilled(cameraX, cameraY, 3.5f, ImColor.rgb(240, 255, 255));
            var facing = camera.getForwardDir().negate();
            var length = (float) Math.hypot(facing.getX(), facing.getY());
            if (length > .01f) {
                draw.addLine(
                        cameraX,
                        cameraY,
                        cameraX + facing.getX() / length * 16,
                        cameraY + facing.getY() / length * 16,
                        HudWidgets.ACCENT,
                        2);
            }
        }
        var legendBottom = top + height - 10;
        for (var layer : layers) {
            if (layer.isShown()) {
                var used = layer.drawLegend(draw, left + 10, legendBottom);
                legendBottom -= used > 0 ? used + 6 : 0;
            }
        }
        var banding = !Float.isNaN(pressX)
                && ImGui.isMouseDown(0)
                && !addingLocation
                && selectsArea()
                && Math.hypot(mouseX - pressX, mouseY - pressY) >= DRAG_PIXELS;
        if (banding) {
            var bandLeft = Math.min(pressX, mouseX);
            var bandTop = Math.min(pressY, mouseY);
            var bandRight = Math.max(pressX, mouseX);
            var bandBottom = Math.max(pressY, mouseY);
            draw.addRectFilled(bandLeft, bandTop, bandRight, bandBottom, ImColor.rgba(92, 219, 241, 28));
            draw.addRect(bandLeft, bandTop, bandRight, bandBottom, HudWidgets.ACCENT, 0, 0, 1.5f);
        }
        ImGui.popClipRect();
        var u = (mouseX - image.x) / image.width;
        var v = (mouseY - image.y) / image.height;
        var tile = coordinates.getTileAt(u, v);
        if (hovered && banding) {
            HudWidgets.showTooltip("Release to select what the box covers.");
        } else if (hovered) {
            var place = labelHover != null ? labelHover : nearby.isEmpty() ? null : nearby.getFirst();
            var layerTip = addingLocation || place != null ? null : layerHover(transform, mouseX, mouseY);
            if (layerTip != null) {
                HudWidgets.showTooltip(layerTip);
            } else if (addingLocation && coordinates.isInstalled(tile)) {
                HudWidgets.showTooltip(String.format(
                        Locale.ROOT,
                        "Save a location here\nX %.0f · Y %.0f · Z %.0f",
                        coordinates.toWorldX(u),
                        coordinates.toWorldY(v),
                        camera.getPosition().getZ()));
            } else if (place != null) {
                HudWidgets.showTooltip(String.format(
                        Locale.ROOT,
                        "%s\nX %.0f · Y %.0f · Z %.0f%s",
                        place.name(),
                        place.x(),
                        place.y(),
                        place.z(),
                        nearby.size() > 1 && labelHover == null
                                ? "\nClick to choose a location here."
                                : "\nClick to travel to this place."));
            } else if (coordinates.isInstalled(tile)) {
                HudWidgets.showTooltip(String.format(
                                Locale.ROOT,
                                "%s · X %.0f · Y %.0f%s",
                                tile,
                                coordinates.toWorldX(u),
                                coordinates.toWorldY(v),
                                loaded.contains(tile)
                                        ? "\nClick to travel, keeping camera height."
                                        : "\nNot loaded in this session. Moving here may show an empty scene.")
                        + "\nRight-click to export.");
            }
        }
        Action action = null;
        if (hovered && !locked && ImGui.isMouseClicked(0)) {
            pressX = mouseX;
            pressY = mouseY;
        }
        var released = !Float.isNaN(pressX) && ImGui.isMouseReleased(0);
        var moved = released && Math.hypot(mouseX - pressX, mouseY - pressY) >= DRAG_PIXELS;
        if (released && moved && !addingLocation && !locked && selectsArea()) {
            var bandLeft = Math.min(pressX, mouseX);
            var bandTop = Math.min(pressY, mouseY);
            var bandRight = Math.max(pressX, mouseX);
            var bandBottom = Math.max(pressY, mouseY);
            for (var layer : layers) {
                if (layer.isShown() && layer.selectsArea()) {
                    layer.selectArea(transform, bandLeft, bandTop, bandRight, bandBottom);
                }
            }
        }
        if (released) {
            pressX = Float.NaN;
            pressY = Float.NaN;
        }
        if (released && !moved && hovered && !locked) {
            Click layerClick;
            if (addingLocation) {
                if (coordinates.isInstalled(tile)) {
                    pendingLocation = new Location(
                            "",
                            coordinates.toWorldX(u),
                            coordinates.toWorldY(v),
                            camera.getPosition().getZ());
                    focusName = true;
                    saveError = "";
                }
            } else if (labelHover != null) {
                action = new Action(Command.MOVE, labelHover);
            } else if (nearby.size() > 1) {
                cluster = List.copyOf(nearby);
                ImGui.openPopup("##mapLocationCluster");
            } else if (!nearby.isEmpty()) {
                action = new Action(Command.MOVE, nearby.getFirst());
            } else if ((layerClick = layerClick(transform, mouseX, mouseY)) != null) {
                if (layerClick.destination() != null) {
                    action = new Action(Command.MOVE, layerClick.destination());
                } else if (layerClick.close()) {
                    action = new Action(Command.CLOSE, null);
                }
            } else if (coordinates.isInstalled(tile)) {
                action = new Action(
                        Command.MOVE,
                        new Location(
                                tile,
                                coordinates.toWorldX(u),
                                coordinates.toWorldY(v),
                                camera.getPosition().getZ()));
            }
        }
        if (hovered && !locked && ImGui.isMouseClicked(1)) {
            rightX = mouseX;
            rightY = mouseY;
        }
        if (!Float.isNaN(rightX) && ImGui.isMouseReleased(1)) {
            var still = Math.hypot(mouseX - rightX, mouseY - rightY) < DRAG_PIXELS;
            rightX = Float.NaN;
            rightY = Float.NaN;
            if (still && hovered && !addingLocation) {
                menuLayer = null;
                for (var i = layers.size() - 1; i >= 0 && menuLayer == null; i--) {
                    var layer = layers.get(i);
                    if (layer.isShown() && layer.openMenu(transform, mouseX, mouseY)) {
                        menuLayer = layer;
                    }
                }
                menuPoint = coordinates.isInstalled(tile)
                        ? new Location(
                                tile,
                                coordinates.toWorldX(u),
                                coordinates.toWorldY(v),
                                camera.getPosition().getZ())
                        : null;
                if (menuLayer != null || menuPoint != null) {
                    ImGui.openPopup("##mapLayerMenu");
                }
            }
        }
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10, 8);
        if (ImGui.beginPopup("##mapLayerMenu")) {
            var chosen = menuLayer == null ? null : menuLayer.getMenu();
            if (chosen != null) {
                ImGui.closeCurrentPopup();
                if (chosen.destination() != null) {
                    action = new Action(Command.MOVE, chosen.destination());
                } else if (chosen.close()) {
                    action = new Action(Command.CLOSE, null);
                }
            }
            if (menuPoint != null) {
                if (menuLayer != null) {
                    ImGui.separator();
                }
                if (ImGui.menuItem("Export this area…")) {
                    action = new Action(Command.EXPORT_AREA, menuPoint);
                }
                GeodataWidgets.tip("A square around this point as glTF, for Blender and other 3D programs.");
                if (ImGui.menuItem("Export tile " + menuPoint.name() + "…")) {
                    action = new Action(Command.EXPORT_TILE, menuPoint);
                }
                GeodataWidgets.tip("The whole tile as glTF, for Blender and other 3D programs.");
            }
            ImGui.endPopup();
        }
        ImGui.popStyleVar();
        if (ImGui.beginPopup("##mapLocationCluster")) {
            ImGui.textDisabled("Choose a place / floor");
            for (var place : cluster) {
                if (ImGui.menuItem(place.name())) {
                    action = new Action(Command.MOVE, place);
                }
            }
            ImGui.endPopup();
        }
        return action;
    }

    private void drawLayerMenu() {
        var open = ImGui.isPopupOpen("##mapLayersMenu");
        if (HudWidgets.button("mapLayers", Glyph.LAYERS, "", 28, 28, open, false)) {
            ImGui.openPopup("##mapLayersMenu");
        }
        tooltip("Layers: what the tools draw over the map, and their options.");
        ImGui.setNextWindowPos(ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY() + 4, ImGuiCond.Always, 1, 0);
        ImGui.setNextWindowSize(300, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 10);
        if (ImGui.beginPopup("##mapLayersMenu")) {
            Motion.open(Motion.appear(ImGui.getID("##layersShown"), Motion.OPEN * .8f), 0, -6, .97f);
            ImGui.textColored(HudWidgets.MUTED, "LAYERS");
            for (var i = 0; i < layers.size(); i++) {
                var layer = layers.get(i);
                if (!layer.isAvailable()) {
                    continue;
                }
                ImGui.pushID("layer" + i);
                GeodataWidgets.space(GeodataWidgets.ROW);
                if (ImGui.checkbox(layer.name(), layer.isShown())) {
                    layer.show(!layer.isShown());
                }
                if (layer.isShown()) {
                    layer.drawOptions();
                }
                ImGui.popID();
            }
            ImGui.endPopup();
        }
        ImGui.popStyleVar();
    }

    private @Nullable String layerHover(MapTransform transform, float x, float y) {
        for (var i = layers.size() - 1; i >= 0; i--) {
            var layer = layers.get(i);
            if (layer.isShown()) {
                var tip = layer.getTooltipAt(transform, x, y);
                if (tip != null) {
                    return tip;
                }
            }
        }
        return null;
    }

    private Click layerClick(MapTransform transform, float x, float y) {
        for (var i = layers.size() - 1; i >= 0; i--) {
            var layer = layers.get(i);
            if (layer.isShown()) {
                var click = layer.getClickAt(transform, x, y);
                if (click != null) {
                    return click;
                }
            }
        }
        return null;
    }

    private boolean selectsArea() {
        for (var layer : layers) {
            if (layer.isShown() && layer.selectsArea()) {
                return true;
            }
        }
        return false;
    }

    private Rect imageRect(Rect area, float fitWidth, float fitHeight) {
        return new Rect(
                area.x + (area.width - fitWidth * zoom) / 2 + panX,
                area.y + (area.height - fitHeight * zoom) / 2 + panY,
                fitWidth * zoom,
                fitHeight * zoom);
    }

    private static void tooltip(String text) {
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(text);
        }
    }
}

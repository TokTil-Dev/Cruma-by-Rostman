package com.rost.cruma.viewer.editing;

import static com.rost.cruma.viewer.editing.EditorUi.tip;

import com.rost.cruma.viewer.editing.MeshLibrary.MaterialEntry;
import com.rost.cruma.viewer.editing.MeshLibrary.MeshEntry;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImGuiHoveredFlags;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class MeshLibraryPanel implements AutoCloseable {
    static final float THUMBNAIL = 64;

    static final float DRAG_PIXELS = 6;

    private static final float SWATCH = 32;
    private static final float STAR_COLUMN = 24;
    private static final long PREFAB_NANOS = 2_000_000_000L;
    private static final String WAIT = "Wait for the check or the save to finish.";
    private final EditingPanel panel;
    private final MeshLibrary library;
    private float thumbnail = THUMBNAIL;
    private final ImGuiListClipper clipper = new ImGuiListClipper();
    private boolean materialsShown;
    private String filtered = "\u0000";
    private List<MeshEntry> meshesShown = List.of();
    private List<MaterialEntry> materialsShownList = List.of();
    private List<MeshEntry> meshSource = List.of();
    private List<MaterialEntry> materialSource = List.of();
    private List<Path> prefabs = List.of();
    private @Nullable Path prefabFolder;
    private long prefabsReadAt;
    private boolean dragStarted;
    private @Nullable String pressedRow;
    final Map<String, float[]> rowAreas = new HashMap<>();

    MeshLibraryPanel(EditingPanel panel, MeshLibrary library) {
        this.panel = panel;
        this.library = library;
    }

    void draw(String query) {
        library.start();
        rowAreas.clear();
        if (!ImGui.isMouseDown(0) && !ImGui.isMouseReleased(0)) {
            dragStarted = false;
            pressedRow = null;
        }
        thumbnail = ImGui.getContentRegionAvailX() >= 160 ? THUMBNAIL : 40;
        var pick = panel.getLibraryPickCount();
        if (pick > 0) {
            GeodataWidgets.note(
                    pick == 1
                            ? "Pick a mesh for the selected object. Its place, turn, size and materials stay."
                            : "Pick a mesh for the " + pick
                                    + " selected actors. Their place, turn, size and materials stay.",
                    GeodataWidgets.ACCENT);
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.actionButton("libraryPickCancel", Glyph.CLOSE, "Cancel", -1, false)) {
                panel.cancelLibraryPick();
            }
            tip("Keep the mesh of the selection (Esc).");
            GeodataWidgets.space(GeodataWidgets.BLOCK);
        } else {
            var shown = GeodataWidgets.segmented(
                    "libraryKind", new String[] {"Meshes", "Materials"}, materialsShown ? 1 : 0);
            if (shown >= 0) {
                materialsShown = shown == 1;
            }
            tip("Meshes to place, or the materials of the client's texture packages.");
            GeodataWidgets.space(GeodataWidgets.BLOCK);
        }
        var text = query.strip().toLowerCase(Locale.ROOT);
        if (materialsShown && pick == 0) {
            drawMaterials(text);
        } else {
            drawMeshes(text);
        }
    }

    @Override
    public void close() {
        clipper.destroy();
    }

    private void drawMeshes(String query) {
        if (!library.areMeshesReady()) {
            var progress = library.getMeshProgress();
            GeodataWidgets.hint(
                    progress[1] == 0
                            ? "Looking for mesh packages…"
                            : "Reading mesh packages · " + progress[0] + " of " + progress[1]);
            tip("Cruma reads the client's meshes once and keeps the list in its settings folder, so the next time"
                    + " it is ready at once.");
            GeodataWidgets.space(GeodataWidgets.ROW);
        } else if (!library.getMeshFailure().isBlank()) {
            GeodataWidgets.note(library.getMeshFailure(), GeodataWidgets.WARNING);
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        refreshFilter(query);
        ImGui.beginChild("##libraryRows", 0, 0, false);
        var chosen = panel.getChosenMeshPath();
        var counts = panel.getSceneMeshCounts();
        var inMap = new ArrayList<String>();
        for (var mesh : counts.keySet()) {
            if (matches(mesh, query)) {
                inMap.add(mesh);
            }
        }
        inMap.sort(String.CASE_INSENSITIVE_ORDER);
        var favorites = new ArrayList<String>();
        for (var mesh : library.getFavorites().list()) {
            if (matches(mesh, query)) {
                favorites.add(mesh);
            }
        }
        group("FAVOURITES", favorites, counts, chosen, false, true);
        group("IN THIS MAP", inMap, counts, chosen, true, favorites.isEmpty());
        var recent = new ArrayList<String>();
        for (var mesh : library.getRecent()) {
            if (matches(mesh, query)) {
                recent.add(mesh);
            }
        }
        if (!recent.isEmpty()) {
            group("RECENT", recent, counts, chosen, false, false);
        }
        if (panel.getLibraryPickCount() == 0) {
            drawPrefabs(query);
        }
        GeodataWidgets.section("ALL · " + meshesShown.size());
        if (meshesShown.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, library.areMeshesReady() ? "No mesh matches." : "Reading…");
        }
        var height = rowHeight(thumbnail);
        clipper.begin(meshesShown.size(), height);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                var mesh = meshesShown.get(i);
                meshRow("all/" + mesh.path(), mesh.path(), mesh, detail(mesh, counts), chosen);
            }
        }
        clipper.end();
        ImGui.endChild();
    }

    private void group(
            String title,
            List<String> paths,
            Map<String, Integer> counts,
            @Nullable String chosen,
            boolean always,
            boolean first) {
        if (paths.isEmpty() && !always) {
            return;
        }
        GeodataWidgets.section(title + " · " + paths.size(), first ? GeodataWidgets.ROW : GeodataWidgets.SECTION);
        if (paths.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No mesh of the loaded maps matches.");
            return;
        }
        var height = rowHeight(thumbnail);
        clipper.begin(paths.size(), height);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                var path = paths.get(i);
                var mesh = library.getMesh(path);
                meshRow(
                        title + "/" + path,
                        path,
                        mesh,
                        mesh == null ? placed(path, counts) : detail(mesh, counts),
                        chosen);
            }
        }
        clipper.end();
    }

    private void meshRow(String id, String path, @Nullable MeshEntry mesh, String detail, @Nullable String chosen) {
        var texture = mesh == null ? 0 : library.getThumbnail(mesh);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var rowWidth = width - STAR_COLUMN;
        var clicked = GeodataWidgets.thumbnailRow(
                id,
                texture,
                Glyph.BOX,
                EditingSession.shortName(path),
                detail,
                thumbnail,
                path.equalsIgnoreCase(chosen),
                rowWidth);
        var height = ImGui.getItemRectMaxY() - y;
        if (startsDrag(path, x, y)) {
            panel.startLibraryDrag(path, mesh == null ? null : mesh.packageFile(), texture);
        }
        if (clicked && !dragStarted && !panel.isWriting()) {
            panel.chooseMesh(path, mesh == null ? null : mesh.packageFile());
        }
        var inWindow = ImGui.isWindowHovered(ImGuiHoveredFlags.AllowWhenBlockedByActiveItem);
        if (inWindow && ImGui.isMouseHoveringRect(x, y, x + rowWidth, y + height)) {
            tipMesh(path, mesh);
        }
        drawStar(
                id,
                path,
                x,
                rowWidth,
                y,
                height,
                width,
                inWindow && ImGui.isMouseHoveringRect(x, y, x + width, y + height));
    }

    private void drawStar(
            String id, String path, float x, float rowWidth, float top, float height, float width, boolean rowHovered) {
        var favorites = library.getFavorites();
        var on = favorites.contains(path);
        var left = x + rowWidth;
        var column = width - rowWidth;
        ImGui.setCursorScreenPos(left, top);
        var pressed = ImGui.invisibleButton(id + "/star", column, height);
        var over = ImGui.isItemHovered();
        if (on || rowHovered) {
            var size = 16f;
            HudWidgets.glyph(
                    ImGui.getWindowDrawList(),
                    Glyph.STAR,
                    left + (column - size) * .5f,
                    top + (height - size) * .5f,
                    size,
                    on ? GeodataWidgets.ACCENT : over ? HudWidgets.TEXT : GeodataWidgets.FAINT);
        }
        if (over) {
            var name = EditingSession.shortName(path);
            HudWidgets.showTooltip(
                    on
                            ? "Take " + name + " out of the favourites."
                            : "Add " + name + " to the favourites. They stand first in the Library.");
        }
        if (pressed) {
            favorites.toggle(path);
            if (!favorites.getFailure().isEmpty()) {
                panel.showError(favorites.getFailure());
            }
        }
        ImGui.setCursorScreenPos(x, top + height);
        ImGui.dummy(rowWidth, 0);
    }

    private void tipMesh(String path, @Nullable MeshEntry mesh) {
        var size = mesh == null
                ? ""
                : "\n"
                        + Math.round(mesh.bounds()[3] - mesh.bounds()[0])
                        + " × "
                        + Math.round(mesh.bounds()[4] - mesh.bounds()[1])
                        + " × "
                        + Math.round(mesh.bounds()[5] - mesh.bounds()[2])
                        + " units · "
                        + EditorUi.meters(mesh.bounds()[3] - mesh.bounds()[0])
                        + " × "
                        + EditorUi.meters(mesh.bounds()[4] - mesh.bounds()[1])
                        + " × "
                        + EditorUi.meters(mesh.bounds()[5] - mesh.bounds()[2]);
        var action = panel.isWriting()
                ? WAIT
                : panel.getLibraryPickCount() > 0
                        ? "Click to give the selected objects this mesh."
                        : "Click to place it with the Place tool (P), or drag it into the view.";
        HudWidgets.showTooltip(path + size + "\n" + action);
    }

    private void drawPrefabs(String query) {
        var folder = panel.getPrefabFolder();
        var now = System.nanoTime();
        if (!folder.equals(prefabFolder) || now - prefabsReadAt > PREFAB_NANOS) {
            prefabFolder = folder;
            prefabsReadAt = now;
            prefabs = ActorClipboard.prefabs(folder);
        }
        var shown = new ArrayList<Path>();
        for (var file : prefabs) {
            if (matches(file.getFileName().toString(), query)) {
                shown.add(file);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        GeodataWidgets.section("PREFABS · " + shown.size());
        var chosen = panel.getChosenPrefab();
        for (var file : shown) {
            var name = file.getFileName().toString();
            var title = name.toLowerCase(Locale.ROOT).endsWith(".json") ? name.substring(0, name.length() - 5) : name;
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var clicked = GeodataWidgets.thumbnailRow(
                    "prefab/" + file, 0, Glyph.LAYERS, title, "Prefab", thumbnail, file.equals(chosen));
            if (startsDrag(file.toString(), x, y)) {
                panel.startPrefabDrag(file);
            }
            if (clicked && !dragStarted && !panel.isWriting()) {
                panel.choosePrefab(file);
            }
            tip(file + "\n"
                    + (panel.isWriting()
                            ? WAIT
                            : "Click to place the whole group with the Place tool (P), or drag it into the view."));
        }
    }

    private boolean startsDrag(String key, float x, float y) {
        var right = ImGui.getItemRectMaxX();
        var bottom = ImGui.getItemRectMaxY();
        if (ImGui.isRectVisible(x, y, right, bottom)) {
            rowAreas.putIfAbsent(key, new float[] {x, y, right, bottom});
        }
        if (ImGui.isMouseClicked(0)
                && ImGui.isWindowHovered(ImGuiHoveredFlags.AllowWhenBlockedByActiveItem)
                && ImGui.isMouseHoveringRect(x, y, right, bottom)) {
            pressedRow = key;
        }
        if (dragStarted
                || !key.equals(pressedRow)
                || !ImGui.isMouseDragging(0, DRAG_PIXELS)
                || !panel.canDragFromLibrary()) {
            return false;
        }
        dragStarted = true;
        return true;
    }

    private void drawMaterials(String query) {
        library.startMaterials();
        if (!library.areMaterialsReady()) {
            var progress = library.getMaterialProgress();
            GeodataWidgets.hint(
                    progress[1] == 0
                            ? "Looking for texture packages…"
                            : "Reading texture packages · " + progress[0] + " of " + progress[1]);
            GeodataWidgets.space(GeodataWidgets.ROW);
        } else if (!library.getMaterialFailure().isBlank()) {
            GeodataWidgets.note(library.getMaterialFailure(), GeodataWidgets.WARNING);
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        refreshFilter(query);
        ImGui.beginChild("##libraryMaterials", 0, 0, false);
        GeodataWidgets.section("MATERIALS · " + materialsShownList.size(), GeodataWidgets.ROW);
        var height = rowHeight(SWATCH);
        clipper.begin(materialsShownList.size(), height);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                var material = materialsShownList.get(i);
                if (GeodataWidgets.thumbnailRow(
                                "material/" + material.path(),
                                library.getSwatch(material),
                                Glyph.LAYERS,
                                material.name(),
                                material.packageName() + " · " + material.className(),
                                SWATCH,
                                false)
                        && !panel.isWriting()) {
                    panel.applyLibraryMaterial(material);
                }
                if (ImGui.isItemHovered()) {
                    HudWidgets.showTooltip(
                            material.path() + "\n" + (panel.isWriting() ? WAIT : panel.getLibraryMaterialHint()));
                }
            }
        }
        clipper.end();
        ImGui.endChild();
    }

    private void refreshFilter(String query) {
        var meshes = library.getMeshes();
        var materials = library.getMaterials();
        if (query.equals(filtered) && meshes == meshSource && materials == materialSource) {
            return;
        }
        filtered = query;
        meshSource = meshes;
        materialSource = materials;
        var meshResult = new ArrayList<MeshEntry>();
        for (var mesh : meshes) {
            if (matches(mesh.path(), query)) {
                meshResult.add(mesh);
            }
        }
        meshesShown = meshResult;
        var materialResult = new ArrayList<MaterialEntry>();
        for (var material : materials) {
            if (matches(material.path(), query)) {
                materialResult.add(material);
            }
        }
        materialsShownList = materialResult;
    }

    private static boolean matches(String path, String query) {
        return query.isEmpty() || path.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String detail(MeshEntry mesh, Map<String, Integer> counts) {
        var slots = EditorUi.plural(Math.max(1, mesh.slots().size()), "slot", "slots");
        var placed = counts.get(mesh.path());
        return mesh.packageName() + " · " + slots + (placed == null ? "" : " · " + placed + " placed");
    }

    private static String placed(String path, Map<String, Integer> counts) {
        var dot = path.indexOf('.');
        var count = counts.get(path);
        return (dot < 0 ? path : path.substring(0, dot)) + (count == null ? "" : " · " + count + " placed");
    }

    private static float rowHeight(float picture) {
        return Math.max(picture + 8, 2 * ImGui.getFontSize() + 12)
                + ImGui.getStyle().getItemSpacingY();
    }
}

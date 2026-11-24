package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.unreal.edit.MapTileOperation;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Backup;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Kind;
import com.rost.cruma.core.unreal.edit.MapTileOperation.TileRef;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.launch.TileOperationWork;
import com.rost.cruma.viewer.launch.TileOperationWork.Listener;
import com.rost.cruma.viewer.launch.TileOperationWork.Planned;
import com.rost.cruma.viewer.launch.TileOperationWork.Request;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.ui.GeodataWidgets.FooterButton;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.TileMapPicker.Zoom;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;

public final class TileOperationsDialog {
    public static final String TITLE = "Copy, move or swap tiles";

    static final String GUIDE = "export/tile-operations";

    private static final float PANEL_WIDTH = 320;

    private static final float LEAST_BESIDE = 640;

    private static final float CHROME = GeodataWidgets.DIALOG_CHROME;

    private static final int BACKUPS_SHOWN = 5;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    private final Zoom zoom = new Zoom();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-map-tiles");
        thread.setDaemon(true);
        return thread;
    });

    private final ImBoolean replace = new ImBoolean(false);
    private final ImBoolean backup = new ImBoolean(true);
    private final ImString exportFolder = new ImString(4096);
    private final FolderPicker exportPicker = new FolderPicker(
            "Choose export folder",
            "Select the folder that gets the moved map files. The client does not change.",
            false);
    private final ImBoolean withGeodata = new ImBoolean(false);

    private boolean buildGeodata;

    private final ImBoolean rebuildPathNodes = new ImBoolean(false);

    private volatile String runningStep = "";

    private volatile boolean stoppable;
    private volatile boolean stopRequested;
    private final ImBoolean withSpawns = new ImBoolean(false);
    private final ImString geodataFolder = new ImString(4096);
    private final ImString scriptFolder = new ImString(4096);
    private final FolderPicker geodataPicker = new FolderPicker();
    private final FolderPicker scriptPicker = FolderPicker.scripts();
    private final FolderPicker otherPicker = new FolderPicker(
            "Choose the other client",
            "Select the folder of the client the tile comes from. It does not change.",
            false);
    private final ImString otherTileSearch = new ImString(64);

    private boolean fromOther;

    private @Nullable ClientInstallation other;
    private String otherProblem = "";

    private List<Path> otherClients = List.of();

    private final Map<String, ClientInstallation> worlds = new HashMap<>();

    private final Map<WorldVariant, List<String>> files = new HashMap<>();

    private boolean mapImage;

    private boolean opening;
    private ClientInstallation installation;
    private Kind kind = Kind.COPY;
    private @Nullable String source;
    private @Nullable String target;
    private WorldVariant sourceVariant = WorldVariant.MAIN;
    private WorldVariant targetVariant = WorldVariant.MAIN;
    private boolean pickingTarget;
    private boolean toFolder;

    private String planKey = "";
    private @Nullable CompletableFuture<Planned> planning;
    private @Nullable Planned planned;

    private @Nullable CompletableFuture<Done> running;
    private String runningTitle = "";
    private @Nullable Done done;
    private boolean changed;
    private @Nullable String openWorld;

    private @Nullable CompletableFuture<List<Backup>> loadingBackups;
    private List<Backup> backups = List.of();

    private @Nullable MapUpdate mapUpdate;

    private record Done(
            String key,
            String message,
            List<String> details,
            boolean failed,
            @Nullable Path folder,
            @Nullable Path backup,
            @Nullable String world,
            List<String> mapTiles,
            @Nullable ClientInstallation before) {
        Done(
                String key,
                String message,
                List<String> details,
                boolean failed,
                @Nullable Path folder,
                @Nullable Path backup,
                @Nullable String world) {
            this(key, message, details, failed, folder, backup, world, List.of(), null);
        }
    }

    public record MapUpdate(ClientInstallation before, List<String> tiles) {}

    public void open(ClientInstallation installation, List<String> selected) {
        open(installation, selected, "", "");
    }

    public void open(ClientInstallation installation, List<String> selected, String scripts, String geodata) {
        open(installation, selected, scripts, geodata, List.of());
    }

    public void open(
            ClientInstallation installation, List<String> selected, String scripts, String geodata, List<Path> others) {
        var sameClient = this.installation != null && this.installation.root().equals(installation.root());
        this.installation = installation;
        worlds.clear();
        files.clear();
        this.otherClients = others.stream()
                .filter(path -> !path.equals(installation.root()))
                .toList();
        if (!sameClient || scriptFolder.get().isBlank()) {
            scriptFolder.set(scripts);
            withSpawns.set(!scripts.isBlank());
        }
        if (!sameClient || geodataFolder.get().isBlank()) {
            geodataFolder.set(geodata);
            withGeodata.set(!geodata.isBlank());
        }
        if (!sameClient) {
            source = selected.size() == 1 ? selected.getFirst() : null;
            target = null;
            sourceVariant = installation.variant().getMapWorld();
            targetVariant = installation.variant().getMapWorld();
            done = null;
            planKey = "";
            planned = null;
        }
        pickingTarget = source != null;
        opening = true;
        refreshBackups();
    }

    public void refresh(ClientInstallation installation) {
        if (this.installation != null && this.installation.root().equals(installation.root())) {
            this.installation = installation;
            worlds.clear();
            files.clear();
        }
    }

    public ClientInstallation getShownWorld() {
        if (showsOther()) {
            return clientForVariant(other, sourceVariant);
        }
        return clientForVariant(installation, pickingTarget ? targetVariant : sourceVariant);
    }

    public @Nullable MapUpdate takeMapUpdate() {
        var result = mapUpdate;
        mapUpdate = null;
        return result;
    }

    public boolean takeChanged() {
        var result = changed;
        changed = false;
        return result;
    }

    public @Nullable String takeOpenWorld() {
        var result = openWorld;
        openWorld = null;
        return result;
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
        if (!isSourceClassic()) {
            sourceVariant = WorldVariant.MAIN;
        }
        if (!installation.hasClassicMaps()) {
            targetVariant = WorldVariant.MAIN;
        }
        var shown = getShownWorld();
        mapImage = previewTexture != 0;
        var bounds = mapBounds();
        var room = GuideWindow.dialogRoom(LEAST_BESIDE);
        var layout = TileMapPicker.layout(
                bounds,
                previewTexture,
                Math.min(1280, room[1] - 2 * GuideWindow.DIALOG_MARGIN) - 40 - PANEL_WIDTH - 20,
                Math.min(1120, io.getDisplaySizeY() - 32) - CHROME);
        var bodyHeight = Math.max(layout.height(), Math.min(560, io.getDisplaySizeY() - 32 - CHROME));
        ImGui.setNextWindowPos(room[0] + room[1] * .5f, io.getDisplaySizeY() * .5f, ImGuiCond.Always, .5f, .5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, GeodataWidgets.DIALOG_SIDE, GeodataWidgets.DIALOG_EDGE);
        var visible = GeodataWidgets.beginDialog(TITLE, layout.width() + PANEL_WIDTH + 20 + 40, bodyHeight + CHROME);
        ImGui.popStyleVar();
        if (!visible) {
            return;
        }
        poll();
        var typing = ImGui.isAnyItemActive();
        if (GeodataWidgets.dialogTitle(fonts, TITLE, "close-map-tiles", GUIDE) && !isBusy()) {
            ImGui.closeCurrentPopup();
        }

        var top = ImGui.getCursorPosY();
        var finished = shownDone();
        var clicked = TileMapPicker.drawPair(
                shown.tiles(),
                bounds,
                fromOther && !showsOther() ? null : source,
                showsOther() ? null : target,
                targetFiles(),
                finished != null && !finished.failed(),
                layout,
                previewTexture,
                zoom);
        if (clicked != null && !isBusy()) {
            pick(clicked);
        }
        ImGui.setCursorPos(20 + layout.width() + 20, top);
        GeodataWidgets.beginDialogPanel("##map-tiles-panel", PANEL_WIDTH, bodyHeight);
        drawPanel();
        GeodataWidgets.endDialogPanel();
        GeodataWidgets.dialogRule(top + bodyHeight);
        drawFooter();
        if (!isBusy() && (!typing && GeodataWidgets.isEscapePressed() || GeodataWidgets.wasClickedOutside())) {
            ImGui.closeCurrentPopup();
        }
        exportPath();
        var chosenOther = otherPicker.draw();
        if (chosenOther != null) {
            useOther(chosenOther);
        }
        var chosenGeodata = geodataPicker.draw();
        if (chosenGeodata != null) {
            geodataFolder.set(chosenGeodata.toString());
        }
        var chosenScripts = scriptPicker.draw();
        if (chosenScripts != null) {
            scriptFolder.set(chosenScripts.toString());
        }
        GuideWindow.drawOverDialog();
        ImGui.endPopup();
    }

    private List<String> targetFiles() {
        return files.computeIfAbsent(
                targetVariant,
                variant -> WorldMapCatalog.findPackages(installation.root(), variant).entrySet().stream()
                        .filter(entry -> entry.getValue().equalsIgnoreCase(entry.getKey() + variant.getMapSuffix()))
                        .map(Entry::getKey)
                        .toList());
    }

    private boolean shouldBuildGeodata() {
        return fromOther || buildGeodata;
    }

    private boolean isSourceClassic() {
        return fromOther ? other != null && other.hasClassicMaps() : installation.hasClassicMaps();
    }

    private boolean showsOther() {
        return fromOther && other != null && !pickingTarget;
    }

    private ClientInstallation clientForVariant(ClientInstallation client, WorldVariant variant) {
        if (client.variant().getMapWorld() == variant || !client.hasClassicMaps()) {
            return client;
        }
        return worlds.computeIfAbsent(client.root() + "|" + variant, key -> client.withVariant(variant));
    }

    private List<String> mapBounds() {
        var tiles = new LinkedHashSet<String>();
        for (var variant : WorldVariant.values()) {
            if (variant.getMapWorld() == variant) {
                tiles.addAll(clientForVariant(installation, variant).tiles());
            }
        }
        if (fromOther && other != null) {
            tiles.addAll(clientForVariant(other, sourceVariant).tiles());
        }
        return TileMapPicker.withMargin(List.copyOf(tiles));
    }

    private void drawPanel() {
        GeodataWidgets.section("FROM", 0);
        ImGui.beginDisabled(isBusy());
        var pickedFrom = GeodataWidgets.segmented(
                "tile-from", new String[] {"This client", "Another client"}, fromOther ? 1 : 0);
        if (pickedFrom >= 0) {
            fromOther = pickedFrom == 1;
            source = null;
            if (fromOther) {
                kind = Kind.COPY;
            }
            pickingTarget = fromOther && other == null;
        }
        GeodataWidgets.tip(
                "A tile of another client, such as a town of a newer chronicle, copied into this one with the packages"
                        + " it needs. Choose the client, click its tile on its map, then click where it goes on this"
                        + " client's map.");
        if (fromOther) {
            drawOtherClient();
        }
        ImGui.endDisabled();

        GeodataWidgets.section("OPERATION");
        var kinds = Kind.values();
        var labels = new String[kinds.length];
        for (var i = 0; i < kinds.length; i++) {
            labels[i] = kinds[i].getLabel();
        }
        ImGui.beginDisabled(isBusy() || fromOther);
        var picked = GeodataWidgets.segmented("tile-kind", labels, kind.ordinal());
        if (picked >= 0) {
            kind = kinds[picked];
        }
        ImGui.endDisabled();
        if (fromOther) {
            GeodataWidgets.tip("From another client only Copy works, so that client does not change.");
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(
                switch (kind) {
                    case COPY -> "The target gets a moved copy. The source stays.";
                    case MOVE -> "The target gets the moved source. The source file is removed.";
                    case SWAP -> "The two tiles change places.";
                });

        GeodataWidgets.section("TILES");
        var column = GeodataWidgets.columnWidth(2);
        ImGui.beginDisabled(isBusy() || fromOther && other == null);
        if (HudWidgets.button(
                "pick-source", null, "From " + name(source, sourceVariant), column, 28, !pickingTarget, true)) {
            pickingTarget = false;
        }
        ImGui.endDisabled();
        ImGui.beginDisabled(isBusy());
        ImGui.sameLine();
        if (HudWidgets.button(
                "pick-target", null, "To " + name(target, targetVariant), column, 28, pickingTarget, true)) {
            pickingTarget = true;
        }
        var sourceClassic = isSourceClassic();
        if (sourceClassic || installation.hasClassicMaps()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var worlds = new String[] {WorldVariant.MAIN.getLabel(), WorldVariant.CLASSIC.getLabel()};
            ImGui.beginDisabled(!sourceClassic);
            var from = GeodataWidgets.segmented("source-world", worlds, sourceVariant.ordinal(), column);
            ImGui.endDisabled();
            if (from >= 0) {
                sourceVariant = WorldVariant.values()[from];
            }
            GeodataWidgets.tip(
                    sourceClassic
                            ? "Which file of the source tile: X_Y.unr or X_Y_Classic.unr."
                            : (fromOther && other != null ? clientName(other.root()) : "This client")
                                    + " has no Classic maps.");
            ImGui.sameLine();
            ImGui.beginDisabled(!installation.hasClassicMaps());
            var to = GeodataWidgets.segmented("target-world", worlds, targetVariant.ordinal(), column);
            ImGui.endDisabled();
            if (to >= 0) {
                targetVariant = WorldVariant.values()[to];
            }
            GeodataWidgets.tip(
                    installation.hasClassicMaps()
                            ? "Which file the target gets: X_Y.unr or X_Y_Classic.unr."
                            : "This client has no Classic maps.");
        }
        ImGui.endDisabled();
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(
                pickingTarget
                        ? "Click the tile it goes to on the map."
                        : "Click the tile to " + kind.getLabel().toLowerCase(Locale.ROOT) + " on the map.");
        GeodataWidgets.hint(shownWorldText());

        GeodataWidgets.section("CHANGES");
        drawChanges();

        GeodataWidgets.section("SERVER FILES");
        ImGui.beginDisabled(isBusy());
        ImGui.checkbox("Geodata", withGeodata);
        GeodataWidgets.tip("The tile's geodata region goes with it. A server needs it where NPCs walk.");
        if (withGeodata.get()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.beginDisabled(fromOther);
            var mode = GeodataWidgets.segmented(
                    "tile-geodata-mode",
                    new String[] {"Copy the region", "Build from the map"},
                    shouldBuildGeodata() ? 1 : 0);
            ImGui.endDisabled();
            if (mode >= 0) {
                buildGeodata = mode == 1;
            }
            GeodataWidgets.tip(
                    fromOther
                            ? "This server has no region for another client's tile, so it is built from that tile's"
                                    + " map."
                            : "Copy renames the folder's region for the new tile. Build makes it again from the map"
                                    + " with Cruma's geodata builder, a few seconds a tile.");
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.pathField(
                    "tile-geodata-folder",
                    "Geodata folder",
                    geodataFolder,
                    "The server's geodata folder, with files such as 22_22_conv.dat.")) {
                geodataPicker.openPath(geodataFolder.get());
            }
            if (planned != null && planned.pathNodes() && !toFolder) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                ImGui.checkbox("Rebuild pathnodes", rebuildPathNodes);
                GeodataWidgets.tip(
                        "The folder's pathnode graph links its regions across their borders, so it is built again"
                                + " whole, about a second a region. Its old files are kept in a backup folder.");
            }
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        ImGui.endDisabled();
        if (fromOther) {
            GeodataWidgets.hint("The spawns go with a tile of this client only.");
        }
        ImGui.beginDisabled(isBusy() || fromOther);
        ImGui.checkbox("NPC spawns", withSpawns);
        GeodataWidgets.tip("The zones and makers of npcpos.txt that lie inside the tile go with it.");
        if (withSpawns.get()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.pathField(
                    "tile-script-folder", "Script folder", scriptFolder, "The server's folder with npcpos.txt.")) {
                scriptPicker.openPath(scriptFolder.get());
            }
        }
        ImGui.endDisabled();

        GeodataWidgets.section("WRITE TO");
        ImGui.beginDisabled(isBusy());
        var destination = GeodataWidgets.segmented(
                "tile-destination", new String[] {"This client", "A folder"}, toFolder ? 1 : 0);
        if (destination >= 0) {
            toFolder = destination == 1;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(
                toFolder
                        ? "The files go into a folder of their own, and no client changes. Copy them into a client"
                                + " yourself."
                        : "Into the Maps folder of "
                                + clientName(installation.root())
                                + ", the client of the start menu"
                                + (fromOther && other != null
                                        ? ". " + clientName(other.root()) + " is only read."
                                        : "."));
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (toFolder) {
            if (GeodataWidgets.pathField(
                    "tile-export-folder",
                    "Folder",
                    exportFolder,
                    "The moved map files are written here. The client does not change.")) {
                exportPicker.openPath(exportFolder.get());
            }
        } else {
            ImGui.checkbox("Keep a backup", backup);
            GeodataWidgets.tip(
                    "Copies every replaced or removed map into a folder beside Maps, so Undo can put it back.");
        }
        ImGui.endDisabled();

        GeodataWidgets.section("BACKUPS");
        drawBackups();
    }

    private void drawChanges() {
        if (shownDone() != null) {
            GeodataWidgets.note(
                    done.failed() ? "Nothing was changed." : done.message(),
                    done.failed() ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN);
            for (var detail : done.details()) {
                GeodataWidgets.hint(detail);
            }
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.hint("Pick other tiles for the next operation.");
            return;
        }
        if (source == null || target == null) {
            GeodataWidgets.hint("Pick a source and a target.");
            return;
        }
        if (planned == null) {
            GeodataWidgets.hint("Checking the tiles…");
            return;
        }
        var plan = planned.plan();
        var existingTarget = planned.targetExists() && kind != Kind.SWAP;
        if (plan == null) {
            GeodataWidgets.note(
                    existingTarget
                            ? name(target, targetVariant) + ".unr is already in this client."
                            : planned.problem(),
                    existingTarget ? GeodataWidgets.WARNING : GeodataWidgets.BLOCKED);
            if (existingTarget && !toFolder) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                ImGui.beginDisabled(isBusy());
                if (GeodataWidgets.rowButton("Replace it", 0, 2)) {
                    replace.set(true);
                }
                GeodataWidgets.tip("Write over it. With Keep a backup, Undo can put it back.");
                if (GeodataWidgets.rowButton("Swap instead", 1, 2)) {
                    kind = Kind.SWAP;
                }
                GeodataWidgets.tip("The two tiles change places, so nothing is lost.");
                ImGui.endDisabled();
            }
            return;
        }
        var dx = plan.target().x() - plan.source().x();
        var dy = plan.target().y() - plan.source().y();
        GeodataWidgets.value("Offset", formatSigned(dx) + ", " + formatSigned(dy) + " tiles");
        for (var write : plan.writes()) {
            GeodataWidgets.note((toFolder ? "Exports " : "Writes ") + write.fileName(), GeodataWidgets.OPEN);
        }
        if (!toFolder) {
            for (var file : plan.replaced()) {
                GeodataWidgets.note(
                        "Replaces " + file.getFileName() + (backup.get() ? ", kept in the backup" : ""),
                        GeodataWidgets.WARNING);
            }
            if (existingTarget) {
                ImGui.beginDisabled(isBusy());
                ImGui.checkbox("Replace " + name(target, targetVariant) + ".unr", replace);
                ImGui.endDisabled();
            }
            for (var file : plan.removed()) {
                GeodataWidgets.note(
                        "Removes " + file.getFileName() + (backup.get() ? ", kept in the backup" : ""),
                        GeodataWidgets.WARNING);
            }
        }
        for (var warning : plan.warnings()) {
            GeodataWidgets.note(warning, GeodataWidgets.WARNING);
        }
        for (var part : planned.server()) {
            GeodataWidgets.note(part.summary(), part.warning() ? GeodataWidgets.WARNING : GeodataWidgets.OPEN);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint("Neighbouring tiles do not change, so terrain and water may not meet at the edges."
                + (planned.server().isEmpty()
                        ? " The server's geodata and spawns stay unless chosen under Server files."
                        : ""));
    }

    private void drawBackups() {
        if (backups.isEmpty()) {
            GeodataWidgets.hint("No backups yet.");
            return;
        }
        for (var i = 0; i < Math.min(BACKUPS_SHOWN, backups.size()); i++) {
            var entry = backups.get(i);
            ImGui.pushID("backup/" + i);
            var rowTop = ImGui.getCursorPosY();
            ImGui.textUnformatted(entry.operation() + " " + entry.source() + " to " + entry.target());
            ImGui.textDisabled(entry.time().format(TIME) + (entry.undone() ? " · Restored" : ""));
            if (ImGui.isItemHovered()) {
                HudWidgets.showTooltip(entry.folder().toString());
            }
            var after = ImGui.getCursorPosY();
            ImGui.setCursorPos(ImGui.getContentRegionMaxX() - 64 - GeodataWidgets.ROW - 28, rowTop + 4);
            if (HudWidgets.button("show-backup", Glyph.FOLDER, "", 28, 28, false, true)) {
                FileManager.tryShow(entry.folder());
            }
            GeodataWidgets.tip("Show the backup folder: " + entry.folder());
            ImGui.setCursorPos(ImGui.getContentRegionMaxX() - 64, rowTop + 4);
            ImGui.beginDisabled(entry.undone() || isBusy());
            if (HudWidgets.button("undo-backup", Glyph.UNDO, "Undo", 64, 28, false, true)) {
                undo(entry);
            }
            GeodataWidgets.tip("Put back the maps this operation replaced or removed, and remove the ones it created.");
            ImGui.endDisabled();
            ImGui.setCursorPosY(after);
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.popID();
        }
    }

    private void exportPath() {
        var chosen = exportPicker.draw();
        if (chosen != null) {
            exportFolder.set(chosen.toString());
        }
    }

    private void drawFooter() {
        if (running != null) {
            var step = runningStep.isEmpty() ? "Writing, checking, then putting files in place" : runningStep;
            if (GeodataWidgets.busyBar(
                    "tile-running", runningTitle + " · " + step, -1, "Stop", stoppable && !stopRequested)) {
                stopRequested = true;
            }
            GeodataWidgets.tip(
                    stoppable
                            ? "Stop ends the geodata build before anything is written, or the pathnode graph after the"
                                    + " maps are written."
                            : "The files are checked and then put in place at once, so this cannot be stopped"
                                    + " halfway.");
            return;
        }
        var buttons = new ArrayList<FooterButton>();
        String status;
        int color;
        var finished = shownDone();
        if (finished != null) {
            status = finished.message();
            color = finished.failed() ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN;
            if (finished.folder() != null) {
                buttons.add(
                        new FooterButton("show-map-tiles", Glyph.FOLDER, "Show folder", 132, finished.world() == null));
            }
            if (finished.backup() != null) {
                buttons.add(new FooterButton(
                        "undo-map-tiles",
                        Glyph.UNDO,
                        "Undo",
                        96,
                        false,
                        true,
                        "Put back the maps this operation replaced or removed, and remove the ones it created."));
            }
            if (!finished.mapTiles().isEmpty() && finished.before() != null) {
                buttons.add(new FooterButton(
                        "update-map-image",
                        Glyph.CAMERA,
                        "Update map image",
                        170,
                        false,
                        true,
                        "The start menu's map image still shows the old tiles. This closes the dialog and draws "
                                + String.join(" and ", finished.mapTiles()) + " into it again, a few seconds a tile."));
            }
            if (finished.world() != null) {
                var label = "Open at " + finished.world();
                buttons.add(new FooterButton(
                        "open-map-tiles",
                        Glyph.PLAY,
                        label,
                        ImGui.calcTextSizeX(label) + 56,
                        true,
                        true,
                        "Close this and open the world at the tile, to see it."));
            }
        } else {
            var missing = missing();
            status = missing.isEmpty()
                    ? installation.tiles().size() + " tiles · "
                            + installation.variant().getLabel() + " world"
                    : missing;
            color = GeodataWidgets.MUTED;
            var label = actionLabel();
            var glyph = toFolder
                    ? Glyph.FILE
                    : switch (kind) {
                        case COPY -> Glyph.COPY;
                        case MOVE -> Glyph.GO;
                        case SWAP -> Glyph.RELOAD;
                    };
            buttons.add(new FooterButton(
                    "run-map-tiles",
                    glyph,
                    label,
                    Math.max(120, ImGui.calcTextSizeX(label) + 56),
                    true,
                    missing.isEmpty(),
                    missing));
        }
        buttons.add(new FooterButton("done-map-tiles", null, "Done", 96, false));
        var pressed = GeodataWidgets.footer(status, color, buttons);
        if (pressed < 0) {
            return;
        }
        switch (buttons.get(pressed).id()) {
            case "show-map-tiles" -> FileManager.tryOpenFolder(finished.folder());
            case "undo-map-tiles" -> undo(finished.backup(), "Restoring the maps");
            case "update-map-image" -> {
                mapUpdate = new MapUpdate(finished.before(), finished.mapTiles());
                ImGui.closeCurrentPopup();
            }
            case "open-map-tiles" -> {
                openWorld = finished.world();
                ImGui.closeCurrentPopup();
            }
            case "run-map-tiles" -> start();
            default -> ImGui.closeCurrentPopup();
        }
    }

    private String missing() {
        if (fromOther && other == null) {
            return "Choose the client the tile comes from.";
        }
        if (source == null && fromOther) {
            return "Choose the tile of the other client.";
        }
        if (source == null) {
            return "Click the tile to " + kind.getLabel().toLowerCase(Locale.ROOT) + " on the map.";
        }
        if (target == null) {
            return "Click the tile it goes to on the map.";
        }
        if (planned == null) {
            return "Checking the tiles…";
        }
        if (planned.plan() == null) {
            return planned.targetExists() && kind != Kind.SWAP
                    ? "Choose Replace it or Swap instead."
                    : planned.problem();
        }
        if (toFolder && exportFolder.get().isBlank()) {
            return "Choose the folder to write to.";
        }
        return "";
    }

    private @Nullable Done shownDone() {
        return done != null && done.key().equals(choicesKey()) ? done : null;
    }

    private String choicesKey() {
        return kind
                + "|"
                + (fromOther && other != null ? other.root() + "/" : "")
                + source
                + "|"
                + sourceVariant
                + "|"
                + target
                + "|"
                + targetVariant
                + "|"
                + toFolder
                + "|"
                + exportFolder.get().trim();
    }

    private void drawOtherClient() {
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Client");
        var preview = other == null ? "Choose a client" : clientName(other.root());
        ImGui.setNextItemWidth(-1);
        if (HudWidgets.beginCombo("##tile-other-client", preview, 0)) {
            for (var path : otherClients) {
                if (ImGui.selectable(
                        clientName(path) + "##" + path,
                        other != null && other.root().equals(path))) {
                    useOther(path);
                }
                if (ImGui.isItemHovered()) {
                    HudWidgets.showTooltip(path.toString());
                }
            }
            if (ImGui.selectable("Choose a folder…")) {
                otherPicker.openPath(other == null ? "" : other.root().toString());
            }
            ImGui.endCombo();
        }
        if (!otherProblem.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(otherProblem, GeodataWidgets.WARNING);
        }
        if (other == null) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Its tile");
        var typed = GeodataWidgets.searchList(
                "##tile-other-tile",
                source == null ? "Choose a tile" : source,
                otherTileSearch,
                "Find a tile, such as 22_22",
                -1);
        if (typed != null) {
            for (var tile : other.tiles()) {
                if (tile.contains(typed)
                        && GeodataWidgets.listItem(
                                tile,
                                tile,
                                installation.tiles().contains(tile) ? "here too" : "",
                                tile.equals(source))) {
                    source = tile;
                    if (target == null) {
                        target = tile;
                    }
                    pickingTarget = true;
                }
            }
            ImGui.endCombo();
        }
    }

    private void useOther(Path folder) {
        try {
            other = ClientInstallation.inspect(folder);
            otherProblem =
                    other.root().equals(installation.root()) ? "That is this client. Choose This client above." : "";
            if (!otherProblem.isEmpty()) {
                other = null;
            }
        } catch (RuntimeException unreadable) {
            other = null;
            otherProblem = "Not a game client: " + (unreadable.getMessage() == null ? folder : unreadable.getMessage());
        }
        source = null;
        pickingTarget = other == null;
    }

    private void pick(String tile) {
        if (pickingTarget) {
            target = tile.equals(target) ? null : tile;
        } else {
            source = tile.equals(source) ? null : tile;
            if (source != null && target == null) {
                if (fromOther) {
                    target = source;
                }
                pickingTarget = true;
            }
        }
    }

    private String shownWorldText() {
        var shown = getShownWorld();
        var client = showsOther() ? clientName(shown.root()) : "this client";
        var world = shown.hasWorldChoice()
                ? client + "'s " + shown.variant().getMapWorld().getLabel() + " world"
                : client;
        return "The map shows " + world
                + (mapImage ? "." : ". It has no map image yet; the start menu can create one.");
    }

    private void poll() {
        var key = kind
                + "|"
                + source
                + "|"
                + sourceVariant
                + "|"
                + target
                + "|"
                + targetVariant
                + "|"
                + replace.get()
                + "|"
                + (withGeodata.get() ? geodataFolder.get().trim() : "")
                + "|"
                + (withSpawns.get() ? scriptFolder.get().trim() : "")
                + "|"
                + (fromOther && other != null ? other.root() : "")
                + "|"
                + shouldBuildGeodata()
                + "|"
                + (rebuildPathNodes.get() && !toFolder);
        if (!key.equals(planKey) && running == null && shownDone() == null) {
            planKey = key;
            planned = null;
            planning = null;
            if (source != null && target != null) {
                var sourceRoot = fromOther && other != null ? other.root() : installation.root();
                var request = new Request(
                        installation.root(),
                        sourceRoot,
                        kind,
                        toTileRef(source, sourceVariant),
                        toTileRef(target, targetVariant),
                        replace.get(),
                        withGeodata.get() ? folderOf(geodataFolder.get()) : null,
                        withSpawns.get() && !fromOther ? scriptsOf(scriptFolder.get()) : null,
                        shouldBuildGeodata(),
                        rebuildPathNodes.get() && !toFolder);
                planning = CompletableFuture.supplyAsync(() -> TileOperationWork.plan(request), worker);
            }
        }
        if (planning != null && planning.isDone()) {
            planned = planning.join();
            planning = null;
        }
        if (running != null && running.isDone()) {
            try {
                done = running.join();
            } catch (RuntimeException failure) {
                done = new Done(choicesKey(), failureMessage(failure), List.of(), true, null, null, null);
            }
            running = null;
            changed = true;
            planKey = "";
            refreshBackups();
        }
        if (loadingBackups != null && loadingBackups.isDone()) {
            try {
                backups = loadingBackups.join();
            } catch (RuntimeException failure) {
                backups = List.of();
            }
            loadingBackups = null;
        }
    }

    private void start() {
        var plan = planned.plan();
        var folder = toFolder ? Path.of(exportFolder.get().trim()) : null;
        var keep = backup.get();
        var key = choicesKey();
        var world = folder == null && targetVariant == installation.variant().getMapWorld() ? target : null;
        runningTitle = (folder != null
                        ? "Exporting "
                        : switch (kind) {
                            case COPY -> "Copying ";
                            case MOVE -> "Moving ";
                            case SWAP -> "Swapping ";
                        })
                + plan.source()
                + (kind == Kind.SWAP ? " and " : " to ")
                + plan.target();
        var headline = doneMessage(plan.source().toString(), plan.target().toString());
        var work = planned;
        var before = installation;
        var mapTiles = changedTiles();
        done = null;
        stopRequested = false;
        running = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        var ran = TileOperationWork.run(
                                work,
                                folder,
                                keep,
                                new Listener() {
                                    @Override
                                    public void step(String text) {
                                        runningStep = text;
                                    }

                                    @Override
                                    public void stoppable(boolean value) {
                                        stoppable = value;
                                    }
                                },
                                () -> stopRequested);
                        if (folder != null) {
                            return new Done(
                                    key, "Exported " + plan.target() + ".", ran.details(), false, folder, null, null);
                        }
                        return new Done(
                                key,
                                headline,
                                ran.details(),
                                false,
                                plan.maps(),
                                ran.result().backup(),
                                world,
                                mapTiles,
                                before);
                    } catch (CancellationException stopped) {
                        throw new IllegalStateException("Stopped. Nothing was changed.", stopped);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    } finally {
                        stoppable = false;
                        runningStep = "";
                    }
                },
                worker);
    }

    private List<String> changedTiles() {
        var tiles = new ArrayList<String>();
        var shown = installation.variant().getMapWorld();
        if (targetVariant == shown) {
            tiles.add(target);
        }
        if (kind != Kind.COPY && !fromOther && sourceVariant == shown && !tiles.contains(source)) {
            tiles.add(source);
        }
        return List.copyOf(tiles);
    }

    private void undo(Backup entry) {
        undo(entry.folder(), "Restoring " + entry.source() + " and " + entry.target());
    }

    private void undo(Path backupFolder, String title) {
        runningTitle = title;
        var key = choicesKey();
        var geodata = withGeodata.get() ? folderOf(geodataFolder.get()) : null;
        done = null;
        running = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        var undone = TileOperationWork.undo(backupFolder, geodata);
                        var restored = undone.restored();
                        return new Done(
                                key,
                                "Restored " + restored.size() + (restored.size() == 1 ? " map." : " maps."),
                                undone.details(),
                                false,
                                backupFolder.getParent(),
                                null,
                                null);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                },
                worker);
    }

    private void refreshBackups() {
        var root = installation.root();
        loadingBackups = CompletableFuture.supplyAsync(() -> TileOperationWork.backups(root), worker);
    }

    private boolean isBusy() {
        return running != null;
    }

    private String actionLabel() {
        if (source == null || target == null) {
            return kind.getLabel();
        }
        var from = name(source, sourceVariant);
        var to = name(target, targetVariant);
        if (toFolder) {
            return "Export " + from + (kind == Kind.SWAP ? " and " : " as ") + to;
        }
        return kind.getLabel() + " " + from + (kind == Kind.SWAP ? " and " : " to ") + to;
    }

    private String doneMessage(String from, String to) {
        return switch (kind) {
            case COPY -> "Copied " + from + " to " + to + ".";
            case MOVE -> "Moved " + from + " to " + to + ".";
            case SWAP -> "Swapped " + from + " and " + to + ".";
        };
    }

    private static String clientName(Path root) {
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }

    private static @Nullable Path folderOf(String typed) {
        try {
            var path = Path.of(typed.trim());
            return !typed.isBlank() && Files.isDirectory(path) ? path : null;
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    private static @Nullable Path scriptsOf(String typed) {
        var folder = folderOf(typed);
        return folder == null ? null : SpawnSource.resolveFolder(folder);
    }

    private static String name(@Nullable String tile, WorldVariant variant) {
        return tile == null ? "…" : tile + variant.getMapSuffix();
    }

    private static TileRef toTileRef(String tile, WorldVariant variant) {
        var parts = tile.split("_");
        return new TileRef(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), variant);
    }

    private static String formatSigned(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private static String failureMessage(RuntimeException failure) {
        Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}

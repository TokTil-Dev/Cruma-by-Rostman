package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.export.NpcBatchExport;
import com.rost.cruma.core.export.NpcBatchExport.Entry;
import com.rost.cruma.core.export.NpcBatchExport.Outcome;
import com.rost.cruma.core.export.NpcBatchExport.Summary;
import com.rost.cruma.core.export.NpcExport;
import com.rost.cruma.core.export.NpcExport.Character;
import com.rost.cruma.core.export.NpcExport.Npc;
import com.rost.cruma.core.export.NpcExport.Result;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.launch.BlenderApp;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.spawn.SpawnModelData;
import com.rost.cruma.viewer.spawn.SpawnModels;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import com.rost.cruma.viewer.ui.BatchWidgets.Pick;
import com.rost.cruma.viewer.ui.CharacterForm.Look;
import com.rost.cruma.viewer.ui.GeodataWidgets.FooterButton;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class NpcExportDialog {
    public static final String TITLE = "Export NPC";
    static final String GUIDE = "npcs/export-an-npc";

    private static final float PANEL_WIDTH = 320;

    private static final float LEAST_BESIDE = 760;

    private static final int PREVIEW_WIDTH = 320;

    private static final int PREVIEW_HEIGHT = 260;

    private static final int CHARACTER_PREVIEW_HEIGHT = 400;

    private static final int TURN_STEP = 10;

    private static final float TURN_PER_PIXEL = .6f;

    private static final int BATCH_ROWS = 4;

    private static final float CHECK_ZONE = 22;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-npc-export");
        thread.setDaemon(true);
        return thread;
    });

    private final ExportSettings settings;
    private final ExportScaleChoice scale;
    private final ImString search = new ImString(256);
    private final ImString folder = new ImString(4096);

    private String offeredFolder = "";

    private final FolderPicker folderPicker = new FolderPicker(
            "Choose export folder",
            "Select the folder that gets the export. A folder named after the NPC is made in it.",
            false);
    private final ImBoolean replace = new ImBoolean(false);
    private final ImGuiListClipper clipper = new ImGuiListClipper();
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-npc-preview");
        thread.setDaemon(true);
        return thread;
    });

    private @Nullable ViewerUiRenderer renderer;
    private @Nullable SpawnModels models;

    private volatile Map<Integer, SpawnStyle> kinds = Map.of();

    private String previewKey = "";

    private @Nullable CompletableFuture<@Nullable SpawnModelData> modelLoading;

    private @Nullable SpawnModelData previewModel;

    private final Map<Integer, Long> previewTextures = new HashMap<>();

    private @Nullable CompletableFuture<byte[]> previewing;

    private int previewingStep;
    private int shownStep;

    private float turn;

    private String previewProblem = "";

    private boolean opening;
    private ClientInstallation installation;
    private WorldVariant variant = WorldVariant.MAIN;
    private @Nullable CompletableFuture<List<Npc>> loading;
    private List<Npc> npcs = List.of();
    private String problem = "";
    private String filterKey = "";
    private @Nullable List<Npc> filtered;
    private List<Npc> shown = List.of();
    private @Nullable Npc selected;
    private boolean scrollToSelected;

    private String existingKey = "";
    private List<Path> existing = List.of();

    private @Nullable CompletableFuture<Result> running;
    private volatile String stage = "";
    private volatile double fraction = -1;
    private volatile boolean stopping;
    private String exporting = "";
    private String outcome = "";

    private String outcomeKey = "";

    private boolean failed;
    private @Nullable Path written;

    private int pendingNpc = -1;

    private boolean characterMode;

    private final CharacterForm characterForm = new CharacterForm("character");

    private final Map<String, Chosen> batch = new LinkedHashMap<>();

    private List<Chosen> batchList = List.of();

    private int batchVersion;

    private @Nullable Npc batchAnchor;

    private final ImGuiListClipper batchClipper = new ImGuiListClipper();
    private final ImBoolean replaceBatch = new ImBoolean(false);

    private String clashKey = "";

    private int clashes;
    private String clashExample = "";
    private @Nullable CompletableFuture<Summary> batchRunning;

    private final Map<String, Integer> runIndex = new HashMap<>();

    private final List<Outcome> runOutcomes = new CopyOnWriteArrayList<>();
    private @Nullable Summary batchResult;
    private String batchProblem = "";

    private String batchOutcomeKey = "";

    private @Nullable Path batchFolder;

    private boolean oneScene = true;

    private float rowsTop = Float.NaN;

    private float rowsScroll;

    private boolean batchScene;

    private String batchNote = "";

    private boolean drawn;

    public NpcExportDialog(ExportSettings settings) {
        this.settings = settings;
        scale = new ExportScaleChoice(settings);
    }

    private record Chosen(
            String key,
            Entry entry,
            String title,
            String detail,
            @Nullable Look look) {
        static Chosen of(Npc npc) {
            return new Chosen(npcKey(npc), Entry.of(npc), "", "", null);
        }
    }

    public void setRenderer(ViewerUiRenderer renderer) {
        this.renderer = renderer;
    }

    public void open(ClientInstallation installation) {
        open(installation, "");
    }

    public void open(ClientInstallation installation, String scriptFolder) {
        var sameClient = this.installation != null
                && this.installation.root().equals(installation.root())
                && this.variant == installation.variant();
        this.installation = installation;
        this.variant = installation.variant();
        if (!sameClient) {
            if (models != null) {
                models.close();
            }
            models = new SpawnModels(installation.root(), installation.variant());
            kinds = Map.of();
            readScript(scriptFolder, models);
            dropPreview();
            characterForm.reset();
            npcs = List.of();
            shown = List.of();
            filterKey = "";
            selected = null;
            batch.clear();
            batchChanged();
            batchResult = null;
            runIndex.clear();
            runOutcomes.clear();
            outcome = "";
            written = null;
            problem = "";
            var root = installation.root();
            var world = variant;
            loading = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return NpcExport.list(root, world);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure.getMessage(), failure);
                        }
                    },
                    worker);
        }
        settings.refresh();
        scale.load();
        var offered = settings.getFolder().toString();
        if (folder.get().isBlank() || !offered.equals(offeredFolder)) {
            folder.set(offered);
            offeredFolder = offered;
        }
        existingKey = "";
        opening = true;
    }

    public void openNpc(ClientInstallation installation, String scriptFolder, int npcId) {
        open(installation, scriptFolder);
        characterMode = false;
        search.set("");
        pendingNpc = npcId;
    }

    public void release() {
        dropPreview();
        if (models != null) {
            models.close();
            models = null;
        }
        installation = null;
    }

    public boolean isOpen() {
        return opening || drawn;
    }

    public void draw(FontManager fonts) {
        if (installation == null) {
            return;
        }
        if (opening) {
            opening = false;
            ImGui.openPopup(TITLE);
        }
        var io = ImGui.getIO();
        var room = GuideWindow.dialogRoom(LEAST_BESIDE);
        var width = Math.min(1040, room[1] - 2 * GuideWindow.DIALOG_MARGIN);
        var height = Math.min(760, io.getDisplaySizeY() - 32);
        ImGui.setNextWindowPos(room[0] + room[1] * .5f, io.getDisplaySizeY() * .5f, ImGuiCond.Always, .5f, .5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, GeodataWidgets.DIALOG_SIDE, GeodataWidgets.DIALOG_EDGE);
        var visible = GeodataWidgets.beginDialog(TITLE, width, height);
        ImGui.popStyleVar();
        drawn = visible;
        if (!visible) {
            return;
        }
        poll();
        var typing = ImGui.isAnyItemActive();
        if (GeodataWidgets.dialogTitle(fonts, TITLE, "close-npc-export", GUIDE) && !isBusy()) {
            ImGui.closeCurrentPopup();
        }

        var top = ImGui.getCursorPosY();
        var footer = GeodataWidgets.FOOTER + 2 * GeodataWidgets.BLOCK + 1;
        var bodyHeight = ImGui.getWindowHeight() - top - GeodataWidgets.DIALOG_EDGE - footer;
        var listWidth = ImGui.getWindowWidth() - 40 - PANEL_WIDTH - 20;
        ImGui.beginChild("##npc-export-list", listWidth, bodyHeight, false, 0);
        drawList();
        ImGui.endChild();
        ImGui.setCursorPos(20 + listWidth + 20, top);
        GeodataWidgets.beginDialogPanel("##npc-export-panel", PANEL_WIDTH, bodyHeight);
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
        GuideWindow.drawOverDialog();
        ImGui.endPopup();
    }

    private void drawList() {
        ImGui.beginDisabled(isBusy());
        var pickedMode = GeodataWidgets.segmented(
                "npc-export-mode", new String[] {"NPCs", "Player character"}, characterMode ? 1 : 0);
        if (pickedMode >= 0) {
            characterMode = pickedMode == 1;
        }
        ImGui.endDisabled();
        GeodataWidgets.space(GeodataWidgets.ROW);
        drawBatch();
        var listTop = rowsTop;
        rowsTop = Float.NaN;
        if (characterMode) {
            drawCharacter();
            return;
        }
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        GeodataWidgets.searchField(
                "##npc-search",
                "Find an NPC by name, title, id or mesh",
                search,
                ImGui.getContentRegionAvailX(),
                ImGui.isWindowAppearing());
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (loading != null) {
            GeodataWidgets.hint("Reading the client's NPC tables…");
            return;
        }
        if (!problem.isEmpty()) {
            GeodataWidgets.note(problem, GeodataWidgets.BLOCKED);
            return;
        }
        filter();
        GeodataWidgets.hint(
                shown.size() == npcs.size() ? npcs.size() + " NPCs" : shown.size() + " of " + npcs.size() + " NPCs");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var top = ImGui.getCursorScreenPosY();
        if (!Float.isNaN(listTop) && top != listTop && !ImGui.isWindowAppearing()) {
            ImGui.setNextWindowScroll(-1, Math.max(0, rowsScroll + top - listTop));
        }
        rowsTop = top;
        ImGui.beginChild("##npc-rows", 0, 0, false, 0);
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        var query = search.get().trim().toLowerCase(Locale.ROOT);
        if (scrollToSelected && selected != null) {
            var index = shown.indexOf(selected);
            if (index >= 0) {
                ImGui.setScrollY(Math.max(0, index * rowHeight - rowHeight * 3));
            }
            scrollToSelected = false;
        }
        clipper.begin(shown.size(), rowHeight);
        while (clipper.step()) {
            for (var index = clipper.getDisplayStart(); index < clipper.getDisplayEnd(); index++) {
                var npc = shown.get(index);
                var x = ImGui.getCursorScreenPosX();
                var y = ImGui.getCursorScreenPosY();
                var rowWidth = ImGui.getContentRegionAvailX();
                var isSelected = npc.equals(selected);
                ImGui.pushID(npc.id());
                if (ImGui.selectable("##npc", isSelected, 0, rowWidth, rowHeight - 2) && !isBusy()) {
                    if (ImGui.getMousePosX() < x + CHECK_ZONE) {
                        tick(npc, ImGui.getIO().getKeyShift());
                    } else {
                        selected = npc;
                    }
                }
                if (ImGui.isItemHovered() && ImGui.getMousePosX() < x + CHECK_ZONE) {
                    GeodataWidgets.tip("Export it with the others. Shift-click ticks the rows between.");
                }
                ImGui.popID();
                var title = npc.title().isBlank() ? npc.label() : npc.label() + " · " + npc.title();
                var draw = ImGui.getWindowDrawList();
                SpawnPanelWidgets.checkBox(draw, x + 4, y + (rowHeight - 2 - 16) * .5f, batch.containsKey(npcKey(npc)));
                SpawnPanelWidgets.listRow(
                        draw,
                        x + CHECK_ZONE,
                        y,
                        rowWidth - CHECK_ZONE,
                        spawnStyleOf(npc).argb,
                        title,
                        detail(npc),
                        Integer.toString(npc.id()),
                        query,
                        true);
            }
        }
        clipper.end();
        rowsScroll = ImGui.getScrollY();
        ImGui.endChild();
    }

    private void drawBatch() {
        ImGui.beginDisabled(isBusy());
        var allLabel = characterMode
                ? "Add this character"
                : shown.isEmpty() ? "Add all shown" : String.format(Locale.ROOT, "Add all %,d shown", shown.size());
        var allTip = characterMode
                ? "Adds the race, hair and outfit chosen below, to export with the others."
                : "Ticks every NPC the list shows now, the search results, to export them together.";
        var pick = BatchWidgets.begin("npc-batch", batch.size(), "model", "models", allLabel, allTip);
        if (pick == Pick.ALL) {
            if (characterMode) {
                addCharacter();
            } else {
                for (var npc : shown) {
                    batch.putIfAbsent(npcKey(npc), Chosen.of(npc));
                }
                batchChanged();
            }
        } else if (pick == Pick.NONE) {
            batch.clear();
            batchChanged();
        }
        if (batch.isEmpty()) {
            GeodataWidgets.hint(
                    characterMode
                            ? "Characters added here are exported together with the NPCs ticked in their list."
                            : "Tick NPCs in the list to export several at once, each into a folder of its own.");
        } else {
            drawBatchRows();
        }
        BatchWidgets.end();
        ImGui.endDisabled();
    }

    private void drawBatchRows() {
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        var spacing = ImGui.getStyle().getItemSpacingY();
        var step = rowHeight + spacing;
        var rows = Math.min(batchList.size(), BATCH_ROWS);
        ImGui.beginChild("##npc-batch-rows", BatchWidgets.inner(), rows * step - spacing, false, 0);
        var current = characterMode ? characterForm.getKey() : selected == null ? "" : npcKey(selected);
        Chosen show = null;
        Chosen remove = null;
        batchClipper.begin(batchList.size(), step);
        while (batchClipper.step()) {
            for (var index = batchClipper.getDisplayStart(); index < batchClipper.getDisplayEnd(); index++) {
                var item = batchList.get(index);
                var npc = item.entry().npc();
                var x = ImGui.getCursorScreenPosX();
                var y = ImGui.getCursorScreenPosY();
                var rowWidth = ImGui.getContentRegionAvailX();
                ImGui.pushID(item.key());
                ImGui.setNextItemAllowOverlap();
                if (ImGui.selectable("##batch-row", item.key().equals(current), 0, rowWidth, rowHeight)) {
                    show = item;
                }
                var outcome = outcome(item.key());
                if (ImGui.isItemHovered() && outcome != null && !outcome.isWritten()) {
                    HudWidgets.showTooltip(outcome.failure());
                }
                var character = item.look() != null;
                var title = character
                        ? item.title()
                        : npc.title().isBlank() ? npc.label() : npc.label() + " · " + npc.title();
                SpawnPanelWidgets.listRow(
                        ImGui.getWindowDrawList(),
                        x,
                        y,
                        rowWidth - 30,
                        character ? SpawnStyle.UNKNOWN.argb : spawnStyleOf(npc).argb,
                        title,
                        character ? item.detail() : detail(npc),
                        rowValue(item, outcome),
                        "",
                        true);
                ImGui.setCursorScreenPos(x + rowWidth - 26, y + (rowHeight - 22) * .5f);
                if (HudWidgets.button("npc-batch-remove", Glyph.CLOSE, "", 22, 22, false, false)) {
                    remove = item;
                }
                GeodataWidgets.tip("Take it out of the batch.");
                ImGui.setCursorScreenPos(x, y + rowHeight);
                ImGui.dummy(0, 0);
                ImGui.popID();
            }
        }
        batchClipper.end();
        ImGui.endChild();
        if (remove != null) {
            batch.remove(remove.key());
            batchChanged();
        } else if (show != null) {
            show(show);
        }
    }

    private String rowValue(Chosen item, @Nullable Outcome outcome) {
        if (outcome != null) {
            return outcome.isWritten() ? formatMegabytes(outcome.bytes()) : "Failed";
        }
        var at = runIndex.get(item.key());
        if (batchRunning != null && at != null) {
            return at == runOutcomes.size() ? "Exporting…" : "Waiting";
        }
        var id = item.entry().npc().id();
        return id > 0 ? Integer.toString(id) : "Character";
    }

    private @Nullable Outcome outcome(String key) {
        var at = runIndex.get(key);
        return at == null || at >= runOutcomes.size() ? null : runOutcomes.get(at);
    }

    private void show(Chosen item) {
        if (item.look() == null) {
            characterMode = false;
            selected = item.entry().npc();
            scrollToSelected = true;
            return;
        }
        characterMode = true;
        characterForm.show(item.look());
    }

    private void tick(Npc npc, boolean range) {
        var on = !batch.containsKey(npcKey(npc));
        var from = range && batchAnchor != null ? shown.indexOf(batchAnchor) : -1;
        var to = shown.indexOf(npc);
        if (from >= 0 && to >= 0) {
            for (var index = Math.min(from, to); index <= Math.max(from, to); index++) {
                put(shown.get(index), on);
            }
        } else {
            put(npc, on);
        }
        batchAnchor = npc;
        batchChanged();
    }

    private void put(Npc npc, boolean on) {
        var key = npcKey(npc);
        if (on) {
            batch.putIfAbsent(key, Chosen.of(npc));
        } else {
            batch.remove(key);
        }
    }

    private void addCharacter() {
        var current = characterForm.getCharacter();
        var look = characterForm.getLook();
        if (current == null || look == null) {
            return;
        }
        var items = characterForm.getItemNames();
        var label = items.isEmpty() ? current.label() : current.label() + ", " + items.getFirst();
        var detail = (items.isEmpty() ? "Bare body" : String.join(" · ", items))
                + " · hair "
                + (characterForm.getHairStyle() + 1);
        var key = characterForm.getKey();
        batch.putIfAbsent(
                key,
                new Chosen(
                        key,
                        Entry.of(new Character(label, current.face(), current.outfit())),
                        current.label(),
                        detail,
                        look));
        batchChanged();
    }

    private void batchChanged() {
        batchVersion++;
        batchList = List.copyOf(batch.values());
        clashKey = "";
    }

    private void drawPanel() {
        drawResult();
        drawPreview();
        if (characterMode) {
            if (characterForm.getData() == null) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.hint("Reading the client's player races…");
            }
        } else {
            drawNpcDetails();
        }
        drawScale();
        drawSaveTo();
    }

    private void drawResult() {
        if (!isBatchShown() || batchResult == null) {
            return;
        }
        var summary = batchResult;
        GeodataWidgets.section("RESULT", 0);
        GeodataWidgets.value("Written", summary.written().size() + " of " + summary.total());
        GeodataWidgets.value("Size", formatMegabytes(summary.bytes()));
        GeodataWidgets.value("Time", formatSeconds(summary.millis()));
        var failed = summary.failed();
        if (!failed.isEmpty()) {
            GeodataWidgets.section("NOT EXPORTED");
            var listed = Math.min(failed.size(), 12);
            for (var index = 0; index < listed; index++) {
                var outcome = failed.get(index);
                if (index > 0) {
                    GeodataWidgets.space(GeodataWidgets.ROW);
                }
                GeodataWidgets.note(outcome.entry().npc().label() + ": " + outcome.failure(), GeodataWidgets.BLOCKED);
            }
            if (failed.size() > listed) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.hint((failed.size() - listed) + " more are marked Failed in the batch.");
            }
        }
        GeodataWidgets.space(GeodataWidgets.SECTION);
    }

    private void drawScale() {
        GeodataWidgets.section("SCALE");
        ImGui.beginDisabled(isBusy());
        scale.draw("npc-export-scale");
        ImGui.endDisabled();
    }

    private void drawNpcDetails() {
        GeodataWidgets.section("NPC");
        if (selected == null) {
            GeodataWidgets.hint("Pick an NPC in the list.");
        } else {
            GeodataWidgets.value("Name", selected.label());
            if (!selected.title().isBlank()) {
                GeodataWidgets.value("Title", selected.title());
            }
            GeodataWidgets.value("Id", Integer.toString(selected.id()));
            GeodataWidgets.value("Mesh", selected.mesh());
            if (selected.drawScale() > 0 && selected.drawScale() != 1f) {
                GeodataWidgets.value("Scale", String.format(Locale.ROOT, "%.2f", selected.drawScale()));
            }
            if (selected.playerLike()) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.hint(
                        kinds.isEmpty()
                                ? "Built from a player race's parts. Without the server's npcdata.txt it stands in the"
                                        + " race's bare body."
                                : "Built from a player race's parts, in the armour and weapons of the server's"
                                        + " npcdata.txt.");
            }
        }
    }

    private void drawSaveTo() {
        var target = target();
        GeodataWidgets.section("SAVE TO");
        ImGui.beginDisabled(isBusy());
        if (GeodataWidgets.pathField(
                "npc-export-folder", "Folder", folder, "A folder named after the NPC is made here.")) {
            folderPicker.openPath(folder.get());
        }
        refreshExisting();
        if (!existing.isEmpty() && target != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var justWritten =
                    written != null && parent() != null && written.equals(parent().resolve(NpcExport.name(target)));
            if (!justWritten) {
                GeodataWidgets.note(
                        "An earlier export of " + target.label() + " is in this folder.", GeodataWidgets.WARNING);
            }
            ImGui.checkbox("Replace it", replace);
            GeodataWidgets.tip("Export again over the files in " + NpcExport.name(target) + ".");
        }
        if (!batch.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var picked = GeodataWidgets.segmented(
                    "npc-batch-files", new String[] {"One scene", "Separate files"}, oneScene ? 0 : 1);
            if (picked >= 0) {
                oneScene = picked == 0;
            }
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.hint(
                    oneScene
                            ? "The batch goes into one glTF file, " + NpcBatchExport.SCENE
                                    + ", its models in a row a metre apart."
                            : "Each model of the batch goes into a folder of its own.");
        }
        refreshClashes();
        if (clashes > 0) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (!isBatchShown()) {
                GeodataWidgets.note(
                        oneScene
                                ? clashExample
                                        + " is in this folder from an earlier export. The scene is written beside it,"
                                        + " as "
                                        + clashExample + "_2."
                                : clashes == 1
                                        ? clashExample
                                                + " of the batch is in this folder from an earlier export. It is"
                                                + " written beside it, as "
                                                + clashExample + "_2."
                                        : clashes
                                                + " models of the batch are in this folder from an earlier export."
                                                + " They are written beside them with a number, such as "
                                                + clashExample + "_2.",
                        GeodataWidgets.WARNING);
            }
            ImGui.checkbox("Replace earlier exports", replaceBatch);
            GeodataWidgets.tip(
                    oneScene
                            ? "Export the scene again over the earlier " + NpcBatchExport.SCENE
                                    + ", instead of beside it."
                            : "Export the batch again over the earlier files of its models, instead of beside them.");
        }
        ImGui.endDisabled();

        GeodataWidgets.section("WHAT IS EXPORTED");
        GeodataWidgets.hint(
                !batch.isEmpty() && oneScene
                        ? "One glTF file with every model of the batch, each with its mesh, textures, skeleton and"
                                + " every animation of the client. Its animations are named after it, such as"
                                + " Roxxy_30006|wait. In Blender each model is an armature with its own actions."
                        : "One glTF file with the mesh, textures, skeleton and every animation of the client. In"
                                + " Blender each animation is an action of the armature.");
    }

    private void drawCharacter() {
        if (models != null) {
            characterForm.read(models, installation.root(), variant, previewWorker);
        }
        characterForm.draw(isBusy());
    }

    private @Nullable Npc target() {
        if (characterMode) {
            var current = characterForm.getCharacter();
            return current == null ? null : current.npc();
        }
        return selected;
    }

    private void drawPreview() {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = width * previewHeight() / PREVIEW_WIDTH;
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, 8f);
        updatePreview();
        var texture = previewTextures.getOrDefault(step(), previewTextures.getOrDefault(shownStep, 0L));
        if (texture != 0) {
            draw.addImage(texture, x, y, x + width, y + height);
        } else {
            var target = target();
            var text = target == null
                    ? characterMode
                            ? characterForm.getData() == null ? "" : "No player race to show."
                            : "Pick an NPC to see it."
                    : previewProblem.isEmpty() ? "Drawing " + target.label() + "…" : previewProblem;
            var textWidth = ImGui.calcTextSizeX(text);
            draw.addText(
                    x + Math.max(8, (width - textWidth) * .5f),
                    y + height * .5f - ImGui.getFontSize() * .5f,
                    GeodataWidgets.FAINT,
                    text);
        }
        ImGui.invisibleButton("##npc-preview", width, height);
        if (previewModel == null) {
            return;
        }
        if (ImGui.isItemActive() && ImGui.isMouseDragging(ImGuiMouseButton.Left, 0)) {
            turn += ImGui.getIO().getMouseDeltaX() * TURN_PER_PIXEL;
        }
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
            turn = 0;
        }
        if (ImGui.isItemHovered() || ImGui.isItemActive()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
            var hint = "Drag to turn · double-click for the front";
            draw.addText(
                    x + (width - ImGui.calcTextSizeX(hint)) * .5f,
                    y + height - ImGui.getFontSize() - 8,
                    GeodataWidgets.MUTED,
                    hint);
        }
    }

    private int previewHeight() {
        return characterMode ? CHARACTER_PREVIEW_HEIGHT : PREVIEW_HEIGHT;
    }

    private int step() {
        return Math.floorMod(Math.round(turn / TURN_STEP), 360 / TURN_STEP);
    }

    private void updatePreview() {
        var current = characterMode ? characterForm.getCharacter() : null;
        var id = selected == null ? -1 : selected.id();
        var key = characterMode ? characterForm.getKey() : id < 0 ? "" : "npc:" + id;
        if (!key.equals(previewKey)) {
            dropPreview();
            previewKey = key;
            var source = models;
            if (!key.isEmpty() && source != null && renderer != null) {
                modelLoading = CompletableFuture.supplyAsync(
                        () -> current != null
                                ? source.buildCharacter(current.face(), current.outfit())
                                : source.readDrawn(id),
                        previewWorker);
            }
        }
        if (modelLoading != null && modelLoading.isDone()) {
            try {
                previewModel = modelLoading.join();
            } catch (CompletionException | CancellationException failure) {
                previewModel = null;
            }
            modelLoading = null;
            if (previewModel == null) {
                previewProblem = "The client has no model to show for it.";
            }
        }
        if (previewing != null && previewing.isDone()) {
            byte[] pixels;
            try {
                pixels = previewing.join();
            } catch (CompletionException | CancellationException failure) {
                pixels = null;
            }
            previewing = null;
            if (pixels != null && renderer != null) {
                var buffer = MemoryUtil.memAlloc(pixels.length);
                try {
                    buffer.put(pixels).flip();
                    previewTextures.put(
                            previewingStep,
                            renderer.createTexture("ui/npc-preview", PREVIEW_WIDTH, previewHeight(), buffer));
                    shownStep = previewingStep;
                } finally {
                    MemoryUtil.memFree(buffer);
                }
            }
        }
        var step = step();
        if (previewTextures.containsKey(step)) {
            shownStep = step;
        } else if (previewModel != null && previewing == null) {
            var model = previewModel;
            var height = previewHeight();
            var azimuth = NpcPreviewImage.AZIMUTH - Math.toRadians(step * TURN_STEP);
            previewingStep = step;
            previewing = CompletableFuture.supplyAsync(
                    () -> NpcPreviewImage.render(model, PREVIEW_WIDTH, height, azimuth), previewWorker);
        }
    }

    private void dropPreview() {
        if (renderer != null) {
            previewTextures.values().forEach(renderer::destroyTexture);
        }
        previewTextures.clear();
        previewKey = "";
        previewProblem = "";
        previewModel = null;
        turn = 0;
        shownStep = 0;
        if (modelLoading != null) {
            modelLoading.cancel(false);
            modelLoading = null;
        }
        if (previewing != null) {
            previewing.cancel(false);
            previewing = null;
        }
    }

    private void readScript(String scriptFolder, SpawnModels target) {
        if (scriptFolder.isBlank()) {
            return;
        }
        previewWorker.submit(() -> {
            try {
                var table = target.readScript(Path.of(scriptFolder.trim()));
                if (table == null) {
                    return;
                }
                var byId = new HashMap<Integer, SpawnStyle>();
                for (var npc : table.getNpcs()) {
                    byId.putIfAbsent(npc.id(), SpawnStyle.of(npc));
                }
                kinds = Map.copyOf(byId);
            } catch (Exception unreadable) {
            }
        });
    }

    private void drawFooter() {
        if (running != null) {
            if (GeodataWidgets.busyBar(
                    "npc-export-running",
                    "Exporting " + exporting + " · " + (stopping ? "Stopping…" : stage),
                    fraction,
                    "Stop",
                    !stopping)) {
                stopping = true;
            }
            return;
        }
        if (batchRunning != null) {
            if (GeodataWidgets.busyBar(
                    "npc-batch-running",
                    "Exporting " + stage + (stopping ? " · Stopping after this model…" : ""),
                    fraction,
                    "Cancel",
                    !stopping)) {
                stopping = true;
            }
            return;
        }
        if (isBatchShown()) {
            drawBatchFooter();
            return;
        }
        var shown = !outcome.isEmpty() && outcomeKey.equals(choicesKey()) && !replace.get();
        var missing = missing();
        var batchMissing = parent() == null ? "Choose the folder to save to." : "";
        var status = shown
                ? outcome
                : !batch.isEmpty()
                        ? !batchMissing.isEmpty()
                                ? batchMissing
                                : count(batch.size(), "model")
                                        + " in the batch · "
                                        + variant.getLabel()
                                        + " world · "
                                        + scale.getUnitText()
                        : !missing.isEmpty()
                                ? missing
                                : (characterMode && characterForm.getData() != null
                                                ? characterForm
                                                                .getData()
                                                                .races()
                                                                .size()
                                                        + " player races in this client"
                                                : npcs.size() + " NPCs in this client")
                                        + " · "
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
                        "npc-export-blender",
                        Glyph.PLAY,
                        "Open in Blender",
                        156,
                        true,
                        true,
                        "Starts Blender with the export as a scene, playing the idle animation, a sun and a camera."));
            }
            buttons.add(new FooterButton("show-npc-export", Glyph.FOLDER, "Show folder", 132, blender == null));
        } else {
            buttons.add(singleButton(batch.isEmpty(), missing));
        }
        if (!batch.isEmpty()) {
            var label = "Export " + count(batch.size(), "model");
            buttons.add(new FooterButton(
                    "run-npc-batch",
                    Glyph.CARD,
                    label,
                    Math.min(320, Math.max(120, ImGui.calcTextSizeX(label) + 56)),
                    !done,
                    batchMissing.isEmpty(),
                    !batchMissing.isEmpty()
                            ? batchMissing
                            : oneScene
                                    ? "Writes the models of the batch into one scene, one after the other."
                                    : "Writes the models of the batch one after the other, each into a folder"
                                            + " of its own."));
        }
        buttons.add(new FooterButton("done-npc-export", null, "Done", 96, false));
        var pressed = GeodataWidgets.footer(status, color, buttons);
        if (pressed < 0) {
            return;
        }
        switch (buttons.get(pressed).id()) {
            case "npc-export-blender" -> {
                try {
                    BlenderApp.open(blender, written);
                    outcome = "Blender is opening " + written.getFileName() + ". A large export takes a while.";
                } catch (IOException failure) {
                    outcome = "Blender did not start: " + failure.getMessage();
                }
            }
            case "show-npc-export" -> FileManager.tryOpenFolder(written);
            case "done-npc-export" -> ImGui.closeCurrentPopup();
            case "run-npc-batch" -> startBatch();
            default -> start();
        }
    }

    private FooterButton singleButton(boolean primary, String missing) {
        var target = target();
        var label = target == null ? "Export" : "Export " + target.label();
        return new FooterButton(
                "run-npc-export",
                Glyph.CARD,
                label,
                Math.min(320, Math.max(120, ImGui.calcTextSizeX(label) + 56)),
                primary,
                missing.isEmpty(),
                missing);
    }

    private void drawBatchFooter() {
        var summary = batchResult;
        var writtenCount = summary.written().size();
        var scene = batchScene && writtenCount > 0
                ? summary.written().getFirst().result().folder()
                : null;
        var blender = scene != null && BlenderApp.canOpen(scene) ? BlenderApp.find() : null;
        var failedCount = summary.failed().size();
        var failures = failedCount == 0 ? "" : " · " + failedCount + " failed, listed in the panel";
        String status;
        int color;
        if (!batchProblem.isEmpty()) {
            status = "The batch stopped: " + batchProblem;
            color = GeodataWidgets.BLOCKED;
        } else if (summary.cancelled()) {
            status = "Stopped with "
                    + writtenCount
                    + " of "
                    + summary.total()
                    + " written"
                    + failures
                    + ". The files written stay.";
            color = GeodataWidgets.WARNING;
        } else if (writtenCount == 0) {
            status = "None of the " + count(summary.total(), "model") + " could be exported. The panel lists why.";
            color = GeodataWidgets.BLOCKED;
        } else if (failedCount > 0) {
            status = "Exported " + writtenCount + " of " + summary.total() + failures;
            color = GeodataWidgets.WARNING;
        } else {
            status = "Exported "
                    + count(writtenCount, "model")
                    + (scene == null ? "" : " into " + scene.getFileName())
                    + " · "
                    + formatMegabytes(summary.bytes());
            color = GeodataWidgets.OPEN;
        }
        if (!batchNote.isEmpty()) {
            status = batchNote;
        }
        var buttons = new ArrayList<FooterButton>();
        if (target() != null) {
            buttons.add(singleButton(false, missing()));
        }
        if (writtenCount == 0) {
            var label = "Export " + count(batch.size(), "model");
            buttons.add(new FooterButton(
                    "run-npc-batch",
                    Glyph.CARD,
                    label,
                    Math.min(320, Math.max(120, ImGui.calcTextSizeX(label) + 56)),
                    true,
                    !batch.isEmpty() && parent() != null,
                    "Tries the models of the batch again."));
        } else if (scene != null) {
            if (blender != null) {
                buttons.add(new FooterButton(
                        "npc-scene-blender",
                        Glyph.PLAY,
                        "Open in Blender",
                        156,
                        true,
                        true,
                        "Starts Blender with the scene, each model playing its idle animation, a sun and a camera."));
            }
            buttons.add(new FooterButton(
                    "show-npc-scene", Glyph.FOLDER, "Show folder", 132, blender == null, true, "Opens " + scene + "."));
        } else if (batchFolder != null) {
            buttons.add(new FooterButton(
                    "show-npc-batch",
                    Glyph.FOLDER,
                    "Show folder",
                    132,
                    true,
                    true,
                    "Opens " + batchFolder + ", which has a folder for each model."));
        }
        buttons.add(new FooterButton("done-npc-export", null, "Done", 96, false));
        var pressed = GeodataWidgets.footer(status, color, buttons);
        if (pressed < 0) {
            return;
        }
        switch (buttons.get(pressed).id()) {
            case "show-npc-batch" -> FileManager.tryOpenFolder(batchFolder);
            case "show-npc-scene" -> FileManager.tryOpenFolder(scene);
            case "npc-scene-blender" -> {
                try {
                    BlenderApp.open(blender, scene);
                    batchNote = "Blender is opening " + scene.getFileName() + ". A large scene takes a while.";
                } catch (IOException failure) {
                    batchNote = "Blender did not start: " + failure.getMessage();
                }
            }
            case "run-npc-batch" -> startBatch();
            case "done-npc-export" -> ImGui.closeCurrentPopup();
            default -> start();
        }
    }

    private boolean isBatchShown() {
        return batchResult != null && batchRunning == null && batchOutcomeKey.equals(batchKey()) && !replaceBatch.get();
    }

    private String batchKey() {
        return batchVersion + "|" + folder.get().trim() + "|" + scale.getScale() + "|" + oneScene;
    }

    private void startBatch() {
        var target = parent();
        if (target == null || batch.isEmpty()) {
            return;
        }
        var root = installation.root();
        var world = variant;
        var entries = new ArrayList<Entry>(batchList.size());
        runIndex.clear();
        for (var item : batchList) {
            runIndex.put(item.key(), entries.size());
            entries.add(item.entry());
        }
        runOutcomes.clear();
        var overwrite = replaceBatch.get();
        var size = scale.getScale();
        scale.commit();
        rememberFolder(target);
        var source = models;
        var dresser = previewWorker;
        Function<Npc, @Nullable Outfit> dress = npc -> source == null
                ? null
                : CompletableFuture.supplyAsync(() -> source.getDress(npc.id()), dresser)
                        .join();
        stage = "Starting";
        fraction = 0;
        stopping = false;
        outcome = "";
        written = null;
        batchResult = null;
        batchProblem = "";
        batchOutcomeKey = batchKey();
        batchFolder = target;
        batchScene = oneScene;
        batchNote = "";
        Progress progress = (text, done) -> {
            stage = text;
            fraction = done;
        };
        batchRunning = CompletableFuture.supplyAsync(
                () -> batchScene
                        ? NpcBatchExport.runScene(
                                entries,
                                target,
                                overwrite,
                                NpcBatchExport.createSceneWriter(root, world, size, dress),
                                progress,
                                runOutcomes::add,
                                () -> stopping)
                        : NpcBatchExport.run(
                                entries,
                                target,
                                overwrite,
                                NpcBatchExport.createWriter(root, world, size, dress),
                                progress,
                                runOutcomes::add,
                                () -> stopping),
                worker);
    }

    private void refreshClashes() {
        var key = batchVersion + "|" + folder.get() + "|" + oneScene;
        if (key.equals(clashKey)) {
            return;
        }
        clashKey = key;
        clashes = 0;
        clashExample = "";
        var parent = parent();
        if (parent == null) {
            return;
        }
        if (oneScene) {
            if (Files.exists(parent.resolve(NpcBatchExport.SCENE))) {
                clashes = 1;
                clashExample = NpcBatchExport.SCENE;
            }
            return;
        }
        for (var item : batchList) {
            var name = NpcExport.name(item.entry().npc());
            if (Files.exists(parent.resolve(name)) && clashes++ == 0) {
                clashExample = name;
            }
        }
    }

    private String missing() {
        if (characterMode && characterForm.getData() == null) {
            return "Reading the client's player races…";
        }
        if (target() == null) {
            return characterMode ? "No player race to export." : "Pick an NPC in the list.";
        }
        if (parent() == null) {
            return "Choose the folder to save to.";
        }
        if (!existing.isEmpty() && !replace.get()) {
            return "Choose Replace it to export over the earlier files.";
        }
        return "";
    }

    private String choicesKey() {
        var what = characterMode ? characterForm.getKey() : selected == null ? "" : Integer.toString(selected.id());
        return what + "|" + folder.get().trim() + "|" + scale.getScale();
    }

    private void start() {
        var root = installation.root();
        var world = variant;
        var character = characterMode ? characterForm.getCharacter() : null;
        var npc = character != null ? character.npc() : selected;
        var target = parent();
        var overwrite = replace.get();
        var size = scale.getScale();
        scale.commit();
        if (target != null) {
            rememberFolder(target);
        }
        var source = models;
        stage = "Starting";
        fraction = 0;
        stopping = false;
        outcome = "";
        batchResult = null;
        outcomeKey = choicesKey();
        exporting = npc.label();
        written = null;
        var dressing = character != null || !npc.playerLike() || source == null
                ? CompletableFuture.completedFuture(character != null ? character.outfit() : null)
                : CompletableFuture.supplyAsync(
                        () -> {
                            source.readPosed(npc.id());
                            return source.getOutfit(npc.id());
                        },
                        previewWorker);
        running = dressing.thenApplyAsync(
                outfit -> {
                    try {
                        return NpcExport.export(
                                root,
                                world,
                                npc,
                                outfit,
                                size,
                                target,
                                overwrite,
                                (text, done) -> {
                                    stage = text;
                                    fraction = done;
                                },
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
        if (loading != null && loading.isDone()) {
            try {
                npcs = loading.join();
            } catch (CompletionException failure) {
                npcs = List.of();
                problem = "The client's NPC tables cannot be read: " + failureMessage(failure);
            }
            loading = null;
            filterKey = "";
        }
        if (loading == null && pendingNpc >= 0) {
            for (var npc : npcs) {
                if (npc.id() == pendingNpc) {
                    selected = npc;
                    scrollToSelected = true;
                }
            }
            pendingNpc = -1;
        }
        if (batchRunning != null && batchRunning.isDone()) {
            try {
                batchResult = batchRunning.join();
            } catch (CancellationException | CompletionException failure) {
                batchResult = new Summary(List.copyOf(runOutcomes), runIndex.size(), true, 0);
                batchProblem = failureMessage(failure);
            }
            batchRunning = null;
            replaceBatch.set(false);
            clashKey = "";
            existingKey = "";
        }
        if (running == null || !running.isDone()) {
            return;
        }
        try {
            var result = running.join();
            failed = false;
            written = result.folder();
            outcome = "Exported "
                    + result.folder().getFileName()
                    + ": "
                    + count(result.bones(), "bone")
                    + ", "
                    + count(result.animations().size(), "animation")
                    + ", "
                    + count(result.triangles(), "triangle")
                    + ".";
        } catch (CancellationException | CompletionException failure) {
            failed = true;
            written = null;
            outcome = isCancellation(failure) ? "Stopped. Nothing was written." : failureMessage(failure);
        }
        running = null;
        replace.set(false);
        existingKey = "";
    }

    private void filter() {
        var query = search.get().trim().toLowerCase(Locale.ROOT);
        if (query.equals(filterKey) && filtered == npcs) {
            return;
        }
        filterKey = query;
        filtered = npcs;
        if (query.isEmpty()) {
            shown = npcs;
        } else {
            var result = new ArrayList<Npc>();
            for (var npc : npcs) {
                if (npc.label().toLowerCase(Locale.ROOT).contains(query)
                        || npc.title().toLowerCase(Locale.ROOT).contains(query)
                        || Integer.toString(npc.id()).equals(query)
                        || npc.mesh().toLowerCase(Locale.ROOT).contains(query)) {
                    result.add(npc);
                }
            }
            shown = List.copyOf(result);
        }
        scrollToSelected = true;
    }

    private void refreshExisting() {
        var parent = parent();
        var target = target();
        var key = (target == null ? "" : NpcExport.name(target)) + "|" + folder.get();
        if (key.equals(existingKey)) {
            return;
        }
        existingKey = key;
        existing = target == null || parent == null ? List.of() : NpcExport.findExistingFiles(parent, target);
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
        return running != null || batchRunning != null;
    }

    private SpawnStyle spawnStyleOf(Npc npc) {
        var kind = kinds.get(npc.id());
        if (kind != null) {
            return kind;
        }
        if (npc.playerLike()) {
            return SpawnStyle.UNKNOWN;
        }
        return npc.mesh().toLowerCase(Locale.ROOT).contains("monster") ? SpawnStyle.MONSTER : SpawnStyle.CITIZEN;
    }

    private void rememberFolder(Path chosen) {
        settings.setFolder(chosen);
        offeredFolder = settings.getFolder().toString();
    }

    private static String npcKey(Npc npc) {
        return "npc:" + npc.id();
    }

    private static String detail(Npc npc) {
        return npc.playerLike() ? "Built from player parts · " + npc.mesh() : npc.mesh();
    }

    private static String formatMegabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static String formatSeconds(long millis) {
        var seconds = Math.round(millis / 1000.0);
        return millis < 10_000
                ? String.format(Locale.ROOT, "%.1f s", millis / 1000.0)
                : seconds < 60 ? seconds + " s" : seconds / 60 + " min " + seconds % 60 + " s";
    }

    private static String count(long value, String noun) {
        return String.format(Locale.ROOT, "%,d %s%s", value, noun, value == 1 ? "" : "s");
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

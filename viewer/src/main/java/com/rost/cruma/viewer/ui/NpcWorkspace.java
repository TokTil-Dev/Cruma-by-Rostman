package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.DropList;
import com.rost.cruma.core.npc.DropList.Expected;
import com.rost.cruma.core.npc.DropList.Group;
import com.rost.cruma.core.npc.DropList.Item;
import com.rost.cruma.core.npc.NpcAiCatalog;
import com.rost.cruma.core.npc.NpcAiCatalog.Parameter;
import com.rost.cruma.core.npc.NpcAiCatalog.ValueKind;
import com.rost.cruma.core.npc.NpcBatchEdits;
import com.rost.cruma.core.npc.NpcDataDocument;
import com.rost.cruma.core.npc.NpcDataDocument.Batch;
import com.rost.cruma.core.npc.NpcDataDocument.State;
import com.rost.cruma.core.npc.NpcDataFile;
import com.rost.cruma.core.npc.NpcDataIndex;
import com.rost.cruma.core.npc.NpcDataReview;
import com.rost.cruma.core.npc.NpcDataReview.Change;
import com.rost.cruma.core.npc.NpcDataReview.Issue;
import com.rost.cruma.core.npc.NpcDataReview.Section;
import com.rost.cruma.core.npc.NpcExperience;
import com.rost.cruma.core.npc.NpcKind;
import com.rost.cruma.core.npc.NpcPch;
import com.rost.cruma.core.npc.NpcRecord;
import com.rost.cruma.core.npc.NpcStatReference;
import com.rost.cruma.core.npc.NpcStatReference.Band;
import com.rost.cruma.core.npc.NpcValues;
import com.rost.cruma.core.npc.NpcValues.Ai;
import com.rost.cruma.core.npc.NpcValues.AiParameter;
import com.rost.cruma.core.npc.ScriptValue;
import com.rost.cruma.core.npc.ServerItems;
import com.rost.cruma.core.npc.ServerSkills;
import com.rost.cruma.core.spawn.SpawnText;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.WindowContext.Keyboard;
import com.rost.cruma.viewer.application.WindowContext.Keyboard.Key;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.npc.NpcDataSource;
import com.rost.cruma.viewer.npc.NpcDataSource.Phase;
import com.rost.cruma.viewer.npc.NpcIcons;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import com.rost.cruma.viewer.ui.GeodataEditingPanel.Leave;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.NpcPanelWidgets.ItemKind;
import com.rost.cruma.viewer.ui.NpcSpawnLink.HeldItems;
import com.rost.cruma.viewer.ui.NpcSpawnLink.Nearest;
import imgui.ImColor;
import imgui.ImFont;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.LongConsumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class NpcWorkspace implements AutoCloseable {
    private static final Map<String, List<String>> CHOICES = Map.of("sex", List.of("male", "female", "etc"));

    private static final List<StatGroup> STATS = List.of(
            new StatGroup(
                    "BASICS",
                    List.of(
                            new StatField("level", "Level", FieldKind.NUMBER, "level"),
                            new StatField("race", "Race", FieldKind.TEXT, "race: human, elf, undead, animal…"),
                            new StatField("sex", "Sex", FieldKind.CHOICE, "sex: male, female or etc (neither)"))),
            new StatGroup(
                    "REWARDS",
                    List.of(
                            new StatField(
                                            "exp",
                                            "Exp",
                                            FieldKind.FIXED,
                                            "exp: the NPC's own experience, what a player has at the start of its"
                                                + " level. It gives nothing; it follows the level, so it changes with"
                                                + " Level. The server's source sets it from the experience table by"
                                                + " level while NpcExpReset is on, as it is by default."),
                                    new StatField(
                                            "acquire_exp_rate",
                                            "Exp rate",
                                            FieldKind.NUMBER,
                                            "acquire_exp_rate: what a kill gives. Experience = level² × this rate,"
                                                    + " then shared by damage and party, with level differences and the"
                                                    + " server's rates."),
                            new StatField(
                                            "acquire_sp",
                                            "SP",
                                            FieldKind.NUMBER,
                                            "acquire_sp: the skill points a kill gives. Chronicle 4 and Gracia Final"
                                                    + " files write them as they are; 162 files write a rate the server"
                                                    + " multiplies by level². Shared like experience."),
                                    new StatField("acquire_rp", "Reputation", FieldKind.NUMBER, "acquire_rp"))),
            new StatGroup(
                    "COMBAT",
                    List.of(
                            new StatField("org_hp", "HP", FieldKind.NUMBER, "org_hp"),
                            new StatField("org_mp", "MP", FieldKind.NUMBER, "org_mp"),
                            new StatField("base_physical_attack", "P. Atk.", FieldKind.NUMBER, "base_physical_attack"),
                            new StatField("base_magic_attack", "M. Atk.", FieldKind.NUMBER, "base_magic_attack"),
                            new StatField("base_defend", "P. Def.", FieldKind.NUMBER, "base_defend"),
                            new StatField("base_magic_defend", "M. Def.", FieldKind.NUMBER, "base_magic_defend"),
                            new StatField("base_attack_speed", "Attack speed", FieldKind.NUMBER, "base_attack_speed"),
                            new StatField("base_critical", "Critical", FieldKind.NUMBER, "base_critical"),
                            new StatField("physical_hit_modify", "Accuracy", FieldKind.NUMBER, "physical_hit_modify"),
                            new StatField(
                                    "physical_avoid_modify", "Evasion", FieldKind.NUMBER, "physical_avoid_modify"),
                            new StatField("base_attack_range", "Attack range", FieldKind.NUMBER, "base_attack_range"),
                            new StatField("agro_range", "Aggro range", FieldKind.NUMBER, "agro_range"),
                            new StatField("org_hp_regen", "HP regen", FieldKind.NUMBER, "org_hp_regen"),
                            new StatField("org_mp_regen", "MP regen", FieldKind.NUMBER, "org_mp_regen"),
                            new StatField(
                                    "hp_increase",
                                    "HP increase",
                                    FieldKind.NUMBER,
                                    "hp_increase, in Chronicle 4 files"),
                            new StatField(
                                    "mp_increase",
                                    "MP increase",
                                    FieldKind.NUMBER,
                                    "mp_increase, in Chronicle 4 files"))),
            new StatGroup(
                    "EQUIPMENT",
                    List.of(
                            new StatField(
                                    "slot_rhand", "Right hand", FieldKind.ITEM, "slot_rhand: the weapon the NPC holds"),
                            new StatField(
                                    "slot_lhand",
                                    "Left hand",
                                    FieldKind.ITEM,
                                    "slot_lhand: a shield or a second weapon"),
                            new StatField("slot_chest", "Chest", FieldKind.ITEM, "slot_chest"))),
            new StatGroup(
                    "SHAPE",
                    List.of(
                            new StatField(
                                    "collision_radius",
                                    "Collision radius",
                                    FieldKind.TEXT,
                                    "collision_radius={normal;alternative}"),
                            new StatField(
                                    "collision_height",
                                    "Collision height",
                                    FieldKind.TEXT,
                                    "collision_height={normal;alternative}"))),
            new StatGroup(
                    "BEHAVIOUR",
                    List.of(
                            new StatField("can_be_attacked", "Can be attacked", FieldKind.FLAG, "can_be_attacked"),
                            new StatField("targetable", "Can be targeted", FieldKind.FLAG, "targetable"),
                            new StatField("can_move", "Can move", FieldKind.FLAG, "can_move"),
                            new StatField("flying", "Flies", FieldKind.FLAG, "flying"),
                            new StatField("undying", "Cannot die", FieldKind.FLAG, "undying"),
                            new StatField("show_name_tag", "Shows its name", FieldKind.FLAG, "show_name_tag"))));

    private static final String[] KINDS = {"All", "Monsters", "Bosses", "Townsfolk"};
    private static final String[] ITEM_FILTERS = {"All", "Weapons", "Armour", "Jewels", "Other"};
    private static final String[] SKILL_FILTERS = {"Passive", "Active", "All"};
    private static final float FOOTER = 46;
    private static final long EDIT_MERGE_NANOS = 1_500_000_000L;
    private static final int LISTED_ISSUES = 8;
    private static final String UNDO_KEY =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "Cmd+Z" : "Ctrl+Z";

    private static final String[] SORTS = {"File order", "By level", "By name"};
    private static final String[] SPAWNED = {"Any", "Spawned", "Never spawned"};
    private static final int RECENT = 8;

    private static final String NONE = "–";

    private final NpcDataSource source = new NpcDataSource();
    private final NpcIcons icons;
    private final ImGuiListClipper clipper = new ImGuiListClipper();
    private final ImString search = new ImString(128);
    private final ImString pickerSearch = new ImString(128);
    private final ImString allFilter = new ImString(64);
    private final Map<String, ImString> buffers = new HashMap<>();
    private @Nullable String activeField;
    private @Nullable Path requestedFolder;
    private int selected = -1;
    private Tab tab = Tab.DROP;
    private int kind;
    private String expanded = "";
    private @Nullable Picker picker;
    private boolean pickerFocus;
    private List<Object> pickerResults = List.of();
    private String pickerQuery = "";
    private int pickerFilter = -1;

    private boolean npcSkillsOnly = true;

    private Map<String, Integer> skillUse = Map.of();

    private @Nullable Object skillUseState;
    private @Nullable Review review;
    private boolean backupOnSave = true;
    private @Nullable Leave pendingLeave;
    private @Nullable Leave approvedLeave;
    private String lastEditKey = "";
    private long lastEditTime;

    private long lastEditRevision = -1;

    private String listedQuery = "";
    private int listedKind = -1;
    private long listedGeneration = -1;
    private List<Integer> listed = List.of();
    private @Nullable String spawnRequest;
    private int exportRequest = -1;
    private @Nullable String pendingSelect;
    private @Nullable NpcDataDocument checkedDocument;
    private @Nullable NpcDataFile experienceFile;
    private NpcExperience experience = NpcExperience.of(NpcDataFile.parse(""));
    private @Nullable State checkedState;
    private List<Issue> checkedIssues = List.of();
    private NpcSpawnLink spawns = NpcSpawnLink.NONE;
    private @Nullable NpcDataIndex dataIndex;
    private @Nullable NpcStatReference statReference;
    private @Nullable NpcDataFile referenceFile;
    private @Nullable NpcAiCatalog aiCatalog;
    private @Nullable NpcDataFile aiFile;
    private @Nullable Lookup lookup;
    private boolean lookupByDistance = true;
    private boolean selecting;
    private final TreeSet<Integer> chosen = new TreeSet<>();
    private int anchor = -1;
    private @Nullable BatchKind batchKind;
    private final ImString batchNumber = new ImString("2", 32);
    private final ImString batchKey = new ImString(64);
    private final ImString batchValue = new ImString(256);
    private int confirmDelete = -1;
    private int compareWith = -1;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private @Nullable String filterItem;
    private @Nullable String filterSkill;
    private final ImString filterRace = new ImString(32);
    private final ImString filterAi = new ImString(64);
    private boolean filterEdited;
    private int filterSpawned;
    private int sort;
    private boolean filtersOpen;
    private String listedFilters = "";
    private final Set<String> lockedShares = new HashSet<>();
    private boolean barDragging;
    private String pasteError = "";
    private String pasteKey = "";
    private final Set<String> reviewOpen = new HashSet<>();

    public NpcWorkspace(@Nullable Path client) {
        this(client, WorldVariant.MAIN);
    }

    public NpcWorkspace(@Nullable Path client, WorldVariant variant) {
        this.icons = new NpcIcons(client);
        source.openClient(client, variant);
    }

    private enum Tab {
        DROP("Drop"),
        SKILLS("Skills"),
        AI("AI"),
        STATS("Stats"),
        ALL("All");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private enum PickKind {
        ITEM,
        SKILL,
        NPC,
        AI,
        PARAMETER
    }

    private record Picker(
            PickKind kind, String title, Consumer<String> apply, int filter, boolean repeatable, List<String> only) {}

    private record Review(
            List<Change> changes,
            List<Section> sections,
            boolean changedOnDisk,
            List<Issue> errors,
            List<Issue> warnings,
            NpcPch.@Nullable Change pch,
            List<String> spawnNotes) {}

    private enum LookupKind {
        ITEM,
        SKILL,
        LIST
    }

    private record LookupRow(int record, double chance, String where, Nearest nearest) {}

    private record Lookup(LookupKind kind, String name, String title, String key, List<LookupRow> rows, State state) {}

    private enum BatchKind {
        DROP("Drop chances", "drop chances"),
        SPOIL("Spoil chances", "spoil chances"),
        EXP("Exp rate", "exp rate"),
        SP("SP", "SP"),
        SET("Set a setting", "");

        final String label;
        final String noun;

        BatchKind(String label, String noun) {
            this.label = label;
            this.noun = noun;
        }
    }

    private enum FieldKind {
        NUMBER,
        FLAG,
        ITEM,
        TEXT,
        CHOICE,
        FIXED
    }

    private record StatField(String key, String label, FieldKind kind, String tip) {}

    private record StatGroup(String title, List<StatField> fields) {}

    static String oneIn(double percent, String tries) {
        if (percent <= 0 || percent >= 5) {
            return "";
        }
        return String.format(Locale.ROOT, " · 1 in %,d %s", Math.round(100 / percent), tries);
    }

    private interface ItemChange {
        String apply(@Nullable Item changed);
    }

    static String skillCell(@Nullable SkillCell skill, @Nullable SkillCell other) {
        if (skill == null) {
            return NONE;
        }
        var level = skill.level() > 0 ? "Lv " + skill.level() : "\u2713";
        if (other == null
                || skill.id() >= 0 && skill.id() == other.id()
                || skill.name().equals(other.name())) {
            return level;
        }
        var name = skill.name();
        var common = 0;
        while (common < Math.min(name.length(), other.name().length())
                && name.charAt(common) == other.name().charAt(common)) {
            common++;
        }
        var cut = name.lastIndexOf('_', common - 1) + 1;
        var rest = name.substring(Math.max(0, cut));
        return (rest.isEmpty() ? name : rest) + (skill.level() > 1 ? " Lv " + skill.level() : "");
    }

    static String formatPercent(double value) {
        var rounded = value >= 1 ? Math.round(value * 100) / 100.0 : Math.round(value * 10_000) / 10_000.0;
        return ScriptValue.number(rounded) + "%";
    }

    public void tick(@Nullable Path scriptFolder, ViewerUiRenderer renderer) {
        if (!Objects.equals(scriptFolder, requestedFolder) && !source.isModified()) {
            requestedFolder = scriptFolder;
            source.open(scriptFolder);
            selected = -1;
            expanded = "";
        }
        source.poll();
        icons.poll(renderer);
        var document = source.getDocument();
        if (pendingSelect != null && document != null) {
            var record = document.byName(document.getState(), pendingSelect);
            if (record != null) {
                open(record.getIndex());
            }
            pendingSelect = null;
        }
        if (document != null
                && (selected >= document.size()
                        || selected >= 0 && document.getState().isRemoved(selected))) {
            selected = -1;
        }
        if (document != null && compareWith >= document.size()) {
            compareWith = -1;
        }
        if (document != null) {
            chosen.removeIf(
                    record -> record >= document.size() || document.getState().isRemoved(record));
        }
    }

    public void setSpawnLink(NpcSpawnLink link) {
        spawns = link;
    }

    public void select(String npcName) {
        pendingSelect = npcName;
        search.set("");
    }

    public @Nullable String consumeSpawnRequest() {
        var request = spawnRequest;
        spawnRequest = null;
        return request;
    }

    public int consumeExportRequest() {
        var request = exportRequest;
        exportRequest = -1;
        return request;
    }

    public @Nullable SpawnText consumeSaved() {
        return source.consumeSaved();
    }

    public void handleKeys(Keyboard keys, boolean panelOpen) {
        var document = source.getDocument();
        if (!panelOpen || document == null || ImGui.getIO().getWantTextInput()) {
            return;
        }
        if (keys.isPressed(Key.COMMAND) && keys.isClicked(Key.Z)) {
            if (keys.isPressed(Key.SHIFT)) {
                document.redo();
            } else {
                document.undo();
            }
            lastEditKey = "";
            return;
        }
        if (keys.isPressed(Key.COMMAND) && keys.isClicked(Key.S)) {
            openReview(document);
        }
        if (keys.isPressed(Key.COMMAND) && keys.isClicked(Key.D) && selected >= 0) {
            duplicate(document, selected);
        }
    }

    public boolean handleEscape() {
        if (picker != null || lookup != null || review != null) {
            return true;
        }
        if (batchKind != null) {
            batchKind = null;
            return true;
        }
        if (!expanded.isEmpty()) {
            expanded = "";
            return true;
        }
        if (selected >= 0) {
            selected = -1;
            return true;
        }
        if (selecting) {
            selecting = false;
            chosen.clear();
            return true;
        }
        return false;
    }

    public void drawPanel() {
        var document = source.getDocument();
        drawSourceRow(document);
        if (source.getPhase() != Phase.READY || document == null) {
            if (source.getPhase() == Phase.EMPTY) {
                space(GeodataWidgets.BLOCK);
                if (GeodataWidgets.callout(
                        "npc-folder", Glyph.FOLDER, "No script folder", "Choose it in NPC Spawns", "Open")) {
                    spawnRequest = "";
                }
                space(GeodataWidgets.BLOCK);
                HudWidgets.guideLink("What NPC data is and where it comes from", "npcs/what-is-npc-data");
            } else if (source.getPhase() == Phase.LOADING) {
                space(GeodataWidgets.BLOCK);
                text(GeodataWidgets.MUTED, "Reading npcdata.txt, itemdata.txt and skilldata.txt…");
            }
            return;
        }
        var footer = isFooterShown(document);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild("##npcBody", 0, footer ? -FOOTER : 0, false);
        ImGui.popStyleVar();
        ImGui.pushTextWrapPos(0);
        if (selected >= 0) {
            drawNpcView(document, document.record(selected));
        } else {
            drawNpcList(document);
        }
        ImGui.popTextWrapPos();
        ImGui.dummy(0, 8);
        ImGui.endChild();
        if (footer) {
            drawFooter(document);
        }
    }

    public void drawGuard(@Nullable ImFont mono) {
        drawPicker();
        drawLookup();
        drawReview(mono);
        if (pendingLeave == null) {
            return;
        }
        var title = "Unsaved NPC changes";
        if (!ImGui.isPopupOpen(title)) {
            ImGui.openPopup(title);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(380, 0);
        if (ImGui.beginPopupModal(title, ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize)) {
            Motion.dialog();
            ImGui.textWrapped("npcdata.txt has edits that are not saved.");
            var document = source.getDocument();
            var problems = document == null ? List.<Issue>of() : filterErrors(findIssues(document));
            if (!problems.isEmpty()) {
                text(GeodataWidgets.BLOCKED, "It cannot be saved until these are fixed:");
                drawIssueList(problems, GeodataWidgets.BLOCKED);
            }
            ImGui.dummy(0, 6);
            ImGui.beginDisabled(!problems.isEmpty());
            var save = GeodataWidgets.rowButton("Save", 0, 3);
            ImGui.endDisabled();
            if (save) {
                source.save(backupOnSave);
                pendingLeave = null;
                ImGui.closeCurrentPopup();
            }
            if (GeodataWidgets.rowButton("Discard", 1, 3)) {
                if (document != null) {
                    document.discardChanges();
                }
                approvedLeave = pendingLeave;
                pendingLeave = null;
                ImGui.closeCurrentPopup();
            }
            if (GeodataWidgets.rowButton("Cancel", 2, 3) || GeodataWidgets.wasClickedOutside()) {
                pendingLeave = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    public boolean requestLeave(Leave action) {
        if (!source.isModified()) {
            return false;
        }
        pendingLeave = action;
        return true;
    }

    public @Nullable Leave consumeApprovedLeave() {
        var leave = approvedLeave;
        approvedLeave = null;
        return leave;
    }

    public boolean isHeld() {
        return barDragging || review != null;
    }

    public NpcDataSource getSource() {
        return source;
    }

    public ClientCatalog getCatalog() {
        return source.getCatalog();
    }

    public boolean hasDocument() {
        return source.getDocument() != null;
    }

    public @Nullable String openNpc() {
        var document = source.getDocument();
        if (document == null || selected < 0) {
            return null;
        }
        try {
            return document.record(selected).name();
        } catch (RuntimeException gone) {
            return null;
        }
    }

    @Override
    public void close() {
        source.close();
        icons.close();
        clipper.destroy();
    }

    private NpcDataIndex dataIndex(NpcDataDocument document) {
        if (dataIndex == null || dataIndex.getDocument() != document) {
            dataIndex = NpcDataIndex.of(document);
        }
        return dataIndex;
    }

    private NpcStatReference statReferenceFor(NpcDataDocument document) {
        if (statReference == null || referenceFile != document.getFile()) {
            referenceFile = document.getFile();
            statReference = NpcStatReference.of(document.getFile());
        }
        return statReference;
    }

    private NpcAiCatalog aiCatalog(NpcDataDocument document) {
        if (aiCatalog == null || aiFile != document.getFile()) {
            aiFile = document.getFile();
            aiCatalog = NpcAiCatalog.of(document.getFile());
        }
        return aiCatalog;
    }

    private void drawSourceRow(@Nullable NpcDataDocument document) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 44f;
        ImGui.setCursorScreenPos(x, y);
        ImGui.setNextItemAllowOverlap();
        ImGui.invisibleButton("npc-source", width - 30, height);
        var hovered = ImGui.isItemHovered();
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, 6f);
        HudWidgets.glyph(draw, Glyph.FILE, x + 9, y + 7, 16, GeodataWidgets.MUTED);
        draw.pushClipRect(x, y, x + width - 34, y + height, true);
        draw.addText(x + 32, y + 6, GeodataWidgets.TEXT, NpcDataSource.NPCS);
        var detail = switch (source.getPhase()) {
            case EMPTY -> "No script folder";
            case LOADING -> "Reading…";
            case FAILED -> "Cannot read the file";
            case READY ->
                document == null
                        ? ""
                        : String.format(
                                Locale.ROOT,
                                "%,d NPCs%s",
                                document.size() - document.getState().removed().size(),
                                clientStatus());
        };
        draw.addText(
                x + 32,
                y + 24,
                source.getPhase() == Phase.FAILED ? GeodataWidgets.BLOCKED : GeodataWidgets.MUTED,
                detail);
        draw.popClipRect();
        ImGui.setCursorScreenPos(x + 32 + ImGui.calcTextSizeX(NpcDataSource.NPCS) + 4, y + 6);
        HudWidgets.helpMark("terms/npcdata");
        if (hovered) {
            var folder = source.getFolder();
            HudWidgets.showTooltip((folder == null ? "" : folder.resolve(NpcDataSource.NPCS) + "\n\n")
                    + "The script folder is the one NPC Spawns has open. Item and skill names come from itemdata.txt"
                    + " and skilldata.txt next to it, their names and icons in English from the game client.");
        }
        ImGui.setCursorScreenPos(x + width - 28, y + (height - 26) * .5f);
        ImGui.beginDisabled(source.getFolder() == null || source.isSaving());
        if (HudWidgets.button("npc-reload", Glyph.RELOAD, "", 26, 26, false, false)) {
            if (source.isModified()) {
                source.poll();
            } else {
                source.reload();
                selected = -1;
            }
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                source.isModified()
                        ? "Save or undo your changes before reading the files again."
                        : "Read npcdata.txt again after it changes on disk.");
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        if (!source.getMessage().isBlank()
                && (source.getPhase() != Phase.READY || source.isMessageError() || !source.isModified())) {
            ImGui.pushTextWrapPos(0);
            text(source.isMessageError() ? GeodataWidgets.BLOCKED : GeodataWidgets.MUTED, source.getMessage());
            ImGui.popTextWrapPos();
        }
    }

    private String clientStatus() {
        if (source.isCatalogLoading()) {
            return " · reading the client…";
        }
        var catalog = source.getCatalog();
        return catalog.getItemCount() == 0 && catalog.getSkillCount() == 0 ? " · no client names" : " · client icons";
    }

    private void drawNpcList(NpcDataDocument document) {
        space(GeodataWidgets.BLOCK);
        var hasText = !search.get().isEmpty();
        ImGui.setNextItemWidth(hasText ? ImGui.getContentRegionAvailX() - 30 : -1);
        ImGui.inputTextWithHint("##npc-search", "Find an NPC by name, id or level", search);
        var typing = ImGui.isItemActive();
        var left = ImGui.isItemDeactivated();
        var entered = left && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter));
        GeodataWidgets.tip(
                "Names in English and as npcdata.txt writes them, ids, lv 40 for a level and lv 20-30 for levels.");
        if (hasText) {
            ImGui.sameLine(0, 4);
            if (HudWidgets.button("npc-search-clear", Glyph.CLOSE, "", 26, ImGui.getFrameHeight(), false, false)) {
                search.set("");
            }
            GeodataWidgets.tip("Clear the search.", "Esc");
        }
        if ((typing || left) && ImGui.isKeyPressed(ImGuiKey.Escape)) {
            search.set("");
        }
        space(GeodataWidgets.ROW);
        var picked = GeodataWidgets.segmented("npc-kind", KINDS, kind);
        if (picked >= 0) {
            kind = picked;
        }
        space(GeodataWidgets.ROW);
        listChips();
        if (filtersOpen) {
            space(GeodataWidgets.ROW);
            drawFilters();
        }
        if (selecting) {
            space(GeodataWidgets.ROW);
            drawBatchBar(document);
        }
        refreshList(document);
        var edited = document.getState().edits().size();
        space(GeodataWidgets.BLOCK);
        text(
                GeodataWidgets.MUTED,
                String.format(Locale.ROOT, "%,d %s", listed.size(), listed.size() == 1 ? "NPC" : "NPCs")
                        + (edited > 0 ? String.format(Locale.ROOT, " · %d edited", edited) : ""));
        if (entered && !listed.isEmpty()) {
            open(listed.getFirst());
        }
        var rowHeight = ImGui.getFontSize() * 2 + 14;
        if (search.get().isBlank() && !isFiltering() && !selecting && !recent.isEmpty()) {
            GeodataWidgets.section("RECENT", GeodataWidgets.ROW);
            for (var name : List.copyOf(recent)) {
                var record = document.byName(document.getState(), name);
                if (record != null) {
                    listRow(document, record, rowHeight, "recent/");
                }
            }
            GeodataWidgets.section("ALL NPCS");
        }
        space(GeodataWidgets.ROW);
        if (listed.isEmpty()) {
            text(GeodataWidgets.FAINT, "No NPC matches.");
            return;
        }
        clipper.begin(listed.size(), rowHeight);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                listRow(document, document.record(listed.get(i)), rowHeight, "");
            }
        }
        clipper.end();
    }

    private void listChips() {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - 2 * spacing) / 3;
        var active = activeFilters();
        if (GeodataWidgets.chip(
                "npc-filters",
                Glyph.FILTER,
                active > 0 ? "Filters " + active : "Filters",
                filtersOpen || active > 0,
                width)) {
            filtersOpen = !filtersOpen;
        }
        GeodataWidgets.tip(
                "Show only NPCs that drop an item, know a skill, have a race or AI, are edited, or are spawned.");
        ImGui.sameLine();
        if (GeodataWidgets.chip("npc-select", Glyph.CURSOR, "Select", selecting, width)) {
            selecting = !selecting;
            batchKind = null;
            if (!selecting) {
                chosen.clear();
            }
        }
        GeodataWidgets.tip("Choose many NPCs and change them at once. Shift-click selects a range.");
        ImGui.sameLine();
        if (GeodataWidgets.chip("npc-sort", Glyph.SLIDERS, SORTS[sort], sort > 0, width)) {
            sort = (sort + 1) % SORTS.length;
        }
        GeodataWidgets.tip("The order of the list: as in npcdata.txt, by level, or by name. Click for the next.");
    }

    private int activeFilters() {
        var count = 0;
        count += filterItem != null ? 1 : 0;
        count += filterSkill != null ? 1 : 0;
        count += filterRace.get().isBlank() ? 0 : 1;
        count += filterAi.get().isBlank() ? 0 : 1;
        count += filterEdited ? 1 : 0;
        count += filterSpawned > 0 ? 1 : 0;
        return count;
    }

    private boolean isFiltering() {
        return activeFilters() > 0;
    }

    private void drawFilters() {
        filterRow(
                "npc-filter-item",
                Glyph.BOX,
                "Drops",
                filterItem == null ? "Any item" : itemLabel(filterItem),
                filterItem != null,
                () -> openItemPicker(name -> filterItem = name),
                () -> filterItem = null);
        space(GeodataWidgets.ROW);
        filterRow(
                "npc-filter-skill",
                Glyph.SPARKLES,
                "Knows",
                filterSkill == null ? "Any skill" : skillLabel(filterSkill),
                filterSkill != null,
                () -> openSkillPicker(2, name -> filterSkill = name),
                () -> filterSkill = null);
        space(GeodataWidgets.ROW);
        var column = GeodataWidgets.columnWidth(2);
        GeodataWidgets.columns(
                () -> GeodataWidgets.compactText("Race", "##npc-filter-race", "any", filterRace, column),
                () -> GeodataWidgets.compactText("AI", "##npc-filter-ai", "any", filterAi, column));
        space(GeodataWidgets.ROW);
        ImGui.beginDisabled(!spawns.isReady());
        var spawned = GeodataWidgets.segmented("npc-filter-spawned", SPAWNED, spawns.isReady() ? filterSpawned : 0);
        ImGui.endDisabled();
        if (spawned >= 0) {
            filterSpawned = spawned;
        }
        GeodataWidgets.tip(
                spawns.isReady()
                        ? "Whether npcpos.txt spawns the NPC."
                        : "Open npcpos.txt in NPC Spawns to filter by spawns.");
        space(GeodataWidgets.ROW);
        if (ImGui.checkbox("Edited only##npc-filter-edited", filterEdited)) {
            filterEdited = !filterEdited;
        }
        if (isFiltering()) {
            ImGui.sameLine(ImGui.getContentRegionMaxX() - 96);
            if (HudWidgets.button("npc-filter-clear", null, "Clear filters", 96, 24, false, true)) {
                filterItem = null;
                filterSkill = null;
                filterRace.set("");
                filterAi.set("");
                filterEdited = false;
                filterSpawned = 0;
            }
        }
    }

    private void filterRow(
            String id, Glyph glyph, String label, String value, boolean set, Runnable choose, Runnable clear) {
        var width = ImGui.getContentRegionAvailX();
        ImGui.alignTextToFramePadding();
        text(GeodataWidgets.MUTED, label);
        ImGui.sameLine(64);
        var room = width - 64 - (set ? 30 : 0);
        if (HudWidgets.button(id, glyph, value, room, 26, false, true)) {
            choose.run();
        }
        if (set) {
            ImGui.sameLine(0, 4);
            if (HudWidgets.button(id + "-clear", Glyph.CLOSE, "", 26, 26, false, false)) {
                clear.run();
            }
        }
    }

    private void drawBatchBar(NpcDataDocument document) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x + 10, y + 8);
        ImGui.beginGroup();
        var inner = width - 20;
        ImGui.alignTextToFramePadding();
        text(
                chosen.isEmpty() ? GeodataWidgets.MUTED : GeodataWidgets.TEXT,
                chosen.isEmpty()
                        ? "No NPC selected"
                        : String.format(
                                Locale.ROOT, "%,d %s selected", chosen.size(), chosen.size() == 1 ? "NPC" : "NPCs"));
        ImGui.sameLine(inner - 150);
        if (HudWidgets.button("npc-select-all", null, "All shown", 76, 24, false, true)) {
            chosen.addAll(listed);
        }
        GeodataWidgets.tip("Select every NPC the list shows now.");
        ImGui.sameLine(0, 4);
        ImGui.beginDisabled(chosen.isEmpty());
        if (HudWidgets.button("npc-select-none", null, "None", 66, 24, false, true)) {
            chosen.clear();
        }
        ImGui.endDisabled();
        space(GeodataWidgets.ROW);
        ImGui.beginDisabled(chosen.isEmpty());
        if (batchKind == null) {
            var spacing = ImGui.getStyle().getItemSpacingX();
            var third = (inner - 2 * spacing) / 3;
            var row = new BatchKind[] {BatchKind.DROP, BatchKind.SPOIL, BatchKind.EXP};
            for (var i = 0; i < row.length; i++) {
                if (i > 0) {
                    ImGui.sameLine();
                }
                if (HudWidgets.button(
                        "npc-batch-" + row[i].name(),
                        null,
                        row[i].label.replace(" chances", "") + " ×",
                        third,
                        26,
                        false,
                        true)) {
                    batchKind = row[i];
                    batchNumber.set("2");
                }
            }
            if (HudWidgets.button("npc-batch-SP", null, "SP ×", third, 26, false, true)) {
                batchKind = BatchKind.SP;
                batchNumber.set("2");
            }
            ImGui.sameLine();
            if (HudWidgets.button("npc-batch-SET", null, "Set…", third, 26, false, true)) {
                batchKind = BatchKind.SET;
                batchKey.set("");
                batchValue.set("");
            }
            ImGui.sameLine();
            if (HudWidgets.button("npc-batch-skill", Glyph.SPARKLES, "Skill", third, 26, false, true)) {
                ImGui.openPopup("npc-batch-skill-menu");
            }
            if (ImGui.beginPopup("npc-batch-skill-menu")) {
                if (ImGui.menuItem("Add a skill…")) {
                    openSkillPicker(
                            0,
                            skill -> applyBatch(
                                    document,
                                    "Add " + skill,
                                    "added " + skill,
                                    Set.of(NpcValues.SKILLS),
                                    state -> NpcBatchEdits.addSkill(document, state, chosen, skill)));
                }
                if (ImGui.menuItem("Remove a skill…")) {
                    openSkillPicker(
                            2,
                            skill -> applyBatch(
                                    document,
                                    "Remove " + skill,
                                    "removed " + skill,
                                    Set.of(NpcValues.SKILLS),
                                    state -> NpcBatchEdits.removeSkill(document, state, chosen, skill)),
                            selectedSkills(document));
                }
                ImGui.endPopup();
            }
        } else {
            drawBatchForm(document, inner);
        }
        ImGui.endDisabled();
        ImGui.endGroup();
        var bottom = ImGui.getItemRectMaxY() + 8;
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.addRect(x, y, x + width, bottom, ImColor.rgba(92, 219, 241, 90), 8, 0, 1);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
    }

    private void drawBatchForm(NpcDataDocument document, float inner) {
        var kindNow = batchKind;
        var spacing = ImGui.getStyle().getItemSpacingX();
        if (kindNow == BatchKind.SET) {
            var half = (inner - spacing) / 2;
            GeodataWidgets.columns(
                    () -> GeodataWidgets.compactText("Setting", "##npc-batch-key", "such as can_move", batchKey, half),
                    () -> GeodataWidgets.compactText("Value", "##npc-batch-value", "as written", batchValue, half));
            var keys = selectedKeys(document);
            var typed = batchKey.get().strip();
            if (!typed.isEmpty() && !keys.contains(typed)) {
                var shown = 0;
                for (var key : keys) {
                    if (key.contains(typed) && shown < 4) {
                        if (shown > 0) {
                            ImGui.sameLine(0, 4);
                        }
                        if (HudWidgets.button(
                                "npc-batch-key-" + key, null, key, ImGui.calcTextSizeX(key) + 16, 22, false, true)) {
                            batchKey.set(key);
                        }
                        shown++;
                    }
                }
            }
        } else {
            ImGui.alignTextToFramePadding();
            text(GeodataWidgets.MUTED, kindNow.label + " ×");
            ImGui.sameLine(110);
            ImGui.setNextItemWidth(80);
            ImGui.inputText("##npc-batch-factor", batchNumber);
            GeodataWidgets.tip(
                    switch (kindNow) {
                        case DROP ->
                            "Each drop group's chance × this, up to 100%. Items keep their shares within a group.";
                        case SPOIL -> "Each spoil item's chance × this, up to 100%.";
                        case EXP -> "acquire_exp_rate × this.";
                        default ->
                            "acquire_sp × this. Whole numbers stay whole when the file writes skill points as points.";
                    });
        }
        space(GeodataWidgets.ROW);
        var half = (inner - spacing) / 2;
        if (HudWidgets.button("npc-batch-apply", null, "Apply to " + chosen.size(), half, 26, true, true)) {
            runBatch(document, kindNow);
        }
        ImGui.sameLine();
        if (HudWidgets.button("npc-batch-cancel", null, "Cancel", half, 26, false, true)) {
            batchKind = null;
        }
    }

    private void runBatch(NpcDataDocument document, BatchKind kindNow) {
        if (kindNow == BatchKind.SET) {
            var key = batchKey.get().strip();
            var value = batchValue.get().strip();
            if (key.isEmpty() || value.isEmpty()) {
                return;
            }
            applyBatch(
                    document,
                    key + "=" + value,
                    key + " set to " + value,
                    Set.of(key),
                    state -> NpcBatchEdits.setValue(document, state, chosen, key, value));
            batchKind = null;
            return;
        }
        double factor;
        try {
            factor = Double.parseDouble(batchNumber.get().strip().replace(',', '.'));
        } catch (NumberFormatException notNumber) {
            return;
        }
        if (!Double.isFinite(factor) || factor <= 0) {
            return;
        }
        var times = NpcBatchEdits.formatFactor(factor);
        switch (kindNow) {
            case DROP ->
                applyBatch(
                        document,
                        times + " drop chances",
                        "drop chances " + times,
                        Set.of(DropList.DROP),
                        state -> NpcBatchEdits.scaleDrop(document, state, chosen, factor));
            case SPOIL ->
                applyBatch(
                        document,
                        times + " spoil chances",
                        "spoil chances " + times,
                        Set.of(DropList.SPOIL),
                        state -> NpcBatchEdits.scaleSpoil(document, state, chosen, factor));
            case EXP ->
                applyBatch(
                        document,
                        times + " exp rate",
                        "exp rate " + times,
                        Set.of(NpcExperience.EXP_RATE),
                        state -> NpcBatchEdits.scaleNumber(
                                document, state, chosen, NpcExperience.EXP_RATE, factor, false));
            default -> {
                var points = !experienceFor(document).hasSpRate();
                applyBatch(
                        document,
                        times + " SP",
                        "SP " + times,
                        Set.of(NpcExperience.SP),
                        state -> NpcBatchEdits.scaleNumber(document, state, chosen, NpcExperience.SP, factor, points));
            }
        }
        batchKind = null;
    }

    private void applyBatch(
            NpcDataDocument document, String action, String noun, Set<String> keys, UnaryOperator<State> edit) {
        var before = document.getState();
        var after = edit.apply(before);
        var changed = NpcBatchEdits.findChangedRecords(before, after);
        if (changed.isEmpty()) {
            source.showNote("Nothing changed: no selected NPC could take it.");
            return;
        }
        var label = action + " on " + changed.size() + (changed.size() == 1 ? " NPC" : " NPCs");
        document.apply(label, unused -> after, new Batch(noun, changed, keys));
        lastEditKey = "";
    }

    private List<String> selectedKeys(NpcDataDocument document) {
        var counts = new TreeMap<String, Integer>();
        for (var record : chosen) {
            for (var key : document.record(record).getKeys()) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        var keys = new ArrayList<>(counts.keySet());
        keys.sort(Comparator.comparing((String key) -> -counts.get(key)).thenComparing(key -> key));
        return keys;
    }

    private List<String> selectedSkills(NpcDataDocument document) {
        var skills = new LinkedHashSet<String>();
        var index = dataIndex(document);
        for (var record : chosen) {
            for (var use : index.getSkills(document.getState(), record)) {
                if (use.setting().isEmpty()) {
                    skills.add(use.skill());
                }
            }
        }
        return List.copyOf(skills);
    }

    private void listRow(NpcDataDocument document, NpcRecord record, float rowHeight, String idPrefix) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var index = record.getIndex();
        if (ImGui.invisibleButton("npc-row/" + idPrefix + index, width, rowHeight - 2)) {
            if (selecting) {
                select(index, ImGui.getIO().getKeyShift());
            } else {
                open(index);
            }
        }
        var hovered = ImGui.isItemHovered();
        var draw = ImGui.getWindowDrawList();
        var picked = selecting && chosen.contains(index);
        if (hovered || picked) {
            draw.addRectFilled(
                    x, y, x + width, y + rowHeight - 2, picked ? ImColor.rgb(30, 58, 70) : GeodataWidgets.HOVER, 6f);
        }
        var left = x + 4;
        if (selecting) {
            var box = 16f;
            var top = y + (rowHeight - 2 - box) * .5f;
            draw.addRectFilled(
                    left + 2,
                    top,
                    left + 2 + box,
                    top + box,
                    picked ? GeodataWidgets.ACCENT : ImColor.rgb(20, 28, 36),
                    4f);
            draw.addRect(
                    left + 2,
                    top,
                    left + 2 + box,
                    top + box,
                    picked ? GeodataWidgets.ACCENT : HudWidgets.BORDER,
                    4,
                    0,
                    1);
            if (picked) {
                draw.addLine(left + 6, top + 8, left + 9, top + 12, ImColor.rgb(16, 24, 30), 2);
                draw.addLine(left + 9, top + 12, left + 15, top + 4, ImColor.rgb(16, 24, 30), 2);
            }
            left += box + 8;
        }
        var style = SpawnStyle.ofType(record.getType());
        var level = "Lv " + Objects.requireNonNullElse(document.getValue(index, "level"), "?");
        var badgeWidth = Math.max(46, ImGui.calcTextSizeX(level) + 12);
        SpawnPanelWidgets.badge(
                draw, left, y + (rowHeight - ImGui.getFontSize() - 8) * .5f, badgeWidth, style.argb, level);
        var textLeft = left + badgeWidth + 10;
        var textWidth = x + width - textLeft - 6;
        var query = search.get().strip().toLowerCase(Locale.ROOT);
        var title = displayName(record);
        SpawnPanelWidgets.drawHighlighted(
                draw, textLeft, y + 4, HudWidgets.truncateToWidth(title, textWidth), query, GeodataWidgets.TEXT);
        var detail = record.name() + " · " + record.getId() + " · " + record.getType();
        var detailWidth = textWidth;
        var fresh = document.isAdded(index)
                && index
                        >= document.getFile().size()
                                + document.getSavedState().added().size();
        var mark = fresh ? "new" : isUnsaved(document, index) ? "edited" : "";
        if (!mark.isEmpty()) {
            detailWidth -= ImGui.calcTextSizeX(mark) + 10;
            draw.addText(
                    x + width - ImGui.calcTextSizeX(mark) - 6,
                    y + ImGui.getFontSize() + 7,
                    fresh ? GeodataWidgets.ACCENT : GeodataWidgets.WARNING,
                    mark);
        }
        SpawnPanelWidgets.drawHighlighted(
                draw,
                textLeft,
                y + ImGui.getFontSize() + 7,
                HudWidgets.truncateToWidth(detail, detailWidth),
                query,
                GeodataWidgets.MUTED);
    }

    private void select(int record, boolean range) {
        var at = listed.indexOf(record);
        var from = listed.indexOf(anchor);
        if (range && at >= 0 && from >= 0) {
            for (var i = Math.min(at, from); i <= Math.max(at, from); i++) {
                chosen.add(listed.get(i));
            }
        } else if (!chosen.remove(record)) {
            chosen.add(record);
        }
        anchor = record;
    }

    private void refreshList(NpcDataDocument document) {
        var query = search.get().strip().toLowerCase(Locale.ROOT);
        var filters = (filterItem == null ? "" : filterItem)
                + "|"
                + (filterSkill == null ? "" : filterSkill)
                + "|"
                + filterRace.get().strip()
                + "|"
                + filterAi.get().strip()
                + "|"
                + filterEdited
                + "|"
                + (spawns.isReady() ? filterSpawned : 0)
                + "|"
                + sort
                + "|"
                + spawns.getSpawnedNames().size();
        if (query.equals(listedQuery)
                && kind == listedKind
                && filters.equals(listedFilters)
                && listedGeneration == source.getGeneration() + document.getRevision() * 31) {
            return;
        }
        listedQuery = query;
        listedKind = kind;
        listedFilters = filters;
        listedGeneration = source.getGeneration() + document.getRevision() * 31;
        var levelFrom = -1;
        var levelTo = -1;
        var levels = Pattern.compile("lv\\s*(\\d+)(?:\\s*-\\s*(\\d+))?").matcher(query);
        if (levels.matches()) {
            levelFrom = Integer.parseInt(levels.group(1));
            levelTo = levels.group(2) == null ? levelFrom : Integer.parseInt(levels.group(2));
            if (levelTo < levelFrom) {
                var swap = levelTo;
                levelTo = levelFrom;
                levelFrom = swap;
            }
        }
        var state = document.getState();
        var index = dataIndex(document);
        Set<Integer> dropping = null;
        if (filterItem != null) {
            dropping = new HashSet<>();
            for (var use : index.findDroppedBy(state, filterItem)) {
                dropping.add(use.record());
            }
        }
        Set<Integer> knowing = null;
        if (filterSkill != null) {
            knowing = new HashSet<>();
            for (var use : index.findKnownBy(state, filterSkill)) {
                knowing.add(use.record());
            }
        }
        var race = filterRace.get().strip().toLowerCase(Locale.ROOT);
        var ai = filterAi.get().strip().toLowerCase(Locale.ROOT);
        var spawned = spawns.isReady() && filterSpawned > 0 ? spawns.getSpawnedNames() : null;
        var found = new ArrayList<Integer>();
        for (var i = 0; i < document.size(); i++) {
            if (state.isRemoved(i)) {
                continue;
            }
            var record = document.record(i);
            if (!doesKindMatch(record)) {
                continue;
            }
            if (!query.isEmpty()) {
                boolean matches;
                if (levelFrom >= 0) {
                    var level = document.getValue(i, "level");
                    var value = -1;
                    try {
                        value = level == null ? -1 : Integer.parseInt(level.strip());
                    } catch (NumberFormatException notLevel) {
                        value = -1;
                    }
                    matches = value >= levelFrom && value <= levelTo;
                } else {
                    matches = record.name().contains(query)
                            || displayName(record).toLowerCase(Locale.ROOT).contains(query)
                            || Integer.toString(record.getId()).startsWith(query);
                }
                if (!matches) {
                    continue;
                }
            }
            if (dropping != null && !dropping.contains(i) || knowing != null && !knowing.contains(i)) {
                continue;
            }
            if (!race.isEmpty()
                    && !race.equals(Objects.requireNonNullElse(document.getValue(i, "race"), "")
                            .strip()
                            .toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!ai.isEmpty() && !index.getAi(state, i).toLowerCase(Locale.ROOT).contains(ai)) {
                continue;
            }
            if (filterEdited && !state.isEdited(i) && !document.isAdded(i)) {
                continue;
            }
            if (spawned != null && spawned.contains(record.name()) != (filterSpawned == 1)) {
                continue;
            }
            found.add(i);
        }
        if (sort == 1) {
            found.sort(Comparator.comparingInt((Integer i) -> readLevel(document, i))
                    .thenComparingInt(i -> i));
        } else if (sort == 2) {
            found.sort(Comparator.comparing(
                            (Integer i) -> displayName(document.record(i)).toLowerCase(Locale.ROOT))
                    .thenComparingInt(i -> i));
        }
        listed = found;
    }

    private boolean doesKindMatch(NpcRecord record) {
        var style = SpawnStyle.ofType(record.getType());
        return switch (kind) {
            case 1 -> style == SpawnStyle.MONSTER;
            case 2 -> style == SpawnStyle.BOSS;
            case 3 -> style == SpawnStyle.CITIZEN;
            default -> true;
        };
    }

    private void open(int record) {
        selected = record;
        expanded = "";
        activeField = null;
        pasteError = "";
        var document = source.getDocument();
        if (document != null && record < document.size()) {
            var name = document.record(record).name();
            recent.remove(name);
            recent.addFirst(name);
            while (recent.size() > RECENT) {
                recent.removeLast();
            }
        }
    }

    private void drawNpcView(NpcDataDocument document, NpcRecord record) {
        space(GeodataWidgets.BLOCK);
        drawNpcCard(document, record);
        space(GeodataWidgets.BLOCK);
        var labels = new String[Tab.values().length];
        for (var t : Tab.values()) {
            labels[t.ordinal()] = t.label;
        }
        var picked = GeodataWidgets.segmented("npc-tab", labels, tab.ordinal());
        if (picked >= 0) {
            tab = Tab.values()[picked];
            expanded = "";
        }
        switch (tab) {
            case DROP -> dropTab(document, record);
            case SKILLS -> drawSkillsTab(document, record);
            case AI -> drawAiTab(document, record);
            case STATS -> drawStatsTab(document, record);
            case ALL -> drawAllTab(document, record);
        }
    }

    private void drawNpcCard(NpcDataDocument document, NpcRecord record) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var buttonsTop = y + ImGui.getFontSize() * 3 + 22;
        var height = buttonsTop + 26 + 10 - y;
        var style = SpawnStyle.ofType(record.getType());
        var index = record.getIndex();
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, 8f);
        draw.addRectFilled(x, y, x + 4, y + height, style.getImguiColor(1f), 8, ImDrawFlags.RoundCornersLeft);
        var client = source.getCatalog().getNpc(record.getId());
        var title = client != null
                        && !client.title().isBlank()
                        && !client.title().strip().matches("\\d+")
                ? client.title()
                : "";
        var textWidth = width - 50;
        draw.addText(x + 14, y + 8, GeodataWidgets.TEXT, HudWidgets.truncateToWidth(displayName(record), textWidth));
        draw.addText(
                x + 14,
                y + 10 + ImGui.getFontSize(),
                GeodataWidgets.MUTED,
                HudWidgets.truncateToWidth(
                        (title.isEmpty() ? "" : title + " · ")
                                + "Level "
                                + Objects.requireNonNullElse(document.getValue(index, "level"), "?")
                                + " · "
                                + style.label.toLowerCase(Locale.ROOT),
                        textWidth));
        var fresh = document.isAdded(index)
                && index
                        >= document.getFile().size()
                                + document.getSavedState().added().size();
        var where = fresh ? "new · line " + record.getLine() + " when saved" : "line " + record.getLine();
        draw.addText(
                x + 14,
                y + 12 + ImGui.getFontSize() * 2,
                fresh ? GeodataWidgets.ACCENT : GeodataWidgets.FAINT,
                HudWidgets.truncateToWidth(
                        record.name() + " · id " + record.getId() + " · " + record.getType() + " · " + where,
                        width - 24));
        ImGui.setCursorScreenPos(x + width - 32, y + 8);
        if (HudWidgets.button("npc-close", Glyph.CLOSE, "", 26, 26, false, false)) {
            selected = -1;
            expanded = "";
        }
        GeodataWidgets.tip("Back to the list.", "Esc");
        var spacing = 4f;
        var inner = width - 24;
        var button = (inner - 26 - 4 * spacing) / 4;
        var labeled = button >= ImGui.calcTextSizeX("Compare") + 16;
        ImGui.setCursorScreenPos(x + 12, buttonsTop);
        var nearest = spawns.isReady() ? spawns.findNearestSpawn(record.name()) : null;
        ImGui.beginDisabled(nearest == null || !nearest.loaded());
        if (HudWidgets.button("npc-fly", Glyph.FOCUS, labeled ? "Fly to" : "", button, 26, false, true)) {
            spawns.flyTo(record.name());
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                !spawns.isReady()
                        ? "Open npcpos.txt in NPC Spawns to fly to where this NPC spawns."
                        : nearest == null
                                ? "npcpos.txt does not spawn this NPC."
                                : !nearest.loaded()
                                        ? "Its spawns lie on map tiles that are not loaded in this session, so the"
                                                + " world does not show them."
                                        : "Fly to its nearest spawn, "
                                                + SpawnPanelWidgets.compact(Math.round(nearest.distance())) + " away.");
        ImGui.sameLine(0, spacing);
        if (HudWidgets.button("npc-spawns", Glyph.NPC, labeled ? "Spawns" : "", button, 26, false, true)) {
            spawnRequest = record.name();
        }
        GeodataWidgets.tip("Show where this NPC spawns, in NPC Spawns.");
        ImGui.sameLine(0, spacing);
        if (HudWidgets.button(
                "npc-compare", Glyph.SLIDERS, labeled ? "Compare" : "", button, 26, compareWith >= 0, true)) {
            if (compareWith >= 0) {
                compareWith = -1;
            } else {
                openNpcPicker("Compare with", name -> {
                    var other = document.byName(document.getState(), name);
                    if (other != null) {
                        compareWith = other.getIndex();
                        if (tab != Tab.DROP && tab != Tab.SKILLS) {
                            tab = Tab.STATS;
                        }
                    }
                });
            }
        }
        GeodataWidgets.tip(
                compareWith >= 0
                        ? "Stop comparing."
                        : "Compare its drop, skills and stats with another NPC, side by side, each on its tab.");
        ImGui.sameLine(0, spacing);
        if (HudWidgets.button("npc-export", Glyph.CARD, labeled ? "Export" : "", button, 26, false, true)) {
            exportRequest = record.getId();
        }
        GeodataWidgets.tip(
                "Export NPC: save its model as glTF with its skeleton and animations, for Blender and other 3D"
                        + " programs.");
        ImGui.sameLine(0, spacing);
        if (HudWidgets.button("npc-card-menu", Glyph.MENU, "", 26, 26, false, false)) {
            ImGui.openPopup("npc-card-menu-popup");
        }
        GeodataWidgets.tip("Duplicate, delete or reset this NPC");
        if (ImGui.beginPopup("npc-card-menu-popup")) {
            if (ImGui.menuItem("Duplicate", UNDO_KEY.replace("Z", "D"))) {
                duplicate(document, index);
            }
            if (ImGui.menuItem(
                    "Reset to file", "", false, document.getState().edits().containsKey(index))) {
                document.apply("Reset " + record.name(), state -> document.withoutEdits(state, index));
                lastEditKey = "";
                activeField = null;
            }
            ImGui.separator();
            if (ImGui.menuItem("Delete…")) {
                confirmDelete = index;
            }
            ImGui.endPopup();
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        if (confirmDelete == index) {
            deleteConfirmation(document, record);
        }
    }

    private void duplicate(NpcDataDocument document, int index) {
        var copy = document.copy(document.getState(), index);
        document.apply(
                "Duplicate " + document.record(index).name(),
                state -> document.copy(state, index).state());
        lastEditKey = "";
        open(copy.index());
        source.showNote("Added "
                + copy.name()
                + " with id "
                + copy.id()
                + ". The game client shows it without a model or name "
                + "until its npcgrp and NpcName tables have the id.");
    }

    private void deleteConfirmation(NpcDataDocument document, NpcRecord record) {
        var id = "Delete " + record.name() + "?##npc-delete";
        if (!ImGui.isPopupOpen(id)) {
            ImGui.openPopup(id);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(400, 0);
        if (!ImGui.beginPopupModal(id, ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize)) {
            confirmDelete = -1;
            return;
        }
        Motion.dialog();
        ImGui.pushTextWrapPos(0);
        ImGui.textUnformatted("The save leaves out the whole line of "
                + record.name()
                + " (id "
                + record.getId()
                + ")"
                + (source.hasPch() ? " and its line in npc_pch.txt." : "."));
        space(GeodataWidgets.ROW);
        if (!spawns.isReady()) {
            text(GeodataWidgets.FAINT, "NPC Spawns has no npcpos.txt open, so its spawns are not counted.");
        } else {
            var summary = spawns.getSummary(record.name());
            if (summary.entries() == 0) {
                text(GeodataWidgets.MUTED, "npcpos.txt does not spawn it.");
            } else {
                text(
                        GeodataWidgets.WARNING,
                        String.format(
                                Locale.ROOT,
                                "%,d %s of npcpos.txt still spawn it (%,d NPCs). The server cannot spawn an NPC "
                                        + "npcdata.txt does not have: remove those entries in NPC Spawns.",
                                summary.entries(),
                                summary.entries() == 1 ? "entry" : "entries",
                                summary.npcs()));
            }
            if (spawns.hasUnsavedSpawns()) {
                space(GeodataWidgets.ROW);
                text(GeodataWidgets.FAINT, "npcpos.txt has unsaved edits; the count is of the edited file.");
            }
        }
        ImGui.popTextWrapPos();
        space(GeodataWidgets.BLOCK);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.BLOCKED);
        var delete = GeodataWidgets.rowButton("Delete", 0, 2);
        ImGui.popStyleColor();
        if (delete) {
            var index = record.getIndex();
            document.apply("Delete " + record.name(), state -> document.withRemoved(state, index, true));
            lastEditKey = "";
            confirmDelete = -1;
            selected = -1;
            recent.remove(record.name());
            ImGui.closeCurrentPopup();
        }
        if (GeodataWidgets.rowButton("Cancel", 1, 2)
                || ImGui.isKeyPressed(ImGuiKey.Escape)
                || GeodataWidgets.wasClickedOutside()) {
            confirmDelete = -1;
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void dropTab(NpcDataDocument document, NpcRecord record) {
        var index = record.getIndex();
        var other = comparedRecord(document, record);
        if (other != null) {
            dropComparison(document, record, other);
        }
        var any = false;
        if (record.has(DropList.DROP)) {
            any = true;
            listTitle(
                    document,
                    index,
                    DropList.DROP,
                    "DROP",
                    GeodataWidgets.BLOCK,
                    "additional_make_multi_list. When the NPC dies, each group is rolled with its chance. A group that"
                            + " hits drops one of its items, picked by the items' chances, which add up to 100%.");
            drawSharedList(document, index, DropList.DROP);
            drawGroups(document, index, DropList.DROP, "drop", true);
        }
        if (record.has(DropList.SPOIL)) {
            any = true;
            listTitle(
                    document,
                    index,
                    DropList.SPOIL,
                    "SPOIL",
                    GeodataWidgets.SECTION,
                    "corpse_make_list: what Spoil and Sweep give, each item with its chance.");
            drawSharedList(document, index, DropList.SPOIL);
            drawItems(document, index, DropList.SPOIL, "spoil", "per spoil");
        }
        if (record.has(DropList.EXTRA) && !"{}".equals(document.getValue(index, DropList.EXTRA))) {
            any = true;
            listTitle(
                    document,
                    index,
                    DropList.EXTRA,
                    "EXTRA DROP",
                    GeodataWidgets.SECTION,
                    "additional_make_list: an older form of the drop, each item rolled with its own chance.");
            drawItems(document, index, DropList.EXTRA, "extra", "per kill");
        }
        if (record.has(DropList.HERBS)) {
            any = true;
            space(GeodataWidgets.SECTION);
            if (GeodataWidgets.header("Herbs##npc-herbs")) {
                space(GeodataWidgets.ROW);
                drawGroups(document, index, DropList.HERBS, "herbs", false);
            }
            GeodataWidgets.tip("ex_item_drop_list: herbs, in groups like the drop.");
        }
        if (record.has(DropList.MAKE) && !"{}".equals(document.getValue(index, DropList.MAKE))) {
            any = true;
            space(GeodataWidgets.SECTION);
            if (GeodataWidgets.header("Item make list##npc-make")) {
                space(GeodataWidgets.ROW);
                drawItems(document, index, DropList.MAKE, "make", "per kill");
            }
            GeodataWidgets.tip(
                    "item_make_list of Chronicle 4 files, items like the extra drop: {[name];min;max;chance}. "
                            + "Every NPC of the Chronicle 4 servers checked has it empty.");
        }
        if (!any) {
            space(GeodataWidgets.BLOCK);
            text(GeodataWidgets.FAINT, "This NPC has no item lists in npcdata.txt.");
            return;
        }
        drawExpectation(document, record);
    }

    private void listTitle(NpcDataDocument document, int record, String key, String title, float above, String tip) {
        GeodataWidgets.section(title, above);
        GeodataWidgets.tip(tip);
        if (key.equals(DropList.DROP)) {
            ImGui.sameLine(0, 4);
            HudWidgets.helpMark("terms/drop-group");
        }
        ImGui.sameLine(ImGui.getContentRegionMaxX() - 22);
        var y = ImGui.getCursorPosY();
        ImGui.setCursorPosY(y - 4);
        if (HudWidgets.button("npc-list-menu-" + key, Glyph.MENU, "", 22, 22, false, false)) {
            ImGui.openPopup("npc-list-menu-popup-" + key);
        }
        GeodataWidgets.tip("Copy or paste this list as text");
        if (ImGui.beginPopup("npc-list-menu-popup-" + key)) {
            if (ImGui.menuItem("Copy list")) {
                ImGui.setClipboardText(Objects.requireNonNullElse(document.getValue(record, key), "{}"));
                source.showNote(
                        "Copied " + key + " of " + document.record(record).name() + ".");
            }
            if (ImGui.menuItem("Paste list")) {
                paste(document, record, key);
            }
            ImGui.endPopup();
        }
        if (key.equals(pasteKey) && !pasteError.isEmpty()) {
            space(GeodataWidgets.ROW);
            ImGui.pushTextWrapPos(0);
            text(GeodataWidgets.BLOCKED, pasteError);
            ImGui.popTextWrapPos();
        }
    }

    private void paste(NpcDataDocument document, int record, String key) {
        var text = Objects.requireNonNullElse(ImGui.getClipboardText(), "").strip();
        pasteKey = key;
        try {
            if (text.isEmpty()) {
                throw new IllegalArgumentException("The clipboard is empty");
            }
            if (DropList.isGrouped(key)) {
                DropList.parseGroups(text);
            } else {
                DropList.parseItems(text);
            }
            set(document, record, key, text, "Paste list");
            pasteError = "";
        } catch (IllegalArgumentException unreadable) {
            pasteError = "The clipboard is not a list of this kind: " + unreadable.getMessage();
        }
    }

    private void drawSharedList(NpcDataDocument document, int record, String key) {
        var current = document.getValue(record, key);
        var original = document.isAdded(record)
                ? current
                : document.getFile().record(record).getValue(key);
        if (current == null || current.equals("{}") || original == null) {
            return;
        }
        var index = dataIndex(document);
        var state = document.getState();
        var same = new ArrayList<>(index.findSameList(state, key, current));
        same.remove((Integer) record);
        List<Integer> before = List.of();
        if (!original.equals(current) && !original.equals("{}")) {
            var had = new ArrayList<>(index.findSameList(state, key, original));
            had.remove((Integer) record);
            before = had;
        }
        if (same.isEmpty() && before.isEmpty()) {
            return;
        }
        space(GeodataWidgets.ROW);
        var others = before.isEmpty() ? same : before;
        ImGui.alignTextToFramePadding();
        text(
                GeodataWidgets.FAINT,
                before.isEmpty()
                        ? count(same.size(), "other NPC") + (same.size() == 1 ? " has" : " have") + " exactly this list"
                        : count(before.size(), "other NPC")
                                + (before.size() == 1 ? " has" : " have")
                                + " the list as it was");
        if (before.isEmpty()) {
            ImGui.sameLine(0, 8);
        }
        if (HudWidgets.button("npc-shared-show-" + key, null, "Show", 52, 22, false, true)) {
            var noun = settingName(key).replaceAll(" · .*", "").toLowerCase(Locale.ROOT);
            openListLookup(
                    document,
                    key,
                    others,
                    before.isEmpty() ? "NPCs with this " + noun + " list" : "NPCs that had this " + noun + " list");
        }
        if (!before.isEmpty()) {
            ImGui.sameLine(0, 4);
            var targets = List.copyOf(before);
            if (HudWidgets.button("npc-shared-apply-" + key, null, "Apply to them too", 124, 22, true, true)) {
                var noun = settingName(key).replace(" · " + key, "").toLowerCase(Locale.ROOT);
                applyBatchTo(
                        document,
                        "Copy " + noun + " list",
                        noun + " list as on " + document.record(record).name(),
                        Set.of(key),
                        state2 -> {
                            var next = state2;
                            for (var other : targets) {
                                next = document.with(next, other, key, current);
                            }
                            return next;
                        });
            }
            GeodataWidgets.tip("Give these NPCs the edited list as well, as one step.");
        }
    }

    private void applyBatchTo(
            NpcDataDocument document, String action, String noun, Set<String> keys, UnaryOperator<State> edit) {
        var before = document.getState();
        var after = edit.apply(before);
        var changed = NpcBatchEdits.findChangedRecords(before, after);
        if (changed.isEmpty()) {
            return;
        }
        document.apply(action + " to " + count(changed.size(), "NPC"), unused -> after, new Batch(noun, changed, keys));
        lastEditKey = "";
    }

    private void drawExpectation(NpcDataDocument document, NpcRecord record) {
        var index = record.getIndex();
        Expected expected;
        try {
            var drop = DropList.expectedGroups(
                    DropList.parseGroups(Objects.requireNonNullElse(document.getValue(index, DropList.DROP), "{}")));
            var extra = DropList.expectedItems(
                    DropList.parseItems(Objects.requireNonNullElse(document.getValue(index, DropList.EXTRA), "{}")));
            expected = new Expected(drop.adena() + extra.adena(), drop.drops() + extra.drops());
        } catch (IllegalArgumentException unreadable) {
            return;
        }
        if (expected.adena() <= 0 && expected.drops() <= 0) {
            return;
        }
        space(GeodataWidgets.SECTION);
        text(GeodataWidgets.MUTED, "Expected per kill: " + expectedText(expected));
        GeodataWidgets.tip(
                "On average: adena is chance × the middle of its count; items count how many drop, from the drop and"
                        + " the extra drop. Spoil and herbs are not counted.");
        if (!spawns.isReady()) {
            return;
        }
        var summary = spawns.getSummary(record.name());
        if (summary.killsPerHour() <= 0) {
            return;
        }
        var kills = summary.killsPerHour();
        ImGui.pushTextWrapPos(0);
        text(
                GeodataWidgets.MUTED,
                String.format(
                        Locale.ROOT,
                        "Per hour with %,d %s%s: %s",
                        summary.npcs(),
                        summary.npcs() == 1 ? "spawn" : "spawns",
                        summary.respawn().isEmpty() ? "" : " and " + summary.respawn() + " respawn",
                        expectedText(expected.times(kills))));
        ImGui.popTextWrapPos();
        GeodataWidgets.tip(String.format(
                Locale.ROOT,
                "Kills per hour = count × 3600 / respawn seconds, for each spawn entry of npcpos.txt: %,.0f kills, as"
                    + " if each NPC were killed as soon as it is back. Respawns that only add a random part count by"
                    + " their fixed part.",
                kills));
    }

    private void drawGroups(NpcDataDocument document, int record, String key, String id, boolean drop) {
        List<Group> groups;
        try {
            groups = DropList.parseGroups(Objects.requireNonNullElse(document.getValue(record, key), "{}"));
        } catch (IllegalArgumentException unreadable) {
            drawUnreadableNote(key, unreadable);
            return;
        }
        space(GeodataWidgets.ROW);
        if (groups.isEmpty()) {
            text(GeodataWidgets.FAINT, drop ? "Drops nothing." : "No herbs.");
        }
        for (var g = 0; g < groups.size(); g++) {
            drawGroupCard(document, record, key, id, groups, g);
            space(GeodataWidgets.ROW);
        }
        if (GeodataWidgets.actionButton(
                "npc-" + id + "-add-group", Glyph.PLUS, drop ? "New drop group" : "New herb group", -1, false)) {
            openItemPicker(name -> {
                var current = new ArrayList<>(
                        DropList.parseGroups(Objects.requireNonNullElse(document.getValue(record, key), "{}")));
                current.add(new Group(10, List.of(new Item(name, 1, 1, 100))));
                set(document, record, key, DropList.formatGroups(current), "Add group");
                expanded = id + "/" + (current.size() - 1) + "/0";
            });
        }
        GeodataWidgets.tip("Choose the first item of a new group. The group starts at a 10% chance.");
    }

    private String groupTitle(Group group) {
        if (group.items().isEmpty()) {
            return formatPercent(group.chance()) + " · empty";
        }
        var first = group.items().getFirst();
        return formatPercent(group.chance())
                + " · "
                + itemLabel(first.name())
                + " "
                + NpcDataReview.formatAmount(first)
                + (group.items().size() > 1 ? " +" + (group.items().size() - 1) : "");
    }

    private void drawGroupCard(NpcDataDocument document, int record, String key, String id, List<Group> groups, int g) {
        var group = groups.get(g);
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.pushID(id + "/" + g);
        var fieldWidth = 64f;
        var titleWidth = width - fieldWidth - 90;
        ImGui.setCursorScreenPos(x + 10, y + 8);
        ImGui.alignTextToFramePadding();
        text(GeodataWidgets.TEXT, HudWidgets.truncateToWidth(groupTitle(group), titleWidth));
        GeodataWidgets.tip("Group " + (g + 1)
                + ": rolled once per kill with this chance. When it hits, it gives one of its items.");
        ImGui.setCursorScreenPos(x + width - fieldWidth - 62, y + 8);
        numberField("chance", group.chance(), fieldWidth, value -> {
            var next = new ArrayList<>(groups);
            next.set(g, group.withChance(value));
            edit(id + "/" + g + "/chance", document, record, key, DropList.formatGroups(next), "Group chance");
        });
        GeodataWidgets.tip("The group's chance per kill, in percent.");
        ImGui.sameLine(0, 4);
        ImGui.alignTextToFramePadding();
        text(GeodataWidgets.MUTED, "%");
        ImGui.setCursorScreenPos(x + width - 34, y + 8);
        if (HudWidgets.button("menu", Glyph.MENU, "", 26, 26, false, false)) {
            ImGui.openPopup("group-menu");
        }
        GeodataWidgets.tip("More for this group");
        if (ImGui.beginPopup("group-menu")) {
            if (ImGui.menuItem(
                    "Make items add up to 100%", "", false, !group.items().isEmpty())) {
                var next = new ArrayList<>(groups);
                next.set(g, group.normalized());
                set(document, record, key, DropList.formatGroups(next), "Even out chances");
            }
            if (ImGui.menuItem("Delete group")) {
                var next = new ArrayList<>(groups);
                next.remove(g);
                set(document, record, key, DropList.formatGroups(next), "Delete group");
                expanded = "";
            }
            ImGui.endPopup();
        }
        ImGui.setCursorScreenPos(x + 10, y + 42);
        var shares = new double[group.items().size()];
        var colors = new int[shares.length];
        var locked = new boolean[shares.length];
        for (var i = 0; i < shares.length; i++) {
            var item = group.items().get(i);
            shares[i] = item.chance();
            var server = source.getItems().byName(item.name());
            colors[i] = ItemKind.of(item.name(), server == null ? "" : server.type()).color;
            locked[i] = lockedShares.contains(lockKey(record, key, g, item.name()));
        }
        var drag = NpcPanelWidgets.dropBar("bar", width - 20, group.chance(), shares, colors, locked);
        GeodataWidgets.tip(
                "The bar is the group, cut by its items' shares. Drag a line between two items to move share from one"
                        + " to the other. Lock an item in its editor to keep its share. The thin line under it is the"
                        + " group's chance, the whole width being 100%.");
        if (drag != null) {
            var next = new ArrayList<>(groups);
            next.set(g, group.rebalanced(drag.left(), drag.share()));
            var value = DropList.formatGroups(next);
            if (barDragging) {
                document.amend(state -> document.with(state, record, key, value));
            } else {
                document.apply("Item shares", state -> document.with(state, record, key, value));
                barDragging = true;
            }
            if (drag.released()) {
                barDragging = false;
                lastEditKey = "";
            }
        } else if (barDragging && !ImGui.isMouseDown(0)) {
            barDragging = false;
        }
        ImGui.setCursorScreenPos(x + 4, y + 42 + NpcPanelWidgets.BAR_HEIGHT + 8);
        ImGui.beginGroup();
        ImGui.pushItemWidth(width - 8);
        var expectedPieces = group.expectedPieces();
        for (var i = 0; i < group.items().size(); i++) {
            var item = group.items().get(i);
            var rowKey = id + "/" + g + "/" + i;
            var perKill = group.chance() * item.chance() / 100;
            final var at = i;
            drawItemEntry(
                    item,
                    rowKey,
                    NpcDataReview.formatAmount(item) + " · " + formatPercent(item.chance()) + " of the group",
                    formatPercent(perKill),
                    "Chance per kill: "
                            + formatPercent(group.chance())
                            + " for the group × "
                            + formatPercent(item.chance())
                            + " for this item."
                            + (oneIn(perKill, "kills").isEmpty()
                                    ? ""
                                    : " About" + oneIn(perKill, "kills").substring(2) + "."),
                    changed -> {
                        var next = new ArrayList<>(groups);
                        if (changed == null) {
                            var left = group.withoutItem(at);
                            next.set(g, left.normalizedKeeping(findLockedItems(record, key, g, left)));
                        } else {
                            next.set(g, group.withItem(at, changed));
                        }
                        return DropList.formatGroups(next);
                    },
                    document,
                    record,
                    key,
                    lockKey(record, key, g, item.name()));
        }
        var sum = group.itemChanceSum();
        if (!group.items().isEmpty() && Math.abs(sum - 100) > .01) {
            space(GeodataWidgets.ROW);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + 6);
            ImGui.alignTextToFramePadding();
            text(GeodataWidgets.WARNING, "Items add up to " + formatPercent(sum));
            ImGui.sameLine(0, 8);
            if (HudWidgets.button("fix", null, "Make 100%", 92, 24, false, true)) {
                var next = new ArrayList<>(groups);
                next.set(g, group.normalized());
                set(document, record, key, DropList.formatGroups(next), "Even out chances");
            }
            GeodataWidgets.tip(
                    "Scales the items' chances to add up to 100%, keeping how they compare. The server picks one item"
                            + " of a group that hits by these chances.");
        }
        if (!group.items().isEmpty()) {
            space(GeodataWidgets.ROW);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + 6);
            var parts = new ArrayList<String>();
            for (var i = 0; i < group.items().size(); i++) {
                parts.add(formatCount(expectedPieces[i] * 100) + " "
                        + itemLabel(group.items().get(i).name()));
            }
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + width - 20);
            text(GeodataWidgets.FAINT, "Per 100 kills: " + String.join(", ", parts));
            ImGui.popTextWrapPos();
            GeodataWidgets.tip(
                    "Pieces on average: group chance × item share × the middle of its count, for 100 kills.");
        }
        space(GeodataWidgets.ROW);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + 4);
        if (HudWidgets.button("add", Glyph.PLUS, "Add item", width - 16, 28, false, false)) {
            openRepeatableItemPicker(name -> {
                var current = DropList.parseGroups(Objects.requireNonNullElse(document.getValue(record, key), "{}"));
                if (g < current.size()) {
                    var target = current.get(g);
                    current.set(
                            g, target.withAddedItem(new Item(name, 1, 1, 0), findLockedItems(record, key, g, target)));
                    set(document, record, key, DropList.formatGroups(current), "Add item");
                    expanded = id + "/" + g + "/" + target.items().size();
                }
            });
        }
        GeodataWidgets.tip(
                "Choose an item for this group. It gets what the group's items leave of 100%; in a full group an even"
                    + " share, which the others give up by their own shares, locked ones keeping theirs. In the picker"
                    + " Shift+Enter or Shift-click adds and keeps it open for the next one.");
        ImGui.popItemWidth();
        ImGui.endGroup();
        var bottom = ImGui.getItemRectMaxY() + 8;
        ImGui.popID();
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.addRect(x, y, x + width, bottom, GeodataWidgets.DIVIDER, 8, 0, 1);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
    }

    private Set<Integer> findLockedItems(int record, String key, int group, Group items) {
        var locked = new HashSet<Integer>();
        for (var i = 0; i < items.items().size(); i++) {
            if (lockedShares.contains(
                    lockKey(record, key, group, items.items().get(i).name()))) {
                locked.add(i);
            }
        }
        return locked;
    }

    private void drawItems(NpcDataDocument document, int record, String key, String id, String per) {
        List<Item> items;
        try {
            items = DropList.parseItems(Objects.requireNonNullElse(document.getValue(record, key), "{}"));
        } catch (IllegalArgumentException unreadable) {
            drawUnreadableNote(key, unreadable);
            return;
        }
        space(GeodataWidgets.ROW);
        if (items.isEmpty()) {
            text(GeodataWidgets.FAINT, "Nothing.");
        }
        for (var i = 0; i < items.size(); i++) {
            var item = items.get(i);
            final var at = i;
            drawItemEntry(
                    item,
                    id + "/" + i,
                    NpcDataReview.formatAmount(item) + " · " + per
                            + oneIn(item.chance(), key.equals(DropList.SPOIL) ? "spoils" : "kills"),
                    formatPercent(item.chance()),
                    "",
                    changed -> {
                        var next = new ArrayList<>(items);
                        if (changed == null) {
                            next.remove(at);
                        } else {
                            next.set(at, changed);
                        }
                        return DropList.formatItems(next);
                    },
                    document,
                    record,
                    key,
                    null);
        }
        space(GeodataWidgets.ROW);
        if (GeodataWidgets.actionButton("npc-" + id + "-add", Glyph.PLUS, "Add item", -1, false)) {
            openRepeatableItemPicker(name -> {
                var current = new ArrayList<>(
                        DropList.parseItems(Objects.requireNonNullElse(document.getValue(record, key), "{}")));
                current.add(new Item(name, 1, 1, 10));
                set(document, record, key, DropList.formatItems(current), "Add item");
                expanded = id + "/" + (current.size() - 1);
            });
        }
        GeodataWidgets.tip(
                "Choose an item. It starts with one piece at a 10% chance. In the picker Shift+Enter or Shift-click "
                        + "adds and keeps it open for the next one.");
    }

    private void drawItemEntry(
            Item item,
            String rowKey,
            String detail,
            String value,
            String valueTip,
            ItemChange change,
            NpcDataDocument document,
            int record,
            String key,
            @Nullable String lockKey) {
        var server = source.getItems().byName(item.name());
        var client = server == null ? null : source.getCatalog().getItem(server.id());
        var title = itemTitle(item.name(), client);
        var known = server != null || source.getItems().size() == 0;
        var open = rowKey.equals(expanded);
        var locked = lockKey != null && lockedShares.contains(lockKey);
        ImGui.pushID(rowKey);
        if (NpcPanelWidgets.iconRow(
                "row",
                iconOf(client),
                Glyph.BOX,
                title + (locked ? " · locked" : ""),
                known ? GeodataWidgets.TEXT : GeodataWidgets.WARNING,
                known ? detail : "Not in itemdata.txt · " + item.name(),
                value,
                GeodataWidgets.TEXT,
                open,
                true)) {
            expanded = open ? "" : rowKey;
        }
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(itemTip(item.name(), server, client)
                    + (valueTip.isEmpty() ? "" : "\n\n" + valueTip)
                    + "\n\nRight-click: who else drops it");
        }
        if (ImGui.beginPopupContextItem("row-menu")) {
            if (ImGui.menuItem("Dropped by…")) {
                openItemLookup(document, item.name());
            }
            ImGui.endPopup();
        }
        if (open) {
            drawItemEditor(item, rowKey, change, document, record, key, lockKey);
        }
        ImGui.popID();
    }

    private void drawItemEditor(
            Item item,
            String rowKey,
            ItemChange change,
            NpcDataDocument document,
            int record,
            String key,
            @Nullable String lockKey) {
        space(GeodataWidgets.ROW);
        var left = ImGui.getCursorPosX();
        ImGui.setCursorPosX(left + 8);
        ImGui.beginGroup();
        var inner = ImGui.getContentRegionAvailX() - 8;
        var spacing = ImGui.getStyle().getItemSpacingX();
        var column = (inner - spacing * 2) / 3;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        text(GeodataWidgets.MUTED, "Min");
        ImGui.setCursorScreenPos(x + column + spacing, y);
        text(GeodataWidgets.MUTED, "Max");
        ImGui.setCursorScreenPos(x + 2 * (column + spacing), y);
        text(GeodataWidgets.MUTED, "Chance %");
        var fieldsY = ImGui.getCursorScreenPosY();
        ImGui.setCursorScreenPos(x, fieldsY);
        wholeField(
                "min",
                item.min(),
                column,
                value -> edit(
                        rowKey + "/min",
                        document,
                        record,
                        key,
                        change.apply(item.withCount(value, Math.max(value, item.max()))),
                        "Item count"));
        GeodataWidgets.tip("The fewest pieces it gives. Tab moves on to Max and Chance.");
        ImGui.setCursorScreenPos(x + column + spacing, fieldsY);
        wholeField(
                "max",
                item.max(),
                column,
                value -> edit(
                        rowKey + "/max",
                        document,
                        record,
                        key,
                        change.apply(item.withCount(Math.min(item.min(), value), value)),
                        "Item count"));
        GeodataWidgets.tip("The most pieces it gives.");
        ImGui.setCursorScreenPos(x + 2 * (column + spacing), fieldsY);
        numberField(
                "chance",
                item.chance(),
                column,
                value -> edit(
                        rowKey + "/chance",
                        document,
                        record,
                        key,
                        change.apply(item.withChance(value)),
                        "Item chance"));
        GeodataWidgets.tip("In percent, from 0.0001 to 100.");
        space(GeodataWidgets.ROW);
        var labels = new String[] {"Other item", "Dropped by", "Remove"};
        var widths = new float[labels.length];
        var rest = inner - 2 * spacing;
        for (var i = 0; i < labels.length; i++) {
            widths[i] = ImGui.calcTextSizeX(labels[i]) + 16;
            rest -= widths[i];
        }
        for (var i = 0; i < labels.length; i++) {
            widths[i] = rest >= 0 ? widths[i] + rest / labels.length : (inner - 2 * spacing) / labels.length;
        }
        if (HudWidgets.button("change", Glyph.BOX, labels[0], widths[0], 28, false, true)) {
            openItemPicker(name -> set(document, record, key, change.apply(item.withName(name)), "Change item"));
        }
        GeodataWidgets.tip("Keep the count and chance, change the item.");
        ImGui.sameLine();
        if (HudWidgets.button("dropped-by", Glyph.SEARCH, labels[1], widths[1], 28, false, true)) {
            openItemLookup(document, item.name());
        }
        GeodataWidgets.tip("Every NPC that drops, spoils or makes this item.");
        ImGui.sameLine();
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.BLOCKED);
        if (HudWidgets.button("remove", Glyph.CLOSE, labels[2], widths[2], 28, false, true)) {
            set(document, record, key, change.apply(null), "Remove item");
            expanded = "";
        }
        ImGui.popStyleColor();
        if (lockKey != null) {
            var locked = lockedShares.contains(lockKey);
            if (ImGui.checkbox("Lock its share on the bar##lock", locked)) {
                if (locked) {
                    lockedShares.remove(lockKey);
                } else {
                    lockedShares.add(lockKey);
                }
            }
            GeodataWidgets.tip(
                    "Dragging the group's bar leaves a locked item's share as it is. The field above still changes"
                            + " it.");
        }
        ImGui.endGroup();
        space(GeodataWidgets.ROW);
    }

    private void drawSkillsTab(NpcDataDocument document, NpcRecord record) {
        var index = record.getIndex();
        var other = comparedRecord(document, record);
        if (other != null) {
            drawSkillComparison(document, record, other);
        }
        GeodataWidgets.section("SKILL LIST", other == null ? GeodataWidgets.BLOCK : GeodataWidgets.SECTION);
        GeodataWidgets.tip(
                "skill_list: the skills the NPC has. Servers put its passive skills here: race, levels of HP, attack"
                    + " and defence, weapon and armour type, resistances. Any skill of skilldata.txt can be added; the"
                    + " AI casts only the skills its settings name, listed below.");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/skill");
        if (record.has(NpcValues.SKILLS)) {
            List<String> skills;
            try {
                skills = new ArrayList<>(NpcValues.parseSkills(
                        Objects.requireNonNullElse(document.getValue(index, NpcValues.SKILLS), "{}")));
            } catch (IllegalArgumentException unreadable) {
                drawUnreadableNote(NpcValues.SKILLS, unreadable);
                return;
            }
            space(GeodataWidgets.ROW);
            if (skills.isEmpty()) {
                text(GeodataWidgets.FAINT, "No skills.");
            }
            for (var i = 0; i < skills.size(); i++) {
                final var at = i;
                var name = skills.get(i);
                drawSkillEntry(
                        name,
                        "skill/" + i,
                        "",
                        () -> {
                            var next = new ArrayList<>(skills);
                            next.remove(at);
                            set(document, index, NpcValues.SKILLS, NpcValues.formatSkills(next), "Remove skill");
                            expanded = "";
                        },
                        chosen -> {
                            var next = new ArrayList<>(skills);
                            next.set(at, chosen);
                            set(document, index, NpcValues.SKILLS, NpcValues.formatSkills(next), "Change skill");
                        },
                        0);
            }
            space(GeodataWidgets.ROW);
            if (GeodataWidgets.actionButton("npc-skill-add", Glyph.PLUS, "Add skill", -1, false)) {
                openSkillPicker(0, chosen -> {
                    var current = new ArrayList<>(NpcValues.parseSkills(
                            Objects.requireNonNullElse(document.getValue(index, NpcValues.SKILLS), "{}")));
                    if (!current.contains(chosen)) {
                        current.add(chosen);
                        set(document, index, NpcValues.SKILLS, NpcValues.formatSkills(current), "Add skill");
                    }
                });
            }
            GeodataWidgets.tip(
                    "Choose a skill of skilldata.txt. The list shows passive skills first, as skill_list holds them.");
        } else {
            space(GeodataWidgets.ROW);
            text(GeodataWidgets.FAINT, "No skill_list in this record.");
        }
        GeodataWidgets.section("SKILLS ITS AI CASTS");
        GeodataWidgets.tip("Settings of npc_ai that name a skill with @. The AI script decides when it casts each one; "
                + "change the skill here, the setting stays.");
        drawAiSkills(document, record);
    }

    private void drawAiSkills(NpcDataDocument document, NpcRecord record) {
        var index = record.getIndex();
        var value = document.getValue(index, NpcValues.AI);
        space(GeodataWidgets.ROW);
        if (value == null) {
            text(GeodataWidgets.FAINT, "No npc_ai in this record.");
            return;
        }
        Ai ai;
        try {
            ai = NpcValues.parseAi(value);
        } catch (IllegalArgumentException unreadable) {
            drawUnreadableNote(NpcValues.AI, unreadable);
            return;
        }
        var shown = 0;
        for (var i = 0; i < ai.parameters().size(); i++) {
            var parameter = ai.parameters().get(i);
            var skill = parameter.skill();
            if (skill.isEmpty()
                    || source.getSkills().size() > 0 && source.getSkills().byName(skill) == null) {
                continue;
            }
            shown++;
            final var at = i;
            drawSkillEntry(
                    skill,
                    "ai/" + i,
                    parameter.name(),
                    null,
                    chosen -> set(
                            document,
                            index,
                            NpcValues.AI,
                            NpcValues.formatAi(ai.withParameter(at, new AiParameter(parameter.name(), "@" + chosen))),
                            "Change AI skill"),
                    1);
        }
        if (shown == 0) {
            text(
                    GeodataWidgets.FAINT,
                    "Its AI " + (ai.name().isEmpty() ? "" : "(" + ai.name() + ") ") + "names no skills.");
        } else {
            space(GeodataWidgets.ROW);
            ImGui.alignTextToFramePadding();
            text(GeodataWidgets.FAINT, "AI: " + ai.name());
            ImGui.sameLine(0, 8);
            if (HudWidgets.button("npc-open-ai", null, "All its settings", 120, 22, false, true)) {
                tab = Tab.AI;
                expanded = "";
            }
            GeodataWidgets.tip("Every setting of its AI, in the AI tab.");
        }
    }

    private void drawSkillEntry(
            String name,
            String rowKey,
            String setting,
            @Nullable Runnable remove,
            Consumer<String> change,
            int pickerFilter) {
        var server = source.getSkills().byName(name);
        var client = server == null ? null : source.getCatalog().getSkill(server.id(), server.level());
        var title = skillTitle(name, server, client);
        var known = server != null || source.getSkills().size() == 0;
        var kindLabel = server == null ? "" : describeOperateType(server.operateType());
        var detail = (setting.isEmpty() ? "" : setting + " · ") + name + (kindLabel.isEmpty() ? "" : " · " + kindLabel);
        var open = rowKey.equals(expanded);
        ImGui.pushID(rowKey);
        if (NpcPanelWidgets.iconRow(
                "row",
                client == null ? 0 : icons.getTexture(client.icon()),
                Glyph.SPARKLES,
                title,
                known ? GeodataWidgets.TEXT : GeodataWidgets.WARNING,
                known ? detail : "Not in skilldata.txt · " + name,
                "",
                0,
                open,
                true)) {
            expanded = open ? "" : rowKey;
        }
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(skillTip(name, server, client) + "\n\nRight-click: who else has it");
        }
        var document = source.getDocument();
        if (ImGui.beginPopupContextItem("row-menu")) {
            if (ImGui.menuItem("Known by…") && document != null) {
                openSkillLookup(document, name);
            }
            ImGui.endPopup();
        }
        if (open) {
            space(GeodataWidgets.ROW);
            var inner = ImGui.getContentRegionAvailX();
            var spacing = ImGui.getStyle().getItemSpacingX();
            var width = remove == null ? (inner - spacing) / 2 : (inner - 2 * spacing) / 3;
            if (HudWidgets.button("change", Glyph.SPARKLES, "Other skill", width, 28, false, true)) {
                openSkillPicker(pickerFilter, change);
            }
            ImGui.sameLine();
            if (HudWidgets.button("known-by", Glyph.SEARCH, "Known by", width, 28, false, true) && document != null) {
                openSkillLookup(document, name);
            }
            GeodataWidgets.tip("Every NPC that has this skill in its skill list or its AI.");
            if (remove != null) {
                ImGui.sameLine();
                ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.BLOCKED);
                if (HudWidgets.button("remove", Glyph.CLOSE, "Remove", width, 28, false, true)) {
                    remove.run();
                }
                ImGui.popStyleColor();
            }
            space(GeodataWidgets.ROW);
        }
        ImGui.popID();
    }

    private void drawAiTab(NpcDataDocument document, NpcRecord record) {
        var index = record.getIndex();
        GeodataWidgets.section("AI", GeodataWidgets.BLOCK);
        GeodataWidgets.tip(
                "npc_ai={[name];{[Setting]=value};...}: the AI class the server runs for this NPC, and the settings it"
                    + " reads. The class itself is in the server's compiled AI; most NPCs have one named after them.");
        var value = document.getValue(index, NpcValues.AI);
        space(GeodataWidgets.ROW);
        if (value == null) {
            text(GeodataWidgets.FAINT, "No npc_ai in this record.");
            return;
        }
        Ai ai;
        try {
            ai = NpcValues.parseAi(value);
        } catch (IllegalArgumentException unreadable) {
            drawUnreadableNote(NpcValues.AI, unreadable);
            return;
        }
        var catalog = aiCatalog(document);
        var known = catalog.getAi(ai.name());
        var uses = known == null ? 0 : known.npcs();
        if (NpcPanelWidgets.iconRow(
                "npc-ai-name",
                0,
                Glyph.CARD,
                ai.name().isEmpty() ? "No AI" : ai.name(),
                GeodataWidgets.TEXT,
                uses <= 1
                        ? "Only this NPC has it · click for another"
                        : String.format(Locale.ROOT, "%,d NPCs have it · click for another", uses),
                "",
                0,
                false,
                true)) {
            openAiPicker(
                    document,
                    name -> set(
                            document,
                            index,
                            NpcValues.AI,
                            NpcValues.formatAi(currentAi(document, index).withName(name)),
                            "AI"));
        }
        GeodataWidgets.tip(
                "The AI class. Choose one of those the file's NPCs use, most used first; its settings stay.");
        GeodataWidgets.section("SETTINGS");
        GeodataWidgets.tip(
                "Settings the AI class reads. Rates and Social settings are numbers, Skill settings and values with @ "
                        + "name a skill, values in brackets name something such as a sound.");
        space(GeodataWidgets.ROW);
        if (ai.parameters().isEmpty()) {
            text(GeodataWidgets.FAINT, "No settings: the AI uses its own defaults.");
        }
        for (var i = 0; i < ai.parameters().size(); i++) {
            drawAiParameter(document, index, ai, i);
        }
        space(GeodataWidgets.ROW);
        if (GeodataWidgets.actionButton("npc-ai-add", Glyph.PLUS, "Add setting", -1, false)) {
            var present = new ArrayList<String>();
            ai.parameters().forEach(parameter -> present.add(parameter.name()));
            openParameterPicker(catalog.suggestParameters(ai.name(), present), name -> {
                var current = currentAi(document, index);
                var usual = "0";
                for (var parameter : catalog.getParameters()) {
                    if (parameter.name().equals(name)) {
                        usual = parameter.usual();
                    }
                }
                set(
                        document,
                        index,
                        NpcValues.AI,
                        NpcValues.formatAi(
                                current.withParameter(current.parameters().size(), new AiParameter(name, usual))),
                        "Add AI setting");
            });
        }
        GeodataWidgets.tip(
                "Settings other NPCs with this AI use come first, then the rest of the file's, most used first. "
                        + "A new setting starts with its most used value.");
    }

    private Ai currentAi(NpcDataDocument document, int index) {
        return NpcValues.parseAi(Objects.requireNonNullElse(document.getValue(index, NpcValues.AI), "{[]}"));
    }

    private void drawAiParameter(NpcDataDocument document, int index, Ai ai, int at) {
        var parameter = ai.parameters().get(at);
        var kind = NpcAiCatalog.kind(parameter.name(), parameter.value());
        ImGui.pushID("ai-setting/" + at);
        if (kind == ValueKind.SKILL
                && (parameter.value().startsWith("@") || parameter.value().isEmpty())) {
            var skill = parameter.skill();
            drawSkillEntry(
                    skill.isEmpty() ? "(none)" : skill,
                    "ai-setting/" + at,
                    parameter.name(),
                    () -> {
                        set(
                                document,
                                index,
                                NpcValues.AI,
                                NpcValues.formatAi(currentAi(document, index).withoutParameter(at)),
                                "Remove AI setting");
                        expanded = "";
                    },
                    chosen -> set(
                            document,
                            index,
                            NpcValues.AI,
                            NpcValues.formatAi(currentAi(document, index)
                                    .withParameter(at, new AiParameter(parameter.name(), "@" + chosen))),
                            "Change AI skill"),
                    2);
            ImGui.popID();
            return;
        }
        var width = ImGui.getContentRegionAvailX();
        ImGui.alignTextToFramePadding();
        var label = HudWidgets.truncateToWidth(parameter.name(), width * .5f - 8);
        text(GeodataWidgets.MUTED, label);
        GeodataWidgets.tip(parameter.name()
                + switch (kind) {
                    case NUMBER -> ": a number";
                    case NAME -> ": a name in brackets";
                    default -> "";
                });
        ImGui.sameLine(width * .5f);
        textField("ai-value/" + index + "/" + at, parameter.value(), width * .5f - 30, typed -> {
            var text = typed.strip();
            if (text.isEmpty()) {
                return;
            }
            if (kind == ValueKind.NUMBER) {
                try {
                    Double.parseDouble(text);
                } catch (NumberFormatException notNumber) {
                    return;
                }
            }
            editState(
                    "ai/" + at,
                    document,
                    index,
                    "AI setting",
                    state -> document.with(
                            state,
                            index,
                            NpcValues.AI,
                            NpcValues.formatAi(NpcValues.parseAi(Objects.requireNonNullElse(
                                            document.getValue(state, index, NpcValues.AI), "{[]}"))
                                    .withParameter(at, new AiParameter(parameter.name(), text)))));
        });
        ImGui.sameLine(0, 4);
        if (HudWidgets.button("remove", Glyph.CLOSE, "", 26, ImGui.getFrameHeight(), false, false)) {
            set(
                    document,
                    index,
                    NpcValues.AI,
                    NpcValues.formatAi(currentAi(document, index).withoutParameter(at)),
                    "Remove AI setting");
        }
        GeodataWidgets.tip("Remove this setting: the AI then uses its own default.");
        ImGui.popID();
    }

    private void drawStatsTab(NpcDataDocument document, NpcRecord record) {
        var index = record.getIndex();
        var first = true;
        var other = comparedRecord(document, record);
        if (other != null) {
            drawComparison(document, record, other);
            first = false;
        }
        for (var group : STATS) {
            var fields = group.fields().stream()
                    .filter(field -> record.has(field.key()))
                    .toList();
            if (fields.isEmpty()) {
                continue;
            }
            GeodataWidgets.section(group.title(), first ? GeodataWidgets.BLOCK : GeodataWidgets.SECTION);
            first = false;
            space(GeodataWidgets.ROW);
            var numbers = new ArrayList<StatField>();
            for (var field : fields) {
                switch (field.kind()) {
                    case NUMBER, TEXT, CHOICE, FIXED -> numbers.add(field);
                    case FLAG -> drawFlag(document, index, field);
                    case ITEM -> drawSlot(document, index, field);
                }
            }
            var column = GeodataWidgets.columnWidth(2);
            for (var i = 0; i < numbers.size(); i += 2) {
                var left = numbers.get(i);
                var right = i + 1 < numbers.size() ? numbers.get(i + 1) : null;
                GeodataWidgets.columns(
                        () -> statField(document, record, left, column),
                        () -> right == null || statField(document, record, right, column));
                space(GeodataWidgets.ROW);
            }
            if (group.title().equals("BASICS")) {
                drawRaceSkillOffer(document, index);
            }
            if (group.title().equals("REWARDS")) {
                drawReward(document, index);
            }
            if (group.title().equals("EQUIPMENT")) {
                drawHeldWeaponNote(record, fields);
            }
        }
        space(GeodataWidgets.SECTION);
        text(GeodataWidgets.FAINT, "Every other setting is under All.");
    }

    private void drawHeldWeaponNote(NpcRecord record, List<StatField> fields) {
        var weapons = fields.stream()
                .anyMatch(
                        field -> field.key().equals("slot_rhand") || field.key().equals("slot_lhand"));
        if (!weapons || spawns.getHeldItems(record.getId()) != HeldItems.PART_OF_MESH) {
            return;
        }
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(
                "The game does not show a weapon set here: this NPC's mesh has no bone for it. The server still adds"
                        + " the weapon's stats, such as P.Atk, M.Atk, attack speed and critical.",
                GeodataWidgets.WARNING);
    }

    private @Nullable NpcRecord comparedRecord(NpcDataDocument document, NpcRecord record) {
        return compareWith >= 0
                        && compareWith != record.getIndex()
                        && compareWith < document.size()
                        && !document.getState().isRemoved(compareWith)
                ? document.record(compareWith)
                : null;
    }

    private record CompareRow(String label, String mine, String theirs, boolean caption, String tip) {
        static CompareRow of(String label, String mine, String theirs) {
            return new CompareRow(label, mine, theirs, false, "");
        }

        static CompareRow of(String label, String mine, String theirs, String tip) {
            return new CompareRow(label, mine, theirs, false, tip);
        }

        static CompareRow caption(String label) {
            return new CompareRow(label, "", "", true, "");
        }

        boolean differs() {
            return !caption && !mine.equals(theirs);
        }
    }

    private void drawComparison(NpcDataDocument document, NpcRecord record, NpcRecord other) {
        var rows = new ArrayList<CompareRow>();
        for (var group : STATS) {
            for (var field : group.fields()) {
                if (field.kind() == FieldKind.FLAG
                        || field.kind() == FieldKind.ITEM
                        || !record.has(field.key()) && !other.has(field.key())) {
                    continue;
                }
                rows.add(CompareRow.of(
                        field.label(),
                        Objects.requireNonNullElse(document.getValue(record.getIndex(), field.key()), NONE),
                        Objects.requireNonNullElse(document.getValue(other.getIndex(), field.key()), NONE)));
            }
        }
        compareTable(other, rows, "stat", .36f);
    }

    private void dropComparison(NpcDataDocument document, NpcRecord record, NpcRecord other) {
        var rows = new ArrayList<CompareRow>();
        var mine = perKill(document, record.getIndex());
        var theirs = perKill(document, other.getIndex());
        if (!mine.isEmpty() || !theirs.isEmpty()) {
            rows.add(CompareRow.caption("Drop, per kill"));
            rows.addAll(chanceRows(mine, theirs));
        }
        var mySpoil = perSpoil(document, record.getIndex());
        var theirSpoil = perSpoil(document, other.getIndex());
        if (!mySpoil.isEmpty() || !theirSpoil.isEmpty()) {
            rows.add(CompareRow.caption("Spoil"));
            rows.addAll(chanceRows(mySpoil, theirSpoil));
        }
        if (rows.isEmpty()) {
            compareTitle();
            text(GeodataWidgets.FAINT, "Neither NPC drops anything.");
            return;
        }
        compareTable(other, rows, "item", .5f);
    }

    private void drawSkillComparison(NpcDataDocument document, NpcRecord record, NpcRecord other) {
        var mine = skillsByName(document, record.getIndex());
        var theirs = skillsByName(document, other.getIndex());
        if (mine.isEmpty() && theirs.isEmpty()) {
            compareTitle();
            text(GeodataWidgets.FAINT, "Neither NPC has skills in skill_list.");
            return;
        }
        var rows = new ArrayList<CompareRow>();
        var keys = new LinkedHashSet<String>(mine.keySet());
        keys.addAll(theirs.keySet());
        for (var key : keys) {
            var a = mine.get(key);
            var b = theirs.get(key);
            var label = a != null ? a.label() : b.label();
            rows.add(CompareRow.of(label, skillCell(a, b), skillCell(b, a), skillTip(a, b, other)));
        }
        compareTable(other, rows, "skill", .5f);
    }

    record SkillCell(String label, String name, int id, int level) {}

    private Map<String, SkillCell> skillsByName(NpcDataDocument document, int record) {
        var skills = new LinkedHashMap<String, SkillCell>();
        List<String> names;
        try {
            names = NpcValues.parseSkills(
                    Objects.requireNonNullElse(document.getValue(record, NpcValues.SKILLS), "{}"));
        } catch (IllegalArgumentException unreadable) {
            return skills;
        }
        for (var name : names) {
            var server = source.getSkills().byName(name);
            var client = server == null ? null : source.getCatalog().getSkill(server.id(), server.level());
            var label = client != null && !client.name().isBlank() ? client.name() : name;
            var cell =
                    new SkillCell(label, name, server == null ? -1 : server.id(), server == null ? 0 : server.level());
            var key = label.toLowerCase(Locale.ROOT);
            for (var more = 2; skills.containsKey(key); more++) {
                key = label.toLowerCase(Locale.ROOT) + "#" + more;
            }
            skills.put(key, cell);
        }
        return skills;
    }

    private String skillTip(@Nullable SkillCell mine, @Nullable SkillCell theirs, NpcRecord other) {
        return "This: " + skillSource(mine) + "\n" + displayName(other) + ": " + skillSource(theirs);
    }

    private Map<String, Double> perKill(NpcDataDocument document, int record) {
        var misses = new LinkedHashMap<String, Double>();
        try {
            for (var group :
                    DropList.parseGroups(Objects.requireNonNullElse(document.getValue(record, DropList.DROP), "{}"))) {
                for (var item : group.items()) {
                    var chance = group.chance() * item.chance() / 10_000;
                    misses.merge(item.name(), 1 - chance, (a, b) -> a * b);
                }
            }
            for (var item :
                    DropList.parseItems(Objects.requireNonNullElse(document.getValue(record, DropList.EXTRA), "{}"))) {
                misses.merge(item.name(), 1 - item.chance() / 100, (a, b) -> a * b);
            }
        } catch (IllegalArgumentException unreadable) {
        }
        var chances = new LinkedHashMap<String, Double>();
        misses.forEach((name, miss) -> chances.put(name, (1 - miss) * 100));
        return chances;
    }

    private Map<String, Double> perSpoil(NpcDataDocument document, int record) {
        var chances = new LinkedHashMap<String, Double>();
        try {
            for (var item :
                    DropList.parseItems(Objects.requireNonNullElse(document.getValue(record, DropList.SPOIL), "{}"))) {
                chances.merge(item.name(), item.chance(), Double::sum);
            }
        } catch (IllegalArgumentException unreadable) {
        }
        return chances;
    }

    private List<CompareRow> chanceRows(Map<String, Double> mine, Map<String, Double> theirs) {
        var names = new LinkedHashSet<String>(mine.keySet());
        names.addAll(theirs.keySet());
        var rows = new ArrayList<CompareRow>();
        for (var name : names) {
            rows.add(CompareRow.of(
                    itemLabel(name),
                    mine.containsKey(name) ? formatPercent(mine.get(name)) : NONE,
                    theirs.containsKey(name) ? formatPercent(theirs.get(name)) : NONE));
        }
        return rows;
    }

    private void compareTitle() {
        GeodataWidgets.section("COMPARED WITH", GeodataWidgets.BLOCK);
        ImGui.sameLine(ImGui.getContentRegionMaxX() - 22);
        ImGui.setCursorPosY(ImGui.getCursorPosY() - 4);
        if (HudWidgets.button("npc-compare-close", Glyph.CLOSE, "", 22, 22, false, false)) {
            compareWith = -1;
        }
        GeodataWidgets.tip("Stop comparing.");
        space(GeodataWidgets.ROW);
    }

    private void compareTable(NpcRecord other, List<CompareRow> rows, String noun, float labelShare) {
        compareTitle();
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var width = ImGui.getContentRegionAvailX();
        var labelWidth = width * labelShare;
        var valueWidth = (width - labelWidth) / 2;
        var line = ImGui.getFontSize() + 6;
        var y = ImGui.getCursorScreenPosY();
        var top = y;
        draw.addText(x + labelWidth, y, GeodataWidgets.MUTED, HudWidgets.truncateToWidth("This", valueWidth - 8));
        draw.addText(
                x + labelWidth + valueWidth,
                y,
                GeodataWidgets.MUTED,
                HudWidgets.truncateToWidth(displayName(other), valueWidth - 4));
        y += line;
        var differences = 0;
        var mouseY = ImGui.getMousePosY();
        var hoveredTip = "";
        for (var row : rows) {
            if (!row.caption() && mouseY >= y - 1 && mouseY < y + line - 1) {
                hoveredTip = row.tip();
            }
            if (row.caption()) {
                y += 4;
                draw.addText(x + 4, y, GeodataWidgets.FAINT, HudWidgets.truncateToWidth(row.label(), width - 8));
                y += line;
                continue;
            }
            var differs = row.differs();
            differences += differs ? 1 : 0;
            if (differs) {
                draw.addRectFilled(x - 2, y - 1, x + width, y + line - 3, ImColor.rgba(92, 219, 241, 18), 4f);
            }
            draw.addText(
                    x + 4,
                    y,
                    differs ? GeodataWidgets.TEXT : GeodataWidgets.MUTED,
                    HudWidgets.truncateToWidth(row.label(), labelWidth - 8));
            draw.addText(
                    x + labelWidth, y, GeodataWidgets.TEXT, HudWidgets.truncateToWidth(row.mine(), valueWidth - 8));
            draw.addText(
                    x + labelWidth + valueWidth,
                    y,
                    differs ? GeodataWidgets.ACCENT : GeodataWidgets.MUTED,
                    HudWidgets.truncateToWidth(row.theirs(), valueWidth - 4));
            y += line;
        }
        ImGui.setCursorScreenPos(x, top);
        ImGui.invisibleButton("npc-compare-table", width, Math.max(1, y - top));
        GeodataWidgets.tip(
                !hoveredTip.isEmpty()
                        ? hoveredTip
                        : other.name()
                                + " · id "
                                + other.getId()
                                + ": "
                                + count(differences, noun)
                                + " differ, marked and in the accent color.");
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, 0);
    }

    private void drawRaceSkillOffer(NpcDataDocument document, int index) {
        var race = document.getValue(index, "race");
        var list = document.getValue(index, NpcValues.SKILLS);
        if (race == null || list == null || document.getState().editedValue(index, "race") == null) {
            return;
        }
        List<String> skills;
        try {
            skills = new ArrayList<>(NpcValues.parseSkills(list));
        } catch (IllegalArgumentException unreadable) {
            return;
        }
        var current = NpcDataReview.raceSkill(skills);
        if (current.isEmpty() || NpcDataReview.raceOf(current).equals(race.strip())) {
            return;
        }
        var fitting = NpcDataReview.raceSkillFor(race.strip(), current, source.getSkills());
        if (fitting.isEmpty()) {
            return;
        }
        ImGui.alignTextToFramePadding();
        text(
                GeodataWidgets.WARNING,
                HudWidgets.truncateToWidth(
                        "Race skill: " + NpcDataReview.raceOf(current), ImGui.getContentRegionAvailX() - 140));
        ImGui.sameLine(ImGui.getContentRegionMaxX() - 132);
        if (HudWidgets.button("npc-race-skill", null, "Set race skill too", 132, 24, true, true)) {
            skills.set(skills.indexOf(current), fitting);
            set(document, index, NpcValues.SKILLS, NpcValues.formatSkills(skills), "Race skill");
        }
        GeodataWidgets.tip("Replace " + current + " with " + fitting
                + " in skill_list. The race skill sets how other skills and items " + "treat the NPC.");
        space(GeodataWidgets.ROW);
    }

    private boolean statField(NpcDataDocument document, NpcRecord npc, StatField field, float fieldWidth) {
        var record = npc.getIndex();
        var value = Objects.requireNonNullElse(document.getValue(record, field.key()), "");
        var edited = document.getState().editedValue(record, field.key()) != null;
        var band = field.kind() == FieldKind.NUMBER && !field.key().equals(NpcExperience.LEVEL)
                ? findBand(document, npc, field.key())
                : null;
        var unusual = false;
        if (band != null) {
            try {
                unusual = band.isUnusual(Double.parseDouble(value.strip()));
            } catch (NumberFormatException notNumber) {
                unusual = false;
            }
        }
        ImGui.beginGroup();
        text(edited || unusual ? GeodataWidgets.WARNING : GeodataWidgets.MUTED, field.label());
        if (field.kind() == FieldKind.FIXED) {
            fixedField("stat/" + field.key(), value, fieldWidth);
        } else if (field.key().equals(NpcExperience.LEVEL)) {
            textField(
                    "stat/" + field.key(), value, fieldWidth, text -> editLevel("stat/level", document, record, text));
        } else if (field.kind() == FieldKind.CHOICE) {
            choiceField(document, record, field, value, fieldWidth);
        } else if (field.kind() == FieldKind.NUMBER) {
            textField("stat/" + field.key(), value, fieldWidth, text -> {
                try {
                    Double.parseDouble(text.strip());
                    edit("stat/" + field.key(), document, record, field.key(), text.strip(), field.label());
                } catch (NumberFormatException notNumber) {
                }
            });
        } else {
            textField("stat/" + field.key(), value, fieldWidth, text -> {
                if (!text.isBlank()) {
                    edit("stat/" + field.key(), document, record, field.key(), text.strip(), field.label());
                }
            });
        }
        if (band != null) {
            text(
                    unusual ? GeodataWidgets.WARNING : GeodataWidgets.FAINT,
                    HudWidgets.truncateToWidth(band.levels() + ": " + statRange(band), fieldWidth));
        }
        ImGui.endGroup();
        var tip = new StringBuilder(field.tip());
        if (edited) {
            tip.append("\n\nIn the file: ").append(Objects.requireNonNullElse(npc.getValue(field.key()), ""));
        }
        if (band != null) {
            tip.append("\n\nTypical for ")
                    .append(band.levels())
                    .append(' ')
                    .append(band.kind().getBandLabel())
                    .append(" (")
                    .append(count(band.count(), "NPC"))
                    .append("): the middle half from ")
                    .append(statNumber(band.q1()))
                    .append(" to ")
                    .append(statNumber(band.q3()))
                    .append(", median ")
                    .append(statNumber(band.median()))
                    .append(". Marked when outside ")
                    .append(statNumber(band.low()))
                    .append(" to ")
                    .append(statNumber(band.high()))
                    .append(": twice the middle half beyond it.");
        }
        GeodataWidgets.tip(tip.toString());
        return true;
    }

    private void choiceField(NpcDataDocument document, int record, StatField field, String value, float width) {
        var current = value.strip();
        var choices = new ArrayList<>(CHOICES.get(field.key()));
        if (!current.isEmpty() && !choices.contains(current)) {
            choices.add(current);
        }
        ImGui.setNextItemWidth(width);
        if (HudWidgets.beginCombo("##stat/" + field.key(), current.isEmpty() ? "–" : current, 0)) {
            for (var choice : choices) {
                if (ImGui.selectable(choice, choice.equals(current)) && !choice.equals(current)) {
                    set(document, record, field.key(), choice, field.label());
                }
            }
            ImGui.endCombo();
        }
    }

    private Band findBand(NpcDataDocument document, NpcRecord npc, String key) {
        var level = document.getValue(npc.getIndex(), NpcExperience.LEVEL);
        if (level == null) {
            return null;
        }
        try {
            return statReferenceFor(document)
                    .findBand(NpcKind.ofType(npc.getType()), Integer.parseInt(level.strip()), key);
        } catch (NumberFormatException notLevel) {
            return null;
        }
    }

    private void drawFlag(NpcDataDocument document, int record, StatField field) {
        var value = Objects.requireNonNullElse(document.getValue(record, field.key()), "0");
        var on = !value.equals("0");
        if (ImGui.checkbox(field.label() + "##" + field.key(), on)) {
            set(document, record, field.key(), on ? "0" : "1", field.label());
        }
        GeodataWidgets.tip(field.tip() + "=" + value);
    }

    private void drawSlot(NpcDataDocument document, int record, StatField field) {
        var name = NpcValues.parseItem(Objects.requireNonNullElse(document.getValue(record, field.key()), "[]"));
        var server = name.isEmpty() ? null : source.getItems().byName(name);
        var client = server == null ? null : source.getCatalog().getItem(server.id());
        var rowKey = "slot/" + field.key();
        var open = rowKey.equals(expanded);
        ImGui.pushID(rowKey);
        if (NpcPanelWidgets.iconRow(
                "row",
                iconOf(client),
                Glyph.BOX,
                name.isEmpty() ? "Nothing" : itemTitle(name, client),
                name.isEmpty() ? GeodataWidgets.FAINT : GeodataWidgets.TEXT,
                field.label() + (name.isEmpty() ? "" : " · " + name),
                "",
                0,
                open)) {
            expanded = open ? "" : rowKey;
        }
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(field.tip() + (name.isEmpty() ? "" : "\n\n" + itemTip(name, server, client)));
        }
        if (open) {
            space(GeodataWidgets.ROW);
            var inner = ImGui.getContentRegionAvailX();
            var half = (inner - ImGui.getStyle().getItemSpacingX()) / 2;
            if (HudWidgets.button(
                    "change", Glyph.BOX, name.isEmpty() ? "Choose item" : "Other item", half, 28, false, true)) {
                openItemPicker(
                        chosen -> set(document, record, field.key(), NpcValues.formatItem(chosen), field.label()));
            }
            ImGui.sameLine();
            ImGui.beginDisabled(name.isEmpty());
            if (HudWidgets.button("clear", Glyph.CLOSE, "Empty", half, 28, false, true)) {
                set(document, record, field.key(), "[]", field.label());
                expanded = "";
            }
            ImGui.endDisabled();
            space(GeodataWidgets.ROW);
        }
        ImGui.popID();
    }

    private void drawAllTab(NpcDataDocument document, NpcRecord record) {
        var index = record.getIndex();
        space(GeodataWidgets.BLOCK);
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##npc-all-filter", "Filter settings", allFilter);
        var filter = allFilter.get().strip().toLowerCase(Locale.ROOT);
        space(GeodataWidgets.ROW);
        text(GeodataWidgets.FAINT, "As npcdata.txt writes them. Lists keep their braces.");
        space(GeodataWidgets.ROW);
        for (var key : record.getKeys()) {
            if (!filter.isEmpty() && !key.contains(filter)) {
                continue;
            }
            var value = Objects.requireNonNullElse(document.getValue(index, key), "");
            var edited = document.getState().editedValue(index, key) != null;
            text(edited ? GeodataWidgets.WARNING : GeodataWidgets.MUTED, key);
            if (key.equals(NpcExperience.EXP)) {
                fixedField("all/" + key, value, -1);
                GeodataWidgets.tip("Follows level: change the level and this changes with it.");
            } else if (key.equals(NpcExperience.LEVEL)) {
                textField("all/" + key, value, -1, text -> editLevel("all/level", document, index, text));
            } else {
                textField("all/" + key, value, -1, text -> {
                    if (!text.isBlank()) {
                        edit("all/" + key, document, index, key, text.strip(), key);
                    }
                });
            }
            if (edited) {
                GeodataWidgets.tip("In the file: " + Objects.requireNonNullElse(record.getValue(key), ""));
            }
        }
    }

    private record PickRow(String name, String title, String detail, int argb) {}

    private void openItemPicker(Consumer<String> apply) {
        openPicker(new Picker(PickKind.ITEM, "Choose an item", apply, 0, false, List.of()));
    }

    private void openRepeatableItemPicker(Consumer<String> apply) {
        openPicker(new Picker(PickKind.ITEM, "Choose an item", apply, 0, true, List.of()));
    }

    private void openSkillPicker(int filter, Consumer<String> apply) {
        openSkillPicker(filter, apply, List.of());
    }

    private void openSkillPicker(int filter, Consumer<String> apply, List<String> only) {
        openPicker(new Picker(PickKind.SKILL, "Choose a skill", apply, filter, false, only));
        pickerFilter = filter;
    }

    private void openNpcPicker(String title, Consumer<String> apply) {
        openPicker(new Picker(PickKind.NPC, title, apply, 0, false, List.of()));
    }

    private void openAiPicker(NpcDataDocument document, Consumer<String> apply) {
        aiCatalog(document);
        openPicker(new Picker(PickKind.AI, "Choose an AI", apply, 0, false, List.of()));
    }

    private void openParameterPicker(List<Parameter> suggestions, Consumer<String> apply) {
        var names = new ArrayList<String>();
        suggestions.forEach(parameter -> names.add(parameter.name()));
        openPicker(new Picker(PickKind.PARAMETER, "Add an AI setting", apply, 0, false, names));
    }

    private void openPicker(Picker next) {
        picker = next;
        pickerSearch.set("");
        pickerQuery = null;
        pickerFilter = next.filter();
        pickerFocus = true;
    }

    private void drawPicker() {
        var current = picker;
        if (current == null) {
            return;
        }
        var title = current.title() + "##npc-picker";
        if (!ImGui.isPopupOpen(title)) {
            ImGui.openPopup(title);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.min(560, io.getDisplaySizeX() - 80), Math.min(620, io.getDisplaySizeY() - 80));
        if (!ImGui.beginPopupModal(title, ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize)) {
            picker = null;
            return;
        }
        Motion.dialog();
        if (pickerFocus) {
            ImGui.setKeyboardFocusHere();
            pickerFocus = false;
        }
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint(
                "##npc-picker-search",
                switch (current.kind()) {
                    case ITEM -> "Name, id or script name";
                    case SKILL -> "Name, id or script name such as s_npc_prop";
                    case NPC -> "Name, id or lv 40";
                    default -> "Name";
                },
                pickerSearch);
        String[] filters = switch (current.kind()) {
            case ITEM -> ITEM_FILTERS;
            case SKILL -> current.only().isEmpty() ? SKILL_FILTERS : new String[0];
            default -> new String[0];
        };
        if (filters.length > 0) {
            space(GeodataWidgets.ROW);
            if (pickerFilter < 0 || pickerFilter >= filters.length) {
                pickerFilter = current.filter();
            }
            var picked = GeodataWidgets.segmented("npc-picker-filter", filters, pickerFilter);
            if (picked >= 0) {
                pickerFilter = picked;
                pickerQuery = null;
            }
            if (current.kind() == PickKind.SKILL) {
                space(GeodataWidgets.ROW);
                if (ImGui.checkbox("Only skills NPCs have##npc-picker-npc-skills", npcSkillsOnly)) {
                    npcSkillsOnly = !npcSkillsOnly;
                    pickerQuery = null;
                }
                GeodataWidgets.tip(
                        "Skills another NPC of npcdata.txt has or casts from its AI settings, such as s_antaras_body"
                            + " or a pet's buff, and those named s_npc_ or s_raid_. Off: every skill of skilldata.txt,"
                            + " the ones players learn too.");
            }
        }
        var query = pickerSearch.get().strip().toLowerCase(Locale.ROOT);
        if (!query.equals(pickerQuery)) {
            pickerQuery = query;
            pickerResults = switch (current.kind()) {
                case ITEM -> findItems(query, pickerFilter);
                case SKILL -> findSkills(query, current.only().isEmpty() ? pickerFilter : 2, current.only());
                case NPC -> findNpcs(query);
                case AI -> findAis(query);
                case PARAMETER -> findParameters(query, current.only());
            };
        }
        space(GeodataWidgets.ROW);
        text(
                GeodataWidgets.MUTED,
                pickerResults.size() >= 2000
                        ? "The first 2,000 matches"
                        : String.format(
                                Locale.ROOT,
                                "%,d %s",
                                pickerResults.size(),
                                pickerResults.size() == 1 ? "match" : "matches"));
        space(GeodataWidgets.ROW);
        ImGui.beginChild("##npc-picker-list", 0, -40, false);
        String chosen = null;
        var keepOpen = false;
        clipper.begin(pickerResults.size(), NpcPanelWidgets.ROW_HEIGHT);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                var result = pickerResults.get(i);
                if (result instanceof ServerItems.Item item) {
                    var client = source.getCatalog().getItem(item.id());
                    if (NpcPanelWidgets.iconRow(
                            "pick/" + i,
                            iconOf(client),
                            Glyph.BOX,
                            itemTitle(item.name(), client),
                            GeodataWidgets.TEXT,
                            item.name()
                                    + " · id "
                                    + item.id()
                                    + (item.grade().equals("none")
                                            ? ""
                                            : " · " + item.grade().toUpperCase(Locale.ROOT) + " grade"),
                            "",
                            0,
                            false)) {
                        chosen = item.name();
                        keepOpen = ImGui.getIO().getKeyShift();
                    }
                    if (ImGui.isItemHovered()) {
                        HudWidgets.showTooltip(itemTip(item.name(), item, client) + "\n\nRight-click: who drops it");
                    }
                    if (ImGui.beginPopupContextItem("pick-menu/" + i)) {
                        var document = source.getDocument();
                        if (ImGui.menuItem("Dropped by…") && document != null) {
                            picker = null;
                            ImGui.closeCurrentPopup();
                            openItemLookup(document, item.name());
                        }
                        ImGui.endPopup();
                    }
                } else if (result instanceof ServerSkills.Skill skill) {
                    var client = source.getCatalog().getSkill(skill.id(), skill.level());
                    if (NpcPanelWidgets.iconRow(
                            "pick/" + i,
                            client == null ? 0 : icons.getTexture(client.icon()),
                            Glyph.SPARKLES,
                            skillTitle(skill.name(), skill, client),
                            GeodataWidgets.TEXT,
                            skill.name()
                                    + " · id "
                                    + skill.id()
                                    + (describeOperateType(skill.operateType()).isEmpty()
                                            ? ""
                                            : " · " + describeOperateType(skill.operateType())),
                            npcCount(skillUse().getOrDefault(skill.name(), 0)),
                            GeodataWidgets.MUTED,
                            false)) {
                        chosen = skill.name();
                    }
                    if (ImGui.isItemHovered()) {
                        HudWidgets.showTooltip(skillTip(skill.name(), skill, client));
                    }
                } else if (result instanceof PickRow row) {
                    var x = ImGui.getCursorScreenPosX();
                    var y = ImGui.getCursorScreenPosY();
                    if (ImGui.selectable("##pick/" + i, false, 0, 0, SpawnPanelWidgets.listRowHeight())) {
                        chosen = row.name();
                    }
                    SpawnPanelWidgets.listRow(
                            ImGui.getWindowDrawList(),
                            x,
                            y,
                            ImGui.getItemRectMaxX() - x,
                            row.argb(),
                            row.title(),
                            row.detail(),
                            "",
                            query);
                }
            }
        }
        clipper.end();
        if (pickerResults.isEmpty()) {
            text(
                    GeodataWidgets.FAINT,
                    source.getItems().size() == 0 && current.kind() == PickKind.ITEM
                            ? "No itemdata.txt or item_pch.txt in the script folder."
                            : "Nothing matches.");
        }
        ImGui.endChild();
        space(GeodataWidgets.ROW);
        if ((ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter))
                && !pickerResults.isEmpty()) {
            var first = pickerResults.getFirst();
            chosen = switch (first) {
                case ServerItems.Item item -> item.name();
                case ServerSkills.Skill skill -> skill.name();
                case PickRow row -> row.name();
                default -> null;
            };
            keepOpen = ImGui.getIO().getKeyShift();
        }
        var cancel = HudWidgets.button(
                        "npc-picker-cancel", null, current.repeatable() ? "Done" : "Cancel", 96, 28, false, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)
                || GeodataWidgets.wasClickedOutside();
        ImGui.sameLine(0, 12);
        ImGui.alignTextToFramePadding();
        text(
                GeodataWidgets.FAINT,
                current.repeatable()
                        ? "Enter takes the first · Shift+Enter adds it and stays open"
                        : "Click an entry, or Enter for the first");
        if (chosen != null) {
            var apply = current.apply();
            if (!(keepOpen && current.repeatable())) {
                picker = null;
                ImGui.closeCurrentPopup();
            } else {
                pickerSearch.set("");
                pickerQuery = null;
                pickerFocus = true;
            }
            apply.accept(chosen);
        } else if (cancel) {
            picker = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private List<Object> findItems(String query, int filter) {
        var found = new ArrayList<Object>();
        var catalog = source.getCatalog();
        for (var item : source.getItems().getItems()) {
            if (!matchesItemFilter(item, filter)) {
                continue;
            }
            if (!query.isEmpty()) {
                var client = catalog.getItem(item.id());
                var matches = item.name().contains(query)
                        || Integer.toString(item.id()).equals(query)
                        || client != null
                                && (client.name() + " " + client.addName())
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query);
                if (!matches) {
                    continue;
                }
            }
            found.add(item);
            if (found.size() >= 2000) {
                break;
            }
        }
        return found;
    }

    private List<Object> findSkills(String query, int filter, List<String> only) {
        var found = new ArrayList<Object>();
        var catalog = source.getCatalog();
        var allowed = only.isEmpty() ? null : new HashSet<>(only);
        var use = skillUse();
        for (var skill : source.getSkills().getSkills()) {
            if (allowed != null && !allowed.contains(skill.name())) {
                continue;
            }
            if (allowed == null && npcSkillsOnly && !isNpcSkill(skill.name(), use)) {
                continue;
            }
            var passive = skill.isPassive();
            if (filter == 0 && !passive && !skill.operateType().isEmpty() || filter == 1 && passive) {
                continue;
            }
            if (!query.isEmpty()) {
                var client = catalog.getSkill(skill.id(), skill.level());
                var matches = skill.name().contains(query)
                        || Integer.toString(skill.id()).equals(query)
                        || client != null
                                && client.name().toLowerCase(Locale.ROOT).contains(query);
                if (!matches) {
                    continue;
                }
            }
            found.add(skill);
            if (found.size() >= 2000) {
                break;
            }
        }
        if (allowed != null && source.getSkills().size() == 0) {
            for (var name : only) {
                if (query.isEmpty() || name.contains(query)) {
                    found.add(new PickRow(name, name, "skill_list", SpawnStyle.UNKNOWN.argb));
                }
            }
        }
        return found;
    }

    private Map<String, Integer> skillUse() {
        var document = source.getDocument();
        if (document == null) {
            return Map.of();
        }
        var state = document.getState();
        if (state != skillUseState) {
            var counts = new HashMap<String, Integer>();
            for (var i = 0; i < document.size(); i++) {
                if (state.isRemoved(i)) {
                    continue;
                }
                var names = new HashSet<String>();
                try {
                    var list = document.getValue(i, NpcValues.SKILLS);
                    if (list != null) {
                        names.addAll(NpcValues.parseSkills(list));
                    }
                    var ai = document.getValue(i, NpcValues.AI);
                    if (ai != null) {
                        for (var parameter : NpcValues.parseAi(ai).parameters()) {
                            if (!parameter.skill().isEmpty()) {
                                names.add(parameter.skill());
                            }
                        }
                    }
                } catch (IllegalArgumentException unreadable) {
                }
                for (var name : names) {
                    counts.merge(name, 1, Integer::sum);
                }
            }
            skillUse = counts;
            skillUseState = state;
        }
        return skillUse;
    }

    private List<Object> findNpcs(String query) {
        var found = new ArrayList<Object>();
        var document = source.getDocument();
        if (document == null) {
            return found;
        }
        for (var i = 0; i < document.size() && found.size() < 2000; i++) {
            if (document.getState().isRemoved(i)) {
                continue;
            }
            var record = document.record(i);
            var name = displayName(record);
            if (!query.isEmpty()
                    && !record.name().contains(query)
                    && !name.toLowerCase(Locale.ROOT).contains(query)
                    && !Integer.toString(record.getId()).startsWith(query)) {
                continue;
            }
            found.add(new PickRow(
                    record.name(),
                    name,
                    record.name()
                            + " · id "
                            + record.getId()
                            + " · Lv "
                            + Objects.requireNonNullElse(document.getValue(i, "level"), "?"),
                    SpawnStyle.ofType(record.getType()).argb));
        }
        return found;
    }

    private List<Object> findAis(String query) {
        var found = new ArrayList<Object>();
        var catalog = aiCatalog;
        if (catalog == null) {
            return found;
        }
        for (var ai : catalog.getAis()) {
            if (!query.isEmpty() && !ai.name().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            found.add(new PickRow(
                    ai.name(),
                    ai.name(),
                    String.format(
                            Locale.ROOT,
                            "%,d %s · %s",
                            ai.npcs(),
                            ai.npcs() == 1 ? "NPC" : "NPCs",
                            count(ai.parameters().size(), "setting")),
                    SpawnStyle.UNKNOWN.argb));
            if (found.size() >= 2000) {
                break;
            }
        }
        return found;
    }

    private List<Object> findParameters(String query, List<String> names) {
        var found = new ArrayList<Object>();
        var catalog = aiCatalog;
        if (catalog == null) {
            return found;
        }
        var byName = new HashMap<String, Parameter>();
        catalog.getParameters().forEach(parameter -> byName.put(parameter.name(), parameter));
        for (var name : names) {
            if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            var parameter = byName.get(name);
            var detail = parameter == null
                    ? ""
                    : String.format(
                            Locale.ROOT,
                            "%,d %s · most often %s",
                            parameter.npcs(),
                            parameter.npcs() == 1 ? "NPC" : "NPCs",
                            parameter.usual());
            found.add(new PickRow(name, name, detail, SpawnStyle.UNKNOWN.argb));
            if (found.size() >= 2000) {
                break;
            }
        }
        return found;
    }

    private void openItemLookup(NpcDataDocument document, String item) {
        lookup = buildLookup(document, LookupKind.ITEM, item, "Dropped by", "", List.of());
    }

    private void openSkillLookup(NpcDataDocument document, String skill) {
        lookup = buildLookup(document, LookupKind.SKILL, skill, "Known by", "", List.of());
    }

    private void openListLookup(NpcDataDocument document, String key, List<Integer> records, String title) {
        lookup = buildLookup(document, LookupKind.LIST, settingName(key), title, key, records);
    }

    private Lookup buildLookup(
            NpcDataDocument document, LookupKind kind, String name, String title, String key, List<Integer> records) {
        var state = document.getState();
        var index = dataIndex(document);
        var rows = new ArrayList<LookupRow>();
        switch (kind) {
            case ITEM -> {
                for (var use : index.findDroppedBy(state, name)) {
                    var group = use.group() >= 0 ? " group " + (use.group() + 1) : "";
                    rows.add(new LookupRow(
                            use.record(),
                            use.perKill(),
                            settingName(use.key()).replaceAll(" · .*", "")
                                    + group
                                    + " · "
                                    + "×"
                                    + NpcDataReview.formatAmount(use.item()),
                            findNearestSpawn(document, use.record())));
                }
            }
            case SKILL -> {
                for (var use : index.findKnownBy(state, name)) {
                    rows.add(new LookupRow(
                            use.record(),
                            -1,
                            use.setting().isEmpty() ? "skill_list" : "AI " + use.setting(),
                            findNearestSpawn(document, use.record())));
                }
            }
            case LIST -> {
                for (var record : records) {
                    rows.add(new LookupRow(record, -1, key, findNearestSpawn(document, record)));
                }
            }
        }
        return new Lookup(kind, name, title, key, sortLookup(rows), state);
    }

    private Nearest findNearestSpawn(NpcDataDocument document, int record) {
        return spawns.isReady()
                ? spawns.findNearestSpawn(document.record(record).name())
                : null;
    }

    private List<LookupRow> sortLookup(List<LookupRow> rows) {
        var sorted = new ArrayList<>(rows);
        if (lookupByDistance && sorted.stream().anyMatch(row -> row.nearest() != null)) {
            sorted.sort(Comparator.comparingInt((LookupRow row) ->
                            row.nearest() == null ? 2 : row.nearest().loaded() ? 0 : 1)
                    .thenComparingDouble(row -> row.nearest() == null
                            ? Double.MAX_VALUE
                            : row.nearest().distance())
                    .thenComparing(Comparator.comparingDouble(LookupRow::chance).reversed()));
        } else {
            sorted.sort(Comparator.comparingDouble(LookupRow::chance).reversed().thenComparingInt(LookupRow::record));
        }
        return sorted;
    }

    private void drawLookup() {
        var current = lookup;
        var document = source.getDocument();
        if (current == null || document == null) {
            lookup = null;
            return;
        }
        if (current.state() != document.getState()) {
            current = current.kind() == LookupKind.LIST
                    ? current
                    : buildLookup(document, current.kind(), current.name(), current.title(), current.key(), List.of());
            lookup = current;
        }
        var label = current.kind() == LookupKind.ITEM
                ? itemLabel(current.name())
                : current.kind() == LookupKind.SKILL ? skillLabel(current.name()) : "";
        var id = (label.isEmpty() ? current.title() : current.title() + " " + label) + "##npc-lookup";
        if (!ImGui.isPopupOpen(id)) {
            ImGui.openPopup(id);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.min(600, io.getDisplaySizeX() - 80), Math.min(620, io.getDisplaySizeY() - 80));
        if (!ImGui.beginPopupModal(id, ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize)) {
            lookup = null;
            return;
        }
        Motion.dialog();
        var rows = current.rows();
        var known = rows.stream().anyMatch(row -> row.nearest() != null);
        ImGui.alignTextToFramePadding();
        text(
                GeodataWidgets.MUTED,
                String.format(Locale.ROOT, "%,d %s", rows.size(), rows.size() == 1 ? "NPC" : "NPCs")
                        + (known && lookupByDistance
                                ? ", nearest first"
                                : current.kind() == LookupKind.ITEM ? ", most likely first" : ""));
        if (known) {
            ImGui.sameLine(ImGui.getWindowContentRegionMaxX() - 200);
            var sortLabels =
                    new String[] {current.kind() == LookupKind.ITEM ? "By chance" : "In file order", "By distance"};
            var picked = GeodataWidgets.segmented("npc-lookup-sort", sortLabels, lookupByDistance ? 1 : 0, 200);
            if (picked >= 0) {
                lookupByDistance = picked == 1;
                lookup = new Lookup(
                        current.kind(),
                        current.name(),
                        current.title(),
                        current.key(),
                        sortLookup(rows),
                        current.state());
            }
        } else if (!spawns.isReady()) {
            ImGui.sameLine(0, 12);
            text(GeodataWidgets.FAINT, "Open npcpos.txt in NPC Spawns for distances");
        }
        space(GeodataWidgets.ROW);
        ImGui.beginChild("##npc-lookup-list", 0, -40, false);
        var opened = -1;
        var height = SpawnPanelWidgets.listRowHeight();
        clipper.begin(rows.size(), height + ImGui.getStyle().getItemSpacingY());
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                var row = rows.get(i);
                if (row.record() >= document.size()) {
                    continue;
                }
                var record = document.record(row.record());
                ImGui.pushID(i);
                var x = ImGui.getCursorScreenPosX();
                var y = ImGui.getCursorScreenPosY();
                var width = ImGui.getContentRegionAvailX();
                var fly = row.nearest() != null && row.nearest().loaded();
                if (ImGui.selectable("##lookup-row", false, 0, width - (fly ? 34 : 0), height)) {
                    opened = row.record();
                }
                var level = Objects.requireNonNullElse(document.getValue(row.record(), NpcExperience.LEVEL), "?");
                var value = row.chance() >= 0 ? formatPercent(row.chance()) : "";
                if (row.nearest() != null) {
                    value = (value.isEmpty() ? "" : value + " · ")
                            + (row.nearest().loaded()
                                    ? SpawnPanelWidgets.compact(
                                                    Math.round(row.nearest().distance())) + " away"
                                    : "not loaded");
                }
                SpawnPanelWidgets.listRow(
                        ImGui.getWindowDrawList(),
                        x,
                        y,
                        width - (fly ? 34 : 0),
                        SpawnStyle.ofType(record.getType()).argb,
                        displayName(record),
                        record.name()
                                + " · Lv "
                                + level
                                + " · "
                                + row.where()
                                + (row.chance() >= 0 ? oneIn(row.chance(), "kills") : ""),
                        value,
                        "");
                if (fly) {
                    ImGui.setCursorScreenPos(x + width - 30, y + (height - 26) * .5f);
                    if (HudWidgets.button("fly", Glyph.FOCUS, "", 26, 26, false, false)) {
                        spawns.flyTo(record.name());
                    }
                    GeodataWidgets.tip("Fly to its nearest spawn.");
                    ImGui.setCursorScreenPos(x, y + height + ImGui.getStyle().getItemSpacingY());
                    ImGui.dummy(0, 0);
                }
                ImGui.popID();
            }
        }
        clipper.end();
        if (rows.isEmpty()) {
            text(GeodataWidgets.FAINT, "No NPC.");
        }
        ImGui.endChild();
        space(GeodataWidgets.ROW);
        var close = HudWidgets.button("npc-lookup-close", null, "Close", 96, 28, false, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)
                || GeodataWidgets.wasClickedOutside();
        ImGui.sameLine(0, 12);
        ImGui.alignTextToFramePadding();
        text(GeodataWidgets.FAINT, "Click an NPC to open it");
        if (opened >= 0) {
            lookup = null;
            ImGui.closeCurrentPopup();
            open(opened);
            tab = current.kind() == LookupKind.SKILL ? Tab.SKILLS : Tab.DROP;
        } else if (close) {
            lookup = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private String displayName(NpcRecord record) {
        var client = source.getCatalog().getNpc(record.getId());
        return client != null && !client.name().isBlank() ? client.name() : record.name();
    }

    private long iconOf(ClientCatalog.@Nullable Item client) {
        return client == null ? 0 : icons.getTexture(client.icon());
    }

    private void textField(String id, String current, float width, Consumer<String> changed) {
        var buffer = buffers.computeIfAbsent(id, unused -> new ImString(256));
        if (!id.equals(activeField)) {
            if (buffer.getBufferSize() < current.length() + 64) {
                buffer = new ImString(current.length() + 256);
                buffers.put(id, buffer);
            }
            buffer.set(current);
        }
        ImGui.setNextItemWidth(width);
        var edited = ImGui.inputText("##" + id, buffer);
        if (ImGui.isItemActive()) {
            activeField = id;
        } else if (id.equals(activeField)) {
            activeField = null;
        }
        if (edited) {
            changed.accept(buffer.get());
        }
    }

    private void numberField(String id, double current, float width, DoubleConsumer changed) {
        textField(ImGui.getID(id) + "/" + id, ScriptValue.number(current), width, text -> {
            try {
                var value = Double.parseDouble(text.strip().replace(',', '.'));
                if (Double.isFinite(value)) {
                    changed.accept(value);
                }
            } catch (NumberFormatException notNumber) {
            }
        });
    }

    private void wholeField(String id, long current, float width, LongConsumer changed) {
        textField(ImGui.getID(id) + "/" + id, Long.toString(current), width, text -> {
            try {
                changed.accept(Long.parseLong(text.strip()));
            } catch (NumberFormatException notNumber) {
            }
        });
    }

    private void set(NpcDataDocument document, int record, String key, String value, String label) {
        document.set(label, record, key, value);
        lastEditKey = "";
    }

    private void edit(String field, NpcDataDocument document, int record, String key, String value, String label) {
        editState(field, document, record, label, state -> document.with(state, record, key, value));
    }

    private void editState(
            String field, NpcDataDocument document, int record, String label, UnaryOperator<State> change) {
        var now = System.nanoTime();
        var mergeKey = record + "/" + field;
        if (mergeKey.equals(lastEditKey)
                && now - lastEditTime < EDIT_MERGE_NANOS
                && document.canUndo()
                && document.getRevision() == lastEditRevision) {
            document.amend(change);
        } else {
            document.apply(label, change);
        }
        lastEditKey = mergeKey;
        lastEditTime = now;
        lastEditRevision = document.getRevision();
    }

    private void editLevel(String field, NpcDataDocument document, int record, String text) {
        var level = text.strip();
        if (!level.matches("\\d{1,3}") || Integer.parseInt(level) < 1) {
            return;
        }
        var experience = experienceFor(document);
        editState(field, document, record, "Level", state -> experience.withLevel(document, state, record, level));
    }

    private NpcExperience experienceFor(NpcDataDocument document) {
        if (experienceFile != document.getFile()) {
            experienceFile = document.getFile();
            experience = NpcExperience.of(document.getFile());
        }
        return experience;
    }

    private void fixedField(String id, String value, float width) {
        ImGui.setNextItemWidth(width);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        ImGui.pushStyleColor(ImGuiCol.FrameBg, ImColor.rgb(20, 28, 36));
        ImGui.inputText("##" + id, new ImString(value, Math.max(16, value.length() + 1)), ImGuiInputTextFlags.ReadOnly);
        ImGui.popStyleColor(2);
    }

    private void drawReward(NpcDataDocument document, int record) {
        var experience = experienceFor(document);
        var reward = experience.computeReward(
                document.getValue(record, NpcExperience.LEVEL),
                document.getValue(record, NpcExperience.EXP_RATE),
                document.getValue(record, NpcExperience.SP));
        if (reward == null) {
            return;
        }
        text(
                GeodataWidgets.MUTED,
                String.format(
                        Locale.ROOT, "A kill gives %,d XP and %,d SP", Math.round(reward[0]), Math.round(reward[1])));
        GeodataWidgets.tip("Experience: level² × Exp rate. Skill points: "
                + (experience.hasSpRate()
                        ? "level² × SP, as this file writes SP as a rate (162)."
                        : "SP as written, as Chronicle 4 and Gracia Final count it.")
                + " The server then shares it by damage and party, lowers it for level differences and applies"
                + " its own rates.");
        space(GeodataWidgets.ROW);
    }

    private boolean isFooterShown(NpcDataDocument document) {
        return document.isModified() || document.canUndo() || document.canRedo() || source.isSaving();
    }

    private void drawFooter(NpcDataDocument document) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY() + 4;
        var width = ImGui.getContentRegionAvailX();
        var modified = document.isModified();
        draw.addRectFilled(
                x, y, x + width, y + FOOTER - 6, modified ? ImColor.rgba(255, 200, 90, 24) : GeodataWidgets.CARD, 8f);
        if (modified) {
            draw.addRect(x, y, x + width, y + FOOTER - 6, ImColor.rgba(255, 200, 90, 80), 8, 0, 1);
        }
        ImGui.setCursorScreenPos(x + 4, y + (FOOTER - 6 - 26) * .5f);
        if (GeodataWidgets.iconButton("npc-undo", Glyph.UNDO, document.canUndo() && !source.isSaving())) {
            document.undo();
            lastEditKey = "";
            activeField = null;
        }
        GeodataWidgets.tip(
                document.canUndo() ? "Undo " + document.getUndoLabel().toLowerCase(Locale.ROOT) : "Nothing to undo",
                UNDO_KEY);
        ImGui.sameLine(0, 2);
        if (GeodataWidgets.iconButton("npc-redo", Glyph.REDO, document.canRedo() && !source.isSaving())) {
            document.redo();
            lastEditKey = "";
            activeField = null;
        }
        GeodataWidgets.tip(
                document.canRedo() ? "Redo " + document.getRedoLabel().toLowerCase(Locale.ROOT) : "Nothing to redo",
                "Shift+" + UNDO_KEY);
        var textX = ImGui.getItemRectMaxX() + 8;
        var problems = modified ? filterErrors(findIssues(document)).size() : 0;
        var edited = document.getState().edits().size();
        var status = source.isSaving()
                ? "Saving…"
                : problems > 0
                        ? problems == 1 ? "1 problem" : problems + " problems"
                        : modified
                                ? "Unsaved"
                                : source.getMessage().isBlank() || source.isMessageError()
                                        ? "All changes saved"
                                        : source.getMessage();
        var justSaved = !modified
                && !source.isSaving()
                && !source.isMessageError()
                && source.getMessage().startsWith("Saved")
                && source.getFolder() != null;
        draw.pushClipRect(textX, y, x + width - (justSaved ? 176 : 142), y + FOOTER, true);
        draw.addText(
                textX,
                y + (FOOTER - 6 - ImGui.getFontSize()) * .5f,
                problems > 0 ? GeodataWidgets.BLOCKED : modified ? GeodataWidgets.WARNING : GeodataWidgets.MUTED,
                status);
        draw.popClipRect();
        if (justSaved) {
            ImGui.setCursorScreenPos(x + width - 168, y + (FOOTER - 6 - 26) * .5f);
            if (GeodataWidgets.iconButton("npc-show-saved", Glyph.FOLDER, true)) {
                FileManager.tryShow(source.getFolder());
            }
            GeodataWidgets.tip("Show the saved file and its backup: " + source.getFolder());
        }
        ImGui.setCursorScreenPos(x + width - 138, y + (FOOTER - 6 - 28) * .5f);
        ImGui.beginDisabled(!modified || source.isSaving());
        if (HudWidgets.button("npc-discard", null, "Discard", 66, 28, false, false)) {
            ImGui.openPopup("npc-discard-confirm");
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Go back to npcdata.txt as last saved. Undo brings the changes back.");
        if (ImGui.beginPopup("npc-discard-confirm")) {
            ImGui.textUnformatted("Discard all unsaved changes?");
            text(GeodataWidgets.FAINT, "Undo brings them back.");
            space(GeodataWidgets.ROW);
            if (HudWidgets.button("npc-discard-yes", null, "Discard", 96, 28, true, true)) {
                document.discardChanges();
                lastEditKey = "";
                activeField = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (HudWidgets.button("npc-discard-no", null, "Keep", 96, 28, false, true)) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
        ImGui.setCursorScreenPos(x + width - 68, y + (FOOTER - 6 - 28) * .5f);
        ImGui.beginDisabled(!modified || source.isSaving());
        if (HudWidgets.button("npc-save", null, "Save…", 64, 28, modified, true)) {
            openReview(document);
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                "Shows each change in words, then writes npcdata.txt. Only the edited values change.",
                UNDO_KEY.replace("Z", "S"));
        ImGui.setCursorScreenPos(x, y + FOOTER - 6);
        ImGui.dummy(width, 0);
    }

    private List<Issue> findIssues(NpcDataDocument document) {
        if (document != checkedDocument || !document.getState().equals(checkedState)) {
            checkedDocument = document;
            checkedState = document.getState();
            checkedIssues =
                    NpcDataReview.findIssues(document, document.getState(), source.getItems(), source.getSkills());
        }
        return checkedIssues;
    }

    private void openReview(NpcDataDocument document) {
        if (!document.isModified() || source.isSaving()) {
            return;
        }
        var issues = findIssues(document);
        var changes = NpcDataReview.collectChanges(document, document.getState(), this::itemLabel);
        var notes = new ArrayList<String>();
        for (var index : document.getState().removed()) {
            if (document.isAdded(index)) {
                continue;
            }
            var name = document.record(index).name();
            if (spawns.isReady()) {
                var summary = spawns.getSummary(name);
                if (summary.entries() > 0) {
                    notes.add(name
                            + ": "
                            + (summary.entries() == 1 ? "1 spawn entry" : summary.entries() + " spawn entries")
                            + " of npcpos.txt still spawn it. Remove them in NPC Spawns.");
                }
            } else {
                notes.add(name + ": open npcpos.txt in NPC Spawns to see whether it still spawns it.");
            }
        }
        review = new Review(
                changes,
                NpcDataReview.groupIntoSections(changes, document.getBatchesSinceSave()),
                source.hasChangedOnDisk(),
                filterErrors(issues),
                filterWarnings(issues),
                source.getPchChange(),
                notes);
        reviewOpen.clear();
    }

    private String itemLabel(String name) {
        var server = source.getItems().byName(name);
        return itemTitle(name, server == null ? null : source.getCatalog().getItem(server.id()));
    }

    private String skillLabel(String name) {
        var server = source.getSkills().byName(name);
        return skillTitle(
                name, server, server == null ? null : source.getCatalog().getSkill(server.id(), server.level()));
    }

    private void drawReview(@Nullable ImFont mono) {
        var current = review;
        if (current == null) {
            return;
        }
        var title = "Save npcdata.txt##npc-review";
        if (!ImGui.isPopupOpen(title)) {
            ImGui.openPopup(title);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.min(760, io.getDisplaySizeX() - 80), Math.min(620, io.getDisplaySizeY() - 80));
        if (!ImGui.beginPopupModal(title, ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize)) {
            return;
        }
        Motion.dialog();
        var npcs = new TreeSet<Integer>();
        current.changes().forEach(change -> npcs.add(change.record()));
        var settings = current.changes().stream()
                .filter(change -> !change.key().equals(NpcDataReview.ADDED)
                        && !change.key().equals(NpcDataReview.REMOVED))
                .count();
        text(
                GeodataWidgets.MUTED,
                String.format(
                                Locale.ROOT,
                                "%,d %s in %,d %s",
                                settings,
                                settings == 1 ? "setting" : "settings",
                                npcs.size(),
                                npcs.size() == 1 ? "NPC" : "NPCs")
                        + pchSummary(current.pch()));
        if (current.changedOnDisk()) {
            space(GeodataWidgets.ROW);
            ImGui.pushTextWrapPos(0);
            text(
                    GeodataWidgets.WARNING,
                    "npcdata.txt changed on disk since it was read. Saving replaces those edits with the ones below;"
                            + " keep the backup to have them.");
            ImGui.popTextWrapPos();
        }
        var blocked = !current.errors().isEmpty();
        if (blocked) {
            space(GeodataWidgets.ROW);
            text(GeodataWidgets.BLOCKED, "Fix these before saving: the server cannot read them.");
            drawIssueList(current.errors(), GeodataWidgets.BLOCKED);
        }
        if (!current.warnings().isEmpty() || !current.spawnNotes().isEmpty()) {
            space(GeodataWidgets.ROW);
            text(GeodataWidgets.WARNING, "Worth a look; Save still works:");
            var notes = new ArrayList<>(current.warnings());
            for (var note : current.spawnNotes()) {
                var colon = note.indexOf(": ");
                notes.add(new Issue(-1, note.substring(0, colon), "", note.substring(colon + 2), false));
            }
            drawIssueList(notes, GeodataWidgets.WARNING);
        }
        space(GeodataWidgets.BLOCK);
        ImGui.beginChild("##npc-review-changes", 0, -88, true, ImGuiWindowFlags.HorizontalScrollbar);
        var first = true;
        for (var section : current.sections()) {
            if (!first) {
                space(GeodataWidgets.BLOCK);
            }
            first = false;
            if (section.batch() != null) {
                drawBatchSection(section, mono);
            } else {
                drawNpcChanges(section.changes(), mono, "");
            }
        }
        var pch = current.pch();
        if (pch != null && !pch.isEmpty()) {
            space(GeodataWidgets.BLOCK);
            text(HudWidgets.TEXT, NpcDataSource.PCH);
            ImGui.sameLine(0, 8);
            text(GeodataWidgets.FAINT, pchSummary(pch).replace(" · npc_pch.txt: ", ""));
            if (mono != null) {
                ImGui.pushFont(mono, 13f);
            }
            for (var name : pch.removedNames()) {
                ImGui.setCursorPosX(ImGui.getCursorPosX() + 24);
                text(GeodataWidgets.BLOCKED, "- [" + name + "] = …");
            }
            for (var line : pch.addedLines()) {
                ImGui.setCursorPosX(ImGui.getCursorPosX() + 24);
                text(GeodataWidgets.OPEN, "+ " + line);
            }
            if (mono != null) {
                ImGui.popFont();
            }
        }
        ImGui.endChild();
        space(GeodataWidgets.BLOCK);
        if (ImGui.checkbox("Back up the current file first", backupOnSave)) {
            backupOnSave = !backupOnSave;
        }
        text(
                GeodataWidgets.FAINT,
                backupOnSave
                        ? "As "
                                + SpawnSource.backupName(NpcDataSource.NPCS, LocalDateTime.now())
                                + " next to it"
                                + (pch != null && !pch.isEmpty() ? ", and npc_pch.txt the same way" : "")
                        : "No copy: the file is replaced");
        var buttons = 2 * 96 + 8;
        ImGui.sameLine(ImGui.getWindowContentRegionMaxX() - buttons);
        if (HudWidgets.button("npc-review-cancel", null, "Cancel", 96, 28, false, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)
                || GeodataWidgets.wasClickedOutside()) {
            review = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine(0, 8);
        ImGui.beginDisabled(blocked);
        if (HudWidgets.button("npc-review-save", null, "Save", 96, 28, true, true)
                || !blocked && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter))) {
            source.save(backupOnSave);
            review = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                blocked
                        ? "Fix the problems listed above first."
                        : pch != null && !pch.isEmpty()
                                ? "Write the changes to npcdata.txt and npc_pch.txt."
                                : "Write the changes to npcdata.txt.");
        ImGui.endPopup();
    }

    private void drawBatchSection(Section section, @Nullable ImFont mono) {
        var key = section.title();
        var open = reviewOpen.contains(key);
        ImGui.pushID("batch/" + key);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        if (ImGui.invisibleButton("toggle", ImGui.getContentRegionAvailX(), ImGui.getFontSize() + 4)) {
            if (open) {
                reviewOpen.remove(key);
            } else {
                reviewOpen.add(key);
            }
        }
        var hovered = ImGui.isItemHovered();
        var draw = ImGui.getWindowDrawList();
        NpcPanelWidgets.chevron(
                draw,
                x + 6,
                y + (ImGui.getFontSize() + 4) * .5f,
                open,
                hovered ? GeodataWidgets.TEXT : GeodataWidgets.MUTED);
        draw.addText(x + 18, y + 2, HudWidgets.TEXT, section.title());
        draw.addText(
                x + 26 + ImGui.calcTextSizeX(section.title()),
                y + 2,
                GeodataWidgets.FAINT,
                open ? "" : "· click for each NPC");
        if (open) {
            var byRecord = new LinkedHashMap<Integer, List<Change>>();
            for (var change : section.changes()) {
                byRecord.computeIfAbsent(change.record(), unused -> new ArrayList<>())
                        .add(change);
            }
            for (var changes : byRecord.values()) {
                space(GeodataWidgets.ROW);
                drawNpcChanges(changes, mono, "  ");
            }
        }
        ImGui.popID();
    }

    private void drawNpcChanges(List<Change> changes, @Nullable ImFont mono, String indent) {
        if (changes.isEmpty()) {
            return;
        }
        var head = changes.getFirst();
        var document = source.getDocument();
        var record = document == null || head.record() >= document.size() ? null : document.record(head.record());
        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.calcTextSizeX(indent));
        text(HudWidgets.TEXT, record == null ? head.npc() : displayName(record));
        ImGui.sameLine(0, 8);
        text(GeodataWidgets.FAINT, head.npc() + " · line " + head.line());
        for (var change : changes) {
            text(GeodataWidgets.MUTED, indent + "  " + settingName(change.key()));
            for (var detail : change.details()) {
                text(
                        change.key().equals(NpcDataReview.REMOVED)
                                ? GeodataWidgets.BLOCKED
                                : change.key().equals(NpcDataReview.ADDED) ? GeodataWidgets.OPEN : HudWidgets.TEXT,
                        indent + "    " + detail);
            }
            drawValueLines(change, mono);
        }
    }

    private void drawIssueList(List<Issue> issues, int color) {
        for (var i = 0; i < Math.min(issues.size(), LISTED_ISSUES); i++) {
            var issue = issues.get(i);
            ImGui.pushTextWrapPos(0);
            text(color, "• " + issue.npc() + ": " + issue.message());
            ImGui.popTextWrapPos();
        }
        if (issues.size() > LISTED_ISSUES) {
            text(GeodataWidgets.FAINT, (issues.size() - LISTED_ISSUES) + " more");
        }
    }

    private static boolean isUnsaved(NpcDataDocument document, int index) {
        return !Objects.equals(
                document.getState().edits().get(index),
                document.getSavedState().edits().get(index));
    }

    private static int readLevel(NpcDataDocument document, int record) {
        try {
            return Integer.parseInt(Objects.requireNonNullElse(document.getValue(record, "level"), "0")
                    .strip());
        } catch (NumberFormatException notLevel) {
            return 0;
        }
    }

    private static String expectedText(Expected expected) {
        return formatCount(expected.adena())
                + " Adena · "
                + formatCount(expected.drops())
                + (Math.abs(expected.drops() - 1) < 1e-9 ? " item" : " items");
    }

    private static String formatCount(double value) {
        if (value >= 10_000) {
            return SpawnPanelWidgets.compact(Math.round(value));
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%,d", Math.round(value));
        }
        if (value >= .05 || value == 0) {
            return ScriptValue.number(Math.round(value * 10) / 10.0);
        }
        return "<0.1";
    }

    private static String lockKey(int record, String key, int group, String item) {
        return record + "/" + key + "/" + group + "/" + item;
    }

    private static String skillSource(@Nullable SkillCell skill) {
        if (skill == null) {
            return "not in its skill_list";
        }
        return skill.name()
                + (skill.id() >= 0 ? " · id " + skill.id() + " · Lv " + skill.level() : " · not in skilldata.txt");
    }

    private static String statRange(Band band) {
        var low = statNumber(band.q1());
        var high = statNumber(band.q3());
        return low.equals(high) ? low : low + "–" + high;
    }

    private static String statNumber(double value) {
        if (Math.abs(value) >= 100) {
            return String.format(Locale.ROOT, "%,d", Math.round(value));
        }
        return ScriptValue.number(Math.round(value * 100) / 100.0);
    }

    private static boolean matchesItemFilter(ServerItems.Item item, int filter) {
        return switch (filter) {
            case 1 -> item.type().equals("weapon");
            case 2 -> item.type().equals("armor");
            case 3 -> item.type().equals("accessary");
            case 4 ->
                !item.type().equals("weapon")
                        && !item.type().equals("armor")
                        && !item.type().equals("accessary");
            default -> true;
        };
    }

    private static boolean isNpcSkill(String name, Map<String, Integer> use) {
        return use.containsKey(name) || name.startsWith("s_npc") || name.startsWith("s_raid");
    }

    private static String npcCount(int npcs) {
        return npcs == 0 ? "" : String.format(Locale.ROOT, "%,d %s", npcs, npcs == 1 ? "NPC" : "NPCs");
    }

    private static String itemTitle(String name, ClientCatalog.@Nullable Item client) {
        if (client != null && !client.name().isBlank()) {
            return client.addName().isBlank() ? client.name() : client.name() + " " + client.addName();
        }
        return name;
    }

    private static String itemTip(String name, ServerItems.@Nullable Item server, ClientCatalog.@Nullable Item client) {
        var tip = new StringBuilder(itemTitle(name, client));
        if (server != null) {
            tip.append("\n").append(name).append(" · id ").append(server.id());
            if (!server.type().isEmpty()) {
                tip.append(" · ").append(server.type());
            }
            if (!server.grade().equals("none")) {
                tip.append(" · ")
                        .append(server.grade().toUpperCase(Locale.ROOT))
                        .append(" grade");
            }
        }
        if (client != null && !client.description().isBlank()) {
            tip.append("\n\n").append(wrap(client.description()));
        }
        return tip.toString();
    }

    private static String skillTitle(
            String name, ServerSkills.@Nullable Skill server, ClientCatalog.@Nullable Skill client) {
        if (client != null && !client.name().isBlank()) {
            return client.name() + (server != null && server.level() > 1 ? " Lv " + server.level() : "");
        }
        return name;
    }

    private static String skillTip(
            String name, ServerSkills.@Nullable Skill server, ClientCatalog.@Nullable Skill client) {
        var tip = new StringBuilder(skillTitle(name, server, client));
        if (server != null) {
            tip.append("\n")
                    .append(name)
                    .append(" · id ")
                    .append(server.id())
                    .append(" level ")
                    .append(server.level());
            var kind = describeOperateType(server.operateType());
            if (!kind.isEmpty()) {
                tip.append(" · ").append(kind);
            }
        }
        if (client != null && !client.description().isBlank()) {
            tip.append("\n\n").append(wrap(client.description()));
        }
        return tip.toString();
    }

    private static String describeOperateType(String type) {
        if (type.isEmpty()) {
            return "";
        }
        if (type.equals("P")) {
            return "passive";
        }
        if (type.equals("T")) {
            return "toggle";
        }
        return type.startsWith("A") ? "active" : type;
    }

    private static String wrap(String text) {
        var out = new StringBuilder();
        var line = 0;
        for (var word : text.split(" ")) {
            if (line > 0 && line + word.length() > 64) {
                out.append('\n');
                line = 0;
            } else if (line > 0) {
                out.append(' ');
                line++;
            }
            out.append(word);
            line = word.contains("\n") ? word.length() - word.lastIndexOf('\n') - 1 : line + word.length();
        }
        return out.toString();
    }

    private static void drawUnreadableNote(String key, IllegalArgumentException failure) {
        space(GeodataWidgets.ROW);
        ImGui.pushTextWrapPos(0);
        text(GeodataWidgets.WARNING, key + " cannot be read here: " + failure.getMessage() + ". Edit it under All.");
        ImGui.popTextWrapPos();
    }

    private static void space(float pixels) {
        GeodataWidgets.space(pixels);
    }

    private static void text(int color, String text) {
        ImGui.pushStyleColor(ImGuiCol.Text, color);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
    }

    private static List<Issue> filterErrors(List<Issue> issues) {
        return issues.stream().filter(Issue::error).toList();
    }

    private static List<Issue> filterWarnings(List<Issue> issues) {
        return issues.stream().filter(issue -> !issue.error()).toList();
    }

    private static String count(int value, String noun) {
        return String.format(Locale.ROOT, "%,d %s%s", value, noun, value == 1 ? "" : "s");
    }

    private static String pchSummary(NpcPch.@Nullable Change pch) {
        if (pch == null || pch.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        if (!pch.addedLines().isEmpty()) {
            parts.add("+" + count(pch.addedLines().size(), "line"));
        }
        if (!pch.removedNames().isEmpty()) {
            parts.add("-" + count(pch.removedNames().size(), "line"));
        }
        return " · " + NpcDataSource.PCH + ": " + String.join(", ", parts);
    }

    private static String settingName(String key) {
        var name = switch (key) {
            case DropList.DROP -> "Drop";
            case DropList.SPOIL -> "Spoil";
            case DropList.HERBS -> "Herbs";
            case DropList.EXTRA -> "Extra drop";
            case DropList.MAKE -> "Item make list";
            case NpcDataReview.ADDED -> "New NPC";
            case NpcDataReview.REMOVED -> "Deleted";
            case NpcValues.SKILLS -> "Skill list";
            case NpcValues.AI -> "AI";
            default -> "";
        };
        return name.isEmpty() ? key : key.startsWith("(") ? name : name + " · " + key;
    }

    private static void drawValueLines(Change change, @Nullable ImFont mono) {
        if (change.lines().isEmpty()) {
            return;
        }
        if (mono != null) {
            ImGui.pushFont(mono, 13f);
        }
        var draw = ImGui.getWindowDrawList();
        for (var line : change.lines()) {
            var color = line.removed() ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN;
            var dim = (color & 0x00FFFFFF) | 0x99000000;
            var mark = (color & 0x00FFFFFF) | 0x38000000;
            var whole = line.spans().size() == 1 && line.spans().getFirst().changed();
            ImGui.setCursorPosX(ImGui.getCursorPosX() + 24);
            text(color, line.removed() ? "-" : "+");
            for (var span : line.spans()) {
                ImGui.sameLine(0, span == line.spans().getFirst() ? ImGui.calcTextSizeX(" ") : 0);
                if (span.changed() && !whole) {
                    var x = ImGui.getCursorScreenPosX();
                    var y = ImGui.getCursorScreenPosY();
                    draw.addRectFilled(
                            x - 1,
                            y,
                            x + ImGui.calcTextSizeX(span.text()) + 1,
                            y + ImGui.getTextLineHeight(),
                            mark,
                            2f);
                }
                text(span.changed() ? color : dim, span.text());
            }
        }
        if (mono != null) {
            ImGui.popFont();
        }
    }
}

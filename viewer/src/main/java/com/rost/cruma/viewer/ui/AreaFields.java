package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.area.AreaChecks;
import com.rost.cruma.core.area.AreaDocument;
import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.area.AreaEdits;
import com.rost.cruma.core.area.AreaKeys;
import com.rost.cruma.core.area.AreaPoint;
import com.rost.cruma.core.area.AreaSkills;
import com.rost.cruma.core.area.AreaSkills.Skill;
import com.rost.cruma.core.area.AreaSummary;
import com.rost.cruma.core.area.AreaTimes;
import com.rost.cruma.core.area.AreaType;
import com.rost.cruma.core.area.ServerArea;
import com.rost.cruma.core.spawn.Polygons;
import com.rost.cruma.core.spawn.SpawnEdits;
import com.rost.cruma.core.spawn.TerritoryPoint;
import com.rost.cruma.viewer.spawn.AreaStyle;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

final class AreaFields {
    static final List<AreaType> COMMON_TYPES = List.of(
            AreaType.POISON,
            AreaType.WATER,
            AreaType.INSTANT_SKILL,
            AreaType.PEACE_ZONE,
            AreaType.NO_RESTART,
            AreaType.SSQ_ZONE,
            AreaType.BATTLE_ZONE,
            AreaType.DAMAGE,
            AreaType.SWAMP,
            AreaType.TELEPORT,
            AreaType.MOTHER_TREE);

    private static final float ROW = GeodataWidgets.ROW;
    private static final float BLOCK = GeodataWidgets.BLOCK;

    private static final int PART_HALF = 200;

    private static final List<String> TARGETS = List.of("all", "pc", "npc", "only_pc");

    private static final List<String> RACES = List.of("all", "human", "elf", "darkelf", "orc", "dwarf", "kamael");

    private static final Set<String> SHAPE_KEYS =
            Set.of("name", "map_no", "type", "range", "water_range", "banned_range");

    private static final String TYPE_LIST = "##area-type";

    private static final String UNKNOWN_PURPOSE = "a type this tool does not know";

    private static final String NOT_IN_FILE_TIP =
            "No area of this file has this type, and a server of another version may not know it.";

    private final Host host;
    private final ImString typeFilter = new ImString(64);
    private final ImString skillFilter = new ImString(96);
    private final ImString keyFilter = new ImString(64);
    private final ImString addingValue = new ImString(256);
    private final ImString customWord = new ImString(64);
    private final ImString customKey = new ImString(64);
    private boolean timerOpen;
    private boolean otherOpen;
    private boolean heightsOpen;
    private boolean cornersOpen;
    private boolean partsOpen;
    private @Nullable String addingKey;

    private boolean addingCustom;

    private boolean focusCustomKey;

    private boolean reopenTypeList;

    private @Nullable String reopenMenu;

    private @Nullable State skillState;
    private List<Skill> skills = List.of();

    AreaFields(Host host) {
        this.host = host;
    }

    interface Host {
        void edit(String key, String label, UnaryOperator<State> change);

        void apply(String label, UnaryOperator<State> change);

        boolean isPlacing(int area, boolean restart);

        void togglePlacing(int area, boolean restart);

        boolean isDrawingPart(int area);

        void toggleDrawingPart(int area);

        int @Nullable [] findGroundRange(ServerArea area);

        @Nullable
        String getMarkedField();

        void flyTo(double x, double y, double z);

        void setMessage(String text);

        Set<String> getFileTypes();
    }

    static List<AreaType> orderTypes() {
        var types = new ArrayList<>(COMMON_TYPES);
        for (var type : AreaType.values()) {
            if (!types.contains(type)) {
                types.add(type);
            }
        }
        return types;
    }

    static State setPoint(State state, int index, boolean restart, int slot, @Nullable AreaPoint place) {
        var area = state.area(index);
        if (area == null) {
            return state;
        }
        var points = new ArrayList<>(restart ? area.restartPoints() : area.teleportPoints());
        if (slot >= points.size()) {
            return state;
        }
        if (place == null) {
            points.remove(slot);
        } else {
            points.set(slot, place);
        }
        return restart
                ? AreaEdits.setRestartPoints(state, index, points)
                : AreaEdits.setTeleportPoints(state, index, points);
    }

    static State moveCorner(State state, int index, int part, int corner, int x, int y) {
        var area = state.area(index);
        if (area == null) {
            return state;
        }
        if (part >= 0) {
            if (part >= area.banned().size()
                    || corner >= area.banned().get(part).size()) {
                return state;
            }
            var parts = new ArrayList<>(area.banned());
            var points = new ArrayList<>(parts.get(part));
            points.set(corner, points.get(corner).withXy(x, y));
            parts.set(part, points);
            return AreaEdits.setBanned(state, index, parts);
        }
        if (corner >= area.points().size()) {
            return state;
        }
        return AreaEdits.setPoints(state, index, movedCorners(area, corner, x, y));
    }

    static State insertCorner(State state, int index, int part, int after, int x, int y) {
        var area = state.area(index);
        if (area == null || part >= area.banned().size()) {
            return state;
        }
        var points = new ArrayList<>(part < 0 ? area.points() : area.banned().get(part));
        if (after >= points.size()) {
            return state;
        }
        var before = points.get(after);
        var next = points.get((after + 1) % points.size());
        points.add(
                after + 1,
                new TerritoryPoint(x, y, Math.min(before.bottom(), next.bottom()), Math.max(before.top(), next.top())));
        return withOutline(state, area, part, points);
    }

    static State removeCorner(State state, int index, int part, int corner) {
        var area = state.area(index);
        if (area == null || part >= area.banned().size()) {
            return state;
        }
        var points = new ArrayList<>(part < 0 ? area.points() : area.banned().get(part));
        if (corner >= points.size() || points.size() <= 3) {
            return state;
        }
        points.remove(corner);
        return withOutline(state, area, part, points);
    }

    static List<TerritoryPoint> movedCorners(ServerArea area, int corner, int x, int y) {
        var points = new ArrayList<>(area.points());
        if (area.isWater() && points.size() == 4) {
            var across = points.get((corner + 2) % 4);
            return List.of(
                    points.get(0).withXy(x, y),
                    points.get(0).withXy(across.x(), y),
                    points.get(0).withXy(across.x(), across.y()),
                    points.get(0).withXy(x, across.y()));
        }
        points.set(corner, points.get(corner).withXy(x, y));
        return points;
    }

    static @Nullable String refuseWord(String word, String what) {
        if (word.isEmpty() || AreaEdits.isValidWord(word)) {
            return null;
        }
        if (AreaEdits.isRecordWord(word)) {
            return word + " starts or ends a record.";
        }
        var first = word.charAt(0);
        if (!(first >= 'a' && first <= 'z' || first >= 'A' && first <= 'Z')) {
            return "A " + what + " starts with a letter A to Z.";
        }
        return "Only letters A to Z, digits and _, without spaces.";
    }

    static String listedWord(String word, Set<String> fileTypes) {
        if (fileTypes.contains(word) || AreaType.find(word) != null) {
            return word;
        }
        for (var listed : fileTypes) {
            if (listed.equalsIgnoreCase(word)) {
                return listed;
            }
        }
        for (var type : AreaType.values()) {
            if (type.getKeyword().equalsIgnoreCase(word)) {
                return type.getKeyword();
            }
        }
        return word;
    }

    static List<String> fileWords(Set<String> fileTypes) {
        var words = new ArrayList<String>();
        for (var type : orderTypes()) {
            if (fileTypes.contains(type.getKeyword())) {
                words.add(type.getKeyword());
            }
        }
        fileTypes.stream().filter(word -> AreaType.find(word) == null).sorted().forEach(words::add);
        return words;
    }

    static List<AreaType> otherTypes(Set<String> fileTypes) {
        if (fileTypes.isEmpty()) {
            return orderTypes().subList(COMMON_TYPES.size(), AreaType.values().length);
        }
        return orderTypes().stream()
                .filter(type -> !fileTypes.contains(type.getKeyword()))
                .toList();
    }

    @Nullable
    String drawTypeMenu(String id, Set<String> fileTypes) {
        String chosen = null;
        var others = id + "-others";
        var custom = id + "-custom";
        var empty = fileTypes.isEmpty();
        var first = empty ? COMMON_TYPES.stream().map(AreaType::getKeyword).toList() : fileWords(fileTypes);
        var rest = otherTypes(fileTypes);
        var firstRows = first.size() + (rest.isEmpty() ? 1 : 2);
        if (HudWidgets.beginFittedMenu(id, 400, HudWidgets.menuHeight(firstRows, 1))) {
            for (var word : first) {
                if (wordRow(id, word)) {
                    chosen = word;
                }
            }
            HudWidgets.menuRule();
            if (!rest.isEmpty()) {
                if (HudWidgets.menuRow(id + "/others", Glyph.MORE, empty ? "Rare types…" : "Other types…", "")) {
                    reopenMenu = others;
                }
                GeodataWidgets.tip(
                        empty
                                ? "The " + rest.size() + " types that real files hardly use, such as siege zones."
                                : "The " + rest.size() + " types this tool knows that no area of this file has.");
            }
            if (HudWidgets.menuRow(id + "/custom", Glyph.PENCIL, "Custom type…", "")) {
                customWord.set("");
                reopenMenu = custom;
            }
            GeodataWidgets.tip("A type word of your own, for a server that knows types this tool does not list.");
            HudWidgets.endMenu();
        }
        if (others.equals(reopenMenu)) {
            ImGui.openPopup(others);
            reopenMenu = null;
        }
        var back = empty ? "Common types" : "Types of this file";
        if (HudWidgets.beginFittedMenu(others, 400, HudWidgets.menuHeight(rest.size() + (empty ? 1 : 2), 1))) {
            if (HudWidgets.menuRow(others + "/back", Glyph.CHEVRON_LEFT, back, "")) {
                reopenMenu = id;
            }
            HudWidgets.menuRule();
            if (!empty) {
                HudWidgets.menuNote("Not in this file");
            }
            for (var type : rest) {
                if (typeRow(others, type, !empty)) {
                    chosen = type.getKeyword();
                }
                if (!empty) {
                    GeodataWidgets.tip(NOT_IN_FILE_TIP);
                }
            }
            HudWidgets.endMenu();
        }
        if (custom.equals(reopenMenu)) {
            ImGui.openPopup(custom);
            reopenMenu = null;
        }
        var typed = drawWordMenu(custom, 400, back, () -> reopenMenu = id, fileTypes);
        if (typed != null) {
            chosen = typed;
        }
        if (id.equals(reopenMenu)) {
            ImGui.openPopup(id);
            reopenMenu = null;
        }
        return chosen;
    }

    void drawSettings(AreaDocument document, ServerArea area) {
        drawIdentity(area);
        drawTypeFields(document, area);
        GeodataWidgets.space(BLOCK);
        drawTimer(area);
        drawOtherSettings(area);
    }

    void drawMarked(AreaDocument document, ServerArea area) {
        var marked = host.getMarkedField();
        if (marked == null || area.hasSetting(marked)) {
            return;
        }
        if (marked.equals("skill_name")) {
            GeodataWidgets.space(ROW);
            drawSkillRow(document, area);
        } else if (marked.equals("teleport_points")) {
            drawPoints(area, false);
        }
    }

    void drawShape(ServerArea area) {
        drawHeights(area);
        drawCorners(area);
        drawParts(area);
    }

    private void drawIdentity(ServerArea area) {
        var name = new ImString(area.name(), 128);
        var half = GeodataWidgets.columnWidth(2);
        var changed = GeodataWidgets.columns(
                () -> {
                    var left = GeodataWidgets.compactText("Name", "##area-name", "such as giran_peace_01", name, half);
                    GeodataWidgets.tip("name in the file: the name scripts and events find the area by. Letters A to"
                            + " Z, digits and underscores.");
                    return left;
                },
                () -> drawTypeList(area, half));
        if (changed[0]) {
            rename(area, name.get().strip());
        }
        GeodataWidgets.space(ROW);
        var note = new ImString(area.note(), 256);
        if (GeodataWidgets.compactText("Note", "##area-note", "No note", note, -1)) {
            var text = AreaEdits.cleanNote(note.get());
            host.edit(
                    "area/" + area.index() + "/note",
                    "Change note",
                    state -> AreaEdits.setNote(state, area.index(), text));
        }
        GeodataWidgets.tip("The // line right above the area in the file. Empty removes the line.");
    }

    private void rename(ServerArea area, String name) {
        if (name.equals(area.name())) {
            return;
        }
        if (!AreaEdits.isValidName(name)) {
            host.setMessage("A name uses the letters A to Z, digits and underscores, such as giran_peace_01, and"
                    + " cannot be empty.");
            return;
        }
        host.apply("Rename area", state -> AreaEdits.rename(state, area.index(), name));
    }

    private boolean drawTypeList(ServerArea area, float width) {
        ImGui.beginGroup();
        ImGui.textColored(GeodataWidgets.MUTED, "Type");
        if (reopenTypeList) {
            GeodataWidgets.openSearchList(TYPE_LIST);
            reopenTypeList = false;
        }
        var fileTypes = host.getFileTypes();
        var empty = fileTypes.isEmpty();
        var custom = "area-type-custom";
        var filter = GeodataWidgets.searchList(TYPE_LIST, AreaStyle.title(area), typeFilter, "Find a type", width);
        String chosen = null;
        if (filter != null) {
            ImGui.dummy(360, 0);
            var first = empty ? orderTypes().stream().map(AreaType::getKeyword).toList() : fileWords(fileTypes);
            for (var word : first) {
                if (!matches(word, filter)) {
                    continue;
                }
                if (empty && filter.isEmpty() && isFirstRare(AreaType.find(word))) {
                    HudWidgets.menuRule();
                }
                if (typeItem(word, area, false)) {
                    chosen = word;
                }
            }
            var header = false;
            for (var type : empty ? List.<AreaType>of() : otherTypes(fileTypes)) {
                var word = type.getKeyword();
                if (!matches(word, filter)) {
                    continue;
                }
                if (!header) {
                    GeodataWidgets.space(4);
                    ImGui.textColored(GeodataWidgets.FAINT, "Not in this file");
                    header = true;
                }
                if (typeItem(word, area, true)) {
                    chosen = word;
                }
                GeodataWidgets.tip(NOT_IN_FILE_TIP);
            }
            if (filter.isEmpty()) {
                HudWidgets.menuRule();
            }
            if (GeodataWidgets.listItem("custom", "Custom type…", "", false)) {
                var typed = typeFilter.get().strip();
                customWord.set(AreaEdits.isValidName(typed) ? typed : "");
                reopenMenu = custom;
            }
            GeodataWidgets.tip("A type word of your own, for a server that knows types this tool does not list.");
            ImGui.endCombo();
        }
        if (custom.equals(reopenMenu)) {
            ImGui.openPopup(custom);
            reopenMenu = null;
        }
        var typed = drawWordMenu(custom, 360, "All types", () -> reopenTypeList = true, fileTypes);
        if (typed != null) {
            chosen = typed;
        }
        ImGui.endGroup();
        GeodataWidgets.tip("What the area does. In the file: type=" + area.typeWord()
                + ". Settings the new type does not read move to Other settings, so nothing is lost.");
        if (chosen != null && !chosen.equals(area.typeWord())) {
            var word = chosen;
            host.apply("Change type", state -> AreaEdits.setSetting(state, area.index(), "type", word));
        }
        return false;
    }

    private @Nullable String drawWordMenu(String id, float width, String back, Runnable goBack, Set<String> fileTypes) {
        if (!HudWidgets.beginFittedMenu(id, width, HudWidgets.menuHeight(3, 1))) {
            return null;
        }
        if (HudWidgets.menuRow(id + "/back", Glyph.CHEVRON_LEFT, back, "")) {
            goBack.run();
        }
        HudWidgets.menuRule();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var frame = ImGui.getFrameHeight();
        var useWidth = 64f;
        var gap = 8f;
        ImGui.setCursorScreenPos(x + 4, y + (HudWidgets.MENU_ROW - frame) * .5f);
        if (ImGui.isWindowAppearing() || !ImGui.isAnyItemActive()) {
            ImGui.setKeyboardFocusHere();
        }
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - useWidth - gap - 4);
        ImGui.pushStyleColor(ImGuiCol.NavCursor, 0);
        ImGui.inputTextWithHint("##word", "such as my_zone", customWord);
        var entered = hasEnterEndedEdit();
        ImGui.popStyleColor();
        GeodataWidgets.tip("The word goes into the file as type=. Your server must know it.");
        var word = customWord.get().strip();
        var refusal = refuseWord(word, "type");
        ImGui.sameLine(0, gap);
        var takes = !word.isEmpty() && refusal == null;
        ImGui.beginDisabled(!takes);
        var use = HudWidgets.button(id + "/use", null, "Use", useWidth, frame, true, true);
        ImGui.endDisabled();
        ImGui.setCursorScreenPos(x, y + HudWidgets.MENU_ROW);
        if (refusal != null) {
            HudWidgets.menuNote(refusal, GeodataWidgets.WARNING);
        } else {
            HudWidgets.menuNote("Use letters A to Z, digits and _. Start with a letter.");
        }
        String taken = null;
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            ImGui.closeCurrentPopup();
        } else if ((entered || use) && takes) {
            taken = listedWord(word, fileTypes);
            ImGui.closeCurrentPopup();
        }
        HudWidgets.endMenu();
        return taken;
    }

    private void drawTypeFields(AreaDocument document, ServerArea area) {
        var type = area.type();
        if (type == null) {
            return;
        }
        var defaults = document.getFile().getDefaultValues();
        var shown = new LinkedHashSet<String>();
        var half = GeodataWidgets.columnWidth(2);
        var third = GeodataWidgets.columnWidth(3);
        if (type.isInstant()) {
            GeodataWidgets.space(ROW);
            GeodataWidgets.columns(
                    () -> intField(area, "unit_tick", "Every N ticks", area.effectiveUnitTick(), 1, 100_000, half),
                    () -> drawTarget(area, half));
            shown.add("unit_tick");
            shown.add("target");
        }
        switch (type) {
            case POISON, INSTANT_SKILL -> drawSkillFields(document, area, shown);
            case DAMAGE -> {
                GeodataWidgets.space(ROW);
                GeodataWidgets.columns(
                        () -> intField(
                                area, "damage_on_hp", "HP per tick", defaults.damageOnHp(), -100_000, 100_000, third),
                        () -> intField(
                                area, "damage_on_mp", "MP per tick", defaults.damageOnMp(), -100_000, 100_000, third),
                        () -> textField(area, "message_no", "Message", "none", third));
                shown.addAll(List.of("damage_on_hp", "damage_on_mp", "message_no"));
            }
            case SWAMP -> {
                GeodataWidgets.space(ROW);
                GeodataWidgets.columns(
                        () -> intField(area, "move_bonus", "Speed change, %", defaults.moveBonus(), -100, 1000, half));
                shown.add("move_bonus");
            }
            case MOTHER_TREE -> {
                GeodataWidgets.space(ROW);
                GeodataWidgets.columns(
                        () -> drawRace(area, third),
                        () -> intField(
                                area, "hp_regen_bonus", "HP regen bonus", defaults.hpRegenBonus(), -1000, 1000, third),
                        () -> intField(
                                area, "mp_regen_bonus", "MP regen bonus", defaults.mpRegenBonus(), -1000, 1000, third));
                GeodataWidgets.space(ROW);
                GeodataWidgets.columns(
                        () -> textField(area, "entering_message_no", "Entering message", "none", half),
                        () -> textField(area, "leaving_message_no", "Leaving message", "none", half));
                shown.addAll(List.of(
                        "affect_race",
                        "hp_regen_bonus",
                        "mp_regen_bonus",
                        "entering_message_no",
                        "leaving_message_no"));
            }
            case NO_RESTART -> {
                GeodataWidgets.space(ROW);
                GeodataWidgets.columns(
                        () -> timeField(area, "restart_time", "Restart after", ServerArea.DEFAULT_RESTART_TIME, half),
                        () -> timeField(area, "restart_allowed_time", "Restart allowed", -1, half));
                drawPoints(area, true);
                shown.addAll(List.of("restart_time", "restart_allowed_time", "restart_points"));
            }
            case SSQ_ZONE -> {
                GeodataWidgets.space(ROW);
                GeodataWidgets.columns(
                        () -> textField(area, "exp_penalty_per", "XP penalty, %", "not set", half),
                        () -> drawItemDrop(area, half));
                shown.addAll(List.of("exp_penalty_per", "item_drop"));
            }
            case PEACE_ZONE -> {
                drawBlockedActions(area);
                shown.add("blocked_actions");
            }
            case TELEPORT -> {
                if (!isDrawnAbove("teleport_points", area)) {
                    drawPoints(area, false);
                }
                shown.add("teleport_points");
                if (area.hasSetting("teleport_range")) {
                    GeodataWidgets.space(ROW);
                    textField(area, "teleport_range", "Teleport range", "", -1);
                }
                shown.add("teleport_range");
            }
            default -> {}
        }
        for (var key : AreaKeys.usedBy(type)) {
            if (!shown.contains(key) && (area.hasSetting(key) || key.equals("enter_inzone_id"))) {
                GeodataWidgets.space(ROW);
                textField(area, key, AreaKeys.label(key), "none", -1);
            }
        }
    }

    private void drawSkillFields(AreaDocument document, ServerArea area, Set<String> shown) {
        if (!isDrawnAbove("skill_name", area)) {
            GeodataWidgets.space(ROW);
            drawSkillRow(document, area);
        }
        if (area.hasSetting("skill_list")) {
            GeodataWidgets.space(ROW);
            textField(area, "skill_list", "Skills", "{@s_area_haste2;@s_area_focus3}", -1);
            GeodataWidgets.space(ROW);
            GeodataWidgets.columns(() -> drawOrder(area, GeodataWidgets.columnWidth(2)), () -> false);
        }
        shown.addAll(List.of("skill_name", "skill_prob", "skill_list", "skill_action_type"));
    }

    private void drawSkillRow(AreaDocument document, ServerArea area) {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var chanceWidth = 96f;
        var skillWidth = ImGui.getContentRegionAvailX() - chanceWidth - spacing;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        drawSkillList(document, area, skillWidth);
        var bottom = ImGui.getItemRectMaxY();
        ImGui.setCursorScreenPos(x + skillWidth + spacing, y);
        intField(area, "skill_prob", "Chance, %", ServerArea.DEFAULT_SKILL_PROB, 0, 100, chanceWidth);
        bottom = Math.max(bottom, ImGui.getItemRectMaxY());
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(0, 0);
    }

    private boolean isDrawnAbove(String key, ServerArea area) {
        return key.equals(host.getMarkedField()) && !area.hasSetting(key);
    }

    private void drawSkillList(AreaDocument document, ServerArea area, float width) {
        var state = document.getState();
        if (state != skillState) {
            skills = AreaSkills.byUse(state);
            skillState = state;
        }
        var current = area.get("skill_name");
        var name = current == null ? "" : AreaSkills.strip(current);
        var marked = "skill_name".equals(host.getMarkedField()) && name.isEmpty();
        ImGui.beginGroup();
        var set = current != null;
        var reset = drawFieldLabel("skill_name", "Skill", set, false, width);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var filter = GeodataWidgets.searchList(
                "##area-skill",
                name.isEmpty() ? "Choose a skill" : name,
                name.isEmpty() ? GeodataWidgets.WARNING : GeodataWidgets.TEXT,
                skillFilter,
                "Find a skill, or type its name",
                width);
        if (marked) {
            ImGui.getWindowDrawList()
                    .addRect(
                            x - 2,
                            y - 2,
                            ImGui.getItemRectMaxX() + 2,
                            ImGui.getItemRectMaxY() + 2,
                            GeodataWidgets.WARNING,
                            6,
                            0,
                            1.5f);
        }
        String chosen = null;
        if (filter != null) {
            var typed = skillFilter.get().strip();
            var known = false;
            var listed = 0;
            for (var skill : skills) {
                known |= skill.name().equals(typed);
            }
            if (!typed.isEmpty() && !known && AreaEdits.isValidValue("skill_name", AreaSkills.toSetting(typed))) {
                if (GeodataWidgets.listItem("typed", typed, "new name", false)) {
                    chosen = typed;
                }
            }
            for (var skill : skills) {
                if (!filter.isEmpty() && !skill.name().toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                if (listed++ >= 300) {
                    break;
                }
                var uses = skill.uses() == 1 ? "1 area" : skill.uses() + " areas";
                if (GeodataWidgets.listItem(
                        skill.name(), skill.name(), uses, skill.name().equals(name))) {
                    chosen = skill.name();
                }
            }
            ImGui.endCombo();
        }
        ImGui.endGroup();
        GeodataWidgets.tip(
                "skill_name: the skill it casts, as npcdata and the skill files name it. The list holds the skills"
                        + " this file casts, the most used first; type a name that is not in it to use that one.");
        if (chosen != null) {
            var value = AreaSkills.toSetting(chosen);
            host.apply("Change skill", s -> AreaEdits.setSetting(s, area.index(), "skill_name", value));
        }
        if (reset) {
            host.apply("Remove skill", s -> AreaEdits.removeSetting(s, area.index(), "skill_name"));
        }
    }

    private boolean drawOrder(ServerArea area, float width) {
        ImGui.beginGroup();
        var set = area.hasSetting("skill_action_type");
        var reset = drawFieldLabel("skill_action_type", "Order", set, true, width);
        var serial = "serial".equals(area.get("skill_action_type"));
        var picked =
                GeodataWidgets.segmented("##area-order", new String[] {"Random", "In order"}, serial ? 1 : 0, width);
        ImGui.endGroup();
        GeodataWidgets.tip(
                "skill_action_type: one skill of the list at random each time, or all of them in order" + " (serial).");
        if (picked >= 0 && picked != (serial ? 1 : 0)) {
            host.apply(
                    "Change skill order",
                    s -> picked == 1
                            ? AreaEdits.setSetting(s, area.index(), "skill_action_type", "serial")
                            : AreaEdits.removeSetting(s, area.index(), "skill_action_type"));
        }
        if (reset) {
            host.apply("Remove skill order", s -> AreaEdits.removeSetting(s, area.index(), "skill_action_type"));
        }
        return false;
    }

    private boolean drawTarget(ServerArea area, float width) {
        ImGui.beginGroup();
        var set = area.hasSetting("target");
        var reset = drawFieldLabel("target", "Works on", set, true, width);
        ImGui.setNextItemWidth(width);
        String chosen = null;
        if (HudWidgets.beginCombo("##area-target", targetLabel(area.target()), 0)) {
            for (var target : TARGETS) {
                if (ImGui.selectable(targetLabel(target), target.equals(area.target()))) {
                    chosen = target;
                }
            }
            ImGui.endCombo();
        }
        ImGui.endGroup();
        GeodataWidgets.tip("target: everyone (all), players and their summons (pc), NPCs (npc) or players only"
                + " (only_pc). Without it, everyone.");
        if (chosen != null) {
            var value = chosen;
            host.apply("Change target", s -> AreaEdits.setSetting(s, area.index(), "target", value));
        }
        if (reset) {
            host.apply("Remove target", s -> AreaEdits.removeSetting(s, area.index(), "target"));
        }
        return false;
    }

    private boolean drawRace(ServerArea area, float width) {
        ImGui.beginGroup();
        var current = area.get("affect_race");
        var reset = drawFieldLabel("affect_race", "Race", current != null, true, width);
        ImGui.setNextItemWidth(width);
        String chosen = null;
        if (HudWidgets.beginCombo("##area-race", current == null ? "Elves" : current.strip(), 0)) {
            for (var race : RACES) {
                if (ImGui.selectable(race, race.equals(current == null ? "" : current.strip()))) {
                    chosen = race;
                }
            }
            ImGui.endCombo();
        }
        ImGui.endGroup();
        GeodataWidgets.tip("affect_race: the race it works on besides elves, or all. Elves always count.");
        if (chosen != null) {
            var value = chosen;
            host.apply("Change race", s -> AreaEdits.setSetting(s, area.index(), "affect_race", value));
        }
        if (reset) {
            host.apply("Remove race", s -> AreaEdits.removeSetting(s, area.index(), "affect_race"));
        }
        return false;
    }

    private boolean drawItemDrop(ServerArea area, float width) {
        ImGui.beginGroup();
        var current = area.get("item_drop");
        var reset = drawFieldLabel("item_drop", "Item drop", current != null, false, width);
        var selected = "on".equals(current) ? 0 : "off".equals(current) ? 1 : -1;
        var picked = GeodataWidgets.segmented("##area-item-drop", new String[] {"On", "Off"}, selected, width);
        ImGui.endGroup();
        GeodataWidgets.tip("item_drop: whether items drop on death inside.");
        if (picked >= 0 && picked != selected) {
            var value = picked == 0 ? "on" : "off";
            host.apply("Change item drop", s -> AreaEdits.setSetting(s, area.index(), "item_drop", value));
        }
        if (reset) {
            host.apply("Remove item drop", s -> AreaEdits.removeSetting(s, area.index(), "item_drop"));
        }
        return false;
    }

    private void drawBlockedActions(ServerArea area) {
        GeodataWidgets.space(ROW);
        var value = area.get("blocked_actions");
        var actions = new ArrayList<String>();
        if (value != null) {
            for (var item : value.replace("{", " ").replace("}", " ").split("[;\\s]+")) {
                if (!item.isEmpty()) {
                    actions.add(item);
                }
            }
        }
        ImGui.textColored(GeodataWidgets.MUTED, "Blocked here");
        var changed = false;
        if (ImGui.checkbox("Private stores##area-priv-store", actions.contains("priv_store"))) {
            toggle(actions, "priv_store");
            changed = true;
        }
        GeodataWidgets.tip("priv_store in blocked_actions: nobody can open a private store to sell or buy here.");
        ImGui.sameLine(0, 16);
        if (ImGui.checkbox("Private workshops##area-priv-rstore", actions.contains("priv_rstore"))) {
            toggle(actions, "priv_rstore");
            changed = true;
        }
        GeodataWidgets.tip("priv_rstore in blocked_actions: nobody can open a private workshop to craft here.");
        if (changed) {
            var next = actions.isEmpty() ? null : "{" + String.join(";", actions) + "}";
            host.apply(
                    "Change blocked actions",
                    s -> next == null
                            ? AreaEdits.removeSetting(s, area.index(), "blocked_actions")
                            : AreaEdits.setSetting(s, area.index(), "blocked_actions", next));
        }
    }

    private void drawPoints(ServerArea area, boolean restart) {
        var points = restart ? area.restartPoints() : area.teleportPoints();
        var key = restart ? "restart_points" : "teleport_points";
        GeodataWidgets.space(BLOCK);
        ImGui.textColored(GeodataWidgets.MUTED, (restart ? "Restart points · " : "Teleport points · ") + points.size());
        GeodataWidgets.tip(
                restart
                        ? "restart_points: where a character that logged out here comes back."
                        : "teleport_points: where the area sends whoever steps in, one of them at random.");
        var marked = key.equals(host.getMarkedField()) && points.isEmpty();
        if (points.isEmpty()) {
            GeodataWidgets.note(
                    restart ? "None: a town of the server's choice." : "None yet: it sends nobody anywhere.",
                    restart ? GeodataWidgets.FAINT : GeodataWidgets.WARNING);
        }
        var spacing = ImGui.getStyle().getItemSpacingX();
        var number = ImGui.calcTextSizeX("00") + 4;
        var field = (ImGui.getContentRegionAvailX() - number - HudWidgets.CONTROL_HEIGHT - 3 * spacing - 2 * 4) / 3;
        int remove = -1;
        for (var i = 0; i < points.size(); i++) {
            var point = points.get(i);
            var slot = i;
            ImGui.pushID(key + i);
            GeodataWidgets.space(i == 0 ? ROW : 2);
            var y = ImGui.getCursorScreenPosY();
            ImGui.alignTextToFramePadding();
            ImGui.textColored(GeodataWidgets.FAINT, Integer.toString(i + 1));
            GeodataWidgets.tip("Double-click a point's number to fly there.");
            if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
                host.flyTo(point.x(), point.y(), point.z());
            }
            ImGui.sameLine(number);
            var values = new int[] {point.x(), point.y(), point.z()};
            var axes = new String[] {"X", "Y", "Z"};
            for (var axis = 0; axis < 3; axis++) {
                var text = new ImString(Integer.toString(values[axis]), 16);
                if (axis > 0) {
                    ImGui.sameLine(0, 4);
                }
                if (GeodataWidgets.axisField(axes[axis], "##" + axes[axis], text, field)) {
                    var typed = parseInt(text.get());
                    if (typed != null) {
                        var moved = values.clone();
                        moved[axis] = typed;
                        var place = new AreaPoint(moved[0], moved[1], moved[2]);
                        host.apply(
                                restart ? "Move restart point" : "Move teleport point",
                                s -> setPoint(s, area.index(), restart, slot, place));
                    }
                }
            }
            ImGui.sameLine(0, spacing);
            ImGui.setCursorScreenPos(
                    ImGui.getCursorScreenPosX(), y + (ImGui.getFrameHeight() - HudWidgets.CONTROL_HEIGHT) * .5f);
            if (HudWidgets.iconButton("remove", Glyph.TRASH, false, GeodataWidgets.BLOCKED)) {
                remove = i;
            }
            GeodataWidgets.tip("Remove this point.");
            ImGui.popID();
        }
        if (remove >= 0) {
            var slot = remove;
            host.apply(
                    restart ? "Remove restart point" : "Remove teleport point",
                    s -> setPoint(s, area.index(), restart, slot, null));
        }
        GeodataWidgets.space(ROW);
        var placing = host.isPlacing(area.index(), restart);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        if (GeodataWidgets.actionButton(
                "area-add-" + key, Glyph.PLUS, placing ? "Click the ground…" : "Add point here", -1, placing)) {
            host.togglePlacing(area.index(), restart);
        }
        if (marked) {
            ImGui.getWindowDrawList()
                    .addRect(
                            x - 2,
                            y - 2,
                            ImGui.getItemRectMaxX() + 2,
                            ImGui.getItemRectMaxY() + 2,
                            GeodataWidgets.WARNING,
                            8,
                            0,
                            1.5f);
        }
        GeodataWidgets.tip(
                placing
                        ? "The next click on the ground places the point. Esc stops."
                        : "The next click on the ground places a point there. Drag a point's mark to move it.");
    }

    private void drawTimer(ServerArea area) {
        var on = area.isOnByDefault();
        var timer = AreaSummary.describeTimer(area);
        var label = "State and timer · " + (on ? "on at start" : "off at start") + (timer.isEmpty() ? "" : " · timer");
        if (HudWidgets.disclosure("area-timer", label, timerOpen)) {
            timerOpen = !timerOpen;
        }
        GeodataWidgets.tip("default_status, event_id, instantzone_id and the timer: initial_delay, on_time, off_time"
                + " and random_time.");
        if (!timerOpen) {
            return;
        }
        GeodataWidgets.space(ROW);
        var next = HudWidgets.switchRow(
                "On when the server starts",
                on,
                true,
                "default_status: an area that starts off does nothing until an event or an AI turns it on.");
        if (next != on) {
            var value = next ? "on" : "off";
            host.apply("Change state at start", s -> AreaEdits.setSetting(s, area.index(), "default_status", value));
        }
        var half = GeodataWidgets.columnWidth(2);
        GeodataWidgets.space(ROW);
        GeodataWidgets.columns(
                () -> textField(area, "event_id", "Event", "none", half),
                () -> textField(area, "instantzone_id", "Instance zones", "all", half));
        GeodataWidgets.space(ROW);
        GeodataWidgets.columns(
                () -> textField(area, "on_time", "On for, sec", Integer.toString(ServerArea.DEFAULT_ON_OFF_TIME), half),
                () -> textField(
                        area, "off_time", "Off for, sec", Integer.toString(ServerArea.DEFAULT_ON_OFF_TIME), half));
        GeodataWidgets.space(ROW);
        GeodataWidgets.columns(
                () -> textField(area, "initial_delay", "First delay, sec", "0", half),
                () -> textField(area, "random_time", "Random, sec", "0", half));
        GeodataWidgets.space(ROW);
        GeodataWidgets.note(
                timer.isEmpty() ? "No timer: it stays as it starts until an event changes it." : timer,
                timer.isEmpty() ? GeodataWidgets.FAINT : GeodataWidgets.MUTED);
        GeodataWidgets.space(ROW);
    }

    private void drawOtherSettings(ServerArea area) {
        var type = area.type();
        var others = new ArrayList<Integer>();
        for (var i = 0; i < area.settings().size(); i++) {
            var key = area.settings().get(i).key();
            if (!AreaKeys.isRead(key, type)) {
                others.add(i);
            }
        }
        if (HudWidgets.disclosure("area-other", "Other settings · " + others.size(), otherOpen)) {
            otherOpen = !otherOpen;
        }
        GeodataWidgets.tip("Settings the type does not read, or the tool has no field for. They stay in the file as"
                + " they are.");
        if (!otherOpen) {
            return;
        }
        GeodataWidgets.space(ROW);
        if (others.isEmpty()) {
            GeodataWidgets.note("None.", GeodataWidgets.FAINT);
        }
        var spacing = ImGui.getStyle().getItemSpacingX();
        var keyWidth = Math.min(116, ImGui.getContentRegionAvailX() * .38f);
        String removed = null;
        for (var at : others) {
            var setting = area.settings().get(at);
            var key = setting.key();
            ImGui.pushID("other" + at);
            var y = ImGui.getCursorScreenPosY();
            GeodataWidgets.rowLabel(HudWidgets.truncateToWidth(key, keyWidth - 4), keyWidth);
            var meaning = AreaKeys.meaning(key);
            GeodataWidgets.tip(
                    meaning.isEmpty()
                            ? key + ": not in the grammar this tool knows. It stays as it is."
                            : key + ": " + meaning
                                    + (type == null ? "" : " The type " + area.typeWord() + " does not read it."));
            var value = new ImString(setting.value(), 512);
            ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX(), y);
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - HudWidgets.CONTROL_HEIGHT - spacing);
            ImGui.inputText("##value", value);
            if (ImGui.isItemDeactivatedAfterEdit()) {
                setRaw(area, key, value.get().strip());
            }
            ImGui.sameLine(0, spacing);
            if (HudWidgets.iconButton("remove", Glyph.TRASH, false, GeodataWidgets.BLOCKED)) {
                removed = key;
            }
            GeodataWidgets.tip("Remove " + key + " from the area.");
            ImGui.popID();
            GeodataWidgets.space(2);
        }
        if (removed != null) {
            var key = removed;
            host.apply("Remove " + key, s -> AreaEdits.removeSetting(s, area.index(), key));
        }
        drawAddSetting(area);
        GeodataWidgets.space(ROW);
    }

    private void drawAddSetting(ServerArea area) {
        GeodataWidgets.space(ROW);
        var adding = addingKey;
        if (adding != null && area.hasSetting(adding)) {
            addingKey = null;
            adding = null;
        }
        var filter = GeodataWidgets.searchList(
                "##area-add-setting",
                addingCustom ? "Custom key" : adding == null ? "Add setting…" : adding,
                addingCustom || adding != null ? GeodataWidgets.TEXT : GeodataWidgets.MUTED,
                keyFilter,
                "Find a key",
                -1);
        if (filter != null) {
            for (var key : AreaKeys.keys()) {
                if (area.hasSetting(key.key())
                        || SHAPE_KEYS.contains(key.key())
                        || !filter.isEmpty()
                                && !key.key().contains(filter)
                                && !key.label().toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                if (GeodataWidgets.listItem(
                        key.key(), key.key(), key.label(), key.key().equals(adding))) {
                    addingKey = key.key();
                    addingCustom = false;
                    addingValue.set("");
                }
            }
            if (filter.isEmpty()) {
                HudWidgets.menuRule();
            }
            if (GeodataWidgets.listItem("custom-key", "Custom key…", "", addingCustom)) {
                var typed = keyFilter.get().strip();
                customKey.set(AreaEdits.isValidName(typed) ? typed : "");
                addingValue.set("");
                addingKey = null;
                addingCustom = true;
                focusCustomKey = true;
            }
            GeodataWidgets.tip("A key of your own, for a server that reads settings this tool does not list.");
            ImGui.endCombo();
        }
        GeodataWidgets.tip("A setting of the server's grammar that the area does not have yet.");
        if (addingCustom) {
            drawCustomKey(area);
            return;
        }
        if (adding == null) {
            return;
        }
        GeodataWidgets.space(ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 64 - spacing);
        ImGui.inputTextWithHint("##area-add-value", "value, as the file writes it", addingValue);
        var entered = hasEnterEndedEdit();
        GeodataWidgets.tip(AreaKeys.meaning(adding));
        ImGui.sameLine(0, spacing);
        var value = addingValue.get().strip();
        var reads = AreaEdits.isValidValue(adding, value);
        ImGui.beginDisabled(!reads);
        var add = HudWidgets.button("area-add-setting-go", null, "Add", 64, ImGui.getFrameHeight(), true, true);
        ImGui.endDisabled();
        if (!value.isEmpty() && !reads) {
            GeodataWidgets.note(
                    "The file cannot read this value: no spaces or tabs outside braces.", GeodataWidgets.WARNING);
        }
        if ((add || entered) && reads) {
            var key = adding;
            host.apply("Add " + key, s -> AreaEdits.setSetting(s, area.index(), key, value));
            addingKey = null;
        }
    }

    private void drawCustomKey(ServerArea area) {
        GeodataWidgets.space(ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var room = ImGui.getContentRegionAvailX() - 64 - 2 * spacing;
        if (focusCustomKey) {
            ImGui.setKeyboardFocusHere();
            focusCustomKey = false;
        }
        ImGui.setNextItemWidth(room * .45f);
        ImGui.inputTextWithHint("##area-add-key", "key, such as my_key", customKey);
        var keyEntered = hasEnterEndedEdit();
        GeodataWidgets.tip("Use letters A to Z, digits and _. Start with a letter.");
        ImGui.sameLine(0, spacing);
        ImGui.setNextItemWidth(room * .55f);
        ImGui.inputTextWithHint("##area-add-value", "value", addingValue);
        var entered = hasEnterEndedEdit();
        GeodataWidgets.tip("The value, as the file writes it. It goes into the file as you type it.");
        ImGui.sameLine(0, spacing);
        var key = customKey.get().strip();
        var value = addingValue.get().strip();
        var refusal = refuseKey(area, key);
        var reads = !key.isEmpty() && refusal == null && AreaEdits.isValidValue(key, value);
        ImGui.beginDisabled(!reads);
        var add = HudWidgets.button("area-add-setting-go", null, "Add", 64, ImGui.getFrameHeight(), true, true);
        ImGui.endDisabled();
        if (refusal != null) {
            GeodataWidgets.note(refusal, GeodataWidgets.WARNING);
        } else if (!key.isEmpty() && !value.isEmpty() && !reads) {
            GeodataWidgets.note(
                    "The file cannot read this value: no spaces or tabs outside braces.", GeodataWidgets.WARNING);
        }
        if ((add || entered || keyEntered) && reads) {
            host.apply("Add " + key, s -> AreaEdits.setSetting(s, area.index(), key, value));
            addingCustom = false;
        }
    }

    private void setRaw(ServerArea area, String key, String value) {
        if (value.equals(area.get(key))) {
            return;
        }
        if (value.isEmpty()) {
            host.apply("Remove " + key, s -> AreaEdits.removeSetting(s, area.index(), key));
            return;
        }
        if (!AreaEdits.isValidValue(key, value)) {
            host.setMessage("The file cannot read " + key + "=" + value
                    + ": a value has no spaces or tabs outside braces. The old value stays.");
            return;
        }
        host.edit(
                "area/" + area.index() + "/" + key,
                "Change " + key,
                s -> AreaEdits.setSetting(s, area.index(), key, value));
    }

    private void drawHeights(ServerArea area) {
        var water = area.isWater();
        var label = water
                ? "Height range · surface " + format(area.top()) + ", bottom " + format(area.bottom())
                : "Height range · " + format(area.bottom()) + " to " + format(area.top());
        if (HudWidgets.disclosure("area-heights", label, heightsOpen)) {
            heightsOpen = !heightsOpen;
        }
        GeodataWidgets.tip("The bottom and the top hold for the whole area: a character between them is inside."
                + " Drag the round handles of the height box in the world while editing to change them.");
        if (!heightsOpen) {
            return;
        }
        GeodataWidgets.space(ROW);
        var bottom = new ImInt(area.bottom());
        var top = new ImInt(area.top());
        var half = GeodataWidgets.columnWidth(2);
        var changed = GeodataWidgets.columns(
                () -> GeodataWidgets.compactInt("Bottom", "##area-bottom", bottom, half, 50),
                () -> GeodataWidgets.compactInt(water ? "Surface" : "Top", "##area-top", top, half, 50));
        var key = "area/" + area.index() + "/heights";
        if (changed[0]) {
            var value = Math.min(bottom.get(), area.top() - 1);
            host.edit(key, "Change bottom", s -> AreaEdits.setHeights(s, area.index(), value, null));
        }
        if (changed[1]) {
            var value = Math.max(top.get(), area.bottom() + 1);
            host.edit(
                    key,
                    water ? "Change surface" : "Change top",
                    s -> AreaEdits.setHeights(s, area.index(), null, value));
        }
        if (water) {
            GeodataWidgets.space(ROW);
            GeodataWidgets.note(
                    "The file writes each height 7 higher: the server puts water 7 below its box.",
                    GeodataWidgets.FAINT);
            GeodataWidgets.space(ROW);
            return;
        }
        var range = host.findGroundRange(area);
        GeodataWidgets.space(ROW);
        ImGui.beginDisabled(range == null);
        if (GeodataWidgets.actionButton("area-fit", null, "Fit to the ground", -1, false) && range != null) {
            var low = range[0] - 200;
            var high = range[1] + 400;
            host.apply("Fit area heights", s -> AreaEdits.setHeights(s, area.index(), low, high));
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                range == null
                        ? "The ground under the outline is not known yet: it needs the geodata or the client's floors."
                        : "From 200 below the lowest ground on the outline to 400 above the highest, as for spawn"
                                + " zones.");
        GeodataWidgets.space(ROW);
    }

    private void drawCorners(ServerArea area) {
        var points = area.points();
        if (HudWidgets.disclosure("area-corners", "Corners · " + points.size(), cornersOpen)) {
            cornersOpen = !cornersOpen;
        }
        GeodataWidgets.tip("Each corner's X and Y in world units, for exact edges. Dragging a corner in the world does"
                + " the same.");
        if (!cornersOpen) {
            return;
        }
        var numberWidth = ImGui.calcTextSizeX("00") + 6;
        var spacing = ImGui.getStyle().getItemSpacingX();
        var field = (ImGui.getContentRegionAvailX() - numberWidth - spacing) / 2 - 2;
        for (var i = 0; i < points.size(); i++) {
            var point = points.get(i);
            var corner = i;
            GeodataWidgets.space(i == 0 ? ROW : 2);
            ImGui.pushID("corner" + i);
            ImGui.alignTextToFramePadding();
            ImGui.textColored(GeodataWidgets.FAINT, Integer.toString(i + 1));
            ImGui.sameLine(numberWidth);
            var x = new ImString(Integer.toString(point.x()), 16);
            var y = new ImString(Integer.toString(point.y()), 16);
            var changedX = GeodataWidgets.axisField("X", "##x", x, field);
            ImGui.sameLine(0, 4);
            var changedY = GeodataWidgets.axisField("Y", "##y", y, field);
            if (changedX || changedY) {
                var typedX = parseInt(x.get());
                var typedY = parseInt(y.get());
                if (typedX != null && typedY != null) {
                    host.apply("Move area corner", s -> moveCorner(s, area.index(), -1, corner, typedX, typedY));
                }
            }
            ImGui.popID();
        }
        GeodataWidgets.space(ROW);
    }

    private void drawParts(ServerArea area) {
        var parts = area.banned();
        if (HudWidgets.disclosure("area-parts", "Excluded parts · " + parts.size(), partsOpen)) {
            partsOpen = !partsOpen;
        }
        GeodataWidgets.tip("banned_range: parts inside the area where it does not hold, " + AreaChecks.MOST_BANNED
                + " at most. While editing they show in red in the world, with handles.");
        if (!partsOpen) {
            return;
        }
        GeodataWidgets.space(ROW);
        if (parts.isEmpty()) {
            GeodataWidgets.note("None: the area holds everywhere inside its outline.", GeodataWidgets.FAINT);
        }
        int remove = -1;
        for (var i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            ImGui.pushID("part" + i);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var width = ImGui.getContentRegionAvailX();
            var draw = ImGui.getWindowDrawList();
            var textTop = y + (HudWidgets.CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1;
            draw.addCircleFilled(x + 4, HudWidgets.textMiddle(textTop), 4, GeodataWidgets.BLOCKED, 12);
            var text = "Part " + (i + 1) + " · " + part.size() + " corners · "
                    + SpawnPanelWidgets.compact(Math.round(Polygons.area(part))) + " units²";
            draw.addText(
                    x + 14,
                    textTop,
                    HudWidgets.TEXT,
                    HudWidgets.truncateToWidth(text, width - 14 - HudWidgets.CONTROL_HEIGHT - ROW));
            ImGui.setCursorScreenPos(x + width - HudWidgets.CONTROL_HEIGHT, y);
            if (HudWidgets.iconButton("remove", Glyph.TRASH, false, GeodataWidgets.BLOCKED)) {
                remove = i;
            }
            GeodataWidgets.tip("Remove this excluded part.");
            ImGui.setCursorScreenPos(x, y + HudWidgets.CONTROL_HEIGHT);
            ImGui.dummy(width, 0);
            if (reachesOutside(area, part)) {
                GeodataWidgets.note(
                        "Reaches outside the area, so the server drops the whole area. Move it inside.",
                        GeodataWidgets.BLOCKED);
            }
            ImGui.popID();
            GeodataWidgets.space(2);
        }
        if (remove >= 0) {
            var next = new ArrayList<>(parts);
            next.remove(remove);
            host.apply("Remove excluded part", s -> AreaEdits.setBanned(s, area.index(), next));
        }
        GeodataWidgets.space(ROW);
        var full = parts.size() >= AreaChecks.MOST_BANNED;
        var drawing = host.isDrawingPart(area.index());
        ImGui.beginDisabled(full && !drawing);
        if (GeodataWidgets.rowButton("Add part", 0, 2) && !full) {
            addSquarePart(area);
        }
        GeodataWidgets.tip(
                full
                        ? "The server takes " + AreaChecks.MOST_BANNED + " excluded parts at most."
                        : "Adds a small square in the middle of the area. Drag its corners in the world while"
                                + " editing.");
        if (GeodataWidgets.rowButton(drawing ? "Stop drawing" : "Draw part", 1, 2)) {
            host.toggleDrawingPart(area.index());
        }
        GeodataWidgets.tip("Click its corners on the ground. Enter closes it, Backspace takes the last corner back,"
                + " Esc stops.");
        ImGui.endDisabled();
        GeodataWidgets.space(ROW);
    }

    private void addSquarePart(ServerArea area) {
        var half = (int) Math.min(PART_HALF, Math.min(area.maxX() - area.minX(), area.maxY() - area.minY()) / 4.0);
        var square = SpawnEdits.square(
                (int) Math.round(area.centerX()),
                (int) Math.round(area.centerY()),
                Math.max(16, half),
                area.bottom(),
                area.top());
        var next = new ArrayList<>(area.banned());
        next.add(square);
        host.apply("Add excluded part", s -> AreaEdits.setBanned(s, area.index(), next));
        partsOpen = true;
    }

    private boolean intField(ServerArea area, String key, String label, int fallback, int min, int max, float width) {
        var value = area.get(key);
        var number = value == null ? fallback : area.getInt(key, Integer.MIN_VALUE);
        if (value != null && number == Integer.MIN_VALUE) {
            return textField(area, key, label, "", width);
        }
        ImGui.beginGroup();
        var reset = drawFieldLabel(key, label, value != null, true, width);
        var field = new ImInt(number);
        ImGui.setNextItemWidth(width);
        if (ImGui.inputInt("##" + key, field, 0, 0)) {
            var next = Integer.toString(Math.clamp(field.get(), min, max));
            host.edit(
                    "area/" + area.index() + "/" + key,
                    "Change " + label.toLowerCase(Locale.ROOT),
                    s -> AreaEdits.setSetting(s, area.index(), key, next));
        }
        ImGui.endGroup();
        GeodataWidgets.tip(tipOf(key, value == null ? "Not set: the server uses " + fallback + "." : ""));
        if (reset) {
            host.apply("Remove " + key, s -> AreaEdits.removeSetting(s, area.index(), key));
        }
        return false;
    }

    private boolean textField(ServerArea area, String key, String label, String hint, float width) {
        var value = area.get(key);
        ImGui.beginGroup();
        var room = width < 0 ? ImGui.getContentRegionAvailX() : width;
        var reset = drawFieldLabel(key, label, value != null, false, room);
        var text = new ImString(value == null ? "" : value, 512);
        ImGui.setNextItemWidth(room);
        ImGui.inputTextWithHint("##" + key, hint, text);
        var left = ImGui.isItemDeactivatedAfterEdit();
        ImGui.endGroup();
        GeodataWidgets.tip(tipOf(key, ""));
        if (left) {
            setRaw(area, key, text.get().strip());
        }
        if (reset) {
            host.apply("Remove " + key, s -> AreaEdits.removeSetting(s, area.index(), key));
        }
        return false;
    }

    private boolean timeField(ServerArea area, String key, String label, long fallback, float width) {
        var value = area.get(key);
        ImGui.beginGroup();
        var reset = drawFieldLabel(key, label, value != null, fallback >= 0, width);
        var text = new ImString(value == null ? "" : value, 64);
        ImGui.setNextItemWidth(width);
        ImGui.inputTextWithHint("##" + key, fallback < 0 ? "not set" : AreaTimes.describe(fallback), text);
        var left = ImGui.isItemDeactivatedAfterEdit();
        var seconds = value == null ? fallback : AreaTimes.parse(value);
        if (value != null && seconds < 0) {
            textIn(GeodataWidgets.WARNING, "Does not read as a time");
        } else if (seconds >= 0) {
            textIn(GeodataWidgets.FAINT, AreaTimes.describe(seconds) + (value == null ? ", the default" : ""));
        }
        ImGui.endGroup();
        GeodataWidgets.tip(tipOf(key, "A number of seconds, or a number with sec, min, hour or day, such as 10min."));
        if (left) {
            var typed = text.get().strip();
            if (typed.isEmpty()) {
                host.apply("Remove " + key, s -> AreaEdits.removeSetting(s, area.index(), key));
            } else {
                var written = AreaTimes.normalize(typed);
                if (written == null) {
                    host.setMessage("A time is a number of seconds, or a number with sec, min, hour or day, such as"
                            + " 10min or 1hour 30min.");
                } else if (!written.equals(value)) {
                    host.edit(
                            "area/" + area.index() + "/" + key,
                            "Change " + label.toLowerCase(Locale.ROOT),
                            s -> AreaEdits.setSetting(s, area.index(), key, written));
                }
            }
        }
        if (reset) {
            host.apply("Remove " + key, s -> AreaEdits.removeSetting(s, area.index(), key));
        }
        return false;
    }

    private static boolean hasEnterEndedEdit() {
        return ImGui.isItemDeactivated()
                && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter));
    }

    private static boolean typeItem(String word, ServerArea area, boolean faint) {
        var type = AreaType.find(word);
        var chosen = GeodataWidgets.listItem(
                word,
                type == null ? word : AreaStyle.title(type),
                type == null ? UNKNOWN_PURPOSE : AreaSummary.describePurpose(type),
                word.equals(area.typeWord()),
                faint);
        if (type == null) {
            GeodataWidgets.tip("Areas of this file have type " + word + ". This tool does not know what it does.");
        }
        return chosen;
    }

    private static boolean matches(String word, String filter) {
        if (filter.isEmpty() || word.toLowerCase(Locale.ROOT).contains(filter)) {
            return true;
        }
        var type = AreaType.find(word);
        return type != null && AreaStyle.title(type).toLowerCase(Locale.ROOT).contains(filter);
    }

    private static @Nullable String refuseKey(ServerArea area, String key) {
        var refusal = refuseWord(key, "key");
        if (refusal != null) {
            return refusal;
        }
        if (SHAPE_KEYS.contains(key)) {
            return key + " has a field of its own above.";
        }
        if (area.hasSetting(key)) {
            return "The area has " + key + " already.";
        }
        return null;
    }

    private static boolean typeRow(String id, AreaType type, boolean faint) {
        return HudWidgets.menuDotRow(
                id + "/" + type.getKeyword(),
                AreaStyle.of(type).getImguiColor(1f),
                AreaStyle.title(type),
                AreaSummary.describePurpose(type),
                faint);
    }

    private static boolean wordRow(String id, String word) {
        var type = AreaType.find(word);
        if (type != null) {
            return typeRow(id, type, false);
        }
        var chosen = HudWidgets.menuDotRow(id + "/" + word, AreaStyle.OTHER.getImguiColor(1f), word, UNKNOWN_PURPOSE);
        GeodataWidgets.tip("Areas of this file have type " + word + ". This tool does not know what it does, so a new"
                + " area of it gets only its name and its shape.");
        return chosen;
    }

    private static boolean isFirstRare(@Nullable AreaType type) {
        var types = orderTypes();
        return type != null && types.indexOf(type) == COMMON_TYPES.size();
    }

    private static State withOutline(State state, ServerArea area, int part, List<TerritoryPoint> points) {
        if (part < 0) {
            return AreaEdits.setPoints(state, area.index(), points);
        }
        var parts = new ArrayList<>(area.banned());
        parts.set(part, points);
        return AreaEdits.setBanned(state, area.index(), parts);
    }

    private static boolean reachesOutside(ServerArea area, List<TerritoryPoint> part) {
        for (var point : part) {
            if (!area.containsXy(point.x(), point.y())) {
                return true;
            }
        }
        return false;
    }

    private static boolean drawFieldLabel(String key, String label, boolean set, boolean hasDefault, float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var size = ImGui.getFontSize();
        if (!set) {
            var full = hasDefault ? label + " · default" : label;
            textIn(
                    GeodataWidgets.FAINT,
                    ImGui.calcTextSizeX(full) <= width ? full : HudWidgets.truncateToWidth(label, width));
            return false;
        }
        textIn(GeodataWidgets.MUTED, HudWidgets.truncateToWidth(label, width - size - 4));
        var after = ImGui.getCursorScreenPosY();
        ImGui.setCursorScreenPos(x + width - size, y);
        var clicked = ImGui.invisibleButton("##reset-" + key, size, size);
        var hovered = ImGui.isItemHovered();
        HudWidgets.glyph(
                ImGui.getWindowDrawList(),
                Glyph.CLOSE,
                x + width - size,
                y,
                size,
                hovered ? HudWidgets.TEXT : GeodataWidgets.FAINT);
        if (hovered) {
            HudWidgets.showTooltip("Remove " + key + ": the server's default holds again.");
        }
        ImGui.setCursorScreenPos(x, after);
        return clicked;
    }

    private static String tipOf(String key, String note) {
        var meaning = AreaKeys.meaning(key);
        return key + (meaning.isEmpty() ? "" : ": " + meaning) + (note.isEmpty() ? "" : " " + note);
    }

    private static String targetLabel(String target) {
        return switch (target) {
            case "all" -> "Everyone";
            case "pc" -> "Players, summons";
            case "npc" -> "NPCs";
            case "only_pc" -> "Players only";
            default -> target;
        };
    }

    private static void toggle(List<String> items, String item) {
        if (!items.remove(item)) {
            items.add(item);
        }
    }

    private static @Nullable Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }

    private static String format(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static void textIn(int color, String value) {
        ImGui.pushStyleColor(ImGuiCol.Text, color);
        ImGui.textUnformatted(value);
        ImGui.popStyleColor();
    }
}

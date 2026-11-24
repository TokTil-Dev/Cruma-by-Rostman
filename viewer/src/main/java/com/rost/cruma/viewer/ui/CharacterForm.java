package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.export.NpcExport;
import com.rost.cruma.core.export.NpcExport.Character;
import com.rost.cruma.core.export.NpcExport.Race;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.spawn.PlayerEquipment;
import com.rost.cruma.core.spawn.PlayerEquipment.Choice;
import com.rost.cruma.core.spawn.PlayerEquipment.Slot;
import com.rost.cruma.core.spawn.PlayerEquipment.Tables;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.UnrealLoader.HairCover;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.spawn.RaceOffers;
import com.rost.cruma.viewer.spawn.SpawnModels;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class CharacterForm {
    private static final float LABEL_WIDTH = 96;

    private static final float SEGMENT_PADDING = 12;

    private static final float SLOT_MIN_WIDTH = 100;

    record Data(List<Race> races, Tables tables, @Nullable ClientCatalog catalog, String problem) {}

    record Limits(boolean outfit, boolean hair, Predicate<Race> races) {
        static final Limits NONE = new Limits(true, true, _ -> true);
    }

    record Look(String face, String figure, int hair, Map<Slot, Integer> items) {}

    private final String id;
    private @Nullable CompletableFuture<Data> loading;
    private @Nullable Data data;
    private int race;
    private int hairStyle;
    private final EnumMap<Slot, Integer> chosen = new EnumMap<>(Slot.class);
    private Map<Slot, List<Choice>> choices = Map.of();
    private String choicesFigure = "";
    private final ImString itemSearch = new ImString(128);
    private final ImString raceSearch = new ImString(32);
    private Limits limits = Limits.NONE;
    private float rowWidth;
    private @Nullable Path root;
    private WorldVariant world = WorldVariant.MAIN;
    private String missing = "";
    private final Map<String, float[]> placed = new HashMap<>();

    CharacterForm(String id) {
        this.id = id;
    }

    static String summary(String race, List<String> items) {
        return items.isEmpty() ? race : race + " · " + String.join(" · ", items);
    }

    static int slotColumns(float width) {
        return width >= 2 * SLOT_MIN_WIDTH + GeodataWidgets.ROW ? 2 : 1;
    }

    void setLimit(Limits next) {
        limits = next;
    }

    void reset() {
        if (loading != null) {
            loading.cancel(false);
        }
        loading = null;
        data = null;
        choices = Map.of();
        choicesFigure = "";
        chosen.clear();
    }

    void read(SpawnModels source, Path root, WorldVariant variant, Executor worker) {
        this.root = root;
        this.world = variant;
        if (data == null && loading == null) {
            loading = CompletableFuture.supplyAsync(
                    () -> {
                        var available = NpcExport.RACES.stream()
                                .filter(candidate -> limits.races().test(candidate) && source.offers(candidate))
                                .toList();
                        var tables = source.getTables();
                        var problem = tables.armors().size() == 0
                                ? "This client's item tables cannot be read, so the character stands in the race's"
                                        + " bare body."
                                : "";
                        return new Data(available, tables, ClientCatalog.read(root, variant), problem);
                    },
                    worker);
        }
        if (loading != null && loading.isDone()) {
            try {
                data = loading.join();
            } catch (CompletionException failure) {
                var cause = failure.getCause() != null ? failure.getCause() : failure;
                data = new Data(
                        List.of(),
                        Tables.EMPTY,
                        null,
                        "The client's player races cannot be read: "
                                + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
            }
            loading = null;
        }
    }

    @Nullable
    Data getData() {
        return data;
    }

    void draw(boolean busy) {
        draw(busy, ImGui.getContentRegionAvailX());
    }

    void draw(boolean busy, float width) {
        draw(busy, width, true);
    }

    void drawUnderHeader(boolean busy, float width) {
        draw(busy, width, false);
    }

    @Nullable
    Character getCharacter() {
        if (data == null || data.races().isEmpty()) {
            return null;
        }
        var current = data.races().get(Math.clamp(race, 0, data.races().size() - 1));
        var outfit = data.problem().isEmpty()
                ? PlayerEquipment.buildOutfit(
                        PlayerEquipment.toParts(chosen, hairStyle), current.figure(), data.tables())
                : new Outfit(List.of(), Set.of(), hairStyle, HairCover.NONE, List.of(), null, null, current.figure());
        return new Character(current.label(), current.face(), outfit);
    }

    @Nullable
    String getFigure() {
        if (data == null || data.races().isEmpty()) {
            return null;
        }
        return data.races().get(Math.clamp(race, 0, data.races().size() - 1)).figure();
    }

    String getKey() {
        var current = getCharacter();
        return current == null
                ? ""
                : "character:" + current.face() + "|" + getFigure() + "|" + hairStyle + "|" + chosen;
    }

    @Nullable
    Look getLook() {
        var current = getCharacter();
        var figure = getFigure();
        return current == null || figure == null
                ? null
                : new Look(current.face(), figure, hairStyle, Map.copyOf(chosen));
    }

    void show(Look look) {
        if (data != null) {
            var found = false;
            for (var index = 0; index < data.races().size() && !found; index++) {
                var candidate = data.races().get(index);
                if (look.figure().isEmpty()
                        ? candidate.face().equals(look.face())
                        : candidate.figure().equalsIgnoreCase(look.figure())) {
                    race = index;
                    found = true;
                }
            }
            missing = found ? "" : describeMissing(look);
        }
        hairStyle = look.hair();
        chosen.clear();
        chosen.putAll(look.items());
    }

    List<String> getItemNames() {
        var names = new ArrayList<String>();
        for (var slot : Slot.values()) {
            var item = chosen.getOrDefault(slot, 0);
            for (var choice : choices.getOrDefault(slot, List.of())) {
                if (choice.id() == item) {
                    names.add(choice.name());
                    break;
                }
            }
        }
        return names;
    }

    String getSummary() {
        if (data == null) {
            return "Reading…";
        }
        if (data.races().isEmpty()) {
            return "No player race";
        }
        var current = data.races().get(Math.clamp(race, 0, data.races().size() - 1));
        return summary(current.label(), getItemNames());
    }

    int getHairStyle() {
        return hairStyle;
    }

    private void draw(boolean busy, float width, boolean titled) {
        rowWidth = width;
        if (data == null) {
            if (titled) {
                GeodataWidgets.space(GeodataWidgets.BLOCK);
            }
            note("Reading the client's player races and items…", GeodataWidgets.MUTED);
            return;
        }
        if (data.races().isEmpty()) {
            if (titled) {
                GeodataWidgets.space(GeodataWidgets.BLOCK);
            }
            note(
                    "This client has no player race Cruma can build: none of the races' face meshes, such as"
                            + " Fighter.MFighter_m000_f, can be read.",
                    GeodataWidgets.WARNING);
            return;
        }
        var races = data.races();
        race = Math.clamp(race, 0, races.size() - 1);
        var current = races.get(race);
        ImGui.beginDisabled(busy);
        if (titled) {
            GeodataWidgets.section("CHARACTER");
        }
        var peoples = races.stream().map(CharacterForm::peopleOf).distinct().toList();
        var picked =
                choose("people", peoples.toArray(String[]::new), peoples.indexOf(peopleOf(current)), formRow("Race"));
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (picked >= 0) {
            race = findClosestRace(races, peoples.get(picked), kind(current), isFemale(current));
            missing = "";
        }
        current = races.get(race);
        var people = peopleOf(current);
        var kinds = races.stream()
                .filter(candidate -> peopleOf(candidate).equals(people))
                .map(CharacterForm::kind)
                .distinct()
                .toList();
        if (kinds.size() > 1) {
            var labels = kinds.stream()
                    .map(kind -> kind.isEmpty() ? "Other classes" : kind)
                    .toArray(String[]::new);
            picked = choose("kind", labels, kinds.indexOf(kind(current)), formRow("Class"));
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (picked >= 0) {
                race = findClosestRace(races, people, kinds.get(picked), isFemale(current));
                missing = "";
            }
            current = races.get(race);
        }
        var kind = kind(current);
        var genders = races.stream()
                .filter(candidate ->
                        peopleOf(candidate).equals(people) && kind(candidate).equals(kind))
                .map(candidate -> isFemale(candidate) ? "Female" : "Male")
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
        picked = GeodataWidgets.segmented(
                id + "-gender",
                genders.toArray(String[]::new),
                genders.indexOf(isFemale(current) ? "Female" : "Male"),
                formRow("Gender"));
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (picked >= 0) {
            race = findClosestRace(races, people, kind, genders.get(picked).equals("Female"));
            missing = "";
        }
        current = races.get(race);
        if (limits.hair()) {
            var pickedHair = GeodataWidgets.segmented(
                    id + "-hair", new String[] {"1", "2", "3", "4", "5"}, hairStyle, formRow("Hair"));
            if (pickedHair >= 0) {
                hairStyle = pickedHair;
            }
            GeodataWidgets.tip("The race's hair styles. A style the race does not have shows the first one.");
        }
        if (!missing.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            note(missing, GeodataWidgets.WARNING);
        }
        if (!data.problem().isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            note(data.problem(), GeodataWidgets.WARNING);
        } else if (limits.outfit()) {
            refreshChoices(current);
            GeodataWidgets.section("OUTFIT");
            GeodataWidgets.tip(
                    "Armour that fits this body, from the client's own tables. The rig moves every piece with the"
                            + " body, and weapons with the hands.");
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawSlots();
            var set = wholeSet();
            if (!set.isEmpty()) {
                if (HudWidgets.button(
                        id + "-set",
                        Glyph.SPARKLES,
                        "Wear the whole " + setName() + " set",
                        rowWidth,
                        28,
                        false,
                        true)) {
                    chosen.putAll(set);
                }
                GeodataWidgets.tip("Puts on the " + setName() + " pieces of the other slots that this body can wear.");
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
            ImGui.beginDisabled(chosen.isEmpty());
            if (HudWidgets.button(id + "-undress", null, "Take everything off", rowWidth, 28, false, true)) {
                chosen.clear();
            }
            ImGui.endDisabled();
        }
        ImGui.endDisabled();
    }

    private float formRow(String label) {
        var left = ImGui.getCursorPosX();
        var top = ImGui.getCursorPosY();
        ImGui.setCursorPosY(top + (ImGui.getFrameHeight() - ImGui.getFontSize()) * .5f);
        ImGui.textColored(GeodataWidgets.MUTED, label);
        ImGui.setCursorPos(left + LABEL_WIDTH, top);
        return rowWidth - LABEL_WIDTH;
    }

    private int choose(String key, String[] labels, int selected, float width) {
        placed.put(key, new float[] {ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY(), width});
        var needed = 0f;
        for (var label : labels) {
            needed += ImGui.calcTextSizeX(label) + SEGMENT_PADDING;
        }
        if (needed <= width) {
            return GeodataWidgets.segmented(id + "-" + key, labels, selected, width);
        }
        var preview = selected >= 0 && selected < labels.length ? labels[selected] : "";
        var typed = GeodataWidgets.searchList("##" + id + "-" + key, preview, raceSearch, "Find", width);
        var clicked = -1;
        if (typed != null) {
            for (var index = 0; index < labels.length; index++) {
                if (labels[index].toLowerCase(Locale.ROOT).contains(typed)
                        && GeodataWidgets.listItem(key + "/" + index, labels[index], "", index == selected)) {
                    clicked = index;
                }
            }
            ImGui.endCombo();
        }
        return clicked;
    }

    private void note(String text, int color) {
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + rowWidth);
        ImGui.textColored(color, text);
        ImGui.popTextWrapPos();
    }

    private void drawSlots() {
        var columns = slotColumns(rowWidth);
        var width = (rowWidth - (columns - 1) * GeodataWidgets.ROW) / columns;
        var left = ImGui.getCursorPosX();
        var slots = Slot.values();
        for (var index = 0; index < slots.length; index++) {
            if (index % columns > 0) {
                ImGui.sameLine();
                ImGui.setCursorPosX(left + index % columns * (width + GeodataWidgets.ROW));
            }
            drawSlot(slots[index], width);
            if (index % columns == columns - 1 || index == slots.length - 1) {
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
        }
    }

    private void drawSlot(Slot slot, float width) {
        var items = choices.getOrDefault(slot, List.of());
        var item = chosen.getOrDefault(slot, 0);
        var name = "";
        for (var choice : items) {
            if (choice.id() == item) {
                name = choice.name();
                break;
            }
        }
        var typed = name.isEmpty()
                ? GeodataWidgets.searchList(
                        "##" + id + "-slot-" + slot,
                        slot.getLabel(),
                        GeodataWidgets.MUTED,
                        itemSearch,
                        "Find an item",
                        width)
                : GeodataWidgets.searchList("##" + id + "-slot-" + slot, name, itemSearch, "Find an item", width);
        GeodataWidgets.tip(name.isEmpty() ? slot.getLabel() + ": nothing worn." : slot.getLabel() + ": " + name);
        if (typed != null) {
            if (typed.isEmpty() && GeodataWidgets.listItem("none", "None", "", item == 0)) {
                chosen.remove(slot);
            }
            var shownItems = 0;
            for (var choice : items) {
                if (!choice.name().toLowerCase(Locale.ROOT).contains(typed)
                        && !Integer.toString(choice.id()).equals(typed)) {
                    continue;
                }
                if (++shownItems > 300) {
                    break;
                }
                if (GeodataWidgets.listItem(
                        slot + "/" + choice.id(), choice.name(), Integer.toString(choice.id()), choice.id() == item)) {
                    chosen.put(slot, choice.id());
                }
            }
            ImGui.endCombo();
        }
    }

    private String setName() {
        var item = chosen.getOrDefault(Slot.CHEST, 0);
        for (var choice : choices.getOrDefault(Slot.CHEST, List.of())) {
            if (choice.id() == item) {
                var space = choice.name().lastIndexOf(' ');
                return space > 0 ? choice.name().substring(0, space) : "";
            }
        }
        return "";
    }

    private Map<Slot, Integer> wholeSet() {
        var name = setName().toLowerCase(Locale.ROOT);
        var set = new EnumMap<Slot, Integer>(Slot.class);
        if (name.isEmpty()) {
            return set;
        }
        for (var slot : List.of(Slot.LEGS, Slot.GLOVES, Slot.BOOTS)) {
            for (var choice : choices.getOrDefault(slot, List.of())) {
                if (choice.name().toLowerCase(Locale.ROOT).startsWith(name + " ")) {
                    if (chosen.getOrDefault(slot, 0) != choice.id()) {
                        set.put(slot, choice.id());
                    }
                    break;
                }
            }
        }
        return set;
    }

    private void refreshChoices(Race current) {
        if (current.figure().equals(choicesFigure) || data == null) {
            return;
        }
        choicesFigure = current.figure();
        var catalog = data.catalog();
        choices = PlayerEquipment.findChoices(data.tables(), current.figure(), itemId -> {
            var item = catalog == null ? null : catalog.getItem(itemId);
            return item == null ? null : item.name();
        });
        chosen.entrySet()
                .removeIf(entry -> choices.getOrDefault(entry.getKey(), List.of()).stream()
                        .noneMatch(choice -> choice.id() == entry.getValue()));
    }

    private String describeMissing(Look look) {
        for (var known : NpcExport.RACES) {
            if (look.figure().isEmpty()
                    ? known.face().equals(look.face())
                    : known.figure().equalsIgnoreCase(look.figure())) {
                var client = root;
                var shown = data == null || data.races().isEmpty()
                        ? ""
                        : " It shows "
                                + data.races()
                                        .get(Math.clamp(race, 0, data.races().size() - 1))
                                        .label()
                                + ".";
                return (client == null
                                ? "This client has no " + known.label() + "."
                                : RaceOffers.describeMissing(known, world, RaceOffers.findWorlds(known, client, true)))
                        + shown;
            }
        }
        return "This client has no such race.";
    }

    private static String peopleOf(Race race) {
        var made = PlayerFigures.findClassFigure(race.figure());
        if (made != null) {
            return made.people();
        }
        var label = race.label();
        var name = label.substring(0, Math.max(0, label.indexOf(',')));
        return name.replace(" fighter", "").replace(" mystic", "");
    }

    private static String kind(Race race) {
        var made = PlayerFigures.findClassFigure(race.figure());
        if (made != null) {
            return made.name();
        }
        return race.label().contains(" mystic") ? "Mystic" : race.label().contains(" fighter") ? "Fighter" : "";
    }

    private static boolean isFemale(Race race) {
        var made = PlayerFigures.findClassFigure(race.figure());
        if (made != null) {
            return made.isFemale();
        }
        return race.label().endsWith("female");
    }

    private static int findClosestRace(List<Race> races, String people, String kind, boolean female) {
        var best = -1;
        var bestScore = -1;
        for (var index = 0; index < races.size(); index++) {
            var candidate = races.get(index);
            if (!peopleOf(candidate).equals(people)) {
                continue;
            }
            var score = (kind(candidate).equals(kind) ? 2 : 0) + (isFemale(candidate) == female ? 1 : 0);
            if (score > bestScore) {
                best = index;
                bestScore = score;
            }
        }
        return Math.max(0, best);
    }
}

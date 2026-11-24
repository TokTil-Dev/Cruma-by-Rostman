package com.rost.cruma.core.npc;

import com.rost.cruma.core.schema.GameDataNames;
import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableReport.State;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.TableSpec;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.unreal.L2DataFile;
import com.rost.cruma.core.unreal.L2TextTable;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

public final class ClientCatalog {
    private static final Map<String, String> TEXT_FIELDS = Map.of(
            "object_id",
            "id",
            "skill_id",
            "id",
            "skill_level",
            "level",
            "description",
            "desc",
            "nick",
            "title",
            "hit_time",
            "hit",
            "cast_range",
            "range",
            "cast_style",
            "style",
            "is_magic",
            "kind",
            "animation",
            "anim");
    private static final Set<String> TEXT_NUMBERS = Set.of("id", "level", "range", "style", "kind");

    private final Map<Integer, Item> items;
    private final Map<Integer, TreeMap<Integer, Skill>> skills;
    private final Map<Integer, Npc> npcs;
    private final List<String> notes;
    private final Map<Integer, TreeMap<Integer, Cast>> casts;
    private final Map<String, Combo> combos;
    private final SkillSounds sounds;
    private final Map<Long, String> npcAnimations;

    private ClientCatalog(
            Map<Integer, Item> items,
            Map<Integer, TreeMap<Integer, Skill>> skills,
            Map<Integer, Npc> npcs,
            List<String> notes) {
        this(items, skills, npcs, notes, Map.of(), Map.of(), SkillSounds.EMPTY, Map.of());
    }

    private ClientCatalog(
            Map<Integer, Item> items,
            Map<Integer, TreeMap<Integer, Skill>> skills,
            Map<Integer, Npc> npcs,
            List<String> notes,
            Map<Integer, TreeMap<Integer, Cast>> casts,
            Map<String, Combo> combos,
            SkillSounds sounds,
            Map<Long, String> npcAnimations) {
        this.items = items;
        this.skills = skills;
        this.npcs = npcs;
        this.notes = List.copyOf(notes);
        this.casts = casts;
        this.combos = combos;
        this.sounds = sounds;
        this.npcAnimations = npcAnimations;
    }

    public record Item(int id, String name, String addName, String description, String icon) {}

    public record Skill(int id, int level, String name, String description, String icon) {}

    public record Npc(int id, String name, String title) {}

    public record Cast(float seconds, List<String> animations, int kind, int range, int style, String effect) {
        public static final int PHYSICAL = 0;
        public static final int MAGIC = 1;
        public static final int STATIC = 2;
        public static final int DANCE = 3;

        public static final int BASE_SPEED = 333;

        public Cast {
            animations = List.copyOf(animations);
        }

        public Cast(float seconds, List<String> animations, int kind, int range) {
            this(seconds, animations, kind, range, -1, "");
        }

        public boolean magic() {
            return kind == MAGIC;
        }

        public boolean fixed() {
            return kind == STATIC;
        }

        public boolean onSelf() {
            return range < 0;
        }

        public boolean drains() {
            return style == 4 || style == 20;
        }

        public float speedFactor(int speed) {
            return fixed() || speed <= 0 ? 1f : speed / (float) BASE_SPEED;
        }
    }

    public record Combo(String name, List<String> parts, int loop) {}

    public record SkillSound(String sound, Moment moment, boolean onTarget, float volume, float radius, float delay) {
        public enum Moment {
            CAST,
            SHOT,
            HIT
        }
    }

    public static ClientCatalog empty() {
        return new ClientCatalog(Map.of(), Map.of(), Map.of(), List.of());
    }

    public static ClientCatalog read(Path client) {
        return read(client, WorldVariant.MAIN);
    }

    public static ClientCatalog read(Path client, WorldVariant variant) {
        var system = client.resolve("system");
        var notes = new ArrayList<String>();
        List<String> names = List.of();
        var namesFile = TableFiles.find(system, variant, "L2GameDataName.dat");
        if (namesFile != null) {
            try {
                names = gameDataNames(L2DataFile.read(namesFile));
            } catch (IOException | RuntimeException failure) {
                notes.add("L2GameDataName.dat: " + failure.getMessage());
            }
        }
        var casts = new HashMap<Integer, TreeMap<Integer, Cast>>();
        return new ClientCatalog(
                loadItems(system, variant, names, notes),
                loadSkills(system, variant, names, notes, casts),
                loadNpcs(system, variant, names, notes),
                notes,
                casts,
                loadCombos(system, variant, names, notes),
                SkillSounds.read(system, variant, names, notes),
                loadNpcAnimations(system, variant, names, notes));
    }

    public static Map<Integer, Npc> readNpcNames(Path client, WorldVariant variant) {
        var system = client.resolve("system");
        return Map.copyOf(
                loadNpcs(system, variant, TableSchemas.readGameDataNames(system, variant), new ArrayList<>()));
    }

    static Map<Integer, TreeMap<Integer, Cast>> castsOf(List<Map<String, Object>> records) {
        var casts = new HashMap<Integer, TreeMap<Integer, Cast>>();
        for (var record : records) {
            if (!(record.get("hit") instanceof Number hit)
                    || !(record.get("id") instanceof Integer id)
                    || !(record.get("level") instanceof Integer level)) {
                continue;
            }
            var seconds = hit.floatValue();
            var written = switch (record.get("anim")) {
                case String name -> List.<Object>of(name);
                case List<?> list -> list;
                case null, default -> List.of();
            };
            var animations = new ArrayList<String>();
            for (var name : written) {
                var animation = String.valueOf(name);
                if (!animation.isBlank() && !animation.equalsIgnoreCase("None") && !animations.contains(animation)) {
                    animations.add(animation);
                }
            }
            var kind = record.get("kind") instanceof Integer value ? value : -1;
            var range = record.get("range") instanceof Integer value ? value : 0;
            var style = record.get("style") instanceof Integer value ? value : -1;
            var effect = record.get("effect") == null ? "" : String.valueOf(record.get("effect"));
            casts.computeIfAbsent(id, unused -> new TreeMap<>())
                    .putIfAbsent(level, new Cast(seconds, animations, kind, range, style, effect));
        }
        return casts;
    }

    static String fillDescription(String description, String values) {
        if (values.isBlank() || !description.contains("$s")) {
            return description;
        }
        var parts = values.split(";");
        var out = description;
        for (var i = parts.length; i >= 1; i--) {
            out = out.replace("$s" + i, parts[i - 1].strip());
        }
        return out;
    }

    public static List<String> gameDataNames(byte[] bytes) {
        return GameDataNames.read(bytes);
    }

    static String findLocalizedTable(Path system, WorldVariant variant, String table) {
        for (var folder : TableFiles.getFolders(system)) {
            for (var world : variant.getTableWorlds()) {
                var found = findLocalizedTableIn(folder, table + world.getTableSuffix());
                if (found != null) {
                    return found;
                }
            }
        }
        return table + "-e.dat";
    }

    static void putIcons(Map<Integer, String> icons, List<Map<String, Object>> rows) {
        for (var record : rows) {
            if (record.get("id") instanceof Integer id) {
                icons.putIfAbsent(id, textOrNull(record.get("icon")));
            }
        }
    }

    static void putReferenceIcons(Map<Integer, String> icons, List<Map<String, Object>> rows) {
        for (var record : rows) {
            var icon = toReference(record.get("icon"));
            if (record.get("id") instanceof Integer id && !icon.isEmpty() && !icon.equalsIgnoreCase("None")) {
                icons.putIfAbsent(id, icon);
            }
        }
    }

    static void putItems(Map<Integer, Item> items, List<Map<String, Object>> rows, Map<Integer, String> icons) {
        for (var record : rows) {
            if (!(record.get("id") instanceof Integer id)) {
                continue;
            }
            items.putIfAbsent(
                    id,
                    new Item(
                            id,
                            textOrNull(record.get("name")),
                            record.containsKey("add") ? textOrNull(record.get("add")) : "",
                            cleanDescription(textOrNull(record.get("desc"))),
                            cleanIconName(icons.get(id))));
        }
    }

    static void putSkillIcons(Map<Long, String> icons, List<Map<String, Object>> rows) {
        for (var record : rows) {
            if (record.get("id") instanceof Integer id && record.get("level") instanceof Integer level) {
                icons.putIfAbsent(skillKey(id, level), textOrNull(record.get("icon")));
            }
            if (record.get("anims") instanceof List<?> animations) {
                record.put("anim", animations);
            }
        }
    }

    static void putSkillTexts(Map<Long, String[]> texts, List<Map<String, Object>> rows, boolean textTable) {
        for (var record : rows) {
            if (!(record.get("id") instanceof Integer id) || !(record.get("level") instanceof Integer level)) {
                continue;
            }
            var values = !textTable && record.get("values") instanceof String text ? text : "";
            var name = record.get("name") instanceof String text ? text : "";
            texts.putIfAbsent(skillKey(id, level), new String[] {name, textOrNull(record.get("desc")), values});
        }
    }

    static Map<Long, String> npcAnimationsOf(List<Map<String, Object>> rows) {
        var animations = new HashMap<Long, String>();
        for (var record : rows) {
            if (record.get("anim") instanceof String name
                    && !name.isBlank()
                    && !name.equalsIgnoreCase("None")
                    && record.get("npc") instanceof Integer npc
                    && record.get("skill") instanceof Integer skill) {
                animations.putIfAbsent(skillKey(npc, skill), name.trim());
            }
        }
        return animations;
    }

    static Map<String, Combo> combosOf(List<Map<String, Object>> rows) {
        var combos = new HashMap<String, Combo>();
        for (var record : rows) {
            if (!(record.get("loop") instanceof Integer loop)) {
                continue;
            }
            var parts = new ArrayList<String>();
            for (var key : List.of("first", "second", "third")) {
                if (record.get(key) instanceof String written) {
                    var part = SkillCombos.part(written);
                    if (!part.isEmpty()) {
                        parts.add(part);
                    }
                }
            }
            var name = String.valueOf(record.get("name"));
            combos.putIfAbsent(name.toLowerCase(Locale.ROOT), new Combo(name, List.copyOf(parts), loop));
        }
        return combos;
    }

    static Map<Integer, Npc> npcsOf(List<Map<String, Object>> rows) {
        var npcs = new HashMap<Integer, Npc>();
        for (var record : rows) {
            if (record.get("id") instanceof Integer id) {
                npcs.putIfAbsent(id, new Npc(id, textOrNull(record.get("name")), textOrNull(record.get("title"))));
            }
        }
        return npcs;
    }

    public @Nullable Item getItem(int id) {
        return items.get(id);
    }

    public @Nullable Skill getSkill(int id, int level) {
        var levels = skills.get(id);
        if (levels == null) {
            return null;
        }
        var entry = levels.floorEntry(level);
        return entry != null ? entry.getValue() : levels.firstEntry().getValue();
    }

    public @Nullable Cast getCast(int id, int level) {
        var levels = casts.get(id);
        if (levels == null) {
            return null;
        }
        var entry = levels.floorEntry(level);
        return entry != null ? entry.getValue() : levels.firstEntry().getValue();
    }

    public boolean hasLevel(int id, int level) {
        var levels = casts.containsKey(id) ? casts.get(id) : skills.get(id);
        return levels != null && levels.containsKey(level);
    }

    public List<Skill> getSkillsAtLevelOne() {
        var all = new ArrayList<Skill>(skills.size());
        for (var levels : skills.values()) {
            var first = levels.get(1);
            if (first != null && hasLevel(first.id(), 1)) {
                all.add(first);
            }
        }
        all.sort(
                Comparator.comparing(Skill::name, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Skill::id));
        return all;
    }

    public int getTwinAtLevelOne(int id) {
        var named = getSkill(id, 1);
        if (named == null || named.name().isBlank()) {
            return 0;
        }
        var twin = 0;
        for (var levels : skills.values()) {
            var first = levels.get(1);
            if (first != null
                    && first.id() != id
                    && first.name().equals(named.name())
                    && hasLevel(first.id(), 1)
                    && (twin == 0 || first.id() < twin)) {
                twin = first.id();
            }
        }
        return twin;
    }

    public int getLastCastLevel(int id) {
        var levels = casts.get(id);
        return levels == null ? 0 : levels.lastKey();
    }

    public @Nullable Combo getCombo(String name) {
        return combos.get(name.toLowerCase(Locale.ROOT));
    }

    public @Nullable String getNpcSkillAnimation(int npc, int skill) {
        return npcAnimations.get(skillKey(npc, skill));
    }

    public List<SkillSound> getSkillSounds(int id, int level, @Nullable String figure) {
        return sounds.of(id, level, figure);
    }

    public @Nullable Npc getNpc(int id) {
        return npcs.get(id);
    }

    public Collection<Item> getItems() {
        return Collections.unmodifiableCollection(items.values());
    }

    public List<Skill> getSkills() {
        var all = new ArrayList<Skill>(skills.size());
        for (var levels : skills.values()) {
            all.add(levels.firstEntry().getValue());
        }
        return all;
    }

    public int getLevels(int id) {
        var levels = skills.get(id);
        return levels == null ? 0 : levels.size();
    }

    public int getItemCount() {
        return items.size();
    }

    public int getSkillCount() {
        return skills.size();
    }

    public int getNpcCount() {
        return npcs.size();
    }

    public Collection<Npc> getNpcs() {
        return Collections.unmodifiableCollection(npcs.values());
    }

    public List<String> getNotes() {
        return notes;
    }

    private static Map<Integer, Item> loadItems(
            Path system, WorldVariant variant, List<String> names, List<String> notes) {
        var icons = new HashMap<Integer, String>();
        var etcItems = readItemTable(system, variant, Tables.ETC_ITEMS, "EtcItemgrp.dat", names, notes);
        putIcons(icons, etcItems != null ? etcItems : readTextTable(system, variant, "EtcItemgrp.txt", notes));
        for (var spec : List.of(Tables.WEAPONS, Tables.ARMORS)) {
            var rows = readItemTable(system, variant, spec, spec.fileName(), names, notes);
            if (rows == null) {
                var text = spec.fileName().substring(0, spec.fileName().length() - ".dat".length()) + ".txt";
                putIcons(icons, readTextTable(system, variant, text, notes));
                continue;
            }
            putReferenceIcons(icons, rows);
        }
        var items = new HashMap<Integer, Item>();
        var itemNames = findLocalizedTable(system, variant, "ItemName");
        var named = readItemTable(system, variant, Tables.ITEM_NAMES, itemNames, names, notes);
        if (named == null) {
            named = readTextTable(
                    system, variant, itemNames.substring(0, itemNames.length() - ".dat".length()) + ".txt", notes);
        }
        putItems(items, named, icons);
        icons.forEach((id, icon) -> items.putIfAbsent(id, new Item(id, "", "", "", cleanIconName(icon))));
        return items;
    }

    private static @Nullable List<Map<String, Object>> readItemTable(
            Path system, WorldVariant variant, TableSpec spec, String name, List<String> names, List<String> notes) {
        var file = TableSchemas.findTable(spec, system, variant);
        if (file == null) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = L2DataFile.read(file);
        } catch (IOException | RuntimeException failure) {
            notes.add(name + ": " + failure.getMessage());
            return List.of();
        }
        var table = TableSchemas.read(spec, file, bytes, names, TableSchemas.getClientFolder(system));
        if (table.schema() == null) {
            notes.add(name + " matches no known layout");
        }
        return table.rows();
    }

    private static String toReference(@Nullable Object value) {
        if (!(value instanceof String text)) {
            return "";
        }
        var trimmed = text.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    private static Map<Integer, TreeMap<Integer, Skill>> loadSkills(
            Path system,
            WorldVariant variant,
            List<String> names,
            List<String> notes,
            Map<Integer, TreeMap<Integer, Cast>> casts) {
        var icons = new HashMap<Long, String>();
        var grp = readSchemaTable(system, variant, Tables.SKILLS, "Skillgrp.dat", names, notes);
        putSkillIcons(icons, grp);
        casts.putAll(castsOf(grp));
        var texts = new HashMap<Long, String[]>();
        var skillNames = findLocalizedTable(system, variant, "SkillName");
        var textTable = TableFiles.find(system, variant, skillNames) == null;
        putSkillTexts(texts, readSchemaTable(system, variant, Tables.SKILL_NAMES, skillNames, names, notes), textTable);
        var skills = new HashMap<Integer, TreeMap<Integer, Skill>>();
        var values = new HashMap<Long, String>();
        var keys = new HashSet<Long>(icons.keySet());
        keys.addAll(texts.keySet());
        for (var key : keys) {
            var id = (int) (key >>> 32);
            var level = (int) (key & 0xffffffffL);
            var text = texts.getOrDefault(key, new String[] {"", "", ""});
            values.put(key, text[2]);
            skills.computeIfAbsent(id, unused -> new TreeMap<>())
                    .put(
                            level,
                            new Skill(
                                    id,
                                    level,
                                    text[0],
                                    cleanDescription(text[1]),
                                    cleanIconName(icons.getOrDefault(key, ""))));
        }
        for (var levels : skills.values()) {
            var description = "";
            var name = "";
            var icon = "";
            for (var entry : levels.entrySet()) {
                var skill = entry.getValue();
                description = skill.description().isEmpty() ? description : skill.description();
                name = skill.name().isEmpty() ? name : skill.name();
                icon = skill.icon().isEmpty() ? icon : skill.icon();
                entry.setValue(new Skill(
                        skill.id(),
                        skill.level(),
                        name,
                        fillDescription(description, values.getOrDefault(skillKey(skill.id(), skill.level()), "")),
                        icon));
            }
        }
        return skills;
    }

    private static Map<Long, String> loadNpcAnimations(
            Path system, WorldVariant variant, List<String> names, List<String> notes) {
        if (TableFiles.find(system, variant, "MobSkillAnimgrp.dat") == null) {
            return Map.of();
        }
        return npcAnimationsOf(
                readSchemaTable(system, variant, Tables.NPC_SKILL_ANIMATIONS, "MobSkillAnimgrp.dat", names, notes));
    }

    private static Map<String, Combo> loadCombos(
            Path system, WorldVariant variant, List<String> names, List<String> notes) {
        var records = TableFiles.find(system, variant, "AnimationCombo.dat") == null
                ? List.<Map<String, Object>>of()
                : readSchemaTable(system, variant, Tables.COMBOS, "AnimationCombo.dat", names, notes);
        if (records.isEmpty()) {
            var combos = new HashMap<String, Combo>();
            for (var combo : SkillCombos.BUILT_IN) {
                combos.put(combo.name().toLowerCase(Locale.ROOT), combo);
            }
            return combos;
        }
        return combosOf(records);
    }

    private static Map<Integer, Npc> loadNpcs(
            Path system, WorldVariant variant, List<String> names, List<String> notes) {
        return npcsOf(readSchemaTable(
                system, variant, Tables.NPC_NAMES, findLocalizedTable(system, variant, "NpcName"), names, notes));
    }

    private static List<Map<String, Object>> readSchemaTable(
            Path system, WorldVariant variant, TableSpec spec, String name, List<String> names, List<String> notes) {
        var file = TableFiles.find(system, variant, name);
        if (file == null) {
            return readTextTable(system, variant, name.substring(0, name.length() - ".dat".length()) + ".txt", notes);
        }
        byte[] bytes;
        try {
            bytes = L2DataFile.read(file);
        } catch (IOException | RuntimeException failure) {
            notes.add(name + ": " + failure.getMessage());
            return List.of();
        }
        var table = TableSchemas.read(spec, file, bytes, names, TableSchemas.getClientFolder(system));
        if (table.report().state() == State.NOT_READ) {
            notes.add(name + " matches no known layout");
        }
        return table.rows();
    }

    private static List<Map<String, Object>> readTextTable(
            Path system, WorldVariant variant, String name, List<String> notes) {
        var file = TableFiles.find(system, variant, name);
        if (file == null) {
            return List.of();
        }
        List<Map<String, String>> rows;
        try {
            rows = L2TextTable.read(file);
        } catch (IOException | RuntimeException failure) {
            notes.add(name + ": " + failure.getMessage());
            return List.of();
        }
        var records = new ArrayList<Map<String, Object>>(rows.size());
        for (var row : rows) {
            var record = new HashMap<String, Object>();
            for (var field : row.entrySet()) {
                var key = TEXT_FIELDS.getOrDefault(field.getKey(), field.getKey());
                var value = field.getValue();
                if (TEXT_NUMBERS.contains(key)) {
                    try {
                        record.put(key, Integer.parseInt(value));
                    } catch (NumberFormatException notANumber) {
                        record.put(key, -1);
                    }
                } else if (key.equals("hit")) {
                    try {
                        record.put(key, Float.parseFloat(value));
                    } catch (NumberFormatException notANumber) {
                    }
                } else if (value.startsWith("{")) {
                    var list = L2TextTable.names(value);
                    record.put(key, list.isEmpty() ? "" : list.getFirst());
                } else {
                    record.put(key, L2TextTable.name(value));
                }
            }
            if (record.get("id") instanceof Integer id && id >= 0) {
                records.add(record);
            }
        }
        return records;
    }

    private static @Nullable String textOrNull(@Nullable Object value) {
        return value instanceof String text ? text : null;
    }

    private static long skillKey(int id, int level) {
        return (long) id << 32 | (level & 0xffffffffL);
    }

    private static String cleanIconName(@Nullable String name) {
        return name == null || name.equalsIgnoreCase("None") ? "" : name.strip();
    }

    private static String cleanDescription(@Nullable String text) {
        return text == null ? "" : text.replace("\\n", "\n").strip();
    }

    private static @Nullable String findLocalizedTableIn(Path folder, String table) {
        var english = table + "-e.dat";
        if (find(folder, english) != null) {
            return english;
        }
        try (var entries = Files.list(folder)) {
            var prefix = (table + "-").toLowerCase(Locale.ROOT);
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)
                            && name.toLowerCase(Locale.ROOT).endsWith(".dat"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static @Nullable Path find(Path folder, String name) {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (var entries = Files.list(folder)) {
            return entries.filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }
}

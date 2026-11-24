package com.rost.cruma.viewer.agent.export;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.export.NpcExport;
import com.rost.cruma.core.export.NpcExport.Race;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.ClientCatalog.Cast;
import com.rost.cruma.core.npc.ClientCatalog.Item;
import com.rost.cruma.core.npc.ClientCatalog.Skill;
import com.rost.cruma.core.npc.NpcSocials;
import com.rost.cruma.core.npc.ServerItems;
import com.rost.cruma.core.spawn.ArmorGrpTable;
import com.rost.cruma.core.spawn.ItemNames;
import com.rost.cruma.core.spawn.NpcGrpParts;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.spawn.PlayerEquipment;
import com.rost.cruma.core.spawn.PlayerEquipment.Slot;
import com.rost.cruma.core.spawn.PlayerEquipment.Tables;
import com.rost.cruma.core.spawn.WeaponGrpTable;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.FigureBodies;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneRig;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.spawn.SceneCharacter;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.ui.DirectorCatalog;
import com.rost.cruma.viewer.ui.DirectorCatalog.Media;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

final class ClientLists {
    static final int MELEE_REACH = 150;
    private static final int ABOUT_LENGTH = 160;
    private static final String BARE_HANDS = "hand";
    private static final Set<String> AIR_WORDS = Set.of("glide", "hover", "hovering");
    private static final Set<String> LOOP_WORDS =
            Set.of("wait", "walk", "run", "glide", "hovering", "moveauto", "loop");
    private static final Map<Kind, String> USE = new EnumMap<>(Map.of(
            Kind.SKILL,
            "A skill of a scene by id. A scene casts level 1, so level_one must be true, else take twin. shows counts"
                + " the effects a cast starts in a scene, and 0 shows nothing. A query also finds the words of type,"
                + " on, ranged, shows and the description.",
            Kind.ITEM,
            "Gear of a character of a scene by id, in its slot. scene_gear says whether this client's scenes keep"
                    + " gear. bodies counts the races that can wear it, and model with a race lists only what that body"
                    + " wears. A query also finds kind, weapon, slot and grade as s-grade.",
            Kind.NPC,
            "An NPC of a scene by id. An NPC with no name, such as a helper of the client's scenes, shows its npcgrp"
                    + " class, and the query no-name lists them. kind animation with model set to the id lists its"
                    + " sequences.",
            Kind.RACE,
            "A character of a scene by its race. in_scene false: this client's scenes cannot hold that body. choices"
                    + " counts the gear of each slot. kind item or animation with model set to the race lists them.",
            Kind.ANIMATION,
            "A sequence of an order by name, with its length. air marks sequences in the air, and flies says the body"
                + " can fly. A race lists those of its bare hands first, and a query such as bow lists those of that"
                + " weapon, its stance first.",
            Kind.MUSIC,
            "A music track of a scene's sound, by this name.",
            Kind.VOICE,
            "A voice line of a scene's sound, by this name. A name that ends in _id takes a number the server gives.",
            Kind.SLIDE,
            "A picture of a scene's screen, by this name. size is the part of its texture in use.",
            Kind.MESSAGE,
            "A line of SystemMsg on a scene's screen, by this number.",
            Kind.POST_EFFECT,
            "A row of PostEffects on a scene's screen, by this number."));

    private final Path client;
    private final WorldVariant variant;
    private @Nullable ClientCatalog catalog;
    private @Nullable NpcGrpTable npcTable;
    private @Nullable NpcSocials classNames;
    private @Nullable List<Entry> skills;
    private @Nullable List<Entry> npcs;
    private @Nullable Bodies bodies;
    private @Nullable Media media;
    private @Nullable Path itemsFolder;
    private @Nullable ServerItems serverItems;

    ClientLists(Path client, WorldVariant variant) {
        this.client = client;
        this.variant = variant;
    }

    enum Kind {
        SKILL("skill", "skills"),
        ITEM("item", "items"),
        NPC("npc", "npcs"),
        RACE("race", "races"),
        ANIMATION("animation", "animations"),
        MUSIC("music", "music"),
        VOICE("voice", "voices"),
        SLIDE("slide", "slides"),
        MESSAGE("message", "messages"),
        POST_EFFECT("post_effect", "post_effects");

        final String word;
        final String rows;

        Kind(String word, String rows) {
            this.word = word;
            this.rows = rows;
        }

        static @Nullable Kind parse(@Nullable String word) {
            for (var kind : values()) {
                if (kind.word.equals(word)) {
                    return kind;
                }
            }
            return null;
        }
    }

    record Entry(int id, String name, String known, String all, JSONObject row) {
        static Entry of(int id, String name, String words, String more, JSONObject row) {
            var known = (name + " " + (id >= 0 ? id : "") + " " + words).toLowerCase(Locale.ROOT);
            return new Entry(id, name, known, known + " " + more.toLowerCase(Locale.ROOT), row);
        }
    }

    record Found(List<JSONObject> shown, int total) {}

    record Model(String label, String mesh, @Nullable String npcClass, String figure) {
        Model(String label, String mesh, @Nullable String npcClass) {
            this(label, mesh, npcClass, PlayerFigures.figureOf(mesh, npcClass));
        }
    }

    private record Bodies(
            List<Race> races,
            Tables tables,
            boolean gear,
            boolean mages,
            Map<Integer, Map<String, Slot>> slots,
            Map<String, Map<Slot, Integer>> choices) {}

    static Found find(List<Entry> entries, @Nullable String query, int limit) {
        var whole = query == null ? "" : query.toLowerCase(Locale.ROOT).strip();
        var words = whole.isEmpty() ? new String[0] : whole.split("\\s+");
        var ranked = new ArrayList<int[]>();
        for (var index = 0; index < entries.size(); index++) {
            var rank = rank(entries.get(index), whole, words);
            if (rank >= 0) {
                ranked.add(new int[] {index, rank});
            }
        }
        ranked.sort(Comparator.comparingInt(pair -> pair[1]));
        var shown = new ArrayList<JSONObject>(Math.min(limit, ranked.size()));
        for (var pair : ranked.subList(0, Math.min(limit, ranked.size()))) {
            shown.add(entries.get(pair[0]).row());
        }
        return new Found(List.copyOf(shown), ranked.size());
    }

    static boolean inAir(String sequence) {
        var lower = sequence.toLowerCase(Locale.ROOT);
        if (lower.startsWith("air_hold") || lower.startsWith("airhold")) {
            return false;
        }
        var words = lower.split("[^a-z]+");
        if (words.length == 0) {
            return false;
        }
        if (words[0].equals("air") || words[0].equals("fly")) {
            return true;
        }
        for (var word : words) {
            if (AIR_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    static boolean isAirLoop(String sequence) {
        if (!inAir(sequence)) {
            return false;
        }
        var words = sequence.toLowerCase(Locale.ROOT).split("[^a-z]+");
        if (words.length == 1 && words[0].equals("fly")) {
            return true;
        }
        for (var word : words) {
            if (LOOP_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    static String weapon(int handness) {
        return switch (handness) {
            case 0 -> "shield";
            case 1 -> "one-handed";
            case 2 -> "two-handed";
            case 3 -> "dual";
            case 4 -> "pole";
            case 5 -> "bow";
            case 7 -> "fist";
            case 8 -> "crossbow";
            case 9 -> "rapier";
            case 10 -> "dual dagger";
            case 11 -> "gun";
            case 12 -> "dagger";
            case 13 -> "dual blunt";
            case 14 -> "staff";
            case 15 -> "orb";
            case 16 -> "fishing rod";
            case 17 -> "pistols";
            case 18 -> "one-handed magic sword";
            case 19 -> "one-handed magic blunt";
            case 20 -> "one-handed blunt";
            default -> "other";
        };
    }

    static Race race(String asked, List<Race> races) throws ToolProblem {
        var wanted = words(asked);
        var holding = new ArrayList<Race>();
        for (var race : races) {
            if (race.label().equalsIgnoreCase(asked.strip())
                    || race.figure().equalsIgnoreCase(asked.strip())
                    || race.face().equalsIgnoreCase(asked.strip())
                    || words(race.label()).equals(wanted)) {
                return race;
            }
            if (words(race.label()).containsAll(wanted)) {
                holding.add(race);
            }
        }
        if (holding.size() == 1) {
            return holding.getFirst();
        }
        var names = new ArrayList<String>();
        for (var race : holding.isEmpty() ? races : holding) {
            names.add(race.label());
        }
        throw new ToolProblem((holding.isEmpty()
                        ? "This client has no race \"" + asked + "\"."
                        : "\"" + asked + "\" fits several races.")
                + " Name one of: "
                + String.join("; ", names)
                + ".");
    }

    static Entry skill(ClientCatalog catalog, Skill skill, int effects) {
        var id = skill.id();
        var name = skill.name().isBlank() ? "Skill " + id : skill.name();
        var row = new JSONObject();
        row.put("id", id);
        row.put("name", name);
        row.put("levels", catalog.getLevels(id));
        var first = catalog.hasLevel(id, 1);
        row.put("level_one", first);
        var words = new StringJoiner(" ");
        if (first) {
            row.put("shows", effects);
            if (effects > 0) {
                words.add("shows");
            }
        } else {
            var twin = catalog.getTwinAtLevelOne(id);
            if (twin > 0) {
                row.put("twin", twin);
            }
        }
        var cast = catalog.getCast(id, 1);
        if (cast != null) {
            row.put("seconds", Math.round(cast.seconds() * 100) / 100.0);
            var type = type(cast.kind());
            if (type != null) {
                row.put("type", type);
                words.add(type);
            }
            if (cast.onSelf()) {
                row.put("on", "self");
                words.add("self");
            } else {
                row.put("on", "target");
                row.put("range", cast.range());
                words.add("target");
                if (cast.range() > MELEE_REACH) {
                    row.put("ranged", true);
                    words.add("ranged");
                }
            }
            var plays = plays(catalog, cast);
            if (!plays.isEmpty()) {
                row.put("plays", plays);
            }
        }
        var about = skill.description().replace('\n', ' ').strip();
        if (!about.isEmpty() && !about.equalsIgnoreCase("none")) {
            row.put("about", about.length() <= ABOUT_LENGTH ? about : about.substring(0, ABOUT_LENGTH - 3) + "...");
        }
        return Entry.of(id, name, words.toString(), skill.description(), row);
    }

    static String stance(List<String> names, @Nullable String query, String idle) {
        var wanted = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        for (var weapon : List.of(wanted, BARE_HANDS)) {
            for (var name : names) {
                if (!weapon.isEmpty()
                        && !weapon.contains(" ")
                        && name.toLowerCase(Locale.ROOT).startsWith("wait_" + weapon + "_")) {
                    return name;
                }
            }
        }
        return idle;
    }

    static boolean startsWords(String name, String[] words) {
        var lower = name.toLowerCase(Locale.ROOT);
        for (var word : words) {
            var found = false;
            for (var at = lower.indexOf(word); at >= 0 && !found; at = lower.indexOf(word, at + 1)) {
                found = at == 0
                        || !Character.isLetter(name.charAt(at - 1))
                        || (Character.isUpperCase(name.charAt(at)) && Character.isLowerCase(name.charAt(at - 1)));
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    synchronized JSONObject answer(
            Kind kind, @Nullable String query, @Nullable String model, @Nullable Path scripts, int limit, Job job)
            throws ToolProblem {
        var answer = new JSONObject();
        answer.put("client", client.getFileName().toString());
        List<Entry> entries = switch (kind) {
            case SKILL -> skills(job);
            case ITEM -> items(model, scripts, answer, job);
            case NPC -> npcs(job);
            case RACE -> races(job);
            case ANIMATION -> animations(model, query, answer, job);
            default -> media(kind, job);
        };
        var found = find(entries, query, limit);
        answer.put("found", found.total());
        answer.put("more", found.total() - found.shown().size());
        answer.put(kind.rows, found.shown());
        answer.put("use", USE.get(kind));
        return answer;
    }

    private List<Entry> skills(Job job) {
        if (skills == null) {
            var catalog = catalog(job);
            job.setProgress("Reading the effects of the client's skills");
            Map<Integer, Integer> counts;
            var loader = loader();
            try {
                counts = DirectorCatalog.effectCounts(catalog, loader);
            } finally {
                loader.trimCaches();
            }
            var all = catalog.getSkills();
            all.sort(Comparator.comparing(Skill::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(Skill::id));
            var entries = new ArrayList<Entry>(all.size());
            for (var skill : all) {
                entries.add(skill(catalog, skill, counts.getOrDefault(skill.id(), 0)));
            }
            skills = List.copyOf(entries);
        }
        return skills;
    }

    private List<Entry> items(@Nullable String model, @Nullable Path scripts, JSONObject answer, Job job)
            throws ToolProblem {
        var catalog = catalog(job);
        var bodies = bodies(job);
        var race = model == null ? null : race(model, bodies.races());
        var server = serverItems(scripts, job);
        answer.put("scene_gear", bodies.gear());
        if (race != null) {
            answer.put("race", race.label());
        }
        if (server == null) {
            answer.put(
                    "grade", "Needs the server's script folder: pass scripts, or set it in Settings for this client.");
        }
        var all = new ArrayList<>(catalog.getItems());
        all.sort(Comparator.comparing((Item item) -> itemName(item), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(Item::id));
        var entries = new ArrayList<Entry>(all.size());
        for (var item : all) {
            var worn = bodies.slots().get(item.id());
            var slot = worn == null
                    ? null
                    : race == null ? worn.values().iterator().next() : worn.get(race.figure());
            if ((race != null && slot == null) || (item.name().isBlank() && worn == null)) {
                continue;
            }
            var weapon = bodies.tables().weapons().getWeapon(item.id());
            var row = new JSONObject();
            row.put("id", item.id());
            row.put("name", itemName(item));
            var words = new StringJoiner(" ");
            var kind = weapon != null
                    ? weapon.handness() == 0 ? "shield" : "weapon"
                    : bodies.tables().armors().getArmor(item.id()) != null ? "armor" : "item";
            row.put("kind", kind);
            words.add(kind);
            if (weapon != null && weapon.handness() != 0) {
                row.put("weapon", weapon(weapon.handness()));
                words.add(weapon(weapon.handness()));
            }
            var known = server == null ? null : server.byId(item.id());
            if (known != null) {
                row.put("grade", known.grade());
                words.add(known.grade() + "-grade");
            }
            if (slot != null) {
                var label = weapon != null && weapon.handness() == 0 ? Slot.LEFT_HAND.getLabel() : slot.getLabel();
                row.put("slot", label);
                words.add(label);
                if (race == null) {
                    row.put("bodies", worn.size());
                }
            }
            entries.add(Entry.of(item.id(), itemName(item), words.toString(), "", row));
        }
        return entries;
    }

    private List<Entry> npcs(Job job) {
        if (npcs == null) {
            var catalog = catalog(job);
            var table = npcTable();
            var rows = DirectorCatalog.npcRows(catalog.getNpcs(), DirectorCatalog.npcClasses(client, variant));
            var entries = new ArrayList<Entry>(rows.size());
            for (var row : rows) {
                var npc = catalog.getNpc(row.id());
                var named = npc != null && !npc.name().isBlank();
                var appearance = table.getAppearance(row.id());
                var json = new JSONObject();
                json.put("id", row.id());
                json.put("name", row.name());
                var words = new StringJoiner(" ");
                if (named && !npc.title().isBlank()) {
                    json.put("title", npc.title());
                    words.add(npc.title());
                }
                if (!named) {
                    json.put("named", false);
                    words.add("no-name");
                }
                if (appearance != null) {
                    json.put("class", appearance.npcClass());
                    json.put("mesh", appearance.mesh());
                    words.add(appearance.npcClass());
                    words.add(appearance.mesh());
                    if (appearance.mesh() != null && UnrealLoader.isPlayerFace(appearance.mesh())) {
                        json.put("player_like", true);
                    }
                }
                entries.add(Entry.of(row.id(), row.name(), words.toString(), "", json));
            }
            npcs = List.copyOf(entries);
        }
        return npcs;
    }

    private List<Entry> races(Job job) {
        var bodies = bodies(job);
        var entries = new ArrayList<Entry>();
        for (var race : bodies.races()) {
            var row = new JSONObject();
            row.put("race", race.label());
            row.put("figure", race.figure());
            row.put("face", race.face());
            row.put("in_scene", (bodies.mages() || !isMystic(race)) && SceneCharacter.canHold(race));
            row.put("gear", bodies.gear());
            var choices = new JSONObject();
            bodies.choices()
                    .getOrDefault(race.figure(), Map.of())
                    .forEach((slot, count) -> choices.put(slot.getLabel(), count));
            row.put("choices", choices);
            entries.add(Entry.of(-1, race.label(), race.figure() + " " + race.face(), "", row));
        }
        return entries;
    }

    private List<Entry> animations(@Nullable String asked, @Nullable String query, JSONObject answer, Job job)
            throws ToolProblem {
        if (asked == null) {
            throw new ToolProblem(
                    "kind animation needs model: an NPC id, a mesh such as LineageMonsters2.valakas_m00, or a race"
                            + " such as Elf, female.");
        }
        var model = model(asked, job);
        job.setProgress("Reading the animation of " + model.mesh());
        var loader = loader();
        SceneRig rig;
        try {
            rig = loader.loadAnimationRig(model.mesh(), model.figure());
        } finally {
            loader.trimCaches();
        }
        if (rig == null || rig.getSequenceCount() == 0) {
            throw new ToolProblem(model.mesh() + " has no animation in this client.");
        }
        var names = new ArrayList<String>(rig.getSequenceCount());
        var index = new HashMap<String, Integer>();
        for (var sequence = 0; sequence < rig.getSequenceCount(); sequence++) {
            names.add(rig.getName(sequence));
            index.putIfAbsent(rig.getName(sequence), sequence);
        }
        var order = names;
        var waiting = rig.getName(rig.getIdle());
        if (UnrealLoader.isPlayerFace(model.mesh())) {
            waiting = stance(names, query, waiting);
            order = new ArrayList<>(SceneOrders.byWeapon(names, waiting));
        }
        var loops = new LinkedHashSet<String>();
        var entries = new ArrayList<Entry>(order.size());
        for (var name : order) {
            var sequence = index.get(name);
            var row = new JSONObject();
            row.put("name", name);
            row.put("seconds", Math.round(rig.getSeconds(sequence) * 100) / 100.0);
            row.put("frames", rig.getFrames(sequence));
            var air = inAir(name);
            if (air) {
                row.put("air", true);
                if (isAirLoop(name)) {
                    loops.add(name);
                }
            }
            entries.add(Entry.of(-1, name, air ? "air" : "", "", row));
        }
        var about = new JSONObject();
        about.put("name", model.label());
        about.put("mesh", model.mesh());
        about.put("sequences", names.size());
        about.put("waits_in", waiting);
        about.put("flies", !loops.isEmpty());
        if (!loops.isEmpty()) {
            about.put("air_loops", List.copyOf(loops));
        }
        if (model.npcClass() != null) {
            about.put("class", classNames(model.npcClass()));
        }
        answer.put("model", about);
        return entries;
    }

    private Model model(String asked, Job job) throws ToolProblem {
        if (asked.chars().allMatch(Character::isDigit)) {
            var id = Integer.parseInt(asked);
            var appearance = npcTable().getAppearance(id);
            if (appearance == null
                    || appearance.mesh() == null
                    || appearance.mesh().isBlank()) {
                throw new ToolProblem(
                        "The client has no model for NPC " + id + " in npcgrp. assets_find with kind npc finds NPCs.");
            }
            var npc = catalog(job).getNpc(id);
            var name = npc == null || npc.name().isBlank() ? appearance.npcClass() : npc.name();
            return new Model(
                    name + " (" + id + ")",
                    appearance.mesh(),
                    appearance.npcClass().isBlank() ? null : appearance.npcClass());
        }
        if (asked.contains(".")) {
            return new Model(asked, asked, null);
        }
        var race = race(asked, bodies(job).races());
        return new Model(race.label(), race.face(), null, race.figure());
    }

    private List<Entry> media(Kind kind, Job job) {
        if (media == null) {
            job.setProgress("Reading the client's music, voices, sounds, slides and lines");
            media = DirectorCatalog.media(client, variant);
        }
        var entries = new ArrayList<Entry>();
        switch (kind) {
            case MUSIC, VOICE -> {
                for (var clip : kind == Kind.MUSIC ? media.tracks() : media.voices()) {
                    var row = new JSONObject();
                    row.put("name", clip.name());
                    entries.add(Entry.of(-1, clip.name(), "", "", row));
                }
            }
            case SLIDE -> {
                for (var slide : media.slides()) {
                    var row = new JSONObject();
                    row.put("name", slide.name());
                    row.put("size", slide.partWidth() + " x " + slide.partHeight());
                    entries.add(Entry.of(-1, slide.name(), "", "", row));
                }
            }
            default -> {
                for (var line : kind == Kind.MESSAGE ? media.lines() : media.posts()) {
                    var row = new JSONObject();
                    row.put("id", line.id());
                    row.put(kind == Kind.MESSAGE ? "text" : "what", line.name());
                    entries.add(Entry.of(line.id(), line.name(), "", "", row));
                }
            }
        }
        return entries;
    }

    private ClientCatalog catalog(Job job) {
        if (catalog == null) {
            job.setProgress("Reading the client's tables");
            catalog = ClientCatalog.read(client, variant);
        }
        return catalog;
    }

    private JSONObject classNames(String npcClass) {
        if (classNames == null) {
            classNames = NpcSocials.read(client, variant);
        }
        var named = new JSONObject();
        named.put("name", npcClass);
        named.put("kinds", new JSONObject(new TreeMap<>(classNames.getKinds(npcClass))));
        var socials = new JSONObject();
        classNames.getSocials(npcClass).forEach((social, sequence) -> socials.put(String.valueOf(social), sequence));
        named.put("socials", socials);
        return named;
    }

    private NpcGrpTable npcTable() {
        if (npcTable == null) {
            try {
                npcTable = NpcGrpTable.read(client.resolve("system"), variant);
            } catch (IOException | RuntimeException unreadable) {
                npcTable = NpcGrpTable.empty();
            }
        }
        return npcTable;
    }

    private Bodies bodies(Job job) {
        if (bodies == null) {
            job.setProgress("Reading the client's races and item tables");
            var system = client.resolve("system");
            WeaponGrpTable weapons;
            try {
                weapons = WeaponGrpTable.read(system, variant);
            } catch (IOException | RuntimeException unreadable) {
                weapons = WeaponGrpTable.empty();
            }
            ArmorGrpTable armors;
            try {
                armors = ArmorGrpTable.read(system, variant);
            } catch (IOException | RuntimeException unreadable) {
                armors = ArmorGrpTable.empty();
            }
            var tables = new Tables(
                    ItemNames.empty(),
                    weapons,
                    armors,
                    NpcGrpParts.empty(),
                    PlayerEquipment.Waits.NONE,
                    FigureBodies.read(client, variant));
            var races = new ArrayList<Race>();
            var loader = loader();
            var created = PlayerFigures.readCreated(client, variant);
            try {
                for (var race : NpcExport.RACES) {
                    if (PlayerFigures.isOffered(
                            race.figure(),
                            loader.hasSkeletalMesh(race.face()),
                            armors::hasLooks,
                            tables.bodies()::hasRow,
                            created::contains)) {
                        races.add(race);
                    }
                }
            } finally {
                loader.trimCaches();
            }
            var scene = GameScenes.model(client);
            var pc = scene == null ? Set.<String>of() : scene.pc();
            var slots = new HashMap<Integer, Map<String, Slot>>();
            var choices = new HashMap<String, Map<Slot, Integer>>();
            for (var race : races) {
                var counts = new EnumMap<Slot, Integer>(Slot.class);
                PlayerEquipment.findChoices(tables, race.figure(), _ -> null).forEach((slot, list) -> {
                    counts.put(slot, list.size());
                    for (var choice : list) {
                        slots.computeIfAbsent(choice.id(), _ -> new HashMap<>()).putIfAbsent(race.figure(), slot);
                    }
                });
                choices.put(race.figure(), counts);
            }
            bodies = new Bodies(
                    List.copyOf(races),
                    tables,
                    SceneCharacter.hasClothes(pc),
                    SceneCharacter.hasMages(pc),
                    slots,
                    choices);
        }
        return bodies;
    }

    private @Nullable ServerItems serverItems(@Nullable Path scripts, Job job) {
        var folder = scripts == null ? null : SpawnSource.resolveFolder(scripts);
        if (folder == null) {
            return null;
        }
        if (!folder.equals(itemsFolder)) {
            job.setProgress("Reading the server's items");
            var data = SpawnSource.find(folder, "itemdata.txt");
            try {
                serverItems = ServerItems.read(data, data == null ? SpawnSource.find(folder, "item_pch.txt") : null);
            } catch (IOException | RuntimeException unreadable) {
                serverItems = null;
            }
            itemsFolder = folder;
        }
        return serverItems == null || serverItems.size() == 0 ? null : serverItems;
    }

    private UnrealLoader loader() {
        var loader = new UnrealLoader(client, new Options(false, 0), true);
        loader.worldVariant(variant);
        return loader;
    }

    private static @Nullable String type(int kind) {
        return switch (kind) {
            case Cast.PHYSICAL -> "physical";
            case Cast.MAGIC -> "magic";
            case Cast.STATIC -> "static";
            case Cast.DANCE -> "dance";
            case 4 -> "triggered";
            default -> null;
        };
    }

    private static List<String> plays(ClientCatalog catalog, Cast cast) {
        if (cast.animations().isEmpty()) {
            return List.of();
        }
        var combo = catalog.getCombo(cast.animations().getFirst());
        return combo == null ? cast.animations() : combo.parts();
    }

    private static String itemName(Item item) {
        return item.name().isBlank() ? "Item " + item.id() : item.name();
    }

    private static boolean isMystic(Race race) {
        return race.face().startsWith("Magic.") || race.face().startsWith("Shaman.");
    }

    private static int rank(Entry entry, String whole, String[] words) {
        if (words.length == 0) {
            return 0;
        }
        var name = entry.name().toLowerCase(Locale.ROOT);
        if (name.equals(whole)
                || (entry.id() >= 0 && Integer.toString(entry.id()).equals(whole))) {
            return 0;
        }
        if (name.startsWith(whole)) {
            return 1;
        }
        if (startsWords(entry.name(), words)) {
            return 2;
        }
        if (holdsAll(name, words)) {
            return 3;
        }
        if (holdsAll(entry.known(), words)) {
            return 4;
        }
        return holdsAll(entry.all(), words) ? 5 : -1;
    }

    private static boolean holdsAll(String text, String[] words) {
        for (var word : words) {
            if (!text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> words(String text) {
        var words = new ArrayList<String>();
        for (var word : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        words.sort(null);
        return words;
    }
}

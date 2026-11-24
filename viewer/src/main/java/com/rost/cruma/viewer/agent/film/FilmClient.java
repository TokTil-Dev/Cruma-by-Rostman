package com.rost.cruma.viewer.agent.film;

import com.rost.cruma.core.export.NpcExport;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.ClientCatalog.Skill;
import com.rost.cruma.core.npc.ClientIcons;
import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.spawn.NpcGrpTable.Appearance;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.GameScenes.Model;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.PostEffects;
import com.rost.cruma.core.world.SceneHelper;
import com.rost.cruma.core.world.SceneOrders.NpcSpeed;
import com.rost.cruma.core.world.SceneOrders.Skills;
import com.rost.cruma.core.world.SceneOrders.Speeds;
import com.rost.cruma.core.world.SoundSpans.Kind;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.spawn.RaceOffers;
import com.rost.cruma.viewer.spawn.SpawnSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class FilmClient implements FilmLookup {
    private static final Map<String, FilmClient> known = new ConcurrentHashMap<>();
    private static final String OGG = ".ogg";
    private static final String UAX = ".uax";
    private static final String SOUNDS = "Sounds";
    private static final String LANGUAGE = "-[a-zA-Z]{1,3}$";
    private static final String SLIDES = "LineageSlideImage";
    private static final int NEAR = 8;
    private static final Logger log = LogManager.getLogger(FilmClient.class);

    private final Path root;
    private final WorldVariant variant;
    private final @Nullable Path scripts;
    private final Map<Integer, @Nullable Map<String, Double>> sequences = new HashMap<>();
    private @Nullable ClientCatalog catalog;
    private @Nullable NpcGrpTable npcgrp;
    private @Nullable Map<Integer, String> names;
    private @Nullable List<Scene> scenes;
    private int helper = -1;
    private boolean modelRead;
    private @Nullable Model model;
    private @Nullable Map<Integer, NpcSpeed> speeds;
    private @Nullable Map<Kind, List<String>> clips;
    private @Nullable List<Picture> slides;
    private @Nullable List<Integer> posts;
    private @Nullable List<String> effects;
    private @Nullable UnrealLoader loader;

    private FilmClient(Path root, WorldVariant variant, @Nullable Path scripts) {
        this.root = root;
        this.variant = variant;
        this.scripts = scripts;
    }

    static FilmClient of(Path root, WorldVariant variant, @Nullable Path scripts) {
        return known.computeIfAbsent(
                root.toAbsolutePath().normalize() + "|" + variant.getKey() + "|" + scripts,
                _ -> new FilmClient(root, variant, scripts));
    }

    static String near(List<String> near) {
        if (near.isEmpty()) {
            return "";
        }
        var shown = near.subList(0, Math.min(NEAR, near.size()));
        return " Near: "
                + String.join(", ", shown)
                + (near.size() > NEAR ? " and " + (near.size() - NEAR) + " more." : ".");
    }

    @Override
    public @Nullable String findMissingFigure(String figure) {
        for (var race : NpcExport.RACES) {
            if (race.figure().equalsIgnoreCase(figure)) {
                return RaceOffers.findMissing(race, root, variant);
            }
        }
        return null;
    }

    @Override
    public String getClient() {
        return root.getFileName().toString();
    }

    @Override
    public synchronized int findNpc(String text, List<String> notes) throws ToolProblem {
        var named = names();
        var number = number(text);
        if (number != null) {
            if (!named.containsKey(number) && npcgrp().getAppearance(number) == null) {
                throw new ToolProblem("This client has no NPC " + number + ". Give its name, or another number.");
            }
            return number;
        }
        var wanted = text.strip();
        var exact = new TreeSet<Integer>();
        var near = new ArrayList<String>();
        for (var entry : named.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(wanted)) {
                exact.add(entry.getKey());
            } else if (entry.getValue().toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))) {
                near.add(entry.getValue() + " (" + entry.getKey() + ")");
            }
        }
        if (exact.size() > 1) {
            var others = new ArrayList<String>();
            for (var id : exact.tailSet(exact.first(), false)) {
                if (others.size() < NEAR) {
                    others.add(Integer.toString(id));
                }
            }
            notes.add(exact.size()
                    + " NPCs are named "
                    + named.get(exact.first())
                    + ". This takes "
                    + exact.first()
                    + ". Others: "
                    + String.join(", ", others)
                    + ". Give a number to take another.");
        }
        if (!exact.isEmpty()) {
            return exact.first();
        }
        throw new ToolProblem("No NPC of this client is named \"" + wanted + "\"." + near(near)
                + " Give its number, or call npc_find.");
    }

    @Override
    public synchronized @Nullable String getNpcName(int npc) {
        var name = names().get(npc);
        if (name != null) {
            return name;
        }
        var appearance = npcgrp().getAppearance(npc);
        return appearance == null ? null : className(appearance);
    }

    @Override
    public synchronized int getHelper() {
        if (helper < 0) {
            try {
                helper = SceneHelper.of(getScenes(), npcgrp()::getAppearance);
            } catch (RuntimeException unreadable) {
                log.warn("The helper of {} could not be read: {}", root, unreadable.toString());
                helper = 0;
            }
        }
        return helper;
    }

    @Override
    public synchronized int findSkill(String text, List<String> notes) throws ToolProblem {
        var catalog = catalog();
        var number = number(text);
        if (number != null) {
            if (catalog.getSkill(number, 1) == null && catalog.getCast(number, 1) == null) {
                throw new ToolProblem("This client has no skill " + number + ". Give its name, or another number.");
            }
            return number;
        }
        var wanted = text.strip();
        var exact = new ArrayList<Skill>();
        var near = new ArrayList<String>();
        for (var skill : catalog.getSkillsAtLevelOne()) {
            if (skill.name().equalsIgnoreCase(wanted)) {
                exact.add(skill);
            } else if (near.size() < NEAR * 4
                    && skill.name().toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))) {
                near.add(skill.name() + " (" + skill.id() + ")");
            }
        }
        if (exact.isEmpty()) {
            throw new ToolProblem("No skill with level 1 is named \"" + wanted + "\", and a scene plays no other level."
                    + near(near) + " Give its number.");
        }
        exact.sort(Comparator.comparing((Skill skill) -> !shows(catalog, skill.id()))
                .thenComparingInt(Skill::id));
        var chosen = exact.getFirst();
        if (exact.size() > 1) {
            var others = new ArrayList<String>();
            for (var skill : exact.subList(1, Math.min(exact.size(), NEAR + 1))) {
                others.add(Integer.toString(skill.id()));
            }
            notes.add(exact.size()
                    + " skills are named "
                    + chosen.name()
                    + ". This takes "
                    + chosen.id()
                    + (shows(catalog, chosen.id()) ? ", which has an effect" : "")
                    + ". Others: "
                    + String.join(", ", others)
                    + ". Give a number to take another.");
        }
        return chosen.id();
    }

    @Override
    public synchronized @Nullable String getSkillName(int skill) {
        var found = catalog().getSkill(skill, 1);
        return found == null ? null : found.name();
    }

    @Override
    public Skills getSkills() {
        var catalog = catalogNow();
        return new Skills() {
            @Override
            public boolean hasLevelOne(int skill) {
                return catalog.hasLevel(skill, 1);
            }

            @Override
            public int getTwin(int skill) {
                return catalog.getTwinAtLevelOne(skill);
            }

            @Override
            public boolean takesBody(int skill) {
                var cast = catalog.hasLevel(skill, 1) ? catalog.getCast(skill, 1) : null;
                return cast != null && cast.style() != 0;
            }
        };
    }

    @Override
    public synchronized int findItem(String text) throws ToolProblem {
        var catalog = catalog();
        var number = number(text);
        if (number != null) {
            if (catalog.getItem(number) == null) {
                throw new ToolProblem("This client has no item " + number + ".");
            }
            return number;
        }
        var wanted = text.strip();
        var best = Integer.MAX_VALUE;
        var near = new ArrayList<String>();
        for (var item : catalog.getItems()) {
            if (item.name().equalsIgnoreCase(wanted)) {
                best = Math.min(best, item.id());
            } else if (near.size() < NEAR * 4
                    && item.name().toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))) {
                near.add(item.name() + " (" + item.id() + ")");
            }
        }
        if (best == Integer.MAX_VALUE) {
            throw new ToolProblem(
                    "No item of this client is named \"" + wanted + "\"." + near(near) + " Give its number.");
        }
        return best;
    }

    @Override
    public synchronized @Nullable Map<String, Double> getSequences(int npc) {
        if (sequences.containsKey(npc)) {
            return sequences.get(npc);
        }
        Map<String, Double> read = null;
        var appearance = npcgrp().getAppearance(npc);
        if (appearance != null) {
            try {
                var rig = loader().loadSceneRig(appearance.mesh(), 1);
                if (rig != null) {
                    read = new LinkedHashMap<>();
                    for (var sequence = 0; sequence < rig.getSequenceCount(); sequence++) {
                        read.put(rig.getName(sequence), (double) rig.getSeconds(sequence));
                    }
                }
            } catch (RuntimeException unreadable) {
                log.warn("The sequences of NPC {} could not be read: {}", npc, unreadable.toString());
            } finally {
                loader().trimCaches();
            }
        }
        sequences.put(npc, read);
        return read;
    }

    @Override
    public Speeds getSpeeds() {
        var known = speedsNow();
        return npc -> known.get(npc);
    }

    @Override
    public synchronized @Nullable Model getModel() {
        if (!modelRead) {
            modelRead = true;
            try {
                model = GameScenes.model(root);
            } catch (RuntimeException unreadable) {
                log.warn("The scene fields of {} could not be read: {}", root, unreadable.toString());
            }
        }
        return model;
    }

    @Override
    public synchronized List<String> getClips(Kind kind) {
        if (clips == null) {
            var read = new HashMap<Kind, List<String>>();
            read.put(Kind.TRACK, files(root.resolve("music"), ""));
            read.put(Kind.VOICE, files(root.resolve("Voice"), LANGUAGE));
            read.put(Kind.SOUND, sounds());
            clips = read;
        }
        return clips.get(kind);
    }

    @Override
    public synchronized List<Picture> getSlides() {
        if (slides == null) {
            var parts = new HashMap<String, int[]>();
            for (var scene : getScenes()) {
                for (var item : scene.items()) {
                    var screen = item.screen();
                    if (screen != null
                            && !screen.slide().isEmpty()
                            && screen.slideWidth() > 1
                            && screen.slideHeight() > 1) {
                        parts.putIfAbsent(
                                screen.slide().toLowerCase(Locale.ROOT),
                                new int[] {screen.slideWidth(), screen.slideHeight()});
                    }
                }
            }
            var read = new ArrayList<Picture>();
            try {
                for (var texture : new ClientIcons(root).loadTextures(SLIDES)) {
                    var name = SLIDES + "." + texture.name();
                    var part = parts.get(name.toLowerCase(Locale.ROOT));
                    read.add(
                            part == null
                                    ? new Picture(name, texture.width(), texture.height())
                                    : new Picture(name, part[0], part[1]));
                }
            } catch (RuntimeException unreadable) {
                log.warn("The slides of {} could not be read: {}", root, unreadable.toString());
            }
            slides = List.copyOf(read);
        }
        return slides;
    }

    @Override
    public synchronized List<Integer> getPostEffects() {
        if (posts == null) {
            var read = new ArrayList<Integer>();
            try {
                for (var row : PostEffects.read(root, variant).getRows()) {
                    read.add(row.id());
                }
            } catch (RuntimeException unreadable) {
                log.warn("PostEffectData of {} could not be read: {}", root, unreadable.toString());
            }
            posts = List.copyOf(read);
        }
        return posts;
    }

    @Override
    public synchronized List<String> getEffects() {
        if (effects == null) {
            var read = new ArrayList<String>();
            try {
                for (var effect : loader().listEffects()) {
                    read.add(effect.name());
                }
            } catch (RuntimeException unreadable) {
                log.warn("The effects of {} could not be read: {}", root, unreadable.toString());
            } finally {
                loader().trimCaches();
            }
            read.sort(String.CASE_INSENSITIVE_ORDER);
            effects = List.copyOf(read);
        }
        return effects;
    }

    synchronized List<Scene> getScenes() {
        if (scenes == null) {
            List<Scene> read;
            try {
                read = GameScenes.find(root);
            } catch (RuntimeException unreadable) {
                log.warn("The scenes of {} could not be read: {}", root, unreadable.toString());
                read = List.of();
            }
            scenes = read;
        }
        return scenes;
    }

    private synchronized ClientCatalog catalog() {
        if (catalog == null) {
            catalog = ClientCatalog.read(root, variant);
        }
        return catalog;
    }

    private ClientCatalog catalogNow() {
        return catalog();
    }

    private synchronized NpcGrpTable npcgrp() {
        if (npcgrp == null) {
            try {
                npcgrp = NpcGrpTable.read(root.resolve("system"), variant);
            } catch (IOException | RuntimeException unreadable) {
                log.warn("The npcgrp of {} could not be read: {}", root, unreadable.toString());
                npcgrp = NpcGrpTable.empty();
            }
        }
        return npcgrp;
    }

    private synchronized Map<Integer, String> names() {
        if (names == null) {
            var read = new HashMap<Integer, String>();
            for (var npc : catalog().getNpcs()) {
                if (!npc.name().isBlank()) {
                    read.put(npc.id(), npc.name());
                } else {
                    var appearance = npcgrp().getAppearance(npc.id());
                    read.put(npc.id(), appearance == null ? "NPC " + npc.id() : className(appearance));
                }
            }
            names = read;
        }
        return names;
    }

    private synchronized Map<Integer, NpcSpeed> speedsNow() {
        if (speeds == null) {
            var read = new HashMap<Integer, NpcSpeed>();
            var table = npcgrp();
            for (var id : names().keySet()) {
                var appearance = table.getAppearance(id);
                if (appearance != null && (appearance.walkSpeed() > 0 || appearance.runSpeed() > 0)) {
                    read.put(id, new NpcSpeed(appearance.walkSpeed(), appearance.runSpeed(), false));
                }
            }
            for (var npc : script().getNpcs()) {
                if (npc.walkSpeed() > 0 || npc.runSpeed() > 0) {
                    read.put(npc.id(), new NpcSpeed(npc.walkSpeed(), npc.runSpeed(), true));
                }
            }
            speeds = read;
        }
        return speeds;
    }

    private NpcDataTable script() {
        var folder = scripts == null ? null : SpawnSource.resolveFolder(scripts);
        var file = folder == null ? null : SpawnSource.find(folder, SpawnSource.NPCS);
        if (file == null) {
            return NpcDataTable.empty();
        }
        try {
            return NpcDataTable.read(file);
        } catch (IOException | RuntimeException unreadable) {
            log.warn("{} could not be read: {}", file, unreadable.toString());
            return NpcDataTable.empty();
        }
    }

    private synchronized UnrealLoader loader() {
        if (loader == null) {
            loader = new UnrealLoader(root, new Options(false, 0), true);
            loader.worldVariant(variant);
        }
        return loader;
    }

    private List<String> sounds() {
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        var folder = root.resolve(SOUNDS);
        if (Files.isDirectory(folder)) {
            var packages = new UArchiveLoader(root, List.of(new USearchConfig(SOUNDS, "uax")));
            try (var files = Files.list(folder)) {
                for (var file : files.map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(UAX) && !name.startsWith("._"))
                        .toList()) {
                    var name = file.substring(0, file.length() - UAX.length());
                    try {
                        var archive = packages.loadArchiveIfPresent(name);
                        if (archive != null) {
                            for (var export : archive.getExportMap()) {
                                if ("Sound".equals(export.getClassName())) {
                                    names.add(name + "." + export.getObjectName());
                                }
                            }
                            packages.removeArchive(name);
                        }
                    } catch (RuntimeException unreadable) {
                        log.warn("The sounds of {} could not be listed: {}", name, unreadable.toString());
                    }
                }
            } catch (IOException | UncheckedIOException unreadable) {
                log.warn("{} could not be listed: {}", folder, unreadable.toString());
            }
        }
        return List.copyOf(names);
    }

    private static boolean shows(ClientCatalog catalog, int skill) {
        var cast = catalog.getCast(skill, 1);
        return cast != null && !cast.effect().isEmpty();
    }

    private static @Nullable Integer number(String text) {
        var stripped = text.strip();
        if (stripped.isEmpty() || !stripped.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.valueOf(stripped);
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }

    private static String className(Appearance appearance) {
        var full = appearance.npcClass();
        return full.isBlank() ? "NPC " + appearance.id() : full.substring(full.lastIndexOf('.') + 1);
    }

    private static List<String> files(Path folder, String suffix) {
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (Files.isDirectory(folder)) {
            try (var files = Files.list(folder)) {
                files.map(file -> file.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(OGG))
                        .map(name -> name.substring(0, name.length() - OGG.length()))
                        .map(name -> suffix.isEmpty() ? name : name.replaceFirst(suffix, ""))
                        .forEach(names::add);
            } catch (IOException | UncheckedIOException unreadable) {
                log.warn("{} could not be listed: {}", folder, unreadable.toString());
            }
        }
        return List.copyOf(names);
    }
}

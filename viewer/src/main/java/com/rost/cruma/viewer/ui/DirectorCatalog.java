package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.ClientCatalog.Npc;
import com.rost.cruma.core.npc.ClientIcons;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.PostEffects;
import com.rost.cruma.core.world.SceneOrders.Skills;
import com.rost.cruma.core.world.SoundSpans.Kind;
import com.rost.cruma.core.world.SystemMessages;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.spawn.SpawnModels;
import com.rost.cruma.viewer.ui.SceneCasts.SceneSkill;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class DirectorCatalog implements AutoCloseable {
    private static final int MOST_ROWS = 200;
    private static final String OGG = ".ogg";
    private static final String UAX = ".uax";
    private static final String SOUNDS = "Sounds";
    private static final String PER_PLAYER = "_id";
    private static final String LANGUAGE = "-[a-zA-Z]{1,3}$";
    private static final String SLIDES = "LineageSlideImage";
    private static final String NO_NAME = "No name in the client · ";
    private static final Logger log = LogManager.getLogger(DirectorCatalog.class);

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-director-catalog");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Integer, Float> heights = new ConcurrentHashMap<>();
    private @Nullable Path client;
    private @Nullable WorldVariant variant;
    private boolean asked;
    private volatile Names names = Names.NONE;
    private volatile Media media = Media.NONE;
    private volatile boolean given;
    private @Nullable SpawnModels models;

    public record Row(int id, String name, String note, String search) {
        static Row of(int id, String name, String note) {
            return new Row(id, name, note, (name + " " + note + " " + id).toLowerCase(Locale.ROOT));
        }
    }

    record Names(
            boolean read,
            List<Row> npcs,
            List<Row> skills,
            List<Row> shows,
            List<String> effects,
            Skills table,
            IntFunction<@Nullable String> npcName) {
        static final Names NONE = new Names(false, List.of(), List.of(), List.of(), List.of(), Skills.ANY, _ -> null);
    }

    public record Slide(String name, int width, int height, int partWidth, int partHeight) {
        String shortName() {
            return name.substring(name.lastIndexOf('.') + 1);
        }

        String size() {
            return partWidth + " x " + partHeight;
        }
    }

    public record Media(
            List<Row> tracks,
            List<Row> voices,
            List<Row> sounds,
            List<Slide> slides,
            List<Row> lines,
            List<Row> posts,
            PostEffects table) {
        static final Media NONE =
                new Media(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), PostEffects.getNone());

        @Nullable
        Slide slide(String name) {
            for (var slide : slides) {
                if (slide.name().equalsIgnoreCase(name)) {
                    return slide;
                }
            }
            return null;
        }

        @Nullable
        String line(int number) {
            for (var line : lines) {
                if (line.id() == number) {
                    return line.name();
                }
            }
            return null;
        }

        boolean has(Kind kind, String name) {
            var rows = switch (kind) {
                case TRACK -> tracks;
                case VOICE -> voices;
                case SOUND -> sounds;
            };
            if (rows.isEmpty()) {
                return true;
            }
            var wanted = name.toLowerCase(Locale.ROOT);
            var first = wanted.endsWith(PER_PLAYER)
                    ? wanted.substring(0, wanted.length() - PER_PLAYER.length()) + "_1"
                    : wanted;
            for (var row : rows) {
                if (row.name().equalsIgnoreCase(first)) {
                    return true;
                }
            }
            return false;
        }
    }

    static List<Row> matching(List<Row> rows, String filter) {
        var found = new ArrayList<Row>();
        for (var row : rows) {
            if (filter.isEmpty() || row.search().contains(filter)) {
                found.add(row);
                if (found.size() == MOST_ROWS) {
                    break;
                }
            }
        }
        return found;
    }

    static List<String> matchingNames(List<String> names, String filter) {
        return matchingNames(names, filter, MOST_ROWS);
    }

    static List<String> matchingNames(List<String> names, String filter, int most) {
        var found = new ArrayList<String>();
        for (var name : names) {
            if (filter.isEmpty() || name.toLowerCase(Locale.ROOT).contains(filter)) {
                found.add(name);
                if (found.size() == most) {
                    break;
                }
            }
        }
        return found;
    }

    static List<Row> shows(ClientCatalog catalog, Map<String, Skill> classes) {
        var rows = new ArrayList<Row>();
        for (var skill : catalog.getSkillsAtLevelOne()) {
            var count = effectCount(catalog, skill.id(), classes);
            if (count > 0) {
                rows.add(Row.of(
                        skill.id(), skill.name(), count + (count == 1 ? " effect · " : " effects · ") + skill.id()));
            }
        }
        rows.sort(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Row::id));
        return List.copyOf(rows);
    }

    public static Map<Integer, Integer> effectCounts(ClientCatalog catalog, UnrealLoader loader) {
        var classes = SceneLibrary.byClass(loader.listSkills());
        var counts = new HashMap<Integer, Integer>();
        for (var skill : catalog.getSkillsAtLevelOne()) {
            var count = effectCount(catalog, skill.id(), classes);
            if (count > 0) {
                counts.put(skill.id(), count);
            }
        }
        return counts;
    }

    static Skills skills(ClientCatalog catalog) {
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
                return cast != null && cast.style() != SceneSkill.INSTANT;
            }
        };
    }

    public static List<Row> npcRows(Collection<Npc> npcs, IntFunction<@Nullable String> classes) {
        var named = new ArrayList<Row>(npcs.size());
        var unnamed = new ArrayList<Row>();
        for (var npc : npcs) {
            if (npc.name().isBlank()) {
                unnamed.add(Row.of(npc.id(), shownName(npc, classes), NO_NAME + npc.id()));
            } else {
                named.add(Row.of(npc.id(), npc.name(), npc.title()));
            }
        }
        var order =
                Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Row::id);
        named.sort(order);
        unnamed.sort(order);
        named.addAll(unnamed);
        return List.copyOf(named);
    }

    public static String shownName(Npc npc, IntFunction<@Nullable String> classes) {
        if (!npc.name().isBlank()) {
            return npc.name();
        }
        var named = classes.apply(npc.id());
        return named == null || named.isBlank() ? "NPC " + npc.id() : named;
    }

    public static IntFunction<@Nullable String> npcClasses(Path root, WorldVariant world) {
        try {
            var table = NpcGrpTable.read(root.resolve("system"), world);
            return id -> {
                var appearance = table.getAppearance(id);
                if (appearance == null || appearance.npcClass().isBlank()) {
                    return null;
                }
                var full = appearance.npcClass();
                return full.substring(full.lastIndexOf('.') + 1);
            };
        } catch (IOException | RuntimeException unreadable) {
            log.warn("The classes of the NPCs could not be read: {}", unreadable.toString());
            return _ -> null;
        }
    }

    public static Media media(Path root, WorldVariant world) {
        var table = PostEffects.read(root, world);
        var posts = new ArrayList<Row>(table.size());
        for (var row : table.getRows()) {
            posts.add(Row.of(row.id(), DirectorMedia.postName(row), ""));
        }
        var lines = new ArrayList<Row>();
        for (var line : SystemMessages.read(root, world).getRows()) {
            if (line.id() >= 1) {
                lines.add(Row.of(line.id(), line.text().replace("\\n", " "), ""));
            }
        }
        return new Media(
                rows(names(root.resolve("music"), "")),
                rows(names(root.resolve("Voice"), LANGUAGE)),
                rows(sounds(root)),
                slides(root),
                List.copyOf(lines),
                List.copyOf(posts),
                table);
    }

    void setClient(Path root, WorldVariant world) {
        client = root;
        variant = world;
    }

    void ask() {
        var root = client;
        var world = variant;
        if (asked || root == null || world == null) {
            return;
        }
        asked = true;
        worker.execute(() -> readMedia(root, world));
        worker.execute(() -> read(root, world));
    }

    Names getNames() {
        return names;
    }

    Media getMedia() {
        return media;
    }

    void set(Names next) {
        given = true;
        names = next;
        asked = true;
    }

    void set(Media next) {
        given = true;
        media = next;
        asked = true;
    }

    float getHeight(int npc) {
        var height = heights.get(npc);
        if (height != null) {
            return height;
        }
        heights.put(npc, 0f);
        var root = client;
        var world = variant;
        if (root != null && world != null) {
            worker.execute(() -> heights.put(npc, models(root, world).getCollisionHeight(npc)));
        }
        return 0;
    }

    void read(CharacterForm form) {
        var root = client;
        var world = variant;
        if (root != null && world != null) {
            form.read(models(root, world), root, world, worker);
        }
    }

    @Override
    public void close() {
        if (worker.isShutdown()) {
            return;
        }
        worker.execute(this::letGo);
        worker.shutdown();
    }

    private synchronized SpawnModels models(Path root, WorldVariant world) {
        if (models == null) {
            models = new SpawnModels(root, world);
        }
        return models;
    }

    private synchronized void letGo() {
        if (models != null) {
            models.close();
            models = null;
        }
    }

    private void read(Path root, WorldVariant world) {
        try {
            var catalog = ClientCatalog.read(root, world);
            var classes = npcClasses(root, world);
            var npcs = npcRows(catalog.getNpcs(), classes);
            var skills = new ArrayList<Row>();
            for (var skill : catalog.getSkillsAtLevelOne()) {
                skills.add(Row.of(skill.id(), skill.name(), Integer.toString(skill.id())));
            }
            var named = new Names(
                    true, List.copyOf(npcs), List.copyOf(skills), List.of(), List.of(), skills(catalog), id -> {
                        var npc = catalog.getNpc(id);
                        return npc == null ? null : shownName(npc, classes);
                    });
            publish(named);
            var loader = new UnrealLoader(root, new Options(false, 0), true);
            loader.worldVariant(world);
            try {
                publish(new Names(
                        true,
                        named.npcs(),
                        named.skills(),
                        shows(catalog, SceneLibrary.byClass(loader.listSkills())),
                        effects(loader),
                        named.table(),
                        named.npcName()));
            } finally {
                loader.trimCaches();
            }
        } catch (RuntimeException failure) {
            log.warn("The client's tables could not be read: {}", failure.toString());
        }
    }

    private void readMedia(Path root, WorldVariant world) {
        try {
            publishMedia(media(root, world));
        } catch (RuntimeException failure) {
            log.warn("The client's sounds and slides could not be read: {}", failure.toString());
        }
    }

    private void publish(Names next) {
        if (!given) {
            names = next;
        }
    }

    private void publishMedia(Media next) {
        if (!given) {
            media = next;
        }
    }

    private static int effectCount(ClientCatalog catalog, int skill, Map<String, Skill> classes) {
        var cast = catalog.getCast(skill, 1);
        var effects = cast == null ? null : SceneLibrary.effectsOf(cast, classes);
        return effects == null ? 0 : effects.count();
    }

    private static List<Row> rows(List<String> names) {
        var rows = new ArrayList<Row>(names.size());
        for (var name : names) {
            rows.add(Row.of(rows.size(), name, ""));
        }
        return List.copyOf(rows);
    }

    private static List<String> names(Path folder, String suffix) {
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

    private static List<String> sounds(Path root) {
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        var folder = root.resolve(SOUNDS);
        if (Files.isDirectory(folder)) {
            var loader = new UArchiveLoader(root, List.of(new USearchConfig(SOUNDS, "uax")));
            try (var files = Files.list(folder)) {
                files.map(file -> file.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(UAX))
                        .filter(name -> !name.startsWith("._"))
                        .map(name -> name.substring(0, name.length() - UAX.length()))
                        .forEach(name -> read(loader, name, names));
            } catch (IOException | UncheckedIOException unreadable) {
                log.warn("{} could not be listed: {}", folder, unreadable.toString());
            }
        }
        return List.copyOf(names);
    }

    private static void read(UArchiveLoader loader, String name, Set<String> names) {
        try {
            var archive = loader.loadArchiveIfPresent(name);
            if (archive != null) {
                for (var export : archive.getExportMap()) {
                    if ("Sound".equals(export.getClassName())) {
                        names.add(name + "." + export.getObjectName());
                    }
                }
                loader.removeArchive(name);
            }
        } catch (RuntimeException failure) {
            log.warn("The sounds of {} could not be listed: {}", name, failure.toString());
        }
    }

    private static List<Slide> slides(Path root) {
        var parts = new HashMap<String, int[]>();
        for (var scene : GameScenes.find(root)) {
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
        var slides = new ArrayList<Slide>();
        for (var texture : new ClientIcons(root).loadTextures(SLIDES)) {
            var name = SLIDES + "." + texture.name();
            var part = parts.get(name.toLowerCase(Locale.ROOT));
            slides.add(
                    part == null
                            ? new Slide(name, texture.width(), texture.height(), texture.width(), texture.height())
                            : new Slide(name, texture.width(), texture.height(), part[0], part[1]));
        }
        return List.copyOf(slides);
    }

    private static List<String> effects(UnrealLoader loader) {
        var effects = new ArrayList<String>();
        for (Effect effect : loader.listEffects()) {
            effects.add(effect.name());
        }
        effects.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(effects);
    }
}

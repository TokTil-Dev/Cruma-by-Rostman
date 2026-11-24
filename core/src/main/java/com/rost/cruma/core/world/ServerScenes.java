package com.rost.cruma.core.world;

import com.rost.cruma.core.npc.NpcPch;
import com.rost.cruma.core.npc.NpcSocials;
import com.rost.cruma.core.npc.ServerAi;
import com.rost.cruma.core.npc.ServerAi.Calls;
import com.rost.cruma.core.npc.ServerAi.Slots;
import com.rost.cruma.core.spawn.AiParameters;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.spawn.NpcPosFile;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.core.spawn.SpawnNpc;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnText;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UMover;
import com.rost.cruma.core.unreal.UPackageLoader;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Camera;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.SceneOrders.NpcSpeed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class ServerScenes {
    private static final String AI = "ai.obj";
    private static final String POSITIONS = "npcpos.txt";
    private static final String NUMBERS = "npc_pch.txt";
    private static final String SKILLS = "skilldata.txt";
    private static final String NPCS = "npcdata.txt";
    private static final String CONSTANTS = "manual_pch.txt";
    private static final String SPAWN_ALL = "SPAWN_ALL_INSTANT";
    private static final String TIMER = "TIMER_FIRED_EX";
    private static final String DYING = "MY_DYING";
    private static final String DEATH = "death";
    private static final String CAST_END = "castend";
    private static final double FIGHT = 3600;
    private static final double MILLISECONDS = 1000.0;
    private static final int NPC_OFFSET = 1_000_000;
    private static final int SKILL_SHIFT = 16;
    private static final long SKILL_NUMBER = 0xFFFFFFFFL;
    private static final double NOBODY = -2;
    private static final int SKILL_LEVEL = 0xFFFF;
    private static final long BEFORE_START = 5_000;
    private static final long LONGEST_WAIT = 600_000;
    private static final String AI_PARAMETERS = "ai_parameters";
    private static final String UNDYING = "undying";
    private static final String DOOR_NAMES = "doordata.txt";
    private static final int TILE_DIGITS = 4;
    private static final String NEXT_SOCIAL = "next social";
    private static final String MAKER_EVENT = "ON_SCRIPT_EVENT";
    private static final int AI_FIELDS = 10;
    private static final Pattern GROUND_HIGH = Pattern.compile("(?:^|\\s)ground_high=\\{?([0-9.]+)");
    private static final String RUN_SPEED = "ground_high";
    private static final Pattern DEX = Pattern.compile("(?:^|\\s)dex=(\\d+)");
    private static final String DEX_KEY = "dex";
    private static final String PARAMETERS = "PC_parameter.txt";
    private static final Pattern DEX_TABLE = Pattern.compile("dex_bonus_begin(.*?)dex_bonus_end", Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile("attribute_(\\d+)\\s*=\\s*(-?\\d+)");
    private static final int KNOWN_DEX = 30;
    private static final int KNOWN_BONUS = 10;
    private static final double PER_CENT = 100;
    private static final double FAR = 65_536;
    private static final Pattern HIT_TIME = Pattern.compile("skill_hit_time\\s*=\\s*([0-9.]+)");
    private static final Pattern DOOR = Pattern.compile("door_begin\\s+\\[([^]]+)][^\\n]*?editor_id=(\\d+)");
    private static final Pattern SETTING = Pattern.compile("\\{\\[(\\w+)]=([^}]*)}");
    private static final Logger log = LogManager.getLogger(ServerScenes.class);

    private static final List<Film> FILMS = List.of(
            new Film(
                    "boss_antharas_opening",
                    List.of("antaras_min"),
                    List.of(timer("antaras_min", 1001)),
                    "",
                    "",
                    0,
                    false),
            new Film(
                    "boss_antharas_ending",
                    List.of("antaras_min"),
                    List.of(dies("antaras_min")),
                    "boss_antharas_opening",
                    "",
                    0,
                    false),
            new Film(
                    "boss_antharas_opening",
                    List.of("antaras_max"),
                    List.of(timer("antaras_max", 1001)),
                    "",
                    "",
                    0,
                    false),
            new Film(
                    "boss_antharas_ending",
                    List.of("antaras_max"),
                    List.of(dies("antaras_max")),
                    "boss_antharas_opening",
                    "",
                    0,
                    false),
            new Film(
                    "boss_andreas_opening",
                    List.of("triol_camera", "triolls_priest_andreas"),
                    List.of(tell("SCE_PAGAN_CAMERA_START", "")),
                    "",
                    "",
                    0,
                    true),
            new Film(
                    "boss_baylor_opening",
                    List.of("barler_effect_npc"),
                    List.of(timer("barler_effect_npc", 2001)),
                    "",
                    "",
                    0,
                    true),
            new Film(
                    "boss_baylor_ending",
                    List.of("barler"),
                    List.of(on("barler_private_crystal_golem", "STATIC_OBJECT_CLAN_ATTACKED")),
                    "",
                    "",
                    0,
                    true),
            new Film("boss_beleth_opening", List.of("beres"), List.of(timer("beres", 1000)), "", "", 0, true),
            new Film(
                    "boss_beleth_ending",
                    List.of("beres"),
                    List.of(dies("beres")),
                    "",
                    "ai_boss09_beres_camera04",
                    0,
                    true),
            new Film(
                    "boss_chaos_golem",
                    List.of("dr_chaos_npc", "strange_machin"),
                    List.of(on("dr_chaos_npc", "TALKED")),
                    "",
                    "",
                    0,
                    false),
            new Film(
                    "boss_frintezza_opening",
                    List.of("frintessa"),
                    List.of(timer("frintessa", 1000).holding(3)),
                    "",
                    "",
                    0,
                    true),
            new Film(
                    "boss_frintezza_middle",
                    List.of("frintessa"),
                    List.of(tell("", "SCE_FRINTESSA_SPAWN_DEMON_FINAL")),
                    "boss_frintezza_opening",
                    "",
                    0,
                    true),
            new Film(
                    "boss_frintezza_change",
                    List.of("frintessa"),
                    List.of(timer("follower_of_frintessa", 2000)),
                    "boss_frintezza_middle",
                    "",
                    0,
                    true),
            new Film(
                    "boss_frintezza_ending",
                    List.of("frintessa"),
                    List.of(dies("follower_of_frintessa_tr")),
                    "boss_frintezza_change",
                    "",
                    0,
                    true),
            new Film("boss_ratana_opening", List.of("ratana"), List.of(on("ratana", "SEE_CREATURE")), "", "", 0, false),
            new Film("boss_ratana_attacked", List.of("ratana"), List.of(on("ratana", "ATTACKED")), "", "", 0, false),
            new Film("boss_ratana_ending", List.of("ratana"), List.of(dies("ratana")), "", "", 0, false),
            new Film(
                    "boss_sailren_opening",
                    List.of("sailren_dummy", "sailren"),
                    List.of(timer("sailren_dummy", 3001), tell("SCE_TELEPORT_SAILEN", "")),
                    "",
                    "",
                    0,
                    false),
            new Film("boss_valakas_opening", List.of("valakas"), List.of(timer("valakas", 1001)), "", "", 0, false),
            new Film("boss_valakas_ending", List.of("valakas"), List.of(dies("valakas")), "", "", 1001, false),
            new Film(
                    "boss_valakas_lite_opening",
                    List.of("valakas_lite"),
                    List.of(timer("valakas_lite", 1001)),
                    "",
                    "",
                    0,
                    false),
            new Film(
                    "boss_valakas_lite_ending",
                    List.of("valakas_lite"),
                    List.of(dies("valakas_lite")),
                    "",
                    "",
                    1001,
                    false),
            new Film(
                    "boss_frintezza_hard_opening",
                    List.of("ai_frintessa_seeker_ver2"),
                    List.of(timer("ai_frintessa_seeker_ver2", 1713002)),
                    "",
                    "",
                    0,
                    true),
            new Film(
                    "boss_frintezza_hard_change",
                    List.of("ai_frintessa_seeker_ver2"),
                    List.of(timer("scarlet_van_halisha", 26351004)),
                    "boss_frintezza_hard_opening",
                    "",
                    0,
                    true),
            new Film(
                    "boss_frintezza_hard_ending",
                    List.of("ai_frintessa_seeker_ver2"),
                    List.of(dies("scarlet_van_halisha_tr")),
                    "boss_frintezza_hard_change",
                    "",
                    0,
                    true),
            new Film(
                    "boss_freya_ending",
                    List.of("ep_fr_freya"),
                    List.of(on("ep_fr_freya", "ATTACKED")),
                    "",
                    "",
                    0,
                    true));

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private ServerScenes() {}

    private record Start(
            String ai, String event, int timer, String first, String second, long delay, boolean dies, int dbValue) {
        Start(String ai, String event, int timer, String first, String second, long delay, boolean dies) {
            this(ai, event, timer, first, second, delay, dies, -1);
        }

        Start holding(int value) {
            return new Start(ai, event, timer, first, second, delay, dies, value);
        }
    }

    private record Film(
            String name,
            List<String> npcs,
            List<Start> starts,
            String after,
            String standsAt,
            int placedBy,
            boolean closed) {}

    private record Cached(long stamp, List<Scene> scenes) {}

    interface Socials {
        @Nullable
        String getSequence(int npc, int social);

        default String getDeath(int npc) {
            return DEATH;
        }

        default @Nullable String getCasting(int npc) {
            return null;
        }
    }

    interface Doors {
        @Nullable
        Door getDoor(int id);
    }

    record Door(String actor, double x, double y, double z, double seconds) {}

    public static List<Scene> find(@Nullable Path folder, Path client, WorldVariant variant) {
        var scripts = scripts(folder);
        if (scripts == null) {
            return List.of();
        }
        var key = scripts.toAbsolutePath()
                .normalize()
                .resolve(client.toAbsolutePath().normalize().toString().replace('/', '_'));
        try {
            var stamp = Files.getLastModifiedTime(scripts.resolve(AI)).toMillis();
            var cached = CACHE.get(key);
            if (cached != null && cached.stamp() == stamp) {
                return cached.scenes();
            }
            var scenes = read(scripts, clientSocials(client, variant), clientDoors(client));
            CACHE.put(key, new Cached(stamp, scenes));
            return scenes;
        } catch (IOException | RuntimeException failure) {
            log.warn("The boss scenes of {} could not be read: {}", scripts, failure.toString());
            return List.of();
        }
    }

    public static boolean isClosedHall(Scene scene) {
        var film = film(scene.source());
        return film != null && film.closed();
    }

    static List<Scene> find(@Nullable Path folder, Socials socials, Doors doors) throws IOException {
        var scripts = scripts(folder);
        return scripts == null ? List.of() : read(scripts, socials, doors);
    }

    static List<Scene> find(@Nullable Path folder, Socials socials) throws IOException {
        return find(folder, socials, _ -> null);
    }

    static Map<Integer, Double> moveRates(String parameters) {
        var table = DEX_TABLE.matcher(parameters);
        if (!table.find()) {
            return Map.of();
        }
        var bonuses = new HashMap<Integer, Integer>();
        var entry = ATTRIBUTE.matcher(table.group(1));
        while (entry.find()) {
            bonuses.put(Integer.valueOf(entry.group(1)), Integer.valueOf(entry.group(2)));
        }
        if (!Integer.valueOf(KNOWN_BONUS).equals(bonuses.get(KNOWN_DEX))) {
            return Map.of();
        }
        var rates = new HashMap<Integer, Double>();
        bonuses.forEach((dex, bonus) -> rates.put(dex, 1 + bonus / PER_CENT));
        return Map.copyOf(rates);
    }

    private static final class Skills {
        private final Path file;
        private final Map<Integer, Long> known = new HashMap<>();
        private @Nullable String text;

        private Skills(Path file) {
            this.file = file;
        }

        private long hit(int skill) {
            return known.computeIfAbsent(skill, this::read);
        }

        private @Nullable Double number(String name) {
            var found = Pattern.compile("skill_name\\s*=\\s*\\[" + Pattern.quote(name)
                            + "].*?skill_id\\s*=\\s*(\\d+)\\s+level\\s*=\\s*(\\d+)")
                    .matcher(text());
            return found.find()
                    ? (double) (Integer.parseInt(found.group(1)) << SKILL_SHIFT | Integer.parseInt(found.group(2)))
                    : null;
        }

        private long read(int skill) {
            var key = Pattern.compile("skill_id\\s*=\\s*"
                    + (skill >>> SKILL_SHIFT)
                    + "\\s+level\\s*=\\s*"
                    + (skill & SKILL_LEVEL)
                    + "\\s");
            var found = key.matcher(text());
            if (!found.find()) {
                return -1;
            }
            var all = text();
            var end = all.indexOf('\n', found.end());
            var time = HIT_TIME.matcher(all.substring(found.end(), end < 0 ? all.length() : end));
            return time.find() ? Math.round(Double.parseDouble(time.group(1)) * 1000) : -1;
        }

        private String text() {
            if (text == null) {
                text = textOf(file);
            }
            return text;
        }
    }

    private static final class NpcSettings {
        private final Path file;
        private final @Nullable Path parameters;
        private final Skills skills;
        private final Map<Integer, Map<String, Object>> known = new HashMap<>();
        private @Nullable String text;
        private @Nullable Map<Integer, Double> rates;

        private NpcSettings(Path file, @Nullable Path parameters, Skills skills) {
            this.file = file;
            this.parameters = parameters;
            this.skills = skills;
        }

        private Map<String, Object> of(int npc) {
            return known.computeIfAbsent(npc, this::read);
        }

        private double moveRate(Map<String, Object> settings) {
            if (rates == null) {
                rates = parameters == null ? Map.of() : moveRates(textOf(parameters));
            }
            return settings.get(DEX_KEY) instanceof Double dex ? rates.getOrDefault((int) Math.round(dex), 0.0) : 0;
        }

        private Map<String, Object> read(int npc) {
            if (text == null) {
                text = textOf(file);
            }
            var found = Pattern.compile("npc_begin\\s+\\S+\\s+" + npc + "\\s").matcher(text);
            if (!found.find()) {
                return Map.of();
            }
            var end = text.indexOf('\n', found.end());
            var line = text.substring(found.end(), end < 0 ? text.length() : end);
            var settings = new HashMap<String, Object>();
            if (line.contains(UNDYING + "=1")) {
                settings.put(UNDYING, 1.0);
            }
            var run = GROUND_HIGH.matcher(line);
            if (run.find()) {
                settings.put(RUN_SPEED, Double.parseDouble(run.group(1)));
            }
            var dex = DEX.matcher(line);
            if (dex.find()) {
                settings.put(DEX_KEY, Double.parseDouble(dex.group(1)));
            }
            var start = line.indexOf("npc_ai=");
            var setting = SETTING.matcher(start < 0 ? "" : line.substring(start));
            while (setting.find()) {
                var value = value(setting.group(2));
                if (value != null) {
                    settings.put(setting.group(1), value);
                }
            }
            return settings;
        }

        private @Nullable Object value(String written) {
            if (written.startsWith("@")) {
                return skills.number(written.substring(1));
            }
            try {
                return Double.valueOf(written);
            } catch (NumberFormatException noNumber) {
                return null;
            }
        }
    }

    private static final class Body {
        private final int index;
        private final int npc;
        private final String ai;
        private final Map<String, Object> settings = new HashMap<>();
        private final Slots self = new Slots();
        private final Slots myself = new Slots().set("sm", self);
        private double x;
        private double y;
        private double z;
        private int heading;
        private long castAt = Long.MIN_VALUE;
        private long castEnd = Long.MIN_VALUE;
        private long socialAt = Long.MIN_VALUE;
        private long socialTime;
        private @Nullable String social;
        private boolean shown;
        private boolean hidden;
        private boolean gone;
        private boolean appears;
        private boolean seen;

        private Body(int index, int npc, String ai, Map<String, Object> settings) {
            this.index = index;
            this.npc = npc;
            this.ai = ai;
            this.settings.putAll(settings);
            self.set("id", (double) index);
            self.set("class_id", (double) NPC_OFFSET + npc);
            self.set("is_pc", 0.0);
            self.set("db_value", 0.0);
            self.set("alive", 1.0);
            for (var i = 0; i < AI_FIELDS; i++) {
                myself.set("i_ai" + i, 0.0);
                myself.set("i_quest" + i, 0.0);
            }
        }

        private void setAll(Map<String, Object> more) {
            settings.putAll(more);
        }

        private void place(double newX, double newY, double newZ) {
            x = newX;
            y = newY;
            z = newZ;
            self.set("x", x);
            self.set("y", y);
            self.set("z", z);
        }

        private double distance(Body other) {
            var dx = x - other.x;
            var dy = y - other.y;
            var dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private record Cue(long at, long order, Body body, String event, Slots frame) {}

    private static final class Stage {
        private final Film film;
        private final ServerAi ai;
        private final NpcPosFile positions;
        private final NpcPch numbers;
        private final NpcPch constants;
        private final Skills skills;
        private final NpcSettings settings;
        private final Socials socials;
        private final Function<String, @Nullable Door> doors;
        private final Map<String, Actor> doorOrders = new LinkedHashMap<>();
        private final List<Body> bodies = new ArrayList<>();
        private final Map<Slots, Body> byCreature = new IdentityHashMap<>();
        private final Map<Integer, Object> globals = new HashMap<>();
        private final Set<String> filled = new HashSet<>();
        private final Map<String, Slots> makers = new HashMap<>();
        private final PriorityQueue<Cue> cues =
                new PriorityQueue<>(Comparator.comparingLong(Cue::at).thenComparingLong(Cue::order));
        private final List<Moment> moments = new ArrayList<>();
        private final Map<Actor, Long> castEnds = new IdentityHashMap<>();
        private @Nullable Body starter;
        private @Nullable Body dying;
        private long cueCount;
        private long now = -BEFORE_START;
        private long firstShot = -1;
        private int opening;
        private long began;
        private long end;
        private double stageX;
        private double stageY;
        private boolean wide;

        private Stage(
                Film film,
                ServerAi ai,
                NpcPosFile positions,
                NpcPch numbers,
                NpcPch constants,
                Skills skills,
                NpcSettings settings,
                Socials socials,
                Function<String, @Nullable Door> doors) {
            this.film = film;
            this.ai = ai;
            this.positions = positions;
            this.numbers = numbers;
            this.constants = constants;
            this.skills = skills;
            this.settings = settings;
            this.socials = socials;
            this.doors = doors;
        }

        private record Moment(
                long at,
                Camera shot,
                List<Actor> orders,
                @Nullable Music music) {}

        private @Nullable Scene shoot() {
            for (var name : cast(film)) {
                var maker = maker(positions, name);
                if (maker == null) {
                    return null;
                }
                if (filled.add(maker.name())) {
                    enterAll(maker, false);
                }
            }
            while (!cues.isEmpty() && cues.peek().at() < 0) {
                run(cues.poll());
            }
            now = 0;
            return play(film) && firstShot >= 0 ? new Scene(film.name(), items()) : null;
        }

        private List<String> cast(Film of) {
            var names = new ArrayList<>(of.npcs());
            var first = before(of);
            if (first != null) {
                names.addAll(cast(first));
            }
            return names;
        }

        private boolean play(Film current) {
            var first = before(current);
            if (first != null) {
                if (!play(first)) {
                    return false;
                }
                forget();
            }
            began = now;
            for (var start : current.starts()) {
                if (!start(current, start)) {
                    return false;
                }
            }
            while (!cues.isEmpty() && cues.peek().at() <= (firstShot < 0 ? began + LONGEST_WAIT : end)) {
                run(cues.poll());
            }
            return true;
        }

        private void forget() {
            var last = lastPlays();
            moments.clear();
            cues.clear();
            firstShot = -1;
            opening = 0;
            wide = false;
            now = Math.max(now, end);
            end = now;
            for (var body : bodies) {
                var casting = body.castEnd > now;
                body.castEnd = Long.MIN_VALUE;
                if (body.shown && !body.gone) {
                    note(SceneFields.ACTOR.with(spawned(body, true), "fights", true));
                    var state = left(body, last.get(body.index), casting);
                    if (state != null) {
                        note(state);
                    }
                }
            }
            for (var order : doorOrders.values()) {
                moments.add(new Moment(now, bars(wide), List.of(order), null));
            }
        }

        private Map<Integer, Actor> lastPlays() {
            var last = new HashMap<Integer, Actor>();
            for (var moment : moments) {
                for (var order : moment.orders()) {
                    switch (order.action()) {
                        case ANIMATION, SKILL, MOVE, TELEPORT -> last.put(order.index(), order);
                        case SPAWN -> {
                            if (!order.present()) {
                                last.put(order.index(), order);
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
            return last;
        }

        private @Nullable Actor left(Body body, @Nullable Actor last, boolean casting) {
            if (last == null) {
                return null;
            }
            if (last.action() == Action.SKILL && casting) {
                var loop = socials.getCasting(body.npc);
                return loop == null
                        ? null
                        : carried(body, loop, true, 0, "The cast that the fight before this film keeps it in");
            }
            if (last.action() == Action.ANIMATION && !last.dies()) {
                return last.loop()
                        ? carried(body, last.animation(), true, 0, last.description())
                        : carried(body, last.animation(), false, FIGHT, "Where the film before left it");
            }
            return null;
        }

        private boolean start(Film current, Start start) {
            var frame = new Slots()
                    .set("timer_id", (double) start.timer())
                    .set("script_event_arg1", constant(start.first()))
                    .set("script_event_arg2", constant(start.second()))
                    .set("creature", player())
                    .set("attacker", player())
                    .set("talker", player());
            if (start.ai().isEmpty()) {
                for (var body : bodies) {
                    cue(now + start.delay(), body, start.event(), frame);
                }
                return !bodies.isEmpty();
            }
            var body = bodies.stream()
                    .filter(candidate -> candidate.ai.equals(start.ai()))
                    .findFirst()
                    .orElse(null);
            if (body == null) {
                return false;
            }
            if (start.dbValue() >= 0) {
                body.self.set("db_value", (double) start.dbValue());
            }
            if (starter == null) {
                starter = body;
                if (current.placedBy() != 0) {
                    placeStarter(starter, current.placedBy());
                }
            }
            if (start.dies() && !body.settings.containsKey(UNDYING)) {
                dying = body;
                if (body.shown) {
                    note(dying(body, socials.getDeath(body.npc)));
                }
            }
            if (start.dies() && current.standsAt().isEmpty()) {
                show(body);
            }
            cue(now + start.delay(), body, start.event(), frame);
            return true;
        }

        private @Nullable Double constant(String name) {
            if (name.isEmpty()) {
                return 0.0;
            }
            var value = constants.getNumber(name);
            return value == null ? null : Double.valueOf(value);
        }

        private void enterAll(SpawnMaker from, boolean shown) {
            for (var entry : from.npcs()) {
                enterOne(entry, shown);
            }
        }

        private void enterOne(SpawnNpc entry, boolean shown) {
            var number = numbers.getNumber(entry.npcName());
            if (number != null && entry.position() instanceof Fixed place) {
                var aiClass = entry.ai().isEmpty() ? entry.npcName() : entry.ai();
                var body = enter(number.intValue(), aiClass, place.x(), place.y(), place.z(), place.heading());
                body.setAll(parameters(entry));
                if (shown) {
                    body.appears = firstShot >= 0;
                    show(body);
                }
            }
        }

        private Map<String, Object> parameters(SpawnNpc entry) {
            var parameters = new HashMap<String, Object>();
            for (var setting : entry.extra()) {
                if (setting.startsWith(AI_PARAMETERS)) {
                    AiParameters.parse(setting.substring(setting.indexOf('=') + 1))
                            .forEach((name, written) -> {
                                var text = written.replaceAll("[\\[\\]]", "");
                                var named = text.startsWith("@") ? numbers.getNumber(text.substring(1)) : null;
                                if (named != null) {
                                    parameters.put(name, named.doubleValue());
                                } else {
                                    parameters.put(name, text.matches("-?\\d+") ? (Object) Double.valueOf(text) : text);
                                }
                            });
                }
            }
            return parameters;
        }

        private void placeStarter(Body body, int timer) {
            ai.run(
                    body.ai,
                    TIMER,
                    new Slots().set("timer_id", (double) timer).set("myself", body.myself),
                    (function, arguments) -> {
                        if (function.startsWith("InstantTeleport") && arguments.size() > 3) {
                            body.place(number(arguments.get(1)), number(arguments.get(2)), number(arguments.get(3)));
                        } else if (function.equals("AddMoveToDesire")) {
                            body.place(number(arguments.get(0)), number(arguments.get(1)), number(arguments.get(2)));
                        }
                        return null;
                    });
        }

        private List<Item> items() {
            var items = new ArrayList<Item>();
            var away = new HashSet<Integer>();
            var seen = new HashSet<Integer>();
            for (var moment : moments) {
                var orders = new ArrayList<Actor>();
                var early = Math.max(0, firstShot - moment.at()) / MILLISECONDS;
                for (var order : moment.orders()) {
                    var index = order.index();
                    if (order.action().enters()) {
                        if (Math.hypot(order.x() - stageX, order.y() - stageY) > FAR) {
                            if (away.add(index) && seen.contains(index)) {
                                orders.add(new Actor(
                                        false,
                                        Action.DESPAWN,
                                        order.npc(),
                                        index,
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        "",
                                        false,
                                        false,
                                        false,
                                        false,
                                        0,
                                        0,
                                        false,
                                        false,
                                        0,
                                        0,
                                        List.of()));
                            }
                            continue;
                        }
                        away.remove(index);
                        seen.add(index);
                    }
                    if (order.action() == Action.SKILL && castEnds.getOrDefault(order, Long.MAX_VALUE) < began) {
                        continue;
                    }
                    if (!away.contains(index)) {
                        var started = order.action() == Action.ANIMATION
                                || order.action() == Action.SPAWN && !order.present();
                        orders.add(
                                started && early > order.since()
                                        ? SceneFields.ACTOR.with(order, "since", early)
                                        : order);
                    }
                }
                items.add(new Item(
                        Math.max(0, moment.at() - firstShot) / 1000.0,
                        "",
                        moment.shot(),
                        orders,
                        null,
                        moment.music()));
            }
            items.add(new Item((end - firstShot) / 1000.0, "", bars(false), List.of(), null, null));
            return items;
        }

        private void run(Cue cue) {
            var body = cue.body();
            if (cue.event().equals(NEXT_SOCIAL)) {
                now = cue.at();
                note(social(body, String.valueOf(cue.frame().get("sequence"))));
            } else if (!body.gone) {
                now = cue.at();
                ai.run(body.ai, cue.event(), cue.frame().set("myself", body.myself), new Calls() {
                    @Override
                    public @Nullable Object call(String function, List<@Nullable Object> arguments) {
                        return Stage.this.call(body, function, arguments);
                    }

                    @Override
                    public @Nullable Object getParameter(String name) {
                        return body.settings.get(name);
                    }
                });
            }
        }

        private void cue(long at, Body body, String event, Slots frame) {
            cues.add(new Cue(at, cueCount++, body, event, frame));
        }

        private Body enter(int number, String aiClass, double x, double y, double z, int heading) {
            var npc = number % NPC_OFFSET;
            var body = new Body(bodies.size() + 1, npc, aiClass, settings.of(npc));
            body.place(x, y, z);
            body.myself.set("start_x", x).set("start_y", y).set("start_z", z);
            body.heading = heading;
            bodies.add(body);
            byCreature.put(body.self, body);
            cue(now, body, "CREATED", new Slots());
            return body;
        }

        private Actor social(Body body, String sequence) {
            var order = act(Action.ANIMATION, body, 0, 0, 0, 0, 0, 0, sequence);
            var rate = settings.moveRate(body.settings);
            return rate > 0 ? SceneFields.ACTOR.with(order, "speed", rate) : order;
        }

        private void show(Body body) {
            if (!body.shown && !body.hidden) {
                body.shown = true;
                var order = spawned(body, !body.appears);
                if (body.appears || body.seen || firstShot < 0) {
                    note(order);
                } else {
                    moments.add(opening++, new Moment(firstShot, bars(false), List.of(order), null));
                }
                body.seen = true;
                if (body == dying) {
                    note(dying(body, socials.getDeath(body.npc)));
                }
            }
        }

        private void note(Actor order) {
            moments.add(new Moment(now, bars(wide), List.of(order), null));
        }

        private @Nullable Object call(Body body, String function, List<@Nullable Object> arguments) {
            switch (function) {
                case "AddTimerEx" ->
                    cue(now + whole(arguments.get(1)), body, TIMER, new Slots().set("timer_id", arguments.get(0)));
                case "BroadcastScriptEvent" ->
                    broadcast(body, arguments.get(0), arguments.get(1), number(arguments.get(2)));
                case "BroadcastScriptEventEx", "BroadcastScriptEventCond" ->
                    broadcast(body, arguments.get(0), arguments.get(1), number(arguments.get(3)));
                case "SendScriptEvent", "SendScriptEventEx" -> send(arguments);
                case "SpecialCamera", "SpecialCamera3" -> shoot(arguments);
                case "EffectMusic" ->
                    moments.add(new Moment(
                            now,
                            bars(wide),
                            List.of(),
                            new Music(String.valueOf(arguments.get(2)), true, false, false, false, false, 1, 0)));
                case "CreateOnePrivateEx", "CreateOnePrivateInzoneEx" -> spawn(body, arguments);
                case "InstantTeleportEx" -> teleport(arguments, whole(arguments.get(4)));
                case "InstantTeleport" -> teleport(arguments, null);
                case "AddMoveToDesire" -> move(body, arguments);
                case "AddEffectActionDesire", "AddEffectActionDesire2" -> social(arguments);
                case "AddUseSkillDesire" -> skill(body, arguments);
                case "Castle_GateOpenClose2", "Castle_GateOpenCloseEx" ->
                    door(String.valueOf(arguments.get(0)), whole(arguments.get(1)) == 0);
                case "SetVisible" -> visible(body, whole(arguments.get(0)) > 0);
                case "Despawn" -> leave(body, act(Action.DESPAWN, body, 0, 0, 0, 0, 0, 0, ""));
                case "Suicide" -> leave(body, dying(body, socials.getDeath(body.npc)));
                case "SetDBValue" -> {
                    if (arguments.get(0) instanceof Slots creature) {
                        creature.set("db_value", arguments.get(1));
                    }
                }
                case "RegisterGlobalMap" -> globals.put(whole(arguments.get(0)), arguments.get(1));
                case "UnregisterGlobalMap" -> globals.remove(whole(arguments.get(0)));
                case "GetGlobalMap" -> {
                    return globals.getOrDefault(whole(arguments.get(0)), -1.0);
                }
                case "GetIndexFromCreature" -> {
                    var creature = byCreature.get(arguments.get(0));
                    return creature == null ? null : (double) creature.index;
                }
                case "GetCreatureFromID", "GetCreatureFromIndex" -> {
                    return creature(arguments.get(0));
                }
                case "IsNull", "IsNullCreature" -> {
                    return arguments.get(0) instanceof Slots ? 0.0 : 1.0;
                }
                case "GetNpcMaker" -> {
                    return makerNamed(String.valueOf(arguments.get(0))) == null
                            ? null
                            : new Slots().set("maker", arguments.get(0));
                }
                case "InstantZone_GetNpcMaker" -> {
                    return makerNamed(String.valueOf(arguments.get(1))) == null
                            ? null
                            : new Slots().set("maker", arguments.get(1));
                }
                case "SendMakerScriptEvent" -> makerEvent(arguments);
                case "GetCurrentTick" -> {
                    return now / 1000.0;
                }
                case "GetTick" -> {
                    return (double) now;
                }
                case "GetParty", "GetPartyFromID" -> {
                    return new Slots().set("id", NOBODY).set("member_count", 0.0);
                }
                case "IsNullParty" -> {
                    return arguments.get(0) instanceof Slots ? 0.0 : 1.0;
                }
                case "GetLeaderOfParty" -> {
                    return player();
                }
                case "IsMyBossAlive" -> {
                    var boss = byCreature.get(body.myself.get("boss"));
                    return boss != null && !boss.gone && boss != dying ? 1.0 : 0.0;
                }
                case "LookNeighbor" -> look(body, number(arguments.get(0)));
                case "DistFromMe" -> {
                    var other = byCreature.get(arguments.get(0));
                    return other == null ? 0.0 : body.distance(other);
                }
                case "GetMemoState" -> {
                    return 0.0;
                }
                case "FloatToInt" -> {
                    return arguments.get(0);
                }
                default -> {
                }
            }
            return null;
        }

        private @Nullable SpawnMaker makerNamed(String name) {
            for (var candidate : positions.getMakers()) {
                if (candidate.name().equals(name)) {
                    return candidate;
                }
            }
            return null;
        }

        private void makerEvent(List<@Nullable Object> arguments) {
            var maker = arguments.get(0) instanceof Slots named ? makerNamed(String.valueOf(named.get("maker"))) : null;
            if (maker == null) {
                return;
            }
            var spawn = constants.getNumber(SPAWN_ALL);
            if (spawn != null && spawn == whole(arguments.get(1))) {
                if (filled.add(maker.name())) {
                    enterAll(maker, true);
                }
            } else if (ai.handles(maker.ai(), MAKER_EVENT)) {
                var frame = new Slots()
                        .set("myself", makers.computeIfAbsent(maker.name(), _ -> new Slots()))
                        .set("script_event_arg1", arguments.get(1))
                        .set("script_event_arg2", arguments.get(2))
                        .set("script_event_arg3", arguments.get(3));
                var defined = new int[] {-1};
                ai.run(maker.ai(), MAKER_EVENT, frame, (function, passed) -> {
                    switch (function) {
                        case "SendScriptEvent" -> send(passed);
                        case "GetCreatureFromID" -> {
                            return creature(passed.get(0));
                        }
                        case "GetSpawnDefine" -> {
                            defined[0] = whole(passed.get(0));
                            return new Slots().set("total", 1.0);
                        }
                        case "IsNull" -> {
                            return passed.get(0) instanceof Slots ? 0.0 : 1.0;
                        }
                        case "AtomicIncreaseTotal" -> {
                            return 1.0;
                        }
                        case "Spawn2" -> {
                            if (defined[0] >= 0 && defined[0] < maker.npcs().size()) {
                                enterOne(maker.npcs().get(defined[0]), true);
                            }
                        }
                        default -> {
                        }
                    }
                    return null;
                });
            }
        }

        private @Nullable Slots creature(@Nullable Object id) {
            var index = whole(id);
            return index >= 1 && index <= bodies.size() ? bodies.get(index - 1).self : null;
        }

        private void broadcast(Body sender, @Nullable Object first, @Nullable Object second, double range) {
            for (var body : bodies) {
                if (body != sender && !body.gone && body.distance(sender) <= range) {
                    event(body, first, second);
                }
            }
        }

        private void send(List<@Nullable Object> arguments) {
            var target = byCreature.get(arguments.get(0));
            if (target != null) {
                event(target, arguments.get(1), arguments.get(2));
            }
        }

        private void event(Body body, @Nullable Object first, @Nullable Object second) {
            cue(
                    now,
                    body,
                    "SCRIPT_EVENT",
                    new Slots().set("script_event_arg1", first).set("script_event_arg2", second));
        }

        private void shoot(List<@Nullable Object> arguments) {
            var target = byCreature.get(arguments.get(0));
            if (target == null) {
                return;
            }
            show(target);
            wide = whole(arguments.get(9)) > 0;
            var hold = Math.abs(whole(arguments.get(6)));
            moments.add(new Moment(
                    now,
                    new Camera(
                            true,
                            target.index,
                            number(arguments.get(1)),
                            whole(arguments.get(2)),
                            whole(arguments.get(3)),
                            number(arguments.get(4)) / 1000,
                            hold / 1000.0,
                            whole(arguments.get(7)),
                            whole(arguments.get(8)),
                            0,
                            wide,
                            whole(arguments.get(10)) > 0,
                            whole(arguments.get(11)) > 0,
                            0),
                    List.of(),
                    null));
            if (firstShot < 0) {
                firstShot = now;
                opening = moments.size() - 1;
                stageX = target.x;
                stageY = target.y;
            }
            end = now + hold;
        }

        private void spawn(Body parent, List<@Nullable Object> arguments) {
            var aiClass = String.valueOf(arguments.get(1));
            var x = number(arguments.get(4));
            var y = number(arguments.get(5));
            var z = number(arguments.get(6));
            var body = enter(whole(arguments.get(0)), aiClass, x, y, z, whole(arguments.get(7)));
            body.myself.set("boss", parent.self);
            body.self.set("param1", arguments.get(8));
            body.self.set("param2", arguments.get(9));
            body.self.set("param3", arguments.get(10));
            body.appears = firstShot >= 0;
            show(body);
            if (aiClass.equals(film.standsAt()) && starter != null) {
                starter.place(x, y, z);
                starter.seen = true;
                show(starter);
            }
        }

        private void teleport(List<@Nullable Object> arguments, @Nullable Integer heading) {
            var body = byCreature.get(arguments.get(0));
            if (body == null) {
                return;
            }
            if (heading != null) {
                body.heading = heading;
            }
            teleport(body, number(arguments.get(1)), number(arguments.get(2)), number(arguments.get(3)));
        }

        private void teleport(Body body, double x, double y, double z) {
            var arrives = Math.hypot(x - body.x, y - body.y) > FAR;
            body.place(x, y, z);
            if (!body.hidden && now >= 0) {
                body.shown = true;
                body.seen = true;
                var order = act(Action.TELEPORT, body, 0, x, y, z, body.heading, 0, "");
                note(arrives ? arrival(order) : order);
            }
        }

        private void move(Body body, List<@Nullable Object> arguments) {
            var x = number(arguments.get(0));
            var y = number(arguments.get(1));
            var z = number(arguments.get(2));
            var order = new Actor(
                    false,
                    Action.MOVE,
                    body.npc,
                    body.index,
                    0,
                    x,
                    y,
                    z,
                    0,
                    0,
                    "",
                    false,
                    true,
                    false,
                    false,
                    0,
                    0,
                    false,
                    false,
                    0,
                    0,
                    List.of());
            var run = body.settings.get(RUN_SPEED) instanceof Double speed ? speed : 0;
            var rate = settings.moveRate(body.settings);
            if (rate > 0) {
                order = SceneFields.ACTOR.with(
                        order, "speed", SceneOrders.speed(order, body.npc, _ -> new NpcSpeed(0, run, true)) * rate);
            }
            var seconds = Math.hypot(x - body.x, y - body.y)
                    / SceneOrders.speed(order, body.npc, _ -> new NpcSpeed(0, run, true));
            if (firstShot < 0) {
                teleport(body, x, y, z);
            } else {
                show(body);
                moments.add(new Moment(now, bars(wide), List.of(order), null));
                body.place(x, y, z);
            }
            cue(
                    now + Math.round(seconds * 1000),
                    body,
                    "MOVE_TO_FINISHED",
                    new Slots().set("x", x).set("y", y).set("z", z));
        }

        private void look(Body body, double range) {
            for (var other : bodies) {
                if (other != body && !other.gone && body.distance(other) <= range) {
                    cue(now, body, "SEE_CREATURE", new Slots().set("creature", other.self));
                }
            }
        }

        private void door(String name, boolean opens) {
            var door = doors.apply(name);
            if (door != null) {
                var way = firstShot < 0 ? 0 : (int) Math.round(door.seconds() * 1000);
                var order = new Actor(
                        false,
                        Action.DOOR,
                        0,
                        0,
                        0,
                        door.x(),
                        door.y(),
                        door.z(),
                        0,
                        way,
                        door.actor(),
                        false,
                        opens,
                        false,
                        false,
                        0,
                        0,
                        false,
                        false,
                        0,
                        0,
                        List.of());
                moments.add(new Moment(now, bars(wide), List.of(order), null));
                doorOrders.put(
                        door.actor(),
                        new Actor(
                                false,
                                Action.DOOR,
                                0,
                                0,
                                0,
                                door.x(),
                                door.y(),
                                door.z(),
                                0,
                                0,
                                door.actor(),
                                false,
                                opens,
                                false,
                                false,
                                0,
                                0,
                                false,
                                false,
                                0,
                                0,
                                List.of()));
            }
        }

        private void social(List<@Nullable Object> arguments) {
            var body = byCreature.get(arguments.get(0));
            if (body != null) {
                show(body);
                var sequence = socials.getSequence(body.npc, whole(arguments.get(1)));
                if (sequence != null && body.socialAt != now) {
                    body.socialAt = now;
                    body.social = sequence;
                    body.socialTime = whole(arguments.get(2));
                    note(social(body, sequence));
                } else if (sequence != null && !sequence.equals(body.social)) {
                    cue(now + body.socialTime, body, NEXT_SOCIAL, new Slots().set("sequence", sequence));
                }
            }
        }

        private void skill(Body caster, List<@Nullable Object> arguments) {
            var target = byCreature.get(arguments.get(0));
            var raw = (long) number(arguments.get(1));
            var skill = (int) (raw > SKILL_NUMBER ? raw >>> SKILL_SHIFT : raw);
            if (target != null && skill != 0 && caster.castAt != now && now >= caster.castEnd) {
                caster.castAt = now;
                show(caster);
                show(target);
                var order = act(Action.SKILL, caster, target.index, 0, 0, 0, 0, skill >>> SKILL_SHIFT, "");
                note(order);
                var hit = skills.hit(skill);
                if (hit >= 0) {
                    castEnds.put(order, now + hit);
                    caster.castEnd = now + hit;
                    cue(now + hit, caster, "USE_SKILL_FINISHED", new Slots().set("skill_name_id", (double) raw));
                }
            }
        }

        private void visible(Body body, boolean visible) {
            body.hidden = !visible;
            body.seen = true;
            if (visible) {
                show(body);
            } else if (body.shown) {
                body.shown = false;
                note(act(Action.DESPAWN, body, 0, 0, 0, 0, 0, 0, ""));
            }
        }

        private void leave(Body body, Actor order) {
            if (body.shown) {
                note(order);
            }
            body.gone = true;
        }

        private static Slots player() {
            return new Slots().set("is_pc", 1.0).set("summon_type", 0.0).set("id", NOBODY);
        }
    }

    private static Socials clientSocials(Path client, WorldVariant variant) throws IOException {
        var table = NpcGrpTable.read(client.resolve("system"), variant);
        var socials = NpcSocials.read(client, variant);
        return new Socials() {
            @Override
            public @Nullable String getSequence(int npc, int social) {
                var appearance = table.getAppearance(npc);
                return appearance == null ? null : socials.getSequence(appearance.npcClass(), social);
            }

            @Override
            public String getDeath(int npc) {
                var appearance = table.getAppearance(npc);
                var death = appearance == null ? null : socials.getDeath(appearance.npcClass());
                return death == null ? DEATH : death;
            }

            @Override
            public @Nullable String getCasting(int npc) {
                var appearance = table.getAppearance(npc);
                return appearance == null ? null : socials.getKind(appearance.npcClass(), CAST_END);
            }
        };
    }

    private static Doors clientDoors(Path client) {
        var packages = new UPackageLoader(
                client,
                List.of(
                        new USearchConfig("Maps", "unr"),
                        new USearchConfig("StaticMeshes", "usx"),
                        new USearchConfig("Textures", "utx"),
                        new USearchConfig("SysTextures", "utx")));
        var tiles = new HashMap<String, Map<Integer, Door>>();
        return id -> {
            var digits = String.valueOf(id);
            if (digits.length() < TILE_DIGITS) {
                return null;
            }
            var tile = digits.substring(0, TILE_DIGITS / 2) + '_' + digits.substring(TILE_DIGITS / 2, TILE_DIGITS);
            return tiles.computeIfAbsent(tile, name -> doorsOf(packages, name)).get(id);
        };
    }

    private static Map<Integer, Door> doorsOf(UPackageLoader packages, String tile) {
        var doors = new HashMap<Integer, Door>();
        try {
            var map = packages.loadPackageIfPresent(tile);
            if (map.isPresent()) {
                var movers = new ArrayList<UMover>();
                map.get().loadObjects("Mover", movers);
                for (var mover : movers) {
                    var way = mover.getOpensBy();
                    doors.put(
                            mover.getServerId(),
                            new Door(mover.getSourcePath(), way.getX(), way.getY(), way.getZ(), mover.getMoveTime()));
                }
                packages.removePackage(tile);
            }
        } catch (RuntimeException unreadable) {
            log.warn("The doors of {} could not be read: {}", tile, unreadable.toString());
        }
        return doors;
    }

    private static Start timer(String ai, int timer) {
        return new Start(ai, TIMER, timer, "", "", 0, false);
    }

    private static Start on(String ai, String event) {
        return new Start(ai, event, 0, "", "", 0, false);
    }

    private static Start dies(String ai) {
        return new Start(ai, DYING, 0, "", "", 0, true);
    }

    private static Start tell(String first, String second) {
        return new Start("", "SCRIPT_EVENT", 0, first, second, 0, false);
    }

    private static @Nullable Film film(String name) {
        for (var film : FILMS) {
            if (film.name().equals(name)) {
                return film;
            }
        }
        return null;
    }

    private static @Nullable Film before(Film of) {
        for (var film : FILMS) {
            if (film.name().equals(of.after()) && film.npcs().equals(of.npcs())) {
                return film;
            }
        }
        return null;
    }

    private static @Nullable Path scripts(@Nullable Path folder) {
        if (folder == null) {
            return null;
        }
        for (var inside : List.of(".", "script", "scripts")) {
            var candidate = folder.resolve(inside);
            if (Files.isRegularFile(candidate.resolve(AI))
                    && Files.isRegularFile(candidate.resolve(POSITIONS))
                    && Files.isRegularFile(candidate.resolve(NUMBERS))) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Scene> read(Path scripts, Socials socials, Doors doors) throws IOException {
        try (var ai = ServerAi.open(scripts.resolve(AI))) {
            return read(scripts, ai, socials, doors);
        }
    }

    private static List<Scene> read(Path scripts, ServerAi ai, Socials socials, Doors doors) throws IOException {
        var positions = NpcPosFile.read(scripts.resolve(POSITIONS));
        var numbers = NpcPch.parse(SpawnText.read(scripts.resolve(NUMBERS)));
        var skills = new Skills(scripts.resolve(SKILLS));
        var settings = new NpcSettings(scripts.resolve(NPCS), parameters(scripts), skills);
        var constants = NpcPch.parse(SpawnText.of(textOf(scripts.resolve(CONSTANTS))));
        var doorNames = doorNames(scripts.resolve(DOOR_NAMES));
        var scenes = new LinkedHashMap<String, Scene>();
        for (var film : FILMS) {
            var scene = new Stage(film, ai, positions, numbers, constants, skills, settings, socials, name -> {
                        var id = doorNames.get(name);
                        return id == null ? null : doors.getDoor(id);
                    })
                    .shoot();
            if (scene != null) {
                scenes.putIfAbsent(scene.name(), scene);
            }
        }
        return List.copyOf(scenes.values());
    }

    private static Map<String, Integer> doorNames(Path file) throws IOException {
        var names = new HashMap<String, Integer>();
        if (Files.isRegularFile(file)) {
            var door = DOOR.matcher(textOf(file));
            while (door.find()) {
                names.put(door.group(1), Integer.valueOf(door.group(2)));
            }
        }
        return names;
    }

    private static @Nullable SpawnMaker maker(NpcPosFile positions, String npc) {
        for (var maker : positions.getMakers()) {
            for (var entry : maker.npcs()) {
                if (entry.npcName().equals(npc) || entry.ai().equals(npc)) {
                    return maker;
                }
            }
        }
        return null;
    }

    private static Actor act(
            Action action, Body body, int target, double x, double y, double z, int yaw, int skill, String animation) {
        return new Actor(
                false,
                action,
                body.npc,
                body.index,
                target,
                x,
                y,
                z,
                yaw,
                skill,
                animation,
                false,
                false,
                false,
                false,
                0,
                0,
                false,
                false,
                0,
                0,
                List.of());
    }

    private static Actor dying(Body body, String death) {
        var order = act(Action.ANIMATION, body, 0, 0, 0, 0, 0, 0, death);
        return new Actor(
                order.player(),
                order.action(),
                order.npc(),
                order.index(),
                order.target(),
                order.x(),
                order.y(),
                order.z(),
                order.yaw(),
                order.skill(),
                order.animation(),
                order.fly(),
                order.run(),
                order.loop(),
                order.self(),
                order.race(),
                order.sex(),
                order.mage(),
                order.spirit(),
                order.setItem(),
                order.setItemType(),
                order.visuals(),
                order.description(),
                order.immediate(),
                order.itemSlots(),
                order.speed(),
                order.moveSequence(),
                order.present(),
                true);
    }

    private static Actor arrival(Actor teleport) {
        return SceneFields.ACTOR.with(teleport, "action", Action.SPAWN);
    }

    private static Actor carried(Body body, String sequence, boolean loop, double since, String note) {
        var order = act(Action.ANIMATION, body, 0, 0, 0, 0, 0, 0, sequence);
        order = SceneFields.ACTOR.with(order, "loop", loop);
        order = SceneFields.ACTOR.with(order, "since", since);
        return SceneFields.ACTOR.with(order, "description", note);
    }

    private static Actor spawned(Body body, boolean present) {
        var order = act(Action.SPAWN, body, 0, body.x, body.y, body.z, body.heading, 0, "");
        return new Actor(
                order.player(),
                order.action(),
                order.npc(),
                order.index(),
                order.target(),
                order.x(),
                order.y(),
                order.z(),
                order.yaw(),
                order.skill(),
                order.animation(),
                order.fly(),
                order.run(),
                order.loop(),
                order.self(),
                order.race(),
                order.sex(),
                order.mage(),
                order.spirit(),
                order.setItem(),
                order.setItemType(),
                order.visuals(),
                order.description(),
                order.immediate(),
                order.itemSlots(),
                order.speed(),
                order.moveSequence(),
                present);
    }

    private static Camera bars(boolean wide) {
        return new Camera(true, 0, 0, 0, 0, 0, 0, 0, 0, 0, wide, false, false, 0);
    }

    private static int whole(@Nullable Object value) {
        return value instanceof Double number ? number.intValue() : 0;
    }

    private static double number(@Nullable Object value) {
        return value instanceof Double number ? number : 0;
    }

    private static String textOf(Path file) {
        try {
            return Files.isRegularFile(file) ? SpawnText.read(file).text() : "";
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static @Nullable Path parameters(Path scripts) {
        try (var files = Files.list(scripts)) {
            return files.filter(file -> file.getFileName().toString().equalsIgnoreCase(PARAMETERS))
                    .findFirst()
                    .orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }
}

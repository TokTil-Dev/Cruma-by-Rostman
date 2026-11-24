package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UObjectExport;
import com.rost.cruma.core.unreal.UProperty;
import com.rost.cruma.core.unreal.UProperty.Type;
import com.rost.cruma.core.world.CameraPath.Places;
import com.rost.cruma.core.world.CameraPath.Pose;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class GameScenes {
    private static final String PACKAGE = "LineageSceneInfo";
    private static final String ENGINE = "Engine";
    private static final String SCENE_CLASS = "L2SceneInfo";
    private static final String ITEM_CLASS = "L2SceneItem";
    private static final String ITEMS = "SceneInfo";
    private static final String ITEM_SLOT = "ItemSlot";
    private static final int ITEM_SLOTS = 29;

    private final UArchive archive;

    private GameScenes(UArchive archive) {
        this.archive = archive;
    }

    public static List<Scene> find(Path clientRoot) {
        var archives = new UArchiveLoader(clientRoot, List.of(new USearchConfig("System", "u")));
        var archive = archives.loadArchiveIfPresent(PACKAGE);
        if (archive == null) {
            return List.of();
        }
        try {
            return read(archive);
        } finally {
            archives.removeArchive(PACKAGE);
        }
    }

    public static @Nullable Model model(Path clientRoot) {
        var archives = new UArchiveLoader(clientRoot, List.of(new USearchConfig("System", "u")));
        var archive = archives.loadArchiveIfPresent(ENGINE);
        if (archive == null) {
            return null;
        }
        try {
            return model(archive);
        } finally {
            archives.removeArchive(ENGINE);
        }
    }

    public static List<Scene> read(UArchive archive) {
        var reader = new GameScenes(archive);
        var exports = archive.getExportMap();
        var scenes = new ArrayList<Scene>();
        for (var index = 1; index <= exports.size(); index++) {
            if (SCENE_CLASS.equals(exports.get(index - 1).getSuperName())) {
                var scene = reader.scene(index);
                if (scene != null) {
                    scenes.add(scene);
                }
            }
        }
        scenes.sort(Comparator.comparing(Scene::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(scenes);
    }

    public record Model(Set<String> camera, Set<String> screen, Set<String> music, Set<String> npc, Set<String> pc) {
        public Model {
            camera = Set.copyOf(camera);
            screen = Set.copyOf(screen);
            music = Set.copyOf(music);
            npc = Set.copyOf(npc);
            pc = Set.copyOf(pc);
        }
    }

    public record Scene(
            String name,
            List<Item> items,
            List<CameraPath> paths,
            String source,
            @Nullable Long seed,
            String map,
            double hour) {
        public Scene {
            if (items.isEmpty()) {
                throw new IllegalArgumentException("A scene needs an item");
            }
            items = List.copyOf(items);
            paths = List.copyOf(paths);
        }

        public Scene(
                String name, List<Item> items, List<CameraPath> paths, String source, @Nullable Long seed, String map) {
            this(name, items, paths, source, seed, map, Double.NaN);
        }

        public Scene(String name, List<Item> items, List<CameraPath> paths, String source, @Nullable Long seed) {
            this(name, items, paths, source, seed, "");
        }

        public Scene(String name, List<Item> items, List<CameraPath> paths) {
            this(name, items, paths, name, null);
        }

        public Scene(String name, List<Item> items) {
            this(name, items, List.of());
        }

        public double hourOr(double viewer) {
            return Double.isNaN(hour) ? viewer : hour;
        }

        public double[] times() {
            var times = new double[items.size()];
            var last = 0.0;
            for (var i = 0; i < times.length; i++) {
                last = Math.max(last, items.get(i).seconds());
                times[i] = last;
            }
            return times;
        }

        public double seconds() {
            var times = times();
            return times[times.length - 1];
        }

        public List<String> tiles() {
            var tiles = new LinkedHashSet<>(closeTiles());
            for (var name : flownTiles()) {
                var tile = WorldTile.fromMapName(name);
                for (var y = tile.y() - 1; y <= tile.y() + 1; y++) {
                    for (var x = tile.x() - 1; x <= tile.x() + 1; x++) {
                        tiles.add(x + "_" + y);
                    }
                }
            }
            return List.copyOf(tiles);
        }

        public List<String> closeTiles() {
            if (!map.isEmpty()) {
                return List.of();
            }
            var tiles = new LinkedHashSet<String>();
            for (var item : items) {
                for (var actor : item.actors()) {
                    if (actor.action().enters() && actor.placed()) {
                        var tile = WorldTile.at((float) actor.x(), (float) actor.y());
                        tiles.add(tile.x() + "_" + tile.y());
                    }
                }
            }
            tiles.addAll(flownTiles());
            return List.copyOf(tiles);
        }

        public List<String> flownTiles() {
            if (!map.isEmpty()) {
                return List.of();
            }
            var tiles = new LinkedHashSet<String>();
            for (var path : paths) {
                tiles.addAll(path.getTiles());
            }
            return List.copyOf(tiles);
        }

        public Pose flown(double seconds) {
            return flown(seconds, (_, _) -> null);
        }

        public Pose flown(double seconds, Places places) {
            CameraPath flying = null;
            for (var path : paths) {
                if (flying == null || path.getStart() <= seconds) {
                    flying = path;
                }
            }
            return flying == null ? null : flying.getPose(seconds, places);
        }

        public boolean movesCamera() {
            if (!paths.isEmpty()) {
                return true;
            }
            var standing = new HashSet<Integer>();
            for (var item : items) {
                for (var order : item.actors()) {
                    if (order.action().enters() && order.stands()) {
                        standing.add(order.index());
                    } else if (order.action() == Action.DESPAWN || order.action() == Action.TELEPORT_BACK) {
                        standing.remove(order.index());
                    }
                }
                var shot = item.camera();
                if (shot != null && shot.specialView() && !shot.blank() && standing.contains(shot.target())) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Item(
            double seconds,
            String description,
            @Nullable Camera camera,
            List<Actor> actors,
            @Nullable Screen screen,
            @Nullable Music music,
            double playRate,
            String source) {
        public Item {
            actors = List.copyOf(actors);
        }

        public Item(
                double seconds,
                String description,
                @Nullable Camera camera,
                List<Actor> actors,
                @Nullable Screen screen,
                @Nullable Music music) {
            this(seconds, description, camera, actors, screen, music, 0, "");
        }

        public Item at(double moment) {
            return new Item(moment, description, camera, actors, screen, music, playRate, source);
        }

        public boolean quiet() {
            return actors.isEmpty() && screen == null && music == null && (camera == null || camera.blank());
        }
    }

    public record Camera(
            int mode,
            int target,
            double distance,
            int yaw,
            int pitch,
            double moveSeconds,
            double holdSeconds,
            int relYaw,
            int relPitch,
            int relRoll,
            boolean wide,
            boolean relative,
            boolean immediate,
            double fov) {
        public static final int SPECIAL_VIEW = 0;

        public Camera(
                boolean specialView,
                int target,
                double distance,
                int yaw,
                int pitch,
                double moveSeconds,
                double holdSeconds,
                int relYaw,
                int relPitch,
                int relRoll,
                boolean wide,
                boolean relative,
                boolean immediate,
                double fov) {
            this(
                    specialView ? SPECIAL_VIEW : 1,
                    target,
                    distance,
                    yaw,
                    pitch,
                    moveSeconds,
                    holdSeconds,
                    relYaw,
                    relPitch,
                    relRoll,
                    wide,
                    relative,
                    immediate,
                    fov);
        }

        public static Camera blank(boolean wide) {
            return new Camera(SPECIAL_VIEW, 0, 0, 0, 0, 0, 0, 0, 0, 0, wide, false, false, 0);
        }

        public Camera withWide(boolean value) {
            return new Camera(
                    mode,
                    target,
                    distance,
                    yaw,
                    pitch,
                    moveSeconds,
                    holdSeconds,
                    relYaw,
                    relPitch,
                    relRoll,
                    value,
                    relative,
                    immediate,
                    fov);
        }

        public boolean specialView() {
            return mode == SPECIAL_VIEW;
        }

        public boolean blank() {
            return distance == 0
                    && yaw == 0
                    && pitch == 0
                    && moveSeconds == 0
                    && holdSeconds == 0
                    && relYaw == 0
                    && relPitch == 0
                    && relRoll == 0
                    && fov == 0;
        }
    }

    public enum Action {
        SPAWN,
        DESPAWN,
        TELEPORT,
        TELEPORT_BACK,
        SELF,
        MOVE,
        SKILL,
        ATTACK,
        ANIMATION,
        AIRSHIP_SPAWN,
        AIRSHIP_MOVE,
        ABNORMAL,
        DOOR,
        EFFECT,
        SELECT_TARGET,
        EQUIP,
        OTHER;

        public boolean enters() {
            return this == SPAWN || this == AIRSHIP_SPAWN || this == TELEPORT || this == SELF;
        }
    }

    public record Visual(int slot, int item) {}

    public record Actor(
            boolean player,
            Action action,
            int npc,
            int index,
            int target,
            double x,
            double y,
            double z,
            int yaw,
            int skill,
            String animation,
            boolean fly,
            boolean run,
            boolean loop,
            boolean self,
            int race,
            int sex,
            boolean mage,
            boolean spirit,
            int setItem,
            int setItemType,
            List<Visual> visuals,
            String description,
            boolean immediate,
            List<Integer> itemSlots,
            double speed,
            String moveSequence,
            boolean present,
            boolean dies,
            double since,
            boolean fights,
            int watches) {
        public Actor {
            visuals = List.copyOf(visuals);
            itemSlots = List.copyOf(itemSlots);
        }

        public Actor(
                boolean player,
                Action action,
                int npc,
                int index,
                int target,
                double x,
                double y,
                double z,
                int yaw,
                int skill,
                String animation,
                boolean fly,
                boolean run,
                boolean loop,
                boolean self,
                int race,
                int sex,
                boolean mage,
                boolean spirit,
                int setItem,
                int setItemType,
                List<Visual> visuals,
                String description,
                boolean immediate,
                List<Integer> itemSlots,
                double speed,
                String moveSequence,
                boolean present,
                boolean dies,
                double since,
                boolean fights) {
            this(
                    player,
                    action,
                    npc,
                    index,
                    target,
                    x,
                    y,
                    z,
                    yaw,
                    skill,
                    animation,
                    fly,
                    run,
                    loop,
                    self,
                    race,
                    sex,
                    mage,
                    spirit,
                    setItem,
                    setItemType,
                    visuals,
                    description,
                    immediate,
                    itemSlots,
                    speed,
                    moveSequence,
                    present,
                    dies,
                    since,
                    fights,
                    0);
        }

        public Actor(
                boolean player,
                Action action,
                int npc,
                int index,
                int target,
                double x,
                double y,
                double z,
                int yaw,
                int skill,
                String animation,
                boolean fly,
                boolean run,
                boolean loop,
                boolean self,
                int race,
                int sex,
                boolean mage,
                boolean spirit,
                int setItem,
                int setItemType,
                List<Visual> visuals,
                String description,
                boolean immediate,
                List<Integer> itemSlots,
                double speed,
                String moveSequence,
                boolean present,
                boolean dies,
                double since) {
            this(
                    player,
                    action,
                    npc,
                    index,
                    target,
                    x,
                    y,
                    z,
                    yaw,
                    skill,
                    animation,
                    fly,
                    run,
                    loop,
                    self,
                    race,
                    sex,
                    mage,
                    spirit,
                    setItem,
                    setItemType,
                    visuals,
                    description,
                    immediate,
                    itemSlots,
                    speed,
                    moveSequence,
                    present,
                    dies,
                    since,
                    false);
        }

        public Actor(
                boolean player,
                Action action,
                int npc,
                int index,
                int target,
                double x,
                double y,
                double z,
                int yaw,
                int skill,
                String animation,
                boolean fly,
                boolean run,
                boolean loop,
                boolean self,
                int race,
                int sex,
                boolean mage,
                boolean spirit,
                int setItem,
                int setItemType,
                List<Visual> visuals,
                String description,
                boolean immediate,
                List<Integer> itemSlots,
                double speed,
                String moveSequence,
                boolean present,
                boolean dies) {
            this(
                    player,
                    action,
                    npc,
                    index,
                    target,
                    x,
                    y,
                    z,
                    yaw,
                    skill,
                    animation,
                    fly,
                    run,
                    loop,
                    self,
                    race,
                    sex,
                    mage,
                    spirit,
                    setItem,
                    setItemType,
                    visuals,
                    description,
                    immediate,
                    itemSlots,
                    speed,
                    moveSequence,
                    present,
                    dies,
                    0);
        }

        public Actor(
                boolean player,
                Action action,
                int npc,
                int index,
                int target,
                double x,
                double y,
                double z,
                int yaw,
                int skill,
                String animation,
                boolean fly,
                boolean run,
                boolean loop,
                boolean self,
                int race,
                int sex,
                boolean mage,
                boolean spirit,
                int setItem,
                int setItemType,
                List<Visual> visuals,
                String description,
                boolean immediate,
                List<Integer> itemSlots,
                double speed,
                String moveSequence,
                boolean present) {
            this(
                    player,
                    action,
                    npc,
                    index,
                    target,
                    x,
                    y,
                    z,
                    yaw,
                    skill,
                    animation,
                    fly,
                    run,
                    loop,
                    self,
                    race,
                    sex,
                    mage,
                    spirit,
                    setItem,
                    setItemType,
                    visuals,
                    description,
                    immediate,
                    itemSlots,
                    speed,
                    moveSequence,
                    present,
                    false);
        }

        public Actor(
                boolean player,
                Action action,
                int npc,
                int index,
                int target,
                double x,
                double y,
                double z,
                int yaw,
                int skill,
                String animation,
                boolean fly,
                boolean run,
                boolean loop,
                boolean self,
                int race,
                int sex,
                boolean mage,
                boolean spirit,
                int setItem,
                int setItemType,
                List<Visual> visuals,
                String description,
                boolean immediate,
                List<Integer> itemSlots,
                double speed,
                String moveSequence) {
            this(
                    player,
                    action,
                    npc,
                    index,
                    target,
                    x,
                    y,
                    z,
                    yaw,
                    skill,
                    animation,
                    fly,
                    run,
                    loop,
                    self,
                    race,
                    sex,
                    mage,
                    spirit,
                    setItem,
                    setItemType,
                    visuals,
                    description,
                    immediate,
                    itemSlots,
                    speed,
                    moveSequence,
                    false);
        }

        public Actor(
                boolean player,
                Action action,
                int npc,
                int index,
                int target,
                double x,
                double y,
                double z,
                int yaw,
                int skill,
                String animation,
                boolean fly,
                boolean run,
                boolean loop,
                boolean self,
                int race,
                int sex,
                boolean mage,
                boolean spirit,
                int setItem,
                int setItemType,
                List<Visual> visuals,
                String description,
                boolean immediate,
                List<Integer> itemSlots) {
            this(
                    player,
                    action,
                    npc,
                    index,
                    target,
                    x,
                    y,
                    z,
                    yaw,
                    skill,
                    animation,
                    fly,
                    run,
                    loop,
                    self,
                    race,
                    sex,
                    mage,
                    spirit,
                    setItem,
                    setItemType,
                    visuals,
                    description,
                    immediate,
                    itemSlots,
                    0,
                    "");
        }

        public Actor(
                boolean player,
                Action action,
                int npc,
                int index,
                int target,
                double x,
                double y,
                double z,
                int yaw,
                int skill,
                String animation,
                boolean fly,
                boolean run,
                boolean loop,
                boolean self,
                int race,
                int sex,
                boolean mage,
                boolean spirit,
                int setItem,
                int setItemType,
                List<Visual> visuals) {
            this(
                    player,
                    action,
                    npc,
                    index,
                    target,
                    x,
                    y,
                    z,
                    yaw,
                    skill,
                    animation,
                    fly,
                    run,
                    loop,
                    self,
                    race,
                    sex,
                    mage,
                    spirit,
                    setItem,
                    setItemType,
                    visuals,
                    "",
                    false,
                    List.of());
        }

        public boolean placed() {
            return x != 0 || y != 0 || z != 0;
        }

        public boolean stands() {
            return player ? placed() : npc > 0;
        }
    }

    public enum ScreenKind {
        NONE,
        FADE,
        MESSAGE,
        SLIDE_START,
        SLIDE_CHANGE,
        SLIDE_MOVE,
        SLIDE_END,
        POST_EFFECT
    }

    public record Screen(
            ScreenKind kind,
            double fadeOut,
            double fadeIn,
            double hold,
            int color,
            int window,
            int animation,
            int message,
            String text,
            String slide,
            int slideDirection,
            double slideSeconds,
            int slideWidth,
            int slideHeight,
            int slideMove,
            int slideWindow,
            int postEffect,
            int delayed,
            int font,
            int background,
            int colorAlpha) {
        public Screen(
                ScreenKind kind,
                double fadeOut,
                double fadeIn,
                double hold,
                int color,
                int window,
                int animation,
                int message,
                String text,
                String slide,
                int slideDirection,
                double slideSeconds,
                int slideWidth,
                int slideHeight,
                int slideMove,
                int slideWindow,
                int postEffect,
                boolean waits) {
            this(
                    kind,
                    fadeOut,
                    fadeIn,
                    hold,
                    color,
                    window,
                    animation,
                    message,
                    text,
                    slide,
                    slideDirection,
                    slideSeconds,
                    slideWidth,
                    slideHeight,
                    slideMove,
                    slideWindow,
                    postEffect,
                    waits ? 1 : 0,
                    0,
                    0,
                    0);
        }

        public boolean waits() {
            return delayed != 0;
        }
    }

    public record Music(
            String name,
            boolean stopMusic,
            boolean stopAmbient,
            boolean stopEffects,
            boolean sound,
            boolean voice,
            double volume,
            double fadeSeconds,
            int level,
            double fadeOutSeconds) {
        private static final double FULL = 255;

        public Music(
                String name,
                boolean stopMusic,
                boolean stopAmbient,
                boolean stopEffects,
                boolean sound,
                boolean voice,
                double volume,
                double fadeSeconds) {
            this(
                    name,
                    stopMusic,
                    stopAmbient,
                    stopEffects,
                    sound,
                    voice,
                    volume,
                    fadeSeconds,
                    (int) Math.round(volume * FULL),
                    0);
        }

        public static double volumeOf(int level) {
            return level <= 0 ? 1 : level / FULL;
        }
    }

    private @Nullable Scene scene(int index) {
        var export = archive.getExportMap().get(index - 1);
        var items = new ArrayList<Item>();
        for (var reference : references(index, export)) {
            var item = item(reference);
            if (item != null) {
                items.add(item);
            }
        }
        return items.isEmpty() ? null : new Scene(export.getObjectName(), items);
    }

    private List<Integer> references(int index, UObjectExport export) {
        var start = export.getSerialOffset();
        var end = start + export.getSerialSize();
        for (var at = start; at < end - 3; at++) {
            var found = arrayAt(at, end);
            if (found != null) {
                return found;
            }
        }
        var items = new ArrayList<Integer>();
        var exports = archive.getExportMap();
        for (var i = 1; i <= exports.size(); i++) {
            var candidate = exports.get(i - 1);
            if (candidate.getPackageIndex() == index && ITEM_CLASS.equals(candidate.getClassName())) {
                items.add(i);
            }
        }
        return items;
    }

    private @Nullable List<Integer> arrayAt(int at, int end) {
        try {
            archive.setPosition(at);
            if (!ITEMS.equals(archive.resolveName(archive.readIndex()))) {
                return null;
            }
            var info = archive.readUInt8();
            if (Type.fromId(info & 0x0F) != Type.Array) {
                return null;
            }
            var size = switch ((info >> 4) & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> archive.readUInt8();
                case 6 -> archive.readUInt8() | archive.readUInt8() << 8;
                default -> archive.readInt();
            };
            var start = archive.getOffset();
            if (size < 1 || start + size > end) {
                return null;
            }
            var count = archive.readIndex();
            var references = new ArrayList<Integer>(Math.max(0, count));
            var exports = archive.getExportMap();
            for (var i = 0; i < count; i++) {
                var reference = archive.readIndex();
                if (reference < 1
                        || reference > exports.size()
                        || !ITEM_CLASS.equals(exports.get(reference - 1).getClassName())) {
                    return null;
                }
                references.add(reference);
            }
            return archive.getOffset() - start == size ? references : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private @Nullable Item item(int reference) {
        var export = archive.getExportMap().get(reference - 1);
        if (!ITEM_CLASS.equals(export.getClassName())) {
            return null;
        }
        var properties = new HashMap<String, UProperty>();
        for (var property : archive.getObjectLoader().exportProperties(export)) {
            properties.putIfAbsent(property.getName(), property);
        }
        var actors = new ArrayList<Actor>();
        for (var fields : elements(properties.get("NpcInfo"))) {
            actors.add(actor(fields, false));
        }
        for (var fields : elements(properties.get("PcInfo"))) {
            actors.add(actor(fields, true));
        }
        return new Item(
                number(properties.get("SceneTime")) / 1000,
                text(properties.get("Description")),
                camera(struct(properties.get("CameraInfo"))),
                actors,
                screen(struct(properties.get("ScreenInfo"))),
                music(struct(properties.get("MusicInfo"))),
                number(properties.get("ScenePlayRate")),
                export.getObjectName());
    }

    private static @Nullable Model model(UArchive archive) {
        var exports = archive.getExportMap();
        var fields = new HashMap<String, Set<String>>();
        for (var export : exports) {
            var owner = export.getPackageIndex();
            if (owner < 1 || owner > exports.size() || !export.getClassName().endsWith("Property")) {
                continue;
            }
            var parent = exports.get(owner - 1);
            var outer = parent.getPackageIndex();
            var inStruct = outer >= 1
                    && outer <= exports.size()
                    && ITEM_CLASS.equals(exports.get(outer - 1).getObjectName());
            if (inStruct) {
                fields.computeIfAbsent(parent.getObjectName(), _ -> new HashSet<>())
                        .add(export.getObjectName());
            }
        }
        if (fields.isEmpty()) {
            return null;
        }
        return new Model(
                fields.getOrDefault("CameraItem", Set.of()),
                fields.getOrDefault("ScreenItem", Set.of()),
                fields.getOrDefault("MusicItem", Set.of()),
                fields.getOrDefault("NpcItem", Set.of()),
                fields.getOrDefault("PcItem", Set.of()));
    }

    private static List<Map<String, UProperty>> elements(@Nullable UProperty property) {
        return property == null ? List.of() : property.getSubProperties();
    }

    private static @Nullable Map<String, UProperty> struct(@Nullable UProperty property) {
        return property == null || property.getSubProperties().isEmpty()
                ? null
                : property.getSubProperties().getFirst();
    }

    private static @Nullable Camera camera(@Nullable Map<String, UProperty> fields) {
        if (fields == null) {
            return null;
        }
        return new Camera(
                cameraMode(fields.get("UseCamera")),
                integer(fields.get("TargetIndex")),
                integer(fields.get("dist")),
                integer(fields.get("Yaw")),
                integer(fields.get("Pitch")),
                integer(fields.get("Time")) / 1000.0,
                integer(fields.get("Duration")) / 1000.0,
                integer(fields.get("RelYaw")),
                integer(fields.get("RelPitch")),
                integer(fields.get("RelRoll")),
                readBool(fields.get("IsWide")),
                readBool(fields.get("IsRelAngle")),
                readBool(fields.get("IsImmTarget")),
                number(fields.get("CameraFov")));
    }

    private static int cameraMode(@Nullable UProperty use) {
        if (use == null) {
            return Camera.SPECIAL_VIEW;
        }
        if (use.getType() == Type.Bool) {
            return use.isTrue() ? Camera.SPECIAL_VIEW : 1;
        }
        return integer(use);
    }

    private static Actor actor(Map<String, UProperty> fields, boolean player) {
        var type = integer(fields.get(player ? "PcItemType" : "NpcItemType"));
        var place = fields.get("Location");
        var at = place == null ? null : place.getVectorValue();
        return new Actor(
                player,
                player ? playerAction(type) : npcAction(type),
                integer(fields.get("NpcID")),
                integer(fields.containsKey("Index") ? fields.get("Index") : fields.get("NpcIndex")),
                integer(fields.get("TargetIndex")),
                at == null ? 0 : at.getX(),
                at == null ? 0 : at.getY(),
                at == null ? 0 : at.getZ(),
                integer(fields.get("Yaw")),
                integer(fields.get("SkillID")),
                text(fields.get("AnimName")),
                readBool(fields.get("IsFly")),
                readBool(fields.get("IsRun")),
                readBool(fields.get("IsLoop")),
                readBool(fields.get("IsSelfPawn")),
                integer(fields.get("Race")),
                integer(fields.get("Sex")),
                readBool(fields.get("IsMage")),
                readBool(fields.get("UseSpirit")),
                integer(fields.get("SetItemNo")),
                integer(fields.get("SetItemType")),
                visuals(fields.get("VisualItemList")),
                text(fields.get("Description")),
                readBool(fields.get("ImmInsight")),
                slots(fields));
    }

    private static List<Integer> slots(Map<String, UProperty> fields) {
        var first = fields.get(ITEM_SLOT);
        if (first == null) {
            return List.of();
        }
        var slots = new ArrayList<Integer>(ITEM_SLOTS);
        for (var slot = 0; slot < ITEM_SLOTS; slot++) {
            var element = fields.get(ITEM_SLOT + "[" + slot + "]");
            slots.add(
                    element != null ? integer(element) : slot == 0 && first.getArrayIndex() == 0 ? integer(first) : 0);
        }
        return slots;
    }

    private static List<Visual> visuals(@Nullable UProperty list) {
        var visuals = new ArrayList<Visual>();
        for (var fields : elements(list)) {
            visuals.add(new Visual(integer(fields.get("Type")), integer(fields.get("VisualItemID"))));
        }
        return visuals;
    }

    private static Action npcAction(int type) {
        return switch (type) {
            case 0 -> Action.SPAWN;
            case 1 -> Action.DESPAWN;
            case 2 -> Action.MOVE;
            case 3 -> Action.SKILL;
            case 4 -> Action.ANIMATION;
            case 5 -> Action.AIRSHIP_SPAWN;
            case 6 -> Action.AIRSHIP_MOVE;
            case 7 -> Action.ABNORMAL;
            case 8 -> Action.EFFECT;
            default -> Action.OTHER;
        };
    }

    private static Action playerAction(int type) {
        return switch (type) {
            case 0 -> Action.SPAWN;
            case 1 -> Action.DESPAWN;
            case 2 -> Action.TELEPORT;
            case 3 -> Action.SELECT_TARGET;
            case 4 -> Action.EQUIP;
            case 5 -> Action.MOVE;
            case 6 -> Action.ATTACK;
            case 7 -> Action.SKILL;
            case 8 -> Action.ANIMATION;
            case 9 -> Action.ABNORMAL;
            case 10 -> Action.EFFECT;
            case 11 -> Action.TELEPORT_BACK;
            case 12 -> Action.SELF;
            default -> Action.OTHER;
        };
    }

    private static @Nullable Screen screen(@Nullable Map<String, UProperty> fields) {
        if (fields == null) {
            return null;
        }
        var type = integer(fields.get("ScreenType"));
        var kinds = ScreenKind.values();
        var color = fields.get("FadeColor");
        var fade = color == null ? null : color.getColorValue();
        var rgb = fade == null ? 0 : (fade.r() & 0xFF) << 16 | (fade.g() & 0xFF) << 8 | fade.b() & 0xFF;
        var alpha = fade == null ? 0 : fade.a() & 0xFF;
        return new Screen(
                type >= 0 && type < kinds.length ? kinds[type] : ScreenKind.NONE,
                number(fields.get("FadeOutDuration")) / 1000,
                number(fields.get("FadeInDuration")) / 1000,
                number(fields.get("BlackOutDuration")) / 1000,
                rgb,
                integer(fields.get("WindowType")),
                integer(fields.get("AnimationType")),
                integer(fields.get("MsgNo")),
                text(fields.get("MsgText")),
                text(fields.get("SlideTexName")),
                integer(fields.get("SlideDirection")),
                integer(fields.get("SlidingTime")) / 1000.0,
                integer(fields.get("SlideTexUSize")),
                integer(fields.get("SlideTexVSize")),
                integer(fields.get("SlideMoveRatio")),
                integer(fields.get("SlideWndRatio")),
                integer(fields.get("PostEffectID")),
                integer(fields.get("DelayedType")),
                integer(fields.get("FontType")),
                integer(fields.get("BackgroundType")),
                alpha);
    }

    private static @Nullable Music music(@Nullable Map<String, UProperty> fields) {
        if (fields == null || !readBool(fields.get("UseMusic"))) {
            return null;
        }
        var level = integer(fields.get("Volume"));
        return new Music(
                text(fields.get("MusicName")),
                readBool(fields.get("StopMapMusic")),
                readBool(fields.get("StopMapAmbient")),
                readBool(fields.get("StopMapSoundFx")),
                readBool(fields.get("IsSound")),
                readBool(fields.get("IsVoice")),
                Music.volumeOf(level),
                integer(fields.get("FadeInTime")) / 1000.0,
                level,
                integer(fields.get("FadeOutTime")) / 1000.0);
    }

    private static int integer(@Nullable UProperty property) {
        if (property == null) {
            return 0;
        }
        return switch (property.getType()) {
            case Byte -> property.getUInt8Value();
            case Int -> property.getInt32Value();
            default -> 0;
        };
    }

    private static double number(@Nullable UProperty property) {
        return property == null || property.getType() != Type.Float ? 0 : property.getFloatValue();
    }

    private static boolean readBool(@Nullable UProperty property) {
        return property != null && property.isTrue();
    }

    private static String text(@Nullable UProperty property) {
        return property == null || property.getStringValue() == null ? "" : property.getStringValue();
    }
}

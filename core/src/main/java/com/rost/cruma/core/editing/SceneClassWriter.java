package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.SceneScript.CAMERA;
import static com.rost.cruma.core.editing.SceneScript.ITEM;
import static com.rost.cruma.core.editing.SceneScript.MUSIC;
import static com.rost.cruma.core.editing.SceneScript.NPC;
import static com.rost.cruma.core.editing.SceneScript.PC;
import static com.rost.cruma.core.editing.SceneScript.SCREEN;
import static com.rost.cruma.core.editing.SceneScript.VISUAL;
import static com.rost.cruma.core.editing.TaggedProperties.ARRAY;
import static com.rost.cruma.core.editing.TaggedProperties.BOOL;
import static com.rost.cruma.core.editing.TaggedProperties.BYTE;
import static com.rost.cruma.core.editing.TaggedProperties.FLOAT;
import static com.rost.cruma.core.editing.TaggedProperties.INT;
import static com.rost.cruma.core.editing.TaggedProperties.STR;
import static com.rost.cruma.core.editing.TaggedProperties.STRUCT;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.SceneScript.Field;
import com.rost.cruma.core.editing.TaggedProperties.Property;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Camera;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import com.rost.cruma.core.world.GameScenes.Visual;
import com.rost.cruma.core.world.SceneDocument;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

public final class SceneClassWriter {
    private static final String ITEMS = "SceneInfo";
    private static final String SCENE_TIME = "SceneTime";
    private static final String USE_CAMERA = "UseCamera";
    private static final String SCREEN_TYPE = "ScreenType";
    private static final String ITEM_SLOT = "ItemSlot";
    private static final String VISUALS = "VisualItemList";
    private static final int ITEM_FLAGS = 0x00070000;
    private static final double READ_BACK = 1e-4;
    private static final double SAME_MOMENT = 1e-6;
    private static final double MILLISECONDS = 1000;

    private final ClassPackageWriter writer;
    private final SceneScript script;
    private final String hash;
    private final Map<String, double[]> moments = new LinkedHashMap<>();

    private SceneClassWriter(ClassPackageWriter writer, SceneScript script, String hash) {
        this.writer = writer;
        this.script = script;
        this.hash = hash;
    }

    public record Loss(double seconds, String part, String field, String value) {}

    public record Report(String scene, int items, int added, int leftOut, boolean keptObjects, List<Loss> losses) {
        public Report {
            losses = List.copyOf(losses);
        }
    }

    public static SceneClassWriter open(Path file, byte[] bytes, SceneScript script) throws IOException {
        return new SceneClassWriter(ClassPackageWriter.open(new PackageBytes(file, bytes)), script, sha256(bytes));
    }

    public Report replace(String className, SceneDocument document) throws IOException {
        if (!document.paths().isEmpty()) {
            throw new IllegalArgumentException("A camera path must go through ShotBake first");
        }
        var classExport = writer.findClassExport(className);
        if (classExport == 0) {
            throw malformed("This package has no scene class " + className);
        }
        var data = ClassExportData.read(writer.getPayload(classExport), writer.exportName(classExport), writer);
        if (data == null) {
            throw malformed("Scene class " + className + " has an unknown layout");
        }
        var array = data.defaults.find(ITEMS, writer);
        if (array == null || array.getArray() == null || array.getArray().getObjects() == null) {
            throw malformed("Scene class " + className + " has no list of items");
        }
        var keeps = keepsObjects(className, document);
        var objects = keeps ? items(classExport) : Map.<String, Integer>of();
        var losses = new Losses();
        if (!Double.isNaN(document.hour())) {
            losses.add("Scene", "hour", document.hour());
        }
        var listed = new ArrayList<Integer>();
        var used = new HashSet<Integer>();
        var last = 0.0;
        var added = 0;
        for (var item : document.items()) {
            losses.at(item.seconds());
            var export = objects.get(key(item.source()));
            if (export != null && used.add(export)) {
                var tree = block(export);
                var before = tree.toBytes();
                last = moment(tree, item.seconds(), last);
                write(tree, item, losses);
                if (!Arrays.equals(before, tree.toBytes())) {
                    writer.replace(export, tree.toBytes());
                }
            } else {
                var tree = createFresh(item, losses);
                last = Math.max(last, item.seconds());
                export = writer.addExport(
                        itemClass(),
                        0,
                        classExport,
                        writer.freeChildName(classExport, ITEM),
                        itemFlags(),
                        tree.toBytes());
                added++;
            }
            listed.add(export);
        }
        var classBefore = data.write();
        var objectsBefore = array.getArray().getObjects();
        var leftOut = 0;
        for (var object : objectsBefore) {
            leftOut += listed.contains(object) ? 0 : 1;
        }
        objectsBefore.clear();
        objectsBefore.addAll(listed);
        if (!Arrays.equals(classBefore, data.write())) {
            writer.replace(classExport, data.write());
        }
        moments.put(key(className), new Scene(className, document.items()).times());
        return new Report(writer.exportName(classExport), listed.size(), added, leftOut, keeps, losses.list);
    }

    public boolean isChanged() {
        return writer.isChanged();
    }

    public byte[] getBytes(Path target) throws IOException {
        var bytes = writer.encode(target);
        var written = new PackageBytes(target, bytes);
        var buffer = ByteBuffer.wrap(written.body).order(ByteOrder.LITTLE_ENDIAN);
        try (var archive = new UArchive(written.packageName, buffer, null)) {
            var read = new HashMap<String, Scene>();
            for (var scene : GameScenes.read(archive)) {
                read.put(key(scene.name()), scene);
            }
            for (var moment : moments.entrySet()) {
                var scene = read.get(moment.getKey());
                if (scene == null || !isSameMoments(scene.times(), moment.getValue())) {
                    throw malformed("Scene " + moment.getKey() + " does not read back as it was written");
                }
            }
        }
        return bytes;
    }

    TaggedProperties createFresh(Item item, Losses losses) throws IOException {
        var tree = TaggedProperties.empty(writer);
        if (item.seconds() != 0) {
            tree.add(writer.intern(SCENE_TIME), FLOAT).setFloatValue((float) (item.seconds() * MILLISECONDS));
        }
        write(tree, item, losses);
        return tree;
    }

    private Map<String, Integer> items(int classExport) throws IOException {
        var result = new HashMap<String, Integer>();
        for (var child : writer.getChildren(classExport)) {
            if (writer.getClassName(child).equals(ITEM)) {
                result.putIfAbsent(key(writer.exportName(child)), child);
            }
        }
        return result;
    }

    private boolean keepsObjects(String className, SceneDocument document) {
        var base = document.basedOn().isEmpty() ? document.name() : document.basedOn();
        var sha = document.basedOnSha256();
        return base.equalsIgnoreCase(className) && (sha.isEmpty() || sha.equalsIgnoreCase(hash));
    }

    private TaggedProperties block(int export) throws IOException {
        var payload = writer.getPayload(export);
        var cursor = new Cursor(payload, 0, payload.length);
        var tree = TaggedProperties.read(cursor, writer);
        if (cursor.position != cursor.end) {
            throw malformed("Item " + writer.exportName(export) + " has bytes after its tags");
        }
        return tree;
    }

    private double moment(TaggedProperties tree, double wanted, double last) throws IOException {
        var tag = tree.find(SCENE_TIME, writer);
        var stored = tag == null ? 0 : tag.getFloatValue() / MILLISECONDS;
        var runs = Math.max(last, stored);
        if (Math.abs(stored - wanted) < SAME_MOMENT || Math.abs(runs - wanted) < SAME_MOMENT) {
            return runs;
        }
        if (tag == null) {
            tag = tree.add(writer.intern(SCENE_TIME), FLOAT);
        }
        tag.setFloatValue((float) (wanted * MILLISECONDS));
        return Math.max(last, wanted);
    }

    private void write(TaggedProperties tree, Item item, Losses losses) throws IOException {
        var wanted = new Fields("Item", losses);
        wanted.put("Description", item.description());
        wanted.put("ScenePlayRate", (float) item.playRate());
        change(tree, ITEM, wanted);
        struct(tree, "CameraInfo", CAMERA, item.camera() == null ? null : shot(item.camera(), losses));
        struct(tree, "ScreenInfo", SCREEN, item.screen() == null ? null : screen(item.screen(), tree, losses));
        struct(tree, "MusicInfo", MUSIC, item.music() == null ? null : music(item.music(), losses));
        orders(tree, item, losses);
    }

    private void struct(TaggedProperties tree, String field, String struct, @Nullable Fields wanted)
            throws IOException {
        var tag = tree.find(field, writer);
        if (wanted == null) {
            if (tag != null && tag.getStruct() != null && !isOff(tag.getStruct(), struct)) {
                tree.remove(field, 0, writer);
            }
            return;
        }
        if (tag != null && tag.getStruct() == null) {
            throw malformed("The struct " + field + " has an unknown layout");
        }
        if (tag != null) {
            change(tag.getStruct(), struct, wanted);
            return;
        }
        var added = tree.add(writer.intern(field), STRUCT).setStructName(writer.intern(struct));
        fill(added.makeEmptyStruct(writer), struct, wanted);
    }

    private boolean isOff(TaggedProperties block, String struct) throws IOException {
        if (block.isEmpty()) {
            return true;
        }
        if (!struct.equals(MUSIC)) {
            return false;
        }
        var use = block.find("UseMusic", writer);
        return use == null || !use.isTrue();
    }

    private Fields shot(Camera shot, Losses losses) {
        var wanted = new Fields("Shot", losses);
        var use = script.findField(CAMERA, USE_CAMERA);
        if (use != null && use.type() == BOOL) {
            wanted.put(USE_CAMERA, shot.specialView());
            if (shot.mode() > 1) {
                wanted.lost(USE_CAMERA, shot.mode());
            }
        } else {
            wanted.put(USE_CAMERA, shot.mode());
        }
        wanted.put("TargetIndex", shot.target());
        wanted.put("dist", (int) Math.round(shot.distance()));
        wanted.put("Yaw", shot.yaw());
        wanted.put("Pitch", shot.pitch());
        wanted.put("Time", milliseconds(shot.moveSeconds()));
        wanted.put("Duration", milliseconds(shot.holdSeconds()));
        wanted.put("RelYaw", shot.relYaw());
        wanted.put("RelPitch", shot.relPitch());
        wanted.put("RelRoll", shot.relRoll());
        wanted.put("IsWide", shot.wide());
        wanted.put("IsRelAngle", shot.relative());
        wanted.put("IsImmTarget", shot.immediate());
        wanted.put("CameraFov", (float) shot.fov());
        return wanted;
    }

    private @Nullable Fields screen(Screen screen, TaggedProperties tree, Losses losses) throws IOException {
        var kinds = script.countValues("ScreenItemType");
        if (screen.kind().ordinal() >= kinds) {
            losses.add("Screen", SCREEN_TYPE, screen.kind());
            return null;
        }
        var wanted = new Fields("Screen", losses);
        var held = tree.find("ScreenInfo", writer);
        var tag = held == null || held.getStruct() == null
                ? null
                : held.getStruct().find(SCREEN_TYPE, writer);
        if (screen.kind() != ScreenKind.NONE || tag == null || tag.getByteValue() < ScreenKind.values().length) {
            wanted.put(SCREEN_TYPE, screen.kind().ordinal());
        }
        wanted.put("DelayedType", screen.delayed());
        wanted.put("FadeOutDuration", (float) (screen.fadeOut() * MILLISECONDS));
        wanted.put("FadeInDuration", (float) (screen.fadeIn() * MILLISECONDS));
        wanted.put("BlackOutDuration", (float) (screen.hold() * MILLISECONDS));
        wanted.put("FadeColor", new Color(screen.color(), screen.colorAlpha()));
        wanted.put("WindowType", screen.window());
        wanted.put("AnimationType", screen.animation());
        wanted.put("FontType", screen.font());
        wanted.put("BackgroundType", screen.background());
        wanted.put("MsgNo", screen.message());
        wanted.put("MsgText", screen.text());
        wanted.put("SlideDirection", screen.slideDirection());
        wanted.put("SlidingTime", milliseconds(screen.slideSeconds()));
        wanted.put("SlideTexName", screen.slide());
        wanted.put("SlideTexUSize", screen.slideWidth());
        wanted.put("SlideTexVSize", screen.slideHeight());
        wanted.put("SlideMoveRatio", screen.slideMove());
        wanted.put("SlideWndRatio", screen.slideWindow());
        wanted.put("PostEffectID", screen.postEffect());
        return wanted;
    }

    private Fields music(Music music, Losses losses) {
        var wanted = new Fields("Music", losses);
        wanted.put("UseMusic", true);
        wanted.put("MusicName", music.name());
        wanted.put("StopMapMusic", music.stopMusic());
        wanted.put("StopMapAmbient", music.stopAmbient());
        wanted.put("StopMapSoundFx", music.stopEffects());
        wanted.put("IsSound", music.sound());
        wanted.put("IsVoice", music.voice());
        if (script.has(MUSIC, "Volume") || Music.volumeOf(music.level()) < 1) {
            wanted.put("Volume", music.level());
        }
        wanted.put("FadeInTime", milliseconds(music.fadeSeconds()));
        wanted.put("FadeOutTime", milliseconds(music.fadeOutSeconds()));
        return wanted;
    }

    private void orders(TaggedProperties tree, Item item, Losses losses) throws IOException {
        var npcs = new ArrayList<Fields>();
        var players = new ArrayList<Fields>();
        var hasPlayers = script.has(ITEM, "PcInfo");
        for (var actor : item.actors()) {
            if (actor.player() && !hasPlayers) {
                losses.add("Order", "PcInfo", actor.action());
                continue;
            }
            var wanted = actor.player() ? player(actor, losses) : npc(actor, losses);
            if (wanted != null) {
                (actor.player() ? players : npcs).add(wanted);
            }
        }
        list(tree, "NpcInfo", NPC, npcs);
        list(tree, "PcInfo", PC, players);
    }

    private @Nullable Fields npc(Actor actor, Losses losses) {
        var wanted = new Fields("Order", losses);
        if (!order(wanted, actor, "NpcItemType", npcType(actor.action()))) {
            return null;
        }
        wanted.put("NpcID", actor.npc());
        wanted.put("IsFly", actor.fly());
        wanted.put(script.has(NPC, "Index") ? "Index" : "NpcIndex", actor.index());
        wanted.put("IsSelfPawn", actor.self());
        wanted.put("Race", actor.race());
        wanted.put("Sex", actor.sex());
        wanted.put("IsMage", actor.mage());
        wanted.put("UseSpirit", actor.spirit());
        wanted.put("SetItemNo", actor.setItem());
        wanted.put("SetItemType", actor.setItemType());
        wanted.put(VISUALS, actor.visuals());
        wanted.put(ITEM_SLOT, slots(actor));
        return wanted;
    }

    private @Nullable Fields player(Actor actor, Losses losses) {
        var wanted = new Fields("Order", losses);
        if (!order(wanted, actor, "PcItemType", playerType(actor.action()))) {
            return null;
        }
        wanted.put("Index", actor.index());
        wanted.put("IsSelfPawn", actor.self());
        wanted.put("Race", actor.race());
        wanted.put("Sex", actor.sex());
        wanted.put("IsMage", actor.mage());
        wanted.put("UseSpirit", actor.spirit());
        wanted.put("SetItemNo", actor.setItem());
        wanted.put("SetItemType", actor.setItemType());
        wanted.put(VISUALS, actor.visuals());
        wanted.put(ITEM_SLOT, slots(actor));
        wanted.put("NpcID", actor.npc());
        wanted.put("IsFly", actor.fly());
        return wanted;
    }

    private boolean order(Fields wanted, Actor actor, String typeName, int type) {
        if (actor.action() == Action.OTHER) {
            wanted.keepsType = typeName;
        } else if (type < 0 || type >= script.countValues(typeName)) {
            wanted.lost(typeName, actor.action());
            return false;
        } else {
            wanted.put(typeName, type);
        }
        wanted.put("Description", actor.description());
        wanted.put("TargetIndex", actor.target());
        wanted.put("Location", new float[] {(float) actor.x(), (float) actor.y(), (float) actor.z()});
        wanted.put("Yaw", actor.yaw());
        wanted.put("SkillID", actor.skill());
        wanted.put("AnimName", actor.animation());
        wanted.put("IsRun", actor.run());
        wanted.put("IsLoop", actor.loop());
        wanted.put("ImmInsight", actor.immediate());
        if (actor.speed() != 0) {
            wanted.lost(actor.action() == Action.ANIMATION ? "rate of a sequence" : "speed of a move", actor.speed());
        }
        if (!actor.moveSequence().isEmpty()) {
            wanted.lost("sequence of a move", actor.moveSequence());
        }
        if (actor.watches() != 0) {
            wanted.lost("actor it keeps turned to", actor.watches());
        }
        return true;
    }

    private void list(TaggedProperties tree, String field, String struct, List<Fields> wanted) throws IOException {
        var tag = tree.find(field, writer);
        if (tag != null && (tag.getArray() == null || tag.getArray().getElements() == null)) {
            throw malformed("The array " + field + " has an unknown layout");
        }
        if (wanted.isEmpty()) {
            if (tag != null && !tag.getArray().getElements().isEmpty()) {
                tree.remove(field, 0, writer);
            }
            return;
        }
        if (tag == null) {
            tag = tree.add(writer.intern(field), ARRAY);
            tag.makeEmptyArray(false);
        }
        var elements = tag.getArray().getElements();
        var left = new ArrayList<>(elements);
        var taken = new TaggedProperties[wanted.size()];
        for (var i = 0; i < taken.length; i++) {
            for (var element : left) {
                if (matches(element, struct, wanted.get(i))) {
                    taken[i] = element;
                    left.remove(element);
                    break;
                }
            }
        }
        for (var i = 0; i < taken.length; i++) {
            if (taken[i] == null) {
                for (var element : left) {
                    if (isSameIndex(element, wanted.get(i))) {
                        taken[i] = element;
                        left.remove(element);
                        change(element, struct, wanted.get(i));
                        break;
                    }
                }
            }
        }
        elements.clear();
        for (var i = 0; i < taken.length; i++) {
            if (taken[i] == null && wanted.get(i).keepsType != null) {
                wanted.get(i).lost(wanted.get(i).keepsType, Action.OTHER);
                continue;
            }
            if (taken[i] == null) {
                taken[i] = TaggedProperties.empty(writer);
                fill(taken[i], struct, wanted.get(i));
            }
            elements.add(taken[i]);
        }
    }

    private boolean matches(TaggedProperties block, String struct, Fields wanted) throws IOException {
        for (var entry : wanted.values.entrySet()) {
            var field = script.findField(struct, entry.getKey());
            if (field != null && !holds(block, field, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean isSameIndex(TaggedProperties block, Fields wanted) throws IOException {
        for (var name : List.of("Index", "NpcIndex")) {
            var value = wanted.get(name);
            if (value != null) {
                return isSame(block.find(name, writer), value);
            }
        }
        return false;
    }

    private boolean holds(TaggedProperties block, Field field, Object value) throws IOException {
        return switch (value) {
            case int[] slots -> {
                for (var i = 0; i < field.entries(); i++) {
                    var tag = block.find(field.name(), i, writer);
                    if (!isSame(tag, i < slots.length ? slots[i] : 0)) {
                        yield false;
                    }
                }
                yield true;
            }
            case List<?> visuals -> visuals(block, field).equals(visuals);
            default -> isSame(block.find(field.name(), writer), value);
        };
    }

    private List<Visual> visuals(TaggedProperties block, Field field) throws IOException {
        var tag = block.find(field.name(), writer);
        if (tag == null || tag.getArray() == null || tag.getArray().getElements() == null) {
            return List.of();
        }
        var result = new ArrayList<Visual>();
        for (var element : tag.getArray().getElements()) {
            result.add(new Visual(whole(element.find("Type", writer)), whole(element.find("VisualItemID", writer))));
        }
        return result;
    }

    private void change(TaggedProperties block, String struct, Fields wanted) throws IOException {
        for (var entry : wanted.values.entrySet()) {
            var value = entry.getValue();
            var field = script.findField(struct, entry.getKey());
            if (field == null) {
                if (!isDefault(value)) {
                    wanted.lost(entry.getKey(), value);
                }
                continue;
            }
            if (holds(block, field, value)) {
                continue;
            }
            switch (value) {
                case int[] slots -> changeSlots(block, field, slots);
                case List<?> _ -> changeVisuals(block, field, wanted);
                default -> {
                    var tag = block.find(field.name(), writer);
                    if (tag != null || !field.name().equalsIgnoreCase(USE_CAMERA) || !Boolean.TRUE.equals(value)) {
                        set(tag == null ? add(block, field, value) : tag, value);
                    }
                }
            }
        }
    }

    private void changeSlots(TaggedProperties block, Field field, int[] slots) throws IOException {
        for (var i = 0; i < field.entries(); i++) {
            var value = i < slots.length ? slots[i] : 0;
            var tag = block.find(field.name(), i, writer);
            if (!isSame(tag, value)) {
                if (tag == null) {
                    tag = block.add(writer.intern(field.name()), INT).setArrayIndex(i);
                }
                tag.setNumber(value);
            }
        }
    }

    private void changeVisuals(TaggedProperties block, Field field, Fields wanted) throws IOException {
        var tag = block.find(field.name(), writer);
        if (tag == null) {
            tag = block.add(writer.intern(field.name()), ARRAY);
            tag.makeEmptyArray(false);
        }
        var elements = tag.getArray().getElements();
        if (elements == null) {
            throw malformed("The array " + field.name() + " has an unknown layout");
        }
        elements.clear();
        for (var visual : visualsOf(wanted)) {
            elements.add(visual(visual));
        }
    }

    private TaggedProperties visual(Visual visual) throws IOException {
        var block = TaggedProperties.empty(writer);
        var values = new Fields("Order", new Losses());
        values.put("Type", visual.slot());
        values.put("VisualItemID", visual.item());
        fill(block, VISUAL, values);
        return block;
    }

    private void fill(TaggedProperties block, String struct, Fields wanted) throws IOException {
        var left = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        left.addAll(wanted.values.keySet());
        for (var field : script.getFields(struct)) {
            var value = wanted.get(field.name());
            left.remove(field.name());
            if (value == null) {
                continue;
            }
            switch (value) {
                case int[] slots -> {
                    for (var i = 0; i < field.entries(); i++) {
                        block.add(writer.intern(field.name()), INT)
                                .setArrayIndex(i)
                                .setNumber(i < slots.length ? slots[i] : 0);
                    }
                }
                case List<?> _ -> {
                    var tag = block.add(writer.intern(field.name()), ARRAY);
                    var elements = tag.makeEmptyArray(false).getElements();
                    for (var visual : visualsOf(wanted)) {
                        elements.add(visual(visual));
                    }
                }
                default -> set(add(block, field, value), value);
            }
        }
        for (var name : left) {
            if (!isDefault(wanted.get(name))) {
                wanted.lost(name, wanted.get(name));
            }
        }
    }

    private Property add(TaggedProperties block, Field field, Object value) {
        var tag = block.add(writer.intern(field.name()), field.type());
        if (field.type() == STRUCT) {
            tag.setStructName(writer.intern(value instanceof Color ? "Color" : "Vector"));
        }
        return tag;
    }

    private int itemClass() {
        return writer.importObject(writer.importPackage("Engine"), "Core", "Class", ITEM);
    }

    private int itemFlags() throws IOException {
        for (var export = 1; export <= writer.getExportCount(); export++) {
            if (writer.getClassName(export).equals(ITEM)) {
                return writer.export(export).flags();
            }
        }
        return ITEM_FLAGS;
    }

    private static int[] slots(Actor actor) {
        var slots = new int[actor.itemSlots().size()];
        for (var i = 0; i < slots.length; i++) {
            slots[i] = actor.itemSlots().get(i);
        }
        return slots;
    }

    @SuppressWarnings("unchecked")
    private static List<Visual> visualsOf(Fields wanted) {
        return (List<Visual>) wanted.get(VISUALS);
    }

    private static void set(Property tag, Object value) throws IOException {
        switch (value) {
            case Boolean bool -> tag.setBool(bool);
            case Integer number -> tag.setNumber(number);
            case Float number -> tag.setNumber(number);
            case String text -> tag.setString(text);
            case float[] place -> {
                tag.setRaw(new byte[12]);
                tag.getBinary().putFloat(0, place[0]).putFloat(4, place[1]).putFloat(8, place[2]);
            }
            case Color color -> tag.setRaw(color.bytes());
            default -> throw malformed("A value of " + value.getClass() + " has no tag");
        }
    }

    private static boolean isSame(@Nullable Property tag, Object value) throws IOException {
        return switch (value) {
            case Boolean bool -> tag != null && tag.getType() == BOOL ? tag.isTrue() == bool : !bool;
            case Integer number -> whole(tag) == number;
            case Float number ->
                Float.compare(tag != null && tag.getType() == FLOAT ? tag.getFloatValue() : 0f, number) == 0;
            case String text -> (tag != null && tag.getType() == STR ? tag.getString() : "").equals(text);
            case float[] place -> Arrays.equals(place(tag), place);
            case Color color -> Arrays.equals(tag == null ? new byte[4] : tag.getRaw(), color.bytes());
            default -> throw malformed("A value of " + value.getClass() + " has no tag");
        };
    }

    private static int whole(@Nullable Property tag) {
        if (tag == null) {
            return 0;
        }
        return switch (tag.getType()) {
            case BYTE -> tag.getByteValue();
            case INT -> tag.getIntValue();
            default -> 0;
        };
    }

    private static float[] place(@Nullable Property tag) {
        if (tag == null || tag.getRaw().length != 12) {
            return new float[3];
        }
        var bytes = tag.getBinary();
        return new float[] {bytes.getFloat(0), bytes.getFloat(4), bytes.getFloat(8)};
    }

    private static boolean isDefault(Object value) {
        return switch (value) {
            case Boolean bool -> !bool;
            case Integer number -> number == 0;
            case Float number -> number == 0;
            case String text -> text.isEmpty();
            case float[] place -> place[0] == 0 && place[1] == 0 && place[2] == 0;
            case Color color -> color.rgb() == 0 && color.alpha() == 0;
            case int[] slots -> Arrays.stream(slots).allMatch(slot -> slot == 0);
            case List<?> list -> list.isEmpty();
            default -> false;
        };
    }

    private static int npcType(Action action) {
        return switch (action) {
            case SPAWN -> 0;
            case DESPAWN -> 1;
            case MOVE -> 2;
            case SKILL -> 3;
            case ANIMATION -> 4;
            case AIRSHIP_SPAWN -> 5;
            case AIRSHIP_MOVE -> 6;
            case ABNORMAL -> 7;
            case EFFECT -> 8;
            default -> -1;
        };
    }

    private static int playerType(Action action) {
        return switch (action) {
            case SPAWN -> 0;
            case DESPAWN -> 1;
            case TELEPORT -> 2;
            case SELECT_TARGET -> 3;
            case EQUIP -> 4;
            case MOVE -> 5;
            case ATTACK -> 6;
            case SKILL -> 7;
            case ANIMATION -> 8;
            case ABNORMAL -> 9;
            case EFFECT -> 10;
            case TELEPORT_BACK -> 11;
            case SELF -> 12;
            default -> -1;
        };
    }

    private static boolean isSameMoments(double[] read, double[] wanted) {
        if (read.length != wanted.length) {
            return false;
        }
        for (var i = 0; i < read.length; i++) {
            if (Math.abs(read[i] - wanted[i]) > READ_BACK) {
                return false;
            }
        }
        return true;
    }

    private static int milliseconds(double seconds) {
        return (int) Math.round(seconds * MILLISECONDS);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static final class Losses {
        private final List<Loss> list = new ArrayList<>();
        private double seconds;

        void at(double moment) {
            seconds = moment;
        }

        void add(String part, String field, Object value) {
            list.add(new Loss(seconds, part, field, text(value)));
        }

        List<Loss> getList() {
            return list;
        }

        private static String text(Object value) {
            return switch (value) {
                case int[] slots -> Arrays.toString(slots);
                case float[] place -> Arrays.toString(place);
                default -> String.valueOf(value);
            };
        }
    }

    private static final class Fields {
        final String part;
        final Losses losses;
        final Map<String, Object> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        @Nullable
        String keepsType;

        Fields(String part, Losses losses) {
            this.part = part;
            this.losses = losses;
        }

        void put(String name, Object value) {
            values.put(name, value);
        }

        @Nullable
        Object get(String name) {
            return values.get(name);
        }

        void lost(String name, Object value) {
            losses.add(part, name, value);
        }
    }

    private record Color(int rgb, int alpha) {
        byte[] bytes() {
            return new byte[] {(byte) rgb, (byte) (rgb >> 8), (byte) (rgb >> 16), (byte) alpha};
        }
    }
}

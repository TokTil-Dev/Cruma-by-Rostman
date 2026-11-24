package com.rost.cruma.viewer.editing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter.Feature;
import com.rost.cruma.core.world.CameraPath;
import com.rost.cruma.core.world.CameraPath.Ease;
import com.rost.cruma.core.world.CameraPath.Key;
import com.rost.cruma.core.world.CameraPath.MapLink;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import com.rost.cruma.core.world.GameScenes.Visual;
import com.rost.cruma.core.world.MapScenes.Vector;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneFields;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class SceneFileStore {
    public static final String EXTENSION = ".scene.json";

    public static final String SCHEMA = "cruma.scene/v6";

    private static final int VERSION = 6;
    private static final Pattern SCHEMAS = Pattern.compile("cruma\\.scene/v(\\d+)");
    private static final String DRAFTS = "scenes";
    private static final String DRAFT = "draft" + EXTENSION;
    private static final String DRAFT_OF = "draft_of";
    private static final Pattern NOT_IN_A_NAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final int LONGEST_NAME = 80;
    private static final String NO_NAME = "scene";
    private static final double HOURS = 24;

    private final Path folder;
    private final Path drafts;

    public SceneFileStore(Path folder, Path drafts) {
        this.folder = folder.toAbsolutePath().normalize();
        this.drafts = drafts.toAbsolutePath().normalize();
    }

    public static SceneFileStore of(Path scenes, Path recovery, Path client) {
        return new SceneFileStore(
                scenes,
                EditingRecovery.clientDirectory(
                        recovery.resolve(DRAFTS), client.toAbsolutePath().normalize()));
    }

    public static final class Refused extends IOException {
        public Refused(String reason) {
            super(reason);
        }

        public Refused(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    public record Listed(Path file, @Nullable SceneDocument document, String problem) {}

    public record Draft(SceneDocument document, @Nullable Path file) {}

    public static SceneDocument read(Path file) throws IOException {
        return parse(text(file), name(file));
    }

    public static void write(Path file, SceneDocument document) throws IOException {
        atomically(file, JSON.toJSONString(json(document), Feature.PrettyFormat));
    }

    public static String safeName(String name) {
        var safe = NOT_IN_A_NAME.matcher(name).replaceAll("_").strip();
        safe = safe.replaceFirst("^[.\\s]+", "").replaceFirst("[.\\s]+$", "");
        if (safe.length() > LONGEST_NAME) {
            safe = safe.substring(0, LONGEST_NAME).strip();
        }
        return safe.isEmpty() ? NO_NAME : safe;
    }

    public static String freeName(Path folder, String name) {
        var safe = safeName(name);
        var free = safe;
        for (var number = 2; Files.exists(folder.resolve(free + EXTENSION)); number++) {
            free = safe + "_" + number;
        }
        return free;
    }

    public Path getFolder() {
        return folder;
    }

    public List<Listed> list() throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        var listed = new ArrayList<Listed>();
        try (var files = Files.list(folder)) {
            for (var file : files.filter(SceneFileStore::isSceneFile)
                    .sorted(Comparator.comparing(file -> name(file).toLowerCase(Locale.ROOT)))
                    .toList()) {
                try {
                    listed.add(new Listed(file, read(file), ""));
                } catch (IOException problem) {
                    listed.add(new Listed(file, null, problem.getMessage()));
                }
            }
        }
        return listed;
    }

    public Path getFile(String name) {
        return folder.resolve(safeName(name) + EXTENSION);
    }

    public String freeName(String name) {
        return freeName(folder, name);
    }

    public void writeDraft(SceneDocument document, @Nullable Path file) throws IOException {
        var json = json(document);
        json.put(DRAFT_OF, file == null ? "" : file.toAbsolutePath().normalize().toString());
        atomically(drafts.resolve(DRAFT), JSON.toJSONString(json, Feature.PrettyFormat));
    }

    public @Nullable Draft readDraft() throws IOException {
        var file = drafts.resolve(DRAFT);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        var text = text(file);
        var document = parse(text, DRAFT);
        var of = JSON.parseObject(text).getString(DRAFT_OF);
        return new Draft(document, of == null || of.isEmpty() ? null : Path.of(of));
    }

    public void deleteDraft() throws IOException {
        Files.deleteIfExists(drafts.resolve(DRAFT));
    }

    private static boolean isSceneFile(Path file) {
        return Files.isRegularFile(file)
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    private static String name(Path file) {
        return file.getFileName().toString();
    }

    private static String text(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException gone) {
            throw new Refused(name(file) + " is not there any more.", gone);
        }
    }

    private static void atomically(Path file, String text) throws IOException {
        var target = file.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".scene-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static SceneDocument parse(String text, String file) throws Refused {
        JSONObject json;
        try {
            json = JSON.parseObject(text);
        } catch (RuntimeException broken) {
            throw new Refused(file + " cannot be read: " + broken.getMessage(), broken);
        }
        if (json == null) {
            throw new Refused(file + " is empty.");
        }
        checkSchema(json.get("schema"), file);
        try {
            return document(json);
        } catch (RuntimeException broken) {
            throw new Refused(file + " cannot be read: " + broken.getMessage(), broken);
        }
    }

    private static void checkSchema(@Nullable Object schema, String file) throws Refused {
        var matched = schema instanceof String text ? SCHEMAS.matcher(text) : null;
        if (matched == null || !matched.matches()) {
            throw new Refused(file + " is not a scene file: its schema is not " + SCHEMA + ".");
        }
        var version = new BigInteger(matched.group(1));
        if (version.compareTo(BigInteger.valueOf(VERSION)) > 0) {
            throw new Refused(file + " comes from a newer Cruma: it is " + schema + ", and this one reads up to "
                    + SCHEMA + ". Update Cruma to open it. The file stays as it is.");
        }
    }

    private static JSONObject json(SceneDocument document) {
        var json = new JSONObject();
        json.put("schema", SCHEMA);
        json.put("name", document.name());
        json.put("based_on", basedOn(document));
        var madeOn = new JSONObject();
        madeOn.put("client", document.client());
        madeOn.put("variant", document.variant());
        json.put("made_on", madeOn);
        json.put("map", document.map());
        if (!document.replaces().isEmpty()) {
            json.put("replaces", document.replaces());
        }
        json.put("seed", document.seed());
        if (!Double.isNaN(document.hour())) {
            json.put("hour", document.hour());
        }
        var items = new JSONArray();
        for (var item : document.items()) {
            items.add(json(item));
        }
        json.put("items", items);
        var paths = new JSONArray();
        for (var path : document.paths()) {
            paths.add(json(path));
        }
        json.put("paths", paths);
        return json;
    }

    private static Object basedOn(SceneDocument document) {
        if (document.basedOn().isEmpty()) {
            return "";
        }
        var json = new JSONObject();
        json.put("class", document.basedOn());
        json.put("sha256", document.basedOnSha256());
        return json;
    }

    private static JSONObject json(Item item) {
        var json = new JSONObject();
        json.put("seconds", item.seconds());
        if (!item.description().isEmpty()) {
            json.put("description", item.description());
        }
        if (!item.source().isEmpty()) {
            json.put("source_item", item.source());
        }
        if (item.playRate() != 0) {
            json.put("play_rate", item.playRate());
        }
        if (item.camera() != null) {
            json.put("camera", json(SceneFields.CAMERA, item.camera()));
        }
        if (!item.actors().isEmpty()) {
            var actors = new JSONArray();
            for (var actor : item.actors()) {
                actors.add(json(SceneFields.ACTOR, actor));
            }
            json.put("actors", actors);
        }
        if (item.screen() != null) {
            json.put("screen", json(SceneFields.SCREEN, item.screen()));
        }
        if (item.music() != null) {
            json.put("music", json(SceneFields.MUSIC, item.music()));
        }
        return json;
    }

    private static <R> JSONObject json(SceneFields<R> table, R record) {
        var json = new JSONObject();
        for (var field : table.getFields()) {
            var value = field.reader().apply(record);
            json.put(
                    field.name(),
                    switch (field.kind()) {
                        case ACTION, SCREEN_KIND -> ((Enum<?>) value).name();
                        case VISUALS -> visuals(value);
                        default -> value;
                    });
        }
        return json;
    }

    private static JSONArray visuals(Object value) {
        var visuals = new JSONArray();
        for (var visual : (List<?>) value) {
            var worn = (Visual) visual;
            var json = new JSONObject();
            json.put("slot", worn.slot());
            json.put("item", worn.item());
            visuals.add(json);
        }
        return visuals;
    }

    private static JSONObject json(CameraPath path) {
        var json = new JSONObject();
        json.put("even_speed", path.hasEvenSpeed());
        var keys = new JSONArray();
        for (var key : path.getKeys()) {
            keys.add(json(key));
        }
        json.put("keys", keys);
        return json;
    }

    private static JSONObject json(Key key) {
        var json = new JSONObject();
        json.put("seconds", key.seconds());
        json.put("x", key.x());
        json.put("y", key.y());
        json.put("z", key.z());
        json.put("yaw", key.yaw());
        json.put("pitch", key.pitch());
        json.put("roll", key.roll());
        json.put("fov", key.fov());
        json.put("ease", key.ease().name().toLowerCase(Locale.ROOT));
        if (key.lookAt() != 0) {
            json.put("look_at", key.lookAt());
        }
        if (key.link() != null) {
            json.put("map_link", json(key.link()));
        }
        return json;
    }

    private static JSONObject json(MapLink link) {
        var json = new JSONObject();
        json.put("action", link.action());
        json.put("fov", link.fov());
        if (link.rides()) {
            json.put("along", link.along());
            json.put("when", link.when());
            json.put("shown", link.shown());
        }
        if (link.leaves() != null && link.reaches() != null) {
            json.put("leaves", json(link.leaves()));
            json.put("reaches", json(link.reaches()));
        }
        return json;
    }

    private static JSONArray json(Vector vector) {
        var json = new JSONArray();
        json.add(vector.x());
        json.add(vector.y());
        json.add(vector.z());
        return json;
    }

    private static SceneDocument document(JSONObject json) {
        var madeOn = json.getJSONObject("made_on");
        var items = new ArrayList<Item>();
        var listed = json.getJSONArray("items");
        if (listed == null || listed.isEmpty()) {
            throw new IllegalArgumentException("it has no items, and a scene needs one");
        }
        for (var index = 0; index < listed.size(); index++) {
            items.add(item(listed.getJSONObject(index)));
        }
        var paths = new ArrayList<CameraPath>();
        var flown = json.getJSONArray("paths");
        if (flown != null) {
            for (var index = 0; index < flown.size(); index++) {
                paths.add(path(flown.getJSONObject(index)));
            }
        }
        var basedOn = json.get("based_on");
        var source = basedOn instanceof JSONObject object ? object : null;
        return new SceneDocument(
                safeName(text(json, "name")),
                source != null ? text(source, "class") : text(json, "based_on"),
                source != null ? text(source, "sha256") : "",
                madeOn == null ? "" : text(madeOn, "client"),
                madeOn == null ? "" : text(madeOn, "variant"),
                text(json, "map"),
                json.getLongValue("seed"),
                items,
                paths,
                text(json, "replaces"),
                hour(json));
    }

    private static double hour(JSONObject json) {
        if (!json.containsKey("hour")) {
            return Double.NaN;
        }
        var hour = number(json, "hour");
        if (hour < 0 || hour > HOURS) {
            throw new IllegalArgumentException("the field hour needs a number from 0 to 24");
        }
        return hour;
    }

    private static String text(JSONObject json, String field) {
        var text = json.getString(field);
        return text == null ? "" : text;
    }

    private static Item item(JSONObject json) {
        var actors = new ArrayList<Actor>();
        var orders = json.getJSONArray("actors");
        if (orders != null) {
            for (var index = 0; index < orders.size(); index++) {
                actors.add(record(SceneFields.ACTOR, orders.getJSONObject(index)));
            }
        }
        var shot = json.getJSONObject("camera");
        var screen = json.getJSONObject("screen");
        var music = json.getJSONObject("music");
        return new Item(
                number(json, "seconds"),
                text(json, "description"),
                shot == null ? null : record(SceneFields.CAMERA, olderShot(shot)),
                actors,
                screen == null ? null : record(SceneFields.SCREEN, olderScreen(screen)),
                music == null ? null : record(SceneFields.MUSIC, olderMusic(music)),
                json.getDoubleValue("play_rate"),
                text(json, "source_item"));
    }

    private static <R> R record(SceneFields<R> table, JSONObject json) {
        var fields = table.getFields();
        var values = new Object[fields.size()];
        for (var index = 0; index < values.length; index++) {
            var name = fields.get(index).name();
            values[index] = switch (fields.get(index).kind()) {
                case WHOLE -> json.getIntValue(name);
                case NUMBER -> json.getDoubleValue(name);
                case SWITCH -> json.getBooleanValue(name);
                case TEXT -> text(json, name);
                case ACTION -> Action.valueOf(text(json, name));
                case SCREEN_KIND -> ScreenKind.valueOf(text(json, name));
                case VISUALS -> visuals(json.getJSONArray(name));
                case SLOTS -> slots(json.getJSONArray(name));
            };
        }
        return table.make(values);
    }

    private static List<Visual> visuals(@Nullable JSONArray json) {
        if (json == null) {
            return List.of();
        }
        var visuals = new ArrayList<Visual>(json.size());
        for (var index = 0; index < json.size(); index++) {
            var worn = json.getJSONObject(index);
            visuals.add(new Visual(worn.getIntValue("slot"), worn.getIntValue("item")));
        }
        return visuals;
    }

    private static List<Integer> slots(@Nullable JSONArray json) {
        if (json == null) {
            return List.of();
        }
        var slots = new ArrayList<Integer>(json.size());
        for (var index = 0; index < json.size(); index++) {
            slots.add(json.getIntValue(index));
        }
        return slots;
    }

    private static JSONObject olderShot(JSONObject json) {
        if (!json.containsKey("mode") && json.containsKey("specialView")) {
            json.put("mode", json.getBooleanValue("specialView") ? 0 : 1);
        }
        return json;
    }

    private static JSONObject olderScreen(JSONObject json) {
        if (!json.containsKey("delayed") && json.getBooleanValue("waits")) {
            json.put("delayed", 1);
        }
        return json;
    }

    private static JSONObject olderMusic(JSONObject json) {
        if (!json.containsKey("level") && json.containsKey("volume")) {
            json.put("level", Math.round(json.getDoubleValue("volume") * 255));
        } else if (!json.containsKey("volume")) {
            json.put("volume", Music.volumeOf(json.getIntValue("level")));
        }
        return json;
    }

    private static CameraPath path(JSONObject json) {
        var keys = new ArrayList<Key>();
        var listed = json.getJSONArray("keys");
        if (listed == null || listed.isEmpty()) {
            throw new IllegalArgumentException("a path has no keys");
        }
        for (var index = 0; index < listed.size(); index++) {
            keys.add(key(listed.getJSONObject(index)));
        }
        return new CameraPath(keys, json.getBooleanValue("even_speed"));
    }

    private static Key key(JSONObject json) {
        var ease = text(json, "ease");
        var roll = json.getDouble("roll");
        var lookAt = json.getInteger("look_at");
        return new Key(
                number(json, "seconds"),
                number(json, "x"),
                number(json, "y"),
                number(json, "z"),
                number(json, "yaw"),
                number(json, "pitch"),
                roll == null ? 0 : roll,
                number(json, "fov"),
                ease.isEmpty() ? Ease.SMOOTH : Ease.valueOf(ease.toUpperCase(Locale.ROOT)),
                lookAt == null ? 0 : lookAt,
                link(json.getJSONObject("map_link")));
    }

    private static @Nullable MapLink link(@Nullable JSONObject json) {
        if (json == null) {
            return null;
        }
        return new MapLink(
                json.getIntValue("action"),
                json.getDoubleValue("along"),
                json.getDoubleValue("when"),
                !json.containsKey("shown") || json.getBooleanValue("shown"),
                number(json, "fov"),
                vector(json.getJSONArray("leaves")),
                vector(json.getJSONArray("reaches")));
    }

    private static @Nullable Vector vector(@Nullable JSONArray json) {
        if (json == null) {
            return null;
        }
        if (json.size() != 3) {
            throw new IllegalArgumentException("a control point needs three numbers");
        }
        return new Vector(json.getDoubleValue(0), json.getDoubleValue(1), json.getDoubleValue(2));
    }

    private static double number(JSONObject json, String field) {
        var number = json.getDouble(field);
        if (number == null || !Double.isFinite(number)) {
            throw new IllegalArgumentException("the field " + field + " needs a number");
        }
        return number;
    }
}

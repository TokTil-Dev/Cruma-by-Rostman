package com.rost.cruma.viewer.editing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.viewer.editing.EditingSession.MaterialChoice;
import com.rost.cruma.viewer.editing.EditingSession.Placement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class ActorClipboard {
    static final String KIND = "actors";

    static final int VERSION = 1;

    private ActorClipboard() {}

    public record Clip(float base, List<Item> items) {
        public Clip {
            items = List.copyOf(items);
        }
    }

    public record Item(
            String mesh,
            @Nullable String packageFile,
            Vector3 offset,
            Rotator rotation,
            float drawScale,
            Vector3 drawScale3d,
            List<@Nullable Material> materials) {
        public Item {
            materials = Collections.unmodifiableList(new ArrayList<>(materials));
        }
    }

    public record Material(String path, @Nullable String packageFile) {}

    public static String encode(Clip clip) {
        var root = new JSONObject();
        root.put("cruma", KIND);
        root.put("version", VERSION);
        root.put("base", clip.base());
        var actors = new JSONArray();
        for (var item : clip.items()) {
            var actor = new JSONObject();
            actor.put("mesh", item.mesh());
            if (item.packageFile() != null) {
                actor.put("package", item.packageFile());
            }
            actor.put("offset", vector(item.offset()));
            actor.put(
                    "rotation",
                    JSONArray.of(
                            item.rotation().pitch(),
                            item.rotation().yaw(),
                            item.rotation().roll()));
            actor.put("drawScale", item.drawScale());
            actor.put("drawScale3D", vector(item.drawScale3d()));
            var materials = new JSONArray();
            for (var material : item.materials()) {
                if (material == null) {
                    materials.add(null);
                } else {
                    var json = new JSONObject();
                    json.put("material", material.path());
                    if (material.packageFile() != null) {
                        json.put("package", material.packageFile());
                    }
                    materials.add(json);
                }
            }
            actor.put("materials", materials);
            actors.add(actor);
        }
        root.put("actors", actors);
        return JSON.toJSONString(root);
    }

    public static Clip decode(String text) {
        JSONObject root;
        try {
            root = JSON.parseObject(text);
        } catch (JSONException | ClassCastException notJson) {
            throw new IllegalArgumentException("The text is not copied actors.");
        }
        if (root == null || !KIND.equals(root.getString("cruma"))) {
            throw new IllegalArgumentException("The text is not copied actors.");
        }
        if (root.getIntValue("version") > VERSION) {
            throw new IllegalArgumentException("These actors were copied by a newer Cruma.");
        }
        var actors = root.getJSONArray("actors");
        if (actors == null || actors.isEmpty()) {
            throw new IllegalArgumentException("The copied text holds no actors.");
        }
        var items = new ArrayList<Item>();
        for (var i = 0; i < actors.size(); i++) {
            var actor = actors.getJSONObject(i);
            var mesh = actor.getString("mesh");
            if (mesh == null || mesh.indexOf('.') <= 0) {
                throw new IllegalArgumentException("A copied actor has no mesh.");
            }
            var rotation = actor.getJSONArray("rotation");
            var materials = new ArrayList<@Nullable Material>();
            var list = actor.getJSONArray("materials");
            for (var k = 0; list != null && k < list.size(); k++) {
                var json = list.getJSONObject(k);
                materials.add(
                        json == null || json.getString("material") == null
                                ? null
                                : new Material(json.getString("material"), json.getString("package")));
            }
            items.add(new Item(
                    mesh,
                    actor.getString("package"),
                    vector(actor.getJSONArray("offset"), Vector3.ZERO),
                    rotation == null || rotation.size() < 3
                            ? new Rotator(0, 0, 0)
                            : new Rotator(rotation.getIntValue(0), rotation.getIntValue(1), rotation.getIntValue(2)),
                    actor.containsKey("drawScale") ? actor.getFloatValue("drawScale") : 1,
                    vector(actor.getJSONArray("drawScale3D"), Vector3.ONE),
                    materials));
        }
        return new Clip(root.getFloatValue("base"), items);
    }

    public static @Nullable Clip of(
            EditingSession session,
            Collection<String> actors,
            Vector3 pivot,
            @Nullable Float bottom,
            Function<String, @Nullable Path> packageOf) {
        var items = new ArrayList<Item>();
        for (var path : actors) {
            var actor = session.getActor(path);
            var mesh = actor.meshSourcePath();
            if (mesh.isBlank() || actor.pawn()) {
                continue;
            }
            var t = actor.transform();
            var materials = new ArrayList<@Nullable Material>();
            for (var reference : actor.skinReferences()) {
                if (reference == 0) {
                    materials.add(null);
                } else {
                    var material = session.getMaterialName(path, reference);
                    materials.add(new Material(material, text(packageOf.apply(material))));
                }
            }
            var location = t.location();
            items.add(new Item(
                    mesh,
                    text(packageOf.apply(mesh)),
                    new Vector3(location.x() - pivot.x(), location.y() - pivot.y(), location.z() - pivot.z()),
                    t.rotation(),
                    t.drawScale(),
                    t.drawScale3d(),
                    materials));
        }
        return items.isEmpty() ? null : new Clip(bottom == null ? 0 : pivot.z() - bottom, items);
    }

    public static List<Placement> placements(Clip clip, Vector3 point, Function<Vector3, String> mapOf) {
        var placements = new ArrayList<Placement>();
        for (var item : clip.items()) {
            var o = item.offset();
            var location = new Vector3(point.x() + o.x(), point.y() + o.y(), point.z() + clip.base() + o.z());
            var materials = new ArrayList<@Nullable MaterialChoice>();
            for (var material : item.materials()) {
                materials.add(
                        material == null
                                ? null
                                : new MaterialChoice(material.path(), existing(material.packageFile())));
            }
            placements.add(new Placement(
                    mapOf.apply(location),
                    item.mesh(),
                    existing(item.packageFile()),
                    new ActorTransform(location, item.rotation(), item.drawScale(), item.drawScale3d(), Vector3.ZERO),
                    materials,
                    false));
        }
        return placements;
    }

    public static Path prefabFolder() {
        return Path.of(System.getProperty("user.home"))
                .resolve("Documents")
                .resolve("Cruma")
                .resolve("Prefabs");
    }

    public static List<Path> prefabs(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    public static Path prefabFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                ? file
                : file.resolveSibling(file.getFileName() + ".json");
    }

    static boolean looksLikeClip(@Nullable String text) {
        return text != null && text.contains("\"cruma\"") && text.contains("\"" + KIND + "\"");
    }

    private static @Nullable Path existing(@Nullable String file) {
        if (file == null) {
            return null;
        }
        try {
            var path = Path.of(file);
            return Files.isRegularFile(path) ? path : null;
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    private static @Nullable String text(@Nullable Path file) {
        return file == null ? null : file.toString();
    }

    private static JSONArray vector(Vector3 value) {
        return JSONArray.of(value.x(), value.y(), value.z());
    }

    private static Vector3 vector(@Nullable JSONArray json, Vector3 fallback) {
        if (json == null || json.size() < 3) {
            return fallback;
        }
        var result = new Vector3(json.getFloatValue(0), json.getFloatValue(1), json.getFloatValue(2));
        if (!Float.isFinite(result.x()) || !Float.isFinite(result.y()) || !Float.isFinite(result.z())) {
            throw new IllegalArgumentException("A copied actor has a value that is not a number.");
        }
        return result;
    }
}

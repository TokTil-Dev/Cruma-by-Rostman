package com.rost.cruma.viewer.agent.view;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorRecord;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.agent.Chosen;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.Target.Kind;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.view.Eyes.Ray;
import com.rost.cruma.viewer.editing.EditorUi;
import com.rost.cruma.viewer.render.EditorEffectStage;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.DebugSelection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class WorldTools implements ToolGroup {
    private static final double DEFAULT_RADIUS = 2000;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MOST_LIMIT = 500;
    private static final int MOST_POINTS = 512;
    private static final int MOST_HIGH_POINTS = 48;
    private static final int MOST_LINES = 32;
    private static final int TILE_SIZE = 32768;
    private static final int COMMON_SHOWN = 8;
    private static final int HERE = 100;
    private static final int PLAIN_PATH_ACTORS = 20;
    private static final double LINE_SLACK = 2;
    private static final float LOW = -1_000_000f;
    private static final float HIGH = 1_000_000f;
    private static final Pattern LINE = Pattern.compile("\\s*(.+?)\\s*(?:;|->)\\s*(.+?)\\s*");
    private static final String MERGED_NOTE =
            "The viewer draws %d of them merged with others of their tile, as it does outside Edit. They keep their"
                    + " names, and edit_mode gives each actor a transform of its own.";
    private static final String[] COLUMNS = {"name", "mesh", "class", "point", "distance", "tile"};
    private static final String EFFECT = "effect";

    record Place(Bounds3f box, double x, double y, @Nullable Double z, double radius) {
        boolean holds(ScenePlacement placement) {
            return radius <= 0 || distanceTo(placement) <= radius;
        }

        double distanceTo(ScenePlacement placement) {
            return placement.distanceTo(x, y, z == null ? placement.bounds().centerZ() : z);
        }
    }

    static Place box(String text) throws ToolProblem {
        var parts = text.split("[,\\s]+");
        var values = new float[parts.length];
        try {
            for (var index = 0; index < parts.length; index++) {
                values[index] = Float.parseFloat(parts[index]);
            }
        } catch (NumberFormatException wrong) {
            throw new ToolProblem("box takes numbers: x1,y1,x2,y2 or x1,y1,z1,x2,y2,z2.");
        }
        Bounds3f box;
        if (values.length == 4) {
            box = new Bounds3f(
                    Math.min(values[0], values[2]),
                    Math.min(values[1], values[3]),
                    LOW,
                    Math.max(values[0], values[2]),
                    Math.max(values[1], values[3]),
                    HIGH);
        } else if (values.length == 6) {
            box = new Bounds3f(
                    Math.min(values[0], values[3]),
                    Math.min(values[1], values[4]),
                    Math.min(values[2], values[5]),
                    Math.max(values[0], values[3]),
                    Math.max(values[1], values[4]),
                    Math.max(values[2], values[5]));
        } else {
            throw new ToolProblem("box takes x1,y1,x2,y2 or x1,y1,z1,x2,y2,z2.");
        }
        return new Place(box, box.centerX(), box.centerY(), null, 0);
    }

    static @Nullable String lobbyAt(double x, double y, Set<String> maps) {
        var map = WorldTile.mapAt(x, y, maps);
        return maps.contains(map) && !WorldTile.isMapName(map) ? map : null;
    }

    static String where(String point, String tile, @Nullable String lobby, boolean loaded) {
        if (lobby != null) {
            return "Point " + point + ", map " + lobby + ", which stands on the place of tile " + tile;
        }
        return "Point " + point + ", tile " + tile + (loaded ? "" : ", which this viewer does not hold");
    }

    static void write(ActorRecord record, @Nullable String moves, JSONObject answer) {
        answer.put("class", record.className());
        var mesh = record.meshSourcePath();
        if (record.emitter()) {
            answer.put("category", EFFECT);
            answer.remove("mesh");
            answer.remove("package");
            answer.remove("materials");
            answer.remove("lighting");
        } else {
            answer.put("mesh", mesh);
            if (mesh != null) {
                answer.put("package", ActorBook.mapOf(mesh));
            }
        }
        var transform = record.transform();
        var place = transform.location();
        answer.put("location", Math.round(place.x()) + "," + Math.round(place.y()) + "," + Math.round(place.z()));
        var turn = transform.rotation();
        answer.put("rotation", turn.pitch() + "," + turn.yaw() + "," + turn.roll());
        answer.put("scale", transform.drawScale());
        var scale3d = transform.drawScale3d();
        answer.put("scale3d", scale3d.x() + "," + scale3d.y() + "," + scale3d.z());
        answer.put("editable", record.editable());
        if (record.restriction() != null && !record.restriction().isBlank()) {
            answer.put("restriction", record.restriction());
        }
        if (moves != null) {
            answer.put("moves", moves);
        }
        if (record.emitter()) {
            answer.put("scale_note", PackageEditDocument.EFFECT_SCALE);
        }
    }

    static JSONObject answer(List<Hit> hits, @Nullable String countBy, int limit) {
        var found = new ArrayList<>(hits);
        found.sort(Comparator.comparingDouble(Hit::distance));
        var answer = new JSONObject();
        answer.put("found", found.size());
        if (countBy != null) {
            census(answer, found, countBy, limit);
            return answer;
        }
        var rows = new ArrayList<List<Object>>();
        for (var hit : found.subList(0, Math.min(limit, found.size()))) {
            var row = new ArrayList<Object>();
            row.add(hit.name());
            row.add(hit.mesh() == null ? EFFECT : hit.mesh());
            row.add(hit.className());
            row.add(hit.point());
            row.add(Math.round(hit.distance()));
            row.add(hit.tile());
            rows.add(row);
        }
        answer.put("actors", WorldFacts.table(List.of(COLUMNS), rows));
        if (found.size() > limit) {
            answer.put("more", found.size() - limit);
        }
        return answer;
    }

    @Override
    public void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "actors_find",
                                "Find actors",
                                "Lists what stands near a target or in a box, by name, mesh, class or category,"
                                        + " nearest first. With count_by it gives a census in place of a list.")
                        .target("Where to look. A tile means all of it. The camera when left out.")
                        .number("radius", "Units around the target. 2000 when left out.")
                        .text("box", "A box in place of the target: x1,y1,x2,y2 or x1,y1,z1,x2,y2,z2.")
                        .text("name", "Words of the actor's name.")
                        .text("mesh", "Words of the mesh.")
                        .text("class", "Words of the class.")
                        .choice(
                                "category",
                                "What to read. Meshes and effects when left out.",
                                "static_mesh",
                                "decoration",
                                "csg",
                                "terrain",
                                "effect")
                        .choice("count_by", "Count in place of a list.", "mesh", "class", "tile")
                        .integer("limit", "How many lines, at most " + MOST_LIMIT + ". 50 when left out."),
                (args, viewer) -> ViewScript.start(viewer, script -> find(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "actor_get",
                                "Read an actor",
                                "All about one actor: class, mesh, package, materials, transform and whether it can be"
                                        + " edited. In Edit also how it moves by itself and what carries it (moves)."
                                        + " An effect has the category effect and no mesh.")
                        .target("The actor: its path, a mark or the selection.")
                        .required("target"),
                (args, viewer) -> ViewScript.start(viewer, script -> actor(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "world_probe",
                                "Probe",
                                "The ground at many points, and what lines hit, in one call. It reads what is really"
                                        + " drawn, the Terrain and the geodata.")
                        .list(
                                "points",
                                "Points or targets. Without a height it reads the top surface there. With a height it"
                                        + " reads the first surface under that point.")
                        .list(
                                "lines",
                                "Lines as two points or targets with a semicolon between them, such as"
                                        + " 100,200,300;400,200,300.")
                        .flag("objects", "Name what each point stands on. It costs more time."),
                (args, viewer) -> ViewScript.start(viewer, script -> probe(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "world_describe",
                                "Describe a place",
                                "A place in about ten lines: tile, nearest named place, ground, hour and what stands"
                                        + " around it. Cheaper than a picture.")
                        .target("What to describe. The camera when left out.")
                        .number("radius", "Units around the target to count. 2000 when left out."),
                (args, viewer) -> ViewScript.start(viewer, script -> describe(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "selection",
                                "Selection",
                                "Reads what the person chose in any tool: an object, the selection of Edit, a geodata"
                                    + " cell or a spawn. With set, it chooses an object and flies there for the person"
                                    + " to see.")
                        .text("set", "What to choose: an actor path or a mark. A place only flies there."),
                (args, viewer) -> ViewScript.start(viewer, script -> selection(script, args)));
    }

    private static ToolResult find(ViewScript script, ToolArgs args) throws Exception {
        var limit = Math.clamp(args.getInteger("limit", DEFAULT_LIMIT), 1, MOST_LIMIT);
        var categoryName = WorldFacts.choice(args, "category", "static_mesh", "decoration", "csg", "terrain", EFFECT);
        Eyes.ready(script);
        var place = place(script, args);
        var book = script.read(WorldFacts::book);
        var hits = new ArrayList<Hit>();
        if (categoryName == null || EFFECT.equals(categoryName)) {
            hits.addAll(effects(script, book, args, place));
        }
        var merged = 0L;
        var grass = new int[1];
        if (!EFFECT.equals(categoryName)) {
            var meshCategory = Objects.requireNonNullElse(categoryName, "static_mesh");
            var decoration = "decoration".equals(meshCategory);
            var category = decoration ? Category.STATIC_MESH : Category.valueOf(meshCategory.toUpperCase(Locale.ROOT));
            var read = Eyes.placements(script, place.box(), row -> {
                if (decoration) {
                    return ScenePlacement.isDecoration(row);
                }
                if (category != Category.STATIC_MESH) {
                    return row.category() == category;
                }
                grass[0] += ScenePlacement.isDecoration(row) ? 1 : 0;
                return ScenePlacement.isObject(row);
            });
            for (var placement : read) {
                if (place.holds(placement)) {
                    var named = WorldFacts.named(book, placement);
                    if (named.matches(args.getText("name"), args.getText("mesh"), args.getText("class"))) {
                        hits.add(Hit.of(named, place.distanceTo(named)));
                        merged += named.merged() ? 1 : 0;
                    }
                }
            }
        }
        var answer = answer(hits, WorldFacts.choice(args, "count_by", "mesh", "class", "tile"), limit);
        if (merged > 0) {
            answer.put("note", String.format(MERGED_NOTE, merged));
        }
        if (grass[0] > 0) {
            answer.put("decoration", grass[0]);
        }
        return ToolResult.data(answer);
    }

    record Hit(String name, @Nullable String mesh, String className, String point, double distance, String tile) {
        static Hit of(ScenePlacement placement, double distance) {
            return new Hit(
                    placement.name(),
                    placement.mesh(),
                    placement.className(),
                    placement.point(),
                    distance,
                    placement.tile());
        }

        static Hit ofEffect(String path, @Nullable String className, float[] at, double distance) {
            var tile = WorldTile.at(at[0], at[1]);
            return new Hit(
                    path,
                    null,
                    className != null ? className : ScenePlacement.classOf(ScenePlacement.shortName(path)),
                    Math.round(at[0]) + "," + Math.round(at[1]) + "," + Math.round(at[2]),
                    distance,
                    tile.x() + "_" + tile.y());
        }

        boolean matches(@Nullable String wantedName, @Nullable String wantedMesh, @Nullable String wantedClass) {
            return holds(name, wantedName)
                    && (wantedMesh == null || mesh != null && holds(mesh, wantedMesh))
                    && holds(className, wantedClass);
        }

        private static boolean holds(String text, @Nullable String wanted) {
            return wanted == null || text.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
        }
    }

    private static void census(JSONObject answer, List<Hit> found, String countBy, int limit) {
        var keys = new ArrayList<String>(found.size());
        for (var hit : found) {
            keys.add(
                    switch (countBy) {
                        case "class" -> hit.className();
                        case "tile" -> hit.tile();
                        default -> hit.mesh() == null ? EFFECT : hit.mesh();
                    });
        }
        var census = WorldFacts.census(keys);
        var rows = new ArrayList<List<Object>>();
        for (var entry : census.entrySet()) {
            if (rows.size() == limit) {
                break;
            }
            rows.add(List.of(entry.getKey(), entry.getValue()));
        }
        answer.put("kinds", census.size());
        answer.put("count", WorldFacts.table(List.of(countBy, "count"), rows));
        if (census.size() > limit) {
            answer.put("more", census.size() - limit);
        }
    }

    private static List<Hit> effects(ViewScript script, ActorBook book, ToolArgs args, Place place)
            throws ToolProblem, InterruptedException {
        var found = script.read(viewer -> {
            var actors = new LinkedHashMap<String, float[]>();
            for (var emitter : viewer.scene().particles.getEmitters()) {
                var actor = emitter.getRenderActorName();
                var at = emitter.getActorPos();
                if (actor != null) {
                    actors.putIfAbsent(actor, new float[] {at.getX(), at.getY(), at.getZ()});
                }
            }
            return actors;
        });
        var hits = new ArrayList<Hit>();
        for (var entry : found.entrySet()) {
            var at = entry.getValue();
            var distance = Math.hypot(at[0] - place.x(), at[1] - place.y());
            if (at[0] < place.box().minX()
                    || at[0] > place.box().maxX()
                    || at[1] < place.box().minY()
                    || at[1] > place.box().maxY()
                    || place.radius() > 0 && distance > place.radius()) {
                continue;
            }
            var record = book.getActor(entry.getKey());
            var hit = Hit.ofEffect(entry.getKey(), record == null ? null : record.className(), at, distance);
            if (hit.matches(args.getText("name"), args.getText("mesh"), args.getText("class"))) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private static Place place(ViewScript script, ToolArgs args) throws ToolProblem, InterruptedException {
        var box = args.getText("box");
        if (box != null) {
            return box(box);
        }
        var text = args.getText("target", "camera");
        var found = script.read(viewer -> viewer.located(text));
        var target = found.target();
        if (target.kind() == Kind.TILE) {
            var left = target.x() - TILE_SIZE / 2f;
            var top = target.y() - TILE_SIZE / 2f;
            return new Place(
                    new Bounds3f(left, top, LOW, left + TILE_SIZE, top + TILE_SIZE, HIGH),
                    target.x(),
                    target.y(),
                    null,
                    0);
        }
        var radius = args.getNumber("radius", DEFAULT_RADIUS);
        var z = target.text().strip().equalsIgnoreCase("camera") ? null : target.z();
        var reach = (float) radius;
        return new Place(
                new Bounds3f(
                        target.x() - reach,
                        target.y() - reach,
                        z == null ? LOW : z - reach,
                        target.x() + reach,
                        target.y() + reach,
                        z == null ? HIGH : z + reach),
                target.x(),
                target.y(),
                z == null ? null : Double.valueOf(z),
                radius);
    }

    private static ToolResult actor(ViewScript script, ToolArgs args) throws Exception {
        var text = args.getRequired("target");
        Eyes.ready(script);
        var found = script.read(viewer -> viewer.located(text));
        var row = found.object();
        if (row == null) {
            throw new ToolProblem(
                    text + " is a place, not an object. Call actors_find near it, or view_look with marks.");
        }
        var placement = ScenePlacement.of(row);
        var answer = new JSONObject();
        answer.put("name", placement.name());
        var box = found.bounds();
        answer.put("box", bounds(box));
        answer.put("tile", placement.tile());
        answer.put("map", row.owner());
        if (EditorEffectStage.isEffectRow(row)) {
            answer.put("category", EFFECT);
            return effect(script, placement, answer);
        }
        answer.put("category", placement.category().name().toLowerCase(Locale.ROOT));
        answer.put("lighting", row.lighting());
        var materials = new JSONArray();
        for (var slot : row.materials()) {
            var material = new JSONObject();
            material.put("slot", slot.slot());
            material.put("name", slot.name());
            material.put("type", slot.materialType());
            material.put("blend", slot.blend());
            materials.add(material);
        }
        answer.put("materials", materials);
        if (placement.actor() == null) {
            answer.put("mesh", placement.mesh());
            answer.put(
                    "note", "This has no actor: it is part of the map itself, such as a BSP surface or a water sheet.");
            return ToolResult.data(answer);
        }
        if (placement.merged()) {
            answer.put(
                    "merged",
                    "The viewer draws this merged with others of its tile, as it does outside Edit. edit_mode gives"
                            + " the actor a transform of its own.");
        }
        var path = placement.actor();
        var live = script.read(viewer -> {
            if (viewer.editing() == null) {
                return Optional.<ActorRecord>empty();
            }
            try {
                return Optional.of(viewer.editing().getActor(path));
            } catch (IllegalArgumentException notInEdit) {
                return Optional.<ActorRecord>empty();
            }
        });
        var moves = script.read(viewer -> {
            var session = viewer.editing();
            return session == null || live.isEmpty()
                    ? Optional.<String>empty()
                    : Optional.ofNullable(session.getMotionDescription(path));
        });
        var book = script.read(WorldFacts::book);
        var record = live.isPresent() ? live.get() : book.getActor(path);
        if (record == null) {
            answer.put("mesh", placement.mesh());
            answer.put("class", placement.className());
            var reason = EditorUi.uneditable(path);
            if (reason == null) {
                answer.put("note", "The map has no saved actor with this path.");
            } else {
                answer.put("editable", false);
                answer.put("restriction", reason);
            }
            return ToolResult.data(answer);
        }
        write(record, moves.orElse(null), answer);
        answer.put("source", live.isPresent() ? "the open Edit session" : "the saved map");
        return ToolResult.data(answer);
    }

    private static ToolResult effect(ViewScript script, ScenePlacement placement, JSONObject answer)
            throws ToolProblem, InterruptedException {
        var path = placement.actor();
        var live = script.read(viewer -> {
            var session = viewer.editing();
            if (session == null || path == null) {
                return Optional.<ActorRecord>empty();
            }
            try {
                return Optional.of(session.getActor(path));
            } catch (IllegalArgumentException notInEdit) {
                return Optional.<ActorRecord>empty();
            }
        });
        if (live.isEmpty()) {
            var book = script.read(WorldFacts::book);
            var saved = path == null ? null : book.getActor(path);
            if (saved == null) {
                answer.put("note", "The map has no saved actor with this path.");
                return ToolResult.data(answer);
            }
            write(saved, null, answer);
            answer.put("category", EFFECT);
            answer.remove("mesh");
            answer.remove("package");
            answer.put("source", "the saved map");
            return ToolResult.data(answer);
        }
        var moves = script.read(viewer -> {
            var session = viewer.editing();
            return session == null ? Optional.<String>empty() : Optional.ofNullable(session.getMotionDescription(path));
        });
        write(live.get(), moves.orElse(null), answer);
        answer.put("source", "the open Edit session");
        return ToolResult.data(answer);
    }

    private static String bounds(Bounds3f box) {
        return Math.round(box.minX())
                + ","
                + Math.round(box.minY())
                + ","
                + Math.round(box.minZ())
                + " to "
                + Math.round(box.maxX())
                + ","
                + Math.round(box.maxY())
                + ","
                + Math.round(box.maxZ());
    }

    private static ToolResult probe(ViewScript script, ToolArgs args) throws Exception {
        var points = args.list("points");
        var lines = args.list("lines");
        if (points.isEmpty() && lines.isEmpty()) {
            throw new ToolProblem("Give points, lines or both.");
        }
        if (points.size() > MOST_POINTS || lines.size() > MOST_LINES) {
            throw new ToolProblem("A call takes at most " + MOST_POINTS + " points and " + MOST_LINES + " lines.");
        }
        Eyes.ready(script);
        script.wantDepth();
        var answer = new JSONObject();
        if (!points.isEmpty()) {
            answer.put("points", points(script, points, args.readFlag("objects", false)));
        }
        if (!lines.isEmpty() && script.read(viewer -> viewer.agent().isHeadless())) {
            throw new ToolProblem(
                    "A line reads a drawn frame, and this viewer has no graphics card. Give points: they answer with"
                            + " Terrain and geodata here.");
        }
        if (!lines.isEmpty()) {
            answer.put("lines", lines(script, lines));
        }
        return ToolResult.data(answer);
    }

    private static JSONObject points(ViewScript script, List<String> texts, boolean named)
            throws ToolProblem, InterruptedException {
        var targets = new ArrayList<Target>();
        for (var text : texts) {
            targets.add(script.read(viewer -> viewer.locate(text)));
        }
        var surfaces = surfaces(script, targets);
        var xs = new double[targets.size()];
        var ys = new double[targets.size()];
        for (var index = 0; index < targets.size(); index++) {
            xs[index] = targets.get(index).x();
            ys[index] = targets.get(index).y();
        }
        var terrains = Eyes.terrains(script, xs, ys);
        var regions = WorldFacts.regions(script);
        var rows = new ArrayList<List<Object>>();
        var columns = new ArrayList<>(List.of("point", "surface", "terrain", "geodata", "tile"));
        if (named) {
            columns.add("object");
        }
        for (var index = 0; index < targets.size(); index++) {
            var target = targets.get(index);
            var surface = surfaces[index];
            var terrain = terrains[index];
            var heights = WorldFacts.geodata(regions, (int) target.x(), (int) target.y());
            var geodata = WorldFacts.nearest(
                    heights, Double.isFinite(surface) ? surface : Double.isFinite(terrain) ? terrain : 0);
            var tile = WorldTile.at(target.x(), target.y());
            var row = new ArrayList<Object>();
            row.add(Math.round(target.x())
                    + ","
                    + Math.round(target.y())
                    + (target.z() == null ? "" : "," + Math.round(target.z())));
            row.add(Double.isFinite(surface) ? Double.valueOf(rounded(surface)) : null);
            row.add(Double.isFinite(terrain) ? Double.valueOf(rounded(terrain)) : null);
            row.add(geodata);
            row.add(tile.x() + "_" + tile.y());
            if (named) {
                row.add(Double.isFinite(surface) ? under(script, target, surface) : null);
            }
            rows.add(row);
        }
        return WorldFacts.table(columns, rows);
    }

    private static double[] surfaces(ViewScript script, List<Target> targets) throws ToolProblem, InterruptedException {
        var result = new double[targets.size()];
        var plain = new ArrayList<Integer>();
        var high = 0;
        for (var index = 0; index < targets.size(); index++) {
            var target = targets.get(index);
            if (target.z() == null) {
                plain.add(index);
            } else if (high++ < MOST_HIGH_POINTS) {
                result[index] = Eyes.below(script, target.x(), target.y(), target.z());
            } else {
                throw new ToolProblem("Each point with a height costs a view. A call takes at most " + MOST_HIGH_POINTS
                        + " of them. Leave the height out for the top surface.");
            }
        }
        var xs = new double[plain.size()];
        var ys = new double[plain.size()];
        for (var position = 0; position < plain.size(); position++) {
            xs[position] = targets.get(plain.get(position)).x();
            ys[position] = targets.get(plain.get(position)).y();
        }
        if (!plain.isEmpty()) {
            var found = Eyes.surfaces(script, xs, ys);
            for (var position = 0; position < plain.size(); position++) {
                result[plain.get(position)] = found[position];
            }
        }
        return result;
    }

    private static @Nullable String under(ViewScript script, Target target, double surface)
            throws ToolProblem, InterruptedException {
        var object = Eyes.hitObject(script, new Ray(target.x(), target.y(), (float) surface + 1, 0, 0, -1, 1, 0, 0));
        return object == null ? null : object.name();
    }

    private static JSONObject lines(ViewScript script, List<String> texts) throws ToolProblem, InterruptedException {
        var rows = new ArrayList<List<Object>>();
        for (var text : texts) {
            var match = LINE.matcher(text);
            if (!match.matches()) {
                throw new ToolProblem("A line is two points with a semicolon between them: " + text);
            }
            var from = script.read(viewer -> viewer.locate(match.group(1)));
            var to = script.read(viewer -> viewer.locate(match.group(2)));
            rows.add(line(script, text, from, to));
        }
        return WorldFacts.table(List.of("line", "length", "blocked", "point", "distance", "object"), rows);
    }

    private static List<Object> line(ViewScript script, String text, Target from, Target to)
            throws ToolProblem, InterruptedException {
        var fromZ = Eyes.height(script, from);
        var toZ = Eyes.height(script, to);
        var length =
                Math.sqrt(Math.pow(to.x() - from.x(), 2) + Math.pow(to.y() - from.y(), 2) + Math.pow(toZ - fromZ, 2));
        Eyes.move(script, Pose.looking(from.x(), from.y(), fromZ, to.x(), to.y(), toZ));
        script.frames(Eyes.SETTLE_FRAMES);
        var lens = script.read(viewer -> Lens.of(viewer.scene().camera));
        var end = lens.project(to.x(), to.y(), toZ);
        var row = new ArrayList<Object>();
        row.add(text);
        row.add(Math.round(length));
        if (end == null) {
            throw new ToolProblem("The line " + text + " has no length.");
        }
        var ray =
                Eyes.rays(script, new float[] {(float) end[0], (float) end[1]}).getFirst();
        if (!ray.hit() || ray.distance() >= length - LINE_SLACK) {
            row.addAll(Arrays.asList(false, null, null, null));
            return row;
        }
        var object = Eyes.hitObject(script, ray);
        row.add(true);
        row.add(Math.round(ray.hitX()) + "," + Math.round(ray.hitY()) + "," + Math.round(ray.hitZ()));
        row.add(Math.round(ray.distance()));
        row.add(object == null ? null : object.name());
        return row;
    }

    private static double rounded(double height) {
        return Math.round(height * 10) / 10.0;
    }

    private static ToolResult describe(ViewScript script, ToolArgs args) throws Exception {
        var text = args.getText("target", "camera");
        var radius = args.getNumber("radius", DEFAULT_RADIUS);
        Eyes.ready(script);
        script.wantDepth();
        var target = script.read(viewer -> viewer.locate(text));
        var lines = new ArrayList<String>();
        var tile = WorldTile.at(target.x(), target.y());
        var tileName = tile.x() + "_" + tile.y();
        var maps = script.read(viewer -> viewer.loading().getLoadedMaps());
        var lobby = lobbyAt(target.x(), target.y(), maps);
        var loaded = lobby != null || script.read(viewer -> viewer.scene().tiles.has(tileName));
        var surface = target.z() == null
                ? Eyes.surfaces(script, new double[] {target.x()}, new double[] {target.y()})[0]
                : Eyes.below(script, target.x(), target.y(), target.z() + 1);
        script.run(script::putCameraBack);
        var terrain = Eyes.terrain(script, target.x(), target.y());
        var geodata = WorldFacts.geodata(WorldFacts.regions(script), (int) target.x(), (int) target.y());
        var z = Double.isFinite(surface) ? surface : terrain != null ? terrain : target.z() != null ? target.z() : 0;
        lines.add(where(
                Math.round(target.x()) + "," + Math.round(target.y()) + "," + Math.round(z), tileName, lobby, loaded));
        lines.add(script.read(viewer -> nearestPlace(viewer, target)));
        lines.add(ground(surface, terrain, geodata));
        lines.add(script.read(WorldTools::hour));
        if (loaded) {
            census(script, target, z, radius, lines);
        } else {
            lines.add("Open this tile with world_open to count what stands here.");
        }
        return ToolResult.text(String.join("\n", lines));
    }

    private static String nearestPlace(ViewerContext viewer, Target target) {
        var places = viewer.camera().locations();
        var best = places.stream()
                .min(Comparator.comparingDouble(place -> Math.hypot(place.x() - target.x(), place.y() - target.y())));
        if (best.isEmpty()) {
            return "No named place is known.";
        }
        var place = best.get();
        var dx = place.x() - target.x();
        var dy = place.y() - target.y();
        var distance = Math.round(Math.hypot(dx, dy));
        if (distance < HERE) {
            return "Place: " + place.name();
        }
        return "Nearest place: "
                + place.name()
                + ", "
                + distance
                + " units to the "
                + ViewTools.compass(Math.toDegrees(Math.atan2(dy, dx)));
    }

    private static String ground(double surface, @Nullable Double terrain, int @Nullable [] geodata) {
        var parts = new ArrayList<String>();
        if (Double.isFinite(surface)) {
            parts.add("surface " + Math.round(surface));
        }
        if (terrain != null) {
            parts.add("Terrain " + Math.round(terrain));
        }
        if (geodata != null) {
            var layers = new ArrayList<String>();
            for (var height : geodata) {
                layers.add(Integer.toString(height));
            }
            parts.add("geodata " + String.join(" and ", layers));
        }
        return parts.isEmpty() ? "Ground: nothing is drawn here" : "Ground: " + String.join(", ", parts);
    }

    private static String hour(ViewerContext viewer) {
        var rendering = viewer.ui().rendering;
        return String.format(
                "Hour %.1f, view %s",
                rendering.timeOfDay.get(),
                viewer.scene().getViewMode().getLabel().toLowerCase(Locale.ROOT));
    }

    private static void census(ViewScript script, Target target, double z, double radius, List<String> lines)
            throws ToolProblem, InterruptedException {
        var reach = (float) radius;
        var box =
                new Bounds3f(target.x() - reach, target.y() - reach, LOW, target.x() + reach, target.y() + reach, HIGH);
        var read = Eyes.placements(script, box, ScenePlacement::isObject);
        var book = script.read(WorldFacts::book);
        var meshes = new ArrayList<String>();
        var classes = new ArrayList<String>();
        for (var found : read) {
            if (found.distanceTo(target.x(), target.y(), found.bounds().centerZ()) > radius) {
                continue;
            }
            var placement = WorldFacts.named(book, found);
            meshes.add(placement.mesh());
            classes.add(placement.className());
        }
        var kinds = WorldFacts.census(meshes);
        lines.add(
                "Within " + Math.round(radius) + " units: " + meshes.size() + " objects of " + kinds.size() + " kinds");
        if (!kinds.isEmpty()) {
            lines.add("Most common: " + common(kinds));
            lines.add("Classes: " + common(WorldFacts.census(classes)));
        }
        var effects = script.read(viewer -> {
            var actors = new LinkedHashSet<String>();
            for (var emitter : viewer.scene().particles.getEmitters()) {
                var at = emitter.getActorPos();
                if (Math.hypot(at.getX() - target.x(), at.getY() - target.y()) <= radius
                        && emitter.getRenderActorName() != null) {
                    actors.add(emitter.getRenderActorName());
                }
            }
            return actors.size();
        });
        lines.add("Effects: " + effects);
    }

    private static String common(Map<String, Integer> census) {
        var parts = new ArrayList<String>();
        for (var entry : census.entrySet()) {
            if (parts.size() == COMMON_SHOWN) {
                break;
            }
            parts.add(entry.getKey() + " " + entry.getValue());
        }
        return String.join(", ", parts);
    }

    private static ToolResult selection(ViewScript script, ToolArgs args) throws Exception {
        var text = args.getText("set");
        Eyes.ready(script);
        if (text != null) {
            choose(script, text);
            script.frames(2);
        }
        return ToolResult.data(script.read(WorldTools::chosen));
    }

    private static void choose(ViewScript script, String text) throws ToolProblem, InterruptedException {
        var found = script.read(viewer -> viewer.located(text));
        if (found.object() != null) {
            script.run(viewer -> viewer.system()
                    .getWorkspace()
                    .selectAndFocus(DebugSelection.scene(found.object(), 0f), found.bounds()));
            return;
        }
        var pose = Eyes.aimed(
                script, found, null, script.read(viewer -> viewer.scene().camera.getYawDegrees()), -20);
        script.run(viewer -> pose.applyTo(viewer.scene().camera));
    }

    private static JSONObject chosen(ViewerContext viewer) {
        var chosen = Chosen.current(viewer);
        var answer = new JSONObject();
        if (chosen == null) {
            answer.put("kind", "nothing");
            answer.put("note", "The person chose nothing. Ask them to click something, or call selection with set.");
            return answer;
        }
        answer.put("kind", chosen.kind());
        answer.putAll(chosen.details());
        answer.put("point", Math.round(chosen.x()) + "," + Math.round(chosen.y()) + "," + Math.round(chosen.z()));
        var session = viewer.editing();
        if (session != null && session.getSelection().size() > 1) {
            var paths = new JSONArray();
            for (var path : session.getSelection()) {
                if (paths.size() == PLAIN_PATH_ACTORS) {
                    break;
                }
                paths.add(path);
            }
            answer.put("actors", paths);
        }
        return answer;
    }
}

package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.TerrainBrushStamp;
import com.rost.cruma.core.editing.TerrainBrushes;
import com.rost.cruma.core.editing.TerrainBrushes.Falloff;
import com.rost.cruma.core.editing.TerrainBrushes.RampPoint;
import com.rost.cruma.core.editing.TerrainBrushes.Shape;
import com.rost.cruma.core.editing.TerrainDecorationSources;
import com.rost.cruma.core.editing.TerrainDecorationSources.Decoration;
import com.rost.cruma.core.editing.TerrainHeightField;
import com.rost.cruma.core.editing.TerrainHeightField.Brush;
import com.rost.cruma.core.editing.TerrainLayerMapping;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainPackageSnapshot;
import com.rost.cruma.core.editing.TerrainPaintHistory;
import com.rost.cruma.core.editing.TerrainPaintHistory.Edit;
import com.rost.cruma.core.editing.TerrainPaintHistory.Layer;
import com.rost.cruma.core.editing.TerrainQuadHistory;
import com.rost.cruma.core.editing.TerrainSoftSelection;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingTimeline;
import com.rost.cruma.viewer.editing.OpenGround;
import com.rost.cruma.viewer.editing.TerrainDocument;
import com.rost.cruma.viewer.editing.TerrainEditingController;
import com.rost.cruma.viewer.editing.TerrainEditingController.Capability;
import com.rost.cruma.viewer.editing.TerrainNeighbors;
import com.rost.cruma.viewer.editing.TerrainViewportSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class TerrainEdit implements Step {
    static final List<String> ACTIONS = List.of(
            "raise",
            "lower",
            "flatten",
            "smooth",
            "noise",
            "set_height",
            "ramp",
            "select",
            "paint",
            "erase",
            "fill",
            "hide",
            "show",
            "turn",
            "restore",
            "mapping",
            "revert",
            "info");

    private static final long WORK_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final float DEFAULT_RADIUS = 512;
    private static final float NOISE_AMOUNT = 64;
    private static final float NOISE_WIDTH = 512;
    private static final float RAMP_WIDTH = 512;
    private static final float OUTER_EDGE = 512;
    private static final float MOST_RADIUS = 256;
    private static final float MOST_RAMP = 512;
    private static final float MOST_EDGE = 64;
    private static final int MOST_POINTS = 256;
    private static final int MOST_STAMPS = 4096;

    private static final Set<String> STROKES = Set.of(
            "raise",
            "lower",
            "flatten",
            "smooth",
            "noise",
            "set_height",
            "paint",
            "erase",
            "hide",
            "show",
            "turn",
            "restore");
    private static final Set<String> CROSSING =
            Set.of("raise", "lower", "flatten", "smooth", "noise", "set_height", "ramp");
    private static final Set<String> BRUSHES = Set.of(
            "raise",
            "lower",
            "flatten",
            "smooth",
            "noise",
            "set_height",
            "select",
            "paint",
            "erase",
            "hide",
            "show",
            "turn",
            "restore");

    private final ViewerContext viewer;
    private final EditingSession session;
    private final TerrainViewportSession terrain;
    private final TerrainEditingController controller;
    private final String action;
    private final Target target;
    private final List<Target> points;
    private final @Nullable Double radius;
    private final @Nullable Integer size;
    private final @Nullable Double amount;
    private final @Nullable Double height;
    private final @Nullable Double strength;
    private final @Nullable Double width;
    private final @Nullable String layer;
    private final @Nullable String decoration;
    private final boolean cover;
    private final @Nullable Double slope;
    private final float @Nullable [] between;
    private final float @Nullable [] edge;
    private final float @Nullable [] scale;
    private final float @Nullable [] pan;
    private final @Nullable Double rotation;
    private final @Nullable String what;
    private final Falloff falloff;
    private final Step reading;
    private final AtomicReference<Owner> owner = new AtomicReference<>();
    private final long deadline = System.nanoTime() + WORK_NANOS;
    private Stage stage = Stage.READING;
    private final JSONObject answer = new JSONObject();
    private String label = "";
    private boolean stroke;
    private final List<float[]> corners = new ArrayList<>();
    private float brushRadius;
    private float middleX;
    private float middleY;
    private int chosenLayer = -1;
    private @Nullable Decoration chosenDecoration;
    private @Nullable AtomicInteger texels;
    private @Nullable Object newestBefore;
    private final List<Beside> besides = new ArrayList<>();
    private @Nullable Step besideReading;

    private TerrainEdit(
            ToolArgs args,
            ViewerContext viewer,
            EditingSession session,
            String action,
            Target target,
            List<Target> points,
            Path root,
            String map)
            throws ToolProblem {
        this.viewer = viewer;
        this.session = session;
        this.action = action;
        this.target = target;
        this.points = points;
        terrain = viewer.scene().getTerrainEditing();
        controller = terrain.getController();
        radius = args.getNumber("radius");
        if (radius != null && !(radius > 0)) {
            throw new ToolProblem("radius must be above 0.");
        }
        var letter = args.getText("size");
        size = letter == null ? null : sizePoints(letter);
        if (radius != null && size != null) {
            throw new ToolProblem("Give radius or size, not both.");
        }
        amount = args.getNumber("amount");
        height = args.getNumber("height");
        strength = args.getNumber("strength");
        if (strength != null && (strength < 0 || strength > 1)) {
            throw new ToolProblem("strength must be between 0 and 1.");
        }
        width = args.getNumber("width");
        if (width != null && !(width > 0)) {
            throw new ToolProblem("width must be above 0.");
        }
        layer = args.getText("layer");
        decoration = args.getText("decoration");
        if (layer != null && decoration != null) {
            throw new ToolProblem("Give layer or decoration, not both.");
        }
        cover = args.readFlag("cover", false);
        slope = args.getNumber("slope");
        if (slope != null && (slope < 0 || slope > 89)) {
            throw new ToolProblem("slope must be from 0 to 89 degrees.");
        }
        between = numbers(args, "between");
        if (between != null && between.length != 2) {
            throw new ToolProblem("between must be two world heights, such as [-3300, -3100].");
        }
        edge = numbers(args, "edge");
        scale = numbers(args, "scale");
        pan = numbers(args, "pan");
        rotation = args.getNumber("rotation");
        var named = args.getText("what");
        what = named == null && decoration != null && action.equals("revert") ? "decoration" : named;
        falloff = falloff(args.getText("falloff", "smooth"));
        check();
        reading = Step.onWorker(() -> {
            owner.set(chooseOwner(root, map, target.x(), target.y()));
            return ToolResult.text("");
        });
    }

    static Step start(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var action = canonical(
                args.getRequired("action").toLowerCase(Locale.ROOT),
                args.has("layer") || args.has("decoration") || args.has("slope") || args.has("between"));
        if (!ACTIONS.contains(action)) {
            throw new ToolProblem("action must be one of " + String.join(", ", ACTIONS) + ".");
        }
        var target = EditSteps.locate(viewer, session, args.getRequired("target"));
        var points = new ArrayList<Target>();
        for (var text : pointTexts(args)) {
            points.add(EditSteps.locate(viewer, session, text));
        }
        var loaded = EditSteps.loadedMaps(viewer, session);
        var map = WorldTile.mapAt(target.x(), target.y(), loaded);
        if (!loaded.contains(map)) {
            throw new ToolProblem("The map " + map + " is not loaded in this viewer. Open it with world_open.");
        }
        return EditSteps.editing(
                viewer,
                session,
                () -> new TerrainEdit(args, viewer, session, action, target, points, session.getClientRoot(), map));
    }

    static String canonical(String action, boolean layerFill) {
        return switch (action) {
            case "hole" -> "hide";
            case "fill" -> layerFill ? "fill" : "show";
            default -> action;
        };
    }

    static int sizePoints(String letter) throws ToolProblem {
        return switch (letter.strip().toUpperCase(Locale.ROOT)) {
            case "S" -> 4;
            case "M" -> 8;
            case "L" -> 16;
            default -> throw new ToolProblem("size must be S, M or L.");
        };
    }

    static float nearestSample(float place, int samples) {
        return Math.clamp(Math.round(place), 0, samples - 1);
    }

    static float room(List<float[]> corners, float radius) {
        var length = length(corners);
        var count = Math.ceil(length / Math.max(.25f, radius * .25f));
        return count < 1 ? 0 : (float) (length / count);
    }

    static List<float[]> strokePlaces(List<float[]> corners, float radius) {
        var places = new ArrayList<float[]>();
        places.add(corners.getFirst().clone());
        var room = room(corners, radius);
        if (room <= 0) {
            return places;
        }
        var length = length(corners);
        var count = (int) Math.round(length / room);
        var segment = 0;
        var walked = 0d;
        for (var step = 1; step <= count; step++) {
            var at = step == count ? length : step * (double) room;
            while (segment < corners.size() - 2
                    && walked + distance(corners.get(segment), corners.get(segment + 1)) < at) {
                walked += distance(corners.get(segment), corners.get(segment + 1));
                segment++;
            }
            var a = corners.get(segment);
            var b = corners.get(segment + 1);
            var span = distance(a, b);
            var t = span < 1e-9 ? 1 : Math.clamp((at - walked) / span, 0, 1);
            places.add(new float[] {(float) (a[0] + (b[0] - a[0]) * t), (float) (a[1] + (b[1] - a[1]) * t)});
        }
        return places;
    }

    static float overlap(Falloff falloff, float room, float radius) {
        if (!(room > 0)) {
            return 1;
        }
        var sum = 0d;
        for (var step = 0; step * room < radius; step++) {
            sum += falloff.getWeight(1 - step * room / radius) * (step == 0 ? 1 : 2);
        }
        return (float) sum;
    }

    static float rotationUnits(double degrees) {
        return (float) (degrees % 360 * TerrainLayerMapping.FULL_TURN / 360);
    }

    static List<Layer> layersOf(List<Layer> layers, String text) {
        var wanted = text.strip().toLowerCase(Locale.ROOT);
        var found = new ArrayList<Layer>();
        var number = wanted.matches("\\d+");
        for (var item : layers) {
            if (number
                    ? String.valueOf(item.index()).equals(wanted)
                    : item.texture().toLowerCase(Locale.ROOT).contains(wanted)) {
                found.add(item);
            }
        }
        return found;
    }

    static List<Decoration> decorationsOf(List<Decoration> decorations, String text) {
        var wanted = text.strip().toLowerCase(Locale.ROOT);
        var found = new ArrayList<Decoration>();
        var number = wanted.matches("\\d+");
        for (var item : decorations) {
            if (item.densityMap().isEmpty()) {
                continue;
            }
            if (number
                    ? String.valueOf(item.index()).equals(wanted)
                    : item.mesh().toLowerCase(Locale.ROOT).contains(wanted)) {
                found.add(item);
            }
        }
        return found;
    }

    static String decorationName(Decoration decoration) {
        var mesh = decoration.mesh();
        return "decoration " + decoration.index() + " (" + mesh.substring(mesh.lastIndexOf('.') + 1) + ")";
    }

    @Override
    public @Nullable ToolResult step() throws ToolProblem {
        if (terrain.isBusy()) {
            if (System.nanoTime() > deadline) {
                throw new ToolProblem("The Terrain worker did not end in time.");
            }
            return null;
        }
        switch (stage) {
            case READING -> {
                if (reading.step() == null) {
                    return null;
                }
                open();
                return null;
            }
            case OPENING -> {
                requireNoError();
                stage = Stage.OPENED;
                return null;
            }
            case OPENED -> {
                return change();
            }
            case BESIDE_READING -> {
                var reading = besideReading;
                if (reading != null && reading.step() == null) {
                    return null;
                }
                openBesides();
                return null;
            }
            case BESIDE_OPENING -> {
                for (var beside : besides) {
                    if (beside.answer != null && !beside.answer.isDone()) {
                        return null;
                    }
                }
                for (var beside : besides) {
                    if (beside.answer != null) {
                        beside.why = beside.answer.join();
                    }
                }
                requireNoError();
                return act();
            }
            case STROKE -> {
                requireNoError();
                terrain.finishStroke(label);
                stage = Stage.FINISHING;
                return null;
            }
            case FINISHING -> {
                requireNoError();
                return finished();
            }
        }
        return null;
    }

    @Override
    public void cancel() {
        if (stroke && !terrain.isBusy()) {
            terrain.cancelStroke();
        }
    }

    private void check() throws ToolProblem {
        switch (action) {
            case "raise", "lower" -> {
                if (amount == null || !(amount > 0)) {
                    throw new ToolProblem(action + " needs amount: the world units at the middle, above 0.");
                }
            }
            case "select" -> {
                if (amount == null || amount == 0) {
                    throw new ToolProblem(
                            "select needs amount: how many world units to move the selected ground, below 0 to lower"
                                    + " it.");
                }
            }
            case "ramp" -> {
                if (points.size() != 1) {
                    throw new ToolProblem("ramp needs its end as the one target in points.");
                }
            }
            case "fill" -> {
                if (layer == null && decoration == null || (slope == null) == (between == null)) {
                    throw new ToolProblem("fill needs layer or decoration and either slope, to Fill by slope, or"
                            + " between, to Fill by height.");
                }
            }
            case "mapping" -> {
                if (decoration != null) {
                    throw new ToolProblem("mapping changes a layer of the ground, not a decoration.");
                }
                if (layer == null || scale == null && pan == null && rotation == null) {
                    throw new ToolProblem("mapping needs layer and scale, pan or rotation.");
                }
            }
            case "revert" -> {
                if (what == null
                        || !List.of("heights", "layer", "mapping", "edges", "decoration")
                                .contains(what)) {
                    throw new ToolProblem("revert needs what: heights, layer, mapping, edges or decoration.");
                }
                if (what.equals("decoration") && decoration == null) {
                    throw new ToolProblem("revert of a decoration needs decoration.");
                }
                if (!what.equals("decoration") && decoration != null) {
                    throw new ToolProblem("decoration goes with what decoration, or with no what.");
                }
                if ((what.equals("layer") || what.equals("mapping")) && layer == null) {
                    throw new ToolProblem("revert of a " + what + " needs layer.");
                }
            }
            default -> {}
        }
        if (edge != null && (edge[0] < 0 || edge[edge.length - 1] < 0)) {
            throw new ToolProblem("edge must be one or two world units of 0 or more.");
        }
        if (amount != null && !Double.isFinite(amount)) {
            throw new ToolProblem("amount must be a number.");
        }
    }

    private enum Stage {
        READING,
        OPENING,
        OPENED,
        BESIDE_READING,
        BESIDE_OPENING,
        STROKE,
        FINISHING
    }

    private record Owner(Path map, String path) {}

    private static final class Beside {
        final String map;
        final float x;
        final float y;

        @Nullable
        Owner owner;

        @Nullable
        CompletableFuture<String> answer;

        String why = "";

        Beside(String map, float x, float y) {
            this.map = map;
            this.x = x;
            this.y = y;
        }
    }

    private void requireNoError() throws ToolProblem {
        var error = terrain.getError();
        if (!error.isEmpty()) {
            throw new ToolProblem(error);
        }
    }

    private void open() throws ToolProblem {
        var chosen = owner.get();
        try {
            if (controller.findDocument(chosen.path()) != null) {
                terrain.activate(chosen.path());
                stage = Stage.OPENED;
                return;
            }
            terrain.open(chosen.map(), chosen.path());
        } catch (IllegalStateException | IllegalArgumentException refused) {
            throw new ToolProblem(refused.getMessage());
        }
        stage = Stage.OPENING;
    }

    private TerrainDocument document() throws ToolProblem {
        var chosen = owner.get();
        var document = chosen == null ? null : controller.findDocument(chosen.path());
        if (document == null) {
            throw new ToolProblem("The Terrain " + (chosen == null ? "" : chosen.path()) + " is not open.");
        }
        return document;
    }

    private String path() {
        return owner.get().path();
    }

    private @Nullable ToolResult change() throws ToolProblem {
        if (controller.findDocument(path()) == null) {
            throw new ToolProblem("The Terrain " + path() + " did not open.");
        }
        var info = document().getInfo();
        var spacing = TerrainEditingController.sampleSpacing(info);
        brushRadius = size != null ? size : (float) ((radius == null ? DEFAULT_RADIUS : radius) / spacing);
        if (BRUSHES.contains(action) && brushRadius > MOST_RADIUS) {
            throw new ToolProblem(
                    "radius is too large: at most " + Math.round(MOST_RADIUS * spacing) + " units on this Terrain.");
        }
        place(info);
        if (CROSSING.contains(action) && readBesides(info, spacing)) {
            stage = Stage.BESIDE_READING;
            return null;
        }
        return act();
    }

    private boolean readBesides(Info info, float spacing) {
        var reach = action.equals("ramp")
                ? (float) ((width == null ? RAMP_WIDTH : width) / spacing) * .5f
                        + Math.max(1, (float) ((width == null ? RAMP_WIDTH : width) / spacing) * .25f)
                : brushRadius;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        var places = action.equals("ramp") ? List.of(corners.getFirst(), corners.getLast()) : corners;
        for (var corner : places) {
            minX = Math.min(minX, corner[0]);
            minY = Math.min(minY, corner[1]);
            maxX = Math.max(maxX, corner[0]);
            maxY = Math.max(maxY, corner[1]);
        }
        var loaded = EditSteps.loadedMaps(viewer, session);
        var files = session.getMapFiles();
        for (var beyond : TerrainNeighbors.mapsBeyond(info, minX - reach, minY - reach, maxX + reach, maxY + reach)) {
            var map = files.getSource(beyond.map());
            var open = false;
            for (var document : controller.getDocuments()) {
                open |= document.getMap().equalsIgnoreCase(map);
            }
            if (open) {
                continue;
            }
            var beside = new Beside(map, beyond.x(), beyond.y());
            if (!loaded.contains(map)) {
                beside.why = "The map " + map + " is not loaded in this viewer. Open it with world_open.";
            }
            besides.add(beside);
        }
        if (besides.isEmpty()) {
            return false;
        }
        var root = session.getClientRoot();
        var toWorld = info.toWorld();
        besideReading = Step.onWorker(() -> {
            for (var beside : besides) {
                if (!beside.why.isEmpty()) {
                    continue;
                }
                var point = toWorld.transform(beside.x, beside.y, 0);
                try {
                    beside.owner = chooseOwner(root, beside.map, point.x(), point.y());
                } catch (ToolProblem | IOException | RuntimeException refused) {
                    beside.why =
                            refused.getMessage() == null ? refused.getClass().getSimpleName() : refused.getMessage();
                }
            }
            return ToolResult.text("");
        });
        return true;
    }

    private void openBesides() {
        for (var beside : besides) {
            if (beside.owner != null && beside.why.isEmpty()) {
                beside.answer = terrain.openBeside(beside.owner.map(), beside.owner.path(), path());
            }
        }
        stage = Stage.BESIDE_OPENING;
    }

    private @Nullable ToolResult act() throws ToolProblem {
        var info = document().getInfo();
        var spacing = TerrainEditingController.sampleSpacing(info);
        var world = info.toWorld().transform(middleX, middleY, 0);
        answer.put("terrain", path());
        answer.put("spacing", EditSteps.round(spacing));
        answer.put("middle", EditSteps.round(world.x()) + "," + EditSteps.round(world.y()));
        if (BRUSHES.contains(action)) {
            answer.put("radius", EditSteps.round(brushRadius * spacing));
        }
        if (!points.isEmpty() && !action.equals("ramp")) {
            answer.put("stamps", strokePlaces(corners, brushRadius).size());
        }
        var done = done();
        newestBefore = done.isEmpty() ? null : done.getFirst();
        switch (action) {
            case "info" -> {
                return ToolResult.data(info(spacing));
            }
            case "raise", "lower", "flatten", "smooth", "noise", "set_height" -> sculpt(info, spacing);
            case "ramp" -> ramp(spacing);
            case "select" -> select(info, spacing);
            case "paint", "erase" -> {
                if (decoration != null) {
                    paintDecoration();
                } else {
                    paint();
                }
            }
            case "fill" -> {
                if (decoration != null) {
                    fillDecoration();
                } else {
                    fill();
                }
            }
            case "mapping" -> mapping();
            case "revert" -> revert();
            default -> quads();
        }
        stroke = STROKES.contains(action);
        stage = stroke ? Stage.STROKE : Stage.FINISHING;
        return null;
    }

    private void place(Info info) throws ToolProblem {
        var all = new ArrayList<Target>();
        all.add(target);
        all.addAll(points);
        var crossing = CROSSING.contains(action);
        for (var index = 0; index < all.size(); index++) {
            var point = all.get(index);
            var sample = info.toHeightmap().transform(point.x(), point.y(), 0);
            var low = crossing ? -info.width() : 0;
            var highX = crossing ? 2 * info.width() - 1 : info.width() - 1;
            var highY = crossing ? 2 * info.height() - 1 : info.height() - 1;
            if (index > 0 && (sample.x() < low || sample.y() < low || sample.x() > highX || sample.y() > highY)) {
                throw new ToolProblem("Point "
                        + index
                        + " of points, "
                        + point.text()
                        + (crossing ? ", lies more than one map away from the Terrain " : ", lies off the Terrain ")
                        + path()
                        + ".");
            }
            corners.add(
                    crossing && index > 0
                            ? new float[] {Math.round(sample.x()), Math.round(sample.y())}
                            : new float[] {
                                nearestSample(sample.x(), info.width()), nearestSample(sample.y(), info.height())
                            });
        }
        middleX = corners.getFirst()[0];
        middleY = corners.getFirst()[1];
    }

    private JSONObject info(float spacing) throws ToolProblem {
        var data = new JSONObject(answer);
        var layers = new JSONArray();
        var document = document();
        var paint = document.getPaint();
        var mapping = document.getMapping();
        if (paint != null) {
            for (var item : paint.getLayers()) {
                var row = new JSONObject();
                row.put("layer", item.index());
                row.put("texture", item.texture());
                row.put("paintable", item.isPaintable());
                if (!item.isPaintable()) {
                    row.put("restriction", item.restriction());
                } else {
                    row.put("here", paint.getWeightAt(item.index(), middleX, middleY));
                }
                var mapped = mapping == null ? null : mapping.getMapping(item.index());
                if (mapped != null) {
                    row.put("mapping", mappingOf(mapped));
                }
                layers.add(row);
            }
        } else {
            data.put("paint", document.getPaintError());
        }
        data.put("layers", layers);
        var density = document.getDecorations();
        var sources = document.getDecorationSources();
        if (density != null && sources != null) {
            var decorations = new JSONArray();
            for (var item : sources.decorations()) {
                var row = new JSONObject();
                row.put("decoration", item.index());
                row.put("mesh", item.mesh());
                row.put("density_map", item.densityMap());
                var holder = item.slot() < 0 ? null : sources.holders().get(item.slot());
                row.put(
                        "package",
                        holder != null
                                ? holder.file().getFileName().toString()
                                : item.densityMap()
                                        .substring(
                                                0, Math.max(0, item.densityMap().indexOf('.'))));
                row.put("paintable", item.isPaintable());
                if (item.isPaintable()) {
                    row.put("density", densityHere(item));
                    if (!item.getAlsoChanges().isEmpty()) {
                        row.put("also_changes", item.getAlsoChanges());
                    }
                } else {
                    row.put("restriction", item.restriction());
                }
                decorations.add(row);
            }
            data.put("decorations", decorations);
        } else {
            data.put(
                    "decorations_error",
                    document.getDecorationError().isEmpty()
                            ? "The decorations are still being read."
                            : document.getDecorationError());
        }
        var quads = document.getQuads();
        if (quads == null) {
            data.put("holes", document.getQuadsError());
        } else {
            data.put("hidden", hiddenQuads(quads));
            data.put("turned", quads.getChangedTurns().cardinality());
        }
        data.put("changed_points", document.getHistory().getSourceChanges().size());
        var open = new JSONArray();
        for (var each : controller.getDocuments()) {
            open.add(each.getOwner());
        }
        data.put("open", open);
        var sizes = new JSONObject();
        for (var letter : List.of("S", "M", "L")) {
            try {
                sizes.put(letter, EditSteps.round(sizePoints(letter) * spacing));
            } catch (ToolProblem impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        data.put("sizes", sizes);
        data.put("ground", EditSteps.round(heightAt()));
        return data;
    }

    private List<TerrainBrushStamp> stamps(float power) throws ToolProblem {
        var places = strokePlaces(corners, brushRadius);
        if (places.size() > MOST_STAMPS) {
            throw new ToolProblem("The stroke needs " + places.size() + " stamps, more than " + MOST_STAMPS
                    + ". Use a larger radius or split it into calls.");
        }
        var stamps = new ArrayList<TerrainBrushStamp>();
        for (var place : places) {
            stamps.add(new TerrainBrushStamp(place[0], place[1], power));
        }
        return stamps;
    }

    private void sculpt(Info info, float spacing) throws ToolProblem {
        var unit = TerrainEditingController.rawUnit(info);
        var raw = (int) Math.round(ground().getSurfaceAt(middleX, middleY));
        if (height != null && (action.equals("flatten") || action.equals("set_height"))) {
            var world = info.toWorld().transform(middleX, middleY, 0);
            raw = Math.round(info.toHeightmap()
                    .transform(world.x(), world.y(), height.floatValue())
                    .z());
        }
        Brush brush;
        float power = strength == null ? 1 : strength.floatValue();
        var amplitude = 0f;
        var bumps = 1f;
        switch (action) {
            case "raise", "lower" -> {
                brush = action.equals("raise") ? Brush.RAISE : Brush.LOWER;
                var total = (float) (amount / unit);
                if (total > 65535) {
                    throw new ToolProblem("amount is too large: this Terrain holds " + Math.round(65535 * unit)
                            + " units from low to high.");
                }
                power = total / overlap(falloff, room(corners, brushRadius), brushRadius);
                label = "AI: " + (action.equals("raise") ? "Raise ground" : "Lower ground");
            }
            case "flatten" -> {
                brush = Brush.FLATTEN;
                label = "AI: Flatten ground";
            }
            case "set_height" -> {
                brush = Brush.SET_HEIGHT;
                power = 1;
                label = "AI: Set ground height";
            }
            case "noise" -> {
                brush = Brush.NOISE;
                amplitude = (float) ((amount == null ? NOISE_AMOUNT : amount) / unit);
                bumps = (float) Math.max(.5, (width == null ? NOISE_WIDTH : width) / spacing);
                label = "AI: Add noise to ground";
            }
            default -> {
                brush = Brush.SMOOTH;
                label = "AI: Smooth ground";
            }
        }
        answer.put("before", EditSteps.round(heightAt()));
        var shape = new Shape(
                brush,
                brushRadius,
                Math.clamp(raw, 0, 65535),
                falloff,
                ThreadLocalRandom.current().nextLong(),
                amplitude,
                bumps);
        terrain.sculpt(path(), shape, stamps(power));
    }

    private void ramp(float spacing) throws ToolProblem {
        var wide = (float) ((width == null ? RAMP_WIDTH : width) / spacing);
        if (wide > MOST_RAMP) {
            throw new ToolProblem(
                    "width is too large: at most " + Math.round(MOST_RAMP * spacing) + " units on this Terrain.");
        }
        var a = corners.getFirst();
        var b = corners.getLast();
        var ground = OpenGround.ofHistories(document(), controller.getDocuments());
        var info = document().getInfo();
        var from = new RampPoint(a[0], a[1], rawAt(ground, info, a[0], a[1]));
        var to = new RampPoint(b[0], b[1], rawAt(ground, info, b[0], b[1]));
        answer.put("from", EditSteps.round(ground.getWorldAt(a[0], a[1]).z()));
        answer.put("to", EditSteps.round(ground.getWorldAt(b[0], b[1]).z()));
        middleX = Math.round((a[0] + b[0]) * .5f);
        middleY = Math.round((a[1] + b[1]) * .5f);
        var halfway = info.toWorld().transform(middleX, middleY, 0);
        answer.put("middle", EditSteps.round(halfway.x()) + "," + EditSteps.round(halfway.y()));
        answer.put("before", EditSteps.round(heightAt()));
        label = "AI: Ramp ground";
        terrain.ramp(path(), from, to, wide, falloff, label);
    }

    private void select(Info info, float spacing) throws ToolProblem {
        var selection = new TerrainSoftSelection(info.width(), info.height());
        for (var place : stamps(1)) {
            selection.add(place.x(), place.y(), brushRadius);
        }
        if (selection.isEmpty()) {
            throw new ToolProblem("The brush selected no ground. Give a larger radius.");
        }
        var limit = MOST_EDGE * spacing;
        var inner = edge == null || edge.length < 2 ? 0 : Math.clamp(edge[0], 0, limit);
        var outer = edge == null ? OUTER_EDGE : Math.clamp(edge[edge.length - 1], 0, limit);
        answer.put("selected", selection.size());
        answer.put("edge", JSONArray.of(EditSteps.round(inner), EditSteps.round(outer)));
        answer.put("before", EditSteps.round(heightAt()));
        label = "AI: Move selection";
        terrain.moveSelection(
                path(),
                selection.computeWeights(inner / spacing, outer / spacing),
                (float) (amount / TerrainEditingController.rawUnit(info)),
                label);
    }

    private TerrainPaintHistory requirePaint() throws ToolProblem {
        var document = document();
        var paint = document.getPaint();
        if (paint == null) {
            throw new ToolProblem("The layers of this Terrain cannot be painted: " + document.getPaintError());
        }
        return paint;
    }

    private void paint() throws ToolProblem {
        var paint = requirePaint();
        var chosen = layer(paint.getLayers(), true);
        var mode = action.equals("paint") ? TerrainPaintHistory.Brush.PAINT : TerrainPaintHistory.Brush.ERASE;
        label = "AI: " + (action.equals("paint") ? "Paint " : "Erase ") + chosen.texture();
        answer.put("before", paint.getWeightAt(chosen.index(), middleX, middleY));
        var power = strength == null ? 1 : strength.floatValue();
        terrain.paint(path(), chosen.index(), mode, brushRadius, cover, stamps(power));
    }

    private void fill() throws ToolProblem {
        var paint = requirePaint();
        var chosen = layer(paint.getLayers(), true);
        Function<TerrainHeightField, BitSet> mask;
        String way;
        if (slope != null) {
            var degrees = slope.floatValue();
            mask = field -> TerrainBrushes.slopeMask(field, chosen.width(), chosen.height(), degrees);
            way = "slope";
        } else {
            var range = between;
            mask = field -> TerrainBrushes.heightMask(field, chosen.width(), chosen.height(), range[0], range[1]);
            way = "height";
        }
        label = "AI: Fill " + chosen.texture() + " by " + way;
        answer.put("before", paint.getWeightAt(chosen.index(), middleX, middleY));
        var counted = new AtomicInteger();
        texels = counted;
        terrain.fill(
                path(),
                chosen.index(),
                field -> {
                    var bits = mask.apply(field);
                    counted.set(bits.cardinality());
                    return bits;
                },
                cover,
                label);
    }

    private void mapping() throws ToolProblem {
        var layers = document().getMapping();
        if (layers == null) {
            throw new ToolProblem("The layer mapping of this Terrain cannot change: "
                    + document().getMappingError());
        }
        var chosen = layer(requirePaint().getLayers(), false);
        var current = layers.getMapping(chosen.index());
        if (current == null) {
            throw new ToolProblem("The layer "
                    + chosen.index()
                    + " cannot be mapped: "
                    + layers.getRestriction(chosen.index())
                    + ".");
        }
        var next = current;
        var words = new ArrayList<String>();
        if (scale != null) {
            next = next.withScale(Math.clamp(scale[0], .01f, 1024), Math.clamp(scale[scale.length - 1], .01f, 1024));
            words.add("Scale");
        }
        if (pan != null) {
            next = next.withPan(pan[0], pan[pan.length - 1]);
            words.add("Pan");
        }
        if (rotation != null) {
            next = next.withRotation(rotationUnits(rotation));
            words.add("Turn");
        }
        label = "AI: " + (words.size() == 1 ? words.getFirst() : "Map") + " " + chosen.texture();
        answer.put("before", mappingOf(current));
        terrain.setLayerMapping(path(), chosen.index(), next, label);
    }

    private void revert() throws ToolProblem {
        var owner = path();
        var name = owner.substring(owner.lastIndexOf('.') + 1);
        switch (what) {
            case "heights" -> {
                label = "AI: Revert " + name;
                answer.put("changed_before", changed());
                terrain.revertHeights(owner, label);
            }
            case "layer" -> {
                var chosen = layer(requirePaint().getLayers(), true);
                label = "AI: Revert " + chosen.texture();
                answer.put("changed_before", changed());
                terrain.revertLayer(owner, chosen.index(), label);
            }
            case "mapping" -> {
                if (document().getMapping() == null) {
                    throw new ToolProblem("The layer mapping of this Terrain cannot change: "
                            + document().getMappingError());
                }
                var chosen = layer(requirePaint().getLayers(), false);
                label = "AI: Revert mapping of " + chosen.texture();
                answer.put("changed_before", changed());
                terrain.revertLayerMapping(owner, chosen.index(), label);
            }
            case "decoration" -> {
                requireDecorations();
                var chosen = decoration(true);
                label = "AI: Revert " + decorationName(chosen);
                answer.put("changed_before", changed());
                terrain.revertDecoration(owner, chosen.slot(), label);
            }
            default -> {
                requireQuads();
                label = "AI: Revert Holes and edges of " + name;
                answer.put("changed_before", changed());
                terrain.revertQuads(owner, label);
            }
        }
    }

    private int changed() throws ToolProblem {
        var document = document();
        return switch (what) {
            case "heights" -> document.getHistory().getSourceChanges().size();
            case "layer" -> {
                var paint = document.getPaint();
                var count = 0;
                for (var edit : paint == null ? List.<Edit>of() : paint.getSourceChanges()) {
                    if (edit.layer().index() == chosenLayer) {
                        count += edit.changedTexels();
                    }
                }
                yield count;
            }
            case "mapping" ->
                document.getMapping() != null
                                && document.getMapping().getSourceChanges().containsKey(chosenLayer)
                        ? 1
                        : 0;
            case "decoration" -> {
                var density = document.getDecorations();
                var count = 0;
                for (var edit : density == null ? List.<Edit>of() : density.getSourceChanges()) {
                    if (chosenDecoration != null && edit.layer().index() == chosenDecoration.slot()) {
                        count += edit.changedTexels();
                    }
                }
                yield count;
            }
            case null, default -> {
                var quads = document.getQuads();
                if (quads == null) {
                    yield 0;
                }
                var cells = quads.getChangedVisibility();
                cells.or(quads.getChangedTurns());
                yield cells.cardinality();
            }
        };
    }

    private Layer layer(List<Layer> layers, boolean paintable) throws ToolProblem {
        if (layer == null) {
            throw new ToolProblem(action + " needs layer. Call terrain_edit with action info to read the layers.");
        }
        var found = layersOf(layers, layer);
        if (found.size() != 1) {
            throw new ToolProblem(
                    found.isEmpty()
                            ? "This Terrain has no layer " + layer
                                    + ". Call terrain_edit with action info to read the layers."
                            : found.size() + " layers fit " + layer + ". Give the number of one.");
        }
        var chosen = found.getFirst();
        if (paintable && !chosen.isPaintable()) {
            throw new ToolProblem("The layer " + chosen.index() + " cannot be painted: " + chosen.restriction());
        }
        chosenLayer = chosen.index();
        answer.put("layer", chosen.index());
        answer.put("texture", chosen.texture());
        return chosen;
    }

    private TerrainPaintHistory requireDecorations() throws ToolProblem {
        var document = document();
        var density = document.getDecorations();
        if (density == null || document.getDecorationSources() == null) {
            throw new ToolProblem("The decorations of this Terrain cannot be painted: "
                    + (document.getDecorationError().isEmpty()
                            ? "they are still being read"
                            : document.getDecorationError()));
        }
        return density;
    }

    private void paintDecoration() throws ToolProblem {
        requireDecorations();
        var chosen = decoration(true);
        var mode = action.equals("paint") ? TerrainPaintHistory.Brush.PAINT : TerrainPaintHistory.Brush.ERASE;
        label = "AI: " + (action.equals("paint") ? "Paint " : "Erase ") + decorationName(chosen);
        answer.put("before", densityHere(chosen));
        var power = strength == null ? 1 : strength.floatValue();
        terrain.paintDecoration(path(), chosen.slot(), mode, brushRadius, stamps(power));
    }

    private void fillDecoration() throws ToolProblem {
        var density = requireDecorations();
        var chosen = decoration(true);
        var slot = density.getLayers().get(chosen.slot());
        Function<TerrainHeightField, BitSet> mask;
        String way;
        if (slope != null) {
            var degrees = slope.floatValue();
            mask = field -> TerrainBrushes.slopeMask(field, slot.width(), slot.height(), degrees);
            way = "slope";
        } else {
            var range = between;
            mask = field -> TerrainBrushes.heightMask(field, slot.width(), slot.height(), range[0], range[1]);
            way = "height";
        }
        label = "AI: Fill " + decorationName(chosen) + " by " + way;
        answer.put("before", densityHere(chosen));
        var counted = new AtomicInteger();
        texels = counted;
        terrain.fillDecoration(
                path(),
                chosen.slot(),
                field -> {
                    var bits = mask.apply(field);
                    counted.set(bits.cardinality());
                    return bits;
                },
                label);
    }

    private Decoration decoration(boolean paintable) throws ToolProblem {
        var sources = document().getDecorationSources();
        if (decoration == null || sources == null) {
            throw new ToolProblem(
                    action + " needs decoration. Call terrain_edit with action info to read the decorations.");
        }
        var found = decorationsOf(sources.decorations(), decoration);
        if (found.size() != 1) {
            throw new ToolProblem(
                    found.isEmpty()
                            ? "This Terrain has no decoration " + decoration
                                    + ". Call terrain_edit with action info to read the decorations."
                            : found.size() + " decorations fit " + decoration + ". Give the number of one.");
        }
        var chosen = found.getFirst();
        if (paintable && !chosen.isPaintable()) {
            throw new ToolProblem("The decoration " + chosen.index() + " cannot be painted: " + chosen.restriction());
        }
        chosenDecoration = chosen;
        answer.put("decoration", chosen.index());
        answer.put("mesh", chosen.mesh());
        answer.put("density_map", chosen.densityMap());
        if (!chosen.getAlsoChanges().isEmpty()) {
            answer.put("also_changes", chosen.getAlsoChanges());
        }
        return chosen;
    }

    private int densityHere(Decoration chosen) throws ToolProblem {
        var density = requireDecorations();
        var weights = density.getWeights(chosen.slot());
        if (weights == null) {
            return -1;
        }
        var slot = density.getLayers().get(chosen.slot());
        var info = document().getInfo();
        var x = Math.clamp((int) Math.floor(middleX), 0, info.width() - 2);
        var y = Math.clamp((int) Math.floor(middleY), 0, info.height() - 2);
        return TerrainDecorationSources.densityAt(weights, slot.width(), slot.height(), info.width(), x, y);
    }

    private TerrainQuadHistory requireQuads() throws ToolProblem {
        var document = document();
        var quads = document.getQuads();
        if (quads == null) {
            throw new ToolProblem("The cells of this Terrain cannot change: " + document.getQuadsError());
        }
        return quads;
    }

    private void quads() throws ToolProblem {
        var quads = requireQuads();
        TerrainQuadHistory.Brush mode;
        switch (action) {
            case "hide" -> {
                mode = TerrainQuadHistory.Brush.HIDE;
                label = "AI: Hide ground";
            }
            case "show" -> {
                mode = TerrainQuadHistory.Brush.SHOW;
                label = "AI: Show ground";
            }
            case "turn" -> {
                mode = TerrainQuadHistory.Brush.TURN;
                label = "AI: Turn edges";
            }
            default -> {
                mode = TerrainQuadHistory.Brush.RESTORE_TURN;
                label = "AI: Restore edges";
            }
        }
        if (isHoles()) {
            answer.put("hidden_before", hiddenQuads(quads));
        } else {
            answer.put("turned_before", quads.getChangedTurns().cardinality());
        }
        terrain.quads(path(), mode, brushRadius, stamps(1));
    }

    private boolean isHoles() {
        return action.equals("hide") || action.equals("show");
    }

    private ToolResult finished() throws ToolProblem {
        switch (action) {
            case "paint", "erase", "fill" -> {
                var chosen = chosenDecoration;
                if (chosen != null) {
                    answer.put("after", densityHere(chosen));
                } else {
                    answer.put("after", requirePaint().getWeightAt(chosenLayer, middleX, middleY));
                }
            }
            case "hide", "show", "turn", "restore" -> {
                var quads = requireQuads();
                if (isHoles()) {
                    answer.put("hidden_after", hiddenQuads(quads));
                } else {
                    answer.put("turned_after", quads.getChangedTurns().cardinality());
                }
            }
            case "mapping" -> {
                var layers = document().getMapping();
                var mapped = layers == null ? null : layers.getMapping(chosenLayer);
                if (mapped != null) {
                    answer.put("after", mappingOf(mapped));
                }
            }
            case "revert" -> answer.put("changed_after", changed());
            default -> answer.put("after", EditSteps.round(heightAt()));
        }
        if (texels != null) {
            answer.put("texels", texels.get());
        }
        var done = done();
        if (!done.isEmpty() && done.getFirst() != newestBefore) {
            answer.put("step", done.getFirst().label());
            if (CROSSING.contains(action)) {
                var changed = controller.getNewestStepOwners();
                var terrains = new JSONArray();
                for (var document : controller.getDocuments()) {
                    if (changed.contains(document.getOwner())) {
                        terrains.add(document.getOwner());
                    }
                }
                answer.put("terrains", terrains);
            }
        } else {
            answer.put("note", "Nothing changed, so no Undo step was made.");
        }
        var closed = new JSONArray();
        for (var beside : besides) {
            if (!beside.why.isEmpty()) {
                var row = new JSONObject();
                row.put("map", beside.map);
                row.put("why", beside.why);
                closed.add(row);
            }
        }
        if (!closed.isEmpty()) {
            answer.put("not_changed", closed);
        }
        stroke = false;
        return ToolResult.data(answer);
    }

    private List<EditingTimeline.Step> done() {
        var timeline = session.getTimeline();
        timeline.sync();
        return timeline.getDone();
    }

    private TerrainHeightField ground() throws ToolProblem {
        var document = document();
        var info = document.getInfo();
        return new TerrainHeightField(
                info.width(), info.height(), document.getHistory().getSamples(), info.toWorld());
    }

    private float heightAt() throws ToolProblem {
        return OpenGround.ofHistories(document(), controller.getDocuments())
                .getWorldAt(middleX, middleY)
                .z();
    }

    private static List<String> pointTexts(ToolArgs args) throws ToolProblem {
        var value = args.getJson().get("points");
        var texts = new ArrayList<String>();
        if (value == null) {
            return texts;
        }
        if (value instanceof String text) {
            texts.add(text);
        } else if (value instanceof JSONArray array) {
            for (var item : array) {
                if (item instanceof JSONArray numbers) {
                    texts.add(numbers.stream()
                            .map(String::valueOf)
                            .reduce((a, b) -> a + "," + b)
                            .orElse(""));
                } else {
                    texts.add(String.valueOf(item));
                }
            }
        } else {
            throw new ToolProblem("points must be a list of targets, such as [\"83800,147972\"].");
        }
        if (texts.size() > MOST_POINTS) {
            throw new ToolProblem("points holds " + texts.size() + " targets, more than " + MOST_POINTS
                    + ". Split the stroke into calls.");
        }
        return texts;
    }

    private static float @Nullable [] numbers(ToolArgs args, String name) throws ToolProblem {
        var value = args.getJson().get(name);
        if (value == null) {
            return null;
        }
        var items = new ArrayList<Object>();
        if (value instanceof JSONArray array) {
            items.addAll(array);
        } else if (value instanceof String text) {
            items.addAll(List.of((Object[]) text.split(",")));
        } else {
            items.add(value);
        }
        var result = new float[items.size()];
        for (var index = 0; index < result.length; index++) {
            try {
                result[index] =
                        Float.parseFloat(String.valueOf(items.get(index)).strip());
            } catch (NumberFormatException notANumber) {
                result[index] = Float.NaN;
            }
            if (!Float.isFinite(result[index])) {
                throw new ToolProblem(name + " must be one or two numbers.");
            }
        }
        if (result.length < 1 || result.length > 2) {
            throw new ToolProblem(name + " must be one or two numbers.");
        }
        return result;
    }

    private static Falloff falloff(String word) throws ToolProblem {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "smooth" -> Falloff.SMOOTH;
            case "linear" -> Falloff.LINEAR;
            case "sharp" -> Falloff.SHARP;
            default -> throw new ToolProblem("falloff must be smooth, linear or sharp.");
        };
    }

    private static Owner chooseOwner(Path root, String map, float x, float y) throws ToolProblem, IOException {
        var file = TerrainEditingController.mapFile(root, map);
        List<Capability> owners;
        try (var reader = new TerrainEditingController(root)) {
            owners = reader.inspect(file);
        }
        if (owners.isEmpty()) {
            throw new ToolProblem("The map " + map + " has no Terrain.");
        }
        Capability under = null;
        for (var terrain : TerrainPackageSnapshot.open(file, root).getTerrains()) {
            var info = terrain.info();
            var point = info.toHeightmap().transform(x, y, 0);
            if (point.x() >= 0 && point.y() >= 0 && point.x() <= info.width() - 1 && point.y() <= info.height() - 1) {
                for (var owner : owners) {
                    if (owner.owner().equals(terrain.path())) {
                        under = owner;
                    }
                }
            }
        }
        if (under == null) {
            throw new ToolProblem("No Terrain of the map " + map + " lies under this point.");
        }
        if (!under.heightsDecoded() || !under.isNativeExportSupported()) {
            throw new ToolProblem("The Terrain " + under.owner() + " cannot be edited: " + under.exportRestriction());
        }
        return new Owner(file, under.owner());
    }

    private static double length(List<float[]> corners) {
        var length = 0d;
        for (var index = 1; index < corners.size(); index++) {
            length += distance(corners.get(index - 1), corners.get(index));
        }
        return length;
    }

    private static double distance(float[] a, float[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    private static JSONObject mappingOf(TerrainLayerMapping mapping) {
        var data = new JSONObject();
        data.put("scale", JSONArray.of(EditSteps.round(mapping.uScale()), EditSteps.round(mapping.vScale())));
        data.put("pan", JSONArray.of(EditSteps.round(mapping.uPan()), EditSteps.round(mapping.vPan())));
        data.put("rotation", EditSteps.round(mapping.textureRotation() * 360 / TerrainLayerMapping.FULL_TURN));
        return data;
    }

    private static int hiddenQuads(TerrainQuadHistory quads) {
        return quads.getWidth() * quads.getHeight() - quads.getVisible().cardinality();
    }

    private static int rawAt(OpenGround ground, Info info, float x, float y) {
        var world = ground.getWorldAt(x, y);
        return Math.round(
                info.toHeightmap().transform(world.x(), world.y(), world.z()).z());
    }
}

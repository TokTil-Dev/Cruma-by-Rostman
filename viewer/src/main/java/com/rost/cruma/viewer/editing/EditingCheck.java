package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.editing.EditingSession.BrushRef;
import com.rost.cruma.viewer.editing.EditingSession.Change;
import com.rost.cruma.viewer.editing.EditingSession.ChangeKind;
import com.rost.cruma.viewer.editing.EditingSession.SurfaceRef;
import com.rost.cruma.viewer.editing.GroundProbe.Snapshot;
import com.rost.cruma.viewer.ui.FindingWidgets.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class EditingCheck implements AutoCloseable {
    static final float REACH = 2048;

    public static final float TOLERANCE = 8;

    static final float RESTING_REACH = 4;

    private static final Pattern MISSING_PACKAGE = Pattern.compile("package (\\S+) is missing\\..*");
    private static final Pattern WRONG_CLASS = Pattern.compile("expected (\\S+), found (.+)\\.");
    private static final Pattern AMBIGUOUS = Pattern.compile("ambiguous exported object for class (\\S+)\\.");
    private static final Pattern CYCLE = Pattern.compile("cyclic class inheritance at (\\S+)\\.");
    private static final Pattern TWO_PACKAGES =
            Pattern.compile("conflicting installed package identities for (\\S+)\\.");
    private static final Pattern UNREADABLE = Pattern.compile("cannot inspect package (\\S+) \\((.+)\\)\\.");

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("editing-check").factory());

    public enum Kind {
        FLOATING(
                "Meshes that float or sink",
                Severity.WARNING,
                "Drop to ground",
                "A mesh near an edit whose bottom is more than 8 units above or below the Terrain under it, more than"
                    + " it was in the client. The Terrain is the only ground the check knows: level geometry and other"
                    + " meshes are not looked at."),
        OUTSIDE(
                "Actors outside their map",
                Severity.WARNING,
                "Move to",
                "A changed actor that stands in another tile than its map. The game loads it with its map, so it may"
                        + " show or collide in the wrong place."),
        COPIES(
                "Copies in one place",
                Severity.WARNING,
                "Delete the copy",
                "Two actors with the same mesh at the same location, one of them new or changed, as after a duplicate"
                        + " that did not move."),
        EXPORT(
                "Problems that block the save",
                Severity.ERROR,
                "",
                "Problems that the check found, such as a package the changes need and the client lacks. Nothing can"
                        + " be saved until they are solved."),
        SPAWNS(
                "NPC Spawns in meshes or under the ground",
                Severity.INFO,
                "",
                "A fixed NPC or a corner of a spawn zone of the open npcpos.txt that lies inside a new or moved mesh,"
                        + " or under ground that the strokes raised."),
        GEODATA(
                "Geodata older than the edit",
                Severity.INFO,
                "",
                "A geodata region of an edited tile whose file is older than the first edit of this session. Rebuild"
                        + " it with the Geodata tool after saving."),
        RESTING(
                "Objects on a deleted brush",
                Severity.WARNING,
                "",
                "An object whose bounds touched a deleted brush: it stood on it or leaned against it, and may float in"
                        + " the air now. Fly to selects it, so you can move or delete it."),
        SURFACES(
                "Changed BSP surfaces",
                Severity.INFO,
                "",
                "A wall or floor of the level geometry that is hidden or deleted, has another texture, is two-sided or"
                    + " unlit now, or whose texture moved, turned or changed its size, and a deleted brush. The line"
                    + " of each says what the game does with it. A hidden wall still blocks the player, as the BSP's"
                    + " nodes decide what blocks, not the surface flags. A deleted sheet never blocked, and the space"
                    + " of a deleted brush is air now. The brush polygons of the map keep the old surface, so a"
                    + " rebuild of the geometry in UnrealEd would very likely bring it back.");

        final String title;
        final Severity severity;
        final String fix;
        final String help;

        Kind(String title, Severity severity, String fix, String help) {
            this.title = title;
            this.severity = severity;
            this.fix = fix;
            this.help = help;
        }
    }

    public record Finding(
            Kind kind, String title, String detail, float x, float y, float z, String actor, float dz, String tile) {}

    record Actor(
            String path,
            String mesh,
            float[] bounds,
            float x,
            float y,
            float z,
            @Nullable float[] source,
            boolean created,
            boolean changed,
            boolean moved,
            @Nullable String outsideTile) {}

    record Surface(String name, String detail, float x, float y, float z, List<Resting> resting) {}

    record Resting(String actor, String brush, float x, float y, float z) {}

    public record SpawnSpot(String name, String what, double x, double y, double z) {}

    @FunctionalInterface
    public interface SpawnSource {
        @Nullable
        List<SpawnSpot> getSpots(double minX, double minY, double maxX, double maxY);
    }

    @FunctionalInterface
    public interface GeodataSource {
        long getRegionModified(String tile);
    }

    record Input(
            List<Actor> actors,
            List<float[]> editPoints,
            List<float[]> terrainRects,
            Snapshot ground,
            List<String> exportProblems,
            @Nullable List<SpawnSpot> spawns,
            Map<String, Long> geodata,
            boolean geodataLoaded,
            long firstEdit,
            Path clientRoot,
            List<Surface> surfaces) {
        Input(
                List<Actor> actors,
                List<float[]> editPoints,
                List<float[]> terrainRects,
                Snapshot ground,
                List<String> exportProblems,
                @Nullable List<SpawnSpot> spawns,
                Map<String, Long> geodata,
                boolean geodataLoaded,
                long firstEdit,
                Path clientRoot) {
            this(
                    actors,
                    editPoints,
                    terrainRects,
                    ground,
                    exportProblems,
                    spawns,
                    geodata,
                    geodataLoaded,
                    firstEdit,
                    clientRoot,
                    List.of());
        }
    }

    public static String fixOf(Finding finding) {
        return switch (finding.kind()) {
            case FLOATING, COPIES -> finding.kind().fix;
            case OUTSIDE -> finding.tile().isEmpty() ? "" : "Move to " + finding.tile();
            default -> "";
        };
    }

    public static String fixLabel(List<Finding> findings) {
        var one = findings.size() == 1
                ? EditingSession.shortName(findings.getFirst().actor())
                : null;
        return switch (findings.getFirst().kind()) {
            case FLOATING -> "Drop " + (one != null ? one : findings.size() + " meshes") + " to the ground";
            case OUTSIDE ->
                "Move "
                        + (one != null
                                ? one + " to " + findings.getFirst().tile()
                                : findings.size() + " actors to their tiles");
            default -> "Delete " + (one != null ? one : findings.size() + " copies");
        };
    }

    public static void fix(EditingSession session, List<Finding> findings, String label, Runnable afterDrop) {
        if (findings.isEmpty()) {
            return;
        }
        switch (findings.getFirst().kind()) {
            case FLOATING -> {
                var values = new LinkedHashMap<String, ActorTransform>();
                for (var finding : findings) {
                    var t = session.getActor(finding.actor()).transform();
                    var p = t.location();
                    values.put(
                            finding.actor(),
                            new ActorTransform(
                                    new Vector3(p.x(), p.y(), p.z() + finding.dz()),
                                    t.rotation(),
                                    t.drawScale(),
                                    t.drawScale3d(),
                                    t.prePivot()));
                }
                session.beginStep();
                try {
                    session.transformActors(values, label);
                    afterDrop.run();
                } finally {
                    session.endStep(label);
                }
            }
            case OUTSIDE -> {
                var targets = new LinkedHashMap<String, String>();
                for (var finding : findings) {
                    if (!finding.tile().isEmpty()) {
                        targets.put(finding.actor(), finding.tile());
                    }
                }
                session.moveToTiles(targets, label);
            }
            case COPIES -> {
                var copies = new ArrayList<String>();
                for (var finding : findings) {
                    copies.add(finding.actor());
                }
                session.deleteActors(copies, label);
            }
            default -> {
            }
        }
    }

    public static Function<AtomicBoolean, List<Finding>> ofChanges(
            EditingSession session, List<Change> changes, Map<String, Bounds3f> bounds, Snapshot ground) {
        var actors = new ArrayList<Actor>();
        var points = new ArrayList<float[]>();
        var surfaces = new ArrayList<Surface>();
        for (var change : changes) {
            if (change.kinds().contains(ChangeKind.SURFACE)) {
                surfaces.add(surfaceOf(change, resting(session, change, bounds)));
                continue;
            }
            var location = change.location();
            points.add(new float[] {location.x(), location.y(), location.z()});
            var box = bounds.get(change.path());
            if (box != null) {
                actors.add(actorOf(session, change, box));
            }
        }
        var input = new Input(
                List.copyOf(actors),
                List.copyOf(points),
                List.of(),
                ground,
                List.of(),
                null,
                Map.of(),
                false,
                0,
                session.getClientRoot(),
                List.copyOf(surfaces));
        return cancelled -> run(input, cancelled).findings();
    }

    public static List<String> getActorsOnBrush(EditingSession session, BrushRef brush, Map<String, Bounds3f> bounds) {
        var box = new float[] {
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        };
        try {
            for (var surface : session.getBrushSurfaces(brush)) {
                for (var polygon : session.getSurfacePolygons(surface)) {
                    for (var i = 0; i + 2 < polygon.length; i += 3) {
                        for (var axis = 0; axis < 3; axis++) {
                            box[axis] = Math.min(box[axis], polygon[i + axis]);
                            box[axis + 3] = Math.max(box[axis + 3], polygon[i + axis]);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
        if (box[0] > box[3]) {
            return List.of();
        }
        var prefix = brush.map().toLowerCase(Locale.ROOT) + ".";
        var result = new ArrayList<String>();
        for (var entry : bounds.entrySet()) {
            var b = entry.getValue();
            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith(prefix)
                    && b.maxX() >= box[0] - RESTING_REACH
                    && b.minX() <= box[3] + RESTING_REACH
                    && b.maxY() >= box[1] - RESTING_REACH
                    && b.minY() <= box[4] + RESTING_REACH
                    && b.maxZ() >= box[2] - RESTING_REACH
                    && b.minZ() <= box[5] + RESTING_REACH) {
                result.add(entry.getKey());
            }
        }
        result.sort(null);
        return result;
    }

    static List<Resting> resting(EditingSession session, Change change, Map<String, Bounds3f> bounds) {
        if (!change.kinds().contains(ChangeKind.BRUSH)) {
            return List.of();
        }
        var surface = SurfaceRef.ofPath(change.path());
        var brush = surface == null ? null : session.getSurfaceBrush(surface);
        if (brush == null) {
            return List.of();
        }
        var result = new ArrayList<Resting>();
        for (var path : getActorsOnBrush(session, brush, bounds)) {
            var b = bounds.get(path);
            result.add(new Resting(path, change.title(), b.centerX(), b.centerY(), b.centerZ()));
        }
        return result;
    }

    static Surface surfaceOf(Change change, List<Resting> resting) {
        var location = change.location();
        var name = change.kinds().contains(ChangeKind.BRUSH) ? change.title() : EditingSession.shortName(change.path());
        return new Surface(
                name + " of " + change.map(), surfaceDetail(change), location.x(), location.y(), location.z(), resting);
    }

    static String surfaceDetail(Change change) {
        var lines = new ArrayList<String>();
        var kinds = change.kinds();
        if (kinds.contains(ChangeKind.BRUSH)) {
            return "Deleted brush: by the client's code the game no longer draws it or blocks there, which was not"
                    + " tried in a game yet. The server's geodata still blocks there: install the saved map, then build"
                    + " the geodata again with the Geodata tool and install it. Its baked shadow stays in the"
                    + " lightmaps: a light that you move bakes its light again without it. A rebuild of the geometry"
                    + " in UnrealEd brings the brush back.";
        }
        if (kinds.contains(ChangeKind.HIDDEN) && kinds.contains(ChangeKind.SHEET)) {
            lines.add("Deleted: a sheet never blocked, so it is gone for the player too. Its baked shadow can stay"
                    + " in the lightmaps.");
        } else if (kinds.contains(ChangeKind.HIDDEN)) {
            lines.add("Hidden: the game does not draw it, but it still blocks the player. A rebuild of the BSP"
                    + " lightmaps near it lets light through it, while the light of meshes still sees it.");
        }
        if (kinds.contains(ChangeKind.TEXTURE)) {
            lines.add("New texture " + EditingSession.shortName(change.from()) + ".");
        }
        if (kinds.contains(ChangeKind.MAPPING)) {
            lines.add("New texture mapping.");
        }
        if (kinds.contains(ChangeKind.TWO_SIDED)) {
            lines.add("Two-sided: the game draws it from both sides, also when the camera is behind it. Nothing"
                    + " blocks differently.");
        }
        if (kinds.contains(ChangeKind.ONE_SIDED)) {
            lines.add("Two-sided off: the game draws it only from its front. Nothing blocks differently.");
        }
        if (kinds.contains(ChangeKind.UNLIT)) {
            lines.add("Unlit: the game draws it at full brightness with no lightmap, no shadow and no dynamic light."
                    + " Its lightmap stays in the map unused.");
        }
        if (kinds.contains(ChangeKind.LIT)) {
            lines.add("Unlit off: the game draws it with its lightmap, and dynamic lights reach it.");
        }
        if (kinds.contains(ChangeKind.HIDDEN)) {
            lines.add("A rebuild of the geometry in UnrealEd would very likely show it again.");
        } else if (kinds.contains(ChangeKind.TEXTURE)) {
            lines.add("Its brush polygon keeps the old texture.");
        } else if (kinds.contains(ChangeKind.MAPPING)) {
            lines.add("Its brush polygon keeps the old mapping.");
        }
        if (kinds.contains(ChangeKind.TWO_SIDED)
                || kinds.contains(ChangeKind.ONE_SIDED)
                || kinds.contains(ChangeKind.UNLIT)
                || kinds.contains(ChangeKind.LIT)) {
            lines.add("A rebuild of the geometry in UnrealEd brings the old flag back.");
        }
        return String.join(" ", lines);
    }

    static Result run(Input input, AtomicBoolean cancelled) {
        var findings = new ArrayList<Finding>();
        var notes = new ArrayList<String>();
        floating(input, findings, cancelled);
        outside(input, findings);
        copies(input, findings);
        for (var problem : input.exportProblems()) {
            var place = input.editPoints().isEmpty()
                    ? new float[3]
                    : input.editPoints().getFirst();
            findings.add(exportFinding(problem, place[0], place[1], place[2]));
        }
        if (input.spawns() == null) {
            notes.add("NPC Spawns not checked · open an npcpos.txt");
        } else {
            spawns(input, findings);
        }
        for (var surface : input.surfaces()) {
            findings.add(new Finding(
                    Kind.SURFACES, surface.name(), surface.detail(), surface.x(), surface.y(), surface.z(), "", 0, ""));
            for (var resting : surface.resting()) {
                findings.add(new Finding(
                        Kind.RESTING,
                        EditingSession.shortName(resting.actor()),
                        "Stood on or against " + resting.brush() + ", which is deleted. It may float in the air now.",
                        resting.x(),
                        resting.y(),
                        resting.z(),
                        resting.actor(),
                        0,
                        ""));
            }
        }
        if (!input.geodataLoaded()) {
            notes.add("Geodata not checked · open a geodata folder");
        } else {
            geodata(input, findings);
        }
        return new Result(List.copyOf(findings), List.copyOf(notes));
    }

    static Finding exportFinding(String problem, float x, float y, float z) {
        var text = exportProblem(problem);
        return new Finding(Kind.EXPORT, text[0], text[1], x, y, z, "", 0, "");
    }

    static String[] exportProblem(String problem) {
        var split = problem.indexOf(": ");
        if (split <= 0) {
            return new String[] {"Save problem", problem};
        }
        var head = problem.substring(0, split);
        var rest = problem.substring(split + 2).strip();
        var chain = head.replace(" -> ", " → ");
        var arrow = chain.lastIndexOf(" → ");
        var last = arrow < 0 ? chain : chain.substring(arrow + 3);
        var needer = arrow < 0 ? chain : chain.substring(0, arrow);
        var undo = " Add the package under Advanced, or undo the change that needs it.";
        var matcher = MISSING_PACKAGE.matcher(rest);
        if (matcher.matches()) {
            return new String[] {"Package " + matcher.group(1) + " is missing", "Needed by " + chain + "." + undo};
        }
        if (rest.equals("exported object is missing.")) {
            var dot = last.indexOf('.');
            var title = dot < 0
                    ? last + " is missing"
                    : last.substring(dot + 1) + " is missing from package " + last.substring(0, dot);
            return new String[] {title, "Needed by " + needer + "." + undo};
        }
        matcher = WRONG_CLASS.matcher(rest);
        if (matcher.matches()) {
            return new String[] {
                last + " is the wrong kind",
                "Needed by " + needer + " as " + matcher.group(1) + ", but it is " + matcher.group(2) + "." + undo
            };
        }
        matcher = AMBIGUOUS.matcher(rest);
        if (matcher.matches()) {
            return new String[] {
                "More than one " + last,
                "Needed by "
                        + needer
                        + " as "
                        + matcher.group(1)
                        + ", and more than one object of that kind has this name."
                        + undo
            };
        }
        matcher = CYCLE.matcher(rest);
        if (matcher.matches()) {
            return new String[] {
                matcher.group(1) + " is defined in a loop",
                "Needed by " + chain + ". The packages that define it are damaged or do not belong together."
            };
        }
        matcher = TWO_PACKAGES.matcher(rest);
        if (matcher.matches()) {
            return new String[] {
                "Two different packages " + matcher.group(1) + " in the client",
                "Needed by " + chain + ". Keep only one file of that name where the client looks for packages."
            };
        }
        matcher = UNREADABLE.matcher(rest);
        if (matcher.matches()) {
            return new String[] {
                "Package " + matcher.group(1) + " cannot be read",
                "Needed by " + chain + ". The file may be damaged or in use (" + matcher.group(2) + ")."
            };
        }
        if (!head.contains(" -> ") && Character.isUpperCase(head.charAt(0))) {
            return new String[] {head, rest};
        }
        return new String[] {"Save problem", problem};
    }

    static float @Nullable [] tileCenter(String tile) {
        var parts = tile.split("_");
        if (parts.length != 2) {
            return null;
        }
        try {
            var x = Integer.parseInt(parts[0]);
            var y = Integer.parseInt(parts[1]);
            return new float[] {(x - 20) * 32768f + 16384, (y - 18) * 32768f + 16384};
        } catch (NumberFormatException notATile) {
            return null;
        }
    }

    CompletableFuture<Result> start(Input input, AtomicBoolean cancelled) {
        return CompletableFuture.supplyAsync(() -> run(input, cancelled), worker);
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    private static Actor actorOf(EditingSession session, Change change, Bounds3f box) {
        var path = change.path();
        var actor = session.getActor(path);
        var location = actor.transform().location();
        var source = session.getSourceTransform(path);
        var kinds = change.kinds();
        return new Actor(
                path,
                actor.meshSourcePath(),
                new float[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()},
                location.x(),
                location.y(),
                location.z(),
                source == null
                        ? null
                        : new float[] {
                            source.location().x(),
                            source.location().y(),
                            source.location().z()
                        },
                actor.created(),
                true,
                kinds.contains(ChangeKind.MOVED)
                        || kinds.contains(ChangeKind.ROTATED)
                        || kinds.contains(ChangeKind.SCALED),
                session.getTileOutsideMap(path));
    }

    record Result(List<Finding> findings, List<String> notes) {}

    private static void floating(Input input, List<Finding> findings, AtomicBoolean cancelled) {
        for (var actor : input.actors()) {
            if (cancelled.get()) {
                return;
            }
            if (actor.mesh().isBlank()) {
                continue;
            }
            var b = actor.bounds();
            var cx = (b[0] + b[3]) * .5f;
            var cy = (b[1] + b[4]) * .5f;
            if (!isNearEdit(input, cx, cy)) {
                continue;
            }
            var now = input.ground().getCurrent(cx, cy);
            if (now == null) {
                continue;
            }
            var offset = b[2] - now;
            float shift;
            if (actor.created() || actor.source() == null) {
                shift = offset;
            } else {
                var dx = actor.x() - actor.source()[0];
                var dy = actor.y() - actor.source()[1];
                var dz = actor.z() - actor.source()[2];
                var then = input.ground().getSaved(cx - dx, cy - dy);
                if (then == null) {
                    continue;
                }
                shift = offset - (b[2] - dz - then);
            }
            if (Math.abs(shift) <= TOLERANCE) {
                continue;
            }
            var detail = String.format(
                            Locale.ROOT,
                            shift > 0 ? "%s floats %,d units above the Terrain" : "%s sinks %,d units into the Terrain",
                            named(actor),
                            Math.round(Math.abs(shift)))
                    + (actor.created() ? "." : ", more than in the client.")
                    + " · "
                    + EditingSession.mapOfPath(actor.path());
            findings.add(new Finding(Kind.FLOATING, title(actor), detail, cx, cy, b[5], actor.path(), -shift, ""));
        }
    }

    private static String title(Actor actor) {
        return actor.mesh() == null || actor.mesh().isBlank()
                ? EditingSession.shortName(actor.path())
                : EditingSession.shortName(actor.mesh());
    }

    private static String named(Actor actor) {
        var actorName = EditingSession.shortName(actor.path());
        var title = title(actor);
        return title.equals(actorName) ? actorName : title + " (" + actorName + ")";
    }

    private static void outside(Input input, List<Finding> findings) {
        for (var actor : input.actors()) {
            var tile = actor.outsideTile();
            if (tile == null || !actor.changed()) {
                continue;
            }
            var map = hasMapFile(input.clientRoot(), tile);
            findings.add(new Finding(
                    Kind.OUTSIDE,
                    title(actor),
                    named(actor)
                            + " stands in "
                            + tile
                            + ", but belongs to "
                            + EditingSession.mapOfPath(actor.path())
                            + (map ? "." : ". The client has no map " + tile + " to move it to."),
                    actor.x(),
                    actor.y(),
                    actor.z(),
                    actor.path(),
                    0,
                    map ? tile : ""));
        }
    }

    private static void copies(Input input, List<Finding> findings) {
        var groups = new LinkedHashMap<String, List<Actor>>();
        for (var actor : input.actors()) {
            if (!actor.mesh().isBlank()) {
                var key = actor.mesh().toLowerCase(Locale.ROOT)
                        + "@"
                        + Math.round(actor.x())
                        + ","
                        + Math.round(actor.y())
                        + ","
                        + Math.round(actor.z());
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(actor);
            }
        }
        for (var group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparingInt(actor -> actor.created() ? 2 : actor.changed() ? 1 : 0));
            var kept = group.getFirst();
            for (var copy : group.subList(1, group.size())) {
                if (!copy.created() && !copy.changed()) {
                    continue;
                }
                var name = EditingSession.shortName(copy.path());
                findings.add(new Finding(
                        Kind.COPIES,
                        title(copy),
                        name
                                + " stands where "
                                + EditingSession.shortName(kept.path())
                                + " stands, with the same mesh "
                                + EditingSession.shortName(copy.mesh())
                                + ".",
                        copy.x(),
                        copy.y(),
                        copy.z(),
                        copy.path(),
                        0,
                        ""));
            }
        }
    }

    private static void spawns(Input input, List<Finding> findings) {
        for (var spot : input.spawns()) {
            Actor inside = null;
            for (var actor : input.actors()) {
                var b = actor.bounds();
                if ((actor.created() || actor.moved())
                        && spot.x() >= b[0]
                        && spot.x() <= b[3]
                        && spot.y() >= b[1]
                        && spot.y() <= b[4]
                        && spot.z() >= b[2] - 16
                        && spot.z() <= b[5]) {
                    inside = actor;
                    break;
                }
            }
            if (inside != null) {
                findings.add(new Finding(
                        Kind.SPAWNS,
                        spot.name(),
                        spot.what() + " lies inside " + EditingSession.shortName(inside.path())
                                + ", a new or moved mesh.",
                        (float) spot.x(),
                        (float) spot.y(),
                        (float) spot.z(),
                        "",
                        0,
                        ""));
                continue;
            }
            var now = input.ground().getCurrent(spot.x(), spot.y());
            var then = input.ground().getSaved(spot.x(), spot.y());
            if (now != null && then != null && now - then > TOLERANCE && spot.z() < now - TOLERANCE) {
                findings.add(new Finding(
                        Kind.SPAWNS,
                        spot.name(),
                        String.format(
                                Locale.ROOT,
                                "%s lies %,d units under the raised ground.",
                                spot.what(),
                                Math.round(now - spot.z())),
                        (float) spot.x(),
                        (float) spot.y(),
                        now,
                        "",
                        0,
                        ""));
            }
        }
    }

    private static void geodata(Input input, List<Finding> findings) {
        input.geodata().forEach((tile, modified) -> {
            if (modified <= 0 || input.firstEdit() <= 0 || modified >= input.firstEdit()) {
                return;
            }
            var center = tileCenter(tile);
            if (center == null) {
                return;
            }
            findings.add(new Finding(
                    Kind.GEODATA,
                    "Geodata of " + tile,
                    "Geodata of " + tile + " may be outdated: its region file is older than the edit.",
                    center[0],
                    center[1],
                    0,
                    "",
                    0,
                    tile));
        });
    }

    private static boolean isNearEdit(Input input, float x, float y) {
        for (var point : input.editPoints()) {
            if (Math.hypot(point[0] - x, point[1] - y) <= REACH) {
                return true;
            }
        }
        for (var rect : input.terrainRects()) {
            var dx = Math.max(0, Math.max(rect[0] - x, x - rect[2]));
            var dy = Math.max(0, Math.max(rect[1] - y, y - rect[3]));
            if (Math.hypot(dx, dy) <= REACH) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMapFile(Path root, String tile) {
        try (var files = Files.list(root.resolve("Maps"))) {
            return files.anyMatch(path -> path.getFileName().toString().equalsIgnoreCase(tile + ".unr"));
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}

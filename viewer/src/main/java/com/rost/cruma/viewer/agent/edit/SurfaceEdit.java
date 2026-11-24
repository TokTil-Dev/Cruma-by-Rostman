package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.BspSurfaceFlag;
import com.rost.cruma.core.editing.BspSurfaceMapping;
import com.rost.cruma.core.editing.BspSurfaceSelect;
import com.rost.cruma.core.editing.PackageEditDocument.BrushFacts;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.editing.EditingCheck;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.BrushRef;
import com.rost.cruma.viewer.editing.EditingSession.MaterialChoice;
import com.rost.cruma.viewer.editing.EditingSession.SurfaceRef;
import com.rost.cruma.viewer.editing.EditorUi;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.jetbrains.annotations.Nullable;

final class SurfaceEdit {
    private static final List<String> ACTIONS = List.of(
            "read", "change", "floor", "wall", "revert", "hide", "show", "delete", "material", "flags", "select");
    private static final List<String> SELECTS = List.of(
            "brush", "texture", "adjacent", "coplanar", "walls", "floors", "slants", "reverse", "all", "zone", "items");
    private static final long SHOWN_MILLIS = 10_000;

    private SurfaceEdit() {}

    static void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "surfaces_edit",
                                "Edit BSP surfaces",
                                "Reads or changes BSP walls and floors, as the Surfaces tool: pan, turn, scale, flip,"
                                        + " floor, wall, hide, show, delete a sheet or a brush that stands apart,"
                                        + " another material, Two-sided and Unlit with flags, revert. A change is"
                                        + " one Undo step. select chooses more surfaces as UnrealEd's Select does:"
                                        + " every side of a brush, the surfaces of a texture, of a zone or of an item"
                                        + " name, the adjacent ones of a kind, reverse or all of a map. It changes"
                                        + " nothing.")
                        .choice("action", "read when left out.", ACTIONS.toArray(String[]::new))
                        .text("target", "A point on the surface, as view_pick or world_probe give it.")
                        .list(
                                "surfaces",
                                "Surface paths such as 22_22.Surface12, from read. For show, [\"hidden\"] is every"
                                        + " one the session hid. For select, the surfaces to start from.")
                        .choice(
                                "by",
                                "For select: brush gives every side of their brushes, texture every surface of"
                                        + " their materials, adjacent, coplanar, walls, floors and slants the"
                                        + " surfaces that touch them, again and again, reverse the others of their"
                                        + " maps and all every surface of their maps. zone gives every surface with"
                                        + " a part in one of their zones, and items the sides of their brushes whose"
                                        + " brush polygon has one of their item names, brush_item of read.",
                                SELECTS.toArray(String[]::new))
                        .text("map", "For select by all or reverse with no surfaces: the map, such as 22_22.")
                        .integer("limit", "For select: the most paths in the answer. All when left out.")
                        .text("material", "For material, a material path, or default for the client's own.")
                        .text("file", "For material, the full path of the .utx that holds it when the client does not.")
                        .schema("pan", pair("Moves the texture by texels along U and V."))
                        .number("turn", "Turns the texture by degrees.")
                        .schema(
                                "scale",
                                pair("Sets world units per texel along U and V. 1 is the size of a new surface."))
                        .choice("flip", "Mirrors the texture along U or V.", "u", "v")
                        .flag(
                                "two_sided",
                                "For flags: true draws the surface from both sides in the game, false only from its"
                                        + " front. Collision does not change.")
                        .flag(
                                "unlit",
                                "For flags: true draws the surface at full brightness, with no lightmap and no light,"
                                        + " false with its lightmap. A surface that the client saves as unlit has no"
                                        + " lightmap, so it stays unlit.")
                        .flag(
                                "brushes",
                                "With read, also says whether the brush of each side of a wall or floor can be"
                                        + " deleted, and why not. The first such read of a map reads all its brushes.")
                        .access(Access.EDIT),
                SurfaceEdit::start);
    }

    static Step start(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var action = args.getText("action", "read").toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new ToolProblem("action must be read, change, floor, wall, revert, hide, show, delete, material,"
                    + " flags or select.");
        }
        var change = action.equals("change") ? change(args) : null;
        var material = action.equals("material") ? material(args) : null;
        var flags = action.equals("flags") ? flags(args) : null;
        if (!action.equals("flags")
                && (args.getJson().containsKey("two_sided") || args.getJson().containsKey("unlit"))) {
            throw new ToolProblem("two_sided and unlit belong to flags.");
        }
        var command = action.equals("select") ? command(args) : null;
        var named = args.list("surfaces");
        var targetText = args.getText("target");
        var brushData = args.readFlag("brushes", false);
        var mapName = args.getText("map");
        var limit = args.getInteger("limit", Integer.MAX_VALUE);
        if (limit < 1) {
            throw new ToolProblem("limit must be 1 or more.");
        }
        if (!action.equals("select")
                && (args.getJson().containsKey("by") || mapName != null || limit != Integer.MAX_VALUE)) {
            throw new ToolProblem("by, map and limit belong to select.");
        }
        if (action.equals("show") && named.size() == 1 && named.getFirst().equalsIgnoreCase("hidden")) {
            return EditSteps.editing(viewer, session, () -> shown(session, showAll(session)));
        }
        var wholeMap = command == BspSurfaceSelect.Command.ALL || command == BspSurfaceSelect.Command.REVERSE;
        if (named.isEmpty() && targetText == null && !(wholeMap && mapName != null)) {
            throw new ToolProblem(
                    wholeMap
                            ? "Name the start: surfaces, target, or map for every surface of one map."
                            : "Name the surfaces: give target, a point on one, or surfaces, paths from read.");
        }
        if (mapName != null && (!wholeMap || !named.isEmpty() || targetText != null)) {
            throw new ToolProblem("map is for select by all or reverse with no surfaces and no target.");
        }
        var refs = new ArrayList<SurfaceRef>();
        for (var path : named) {
            var ref = SurfaceRef.ofPath(path);
            if (ref == null) {
                throw new ToolProblem(path + " is not a surface path. They look like 22_22.Surface12.");
            }
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        }
        Vector3 point = null;
        String pointMap = null;
        var loaded = EditSteps.loadedMaps(viewer, session);
        if (targetText != null) {
            var target = EditSteps.locate(viewer, session, targetText);
            if (target.z() == null) {
                throw new ToolProblem(
                        "target needs a height on the surface. view_pick or world_probe with a line gives one.");
            }
            point = new Vector3(
                    (float) target.x(), (float) target.y(), target.z().floatValue());
            pointMap = WorldTile.mapAt(target.x(), target.y(), loaded);
        }
        var maps = new LinkedHashSet<String>();
        refs.forEach(ref -> maps.add(ref.map()));
        if (pointMap != null) {
            maps.add(pointMap);
        }
        if (mapName != null) {
            maps.add(mapName);
        }
        for (var map : maps) {
            if (!loaded.contains(map)) {
                throw new ToolProblem("The map " + map + " is not loaded in this viewer. Open it with world_open.");
            }
        }
        var at = point;
        var atMap = pointMap;
        return EditSteps.editing(
                viewer,
                session,
                () -> EditSteps.opening(session, maps, () -> {
                    if (at != null) {
                        var picked = session.pickSurface(atMap, at);
                        if (picked == null) {
                            throw new ToolProblem("No BSP surface of " + atMap + " lies at " + EditSteps.point(at)
                                    + ". Give a point on a wall or floor of the level, as view_pick gives it."
                                    + " A mesh or the Terrain is no BSP surface.");
                        }
                        if (!refs.contains(picked)) {
                            refs.add(picked);
                        }
                    }
                    if (action.equals("read")) {
                        return Step.done(read(session, refs, brushData));
                    }
                    if (command != null) {
                        return Step.done(select(session, refs, command, mapName, limit));
                    }
                    return shown(
                            session,
                            switch (action) {
                                case "revert" -> revert(session, refs);
                                case "hide" -> hide(session, refs);
                                case "show" -> show(session, refs);
                                case "delete" -> delete(viewer, session, refs);
                                case "material" -> material(session, refs, material);
                                case "flags" -> flags(session, refs, flags);
                                case "floor" ->
                                    changed(
                                            session,
                                            refs,
                                            "Align texture",
                                            (current, normal) -> current.alignFloor(normal),
                                            "It is too steep for Floor.");
                                case "wall" ->
                                    changed(
                                            session,
                                            refs,
                                            "Align texture",
                                            (current, normal) -> current.alignWall(normal),
                                            "It is too flat for Wall.");
                                default ->
                                    changed(
                                            session,
                                            refs,
                                            change.label(),
                                            change::apply,
                                            "It has this mapping already.");
                            });
                }));
    }

    static Step shown(EditingSession session, ToolResult result) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHOWN_MILLIS);
        return () -> {
            if (session.isSurfacePreviewSettled()) {
                var missing = session.getSurfaceMapsNotShown();
                return missing.isEmpty()
                        ? result
                        : result.withText("The change was made, but the viewport could not show it on "
                                + String.join(", ", missing)
                                + ". It tries again at the next change, or when the map loads again.");
            }
            if (System.nanoTime() > deadline) {
                return result.withText("The change was made. The viewport still reads the map, so a picture taken"
                        + " now may not show it yet.");
            }
            return null;
        };
    }

    record Change(
            float panU,
            float panV,
            float turn,
            float @Nullable [] scale,
            @Nullable String flip) {
        @Nullable
        BspSurfaceMapping apply(BspSurfaceMapping current, Vector3 normal) {
            var next = current;
            if (panU != 0 || panV != 0) {
                next = next.pan(panU, panV);
            }
            if (turn != 0) {
                next = next.rotate(turn);
            }
            if (scale != null) {
                next = next.withScale(scale[0], scale[1]);
            }
            if ("u".equals(flip)) {
                next = next.flipU();
            } else if ("v".equals(flip)) {
                next = next.flipV();
            }
            return next;
        }

        String label() {
            if (scale != null) {
                return "Scale texture";
            }
            if (turn != 0) {
                return "Turn texture";
            }
            return flip != null ? "Flip texture" : "Pan texture";
        }
    }

    static Change change(ToolArgs args) throws ToolProblem {
        var pan = pair(args, "pan");
        var scale = pair(args, "scale");
        if (scale != null && (!(scale[0] > 0) || !(scale[1] > 0))) {
            throw new ToolProblem("scale must be two sizes above 0, such as [2, 2].");
        }
        var flip = args.getText("flip");
        if (flip != null && !flip.equalsIgnoreCase("u") && !flip.equalsIgnoreCase("v")) {
            throw new ToolProblem("flip must be u or v.");
        }
        var turn = (float) args.getNumber("turn", 0);
        if (pan == null && scale == null && flip == null && turn == 0) {
            throw new ToolProblem(
                    "Say what to change: pan, turn, scale or flip. floor, wall and revert are actions of their own.");
        }
        return new Change(
                pan == null ? 0 : pan[0],
                pan == null ? 0 : pan[1],
                turn,
                scale,
                flip == null ? null : flip.toLowerCase(Locale.ROOT));
    }

    static @Nullable MaterialChoice material(ToolArgs args) throws ToolProblem {
        var material = args.getText("material");
        if (material == null) {
            throw new ToolProblem("Say which material: material, a path such as Package.Texture, or default.");
        }
        if (material.equalsIgnoreCase("default")) {
            return null;
        }
        if (material.indexOf('.') <= 0) {
            throw new ToolProblem(
                    "material must be a path such as Package.Texture. assets_find lists the materials of the client.");
        }
        var file = args.getText("file");
        if (file == null) {
            return new MaterialChoice(material, null);
        }
        try {
            var path = Path.of(file);
            if (!path.isAbsolute()) {
                throw new ToolProblem("file must be a full path.");
            }
            return new MaterialChoice(material, path.normalize());
        } catch (InvalidPathException wrong) {
            throw new ToolProblem("file is not a path: " + wrong.getMessage());
        }
    }

    static Map<BspSurfaceFlag, Boolean> flags(ToolArgs args) throws ToolProblem {
        var result = new EnumMap<BspSurfaceFlag, Boolean>(BspSurfaceFlag.class);
        if (args.getJson().containsKey("two_sided")) {
            result.put(BspSurfaceFlag.TWO_SIDED, args.readFlag("two_sided", false));
        }
        if (args.getJson().containsKey("unlit")) {
            result.put(BspSurfaceFlag.UNLIT, args.readFlag("unlit", false));
        }
        if (result.isEmpty()) {
            throw new ToolProblem("Say which flag: two_sided, unlit or both, each true or false.");
        }
        return result;
    }

    static String flagLabel(Map<BspSurfaceFlag, Boolean> flags, int surfaces) {
        var parts = new ArrayList<String>();
        flags.forEach((flag, on) ->
                parts.add((flag == BspSurfaceFlag.TWO_SIDED ? "two-sided" : "unlit") + (on ? "" : " off")));
        var text = String.join(" and ", parts);
        return Character.toUpperCase(text.charAt(0))
                + text.substring(1)
                + (surfaces > 1 ? " of " + surfaces + " surfaces" : "");
    }

    static BspSurfaceSelect.Command command(ToolArgs args) throws ToolProblem {
        var by = args.getText("by");
        if (by == null) {
            throw new ToolProblem("Say how to choose with by: " + String.join(", ", SELECTS) + ".");
        }
        var at = SELECTS.indexOf(by.toLowerCase(Locale.ROOT));
        if (at < 0) {
            throw new ToolProblem("by must be one of " + String.join(", ", SELECTS) + ".");
        }
        return BspSurfaceSelect.Command.values()[at];
    }

    static Object csg(int csgOper) {
        return switch (csgOper) {
            case BrushFacts.ADD -> "add";
            case BrushFacts.SUBTRACT -> "subtract";
            default -> csgOper;
        };
    }

    private static ToolResult select(
            EditingSession session,
            List<SurfaceRef> refs,
            BspSurfaceSelect.Command command,
            @Nullable String map,
            int limit)
            throws ToolProblem {
        List<SurfaceRef> chosen;
        try {
            chosen = session.selectSurfaces(refs, command, map);
        } catch (IllegalArgumentException unreadable) {
            throw new ToolProblem(unreadable.getMessage());
        }
        var before = new HashSet<>(refs);
        var added = 0;
        for (var surface : chosen) {
            if (!before.contains(surface)) {
                added++;
            }
        }
        var paths = new JSONArray();
        for (var surface : chosen.subList(0, Math.min(limit, chosen.size()))) {
            paths.add(surface.path());
        }
        var data = new JSONObject();
        data.put("surfaces", paths);
        data.put("count", chosen.size());
        data.put("added", added);
        if (command == BspSurfaceSelect.Command.BRUSH) {
            var brushes = new JSONArray();
            var seen = new HashSet<BrushRef>();
            for (var ref : refs) {
                var brush = session.getSurfaceBrushActor(ref);
                var facts = brush == null ? null : session.getBrushFacts(brush);
                if (facts == null || !seen.add(brush)) {
                    continue;
                }
                var row = new JSONObject();
                row.put("name", facts.name());
                row.put("map", brush.map());
                row.put("csg", csg(facts.csgOper()));
                row.put("surface_count", facts.sides());
                brushes.add(row);
            }
            data.put("brushes", brushes);
        }
        return ToolResult.data(data);
    }

    private static JSONObject pair(String text) {
        var item = new JSONObject();
        item.put("type", "number");
        var schema = new JSONObject();
        schema.put("type", "array");
        schema.put("description", text);
        schema.put("items", item);
        schema.put("minItems", 2);
        schema.put("maxItems", 2);
        return schema;
    }

    private static float @Nullable [] pair(ToolArgs args, String key) throws ToolProblem {
        var value = args.getJson().get(key);
        if (value == null) {
            return null;
        }
        var numbers = ActorChange.numbers(value, key);
        if (numbers.length != 2) {
            throw new ToolProblem(key + " must be two numbers: U and V.");
        }
        return numbers;
    }

    private static ToolResult read(EditingSession session, List<SurfaceRef> refs, boolean brushData)
            throws ToolProblem {
        var rows = new JSONArray();
        for (var ref : refs) {
            rows.add(row(session, ref, brushData));
        }
        var data = new JSONObject();
        data.put("surfaces", rows);
        return ToolResult.data(data);
    }

    private static JSONObject row(EditingSession session, SurfaceRef ref) throws ToolProblem {
        return row(session, ref, false);
    }

    private static JSONObject row(EditingSession session, SurfaceRef ref, boolean brushData) throws ToolProblem {
        try {
            var mapping = session.getSurfaceMapping(ref);
            var row = new JSONObject();
            row.put("surface", ref.path());
            row.put("scale", JSONArray.of(EditSteps.round(mapping.scaleU()), EditSteps.round(mapping.scaleV())));
            row.put("u", vector(mapping.u()));
            row.put("v", vector(mapping.v()));
            row.put("base", EditSteps.point(mapping.base()));
            row.put("normal", vector(session.getSurfaceNormal(ref)));
            row.put("middle", EditSteps.point(EditingSession.center(session.getSurfacePolygons(ref))));
            var tile = session.getSurfaceTile(ref);
            if (tile != null) {
                row.put("tile_texels", JSONArray.of(Math.round(tile[0]), Math.round(tile[1])));
                row.put(
                        "tile_units",
                        JSONArray.of(Math.round(tile[0] * mapping.scaleU()), Math.round(tile[1] * mapping.scaleV())));
            }
            var material = session.getSurfaceMaterial(ref);
            var own = session.getSourceSurfaceMaterial(ref);
            var deleted = session.isSurfaceInDeletedBrush(ref);
            var twoSided = session.isSurfaceFlagOn(ref, BspSurfaceFlag.TWO_SIDED);
            var unlit = session.isSurfaceFlagOn(ref, BspSurfaceFlag.UNLIT);
            row.put(
                    "changed",
                    !mapping.equals(session.getSourceSurfaceMapping(ref))
                            || session.isSurfaceHidden(ref)
                            || deleted
                            || !own.equals(material)
                            || twoSided != session.isSourceSurfaceFlagOn(ref, BspSurfaceFlag.TWO_SIDED)
                            || unlit != session.isSourceSurfaceFlagOn(ref, BspSurfaceFlag.UNLIT));
            row.put("hidden", session.isSurfaceHidden(ref));
            row.put("two_sided", twoSided);
            row.put("unlit", unlit);
            row.put("sheet", session.isSurfaceSheet(ref));
            var brush = session.getSurfaceBrush(ref);
            if (brush != null) {
                row.put("brush", session.getBrushName(brush));
                row.put("brush_surfaces", session.getBrushSurfaces(brush).size());
                var facts = session.getBrushFacts(brush);
                if (facts != null) {
                    row.put("brush_csg", csg(facts.csgOper()));
                }
            }
            var item = session.getSurfaceItemName(ref);
            if (!item.isEmpty()) {
                row.put("brush_item", item);
            }
            if (deleted) {
                row.put("brush_deleted", true);
            }
            row.put("material", material);
            if (!own.equals(material)) {
                row.put("client_material", own);
            }
            var reason = session.getSurfaceRestriction(ref);
            if (!reason.isEmpty()) {
                row.put("restriction", reason);
            }
            var flagReason = flagsRestriction(session, ref, twoSided, unlit);
            if (!flagReason.isEmpty()) {
                row.put("flags_restriction", flagReason);
            }
            if (!session.isSurfaceHidden(ref) && !deleted) {
                var hide = session.getSurfaceHideRestriction(ref);
                if (!hide.isEmpty()) {
                    row.put("hide_restriction", hide);
                }
                var delete = session.getSurfaceDeleteRestriction(ref);
                if (!delete.isEmpty() && brush != null) {
                    delete = brushData ? session.getBrushDeleteRestriction(brush) : "";
                }
                if (!delete.isEmpty()) {
                    row.put("delete_restriction", delete);
                }
            }
            return row;
        } catch (IOException | RuntimeException unreadable) {
            throw new ToolProblem("The surface " + ref.path() + " cannot be read: " + unreadable.getMessage());
        }
    }

    private static String flagsRestriction(EditingSession session, SurfaceRef ref, boolean twoSided, boolean unlit) {
        var sides = session.getSurfaceFlagRestriction(ref, BspSurfaceFlag.TWO_SIDED, !twoSided);
        var light = session.getSurfaceFlagRestriction(ref, BspSurfaceFlag.UNLIT, !unlit);
        if (sides.equals(light)) {
            return sides;
        }
        var parts = new ArrayList<String>();
        if (!sides.isEmpty()) {
            parts.add((twoSided ? "Two-sided cannot go: " : "Two-sided cannot be set: ") + sides + ".");
        }
        if (!light.isEmpty()) {
            parts.add((unlit ? "Unlit cannot go: " : "Unlit cannot be set: ") + light + ".");
        }
        return String.join(" ", parts);
    }

    private static JSONArray vector(Vector3 value) {
        return JSONArray.of(round(value.x()), round(value.y()), round(value.z()));
    }

    private static double round(float value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private static ToolResult changed(
            EditingSession session,
            List<SurfaceRef> refs,
            String verb,
            BiFunction<BspSurfaceMapping, Vector3, @Nullable BspSurfaceMapping> change,
            String unchanged)
            throws ToolProblem {
        var refused = new Refusals();
        var chosen = new ArrayList<SurfaceRef>();
        for (var ref : refs) {
            var reason = session.getSurfaceEditRestriction(ref.map());
            if (reason.isEmpty()) {
                reason = session.getSurfaceRestriction(ref);
            }
            if (reason.isEmpty()) {
                reason = refusal(session, ref, change, unchanged);
            }
            if (reason.isEmpty()) {
                chosen.add(ref);
            } else {
                refused.add(reason, ref.path());
            }
        }
        var label = EditSteps.AI + verb + (chosen.size() > 1 ? " of " + chosen.size() + " surfaces" : "");
        var count = chosen.isEmpty() ? 0 : session.editSurfaces(chosen, label, change);
        var data = answer(session, chosen, refused, count);
        if (count > 0) {
            data.put("step", label);
        }
        return ToolResult.data(data);
    }

    private static String refusal(
            EditingSession session,
            SurfaceRef ref,
            BiFunction<BspSurfaceMapping, Vector3, @Nullable BspSurfaceMapping> change,
            String unchanged)
            throws ToolProblem {
        try {
            var current = session.getSurfaceMapping(ref);
            var next = change.apply(current, session.getSurfaceNormal(ref));
            if (next == null || next.equals(current)) {
                return unchanged;
            }
            return next.restriction();
        } catch (IOException | IllegalArgumentException unreadable) {
            return "It cannot be read: " + unreadable.getMessage();
        }
    }

    private static ToolResult revert(EditingSession session, List<SurfaceRef> refs) throws ToolProblem {
        var edited = session.getEditedSurfaces();
        var refused = new Refusals();
        var chosen = new ArrayList<SurfaceRef>();
        for (var ref : refs) {
            if (edited.contains(ref) || session.isSurfaceInDeletedBrush(ref)) {
                chosen.add(ref);
            } else {
                refused.add("It is as in the client.", ref.path());
            }
        }
        var label = EditSteps.AI + (chosen.size() == 1 ? "Revert surface" : "Revert " + chosen.size() + " surfaces");
        if (!chosen.isEmpty()) {
            session.resetSurfaces(chosen, label);
        }
        var data = answer(session, chosen, refused, chosen.size());
        if (!chosen.isEmpty()) {
            data.put("step", label);
        }
        return ToolResult.data(data);
    }

    private static ToolResult hide(EditingSession session, List<SurfaceRef> refs) throws ToolProblem {
        var refused = new Refusals();
        var chosen = new ArrayList<SurfaceRef>();
        for (var ref : refs) {
            var reason =
                    session.isSurfaceHidden(ref) ? "It is hidden already." : session.getSurfaceHideRestriction(ref);
            if (reason.isEmpty()) {
                chosen.add(ref);
            } else {
                refused.add(reason, ref.path());
            }
        }
        var label = EditSteps.AI + "Hide " + (chosen.size() == 1 ? "surface" : chosen.size() + " surfaces");
        var count = chosen.isEmpty() ? 0 : session.setSurfacesHidden(chosen, true, label);
        var data = answer(session, chosen, refused, count);
        if (count > 0) {
            data.put("step", label);
        }
        if (count > 0 && chosen.stream().anyMatch(ref -> !session.isSurfaceSheet(ref))) {
            data.put(
                    "note",
                    "The game no longer draws it, but a hidden wall or floor still blocks the player, as the BSP's"
                            + " nodes decide what blocks, not the surface flags. delete takes a sheet, or a brush that"
                            + " stands apart, out of the game.");
        }
        return ToolResult.data(data);
    }

    private static ToolResult showAll(EditingSession session) throws ToolProblem {
        var surfaces = session.getHiddenSurfaces();
        var brushes = session.getDeletedBrushes();
        var count = surfaces.size() + brushes.size();
        var label = EditSteps.AI + "Show " + (count == 1 ? "1 row" : count + " rows");
        if (count > 0) {
            session.showAll(surfaces, brushes, label);
        }
        var data = answer(session, surfaces, new Refusals(), count);
        if (count > 0) {
            data.put("step", label);
        }
        if (!brushes.isEmpty()) {
            var back = new JSONArray();
            for (var brush : brushes) {
                back.add(brush.map() + "." + session.getBrushName(brush));
            }
            data.put("brushes_back", back);
        }
        return ToolResult.data(data);
    }

    private static ToolResult show(EditingSession session, List<SurfaceRef> refs) throws ToolProblem {
        var refused = new Refusals();
        var chosen = new ArrayList<SurfaceRef>();
        for (var ref : refs) {
            if (session.isSurfaceHidden(ref)) {
                chosen.add(ref);
            } else if (session.isSurfaceInDeletedBrush(ref)) {
                refused.add("It belongs to a deleted brush. revert brings the brush back.", ref.path());
            } else if (session.getSurfaceHideRestriction(ref).contains("already")) {
                refused.add("The client itself hides it, so it stays hidden.", ref.path());
            } else {
                refused.add("It is not hidden.", ref.path());
            }
        }
        var label = EditSteps.AI + "Show " + (chosen.size() == 1 ? "surface" : chosen.size() + " surfaces");
        var count = chosen.isEmpty() ? 0 : session.setSurfacesHidden(chosen, false, label);
        var data = answer(session, chosen, refused, count);
        if (count > 0) {
            data.put("step", label);
        }
        return ToolResult.data(data);
    }

    private static ToolResult delete(ViewerContext viewer, EditingSession session, List<SurfaceRef> refs)
            throws ToolProblem {
        var refused = new Refusals();
        var sheets = new ArrayList<SurfaceRef>();
        var brushes = new LinkedHashSet<BrushRef>();
        for (var ref : refs) {
            if (session.isSurfaceHidden(ref) || session.isSurfaceInDeletedBrush(ref)) {
                refused.add("It is hidden or deleted already.", ref.path());
                continue;
            }
            var reason = session.getSurfaceDeleteRestriction(ref);
            if (reason.isEmpty()) {
                sheets.add(ref);
                continue;
            }
            var brush = session.getSurfaceBrush(ref);
            if (brush != null) {
                reason = session.getBrushDeleteRestriction(brush);
                if (reason.isEmpty()) {
                    brushes.add(brush);
                    continue;
                }
            }
            refused.add(reason + ".", ref.path());
        }
        var chosen = new ArrayList<SurfaceRef>(sheets);
        var deletedBrushes = new JSONArray();
        var resting = 0;
        for (var brush : brushes) {
            var surfaces = session.getBrushSurfaces(brush);
            surfaces.stream().filter(surface -> !chosen.contains(surface)).forEach(chosen::add);
            var row = new JSONObject();
            row.put("brush", session.getBrushName(brush));
            row.put("map", brush.map());
            row.put("surface_count", surfaces.size());
            var objects = EditingCheck.getActorsOnBrush(
                    session, brush, EditSteps.bounds(viewer, EditSteps.mapActors(session, brush.map())));
            if (!objects.isEmpty()) {
                row.put("objects_on_it", objects.subList(0, Math.min(20, objects.size())));
                row.put("object_count", objects.size());
                resting += objects.size();
            }
            deletedBrushes.add(row);
        }
        var names = new ArrayList<String>();
        if (brushes.size() == 1) {
            names.add(session.getBrushName(brushes.getFirst()));
        } else if (brushes.size() > 1) {
            names.add(brushes.size() + " brushes");
        }
        if (!sheets.isEmpty()) {
            names.add(
                    sheets.size() == 1 && brushes.isEmpty()
                            ? "sheet"
                            : sheets.size() + (sheets.size() == 1 ? " sheet" : " sheets"));
        }
        var label = EditSteps.AI + "Delete " + String.join(" and ", names);
        var count =
                brushes.isEmpty() && sheets.isEmpty() ? 0 : session.deleteBrushes(List.copyOf(brushes), sheets, label);
        var data = answer(session, chosen, refused, count);
        if (count > 0) {
            data.put("step", label);
        }
        if (!brushes.isEmpty()) {
            data.put("deleted_brushes", deletedBrushes);
            data.put(
                    "note",
                    "The game no longer draws a deleted brush or blocks in its space. This follows the client's code"
                            + " and was not tried in a game. The server's geodata still blocks there: install the saved"
                            + " map first, then build geodata with geo_build and install it. revert on one of its"
                            + " surfaces brings it back."
                            + (resting == 0
                                    ? ""
                                    : " " + EditorUi.plural(resting, "object stands", "objects stand")
                                            + " on it or against it and may float in the air now. edit_changes lists"
                                            + " them."));
        }
        return ToolResult.data(data);
    }

    private static ToolResult flags(EditingSession session, List<SurfaceRef> refs, Map<BspSurfaceFlag, Boolean> flags)
            throws ToolProblem {
        var refused = new Refusals();
        var chosen = new ArrayList<SurfaceRef>();
        for (var ref : refs) {
            var reason = session.getSurfaceEditRestriction(ref.map());
            var changes = false;
            var already = reason.isEmpty();
            for (var entry : reason.isEmpty()
                    ? flags.entrySet()
                    : Map.<BspSurfaceFlag, Boolean>of().entrySet()) {
                try {
                    if (session.isSurfaceFlagOn(ref, entry.getKey()) == entry.getValue()) {
                        continue;
                    }
                } catch (IOException | IllegalArgumentException unreadable) {
                    reason = "It cannot be read: " + unreadable.getMessage();
                    break;
                }
                already = false;
                var refusal = session.getSurfaceFlagRestriction(ref, entry.getKey(), entry.getValue());
                if (refusal.isEmpty()) {
                    changes = true;
                } else if (reason.isEmpty()) {
                    reason = refusal + ".";
                }
            }
            if (changes) {
                chosen.add(ref);
            } else {
                refused.add(reason.isEmpty() && already ? "It has these flags already." : reason, ref.path());
            }
        }
        var label = EditSteps.AI + flagLabel(flags, chosen.size());
        int count;
        try {
            count = chosen.isEmpty() ? 0 : session.setSurfaceFlags(chosen, flags, label);
        } catch (IllegalArgumentException | IllegalStateException refusedFlags) {
            throw new ToolProblem(refusedFlags.getMessage());
        }
        var data = answer(session, chosen, refused, count);
        if (count > 0) {
            data.put("step", label);
            data.put(
                    "note",
                    "Nothing blocks differently. A rebuild of the geometry in UnrealEd brings the old flags back, as"
                            + " the brush polygons keep them.");
        }
        return ToolResult.data(data);
    }

    private static ToolResult material(EditingSession session, List<SurfaceRef> refs, @Nullable MaterialChoice choice)
            throws ToolProblem {
        var refused = new Refusals();
        var chosen = new ArrayList<SurfaceRef>();
        for (var ref : refs) {
            var reason = session.getSurfaceEditRestriction(ref.map());
            if (reason.isEmpty()) {
                reason = session.getSurfaceMaterialRestriction(ref);
            }
            try {
                var wanted = choice == null ? session.getSourceSurfaceMaterial(ref) : choice.path();
                if (reason.isEmpty() && session.getSurfaceMaterial(ref).equalsIgnoreCase(wanted)) {
                    reason = "It has this material already.";
                }
            } catch (IOException unreadable) {
                reason = "It cannot be read: " + unreadable.getMessage();
            }
            if (reason.isEmpty()) {
                chosen.add(ref);
            } else {
                refused.add(reason, ref.path());
            }
        }
        var label = EditSteps.AI + "Material of " + (chosen.size() == 1 ? "surface" : chosen.size() + " surfaces");
        int count;
        try {
            count = chosen.isEmpty() ? 0 : session.setSurfaceMaterials(chosen, choice, label);
        } catch (IllegalArgumentException refusedMaterial) {
            throw new ToolProblem(refusedMaterial.getMessage());
        }
        var data = answer(session, chosen, refused, count);
        if (count > 0) {
            data.put("step", label);
        }
        return ToolResult.data(data);
    }

    private static JSONObject answer(EditingSession session, List<SurfaceRef> chosen, Refusals refused, int count)
            throws ToolProblem {
        var data = new JSONObject();
        data.put("changed", count);
        data.put("could_not", refused.getCount());
        if (refused.getCount() > 0) {
            data.put("reasons", refused.write());
        }
        var rows = new JSONArray();
        for (var ref : chosen.subList(0, Math.min(20, chosen.size()))) {
            rows.add(row(session, ref));
        }
        data.put("surfaces", rows);
        return data;
    }
}

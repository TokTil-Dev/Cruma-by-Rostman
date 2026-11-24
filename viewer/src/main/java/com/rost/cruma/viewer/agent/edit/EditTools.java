package com.rost.cruma.viewer.agent.edit;

import static com.rost.cruma.viewer.agent.edit.EditSteps.MOST_MAPS;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.MeshLibrary;
import org.jetbrains.annotations.Nullable;

public final class EditTools implements ToolGroup {
    private @Nullable MeshLibrary library;
    private final ActorPlaceTool placeTool = new ActorPlaceTool(this);
    private final ActorChangeTool changeTool = new ActorChangeTool(this);

    @Override
    public void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "edit_mode",
                                "Start or end Edit",
                                "Starts Edit, so the edit tools work, or ends it. The tiles load again, so it takes a"
                                        + " while.")
                        .flag("on", "True starts Edit, false ends it. True when left out.")
                        .flag("discard", "End Edit even when changes are not saved. They are lost.")
                        .access(Access.EDIT)
                        .destructive(),
                EditModeTool::mode);
        tools.viewer(
                ToolSpec.named(
                                "actors_place",
                                "Place actors",
                                "Places meshes or a prefab as one Undo step, also on the ground. Give target for one,"
                                        + " points for many, or items when each differs.")
                        .text("mesh", "A mesh: its path such as Meshes.Pillar, or its name. assets_find lists them.")
                        .text(
                                "prefab",
                                "A prefab in place of a mesh: a name in the prefab folder, a full path, or its text."
                                        + " Its bottom goes on target.")
                        .choice(
                                "list",
                                "Lists the Library's favourite meshes, the meshes placed in this session or the"
                                        + " prefabs, and places nothing.",
                                "favourites",
                                "recent",
                                "prefabs")
                        .target("Where to place it.")
                        .list("points", "Many places for the same mesh, each a target.")
                        .schema(
                                "rotation",
                                degrees("The turn in degrees: yaw, or yaw, pitch and roll. Yaw 0 looks east and 90"
                                        + " south."))
                        .number("scale", "The size. 1 when left out.")
                        .flag(
                                "on_ground",
                                "Stand each mesh on the Terrain or the BSP floor. With no height in the target it"
                                        + " takes the highest floor seen from above. Other meshes are no ground.")
                        .schema("items", items())
                        .integer("limit", "How many new actors to list. 20 when left out.")
                        .access(Access.EDIT),
                placeTool::place);
        tools.viewer(
                ToolSpec.named(
                                "actors_change",
                                "Change actors",
                                "Changes actors as one Undo step, by list or by filter: move, turn, scale, set, face,"
                                    + " mirror, align, randomize, copy, revert, replace mesh, hide, lock, group, save"
                                    + " as prefab. Meshes, and the effects of a map, which take no scale. A mesh that"
                                    + " carries others takes them along, also in a copy, and note names what stayed."
                                    + " Says why some could not.")
                        .choice(
                                "action",
                                "change when left out. revert takes paths of edit_changes. show and unlock with no"
                                        + " actors take all.",
                                "change",
                                "revert",
                                "replace_mesh",
                                "hide",
                                "show",
                                "lock",
                                "unlock",
                                "group",
                                "ungroup",
                                "save_prefab")
                        .list("actors", "Actor paths, or selection.")
                        .schema("where", where())
                        .schema("move_by", vector("The move in world units: x, y and z."))
                        .text("move_to", "A target. The middle of the actors goes there and they keep their shape.")
                        .schema("turn_by", degrees("The turn of each actor in degrees: yaw, or yaw, pitch and roll."))
                        .number("scale", "The factor of each actor's size, such as 1.5.")
                        .flag(
                                "to_ground",
                                "Then stand each actor on the Terrain or the BSP floor, keeping the depth it has in"
                                        + " the client.")
                        .flag("copy", "Change copies and leave the actors where they are.")
                        .schema("set", exact())
                        .text("face", "A target each actor turns to face about the vertical axis, such as camera.")
                        .choice("mirror", "Mirror: turns the sign of this axis of DrawScale3D.", "x", "y", "z")
                        .choice(
                                "align",
                                "Lines the actors up by their drawn bounds, as Align, Same height and Distribute.",
                                ActorActions.ALIGN.toArray(String[]::new))
                        .schema("randomize", randomize())
                        .text("mesh", "For replace_mesh, the new mesh. The materials of the slots it has stay.")
                        .text("group", "For group, its name. For ungroup, the group to take away.")
                        .text("file", "For save_prefab, a name in the prefab folder or a full path. Needs Write.")
                        .flag(
                                "preview",
                                "Change nothing. Say what would change, with the height of each actor's bottom in the"
                                        + " scene.")
                        .integer("limit", "How many actors to list. 20 when left out.")
                        .access(Access.EDIT),
                changeTool::change);
        tools.viewer(
                ToolSpec.named(
                                "actors_delete",
                                "Delete actors",
                                "Deletes actors as one Undo step, by list or by filter. Says how many went and why"
                                        + " others could not.")
                        .list("actors", "Actor paths, or selection.")
                        .schema("where", where())
                        .flag("preview", "Delete nothing. Say what would go.")
                        .integer("limit", "How many actors to list. 20 when left out.")
                        .access(Access.EDIT)
                        .destructive(),
                ActorDeleteTool::delete);
        tools.viewer(
                ToolSpec.named(
                                "lights_edit",
                                "Edit lights",
                                "Lists, adds, copies, changes, deletes and reverts lights, and turns spotlights and"
                                        + " sets their cone. Each call is one Undo step, and a light that refuses says"
                                        + " why.")
                        .choice(
                                "action",
                                "What to do. list when left out. copy puts a copy beside each light, or at target."
                                        + " revert gives the client's light back.",
                                "list",
                                "add",
                                "copy",
                                "change",
                                "delete",
                                "revert")
                        .list("lights", "Light paths from list. Needed for copy, change, delete and revert.")
                        .target("For add, copy and change, where the light stands. For list, the middle of the circle."
                                + " The camera when list leaves it out.")
                        .number(
                                "radius",
                                "For add and change, the LightRadius: the light reaches 25 * (radius + 1) units. For"
                                        + " list, the circle in world units.")
                        .number("brightness", "LightBrightness, 0 to 1024.")
                        .choice(
                                "preset",
                                "A colour and brightness of the Lights tool.",
                                "candle",
                                "lamp",
                                "daylight",
                                "moonlight",
                                "fire",
                                "magic",
                                "poison")
                        .number(
                                "temperature",
                                "The colour of a glowing body in kelvin, 1000 to 12000: 1900 a candle, 5500 daylight.")
                        .number(
                                "saturation_pct",
                                "Saturation as the Lights tool shows it: 0 white, 100 the full colour of the hue.")
                        .integer("hue", "LightHue, 0 to 255.")
                        .integer("saturation", "LightSaturation, 0 to 255. 255 is white and 0 the full colour.")
                        .flag(
                                "spot",
                                "For add, a spotlight. It faces straight down unless yaw, pitch or face say otherwise.")
                        .number(
                                "yaw",
                                "For a spotlight, the way it faces in degrees: 0 east, 90 south. Its pitch stays when"
                                        + " left out.")
                        .number(
                                "pitch",
                                "For a spotlight, degrees from -90, straight down, through 0, level, to 90, straight"
                                        + " up. Its yaw stays when left out.")
                        .text(
                                "face",
                                "For a spotlight, a target with a height that it turns to face from where it stands,"
                                        + " in place of yaw and pitch.")
                        .number(
                                "cone",
                                "For a spotlight, how wide it opens, as the full angle in degrees from 0 to "
                                        + (int) LightAim.MOST_CONE + ". 120 is LightCone 128.")
                        .integer("limit", "How many lights to list. 20 when left out.")
                        .access(Access.EDIT)
                        .destructive(),
                LightsTool::lights);
        tools.viewer(
                ToolSpec.named(
                                "terrain_edit",
                                "Edit Terrain",
                                "Sculpts a Terrain in a circle or along points, paints, fills or maps a layer, paints"
                                        + " decorations, hides cells, turns edges or reverts, as one Undo step. info"
                                        + " lists layers and decorations.")
                        .choice(
                                "action",
                                "What to do. select selects the brush's ground and moves it by amount. turn and"
                                        + " restore change the diagonals of cells.",
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
                                "info")
                        .target("The middle of the circle, or the start of a stroke.")
                        .list("points", "More targets after target: one stroke along them. For ramp, its end alone.")
                        .number("radius", "The circle in world units. 512 when left out.")
                        .choice("size", "In place of radius: 4, 8 or 16 points of the grid.", "S", "M", "L")
                        .number(
                                "amount",
                                "raise and lower: world units at target, or along a stroke. noise: height of bumps,"
                                        + " 64. select: units to move, below 0 lowers.")
                        .number(
                                "height",
                                "flatten and set_height: the height to reach. The ground at target when left out.")
                        .number(
                                "strength",
                                "flatten, smooth, noise, paint and erase: 0 to 1. 1 reaches the aim at the middle.")
                        .choice(
                                "falloff",
                                "How the brush fades to its edge. smooth when left out.",
                                "smooth",
                                "linear",
                                "sharp")
                        .number("width", "ramp: world units wide, 512. noise: units between bumps, 512.")
                        .schema(
                                "edge",
                                numberList("select: the soft edge, inner and outer in world units, [0,512]: all moves"
                                        + " within inner, nothing past outer."))
                        .text("layer", "paint, erase, fill, mapping, revert: a layer's number or words of its texture.")
                        .text(
                                "decoration",
                                "paint, erase, fill, revert: in place of layer, a decoration's number or words of its"
                                        + " mesh. Its density map says where its grass or bushes grow.")
                        .flag("cover", "paint and fill: Cover the layers above, so the layer shows.")
                        .number("slope", "fill: paint where the ground is steeper, in degrees.")
                        .schema("between", numberList("fill: paint where the ground lies between two world heights."))
                        .schema(
                                "scale",
                                numberList("mapping: grid points one repeat covers, U and V, or one for both."))
                        .schema("pan", numberList("mapping: shift in whole textures, U and V."))
                        .number("rotation", "mapping: turn in degrees.")
                        .choice(
                                "what",
                                "revert: what goes back to the client. edges: hidden cells and diagonals. decoration"
                                        + " when left out with decoration.",
                                "heights",
                                "layer",
                                "mapping",
                                "edges",
                                "decoration")
                        .required("action", "target")
                        .access(Access.EDIT),
                TerrainEdit::start);
        tools.viewer(
                ToolSpec.named(
                                "edit_history",
                                "Edit history",
                                "Lists the Undo steps, or takes them back or again. Actors and Terrain share one"
                                        + " order.")
                        .choice("action", "What to do. list when left out.", "list", "undo", "redo")
                        .integer("count", "How many steps to undo or redo. 1 when left out.")
                        .integer("limit", "How many steps to list. 20 when left out.")
                        .access(Access.EDIT),
                EditHistoryTool::history);
        tools.viewer(
                ToolSpec.named(
                                "edit_changes",
                                "Unsaved changes",
                                "What changed since the last save, by kind and by map, with warnings such as actors"
                                        + " that float.")
                        .integer("limit", "How many changes to list. 20 when left out."),
                EditChangesTool::changes);
        tools.viewer(
                ToolSpec.named(
                                "edit_export",
                                "Save changes",
                                "Checks the changes, fixes warnings, saves them to a folder, installs a save into the"
                                        + " client or restores the client.")
                        .choice(
                                "action",
                                "What to do. check when left out. fix applies the fix of the warnings, as Fix all."
                                        + " fix: Needs Edit. save, install, restore: Needs Write.",
                                "check",
                                "fix",
                                "save",
                                "install",
                                "restore")
                        .text(
                                "folder",
                                "For save, a new folder outside the client. For install and restore, a saved folder,"
                                        + " or the last save when left out.")
                        .schema("lighting", EditExport.lighting())
                        .choice(
                                "kind",
                                "For fix, the kind of warning. Needed when there are several.",
                                "floating",
                                "outside",
                                "copies")
                        .list("actors", "For fix, only the warnings of these actors.")
                        .writes()
                        .destructive(),
                EditExport::start);
        SurfaceEdit.register(tools);
        MaterialEdit.register(tools);
    }

    @Nullable
    MeshLibrary getReadyLibrary() {
        return library != null && library.areMeshesReady() ? library : null;
    }

    MeshLibrary getLibrary(ViewerContext viewer, EditingSession session) throws ToolProblem {
        if (library == null) {
            var directory = viewer.configDirectory();
            if (directory == null) {
                throw new ToolProblem("This viewer keeps no files, so give the full path of the mesh.");
            }
            library = new MeshLibrary(directory, session.getClientRoot());
            library.start();
        }
        return library;
    }

    private static JSONObject vector(String text) {
        var schema = numberList(text);
        schema.put("minItems", 3);
        schema.put("maxItems", 3);
        return schema;
    }

    private static JSONObject exact() {
        var properties = new JSONObject();
        properties.put("location", vector("x, y and z."));
        properties.put("rotation", degrees("Degrees: yaw, or yaw, pitch and roll."));
        properties.put("scale", word("DrawScale, above 0.").fluentPut("type", "number"));
        properties.put("scale3d", vector("DrawScale3D: x, y and z."));
        var schema = new JSONObject();
        schema.put("type", "object");
        schema.put("description", "Exact values, as the Transform fields. What it leaves out stays.");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static JSONObject randomize() {
        var properties = new JSONObject();
        properties.put("yaw", word("A random yaw. True when left out.").fluentPut("type", "boolean"));
        var scale = new JSONObject();
        scale.put("description", "[min, max] factors of the client's size, or false. 0.9 to 1.1 when left out.");
        properties.put("scale", scale);
        properties.put("seed", word("The same seed gives the same values.").fluentPut("type", "integer"));
        var schema = new JSONObject();
        schema.put("type", "object");
        schema.put("description", "Randomize: each actor's own random yaw and size.");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static JSONObject degrees(String text) {
        var schema = numberList(text);
        schema.put("minItems", 1);
        schema.put("maxItems", 3);
        return schema;
    }

    private static JSONObject numberList(String text) {
        var item = new JSONObject();
        item.put("type", "number");
        var schema = new JSONObject();
        schema.put("type", "array");
        schema.put("description", text);
        schema.put("items", item);
        return schema;
    }

    private static JSONObject where() {
        var properties = new JSONObject();
        properties.put("mesh", word("Words of the mesh path."));
        properties.put("name", word("Words of the actor path."));
        properties.put("class", word("Words of the class, such as StaticMeshActor."));
        properties.put("near", word("A target, the middle of a circle."));
        var radius = new JSONObject();
        radius.put("type", "number");
        radius.put("description", "The circle around near in world units, measured level. 2000 when left out.");
        properties.put("radius", radius);
        var schema = new JSONObject();
        schema.put("type", "object");
        schema.put(
                "description",
                "Which actors, so a call needs no list. All parts must fit. Without near it reads every map the viewer"
                        + " holds, at most "
                        + MOST_MAPS + ".");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static JSONObject items() {
        var properties = new JSONObject();
        properties.put("mesh", word("A mesh."));
        properties.put("target", word("Where to place it."));
        properties.put("rotation", degrees("The turn in degrees."));
        var scale = new JSONObject();
        scale.put("type", "number");
        scale.put("description", "The size.");
        properties.put("scale", scale);
        var item = new JSONObject();
        item.put("type", "object");
        item.put("properties", properties);
        item.put("additionalProperties", false);
        var schema = new JSONObject();
        schema.put("type", "array");
        schema.put("description", "Actors that differ. Each may leave out what the call gives for all.");
        schema.put("items", item);
        return schema;
    }

    private static JSONObject word(String text) {
        var property = new JSONObject();
        property.put("type", "string");
        property.put("description", text);
        return property;
    }
}

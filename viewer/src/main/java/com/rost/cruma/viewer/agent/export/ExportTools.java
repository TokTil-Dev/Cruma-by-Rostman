package com.rost.cruma.viewer.agent.export;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.export.ExportScale;
import com.rost.cruma.core.export.LocationExport;
import com.rost.cruma.core.export.LocationExport.Area;
import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.export.LocationExport.Settings;
import com.rost.cruma.core.export.NpcBatchExport;
import com.rost.cruma.core.export.NpcBatchExport.Entry;
import com.rost.cruma.core.export.NpcBatchExport.Summary;
import com.rost.cruma.core.export.NpcExport;
import com.rost.cruma.core.export.NpcExport.Npc;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.ExportRoom;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.export.AssetIndex.Kind;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigurationProblem;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.launch.BlenderApp;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.ClientTables;
import com.rost.cruma.viewer.launch.LocationExportRun;
import com.rost.cruma.viewer.spawn.SpawnModels;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class ExportTools implements ToolGroup {
    private static final double FIRST_SIDE_METERS = 400;
    private static final int FIRST_ASSETS = 30;
    private static final int MOST_ASSETS = 200;
    private static final int NPCS_NAMED = 10;
    private static final int MODELS_SHOWN = 30;
    private static final int ANIMATIONS_SHOWN = 60;
    private static final Map<Kind, String> USE = Map.of(
            Kind.MESH,
            "Place it with actors_place, with this path as the mesh. size is its width, depth and height in world"
                    + " units.",
            Kind.TEXTURE,
            "A material, by this path, such as a Terrain layer in terrain_edit. type says what it is: Texture is a"
                    + " plain picture.",
            Kind.SOUND,
            "A sound of a package, by this path, such as a sound of a scene in film_edit.",
            Kind.EFFECT,
            "An effect class, by this path, such as an effect of a scene in film_edit. The Effects tool of Cruma plays"
                    + " it.");

    private final Map<Path, AssetIndex> indexes = new ConcurrentHashMap<>();
    private final Map<Path, ClientLists> lists = new ConcurrentHashMap<>();

    private record Where(
            ClientInstallation client,
            Path asked,
            List<String> tiles,
            @Nullable Area area,
            int hour) {}

    static void requireOwnExport(Path folder, Path exports, Access access) throws ToolProblem {
        if (access.allows(Access.WRITE)) {
            return;
        }
        try {
            if (folder.toRealPath().startsWith(exports.toRealPath())) {
                return;
            }
        } catch (IOException unreadable) {
            throw new ToolProblem("Cannot read " + folder + ": " + unreadable.getMessage());
        }
        throw new ToolProblem(folder
                + " is outside the export folder, and Blender runs the script it finds there. That needs Write"
                + " access. Ask the person to raise it in Settings, AI agent.");
    }

    static ToolResult open(@Nullable Path blender, Path folder, boolean check, Job job)
            throws ToolProblem, InterruptedException {
        if (blender == null) {
            throw new ToolProblem(
                    "Blender is not on this computer. Cruma looks where its installers put it and on the PATH. The"
                            + " person can install it from blender.org, or open the .glb of "
                            + folder + " in another 3D program.");
        }
        if (check) {
            return ToolResult.data(BlenderCheck.run(blender, folder, job));
        }
        try {
            BlenderApp.open(blender, folder);
        } catch (IOException failed) {
            throw new ToolProblem("Blender did not start: " + failed.getMessage());
        }
        return ToolResult.text(
                "Blender is opening " + folder.getFileName() + " for the person. A large export takes a while.");
    }

    static Path exportFolder(@Nullable String asked, boolean replace, Path standard, Access access) throws ToolProblem {
        var usual = standard.toAbsolutePath().normalize();
        var folder = usual;
        if (asked != null) {
            folder = fullPath(asked, "folder");
        }
        if (!folder.equals(usual) && !access.allows(Access.WRITE)) {
            throw new ToolProblem("Exporting into another folder needs Write access, and Cruma gives "
                    + access.getLabel() + ". Leave folder out to use the export folder " + usual
                    + ", or ask the person to raise it in Settings, AI agent.");
        }
        if (replace && !access.allows(Access.WRITE)) {
            throw new ToolProblem("Replacing an earlier export needs Write access, and Cruma gives " + access.getLabel()
                    + ". Ask the person to raise it in Settings, AI agent.");
        }
        return folder;
    }

    static Path exportOf(String asked, Path standard) throws ToolProblem {
        try {
            var path = Path.of(asked);
            return (path.isAbsolute() ? path : standard.resolve(path))
                    .toAbsolutePath()
                    .normalize();
        } catch (InvalidPathException wrong) {
            throw new ToolProblem("folder is not a path: " + asked);
        }
    }

    static ExportScale scale(@Nullable Object asked, ExportScale standard) throws ToolProblem {
        var word = word(asked);
        if (word == null) {
            return standard;
        }
        switch (word.toLowerCase(Locale.ROOT)) {
            case "real", "real size", "real_size" -> {
                return ExportScale.REAL_SIZE;
            }
            case "unreal" -> {
                return ExportScale.UNREAL_UNITS;
            }
            case "metre", "meter", "1m", "1 m" -> {
                return ExportScale.METERS;
            }
            default -> {
                try {
                    return new ExportScale(Float.parseFloat(word));
                } catch (IllegalArgumentException wrong) {
                    throw new ToolProblem("scale must be real, unreal, metre or a number of centimetres from "
                            + ExportScale.MIN
                            + " to "
                            + ExportScale.MAX
                            + ", not "
                            + word
                            + ".");
                }
            }
        }
    }

    static List<String> wholeTiles(List<String> asked, List<String> installed) throws ToolProblem {
        var tiles = new ArrayList<>(new LinkedHashSet<>(asked));
        if (tiles.size() > LocationExport.MAX_TILES) {
            throw new ToolProblem("An export takes at most " + LocationExport.MAX_TILES + " tiles, and this asks for "
                    + tiles.size() + ". Export them in parts, or a square with target and side.");
        }
        var missing = tiles.stream().filter(tile -> !installed.contains(tile)).toList();
        if (!missing.isEmpty()) {
            throw new ToolProblem("The client has no map on " + String.join(", ", missing) + ".");
        }
        tiles.sort(Comparator.comparingInt((String name) -> Integer.parseInt(name.split("_")[1]))
                .thenComparingInt(name -> Integer.parseInt(name.split("_")[0])));
        return tiles;
    }

    static Area square(String label, float x, float y, double sideMeters) throws ToolProblem {
        var side = (float) (sideMeters / LocationExport.METERS_PER_UNIT);
        if (!(side >= Area.MIN_SIDE)) {
            throw new ToolProblem(
                    "side must be at least " + Math.round(Area.MIN_SIDE * LocationExport.METERS_PER_UNIT) + " metres.");
        }
        return Area.around(label, Math.round(x), Math.round(y), side);
    }

    static List<String> tilesOf(Area area, List<String> installed) throws ToolProblem {
        var tiles = area.tiles().stream().filter(installed::contains).toList();
        if (tiles.isEmpty()) {
            throw new ToolProblem("The square is outside this client's tiles. place_find names the tile of a point.");
        }
        if (tiles.size() > LocationExport.MAX_TILES) {
            throw new ToolProblem("An export takes at most " + LocationExport.MAX_TILES
                    + " tiles, and this square touches " + tiles.size() + ". Ask for a smaller side.");
        }
        return tiles;
    }

    static Npc pick(List<Npc> all, String asked) throws ToolProblem {
        if (asked.chars().allMatch(Character::isDigit)) {
            var id = Integer.parseInt(asked);
            for (var npc : all) {
                if (npc.id() == id) {
                    return npc;
                }
            }
            throw new ToolProblem("The client has no NPC with the id " + asked + ".");
        }
        var named =
                all.stream().filter(npc -> npc.label().equalsIgnoreCase(asked)).toList();
        if (named.isEmpty()) {
            var wanted = asked.toLowerCase(Locale.ROOT);
            named = all.stream()
                    .filter(npc -> npc.label().toLowerCase(Locale.ROOT).contains(wanted))
                    .toList();
        }
        if (named.isEmpty()) {
            throw new ToolProblem("The client has no NPC named " + asked + ". npc_find finds NPCs by parts of a name.");
        }
        if (named.size() > 1) {
            var names = new ArrayList<String>();
            for (var npc : named.subList(0, Math.min(NPCS_NAMED, named.size()))) {
                names.add(npc.id() + " " + npc.label() + (npc.title().isBlank() ? "" : " (" + npc.title() + ")"));
            }
            throw new ToolProblem(named.size()
                    + " NPCs fit \""
                    + asked
                    + "\": "
                    + String.join(", ", names)
                    + (named.size() > NPCS_NAMED ? " and more" : "")
                    + ". Give the id.");
        }
        return named.getFirst();
    }

    @Override
    public void register(Tools tools) {
        tools.hub(
                ToolSpec.named(
                                "export_location",
                                "Export a location",
                                "Exports whole tiles, or a square around a target, for Blender, Unity and other 3D"
                                        + " programs. Works with no world open. Files go into the export folder. The"
                                        + " effects follow the map events of view_settings. With effects off too, its"
                                        + " zone_state picks the meshes, Terrain, fog, sky and sounds,"
                                        + " and by_time the sounds by the hour. map_events in the answer says what it"
                                        + " followed.")
                        .text(
                                "target",
                                "The middle of a square: a place name, a tile (22_22), a point (x,y or x,y,z), or"
                                        + " camera for the viewer in use. A tile alone without side exports the whole"
                                        + " tile.")
                        .number(
                                "side",
                                "The side of the square in metres at real size, 4 cm a unit, whatever the scale. 400"
                                        + " when left out.")
                        .list(
                                "tiles",
                                "Whole tiles such as 22_22, in place of a target. At most " + LocationExport.MAX_TILES
                                        + ", and up to " + LocationExport.COMFORTABLE_TILES + " open well.")
                        .text(
                                "client",
                                "A client from cruma_status by name, or a client folder. The first known one when left"
                                        + " out.")
                        .integer(
                                "hour",
                                "The hour of the client's sun, sky and fog, 0 to 23. " + Settings.DEFAULT_HOUR
                                        + " when left out, or the viewer's hour with target camera.")
                        .text(
                                "scale",
                                "The length of a client unit: real (4 cm), unreal (1 cm), metre (1 m), or a number of"
                                        + " centimetres. The person's last scale when left out.")
                        .integer(
                                "terrain_size",
                                "The side of the baked terrain texture in pixels: 2048, 4096 or 8192. 4096 when left"
                                        + " out.")
                        .flag("grass", "Grass and flowers. On when left out.")
                        .flag("client_light", "The light the client bakes into the map. On when left out.")
                        .flag("effects", "A still of the particle effects. On when left out.")
                        .flag(
                                "moving_effects",
                                "A loop of the effects that moves, in place of a still. Off when left out.")
                        .flag("sounds", "The map's sounds. On when left out.")
                        .flag(
                                "music",
                                "The tracks of the map's music zones, with music.json. About 200 MB for a town tile."
                                        + " On when left out.")
                        .flag("npcs", "NPCs from the server's npcpos.txt, each with a rig. Off when left out.")
                        .text(
                                "scripts",
                                "The server's script folder for npcs. The client's folder from Settings when left out.")
                        .text(
                                "geodata",
                                "The server's geodata folder for npcs, for random spawns. The client's folder from"
                                        + " Settings when left out.")
                        .text("folder", "A full path of another folder for the export. Needs Write.")
                        .flag("replace", "Replaces an earlier export of the same name. Needs Write.")
                        .text(
                                "viewer",
                                "The viewer id from cruma_status, for target camera. Without it, the viewer used last.")
                        .destructive()
                        .build(),
                ExportTools::location);
        tools.hub(
                ToolSpec.named(
                                "export_npc",
                                "Export NPCs",
                                "Exports an NPC with its skeleton and every animation, or a list of NPCs, for Blender"
                                        + " and other 3D programs. Works with no world open. Files go into the export"
                                        + " folder.")
                        .text("npc", "One NPC by id, or by name when only one has it.")
                        .list("npcs", "Several NPCs by id or name, in place of npc.")
                        .flag(
                                "scene",
                                "Puts a list of NPCs into one file, in a row. On when left out. Off gives each its own"
                                        + " folder.")
                        .text(
                                "client",
                                "A client from cruma_status by name, or a client folder. The first known one when left"
                                        + " out.")
                        .text(
                                "scale",
                                "The length of a client unit: real (4 cm), unreal (1 cm), metre (1 m), or a number of"
                                        + " centimetres. The person's last scale when left out.")
                        .text(
                                "scripts",
                                "The server's script folder, which says what an NPC built from player parts wears. The"
                                        + " client's folder from Settings when left out.")
                        .text("folder", "A full path of another folder for the export. Needs Write.")
                        .flag("replace", "Replaces an earlier export of the same name. Needs Write.")
                        .destructive()
                        .build(),
                ExportTools::npc);
        tools.hub(
                ToolSpec.named(
                                "blender_open",
                                "Open in Blender",
                                "Opens an export in Blender for the person, as a scene with its light and camera. With"
                                    + " check it opens it with no window and answers what the scene holds, to see that"
                                    + " an export is right.")
                        .text(
                                "folder",
                                "The folder of the export, as an export tool answered it, or its name in the export"
                                        + " folder. Outside the export folder: Needs Write.")
                        .flag(
                                "check",
                                "Runs Blender with no window and reads back what the scene holds, and how it compares"
                                        + " with the export's numbers.")
                        .writes()
                        .required("folder")
                        .build(),
                ExportTools::blender);
        tools.hub(
                ToolSpec.named(
                                "assets_find",
                                "Find assets",
                                "Finds what a client has by name or id, with how to use it: meshes, textures, sounds,"
                                    + " effects, and for scenes skills, items, NPCs, races, animations of a model,"
                                    + " music, voices, slides, system messages and post effects. kind table says how"
                                    + " each system/*.dat table was read, and kind schema gives the schemas of one."
                                    + " Works with no world open.")
                        .text(
                                "query",
                                "Words of a name, such as oak tree, or an id. All must be in a row. Leave it out to"
                                        + " list a package or a kind.")
                        .choice(
                                "kind",
                                "Only this kind. Without it: meshes, textures, sounds and effects.",
                                "mesh",
                                "texture",
                                "sound",
                                "effect",
                                "skill",
                                "item",
                                "npc",
                                "race",
                                "animation",
                                "music",
                                "voice",
                                "slide",
                                "message",
                                "post_effect",
                                "table",
                                "schema")
                        .text("package", "Only this package, such as Rune_S.")
                        .integer(
                                "row",
                                "For table: a row to read field by field, from 0, or -1 for the fields outside the"
                                        + " rows. query names the table, such as npcgrp.dat.")
                        .text(
                                "schema",
                                "For table with row: the title of the schema to read with, such as Interlude, the first"
                                        + " of that title in the order Cruma tries them. The one that read the table,"
                                        + " or came closest, when left out.")
                        .choice(
                                "variant",
                                "For table and schema: the world, main, classic or classic_aden (Essence), as"
                                        + " viewer.world.variant of options. The client's world in Settings when left"
                                        + " out.",
                                "main",
                                "classic",
                                "classic_aden")
                        .text(
                                "model",
                                "For animation: an NPC id, a mesh or a race such as Elf, female. For item: a race, to"
                                        + " list what it wears.")
                        .text(
                                "scripts",
                                "The server's script folder, for the grade of items. The client's folder from Settings"
                                        + " when left out.")
                        .integer(
                                "limit",
                                "How many to list, " + FIRST_ASSETS + " when left out, at most " + MOST_ASSETS + ".")
                        .text(
                                "client",
                                "A client from cruma_status by name, or a client folder. The first known one when left"
                                        + " out.")
                        .build(),
                this::assets);
    }

    private ToolResult assets(ToolArgs args, AgentHub hub, Job job) throws ToolProblem, InterruptedException {
        var client = hub.resolveClient(args.getText("client"));
        var word = args.getText("kind");
        if ("table".equals(word) || "schema".equals(word)) {
            return tables(args, hub, client, word);
        }
        if (args.has("row") || args.has("schema") || args.has("variant")) {
            throw new ToolProblem("row, schema and variant go with kind table, and variant with kind schema too.");
        }
        var listed = ClientLists.Kind.parse(word);
        if (listed != null) {
            return lists(args, hub, job, client, listed);
        }
        var kind = Kind.parse(word);
        if (word != null && kind == null) {
            throw new ToolProblem(
                    "kind must be mesh, texture, sound, effect, skill, item, npc, race, animation, music, voice,"
                            + " slide, message, post_effect, table or schema.");
        }
        if (args.has("model")) {
            throw new ToolProblem("model goes with kind animation or item.");
        }
        var index = indexes.computeIfAbsent(client, root -> new AssetIndex(root, hub.getFolder()));
        var total = new AtomicInteger();
        try {
            index.update(
                    job::isCancelled,
                    total::set,
                    done -> job.setProgress("Reading the client's packages, " + done + " of " + total.get()));
        } catch (IOException failed) {
            throw new ToolProblem("The client's packages cannot be read: " + failed.getMessage());
        }
        var limit = Math.clamp(args.getInteger("limit", FIRST_ASSETS), 1, MOST_ASSETS);
        var found = index.find(args.getText("query"), kind, args.getText("package"), limit);
        var rows = new JSONArray();
        var use = new JSONObject();
        for (var asset : found.shown()) {
            var row = new JSONObject();
            row.put("kind", asset.kind().word);
            row.put("path", asset.path());
            row.put("package", asset.packageName());
            row.put("file", asset.file());
            row.put("type", asset.type());
            if (asset.size() != null) {
                row.put("size", new int[] {
                    Math.round(asset.size()[0]), Math.round(asset.size()[1]), Math.round(asset.size()[2])
                });
            }
            if (asset.kind() == Kind.SOUND && !asset.path().equals(asset.packageName() + "." + asset.name())) {
                row.put("scene_name", asset.packageName() + "." + asset.name());
            }
            rows.add(row);
            use.put(asset.kind().word, USE.get(asset.kind()));
        }
        var kinds = new JSONObject();
        found.kinds().forEach((each, count) -> kinds.put(each.word, count));
        var answer = new JSONObject();
        answer.put("client", client.getFileName().toString());
        answer.put("found", found.total());
        answer.put("more", found.total() - found.shown().size());
        answer.put("kinds", kinds);
        answer.put("assets", rows);
        answer.put("use", use);
        return ToolResult.data(answer);
    }

    private ToolResult tables(ToolArgs args, AgentHub hub, Path client, String kind) throws ToolProblem {
        for (var other : List.of("package", "model", "scripts")) {
            if (args.has(other)) {
                throw new ToolProblem(other + " does not go with kind " + kind + ".");
            }
        }
        var asked = args.getText("variant");
        WorldVariant variant;
        try {
            variant = asked != null
                    ? WorldVariant.parse(asked)
                    : hub.getConfigFor(client).viewer().world().variant();
        } catch (ConfigurationProblem wrong) {
            throw new ToolProblem(client + ": " + wrong.getMessage());
        }
        var key = ClientTables.Key.of(client, variant);
        if (!Files.isDirectory(key.system())) {
            throw new ToolProblem(
                    client + " has no system folder, so it has no tables. Pass the folder that holds" + " system.");
        }
        var query = args.getText("query");
        var limit = Math.clamp(args.getInteger("limit", FIRST_ASSETS), 1, MOST_ASSETS);
        if (kind.equals("schema")) {
            if (args.has("row") || args.has("schema")) {
                throw new ToolProblem("row and schema go with kind table.");
            }
            return ToolResult.data(TableAnswers.schemas(key, query, limit));
        }
        Integer row = args.has("row") ? args.getInteger("row", 0) : null;
        if (row != null && row < -1) {
            throw new ToolProblem("row is a row from 0, or -1 for the fields outside the rows.");
        }
        return ToolResult.data(TableAnswers.tables(key, query, row, args.getText("schema"), limit));
    }

    private ToolResult lists(ToolArgs args, AgentHub hub, Job job, Path client, ClientLists.Kind kind)
            throws ToolProblem {
        if (args.has("package")) {
            throw new ToolProblem("package goes with kind mesh, texture, sound or effect.");
        }
        var model = word(args.getJson().get("model"));
        if (model != null && kind != ClientLists.Kind.ANIMATION && kind != ClientLists.Kind.ITEM) {
            throw new ToolProblem("model goes with kind animation or item.");
        }
        var installation = installation(client, hub);
        var found = lists.computeIfAbsent(client, _ -> new ClientLists(installation.root(), installation.variant()));
        var scripts = kind == ClientLists.Kind.ITEM ? itemScripts(args, hub.getConfigFor(client)) : null;
        var limit = Math.clamp(args.getInteger("limit", FIRST_ASSETS), 1, MOST_ASSETS);
        return ToolResult.data(found.answer(kind, word(args.getJson().get("query")), model, scripts, limit, job));
    }

    private static ToolResult location(ToolArgs args, AgentHub hub, Job job) throws ToolProblem, InterruptedException {
        var loader = loader(hub);
        var exports = ExportSettings.in(loader);
        var replace = args.readFlag("replace", false);
        var parent = exportFolder(args.getText("folder"), replace, exports.getFolder(), hub.getAccess());
        hub.getRoom().require(parent, hub.getAccess());
        var where = where(args, hub);
        var client = where.client();
        var config = hub.getConfigFor(where.asked());
        var events = MapEventChoices.in(loader).of(where.asked());
        var npcs = args.readFlag("npcs", false);
        var scripts = npcs ? scriptFolder(args, config) : null;
        var geodata = npcs ? geodataFolder(args, config) : null;
        var settings = LocationExportRun.settings(
                terrainSize(args),
                args.readFlag("grass", true),
                npcs,
                where.area(),
                where.hour(),
                scale(args.getJson().get("scale"), exports.getScale()),
                args.readFlag("client_light", true),
                args.readFlag("effects", true),
                args.readFlag("moving_effects", false),
                args.readFlag("sounds", true),
                args.readFlag("music", true),
                events);
        var name = LocationExport.name(where.tiles(), client.variant(), where.area());
        if (!replace
                && !LocationExport.findExistingFiles(parent, name, settings).isEmpty()) {
            throw earlierExport(name, parent);
        }
        var started = System.nanoTime();
        LocationExport.Result result;
        try {
            result = LocationExportRun.tiles(
                    client.root(),
                    client.variant(),
                    where.tiles(),
                    parent,
                    settings,
                    replace,
                    events,
                    scripts,
                    geodata,
                    progress(job),
                    job::isCancelled);
        } catch (CancellationException stopped) {
            throw new ToolProblem("Stopped. Nothing was written.");
        } catch (IOException failed) {
            throw new ToolProblem("The export failed: " + failed.getMessage());
        }
        var file = result.folder().resolve(name + ".glb");
        var answer = new JSONObject();
        answer.put("exported", name);
        answer.put("client", client.root().getFileName().toString());
        answer.put("folder", result.folder().toString());
        answer.put("file", file.toString());
        answer.put("tiles", where.tiles());
        answer.put("objects", result.objects());
        answer.put("meshes", result.meshes());
        answer.put("triangles", result.triangles());
        answer.put("textures", result.textures());
        if (result.sounds() > 0) {
            answer.put("sounds", result.sounds());
        }
        if (result.musicZones() > 0) {
            answer.put("music_zones", result.musicZones());
            answer.put("music_tracks", result.musicTracks());
        }
        if (npcs) {
            answer.put("npc_figures", result.figures());
            answer.put("rigs", result.rigs());
            answer.put("left_out", result.withoutPlace());
        }
        if (result.mapFigures() > 0) {
            answer.put("map_figures", result.mapFigures());
            answer.put("map_rigs", result.mapRigs());
        }
        answer.put("size_mb", megabytes(file));
        answer.put("seconds", seconds(started));
        answer.put("hour", settings.hour());
        answer.put("scale", settings.scale().unitText());
        var followed = LocationExportRun.describeMapEvents(events, settings.effects(), settings.sounds());
        if (!followed.isEmpty()) {
            answer.put("map_events", followed);
        }
        answer.put("next", "blender_open with this folder opens it, and with check true reads back what it holds.");
        hub.getRoom().wrote(result.folder());
        return ToolResult.data(answer).withFolder(result.folder());
    }

    private static ToolResult npc(ToolArgs args, AgentHub hub, Job job) throws ToolProblem, InterruptedException {
        var one = word(args.getJson().get("npc"));
        var many = args.list("npcs");
        if (one != null && !many.isEmpty()) {
            throw new ToolProblem("Give npc or npcs, not both.");
        }
        if (one == null && many.isEmpty()) {
            throw new ToolProblem("Give npc with an id or a name, or npcs with a list. npc_find finds them.");
        }
        var loader = loader(hub);
        var exports = ExportSettings.in(loader);
        var replace = args.readFlag("replace", false);
        var parent = exportFolder(args.getText("folder"), replace, exports.getFolder(), hub.getAccess());
        hub.getRoom().require(parent, hub.getAccess());
        var asked = hub.resolveClient(args.getText("client"));
        var client = installation(asked, hub);
        var size = scale(args.getJson().get("scale"), exports.getScale());
        List<Npc> all;
        try {
            job.setProgress("Reading the client's NPC tables");
            all = NpcExport.list(client.root(), client.variant());
        } catch (IOException | RuntimeException unreadable) {
            throw new ToolProblem("The client's NPC tables cannot be read: " + unreadable.getMessage());
        }
        var chosen = new ArrayList<Npc>();
        for (var wanted : one != null ? List.of(one) : many) {
            chosen.add(pick(all, wanted));
        }
        var config = hub.getConfigFor(asked);
        var dressed = chosen.stream().anyMatch(Npc::playerLike);
        try (var models = dressed ? new SpawnModels(client.root(), client.variant()) : null) {
            var script = dressed && readScript(models, args, config);
            Function<Npc, @Nullable Outfit> dress = npc -> models == null ? null : models.getDress(npc.id());
            var result = one != null
                    ? single(chosen.getFirst(), client, size, parent, replace, dress, job)
                    : batch(
                            chosen,
                            client,
                            size,
                            parent,
                            replace,
                            args.readFlag("scene", true),
                            dress,
                            hub.getRoom(),
                            job);
            if (one != null) {
                hub.getRoom().wrote(result.getFolder());
            }
            return dressed && !script
                    ? result.withText(
                            "Built from player parts, it wears only what the client's own tables give it, as Cruma has"
                                    + " no script folder for this client. Pass scripts for what the server gives it.")
                    : result;
        }
    }

    private static ToolResult single(
            Npc npc,
            ClientInstallation client,
            ExportScale size,
            Path parent,
            boolean replace,
            Function<Npc, @Nullable Outfit> dress,
            Job job)
            throws ToolProblem {
        if (!replace && !NpcExport.findExistingFiles(parent, npc).isEmpty()) {
            throw earlierExport(NpcExport.name(npc), parent);
        }
        var started = System.nanoTime();
        NpcExport.Result result;
        try {
            result = NpcExport.export(
                    client.root(),
                    client.variant(),
                    npc,
                    dress.apply(npc),
                    size,
                    parent,
                    replace,
                    progress(job),
                    job::isCancelled);
        } catch (CancellationException stopped) {
            throw new ToolProblem("Stopped. Nothing was written.");
        } catch (IOException | RuntimeException failed) {
            throw new ToolProblem("The export of " + npc.label() + " failed: " + failed.getMessage());
        }
        var name = NpcExport.name(npc);
        var file = result.folder().resolve(name + ".glb");
        var animations = result.animations();
        var answer = new JSONObject();
        answer.put("exported", name);
        answer.put("npc", npc.label());
        answer.put("id", npc.id());
        answer.put("mesh", npc.mesh());
        answer.put("folder", result.folder().toString());
        answer.put("file", file.toString());
        answer.put("bones", result.bones());
        answer.put("triangles", result.triangles());
        answer.put("textures", result.textures());
        answer.put("animations", animations.size());
        answer.put("animation_names", animations.subList(0, Math.min(ANIMATIONS_SHOWN, animations.size())));
        answer.put("size_mb", megabytes(file));
        answer.put("seconds", seconds(started));
        answer.put("scale", size.unitText());
        answer.put("next", "blender_open with this folder opens it, and with check true reads back its rig.");
        return ToolResult.data(answer).withFolder(result.folder());
    }

    private static ToolResult batch(
            List<Npc> npcs,
            ClientInstallation client,
            ExportScale size,
            Path parent,
            boolean replace,
            boolean scene,
            Function<Npc, @Nullable Outfit> dress,
            ExportRoom room,
            Job job)
            throws ToolProblem {
        var entries = npcs.stream().map(Entry::of).toList();
        var started = System.nanoTime();
        Summary summary;
        try {
            summary = scene
                    ? NpcBatchExport.runScene(
                            entries,
                            parent,
                            replace,
                            NpcBatchExport.createSceneWriter(client.root(), client.variant(), size, dress),
                            progress(job),
                            outcome -> {},
                            job::isCancelled)
                    : NpcBatchExport.run(
                            entries,
                            parent,
                            replace,
                            NpcBatchExport.createWriter(client.root(), client.variant(), size, dress),
                            progress(job),
                            outcome -> {},
                            job::isCancelled);
        } catch (UncheckedIOException failed) {
            throw new ToolProblem(failed.getMessage());
        }
        var written = summary.written();
        var models = new JSONArray();
        for (var outcome : written.subList(0, Math.min(MODELS_SHOWN, written.size()))) {
            var item = new JSONObject();
            item.put("name", outcome.name());
            item.put("bones", outcome.result().bones());
            item.put("animations", outcome.result().animations().size());
            item.put("triangles", outcome.result().triangles());
            models.add(item);
        }
        var failed = new JSONArray();
        var failures = summary.failed();
        for (var outcome : failures.subList(0, Math.min(MODELS_SHOWN, failures.size()))) {
            var item = new JSONObject();
            item.put("npc", outcome.entry().npc().label());
            item.put("why", outcome.failure());
            failed.add(item);
        }
        written.stream().map(done -> done.result().folder()).distinct().forEach(room::wrote);
        var folder = scene && !written.isEmpty() ? written.getFirst().result().folder() : parent;
        var answer = new JSONObject();
        answer.put("exported", written.size());
        answer.put("of", summary.total());
        answer.put("folder", folder.toString());
        if (scene && !written.isEmpty()) {
            answer.put("file", written.getFirst().result().files().getFirst().toString());
        }
        answer.put("models", models);
        answer.put("more_models", written.size() - models.size());
        answer.put("failed", failed);
        answer.put("more_failed", failures.size() - failed.size());
        answer.put("size_mb", Math.round(summary.bytes() / 100_000.0) / 10.0);
        answer.put("seconds", seconds(started));
        answer.put("scale", size.unitText());
        if (summary.cancelled()) {
            answer.put("stopped", "Stopped before the end. The models written stay.");
        }
        return ToolResult.data(answer).withFolder(folder);
    }

    private static ToolResult blender(ToolArgs args, AgentHub hub, Job job) throws ToolProblem, InterruptedException {
        var exports = ExportSettings.in(loader(hub)).getFolder();
        var folder = exportOf(args.getRequired("folder"), exports);
        requireOwnExport(folder, exports, hub.getAccess());
        if (!BlenderApp.canOpen(folder)) {
            throw new ToolProblem(folder + " is not an export: it has no " + LocationExport.BLENDER_SCRIPT
                    + ". Call export_location or export_npc first.");
        }
        return open(BlenderApp.find(), folder, args.readFlag("check", false), job);
    }

    private static @Nullable Path itemScripts(ToolArgs args, Config config) throws ToolProblem {
        var asked = args.getText("scripts");
        if (asked != null) {
            return fullPath(asked, "scripts");
        }
        var spawns = config.viewer().world().spawns();
        var folder = spawns == null ? null : spawns.scriptPath();
        return folder == null || folder.toString().isEmpty() || !Files.isDirectory(folder) ? null : folder;
    }

    private static Path fullPath(String text, String argument) throws ToolProblem {
        try {
            var path = Path.of(text);
            if (!path.isAbsolute()) {
                throw new ToolProblem(argument + " must be a full path, not " + text + ".");
            }
            return path.normalize();
        } catch (InvalidPathException wrong) {
            throw new ToolProblem(argument + " is not a path: " + text);
        }
    }

    private static Where where(ToolArgs args, AgentHub hub) throws ToolProblem, InterruptedException {
        var text = args.getText("target");
        var named = args.list("tiles");
        if (text != null && !named.isEmpty()) {
            throw new ToolProblem("Give tiles or a target, not both.");
        }
        if (text == null && named.isEmpty()) {
            throw new ToolProblem("Give tiles such as 22_22, or a target with a side in metres.");
        }
        if (text == null && args.has("side")) {
            throw new ToolProblem("side goes with a target. Tiles are exported whole.");
        }
        var hour = hour(args);
        var target = text == null ? null : Target.parse(text);
        if (target != null && target.kind() == Target.Kind.CAMERA) {
            return aroundCamera(args, hub, hour);
        }
        var asked = hub.resolveClient(args.getText("client"));
        var client = installation(asked, hub);
        var hours = hour == null ? Settings.DEFAULT_HOUR : hour;
        if (target == null) {
            return new Where(client, asked, wholeTiles(named, client.tiles()), null, hours);
        }
        if (target.kind() == Target.Kind.TILE && !args.has("side")) {
            return new Where(client, asked, wholeTiles(List.of(text.strip()), client.tiles()), null, hours);
        }
        var side = args.getNumber("side", FIRST_SIDE_METERS);
        var area = switch (target.kind()) {
            case POINT, TILE -> square("", target.x(), target.y(), side);
            case NAME -> {
                var locations = hub.getConfigFor(asked).viewer().camera().locations();
                var place = Target.place(locations, target.text());
                if (place.isEmpty()) {
                    throw new ToolProblem(
                            "There is no place named \"" + text + "\". Call place_find, or give a point or a tile.");
                }
                yield square(place.get().name(), place.get().x(), place.get().y(), side);
            }
            default ->
                throw new ToolProblem(
                        text + " needs the viewer's tools. Read its point with them, and give the point.");
        };
        return new Where(client, asked, tilesOf(area, client.tiles()), area, hours);
    }

    private static Where aroundCamera(ToolArgs args, AgentHub hub, @Nullable Integer hour)
            throws ToolProblem, InterruptedException {
        var link = hub.getViewers().pick(args.getText("viewer"));
        var status = link.getStatus();
        if (status == null) {
            throw new ToolProblem("The viewer does not answer. Call cruma_status.");
        }
        var named = args.getText("client");
        var asked = named == null ? Path.of(link.getEndpoint().client()) : hub.resolveClient(named);
        var client = installation(asked, hub);
        var camera = status.getJSONObject("camera");
        var area = square(
                "", camera.getFloatValue("x"), camera.getFloatValue("y"), args.getNumber("side", FIRST_SIDE_METERS));
        var shown = (int) Math.floor(status.getDoubleValue("hour"));
        return new Where(
                client, asked, tilesOf(area, client.tiles()), area, hour == null ? Math.floorMod(shown, 24) : hour);
    }

    private static @Nullable Integer hour(ToolArgs args) throws ToolProblem {
        if (!args.has("hour")) {
            return null;
        }
        var hour = args.getInteger("hour", Settings.DEFAULT_HOUR);
        if (hour < 0 || hour > 23) {
            throw new ToolProblem("hour must be 0 to 23.");
        }
        return hour;
    }

    private static int terrainSize(ToolArgs args) throws ToolProblem {
        var size = args.getInteger("terrain_size", LocationExport.TERRAIN_SIZES[LocationExportRun.FIRST_TERRAIN_SIZE]);
        for (var allowed : LocationExport.TERRAIN_SIZES) {
            if (allowed == size) {
                return size;
            }
        }
        throw new ToolProblem("terrain_size must be 2048, 4096 or 8192.");
    }

    private static ClientInstallation installation(Path client, AgentHub hub) throws ToolProblem {
        try {
            return ClientInstallation.inspect(
                    client, hub.getConfigFor(client).viewer().world().variant());
        } catch (ConfigurationProblem wrong) {
            throw new ToolProblem(client + ": " + wrong.getMessage());
        }
    }

    private static Path scriptFolder(ToolArgs args, Config config) throws ToolProblem {
        var asked = args.getText("scripts");
        var spawns = config.viewer().world().spawns();
        var folder = asked != null ? fullPath(asked, "scripts") : spawns == null ? null : spawns.scriptPath();
        if (folder == null || folder.toString().isEmpty() || !Files.isDirectory(folder)) {
            throw new ToolProblem(
                    "npcs needs the server's script folder. Pass scripts, or ask the person to set it in Settings for"
                            + " this client.");
        }
        return folder;
    }

    private static @Nullable Path geodataFolder(ToolArgs args, Config config) throws ToolProblem {
        var asked = args.getText("geodata");
        var geodata = config.viewer().world().geodata();
        var folder = asked != null ? fullPath(asked, "geodata") : geodata == null ? null : geodata.path();
        return folder == null || folder.toString().isEmpty() || !Files.isDirectory(folder) ? null : folder;
    }

    private static boolean readScript(SpawnModels models, ToolArgs args, Config config) throws ToolProblem {
        var asked = args.getText("scripts");
        var spawns = config.viewer().world().spawns();
        var folder = asked != null ? fullPath(asked, "scripts") : spawns == null ? null : spawns.scriptPath();
        if (folder == null || folder.toString().isEmpty()) {
            return false;
        }
        try {
            return models.readScript(folder) != null;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private static ToolProblem earlierExport(String name, Path parent) {
        return new ToolProblem("An earlier export of " + name + " is in " + parent
                + ". blender_open opens it. replace true writes over it, and that needs Write access.");
    }

    private static Progress progress(Job job) {
        return (stage, done) -> job.setProgress(Math.round(done * 100) + "% " + stage);
    }

    private static @Nullable String word(@Nullable Object value) {
        if (value instanceof Number number) {
            var whole = number.doubleValue() == Math.rint(number.doubleValue());
            return whole ? Long.toString(number.longValue()) : number.toString();
        }
        return value == null || value.toString().isBlank()
                ? null
                : value.toString().strip();
    }

    private static ConfigLoader loader(AgentHub hub) {
        return new ConfigLoader(ConfigLoader.parseOptions(
                new String[] {"-c", hub.getConfigDirectory().toString()}));
    }

    private static double megabytes(Path file) {
        try {
            return Math.round(Files.size(file) / 100_000.0) / 10.0;
        } catch (IOException gone) {
            return 0;
        }
    }

    private static double seconds(long started) {
        return Math.round((System.nanoTime() - started) / 100_000_000.0) / 10.0;
    }
}

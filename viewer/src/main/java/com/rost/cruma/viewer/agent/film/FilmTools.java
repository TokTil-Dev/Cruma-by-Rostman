package com.rost.cruma.viewer.agent.film;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.world.CameraPath;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneSources;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.film.FilmKeys.Edited;
import com.rost.cruma.viewer.agent.film.FilmScenes.Found;
import com.rost.cruma.viewer.agent.film.FilmScenes.Kind;
import com.rost.cruma.viewer.application.RecordLine;
import com.rost.cruma.viewer.application.RecordingProcess;
import com.rost.cruma.viewer.application.SceneRequest;
import com.rost.cruma.viewer.application.SceneVideoFile;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.config.RecordOptions;
import com.rost.cruma.viewer.editing.SceneFileStore;
import com.rost.cruma.viewer.editing.SceneFileStore.Listed;
import com.rost.cruma.viewer.launch.FfmpegApp;
import com.rost.cruma.viewer.launch.OwnProgram;
import com.rost.cruma.viewer.ui.SceneBlenderJob;
import com.rost.cruma.viewer.ui.SceneBlenderJob.Plan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.jetbrains.annotations.Nullable;

public final class FilmTools implements ToolGroup {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MOST_LIMIT = 200;
    private static final int SHEET_WIDTH = 1280;
    private static final int LEAST_SHEET_WIDTH = 640;
    private static final int MOST_SHEET_WIDTH = 1920;
    private static final int MOST_RATE = 240;
    private static final double LAST_HOUR = 24;
    private static final String CLIENT =
            "A client from cruma_status by name, or a client folder. The first known one when left out.";
    private static final String SCENE_CLIENT = "A client from cruma_status by name, or a client folder. The client"
            + " your scene was made on when left out, and the first known one for a cut scene.";
    private static final String SCENE = "The name of a scene from scene_list: your own or a cut scene.";
    private static final String HOUR =
            "The hour of the game day to show, from 0 to 24. Left out, the scene's own hour, else ";
    private static final String FOLDER =
            "Another folder to write into. Needs Write. The export folder of the settings when left out.";

    static Path sceneClient(AgentHub hub, @Nullable String asked, Path searched, Found found) throws ToolProblem {
        if (found.file() == null) {
            return searched;
        }
        return clientOf(hub, asked, read(found.file(), found.scene().name()));
    }

    @Override
    public void register(Tools tools) {
        tools.hub(
                ToolSpec.named(
                                "scene_list",
                                "List scenes",
                                "Cut scenes, lobby scenes and your own scenes of a client, with their tiles and"
                                        + " lengths. Use it to find a scene for the other film tools.")
                        .text("query", "Words of the name. All scenes when left out.")
                        .text("client", CLIENT)
                        .integer("limit", "How many to show, " + DEFAULT_LIMIT + " when left out.")
                        .build(),
                FilmTools::list);
        tools.viewer(
                ToolSpec.named(
                                "scene_play",
                                "Play a scene",
                                "Plays a scene to its end in a viewer that holds its tiles, or a lobby scene in a"
                                        + " viewer of its map. The call lasts as long as the scene.")
                        .text("scene", SCENE)
                        .number("hour", HOUR + "the hour of the viewer.")
                        .required("scene"),
                FilmTools::play);
        tools.hub(
                ToolSpec.named(
                                "film_read",
                                "Read a scene",
                                "All a scene holds: cast, orders with their keys, camera, sound, screen, walks and"
                                        + " warnings with their fix. Its keys and Indexes go into film_actors.")
                        .text("scene", SCENE)
                        .text("client", SCENE_CLIENT)
                        .integer("actor", "Only this Index: its orders, walks and warnings.")
                        .number("from", "Only what happens from this second on.")
                        .number("to", "Only what happens up to this second.")
                        .choice("part", "Only one part. All when left out.", FilmRead.PARTS)
                        .flag("sequences", "With actor: the sequences of its model, with their seconds.")
                        .integer("limit", "Rows of each list, " + FilmRead.DEFAULT_ROWS + " when left out.")
                        .required("scene")
                        .build(),
                FilmTools::read);
        tools.hub(
                ToolSpec.named(
                                "film_actors",
                                "Direct actors",
                                "Puts NPCs, characters and airships into your scene and gives them orders, also music,"
                                    + " voice, sound and screen. An actor with watch keeps turned to another actor."
                                    + " Writes the file with a dated .bak. A scene open in your tray does not see it.")
                        .text("scene", "Your scene, from scene_list. Copy a cut scene first with film_edit copy_of.")
                        .text("client", CLIENT + " It reads the names. The scene's own client when left out.")
                        .schema("ops", FilmOps.schema())
                        .flag("preview", "Says what would change, and writes nothing.")
                        .required("scene", "ops")
                        .access(Access.EDIT)
                        .destructive()
                        .build(),
                FilmTools::actors);
        tools.hub(
                ToolSpec.named(
                                "film_edit",
                                "Write a film",
                                "Makes a camera flight in one call, also by a quick start or as a copy of a cut scene,"
                                        + " or changes a scene of the video director. Writes its file. Review it with"
                                        + " film_frames, then film_render.")
                        .text(
                                "name",
                                "The name of a new scene. With scene, the name to save that scene under, and the old"
                                        + " file stays.")
                        .text("scene", "The name of your scene to change. Leave it out to make a new one.")
                        .text(
                                "copy_of",
                                "A cut scene of the client or the server, from scene_list, to copy into a new scene of"
                                        + " yours.")
                        .schema("quick", quick())
                        .schema(
                                "keys",
                                keys(
                                        "The camera keys. On a scene you change, they take the place of all its keys.",
                                        false))
                        .schema("add_keys", keys("Keys to add to a scene.", false))
                        .schema(
                                "move_keys",
                                keys(
                                        "Keys to change, each by its index. A key keeps what you do not name, and"
                                                + " above moves it up from where it stands.",
                                        true))
                        .schema("remove_keys", numbers("The indexes of keys to remove.", "integer"))
                        .number(
                                "hour",
                                "The hour of the game day the scene plays at, from 0 to 24. It is saved with the"
                                        + " scene. The viewer's hour when left out.")
                        .number(
                                "length",
                                "How long the scene plays, in seconds. It never ends before its last key. A new scene"
                                        + " ends at its last key when left out.")
                        .flag(
                                "even_speed",
                                "The camera moves at one speed from the first key to the last, so the keys between"
                                        + " take their times from the path.")
                        .text("client", CLIENT + " Only for a new scene.")
                        .access(Access.EDIT)
                        .destructive()
                        .build(),
                FilmTools::edit);
        tools.viewer(
                ToolSpec.named(
                                "film_frames",
                                "Film frames",
                                "One sheet of frames of a scene at the times you name, to review a film before"
                                        + " film_render. Needs a viewer that holds its tiles.")
                        .text("scene", SCENE)
                        .schema(
                                "times",
                                numbers(
                                        "The moments to show, in seconds. At most " + FilmFrames.MOST_FRAMES + ".",
                                        "number"))
                        .integer(
                                "count",
                                "How many frames to spread over the scene when times is left out. "
                                        + FilmFrames.DEFAULT_FRAMES + " when left out.")
                        .number("hour", HOUR + "the hour of the viewer. Give the hour you will render at.")
                        .integer("width", "Width of the sheet in pixels. " + SHEET_WIDTH + " when left out.")
                        .required("scene")
                        .needsGraphics(),
                FilmTools::frames);
        tools.hub(
                ToolSpec.named(
                                "film_render",
                                "Render a film",
                                "Renders a scene to an MP4 file with its sound, with no window and no viewer open."
                                        + " Needs ffmpeg. A long job: call job_wait, and job_cancel stops it.")
                        .text("scene", SCENE)
                        .text("client", SCENE_CLIENT)
                        .integer("width", "Width of the video, an even number. The size of the settings when left out.")
                        .integer("height", "Height of the video, an even number.")
                        .integer("rate", "Frames a second. The rate of the settings when left out.")
                        .number("from", "Where the video starts in the scene, in seconds.")
                        .number("to", "Where the video ends in the scene at the latest, in seconds.")
                        .number("hour", HOUR + "the hour of the settings.")
                        .text("folder", FOLDER)
                        .required("scene")
                        .writes()
                        .build(),
                FilmTools::render);
        tools.hub(
                ToolSpec.named(
                                "film_export",
                                "Export a film to Blender",
                                "Writes a scene into a folder for Blender: its tiles, actors, camera, sounds and marks"
                                        + " of effects. A long job: call job_wait.")
                        .text("scene", SCENE)
                        .text("client", SCENE_CLIENT)
                        .number("hour", HOUR + "the hour of the settings.")
                        .text("folder", FOLDER)
                        .required("scene")
                        .writes()
                        .build(),
                FilmTools::export);
    }

    private static JSONObject keys(String text, boolean moving) {
        var properties = new JSONObject();
        if (moving) {
            properties.put("index", property("integer", "Which key, from 0, as the last answer lists them."));
        }
        properties.put(
                "time",
                property(
                        "number",
                        "The moment in seconds from the start of the scene. Two keys at one moment are a cut."));
        properties.put("target", property("string", "Where the camera stands: a place, or a point x,y,z."));
        properties.put("above", property("number", "Units above the target."));
        properties.put(
                "look_at", property("string", "A place or a point x,y,z to look at, in place of yaw and pitch."));
        properties.put("yaw", property("number", "Degrees. 0 looks east and 90 south."));
        properties.put("pitch", property("number", "Degrees. 0 is level and negative looks down."));
        properties.put("roll", property("number", "Degrees. A positive roll tips the top of the view to the right."));
        properties.put(
                "fov",
                property("number", "The field of view in degrees. " + (int) FilmKeys.DEFAULT_FOV + " when left out."));
        properties.put(
                "ease",
                property("string", "smooth bends through the key, linear makes a corner. smooth when left out."));
        properties.put(
                "actor",
                property(
                        "integer",
                        "The Index of an actor of the scene to follow. Its yaw and pitch then count only while the"
                                + " actor is away."));
        var item = new JSONObject();
        item.put("type", "object");
        item.put("properties", properties);
        var schema = property("array", text);
        schema.put("items", item);
        return schema;
    }

    private static JSONObject quick() {
        var properties = new JSONObject();
        var kind = property(
                "string",
                "orbit circles a centre, fly_to flies from one view to another, reveal pulls back and rises.");
        kind.put("enum", JSONArray.of("orbit", "fly_to", "reveal"));
        properties.put("kind", kind);
        properties.put("seconds", property("number", "How long it takes."));
        properties.put(
                "from",
                property(
                        "object",
                        "Where it starts, as a key: target, above, look_at or yaw and pitch, fov. The last key of the"
                                + " path when left out."));
        properties.put("to", property("object", "fly_to: where it ends, as a key."));
        properties.put("around", property("string", "orbit: a place or point x,y,z to circle."));
        properties.put(
                "actor", property("integer", "orbit: the Index of an actor to circle and follow, in place of around."));
        properties.put("turns", property("number", "orbit: from 0.25 to 4. 1 when left out."));
        properties.put(
                "direction",
                property("string", "orbit: left, clockwise seen from above, or right. left when left out."));
        properties.put("distance", property("number", "reveal: units back. 1500 when left out."));
        properties.put("rise", property("number", "reveal: units up. 300 when left out."));
        properties.put("time", property("number", "Where it starts on an empty path, in seconds. 0 when left out."));
        var schema = property("object", "A quick start of the director, as a new shot at the end of the path.");
        schema.put("properties", properties);
        schema.put("required", JSONArray.of("kind", "seconds"));
        return schema;
    }

    private static JSONObject numbers(String text, String type) {
        var item = new JSONObject();
        item.put("type", type);
        var schema = property("array", text);
        schema.put("items", item);
        return schema;
    }

    private static JSONObject property(String type, String text) {
        var property = new JSONObject();
        property.put("type", type);
        property.put("description", text);
        return property;
    }

    private static ToolResult list(ToolArgs args, AgentHub hub, Job job) throws ToolProblem {
        var client = hub.resolveClient(args.getText("client"));
        var config = hub.getConfigFor(client);
        var scenes = scenes(config, client);
        var limit = Math.clamp(args.getInteger("limit", DEFAULT_LIMIT), 1, MOST_LIMIT);
        var words = words(args.getText("query"));
        job.setProgress("Reading the scenes");
        var rows = new JSONArray();
        var counts = new int[Kind.values().length];
        var left = 0;
        for (var listed : own(scenes)) {
            counts[Kind.OWN.ordinal()]++;
            var name = listed.document() == null
                    ? fileName(listed.file())
                    : listed.document().name();
            if (!matches(name, words)) {
                continue;
            }
            if (rows.size() < limit) {
                rows.add(
                        listed.document() == null
                                ? broken(name, listed)
                                : row(new Found(listed.document().scene(), Kind.OWN, listed.file())));
            } else {
                left++;
            }
        }
        for (var found : scenes.cuts()) {
            counts[found.kind().ordinal()]++;
            if (!matches(found.scene().name(), words)) {
                continue;
            }
            if (rows.size() < limit) {
                rows.add(row(found));
            } else {
                left++;
            }
        }
        var lobbies = 0;
        for (var found : LobbyScenes.find(client)) {
            lobbies++;
            if (!matches(found.scene().tag(), words)) {
                continue;
            }
            if (rows.size() < limit) {
                rows.add(LobbyScenes.row(found));
            } else {
                left++;
            }
        }
        var answer = new JSONObject();
        answer.put("client", client.getFileName().toString());
        answer.put("own", counts[Kind.OWN.ordinal()]);
        answer.put("lobby_scenes", lobbies);
        if (lobbies > 0) {
            answer.put("lobby", "A lobby scene plays in a viewer of its map: world_open with map, then scene_play.");
        }
        answer.put("cut_scenes", counts[Kind.CLIENT.ordinal()]);
        answer.put("server_scenes", counts[Kind.SERVER.ordinal()]);
        answer.put("scenes", rows);
        if (left > 0) {
            answer.put("more", left);
        }
        if (counts[Kind.CLIENT.ordinal()] == 0 && counts[Kind.SERVER.ordinal()] == 0) {
            answer.put(
                    "note",
                    "This client has no cut scenes. Clients before Gracia have none. Your own flights work on it.");
        }
        return ToolResult.data(answer);
    }

    private static List<Listed> own(FilmScenes scenes) throws ToolProblem {
        try {
            return new SceneFileStore(scenes.folder(), scenes.folder()).list();
        } catch (IOException failed) {
            throw new ToolProblem("The folder of scenes cannot be read: " + failed.getMessage());
        }
    }

    private static String[] words(@Nullable String query) {
        return query == null ? new String[0] : query.toLowerCase(Locale.ROOT).split("\\s+");
    }

    private static boolean matches(String name, String[] words) {
        var lower = name.toLowerCase(Locale.ROOT);
        for (var word : words) {
            if (!lower.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static String fileName(Path file) {
        var name = file.getFileName().toString();
        return name.endsWith(SceneFileStore.EXTENSION)
                ? name.substring(0, name.length() - SceneFileStore.EXTENSION.length())
                : name;
    }

    private static JSONObject broken(String name, Listed listed) {
        var row = new JSONObject();
        row.put("name", name);
        row.put("kind", Kind.OWN.getWord());
        row.put("problem", listed.problem());
        return row;
    }

    private static JSONObject row(Found found) {
        var scene = found.scene();
        var row = new JSONObject();
        row.put("name", scene.name());
        row.put("kind", found.kind().getWord());
        row.put("seconds", round(scene.seconds()));
        if (!scene.map().isEmpty()) {
            row.put("map", scene.map());
        }
        row.put("tiles", scene.closeTiles());
        if (!scene.paths().isEmpty()) {
            row.put("keys", scene.paths().getFirst().getKeys().size());
        }
        if (found.file() != null) {
            row.put("file", found.file().toString());
        }
        return row;
    }

    private static Step play(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var hour = hour(args);
        var lobby =
                viewer.scene().getStandaloneMap() == null ? null : LobbyScenes.play(args.getRequired("scene"), viewer);
        if (lobby != null) {
            return lobby;
        }
        return new FilmPlay(
                viewer, scenes(viewer).fitLater(args.getRequired("scene"), List.copyOf(viewer.maps())), hour);
    }

    private static Step frames(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var name = args.getRequired("scene");
        var hour = hour(args);
        var times = times(args);
        var count = Math.clamp(args.getInteger("count", FilmFrames.DEFAULT_FRAMES), 1, FilmFrames.MOST_FRAMES);
        var width = Math.clamp(args.getInteger("width", SHEET_WIDTH), LEAST_SHEET_WIDTH, MOST_SHEET_WIDTH);
        var directory = viewer.configDirectory();
        return new FilmFrames(
                viewer,
                scenes(viewer).fitLater(name, List.copyOf(viewer.maps())),
                hour,
                times,
                count,
                width,
                CompletableFuture.supplyAsync(() -> shape(directory)));
    }

    private static List<Double> times(ToolArgs args) throws ToolProblem {
        var items = args.getJson().get("times");
        var times = new ArrayList<Double>();
        if (items == null) {
            return times;
        }
        if (!(items instanceof JSONArray list)) {
            throw new ToolProblem("times must be a list of seconds, such as [0, 1.5, 3].");
        }
        for (var item : list) {
            if (!(item instanceof Number number)) {
                throw new ToolProblem("times must be a list of seconds, such as [0, 1.5, 3].");
            }
            times.add(number.doubleValue());
        }
        if (times.size() > FilmFrames.MOST_FRAMES) {
            throw new ToolProblem("One sheet holds at most " + FilmFrames.MOST_FRAMES + " frames, and you named "
                    + times.size() + ". Take a second sheet.");
        }
        return times;
    }

    private static double shape(@Nullable Path directory) {
        var settings = directory == null ? ExportSettings.session() : ExportSettings.in(loader(directory));
        return (double) settings.getVideoWidth() / settings.getVideoHeight();
    }

    private static FilmScenes scenes(ViewerContext viewer) throws ToolProblem {
        var folder = viewer.ui().scenesFolder;
        if (folder == null) {
            throw new ToolProblem("This viewer keeps no scenes.");
        }
        var world = viewer.world();
        return new FilmScenes(
                folder,
                world.clientPath(),
                world.variant(),
                world.spawns() == null ? null : world.spawns().scriptPath());
    }

    private static FilmScenes scenes(Config config, Path client) {
        var world = config.viewer().world();
        return new FilmScenes(
                config.viewer().director().scenesFolder(),
                client,
                world.variant(),
                world.spawns() == null ? null : world.spawns().scriptPath());
    }

    private static void backUp(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            var stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            Files.copy(file, file.resolveSibling(file.getFileName() + "." + stamp + ".bak"));
        }
    }

    private static ToolResult edit(ToolArgs args, AgentHub hub, Job job) throws ToolProblem {
        var hour = hour(args);
        var config = hub.getConfig();
        var store = new SceneFileStore(
                config.viewer().director().scenesFolder(),
                config.viewer().director().scenesFolder());
        var keys = new FilmKeys(config.viewer().camera().locations());
        var name = args.getText("name");
        var changed = args.getText("scene");
        var copied = args.getText("copy_of");
        Edited edited;
        Path file;
        if (copied != null) {
            if (changed != null) {
                throw new ToolProblem("Give copy_of for a copy of a cut scene, or scene for your own, not both.");
            }
            var client = hub.resolveClient(args.getText("client"));
            var copy = copy(hub, client, copied);
            var named = name != null ? SceneFileStore.safeName(name) : store.freeName(copy.name());
            file = free(store, named);
            edited = keys.change(copy.withName(named), args);
        } else if (changed == null) {
            if (name == null) {
                throw new ToolProblem(
                        "Give name for a new scene, scene for one to change, or copy_of for a copy of a cut scene.");
            }
            file = free(store, name);
            var client = hub.resolveClient(args.getText("client"));
            var variant = hub.getConfigFor(client).viewer().world().variant().getKey();
            var seed = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
            edited = keys.create(
                    SceneFileStore.safeName(name), client.getFileName().toString(), variant, seed, args);
        } else {
            file = store.getFile(changed);
            edited = keys.change(read(file, changed), args);
            if (name != null) {
                file = free(store, name);
                edited = new Edited(edited.document().withName(SceneFileStore.safeName(name)), edited.notes());
            }
        }
        var quick = args.getJson().getJSONObject("quick");
        if (quick != null) {
            var client = clientOf(hub, args.getText("client"), edited.document());
            edited = keys.applyQuick(edited, new ToolArgs(quick), lookup(hub, client));
        }
        if (hour != null) {
            edited = new Edited(edited.document().withHour(hour), edited.notes());
        }
        try {
            backUp(file);
            SceneFileStore.write(file, edited.document());
        } catch (IOException failed) {
            throw new ToolProblem("The scene was not written: " + failed.getMessage());
        }
        return ToolResult.data(describe(edited, file)).withFolder(file.getParent());
    }

    private static SceneDocument copy(AgentHub hub, Path client, String name) throws ToolProblem {
        var world = hub.getConfigFor(client).viewer().world();
        var scripts = world.spawns() == null ? null : world.spawns().scriptPath();
        for (var found : scenes(hub.getConfigFor(client), client).cuts()) {
            if (found.scene().name().equalsIgnoreCase(name)) {
                var sha256 = found.kind() == Kind.SERVER
                        ? SceneSources.serverSha256(scripts)
                        : SceneSources.clientSha256(client);
                var seed = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
                return SceneDocument.copy(
                        found.scene(),
                        client.getFileName().toString(),
                        world.variant().getKey(),
                        seed,
                        sha256);
            }
        }
        throw new ToolProblem("There is no cut scene named \"" + name + "\" of " + client.getFileName()
                + " or its server. Call scene_list. A scene of a lobby is copied in the viewer's video director.");
    }

    private static Path clientOf(AgentHub hub, @Nullable String asked, SceneDocument document) throws ToolProblem {
        if (asked != null || document.client().isEmpty()) {
            return hub.resolveClient(asked);
        }
        try {
            return hub.resolveClient(document.client());
        } catch (ToolProblem unknown) {
            throw new ToolProblem("The scene was made on " + document.client()
                    + ", which Cruma does not know here. Pass client with its folder, as its names come from there.");
        }
    }

    private static FilmClient lookup(AgentHub hub, Path client) {
        var world = hub.getConfigFor(client).viewer().world();
        return FilmClient.of(
                client,
                world.variant(),
                world.spawns() == null ? null : world.spawns().scriptPath());
    }

    private static int rate(AgentHub hub) {
        return Math.max(1, ExportSettings.in(loader(hub.getConfigDirectory())).getVideoRate());
    }

    private static ToolResult read(ToolArgs args, AgentHub hub, Job job) throws ToolProblem {
        var filter = FilmRead.filter(args);
        var asked = args.getText("client");
        var client = hub.resolveClient(asked);
        job.setProgress("Reading the scene");
        var name = args.getRequired("scene");
        var found = scenes(hub.getConfigFor(client), client).find(name);
        var document = found.file() == null ? SceneDocument.of(found.scene()) : read(found.file(), name);
        if (found.file() != null) {
            client = clientOf(hub, asked, document);
        }
        var answer = FilmRead.answer(document, lookup(hub, client), rate(hub), filter);
        answer.put("kind", found.kind().getWord());
        if (found.file() != null) {
            answer.put("file", found.file().toString());
        } else if (filter.part() == null) {
            answer.put(
                    "next",
                    "This is a cut scene. film_edit copy_of makes it a scene of yours, which film_actors can change.");
        }
        return ToolResult.data(answer);
    }

    private static ToolResult actors(ToolArgs args, AgentHub hub, Job job) throws ToolProblem {
        var config = hub.getConfig();
        var folder = config.viewer().director().scenesFolder();
        var name = args.getRequired("scene");
        var file = new SceneFileStore(folder, folder).getFile(name);
        if (!Files.isRegularFile(file)) {
            throw new ToolProblem("You have no scene named " + name
                    + ". film_actors changes your own scenes: make one with film_edit name, or copy a cut"
                    + " scene with film_edit copy_of.");
        }
        if (!(args.getJson().get("ops") instanceof JSONArray ops) || ops.isEmpty()) {
            throw new ToolProblem(
                    "ops must be a list of what to do, such as [{\"op\": \"npc\", \"npc\": \"Valakas\", \"at\":"
                            + " \"83336,147972,-3400\"}].");
        }
        var document = read(file, name);
        var lookup = lookup(hub, clientOf(hub, args.getText("client"), document));
        var rate = rate(hub);
        job.setProgress("Directing " + ops.size() + " ops");
        var done = FilmOps.apply(document, ops, lookup, config.viewer().camera().locations(), rate);
        var next = done.document();
        var answer = new JSONObject();
        answer.put("scene", next.name());
        answer.put("file", file.toString());
        answer.put("seconds", round(next.seconds()));
        answer.put("done", done.done());
        if (!done.notes().isEmpty()) {
            answer.put("notes", done.notes());
        }
        var warnings = new JSONArray();
        var more = 0;
        for (var warning : FilmRead.warnings(next, lookup, rate)) {
            if (warnings.size() < DEFAULT_LIMIT) {
                warnings.add(FilmRead.warning(next, warning, lookup));
            } else {
                more++;
            }
        }
        answer.put("warnings", warnings);
        if (more > 0) {
            answer.put("warnings_more", more);
        }
        answer.put("tiles", next.scene().closeTiles());
        if (args.readFlag("preview", false)) {
            answer.put("preview", "Nothing was written. Call again without preview to write it.");
            return ToolResult.data(answer);
        }
        try {
            backUp(file);
            SceneFileStore.write(file, next);
        } catch (IOException failed) {
            throw new ToolProblem("The scene was not written: " + failed.getMessage());
        }
        answer.put("next", "film_read shows all of it. Open its tiles with world_open and call film_frames to see it.");
        return ToolResult.data(answer).withFolder(file.getParent());
    }

    private static Path free(SceneFileStore store, String name) throws ToolProblem {
        var file = store.getFile(name);
        if (Files.exists(file)) {
            throw new ToolProblem("You have a scene named " + SceneFileStore.safeName(name)
                    + ". Pass scene to change it, or take another name.");
        }
        return file;
    }

    private static SceneDocument read(Path file, String name) throws ToolProblem {
        if (!Files.isRegularFile(file)) {
            throw new ToolProblem(
                    "You have no scene named " + name + ". Call scene_list, or give name to make a new one.");
        }
        try {
            return SceneFileStore.read(file);
        } catch (IOException refused) {
            throw new ToolProblem("The scene " + name + " does not open: " + refused.getMessage());
        }
    }

    private static JSONObject describe(Edited edited, Path file) {
        var document = edited.document();
        var answer = new JSONObject();
        answer.put("scene", document.name());
        answer.put("file", file.toString());
        answer.put("seconds", round(document.seconds()));
        answer.put("keys", keys(document.path()));
        answer.put("tiles", document.scene().closeTiles());
        if (!edited.notes().isEmpty()) {
            answer.put("notes", edited.notes());
        }
        answer.put(
                "next", "Open its tiles with world_open and call film_frames to see it. film_render makes the video.");
        return answer;
    }

    private static JSONArray keys(@Nullable CameraPath path) {
        var keys = new JSONArray();
        if (path == null) {
            return keys;
        }
        var index = 0;
        for (var key : path.getKeys()) {
            var item = new JSONObject();
            item.put("index", index++);
            item.put("time", round(key.seconds()));
            item.put("point", Math.round(key.x()) + "," + Math.round(key.y()) + "," + Math.round(key.z()));
            item.put("yaw", round(key.yaw()));
            item.put("pitch", round(key.pitch()));
            if (key.lookAt() > 0) {
                item.put("actor", key.lookAt());
            }
            keys.add(item);
        }
        return keys;
    }

    private static ToolResult render(ToolArgs args, AgentHub hub, Job job) throws ToolProblem, InterruptedException {
        var asked = args.getText("client");
        var searched = hub.resolveClient(asked);
        var hour = hour(args);
        var loader = loader(hub.getConfigDirectory());
        var settings = ExportSettings.in(loader);
        var parent = folder(args, hub, settings);
        hub.getRoom().require(parent, hub.getAccess());
        var width = args.getInteger("width", settings.getVideoWidth());
        var height = args.getInteger("height", settings.getVideoHeight());
        var rate = args.getInteger("rate", settings.getVideoRate());
        requireVideo(width, height, rate);
        var from = args.getNumber("from", 0);
        var to = args.getNumber("to", Double.POSITIVE_INFINITY);
        job.setProgress("Reading the scene");
        var found = scenes(hub.getConfigFor(searched), searched).find(args.getRequired("scene"));
        var client = sceneClient(hub, asked, searched, found);
        var world = hub.getConfigFor(client).viewer().world();
        var scene = found.scene();
        if (from >= Math.min(to, scene.seconds())) {
            throw new ToolProblem(String.format(
                    Locale.ROOT,
                    "from must lie before to and before the end of the scene, at %.2f seconds.",
                    scene.seconds()));
        }
        var request = SceneRequest.of(scene, client, world.variant());
        var lack = request.lack(scene);
        if (lack != null) {
            throw new ToolProblem(lack);
        }
        if (FfmpegApp.find() == null) {
            throw new ToolProblem(FfmpegApp.installHint());
        }
        var run = start(job, () -> {
            Files.createDirectories(parent);
            var file = SceneVideoFile.in(parent, scene.name(), world.variant());
            var temporary = Files.createTempDirectory("cruma-record-");
            var scripts = world.spawns() == null ? null : world.spawns().scriptPath();
            var options = new RecordOptions(
                    file,
                    width,
                    height,
                    rate,
                    from,
                    to,
                    hour == null ? Double.NaN : hour,
                    MapEventChoices.text(MapEventChoices.in(loader).of(client)),
                    scripts,
                    world.variant(),
                    request.tiles(),
                    true);
            var arguments = new ArrayList<>(
                    List.of("viewer", "-c", hub.getConfigDirectory().toString(), "--client", client.toString()));
            arguments.addAll(
                    found.file() == null
                            ? List.of("--scene", scene.name())
                            : List.of("--scene-file", found.file().toString()));
            arguments.addAll(options.arguments());
            return new RecordingProcess(OwnProgram.command(arguments), file, temporary, line -> progress(job, line));
        });
        job.onCancel(run::stop);
        if (job.isCancelled()) {
            run.stop();
        }
        run.await();
        var done = run.getDone();
        if (done == null) {
            throw new ToolProblem(
                    job.isCancelled() ? "Stopped. Nothing was written." : "The render failed: " + run.getFailure());
        }
        hub.getRoom().wrote(Path.of(done.text()));
        return video(
                Path.of(done.text()),
                client,
                done.frames(),
                width,
                height,
                rate,
                FilmScenes.hourOf(scene, hour),
                run.getSkipped());
    }

    private static void requireVideo(int width, int height, int rate) throws ToolProblem {
        if (width < 16 || height < 16 || width % 2 != 0 || height % 2 != 0) {
            throw new ToolProblem("width and height must be even numbers of at least 16, as a video has no odd sizes.");
        }
        if (rate < 1 || rate > MOST_RATE) {
            throw new ToolProblem("rate must be from 1 to " + MOST_RATE + " frames a second.");
        }
    }

    private static RecordingProcess start(Job job, Starter starter) throws ToolProblem {
        try {
            job.setProgress("Starting Cruma");
            return starter.start();
        } catch (IOException failed) {
            throw new ToolProblem("The render did not start: " + failed.getMessage());
        }
    }

    private static void progress(Job job, RecordLine line) {
        switch (line.kind()) {
            case STAGE -> job.setProgress(line.text());
            case FRAME -> job.setProgress("Frame " + line.frame() + " of " + line.frames());
            case SKIPPED, DONE, FAILED -> {
            }
        }
    }

    private static ToolResult video(
            Path file, Path client, int frames, int width, int height, int rate, @Nullable Double hour, String skipped)
            throws ToolProblem {
        var answer = new JSONObject();
        answer.put("file", file.toString());
        answer.put("client", client.getFileName().toString());
        answer.put("frames", frames);
        answer.put("seconds", round((double) frames / rate));
        answer.put("size", width + "x" + height);
        answer.put("rate", rate);
        answer.put("hour", hour == null ? "of the settings" : hour);
        try {
            answer.put("megabytes", round(Files.size(file) / 1e6));
        } catch (IOException gone) {
            throw new ToolProblem("The video has gone from " + file + ".");
        }
        if (!skipped.isEmpty()) {
            answer.put("note", skipped.strip());
        }
        return ToolResult.data(answer).withFolder(file.getParent());
    }

    private static ToolResult export(ToolArgs args, AgentHub hub, Job job) throws ToolProblem {
        var asked = args.getText("client");
        var searched = hub.resolveClient(asked);
        var hour = hour(args);
        var loader = loader(hub.getConfigDirectory());
        var settings = ExportSettings.in(loader);
        var parent = folder(args, hub, settings);
        hub.getRoom().require(parent, hub.getAccess());
        job.setProgress("Reading the scene");
        var found = scenes(hub.getConfigFor(searched), searched).find(args.getRequired("scene"));
        var client = sceneClient(hub, asked, searched, found);
        var world = hub.getConfigFor(client).viewer().world();
        var plan = new Plan(
                client,
                world.variant(),
                world.rendering().textureQuality(),
                parent,
                settings.getScale(),
                MapEventChoices.in(loader).of(client),
                (int) Math.round(hour == null ? world.rendering().timeOfDay() : hour),
                (float) world.music().fadeSeconds());
        try {
            var result = SceneBlenderJob.run(
                    found.scene(),
                    plan,
                    (text, done) -> job.setProgress(text + String.format(Locale.ROOT, " (%.0f%%)", done * 100)),
                    job::isCancelled);
            var answer = new JSONObject();
            answer.put("folder", result.folder().toString());
            answer.put("client", client.getFileName().toString());
            answer.put("scene", found.scene().name());
            answer.put("hour", found.scene().hourOr(plan.hour()));
            answer.put("actors", result.actors());
            answer.put("rigs", result.rigs());
            answer.put("sounds", result.sounds());
            answer.put("effects", result.effects());
            answer.put("triangles", result.triangles());
            if (!result.skipped().isEmpty()) {
                answer.put("left_out", result.skipped());
            }
            hub.getRoom().wrote(result.folder());
            return ToolResult.data(answer).withFolder(result.folder());
        } catch (CancellationException stopped) {
            throw new ToolProblem("Stopped. Nothing was written.");
        } catch (IOException failed) {
            throw new ToolProblem("The export failed: " + failed.getMessage());
        }
    }

    private static Path folder(ToolArgs args, AgentHub hub, ExportSettings settings) throws ToolProblem {
        var asked = args.getText("folder");
        var usual = settings.getFolder();
        if (asked == null) {
            return usual;
        }
        var folder = Path.of(asked).toAbsolutePath().normalize();
        if (!folder.equals(usual.toAbsolutePath().normalize())
                && !hub.getAccess().allows(Access.WRITE)) {
            throw new ToolProblem("Writing outside the export folder " + usual + " needs Write access, and Cruma gives "
                    + hub.getAccess().getLabel()
                    + ". Leave folder out, or ask the person to raise it in Settings, AI agent.");
        }
        return folder;
    }

    private static ConfigLoader loader(Path directory) {
        return new ConfigLoader(ConfigLoader.parseOptions(new String[] {"-c", directory.toString()}));
    }

    private static @Nullable Double hour(ToolArgs args) throws ToolProblem {
        var hour = args.getNumber("hour");
        if (hour != null && !(hour >= 0 && hour <= LAST_HOUR)) {
            throw new ToolProblem("hour must be from 0 to 24.");
        }
        return hour;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    @FunctionalInterface
    private interface Starter {
        RecordingProcess start() throws IOException;
    }
}

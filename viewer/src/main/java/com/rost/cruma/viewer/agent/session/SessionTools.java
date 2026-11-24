package com.rost.cruma.viewer.agent.session;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.Target.Kind;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerLink;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.launch.OwnProgram;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

public final class SessionTools implements ToolGroup {
    private static final int MOST_TILES = 25;
    private static final int MOST_VIEWERS = 3;
    private static final double DEFAULT_RADIUS = 4000;
    private static final int MOST_WAIT_SECONDS = 40;
    private static final long VIEWER_STARTS_NANOS = TimeUnit.SECONDS.toNanos(120);
    private static final long TILES_LOAD_NANOS = TimeUnit.MINUTES.toNanos(15);
    private static final float HEIGHT_OF_A_FLAT_POINT = 8000;
    private static final int PLACES_SHOWN = 20;

    static List<String> arguments(
            Path config,
            Path client,
            List<String> tiles,
            String camera,
            boolean named,
            boolean hidden,
            boolean headless,
            boolean editing) {
        var arguments =
                new ArrayList<>(List.of("viewer", "--config", config.toString(), "--client", client.toString()));
        if (tiles.isEmpty()) {
            arguments.add("--effects");
        } else {
            arguments.add(named ? "--map" : "--tiles");
            arguments.add(String.join(",", tiles));
        }
        arguments.addAll(List.of("--camera", camera, "--agent"));
        if (headless) {
            arguments.add("--headless");
        } else if (hidden) {
            arguments.add("--hidden");
        }
        if (editing) {
            arguments.add("--editing");
        }
        return arguments;
    }

    static void requireSaved(@Nullable JSONObject status) throws ToolProblem {
        if (status == null) {
            throw new ToolProblem(
                    "The viewer does not answer, so its changes cannot be read. Call world_close with discard: true to"
                            + " close it and lose them.");
        }
        if (status.getBooleanValue("unsaved")) {
            throw new ToolProblem(
                    "This viewer has changes that are not saved in the world, in geodata, in spawns, in areas or in"
                            + " effects. Save them with edit_export, geo_save, spawn_save, area_save or effect_edit"
                            + " export, or call world_close with discard: true to lose them.");
        }
    }

    static Set<String> tilesOf(List<String> maps) {
        var tiles = new HashSet<String>();
        for (var map : maps) {
            if (WorldTile.isMapName(map)) {
                tiles.add(tileName(WorldTile.fromMapName(map)));
            }
        }
        return tiles;
    }

    @Override
    public void register(Tools tools) {
        tools.hub(
                ToolSpec.named(
                                "cruma_status",
                                "Status",
                                "What Cruma has now: clients, open viewers with their camera, your access and running"
                                        + " jobs. Call this first.")
                        .build(),
                (args, hub, job) -> status(hub));
        tools.hub(
                ToolSpec.named(
                                "place_find",
                                "Find a place",
                                "Finds named places by words, or names the place and tile at a point. Works with no"
                                        + " world open.")
                        .text("query", "Words of a name, such as giran, or a point x,y,z. Leave it out for all places.")
                        .build(),
                (args, hub, job) -> places(args, hub));
        tools.hub(
                ToolSpec.named(
                                "world_open",
                                "Open a world",
                                "Opens a client on the tiles around a target and waits until they are loaded. Use"
                                        + " hidden for your own looking, visible to show the person.")
                        .target("Where to look. The camera starts there.")
                        .list("tiles", "Tiles to hold, such as 22_22, in place of what the target needs.")
                        .text("map", "A map with a name of its own, such as Lobby01, in place of tiles.")
                        .flag(
                                "effects",
                                "Opens the Effects tool with no map, as Effects of the start menu, for effect_play and"
                                        + " effect_edit.")
                        .number("radius", "Units around the target to hold. 4000 when left out. A tile is 32768 wide.")
                        .text(
                                "client",
                                "A client from cruma_status by name, or a client folder. The first known one when left"
                                        + " out.")
                        .choice(
                                "window",
                                "hidden has no window. visible opens a window for the person. headless needs no"
                                    + " display and no graphics card, as on a server: data and edits work, pictures do"
                                    + " not. hidden when left out.",
                                "hidden",
                                "visible",
                                "headless")
                        .flag("editing", "Start in Edit, so edit tools work at once. Needs Edit.")
                        .writes()
                        .build(),
                SessionTools::open);
        tools.hub(
                ToolSpec.named(
                                "world_close",
                                "Close a world",
                                "Closes a viewer the agent opened. A window of the person stays open. It refuses to"
                                        + " lose unsaved changes.")
                        .text("viewer", "The viewer id from cruma_status. Without it, the viewer used last.")
                        .flag("discard", "Close even when changes are not saved. They are lost. Needs Edit.")
                        .destructive()
                        .build(),
                SessionTools::close);
        tools.hub(
                ToolSpec.named(
                                "job_wait",
                                "Wait for a job",
                                "Waits for a call that answered with a job, and gives its answer.")
                        .text("job", "The job id.")
                        .integer("seconds", "How long to wait, at most " + MOST_WAIT_SECONDS + ". 30 when left out.")
                        .required("job")
                        .build(),
                (args, hub, job) -> {
                    var waited = find(args, hub);
                    var seconds = Math.clamp(args.getInteger("seconds", 30), 1, MOST_WAIT_SECONDS);
                    return hub.getJobs().answer(waited, TimeUnit.SECONDS.toMillis(seconds));
                });
        tools.hub(
                ToolSpec.named("job_cancel", "Stop a job", "Stops a call that answered with a job.")
                        .text("job", "The job id.")
                        .required("job")
                        .build(),
                (args, hub, job) -> {
                    var stopped = find(args, hub);
                    stopped.cancel();
                    return ToolResult.text("Stopping " + stopped.getTool() + ".");
                });
    }

    private static Job find(ToolArgs args, AgentHub hub) throws ToolProblem {
        var id = args.getRequired("job");
        var job = hub.getJobs().find(id);
        if (job == null) {
            throw new ToolProblem("There is no job " + id + ". Its answer may have been read already.");
        }
        return job;
    }

    private static ToolResult status(AgentHub hub) throws InterruptedException {
        var clients = new JSONArray();
        for (var client : hub.getClients()) {
            var item = new JSONObject();
            item.put("name", client.getFileName().toString());
            item.put("folder", client.toString());
            clients.add(item);
        }
        var viewers = new JSONArray();
        for (var link : hub.getViewers().list()) {
            viewers.add(describe(hub, link));
        }
        var jobs = new JSONArray();
        for (var job : hub.getJobs().getRunning()) {
            if (job.getTool().equals("cruma_status")) {
                continue;
            }
            var item = new JSONObject();
            item.put("job", job.getId());
            item.put("tool", job.getTool());
            item.put("progress", job.getProgress());
            jobs.add(item);
        }
        var status = new JSONObject();
        status.put("access", hub.getAccess().getWord());
        status.put("clients", clients);
        status.put("viewers", viewers);
        status.put("jobs", jobs);
        if (clients.isEmpty()) {
            status.put(
                    "next",
                    "Cruma knows no client yet. Ask the person for the folder of a client, and pass it as client to"
                            + " world_open.");
        } else if (viewers.isEmpty()) {
            status.put("next", "No world is open. Call world_open with a target.");
        }
        return ToolResult.data(status);
    }

    private static JSONObject describe(AgentHub hub, ViewerLink link) throws InterruptedException {
        var endpoint = link.getEndpoint();
        var item = new JSONObject();
        item.put("viewer", endpoint.id());
        item.put("client", Path.of(endpoint.client()).getFileName().toString());
        item.put("tiles", endpoint.tiles());
        item.put("window", endpoint.headless() ? "headless" : endpoint.hidden() ? "hidden" : "visible");
        item.put("owner", endpoint.owned() ? "agent" : "person");
        var refused = hub.getViewers().getRefusal(link);
        if (refused != null) {
            item.put("problem", refused);
        }
        var status = link.getStatus();
        if (status != null) {
            item.putAll(status);
        }
        return item;
    }

    private static ToolResult places(ToolArgs args, AgentHub hub) throws ToolProblem {
        var locations = hub.getConfig().viewer().camera().locations();
        var query = args.getText("query");
        var result = new JSONObject();
        var found = new JSONArray();
        if (query != null && Target.parse(query).kind() == Kind.POINT) {
            var point = Target.parse(query);
            result.put("tile", tileName(WorldTile.at(point.x(), point.y())));
            var nearest = locations.stream().min((a, b) -> Double.compare(distance(a, point), distance(b, point)));
            nearest.ifPresent(place -> {
                found.add(write(place));
                result.put("distance", Math.round(distance(place, point)));
            });
        } else {
            var words = query == null
                    ? new String[0]
                    : query.toLowerCase(Locale.ROOT).split("\\s+");
            for (var place : locations) {
                var name = place.name().toLowerCase(Locale.ROOT);
                if (List.of(words).stream().allMatch(name::contains) && found.size() < PLACES_SHOWN) {
                    found.add(write(place));
                }
            }
        }
        result.put("places", found);
        return ToolResult.data(result);
    }

    private static double distance(Location place, Target point) {
        return Math.hypot(place.x() - point.x(), place.y() - point.y());
    }

    private static JSONObject write(Location place) {
        var item = new JSONObject();
        item.put("name", place.name());
        item.put("point", Math.round(place.x()) + "," + Math.round(place.y()) + "," + Math.round(place.z()));
        item.put("tile", tileName(WorldTile.at(place.x(), place.y())));
        return item;
    }

    private static String tileName(WorldTile tile) {
        return tile.x() + "_" + tile.y();
    }

    private static ToolResult open(ToolArgs args, AgentHub hub, Job job) throws ToolProblem, InterruptedException {
        if (args.readFlag("editing", false) && !hub.getAccess().allows(Access.EDIT)) {
            throw new ToolProblem(
                    "editing needs Edit access. Leave it out, or ask the person to raise it in Settings, AI agent.");
        }
        var client = hub.resolveClient(args.getText("client"));
        var config = hub.getConfigFor(client);
        var start = start(args, config);
        var map = args.getText("map");
        var effects = args.readFlag("effects", false);
        if (effects && (map != null || args.has("tiles") || args.readFlag("editing", false))) {
            throw new ToolProblem("effects opens no map, so it goes without map, tiles and editing.");
        }
        var tiles = effects
                ? List.<String>of()
                : map == null ? tiles(args, config, client, start) : List.of(named(client, map));
        var window = args.getText("window", "hidden");
        var hidden = !"visible".equals(window);
        var headless = "headless".equals(window);
        var kept = reusable(hub, client, tiles, hidden, headless);
        if (kept != null) {
            hub.getViewers().use(kept);
            return ToolResult.data(describe(hub, kept));
        }
        var own = hub.getViewers().list().stream()
                .filter(link -> link.getEndpoint().owned())
                .count();
        if (own >= MOST_VIEWERS) {
            throw new ToolProblem("You have " + own
                    + " viewers open, and each holds its own memory. Close one with world_close first.");
        }
        job.setProgress("Starting Cruma");
        var process = launch(hub, client, tiles, start, hidden, headless, args.readFlag("editing", false), map != null);
        hub.started(process);
        job.onCancel(process::destroy);
        var link = await(hub, process, job);
        var refused = hub.getViewers().getRefusal(link);
        if (refused != null) {
            link.shutdown();
            throw new ToolProblem(refused);
        }
        hub.getViewers().use(link);
        return ToolResult.data(describe(hub, link));
    }

    private static Target start(ToolArgs args, Config config) throws ToolProblem {
        var text = args.getText("target");
        var camera = config.viewer().camera();
        if (text == null) {
            var place = camera.start();
            return new Target(Kind.POINT, place.name(), place.x(), place.y(), place.z());
        }
        var target = Target.parse(text);
        return switch (target.kind()) {
            case POINT, TILE -> target;
            case NAME -> {
                var place = Target.place(camera.locations(), target.text());
                if (place.isEmpty()) {
                    throw new ToolProblem(
                            "There is no place named \"" + text + "\". Call place_find, or give a point or a tile.");
                }
                yield new Target(
                        Kind.POINT,
                        text,
                        place.get().x(),
                        place.get().y(),
                        place.get().z());
            }
            default -> throw new ToolProblem(text + " needs an open world. Give a place, a tile or a point.");
        };
    }

    private static List<String> tiles(ToolArgs args, Config config, Path client, Target start) throws ToolProblem {
        var world = config.viewer().world();
        var maps = WorldMapCatalog.discover(client, world.variant());
        var named = new LinkedHashSet<>(args.list("tiles"));
        if (named.isEmpty()) {
            var radius = args.getNumber("radius", DEFAULT_RADIUS);
            var low = WorldTile.at((float) (start.x() - radius), (float) (start.y() - radius));
            var high = WorldTile.at((float) (start.x() + radius), (float) (start.y() + radius));
            for (var x = low.x(); x <= high.x(); x++) {
                for (var y = low.y(); y <= high.y(); y++) {
                    named.add(x + "_" + y);
                }
            }
        }
        var chosen = new ArrayList<String>();
        for (var map : maps) {
            if (WorldTile.isMapName(map) && named.contains(tileName(WorldTile.fromMapName(map)))) {
                chosen.add(map);
            }
        }
        if (chosen.isEmpty()) {
            throw new ToolProblem("The client has no map on the tiles " + named + ".");
        }
        if (chosen.size() > MOST_TILES) {
            throw new ToolProblem("That is " + chosen.size() + " tiles, and a viewer takes at most " + MOST_TILES
                    + ", as each takes about 140 MB of memory. Ask for a smaller radius.");
        }
        return chosen;
    }

    private static String named(Path client, String map) throws ToolProblem {
        var maps = WorldMapCatalog.namedMaps(client);
        for (var name : maps) {
            if (name.equalsIgnoreCase(map)) {
                return name;
            }
        }
        throw new ToolProblem(
                maps.isEmpty()
                        ? "This client has no map with a name of its own. Give a target or tiles."
                        : "This client has no map " + map + ". Its maps with a name of their own are "
                                + String.join(", ", maps) + ".");
    }

    private static ViewerLink reusable(
            AgentHub hub, Path client, List<String> tiles, boolean hidden, boolean headless) {
        for (var link : hub.getViewers().list()) {
            var endpoint = link.getEndpoint();
            if (endpoint.owned()
                    && hub.getViewers().getRefusal(link) == null
                    && endpoint.hidden() == hidden
                    && endpoint.headless() == headless
                    && endpoint.client().equals(client.toString())
                    && (tiles.isEmpty()
                            ? endpoint.tiles().isEmpty()
                            : new HashSet<>(endpoint.tiles()).containsAll(tiles))) {
                return link;
            }
        }
        return null;
    }

    private static Process launch(
            AgentHub hub,
            Path client,
            List<String> tiles,
            Target start,
            boolean hidden,
            boolean headless,
            boolean editing,
            boolean named)
            throws ToolProblem {
        var high = start.z() == null;
        var camera = start.x()
                + ","
                + start.y()
                + ","
                + (high ? HEIGHT_OF_A_FLAT_POINT : start.z())
                + ",270,"
                + (high ? -45 : -12);
        var arguments = arguments(hub.getConfigDirectory(), client, tiles, camera, named, hidden, headless, editing);
        try {
            var temporary = Files.createTempDirectory("cruma-agent-");
            var builder = new ProcessBuilder(OwnProgram.command(arguments))
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD);
            builder.environment()
                    .put(
                            "JAVA_TOOL_OPTIONS",
                            OwnProgram.recordingOptions(System.getenv("JAVA_TOOL_OPTIONS"), temporary));
            var process = builder.start();
            var drafts = hub.getFolder().resolve("recovery").resolve(Long.toString(process.pid()));
            process.onExit().thenRun(() -> {
                removeFolder(temporary);
                removeFolder(drafts);
            });
            return process;
        } catch (IOException failed) {
            throw new ToolProblem("Cruma could not start a viewer: " + failed.getMessage());
        }
    }

    private static void removeFolder(Path folder) {
        try (var files = Files.walk(folder)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException left) {
        }
    }

    private static ViewerLink await(AgentHub hub, Process process, Job job) throws ToolProblem, InterruptedException {
        var deadline = System.nanoTime() + VIEWER_STARTS_NANOS;
        ViewerLink link = null;
        while (link == null) {
            if (!process.isAlive()) {
                throw stopped(hub, process);
            }
            if (System.nanoTime() > deadline) {
                process.destroy();
                throw new ToolProblem("The viewer did not start in time. Its log is in " + logs(hub) + ".");
            }
            link = hub.getViewers().ofProcess(process.pid());
            if (link == null) {
                Thread.sleep(200);
            }
        }
        deadline = System.nanoTime() + TILES_LOAD_NANOS;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw stopped(hub, process);
            }
            var status = link.getStatus();
            if (status != null && status.getBooleanValue("ready")) {
                return link;
            }
            if (status != null) {
                job.setProgress("Loaded "
                        + status.getIntValue("tiles_loaded")
                        + " of "
                        + status.getIntValue("tiles_total")
                        + " tiles");
            }
            Thread.sleep(200);
        }
        process.destroy();
        throw new ToolProblem("The tiles did not load in time. The log is in " + logs(hub) + ".");
    }

    private static ToolProblem stopped(AgentHub hub, Process process) {
        return new ToolProblem(
                "The viewer stopped with code " + process.exitValue() + ". Its log is in " + logs(hub) + ".");
    }

    private static Path logs(AgentHub hub) {
        return hub.getFolder().resolve("logs");
    }

    private static ToolResult close(ToolArgs args, AgentHub hub, Job job) throws ToolProblem, InterruptedException {
        var link = hub.getViewers().pick(args.getText("viewer"));
        var endpoint = link.getEndpoint();
        if (!endpoint.owned()) {
            throw new ToolProblem("This is a window of the person, and only they close it.");
        }
        var discard = args.readFlag("discard", false);
        if (discard && !hub.getAccess().allows(Access.EDIT)) {
            throw new ToolProblem(
                    "discard loses changes, so it needs Edit access. Leave it out, or ask the person to raise it.");
        }
        if (!discard) {
            requireSaved(link.getStatus());
        }
        link.shutdown();
        var process = ProcessHandle.of(endpoint.pid());
        for (var wait = 0; wait < 100 && process.isPresent() && process.get().isAlive(); wait++) {
            Thread.sleep(100);
        }
        return ToolResult.text("Closed " + endpoint.id() + ".");
    }
}

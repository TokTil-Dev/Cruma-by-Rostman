package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.viewer.agent.McpServer.Calls;
import com.rost.cruma.viewer.agent.Tools.OnHub;
import com.rost.cruma.viewer.agent.Tools.OnViewer;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigurationProblem;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.diagnostics.LogFiles;
import com.rost.cruma.viewer.launch.ClientTables;
import com.rost.cruma.viewer.launch.RecentClients;
import com.rost.cruma.viewer.system.SoftHeapLimit;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class AgentHub {
    private static final long ANSWER_WAIT_MILLIS = 45_000L;
    private static final long LAST_ANSWERS_MILLIS = 10_000L;
    private static final long LAST_JOBS_MILLIS = 10_000L;
    private static final String INSTRUCTIONS = """
        Cruma shows, edits, films and exports the world of Lineage II clients.
        Start with cruma_status: it names the clients, the open viewers and your access.
        Tools of a viewer need an open world. world_open opens tiles around a target. Use window=hidden for your \
        own looking and surveys, so the person's work is not touched, and visible to show them something. A viewer \
        holds only the tiles it opened with.
        A target is one text: a place (Giran Castle Town), a tile (22_22), a point (x,y,z), an actor path, camera, \
        selection or a mark of the last picture (#7).
        Points are world units, the same numbers as the game. Z points up and Y points south. A tile is 32768 units \
        wide, and a geodata cell 16. Yaw 0 looks east and 90 south. Pitch 0 is level, negative looks down.
        When a call answers with a job, call job_wait with it. Changes go into the Undo list. Nothing is saved into \
        the client or server folders unless the person gives Write access.
        The tool guide finds and reads the Guide, which explains each concept in the words the person sees on the \
        screen.\
        """;

    private final ConfigLoader loader;
    private final Tools tools = Tools.all();
    private final Jobs jobs = new Jobs();
    private final ViewerDirectory viewers;
    private final List<Process> started = new ArrayList<>();
    private final ExportRoom room = new ExportRoom();

    public AgentHub(ConfigLoader loader) {
        this.loader = loader;
        viewers = new ViewerDirectory(getFolder());
    }

    public static void run(String[] arguments) {
        var protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setOut(System.err);
        System.setProperty("java.awt.headless", "true");
        ConfigLoader loader;
        try {
            loader = new ConfigLoader(ConfigLoader.parseOptions(arguments));
        } catch (ConfigurationProblem problem) {
            System.err.println(problem.getMessage());
            System.exit(2);
            return;
        }
        LogFiles.start(ViewerEndpoint.folder(loader.getConfigDirectory()), LocalDateTime.now());
        SoftHeapLimit.start();
        TableSchemas.setUserFolder(ClientTables.userFolderOf(loader.getConfigDirectory()));
        var hub = new AgentHub(loader);
        hub.serve(protocol);
        System.exit(0);
    }

    record Export(Path folder, boolean counted) {}

    static @Nullable Export exportsInto(String tool, ToolArgs args, Supplier<ExportSettings> settings)
            throws ToolProblem {
        if (!tool.equals("effect_edit")) {
            return null;
        }
        var action = args.getText("action");
        var scripts = "scripts".equals(action) || "script".equals(action) && args.readFlag("save", args.has("folder"));
        if (!"export".equals(action) && !scripts) {
            return null;
        }
        var asked = args.getText("folder");
        if (asked != null) {
            try {
                var folder = Path.of(asked);
                if (folder.isAbsolute()) {
                    return new Export(folder, !scripts);
                }
            } catch (InvalidPathException wrong) {
            }
        }
        var exports = settings.get();
        return new Export(scripts ? exports.getScriptsFolder() : exports.getFolder(), !scripts);
    }

    public synchronized Config getConfig() {
        loader.reload();
        return loader.load();
    }

    public Config getConfigFor(Path client) {
        return new ConfigLoader(ConfigLoader.parseOptions(
                        new String[] {"-c", loader.getConfigDirectory().toString(), "--client", client.toString()}))
                .load();
    }

    public Path getConfigDirectory() {
        return loader.getConfigDirectory();
    }

    public Path getFolder() {
        return ViewerEndpoint.folder(loader.getConfigDirectory());
    }

    public Access getAccess() {
        return Access.parse(getConfig().viewer().agent().access());
    }

    public ExportRoom getRoom() {
        return room;
    }

    public Jobs getJobs() {
        return jobs;
    }

    public ViewerDirectory getViewers() {
        return viewers;
    }

    public synchronized void started(Process viewer) {
        started.removeIf(process -> !process.isAlive());
        started.add(viewer);
    }

    public List<Path> getClients() {
        var clients = new LinkedHashSet<Path>();
        var configured = getConfig().viewer().world().clientPath();
        if (configured != null && !configured.toString().isEmpty() && Files.isDirectory(configured)) {
            clients.add(configured.toAbsolutePath().normalize());
        }
        for (var entry : new RecentClients(loader.getConfigDirectory()).getEntries()) {
            if (Files.isDirectory(entry.path())) {
                clients.add(entry.path().toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(clients);
    }

    public Path resolveClient(@Nullable String asked) throws ToolProblem {
        var known = getClients();
        if (asked == null) {
            if (known.isEmpty()) {
                throw new ToolProblem("Cruma knows no client yet. Pass client with the folder of a Lineage II client.");
            }
            return known.getFirst();
        }
        var folder = Path.of(asked);
        if (folder.isAbsolute() && Files.isDirectory(folder)) {
            return folder.normalize();
        }
        var wanted = asked.toLowerCase(Locale.ROOT);
        Path partly = null;
        for (var client : known) {
            var name = client.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.equals(wanted)) {
                return client;
            }
            if (partly == null && name.contains(wanted)) {
                partly = client;
            }
        }
        if (partly != null) {
            return partly;
        }
        throw new ToolProblem(
                "Cruma knows no client \"" + asked + "\". Known: " + known + ". A full folder works too.");
    }

    private void serve(PrintStream protocol) {
        var version = Objects.requireNonNullElse(AgentHub.class.getPackage().getImplementationVersion(), "development");
        var calls = new Calls() {
            @Override
            public Job start(String tool, @Nullable JSONObject arguments) {
                return AgentHub.this.start(tool, new ToolArgs(arguments));
            }

            @Override
            public ToolResult answer(Job job) throws InterruptedException {
                return jobs.answer(job, ANSWER_WAIT_MILLIS);
            }
        };
        Consumer<String> out = line -> {
            synchronized (protocol) {
                protocol.print(line);
                protocol.print('\n');
                protocol.flush();
            }
        };
        var server = new McpServer(tools, calls, out, version, INSTRUCTIONS);
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                server.handle(line);
            }
        } catch (IOException gone) {
            LogManager.getLogger(AgentHub.class).info("The agent's program went away: {}", gone.toString());
        }
        finish(server);
    }

    private void finish(McpServer server) {
        await(server::isIdle, LAST_ANSWERS_MILLIS);
        jobs.getRunning().forEach(Job::cancel);
        await(() -> jobs.getRunning().isEmpty(), LAST_JOBS_MILLIS);
    }

    private Job start(String name, ToolArgs args) {
        var entry = Objects.requireNonNull(tools.find(name));
        return jobs.start(name, job -> {
            entry.spec().requireKnown(args);
            return switch (entry) {
                case OnHub tool -> {
                    requireAccess(tool.spec());
                    requireNotStopped(tool.spec());
                    var result = tool.tool().call(args, this, job);
                    report(tool.spec(), result);
                    yield result;
                }
                case OnViewer tool -> {
                    requireNotStopped(tool.spec());
                    if (tool.alone() != null
                            && args.getText("viewer") == null
                            && viewers.list().isEmpty()) {
                        requireAccess(tool.spec());
                        var result = tool.alone().call(args, this, job);
                        report(tool.spec(), result);
                        yield result;
                    }
                    yield forward(tool.spec(), args, job);
                }
            };
        });
    }

    private void requireAccess(ToolSpec spec) throws ToolProblem {
        var access = getAccess();
        if (!access.allows(spec.access())) {
            throw new ToolProblem(spec.name()
                    + " needs "
                    + spec.access().getLabel()
                    + " access, and Cruma gives "
                    + access.getLabel()
                    + ". Ask the person to raise it in Settings, AI agent.");
        }
    }

    private void requireNotStopped(ToolSpec spec) throws ToolProblem {
        if (spec.writes() && AgentStop.isActive(getFolder())) {
            throw new ToolProblem("The person stopped agents in Cruma. Ask them to press Allow in the AI agent panel.");
        }
    }

    private void report(ToolSpec spec, ToolResult result) {
        if (!spec.writes()) {
            return;
        }
        for (var link : viewers.list()) {
            if (!link.getEndpoint().hidden()) {
                link.note(spec.title(), result);
            }
        }
    }

    private ToolResult forward(ToolSpec spec, ToolArgs args, Job job) throws ToolProblem, InterruptedException {
        var link = spec.window() ? viewers.pickWindow(args.getText("viewer")) : viewers.pick(args.getText("viewer"));
        var into = exportsInto(spec.name(), args, this::getExportSettings);
        if (into != null) {
            room.require(into.folder(), getAccess());
        }
        var call = link.call(spec.name(), args.without("viewer").getJson());
        job.onCancel(() -> link.cancel(call.id()));
        ToolResult result;
        try {
            result = call.result().get();
        } catch (ExecutionException lost) {
            throw new ToolProblem("The viewer closed during " + spec.name() + ". Call cruma_status.");
        }
        if (into != null && into.counted() && !result.hasFailed() && result.getFolder() != null) {
            room.wrote(result.getFolder());
        }
        return result;
    }

    private ExportSettings getExportSettings() {
        return ExportSettings.in(new ConfigLoader(ConfigLoader.parseOptions(
                new String[] {"-c", getConfigDirectory().toString()})));
    }

    private static void await(BooleanSupplier done, long millis) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        try {
            while (!done.getAsBoolean() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }
}

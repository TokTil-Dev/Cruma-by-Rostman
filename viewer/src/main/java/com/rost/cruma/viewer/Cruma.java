package com.rost.cruma.viewer;

import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.viewer.agent.AgentConnect;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.ViewerEndpoint;
import com.rost.cruma.viewer.application.RecordLine;
import com.rost.cruma.viewer.application.SceneRecording;
import com.rost.cruma.viewer.application.SceneRecording.Plan;
import com.rost.cruma.viewer.application.SceneRequest;
import com.rost.cruma.viewer.application.SceneVideoFile;
import com.rost.cruma.viewer.application.WorldViewer;
import com.rost.cruma.viewer.cli.TablesCommand;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.config.Config.Viewer;
import com.rost.cruma.viewer.config.Config.Viewer.Camera;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.config.Config.Viewer.Editing;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigLoader.Options;
import com.rost.cruma.viewer.config.ConfigurationProblem;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.diagnostics.CrashReports;
import com.rost.cruma.viewer.diagnostics.ErrorDialog;
import com.rost.cruma.viewer.diagnostics.Failures;
import com.rost.cruma.viewer.diagnostics.LogFiles;
import com.rost.cruma.viewer.editing.SceneFileStore;
import com.rost.cruma.viewer.launch.BackgroundApp;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.ClientTables;
import com.rost.cruma.viewer.launch.FfmpegApp;
import com.rost.cruma.viewer.launch.MapPreviewCache;
import com.rost.cruma.viewer.launch.MenuStart;
import com.rost.cruma.viewer.render.ViewerBenchmarkProperties;
import com.rost.cruma.viewer.system.ClientLauncher;
import com.rost.cruma.viewer.system.SoftHeapLimit;
import com.rost.cruma.viewer.system.WindowSystem;
import com.rost.cruma.viewer.system.WindowSystem.Placement;
import com.rost.cruma.viewer.ui.Motion;
import com.rost.cruma.viewer.ui.NativeDialogs;
import com.rost.cruma.viewer.ui.WorkspaceLayout.Mode;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class Cruma {
    private Cruma() {}

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("mcp")) {
            AgentHub.run(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("tables")) {
            TablesCommand.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Command command;
        try {
            command = parseCommand(args);
        } catch (ConfigurationProblem problem) {
            System.err.println(problem.getMessage());
            System.exit(2);
            return;
        }
        var options = command.options();
        if (options.configDirectory() != null) {
            System.setProperty(
                    AgentConnect.CONFIG_PROPERTY,
                    options.configDirectory().toAbsolutePath().toString());
        }
        boolean startMenu = startMenu(command);
        if (Set.of("viewer", "capture").contains(command.mode())
                && !options.printConfig()
                && !options.printConfigDir()) {
            var directory = new ConfigLoader(options).getConfigDirectory();
            prepareRun(options.agent() ? ViewerEndpoint.folder(directory) : directory);
            TableSchemas.setUserFolder(ClientTables.userFolderOf(directory));
        }
        SoftHeapLimit.start();
        try {
            switch (command.mode()) {
                case "viewer" -> {
                    if (options.record() != null) {
                        recordScene(options);
                    } else {
                        runWorldViewer(options, startMenu);
                    }
                }
                case "capture" -> captureWorldViewer(options);
                case "help", "--help", "-h" -> System.out.println(ConfigLoader.usage());
                default -> throw new AssertionError(command.mode());
            }
        } catch (ConfigurationProblem problem) {
            log().error("Settings problem: {}", problem.getMessage(), problem.getCause());
            System.err.println(problem.getMessage());
            if (options.record() != null) {
                System.out.println(RecordLine.failed(problem.getMessage()).write());
            }
            if (startMenu) {
                ErrorDialog.show("Cruma settings", problem.getMessage());
            }
            System.exit(2);
        } catch (Throwable failure) {
            log().fatal("Cruma stopped because of an unexpected error", failure);
            if (startMenu) {
                ErrorDialog.show(
                        "Cruma",
                        Failures.describe("Cruma", failure, Runtime.getRuntime().maxMemory()) + logHint());
            }
            System.exit(1);
        }
    }

    record Command(String mode, Options options) {}

    static Command parseCommand(String[] args) {
        int offset = 0;
        String mode = "viewer";
        if (args.length > 0 && (!args[0].startsWith("-") || args[0].equals("--help") || args[0].equals("-h"))) {
            mode = args[0];
            offset = 1;
        }
        if (!Set.of("viewer", "capture", "help", "--help", "-h").contains(mode)) {
            throw new ConfigurationProblem("Unknown command '" + mode + "'.\n" + ConfigLoader.usage());
        }
        return new Command(mode, ConfigLoader.parseOptions(Arrays.copyOfRange(args, offset, args.length)));
    }

    static boolean startMenu(Command command) {
        var options = command.options();
        return command.mode().equals("viewer")
                && options.client() == null
                && !options.noClientPicker()
                && options.record() == null
                && !options.printConfig()
                && !options.printConfigDir()
                && !ViewerBenchmarkProperties.isEnabled();
    }

    private static Logger log() {
        return LogManager.getLogger(Cruma.class);
    }

    private static void prepareRun(Path configDirectory) {
        var file = LogFiles.start(configDirectory, LocalDateTime.now());
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, failure) -> log().error("Uncaught failure in thread {}", thread.getName(), failure));
        var runtime = Runtime.getRuntime();
        log().info(
                        "Cruma {} on {} {} ({}), Java {} ({}), {} processors, heap limit {} MB, GC {}, JVM options {}",
                        Objects.requireNonNullElse(
                                Cruma.class.getPackage().getImplementationVersion(), "development build"),
                        System.getProperty("os.name"),
                        System.getProperty("os.version"),
                        System.getProperty("os.arch"),
                        System.getProperty("java.version"),
                        System.getProperty("java.vendor"),
                        runtime.availableProcessors(),
                        runtime.maxMemory() >> 20,
                        ManagementFactory.getGarbageCollectorMXBeans().stream()
                                .map(GarbageCollectorMXBean::getName)
                                .toList(),
                        ManagementFactory.getRuntimeMXBean().getInputArguments());
        if (file != null) {
            log().info("Log file: {}", file);
        }
    }

    private static String logHint() {
        var file = LogFiles.current();
        return file == null ? "" : "\n\nDetails are in the log file:\n" + file;
    }

    private static void runWorldViewer(Options options, boolean startMenu) {
        if (options.headless()) {
            WindowSystem.useNoDisplay();
            System.setProperty("java.awt.headless", "true");
        } else {
            NativeDialogs.enable();
        }
        var loader = new ConfigLoader(options);
        var config = loadConfig(loader);
        var viewer = config.viewer();
        var ui = config.ui();
        Motion.setEnabled(ui.animations());
        var screenshots = config.screenshotFolder();
        var chooseClient = options.client() == null && !options.noClientPicker();
        String notice = "";
        boolean problem = false;
        if (startMenu) {
            var crashes = CrashReports.collect(
                    LogFiles.logDirectory(loader.getConfigDirectory()), CrashReports.searchDirectories());
            if (!crashes.isEmpty()) {
                log().warn("Moved crash reports of an earlier run to {}", crashes);
                notice = "Cruma closed unexpectedly last time. Its crash report is in the logs folder.";
                problem = true;
            }
        }
        var effects = options.effects();
        var editing = options.editing();
        var menuEditing = editing;
        var sceneAtStart = options.scene();
        var copyAtStart = false;
        Path fileAtStart = null;
        var sceneFromFile = sceneOfFile(options);
        List<String> maps = effects ? List.of() : options.map() == null ? null : List.of(options.map());
        Path captureFolder = null;
        Path capturedInto = null;
        Placement windowPlacement = null;
        MenuStart menuStart = null;
        Mode modeAtStart = null;
        var cameraResumed = false;
        while (true) {
            if (chooseClient) {
                var settings = loader.load();
                Motion.setEnabled(settings.ui().animations());
                var launcher = new ClientLauncher(loader, settings, menuEditing);
                if (problem) {
                    launcher.showProblem(notice);
                } else {
                    launcher.showNotice(notice);
                }
                launcher.showCaptureFolder(captureFolder);
                launcher.setCapturedInto(capturedInto);
                captureFolder = null;
                capturedInto = null;
                launcher.openAt(windowPlacement);
                var selection = launcher.choose();
                windowPlacement = launcher.getPlacement();
                menuEditing = launcher.isEditingMode();
                if (selection == null) {
                    System.exit(0);
                    return;
                }
                ui = selection.config().ui();
                Motion.setEnabled(ui.animations());
                notice = "";
                problem = false;
                if (!selection.capture()) {
                    viewer = selection.config().viewer();
                    screenshots = selection.config().screenshotFolder();
                    maps = selection.maps();
                    effects = selection.effects();
                    editing = selection.editing();
                    menuStart = selection.start();
                } else {
                    var capture = selection.config().capture();
                    if (selection.mapPreview()) {
                        var cache = new MapPreviewCache(
                                loader.getConfigDirectory(),
                                ClientInstallation.inspect(
                                        capture.world().clientPath(),
                                        capture.world().variant()));
                        try {
                            var complete = new WorldViewer(capture.world(), null, selection.maps(), null, ui.scale())
                                    .capturePreview(capture.output(), true);
                            var patch = selection.patch();
                            if (complete && patch != null) {
                                cache.patch(capture.output().radarPath(), patch.base(), patch.tiles());
                            } else if (complete) {
                                cache.install(capture.output().radarPath());
                            }
                            notice = !complete
                                    ? "Map image creation cancelled."
                                    : patch != null
                                            ? "Map image updated for " + String.join(" and ", patch.tiles()) + "."
                                            : "Map image ready. The map shows it now.";
                        } catch (Throwable failure) {
                            log().error("Map image creation failed", failure);
                            notice = Failures.describe(
                                    "Map image creation",
                                    failure,
                                    Runtime.getRuntime().maxMemory());
                            problem = true;
                        } finally {
                            try {
                                cache.removePending(capture.output().directory());
                            } catch (Exception failure) {
                                log().warn("Cannot remove temporary map image", failure);
                            }
                        }
                        continue;
                    }
                    try {
                        var completedFolders = new ArrayList<Path>(1);
                        boolean complete = new WorldViewer(capture.world(), null, selection.maps(), null, ui.scale())
                                .capture(capture.output(), true, completedFolders::add);
                        if (complete && !completedFolders.isEmpty()) {
                            captureFolder = completedFolders.getFirst();
                        }
                        var output = capture.output();
                        capturedInto = output.writeTiles()
                                ? output.directory()
                                : output.radarPath().toAbsolutePath().getParent();
                        notice = complete
                                ? "Capture complete. Images saved to "
                                        + capture.output().directory().toAbsolutePath()
                                : "Capture cancelled. Images already written have been kept.";
                    } catch (Throwable failure) {
                        log().error("Capture failed", failure);
                        notice = Failures.describe(
                                "Capture", failure, Runtime.getRuntime().maxMemory());
                        problem = true;
                    }
                    continue;
                }
            }
            ConfigLoader.requireClientPath(
                    viewer.world().clientPath(),
                    "viewer.world.client_path",
                    loader.getConfigDirectory().resolve(ConfigLoader.USER_FILE));
            var world = viewer.world();
            if (sceneAtStart != null && maps == null) {
                var spawns = world.spawns();
                var request = SceneRequest.find(
                        sceneAtStart, world.clientPath(), world.variant(), spawns == null ? null : spawns.scriptPath());
                if (request != null && !request.tiles().isEmpty()) {
                    maps = request.tiles();
                }
            }
            if (sceneFromFile != null && maps == null) {
                maps = tilesOfFile(sceneFromFile, world);
            }
            if ((sceneAtStart != null || sceneFromFile != null) && maps != null) {
                world = world.covering(maps);
            }
            if (options.tiles() != null && maps == null) {
                maps = options.tiles();
                world = world.covering(maps);
            }
            var checkedMaps = maps == null
                    ? WorldMapCatalog.discover(
                            world.clientPath(),
                            world.variant(),
                            world.minXTile(),
                            world.maxXTile(),
                            world.minYTile(),
                            world.maxYTile())
                    : maps;
            boolean backToMenu;
            var session = new WorldViewer(
                            world,
                            cameraResumed ? viewer.camera() : startCamera(viewer.camera(), options),
                            checkedMaps,
                            loader.getConfigDirectory(),
                            ui.scale())
                    .editingSettings(agentEditing(viewer.editing(), options, loader.getConfigDirectory()))
                    .agent(viewer.agent(), options.agent(), options.hidden(), options.headless())
                    .scenesIn(viewer.director().scenesFolder())
                    .openAt(windowPlacement)
                    .screenshotsIn(screenshots)
                    .screenshotInterface(ui.screenshotInterface())
                    .effectsOnly(effects)
                    .editingAtStart(editing)
                    .modeAtStart(modeAtStart)
                    .resumeCamera(cameraResumed)
                    .sceneAtStart(sceneAtStart)
                    .copyAtStart(copyAtStart)
                    .fileAtStart(fileAtStart)
                    .sceneFromFile(sceneFromFile);
            sceneAtStart = null;
            copyAtStart = false;
            fileAtStart = null;
            sceneFromFile = null;
            modeAtStart = null;
            cameraResumed = false;
            try {
                backToMenu = session.run(!options.hidden());
            } catch (RuntimeException | Error failure) {
                if (!startMenu) {
                    throw failure;
                }
                log().error("The Viewer stopped because of an error", failure);
                notice = Failures.describe(
                        "The Viewer", failure, Runtime.getRuntime().maxMemory());
                problem = true;
                backToMenu = true;
            }
            windowPlacement = session.getPlacement();
            var request = session.getSceneRequest();
            if (request != null) {
                maps = request.tiles();
                sceneAtStart = request.scene();
                copyAtStart = request.copy();
                fileAtStart = request.file();
                chooseClient = false;
                loader.reload();
                var saved = loader.load().viewer();
                viewer = new Viewer(
                        viewer.world()
                                .withAudio(saved.world().music(), saved.world().sound())
                                .withFrameRateCap(
                                        saved.world().rendering().graphics().frameRateCap()),
                        viewer.camera(),
                        viewer.editing(),
                        viewer.director(),
                        saved.agent());
                continue;
            }
            if (session.isReloadRequested()) {
                var closed = new SettingsReload(
                        menuStart,
                        checkedMaps,
                        effects,
                        Objects.requireNonNullElse(session.getModeAtClose(), Mode.EXPLORE),
                        Objects.requireNonNull(session.getCameraAtClose()),
                        viewer.editing());
                loader.reload();
                var outcome = closed.rebuild(settingsOf(loader), Files::isDirectory, startMenu);
                var next = outcome.next();
                if (next == null) {
                    log().warn("The world did not load again after Settings: {}", outcome.notice());
                    notice = outcome.notice();
                    problem = true;
                    chooseClient = true;
                    maps = null;
                    continue;
                }
                log().info("The world loads again with the saved settings, in {}", next.mode());
                if (!next.note().isEmpty()) {
                    log().info(next.note());
                }
                viewer = next.viewer();
                ui = next.config().ui();
                Motion.setEnabled(ui.animations());
                screenshots = next.config().screenshotFolder();
                maps = next.maps();
                effects = next.effects();
                editing = next.mode() == Mode.EDITING;
                modeAtStart = next.mode();
                cameraResumed = true;
                chooseClient = false;
                continue;
            }
            if (!backToMenu) {
                break;
            }
            chooseClient = true;
            maps = null;
            loader.reload();
        }
        System.exit(0);
    }

    private static Camera startCamera(Camera camera, Options options) {
        if (options.camera() == null) {
            return camera;
        }
        var parts = options.camera().split(",");
        try {
            var place = new Location(
                    "Start", Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
            return new Camera(
                    place,
                    Double.parseDouble(parts[3]),
                    Double.parseDouble(parts[4]),
                    camera.locations(),
                    camera.mouseSensitivity());
        } catch (NumberFormatException wrong) {
            throw new ConfigurationProblem("--camera needs numbers: x,y,z,yaw,pitch");
        }
    }

    private static SettingsReload.SettingsSource settingsOf(ConfigLoader loader) {
        return new SettingsReload.SettingsSource() {
            @Override
            public Config load() {
                return loader.load();
            }

            @Override
            public Config forClient(Path client, String tiles, @Nullable String place) {
                return loader.forClient(client, tiles, false, place);
            }
        };
    }

    private static Editing agentEditing(Editing editing, Options options, Path configDirectory) {
        if (!options.agent()) {
            return editing;
        }
        return editing.withRecoveryDirectory(ViewerEndpoint.folder(configDirectory)
                .resolve("recovery")
                .resolve(Long.toString(ProcessHandle.current().pid())));
    }

    private static void recordScene(Options options) {
        var loader = new ConfigLoader(options);
        var config = loadConfig(loader);
        var viewer = config.viewer();
        var exports = config.exports();
        var asked = options.record().withSize(exports.videoWidth(), exports.videoHeight(), exports.videoRate());
        ConfigLoader.requireClientPath(
                viewer.world().clientPath(),
                "viewer.world.client_path",
                loader.getConfigDirectory().resolve(ConfigLoader.USER_FILE));
        var ffmpeg = FfmpegApp.find();
        if (ffmpeg == null) {
            throw new ConfigurationProblem(FfmpegApp.installHint());
        }
        Motion.setEnabled(false);
        var world = viewer.world();
        if (asked.variant() != null) {
            world = world.withVariant(asked.variant());
        }
        if (asked.scripts() != null) {
            world = world.withScripts(asked.scripts());
        }
        var scripts = world.spawns() == null ? null : world.spawns().scriptPath();
        var sceneFromFile = sceneOfFile(options);
        var tiles = asked.tiles();
        if (tiles == null && sceneFromFile != null) {
            tiles = tilesOfFile(sceneFromFile, world);
        } else if (tiles == null) {
            var request = SceneRequest.find(options.scene(), world.clientPath(), world.variant(), scripts);
            if (request == null) {
                throw new ConfigurationProblem("The client has no cut scene " + options.scene());
            }
            tiles = request.tiles();
        }
        world = world.covering(tiles);
        var file = SceneVideoFile.free(asked.output().toAbsolutePath().normalize());
        var plan = new Plan(file, asked.width(), asked.height(), asked.rate(), asked.from(), asked.to(), tiles);
        var recording = SceneRecording.withFfmpeg(ffmpeg, plan, line -> {
            System.out.println(line.write());
            System.out.flush();
        });
        if (asked.guarded()) {
            stopWhenInputEnds(recording);
        }
        var events = asked.events() == null ? null : MapEventChoices.read(asked.events(), scripts);
        try {
            Files.createDirectories(file.getParent());
            BackgroundApp.start("Recording a cut scene");
            new WorldViewer(
                            world,
                            viewer.camera(),
                            tiles,
                            loader.getConfigDirectory(),
                            config.ui().scale())
                    .editingSettings(viewer.editing())
                    .sceneAtStart(options.scene())
                    .sceneFromFile(sceneFromFile)
                    .recording(recording, asked.hourOf(sceneFromFile), events)
                    .run(false);
        } catch (IOException | RuntimeException | Error failure) {
            log().error("A recording stopped because of an error", failure);
            recording.fail(Failures.describe(
                    "The recording", failure, Runtime.getRuntime().maxMemory()));
        } finally {
            recording.close();
        }
        System.exit(recording.getFailure() != null ? 1 : recording.isStopped() ? 3 : 0);
    }

    private static @Nullable Scene sceneOfFile(Options options) {
        if (options.sceneFile() == null) {
            return null;
        }
        try {
            return SceneFileStore.read(options.sceneFile()).scene();
        } catch (IOException refused) {
            throw new ConfigurationProblem(refused.getMessage());
        }
    }

    private static List<String> tilesOfFile(Scene scene, World world) {
        var request = SceneRequest.of(scene, world.clientPath(), world.variant());
        var lack = request.lack(scene);
        if (lack != null) {
            throw new ConfigurationProblem(lack);
        }
        return request.tiles();
    }

    private static void stopWhenInputEnds(SceneRecording recording) {
        Thread.ofPlatform().name("cruma-record-guard").daemon().start(() -> {
            try {
                while (System.in.read() >= 0) {
                }
            } catch (IOException gone) {
                log().debug("The standard input of a recording ended: {}", gone.toString());
            }
            recording.stop();
        });
    }

    private static void captureWorldViewer(Options options) {
        var loader = new ConfigLoader(options);
        var config = loadConfig(loader);
        ConfigLoader.requireClientPath(
                config.capture().world().clientPath(),
                "capture.world.client_path",
                loader.getConfigDirectory().resolve(ConfigLoader.USER_FILE));
        new WorldViewer(config.capture().world(), null).capture(config.capture().output());
        System.exit(0);
    }

    private static Config loadConfig(ConfigLoader loader) {
        var options = loader.getOptions();
        if (options.printConfig()) {
            System.out.println(loader.renderEffectiveConfig());
            System.exit(0);
        }
        if (options.printConfigDir()) {
            System.out.println(loader.getConfigDirectory());
            System.exit(0);
        }
        return loader.load();
    }
}

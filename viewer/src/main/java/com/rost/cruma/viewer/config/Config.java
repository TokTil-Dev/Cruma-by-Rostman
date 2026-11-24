package com.rost.cruma.viewer.config;

import com.rost.cruma.core.export.ExportScale;
import com.rost.cruma.core.world.EmitterDistanceMode;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.geodata.GeoDataDisplay;
import com.rost.cruma.viewer.geodata.GeoDataDisplay.Mode;
import com.rost.cruma.viewer.geodata.GeoDataFocusMode;
import com.rost.cruma.viewer.geodata.GeoDataMesh;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.DynamicResolution;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.OptimizationProfile;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Optimizations;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Renderer;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Tuning;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Upscaler;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.UpscalerAlgorithm;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.UpscalerPreset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public record Config(Viewer viewer, Capture capture, Interface ui, Exports exports) {
    static final String SCREENSHOTS_FOLDER = "Screenshots";

    public record Interface(
            InterfaceScale scale,
            boolean screenshotInterface,
            @Nullable Path screenshotFolder,
            boolean animations) {
        public Interface {
            Objects.requireNonNull(scale, "interface scale");
        }

        public Interface(InterfaceScale scale) {
            this(scale, true, null, true);
        }
    }

    public record Exports(
            @Nullable Path folder,
            ExportScale scale,
            SceneFormat sceneFormat,
            int videoWidth,
            int videoHeight,
            int videoRate,
            boolean installAfterWrite,
            @Nullable Path scriptsFolder) {
        public Exports {
            Objects.requireNonNull(scale, "export scale");
            Objects.requireNonNull(sceneFormat, "scene format");
        }
    }

    static Path screenshotFolder(@Nullable Path chosen, Path captureFolder) {
        return chosen != null ? chosen : captureFolder.resolve(SCREENSHOTS_FOLDER);
    }

    public record Viewer(World world, Camera camera, Editing editing, Director director, Agent agent) {
        public Viewer(World world, Camera camera, Editing editing, Director director) {
            this(world, camera, editing, director, Agent.DEFAULT);
        }

        public record Agent(boolean enabled, String access) {
            public static final Agent DEFAULT = new Agent(false, "edit");
        }

        public record Director(Path scenesFolder) {}

        public record Editing(
                boolean recoveryEnabled,
                long autosaveDebounceMillis,
                int checkpointCommands,
                int retainedGenerations,
                int maximumMegabytes,
                Path recoveryDirectory,
                boolean moveActorsToTheirTile,
                boolean welcomeShown,
                String teleportCommand) {
            public Editing {
                Objects.requireNonNull(teleportCommand, "editing teleport command");
                if (autosaveDebounceMillis < 100 || autosaveDebounceMillis > 60000) {
                    throw new IllegalArgumentException("editing.autosave_debounce_ms must be within 100..60000");
                }
                if (checkpointCommands < 1 || checkpointCommands > 256) {
                    throw new IllegalArgumentException("editing.checkpoint_commands must be within 1..256");
                }
                if (retainedGenerations < 2 || retainedGenerations > 8) {
                    throw new IllegalArgumentException("editing.retained_generations must be within 2..8");
                }
                if (maximumMegabytes < 16 || maximumMegabytes > 1024) {
                    throw new IllegalArgumentException("editing.maximum_megabytes must be within 16..1024");
                }
                Objects.requireNonNull(recoveryDirectory, "editing recovery directory");
            }

            public Editing withRecoveryDirectory(Path directory) {
                return new Editing(
                        recoveryEnabled,
                        autosaveDebounceMillis,
                        checkpointCommands,
                        retainedGenerations,
                        maximumMegabytes,
                        directory,
                        moveActorsToTheirTile,
                        welcomeShown,
                        teleportCommand);
            }
        }

        public record Camera(
                Location start, double startYaw, double startPitch, List<Location> locations, double mouseSensitivity) {
            public static final double MIN_MOUSE_SENSITIVITY = 0.1;

            public static final double MAX_MOUSE_SENSITIVITY = 5;

            public Camera(Location start, double startYaw, double startPitch, List<Location> locations) {
                this(start, startYaw, startPitch, locations, 1);
            }

            public record Location(String name, float x, float y, float z) {}
        }

        public record World(
                Path clientPath,
                int minXTile,
                int maxXTile,
                int minYTile,
                int maxYTile,
                int parserWorkers,
                WorldRendering rendering,
                Music music,
                Sound sound,
                Geodata geodata,
                Spawns spawns,
                WorldVariant variant) {
            private static final Pattern TILE_NAME = Pattern.compile("(\\d+)_(\\d+)");

            public World(
                    Path clientPath,
                    int minXTile,
                    int maxXTile,
                    int minYTile,
                    int maxYTile,
                    int parserWorkers,
                    WorldRendering rendering,
                    Music music,
                    Sound sound,
                    Geodata geodata) {
                this(
                        clientPath,
                        minXTile,
                        maxXTile,
                        minYTile,
                        maxYTile,
                        parserWorkers,
                        rendering,
                        music,
                        sound,
                        geodata,
                        Spawns.NONE);
            }

            public World(
                    Path clientPath,
                    int minXTile,
                    int maxXTile,
                    int minYTile,
                    int maxYTile,
                    int parserWorkers,
                    WorldRendering rendering,
                    Music music,
                    Sound sound,
                    Geodata geodata,
                    Spawns spawns) {
                this(
                        clientPath,
                        minXTile,
                        maxXTile,
                        minYTile,
                        maxYTile,
                        parserWorkers,
                        rendering,
                        music,
                        sound,
                        geodata,
                        spawns,
                        WorldVariant.MAIN);
            }

            public World {
                if (minXTile > maxXTile || minYTile > maxYTile) {
                    throw new IllegalArgumentException(
                            "world tile bounds: min_x_tile must not exceed max_x_tile, and min_y_tile must not exceed"
                                    + " max_y_tile");
                }
                if (parserWorkers < 1) {
                    throw new IllegalArgumentException("parser_workers must be at least 1, got " + parserWorkers);
                }
                if (music == null) {
                    throw new IllegalArgumentException("world.music is required");
                }
                if (sound == null) {
                    throw new IllegalArgumentException("world.sound is required");
                }
                Objects.requireNonNull(geodata, "world.geodata is required");
                Objects.requireNonNull(spawns, "world.spawns is required");
                Objects.requireNonNull(variant, "world.variant is required");
            }

            public World covering(List<String> maps) {
                var left = minXTile;
                var right = maxXTile;
                var low = minYTile;
                var high = maxYTile;
                for (var map : maps) {
                    var tile = TILE_NAME.matcher(map);
                    if (tile.matches()) {
                        var x = Integer.parseInt(tile.group(1));
                        var y = Integer.parseInt(tile.group(2));
                        left = Math.min(left, x);
                        right = Math.max(right, x);
                        low = Math.min(low, y);
                        high = Math.max(high, y);
                    }
                }
                return new World(
                        clientPath,
                        left,
                        right,
                        low,
                        high,
                        parserWorkers,
                        rendering,
                        music,
                        sound,
                        geodata,
                        spawns,
                        variant);
            }

            public World withVariant(WorldVariant newVariant) {
                return new World(
                        clientPath,
                        minXTile,
                        maxXTile,
                        minYTile,
                        maxYTile,
                        parserWorkers,
                        rendering,
                        music,
                        sound,
                        geodata,
                        spawns,
                        newVariant);
            }

            public World withScripts(Path scripts) {
                return new World(
                        clientPath,
                        minXTile,
                        maxXTile,
                        minYTile,
                        maxYTile,
                        parserWorkers,
                        rendering,
                        music,
                        sound,
                        geodata,
                        new Spawns(scripts, spawns.enabled()),
                        variant);
            }

            public World withAudio(Music newMusic, Sound newSound) {
                return new World(
                        clientPath,
                        minXTile,
                        maxXTile,
                        minYTile,
                        maxYTile,
                        parserWorkers,
                        rendering,
                        newMusic,
                        newSound,
                        geodata,
                        spawns,
                        variant);
            }

            public World withFrameRateCap(int cap) {
                return new World(
                        clientPath,
                        minXTile,
                        maxXTile,
                        minYTile,
                        maxYTile,
                        parserWorkers,
                        rendering.withGraphics(rendering.graphics().withFrameRateCap(cap)),
                        music,
                        sound,
                        geodata,
                        spawns,
                        variant);
            }

            public record Spawns(Path scriptPath, boolean enabled) {
                public static final Spawns NONE = new Spawns(null, true);
            }

            public record Geodata(
                    Path path,
                    boolean enabled,
                    int radius,
                    boolean throughGeometry,
                    GeoDataDisplay display,
                    GeoDataFocusMode focusMode,
                    int vertexLimit) {
                public Geodata(
                        Path path, boolean enabled, int radius, boolean throughGeometry, GeoDataDisplay display) {
                    this(
                            path,
                            enabled,
                            radius,
                            throughGeometry,
                            display,
                            GeoDataFocusMode.MOUSE,
                            GeoDataMesh.DEFAULT_VERTEX_LIMIT);
                }

                public Geodata {
                    Objects.requireNonNull(display, "geodata.display is required");
                    Objects.requireNonNull(focusMode, "geodata.focus_mode is required");
                    if (vertexLimit < 250_000 || vertexLimit > GeoDataMesh.MAX_VERTICES) {
                        throw new IllegalArgumentException(
                                "geodata.vertex_limit must be within 250000..4000000, got " + vertexLimit);
                    }
                    if (radius < 128 || radius > 4096) {
                        throw new IllegalArgumentException("geodata.radius must be within 128..4096, got " + radius);
                    }
                }
            }

            public record Sound(
                    boolean enabled,
                    double volume,
                    int maxVoices,
                    double maxRadiusMultiplier) {
                public Sound {
                    if (volume < 0.0 || volume > 1.0) {
                        throw new IllegalArgumentException("sound.volume must be within 0..1, got " + volume);
                    }
                    if (maxVoices < 1 || maxVoices > 256) {
                        throw new IllegalArgumentException("sound.max_voices must be within 1..256, got " + maxVoices);
                    }
                    if (maxRadiusMultiplier < 1.0 || !Double.isFinite(maxRadiusMultiplier)) {
                        throw new IllegalArgumentException(
                                "sound.max_radius_multiplier must be at least 1, got " + maxRadiusMultiplier);
                    }
                }
            }

            public record Music(
                    boolean enabled,
                    double volume,
                    double waitSeconds,
                    double idleWaitSeconds,
                    double fadeSeconds,
                    double nightStartHour,
                    double nightEndHour) {
                public Music {
                    if (volume < 0.0 || volume > 1.0) {
                        throw new IllegalArgumentException("music.volume must be within 0..1, got " + volume);
                    }
                    if (waitSeconds < 0.0 || idleWaitSeconds < 0.0 || fadeSeconds < 0.0) {
                        throw new IllegalArgumentException("music wait/fade seconds cannot be negative");
                    }
                    if (nightStartHour < 0.0 || nightStartHour > 24.0 || nightEndHour < 0.0 || nightEndHour > 24.0) {
                        throw new IllegalArgumentException("music night hours must be within 0..24");
                    }
                }
            }

            public record WorldRendering(
                    boolean staticMeshDistanceCullingEnabled,
                    int staticMeshCullingDistance,
                    float impostorDistanceMultiplier,
                    float grassDistanceMultiplier,
                    int viewDistance,
                    int nearPlane,
                    boolean bloomEnabled,
                    double displayGamma,
                    double textureMipBias,
                    TextureQuality textureQuality,
                    boolean dxtMipCodecEnabled,
                    int dxtMipMinSize,
                    Path dxtMipCachePath,
                    double timeOfDay,
                    double realSkySunSize,
                    double realSkyMoonSize,
                    double realSkyFlareBaseSize,
                    double realSkyFlareIntensity,
                    int oneOffMergeVertexBudget,
                    double terrainDecorationFadeDistanceMultiplier,
                    int waterEffectResolution,
                    boolean waterLinearFiltering,
                    WaterColorReference waterColorReference,
                    int fogRangePreset,
                    EmitterDistanceMode emitterDistanceMode,
                    MapEventState mapEvents,
                    boolean customLightingEnabled,
                    double customAuthoredShadowStrength,
                    ViewerGraphicsConfig graphics) {
                public WorldRendering withGraphics(ViewerGraphicsConfig newGraphics) {
                    return new WorldRendering(
                            staticMeshDistanceCullingEnabled,
                            staticMeshCullingDistance,
                            impostorDistanceMultiplier,
                            grassDistanceMultiplier,
                            viewDistance,
                            nearPlane,
                            bloomEnabled,
                            displayGamma,
                            textureMipBias,
                            textureQuality,
                            dxtMipCodecEnabled,
                            dxtMipMinSize,
                            dxtMipCachePath,
                            timeOfDay,
                            realSkySunSize,
                            realSkyMoonSize,
                            realSkyFlareBaseSize,
                            realSkyFlareIntensity,
                            oneOffMergeVertexBudget,
                            terrainDecorationFadeDistanceMultiplier,
                            waterEffectResolution,
                            waterLinearFiltering,
                            waterColorReference,
                            fogRangePreset,
                            emitterDistanceMode,
                            mapEvents,
                            customLightingEnabled,
                            customAuthoredShadowStrength,
                            newGraphics);
                }
            }

            public record WaterColorReference(
                    int red,
                    int green,
                    int blue,
                    int alpha,
                    int reflectionReference,
                    double bumpAmplitude,
                    int bumpMipCeiling) {}
        }
    }

    public record Capture(Viewer.World world, Output output) {
        public Capture {
            Objects.requireNonNull(world, "capture world");
            Objects.requireNonNull(output, "capture output");
            if (world.minXTile() > world.maxXTile() || world.minYTile() > world.maxYTile()) {
                throw new IllegalArgumentException("capture tile bounds are inverted");
            }
            output.validate(world);
        }

        public enum ImageFormat {
            JPEG("jpg"),
            PNG("png");

            private final String extension;

            ImageFormat(String extension) {
                this.extension = extension;
            }

            static ImageFormat parse(String value) {
                return switch (value.toLowerCase(Locale.ROOT)) {
                    case "jpg", "jpeg" -> JPEG;
                    case "png" -> PNG;
                    default ->
                        throw new IllegalArgumentException(
                                "capture output format must be jpg/jpeg or png, got " + value);
                };
            }

            public String getExtension() {
                return extension;
            }

            public Path getImagePath(Path file) {
                var name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    var suffix = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                    if (suffix.equals(extension) || this == JPEG && suffix.equals("jpeg")) {
                        return file;
                    }
                    name = name.substring(0, dot);
                }
                return file.resolveSibling(name + "." + extension);
            }
        }

        public record Output(
                Path directory,
                int tileResolution,
                ImageFormat format,
                int jpegQuality,
                boolean writeTiles,
                boolean openFolderOnComplete,
                Radar radar,
                List<CaptureView> views,
                boolean deleteOldImages) {
            public Output {
                Objects.requireNonNull(directory, "capture output directory");
                Objects.requireNonNull(format, "capture output format");
                Objects.requireNonNull(radar, "capture radar");
                views = List.copyOf(views);
                CaptureView.requireDistinct(views);
            }

            public Output(
                    Path directory,
                    int tileResolution,
                    ImageFormat format,
                    int jpegQuality,
                    boolean writeTiles,
                    boolean openFolderOnComplete,
                    Radar radar) {
                this(
                        directory,
                        tileResolution,
                        format,
                        jpegQuality,
                        writeTiles,
                        openFolderOnComplete,
                        radar,
                        List.of());
            }

            public Output(
                    Path directory,
                    int tileResolution,
                    ImageFormat format,
                    int jpegQuality,
                    boolean writeTiles,
                    boolean openFolderOnComplete,
                    Radar radar,
                    List<CaptureView> views) {
                this(
                        directory,
                        tileResolution,
                        format,
                        jpegQuality,
                        writeTiles,
                        openFolderOnComplete,
                        radar,
                        views,
                        false);
            }

            public Path tilePath(int mapX, int mapY) {
                return directory.resolve(mapX + "_" + mapY + "." + format.getExtension());
            }

            public Path viewPath(CaptureView view) {
                return view.main()
                        ? tilePath(view.mapX(), view.mapY())
                        : directory.resolve(view.tile() + "_" + view.name() + "." + format.getExtension());
            }

            public List<CaptureView> viewsOutside(Collection<String> tiles) {
                return views.stream()
                        .filter(view -> !tiles.contains(view.tile()))
                        .toList();
            }

            public List<CaptureView> shots(int mapX, int mapY) {
                var tile = mapX + "_" + mapY;
                var result = new ArrayList<CaptureView>();
                result.add(new CaptureView(tile, "", null, null, false, true));
                for (var view : views) {
                    if (view.tile().equals(tile)) {
                        if (view.main()) {
                            result.set(0, view);
                        } else {
                            result.add(view);
                        }
                    }
                }
                return List.copyOf(result);
            }

            public Path radarPath() {
                var file = radar.format().getImagePath(radar.file());
                return file.isAbsolute() ? file : directory.resolve(file);
            }

            private void validate(Viewer.World world) {
                if (tileResolution < 16 || tileResolution > 16_384) {
                    throw new IllegalArgumentException(
                            "capture tile_resolution must be within 16..16384, got " + tileResolution);
                }
                if (jpegQuality < 1 || jpegQuality > 100) {
                    throw new IllegalArgumentException(
                            "capture jpeg_quality must be within 1..100, got " + jpegQuality);
                }
                if (!writeTiles && !radar.enabled()) {
                    throw new IllegalArgumentException("capture must enable write_tiles, radar, or both");
                }
                if (!radar.enabled()) {
                    return;
                }
                if (radar.tileResolution() < 1 || radar.tileResolution() > tileResolution) {
                    throw new IllegalArgumentException(
                            "capture radar tile_resolution must be within 1..capture tile_resolution, got "
                                    + radar.tileResolution());
                }
                if (radar.file().getFileName() == null
                        || radar.file().toString().isBlank()) {
                    throw new IllegalArgumentException("Give the radar map a filename.");
                }
                for (var view : views) {
                    if (view.main() && view.resolution() != null && view.resolution() < radar.tileResolution()) {
                        throw new IllegalArgumentException("Capture view " + view.label()
                                + " resolution must be at least the radar tile_resolution " + radar.tileResolution());
                    }
                }
                CaptureRadarLayout.forBounds(
                                radar.tileResolution(),
                                world.minXTile(),
                                world.maxXTile(),
                                world.minYTile(),
                                world.maxYTile())
                        .checkFormat(radar.format());
            }
        }

        public record Radar(boolean enabled, Path file, int tileResolution, ImageFormat format, int jpegQuality) {
            public Radar {
                Objects.requireNonNull(file, "capture radar file");
                Objects.requireNonNull(format, "capture radar format");
                if (jpegQuality < 1 || jpegQuality > 100) {
                    throw new IllegalArgumentException("Radar JPEG quality must be within 1..100, got " + jpegQuality);
                }
            }
        }
    }

    static Config parseConfig(com.typesafe.config.Config conf) {
        return parseConfig(conf, Path.of(""));
    }

    static Config parseConfig(com.typesafe.config.Config conf, Path base) {
        var viewerConf = conf.getConfig("viewer");
        var editing = viewerConf.getConfig("editing");
        var recoveryDirectory = editing.getString("recovery_directory");
        var viewer = new Viewer(
                parseWorld(conf, viewerConf.getConfig("world"), base),
                parseViewerCamera(viewerConf.getConfig("camera")),
                new Viewer.Editing(
                        editing.getBoolean("recovery_enabled"),
                        editing.getLong("autosave_debounce_ms"),
                        editing.getInt("checkpoint_commands"),
                        editing.getInt("retained_generations"),
                        editing.getInt("maximum_megabytes"),
                        base.resolve(recoveryDirectory).toAbsolutePath().normalize(),
                        editing.getBoolean("move_actors_to_their_tile"),
                        editing.getBoolean("welcome_shown"),
                        editing.getString("teleport_command")),
                new Viewer.Director(
                        UserPaths.resolve(viewerConf.getConfig("director").getString("scenes_folder"), base)),
                new Viewer.Agent(viewerConf.getBoolean("agent.enabled"), viewerConf.getString("agent.access")));
        var captureConf = conf.getConfig("capture");
        var captureWorld = parseWorld(conf, captureConf.getConfig("world"), base);
        var capture = new Capture(
                captureWorld,
                parseCaptureOutput(
                        captureConf.getConfig("output"), base, captureWorld.clientPath(), captureWorld.variant()));
        var interfaceConf = conf.getConfig("interface");
        var screenshotFolder = interfaceConf.getString("screenshot_folder");
        var ui = new Interface(
                InterfaceScale.parse(interfaceConf.getString("scale")),
                interfaceConf.getBoolean("screenshot_interface"),
                screenshotFolder.isBlank() ? null : UserPaths.resolve(screenshotFolder, base),
                interfaceConf.getBoolean("animations"));
        return new Config(viewer, capture, ui, parseExports(conf.getConfig("exports"), base));
    }

    static Capture.Output parseCaptureOutput(com.typesafe.config.Config conf) {
        return parseCaptureOutput(conf, Path.of(""), Path.of(""));
    }

    static Capture.Output parseCaptureOutput(com.typesafe.config.Config conf, Path base, Path clientPath) {
        return parseCaptureOutput(conf, base, clientPath, WorldVariant.MAIN);
    }

    static Capture.Output parseCaptureOutput(
            com.typesafe.config.Config conf, Path base, Path clientPath, WorldVariant variant) {
        var radar = conf.getConfig("radar");
        var directory = UserPaths.resolve(conf.getString("directory"), base);
        if (conf.getBoolean("client_subfolder") && !clientPath.toString().isBlank()) {
            directory = directory.resolve(UserPaths.clientFolder(clientPath, variant));
        }
        return new Capture.Output(
                directory,
                conf.getInt("tile_resolution"),
                Capture.ImageFormat.parse(conf.getString("format")),
                conf.getInt("jpeg_quality"),
                conf.getBoolean("write_tiles"),
                conf.getBoolean("open_folder_on_complete"),
                new Capture.Radar(
                        radar.getBoolean("enabled"),
                        Paths.get(radar.getString("file")),
                        radar.getInt("tile_resolution"),
                        Capture.ImageFormat.parse(radar.getString("format")),
                        radar.getInt("jpeg_quality")),
                conf.getConfigList("views").stream().map(CaptureView::parse).toList(),
                conf.getBoolean("delete_old_images"));
    }

    static Viewer.Camera parseViewerCamera(com.typesafe.config.Config conf) {
        var locations = new ArrayList<Viewer.Camera.Location>();
        var names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (var locationConf : conf.getConfigList("locations")) {
            var location = parseCameraLocation(locationConf);
            if (!names.add(location.name())) {
                throw new IllegalArgumentException("Duplicate viewer camera location name: '" + location.name() + "'");
            }
            locations.add(location);
        }
        var startPitch = conf.getDouble("start_pitch");
        if (startPitch < -Camera.MAX_PITCH_DEGREES || startPitch > Camera.MAX_PITCH_DEGREES) {
            throw new IllegalArgumentException("viewer camera start_pitch must be within +-" + Camera.MAX_PITCH_DEGREES
                    + " degrees (a look straight up or down has no unique orientation), got " + startPitch);
        }
        var mouseSensitivity = conf.hasPath("mouse_sensitivity") ? conf.getDouble("mouse_sensitivity") : 1;
        if (!(mouseSensitivity >= Viewer.Camera.MIN_MOUSE_SENSITIVITY
                && mouseSensitivity <= Viewer.Camera.MAX_MOUSE_SENSITIVITY)) {
            throw new IllegalArgumentException("viewer camera mouse_sensitivity must be between "
                    + Viewer.Camera.MIN_MOUSE_SENSITIVITY + " and " + Viewer.Camera.MAX_MOUSE_SENSITIVITY + ", got "
                    + mouseSensitivity);
        }
        return new Viewer.Camera(
                parseCameraStart(conf.getString("start"), locations),
                conf.getDouble("start_yaw"),
                startPitch,
                List.copyOf(locations),
                mouseSensitivity);
    }

    public Path screenshotFolder() {
        return screenshotFolder(ui.screenshotFolder(), capture.output().directory());
    }

    private static Exports parseExports(com.typesafe.config.Config conf, Path base) {
        var folder = conf.getString("folder");
        var scripts = conf.getString("scripts_folder");
        var centimeters = (float) conf.getDouble("scale_cm");
        if (!(centimeters >= ExportScale.MIN && centimeters <= ExportScale.MAX)) {
            throw new IllegalArgumentException("exports.scale_cm must be within "
                    + ExportScale.MIN
                    + ".."
                    + ExportScale.MAX
                    + " (centimetres), got "
                    + conf.getDouble("scale_cm"));
        }
        var size = conf.getString("video_size").split("x");
        var rate = conf.getInt("video_rate");
        if (size.length != 2
                || !size[0].matches("\\d{2,5}")
                || !size[1].matches("\\d{2,5}")
                || Integer.parseInt(size[0]) % 2 != 0
                || Integer.parseInt(size[1]) % 2 != 0) {
            throw new IllegalArgumentException("exports.video_size must be two even numbers, such as 1920x1080, got "
                    + conf.getString("video_size"));
        }
        if (rate < 1 || rate > 240) {
            throw new IllegalArgumentException("exports.video_rate must be within 1..240, got " + rate);
        }
        return new Exports(
                folder.isBlank() ? null : UserPaths.resolve(folder, base),
                new ExportScale(centimeters),
                SceneFormat.parse(conf.getString("scene_format")),
                Integer.parseInt(size[0]),
                Integer.parseInt(size[1]),
                rate,
                conf.getBoolean("install_after_write"),
                scripts.isBlank() ? null : UserPaths.resolve(scripts, base));
    }

    private static Viewer.World parseWorld(
            com.typesafe.config.Config root, com.typesafe.config.Config conf, Path base) {
        var client = Paths.get(conf.getString("client_path"));
        var mapEvents = ClientProfiles.mapEvents(root, client, base);
        return new Viewer.World(
                client,
                conf.hasPath("min_x_tile") ? conf.getInt("min_x_tile") : WorldDimensions.WORLD_X_MIN,
                conf.hasPath("max_x_tile") ? conf.getInt("max_x_tile") : WorldDimensions.WORLD_X_MAX,
                conf.hasPath("min_y_tile") ? conf.getInt("min_y_tile") : WorldDimensions.WORLD_Y_MIN,
                conf.hasPath("max_y_tile") ? conf.getInt("max_y_tile") : WorldDimensions.WORLD_Y_MAX,
                conf.getInt("parser_workers"),
                parseWorldRendering(conf.getConfig("rendering"), base, mapEvents),
                parseMusic(conf.getConfig("music")),
                parseSound(conf.getConfig("sound")),
                parseGeodata(conf.getConfig("geodata"), base),
                parseSpawns(conf, base),
                conf.hasPath("variant") ? WorldVariant.parse(conf.getString("variant")) : WorldVariant.MAIN);
    }

    private static Viewer.World.Spawns parseSpawns(com.typesafe.config.Config world, Path base) {
        if (!world.hasPath("spawns")) {
            return Viewer.World.Spawns.NONE;
        }
        var conf = world.getConfig("spawns");
        var path = conf.hasPath("script_path") ? conf.getString("script_path") : "";
        return new Viewer.World.Spawns(
                path.isBlank() ? null : UserPaths.resolve(path, base),
                !conf.hasPath("enabled") || conf.getBoolean("enabled"));
    }

    private static Viewer.World.Geodata parseGeodata(com.typesafe.config.Config conf, Path base) {
        var path = conf.getString("path");
        return new Viewer.World.Geodata(
                path.isBlank() ? null : UserPaths.resolve(path, base),
                conf.getBoolean("enabled"),
                conf.getInt("radius"),
                conf.getBoolean("through_geometry"),
                new GeoDataDisplay(
                        Mode.valueOf(conf.getString("display.mode").toUpperCase(Locale.ROOT)),
                        (float) conf.getDouble("display.opacity"),
                        (float) conf.getDouble("display.line_width_pixels"),
                        conf.getBoolean("display.local_grid"),
                        conf.getInt("display.local_grid_radius"),
                        conf.getBoolean("display.height_filter"),
                        conf.getInt("display.min_height"),
                        conf.getInt("display.max_height"),
                        conf.getBoolean("display.dim_other_heights"),
                        null),
                GeoDataFocusMode.valueOf(conf.getString("focus_mode").toUpperCase(Locale.ROOT)),
                conf.getInt("vertex_limit"));
    }

    private static Viewer.World.Sound parseSound(com.typesafe.config.Config conf) {
        return new Viewer.World.Sound(
                conf.getBoolean("enabled"),
                conf.getDouble("volume"),
                conf.getInt("max_voices"),
                conf.getDouble("max_radius_multiplier"));
    }

    private static Viewer.World.Music parseMusic(com.typesafe.config.Config conf) {
        return new Viewer.World.Music(
                conf.getBoolean("enabled"),
                conf.getDouble("volume"),
                conf.getDouble("wait_seconds"),
                conf.getDouble("idle_wait_seconds"),
                conf.getDouble("fade_seconds"),
                conf.getDouble("night_start_hour"),
                conf.getDouble("night_end_hour"));
    }

    private static Viewer.Camera.Location parseCameraLocation(com.typesafe.config.Config conf) {
        var name = conf.getString("name");
        var position = conf.getDoubleList("position");
        if (position.size() != 3) {
            throw new IllegalArgumentException("viewer camera location '" + name
                    + "' needs a position of exactly 3 numbers [x, y, z], got " + position);
        }
        return new Viewer.Camera.Location(
                name,
                position.get(0).floatValue(),
                position.get(1).floatValue(),
                position.get(2).floatValue());
    }

    private static Viewer.Camera.Location parseCameraStart(String start, List<Viewer.Camera.Location> locations) {
        for (var location : locations) {
            if (location.name().equalsIgnoreCase(start)) {
                return location;
            }
        }
        var parts = start.trim().split("[\\s,]+");
        if (parts.length == 3) {
            try {
                return new Viewer.Camera.Location(
                        start, Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
            } catch (NumberFormatException e) {
            }
        }
        var known = new StringBuilder();
        for (var location : locations) {
            known.append(known.isEmpty() ? "" : ", ")
                    .append('\'')
                    .append(location.name())
                    .append('\'');
        }
        throw new IllegalArgumentException("viewer camera start '" + start
                + "' is neither 'x, y, z' nor one of the configured locations: " + known);
    }

    private static Viewer.World.WaterColorReference parseWaterColorReference(com.typesafe.config.Config conf) {
        var red = conf.getInt("r");
        var green = conf.getInt("g");
        var blue = conf.getInt("b");
        var alpha = conf.getInt("a");
        var reflectionReference = conf.getInt("reflection_reference");
        var bumpAmplitude = conf.getDouble("bump_amplitude");
        var bumpMipCeiling = conf.getInt("bump_mip_ceiling");
        if (bumpAmplitude < 0 || bumpAmplitude > 1) {
            throw new IllegalArgumentException("water bump_amplitude must be 0..1, got " + bumpAmplitude);
        }
        if (bumpMipCeiling < 0 || bumpMipCeiling > 8) {
            throw new IllegalArgumentException("water bump_mip_ceiling must be 0..8, got " + bumpMipCeiling);
        }
        for (var channel : new int[] {red, green, blue, alpha, reflectionReference}) {
            if (channel < 0 || channel > 255) {
                throw new IllegalArgumentException("water_color_reference components must be 0..255, got " + channel);
            }
        }
        return new Viewer.World.WaterColorReference(
                red, green, blue, alpha, reflectionReference, bumpAmplitude, bumpMipCeiling);
    }

    private static Viewer.World.WorldRendering parseWorldRendering(
            com.typesafe.config.Config conf, Path base, MapEventState mapEvents) {
        var oneOffMergeVertexBudget = conf.getInt("one_off_merge_vertex_budget");
        if (oneOffMergeVertexBudget < 1) {
            throw new IllegalArgumentException(
                    "one_off_merge_vertex_budget must be positive, got " + oneOffMergeVertexBudget);
        }
        var terrainDecorationFadeDistanceMultiplier = conf.getDouble("terrain_decoration_fade_distance_multiplier");
        if (!Double.isFinite(terrainDecorationFadeDistanceMultiplier)
                || terrainDecorationFadeDistanceMultiplier <= 0.0) {
            throw new IllegalArgumentException(
                    "terrain_decoration_fade_distance_multiplier must be finite and positive, got "
                            + terrainDecorationFadeDistanceMultiplier);
        }
        var waterColorReference = parseWaterColorReference(conf.getConfig("water_color_reference"));
        var waterEffectResolution = conf.getInt("water_effect_resolution");
        if (waterEffectResolution < 1 || waterEffectResolution > 16_384) {
            throw new IllegalArgumentException(
                    "water_effect_resolution must be within 1..16384, got " + waterEffectResolution);
        }
        var fogRangePreset = conf.getInt("fog_range_preset");
        if (fogRangePreset < 0 || fogRangePreset > 5) {
            throw new IllegalArgumentException("fog_range_preset must be 0..5, got " + fogRangePreset);
        }
        var displayGamma = conf.getDouble("display_gamma");
        if (!Double.isFinite(displayGamma) || displayGamma < 0.5 || displayGamma > 2.0) {
            throw new IllegalArgumentException("display_gamma must be finite and within 0.5..2.0, got " + displayGamma);
        }
        var textureMipBias = conf.getDouble("texture_mip_bias");
        if (!Double.isFinite(textureMipBias) || textureMipBias < -16.0 || textureMipBias > 15.99) {
            throw new IllegalArgumentException(
                    "texture_mip_bias must be finite and within -16..15.99, got " + textureMipBias);
        }
        var impostorDistanceMultiplier = conf.getDouble("impostor_distance_multiplier");
        if (!Double.isFinite(impostorDistanceMultiplier)
                || impostorDistanceMultiplier < 1
                || impostorDistanceMultiplier > 20) {
            throw new IllegalArgumentException("impostor_distance_multiplier must be within 1..20");
        }
        var grassDistanceMultiplier = conf.getDouble("grass_distance_multiplier");
        if (!Double.isFinite(grassDistanceMultiplier) || grassDistanceMultiplier < 1 || grassDistanceMultiplier > 20) {
            throw new IllegalArgumentException("grass_distance_multiplier must be within 1..20");
        }
        var dxtMipCachePath = conf.getString("dxt_mip_cache_path");
        var customAuthoredShadowStrength = conf.getDouble("custom_authored_shadow_strength");
        if (!Double.isFinite(customAuthoredShadowStrength)
                || customAuthoredShadowStrength < 0
                || customAuthoredShadowStrength > 100) {
            throw new IllegalArgumentException("custom_authored_shadow_strength must be finite and within 0..100, got "
                    + customAuthoredShadowStrength);
        }
        return new Viewer.World.WorldRendering(
                conf.getBoolean("static_mesh_distance_culling_enabled"),
                conf.getInt("static_mesh_culling_distance"),
                (float) impostorDistanceMultiplier,
                (float) grassDistanceMultiplier,
                conf.getInt("view_distance"),
                conf.getInt("near_plane"),
                conf.getBoolean("bloom_enabled"),
                displayGamma,
                textureMipBias,
                new TextureQuality(
                        conf.hasPath("texture_detail") ? conf.getInt("texture_detail") : 0,
                        conf.hasPath("effect_texture_detail") ? conf.getInt("effect_texture_detail") : 0,
                        false,
                        false),
                conf.getBoolean("dxt_mip_codec_enabled"),
                conf.getInt("dxt_mip_min_size"),
                dxtMipCachePath.isBlank() ? null : UserPaths.resolve(dxtMipCachePath, base),
                conf.getDouble("time_of_day"),
                conf.getDouble("real_sky_sun_size"),
                conf.getDouble("real_sky_moon_size"),
                conf.getDouble("real_sky_flare_base_size"),
                conf.getDouble("real_sky_flare_intensity"),
                oneOffMergeVertexBudget,
                terrainDecorationFadeDistanceMultiplier,
                waterEffectResolution,
                conf.getBoolean("water_linear_filtering"),
                waterColorReference,
                fogRangePreset,
                EmitterDistanceMode.parse(conf.getString("emitter_distance_mode")),
                mapEvents,
                conf.getBoolean("custom_lighting_enabled"),
                customAuthoredShadowStrength,
                parseViewerGraphics(conf));
    }

    private static Tuning parseGraphicsTuning(com.typesafe.config.Config conf) {
        return new Tuning(
                conf.getInt("terrain_proxy_grid_step"),
                conf.getInt("actor_lighting_words"),
                conf.getInt("material_page_target_layers"),
                conf.getInt("ordinary_diffuse_page_group_size"),
                conf.getInt("command_hierarchy_leaf_commands"),
                conf.getDouble("fx_min_projected_pixels"),
                conf.getDouble("fx_simulation_lod_projected_pixels"),
                conf.getInt("fx_actor_transform_capacity"),
                conf.getInt("visibility_max_candidates"),
                conf.getInt("visibility_max_commands"),
                conf.getDouble("visibility_min_projected_pixels"),
                conf.getDouble("visibility_terrain_detail_pixels"),
                conf.getDouble("gpu_group_sort_cell_size"));
    }

    private static ViewerGraphicsConfig parseViewerGraphics(com.typesafe.config.Config renderingConf) {
        if (!renderingConf.hasPath("graphics")) {
            return ViewerGraphicsConfig.defaults();
        }
        var conf = renderingConf.getConfig("graphics");
        var upscalerConf = conf.getConfig("upscaler");
        var dynamicConf = upscalerConf.getConfig("dynamic_resolution");
        var optimizationsConf = conf.getConfig("optimizations");
        var graphics = new ViewerGraphicsConfig(
                Renderer.parse(conf.getString("renderer")),
                conf.getBoolean("validation"),
                conf.getBoolean("profiler"),
                conf.getBoolean("vsync"),
                conf.getInt("frame_rate_cap"),
                conf.getInt("msaa_samples"),
                new Upscaler(
                        UpscalerAlgorithm.parse(upscalerConf.getString("algorithm")),
                        UpscalerPreset.parse(upscalerConf.getString("preset")),
                        upscalerConf.getDouble("custom_scale"),
                        upscalerConf.getBoolean("rcas_enabled"),
                        upscalerConf.getDouble("sharpness"),
                        new DynamicResolution(
                                dynamicConf.getBoolean("enabled"),
                                dynamicConf.getDouble("target_frame_ms"),
                                dynamicConf.getDouble("min_scale"),
                                dynamicConf.getDouble("max_scale"),
                                dynamicConf.getInt("cooldown_frames"))),
                new Optimizations(
                        OptimizationProfile.parse(optimizationsConf.getString("profile")),
                        optimizationsConf.getBoolean("fill_reflection"),
                        optimizationsConf.getBoolean("gpu_visibility"),
                        optimizationsConf.getBoolean("indirect_submission"),
                        optimizationsConf.getBoolean("parallel_encoders")),
                parseGraphicsTuning(conf.getConfig("tuning")));
        var benchmarkOutput =
                System.getProperty("cruma.viewer.benchmark.output", "").trim();
        if (benchmarkOutput.isEmpty()) {
            return graphics;
        }
        return graphics.forBenchmarkCheckpoint(System.getProperty("cruma.viewer.benchmark.checkpoint", ""));
    }
}

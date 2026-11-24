package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.ConfigMigrations;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.OptionsModel;
import com.rost.cruma.viewer.config.OptionsModel.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class LaunchOptionCatalog {
    public static final String CLIENT = "This client";

    public static final String TABLES = "Client tables";

    public static final String GRAPHICS = "Graphics";
    public static final String INTERFACE = "Interface";
    public static final String AUDIO = "Audio";
    public static final String PLACES = "Places";
    public static final String TOOLS = "Tools";
    public static final String FOLDERS = "Folders and export";
    public static final String CAPTURE_RENDERING = "Capture rendering";

    public static final String AI_AGENT = "AI agent";

    public static final String ADVANCED = "Advanced";

    public static final List<String> PAGES =
            List.of(GRAPHICS, INTERFACE, AUDIO, PLACES, TOOLS, FOLDERS, CAPTURE_RENDERING, AI_AGENT, ADVANCED);

    public static final String CAPTURE_DIALOG = "Capture dialog";

    public static final String SHARED_PREFIX = "interface.";

    public static final String EXPORT_PREFIX = "exports.";

    private static final Set<String> CAPTURE_FIXED_SETTINGS = Set.of(
            "rendering.graphics.vsync",
            "rendering.graphics.frame_rate_cap",
            "rendering.graphics.msaa_samples",
            "rendering.graphics.profiler",
            "rendering.graphics.optimizations.profile",
            "rendering.graphics.optimizations.gpu_visibility",
            "rendering.graphics.optimizations.indirect_submission",
            "rendering.graphics.tuning.terrain_proxy_grid_step",
            "rendering.graphics.tuning.command_hierarchy_leaf_commands",
            "rendering.graphics.tuning.visibility_min_projected_pixels",
            "rendering.graphics.tuning.visibility_terrain_detail_pixels",
            "rendering.graphics.tuning.visibility_max_candidates",
            "rendering.graphics.tuning.visibility_max_commands",
            "rendering.graphics.tuning.fx_min_projected_pixels",
            "rendering.graphics.tuning.fx_simulation_lod_projected_pixels",
            "rendering.static_mesh_distance_culling_enabled",
            "rendering.static_mesh_culling_distance",
            "rendering.impostor_distance_multiplier",
            "rendering.grass_distance_multiplier",
            "rendering.terrain_decoration_fade_distance_multiplier",
            "rendering.emitter_distance_mode",
            "rendering.view_distance",
            "rendering.near_plane",
            "rendering.one_off_merge_vertex_budget",
            "rendering.bloom_enabled",
            "rendering.display_gamma",
            "rendering.fog_range_preset",
            "rendering.real_sky_sun_size",
            "rendering.real_sky_moon_size",
            "rendering.real_sky_flare_base_size",
            "rendering.real_sky_flare_intensity",
            "rendering.dxt_mip_cache_path");

    private static final Set<String> MIRRORS = Set.of(
            ClientProfiles.CAPTURE_VARIANT, ClientProfiles.CAPTURE_TIME_WINDOWS, ClientProfiles.CAPTURE_CASTLE_SIDES);

    private static final List<String> SEGMENTED = List.of(
            ".geodata.focus_mode",
            ".rendering.graphics.upscaler.algorithm",
            ".rendering.graphics.optimizations.profile",
            ".rendering.emitter_distance_mode",
            ".rendering.time_windows",
            ".rendering.castle_sides");

    private static final Pattern TILE_BOUND = Pattern.compile("(?:viewer|capture)\\.world\\.(?:min|max)_[xy]_tile");

    private static final Pattern FOLDER = Pattern.compile(".*(?:path|folder|directory|\\.file)");

    private static final String LOCATIONS = "viewer.camera.locations";

    private static final Map<String, Presentation> ITEMS = new LinkedHashMap<>();

    private static final Comparator<Field> ORDER = Comparator.comparingInt((Field field) -> rankOf(field.path()))
            .thenComparingInt(field -> presentation(field.path()).order());

    static {
        addGroup(CLIENT, "World", "", """
            variant|World|Main reads the maps X_Y.unr. Classic reads X_Y_Classic.unr where the client has it and X_Y.unr for the other tiles, with the _Classic tables. Only clients with Classic maps offer it. Essence reads the Classic maps with the _ClassicAden tables, the Classic ones where it has none, and the main ones after them. Only clients with _ClassicAden tables offer it. Each client keeps its own choice, its own geodata and script folders for each world, and the images of the Classic maps go into their own folder.
            """);
        addGroup(CLIENT, "Map events", "rendering.", """
            time_windows|Effects by time of day|Effects that show only in some hours of the game day, such as the High Five blood rain at 02:00 and 04:00. By clock shows them in their hours of Time of day, as the game does, Always at every hour, Off never. The sounds of those hours follow it too. It also clears the choices made for single hours in Map events. Set for the selected client, with its map events: the Viewer's Map events tool changes it too, and Capture and Export location follow it.
            castle_sides|Castle sides|The light and dark effects of the castle towns, in P152 and later clients: older clients have none, so this changes nothing there. Both shows them all, Light and Dark only that side's effects, None hides them. It sets every castle at once. Mixed means that the Map events tool gave the castles different sides. Set for the selected client, with its map events: the Viewer's Map events tool changes it too, and Capture and Export location follow it.
            """);
        addGroup(CLIENT, "Server data", "geodata.", """
            path|Geodata folder|Folder for the selected game client, containing L2OFF tile files named like 22_22_conv.dat or L2J files named like 22_22.l2j. Other clients keep their own folders. Relative paths count from the configuration folder.
            """);
        addGroup(CLIENT, "Server data", "spawns.", """
            script_path|Server scripts folder|L2OFF server script folder with npcpos.txt and npcdata.txt, or a folder that contains a script subfolder. The NPC Spawns panel shows, previews and edits its spawns, and Scenes plays the boss scenes of its AI.
            """);
        addGroup(ADVANCED, "Default area", "", """
            min_x_tile|Minimum tile X|Left bound of the configured map area, inclusive. Only installed numeric map tiles are loaded. The start menu chooses the tiles on its map instead.
            max_x_tile|Maximum tile X|Right bound of the configured map area, inclusive. Missing tiles are skipped. The start menu chooses the tiles on its map instead.
            min_y_tile|Minimum tile Y|Lower Y bound of the configured map area, inclusive. Use a small area for quicker loading and lower memory use.
            max_y_tile|Maximum tile Y|Upper Y bound of the configured map area, inclusive. These bounds apply when Cruma starts without the start menu and no tiles are given.
            """);
        addGroup(TOOLS, "Geodata display", "geodata.", """
            enabled|Show geodata|Display server cells around the selected focus ray. Can also be changed live in the Viewer's Geodata panel or with G.
            focus_mode|Follow|camera follows the center of the viewport; mouse follows the pointer over the scene and holds its last position over settings. These modes never switch automatically.
            vertex_limit|Display capacity (vertices)|250000 to 4000000 triangle corners. A radius of 4096 needs about two million in towns. One million in use takes about 21 MB of GPU memory. Increase for dense layers, or reduce Visible radius / isolate a height. Source cells and navigation are unaffected.
            radius|Visible radius (units)|Horizontal distance around the selected focus ray from 128 to 4096 world units. Larger areas load and draw more cells. All stored height layers are shown.
            through_geometry|Show through geometry|Draw hidden layers faintly through terrain and buildings. Visible layers remain bright to preserve depth cues.
            display.mode|Geodata display mode|exact_grid shows every cell, contours keeps obstacles and height changes, surface adds merged flat patches. Queries always use the original cells.
            display.opacity|Geodata opacity|Opacity from 0 to 1 for geodata lines and filled surfaces. Lower values make the client geometry easier to see underneath.
            display.line_width_pixels|Line width (pixels)|Screen-space boundary width from 0.5 to 8 pixels. Wider lines make blocked edges easier to inspect at a distance.
            display.local_grid|Detail grid|Show individual 16-unit cells around the selected focus ray in contours or surface mode. The exact-grid mode already shows every cell.
            display.local_grid_radius|Detail radius|Radius around the selected focus ray from 16 to 1024 world units. One cell is 16 units. Controls how much exact cell detail remains visible in the simplified modes.
            display.height_filter|Filter height range|Limit displayed surfaces by height without changing navigation.
            display.min_height|Minimum geodata height|Lower inclusive world Z from -32768 to 32767. Surfaces below it are hidden or dimmed while the height filter is enabled.
            display.max_height|Maximum geodata height|Upper inclusive world Z from -32768 to 32767. Surfaces above it are hidden or dimmed while the height filter is enabled.
            display.dim_other_heights|Dim other heights|Keep surfaces outside the selected height range faintly visible instead of hiding them.
            """);
        addGroup(TOOLS, "NPC spawns", "spawns.", """
            enabled|Show spawns|Draw spawn zones and fixed NPC positions when a script folder is open. Can also be changed live in the NPC spawns panel or with N.
            """);
        addGroup(PLACES, "Starting camera", "camera.", """
            start|Location or coordinates|Starting position for the free camera. Enter a saved place name or three world coordinates: X, Y, Z.
            start_yaw|Yaw (degrees)|Initial horizontal camera direction in degrees. This changes the viewing direction without moving the starting position.
            start_pitch|Pitch (degrees)|Initial vertical camera angle in degrees. This changes how far the camera looks up or down.
            """);
        addGroup(PLACES, "Named locations", "camera.", """
            locations|Named locations|Saved destinations shared by the starting camera and the world map. Each entry contains a name and X, Y, Z world coordinates.
            """);
        addGroup(GRAPHICS, "Display", "rendering.graphics.", """
            renderer|Graphics backend|Rendering API used when opening the world or starting an export. Automatic lets bgfx select the backend. The list contains APIs supported by this platform and native build with packaged Cruma shaders. A working graphics driver is still required.
            vsync|Vertical sync|Synchronize presentation to the display to avoid tearing, potentially adding latency or limiting FPS. Vulkan uses synchronized presentation regardless of this switch.
            frame_rate_cap|Frame limit (FPS)|Maximum interactive frame rate, from 1 to 1000 FPS. Lower limits reduce CPU/GPU use. Vertical sync may impose a lower limit. Can also be changed live by clicking the FPS in the Viewer's top bar.
            msaa_samples|UI antialiasing (samples)|Multisample antialiasing for the backbuffer UI and compositing, not world geometry. Choose 0, 2 or 4. Unsupported sample counts fall back. Keep 0 for Vulkan.
            """);
        addGroup(GRAPHICS, "Resolution", "rendering.graphics.upscaler.", """
            algorithm|Upscaling|Native draws at full resolution. AMD FSR 1 reconstructs a lower-resolution scene to reduce GPU work. Bilinear is a simpler, softer alternative. UI remains at display resolution.
            preset|Quality preset|Resolution of the world before upscaling. Quality renders at about 67% of each display dimension. Performance uses 50%. Lower scales improve speed but lose detail.
            custom_scale|Custom render scale|Fraction of display width and height used to render the world with the Custom preset. 1 is full resolution. 0.5 renders one quarter as many pixels.
            rcas_enabled|Sharpen image|Apply the RCAS sharpening pass after FSR 1 reconstruction to recover edge contrast. Extra sharpening can emphasize texture noise.
            sharpness|Sharpness|Sharpening strength from 0 (softest) to 1 (strongest). Used only when FSR 1 sharpening is enabled.
            """);
        addGroup(GRAPHICS, "Dynamic resolution", "rendering.graphics.upscaler.dynamic_resolution.", """
            enabled|Dynamic resolution|Adjust render resolution while moving to approach a target frame time. This can stabilize performance but causes visible changes in scene sharpness.
            target_frame_ms|Target frame time (ms)|Frame-time budget for automatic resolution changes. 16.67 ms corresponds to 60 FPS. 8.33 ms to 120 FPS. Resolution cannot fix a CPU bottleneck.
            min_scale|Minimum render scale|Lowest permitted world render scale during automatic adjustment. Smaller values allow more speed at the cost of fine detail.
            max_scale|Maximum render scale|Highest permitted world render scale during automatic adjustment, also capped by the selected quality preset. 1 permits full resolution when the preset allows it.
            cooldown_frames|Adjustment interval (frames)|Minimum number of frames between automatic resolution adjustments. Longer intervals reduce frequent changes in sharpness.
            """);
        addGroup(GRAPHICS, "Visibility", "rendering.", """
            view_distance|View distance (units)|Perspective far clipping distance in world units. Increasing it shows more of the loaded world and increases rendering work. It does not load additional map tiles.
            static_mesh_distance_culling_enabled|Limit object distance|Hide static objects beyond the object-distance limit without replacement geometry. Off preserves distant buildings and props. Enable only if that loss of scenery is acceptable.
            static_mesh_culling_distance|Object distance (units)|Distance from the camera beyond which static meshes disappear when Limit object distance is enabled. Measured in world units.
            impostor_distance_multiplier|Impostor distance multiplier|Keep detailed models visible farther away before they switch to their far versions: simpler meshes and flat cards. 1 uses client distances. 2 doubles the transition distance. Higher values increase rendering work. Range: 1 to 20. Sets the initial value for Scene settings, where it can also be adjusted live.
            grass_distance_multiplier|Grass distance multiplier|Keep terrain grass visible farther away by lowering its screen-size cutoff. 1 uses the standard cutoff, 2 doubles the approximate cutoff distance. Range: 1 to 20. Adjust live in Scene settings with GPU visibility enabled.
            terrain_decoration_fade_distance_multiplier|Vegetation distance multiplier|Multiply the vegetation fade distances stored in each map. 1 is the game's distance. Higher values keep plants visible farther away and increase rendering work. Plant size and density are unchanged.
            emitter_distance_mode|Effect distance policy|From client data uses each effect actor's visibility limit and fade range. Custom uses the viewer particle-distance control instead.
            """);
        addGroup(GRAPHICS, "Image quality", "rendering.", """
            texture_detail|Texture starting mip|First uploaded mip level for ordinary textures: 0 keeps full detail, 1 or 2 skips the largest levels to save texture memory. Takes effect when the world loads.
            effect_texture_detail|Effect starting mip|First uploaded mip level for the LineageEffectsTextures texture packages: 0 keeps full detail, 1 or 2 saves memory by skipping the largest levels.
            texture_mip_bias|Texture mip bias|Bias texture sampling toward finer levels with negative values or softer levels with positive values. Finer levels may shimmer at a distance. 0 leaves level selection unbiased.
            display_gamma|Display gamma|Final display gamma. 1 leaves the image unchanged and bypasses the gamma pass. Adjust for your display without changing the scene lighting.
            bloom_enabled|Bloom|Add a blurred glow around bright pixels. Changes the image and adds post-processing work, so it is optional rather than a performance optimization.
            """);
        addGroup(GRAPHICS, "Lighting", "rendering.", """
            custom_lighting_enabled|Custom lighting (Authored Hybrid)|Keep the client-authored material colours and add sunlight shadows. Capture waits for complete shadows before exporting each tile. Realistic lighting and GI remain live Scene settings models.
            custom_authored_shadow_strength|Shadow strength (%)|Maximum extra darkening from Authored Hybrid sunlight shadows, from 0 to 100. The default 45 preserves at least 55 percent of the authored colour in shadow. Used only when Custom lighting is enabled.
            """);
        addGroup(GRAPHICS, "Water", "rendering.", """
            water_effect_resolution|Reflection resolution (px)|Width and height of square water reflection targets in pixels, independent of the viewport. Higher resolution sharpens reflections but consumes more GPU time and memory.
            water_linear_filtering|Smooth water filtering|Smooth samples from water colour targets and ripple textures. Off uses point sampling unless the material specifies its own filter. Packed depth remains point-filtered.
            """);
        addGroup(GRAPHICS, "Sky and atmosphere", "rendering.", """
            time_of_day|Time of day (hour)|Starting scene hour from 0 to 24. Controls environment lighting and sky colours. It can also be changed while exploring.
            fog_range_preset|Fog range preset|Outdoor fog range: 0 follows the viewer draw distance. 1 through 5 use the environment's fixed presets, adjusted for camera altitude.
            real_sky_sun_size|Sun size|Size of the sun sprite in the sky. Adjusts its appearance without changing its orbit or lighting intensity.
            real_sky_moon_size|Moon size|Size of the moon sprite in the sky. Adjusts its appearance without changing its orbit.
            real_sky_flare_base_size|Lens flare size|Base size of the sun lens-flare sprites. Changes their screen appearance, not the brightness of the world.
            real_sky_flare_intensity|Lens flare intensity|Brightness multiplier for the sun lens flare. Set to 0 to remove the flare without hiding the sun.
            """);
        addGroup(INTERFACE, "Display", SHARED_PREFIX, """
            scale|Interface scale|Size of text and controls in the start menu, Viewer and Capture windows. Automatic follows the display scale set in your operating system. Choose a larger value if text is hard to read. The start menu shows the change at once, the Viewer when its world loads again.
            animations|Animations|Panels, menus and dialogs fade and slide as they open, and buttons, tabs and switches glide from one state to the next. Turn this off to show every change at once.
            """);
        addGroup(INTERFACE, "Mouse", "camera.", """
            mouse_sensitivity|Mouse sensitivity|How fast the view turns when you move the mouse with the right button held, from 0.1 to 5. 1 is the usual speed. 0.5 turns half as fast and 2 twice as fast. The keys and the camera speed stay as they are.
            """);
        addGroup(INTERFACE, "Screenshots", SHARED_PREFIX, """
            screenshot_interface|Interface in screenshots|F12 saves the window as you see it, panels included. Turn this off to save only the world. Shift+F12 always takes the other kind.
            screenshot_folder|Screenshot folder|Folder for F12 and Shift+F12 screenshots, a new file for each one. Leave it empty to use the Screenshots folder inside the folder of Capture's images for the client. ~ is your home folder, and a relative path counts from the folder with cruma.conf. The Viewer takes the change when it starts.
            """);
        addGroup(AUDIO, "Music", "music.", """
            enabled|Play music|Play location-based background music from the selected game installation while exploring.
            volume|Volume (0–1)|Background music gain from 0 (silent) to 1 (full volume). Independent of ambient sound volume.
            fade_seconds|Fade duration (s)|Duration in seconds of a music transition when the active track changes. Longer values make transitions more gradual.
            wait_seconds|Wait between tracks (s)|Delay before replaying a non-looping music track. Tracks marked as looping restart immediately.
            idle_wait_seconds|Idle retry interval (s)|Delay before checking for music again when there is no track to play. This controls retry frequency, not animation speed.
            night_start_hour|Night starts (hour)|Scene hour at which night-specific music and ambient sounds become eligible. Uses the scene clock, not your computer clock.
            night_end_hour|Night ends (hour)|Scene hour at which night-specific music and ambient sounds stop being eligible. The interval may cross midnight.
            """);
        addGroup(AUDIO, "Ambient sound", "sound.", """
            enabled|Play ambient sound|Play nearby ambient sounds, such as wind and water, from the selected game installation while exploring.
            volume|Volume (0–1)|Ambient sound gain from 0 (silent) to 1 (full volume). Independent of background music volume.
            max_voices|Maximum voices|Maximum simultaneous ambient sound sources. Higher limits allow more overlapping sounds but use more mixing time.
            max_radius_multiplier|Audible radius multiplier|Farthest audible distance as a multiple of each sound's stored radius. Sounds become quieter outside that radius. Larger values let more distant sources participate.
            """);
        addGroup(CAPTURE_DIALOG, "Tile images", "output.", """
            directory|Output folder|Destination folder for exported tile images and relative radar map paths. ~ is your home folder, and a relative path counts from the folder with cruma.conf. Existing files with matching names are replaced by an export.
            client_subfolder|Folder per client|Save the images of each client in its own subfolder of the output folder, named after the client folder with a short code that tells clients with the same folder name apart.
            delete_old_images|Delete old images first|Before an export starts, delete the tile images, tile views and radar map that an earlier export left in this client's folder. Other files and subfolders are kept. Off keeps old images and replaces only files with the same name.
            format|Image format|PNG preserves image pixels without loss. JPEG produces smaller files with visible compression at lower quality settings.
            tile_resolution|Tile resolution (px)|Width and height of each square tile export. A 4096-pixel image gives 8 world units per pixel for a 32768-unit tile. Higher values use more memory and time.
            jpeg_quality|JPEG quality (1–100)|JPEG compression quality from 1 to 100. Higher values preserve detail and produce larger files. PNG does not use this value.
            write_tiles|Write individual tiles|Write a separate image for each captured tile. Turn off when only the combined radar map is needed. Tile views other than a main view are always saved as files.
            open_folder_on_complete|Open folder after Capture|Open the result folder in your file manager after a successful Capture, once all images and the radar map are written. Cancelled captures and temporary map previews do not open it.
            """);
        addGroup(CAPTURE_DIALOG, "Tile views", "output.", """
            views|Tile views|Extra images of single tiles with their own height cuts or without passable surfaces. Use them for tower floors, dungeons under the ground, or a radar map without bushes and small plants. A cut can keep the terrain or other layers whole. A main view replaces the tile's normal image.
            """);
        addGroup(CAPTURE_DIALOG, "Radar map", "output.radar.", """
            enabled|Create radar map|Combine captured tiles into one radar overview image. Pixels are stored in a temporary file, so large images need disk space instead of a full image in RAM.
            file|Radar filename|Filename of the combined radar overview. Relative paths are resolved inside the export output folder. The extension follows the selected radar format.
            format|Radar image format|PNG preserves every pixel. JPEG produces a smaller radar map with some loss of detail. Independent of the format of individual tile images.
            jpeg_quality|Radar JPEG quality (1–100)|JPEG quality from 1 to 100. Higher values preserve detail and produce larger files. Start with 90 for a compact radar overview. PNG does not use this value.
            tile_resolution|Tile resolution (px)|Pixels contributed by each map tile to the combined overview. Independent of the resolution of individual tile images.
            """);
        addGroup(FOLDERS, "Location and NPC export", EXPORT_PREFIX, """
            folder|Export folder|Folder where Export location and Export NPC start. A folder named after the tiles or the NPC is made inside it. Leave it empty for Documents/Cruma exports in your home folder, or Cruma exports in your home folder when it has no Documents folder. The dialogs save the folder you export to here. ~ is your home folder, and a relative path counts from the folder with cruma.conf.
            scale_cm|Export scale (cm per unit)|How long one client unit is in the exported files, in centimetres, from 0.1 to 1000. 4 is real size, so a person is 1.84 m tall. 1 reads the client's numbers as centimetres, as Unreal Engine does, and 100 as metres. The dialogs save the scale you export with here.
            """);
        addGroup(FOLDERS, "Cut scene export", EXPORT_PREFIX, """
            scene_format|Cut scene export|What Scenes exports a cut scene as: a folder for Blender, or an MP4 file. Scenes saves the choice you make there here.
            video_size|Video size|The size of the MP4 file of a cut scene in pixels. It does not depend on the size of the screen. Scenes saves the choice you make there here.
            video_rate|Video frames a second|The frames a second of the MP4 file of a cut scene. 60 is smoother and takes twice as long to make. Scenes saves the choice you make there here.
            """);
        addGroup(FOLDERS, "Map editing export", EXPORT_PREFIX, """
            install_after_write|Install after writing|Whether the save of Editing copies the written packages into the client right after Write. The client files it replaces are kept in the export folder for Restore client. The save page saves the choice you make there here.
            """);
        addGroup(FOLDERS, "Effect and skill scripts", EXPORT_PREFIX, """
            scripts_folder|Effect scripts folder|Folder where Save of the Script window of Effects and the AI agents put the scripts of effects and skills, a folder for each package in it. Leave it empty for Scripts in the export folder. The Script window keeps a folder here when you tick Use this folder next time. ~ is your home folder, and a relative path counts from the folder with cruma.conf.
            """);
        addGroup(FOLDERS, "Video director", "director.", """
            scenes_folder|Scenes folder|Folder of your scenes, each a .scene.json file. MY SCENES in the Scenes tool lists it, and a new scene is saved there. ~ is your home folder, and a relative path counts from the folder with cruma.conf.
            """);
        addGroup(AI_AGENT, "Your windows", "agent.", """
            enabled|Let an AI agent use this window|Lets an agent such as Claude work in the windows you open yourself: it sees your selection and your camera, and you see what it does. An agent can always open windows of its own. The AI agent panel of the Viewer has the same switch.
            access|Access|How far an agent may go. See only looks, reads and exports. Edit also changes the world in memory, where Undo takes it back. Write also saves into the client and server folders. Cruma checks this on each call.
            """);
        addGroup(ADVANCED, "Loading", "", """
            parser_workers|Parallel tile decoders|Number of tiles decoded concurrently on the CPU. More workers may reduce loading time but increase peak memory and compete with other applications. GPU uploads remain on the render thread.
            """);
        addGroup(ADVANCED, "Edit recovery", "editing.", """
            recovery_enabled|Recover editing sessions|Keep private recovery copies of committed edits, imported assets and Undo/Redo history. Recovery runs only in Editing and never saves over native client packages.
            autosave_debounce_ms|Save after idle (ms)|Wait this many milliseconds after the last committed edit before writing a recovery copy, from 100 to 60000. The edit-count limit also saves during continuous work.
            checkpoint_commands|Edits between recovery copies|Write a recovery copy after this many committed edits, Undo or Redo operations, from 1 to 256. This limits unsaved work while editing continuously.
            retained_generations|Recovery copies to keep|Keep 2 to 8 valid recovery copies so an earlier draft remains available if the newest copy cannot be read. Each copy can include imported packages and history.
            maximum_megabytes|Maximum recovery copy (MiB)|Maximum size of one recovery copy, including imported assets retained by Undo/Redo, from 16 to 1024 MiB. An oversized copy is rejected and the previous draft is preserved.
            recovery_directory|Recovery folder|Folder for private editing recovery copies, separated by source client. Relative paths count from the folder containing cruma.conf. Changing it leaves earlier drafts in their original folder.
            """);
        addGroup(ADVANCED, "Edit", "editing.", """
            move_actors_to_their_tile|Move actors to their tile|When a drag, an arrow-key nudge or a drop to the ground leaves an actor in another tile than its map, move it into the map of that tile, in the same Undo step, when that map is loaded. It keeps its place. Off keeps it in its map, and the Selection part offers Move to.
            welcome_shown|First visit card closed|On once the card that welcomes a first visit to Edit was closed with Got it or by a first Save. Off shows the card again the next time Edit opens.
            teleport_command|Teleport command|The admin command of your server that Copy teleport command in the right-click menu of Edit puts on the clipboard. %x, %y and %z become the whole numbers of the place, such as //teleportto 83000 148000 -3400.
            """);
        addGroup(ADVANCED, "Diagnostics", "rendering.graphics.", """
            validation|Graphics validation|Enable graphics validation for diagnosing rendering errors. Adds overhead and is normally off. This does not improve image quality.
            profiler|GPU timing queries|Collect per-pass GPU timings for the Performance panel. Timer queries add overhead, so enable them when investigating performance.
            """);
        addGroup(ADVANCED, "Optimizations", "rendering.graphics.optimizations.", """
            profile|Optimization profile|Optimized enables the supported rendering optimizations. Compatibility disables them for diagnosing artifacts. Custom retains individually selected flags. Normal use should keep Optimized.
            fill_reflection|Fill and reflection optimization|Reduce redundant opaque shading, unused terrain texture samples and duplicate water reflection work. Enabled by default. Disable to compare rendering when diagnosing artifacts.
            gpu_visibility|GPU visibility|Use GPU visibility testing to reject hidden or very small objects. Falls back when required GPU features are absent. Disable to diagnose disappearing geometry.
            indirect_submission|Indirect submission|Let GPU visibility results drive indirect draw commands, reducing CPU submission work. Requires GPU visibility and hardware support. Otherwise rendering uses the direct path.
            parallel_encoders|Parallel encoders|Legacy configuration key retained for compatibility. Parallel encoder submission was removed. Changing this value does not enable it.
            """);
        addGroup(ADVANCED, "Geometry and visibility", "rendering.", """
            near_plane|Near clipping plane (units)|Nearest visible distance from the camera in world units. Smaller values let you approach surfaces more closely. Far from everything the free view moves it out by itself.
            one_off_merge_vertex_budget|Merge vertex budget|Largest unique mesh, in vertices, eligible for tile merging to reduce draw calls. Larger meshes stay on the instanced path. Higher limits can increase merged buffer size.
            """);
        addGroup(ADVANCED, "Geometry and visibility", "rendering.graphics.tuning.", """
            terrain_proxy_grid_step|Terrain proxy grid step|Sampling step for coarse distant terrain, a power of two from 2 to 16. Larger steps reduce geometry but discard more terrain detail.
            command_hierarchy_leaf_commands|Commands per hierarchy leaf|Draw commands per visibility-hierarchy leaf, a power of two from 2 to 32. Trades hierarchy traversal work against the precision of grouped visibility tests.
            visibility_min_projected_pixels|Minimum object size (px)|GPU visibility rejects objects below this projected size in pixels. Higher thresholds reduce work but hide small distant objects. 0 disables this size cutoff.
            visibility_terrain_detail_pixels|Detailed terrain threshold (px)|Projected tile size in pixels at which detailed terrain replaces the coarse proxy. A higher threshold keeps coarse terrain in use closer to the camera. 0 always keeps detailed terrain. Also adjustable live in Scene settings.
            gpu_group_sort_cell_size|Depth-sort cell size (units)|World-unit cell size for approximate front-to-back opaque sorting. This changes sorting granularity to reduce overdraw. It does not change the map tile size.
            """);
        addGroup(ADVANCED, "Effects", "rendering.graphics.tuning.", """
            fx_min_projected_pixels|Minimum effect size (px)|Do not draw emitters smaller than this projected pixel size. Higher values reduce distant effect work but can hide effects. 0 disables this size cutoff.
            fx_simulation_lod_projected_pixels|Reduced simulation below (px)|Emitters below this projected pixel size simulate less frequently. Higher values save CPU time but may make distant motion less smooth.
            """);
        addGroup(ADVANCED, "GPU buffers", "rendering.graphics.tuning.", """
            actor_lighting_words|Initial lighting buffer (words)|Initial vertex-lighting allocation in 32-bit words. The buffer grows as needed and retains a CPU copy. Larger initial values avoid growth but reserve more memory.
            material_page_target_layers|Layers per material page|Target number of texture layers per shared material page. Larger pages can reduce texture handles and draw calls but allocate larger arrays.
            ordinary_diffuse_page_group_size|Diffuse pages per group|Number of diffuse pages an ordinary-material batch can share: 1, 2 or 4. Larger groups can reduce draw calls while using more texture bindings.
            fx_actor_transform_capacity|Effect transform capacity|Capacity of the effect-actor transform buffer. Increase if a scene exceeds it. Larger values reserve more GPU memory. Minimum 1024.
            visibility_max_candidates|Visibility candidate capacity|Maximum GPU visibility candidates. Larger limits accommodate denser worlds at the cost of more buffer memory.
            visibility_max_commands|Draw command capacity|Maximum GPU visibility draw commands. Larger limits accommodate denser worlds at the cost of more buffer memory.
            """);
        addGroup(ADVANCED, "Texture mip generation", "rendering.", """
            dxt_mip_codec_enabled|Generate extra DXT mips|Synthesize missing small mip levels for DXT textures to smooth distant sampling. Changes texture filtering and adds preprocessing/cache work. Off preserves the supplied mip chain.
            dxt_mip_min_size|Minimum source size (px)|Minimum source texture dimension eligible for extra DXT mip generation. Smaller textures are left unchanged.
            dxt_mip_cache_path|Mip cache folder|Folder for generated DXT mip tails, reused on later loads. Each client has a separate subfolder. Leave empty to disable disk caching, not mip generation.
            """);
        addGroup(ADVANCED, "Water reference values", "rendering.water_color_reference.", """
            r|Red (0–255)|Red component of the configurable water reference colour, from 0 to 255. Used by the water rendering path, not as a global scene tint.
            g|Green (0–255)|Green component of the configurable water reference colour, from 0 to 255. Used by the water rendering path, not as a global scene tint.
            b|Blue (0–255)|Blue component of the configurable water reference colour, from 0 to 255. Used by the water rendering path, not as a global scene tint.
            a|Alpha (0–255)|Alpha component of the water reference colour, from 0 to 255. Adjusts its contribution to the water shader.
            reflection_reference|Reflection reference|Water reflection reference strength, stored from 0 to 255 and normalized for the water shader. Adjusts the reflection contribution.
            bump_amplitude|Ripple amplitude|Strength of ripple distortion in the water shader. Larger values bend the sampled image more strongly.
            bump_mip_ceiling|Maximum ripple mip|Highest ripple-texture mip level the water shader may sample. Allowing coarser levels can reduce distant shimmer.
            """);
    }

    private LaunchOptionCatalog() {}

    public record Presentation(String category, String section, String label, String description, int order) {}

    public static boolean isEditable(String path) {
        if (path.equals(ClientProfiles.LIST)
                || path.endsWith(".parallel_encoders")
                || ConfigMigrations.getRemovals().containsKey(path)) {
            return false;
        }
        if (path.equals(ClientProfiles.CAPTURE_VARIANT)) {
            return false;
        }
        if (path.startsWith("capture.world.")) {
            var key = path.substring("capture.world.".length());
            return !key.startsWith("music.")
                    && !key.startsWith("sound.")
                    && !key.startsWith("geodata.")
                    && !key.startsWith("spawns.")
                    && !key.startsWith("rendering.graphics.upscaler.")
                    && !CAPTURE_FIXED_SETTINGS.contains(key);
        }
        return true;
    }

    public static Presentation presentation(String path) {
        var key = path.replaceFirst("^(viewer|capture)\\.world\\.", "").replaceFirst("^(viewer|capture)\\.", "");
        return ITEMS.get(key);
    }

    public static @Nullable String pageOf(String path) {
        var info = presentation(path);
        if (info == null || !isEditable(path) || !isInProfiles(path) || isMovedToCaptureDialog(path)) {
            return null;
        }
        if (MIRRORS.contains(path)) {
            return null;
        }
        if (isTileBound(path)) {
            return ADVANCED;
        }
        if (path.startsWith("capture.")) {
            return CAPTURE_RENDERING;
        }
        return info.category();
    }

    public static String sectionOf(String path) {
        if (isTileBound(path) && path.startsWith("capture.")) {
            return "Default area of Capture";
        }
        return presentation(path).section();
    }

    public static List<Field> fieldsOn(List<Field> fields, String page) {
        var result = new ArrayList<Field>();
        for (var field : fields) {
            if (page.equals(pageOf(field.path()))) {
                result.add(field);
            }
        }
        result.sort(ORDER);
        return List.copyOf(result);
    }

    public static List<Field> search(List<Field> fields, String query, boolean client) {
        var result = new ArrayList<Field>();
        for (var field : fields) {
            var page = pageOf(field.path());
            if (page != null && (client || !page.equals(CLIENT)) && matches(field.path(), page, query)) {
                result.add(field);
            }
        }
        result.sort(ORDER);
        return List.copyOf(result);
    }

    public static int countCaptureDialogMatches(List<Field> fields, String query) {
        var count = 0;
        for (var field : fields) {
            if (isMovedToCaptureDialog(field.path())
                    && presentation(field.path()) != null
                    && isEditable(field.path())
                    && matches(field.path(), CAPTURE_DIALOG, query)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isMovedToCaptureDialog(String path) {
        return path.startsWith("capture.output.");
    }

    public static boolean isFolder(String path) {
        return FOLDER.matcher(path).matches();
    }

    public static boolean isTileBound(String path) {
        return TILE_BOUND.matcher(path).matches();
    }

    public static boolean isSegmented(String path) {
        if (path.equals(ClientProfiles.VARIANT)
                || path.equals(ExportSettings.SCENE_FORMAT)
                || path.equals("capture.output.format")
                || path.equals("capture.output.radar.format")) {
            return true;
        }
        for (var end : SEGMENTED) {
            if (path.endsWith(end)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> resetPaths(List<Field> shown) {
        var result = new ArrayList<String>();
        for (var field : shown) {
            if (!isFolder(field.path()) && !field.path().equals(LOCATIONS)) {
                result.add(field.path());
            }
        }
        return List.copyOf(result);
    }

    public static String inactiveReason(String path, OptionsModel model) {
        if (path.equals(ClientProfiles.GEODATA_PATH) && model.getSelectedClient() == null) {
            return "Choose a game client in the start menu to set its geodata folder.";
        }
        if (path.equals(ClientProfiles.SCRIPT_PATH) && model.getSelectedClient() == null) {
            return "Choose a game client in the start menu to set its script folder.";
        }
        String world = path.startsWith("capture.") ? "capture.world." : "viewer.world.";
        if (path.startsWith(world + "geodata.display.")) {
            if ((path.endsWith(".min_height") || path.endsWith(".max_height") || path.endsWith(".dim_other_heights"))
                    && !Boolean.TRUE.equals(model.getValue(world + "geodata.display.height_filter")
                            .unwrapped())) {
                return "Enable Filter height range to adjust these settings.";
            }
            if (path.endsWith(".local_grid_radius")
                    && !Boolean.TRUE.equals(
                            model.getValue(world + "geodata.display.local_grid").unwrapped())) {
                return "Enable Detail grid to adjust its radius.";
            }
        }
        if (path.startsWith("capture.") && !isEditable(path)) {
            return "Capture renders every tile at native resolution without audio or distance and size limits.";
        }
        if (path.startsWith("viewer.editing.")
                && !path.endsWith(".recovery_enabled")
                && !path.endsWith(".move_actors_to_their_tile")
                && !path.endsWith(".welcome_shown")
                && !path.endsWith(".teleport_command")
                && !Boolean.TRUE.equals(
                        model.getValue("viewer.editing.recovery_enabled").unwrapped())) {
            return "Enable Recover editing sessions to use this setting.";
        }
        String upscaler = world + "rendering.graphics.upscaler.";
        if (path.endsWith(".custom_authored_shadow_strength")
                && !Boolean.TRUE.equals(model.getValue(world + "rendering.custom_lighting_enabled")
                        .unwrapped())) {
            return "Enable Custom lighting to adjust shadow strength.";
        }
        String algorithm = model.getValue(upscaler + "algorithm").unwrapped().toString();
        if (path.contains(".upscaler.") && !path.endsWith(".algorithm") && algorithm.equals("native")) {
            return "Choose FSR 1 or Bilinear to render below display resolution.";
        }
        if ((path.endsWith(".rcas_enabled") || path.endsWith(".sharpness")) && !algorithm.equals("fsr1")) {
            return "Sharpening is available with AMD FSR 1.";
        }
        if (path.endsWith(".sharpness")
                && !Boolean.TRUE.equals(
                        model.getValue(upscaler + "rcas_enabled").unwrapped())) {
            return "Enable Sharpen image to adjust its strength.";
        }
        if ((path.endsWith(".dxt_mip_min_size") || path.endsWith(".dxt_mip_cache_path"))
                && !Boolean.TRUE.equals(model.getValue(world + "rendering.dxt_mip_codec_enabled")
                        .unwrapped())) {
            return "Enable Generate extra DXT mips to use this setting.";
        }
        for (String group : List.of("music", "sound")) {
            if (path.contains("." + group + ".")
                    && !path.endsWith(".enabled")
                    && !path.contains(".night_")
                    && !Boolean.TRUE.equals(
                            model.getValue(world + group + ".enabled").unwrapped())) {
                return "Enable " + (group.equals("music") ? "music" : "ambient sound") + " to use this setting.";
            }
        }
        if (path.startsWith("capture.output.radar.")
                && !path.endsWith(".enabled")
                && !Boolean.TRUE.equals(
                        model.getValue("capture.output.radar.enabled").unwrapped())) {
            return "Enable Create radar map to use this setting.";
        }
        if ((path.equals("capture.output.jpeg_quality") || path.equals("capture.output.radar.jpeg_quality"))
                && model.getValue(path.substring(0, path.lastIndexOf('.') + 1) + "format")
                        .unwrapped()
                        .equals("png")) {
            return "Choose JPEG to adjust compression quality.";
        }
        if (path.endsWith(".static_mesh_culling_distance")
                && !Boolean.TRUE.equals(model.getValue(world + "rendering.static_mesh_distance_culling_enabled")
                        .unwrapped())) {
            return "Enable Limit object distance to use this setting.";
        }
        if (path.contains(".dynamic_resolution.")
                && !path.endsWith(".enabled")
                && !Boolean.TRUE.equals(model.getValue(world + "rendering.graphics.upscaler.dynamic_resolution.enabled")
                        .unwrapped())) {
            return "Enable Dynamic resolution to use this setting.";
        }
        if (path.endsWith("upscaler.custom_scale")
                && !model.getValue(world + "rendering.graphics.upscaler.preset")
                        .unwrapped()
                        .equals("custom")) {
            return "Choose the Custom quality preset to set a render scale.";
        }
        return "";
    }

    private static boolean isInProfiles(String path) {
        return path.startsWith("viewer.")
                || path.startsWith("capture.")
                || path.startsWith(SHARED_PREFIX)
                || path.startsWith(EXPORT_PREFIX);
    }

    private static boolean matches(String path, String page, String query) {
        var info = presentation(path);
        var haystack = (page + " " + sectionOf(path) + " " + info.label() + " " + path).toLowerCase(Locale.ROOT);
        for (var word : query.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
            if (!haystack.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static int rankOf(String path) {
        var page = pageOf(path);
        var index = page == null ? PAGES.size() + 1 : page.equals(CLIENT) ? 0 : PAGES.indexOf(page) + 1;
        var bounds = isTileBound(path) ? (path.startsWith("capture.") ? 1 : 0) : 2;
        return index * 4 + bounds;
    }

    private static void addGroup(String category, String section, String prefix, String entries) {
        for (var entry : entries.strip().split("\\R")) {
            var parts = entry.split("\\|", 3);
            ITEMS.put(prefix + parts[0], new Presentation(category, section, parts[1], parts[2], ITEMS.size()));
        }
    }
}

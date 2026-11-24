package com.rost.cruma.viewer.system;

import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.ApplicationContext;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigurationProblem;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.InterfaceScale;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.config.OptionsModel;
import com.rost.cruma.viewer.diagnostics.LogFiles;
import com.rost.cruma.viewer.guide.GuideHistory;
import com.rost.cruma.viewer.guide.GuideLibrary;
import com.rost.cruma.viewer.guide.GuideRequests;
import com.rost.cruma.viewer.launch.ClientFolders;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.launch.MapPreviewCache;
import com.rost.cruma.viewer.launch.MenuStart;
import com.rost.cruma.viewer.launch.RecentClients;
import com.rost.cruma.viewer.launch.RecentClients.Entry;
import com.rost.cruma.viewer.launch.SessionHistory;
import com.rost.cruma.viewer.launch.SessionHistory.Kind;
import com.rost.cruma.viewer.launch.SessionHistory.Session;
import com.rost.cruma.viewer.launch.StartKeys;
import com.rost.cruma.viewer.launch.StartPlaces;
import com.rost.cruma.viewer.launch.StartSelection;
import com.rost.cruma.viewer.launch.StartSelection.Size;
import com.rost.cruma.viewer.launch.TileBounds;
import com.rost.cruma.viewer.launch.TileSelection;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.render.bgfx.BgfxDevice;
import com.rost.cruma.viewer.render.bgfx.BgfxViewerUiRenderer;
import com.rost.cruma.viewer.system.WindowSystem.Placement;
import com.rost.cruma.viewer.ui.CaptureDialog;
import com.rost.cruma.viewer.ui.CrumaBranding;
import com.rost.cruma.viewer.ui.FontManager;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.GeodataWidgets.LineLook;
import com.rost.cruma.viewer.ui.GeodataWidgets.Picture;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.GuideHost;
import com.rost.cruma.viewer.ui.GuideWindow;
import com.rost.cruma.viewer.ui.HudTheme;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.LaunchOptions;
import com.rost.cruma.viewer.ui.LocationExportDialog;
import com.rost.cruma.viewer.ui.LocationExportDialog.Place;
import com.rost.cruma.viewer.ui.Motion;
import com.rost.cruma.viewer.ui.NativeDialogs;
import com.rost.cruma.viewer.ui.NativeDialogs.Outcome;
import com.rost.cruma.viewer.ui.NpcExportDialog;
import com.rost.cruma.viewer.ui.TileMapPicker;
import com.rost.cruma.viewer.ui.TileMapPicker.Gesture;
import com.rost.cruma.viewer.ui.TileMapPicker.Layout;
import com.rost.cruma.viewer.ui.TileMapPicker.Pointer;
import com.rost.cruma.viewer.ui.TileMapPicker.Zoom;
import com.rost.cruma.viewer.ui.TileMapPreview;
import com.rost.cruma.viewer.ui.TileOperationsDialog;
import com.rost.cruma.viewer.ui.TileOperationsDialog.MapUpdate;
import com.rost.cruma.viewer.ui.aside.ClientAside;
import com.rost.cruma.viewer.ui.aside.MenuMusic;
import com.rost.cruma.viewer.ui.aside.PlaceAside;
import com.rost.cruma.viewer.ui.aside.RadioAside;
import com.rost.cruma.viewer.ui.aside.StartAside;
import com.rost.cruma.viewer.ui.aside.StartAsides;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

public final class ClientLauncher {
    public static final String ASIDE_FILE = "start-aside.txt";

    static final String GUIDE_START = GuideHistory.HOME;

    static final float WIDE = 1000;

    private static final int MENU_SHOWN = "launcher/shown".hashCode();
    private static final int MIN_WIDTH = 700;
    private static final int MIN_HEIGHT = 560;

    private static final float SIDE_WIDE = 304;
    private static final float SIDE_SMALL = 240;

    private static final float MARGIN = GeodataWidgets.BLOCK;

    private static final float GUIDE_ROOM = 420;

    private static final float BAR_HEIGHT = 56;

    private static final float BAR_PICTURE = 40;

    private static final float SIZES_WIDTH = 224;
    private static final float MODES_WIDTH = 128;
    private static final float OPEN_WIDTH = 122;

    private static final float OPEN_HEIGHT = 34;

    private static final float TITLE_LEAST = 160;

    private static final float BAR_MOST = 886;

    private static final float VARIANT_SEGMENT_WIDTH = 75;

    private static final String ASIDE_FIRST = "Place";

    private static final float ASIDE_LEAST = 360;
    private static final float ASIDE_MOST = 520;

    private static final int RECENT_SHOWN = 5;

    private static final long NOTE_NANOS = 4_000_000_000L;
    private static final long NOTE_WITH_ACTION_NANOS = 10_000_000_000L;

    private static final List<String[]> HINTS = List.of(
            new String[] {"Click", "choose"},
            new String[] {"Drag", "several"},
            new String[] {"Shift", "add"},
            new String[] {"Double-click", "open"});

    private static final String NO_NAMED_MAPS =
            "This client has no map with a name of its own, such as Maps/Lobby01.unr.";

    private final ConfigLoader loader;
    private Config configured;
    private CrumaBranding branding;
    private LaunchOptions options;
    private TileMapPreview mapPreview;
    private final TileOperationsDialog tileOperations = new TileOperationsDialog();
    private final CaptureDialog captureDialog;
    private final LocationExportDialog locationExport;
    private final NpcExportDialog npcExport;
    private @Nullable Placement placement;
    private @Nullable WindowContext window;

    private List<StartAside> asides = StartAsides.create();
    private @Nullable StartAside shownAside;
    private @Nullable String asideChoice;
    private @Nullable WorldVariant requestedVariant;
    private @Nullable BgfxDevice device;
    private @Nullable BgfxViewerUiRenderer renderer;
    private MenuMusic music = new MenuMusic(this::musicVolume);

    private final ImString browsePath = new ImString(4096);
    private final ImBoolean showHidden = new ImBoolean(false);
    private final FontManager fonts = new FontManager();
    private @Nullable ViewerUiRenderer uiRenderer;
    private final float[] guidePlace = new float[4];
    private final GuideWindow guide = new GuideWindow(fonts, new StartScreenHost(), () -> uiRenderer);
    private @Nullable RecentClients recent;
    private @Nullable SessionHistory sessions;
    private @Nullable ClientInstallation installation;
    private StartSelection selection = StartSelection.NONE;
    private boolean editing;
    private StartPlaces places = new StartPlaces(List.of());
    private List<String> namedMaps = List.of();
    private Selection result;
    private boolean cancelled;
    private @Nullable Path capturedInto;
    private Path browsing;
    private List<Path> directories = List.of();
    private String browseError = "";
    private boolean requestOptions;
    private boolean requestClientPage;
    private boolean requestTablesPage;
    private boolean requestBrowser;
    private boolean requestCapture;
    private boolean requestExportMaps;
    private @Nullable Path captureFolder;
    private int automaticScalePercent = 100;

    private final ImString query = new ImString(128);
    private boolean searchActive;
    private int searchId;
    private boolean focusSearch = true;

    private int startFrame = -1;

    private int starts;
    private @Nullable Layout mapLayout;

    private Zoom zoom = new Zoom();
    private Pointer pointer = new Pointer();

    private final List<Toast> toasts = new ArrayList<>();

    private final Map<Path, Inspected> inspected = new HashMap<>();

    private final Map<String, float[]> rects = new HashMap<>();

    private MapEventState mapEvents = MapEventState.ALL_SHOWN;
    private @Nullable Path mapEventsClient;
    private @Nullable Config mapEventsConfig;

    public ClientLauncher(ConfigLoader loader, Config configured) {
        this(loader, configured, false);
    }

    public ClientLauncher(ConfigLoader loader, Config configured, boolean previousEditing) {
        this.loader = loader;
        this.configured = configured;
        this.editing = previousEditing;
        guide.setSolid(true);
        var exportSettings = ExportSettings.in(loader);
        locationExport = new LocationExportDialog(exportSettings, MapEventChoices.in(loader));
        npcExport = new NpcExportDialog(exportSettings);
        captureDialog = new CaptureDialog(loader);
        places = placesOf(configured);
        try {
            recent = new RecentClients(loader.getConfigDirectory());
        } catch (RuntimeException failure) {
            showError(failure.getMessage());
        }
        try {
            sessions = new SessionHistory(
                    loader.getConfigDirectory(),
                    recent == null ? List.of() : SessionHistory.seed(recent.getEntries(), places));
        } catch (RuntimeException failure) {
            showError(failure.getMessage());
        }
        var opened = false;
        if (recent != null) {
            for (var entry : recent.getEntries()) {
                if (inspect(entry.path()).client() != null) {
                    select(entry.path(), entry.tile());
                    opened = installation != null;
                    break;
                }
            }
        }
        if (!opened && !configured.viewer().world().clientPath().toString().isBlank()) {
            select(configured.viewer().world().clientPath(), null);
        }
    }

    public boolean isEditingMode() {
        return editing;
    }

    public record Selection(
            Config config,
            boolean capture,
            List<String> maps,
            boolean mapPreview,
            @Nullable MapPatch patch,
            boolean effects,
            boolean editing,
            @Nullable MenuStart start) {
        public Selection(Config config, boolean capture, List<String> maps) {
            this(config, capture, maps, false, null);
        }

        public Selection(Config config, boolean capture, List<String> maps, boolean mapPreview) {
            this(config, capture, maps, mapPreview, null);
        }

        public Selection(
                Config config, boolean capture, List<String> maps, boolean mapPreview, @Nullable MapPatch patch) {
            this(config, capture, maps, mapPreview, patch, false, false);
        }

        public Selection(
                Config config,
                boolean capture,
                List<String> maps,
                boolean mapPreview,
                @Nullable MapPatch patch,
                boolean effects,
                boolean editing) {
            this(config, capture, maps, mapPreview, patch, effects, editing, null);
        }

        public Selection {
            maps = List.copyOf(maps);
        }
    }

    public record MapPatch(Path base, List<String> tiles) {
        public MapPatch {
            tiles = List.copyOf(tiles);
        }
    }

    private record Toast(
            String text, boolean error, @Nullable Path folder, long at) {}

    private record Inspected(@Nullable ClientInstallation client, List<String> namedMaps, String problem) {}

    private record Frame(
            float width,
            float height,
            float side,
            boolean guideBeside,
            float mainLeft,
            float mapLeft,
            float mapTop,
            float mapRight,
            float mapBottom,
            float barLeft,
            float barTop,
            float barWidth,
            boolean wideBar,
            @Nullable Layout map,
            boolean aside,
            float asideLeft,
            float asideWidth,
            float switcherLeft) {}

    public void openAt(@Nullable Placement placement) {
        this.placement = placement;
    }

    public @Nullable Placement getPlacement() {
        return placement;
    }

    public @Nullable Selection choose() {
        var context = new WindowContext();
        window = context;
        var application = new ApplicationContext();
        var host = new WindowSystem(context, application, "Cruma", 1000, 760, true, true, placement);
        var input = new SingleWindowImGuiGlfw();
        boolean inputReady = false;
        GuideRequests.take();
        GuideLibrary.warmUp();
        ImGui.createContext();
        try (var device = new BgfxDevice(context, ViewerGraphicsConfig.defaults());
                var renderer = new BgfxViewerUiRenderer(device)) {
            inputReady = input.init(context.windowHandle, true);
            if (!inputReady) {
                throw new IllegalStateException("Cannot initialize the client picker input");
            }
            var io = ImGui.getIO();
            io.setIniFilename(null);
            io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
            input.setInterfaceScale(interfaceScale());
            fonts.initViewerFonts(io, input.getPixelDensity(context.windowHandle));
            float limitFactor = input.getFactor();
            host.limitInterfaceSize(MIN_WIDTH, MIN_HEIGHT, limitFactor);
            renderer.initialize();
            uiRenderer = renderer;
            this.device = device;
            this.renderer = renderer;
            mapPreview = new TileMapPreview(loader.getConfigDirectory(), renderer);
            npcExport.setRenderer(renderer);
            branding = new CrumaBranding(renderer);
            theme();
            host.start();
            try {
                return runFrames(context, host, input, device, renderer, io, limitFactor);
            } finally {
                releaseAside();
                music.close();
                this.device = null;
                this.renderer = null;
            }
        } finally {
            mapPreview = null;
            uiRenderer = null;
            window = null;
            guide.release();
            if (inputReady) {
                input.shutdown();
            }
            ImGui.destroyContext();
            host.stop(context);
            if (host.getPlacement() != null) {
                placement = host.getPlacement();
            }
        }
    }

    public void showCaptureFolder(@Nullable Path directory) {
        captureFolder = directory;
    }

    public void setCapturedInto(@Nullable Path directory) {
        capturedInto = directory;
        for (var index = toasts.size() - 1; index >= 0; index--) {
            var toast = toasts.get(index);
            if (!toast.error() && toast.folder() == null) {
                toasts.set(index, new Toast(toast.text(), false, directory, toast.at()));
                return;
            }
        }
    }

    public void showNotice(String text) {
        if (!text.isEmpty()) {
            toasts.add(new Toast(text, false, capturedInto, java.lang.System.nanoTime()));
        }
    }

    public void showProblem(String text) {
        if (!text.isEmpty()) {
            showError(text + " Details are in Logs.");
        }
    }

    public float @Nullable [] place(String name) {
        var rect = rects.get(name);
        return rect == null ? null : rect.clone();
    }

    public List<String> getPlaceNames() {
        return List.copyOf(rects.keySet());
    }

    private @Nullable Selection runFrames(
            WindowContext context,
            WindowSystem host,
            SingleWindowImGuiGlfw input,
            BgfxDevice device,
            BgfxViewerUiRenderer renderer,
            ImGuiIO io,
            float limitFactor) {
        while (!cancelled && result == null && !GLFW.glfwWindowShouldClose(context.windowHandle)) {
            GLFW.glfwWaitEventsTimeout(
                    Motion.isAnimating()
                                    || pointer.isDragging()
                                    || isToastMoving(java.lang.System.nanoTime())
                                    || (shownAside != null && shownAside.isAnimating())
                            ? 1.0 / 120.0
                            : 1.0 / 30.0);
            host.frameBegin(33);
            device.beginFrame();
            input.setInterfaceScale(interfaceScale());
            input.newFrame();
            if (input.getFactor() != limitFactor) {
                limitFactor = input.getFactor();
                host.limitInterfaceSize(MIN_WIDTH, MIN_HEIGHT, limitFactor);
            }
            automaticScalePercent = Math.round(host.getInterfaceFactor(InterfaceScale.AUTOMATIC) * 100);
            if (fonts.updateViewerScale(
                    io, Math.max(io.getDisplayFramebufferScaleX(), io.getDisplayFramebufferScaleY()))) {
                renderer.refreshFontAtlas();
            }
            renderer.newFrame();
            ImGui.newFrame();
            HudTheme.push(24, 24);
            ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 9, 7);
            draw();
            ImGui.popStyleVar();
            HudTheme.pop();
            ImGui.render();
            renderer.render(ImGui.getDrawData(), context.framebuffer.width, context.framebuffer.height);
            device.endFrame();
            afterFramePresented();
            releaseIfEnding();
        }
        return result;
    }

    private void releaseIfEnding() {
        if (result != null || cancelled) {
            releaseAside();
        }
    }

    private void showError(@Nullable String text) {
        toasts.add(new Toast(
                text == null || text.isBlank() ? "Something went wrong." : text,
                true,
                null,
                java.lang.System.nanoTime()));
    }

    private void showNote(String text) {
        toasts.add(new Toast(text, false, null, java.lang.System.nanoTime()));
    }

    private MapEventState mapEventsOf(Path client) {
        if (!client.equals(mapEventsClient) || configured != mapEventsConfig) {
            mapEventsClient = client;
            mapEventsConfig = configured;
            try {
                mapEvents =
                        ClientProfiles.mapEvents(loader.getEffectiveSettings(), client, loader.getConfigDirectory());
            } catch (RuntimeException unreadable) {
                mapEvents = MapEventState.ALL_SHOWN;
            }
        }
        return mapEvents;
    }

    private void afterFramePresented() {
        if (captureFolder != null) {
            var directory = captureFolder;
            captureFolder = null;
            FileManager.tryOpenFolder(directory);
        }
    }

    private InterfaceScale interfaceScale() {
        return options != null ? options.getInterfaceScale() : configured.ui().scale();
    }

    private void draw() {
        var io = ImGui.getIO();
        var width = io.getDisplaySizeX();
        var height = io.getDisplaySizeY();
        var fieldHadKeys = io.getWantTextInput();
        var searchHadKeys = searchActive;
        var navVisible = io.getNavVisible() && imgui.internal.ImGui.getFocusID() != searchId;
        var popupOpen = ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel);
        rects.clear();
        takeGuideRequest();
        takeDroppedFolder(popupOpen);
        GeodataWidgets.keepHintsRightOf(0);
        takeVariantRequest();
        var frame = layout(width, height);
        followAside(frame);
        ImGui.getBackgroundDrawList().addRectFilled(0, 0, width, height, ImGui.getColorU32(.055f, .075f, .095f, 1f));
        if (guide.isOpen()) {
            guide.placeAt(frame.side() + MARGIN, MARGIN, height - MARGIN, width - frame.side() - 2 * MARGIN);
        }
        var laidOutFor = installation;
        drawSide(frame);
        if (installation != laidOutFor) {
            frame = layout(width, height);
            followAside(frame);
        }
        drawMain(frame);
        drawToasts(frame);
        takeKeys(fieldHadKeys, searchHadKeys, navVisible, popupOpen);
        guide.drawPlaced();
    }

    private Frame layout(float width, float height) {
        var side = width >= WIDE ? SIDE_WIDE : SIDE_SMALL;
        var guideRight = side + MARGIN + GuideWindow.width(width - side - 2 * MARGIN);
        var guideBeside = guide.isOpen() && width - guideRight >= GUIDE_ROOM;
        var mainLeft = guideBeside ? guideRight : side;
        var mainWidth = width - mainLeft;
        var wideFixed = wideBarFixed();
        var wideBar = mainWidth - 2 * MARGIN >= wideFixed + TITLE_LEAST;
        var barWidth =
                (float) Math.floor(wideBar ? Math.min(mainWidth - 2 * MARGIN, BAR_MOST) : mainWidth - 2 * MARGIN);
        var barTop = height - MARGIN - BAR_HEIGHT;
        var mapTop = MARGIN + HudWidgets.CONTROL_HEIGHT + MARGIN;
        var mapBottom = barTop - MARGIN;
        var map = installation == null
                ? null
                : TileMapPicker.layoutByPixel(
                        installation.tiles(), mapTexture(), mainWidth - 2 * MARGIN, mapBottom - mapTop);
        var column = (float) Math.ceil(Math.max(barWidth, map == null ? 0 : map.width()));
        var asideWidth = (float) Math.floor(Math.min(ASIDE_MOST, mainWidth - 3 * MARGIN - column));
        var aside = asideWidth >= ASIDE_LEAST && !asides.isEmpty();
        var barLeft = aside ? mainLeft + MARGIN : (float) Math.floor(mainLeft + (mainWidth - barWidth) * .5f);
        return new Frame(
                width,
                height,
                side,
                guideBeside,
                mainLeft,
                aside ? barLeft : mainLeft + MARGIN,
                mapTop,
                aside ? barLeft + column : width - MARGIN,
                mapBottom,
                barLeft,
                barTop,
                aside ? column : barWidth,
                wideBar,
                map,
                aside,
                width - MARGIN - asideWidth,
                asideWidth,
                width - MARGIN - switcherWidth());
    }

    private String[] switcherLabels() {
        var labels = new String[asides.size()];
        for (var index = 0; index < asides.size(); index++) {
            labels[index] = asides.get(index).getLabel();
        }
        return labels;
    }

    private float switcherWidth() {
        return GeodataWidgets.fittedSegmentedWidth(switcherLabels());
    }

    private @Nullable StartAside chosenAside() {
        if (asideChoice == null) {
            asideChoice = readAsideChoice();
        }
        for (var panel : asides) {
            if (panel.getLabel().equals(asideChoice)) {
                return panel;
            }
        }
        return asides.isEmpty() ? null : asides.getFirst();
    }

    private String readAsideChoice() {
        try {
            var word = Files.readString(loader.getConfigDirectory().resolve(ASIDE_FILE))
                    .strip();
            if (asides.stream().anyMatch(panel -> panel.getLabel().equals(word))) {
                return word;
            }
        } catch (IOException | RuntimeException noChoice) {
        }
        return ASIDE_FIRST;
    }

    private void chooseAside(String word) {
        if (word.equals(asideChoice)) {
            return;
        }
        asideChoice = word;
        try {
            Files.writeString(loader.getConfigDirectory().resolve(ASIDE_FILE), word + "\n");
        } catch (IOException failure) {
            showError("Cannot keep the panel of the right column: " + failure.getMessage());
        }
    }

    private void drawSwitcher(Frame frame) {
        if (!frame.aside()) {
            return;
        }
        var labels = switcherLabels();
        var index = asides.indexOf(chosenAside());
        var left = frame.switcherLeft();
        var top = MARGIN + 1;
        ImGui.setCursorScreenPos(left, top);
        var picked = GeodataWidgets.fittedSegmented("start-aside", labels, index);
        mark("main.aside-switcher", left, top, left + switcherWidth(), top + HudWidgets.CONTROL_HEIGHT - 2);
        var segmentLeft = left;
        for (var label : labels) {
            var segmentRight = segmentLeft + GeodataWidgets.fittedSegmentedWidth(new String[] {label});
            mark("switcher." + label, segmentLeft, top, segmentRight, top + HudWidgets.CONTROL_HEIGHT - 2);
            segmentLeft = segmentRight;
        }
        if (picked >= 0 && picked != index) {
            chooseAside(labels[picked]);
        }
    }

    private void drawAside(Frame frame) {
        var panel = shownAside;
        if (!frame.aside() || panel == null || device == null || renderer == null) {
            return;
        }
        var top = frame.mapTop();
        var height = frame.height() - MARGIN - top;
        if (panel instanceof ClientAside client) {
            client.setSavedClients(savedClientFacts());
            if (client.takeSettingsRequest() && installation != null) {
                requestOptions = true;
                requestClientPage = true;
            }
            if (client.takeTablesRequest() && installation != null) {
                requestOptions = true;
                requestTablesPage = true;
            }
        }
        if (panel instanceof RadioAside radio) {
            radio.setMusic(music);
        }
        if (panel instanceof PlaceAside place) {
            place.setMusic(music);
            place.setMapImage(mapTexture(), installation == null ? null : TileBounds.of(installation.tiles()));
            var asked = place.takePlaceRequest();
            if (asked != null && installation != null) {
                var tile = StartPlaces.tileOf(asked);
                take(new StartPlaces.Result(asked, tile, installation.tiles().contains(tile)));
            }
        }
        var tiles = installation == null ? List.<String>of() : selection.tiles();
        var title = installation == null || tiles.isEmpty()
                ? ""
                : places.describe(tiles, selection.place(), installation.tiles().size());
        var context = new StartAside.Context(
                loader,
                fonts,
                device,
                renderer,
                installation,
                tiles,
                title,
                installation == null ? null : pointer.getUnder());
        ImGui.setCursorScreenPos(frame.asideLeft(), top);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild(
                "##start-aside/" + panel.getLabel(), frame.asideWidth(), height, 0, ImGuiWindowFlags.NoBackground);
        ImGui.popStyleVar();
        panel.draw(context, ImGui.getContentRegionAvailX(), ImGui.getContentRegionAvailY());
        ImGui.endChild();
        mark("main.aside", frame.asideLeft(), top, frame.asideLeft() + frame.asideWidth(), top + height);
    }

    private double musicVolume() {
        try {
            return loader.load().viewer().world().music().volume();
        } catch (RuntimeException unreadable) {
            return 1;
        }
    }

    private List<ClientAside.Saved> savedClientFacts() {
        var saved = savedClients();
        var facts = new ArrayList<ClientAside.Saved>();
        for (var path : saved) {
            var found = inspect(path);
            facts.add(new ClientAside.Saved(
                    path,
                    ClientFolders.label(path, saved),
                    found.client() == null ? null : found.client().tiles(),
                    found.namedMaps().size(),
                    found.problem()));
        }
        return facts;
    }

    private void followAside(Frame frame) {
        var chosen = frame.aside() ? chosenAside() : null;
        if (shownAside != null && shownAside != chosen) {
            shownAside.close();
        }
        shownAside = chosen;
    }

    private void releaseAside() {
        var panel = shownAside;
        shownAside = null;
        if (panel != null) {
            panel.close();
        }
    }

    private void drawSide(Frame frame) {
        ImGui.setNextWindowPos(0, 0);
        ImGui.setNextWindowSize(frame.side(), frame.height());
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, MARGIN, MARGIN);
        ImGui.begin(
                "##start-side",
                ImGuiWindowFlags.NoDecoration
                        | ImGuiWindowFlags.NoMove
                        | ImGuiWindowFlags.NoSavedSettings
                        | ImGuiWindowFlags.NoBringToFrontOnFocus
                        | ImGuiWindowFlags.NoScrollbar
                        | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar(3);
        Motion.open(Motion.appear(MENU_SHOWN, .5f), 0, 12, .99f);
        var draw = ImGui.getWindowDrawList();
        draw.addLine(frame.side() - .5f, 0, frame.side() - .5f, frame.height(), GeodataWidgets.DIVIDER, 1);
        mark("side", 0, 0, frame.side(), frame.height());
        drawTitleRow(frame);
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var appearing = focusSearch && !ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId);
        ImGui.pushStyleVar(
                ImGuiStyleVar.FramePadding,
                GeodataWidgets.LINE_INSET,
                ImGui.getStyle().getFramePaddingY());
        GeodataWidgets.searchField(
                "##start-search", "Find a place or tile", query, ImGui.getContentRegionAvailX(), appearing);
        ImGui.popStyleVar();
        if (appearing) {
            focusSearch = false;
        }
        searchActive = ImGui.isItemActive();
        searchId = ImGui.getItemID();
        mark("side.search");
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var bottomRow = HudWidgets.CONTROL_HEIGHT + MARGIN;
        var bodyHeight = frame.height() - MARGIN - bottomRow - ImGui.getCursorPosY();
        ImGui.beginChild("##start-side-body", 0, Math.max(1, bodyHeight), ImGuiChildFlags.NavFlattened, 0);
        mark(
                "side.body",
                ImGui.getWindowPosX(),
                ImGui.getWindowPosY(),
                ImGui.getWindowPosX() + ImGui.getWindowWidth(),
                ImGui.getWindowPosY() + ImGui.getWindowHeight());
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 7, 2);
        if (query.get().isBlank()) {
            drawClients();
            drawRecent();
            drawMore();
        } else {
            drawResults();
        }
        ImGui.popStyleVar();
        ImGui.endChild();
        drawBottomRow(frame);
        ImGui.end();
    }

    private void drawTitleRow(Frame frame) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        if (branding != null) {
            branding.mark(draw, x, y + 2, 24);
        }
        ImGui.setCursorScreenPos(x + 34, y + (HudWidgets.CONTROL_HEIGHT - 20) * .5f - 1);
        ImGui.pushFont(fonts.getHeadingFont(20), 20);
        ImGui.textUnformatted("Cruma");
        ImGui.popFont();
        var right = frame.side() - MARGIN;
        ImGui.setCursorScreenPos(right - 2 * HudWidgets.CONTROL_HEIGHT - 4, y);
        if (HudWidgets.iconButton("guide", Glyph.BOOK_OPEN, guide.isOpen(), HudWidgets.TEXT)) {
            if (guide.isOpen()) {
                guide.close();
            } else {
                guide.open(GUIDE_START);
            }
        }
        guidePlace[0] = ImGui.getItemRectMinX();
        guidePlace[1] = ImGui.getItemRectMinY();
        guidePlace[2] = ImGui.getItemRectMaxX();
        guidePlace[3] = ImGui.getItemRectMaxY();
        mark("side.guide");
        GeodataWidgets.tip(
                "What a client and a tile are and how to open a world, in the Guide, with pictures and a search.");
        ImGui.setCursorScreenPos(right - HudWidgets.CONTROL_HEIGHT, y);
        if (HudWidgets.iconButton("options", Glyph.SETTINGS, false, HudWidgets.TEXT)) {
            requestOptions = true;
        }
        mark("side.options");
        GeodataWidgets.tip("Settings");
        ImGui.setCursorScreenPos(x, y + HudWidgets.CONTROL_HEIGHT);
        ImGui.dummy(0, 0);
    }

    private void drawClients() {
        sideSection("CLIENTS", 0);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var saved = savedClients();
        for (var path : saved) {
            var found = inspect(path);
            var chosen = installation != null && installation.root().equals(path);
            var name = ClientFolders.label(path, saved);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var rowWidth = ImGui.getContentRegionAvailX();
            var over = ImGui.isWindowHovered(ImGuiHoveredFlags.AllowWhenBlockedByActiveItem)
                    && ImGui.isMouseHoveringRect(x, y, x + rowWidth, y + GeodataWidgets.LINE_ROW);
            var note = found.client() == null || over
                    ? ""
                    : tilesWord(found.client().tiles().size());
            var look = found.client() == null ? LineLook.FAINT : chosen ? LineLook.SELECTED : LineLook.PLAIN;
            var gear = look != LineLook.FAINT && (chosen || over);
            var buttonTop = y + (GeodataWidgets.LINE_ROW - HudWidgets.CONTROL_HEIGHT) * .5f;
            var gearLeft = x + rowWidth - HudWidgets.CONTROL_HEIGHT - 2;
            var removeLeft = gear ? gearLeft - 4 - HudWidgets.CONTROL_HEIGHT : gearLeft;
            var removing = over && recent != null;
            var buttonsLeft = removing ? removeLeft : gear ? gearLeft : x + rowWidth;
            ImGui.setNextItemAllowOverlap();
            if (GeodataWidgets.lineRow(
                            "client/" + path,
                            null,
                            name,
                            note,
                            look,
                            false,
                            buttonsLeft < x + rowWidth ? x + rowWidth - buttonsLeft - GeodataWidgets.LINE_INSET + 4 : 0)
                    && !chosen) {
                chooseClient(path);
            }
            mark("side.client/" + name);
            if (look == LineLook.FAINT
                    && over
                    && ImGui.isMouseClicked(0)
                    && ImGui.getMousePosX() < x + rowWidth - HudWidgets.CONTROL_HEIGHT - 4) {
                retryClient(path);
            }
            if (ImGui.isItemHovered()) {
                HudWidgets.showTooltip(found.client() == null ? path + "\n" + found.problem() : path.toString());
            }
            if (gear) {
                ImGui.setCursorScreenPos(gearLeft, buttonTop);
                if (HudWidgets.iconButton("client-settings/" + path, Glyph.SETTINGS, false, HudWidgets.MUTED)) {
                    if (!chosen) {
                        chooseClient(path);
                    }
                    if (installation != null && installation.root().equals(path)) {
                        requestOptions = true;
                        requestClientPage = true;
                    }
                }
                mark("side.client-settings/" + name);
                GeodataWidgets.tip("Settings of this client");
            }
            if (removing) {
                ImGui.setCursorScreenPos(removeLeft, buttonTop);
                if (HudWidgets.iconButton("remove-client/" + path, Glyph.CLOSE, false, HudWidgets.MUTED)) {
                    removeClient(path);
                }
                mark("side.client-remove/" + name);
                GeodataWidgets.tip("Remove this shortcut. Game files are kept.");
            }
            if (gear || removing) {
                ImGui.setCursorScreenPos(x, y + GeodataWidgets.LINE_ROW);
                ImGui.dummy(0, 0);
            }
        }
        if (GeodataWidgets.lineRow(
                "add-client", Glyph.PLUS, "Add client…", "or drop its folder", LineLook.PLAIN, false)) {
            addClient();
        }
        mark("side.add-client");
        GeodataWidgets.tip("Choose the folder of a Lineage II client, or drop it onto this window.");
    }

    private void drawRecent() {
        if (sessions == null || sessions.getSessions().isEmpty()) {
            return;
        }
        sideSection("RECENT", GeodataWidgets.SECTION);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var shown = sessions.getSessions();
        for (var index = 0; index < Math.min(RECENT_SHOWN, shown.size()); index++) {
            var session = shown.get(index);
            var problem = findProblem(session);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var rowWidth = ImGui.getContentRegionAvailX();
            var rowHeight = Math.max(32 + 8, 2 * ImGui.getFontSize() + 12);
            var over = ImGui.isWindowHovered(ImGuiHoveredFlags.AllowWhenBlockedByActiveItem)
                    && ImGui.isMouseHoveringRect(x, y, x + rowWidth, y + rowHeight);
            ImGui.setNextItemAllowOverlap();
            var clicked = GeodataWidgets.thumbnailRow(
                    "session/" + index,
                    sessionPicture(session),
                    session.title(),
                    describe(session),
                    32,
                    false,
                    !problem.isEmpty(),
                    over ? rowWidth - HudWidgets.CONTROL_HEIGHT - 4 : rowWidth,
                    GeodataWidgets.LINE_INSET);
            mark("side.session/" + index, x, y, x + rowWidth, y + rowHeight);
            if (over && ImGui.getMousePosX() < x + rowWidth - HudWidgets.CONTROL_HEIGHT - 4) {
                HudWidgets.showTooltip(problem.isEmpty() ? "Click to open it again at once." : problem);
            }
            if (clicked) {
                startSession(session);
            } else if (!problem.isEmpty()
                    && over
                    && ImGui.isMouseClicked(0)
                    && ImGui.getMousePosX() < x + rowWidth - HudWidgets.CONTROL_HEIGHT - 4) {
                retrySession(session);
            }
            if (over) {
                ImGui.setCursorScreenPos(x + rowWidth - HudWidgets.CONTROL_HEIGHT - 2, y + (rowHeight - 28) * .5f);
                if (HudWidgets.iconButton("remove-session/" + index, Glyph.CLOSE, false, HudWidgets.MUTED)) {
                    removeSession(session);
                }
                GeodataWidgets.tip("Remove this row from Recent. The client and its files stay.");
                ImGui.setCursorScreenPos(x, y + rowHeight);
                ImGui.dummy(0, 0);
            }
        }
    }

    private void drawMore() {
        sideSection("MORE", GeodataWidgets.SECTION);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var ready = installation != null;
        var look = ready ? LineLook.PLAIN : LineLook.FAINT;
        var otherLook = ready && namedMaps.isEmpty() ? LineLook.FAINT : look;
        if (GeodataWidgets.lineRow("other-maps", Glyph.PLACE, "Other maps", "", otherLook, true)) {
            ImGui.openPopup("##other-maps");
        }
        mark("side.other-maps");
        GeodataWidgets.tip(
                !ready
                        ? "Add a client first."
                        : namedMaps.isEmpty()
                                ? NO_NAMED_MAPS
                                : "Open a map that is not a world tile, such as a lobby.");
        var menuWidth = 260f;
        if (beginMenuAt("##other-maps", menuWidth, HudWidgets.menuHeight(namedMaps.size(), 0), "side.other-maps")) {
            for (var name : namedMaps) {
                if (HudWidgets.menuRow("named-map/" + name, Glyph.PLACE, name, "")) {
                    startNamedMap(name);
                }
                mark("menu.named-map/" + name);
            }
            HudWidgets.endMenu();
        }
        if (GeodataWidgets.lineRow("effects", Glyph.SPARKLES, "Effects", "", look, false)) {
            startEffects();
        }
        mark("side.effects");
        GeodataWidgets.tip(ready ? "Play the client's effects and skills, without the world." : "Add a client first.");
        if (GeodataWidgets.lineRow("tools", Glyph.WRENCH, "Tools", "", look, true)) {
            ImGui.openPopup("##tools");
        }
        mark("side.tools");
        if (!ready) {
            GeodataWidgets.tip("Add a client first.");
        }
        var image = mapTexture() != 0;
        if (beginMenuAt("##tools", menuWidth, HudWidgets.menuHeight(image ? 5 : 4, image ? 1 : 0), "side.tools")) {
            drawToolRows();
            if (HudWidgets.menuRow("export-other-map", Glyph.FILE, "Export other map…", "")) {
                requestExportMaps = true;
            }
            mark("menu.export-other-map");
            GeodataWidgets.tip("Save a map that is not a world tile, such as a lobby, as glTF.");
            if (image) {
                HudWidgets.menuRule();
                if (HudWidgets.menuRow("rebuild-map-image", Glyph.RELOAD, "Rebuild map image", "")) {
                    createMapImage();
                }
                GeodataWidgets.tip("Draw the map image of this client again, from its maps as they are now.");
            }
            HudWidgets.endMenu();
        }
        if (requestExportMaps && !ImGui.isPopupOpen("##tools")) {
            requestExportMaps = false;
            ImGui.openPopup("##export-maps");
        }
        if (beginMenuAt(
                "##export-maps", menuWidth, HudWidgets.menuHeight(Math.max(1, namedMaps.size()), 0), "side.tools")) {
            if (namedMaps.isEmpty()) {
                HudWidgets.menuRow("export-map-none", Glyph.FILE, "No other maps", "", false);
                GeodataWidgets.tip(NO_NAMED_MAPS);
            }
            for (var name : namedMaps) {
                if (HudWidgets.menuRow("export-map/" + name, Glyph.FILE, name, "") && installation != null) {
                    locationExport.openMap(installation, name);
                }
                mark("menu.export-map/" + name);
            }
            HudWidgets.endMenu();
        }
    }

    private void drawToolRows() {
        if (HudWidgets.menuRow("map-tiles", Glyph.COPY, "Copy, move or swap tiles…", "") && installation != null) {
            var folders = npcFolders(installation);
            var others = recent == null
                    ? List.<Path>of()
                    : recent.getEntries().stream().map(Entry::path).toList();
            tileOperations.open(installation, selection.tiles(), folders[0], folders[1], others);
        }
        mark("menu.map-tiles");
        GeodataWidgets.tip(
                "Copy, move or swap whole map files of this client. Every change is checked first and can be undone.");
        if (HudWidgets.menuRow("export-location", Glyph.FILE, "Export location…", "") && installation != null) {
            var folders = npcFolders(installation);
            locationExport.open(installation, selection.tiles(), folders[0], folders[1], exportPlaces());
        }
        mark("menu.export-location");
        GeodataWidgets.tip("Save map tiles as glTF for Blender, Unity, Unreal and other 3D programs.");
        if (HudWidgets.menuRow("export-npc", Glyph.CARD, "Export NPC…", "") && installation != null) {
            npcExport.open(installation, npcFolders(installation)[0]);
        }
        mark("menu.export-npc");
        GeodataWidgets.tip("Save one NPC as glTF with its skeleton and animations, for Blender and other 3D programs.");
    }

    private void drawResults() {
        sideSection("PLACES AND TILES", 0);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var results = searchResults();
        if (results.isEmpty()) {
            GeodataWidgets.hint("Nothing found. Try a town, such as Giran, or a tile, such as 22_22.");
            return;
        }
        var marked = false;
        for (var index = 0; index < results.size(); index++) {
            var found = results.get(index);
            var look = !found.available() ? LineLook.FAINT : marked || !searchActive ? LineLook.PLAIN : LineLook.MARKED;
            marked |= found.available();
            var note = !found.available() ? "not in this client" : found.place() == null ? "" : found.tile();
            if (GeodataWidgets.lineRow(
                    "result/" + index,
                    found.place() == null ? Glyph.GLOBE : Glyph.PLACE,
                    found.getTitle(),
                    note,
                    look,
                    false)) {
                take(found);
            }
            mark("side.result/" + index);
            if (!found.available()) {
                GeodataWidgets.tip("This client has no tile " + found.tile() + ".");
            }
        }
    }

    private void drawBottomRow(Frame frame) {
        var top = frame.height() - MARGIN - HudWidgets.CONTROL_HEIGHT;
        ImGui.setCursorScreenPos(MARGIN, top);
        var logsWidth = ImGui.calcTextSizeX("Logs") + 2 * GeodataWidgets.LINE_INSET;
        var exitWidth = ImGui.calcTextSizeX("Exit") + 2 * GeodataWidgets.LINE_INSET;
        if (HudWidgets.button("open-logs", null, "Logs", logsWidth, HudWidgets.CONTROL_HEIGHT, false, false)) {
            openLogs();
        }
        mark("side.logs");
        GeodataWidgets.tip(
                "Open the folder with Cruma log files and crash reports. Attach the newest files to a bug report.");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        if (HudWidgets.button("exit-launcher", null, "Exit", exitWidth, HudWidgets.CONTROL_HEIGHT, false, false)) {
            cancelled = true;
        }
        mark("side.exit");
    }

    private void drawMain(Frame frame) {
        ImGui.setNextWindowPos(frame.mainLeft(), 0);
        ImGui.setNextWindowSize(frame.width() - frame.mainLeft(), frame.height());
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.begin(
                "##start-main",
                ImGuiWindowFlags.NoDecoration
                        | ImGuiWindowFlags.NoMove
                        | ImGuiWindowFlags.NoSavedSettings
                        | ImGuiWindowFlags.NoBringToFrontOnFocus
                        | ImGuiWindowFlags.NoFocusOnAppearing
                        | ImGuiWindowFlags.NoBackground
                        | ImGuiWindowFlags.NoScrollbar
                        | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar(3);
        Motion.open(Motion.appear(MENU_SHOWN, .5f), 0, 12, .99f);
        mark("main", frame.mainLeft(), 0, frame.width(), frame.height());
        if (installation == null) {
            drawFirstRun(frame);
        } else {
            drawTopRow(frame);
            drawMap(frame);
            drawBar(frame);
        }
        drawSwitcher(frame);
        drawAside(frame);
        drawDialogs(frame);
        ImGui.end();
    }

    private void drawFirstRun(Frame frame) {
        var detail = "The folder with Maps, Textures, StaticMeshes and system.";
        var spanLeft = frame.aside() ? frame.barLeft() : frame.mainLeft();
        var span = frame.aside() ? frame.barWidth() : frame.width() - frame.mainLeft();
        var room = span - (frame.aside() ? 0 : 4 * MARGIN);
        var cardWidth = (float) Math.floor(Math.min(520, room));
        var cardHeight = GeodataWidgets.stackedCalloutHeight(detail, cardWidth);
        var left = (float) Math.floor(spanLeft + (span - cardWidth) * .5f);
        var top = (float) Math.floor((frame.height() - cardHeight) * .5f);
        ImGui.setCursorScreenPos(left, top);
        if (GeodataWidgets.stackedCallout(
                "choose-folder",
                Glyph.FOLDER,
                "Drop your Lineage II folder here",
                detail,
                "Choose folder…",
                cardWidth)) {
            addClient();
        }
        mark("main.callout", left, top, left + cardWidth, top + cardHeight);
    }

    private void drawTopRow(Frame frame) {
        var top = MARGIN;
        var right = frame.barLeft() + frame.barWidth();
        var worldsWidth = installation.hasWorldChoice()
                ? VARIANT_SEGMENT_WIDTH * installation.worlds().size()
                : 0;
        var worldsRight = frame.barLeft() + (worldsWidth > 0 ? worldsWidth + MARGIN : 0);
        var texture = mapTexture();
        if (texture == 0) {
            var label = "Create map image";
            var buttonWidth = ImGui.calcTextSizeX(label) + 28;
            ImGui.setCursorScreenPos(right - buttonWidth, top);
            if (HudWidgets.button(
                    "create-map-preview", null, label, buttonWidth, HudWidgets.CONTROL_HEIGHT, false, true)) {
                createMapImage();
            }
            mark("main.create-map-image");
            GeodataWidgets.tip("Draw every tile of this client from above into one picture, for this map.");
            right -= buttonWidth + GeodataWidgets.ROW;
            var words = "No map image";
            var wordsWidth = ImGui.calcTextSizeX(words);
            if (right - wordsWidth >= worldsRight) {
                var wordsTop = top + (HudWidgets.CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1;
                ImGui.getWindowDrawList().addText(right - wordsWidth, wordsTop, HudWidgets.MUTED, words);
                mark("main.no-map-image", right - wordsWidth, wordsTop, right, wordsTop + ImGui.getFontSize());
                var problem = mapPreview == null ? "" : mapPreview.getProblem();
                if (!problem.isEmpty()
                        && ImGui.isWindowHovered()
                        && ImGui.isMouseHoveringRect(right - wordsWidth, top, right, top + HudWidgets.CONTROL_HEIGHT)) {
                    HudWidgets.showTooltip(problem);
                }
                right -= wordsWidth + MARGIN;
            }
        }
        var hintsLeft = frame.barLeft();
        if (worldsWidth > 0) {
            var left = hintsLeft;
            ImGui.setCursorScreenPos(left, top + 1);
            drawVariant(left, worldsWidth);
            mark("main.variant", left, top + 1, left + worldsWidth, top + 27);
            hintsLeft = worldsRight;
        }
        var hintsWidth = GeodataWidgets.hintStripWidth("MAP", HINTS);
        if (hintsLeft + hintsWidth + MARGIN <= right) {
            var viewport = new Viewport(hintsLeft - MARGIN, top, right, top + HudWidgets.CONTROL_HEIGHT);
            GeodataWidgets.hintStrip(ImGui.getWindowDrawList(), viewport, "MAP", HINTS, new ArrayList<>());
            mark("main.hints", hintsLeft, top, hintsLeft + hintsWidth, top + HudWidgets.CONTROL_HEIGHT);
        }
    }

    private void drawVariant(float left, float width) {
        var top = ImGui.getCursorScreenPosY();
        var variants = installation.worlds();
        var labels = new String[variants.size()];
        for (var index = 0; index < labels.length; index++) {
            labels[index] = variants.get(index).getLabel();
        }
        var picked =
                GeodataWidgets.segmented("client-variant", labels, variants.indexOf(installation.variant()), width);
        if (ImGui.isMouseHoveringRect(left, top, left + width, top + 26) && ImGui.isWindowHovered()) {
            HudWidgets.showTooltip(describeWorld(installation.variant()));
        }
        if (picked >= 0 && variants.get(picked) != installation.variant()) {
            requestedVariant = variants.get(picked);
        }
    }

    private void takeVariantRequest() {
        var variant = requestedVariant;
        requestedVariant = null;
        if (variant != null
                && installation != null
                && installation.worlds().contains(variant)
                && variant != installation.variant()) {
            chooseVariant(variant);
        }
    }

    private void chooseVariant(WorldVariant variant) {
        try {
            var model = new OptionsModel(loader, installation.root());
            model.set(ClientProfiles.VARIANT, variant.getKey());
            model.save();
            configured = loader.load();
            inspected.clear();
            var other = installation.withVariant(variant);
            selection = selection.switchClient(other.tiles(), savedTile(other.root()), configuredTiles(other));
            installation = other;
            zoom = new Zoom();
        } catch (IOException | RuntimeException failure) {
            showError("Cannot save the world of this client: " + failure.getMessage());
        }
    }

    private void drawMap(Frame frame) {
        var texture = mapTexture();
        var roomWidth = frame.mapRight() - frame.mapLeft();
        var roomHeight = frame.mapBottom() - frame.mapTop();
        var layout = Objects.requireNonNull(frame.map());
        mapLayout = layout;
        var left = (float) Math.floor(frame.mapLeft() + (roomWidth - layout.width()) * .5f);
        var top = (float) Math.floor(frame.mapTop() + (roomHeight - layout.height()) * .5f);
        ImGui.setCursorScreenPos(left, top);
        var gesture = TileMapPicker.drawStart(
                installation.tiles(),
                selection.tiles(),
                selection.focus(),
                places.getPlaces(),
                layout,
                texture,
                zoom,
                pointer);
        mark("main.map", left, top, left + layout.width(), top + layout.height());
        if (gesture != null) {
            take(gesture);
        }
    }

    private void drawBar(Frame frame) {
        var draw = ImGui.getWindowDrawList();
        var left = frame.barLeft();
        var top = frame.barTop();
        var right = left + frame.barWidth();
        var bottom = top + BAR_HEIGHT;
        draw.addRectFilled(left, top, right, bottom, HudWidgets.BACKGROUND, HudWidgets.PANEL_RADIUS);
        draw.addRect(left, top, right, bottom, GeodataWidgets.DIVIDER, HudWidgets.PANEL_RADIUS, 0, 1);
        mark("main.bar", left, top, right, bottom);
        var tiles = selection.tiles();
        var controlTop = top + (BAR_HEIGHT - HudWidgets.CONTROL_HEIGHT) * .5f;
        var x = right - MARGIN;
        x -= HudWidgets.CONTROL_HEIGHT;
        if (!tiles.isEmpty()) {
            ImGui.setCursorScreenPos(x, controlTop);
            if (HudWidgets.iconButton("selection-more", Glyph.MORE, false, HudWidgets.TEXT)) {
                ImGui.openPopup("##selection-more");
            }
            mark("bar.more");
            GeodataWidgets.tip("More: capture, copy and export");
            drawMoreMenu(frame.wideBar());
        }
        x -= GeodataWidgets.ROW + OPEN_WIDTH;
        ImGui.setCursorScreenPos(x, top + (BAR_HEIGHT - OPEN_HEIGHT) * .5f);
        ImGui.beginDisabled(tiles.isEmpty());
        if (HudWidgets.keyButton("open-world", Glyph.PLAY, "Open", Glyph.ENTER, OPEN_WIDTH, OPEN_HEIGHT, true, true)) {
            open();
        }
        ImGui.endDisabled();
        mark("bar.open");
        GeodataWidgets.tip(
                editing ? "Open the chosen tiles in Edit (Enter)." : "Open the chosen tiles in the Viewer (Enter).");
        if (tiles.isEmpty()) {
            var line = "Click a tile, or drag over several.";
            var textTop = top + (BAR_HEIGHT - ImGui.getFontSize()) * .5f - 1;
            var shown = HudWidgets.truncateToWidth(line, x - GeodataWidgets.ROW - left - MARGIN);
            draw.addText(left + MARGIN, textTop, HudWidgets.MUTED, shown);
            mark(
                    "bar.title",
                    left + MARGIN,
                    textTop,
                    left + MARGIN + ImGui.calcTextSizeX(shown),
                    textTop + ImGui.getFontSize());
            return;
        }
        x -= GeodataWidgets.ROW + MODES_WIDTH;
        ImGui.setCursorScreenPos(x, controlTop + 1);
        var modesLeft = x;
        var mode = GeodataWidgets.segmented("view-edit", new String[] {"View", "Edit"}, editing ? 1 : 0, MODES_WIDTH);
        if (mode >= 0) {
            editing = mode == 1;
        }
        mark("bar.modes");
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(
                    GeodataWidgets.hoveredSegment(modesLeft, MODES_WIDTH, 2) == 1
                            ? "Edit: move, place and paint in the client's maps, and save them back."
                            : "View: explore the world and inspect its objects.");
        }
        if (frame.wideBar()) {
            x -= MARGIN + SIZES_WIDTH;
            ImGui.setCursorScreenPos(x, controlTop + 1);
            var sizes = Size.values();
            var labels = new String[sizes.length];
            for (var index = 0; index < sizes.length; index++) {
                labels[index] = sizes[index].getLabel();
            }
            var shownSize = selection.findSize(installation.tiles());
            var picked = GeodataWidgets.segmented(
                    "sizes", labels, shownSize == null ? -1 : shownSize.ordinal(), SIZES_WIDTH);
            if (picked >= 0) {
                selection = selection.resize(sizes[picked], installation.tiles());
            }
            mark("bar.sizes");
            GeodataWidgets.tip("How much to load around the chosen tile.");
        }
        var textLeft = left + MARGIN;
        if (frame.wideBar()) {
            GeodataWidgets.picture(
                    draw, textLeft, top + (BAR_HEIGHT - BAR_PICTURE) * .5f, BAR_PICTURE, cutOf(tiles), false);
            mark(
                    "bar.picture",
                    textLeft,
                    top + (BAR_HEIGHT - BAR_PICTURE) * .5f,
                    textLeft + BAR_PICTURE,
                    top + (BAR_HEIGHT + BAR_PICTURE) * .5f);
            textLeft += BAR_PICTURE + MARGIN;
        }
        var textRight = x - MARGIN;
        var title =
                places.describe(tiles, selection.place(), installation.tiles().size());
        var detail = StartPlaces.detail(tiles, installation.tiles().size());
        var lineGap = (BAR_HEIGHT - 15 - ImGui.getFontSize()) / 3;
        ImGui.pushFont(fonts.getHeadingFont(15), 15);
        var shownTitle = HudWidgets.truncateToWidth(title, textRight - textLeft);
        draw.addText(textLeft, top + lineGap, HudWidgets.TEXT, shownTitle);
        ImGui.popFont();
        var shownDetail = HudWidgets.truncateToWidth(detail, textRight - textLeft);
        draw.addText(textLeft, top + 2 * lineGap + 15, HudWidgets.MUTED, shownDetail);
        mark("bar.title", textLeft, top + lineGap, textRight, top + 2 * lineGap + 15 + ImGui.getFontSize());
        if ((!shownTitle.equals(title) || !shownDetail.equals(detail))
                && ImGui.isWindowHovered()
                && ImGui.isMouseHoveringRect(textLeft, top, textRight, bottom)) {
            HudWidgets.showTooltip(title + "\n" + detail);
        }
    }

    private void drawMoreMenu(boolean wideBar) {
        var rows = (wideBar ? 0 : 4) + 4;
        var rules = (wideBar ? 0 : 1) + 1;
        if (!beginMenuAt("##selection-more", 280, HudWidgets.menuHeight(rows, rules), "bar.more")) {
            return;
        }
        if (!wideBar) {
            var shownSize = selection.findSize(installation.tiles());
            for (var size : Size.values()) {
                if (HudWidgets.menuRow(
                        "size/" + size.name(), size == shownSize ? Glyph.CHECK : null, size.getMenuLabel(), "")) {
                    selection = selection.resize(size, installation.tiles());
                }
                mark("menu.size/" + size.name());
            }
            HudWidgets.menuRule();
        }
        if (HudWidgets.menuRow("capture", Glyph.CAMERA, "Capture map images…", "")) {
            requestCapture = true;
        }
        mark("menu.capture");
        GeodataWidgets.tip("Export tile images and a radar map of the chosen tiles.");
        HudWidgets.menuRule();
        drawToolRows();
        HudWidgets.endMenu();
    }

    private void drawDialogs(Frame frame) {
        var width = frame.width();
        var height = frame.height();
        if (requestCapture) {
            requestCapture = false;
            if (installation != null) {
                try {
                    captureDialog.open(installation.root(), installation.tiles());
                    ImGui.openPopup(CaptureDialog.TITLE);
                } catch (IOException | RuntimeException failure) {
                    showError("Cannot open Capture map images: " + failure.getMessage());
                }
            }
        }
        drawCapture(width, height);
        if (requestBrowser) {
            requestBrowser = false;
            var start = installation == null
                    ? Path.of(java.lang.System.getProperty("user.home"))
                    : installation.root().getParent();
            navigate(start == null ? Path.of(java.lang.System.getProperty("user.home")) : start);
            ImGui.openPopup("Choose game folder");
        }
        drawBrowser(width, height);
        if (requestOptions) {
            try {
                options = new LaunchOptions(
                        loader, LaunchOptions.Host.MENU, installation == null ? null : installation.root());
                options.selectTiles(installation == null ? List.of() : selection.tiles());
                if (requestClientPage) {
                    options.showClientPage();
                }
                if (requestTablesPage) {
                    options.showTablesPage();
                }
                ImGui.openPopup(LaunchOptions.TITLE);
            } catch (IOException | ConfigurationProblem failure) {
                showError("Cannot open settings: " + failure.getMessage());
            }
            requestOptions = false;
            requestClientPage = false;
            requestTablesPage = false;
        }
        if (installation != null) {
            var open = ImGui.isPopupOpen(TileOperationsDialog.TITLE);
            tileOperations.draw(
                    fonts, open && mapPreview != null ? mapPreview.getTextureFor(tileOperations.getShownWorld()) : 0);
            npcExport.draw(fonts);
            var exportOpen = ImGui.isPopupOpen(LocationExportDialog.TITLE);
            locationExport.draw(fonts, exportOpen && mapPreview != null ? mapPreview.getTextureFor(installation) : 0);
            if (tileOperations.takeChanged()) {
                reread();
                if (installation != null) {
                    tileOperations.refresh(installation);
                }
            }
            var mapUpdate = tileOperations.takeMapUpdate();
            if (mapUpdate != null && installation != null) {
                updateMapImage(mapUpdate);
            }
            var openAt = tileOperations.takeOpenWorld();
            if (openAt != null && installation != null && installation.tiles().contains(openAt)) {
                selection = selection.click(openAt);
                open();
            }
        }
        if (options != null) {
            options.setAutomaticScalePercent(automaticScalePercent);
        }
        if (options != null && !options.draw(width, height, fonts)) {
            if (options.isSaved()) {
                configured = loader.load();
                Motion.setEnabled(configured.ui().animations());
                places = placesOf(configured);
                reread();
                selection = selection.keepPlaceIn(places);
            }
            var mapImage = options.isMapImageRequested();
            options = null;
            if (mapImage) {
                createMapImage();
            }
        }
    }

    private void drawCapture(float width, float height) {
        if (installation == null || selection.tiles().isEmpty()) {
            return;
        }
        var tiles = selection.tiles();
        var events = mapEventsOf(installation.root());
        var area = new CaptureDialog.Area(
                cutOf(tiles),
                places.describe(tiles, selection.place(), installation.tiles().size()),
                labelOf(installation.root()) + " · " + tilesWord(tiles.size()),
                events.summary(),
                "Effects that a game server turns on and off: " + events.describe()
                        + ". The Viewer's Map events tool and Export location change this choice for the client."
                        + " A tile view can have its own, under Tile views.",
                tiles,
                events);
        var result = captureDialog.draw(width, height, fonts, area);
        if (result == CaptureDialog.Result.OPEN) {
            rects.putAll(captureDialog.getPlaces());
        }
        if (result == CaptureDialog.Result.STARTED) {
            if (captureDialog.isSaved()) {
                configured = loader.load();
            }
            startCapture();
        }
    }

    private boolean isToastMoving(long now) {
        var motion = (long) (Motion.OPEN * 1e9);
        for (var toast : toasts) {
            var age = now - toast.at();
            if (age < motion || (!toast.error() && life(toast) - age < motion)) {
                return true;
            }
        }
        return false;
    }

    private void drawToasts(Frame frame) {
        var now = java.lang.System.nanoTime();
        toasts.removeIf(toast -> !toast.error() && now - toast.at() >= life(toast));
        var mainLeft = frame.aside() ? frame.barLeft() - MARGIN : frame.mainLeft();
        var mainWidth = frame.aside() ? frame.barWidth() + 2 * MARGIN : frame.width() - mainLeft;
        var bottom = (installation == null ? frame.height() : frame.barTop()) - GeodataWidgets.ROW;
        if (ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel)) {
            return;
        }
        Toast clicked = null;
        for (var index = toasts.size() - 1; index >= 0; index--) {
            var toast = toasts.get(index);
            var action = toast.folder() != null && Files.isDirectory(toast.folder()) ? "Show folder" : null;
            var toastWidth =
                    HudWidgets.toastWidth(toast.text(), action, Math.max(160, Math.min(720, mainWidth - 2 * MARGIN)));
            var height = HudWidgets.TOAST_HEIGHT;
            var x = (float) Math.floor(mainLeft + (mainWidth - toastWidth) * .5f);
            var top = bottom - height;
            ImGui.setNextWindowPos(x, top, ImGuiCond.Always);
            ImGui.setNextWindowSize(toastWidth, height, ImGuiCond.Always);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 1, 1);
            ImGui.begin(
                    "##start-toast-" + index,
                    ImGuiWindowFlags.NoDecoration
                            | ImGuiWindowFlags.NoSavedSettings
                            | ImGuiWindowFlags.NoBackground
                            | ImGuiWindowFlags.NoFocusOnAppearing
                            | ImGuiWindowFlags.NoNav
                            | ImGuiWindowFlags.NoScrollWithMouse);
            ImGui.popStyleVar(3);
            imgui.internal.ImGui.bringWindowToDisplayFront(imgui.internal.ImGui.getCurrentWindow());
            var age = now - toast.at();
            var rise = Motion.easeOut(Math.min(1f, age / (Motion.OPEN * 1e9f)));
            var leave = toast.error() ? 1f : (float) Math.clamp((life(toast) - age) / (Motion.OPEN * 1e9), 0, 1);
            Motion.fade(
                    Math.min(Math.clamp(rise * 1.6f, 0, 1), leave),
                    1,
                    0,
                    12 * (1 - rise) + 8 * (1 - leave),
                    1 - .04f * (1 - rise),
                    0);
            var dot = toast.error() ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN;
            var pressed = HudWidgets.toast("toast-" + index, toast.text(), dot, Glyph.FOLDER, action, toastWidth);
            if (!toast.error() && ImGui.isWindowHovered() && life(toast) - age < 1_500_000_000L) {
                toasts.set(index, new Toast(toast.text(), false, toast.folder(), now - life(toast) + 1_500_000_000L));
            }
            mark("main.toast/" + index, x, top, x + toastWidth, top + height);
            ImGui.end();
            if (pressed == HudWidgets.TOAST_ACTION && toast.folder() != null) {
                FileManager.tryShow(toast.folder());
            } else if (pressed == HudWidgets.TOAST_CLICKED) {
                clicked = toast;
            }
            bottom = top - GeodataWidgets.ROW;
        }
        if (clicked != null) {
            toasts.remove(clicked);
        }
    }

    private void takeKeys(boolean fieldHadKeys, boolean searchHadKeys, boolean navVisible, boolean popupOpen) {
        if (ImGui.isKeyPressed(ImGuiKey.Enter, false) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false)) {
            var work =
                    StartKeys.onEnter(popupOpen, guide.hasKeys(), fieldHadKeys, searchHadKeys, navVisible, query.get());
            if (work == StartKeys.Enter.OPEN) {
                open();
            } else if (work == StartKeys.Enter.TAKE_RESULT) {
                for (var found : searchResults()) {
                    if (found.available()) {
                        take(found);
                        break;
                    }
                }
            }
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false) && !popupOpen) {
            if (!query.get().isEmpty()) {
                query.set("");
            } else if (guide.isOpen() && !fieldHadKeys) {
                guide.close();
            }
        }
        if (ImGui.isKeyPressed(ImGuiKey.Slash, false) && !fieldHadKeys && !popupOpen) {
            focusSearch = true;
        }
    }

    private void take(Gesture gesture) {
        var tiles = installation.tiles();
        query.set("");
        switch (gesture.kind()) {
            case CLICK ->
                selection =
                        gesture.modified() ? selection.toggle(gesture.tile(), tiles) : selection.click(gesture.tile());
            case BOX -> selection = selection.box(gesture.from(), gesture.tile(), gesture.modified(), tiles);
            case DOUBLE_CLICK -> {
                selection = selection.click(gesture.tile());
                open();
            }
        }
    }

    private void take(StartPlaces.Result found) {
        if (!found.available()) {
            return;
        }
        selection = found.place() == null ? selection.click(found.tile()) : selection.take(found.place());
        query.set("");
    }

    private List<StartPlaces.Result> searchResults() {
        return places.search(query.get(), installation == null ? List.of() : installation.tiles());
    }

    private void open() {
        if (installation == null || selection.tiles().isEmpty()) {
            return;
        }
        var tiles = selection.tiles();
        var title =
                places.describe(tiles, selection.place(), installation.tiles().size());
        var place = selection.place() != null ? selection.place().name() : null;
        startViewer(installation, Kind.TILES, tiles, "", title, place, editing);
    }

    private void startNamedMap(String map) {
        if (installation != null) {
            startViewer(installation, Kind.MAP, List.of(), map, map, null, false);
        }
    }

    private void startEffects() {
        if (installation != null) {
            startViewer(installation, Kind.EFFECTS, List.of(), "", "Effects", null, false);
        }
    }

    private void startSession(Session session) {
        var found = inspect(session.client());
        if (found.client() == null || !findProblem(session).isEmpty()) {
            return;
        }
        var client = found.client();
        switch (session.kind()) {
            case TILES -> {
                var tiles = ClientInstallation.ALL_TILES.equals(session.tiles())
                        ? client.tiles()
                        : TileSelection.parse(session.tiles());
                var place = session.place().isEmpty() ? null : session.place();
                startViewer(client, Kind.TILES, tiles, "", session.title(), place, session.edit());
            }
            case MAP -> startViewer(client, Kind.MAP, List.of(), session.map(), session.map(), null, false);
            case EFFECTS -> startViewer(client, Kind.EFFECTS, List.of(), "", "Effects", null, false);
        }
    }

    private void startViewer(
            ClientInstallation client,
            Kind kind,
            List<String> tiles,
            String map,
            String title,
            @Nullable String place,
            boolean edit) {
        var frame = ImGui.getFrameCount();
        if (startFrame == frame) {
            return;
        }
        startFrame = frame;
        var own = installation != null && installation.root().equals(client.root());
        var shown = kind == Kind.TILES ? tiles : own ? selection.tiles() : List.<String>of();
        var tile = shown.isEmpty() ? "" : TileSelection.encode(shown, client.tiles());
        try {
            var start = new MenuStart(client.root(), tile, kind == Kind.TILES ? place : null);
            var config = loader.forClient(start.client(), start.tiles(), false, start.place());
            rememberClient(client.root(), tile.isEmpty() ? null : tile);
            if (sessions != null) {
                var kept = kind == Kind.TILES ? tile : "";
                try {
                    sessions.record(new Session(
                            client.root(),
                            kind,
                            kept,
                            map,
                            title,
                            kind == Kind.TILES && place != null ? place : "",
                            edit && kind == Kind.TILES,
                            Instant.now()));
                } catch (IOException | RuntimeException failure) {
                    java.lang.System.getLogger(ClientLauncher.class.getName())
                            .log(Level.WARNING, "Cannot record the session", failure);
                }
            }
            var maps = switch (kind) {
                case TILES -> tiles;
                case MAP -> List.of(map);
                case EFFECTS -> List.<String>of();
            };
            result = new Selection(
                    config, false, maps, false, null, kind == Kind.EFFECTS, edit && kind == Kind.TILES, start);
            starts++;
        } catch (RuntimeException failure) {
            showError("Could not open this client: " + failure.getMessage());
        }
    }

    private void startCapture() {
        if (installation == null || selection.tiles().isEmpty()) {
            return;
        }
        var tile = TileSelection.encode(selection.tiles(), installation.tiles());
        try {
            var config = loader.forClient(installation.root(), tile, true);
            rememberClient(installation.root(), tile);
            result = new Selection(config, true, selection.tiles());
        } catch (RuntimeException failure) {
            showError("Could not start the capture: " + failure.getMessage());
        }
    }

    private void createMapImage() {
        if (installation == null) {
            return;
        }
        try {
            var cache = new MapPreviewCache(loader.getConfigDirectory(), installation);
            var config = loader.forMapPreview(installation, cache.getCaptureDirectory());
            rememberClient(installation.root(), savedChoice());
            result = new Selection(config, true, installation.tiles(), true);
        } catch (RuntimeException failure) {
            showError("Could not create a map image: " + failure.getMessage());
        }
    }

    private void updateMapImage(MapUpdate update) {
        try {
            var cache = new MapPreviewCache(loader.getConfigDirectory(), installation);
            var old = new MapPreviewCache(loader.getConfigDirectory(), update.before());
            var drawn = update.tiles().stream()
                    .filter(installation.tiles()::contains)
                    .toList();
            var partial = Files.isRegularFile(old.getImage()) && cache.hasSameGrid(old) && !drawn.isEmpty();
            var config = loader.forMapPreview(installation, cache.getCaptureDirectory());
            rememberClient(installation.root(), savedChoice());
            result = partial
                    ? new Selection(config, true, drawn, true, new MapPatch(old.getImage(), update.tiles()))
                    : new Selection(config, true, installation.tiles(), true);
        } catch (RuntimeException failure) {
            showError("Could not update the map image: " + failure.getMessage());
        }
    }

    private void select(Path path, @Nullable String saved) {
        try {
            var found = readClient(path);
            selection = StartSelection.restore(saved, found.tiles(), configuredTiles(found))
                    .withSavedPlace(newestTiles(found.root()), places, found.tiles());
            useClient(found);
        } catch (RuntimeException failure) {
            showError(failure.getMessage());
        }
    }

    private void chooseClient(Path path) {
        try {
            var found = readClient(path);
            var kept = selection.tiles().stream().anyMatch(found.tiles()::contains);
            selection = selection.switchClient(found.tiles(), savedTile(found.root()), configuredTiles(found));
            if (!kept) {
                selection = selection.withSavedPlace(newestTiles(found.root()), places, found.tiles());
            }
            useClient(found);
        } catch (RuntimeException failure) {
            showError(failure.getMessage());
        }
    }

    private void retryClient(Path path) {
        inspected.remove(path);
        var found = inspect(path);
        if (found.client() != null) {
            chooseClient(path);
        } else {
            showError(found.problem());
        }
    }

    private void retrySession(Session session) {
        inspected.remove(session.client());
        var problem = findProblem(session);
        if (!problem.isEmpty()) {
            showError(problem);
        }
    }

    private @Nullable Session newestTiles(Path root) {
        return sessions == null ? null : sessions.findNewestTiles(root);
    }

    private @Nullable String savedChoice() {
        return selection.tiles().isEmpty() ? null : TileSelection.encode(selection.tiles(), installation.tiles());
    }

    private void takeFolder(Path chosen) {
        try {
            var found = ClientFolders.inspect(chosen);
            chooseClient(found.root());
        } catch (RuntimeException notClient) {
            showError(notClient.getMessage() == null ? "This is not a readable game folder." : notClient.getMessage());
        }
    }

    private void takeDroppedFolder(boolean popupOpen) {
        if (popupOpen || window == null || window.droppedFiles.isEmpty()) {
            return;
        }
        var dropped = window.droppedFiles.getFirst();
        window.droppedFiles.clear();
        takeFolder(dropped);
    }

    private void addClient() {
        if (NativeDialogs.isAvailable()) {
            var start = installation == null ? null : installation.root().getParent();
            var chosen = NativeDialogs.chooseFolder("Choose your Lineage II folder", start);
            if (chosen.outcome() == Outcome.CHOSEN && chosen.path() != null) {
                takeFolder(chosen.path());
                return;
            }
            if (chosen.outcome() == Outcome.CANCELLED) {
                return;
            }
        }
        requestBrowser = true;
    }

    private void useClient(ClientInstallation found) {
        installation = found;
        inspected.put(found.root(), new Inspected(found, namedMapsOf(found.root()), ""));
        namedMaps = inspected.get(found.root()).namedMaps();
        CrumaBranding.prepareScene(found.root());
        zoom = new Zoom();
        pointer = new Pointer();
        if (recent != null
                && recent.getEntries().stream().noneMatch(entry -> entry.path().equals(found.root()))) {
            try {
                recent.remember(new Entry(found.root(), TileSelection.encode(selection.tiles(), found.tiles())));
            } catch (IOException failure) {
                showError("Could not save this client shortcut: " + failure.getMessage());
            }
        }
    }

    private void removeClient(Path path) {
        try {
            if (recent != null) {
                recent.remove(path);
            }
        } catch (IOException failure) {
            showError("Could not remove this shortcut: " + failure.getMessage());
            return;
        }
        try {
            if (sessions != null) {
                sessions.removeClient(path);
            }
        } catch (IOException failure) {
            showError("Could not remove this row: " + failure.getMessage());
        }
        inspected.remove(path);
        if (installation != null && installation.root().equals(path)) {
            installation = null;
            selection = StartSelection.NONE;
            namedMaps = List.of();
            for (var other : savedClients()) {
                if (inspect(other).client() != null) {
                    var entry = recent.getEntries().stream()
                            .filter(value -> value.path().equals(other))
                            .findFirst()
                            .orElse(null);
                    select(other, entry == null ? null : entry.tile());
                    break;
                }
            }
        }
    }

    private void removeSession(Session session) {
        try {
            if (sessions != null) {
                sessions.remove(session);
            }
        } catch (IOException failure) {
            showError("Could not remove this row: " + failure.getMessage());
        }
    }

    private void reread() {
        inspected.clear();
        for (var panel : asides) {
            if (panel instanceof ClientAside client) {
                client.forget();
            }
            if (panel instanceof PlaceAside place) {
                place.forget();
            }
        }
        if (installation == null) {
            return;
        }
        try {
            var found = readClient(installation.root());
            selection = selection.switchClient(found.tiles(), savedTile(found.root()), configuredTiles(found));
            installation = found;
            namedMaps = namedMapsOf(found.root());
            inspected.put(found.root(), new Inspected(found, namedMaps, ""));
        } catch (RuntimeException failure) {
            showError(failure.getMessage());
        }
    }

    private ClientInstallation readClient(Path path) {
        var found = ClientInstallation.inspect(path);
        return found.withVariant(loader.getVariantOf(found.root()));
    }

    private Inspected inspect(Path path) {
        return inspected.computeIfAbsent(path, folder -> {
            try {
                var found = readClient(folder);
                return new Inspected(found, namedMapsOf(found.root()), "");
            } catch (RuntimeException failure) {
                return new Inspected(
                        null,
                        List.of(),
                        failure.getMessage() == null ? "This client folder cannot be read." : failure.getMessage());
            }
        });
    }

    private List<Path> savedClients() {
        var paths = new ArrayList<Path>();
        if (recent != null) {
            for (var entry : recent.getEntries()) {
                paths.add(entry.path());
            }
        }
        if (installation != null && !paths.contains(installation.root())) {
            paths.add(installation.root());
        }
        paths.sort(Comparator.comparing(ClientLauncher::clientName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Path::toString));
        return paths;
    }

    private @Nullable String savedTile(Path root) {
        if (recent == null) {
            return null;
        }
        for (var entry : recent.getEntries()) {
            if (entry.path().equals(root)) {
                return entry.tile();
            }
        }
        return null;
    }

    private List<String> configuredTiles(ClientInstallation client) {
        var world = configured.viewer().world();
        return client.tilesIn(world.minXTile(), world.maxXTile(), world.minYTile(), world.maxYTile());
    }

    private void rememberClient(Path root, @Nullable String tile) {
        if (recent == null) {
            return;
        }
        var previous = recent.getEntries().stream()
                .filter(entry -> entry.path().equals(root))
                .findFirst()
                .orElse(null);
        var kept = tile != null ? tile : previous != null ? previous.tile() : "";
        try {
            recent.remember(new Entry(root, kept, previous == null ? "auto" : previous.captureTile()));
        } catch (IOException | RuntimeException failure) {
            java.lang.System.getLogger(ClientLauncher.class.getName())
                    .log(Level.WARNING, "Cannot remember selected client", failure);
        }
    }

    private String findProblem(Session session) {
        var found = inspect(session.client());
        if (found.client() == null) {
            return found.problem();
        }
        return switch (session.kind()) {
            case TILES -> {
                if (ClientInstallation.ALL_TILES.equals(session.tiles())) {
                    yield "";
                }
                var missing = TileSelection.parse(session.tiles()).stream()
                        .filter(tile -> !found.client().tiles().contains(tile))
                        .toList();
                yield missing.isEmpty()
                        ? ""
                        : missing.size() == 1
                                ? "This client has no tile " + missing.getFirst() + " any more."
                                : missing.size() + " of its tiles are not in this client any more.";
            }
            case MAP ->
                found.namedMaps().contains(session.map())
                        ? ""
                        : "This client has no map " + session.map() + " any more.";
            case EFFECTS -> "";
        };
    }

    private String describe(Session session) {
        var name = labelOf(session.client());
        return switch (session.kind()) {
            case TILES -> {
                var found = inspect(session.client()).client();
                var count = ClientInstallation.ALL_TILES.equals(session.tiles())
                        ? found == null ? "all tiles" : tilesWord(found.tiles().size())
                        : tilesWord(TileSelection.parse(session.tiles()).size());
                yield name + " · " + count + " · " + (session.edit() ? "Edit" : "Viewer");
            }
            case MAP -> name + " · other map";
            case EFFECTS -> name + " · no map";
        };
    }

    private String labelOf(Path client) {
        var known = new ArrayList<>(savedClients());
        if (!known.contains(client)) {
            known.add(client);
        }
        return ClientFolders.label(client, known);
    }

    private Picture sessionPicture(Session session) {
        return switch (session.kind()) {
            case MAP -> Picture.of(0, Glyph.PLACE);
            case EFFECTS -> Picture.of(0, Glyph.SPARKLES);
            case TILES -> {
                if (installation == null || !installation.root().equals(session.client())) {
                    yield Picture.of(0, Glyph.GLOBE);
                }
                var tiles = ClientInstallation.ALL_TILES.equals(session.tiles())
                        ? installation.tiles()
                        : TileSelection.parse(session.tiles());
                yield cutOf(tiles);
            }
        };
    }

    private Picture cutOf(List<String> tiles) {
        var texture = mapTexture();
        if (texture == 0 || tiles.isEmpty()) {
            return Picture.of(0, Glyph.GLOBE);
        }
        var image = TileBounds.of(installation.tiles());
        var chosen = TileBounds.of(tiles);
        var columns = image.maxX() - image.minX() + 1f;
        var rows = image.maxY() - image.minY() + 1f;
        var across = chosen.maxX() - chosen.minX() + 1f;
        var down = chosen.maxY() - chosen.minY() + 1f;
        var span = Math.max(across, down);
        if (span >= Math.max(columns, rows)) {
            return Picture.of(texture, Glyph.GLOBE);
        }
        var spanX = Math.min(span, columns);
        var spanY = Math.min(span, rows);
        var left = Math.clamp(chosen.minX() - (spanX - across) * .5f, image.minX(), image.maxX() + 1 - spanX);
        var top = Math.clamp(chosen.minY() - (spanY - down) * .5f, image.minY(), image.maxY() + 1 - spanY);
        return new Picture(
                texture,
                (left - image.minX()) / columns,
                (top - image.minY()) / rows,
                (left + spanX - image.minX()) / columns,
                (top + spanY - image.minY()) / rows,
                Glyph.GLOBE);
    }

    private long mapTexture() {
        return installation == null || mapPreview == null ? 0 : mapPreview.getTextureFor(installation);
    }

    private String[] npcFolders(ClientInstallation client) {
        try {
            var world = loader.forClient(client.root(), "", false).viewer().world();
            var scripts = world.spawns().scriptPath();
            var geodata = world.geodata().path();
            return new String[] {scripts == null ? "" : scripts.toString(), geodata == null ? "" : geodata.toString()};
        } catch (RuntimeException unreadable) {
            return new String[] {"", ""};
        }
    }

    private List<Place> exportPlaces() {
        return places.getPlaces().stream()
                .map(location -> new Place(location.name(), location.x(), location.y()))
                .toList();
    }

    private void openLogs() {
        var directory = LogFiles.logDirectory(loader.getConfigDirectory());
        try {
            Files.createDirectories(directory);
            FileManager.openFolder(directory);
        } catch (IOException | RuntimeException failure) {
            showError("Cannot open the logs folder " + directory + ": " + failure.getMessage());
        }
    }

    private void takeGuideRequest() {
        var request = GuideRequests.take();
        if (request == null) {
            return;
        }
        if (request.toggle() && guide.isOpen()) {
            guide.close();
            return;
        }
        guide.open(request.page() == null ? GUIDE_START : request.page());
    }

    private boolean beginMenuAt(String id, float width, float height, String under) {
        var rect = rects.get(under);
        if (rect == null) {
            return false;
        }
        var below = rect[3] + GeodataWidgets.ROW;
        var room = ImGui.getIO().getDisplaySizeY() - GeodataWidgets.ROW;
        var top = below + height <= room ? below : rect[1] - GeodataWidgets.ROW - height;
        return HudWidgets.beginMenuAt(id, width, height, rect[2] - width, top);
    }

    private void mark(String name) {
        mark(name, ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY());
    }

    private void mark(String name, float left, float top, float right, float bottom) {
        rects.put(name, new float[] {left, top, right, bottom});
    }

    private void navigate(Path path) {
        try {
            var target = path.toRealPath();
            List<Path> children;
            try (var stream = Files.list(target)) {
                children = stream.filter(Files::isDirectory)
                        .filter(child -> showHidden.get()
                                || !child.getFileName().toString().startsWith("."))
                        .sorted(Comparator.comparing(
                                child -> child.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .toList();
            }
            browsing = target;
            browsePath.set(target.toString());
            directories = children;
            browseError = "";
        } catch (IOException | RuntimeException failure) {
            browsing = null;
            directories = List.of();
            browseError = "Cannot open this folder. Check the path and permissions.";
        }
    }

    private void drawBrowser(float width, float height) {
        ImGui.setNextWindowSize(Math.min(680, width - 32), Math.min(500, height - 32));
        ImGui.setNextWindowPos(width / 2, height / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 20, 20);
        ImGui.pushStyleColor(ImGuiCol.PopupBg, ImGui.getColorU32(ImGuiCol.WindowBg));
        boolean visible = ImGui.beginPopupModal(
                "Choose game folder",
                ImGuiWindowFlags.NoSavedSettings
                        | ImGuiWindowFlags.NoTitleBar
                        | ImGuiWindowFlags.NoResize
                        | ImGuiWindowFlags.NoMove
                        | ImGuiWindowFlags.NoScrollbar);
        ImGui.popStyleColor();
        if (!visible) {
            ImGui.popStyleVar();
            return;
        }
        Motion.dialog();
        mark(
                "browser",
                ImGui.getWindowPosX(),
                ImGui.getWindowPosY(),
                ImGui.getWindowPosX() + ImGui.getWindowWidth(),
                ImGui.getWindowPosY() + ImGui.getWindowHeight());
        if (window != null && !window.droppedFiles.isEmpty()) {
            var dropped = window.droppedFiles.getFirst();
            window.droppedFiles.clear();
            try {
                chooseClient(ClientFolders.inspect(dropped).root());
                ImGui.closeCurrentPopup();
            } catch (RuntimeException notClient) {
                browseError =
                        notClient.getMessage() == null ? "This is not a readable game folder." : notClient.getMessage();
            }
        }
        ImGui.pushFont(fonts.getHeadingFont(20), 20);
        ImGui.text("Choose game folder");
        ImGui.popFont();
        ImGui.textDisabled("Open a folder, then select it to use as your client.");
        ImGui.dummy(0, 6);
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 68);
        ImGui.inputText("##browse-path", browsePath);
        boolean entered = ImGui.isItemDeactivated()
                && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter));
        ImGui.sameLine();
        if (ImGui.button("Go", 60, 28) || entered) {
            try {
                navigate(Path.of(browsePath.get()));
            } catch (RuntimeException failure) {
                browseError = "Invalid folder path.";
            }
        }
        if (ImGui.button("Home", 68, 28)) {
            navigate(Path.of(java.lang.System.getProperty("user.home")));
        }
        ImGui.sameLine();
        ImGui.beginDisabled(browsing == null || browsing.getParent() == null);
        if (ImGui.button("Up", 52, 28) && browsing != null) {
            navigate(browsing.getParent());
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        ImGui.setNextItemWidth(112);
        if (HudWidgets.beginCombo("##drives", "Drives", 0)) {
            var roots = new ArrayList<Path>();
            FileSystems.getDefault().getRootDirectories().forEach(roots::add);
            var volumes = Path.of("/Volumes");
            if (Files.isDirectory(volumes)) {
                roots.add(volumes);
            }
            for (var root : roots) {
                if (ImGui.selectable(root.toString())) {
                    navigate(root);
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Hidden folders", showHidden) && browsing != null) {
            navigate(browsing);
        }
        ImGui.beginChild(
                "##folders",
                0,
                ImGui.getContentRegionAvailY() - 96,
                ImGuiChildFlags.Borders | ImGuiChildFlags.NavFlattened,
                0);
        for (var directory : directories) {
            if (ImGui.selectable(directory.getFileName().toString() + "/", false)) {
                navigate(directory);
                break;
            }
        }
        if (directories.isEmpty()) {
            ImGui.textDisabled(browsing == null ? "Folder unavailable" : "No subfolders");
        }
        ImGui.endChild();
        if (!browseError.isEmpty()) {
            ImGui.pushStyleColor(ImGuiCol.Text, 1, .65f, .55f, 1);
            ImGui.textWrapped(browseError);
            ImGui.popStyleColor();
        }
        ImGui.setCursorPosY(ImGui.getWindowHeight() - 52);
        ImGui.setCursorPosX(ImGui.getWindowWidth() - 274);
        if (HudWidgets.button("cancel-browser", null, "Cancel", 96, 32, false, true)
                || GeodataWidgets.wasClickedOutside()) {
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        if (HudWidgets.button("select-folder", null, "Select folder", 150, 32, true, true)) {
            try {
                var selected = ClientFolders.inspect(Path.of(browsePath.get().trim()));
                chooseClient(selected.root());
                ImGui.closeCurrentPopup();
            } catch (RuntimeException failure) {
                browseError =
                        failure.getMessage() == null ? "This is not a readable game folder." : failure.getMessage();
            }
        }
        ImGui.endPopup();
        ImGui.popStyleVar();
    }

    private static void sideSection(String label, float above) {
        ImGui.indent(GeodataWidgets.LINE_INSET);
        GeodataWidgets.section(label, above);
        ImGui.unindent(GeodataWidgets.LINE_INSET);
    }

    private static float wideBarFixed() {
        var gap = GeodataWidgets.ROW;
        return MARGIN
                + BAR_PICTURE
                + MARGIN
                + MARGIN
                + SIZES_WIDTH
                + gap
                + MODES_WIDTH
                + gap
                + OPEN_WIDTH
                + gap
                + HudWidgets.CONTROL_HEIGHT
                + MARGIN;
    }

    private static List<String> namedMapsOf(Path root) {
        try {
            return WorldMapCatalog.namedMaps(root);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    private static long life(Toast toast) {
        return toast.folder() != null ? NOTE_WITH_ACTION_NANOS : NOTE_NANOS;
    }

    private static String tilesWord(int count) {
        return count + (count == 1 ? " tile" : " tiles");
    }

    private static String clientName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static StartPlaces placesOf(Config settings) {
        try {
            return new StartPlaces(settings.viewer().camera().locations());
        } catch (RuntimeException unreadable) {
            return new StartPlaces(List.<Location>of());
        }
    }

    private static String describeWorld(WorldVariant variant) {
        return switch (variant) {
            case MAIN -> "Main world: X_Y maps and the main tables.";
            case CLASSIC -> "Classic world: X_Y_Classic maps where the client has them, X_Y maps elsewhere.";
            case CLASSIC_ADEN ->
                "Essence world: the Classic maps, with the _ClassicAden tables where the client has them.";
        };
    }

    private static void theme() {
        ImGui.styleColorsDark();
        ImGui.getStyle().setColor(ImGuiCol.ModalWindowDimBg, 0, 0, 0, 0);
        ImGui.getStyle().setScrollbarSize(6);
    }

    private static final class StartScreenHost implements GuideHost {
        @Override
        public @Nullable String getOpenTool() {
            return null;
        }

        @Override
        public @Nullable String getOpenTab() {
            return null;
        }

        @Override
        public boolean hasTool(String tool) {
            return false;
        }

        @Override
        public void openTool(String tool) {}

        @Override
        public @Nullable Snapshot getView() {
            return null;
        }

        @Override
        public void restoreView(Snapshot view) {}
    }
}

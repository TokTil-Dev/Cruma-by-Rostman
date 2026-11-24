package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.GameScenes.Model;
import com.rost.cruma.core.world.MapScenes.Scene;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.WindowContext.Keyboard;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.editing.SceneFileStore;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.GameScenePlayer;
import com.rost.cruma.viewer.render.GameScenePlayer.Ground;
import com.rost.cruma.viewer.render.LoadedTiles;
import com.rost.cruma.viewer.render.SceneAudio;
import com.rost.cruma.viewer.render.SceneDoors.Moved;
import com.rost.cruma.viewer.render.SceneOverlay;
import com.rost.cruma.viewer.render.ScenePlayer;
import com.rost.cruma.viewer.render.ScenePostEffects;
import com.rost.cruma.viewer.render.ScreenLens;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImColor;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public final class ScenesPanel {
    private static final float BUTTON_WIDTH = 28;
    private static final float BUTTON_HEIGHT = 26;
    private static final float MARK = 18;
    private static final float MARK_ROOM = MARK + 12;
    private static final float COPY_MARK = 16;
    private static final float COPY_ROOM = COPY_MARK + 12;
    private static final int PLAYING = 0xFF5CDBF1;
    private static final int MOVING = 0xFFA4B4C3;
    private static final int JUMP = 0xFF5C6E7E;
    private static final int PLAYING_ROW = ImColor.rgb(30, 58, 70);
    private static final String READING = "Reading the scene";
    private static final String STRIP = "SCENES";

    private final ImString search = new ImString(64);
    private final ImGuiListClipper clipper = new ImGuiListClipper();
    private final List<Scene> listed = new ArrayList<>();
    private final List<GameScenes.Scene> listedGame = new ArrayList<>();
    private final List<GameScenes.Scene> listedServer = new ArrayList<>();
    private List<Scene> scenes = List.of();
    private List<GameScenes.Scene> gameScenes = List.of();
    private List<GameScenes.Scene> serverScenes = List.of();
    private boolean gameScenesRead;
    private @Nullable GameScenes.Scene opening;
    private boolean openingCopy;
    private String clientSha256 = "";
    private String serverSha256 = "";
    private String mapSha256 = "";
    private @Nullable GameScenes.Scene chosen;
    private @Nullable SceneExports export;
    private final DirectorPanel director = new DirectorPanel();

    private @Nullable String map;
    private @Nullable String listedFor;
    private final Map<String, float[]> copyButtons = new HashMap<>();
    private String viewTag = "";
    private boolean othersOpen;
    private float othersTop;

    public static void drawFade(ScenePlayer player) {
        var fade = player.getFade();
        if (fade <= 0) {
            return;
        }
        var io = ImGui.getIO();
        ImGui.getBackgroundDrawList()
                .addRectFilled(
                        0,
                        0,
                        io.getDisplaySizeX(),
                        io.getDisplaySizeY(),
                        ImColor.rgba(0, 0, 0, Math.round(fade * 255)));
    }

    static String detail(GameScenes.Scene scene, boolean loaded) {
        var time = String.format(Locale.ROOT, "%.1f s", scene.seconds());
        var tiles = scene.tiles();
        if (tiles.isEmpty()) {
            return "On the screen · " + time;
        }
        return (tiles.size() == 1 ? "Tile " : "Tiles ")
                + String.join(", ", tiles)
                + (loaded ? "" : " not loaded")
                + " · "
                + time;
    }

    static String tip(GameScenes.Scene scene) {
        if (scene.movesCamera()) {
            return "the camera follows it and the keys wait until it ends.";
        }
        return scene.tiles().isEmpty()
                ? "it shows on the screen."
                : "the camera starts near it, then its animations can take it until it ends.";
    }

    static boolean isLoaded(GameScenes.Scene scene, LoadedTiles tiles) {
        for (var tile : scene.tiles()) {
            if (!tiles.has(tile)) {
                return false;
            }
        }
        return true;
    }

    static String summary(int cuts, int server) {
        var text = cuts + (cuts == 1 ? " cut scene" : " cut scenes");
        return server == 0 ? text : text + " · " + server + " from the server";
    }

    static String progress(double seconds, double length) {
        return String.format(Locale.ROOT, "%.1f of %.1f s", seconds, length);
    }

    static String detail(Scene scene) {
        if (scene.warp()) {
            return "Jump · 0 s";
        }
        var moves = scene.moves();
        return moves + (moves == 1 ? " move · " : " moves · ") + String.format(Locale.ROOT, "%.1f s", scene.seconds());
    }

    public void setScenes(@Nullable String map, List<Scene> scenes) {
        this.map = map;
        this.scenes = List.copyOf(scenes);
        listedFor = null;
    }

    public void setDirectorMap(@Nullable String name) {
        director.setMap(name);
    }

    public void setMapFile(String sha256) {
        mapSha256 = sha256;
    }

    public void setDirectorInto(ExportSettings settings, Predicate<String> mapEdits, Runnable clientChanged) {
        director.getInto().setHost(settings, mapEdits, clientChanged);
    }

    public void setGameScenes(List<GameScenes.Scene> scenes) {
        gameScenes = List.copyOf(scenes);
        gameScenesRead = true;
        var names = new ArrayList<String>(scenes.size());
        for (var scene : scenes) {
            names.add(scene.name());
        }
        director.getInto().setClasses(names);
        listedFor = null;
    }

    public void setDirector(
            Camera camera, IntSupplier rate, String client, String variant, @Nullable SceneFileStore store) {
        director.setSession(camera, rate, client, variant, store);
    }

    public void setDirectorWorld(
            Path client,
            WorldVariant variant,
            TextureQuality quality,
            SpawnOverlayState spawns,
            Supplier<@Nullable EffectPreview> effects,
            Consumer<Moved> doors,
            Consumer<@Nullable SceneAudio> speakers) {
        var session = director.getSession();
        if (session != null) {
            session.setBaking(
                    new SceneBake(client, variant, quality), new BakedSceneView(spawns, effects, doors, speakers));
            director.setClient(client, variant);
        }
    }

    public void setVideoShape(DoubleSupplier shape, Supplier<ScreenLens> lens) {
        director.setVideoShape(shape, lens);
    }

    public void setSceneModel(Model model) {
        director.setModel(model);
    }

    public void setSceneHelper(int npc) {
        director.setHelper(npc);
    }

    public void setSceneFiles(String client, String server) {
        clientSha256 = client;
        serverSha256 = server;
    }

    public void openCopy(GameScenes.Scene scene) {
        director.openCopy(scene, isServerScene(scene) ? serverSha256 : clientSha256);
    }

    public void openFile(Path file) {
        director.openFile(file);
    }

    public @Nullable Path takeDirectorReload() {
        return director.takeReload();
    }

    public boolean takeDirectorGrown() {
        return director.takeGrown();
    }

    public @Nullable SceneOverlay getDirectorOverlay() {
        var view = directorView();
        return view == null || isDirectorFree() ? null : view.getOverlay();
    }

    public GameScenes.@Nullable Scene getDirectorFilm() {
        var view = directorView();
        return view == null ? null : view.getScene();
    }

    public @Nullable ScenePostEffects getDirectorPosts() {
        var view = directorView();
        return view == null || isDirectorFree() ? null : view.getPosts();
    }

    public boolean isDirectorOpen() {
        return director.isOpen();
    }

    public boolean isDirectorPlaying() {
        return director.isPlaying();
    }

    public boolean doesDirectorHoldCamera() {
        return director.holdsCamera();
    }

    public void updateDirector(double seconds, boolean shown, boolean cameraFree) {
        director.update(seconds, shown, cameraFree);
    }

    public double getDirectorHour() {
        return director.getSceneHour();
    }

    public void handleDirectorKeys(Keyboard keys) {
        director.handleKeys(keys);
    }

    public boolean handleDirectorEscape() {
        return director.handleEscape();
    }

    public boolean drawDirectorHandle(Camera camera, float left, float top, float right, float bottom) {
        return director.drawHandle(camera, left, top, right, bottom);
    }

    public boolean doesDirectorPointAtActor() {
        return director.pointsAtActor();
    }

    public void pollDirectorPictures(ViewerUiRenderer ui) {
        director.pollPictures(ui);
    }

    public void drawDirectorTray(float width, float height) {
        director.drawTray(width, height);
    }

    public void drawDirectorMarks(GeoOverlayLines marks, float viewportPoints) {
        director.drawMarks(marks, viewportPoints);
    }

    public void setServerScenes(List<GameScenes.Scene> scenes) {
        serverScenes = List.copyOf(scenes);
        listedFor = null;
    }

    public void setExport(
            Path client,
            WorldVariant variant,
            TextureQuality quality,
            Path configFolder,
            @Nullable Path scripts,
            ExportSettings settings,
            MapEventChoices mapEvents,
            IntSupplier hour,
            DoubleSupplier exactHour,
            DoubleSupplier musicFade) {
        export = new SceneExports(
                new SceneBlenderExport(client, variant, quality, settings, mapEvents, hour, musicFade),
                new SceneVideoExport(client, variant, configFolder, scripts, settings, mapEvents, exactHour),
                settings);
        director.setViewerHour(exactHour);
    }

    public void close() {
        if (export != null) {
            export.close();
        }
        director.close();
    }

    public List<Scene> getScenes() {
        return scenes;
    }

    public GameScenes.@Nullable Scene findGameScene(String name) {
        for (var list : List.of(gameScenes, serverScenes)) {
            for (var scene : list) {
                if (scene.name().equalsIgnoreCase(name)) {
                    return scene;
                }
            }
        }
        return null;
    }

    public boolean isServerScene(GameScenes.@Nullable Scene scene) {
        for (var listed : serverScenes) {
            if (listed == scene) {
                return true;
            }
        }
        return false;
    }

    public boolean hasDirector() {
        return director.getSession() != null;
    }

    public void openDirector() {
        director.openNew();
    }

    public boolean hasDirectorDraftFailed() {
        return director.hasDraftFailed();
    }

    public boolean hasCutScenes() {
        return hasGameScenes();
    }

    public GameScenes.@Nullable Scene findExampleScene(LoadedTiles tiles) {
        GameScenes.Scene story = null;
        for (var list : List.of(gameScenes, serverScenes)) {
            for (var scene : list) {
                if (scene.tiles().isEmpty()) {
                    if (story == null) {
                        story = scene;
                    }
                } else if (isLoaded(scene, tiles)) {
                    return scene;
                }
            }
        }
        return story;
    }

    public boolean hasScenes() {
        return !scenes.isEmpty() || hasGameScenes();
    }

    public GameScenes.@Nullable Scene takeOpening() {
        var scene = opening;
        opening = null;
        return scene;
    }

    public boolean takeOpeningCopy() {
        var copy = openingCopy;
        openingCopy = false;
        return copy;
    }

    public void drawPanel(ScenePlayer player, GameScenePlayer game, LoadedTiles tiles, Ground ground) {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild("##scenesBody", 0, -director.getSaveBarHeight(), false);
        ImGui.popStyleVar();
        copyButtons.clear();
        othersTop = 0;
        if (scenes.isEmpty() && map == null || scenes.isEmpty() && hasGameScenes()) {
            drawGameBody(game, tiles, ground);
        } else {
            drawBody(player, game, tiles);
        }
        ImGui.dummy(0, 8);
        ImGui.endChild();
        director.drawSaveBar();
    }

    public void drawViewport(boolean playing, boolean directorShown, Viewport viewport, boolean helpOpen) {
        viewTag = "";
        if (helpOpen || !playing && !directorShown) {
            return;
        }
        var items = new ArrayList<String[]>();
        var draw = ImGui.getBackgroundDrawList();
        var strip = viewport;
        if (playing) {
            items.add(new String[] {"Esc", "stop the scene"});
        } else {
            items.addAll(director.getHints());
            var label = director.getViewLabel();
            var right = viewport.right() - GeodataWidgets.BLOCK;
            if (label != null
                    && right - GeodataWidgets.viewTagWidth(label.text()) - GeodataWidgets.BLOCK
                            >= GeodataWidgets.hintStripLeft(viewport) + GeodataWidgets.hintStripFirst(STRIP, items)) {
                viewTag = label.text();
                var left = GeodataWidgets.viewTag(
                        draw,
                        right,
                        viewport.top(),
                        label.text(),
                        label.warning() ? GeodataWidgets.WARNING : GeodataWidgets.MUTED);
                strip = new Viewport(viewport.left(), viewport.top(), left, viewport.bottom());
            }
        }
        GeodataWidgets.hintStrip(draw, strip, STRIP, items, new ArrayList<>());
    }

    private boolean isDirectorFree() {
        var session = director.getSession();
        return session != null && session.isFreeView();
    }

    private @Nullable BakedSceneView directorView() {
        var session = director.getSession();
        return session == null ? null : session.getView();
    }

    private boolean hasGameScenes() {
        return !gameScenes.isEmpty() || !serverScenes.isEmpty();
    }

    private void drawBody(ScenePlayer player, GameScenePlayer game, LoadedTiles tiles) {
        var flown = game.getScene() == null ? director.getExportedScene() : null;
        if (director.isOpen()) {
            director.drawInspector(tiles);
            if (player.getScene() != null) {
                space(GeodataWidgets.SECTION);
                drawLobbySummary();
                drawPlayback(player);
            }
            if (export != null && flown != null) {
                space(GeodataWidgets.SECTION);
                export.drawCard(flown);
            }
            director.drawInto();
            if (!drawOtherScenes()) {
                return;
            }
            if (player.getScene() == null) {
                drawLobbySummary();
                drawPlayback(player);
                space(GeodataWidgets.BLOCK);
            }
        } else {
            drawLobbySummary();
            drawPlayback(player);
            space(GeodataWidgets.BLOCK);
        }
        GeodataWidgets.searchField(
                "##sceneSearch", "Search scenes by name", search, ImGui.getContentRegionAvailX(), false);
        GeodataWidgets.tip("A part of a scene's name, such as Human, Wizard or Warp.");
        var query = search.get().trim().toLowerCase(Locale.ROOT);
        filter(query);
        var rowHeight = SpawnPanelWidgets.listRowHeight() + 2;
        director.drawList(query, tiles, rowHeight);
        GeodataWidgets.section(
                query.isEmpty() ? "SCENES · " + scenes.size() : "SCENES · " + listed.size() + " of " + scenes.size());
        space(GeodataWidgets.ROW);
        if (listed.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No scene matches.");
            return;
        }
        clipper.begin(listed.size(), rowHeight);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                row(listed.get(i), i, player, query, rowHeight);
            }
        }
        clipper.end();
    }

    private void drawGameBody(GameScenePlayer game, LoadedTiles tiles, Ground ground) {
        var playback = hasGameScenes() || game.getScene() != null;
        if (game.getScene() != null) {
            chosen = game.getScene();
        }
        if (director.isOpen()) {
            director.drawInspector(tiles);
            if (game.getScene() != null) {
                space(GeodataWidgets.SECTION);
                drawGameSummary(game);
                if (export != null) {
                    export.draw(chosen);
                }
            } else {
                var flown = director.getExportedScene();
                if (export != null && flown != null) {
                    space(GeodataWidgets.SECTION);
                    export.drawCard(flown);
                }
            }
            director.drawInto();
            if (!drawOtherScenes()) {
                return;
            }
            if (game.getScene() == null && playback) {
                drawGameSummary(game);
                space(GeodataWidgets.BLOCK);
            }
        } else {
            if (playback) {
                drawGameSummary(game);
            }
            if (export != null && (playback || chosen != null)) {
                if (!playback) {
                    space(GeodataWidgets.SECTION);
                }
                export.draw(chosen);
            }
            space(GeodataWidgets.BLOCK);
        }
        GeodataWidgets.searchField(
                "##sceneSearch", "Search scenes by name", search, ImGui.getContentRegionAvailX(), false);
        GeodataWidgets.tip("A part of a scene's name, such as freya, tiat or ssq.");
        var query = search.get().trim().toLowerCase(Locale.ROOT);
        filterGame(query);
        var rowHeight = SpawnPanelWidgets.listRowHeight() + 2;
        director.drawList(query, tiles, rowHeight);
        if (!listedServer.isEmpty()) {
            GeodataWidgets.section("FROM THE SERVER · " + listedServer.size());
            GeodataWidgets.tip(
                    "Boss scenes that no client holds. The AI in the script folder films them, and Cruma plays what it"
                            + " does.");
            space(GeodataWidgets.ROW);
            for (var i = 0; i < listedServer.size(); i++) {
                gameRow(listedServer.get(i), -1 - i, game, tiles, ground, query, rowHeight);
            }
            space(GeodataWidgets.BLOCK);
        }
        if (gameScenes.isEmpty()) {
            if (gameScenesRead && listedServer.isEmpty() && query.isEmpty()) {
                space(GeodataWidgets.BLOCK);
                ImGui.textColored(GeodataWidgets.FAINT, "This client has no cut scenes.");
                space(GeodataWidgets.ROW);
                HudWidgets.guideLink("What a cut scene is", "scenes/what-is-a-cut-scene");
            }
            return;
        }
        GeodataWidgets.section(
                query.isEmpty()
                        ? "CUT SCENES · " + gameScenes.size()
                        : "CUT SCENES · " + listedGame.size() + " of " + gameScenes.size());
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/cut-scene");
        space(GeodataWidgets.ROW);
        if (listedGame.isEmpty()) {
            if (listedServer.isEmpty()) {
                ImGui.textColored(GeodataWidgets.FAINT, "No scene matches.");
            }
            return;
        }
        clipper.begin(listedGame.size(), rowHeight);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                gameRow(listedGame.get(i), i, game, tiles, ground, query, rowHeight);
            }
        }
        clipper.end();
    }

    private boolean drawOtherScenes() {
        space(GeodataWidgets.SECTION);
        othersTop = ImGui.getCursorScreenPosY();
        ImGui.setNextItemOpen(othersOpen);
        othersOpen = GeodataWidgets.header("Other scenes##scenes-others");
        GeodataWidgets.tip("Your scenes and those of the client, to open or copy another one.");
        if (othersOpen) {
            space(GeodataWidgets.ROW);
        }
        return othersOpen;
    }

    private void drawLobbySummary() {
        var jumps = 0;
        for (var scene : scenes) {
            if (scene.warp()) {
                jumps++;
            }
        }
        ImGui.textColored(
                GeodataWidgets.MUTED,
                scenes.size()
                        + (scenes.size() == 1 ? " scene" : " scenes")
                        + (jumps > 0 ? " · " + jumps + (jumps == 1 ? " jump" : " jumps") : "")
                        + (map == null ? "" : " · " + map));
        GeodataWidgets.tip(
                "The camera scenes the client plays on the login and character screens. A jump puts the camera on a"
                        + " point and ends.");
        space(GeodataWidgets.ROW);
    }

    private void drawGameSummary(GameScenePlayer game) {
        ImGui.textColored(GeodataWidgets.MUTED, summary(gameScenes.size(), serverScenes.size()));
        GeodataWidgets.tip(
                "The cut scenes the server starts in the game, with their camera, NPCs, fades, slides and lines.");
        space(GeodataWidgets.ROW);
        drawGamePlayback(game);
    }

    private void drawGamePlayback(GameScenePlayer game) {
        var playing = game.getScene();
        var top = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(playing == null);
        if (HudWidgets.button("scenes/stop", Glyph.STOP, "", BUTTON_WIDTH, BUTTON_HEIGHT, false, true)) {
            game.stop();
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Stop: the scene's NPCs leave, and the camera stays where the scene left it.", "Esc");
        ImGui.sameLine(0, 4);
        if (HudWidgets.button("scenes/loop", Glyph.REPEAT, "", BUTTON_WIDTH, BUTTON_HEIGHT, game.loops(), true)) {
            game.setLoop(!game.loops());
        }
        GeodataWidgets.tip(
                game.loops()
                        ? "Loop is on: a scene starts again when it ends. Click to play scenes once."
                        : "Loop is off: a scene plays once and gives the camera back. Click to play scenes again and"
                                + " again.");
        ImGui.sameLine(0, 8);
        var x = ImGui.getCursorScreenPosX();
        var room = ImGui.getContentRegionAvailX();
        var text = playing == null
                ? "No scene plays"
                : playing.name()
                        + " · "
                        + (game.isReading() ? READING : progress(game.getSeconds(), playing.seconds()));
        ImGui.getWindowDrawList()
                .addText(
                        x,
                        top + (BUTTON_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                        playing == null ? GeodataWidgets.FAINT : HudWidgets.TEXT,
                        HudWidgets.truncateToWidth(text, room));
        ImGui.dummy(room, BUTTON_HEIGHT);
    }

    private void gameRow(
            GameScenes.Scene scene,
            int index,
            GameScenePlayer game,
            LoadedTiles tiles,
            Ground ground,
            String query,
            float rowHeight) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var playing = scene == game.getScene();
        var loaded = isLoaded(scene, tiles);
        ImGui.setNextItemAllowOverlap();
        if (ImGui.invisibleButton("scene/" + index, width, rowHeight - 2)) {
            if (playing) {
                game.stop();
            } else if (loaded) {
                game.play(scene, ground);
                playing = true;
            } else {
                opening = scene;
            }
        }
        var hovered = ImGui.isItemHovered();
        var below = ImGui.getCursorScreenPosY();
        var copyLeft = x + width - MARK_ROOM - COPY_ROOM + 6;
        var copyTop = y + (rowHeight - 2 - COPY_ROOM) * .5f;
        var onCopy = false;
        if (director.getSession() != null) {
            ImGui.setCursorScreenPos(copyLeft, copyTop);
            var copy = ImGui.invisibleButton("scene-copy/" + index, COPY_ROOM, COPY_ROOM);
            onCopy = ImGui.isItemHovered();
            ImGui.setCursorScreenPos(x, below);
            copyButtons.put(scene.name(), new float[] {copyLeft + COPY_ROOM * .5f, copyTop + COPY_ROOM * .5f});
            if (copy) {
                copy(scene, game, tiles);
            }
        }
        GeodataWidgets.tip(
                onCopy
                        ? "Make a copy of "
                                + scene.name()
                                + " in the tray, to change it."
                                + (loaded ? "" : " Its tiles open first.")
                        : playing
                                ? "Stop " + scene.name() + "."
                                : !loaded
                                        ? "Open "
                                                + String.join(", ", scene.tiles())
                                                + " and play "
                                                + scene.name()
                                                + ". The loaded tiles close."
                                        : "Play " + scene.name() + ": " + tip(scene));
        var draw = ImGui.getWindowDrawList();
        var lit = hovered || onCopy;
        if (playing || lit) {
            draw.addRectFilled(x, y, x + width, y + rowHeight - 2, playing ? PLAYING_ROW : GeodataWidgets.HOVER, 6f);
        }
        if (lit && director.getSession() != null) {
            HudWidgets.glyph(
                    draw,
                    Glyph.COPY,
                    copyLeft + (COPY_ROOM - COPY_MARK) * .5f,
                    y + (rowHeight - 2 - COPY_MARK) * .5f,
                    COPY_MARK,
                    onCopy ? HudWidgets.ACCENT : GeodataWidgets.MUTED);
        }
        SpawnPanelWidgets.listRow(
                draw,
                x,
                y,
                width - MARK_ROOM - (lit ? COPY_ROOM : 0),
                playing ? PLAYING : loaded ? MOVING : JUMP,
                scene.name(),
                playing
                        ? game.isReading() ? READING : "Playing · " + progress(game.getSeconds(), scene.seconds())
                        : detail(scene, loaded),
                "",
                query);
        HudWidgets.glyph(
                draw,
                playing ? Glyph.STOP : Glyph.PLAY,
                x + width - 8 - MARK,
                y + (rowHeight - 2 - MARK) * .5f,
                MARK,
                playing ? HudWidgets.ACCENT : hovered ? HudWidgets.TEXT : GeodataWidgets.MUTED);
        if (playing && scene.seconds() > 0) {
            var fraction = (float) Math.clamp(game.getSeconds() / scene.seconds(), 0, 1);
            draw.addRectFilled(
                    x + 8,
                    y + rowHeight - 4,
                    x + 8 + (width - 16) * fraction,
                    y + rowHeight - 2,
                    HudWidgets.ACCENT,
                    1f);
        }
    }

    private void copy(GameScenes.Scene scene, GameScenePlayer game, LoadedTiles tiles) {
        director.askToCopy(scene.name(), () -> {
            game.stop();
            if (isLoaded(scene, tiles)) {
                openCopy(scene);
            } else {
                opening = scene;
                openingCopy = true;
            }
        });
    }

    private void filterGame(String query) {
        if (query.equals(listedFor)) {
            return;
        }
        listedFor = query;
        listedGame.clear();
        listedServer.clear();
        addMatches(gameScenes, listedGame, query);
        addMatches(serverScenes, listedServer, query);
    }

    private void drawPlayback(ScenePlayer player) {
        var playing = player.getScene();
        var top = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(playing == null);
        if (HudWidgets.button("scenes/stop", Glyph.STOP, "", BUTTON_WIDTH, BUTTON_HEIGHT, false, true)) {
            player.stop();
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                "Stop: the camera stays where the scene left it, and the keys and the mouse move it again.", "Esc");
        ImGui.sameLine(0, 4);
        if (HudWidgets.button("scenes/loop", Glyph.REPEAT, "", BUTTON_WIDTH, BUTTON_HEIGHT, player.loops(), true)) {
            player.setLoop(!player.loops());
        }
        GeodataWidgets.tip(
                player.loops()
                        ? "Loop is on: a scene starts again when it ends. Click to play scenes once."
                        : "Loop is off: a scene plays once and gives the camera back. Click to play scenes again and"
                                + " again.");
        ImGui.sameLine(0, 8);
        var x = ImGui.getCursorScreenPosX();
        var room = ImGui.getContentRegionAvailX();
        var text = playing == null
                ? "No scene plays"
                : playing.tag() + " · " + progress(player.getSeconds(), playing.seconds());
        ImGui.getWindowDrawList()
                .addText(
                        x,
                        top + (BUTTON_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                        playing == null ? GeodataWidgets.FAINT : HudWidgets.TEXT,
                        HudWidgets.truncateToWidth(text, room));
        ImGui.dummy(room, BUTTON_HEIGHT);
    }

    private void row(Scene scene, int index, ScenePlayer player, String query, float rowHeight) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var playing = scene == player.getScene();
        ImGui.setNextItemAllowOverlap();
        if (ImGui.invisibleButton("scene/" + index, width, rowHeight - 2)) {
            if (playing) {
                player.stop();
            } else {
                player.play(scene);
                playing = player.isPlaying();
            }
        }
        var hovered = ImGui.isItemHovered();
        var below = ImGui.getCursorScreenPosY();
        var copyLeft = x + width - MARK_ROOM - COPY_ROOM + 6;
        var copyTop = y + (rowHeight - 2 - COPY_ROOM) * .5f;
        var onCopy = false;
        if (director.getSession() != null) {
            ImGui.setCursorScreenPos(copyLeft, copyTop);
            var copy = ImGui.invisibleButton("scene-copy/" + index, COPY_ROOM, COPY_ROOM);
            onCopy = ImGui.isItemHovered();
            ImGui.setCursorScreenPos(x, below);
            copyButtons.put(scene.tag(), new float[] {copyLeft + COPY_ROOM * .5f, copyTop + COPY_ROOM * .5f});
            if (copy) {
                director.askToCopy(scene.tag(), () -> {
                    player.stop();
                    director.openLobbyCopy(scene, mapSha256);
                });
            }
        }
        GeodataWidgets.tip(
                onCopy
                        ? "Make a copy of " + scene.tag() + " in the tray, as a path to change."
                        : playing
                                ? "Stop " + scene.tag() + "."
                                : scene.warp()
                                        ? "Put the camera where " + scene.tag() + " puts it."
                                        : "Play " + scene.tag()
                                                + ": the camera follows it and the keys wait until it ends.");
        var draw = ImGui.getWindowDrawList();
        var lit = hovered || onCopy;
        if (playing || lit) {
            draw.addRectFilled(x, y, x + width, y + rowHeight - 2, playing ? PLAYING_ROW : GeodataWidgets.HOVER, 6f);
        }
        if (lit && director.getSession() != null) {
            HudWidgets.glyph(
                    draw,
                    Glyph.COPY,
                    copyLeft + (COPY_ROOM - COPY_MARK) * .5f,
                    y + (rowHeight - 2 - COPY_MARK) * .5f,
                    COPY_MARK,
                    onCopy ? HudWidgets.ACCENT : GeodataWidgets.MUTED);
        }
        SpawnPanelWidgets.listRow(
                draw,
                x,
                y,
                width - MARK_ROOM - (lit ? COPY_ROOM : 0),
                playing ? PLAYING : scene.warp() ? JUMP : MOVING,
                scene.tag(),
                playing ? "Playing · " + progress(player.getSeconds(), scene.seconds()) : detail(scene),
                "",
                query);
        HudWidgets.glyph(
                draw,
                playing ? Glyph.STOP : Glyph.PLAY,
                x + width - 8 - MARK,
                y + (rowHeight - 2 - MARK) * .5f,
                MARK,
                playing ? HudWidgets.ACCENT : hovered ? HudWidgets.TEXT : GeodataWidgets.MUTED);
        if (playing && scene.seconds() > 0) {
            var fraction = (float) Math.clamp(player.getSeconds() / scene.seconds(), 0, 1);
            draw.addRectFilled(
                    x + 8,
                    y + rowHeight - 4,
                    x + 8 + (width - 16) * fraction,
                    y + rowHeight - 2,
                    HudWidgets.ACCENT,
                    1f);
        }
    }

    private void filter(String query) {
        if (query.equals(listedFor)) {
            return;
        }
        listedFor = query;
        listed.clear();
        for (var scene : scenes) {
            if (query.isEmpty() || scene.tag().toLowerCase(Locale.ROOT).contains(query)) {
                listed.add(scene);
            }
        }
    }

    private static void addMatches(List<GameScenes.Scene> scenes, List<GameScenes.Scene> listed, String query) {
        for (var scene : scenes) {
            if (query.isEmpty() || scene.name().toLowerCase(Locale.ROOT).contains(query)) {
                listed.add(scene);
            }
        }
    }
}

package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.world.CameraPath.Ease;
import com.rost.cruma.core.world.CameraPath.Key;
import com.rost.cruma.core.world.GameScenes.Model;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneFit;
import com.rost.cruma.core.world.SceneFit.Lost;
import com.rost.cruma.core.world.SceneItems;
import com.rost.cruma.core.world.SceneItems.Part;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneOrders.Warning;
import com.rost.cruma.core.world.ShotSpans;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.WindowContext.Keyboard;
import com.rost.cruma.viewer.editing.SceneFileStore;
import com.rost.cruma.viewer.editing.SceneFileStore.Listed;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.LoadedTiles;
import com.rost.cruma.viewer.render.ScreenLens;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.ui.DirectorAdd.Row;
import com.rost.cruma.viewer.ui.DirectorCatalog.Names;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.KeyTimeline.Fold;
import com.rost.cruma.viewer.ui.KeyTimeline.Gesture;
import com.rost.cruma.viewer.ui.KeyTimeline.Lane;
import com.rost.cruma.viewer.ui.KeyTimeline.Mark;
import com.rost.cruma.viewer.ui.KeyTimeline.Span;
import com.rost.cruma.viewer.ui.PathShots.Shot;
import com.rost.cruma.viewer.ui.SceneTracks.Orders;
import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiSelectableFlags;
import imgui.type.ImFloat;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

final class DirectorPanel {
    private static final float BUTTON_WIDTH = 28;
    private static final float BUTTON_HEIGHT = 26;
    private static final float KEY_BUTTON_WIDTH = 92;
    private static final float DELETE_BUTTON_WIDTH = 76;
    private static final float SHOT_BUTTON_WIDTH = 76;
    private static final float ADD_BUTTON_WIDTH = 64;
    private static final int OPEN_DOT = 0xFF5CDBF1;
    private static final int FILE_DOT = 0xFFA4B4C3;
    private static final int REFUSED_DOT = 0xFFFF6256;
    private static final int OPEN_ROW = ImColor.rgb(30, 58, 70);
    private static final float MOST_PLACE = 2_000_000;
    private static final float MOST_SECONDS = 3_600;
    private static final float NEW_SCENE_PADDING = 48;
    private static final float VIEW_BUTTON_WIDTH = 124;
    private static final int SHOT_SPAN = Motion.alpha(HudWidgets.ACCENT, .18f);
    private static final float LEAST_NAME_ROOM = 32;
    private static final double NOON = 12;
    private static final float LAST_HOUR = 24;
    private static final double TENTHS = 10;
    private static final float ACTOR_GUTTER = 148;
    private static final int ACTORS_TO_SEARCH = 6;
    private static final List<String> FIRST_STEPS = List.of(
            "Fly to the first view, press K",
            "Move the playhead on the ruler",
            "Fly on and press K again",
            "Space plays the flight");

    private final KeyTimeline timeline = new KeyTimeline("director-keys");
    private final SceneInspector inspector = new SceneInspector();
    private final DirectorCamera aiming = new DirectorCamera();
    private final DirectorActors actors = new DirectorActors();
    private final DirectorMedia media = new DirectorMedia();
    private final DirectorAdd add = new DirectorAdd(actors, media);
    private final DirectorCatalog catalog = new DirectorCatalog();
    private final DirectorWorld world = new DirectorWorld();
    private final DirectorInto into = new DirectorInto();
    private final FolderPicker picker = FolderPicker.file(
            "Open a scene", "Pick the folder of the scene, and its name below.", "flight" + SceneFileStore.EXTENSION);
    private final ImString name = new ImString(96);
    private final ImFloat hour = new ImFloat();
    private final ImString seed = new ImString(24);
    private final ImString actorSearch = new ImString(64);
    private final float[] number = new float[1];
    private final Set<Integer> openActors = new HashSet<>();
    private @Nullable DirectorSession session;
    private volatile @Nullable Model model;
    private volatile boolean modelRead;
    private volatile int helper;
    private List<Lane> lanes = List.of();
    private @Nullable SceneDocument lanesOf;
    private @Nullable BakedScene lanesBaked;
    private List<Warning> lanesWarned = List.of();
    private Set<Integer> lanesOpen = Set.of();
    private Set<SceneItems.Key> lanesChosen = Set.of();
    private Set<Integer> lanesSelected = Set.of();
    private int lanesBars = -1;
    private List<Shot> shots = List.of();
    private List<Key> shotsOf = List.of();
    private List<ShotSpans.Span> clientShots = List.of();
    private @Nullable SceneDocument clientShotsOf;
    private int tracksOf;
    private List<Lane> shownLanes = List.of();
    private List<Lane> shownFrom = List.of();
    private String shownFilter = "";
    private boolean grown;
    private boolean hadItems;
    private @Nullable Names namesGiven;
    private @Nullable Runnable waiting;
    private String waitingFor = "";
    private boolean waitingNew;
    private volatile List<Listed> listed = List.of();
    private boolean listAsked;
    private boolean nameHeld;
    private boolean hourHeld;
    private boolean seedHeld;
    private boolean settingsOpen;
    private boolean numbersOpen;
    private String summary = "";
    private @Nullable SceneDocument summaryOf;
    private boolean summaryUnsaved;
    private boolean shown;
    private DoubleSupplier viewerHour = () -> NOON;
    private float newLeft;
    private float newTop;
    private float keyLeft;
    private float keyTop;
    private float cutLeft;
    private float closeLeft;
    private float newSceneLeft;
    private float newSceneTop;
    private float saveLeft;
    private float saveTop;
    private float tilesTop;
    private @Nullable Path reload;
    private float saveWidth;
    private float searchLeft;
    private float searchTop;
    private float hourLeft;
    private float hourTop;
    private float seedWidth;
    private float fieldLeft;
    private float fieldTop;
    private float takeTop;
    private float throughLeft;
    private float easeLeft;
    private float easeTop;
    private float easeWidth;
    private float numbersTop;
    private float shotThroughTop;
    private float viewLeft;
    private float viewTop;
    private float lookTop;
    private float barsLeft;
    private float barsTop;
    private float waitingLeft;
    private float waitingTop;
    private float waitingWidth;
    private float settingsTop;
    private float calloutLeft;
    private float calloutTop;
    private float calloutWidth;
    private float quickLeft;
    private float quickTop;
    private float quickWidth;

    record Tray(float key, float add, float shot, float view, boolean frames) {
        float left() {
            return (frames ? 3 * BUTTON_WIDTH + 8 : BUTTON_WIDTH) + GeodataWidgets.ROW + add + GeodataWidgets.ROW;
        }

        float commands() {
            return view + 4 + key + 4 + shot + 4 + DELETE_BUTTON_WIDTH + 4 + BUTTON_WIDTH;
        }
    }

    record ViewLabel(String text, boolean warning) {}

    enum Answer {
        SAVE,
        DISCARD,
        CANCEL
    }

    enum View {
        EMPTY,
        SCENE,
        CHOSEN
    }

    DirectorPanel() {
        inspector.setLists(actors, media, catalog);
        world.setHeights(catalog::getHeight);
        world.onChosen(this::showTrack);
        actors.onTrack(this::showTrack);
    }

    static String detail(SceneDocument document, LoadedTiles tiles) {
        var path = document.path();
        var count = path == null ? 0 : path.getKeys().size();
        var what = document.basedOn().isEmpty()
                ? count + (count == 1 ? " key · " : " keys · ")
                : "Copy of " + document.basedOn() + " · ";
        var text = what + String.format(Locale.ROOT, "%.1f s", document.seconds());
        if (!document.map().isEmpty()) {
            return text + " · " + document.map();
        }
        var missing = missingTiles(document.scene(), tiles);
        if (missing.isEmpty()) {
            return text;
        }
        return text
                + " · "
                + (missing.size() == 1 ? "Tile " + missing.getFirst() : missing.size() + " tiles")
                + " not loaded";
    }

    static List<String> missingTiles(Scene scene, LoadedTiles tiles) {
        var missing = new ArrayList<String>();
        for (var tile : scene.closeTiles()) {
            if (!tiles.has(tile)) {
                missing.add(tile);
            }
        }
        return missing;
    }

    static String time(double seconds, double length) {
        return String.format(Locale.ROOT, "%.2f of %.2f s", seconds, length);
    }

    static Tray tray(float width) {
        var layout = new Tray(KEY_BUTTON_WIDTH, ADD_BUTTON_WIDTH, SHOT_BUTTON_WIDTH, VIEW_BUTTON_WIDTH, true);
        while (layout.left() + layout.commands() > width) {
            if (layout.key() > BUTTON_WIDTH) {
                layout = new Tray(BUTTON_WIDTH, layout.add(), layout.shot(), layout.view(), true);
            } else if (layout.add() > BUTTON_WIDTH) {
                layout = new Tray(BUTTON_WIDTH, BUTTON_WIDTH, layout.shot(), layout.view(), true);
            } else if (layout.shot() > BUTTON_WIDTH) {
                layout = new Tray(BUTTON_WIDTH, BUTTON_WIDTH, BUTTON_WIDTH, layout.view(), true);
            } else if (layout.view() > BUTTON_WIDTH) {
                layout = new Tray(BUTTON_WIDTH, BUTTON_WIDTH, BUTTON_WIDTH, BUTTON_WIDTH, true);
            } else if (layout.frames()) {
                layout = new Tray(BUTTON_WIDTH, BUTTON_WIDTH, BUTTON_WIDTH, BUTTON_WIDTH, false);
            } else {
                break;
            }
        }
        return layout;
    }

    static ViewLabel viewLabel(
            boolean cameraView, boolean onPath, boolean shotsHold, int keys, int shot, double seconds) {
        if (keys == 0 && !shotsHold) {
            return new ViewLabel("No key yet", false);
        }
        if (!cameraView) {
            return new ViewLabel("Free camera · the scene's camera is the frame", false);
        }
        if (!onPath) {
            return new ViewLabel(
                    shotsHold ? "Free camera · the ruler brings the scene back" : "Free camera · K keeps this view",
                    true);
        }
        var time = String.format(Locale.ROOT, "%.2f s", seconds);
        return new ViewLabel(shot > 0 ? "Shot " + shot + " · " + time : time, false);
    }

    static int clientShot(List<ShotSpans.Span> shots, double seconds) {
        var number = 0;
        for (var index = 0; index < shots.size(); index++) {
            if (shots.get(index).start() <= seconds) {
                number = index + 1;
            }
        }
        return number;
    }

    static View view(SceneDocument document, boolean chosen) {
        if (chosen) {
            return View.CHOSEN;
        }
        return document.path() == null && !document.hasContent() ? View.EMPTY : View.SCENE;
    }

    static boolean isChosen(DirectorSession open) {
        return !open.getSelected().isEmpty() || !open.getChosen().isEmpty() || open.getChosenBars() >= 0;
    }

    static boolean doesKeyMatter(int keys, boolean onPath, boolean shotsHold) {
        return !shotsHold && (keys == 0 || !onPath);
    }

    static String summary(SceneDocument document, boolean unsaved, int helper) {
        var parts = new ArrayList<String>();
        parts.add(String.format(Locale.ROOT, "%.1f s", document.seconds()));
        var path = document.path();
        var shots = 0;
        var sounds = 0;
        for (var item : document.items()) {
            if (item.camera() != null && !item.camera().blank()) {
                shots++;
            }
            if (item.music() != null) {
                sounds++;
            }
        }
        if (path != null) {
            parts.add(count(path.getKeys().size(), "key", "keys"));
        } else if (shots > 0) {
            parts.add(count(shots, "shot", "shots"));
        }
        var actors = 0;
        for (var index : SceneOrders.indexes(document.items())) {
            var entry = SceneOrders.entry(document.items(), index);
            var invisible = entry != null
                    && helper > 0
                    && entry.npc() == helper
                    && entry.description().isEmpty();
            if (!invisible) {
                actors++;
            }
        }
        if (actors > 0) {
            parts.add(count(actors, "actor", "actors"));
        }
        if (sounds > 0) {
            parts.add(count(sounds, "sound", "sounds"));
        }
        if (unsaved) {
            parts.add("Not saved");
        }
        return String.join(" · ", parts);
    }

    static List<Span> shotSpans(List<Shot> shots, Set<Integer> selected, IntPredicate choosable) {
        var spans = new ArrayList<Span>(shots.size());
        for (var shot : shots) {
            if (shot.keys() < 2) {
                continue;
            }
            var name = "Shot " + shot.number();
            var tip = name + " · " + shotDetail(shot);
            var chosen = false;
            var left = false;
            for (var index = shot.first(); index <= shot.last(); index++) {
                if (choosable.test(index)) {
                    chosen |= selected.contains(index);
                    left |= !selected.contains(index);
                }
            }
            chosen &= !left;
            spans.add(new Span(shot.start(), shot.end(), SHOT_SPAN, PathShots.spanId(shot.number()), tip, null, chosen)
                    .clip(name));
        }
        return spans;
    }

    static String shotDetail(Shot shot) {
        return String.format(Locale.ROOT, "%.2f to %.2f s · %d keys", shot.start(), shot.end(), shot.keys());
    }

    static String keyTitle(int index, double seconds, int shot) {
        return String.format(Locale.ROOT, "KEY %d · %.2f s · Shot %d", index + 1, seconds, shot);
    }

    static @Nullable Shot wholeShot(List<Shot> shots, Set<Integer> selected, IntPredicate choosable) {
        if (selected.size() < 2) {
            return null;
        }
        for (var shot : shots) {
            if (shot.keys() < 2 || !shot.holds(selected.iterator().next())) {
                continue;
            }
            var count = 0;
            for (var index = shot.first(); index <= shot.last(); index++) {
                if (choosable.test(index)) {
                    if (!selected.contains(index)) {
                        return null;
                    }
                    count++;
                }
            }
            return count == selected.size() ? shot : null;
        }
        return null;
    }

    static String fitNote(List<Lost> lost) {
        var parts = new ArrayList<String>(lost.size());
        for (var part : lost) {
            parts.add(part.text());
        }
        var one = lost.size() == 1 && lost.getFirst().count() == 1;
        return "This client has no place for " + String.join(", ", parts)
                + (one
                        ? ". The Viewer plays it, and a file of this client leaves it out."
                        : ". The Viewer plays them, and a file of this client leaves them out.");
    }

    static String mapNote(String map, boolean saved) {
        return "This scene stands on the map " + map
                + ", and the view shows another place. A video opens that map by itself."
                + (saved ? "" : " Save the scene to open the map here.");
    }

    static String tilesNote(List<String> missing, boolean saved) {
        return (missing.size() == 1 ? "Tile " + missing.getFirst() : missing.size() + " tiles")
                + " of this scene not loaded: the view is empty there. A video loads them by itself."
                + (saved ? "" : " Save the scene to load them here.");
    }

    void setSession(Camera camera, IntSupplier rate, String client, String variant, @Nullable SceneFileStore store) {
        var next = new DirectorSession(camera, rate, client, variant);
        next.setStore(store);
        next.restoreDraft();
        next.setHelper(helper);
        session = next;
    }

    @Nullable
    DirectorSession getSession() {
        return session;
    }

    void setClient(Path client, WorldVariant variant) {
        catalog.setClient(client, variant);
        media.getThumbnails().setClient(client);
        into.setClient(client, variant);
    }

    DirectorInto getInto() {
        return into;
    }

    void drawInto() {
        if (session != null && session.isOpen()) {
            into.draw(session);
        }
    }

    void pollPictures(ViewerUiRenderer ui) {
        media.getThumbnails().poll(ui);
    }

    void setViewerHour(DoubleSupplier hours) {
        viewerHour = hours;
    }

    double getSceneHour() {
        var document = session == null ? null : session.getDocument();
        return document == null || !shown ? Double.NaN : document.hour();
    }

    void setVideoShape(DoubleSupplier shape, Supplier<ScreenLens> lens) {
        world.setFrameShape(shape);
        world.setLens(lens);
    }

    void setModel(@Nullable Model next) {
        model = next;
        modelRead = true;
        if (session != null && next != null) {
            session.setShotsHaveFov(next.camera().contains("CameraFov"));
        }
    }

    void setHelper(int npc) {
        helper = npc;
        if (session != null) {
            session.setHelper(npc);
        }
    }

    boolean takeGrown() {
        var was = grown;
        grown = false;
        return was;
    }

    void openCopy(Scene scene, String sha256) {
        if (session != null) {
            session.openCopy(scene, sha256);
        }
    }

    void setMap(@Nullable String name) {
        if (session != null) {
            session.setMap(name);
        }
    }

    void openLobbyCopy(com.rost.cruma.core.world.MapScenes.Scene scene, String sha256) {
        if (session != null) {
            session.openLobbyCopy(scene, sha256);
        }
    }

    void openFile(Path file) {
        if (session != null) {
            session.open(file);
        }
    }

    @Nullable
    Path takeReload() {
        var file = reload;
        reload = null;
        return file;
    }

    void askToCopy(String title, Runnable copy) {
        if (session != null) {
            guarded(session, "Make a copy of " + title, copy);
        }
    }

    void askClose() {
        if (session != null && session.isOpen()) {
            guarded(session, "Close the scene", session::close);
        }
    }

    void askNewScene() {
        if (session != null) {
            guarded(session, "New scene", session::newScene);
        }
    }

    boolean isAsking() {
        return waiting != null;
    }

    void answer(Answer answer) {
        var action = waiting;
        var open = session;
        if (action == null || open == null) {
            return;
        }
        switch (answer) {
            case SAVE -> {
                if (open.save()) {
                    listAsked = false;
                    waiting = null;
                    action.run();
                }
            }
            case DISCARD -> {
                waiting = null;
                action.run();
            }
            case CANCEL -> waiting = null;
        }
    }

    boolean isOpen() {
        return session != null && session.isOpen();
    }

    void openNew() {
        var open = session;
        if (open != null && !open.isOpen()) {
            guarded(open, "New scene", open::newScene);
        }
    }

    boolean hasDraftFailed() {
        var open = session;
        return open != null && !open.isOpen() && open.hasNoteFailed();
    }

    boolean isPlaying() {
        return session != null && session.isPlaying();
    }

    boolean holdsCamera() {
        return isPlaying() && !session.isFreeView();
    }

    @Nullable
    Scene getExportedScene() {
        if (session == null) {
            return null;
        }
        var document = session.getDocument();
        return document == null || document.path() == null && !document.hasContent() ? null : session.getScene();
    }

    void update(double seconds, boolean shown, boolean cameraFree) {
        this.shown = shown;
        if (session == null) {
            return;
        }
        var document = session.getDocument();
        var items = document != null && document.hasContent();
        grown |= items && !hadItems;
        hadItems = items;
        if (document != null) {
            catalog.ask();
        }
        var names = catalog.getNames();
        if (names != namesGiven) {
            namesGiven = names;
            session.setCatalog(names.table(), names.npcName());
        }
        if (shown) {
            session.update(seconds, cameraFree);
        } else {
            session.release();
            session.update(0, false);
        }
    }

    void close() {
        if (session != null) {
            session.flush();
            session.closeBaking();
        }
        catalog.close();
        media.getThumbnails().close();
    }

    List<String[]> getHints() {
        var items = new ArrayList<String[]>();
        if (session != null && session.getPlacing() != null) {
            items.add(new String[] {"Click", "place"});
            if (session.getPlacing().turns()) {
                items.add(new String[] {"Drag", "turn it"});
            }
            items.add(new String[] {"Esc", "cancel"});
            return items;
        }
        if (world.isDragging()) {
            items.add(new String[] {"Esc", "put it back"});
            return items;
        }
        items.add(new String[] {"Space", isPlaying() ? "hold" : "play"});
        items.add(new String[] {"K", "set a key"});
        items.add(new String[] {"C", "new shot"});
        if (session != null && isChosen(session)) {
            items.add(new String[] {"Del", "delete"});
        }
        items.add(new String[] {EffectEditor.getUndoKey(), "undo"});
        return items;
    }

    @Nullable
    ViewLabel getViewLabel() {
        var open = session;
        if (open == null || !open.isOpen()) {
            return null;
        }
        var document = open.getDocument();
        var keys = open.getKeys();
        int shot;
        if (keys.isEmpty()) {
            if (document != clientShotsOf) {
                clientShotsOf = document;
                clientShots = ShotSpans.of(document.items(), document.seconds());
            }
            shot = clientShot(clientShots, open.getPlayhead());
        } else {
            shot = PathShots.at(shots(keys), open.getPlayhead());
        }
        return viewLabel(
                !open.isFreeView(),
                open.isOnPath(),
                DirectorCamera.shows(document),
                keys.size(),
                shot,
                open.getPlayhead());
    }

    void handleKeys(Keyboard keys) {
        var open = session;
        if (open == null || !open.isOpen() || world.isHolding()) {
            return;
        }
        var command = keys.isPressed(Keyboard.Key.COMMAND);
        if (command && keys.isClicked(Keyboard.Key.Z)) {
            if (keys.isPressed(Keyboard.Key.SHIFT)) {
                open.redo();
            } else {
                open.undo();
            }
            return;
        }
        if (command) {
            return;
        }
        if (keys.isClicked(Keyboard.Key.SPACE)) {
            open.togglePlay();
        }
        if (keys.isClicked(Keyboard.Key.K)) {
            open.setKey();
        }
        if (keys.isClicked(Keyboard.Key.C)) {
            open.cut();
        }
        if (keys.isClicked(Keyboard.Key.DELETE)) {
            open.deleteSelected();
        }
        if (keys.isClicked(Keyboard.Key.COMMA)) {
            open.stepFrames(-1);
        }
        if (keys.isClicked(Keyboard.Key.PERIOD)) {
            open.stepFrames(1);
        }
        if (keys.isClicked(Keyboard.Key.LEFT_BRACKET)) {
            open.nextKey(-1);
        }
        if (keys.isClicked(Keyboard.Key.RIGHT_BRACKET)) {
            open.nextKey(1);
        }
    }

    boolean handleEscape() {
        var open = session;
        if (open == null || !open.isOpen()) {
            return false;
        }
        if (world.cancelDrag(open)) {
            return true;
        }
        if (open.getPlacing() != null) {
            open.cancelPlacing();
            return true;
        }
        if (add.takeEscape(open)) {
            return true;
        }
        if (open.isPlaying()) {
            open.pause();
            return true;
        }
        if (!open.getSelected().isEmpty() || !open.getChosen().isEmpty() || open.getChosenBars() >= 0) {
            open.clearSelection();
            return true;
        }
        return false;
    }

    DirectorWorld getWorld() {
        return world;
    }

    DirectorCatalog getCatalog() {
        return catalog;
    }

    void drawTray(float width, float height) {
        var open = session;
        if (open == null || !open.isOpen()) {
            return;
        }
        var top = ImGui.getCursorScreenPosY();
        var x = ImGui.getCursorScreenPosX();
        drawTransport(open, x, top, width);
        if (!open.isOpen()) {
            return;
        }
        var tracks = top + BUTTON_HEIGHT + GeodataWidgets.ROW;
        var left = height - BUTTON_HEIGHT - GeodataWidgets.ROW;
        timeline.gutter(open.getDocument().hasContent() ? ACTOR_GUTTER : KeyTimeline.GUTTER);
        timeline.setProgress(open.isBaking() ? open.getBakeProgress() : -1);
        if (open.getOpened() != tracksOf) {
            tracksOf = open.getOpened();
            forgetTracks();
        }
        var all = lanes(open);
        drawActorSearch(all, x, tracks);
        ImGui.setCursorScreenPos(x, tracks);
        var gesture = timeline.draw(shown(all), open.length(), open.getPlayhead(), open.frame(), width, left);
        if (gesture != null) {
            act(open, gesture);
        }
    }

    void drawInspector(LoadedTiles tiles) {
        newTop = 0;
        newSceneTop = 0;
        calloutTop = 0;
        quickTop = 0;
        settingsTop = 0;
        hourTop = 0;
        var open = session;
        if (open == null) {
            return;
        }
        readList(open);
        var document = open.getDocument();
        if (document == null) {
            return;
        }
        world.setTiles(tiles);
        drawWaiting(open);
        drawSceneTitle(open);
        space(GeodataWidgets.ROW);
        drawSceneFields(open, document);
        space(GeodataWidgets.ROW);
        var unsaved = open.isUnsaved();
        if (document != summaryOf || unsaved != summaryUnsaved) {
            summaryOf = document;
            summaryUnsaved = unsaved;
            summary = summary(document, unsaved, open.getHelper());
        }
        ImGui.textColored(GeodataWidgets.MUTED, HudWidgets.truncateToWidth(summary, ImGui.getContentRegionAvailX()));
        if (!open.getBakeProblem().isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(open.getBakeProblem(), GeodataWidgets.BLOCKED);
        }
        warnOfFit(document);
        warnOfTiles(open, document, tiles);
        aiming.drawPathWarning(open);
        add.drawPending(open, catalog, model);
        var chosen = isChosen(open);
        if (view(document, chosen) == View.EMPTY && add.getPending() == null && open.getPlacing() == null) {
            drawFirstSteps(open);
        }
        drawSettings(open, document);
        aiming.draw(open, chosen);
        drawKey(open);
    }

    void drawSaveBar() {
        var open = session;
        if (open == null || !open.isOpen()) {
            return;
        }
        var unsaved = open.isUnsaved();
        var status = unsaved ? "Not saved" : open.getFile() == null ? "New scene" : "Saved";
        saveLeft = ImGui.getCursorScreenPosX();
        saveTop = ImGui.getCursorScreenPosY();
        saveWidth = ImGui.getContentRegionAvailX();
        var pressed = GeodataWidgets.undoBar(
                "director-save",
                status,
                status,
                unsaved,
                open.canUndo(),
                open.canRedo(),
                EffectEditor.getUndoKey(),
                "Close",
                unsaved
                        ? "Closes the scene and the tray. It asks first what to do with the changes."
                        : "Closes the scene and the tray.",
                "Save",
                open.getStore() != null,
                open.getStore() == null
                        ? "This run keeps no scenes."
                        : "Saves " + open.getDocument().name() + SceneFileStore.EXTENSION + ".");
        switch (pressed) {
            case GeodataWidgets.BAR_UNDO -> open.undo();
            case GeodataWidgets.BAR_REDO -> open.redo();
            case GeodataWidgets.BAR_ACTION -> save(open);
            case GeodataWidgets.BAR_SECOND -> askClose();
            default -> {
            }
        }
    }

    float getSaveBarHeight() {
        return isOpen() ? GeodataWidgets.SAVE_BAR : 0;
    }

    void drawList(String query, LoadedTiles tiles, float rowHeight) {
        var open = session;
        if (open == null) {
            return;
        }
        readList(open);
        var rows = listed;
        GeodataWidgets.section(rows.isEmpty() ? "MY SCENES" : "MY SCENES · " + rows.size());
        GeodataWidgets.tip("Scenes of your own: a flight of the camera, saved as a file.");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/scene-file");
        space(GeodataWidgets.ROW);
        newLeft = ImGui.getCursorScreenPosX();
        newTop = ImGui.getCursorScreenPosY();
        if (GeodataWidgets.rowButton("New", 0, 2)) {
            guarded(open, "New scene", open::newScene);
        }
        GeodataWidgets.tip("Opens a new scene in the tray under the world. Fly the camera and press K to set a key.");
        ImGui.beginDisabled(open.getStore() == null);
        if (GeodataWidgets.rowButton("Open", 1, 2)) {
            picker.openPath(open.getStore().getFolder().toString());
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                open.getStore() == null ? "This run keeps no scenes." : "Opens a scene file from any folder.");
        var chosen = picker.draw();
        if (chosen != null) {
            guarded(open, "Open " + chosen.getFileName(), () -> openFile(chosen));
        }
        if (!open.getNote().isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.writtenResult(
                    "director-saved",
                    open.getNote(),
                    open.hasNoteFailed() ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN,
                    open.getWritten());
        }
        space(GeodataWidgets.ROW);
        if (rows.isEmpty() && !open.isOpen()) {
            HudWidgets.guideLink("What the video director is and how to film", "scenes/video-director");
            space(GeodataWidgets.ROW);
        }
        for (var index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var title = row.document() == null
                    ? row.file().getFileName().toString()
                    : row.document().name();
            if (query.isEmpty() || title.toLowerCase(Locale.ROOT).contains(query)) {
                drawRow(open, row, title, index, tiles, query, rowHeight);
            }
        }
    }

    void drawMarks(GeoOverlayLines marks, float viewportPoints) {
        if (session != null && session.isOpen()) {
            world.drawMarks(session, marks, viewportPoints);
        }
    }

    boolean drawHandle(Camera camera, float left, float top, float right, float bottom) {
        return session != null && session.isOpen() && world.drawHandle(session, camera, left, top, right, bottom);
    }

    boolean pointsAtActor() {
        return session != null && session.isOpen() && world.isPointing();
    }

    private List<Shot> shots(List<Key> keys) {
        if (keys != shotsOf) {
            shotsOf = keys;
            shots = PathShots.of(keys);
        }
        return shots;
    }

    private void drawActorSearch(List<Lane> all, float x, float y) {
        searchTop = 0;
        if (SceneTracks.actors(all) < ACTORS_TO_SEARCH) {
            actorSearch.set("");
            return;
        }
        searchLeft = x;
        searchTop = y;
        ImGui.setCursorScreenPos(x, y);
        GeodataWidgets.searchField(
                "##director-actor-search", "Search actors", actorSearch, ACTOR_GUTTER - GeodataWidgets.ROW, false);
        GeodataWidgets.tip("Leaves the tracks of the actors it names, by name or Index, and the Camera.");
    }

    private void forgetTracks() {
        openActors.clear();
        actorSearch.set("");
    }

    private List<Lane> shown(List<Lane> all) {
        var filter = actorSearch.get().strip().toLowerCase(Locale.ROOT);
        if (all != shownFrom || !filter.equals(shownFilter)) {
            shownFrom = all;
            shownFilter = filter;
            shownLanes = SceneTracks.matching(all, filter);
        }
        return shownLanes;
    }

    private void showTrack(int actor) {
        var lane = SceneTracks.laneOf(actor);
        if (shownLanes.stream().noneMatch(each -> each.id().equals(lane))) {
            actorSearch.set("");
        }
        timeline.reveal(lane);
    }

    private List<Lane> lanes(DirectorSession open) {
        var document = open.getDocument();
        var baked = open.getBaked();
        var warnings = open.getWarnings();
        if (document == lanesOf
                && baked == lanesBaked
                && warnings == lanesWarned
                && openActors.equals(lanesOpen)
                && open.getChosen().equals(lanesChosen)
                && open.getSelected().equals(lanesSelected)
                && open.getChosenBars() == lanesBars) {
            return lanes;
        }
        lanesOf = document;
        lanesBaked = baked;
        lanesWarned = warnings;
        lanesOpen = Set.copyOf(openActors);
        lanesChosen = new TreeSet<>(open.getChosen());
        lanesSelected = new TreeSet<>(open.getSelected());
        lanesBars = open.getChosenBars();
        var keys = open.getKeys();
        var shots = shots(keys);
        var marks = new ArrayList<Mark>(keys.size());
        for (var index = 0; index < keys.size(); index++) {
            var key = keys.get(index);
            if (!open.isChoosable(index)) {
                marks.add(Mark.quiet(Integer.toString(index), key.seconds()));
                continue;
            }
            var movable = open.isMovable(index);
            var ends = index + 1 < keys.size() && keys.get(index + 1).seconds() == key.seconds();
            var starts = index > 0 && keys.get(index - 1).seconds() == key.seconds();
            var tip = String.format(Locale.ROOT, "Key %d at %.2f s", index + 1, key.seconds())
                    + (ends
                            ? ". It ends shot " + shotOf(shots, index)
                            : starts ? ". It starts shot " + shotOf(shots, index) : "")
                    + (movable
                            ? ""
                            : key.rides()
                                    ? ". It rides on the way between two points of the map, and only its field of view"
                                            + " can change"
                                    : ". Even speed sets its time: turn it off to move this key");
            marks.add(new Mark(
                            Integer.toString(index),
                            key.seconds(),
                            open.getSelected().contains(index),
                            movable,
                            tip)
                    .nudged(ends == starts ? 0 : ends ? -KeyTimeline.CUT_NUDGE : KeyTimeline.CUT_NUDGE));
        }
        var warned = new HashSet<SceneItems.Key>();
        for (var warning : warnings) {
            warned.add(warning.key());
        }
        var spans = shotSpans(shots, open.getSelected(), open::isChoosable);
        lanes = document.hasContent()
                ? SceneTracks.lanes(
                        document,
                        marks,
                        spans,
                        baked,
                        openActors,
                        open.getChosen()::contains,
                        open.getChosenBars(),
                        new Orders(open.getWalks(), warned, catalog.getNames().npcName(), open.getHelper()))
                : List.of(new Lane("Camera", "Camera", marks, spans, Fold.NONE, false, false));
        return lanes;
    }

    private void drawSceneTitle(DirectorSession open) {
        space(waiting != null ? GeodataWidgets.SECTION : GeodataWidgets.ROW);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        ImGui.textColored(GeodataWidgets.MUTED, "SCENE");
        GeodataWidgets.tip("The scene that is open in the tray. Its name is the name of its file.");
        var after = ImGui.getCursorScreenPosY();
        var label = "New scene";
        var buttonWidth = ImGui.calcTextSizeX(label) + NEW_SCENE_PADDING;
        newSceneLeft = x + width - buttonWidth;
        newSceneTop = y + (ImGui.getFontSize() - HudWidgets.CONTROL_HEIGHT) / 2;
        ImGui.setCursorScreenPos(newSceneLeft, newSceneTop);
        if (HudWidgets.button(
                "director/new-scene", Glyph.PLUS, label, buttonWidth, HudWidgets.CONTROL_HEIGHT, false, true)) {
            askNewScene();
        }
        GeodataWidgets.tip(
                open.isUnsaved()
                        ? "Closes this scene and opens an empty one. It asks first what to do with the changes."
                        : "Closes this scene and opens an empty one.");
        ImGui.setCursorScreenPos(x, after);
    }

    private void drawFirstSteps(DirectorSession open) {
        space(GeodataWidgets.BLOCK);
        calloutLeft = ImGui.getCursorScreenPosX();
        calloutTop = ImGui.getCursorScreenPosY();
        calloutWidth = ImGui.getContentRegionAvailX();
        if (GeodataWidgets.callout(
                "director-first-key", Glyph.CAMERA, "No key yet", "Keys make the flight", "Set key", FIRST_STEPS)) {
            open.setKey();
        }
        GeodataWidgets.tip("Puts a key at the playhead with what the camera shows.", "K");
        GeodataWidgets.section("QUICK START");
        GeodataWidgets.tip("Moves that make editable keys from the view now.");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/camera-path");
        space(GeodataWidgets.ROW);
        quickLeft = ImGui.getCursorScreenPosX();
        quickTop = ImGui.getCursorScreenPosY();
        quickWidth = ImGui.getContentRegionAvailX();
        var rows = new Row[] {Row.ORBIT, Row.FLY_TO, Row.REVEAL};
        for (var column = 0; column < rows.length; column++) {
            var row = rows[column];
            if (GeodataWidgets.rowButton(row.getLabel().replace("…", ""), column, rows.length)) {
                add.ask(open, row);
            }
            GeodataWidgets.tip(row.getTip());
        }
    }

    private void drawSettings(DirectorSession open, SceneDocument document) {
        space(GeodataWidgets.SECTION);
        settingsTop = ImGui.getCursorScreenPosY();
        ImGui.setNextItemOpen(settingsOpen);
        settingsOpen = GeodataWidgets.header("Settings##director-settings");
        GeodataWidgets.tip("The hour and the seed of the scene, and how its path moves.");
        if (!settingsOpen) {
            return;
        }
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing) / 2;
        hourLeft = ImGui.getCursorScreenPosX();
        hourTop = ImGui.getCursorScreenPosY();
        seedWidth = width + spacing;
        GeodataWidgets.columns(
                () -> {
                    drawHour(open, document, width);
                    return false;
                },
                () -> {
                    drawSeed(open, document, width);
                    return false;
                });
        var path = document.path();
        if (path != null) {
            space(GeodataWidgets.ROW);
            var even = HudWidgets.switchRow(
                    "Even speed",
                    path.hasEvenSpeed(),
                    true,
                    "The camera moves at one speed from the first key to the last, so the length of the path sets the"
                            + " time of the keys between them.");
            if (even != path.hasEvenSpeed()) {
                open.setEvenSpeed(even);
            }
        }
        if (!document.basedOn().isEmpty()) {
            space(GeodataWidgets.ROW);
            ImGui.textColored(
                    GeodataWidgets.MUTED,
                    HudWidgets.truncateToWidth("Copy of " + document.basedOn(), ImGui.getContentRegionAvailX()));
        }
    }

    private void warnOfFit(SceneDocument document) {
        var lost = SceneFit.lost(document.items(), model);
        if (!lost.isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(fitNote(lost), GeodataWidgets.WARNING);
        }
    }

    private void warnOfTiles(DirectorSession open, SceneDocument document, LoadedTiles tiles) {
        tilesTop = 0;
        var missing = missingTiles(document.scene(), tiles);
        var otherMap = !document.map().isEmpty() && !document.map().equalsIgnoreCase(open.getMap());
        if (missing.isEmpty() && !otherMap) {
            return;
        }
        var file = open.getFile();
        var saved = file != null && !open.isUnsaved();
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(
                otherMap ? mapNote(document.map(), saved) : tilesNote(missing, saved), GeodataWidgets.WARNING);
        if (saved) {
            space(GeodataWidgets.ROW);
            tilesTop = ImGui.getCursorScreenPosY();
            if (GeodataWidgets.rowButton(otherMap ? "Load this map" : "Load these tiles", 0, 1)) {
                reload = file;
            }
        }
    }

    private void readList(DirectorSession open) {
        var store = open.getStore();
        if (listAsked || store == null) {
            return;
        }
        listAsked = true;
        Thread.ofVirtual().name("cruma-my-scenes").start(() -> {
            try {
                listed = store.list();
            } catch (IOException | RuntimeException problem) {
                LogManager.getLogger(DirectorPanel.class).warn("Could not list the scenes: {}", problem.toString());
            }
        });
    }

    private void save(DirectorSession open) {
        if (open.save()) {
            listAsked = false;
        }
    }

    private void guarded(DirectorSession open, String what, Runnable action) {
        if (open.isUnsaved()) {
            waiting = action;
            waitingFor = what;
            waitingNew = true;
        } else {
            action.run();
        }
    }

    private void drawWaiting(DirectorSession open) {
        var action = waiting;
        if (action == null) {
            return;
        }
        if (waitingNew) {
            waitingNew = false;
            ImGui.setScrollHereY(0);
        }
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(
                "Changes to " + open.getDocument().name() + " are not saved. " + waitingFor + "?",
                GeodataWidgets.WARNING);
        if (open.hasNoteFailed() && !open.getNote().isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(open.getNote(), GeodataWidgets.BLOCKED);
        }
        space(GeodataWidgets.ROW);
        waitingLeft = ImGui.getCursorScreenPosX();
        waitingTop = ImGui.getCursorScreenPosY();
        waitingWidth = ImGui.getContentRegionAvailX();
        ImGui.beginDisabled(open.getStore() == null);
        if (GeodataWidgets.rowButton("Save", 0, 3)) {
            answer(Answer.SAVE);
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                open.getStore() == null
                        ? "This run keeps no scenes."
                        : "Saves the scene and goes on: " + waitingFor + ".");
        if (GeodataWidgets.rowButton("Discard", 1, 3)) {
            answer(Answer.DISCARD);
        }
        GeodataWidgets.tip("Drops the changes and goes on: " + waitingFor + ".");
        if (GeodataWidgets.rowButton("Cancel", 2, 3)) {
            answer(Answer.CANCEL);
        }
        GeodataWidgets.tip("Keeps the scene open as it is.");
    }

    private void drawRow(
            DirectorSession open,
            Listed row,
            String title,
            int index,
            LoadedTiles tiles,
            String query,
            float rowHeight) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var current = row.file().equals(open.getFile());
        if (ImGui.invisibleButton("director-scene/" + index, width, rowHeight - 2)) {
            guarded(open, "Open " + title, () -> openFile(row.file()));
        }
        var hovered = ImGui.isItemHovered();
        GeodataWidgets.tip(row.document() == null ? row.problem() : "Open " + title + " in the tray.");
        var draw = ImGui.getWindowDrawList();
        if (current || hovered) {
            draw.addRectFilled(x, y, x + width, y + rowHeight - 2, current ? OPEN_ROW : GeodataWidgets.HOVER, 6f);
        }
        var document = row.document();
        SpawnPanelWidgets.listRow(
                draw,
                x,
                y,
                width,
                document == null ? REFUSED_DOT : current ? OPEN_DOT : FILE_DOT,
                title,
                document == null ? "Does not open" : detail(document, tiles),
                "",
                query);
    }

    private void drawTransport(DirectorSession open, float x, float top, float width) {
        var layout = tray(width);
        ImGui.setCursorScreenPos(x, top);
        if (layout.frames()) {
            if (HudWidgets.button("director/back", Glyph.STEP_BACK, "", BUTTON_WIDTH, BUTTON_HEIGHT, false, true)) {
                open.stepFrames(-1);
            }
            GeodataWidgets.tip("One frame back.", ",");
            ImGui.sameLine(0, 4);
        }
        if (HudWidgets.button(
                "director/play",
                open.isPlaying() ? Glyph.PAUSE : Glyph.PLAY,
                "",
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                open.isPlaying(),
                true)) {
            open.togglePlay();
        }
        GeodataWidgets.tip(
                open.isPlaying()
                        ? "Hold: the camera stays on the path."
                        : "Play: the camera flies the path from the playhead.",
                "Space");
        if (layout.frames()) {
            ImGui.sameLine(0, 4);
            if (HudWidgets.button("director/step", Glyph.STEP, "", BUTTON_WIDTH, BUTTON_HEIGHT, false, true)) {
                open.stepFrames(1);
            }
            GeodataWidgets.tip("One frame on.", ".");
        }
        ImGui.sameLine(0, GeodataWidgets.ROW);
        add.drawButton(open, catalog, model, modelRead, layout.add(), BUTTON_HEIGHT);
        ImGui.sameLine(0, GeodataWidgets.ROW);
        var state = open.getPath() == null && open.getBaked() == null
                ? open.getDocument().hasContent() ? " · Reading the scene" : ""
                : open.isOnPath() ? open.getPath() == null ? " · On the scene" : " · On the path" : " · Free camera";
        var text = open.getDocument().name() + " · " + time(open.getPlayhead(), open.length()) + state;
        var textLeft = ImGui.getCursorScreenPosX();
        var keyWidth = layout.key();
        var commands = layout.commands();
        var room = x + width - commands - GeodataWidgets.ROW - textLeft;
        if (room >= LEAST_NAME_ROOM) {
            ImGui.getWindowDrawList()
                    .addText(
                            textLeft,
                            top + (BUTTON_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                            HudWidgets.TEXT,
                            HudWidgets.truncateToWidth(text, room));
        }
        ImGui.setCursorScreenPos(x + width - commands, top);
        viewLeft = ImGui.getCursorScreenPosX();
        viewTop = top;
        var cameraView = !open.isFreeView();
        if (HudWidgets.button(
                "director/view",
                Glyph.EYE,
                layout.view() == BUTTON_WIDTH ? "" : "Camera view",
                layout.view(),
                BUTTON_HEIGHT,
                cameraView,
                true)) {
            open.setFreeView(cameraView);
        }
        GeodataWidgets.tip(
                cameraView
                        ? "The view is the scene's camera. A move of the free camera leaves the path until the next"
                                + " press on the ruler."
                        : "The camera stays with you, and the scene's camera shows as a frame with its line in the"
                                + " world.");
        ImGui.sameLine(0, 4);
        keyLeft = ImGui.getCursorScreenPosX();
        keyTop = top;
        if (HudWidgets.button(
                "director/key",
                Glyph.CAMERA,
                keyWidth == BUTTON_WIDTH ? "" : "Set key",
                keyWidth,
                BUTTON_HEIGHT,
                doesKeyMatter(open.getKeys().size(), open.isOnPath(), DirectorCamera.shows(open.getDocument())),
                true)) {
            open.setKey();
        }
        GeodataWidgets.tip(
                open.getKeys().isEmpty() || open.isOnPath()
                        ? "Puts a key at the playhead with what the camera shows. Over a key, it replaces that key."
                        : "The camera has left the path: a key at the playhead keeps what it shows now. Over a key, it"
                                + " replaces that key.",
                "K");
        ImGui.sameLine(0, 4);
        cutLeft = ImGui.getCursorScreenPosX();
        var noCut = open.getNoCut();
        ImGui.beginDisabled(noCut != null);
        var narrowCut = layout.shot() < SHOT_BUTTON_WIDTH;
        if (HudWidgets.button(
                "director/cut",
                narrowCut ? Glyph.SLICE : null,
                narrowCut ? "" : "New shot",
                layout.shot(),
                BUTTON_HEIGHT,
                false,
                true)) {
            open.cut();
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                noCut != null
                        ? noCut
                        : "Ends the shot at the playhead and starts a new one with what the camera shows. The camera"
                                + " jumps there.",
                "C");
        ImGui.sameLine(0, 4);
        var nothing = open.getSelected().isEmpty() && open.getChosen().isEmpty() && open.getChosenBars() < 0;
        ImGui.beginDisabled(nothing);
        if (HudWidgets.button("director/delete", null, "Delete", DELETE_BUTTON_WIDTH, BUTTON_HEIGHT, false, true)) {
            open.deleteSelected();
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(nothing ? "Choose a key on the timeline first." : "Takes the chosen keys away.", "Del");
        ImGui.sameLine(0, 4);
        closeLeft = ImGui.getCursorScreenPosX();
        if (HudWidgets.button("director/close", Glyph.CLOSE, "", BUTTON_WIDTH, BUTTON_HEIGHT, false, true)) {
            askClose();
        }
        GeodataWidgets.tip("Closes the scene and the tray.");
    }

    private void act(DirectorSession open, Gesture gesture) {
        switch (gesture.kind()) {
            case SEEK -> {
                open.pause();
                open.scrub(true);
                open.seek(gesture.seconds());
            }
            case PICKED -> pick(open, gesture.marks().getFirst(), gesture.add());
            case OPENED -> {
                var mark = gesture.marks().getFirst();
                if (SceneItems.Key.of(mark) == null) {
                    open.lookThrough(Integer.parseInt(mark));
                }
            }
            case DRAGGED -> open.moveSelected(gesture.seconds());
            case RELEASED -> {
                open.scrub(false);
                open.settle();
            }
            case BOXED -> {
                var indexes = new ArrayList<Integer>(gesture.marks().size());
                var keys = new ArrayList<SceneItems.Key>(gesture.marks().size());
                for (var mark : gesture.marks()) {
                    var key = SceneItems.Key.of(mark);
                    if (key == null) {
                        indexes.add(Integer.parseInt(mark));
                    } else {
                        keys.add(key);
                    }
                }
                open.selectAll(indexes, gesture.add());
                open.selectItems(keys);
            }
            case CLEARED -> open.clearSelection();
            case FOLDED -> {
                var actor = SceneTracks.actorOf(gesture.marks().getFirst());
                if (!openActors.remove(actor)) {
                    openActors.add(actor);
                }
                chooseFirstOrder(open, actor);
            }
            case SPANNED -> {
                var span = gesture.marks().getFirst();
                if (span.isEmpty()) {
                    open.paintBars(gesture.from(), gesture.seconds());
                } else if (SceneTracks.barsOf(span) >= 0) {
                    open.moveBars(SceneTracks.barsOf(span), gesture.from(), gesture.seconds());
                }
            }
            case SPAN_PICKED -> {
                var span = gesture.marks().getFirst();
                var shot = PathShots.numberOf(span);
                if (shot > 0) {
                    chooseShot(open, shot, gesture.add());
                } else {
                    open.chooseBars(SceneTracks.barsOf(span));
                }
            }
        }
    }

    private void chooseShot(DirectorSession open, int number, boolean add) {
        for (var shot : shots(open.getKeys())) {
            if (shot.number() == number) {
                var indexes = new ArrayList<Integer>(shot.keys());
                for (var index = shot.first(); index <= shot.last(); index++) {
                    if (open.isChoosable(index)) {
                        indexes.add(index);
                    }
                }
                open.selectAll(indexes, add);
            }
        }
    }

    private void drawSceneFields(DirectorSession open, SceneDocument document) {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing) / 2;
        GeodataWidgets.columns(
                () -> {
                    if (!nameHeld && !name.get().equals(document.name())) {
                        name.set(document.name());
                    }
                    var left = GeodataWidgets.compactText("Name", "##director-name", "Name of the scene", name, width);
                    nameHeld = ImGui.isItemActive();
                    if (left) {
                        open.rename(name.get());
                    }
                    return false;
                },
                () -> field(
                        open,
                        "Length",
                        "##director-length",
                        open.length(),
                        width,
                        .05f,
                        (float) open.frame(),
                        MOST_SECONDS,
                        "%.2f s",
                        open::setLength,
                        "How long the scene plays. Drag or double-click to type. It ends no sooner than its last"
                                + " key."));
    }

    private void drawSeed(DirectorSession open, SceneDocument document, float width) {
        if (!seedHeld) {
            seed.set(Long.toString(document.seed()));
        }
        var left = GeodataWidgets.compactText("Seed", "##director-seed", "", seed, width);
        seedHeld = ImGui.isItemActive();
        GeodataWidgets.tip(
                "What makes the random choices of the scene the same in each run, such as which animation of a skill"
                        + " plays. A new scene draws one. Type the number of a scene to get its choices again.");
        if (left) {
            try {
                open.setSeed(Long.parseLong(seed.get().strip()));
            } catch (NumberFormatException wrong) {
                seedHeld = false;
            }
        }
    }

    private void drawHour(DirectorSession open, SceneDocument document, float width) {
        var set = !Double.isNaN(document.hour());
        if (!hourHeld) {
            hour.set((float) (set ? document.hour() : Math.round(viewerHour.getAsDouble() * TENTHS) / TENTHS));
        }
        var result = GeodataWidgets.compactToggle("Hour", "##director-hour", set, hour, width);
        hourHeld = ImGui.isItemActive();
        if (result.toggled()) {
            open.setHour(set ? Double.NaN : hour.get());
        } else if (result.left()) {
            open.setHour(Math.clamp(hour.get(), 0, LAST_HOUR));
        }
        GeodataWidgets.tip(
                "The hour of the game day of this scene, from 0 to 24, such as 18.5. The world shows it while the tray"
                    + " does, and a video and Blender take it. Off, they take the hour of the Viewer. The client has"
                    + " no place for it.");
    }

    private void drawBars(DirectorSession open) {
        var spans = open.getBars();
        var index = open.getChosenBars();
        var span = spans.get(index);
        GeodataWidgets.section("BARS");
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing) / 2;
        barsLeft = ImGui.getCursorScreenPosX();
        barsTop = ImGui.getCursorScreenPosY();
        GeodataWidgets.columns(
                () -> field(
                        open,
                        "From",
                        "##director-bars-from",
                        span.from(),
                        width,
                        .01f,
                        0,
                        MOST_SECONDS,
                        "%.2f s",
                        value -> open.moveBars(index, open.onFrame(value), span.to()),
                        "Where the black bars come on."),
                () -> field(
                        open,
                        "To",
                        "##director-bars-to",
                        span.to(),
                        width,
                        .01f,
                        0,
                        MOST_SECONDS,
                        "%.2f s",
                        value -> open.moveBars(index, span.from(), open.onFrame(value)),
                        "Where they go off."));
    }

    private void drawLookAt(DirectorSession open, int index, Key key) {
        var actors = open.getActors();
        lookTop = 0;
        if (actors.isEmpty() && key.lookAt() == 0) {
            return;
        }
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Look at");
        lookTop = ImGui.getCursorScreenPosY();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        var shown = "Its own turn";
        for (var actor : actors) {
            if (actor.index() == key.lookAt()) {
                shown = actor.name();
            }
        }
        if (HudWidgets.beginCombo("##director-look", shown, 0)) {
            if (ImGui.selectable("Its own turn", key.lookAt() == 0, ImGuiSelectableFlags.None)) {
                open.setLookAt(index, 0);
            }
            for (var actor : actors) {
                if (ImGui.selectable(
                        actor.name() + "##" + actor.index(),
                        actor.index() == key.lookAt(),
                        ImGuiSelectableFlags.None)) {
                    open.setLookAt(index, actor.index());
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "Follows an actor by where it stands in the scene. Yaw and Pitch then count only while the actor is"
                        + " not there.");
    }

    private void drawKey(DirectorSession open) {
        fieldTop = 0;
        takeTop = 0;
        easeTop = 0;
        numbersTop = 0;
        shotThroughTop = 0;
        if (open.getChosenBars() >= 0) {
            drawBars(open);
            return;
        }
        var item = open.getChosenKey();
        if (item != null) {
            var order = open.getChosenOrder();
            if (order != null) {
                actors.drawOrders(open, catalog, order);
            }
            var still = open.getChosenKey();
            if (still != null) {
                inspector.draw(open, still, model);
            }
            return;
        }
        var index = open.getSelectedKey();
        if (index < 0) {
            var shot = open.getChosen().isEmpty()
                    ? wholeShot(shots(open.getKeys()), open.getSelected(), open::isChoosable)
                    : null;
            if (shot != null) {
                drawShot(open, shot);
                return;
            }
            var chosen = open.getSelected().size() + open.getChosen().size();
            if (chosen > 1) {
                GeodataWidgets.section("KEYS");
                space(GeodataWidgets.ROW);
                ImGui.textColored(GeodataWidgets.MUTED, chosen + " keys chosen");
            }
            return;
        }
        var key = open.getKeys().get(index);
        GeodataWidgets.section(keyTitle(index, key.seconds(), shotOf(shots(open.getKeys()), index)));
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/key");
        space(GeodataWidgets.ROW);
        fieldLeft = ImGui.getCursorScreenPosX();
        drawViewButtons(open, index, key);
        drawLookAt(open, index, key);
        space(GeodataWidgets.ROW);
        drawEase(open, index, key);
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var half = (ImGui.getContentRegionAvailX() - spacing) / 2;
        var movable = open.isMovable(index);
        GeodataWidgets.columns(
                () -> field(
                        open,
                        "Field of view",
                        "##director-fov",
                        key.fov(),
                        half,
                        .25f,
                        1,
                        170,
                        "%.1f°",
                        value -> open.editKey(
                                index, key.with(key.x(), key.y(), key.z(), key.yaw(), key.pitch(), key.roll(), value)),
                        "How wide the view is. A smaller number comes closer."),
                () -> {
                    ImGui.beginDisabled(!movable);
                    field(
                            open,
                            "Time",
                            "##director-time",
                            key.seconds(),
                            half,
                            .01f,
                            0,
                            MOST_SECONDS,
                            "%.2f s",
                            value -> open.editKey(index, key.withSeconds(open.onFrame(value))),
                            "");
                    ImGui.endDisabled();
                    GeodataWidgets.tip(
                            movable
                                    ? "The moment of this key in the scene."
                                    : "Even speed sets the time of this key. Turn it off to change it.");
                    return false;
                });
        drawNumbers(open, index, key);
    }

    private void drawViewButtons(DirectorSession open, int index, Key key) {
        takeTop = ImGui.getCursorScreenPosY();
        var free = open.isFreeView() || !open.isOnPath();
        ImGui.beginDisabled(!free || key.rides());
        if (GeodataWidgets.rowButton("Take the view", 0, 2)) {
            open.takeView(index);
        }
        ImGui.endDisabled();
        if (key.rides()) {
            GeodataWidgets.tip("The way of the map gives this key its place and its turn.");
        } else if (!free) {
            GeodataWidgets.tip(
                    open.getStandsIn() == index
                            ? "The view is this key. Fly the free camera first."
                            : "The view is on the path. Fly the free camera first.");
        } else {
            GeodataWidgets.tip(
                    "Gives this key what the camera shows now, as K does at its moment. Its moment, its ease and Look"
                            + " at stay.");
        }
        var through = GeodataWidgets.rowButton("Look through", 1, 2);
        throughLeft = ImGui.getItemRectMinX();
        if (through) {
            open.lookThrough(index);
        }
        GeodataWidgets.tip("Puts the camera in this key, at its moment.", "Double-click the key on the timeline");
    }

    private void drawEase(DirectorSession open, int index, Key key) {
        ImGui.textColored(GeodataWidgets.MUTED, "Ease");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/easing");
        easeLeft = ImGui.getCursorScreenPosX();
        easeTop = ImGui.getCursorScreenPosY();
        easeWidth = ImGui.getContentRegionAvailX();
        var corner = key.ease() == Ease.LINEAR;
        ImGui.beginDisabled(key.rides());
        var clicked =
                GeodataWidgets.segmented("director-ease", new String[] {"Smooth", "Corner"}, corner ? 1 : 0, easeWidth);
        ImGui.endDisabled();
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            HudWidgets.showTooltip(
                    key.rides()
                            ? "The way of the map gives this key its ease."
                            : GeodataWidgets.hoveredSegment(easeLeft, easeWidth, 2) == 0
                                    ? "The path bends through the key and keeps its speed."
                                    : "A corner: the path comes and goes on straight lines, and the turn and the field"
                                            + " of view move evenly.");
        }
        if (clicked >= 0 && clicked == 1 != corner) {
            open.editKey(index, key.withEase(clicked == 1 ? Ease.LINEAR : Ease.SMOOTH));
            open.settle();
        }
    }

    private void drawNumbers(DirectorSession open, int index, Key key) {
        space(GeodataWidgets.SECTION);
        numbersTop = ImGui.getCursorScreenPosY();
        ImGui.setNextItemOpen(numbersOpen);
        numbersOpen = GeodataWidgets.header("Numbers##director-numbers");
        GeodataWidgets.tip("Where the key stands and how it turns, as numbers.");
        if (!numbersOpen) {
            return;
        }
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - 2 * spacing) / 3;
        fieldTop = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(key.rides());
        GeodataWidgets.columns(
                () -> field(
                        open,
                        "X",
                        "##director-x",
                        key.x(),
                        width,
                        1,
                        -MOST_PLACE,
                        MOST_PLACE,
                        "%.1f",
                        value -> open.editKey(
                                index,
                                key.with(value, key.y(), key.z(), key.yaw(), key.pitch(), key.roll(), key.fov())),
                        "Where the camera stands. Drag or double-click to type."),
                () -> field(
                        open,
                        "Y",
                        "##director-y",
                        key.y(),
                        width,
                        1,
                        -MOST_PLACE,
                        MOST_PLACE,
                        "%.1f",
                        value -> open.editKey(
                                index,
                                key.with(key.x(), value, key.z(), key.yaw(), key.pitch(), key.roll(), key.fov())),
                        "Where the camera stands. Drag or double-click to type."),
                () -> field(
                        open,
                        "Z",
                        "##director-z",
                        key.z(),
                        width,
                        1,
                        -MOST_PLACE,
                        MOST_PLACE,
                        "%.1f",
                        value -> open.editKey(
                                index,
                                key.with(key.x(), key.y(), value, key.yaw(), key.pitch(), key.roll(), key.fov())),
                        "The height of the camera. Drag or double-click to type."));
        space(GeodataWidgets.ROW);
        GeodataWidgets.columns(
                () -> field(
                        open,
                        "Yaw",
                        "##director-yaw",
                        key.yaw(),
                        width,
                        .25f,
                        -720,
                        720,
                        "%.1f°",
                        value -> open.editKey(
                                index, key.with(key.x(), key.y(), key.z(), value, key.pitch(), key.roll(), key.fov())),
                        "Which way the camera looks: 0 along X and 90 along Y."),
                () -> field(
                        open,
                        "Pitch",
                        "##director-pitch",
                        key.pitch(),
                        width,
                        .25f,
                        -Camera.MAX_PITCH_DEGREES,
                        Camera.MAX_PITCH_DEGREES,
                        "%.1f°",
                        value -> open.editKey(
                                index, key.with(key.x(), key.y(), key.z(), key.yaw(), value, key.roll(), key.fov())),
                        "How far the camera looks up or down."),
                () -> field(
                        open,
                        "Roll",
                        "##director-roll",
                        key.roll(),
                        width,
                        .25f,
                        -720,
                        720,
                        "%.1f°",
                        value -> open.editKey(
                                index, key.with(key.x(), key.y(), key.z(), key.yaw(), key.pitch(), value, key.fov())),
                        "How far the view tips: above 0 its top goes to the right."));
        ImGui.endDisabled();
    }

    private void drawShot(DirectorSession open, Shot shot) {
        GeodataWidgets.section("SHOT " + shot.number() + " · " + shotDetail(shot));
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/shot");
        space(GeodataWidgets.ROW);
        shotThroughTop = ImGui.getCursorScreenPosY();
        fieldLeft = ImGui.getCursorScreenPosX();
        if (GeodataWidgets.rowButton("Look through", 0, 1)) {
            open.lookThrough(shot.first());
        }
        GeodataWidgets.tip("Puts the camera at the start of this shot.");
    }

    private boolean field(
            DirectorSession open,
            String label,
            String id,
            double value,
            float width,
            float speed,
            float min,
            float max,
            String format,
            DoubleConsumer changed,
            String tip) {
        number[0] = (float) value;
        var left = GeodataWidgets.compactDrag(label, id, number, width, speed, min, max, format, GeodataWidgets.MUTED);
        if (number[0] != (float) value) {
            changed.accept(number[0]);
        }
        if (left) {
            open.settle();
        }
        if (!tip.isEmpty()) {
            GeodataWidgets.tip(tip);
        }
        return false;
    }

    private static String count(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private static int shotOf(List<Shot> shots, int index) {
        for (var shot : shots) {
            if (shot.holds(index)) {
                return shot.number();
            }
        }
        return 0;
    }

    private static void chooseFirstOrder(DirectorSession open, int actor) {
        for (var key : open.getItemKeys()) {
            if (key.part() == Part.ORDER && open.order(key).index() == actor) {
                open.select(key, false);
                return;
            }
        }
    }

    private static void pick(DirectorSession open, String mark, boolean add) {
        var key = SceneItems.Key.of(mark);
        if (key != null) {
            open.select(key, add);
            return;
        }
        var index = Integer.parseInt(mark);
        if (!add) {
            open.clearSelection();
        }
        open.select(index, add);
    }
}

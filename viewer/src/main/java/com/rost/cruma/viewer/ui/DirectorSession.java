package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.editing.ShotBake;
import com.rost.cruma.core.export.PathFit;
import com.rost.cruma.core.export.PathFit.Fit;
import com.rost.cruma.core.world.CameraPath;
import com.rost.cruma.core.world.CameraPath.Ease;
import com.rost.cruma.core.world.CameraPath.Key;
import com.rost.cruma.core.world.CameraPath.Places;
import com.rost.cruma.core.world.CameraPath.Pose;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.LobbyPath;
import com.rost.cruma.core.world.MapScenes;
import com.rost.cruma.core.world.MapScenes.Vector;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneFields;
import com.rost.cruma.core.world.SceneItems;
import com.rost.cruma.core.world.SceneItems.Bars;
import com.rost.cruma.core.world.SceneItems.Moved;
import com.rost.cruma.core.world.SceneItems.Part;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneOrders.Bodies;
import com.rost.cruma.core.world.SceneOrders.Sequences;
import com.rost.cruma.core.world.SceneOrders.Skills;
import com.rost.cruma.core.world.SceneOrders.Speeds;
import com.rost.cruma.core.world.SceneOrders.Walk;
import com.rost.cruma.core.world.SceneOrders.Warning;
import com.rost.cruma.core.world.SceneScreens;
import com.rost.cruma.core.world.ShotMath;
import com.rost.cruma.core.world.ShotMath.Aim;
import com.rost.cruma.core.world.ShotMath.Target;
import com.rost.cruma.core.world.ShotSpans;
import com.rost.cruma.core.world.SoundSpans;
import com.rost.cruma.core.world.SoundSpans.Clips;
import com.rost.cruma.core.world.SoundSpans.Span;
import com.rost.cruma.viewer.editing.SceneFileStore;
import com.rost.cruma.viewer.editing.SceneFileStore.Draft;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.geodata.ClientFloors.Hit;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import com.rost.cruma.viewer.render.CameraView;
import com.rost.cruma.viewer.ui.BakedSceneView.Nudge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class DirectorSession {
    public static final int POINT = -1;

    private static final String NEW_NAME = "flight";
    private static final double NEW_SECONDS = 10;
    private static final long DRAFT_NANOS = 500_000_000L;
    private static final double SAME_MOMENT = 1e-9;
    private static final double PLACE_STEPS = 100;
    private static final double ANGLE_STEPS = 1_000;
    private static final double MILLIS = 1_000;
    private static final double HELPER_AHEAD = 500;
    private static final double LEAST_TURN = 16;
    private static final double STANDS_AT = 16;
    private static final double TURN = 0x10000;
    private static final Logger log = LogManager.getLogger(DirectorSession.class);

    private final Camera camera;
    private final IntSupplier rate;
    private final LongSupplier clock;
    private final String client;
    private final String variant;
    private String map = "";
    private final TreeSet<Integer> selected = new TreeSet<>();
    private final TreeSet<SceneItems.Key> chosen = new TreeSet<>();
    private @Nullable SceneFileStore store;
    private @Nullable SceneBaker baker;
    private @Nullable BakedSceneView view;
    private @Nullable Scene asked;
    private @Nullable SceneDocument askedFrom;
    private int askedRate;
    private @Nullable BakedScene baked;
    private boolean asClient;
    private boolean askedShots;
    private boolean scrubbing;
    private @Nullable UndoSteps<SceneDocument> steps;
    private int opened;
    private @Nullable SceneDocument saved;
    private @Nullable Path file;
    private double playhead;
    private boolean playing;
    private boolean onPath;
    private Snapshot placed;
    private boolean flown;
    private float freeFov;
    private @Nullable List<Key> dragKeys;
    private SortedSet<Integer> dragged = new TreeSet<>();
    private @Nullable List<Item> dragItems;
    private Set<SceneItems.Key> draggedItems = Set.of();
    private @Nullable List<Item> dragBars;
    private GameScenes.@Nullable Camera dragShot;
    private SceneItems.@Nullable Key dragOrderKey;
    private @Nullable Actor dragOrderStart;
    private @Nullable Nudge nudge;
    private @Nullable BakedScene nudgedOn;
    private @Nullable SceneDocument runFrom;
    private @Nullable SceneDocument bakedFrom;
    private int chosenBars = -1;
    private boolean free;
    private int shotTarget;
    private volatile int helper;
    private volatile boolean shotsHaveFov = true;
    private @Nullable Aimed aimed;
    private @Nullable SceneDocument aimedIn;
    private String aimProblem = "";
    private @Nullable Fit fit;
    private @Nullable SceneDocument fitIn;
    private @Nullable Placing placing;
    private @Nullable Vector pointed;
    private volatile Skills skills = Skills.ANY;
    private volatile IntFunction<@Nullable String> npcNames = _ -> null;
    private List<Warning> warnings = List.of();
    private @Nullable SceneDocument warnedOf;
    private @Nullable BakedScene warnedBy;
    private @Nullable Skills warnedOn;
    private int warnedRate;
    private List<Walk> walks = List.of();
    private @Nullable SceneDocument walksOf;
    private @Nullable Skills walksOn;
    private @Nullable BakedScene walksBy;
    private long draftAt = -1;
    private String note = "";
    private boolean failed;
    private @Nullable Path written;

    DirectorSession(Camera camera, IntSupplier rate, String client, String variant) {
        this(camera, rate, client, variant, System::nanoTime);
    }

    DirectorSession(Camera camera, IntSupplier rate, String client, String variant, LongSupplier clock) {
        this.camera = camera;
        this.rate = rate;
        this.client = client;
        this.variant = variant;
        this.clock = clock;
    }

    public record Aimed(int item, double moved, double turned, boolean drifts) {}

    public record Standing(int index, String name) {}

    public record Placing(
            Actor order,
            String name,
            double lift,
            boolean shown,
            boolean held,
            boolean lost,
            double height,
            int skill,
            boolean point) {
        public boolean turns() {
            return !point && order.action().enters();
        }

        private Placing at(Actor next, boolean nowShown, boolean nowHeld, boolean nowLost) {
            return new Placing(next, name, lift, nowShown, nowHeld, nowLost, height, skill, point);
        }
    }

    static SceneItems.@Nullable Key getPlacer(List<Item> items, int index, double seconds) {
        SceneItems.Key found = null;
        var moment = 0.0;
        for (var place = 0; place < items.size(); place++) {
            var item = items.get(place);
            moment = Math.max(moment, item.seconds());
            if (moment > seconds) {
                break;
            }
            for (var at = 0; at < item.actors().size(); at++) {
                var order = item.actors().get(at);
                if (order.index() != index) {
                    continue;
                }
                var action = order.action();
                if (action.enters() && order.stands() && SceneOrders.hasPlace(order)
                        || action == Action.MOVE
                        || action == Action.AIRSHIP_MOVE) {
                    found = new SceneItems.Key(Part.ORDER, place, at);
                } else if (action == Action.DESPAWN || action == Action.TELEPORT_BACK) {
                    found = null;
                }
            }
        }
        return found;
    }

    void setMap(@Nullable String name) {
        map = name == null ? "" : name;
    }

    public String getMap() {
        return map;
    }

    void setStore(@Nullable SceneFileStore next) {
        store = next;
    }

    @Nullable
    SceneFileStore getStore() {
        return store;
    }

    void setBaking(@Nullable SceneBaker next, @Nullable BakedSceneView shows) {
        baker = next;
        view = shows;
    }

    void setCatalog(Skills table, IntFunction<@Nullable String> names) {
        skills = table;
        npcNames = names;
    }

    void setHelper(int npc) {
        helper = npc;
    }

    void setShotsHaveFov(boolean has) {
        shotsHaveFov = has;
    }

    public @Nullable BakedScene getBaked() {
        return baked;
    }

    public @Nullable BakedSceneView getView() {
        return view;
    }

    public boolean isBaking() {
        return baker != null && baker.isBusy();
    }

    public double getBakeProgress() {
        return baker == null ? 0 : baker.getProgress();
    }

    public String getBakeProblem() {
        return baker == null ? "" : baker.getProblem();
    }

    public int getOpened() {
        return opened;
    }

    public boolean isOpen() {
        return steps != null;
    }

    public @Nullable SceneDocument getDocument() {
        return steps == null ? null : steps.getValue();
    }

    public @Nullable Scene getScene() {
        var document = getDocument();
        return document == null ? null : document.scene();
    }

    public @Nullable CameraPath getPath() {
        var document = getDocument();
        return document == null ? null : document.path();
    }

    public List<Key> getKeys() {
        var path = getPath();
        return path == null ? List.of() : path.getKeys();
    }

    public @Nullable Path getFile() {
        return file;
    }

    public boolean isUnsaved() {
        return isOpen() && !getDocument().equals(saved);
    }

    public double length() {
        var document = getDocument();
        return document == null ? 0 : document.seconds();
    }

    public double frame() {
        return 1.0 / Math.max(1, rate.getAsInt());
    }

    public double onFrame(double seconds) {
        var frames = Math.max(1, rate.getAsInt());
        return Math.round(seconds * frames) / (double) frames;
    }

    public double getPlayhead() {
        return playhead;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isOnPath() {
        return onPath;
    }

    public boolean isFreeView() {
        return free;
    }

    public void setFreeView(boolean on) {
        if (on == free) {
            return;
        }
        free = on;
        if (on) {
            onPath = false;
            giveCameraBack();
        } else {
            place();
        }
    }

    public @Nullable Pose getScenePose() {
        var path = flownPath();
        if (path != null) {
            return path.getPose(playhead, places());
        }
        var shot = baked == null ? null : baked.getView(playhead);
        return shot == null
                ? null
                : new Pose(shot.x(), shot.y(), shot.z(), shot.yaw(), shot.pitch(), shot.roll(), shot.fov());
    }

    public @Nullable BakedScene getLine() {
        return flownPath() == null ? baked : null;
    }

    public boolean isAsClient() {
        return asClient;
    }

    public void setAsClient(boolean on) {
        if (on != asClient) {
            asClient = on;
            askedFrom = null;
            if (onPath) {
                place();
            }
        }
    }

    public void setReplaces(String name) {
        var document = getDocument();
        if (document != null && !name.equals(document.replaces())) {
            settle();
            change(document.withReplaces(name));
        }
    }

    public List<Standing> getStanding() {
        var actors = new ArrayList<Standing>();
        if (baked == null) {
            return actors;
        }
        var seen = new TreeSet<Integer>();
        for (var actor : baked.getStood()) {
            if (baked.place(actor.index(), playhead) != null && seen.add(actor.index())) {
                actors.add(new Standing(actor.index(), actor.name()));
            }
        }
        return actors;
    }

    public List<Standing> getActors() {
        var actors = new ArrayList<Standing>();
        if (baked != null) {
            var seen = new TreeSet<Integer>();
            for (var actor : baked.getStood()) {
                if (seen.add(actor.index())) {
                    actors.add(new Standing(actor.index(), actor.name()));
                }
            }
        }
        return actors;
    }

    public int getShotTarget() {
        var actors = getStanding();
        if (shotTarget == POINT && canAimAtPoint()) {
            return POINT;
        }
        for (var actor : actors) {
            if (actor.index() == shotTarget) {
                return shotTarget;
            }
        }
        var last = lastTarget();
        for (var actor : actors) {
            if (actor.index() == last) {
                return last;
            }
        }
        if (!actors.isEmpty()) {
            return actors.getFirst().index();
        }
        return canAimAtPoint() ? POINT : 0;
    }

    public void setShotTarget(int index) {
        shotTarget = index;
    }

    public boolean canAimAtPoint() {
        return helper > 0;
    }

    public SceneItems.@Nullable Key getChosenShot() {
        var key = getChosenKey();
        var document = getDocument();
        if (key == null || key.part() != Part.SHOT || document == null) {
            return null;
        }
        var shot = document.items().get(key.item()).camera();
        return shot == null || shot.blank() ? null : key;
    }

    public SceneItems.@Nullable Key getChosenOrder() {
        var key = getChosenKey();
        return key == null || key.part() != Part.ORDER ? null : key;
    }

    public Actor order(SceneItems.Key key) {
        return getDocument().items().get(key.item()).actors().get(key.order());
    }

    public Actor getActorOf(SceneItems.Key key) {
        var order = order(key);
        var entry = SceneOrders.entry(getDocument().items(), order.index());
        return entry == null ? order : entry;
    }

    public void place(Actor order, String name, double lift) {
        settle();
        placing = new Placing(order, name, lift, false, false, false, Double.NaN, 0, false);
    }

    public void placePoint(String name) {
        settle();
        pointed = null;
        placing = new Placing(SceneOrders.effectAt("", 0, 0, 0), name, 0, false, false, false, Double.NaN, 0, true);
    }

    public @Nullable Vector getPointed() {
        return pointed;
    }

    public void forgetPoint() {
        pointed = null;
    }

    public void placeSkill(int skill, String name) {
        settle();
        var npc = SceneFields.ACTOR.with(SceneOrders.spawn(helper, 0, 0, 0, 0, 0), "description", name);
        placing = new Placing(npc, name, 0, false, false, false, Double.NaN, skill, false);
    }

    public void placeAt(Actor order, String name, double height) {
        settle();
        placing = new Placing(order, name, 0, false, false, false, height, 0, false);
    }

    public double getMiddleAt(Actor entry) {
        var run = baked;
        var place = run == null ? null : run.place(entry.index(), playhead);
        return place == null ? entry.z() : place.z();
    }

    public @Nullable Placing getPlacing() {
        return placing;
    }

    public void cancelPlacing() {
        placing = null;
    }

    public void pointAt(@Nullable Hit floor) {
        var now = placing;
        if (now != null && !now.held()) {
            placing = floor == null
                    ? now.at(now.order(), false, false, true)
                    : now.at(at(now, floor), true, false, false);
        }
    }

    public void pointAway() {
        var now = placing;
        if (now != null && !now.held()) {
            placing = now.at(now.order(), false, false, false);
        }
    }

    public void press(Hit floor, int yaw) {
        var now = placing;
        if (now != null) {
            var order = at(now, floor);
            placing = now.at(now.turns() ? SceneOrders.facing(order, yaw) : order, true, true, false);
        }
    }

    public void turnTo(double x, double y) {
        var now = placing;
        if (now != null && now.held() && now.turns()) {
            var dx = x - now.order().x();
            var dy = y - now.order().y();
            if (Math.hypot(dx, dy) >= LEAST_TURN) {
                placing = now.at(SceneOrders.facing(now.order(), SceneOrders.yaw(dx, dy)), true, true, false);
            }
        }
    }

    public void letGo() {
        var now = placing;
        var document = getDocument();
        placing = null;
        if (now == null || document == null || !now.shown()) {
            return;
        }
        if (now.point()) {
            pointed = new Vector(now.order().x(), now.order().y(), now.order().z());
            return;
        }
        var order = now.turns()
                ? SceneFields.ACTOR.with(now.order(), "index", SceneItems.freeIndex(document.items(), document.paths()))
                : now.order();
        if (now.skill() > 0) {
            addCast(document, order, now.skill());
        } else {
            addOrder(order);
        }
    }

    public void addOrder(Actor order) {
        var document = getDocument();
        if (document == null) {
            return;
        }
        settle();
        var moved = SceneItems.withNewOrder(document.items(), onFrame(playhead), order, frame() / 2);
        change(document.withItems(moved.items()));
        clearSelection();
        chosen.addAll(moved.keys());
    }

    public void moveOrder(SceneItems.Key key, double x, double y, double z) {
        dragOrder(key, SceneOrders.at(order(key), x, y, z));
    }

    public void dragOrder(SceneItems.Key key, Actor next) {
        var document = getDocument();
        if (document == null || !SceneItems.holds(document.items(), key)) {
            return;
        }
        if (dragOrderStart == null || !key.equals(dragOrderKey)) {
            dragOrderKey = key;
            dragOrderStart = order(key);
        }
        editOrder(key.item(), key.order(), next);
        nudge = nudgeOf(shownOrder(key, dragOrderStart), next);
        nudgedOn = baked;
    }

    public @Nullable Nudge getNudge() {
        return baked != null && nudgedOn == baked ? nudge : null;
    }

    public void cancelDrag() {
        if (steps == null || !steps.isDragging()) {
            return;
        }
        steps.cancel();
        dragKeys = null;
        dragItems = null;
        dragBars = null;
        dragShot = null;
        dragOrderKey = null;
        dragOrderStart = null;
        nudge = null;
        draftLater();
        if (onPath) {
            place();
        }
    }

    public SceneItems.@Nullable Key getPlacer(int index) {
        var document = getDocument();
        return document == null ? null : getPlacer(document.items(), index, playhead + frame() / 2);
    }

    public List<Walk> getWalks() {
        var document = getDocument();
        if (document == null) {
            return List.of();
        }
        if (document != walksOf || skills != walksOn || baked != walksBy) {
            walksOf = document;
            walksOn = skills;
            walksBy = baked;
            walks = SceneOrders.walks(document.items(), skills::takesBody, speeds());
        }
        return walks;
    }

    public List<Warning> getWarnings() {
        var document = getDocument();
        if (document == null) {
            return List.of();
        }
        var frames = Math.max(1, rate.getAsInt());
        if (document != warnedOf || baked != warnedBy || skills != warnedOn || frames != warnedRate) {
            warnedOf = document;
            warnedBy = baked;
            warnedOn = skills;
            warnedRate = frames;
            var run = baked;
            var all = new ArrayList<>(SceneOrders.warnings(
                    document.items(), length(), frame(), skills, run == null ? Sequences.ANY : run::hasSequence));
            all.addAll(
                    SceneScreens.warnings(document.items(), frames, run == null ? SceneScreens.NO_RUN : run::nextMark));
            all.addAll(SceneOrders.crowded(
                    document.items(),
                    length(),
                    frame(),
                    skills::takesBody,
                    run == null ? Bodies.NONE : run::getSize,
                    speeds()));
            warnings = all;
        }
        return warnings;
    }

    public List<Span> getSoundSpans() {
        var document = getDocument();
        var run = baked;
        return document == null
                ? List.of()
                : SoundSpans.of(document.items(), length(), run == null ? Clips.NONE : run::clipSeconds);
    }

    public Span findSoundSpan(SceneItems.Key key) {
        for (var span : getSoundSpans()) {
            if (span.key().equals(key)) {
                return span;
            }
        }
        return null;
    }

    public void addMusic(Music cue) {
        var document = getDocument();
        if (document != null) {
            settle();
            added(document, SceneItems.withNewMusic(document.items(), onFrame(playhead), cue, frame() / 2));
        }
    }

    public void addScreen(Screen screen) {
        var document = getDocument();
        if (document != null) {
            settle();
            added(
                    document,
                    SceneItems.withNewScreens(document.items(), onFrame(playhead), List.of(screen), frame() / 2));
        }
    }

    public void addSlide(String picture, int width, int height) {
        var document = getDocument();
        if (document != null) {
            settle();
            var slide = SceneScreens.slide(picture, width, height);
            var screens = areSlidesOpen() ? List.of(slide) : List.of(SceneScreens.slideStart(), slide);
            var moved = SceneItems.withNewScreens(document.items(), onFrame(playhead), screens, frame() / 2);
            change(document.withItems(moved.items()));
            clearSelection();
            chosen.add(moved.keys().last());
        }
    }

    public boolean areSlidesOpen() {
        var document = getDocument();
        return document != null && SceneItems.areSlidesOpenAt(document.items(), playhead);
    }

    public void fix(Warning warning) {
        var document = getDocument();
        if (document == null) {
            return;
        }
        settle();
        var fixed = SceneOrders.fixed(document.items(), warning, frame() / 2);
        change(document.withItems(fixed.items()));
        clearSelection();
        chosen.addAll(fixed.keys());
    }

    public List<Standing> getCast() {
        var document = getDocument();
        var actors = new ArrayList<Standing>();
        if (document != null) {
            for (var index : SceneOrders.indexes(document.items())) {
                actors.add(new Standing(index, getActorName(index)));
            }
        }
        return actors;
    }

    public String getActorName(int index) {
        var entry = SceneOrders.entry(getDocument().items(), index);
        if (entry != null && SceneTracks.castsOnAPlace(entry, helper)) {
            return SceneTracks.nameOf(entry, npcNames, helper);
        }
        var run = baked;
        var known = run == null ? null : run.getName(index);
        if (known != null) {
            return known;
        }
        return entry == null ? "Index " + index : SceneTracks.nameOf(entry, npcNames);
    }

    public int getHelper() {
        return helper;
    }

    public @Nullable String getNpcName(int npc) {
        return npcNames.apply(npc);
    }

    public @Nullable List<String> getSequences(int index) {
        return baked == null ? null : baked.getSequences(index);
    }

    public double getSequenceSeconds(int index, String name) {
        return baked == null ? Double.NaN : baked.getSequenceSeconds(index, name);
    }

    public void addAnimation(Actor actor, String sequence) {
        var document = getDocument();
        if (document == null) {
            return;
        }
        settle();
        var moment = onFrame(playhead);
        var moved = SceneItems.withNewOrder(
                document.items(), moment, SceneOrders.animation(actor, sequence, false), frame() / 2);
        change(document.withItems(withDeathWait(moved.items(), actor, sequence, moment)));
        clearSelection();
        chosen.addAll(moved.keys());
    }

    public void setSequence(SceneItems.Key key, String sequence) {
        var document = getDocument();
        if (document == null) {
            return;
        }
        settle();
        var order = order(key);
        var items = SceneItems.withOrder(
                document.items(), key.item(), key.order(), SceneFields.ACTOR.with(order, "animation", sequence));
        change(document.withItems(withDeathWait(
                items, order, sequence, document.items().get(key.item()).seconds())));
    }

    public @Nullable ClientFloors getFloors() {
        return baker == null ? null : baker.getFloors();
    }

    public void readFloors(String tile) {
        if (baker != null) {
            baker.readFloors(tile);
        }
    }

    public boolean isReadingFloors() {
        return baker != null && baker.isReadingFloors();
    }

    public @Nullable Aimed getAimed() {
        return aimed != null && getDocument() == aimedIn ? aimed : null;
    }

    public String getAimProblem() {
        return aimProblem;
    }

    public boolean isShotFromView() {
        var document = getDocument();
        if (document == null) {
            return false;
        }
        settle();
        aimProblem = "";
        var shot = getChosenShot();
        return shot != null ? isAimChosen(document, shot) : isAimNew(document);
    }

    public @Nullable Pose getShotEnd(SceneItems.Key key) {
        var document = getDocument();
        var moment = shotEndMoment(key);
        if (document == null || baked == null || Double.isNaN(moment)) {
            return null;
        }
        var item = document.items().get(key.item());
        var target = shotTargetAt(item, moment);
        if (target == null) {
            return null;
        }
        if (document == askedFrom && baked.getScene() == asked) {
            var view = baked.getView(moment);
            return view == null
                    ? null
                    : new Pose(view.x(), view.y(), view.z(), view.yaw(), view.pitch(), view.roll(), view.fov());
        }
        return ShotMath.pose(item.camera(), target, camera.getFov());
    }

    public List<Pose> getShotEnds() {
        var document = getDocument();
        var ends = new ArrayList<Pose>();
        if (document == null || baked == null) {
            return ends;
        }
        for (var span : ShotSpans.of(document.items(), length())) {
            var pose = getShotEnd(new SceneItems.Key(Part.SHOT, span.item(), 0));
            if (pose != null) {
                ends.add(pose);
            }
        }
        return ends;
    }

    public void moveShotEnd(SceneItems.Key key, double dx, double dy, double dz) {
        var document = getDocument();
        var moment = shotEndMoment(key);
        if (document == null || baked == null || Double.isNaN(moment)) {
            return;
        }
        var item = document.items().get(key.item());
        var target = shotTargetAt(item, moment);
        if (target == null) {
            return;
        }
        if (dragShot == null) {
            dragShot = item.camera();
        }
        var rest = ShotMath.pose(dragShot, target, camera.getFov());
        var placed = dx == 0 && dy == 0 && dz == 0
                ? dragShot
                : ShotMath.placed(dragShot, target, rest.x() + dx, rest.y() + dy, rest.z() + dz);
        if (placed != null && !placed.equals(item.camera())) {
            editShot(key.item(), placed);
        }
    }

    public void placeKey(int index, double x, double y, double z) {
        var keys = getKeys();
        if (index >= 0 && index < keys.size()) {
            editKey(index, keys.get(index).at(x, y, z));
        }
    }

    public void poseKey(int index, double x, double y, double z, double yaw, double pitch, double roll) {
        var keys = getKeys();
        if (index >= 0 && index < keys.size()) {
            var key = keys.get(index);
            editKey(index, key.with(x, y, z, yaw, pitch, roll, key.fov()));
        }
    }

    public void takeView(int index) {
        var document = getDocument();
        var path = getPath();
        if (document == null
                || path == null
                || index < 0
                || index >= path.getKeys().size()) {
            return;
        }
        var key = path.getKeys().get(index);
        if (key.rides()) {
            return;
        }
        settle();
        var keys = new ArrayList<>(path.getKeys());
        keys.set(index, taken(key));
        change(document.withPath(new CameraPath(keys, path.hasEvenSpeed())));
        clearSelection();
        selected.add(index);
    }

    public void lookThrough(int index) {
        var keys = getKeys();
        if (index < 0 || index >= keys.size()) {
            return;
        }
        var seconds = keys.get(index).seconds();
        var cut = index + 1 < keys.size() && keys.get(index + 1).seconds() == seconds;
        pause();
        seek(cut ? Math.nextDown(seconds) : seconds);
        setFreeView(false);
    }

    public int getStandsIn() {
        return free || !onPath ? -1 : nearKey(getKeys(), playhead);
    }

    public void removePath() {
        var document = getDocument();
        if (document != null && document.path() != null) {
            settle();
            clearSelection();
            change(document.withPath(null));
            if (onPath) {
                place();
            }
        }
    }

    public void setLookAt(int index, int actor) {
        var keys = getKeys();
        if (index >= 0 && index < keys.size() && keys.get(index).lookAt() != actor) {
            editKey(index, keys.get(index).withLookAt(actor));
            settle();
        }
    }

    public boolean hasMarks() {
        return baked != null && baked.hasMarks();
    }

    public @Nullable Fit makeKeys(double place, double turn) {
        var document = getDocument();
        var run = baked;
        if (document == null || run == null) {
            return null;
        }
        settle();
        var made = PathFit.fit(run.getTimeline().views(), place, turn);
        if (made == null) {
            return null;
        }
        clearSelection();
        change(document.withPath(new CameraPath(made.keys(), false)));
        fit = made;
        fitIn = getDocument();
        place();
        return made;
    }

    public @Nullable Fit getFit() {
        return fit != null && getDocument() == fitIn ? fit : null;
    }

    public List<Bars> getBars() {
        var document = getDocument();
        return document == null ? List.of() : SceneItems.bars(document.items(), length());
    }

    public int getChosenBars() {
        return chosenBars < getBars().size() ? chosenBars : -1;
    }

    public void chooseBars(int index) {
        clearSelection();
        chosenBars = index >= 0 && index < getBars().size() ? index : -1;
    }

    public void paintBars(double from, double to) {
        var document = getDocument();
        if (document == null) {
            return;
        }
        if (dragBars == null) {
            dragBars = document.items();
        }
        drag(document.withItems(SceneItems.withBars(dragBars, from, to, true, frame() / 2, length())));
    }

    public void moveBars(int index, double from, double to) {
        var document = getDocument();
        if (document == null) {
            return;
        }
        if (dragBars == null) {
            dragBars = document.items();
        }
        var spans = SceneItems.bars(dragBars, length());
        if (index < 0 || index >= spans.size()) {
            return;
        }
        var span = spans.get(index);
        var reach = frame() / 2;
        var off = SceneItems.withBars(dragBars, span.from(), span.to(), false, reach, length());
        drag(document.withItems(SceneItems.withBars(off, from, to, true, reach, length())));
        chosenBars = -1;
        var low = Math.min(from, to);
        var now = getBars();
        for (var at = 0; at < now.size(); at++) {
            if (Math.abs(now.get(at).from() - low) <= reach) {
                chosenBars = at;
            }
        }
    }

    public SortedSet<Integer> getSelected() {
        return selected;
    }

    public SortedSet<SceneItems.Key> getChosen() {
        return chosen;
    }

    public int getSelectedKey() {
        return selected.size() == 1 && chosen.isEmpty() ? selected.first() : -1;
    }

    public SceneItems.@Nullable Key getChosenKey() {
        return chosen.size() == 1 && selected.isEmpty() ? chosen.first() : null;
    }

    public String getNote() {
        return note;
    }

    public boolean hasNoteFailed() {
        return failed;
    }

    public @Nullable Path getWritten() {
        return written;
    }

    public void newScene() {
        var name = store == null ? NEW_NAME : store.freeName(NEW_NAME);
        var seed = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        var document =
                SceneDocument.blank(name, NEW_SECONDS, client, variant, seed).withMap(map);
        begin(document, document, null);
        deleteDraft();
        note = "";
        written = null;
    }

    public void openCopy(Scene scene, String sha256) {
        var seed = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        var copy = SceneDocument.copy(scene, client, variant, seed, sha256);
        var document = store == null ? copy : copy.withName(store.freeName(copy.name()));
        begin(document, document, null);
        deleteDraft();
        note = "";
        written = null;
    }

    public void openLobbyCopy(MapScenes.Scene scene, String sha256) {
        var seed = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        var copy = LobbyPath.copy(scene, map, client, variant, seed, sha256, flown ? freeFov : camera.getFov());
        var document = store == null ? copy : copy.withName(store.freeName(copy.name()));
        begin(document, document, null);
        deleteDraft();
        note = "";
        written = null;
    }

    public boolean open(Path path) {
        try {
            var document = SceneFileStore.read(path);
            begin(document, document, path);
            deleteDraft();
            note = "";
            failed = false;
            written = null;
            return true;
        } catch (IOException refused) {
            log.warn("The scene {} does not open: {}", path, refused.toString());
            note = refused.getMessage();
            failed = true;
            written = null;
            return false;
        }
    }

    public void restoreDraft() {
        if (store == null || isOpen()) {
            return;
        }
        try {
            var draft = store.readDraft();
            if (draft != null) {
                begin(draft.document(), savedValue(draft), draft.file());
                note = "A draft of " + draft.document().name() + " came back. Save it to keep it.";
                failed = false;
            }
        } catch (IOException refused) {
            log.warn("The draft does not open: {}", refused.toString());
            note = "The draft does not open: " + refused.getMessage();
            failed = true;
        }
    }

    public void close() {
        release();
        steps = null;
        saved = null;
        file = null;
        placing = null;
        selected.clear();
        chosen.clear();
        chosenBars = -1;
        playhead = 0;
        dragKeys = null;
        dragItems = null;
        dragBars = null;
        dragOrderKey = null;
        dragOrderStart = null;
        nudge = null;
        draftAt = -1;
        forgetBake();
        if (baker != null) {
            baker.release();
        }
        deleteDraft();
    }

    public boolean save() {
        var current = store;
        if (steps == null || current == null) {
            return false;
        }
        settle();
        var target = target(current);
        try {
            SceneFileStore.write(target, getDocument());
            saved = getDocument();
            file = target;
            draftAt = -1;
            deleteDraft();
            note = "Saved " + target.getFileName() + ".";
            failed = false;
            written = target;
            return true;
        } catch (IOException problem) {
            log.warn("Could not save {}", target, problem);
            note = "The scene was not saved: " + problem.getMessage();
            failed = true;
            written = null;
            return false;
        }
    }

    public void togglePlay() {
        if (playing) {
            playing = false;
            return;
        }
        if (!isOpen()) {
            return;
        }
        if (playhead >= length() - SAME_MOMENT) {
            playhead = 0;
        }
        playing = true;
        place();
    }

    public void pause() {
        playing = false;
    }

    public void seek(double seconds) {
        if (isOpen()) {
            playhead = Math.clamp(seconds, 0, length());
            place();
        }
    }

    public void scrub(boolean held) {
        scrubbing = held;
    }

    public void stepFrames(int frames) {
        pause();
        seek(onFrame(playhead) + frames * frame());
    }

    public void nextKey(int direction) {
        pause();
        var keys = getKeys();
        var best = -1;
        var moment = Double.NaN;
        for (var index = 0; index < keys.size(); index++) {
            var seconds = keys.get(index).seconds();
            if (!isChoosable(index)) {
                continue;
            }
            if (direction > 0 ? seconds > playhead + SAME_MOMENT && best < 0 : seconds < playhead - SAME_MOMENT) {
                best = index;
                moment = seconds;
            }
        }
        SceneItems.Key bestItem = null;
        for (var key : getItemKeys()) {
            var seconds = SceneItems.seconds(getDocument().items(), key);
            var nearer = Double.isNaN(moment) || (direction > 0 ? seconds < moment : seconds > moment);
            if (nearer && (direction > 0 ? seconds > playhead + SAME_MOMENT : seconds < playhead - SAME_MOMENT)) {
                bestItem = key;
                moment = seconds;
            }
        }
        if (!Double.isNaN(moment)) {
            clearSelection();
            if (bestItem != null) {
                chosen.add(bestItem);
            } else {
                selected.add(best);
            }
            seek(moment);
        }
    }

    public List<SceneItems.Key> getItemKeys() {
        var document = getDocument();
        var keys = new ArrayList<SceneItems.Key>();
        if (document == null) {
            return keys;
        }
        var items = document.items();
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            if (item.camera() != null && !item.camera().blank()) {
                keys.add(new SceneItems.Key(Part.SHOT, index, 0));
            }
            if (item.screen() != null) {
                keys.add(new SceneItems.Key(Part.SCREEN, index, 0));
            }
            if (item.music() != null) {
                keys.add(new SceneItems.Key(Part.MUSIC, index, 0));
            }
            for (var order = 0; order < item.actors().size(); order++) {
                keys.add(new SceneItems.Key(Part.ORDER, index, order));
            }
        }
        return keys;
    }

    public void setKey() {
        var document = getDocument();
        if (document == null) {
            return;
        }
        settle();
        var keys = new ArrayList<>(getKeys());
        var near = nearKey(keys, playhead);
        var at = near >= 0 ? keys.get(near).seconds() : playhead;
        var key = near >= 0 ? taken(keys.get(near)) : getView(at, Ease.SMOOTH);
        var index = near;
        if (near >= 0) {
            keys.set(near, key);
        } else {
            index = 0;
            while (index < keys.size() && keys.get(index).seconds() < at) {
                index++;
            }
            keys.add(index, key);
        }
        var path = getPath();
        change(document.withPath(new CameraPath(keys, path != null && path.hasEvenSpeed())));
        clearSelection();
        selected.add(index);
        place();
    }

    public void cut() {
        var document = getDocument();
        var path = getPath();
        if (document == null || path == null || getNoCut() != null) {
            return;
        }
        settle();
        var keys = new ArrayList<>(path.getKeys());
        var near = nearKey(keys, playhead);
        var at = near >= 0 ? keys.get(near).seconds() : playhead;
        while (near >= 0 && near + 1 < keys.size() && keys.get(near + 1).seconds() == at) {
            near++;
        }
        var index = near + 1;
        if (near < 0) {
            index = 0;
            while (index < keys.size() && keys.get(index).seconds() < at) {
                index++;
            }
            keys.add(index, getShotEnd(path, keys.get(index - 1), at));
            index++;
        }
        if (near > 0 && keys.get(near - 1).seconds() == at) {
            index = near;
            keys.set(
                    index,
                    getView(at, keys.get(index).ease()).withLink(keys.get(index).link()));
        } else {
            keys.add(index, getView(at, Ease.SMOOTH));
        }
        change(document.withPath(new CameraPath(keys, false)));
        clearSelection();
        selected.add(index);
        playhead = at;
        place();
    }

    public @Nullable String getNoCut() {
        var path = getPath();
        if (path == null || playhead < path.getStart() + frame() / 2) {
            return "A new shot ends the one before it, so it needs a key before the playhead.";
        }
        return path.hasEvenSpeed()
                ? "Even speed spreads the keys along the path, so it has one shot. Turn it off first."
                : null;
    }

    public @Nullable String getNoQuickStart() {
        var path = getPath();
        return path != null && path.hasEvenSpeed()
                ? "Even speed spreads the keys along the path, so it has one shot. Turn it off first."
                : null;
    }

    public double getQuickStartAt() {
        var path = getPath();
        return path != null ? path.getEnd() : playhead;
    }

    public Pose getViewNow() {
        var key = getView(playhead, Ease.SMOOTH);
        return new Pose(key.x(), key.y(), key.z(), key.yaw(), key.pitch(), key.roll(), key.fov());
    }

    public @Nullable Vector getMiddleOf(int index) {
        return places().place(index, playhead);
    }

    public void addShot(List<Key> made) {
        var document = getDocument();
        if (document == null || made.isEmpty() || getNoQuickStart() != null) {
            return;
        }
        settle();
        var next = QuickStarts.withShot(document, made, getQuickStartAt());
        change(next);
        var keys = next.path().getKeys();
        var first = keys.size() - made.size();
        clearSelection();
        for (var index = first; index < keys.size(); index++) {
            selected.add(index);
        }
        lookThrough(first);
    }

    public void deleteSelected() {
        var document = getDocument();
        var path = getPath();
        if (document != null && getChosenBars() >= 0) {
            removeBars(document);
            return;
        }
        if (document == null || selected.isEmpty() && chosen.isEmpty()) {
            return;
        }
        settle();
        var next = document;
        if (!chosen.isEmpty()) {
            next = next.withItems(SceneItems.remove(next.items(), chosen));
            chosen.clear();
        }
        if (path != null && !selected.isEmpty()) {
            var keys = new ArrayList<>(path.getKeys());
            for (var index : selected.reversed()) {
                if (!keys.get(index).rides()) {
                    keys.remove((int) index);
                }
            }
            selected.clear();
            next = next.withPath(keys.isEmpty() ? null : new CameraPath(keys, path.hasEvenSpeed()));
        }
        change(next);
        if (onPath) {
            place();
        }
    }

    public void select(int index, boolean add) {
        if (index < 0 || index >= getKeys().size() || !isChoosable(index)) {
            return;
        }
        if (!add) {
            selected.clear();
            selected.add(index);
        } else if (!selected.remove(index)) {
            selected.add(index);
        }
    }

    public void select(SceneItems.Key key, boolean add) {
        var document = getDocument();
        if (document == null || !SceneItems.holds(document.items(), key)) {
            return;
        }
        if (!add) {
            clearSelection();
            chosen.add(key);
        } else if (!chosen.remove(key)) {
            chosen.add(key);
        }
    }

    public void selectAll(Collection<Integer> indexes, boolean add) {
        if (!add) {
            clearSelection();
        }
        selected.addAll(indexes);
    }

    public void selectItems(Collection<SceneItems.Key> keys) {
        chosen.addAll(keys);
    }

    public void clearSelection() {
        selected.clear();
        chosen.clear();
        chosenBars = -1;
    }

    public boolean isMovable(int index) {
        var path = getPath();
        return path != null
                && !path.getKeys().get(index).rides()
                && (!path.hasEvenSpeed()
                        || index == 0
                        || index == path.getKeys().size() - 1);
    }

    public boolean isChoosable(int index) {
        var keys = getKeys();
        if (index < 0 || index >= keys.size()) {
            return false;
        }
        var key = keys.get(index);
        return !key.rides() || key.link().shown();
    }

    public void moveSelected(double seconds) {
        var document = getDocument();
        if (document == null) {
            return;
        }
        if (dragItems != null || !chosen.isEmpty()) {
            document = moveItems(document, seconds);
        }
        var path = getPath();
        if (path == null || selected.isEmpty()) {
            return;
        }
        if (dragKeys == null) {
            dragKeys = path.getKeys();
            dragged = new TreeSet<>(selected);
        }
        var base = dragKeys;
        var moved = new TreeSet<Integer>();
        for (var index : dragged) {
            if (isMovable(index)) {
                moved.add(index);
            }
        }
        if (moved.isEmpty()) {
            return;
        }
        var shift = shift(base, moved, seconds);
        var marked = new ArrayList<Marked>(base.size());
        for (var index = 0; index < base.size(); index++) {
            var key = base.get(index);
            marked.add(new Marked(
                    moved.contains(index) ? key.withSeconds(key.seconds() + shift) : key, dragged.contains(index)));
        }
        marked.sort(Comparator.comparingDouble(each -> each.key().seconds()));
        apply(document, path, marked, false);
    }

    public void settle() {
        if (steps == null) {
            return;
        }
        landKeys();
        dragItems = null;
        dragBars = null;
        dragShot = null;
        dragOrderKey = null;
        dragOrderStart = null;
        steps.settle();
    }

    public void editShot(int item, GameScenes.Camera shot) {
        editItems(SceneItems.withShot(getDocument().items(), item, shot));
    }

    public void editScreen(int item, Screen screen) {
        editItems(SceneItems.withScreen(getDocument().items(), item, screen));
    }

    public void editMusic(int item, Music music) {
        editItems(SceneItems.withMusic(getDocument().items(), item, music));
    }

    public void editOrder(int item, int order, Actor actor) {
        editItems(SceneItems.withOrder(getDocument().items(), item, order, actor));
    }

    public void moveKeyTo(double seconds) {
        var key = getChosenKey();
        var document = getDocument();
        if (key != null && document != null) {
            var base = dragItems != null ? dragItems : document.items();
            var from = dragItems != null
                    ? SceneItems.seconds(base, draggedItems.iterator().next())
                    : SceneItems.seconds(base, key);
            moveItems(document, seconds - from);
        }
    }

    public void editKey(int index, Key next) {
        var document = getDocument();
        var path = getPath();
        if (document == null
                || path == null
                || index < 0
                || index >= path.getKeys().size()) {
            return;
        }
        var clamped = next.withSeconds(Math.clamp(next.seconds(), 0, length()));
        var keys = new ArrayList<>(path.getKeys());
        keys.set(index, clamped);
        var updated = new CameraPath(keys, path.hasEvenSpeed());
        drag(document.withPath(updated));
        var kept = updated.getKeys().indexOf(clamped);
        if (kept >= 0 && !path.hasEvenSpeed()) {
            selected.clear();
            selected.add(kept);
        }
        if (onPath) {
            place();
        }
    }

    public void setEvenSpeed(boolean on) {
        var document = getDocument();
        var path = getPath();
        if (document != null && path != null) {
            change(document.withPath(new CameraPath(path.getKeys(), on)));
            if (onPath) {
                place();
            }
        }
    }

    public void rename(String name) {
        var document = getDocument();
        if (document != null) {
            change(document.withName(SceneFileStore.safeName(name)));
        }
    }

    public void setSeed(long seed) {
        var document = getDocument();
        if (document != null && seed != document.seed()) {
            settle();
            change(document.withSeed(seed));
        }
    }

    public void setHour(double hour) {
        var document = getDocument();
        if (document != null && Double.compare(hour, document.hour()) != 0) {
            settle();
            change(document.withHour(hour));
        }
    }

    public void setLength(double seconds) {
        var document = getDocument();
        if (document != null) {
            drag(document.withSeconds(Math.max(frame(), seconds)));
            playhead = Math.min(playhead, length());
        }
    }

    public boolean canUndo() {
        return steps != null && steps.canUndo();
    }

    public boolean canRedo() {
        return steps != null && steps.canRedo();
    }

    public void undo() {
        if (steps != null) {
            landKeys();
            steps.undo();
            afterStep();
        }
    }

    public void redo() {
        if (steps != null) {
            landKeys();
            steps.redo();
            afterStep();
        }
    }

    public void update(double seconds, boolean cameraFree) {
        if (steps == null) {
            return;
        }
        writeDraftWhenDue();
        if (!cameraFree) {
            playing = false;
            onPath = false;
            if (view != null) {
                view.hide();
            }
            return;
        }
        bakeWhenDue();
        if (onPath && !camera.snapshot().equals(placed)) {
            onPath = false;
            playing = false;
        }
        if (playing) {
            var length = length();
            playhead = Math.min(length, playhead + seconds);
            if (playhead >= length) {
                playing = false;
            }
            place();
        } else if (onPath) {
            place();
        }
        if (view != null && baked != null) {
            view.show(baked, playhead, scrubbing, playing, getNudge());
        }
    }

    public void release() {
        playing = false;
        onPath = false;
        scrubbing = false;
        if (view != null) {
            view.hide();
        }
        giveCameraBack();
    }

    public void flush() {
        if (draftAt >= 0) {
            draftAt = -1;
            writeDraft();
        }
    }

    public void closeBaking() {
        if (baker != null) {
            baker.close();
            baker = null;
        }
    }

    private @Nullable CameraPath flownPath() {
        return asClient ? null : getPath();
    }

    private void addCast(SceneDocument document, Actor caster, int skill) {
        settle();
        var moved = SceneItems.withNewOrder(document.items(), onFrame(playhead), caster, frame() / 2);
        var item = moved.keys().first().item();
        var items = SceneItems.withNewOrder(moved.items(), item, SceneOrders.skill(caster, skill, caster.index()));
        change(document.withItems(items));
        clearSelection();
        var orders = items.get(item).actors();
        for (var at = 0; at < orders.size(); at++) {
            if (orders.get(at).action() == Action.SKILL && orders.get(at).index() == caster.index()) {
                chosen.add(new SceneItems.Key(Part.ORDER, item, at));
            }
        }
    }

    private Actor shownOrder(SceneItems.Key key, Actor dragged) {
        var from = bakedFrom;
        if (from == null || !SceneItems.holds(from.items(), key)) {
            return dragged;
        }
        var shown = from.items().get(key.item()).actors().get(key.order());
        return shown.index() == dragged.index() && shown.action() == dragged.action() ? shown : dragged;
    }

    private @Nullable Nudge nudgeOf(Actor start, Actor next) {
        var run = baked;
        var action = start.action();
        var places = action.enters() || action == Action.MOVE || action == Action.AIRSHIP_MOVE;
        var place = run == null || !places || start.index() <= 0 ? null : run.place(start.index(), playhead);
        if (place == null) {
            return null;
        }
        var size = run.getSize(start.index());
        var reach = Math.max(STANDS_AT, size == null ? 0 : size.radius());
        if (Math.hypot(place.x() - start.x(), place.y() - start.y()) > reach) {
            return null;
        }
        var turn = action.enters() ? (next.yaw() - start.yaw()) * 2 * Math.PI / TURN : 0;
        return new Nudge(
                start.index(),
                (float) (next.x() - start.x()),
                (float) (next.y() - start.y()),
                (float) (next.z() - start.z()),
                (float) turn,
                place.x(),
                place.y());
    }

    private Speeds speeds() {
        var run = baked;
        return run == null ? Speeds.NONE : run::getSpeed;
    }

    private void added(SceneDocument document, Moved moved) {
        change(document.withItems(moved.items()));
        clearSelection();
        chosen.addAll(moved.keys());
    }

    private List<Item> withDeathWait(List<Item> items, Actor actor, String sequence, double moment) {
        var names = getSequences(actor.index());
        if (names == null) {
            return items;
        }
        var ends = onFrame(moment + getSequenceSeconds(actor.index(), sequence));
        return SceneOrders.withDeathWait(items, actor, sequence, moment, ends, names, length(), frame());
    }

    private Key getShotEnd(CameraPath path, Key before, double seconds) {
        var pose = path.getPose(seconds, places());
        var key = new Key(
                seconds,
                pose.x(),
                pose.y(),
                pose.z(),
                pose.yaw(),
                pose.pitch(),
                pose.roll(),
                pose.fov(),
                before.ease());
        return before.lookAt() > 0 ? key.withLookAt(before.lookAt()) : key;
    }

    private void begin(SceneDocument document, @Nullable SceneDocument savedValue, @Nullable Path path) {
        release();
        opened++;
        steps = new UndoSteps<>(document);
        placing = null;
        saved = savedValue;
        file = path;
        selected.clear();
        chosen.clear();
        chosenBars = -1;
        playhead = 0;
        dragKeys = null;
        dragItems = null;
        dragBars = null;
        dragOrderKey = null;
        dragOrderStart = null;
        nudge = null;
        draftAt = -1;
        free = false;
        asClient = false;
        shotTarget = 0;
        aimed = null;
        aimProblem = "";
        fit = null;
        forgetBake();
    }

    private void giveCameraBack() {
        if (flown) {
            flown = false;
            camera.setDirection(camera.getYawDegrees(), camera.getPitchDegrees());
            camera.setFov(freeFov);
        }
    }

    private void forgetBake() {
        if (baker != null) {
            baker.cancel();
        }
        asked = null;
        askedFrom = null;
        baked = null;
        runFrom = null;
        bakedFrom = null;
    }

    private void bakeWhenDue() {
        var current = baker;
        var document = getDocument();
        if (current == null || document == null) {
            return;
        }
        var latest = current.getLatest();
        if (latest != null && latest != baked && latest.getScene() == asked) {
            var first = baked == null;
            baked = latest;
            bakedFrom = runFrom;
            if (first) {
                place();
            }
        }
        var frames = Math.max(1, rate.getAsInt());
        var shots = asClient && document.path() != null;
        if (!document.hasContent() && !shots) {
            if (baked != null) {
                forgetBake();
                if (view != null) {
                    view.hide();
                }
            }
            return;
        }
        if (steps.isDragging() || document == askedFrom && frames == askedRate && shots == askedShots) {
            return;
        }
        askedShots = shots;
        var scene = shots ? ShotBake.bake(document).document().scene() : document.scene();
        var coming = baked != null && baked.getScene() == asked || current.isBusy();
        var sameRun = asked != null
                && coming
                && frames == askedRate
                && scene.items().equals(asked.items())
                && scene.paths().equals(asked.paths())
                && Objects.equals(scene.seed(), asked.seed())
                && Double.compare(scene.hour(), asked.hour()) == 0;
        askedFrom = document;
        if (!sameRun) {
            asked = scene;
            askedRate = frames;
            runFrom = document;
            current.start(scene, frames);
        }
    }

    private void editItems(List<Item> items) {
        drag(getDocument().withItems(items));
        if (onPath) {
            place();
        }
    }

    private SceneDocument moveItems(SceneDocument document, double seconds) {
        if (dragItems == null) {
            dragItems = document.items();
            draggedItems = Set.copyOf(chosen);
        }
        var base = dragItems;
        var shift = SceneItems.shift(base, draggedItems, seconds, length());
        var moved = SceneItems.move(base, draggedItems, shift, frame() / 2);
        chosen.clear();
        chosen.addAll(moved.keys());
        var next = document.withItems(moved.items());
        drag(next);
        return next;
    }

    private @Nullable SceneDocument savedValue(Draft draft) {
        var path = draft.file();
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return SceneFileStore.read(path);
        } catch (IOException refused) {
            return null;
        }
    }

    private Path target(SceneFileStore current) {
        var document = getDocument();
        var name = SceneFileStore.safeName(document.name());
        var folder = file == null
                ? current.getFolder()
                : file.toAbsolutePath().normalize().getParent();
        var wanted = folder.resolve(name + SceneFileStore.EXTENSION);
        if (wanted.equals(file == null ? null : file.toAbsolutePath().normalize())) {
            return wanted;
        }
        var free = SceneFileStore.freeName(folder, name);
        if (!free.equals(document.name())) {
            steps.change(document.withName(free));
        }
        return folder.resolve(free + SceneFileStore.EXTENSION);
    }

    private void change(SceneDocument next) {
        steps.change(next);
        draftLater();
    }

    private void drag(SceneDocument next) {
        steps.drag(next);
        if (baker != null) {
            baker.cancel();
            askedFrom = null;
        }
        draftLater();
    }

    private void draftLater() {
        draftAt = clock.getAsLong() + DRAFT_NANOS;
        note = "";
        aimProblem = "";
        written = null;
    }

    private void writeDraftWhenDue() {
        if (draftAt >= 0 && clock.getAsLong() >= draftAt) {
            draftAt = -1;
            writeDraft();
        }
    }

    private void writeDraft() {
        var current = store;
        if (current == null || steps == null) {
            return;
        }
        try {
            if (isUnsaved()) {
                current.writeDraft(getDocument(), file);
            } else {
                current.deleteDraft();
            }
        } catch (IOException problem) {
            log.warn("Could not keep the draft: {}", problem.toString());
        }
    }

    private void deleteDraft() {
        if (store != null) {
            try {
                store.deleteDraft();
            } catch (IOException problem) {
                log.warn("Could not take the draft away: {}", problem.toString());
            }
        }
    }

    private void afterStep() {
        draftLater();
        dragOrderKey = null;
        dragOrderStart = null;
        nudge = null;
        var count = getKeys().size();
        selected.removeIf(index -> index >= count);
        chosen.removeIf(key -> !SceneItems.holds(getDocument().items(), key));
        chosenBars = chosenBars < getBars().size() ? chosenBars : -1;
        playhead = Math.min(playhead, length());
        if (onPath) {
            place();
        }
    }

    private void place() {
        if (free) {
            onPath = false;
            return;
        }
        var path = flownPath();
        var shot = path == null && baked != null ? baked.getView(playhead) : null;
        if (path == null && shot == null) {
            onPath = false;
            return;
        }
        if (!flown) {
            flown = true;
            freeFov = camera.getFov();
        }
        if (camera.isInteractiveOrtho()) {
            camera.setView(CameraView.PERSPECTIVE);
        }
        if (path != null) {
            var pose = path.getPose(playhead, places());
            camera.setPosition((float) pose.x(), (float) pose.y(), (float) pose.z());
            camera.setDirection(pose.yaw(), pose.pitch(), pose.roll());
            camera.setFov((float) Math.clamp(pose.fov(), 1, 170));
        } else {
            camera.setPosition(shot.x(), shot.y(), shot.z());
            camera.setDirection(shot.yaw(), shot.pitch(), shot.roll());
            camera.setFov(Math.clamp(shot.fov(), 1, 170));
        }
        placed = camera.snapshot();
        onPath = true;
    }

    private Key getView(double seconds, Ease ease) {
        var path = flownPath();
        if (onPath && path != null) {
            var pose = path.getPose(playhead, places());
            return new Key(
                    seconds, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), pose.roll(), pose.fov(), ease);
        }
        var place = camera.getWorldFocusPosition();
        return new Key(
                seconds,
                rounded(place.getX(), PLACE_STEPS),
                rounded(place.getY(), PLACE_STEPS),
                rounded(place.getZ(), PLACE_STEPS),
                rounded(camera.getYawDegrees(), ANGLE_STEPS),
                rounded(camera.getPitchDegrees(), ANGLE_STEPS),
                rounded(camera.getRollDegrees(), ANGLE_STEPS),
                rounded(camera.getFov(), ANGLE_STEPS),
                ease);
    }

    private Key taken(Key key) {
        return getView(key.seconds(), key.ease()).withLink(key.link()).withLookAt(key.lookAt());
    }

    private Places places() {
        var run = baked;
        return (index, seconds) -> {
            var place = run == null ? null : run.place(index, seconds);
            return place == null ? null : new Vector(place.x(), place.y(), place.z());
        };
    }

    private int lastTarget() {
        var document = getDocument();
        var target = 0;
        if (document != null) {
            for (var span : ShotSpans.of(document.items(), length())) {
                if (span.start() <= playhead) {
                    target = span.target();
                }
            }
        }
        return target;
    }

    private boolean isAimChosen(SceneDocument document, SceneItems.Key key) {
        var item = document.items().get(key.item());
        var shot = item.camera();
        var target = shotTargetAt(item, item.seconds() + shot.moveSeconds());
        if (target == null) {
            aimProblem = baked == null
                    ? "The scene is still being read."
                    : "The target of this shot is not in the scene at its end.";
            return false;
        }
        var aim = ShotMath.aim(pose(), target, shot, !shotsHaveFov);
        if (aim == null) {
            aimProblem = "The camera stands on its target: fly away from it first.";
            return false;
        }
        finishAim(
                document.withItems(SceneItems.withShot(document.items(), key.item(), aim.shot())),
                key.item(),
                aim,
                false);
        return true;
    }

    private boolean isAimNew(SceneDocument document) {
        var chosenTarget = getShotTarget();
        var point = chosenTarget == POINT;
        if (chosenTarget == 0) {
            aimProblem = "Nothing stands in the scene here for a shot to look at.";
            return false;
        }
        var seconds = onFrame(playhead);
        var view = pose();
        var items = document.items();
        var index = point ? SceneItems.freeIndex(items, document.paths()) : chosenTarget;
        var at = point ? ShotMath.helper(view, HELPER_AHEAD) : null;
        var target =
                point ? new Target(at.x(), at.y(), at.z(), 0) : baked == null ? null : baked.getTarget(index, seconds);
        if (target == null) {
            aimProblem = "This actor is not in the scene at this moment.";
            return false;
        }
        var wide = SceneItems.isWideAt(items, seconds);
        var base = new GameScenes.Camera(
                GameScenes.Camera.SPECIAL_VIEW,
                index,
                0,
                0,
                0,
                0,
                holdUntilNext(document, seconds),
                0,
                0,
                0,
                wide,
                false,
                true,
                0);
        var aim = ShotMath.aim(view, target, base, !shotsHaveFov);
        if (aim == null) {
            aimProblem = "The camera stands on its target: fly away from it first.";
            return false;
        }
        var moved = SceneItems.withNewShot(items, seconds, aim.shot(), frame() / 2);
        var key = moved.keys().first();
        var next = moved.items();
        if (point) {
            next = SceneItems.withNewOrder(next, key.item(), helperOrder(index, at));
        }
        finishAim(document.withItems(next), key.item(), aim, drifts(document, aim.shot(), seconds));
        return true;
    }

    private void finishAim(SceneDocument next, int item, Aim aim, boolean drifts) {
        change(next);
        clearSelection();
        chosen.add(new SceneItems.Key(Part.SHOT, item, 0));
        aimed = new Aimed(item, aim.moved(), aim.turned(), drifts);
        aimedIn = getDocument();
    }

    private boolean drifts(SceneDocument before, GameScenes.Camera shot, double seconds) {
        if (baked == null || shot.relYaw() == 0 && shot.relPitch() == 0 && shot.relRoll() == 0) {
            return false;
        }
        for (var span : ShotSpans.of(before.items(), length())) {
            if (span.target() == shot.target() && span.start() <= seconds && span.holdEnd() > seconds) {
                return false;
            }
        }
        var from = baked.place(shot.target(), seconds);
        var to = baked.place(shot.target(), seconds + shot.holdSeconds());
        return from != null && to != null && (from.x() != to.x() || from.y() != to.y() || from.z() != to.z());
    }

    private double holdUntilNext(SceneDocument document, double seconds) {
        var until = length();
        for (var span : ShotSpans.of(document.items(), length())) {
            if (span.start() > seconds + frame() / 2) {
                until = span.start();
                break;
            }
        }
        return Math.max(frame(), Math.round((until - seconds) * MILLIS) / MILLIS);
    }

    private @Nullable Target shotTargetAt(Item item, double moment) {
        var run = baked;
        var index = item.camera().target();
        var place = run == null ? null : run.getTarget(index, moment);
        if (place == null) {
            return null;
        }
        var start = run.getTarget(index, item.seconds());
        return new Target(place.x(), place.y(), place.z(), start == null ? place.yaw() : start.yaw());
    }

    private Actor helperOrder(int index, Vector place) {
        return new Actor(
                false,
                Action.SPAWN,
                helper,
                index,
                0,
                place.x(),
                place.y(),
                place.z(),
                0,
                0,
                "",
                true,
                false,
                false,
                false,
                0,
                0,
                false,
                false,
                0,
                0,
                List.of());
    }

    private Pose pose() {
        var key = getView(playhead, Ease.SMOOTH);
        return new Pose(key.x(), key.y(), key.z(), key.yaw(), key.pitch(), key.roll(), key.fov());
    }

    private double shotEndMoment(SceneItems.Key key) {
        var document = getDocument();
        if (document == null) {
            return Double.NaN;
        }
        for (var span : ShotSpans.of(document.items(), length())) {
            if (span.item() == key.item()) {
                return Math.max(span.start(), span.holdEnd() - frame());
            }
        }
        return Double.NaN;
    }

    private void removeBars(SceneDocument document) {
        settle();
        var spans = getBars();
        if (chosenBars < spans.size()) {
            var span = spans.get(chosenBars);
            change(document.withItems(
                    SceneItems.withBars(document.items(), span.from(), span.to(), false, frame() / 2, length())));
        }
        chosenBars = -1;
    }

    private int nearKey(List<Key> keys, double seconds) {
        var reach = frame() / 2;
        var best = -1;
        var nearest = reach;
        for (var index = 0; index < keys.size(); index++) {
            var distance = Math.abs(keys.get(index).seconds() - seconds);
            var later = best >= 0 && distance == nearest && keys.get(index).seconds() <= seconds;
            if (!keys.get(index).rides() && (distance < nearest || distance == 0 || later)) {
                nearest = distance;
                best = index;
            }
        }
        return best;
    }

    private double shift(List<Key> base, Collection<Integer> moved, double seconds) {
        var earliest = Double.POSITIVE_INFINITY;
        var latest = 0.0;
        for (var index : moved) {
            earliest = Math.min(earliest, base.get(index).seconds());
            latest = Math.max(latest, base.get(index).seconds());
        }
        return Math.clamp(seconds, -earliest, Math.max(-earliest, length() - latest));
    }

    private void apply(SceneDocument document, CameraPath path, List<Marked> marked, boolean last) {
        var keys = new ArrayList<Key>(marked.size());
        selected.clear();
        for (var index = 0; index < marked.size(); index++) {
            keys.add(marked.get(index).key());
            if (marked.get(index).chosen()) {
                selected.add(index);
            }
        }
        var next = new CameraPath(keys, path.hasEvenSpeed());
        if (next.getKeys().size() == keys.size() && !next.getKeys().equals(keys)) {
            selected.clear();
            for (var chosen : marked) {
                var index = next.getKeys().indexOf(chosen.key());
                if (chosen.chosen() && index >= 0) {
                    selected.add(index);
                }
            }
        }
        if (!last) {
            drag(document.withPath(next));
            place();
        } else if (!keys.equals(path.getKeys())) {
            drag(document.withPath(next));
        }
    }

    private void landKeys() {
        var base = dragKeys;
        dragKeys = null;
        var document = getDocument();
        var path = getPath();
        if (base == null || document == null || path == null) {
            return;
        }
        var reach = frame() / 2;
        var keys = path.getKeys();
        var marked = new ArrayList<Marked>(keys.size());
        for (var index = 0; index < keys.size(); index++) {
            var key = keys.get(index);
            var chosen = selected.contains(index);
            var covered = false;
            if (!chosen && !key.rides()) {
                for (var other : selected) {
                    covered |= Math.abs(keys.get(other).seconds() - key.seconds()) < reach;
                }
            }
            if (!covered) {
                marked.add(new Marked(key, chosen));
            }
        }
        apply(document, path, marked, true);
    }

    private record Marked(Key key, boolean chosen) {}

    private static Actor at(Placing placing, Hit floor) {
        return SceneOrders.at(
                placing.order(),
                floor.x(),
                floor.y(),
                Double.isNaN(placing.height()) ? floor.z() + placing.lift() : placing.height());
    }

    private static double rounded(double value, double steps) {
        return Math.round(value * steps) / steps;
    }
}

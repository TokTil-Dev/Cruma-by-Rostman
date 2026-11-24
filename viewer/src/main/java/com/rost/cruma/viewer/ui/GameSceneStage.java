package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.npc.EnterEvents.Entrance;
import com.rost.cruma.core.npc.script.ScriptedClass;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.ClientFov;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.NpcAnimation;
import com.rost.cruma.core.world.NpcAnimation.Pose;
import com.rost.cruma.core.world.PostEffects;
import com.rost.cruma.core.world.SceneOrders.NpcSpeed;
import com.rost.cruma.core.world.SceneRig;
import com.rost.cruma.core.world.SystemMessages;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.SceneRequest;
import com.rost.cruma.viewer.geodata.ClientFloorGrid;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.render.GameScenePlayer;
import com.rost.cruma.viewer.render.GameScenePlayer.CastPlay;
import com.rost.cruma.viewer.render.GameScenePlayer.EntranceCue;
import com.rost.cruma.viewer.render.GameScenePlayer.Ground;
import com.rost.cruma.viewer.render.GameScenePlayer.Message;
import com.rost.cruma.viewer.render.GameScenePlayer.Rigs;
import com.rost.cruma.viewer.render.GameScenePlayer.SceneActor;
import com.rost.cruma.viewer.render.GameSlides;
import com.rost.cruma.viewer.render.SceneOverlay;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.SpawnOverlayState.LivePose;
import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.spawn.AirshipModel;
import com.rost.cruma.viewer.spawn.AirshipModel.Part;
import com.rost.cruma.viewer.spawn.SceneCharacter;
import com.rost.cruma.viewer.spawn.SpawnModels;
import com.rost.cruma.viewer.spawn.SpawnModels.Animated;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.SkillShow.Moment;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class GameSceneStage implements AutoCloseable {
    public static final float BAR_SHARE = .1f;

    static final int LINE_FONT = 28;
    static final float LINE_ROW = LINE_FONT * 1.2f;
    static final float LINE_SHADOW = 2;

    static final float LINE_ROOM = .9f;

    private static final int UNITS_PER_TURN = 65536;
    private static final float LINE_GAP = 10;
    private static final float ENTRANCE_RADIUS = 10;
    private static final float LINE_WINDOW7 = 241;
    private static final int LINE_ROWS = 3;

    private static final float RADIUS_SHARE = .4f;
    private static final Logger log = LogManager.getLogger(GameSceneStage.class);
    private static final Map<Path, Path> SCRIPTS = new ConcurrentHashMap<>();

    private final Path client;
    private final WorldVariant variant;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-scene-figures");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Integer, CompletableFuture<@Nullable Animated>> figures = new HashMap<>();
    private final Map<SceneCharacter, CompletableFuture<@Nullable Animated>> characters = new HashMap<>();
    private final Map<SceneActor, LivePose> poses = new IdentityHashMap<>();
    private final Map<Integer, Float> heights = new ConcurrentHashMap<>();
    private final Map<Integer, Float> radii = new ConcurrentHashMap<>();
    private final Map<Integer, ScriptedClass> scripts = new ConcurrentHashMap<>();
    private final Map<Integer, NpcSpeed> speeds = new ConcurrentHashMap<>();
    private final Map<Integer, SceneRig> rigs = new ConcurrentHashMap<>();
    private @Nullable CompletableFuture<@Nullable SceneRig> cameraRig;
    private final Map<SceneActor, Held> held = new IdentityHashMap<>();
    private final Map<Part, LivePose> airshipPoses = new IdentityHashMap<>();
    private final CompletableFuture<SystemMessages> messages;
    private final CompletableFuture<PostEffects> postEffects;
    private final SlidePictures pictures;
    private final LinePictures linePictures = new LinePictures();
    private @Nullable CompletableFuture<@Nullable AirshipModel> airship;
    private final SceneLibrary library;
    private final SceneCasts casts;
    private final SceneClassScripts classScripts;
    private final float playerFov;
    private final Rigs sceneRigs = new Rigs() {
        @Override
        public @Nullable SceneRig of(SceneActor actor) {
            return actor.getSpawn().player() ? null : rigs.get(actor.getSpawn().npc());
        }

        @Override
        public @Nullable SceneRig getCamera() {
            var read = cameraRig;
            return read == null ? null : read.getNow(null);
        }

        @Override
        public boolean takesBody(int skill) {
            var read = library.getSkill(skill);
            return read != null && !read.instant();
        }

        @Override
        public @Nullable Entrance getEntrance(int npc) {
            return library.getEntrance(npc);
        }

        @Override
        public @Nullable CastPlay getCasting(SceneActor actor) {
            var spawn = actor.getSpawn();
            var reading = spawn.player() ? characters.get(SceneCharacter.of(spawn)) : figures.get(spawn.npc());
            var figure = reading == null || reading.isCompletedExceptionally() ? null : reading.getNow(null);
            var moment = figure == null ? null : casts.findMoment(actor, figure.animation());
            return moment == null
                    ? null
                    : new CastPlay(figure.animation().getName(moment.sequence()), moment.seconds());
        }

        @Override
        public float getRadius(int npc) {
            return radii.getOrDefault(npc, 0f);
        }

        @Override
        public float getHeight(int npc) {
            return heights.getOrDefault(npc, 0f);
        }
    };
    private final SceneReading reading;
    private final @Nullable Watcher watcher;
    private final boolean draws;
    private @Nullable SpawnModels source;
    private @Nullable ClientFloors floors;
    private long revision = -1;

    public GameSceneStage(
            Path client, WorldVariant variant, TextureQuality quality, Supplier<@Nullable EffectPreview> effects) {
        this(client, variant, quality, effects, System::nanoTime, null);
    }

    GameSceneStage(
            Path client,
            WorldVariant variant,
            TextureQuality quality,
            Supplier<@Nullable EffectPreview> effects,
            LongSupplier clock,
            @Nullable Watcher watcher) {
        this.client = client;
        this.variant = variant;
        this.watcher = watcher;
        draws = watcher == null;
        reading = new SceneReading(clock);
        messages = CompletableFuture.supplyAsync(() -> SystemMessages.read(client, variant), worker);
        postEffects = CompletableFuture.supplyAsync(() -> PostEffects.read(client, variant), worker);
        pictures = new SlidePictures(client);
        library = new SceneLibrary(client, variant, quality);
        casts = new SceneCasts(library, effects);
        classScripts = new SceneClassScripts(library, effects);
        playerFov = ClientFov.read(client);
    }

    public static GameSceneStage forFilm(
            Path client, WorldVariant variant, TextureQuality quality, Supplier<@Nullable EffectPreview> effects) {
        return new GameSceneStage(client, variant, quality, effects, () -> 0L, null);
    }

    public static void useScripts(Path client, @Nullable Path scripts) {
        if (scripts == null) {
            SCRIPTS.remove(client);
        } else {
            SCRIPTS.put(client, scripts);
        }
    }

    public static Ground ground(ClientFloors floors) {
        return new Ground() {
            @Override
            public double getFeet(double x, double y, double z) {
                return floors.getSurfaceHeight(x, y, z);
            }

            @Override
            public double getFeet(double x, double y, double z, double radius, double height) {
                var floor = floors.findStandHeight(x, y, z, radius, height);
                return Double.isNaN(floor) ? floors.getSurfaceHeight(x, y, z) : floor;
            }
        };
    }

    static String text(Message message, @Nullable String line) {
        return message.id() < 1 || line == null ? message.note() : line.replace("\\n", "\n");
    }

    static List<String> rows(String text) {
        var rows = new ArrayList<String>();
        var row = new StringBuilder();
        for (var i = 0; i < text.length(); i++) {
            var letter = text.charAt(i);
            if (letter == '\n' || letter == '#' && rows.size() < LINE_ROWS - 1) {
                rows.add(row.toString());
                row.setLength(0);
            } else if (letter != '`' && letter != '#') {
                row.append(letter);
            }
        }
        if (!row.isEmpty()) {
            rows.add(row.toString());
        }
        return rows;
    }

    static List<String> wrap(List<String> rows, float room, Width width) {
        var wrapped = new ArrayList<String>(rows.size());
        for (var row : rows) {
            var taken = new StringBuilder();
            for (var word : row.split(" ")) {
                var longer = taken.isEmpty() ? word : taken + " " + word;
                if (!taken.isEmpty() && width.of(longer) > room) {
                    wrapped.add(taken.toString());
                    taken.setLength(0);
                    taken.append(word);
                } else {
                    taken.setLength(0);
                    taken.append(longer);
                }
            }
            wrapped.add(taken.toString());
        }
        return wrapped;
    }

    static boolean isWritable(String text) {
        return text.chars()
                .noneMatch(letter -> letter >= 0x1100 && letter <= 0x11FF
                        || letter >= 0x3130 && letter <= 0x318F
                        || letter >= 0xAC00 && letter <= 0xD7A3);
    }

    static float[] linePlace(int window, Viewport free, float width, float height, float textWidth, float textHeight) {
        var row = window >= 9 ? 3 : window / 3;
        var column = window >= 9 ? 1 : window % 3;
        var margin = height * BAR_SHARE;
        var x = switch (column) {
            case 0 -> free.left() + margin;
            case 2 -> free.right() - margin - textWidth;
            default -> middle(free, width, textWidth);
        };
        var y = switch (row) {
            case 0 -> margin * 1.5f;
            case 1 -> (height - textHeight) * .5f;
            case 2 -> height - LINE_WINDOW7 - textHeight;
            default -> Math.min(height - margin + LINE_GAP + LINE_ROW, free.bottom() + LINE_GAP) - textHeight;
        };
        return new float[] {x, y};
    }

    static int sequence(NpcAnimation animation, SceneActor actor) {
        if (actor.isMoving()) {
            var own = actor.getMoveSequence().isEmpty() ? -1 : animation.findSequence(actor.getMoveSequence());
            if (own >= 0) {
                return own;
            }
            var moving = actor.isRunning() ? moving(animation, "run", "walk") : moving(animation, "walk", "run");
            return moving < 0 ? waitOf(animation, actor) : moving;
        }
        var name = actor.getAnimation();
        if (name.isEmpty()) {
            return waitOf(animation, actor);
        }
        var played = animation.findSequence(name);
        return played < 0 ? waitOf(animation, actor) : played;
    }

    static int waitOf(NpcAnimation animation, SceneActor actor) {
        var fight = actor.fights() ? animation.getFightIdle() : -1;
        return fight < 0 ? animation.getIdle() : fight;
    }

    static String inStance(String waiting, String move) {
        var stance = waiting.indexOf('_');
        return stance < 0 ? move : move + waiting.substring(stance).replaceFirst("(?i)^_shield", "_1HS");
    }

    static float moment(float seconds, float length, boolean played, boolean loops, boolean moving, boolean waits) {
        if (!played && !moving && !waits) {
            return 0;
        }
        var looping = moving || !played || loops;
        return looping && length > 0 ? seconds % length : seconds;
    }

    static long seed(Scene scene) {
        return scene.seed() == null ? scene.name().hashCode() : scene.seed();
    }

    public void standOn(ClientFloors floors) {
        this.floors = floors;
    }

    public void prepare(GameScenePlayer player) {
        var scene = player.getScene();
        if (scene != null) {
            begin(player, scene);
            player.setPostEffects(postEffects.getNow(PostEffects.getNone()));
            player.setRigs(sceneRigs);
            player.setSpeeds(speeds::get);
            player.setPlayerFov(playerFov);
            player.setReading(reading.isWaiting());
        }
    }

    public void update(GameScenePlayer player, SpawnOverlayState stage, double seconds) {
        var scene = player.getScene();
        if (scene == null) {
            casts.stop();
            classScripts.stop();
            if (!stage.cast.isEmpty()) {
                stage.cast = List.of();
            }
            poses.clear();
            held.clear();
            return;
        }
        begin(player, scene);
        startCues(player);
        casts.left(player.getActors());
        classScripts.left(player.getActors());
        var cast = new ArrayList<ModelInstance>(player.getActors().size());
        for (var actor : player.getActors()) {
            var figure = figure(actor);
            if (figure != null) {
                cast.add(stand(actor, figure));
            } else if (classScripts.runs(actor)) {
                standUnseen(actor);
            }
            standAirship(actor, cast);
        }
        var death = player.takeDeath();
        while (death != null) {
            classScripts.died(death);
            death = player.takeDeath();
        }
        stage.cast = List.copyOf(cast);
        classScripts.update((float) seconds);
        casts.update((float) seconds);
    }

    public void drawScreen(GameScenePlayer player, ViewerUiRenderer ui, FontManager fonts, @Nullable Viewport free) {
        drawScreen(player.getScene(), player.isPlaying() ? player.getOverlay() : null, ui, fonts, free);
    }

    public void drawScreen(
            @Nullable Scene scene,
            @Nullable SceneOverlay overlay,
            ViewerUiRenderer ui,
            FontManager fonts,
            @Nullable Viewport free) {
        pictures.follow(scene, ui);
        linePictures.follow(scene, ui);
        if (overlay == null) {
            return;
        }
        var io = ImGui.getIO();
        var width = io.getDisplaySizeX();
        var height = io.getDisplaySizeY();
        var draw = ImGui.getBackgroundDrawList();
        var fade = overlay.fade();
        if (fade > 0) {
            var color = overlay.fadeColor();
            draw.addRectFilled(
                    0,
                    0,
                    width,
                    height,
                    ImColor.rgba(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, Math.round(fade * 255)));
        }
        if (overlay.wide()) {
            var black = ImColor.rgba(0, 0, 0, 255);
            draw.addRectFilled(0, 0, width, height * BAR_SHARE, black);
            draw.addRectFilled(0, height * (1 - BAR_SHARE), width, height, black);
        }
        drawSlides(overlay, draw, width, height);
        var font = fonts.getHeadingFont(LINE_FONT);
        var room = free == null ? new Viewport(0, 0, width, height) : free;
        for (var message : overlay.messages()) {
            drawLine(message, font, draw, room, width, height);
        }
    }

    public boolean arePicturesRead() {
        return pictures.isAllRead();
    }

    @Override
    public void close() {
        if (source != null && !worker.isShutdown()) {
            worker.execute(source::trimCaches);
        }
        worker.shutdown();
        pictures.close();
        linePictures.release();
        library.close();
        if (source != null) {
            source.close();
        }
    }

    float getRadius(SceneActor actor) {
        var radius = actor.getSpawn().player()
                ? 0f
                : radii.getOrDefault(actor.getSpawn().npc(), 0f);
        return radius > 0 ? radius : actor.getHalfHeight() * RADIUS_SHARE;
    }

    @Nullable
    SoundClip getSoundClip(String name) {
        return library.getSound(name);
    }

    Map<Integer, NpcSpeed> getSpeeds() {
        return Map.copyOf(speeds);
    }

    String getLine(Message message) {
        return text(message, messages.join().getLine(message.id()));
    }

    @Nullable
    AirshipModel getAirshipParts() {
        return airship == null ? null : airship.getNow(null);
    }

    SpawnModels getSource() {
        if (source == null) {
            source = new SpawnModels(client, variant);
            readScript(source);
        }
        return source;
    }

    private void begin(GameScenePlayer player, Scene scene) {
        if (revision == player.getRevision()) {
            return;
        }
        revision = player.getRevision();
        poses.clear();
        held.clear();
        casts.stop();
        classScripts.stop();
        casts.setChance(new SplittableRandom(seed(scene)));
        classScripts.setChance(new SplittableRandom(~seed(scene)));
        reading.start();
        reading.add(postEffects::isDone);
        reading.add(cameraRig()::isDone);
        readFloors(scene);
        var npcs = new HashSet<Integer>();
        var people = new HashSet<SceneCharacter>();
        for (var item : scene.items()) {
            for (var order : item.actors()) {
                if (order.action() == Action.AIRSHIP_SPAWN) {
                    airship();
                    reading.add(airship::isDone);
                } else if (order.player() && order.action().enters() && order.placed()) {
                    var character = SceneCharacter.of(order);
                    people.add(character);
                    reading.add(characters.computeIfAbsent(character, this::read)::isDone);
                } else if (order.action().enters() && !order.player() && order.npc() > 0) {
                    npcs.add(order.npc());
                    reading.add(figures.computeIfAbsent(order.npc(), this::read)::isDone);
                    var npc = order.npc();
                    reading.add(() -> areClassEffectsRead(npc));
                }
            }
        }
        figures.keySet().retainAll(npcs);
        rigs.keySet().retainAll(npcs);
        scripts.keySet().retainAll(npcs);
        characters.keySet().retainAll(people);
        for (var read : library.read(scene)) {
            reading.add(read::isDone);
        }
    }

    private void readFloors(Scene scene) {
        var ground = floors;
        if (ground == null
                || scene.items().stream().allMatch(item -> item.actors().isEmpty())) {
            return;
        }
        var missing = SceneRequest.close(scene, client, variant).tiles().stream()
                .filter(map -> !ground.holds(map))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        reading.add(CompletableFuture.runAsync(() -> readFloors(ground, missing), worker)::isDone);
    }

    private void readFloors(ClientFloors ground, List<String> maps) {
        var loader = new UnrealLoader(client, new Options(false, 0), true);
        loader.worldVariant(variant);
        try {
            for (var name : maps) {
                try {
                    var map = loader.loadMap(name);
                    var grid = map == null ? null : ClientFloorGrid.build(map, worker::isShutdown);
                    if (grid != null) {
                        ground.put(grid);
                    }
                } catch (RuntimeException failure) {
                    log.warn("The floors of {} could not be read for a cut scene: {}", name, failure.toString());
                }
            }
        } finally {
            loader.trimCaches();
        }
    }

    private void startCues(GameScenePlayer player) {
        var skill = player.takeSkill();
        while (skill != null) {
            casts.cast(skill, figure(skill.caster()), figure(skill.target()));
            skill = player.takeSkill();
        }
        var effect = player.takeEffect();
        while (effect != null) {
            casts.effect(effect);
            var quake = library.getQuake(effect.effect());
            if (quake != null) {
                player.shake(quake, effect);
            }
            effect = player.takeEffect();
        }
        var arrival = player.takeArrival();
        while (arrival != null) {
            figure(arrival);
            var npc = arrival.getSpawn().npc();
            var type = scripts.get(npc);
            if (type != null) {
                classScripts.arrive(
                        arrival, type, rigs.get(npc), heights.getOrDefault(npc, 0f), radii.getOrDefault(npc, 0f));
            }
            arrival = player.takeArrival();
        }
        var entrance = player.takeEntrance();
        while (entrance != null) {
            enter(entrance);
            entrance = player.takeEntrance();
        }
        var sound = player.takeSound();
        while (sound != null) {
            casts.sound(sound);
            sound = player.takeSound();
        }
    }

    private boolean areClassEffectsRead(int npc) {
        var figure = figures.get(npc);
        if (figure == null || !figure.isDone()) {
            return false;
        }
        var type = scripts.get(npc);
        var read = true;
        for (var effect : type == null ? Set.<String>of() : type.getEffects()) {
            read &= library.prefetchEffect(effect).isDone();
        }
        return read;
    }

    private void enter(EntranceCue cue) {
        var actor = cue.actor();
        var entrance = cue.entrance();
        if (!entrance.effect().isEmpty()) {
            var radius = radii.getOrDefault(actor.getSpawn().npc(), 0f);
            casts.entranceEffect(
                    entrance.effect(), actor, actor.getStandingFeet(), radius > 0 ? radius / ENTRANCE_RADIUS : 1f);
        }
        if (!entrance.sound().isEmpty()) {
            casts.sound(
                    entrance.sound(), new Vec3(actor.x(), actor.y(), actor.z()), entrance.volume(), entrance.radius());
        }
    }

    private void drawSlides(SceneOverlay overlay, ImDrawList draw, float width, float height) {
        if (!overlay.slidesShow()) {
            return;
        }
        draw.addRectFilled(0, 0, width, height, ImColor.rgba(0, 0, 0, 255));
        var box = GameSlides.box(width, height);
        draw.pushClipRect(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), true);
        for (var slide : overlay.slides()) {
            var alpha = (int) Math.round(slide.alpha() * 255);
            if (alpha <= 0) {
                continue;
            }
            var left = box.x() + (float) slide.x() * box.width();
            var top = box.y() + (float) slide.y() * box.height();
            var right = left + (float) slide.width() * box.width();
            var bottom = top + (float) slide.height() * box.height();
            var picture = pictures.getPicture(slide.picture());
            if (picture == null) {
                draw.addRectFilled(left, top, right, bottom, ImColor.rgba(0, 0, 0, alpha));
            } else {
                draw.addImage(
                        picture.texture(),
                        left,
                        top,
                        right,
                        bottom,
                        0,
                        0,
                        slide.crop().u(picture.width()),
                        slide.crop().v(picture.height()),
                        ImColor.rgba(255, 255, 255, alpha));
            }
        }
        draw.popClipRect();
    }

    private void drawLine(Message message, ImFont font, ImDrawList draw, Viewport free, float width, float height) {
        var table = messages.getNow(null);
        var text = text(message, table == null ? null : table.getLine(message.id()));
        if (text.isEmpty() || message.alpha() <= 0) {
            return;
        }
        var drawn = isWritable(text);
        var density = ImGui.getIO().getDisplayFramebufferScaleX();
        var room =
                Math.min(GameSlides.box(width, height).width() * LINE_ROOM, free.right() - free.left() - LINE_GAP * 2);
        var rows = wrap(
                rows(text),
                room,
                row -> drawn ? font.calcTextSizeAX(LINE_FONT, Float.MAX_VALUE, 0, row) : pictureWidth(row, density));
        var blockHeight = LINE_ROW * rows.size();
        var shadow = ImColor.rgba(0, 0, 0, message.alpha() * 220 / 255);
        var color = ImColor.rgba(255, 255, 255, message.alpha());
        for (var i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (!drawn) {
                var picture = linePictures.getRow(row, LINE_FONT, density);
                if (picture != null) {
                    var spot = linePlace(message.window(), free, width, height, picture.width(), blockHeight);
                    var y = spot[1] + LINE_ROW * i;
                    var x = spot[0];
                    draw.addImage(
                            picture.texture(),
                            x + LINE_SHADOW,
                            y + LINE_SHADOW,
                            x + LINE_SHADOW + picture.width(),
                            y + LINE_SHADOW + picture.height(),
                            0,
                            0,
                            1,
                            1,
                            shadow);
                    draw.addImage(
                            picture.texture(), x, y, x + picture.width(), y + picture.height(), 0, 0, 1, 1, color);
                }
                continue;
            }
            var rowWidth = font.calcTextSizeAX(LINE_FONT, Float.MAX_VALUE, 0, row);
            var place = linePlace(message.window(), free, width, height, rowWidth, blockHeight);
            var top = place[1] + LINE_ROW * i;
            draw.addText(font, LINE_FONT, place[0] + LINE_SHADOW, top + LINE_SHADOW, shadow, row);
            draw.addText(font, LINE_FONT, place[0] - LINE_SHADOW * .5f, top + LINE_SHADOW * .5f, shadow, row);
            draw.addText(font, LINE_FONT, place[0], top, color, row);
        }
    }

    private float pictureWidth(String row, float density) {
        var picture = linePictures.getRow(row, LINE_FONT, density);
        return picture == null ? 0 : picture.width();
    }

    private ModelInstance stand(SceneActor actor, Animated figure) {
        var animation = figure.animation();
        var waits = waits(animation);
        var casting = held(actor, animation, actor.isMoving() ? null : casts.findMoment(actor, animation));
        var sequence = sequence(animation, actor);
        if (casting == null && waits && actor.isCasting() && !actor.isMoving() && held.containsKey(actor)) {
            sequence = waitOf(animation, actor);
        }
        var length = animation.getSeconds(sequence);
        var seconds = (float) actor.getAnimationSeconds();
        var played = !actor.getAnimation().isEmpty() && (sequence != waitOf(animation, actor) || !waits);
        var moment = moment(seconds, length, played, actor.loops(), actor.isMoving(), waits);
        var pose = poses.computeIfAbsent(actor, _ -> new LivePose(figure.model().vertices()));
        var shown = casting == null ? sequence : casting.sequence();
        var at = casting == null ? moment : casting.seconds();
        if (classScripts.runs(actor)) {
            classScripts.playing(
                    actor,
                    animation.getName(shown),
                    at,
                    animation.getSeconds(shown),
                    casting == null && played && !actor.loops() && !actor.isMoving());
        }
        if (watcher != null) {
            watcher.stands(actor, figure, shown, at);
        }
        if (draws) {
            var body = animation.getPose(shown, at);
            casts.posed(actor, body);
            pose.set(figure.vertices(body));
        } else {
            casts.posed(actor, later(animation, shown, at));
        }
        var yaw = (float) (actor.getYaw() * Math.PI * 2 / UNITS_PER_TURN);
        return new ModelInstance(
                figure.model(), actor.x(), actor.y(), actor.z() - figure.model().lowest(), yaw, 1f, pose);
    }

    private void standUnseen(SceneActor actor) {
        var rig = rigs.get(actor.getSpawn().npc());
        if (rig == null || rig.getSequenceCount() == 0) {
            return;
        }
        var fight = actor.fights() ? rig.getFightIdle() : -1;
        var idle = fight < 0 ? rig.getIdle() : fight;
        var sequence = idle;
        if (actor.isMoving()) {
            var own = actor.getMoveSequence().isEmpty() ? -1 : rig.findSequence(actor.getMoveSequence());
            var moving = actor.isRunning() ? rig.find("run", "walk") : rig.find("walk", "run");
            sequence = own >= 0 ? own : moving >= 0 ? moving : idle;
        } else if (!actor.getAnimation().isEmpty() && rig.findSequence(actor.getAnimation()) >= 0) {
            sequence = rig.findSequence(actor.getAnimation());
        }
        var waits = rig.waits();
        var played = !actor.getAnimation().isEmpty() && (sequence != idle || !waits);
        var length = rig.getSeconds(sequence);
        var at = moment((float) actor.getAnimationSeconds(), length, played, actor.loops(), actor.isMoving(), waits);
        classScripts.playing(actor, rig.getName(sequence), at, length, played && !actor.loops() && !actor.isMoving());
    }

    private @Nullable Moment held(SceneActor actor, NpcAnimation animation, @Nullable Moment casting) {
        if (casting != null) {
            held.put(actor, new Held(casting, actor.getAnimationSeconds()));
            return casting;
        }
        if (actor.isMoving()) {
            held.remove(actor);
            return null;
        }
        var last = held.get(actor);
        return last != null && actor.getAnimationSeconds() >= last.orderSeconds() && !waits(animation)
                ? last.body()
                : null;
    }

    private void standAirship(SceneActor actor, List<ModelInstance> cast) {
        var ship = actor.isAirship() ? airship() : null;
        if (ship == null) {
            return;
        }
        var yaw = (float) (actor.getYaw() * Math.PI * 2 / UNITS_PER_TURN);
        var cos = (float) Math.cos(yaw);
        var sin = (float) Math.sin(yaw);
        for (var part : ship.parts()) {
            var x = actor.x() + part.x() * cos - part.y() * sin;
            var y = actor.y() + part.x() * sin + part.y() * cos;
            var z = actor.z() + part.z() - part.model().lowest();
            cast.add(new ModelInstance(part.model(), x, y, z, yaw, 1f, pose(part, actor)));
        }
    }

    private @Nullable LivePose pose(Part part, SceneActor actor) {
        var animated = part.animated();
        if (animated == null) {
            return null;
        }
        var animation = animated.animation();
        var idle = animation.getIdle();
        var length = animation.getSeconds(idle);
        var moment = length > 0 ? (float) (actor.getAnimationSeconds() % length) : 0;
        var pose = airshipPoses.computeIfAbsent(
                part, _ -> new LivePose(part.model().vertices()));
        if (draws) {
            pose.set(animated.vertices(animation.getPose(idle, moment)));
        }
        return pose;
    }

    private @Nullable AirshipModel airship() {
        if (airship == null) {
            var reader = getSource();
            airship = CompletableFuture.<@Nullable AirshipModel>supplyAsync(reader::readAirship, worker)
                    .exceptionally(failure -> {
                        log.warn("The airship of a cut scene could not be read", failure);
                        return null;
                    });
        }
        return airship.getNow(null);
    }

    private @Nullable Animated figure(SceneActor actor) {
        var spawn = actor.getSpawn();
        if (!spawn.player() && spawn.npc() <= 0 || actor.isAirship()) {
            return null;
        }
        var reading = spawn.player()
                ? characters.computeIfAbsent(SceneCharacter.of(spawn), this::read)
                : figures.computeIfAbsent(spawn.npc(), this::read);
        if (!reading.isDone() || reading.isCompletedExceptionally()) {
            return null;
        }
        var figure = reading.getNow(null);
        if (!poses.containsKey(actor)) {
            var height = spawn.player() ? 0f : heights.getOrDefault(spawn.npc(), 0f);
            if (height > 0) {
                actor.setHalfHeight(height);
            } else if (figure != null) {
                actor.setHalfHeight(Math.max(0f, figure.model().lowest()));
            }
        }
        return figure;
    }

    private CompletableFuture<@Nullable Animated> read(int npc) {
        var reader = getSource();
        return CompletableFuture.<@Nullable Animated>supplyAsync(
                        () -> {
                            speeds.put(npc, reader.getSpeed(npc));
                            var figure = reader.readScenePawn(npc, reader.getDress(npc));
                            heights.put(npc, reader.getCollisionHeight(npc));
                            radii.put(npc, reader.getCollisionRadius(npc));
                            var type = reader.getClassScript(npc);
                            if (type != null) {
                                scripts.put(npc, type);
                            }
                            var rig = reader.readSceneRig(npc);
                            if (rig != null) {
                                rigs.put(npc, rig);
                            }
                            return figure;
                        },
                        worker)
                .exceptionally(failure -> {
                    log.warn("NPC {} of a cut scene could not be read", npc, failure);
                    return null;
                });
    }

    private CompletableFuture<@Nullable SceneRig> cameraRig() {
        var read = cameraRig;
        if (read == null) {
            var reader = getSource();
            read = CompletableFuture.<@Nullable SceneRig>supplyAsync(reader::readCameraRig, worker)
                    .exceptionally(failure -> {
                        log.warn("The camera pawn of the cut scenes could not be read", failure);
                        return null;
                    });
            cameraRig = read;
        }
        return read;
    }

    private CompletableFuture<@Nullable Animated> read(SceneCharacter character) {
        var reader = getSource();
        return CompletableFuture.<@Nullable Animated>supplyAsync(
                        () -> reader.buildAnimatedCharacter(character.face(), character.outfit(reader.getTables())),
                        worker)
                .exceptionally(failure -> {
                    log.warn("Player character {} of a cut scene could not be read", character.face(), failure);
                    return null;
                });
    }

    private void readScript(SpawnModels reader) {
        var scripts = SCRIPTS.get(client);
        if (scripts != null) {
            worker.execute(() -> {
                try {
                    reader.readScript(scripts);
                } catch (IOException | RuntimeException failure) {
                    log.warn(
                            "The script folder {} could not be read for the cut scenes: {}",
                            scripts,
                            failure.toString());
                }
            });
        }
    }

    interface Width {
        float of(String row);
    }

    interface Watcher {
        void stands(SceneActor actor, Animated figure, int sequence, float seconds);
    }

    private record Held(Moment body, double orderSeconds) {}

    private static float middle(Viewport free, float width, float textWidth) {
        var left = free.left() + LINE_GAP;
        var right = free.right() - LINE_GAP - textWidth;
        if (right < left) {
            return (free.left() + free.right() - textWidth) * .5f;
        }
        return Math.clamp((width - textWidth) * .5f, left, right);
    }

    private static int moving(NpcAnimation animation, String first, String second) {
        var same = animation.findSequence(inStance(animation.getName(animation.getIdle()), first));
        return same >= 0 ? same : animation.find(first, second);
    }

    private static Supplier<Pose> later(NpcAnimation animation, int sequence, float seconds) {
        return new Supplier<>() {
            private @Nullable Pose pose;

            @Override
            public Pose get() {
                if (pose == null) {
                    pose = animation.getPose(sequence, seconds);
                }
                return pose;
            }
        };
    }

    private static boolean waits(NpcAnimation animation) {
        return animation.waits();
    }
}

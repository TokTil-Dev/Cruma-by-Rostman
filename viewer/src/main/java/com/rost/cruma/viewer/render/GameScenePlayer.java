package com.rost.cruma.viewer.render;

import com.rost.cruma.core.npc.EnterEvents;
import com.rost.cruma.core.npc.EnterEvents.Entrance;
import com.rost.cruma.core.unreal.EffectClasses.Quake;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.MapScenes.Vector;
import com.rost.cruma.core.world.PostEffects;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneOrders.Speeds;
import com.rost.cruma.core.world.SceneRig;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.render.SceneCamera.Target;
import com.rost.cruma.viewer.render.SceneDoors.Moved;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class GameScenePlayer implements SceneAudio {
    private static final double AIRSHIP_TURN = 2000;
    private static final float DEFAULT_HALF_HEIGHT = 24;
    private static final double FLOOR_CLEARANCE = ClientFloors.PAWN_CLEARANCE;
    private static final int TURN = 0x10000;
    private static final double FRAME_DISTANCE = 500;
    private static final double FRAME_PITCH = Math.toRadians(15);
    private static final double FRAME_CLEARANCE = 60;

    private final Camera camera;
    private final SceneCamera view = new SceneCamera();
    private final SceneBoneCamera bones = new SceneBoneCamera();
    private Rigs rigs = Rigs.NONE;
    private Speeds speeds = Speeds.NONE;
    private final Map<Integer, SceneActor> actors = new LinkedHashMap<>();
    private final ArrayDeque<Music> music = new ArrayDeque<>();
    private final ArrayDeque<SkillCue> skills = new ArrayDeque<>();
    private final SceneDoors doors = new SceneDoors();
    private final GameSlides slides = new GameSlides();
    private final ScenePostEffects postEffects = new ScenePostEffects();
    private final SceneShake shake = new SceneShake();
    private PostEffects postTable = PostEffects.getNone();
    private final GameLines lines = new GameLines();
    private final ArrayDeque<EffectCue> effects = new ArrayDeque<>();
    private final ArrayDeque<EntranceCue> entrances = new ArrayDeque<>();
    private final ArrayDeque<SceneActor> arrivals = new ArrayDeque<>();
    private final ArrayDeque<SceneActor> deaths = new ArrayDeque<>();
    private final ArrayDeque<Music> sounds = new ArrayDeque<>();
    private @Nullable Scene scene;
    private double[] times = {};
    private Ground ground = (x, y, z) -> Double.NaN;
    private int next;
    private double seconds;
    private boolean loop;
    private boolean ranOut;
    private boolean viewed;
    private boolean framed;
    private boolean wide;
    private boolean reading;
    private boolean stopsAmbient;
    private boolean stopsEffects;
    private float freeFov;
    private float playerFov;
    private double lastYaw;
    private double lastPitch;
    private int fadeColor;
    private double fadeOut;
    private double fadeHold;
    private double fadeIn;
    private double fadeAt;
    private boolean fading;
    private boolean fadeWaits;
    private long revision;

    public GameScenePlayer(Camera camera) {
        this.camera = camera;
    }

    public void play(Scene scene, Ground ground) {
        if (this.scene == null) {
            freeFov = camera.getFov();
        } else {
            giveCameraBack();
        }
        if (scene.movesCamera() && camera.isInteractiveOrtho()) {
            camera.setView(CameraView.PERSPECTIVE);
        }
        ranOut = false;
        this.scene = scene;
        this.ground = ground;
        times = scene.times();
        restart();
        frame(scene);
    }

    public void stop() {
        if (scene == null) {
            return;
        }
        giveCameraBack();
        scene = null;
        clear();
    }

    public void update(double delta) {
        var playing = scene;
        if (playing == null || delta <= 0 || reading) {
            return;
        }
        var items = playing.items();
        if (next >= items.size()) {
            if (loop) {
                giveCameraBack();
                restart();
            } else {
                ranOut = true;
                stop();
            }
            return;
        }
        seconds += delta;
        while (next < items.size() && items.get(next).seconds() <= seconds) {
            run(items.get(next), times[next]);
            next++;
        }
        var cameraRig = rigs.getCamera();
        for (var actor : actors.values()) {
            actor.advance(delta);
        }
        for (var actor : actors.values()) {
            var watched = actor.getWatches() == 0 ? null : actors.get(actor.getWatches());
            if (watched != null) {
                actor.watch(watched);
            }
        }
        for (var actor : actors.values()) {
            var rig = rigs.of(actor);
            bones.played(
                    actor,
                    rig,
                    cameraRig,
                    actor.getBefore(),
                    actor.getAnimationSeconds(),
                    actor.isCasting() ? rigs.getCasting(actor) : null);
            endOnce(actor, rig);
        }
        bones.advance(delta, cameraRig);
        var around = bones.getAround();
        if (around == null) {
            view.aroundTarget();
        } else {
            view.aroundBone(around.body(), around);
        }
        doors.advance(delta);
        fadeWaits &= !bones.hasRun();
        advanceFade(delta);
        postEffects.advance(delta);
        shake.advance(delta);
        slides.advance(seconds);
        lines.advance(seconds);
        view.update((float) delta);
        applyView();
    }

    @Override
    public boolean isPlaying() {
        return scene != null;
    }

    @Override
    public boolean hasRunOut() {
        return ranOut;
    }

    public void setRigs(Rigs rigs) {
        this.rigs = rigs;
    }

    public void setSpeeds(Speeds speeds) {
        this.speeds = speeds;
    }

    public void setPlayerFov(float degrees) {
        playerFov = degrees;
    }

    public boolean holdsCamera() {
        return scene != null && viewed;
    }

    public boolean markHolds() {
        return bones.holds();
    }

    public @Nullable Scene getScene() {
        return scene;
    }

    public double getSeconds() {
        return seconds;
    }

    public boolean loops() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public Collection<SceneActor> getActors() {
        return actors.values();
    }

    @Override
    public long getRevision() {
        return revision;
    }

    @Override
    public @Nullable Music takeMusic() {
        return music.poll();
    }

    public @Nullable Music takeSound() {
        return sounds.poll();
    }

    public @Nullable Moved takeDoors() {
        return doors.take();
    }

    public @Nullable SkillCue takeSkill() {
        return skills.poll();
    }

    public @Nullable EffectCue takeEffect() {
        return effects.poll();
    }

    public @Nullable EntranceCue takeEntrance() {
        return entrances.poll();
    }

    public @Nullable SceneActor takeArrival() {
        return arrivals.poll();
    }

    public @Nullable SceneActor takeDeath() {
        return deaths.poll();
    }

    @Override
    public boolean stopsAmbient() {
        return stopsAmbient;
    }

    @Override
    public boolean stopsEffects() {
        return stopsEffects;
    }

    public void setReading(boolean reading) {
        this.reading = reading;
    }

    public boolean isReading() {
        return reading;
    }

    public boolean isWide() {
        return wide;
    }

    public float getFade() {
        if (!fading) {
            return 0;
        }
        if (fadeAt < fadeOut) {
            return (float) (fadeAt / fadeOut);
        }
        var after = fadeAt - fadeOut - fadeHold;
        if (after <= 0 || fadeWaits) {
            return 1;
        }
        return fadeIn <= 0 ? 0 : (float) Math.max(0, 1 - after / fadeIn);
    }

    public void shake(Quake quake, EffectCue cue) {
        var on = cue.on();
        var dx = (on == null ? cue.x() : on.x()) - view.getX();
        var dy = (on == null ? cue.y() : on.y()) - view.getY();
        var dz = (on == null ? cue.z() : on.z()) - view.getZ();
        shake.start(quake, Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    public boolean isFramed() {
        return framed;
    }

    public SceneOverlay getOverlay() {
        return new SceneOverlay(getFade(), fadeColor, wide, slides.shows(), slides.getShown(), getMessages());
    }

    public ScenePostEffects getPostEffects() {
        return postEffects;
    }

    public void setPostEffects(PostEffects table) {
        postTable = table;
    }

    public int getFadeColor() {
        return fadeColor;
    }

    public GameSlides getSlides() {
        return slides;
    }

    public List<Message> getMessages() {
        return lines.getShown();
    }

    private void restart() {
        clear();
        revision++;
        next = 0;
        seconds = 0;
        viewed = false;
        view.reset();
    }

    private void clear() {
        actors.clear();
        doors.clear();
        music.clear();
        skills.clear();
        effects.clear();
        entrances.clear();
        arrivals.clear();
        deaths.clear();
        sounds.clear();
        stopsAmbient = false;
        stopsEffects = false;
        reading = false;
        wide = false;
        fading = false;
        fadeWaits = false;
        fadeColor = 0;
        slides.clear();
        lines.clear();
        postEffects.clear();
        shake.stop();
        bones.reset();
    }

    private void giveCameraBack() {
        if (viewed) {
            camera.setDirection(lastYaw, lastPitch);
            camera.setFov(freeFov);
        }
    }

    private void run(Item item, double at) {
        for (var order : item.actors()) {
            runActor(order);
        }
        var shot = item.camera();
        wide = shot != null && shot.wide();
        if (shot != null && shot.specialView()) {
            runCamera(shot);
        }
        var screen = item.screen();
        if (screen != null) {
            runScreen(screen, at);
        }
        var cue = item.music();
        if (cue != null) {
            music.add(cue);
            if (cue.sound() && !cue.name().isEmpty()) {
                sounds.add(cue);
            }
            stopsAmbient |= cue.stopAmbient();
            stopsEffects |= cue.stopEffects();
        }
    }

    private void runActor(Actor order) {
        var actor = actors.get(order.index());
        switch (order.action()) {
            case SPAWN, AIRSHIP_SPAWN, SELF -> enter(order);
            case DESPAWN, TELEPORT_BACK -> {
                actors.remove(order.index());
                if (actor != null) {
                    bones.left(actor);
                }
            }
            case TELEPORT -> {
                if (actor == null) {
                    enter(order);
                } else {
                    actor.teleport(order, ground);
                }
            }
            case MOVE -> {
                if (actor != null) {
                    var target = order.player() ? actors.get(order.target()) : null;
                    actor.moveTo(order, target, ground, speeds);
                }
            }
            case AIRSHIP_MOVE -> {
                if (actor != null) {
                    actor.flyTo(order);
                }
            }
            case ANIMATION -> {
                if (actor != null) {
                    actor.animate(
                            order.animation(), order.loop(), order.since(), order.speed() > 0 ? order.speed() : 1);
                    if (order.dies()) {
                        actor.die();
                        deaths.add(actor);
                    }
                }
            }
            case DOOR -> doors.order(order);
            case SKILL -> {
                var target = actors.get(order.target());
                if (actor != null && target != null) {
                    skills.add(new SkillCue(actor, target, order.skill()));
                    if (rigs.takesBody(order.skill())) {
                        actor.cast();
                    }
                }
            }
            case EFFECT -> {
                if (!order.animation().isEmpty()) {
                    effects.add(new EffectCue(order.animation(), actor, order.x(), order.y(), order.z(), order.loop()));
                }
            }
            default -> {
            }
        }
    }

    private void enter(Actor order) {
        if (order.stands()) {
            var entered = new SceneActor(order);
            if (!order.player() && order.npc() > 0) {
                entered.cylinder(rigs.getRadius(order.npc()), rigs.getHeight(order.npc()));
            }
            entered.stand(ground);
            actors.put(order.index(), entered);
            if (!order.player() && order.npc() > 0) {
                arrivals.add(entered);
            }
            var entrance = order.action() == Action.SPAWN && !order.player() && !order.present()
                    ? rigs.getEntrance(order.npc())
                    : null;
            if (entrance != null) {
                entered.enter(entrance, order.since());
                if (order.since() <= 0) {
                    entrances.add(new EntranceCue(entered, entrance));
                }
            }
        }
    }

    private void frame(Scene scene) {
        var first = scene.movesCamera() ? null : firstActor(scene);
        framed = first != null;
        if (first == null) {
            return;
        }
        if (camera.isInteractiveOrtho()) {
            camera.setView(CameraView.PERSPECTIVE);
        }
        var actor = new SceneActor(first);
        actor.stand(ground);
        var facing = actor.getYaw() * 2 * Math.PI / TURN;
        var across = FRAME_DISTANCE * Math.cos(FRAME_PITCH);
        var x = actor.x() + Math.cos(facing) * across;
        var y = actor.y() + Math.sin(facing) * across;
        var z = actor.z() + FRAME_DISTANCE * Math.sin(FRAME_PITCH);
        var floor = ground.getFeet(x, y, z);
        if (!Double.isNaN(floor)) {
            z = Math.max(z, floor + FRAME_CLEARANCE);
        }
        camera.setPosition((float) x, (float) y, (float) z);
        camera.setDirection(
                Math.toDegrees(Math.atan2(actor.y() - y, actor.x() - x)),
                Math.toDegrees(Math.atan2(actor.z() - z, across)));
    }

    private void runCamera(GameScenes.Camera shot) {
        var target = actors.get(shot.target());
        if (target == null || shot.blank()) {
            return;
        }
        bones.shot(target);
        var immediate = shot.immediate() || !viewed;
        view.set(
                target,
                (float) shot.distance(),
                units(-shot.yaw()),
                units(-shot.pitch()),
                (float) shot.moveSeconds(),
                (float) shot.holdSeconds(),
                units(shot.relYaw()),
                units(shot.relPitch()),
                units(shot.relRoll()),
                shot.relative(),
                immediate,
                (float) shot.fov());
        viewed = true;
    }

    private void runScreen(Screen screen, double at) {
        switch (screen.kind()) {
            case FADE -> {
                if (!fading) {
                    fading = true;
                    fadeAt = 0;
                    fadeOut = screen.fadeOut();
                    fadeHold = screen.hold();
                    fadeIn = screen.fadeIn();
                    fadeColor = screen.color();
                }
                fadeWaits |= screen.waits();
            }
            case MESSAGE -> lines.show(screen, at);
            case SLIDE_START, SLIDE_CHANGE, SLIDE_MOVE, SLIDE_END -> slides.run(screen, at);
            case POST_EFFECT -> {
                var effect = postTable.getEffect(screen.postEffect());
                if (effect != null) {
                    postEffects.play(effect);
                }
            }
            default -> {
            }
        }
    }

    private void advanceFade(double delta) {
        if (fading) {
            fadeAt += delta;
            if (!fadeWaits && fadeAt >= fadeOut + fadeHold + fadeIn) {
                fading = false;
            }
        }
    }

    private void applyView() {
        var flown = scene == null ? null : scene.flown(seconds, this::actorPlace);
        if (flown != null) {
            viewed = true;
            view.standAt(
                    (float) flown.x(), (float) flown.y(), (float) flown.z(), flown.yaw(), flown.pitch(), flown.roll());
        }
        var pose = flown != null ? null : bones.getPose();
        if (pose != null) {
            if (!viewed && camera.isInteractiveOrtho()) {
                camera.setView(CameraView.PERSPECTIVE);
            }
            viewed = true;
            view.standAt(pose.x(), pose.y(), pose.z(), pose.yawDegrees(), pose.pitchDegrees(), pose.rollDegrees());
        }
        if (!viewed) {
            return;
        }
        lastYaw = view.getYawDegrees();
        lastPitch = view.getPitchDegrees();
        camera.setPosition(view.getX(), view.getY(), view.getZ());
        camera.setDirection(
                lastYaw + shake.getYawDegrees(), lastPitch + shake.getPitchDegrees(), view.getRollDegrees());
        var fov = view.getFov() != 0 ? view.getFov() : playerFov != 0 ? playerFov : freeFov;
        camera.setFov(Math.clamp(flown != null ? (float) flown.fov() : fov, 1, 170));
    }

    private @Nullable Vector actorPlace(int index, double moment) {
        var actor = actors.get(index);
        return actor == null ? null : new Vector(actor.x(), actor.y(), actor.z());
    }

    public interface Rigs {
        Rigs NONE = new Rigs() {
            @Override
            public @Nullable SceneRig of(SceneActor actor) {
                return null;
            }

            @Override
            public @Nullable SceneRig getCamera() {
                return null;
            }
        };

        @Nullable
        SceneRig of(SceneActor actor);

        @Nullable
        SceneRig getCamera();

        default boolean takesBody(int skill) {
            return false;
        }

        default @Nullable Entrance getEntrance(int npc) {
            return null;
        }

        default @Nullable CastPlay getCasting(SceneActor actor) {
            return null;
        }

        default float getRadius(int npc) {
            return 0;
        }

        default float getHeight(int npc) {
            return 0;
        }
    }

    public record CastPlay(String sequence, double seconds) {}

    public record EntranceCue(SceneActor actor, Entrance entrance) {}

    public interface Ground {
        double getFeet(double x, double y, double z);

        default double getFeet(double x, double y, double z, double radius, double height) {
            return getFeet(x, y, z);
        }
    }

    public record SkillCue(SceneActor caster, SceneActor target, int skill) {}

    public record EffectCue(String effect, @Nullable SceneActor on, double x, double y, double z, boolean loop) {}

    public record Message(int id, String note, int window, int alpha) {}

    public static final class SceneActor implements Target {
        private final Actor spawn;
        private double x;
        private double y;
        private double feet;
        private int yaw;
        private float halfHeight = DEFAULT_HALF_HEIGHT;
        private float cylinderRadius;
        private float cylinderHeight;
        private boolean grounded;
        private double goalX;
        private double goalY;
        private double goalFeet;
        private boolean moving;
        private boolean flying;
        private double speed;
        private boolean running;
        private @Nullable Ground floors;
        private String moveSequence = "";
        private String animation = "";
        private boolean loops;
        private boolean casting;
        private double animationSeconds;
        private double before;
        private double rate = 1;
        private boolean entering;
        private boolean dying;
        private int ends;
        private int plays;
        private int rising;
        private double risingLeft;

        SceneActor(Actor spawn) {
            this.spawn = spawn;
        }

        void enter(Entrance entrance, double since) {
            rising = switch (entrance.rise()) {
                case EnterEvents.FROM_BELOW -> -1;
                case EnterEvents.FROM_ABOVE -> 1;
                default -> 0;
            };
            risingLeft = rising == 0 ? 0 : Math.max(0, EnterEvents.RISE_SECONDS - since);
            if (!entrance.animation().isEmpty()) {
                animate(entrance.animation(), false, since, 1);
                entering = true;
            }
        }

        public boolean isEntering() {
            return entering;
        }

        public boolean fights() {
            return spawn.fights();
        }

        boolean playsOnce() {
            return !animation.isEmpty() && !loops && !dying && !casting;
        }

        void die() {
            dying = true;
        }

        void waitAgain(double over) {
            entering = false;
            animation = "";
            loops = false;
            rate = 1;
            animationSeconds = Math.max(0, over);
            ends++;
        }

        public int getEnds() {
            return ends;
        }

        public Actor getSpawn() {
            return spawn;
        }

        public boolean isAirship() {
            return spawn.action() == Action.AIRSHIP_SPAWN;
        }

        @Override
        public float x() {
            return (float) x;
        }

        @Override
        public float y() {
            return (float) y;
        }

        @Override
        public float z() {
            return (float) (feet + risen() + halfHeight);
        }

        @Override
        public int getYaw() {
            return yaw;
        }

        public float getFeet() {
            return (float) (feet + risen());
        }

        public float getStandingFeet() {
            return (float) feet;
        }

        public float getHalfHeight() {
            return halfHeight;
        }

        public void setHalfHeight(float halfHeight) {
            if (!grounded) {
                feet += this.halfHeight - halfHeight;
            }
            this.halfHeight = halfHeight;
        }

        public boolean isMoving() {
            return moving;
        }

        public boolean isRunning() {
            return running;
        }

        public String getMoveSequence() {
            return moveSequence;
        }

        public String getAnimation() {
            return animation;
        }

        public boolean loops() {
            return loops;
        }

        public double getAnimationSeconds() {
            return animationSeconds;
        }

        double getBefore() {
            return before;
        }

        public int getWatches() {
            return spawn.watches();
        }

        void watch(SceneActor watched) {
            var dx = watched.x - x;
            var dy = watched.y - y;
            if (watched == this || moving || isAirship() || dx == 0 && dy == 0) {
                return;
            }
            yaw = (int) Math.round(Math.atan2(dy, dx) * TURN / (2 * Math.PI));
        }

        public boolean isCasting() {
            return casting;
        }

        void stand(Ground ground) {
            x = spawn.x();
            y = spawn.y();
            yaw = spawn.yaw();
            grounded = !spawn.fly() && !isAirship();
            feet = spawn.z() - (grounded ? 0 : halfHeight);
            if (grounded) {
                var floor = place(ground, spawn.z());
                if (!Double.isNaN(floor)) {
                    feet = floor + FLOOR_CLEARANCE;
                }
            }
        }

        void cylinder(float radius, float height) {
            cylinderRadius = radius;
            cylinderHeight = height;
        }

        void teleport(Actor order, Ground ground) {
            x = order.x();
            y = order.y();
            if (!order.player()) {
                yaw = order.yaw();
            }
            moving = false;
            var floor = place(ground, order.z());
            feet = Double.isNaN(floor) ? order.z() : floor + (grounded ? FLOOR_CLEARANCE : 0);
        }

        void moveTo(Actor order, @Nullable SceneActor target, Ground ground, Speeds speeds) {
            goalX = target == null ? order.x() : target.x;
            goalY = target == null ? order.y() : target.y;
            flying = !grounded;
            goalFeet = (target == null ? order.z() : target.z()) - halfHeight;
            running = order.run();
            speed = SceneOrders.speed(order, SceneOrders.npc(spawn), speeds);
            moving = true;
            animation = "";
            rate = 1;
            moveSequence = order.moveSequence();
            if (!moveSequence.isEmpty()) {
                animationSeconds = 0;
            }
            yaw = (int) Math.round(Math.atan2(goalY - y, goalX - x) * TURN / (2 * Math.PI));
            floors = ground;
        }

        void flyTo(Actor order) {
            goalX = order.x();
            goalY = order.y();
            goalFeet = order.z() - halfHeight;
            speed = SceneOrders.speed(order, 0, Speeds.NONE);
            moving = true;
            flying = true;
        }

        public void animate(String name, boolean loop) {
            animate(name, loop, 0, 1);
        }

        public void animate(String name, boolean loop, double since, double rate) {
            animation = name;
            loops = loop;
            casting = false;
            entering = false;
            dying = false;
            this.rate = rate;
            animationSeconds = Math.max(0, since) * rate;
            moving = false;
            plays++;
        }

        public int getPlays() {
            return plays;
        }

        void cast() {
            casting = true;
            rate = 1;
        }

        public void stopMoving() {
            moving = false;
        }

        void advance(double delta) {
            before = animationSeconds;
            animationSeconds += delta * rate;
            risingLeft = Math.max(0, risingLeft - delta);
            if (!moving) {
                return;
            }
            if (flying) {
                fly(delta);
                return;
            }
            var dx = goalX - x;
            var dy = goalY - y;
            var left = Math.sqrt(dx * dx + dy * dy);
            var step = speed * delta;
            if (step >= left) {
                x = goalX;
                y = goalY;
                moving = false;
            } else {
                x += dx / left * step;
                y += dy / left * step;
            }
            var walked = floors;
            if (grounded && walked != null) {
                var floor = walked.getFeet(x, y, feet);
                if (!Double.isNaN(floor)) {
                    feet = floor + FLOOR_CLEARANCE;
                }
            }
        }

        private double risen() {
            return rising == 0 ? 0 : rising * 2.0 * halfHeight * risingLeft / EnterEvents.RISE_SECONDS;
        }

        private double place(Ground ground, double z) {
            return cylinderRadius > 0
                    ? ground.getFeet(x, y, z, cylinderRadius, cylinderHeight)
                    : ground.getFeet(x, y, z);
        }

        private void fly(double delta) {
            var dx = goalX - x;
            var dy = goalY - y;
            var dz = goalFeet - feet;
            if (isAirship()) {
                turn(dx, dy, delta);
            }
            var left = Math.sqrt(dx * dx + dy * dy + dz * dz);
            var step = speed * delta;
            if (step >= left) {
                x = goalX;
                y = goalY;
                feet = goalFeet;
                moving = false;
            } else {
                x += dx / left * step;
                y += dy / left * step;
                feet += dz / left * step;
            }
        }

        private void turn(double dx, double dy, double delta) {
            if (dx == 0 && dy == 0) {
                return;
            }
            var wanted = (int) Math.round(Math.atan2(dy, dx) * TURN / (2 * Math.PI));
            var left = Math.floorMod(wanted - yaw + TURN / 2, TURN) - TURN / 2;
            var step = (int) Math.round(Math.min(Math.abs(left), AIRSHIP_TURN * delta));
            yaw += left < 0 ? -step : step;
        }
    }

    private static void endOnce(SceneActor actor, @Nullable SceneRig rig) {
        if (rig == null || !actor.playsOnce()) {
            return;
        }
        var sequence = rig.findSequence(actor.getAnimation());
        if (sequence < 0 || !rig.waits()) {
            return;
        }
        var over = actor.getAnimationSeconds() - rig.getSeconds(sequence);
        if (over >= 0) {
            actor.waitAgain(over);
        }
    }

    private static @Nullable Actor firstActor(Scene scene) {
        for (var item : scene.items()) {
            for (var order : item.actors()) {
                if (order.action().enters() && order.placed() && order.stands()) {
                    return order;
                }
            }
        }
        return null;
    }

    private static int units(int degrees) {
        return degrees * TURN / 360;
    }
}

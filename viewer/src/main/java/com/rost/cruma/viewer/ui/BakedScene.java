package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.export.SceneTimeline;
import com.rost.cruma.core.export.SceneTimeline.Place;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.MapScenes.Vector;
import com.rost.cruma.core.world.PostEffects.Effect;
import com.rost.cruma.core.world.SceneOrders.NpcSpeed;
import com.rost.cruma.core.world.SceneOrders.Size;
import com.rost.cruma.core.world.ShotMath;
import com.rost.cruma.core.world.ShotMath.Target;
import com.rost.cruma.core.world.SoundSpans;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.SceneDoors.Moved;
import com.rost.cruma.viewer.render.SceneOverlay;
import com.rost.cruma.viewer.render.ScenePostEffects;
import com.rost.cruma.viewer.spawn.SpawnModelData;
import com.rost.cruma.viewer.spawn.SpawnModels.Animated;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.collections.api.list.primitive.MutableFloatList;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

public final class BakedScene {
    private static final float CUT_SHARE = 4;
    private static final float LEAST_CUT = 64;
    private static final int VIEW_FLOATS = 7;
    private static final int TURN = 0x10000;
    private static final double LANDED = 2;

    private final Scene scene;
    private final int rate;
    private final float[] views;
    private final BitSet holds;
    private final BitSet cuts;
    private final BitSet marks;
    private final List<Stood> stood;
    private final List<SceneOverlay> overlays;
    private final List<Post> posts;
    private final List<Body> bodies;
    private final List<PlayedEffect> effects;
    private final List<Cue> cues;
    private final List<Clip> clips;
    private final Map<String, Double> clipSeconds;
    private final List<DoorStep> doors;
    private final Map<Integer, String> names;
    private final Map<Integer, List<String>> sequences;
    private final Map<Integer, Map<String, Double>> lengths;
    private final Map<Integer, Size> sizes;
    private final Map<Integer, NpcSpeed> speeds;
    private final SceneTimeline timeline;
    private final int steps;

    private BakedScene(Builder builder, SceneTimeline timeline) {
        scene = builder.scene;
        rate = builder.rate;
        views = builder.views.toArray();
        holds = builder.holds;
        cuts = builder.cuts;
        marks = builder.marks;
        stood = stood(builder.indexes, timeline);
        overlays = List.copyOf(builder.overlays);
        posts = List.copyOf(builder.posts);
        bodies = List.copyOf(builder.bodies);
        effects = List.copyOf(builder.effects);
        cues = List.copyOf(builder.cues);
        clips = List.copyOf(builder.clips);
        clipSeconds = Map.copyOf(builder.clipSeconds);
        doors = List.copyOf(builder.doors);
        names = Map.copyOf(builder.names);
        sequences = Map.copyOf(builder.sequences);
        lengths = Map.copyOf(builder.lengths);
        sizes = Map.copyOf(builder.sizes);
        speeds = Map.copyOf(builder.speeds);
        this.timeline = timeline;
        steps = overlays.size();
    }

    public record Stood(int index, String name, double enters, double leaves, List<Place> places) {
        Place place(double seconds) {
            var low = 0;
            var high = places.size() - 1;
            while (low < high) {
                var middle = (low + high + 1) >>> 1;
                if (places.get(middle).seconds() <= seconds) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            var from = places.get(low);
            if (low + 1 >= places.size() || seconds <= from.seconds()) {
                return from;
            }
            var to = places.get(low + 1);
            var share = (seconds - from.seconds()) / (to.seconds() - from.seconds());
            return new Place(
                    seconds,
                    mix(from.x(), to.x(), share),
                    mix(from.y(), to.y(), share),
                    mix(from.z(), to.z(), share),
                    turn(from.yaw(), to.yaw(), share, (float) (2 * Math.PI)));
        }
    }

    public record View(float x, float y, float z, float yaw, float pitch, float roll, float fov) {}

    record Post(int blurAlpha, @Nullable Effect color, float colorShare) {}

    record Stand(Body body, float x, float y, float z, float yaw, int sequence, float seconds) {}

    record PlayedEffect(
            EffectParts parts, double start, double end, float yaw, List<Place> places, float life, boolean fixedLife) {
        PlayedEffect {
            places = List.copyOf(places);
        }

        PlayedEffect(EffectParts parts, double start, double end, float yaw, List<Place> places) {
            this(parts, start, end, yaw, places, Float.NaN, false);
        }

        boolean alive(double seconds) {
            return start <= seconds && (Double.isNaN(end) || seconds < end);
        }

        Place place(double seconds) {
            var low = 0;
            var high = places.size() - 1;
            while (low < high) {
                var middle = (low + high + 1) >>> 1;
                if (places.get(middle).seconds() <= seconds) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return places.get(low);
        }
    }

    record Cue(
            double seconds,
            @Nullable Music music,
            @Nullable EmitterSound sound,
            @Nullable Vec3 at) {}

    record Clip(
            @Nullable Music music,
            @Nullable EmitterSound sound,
            @Nullable Vec3 at,
            double start,
            double end) {
        boolean sounds(double seconds) {
            return start <= seconds && seconds < end;
        }
    }

    record Sounding(Clip clip, double from) {}

    record Quiet(boolean music, boolean ambient, boolean effects) {}

    private record DoorStep(double seconds, Moved doors) {}

    public Scene getScene() {
        return scene;
    }

    int getRate() {
        return rate;
    }

    int getSteps() {
        return steps;
    }

    SceneTimeline getTimeline() {
        return timeline;
    }

    List<PlayedEffect> getEffects() {
        return effects;
    }

    List<Cue> getCues() {
        return cues;
    }

    List<Clip> getClips() {
        return clips;
    }

    List<Sounding> getSounding(double seconds) {
        var sounding = new ArrayList<Sounding>(4);
        for (var clip : clips) {
            if (clip.sounds(seconds)) {
                sounding.add(new Sounding(clip, seconds - clip.start()));
            }
        }
        sounding.sort(Comparator.comparingDouble(playing -> playing.clip().start()));
        return sounding;
    }

    Quiet getQuiet(double seconds) {
        var music = false;
        var ambient = false;
        var effects = false;
        for (var cue : cues) {
            if (cue.seconds() > seconds) {
                break;
            }
            var item = cue.music();
            if (item != null) {
                music |= item.stopMusic() || !item.name().isEmpty() && !item.sound() && !item.voice();
                ambient |= item.stopAmbient();
                effects |= item.stopEffects();
            }
        }
        return new Quiet(music, ambient, effects);
    }

    public double clipSeconds(Music cue) {
        var seconds = clipSeconds.get(clipName(cue));
        return seconds == null ? Double.NaN : seconds;
    }

    @Nullable
    String getName(int index) {
        return names.get(index);
    }

    public @Nullable List<String> getSequences(int index) {
        return sequences.get(index);
    }

    public @Nullable Size getSize(int index) {
        return sizes.get(index);
    }

    public @Nullable NpcSpeed getSpeed(int npc) {
        return speeds.get(npc);
    }

    public double getSequenceSeconds(int index, String sequence) {
        var known = lengths.get(index);
        var seconds = known == null ? null : known.get(sequence.toLowerCase(Locale.ROOT));
        return seconds == null ? Double.NaN : seconds;
    }

    public boolean hasSequence(int index, String sequence) {
        var known = sequences.get(index);
        if (known == null) {
            return true;
        }
        for (var name : known) {
            if (name.equalsIgnoreCase(sequence)) {
                return true;
            }
        }
        return false;
    }

    public List<Stood> getStood() {
        return stood;
    }

    public @Nullable Place place(int index, double seconds) {
        var near = 1.0 / rate;
        for (var actor : stood) {
            if (actor.index() == index
                    && !actor.places().isEmpty()
                    && seconds >= actor.enters() - near
                    && seconds <= actor.leaves() + near) {
                return actor.place(seconds);
            }
        }
        return null;
    }

    public @Nullable Target getTarget(int index, double seconds) {
        var place = place(index, seconds);
        return place == null
                ? null
                : new Target(place.x(), place.y(), place.z(), (int) Math.round(place.yaw() * TURN / (2 * Math.PI)));
    }

    public boolean isMarked(double seconds) {
        var step = step(seconds);
        return step >= 0 && marks.get(step);
    }

    public boolean hasMarks() {
        return !marks.isEmpty();
    }

    public double nextMark(double seconds) {
        for (var span : getMarkSpans()) {
            if (span[0] >= seconds) {
                return span[0];
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    public List<double[]> getMarkSpans() {
        var spans = new ArrayList<double[]>();
        for (var from = marks.nextSetBit(0); from >= 0; ) {
            var to = marks.nextClearBit(from);
            spans.add(new double[] {(double) (from + 1) / rate, (double) Math.min(to + 1, steps) / rate});
            from = marks.nextSetBit(to);
        }
        return spans;
    }

    public double findLanding(GameScenes.Camera shot, double from, double until) {
        var first = Math.max(0, step(from));
        var last = step(until);
        var turned = getTarget(shot.target(), from);
        if (turned == null) {
            return until;
        }
        for (var step = first; step <= last && step >= 0; step++) {
            var seconds = (double) (step + 1) / rate;
            var target = getTarget(shot.target(), seconds);
            if (target == null) {
                continue;
            }
            var pose = ShotMath.pose(shot, new Target(target.x(), target.y(), target.z(), turned.yaw()), 0);
            var view = getView(step);
            var dx = pose.x() - view.x;
            var dy = pose.y() - view.y;
            var dz = pose.z() - view.z;
            if (dx * dx + dy * dy + dz * dz <= LANDED * LANDED) {
                return seconds;
            }
        }
        return until;
    }

    public List<List<Vector>> getLines(double least) {
        var lines = new ArrayList<List<Vector>>();
        List<Vector> line = null;
        for (var step = 0; step < steps; step++) {
            if (!holds.get(step)) {
                line = null;
                continue;
            }
            var view = getView(step);
            var point = new Vector(view.x, view.y, view.z);
            if (line == null || cuts.get(step)) {
                line = new ArrayList<>();
                lines.add(line);
                line.add(point);
            } else if (line.getLast().distance(point) >= least) {
                line.add(point);
            }
        }
        return lines;
    }

    public @Nullable View getView(double seconds) {
        var step = step(seconds);
        if (step < 0 || !holds.get(step)) {
            return null;
        }
        var share = share(seconds, step);
        if (share <= 0 || step + 1 >= steps || cuts.get(step + 1) || !holds.get(step + 1)) {
            return getView(step);
        }
        var from = getView(step);
        var to = getView(step + 1);
        return new View(
                mix(from.x, to.x, share),
                mix(from.y, to.y, share),
                mix(from.z, to.z, share),
                turn(from.yaw, to.yaw, share, 360),
                mix(from.pitch, to.pitch, share),
                turn(from.roll, to.roll, share, 360),
                mix(from.fov, to.fov, share));
    }

    @Nullable
    SceneOverlay getOverlay(double seconds) {
        var step = step(seconds);
        if (step < 0) {
            return null;
        }
        var overlay = overlays.get(step);
        var share = share(seconds, step);
        if (share <= 0 || step + 1 >= steps) {
            return overlay;
        }
        var next = overlays.get(step + 1);
        if (overlay.fade() == next.fade() || overlay.fadeColor() != next.fadeColor()) {
            return overlay;
        }
        return new SceneOverlay(
                mix(overlay.fade(), next.fade(), share),
                overlay.fadeColor(),
                overlay.wide(),
                overlay.slidesShow(),
                overlay.slides(),
                overlay.messages());
    }

    @Nullable
    Post post(double seconds) {
        var step = step(seconds);
        return step < 0 ? null : posts.get(step);
    }

    List<Stand> getStands(double seconds) {
        var step = step(seconds);
        if (step < 0) {
            return List.of();
        }
        var share = share(seconds, step);
        var stands = new ArrayList<Stand>(bodies.size());
        for (var body : bodies) {
            if (body.covers(step)) {
                stands.add(body.stand(step, share));
            } else if (body.startsAt(step + 1)) {
                stands.add(body.stand(step + 1, 0));
            }
        }
        return stands;
    }

    Map<String, float[]> getDoors(double seconds) {
        var matrices = new HashMap<String, float[]>();
        for (var step : doors) {
            if (step.seconds > seconds) {
                break;
            }
            matrices.putAll(step.doors.moved());
            matrices.keySet().removeAll(step.doors.restored());
        }
        return matrices;
    }

    private int step(double seconds) {
        if (steps == 0) {
            return -1;
        }
        return Math.clamp((long) Math.floor(seconds * rate + 1e-6) - 1, 0, steps - 1);
    }

    private double share(double seconds, int step) {
        var share = seconds * rate - (step + 1);
        return share < 1e-6 ? 0 : Math.min(1, share);
    }

    private View getView(int step) {
        var at = step * VIEW_FLOATS;
        return new View(
                views[at], views[at + 1], views[at + 2], views[at + 3], views[at + 4], views[at + 5], views[at + 6]);
    }

    private static String clipName(Music cue) {
        return SoundSpans.kind(cue) + "/" + cue.name().toLowerCase(Locale.ROOT);
    }

    private static List<Stood> stood(List<Integer> indexes, SceneTimeline timeline) {
        var actors = timeline.actors();
        var stood = new ArrayList<Stood>(actors.size());
        for (var at = 0; at < Math.min(indexes.size(), actors.size()); at++) {
            var actor = actors.get(at);
            stood.add(new Stood(indexes.get(at), actor.name(), actor.enters(), actor.leaves(), actor.places()));
        }
        return List.copyOf(stood);
    }

    private static float mix(float from, float to, double share) {
        return (float) (from + (to - from) * share);
    }

    private static float turn(float from, float to, double share, float whole) {
        var left = (to - from) % whole;
        if (left > whole / 2) {
            left -= whole;
        } else if (left < -whole / 2) {
            left += whole;
        }
        return (float) (from + left * share);
    }

    static final class Body {
        private final SpawnModelData model;
        private final @Nullable Animated figure;
        private final float lift;
        private final int index;
        private final MutableFloatList places = new FloatArrayList();
        private final MutableIntList sequences = new IntArrayList();
        private final MutableFloatList moments = new FloatArrayList();
        private int first = -1;

        Body(SpawnModelData model, @Nullable Animated figure, float lift, int index) {
            this.model = model;
            this.figure = figure;
            this.lift = lift;
            this.index = index;
        }

        SpawnModelData getModel() {
            return model;
        }

        @Nullable
        Animated getFigure() {
            return figure;
        }

        float getLift() {
            return lift;
        }

        int getIndex() {
            return index;
        }

        private void add(int step, float x, float y, float z, float yaw, int sequence, float seconds) {
            if (first < 0) {
                first = step;
            }
            places.add(x);
            places.add(y);
            places.add(z);
            places.add(yaw);
            sequences.add(sequence);
            moments.add(seconds);
        }

        private boolean covers(int step) {
            return first >= 0 && step >= first && step - first < sequences.size();
        }

        private boolean startsAt(int step) {
            return first == step;
        }

        private Stand stand(int step, double share) {
            var at = step - first;
            var place = at * 4;
            var next = at + 1;
            var sequence = sequences.get(at);
            var seconds = moments.get(at);
            if (share <= 0 || next >= sequences.size()) {
                return new Stand(
                        this,
                        places.get(place),
                        places.get(place + 1),
                        places.get(place + 2),
                        places.get(place + 3),
                        sequence,
                        seconds);
            }
            var then = next * 4;
            var goesOn = sequences.get(next) == sequence && moments.get(next) >= seconds;
            return new Stand(
                    this,
                    mix(places.get(place), places.get(then), share),
                    mix(places.get(place + 1), places.get(then + 1), share),
                    mix(places.get(place + 2), places.get(then + 2), share),
                    turn(places.get(place + 3), places.get(then + 3), share, (float) (2 * Math.PI)),
                    sequence,
                    goesOn ? mix(seconds, moments.get(next), share) : seconds);
        }
    }

    static final class Builder {
        private final Scene scene;
        private final int rate;
        private final MutableFloatList views = new FloatArrayList();
        private final BitSet holds = new BitSet();
        private final BitSet cuts = new BitSet();
        private final BitSet marks = new BitSet();
        private final List<Integer> indexes = new ArrayList<>();
        private final List<SceneOverlay> overlays = new ArrayList<>();
        private final List<Post> posts = new ArrayList<>();
        private final List<Body> bodies = new ArrayList<>();
        private final List<PlayedEffect> effects = new ArrayList<>();
        private final List<Cue> cues = new ArrayList<>();
        private final List<Clip> clips = new ArrayList<>();
        private final Map<String, Double> clipSeconds = new HashMap<>();
        private final List<DoorStep> doors = new ArrayList<>();
        private final Map<Integer, String> names = new HashMap<>();
        private final Map<Integer, List<String>> sequences = new HashMap<>();
        private final Map<Integer, Map<String, Double>> lengths = new HashMap<>();
        private final Map<Integer, Size> sizes = new HashMap<>();
        private final Map<Integer, NpcSpeed> speeds = new HashMap<>();
        private float lastMove;

        Builder(Scene scene, int rate) {
            this.scene = scene;
            this.rate = rate;
        }

        int next() {
            return overlays.size();
        }

        void marked(boolean holds) {
            marks.set(next(), holds);
        }

        void step(Camera camera, boolean holding, SceneOverlay overlay, ScenePostEffects effects) {
            var step = next();
            var at = camera.getPosition();
            if (step > 0) {
                var last = (step - 1) * VIEW_FLOATS;
                var dx = at.getX() - views.get(last);
                var dy = at.getY() - views.get(last + 1);
                var dz = at.getZ() - views.get(last + 2);
                var move = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (move > LEAST_CUT && move > lastMove * CUT_SHARE) {
                    cuts.set(step);
                }
                lastMove = move;
            }
            views.add(at.getX());
            views.add(at.getY());
            views.add(at.getZ());
            views.add((float) camera.getYawDegrees());
            views.add((float) camera.getPitchDegrees());
            views.add((float) camera.getRollDegrees());
            views.add(camera.getFov());
            holds.set(step, holding);
            overlays.add(!overlays.isEmpty() && overlays.getLast().equals(overlay) ? overlays.getLast() : overlay);
            var post = new Post(effects.getBlurAlpha(), effects.getColor(), effects.getColorShare());
            posts.add(!posts.isEmpty() && posts.getLast().equals(post) ? posts.getLast() : post);
        }

        Body addBody(SpawnModelData model, @Nullable Animated figure, float lift, int index) {
            var body = new Body(model, figure, lift, index);
            bodies.add(body);
            return body;
        }

        void stands(Body body, float x, float y, float z, float yaw, int sequence, float seconds) {
            body.add(next(), x, y, z, yaw, sequence, seconds);
        }

        void effect(PlayedEffect effect) {
            effects.add(effect);
        }

        void cue(Cue cue) {
            cues.add(cue);
        }

        void clip(Clip clip) {
            clips.add(clip);
        }

        void clipSeconds(Music cue, double seconds) {
            if (!Double.isNaN(seconds)) {
                clipSeconds.put(clipName(cue), seconds);
            }
        }

        double clipOf(Music cue) {
            return clipSeconds.getOrDefault(clipName(cue), Double.POSITIVE_INFINITY);
        }

        void soundsStop(double seconds) {
            clips.replaceAll(clip -> clip.sound() == null || clip.end() <= seconds
                    ? clip
                    : new Clip(clip.music(), clip.sound(), clip.at(), clip.start(), seconds));
        }

        void doors(double seconds, Moved moved) {
            doors.add(new DoorStep(seconds, moved));
        }

        void sequences(int index, List<String> names) {
            sequences.putIfAbsent(index, List.copyOf(names));
        }

        void size(int index, Size size) {
            sizes.putIfAbsent(index, size);
        }

        void speeds(Map<Integer, NpcSpeed> known) {
            speeds.putAll(known);
        }

        void lengths(int index, Map<String, Double> seconds) {
            var named = new HashMap<String, Double>();
            seconds.forEach((name, length) -> named.put(name.toLowerCase(Locale.ROOT), length));
            lengths.putIfAbsent(index, Map.copyOf(named));
        }

        void name(int index, String name) {
            names.putIfAbsent(index, name);
            indexes.add(index);
        }

        BakedScene build(SceneTimeline timeline) {
            return new BakedScene(this, timeline);
        }
    }
}

package com.rost.cruma.core.export;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.export.SceneTimeline.Kind;
import com.rost.cruma.core.export.SceneTimeline.Level;
import com.rost.cruma.core.export.SceneTimeline.Sound;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class SceneAudioMix {
    public static final int RATE = 48_000;

    public static final int CHANNELS = 2;

    static final float CEILING = .97f;

    private static final int LOOK_AHEAD = RATE / 100;
    private static final float FULL_SCALE = 32767f;
    private static final float MIDDLE = (float) Math.sqrt(.5);
    private static final String MAP_SOUNDS = "Map sounds";

    private SceneAudioMix() {}

    public record Mix(SoundClip clip, List<String> skipped) {
        public Mix {
            skipped = List.copyOf(skipped);
        }
    }

    public static Mix mix(SceneTimeline timeline, double from, double to, BooleanSupplier cancelled) {
        return mix(timeline.name(), timeline.sounds(), timeline.seconds(), from, to, cancelled);
    }

    public static SceneTimeline forStrips(SceneTimeline timeline, BooleanSupplier cancelled) {
        var kept = new ArrayList<Sound>();
        var ambient = new ArrayList<Sound>();
        for (var sound : timeline.sounds()) {
            if (sound.kind() == Kind.AMBIENT) {
                ambient.add(sound);
            } else if (sound.levels().isEmpty()
                    || sound.clip() == null
                    || sound.clip().channels() == 1 && sound.pitch() == 1 && !sound.loop()) {
                kept.add(sound);
            } else {
                var alone = mix(
                        sound.name(), List.of(sound), timeline.seconds(), sound.start(), timeline.seconds(), cancelled);
                if (alone.clip().frames() > 0) {
                    kept.add(new Sound(
                            sound.kind(),
                            sound.name() + " " + kept.size(),
                            null,
                            trimmed(alone.clip()),
                            sound.start(),
                            timeline.seconds(),
                            1,
                            0,
                            0));
                }
            }
        }
        if (!ambient.isEmpty()) {
            var all = mix(MAP_SOUNDS, ambient, timeline.seconds(), 0, timeline.seconds(), cancelled);
            kept.add(new Sound(Kind.AMBIENT, MAP_SOUNDS, null, all.clip(), 0, timeline.seconds(), 1, 0, 0));
        }
        return new SceneTimeline(
                timeline.name(),
                timeline.rate(),
                timeline.seconds(),
                timeline.views(),
                timeline.actors(),
                kept,
                timeline.effects(),
                timeline.layers(),
                timeline.grades(),
                timeline.doors());
    }

    static void limit(float[] stereo) {
        var frames = stereo.length / CHANNELS;
        var gains = new float[frames];
        var loud = false;
        for (var frame = 0; frame < frames; frame++) {
            var peak = Math.max(Math.abs(stereo[frame * CHANNELS]), Math.abs(stereo[frame * CHANNELS + 1]));
            gains[frame] = peak > CEILING ? CEILING / peak : 1;
            loud |= peak > CEILING;
        }
        if (!loud) {
            return;
        }
        var lowest = lowest(gains);
        var sums = sums(lowest);
        for (var frame = 0; frame < frames; frame++) {
            var first = frame - LOOK_AHEAD;
            var last = frame + LOOK_AHEAD;
            var inside = sums[Math.min(last + 1, frames)] - sums[Math.max(first, 0)];
            var before = Math.max(0, -first) * (double) lowest[0];
            var after = Math.max(0, last + 1 - frames) * (double) lowest[frames - 1];
            var gain = (float) ((before + inside + after) / (2 * LOOK_AHEAD + 1));
            stereo[frame * CHANNELS] *= gain;
            stereo[frame * CHANNELS + 1] *= gain;
        }
    }

    private static SoundClip trimmed(SoundClip clip) {
        var samples = clip.samples();
        var last = samples.length;
        while (last > 0 && samples[last - 1] == 0) {
            last--;
        }
        last += last % CHANNELS;
        return new SoundClip(
                clip.name(),
                clip.channels(),
                clip.sampleRate(),
                Arrays.copyOf(samples, Math.min(samples.length, last)));
    }

    private static Mix mix(
            String name, List<Sound> sounds, double seconds, double from, double to, BooleanSupplier cancelled) {
        var frames = Math.max(0, (int) Math.ceil((to - from) * RATE));
        var sum = new float[frames * CHANNELS];
        var decoded = new HashMap<Path, SoundClip>();
        var skipped = new ArrayList<String>();
        for (var sound : sounds) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            SoundClip clip;
            try {
                clip = clip(sound, decoded);
            } catch (IOException | RuntimeException unreadable) {
                if (!skipped.contains(sound.name())) {
                    skipped.add(sound.name());
                }
                continue;
            }
            add(sum, sound, clip, from, seconds);
        }
        limit(sum);
        var samples = new short[sum.length];
        for (var index = 0; index < sum.length; index++) {
            samples[index] = (short) Math.round(Math.clamp(sum[index], -1f, 1f) * FULL_SCALE);
        }
        return new Mix(new SoundClip(name, CHANNELS, RATE, samples), skipped);
    }

    private static SoundClip clip(Sound sound, Map<Path, SoundClip> decoded) throws IOException {
        if (sound.clip() != null) {
            return sound.clip();
        }
        var source = Objects.requireNonNull(sound.source(), "a sound needs a clip or a file");
        var known = decoded.get(source);
        if (known != null) {
            return known;
        }
        var clip = SceneExport.decode(sound);
        decoded.put(source, clip);
        return clip;
    }

    private static void add(float[] sum, Sound sound, SoundClip clip, double from, double sceneEnd) {
        var rate = (double) clip.sampleRate() * sound.pitch();
        var clipEnd = sound.loop() ? Double.POSITIVE_INFINITY : sound.start() + clip.frames() / rate;
        var end = Double.isNaN(sound.end()) ? sceneEnd : sound.end();
        var cut = end < clipEnd;
        var fadeOut = cut && end < sceneEnd ? sound.fadeOut() : 0f;
        var stop = cut ? Math.min(clipEnd, end + fadeOut) : clipEnd;
        var first = Math.max(0, (int) Math.ceil((sound.start() - from) * RATE));
        var last = Math.min(sum.length / CHANNELS, (int) Math.ceil((stop - from) * RATE));
        var levels = new Sides(sound.levels());
        for (var frame = first; frame < last; frame++) {
            var seconds = from + (double) frame / RATE;
            var left = clip.channels() == 1 ? sound.volume() * MIDDLE : sound.volume();
            var right = left;
            if (!sound.levels().isEmpty()) {
                levels.moveTo(seconds);
                left = levels.getLeft();
                right = levels.getRight();
            }
            var fade = 1f;
            if (sound.fadeIn() > 0) {
                fade *= (float) Math.min(1, (seconds - sound.start()) / sound.fadeIn());
            }
            if (fadeOut > 0 && seconds > end) {
                fade *= (float) Math.max(0, 1 - (seconds - end) / fadeOut);
            }
            var at = (seconds - sound.start()) * rate;
            if (sound.loop()) {
                at %= clip.frames();
            }
            sum[frame * CHANNELS] += fade * left * sample(clip, at, 0);
            sum[frame * CHANNELS + 1] += fade * right * sample(clip, at, clip.channels() - 1);
        }
    }

    private static float sample(SoundClip clip, double at, int channel) {
        var whole = (int) Math.floor(at);
        var part = (float) (at - whole);
        var before = frame(clip, whole - 1, channel);
        var here = frame(clip, whole, channel);
        var next = frame(clip, whole + 1, channel);
        var after = frame(clip, whole + 2, channel);
        return here
                + .5f
                        * part
                        * (next
                                - before
                                + part
                                        * (2 * before
                                                - 5 * here
                                                + 4 * next
                                                - after
                                                + part * (3 * (here - next) + after - before)));
    }

    private static float frame(SoundClip clip, int frame, int channel) {
        if (frame < 0 || frame >= clip.frames()) {
            return 0;
        }
        return clip.samples()[frame * clip.channels() + channel] / FULL_SCALE;
    }

    private static final class Sides {
        private final List<Level> levels;
        private int key;
        private float left;
        private float right;
        private float nextLeft;
        private float nextRight;
        private double part;

        Sides(List<Level> levels) {
            this.levels = levels;
            if (!levels.isEmpty()) {
                set();
            }
        }

        void moveTo(double seconds) {
            var moved = false;
            while (key + 1 < levels.size() && levels.get(key + 1).seconds() <= seconds) {
                key++;
                moved = true;
            }
            if (moved) {
                set();
            }
            var here = levels.get(key).seconds();
            var next = key + 1 < levels.size() ? levels.get(key + 1).seconds() : here;
            part = next > here ? Math.clamp((seconds - here) / (next - here), 0, 1) : 0;
        }

        float getLeft() {
            return left + (float) part * (nextLeft - left);
        }

        float getRight() {
            return right + (float) part * (nextRight - right);
        }

        private void set() {
            var level = levels.get(key);
            left = side(level, true);
            right = side(level, false);
            var next = levels.get(Math.min(key + 1, levels.size() - 1));
            nextLeft = side(next, true);
            nextRight = side(next, false);
        }

        private static float side(Level level, boolean left) {
            var angle = (level.pan() + 1) * Math.PI / 4;
            return level.volume() * (float) (left ? Math.cos(angle) : Math.sin(angle));
        }
    }

    private static float[] lowest(float[] gains) {
        var result = new float[gains.length];
        var window = new ArrayDeque<Integer>();
        for (var frame = -LOOK_AHEAD; frame < gains.length; frame++) {
            var entering = frame + LOOK_AHEAD;
            if (entering < gains.length) {
                while (!window.isEmpty() && gains[window.peekLast()] >= gains[entering]) {
                    window.removeLast();
                }
                window.addLast(entering);
            }
            while (window.peekFirst() < frame - LOOK_AHEAD) {
                window.removeFirst();
            }
            if (frame >= 0) {
                result[frame] = gains[window.peekFirst()];
            }
        }
        return result;
    }

    private static double[] sums(float[] values) {
        var sums = new double[values.length + 1];
        for (var index = 0; index < values.length; index++) {
            sums[index + 1] = sums[index] + values[index];
        }
        return sums;
    }
}

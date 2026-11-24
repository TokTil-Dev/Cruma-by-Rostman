package com.rost.cruma.core.world;

import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneItems.Part;
import java.util.ArrayList;
import java.util.List;

public final class SoundSpans {
    private SoundSpans() {}

    public enum Kind {
        TRACK,
        VOICE,
        SOUND
    }

    public interface Clips {
        Clips NONE = _ -> Double.NaN;

        double getSeconds(Music cue);
    }

    public record Span(Key key, Kind kind, double start, double end, double clip, boolean cut) {
        public boolean sounds(double moment) {
            return start <= moment && moment < end;
        }

        public double from(double moment) {
            return moment - start;
        }
    }

    public static Music track(String name) {
        return new Music(name, false, false, false, false, false, 1, 0);
    }

    public static Music voice(String name) {
        return new Music(name, false, false, false, false, true, 1, 0);
    }

    public static Music sound(String name) {
        return new Music(name, false, false, false, true, false, 1, 0);
    }

    public static Kind kind(Music cue) {
        return cue.voice() ? Kind.VOICE : cue.sound() ? Kind.SOUND : Kind.TRACK;
    }

    public static List<Span> of(List<Item> items, double length, Clips clips) {
        var spans = new ArrayList<Span>();
        var track = -1;
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var cue = item.music();
            if (cue == null) {
                continue;
            }
            var kind = kind(cue);
            if (track >= 0
                    && (cue.stopMusic() || kind == Kind.TRACK && !cue.name().isEmpty())) {
                spans.set(track, cutAt(spans.get(track), item.seconds()));
                track = -1;
            }
            var seconds = cue.name().isEmpty() ? Double.NaN : clips.getSeconds(cue);
            if (!Double.isNaN(seconds)) {
                var ends = item.seconds() + seconds;
                spans.add(new Span(
                        new Key(Part.MUSIC, index, 0),
                        kind,
                        item.seconds(),
                        Math.min(ends, length),
                        seconds,
                        length < ends));
                if (kind == Kind.TRACK) {
                    track = spans.size() - 1;
                }
            }
        }
        return spans;
    }

    public static List<Span> at(List<Span> spans, double moment) {
        var sounding = new ArrayList<Span>(2);
        for (var span : spans) {
            if (span.sounds(moment)) {
                sounding.add(span);
            }
        }
        return sounding;
    }

    private static Span cutAt(Span span, double moment) {
        return moment >= span.end() ? span : new Span(span.key(), span.kind(), span.start(), moment, span.clip(), true);
    }
}

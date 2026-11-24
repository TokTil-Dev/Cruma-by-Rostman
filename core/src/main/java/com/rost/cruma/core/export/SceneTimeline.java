package com.rost.cruma.core.export;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.export.NpcExport.Npc;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public record SceneTimeline(
        String name,
        int rate,
        double seconds,
        List<View> views,
        List<Actor> actors,
        List<Sound> sounds,
        List<Effect> effects,
        List<Layer> layers,
        List<Grade> grades,
        List<Door> doors) {
    public SceneTimeline {
        views = List.copyOf(views);
        actors = List.copyOf(actors);
        sounds = List.copyOf(sounds);
        effects = List.copyOf(effects);
        layers = List.copyOf(layers);
        grades = List.copyOf(grades);
        doors = List.copyOf(doors);
    }

    public SceneTimeline(
            String name,
            int rate,
            double seconds,
            List<View> views,
            List<Actor> actors,
            List<Sound> sounds,
            List<Effect> effects,
            List<Layer> layers,
            List<Grade> grades) {
        this(name, rate, seconds, views, actors, sounds, effects, layers, grades, List.of());
    }

    public SceneTimeline(
            String name,
            int rate,
            double seconds,
            List<View> views,
            List<Actor> actors,
            List<Sound> sounds,
            List<Effect> effects) {
        this(name, rate, seconds, views, actors, sounds, effects, List.of(), List.of(), List.of());
    }

    public record Door(String actor, List<DoorKey> keys) {
        public Door {
            keys = List.copyOf(keys);
        }
    }

    public record DoorKey(double seconds, float x, float y, float z) {
        public boolean sameAs(DoorKey other) {
            return x == other.x && y == other.y && z == other.z;
        }
    }

    public enum Space {
        SCREEN,

        BOX
    }

    public record Layer(
            String name, Space space, int color, @Nullable Supplier<@Nullable Icon> picture, List<LayerKey> keys) {
        public Layer {
            keys = List.copyOf(keys);
        }
    }

    public record LayerKey(double seconds, float x, float y, float width, float height, float alpha) {
        public boolean sameAs(LayerKey other) {
            return x == other.x
                    && y == other.y
                    && width == other.width
                    && height == other.height
                    && alpha == other.alpha;
        }
    }

    public record Grade(double seconds, float luma, float chroma, float blue, float red) {
        public boolean sameAs(Grade other) {
            return luma == other.luma && chroma == other.chroma && blue == other.blue && red == other.red;
        }
    }

    public record View(
            double seconds,
            float x,
            float y,
            float z,
            float lookX,
            float lookY,
            float lookZ,
            float upX,
            float upY,
            float upZ,
            float fov) {
        public boolean sameAs(View other) {
            return x == other.x
                    && y == other.y
                    && z == other.z
                    && lookX == other.lookX
                    && lookY == other.lookY
                    && lookZ == other.lookZ
                    && upX == other.upX
                    && upY == other.upY
                    && upZ == other.upZ
                    && fov == other.fov;
        }
    }

    public record Place(double seconds, float x, float y, float z, float yaw) {
        public boolean sameAs(Place other) {
            return x == other.x && y == other.y && z == other.z && yaw == other.yaw;
        }
    }

    public record Actor(
            String name,
            @Nullable Rig rig,
            float lift,
            double enters,
            double leaves,
            List<Place> places,
            List<Strip> strips) {
        public Actor {
            places = List.copyOf(places);
            strips = List.copyOf(strips);
        }
    }

    public record Rig(Npc npc, @Nullable Outfit outfit, boolean still) {
        public Rig(Npc npc, @Nullable Outfit outfit) {
            this(npc, outfit, false);
        }
    }

    public record Strip(String sequence, double start, double end, double from, double to, double repeat) {}

    public enum Kind {
        MUSIC,
        VOICE,
        SOUND,
        AMBIENT
    }

    public record Level(double seconds, float volume, float pan) {
        public boolean sameAs(Level other) {
            return volume == other.volume && pan == other.pan;
        }
    }

    public record Sound(
            Kind kind,
            String name,
            @Nullable Path source,
            @Nullable SoundClip clip,
            double start,
            double end,
            float volume,
            float fadeIn,
            float fadeOut,
            float pitch,
            boolean loop,
            List<Level> levels) {
        public Sound {
            levels = List.copyOf(levels);
        }

        public Sound(
                Kind kind,
                String name,
                @Nullable Path source,
                @Nullable SoundClip clip,
                double start,
                double end,
                float volume,
                float fadeIn,
                float fadeOut) {
            this(kind, name, source, clip, start, end, volume, fadeIn, fadeOut, 1, false, List.of());
        }
    }

    public record Effect(String name, double start, double end, List<Place> places) {
        public Effect {
            places = List.copyOf(places);
        }
    }
}

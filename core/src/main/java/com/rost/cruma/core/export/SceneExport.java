package com.rost.cruma.core.export;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.audio.VorbisDecoder;
import com.rost.cruma.core.audio.WavWriter;
import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.export.LocationExport.Settings;
import com.rost.cruma.core.export.SceneTimeline.Layer;
import com.rost.cruma.core.export.SceneTimeline.Rig;
import com.rost.cruma.core.export.SceneTimeline.Sound;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class SceneExport {
    public static final String TIMELINE = "scene.json";

    public static final String SOUNDS = "scene-sounds";

    public static final String SCREEN = "scene-screen";

    private static final double BODIES = .1;
    private static final double LOCATION = .97;

    private SceneExport() {}

    public record Result(
            Path folder, int actors, int rigs, int sounds, int effects, long triangles, List<String> skipped) {
        public Result {
            skipped = List.copyOf(skipped);
        }
    }

    public static String folderName(Path parent, String scene, WorldVariant variant) {
        var name = LocationExport.toSafeName(scene) + variant.getTableSuffix();
        var free = name;
        for (var number = 2; Files.exists(parent.resolve(free)); number++) {
            free = name + "_" + number;
        }
        return free;
    }

    public static Result export(
            Path client,
            WorldVariant variant,
            SceneTimeline timeline,
            List<String> tiles,
            Path parent,
            Settings settings,
            EffectStill effects,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        var name = folderName(parent, timeline.name(), variant);
        Files.createDirectories(parent);
        var staging = Files.createTempDirectory(parent, "." + name + "-scene-");
        try {
            var skipped = new ArrayList<String>();
            var rigs = writeRigs(client, variant, timeline, settings.scale(), staging, skipped, progress, cancelled);
            var strips = SceneAudioMix.forStrips(timeline, cancelled);
            var sounds = writeSounds(strips, staging, skipped, cancelled);
            var pictures = writePictures(strips, staging, cancelled);
            var alone = tiles.isEmpty();
            var map = WorldMapCatalog.findStandaloneMap(tiles);
            var origin = alone
                    ? new float[3]
                    : map != null ? LocationExport.mapOrigin(client, map) : LocationModelBuilder.computeOrigin(tiles);
            Files.writeString(
                    staging.resolve(TIMELINE),
                    SceneJson.text(strips, rigs, sounds, pictures, origin, settings.scale(), map != null),
                    StandardCharsets.UTF_8);
            Progress writing = (stage, done) -> progress.update(stage, BODIES + (LOCATION - BODIES) * done);
            var location = alone
                    ? null
                    : map != null
                            ? LocationExport.exportMap(
                                    client, map, name, parent, settings, false, effects, writing, cancelled)
                            : LocationExport.exportTiles(
                                    client, variant, tiles, name, parent, settings, false, null, effects, writing,
                                    cancelled);
            var folder = location == null ? Files.createDirectories(parent.resolve(name)) : location.folder();
            if (location == null) {
                LocationExport.writeBlenderScript(folder);
            }
            progress.update("Putting files in place", LOCATION);
            for (var entry : List.of(LocationExport.NPCS, SOUNDS, SCREEN, TIMELINE)) {
                if (Files.exists(staging.resolve(entry))) {
                    Files.move(staging.resolve(entry), folder.resolve(entry), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.writeString(
                    folder.resolve(LocationExport.INFO),
                    infoText(strips, skipped),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            progress.update("Done", 1);
            return new Result(
                    folder,
                    timeline.actors().size(),
                    rigs.size(),
                    Math.toIntExact(
                            strips.sounds().stream().filter(sounds::containsKey).count()),
                    timeline.effects().size(),
                    location == null ? 0 : location.triangles(),
                    skipped);
        } finally {
            LocationExport.deleteTree(staging);
        }
    }

    static Map<Layer, String> writePictures(SceneTimeline timeline, Path staging, BooleanSupplier cancelled)
            throws IOException {
        var files = new IdentityHashMap<Layer, String>();
        var folder = staging.resolve(SCREEN);
        for (var layer : timeline.layers()) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            var icon = layer.picture() == null ? null : layer.picture().get();
            if (icon != null) {
                var file = files.size() + "_" + LocationExport.toSafeName(layer.name()) + ".png";
                Files.createDirectories(folder);
                Files.write(folder.resolve(file), PngWriter.encode(icon.width(), icon.height(), icon.rgba(), true));
                files.put(layer, file);
            }
        }
        return files;
    }

    static Map<Sound, String> writeSounds(
            SceneTimeline timeline, Path staging, List<String> skipped, BooleanSupplier cancelled) throws IOException {
        var files = new HashMap<Sound, String>();
        var written = new HashMap<String, String>();
        var folder = staging.resolve(SOUNDS);
        for (var sound : timeline.sounds()) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            var file = LocationExport.toSafeName(sound.name()) + ".wav";
            if (!written.containsKey(file)) {
                try {
                    var clip = sound.clip() != null ? sound.clip() : decode(sound);
                    Files.createDirectories(folder);
                    Files.write(folder.resolve(file), WavWriter.encode(clip));
                    written.put(file, file);
                } catch (IOException | RuntimeException unreadable) {
                    skipped.add(sound.name());
                    written.put(file, null);
                }
            }
            if (written.get(file) != null) {
                files.put(sound, file);
            }
        }
        return files;
    }

    static SoundClip decode(Sound sound) throws IOException {
        var source = Objects.requireNonNull(sound.source(), "a sound needs a clip or a file");
        var clip = VorbisDecoder.decode(source, sound.name());
        return clip.channels() == 1 ? new SoundClip(sound.name(), 2, clip.sampleRate(), stereo(clip.samples())) : clip;
    }

    static short[] stereo(short[] mono) {
        var both = new short[mono.length * 2];
        for (var index = 0; index < mono.length; index++) {
            both[index * 2] = mono[index];
            both[index * 2 + 1] = mono[index];
        }
        return both;
    }

    private static Map<Rig, String> writeRigs(
            Path client,
            WorldVariant variant,
            SceneTimeline timeline,
            ExportScale scale,
            Path staging,
            List<String> skipped,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        var bodies = new ArrayList<Rig>();
        for (var actor : timeline.actors()) {
            if (actor.rig() != null && !bodies.contains(actor.rig())) {
                bodies.add(actor.rig());
            }
        }
        var written = new LinkedHashMap<Rig, String>();
        if (bodies.isEmpty()) {
            return written;
        }
        var folder = Files.createDirectories(staging.resolve(LocationExport.NPCS));
        var loader = new UnrealLoader(client, new Options(false, 0), true);
        loader.worldVariant(variant);
        try {
            for (var index = 0; index < bodies.size(); index++) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("export cancelled");
                }
                var rig = bodies.get(index);
                progress.update(
                        "Writing the rig of " + rig.npc().label() + ", " + (index + 1) + " of " + bodies.size(),
                        BODIES * index / bodies.size());
                var file = NpcExport.name(rig.npc()) + ".glb";
                try {
                    if (rig.still()) {
                        NpcExport.writeStill(loader, rig.npc(), scale, folder.resolve(file));
                    } else {
                        NpcExport.writeRig(loader, rig.npc(), rig.outfit(), scale, folder.resolve(file));
                    }
                    written.put(rig, file);
                } catch (IOException | RuntimeException unreadable) {
                    skipped.add(rig.npc().label());
                }
            }
        } finally {
            loader.trimCaches();
        }
        return written;
    }

    private static String infoText(SceneTimeline timeline, List<String> skipped) {
        var text = new StringBuilder();
        text.append("\nCut scene: ")
                .append(timeline.name())
                .append(String.format(
                        Locale.ROOT, ", %.1f seconds at %d frames a second%n", timeline.seconds(), timeline.rate()));
        text.append("Actors: ")
                .append(timeline.actors().size())
                .append(", sounds: ")
                .append(timeline.sounds().size())
                .append(", effects: ")
                .append(timeline.effects().size())
                .append('\n');
        if (!skipped.isEmpty()) {
            text.append("Bodies that show as empties and sounds that are left out, as they could not be written: ")
                    .append(String.join(", ", skipped))
                    .append('\n');
        }
        text.append(TIMELINE)
                .append(" holds the camera, the actors and their strips, the sounds, the marks of the effects and what"
                        + " shows over the world, in the axes and metres of the glTF file.\n");
        text.append(
                "Open in Blender makes them from it. The fades, the bars, the slides and the lines are planes on the"
                        + " camera with keys, and a colour change is in the compositor. The motion blur is not in the"
                        + " export.\n");
        return text.toString();
    }
}

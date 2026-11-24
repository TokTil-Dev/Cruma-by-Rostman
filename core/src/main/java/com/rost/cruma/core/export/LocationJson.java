package com.rost.cruma.core.export;

import static com.rost.cruma.core.export.GlbWriter.toJsonString;

import com.rost.cruma.core.export.LocationModel.EffectActor;
import com.rost.cruma.core.export.LocationModel.EffectEmitter;
import com.rost.cruma.core.export.LocationModel.Lamp;
import com.rost.cruma.core.export.LocationModel.MusicTrack;
import com.rost.cruma.core.export.LocationModel.MusicZone;
import com.rost.cruma.core.export.LocationModel.Sound;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class LocationJson {
    static final String SOUNDS = "sounds.json";

    static final String EFFECTS = "effects.json";

    static final String MUSIC = "music.json";

    static final String LIGHTS = "lights.json";

    static final int NIGHT_FROM_HOUR = 20;

    static final int NIGHT_TO_HOUR = 6;

    static final int VERSION = 1;

    static final int SOUNDS_VERSION = 2;

    private static final String RANGE_TIME = "range_time";

    private static final String AXES =
            "The same axes as the .glb, as glTF has them: +X east, +Y up, -Z north, right-handed, in metres.";

    private static final String ATTENUATION =
            "Full volume within radius metres, then volume x radius / distance, and nothing beyond max_distance, "
                    + LocationModelBuilder.AUDIBLE_RADII
                    + " times the radius, as the client hears it. In Unity: an AudioSource with Logarithmic rolloff,"
                    + " minDistance = radius and maxDistance = max_distance.";

    private static final String SOUND_RULES =
            "The client asks in each update for an ambient sound in reach that its when lets play and that does not"
                + " play. A sound with loop plays in a loop. Any other plays once when it wins its chance, a number"
                + " from 1 to 99: rand() % 100 < chance. It rolls at once the first time when start_time is 0, and"
                + " takes the time of that roll as its start_time. Else it rolls when (int) ((now - start_time) /"
                + " seconds) is above (int) ((last - start_time) / seconds), once in each length of the clip, where"
                + " now and last are the times of this update and of the one before, in seconds of the client's clock,"
                + " the time since the computer started. A start_time that the map stores comes from the clock of the"
                + " editor, so it only sets where the lengths begin. when is always, day (from 6:00 to midnight: hour"
                + " >= 5.999 or hour <= 0.001), night (from midnight to 6:00: hour < 6.001 or hour > 23.999), water"
                + " (only while the listener is under water, where no other sound plays) or range_time: while HHMM.SS"
                + " = whole hours * 100 + whole minutes + whole seconds / 100 of the game time lies from start to end"
                + " of one of its hours, both ends included, written as HHMM. A sound that may no longer play stops.";

    private static final String MUSIC_RULES =
            "The client plays the music of the zone the listener is in, one of its tracks picked at random, or of its"
                + " night_tracks at night when it has them. Of two zones that hold the listener it takes one with"
                + " force, otherwise the later one of the list. In another zone the track fades out and a track of the"
                + " new zone starts. A track that ends plays again at once in a zone with loop, otherwise after a"
                + " wait. Outside every zone the track plays to its end.";

    private static final String MUSIC_INSIDE =
            "A zone is a brush, and bounds_min and bounds_max are only a box around it: a point outside the box is"
                + " outside the zone. To know whether a point (x, y, z) of the box is inside, walk the zone's nodes:"
                + " start at node 0 with outside = root_outside. At node n, side = x * planes[4n] + y * planes[4n + 1]"
                + " + z * planes[4n + 2] - planes[4n + 3]. When decides[n] is true, outside = side > 0. Go on to"
                + " front[n] when side > 0, otherwise to back[n]. A child of -1 ends the walk, and the point is inside"
                + " when outside is false. A zone without nodes holds every point of its box when root_outside is"
                + " false.";

    private static final String LIGHT_STRENGTH =
            "The .glb gives its lights in glTF's units, lux and candela, which Blender's importer divides by 683."
                + " strength is the same light as a plain number: how brightly the light shows a white surface that"
                + " faces it, in linear colour, where 1 shows it as white as its texture. The sun's is that number as"
                + " it is. A lamp's is that number at 1 metre, and it falls with the square of the distance, so at"
                + " half its radius the lamp gives the light the client gives there. In Unity's Universal Render"
                + " Pipeline strength is the light's Intensity. The glTF intensity is strength times 683 times pi."
                + " ambient is what a surface in shadow gets, in linear colour.";

    private LocationJson() {}

    static String lights(LocationModel model, @Nullable LocationEnvironment environment) {
        var json = new StringBuilder();
        header(json, model);
        json.append(",\n  \"strength\": ").append(toJsonString(LIGHT_STRENGTH));
        if (environment != null) {
            json.append(",\n  \"ambient\": ");
            writeNumbers(json, environment.ambientLinear());
        }
        json.append(",\n  \"lights\": [");
        var first = true;
        if (environment != null) {
            json.append("\n    {\"name\": \"Sun\", \"type\": \"sun\", \"color\": ");
            writeNumbers(json, GlbWriter.sunColor(environment));
            json.append(", \"strength\": ")
                    .append(number((float) (GlbWriter.sunStrength(environment) / Math.PI)))
                    .append('}');
            first = false;
        }
        for (var tile : model.tiles()) {
            var suffix = model.tiles().size() > 1 ? " " + tile.name() : "";
            for (var lamp : tile.lamps()) {
                json.append(first ? "\n    " : ",\n    ");
                writeLamp(json, lamp, lamp.name() + suffix, tile.name());
                first = false;
            }
        }
        return json.append(first ? "]\n}\n" : "\n  ]\n}\n").toString();
    }

    static String music(LocationModel model, int hour) {
        var json = new StringBuilder();
        header(json, model);
        json.append(",\n  \"hour\": ")
                .append(hour)
                .append(",\n  \"night\": {\"from_hour\": ")
                .append(NIGHT_FROM_HOUR)
                .append(", \"to_hour\": ")
                .append(NIGHT_TO_HOUR)
                .append("},\n  \"rules\": ")
                .append(toJsonString(MUSIC_RULES))
                .append(",\n  \"inside\": ")
                .append(toJsonString(MUSIC_INSIDE))
                .append(",\n  \"zones\": [");
        var first = true;
        for (var tile : model.tiles()) {
            for (var zone : tile.music()) {
                json.append(first ? "\n    " : ",\n    ");
                writeZone(json, zone);
                first = false;
            }
        }
        return json.append(first ? "]\n}\n" : "\n  ]\n}\n").toString();
    }

    static String sounds(LocationModel model, int hour) {
        var json = new StringBuilder();
        header(json, model, SOUNDS_VERSION);
        json.append(",\n  \"hour\": ")
                .append(hour)
                .append(",\n  \"attenuation\": ")
                .append(toJsonString(ATTENUATION))
                .append(",\n  \"rules\": ")
                .append(toJsonString(SOUND_RULES))
                .append(",\n  \"sounds\": [");
        var first = true;
        for (var tile : model.tiles()) {
            for (var sound : tile.sounds()) {
                json.append(first ? "\n    " : ",\n    ");
                writeSound(json, sound);
                first = false;
            }
        }
        return json.append(first ? "]\n}\n" : "\n  ]\n}\n").toString();
    }

    static String effects(LocationModel model) {
        var json = new StringBuilder();
        header(json, model);
        json.append(",\n  \"effects\": [");
        var first = true;
        for (var tile : model.tiles()) {
            for (var actor : tile.effects()) {
                json.append(first ? "\n    " : ",\n    ");
                writeEffect(json, actor);
                first = false;
            }
        }
        return json.append(first ? "]\n}\n" : "\n  ]\n}\n").toString();
    }

    static void writeSound(StringBuilder json, Sound sound) {
        json.append("{\"name\": ")
                .append(toJsonString(sound.name()))
                .append(", \"file\": ")
                .append(toJsonString(sound.file()))
                .append(", \"kind\": ")
                .append(sound.emitter() ? "\"emitter\"" : "\"ambient\"");
        if (!sound.actor().isEmpty()) {
            json.append(", \"actor\": ").append(toJsonString(sound.actor()));
        }
        json.append(", \"tile\": ").append(toJsonString(sound.tile())).append(", \"position\": ");
        writeNumbers(json, sound.position());
        json.append(", \"spatial\": ")
                .append(sound.spatial() ? "\"3d\"" : "\"2d\"")
                .append(", \"radius\": ")
                .append(number(sound.radius()))
                .append(", \"max_distance\": ")
                .append(number(sound.maxDistance()))
                .append(", \"volume\": ")
                .append(number(sound.volume()));
        if (sound.emitter()) {
            json.append(", \"pitch_min\": ")
                    .append(number(sound.pitchMin()))
                    .append(", \"pitch_max\": ")
                    .append(number(sound.pitchMax()));
        } else {
            json.append(", \"pitch\": ").append(number(sound.pitchMin()));
        }
        json.append(", \"loop\": ").append(sound.loop());
        if (!sound.emitter() && !sound.loop()) {
            json.append(", \"chance\": ")
                    .append(sound.chance())
                    .append(", \"start_time\": ")
                    .append(number(sound.startTime()));
        }
        json.append(", \"when\": ").append(toJsonString(sound.when()));
        if (!sound.emitter() && RANGE_TIME.equals(sound.when())) {
            json.append(", \"hours\": [");
            var first = true;
            for (var window : sound.hours().windows()) {
                json.append(first ? "{\"start\": " : ", {\"start\": ")
                        .append(number(window.start()))
                        .append(", \"end\": ")
                        .append(number(window.end()))
                        .append('}');
                first = false;
            }
            json.append(']');
        }
        if (sound.emitter()) {
            json.append(", \"delay_seconds\": ")
                    .append(number(sound.delaySeconds()))
                    .append(", \"fade_in_seconds\": ")
                    .append(number(sound.fadeInSeconds()))
                    .append(", \"fade_out_start_seconds\": ")
                    .append(number(sound.fadeOutStartSeconds()))
                    .append(", \"fade_out_seconds\": ")
                    .append(number(sound.fadeOutSeconds()));
        }
        json.append(", \"seconds\": ").append(number(sound.seconds())).append('}');
    }

    static String number(float value) {
        if (!Float.isFinite(value)) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e7f) {
            return Integer.toString((int) value);
        }
        return new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
    }

    private static void header(StringBuilder json, LocationModel model) {
        header(json, model, VERSION);
    }

    private static void header(StringBuilder json, LocationModel model, int version) {
        var origin = model.origin();
        json.append("{\n  \"version\": ")
                .append(version)
                .append(",\n  \"metres_per_unit\": ")
                .append(number(model.metersPerUnit()))
                .append(",\n  \"axes\": ")
                .append(toJsonString(AXES))
                .append(",\n  \"origin\": {\"client_point\": ");
        writeNumbers(json, new float[] {origin[0], origin[1], origin[2]});
        json.append(", \"meaning\": ")
                .append(toJsonString("The client point, in client units, that is (0, 0, 0) in the files. "
                        + LocationExport.INFO + " says which point it is."))
                .append('}');
    }

    private static void writeLamp(StringBuilder json, Lamp lamp, String name, String tile) {
        var linear = new float[] {
            LocationEnvironment.toLinear(lamp.color()[0]),
            LocationEnvironment.toLinear(lamp.color()[1]),
            LocationEnvironment.toLinear(lamp.color()[2])
        };
        var brightest = Math.max(linear[0], Math.max(linear[1], linear[2]));
        var half = lamp.radius() / 2;
        json.append("{\"name\": ")
                .append(toJsonString(name))
                .append(", \"type\": ")
                .append(lamp.direction() == null ? "\"point\"" : "\"spot\"")
                .append(", \"tile\": ")
                .append(toJsonString(tile))
                .append(", \"position\": ");
        writeNumbers(json, lamp.position());
        json.append(", \"color\": ");
        writeNumbers(json, new float[] {linear[0] / brightest, linear[1] / brightest, linear[2] / brightest});
        json.append(", \"strength\": ")
                .append(number((float) ((double) brightest * half * half)))
                .append(", \"radius\": ")
                .append(number(lamp.radius()))
                .append('}');
    }

    private static void writeZone(StringBuilder json, MusicZone zone) {
        json.append('{');
        if (!zone.actor().isEmpty()) {
            json.append("\"actor\": ").append(toJsonString(zone.actor())).append(", ");
        }
        json.append("\"tile\": ")
                .append(toJsonString(zone.tile()))
                .append(", \"music_id\": ")
                .append(zone.musicId())
                .append(", \"tracks\": ");
        writeStrings(json, zone.tracks().stream().map(MusicTrack::file).toList());
        json.append(", \"has_night_music\": ").append(zone.night());
        if (zone.night()) {
            json.append(", \"night_music_id\": ").append(zone.nightMusicId()).append(", \"night_tracks\": ");
            writeStrings(json, zone.nightTracks().stream().map(MusicTrack::file).toList());
        }
        json.append(", \"loop\": ")
                .append(zone.loop())
                .append(", \"force\": ")
                .append(zone.force())
                .append(", \"bounds_min\": ");
        writeNumbers(json, zone.boundsMin());
        json.append(", \"bounds_max\": ");
        writeNumbers(json, zone.boundsMax());
        json.append(", \"root_outside\": ").append(zone.rootOutside()).append(", \"planes\": ");
        writeNumbers(json, zone.planes());
        json.append(", \"front\": ")
                .append(Arrays.toString(zone.front()))
                .append(", \"back\": ")
                .append(Arrays.toString(zone.back()))
                .append(", \"decides\": ")
                .append(Arrays.toString(zone.decides()))
                .append('}');
    }

    private static void writeEffect(StringBuilder json, EffectActor actor) {
        json.append("{\"actor\": ")
                .append(toJsonString(actor.actor()))
                .append(", \"tile\": ")
                .append(toJsonString(actor.tile()))
                .append(", \"position\": ");
        writeNumbers(json, actor.position());
        json.append(", \"rotation\": ");
        writeNumbers(json, actor.rotation());
        json.append(", \"scale\": ");
        writeNumbers(json, actor.scale());
        json.append(", \"nodes\": ");
        writeStrings(json, actor.nodes());
        json.append(", \"emitters\": [");
        for (var index = 0; index < actor.emitters().size(); index++) {
            json.append(index == 0 ? "" : ", ");
            writeEmitter(json, actor.emitters().get(index));
        }
        json.append("]}");
    }

    private static void writeEmitter(StringBuilder json, EffectEmitter emitter) {
        json.append("{\"name\": ")
                .append(toJsonString(emitter.name()))
                .append(", \"order\": ")
                .append(emitter.order())
                .append(", \"type\": ")
                .append(toJsonString(emitter.type()));
        if (!emitter.texture().isEmpty()) {
            json.append(", \"texture\": ").append(toJsonString(emitter.texture()));
        }
        json.append(", \"blend\": ").append(emitter.blend()).append('}');
    }

    private static void writeNumbers(StringBuilder json, float[] values) {
        json.append('[');
        for (var index = 0; index < values.length; index++) {
            json.append(index == 0 ? "" : ", ").append(number(values[index]));
        }
        json.append(']');
    }

    private static void writeStrings(StringBuilder json, List<String> values) {
        json.append('[');
        for (var index = 0; index < values.size(); index++) {
            json.append(index == 0 ? "" : ", ").append(toJsonString(values.get(index)));
        }
        json.append(']');
    }
}

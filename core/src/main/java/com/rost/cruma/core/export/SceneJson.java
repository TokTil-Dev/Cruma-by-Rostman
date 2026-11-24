package com.rost.cruma.core.export;

import static com.rost.cruma.core.export.GlbWriter.toJsonString;
import static com.rost.cruma.core.export.LocationJson.number;

import com.rost.cruma.core.export.SceneTimeline.Actor;
import com.rost.cruma.core.export.SceneTimeline.Door;
import com.rost.cruma.core.export.SceneTimeline.Effect;
import com.rost.cruma.core.export.SceneTimeline.Layer;
import com.rost.cruma.core.export.SceneTimeline.Place;
import com.rost.cruma.core.export.SceneTimeline.Rig;
import com.rost.cruma.core.export.SceneTimeline.Sound;
import com.rost.cruma.core.export.SceneTimeline.Strip;
import com.rost.cruma.core.export.SceneTimeline.View;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class SceneJson {
    static final int VERSION = 1;

    private static final String AXES =
            "The same axes as the .glb, as glTF has them: +X east, +Y up, -Z north, right-handed, in metres.";

    private static final String TURN = "A yaw is in radians about +Y, right-handed. At 0 a body looks along +X.";

    private static final String FOV =
            "The width in degrees of a 4:3 picture. The height of the view stays the same in a wider picture.";

    private static final String LOBBY_FOV = "The width in degrees of the picture itself, whatever its shape, as the"
            + " game draws a map that it shows alone, such as its lobby.";

    private static final String PLACE =
            "An actor's place is its pawn's: the floor plus its collision height. Its rig stands lift metres under"
                    + " that place.";

    private static final String LEFT_OUT =
            "The motion blur, as it blends each frame into the last ones. An effect is only a mark, as glTF has no"
                    + " form for particles.";

    private final StringBuilder json = new StringBuilder(1 << 16);
    private final float meters;
    private final float[] origin;
    private final boolean lobby;

    private SceneJson(float meters, float[] origin, boolean lobby) {
        this.meters = meters;
        this.origin = origin;
        this.lobby = lobby;
    }

    static String text(
            SceneTimeline timeline,
            Map<Rig, String> rigs,
            Map<Sound, String> sounds,
            float[] origin,
            ExportScale scale) {
        return text(timeline, rigs, sounds, Map.of(), origin, scale);
    }

    static String text(
            SceneTimeline timeline,
            Map<Rig, String> rigs,
            Map<Sound, String> sounds,
            Map<Layer, String> pictures,
            float[] origin,
            ExportScale scale) {
        return text(timeline, rigs, sounds, pictures, origin, scale, false);
    }

    static String text(
            SceneTimeline timeline,
            Map<Rig, String> rigs,
            Map<Sound, String> sounds,
            Map<Layer, String> pictures,
            float[] origin,
            ExportScale scale,
            boolean lobby) {
        return new SceneJson(scale.metersPerUnit(), origin, lobby).write(timeline, rigs, sounds, pictures);
    }

    private String write(
            SceneTimeline timeline, Map<Rig, String> rigs, Map<Sound, String> sounds, Map<Layer, String> pictures) {
        json.append("{\n  \"version\": ")
                .append(VERSION)
                .append(",\n  \"scene\": ")
                .append(toJsonString(timeline.name()))
                .append(",\n  \"metres_per_unit\": ")
                .append(number(meters))
                .append(",\n  \"axes\": ")
                .append(toJsonString(AXES))
                .append(",\n  \"turns\": ")
                .append(toJsonString(TURN))
                .append(",\n  \"places\": ")
                .append(toJsonString(PLACE))
                .append(",\n  \"origin\": {\"client_point\": [")
                .append(number(origin[0]))
                .append(", ")
                .append(number(origin[1]))
                .append(", ")
                .append(number(origin[2]))
                .append("]}")
                .append(",\n  \"left_out\": ")
                .append(toJsonString(LEFT_OUT))
                .append(",\n  \"frames_per_second\": ")
                .append(timeline.rate())
                .append(",\n  \"seconds\": ")
                .append(time(timeline.seconds()))
                .append(",\n  \"camera\": {\"lens\": ")
                .append(toJsonString(lobby ? "lobby" : "world"))
                .append(", \"fov\": ")
                .append(toJsonString(lobby ? LOBBY_FOV : FOV))
                .append(", \"columns\": [\"seconds\", \"x\", \"y\", \"z\", \"look_x\", \"look_y\", \"look_z\","
                        + " \"up_x\", \"up_y\", \"up_z\", \"fov\"], \"keys\": [");
        var first = true;
        for (var view : timeline.views()) {
            json.append(first ? "\n    " : ",\n    ");
            writeView(view);
            first = false;
        }
        json.append(first ? "]}" : "\n  ]}").append(",\n  \"actors\": [");
        first = true;
        for (var actor : timeline.actors()) {
            json.append(first ? "\n    " : ",\n    ");
            writeActor(actor, rigs);
            first = false;
        }
        json.append(first ? "]" : "\n  ]").append(",\n  \"sounds\": [");
        first = true;
        for (var sound : timeline.sounds()) {
            var file = sounds.get(sound);
            if (file != null) {
                json.append(first ? "\n    " : ",\n    ");
                writeSound(sound, file);
                first = false;
            }
        }
        json.append(first ? "]" : "\n  ]").append(",\n  \"effects\": [");
        first = true;
        for (var effect : timeline.effects()) {
            json.append(first ? "\n    " : ",\n    ");
            writeEffect(effect);
            first = false;
        }
        json.append(first ? "]" : "\n  ]")
                .append(",\n"
                        + "  \"screen\": {\"columns\": [\"seconds\", \"x\", \"y\", \"width\", \"height\","
                        + " \"alpha\"], \"layers\": [");
        first = true;
        for (var layer : timeline.layers()) {
            json.append(first ? "\n    " : ",\n    ");
            writeLayer(layer, pictures.get(layer));
            first = false;
        }
        json.append(first ? "]}" : "\n  ]}")
                .append(",\n"
                        + "  \"grades\": {\"columns\": [\"seconds\", \"luma\", \"chroma\", \"blue\", \"red\"],"
                        + " \"keys\": [");
        first = true;
        for (var grade : timeline.grades()) {
            json.append(first ? "\n    " : ",\n    ")
                    .append('[')
                    .append(time(grade.seconds()))
                    .append(", ")
                    .append(number(grade.luma()))
                    .append(", ")
                    .append(number(grade.chroma()))
                    .append(", ")
                    .append(number(grade.blue()))
                    .append(", ")
                    .append(number(grade.red()))
                    .append(']');
            first = false;
        }
        json.append(first ? "]}" : "\n  ]}").append(",\n  \"doors\": [");
        first = true;
        for (var door : timeline.doors()) {
            json.append(first ? "\n    " : ",\n    ");
            writeDoor(door);
            first = false;
        }
        return json.append(first ? "]\n}\n" : "\n  ]\n}\n").toString();
    }

    private void writeDoor(Door door) {
        json.append("{\"actor\": ").append(toJsonString(door.actor())).append(", \"keys\": [");
        var first = true;
        for (var key : door.keys()) {
            json.append(first ? "[" : ", [").append(time(key.seconds())).append(", ");
            writeTurned(key.x() * meters, key.y() * meters, key.z() * meters);
            json.append(']');
            first = false;
        }
        json.append("]}");
    }

    private void writeLayer(Layer layer, @Nullable String picture) {
        json.append("{\"name\": ")
                .append(toJsonString(layer.name()))
                .append(", \"space\": ")
                .append(toJsonString(layer.space().name().toLowerCase(Locale.ROOT)))
                .append(", \"color\": [")
                .append(layer.color() >> 16 & 0xFF)
                .append(", ")
                .append(layer.color() >> 8 & 0xFF)
                .append(", ")
                .append(layer.color() & 0xFF)
                .append(']');
        if (picture != null) {
            json.append(", \"picture\": ").append(toJsonString(SceneExport.SCREEN + "/" + picture));
        }
        json.append(", \"keys\": [");
        var first = true;
        for (var key : layer.keys()) {
            json.append(first ? "[" : ", [")
                    .append(time(key.seconds()))
                    .append(", ")
                    .append(number(key.x()))
                    .append(", ")
                    .append(number(key.y()))
                    .append(", ")
                    .append(number(key.width()))
                    .append(", ")
                    .append(number(key.height()))
                    .append(", ")
                    .append(number(key.alpha()))
                    .append(']');
            first = false;
        }
        json.append("]}");
    }

    private void writeView(View view) {
        json.append('[').append(time(view.seconds())).append(", ");
        writePoint(view.x(), view.y(), view.z());
        json.append(", ");
        writeTurned(view.lookX(), view.lookY(), view.lookZ());
        json.append(", ");
        writeTurned(view.upX(), view.upY(), view.upZ());
        json.append(", ").append(number(view.fov())).append(']');
    }

    private void writeActor(Actor actor, Map<Rig, String> rigs) {
        json.append("{\"name\": ").append(toJsonString(actor.name()));
        var rig = actor.rig() == null ? null : rigs.get(actor.rig());
        if (rig != null) {
            json.append(", \"rig\": ").append(toJsonString(LocationExport.NPCS + "/" + rig));
        }
        json.append(", \"lift\": ")
                .append(number(actor.lift() * meters))
                .append(", \"enters\": ")
                .append(time(actor.enters()))
                .append(", \"leaves\": ")
                .append(time(actor.leaves()))
                .append(", \"keys\": ");
        writePlaces(actor.places());
        json.append(", \"strips\": [");
        var first = true;
        for (var strip : actor.strips()) {
            json.append(first ? "" : ", ");
            writeStrip(strip);
            first = false;
        }
        json.append("]}");
    }

    private void writeStrip(Strip strip) {
        json.append("{\"sequence\": ")
                .append(toJsonString(strip.sequence()))
                .append(", \"start\": ")
                .append(time(strip.start()))
                .append(", \"end\": ")
                .append(time(strip.end()))
                .append(", \"from\": ")
                .append(time(strip.from()))
                .append(", \"to\": ")
                .append(time(strip.to()))
                .append(", \"repeat\": ")
                .append(time(strip.repeat()))
                .append('}');
    }

    private void writeSound(Sound sound, String file) {
        json.append("{\"kind\": ")
                .append(toJsonString(sound.kind().name().toLowerCase(Locale.ROOT)))
                .append(", \"name\": ")
                .append(toJsonString(sound.name()))
                .append(", \"file\": ")
                .append(toJsonString(SceneExport.SOUNDS + "/" + file))
                .append(", \"start\": ")
                .append(time(sound.start()))
                .append(", \"end\": ")
                .append(time(sound.end()))
                .append(", \"volume\": ")
                .append(number(sound.volume()))
                .append(", \"fade_in\": ")
                .append(number(sound.fadeIn()))
                .append(", \"fade_out\": ")
                .append(number(sound.fadeOut()));
        if (!sound.levels().isEmpty()) {
            json.append(", \"levels\": [");
            var first = true;
            for (var level : sound.levels()) {
                json.append(first ? "[" : ", [")
                        .append(time(level.seconds()))
                        .append(", ")
                        .append(number(level.volume()))
                        .append(", ")
                        .append(number(level.pan()))
                        .append(']');
                first = false;
            }
            json.append(']');
        }
        json.append('}');
    }

    private void writeEffect(Effect effect) {
        json.append("{\"name\": ")
                .append(toJsonString(effect.name()))
                .append(", \"start\": ")
                .append(time(effect.start()));
        if (!Double.isNaN(effect.end())) {
            json.append(", \"end\": ").append(time(effect.end()));
        }
        json.append(", \"keys\": ");
        writePlaces(effect.places());
        json.append('}');
    }

    private void writePlaces(List<Place> places) {
        json.append('[');
        var first = true;
        for (var place : places) {
            json.append(first ? "[" : ", [").append(time(place.seconds())).append(", ");
            writePoint(place.x(), place.y(), place.z());
            json.append(", ").append(number(-place.yaw())).append(']');
            first = false;
        }
        json.append(']');
    }

    private void writePoint(float x, float y, float z) {
        json.append(number((x - origin[0]) * meters))
                .append(", ")
                .append(number((z - origin[2]) * meters))
                .append(", ")
                .append(number((y - origin[1]) * meters));
    }

    private void writeTurned(float x, float y, float z) {
        json.append(number(x)).append(", ").append(number(z)).append(", ").append(number(y));
    }

    private static String time(double seconds) {
        return number((float) seconds);
    }
}

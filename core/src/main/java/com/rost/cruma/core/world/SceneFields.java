package com.rost.cruma.core.world;

import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Camera;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import com.rost.cruma.core.world.GameScenes.Visual;
import java.util.List;
import java.util.function.Function;

public final class SceneFields<R> {
    public static final SceneFields<Camera> CAMERA = new SceneFields<>(
            value -> new Camera(
                    (int) value[0],
                    (int) value[1],
                    (double) value[2],
                    (int) value[3],
                    (int) value[4],
                    (double) value[5],
                    (double) value[6],
                    (int) value[7],
                    (int) value[8],
                    (int) value[9],
                    (boolean) value[10],
                    (boolean) value[11],
                    (boolean) value[12],
                    (double) value[13]),
            List.of(
                    field("mode", Kind.WHOLE, Camera::mode),
                    field("target", Kind.WHOLE, Camera::target),
                    field("distance", Kind.NUMBER, Camera::distance),
                    field("yaw", Kind.WHOLE, Camera::yaw),
                    field("pitch", Kind.WHOLE, Camera::pitch),
                    field("moveSeconds", Kind.NUMBER, Camera::moveSeconds),
                    field("holdSeconds", Kind.NUMBER, Camera::holdSeconds),
                    field("relYaw", Kind.WHOLE, Camera::relYaw),
                    field("relPitch", Kind.WHOLE, Camera::relPitch),
                    field("relRoll", Kind.WHOLE, Camera::relRoll),
                    field("wide", Kind.SWITCH, Camera::wide),
                    field("relative", Kind.SWITCH, Camera::relative),
                    field("immediate", Kind.SWITCH, Camera::immediate),
                    field("fov", Kind.NUMBER, Camera::fov)));

    public static final SceneFields<Screen> SCREEN = new SceneFields<>(
            value -> new Screen(
                    (ScreenKind) value[0],
                    (double) value[1],
                    (double) value[2],
                    (double) value[3],
                    (int) value[4],
                    (int) value[5],
                    (int) value[6],
                    (int) value[7],
                    (String) value[8],
                    (String) value[9],
                    (int) value[10],
                    (double) value[11],
                    (int) value[12],
                    (int) value[13],
                    (int) value[14],
                    (int) value[15],
                    (int) value[16],
                    (int) value[17],
                    (int) value[18],
                    (int) value[19],
                    (int) value[20]),
            List.of(
                    field("kind", Kind.SCREEN_KIND, Screen::kind),
                    field("fadeOut", Kind.NUMBER, Screen::fadeOut),
                    field("fadeIn", Kind.NUMBER, Screen::fadeIn),
                    field("hold", Kind.NUMBER, Screen::hold),
                    field("color", Kind.WHOLE, Screen::color),
                    field("window", Kind.WHOLE, Screen::window),
                    field("animation", Kind.WHOLE, Screen::animation),
                    field("message", Kind.WHOLE, Screen::message),
                    field("text", Kind.TEXT, Screen::text),
                    field("slide", Kind.TEXT, Screen::slide),
                    field("slideDirection", Kind.WHOLE, Screen::slideDirection),
                    field("slideSeconds", Kind.NUMBER, Screen::slideSeconds),
                    field("slideWidth", Kind.WHOLE, Screen::slideWidth),
                    field("slideHeight", Kind.WHOLE, Screen::slideHeight),
                    field("slideMove", Kind.WHOLE, Screen::slideMove),
                    field("slideWindow", Kind.WHOLE, Screen::slideWindow),
                    field("postEffect", Kind.WHOLE, Screen::postEffect),
                    field("delayed", Kind.WHOLE, Screen::delayed),
                    field("font", Kind.WHOLE, Screen::font),
                    field("background", Kind.WHOLE, Screen::background),
                    field("colorAlpha", Kind.WHOLE, Screen::colorAlpha)));

    public static final SceneFields<Music> MUSIC = new SceneFields<>(
            value -> new Music(
                    (String) value[0],
                    (boolean) value[1],
                    (boolean) value[2],
                    (boolean) value[3],
                    (boolean) value[4],
                    (boolean) value[5],
                    (double) value[6],
                    (double) value[7],
                    (int) value[8],
                    (double) value[9]),
            List.of(
                    field("name", Kind.TEXT, Music::name),
                    field("stopMusic", Kind.SWITCH, Music::stopMusic),
                    field("stopAmbient", Kind.SWITCH, Music::stopAmbient),
                    field("stopEffects", Kind.SWITCH, Music::stopEffects),
                    field("sound", Kind.SWITCH, Music::sound),
                    field("voice", Kind.SWITCH, Music::voice),
                    field("volume", Kind.NUMBER, Music::volume),
                    field("fadeSeconds", Kind.NUMBER, Music::fadeSeconds),
                    field("level", Kind.WHOLE, Music::level),
                    field("fadeOutSeconds", Kind.NUMBER, Music::fadeOutSeconds)));

    public static final SceneFields<Actor> ACTOR = new SceneFields<>(
            SceneFields::actor,
            List.of(
                    field("player", Kind.SWITCH, Actor::player),
                    field("action", Kind.ACTION, Actor::action),
                    field("npc", Kind.WHOLE, Actor::npc),
                    field("index", Kind.WHOLE, Actor::index),
                    field("target", Kind.WHOLE, Actor::target),
                    field("x", Kind.NUMBER, Actor::x),
                    field("y", Kind.NUMBER, Actor::y),
                    field("z", Kind.NUMBER, Actor::z),
                    field("yaw", Kind.WHOLE, Actor::yaw),
                    field("skill", Kind.WHOLE, Actor::skill),
                    field("animation", Kind.TEXT, Actor::animation),
                    field("fly", Kind.SWITCH, Actor::fly),
                    field("run", Kind.SWITCH, Actor::run),
                    field("loop", Kind.SWITCH, Actor::loop),
                    field("self", Kind.SWITCH, Actor::self),
                    field("race", Kind.WHOLE, Actor::race),
                    field("sex", Kind.WHOLE, Actor::sex),
                    field("mage", Kind.SWITCH, Actor::mage),
                    field("spirit", Kind.SWITCH, Actor::spirit),
                    field("setItem", Kind.WHOLE, Actor::setItem),
                    field("setItemType", Kind.WHOLE, Actor::setItemType),
                    field("visuals", Kind.VISUALS, Actor::visuals),
                    field("description", Kind.TEXT, Actor::description),
                    field("immediate", Kind.SWITCH, Actor::immediate),
                    field("itemSlots", Kind.SLOTS, Actor::itemSlots),
                    field("speed", Kind.NUMBER, Actor::speed),
                    field("moveSequence", Kind.TEXT, Actor::moveSequence),
                    field("present", Kind.SWITCH, Actor::present),
                    field("dies", Kind.SWITCH, Actor::dies),
                    field("since", Kind.NUMBER, Actor::since),
                    field("fights", Kind.SWITCH, Actor::fights),
                    field("watches", Kind.WHOLE, Actor::watches)));

    private final Function<Object[], R> maker;
    private final List<Field<R>> fields;

    private SceneFields(Function<Object[], R> maker, List<Field<R>> fields) {
        this.maker = maker;
        this.fields = fields;
    }

    public enum Kind {
        WHOLE,
        NUMBER,
        SWITCH,
        TEXT,
        ACTION,
        SCREEN_KIND,
        VISUALS,
        SLOTS
    }

    public record Field<R>(String name, Kind kind, Function<R, Object> reader) {}

    public List<Field<R>> getFields() {
        return fields;
    }

    public R make(Object[] values) {
        return maker.apply(values);
    }

    public R with(R record, String name, Object value) {
        var values = new Object[fields.size()];
        var found = false;
        for (var index = 0; index < values.length; index++) {
            var field = fields.get(index);
            if (field.name().equals(name)) {
                values[index] = value;
                found = true;
            } else {
                values[index] = field.reader().apply(record);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("No field " + name);
        }
        return make(values);
    }

    private static <R> Field<R> field(String name, Kind kind, Function<R, Object> reader) {
        return new Field<>(name, kind, reader);
    }

    @SuppressWarnings("unchecked")
    private static Actor actor(Object[] value) {
        return new Actor(
                (boolean) value[0],
                (Action) value[1],
                (int) value[2],
                (int) value[3],
                (int) value[4],
                (double) value[5],
                (double) value[6],
                (double) value[7],
                (int) value[8],
                (int) value[9],
                (String) value[10],
                (boolean) value[11],
                (boolean) value[12],
                (boolean) value[13],
                (boolean) value[14],
                (int) value[15],
                (int) value[16],
                (boolean) value[17],
                (boolean) value[18],
                (int) value[19],
                (int) value[20],
                (List<Visual>) value[21],
                (String) value[22],
                (boolean) value[23],
                (List<Integer>) value[24],
                (double) value[25],
                (String) value[26],
                (boolean) value[27],
                (boolean) value[28],
                (double) value[29],
                (boolean) value[30],
                (int) value[31]);
    }
}

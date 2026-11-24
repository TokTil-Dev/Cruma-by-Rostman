package com.rost.cruma.viewer.agent.effect;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.EffectEditSession;
import com.rost.cruma.core.editing.EffectEditSession.ActionInfo;
import com.rost.cruma.core.editing.EffectEditSession.EditProblem;
import com.rost.cruma.core.editing.EffectEditSession.EmitterInfo;
import com.rost.cruma.core.editing.EffectEditSession.PropertyInfo;
import com.rost.cruma.core.editing.EffectEdits;
import com.rost.cruma.core.editing.EffectEdits.ActionEdit;
import com.rost.cruma.core.editing.EffectEdits.AddedAction;
import com.rost.cruma.core.editing.EffectEdits.Kind;
import com.rost.cruma.core.editing.EffectEdits.Knobs;
import com.rost.cruma.core.editing.EffectEdits.PropertyEdit;
import com.rost.cruma.core.unreal.SkillEffects.Action;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.editing.EffectEditController;
import com.rost.cruma.viewer.editing.SkillActions;
import com.rost.cruma.viewer.ui.SkillCasting;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class EffectChanges {
    static final List<String> EFFECT_FIELDS = List.of(
            "size",
            "spread",
            "speed",
            "lifetime",
            "count",
            "opacity",
            "hue",
            "colour",
            "reset",
            "on",
            "texture",
            "image",
            "from");

    static final List<String> ACTION_FIELDS = List.of(
            "effect", "on", "multi", "place", "bone", "offset", "relative", "scale", "delay", "phase", "flight");

    private EffectChanges() {}

    record Fields(
            @Nullable Double size,
            @Nullable Double spread,
            @Nullable Double speed,
            @Nullable Double lifetime,
            @Nullable Double count,
            @Nullable Double opacity,
            @Nullable Double hue,
            @Nullable List<Double> color,
            boolean reset,
            @Nullable Boolean on,
            @Nullable String texture,
            @Nullable Path image,
            @Nullable String from) {
        boolean knobs() {
            return reset
                    || size != null
                    || spread != null
                    || speed != null
                    || lifetime != null
                    || count != null
                    || opacity != null
                    || hue != null
                    || color != null;
        }

        Knobs apply(Knobs knobs) {
            var next = reset ? Knobs.NONE : knobs;
            next = size == null ? next : next.withSize(size);
            next = spread == null ? next : next.withSpread(spread);
            next = speed == null ? next : next.withSpeed(speed);
            next = lifetime == null ? next : next.withLifetime(lifetime);
            next = count == null ? next : next.withCount(count);
            next = opacity == null ? next : next.withOpacity(opacity);
            next = hue == null ? next : next.withHue(hue);
            return color == null ? next : next.withColor(color.get(0), color.get(1), color.get(2));
        }
    }

    record ActionFields(
            @Nullable String effect,
            @Nullable Boolean onTarget,
            @Nullable Boolean multi,
            @Nullable Integer place,
            @Nullable String bone,
            @Nullable List<Float> offset,
            @Nullable Boolean relative,
            @Nullable Float scale,
            @Nullable Float delay,
            @Nullable Phase phase,
            @Nullable Float flight) {
        boolean action() {
            return effect != null
                    || onTarget != null
                    || multi != null
                    || place != null
                    || bone != null
                    || offset != null
                    || relative != null
                    || scale != null
                    || delay != null;
        }

        ActionEdit apply(ActionEdit edit) {
            var next = effect == null ? edit : edit.withEffectClass(effect);
            next = onTarget == null ? next : next.withSpawnOnTarget(onTarget);
            next = multi == null ? next : next.withOnMultiTarget(multi);
            next = place == null ? next : next.withAttachOn(place);
            next = bone == null ? next : next.withAttachBoneName(bone);
            next = offset == null ? next : next.withOffset(offset);
            next = relative == null ? next : next.withRelativeToCylinder(relative);
            next = scale == null ? next : next.withScaleSize(scale);
            return delay == null ? next : next.withSpawnDelay(delay);
        }
    }

    static Fields fields(@Nullable JSONObject set) throws ToolProblem {
        var args = new ToolArgs(set);
        unknown(args, EFFECT_FIELDS);
        var image = args.getText("image");
        Path file = null;
        if (image != null) {
            try {
                file = Path.of(image);
            } catch (InvalidPathException wrong) {
                throw new ToolProblem("image is not a path: " + wrong.getMessage());
            }
            if (!file.isAbsolute()) {
                throw new ToolProblem("image must be a full path to a PNG, JPEG, BMP or DDS file.");
            }
        }
        return new Fields(
                factor(args, "size"),
                factor(args, "spread"),
                factor(args, "speed"),
                factor(args, "lifetime"),
                factor(args, "count"),
                factor(args, "opacity"),
                within(args, "hue", -EffectEditController.HUE_LIMIT, EffectEditController.HUE_LIMIT),
                color(args),
                args.readFlag("reset", false),
                args.has("on") ? args.readFlag("on", true) : null,
                args.getText("texture"),
                file,
                args.getText("from"));
    }

    static ActionFields actionFields(@Nullable JSONObject set) throws ToolProblem {
        var args = new ToolArgs(set);
        unknown(args, ACTION_FIELDS);
        Boolean onTarget = null;
        var on = args.getText("on");
        if (on != null) {
            onTarget = switch (on.toLowerCase(Locale.ROOT)) {
                case "target" -> true;
                case "caster" -> false;
                default -> throw new ToolProblem("on must be target or caster.");
            };
        }
        var phase = args.getText("phase");
        return new ActionFields(
                args.getText("effect"),
                onTarget,
                args.has("multi") ? args.readFlag("multi", false) : null,
                place(args.getText("place")),
                args.getText("bone"),
                offset(args),
                args.has("relative") ? args.readFlag("relative", true) : null,
                floatWithin(args, "scale", 0, SkillActions.LARGEST_SCALE),
                floatWithin(args, "delay", 0, SkillActions.LONGEST_DELAY),
                phase == null ? null : phase(phase),
                floatWithin(args, "flight", 0, SkillActions.LONGEST_FLIGHT));
    }

    static Phase phase(String words) throws ToolProblem {
        var wanted = simple(words);
        for (var phase : Phase.values()) {
            if (simple(SkillCasting.phaseName(phase)).equals(wanted)
                    || simple(phase.name()).equals(wanted)
                    || simple(phase.getProperty()).equals(wanted)) {
                return phase;
            }
        }
        throw new ToolProblem("phase must be cast, channel, before the shot, shot, hit or linked targets.");
    }

    static List<String> effect(
            EffectEditSession session,
            String path,
            @Nullable String emitterKey,
            Fields fields,
            Map<String, String> properties)
            throws IOException {
        var said = new ArrayList<String>();
        var edits = session.getEdits();
        var edit = edits.classEdit(path);
        if (emitterKey == null) {
            if (!properties.isEmpty() || fields.on() != null || fields.texture() != null || fields.image() != null) {
                throw new IOException(
                        "on, texture, image and properties belong to one emitter. Name it with emitter, from read.");
            }
            if (fields.knobs()) {
                session.setEdits(edits.withClass(path, edit.withKnobs(fields.apply(edit.knobs()))));
                said.add("the fields of the whole effect");
            }
            return said;
        }
        var emitter = emitter(session, path, emitterKey);
        var key = emitter.key();
        var emitterEdit = edit.emitter(key);
        if (fields.knobs()) {
            emitterEdit = emitterEdit.withKnobs(fields.apply(emitterEdit.knobs()));
            said.add("the fields of " + key);
        }
        if (fields.on() != null) {
            if (!fields.on()) {
                emitterEdit = emitterEdit.withDisabled(true);
            } else if (emitterEdit.disabled()) {
                emitterEdit = emitterEdit.withDisabled(false);
            } else if (emitter.disabled()) {
                emitterEdit = emitterEdit.withProperty(PropertyEdit.bool("Disabled", false));
            }
            said.add(key + (fields.on() ? " on" : " off"));
        }
        var objects = new LinkedHashMap<String, String>();
        if (!properties.isEmpty()) {
            var known = new LinkedHashMap<String, PropertyInfo>();
            for (var property : session.getProperties(path, key)) {
                known.put(property.path().toLowerCase(Locale.ROOT), property);
            }
            for (var entry : properties.entrySet()) {
                var property = known.get(entry.getKey().toLowerCase(Locale.ROOT));
                if (property == null) {
                    throw new IOException(
                            key + " has no property " + entry.getKey() + ". read with emitter lists its properties.");
                }
                var value = entry.getValue().strip();
                if (property.kind() == Kind.OBJECT) {
                    objects.put(property.path(), value);
                    continue;
                }
                var problem = EffectEditController.valueProblem(property.kind(), value);
                if (problem != null) {
                    throw new IOException(property.path() + ": " + problem);
                }
                emitterEdit = emitterEdit.withProperty(new PropertyEdit(property.path(), property.kind(), value));
                said.add(property.path() + " " + value);
            }
        }
        session.setEdits(edits.withClass(path, edit.withEmitter(key, emitterEdit)));
        if (fields.texture() != null) {
            objects.put("Texture", fields.texture());
        }
        for (var entry : objects.entrySet()) {
            session.objectEdit(path, key, entry.getKey(), entry.getValue());
            said.add(entry.getKey() + " " + entry.getValue());
        }
        if (fields.image() != null) {
            said.add("Texture " + session.importImage(fields.image(), path, key) + " from "
                    + fields.image().getFileName());
        }
        return said;
    }

    static List<String> action(EffectEditSession session, String skill, @Nullable String actionKey, ActionFields fields)
            throws IOException {
        var said = new ArrayList<String>();
        var edits = session.getEdits();
        var edit = edits.skillEdit(skill);
        if (fields.flight() != null) {
            session.setEdits(EffectEdits.NONE);
            var client = session.previewSkill(skill).flyingTime();
            session.setEdits(edits);
            edit = edit.withFlyingTime(fields.flight() == client ? null : fields.flight());
            said.add("flight " + fields.flight());
        }
        if (actionKey == null) {
            if (fields.action() || fields.phase() != null) {
                throw new IOException("Name the action with key, from read of the skill.");
            }
            session.setEdits(edits.withSkill(skill, edit));
            return said;
        }
        var info = action(session.getActions(skill), actionKey, null);
        var key = info.key();
        if (fields.action()) {
            session.setEdits(EffectEdits.NONE);
            Action client = null;
            for (var candidate : session.getActions(skill)) {
                if (candidate.key().equals(key)) {
                    client = candidate.action();
                }
            }
            session.setEdits(edits);
            edit = edit.withAction(key, SkillActions.withoutClientValues(fields.apply(edit.action(key)), client));
            said.add("the fields of " + key);
        }
        if (fields.phase() != null && fields.phase() != info.phase()) {
            edit = SkillActions.moved(edit, key, info.phase(), fields.phase());
            said.add(key + " to " + SkillCasting.phaseName(fields.phase()));
        }
        session.setEdits(edits.withSkill(skill, edit));
        return said;
    }

    static String addAction(EffectEditSession session, String skill, Phase phase, String effect) throws IOException {
        var edits = session.getEdits();
        var edit = edits.skillEdit(skill);
        var key = SkillActions.freeName(session.getActions(skill), edit, skill);
        var onCaster = phase == Phase.CASTING || phase == Phase.CHANNELING || phase == Phase.PRESHOT;
        var fields = ActionEdit.NONE.withEffectClass(effect).withSpawnOnTarget(!onCaster);
        session.setEdits(edits.withSkill(
                skill, edit.withAdded(new AddedAction(key, phase)).withAction(key, fields)));
        return key;
    }

    static String removeAction(EffectEditSession session, String skill, String actionKey, @Nullable Phase phase)
            throws IOException {
        var info = action(session.getActions(skill), actionKey, phase);
        var edits = session.getEdits();
        session.setEdits(
                edits.withSkill(skill, SkillActions.removed(edits.skillEdit(skill), info.key(), info.phase())));
        return info.key() + " of " + SkillCasting.phaseName(info.phase());
    }

    static String copyForSkill(EffectEditSession session, String skill, String actionKey) throws IOException {
        var info = action(session.getActions(skill), actionKey, null);
        var source = info.action().effect();
        var copy = session.copy(source, session.suggestCopyName(source));
        var edits = session.getEdits();
        var edit = edits.skillEdit(skill);
        session.setEdits(edits.withSkill(
                skill, edit.withAction(info.key(), edit.action(info.key()).withEffectClass(copy))));
        return copy;
    }

    static JSONObject readEffect(
            EffectEditSession session, String path, @Nullable String emitterKey, @Nullable String query, int limit)
            throws IOException {
        var data = new JSONObject();
        var edit = session.getEdits().classEdit(path);
        data.put("effect", path);
        data.put("fields", EffectChanges.write(edit.knobs()));
        if (emitterKey == null) {
            var emitters = new JSONArray();
            for (var info : session.getEmitters(path)) {
                var item = new JSONObject();
                item.put("key", info.key());
                item.put("kind", info.kind());
                if (!info.label().isEmpty() && !info.label().equals(info.key())) {
                    item.put("label", info.label());
                }
                item.put("on", !info.disabled());
                var knobs = EffectChanges.write(edit.emitter(info.key()).knobs());
                if (!knobs.isEmpty()) {
                    item.put("fields", knobs);
                }
                emitters.add(item);
            }
            data.put("emitters", emitters);
            var refusal = session.copyRefusal(path);
            data.put("copy", refusal == null ? "can be copied" : refusal);
            return data;
        }
        var info = EffectChanges.emitter(session, path, emitterKey);
        data.put("emitter", info.key());
        data.put("kind", info.kind());
        data.put("on", !info.disabled());
        data.put("emitter_fields", EffectChanges.write(edit.emitter(info.key()).knobs()));
        var words = query == null
                ? List.<String>of()
                : List.of(query.toLowerCase(Locale.ROOT).split("\\s+"));
        var properties = new JSONArray();
        var more = 0;
        for (var property : session.getProperties(path, info.key())) {
            var name = property.path().toLowerCase(Locale.ROOT);
            if (!words.stream().allMatch(name::contains)) {
                continue;
            }
            if (properties.size() >= limit) {
                more++;
                continue;
            }
            var item = new JSONObject();
            item.put("path", property.path());
            item.put("kind", property.kind().name().toLowerCase(Locale.ROOT));
            item.put("value", property.value());
            properties.add(item);
        }
        data.put("properties", properties);
        if (more > 0) {
            data.put("more", more);
        }
        return data;
    }

    static JSONObject readSkill(EffectEditSession session, String skill) throws IOException {
        var actions = new JSONArray();
        for (var info : session.getActions(skill)) {
            actions.add(write(info));
        }
        var data = new JSONObject();
        data.put("class", skill);
        data.put("flight", session.previewSkill(skill).flyingTime());
        data.put("actions", actions);
        return data;
    }

    static String removeEmitter(EffectEditSession session, String path, String key) throws IOException {
        var emitter = emitter(session, path, key).key();
        session.setEdits(session.getEdits().withoutEmitter(path, emitter));
        return emitter;
    }

    static String removeCopy(EffectEditSession session, String path) throws IOException {
        if (!session.getEdits().copies().containsKey(path)) {
            throw new IOException(path + " is no copy of the edits.");
        }
        session.deleteCopy(path);
        return path;
    }

    static @Nullable EditProblem problemOf(List<EditProblem> problems, String path, boolean skill) {
        for (var problem : problems) {
            if (problem.skill() == skill && problem.path().equals(path)) {
                return problem;
            }
        }
        return null;
    }

    static EmitterInfo emitter(EffectEditSession session, String path, String key) throws IOException {
        var emitters = session.getEmitters(path);
        for (var emitter : emitters) {
            if (emitter.key().equalsIgnoreCase(key)) {
                return emitter;
            }
        }
        var keys = new ArrayList<String>();
        for (var emitter : emitters) {
            keys.add(emitter.key());
        }
        throw new IOException(path + " has no emitter " + key + ". Its emitters: " + keys + ".");
    }

    static ActionInfo action(List<ActionInfo> actions, String key, @Nullable Phase phase) throws IOException {
        for (var info : actions) {
            if (info.key().equalsIgnoreCase(key) && (phase == null || info.phase() == phase)) {
                return info;
            }
        }
        var keys = new ArrayList<String>();
        for (var info : actions) {
            keys.add(info.key());
        }
        throw new IOException("The skill has no action "
                + key
                + (phase == null ? "" : " in " + SkillCasting.phaseName(phase))
                + ". Its actions: "
                + keys
                + ".");
    }

    static JSONObject write(ActionInfo info) {
        var action = info.action();
        var data = new JSONObject();
        data.put("key", info.key());
        data.put("phase", SkillCasting.phaseName(info.phase()));
        data.put("effect", action.effect());
        data.put("on", action.onTarget() ? "target" : "caster");
        if (action.onMultiTarget()) {
            data.put("multi", true);
        }
        data.put("place", SkillActions.attachName(action.attachOn()));
        if (!action.bone().isEmpty()) {
            data.put("bone", action.bone());
        }
        var offset = action.offset();
        if (offset.getX() != 0 || offset.getY() != 0 || offset.getZ() != 0) {
            data.put("offset", JSONArray.of(offset.getX(), offset.getY(), offset.getZ()));
        }
        if (!action.relativeToCylinder()) {
            data.put("relative", false);
        }
        if (action.scale() != 0) {
            data.put("scale", action.scale());
        }
        data.put("delay", action.delay());
        return data;
    }

    static JSONObject write(Knobs knobs) {
        var data = new JSONObject();
        var none = Knobs.NONE;
        put(data, "size", knobs.size(), none.size());
        put(data, "spread", knobs.spread(), none.spread());
        put(data, "speed", knobs.speed(), none.speed());
        put(data, "lifetime", knobs.lifetime(), none.lifetime());
        put(data, "count", knobs.count(), none.count());
        put(data, "opacity", knobs.opacity(), none.opacity());
        put(data, "hue", knobs.hue(), none.hue());
        if (knobs.red() != 1 || knobs.green() != 1 || knobs.blue() != 1) {
            data.put("colour", JSONArray.of(knobs.red(), knobs.green(), knobs.blue()));
        }
        return data;
    }

    private static void put(JSONObject data, String name, double value, double none) {
        if (value != none) {
            data.put(name, value);
        }
    }

    private static void unknown(ToolArgs args, List<String> fields) throws ToolProblem {
        for (var name : args.getJson().keySet()) {
            if (!fields.contains(name)) {
                throw new ToolProblem(
                        "set has no field " + name + " here. It takes " + String.join(", ", fields) + ".");
            }
        }
    }

    private static @Nullable Double factor(ToolArgs args, String name) throws ToolProblem {
        return within(args, name, EffectEditController.FACTOR_MIN, EffectEditController.FACTOR_MAX);
    }

    private static @Nullable Double within(ToolArgs args, String name, double low, double high) throws ToolProblem {
        var value = args.getNumber(name);
        if (value != null && (value < low || value > high)) {
            throw new ToolProblem(name + " must be from " + low + " to " + high + ".");
        }
        return value;
    }

    private static @Nullable Float floatWithin(ToolArgs args, String name, float low, float high) throws ToolProblem {
        var value = within(args, name, low, high);
        return value == null ? null : value.floatValue();
    }

    private static @Nullable List<Double> color(ToolArgs args) throws ToolProblem {
        var values = numbers(args, "colour");
        if (values == null) {
            return null;
        }
        for (var value : values) {
            if (value < 0 || value > 1) {
                throw new ToolProblem(
                        "colour is red, green and blue, each from 0 to 1, such as [1, 0.5, 0.5]. It can only darken.");
            }
        }
        return values;
    }

    private static @Nullable List<Float> offset(ToolArgs args) throws ToolProblem {
        var values = numbers(args, "offset");
        if (values == null) {
            return null;
        }
        var result = new ArrayList<Float>();
        for (var value : values) {
            if (Math.abs(value) > SkillActions.FURTHEST_OFFSET) {
                throw new ToolProblem("offset must stay within " + SkillActions.FURTHEST_OFFSET + ".");
            }
            result.add(value.floatValue());
        }
        return result;
    }

    private static @Nullable List<Double> numbers(ToolArgs args, String name) throws ToolProblem {
        if (!args.has(name)) {
            return null;
        }
        var result = new ArrayList<Double>();
        try {
            for (var item : args.list(name)) {
                result.add(Double.valueOf(item));
            }
        } catch (NumberFormatException wrong) {
            result.clear();
        }
        if (result.size() != 3) {
            throw new ToolProblem(name + " must be three numbers, such as [0, 0, -1].");
        }
        return result;
    }

    private static @Nullable Integer place(@Nullable String words) throws ToolProblem {
        if (words == null) {
            return null;
        }
        var wanted = simple(words);
        for (var point : SkillActions.ATTACH_POINTS) {
            if (simple(point.name()).equals(wanted)) {
                return point.value();
            }
        }
        var names = new ArrayList<String>();
        for (var point : SkillActions.ATTACH_POINTS) {
            names.add(point.name());
        }
        if (wanted.matches("\\d+")) {
            return Integer.valueOf(wanted);
        }
        throw new ToolProblem("place must be one of " + String.join(", ", names)
                + ", or a number of AttachOn. Right foot and Left foot are only in the skill.usk of Interlude.");
    }

    private static String simple(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s_]", "");
    }
}

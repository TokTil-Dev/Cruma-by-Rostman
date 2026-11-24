package com.rost.cruma.core.editing;

import com.rost.cruma.core.unreal.EffectClasses;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

public record EffectEdits(
        Map<String, ClassEdit> classes,
        Map<String, Copy> copies,
        Map<String, SkillEdit> skills,
        Map<String, String> textures) {
    public static final String CUSTOM_PACKAGE = "LineageEffect_Custom";

    public static final EffectEdits NONE = new EffectEdits(Map.of(), Map.of(), Map.of(), Map.of());

    public EffectEdits {
        classes = copyOf(classes);
        copies = copyOf(copies);
        skills = copyOf(skills);
        textures = copyOf(textures == null ? Map.of() : textures);
    }

    public static String texturePath(String name) {
        return EffectClasses.CUSTOM_TEXTURES + "." + name;
    }

    public ClassEdit classEdit(String classPath) {
        return classes.getOrDefault(classPath, ClassEdit.NONE);
    }

    public SkillEdit skillEdit(String className) {
        return skills.getOrDefault(className, SkillEdit.NONE);
    }

    public @Nullable String textureName(String objectPath) {
        var prefix = texturePath("");
        if (!objectPath.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        var name = objectPath.substring(prefix.length());
        for (var key : textures.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    public Map<String, String> usedTextures() {
        var used = new LinkedHashMap<String, String>();
        for (var edit : classes.values()) {
            for (var emitter : edit.emitters().values()) {
                for (var property : emitter.properties()) {
                    var name = property.kind() == Kind.OBJECT ? textureName(property.value()) : null;
                    if (name != null) {
                        used.put(name, textures.get(name));
                    }
                }
            }
        }
        return used;
    }

    public EffectEdits withClass(String classPath, ClassEdit edit) {
        var result = new LinkedHashMap<>(classes);
        if (edit.isEmpty()) {
            result.remove(classPath);
        } else {
            result.put(classPath, edit);
        }
        return new EffectEdits(result, copies, skills, textures);
    }

    public EffectEdits withSkill(String className, SkillEdit edit) {
        var result = new LinkedHashMap<>(skills);
        if (edit.isEmpty()) {
            result.remove(className);
        } else {
            result.put(className, edit);
        }
        return new EffectEdits(classes, copies, result, textures);
    }

    public EffectEdits withCopy(Copy copy) {
        var result = new LinkedHashMap<>(copies);
        result.put(copy.path(), copy);
        return new EffectEdits(classes, result, skills, textures);
    }

    public EffectEdits withCopyRenamed(String copyPath, String newName) {
        var old = copies.get(copyPath);
        if (old == null) {
            return this;
        }
        var renamed = new Copy(newName, old.source());
        var path = renamed.path();
        var newCopies = new LinkedHashMap<String, Copy>();
        for (var entry : copies.entrySet()) {
            if (entry.getKey().equals(copyPath)) {
                newCopies.put(path, renamed);
            } else {
                newCopies.put(entry.getKey(), entry.getValue());
            }
        }
        var newClasses = new LinkedHashMap<String, ClassEdit>();
        for (var entry : classes.entrySet()) {
            var edit = entry.getValue();
            var added = new ArrayList<AddedEmitter>(edit.added().size());
            for (var emitter : edit.added()) {
                added.add(
                        emitter.fromClass().equalsIgnoreCase(copyPath)
                                ? new AddedEmitter(emitter.name(), path, emitter.fromEmitter())
                                : emitter);
            }
            newClasses.put(
                    entry.getKey().equals(copyPath) ? path : entry.getKey(),
                    new ClassEdit(edit.knobs(), edit.emitters(), edit.removed(), added));
        }
        var newSkills = new LinkedHashMap<String, SkillEdit>();
        for (var entry : skills.entrySet()) {
            var skill = entry.getValue();
            var actions = new LinkedHashMap<String, ActionEdit>();
            for (var action : skill.actions().entrySet()) {
                var fields = action.getValue();
                var plays = fields.effectClass() != null && fields.effectClass().equalsIgnoreCase(copyPath);
                actions.put(action.getKey(), plays ? fields.withEffectClass(path) : fields);
            }
            newSkills.put(
                    entry.getKey(),
                    new SkillEdit(skill.flyingTime(), actions, skill.added(), skill.removed(), skill.moved()));
        }
        return new EffectEdits(newClasses, newCopies, newSkills, textures);
    }

    public EffectEdits withTexture(String name, String file) {
        var result = new LinkedHashMap<>(textures);
        result.put(name, file);
        return new EffectEdits(classes, copies, skills, result);
    }

    public EffectEdits withoutCopy(String copyPath) {
        var kept = detached(added -> added.fromClass().equalsIgnoreCase(copyPath));
        var keptCopies = new LinkedHashMap<>(copies);
        keptCopies.remove(copyPath);
        var keptClasses = new LinkedHashMap<>(kept.classes);
        keptClasses.remove(copyPath);
        return new EffectEdits(keptClasses, keptCopies, skills, textures);
    }

    public EffectEdits withoutEmitter(String classPath, String key) {
        var kept = detached(added -> added.fromClass().equalsIgnoreCase(classPath)
                && added.fromEmitter().equalsIgnoreCase(key));
        return kept.withClass(classPath, kept.classEdit(classPath).withRemoved(key));
    }

    public EffectEdits withoutEdit(String classPath) {
        if (copies.containsKey(classPath)) {
            return withoutCopy(classPath);
        }
        return detached(added -> added.fromClass().equalsIgnoreCase(classPath)).withClass(classPath, ClassEdit.NONE);
    }

    public @Nullable EmitterRef origin(String classPath, String key) {
        var seen = new HashSet<String>();
        var path = classPath;
        var name = key;
        while (seen.add((path + "|" + name).toLowerCase(Locale.ROOT))) {
            var added = classEdit(path).added(name);
            if (added == null) {
                var copy = copies.get(path);
                return new EmitterRef(copy == null ? path : copy.source(), name);
            }
            path = added.fromClass();
            name = added.fromEmitter();
        }
        return null;
    }

    private EffectEdits detached(Predicate<AddedEmitter> depends) {
        var result = new LinkedHashMap<String, ClassEdit>();
        for (var entry : classes.entrySet()) {
            var edit = entry.getValue();
            var added = new ArrayList<AddedEmitter>(edit.added().size());
            for (var emitter : edit.added()) {
                var origin = depends.test(emitter) ? origin(emitter.fromClass(), emitter.fromEmitter()) : null;
                added.add(
                        origin == null ? emitter : new AddedEmitter(emitter.name(), origin.classPath(), origin.key()));
            }
            result.put(entry.getKey(), new ClassEdit(edit.knobs(), edit.emitters(), edit.removed(), added));
        }
        return new EffectEdits(result, copies, skills, textures);
    }

    private static <T> Map<String, T> copyOf(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record Copy(String name, String source) {
        public String path() {
            return CUSTOM_PACKAGE + "." + name;
        }
    }

    public record Knobs(
            double size,
            double spread,
            double speed,
            double lifetime,
            double count,
            double opacity,
            double red,
            double green,
            double blue,
            double hue) {
        public static final Knobs NONE = new Knobs(1, 1, 1, 1, 1, 1, 1, 1, 1, 0);

        public boolean isNone() {
            return equals(NONE);
        }

        public Knobs times(Knobs other) {
            return new Knobs(
                    size * other.size,
                    spread * other.spread,
                    speed * other.speed,
                    lifetime * other.lifetime,
                    count * other.count,
                    opacity * other.opacity,
                    red * other.red,
                    green * other.green,
                    blue * other.blue,
                    hue + other.hue);
        }

        public Knobs withSize(double value) {
            return new Knobs(value, spread, speed, lifetime, count, opacity, red, green, blue, hue);
        }

        public Knobs withSpread(double value) {
            return new Knobs(size, value, speed, lifetime, count, opacity, red, green, blue, hue);
        }

        public Knobs withSpeed(double value) {
            return new Knobs(size, spread, value, lifetime, count, opacity, red, green, blue, hue);
        }

        public Knobs withLifetime(double value) {
            return new Knobs(size, spread, speed, value, count, opacity, red, green, blue, hue);
        }

        public Knobs withCount(double value) {
            return new Knobs(size, spread, speed, lifetime, value, opacity, red, green, blue, hue);
        }

        public Knobs withOpacity(double value) {
            return new Knobs(size, spread, speed, lifetime, count, value, red, green, blue, hue);
        }

        public Knobs withColor(double r, double g, double b) {
            return new Knobs(size, spread, speed, lifetime, count, opacity, r, g, b, hue);
        }

        public Knobs withHue(double degrees) {
            return new Knobs(size, spread, speed, lifetime, count, opacity, red, green, blue, degrees);
        }
    }

    public record ClassEdit(
            Knobs knobs, Map<String, EmitterEdit> emitters, List<String> removed, List<AddedEmitter> added) {
        public static final ClassEdit NONE = new ClassEdit(Knobs.NONE, Map.of(), List.of(), List.of());

        public ClassEdit {
            emitters = copyOf(emitters);
            removed = List.copyOf(removed);
            added = List.copyOf(added);
        }

        public boolean isEmpty() {
            return knobs.isNone() && emitters.isEmpty() && removed.isEmpty() && added.isEmpty();
        }

        public EmitterEdit emitter(String key) {
            return emitters.getOrDefault(key, EmitterEdit.NONE);
        }

        public @Nullable AddedEmitter added(String name) {
            for (var emitter : added) {
                if (emitter.name().equalsIgnoreCase(name)) {
                    return emitter;
                }
            }
            return null;
        }

        public ClassEdit withKnobs(Knobs value) {
            return new ClassEdit(value, emitters, removed, added);
        }

        public ClassEdit withEmitter(String key, EmitterEdit edit) {
            var result = new LinkedHashMap<>(emitters);
            if (edit.isEmpty()) {
                result.remove(key);
            } else {
                result.put(key, edit);
            }
            return new ClassEdit(knobs, result, removed, added);
        }

        ClassEdit withRemoved(String key) {
            var kept = new ArrayList<>(added);
            if (kept.removeIf(emitter -> emitter.name().equalsIgnoreCase(key))) {
                return new ClassEdit(knobs, emitters, removed, kept).withEmitter(key, EmitterEdit.NONE);
            }
            var result = new ArrayList<>(removed);
            result.add(key);
            return new ClassEdit(knobs, emitters, result, added).withEmitter(key, EmitterEdit.NONE);
        }

        public ClassEdit withAdded(AddedEmitter emitter) {
            var result = new ArrayList<>(added);
            result.add(emitter);
            return new ClassEdit(knobs, emitters, removed, result);
        }
    }

    public record EmitterEdit(Knobs knobs, List<PropertyEdit> properties, boolean disabled) {
        public static final EmitterEdit NONE = new EmitterEdit(Knobs.NONE, List.of(), false);

        public EmitterEdit {
            properties = List.copyOf(properties);
        }

        public boolean isEmpty() {
            return knobs.isNone() && properties.isEmpty() && !disabled;
        }

        public EmitterEdit withKnobs(Knobs value) {
            return new EmitterEdit(value, properties, disabled);
        }

        public EmitterEdit withDisabled(boolean value) {
            return new EmitterEdit(knobs, properties, value);
        }

        public EmitterEdit withProperty(PropertyEdit edit) {
            var result = new ArrayList<>(properties);
            result.removeIf(other -> other.path().equalsIgnoreCase(edit.path()));
            result.add(edit);
            return new EmitterEdit(knobs, result, disabled);
        }
    }

    public record AddedEmitter(String name, String fromClass, String fromEmitter) {}

    public record EmitterRef(String classPath, String key) {}

    public enum Kind {
        BOOL,
        BYTE,
        INT,
        FLOAT,
        STR,
        NAME,
        OBJECT,
        VECTOR,
        ROTATOR,
        COLOR,
        REMOVE
    }

    public record PropertyEdit(String path, Kind kind, String value) {
        public static PropertyEdit bool(String path, boolean value) {
            return new PropertyEdit(path, Kind.BOOL, Boolean.toString(value));
        }

        public static PropertyEdit number(String path, float value) {
            return new PropertyEdit(path, Kind.FLOAT, Float.toString(value));
        }

        public static PropertyEdit integer(String path, int value) {
            return new PropertyEdit(path, Kind.INT, Integer.toString(value));
        }

        public static PropertyEdit object(String path, String objectPath) {
            return new PropertyEdit(path, Kind.OBJECT, objectPath);
        }

        public static PropertyEdit remove(String path) {
            return new PropertyEdit(path, Kind.REMOVE, "");
        }
    }

    public record SkillEdit(
            @Nullable Float flyingTime,
            Map<String, ActionEdit> actions,
            List<AddedAction> added,
            List<ActionSlot> removed,
            List<ActionMove> moved) {
        public static final SkillEdit NONE = new SkillEdit(null, Map.of(), List.of(), List.of(), List.of());

        public SkillEdit {
            actions = copyOf(actions);
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            moved = List.copyOf(moved);
        }

        public boolean isEmpty() {
            return flyingTime == null && actions.isEmpty() && added.isEmpty() && removed.isEmpty() && moved.isEmpty();
        }

        public ActionEdit action(String name) {
            return actions.getOrDefault(name, ActionEdit.NONE);
        }

        public SkillEdit withFlyingTime(@Nullable Float value) {
            return new SkillEdit(value, actions, added, removed, moved);
        }

        public SkillEdit withAction(String name, ActionEdit edit) {
            var result = new LinkedHashMap<>(actions);
            if (edit.equals(ActionEdit.NONE)) {
                result.remove(name);
            } else {
                result.put(name, edit);
            }
            return new SkillEdit(flyingTime, result, added, removed, moved);
        }

        public SkillEdit withAdded(AddedAction action) {
            var result = new ArrayList<>(added);
            result.add(action);
            return new SkillEdit(flyingTime, actions, result, removed, moved);
        }

        public SkillEdit withRemoved(ActionSlot slot) {
            var kept = new ArrayList<>(added);
            if (kept.removeIf(action -> action.name().equalsIgnoreCase(slot.name()))) {
                return new SkillEdit(flyingTime, actions, kept, removed, moved)
                        .withAction(slot.name(), ActionEdit.NONE);
            }
            var result = new ArrayList<>(removed);
            result.add(slot);
            return new SkillEdit(flyingTime, actions, added, result, moved);
        }

        public SkillEdit withMoved(ActionMove move) {
            var result = new ArrayList<>(moved);
            result.add(move);
            return new SkillEdit(flyingTime, actions, added, removed, result);
        }
    }

    public record ActionEdit(
            @Nullable String effectClass,
            @Nullable Integer attachOn,
            @Nullable String attachBoneName,
            @Nullable Boolean spawnOnTarget,
            @Nullable Boolean onMultiTarget,
            @Nullable Boolean relativeToCylinder,
            @Nullable List<Float> offset,
            @Nullable Float scaleSize,
            @Nullable Float spawnDelay) {
        public static final ActionEdit NONE = new ActionEdit(null, null, null, null, null, null, null, null, null);

        public ActionEdit withEffectClass(@Nullable String value) {
            return new ActionEdit(
                    value,
                    attachOn,
                    attachBoneName,
                    spawnOnTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scaleSize,
                    spawnDelay);
        }

        public ActionEdit withAttachOn(@Nullable Integer value) {
            return new ActionEdit(
                    effectClass,
                    value,
                    attachBoneName,
                    spawnOnTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scaleSize,
                    spawnDelay);
        }

        public ActionEdit withAttachBoneName(@Nullable String value) {
            return new ActionEdit(
                    effectClass,
                    attachOn,
                    value,
                    spawnOnTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scaleSize,
                    spawnDelay);
        }

        public ActionEdit withSpawnOnTarget(@Nullable Boolean value) {
            return new ActionEdit(
                    effectClass,
                    attachOn,
                    attachBoneName,
                    value,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scaleSize,
                    spawnDelay);
        }

        public ActionEdit withOnMultiTarget(@Nullable Boolean value) {
            return new ActionEdit(
                    effectClass,
                    attachOn,
                    attachBoneName,
                    spawnOnTarget,
                    value,
                    relativeToCylinder,
                    offset,
                    scaleSize,
                    spawnDelay);
        }

        public ActionEdit withRelativeToCylinder(@Nullable Boolean value) {
            return new ActionEdit(
                    effectClass,
                    attachOn,
                    attachBoneName,
                    spawnOnTarget,
                    onMultiTarget,
                    value,
                    offset,
                    scaleSize,
                    spawnDelay);
        }

        public ActionEdit withOffset(@Nullable List<Float> value) {
            return new ActionEdit(
                    effectClass,
                    attachOn,
                    attachBoneName,
                    spawnOnTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    value,
                    scaleSize,
                    spawnDelay);
        }

        public ActionEdit withScaleSize(@Nullable Float value) {
            return new ActionEdit(
                    effectClass,
                    attachOn,
                    attachBoneName,
                    spawnOnTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    value,
                    spawnDelay);
        }

        public ActionEdit withSpawnDelay(@Nullable Float value) {
            return new ActionEdit(
                    effectClass,
                    attachOn,
                    attachBoneName,
                    spawnOnTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scaleSize,
                    value);
        }
    }

    public record AddedAction(String name, Phase phase) {}

    public record ActionSlot(String name, Phase phase) {}

    public record ActionMove(String name, Phase from, Phase to) {}
}

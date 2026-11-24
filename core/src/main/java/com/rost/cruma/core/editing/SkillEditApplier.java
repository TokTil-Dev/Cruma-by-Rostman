package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.TaggedProperties.ARRAY;
import static com.rost.cruma.core.editing.TaggedProperties.BOOL;
import static com.rost.cruma.core.editing.TaggedProperties.BYTE;
import static com.rost.cruma.core.editing.TaggedProperties.FLOAT;
import static com.rost.cruma.core.editing.TaggedProperties.NAME;
import static com.rost.cruma.core.editing.TaggedProperties.OBJECT;
import static com.rost.cruma.core.editing.TaggedProperties.STRUCT;

import com.rost.cruma.core.editing.EffectEdits.ActionEdit;
import com.rost.cruma.core.editing.EffectEdits.SkillEdit;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

final class SkillEditApplier {
    private static final String ACTION_CLASS = "L2EffectEmitter";
    private static final String SIMPLE_EFFECT = "SimpleEffectClass";

    private SkillEditApplier() {}

    static Map<String, Integer> keys(ClassPackageWriter writer, int classExport) throws IOException {
        var data = ClassExportData.read(writer.getPayload(classExport), writer.exportName(classExport), writer);
        var result = new LinkedHashMap<String, Integer>();
        if (data != null) {
            for (var phase : Phase.values()) {
                var property = data.defaults.find(phase.getProperty(), writer);
                if (property != null
                        && property.getArray() != null
                        && property.getArray().getObjects() != null) {
                    for (var slot : property.getArray().getObjects()) {
                        if (slot > 0) {
                            result.put(key(writer, classExport, slot), slot);
                        }
                    }
                }
            }
        }
        for (var child : writer.getChildren(classExport)) {
            if (writer.getClassName(child).equals(ACTION_CLASS)) {
                result.put(key(writer, classExport, child), child);
            }
        }
        return result;
    }

    static void apply(
            ClassPackageWriter writer,
            int classExport,
            SkillEdit edit,
            UnaryOperator<String> effectPath,
            Map<Integer, String> keys)
            throws IOException {
        var className = writer.exportName(classExport);
        var data = ClassExportData.read(writer.getPayload(classExport), className, writer);
        if (data == null) {
            throw malformed("Skill class " + className + " has an unknown layout");
        }
        var classBefore = data.write();
        if (edit.flyingTime() != null) {
            var tag = data.defaults.find("FlyingTime", writer);
            if (tag != null || edit.flyingTime() != 0f) {
                EmitterProperties.ensure(data.defaults, "FlyingTime", FLOAT, null, writer)
                        .setFloatValue(edit.flyingTime());
            }
        }
        var actions = new HashMap<String, Integer>();
        for (var entry : keys(writer, classExport).entrySet()) {
            actions.put(lower(keys.getOrDefault(entry.getValue(), entry.getKey())), entry.getValue());
        }
        var added = new HashSet<String>();
        for (var action : edit.added()) {
            if (!added.add(lower(action.name())) || actions.containsKey(lower(action.name()))) {
                throw malformed("Skill class " + className + " already has an action " + action.name());
            }
            var fields = edit.action(action.name());
            if (fields.effectClass() == null) {
                throw malformed("New action " + action.name() + " of " + className + " has no effect class");
            }
            var tree = TaggedProperties.empty(writer);
            applyFields(writer, tree, fields, true, effectPath);
            var export = writer.addExport(
                    actionClass(writer), 0, classExport, action.name(), actionFlags(writer), tree.toBytes());
            actions.put(lower(action.name()), export);
            addSlot(phase(data, action.phase(), writer), export);
        }
        for (var slot : edit.removed()) {
            removeSlot(data, slot.phase(), require(actions, slot.name(), className), writer);
        }
        for (var move : edit.moved()) {
            var export = require(actions, move.name(), className);
            removeSlot(data, move.from(), export, writer);
            addSlot(phase(data, move.to(), writer), export);
        }
        for (var entry : edit.actions().entrySet()) {
            var export = require(actions, entry.getKey(), className);
            if (added.contains(lower(entry.getKey()))) {
                continue;
            }
            var row = writer.export(export);
            var own = row.outer() == classExport;
            var payload = own ? writer.getPayload(export) : writer.getOriginalPayload(export);
            var cursor = new Cursor(payload, 0, payload.length);
            var tree = TaggedProperties.read(cursor, writer);
            if (cursor.position != cursor.end) {
                throw malformed("Action " + entry.getKey() + " of " + className + " has a native tail");
            }
            var before = tree.toBytes();
            applyFields(writer, tree, entry.getValue(), false, effectPath);
            if (Arrays.equals(before, tree.toBytes())) {
                continue;
            }
            if (own) {
                writer.replace(export, tree.toBytes());
            } else {
                var name = writer.freeChildName(classExport, ACTION_CLASS);
                var copy = writer.addExport(row.classIndex(), 0, classExport, name, row.flags(), tree.toBytes());
                for (var phase : Phase.values()) {
                    var property = data.defaults.find(phase.getProperty(), writer);
                    if (property != null
                            && property.getArray() != null
                            && property.getArray().getObjects() != null) {
                        property.getArray().getObjects().replaceAll(slot -> slot == export ? copy : slot);
                    }
                }
            }
        }
        if (!Arrays.equals(data.write(), classBefore)) {
            writer.replace(classExport, data.write());
        }
    }

    static void bool(
            ClassPackageWriter writer, TaggedProperties tree, String name, @Nullable Boolean value, boolean absent)
            throws IOException {
        if (value != null && (value != absent || tree.find(name, writer) != null)) {
            EmitterProperties.ensure(tree, name, BOOL, null, writer).setBool(value);
        }
    }

    static void number(ClassPackageWriter writer, TaggedProperties tree, String name, @Nullable Float value)
            throws IOException {
        if (value != null && (value != 0f || tree.find(name, writer) != null)) {
            EmitterProperties.ensure(tree, name, FLOAT, null, writer).setFloatValue(value);
        }
    }

    static void offset(ClassPackageWriter writer, TaggedProperties tree, @Nullable List<Float> values)
            throws IOException {
        if (values == null) {
            return;
        }
        var zero = values.get(0) == 0f && values.get(1) == 0f && values.get(2) == 0f;
        if (!zero || tree.find("offset", writer) != null) {
            var property = EmitterProperties.ensure(tree, "offset", STRUCT, "Vector", writer);
            property.setRaw(new byte[12]);
            property.getBinary()
                    .putFloat(0, values.get(0))
                    .putFloat(4, values.get(1))
                    .putFloat(8, values.get(2));
        }
    }

    private static String key(ClassPackageWriter writer, int classExport, int action) throws IOException {
        var row = writer.export(action);
        var name = writer.getName(row.objectName());
        return row.outer() == classExport ? name : writer.getObjectName(row.outer()) + "." + name;
    }

    private static void applyFields(
            ClassPackageWriter writer,
            TaggedProperties tree,
            ActionEdit fields,
            boolean isNew,
            UnaryOperator<String> effectPath)
            throws IOException {
        if (fields.effectClass() != null) {
            var reference = writer.importPath(effectPath.apply(fields.effectClass()), "Core", "Class");
            EmitterProperties.ensure(tree, "EffectClass", OBJECT, null, writer).setIndex(reference);
            if (tree.find(SIMPLE_EFFECT, writer) != null || isNew && writer.findName(SIMPLE_EFFECT) >= 0) {
                EmitterProperties.ensure(tree, SIMPLE_EFFECT, OBJECT, null, writer)
                        .setIndex(reference);
            }
        }
        if (fields.attachOn() != null && fields.attachOn() >= SkillEffects.AM_RIGHT_FOOT) {
            throw malformed("An L2EffectEmitter cannot hang on the feet: only the skill.usk of Interlude has them");
        }
        if (fields.attachOn() != null && (fields.attachOn() != 0 || tree.find("AttachOn", writer) != null)) {
            EmitterProperties.ensure(tree, "AttachOn", BYTE, null, writer).setByteValue(fields.attachOn());
        }
        if (fields.attachBoneName() != null) {
            if (fields.attachBoneName().isEmpty()) {
                tree.remove("AttachBoneName", 0, writer);
            } else {
                EmitterProperties.ensure(tree, "AttachBoneName", NAME, null, writer)
                        .setIndex(writer.intern(fields.attachBoneName()));
            }
        }
        bool(writer, tree, "bSpawnOnTarget", fields.spawnOnTarget(), true);
        bool(writer, tree, "bOnMultiTarget", fields.onMultiTarget(), false);
        bool(writer, tree, "bRelativeToCylinder", fields.relativeToCylinder(), true);
        offset(writer, tree, fields.offset());
        number(writer, tree, "ScaleSize", fields.scaleSize());
        number(writer, tree, "SpawnDelay", fields.spawnDelay());
    }

    private static List<Integer> phase(ClassExportData data, Phase phase, ClassPackageWriter writer)
            throws IOException {
        var property = data.defaults.find(phase.getProperty(), writer);
        if (property == null) {
            property = data.defaults.add(writer.intern(phase.getProperty()), ARRAY);
            property.makeEmptyArray(true);
        }
        if (property.getArray() == null || property.getArray().getObjects() == null) {
            throw malformed("Phase " + phase.getProperty() + " is not an object array");
        }
        return property.getArray().getObjects();
    }

    private static void addSlot(List<Integer> slots, int export) {
        var free = slots.indexOf(0);
        if (free >= 0) {
            slots.set(free, export);
        } else {
            slots.add(export);
        }
    }

    private static void removeSlot(ClassExportData data, Phase phase, int export, ClassPackageWriter writer)
            throws IOException {
        var slots = phase(data, phase, writer);
        if (!slots.remove((Integer) export)) {
            throw malformed("Phase " + phase.getProperty() + " has no action " + writer.exportName(export));
        }
        if (slots.stream().allMatch(slot -> slot == 0)) {
            data.defaults.remove(phase.getProperty(), 0, writer);
        }
    }

    private static int actionClass(ClassPackageWriter writer) {
        return writer.importObject(writer.importPackage("Engine"), "Core", "Class", ACTION_CLASS);
    }

    private static int actionFlags(ClassPackageWriter writer) throws IOException {
        var seen = new ArrayList<Integer>();
        for (var export = 1; export <= writer.getExportCount() && seen.isEmpty(); export++) {
            if (writer.getClassName(export).equals(ACTION_CLASS)) {
                seen.add(writer.export(export).flags());
            }
        }
        return seen.isEmpty() ? 0x00070000 : seen.getFirst();
    }

    private static int require(Map<String, Integer> actions, String name, String className) throws IOException {
        var export = actions.get(lower(name));
        if (export == null) {
            throw malformed("Skill class " + className + " has no action " + name);
        }
        return export;
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}

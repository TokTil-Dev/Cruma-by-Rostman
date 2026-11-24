package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.TaggedProperties.ARRAY;
import static com.rost.cruma.core.editing.TaggedProperties.BYTE;
import static com.rost.cruma.core.editing.TaggedProperties.INT;
import static com.rost.cruma.core.editing.TaggedProperties.NAME;
import static com.rost.cruma.core.editing.TaggedProperties.OBJECT;

import com.rost.cruma.core.editing.EffectEdits.ActionEdit;
import com.rost.cruma.core.editing.EffectEdits.SkillEdit;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Layout;
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

final class SkillVisualEditApplier {
    static final String VISUAL_CLASS = "SkillVisualEffect";
    private static final String ACTION_CLASS = "SkillAction_LocateEffect";
    private static final int ACTION_FLAGS = 0x00070004;

    private SkillVisualEditApplier() {}

    static int find(ClassPackageWriter writer, String path) throws IOException {
        for (var export = 1; export <= writer.getExportCount(); export++) {
            if (writer.getClassName(export).equals(VISUAL_CLASS)
                    && writer.getObjectPath(export).equalsIgnoreCase(path)) {
                return export;
            }
        }
        return 0;
    }

    static Map<String, Integer> keys(ClassPackageWriter writer, int skill) throws IOException {
        var tree = read(writer, skill);
        var result = new LinkedHashMap<String, Integer>();
        for (var phase : Phase.values()) {
            for (var element : elements(tree, phase, writer)) {
                var action = action(element, writer);
                if (action > 0) {
                    result.put(key(writer, skill, action), action);
                }
            }
        }
        return result;
    }

    static List<String> playedKeys(ClassPackageWriter writer, int skill, Phase phase) throws IOException {
        var result = new ArrayList<String>();
        for (var element : elements(read(writer, skill), phase, writer)) {
            var action = action(element, writer);
            if (action > 0 && writer.getClassName(action).equals(ACTION_CLASS) && hasEffect(writer, action)) {
                result.add(key(writer, skill, action));
            }
        }
        return result;
    }

    static void apply(
            ClassPackageWriter writer, int skill, SkillEdit edit, UnaryOperator<String> effectPath, Layout layout)
            throws IOException {
        var path = writer.getObjectPath(skill);
        var tree = read(writer, skill);
        var before = tree.toBytes();
        if (edit.flyingTime() != null) {
            SkillEditApplier.number(writer, tree, "FlyingTime", edit.flyingTime());
        }
        var actions = new HashMap<String, Integer>();
        for (var entry : keys(writer, skill).entrySet()) {
            actions.put(lower(entry.getKey()), entry.getValue());
        }
        var group = writer.export(skill).outer();
        var added = new HashSet<String>();
        for (var action : edit.added()) {
            var left = child(writer, group, action.name());
            if (!added.add(lower(action.name()))
                    || actions.containsKey(lower(action.name()))
                    || left != 0 && (!writer.getClassName(left).equals(ACTION_CLASS) || users(writer, left) > 0)) {
                throw malformed("Skill " + path + " already has an action " + action.name());
            }
            var fields = edit.action(action.name());
            if (fields.effectClass() == null) {
                throw malformed("New action " + action.name() + " of " + path + " has no effect class");
            }
            var actionTree = TaggedProperties.empty(writer);
            applyFields(writer, actionTree, fields, effectPath, layout, path);
            var export = left;
            if (left != 0) {
                writer.replace(left, actionTree.toBytes());
            } else {
                export = writer.addExport(
                        actionClass(writer), 0, group, action.name(), actionFlags(writer), actionTree.toBytes());
            }
            actions.put(lower(action.name()), export);
            phase(tree, action.phase(), layout, path, writer).add(element(writer, export));
        }
        for (var slot : edit.removed()) {
            take(tree, slot.phase(), require(actions, slot.name(), path), path, writer);
        }
        for (var move : edit.moved()) {
            var element = take(tree, move.from(), require(actions, move.name(), path), path, writer);
            phase(tree, move.to(), layout, path, writer).add(element);
        }
        for (var entry : edit.actions().entrySet()) {
            if (added.contains(lower(entry.getKey()))) {
                continue;
            }
            var export = require(actions, entry.getKey(), path);
            if (users(writer, export) > 1) {
                throw malformed("Action " + entry.getKey() + " of " + path
                        + " stands in more than one phase, so a change of it would change them all");
            }
            var payload = writer.getPayload(export);
            var cursor = new Cursor(payload, 0, payload.length);
            var actionTree = TaggedProperties.read(cursor, writer);
            if (cursor.position != cursor.end) {
                throw malformed("Action " + entry.getKey() + " of " + path + " has a native tail");
            }
            var old = actionTree.toBytes();
            applyFields(writer, actionTree, entry.getValue(), effectPath, layout, path);
            if (!Arrays.equals(old, actionTree.toBytes())) {
                writer.replace(export, actionTree.toBytes());
            }
        }
        if (!Arrays.equals(before, tree.toBytes())) {
            writer.replace(skill, tree.toBytes());
        }
    }

    private static TaggedProperties read(ClassPackageWriter writer, int skill) throws IOException {
        var payload = writer.getPayload(skill);
        var cursor = new Cursor(payload, 0, payload.length);
        var tree = TaggedProperties.read(cursor, writer);
        if (cursor.position != cursor.end) {
            throw malformed("Skill " + writer.getObjectPath(skill) + " has a native tail");
        }
        return tree;
    }

    private static List<TaggedProperties> elements(TaggedProperties tree, Phase phase, ClassPackageWriter writer)
            throws IOException {
        var property = tree.find(phase.getVisualProperty(), writer);
        if (property == null) {
            return List.of();
        }
        if (property.getArray() == null || property.getArray().getElements() == null) {
            throw malformed("Phase " + phase.getVisualProperty() + " is not an array of actions");
        }
        return property.getArray().getElements();
    }

    private static List<TaggedProperties> phase(
            TaggedProperties tree, Phase phase, Layout layout, String path, ClassPackageWriter writer)
            throws IOException {
        if (!layout.phases().contains(phase)) {
            throw malformed("Skill " + path + " cannot hold " + phase.getVisualProperty()
                    + ": the Engine.u of this client has no such phase");
        }
        var property = tree.find(phase.getVisualProperty(), writer);
        if (property == null) {
            property = tree.add(writer.intern(phase.getVisualProperty()), ARRAY);
            property.makeEmptyArray(false);
        }
        return elements(tree, phase, writer);
    }

    private static TaggedProperties take(
            TaggedProperties tree, Phase phase, int export, String path, ClassPackageWriter writer) throws IOException {
        var elements = elements(tree, phase, writer);
        for (var element : elements) {
            if (action(element, writer) == export) {
                elements.remove(element);
                if (elements.isEmpty()) {
                    tree.remove(phase.getVisualProperty(), 0, writer);
                }
                return element;
            }
        }
        throw malformed(
                "Phase " + phase.getVisualProperty() + " of " + path + " has no action " + writer.exportName(export));
    }

    private static TaggedProperties element(ClassPackageWriter writer, int export) {
        var element = TaggedProperties.empty(writer);
        element.add(writer.intern("Action"), OBJECT).setIndex(export);
        element.add(writer.intern("SpecificStage"), INT).setIntValue(0);
        return element;
    }

    private static boolean hasEffect(ClassPackageWriter writer, int action) throws IOException {
        var payload = writer.getPayload(action);
        var tree = TaggedProperties.read(new Cursor(payload, 0, payload.length), writer);
        var effect = tree.find("EffectClass", writer);
        return effect != null && effect.getIndex() != 0;
    }

    private static int action(TaggedProperties element, ClassPackageWriter writer) throws IOException {
        var reference = element.find("Action", writer);
        return reference == null ? 0 : reference.getIndex();
    }

    private static int users(ClassPackageWriter writer, int action) throws IOException {
        var count = 0;
        for (var export = 1; export <= writer.getExportCount(); export++) {
            if (writer.getClassName(export).equals(VISUAL_CLASS)) {
                var tree = read(writer, export);
                for (var phase : Phase.values()) {
                    for (var element : elements(tree, phase, writer)) {
                        count += action(element, writer) == action ? 1 : 0;
                    }
                }
            }
        }
        return count;
    }

    private static void applyFields(
            ClassPackageWriter writer,
            TaggedProperties tree,
            ActionEdit fields,
            UnaryOperator<String> effectPath,
            Layout layout,
            String path)
            throws IOException {
        if (fields.effectClass() != null) {
            var reference = writer.importPath(effectPath.apply(fields.effectClass()), "Core", "Class");
            EmitterProperties.ensure(tree, "EffectClass", OBJECT, null, writer).setIndex(reference);
        }
        if (fields.attachOn() != null) {
            var value = layout.places().contains(fields.attachOn())
                    ? SkillEffects.visualAttach(
                            fields.attachOn(), layout.places().size())
                    : -1;
            if (value < 0) {
                throw malformed("An action of " + path + " cannot hang on place " + fields.attachOn()
                        + ": the Engine.u of this client has no such place");
            }
            if (value != 0 || tree.find("AttachOn", writer) != null) {
                EmitterProperties.ensure(tree, "AttachOn", BYTE, null, writer).setByteValue(value);
            }
        }
        if (fields.attachBoneName() != null) {
            if (fields.attachBoneName().isEmpty()) {
                tree.remove("AttachBoneName", 0, writer);
            } else {
                EmitterProperties.ensure(tree, "AttachBoneName", NAME, null, writer)
                        .setIndex(writer.intern(fields.attachBoneName()));
            }
        }
        SkillEditApplier.bool(writer, tree, "bSpawnOnTarget", fields.spawnOnTarget(), false);
        SkillEditApplier.bool(writer, tree, "bOnMultiTarget", fields.onMultiTarget(), false);
        SkillEditApplier.bool(writer, tree, "bRelativeToCylinder", fields.relativeToCylinder(), true);
        SkillEditApplier.offset(writer, tree, fields.offset());
        if (!layout.scale() && fields.scaleSize() != null && fields.scaleSize() != 0f) {
            throw malformed("An action of " + path + " cannot take a scale: skill.usk has no ScaleSize");
        }
        if (!layout.delay() && fields.spawnDelay() != null && fields.spawnDelay() != 0f) {
            throw malformed(
                    "An action of " + path + " cannot take a delay: the Engine.u of this client has no SpawnDelay");
        }
        SkillEditApplier.number(writer, tree, "SpawnDelay", fields.spawnDelay());
    }

    private static String key(ClassPackageWriter writer, int skill, int action) throws IOException {
        var row = writer.export(action);
        var name = writer.getName(row.objectName());
        return row.outer() == writer.export(skill).outer() ? name : writer.getObjectName(row.outer()) + "." + name;
    }

    private static int child(ClassPackageWriter writer, int group, String name) throws IOException {
        for (var child : writer.getChildren(group)) {
            if (writer.exportName(child).equalsIgnoreCase(name)) {
                return child;
            }
        }
        return 0;
    }

    private static int actionClass(ClassPackageWriter writer) {
        return writer.importObject(writer.importPackage("Engine"), "Core", "Class", ACTION_CLASS);
    }

    private static int actionFlags(ClassPackageWriter writer) throws IOException {
        for (var export = 1; export <= writer.getExportCount(); export++) {
            if (writer.getClassName(export).equals(ACTION_CLASS)) {
                return writer.export(export).flags();
            }
        }
        return ACTION_FLAGS;
    }

    private static int require(Map<String, Integer> actions, String name, String path) throws IOException {
        var export = actions.get(lower(name));
        if (export == null) {
            throw malformed("Skill " + path + " has no action " + name);
        }
        return export;
    }

    private static String lower(@Nullable String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}

package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.TaggedProperties.ARRAY;
import static com.rost.cruma.core.editing.TaggedProperties.BOOL;
import static com.rost.cruma.core.editing.TaggedProperties.OBJECT;

import com.rost.cruma.core.editing.EffectEdits.AddedEmitter;
import com.rost.cruma.core.editing.EffectEdits.ClassEdit;
import com.rost.cruma.core.editing.EffectEdits.EmitterEdit;
import com.rost.cruma.core.editing.EffectEdits.PropertyEdit;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.texture.TextureHue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class EffectEditApplier {
    private EffectEditApplier() {}

    interface Context {
        EmitterOrigin getEmitter(String classPath, AddedEmitter added) throws IOException;

        ObjectTarget getObject(String path, String property) throws IOException;

        @Nullable
        PropertyEdit getPrepared(PropertyEdit edit, int degrees) throws IOException;

        @Nullable
        String createShiftedTexture(String texturePath, int degrees) throws IOException;
    }

    record EmitterOrigin(ClassPackageWriter owner, int export, boolean sibling) {}

    record ObjectTarget(String path, String classPackage, String className) {}

    static Map<String, Integer> keys(ClassPackageWriter writer, int classExport) throws IOException {
        var data = ClassExportData.read(writer.getPayload(classExport), writer.exportName(classExport), writer);
        var result = new LinkedHashMap<String, Integer>();
        for (var slot : slots(writer, classExport, data)) {
            if (slot > 0) {
                result.put(key(writer, classExport, slot), slot);
            }
        }
        return result;
    }

    static void apply(
            ClassPackageWriter writer,
            int classExport,
            String classPath,
            ClassEdit edit,
            Context context,
            Map<Integer, String> keys)
            throws IOException {
        apply(writer, classExport, classPath, edit, context, keys, null);
    }

    static void apply(
            ClassPackageWriter writer,
            int classExport,
            String classPath,
            ClassEdit edit,
            Context context,
            Map<Integer, String> keys,
            int @Nullable [] movedSlots)
            throws IOException {
        var data = ClassExportData.read(writer.getPayload(classExport), writer.exportName(classExport), writer);
        if (data == null && !(edit.removed().isEmpty() && edit.added().isEmpty())) {
            throw malformed(
                    "The emitter list of " + classPath + " cannot be read, so emitters cannot be added or removed");
        }
        var classBefore = data == null ? null : data.write();
        var slots = edit.added().isEmpty()
                ? slots(writer, classExport, data)
                : emitterArray(writer, data, slots(writer, classExport, data));
        var keyed = new LinkedHashMap<String, Integer>();
        var keyOf = new HashMap<Integer, String>();
        for (var slot : slots) {
            if (slot > 0) {
                var key = lower(keys.getOrDefault(slot, key(writer, classExport, slot)));
                keyed.put(key, slot);
                keyOf.put(slot, key);
            }
        }
        var trees = new LinkedHashMap<Integer, TaggedProperties>();
        var before = new HashMap<Integer, byte[]>();
        for (var added : edit.added()) {
            var origin = context.getEmitter(classPath, added);
            var export = new EffectClassCopier(origin.owner(), writer)
                    .copyEmitter(origin.export(), classExport, added.name(), origin.sibling(), movedSlots);
            slots.add(export);
            keyed.put(lower(added.name()), export);
            keyOf.put(export, lower(added.name()));
        }
        if (!edit.removed().isEmpty()) {
            for (var slot : slots) {
                if (slot > 0) {
                    open(writer, classExport, slot, trees, before);
                }
            }
            var removed = new HashSet<Integer>();
            for (var key : edit.removed()) {
                removed.add(require(keyed, key, classPath));
            }
            EffectClassCopier.removeSlots(writer, data.defaults, trees, removed);
            trees.keySet().removeAll(removed);
        }
        for (var key : edit.emitters().keySet()) {
            require(keyed, key, classPath);
        }
        for (var export : new LinkedHashSet<>(slots)) {
            if (export <= 0) {
                continue;
            }
            var emitterEdit = editOf(edit, keyOf.get(export));
            var knobs = edit.knobs().times(emitterEdit.knobs());
            if (emitterEdit.isEmpty() && knobs.isNone() && !trees.containsKey(export)) {
                continue;
            }
            var tree = open(writer, classExport, export, trees, before);
            var degrees = textureDegrees(writer, export, knobs.hue());
            for (var property : emitterEdit.properties()) {
                var prepared = context.getPrepared(property, degrees);
                if (prepared != null) {
                    EmitterProperties.apply(
                            tree, prepared, writer, (path, name) -> objectIndex(writer, context, path, name));
                }
            }
            if (emitterEdit.disabled()) {
                EmitterProperties.ensure(tree, "Disabled", BOOL, null, writer).setBool(true);
            }
            EmitterProperties.applyKnobs(tree, knobs, writer);
            shiftTexture(writer, tree, degrees, context);
        }
        for (var entry : trees.entrySet()) {
            var bytes = entry.getValue().toBytes();
            if (!Arrays.equals(bytes, before.get(entry.getKey()))) {
                write(writer, classExport, entry.getKey(), bytes, slots);
            }
        }
        if (data != null && !Arrays.equals(data.write(), classBefore)) {
            writer.replace(classExport, data.write());
        }
    }

    private static EmitterEdit editOf(ClassEdit edit, String lowerKey) {
        for (var entry : edit.emitters().entrySet()) {
            if (lower(entry.getKey()).equals(lowerKey)) {
                return entry.getValue();
            }
        }
        return EmitterEdit.NONE;
    }

    private static void write(ClassPackageWriter writer, int classExport, int export, byte[] bytes, List<Integer> slots)
            throws IOException {
        var row = writer.export(export);
        if (row.outer() == classExport) {
            writer.replace(export, bytes);
            return;
        }
        var own = writer.addExport(
                row.classIndex(),
                0,
                classExport,
                writer.freeChildName(classExport, writer.getClassName(export)),
                row.flags(),
                bytes);
        slots.replaceAll(slot -> slot == export ? own : slot);
    }

    private static TaggedProperties open(
            ClassPackageWriter writer,
            int classExport,
            int export,
            Map<Integer, TaggedProperties> trees,
            Map<Integer, byte[]> before)
            throws IOException {
        var tree = trees.get(export);
        if (tree == null) {
            tree = read(writer, classExport, export);
            trees.put(export, tree);
            before.put(export, tree.toBytes());
        }
        return tree;
    }

    private static TaggedProperties read(ClassPackageWriter writer, int classExport, int export) throws IOException {
        var payload = writer.export(export).outer() == classExport
                ? writer.getPayload(export)
                : writer.getOriginalPayload(export);
        var cursor = new Cursor(payload, 0, payload.length);
        var tree = TaggedProperties.read(cursor, writer);
        if (cursor.position != cursor.end) {
            throw malformed("Emitter " + writer.exportName(export) + " has a native tail");
        }
        return tree;
    }

    private static int textureDegrees(ClassPackageWriter writer, int export, double hue) throws IOException {
        var kind = writer.getClassName(export);
        return kind.equals("MeshEmitter") || kind.equals("VertMeshEmitter") ? 0 : TextureHue.normalize(hue);
    }

    private static void shiftTexture(ClassPackageWriter writer, TaggedProperties tree, int degrees, Context context)
            throws IOException {
        if (degrees == 0) {
            return;
        }
        var texture = tree.find("Texture", writer);
        if (texture == null || texture.getType() != OBJECT) {
            return;
        }
        var reference = texture.getIndex();
        if (reference == 0 || !writer.getClassOf(reference).equals("Texture")) {
            return;
        }
        var shifted = context.createShiftedTexture(writer.getObjectPath(reference), degrees);
        if (shifted != null) {
            texture.setIndex(writer.importPath(shifted, "Engine", "Texture"));
        }
    }

    private static int objectIndex(ClassPackageWriter writer, Context context, String path, String property)
            throws IOException {
        if (path.isEmpty() || path.equalsIgnoreCase("None")) {
            return 0;
        }
        var target = context.getObject(path, property);
        if (target.path().toLowerCase(Locale.ROOT).startsWith(lower(writer.getPackageName()) + ".")) {
            for (var export = 1; export <= writer.getExportCount(); export++) {
                if (writer.getObjectPath(export).equalsIgnoreCase(target.path())) {
                    return export;
                }
            }
        }
        return writer.importPath(target.path(), target.classPackage(), target.className());
    }

    private static List<Integer> slots(ClassPackageWriter writer, int classExport, @Nullable ClassExportData data)
            throws IOException {
        var property = data == null ? null : data.defaults.find("Emitters", writer);
        if (property != null
                && property.getArray() != null
                && property.getArray().getObjects() != null) {
            return property.getArray().getObjects();
        }
        var children = new ArrayList<Integer>();
        for (var child : writer.getChildren(classExport)) {
            if (EffectClassCopier.EMITTER_CLASSES.contains(writer.getClassName(child))) {
                children.add(child);
            }
        }
        return children;
    }

    private static List<Integer> emitterArray(ClassPackageWriter writer, ClassExportData data, List<Integer> slots)
            throws IOException {
        var property = data.defaults.find("Emitters", writer);
        if (property != null) {
            return slots;
        }
        var array = data.defaults
                .add(writer.intern("Emitters"), ARRAY)
                .makeEmptyArray(true)
                .getObjects();
        array.addAll(slots);
        return array;
    }

    private static String key(ClassPackageWriter writer, int classExport, int emitter) throws IOException {
        var row = writer.export(emitter);
        var name = writer.getName(row.objectName());
        return row.outer() == classExport ? name : writer.getObjectName(row.outer()) + "." + name;
    }

    private static int require(Map<String, Integer> keyed, String key, String classPath) throws IOException {
        var export = keyed.get(lower(key));
        if (export == null) {
            throw malformed("Class " + classPath + " has no emitter " + key);
        }
        return export;
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}

package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.PackageBytes.writeInt;

import com.rost.cruma.core.editing.ClassExportData.Dependency;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class EffectClassCopier {
    static final Set<String> EMITTER_CLASSES = Set.of(
            "SpriteEmitter",
            "MeshEmitter",
            "VertMeshEmitter",
            "BeamEmitter",
            "TrailEmitter",
            "RibbonEmitter",
            "SparkEmitter");
    private static final String ACTION_CLASS = "L2EffectEmitter";
    private static final List<String> SLOT_LISTS = slotLists();
    private static final int RF_HAS_STACK = 0x02000000;

    private final ClassPackageWriter source;
    private final ClassPackageWriter target;
    private final boolean samePackage;
    private final Map<Integer, Integer> names = new HashMap<>();
    private final Map<Integer, Integer> exports = new HashMap<>();
    private final Map<Integer, Integer> imports = new HashMap<>();

    EffectClassCopier(ClassPackageWriter source, ClassPackageWriter target) {
        this.source = source;
        this.target = target;
        samePackage = source == target;
    }

    static @Nullable String refusal(ClassPackageWriter source, int classExport) throws IOException {
        var name = source.exportName(classExport);
        var data = ClassExportData.read(source.getPayload(classExport), name, source);
        if (data == null) {
            return "Class " + name + " has script code or an unknown layout";
        }
        if (data.children != 0) {
            return "Class " + name + " has script functions";
        }
        for (var child : source.getChildren(classExport)) {
            var kind = source.getClassName(child);
            if (!kind.equals("TextBuffer") && !kind.equals(ACTION_CLASS) && !EMITTER_CLASSES.contains(kind)) {
                return "Class " + name + " has a " + kind + " object";
            }
        }
        return null;
    }

    static void removeSlots(
            ClassPackageWriter owner,
            TaggedProperties defaults,
            Map<Integer, TaggedProperties> trees,
            Set<Integer> removed)
            throws IOException {
        var property = defaults.find("Emitters", owner);
        if (property == null
                || property.getArray() == null
                || property.getArray().getObjects() == null) {
            return;
        }
        var slots = property.getArray().getObjects();
        var moved = movedSlots(slots, removed);
        slots.removeAll(removed);
        for (var entry : trees.entrySet()) {
            if (EMITTER_CLASSES.contains(owner.getClassName(entry.getKey()))) {
                EmitterProperties.renumber(
                        entry.getValue(), owner.getClassName(entry.getKey()).equals("TrailEmitter"), moved, owner);
            }
        }
    }

    static int @Nullable [] movedSlots(ClassPackageWriter owner, int classExport, Set<Integer> removed)
            throws IOException {
        var data = ClassExportData.read(owner.getPayload(classExport), owner.exportName(classExport), owner);
        var property = data == null ? null : data.defaults.find("Emitters", owner);
        if (property == null
                || property.getArray() == null
                || property.getArray().getObjects() == null) {
            return null;
        }
        return movedSlots(property.getArray().getObjects(), removed);
    }

    static @Nullable String nameProblem(String name) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
            return "A class name must start with a letter, hold only letters, digits and underscores, and be at most"
                    + " 63 characters long";
        }
        if (Character.isDigit(name.charAt(name.length() - 1))) {
            return "A class name must not end in a digit";
        }
        return name.equalsIgnoreCase("None") ? "None is a reserved name" : null;
    }

    int copyClass(int classExport, String newName, Set<Integer> omitted) throws IOException {
        var reason = refusal(source, classExport);
        if (reason != null) {
            throw malformed(reason);
        }
        return copy(classExport, newName, omitted);
    }

    int copyForPreview(int classExport, String newName, Set<Integer> omitted) throws IOException {
        return copy(classExport, newName, omitted);
    }

    int copyEmitter(int emitter, int outerClass, String name, boolean keepLinks, int @Nullable [] movedSlots)
            throws IOException {
        var row = source.export(emitter);
        if ((row.flags() & RF_HAS_STACK) != 0) {
            throw malformed("Emitter " + source.exportName(emitter) + " has a state frame");
        }
        var tree = readTree(emitter);
        remap(tree);
        if (!keepLinks) {
            EmitterProperties.clearLinks(tree, target);
        } else if (movedSlots != null) {
            EmitterProperties.renumber(tree, source.getClassName(emitter).equals("TrailEmitter"), movedSlots, target);
        }
        return target.addExport(object(row.classIndex()), 0, outerClass, name, row.flags(), tree.toBytes());
    }

    int getMoved(int export) {
        return exports.getOrDefault(export, 0);
    }

    private int copy(int classExport, String newName, Set<Integer> omitted) throws IOException {
        var row = source.export(classExport);
        var oldName = source.getName(row.objectName());
        var data = ClassExportData.read(source.getPayload(classExport), oldName, source);
        if (data == null) {
            throw malformed("Class " + oldName + " has script code or an unknown layout");
        }
        var moved = new ArrayList<Integer>();
        var text = 0;
        for (var child : source.getChildren(classExport)) {
            var kind = source.getClassName(child);
            if (kind.equals("TextBuffer")) {
                text = child;
            } else if ((kind.equals(ACTION_CLASS) || EMITTER_CLASSES.contains(kind)) && !omitted.contains(child)) {
                moved.add(child);
            }
        }
        for (var list : SLOT_LISTS) {
            var slots = data.defaults.find(list, source);
            if (slots != null && slots.getArray() != null && slots.getArray().getObjects() != null) {
                for (var slot : slots.getArray().getObjects()) {
                    if (slot > 0 && !moved.contains(slot) && !omitted.contains(slot)) {
                        moved.add(slot);
                    }
                }
            }
        }
        var trees = new LinkedHashMap<Integer, TaggedProperties>();
        for (var export : moved) {
            if ((source.export(export).flags() & RF_HAS_STACK) != 0) {
                throw malformed("Object " + source.exportName(export) + " has a state frame");
            }
            trees.put(export, readTree(export));
        }
        if (!omitted.isEmpty()) {
            removeSlots(source, data.defaults, trees, omitted);
        }

        var newClass = target.addExport(0, object(row.superIndex()), 0, newName, row.flags(), new byte[0]);
        exports.put(classExport, newClass);
        var newText = 0;
        if (text != 0) {
            var textRow = source.export(text);
            newText = target.addExport(
                    object(textRow.classIndex()),
                    0,
                    newClass,
                    source.getName(textRow.objectName()),
                    textRow.flags(),
                    new byte[0]);
        }
        for (var export : moved) {
            var child = source.export(export);
            var name = source.getName(child.objectName());
            if (child.outer() != classExport) {
                name = target.freeChildName(newClass, source.getClassName(export));
            }
            exports.put(
                    export,
                    target.addExport(object(child.classIndex()), 0, newClass, name, child.flags(), new byte[0]));
        }
        for (var entry : trees.entrySet()) {
            remap(entry.getValue());
            target.replace(exports.get(entry.getKey()), entry.getValue().toBytes());
        }
        if (text != 0) {
            target.replace(newText, scriptText(source.getPayload(text), oldName, newName));
        }
        target.replace(newClass, classPayload(data, oldName, newName, newText));
        return newClass;
    }

    private byte[] classPayload(ClassExportData data, String oldName, String newName, int newText) throws IOException {
        var tag = data.defaults.find("Tag", source);
        remap(data.defaults);
        if (tag != null
                && tag.getType() == TaggedProperties.NAME
                && target.getName(tag.getIndex()).equalsIgnoreCase(oldName)) {
            tag.setIndex(target.intern(newName));
        }
        data.superField = object(data.superField);
        data.next = 0;
        data.scriptText = newText;
        data.children = 0;
        data.friendlyName = target.intern(newName);
        data.cppText = object(data.cppText);
        var dependencies = new ArrayList<Dependency>(data.dependencies.size());
        for (var dependency : data.dependencies) {
            if (dependency.object() <= 0 || exports.containsKey(dependency.object())) {
                dependencies.add(new Dependency(object(dependency.object()), dependency.deep(), dependency.crc()));
            }
        }
        data.dependencies.clear();
        data.dependencies.addAll(dependencies);
        var packages = new ArrayList<Integer>(data.packageImports.size());
        for (var name : data.packageImports) {
            var text = source.getName(name);
            packages.add(
                    text.equalsIgnoreCase(source.getPackageName())
                            ? target.intern(target.getPackageName())
                            : name(name));
        }
        data.packageImports.clear();
        data.packageImports.addAll(packages);
        data.classWithin = object(data.classWithin);
        data.configName = name(data.configName);
        if (data.hideCategories != null) {
            data.hideCategories.replaceAll(this::name);
        }
        return data.write();
    }

    private byte[] scriptText(byte[] payload, String oldName, String newName) throws IOException {
        var cursor = new Cursor(payload, 0, payload.length);
        var tags = TaggedProperties.read(cursor, source);
        remap(tags);
        var pos = cursor.readI32();
        var top = cursor.readI32();
        var text = cursor.readString();
        var header = Pattern.compile("(?i)(\\bclass\\s+)" + Pattern.quote(oldName) + "\\b")
                .matcher(text);
        var renamed = header.replaceFirst("$1" + Matcher.quoteReplacement(newName));
        var out = new ByteArrayOutputStream();
        tags.write(out);
        writeInt(out, pos);
        writeInt(out, top);
        TaggedProperties.writeString(out, renamed);
        return out.toByteArray();
    }

    private TaggedProperties readTree(int export) throws IOException {
        var payload = source.getPayload(export);
        var cursor = new Cursor(payload, 0, payload.length);
        var tree = TaggedProperties.read(cursor, source);
        if (cursor.position != cursor.end) {
            throw malformed("Object " + source.exportName(export) + " has a native tail");
        }
        return tree;
    }

    private void remap(TaggedProperties tree) throws IOException {
        if (samePackage) {
            tree.remapObjects(this::object);
        } else {
            tree.remap(this::name, this::object, source);
        }
    }

    private int name(int index) {
        if (samePackage) {
            return index;
        }
        return names.computeIfAbsent(index, at -> {
            try {
                return target.intern(source.getName(at));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private int object(int reference) {
        try {
            return objectOf(reference);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private int objectOf(int reference) throws IOException {
        if (reference == 0) {
            return 0;
        }
        if (reference > 0) {
            var moved = exports.get(reference);
            if (moved != null) {
                return moved;
            }
            return samePackage ? reference : importOfExport(reference);
        }
        if (samePackage) {
            return reference;
        }
        var mapped = imports.get(reference);
        if (mapped == null) {
            var entry = source.importEntry(reference);
            mapped = target.importObject(
                    objectOf(entry.outer()),
                    source.getName(entry.classPackage()),
                    source.getName(entry.className()),
                    source.getName(entry.objectName()));
            imports.put(reference, mapped);
        }
        return mapped;
    }

    private int importOfExport(int export) throws IOException {
        var row = source.export(export);
        var outer = row.outer() == 0 ? target.importPackage(source.getPackageName()) : importOfExport(row.outer());
        if (row.classIndex() == 0) {
            return target.importObject(outer, "Core", "Class", source.getName(row.objectName()));
        }
        var classPackage = "Core";
        if (row.classIndex() < 0) {
            for (var at = row.classIndex(); at != 0; at = source.importEntry(at).outer()) {
                classPackage = source.getObjectName(at);
            }
        } else {
            classPackage = source.getPackageName();
        }
        return target.importObject(outer, classPackage, source.getClassName(export), source.getName(row.objectName()));
    }

    private static int[] movedSlots(List<Integer> slots, Set<Integer> removed) {
        var moved = new int[slots.size()];
        var next = 0;
        for (var i = 0; i < slots.size(); i++) {
            moved[i] = removed.contains(slots.get(i)) ? -1 : next++;
        }
        return moved;
    }

    private static List<String> slotLists() {
        var result = new ArrayList<String>(List.of("Emitters"));
        for (var phase : Phase.values()) {
            result.add(phase.getProperty());
        }
        return List.copyOf(result);
    }
}

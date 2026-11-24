package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.TaggedProperties.ARRAY;
import static com.rost.cruma.core.editing.TaggedProperties.BOOL;
import static com.rost.cruma.core.editing.TaggedProperties.BYTE;
import static com.rost.cruma.core.editing.TaggedProperties.FLOAT;
import static com.rost.cruma.core.editing.TaggedProperties.INT;
import static com.rost.cruma.core.editing.TaggedProperties.NAME;
import static com.rost.cruma.core.editing.TaggedProperties.OBJECT;
import static com.rost.cruma.core.editing.TaggedProperties.STR;
import static com.rost.cruma.core.editing.TaggedProperties.STRUCT;

import com.rost.cruma.core.editing.EffectEdits.Knobs;
import com.rost.cruma.core.editing.EffectEdits.PropertyEdit;
import com.rost.cruma.core.editing.TaggedProperties.Names;
import com.rost.cruma.core.editing.TaggedProperties.Property;
import com.rost.cruma.core.editing.TaggedProperties.Segment;
import com.rost.cruma.core.texture.TextureHue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class EmitterProperties {
    private static final Set<String> RANGES = lowerCase(
            "LifetimeRange",
            "InitialTimeRange",
            "InitialDelayRange",
            "SphereRadiusRange",
            "StartVelocityRadialRange",
            "BeamDistanceRange",
            "BranchProbability",
            "BranchSpawnAmountRange",
            "BranchHFPointsRange",
            "DynamicHFNoisePointsRange",
            "MeshNormalThresholdRange",
            "StartMassRange",
            "LineSegmentsRange",
            "TimeBeforeVisibleRange",
            "TimeBetweenSegmentsRange");
    private static final Set<String> RANGE_VECTORS = lowerCase(
            "StartSizeRange",
            "StartLocationRange",
            "StartLocationPolarRange",
            "StartVelocityRange",
            "ColorMultiplierRange",
            "VelocityLossRange",
            "RevolutionsPerSecondRange",
            "RevolutionCenterOffsetRange",
            "StartSpinRange",
            "SpinsPerSecondRange",
            "AddVelocityMultiplierRange",
            "LowFrequencyNoiseRange",
            "HighFrequencyNoiseRange",
            "DynamicHFNoiseRange",
            "DampingFactorRange",
            "RotationDampingFactorRange");
    private static final Set<String> VECTORS = lowerCase(
            "StartLocationOffset",
            "Acceleration",
            "MaxAbsVelocity",
            "RotationNormal",
            "ProjectionNormal",
            "SpinCCWorCW",
            "MeshNormal");
    private static final Map<String, String> ELEMENT_FIELDS = Map.of(
            "colorscale.color", "Color",
            "velocityscale.relativevelocity", "Vector",
            "revolutionscale.relativerevolution", "Vector",
            "hfscalefactors.frequencyscale", "Vector",
            "lfscalefactors.frequencyscale", "Vector",
            "beamendpoints.offset", "RangeVector");
    private static final Set<String> TAGGED_ELEMENT_ARRAYS = lowerCase(
            "ColorScale",
            "SizeScale",
            "VelocityScale",
            "RevolutionScale",
            "BeamEndPoints",
            "HFScaleFactors",
            "LFScaleFactors",
            "CollisionPlanes");
    private static final List<String> LINKS = List.of(
            "SpawnFromOtherEmitter", "AddLocationFromOtherEmitter", "AddVelocityFromOtherEmitter", "BranchEmitter");
    private static final String ATTACH_LINK = "AttachEmitterIndex";
    private static final int NO_ATTACH_EMITTER = Integer.MAX_VALUE;
    private static final float DEFAULT_LIFETIME = 4f;
    private static final int DEFAULT_MAX_PARTICLES = 10;

    private EmitterProperties() {}

    static @Nullable String structOf(String parent, String field) {
        var key = field.toLowerCase(Locale.ROOT);
        if (parent.equalsIgnoreCase("RangeVector")) {
            return "Range";
        }
        if (RANGES.contains(key)) {
            return "Range";
        }
        if (RANGE_VECTORS.contains(key)) {
            return "RangeVector";
        }
        return VECTORS.contains(key) ? "Vector" : null;
    }

    static void renumber(TaggedProperties emitter, boolean trail, int[] slots, Names names) throws IOException {
        for (var link : LINKS) {
            var property = emitter.find(link, names);
            if (property != null) {
                var slot = property.getIntOrFloat();
                if (slot >= 0 && slot < slots.length && slots[slot] != slot) {
                    property.setNumber(slots[slot]);
                }
            }
        }
        if (trail) {
            var property = emitter.find(ATTACH_LINK, names);
            var slot = property == null ? 0 : property.getIntOrFloat();
            if (slot >= 0 && slot < slots.length && slots[slot] != slot) {
                var moved = slots[slot] < 0 ? NO_ATTACH_EMITTER : slots[slot];
                if (property == null) {
                    property = emitter.add(names.intern(ATTACH_LINK), INT);
                }
                property.setNumber(moved);
            }
        }
    }

    static void clearLinks(TaggedProperties emitter, Names names) throws IOException {
        for (var link : LINKS) {
            emitter.remove(link, 0, names);
        }
        var property = emitter.find(ATTACH_LINK, names);
        if (property != null) {
            property.setNumber(NO_ATTACH_EMITTER);
        }
    }

    static Property ensure(TaggedProperties root, String path, int leafType, @Nullable String leafStruct, Names names)
            throws IOException {
        var block = root;
        var parent = "";
        var parts = path.split("\\.");
        var array = "";
        for (var i = 0; i < parts.length; i++) {
            var segment = Segment.of(parts[i]);
            var last = i == parts.length - 1;
            var head = block.find(segment.name(), 0, names);
            var isArray = head != null
                    ? head.getType() == ARRAY
                    : TAGGED_ELEMENT_ARRAYS.contains(segment.name().toLowerCase(Locale.ROOT));
            if (segment.isElement() && isArray) {
                if (last) {
                    throw malformed("Path " + path + " ends at an array element, it needs a field");
                }
                if (head == null) {
                    head = block.add(names.intern(segment.name()), ARRAY);
                    head.makeEmptyArray(false);
                }
                var elements = head.getArray() == null ? null : head.getArray().getElements();
                if (elements == null || segment.index() > elements.size()) {
                    throw malformed("Path " + path + " has no such array element");
                }
                if (segment.index() == elements.size()) {
                    elements.add(TaggedProperties.empty(names));
                }
                block = elements.get(segment.index());
                array = segment.name();
                continue;
            }
            var target = segment.isElement() ? block.find(segment.name(), segment.index(), names) : head;
            if (target == null) {
                if (last) {
                    target = block.add(names.intern(segment.name()), leafType).setArrayIndex(segment.index());
                    if (leafStruct != null) {
                        target.setStructName(names.intern(leafStruct));
                    }
                    if (leafType == STRUCT && leafStruct == null) {
                        throw malformed("Path " + path + " needs a struct type");
                    }
                    return target;
                }
                var struct = array.isEmpty()
                        ? structOf(parent, segment.name())
                        : ELEMENT_FIELDS.get((array + "." + segment.name()).toLowerCase(Locale.ROOT));
                if (struct == null) {
                    throw malformed("The struct of " + segment.name() + " is not known, so it cannot be added");
                }
                target = block.add(names.intern(segment.name()), STRUCT).setStructName(names.intern(struct));
                target.makeEmptyStruct(names);
            }
            if (last) {
                return target;
            }
            if (target.getStruct() == null) {
                throw malformed("Property " + segment.name() + " is not a tagged struct");
            }
            block = target.getStruct();
            parent = names.getName(target.getStructName());
            array = "";
        }
        throw malformed("Empty property path");
    }

    static void apply(TaggedProperties root, PropertyEdit edit, Names names, ObjectIndex object) throws IOException {
        var path = edit.path();
        switch (edit.kind()) {
            case REMOVE -> remove(root, path, names);
            case BOOL -> {
                var value = Boolean.parseBoolean(edit.value());
                var property = prepared(root, path, BOOL, null, names);
                if (property.getType() == BOOL) {
                    property.setBool(value);
                } else {
                    property.setNumber(value ? 1 : 0);
                }
            }
            case BYTE, INT, FLOAT -> {
                var type = switch (edit.kind()) {
                    case BYTE -> BYTE;
                    case INT -> INT;
                    default -> FLOAT;
                };
                prepared(root, path, type, null, names).setNumber(Double.parseDouble(edit.value()));
            }
            case STR -> prepared(root, path, STR, null, names).setString(edit.value());
            case NAME -> prepared(root, path, NAME, null, names).setIndex(names.intern(edit.value()));
            case OBJECT -> setObject(root, path, object.of(edit.value(), lastName(path)), names);
            case VECTOR -> {
                var values = numbers(edit.value(), 3);
                var property = prepared(root, path, STRUCT, "Vector", names);
                property.setRaw(new byte[12]);
                property.getBinary()
                        .putFloat(0, (float) values[0])
                        .putFloat(4, (float) values[1])
                        .putFloat(8, (float) values[2]);
            }
            case ROTATOR -> {
                var values = numbers(edit.value(), 3);
                var property = prepared(root, path, STRUCT, "Rotator", names);
                property.setRaw(new byte[12]);
                property.getBinary()
                        .putInt(0, (int) values[0])
                        .putInt(4, (int) values[1])
                        .putInt(8, (int) values[2]);
            }
            case COLOR -> {
                var values = numbers(edit.value(), 4);
                var property = prepared(root, path, STRUCT, "Color", names);
                property.setRaw(new byte[] {(byte) values[2], (byte) values[1], (byte) values[0], (byte) values[3]});
            }
        }
    }

    @FunctionalInterface
    interface ObjectIndex {
        int of(String path, String property) throws IOException;
    }

    static void applyKnobs(TaggedProperties emitter, Knobs knobs, Names names) throws IOException {
        scaleRangeVector(emitter, "StartSizeRange", knobs.size(), 1f, names);
        scaleVector(emitter, "StartLocationOffset", knobs.spread(), names);
        scaleRangeVector(emitter, "StartLocationRange", knobs.spread(), 0f, names);
        scaleAxis(emitter, "StartLocationPolarRange", "Z", knobs.spread(), names);
        scaleRange(emitter, "SphereRadiusRange", knobs.spread(), 0f, names);
        scaleRangeVector(emitter, "StartVelocityRange", knobs.speed(), 0f, names);
        scaleRange(emitter, "StartVelocityRadialRange", knobs.speed(), 0f, names);
        scaleVector(emitter, "Acceleration", knobs.speed(), names);
        scaleVector(emitter, "MaxAbsVelocity", knobs.speed(), names);
        scaleRange(emitter, "LifetimeRange", knobs.lifetime(), DEFAULT_LIFETIME, names);
        scaleFloat(emitter, "FadeInEndTime", knobs.lifetime(), 0f, Float.MAX_VALUE, names);
        scaleFloat(emitter, "FadeOutStartTime", knobs.lifetime(), 0f, Float.MAX_VALUE, names);
        scaleCount(emitter, knobs.count(), names);
        scaleFloat(emitter, "InitialParticlesPerSecond", knobs.count(), 0f, Float.MAX_VALUE, names);
        scaleFloat(emitter, "ParticlesPerSecond", knobs.count(), 0f, Float.MAX_VALUE, names);
        scaleFloat(emitter, "Opacity", knobs.opacity(), 1f, 1f, names);
        applyColor(emitter, knobs.red(), knobs.green(), knobs.blue(), TextureHue.normalize(knobs.hue()), names);
    }

    static float rangeBound(
            TaggedProperties emitter,
            String property,
            @Nullable String axis,
            String bound,
            float absent,
            float missingField,
            Names names)
            throws IOException {
        var tag = emitter.find(property, names);
        if (tag == null || tag.getStruct() == null) {
            return absent;
        }
        var block = tag.getStruct();
        if (axis != null) {
            var axisTag = block.find(axis, names);
            if (axisTag == null || axisTag.getStruct() == null) {
                return missingField;
            }
            block = axisTag.getStruct();
        }
        var field = block.find(bound, names);
        return field == null ? missingField : field.getFloatValue();
    }

    private static void remove(TaggedProperties root, String path, Names names) throws IOException {
        var dot = path.lastIndexOf('.');
        var block = root;
        if (dot >= 0) {
            var parent = root.at(path.substring(0, dot), names);
            if (parent == null) {
                return;
            }
            block = parent.getStruct();
            var last = Segment.of(path.substring(path.lastIndexOf('.', dot - 1) + 1, dot));
            if (last.isElement()
                    && parent.getArray() != null
                    && parent.getArray().getElements() != null) {
                block = parent.getArray().getElements().get(last.index());
            }
            if (block == null) {
                return;
            }
        }
        var segment = Segment.of(path.substring(dot + 1));
        var head = block.find(segment.name(), 0, names);
        if (segment.isElement() && head != null && head.getArray() != null) {
            var array = head.getArray();
            if (array.getElements() != null
                    && segment.index() < array.getElements().size()) {
                array.getElements().remove(segment.index());
            } else if (array.getObjects() != null
                    && segment.index() < array.getObjects().size()) {
                array.getObjects().remove(segment.index());
            }
            return;
        }
        block.remove(segment.name(), segment.isElement() ? segment.index() : 0, names);
    }

    private static void setObject(TaggedProperties root, String path, int index, Names names) throws IOException {
        var dot = path.lastIndexOf('.');
        var segment = Segment.of(path.substring(dot + 1));
        if (segment.isElement()) {
            var array = root.at(dot < 0 ? segment.name() : path.substring(0, dot + 1) + segment.name(), names);
            if (array != null && array.getArray() != null && array.getArray().getObjects() != null) {
                var objects = array.getArray().getObjects();
                if (segment.index() == objects.size()) {
                    objects.add(index);
                } else if (segment.index() < objects.size()) {
                    objects.set(segment.index(), index);
                } else {
                    throw malformed("Path " + path + " has no such array element");
                }
                return;
            }
        }
        prepared(root, path, OBJECT, null, names).setIndex(index);
    }

    private static Property prepared(TaggedProperties root, String path, int type, @Nullable String struct, Names names)
            throws IOException {
        if (path.toLowerCase(Locale.ROOT).startsWith("lifetimerange.") && root.find("LifetimeRange", names) == null) {
            ensure(root, "LifetimeRange.Min", FLOAT, null, names).setFloatValue(DEFAULT_LIFETIME);
            ensure(root, "LifetimeRange.Max", FLOAT, null, names).setFloatValue(DEFAULT_LIFETIME);
        }
        return ensure(root, path, type, struct, names);
    }

    private static String lastName(String path) throws IOException {
        var dot = path.lastIndexOf('.');
        return Segment.of(path.substring(dot + 1)).name();
    }

    private static double[] numbers(String text, int count) throws IOException {
        var parts = text.split(",");
        if (parts.length != count) {
            throw malformed("Expected " + count + " numbers in " + text);
        }
        var result = new double[count];
        for (var i = 0; i < count; i++) {
            result[i] = Double.parseDouble(parts[i].trim());
        }
        return result;
    }

    private static void scaleFloat(
            TaggedProperties emitter, String name, double factor, float absent, float limit, Names names)
            throws IOException {
        if (factor == 1) {
            return;
        }
        var tag = emitter.find(name, names);
        var base = tag == null ? absent : tag.getType() == INT ? tag.getIntValue() : tag.getFloatValue();
        var scaled = (float) Math.max(0, Math.min(limit, base * factor));
        if (scaled == base) {
            return;
        }
        if (tag == null) {
            tag = emitter.add(names.intern(name), FLOAT);
        }
        tag.setNumber(scaled);
    }

    private static void scaleCount(TaggedProperties emitter, double factor, Names names) throws IOException {
        if (factor == 1) {
            return;
        }
        var tag = emitter.find("MaxParticles", names);
        var base = tag == null ? DEFAULT_MAX_PARTICLES : tag.getIntOrFloat();
        var scaled = (int) Math.max(1, Math.round(base * factor));
        if (scaled == base) {
            return;
        }
        if (tag == null) {
            tag = emitter.add(names.intern("MaxParticles"), INT);
        }
        tag.setNumber(scaled);
    }

    private static void scaleVector(TaggedProperties emitter, String name, double factor, Names names)
            throws IOException {
        if (factor == 1) {
            return;
        }
        var tag = emitter.find(name, names);
        if (tag == null) {
            return;
        }
        var values = tag.getBinary();
        for (var offset = 0; offset < 12; offset += 4) {
            values.putFloat(offset, (float) (values.getFloat(offset) * factor));
        }
    }

    private static void scaleRange(TaggedProperties emitter, String name, double factor, float absent, Names names)
            throws IOException {
        if (factor == 1) {
            return;
        }
        var min = rangeBound(emitter, name, null, "Min", absent, 0f, names);
        var max = rangeBound(emitter, name, null, "Max", absent, 0f, names);
        writeBound(emitter, name, "Min", min, (float) (min * factor), 0f, names);
        writeBound(emitter, name, "Max", max, (float) (max * factor), 0f, names);
    }

    private static void scaleRangeVector(
            TaggedProperties emitter, String name, double factor, float absent, Names names) throws IOException {
        if (factor == 1) {
            return;
        }
        for (var axis : List.of("X", "Y", "Z")) {
            scaleAxis(emitter, name, axis, factor, absent, names);
        }
    }

    private static void scaleAxis(TaggedProperties emitter, String name, String axis, double factor, Names names)
            throws IOException {
        if (factor != 1) {
            scaleAxis(emitter, name, axis, factor, 0f, names);
        }
    }

    private static void scaleAxis(
            TaggedProperties emitter, String name, String axis, double factor, float absent, Names names)
            throws IOException {
        var min = rangeBound(emitter, name, axis, "Min", absent, absent, names);
        var max = rangeBound(emitter, name, axis, "Max", absent, absent, names);
        writeBound(emitter, name + "." + axis, "Min", min, (float) (min * factor), absent, names);
        writeBound(emitter, name + "." + axis, "Max", max, (float) (max * factor), absent, names);
    }

    private static void writeBound(
            TaggedProperties emitter, String path, String bound, float before, float after, float fallback, Names names)
            throws IOException {
        if (after == before) {
            return;
        }
        var full = path + "." + bound;
        if (emitter.at(full, names) == null && after == fallback && emitter.at(path, names) != null) {
            return;
        }
        ensure(emitter, full, FLOAT, null, names).setFloatValue(after);
    }

    private static void applyColor(
            TaggedProperties emitter, double red, double green, double blue, int degrees, Names names)
            throws IOException {
        if (red == 1 && green == 1 && blue == 1 && degrees == 0) {
            return;
        }
        var factors = new double[] {red, green, blue};
        var axes = List.of("X", "Y", "Z");
        var bounds = new ArrayList<float[]>();
        var before = new ArrayList<float[]>();
        for (var bound : List.of("Min", "Max")) {
            var values = new float[3];
            var scaled = new float[3];
            for (var axis = 0; axis < 3; axis++) {
                values[axis] = rangeBound(emitter, "ColorMultiplierRange", axes.get(axis), bound, 1f, 1f, names);
                scaled[axis] = (float) Math.min(values[axis] * factors[axis], Math.max(1f, values[axis]));
            }
            before.add(values);
            bounds.add(degrees == 0 ? scaled : TextureHue.rotate(scaled[0], scaled[1], scaled[2], degrees));
        }
        for (var axis = 0; axis < 3; axis++) {
            var path = "ColorMultiplierRange." + axes.get(axis);
            writeBound(emitter, path, "Min", before.get(0)[axis], bounds.get(0)[axis], 1f, names);
            writeBound(emitter, path, "Max", before.get(1)[axis], bounds.get(1)[axis], 1f, names);
        }
        if (degrees != 0) {
            rotateColorScale(emitter, degrees, names);
        }
    }

    private static void rotateColorScale(TaggedProperties emitter, int degrees, Names names) throws IOException {
        var scale = emitter.find("ColorScale", names);
        if (scale == null || scale.getArray() == null || scale.getArray().getElements() == null) {
            return;
        }
        for (var element : scale.getArray().getElements()) {
            var color = element.find("Color", names);
            if (color != null && color.getRaw().length == 4) {
                var raw = color.getRaw();
                var rgb = TextureHue.rotate(raw[2] & 255, raw[1] & 255, raw[0] & 255, degrees);
                color.setRaw(new byte[] {(byte) rgb, (byte) (rgb >>> 8), (byte) (rgb >>> 16), raw[3]});
            }
        }
    }

    private static Set<String> lowerCase(String... names) {
        var result = new HashSet<String>();
        for (var name : names) {
            result.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }
}

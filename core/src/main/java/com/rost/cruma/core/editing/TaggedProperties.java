package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.PackageBytes.writeIndex;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import org.jetbrains.annotations.Nullable;

final class TaggedProperties {
    static final int BYTE = 1;
    static final int INT = 2;
    static final int BOOL = 3;
    static final int FLOAT = 4;
    static final int OBJECT = 5;
    static final int NAME = 6;
    static final int ARRAY = 9;
    static final int STRUCT = 10;
    static final int STR = 13;

    private static final Set<String> OBJECT_ARRAYS = lowerCase(
            "Emitters",
            "CustomMaterials",
            "Skins",
            "SpawnSound",
            "ManualExcludes",
            "CastingAction",
            "ChannelingAction",
            "PreshotAction",
            "ShotAction",
            "ExplosionAction",
            "UnionTargetAction",
            "SceneInfo",
            "Actions",
            "SubActions");
    private static final Set<String> TAGGED_ARRAYS = lowerCase(
            "ColorScale",
            "SizeScale",
            "VelocityScale",
            "RevolutionScale",
            "BeamEndPoints",
            "HFScaleFactors",
            "LFScaleFactors",
            "CollisionPlanes",
            "WeatherRangeTime",
            "ControlPoints",
            "NpcInfo",
            "PcInfo",
            "VisualItemList",
            "CastingActions",
            "ChannelingActions",
            "PreshotActions",
            "ShotActions",
            "ExplosionActions");
    private static final Set<String> PLAIN_ARRAYS =
            lowerCase("SubdivisionScale", "AnimRate", "ControlPoints", "ControlPointOffset");
    private static final Set<String> BINARY_STRUCTS = lowerCase("Vector", "Rotator", "Color");

    private final List<Property> properties = new ArrayList<>();
    private int none;

    private TaggedProperties(int none) {
        this.none = none;
    }

    interface Names {
        String getName(int index) throws IOException;

        int intern(String name);
    }

    static TaggedProperties empty(Names names) {
        return new TaggedProperties(names.intern("None"));
    }

    static TaggedProperties read(Cursor cursor, Names names) throws IOException {
        var result = new TaggedProperties(0);
        while (true) {
            var name = cursor.readIndex();
            var text = names.getName(name);
            if (text.equalsIgnoreCase("None")) {
                result.none = name;
                return result;
            }
            var info = cursor.readU8();
            var type = info & 15;
            if (type == 0) {
                throw malformed("Invalid property type");
            }
            var struct = type == STRUCT ? cursor.readIndex() : -1;
            var size = switch (info >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> cursor.readU8();
                case 6 -> cursor.readU16();
                default -> cursor.readI32();
            };
            var property = new Property(name, type);
            property.structName = struct;
            if (type == BOOL) {
                property.bool = (info & 128) != 0;
                result.properties.add(property);
                continue;
            }
            property.arrayIndex = (info & 128) != 0 ? cursor.readArrayIndex() : 0;
            if (size < 0 || (long) cursor.position + size > cursor.end) {
                throw malformed("Truncated property value");
            }
            var valueEnd = cursor.position + size;
            property.readValue(new Cursor(cursor.bytes, cursor.position, valueEnd), text, names);
            cursor.position = valueEnd;
            result.properties.add(property);
        }
    }

    static void writeString(ByteArrayOutputStream out, String value) {
        if (value.isEmpty()) {
            writeIndex(out, 0);
        } else if (value.chars().allMatch(c -> c < 256)) {
            writeIndex(out, value.length() + 1);
            out.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
            out.write(0);
        } else {
            writeIndex(out, -(value.length() + 1));
            out.writeBytes(value.getBytes(StandardCharsets.UTF_16LE));
            out.write(0);
            out.write(0);
        }
    }

    void write(ByteArrayOutputStream out) {
        for (var property : properties) {
            property.write(out);
        }
        writeIndex(out, none);
    }

    byte[] toBytes() {
        var out = new ByteArrayOutputStream();
        write(out);
        return out.toByteArray();
    }

    List<Property> getProperties() {
        return properties;
    }

    boolean isEmpty() {
        return properties.isEmpty();
    }

    @Nullable
    Property find(String name, int arrayIndex, Names names) throws IOException {
        for (var property : properties) {
            if (property.arrayIndex == arrayIndex
                    && names.getName(property.name).equalsIgnoreCase(name)) {
                return property;
            }
        }
        return null;
    }

    @Nullable
    Property find(String name, Names names) throws IOException {
        return find(name, 0, names);
    }

    @Nullable
    Property at(String path, Names names) throws IOException {
        var block = this;
        Property property = null;
        for (var text : path.split("\\.")) {
            if (block == null) {
                return null;
            }
            var segment = Segment.of(text);
            var head = block.find(segment.name(), 0, names);
            if (segment.isElement() && head != null && head.type == ARRAY) {
                var elements = head.array == null ? null : head.array.elements;
                if (elements == null || segment.index() >= elements.size()) {
                    return null;
                }
                property = head;
                block = elements.get(segment.index());
                continue;
            }
            property = segment.isElement() ? block.find(segment.name(), segment.index(), names) : head;
            if (property == null) {
                return null;
            }
            block = property.struct;
        }
        return property;
    }

    Property add(int name, int type) {
        var property = new Property(name, type);
        properties.add(property);
        return property;
    }

    boolean remove(String name, int arrayIndex, Names names) throws IOException {
        var property = find(name, arrayIndex, names);
        return property != null && properties.remove(property);
    }

    void remap(IntUnaryOperator name, IntUnaryOperator object, Names source) throws IOException {
        none = name.applyAsInt(none);
        for (var property : properties) {
            property.remap(name, object, source);
        }
    }

    void remapObjects(IntUnaryOperator object) throws IOException {
        for (var property : properties) {
            property.remapObjects(object);
        }
    }

    TaggedProperties copy() {
        var result = new TaggedProperties(none);
        for (var property : properties) {
            result.properties.add(property.copy());
        }
        return result;
    }

    private static Set<String> lowerCase(String... names) {
        var result = new HashSet<String>();
        for (var name : names) {
            result.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private static void writeArrayIndex(ByteArrayOutputStream out, int index) {
        if (index < 128) {
            out.write(index);
        } else if (index < 16384) {
            out.write(index >> 8 | 0x80);
            out.write(index);
        } else {
            out.write(index >>> 24 | 0xc0);
            out.write(index >>> 16);
            out.write(index >>> 8);
            out.write(index);
        }
    }

    private static byte[] compact(int value) {
        var out = new ByteArrayOutputStream(5);
        writeIndex(out, value);
        return out.toByteArray();
    }

    private static ByteBuffer little(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    record Segment(String name, int index, boolean isElement) {
        static Segment of(String text) throws IOException {
            var open = text.indexOf('[');
            if (open < 0) {
                return new Segment(text, 0, false);
            }
            if (!text.endsWith("]")) {
                throw malformed("Invalid property path step " + text);
            }
            try {
                return new Segment(
                        text.substring(0, open), Integer.parseInt(text.substring(open + 1, text.length() - 1)), true);
            } catch (NumberFormatException failure) {
                throw malformed("Invalid property path step " + text);
            }
        }
    }

    static final class Property {
        private int name;
        private final int type;
        private int structName = -1;
        private int arrayIndex;
        private boolean bool;
        private byte[] raw = new byte[0];
        private @Nullable TaggedProperties struct;
        private @Nullable ArrayValue array;

        private Property(int name, int type) {
            this.name = name;
            this.type = type;
        }

        int name() {
            return name;
        }

        int getType() {
            return type;
        }

        int getArrayIndex() {
            return arrayIndex;
        }

        Property setArrayIndex(int value) {
            arrayIndex = value;
            return this;
        }

        int getStructName() {
            return structName;
        }

        Property setStructName(int value) {
            structName = value;
            return this;
        }

        @Nullable
        TaggedProperties getStruct() {
            return struct;
        }

        TaggedProperties makeEmptyStruct(Names names) {
            struct = TaggedProperties.empty(names);
            return struct;
        }

        @Nullable
        ArrayValue getArray() {
            return array;
        }

        ArrayValue makeEmptyArray(boolean objects) {
            array = new ArrayValue();
            if (objects) {
                array.objects = new ArrayList<>();
            } else {
                array.elements = new ArrayList<>();
            }
            return array;
        }

        boolean isTrue() {
            return bool;
        }

        void setBool(boolean value) {
            bool = value;
        }

        float getFloatValue() {
            return little(raw).getFloat(0);
        }

        int getIntValue() {
            return little(raw).getInt(0);
        }

        int getIntOrFloat() {
            return type == FLOAT ? Math.round(getFloatValue()) : getIntValue();
        }

        int getByteValue() {
            return raw[0] & 255;
        }

        int getIndex() throws IOException {
            return new Cursor(raw, 0, raw.length).readIndex();
        }

        ByteBuffer getBinary() {
            return little(raw);
        }

        byte[] getRaw() {
            return raw;
        }

        void setRaw(byte[] value) {
            raw = value;
        }

        void setFloatValue(float value) {
            raw = little(new byte[4]).putFloat(0, value).array();
        }

        void setIntValue(int value) {
            raw = little(new byte[4]).putInt(0, value).array();
        }

        void setByteValue(int value) {
            raw = new byte[] {(byte) value};
        }

        void setIndex(int value) {
            raw = compact(value);
        }

        void setNumber(double value) throws IOException {
            switch (type) {
                case FLOAT -> setFloatValue((float) value);
                case INT -> setIntValue((int) Math.round(value));
                case BYTE -> setByteValue((int) Math.round(value));
                default -> throw malformed("Property type " + type + " holds no number");
            }
        }

        String getString() throws IOException {
            return new Cursor(raw, 0, raw.length).readString();
        }

        void setString(String value) {
            var out = new ByteArrayOutputStream();
            writeString(out, value);
            raw = out.toByteArray();
        }

        private void readValue(Cursor value, String text, Names names) throws IOException {
            if (type == STRUCT
                    && !BINARY_STRUCTS.contains(names.getName(structName).toLowerCase(Locale.ROOT))) {
                var nested = tagged(new Cursor(value.bytes, value.position, value.end), names);
                if (nested != null) {
                    struct = nested;
                    return;
                }
            }
            if (type == ARRAY) {
                array = ArrayValue.read(new Cursor(value.bytes, value.position, value.end), text, names);
                if (array != null) {
                    return;
                }
            }
            raw = Arrays.copyOfRange(value.bytes, value.position, value.end);
        }

        private void write(ByteArrayOutputStream out) {
            var value = new ByteArrayOutputStream();
            if (struct != null) {
                struct.write(value);
            } else if (array != null) {
                array.write(value);
            } else {
                value.writeBytes(raw);
            }
            var size = value.size();
            writeIndex(out, name);
            var code = type == BOOL ? 5 : sizeCode(size);
            out.write(type | code << 4 | ((type == BOOL ? bool : arrayIndex != 0) ? 128 : 0));
            if (type == STRUCT) {
                writeIndex(out, structName);
            }
            switch (code) {
                case 5 -> out.write(size);
                case 6 -> {
                    out.write(size);
                    out.write(size >>> 8);
                }
                case 7 -> PackageBytes.writeInt(out, size);
                default -> {
                }
            }
            if (type != BOOL && arrayIndex != 0) {
                writeArrayIndex(out, arrayIndex);
            }
            out.writeBytes(value.toByteArray());
        }

        private void remap(IntUnaryOperator names, IntUnaryOperator objects, Names source) throws IOException {
            var text = source.getName(name);
            if (struct == null && array == null) {
                if (type == ARRAY && !PLAIN_ARRAYS.contains(text.toLowerCase(Locale.ROOT))) {
                    throw malformed("Array " + text + " has an unknown layout and cannot move to another package");
                }
                if (type == STRUCT
                        && !BINARY_STRUCTS.contains(source.getName(structName).toLowerCase(Locale.ROOT))) {
                    throw malformed("Struct " + text + " has an unknown layout and cannot move to another package");
                }
            }
            name = names.applyAsInt(name);
            if (structName >= 0) {
                structName = names.applyAsInt(structName);
            }
            if (type == NAME) {
                setIndex(names.applyAsInt(getIndex()));
            } else if (type == OBJECT) {
                setIndex(objects.applyAsInt(getIndex()));
            } else if (struct != null) {
                struct.remap(names, objects, source);
            } else if (array != null) {
                array.remap(names, objects, source);
            }
        }

        private void remapObjects(IntUnaryOperator objects) throws IOException {
            if (type == OBJECT) {
                setIndex(objects.applyAsInt(getIndex()));
            } else if (struct != null) {
                struct.remapObjects(objects);
            } else if (array != null) {
                array.remapObjects(objects);
            }
        }

        private Property copy() {
            var result = new Property(name, type);
            result.structName = structName;
            result.arrayIndex = arrayIndex;
            result.bool = bool;
            result.raw = raw;
            result.struct = struct == null ? null : struct.copy();
            result.array = array == null ? null : array.copy();
            return result;
        }

        private static @Nullable TaggedProperties tagged(Cursor cursor, Names names) {
            try {
                var block = read(cursor, names);
                return cursor.position == cursor.end ? block : null;
            } catch (IOException | RuntimeException otherLayout) {
                return null;
            }
        }

        private static int sizeCode(int size) {
            return switch (size) {
                case 1 -> 0;
                case 2 -> 1;
                case 4 -> 2;
                case 12 -> 3;
                case 16 -> 4;
                default -> size < 256 ? 5 : size < 65536 ? 6 : 7;
            };
        }
    }

    static final class ArrayValue {
        private @Nullable List<Integer> objects;
        private @Nullable List<TaggedProperties> elements;

        private ArrayValue() {}

        @Nullable
        List<Integer> getObjects() {
            return objects;
        }

        @Nullable
        List<TaggedProperties> getElements() {
            return elements;
        }

        private void write(ByteArrayOutputStream out) {
            if (objects != null) {
                writeIndex(out, objects.size());
                for (var object : objects) {
                    writeIndex(out, object);
                }
            } else if (elements != null) {
                writeIndex(out, elements.size());
                for (var element : elements) {
                    element.write(out);
                }
            }
        }

        private void remap(IntUnaryOperator names, IntUnaryOperator objectMap, Names source) throws IOException {
            if (objects != null) {
                objects.replaceAll(objectMap::applyAsInt);
            } else if (elements != null) {
                for (var element : elements) {
                    element.remap(names, objectMap, source);
                }
            }
        }

        private void remapObjects(IntUnaryOperator objectMap) throws IOException {
            if (objects != null) {
                objects.replaceAll(objectMap::applyAsInt);
            } else if (elements != null) {
                for (var element : elements) {
                    element.remapObjects(objectMap);
                }
            }
        }

        private ArrayValue copy() {
            var result = new ArrayValue();
            result.objects = objects == null ? null : new ArrayList<>(objects);
            if (elements != null) {
                result.elements = new ArrayList<>(elements.size());
                for (var element : elements) {
                    result.elements.add(element.copy());
                }
            }
            return result;
        }

        private static @Nullable ArrayValue read(Cursor cursor, String name, Names names) {
            var key = name.toLowerCase(Locale.ROOT);
            try {
                if (OBJECT_ARRAYS.contains(key)) {
                    var count = PackageBytes.count(cursor.readIndex());
                    var result = new ArrayValue();
                    result.objects = new ArrayList<>(count);
                    for (var i = 0; i < count; i++) {
                        result.objects.add(cursor.readIndex());
                    }
                    return cursor.position == cursor.end ? result : null;
                }
                if (TAGGED_ARRAYS.contains(key)) {
                    var count = PackageBytes.count(cursor.readIndex());
                    var result = new ArrayValue();
                    result.elements = new ArrayList<>(count);
                    for (var i = 0; i < count; i++) {
                        result.elements.add(TaggedProperties.read(cursor, names));
                    }
                    return cursor.position == cursor.end ? result : null;
                }
            } catch (IOException | RuntimeException otherLayout) {
                return null;
            }
            return null;
        }
    }
}

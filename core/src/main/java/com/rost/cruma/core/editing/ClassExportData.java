package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.writeIndex;
import static com.rost.cruma.core.editing.PackageBytes.writeInt;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TaggedProperties.Names;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class ClassExportData {
    private static final int STATE_BYTES = 16 + 2 + 4 + 4;

    int superField;
    int next;
    int scriptText;
    int children;
    int friendlyName;
    int cppText;
    boolean hasCppText;
    int line;
    int textPosition;
    int scriptSize;
    byte[] state = new byte[0];
    byte[] guid = new byte[16];
    final List<Dependency> dependencies = new ArrayList<>();
    final List<Integer> packageImports = new ArrayList<>();
    int classWithin;
    int configName;

    @Nullable
    List<Integer> hideCategories;

    TaggedProperties defaults;

    private ClassExportData() {}

    record Dependency(int object, int deep, int crc) {}

    static @Nullable ClassExportData read(byte[] payload, String className, Names names) {
        for (var cppText : new boolean[] {true, false}) {
            for (var hideCategories : new boolean[] {true, false}) {
                var result = read(payload, className, names, cppText, hideCategories);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    byte[] write() {
        var out = new ByteArrayOutputStream();
        writeIndex(out, superField);
        writeIndex(out, next);
        writeIndex(out, scriptText);
        writeIndex(out, children);
        writeIndex(out, friendlyName);
        if (hasCppText) {
            writeIndex(out, cppText);
        }
        writeInt(out, line);
        writeInt(out, textPosition);
        writeInt(out, scriptSize);
        out.writeBytes(state);
        out.writeBytes(guid);
        writeIndex(out, dependencies.size());
        for (var dependency : dependencies) {
            writeIndex(out, dependency.object());
            writeInt(out, dependency.deep());
            writeInt(out, dependency.crc());
        }
        writeIndices(out, packageImports);
        writeIndex(out, classWithin);
        writeIndex(out, configName);
        if (hideCategories != null) {
            writeIndices(out, hideCategories);
        }
        defaults.write(out);
        return out.toByteArray();
    }

    private static @Nullable ClassExportData read(
            byte[] payload, String className, Names names, boolean cppText, boolean hideCategories) {
        try {
            var cursor = new Cursor(payload, 0, payload.length);
            var data = new ClassExportData();
            data.superField = cursor.readIndex();
            data.next = cursor.readIndex();
            data.scriptText = cursor.readIndex();
            data.children = cursor.readIndex();
            data.friendlyName = cursor.readIndex();
            if (!names.getName(data.friendlyName).equalsIgnoreCase(className)) {
                return null;
            }
            data.hasCppText = cppText;
            data.cppText = cppText ? cursor.readIndex() : 0;
            data.line = cursor.readI32();
            data.textPosition = cursor.readI32();
            data.scriptSize = cursor.readI32();
            if (data.scriptSize != 0) {
                return null;
            }
            data.state = bytes(cursor, STATE_BYTES);
            data.guid = bytes(cursor, 16);
            var dependencies = PackageBytes.count(cursor.readIndex());
            for (var i = 0; i < dependencies; i++) {
                data.dependencies.add(new Dependency(cursor.readIndex(), cursor.readI32(), cursor.readI32()));
            }
            readIndices(cursor, data.packageImports);
            data.classWithin = cursor.readIndex();
            data.configName = cursor.readIndex();
            if (hideCategories) {
                data.hideCategories = new ArrayList<>();
                readIndices(cursor, data.hideCategories);
            }
            data.defaults = TaggedProperties.read(cursor, names);
            return cursor.position == cursor.end ? data : null;
        } catch (IOException | RuntimeException otherLayout) {
            return null;
        }
    }

    private static byte[] bytes(Cursor cursor, int count) throws IOException {
        var start = cursor.position;
        cursor.skip(count);
        return Arrays.copyOfRange(cursor.bytes, start, cursor.position);
    }

    private static void readIndices(Cursor cursor, List<Integer> target) throws IOException {
        var count = PackageBytes.count(cursor.readIndex());
        for (var i = 0; i < count; i++) {
            target.add(cursor.readIndex());
        }
    }

    private static void writeIndices(ByteArrayOutputStream out, List<Integer> values) {
        writeIndex(out, values.size());
        for (var value : values) {
            writeIndex(out, value);
        }
    }
}

package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class TerrainTextureLayout {
    private TerrainTextureLayout() {}

    record Text(String value, int start, int end, int dataOffset, boolean wide) {}

    record Stage(Text material, List<Text> modifiers) {
        Stage {
            modifiers = List.copyOf(modifiers);
        }
    }

    record Prefix(List<Stage> stages, Text shader, List<Text> legacyTextures, int mipOffset) {
        Prefix {
            stages = List.copyOf(stages);
            legacyTextures = List.copyOf(legacyTextures);
        }
    }

    static boolean supportsReadOnly(int fileVersion, int licenseVersion) {
        return fileVersion >= 118 && fileVersion <= 133 && licenseVersion >= 0 && licenseVersion <= 40;
    }

    static Prefix readPrefix(PackageBytes source, Cursor cursor) throws IOException {
        if (!supportsReadOnly(source.fileVersion, source.licenseVersion)) {
            throw new IOException("Unaudited G16 material native prefix");
        }
        var legacy = new ArrayList<Text>();
        var stages = new ArrayList<Stage>();
        if (source.fileVersion < 123) {
            return new Prefix(
                    stages,
                    new Text("", cursor.position, cursor.position, cursor.position, false),
                    legacy,
                    cursor.position);
        }
        if (source.licenseVersion >= 16 && source.licenseVersion < 37) {
            cursor.skip(4);
        }
        if (source.licenseVersion > 29 && source.licenseVersion < 37) {
            cursor.skip((source.licenseVersion > 32 && source.licenseVersion < 36 ? 1 : 0)
                    + 18
                    + 8 * (source.licenseVersion < 36 ? 128 : 127)
                    + 20);
            for (var i = 0; i < 16; i++) {
                legacy.add(readText(cursor));
            }
        }
        if (source.licenseVersion >= 37) {
            cursor.skip(source.fileVersion < 129 ? 36 : 92);
            var count = requireBounded(cursor.readIndex(), 32, "material stages");
            for (var i = 0; i < count; i++) {
                var material = readText(cursor);
                var modifiers = new ArrayList<Text>();
                var modifierCount = requireBounded(cursor.readIndex(), 64, "stage modifiers");
                for (var j = 0; j < modifierCount; j++) {
                    modifiers.add(readText(cursor));
                }
                stages.add(new Stage(material, modifiers));
            }
        }
        var shader = source.licenseVersion > 29
                ? readText(cursor)
                : new Text("", cursor.position, cursor.position, cursor.position, false);
        if (source.licenseVersion > 30) {
            cursor.skip(4);
        }
        return new Prefix(stages, shader, legacy, cursor.position);
    }

    private static Text readText(Cursor cursor) throws IOException {
        var start = cursor.position;
        var count = cursor.readIndex();
        if (Math.abs((long) count) > 4 * 1024 * 1024) {
            throw new IOException("Material string exceeds the audited bound");
        }
        var data = cursor.position;
        cursor.position = start;
        var value = cursor.readString();
        if (value.indexOf('\0') >= 0) {
            throw new IOException("Material string contains an embedded terminator");
        }
        return new Text(value, start, cursor.position, data, count < 0);
    }

    private static int requireBounded(int count, int maximum, String name) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid " + name);
        }
        return count;
    }
}

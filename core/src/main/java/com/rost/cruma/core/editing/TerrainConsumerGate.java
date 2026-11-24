package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.IOException;

final class TerrainConsumerGate {
    private TerrainConsumerGate() {}

    static void requireEmptyModel(PackageBytes source, ExportEntry entry, int nativeOffset) throws IOException {
        var cursor = openCursor(source, entry, nativeOffset, "Model");
        for (var i = 0; i < 6; i++) {
            readFiniteFloat(cursor);
        }
        checkBoolByte(cursor, "Model bounds flag");
        for (var i = 0; i < 3; i++) {
            readFiniteFloat(cursor);
        }
        if (readFiniteFloat(cursor) < 0) {
            throw rejected("Model sphere radius");
        }
        for (var label : new String[] {"vectors", "points", "nodes", "surfaces", "vertices"}) {
            requireEmpty(cursor, "Model " + label);
        }
        if (cursor.readI32() != 0 || cursor.readI32() != 0) {
            throw rejected("Model shared sides or zones");
        }
        var polys = cursor.readIndex();
        if (polys != 0) {
            if (polys < 0 || polys > source.exports.size()) {
                throw rejected("external or invalid Model Polys");
            }
            var polygons = source.exports.get(polys - 1);
            var properties =
                    new Cursor(source.body, polygons.serialOffset(), polygons.serialOffset() + polygons.serialSize());
            NativeLevelEditor.skipProperties(source, properties, polygons.flags());
            requireEmptyPolys(source, polygons, properties.position);
        }
        for (var label : new String[] {"bounds", "leaf hulls", "leaves", "lights"}) {
            requireEmpty(cursor, "Model " + label);
        }
        checkBoolInt(cursor, "Model root outside flag");
        checkBoolInt(cursor, "Model linked flag");
        for (var label : new String[] {"render sections", "lightmaps", "lightmap atlases"}) {
            requireEmpty(cursor, "Model " + label);
        }
        end(cursor, "Model");
    }

    static void requireEmptyPolys(PackageBytes source, ExportEntry entry, int nativeOffset) throws IOException {
        var cursor = openCursor(source, entry, nativeOffset, "Polys");
        if (cursor.readI32() != 0 || cursor.readI32() != 0) {
            throw rejected("nonempty Polys");
        }
        end(cursor, "Polys");
    }

    static void requireTerrainLevel(PackageBytes source, ExportEntry entry, int nativeOffset, int terrainExport)
            throws IOException {
        var cursor = openCursor(source, entry, nativeOffset, "Level");
        if (source.licenseVersion == 32 || source.licenseVersion == 33) {
            throw rejected("unaudited Level license extension");
        }
        if (terrainExport <= 0
                || terrainExport > source.exports.size()
                || !source.getObjectPath(source.exports.get(terrainExport - 1).classIndex())
                        .equalsIgnoreCase("Engine.TerrainInfo")) {
            throw rejected("invalid Terrain owner reference");
        }
        if (source.licenseVersion >= 23) {
            checkTerrainActors(cursor, terrainExport);
        }
        checkTerrainActors(cursor, terrainExport);
        for (var i = 0; i < 4; i++) {
            cursor.readString();
        }
        var options = PackageBytes.count(cursor.readIndex());
        if (options > cursor.end - cursor.position) {
            throw rejected("invalid Level URL options");
        }
        for (var i = 0; i < options; i++) {
            cursor.readString();
        }
        cursor.readI32();
        cursor.skip(3);
        requireEmpty(cursor, "Level navigation ReachSpecs");
        var model = cursor.readIndex();
        if (model != 0) {
            if (model < 0 || model > source.exports.size()) {
                throw rejected("external or invalid Level Model");
            }
            var modelEntry = source.exports.get(model - 1);
            var properties = new Cursor(
                    source.body, modelEntry.serialOffset(), modelEntry.serialOffset() + modelEntry.serialSize());
            NativeLevelEditor.skipProperties(source, properties, modelEntry.flags());
            requireEmptyModel(source, modelEntry, properties.position);
        }
        if (!Float.isFinite(Float.intBitsToFloat(cursor.readI32()))) {
            throw rejected("invalid Level time");
        }
        for (var i = 0; i < 17; i++) {
            requireEmpty(cursor, "Level deleted actor or text reference");
        }
        requireEmpty(cursor, "Level travel information");
        end(cursor, "Level");
    }

    private static void checkTerrainActors(Cursor cursor, int owner) throws IOException {
        var count = PackageBytes.count(cursor.readI32());
        cursor.readI32();
        if (count > cursor.end - cursor.position) {
            throw rejected("invalid Level actor count");
        }
        for (var i = 0; i < count; i++) {
            var reference = cursor.readIndex();
            if (reference != 0 && reference != owner) {
                throw rejected("additional Level actor or auxiliary consumer");
            }
        }
    }

    private static Cursor openCursor(PackageBytes source, ExportEntry entry, int offset, String type)
            throws IOException {
        if (!TerrainNativeCodec.supports(source.fileVersion, source.licenseVersion)
                || !source.getObjectPath(entry.classIndex()).equalsIgnoreCase("Engine." + type)) {
            throw rejected("unaudited " + type + " native class or package revision");
        }
        if (offset < entry.serialOffset()) {
            throw rejected("invalid " + type + " native offset");
        }
        return new Cursor(source.body, offset, entry.serialOffset() + entry.serialSize());
    }

    private static float readFiniteFloat(Cursor cursor) throws IOException {
        var value = Float.intBitsToFloat(cursor.readI32());
        if (!Float.isFinite(value)) {
            throw rejected("non-finite Model bounds");
        }
        return value;
    }

    private static void requireEmpty(Cursor cursor, String label) throws IOException {
        if (cursor.readIndex() != 0) {
            throw rejected(label);
        }
    }

    private static void checkBoolByte(Cursor cursor, String label) throws IOException {
        if (cursor.readU8() > 1) {
            throw rejected(label);
        }
    }

    private static void checkBoolInt(Cursor cursor, String label) throws IOException {
        var value = cursor.readI32();
        if (value != 0 && value != 1) {
            throw rejected(label);
        }
    }

    private static void end(Cursor cursor, String label) throws IOException {
        if (cursor.position != cursor.end) {
            throw rejected("unknown " + label + " native suffix");
        }
    }

    private static IOException rejected(String reason) {
        return new IOException("Terrain height consumers require a verified empty BSP layout: " + reason);
    }
}

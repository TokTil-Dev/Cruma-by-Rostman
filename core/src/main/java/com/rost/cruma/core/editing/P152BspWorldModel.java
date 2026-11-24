package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeClassDefaults.Property;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class P152BspWorldModel {
    private P152BspWorldModel() {}

    static final class Selection {
        private final int levelReference, modelReference, firstActor;
        private final String packageName, bodySha256;
        private final PackageBytes snapshot;
        private final Set<String> duplicatePaths;

        private Selection(
                PackageBytes source, int levelReference, int modelReference, int firstActor, BooleanSupplier cancelled)
                throws IOException {
            this.levelReference = levelReference;
            this.modelReference = modelReference;
            this.firstActor = firstActor;
            packageName = source.packageName;
            bodySha256 = P152ActorDomain.hash(source.body);
            snapshot = source;
            var seen = new HashSet<String>();
            var duplicates = new HashSet<String>();
            for (var entry : source.exports) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var path = identityKey(source.getObjectPath(entry.index()));
                if (!seen.add(path)) {
                    duplicates.add(path);
                }
            }
            duplicatePaths = Set.copyOf(duplicates);
        }

        int getLevelReference() {
            return levelReference;
        }

        int getModelReference() {
            return modelReference;
        }

        void requireSource(PackageBytes source) throws IOException {
            if (!packageName.equals(source.packageName) || !bodySha256.equals(P152ActorDomain.hash(source.body))) {
                throw new IOException("BSP Level selection belongs to a different frozen source");
            }
        }

        int requireLevelInfo(PackageBytes source) throws IOException {
            requireSource(source);
            return getLevelInfo();
        }

        int getLevelInfo() throws IOException {
            var source = snapshot;
            if (firstActor <= 0 || firstActor > source.exports.size()) {
                throw new IOException("BSP null zone requires a local nonnull native Actors[0]");
            }
            requireRoot(source, source.exports.get(firstActor - 1), "LevelInfo");
            requireBindings(source, firstActor, "LevelInfo");
            return firstActor;
        }

        void requireZone(int reference) throws IOException {
            var source = snapshot;
            if (reference <= 0 || reference > source.exports.size()) {
                throw new IOException("BSP zone requires a local root actor");
            }
            var entry = source.exports.get(reference - 1);
            var type = source.getClassName(entry);
            if (!type.equals("ZoneInfo") && !type.equals("SkyZoneInfo") && !type.equals("LevelInfo")) {
                throw new IOException("Unaudited BSP zone class " + type);
            }
            requireRoot(source, entry, type);
            requireBindings(source, reference, type);
        }

        void requireLight(int reference) throws IOException {
            var source = snapshot;
            var entry = P152EffectiveInputs.localBspActor(source, reference);
            var path = source.getObjectPath(entry.classIndex());
            if (!path.equals("Engine.Light")
                    && !path.equals("Engine.NMovableSunLight")
                    && !path.equals("Gameplay.Spotlight")) {
                throw new IOException("Unaudited BSP light binding class " + path);
            }
            var dot = path.indexOf('.');
            requireBindings(source, reference, path.substring(0, dot), path.substring(dot + 1));
        }

        private void requireBindings(PackageBytes source, int reference, String type) throws IOException {
            requireBindings(source, reference, "Engine", type);
        }

        private void requireBindings(PackageBytes source, int reference, String packageName, String type)
                throws IOException {
            var path = source.getObjectPath(reference);
            if (duplicatePaths.contains(identityKey(path))) {
                throw new IOException("Ambiguous effective-input actor " + path);
            }
            var properties = P152EffectiveInputs.bspActorProperties(source, reference, packageName, type);
            for (var property : properties) {
                if (property.name().equalsIgnoreCase("Level")) {
                    if (firstActor <= 0 || firstActor > source.exports.size()) {
                        throw new IOException("BSP actor Level binding has no native Actors[0]");
                    }
                    requireRoot(source, source.exports.get(firstActor - 1), "LevelInfo");
                    requireReference(property, firstActor);
                } else if (property.name().equalsIgnoreCase("XLevel")) {
                    requireReference(property, levelReference);
                }
            }
        }

        private static void requireReference(Property property, int expected) throws IOException {
            if (property.type() != 5 || property.arrayIndex() != 0) {
                throw new IOException("Malformed BSP actor " + property.name() + " binding");
            }
            var bytes = property.payload();
            var cursor = new Cursor(bytes, 0, bytes.length);
            if (cursor.readIndex() != expected || cursor.position != cursor.end) {
                throw new IOException("BSP actor " + property.name() + " differs from its native Level binding");
            }
        }

        private static String identityKey(String value) {
            var result = new StringBuilder(value.length());
            for (var offset = 0; offset < value.length(); ) {
                var codePoint = value.codePointAt(offset);
                result.appendCodePoint(Character.toLowerCase(Character.toUpperCase(codePoint)));
                offset += Character.charCount(codePoint);
            }
            return result.toString();
        }
    }

    static Selection select(PackageBytes source) throws IOException {
        return select(source, () -> false);
    }

    static Selection select(PackageBytes source, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        source = new PackageBytes(Path.of(source.packageName + ".unr"), source.body);
        if (source.fileVersion < 123
                || source.fileVersion > 132
                || source.licenseVersion < 16
                || source.licenseVersion > 40) {
            throw new IOException(
                    "Unaudited BSP Level prefix version " + source.fileVersion + "/" + source.licenseVersion);
        }
        ExportEntry level = null;
        for (var entry : source.exports) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (source.getClassName(entry).equals("Level")) {
                requireRoot(source, entry, "Level");
                if (level != null) {
                    throw new IOException("BSP source has more than one native Level");
                }
                level = entry;
            }
        }
        if (level == null) {
            throw new IOException("BSP source has no native Level");
        }
        var cursor = new Cursor(source.body, level.serialOffset(), level.serialOffset() + level.serialSize());
        if ((level.flags() & 0x02000000) != 0
                || !source.getName(cursor.readIndex()).equals("None")) {
            throw new IOException("Unaudited BSP Level state frame or tagged properties");
        }
        if (source.licenseVersion >= 23) {
            readFirstReference(source, cursor, cancelled);
        }
        var firstActor = readFirstReference(source, cursor, cancelled);
        for (var index = 0; index < 4; index++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            cursor.readString();
        }
        var options = PackageBytes.count(cursor.readIndex());
        if (options > cursor.end - cursor.position) {
            throw new IOException("Truncated BSP Level URL option array");
        }
        for (var index = 0; index < options; index++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            cursor.readString();
        }
        cursor.readI32();
        cursor.readI32();
        var modelReference = cursor.readIndex();
        source.checkReference(modelReference);
        if (modelReference <= 0) {
            throw new IOException("BSP Level.Model must reference a local export");
        }
        requireRoot(source, source.exports.get(modelReference - 1), "Model");
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Selection(source, level.index(), modelReference, firstActor, cancelled);
    }

    private static void requireRoot(PackageBytes source, ExportEntry entry, String type) throws IOException {
        P152ActorDomain.requireEngineClass(source, entry, type);
        if (entry.outer() != 0) {
            throw new IOException("BSP " + type + " must belong to the package root");
        }
        if ((entry.flags() & 0x00040000) == 0 || (entry.flags() & 0x00400000) != 0) {
            throw new IOException("BSP " + type + " is not admitted by native editor load flags");
        }
    }

    private static int readFirstReference(PackageBytes source, Cursor cursor, BooleanSupplier cancelled)
            throws IOException {
        var count = PackageBytes.count(cursor.readI32());
        cursor.readI32();
        if (count > cursor.end - cursor.position) {
            throw new IOException("Truncated BSP Level reference array");
        }
        var first = 0;
        for (var index = 0; index < count; index++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var reference = cursor.readIndex();
            source.checkReference(reference);
            if (reference < 0) {
                throw new IOException("BSP Level prefix contains a foreign actor reference");
            }
            if (index == 0) {
                first = reference;
            }
        }
        return first;
    }
}

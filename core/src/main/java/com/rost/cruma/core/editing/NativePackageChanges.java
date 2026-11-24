package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class NativePackageChanges {
    private final Path sourcePath;
    private final String sourceSha256;
    private final PackageBytes source;
    private final Map<Integer, byte[]> replacements;
    private final Set<Integer> positionIndependent;
    private final Map<Integer, Placer> placers;
    private final Verification verification;

    private NativePackageChanges(
            Path sourcePath,
            String sourceSha256,
            PackageBytes source,
            Map<Integer, byte[]> replacements,
            Set<Integer> positionIndependent,
            Map<Integer, Placer> placers,
            Verification verification) {
        this.sourcePath = sourcePath.toAbsolutePath().normalize();
        this.sourceSha256 = sourceSha256;
        this.source = source;
        this.replacements = replacements;
        this.positionIndependent = positionIndependent;
        this.placers = placers;
        this.verification = verification;
    }

    @FunctionalInterface
    interface Verification {
        void verify(Path target, byte[] encoded) throws IOException;
    }

    @FunctionalInterface
    interface Placer {
        byte[] at(int serialOffset) throws IOException;
    }

    static NativePackageChanges create(
            Path sourcePath,
            String sourceSha256,
            PackageBytes source,
            Map<Integer, byte[]> replacementPayloads,
            Set<Integer> positionIndependentExports,
            Verification verification)
            throws IOException {
        return create(
                sourcePath,
                sourceSha256,
                source,
                replacementPayloads,
                positionIndependentExports,
                Map.of(),
                verification);
    }

    static NativePackageChanges create(
            Path sourcePath,
            String sourceSha256,
            PackageBytes source,
            Map<Integer, byte[]> replacementPayloads,
            Set<Integer> positionIndependentExports,
            Map<Integer, Placer> placers,
            Verification verification)
            throws IOException {
        Objects.requireNonNull(sourcePath);
        Objects.requireNonNull(sourceSha256);
        Objects.requireNonNull(verification);
        if (!replacementPayloads.keySet().containsAll(positionIndependentExports)) {
            throw new IOException("Native relocation policy refers to an unchanged export");
        }
        var replacements = new LinkedHashMap<Integer, byte[]>();
        for (var replacement : replacementPayloads.entrySet()) {
            var index = replacement.getKey();
            if (index <= 0 || index > source.exports.size()) {
                throw new IOException("Native changes require an existing source export identity");
            }
            var entry = source.exports.get(index - 1);
            var classPath = source.getObjectPath(entry.classIndex());
            var model = classPath.equals("Engine.Model");
            if (model
                    ? !placers.containsKey(index)
                    : !Set.of("Engine.TerrainInfo", "Engine.TerrainSector", "Engine.Texture")
                            .contains(classPath)) {
                throw new IOException(
                        "No native composition policy for " + classPath + (model ? " without its lightmap codec" : ""));
            }
            if (!model && placers.containsKey(index)) {
                throw new IOException("Only a level Model is written by a placer");
            }
            if (positionIndependentExports.contains(index)
                    && !Set.of("Engine.TerrainInfo", "Engine.TerrainSector", "Engine.Model")
                            .contains(classPath)) {
                throw new IOException("Only audited TerrainInfo, TerrainSector and Model payloads may relocate");
            }
            var payload = replacement.getValue().clone();
            if (positionIndependentExports.contains(index) && !classPath.equals("Engine.Model")) {
                validateRelocatableTail(source, entry, payload, classPath);
            }
            if (payload.length != entry.serialSize() && !positionIndependentExports.contains(index)) {
                throw new IOException("Native export must preserve its original size and absolute offset: "
                        + source.getObjectPath(index));
            }
            replacements.put(index, payload);
        }
        return new NativePackageChanges(
                sourcePath,
                sourceSha256,
                source,
                Map.copyOf(replacements),
                Set.copyOf(positionIndependentExports),
                Map.copyOf(placers),
                verification);
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public boolean isEmpty() {
        return replacements.isEmpty();
    }

    public NativePackageChanges combine(NativePackageChanges other) throws IOException {
        other.validateFor(sourcePath, sourceSha256, source);
        var combined = new LinkedHashMap<>(replacements);
        for (var entry : other.replacements.entrySet()) {
            if (combined.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IOException("Overlapping native edits require one coordinated producer: "
                        + source.getObjectPath(entry.getKey()));
            }
        }
        var relocation = new HashSet<>(positionIndependent);
        relocation.addAll(other.positionIndependent);
        var combinedPlacers = new LinkedHashMap<>(placers);
        combinedPlacers.putAll(other.placers);
        return create(sourcePath, sourceSha256, source, combined, relocation, combinedPlacers, (target, encoded) -> {
            verification.verify(target, encoded);
            other.verification.verify(target, encoded);
        });
    }

    void validateFor(Path path, String fingerprint, PackageBytes original) throws IOException {
        if (!sourcePath.equals(path.toAbsolutePath().normalize())
                || !sourceSha256.equals(fingerprint)
                || !Arrays.equals(source.body, original.body)) {
            throw new IOException("Native edits belong to a different source package revision");
        }
    }

    void contribute(Map<Integer, byte[]> changes) throws IOException {
        for (var entry : replacements.entrySet()) {
            if (changes.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IOException(
                        "Actor and native changes overlap one export: " + source.getObjectPath(entry.getKey()));
            }
        }
    }

    boolean mayRelocate(int exportIndex) {
        return positionIndependent.contains(exportIndex);
    }

    boolean contains(int exportIndex) {
        return replacements.containsKey(exportIndex);
    }

    byte[] getPayloadAt(int exportIndex, int serialOffset) throws IOException {
        var placer = placers.get(exportIndex);
        if (placer == null) {
            return replacements.get(exportIndex).clone();
        }
        return placer.at(serialOffset);
    }

    void verify(Path target, byte[] encoded) throws IOException {
        var result = new PackageBytes(target, encoded);
        for (var replacement : replacements.entrySet()) {
            var originalEntry = source.exports.get(replacement.getKey() - 1);
            var entry = result.exports.get(replacement.getKey() - 1);
            if (entry.classIndex() != originalEntry.classIndex()
                    || entry.superIndex() != originalEntry.superIndex()
                    || entry.outer() != originalEntry.outer()
                    || entry.objectName() != originalEntry.objectName()
                    || entry.flags() != originalEntry.flags()
                    || !positionIndependent.contains(entry.index())
                            && entry.serialOffset() != originalEntry.serialOffset()
                    || !Arrays.equals(
                            getPayloadAt(replacement.getKey(), entry.serialOffset()),
                            Arrays.copyOfRange(
                                    result.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize()))) {
                throw new IOException("Composed native export did not preserve its identity or payload");
            }
        }
        verification.verify(target, encoded);
    }

    private static void validateRelocatableTail(
            PackageBytes source, ExportEntry entry, byte[] payload, String classPath) throws IOException {
        if (!TerrainNativeCodec.supportsReadOnly(source.fileVersion, source.licenseVersion)) {
            throw new IOException("Native relocation is not admitted for terrain revision " + source.fileVersion + "/"
                    + source.licenseVersion);
        }
        if ((entry.flags() & 0x02000000) != 0) {
            throw new IOException("Native relocation does not admit actor state frames");
        }
        var original = Arrays.copyOfRange(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        var before = new Cursor(original, 0, original.length);
        var after = new Cursor(payload, 0, payload.length);
        NativeLevelEditor.skipProperties(source, before, entry.flags());
        NativeLevelEditor.skipProperties(source, after, entry.flags());
        if (before.position != after.position
                || !Arrays.equals(Arrays.copyOf(original, before.position), Arrays.copyOf(payload, after.position))) {
            throw new IOException("Relocated native exports must preserve all authored property bytes");
        }
        var originalTail = Arrays.copyOfRange(original, before.position, original.length);
        var replacementTail = Arrays.copyOfRange(payload, after.position, payload.length);
        if (classPath.equals("Engine.TerrainInfo")) {
            TerrainNativeCodec.readInfo(source.fileVersion, source.licenseVersion, originalTail);
            TerrainNativeCodec.readInfo(source.fileVersion, source.licenseVersion, replacementTail);
        } else {
            TerrainNativeCodec.readSector(source.fileVersion, source.licenseVersion, originalTail);
            TerrainNativeCodec.readSector(source.fileVersion, source.licenseVersion, replacementTail);
        }
    }
}

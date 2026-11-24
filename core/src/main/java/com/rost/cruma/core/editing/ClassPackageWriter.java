package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.littleEndian;
import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.PackageBytes.writeIndex;
import static com.rost.cruma.core.editing.PackageBytes.writeInt;

import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.PackageBytes.ImportEntry;
import com.rost.cruma.core.editing.TaggedProperties.Names;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

final class ClassPackageWriter implements Names {
    private static final int HEADER_SIZE = 64;
    private static final int NAME_FLAGS = 0x00070010;

    private final @Nullable PackageBytes original;
    private final String packageName;
    private final int fileVersion;
    private final int licenseVersion;
    private final int packageFlags;
    private final int nameFlags;
    private final List<String> names;
    private final Map<String, Integer> nameIndex = new HashMap<>();
    private final List<ImportEntry> imports;
    private final Map<ImportKey, Integer> importIndex = new HashMap<>();
    private final List<ExportEntry> exports;
    private final int originalExports;
    private final Map<Integer, byte[]> payloads = new LinkedHashMap<>();
    private final Map<Integer, IntFunction<byte[]>> placed = new HashMap<>();
    private @Nullable Map<Integer, List<Integer>> children;

    private ClassPackageWriter(
            @Nullable PackageBytes original,
            String packageName,
            int fileVersion,
            int licenseVersion,
            int packageFlags,
            int nameFlags) {
        this.original = original;
        this.packageName = packageName;
        this.fileVersion = fileVersion;
        this.licenseVersion = licenseVersion;
        this.packageFlags = packageFlags;
        this.nameFlags = nameFlags;
        names = new ArrayList<>(original == null ? List.of() : original.names);
        imports = new ArrayList<>(original == null ? List.of() : original.imports);
        exports = new ArrayList<>(original == null ? List.of() : original.exports);
        originalExports = exports.size();
        for (var i = 0; i < names.size(); i++) {
            nameIndex.putIfAbsent(key(names.get(i)), i);
        }
        for (var i = 0; i < imports.size(); i++) {
            var entry = imports.get(i);
            importIndex.putIfAbsent(importKey(entry), -i - 1);
        }
    }

    static ClassPackageWriter open(PackageBytes source) throws IOException {
        return new ClassPackageWriter(
                source,
                source.packageName,
                source.fileVersion,
                source.licenseVersion,
                littleEndian(source.body).getInt(8),
                lastNameFlags(source));
    }

    static ClassPackageWriter create(String packageName, PackageBytes template) throws IOException {
        return new ClassPackageWriter(
                null,
                packageName,
                template.fileVersion,
                template.licenseVersion,
                littleEndian(template.body).getInt(8),
                lastNameFlags(template));
    }

    static ClassPackageWriter create(String packageName, int fileVersion, int licenseVersion) {
        return new ClassPackageWriter(null, packageName, fileVersion, licenseVersion, 0, NAME_FLAGS);
    }

    String getPackageName() {
        return packageName;
    }

    int getFileVersion() {
        return fileVersion;
    }

    @Nullable
    PackageBytes getOriginal() {
        return original;
    }

    boolean isChanged() {
        return original == null
                || !payloads.isEmpty()
                || names.size() != original.names.size()
                || imports.size() != original.imports.size()
                || exports.size() != originalExports;
    }

    @Override
    public String getName(int index) throws IOException {
        if (index < 0 || index >= names.size()) {
            throw malformed("Invalid name index " + index);
        }
        return names.get(index);
    }

    @Override
    public int intern(String name) {
        return nameIndex.computeIfAbsent(key(name), _ -> {
            names.add(name);
            return names.size() - 1;
        });
    }

    int findName(String name) {
        return nameIndex.getOrDefault(key(name), -1);
    }

    int getExportCount() {
        return exports.size();
    }

    int getImportCount() {
        return imports.size();
    }

    ExportEntry export(int index) throws IOException {
        if (index < 1 || index > exports.size()) {
            throw malformed("Invalid export index " + index);
        }
        return exports.get(index - 1);
    }

    String exportName(int index) throws IOException {
        return getName(export(index).objectName());
    }

    String getClassName(int index) throws IOException {
        var classIndex = export(index).classIndex();
        return classIndex == 0 ? "Class" : getObjectName(classIndex);
    }

    byte[] getPayload(int index) throws IOException {
        var changed = payloads.get(index);
        return changed != null ? changed : getOriginalPayload(index);
    }

    byte[] getOriginalPayload(int index) throws IOException {
        if (index > originalExports) {
            return getPayload(index);
        }
        var entry = export(index);
        return Arrays.copyOfRange(original.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
    }

    void replace(int index, byte[] payload) throws IOException {
        if (!Arrays.equals(getPayload(index), payload)) {
            payloads.put(index, payload);
        }
    }

    int addExport(int classIndex, int superIndex, int outer, String objectName, int flags, byte[] payload) {
        var index = exports.size() + 1;
        exports.add(new ExportEntry(index, classIndex, superIndex, outer, intern(objectName), flags, 0, 0, 0, 0, 0));
        payloads.put(index, payload);
        if (children != null) {
            children.computeIfAbsent(outer, _ -> new ArrayList<>()).add(index);
        }
        return index;
    }

    int addPlacedExport(
            int classIndex, int superIndex, int outer, String objectName, int flags, IntFunction<byte[]> payload) {
        var index = addExport(classIndex, superIndex, outer, objectName, flags, payload.apply(0));
        placed.put(index, payload);
        return index;
    }

    List<Integer> getChildren(int outer) {
        if (children == null) {
            var result = new HashMap<Integer, List<Integer>>();
            for (var entry : exports) {
                result.computeIfAbsent(entry.outer(), _ -> new ArrayList<>()).add(entry.index());
            }
            children = result;
        }
        return children.getOrDefault(outer, List.of());
    }

    int findClassExport(String name) throws IOException {
        for (var index : getChildren(0)) {
            if (export(index).classIndex() == 0 && exportName(index).equalsIgnoreCase(name)) {
                return index;
            }
        }
        return 0;
    }

    String freeChildName(int outer, String base) throws IOException {
        var taken = new HashSet<String>();
        for (var child : getChildren(outer)) {
            taken.add(key(exportName(child)));
        }
        for (var number = 0; ; number++) {
            if (!taken.contains(key(base + number))) {
                return base + number;
            }
        }
    }

    String getObjectName(int reference) throws IOException {
        if (reference == 0) {
            return "None";
        }
        return reference > 0
                ? exportName(reference)
                : getName(importEntry(reference).objectName());
    }

    ImportEntry importEntry(int reference) throws IOException {
        var at = -(long) reference - 1;
        if (at < 0 || at >= imports.size()) {
            throw malformed("Invalid import reference " + reference);
        }
        return imports.get((int) at);
    }

    String getClassOf(int reference) throws IOException {
        return reference < 0 ? getName(importEntry(reference).className()) : getClassName(reference);
    }

    String getObjectPath(int reference) throws IOException {
        if (reference == 0) {
            return "None";
        }
        var parts = new ArrayList<String>();
        var seen = new HashSet<Integer>();
        var rootIsExport = reference > 0;
        for (var at = reference; at != 0; ) {
            if (!seen.add(at)) {
                throw malformed("Cyclic package outer chain");
            }
            parts.add(getObjectName(at));
            rootIsExport = at > 0;
            at = at < 0 ? importEntry(at).outer() : export(at).outer();
        }
        if (rootIsExport) {
            parts.add(packageName);
        }
        return String.join(".", parts.reversed());
    }

    int importPackage(String name) {
        return importObject(0, "Core", "Package", name);
    }

    int importObject(int outer, String classPackage, String className, String objectName) {
        var key = new ImportKey(key(classPackage), key(className), outer, key(objectName));
        return importIndex.computeIfAbsent(key, _ -> {
            imports.add(new ImportEntry(intern(classPackage), intern(className), outer, intern(objectName)));
            return -imports.size();
        });
    }

    int importPath(String path, String classPackage, String className) throws IOException {
        var parts = path.split("\\.");
        if (parts.length < 2) {
            throw malformed("An object path needs a package: " + path);
        }
        var outer = importPackage(parts[0]);
        for (var i = 1; i < parts.length - 1; i++) {
            outer = importObject(outer, "Core", "Package", parts[i]);
        }
        return importObject(outer, classPackage, className, parts[parts.length - 1]);
    }

    byte[] getBody() throws IOException {
        return original == null ? newBody() : editedBody(original);
    }

    byte[] encode(Path target) throws IOException {
        var body = getBody();
        var filename = target.getFileName().toString();
        var keep = original != null && (original.container != 111 || !PackageBytes.isTexturePackage(filename));
        var result = keep ? original.encode(body, target) : PackageBytes.encodeNew(body, filename);
        new PackageBytes(target, result);
        return result;
    }

    private byte[] editedBody(PackageBytes source) throws IOException {
        if (!isChanged()) {
            return source.body.clone();
        }
        var patched = source.body.clone();
        var out = new ByteArrayOutputStream();
        var base = source.body.length;
        var offsets = new HashMap<Integer, Integer>();
        for (var change : payloads.entrySet()) {
            var index = change.getKey();
            var payload = change.getValue();
            if (index <= originalExports) {
                var entry = source.exports.get(index - 1);
                if (entry.serialSize() == payload.length) {
                    System.arraycopy(payload, 0, patched, entry.serialOffset(), payload.length);
                    continue;
                }
            }
            offsets.put(index, base + out.size());
            out.writeBytes(atOffset(index, payload, base + out.size()));
        }
        var newNames = names.size() != source.names.size();
        var nameOffset = base + out.size();
        if (newNames) {
            out.write(source.body, source.nameOffset, source.nameEnd - source.nameOffset);
            for (var i = source.names.size(); i < names.size(); i++) {
                writeName(out, names.get(i));
            }
        }
        var newImports = imports.size() != source.imports.size();
        var importOffset = base + out.size();
        if (newImports) {
            out.write(source.body, source.importOffset, source.importEnd - source.importOffset);
            for (var i = source.imports.size(); i < imports.size(); i++) {
                writeImport(out, imports.get(i));
            }
        }
        var newExports = !offsets.isEmpty() || exports.size() != originalExports;
        var exportOffset = base + out.size();
        if (newExports) {
            for (var entry : source.exports) {
                var offset = offsets.get(entry.index());
                if (offset == null) {
                    out.write(source.body, entry.rowStart(), entry.rowEnd() - entry.rowStart());
                } else {
                    out.write(source.body, entry.rowStart(), entry.sizeOffset() - entry.rowStart());
                    writeIndex(out, payloads.get(entry.index()).length);
                    writeIndex(out, offset);
                }
            }
            for (var i = originalExports; i < exports.size(); i++) {
                var entry = exports.get(i);
                writeExport(out, entry, payloads.get(entry.index()).length, offsets.get(entry.index()));
            }
        }
        var result = Arrays.copyOf(patched, base + out.size());
        System.arraycopy(out.toByteArray(), 0, result, base, out.size());
        var header = littleEndian(result);
        if (newNames) {
            header.putInt(12, names.size());
            header.putInt(16, nameOffset);
            if (source.lastGenerationNameOffset >= 0) {
                header.putInt(source.lastGenerationNameOffset, names.size());
            }
        }
        if (newImports) {
            header.putInt(28, imports.size());
            header.putInt(32, importOffset);
        }
        if (newExports) {
            header.putInt(20, exports.size());
            header.putInt(24, exportOffset);
            if (source.lastGenerationNameOffset >= 0) {
                header.putInt(source.lastGenerationNameOffset - 4, exports.size());
            }
        }
        return result;
    }

    private byte[] newBody() throws IOException {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[HEADER_SIZE]);
        intern("None");
        for (var name : names) {
            writeName(out, name);
        }
        var offsets = new int[exports.size() + 1];
        for (var entry : exports) {
            offsets[entry.index()] = out.size();
            out.writeBytes(atOffset(entry.index(), getPayload(entry.index()), out.size()));
        }
        var importOffset = out.size();
        for (var entry : imports) {
            writeImport(out, entry);
        }
        var exportOffset = out.size();
        for (var entry : exports) {
            writeExport(out, entry, getPayload(entry.index()).length, offsets[entry.index()]);
        }
        var result = out.toByteArray();
        var guid = UUID.randomUUID();
        littleEndian(result)
                .putInt(PackageBytes.MAGIC)
                .putShort((short) fileVersion)
                .putShort((short) licenseVersion)
                .putInt(packageFlags)
                .putInt(names.size())
                .putInt(HEADER_SIZE)
                .putInt(exports.size())
                .putInt(exportOffset)
                .putInt(imports.size())
                .putInt(importOffset)
                .putLong(guid.getMostSignificantBits())
                .putLong(guid.getLeastSignificantBits())
                .putInt(1)
                .putInt(exports.size())
                .putInt(names.size());
        return result;
    }

    private byte[] atOffset(int index, byte[] payload, int offset) {
        var builder = placed.get(index);
        return builder == null ? payload : builder.apply(offset);
    }

    private void writeName(ByteArrayOutputStream out, String value) {
        if (value.chars().allMatch(c -> c > 0 && c < 128)) {
            var bytes = value.getBytes(StandardCharsets.US_ASCII);
            writeIndex(out, bytes.length + 1);
            out.writeBytes(bytes);
            out.write(0);
        } else {
            writeIndex(out, -(value.length() + 1));
            out.writeBytes(value.getBytes(StandardCharsets.UTF_16LE));
            out.write(0);
            out.write(0);
        }
        writeInt(out, nameFlags);
    }

    private ImportKey importKey(ImportEntry entry) {
        return new ImportKey(
                key(names.get(entry.classPackage())),
                key(names.get(entry.className())),
                entry.outer(),
                key(names.get(entry.objectName())));
    }

    private static void writeImport(ByteArrayOutputStream out, ImportEntry entry) {
        writeIndex(out, entry.classPackage());
        writeIndex(out, entry.className());
        writeInt(out, entry.outer());
        writeIndex(out, entry.objectName());
    }

    private static void writeExport(ByteArrayOutputStream out, ExportEntry entry, int size, int offset) {
        writeIndex(out, entry.classIndex());
        writeIndex(out, entry.superIndex());
        writeInt(out, entry.outer());
        writeIndex(out, entry.objectName());
        writeInt(out, entry.flags());
        writeIndex(out, size);
        if (size > 0) {
            writeIndex(out, offset);
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static int lastNameFlags(PackageBytes source) throws IOException {
        if (source.names.isEmpty()) {
            return NAME_FLAGS;
        }
        return littleEndian(source.body).getInt(source.nameEnd - 4);
    }

    private record ImportKey(String classPackage, String className, int outer, String objectName) {}
}

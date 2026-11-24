package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.TaggedProperties.ARRAY;
import static com.rost.cruma.core.editing.TaggedProperties.BOOL;
import static com.rost.cruma.core.editing.TaggedProperties.BYTE;
import static com.rost.cruma.core.editing.TaggedProperties.FLOAT;
import static com.rost.cruma.core.editing.TaggedProperties.INT;
import static com.rost.cruma.core.editing.TaggedProperties.NAME;
import static com.rost.cruma.core.editing.TaggedProperties.OBJECT;
import static com.rost.cruma.core.editing.TaggedProperties.STR;
import static com.rost.cruma.core.editing.TaggedProperties.STRUCT;

import com.rost.cruma.core.editing.ClassSchema.Field;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TaggedProperties.Property;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class ClassSource {
    private static final String INDENT = "    ";

    private static final Set<String> DECLARED = Set.of(
            "textbuffer",
            "function",
            "state",
            "struct",
            "enum",
            "const",
            "boolproperty",
            "byteproperty",
            "intproperty",
            "floatproperty",
            "strproperty",
            "stringproperty",
            "nameproperty",
            "objectproperty",
            "classproperty",
            "structproperty",
            "arrayproperty",
            "fixedarrayproperty",
            "mapproperty",
            "delegateproperty",
            "pointerproperty");

    private static final Map<Integer, String> VARIABLE_FLAGS = new LinkedHashMap<>();

    static {
        VARIABLE_FLAGS.put(0x2, "const");
        VARIABLE_FLAGS.put(0x4, "input");
        VARIABLE_FLAGS.put(0x8, "export");
        VARIABLE_FLAGS.put(0x1000, "native");
        VARIABLE_FLAGS.put(0x2000, "transient");
        VARIABLE_FLAGS.put(0x4000, "config");
        VARIABLE_FLAGS.put(0x8000, "localized");
        VARIABLE_FLAGS.put(0x10000, "travel");
        VARIABLE_FLAGS.put(0x20000, "editconst");
        VARIABLE_FLAGS.put(0x40000, "globalconfig");
        VARIABLE_FLAGS.put(0x4000000, "editinline");
        VARIABLE_FLAGS.put(0x10000000, "editinlineuse");
        VARIABLE_FLAGS.put(0x20000000, "deprecated");
    }

    private static final int CPF_EDIT = 0x1;
    private static final int CPF_NET = 0x20;
    private static final int CPF_CONFIG = 0x4000;
    private static final int CPF_GLOBAL_CONFIG = 0x40000;
    private static final int CPF_EDIT_INLINE = 0x4000000;
    private static final int CPF_EDIT_INLINE_USE = 0x10000000;
    private static final int CPF_NEED_CTOR_LINK = 0x400000;

    private static final Pattern DEFAULTS = Pattern.compile("(?im)^\\s*defaultproperties\\b");

    private static final @Nullable Charset KOREAN =
            Charset.isSupported("x-windows-949") ? Charset.forName("x-windows-949") : null;

    private final ClassPackageWriter writer;

    private final ClassSchema schema;

    private final String packageName;
    private final StringBuilder out = new StringBuilder();

    private int unread;

    private ClassSource(ClassPackageWriter writer, ClassSchema schema, String packageName) {
        this.writer = writer;
        this.schema = schema;
        this.packageName = packageName;
    }

    public record Source(String fileName, String packageName, String text, int unread) {
        public Path getRelativePath() {
            return fileName.endsWith(".uc")
                    ? Path.of(packageName, "Classes", fileName)
                    : Path.of(packageName, fileName);
        }

        public byte[] toFileBytes() {
            if (text.chars().allMatch(c -> c < 128)) {
                return text.getBytes(StandardCharsets.US_ASCII);
            }
            var body = text.getBytes(StandardCharsets.UTF_16LE);
            var bytes = new byte[body.length + 2];
            bytes[0] = (byte) 0xFF;
            bytes[1] = (byte) 0xFE;
            System.arraycopy(body, 0, bytes, 2, body.length);
            return bytes;
        }
    }

    record Compiled(ClassPackageWriter writer, int classExport, ClassSchema schema) {}

    public record Written(String packageName, int files, List<String> unreadFiles) {}

    public static Source read(Path packageFile, String className) throws IOException {
        var writer = ClassPackageWriter.open(new PackageBytes(packageFile, Files.readAllBytes(packageFile)));
        var index = writer.findClassExport(className);
        if (index == 0) {
            throw new IOException(packageFile.getFileName() + " has no class " + className + ".");
        }
        return ofClass(writer, index, ClassSchema.read(packageFile.getParent()).with(writer));
    }

    public static Written writePackage(
            Path packageFile, @Nullable Path systemDirectory, Path folder, BooleanSupplier cancelled)
            throws IOException {
        var writer = ClassPackageWriter.open(new PackageBytes(packageFile, Files.readAllBytes(packageFile)));
        var schema = ClassSchema.read(systemDirectory).with(writer);
        var packageName = findPackageName(writer);
        var files = 0;
        var unread = new ArrayList<String>();
        for (var index = 1; index <= writer.getExportCount(); index++) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("The scripts of " + packageName + " were stopped.");
            }
            var export = writer.export(index);
            Source source;
            if (export.classIndex() == 0 && export.outer() == 0) {
                source = ofClass(writer, index, schema, null, packageName);
            } else if (writer.getClassName(index).equalsIgnoreCase("SkillVisualEffect")) {
                source = ofObject(writer, index, schema);
            } else {
                continue;
            }
            var file = folder.resolve(source.getRelativePath());
            Files.createDirectories(file.getParent());
            Files.write(file, source.toFileBytes());
            files++;
            if (source.unread() > 0) {
                unread.add(source.getRelativePath().toString().replace('\\', '/'));
            }
        }
        return new Written(packageName, files, unread);
    }

    static Source ofClass(ClassPackageWriter writer, int classExport, ClassSchema schema) throws IOException {
        return ofClass(writer, classExport, schema, null, findPackageName(writer));
    }

    static Source ofClass(
            ClassPackageWriter writer,
            int classExport,
            ClassSchema schema,
            @Nullable Compiled compiled,
            String packageName)
            throws IOException {
        var source = new ClassSource(writer, schema, packageName);
        source.writeClass(classExport, compiled == null ? new Compiled(writer, classExport, schema) : compiled);
        return new Source(writer.exportName(classExport) + ".uc", packageName, source.out.toString(), source.unread);
    }

    static Source ofObject(ClassPackageWriter writer, int export, ClassSchema schema) throws IOException {
        var packageName = findPackageName(writer);
        var source = new ClassSource(writer, schema, packageName);
        source.writeObject(export);
        return new Source(writer.exportName(export) + ".t3d", packageName, source.out.toString(), source.unread);
    }

    static String findPackageName(ClassPackageWriter writer) throws IOException {
        var index = writer.findName(writer.getPackageName());
        return index < 0 ? writer.getPackageName() : writer.getName(index);
    }

    static String number(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return Float.toString(value);
        }
        if (value == 0) {
            return "0.0";
        }
        var text = new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
        return text.contains(".") ? text : text + ".0";
    }

    static String korean(String text) {
        if (text.chars().allMatch(c -> c < 128) || text.chars().anyMatch(c -> c > 255)) {
            return text;
        }
        var bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        var utf8 = decode(StandardCharsets.UTF_8, bytes);
        if (utf8 != null) {
            return utf8;
        }
        var euc = KOREAN == null ? null : decode(KOREAN, bytes);
        return euc != null ? euc : text;
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void writeClass(int classExport, Compiled compiled) throws IOException {
        var name = writer.exportName(classExport);
        var data = ClassExportData.read(writer.getPayload(classExport), name, writer);
        line("// " + name + " of " + packageName + ", rebuilt by Cruma from the compiled class. The client");
        line("// keeps no defaultproperties text, so they are written from the compiled values.");
        var script = scriptText(classExport);
        if (script.isBlank()) {
            var variables = variables(compiled);
            var functions = FunctionSource.list(compiled.writer(), compiled.classExport());
            if (!variables.isEmpty() || !functions.isEmpty()) {
                line("// It keeps no script text either, so the variables and functions come from the");
                line("// compiled class too: they have no comments, and Cruma lays them out.");
            }
            var parent = writer.export(classExport).superIndex();
            line("class " + name + (parent == 0 ? "" : " extends " + writer.getObjectName(parent)) + ";");
            if (!variables.isEmpty()) {
                line("");
                variables.forEach(this::line);
            }
            var notShown = new ArrayList<String>();
            for (var function : functions) {
                var text = FunctionSource.write(
                        compiled.writer(),
                        compiled.classExport(),
                        function.export(),
                        compiled.schema(),
                        reference -> reference(compiled.writer(), reference));
                if (text == null) {
                    notShown.add(function.name());
                } else {
                    line("");
                    out.append(text);
                }
            }
            for (var child : compiled.writer().getChildren(compiled.classExport())) {
                if (compiled.writer().getClassName(child).equalsIgnoreCase("State")) {
                    notShown.add(compiled.writer().exportName(child));
                }
            }
            if (!notShown.isEmpty()) {
                line("");
                line("// Compiled code only, which is not shown: " + String.join(", ", notShown) + ".");
                unread += notShown.size();
            }
        } else {
            out.append(script.stripTrailing().replace("\r\n", "\n")).append('\n');
            if (DEFAULTS.matcher(script).find()) {
                return;
            }
        }
        line("");
        line("defaultproperties");
        line("{");
        objects(classExport, INDENT);
        if (data == null) {
            line(INDENT + "// The compiled defaults of this class could not be read.");
            unread++;
        } else {
            properties(data.defaults, name, INDENT);
        }
        line("}");
    }

    private List<String> variables(Compiled compiled) throws IOException {
        var source = compiled.writer();
        var result = new ArrayList<String>();
        for (var child : source.getChildren(compiled.classExport())) {
            if (!source.getClassName(child).endsWith("Property")) {
                continue;
            }
            var property = ScriptProperty.read(source, child);
            String type;
            try {
                type = property == null ? null : property.type(source);
            } catch (IOException unreadable) {
                type = null;
            }
            if (property == null || type == null) {
                result.add("// The variable " + source.exportName(child) + " could not be read.");
                unread++;
                continue;
            }
            var words = new StringBuilder("var");
            if (property.is(CPF_EDIT)) {
                var category = source.getName(property.category());
                words.append(
                        category.equalsIgnoreCase("None")
                                        || category.equalsIgnoreCase(source.exportName(compiled.classExport()))
                                ? "()"
                                : "(" + category + ")");
            }
            var known = CPF_EDIT | CPF_NET | CPF_NEED_CTOR_LINK;
            for (var flag : VARIABLE_FLAGS.entrySet()) {
                var implied = flag.getKey() == CPF_CONFIG && property.is(CPF_GLOBAL_CONFIG)
                        || flag.getKey() == CPF_EDIT_INLINE && property.is(CPF_EDIT_INLINE_USE);
                if (property.is(flag.getKey()) && !implied) {
                    words.append(' ').append(flag.getValue());
                }
                known |= flag.getKey();
            }
            words.append(' ').append(type).append(' ').append(property.name());
            if (property.arrayDim() > 1) {
                words.append('[').append(property.arrayDim()).append(']');
            }
            words.append(';');
            var unknown = property.flags() & ~known;
            if (unknown != 0) {
                words.append(" // and flags 0x")
                        .append(Integer.toHexString(unknown))
                        .append(" that Cruma does not name");
                unread++;
            }
            result.add(words.toString());
        }
        return result;
    }

    private void writeObject(int export) throws IOException {
        line("// " + path(export) + ", rebuilt by Cruma from the compiled object. Chronicle 3 to");
        line("// Interlude keep a skill as an object, not as a class with a .uc file.");
        var tree = tree(export);
        var outer = writer.export(export).outer();
        var written = new HashSet<Integer>();
        if (tree != null) {
            for (var reference : references(tree)) {
                if (reference > 0
                        && reference != export
                        && writer.export(reference).outer() == outer
                        && written.add(reference)) {
                    block(reference, "");
                }
            }
        }
        line("Begin Object Class=" + writer.getClassName(export) + " Name=" + writer.exportName(export));
        objects(export, INDENT);
        body(export, tree, INDENT);
        line("End Object");
    }

    private void objects(int outer, String indent) throws IOException {
        for (var child : writer.getChildren(outer)) {
            if (!DECLARED.contains(writer.getClassName(child).toLowerCase(Locale.ROOT))) {
                block(child, indent);
            }
        }
    }

    private void block(int export, String indent) throws IOException {
        line(indent + "Begin Object Class=" + writer.getClassName(export) + " Name=" + writer.exportName(export));
        objects(export, indent + INDENT);
        body(export, tree(export), indent + INDENT);
        line(indent + "End Object");
    }

    private void body(int export, @Nullable TaggedProperties tree, String indent) throws IOException {
        if (tree == null) {
            line(indent + "// The values of this object could not be read.");
            unread++;
            return;
        }
        properties(tree, writer.getClassName(export), indent);
        var payload = writer.getPayload(export);
        var cursor = new Cursor(payload, 0, payload.length);
        TaggedProperties.read(cursor, writer);
        if (cursor.position < cursor.end) {
            line(indent + "// " + bytes(cursor.end - cursor.position) + " of native data after the values are not"
                    + " shown.");
            unread++;
        }
    }

    private @Nullable TaggedProperties tree(int export) {
        try {
            var payload = writer.getPayload(export);
            return TaggedProperties.read(new Cursor(payload, 0, payload.length), writer);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private void properties(TaggedProperties tree, @Nullable String owner, String indent) throws IOException {
        for (var entry : entries(tree, owner)) {
            if (entry.value() == null) {
                line(indent + "// " + entry.key() + ": " + bytes(entry.size()) + " in a layout that Cruma does not"
                        + " read.");
            } else {
                line(indent + entry.key() + "=" + entry.value() + missing(entry.missing()));
            }
        }
    }

    private record Entry(String key, @Nullable String value, int size, int missing) {}

    private List<Entry> entries(TaggedProperties tree, @Nullable String owner) throws IOException {
        var indexed = new HashSet<String>();
        for (var property : tree.getProperties()) {
            if (property.getArrayIndex() > 0) {
                indexed.add(writer.getName(property.name()).toLowerCase(Locale.ROOT));
            }
        }
        var result = new ArrayList<Entry>();
        for (var property : tree.getProperties()) {
            var name = writer.getName(property.name());
            var field = schema.find(owner, name);
            if (property.getType() == ARRAY) {
                array(property, name, field, result);
                continue;
            }
            var key = indexed.contains(name.toLowerCase(Locale.ROOT))
                    ? name + "(" + property.getArrayIndex() + ")"
                    : name;
            var before = unread;
            var value = value(property, field);
            if (value == null) {
                unread++;
            }
            result.add(new Entry(key, value, property.getRaw().length, unread - before - (value == null ? 1 : 0)));
        }
        return result;
    }

    private void array(Property property, String name, @Nullable Field field, List<Entry> result) throws IOException {
        var inner = field == null ? null : field.inner();
        var array = property.getArray();
        if (array != null && array.getObjects() != null) {
            var objects = array.getObjects();
            for (var i = 0; i < objects.size(); i++) {
                result.add(new Entry(name + "(" + i + ")", reference(objects.get(i)), 0, 0));
            }
            return;
        }
        var elements = array != null ? array.getElements() : taggedElements(property.getRaw());
        if (elements != null) {
            var struct = inner == null ? null : inner.struct();
            for (var i = 0; i < elements.size(); i++) {
                var before = unread;
                var value = "(" + inline(elements.get(i), struct) + ")";
                result.add(new Entry(name + "(" + i + ")", value, 0, unread - before));
            }
            return;
        }
        var values = plainElements(property.getRaw(), inner);
        if (values == null) {
            unread++;
            result.add(new Entry(name, null, property.getRaw().length, 0));
            return;
        }
        for (var i = 0; i < values.size(); i++) {
            result.add(new Entry(name + "(" + i + ")", values.get(i), 0, 0));
        }
    }

    private @Nullable List<TaggedProperties> taggedElements(byte[] raw) {
        try {
            var cursor = new Cursor(raw, 0, raw.length);
            var count = PackageBytes.count(cursor.readIndex());
            if (count == 0) {
                return cursor.position == cursor.end ? List.of() : null;
            }
            var result = new ArrayList<TaggedProperties>(count);
            for (var i = 0; i < count; i++) {
                result.add(TaggedProperties.read(cursor, writer));
            }
            return cursor.position == cursor.end ? result : null;
        } catch (IOException | RuntimeException otherLayout) {
            return null;
        }
    }

    private @Nullable List<String> plainElements(byte[] raw, @Nullable Field inner) {
        if (inner == null) {
            return null;
        }
        try {
            var cursor = new Cursor(raw, 0, raw.length);
            var count = PackageBytes.count(cursor.readIndex());
            var result = new ArrayList<String>(count);
            for (var i = 0; i < count; i++) {
                var value = plain(cursor, inner);
                if (value == null) {
                    return null;
                }
                result.add(value);
            }
            return cursor.position == cursor.end ? result : null;
        } catch (IOException | RuntimeException otherLayout) {
            return null;
        }
    }

    private @Nullable String plain(Cursor cursor, Field inner) throws IOException {
        return switch (inner.kind()) {
            case "FloatProperty" -> number(Float.intBitsToFloat(cursor.readI32()));
            case "IntProperty" -> Integer.toString(cursor.readI32());
            case "ByteProperty" -> enumName(cursor.readU8(), inner);
            case "NameProperty" -> name(writer.getName(cursor.readIndex()));
            case "ObjectProperty", "ClassProperty" -> reference(cursor.readIndex());
            case "StrProperty" -> quote(korean(cursor.readString()));
            case "StructProperty" -> {
                var struct = inner.struct() == null ? "" : inner.struct().toLowerCase(Locale.ROOT);
                var size = struct.equals("color") ? 4 : struct.equals("vector") || struct.equals("rotator") ? 12 : 0;
                if (size == 0) {
                    yield null;
                }
                var start = cursor.position;
                cursor.skip(size);
                yield binary(struct, Arrays.copyOfRange(cursor.bytes, start, start + size));
            }
            default -> null;
        };
    }

    private @Nullable String value(Property property, @Nullable Field field) throws IOException {
        var raw = property.getRaw();
        return switch (property.getType()) {
            case BOOL -> property.isTrue() ? "True" : "False";
            case BYTE -> raw.length == 1 ? enumName(property.getByteValue(), field) : null;
            case INT -> raw.length == 4 ? Integer.toString(property.getIntValue()) : null;
            case FLOAT -> raw.length == 4 ? number(property.getFloatValue()) : null;
            case OBJECT -> reference(property.getIndex());
            case NAME -> name(writer.getName(property.getIndex()));
            case STR -> quote(korean(property.getString()));
            case STRUCT -> struct(property);
            default -> null;
        };
    }

    private @Nullable String struct(Property property) throws IOException {
        var structName = writer.getName(property.getStructName());
        var nested = property.getStruct();
        if (nested != null) {
            return "(" + inline(nested, structName) + ")";
        }
        return binary(structName.toLowerCase(Locale.ROOT), property.getRaw());
    }

    private String inline(TaggedProperties tree, @Nullable String struct) throws IOException {
        var parts = new ArrayList<String>();
        for (var entry : entries(tree, struct)) {
            if (entry.value() != null) {
                parts.add(entry.key() + "=" + entry.value());
            }
        }
        return String.join(",", parts);
    }

    private List<Integer> references(TaggedProperties tree) throws IOException {
        var result = new ArrayList<Integer>();
        for (var property : tree.getProperties()) {
            if (property.getType() == OBJECT) {
                result.add(property.getIndex());
            } else if (property.getStruct() != null) {
                result.addAll(references(property.getStruct()));
            } else if (property.getArray() != null) {
                var array = property.getArray();
                if (array.getObjects() != null) {
                    result.addAll(array.getObjects());
                } else if (array.getElements() != null) {
                    for (var element : array.getElements()) {
                        result.addAll(references(element));
                    }
                }
            }
        }
        return result;
    }

    private String reference(int reference) throws IOException {
        return reference(writer, reference);
    }

    private String reference(ClassPackageWriter source, int reference) throws IOException {
        if (reference == 0) {
            return "None";
        }
        return source.getClassOf(reference) + "'" + path(source, reference) + "'";
    }

    private String path(int reference) throws IOException {
        return path(writer, reference);
    }

    private String path(ClassPackageWriter source, int reference) throws IOException {
        var path = source.getObjectPath(reference);
        var own = source.getPackageName() + ".";
        return reference > 0 && path.startsWith(own) ? packageName + "." + path.substring(own.length()) : path;
    }

    private String scriptText(int classExport) throws IOException {
        for (var child : writer.getChildren(classExport)) {
            if (!writer.getClassName(child).equalsIgnoreCase("TextBuffer")
                    || !writer.exportName(child).equalsIgnoreCase("ScriptText")) {
                continue;
            }
            try {
                var payload = writer.getPayload(child);
                var cursor = new Cursor(payload, 0, payload.length);
                TaggedProperties.read(cursor, writer);
                cursor.readI32();
                cursor.readI32();
                return korean(cursor.readString());
            } catch (IOException | RuntimeException unreadable) {
                return "";
            }
        }
        return "";
    }

    private void line(String text) {
        out.append(text).append('\n');
    }

    private static String enumName(int value, @Nullable Field field) {
        var values = field == null ? null : field.values();
        return values != null && value < values.size() ? values.get(value) : Integer.toString(value);
    }

    private static @Nullable String binary(String struct, byte[] raw) {
        var binary = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        return switch (struct) {
            case "vector" ->
                raw.length == 12
                        ? "(X=" + number(binary.getFloat(0)) + ",Y=" + number(binary.getFloat(4)) + ",Z="
                                + number(binary.getFloat(8)) + ")"
                        : null;
            case "rotator" ->
                raw.length == 12
                        ? "(Pitch=" + binary.getInt(0) + ",Yaw=" + binary.getInt(4) + ",Roll=" + binary.getInt(8) + ")"
                        : null;
            case "color" ->
                raw.length == 4
                        ? "(B=" + (raw[0] & 255) + ",G=" + (raw[1] & 255) + ",R=" + (raw[2] & 255) + ",A="
                                + (raw[3] & 255) + ")"
                        : null;
            default -> null;
        };
    }

    private static String missing(int count) {
        if (count == 0) {
            return "";
        }
        return count == 1
                ? " // 1 field left out, in a layout that Cruma does not read"
                : " // " + count + " fields left out, in a layout that Cruma does not read";
    }

    private static @Nullable String decode(Charset charset, byte[] bytes) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException otherCharset) {
            return null;
        }
    }

    private static String bytes(int count) {
        return count == 1 ? "1 byte" : count + " bytes";
    }

    private static String name(String value) {
        return value.isEmpty() || value.chars().anyMatch(c -> c <= ' ' || c == ',' || c == ')' || c == '"')
                ? quote(value)
                : value;
    }
}

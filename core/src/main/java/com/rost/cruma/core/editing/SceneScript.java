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

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class SceneScript {
    static final String ITEM = "L2SceneItem";
    static final String CAMERA = "CameraItem";
    static final String SCREEN = "ScreenItem";
    static final String MUSIC = "MusicItem";
    static final String NPC = "NpcItem";
    static final String PC = "PcItem";
    static final String VISUAL = "VisualItem";
    private static final Set<String> TYPED = Set.of(
            "BoolProperty",
            "ByteProperty",
            "IntProperty",
            "FloatProperty",
            "StrProperty",
            "NameProperty",
            "ObjectProperty",
            "ClassProperty",
            "StructProperty",
            "ArrayProperty");

    private final Map<String, List<Field>> fields;
    private final Map<String, List<String>> enums;

    SceneScript(Map<String, List<Field>> fields, Map<String, List<String>> enums) {
        this.fields = new HashMap<>();
        for (var entry : fields.entrySet()) {
            this.fields.put(key(entry.getKey()), List.copyOf(entry.getValue()));
        }
        this.enums = new HashMap<>();
        for (var entry : enums.entrySet()) {
            this.enums.put(key(entry.getKey()), List.copyOf(entry.getValue()));
        }
    }

    record Field(String name, int type, int entries) {}

    public static SceneScript read(Path clientRoot) throws IOException {
        var file = find(find(clientRoot, "System"), "Engine.u");
        return of(new PackageBytes(file, Files.readAllBytes(file)));
    }

    static SceneScript of(PackageBytes engine) throws IOException {
        var writer = ClassPackageWriter.open(engine);
        var item = writer.findClassExport(ITEM);
        if (item == 0) {
            throw malformed("This client has no scene items: its Engine.u has no " + ITEM);
        }
        var data = ClassExportData.read(writer.getPayload(item), ITEM, writer);
        if (data == null) {
            throw malformed("The class " + ITEM + " has an unknown layout");
        }
        var fields = new HashMap<String, List<Field>>();
        var enums = new HashMap<String, List<String>>();
        fields.put(ITEM, chain(writer, data.children, fields, enums));
        return new SceneScript(fields, enums);
    }

    List<Field> getFields(String struct) {
        return fields.getOrDefault(key(struct), List.of());
    }

    @Nullable
    Field findField(String struct, String name) {
        for (var field : getFields(struct)) {
            if (field.name().equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    boolean has(String struct, String name) {
        return findField(struct, name) != null;
    }

    int countValues(String enumName) {
        return enums.getOrDefault(key(enumName), List.of()).size();
    }

    private static List<Field> chain(
            ClassPackageWriter writer, int first, Map<String, List<Field>> fields, Map<String, List<String>> enums)
            throws IOException {
        var result = new ArrayList<Field>();
        var seen = 0;
        for (var at = first; at > 0; seen++) {
            if (seen > PackageBytes.MAX_ENTRIES) {
                throw malformed("The fields of " + ITEM + " form a loop");
            }
            var kind = writer.getClassName(at);
            var name = writer.exportName(at);
            var payload = writer.getPayload(at);
            var cursor = new Cursor(payload, 0, payload.length);
            TaggedProperties.read(cursor, writer);
            cursor.readIndex();
            var next = cursor.readIndex();
            switch (kind) {
                case "Struct" -> {
                    cursor.readIndex();
                    fields.put(name, chain(writer, cursor.readIndex(), fields, enums));
                }
                case "Enum" -> {
                    var count = PackageBytes.count(cursor.readIndex());
                    var values = new ArrayList<String>(count);
                    for (var i = 0; i < count; i++) {
                        values.add(writer.getName(cursor.readIndex()));
                    }
                    enums.put(name, values);
                }
                default -> {
                    if (TYPED.contains(kind)) {
                        result.add(new Field(name, tagOf(kind), cursor.readU16()));
                    }
                }
            }
            at = next;
        }
        return result;
    }

    private static int tagOf(String kind) {
        return switch (kind) {
            case "BoolProperty" -> BOOL;
            case "ByteProperty" -> BYTE;
            case "IntProperty" -> INT;
            case "FloatProperty" -> FLOAT;
            case "StrProperty" -> STR;
            case "NameProperty" -> NAME;
            case "StructProperty" -> STRUCT;
            case "ArrayProperty" -> ARRAY;
            default -> OBJECT;
        };
    }

    private static Path find(Path folder, String name) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.filter(entry -> entry.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> malformed("No " + name + " in " + folder));
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}

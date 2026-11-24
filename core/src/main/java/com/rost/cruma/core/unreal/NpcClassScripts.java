package com.rost.cruma.core.unreal;

import com.rost.cruma.core.npc.script.ScriptValues.Name;
import com.rost.cruma.core.npc.script.ScriptValues.Rotator;
import com.rost.cruma.core.npc.script.ScriptValues.Vector;
import com.rost.cruma.core.npc.script.ScriptedClass.Default;
import com.rost.cruma.core.npc.script.ScriptedClass.Source;
import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class NpcClassScripts {
    private static final int MOST_PARENTS = 8;
    private static final Set<String> BASE_PACKAGES = Set.of("engine", "core");
    private static final String BASE_CLASS = "lineagepawn";

    private NpcClassScripts() {}

    public static List<Source> read(String npcClass, Function<String, @Nullable UArchive> packages) {
        var sources = new ArrayList<Source>();
        var name = npcClass;
        for (var parents = 0; parents < MOST_PARENTS && name != null; parents++) {
            var dot = name.indexOf('.');
            if (dot <= 0) {
                break;
            }
            var packageName = name.substring(0, dot);
            var className = name.substring(dot + 1);
            if (BASE_PACKAGES.contains(packageName.toLowerCase(Locale.ROOT))
                    || className.equalsIgnoreCase(BASE_CLASS)) {
                break;
            }
            var archive = packages.apply(packageName);
            if (archive == null) {
                break;
            }
            var index = EffectClasses.classIndex(archive, className);
            if (index == 0) {
                break;
            }
            var text = text(archive, index);
            if (text == null || text.isBlank()) {
                break;
            }
            sources.add(new Source(
                    packageName + "." + archive.getExportMap().get(index - 1).getObjectName(),
                    text,
                    defaults(archive, index)));
            name = NpcClasses.parent(archive, index, packageName);
        }
        return sources;
    }

    public static boolean hasEmptyText(String npcClass, Function<String, @Nullable UArchive> packages) {
        var dot = npcClass.indexOf('.');
        var archive = dot > 0 ? packages.apply(npcClass.substring(0, dot)) : null;
        if (archive == null) {
            return false;
        }
        var index = EffectClasses.classIndex(archive, npcClass.substring(dot + 1));
        var text = index == 0 ? null : text(archive, index);
        return text != null && text.isBlank();
    }

    static @Nullable String text(UArchive archive, int classIndex) {
        var exports = archive.getExportMap();
        var named = scriptTextReference(archive, classIndex);
        if (named > 0
                && named <= exports.size()
                && "TextBuffer".equals(exports.get(named - 1).getClassName())) {
            var text = readTextBuffer(archive, exports.get(named - 1));
            if (text != null) {
                return text;
            }
        }
        for (var export : exports) {
            if (export.getPackageIndex() == classIndex
                    && "TextBuffer".equals(export.getClassName())
                    && "ScriptText".equalsIgnoreCase(export.getObjectName())) {
                return readTextBuffer(archive, export);
            }
        }
        return null;
    }

    private static int scriptTextReference(UArchive archive, int classIndex) {
        var export = archive.getExportMap().get(classIndex - 1);
        var saved = archive.getObjectEnd();
        try {
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            archive.readIndex();
            archive.readIndex();
            return archive.readIndex();
        } catch (RuntimeException unreadable) {
            return 0;
        } finally {
            archive.setObjectEnd(saved);
        }
    }

    private static @Nullable String readTextBuffer(UArchive archive, UObjectExport export) {
        var saved = archive.getObjectEnd();
        try {
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            archive.getPropertyExtractor().extractProperties();
            archive.readInt();
            archive.readInt();
            return string(archive);
        } catch (RuntimeException unreadable) {
            return null;
        } finally {
            archive.setObjectEnd(saved);
        }
    }

    private static String string(UArchive archive) {
        var length = archive.readIndex();
        if (length == 0) {
            return "";
        }
        if (length < 0) {
            var characters = new char[-length - 1];
            for (var i = 0; i < characters.length; i++) {
                characters[i] = (char) archive.readUInt16();
            }
            archive.readUInt16();
            return new String(characters);
        }
        var bytes = new byte[length - 1];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = archive.readByte();
        }
        archive.readByte();
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static List<Default> defaults(UArchive archive, int index) {
        var defaults = new ArrayList<Default>();
        var properties = EffectClasses.defaults(archive, index);
        for (var property : properties == null ? List.<UProperty>of() : properties) {
            Object value;
            try {
                value = switch (property.getType()) {
                    case Int -> property.getInt32Value();
                    case Float -> property.getFloatValue();
                    case Bool -> property.isTrue();
                    case Byte -> (int) property.getUInt8Value();
                    case Name -> new Name(archive.resolveName(property.getIndexValue()));
                    case Str -> property.getStringValue();
                    case Vector -> vector(property.getVectorValue());
                    case Rotator -> rotator(property.getRotatorValue());
                    case Struct ->
                        switch (property.getStructName()) {
                            case "Vector" -> vector(property.getVectorValue());
                            case "Rotator" -> rotator(property.getRotatorValue());
                            default -> null;
                        };
                    default -> null;
                };
            } catch (RuntimeException unreadable) {
                value = null;
            }
            if (value != null) {
                defaults.add(new Default(property.getName(), property.getArrayIndex(), value));
            }
        }
        return defaults;
    }

    private static Vector vector(Vec3 vector) {
        return new Vector(vector.getX(), vector.getY(), vector.getZ());
    }

    private static Rotator rotator(URotator rotator) {
        return new Rotator(rotator.pitch(), rotator.yaw(), rotator.roll());
    }
}

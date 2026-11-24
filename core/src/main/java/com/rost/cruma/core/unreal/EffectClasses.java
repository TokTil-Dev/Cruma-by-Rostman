package com.rost.cruma.core.unreal;

import com.rost.cruma.core.npc.script.ClassScript;
import com.rost.cruma.core.unreal.UActor.EmitterActor;
import com.rost.cruma.core.unreal.UProperty.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

public final class EffectClasses {
    public static final String CUSTOM_TEXTURES = "LineageEffect_Custom_T";

    private static final Set<String> DRAWN = Set.of("SpriteEmitter", "MeshEmitter", "VertMeshEmitter", "BeamEmitter");
    private static final Set<String> UNDRAWN = Set.of("SparkEmitter", "TrailEmitter", "RibbonEmitter");
    private static final String PROJECTILE = "NSkillProjectile";

    private EffectClasses() {}

    public record Quake(float intensity, float x, float y, float range, int count, float seconds, float delay) {}

    public record Effect(
            String packageName,
            String name,
            String superName,
            int sprites,
            int meshes,
            int beams,
            int undrawn,
            boolean scripted) {
        public String path() {
            return packageName + "." + name;
        }

        public int emitters() {
            return sprites + meshes + beams + undrawn;
        }
    }

    public static List<Effect> list(UArchive archive) {
        var exports = archive.getExportMap();
        var sprites = new int[exports.size() + 1];
        var meshes = new int[exports.size() + 1];
        var beams = new int[exports.size() + 1];
        var undrawn = new int[exports.size() + 1];
        var scripted = new boolean[exports.size() + 1];
        for (var export : exports) {
            var owner = export.getPackageIndex();
            if (owner <= 0) {
                continue;
            }
            switch (export.getClassName()) {
                case "SpriteEmitter" -> sprites[owner]++;
                case "MeshEmitter", "VertMeshEmitter" -> meshes[owner]++;
                case "BeamEmitter" -> beams[owner]++;
                case "Function", "State" -> scripted[owner] = true;
                default -> {
                    if (UNDRAWN.contains(export.getClassName())) {
                        undrawn[owner]++;
                    }
                }
            }
        }
        var effects = new ArrayList<Effect>();
        for (var index = 1; index <= exports.size(); index++) {
            var export = exports.get(index - 1);
            if (!isClass(export) || sprites[index] + meshes[index] + beams[index] + undrawn[index] == 0) {
                continue;
            }
            effects.add(new Effect(
                    archive.getName(),
                    export.getObjectName(),
                    String.valueOf(export.getSuperName()),
                    sprites[index],
                    meshes[index],
                    beams[index],
                    undrawn[index],
                    scripted[index]));
        }
        effects.sort(Comparator.comparing(effect -> effect.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(effects);
    }

    public static @Nullable Quake quake(UArchive archive, String className) {
        var index = classIndex(archive, className);
        var defaults = index == 0 ? null : defaults(archive, index);
        if (defaults == null) {
            return null;
        }
        var used = false;
        var intensity = 0f;
        var x = 0f;
        var y = 0f;
        var range = 0f;
        var count = 0;
        var seconds = 0f;
        var delay = 0f;
        for (var property : defaults) {
            switch (property.getName()) {
                case "bUseQuake" -> used = property.isTrue();
                case "ShakeIntensity" -> intensity = property.getFloatValue();
                case "ShakeVector" -> {
                    var vector = property.getVectorValue();
                    x = vector.getX();
                    y = vector.getY();
                }
                case "ShakeRange" -> range = property.getFloatValue();
                case "ShakeCount" -> count = property.getInt32Value();
                case "ShakeTime" -> seconds = property.getFloatValue();
                case "EQ_InitialDelay" -> delay = property.getFloatValue();
                default -> {
                }
            }
        }
        return used ? new Quake(intensity, x, y, range, count, seconds, delay) : null;
    }

    public static @Nullable Projectile projectile(UArchive archive, String className) {
        var index = classIndex(archive, className);
        if (index == 0) {
            return null;
        }
        var flies = false;
        var chain = new ArrayList<Integer>();
        for (var at = index; at > 0 && chain.size() < 32 && !chain.contains(at); at = superIndex(archive, at)) {
            chain.add(at);
            flies |= PROJECTILE.equalsIgnoreCase(
                    String.valueOf(archive.getExportMap().get(at - 1).getSuperName()));
        }
        if (!flies) {
            return null;
        }
        var export = archive.getExportMap().get(index - 1);
        var loads = new ArrayList<String>();
        for (var at : chain) {
            var text = NpcClassScripts.text(archive, at);
            if (text != null && !text.isBlank()) {
                var name = archive.getExportMap().get(at - 1).getObjectName();
                loads.addAll(ClassScript.parse(name, text).getClassLiterals());
            }
        }
        return new Projectile(
                new Effect(
                        archive.getName(),
                        export.getObjectName(),
                        String.valueOf(export.getSuperName()),
                        0,
                        0,
                        0,
                        0,
                        true),
                loads);
    }

    public record Projectile(Effect effect, List<String> loads) {
        public Projectile {
            loads = List.copyOf(loads);
        }
    }

    public static @Nullable EmitterActor actor(UArchive archive, String className) {
        return actor(archive, className, ignored -> {});
    }

    public static @Nullable EmitterActor actor(UArchive archive, String className, Consumer<EmitterActor> parents) {
        var index = classIndex(archive, className);
        if (index == 0) {
            return null;
        }
        var chain = new ArrayList<Integer>();
        for (var at = index; at > 0 && chain.size() < 32 && !chain.contains(at); at = superIndex(archive, at)) {
            chain.add(at);
        }
        var actor = new EmitterActor(archive);
        actor.setName(archive.getExportMap().get(index - 1).getObjectName());
        parents.accept(actor);
        int[] emitters = null;
        for (var at = chain.size() - 1; at >= 0; at--) {
            var defaults = defaults(archive, chain.get(at));
            if (defaults == null) {
                continue;
            }
            for (var property : defaults) {
                if ("Emitters".equals(property.getName())) {
                    emitters = property.getObjectArray();
                }
                actor.setProperty(property);
            }
        }
        if (emitters == null) {
            var children = new ArrayList<Integer>();
            var exports = archive.getExportMap();
            for (var child = 1; child <= exports.size(); child++) {
                var export = exports.get(child - 1);
                if (export.getPackageIndex() == index
                        && (DRAWN.contains(export.getClassName()) || UNDRAWN.contains(export.getClassName()))) {
                    children.add(child);
                }
            }
            actor.setProperty(UProperty.builder()
                    .name("Emitters")
                    .type(Type.Array)
                    .objectArray(children.stream().mapToInt(Integer::intValue).toArray())
                    .build());
        }
        return actor;
    }

    static int classIndex(UArchive archive, String className) {
        var exports = archive.getExportMap();
        for (var index = 1; index <= exports.size(); index++) {
            var export = exports.get(index - 1);
            if (isClass(export) && export.getObjectName().equalsIgnoreCase(className)) {
                return index;
            }
        }
        return 0;
    }

    static @Nullable List<UProperty> defaults(UArchive archive, int index) {
        var export = archive.getExportMap().get(index - 1);
        for (var cppText : new boolean[] {true, false}) {
            for (var hideCategories : new boolean[] {true, false}) {
                var properties = defaults(archive, export, cppText, hideCategories);
                if (properties != null) {
                    return properties;
                }
            }
        }
        return null;
    }

    private static boolean isClass(UObjectExport export) {
        return "None".equals(export.getClassName());
    }

    private static int superIndex(UArchive archive, int index) {
        var export = archive.getExportMap().get(index - 1);
        var saved = archive.getObjectEnd();
        try {
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            var parent = archive.readIndex();
            return parent > 0 && isClass(archive.getExportMap().get(parent - 1)) ? parent : 0;
        } catch (RuntimeException failure) {
            return 0;
        } finally {
            archive.setObjectEnd(saved);
        }
    }

    private static @Nullable List<UProperty> defaults(
            UArchive archive, UObjectExport export, boolean cppText, boolean hideCategories) {
        var start = export.getSerialOffset();
        var end = start + export.getSerialSize();
        var saved = archive.getObjectEnd();
        try {
            archive.setPosition(start);
            archive.setObjectEnd(end);
            archive.readIndex();
            archive.readIndex();
            archive.readIndex();
            archive.readIndex();
            if (!archive.resolveName(archive.readIndex()).equalsIgnoreCase(export.getObjectName())) {
                return null;
            }
            if (cppText) {
                archive.readIndex();
            }
            archive.readInt();
            archive.readInt();
            if (archive.readInt() != 0) {
                return null;
            }
            archive.readUInt64();
            archive.readUInt64();
            archive.readInt16();
            archive.readInt();
            archive.readInt();
            archive.skipBytes(16);
            var dependencies = count(archive);
            for (var i = 0; i < dependencies; i++) {
                archive.readIndex();
                archive.readInt();
                archive.readInt();
            }
            skipIndices(archive);
            archive.readIndex();
            archive.readIndex();
            if (hideCategories) {
                skipIndices(archive);
            }
            var properties = archive.getPropertyExtractor().extractProperties();
            return archive.getOffset() == end ? properties : null;
        } catch (RuntimeException failure) {
            return null;
        } finally {
            archive.setObjectEnd(saved);
        }
    }

    private static void skipIndices(UArchive archive) {
        var count = count(archive);
        for (var i = 0; i < count; i++) {
            archive.readIndex();
        }
    }

    private static int count(UArchive archive) {
        var count = archive.readIndex();
        if (count < 0 || count > 4096) {
            throw new IllegalStateException("Implausible class table size " + count);
        }
        return count;
    }
}

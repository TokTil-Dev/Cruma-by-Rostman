package com.rost.cruma.core.world;

import com.rost.cruma.core.npc.script.ScriptValues.Name;
import com.rost.cruma.core.npc.script.ScriptedClass.Default;
import com.rost.cruma.core.npc.script.ScriptedClass.Source;
import com.rost.cruma.core.unreal.MapPawnClasses;
import com.rost.cruma.core.unreal.NpcClassScripts;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.ULineagePawn;
import com.rost.cruma.core.unreal.UPackage;
import com.rost.cruma.core.unreal.UProperty;
import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public record MapPawn(
        String path,
        String mesh,
        List<String> skins,
        Vec3 location,
        URotator rotation,
        Vec3 scale,
        Vec3 prePivot,
        Animations animations,
        boolean castsShadow,
        String tag,
        @Nullable Script script) {
    public static final String PAWN_TAG = "LineagePawn";

    public static final String PAWN_CLASS = "LineagePawn";

    public MapPawn {
        skins = List.copyOf(skins);
    }

    public MapPawn(
            String path,
            String mesh,
            List<String> skins,
            Vec3 location,
            URotator rotation,
            Vec3 scale,
            Vec3 prePivot,
            Animations animations,
            boolean castsShadow) {
        this(path, mesh, skins, location, rotation, scale, prePivot, animations, castsShadow, PAWN_TAG, null);
    }

    public record Animations(List<String> list, boolean random, String interval, String first) {
        public static final Animations NONE = new Animations(List.of(), false, "", "");

        public Animations {
            list = List.copyOf(list);
        }
    }

    public record Script(String className, List<Source> sources, List<Default> saved) {
        public Script {
            sources = List.copyOf(sources);
            saved = List.copyOf(saved);
        }
    }

    public static List<MapPawn> readAll(UPackage uPackage) {
        var actors = new ArrayList<ULineagePawn>();
        uPackage.loadObjects("LineagePawn", actors);
        var levels = new ArrayList<ULevel>();
        uPackage.loadObjects("Level", levels);
        var held = new LinkedHashSet<Integer>();
        if (!levels.isEmpty()) {
            var registered = new HashSet<String>();
            for (var level : levels) {
                for (var reference : level.getActors()) {
                    var path = reference.peekExportSourcePath();
                    if (path != null) {
                        registered.add(path);
                    }
                    if (reference.getIndex() > 0) {
                        held.add(reference.getIndex());
                    }
                }
            }
            actors.removeIf(actor -> !registered.contains(actor.getSourcePath()));
        }
        var result = new ArrayList<MapPawn>(actors.size());
        for (var actor : actors) {
            var mesh = actor.getSavedMesh();
            if (actor.isDeleteMe() || actor.isHidden() || !actor.isClientSide() || isNone(mesh)) {
                continue;
            }
            result.add(new MapPawn(
                    actor.getFullName(),
                    mesh,
                    actor.getSkinNames(),
                    actor.getLocation(),
                    actor.getRotation(),
                    actor.getScale(),
                    actor.getPrePivot(),
                    new Animations(
                            actor.getAnimations(),
                            actor.isRandom(),
                            isNone(actor.getInterval()) ? "" : actor.getInterval(),
                            isNone(actor.getFirst()) ? "" : actor.getFirst()),
                    actor.hasActorShadows(),
                    isNone(actor.getTag()) ? PAWN_TAG : actor.getTag(),
                    null));
        }
        var archive = uPackage.getArchive();
        var scripts = new HashMap<Integer, Script>();
        for (var index : held) {
            var pawn = readPlaced(archive, index, scripts);
            if (pawn != null) {
                result.add(pawn);
            }
        }
        return List.copyOf(result);
    }

    public static float[] computeModelMatrix(float[] actor, float lowest) {
        var matrix = actor.clone();
        for (var row = 0; row < 3; row++) {
            matrix[12 + row] -= actor[8 + row] * lowest;
        }
        return matrix;
    }

    public String getActorName() {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    public String getClassName() {
        return script == null ? PAWN_CLASS : script.className();
    }

    public long getSeed() {
        return path.hashCode() * 0x9E3779B97F4A7C15L;
    }

    public float[] computeMatrix() {
        return Entity.actorModelMatrix(location, prePivot, rotation.vector(), scale)
                .to(new float[16]);
    }

    public float[] computeModelMatrix(float lowest) {
        return computeModelMatrix(computeMatrix(), lowest);
    }

    private static boolean isNone(String name) {
        return name.isBlank() || name.equalsIgnoreCase(UArchive.NONE_NAME);
    }

    private static @Nullable MapPawn readPlaced(UArchive archive, int index, Map<Integer, Script> scripts) {
        var exports = archive.getExportMap();
        if (index > exports.size()) {
            return null;
        }
        var export = exports.get(index - 1);
        var chain = MapPawnClasses.findChain(archive, export.getClassIndex());
        if (chain == null) {
            return null;
        }
        var loader = archive.getObjectLoader();
        var own = loader.exportProperties(export);
        var values = new HashMap<String, UProperty>();
        for (var at = chain.size() - 1; at >= 0; at--) {
            for (var property : MapPawnClasses.getDefaults(archive, chain.get(at))) {
                values.put(key(property.getName(), property.getArrayIndex()), property);
            }
        }
        for (var property : own) {
            values.put(key(property.getName(), property.getArrayIndex()), property);
        }
        if (isTrue(values, "IsClientSidePawn") || isTrue(values, "bHidden") || isTrue(values, "bDeleteMe")) {
            return null;
        }
        var mesh = values.get(key("Mesh", 0));
        var meshReference = mesh == null || mesh.getType() != UProperty.Type.Object ? 0 : mesh.getIndexValue();
        var meshName = meshReference == 0 ? null : loader.peekObjectFullName(meshReference);
        if (meshName == null || !"SkeletalMesh".equals(loader.peekObjectClassName(meshReference))) {
            return null;
        }
        var skins = new ArrayList<String>();
        var skinsProperty = values.get(key("Skins", 0));
        var skinRefs = skinsProperty == null ? null : skinsProperty.getObjectArray();
        for (var ref : skinRefs == null ? new int[0] : skinRefs) {
            var name = ref == 0 ? null : loader.peekObjectFullName(ref);
            skins.add(name == null ? "" : name);
        }
        var drawScale = values.get(key("DrawScale", 0));
        var rotation = values.get(key("Rotation", 0));
        var shadows = values.get(key("bActorShadows", 0));
        var tag = values.get(key("Tag", 0));
        var script = new Script(PAWN_CLASS, List.of(), List.of());
        if (!chain.isEmpty()) {
            var type = scripts.computeIfAbsent(chain.getFirst(), _ -> readScript(archive, chain.getFirst()));
            script = new Script(type.className(), type.sources(), saved(archive, own, chain));
        }
        return new MapPawn(
                archive.getName() + "." + export.getObjectName(),
                meshName,
                skins,
                vector(values, "Location", new Vec3()),
                rotation == null || rotation.getRotatorValue() == null
                        ? new URotator(0, 0, 0)
                        : rotation.getRotatorValue(),
                vector(values, "DrawScale3D", new Vec3(1, 1, 1))
                        .times(drawScale == null ? 1 : drawScale.getFloatValue()),
                vector(values, "PrePivot", new Vec3()),
                Animations.NONE,
                shadows == null || shadows.isTrue(),
                tag == null ? export.getClassName() : archive.resolveName(tag.getIndexValue()),
                script);
    }

    private static Script readScript(UArchive archive, int classIndex) {
        var className = archive.getName() + "."
                + archive.getExportMap().get(classIndex - 1).getObjectName();
        var sources =
                NpcClassScripts.read(className, name -> name.equalsIgnoreCase(archive.getName()) ? archive : null);
        return new Script(className, sources, List.of());
    }

    private static List<Default> saved(UArchive archive, List<UProperty> own, List<Integer> chain) {
        var variables = MapPawnClasses.getVariables(archive, chain);
        var saved = new ArrayList<Default>();
        for (var property : own) {
            if (!variables.contains(property.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Object value = switch (property.getType()) {
                case Int -> property.getInt32Value();
                case Float -> property.getFloatValue();
                case Bool -> property.isTrue();
                case Name -> new Name(archive.resolveName(property.getIndexValue()));
                default -> null;
            };
            if (value != null) {
                saved.add(new Default(property.getName(), property.getArrayIndex(), value));
            }
        }
        return saved;
    }

    private static boolean isTrue(Map<String, UProperty> values, String name) {
        var property = values.get(key(name, 0));
        return property != null && property.getType() == UProperty.Type.Bool && property.isTrue();
    }

    private static Vec3 vector(Map<String, UProperty> values, String name, Vec3 otherwise) {
        var property = values.get(key(name, 0));
        return property == null || property.getVectorValue() == null ? otherwise : property.getVectorValue();
    }

    private static String key(String name, int index) {
        return name.toLowerCase(Locale.ROOT) + "#" + index;
    }
}

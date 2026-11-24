package com.rost.cruma.core.unreal;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class NpcClasses {
    private static final int MOST_PARENTS = 8;
    private static final String RIGHT_HAND = "Sword Bone";
    private static final String LEFT_HAND = "Bow Bone";
    private static final String LEFT_ARM = "Shield Bone";
    private static final String SPINE = "bip01 spine2";
    private static final int NO_BONE = -1;

    private NpcClasses() {}

    public static Body body(String npcClass, Function<String, @Nullable UArchive> packages) {
        var scale = Float.NaN;
        var height = Float.NaN;
        var radius = Float.NaN;
        var weapon = Float.NaN;
        String rightHand = null;
        String leftHand = null;
        String shield = null;
        String spine = null;
        var effectBone = NO_BONE;
        var effectBoneSet = false;
        var name = npcClass;
        for (var parents = 0; parents < MOST_PARENTS && name != null; parents++) {
            var dot = name.indexOf('.');
            var archive = dot > 0 ? packages.apply(name.substring(0, dot)) : null;
            if (archive == null) {
                break;
            }
            var index = EffectClasses.classIndex(archive, name.substring(dot + 1));
            if (index == 0) {
                break;
            }
            var defaults = EffectClasses.defaults(archive, index);
            for (var property : defaults == null ? List.<UProperty>of() : defaults) {
                switch (property.getName().toLowerCase(Locale.ROOT)) {
                    case "drawscale" -> scale = Float.isNaN(scale) ? property.getFloatValue() : scale;
                    case "collisionheight" -> height = Float.isNaN(height) ? property.getFloatValue() : height;
                    case "collisionradius" -> radius = Float.isNaN(radius) ? property.getFloatValue() : radius;
                    case "weaponscale" -> weapon = Float.isNaN(weapon) ? property.getFloatValue() : weapon;
                    case "righthandbone" ->
                        rightHand = rightHand == null ? archive.resolveName(property.getIndexValue()) : rightHand;
                    case "lefthandbone" ->
                        leftHand = leftHand == null ? archive.resolveName(property.getIndexValue()) : leftHand;
                    case "leftarmbone" ->
                        shield = shield == null ? archive.resolveName(property.getIndexValue()) : shield;
                    case "spinebone" -> spine = spine == null ? archive.resolveName(property.getIndexValue()) : spine;
                    case "effectspawnboneidx" -> {
                        if (!effectBoneSet) {
                            effectBone = property.getInt32Value();
                            effectBoneSet = true;
                        }
                    }
                    default -> {
                    }
                }
            }
            name = parent(archive, index, name.substring(0, dot));
        }
        return new Body(
                Float.isNaN(scale) ? 1 : scale,
                Float.isNaN(height) ? 0 : height,
                Float.isNaN(radius) ? 0 : radius,
                Float.isNaN(weapon) ? 0 : weapon,
                rightHand == null ? RIGHT_HAND : rightHand,
                leftHand == null ? LEFT_HAND : leftHand,
                shield == null ? LEFT_ARM : shield,
                spine == null ? SPINE : spine,
                effectBone);
    }

    public static @Nullable String findEffectBone(Body body, List<String> boneNames) {
        if (boneNames.isEmpty()) {
            return null;
        }
        if (body.effectSpawnBone() != NO_BONE) {
            var index = body.effectSpawnBone();
            return index >= 0 && index < boneNames.size() ? boneNames.get(index) : null;
        }
        for (var bone : boneNames) {
            if (bone.equalsIgnoreCase(body.spineBone())) {
                return bone;
            }
        }
        return boneNames.getFirst();
    }

    static @Nullable String parent(UArchive archive, int index, String packageName) {
        var export = archive.getExportMap().get(index - 1);
        var saved = archive.getObjectEnd();
        try {
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            var parent = archive.readIndex();
            if (parent > 0) {
                return packageName + "."
                        + archive.getExportMap().get(parent - 1).getObjectName();
            }
            if (parent < 0) {
                var imports = archive.getImportMap();
                var imported = imports.get(-parent - 1);
                var outer = imported.getPackageIndex();
                return outer < 0 ? imports.get(-outer - 1).getObjectName() + "." + imported.getObjectName() : null;
            }
            return null;
        } finally {
            archive.setObjectEnd(saved);
        }
    }

    public record Body(
            float drawScale,
            float collisionHeight,
            float collisionRadius,
            float weaponScale,
            String rightHand,
            String leftHand,
            String shield,
            String spineBone,
            int effectSpawnBone) {
        public static final Body UNKNOWN = new Body(1, 0);

        public Body(float drawScale, float collisionHeight) {
            this(drawScale, collisionHeight, 0);
        }

        public Body(float drawScale, float collisionHeight, float collisionRadius) {
            this(drawScale, collisionHeight, collisionRadius, 0, RIGHT_HAND, LEFT_HAND, LEFT_ARM);
        }

        public Body(
                float drawScale,
                float collisionHeight,
                float collisionRadius,
                float weaponScale,
                String rightHand,
                String leftHand,
                String shield) {
            this(drawScale, collisionHeight, collisionRadius, weaponScale, rightHand, leftHand, shield, SPINE, NO_BONE);
        }
    }
}

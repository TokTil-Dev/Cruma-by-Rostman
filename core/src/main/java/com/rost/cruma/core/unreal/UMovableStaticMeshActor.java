package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UProperty.Type;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.L2Swing;
import com.rost.cruma.core.world.L2Swing.Saved;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class UMovableStaticMeshActor extends StaticMeshActor {
    public static final int PHYS_L2_MOVEMENT = 19;

    private final List<String> movementTags = new ArrayList<>();
    private Vec3 accelRatio = new Vec3();
    private URotator rotatorRate = new URotator(0, 0, 0);
    private URotator rotatorMax = new URotator(0, 0, 0);
    private boolean maxRandom;
    private boolean randomStart = true;
    private boolean initMove;
    private URotator originRotator = new URotator(0, 0, 0);
    private Vec3 cycleDeltaTime = new Vec3();
    private Vec3 currentCycleTime = new Vec3();
    private Vec3 currentMax = new Vec3();
    private boolean swingResolved;
    private @Nullable L2Swing resolvedSwing;

    public UMovableStaticMeshActor(UArchive archive) {
        super(archive, false);
        physics = PHYS_L2_MOVEMENT;
    }

    static void readMovementTag(List<String> tags, UArchive archive, UProperty property) {
        if (property.getType() == Type.Name) {
            var index = property.getArrayIndex();
            while (tags.size() <= index) {
                tags.add(UArchive.NONE_NAME);
            }
            tags.set(index, archive.resolveName(property.getIndexValue()));
            return;
        }
        if (property.getType() != Type.Array) {
            return;
        }
        tags.clear();
        var names = property.getObjectArray();
        if (names != null) {
            for (var name : names) {
                tags.add(archive.resolveName(name));
            }
        }
    }

    @Override
    public boolean setProperty(UProperty property) {
        if (super.setProperty(property)) {
            return true;
        }
        return switch (property.getName()) {
            case "L2MovementTag" -> {
                readMovementTag(movementTags, archive, property);
                yield true;
            }
            case "L2AccelRatio" -> {
                accelRatio = rotatorTime(property);
                yield true;
            }
            case "L2RotatorRate" -> {
                rotatorRate = property.getRotatorValue();
                yield true;
            }
            case "L2RotatorMax" -> {
                rotatorMax = property.getRotatorValue();
                yield true;
            }
            case "bUseL2RotatorMaxRandom" -> {
                maxRandom = property.isTrue();
                yield true;
            }
            case "bUseL2RotatorRandomStart" -> {
                randomStart = property.isTrue();
                yield true;
            }
            case "bL2InitMove" -> {
                initMove = property.isTrue();
                yield true;
            }
            case "L2OrgRotator" -> {
                originRotator = property.getRotatorValue();
                yield true;
            }
            case "L2CycleDeltaTime" -> {
                cycleDeltaTime = rotatorTime(property);
                yield true;
            }
            case "L2CurrentCycleTime" -> {
                currentCycleTime = rotatorTime(property);
                yield true;
            }
            case "L2CurrentMax" -> {
                currentMax = rotatorTime(property);
                yield true;
            }
            default -> false;
        };
    }

    public boolean isSwingResolved() {
        return swingResolved;
    }

    public @Nullable L2Swing getResolvedSwing() {
        return resolvedSwing;
    }

    public void setResolvedSwing(@Nullable L2Swing swing) {
        resolvedSwing = swing;
        swingResolved = true;
    }

    public List<String> getMovementTags() {
        return List.copyOf(movementTags);
    }

    public @Nullable L2Swing getSwing(List<L2Swing.Follower> followers, @Nullable String carrier) {
        if (isPhysicsSaved() || isDeleteMe()) {
            return null;
        }
        var swing = new L2Swing(
                getFullName(),
                getLocation(),
                getRotation(),
                new L2Swing.Axis(rotatorMax.pitch(), rotatorRate.pitch(), accelRatio.getX()),
                new L2Swing.Axis(rotatorMax.roll(), rotatorRate.roll(), accelRatio.getY()),
                new L2Swing.Axis(rotatorMax.yaw(), rotatorRate.yaw(), accelRatio.getZ()),
                maxRandom,
                randomStart,
                initMove ? new Saved(originRotator, currentMax, cycleDeltaTime, currentCycleTime) : null,
                carrier,
                followers,
                getFullName().hashCode());
        return swing.isMoving() ? swing : null;
    }

    private static Vec3 rotatorTime(UProperty property) {
        var parts = property.getSubProperties();
        if (parts == null || parts.isEmpty()) {
            return new Vec3();
        }
        Map<String, UProperty> fields = parts.getFirst();
        return new Vec3(field(fields, "PitchTime"), field(fields, "RollTime"), field(fields, "YawTime"));
    }

    private static float field(Map<String, UProperty> fields, String name) {
        var value = fields.get(name);
        return value == null ? 0f : value.getFloatValue();
    }
}

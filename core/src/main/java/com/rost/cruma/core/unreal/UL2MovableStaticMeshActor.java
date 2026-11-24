package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.L2Movement;
import com.rost.cruma.core.world.L2Movement.Channel;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class UL2MovableStaticMeshActor extends StaticMeshActor {
    private Vec3 originLocation = new Vec3();
    private URotator originRotation = new URotator(0, 0, 0);
    private Vec3 originScale = new Vec3();
    private Vec3 deltaLocation = new Vec3();
    private URotator deltaRotation = new URotator(0, 0, 0);
    private Vec3 deltaScale = new Vec3();
    private final Vec3[] max = {new Vec3(), new Vec3(), new Vec3()};
    private final Vec3[] rate = {new Vec3(), new Vec3(), new Vec3()};
    private final Vec3[] phase = {new Vec3(), new Vec3(), new Vec3()};
    private final Vec3[] ratio = {new Vec3(), new Vec3(), new Vec3()};
    private boolean initialized;
    private boolean randomStart = true;
    private boolean randomMax;
    private boolean rotatedTranslation;
    private final List<String> movementTags = new ArrayList<>();
    private boolean carryResolved;

    public UL2MovableStaticMeshActor(UArchive archive) {
        super(archive, false);
        physics = UMovableStaticMeshActor.PHYS_L2_MOVEMENT;
    }

    @Override
    public boolean setProperty(UProperty property) {
        if (property.getName().equals("RotationRate")) {
            rate[1] = rawRotationVector(property.getRotatorValue());
            return true;
        }
        if (super.setProperty(property)) {
            return true;
        }
        return switch (property.getName()) {
            case "L2MovementTag" -> {
                UMovableStaticMeshActor.readMovementTag(movementTags, archive, property);
                yield true;
            }
            case "OrgLocation" -> {
                originLocation = property.getVectorValue();
                yield true;
            }
            case "OrgRotation" -> {
                originRotation = property.getRotatorValue();
                yield true;
            }
            case "OrgScale" -> {
                originScale = property.getVectorValue();
                yield true;
            }
            case "DeltaLocation" -> {
                deltaLocation = property.getVectorValue();
                yield true;
            }
            case "DeltaRotation" -> {
                deltaRotation = property.getRotatorValue();
                yield true;
            }
            case "DeltaScale" -> {
                deltaScale = property.getVectorValue();
                yield true;
            }
            case "TranslationMax" -> {
                max[0] = property.getVectorValue();
                yield true;
            }
            case "TranslationRate" -> {
                rate[0] = property.getVectorValue();
                yield true;
            }
            case "TranslationCurrent" -> {
                phase[0] = property.getVectorValue();
                yield true;
            }
            case "TranslationMaxRatio" -> {
                ratio[0] = property.getVectorValue();
                yield true;
            }
            case "RotationMax" -> {
                max[1] = rawRotationVector(property.getRotatorValue());
                yield true;
            }
            case "RotationCurrent" -> {
                phase[1] = property.getVectorValue();
                yield true;
            }
            case "RotationMaxRatio" -> {
                ratio[1] = property.getVectorValue();
                yield true;
            }
            case "ScaleMax" -> {
                max[2] = property.getVectorValue();
                yield true;
            }
            case "ScaleRate" -> {
                rate[2] = property.getVectorValue();
                yield true;
            }
            case "ScaleCurrent" -> {
                phase[2] = property.getVectorValue();
                yield true;
            }
            case "ScaleMaxRatio" -> {
                ratio[2] = property.getVectorValue();
                yield true;
            }
            case "bInitialized" -> {
                initialized = property.isTrue();
                yield true;
            }
            case "bRandomStart" -> {
                randomStart = property.isTrue();
                yield true;
            }
            case "bRandomMax" -> {
                randomMax = property.isTrue();
                yield true;
            }
            case "bUseRotatedTranslation" -> {
                rotatedTranslation = property.isTrue();
                yield true;
            }
            default -> false;
        };
    }

    public @Nullable L2Movement getMovement() {
        if (isPhysicsSaved()) {
            return null;
        }
        var saved = new L2Movement(
                getLocation(),
                getRotation(),
                getDrawScale3d(),
                getPrePivot(),
                getDrawScale(),
                originLocation,
                originRotation,
                originScale,
                new Channel(max[0], rate[0], phase[0], ratio[0]),
                new Channel(max[1], rate[1], phase[1], ratio[1]),
                new Channel(max[2], rate[2], phase[2], ratio[2]),
                initialized,
                randomStart,
                randomMax,
                rotatedTranslation,
                getFullName().hashCode(),
                new L2Movement.Deltas(deltaLocation, deltaRotation, deltaScale, originLocation, phase[0]));
        return initialized
                ? saved
                : saved.withPlacement(getLocation(), getRotation(), getDrawScale3d(), getPrePivot(), getDrawScale());
    }

    public List<String> getMovementTags() {
        return List.copyOf(movementTags);
    }

    public boolean isCarryResolved() {
        return carryResolved;
    }

    public void markCarryResolved() {
        carryResolved = true;
    }

    private static Vec3 rawRotationVector(URotator rotation) {
        return new Vec3(rotation.pitch(), rotation.yaw(), rotation.roll());
    }
}

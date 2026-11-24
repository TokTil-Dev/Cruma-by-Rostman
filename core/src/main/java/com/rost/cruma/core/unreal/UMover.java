package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import glm_.vec3.Vec3;

public final class UMover extends StaticMeshActor {
    private static final int OPEN_KEY = 1;

    private Vec3 opensBy = new Vec3();
    private float moveTime = 1f;
    private int serverId;

    public UMover(UArchive archive) {
        super(archive, false);
        physics = 8;
    }

    @Override
    public boolean setProperty(UProperty property) {
        if (super.setProperty(property)) {
            return true;
        }
        return switch (property.getName()) {
            case "KeyPos" -> {
                if (property.getArrayIndex() == OPEN_KEY) {
                    opensBy = property.getVectorValue();
                }
                yield true;
            }
            case "MoveTime" -> {
                moveTime = property.getFloatValue();
                yield true;
            }
            case "L2ServerObjectRealID" -> {
                serverId = property.getInt32Value();
                yield true;
            }
            default -> false;
        };
    }

    public Vec3 getOpensBy() {
        return opensBy;
    }

    public float getMoveTime() {
        return moveTime;
    }

    public int getServerId() {
        return serverId;
    }
}

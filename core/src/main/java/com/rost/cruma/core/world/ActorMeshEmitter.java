package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UEmitter.UMeshEmitter;

final class ActorMeshEmitter extends UMeshEmitter {
    private static final int RELATIVE = 1;

    ActorMeshEmitter() {
        super(null);
    }

    @Override
    public int getMaxParticles() {
        return 1;
    }

    @Override
    public boolean isRespawnDeadParticles() {
        return false;
    }

    @Override
    public float getLifetimeMin() {
        return 0f;
    }

    @Override
    public float getLifetimeMax() {
        return 0f;
    }

    @Override
    public int getCoordinateSystem() {
        return RELATIVE;
    }

    @Override
    public boolean isCoordinateSystemAuthored() {
        return true;
    }

    @Override
    public boolean isZWrite() {
        return true;
    }
}

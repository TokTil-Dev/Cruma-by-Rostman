package com.rost.cruma.core.npc.script;

import com.rost.cruma.core.npc.script.ScriptValues.Rotator;
import com.rost.cruma.core.npc.script.ScriptValues.Vector;
import org.jetbrains.annotations.Nullable;

public final class ScriptEffect {
    private final String effectClass;
    private final Vector spawnLocation;
    private final Rotator spawnRotation;
    private final boolean owned;
    private boolean trailer;
    private boolean relativeTrail;
    private boolean trailerSameRotation;
    private boolean trailerPrePivot;
    private boolean trailerNoOwnerDestroy;
    private Vector relativeTrailOffset = Vector.ZERO;
    private Vector trailerPrePivotOffset = Vector.ZERO;
    private @Nullable String bone;
    private boolean absoluteAttach;
    private boolean based;
    private Vector relativeLocation = Vector.ZERO;
    private Rotator relativeRotation = Rotator.ZERO;
    private float sizeScale = 1;
    private boolean destroyed;
    private boolean killed;
    private boolean started;

    ScriptEffect(String effectClass, Vector spawnLocation, Rotator spawnRotation, boolean owned) {
        this.effectClass = effectClass;
        this.spawnLocation = spawnLocation;
        this.spawnRotation = spawnRotation;
        this.owned = owned;
    }

    public String getEffectClass() {
        return effectClass;
    }

    public Vector getSpawnLocation() {
        return spawnLocation;
    }

    public Rotator getSpawnRotation() {
        return spawnRotation;
    }

    public boolean isOwned() {
        return owned;
    }

    public boolean isTrailer() {
        return trailer;
    }

    public boolean isRelativeTrail() {
        return relativeTrail;
    }

    public boolean isTrailerSameRotation() {
        return trailerSameRotation;
    }

    public boolean isTrailerPrePivot() {
        return trailerPrePivot;
    }

    public boolean isTrailerNoOwnerDestroy() {
        return trailerNoOwnerDestroy;
    }

    public Vector getRelativeTrailOffset() {
        return relativeTrailOffset;
    }

    public Vector getTrailerPrePivotOffset() {
        return trailerPrePivotOffset;
    }

    public @Nullable String getBone() {
        return bone;
    }

    public boolean isAbsoluteAttach() {
        return absoluteAttach;
    }

    public boolean isBased() {
        return based;
    }

    public Vector getRelativeLocation() {
        return relativeLocation;
    }

    public Rotator getRelativeRotation() {
        return relativeRotation;
    }

    public float getSizeScale() {
        return sizeScale;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public boolean isKilled() {
        return killed;
    }

    public boolean isStarted() {
        return started;
    }

    public void markStarted() {
        started = true;
    }

    void setTrailer(boolean trailer) {
        this.trailer = trailer;
    }

    void setRelativeTrail(boolean value) {
        relativeTrail = value;
    }

    void setTrailerSameRotation(boolean value) {
        trailerSameRotation = value;
    }

    void setTrailerPrePivot(boolean value) {
        trailerPrePivot = value;
    }

    void setTrailerNoOwnerDestroy(boolean value) {
        trailerNoOwnerDestroy = value;
    }

    void setRelativeTrailOffset(Vector value) {
        relativeTrailOffset = value;
    }

    void setTrailerPrePivotOffset(Vector value) {
        trailerPrePivotOffset = value;
    }

    void attach(@Nullable String bone, boolean absolute) {
        this.bone = bone;
        absoluteAttach = absolute;
        based = bone != null;
    }

    void base(boolean based, Vector offset) {
        this.based = based;
        bone = null;
        relativeLocation = offset;
    }

    void setRelativeLocation(Vector value) {
        relativeLocation = value;
    }

    void setRelativeRotation(Rotator value) {
        relativeRotation = value;
    }

    void scale(float factor) {
        sizeScale *= factor;
    }

    void destroy() {
        destroyed = true;
    }

    void kill() {
        killed = true;
    }
}

package com.rost.cruma.viewer.render;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.geometry.Frustum;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.EmitterResetPolicy;
import com.rost.cruma.core.world.EmitterVisibility;
import glm_.quat.Quat;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class ParticleActorCullState {
    static final int REFRESH_FRAME_MASK = 7;
    private static final float FORCED_UPDATE_SECONDS = 5f;
    private final Box bounds = new Box();
    private final List<ParticleEmitter> children = new ArrayList<>();
    ParticleEmitter[] selectedChildren = new ParticleEmitter[4];
    int selectedChildCount;
    boolean spawnedThisFrame;
    int publicationOrder;
    private final Box nativeSortBounds = new Box();
    private int nativeSortFrame = Integer.MIN_VALUE;
    private float timeTillReset = Float.NaN;
    private final int initialResetRandomState;
    private int resetRandomState;
    private URotator rotation;
    private int ownerTurnsSeen;
    private URotator flight;
    private final int refreshPhase;
    private int boundsFrame;
    private int simulationDecisionFrame;
    private float deferredSimulationSeconds;
    private float simulationDelta;
    private Frustum cachedFrustum;
    private boolean cachedIntersection;
    private int projectedWidth;
    private int projectedHeight;
    private int projectedThresholdBits;
    private boolean projectedResultValid;
    private boolean cachedProjectedInvisible;
    private boolean firstSpawnArmed = true;
    private boolean synchronousChildren;
    private EmitterVisibility settings = EmitterVisibility.UNLIMITED;
    private boolean canAutoReset;
    private boolean drawnMark = true;
    private float gatheredSeconds;
    private float tickSeconds;
    private int frozenFrame = Integer.MIN_VALUE;
    private boolean skipping;
    private boolean outsideVisibleLimit;
    private final @Nullable Object key;
    private final Box pointBox = new Box();
    private @Nullable Vec3 boundsPlace;
    private boolean canAutoDestroy;
    private boolean destroyed;
    private boolean rotates;

    ParticleActorCullState(int refreshPhase) {
        this(refreshPhase, null);
    }

    ParticleActorCullState(int refreshPhase, @Nullable Object key) {
        this.key = key;
        this.refreshPhase = refreshPhase & REFRESH_FRAME_MASK;
        this.initialResetRandomState = refreshPhase * 0x9E3779B1 + 0x85EBCA6B;
        this.resetRandomState = initialResetRandomState;
    }

    static int rotationStep(int unitsPerSecond, float seconds) {
        var value = unitsPerSecond * seconds;
        return !Float.isFinite(value) || value >= 0x1p31f || value < -0x1p31f ? Integer.MIN_VALUE : (int) value;
    }

    Box getBounds() {
        return bounds;
    }

    void clearSelectedChildren() {
        selectedChildCount = 0;
    }

    void selectChild(ParticleEmitter emitter) {
        if (selectedChildCount == selectedChildren.length) {
            selectedChildren = Arrays.copyOf(selectedChildren, selectedChildren.length << 1);
        }
        if (selectedChildren[selectedChildCount] != emitter) {
            selectedChildren[selectedChildCount] = emitter;
        }
        selectedChildCount++;
    }

    void addChild(ParticleEmitter emitter) {
        if (children.isEmpty()) {
            firstSpawnArmed = true;
            destroyed = false;
            drawnMark = true;
            gatheredSeconds = 0f;
            timeTillReset = Float.NaN;
            boundsPlace = null;
        }
        children.add(emitter);
        refreshSettings();
        synchronousChildren |= emitter.hasSiblingTiming();
        nativeSortBounds.clear();
        nativeSortFrame = Integer.MIN_VALUE;
    }

    @Nullable
    Object getKey() {
        return key;
    }

    ParticleEmitter findChildByOrder(int order) {
        for (var child : children) {
            if (child.getRenderChildOrder() == order) {
                return child;
            }
        }
        return null;
    }

    void removeChild(ParticleEmitter emitter) {
        children.remove(emitter);
        Arrays.fill(selectedChildren, null);
        selectedChildCount = 0;
        refreshSettings();
        synchronousChildren = false;
        for (var child : children) {
            synchronousChildren |= child.hasSiblingTiming();
        }
        nativeSortBounds.clear();
        nativeSortFrame = Integer.MIN_VALUE;
    }

    int getChildCount() {
        return children.size();
    }

    boolean hasSynchronousChildren() {
        return synchronousChildren;
    }

    ParticleEmitter getLeadChild() {
        return children.get(0);
    }

    void visibilityChanged(ParticleEmitter child) {
        if (!children.isEmpty() && children.getFirst() == child) {
            refreshSettings();
        }
    }

    boolean pollFirstSpawn() {
        if (!firstSpawnArmed) {
            return false;
        }
        for (var child : children) {
            if (child.hasLiveParticles()) {
                firstSpawnArmed = false;
                return true;
            }
        }
        return false;
    }

    boolean isSimulatedIn(int frame) {
        return simulationDecisionFrame == frame && simulationDelta > 0f;
    }

    void finishSimulation(int frame) {
        spawnedThisFrame = false;
        if (isSimulatedIn(frame)) {
            tickAutoReset();
            tickRotation(skipping ? tickSeconds : simulationDelta);
            spawnedThisFrame = pollFirstSpawn();
        } else if (skipping && frozenFrame == frame) {
            tickRotation(tickSeconds);
        }
    }

    void markDrawn() {
        drawnMark = true;
    }

    void setOutsideVisibleLimit(boolean outside) {
        outsideVisibleLimit = outside;
    }

    void tickAutoReset() {
        if (!canAutoReset && !canAutoDestroy) {
            return;
        }
        var policy = settings.resetPolicy();
        var delayed = false;
        for (var child : children) {
            if (child.keepsActorAlive()) {
                return;
            }
            delayed |= child.isDelayed();
        }
        if (canAutoDestroy && !delayed) {
            destroyed = true;
            return;
        }
        if (!canAutoReset) {
            return;
        }
        if (Float.isNaN(timeTillReset)) {
            timeTillReset = nextTimeTillReset(policy);
        }
        timeTillReset -= skipping ? tickSeconds : simulationDelta;
        if (timeTillReset > 0f) {
            return;
        }
        for (var child : children) {
            child.restart();
        }
        firstSpawnArmed = true;
        timeTillReset = nextTimeTillReset(policy);
    }

    void resetForSnapshot() {
        resetFrame();
        bounds.clear();
        nativeSortBounds.clear();
        nativeSortFrame = Integer.MIN_VALUE;
        timeTillReset = Float.NaN;
        resetRandomState = initialResetRandomState;
        destroyed = false;
        drawnMark = true;
        gatheredSeconds = 0f;
        frozenFrame = Integer.MIN_VALUE;
        firstSpawnArmed = true;
        spawnedThisFrame = false;
        selectedChildCount = 0;
        if (rotation != null || settings.rotating()) {
            rotation = startRotation();
            ownerTurnsSeen = children.isEmpty() ? 0 : children.getFirst().getOwnerTurns();
            applyRotation(rotation);
        }
    }

    boolean isRotating() {
        return rotates;
    }

    void fly(URotator direction) {
        takeOwnerTurn();
        var current = rotation == null ? startRotation() : rotation;
        var last = flight == null ? startRotation() : flight;
        rotation = new URotator(
                current.pitch() + direction.pitch() - last.pitch(),
                current.yaw() + direction.yaw() - last.yaw(),
                current.roll() + direction.roll() - last.roll());
        flight = direction;
        applyRotation(rotation);
    }

    Box getNativeSortBounds(int frame) {
        if (nativeSortFrame != frame) {
            nativeSortFrame = frame;
            nativeSortBounds.clear();
            for (var child : children) {
                child.addNativeSortBounds(nativeSortBounds);
            }
        }
        return nativeSortBounds;
    }

    float getSimulationDelta(
            int frame,
            float frameDelta,
            Frustum frustum,
            float[] viewProjection,
            int renderWidth,
            int renderHeight,
            float minimumProjectedPixels,
            boolean clientUpdateSkip) {
        if (simulationDecisionFrame == frame) {
            return simulationDelta;
        }
        simulationDecisionFrame = frame;
        if (destroyed && !isStartedAgain()) {
            simulationDelta = -1f;
            return simulationDelta;
        }
        destroyed = false;
        skipping = clientUpdateSkip && settings.resetPolicy().skipsWhenNotDrawn();
        if (skipping) {
            return decideByClientSkip(frame, frameDelta, frustum);
        }
        var canCull = bounds.isValid()
                && frustum != null
                && viewProjection != null
                && viewProjection.length >= 16
                && renderWidth > 0
                && renderHeight > 0;
        var inFrustum = !canCull || frustum.intersects(bounds);
        var subPixel = canCull
                && inFrustum
                && minimumProjectedPixels > 0f
                && ParticleSystem.isProjectedSizeInvisible(
                        bounds, viewProjection, renderWidth, renderHeight, minimumProjectedPixels);
        var simulate = inFrustum && !subPixel || (frame & REFRESH_FRAME_MASK) == refreshPhase;
        if (!simulate) {
            if (frameDelta > 0f && Float.isFinite(frameDelta)) {
                deferredSimulationSeconds += frameDelta;
            }
            simulationDelta = -1f;
            cachedFrustum = frustum;
            cachedIntersection = inFrustum;
            projectedWidth = renderWidth;
            projectedHeight = renderHeight;
            projectedThresholdBits = Float.floatToIntBits(minimumProjectedPixels);
            projectedResultValid = canCull && inFrustum;
            cachedProjectedInvisible = subPixel;
            return simulationDelta;
        }
        simulationDelta = frameDelta > 0f && Float.isFinite(frameDelta) ? frameDelta + deferredSimulationSeconds : 0f;
        if (simulationDelta > 0f) {
            deferredSimulationSeconds = 0f;
        }
        return simulationDelta;
    }

    boolean isDestroyed() {
        return destroyed;
    }

    void beginFrame(int frame) {
        if (boundsFrame == frame) {
            return;
        }
        boundsFrame = frame;
        bounds.clear();
        cachedFrustum = null;
        projectedResultValid = false;
    }

    boolean intersects(Frustum frustum) {
        if (cachedFrustum != frustum) {
            cachedFrustum = frustum;
            cachedIntersection = frustum.intersects(bounds);
        }
        return cachedIntersection;
    }

    boolean isProjectedSizeInvisible(
            float[] viewProjection, int renderWidth, int renderHeight, float minimumProjectedPixels) {
        var thresholdBits = Float.floatToIntBits(minimumProjectedPixels);
        if (!projectedResultValid
                || projectedWidth != renderWidth
                || projectedHeight != renderHeight
                || projectedThresholdBits != thresholdBits) {
            projectedWidth = renderWidth;
            projectedHeight = renderHeight;
            projectedThresholdBits = thresholdBits;
            cachedProjectedInvisible = ParticleSystem.isProjectedSizeInvisible(
                    bounds, viewProjection, renderWidth, renderHeight, minimumProjectedPixels);
            projectedResultValid = true;
        }
        return cachedProjectedInvisible;
    }

    void resetFrame() {
        boundsFrame = 0;
        simulationDecisionFrame = 0;
        deferredSimulationSeconds = 0f;
        simulationDelta = 0f;
        cachedFrustum = null;
        projectedResultValid = false;
    }

    private void tickRotation(float seconds) {
        if (!rotates) {
            return;
        }
        if (takeOwnerTurn()) {
            return;
        }
        var perSecond = settings.rotationPerSecond();
        var current = rotation == null ? startRotation() : rotation;
        var next = new URotator(
                current.pitch() + rotationStep(perSecond.pitch(), seconds),
                current.yaw() + rotationStep(perSecond.yaw(), seconds),
                current.roll() + rotationStep(perSecond.roll(), seconds));
        rotation = next;
        if (!next.equals(current)) {
            applyRotation(next);
        }
    }

    private float decideByClientSkip(int frame, float frameDelta, Frustum frustum) {
        var policy = settings.resetPolicy();
        var seconds = frameDelta > 0f && Float.isFinite(frameDelta) ? frameDelta : 0f;
        tickSeconds = seconds;
        if (seconds <= 0f) {
            simulationDelta = 0f;
            return simulationDelta;
        }
        gatheredSeconds = policy.gathersSkippedTime() ? gatheredSeconds + seconds : seconds;
        var drawn = drawnMark || !outsideVisibleLimit && isInFrustum(frustum);
        drawnMark = false;
        if (drawn || gatheredSeconds >= FORCED_UPDATE_SECONDS) {
            simulationDelta = gatheredSeconds;
            gatheredSeconds = 0f;
            deferredSimulationSeconds = 0f;
            return simulationDelta;
        }
        frozenFrame = frame;
        simulationDelta = -1f;
        return simulationDelta;
    }

    private boolean isInFrustum(Frustum frustum) {
        if (frustum == null) {
            return true;
        }
        if (children.isEmpty()) {
            return false;
        }
        var at = children.getFirst().getActorPos();
        if (!at.equals(boundsPlace)) {
            boundsPlace = new Vec3(at);
            bounds.clear();
            for (var child : children) {
                child.computeWorldBounds(bounds);
            }
        }
        if (bounds.isValid()) {
            return frustum.intersects(bounds);
        }
        pointBox.clear();
        pointBox.add(at.getX() - 1f, at.getY() - 1f, at.getZ() - 1f);
        pointBox.add(at.getX() + 1f, at.getY() + 1f, at.getZ() + 1f);
        return frustum.intersects(pointBox);
    }

    private void refreshSettings() {
        settings = children.isEmpty()
                ? EmitterVisibility.UNLIMITED
                : children.getFirst().getVisibility();
        var policy = settings.resetPolicy();
        canAutoReset = !children.isEmpty() && policy.canReset();
        canAutoDestroy = !children.isEmpty() && policy.isDestroyedWhenDead();
        if (!canAutoDestroy) {
            destroyed = false;
        }
        var rate = settings.rotationPerSecond();
        rotates = !children.isEmpty()
                && settings.rotating()
                && (rate.pitch() != 0 || rate.yaw() != 0 || rate.roll() != 0);
    }

    private boolean isStartedAgain() {
        for (var child : children) {
            if (child.keepsActorAlive()) {
                return true;
            }
        }
        return false;
    }

    private URotator startRotation() {
        if (flight != null) {
            return flight;
        }
        var owned = children.isEmpty() ? null : children.getFirst().getOwnerRotation();
        return owned == null ? settings.baseRotation() : owned;
    }

    private boolean takeOwnerTurn() {
        var lead = children.getFirst();
        if (lead.getOwnerTurns() == ownerTurnsSeen) {
            return false;
        }
        ownerTurnsSeen = lead.getOwnerTurns();
        flight = null;
        rotation = lead.getOwnerRotation();
        return true;
    }

    private void applyRotation(URotator rotation) {
        var quaternion = new Quat(rotation.vector());
        for (var child : children) {
            child.setActorRotation(quaternion);
        }
    }

    private float nextTimeTillReset(EmitterResetPolicy policy) {
        resetRandomState = resetRandomState * 1103515245 + 12345;
        var fraction = (resetRandomState >>> 8 & 0xffffff) / 16777216f;
        return policy.timeTillResetMin() + (policy.timeTillResetMax() - policy.timeTillResetMin()) * fraction;
    }
}

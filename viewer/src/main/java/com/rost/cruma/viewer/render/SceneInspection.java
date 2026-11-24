package com.rost.cruma.viewer.render;

import com.rost.cruma.viewer.render.api.DebugSelection;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class SceneInspection {
    public DebugSelection selected;
    public boolean showBounds;
    private final Set<String> hiddenEffects = new HashSet<>();
    private boolean isolated;
    private @Nullable Set<String> isolatedActors;
    private float othersOpacity;
    private long revision;

    public void select(DebugSelection selected) {
        this.selected = selected;
        revision++;
        if (selected == null) {
            isolated = false;
        }
    }

    public void setIsolated(boolean isolated) {
        this.isolated = isolated && selected != null;
        if (this.isolated && selected.emitter() != null) {
            hiddenEffects.remove(selected.emitter().getRenderActorName());
        }
        revision++;
    }

    public void setEffectHidden(ParticleEmitter emitter, boolean hidden) {
        var actor = emitter.getRenderActorName();
        var changed = hidden ? hiddenEffects.add(actor) : hiddenEffects.remove(actor);
        if (hidden
                && isIsolated()
                && selected.emitter() != null
                && selected.emitter().getRenderActorName().equals(actor)) {
            isolated = false;
            changed = true;
        }
        if (changed) {
            revision++;
        }
    }

    public boolean isEffectHidden(ParticleEmitter emitter) {
        return hiddenEffects.contains(emitter.getRenderActorName());
    }

    public boolean isIsolated() {
        return isolatedActors != null || isSelectionIsolated();
    }

    public void isolateActors(@Nullable Set<String> actors) {
        isolatedActors = actors;
        revision++;
    }

    public @Nullable Set<String> getIsolatedActors() {
        return isolatedActors;
    }

    public long getRevision() {
        return revision;
    }

    public float getOthersOpacity() {
        return othersOpacity;
    }

    public void setOthersOpacity(float opacity) {
        othersOpacity = Math.clamp(opacity, 0f, 1f);
    }

    public boolean areOthersShown() {
        return othersOpacity > 0f && isIsolated();
    }

    public boolean acceptsObject(long stableId) {
        return !isSelectionIsolated()
                || selected.sceneObject() != null && selected.sceneObject().renderObjectId() == stableId;
    }

    public boolean acceptsEmitter(ParticleEmitter emitter) {
        return isolatedActors == null
                && !isEffectHidden(emitter)
                && (!isSelectionIsolated() || selected.emitter() == emitter);
    }

    public int getIsolatedInstance() {
        return isSelectionIsolated() && selected.sceneObject() != null ? selected.instanceOrdinal() : -1;
    }

    public String getIsolatedSourceActor() {
        return isSelectionIsolated()
                        && selected.sceneObject() != null
                        && selected.sceneObject().sourcePartOrdinal() >= 0
                ? selected.actor()
                : null;
    }

    private boolean isSelectionIsolated() {
        return isolated && selected != null;
    }
}

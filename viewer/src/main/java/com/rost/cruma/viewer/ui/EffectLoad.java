package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.world.EffectParts;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

final class EffectLoad {
    private @Nullable CompletableFuture<@Nullable EffectParts> running;
    private @Nullable Effect effect;

    void start(Effect chosen, CompletableFuture<@Nullable EffectParts> read) {
        effect = chosen;
        running = read;
    }

    boolean isRunning() {
        return running != null;
    }

    boolean isRunning(Effect wanted) {
        return running != null && wanted.equals(effect);
    }

    boolean isDone() {
        return running != null && running.isDone();
    }

    @Nullable
    Effect getEffect() {
        return effect;
    }

    boolean overtake(Effect built, @Nullable EffectParts parts) {
        if (!isRunning(built)) {
            return false;
        }
        running = CompletableFuture.completedFuture(parts);
        return true;
    }

    @Nullable
    EffectParts take() {
        var read = running;
        running = null;
        return read == null ? null : read.join();
    }
}

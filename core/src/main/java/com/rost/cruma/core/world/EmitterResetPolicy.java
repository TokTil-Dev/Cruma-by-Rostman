package com.rost.cruma.core.world;

public record EmitterResetPolicy(
        boolean autoReset,
        boolean autoDestroy,
        boolean noDelete,
        boolean placed,
        float timeTillResetMin,
        float timeTillResetMax,
        ClientRule rule,
        boolean alwaysUpdated) {
    public static final EmitterResetPolicy NONE = new EmitterResetPolicy(false, true, true, false, 0f, 0f);

    public enum ClientRule {
        TICK,
        POST_UPDATE,
        HIGH_FIVE,
        P152
    }

    public EmitterResetPolicy(
            boolean autoReset,
            boolean autoDestroy,
            boolean noDelete,
            boolean placed,
            float timeTillResetMin,
            float timeTillResetMax) {
        this(
                autoReset,
                autoDestroy,
                noDelete,
                placed,
                timeTillResetMin,
                timeTillResetMax,
                ClientRule.POST_UPDATE,
                false);
    }

    public EmitterResetPolicy(boolean autoReset, boolean autoDestroy, float timeTillResetMin, float timeTillResetMax) {
        this(autoReset, autoDestroy, false, false, timeTillResetMin, timeTillResetMax);
    }

    public boolean isDestroyedWhenDead() {
        return placed && autoDestroy && !noDelete;
    }

    public boolean canReset() {
        return placed && rule != ClientRule.TICK ? autoReset : autoReset && !autoDestroy;
    }

    public boolean skipsWhenNotDrawn() {
        return placed && (rule == ClientRule.HIGH_FIVE || rule == ClientRule.P152 && !alwaysUpdated);
    }

    public boolean gathersSkippedTime() {
        return rule == ClientRule.P152 && !noDelete;
    }
}

package com.rost.cruma.core.world;

import java.util.Arrays;

public final class ZoneFade {
    public static final int IDLE = 0;

    public static final int STARTED = 1;

    public static final int FADING_IN = 2;

    public static final int FADING_OUT = 3;

    public static final int ENDED = 4;

    public static final int INACTIVE = 0;

    public static final int CURRENT = 1;

    public static final int PREVIOUS = 2;

    public static final int BOTH = 3;

    private static final float DEFAULT_BLEND_TIME = 2.0f;
    private static final float SETTLE_STEP = 1.0e6f;

    private final String zone;
    private final int[] states;
    private final float[] delayTimes;
    private final float[] blendTimes;
    private final float[] blendRatios;
    private final float[] times;
    private final boolean[] actives;
    private int curZoneState;
    private int previousZoneState;
    private int blendingState;
    private long revision;

    public ZoneFade(ZoneBlend blend, int curZoneState) {
        zone = blend.zone();
        var entries = blend.entries();
        states = new int[entries.size()];
        delayTimes = new float[entries.size()];
        blendTimes = new float[entries.size()];
        blendRatios = new float[entries.size()];
        times = new float[entries.size()];
        actives = new boolean[entries.size()];
        for (var index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            states[index] = entry.state();
            delayTimes[index] = entry.delayTime();
            blendTimes[index] = entry.blendTime();
            blendRatios[index] = entry.blendRatio();
            times[index] = entry.time();
            actives[index] = entry.active();
        }
        this.curZoneState = curZoneState;
        previousZoneState = blend.previousZoneState();
        blendingState = blend.blendingState();
    }

    public String getZone() {
        return zone;
    }

    public int getCurZoneState() {
        return curZoneState;
    }

    public int getPreviousZoneState() {
        return previousZoneState;
    }

    public int getBlendingState() {
        return blendingState;
    }

    public long getRevision() {
        return revision;
    }

    public boolean isRunning() {
        return blendingState == ENDED || hasActiveEntry();
    }

    public void changeState(int state) {
        var previous = curZoneState;
        curZoneState = state;
        if (previous != 0 && previous != curZoneState) {
            previousZoneState = previous;
        }
        activate(curZoneState, true);
        revision++;
    }

    public void activate(int state, boolean in) {
        var entry = findEntry(state);
        if (entry >= 0) {
            if (in && blendingState == IDLE) {
                if (blendTimes[entry] == 0f) {
                    blendTimes[entry] = DEFAULT_BLEND_TIME;
                }
                blendRatios[entry] = 0f;
                times[entry] = 0f;
                actives[entry] = true;
                blendingState = STARTED;
                revision++;
                return;
            }
            if (!in && (blendingState == STARTED || blendingState == FADING_IN)) {
                blendRatios[entry] = 1f;
                times[entry] = 0f;
                actives[entry] = true;
                blendingState = FADING_OUT;
                revision++;
                return;
            }
        }
        if (in && previousZoneState > 0) {
            blendingState = FADING_IN;
            revision++;
            activate(previousZoneState, false);
        }
    }

    public void update(float deltaSeconds) {
        if (blendingState == ENDED) {
            blendingState = IDLE;
            revision++;
            return;
        }
        for (var entry = 0; entry < states.length; entry++) {
            if (!actives[entry]) {
                continue;
            }
            times[entry] += deltaSeconds;
            var time = times[entry];
            revision++;
            if (blendingState == STARTED || blendingState == FADING_IN) {
                var delay = delayTimes[entry];
                var blend = blendTimes[entry];
                if (time >= delay + blend) {
                    if (previousZoneState > 0) {
                        if (findEntry(previousZoneState) >= 0) {
                            activate(previousZoneState, false);
                        }
                        if (blendingState != FADING_OUT) {
                            previousZoneState = 0;
                            blendingState = ENDED;
                        }
                    } else {
                        blendingState = ENDED;
                    }
                    blendRatios[entry] = 1f;
                    actives[entry] = false;
                } else if (time >= delay) {
                    blendRatios[entry] = (time - delay) / blend;
                    blendingState = FADING_IN;
                }
            } else if (blendingState == FADING_OUT) {
                var blend = blendTimes[entry];
                if (time >= blend) {
                    actives[entry] = false;
                    blendRatios[entry] = 0f;
                    previousZoneState = 0;
                    blendingState = ENDED;
                } else {
                    blendRatios[entry] = 1f - time / blend;
                }
            }
        }
    }

    public void settle() {
        for (var step = 0; step < 8 && blendingState != IDLE; step++) {
            var before = revision;
            update(SETTLE_STEP);
            if (revision == before) {
                return;
            }
        }
    }

    public boolean endStuckFade() {
        var active = hasActiveEntry();
        if (blendingState == IDLE && active) {
            Arrays.fill(actives, false);
            revision++;
            return true;
        }
        if (blendingState != IDLE && blendingState != ENDED && !active) {
            previousZoneState = 0;
            blendingState = IDLE;
            revision++;
            return true;
        }
        return false;
    }

    public int checkActive(int mask) {
        var result = (mask & bitOf(curZoneState)) != 0 ? CURRENT : INACTIVE;
        if ((mask & bitOf(previousZoneState)) != 0) {
            result = result == CURRENT ? BOTH : PREVIOUS;
        }
        return result;
    }

    public float getBlendingRatio(int kind) {
        var state = switch (kind) {
            case CURRENT -> curZoneState;
            case PREVIOUS -> previousZoneState;
            default -> Integer.MIN_VALUE;
        };
        var entry = state == Integer.MIN_VALUE ? -1 : findEntry(state);
        return entry < 0 ? 0f : blendRatios[entry];
    }

    public boolean isDrawn(int mask) {
        return mask == 0 || checkActive(mask) != INACTIVE;
    }

    public float getMeshOpacity(int mask) {
        var active = checkActive(mask);
        if (mask == 0 || active == INACTIVE || blendingState == IDLE || blendingState == ENDED) {
            return 1f;
        }
        var ratio = active == BOTH ? 1f : getBlendingRatio(active);
        return ((int) (ratio * 255f) & 0xFF) / 255f;
    }

    public float getEffectRatio(int mask) {
        var active = mask == 0 ? INACTIVE : checkActive(mask);
        if (active == INACTIVE) {
            return Float.NaN;
        }
        return active == BOTH ? 1f : getBlendingRatio(active);
    }

    public float getFogFactor(int mask) {
        var active = checkActive(mask);
        return blendingState == IDLE || active == INACTIVE ? 1f : getBlendingRatio(active);
    }

    public int getSkinState(int looks) {
        if ((looks & bitOf(curZoneState)) != 0) {
            return curZoneState;
        }
        if (blendingState != IDLE && (looks & bitOf(previousZoneState)) != 0) {
            return previousZoneState;
        }
        return 0;
    }

    @Override
    public String toString() {
        return "ZoneFade[" + zone + ", cur " + curZoneState + ", previous " + previousZoneState + ", step "
                + blendingState + ", ratios " + Arrays.toString(blendRatios) + "]";
    }

    private boolean hasActiveEntry() {
        for (var active : actives) {
            if (active) {
                return true;
            }
        }
        return false;
    }

    private int findEntry(int state) {
        for (var entry = 0; entry < states.length; entry++) {
            if (states[entry] == state) {
                return entry;
            }
        }
        return -1;
    }

    private static int bitOf(int state) {
        return state >= 1 && state <= 32 ? 1 << (state - 1) : 0;
    }
}

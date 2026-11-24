package com.rost.cruma.viewer.render.bgfx;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class BgfxSubmissionBudget {
    static final long SHADOW_LIMIT_BYTES = 1L * 1_024L * 1_024L;
    static final int SHADOW_LIMIT_DRAWS = 8_192;

    static final int ENTITY_SCENE_DRAW_BYTES = 736;
    static final int ENTITY_GPU_SCENE_DRAW_BYTES = 416;
    static final int TERRAIN_SCENE_DRAW_BYTES = 512;
    static final int TERRAIN_OVERLAY_DRAW_BYTES = 912;
    static final int ENTITY_SHADOW_DRAW_BYTES = 96;
    static final int MASKED_SHADOW_DRAW_BYTES = 160;
    static final int TERRAIN_SHADOW_DRAW_BYTES = 160;

    private static final Logger log = LogManager.getLogger(BgfxSubmissionBudget.class);

    private final int shadowDrawLimit;
    private final int drawLimit;
    private long usedBytes;
    private int draws;
    private int skippedDraws;
    private boolean drawLimitReported;
    private long shadowBytes;
    private int shadowDraws;
    private int deferredShadowDraws;
    private long lastShadowDebugFrame = Long.MIN_VALUE;

    BgfxSubmissionBudget(int nativeRenderItemLimit) {
        if (nativeRenderItemLimit < 4) {
            throw new IllegalArgumentException("native render-item limit is too small");
        }
        shadowDrawLimit = Math.min(SHADOW_LIMIT_DRAWS, nativeRenderItemLimit / 4);
        drawLimit = nativeRenderItemLimit - nativeRenderItemLimit / 8;
    }

    void beginFrame() {
        usedBytes = 0L;
        draws = 0;
        skippedDraws = 0;
        shadowBytes = 0L;
        shadowDraws = 0;
        deferredShadowDraws = 0;
    }

    boolean reserve(Domain domain, long bytes, int draws) {
        if (bytes < 0L || draws < 0 || domain == null) {
            throw new IllegalArgumentException("invalid submission reservation");
        }
        if (domain == Domain.SHADOW
                && (shadowBytes > SHADOW_LIMIT_BYTES - bytes || shadowDraws > shadowDrawLimit - draws)) {
            deferredShadowDraws = Math.addExact(deferredShadowDraws, draws);
            return false;
        }
        if (draws > drawLimit - this.draws) {
            if (domain == Domain.SHADOW) {
                deferredShadowDraws = Math.addExact(deferredShadowDraws, draws);
            } else {
                skippedDraws = Math.addExact(skippedDraws, draws);
            }
            return false;
        }
        this.draws += draws;
        usedBytes = Math.addExact(usedBytes, bytes);
        if (domain == Domain.SHADOW) {
            shadowBytes += bytes;
            shadowDraws += draws;
        }
        return true;
    }

    void reportDrops(long frameNumber) {
        if (skippedDraws > 0 && !drawLimitReported) {
            drawLimitReported = true;
            log.warn(
                    "Frame needed more than {} world draws; skipped {} so the final image "
                            + "and interface still render (reported once)",
                    drawLimit,
                    skippedDraws);
        }
        if (deferredShadowDraws == 0
                || lastShadowDebugFrame != Long.MIN_VALUE
                        && Integer.toUnsignedLong((int) (frameNumber - lastShadowDebugFrame)) < 120L) {
            return;
        }
        lastShadowDebugFrame = frameNumber;
        log.debug(
                "Deferred {} shadow draw(s); estimated scene uniforms={} KiB", deferredShadowDraws, usedBytes / 1_024L);
    }

    long getUsedBytes() {
        return usedBytes;
    }

    int getDroppedDraws() {
        return deferredShadowDraws;
    }

    int getSkippedDraws() {
        return skippedDraws;
    }

    enum Domain {
        MAIN,
        REFLECTION,
        SHADOW,
        FX
    }
}

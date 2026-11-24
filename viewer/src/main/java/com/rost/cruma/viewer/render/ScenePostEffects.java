package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.PostEffects;
import com.rost.cruma.core.world.PostEffects.Effect;
import org.jetbrains.annotations.Nullable;

public final class ScenePostEffects {
    public static final int NO_BLUR = 255;

    private static final float GAME_FRAMES_PER_SECOND = 60f;
    private static final float SLOW_FRAME_SECONDS = .1f;
    private static final float SLOW_FRAME_SLOPE = 2.3f;
    private static final float MILLIS = 1000f;
    private static final int REMOVES = 2;

    private float blurFrom = NO_BLUR;
    private float blurTo = NO_BLUR;
    private float blur = NO_BLUR;
    private float blurMillis;
    private float blurSpent;
    private @Nullable Effect color;
    private float colorSpent;
    private float colorShare;

    public static float share(int alpha, float frameSeconds) {
        var strength = Math.clamp((1f - frameSeconds / SLOW_FRAME_SECONDS) * SLOW_FRAME_SLOPE, 0f, 1f);
        var blended = (alpha * strength + (1f - strength) * NO_BLUR) / NO_BLUR;
        return (float) (1 - Math.pow(1 - blended, frameSeconds * GAME_FRAMES_PER_SECOND));
    }

    public void play(Effect effect) {
        switch (effect.type()) {
            case PostEffects.MOTION_BLUR -> {
                blurFrom = blur;
                blurTo = effect.blurAlpha();
                blurMillis = effect.blurMillis();
                blurSpent = 0;
            }
            case PostEffects.YCBCR -> {
                color = effect;
                colorSpent = 0;
                colorShare = effect.seconds() == 0 ? 1 : 0;
            }
            default -> {
            }
        }
    }

    public void show(int alpha, @Nullable Effect effect, float share) {
        clear();
        blur = alpha;
        blurFrom = alpha;
        blurTo = alpha;
        color = effect;
        colorShare = share;
    }

    public void advance(double seconds) {
        advanceBlur((float) seconds);
        advanceColor((float) seconds);
    }

    public void clear() {
        blurFrom = NO_BLUR;
        blurTo = NO_BLUR;
        blur = NO_BLUR;
        blurMillis = 0;
        blurSpent = 0;
        color = null;
        colorSpent = 0;
        colorShare = 0;
    }

    public int getBlurAlpha() {
        return (int) blur;
    }

    public boolean blurs() {
        return getBlurAlpha() != NO_BLUR;
    }

    public @Nullable Effect getColor() {
        return color;
    }

    public float getColorShare() {
        return colorShare;
    }

    private void advanceBlur(float seconds) {
        if (blurMillis <= blurSpent) {
            blur = blurTo;
            blurMillis = 0;
            blurSpent = 0;
            return;
        }
        blurSpent += seconds * MILLIS;
        blur = Math.clamp(blurSpent / blurMillis * (blurTo - blurFrom) + blurFrom, 0f, NO_BLUR);
    }

    private void advanceColor(float seconds) {
        var effect = color;
        if (effect == null) {
            return;
        }
        if (colorShare < 1) {
            colorSpent = Math.min(colorSpent + seconds, effect.seconds());
            colorShare = colorSpent / effect.seconds();
        }
        if (colorShare >= 1 && effect.mode() == REMOVES) {
            color = null;
        }
    }
}

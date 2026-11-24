package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.UColorFade;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record AuthoredMaterialFactors(List<Step> steps) {
    public AuthoredMaterialFactors {
        steps = List.copyOf(steps);
    }

    public int at(float seconds) {
        var factor = 0;
        for (var step : steps) {
            if (step.replace() || factor == 0) {
                factor = step.animation() == null
                        ? step.color()
                        : step.animation().argb(seconds);
            }
        }
        return factor;
    }

    public record Step(int color, @Nullable UColorFade animation, boolean replace) {
        public Step {
            if (animation != null && !animation.isSupported()) {
                throw new IllegalArgumentException("unsupported factor read");
            }
        }
    }
}

package com.rost.cruma.viewer.render.api;

import java.util.List;

public interface ViewerLoadingScene {
    float HALF_WIDTH = 1.45f, TOP = 1.3f, BOTTOM = -.3f;

    record Placement(int figure, float x, float y, float z, float scale, float turned, boolean upsideDown) {}

    record Frame(
            int width,
            int height,
            float level,
            float edge,
            float pulse,
            float glow,
            float rockDrift,
            float seconds,
            float toiX,
            float toiZ,
            float toiHeight,
            List<Placement> placements) {}

    boolean prepare();

    long render(Frame frame);

    boolean isFlipped();
}

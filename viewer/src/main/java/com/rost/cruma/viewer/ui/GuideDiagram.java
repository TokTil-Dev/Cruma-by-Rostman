package com.rost.cruma.viewer.ui;

import imgui.ImDrawList;

public interface GuideDiagram {
    float getHeight(float width);

    void draw(ImDrawList draw, float x, float y, float width);
}

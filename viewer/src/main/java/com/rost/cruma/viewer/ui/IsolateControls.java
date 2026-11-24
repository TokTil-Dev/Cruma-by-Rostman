package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.render.SceneInspection;

public final class IsolateControls {
    private static final String TIP =
            "How much of the rest of the scene shows around what is isolated: 0 hides it. Water and glass stay hidden."
                    + " Clicks in the view go through it, so only what is isolated can be selected.";
    private static final int MOST_PERCENT = 90;
    private static final int[] PERCENT = new int[1];

    private IsolateControls() {}

    public static void draw(SceneInspection inspection, float width) {
        PERCENT[0] = Math.round(inspection.getOthersOpacity() * 100);
        if (HudWidgets.sliderInt("Others", PERCENT, 0, MOST_PERCENT, width)) {
            inspection.setOthersOpacity(PERCENT[0] / 100f);
        }
        GeodataWidgets.tip(TIP);
    }
}

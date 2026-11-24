package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.export.ExportScale;
import com.rost.cruma.viewer.config.ExportSettings;
import imgui.type.ImFloat;

public final class ExportScaleChoice {
    private static final ExportScale[] NAMED = {ExportScale.REAL_SIZE, ExportScale.UNREAL_UNITS, ExportScale.METERS};

    private static final String[] LABELS = {"Real size", "Unreal", "1 m", "Custom"};

    private static final String[] NAMES = {"Real size", "Unreal units", ""};

    private static final int CUSTOM = NAMED.length;

    private static final String TIP =
            "How long one client unit is in the files. Real size is 4 cm, so a person is 1.84 m tall. Unreal reads the"
                + " client's numbers as centimetres and 1 m as metres. Locations and NPCs exported with the same scale"
                + " fit together.";

    private final ExportSettings settings;
    private final ImFloat custom = new ImFloat(ExportScale.REAL_SIZE.centimetersPerUnit());
    private int mode;

    public ExportScaleChoice(ExportSettings settings) {
        this.settings = settings;
        load();
    }

    public void load() {
        var kept = settings.getScale();
        mode = CUSTOM;
        for (var index = 0; index < NAMED.length; index++) {
            if (NAMED[index].equals(kept)) {
                mode = index;
            }
        }
        custom.set(kept.centimetersPerUnit());
    }

    public ExportScale getScale() {
        return mode == CUSTOM ? ExportScale.clamped(custom.get()) : NAMED[mode];
    }

    public void commit() {
        settings.setScale(getScale());
    }

    public String getUnitText() {
        return getScale().unitText();
    }

    public void draw(String id) {
        var picked = GeodataWidgets.segmented(id, LABELS, mode);
        GeodataWidgets.tip(TIP);
        if (picked >= 0 && picked != mode) {
            var from = getScale().centimetersPerUnit();
            mode = picked;
            if (mode == CUSTOM) {
                custom.set(from);
            }
            commit();
        }
        if (mode == CUSTOM) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.decimal("Length of a unit", custom, " cm")) {
                custom.set(getScale().centimetersPerUnit());
                commit();
            }
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(getSummary());
    }

    String getSummary() {
        var scale = getScale();
        var name = mode == CUSTOM ? "" : NAMES[mode];
        return (name.isEmpty() ? "" : name + " · ")
                + scale.unitText()
                + " · a person is "
                + scale.humanText()
                + " tall";
    }
}

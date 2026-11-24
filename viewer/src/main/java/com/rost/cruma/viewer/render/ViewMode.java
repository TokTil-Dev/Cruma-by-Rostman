package com.rost.cruma.viewer.render;

public enum ViewMode {
    LIT("Lit"),
    UNLIT("Unlit"),
    WIREFRAME("Wire");

    private final String label;

    ViewMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isLit() {
        return this == LIT;
    }

    public boolean isFilled() {
        return this != WIREFRAME;
    }

    public boolean picksObjects() {
        return this != WIREFRAME;
    }
}

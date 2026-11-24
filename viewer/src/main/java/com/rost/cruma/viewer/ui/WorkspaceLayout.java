package com.rost.cruma.viewer.ui;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

public final class WorkspaceLayout {
    public Mode mode = Mode.EXPLORE;
    public Tab tab = Tab.MATERIAL;
    public float inspectorWidth = 304f;
    public float editingWidth = 340f;
    public float treeWidth = 224f;
    public boolean treeFolded;
    public boolean viewControlsOpen;
    public boolean statusDocked;
    public float analysisHeight = 92f;
    public float directorHeight = 108f;
    public boolean materialDetails;

    public enum Mode {
        EXPLORE,
        INSPECT,
        ANALYZE,
        EDITING
    }

    public enum Tab {
        MATERIAL,
        ANIMATION,
        SOURCE
    }

    public static Mode initialMode(boolean editingPrepared, Mode resumed, String configured) {
        if (editingPrepared) {
            return Mode.EDITING;
        }
        if (resumed != null) {
            return resumed;
        }
        if (configured != null) {
            try {
                return Mode.valueOf(configured.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Mode.EXPLORE;
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty(
                "cruma.viewer.hud.layout",
                Path.of(System.getProperty("user.home"), ".config", "cruma", "hud.properties")
                        .toString()));
    }

    public static WorkspaceLayout load(Path path) throws IOException {
        var layout = new WorkspaceLayout();
        if (!Files.isRegularFile(path)) {
            return layout;
        }
        var values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
        }
        var version = values.getProperty("version");
        if (!"1".equals(version) && !"2".equals(version) && !"3".equals(version)) {
            return layout;
        }
        try {
            layout.tab = Tab.valueOf(values.getProperty("tab", "MATERIAL"));
        } catch (IllegalArgumentException ignored) {
        }
        layout.inspectorWidth = readNumber(values, "inspectorWidth", 304, 280, 700);
        layout.editingWidth = readNumber(values, "editingWidth", 340, 280, 700);
        layout.treeWidth = readNumber(values, "treeWidth", 224, 200, 500);
        layout.treeFolded = Boolean.parseBoolean(values.getProperty("treeFolded"));
        layout.viewControlsOpen = Boolean.parseBoolean(values.getProperty("viewControlsOpen"));
        layout.statusDocked = Boolean.parseBoolean(values.getProperty("statusDocked"));
        layout.analysisHeight = readNumber(values, "analysisHeight", 92, 92, 360);
        layout.directorHeight = readNumber(values, "directorHeight", 108, 108, 400);
        if (!"3".equals(version)) {
            if (layout.inspectorWidth == 340 || layout.inspectorWidth == 380) {
                layout.inspectorWidth = 304;
            }
            if (layout.treeWidth == 260 || layout.treeWidth == 280) {
                layout.treeWidth = 224;
            }
            if (layout.analysisHeight == 154 || layout.analysisHeight == 112) {
                layout.analysisHeight = 92;
            }
        }
        layout.materialDetails = Boolean.parseBoolean(values.getProperty("materialDetails", "false"));
        return layout;
    }

    public void save(Path path) throws IOException {
        var values = new Properties();
        values.setProperty("version", "3");
        values.setProperty("tab", tab.name());
        values.setProperty("inspectorWidth", Float.toString(inspectorWidth));
        values.setProperty("editingWidth", Float.toString(editingWidth));
        values.setProperty("treeWidth", Float.toString(treeWidth));
        values.setProperty("treeFolded", Boolean.toString(treeFolded));
        values.setProperty("viewControlsOpen", Boolean.toString(viewControlsOpen));
        values.setProperty("statusDocked", Boolean.toString(statusDocked));
        values.setProperty("analysisHeight", Float.toString(analysisHeight));
        values.setProperty("directorHeight", Float.toString(directorHeight));
        values.setProperty("materialDetails", Boolean.toString(materialDetails));
        var absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        var temporary = Files.createTempFile(absolute.getParent(), "hud-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                values.store(output, "Cruma viewer workspace");
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static float readNumber(Properties values, String key, float fallback, float min, float max) {
        try {
            var value = Float.parseFloat(values.getProperty(key, ""));
            return Float.isFinite(value) ? Math.clamp(value, min, max) : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}

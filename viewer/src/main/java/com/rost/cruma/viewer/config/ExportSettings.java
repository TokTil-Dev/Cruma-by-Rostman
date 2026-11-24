package com.rost.cruma.viewer.config;

import com.rost.cruma.core.export.ExportScale;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class ExportSettings {
    public static final String FOLDER = "exports.folder";
    public static final String SCALE = "exports.scale_cm";
    public static final String SCENE_FORMAT = "exports.scene_format";
    public static final String VIDEO_SIZE = "exports.video_size";
    public static final String VIDEO_RATE = "exports.video_rate";
    public static final String INSTALL_AFTER_WRITE = "exports.install_after_write";
    public static final String EDIT_WELCOME_SHOWN = "viewer.editing.welcome_shown";
    public static final String SCRIPTS_FOLDER = "exports.scripts_folder";

    public static final String SCRIPTS_NAME = "Scripts";

    public static final String FOLDER_NAME = "Cruma exports";

    private final @Nullable ConfigLoader loader;
    private final Path home;
    private @Nullable Path chosen;
    private @Nullable Path scriptsChosen;
    private ExportScale scale = ExportScale.REAL_SIZE;
    private SceneFormat sceneFormat = SceneFormat.BLENDER;
    private int videoWidth = 1920;
    private int videoHeight = 1080;
    private int videoRate = 60;
    private boolean installAfterWrite;
    private boolean editWelcomeShown;

    private ExportSettings(@Nullable ConfigLoader loader, Path home) {
        this.loader = loader;
        this.home = home;
    }

    public static ExportSettings in(ConfigLoader loader) {
        return in(loader, Path.of(System.getProperty("user.home")));
    }

    static ExportSettings in(ConfigLoader loader, Path home) {
        var settings = new ExportSettings(loader, home);
        settings.refresh();
        return settings;
    }

    public static ExportSettings session() {
        return new ExportSettings(null, Path.of(System.getProperty("user.home")));
    }

    public static Path defaultFolder(Path home) {
        var documents = home.resolve("Documents");
        return (Files.isDirectory(documents) ? documents : home).resolve(FOLDER_NAME);
    }

    public void refresh() {
        if (loader == null) {
            return;
        }
        try {
            loader.reload();
            var exports = loader.load().exports();
            chosen = exports.folder();
            scale = exports.scale();
            sceneFormat = exports.sceneFormat();
            videoWidth = exports.videoWidth();
            videoHeight = exports.videoHeight();
            videoRate = exports.videoRate();
            installAfterWrite = exports.installAfterWrite();
            scriptsChosen = exports.scriptsFolder();
            editWelcomeShown = loader.load().viewer().editing().welcomeShown();
        } catch (RuntimeException unreadable) {
            LogManager.getLogger(ExportSettings.class)
                    .warn("Could not read the export settings: {}", unreadable.toString());
        }
    }

    public Path getFolder() {
        return chosen != null ? chosen : defaultFolder(home);
    }

    public void setFolder(Path next) {
        var absolute = next.toAbsolutePath().normalize();
        if (absolute.equals(getFolder())) {
            return;
        }
        chosen = absolute;
        write(FOLDER, absolute.toString());
    }

    public Path getScriptsFolder() {
        return scriptsChosen != null ? scriptsChosen : getStandardScriptsFolder();
    }

    public Path getStandardScriptsFolder() {
        return getFolder().resolve(SCRIPTS_NAME);
    }

    public void setScriptsFolder(@Nullable Path next) {
        var absolute = next == null ? null : next.toAbsolutePath().normalize();
        if (absolute != null && absolute.equals(getStandardScriptsFolder())) {
            absolute = null;
        }
        if (java.util.Objects.equals(absolute, scriptsChosen)) {
            return;
        }
        scriptsChosen = absolute;
        write(SCRIPTS_FOLDER, absolute == null ? "" : absolute.toString());
    }

    public ExportScale getScale() {
        return scale;
    }

    public void setScale(ExportScale next) {
        if (next.equals(scale)) {
            return;
        }
        scale = next;
        write(SCALE, Double.parseDouble(Float.toString(next.centimetersPerUnit())));
    }

    public SceneFormat getSceneFormat() {
        return sceneFormat;
    }

    public void setSceneFormat(SceneFormat next) {
        if (next != sceneFormat) {
            sceneFormat = next;
            write(SCENE_FORMAT, next.getKey());
        }
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public void videoSize(int width, int height) {
        if (width != videoWidth || height != videoHeight) {
            videoWidth = width;
            videoHeight = height;
            write(VIDEO_SIZE, width + "x" + height);
        }
    }

    public int getVideoRate() {
        return videoRate;
    }

    public void setVideoRate(int next) {
        if (next != videoRate) {
            videoRate = next;
            write(VIDEO_RATE, next);
        }
    }

    public boolean isInstallAfterWrite() {
        return installAfterWrite;
    }

    public void installAfterWrite(boolean next) {
        if (next != installAfterWrite) {
            installAfterWrite = next;
            write(INSTALL_AFTER_WRITE, next);
        }
    }

    public boolean isEditWelcomeShown() {
        return editWelcomeShown;
    }

    public void setEditWelcomeShown(boolean next) {
        if (next != editWelcomeShown) {
            editWelcomeShown = next;
            write(EDIT_WELCOME_SHOWN, next);
        }
    }

    private void write(String path, Object value) {
        if (loader == null) {
            return;
        }
        try {
            loader.reload();
            var model = new OptionsModel(loader);
            model.set(path, value);
            if (model.hasChanges()) {
                model.save();
            }
        } catch (IOException | RuntimeException failure) {
            LogManager.getLogger(ExportSettings.class).warn("Could not save {}: {}", path, failure.toString());
        }
    }
}

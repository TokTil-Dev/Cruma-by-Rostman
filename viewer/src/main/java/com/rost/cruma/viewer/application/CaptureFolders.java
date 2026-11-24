package com.rost.cruma.viewer.application;

import com.rost.cruma.viewer.config.Config.Capture.Output;
import com.rost.cruma.viewer.config.ConfigurationProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class CaptureFolders {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private CaptureFolders() {}

    static void prepare(Output output, boolean mapPreview) {
        for (var folder : folders(output)) {
            try {
                Files.createDirectories(folder);
                Files.delete(Files.createTempFile(folder, ".cruma-check-", ".tmp"));
            } catch (IOException failure) {
                throw new ConfigurationProblem(message(folder, mapPreview, WINDOWS), failure);
            }
        }
    }

    public static String message(Path folder, boolean mapPreview, boolean windows) {
        var images = mapPreview ? "the map image" : "images";
        var reason = windows
                ? " Windows may block Cruma there with Controlled folder access. Allow Cruma.exe in Windows Security >"
                        + " Virus & threat protection > Ransomware protection"
                : " Check that you can write to that folder";
        var setting = mapPreview
                ? "."
                : ", or choose another folder in Capture map images, Output folder (capture.output.directory in"
                        + " cruma.conf).";
        return "Cruma cannot save " + images + " in " + folder + "." + reason + setting;
    }

    private static Set<Path> folders(Output output) {
        var folders = new LinkedHashSet<Path>();
        if (output.writeTiles()) {
            folders.add(output.directory().toAbsolutePath().normalize());
        }
        if (output.radar().enabled()) {
            folders.add(output.radarPath().toAbsolutePath().normalize().getParent());
        }
        return folders;
    }
}

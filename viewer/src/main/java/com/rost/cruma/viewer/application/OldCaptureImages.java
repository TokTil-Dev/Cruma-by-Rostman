package com.rost.cruma.viewer.application;

import com.rost.cruma.viewer.config.Config.Capture.ImageFormat;
import com.rost.cruma.viewer.config.Config.Capture.Output;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

final class OldCaptureImages {
    private static final Pattern IMAGE = Pattern.compile(
            "\\d{1,3}_\\d{1,3}(?:_[A-Za-z0-9][A-Za-z0-9._-]{0,63})?\\.(?:png|jpe?g)", Pattern.CASE_INSENSITIVE);
    private static final String RADAR_TEMPORARY = ".cruma-radar-";

    private OldCaptureImages() {}

    static int delete(Output output) {
        var files = new ArrayList<Path>();
        var directory = output.directory();
        if (Files.isDirectory(directory)) {
            try (var children = Files.list(directory)) {
                children.filter(file -> {
                            var name = file.getFileName().toString();
                            return IMAGE.matcher(name).matches() || name.startsWith(RADAR_TEMPORARY);
                        })
                        .forEach(files::add);
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot read the capture folder " + directory, failure);
            }
        }
        var radar = output.radarPath();
        for (var format : ImageFormat.values()) {
            files.add(format.getImagePath(radar));
        }
        var deleted = 0;
        for (var file : files.stream().distinct().toList()) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.delete(file);
                deleted++;
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot delete the old image " + file, failure);
            }
        }
        return deleted;
    }
}

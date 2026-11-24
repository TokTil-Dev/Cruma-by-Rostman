package com.rost.cruma.viewer.launch;

import com.rost.cruma.viewer.config.ConfigurationProblem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

public final class ClientFolders {
    private ClientFolders() {}

    public static String label(Path client, Collection<Path> clients) {
        var name = nameOf(client);
        var shared = 0;
        for (var other : clients) {
            if (nameOf(other).equalsIgnoreCase(name) && !other.equals(client)) {
                shared++;
            }
        }
        var parent = client.getParent();
        if (shared == 0 || parent == null) {
            return name;
        }
        return name + " · " + nameOf(parent);
    }

    public static ClientInstallation inspect(Path chosen) {
        var folder = chosen.toAbsolutePath().normalize();
        if (Files.isRegularFile(folder) && folder.getParent() != null) {
            folder = folder.getParent();
        }
        try {
            return ClientInstallation.inspect(folder);
        } catch (ConfigurationProblem problem) {
            var parent = folder.getParent();
            if (parent != null && Files.isDirectory(folder)) {
                try {
                    return ClientInstallation.inspect(parent);
                } catch (ConfigurationProblem notEither) {
                }
            }
            throw problem;
        }
    }

    private static String nameOf(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }
}

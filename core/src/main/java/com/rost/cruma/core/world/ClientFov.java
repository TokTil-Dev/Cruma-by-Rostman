package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.ClassDefaults;
import com.rost.cruma.core.unreal.L2DataFile;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;

public final class ClientFov {
    private static final String CONTROLLER = "[Engine.LineagePlayerController]";
    private static final String OLD_CONTROLLER = "[Engine.PlayerController]";
    private static final String FOV_ANGLE = "FovAngle";
    private static final String DEFAULT_FOV = "DefaultFOV";
    private static final String LOBBY_CONTROLLER = "LineagePlayerController";
    private static final String PLAIN_CONTROLLER = "PlayerController";
    private static final float SHIPPED = 60;
    private static final float SHIPPED_FROM_P152 = 50;
    private static final int ENGINE_FROM_P152 = 132;
    private static final int LICENSEE_FROM_GRACIA_FINAL = 37;
    private static final float LEAST_LOBBY_SHAPE = .777f;

    private ClientFov() {}

    public record Lobby(float fov, float leastShape) {}

    public static float read(Path clientRoot) {
        var written = written(clientRoot.resolve("system").resolve("User.ini"));
        return written > 0 ? written : shipped(engineVersion(clientRoot));
    }

    public static Lobby readLobby(Path clientRoot) {
        var loader = new UArchiveLoader(clientRoot, List.of(new USearchConfig("System", "u")));
        try {
            var engine = loader.loadArchive("Engine");
            if (engine == null) {
                return new Lobby(read(clientRoot), 0);
            }
            var header = engine.getHeader();
            var fov = header.getFileVersion() >= ENGINE_FROM_P152
                    ? lobbyWritten(clientRoot.resolve("system").resolve("User.ini"))
                    : 0f;
            if (!(fov > 0)) {
                fov = ClassDefaults.findFloat(engine, LOBBY_CONTROLLER, FOV_ANGLE);
            }
            if (!(fov > 0)) {
                fov = ClassDefaults.findFloat(engine, PLAIN_CONTROLLER, FOV_ANGLE);
            }
            var least = header.getLicenseVersion() >= LICENSEE_FROM_GRACIA_FINAL ? LEAST_LOBBY_SHAPE : 0f;
            return new Lobby(fov > 0 ? fov : read(clientRoot), least);
        } catch (RuntimeException unreadable) {
            LogManager.getLogger(ClientFov.class).debug("No lobby camera in Engine.u: {}", unreadable.toString());
            return new Lobby(read(clientRoot), 0);
        } finally {
            loader.clearArchives();
        }
    }

    static float shipped(int engineVersion) {
        return engineVersion >= ENGINE_FROM_P152 ? SHIPPED_FROM_P152 : SHIPPED;
    }

    static float of(String ini) {
        var fovAngle = lobbyOf(ini);
        return fovAngle > 0 ? fovAngle : find(ini, OLD_CONTROLLER, DEFAULT_FOV);
    }

    static float lobbyOf(String ini) {
        return find(ini, CONTROLLER, FOV_ANGLE);
    }

    private static int engineVersion(Path clientRoot) {
        var loader = new UArchiveLoader(clientRoot, List.of(new USearchConfig("System", "u")));
        try {
            var engine = loader.loadArchive("Engine");
            return engine == null ? 0 : engine.getHeader().getFileVersion();
        } catch (RuntimeException unreadable) {
            LogManager.getLogger(ClientFov.class).debug("No Engine.u to read: {}", unreadable.toString());
            return 0;
        } finally {
            loader.clearArchives();
        }
    }

    private static float written(Path file) {
        try {
            return of(text(file));
        } catch (IOException | RuntimeException unreadable) {
            LogManager.getLogger(ClientFov.class).debug("No field of view in User.ini: {}", unreadable.toString());
            return 0;
        }
    }

    private static float lobbyWritten(Path file) {
        try {
            return lobbyOf(text(file));
        } catch (IOException | RuntimeException unreadable) {
            LogManager.getLogger(ClientFov.class)
                    .debug("No lobby field of view in User.ini: {}", unreadable.toString());
            return 0;
        }
    }

    private static float find(String ini, String wanted, String key) {
        var section = "";
        var prefix = key + "=";
        for (var line : ini.split("\\r?\\n")) {
            if (line.startsWith("[")) {
                section = line.strip();
            } else if (section.equals(wanted) && line.startsWith(prefix)) {
                return number(line);
            }
        }
        return 0;
    }

    private static String text(Path file) throws IOException {
        byte[] bytes;
        try {
            bytes = L2DataFile.read(file);
        } catch (IOException notEncrypted) {
            bytes = Files.readAllBytes(file);
        }
        var wide = bytes.length > 1 && (bytes[0] == (byte) 0xFF || bytes[1] == 0);
        return new String(bytes, wide ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
    }

    private static float number(String line) {
        return Float.parseFloat(line.substring(line.indexOf('=') + 1).strip());
    }
}

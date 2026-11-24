package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.LoadingScene;
import com.rost.cruma.core.world.LoadingScene.Figure;
import com.rost.cruma.core.world.LoadingScene.FigureLook;
import com.rost.cruma.core.world.LoadingScene.Landmarks;
import com.rost.cruma.core.world.UnrealLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class LoadingSceneSource {
    static final int ANTHARAS = 0, VALAKAS = 1, ORFEN = 2, QUEEN_ANT = 3, ZAKEN = 4, BAIUM = 5;

    private static final Logger log = LogManager.getLogger(LoadingSceneSource.class);
    private static final String[] MAPS = {"20_21.unr", "23_18.unr"};
    private static final FigureLook[] LOOKS = {
        LoadingScene.ANTHARAS,
        LoadingScene.VALAKAS,
        LoadingScene.ORFEN,
        LoadingScene.QUEEN_ANT,
        LoadingScene.ZAKEN,
        LoadingScene.BAIUM
    };
    private static @Nullable LoadingSceneSource latest;
    private final Path client;
    private volatile @Nullable Made made;

    private LoadingSceneSource(Path client) {
        this.client = client;
    }

    record Made(@Nullable Landmarks landmarks, List<@Nullable Figure> figures) {}

    static synchronized @Nullable LoadingSceneSource forClient(Path client) {
        var path = client.toAbsolutePath().normalize();
        if (latest != null && latest.client.equals(path)) {
            return latest;
        }
        if (!hasMaps(path)) {
            return null;
        }
        var source = new LoadingSceneSource(path);
        var thread = new Thread(source::make, "loading-scene");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        latest = source;
        return source;
    }

    static boolean hasMaps(Path client) {
        for (var name : MAPS) {
            if (!hasMap(client, name)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    Made getMade() {
        return made;
    }

    private void make() {
        Landmarks landmarks = null;
        var figures = new Figure[LOOKS.length];
        try {
            var scene = new LoadingScene(new UnrealLoader(client, new Options(false, 0), false));
            landmarks = scene.getLandmarks();
            for (var i = 0; i < LOOKS.length; i++) {
                figures[i] = scene.loadFigure(LOOKS[i]);
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            log.warn("The loading scene stays without some of the client's models: {}", failure.toString());
        } finally {
            made = new Made(landmarks, Arrays.asList(figures));
        }
    }

    private static boolean hasMap(Path client, String name) {
        if (!Files.isDirectory(client)) {
            return false;
        }
        try (var folders = Files.list(client)) {
            return folders.filter(folder -> folder.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .equals("maps"))
                    .anyMatch(folder -> Files.isRegularFile(folder.resolve(name)));
        } catch (IOException failure) {
            return false;
        }
    }
}

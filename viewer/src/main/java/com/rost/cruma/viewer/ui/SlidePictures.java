package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.npc.ClientIcons;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

public final class SlidePictures implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(SlidePictures.class);

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-scene-slides");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentLinkedQueue<Decoded> decoded = new ConcurrentLinkedQueue<>();
    private final Map<String, Picture> pictures = new HashMap<>();
    private Supplier<Reader> source;
    private @Nullable Scene scene;
    private @Nullable ViewerUiRenderer renderer;
    private volatile long reading;
    private final AtomicInteger unread = new AtomicInteger();

    public SlidePictures(Path client) {
        source = () -> new ClientIcons(client)::load;
    }

    static List<String> names(Scene scene) {
        var names = new LinkedHashSet<String>();
        for (var item : scene.items()) {
            var screen = item.screen();
            if (screen != null
                    && !screen.slide().isEmpty()
                    && (screen.kind() == ScreenKind.SLIDE_CHANGE || screen.kind() == ScreenKind.SLIDE_MOVE)) {
                names.add(screen.slide());
            }
        }
        return new ArrayList<>(names);
    }

    public void setSource(Supplier<Reader> source) {
        this.source = source;
    }

    public void follow(@Nullable Scene playing, ViewerUiRenderer ui) {
        renderer = ui;
        if (playing != scene) {
            var kept = scene != null && playing != null && names(playing).equals(names(scene));
            if (!kept) {
                release(ui);
            }
            scene = playing;
            if (playing != null && !kept) {
                read(playing);
            }
        }
        upload(ui);
    }

    public boolean isAllRead() {
        return unread.get() == 0;
    }

    public @Nullable Picture getPicture(String name) {
        return pictures.get(name);
    }

    @Override
    public void close() {
        worker.shutdownNow();
        if (renderer != null) {
            release(renderer);
        }
    }

    private void read(Scene playing) {
        var names = names(playing);
        if (names.isEmpty()) {
            return;
        }
        unread.set(names.size());
        var turn = reading;
        var opened = source;
        worker.submit(() -> {
            var reader = opened.get();
            for (var name : names) {
                if (turn != reading) {
                    return;
                }
                var icon = reader.read(name);
                if (icon == null || icon.width() <= 0 || icon.height() <= 0) {
                    log.debug("The client has no slide {}", name);
                    unread.decrementAndGet();
                } else {
                    decoded.add(new Decoded(turn, name, icon));
                }
            }
        });
    }

    private void upload(ViewerUiRenderer ui) {
        var next = decoded.poll();
        while (next != null && next.turn() != reading) {
            next = decoded.poll();
        }
        if (next == null) {
            return;
        }
        var icon = next.icon();
        var pixels = BufferUtils.createByteBuffer(icon.rgba().length);
        pixels.put(icon.rgba()).flip();
        var texture = ui.createTexture("ui/slide/" + next.name(), icon.width(), icon.height(), pixels);
        pictures.put(next.name(), new Picture(texture, icon.width(), icon.height()));
        unread.decrementAndGet();
    }

    private void release(ViewerUiRenderer ui) {
        reading++;
        unread.set(0);
        decoded.clear();
        for (var picture : pictures.values()) {
            ui.destroyTexture(picture.texture());
        }
        pictures.clear();
    }

    public interface Reader {
        @Nullable
        Icon read(String name);
    }

    public record Picture(long texture, int width, int height) {}

    private record Decoded(long turn, String name, Icon icon) {}
}

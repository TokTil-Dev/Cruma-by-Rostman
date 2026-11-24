package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.npc.ClientIcons;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.ui.SlidePictures.Reader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

final class SlideThumbnails implements AutoCloseable {
    static final int WIDTH = 256;

    static final int HEIGHT = 192;

    private static final int UPLOADS_PER_FRAME = 2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-director-slides");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentLinkedQueue<Read> read = new ConcurrentLinkedQueue<>();
    private final Set<String> asked = new HashSet<>();
    private final Map<String, Long> textures = new HashMap<>();
    private @Nullable Supplier<Reader> source;
    private @Nullable Reader reader;
    private @Nullable ViewerUiRenderer renderer;

    private record Read(String name, @Nullable Icon icon) {}

    static Icon small(Icon icon, int width, int height) {
        var partWidth = width <= 0 ? icon.width() : Math.min(width, icon.width());
        var partHeight = height <= 0 ? icon.height() : Math.min(height, icon.height());
        var scale = Math.min(1, Math.min((double) WIDTH / partWidth, (double) HEIGHT / partHeight));
        var newWidth = Math.max(1, (int) Math.round(partWidth * scale));
        var newHeight = Math.max(1, (int) Math.round(partHeight * scale));
        var rgba = new byte[newWidth * newHeight * 4];
        for (var y = 0; y < newHeight; y++) {
            var fromY = y * partHeight / newHeight;
            var toY = Math.max(fromY + 1, (y + 1) * partHeight / newHeight);
            for (var x = 0; x < newWidth; x++) {
                var fromX = x * partWidth / newWidth;
                var toX = Math.max(fromX + 1, (x + 1) * partWidth / newWidth);
                mean(icon, fromX, toX, fromY, toY, rgba, (y * newWidth + x) * 4);
            }
        }
        return new Icon(newWidth, newHeight, rgba);
    }

    void setClient(Path client) {
        setSource(() -> new ClientIcons(client)::load);
    }

    void setSource(Supplier<Reader> next) {
        source = next;
    }

    long getTexture(String name, int width, int height) {
        var texture = textures.get(name);
        if (texture != null) {
            return texture;
        }
        var opened = source;
        if (opened != null && asked.add(name)) {
            worker.execute(() -> {
                if (reader == null) {
                    reader = opened.get();
                }
                var icon = reader.read(name);
                read.add(new Read(name, icon == null ? null : small(icon, width, height)));
            });
        }
        return 0;
    }

    void poll(ViewerUiRenderer ui) {
        renderer = ui;
        for (var made = 0; made < UPLOADS_PER_FRAME; made++) {
            var next = read.poll();
            if (next == null) {
                return;
            }
            var icon = next.icon();
            if (icon != null) {
                var pixels = BufferUtils.createByteBuffer(icon.rgba().length);
                pixels.put(icon.rgba()).flip();
                textures.put(
                        next.name(),
                        ui.createTexture("ui/slide-thumbnail/" + next.name(), icon.width(), icon.height(), pixels));
            }
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
        if (renderer != null) {
            for (var texture : textures.values()) {
                renderer.destroyTexture(texture);
            }
        }
        textures.clear();
    }

    private static void mean(Icon icon, int fromX, int toX, int fromY, int toY, byte[] out, int at) {
        var sums = new long[4];
        for (var y = fromY; y < toY; y++) {
            for (var x = fromX; x < toX; x++) {
                var pixel = (y * icon.width() + x) * 4;
                for (var channel = 0; channel < 4; channel++) {
                    sums[channel] += icon.rgba()[pixel + channel] & 0xFF;
                }
            }
        }
        var count = (toX - fromX) * (toY - fromY);
        for (var channel = 0; channel < 4; channel++) {
            out[at + channel] = (byte) (sums[channel] / count);
        }
    }
}

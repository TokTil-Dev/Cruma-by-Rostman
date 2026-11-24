package com.rost.cruma.viewer.npc;

import com.rost.cruma.core.npc.ClientIcons;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

public final class NpcIcons implements AutoCloseable {
    static final int KEPT = 640;

    private static final int UPLOADS_PER_FRAME = 48;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "npc-icons");
        thread.setDaemon(true);
        return thread;
    });
    private final @Nullable ClientIcons icons;
    private final ConcurrentLinkedQueue<Decoded> decoded = new ConcurrentLinkedQueue<>();
    private final Set<String> pending = new HashSet<>();
    private final Set<String> missing = new HashSet<>();
    private final LinkedHashMap<String, Long> textures = new LinkedHashMap<>(256, .75f, true);
    private @Nullable ViewerUiRenderer renderer;

    public NpcIcons(@Nullable Path client) {
        this.icons = client == null ? null : new ClientIcons(client);
    }

    private record Decoded(String name, @Nullable Icon icon) {}

    public long getTexture(String name) {
        if (name.isEmpty() || icons == null) {
            return 0;
        }
        var texture = textures.get(name);
        if (texture != null) {
            return texture;
        }
        if (!missing.contains(name) && pending.add(name)) {
            var client = icons;
            worker.submit(() -> decoded.add(new Decoded(name, client.load(name))));
        }
        return 0;
    }

    public long getPicture(String name, Icon icon) {
        var texture = textures.get(name);
        if (texture != null) {
            return texture;
        }
        if (pending.add(name)) {
            decoded.add(new Decoded(name, icon));
        }
        return 0;
    }

    public void poll(ViewerUiRenderer uiRenderer) {
        renderer = uiRenderer;
        for (var uploaded = 0; uploaded < UPLOADS_PER_FRAME; uploaded++) {
            var next = decoded.poll();
            if (next == null) {
                break;
            }
            pending.remove(next.name());
            var icon = next.icon();
            if (icon == null || icon.width() <= 0 || icon.height() <= 0) {
                missing.add(next.name());
                continue;
            }
            var pixels = BufferUtils.createByteBuffer(icon.rgba().length);
            pixels.put(icon.rgba()).flip();
            textures.put(
                    next.name(),
                    uiRenderer.createTexture("ui/icon/" + next.name(), icon.width(), icon.height(), pixels));
        }
        var iterator = textures.entrySet().iterator();
        while (textures.size() > KEPT && iterator.hasNext()) {
            Entry<String, Long> oldest = iterator.next();
            uiRenderer.destroyTexture(oldest.getValue());
            iterator.remove();
        }
    }

    public boolean isAvailable() {
        return icons != null;
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
}

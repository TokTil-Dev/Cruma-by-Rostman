package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.MapPreviewCache;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.lwjgl.system.MemoryUtil;

public final class TileMapPreview {
    private static final int KEPT = 3;

    private final Path configDirectory;
    private final ViewerUiRenderer renderer;

    private final LinkedHashMap<ClientInstallation, Loaded> loaded = new LinkedHashMap<>(KEPT + 1, .75f, true);

    private String problem = "";

    public TileMapPreview(Path configDirectory, ViewerUiRenderer renderer) {
        this.configDirectory = configDirectory;
        this.renderer = renderer;
    }

    private record Loaded(long texture, String problem) {}

    public long getTextureFor(ClientInstallation installation) {
        var known = loaded.get(installation);
        if (known == null) {
            known = load(installation);
            loaded.put(installation, known);
            var oldest = loaded.entrySet().iterator();
            while (loaded.size() > KEPT) {
                var retired = oldest.next().getValue();
                if (retired.texture() != 0) {
                    renderer.destroyTexture(retired.texture());
                }
                oldest.remove();
            }
        }
        problem = known.problem();
        return known.texture();
    }

    public String getProblem() {
        return problem;
    }

    private Loaded load(ClientInstallation installation) {
        try {
            var cache = new MapPreviewCache(configDirectory, installation);
            var image = cache.read();
            if (image == null) {
                return new Loaded(0, "");
            }
            var pixels =
                    MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(image.getWidth(), image.getHeight()), 4));
            try {
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        var rgb = image.getRGB(x, y);
                        pixels.put((byte) (rgb >> 16))
                                .put((byte) (rgb >> 8))
                                .put((byte) rgb)
                                .put((byte) 255);
                    }
                }
                pixels.flip();
                return new Loaded(
                        renderer.createTexture("ui/map-preview", image.getWidth(), image.getHeight(), pixels), "");
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (IOException | RuntimeException failure) {
            return new Loaded(0, "Cannot read map image: " + failure.getMessage());
        }
    }
}

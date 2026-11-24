package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.world.BrandCrystal;
import com.rost.cruma.core.world.LoadingScene.Figure;
import com.rost.cruma.core.world.LoadingScene.Landmarks;
import imgui.ImDrawData;
import java.nio.ByteBuffer;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public interface ViewerUiRenderer extends AutoCloseable {
    void initialize();

    void refreshFontAtlas();

    long createTexture(String label, int width, int height, ByteBuffer rgba);

    void destroyTexture(long textureId);

    default @Nullable ViewerLoadingScene createLoadingScene(Landmarks landmarks, List<@Nullable Figure> figures) {
        return null;
    }

    default void destroyLoadingScene(ViewerLoadingScene scene) {}

    default @Nullable ViewerBrandCrystal createBrandCrystal(BrandCrystal crystal) {
        return null;
    }

    default void destroyBrandCrystal(ViewerBrandCrystal crystal) {}

    void newFrame();

    void render(ImDrawData drawData, int framebufferWidth, int framebufferHeight);

    @Override
    void close();
}

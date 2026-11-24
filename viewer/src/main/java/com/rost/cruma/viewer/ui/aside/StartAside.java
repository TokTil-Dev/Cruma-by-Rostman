package com.rost.cruma.viewer.ui.aside;

import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.render.bgfx.BgfxDevice;
import com.rost.cruma.viewer.render.bgfx.BgfxViewerUiRenderer;
import com.rost.cruma.viewer.ui.FontManager;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public interface StartAside {
    String getLabel();

    void draw(Context context, float width, float height);

    default boolean isAnimating() {
        return false;
    }

    default void close() {}

    record Context(
            ConfigLoader loader,
            FontManager fonts,
            BgfxDevice device,
            BgfxViewerUiRenderer renderer,
            @Nullable ClientInstallation client,
            List<String> tiles,
            String title,
            @Nullable String pointed) {}
}

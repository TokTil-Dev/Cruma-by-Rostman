package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.BrandCrystal;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.viewer.render.api.ViewerBrandCrystal;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import imgui.ImDrawList;
import imgui.ImGui;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class CrumaCrystal {
    private static final Logger log = LogManager.getLogger(CrumaCrystal.class);
    private static final long BEGAN = System.nanoTime();

    private static @Nullable Source source;
    private static @Nullable BrandCrystal shown;
    private static @Nullable ViewerUiRenderer renderer;
    private static @Nullable ViewerBrandCrystal live;
    private static boolean tried;
    private static boolean moved;

    private CrumaCrystal() {}

    private static final class Source {
        final Path client;
        volatile boolean read;
        volatile @Nullable BrandCrystal crystal;

        Source(Path client) {
            this.client = client;
        }

        void read() {
            try {
                crystal = BrandCrystal.read(new UnrealLoader(client, new Options(false, 0), false));
            } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
                log.warn("The mark stays the drawn one: {}", failure.toString());
            } finally {
                read = true;
            }
        }
    }

    static void useClient(Path client) {
        var path = client.toAbsolutePath().normalize();
        if (source != null && source.client.equals(path)) {
            return;
        }
        var next = new Source(path);
        var thread = new Thread(next::read, "mark-crystal");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        source = next;
    }

    static void useRenderer(ViewerUiRenderer next) {
        if (next != renderer) {
            renderer = next;
            live = null;
            tried = false;
        }
    }

    static boolean draw(ImDrawList draw, float x, float y, float size) {
        var crystal = current();
        if (crystal == null) {
            return false;
        }
        var density = Math.max(1f, ImGui.getIO().getDisplayFramebufferScaleX());
        var pixels = Math.round(size * density);
        var seconds = Motion.isEnabled() ? (System.nanoTime() - BEGAN) / 1e9 : 0;
        var drawn = crystal.draw(pixels, seconds);
        if (drawn == null) {
            return false;
        }
        float left = Math.round(x * density) / density, top = Math.round(y * density) / density;
        var side = pixels / density;
        draw.addImage(
                drawn.image(),
                left,
                top,
                left + side,
                top + side,
                drawn.u0(),
                drawn.v0(),
                drawn.u1(),
                drawn.v1(),
                0x00ffffff | Math.round(255 * ImGui.getStyle().getAlpha()) << 24);
        moved |= Motion.isEnabled() && crystal.isMoving();
        return true;
    }

    static boolean takeMoved() {
        var was = moved;
        moved = false;
        return was;
    }

    private static @Nullable ViewerBrandCrystal current() {
        var read = source;
        if (read != null && read.read && read.crystal != shown) {
            if (live != null && renderer != null) {
                renderer.destroyBrandCrystal(live);
            }
            live = null;
            tried = false;
            shown = read.crystal;
        }
        if (!tried && shown != null && renderer != null) {
            tried = true;
            live = renderer.createBrandCrystal(shown);
        }
        return live;
    }
}

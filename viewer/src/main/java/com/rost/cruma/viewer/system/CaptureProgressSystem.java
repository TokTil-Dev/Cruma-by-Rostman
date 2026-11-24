package com.rost.cruma.viewer.system;

import com.rost.cruma.viewer.application.ApplicationContext;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.config.InterfaceScale;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.ui.CrumaBranding;
import com.rost.cruma.viewer.ui.FontManager;
import com.rost.cruma.viewer.ui.HudTheme;
import com.rost.cruma.viewer.ui.HudWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;

public final class CaptureProgressSystem implements System {
    private final WindowContext window;
    private final ApplicationContext application;
    private final ViewerUiRenderer renderer;
    private final int total;
    private final String destination;
    private final InterfaceScale interfaceScale;
    private final FontManager fonts = new FontManager();
    private final SingleWindowImGuiGlfw input = new SingleWindowImGuiGlfw();
    private CrumaBranding branding;
    private boolean started;
    private long startTime;

    public CaptureProgressSystem(
            WindowContext window,
            ApplicationContext application,
            ViewerUiRenderer renderer,
            int total,
            String destination,
            InterfaceScale interfaceScale) {
        this.window = window;
        this.application = application;
        this.renderer = renderer;
        this.total = total;
        this.destination = destination;
        this.interfaceScale = interfaceScale;
    }

    @Override
    public void start() {
        ImGui.createContext();
        started = true;
        if (!input.init(window.windowHandle, true)) {
            throw new IllegalStateException("Cannot initialize capture controls");
        }
        ImGui.getIO().setIniFilename(null);
        input.setInterfaceScale(interfaceScale);
        fonts.initViewerFonts(ImGui.getIO(), input.getPixelDensity(window.windowHandle));
        renderer.initialize();
        branding = new CrumaBranding(renderer);
        ImGui.styleColorsDark();
        startTime = java.lang.System.nanoTime();
    }

    @Override
    public void frameBegin(long frameTime) {
        var io = ImGui.getIO();
        input.newFrame();
        if (fonts.updateViewerScale(io, Math.max(io.getDisplayFramebufferScaleX(), io.getDisplayFramebufferScaleY()))) {
            renderer.refreshFontAtlas();
        }
        renderer.newFrame();
        ImGui.newFrame();
        HudTheme.push(28, 28);
        ImGui.setNextWindowPos(0, 0);
        ImGui.setNextWindowSize(io.getDisplaySizeX(), io.getDisplaySizeY());
        ImGui.begin(
                "##capture-progress",
                ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoSavedSettings);
        branding.mark(ImGui.getWindowDrawList(), ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY(), 28);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + 40);
        ImGui.pushFont(fonts.getHeadingFont(20), 20);
        ImGui.text("Cruma");
        ImGui.popFont();
        ImGui.dummy(0, 24);
        ImGui.pushFont(fonts.getHeadingFont(28), 28);
        ImGui.text("Capturing world");
        ImGui.popFont();
        int submitted = Math.clamp(window.mapCaptureSubmitted + 1, 0, total);
        ImGui.text(submitted + " / " + total + " tiles submitted");
        ImGui.progressBar(total == 0 ? 0 : submitted / (float) total, -1, 5, "");
        ImGui.spacing();
        ImGui.textDisabled(
                submitted == total
                        ? "Finishing image files and radar map…"
                        : "Loading and rendering the selected area…");
        ImGui.textWrapped(destination);
        long seconds = (java.lang.System.nanoTime() - startTime) / 1_000_000_000;
        ImGui.textDisabled(String.format("Elapsed %d:%02d", seconds / 60, seconds % 60));
        ImGui.dummy(0, 12);
        if (HudWidgets.button("cancel-capture", null, "Cancel capture", 150, 32, false, true)) {
            application.running = false;
        }
        ImGui.end();
        HudTheme.pop();
    }

    @Override
    public void frameEnd(long frameTime) {
        ImGui.render();
    }

    @Override
    public void stop(WindowContext context) {
        if (started) {
            renderer.close();
            input.shutdown();
            ImGui.destroyContext();
            started = false;
        }
    }
}

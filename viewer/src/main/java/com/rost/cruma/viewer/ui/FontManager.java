package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImFontGlyphRangesBuilder;
import imgui.ImGui;
import imgui.ImGuiIO;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public class FontManager {
    private static final float MAX_RASTER_DENSITY = 2f;
    private static final long GLYPH_RANGES_FIELD = 0x40;
    private static final Logger log = LogManager.getLogger(FontManager.class);
    private static final int[] ICON_SIZES = {12, 13, 14, 15, 16, 17, 18, 22, 24, 36};
    private static final Map<Long, NavigableMap<Integer, ImFont>> ICON_FONTS = new HashMap<>();
    private static final int MAX_LETTERS = 2048;
    private final List<Long> rangeMemory = new ArrayList<>();
    private ImFont font14;
    private ImFont monoFont;
    private final Map<Integer, ImFont> viewerTextFonts = new HashMap<>();
    private final Map<Integer, ImFont> viewerHeadingFonts = new HashMap<>();
    private float viewerRasterDensity;
    private final Set<Integer> letters = new TreeSet<>();
    private boolean lettersAdded;
    private @Nullable Path koreanFont;
    private boolean koreanFontLooked;

    static @Nullable ImFont iconFont(int size) {
        var fonts = ICON_FONTS.get(ImGui.getIO().getFonts().ptr);
        if (fonts == null) {
            return null;
        }
        var font = fonts.ceilingEntry(size);
        return font != null ? font.getValue() : fonts.lastEntry().getValue();
    }

    static @Nullable Path findKoreanFont(List<Path> candidates) {
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    static List<Path> listKoreanFonts() {
        var windows = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts");
        return List.of(
                Path.of("/System/Library/Fonts/AppleSDGothicNeo.ttc"),
                Path.of("/System/Library/Fonts/Supplemental/AppleGothic.ttf"),
                windows.resolve("malgun.ttf"),
                windows.resolve("gulim.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/truetype/nanum/NanumGothic.ttf"),
                Path.of("/usr/share/fonts/nanum/NanumGothic.ttf"));
    }

    static boolean isKorean(int letter) {
        return letter >= 0x1100 && letter <= 0x11FF
                || letter >= 0x3130 && letter <= 0x318F
                || letter >= 0xA960 && letter <= 0xA97F
                || letter >= 0xAC00 && letter <= 0xD7FF;
    }

    public void initViewerFonts(final ImGuiIO io) {
        initViewerFonts(io, 1f);
    }

    public void initViewerFonts(final ImGuiIO io, float framebufferScale) {
        io.getFonts().clear();
        freeRanges();
        viewerTextFonts.clear();
        viewerHeadingFonts.clear();
        viewerRasterDensity = rasterDensity(framebufferScale);
        var ranges = new ImFontGlyphRangesBuilder();
        ranges.addRanges(io.getFonts().getGlyphRangesDefault());
        ranges.addRanges(io.getFonts().getGlyphRangesCyrillic());
        ranges.addRanges(new short[] {0x2000, 0x206F, 0x2190, 0x21FF, 0x25A0, 0x25FF, 0});
        var config = new ImFontConfig();
        config.setFontDataOwnedByAtlas(true);
        config.setRasterizerDensity(viewerRasterDensity);
        config.setOversampleH(viewerRasterDensity > 1f ? 1 : 2);
        config.setOversampleV(1);
        var glyphRanges = ranges.buildRanges();
        try (var files = new FontFiles()) {
            useRanges(config, glyphRanges);
            var regular = files.of("ui/fonts/inter/InterVariable.ttf");
            var semibold = files.of("ui/fonts/inter/CrumaUI-SemiBold.ttf");
            for (var size : new int[] {12, 13, 14, 16}) {
                viewerTextFonts.put(size, io.getFonts().addFontFromFileTTF(regular, size, config));
            }
            font14 = viewerTextFonts.get(14);
            for (var size : new int[] {15, 17, 18, 19, 20, 28, 32}) {
                viewerHeadingFonts.put(size, io.getFonts().addFontFromFileTTF(semibold, size, config));
            }
            monoFont = io.getFonts()
                    .addFontFromFileTTF(
                            files.of("ui/fonts/jetbrainsmono-2.304/JetBrainsMono-Regular.ttf"), 14f, config);
            addKoreanLetters(io);
            addIconFonts(io, config, files);
        }
        config.destroy();
        io.setFontDefault(font14);
        io.getFonts().build();
    }

    public boolean updateViewerScale(ImGuiIO io, float framebufferScale) {
        if (Math.abs(rasterDensity(framebufferScale) - viewerRasterDensity) < .01f) {
            return false;
        }
        initViewerFonts(io, framebufferScale);
        return true;
    }

    public boolean useLetters(String text) {
        if (getKoreanFont() == null) {
            return false;
        }
        var before = letters.size();
        text.codePoints().filter(FontManager::isKorean).forEach(letter -> {
            if (letters.size() < MAX_LETTERS) {
                letters.add(letter);
            }
        });
        var added = letters.size() > before;
        lettersAdded |= added;
        return added;
    }

    public boolean updateLetters(ImGuiIO io) {
        if (!lettersAdded) {
            return false;
        }
        initViewerFonts(io, viewerRasterDensity);
        return true;
    }

    public float getViewerRasterDensity() {
        return viewerRasterDensity;
    }

    public ImFont getTextFont(int size) {
        return viewerTextFonts.get(size);
    }

    public ImFont getHeadingFont(int size) {
        return viewerHeadingFonts.get(size);
    }

    public ImFont getMonoFont() {
        return monoFont == null ? font14 : monoFont;
    }

    void useRanges(ImFontConfig config, short[] ranges) {
        var field = config.ptr + GLYPH_RANGES_FIELD;
        MemoryUtil.memPutAddress(field, 0L);
        config.setGlyphRanges(ranges);
        if (MemoryUtil.memGetAddress(field) == 0L) {
            log.warn("The glyph ranges are not where imgui-java 1.92.7.1 keeps them, so they stay in a Java array");
            return;
        }
        var memory = MemoryUtil.nmemAllocChecked((ranges.length + 1L) * Short.BYTES);
        rangeMemory.add(memory);
        MemoryUtil.memShortBuffer(memory, ranges.length + 1).put(ranges).put((short) 0);
        MemoryUtil.memPutAddress(field, memory);
    }

    private @Nullable Path getKoreanFont() {
        if (!koreanFontLooked) {
            koreanFontLooked = true;
            koreanFont = findKoreanFont(listKoreanFonts());
        }
        return koreanFont;
    }

    private void addKoreanLetters(ImGuiIO io) {
        lettersAdded = false;
        var file = getKoreanFont();
        if (file == null || letters.isEmpty()) {
            return;
        }
        var ranges = new ImFontGlyphRangesBuilder();
        letters.forEach(letter -> ranges.addChar((char) letter.intValue()));
        var config = new ImFontConfig();
        config.setMergeMode(true);
        config.setFontDataOwnedByAtlas(true);
        config.setRasterizerDensity(viewerRasterDensity);
        config.setOversampleH(1);
        config.setOversampleV(1);
        useRanges(config, ranges.buildRanges());
        io.getFonts().addFontFromFileTTF(file.toString(), 14f, config);
        config.destroy();
    }

    private void addIconFonts(ImGuiIO io, ImFontConfig config, FontFiles files) {
        var codes = new ImFontGlyphRangesBuilder();
        for (var glyph : Glyph.values()) {
            if (glyph.getCode() != 0) {
                codes.addChar((char) glyph.getCode());
            }
        }
        useRanges(config, codes.buildRanges());
        var lucide = files.of("ui/fonts/lucide/lucide.ttf");
        var fonts = new TreeMap<Integer, ImFont>();
        for (var size : ICON_SIZES) {
            fonts.put(size, io.getFonts().addFontFromFileTTF(lucide, size, config));
        }
        ICON_FONTS.put(io.getFonts().ptr, fonts);
    }

    private void freeRanges() {
        for (var memory : rangeMemory) {
            MemoryUtil.nmemFree(memory);
        }
        rangeMemory.clear();
    }

    static final class FontFiles implements AutoCloseable {
        private final List<Path> files = new ArrayList<>();

        String of(String name) {
            try (var stream = FontManager.class.getClassLoader().getResourceAsStream(name)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing bundled resource " + name);
                }
                var file = Files.createTempFile("cruma-font", ".ttf");
                files.add(file);
                Files.write(file, stream.readAllBytes());
                return file.toString();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read bundled resource " + name, e);
            }
        }

        @Override
        public void close() {
            for (var file : files) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException left) {
                    file.toFile().deleteOnExit();
                }
            }
        }
    }

    private static float rasterDensity(float scale) {
        return Float.isFinite(scale) && scale > 0 ? Math.clamp(scale, 1f, MAX_RASTER_DENSITY) : 1f;
    }
}

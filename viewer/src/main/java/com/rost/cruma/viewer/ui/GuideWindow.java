package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.guide.GuideArticle;
import com.rost.cruma.viewer.guide.GuideBlock;
import com.rost.cruma.viewer.guide.GuideBlock.Action;
import com.rost.cruma.viewer.guide.GuideBlock.Bullets;
import com.rost.cruma.viewer.guide.GuideBlock.Code;
import com.rost.cruma.viewer.guide.GuideBlock.Diagram;
import com.rost.cruma.viewer.guide.GuideBlock.Heading;
import com.rost.cruma.viewer.guide.GuideBlock.Image;
import com.rost.cruma.viewer.guide.GuideBlock.Note;
import com.rost.cruma.viewer.guide.GuideBlock.Paragraph;
import com.rost.cruma.viewer.guide.GuideBlock.Steps;
import com.rost.cruma.viewer.guide.GuideBlock.Table;
import com.rost.cruma.viewer.guide.GuideBlock.Within;
import com.rost.cruma.viewer.guide.GuideHistory;
import com.rost.cruma.viewer.guide.GuideLibrary;
import com.rost.cruma.viewer.guide.GuideRequests;
import com.rost.cruma.viewer.guide.GuideSearch.Cache;
import com.rost.cruma.viewer.guide.GuideSpan;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.ui.GuideText.Line;
import com.rost.cruma.viewer.ui.GuideText.Measure;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class GuideWindow {
    public static final float WIDTH = 440;

    public static final float NARROWEST = 320;

    public static final float DIALOG_MARGIN = 16;

    static final String OVER_DIALOG = "Close the dialog first.";

    private static final float PADDING_X = 14;
    private static final float SCROLL_ROOM = 10;

    public static final float BODY = WIDTH - 2 * PADDING_X - SCROLL_ROOM;

    private static final String WINDOW = "##guide";
    private static final int SHOWN = "guide/shown".hashCode();
    private static final float PADDING_Y = 12;
    private static final float HEADER_BUTTON = 24;
    private static final float KIND_GAP = 8;
    private static final float ROW_PAD = 8;
    private static final float GROUP_INDENT = 26;
    private static final float BULLET_INDENT = 18;
    private static final float STEP_INDENT = 24;
    private static final float NOTE_PADDING = 12;
    private static final float CELL_PADDING = 8;
    private static final float CODE_PADDING = GeodataWidgets.ROW;
    private static final int WINDOW_FLAGS = ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoSavedSettings
            | ImGuiWindowFlags.NoFocusOnAppearing
            | ImGuiWindowFlags.NoScrollbar
            | ImGuiWindowFlags.NoScrollWithMouse;
    private static final String SEPARATOR = "  ›  ";
    private static final String NO_WORLD = "Open a world first.";

    private static @Nullable GuideWindow placed;

    private static final float[] covered = new float[4];
    private static int coveredFrame = -1;
    private static long coveredContext;

    private final FontManager fonts;
    private final GuideHost host;
    private final Supplier<@Nullable ViewerUiRenderer> renderer;
    private final GuideHistory history = new GuideHistory();
    private final Cache found = new Cache();
    private final ImString query = new ImString(128);
    private String search = "";
    private final Map<String, Boolean> groupsOpen = new HashMap<>();
    private final Map<Object, List<Line>> wrapped = new IdentityHashMap<>();
    private float wrappedWidth;
    private final Map<Code, CodeText> codes = new IdentityHashMap<>();
    private final Map<String, float[]> scrolls = new HashMap<>();
    private int codesDrawn;
    private @Nullable GuideLibrary library;
    private final Map<String, Long> images = new HashMap<>();
    private final Map<String, int[]> imageSizes = new HashMap<>();
    private final Map<String, float[]> places = new LinkedHashMap<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private boolean open;
    private @Nullable String drawnPage;
    private @Nullable String drawnSearch;
    private @Nullable Snapshot savedView;
    private @Nullable String savedFor;
    private @Nullable String savedBy;
    private float width = WIDTH;
    private float placedLeft;
    private float placedTop;
    private float placedBottom;
    private float placedView;
    private int placedFrame = -1;
    private long placedContext;
    private int drawnFrame = -1;
    private boolean overDialog;
    private boolean drawnOverDialog;
    private int bodyFrame = -1;
    private boolean solid;
    private boolean focused;
    private int focusedFrame = -1;

    public GuideWindow(FontManager fonts, GuideHost host, Supplier<@Nullable ViewerUiRenderer> renderer) {
        this.fonts = fonts;
        this.host = host;
        this.renderer = renderer;
    }

    public static float width(float view) {
        return Math.round(Math.clamp(view, NARROWEST, WIDTH));
    }

    public static void drawOverDialog() {
        var guide = placed;
        var frame = ImGui.getFrameCount();
        if (guide != null
                && guide.open
                && guide.placedContext == ImGui.getCurrentContext().ptr
                && guide.placedFrame == frame
                && guide.drawnFrame != frame) {
            guide.show(true);
        }
    }

    public static float[] dialogRoom(float least) {
        var display = ImGui.getIO().getDisplaySizeX();
        var guide = placed;
        if (guide != null
                && guide.open
                && guide.placedContext == ImGui.getCurrentContext().ptr
                && guide.placedFrame == ImGui.getFrameCount()) {
            var edge = Math.round(guide.placedLeft + width(guide.placedView));
            if (display - edge - 2 * DIALOG_MARGIN >= least) {
                return new float[] {edge, display - edge};
            }
        }
        return new float[] {0, display};
    }

    public static boolean covers(float x, float y) {
        return coveredContext == ImGui.getCurrentContext().ptr
                && coveredFrame >= ImGui.getFrameCount() - 1
                && x >= covered[0]
                && x < covered[2]
                && y >= covered[1]
                && y < covered[3];
    }

    public void setSolid(boolean solid) {
        this.solid = solid;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean hasKeys() {
        return open && focused && focusedFrame >= ImGui.getFrameCount() - 1;
    }

    public void open(String page) {
        open = true;
        clearSearch();
        history.open(page);
    }

    public void close() {
        open = false;
        GuideActions.resetAll();
    }

    public void release() {
        close();
        if (placed == this) {
            placed = null;
        }
        coveredFrame = -1;
        coveredContext = 0;
        GuideDiagrams.forgetFonts(fonts);
        GuideRequests.take();
    }

    public String getPage() {
        return history.getCurrent();
    }

    public String getSearch() {
        return search;
    }

    public GuideHistory getHistory() {
        return history;
    }

    public float @Nullable [] place(String name) {
        return places.get(name);
    }

    public float @Nullable [] getScroll(String name) {
        return scrolls.get(name);
    }

    public void useLibrary(GuideLibrary library) {
        this.library = library;
        codes.clear();
    }

    public int getId(String name) {
        return ids.getOrDefault(name, 0);
    }

    public void draw(float left, float top, float bottom, float view) {
        placeAt(left, top, bottom, view);
        drawPlaced();
    }

    public void placeAt(float left, float top, float bottom, float view) {
        placedLeft = left;
        placedTop = top;
        placedBottom = bottom;
        placedView = view;
        placedFrame = ImGui.getFrameCount();
        placed = this;
        placedContext = ImGui.getCurrentContext().ptr;
    }

    public void drawPlaced() {
        var frame = ImGui.getFrameCount();
        if (placedFrame == frame && drawnFrame != frame) {
            show(false);
        }
        if (placed == this) {
            placed = null;
        }
    }

    private void show(boolean overDialog) {
        if (!open) {
            return;
        }
        drawnFrame = ImGui.getFrameCount();
        this.overDialog = overDialog;
        var underPopup = overDialog && ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId);
        var left = placedLeft;
        var top = placedTop;
        var bottom = placedBottom;
        var view = placedView;
        places.clear();
        ids.clear();
        scrolls.clear();
        GuideDiagrams.useFonts(fonts);
        width = width(view);
        ImGui.setNextWindowPos(Math.round(left), Math.round(top), ImGuiCond.Always);
        ImGui.setNextWindowSize(width, Math.max(160, Math.round(bottom - top)), ImGuiCond.Always);
        ImGui.setNextWindowBgAlpha(overDialog || solid ? 1f : .985f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, HudWidgets.PANEL_RADIUS);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, PADDING_X, PADDING_Y);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8, 4);
        ImGui.begin(WINDOW, WINDOW_FLAGS);
        if (overDialog && !underPopup) {
            imgui.internal.ImGui.bringWindowToDisplayFront(imgui.internal.ImGui.getCurrentWindow());
        }
        Motion.open(Motion.appear(SHOWN, Motion.OPEN), -20, 0, .98f);
        header();
        searchField();
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        body();
        if (overDialog) {
            covered[0] = ImGui.getWindowPosX();
            covered[1] = ImGui.getWindowPosY();
            covered[2] = covered[0] + ImGui.getWindowWidth();
            covered[3] = covered[1] + ImGui.getWindowHeight();
            coveredFrame = ImGui.getFrameCount();
            coveredContext = ImGui.getCurrentContext().ptr;
        }
        focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
        focusedFrame = ImGui.getFrameCount();
        var closeByKey = overDialog
                && focused
                && !ImGui.getIO().getWantTextInput()
                && ImGui.isKeyPressed(ImGuiKey.Escape, false);
        ImGui.end();
        ImGui.popStyleVar(3);
        if (closeByKey) {
            close();
        }
    }

    private void header() {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var right = x + ImGui.getContentRegionAvailX();
        HudWidgets.glyph(draw, Glyph.BOOK_OPEN, x, y + 3, 18, HudWidgets.ACCENT);
        ImGui.setCursorScreenPos(x + 26, y + 1);
        ImGui.pushFont(fonts.getHeadingFont(18), 18);
        ImGui.textUnformatted("Guide");
        ImGui.popFont();
        var closeX = right - HEADER_BUTTON;
        var homeX = closeX - GeodataWidgets.ROW - HEADER_BUTTON;
        var forwardX = homeX - 4 - HEADER_BUTTON;
        var backX = forwardX - 4 - HEADER_BUTTON;
        ImGui.setCursorScreenPos(backX, y);
        ImGui.beginDisabled(!history.canGoBack() && search.isEmpty());
        if (HudWidgets.button("guide-back", Glyph.BACK, "", HEADER_BUTTON, HEADER_BUTTON, false, false)) {
            back();
        }
        ImGui.endDisabled();
        remember("back");
        GeodataWidgets.tip("Back");
        ImGui.setCursorScreenPos(forwardX, y);
        ImGui.beginDisabled(!history.canGoForward());
        if (HudWidgets.button("guide-forward", Glyph.GO, "", HEADER_BUTTON, HEADER_BUTTON, false, false)) {
            clearSearch();
            history.forward();
        }
        ImGui.endDisabled();
        remember("forward");
        GeodataWidgets.tip("Forward");
        ImGui.setCursorScreenPos(homeX, y);
        if (HudWidgets.button("guide-home", Glyph.HOME, "", HEADER_BUTTON, HEADER_BUTTON, false, false)) {
            clearSearch();
            history.open(GuideHistory.HOME);
        }
        remember("home");
        GeodataWidgets.tip("Home: every section of the Guide");
        var dividerX = Math.round(closeX - GeodataWidgets.ROW / 2f);
        draw.addLine(dividerX, y + 5, dividerX, y + HEADER_BUTTON - 5, HudWidgets.BORDER, 1);
        ImGui.setCursorScreenPos(closeX, y);
        if (HudWidgets.button("guide-close", Glyph.CLOSE, "", HEADER_BUTTON, HEADER_BUTTON, false, false)) {
            close();
        }
        remember("close");
        GeodataWidgets.tip("Close the Guide · Esc");
        ImGui.setCursorScreenPos(x, y + HEADER_BUTTON);
        ImGui.dummy(0, 0);
        GeodataWidgets.space(6);
        HudWidgets.line(ImGui.getContentRegionAvailX());
        GeodataWidgets.space(GeodataWidgets.ROW);
    }

    private void back() {
        if (!search.isEmpty()) {
            clearSearch();
            return;
        }
        history.back();
    }

    private void searchField() {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 30, 7);
        if (GeodataWidgets.searchField(
                "##guideSearch", "Search the Guide", query, ImGui.getContentRegionAvailX(), false)) {
            search = query.get().strip();
        }
        ImGui.popStyleVar();
        remember("search");
        HudWidgets.glyph(ImGui.getWindowDrawList(), Glyph.SEARCH, x + 9, y + 7, 15, HudWidgets.MUTED);
    }

    private void clearSearch() {
        query.set("");
        search = "";
    }

    private void body() {
        var library = this.library != null ? this.library : GuideLibrary.shared();
        var page = history.getCurrent();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild("##guideBody", 0, 0, false);
        ImGui.popStyleVar();
        var frame = ImGui.getFrameCount();
        var changed = !page.equals(drawnPage) || !search.equals(drawnSearch);
        if (changed || overDialog != drawnOverDialog || bodyFrame != frame - 1) {
            GuideActions.resetAll();
        }
        bodyFrame = frame;
        drawnOverDialog = overDialog;
        if (changed) {
            ImGui.setScrollY(0);
            if (savedFor != null && !savedFor.equals(page)) {
                savedView = null;
                savedFor = null;
                savedBy = null;
            }
            drawnPage = page;
            drawnSearch = search;
        }
        var inner = (float) Math.floor(ImGui.getWindowWidth() - SCROLL_ROOM);
        if (inner != wrappedWidth) {
            wrapped.clear();
            wrappedWidth = inner;
        }
        if (library.getProblem() != null) {
            GeodataWidgets.note("The Guide could not be read: " + library.getProblem(), GeodataWidgets.WARNING, inner);
        } else if (!search.isEmpty()) {
            results(library, inner);
        } else if (page.equals(GuideHistory.HOME)) {
            home(library, inner);
        } else {
            var article = library.getArticle(page);
            if (article == null) {
                GeodataWidgets.note("There is no article " + page + ".", GeodataWidgets.WARNING, inner);
            } else {
                article(library, article, inner);
            }
        }
        GeodataWidgets.space(GeodataWidgets.SECTION);
        scrolls.put("body", new float[] {0, 0, ImGui.getScrollY(), ImGui.getScrollMaxY()});
        ImGui.endChild();
    }

    private void home(GuideLibrary library, float inner) {
        var first = true;
        var tool = host.getOpenTool();
        if (tool != null) {
            var mine = library.forTool(tool);
            if (!mine.isEmpty()) {
                GeodataWidgets.section("FOR THIS TOOL", GeodataWidgets.ROW);
                first = false;
                GeodataWidgets.space(GeodataWidgets.ROW);
                ImGui.pushID("tool");
                for (var article : mine) {
                    articleRow(article, inner, 0);
                }
                ImGui.popID();
            }
        }
        var start = library.findSection("start");
        if (start != null) {
            GeodataWidgets.section("START HERE", first ? GeodataWidgets.ROW : GeodataWidgets.SECTION);
            first = false;
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.pushID("start");
            for (var article : start.articles()) {
                articleRow(article, inner, 0);
            }
            ImGui.popID();
        }
        GeodataWidgets.section("SECTIONS", first ? GeodataWidgets.ROW : GeodataWidgets.SECTION);
        GeodataWidgets.space(GeodataWidgets.ROW);
        for (var section : library.getSections()) {
            if (section.id().equals("start")) {
                continue;
            }
            group(
                    section.id(),
                    section.title(),
                    count(section.articles().size(), "article"),
                    section.articles(),
                    inner);
        }
        group(GuideLibrary.TERMS, "Glossary", count(library.getTerms().size(), "term"), library.getTerms(), inner);
    }

    private void group(String id, String title, String detail, List<GuideArticle> articles, float inner) {
        var opened = groupsOpen.getOrDefault(id, false);
        var top = ImGui.getCursorScreenPosY();
        if (SpawnPanelWidgets.groupRow("guide-group/" + id, opened, title, detail, "", "", rowHeight(), inner)) {
            opened = !opened;
            groupsOpen.put(id, opened);
        }
        remember("group:" + id);
        if (opened) {
            ImGui.pushID("group/" + id);
            for (var article : articles) {
                articleRow(article, inner, GROUP_INDENT, "group-row:");
            }
            ImGui.popID();
        }
        if (ImGui.getCursorScreenPosY() > top) {
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
    }

    private void articleRow(GuideArticle article, float inner, float indent) {
        articleRow(article, inner, indent, "row:");
    }

    private void articleRow(GuideArticle article, float inner, float indent, String name) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = rowHeight();
        if (ImGui.invisibleButton("##row/" + article.id(), inner, height)) {
            open(article.id());
        }
        remember(name + article.id());
        var hovered = ImGui.isItemHovered();
        var lit = Motion.toggle(ImGui.getItemID(), hovered, Motion.QUICK);
        if (lit > 0) {
            draw.addRectFilled(x, y, x + inner, y + height, Motion.alpha(GeodataWidgets.HOVER, lit), 6f);
        }
        var left = x + ROW_PAD + indent;
        var room = inner - ROW_PAD * 2 - indent;
        draw.addText(left, y + 4, HudWidgets.TEXT, HudWidgets.truncateToWidth(article.title(), room));
        var summary = HudWidgets.truncateToWidth(article.summary(), room);
        draw.addText(left, y + ImGui.getFontSize() + 6, HudWidgets.MUTED, summary);
        if (hovered) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            if (!summary.equals(article.summary())) {
                HudWidgets.showTooltip(article.summary());
            }
        }
    }

    private void results(GuideLibrary library, float inner) {
        var hits = found.search(library, search);
        if (hits.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note("Nothing found for “" + search + "”.", HudWidgets.MUTED, inner);
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    "Try fewer words, or a word from a panel, such as layer, NSWE or region.",
                    GeodataWidgets.FAINT,
                    inner);
            return;
        }
        GeodataWidgets.section(count(hits.size(), "result").toUpperCase(Locale.ROOT), GeodataWidgets.ROW);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var draw = ImGui.getWindowDrawList();
        for (var hit : hits) {
            var article = hit.article();
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var height = rowHeight();
            if (ImGui.invisibleButton("##result/" + article.id(), inner, height)) {
                open(article.id());
            }
            remember("result:" + article.id());
            var hovered = ImGui.isItemHovered();
            if (hovered) {
                draw.addRectFilled(x, y, x + inner, y + height, GeodataWidgets.HOVER, 6f);
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            }
            var where = article.isTerm() ? "Term" : sectionTitle(library, article);
            var whereWidth = ImGui.calcTextSizeX(where);
            var left = x + ROW_PAD;
            var room = inner - 2 * ROW_PAD;
            draw.addText(x + inner - ROW_PAD - whereWidth, y + 4, GeodataWidgets.FAINT, where);
            draw.addText(
                    left, y + 4, HudWidgets.TEXT, HudWidgets.truncateToWidth(article.title(), room - whereWidth - 12));
            highlighted(left, y + ImGui.getFontSize() + 6, room, hit.line(), hit.matches());
        }
    }

    private void article(GuideLibrary library, GuideArticle article, float inner) {
        breadcrumb(library, article, inner);
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.pushFont(fonts.getHeadingFont(18), 18);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + inner);
        ImGui.textUnformatted(article.title());
        ImGui.popTextWrapPos();
        ImGui.popFont();
        GuideBlock previous = null;
        codesDrawn = 0;
        for (var index = 0; index < article.blocks().size(); index++) {
            var block = article.blocks().get(index);
            GeodataWidgets.space(gap(block, previous));
            ImGui.pushID(index);
            block(article, block, inner);
            ImGui.popID();
            previous = block;
        }
        var next = article.next() == null ? null : library.getArticle(article.next());
        if (next != null) {
            GeodataWidgets.space(GeodataWidgets.SECTION);
            if (HudWidgets.button(
                    "guide-next", Glyph.GO, "Next: " + next.title(), inner, HudWidgets.CONTROL_HEIGHT, false, true)) {
                open(next.id());
            }
            remember("next");
            GeodataWidgets.tip(next.summary());
        }
        var related = new ArrayList<GuideArticle>();
        for (var id : article.related()) {
            var found = library.getArticle(id);
            if (found != null) {
                related.add(found);
            }
        }
        if (!related.isEmpty()) {
            GeodataWidgets.section("RELATED");
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.pushID("related");
            for (var each : related) {
                articleRow(each, inner, 0);
            }
            ImGui.popID();
        }
    }

    private void breadcrumb(GuideLibrary library, GuideArticle article, float inner) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var section = article.isTerm() ? "Glossary" : sectionTitle(library, article);
        if (crumb("guide-crumb-home", "Guide", x, y)) {
            history.open(GuideHistory.HOME);
        }
        remember("crumb:home");
        var sectionX = x + ImGui.calcTextSizeX("Guide");
        draw.addText(sectionX, y, GeodataWidgets.FAINT, SEPARATOR);
        sectionX += ImGui.calcTextSizeX(SEPARATOR);
        if (crumb("guide-crumb-section", section, sectionX, y)) {
            groupsOpen.put(article.section(), true);
            history.open(GuideHistory.HOME);
        }
        remember("crumb:section");
        var kind = article.kind().getLabel();
        var gap = ImGui.getScrollMaxY() > 0 ? KIND_GAP : 0;
        draw.addText(x + inner - gap - ImGui.calcTextSizeX(kind), y, GeodataWidgets.FAINT, kind);
        ImGui.setCursorScreenPos(x, y + ImGui.getFontSize());
        ImGui.dummy(0, 0);
    }

    private void block(GuideArticle article, GuideBlock block, float inner) {
        switch (block) {
            case Heading heading -> {
                var size = heading.level() == 2 ? 17 : 15;
                ImGui.pushFont(fonts.getHeadingFont(size), size);
                ImGui.pushTextWrapPos(ImGui.getCursorPosX() + inner);
                ImGui.textUnformatted(heading.text());
                ImGui.popTextWrapPos();
                ImGui.popFont();
            }
            case Paragraph paragraph -> text("p", paragraph.spans(), inner, HudWidgets.MUTED);
            case Bullets bullets -> list(bullets.items(), inner, false, 1);
            case Steps steps -> list(steps.items(), inner, true, steps.first());
            case Note note -> note(note, inner);
            case Diagram diagram -> diagram(diagram, inner);
            case Image image -> image(image, inner);
            case Action action -> action(action, inner);
            case Table table -> table(table, inner);
            case Code code -> code(code, inner);
        }
    }

    private void text(String id, List<GuideSpan> spans, float width, int color) {
        var lines = wrapped.computeIfAbsent(spans, key -> GuideText.layout(spans, width, measure()));
        draw(id, lines, color);
    }

    private void draw(String id, List<Line> lines, int color) {
        var pressed = GuideText.draw(id, lines, fonts.getTextFont(14), fonts.getMonoFont(), color, this::rememberLink);
        if (pressed != null) {
            open(pressed);
        }
    }

    private Measure measure() {
        return GuideText.measure(fonts.getTextFont(14), fonts.getMonoFont());
    }

    private void rememberLink(String target, float[] place) {
        places.putIfAbsent("link:" + target, place);
    }

    private void list(List<List<GuideSpan>> items, float inner, boolean numbered, int first) {
        var draw = ImGui.getWindowDrawList();
        var indent = numbered ? STEP_INDENT : BULLET_INDENT;
        for (var index = 0; index < items.size(); index++) {
            if (index > 0) {
                GeodataWidgets.space(numbered ? GeodataWidgets.ROW : 4);
            }
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var textTop = y + (GuideText.LINE - ImGui.getFontSize()) * .5f - 1;
            if (numbered) {
                var number = (first + index) + ".";
                draw.addText(x + indent - 8 - ImGui.calcTextSizeX(number), textTop, HudWidgets.ACCENT, number);
            } else {
                draw.addCircleFilled(x + 6, Math.round(HudWidgets.textMiddle(textTop)), 2.5f, HudWidgets.MUTED, 12);
            }
            ImGui.setCursorScreenPos(x + indent, y);
            ImGui.pushID(index);
            text("item", items.get(index), inner - indent, HudWidgets.MUTED);
            ImGui.popID();
            ImGui.setCursorScreenPos(x, ImGui.getCursorScreenPosY());
        }
    }

    private void note(Note note, float inner) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var textLeft = NOTE_PADDING + 4 + (note.warning() ? 24 : 0);
        var textWidth = inner - textLeft - NOTE_PADDING;
        var lines = wrapped.computeIfAbsent(note.spans(), key -> GuideText.layout(note.spans(), textWidth, measure()));
        var height = Math.round(GuideText.height(lines) + 2 * (NOTE_PADDING - 2));
        var stripe = note.warning() ? GeodataWidgets.WARNING : HudWidgets.ACCENT;
        draw.addRectFilled(x, y, x + inner, y + height, GeodataWidgets.CARD, 8f);
        draw.addRectFilled(x, y, x + 3, y + height, stripe, 8f, ImDrawFlags.RoundCornersLeft);
        if (note.warning()) {
            HudWidgets.glyph(draw, Glyph.ALERT, x + NOTE_PADDING + 4, y + NOTE_PADDING, 16, GeodataWidgets.WARNING);
        }
        ImGui.setCursorScreenPos(x + textLeft, y + NOTE_PADDING - 2);
        draw("note", lines, HudWidgets.MUTED);
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(inner, 0);
    }

    private void diagram(Diagram diagram, float inner) {
        var picture = GuideDiagrams.get(diagram.id());
        if (picture == null) {
            GeodataWidgets.note("The diagram " + diagram.id() + " is missing.", GeodataWidgets.WARNING, inner);
            return;
        }
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = (float) Math.ceil(picture.getHeight(inner));
        picture.draw(ImGui.getWindowDrawList(), Math.round(x), Math.round(y), inner);
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(inner, 0);
        caption(diagram.caption(), inner);
    }

    private void caption(String caption, float inner) {
        if (caption.isBlank()) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.pushFont(fonts.getTextFont(13), 13);
        GeodataWidgets.note(caption, GeodataWidgets.FAINT, inner);
        ImGui.popFont();
    }

    private void image(Image image, float inner) {
        var texture = images.computeIfAbsent(image.name(), this::loadImage);
        if (texture == 0) {
            GeodataWidgets.note("The picture " + image.name() + " could not be read.", GeodataWidgets.WARNING, inner);
            return;
        }
        var size = imageSizes.getOrDefault(image.name(), new int[] {1, 1});
        var height = Math.round(inner * size[1] / (float) size[0]);
        ImGui.image(texture, inner, height);
        caption(image.caption(), inner);
    }

    private long loadImage(String name) {
        var ui = renderer.get();
        if (ui == null) {
            return 0;
        }
        try (var stream = GuideWindow.class.getClassLoader().getResourceAsStream("guide/images/" + name)) {
            var decoded = stream == null ? null : ImageIO.read(stream);
            if (decoded == null) {
                return 0;
            }
            int w = decoded.getWidth(), h = decoded.getHeight();
            var pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(w, h), 4));
            try {
                for (var row = 0; row < h; row++) {
                    for (var column = 0; column < w; column++) {
                        var argb = decoded.getRGB(column, row);
                        pixels.put((byte) (argb >> 16))
                                .put((byte) (argb >> 8))
                                .put((byte) argb)
                                .put((byte) (argb >> 24));
                    }
                }
                pixels.flip();
                imageSizes.put(name, new int[] {w, h});
                return ui.createTexture("guide/" + name, w, h, pixels);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (IOException | RuntimeException failure) {
            LogManager.getLogger(GuideWindow.class).warn("Could not read the picture {}: {}", name, failure.toString());
            return 0;
        }
    }

    private void action(Action block, float inner) {
        var action = GuideActions.get(block.id());
        if (action == null) {
            GeodataWidgets.note("The action " + block.id() + " is missing.", GeodataWidgets.WARNING, inner);
            return;
        }
        var page = history.getCurrent();
        var blocked = host.getView() == null ? NO_WORLD : overDialog ? OVER_DIALOG : action.getBlocked(host);
        var status = action.getStatus(host);
        var label = block.label();
        var labelWidth = Math.min(inner, ImGui.calcTextSizeX(label) + 18 + 8 + 28);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(blocked != null || action.isBusy());
        if (HudWidgets.button(
                "guide-action/" + block.id(), Glyph.FOCUS, label, labelWidth, HudWidgets.CONTROL_HEIGHT, false, true)) {
            if (savedFor == null || !savedFor.equals(page) || savedView == null) {
                savedView = host.getView();
                savedFor = page;
            }
            savedBy = block.id();
            action.run(host);
            status = action.getStatus(host);
        }
        ImGui.endDisabled();
        remember("action:" + block.id());
        var saved = savedView;
        if (saved != null && page.equals(savedFor) && block.id().equals(savedBy) && isMoved(saved)) {
            var backLabel = "Back to where I was";
            var backWidth = ImGui.calcTextSizeX(backLabel) + 18 + 8 + 28;
            var besideX = x + labelWidth + GeodataWidgets.ROW;
            if (besideX + backWidth <= x + inner) {
                ImGui.setCursorScreenPos(besideX, y);
            } else {
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
            ImGui.beginDisabled(overDialog);
            if (HudWidgets.button(
                    "guide-action-back", Glyph.BACK, backLabel, backWidth, HudWidgets.CONTROL_HEIGHT, false, true)) {
                host.restoreView(saved);
                savedView = null;
                savedFor = null;
                savedBy = null;
            }
            ImGui.endDisabled();
            remember("action-back");
            GeodataWidgets.tip("Put the camera back where it stood before the button moved it.");
        }
        var note = blocked != null ? blocked : status;
        if (note != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(note, blocked != null ? GeodataWidgets.FAINT : HudWidgets.MUTED, inner);
        }
    }

    private boolean isMoved(Snapshot saved) {
        var now = host.getView();
        return now != null
                && (Math.abs(now.x() - saved.x()) > .5f
                        || Math.abs(now.y() - saved.y()) > .5f
                        || Math.abs(now.z() - saved.z()) > .5f);
    }

    private void table(Table table, float inner) {
        var columns = table.header().size();
        var widths = columnWidths(table, inner);
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var rows = new ArrayList<List<List<GuideSpan>>>();
        rows.add(table.header());
        rows.addAll(table.rows());
        var top = y;
        for (var row = 0; row < rows.size(); row++) {
            var cells = rows.get(row);
            var height = 0f;
            var laid = new ArrayList<List<Line>>();
            for (var column = 0; column < columns; column++) {
                var cell = cells.get(column);
                var cellWidth = widths[column] - 2 * CELL_PADDING;
                var lines = wrapped.computeIfAbsent(cell, key -> GuideText.layout(cell, cellWidth, measure()));
                laid.add(lines);
                height = Math.max(height, GuideText.height(lines));
            }
            height = Math.max(height, GuideText.LINE) + 8;
            if (row == 0) {
                draw.addRectFilled(x, top, x + inner, top + height, GeodataWidgets.CARD, 6f);
            }
            var left = x;
            for (var column = 0; column < columns; column++) {
                ImGui.setCursorScreenPos(left + CELL_PADDING, top + 4);
                ImGui.pushID(row);
                ImGui.pushID(column);
                draw("cell", laid.get(column), row == 0 ? HudWidgets.MUTED : HudWidgets.TEXT);
                ImGui.popID();
                ImGui.popID();
                left += widths[column];
            }
            top += height;
            if (row > 0 && row < rows.size() - 1) {
                draw.addLine(x, top, x + inner, top, GeodataWidgets.DIVIDER, 1);
            }
        }
        ImGui.setCursorScreenPos(x, top);
        ImGui.dummy(inner, 0);
    }

    private float[] columnWidths(Table table, float inner) {
        var columns = table.header().size();
        var natural = new float[columns];
        var measure = measure();
        var rows = new ArrayList<List<List<GuideSpan>>>();
        rows.add(table.header());
        rows.addAll(table.rows());
        for (var row : rows) {
            for (var column = 0; column < columns; column++) {
                var lines = GuideText.layout(row.get(column), Float.MAX_VALUE, measure);
                var widest = 0f;
                for (var line : lines) {
                    widest = Math.max(widest, line.width());
                }
                natural[column] = Math.max(natural[column], widest + 2 * CELL_PADDING);
            }
        }
        var widths = new float[columns];
        var left = inner;
        var open = columns;
        var fixed = new boolean[columns];
        var changed = true;
        while (changed && open > 0) {
            changed = false;
            var share = left / open;
            for (var column = 0; column < columns; column++) {
                if (!fixed[column] && natural[column] <= share) {
                    fixed[column] = true;
                    widths[column] = natural[column];
                    left -= natural[column];
                    open--;
                    changed = true;
                }
            }
        }
        for (var column = 0; column < columns; column++) {
            if (!fixed[column]) {
                widths[column] = (float) Math.floor(left / open);
            }
        }
        var sum = 0f;
        for (var width : widths) {
            sum += width;
        }
        widths[columns - 1] += inner - sum;
        return widths;
    }

    private void code(Code code, float inner) {
        var indent = switch (code.within()) {
            case BODY -> 0f;
            case BULLETS -> BULLET_INDENT;
            case STEPS -> STEP_INDENT;
        };
        var name = "code:" + codesDrawn++;
        var mono = fonts.getMonoFont();
        var text = codes.computeIfAbsent(code, key -> codeText(key, mono));
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var left = x + indent;
        var width = inner - indent;
        var height = code.lines().size() * GuideText.SIZE + 2 * CODE_PADDING;
        var fieldWidth = (float) Math.ceil(text.width() + 2 * CODE_PADDING);
        var sideways = fieldWidth > width;
        var boxHeight = height + (sideways ? ImGui.getStyle().getScrollbarSize() : 0);
        ImGui.getWindowDrawList().addRectFilled(left, y, left + width, y + boxHeight, GuideText.CODE_FILL, 6f);
        ImGui.setCursorScreenPos(left, y);
        ImGui.setNextWindowContentSize(Math.max(width, fieldWidth), height);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild(
                "##code",
                width,
                boxHeight,
                false,
                sideways
                        ? ImGuiWindowFlags.HorizontalScrollbar | ImGuiWindowFlags.AlwaysHorizontalScrollbar
                        : ImGuiWindowFlags.NoScrollbar);
        ImGui.popStyleVar();
        ImGui.pushFont(mono, GuideText.SIZE);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, CODE_PADDING, CODE_PADDING);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0);
        ImGui.pushStyleColor(ImGuiCol.FrameBg, 0);
        ImGui.pushStyleColor(ImGuiCol.Text, HudWidgets.TEXT);
        ImGui.pushStyleColor(ImGuiCol.NavCursor, 0);
        ImGui.inputTextMultiline(
                "##lines",
                text.text(),
                Math.max(width, fieldWidth),
                height,
                ImGuiInputTextFlags.ReadOnly | ImGuiInputTextFlags.NoHorizontalScroll);
        ImGui.popStyleColor(3);
        ImGui.popStyleVar(2);
        ImGui.popFont();
        scrolls.put(
                name,
                new float[] {ImGui.getScrollX(), ImGui.getScrollMaxX(), ImGui.getScrollY(), ImGui.getScrollMaxY()});
        ImGui.endChild();
        remember(name);
        ImGui.setCursorScreenPos(x, y + boxHeight);
        ImGui.dummy(inner, 0);
    }

    private void remember(String name) {
        places.put(name, new float[] {
            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
        });
        ids.put(name, ImGui.getItemID());
    }

    private static float gap(GuideBlock block, @Nullable GuideBlock previous) {
        if (block instanceof Heading heading) {
            return heading.level() == 2 ? GeodataWidgets.SECTION : GeodataWidgets.BLOCK + 4;
        }
        if (previous instanceof Heading || block instanceof Code code && code.within() != Within.BODY) {
            return GeodataWidgets.ROW;
        }
        if (previous instanceof Code code
                && (code.within() == Within.STEPS && block instanceof Steps
                        || code.within() == Within.BULLETS && block instanceof Bullets)) {
            return GeodataWidgets.ROW;
        }
        return GeodataWidgets.BLOCK;
    }

    private static CodeText codeText(Code code, ImFont mono) {
        var widest = 0f;
        for (var line : code.lines()) {
            widest = Math.max(widest, mono.calcTextSizeAX(GuideText.SIZE, Float.MAX_VALUE, 0, line));
        }
        return new CodeText(new ImString(String.join("\n", code.lines())), widest);
    }

    private record CodeText(ImString text, float width) {}

    private static String count(int count, String word) {
        return count + " " + word + (count == 1 ? "" : "s");
    }

    private static float rowHeight() {
        return ImGui.getFontSize() * 2 + 10;
    }

    private static String sectionTitle(GuideLibrary library, GuideArticle article) {
        var section = library.findSection(article.section());
        return section == null ? article.section() : section.title();
    }

    private static void highlighted(float x, float y, float room, String line, List<int[]> matches) {
        var shown = HudWidgets.truncateToWidth(line, room);
        var end = shown.endsWith("…") && !line.endsWith("…") ? shown.length() - 1 : shown.length();
        var draw = ImGui.getWindowDrawList();
        var at = 0;
        var penX = x;
        for (var range : matches) {
            if (range[0] >= end) {
                break;
            }
            var stop = Math.min(range[1], end);
            var before = shown.substring(at, range[0]);
            draw.addText(penX, y, HudWidgets.MUTED, before);
            penX += ImGui.calcTextSizeX(before);
            var match = shown.substring(range[0], stop);
            draw.addText(penX, y, HudWidgets.ACCENT, match);
            penX += ImGui.calcTextSizeX(match);
            at = stop;
        }
        draw.addText(penX, y, HudWidgets.MUTED, shown.substring(at));
    }

    private static boolean crumb(String id, String text, float x, float y) {
        ImGui.setCursorScreenPos(x, y);
        var pressed = ImGui.invisibleButton("##" + id, ImGui.calcTextSizeX(text), ImGui.getFontSize());
        var hovered = ImGui.isItemHovered();
        if (hovered) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }
        ImGui.getWindowDrawList().addText(x, y, hovered ? HudWidgets.TEXT : GeodataWidgets.FAINT, text);
        return pressed;
    }
}

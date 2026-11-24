package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.spawn.SourceSpan;
import com.rost.cruma.core.spawn.SpawnTerritory;
import com.rost.cruma.core.spawn.TerritoryPoint;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.editing.ClientInstallPanel;
import com.rost.cruma.viewer.spawn.AreaStyle;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import com.rost.cruma.viewer.ui.BatchWidgets.Action;
import com.rost.cruma.viewer.ui.FindingWidgets.FindingList;
import com.rost.cruma.viewer.ui.FindingWidgets.FindingList.Group;
import com.rost.cruma.viewer.ui.FindingWidgets.Severity;
import com.rost.cruma.viewer.ui.GeodataWidgets.FooterButton;
import com.rost.cruma.viewer.ui.GeodataWidgets.Toggle;
import com.rost.cruma.viewer.ui.GeodataWidgets.ToolTab;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.KeyTimeline.Fold;
import com.rost.cruma.viewer.ui.KeyTimeline.Lane;
import com.rost.cruma.viewer.ui.KeyTimeline.Mark;
import com.rost.cruma.viewer.ui.KeyTimeline.Span;
import com.rost.cruma.viewer.ui.NpcPanelWidgets.ItemKind;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.MapPoint;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.Part;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.Share;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.Slice;
import com.rost.cruma.viewer.ui.TrackView.Bar;
import com.rost.cruma.viewer.ui.TrackView.Track;
import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class UiGallery {
    private static final float COLUMN = 420;
    private static final float GUTTER = 36;
    private static final float GUIDE_BODY = GuideWindow.BODY;
    private final ImInt number = new ImInt(20);
    private final ImInt x = new ImInt(82100);
    private final ImInt y = new ImInt(147500);
    private final ImString respawn = new ImString("1min", 32);
    private final int[] level = {18};
    private final int[] castingSpeed = {333};
    private final int[] distance = {900};
    private final float[] sizeFactor = {1.35f};
    private final float[] moveSpeed = {1500};
    private final int[] hue = {40};
    private final float[] delay = {.25f};
    private final TrackView tracks = new TrackView("gallery-tracks");
    private final KeyTimeline keyTimeline = new KeyTimeline("gallery-keys");
    private final float[] tint = {1f, .8f, .6f};
    private final ImFloat grid = new ImFloat(16);
    private final ImFloat angle = new ImFloat(15);
    private final ImFloat scale = new ImFloat(.1f);
    private final ImFloat from = new ImFloat(0);
    private final ImFloat to = new ImFloat(500);
    private final boolean[] snaps = {true, true, false};
    private final ImString place = new ImString("82100, 147500, -3400", 64);
    private final ImString path = new ImString("~/geodata/cases/giran-steps.json", 256);
    private final ImString listFilter = new ImString(64);
    private String listed = "default_maker";
    private final ExportScaleChoice exportScale = new ExportScaleChoice(ExportSettings.session());
    private final int[] segment = {1};
    private boolean switchedOn = true;
    private final ImString settingsSearch = new ImString(64);
    private final ImString settingFolder = new ImString("~/Servers/hf/geodata", 256);
    private boolean settingOn;
    private int settingTime;
    private boolean settingFoldOpen;
    private int toolTab;
    private int brushTile;
    private int layerTile = 2;
    private final float[] slider = {.6f};
    private final float[] hueTrack = {20};
    private final float[] dialYaw = {45};
    private final float[] dialPitch = {-40};
    private final float[] rangeLow = {.25f};
    private final float[] rangeHigh = {.7f};
    private final LogSliderField zoom = new LogSliderField(
            "gallery-zoom",
            "Zoom",
            10,
            1000,
            "%",
            "A number in a bar: its label",
            "Drag on a log scale",
            "Type an exact value");
    private final float[] zoomPercent = {250};
    private boolean galleryFilesOpen;
    private final ClientInstallPanel galleryInstall = new ClientInstallPanel("Install into the client", "");
    private final boolean[] groupsOpen = {true, false};
    private final boolean[] sectionsOpen = {true, false};
    private boolean zoneStatesOpen;
    private final List<float[]> hintAreas = new ArrayList<>();

    private final ImString factor = new ImString("2", 16);
    private final ImString[] transform = {
        new ImString("82646.96", 32),
        new ImString("148952.3", 32),
        new ImString("-3475.54", 32),
        new ImString("0", 32),
        new ImString("90", 32),
        new ImString("0", 32),
        new ImString("1", 32),
        new ImString("1", 32),
        new ImString("1", 32),
        new ImString("1.25", 32)
    };

    private final FindingList findings = new FindingList(2);

    public void draw(@Nullable FontManager fonts, float width, float height) {
        HudTheme.push(14, 12);
        ImGui.setNextWindowPos(0, 0);
        ImGui.setNextWindowSize(width, height);
        ImGui.setNextWindowBgAlpha(1f);
        ImGui.begin("##ui-gallery", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoSavedSettings);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8, 4);
        heading(fonts, 28, "Cruma interface");
        ImGui.textColored(
                HudWidgets.MUTED, "Tokens, icons and components of the design system, drawn by the panels' own code.");
        GeodataWidgets.space(GeodataWidgets.SECTION);
        drawColumn("tokens", () -> {
            tokens(fonts);
            drawFindings();
            drawGroupedList();
            drawAgentRows(fonts);
            drawGuide(fonts);
        });
        ImGui.sameLine(0, GUTTER);
        drawColumn("icons", this::drawIcons);
        ImGui.sameLine(0, GUTTER);
        drawColumn("controls", () -> drawControls(fonts));
        ImGui.popStyleVar();
        ImGui.end();
        HudTheme.pop();
    }

    private void tokens(@Nullable FontManager fonts) {
        GeodataWidgets.section("COLORS");
        drawSwatches(
                new String[] {"TEXT", "MUTED", "FAINT", "ACCENT"},
                new int[] {HudWidgets.TEXT, HudWidgets.MUTED, GeodataWidgets.FAINT, HudWidgets.ACCENT});
        drawSwatches(
                new String[] {"OPEN", "WARNING", "BLOCKED", "BORDER"},
                new int[] {GeodataWidgets.OPEN, GeodataWidgets.WARNING, GeodataWidgets.BLOCKED, HudWidgets.BORDER});
        drawSwatches(new String[] {"BACKGROUND", "CARD", "DIVIDER", "HOVER"}, new int[] {
            HudWidgets.BACKGROUND, GeodataWidgets.CARD, GeodataWidgets.DIVIDER, ImColor.rgb(34, 51, 63)
        });
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(HudWidgets.MUTED, "NPC kinds");
        var kinds = SpawnStyle.values();
        var names = new String[kinds.length];
        var colors = new int[kinds.length];
        for (var i = 0; i < kinds.length; i++) {
            names[i] = kinds[i].name();
            colors[i] = kinds[i].getImguiColor(1f);
        }
        drawSwatches(names, colors);

        GeodataWidgets.section("TYPE");
        heading(fonts, 18, "Panel title · Inter SemiBold 18");
        heading(fonts, 15, "Card title · Inter SemiBold 15");
        ImGui.textUnformatted("Body · Inter 14, TEXT");
        ImGui.textColored(HudWidgets.MUTED, "Detail · Inter 14, MUTED");
        ImGui.textColored(GeodataWidgets.FAINT, "Hint · Inter 14, FAINT");
        ImGui.textColored(HudWidgets.MUTED, "SECTION TITLE · UPPER CASE, MUTED");

        GeodataWidgets.section("SPACING");
        drawSpacing("BLOCK", GeodataWidgets.BLOCK, "between blocks: cards, fields, lists");
        drawSpacing("SECTION", GeodataWidgets.SECTION, "above a section title");
        drawSpacing("ROW", GeodataWidgets.ROW, "between rows of one section");
        drawSpacing("MARGIN", 12, "between HUD panels and the window edge");

        GeodataWidgets.section("SIZES AND RADII");
        GeodataWidgets.value("Control", "28 high, radius 6");
        GeodataWidgets.value("Card", "radius 8, CARD fill");
        GeodataWidgets.value("Panel", "radius 9, BACKGROUND");
        GeodataWidgets.value("Icon", "18 in controls, 22 on the rail");
        GeodataWidgets.value("Stroke", "1.6 per 24 units");
        GeodataWidgets.value("Scrollbar", "6 wide, radius 3");
    }

    private boolean toggle(Toggle result, int index) {
        if (result.toggled()) {
            snaps[index] = !snaps[index];
        }
        return result.toggled();
    }

    private void drawIcons() {
        GeodataWidgets.section("ICONS · LUCIDE");
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var cell = COLUMN / 5;
        var rowHeight = 62f;
        var glyphs = Glyph.values();
        for (var i = 0; i < glyphs.length; i++) {
            var left = x + (i % 5) * cell;
            var top = y + (i / 5) * rowHeight;
            HudWidgets.glyph(draw, glyphs[i], left + (cell - 22) * .5f, top + 4, 22, HudWidgets.TEXT);
            var name = glyphs[i].name().toLowerCase(Locale.ROOT);
            draw.addText(left + (cell - ImGui.calcTextSizeX(name)) * .5f, top + 34, GeodataWidgets.FAINT, name);
        }
        ImGui.dummy(COLUMN, ((glyphs.length + 4) / 5) * rowHeight);

        GeodataWidgets.section("TOOL RAIL");
        var tools = new Glyph[] {
            Glyph.SLIDERS,
            Glyph.BOX,
            Glyph.PROFILE,
            Glyph.GEODATA,
            Glyph.NPC,
            Glyph.AREA,
            Glyph.CARD,
            Glyph.SWITCH,
            Glyph.BOT
        };
        for (var i = 0; i < tools.length; i++) {
            if (i > 0) {
                ImGui.sameLine(0, 4);
            }
            HudWidgets.railButton(
                    "gallery-rail" + i,
                    tools[i],
                    ToolWindows.RAIL_WIDTH - 8,
                    ToolWindows.BUTTON_HEIGHT,
                    ToolWindows.ICON_SIZE,
                    i == 4);
        }

        GeodataWidgets.section("HINT STRIP · KEYS FOR WHAT CAN BE DONE NOW");
        var stripTop = ImGui.getCursorScreenPosY();
        var stripLeft = ImGui.getCursorScreenPosX() - 12;
        var items = new ArrayList<String[]>();
        items.add(new String[] {"Click", "select"});
        items.add(new String[] {"Drag", "move"});
        items.add(new String[] {"Esc", "deselect"});
        items.add(new String[] {"F1", "all keys"});
        GeodataWidgets.hintStrip(
                ImGui.getWindowDrawList(),
                new Viewport(stripLeft, stripTop, stripLeft + COLUMN + 24, stripTop + 80),
                "TOOL",
                items,
                hintAreas);
        var stripBottom = stripTop;
        for (var area : hintAreas) {
            stripBottom = Math.max(stripBottom, area[3]);
        }
        ImGui.dummy(COLUMN, stripBottom - stripTop);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var tagTop = ImGui.getCursorScreenPosY();
        var tagRight = ImGui.getCursorScreenPosX() + COLUMN;
        var tagLeft = GeodataWidgets.viewTag(
                ImGui.getWindowDrawList(), tagRight, tagTop, "Shot 2 · 4.37 s", GeodataWidgets.MUTED);
        GeodataWidgets.viewTag(
                ImGui.getWindowDrawList(),
                tagLeft - GeodataWidgets.ROW,
                tagTop,
                "Free camera · K keeps this view",
                GeodataWidgets.WARNING);
        ImGui.dummy(COLUMN, ImGui.getFontSize() + 14);
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        GeodataWidgets.stepsCard(
                "gallery-steps",
                Glyph.PENCIL,
                "Edit this map",
                List.of("1 · Click an object to move it.", "2 · T sculpts the ground, B paints it, I adds lights."),
                "Got it",
                "All keys (F1)",
                COLUMN);

        GeodataWidgets.section("DATA");
        SpawnPanelWidgets.composition(
                "gallery-kinds",
                List.of(
                        new Share(SpawnStyle.MONSTER, 1800),
                        new Share(SpawnStyle.CITIZEN, 420),
                        new Share(SpawnStyle.GUARD, 240),
                        new Share(SpawnStyle.BOSS, 60)),
                EnumSet.noneOf(SpawnStyle.class));
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var areaCounts = new int[] {136, 37, 5, 410, 36, 1205, 260, 26, 16, 80, 18, 41, 12};
        var areaSlices = new ArrayList<Slice<AreaStyle>>();
        for (var style : AreaStyle.values()) {
            areaSlices.add(new Slice<>(style, style.label, style.label, style.argb, areaCounts[style.ordinal()]));
        }
        areaSlices.sort(Comparator.comparingLong(Slice<AreaStyle>::count).reversed());
        SpawnPanelWidgets.compositionOf("gallery-areas", areaSlices, EnumSet.of(AreaStyle.WATER), "in the world");
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        SpawnPanelWidgets.fill(List.of(new Part(SpawnStyle.MONSTER.argb, 12), new Part(SpawnStyle.BOSS.argb, 1)), 15);
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        NpcPanelWidgets.iconRow(
                "gallery-item",
                0,
                Glyph.BOX,
                "Adena",
                HudWidgets.TEXT,
                "30–42 · 100% of the group",
                "70%",
                HudWidgets.TEXT,
                false);
        NpcPanelWidgets.iconRow(
                "gallery-skill",
                0,
                Glyph.SPARKLES,
                "Race Lv 2",
                HudWidgets.TEXT,
                "s_npc_prop_race_humanoid · passive",
                "",
                0,
                true);
        NpcPanelWidgets.iconRow(
                "gallery-item-open",
                0,
                Glyph.BOX,
                "Thread",
                HudWidgets.TEXT,
                "1 · 40% of the group · 1 in 37 kills",
                "2.68%",
                HudWidgets.TEXT,
                false,
                true);
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.thumbnailRow("gallery-mesh", 0, Glyph.BOX, "Pillar", "Meshes · 2 slots", 64, false);
        GeodataWidgets.thumbnailRow("gallery-material", 0, Glyph.LAYERS, "Stone", "Materials · Texture", 24, true);
        GeodataWidgets.thumbnailRow(
                "gallery-session",
                GeodataWidgets.Picture.of(0, Glyph.GLOBE),
                "Giran Castle Town",
                "HF-273 · 9 tiles · Viewer",
                32,
                false,
                false,
                COLUMN);
        GeodataWidgets.thumbnailRow(
                "gallery-session-gone",
                GeodataWidgets.Picture.of(0, Glyph.SPARKLES),
                "Effects",
                "C1 · no map",
                32,
                false,
                true,
                COLUMN);
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.pushID("gallery-lines");
        ImGui.beginChild(
                "##lines",
                COLUMN,
                4 * GeodataWidgets.LINE_ROW + 3 * ImGui.getStyle().getItemSpacingY());
        GeodataWidgets.lineRow("client", null, "HF-273", "203 tiles", GeodataWidgets.LineLook.SELECTED, false);
        GeodataWidgets.lineRow(
                "result", Glyph.PLACE, "Giran Castle Town", "22_22", GeodataWidgets.LineLook.MARKED, false);
        GeodataWidgets.lineRow(
                "lacking",
                Glyph.PLACE,
                "Town of Schuttgart",
                "not in this client",
                GeodataWidgets.LineLook.FAINT,
                false);
        GeodataWidgets.lineRow("menu", Glyph.PLACE, "Other maps", "", GeodataWidgets.LineLook.PLAIN, true);
        ImGui.endChild();
        ImGui.popID();
        GeodataWidgets.space(GeodataWidgets.ROW);
        NpcPanelWidgets.dropBar(
                "gallery-drop-bar",
                COLUMN,
                62,
                new double[] {50, 30, 20},
                new int[] {ItemKind.ADENA.color, ItemKind.WEAPON.color, ItemKind.OTHER.color},
                new boolean[] {false, false, true});
        GeodataWidgets.space(GeodataWidgets.ROW);
        NpcPanelWidgets.kindLegend(COLUMN);
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var zone = new SpawnTerritory(
                0,
                "gallery_zone",
                false,
                -1,
                List.of(
                        new TerritoryPoint(-600, -400, -100, 100), new TerritoryPoint(500, -500, -100, 100),
                        new TerritoryPoint(700, 300, -100, 100), new TerritoryPoint(-200, 600, -100, 100)),
                -1,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE);
        SpawnPanelWidgets.zoneMap(
                "gallery-map",
                List.of(zone),
                List.of(),
                SpawnStyle.MONSTER.argb,
                List.of(
                        new MapPoint(-100, 0, 0, SpawnStyle.MONSTER.argb, false, 0, "rabbit"),
                        new MapPoint(200, 100, 0, SpawnStyle.MONSTER.argb, false, 0, "rabbit"),
                        new MapPoint(300, -200, 0, SpawnStyle.BOSS.argb, true, 1, "queen")),
                1,
                150,
                9999,
                9999,
                0);
        drawInspectorParts();
    }

    private void drawInspectorParts() {
        GeodataWidgets.section("ICON BUTTONS · FIELDS WITH THEIR AXES");
        var icons = new Glyph[] {
            Glyph.FOCUS,
            Glyph.LOCK,
            Glyph.EYE_OFF,
            Glyph.COPY,
            Glyph.TRASH,
            Glyph.FILTER,
            Glyph.DROP,
            Glyph.BOX,
            Glyph.MORE
        };
        for (var i = 0; i < icons.length; i++) {
            if (i > 0) {
                ImGui.sameLine(0, i == 3 ? GeodataWidgets.BLOCK : 4);
            }
            HudWidgets.iconButton(
                    "gallery-icon-button" + i, icons[i], i == 1, i == 4 ? GeodataWidgets.BLOCKED : HudWidgets.TEXT);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var rows = new String[] {"Location", "Rotation", "Scale"};
        var axes = new String[][] {{"X", "Y", "Z"}, {"P", "Y", "R"}, {"U", "X", "Y", "Z"}};
        var field = 0;
        for (var row = 0; row < rows.length; row++) {
            if (row > 0) {
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
            GeodataWidgets.rowLabel(rows[row], 64);
            var count = axes[row].length;
            var spacing = ImGui.getStyle().getItemSpacingX();
            var width = (COLUMN - 64 - spacing * (count - 1)) / count;
            for (var axis = 0; axis < count; axis++) {
                if (axis > 0) {
                    ImGui.sameLine();
                }
                GeodataWidgets.axisField(axes[row][axis], "##gallery-axis" + field, transform[field], width);
                field++;
            }
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.thumbnailLine("gallery-slot", 0, Glyph.LAYERS, "0 · Stone", "Mesh default", 24, false, COLUMN);

        GeodataWidgets.section("MESSAGES OVER THE VIEW");
        HudWidgets.toast(
                "gallery-toast-note",
                "Moved to 21_18",
                GeodataWidgets.OPEN,
                null,
                null,
                HudWidgets.toastWidth("Moved to 21_18", null, COLUMN));
        GeodataWidgets.space(GeodataWidgets.ROW);
        var error = "No ground below the selection is in view. Turn the camera and press End again.";
        HudWidgets.toast(
                "gallery-toast-error",
                error,
                GeodataWidgets.BLOCKED,
                null,
                null,
                HudWidgets.toastWidth(error, null, COLUMN));
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.toast(
                "gallery-toast-action",
                "Prefab saved: Pillar.json",
                GeodataWidgets.OPEN,
                Glyph.FOLDER,
                "Show folder",
                HudWidgets.toastWidth("Prefab saved: Pillar.json", "Show folder", COLUMN));
    }

    private void drawFindings() {
        GeodataWidgets.section("CHECKS · FINDINGS");
        findings.groupCheckboxes(
                "gallery-findings",
                List.of(
                        new Group(
                                "floating",
                                "Zone floats above the ground",
                                Severity.WARNING,
                                3,
                                "The zone's bottom is above the ground under it."),
                        new Group(
                                "unknown",
                                "NPC not in npcdata.txt",
                                Severity.ERROR,
                                1,
                                "The server cannot spawn an NPC it does not know.")));
        GeodataWidgets.space(GeodataWidgets.ROW);
        findings.drawPager("gallery-findings", 4);
        GeodataWidgets.space(GeodataWidgets.ROW);
        FindingWidgets.card(
                "gallery-finding",
                Severity.WARNING,
                "One-way passages · 8 edges",
                "83200, 148100 · Characters walk east into the neighbor but find no way back",
                List.of("Open both ways", "Block both ways", "Fix all 3"),
                true);
        GeodataWidgets.space(GeodataWidgets.ROW);
        FindingWidgets.card(
                "gallery-finding-error",
                Severity.ERROR,
                "NPC not in npcdata.txt",
                "maker cruma_marsh_01 · Entry marsh_quen names an NPC npcdata.txt lacks",
                List.of("Remove entry"),
                false);
        GeodataWidgets.space(GeodataWidgets.ROW);
        FindingWidgets.card(
                "gallery-finding-placeless",
                Severity.ERROR,
                "Package Engine is missing",
                "Needed by 20_18 → Engine.Level. Add the package under Advanced, or undo the change that needs it.",
                List.of(),
                false,
                false);
    }

    private void drawAgentRows(@Nullable FontManager fonts) {
        GeodataWidgets.section("A COMMAND TO COPY · ROWS WITH A STATE");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.commandField(
                "gallery-command",
                "Claude Code",
                "claude mcp add --scope user cruma -- \"/Applications/Cruma.app/Contents/MacOS/Cruma\" mcp",
                fonts == null ? null : fonts.getMonoFont());
        GeodataWidgets.space(GeodataWidgets.ROW);
        var width = ImGui.getContentRegionAvailX();
        var height = SpawnPanelWidgets.listRowHeight();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.dummy(width, height);
        SpawnPanelWidgets.statusRow(
                ImGui.getWindowDrawList(),
                x,
                y,
                width,
                GeodataWidgets.OPEN,
                "view_look",
                "Look",
                GeodataWidgets.MUTED,
                "12:04:33");
        y = ImGui.getCursorScreenPosY();
        ImGui.dummy(width, height);
        SpawnPanelWidgets.statusRow(
                ImGui.getWindowDrawList(),
                x,
                y,
                width,
                GeodataWidgets.BLOCKED,
                "geo_edit",
                "geo_edit needs Edit access",
                GeodataWidgets.BLOCKED,
                "12:04:40");
    }

    private void drawGuide(@Nullable FontManager fonts) {
        GeodataWidgets.section("GUIDE · HELP MARK, LINK, A PANEL'S \"?\"");
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(HudWidgets.MUTED, "Server format");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/l2off");
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.guideLink("What geodata is and where to get it", "geodata/what-is-geodata");
        GeodataWidgets.space(GeodataWidgets.ROW);
        heading(fonts, 18, "Geodata");
        ImGui.sameLine(COLUMN - 24 - 4 - 24);
        HudWidgets.guideButton("gallery-guide-tool", "geodata/what-is-geodata");
        ImGui.sameLine(COLUMN - 24);
        HudWidgets.button("gallery-guide-close", Glyph.CLOSE, "", 24, 24, false, false);
        if (fonts != null) {
            GeodataWidgets.section("GUIDE · A DIALOG'S \"?\"");
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, GeodataWidgets.DIALOG_SIDE, GeodataWidgets.ROW);
            ImGui.beginChild(
                    "##gallery-dialog-title",
                    COLUMN,
                    GeodataWidgets.DIALOG_TITLE + 2 * GeodataWidgets.ROW,
                    ImGuiChildFlags.Borders | ImGuiChildFlags.AlwaysUseWindowPadding,
                    ImGuiWindowFlags.NoScrollbar);
            GeodataWidgets.dialogTitle(fonts, "Export location", "gallery-dialog-close", "export/export-a-location");
            ImGui.dummy(0, 0);
            ImGui.endChild();
            ImGui.popStyleVar();
        }
        GeodataWidgets.section("GUIDE · DIAGRAMS OF THE ARTICLES");
        if (fonts != null) {
            GuideDiagrams.useFonts(fonts);
        }
        var width = GUIDE_BODY;
        for (var entry : GuideDiagrams.getAll().entrySet()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var height = (float) Math.ceil(entry.getValue().getHeight(width));
            entry.getValue().draw(ImGui.getWindowDrawList(), Math.round(x), Math.round(y), width);
            ImGui.setCursorScreenPos(x, y + height);
            ImGui.dummy(width, 0);
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.textColored(GeodataWidgets.FAINT, "diagram:" + entry.getKey());
        }
    }

    private void drawGroupedList() {
        GeodataWidgets.section("GROUPED LIST · HEADERS THAT FOLD");
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (SpawnPanelWidgets.sectionRow("gallery-section-castles", sectionsOpen[1], "CASTLES", "1")) {
            sectionsOpen[1] = !sectionsOpen[1];
        }
        GeodataWidgets.space(GeodataWidgets.SECTION);
        if (SpawnPanelWidgets.sectionRow(
                "gallery-section-zone-states", zoneStatesOpen, "ZONE STATES", "2", "terms/zone-state")) {
            zoneStatesOpen = !zoneStatesOpen;
        }
        GeodataWidgets.space(GeodataWidgets.SECTION);
        if (SpawnPanelWidgets.sectionRow("gallery-section-events", sectionsOpen[0], "EVENTS", "11")) {
            sectionsOpen[0] = !sectionsOpen[0];
        }
        if (!sectionsOpen[0]) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        if (SpawnPanelWidgets.groupRow(
                "gallery-group-near", groupsOpen[0], "22_22", "2 events · 1 on", "3,493 away", "", rowHeight)) {
            groupsOpen[0] = !groupsOpen[0];
        }
        if (groupsOpen[0]) {
            var titles = new String[] {"22_22_eventzone_effect_on", "Event 22226294"};
            var details = new String[] {"22226292 · 22_22 · 3 effects", "22_22 · 1 effect"};
            var values = new String[] {"3,493 away", "3,503 away"};
            for (var row = 0; row < titles.length; row++) {
                var x = ImGui.getCursorScreenPosX();
                var y = ImGui.getCursorScreenPosY();
                var width = ImGui.getContentRegionAvailX();
                ImGui.invisibleButton("gallery-group-row" + row, width, rowHeight);
                var draw = ImGui.getWindowDrawList();
                SpawnPanelWidgets.checkBox(draw, x + 4, y + (rowHeight - 16) * .5f, row == 1);
                SpawnPanelWidgets.listRow(
                        draw,
                        x + 22,
                        y,
                        width - 22,
                        row == 1 ? SpawnStyle.EVENT.argb : 0xFF5C6E7E,
                        titles[row],
                        details[row],
                        values[row],
                        "");
            }
        }
        if (SpawnPanelWidgets.groupRow(
                "gallery-group-far", groupsOpen[1], "16_20", "9 events · none on", "136k away", "", rowHeight)) {
            groupsOpen[1] = !groupsOpen[1];
        }
    }

    private void drawControls(@Nullable FontManager fonts) {
        GeodataWidgets.section("BUTTONS");
        HudWidgets.button("gallery-plain", null, "Plain", 120, HudWidgets.CONTROL_HEIGHT, false, true);
        ImGui.sameLine();
        HudWidgets.button("gallery-primary", null, "Primary", 120, HudWidgets.CONTROL_HEIGHT, true, true);
        ImGui.sameLine();
        HudWidgets.button("gallery-icon", Glyph.FOCUS, "", 28, 28, false, false);
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.keyButton("gallery-key", Glyph.PLAY, "Open", Glyph.ENTER, 122, 34, true, true);
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.actionButton("gallery-action", Glyph.PLUS, "Full-width action", -1, false);
        GeodataWidgets.actionButton("gallery-action-primary", Glyph.FOLDER, "Primary action", -1, true);
        GeodataWidgets.rowButton("Keep", 0, 2);
        GeodataWidgets.rowButton("Delete", 1, 2);

        GeodataWidgets.section("MENU");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var menuHeight = 5 * HudWidgets.MENU_ROW + 2 * (2 * HudWidgets.MENU_PADDING + 1);
        ImGui.beginChild("##gallery-menu", 248, menuHeight, false, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 0);
        HudWidgets.menuRow("gallery-menu-guide", Glyph.BOOK_OPEN, "Guide", "Shift+F1");
        HudWidgets.menuRow("gallery-menu-help", Glyph.KEYBOARD, "Controls & shortcuts", "F1");
        HudWidgets.menuRule();
        HudWidgets.menuRow("gallery-menu-export", Glyph.FILE, "Export here…", "");
        HudWidgets.menuRow("gallery-menu-npc", Glyph.CARD, "Export NPC…", "");
        HudWidgets.menuRule();
        HudWidgets.menuRow("gallery-menu-main", Glyph.LOGO, "Main menu", "");
        HudWidgets.menuRow("gallery-menu-off", Glyph.COPY, "Paste", "", false);
        ImGui.popStyleVar();
        ImGui.endChild();
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.beginChild("##gallery-kind-menu", 300, 4 * HudWidgets.MENU_ROW, false, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 0);
        HudWidgets.menuDotRow(
                "gallery-kind-peace", AreaStyle.PEACE.getImguiColor(1f), "Peace zone", "nobody can fight here");
        HudWidgets.menuDotRow("gallery-kind-water", AreaStyle.WATER.getImguiColor(1f), "Water", "characters swim here");
        HudWidgets.menuNote("Not in this file");
        HudWidgets.menuDotRow(
                "gallery-kind-airport", AreaStyle.OTHER.getImguiColor(1f), "Airport", "no settings of its own", true);
        ImGui.popStyleVar();
        ImGui.endChild();

        GeodataWidgets.section("TOGGLES AND CHOICES");
        var chipWidth = (ImGui.getContentRegionAvailX() - 16) / 3;
        GeodataWidgets.chip("gallery-chip-on", Glyph.EYE, "Show", "N", true, chipWidth);
        ImGui.sameLine();
        GeodataWidgets.chip("gallery-chip-off", Glyph.SPARKLES, "Events", false, chipWidth);
        ImGui.sameLine();
        GeodataWidgets.chip("gallery-chip-view", Glyph.SLIDERS, "View", false, chipWidth);
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.alignTextToFramePadding();
        ImGui.textColored(HudWidgets.MUTED, "Size presets");
        for (var index = 0; index < 3; index++) {
            ImGui.sameLine();
            GeodataWidgets.chip(
                    "gallery-chip-small" + index, null, "SML".substring(index, index + 1), "", index == 1, 24, 18);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var picked =
                GeodataWidgets.segmented("gallery-segment", new String[] {"Random", "Fixed", "Weighted"}, segment[0]);
        if (picked >= 0) {
            segment[0] = picked;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        switchedOn = HudWidgets.switchRow("A setting in a row", switchedOn, true, "The whole row is the button.");

        drawSettingRows();

        GeodataWidgets.section("TOOL TABS · A PANEL'S TOOLS WITH THEIR KEYS");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var tab = GeodataWidgets.toolTabs(
                "gallery-tool-tabs",
                List.of(
                        new ToolTab("Select", Glyph.CURSOR, "Q", ""),
                        new ToolTab("Place", Glyph.PLUS, "P", ""),
                        new ToolTab("Sculpt", Glyph.TERRAIN, "T", ""),
                        new ToolTab("Paint", Glyph.PENCIL, "B", "")),
                toolTab);
        if (tab >= 0) {
            toolTab = tab;
        }

        GeodataWidgets.section("TOOL TILES · BRUSHES · A GRID OF PICTURES");
        var brushes = new Glyph[] {
            Glyph.TERRAIN,
            Glyph.EQUAL,
            Glyph.WAVES,
            Glyph.SLOPE,
            Glyph.SHUFFLE,
            Glyph.LEVEL,
            Glyph.LASSO,
            Glyph.CIRCLE_OFF,
            Glyph.TRIANGLE
        };
        var brushNames =
                new String[] {"Raise", "Flatten", "Smooth", "Ramp", "Noise", "Set height", "Select", "Holes", "Edges"};
        var brush = GeodataWidgets.toolTiles(
                "gallery-tool-tiles", brushes, brushNames, brushTile, 6, ImGui.getContentRegionAvailX());
        if (brush >= 0) {
            brushTile = brush;
        }
        ImGui.textColored(GeodataWidgets.MUTED, brushNames[brushTile] + " · Shift lowers");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var locked = new boolean[] {true, false, false, false, false, false, false, true};
        var layer = GeodataWidgets.pictureGrid(
                "gallery-picture-grid",
                new long[8],
                Glyph.LAYERS,
                locked,
                new String[] {
                    "0 · Base", "1 · Grass", "2 · Rock", "3 · Sand", "4 · Mud", "5 · Snow", "6 · Moss", "7 · Road"
                },
                layerTile,
                40,
                ImGui.getContentRegionAvailX());
        if (layer >= 0) {
            layerTile = layer;
        }

        GeodataWidgets.section("MOTION · A PART THAT OPENS TURNS ITS CHEVRON");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var turning = ImGui.getWindowDrawList();
        var left = ImGui.getCursorScreenPosX();
        var top = ImGui.getCursorScreenPosY();
        var turns = new float[] {0, .5f, 1};
        var names = new String[] {"Closed", "Opening", "Open"};
        for (var index = 0; index < turns.length; index++) {
            var x = left + index * 110;
            HudWidgets.chevron(turning, x + 8, top + 10, turns[index], HudWidgets.MUTED, 1.4f);
            turning.addText(x + 22, top + 10 - ImGui.getFontSize() * .5f - 1, HudWidgets.MUTED, names[index]);
        }
        ImGui.setCursorScreenPos(left, top + 20);
        ImGui.dummy(0, 0);

        GeodataWidgets.section("FIELDS");
        GeodataWidgets.integer("Max NPC", number, 1);
        GeodataWidgets.value("Zones", "giran_square");
        GeodataWidgets.value("Spawns", "during siege", SpawnStyle.EVENT.getImguiColor(1f));
        HudWidgets.sliderFloat("Opacity", slider, 0, 1, "%.2f");
        GeodataWidgets.space(GeodataWidgets.ROW);
        zoom.draw(zoomPercent, ImGui.calcTextSizeX("Zoom"));
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.rangeSlider("gallery-range", rangeLow, rangeHigh, ImGui.getContentRegionAvailX());
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.foldButton("gallery-fold", "Front · Unlit", Glyph.CHEVRON_RIGHT, 124, 28, false);
        ImGui.sameLine(0, GeodataWidgets.ROW);
        HudWidgets.textButton("gallery-text", "73 FPS", HudWidgets.fpsColor(73), 64, 28);
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.timeline("gallery-timeline", 1.2f, 3f, new float[] {.8f, 1.2f}, ImGui.getContentRegionAvailX());
        GeodataWidgets.space(GeodataWidgets.ROW);
        tracks.draw(
                List.of(
                        new Track(
                                "Cast",
                                List.of(new Bar(
                                        "cast",
                                        0f,
                                        1.4f,
                                        "e_u071_a",
                                        "A bar: when it starts, and how long it lasts",
                                        true,
                                        false))),
                        new Track(
                                "Shot",
                                List.of(new Bar("shot", .8f, 0f, "a_u050_a", "A bar of unknown length", false, false))),
                        new Track(
                                "Hit",
                                List.of(
                                        new Bar("hit", 1.2f, 1.2f, "at_shock_blast_ta", "A bar", false, false),
                                        new Bar(
                                                "shake",
                                                1.45f,
                                                0f,
                                                "z_dummy_quake_1s (missing)",
                                                "A bar the client has nothing for",
                                                false,
                                                true)))),
                3f,
                1.2f,
                ImGui.getContentRegionAvailX());
        GeodataWidgets.space(GeodataWidgets.ROW);
        keyTimeline.gutter(112);
        keyTimeline.draw(
                List.of(
                        new Lane(
                                "Camera",
                                "Camera",
                                List.of(
                                        new Mark("0", 0, false, true, "A key"),
                                        new Mark("1", 1.4, true, true, "The chosen key"),
                                        new Mark("2", 2.3, false, false, "A key whose time something else sets"),
                                        Mark.quiet("3", 3),
                                        new Mark("4", 3.5, false, true, "A key")),
                                List.of(
                                        new Span(
                                                        0,
                                                        1.4,
                                                        Motion.alpha(HudWidgets.ACCENT, .18f),
                                                        "shot/1",
                                                        "Shot 1 · 0.00 to 1.40 s · 2 keys",
                                                        null,
                                                        false)
                                                .clip("Shot 1"),
                                        new Span(
                                                        2.3,
                                                        3.5,
                                                        Motion.alpha(HudWidgets.ACCENT, .18f),
                                                        "shot/2",
                                                        "Shot 2 · 2.30 to 3.50 s · 3 keys",
                                                        null,
                                                        true)
                                                .clip("Shot 2")),
                                Fold.NONE,
                                false,
                                false),
                        new Lane(
                                "bars",
                                "Bars",
                                List.of(),
                                List.of(
                                        new Span(
                                                .5,
                                                2.8,
                                                ImColor.rgba(164, 180, 195, 70),
                                                "bars/0",
                                                "A span that a press can take",
                                                null,
                                                true),
                                        new Span(
                                                3,
                                                3.6,
                                                ImColor.rgba(164, 180, 195, 60),
                                                "",
                                                "A span that nothing can press",
                                                Glyph.LOCK,
                                                false)),
                                Fold.NONE,
                                false,
                                false,
                                true),
                        new Lane(
                                "closed",
                                "A closed actor",
                                List.of(
                                        new Mark("5", .4, false, true, "An order"),
                                        new Mark("6", 2, false, true, "An order that does nothing").warning()),
                                List.of(
                                        new Span(.4, 3.2, ImColor.rgba(92, 219, 241, 46)),
                                        Span.guess(.8, 2.4, ImColor.rgba(231, 238, 246, 90), "About 1.6 s, a guess")),
                                Fold.CLOSED,
                                true,
                                false),
                        new Lane(
                                "open",
                                "An open actor",
                                List.of(new Mark("7", 1, false, true, "An order")),
                                List.of(new Span(1, 4, ImColor.rgba(92, 219, 241, 46))),
                                Fold.OPEN,
                                true,
                                false),
                        new Lane(
                                "row",
                                "Moves",
                                List.of(new Mark("8", 1, false, true, "An order")),
                                List.of(),
                                Fold.NONE,
                                true,
                                true),
                        new Lane(
                                "voice",
                                "Voice",
                                List.of(
                                        new Mark("9", .4, false, true, "A voice line"),
                                        new Mark("10", 1.2, false, true, "A voice line")),
                                List.of(
                                        new Span(.4, 2.2, ImColor.rgba(164, 180, 195, 46), "Plays 1.8 s").inBand(0, 2),
                                        new Span(1.2, 3, ImColor.rgba(164, 180, 195, 46), "Plays 1.8 s").inBand(1, 2)),
                                Fold.NONE,
                                false,
                                false)),
                4,
                1.8,
                1.0 / 30,
                ImGui.getContentRegionAvailX(),
                KeyTimeline.height(6));

        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.screenPlaces(
                "##gallery-places",
                new float[][] {
                    {.05f, .1f, .25f, .07f},
                    {.37f, .1f, .25f, .07f},
                    {.7f, .1f, .25f, .07f},
                    {.05f, .47f, .25f, .07f},
                    {.37f, .47f, .25f, .07f},
                    {.7f, .47f, .25f, .07f},
                    {.05f, .72f, .25f, .07f},
                    {.37f, .72f, .25f, .07f},
                    {.7f, .72f, .25f, .07f},
                    {.37f, .88f, .25f, .07f}
                },
                new String[] {
                    "Upper left",
                    "Upper center",
                    "Upper right",
                    "Middle left",
                    "Middle center",
                    "Middle right",
                    "Lower left",
                    "Lower center",
                    "Lower right",
                    "Bottom center"
                },
                7,
                240);

        GeodataWidgets.space(GeodataWidgets.ROW);
        var third = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX() * 2) / 3;
        GeodataWidgets.columns(
                () -> GeodataWidgets.compactInt("X", "##gallery-x", x, third, 0),
                () -> GeodataWidgets.compactInt("Y", "##gallery-y", y, third, 0),
                () -> GeodataWidgets.compactText("Respawn", "##gallery-respawn", "30sec", respawn, third));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.columns(
                () -> GeodataWidgets.compactDrag("Level", "##gallery-level", level, third, .1f, 1, 45),
                () -> GeodataWidgets.compactDrag("Casting Spd", "##gallery-speed", castingSpeed, third, 2, 100, 3000),
                () -> GeodataWidgets.compactDrag("Distance", "##gallery-distance", distance, third, 4.5f, 0, 1800));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.columns(
                () -> GeodataWidgets.compactFactor(
                        "Size", "##gallery-size", sizeFactor, third, .1f, 10, GeodataWidgets.WARNING),
                () -> GeodataWidgets.compactDrag(
                        "Hue", "##gallery-hue", hue, third, 1, -180, 180, "%d°", GeodataWidgets.MUTED),
                () -> GeodataWidgets.compactDrag(
                        "Delay", "##gallery-delay", delay, third, .01f, 0, 60, "%.2f s", GeodataWidgets.MUTED));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.columns(
                () -> GeodataWidgets.compactSwatch("Colour", "##gallery-colour", tint, third, GeodataWidgets.MUTED),
                () -> GeodataWidgets.compactRange(
                        "Speed",
                        "##gallery-move-speed",
                        moveSpeed,
                        third,
                        1,
                        20_000,
                        "%.0f u/s",
                        GeodataWidgets.MUTED));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.swatchRow(
                "##gallery-swatches",
                new float[][] {
                    {1, .56f, .37f},
                    {1, .82f, .67f},
                    {1, 1, 1},
                    {.72f, .81f, 1},
                    {1, .28f, .13f},
                    {.59f, .3f, 1},
                    {.43f, 1, .27f}
                },
                new String[] {"Candle", "Lamp", "Daylight", "Moonlight", "Fire", "Magic", "Poison"},
                1);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var hues = new int[7];
        for (var i = 0; i < hues.length; i++) {
            var color = new float[3];
            ImGui.colorConvertHSVtoRGB(new float[] {i / (float) (hues.length - 1), 1, 1}, color);
            hues[i] = ImGui.colorConvertFloat4ToU32(color[0], color[1], color[2], 1);
        }
        HudWidgets.gradientSlider("Hue", hueTrack, 0, 255, "%.0f", 1, hues, "");
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.directionDials(
                "##gallery-dials",
                dialYaw,
                dialPitch,
                30,
                16,
                ImGui.colorConvertFloat4ToU32(1, .82f, .6f, 1),
                ImGui.getContentRegionAvailX());
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.columns(
                () -> toggle(GeodataWidgets.compactToggle("Grid", "##gallery-grid", snaps[0], grid, third), 0),
                () -> toggle(GeodataWidgets.compactToggle("Angle", "##gallery-angle", snaps[1], angle, third), 1),
                () -> toggle(GeodataWidgets.compactToggle("Scale", "##gallery-scale", snaps[2], scale, third), 2));
        GeodataWidgets.space(GeodataWidgets.ROW);
        var half = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) / 2;
        GeodataWidgets.columns(
                () -> GeodataWidgets.compactDecimal("From", "##gallery-from", from, " units", half),
                () -> GeodataWidgets.compactDecimal("To", "##gallery-to", to, " units", half));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.actionField(
                "gallery-action-field",
                "X, Y, Z",
                place,
                "A field with its one action at its end",
                Glyph.GO,
                "Go",
                true);
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.pathField(
                "gallery-path-field", "Case JSON file", path, "A typed path with a browse button at its end");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.folderField(
                "gallery-folder-field",
                "Folder to write",
                Path.of("Exports", "Cruma export 2026-11-08_14-02").toString(),
                "A folder shown by its name, the parent on hover; a click chooses another");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var typed = GeodataWidgets.searchList("##gallery-search-list", listed, listFilter, "Find an AI", -1);
        if (typed != null) {
            for (var ai : new String[] {"default_maker", "event_maker", "close_door_maker"}) {
                if (ai.contains(typed) && GeodataWidgets.listItem(ai, ai, "12 makers", ai.equals(listed))) {
                    listed = ai;
                }
            }
            if ("old_maker".contains(typed)
                    && GeodataWidgets.listItem(
                            "old_maker", "old_maker", "no maker", "old_maker".equals(listed), true)) {
                listed = "old_maker";
            }
            GeodataWidgets.tip("No maker of this file uses this AI.");
            ImGui.endCombo();
        }
        GeodataWidgets.hint("A hint: a wrapped line that explains, in MUTED. 100% stays as it is.");

        GeodataWidgets.section("EXPORT SCALE");
        exportScale.draw("gallery-export-scale");

        GeodataWidgets.section("KEYS");
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        for (var key : new String[] {"N", "Esc", "Cmd+Z", "Drag a handle"}) {
            x += GeodataWidgets.keycap(draw, x, y, key) + 8;
        }
        ImGui.dummy(COLUMN, ImGui.getFontSize() + 6);

        GeodataWidgets.section("CARDS AND FEEDBACK");
        GeodataWidgets.callout("gallery-callout", Glyph.GEODATA, "No geodata yet", "Zones float without it", "Set up");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.callout(
                "gallery-callout-list",
                Glyph.GEODATA,
                "No geodata yet",
                "Some of the view needs it",
                "Set up",
                List.of("Zones float at the camera's height", "Random NPCs are not placed"));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.stackedCallout(
                "gallery-callout-stacked",
                Glyph.FOLDER,
                "Drop your Lineage II folder here",
                "The folder with Maps, Textures, StaticMeshes and system.",
                "Choose folder…",
                COLUMN);
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.progress("gallery-progress", "Region 12 of 152", "22_22 · walking · 9 min left", .3, "Stop");
        GeodataWidgets.space(GeodataWidgets.ROW);
        galleryFilesOpen = GeodataWidgets.disclosureCard(
                "gallery-disclosure-card",
                Glyph.FOLDER,
                "Save and export",
                "2 changed cells not saved",
                GeodataWidgets.WARNING,
                galleryFilesOpen);
        GeodataWidgets.space(GeodataWidgets.ROW);
        galleryInstall.drawClientField(Path.of("Lineage II"));
        GeodataWidgets.space(GeodataWidgets.ROW);
        galleryInstall.drawState(Path.of("Export"), false);
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.saveBar("gallery-save-bar", "2 changed cells not saved", "Save…", true);
        GeodataWidgets.undoBar(
                "gallery-undo-bar",
                "3 effects, 1 copy changed",
                "3 effects, 1 copy",
                true,
                true,
                false,
                "Cmd+Z",
                "Export",
                true,
                "Write the changes.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.undoBar(
                "gallery-undo-close-bar",
                "Not saved",
                "Not saved",
                true,
                true,
                false,
                "Cmd+Z",
                "Close",
                "Closes the scene. Changes that are not saved ask first.",
                "Save",
                true,
                "Saves flight.scene.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.busyBar("gallery-busy-bar", "Exporting region 12 of 224", .05, "Cancel", true);
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.footer(
                "Exported 22_22: 2,856 objects",
                GeodataWidgets.OPEN,
                List.of(
                        new FooterButton("gallery-footer-show", Glyph.FOLDER, "Show folder", 120, true),
                        new FooterButton("gallery-footer-done", null, "Done", 72, false)));
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var cardX = ImGui.getCursorScreenPosX();
        var cardY = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var cardHeight = ImGui.getFontSize() * 2 + 22;
        draw.addRectFilled(cardX, cardY, cardX + width, cardY + cardHeight, GeodataWidgets.CARD, 8f);
        draw.addRectFilled(
                cardX,
                cardY,
                cardX + 4,
                cardY + cardHeight,
                SpawnStyle.GUARD.getImguiColor(1f),
                8,
                ImDrawFlags.RoundCornersLeft);
        draw.addText(cardX + 14, cardY + 9, HudWidgets.TEXT, "Selection card");
        draw.addText(
                cardX + 14,
                cardY + ImGui.getFontSize() + 13,
                HudWidgets.MUTED,
                "kind color stripe · what it is · where");
        ImGui.dummy(width, cardHeight);
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var badgeWidth = SpawnPanelWidgets.badgeWidth("×12");
        for (var row = 0; row < 2; row++) {
            var rowY = ImGui.getCursorScreenPosY();
            var rowX = ImGui.getCursorScreenPosX();
            SpawnPanelWidgets.badge(
                    draw,
                    rowX,
                    rowY + 4,
                    badgeWidth,
                    row == 0 ? SpawnStyle.MONSTER.argb : SpawnStyle.BOSS.argb,
                    row == 0 ? "×12" : "×1");
            draw.addText(rowX + badgeWidth + 10, rowY + 2, HudWidgets.TEXT, row == 0 ? "marsh_stakato" : "marsh_queen");
            draw.addText(
                    rowX + badgeWidth + 10,
                    rowY + ImGui.getFontSize() + 4,
                    HudWidgets.MUTED,
                    row == 0 ? "Lv 34 · random · 30 s" : "Lv 48 · random · 6 h");
            ImGui.dummy(width, ImGui.getFontSize() * 2 + 8);
        }
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        ImGui.textColored(GeodataWidgets.WARNING, "Warning text: what is wrong and what to do.");
        ImGui.textColored(GeodataWidgets.BLOCKED, "Error text: what failed.");
        ImGui.textColored(GeodataWidgets.OPEN, "Success text: what was done.");
        drawBatch();
        drawNumberCards(fonts);
    }

    private void drawNumberCards(@Nullable FontManager fonts) {
        GeodataWidgets.section("CARDS OF NUMBERS AND STATES · A ROUND BUTTON");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var left = ImGui.getCursorScreenPosX();
        var width = ImGui.getContentRegionAvailX();
        var inner = width - 2 * CardWidgets.PAD;
        var places = new HashMap<String, float[]>();
        CardWidgets.numberRow(
                places,
                fonts,
                List.of(
                        new CardWidgets.Number("663", "NPC spawns", "What the number counts."),
                        new CardWidgets.Number("2", "named places", "What the number counts."),
                        new CardWidgets.Number("…", "geodata regions", "A number that still loads.")),
                left,
                width,
                "gallery-numbers");
        CardWidgets.numberRow(
                places,
                fonts,
                List.of(
                        new CardWidgets.Number("203", "tiles", "What the number counts."),
                        new CardWidgets.Number("148", "named places", "What the number counts."),
                        new CardWidgets.Number("24", "Classic tiles", "What the number counts."),
                        new CardWidgets.Number(CardWidgets.compact(104_300), "NPCs", "A large count in a few signs.")),
                left,
                width,
                "gallery-numbers-four");
        var top = CardWidgets.beginCard(left);
        ImGui.textColored(GeodataWidgets.MUTED, "A CARD'S TITLE");
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.roundButton("gallery-round-play", Glyph.PLAY, false, "Play");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        HudWidgets.roundButton("gallery-round-pause", Glyph.PAUSE, false, "Pause");
        ImGui.sameLine(0, GeodataWidgets.BLOCK);
        HudWidgets.roundButton("gallery-round-large-play", Glyph.PLAY, true, "Play");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        HudWidgets.roundButton("gallery-round-large-pause", Glyph.PAUSE, true, "Pause");
        GeodataWidgets.space(GeodataWidgets.ROW);
        CardWidgets.statusRow("Geodata", new ClientFolderStatus("194 regions", GeodataWidgets.OPEN), inner);
        CardWidgets.statusRow("Server scripts", new ClientFolderStatus("not set", 0), inner);
        CardWidgets.statusRow("Map image", new ClientFolderStatus("…", 0), inner);
        GeodataWidgets.space(GeodataWidgets.ROW);
        CardWidgets.folderLine(Path.of("/Games/Lineage II/HF-273"), inner);
        GeodataWidgets.space(GeodataWidgets.ROW);
        CardWidgets.link("gallery-card-link", "Settings of this client", "A line a click follows.");
        CardWidgets.endCard(places, left, width, top, "gallery-card");
        CardWidgets.emptyCard(
                places,
                left,
                width,
                "No server script folder",
                "A state with nothing to measure says what is missing and how to get it.",
                null,
                "gallery-card-empty");
    }

    private void drawBatch() {
        GeodataWidgets.section("SELECTION · BATCH BAR");
        BatchWidgets.begin("gallery-batch", 12, "maker", "makers", "All shown", "Select every maker the list shows.");
        BatchWidgets.actionGrid(
                "gallery-batch",
                List.of(
                        new Action("respawn", null, "Respawn ×", "Respawn times × a number."),
                        new Action("count", null, "Count ×", "Counts × a number."),
                        new Action("delete", Glyph.CLOSE, "Delete", "Delete them.")));
        BatchWidgets.end();
        GeodataWidgets.space(GeodataWidgets.ROW);
        BatchWidgets.begin(
                "gallery-batch-form", 12, "maker", "makers", "All shown", "Select every maker the list shows.");
        ImGui.alignTextToFramePadding();
        ImGui.textColored(HudWidgets.MUTED, "Respawn ×");
        ImGui.sameLine(110);
        ImGui.setNextItemWidth(80);
        ImGui.inputText("##gallery-factor", factor);
        BatchWidgets.applyCancelButtons("gallery-batch-form", "Apply to 12", true);
        BatchWidgets.end();
    }

    private void drawSettingRows() {
        GeodataWidgets.section("SETTINGS · NAVIGATION, ROWS, A FOLDER AND ITS STATE");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.searchField(
                "##gallery-settings-search", "Search every setting", settingsSearch, -1, false, Glyph.SEARCH);
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8, 4);
        GeodataWidgets.lineRow("gallery-nav-client", Glyph.FOLDER, "HF-273", "", GeodataWidgets.LineLook.PLAIN, false);
        GeodataWidgets.lineRow("gallery-nav-current", null, "Graphics", "", GeodataWidgets.LineLook.CURRENT, false);
        GeodataWidgets.lineRow("gallery-nav-plain", null, "Interface", "", GeodataWidgets.LineLook.PLAIN, false);
        ImGui.popStyleVar();
        GeodataWidgets.space(GeodataWidgets.ROW);
        settingOn = GeodataWidgets.settingSwitchRow(
                "gallery-setting-switch", "Vertical sync", "", settingOn, true, "The whole row is the button.");
        var times = new String[] {"By clock", "Always", "Off"};
        GeodataWidgets.beginSettingRow("Effects by time of day", "", GeodataWidgets.fittedSegmentedWidth(times));
        ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY() + 1);
        var time = GeodataWidgets.fittedSegmented("gallery-setting-times", times, settingTime);
        if (time >= 0) {
            settingTime = time;
        }
        GeodataWidgets.endSettingRow();
        var status = "194 regions";
        var statusWidth = GeodataWidgets.statusWidth(status, GeodataWidgets.OPEN);
        var fieldWidth = Math.min(200, ImGui.getContentRegionAvailX() * .5f - statusWidth);
        GeodataWidgets.beginSettingRow("Geodata folder", "For the Geodata tool", fieldWidth + 8 + statusWidth);
        var left = ImGui.getCursorScreenPosX();
        var top = ImGui.getCursorScreenPosY();
        GeodataWidgets.folderInput("gallery-setting-folder", "Folder", settingFolder, "A folder field.", fieldWidth);
        ImGui.setCursorScreenPos(left + fieldWidth + 8, top);
        GeodataWidgets.status(status, GeodataWidgets.OPEN);
        GeodataWidgets.endSettingRow();
        GeodataWidgets.beginSettingRow("Game client", "", 160);
        GeodataWidgets.folderText(Path.of("/Games/Lineage II/HF-273"), 160);
        GeodataWidgets.endSettingRow();
        if (GeodataWidgets.settingChevronRow(
                "gallery-setting-fold",
                "Tile views",
                "Cuts by height, such as a floor of a tower",
                "12 views",
                settingFoldOpen)) {
            settingFoldOpen = !settingFoldOpen;
        }
        GeodataWidgets.tip("A row that opens more: a part under it, or a page of the dialog.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.footer(
                "1 unsaved change",
                "Changes apply when the world loads again.",
                GeodataWidgets.WARNING,
                List.of(new FooterButton("gallery-settings-save", null, "Save", 64, true)));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.footer(
                "Drop 3 unsaved changes?",
                GeodataWidgets.WARNING,
                List.of(
                        new FooterButton("gallery-settings-keep", null, "Keep editing", 104, true),
                        new FooterButton("gallery-settings-drop", null, "Drop changes", 104, false)));
    }

    private static void drawColumn(String id, Runnable body) {
        ImGui.beginChild("##gallery-" + id, COLUMN, 0, false, ImGuiWindowFlags.NoScrollbar);
        body.run();
        ImGui.endChild();
    }

    private static void heading(@Nullable FontManager fonts, int size, String text) {
        var font = fonts == null ? null : fonts.getHeadingFont(size);
        if (font != null) {
            ImGui.pushFont(font, size);
        }
        ImGui.textUnformatted(text);
        if (font != null) {
            ImGui.popFont();
        }
    }

    private static void drawSwatches(String[] names, int[] colors) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var size = 28f;
        var cell = COLUMN / 4;
        for (var i = 0; i < names.length; i++) {
            var left = x + (i % 4) * cell;
            var top = y + (i / 4) * (size + 26);
            draw.addRectFilled(left, top, left + size, top + size, colors[i], 6f);
            draw.addRect(left, top, left + size, top + size, GeodataWidgets.DIVIDER, 6, 0, 1);
            draw.addText(
                    left,
                    top + size + 4,
                    HudWidgets.MUTED,
                    names[i].length() > 12 ? names[i].substring(0, 12) : names[i]);
        }
        ImGui.dummy(COLUMN, ((names.length + 3) / 4) * (size + 26));
    }

    private static void drawSpacing(String name, float pixels, String use) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        draw.addRectFilled(
                x, y + 2, x + pixels, y + ImGui.getFontSize() + 2, SpawnStyle.getImguiColor(0xFF5CDBF1, .45f, 1f), 2f);
        draw.addText(x + 30, y, HudWidgets.TEXT, name + "  " + (int) pixels);
        draw.addText(x + 140, y, HudWidgets.MUTED, use);
        ImGui.dummy(COLUMN, ImGui.getFontSize() + 4);
    }
}

package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.world.CameraPath.Pose;
import com.rost.cruma.core.world.MapScenes.Vector;
import com.rost.cruma.viewer.ui.DirectorAdd.Row;
import com.rost.cruma.viewer.ui.QuickStarts.Direction;
import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiSelectableFlags;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class DirectorQuickStart {
    private static final int POINT = -1;
    private static final String POINT_NAME = "A point in the world";
    private static final float LEAST_SECONDS = .5f;
    private static final float MOST_SECONDS = 600;
    private static final float LEAST_ROOM = 2;
    private static final float[] TURNS = {.25f, 4};
    private static final float[] DISTANCE = {100, 20_000};
    private static final float[] RISE = {0, 5_000};

    private final float[] turns = {1};
    private final float[] seconds = {LEAST_ROOM};
    private final float[] distance = {1_500};
    private final float[] rise = {300};
    private Direction direction = Direction.LEFT;
    private int around;
    private boolean awaiting;
    private @Nullable Pose flyStart;
    private float aroundTop;
    private float directionLeft;
    private float directionWidth;
    private float makeLeft;
    private float makeTop;

    static String where(DirectorSession open) {
        var start = open.getQuickStartAt();
        return open.getPath() == null
                ? String.format(Locale.ROOT, "Adds the first shot at %.2f s.", start)
                : String.format(Locale.ROOT, "Adds a shot at %.2f s, after the last key.", start);
    }

    void begin(DirectorSession open) {
        flyStart = null;
        around = 0;
        awaiting = false;
        open.forgetPoint();
        seconds[0] = (float) Math.max(LEAST_ROOM, open.length() - open.getQuickStartAt());
        var standing = open.getStanding();
        if (!standing.isEmpty()) {
            around = standing.getFirst().index();
        }
    }

    void end(DirectorSession open) {
        flyStart = null;
        open.forgetPoint();
        var placing = open.getPlacing();
        if (placing != null && placing.point()) {
            open.cancelPlacing();
        }
    }

    boolean draw(DirectorSession open, Row row) {
        aroundTop = 0;
        makeTop = 0;
        GeodataWidgets.section(row.getTitle());
        GeodataWidgets.tip(row.getTip());
        space(GeodataWidgets.ROW);
        GeodataWidgets.hint(where(open));
        space(GeodataWidgets.ROW);
        return switch (row) {
            case ORBIT -> drawOrbit(open);
            case FLY_TO -> drawFlyTo(open);
            case REVEAL -> drawReveal(open);
            default -> true;
        };
    }

    private boolean drawOrbit(DirectorSession open) {
        var standing = open.getStanding();
        var stands = false;
        for (var actor : standing) {
            stands |= actor.index() == around;
        }
        if (around > 0 && !stands) {
            around = 0;
        }
        if (awaiting && open.getPlacing() == null) {
            awaiting = false;
            if (open.getPointed() != null) {
                around = POINT;
            }
        }
        if (around == POINT && open.getPointed() == null) {
            around = 0;
        }
        ImGui.textColored(GeodataWidgets.MUTED, "Around");
        aroundTop = ImGui.getCursorScreenPosY();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        var shown = around == POINT ? POINT_NAME : "Choose a centre";
        for (var actor : standing) {
            if (actor.index() == around) {
                shown = actor.name();
            }
        }
        if (HudWidgets.beginCombo("##director-orbit-around", shown, 0)) {
            for (var actor : standing) {
                if (ImGui.selectable(
                        actor.name() + "##" + actor.index(), actor.index() == around, ImGuiSelectableFlags.None)) {
                    around = actor.index();
                    open.forgetPoint();
                }
            }
            if (ImGui.selectable(POINT_NAME, around == POINT, ImGuiSelectableFlags.None)) {
                open.placePoint("the centre of the orbit");
                awaiting = true;
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "What the camera turns around: an actor that stands in the scene at the playhead, which the keys then"
                        + " follow, or a point that a press on the floor gives.");
        space(GeodataWidgets.ROW);
        var half = GeodataWidgets.columnWidth(2);
        GeodataWidgets.columns(
                () -> {
                    GeodataWidgets.compactDrag(
                            "Turns",
                            "##director-orbit-turns",
                            turns,
                            half,
                            .01f,
                            TURNS[0],
                            TURNS[1],
                            "%.2f",
                            GeodataWidgets.MUTED);
                    GeodataWidgets.tip(
                            "How far the camera goes round, from a quarter to four turns. Drag, or double-click to"
                                    + " type.");
                    return false;
                },
                () -> drawSeconds(half));
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Direction");
        directionLeft = ImGui.getCursorScreenPosX();
        directionWidth = ImGui.getContentRegionAvailX();
        var clicked = GeodataWidgets.segmented(
                "director-orbit-direction",
                new String[] {"Left", "Right"},
                direction == Direction.LEFT ? 0 : 1,
                directionWidth);
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            HudWidgets.showTooltip(
                    GeodataWidgets.hoveredSegment(directionLeft, directionWidth, 2) == 0
                            ? "The camera goes to its left as it looks at the centre: clockwise seen from above."
                            : "The camera goes to its right as it looks at the centre: counter-clockwise seen from"
                                    + " above.");
        }
        if (clicked >= 0) {
            direction = clicked == 0 ? Direction.LEFT : Direction.RIGHT;
        }
        var view = open.getViewNow();
        var center = center(open);
        String off = open.getNoQuickStart();
        if (off == null && center == null) {
            off = "Choose what the camera turns around first.";
        } else if (off == null && QuickStarts.radius(view, center) < QuickStarts.LEAST_RADIUS) {
            off = "The camera stands right over the centre. Fly aside first, as its distance across is the radius.";
        }
        space(GeodataWidgets.ROW);
        if (buttons("Make orbit", off, "Makes the keys of the circle as a new shot at the end of the path.")) {
            open.addShot(QuickStarts.orbit(view, center, around > 0 ? around : 0, turns[0], direction, seconds[0]));
            return true;
        }
        return isCancelled();
    }

    private boolean drawFlyTo(DirectorSession open) {
        drawSeconds(GeodataWidgets.columnWidth(2));
        space(GeodataWidgets.ROW);
        var off = open.getNoQuickStart();
        if (flyStart == null) {
            if (buttons("Start here", off, "Keeps the view now as the start of the flight. Nothing changes yet.")) {
                flyStart = open.getViewNow();
            }
            return isCancelled();
        }
        GeodataWidgets.hint("Fly to where the flight ends and press End here.");
        space(GeodataWidgets.ROW);
        if (buttons(
                "End here",
                off,
                "Puts the start and this view in as a new shot at the end of the path, Length apart.")) {
            open.addShot(QuickStarts.flyTo(flyStart, open.getViewNow(), seconds[0]));
            return true;
        }
        return isCancelled();
    }

    private boolean drawReveal(DirectorSession open) {
        var third = GeodataWidgets.columnWidth(3);
        GeodataWidgets.columns(
                () -> {
                    GeodataWidgets.compactRange(
                            "Distance",
                            "##director-reveal-distance",
                            distance,
                            third,
                            DISTANCE[0],
                            DISTANCE[1],
                            "%.0f u",
                            GeodataWidgets.MUTED);
                    GeodataWidgets.tip(
                            "How far the camera pulls back along its look, in units. Drag, or double-click to type.");
                    return false;
                },
                () -> {
                    GeodataWidgets.compactDrag(
                            "Rise",
                            "##director-reveal-rise",
                            rise,
                            third,
                            5,
                            RISE[0],
                            RISE[1],
                            "%.0f u",
                            GeodataWidgets.MUTED);
                    GeodataWidgets.tip("How far the camera rises on the way, in units.");
                    return false;
                },
                () -> drawSeconds(third));
        space(GeodataWidgets.ROW);
        if (buttons(
                "Make reveal",
                open.getNoQuickStart(),
                "Makes two keys as a new shot at the end of the path: the view now, and the same view further back and"
                        + " higher.")) {
            var far = Math.clamp(distance[0], DISTANCE[0], DISTANCE[1]);
            open.addShot(QuickStarts.reveal(open.getViewNow(), far, rise[0], seconds[0]));
            return true;
        }
        return isCancelled();
    }

    private boolean drawSeconds(float width) {
        GeodataWidgets.compactDrag(
                "Length",
                "##director-quick-length",
                seconds,
                width,
                .05f,
                LEAST_SECONDS,
                MOST_SECONDS,
                "%.2f s",
                GeodataWidgets.MUTED);
        GeodataWidgets.tip("How long the move takes, in seconds. The scene grows when it ends later.");
        return false;
    }

    private boolean buttons(String label, @Nullable String off, String tip) {
        makeLeft = ImGui.getCursorScreenPosX();
        makeTop = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(off != null);
        var pressed = GeodataWidgets.rowButton(label, 0, 2) && off == null;
        ImGui.endDisabled();
        GeodataWidgets.tip(off == null ? tip : off);
        return pressed;
    }

    private boolean isCancelled() {
        var pressed = GeodataWidgets.rowButton("Cancel", 1, 2);
        GeodataWidgets.tip("Goes back with nothing changed.", "Esc");
        return pressed;
    }

    private @Nullable Vector center(DirectorSession open) {
        if (around == POINT) {
            var point = open.getPointed();
            return point == null ? null : QuickStarts.overFloor(point);
        }
        return around > 0 ? open.getMiddleOf(around) : null;
    }
}

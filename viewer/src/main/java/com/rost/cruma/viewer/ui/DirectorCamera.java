package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.world.SceneDocument;
import imgui.ImGui;
import imgui.flag.ImGuiSelectableFlags;
import java.util.Locale;

final class DirectorCamera {
    private static final String POINT_NAME = "A point in front of the view";
    private static final float LEAST_PLACE = .5f;
    private static final float MOST_PLACE = 1_000;
    private static final float LEAST_TURN = .05f;
    private static final float MOST_TURN = 45;
    private static final float FIRST_PLACE = 8;
    private static final float FIRST_TURN = .5f;

    private final float[] place = {FIRST_PLACE};
    private final float[] turn = {FIRST_TURN};
    private float shotLeft;
    private float shotTop;
    private float targetTop;
    private float keysLeft;
    private float keysTop;
    private float limitsTop;
    private float removeTop;

    static boolean shows(SceneDocument document) {
        return document.path() == null && (!document.basedOn().isEmpty() || hasShots(document));
    }

    static boolean hasShots(SceneDocument document) {
        for (var item : document.items()) {
            if (item.camera() != null && !item.camera().blank()) {
                return true;
            }
        }
        return false;
    }

    static String rounding(double moved, double turned) {
        if (moved < .05 && turned < .05) {
            return "Whole numbers left the view as it was.";
        }
        return String.format(
                Locale.ROOT, "Whole numbers moved the camera %.1f units and turned the view %.1f°.", moved, turned);
    }

    static String made(int keys, int cuts, double place, double turn) {
        return String.format(
                Locale.ROOT,
                "%d keys%s. At worst %.1f units and %.2f° from the camera of the scene. In the client the mark would"
                        + " still run.",
                keys,
                cuts == 0 ? "" : ", " + cuts + (cuts == 1 ? " cut" : " cuts"),
                place,
                turn);
    }

    void drawPathWarning(DirectorSession open) {
        removeTop = 0;
        var document = open.getDocument();
        if (document == null || document.path() == null || !hasShots(document) && !open.hasMarks()) {
            return;
        }
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(
                "The path holds the camera for the whole scene, so shots and marks do not show.",
                GeodataWidgets.WARNING);
        space(GeodataWidgets.ROW);
        removeTop = ImGui.getCursorScreenPosY();
        if (GeodataWidgets.rowButton("Remove the path", 0, 1)) {
            open.removePath();
        }
        GeodataWidgets.tip("Takes all keys of the path away, so the shots and the marks show again.");
    }

    void draw(DirectorSession open, boolean chosen) {
        var document = open.getDocument();
        if (document == null) {
            return;
        }
        shotTop = 0;
        targetTop = 0;
        keysTop = 0;
        var chosenShot = open.getChosenShot();
        var actors = open.getStanding();
        if (!shows(document)
                || chosen && chosenShot == null
                || chosenShot == null && actors.isEmpty() && !open.canAimAtPoint()) {
            drawMarks(open, document);
            return;
        }
        GeodataWidgets.section("CAMERA");
        GeodataWidgets.tip("Aims the scene from where the free camera stands.");
        if (chosenShot == null) {
            drawTarget(open);
        }
        space(GeodataWidgets.ROW);
        shotLeft = ImGui.getCursorScreenPosX();
        shotTop = ImGui.getCursorScreenPosY();
        if (GeodataWidgets.rowButton("Shot from view", 0, 1)) {
            open.isShotFromView();
        }
        GeodataWidgets.tip(
                chosenShot == null
                        ? "Makes a shot at the playhead that stands where the free camera stands."
                        : "Gives the chosen shot the numbers of what the free camera shows. Its target and its times"
                                + " stay.");
        var aimed = open.getAimed();
        if (aimed != null) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(rounding(aimed.moved(), aimed.turned()), GeodataWidgets.MUTED);
            if (aimed.drifts()) {
                space(GeodataWidgets.ROW);
                GeodataWidgets.note(
                        "This target moves, and the client adds the Rel angles up on the way to a new target, so the"
                                + " view can turn away. Look straight at it, or shoot where it stands still.",
                        GeodataWidgets.WARNING);
            }
        }
        if (!open.getAimProblem().isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(open.getAimProblem(), GeodataWidgets.WARNING);
        }
        drawMarks(open, document);
    }

    private void drawTarget(DirectorSession open) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Target");
        targetTop = ImGui.getCursorScreenPosY();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        var chosen = open.getShotTarget();
        if (HudWidgets.beginCombo("##director-target", targetName(open, chosen), 0)) {
            for (var actor : open.getStanding()) {
                if (ImGui.selectable(
                        actor.name() + "##" + actor.index(), actor.index() == chosen, ImGuiSelectableFlags.None)) {
                    open.setShotTarget(actor.index());
                }
            }
            if (open.canAimAtPoint()
                    && ImGui.selectable(POINT_NAME, chosen == DirectorSession.POINT, ImGuiSelectableFlags.None)) {
                open.setShotTarget(DirectorSession.POINT);
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "What the shot looks at. A point in front of the view gets an invisible NPC, as the client's own"
                        + " scenes have.");
    }

    private void drawMarks(DirectorSession open, SceneDocument document) {
        var fit = open.getFit();
        if (fit == null && (!open.hasMarks() || document.path() != null)) {
            return;
        }
        GeodataWidgets.section("MARKS");
        GeodataWidgets.tip(
                "A mark of an animation holds the camera, so it has no numbers to change. Make keys turns its way into"
                        + " a path.");
        if (fit != null) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    made(fit.keys().size(), fit.cuts(), fit.worstPlace(), fit.worstTurn()), GeodataWidgets.MUTED);
            return;
        }
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing) / 2;
        limitsTop = ImGui.getCursorScreenPosY();
        GeodataWidgets.columns(
                () -> {
                    GeodataWidgets.compactDrag(
                            "Place",
                            "##director-keys-place",
                            place,
                            width,
                            .1f,
                            LEAST_PLACE,
                            MOST_PLACE,
                            "%.1f",
                            GeodataWidgets.MUTED);
                    GeodataWidgets.tip("How far the keys may stand the camera from the camera of the scene, in units.");
                    return false;
                },
                () -> {
                    GeodataWidgets.compactDrag(
                            "Turn",
                            "##director-keys-turn",
                            turn,
                            width,
                            .01f,
                            LEAST_TURN,
                            MOST_TURN,
                            "%.2f°",
                            GeodataWidgets.MUTED);
                    GeodataWidgets.tip("How far they may turn the view from the camera of the scene.");
                    return false;
                });
        space(GeodataWidgets.ROW);
        keysLeft = ImGui.getCursorScreenPosX();
        keysTop = ImGui.getCursorScreenPosY();
        if (GeodataWidgets.rowButton("Make keys", 0, 1)) {
            open.makeKeys(place[0], turn[0]);
        }
        GeodataWidgets.tip(
                "Makes a path of few keys that stays within these limits. In the client the mark would still run.");
    }

    private static String targetName(DirectorSession open, int index) {
        if (index == DirectorSession.POINT) {
            return POINT_NAME;
        }
        for (var actor : open.getStanding()) {
            if (actor.index() == index) {
                return actor.name();
            }
        }
        return "Nothing stands here";
    }
}

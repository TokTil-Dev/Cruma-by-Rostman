package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.ROW;
import static com.rost.cruma.viewer.ui.GeodataWidgets.SECTION;
import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.viewer.spawn.MapPawnStage;
import com.rost.cruma.viewer.spawn.MapPawnStage.Hit;
import glm_.vec3.Vec3;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class MapPawnCard implements InspectedView {
    private final MapPawnStage stage;
    private @Nullable String inspected;

    public MapPawnCard(MapPawnStage stage) {
        this.stage = stage;
    }

    public void inspect(@Nullable Hit hit) {
        inspected = hit == null ? null : hit.path();
    }

    public void stopInspecting() {
        inspected = null;
    }

    @Override
    public @Nullable Header getInspectedHeader() {
        var path = inspected;
        var place = path == null ? null : stage.findPlace(path);
        if (place == null) {
            return null;
        }
        var mesh = place.pawn().mesh();
        return new Header(
                mesh.substring(mesh.lastIndexOf('.') + 1),
                path + " · " + mesh,
                place.pawn().getClassName());
    }

    @Override
    public void drawInspectedDetails() {
        var path = inspected;
        var place = path == null ? null : stage.findPlace(path);
        if (place == null) {
            return;
        }
        var pawn = place.pawn();
        var figure = place.figure();
        GeodataWidgets.section("MODEL", ROW);
        space(ROW);
        GeodataWidgets.value(
                "Status",
                place.missing() ? "The client's mesh could not be read" : figure == null ? "Loading…" : "Drawn",
                place.missing() ? GeodataWidgets.WARNING : HudWidgets.TEXT);
        GeodataWidgets.value("Mesh", pawn.mesh());
        GeodataWidgets.value("Class", pawn.getClassName());
        GeodataWidgets.value("Scale", formatScale(pawn.scale()));
        if (figure != null) {
            GeodataWidgets.value(
                    "Height",
                    String.format(
                            Locale.ROOT,
                            "%,.0f units",
                            figure.model().height() * Math.abs(pawn.scale().getZ())));
        }
        if (pawn.skins().stream().anyMatch(skin -> !skin.isBlank())) {
            space(ROW);
            GeodataWidgets.value("Skins", "");
            for (var skin : pawn.skins()) {
                GeodataWidgets.note(skin.isBlank() ? "the mesh's own" : skin, HudWidgets.TEXT);
            }
        }
        GeodataWidgets.section("ANIMATION", SECTION);
        space(ROW);
        var playing = place.moment();
        GeodataWidgets.value("Plays", playing == null ? "Nothing: the reference pose" : playing.sequence());
        var script = pawn.script();
        if (script != null) {
            GeodataWidgets.value("Played by", script.sources().isEmpty() ? "LineagePawn" : "The script of its class");
            GeodataWidgets.value("Tag", pawn.tag());
        }
        var animations = pawn.animations();
        if (!animations.list().isEmpty()) {
            GeodataWidgets.value("List", String.join(", ", animations.list()));
            GeodataWidgets.value("Order", animations.random() ? "Random" : "In turn");
        }
        if (!animations.interval().isEmpty()) {
            GeodataWidgets.value("Between", animations.interval());
        }
        if (!animations.first().isEmpty()) {
            GeodataWidgets.value("First in the lobby", animations.first());
        }
        GeodataWidgets.section("PLACE", SECTION);
        space(ROW);
        var at = pawn.location();
        GeodataWidgets.value(
                "Stands at", String.format(Locale.ROOT, "%.0f, %.0f, %.0f", at.getX(), at.getY(), at.getZ()));
        var turn = pawn.rotation();
        GeodataWidgets.value(
                "Turn", String.format(Locale.ROOT, "pitch %d, yaw %d, roll %d", turn.pitch(), turn.yaw(), turn.roll()));
        GeodataWidgets.value("Casts a shadow", pawn.castsShadow() ? "Yes" : "No");
        space(ROW);
        GeodataWidgets.hint(
                pawn.script() == null || pawn.script().sources().isEmpty()
                        ? "The map places this pawn with its mesh, so it is drawn as the map has it. Edit moves,"
                              + " turns, sizes, copies and deletes it. Export location and Export map take it with its"
                              + " rig."
                        : "The map places this pawn with a class of its own, whose script plays it and hangs its"
                                + " effects on it. Edit moves, turns, sizes, copies and deletes it. Export location and"
                                + " Export map take it with its rig.");
    }

    private static String formatScale(Vec3 scale) {
        if (scale.getX() == scale.getY() && scale.getY() == scale.getZ()) {
            return String.format(Locale.ROOT, "%.2f", scale.getX());
        }
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", scale.getX(), scale.getY(), scale.getZ());
    }
}

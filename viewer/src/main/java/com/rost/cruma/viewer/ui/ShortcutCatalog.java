package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.config.UserPaths;
import com.rost.cruma.viewer.ui.ShortcutKeys.Piece;
import com.rost.cruma.viewer.ui.ShortcutReference.View;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class ShortcutCatalog {
    private static final String COMMAND =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "Cmd" : "Ctrl";
    private static final String UNDO = COMMAND + "+Z";
    private static final String REDO = "Shift+" + UNDO;

    private ShortcutCatalog() {}

    static List<Section> sections(View view) {
        var sections = new ArrayList<Section>();
        if (view.effectsOnly()) {
            sections.add(effects());
            sections.add(camera(false));
            sections.add(new Section("INTERFACE")
                    .row("F1", "Show or hide this list")
                    .row("Shift+F1", "Guide: what a tool is for")
                    .row("F3", "Scene panel")
                    .row("F4", "Performance panel")
                    .row("H", "Hide the interface")
                    .row("Esc", "Close the panel"));
            sections.add(pictures(view.screenshots()));
            return sections;
        }
        if (view.geodata()) {
            sections.add(geodata());
        }
        if (view.editing()) {
            sections.addAll(editing());
        }
        sections.add(camera(true));
        if (view.scenes()) {
            sections.add(new Section("SCENES").row("Esc", "Stop the scene, the camera moves again"));
        }
        sections.add(new Section("INTERFACE")
                .row("F1", "Show or hide this list")
                .row("Shift+F1", "Guide: what a tool is for")
                .row("F2", "Next mode: Explore, Inspect, Analyze")
                .row("F3", "Scene panel")
                .row("Tab", "Object panel")
                .row("F4", "Performance panel")
                .row("M", "World map")
                .row("P", "Pause or play the scene, outside Edit")
                .row("H", "Hide the interface, outside Edit")
                .row("Esc", "Close this list, then the panel, then clear the selection"));
        sections.add(pictures(view.screenshots()));
        if (!view.editing()) {
            sections.addAll(editing());
        }
        if (!view.geodata()) {
            sections.add(geodata());
        }
        sections.add(new Section("NPC SPAWNS")
                .row("N", "Show or hide spawn zones")
                .row("Hover a zone", "Preview its NPCs")
                .row("Click", "Select a maker, again for the next")
                .row("Double-click a zone", "Edit the zone and its NPCs")
                .row("Double-click an NPC", "Open it in NPCs")
                .row("Drag a handle", "Move a corner, zone or NPC")
                .row("Shift + drag a zone", "Move it, its random NPCs along")
                .row("Delete", "Remove the hovered corner")
                .row("F", "Focus the selected maker")
                .row("Enter", "Search: open the first match")
                .row("Esc", "Cancel a drag, stop editing, deselect")
                .row(COMMAND + "+S", "Save npcpos.txt"));
        sections.add(new Section("NPC SPAWNS · DRAW ZONE")
                .row("Click", "Add a corner, on 16-unit cells")
                .row("Enter", "Close the zone")
                .row("Shift", "Hold: corners off the cells"));
        sections.add(new Section("NPC SPAWNS · SELECT")
                .row("Click a zone or NPC", "Choose its makers, again to take them out")
                .row("Shift + click in a list", "Select a range")
                .row("Drag on the map", "Select the makers in the box, with the World map open"));
        sections.add(new Section("AREAS")
                .row("L", "Show or hide the server's areas")
                .row("Point at an area", "What it does, with the Areas panel open")
                .row("Click", "Select the smallest area there")
                .row("Click again", "The next area at the same place")
                .row("Double-click an area", "Edit its shape")
                .row("Drag a handle", "Move a corner, a point or the bottom or top")
                .row("Click an edge's middle", "Add a corner there")
                .row("Drag inside", "Move the area, or the excluded part pressed")
                .row("Delete", "Remove the hovered corner")
                .row("F", "Focus the selected area")
                .row("Enter", "Search: open the first match")
                .row("Esc", "Take a drag back, stop editing, deselect")
                .row(UNDO, "Undo an area change")
                .row(REDO, "Redo it")
                .row(COMMAND + "+S", "Save areadata.txt"));
        sections.add(new Section("AREAS · DRAW AREA AND ADD POINT")
                .row("Click", "Add a corner, on 16-unit cells, or place the point")
                .row("Backspace", "Take the last corner back")
                .row("Enter", "Close the area or the part")
                .row("Shift", "Hold: corners off the cells")
                .row("Esc", "Cancel"));
        sections.add(new Section("MAP EVENTS")
                .row("Click a row", "Select the event and frame its effects")
                .row("Click its check box", "Turn the event on or off")
                .row("Point at a mark", "What it is and how far")
                .row("Click a mark", "Select it and show its row and card")
                .row("Double-click a mark", "Frame its effects")
                .row("T", "Turn the mark under the pointer on or off")
                .row("F", "Frame the selected event")
                .row("Esc", "Deselect"));
        sections.add(new Section("VIDEO DIRECTOR, WITH THE TRAY OPEN")
                .row("Space", "Play or hold")
                .row("K", "Set a key at the playhead, or replace it")
                .row("C", "New shot at the playhead with what the camera shows")
                .row(", / .", "A frame back or on")
                .row("[ / ]", "The key before or the next key")
                .row("Delete", "Delete the chosen keys")
                .row("Shift + click", "Choose a key beside the others")
                .row("Ctrl + wheel", "Timeline: zoom at the pointer")
                .row("Wheel", "Timeline: move the view")
                .row(UNDO, "Undo a change of the scene")
                .row(REDO, "Redo it"));
        sections.add(effects());
        return sections;
    }

    private static Section effects() {
        return new Section("EFFECTS")
                .row("Up / Down", "Previous or next effect or skill")
                .row("R", "Play it again")
                .row("P", "Pause or play")
                .row(", / .", "A frame back or on, while paused")
                .row("F", "Frame the effect or the caster")
                .row(UNDO, "Undo an edit of an effect")
                .row(REDO, "Redo it")
                .row("Esc", "Clear the chosen effect or skill");
    }

    private static Section camera(boolean tiles) {
        var camera = new Section("CAMERA")
                .row("Right drag", "Look around")
                .row("W A S D", "Move")
                .row("E / Space", "Up or down")
                .row("Shift / Alt", "Fast or fine movement")
                .row("1 - 5", "Speed presets")
                .row("+ / -", "Faster or slower")
                .row("Right drag + wheel", "Faster or slower while you fly")
                .row("O", "Top view, and back")
                .row("Wheel", "Top, Front, Side: zoom at the pointer")
                .row("Alt + wheel", "Top, Front, Side: move the slice")
                .row("E / Space", "Top, Front, Side: zoom out or in");
        if (tiles) {
            camera.row("Left / Right", "Previous or next tile");
        }
        return camera;
    }

    private static Section pictures(Path screenshots) {
        return new Section("PICTURE")
                .row("F12", "Screenshot to " + UserPaths.toDisplayString(screenshots), screenshots.toString())
                .row("Shift+F12", "Screenshot of the other kind: with or without the interface")
                .row("F5", "Next upscaler: Native, Bilinear, FSR1")
                .row("F6", "Next quality preset of the upscaler");
    }

    private static List<Section> editing() {
        return List.of(
                new Section("EDIT")
                        .row("Q / P / T / B", "Select, Place, Sculpt, Paint")
                        .row("I / U", "Lights, Surfaces")
                        .row(UNDO, "Undo, actors and Terrain alike")
                        .row(COMMAND + "+Y", "Redo, also " + REDO)
                        .row("Right click Undo", "The list of steps: a click goes there")
                        .row(COMMAND + "+S", "Open the save review, then save")
                        .row("Shift+" + COMMAND + "+S", "Save into another folder")
                        .row("Right drag", "Hold, then W A S D to fly")
                        .row("Right drag + wheel", "Faster or slower camera, 25% a notch")
                        .row("Esc", "Cancel a gesture, back to Select, close"),
                new Section("EDIT · SELECT")
                        .row("Click", "Select an actor")
                        .row("Double-click", "Select an actor and frame it")
                        .row("Click again", "The next object behind, at the same place")
                        .row(COMMAND + " + click", "Add or remove from the selection")
                        .row("Shift + click", "Add to the selection")
                        .row("Drag", "Select the visible actors in a rectangle")
                        .row("Alt", "As you let go of the rectangle: also actors behind others")
                        .row("Right click", "Menu of the actor or the place")
                        .row("Click twice", "A group's object alone. Esc: the group again")
                        .row("F", "Frame the selection")
                        .row("H", "Hide the selection")
                        .row("Shift+H", "Isolate: hide all but the selection")
                        .row("Alt+H", "Show every hidden actor")
                        .row("L", "Lock or unlock the selection"),
                new Section("EDIT · MOVE, TURN AND SIZE")
                        .row("W / E / R", "Move, Rotate, Scale gizmo")
                        .row("Alt + drag", "On the gizmo: copy, then move the copy")
                        .row("Drag + 0-9", "On one axis: type the exact distance, angle or size")
                        .row("Enter / Esc", "Apply the typed number, or cancel the drag")
                        .row(COMMAND, "During a drag: snap the other way")
                        .row("Alt + drag", "Off the gizmo: orbit around the selection")
                        .row("Arrows", "Nudge along X and Y by the grid step")
                        .row("PgUp / PgDn", "Nudge along Z. With Shift: ten steps")
                        .row("End", "Drop the selection to the ground")
                        .row("Shift+End", "Drop it and follow the slope")
                        .row("V + drag", "Move along the surface, flush with neighbours")
                        .row("M + drag", "Measure from point to point"),
                new Section("EDIT · PLACE, COPY AND GROUP")
                        .row("Click", "Place: put the chosen mesh there")
                        .row("Drag a Library row", "Place its mesh where you let go")
                        .row(COMMAND + " + click", "Place: take the mesh of an actor")
                        .row("Drag", "Place, Brush: paint actors")
                        .row("Shift + drag", "Place, Brush: erase actors")
                        .row("Alt + click", "With the material picker open: take an actor's material")
                        .row(COMMAND + "+D", "Duplicate the selected actors")
                        .row(COMMAND + "+C / " + COMMAND + "+V", "Copy actors, paste under the pointer")
                        .row("Del", "Delete the selected actors")
                        .row(COMMAND + "+G", "Group the selection")
                        .row("Shift+" + COMMAND + "+G", "Ungroup the selected group"),
                new Section("EDIT · SCULPT AND PAINT")
                        .row("Drag", "Brush the Terrain")
                        .row("Shift + drag", "Lower it, or erase the layer or decoration")
                        .row("[ / ]", "Smaller or bigger brush, also " + COMMAND + " + wheel")
                        .row("Shift+[ / ]", "Weaker or stronger brush, also Shift + wheel")
                        .row("Alt + click", "Sculpt: pick a height. Paint: pick a layer or decoration")
                        .row("Click, click", "Ramp: its start and its end")
                        .row("Alt + drag", "Select: move the selected ground up or down")
                        .row("Drag", "Holes: hide cells. Edges: turn their diagonals")
                        .row("Shift + drag", "Holes: show cells. Edges: as saved"),
                new Section("EDIT · LIGHTS")
                        .row("N / Shift+N", "New light, new spotlight")
                        .row(COMMAND + " + click", "Add or remove a light")
                        .row("Drag", "Choose the lights in a rectangle")
                        .row(COMMAND + "+Shift+F", "Aim a spotlight from the camera"),
                new Section("EDIT · SURFACES")
                        .row("Click", "Choose the wall or floor under the pointer")
                        .row(COMMAND + " + click", "Add or remove a surface, also Shift + click")
                        .row("Del", "Delete the chosen sheets and brushes")
                        .row("Shift+B", "Every side of the brush: Choose brush")
                        .row("Shift+T", "Every surface of the same texture")
                        .row("Shift+I", "Sides of the brush with the same item name")
                        .row("Shift+J", "All adjacent surfaces")
                        .row("Shift+C", "Adjacent surfaces in the same plane")
                        .row("Shift+W / F / Y", "Adjacent walls, floors and ceilings, slants")
                        .row("Shift+Q", "Reverse the choice in its maps")
                        .row("Shift+A / Shift+N", "All of the map, none")
                        .row("Shift+M / Shift+R", "Memorize the choice, recall it")
                        .row("Shift+O / U / X", "Or, and, xor with the memorized surfaces"));
    }

    private static Section geodata() {
        return new Section("GEODATA")
                .row("G", "Show or hide geodata")
                .row("Q / R / B / C", "Inspect, Route, Edit, Check")
                .row("X", "X-ray: geodata behind walls")
                .row("Click", "Select a cell, again for the floor below")
                .row("Drag", "Route: from A to B. Edit: a wall or paint")
                .row("[ / ]", "Floor below or above. Edit: brush size")
                .row("F", "Focus the selected cell")
                .row("I", "Isolate its walkable area")
                .row("Z", "Slice at its floor")
                .row("Shift + click", "Route: move A. Edit: one side")
                .row("Shift + drag", "Edit: a rectangle of the brush. Inspect: select an area of cells")
                .row("Alt + click", "Edit, Height: take a cell's height")
                .row("Ctrl + wheel", "Edit: brush size")
                .row(COMMAND + "+C / " + COMMAND + "+V", "Copy the area, paste it at the pointer")
                .row("Ctrl + click", "Route: add a stop")
                .row("Space", "Route, Walk: pause")
                .row(UNDO, "Undo an edit")
                .row(REDO, "Redo it")
                .row("Esc", "Cancel, clear, then deselect");
    }

    static final class Section {
        private final String title;
        private final List<Row> rows = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }

        String getTitle() {
            return title;
        }

        List<Row> getRows() {
            return rows;
        }

        Section row(String keys, String action) {
            return row(keys, action, null);
        }

        Section row(String keys, String action, @Nullable String tip) {
            rows.add(new Row(keys, ShortcutKeys.parse(keys), action, tip));
            return this;
        }
    }

    record Row(
            String keys,
            List<Piece> pieces,
            String action,
            @Nullable String tip) {
        boolean matches(String[] words) {
            var text = (keys + ' ' + action).toLowerCase(Locale.ROOT);
            for (var word : words) {
                if (!text.contains(word)) {
                    return false;
                }
            }
            return true;
        }
    }
}

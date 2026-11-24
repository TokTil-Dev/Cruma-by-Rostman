package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataScanner;
import com.rost.cruma.core.geodata.GeoDataScanner.Confidence;
import com.rost.cruma.core.geodata.GeoDataScanner.Rule;

public final class GeoFindingWords {
    private GeoFindingWords() {}

    public static String title(Rule rule, Confidence confidence) {
        var information = confidence == Confidence.INFORMATION;
        return switch (rule) {
            case CORRUPT_REGION -> "Unreadable regions";
            case UNAVAILABLE_REGION -> "Regions not loaded";
            case EMPTY_CELL -> "Cells without ground";
            case INVALID_LAYER -> "Layers that cannot be saved";
            case DUPLICATE_HEIGHT -> "Duplicate heights";
            case LAYER_ORDER -> "Layers out of order";
            case STEEP_CLIMB -> "Walls you can walk up";
            case LONG_DROP -> "Long drops";
            case REGION_SEAM -> "Region border jumps";
            case ONE_WAY_TRANSITION -> information ? "Passages from closed spots" : "One-way passages";
            case ONE_WAY_TRAP -> information ? "Traps in steep terrain" : "Traps";
            case OBSTACLE_ENTRY -> information ? "Obstacles in steep terrain" : "Edges into obstacles";
            case SHORT_DETOUR -> "One-way edges with a way around";
            case OTHER_FLOOR_RETURN -> "Way back lands on another floor";
            case ISOLATED_LAYER -> "Unreachable layers";
            case CLIENT_SURFACE_MISMATCH -> "Off the client ground";
            case CLIENT_GEOMETRY_INCOMPLETE -> "Client ground missing";
        };
    }

    public static String help(Rule rule, Confidence confidence) {
        var information = confidence == Confidence.INFORMATION;
        var cells = GeoDataScanner.RETURN_RADIUS / 16;
        return switch (rule) {
            case CORRUPT_REGION -> "The region file fails validation and cannot be used by the server.";
            case UNAVAILABLE_REGION -> "Part of the scanned area has no loaded region. Cells there were not checked.";
            case EMPTY_CELL -> "The cell stores no layer. Characters cannot enter it.";
            case INVALID_LAYER -> "An edited layer has a height or directions that the file format cannot store.";
            case DUPLICATE_HEIGHT ->
                "Two layers of one cell have the same height. The server never reaches the second.";
            case LAYER_ORDER -> "Layers are not stored highest first, so the server can pick the wrong floor.";
            case STEEP_CLIMB ->
                "An open edge leads at least " + GeoDataScanner.WALL_CLIMB
                        + " units up. Slopes rise less, so this is a wall that characters can walk up.";
            case LONG_DROP ->
                "An open edge leads down by at least the drop threshold. Usually a normal fall from a ledge.";
            case REGION_SEAM ->
                "Heights jump across a region border on an open edge. Generated region files end in a flat strip, so"
                        + " most borders do this.";
            case ONE_WAY_TRANSITION ->
                information
                        ? "A one-way edge out of a spot that only a few cells lead to, such as a strip along a"
                                + " parapet. Characters rarely get here."
                        : "Characters cross the edge one way and find no way back within " + cells
                                + " cells. Usually a wall closed on one side only, which lets characters walk through"
                                + " it.";
            case ONE_WAY_TRAP ->
                information
                        ? "A closed area that characters cannot leave, reached only from steep ground that leads down."
                                + " Few characters get here."
                        : "Characters walk from open ground into a closed area and can never leave it.";
            case OBSTACLE_ENTRY ->
                information
                        ? "An obstacle cell reached only from steep ground that leads down. Few characters get here."
                        : "An open edge leads from open ground into a cell with no open direction. A character that"
                                + " enters it is stuck.";
            case SHORT_DETOUR ->
                "The edge is one-way, but the way back is within " + cells
                        + " cells. Usually just the thickness of a wall.";
            case OTHER_FLOOR_RETURN ->
                "Moving back from the neighbor selects another floor of this cell. Normal for stacked floors.";
            case ISOLATED_LAYER -> "An open layer that no move leads into or out of. It is never reachable.";
            case CLIENT_SURFACE_MISMATCH ->
                "At least three neighboring cells are further than the tolerance from walkable client ground at every"
                        + " point. Layers without client geometry nearby are not compared.";
            case CLIENT_GEOMETRY_INCOMPLETE ->
                "Client geometry for this area is missing or truncated, so it was not compared.";
        };
    }
}

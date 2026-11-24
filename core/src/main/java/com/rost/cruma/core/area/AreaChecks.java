package com.rost.cruma.core.area;

import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.spawn.Polygons;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import com.rost.cruma.core.spawn.SpawnChecks.Severity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class AreaChecks {
    public static final int MOST_BANNED = 5;

    public static final int BORDER_SLACK = 16;

    private AreaChecks() {}

    public enum Kind {
        BAD_NAME(Severity.ERROR, "Name cannot be read", null),
        NO_TYPE(Severity.ERROR, "No type", null),
        FEW_CORNERS(Severity.ERROR, "Fewer than 3 corners", null),
        HEIGHTS_SWAPPED(Severity.ERROR, "Bottom above top", null),
        MAP_MISMATCH(Severity.ERROR, "map_no is not the center's tile", "Set map_no"),
        OUTSIDE_TABLE(Severity.ERROR, "Center outside the server's tiles", null),
        BANNED_OUTSIDE(Severity.ERROR, "Excluded part reaches outside", null),
        TOO_MANY_BANNED(Severity.ERROR, "Too many excluded parts", null),
        BAD_UNIT_TICK(Severity.ERROR, "unit_tick below 1", null),
        BAD_SKILL_PROB(Severity.ERROR, "skill_prob outside 0 to 100", null),
        OTHER_TILE(Severity.WARNING, "Reaches another tile", "Split at tile borders"),
        SAME_NAME(Severity.WARNING, "Name used twice", null),
        CROSSING_SIDES(Severity.WARNING, "Sides cross", null),
        LOST_UNIT_TICK(Severity.WARNING, "unit_tick before type", "Move unit_tick after type"),
        NO_SKILL(Severity.WARNING, "No skill to cast", null),
        NO_DESTINATION(Severity.WARNING, "Nowhere to teleport to", null),
        FLAT(Severity.WARNING, "Top equals bottom", null),
        UNKNOWN_TYPE(Severity.WARNING, "Unknown type", null),
        TYPE_NEW_TO_FILE(Severity.WARNING, "Type new to this file", null);

        private final Severity severity;
        private final String title;
        private final @Nullable String fixLabel;

        Kind(Severity severity, String title, @Nullable String fixLabel) {
            this.severity = severity;
            this.title = title;
            this.fixLabel = fixLabel;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getTitle() {
            return title;
        }

        public @Nullable String getFixLabel() {
            return fixLabel;
        }

        public boolean hasFix() {
            return fixLabel != null;
        }
    }

    public record Finding(Kind kind, int area, String name, String detail) {
        public Severity severity() {
            return kind.getSeverity();
        }

        public String title() {
            return kind.getTitle();
        }

        public boolean isError() {
            return kind.getSeverity() == Severity.ERROR;
        }

        public boolean hasFix() {
            return kind.hasFix();
        }

        public String key() {
            return kind + "|" + area;
        }

        public Issue toIssue() {
            return new Issue(severity(), "area|" + key(), "area " + name, title() + ": " + detail);
        }
    }

    public static List<Finding> check(State state, int index, Set<String> fileTypes) {
        var area = state.area(index);
        if (area == null) {
            return List.of();
        }
        var findings = new ArrayList<Finding>();
        checkArea(area, countNames(state), fileTypes, findings);
        return findings;
    }

    public static List<Finding> findAll(State state, Set<String> fileTypes) {
        var names = countNames(state);
        var findings = new ArrayList<Finding>();
        for (var area : state.areas()) {
            if (area != null) {
                checkArea(area, names, fileTypes, findings);
            }
        }
        return findings;
    }

    public static List<Finding> findIntroduced(State saved, State now, Set<String> fileTypes) {
        if (saved == now) {
            return List.of();
        }
        var savedNames = countNames(saved);
        var nowNames = countNames(now);
        var before = new ArrayList<Finding>();
        var after = new ArrayList<Finding>();
        for (var i = 0; i < now.areas().size(); i++) {
            var area = now.areas().get(i);
            var old = saved.area(i);
            if (area != null && area != old && !area.equals(old)) {
                if (old != null) {
                    checkArea(old, savedNames, fileTypes, before);
                }
                checkArea(area, nowNames, fileTypes, after);
            }
        }
        var known = new HashSet<String>();
        for (var finding : before) {
            known.add(finding.key());
        }
        var fresh = new ArrayList<Finding>();
        for (var finding : after) {
            if (!known.contains(finding.key())) {
                fresh.add(finding);
            }
        }
        return fresh;
    }

    public static State fix(State state, Finding finding) {
        var area = state.area(finding.area());
        if (area == null) {
            return state;
        }
        return switch (finding.kind()) {
            case OTHER_TILE -> AreaEdits.splitAtTileBorders(state, finding.area());
            case MAP_MISMATCH ->
                AreaEdits.setSetting(
                        state,
                        finding.area(),
                        "map_no",
                        AreaDataWriter.formatMap(area.centerTileX(), area.centerTileY()));
            case LOST_UNIT_TICK -> {
                var tick = area.get("unit_tick");
                var next = AreaEdits.removeSetting(state, finding.area(), "unit_tick");
                yield tick == null ? state : AreaEdits.setSetting(next, finding.area(), "unit_tick", tick);
            }
            default -> state;
        };
    }

    public static State fixAll(State state, Kind kind, Set<String> fileTypes) {
        if (!kind.hasFix()) {
            return state;
        }
        var next = state;
        for (var finding : findAll(state, fileTypes)) {
            if (finding.kind() != kind) {
                continue;
            }
            for (var still : check(next, finding.area(), fileTypes)) {
                if (still.kind() == kind) {
                    next = fix(next, still);
                    break;
                }
            }
        }
        return next;
    }

    private static Map<String, Integer> countNames(State state) {
        var names = new HashMap<String, Integer>();
        for (var area : state.areas()) {
            if (area != null) {
                names.merge(area.name(), 1, Integer::sum);
            }
        }
        return names;
    }

    private static void checkArea(
            ServerArea area, Map<String, Integer> names, Set<String> fileTypes, List<Finding> findings) {
        var name = area.name();
        var index = area.index();
        if (name.isBlank()) {
            findings.add(new Finding(Kind.BAD_NAME, index, name, "The area has no name, so nothing can find it."));
        } else if (name.contains("[") || name.contains("]")) {
            findings.add(new Finding(
                    Kind.BAD_NAME, index, name, "A name cannot hold [ or ], as the file ends the name at ]."));
        } else if (names.getOrDefault(name, 0) > 1) {
            findings.add(new Finding(
                    Kind.SAME_NAME,
                    index,
                    name,
                    "Another area has this name, and a search by name finds only the last of them."));
        }
        checkType(area, fileTypes, findings);
        checkSettings(area, findings);
        checkShape(area, findings);
    }

    private static void checkType(ServerArea area, Set<String> fileTypes, List<Finding> findings) {
        var word = area.typeWord();
        if (word.isEmpty()) {
            findings.add(new Finding(
                    Kind.NO_TYPE, area.index(), area.name(), "Without type= the server does not know what it does."));
            return;
        }
        var type = area.type();
        var inFile = fileTypes.contains(word);
        if (type == null) {
            if (!inFile) {
                findings.add(new Finding(
                        Kind.UNKNOWN_TYPE,
                        area.index(),
                        area.name(),
                        "Type " + word + " is not in this file and this tool does not know it. Check that your server"
                                + " reads it."));
            }
            return;
        }
        if (!inFile && !fileTypes.isEmpty()) {
            findings.add(new Finding(
                    Kind.TYPE_NEW_TO_FILE,
                    area.index(),
                    area.name(),
                    "No other area in this " + AreaDataFile.FILE_NAME + " has type " + word + ". A server of another"
                            + " version may not know it, and then it cannot read the area."));
        }
        if ((type == AreaType.POISON || type == AreaType.INSTANT_SKILL)
                && !area.hasSetting("skill_name")
                && !area.hasSetting("skill_list")) {
            findings.add(new Finding(
                    Kind.NO_SKILL,
                    area.index(),
                    area.name(),
                    "A " + word + " area casts its skill_name or skill_list, and it has neither."));
        }
        if (type == AreaType.TELEPORT && !area.hasSetting("teleport_points") && !area.hasSetting("teleport_range")) {
            findings.add(new Finding(
                    Kind.NO_DESTINATION,
                    area.index(),
                    area.name(),
                    "It has neither teleport_points nor teleport_range, so it sends nobody anywhere."));
        }
    }

    private static void checkSettings(ServerArea area, List<Finding> findings) {
        var tick = area.effectiveUnitTick();
        if (tick < 1) {
            findings.add(new Finding(
                    Kind.BAD_UNIT_TICK,
                    area.index(),
                    area.name(),
                    "The server divides by unit_tick " + tick + ", so it must be 1 or more."));
        }
        if (area.hasLostUnitTick()) {
            findings.add(new Finding(
                    Kind.LOST_UNIT_TICK,
                    area.index(),
                    area.name(),
                    "type= comes after unit_tick= and sets its own tick, so the server uses " + tick + "."));
        }
        if (area.hasSetting("skill_prob")) {
            var chance = area.getInt("skill_prob", ServerArea.DEFAULT_SKILL_PROB);
            if (chance < 0 || chance > 100) {
                findings.add(new Finding(
                        Kind.BAD_SKILL_PROB,
                        area.index(),
                        area.name(),
                        "skill_prob is " + chance + ", but it is a chance from 0 to 100."));
            }
        }
    }

    private static void checkShape(ServerArea area, List<Finding> findings) {
        var points = area.points();
        if (points.size() < 3) {
            findings.add(new Finding(
                    Kind.FEW_CORNERS,
                    area.index(),
                    area.name(),
                    "It has " + points.size() + (points.size() == 1 ? " corner" : " corners") + ", and needs 3."));
            return;
        }
        var swapped = false;
        for (var point : points) {
            if (point.bottom() > point.top()) {
                swapped = true;
                findings.add(new Finding(
                        Kind.HEIGHTS_SWAPPED,
                        area.index(),
                        area.name(),
                        "A corner's bottom " + point.bottom() + " is above its top " + point.top() + "."));
                break;
            }
        }
        if (!swapped && area.bottom() == area.top()) {
            findings.add(new Finding(
                    Kind.FLAT,
                    area.index(),
                    area.name(),
                    "Its bottom and top are both " + area.top() + ", so only that exact height is inside."));
        }
        var tileX = area.centerTileX();
        var tileY = area.centerTileY();
        var tile = AreaTiles.format(tileX, tileY);
        if (!AreaTiles.isInTable(tileX, tileY)) {
            findings.add(new Finding(
                    Kind.OUTSIDE_TABLE,
                    area.index(),
                    area.name(),
                    "Its center is on tile " + tile + ", and the server holds tiles 10_10 to 29_26 only."));
        } else if (area.hasMap() && (area.mapX() != tileX || area.mapY() != tileY)) {
            findings.add(new Finding(
                    Kind.MAP_MISMATCH,
                    area.index(),
                    area.name(),
                    "map_no is " + AreaTiles.format(area.mapX(), area.mapY()) + " but the center is on tile " + tile
                            + ", so the server refuses the area."));
        }
        if (reachesOtherTile(area, tileX, tileY)) {
            findings.add(new Finding(
                    Kind.OTHER_TILE,
                    area.index(),
                    area.name(),
                    "The server files it under tile " + tile + " only, so the part on another tile does nothing."));
        }
        if (!area.isWater() && Polygons.hasCrossingSides(points)) {
            findings.add(new Finding(
                    Kind.CROSSING_SIDES,
                    area.index(),
                    area.name(),
                    "Its sides cross, so the inside is not what the outline suggests."));
        }
        var banned = area.banned();
        if (banned.size() > MOST_BANNED) {
            findings.add(new Finding(
                    Kind.TOO_MANY_BANNED,
                    area.index(),
                    area.name(),
                    "It has " + banned.size() + " excluded parts, and the server takes " + MOST_BANNED + " at most."));
        }
        for (var i = 0; i < banned.size(); i++) {
            var outside = false;
            for (var point : banned.get(i)) {
                if (!area.containsXy(point.x(), point.y())) {
                    outside = true;
                    break;
                }
            }
            if (outside) {
                findings.add(new Finding(
                        Kind.BANNED_OUTSIDE,
                        area.index(),
                        area.name(),
                        "Excluded part " + (i + 1) + " reaches outside the area, so the server drops the area."));
                break;
            }
        }
    }

    private static boolean reachesOtherTile(ServerArea area, int tileX, int tileY) {
        var minX = AreaTiles.originX(tileX) - BORDER_SLACK;
        var minY = AreaTiles.originY(tileY) - BORDER_SLACK;
        var maxX = AreaTiles.originX(tileX) + AreaTiles.SIZE + BORDER_SLACK;
        var maxY = AreaTiles.originY(tileY) + AreaTiles.SIZE + BORDER_SLACK;
        return area.minX() < minX || area.minY() < minY || area.maxX() > maxX || area.maxY() > maxY;
    }
}

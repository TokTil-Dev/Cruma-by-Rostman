package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.AreaSteps.findArea;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.kindWord;
import static com.rost.cruma.viewer.agent.spawn.SpawnEditor.number;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.area.AreaChecks;
import com.rost.cruma.core.area.AreaChecks.Kind;
import com.rost.cruma.core.area.AreaCopies;
import com.rost.cruma.core.area.AreaDataFile;
import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.area.AreaEdits;
import com.rost.cruma.core.area.AreaPoint;
import com.rost.cruma.core.area.AreaTiles;
import com.rost.cruma.core.area.AreaType;
import com.rost.cruma.core.area.ServerArea;
import com.rost.cruma.core.spawn.SpawnEdits;
import com.rost.cruma.core.spawn.SpawnHealth;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnReview.Geodata;
import com.rost.cruma.core.spawn.TerritoryPoint;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

final class AreaEditor {
    static final String[] OPS = {
        "add_area",
        "set",
        "remove_setting",
        "rename",
        "note",
        "type",
        "set_corners",
        "set_heights",
        "fit_to_ground",
        "move",
        "add_part",
        "remove_part",
        "add_point",
        "remove_point",
        "duplicate",
        "paste",
        "remove",
        "fix",
        "undo",
        "redo"
    };

    private static final int NEW_HALF = 400;
    private static final int BELOW_GROUND = 200;
    private static final int ABOVE_GROUND = 400;
    private static final int WATER_DEPTH = 400;
    private static final int PASTE_ABOVE_GROUND = 100;
    private static final int FLOOR_SEARCH = 64;
    private static final int TOP_OF_THE_WORLD = 20000;
    private static final int GROUND_STEP = 256;
    private static final String WORD_RULE = "letters A to Z, digits and _, starting with a letter";

    private final ToolArgs args;
    private final State state;
    private final AreaDataFile file;
    private final Set<String> types;
    private final @Nullable Target target;
    private final @Nullable Geodata geodata;
    private final Floors floors;

    private boolean guessedGround;

    private boolean clientGround;

    AreaEditor(
            ToolArgs args,
            State state,
            AreaDataFile file,
            Set<String> types,
            @Nullable Target target,
            @Nullable Geodata geodata,
            Floors floors) {
        this.args = args;
        this.state = state;
        this.file = file;
        this.types = types;
        this.target = target;
        this.geodata = geodata;
        this.floors = floors;
    }

    @FunctionalInterface
    interface Floors {
        double findBelow(double x, double y, double z);
    }

    record Plan(String label, UnaryOperator<State> edit, int focus, JSONObject said) {}

    static String typeWord(String text, Set<String> types) throws ToolProblem {
        var word = text.strip();
        if (!AreaEdits.isValidWord(word) || !AreaEdits.isValidValue("type", word)) {
            throw new ToolProblem("A type is a word of " + WORD_RULE + ", such as peace_zone, not \"" + text + "\".");
        }
        if (types.contains(word) || AreaType.find(word) != null) {
            return word;
        }
        for (var listed : types) {
            if (listed.equalsIgnoreCase(word)) {
                return listed;
            }
        }
        for (var type : AreaType.values()) {
            if (type.getKeyword().equalsIgnoreCase(word)) {
                return type.getKeyword();
            }
        }
        return word;
    }

    Plan plan() throws ToolProblem {
        var made = planAction();
        if (guessedGround) {
            made.said()
                    .put(
                            "ground",
                            "from the height of the target, as the geodata and the client's map have no floor there:"
                                    + " the area or point may hang in the air or lie under the ground. Give bottom"
                                    + " and top, or a point on the ground.");
        } else if (clientGround) {
            made.said().put("ground_from", "the floors of the client's map, as the geodata has none there");
        }
        return made;
    }

    private Plan planAction() throws ToolProblem {
        var op = args.getRequired("action");
        return switch (op) {
            case "add_area" -> addArea();
            case "set" -> set();
            case "remove_setting" -> removeSetting();
            case "rename" -> rename();
            case "note" -> note();
            case "type" -> changeType(area(), args.getRequired("type"));
            case "set_corners" -> setCorners();
            case "set_heights" -> setHeights();
            case "fit_to_ground" -> fitToGround();
            case "move" -> move();
            case "add_part" -> addPart();
            case "remove_part" -> removePart();
            case "add_point" -> addPoint();
            case "remove_point" -> removePoint();
            case "duplicate" -> duplicate();
            case "paste" -> paste();
            case "remove" -> remove();
            case "fix" -> fix();
            default -> throw new ToolProblem("action " + op + " is not one of " + String.join(", ", OPS) + ".");
        };
    }

    private Plan addArea() throws ToolProblem {
        var type = typeWord(args.getRequired("type"), types);
        var water = AreaType.WATER.getKeyword().equals(type);
        List<TerritoryPoint> points;
        double middleX;
        double middleY;
        if (args.has("corners")) {
            var corners = readCorners();
            middleX = corners.stream().mapToInt(corner -> corner[0]).average().orElse(0);
            middleY = corners.stream().mapToInt(corner -> corner[1]).average().orElse(0);
            var heights = newHeights((int) Math.round(middleX), (int) Math.round(middleY), water);
            points = withHeights(corners, heights[0], heights[1]);
        } else {
            if (target == null) {
                throw new ToolProblem("add_area needs a target for the middle of a square, or corners.");
            }
            var x = Math.round(target.x());
            var y = Math.round(target.y());
            var half = (int) Math.round(args.getNumber("radius", NEW_HALF));
            if (half < 1) {
                throw new ToolProblem("radius must be above 0.");
            }
            var heights = newHeights(x, y, water);
            points = SpawnEdits.square(x, y, half, heights[0], heights[1]);
            middleX = x;
            middleY = y;
        }
        var asked = args.getText("name");
        var name = asked == null
                ? AreaEdits.newName(state, type, AreaTiles.tileX(middleX), AreaTiles.tileY(middleY))
                : freeName(asked);
        var index = state.nextIndex();
        var said = said(index, name);
        said.put("type", type);
        if (AreaType.find(type) == null) {
            said.put(
                    "settings",
                    "none of a type: this tool does not know " + type + ", so the area has its name, its"
                            + " tile, its type and its shape only");
        }
        return new Plan(
                "Add area " + name, current -> AreaEdits.newArea(current, file, type, name, points), index, said);
    }

    private Plan set() throws ToolProblem {
        var area = area();
        var key = args.getRequired("key").strip();
        var value = args.getRequired("value").strip();
        switch (key) {
            case "name" -> {
                return rename(area, value);
            }
            case "type" -> {
                return changeType(area, value);
            }
            case "range", "water_range" ->
                throw new ToolProblem(key + " is the outline: set_corners and set_heights change it.");
            case "banned_range" -> throw new ToolProblem("add_part and remove_part change the excluded parts.");
            case "map_no" -> throw new ToolProblem("map_no follows the middle of the area by itself.");
            default -> {
            }
        }
        if (!AreaEdits.isValidWord(key)) {
            throw new ToolProblem("A key is a word of " + WORD_RULE + ", such as skill_name, not \"" + key + "\".");
        }
        if (!AreaEdits.isValidValue(key, value)) {
            throw new ToolProblem(
                    "The file cannot read " + key + "=" + value + ": a value has no spaces or tabs outside braces.");
        }
        var index = area.index();
        var said = said(index, area.name());
        var old = area.get(key);
        said.put("changed", key + (old == null ? " added: " + value : " " + old + " → " + value));
        return new Plan(
                "Set " + key + " of " + area.name(),
                current -> AreaEdits.setSetting(current, index, key, value),
                index,
                said);
    }

    private Plan removeSetting() throws ToolProblem {
        var area = area();
        var key = args.getRequired("key").strip();
        switch (key) {
            case "name", "type" -> throw new ToolProblem("An area keeps its " + key + ". Change it with set.");
            case "range", "water_range", "map_no" -> throw new ToolProblem("An area keeps its " + key + ".");
            case "banned_range" -> throw new ToolProblem("remove_part takes excluded parts away one by one.");
            default -> {
            }
        }
        if (!area.hasSetting(key)) {
            throw new ToolProblem("Area " + area.name() + " has no " + key + ".");
        }
        var index = area.index();
        var said = said(index, area.name());
        said.put("removed", key);
        return new Plan(
                "Remove " + key + " of " + area.name(),
                current -> AreaEdits.removeSetting(current, index, key),
                index,
                said);
    }

    private Plan rename() throws ToolProblem {
        return rename(area(), args.getRequired("name"));
    }

    private Plan rename(ServerArea area, String text) throws ToolProblem {
        var name = text.strip();
        if (name.equals(area.name())) {
            throw new ToolProblem("Area " + name + " has this name already.");
        }
        var free = freeName(name);
        var index = area.index();
        var said = said(index, free);
        said.put("renamed", area.name() + " → " + free);
        return new Plan("Rename area " + area.name(), current -> AreaEdits.rename(current, index, free), index, said);
    }

    private Plan note() throws ToolProblem {
        var area = area();
        var text = AreaEdits.cleanNote(args.getText("note", ""));
        var index = area.index();
        var said = said(index, area.name());
        said.put("note", text.isEmpty() ? "removed" : text);
        return new Plan(
                "Change note of " + area.name(), current -> AreaEdits.setNote(current, index, text), index, said);
    }

    private Plan changeType(ServerArea area, String text) throws ToolProblem {
        var type = typeWord(text, types);
        var index = area.index();
        var said = said(index, area.name());
        said.put("type", area.typeWord() + " → " + type);
        said.put("kept", "The other settings stay. Those the new type does not read show under Other settings.");
        return new Plan(
                "Change type of " + area.name(),
                current -> AreaEdits.setSetting(current, index, "type", type),
                index,
                said);
    }

    private Plan setCorners() throws ToolProblem {
        var area = area();
        var corners = readCorners();
        var bottom = args.getInteger("bottom", area.bottom());
        var top = args.getInteger("top", area.top());
        requireHeights(bottom, top);
        var points = withHeights(corners, bottom, top);
        var index = area.index();
        var said = said(index, area.name());
        said.put("corners", points.size());
        if (area.isWater()) {
            said.put("water", "Water stays a box: the bounds of the corners given.");
        }
        return new Plan(
                "Set corners of " + area.name(), current -> AreaEdits.setPoints(current, index, points), index, said);
    }

    private Plan setHeights() throws ToolProblem {
        var area = area();
        if (!args.has("bottom") && !args.has("top")) {
            throw new ToolProblem("set_heights needs bottom, top or both.");
        }
        var bottom = args.has("bottom") ? Integer.valueOf(args.getInteger("bottom", 0)) : null;
        var top = args.has("top") ? Integer.valueOf(args.getInteger("top", 0)) : null;
        requireHeights(bottom == null ? area.bottom() : bottom, top == null ? area.top() : top);
        var index = area.index();
        var said = said(index, area.name());
        said.put("heights", (bottom == null ? area.bottom() : bottom) + " to " + (top == null ? area.top() : top));
        return new Plan(
                "Set heights of " + area.name(),
                current -> AreaEdits.setHeights(current, index, bottom, top),
                index,
                said);
    }

    private Plan fitToGround() throws ToolProblem {
        var area = area();
        if (area.isWater()) {
            throw new ToolProblem("Water keeps its surface. Change its heights with set_heights.");
        }
        var range = groundRange(area);
        var low = range[0] - BELOW_GROUND;
        var high = range[1] + ABOVE_GROUND;
        var index = area.index();
        var said = said(index, area.name());
        said.put("ground", range[0] + " to " + range[1]);
        said.put("heights", low + " to " + high);
        return new Plan(
                "Fit heights of " + area.name(),
                current -> AreaEdits.setHeights(current, index, low, high),
                index,
                said);
    }

    private Plan move() throws ToolProblem {
        var area = area();
        int shiftX;
        int shiftY;
        if (target != null) {
            shiftX = (int) Math.round(target.x() - (area.minX() + (double) area.maxX()) / 2);
            shiftY = (int) Math.round(target.y() - (area.minY() + (double) area.maxY()) / 2);
        } else if (args.has("dx") || args.has("dy") || args.has("dz")) {
            shiftX = args.getInteger("dx", 0);
            shiftY = args.getInteger("dy", 0);
        } else {
            throw new ToolProblem("move needs a target for the middle of the area, or dx, dy and dz.");
        }
        var shiftZ = args.getInteger("dz", 0);
        var index = area.index();
        var said = said(index, area.name());
        said.put("moved", shiftX + "," + shiftY + "," + shiftZ);
        if (!area.teleportPoints().isEmpty() || !area.restartPoints().isEmpty()) {
            said.put("points", "Teleport and restart points stay where they are, as in a drag of the panel.");
        }
        return new Plan(
                "Move area " + area.name(),
                current -> {
                    var next = AreaEdits.move(current, index, shiftX, shiftY);
                    var moved = next.area(index);
                    return shiftZ == 0 || moved == null ? next : next.withArea(AreaEdits.raised(moved, shiftZ));
                },
                index,
                said);
    }

    private Plan addPart() throws ToolProblem {
        var area = area();
        if (area.banned().size() >= AreaChecks.MOST_BANNED) {
            throw new ToolProblem("The server takes " + AreaChecks.MOST_BANNED + " excluded parts at most, and area "
                    + area.name() + " has them.");
        }
        var part = withHeights(readCorners(), area.bottom(), area.top());
        var parts = new ArrayList<>(area.banned());
        parts.add(part);
        var index = area.index();
        var said = said(index, area.name());
        said.put("part", parts.size() - 1);
        return new Plan(
                "Add excluded part to " + area.name(),
                current -> AreaEdits.setBanned(current, index, parts),
                index,
                said);
    }

    private Plan removePart() throws ToolProblem {
        var area = area();
        var number = pick(area.banned().size(), "excluded parts");
        var parts = new ArrayList<>(area.banned());
        parts.remove(number);
        var index = area.index();
        var said = said(index, area.name());
        said.put("removed_part", number);
        return new Plan(
                "Remove excluded part of " + area.name(),
                current -> AreaEdits.setBanned(current, index, parts),
                index,
                said);
    }

    private Plan addPoint() throws ToolProblem {
        var area = area();
        if (target == null) {
            throw new ToolProblem("add_point needs a target: where the point goes.");
        }
        var restart = args.readFlag("restart", false);
        var x = Math.round(target.x());
        var y = Math.round(target.y());
        var place = new AreaPoint(x, y, groundAt(x, y, target.z()));
        var points = new ArrayList<>(restart ? area.restartPoints() : area.teleportPoints());
        points.add(place);
        var index = area.index();
        var said = said(index, area.name());
        said.put(restart ? "restart_point" : "teleport_point", place.x() + "," + place.y() + "," + place.z());
        said.put("number", points.size() - 1);
        return new Plan(
                (restart ? "Add restart point to " : "Add teleport point to ") + area.name(),
                current -> restart
                        ? AreaEdits.setRestartPoints(current, index, points)
                        : AreaEdits.setTeleportPoints(current, index, points),
                index,
                said);
    }

    private Plan removePoint() throws ToolProblem {
        var area = area();
        var restart = args.readFlag("restart", false);
        var points = new ArrayList<>(restart ? area.restartPoints() : area.teleportPoints());
        var number = pick(points.size(), restart ? "restart points" : "teleport points");
        points.remove(number);
        var index = area.index();
        var said = said(index, area.name());
        said.put(restart ? "removed_restart_point" : "removed_teleport_point", number);
        return new Plan(
                (restart ? "Remove restart point of " : "Remove teleport point of ") + area.name(),
                current -> restart
                        ? AreaEdits.setRestartPoints(current, index, points)
                        : AreaEdits.setTeleportPoints(current, index, points),
                index,
                said);
    }

    private Plan duplicate() throws ToolProblem {
        var area = area();
        if (target == null) {
            throw new ToolProblem("duplicate needs a target: the middle of the copy.");
        }
        var shiftX = (int) Math.round(target.x() - (area.minX() + (double) area.maxX()) / 2);
        var shiftY = (int) Math.round(target.y() - (area.minY() + (double) area.maxY()) / 2);
        var original = area.index();
        var index = state.nextIndex();
        var said = new JSONObject();
        said.put("copy_of", area.name());
        int shiftZ;
        if (args.has("dz")) {
            shiftZ = args.getInteger("dz", 0);
        } else {
            var from = floorAt(
                    (int) Math.round(area.centerX()),
                    (int) Math.round(area.centerY()),
                    Float.valueOf(area.bottom() + BELOW_GROUND));
            var to = floorAt(Math.round(target.x()), Math.round(target.y()), target.z());
            shiftZ = from == null || to == null ? 0 : to - from;
            if (from == null || to == null) {
                clientGround = false;
                said.put("heights", "kept: the ground is not known. Give dz to raise or lower the copy.");
            }
        }
        said.put("moved", shiftX + "," + shiftY + "," + shiftZ);
        var name = AreaEdits.freeName(state, area.name());
        said.put("area", number(index));
        said.put("name", name);
        return new Plan(
                "Duplicate area " + area.name(),
                current -> {
                    var next = AreaEdits.duplicate(current, original, shiftX, shiftY);
                    var copy = next.area(index);
                    return copy == null ? next : next.withArea(AreaEdits.raised(copy, shiftZ));
                },
                index,
                said);
    }

    private Plan paste() throws ToolProblem {
        var text = args.getRequired("text");
        if (!text.contains("area_begin")) {
            throw new ToolProblem("text holds no area. Give the lines from area_begin to area_end, as area_find with"
                    + " area gives them.");
        }
        if (target == null) {
            throw new ToolProblem("paste needs a target: the middle of the pasted areas.");
        }
        var x = Math.round(target.x());
        var y = Math.round(target.y());
        var ground = groundAt(x, y, target.z());
        var pasted = AreaCopies.paste(state, file, text, x, y, ground + PASTE_ABOVE_GROUND);
        if (pasted == null) {
            throw new ToolProblem("text holds no area with three corners or more.");
        }
        var result = pasted.state();
        var first = pasted.indexes().getFirst();
        var said = new JSONObject();
        var names = new ArrayList<String>();
        for (var index : pasted.indexes()) {
            var area = result.area(index);
            names.add(number(index) + " " + (area == null ? "" : area.name()));
        }
        said.put("pasted", names);
        return new Plan(pasted.indexes().size() == 1 ? "Paste area" : "Paste areas", unused -> result, first, said);
    }

    private Plan remove() throws ToolProblem {
        var area = area();
        var index = area.index();
        var said = said(index, area.name());
        said.put("removed", true);
        return new Plan("Delete area " + area.name(), current -> AreaEdits.delete(current, index), -1, said);
    }

    private Plan fix() throws ToolProblem {
        var kind = kind(args.getRequired("kind"));
        if (!kind.hasFix()) {
            throw new ToolProblem(kind.getTitle() + " has no fix of its own. area_check says what is wrong, and"
                    + " area_edit changes it.");
        }
        if (args.readFlag("all", false)) {
            var count = AreaChecks.findAll(state, types).stream()
                    .filter(finding -> finding.kind() == kind)
                    .count();
            if (count == 0) {
                throw new ToolProblem("No area has the finding " + kind.getTitle() + ".");
            }
            var said = new JSONObject();
            said.put("fixed", count);
            said.put("fix", kind.getFixLabel());
            return new Plan(
                    "Fix all: " + kind.getFixLabel(), current -> AreaChecks.fixAll(current, kind, types), -1, said);
        }
        var area = area();
        var finding = AreaChecks.check(state, area.index(), types).stream()
                .filter(found -> found.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new ToolProblem("Area " + area.name() + " has no finding " + kind.getTitle() + "."));
        var index = area.index();
        var said = said(index, area.name());
        said.put("fix", kind.getFixLabel());
        return new Plan(
                kind.getFixLabel() + " (" + area.name() + ")",
                current -> AreaChecks.fix(current, finding),
                index,
                said);
    }

    private ServerArea area() throws ToolProblem {
        var text = args.getText("area");
        if (text == null) {
            throw new ToolProblem(args.getRequired("action") + " needs area: its name, or its number such as #12.");
        }
        return findArea(state, text);
    }

    private String freeName(String text) throws ToolProblem {
        var name = text.strip();
        if (!AreaEdits.isValidName(name)) {
            throw new ToolProblem(
                    "A name uses the letters A to Z, digits and underscores, such as giran_peace_01, not \"" + text
                            + "\".");
        }
        if (state.findArea(name) != null) {
            throw new ToolProblem("Another area is already called " + name + ". Leave name out for a free one.");
        }
        return name;
    }

    private int pick(int count, String what) throws ToolProblem {
        if (!args.has("number")) {
            throw new ToolProblem("Give number: which of the " + what + ", counted from 0, as area_find lists them.");
        }
        var number = args.getInteger("number", -1);
        if (number < 0 || number >= count) {
            throw new ToolProblem(
                    count == 0
                            ? "The area has no " + what + "."
                            : "number is from 0 to " + (count - 1) + ", as the area has " + count + " " + what + ".");
        }
        return number;
    }

    private int[] newHeights(int x, int y, boolean water) throws ToolProblem {
        if (args.has("bottom") && args.has("top")) {
            var bottom = args.getInteger("bottom", 0);
            var top = args.getInteger("top", 0);
            requireHeights(bottom, top);
            return new int[] {bottom, top};
        }
        var ground = groundAt(x, y, target == null ? null : target.z());
        var bottom = args.getInteger("bottom", water ? ground - WATER_DEPTH : ground - BELOW_GROUND);
        var top = args.getInteger("top", water ? ground : ground + ABOVE_GROUND);
        requireHeights(bottom, top);
        return new int[] {bottom, top};
    }

    private List<int[]> readCorners() throws ToolProblem {
        var corners = new ArrayList<int[]>();
        for (var text : args.list("corners")) {
            var parts = text.split("[, ]+");
            try {
                corners.add(new int[] {Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip())});
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException wrong) {
                throw new ToolProblem("A corner is x,y, not \"" + text + "\".");
            }
        }
        if (corners.size() < 3) {
            throw new ToolProblem("An outline needs at least 3 corners.");
        }
        return corners;
    }

    private int groundAt(int x, int y, @Nullable Float z) throws ToolProblem {
        var floor = floorAt(x, y, z);
        if (floor != null) {
            return floor;
        }
        if (z != null) {
            guessedGround = true;
            return Math.round(z);
        }
        throw new ToolProblem("The ground at " + x + "," + y + " is not known: the geodata and the client's map have"
                + " no floor there. Give a target with a height as x,y,z, or bottom and top.");
    }

    private @Nullable Integer floorAt(int x, int y, @Nullable Float z) {
        var from = z == null ? TOP_OF_THE_WORLD : Math.round(z) + FLOOR_SEARCH;
        var data = geodata == null ? null : geodata.readArea(x - 1, y - 1, x + 1, y + 1);
        var dropped = data == null ? null : SpawnHealth.dropToFloor(new Fixed(x, y, from, 0), data);
        if (dropped != null) {
            return dropped.z();
        }
        var floor = floors.findBelow(x, y, from);
        if (!Double.isFinite(floor)) {
            return null;
        }
        clientGround = true;
        return (int) Math.round(floor);
    }

    private int[] groundRange(ServerArea area) throws ToolProblem {
        var from = area.top() + FLOOR_SEARCH;
        var data = geodata == null
                ? null
                : geodata.readArea(area.minX() - 1, area.minY() - 1, area.maxX() + 1, area.maxY() + 1);
        var range = new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE};
        if (data != null) {
            for (var point : outlineSamples(area)) {
                var dropped = SpawnHealth.dropToFloor(new Fixed(point[0], point[1], from, 0), data);
                if (dropped != null) {
                    range[0] = Math.min(range[0], dropped.z());
                    range[1] = Math.max(range[1], dropped.z());
                }
            }
        }
        if (range[0] > range[1]) {
            for (var point : outlineSamples(area)) {
                var floor = floors.findBelow(point[0], point[1], from);
                if (Double.isFinite(floor)) {
                    range[0] = Math.min(range[0], (int) Math.round(floor));
                    range[1] = Math.max(range[1], (int) Math.round(floor));
                    clientGround = true;
                }
            }
        }
        if (range[0] > range[1]) {
            throw new ToolProblem("The ground under the outline of " + area.name() + " is not known: the geodata and"
                    + " the client's map have no floor there. Give bottom and top with set_heights.");
        }
        return range;
    }

    private static List<int[]> outlineSamples(ServerArea area) {
        var samples = new ArrayList<int[]>();
        var points = area.points();
        for (var i = 0; i < points.size(); i++) {
            var a = points.get(i);
            var b = points.get((i + 1) % points.size());
            var steps = Math.max(1, (int) Math.ceil(Math.hypot(b.x() - a.x(), b.y() - a.y()) / GROUND_STEP));
            for (var step = 0; step < steps; step++) {
                samples.add(new int[] {
                    (int) Math.round(a.x() + (b.x() - a.x()) * (double) step / steps),
                    (int) Math.round(a.y() + (b.y() - a.y()) * (double) step / steps)
                });
            }
        }
        return samples;
    }

    private static Kind kind(String text) throws ToolProblem {
        var wanted = text.strip();
        for (var kind : Kind.values()) {
            if (kindWord(kind).equalsIgnoreCase(wanted) || kind.getTitle().equalsIgnoreCase(wanted)) {
                return kind;
            }
        }
        var names = new ArrayList<String>();
        for (var kind : Kind.values()) {
            if (kind.hasFix()) {
                names.add(kindWord(kind));
            }
        }
        throw new ToolProblem("kind " + text + " is not a finding with a fix: " + String.join(", ", names) + ".");
    }

    private static List<TerritoryPoint> withHeights(List<int[]> corners, int bottom, int top) {
        return corners.stream()
                .map(corner -> new TerritoryPoint(corner[0], corner[1], bottom, top))
                .toList();
    }

    private static void requireHeights(int bottom, int top) throws ToolProblem {
        if (bottom >= top) {
            throw new ToolProblem("The bottom " + bottom + " must lie below the top " + top + ".");
        }
    }

    private static JSONObject said(int index, String name) {
        var said = new JSONObject();
        said.put("area", number(index));
        said.put("name", name);
        return said;
    }
}

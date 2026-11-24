package com.rost.cruma.viewer.agent.spawn;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.spawn.MakerAiCatalog;
import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcPosDocument.Batch;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnArea;
import com.rost.cruma.core.spawn.SpawnBatchEdits;
import com.rost.cruma.core.spawn.SpawnCopies;
import com.rost.cruma.core.spawn.SpawnCopies.Copy;
import com.rost.cruma.core.spawn.SpawnEdits;
import com.rost.cruma.core.spawn.SpawnEdits.Carried;
import com.rost.cruma.core.spawn.SpawnHealth;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.core.spawn.SpawnNames;
import com.rost.cruma.core.spawn.SpawnNpc;
import com.rost.cruma.core.spawn.SpawnPosition;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnReview.Geodata;
import com.rost.cruma.core.spawn.SpawnTerritory;
import com.rost.cruma.core.spawn.TerritoryPoint;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.spawn.AreaEditor.Floors;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

final class SpawnEditor {
    static final String[] OPS = {
        "add_npc",
        "change_npc",
        "remove_npc",
        "add_maker",
        "change_maker",
        "remove_maker",
        "add_zone",
        "move_zone",
        "change_zone",
        "remove_zone",
        "duplicate_maker",
        "paste_maker",
        "remove_unused_zones",
        "bookmark",
        "unbookmark",
        "batch",
        "undo",
        "redo"
    };

    private static final int FULL_TURN = 65536;
    private static final int NEW_ZONE_HALF = 400;
    private static final int BELOW_GROUND = 200;
    private static final int ABOVE_GROUND = 600;
    private static final int FLOOR_SEARCH = 64;
    private static final int TOP_OF_THE_WORLD = 20000;
    private static final int SHOWN_NAMES = 5;

    private final ToolArgs args;
    private final State state;
    private final NpcDataTable npcs;
    private final @Nullable Target target;
    private final @Nullable SpawnArea area;
    private final @Nullable Geodata geodata;
    private final Floors floors;

    private boolean clientGround;

    SpawnEditor(
            ToolArgs args,
            State state,
            NpcDataTable npcs,
            @Nullable Target target,
            @Nullable SpawnArea area,
            @Nullable Geodata geodata,
            Floors floors) {
        this.args = args;
        this.state = state;
        this.npcs = npcs;
        this.target = target;
        this.area = area;
        this.geodata = geodata;
        this.floors = floors;
    }

    record Plan(
            String label,
            UnaryOperator<State> edit,
            @Nullable Batch batch,
            JSONObject said) {}

    static SpawnMaker findMaker(State state, String text) throws ToolProblem {
        if (text.startsWith("#")) {
            var index = parseIndex(text);
            if (index >= 0 && index < state.makers().size() && state.makers().get(index) != null) {
                return state.makers().get(index);
            }
            throw new ToolProblem("There is no maker " + text + ". spawn_find names the makers.");
        }
        var found = new ArrayList<SpawnMaker>();
        for (var maker : state.makers()) {
            if (maker != null
                    && (maker.name().equals(text) || maker.displayName().equals(text))) {
                found.add(maker);
            }
        }
        if (found.isEmpty()) {
            throw new ToolProblem("There is no maker named " + text + ". spawn_find names the makers.");
        }
        if (found.size() > 1) {
            var numbers = found.stream()
                    .limit(SHOWN_NAMES)
                    .map(maker -> number(maker.index()))
                    .toList();
            throw new ToolProblem(
                    found.size() + " makers are named " + text + ", such as " + numbers + ". Use a number.");
        }
        return found.getFirst();
    }

    static String number(int index) {
        return "#" + index;
    }

    Plan plan() throws ToolProblem {
        var made = planAction();
        if (clientGround) {
            made.said().put("ground_from", "the floors of the client's map, as the geodata has none there");
        }
        return made;
    }

    private Plan planAction() throws ToolProblem {
        var op = args.getRequired("action");
        return switch (op) {
            case "add_npc" -> addNpc();
            case "change_npc" -> changeNpc();
            case "remove_npc" -> removeNpc();
            case "add_maker" -> addMaker();
            case "change_maker" -> changeMaker();
            case "remove_maker" -> removeMaker();
            case "add_zone" -> addZone();
            case "move_zone" -> moveZone();
            case "change_zone" -> changeZone();
            case "remove_zone" -> removeZone();
            case "duplicate_maker" -> duplicateMaker();
            case "paste_maker" -> pasteMaker();
            case "remove_unused_zones" -> removeUnusedZones();
            case "batch" -> batch();
            default -> throw new ToolProblem("action cannot be " + op + ". Use one of " + String.join(", ", OPS) + ".");
        };
    }

    private Plan addNpc() throws ToolProblem {
        var maker = maker();
        var name = npcName();
        var position = placeOf(maker);
        var index = maker.index();
        var npc = SpawnEdits.newNpc(
                index,
                name,
                position,
                count("total", 1),
                time("respawn", SpawnEdits.DEFAULT_RESPAWN),
                time("respawn_random", ""));
        var said = said(maker);
        said.put("slot", maker.npcs().size());
        return new Plan(
                "Add NPC " + name + " to " + maker.displayName(),
                current -> SpawnEdits.addNpc(current, index, npc),
                null,
                said);
    }

    private Plan changeNpc() throws ToolProblem {
        var maker = maker();
        var slot = slot(maker);
        var old = maker.npcs().get(slot);
        var name = args.getText("npc") == null ? null : npcName();
        var total = args.has("total") ? count("total", old.total()) : -1;
        var respawn = time("respawn", old.respawn());
        var random = time("respawn_random", old.respawnRandom());
        var position =
                target != null ? fixedAt(target) : args.readFlag("anywhere", false) ? SpawnPosition.ANYWHERE : null;
        if (position == SpawnPosition.ANYWHERE && maker.territoryNames().isEmpty()) {
            throw new ToolProblem("Maker " + maker.displayName() + " has no zone, so its NPCs need a target.");
        }
        UnaryOperator<SpawnNpc> change = entry -> {
            var next = entry.withRespawn(respawn, random);
            next = name == null ? next : next.withNpcName(name);
            next = total < 0 ? next : next.withTotal(total);
            return position == null ? next : next.withPosition(position);
        };
        var index = maker.index();
        var said = said(maker);
        said.put("slot", slot);
        return new Plan(
                "Change NPC " + old.npcName() + " of " + maker.displayName(),
                current -> SpawnEdits.replaceNpc(current, index, slot, change),
                null,
                said);
    }

    private Plan removeNpc() throws ToolProblem {
        var maker = maker();
        var slot = slot(maker);
        var index = maker.index();
        var name = maker.npcs().get(slot).npcName();
        return new Plan(
                "Remove NPC " + name + " from " + maker.displayName(),
                current -> current.withNpc(index, slot, null),
                null,
                said(maker));
    }

    private Plan addMaker() throws ToolProblem {
        var name = npcName();
        var zone = args.getText("zone") == null ? null : zone();
        if (zone == null && target == null) {
            throw new ToolProblem("add_maker needs a zone, or a target for a new zone.");
        }
        var newZone = zone == null ? newZone() : null;
        var zoneName = zone == null ? newZone.name() : zone.name();
        var makerName = args.getText("name");
        var after = newZone == null ? state : state.withTerritory(newZone);
        var title = makerName == null ? SpawnNames.newMakerName(after, zoneName) : freeMakerName(makerName);
        var index = state.makers().size();
        var npc = SpawnEdits.newNpc(
                index,
                name,
                SpawnPosition.ANYWHERE,
                count("total", 1),
                time("respawn", SpawnEdits.DEFAULT_RESPAWN),
                time("respawn_random", ""));
        var maker = SpawnMaker.newMaker(index, List.of(zoneName), title, List.of());
        var said = new JSONObject();
        said.put("maker", number(index));
        said.put("name", title);
        said.put("zone", zoneName);
        return new Plan(
                "Add maker " + title,
                current -> {
                    var next = newZone == null ? current : current.withTerritory(newZone);
                    return SpawnEdits.addNpc(next.withMaker(maker), index, npc);
                },
                null,
                said);
    }

    private Plan changeMaker() throws ToolProblem {
        var maker = maker();
        var index = maker.index();
        var next = maker;
        if (args.has("maximum")) {
            next = next.withMaximumNpc(count("maximum", maker.maximumNpc()));
        }
        var name = args.getText("name");
        if (name != null && !name.equals(maker.name())) {
            next = next.withName(freeMakerName(name));
        }
        if (args.has("zones") || args.has("banned")) {
            var zones = args.has("zones") ? zoneNames("zones") : maker.territoryNames();
            var banned = args.has("banned") ? zoneNames("banned") : maker.bannedTerritoryNames();
            next = next.withTerritories(zones, banned);
        }
        var changed = next;
        var ai = args.getText("ai");
        var parameter = args.getText("parameter");
        if ((ai != null || parameter != null) && !maker.extended()) {
            throw new ToolProblem(
                    "Maker " + maker.displayName() + " is an npcmaker_begin maker, and those have no AI.");
        }
        return new Plan(
                "Change maker " + maker.displayName(),
                current -> {
                    var after = current.withMaker(changed);
                    if (ai != null) {
                        after = SpawnBatchEdits.setAi(after, List.of(index), ai, catalog());
                    }
                    return parameter == null
                            ? after
                            : SpawnBatchEdits.setAiParameter(after, List.of(index), parameter, parameterValue());
                },
                null,
                said(maker));
    }

    private MakerAiCatalog catalog() {
        return MakerAiCatalog.of(state.makers());
    }

    private @Nullable String parameterValue() {
        var value = args.getJson().get("value");
        return value == null ? null : String.valueOf(value);
    }

    private Plan removeMaker() throws ToolProblem {
        var maker = maker();
        var index = maker.index();
        var zones = args.readFlag("with_zones", false) ? state.zonesOnlyUsedBy(index) : List.<Integer>of();
        var said = said(maker);
        said.put("zones_removed", zones.size());
        return new Plan(
                (zones.isEmpty() ? "Remove maker " : "Remove maker and zones of ") + maker.displayName(),
                current -> SpawnEdits.deleteMaker(current, index, zones),
                null,
                said);
    }

    private Plan addZone() throws ToolProblem {
        var zone = newZone();
        var said = new JSONObject();
        said.put("zone", zone.name());
        return new Plan("Add zone " + zone.name(), current -> current.withTerritory(zone), null, said);
    }

    private SpawnTerritory newZone() throws ToolProblem {
        var name = args.getText("zone_name");
        if (args.has("corners")) {
            var corners = corners(0, 0);
            var middleX = corners.stream().mapToInt(TerritoryPoint::x).average().orElse(0);
            var middleY = corners.stream().mapToInt(TerritoryPoint::y).average().orElse(0);
            return SpawnEdits.newZone(
                    state.territories().size(),
                    name == null ? SpawnNames.newZoneName(state, middleX, middleY) : freeZoneName(name),
                    corners);
        }
        if (target == null) {
            throw new ToolProblem("A new zone needs a target or corners.");
        }
        var x = Math.round(target.x());
        var y = Math.round(target.y());
        var ground = groundAt(x, y, target.z());
        var bottom = args.getInteger("bottom", ground - BELOW_GROUND);
        var top = args.getInteger("top", ground + ABOVE_GROUND);
        var half = (int) Math.round(args.getNumber("radius", NEW_ZONE_HALF));
        if (half < 1) {
            throw new ToolProblem("radius must be above 0.");
        }
        return SpawnEdits.newZone(
                state.territories().size(),
                name == null ? SpawnNames.newZoneName(state, x, y) : freeZoneName(name),
                SpawnEdits.square(x, y, half, bottom, top));
    }

    private Plan moveZone() throws ToolProblem {
        var zone = zone();
        int shiftX;
        int shiftY;
        if (target != null) {
            shiftX = (int) Math.round(target.x() - zone.centerX());
            shiftY = (int) Math.round(target.y() - zone.centerY());
        } else if (args.has("dx") || args.has("dy") || args.has("dz")) {
            shiftX = args.getInteger("dx", 0);
            shiftY = args.getInteger("dy", 0);
        } else {
            throw new ToolProblem("move_zone needs a target for the middle of the zone, or dx, dy and dz.");
        }
        var shiftZ = args.getInteger("dz", 0);
        var points = SpawnEdits.shifted(zone.points(), shiftX, shiftY, shiftZ);
        var moved = args.readFlag("keep_npcs", false) ? List.<Carried>of() : carried(zone, shiftX, shiftY, shiftZ);
        var index = zone.index();
        var said = new JSONObject();
        said.put("zone", zone.name());
        said.put("moved", shiftX + "," + shiftY + "," + shiftZ);
        said.put("npcs_moved", moved.size());
        return new Plan(
                "Move zone " + zone.name(), current -> SpawnEdits.moveZone(current, index, points, moved), null, said);
    }

    private List<Carried> carried(SpawnTerritory zone, int shiftX, int shiftY, int shiftZ) {
        var carried = SpawnEdits.carriedBy(state, zone);
        if (carried.isEmpty()) {
            return carried;
        }
        var data = geodata == null
                ? null
                : geodata.readArea(
                        zone.minX() + shiftX, zone.minY() + shiftY, zone.maxX() + shiftX, zone.maxY() + shiftY);
        var moved = new ArrayList<Carried>();
        for (var npc : carried) {
            var old = npc.position();
            var place = new Fixed(old.x() + shiftX, old.y() + shiftY, old.z() + shiftZ, old.heading());
            var floor = floorAt(place.x(), place.y(), place.z() + FLOOR_SEARCH, data);
            moved.add(new Carried(
                    npc.maker(),
                    npc.slot(),
                    floor == null ? place : new Fixed(place.x(), place.y(), floor, place.heading())));
        }
        return moved;
    }

    private Plan changeZone() throws ToolProblem {
        var zone = zone();
        var index = zone.index();
        var name = args.getText("zone_name");
        var newName = name == null || name.equals(zone.name()) ? null : freeZoneName(name);
        var points = args.has("corners") ? corners(zone.bottom(), zone.top()) : null;
        var bottom = args.has("bottom") ? Integer.valueOf(args.getInteger("bottom", 0)) : null;
        var top = args.has("top") ? Integer.valueOf(args.getInteger("top", 0)) : null;
        var said = new JSONObject();
        said.put("zone", newName == null ? zone.name() : newName);
        return new Plan(
                "Change zone " + zone.name(),
                current -> {
                    var next = current;
                    if (points != null) {
                        next = next.withTerritory(next.territories().get(index).withPoints(points));
                    }
                    if (bottom != null || top != null) {
                        next = SpawnEdits.withZoneHeights(next, index, bottom, top);
                    }
                    return newName == null ? next : next.withTerritoryName(index, newName);
                },
                null,
                said);
    }

    private Plan removeZone() throws ToolProblem {
        var zone = zone();
        var users = new ArrayList<String>();
        for (var maker : state.makers()) {
            if (maker != null
                    && (maker.territoryNames().contains(zone.name())
                            || maker.bannedTerritoryNames().contains(zone.name()))) {
                users.add(maker.displayName());
            }
        }
        if (!users.isEmpty()) {
            throw new ToolProblem(users.size()
                    + " makers still name zone "
                    + zone.name()
                    + ", such as "
                    + String.join(", ", users.subList(0, Math.min(users.size(), SHOWN_NAMES)))
                    + ". Change or remove them first. remove_maker with with_zones takes a zone only one maker uses.");
        }
        var index = zone.index();
        var said = new JSONObject();
        said.put("zone", zone.name());
        return new Plan("Remove zone " + zone.name(), current -> current.withoutTerritory(index), null, said);
    }

    private Plan duplicateMaker() throws ToolProblem {
        var maker = maker();
        if (target == null) {
            throw new ToolProblem("duplicate_maker needs a target for the copy.");
        }
        var zones = SpawnCopies.findZones(state, maker);
        var middle = SpawnCopies.computeMiddle(zones, maker);
        if (middle == null) {
            throw new ToolProblem("Maker " + maker.displayName() + " has no zone or fixed NPC to copy.");
        }
        var x = Math.round(target.x());
        var y = Math.round(target.y());
        var ground = groundAt(x, y, target.z());
        var source = Math.round(middle[2]);
        var middleX = (int) Math.round(middle[0]);
        var middleY = (int) Math.round(middle[1]);
        var under = geodata == null ? null : geodata.readArea(middleX - 1, middleY - 1, middleX + 1, middleY + 1);
        var dropped = floorAt(middleX, middleY, (int) source + FLOOR_SEARCH, under);
        var sourceGround = dropped == null ? source : dropped;
        var copy = SpawnCopies.duplicate(
                state, maker.index(), x - middle[0], y - middle[1], (int) (ground - sourceGround));
        return copied("Duplicate maker " + maker.displayName(), copy);
    }

    private Plan pasteMaker() throws ToolProblem {
        var text = args.getJson().getString("text");
        if (text == null || !text.contains("npcmaker")) {
            throw new ToolProblem(
                    "text holds no maker. spawn_find with maker gives one as text, or copy its lines from npcpos.txt.");
        }
        if (target == null) {
            throw new ToolProblem("paste_maker needs a target for the copy.");
        }
        var x = Math.round(target.x());
        var y = Math.round(target.y());
        var copy = SpawnCopies.paste(state, text, x, y, groundAt(x, y, target.z()));
        if (copy == null) {
            throw new ToolProblem("text holds no maker with a zone or a fixed NPC to place.");
        }
        return copied("Paste maker", copy);
    }

    private Plan removeUnusedZones() throws ToolProblem {
        var zones = state.unusedZones();
        if (zones.isEmpty()) {
            throw new ToolProblem("No zone is unused: every zone has a maker.");
        }
        var said = new JSONObject();
        said.put("zones_removed", zones.size());
        return new Plan(
                "Delete " + zones.size() + " unused zones",
                current -> current.withoutTerritories(zones),
                new Batch("unused zones deleted", Set.of(), Set.copyOf(zones)),
                said);
    }

    private Plan batch() throws ToolProblem {
        var makers = batchMakers();
        var action = args.getRequired("batch_action");
        var factor = args.getNumber("factor");
        var times = factor == null ? "" : SpawnBatchEdits.formatFactor(factor);
        String label;
        String noun;
        State after;
        switch (action) {
            case "scale_respawn" -> {
                requireFactor(factor);
                var random = args.readFlag("random", true);
                label = "Respawn " + times;
                noun = "respawn " + times + (random ? "" : " (not the random part)");
                after = SpawnBatchEdits.scaleRespawn(state, makers, factor, random);
            }
            case "scale_counts" -> {
                requireFactor(factor);
                var totals = args.readFlag("totals", true);
                var maximum = args.readFlag("scale_maximum", true);
                label = "Counts " + times;
                noun = (totals && maximum ? "counts and maximum_npc " : totals ? "counts " : "maximum_npc ") + times;
                after = SpawnBatchEdits.scaleCounts(state, makers, factor, totals, maximum);
            }
            case "set_ai" -> {
                var ai = args.getRequired("ai");
                label = "AI " + ai;
                noun = "AI changed to " + ai;
                after = SpawnBatchEdits.setAi(state, makers, ai, catalog());
            }
            case "set_ai_parameter" -> {
                var parameter = args.getRequired("parameter");
                var value = parameterValue();
                label = value == null ? "Remove " + parameter : parameter + "=" + value;
                noun = value == null ? parameter + " removed" : parameter + " set to " + value;
                after = SpawnBatchEdits.setAiParameter(state, makers, parameter, value);
            }
            case "remove" -> {
                var zones = args.readFlag("with_zones", false);
                label = "Delete";
                noun = zones ? "deleted with their zones" : "deleted";
                after = SpawnBatchEdits.delete(state, makers, zones);
            }
            default ->
                throw new ToolProblem("batch_action cannot be " + action
                        + ". Use scale_respawn, scale_counts, set_ai, set_ai_parameter or remove.");
        }
        var changed = SpawnBatchEdits.changedMakers(state, after);
        var zones = SpawnBatchEdits.changedTerritories(state, after);
        if (changed.isEmpty() && zones.isEmpty()) {
            throw new ToolProblem("Nothing changed: none of the " + makers.size() + " makers could take it.");
        }
        var said = new JSONObject();
        said.put("makers_asked", makers.size());
        said.put("makers_changed", changed.size());
        said.put("zones_changed", zones.size());
        var result = after;
        return new Plan(
                label + " on " + changed.size() + (changed.size() == 1 ? " maker" : " makers"),
                unused -> result,
                new Batch(noun, changed, zones),
                said);
    }

    private List<Integer> batchMakers() throws ToolProblem {
        var named = new LinkedHashSet<Integer>();
        for (var text : args.list("makers")) {
            named.add(findMaker(text).index());
        }
        if (area != null) {
            for (var hit : area.makers(state)) {
                named.add(hit.index());
            }
        }
        if (named.isEmpty()) {
            throw new ToolProblem("batch needs makers, or a target with makers around it. spawn_find names them.");
        }
        return List.copyOf(named);
    }

    private SpawnMaker maker() throws ToolProblem {
        var text = args.getText("maker");
        if (text == null) {
            throw new ToolProblem("maker is missing. spawn_find names the makers.");
        }
        return findMaker(text);
    }

    private SpawnMaker findMaker(String text) throws ToolProblem {
        return findMaker(state, text);
    }

    private SpawnTerritory zone() throws ToolProblem {
        var text = args.getText("zone");
        if (text == null) {
            throw new ToolProblem("zone is missing. spawn_find names the zones.");
        }
        return findZone(text);
    }

    private SpawnTerritory findZone(String text) throws ToolProblem {
        if (text.startsWith("#")) {
            var index = parseIndex(text);
            if (index >= 0
                    && index < state.territories().size()
                    && state.territories().get(index) != null
                    && !state.territories().get(index).domain()) {
                return state.territories().get(index);
            }
            throw new ToolProblem("There is no zone " + text + ". spawn_find names the zones.");
        }
        var zone = state.territory(text);
        if (zone == null) {
            throw new ToolProblem("There is no zone named " + text + ". spawn_find names the zones.");
        }
        return zone;
    }

    private List<String> zoneNames(String key) throws ToolProblem {
        var names = new ArrayList<String>();
        for (var name : args.list(key)) {
            names.add(findZone(name).name());
        }
        return names;
    }

    private int slot(SpawnMaker maker) throws ToolProblem {
        var count = maker.npcs().size();
        if (!args.has("slot")) {
            if (count == 1) {
                return 0;
            }
            throw new ToolProblem("Maker " + maker.displayName() + " has " + count
                    + " NPC entries. Say which with slot, counted from 0, as spawn_find lists them.");
        }
        var slot = args.getInteger("slot", -1);
        if (slot < 0 || slot >= count) {
            throw new ToolProblem("Maker "
                    + maker.displayName()
                    + " has no slot "
                    + slot
                    + ": it has "
                    + count
                    + " NPC entries, counted from 0.");
        }
        return slot;
    }

    private String npcName() throws ToolProblem {
        var text = args.getText("npc");
        if (text == null) {
            throw new ToolProblem("npc is missing. npc_find names the NPCs.");
        }
        if (npcs.size() == 0 || npcs.getNpc(text) != null) {
            return text;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            var id = Integer.parseInt(text);
            for (var npc : npcs.getNpcs()) {
                if (npc.id() == id) {
                    return npc.name();
                }
            }
        }
        throw new ToolProblem("The saved npcdata.txt has no NPC " + text
                + ". Call npc_find for its name. A new NPC of npc_edit counts here after spawn_save with file"
                + " npcdata.");
    }

    private int count(String key, int fallback) throws ToolProblem {
        var count = args.getInteger(key, fallback);
        if (count < 1) {
            throw new ToolProblem(key + " must be 1 or more.");
        }
        return count;
    }

    private String time(String key, String fallback) throws ToolProblem {
        var text = args.getText(key);
        if (text == null) {
            return fallback;
        }
        var value = text.toLowerCase(Locale.ROOT);
        if (SpawnEdits.isTime(value) || value.equals("no")) {
            return value;
        }
        throw new ToolProblem(key + " must be a time such as 30sec, 5min, 2hour or 1day, or no.");
    }

    private SpawnPosition placeOf(SpawnMaker maker) throws ToolProblem {
        if (target != null) {
            return fixedAt(target);
        }
        if (maker.territoryNames().isEmpty()) {
            throw new ToolProblem("Maker " + maker.displayName() + " has no zone, so a new entry needs a target.");
        }
        return SpawnPosition.ANYWHERE;
    }

    private Fixed fixedAt(Target place) throws ToolProblem {
        var x = Math.round(place.x());
        var y = Math.round(place.y());
        var heading = Math.floorMod(Math.round((float) (args.getNumber("heading", 0) / 360 * FULL_TURN)), FULL_TURN);
        return new Fixed(x, y, groundAt(x, y, place.z()), heading);
    }

    private int groundAt(int x, int y, @Nullable Float z) throws ToolProblem {
        var from = z == null ? TOP_OF_THE_WORLD : Math.round(z) + FLOOR_SEARCH;
        var data = geodata == null ? null : geodata.readArea(x - 1, y - 1, x + 1, y + 1);
        var floor = floorAt(x, y, from, data);
        if (floor != null) {
            return floor;
        }
        if (z != null) {
            return Math.round(z);
        }
        throw new ToolProblem("The ground at " + x + "," + y + " is not known: the geodata and the client's map have"
                + " no floor there. Give a height as x,y,z.");
    }

    private @Nullable Integer floorAt(int x, int y, int from, @Nullable GeoDataAccess data) {
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

    private List<TerritoryPoint> corners(int bottom, int top) throws ToolProblem {
        var low = args.getInteger("bottom", bottom);
        var high = args.getInteger("top", top);
        var corners = new ArrayList<TerritoryPoint>();
        for (var text : args.list("corners")) {
            var parts = text.split("[, ]+");
            try {
                corners.add(new TerritoryPoint(
                        Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()), low, high));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException wrong) {
                throw new ToolProblem("A corner is x,y, not \"" + text + "\".");
            }
        }
        if (corners.size() < 3) {
            throw new ToolProblem("A zone needs at least 3 corners.");
        }
        if (low >= high) {
            throw new ToolProblem("A zone needs bottom and top, with the bottom below the top.");
        }
        return corners;
    }

    private String freeZoneName(String name) throws ToolProblem {
        if (!SpawnNames.isValid(name)) {
            throw new ToolProblem("A zone name uses letters, digits and underscores, such as giran_2223_01.");
        }
        if (state.territory(name) != null) {
            throw new ToolProblem("Another zone is already called " + name + ".");
        }
        return name;
    }

    private String freeMakerName(String name) throws ToolProblem {
        if (!SpawnNames.isValid(name)) {
            throw new ToolProblem("A maker name uses letters, digits and underscores, such as giran_2223_01m1.");
        }
        for (var maker : state.makers()) {
            if (maker != null && maker.name().equals(name)) {
                throw new ToolProblem("Another maker is already called " + name + ".");
            }
        }
        return name;
    }

    private static Plan copied(String label, Copy copy) {
        var maker = copy.state().makers().get(copy.maker());
        var said = said(maker);
        said.put("zones", maker.territoryNames());
        var result = copy.state();
        return new Plan(label, unused -> result, null, said);
    }

    private static void requireFactor(@Nullable Double factor) throws ToolProblem {
        if (factor == null || !Double.isFinite(factor) || factor <= 0) {
            throw new ToolProblem("factor must be a number above 0, such as 2 or 0.5.");
        }
    }

    private static int parseIndex(String text) throws ToolProblem {
        try {
            return Integer.parseInt(text.substring(1).strip());
        } catch (NumberFormatException wrong) {
            throw new ToolProblem(text + " is not a number such as #1234.");
        }
    }

    private static JSONObject said(SpawnMaker maker) {
        var said = new JSONObject();
        said.put("maker", number(maker.index()));
        said.put("name", maker.displayName());
        return said;
    }
}

package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.NpcTool.npcEdit;
import static com.rost.cruma.viewer.agent.spawn.NpcTool.npcFind;
import static com.rost.cruma.viewer.agent.spawn.NpcTool.npcGet;
import static com.rost.cruma.viewer.agent.spawn.SpawnCheckTool.spawnCheck;
import static com.rost.cruma.viewer.agent.spawn.SpawnEditTool.spawnEdit;
import static com.rost.cruma.viewer.agent.spawn.SpawnFindTool.spawnFind;
import static com.rost.cruma.viewer.agent.spawn.SpawnSaveTool.spawnSave;

import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.spawn.SpawnAccess;
import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;

public final class SpawnTools implements ToolGroup {
    private static final String SCRIPTS =
            "With no viewer open: the server's script folder. The client's folder from Settings when left out.";
    private static final String[] KIND_WORDS = {
        "monster", "boss", "townsfolk", "guard", "summon", "object", "unknown", "event"
    };
    private static final String[] BAND_WORDS = {"1-20", "21-40", "41-60", "61-76", "77+"};
    private static final String[] LISTS = {"maker_ais", "npc_ais", "events", "bookmarks", "unused_zones"};

    @FunctionalInterface
    interface Places {
        Target locate(String text) throws ToolProblem;
    }

    record Desk(SpawnAccess spawns, @Nullable Path scriptPath, Places places, boolean hidden, Access access) {}

    @Override
    public void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "npc_find",
                                "Find NPCs",
                                "Finds NPCs by id, by their npcdata.txt name (gremlin) or by the name the game shows"
                                        + " (Gremlin), with how many of each spawn.")
                        .text("query", "An id, words of a name or title, or lv 40 or lv 20-30. All when left out.")
                        .choice("kind", "Only this kind.", NpcLookup.KINDS)
                        .integer("limit", "How many to list. 30 when left out.")
                        .text("scripts", SCRIPTS),
                (args, viewer) -> npcFind(args, desk(viewer)),
                SpawnFiles.alone(NpcTool::npcFind));
        tools.viewer(
                ToolSpec.named(
                                "npc_get",
                                "Read an NPC",
                                "One NPC in short: level, stats, AI, skills, drops, model and how many spawn. Or who"
                                        + " drops an item or knows a skill, or the unsaved changes.")
                        .text("npc", "An id or a name, as npc_find gives it.")
                        .flag("detail", "All settings as npcdata.txt writes them, with all skills and drops.")
                        .text("item", "Who drops, spoils or makes this item: a name or id of itemdata.txt.")
                        .text("skill", "Who has this skill in its skill list or AI.")
                        .flag("changes", "What saving npcdata.txt would change, with its problems.")
                        .integer("limit", "How many NPCs or changes to list. 30 when left out.")
                        .text("scripts", SCRIPTS),
                (args, viewer) -> npcGet(args, desk(viewer)),
                SpawnFiles.alone(NpcTool::npcGet));
        tools.viewer(
                ToolSpec.named(
                                "npc_edit",
                                "Edit NPCs",
                                "Changes npcdata.txt in memory as the NPCs panel does: settings, drops, skills, AI,"
                                        + " copies, deletes and batches, or undoes a step. spawn_save with file npcdata"
                                        + " writes it.")
                        .access(Access.EDIT)
                        .choice("action", "What to do.", NpcEditor.OPS)
                        .text("npc", "An id or a name, as npc_find gives it.")
                        .text("key", "set: a setting as npcdata.txt names it, such as org_hp. ai_set: an AI setting.")
                        .text(
                                "value",
                                "The value as npcdata.txt writes it. ai: the AI class. ai_set without it removes the"
                                        + " setting. item_change: another item.")
                        .flag("race_skill", "set race: also swap the race skill, as Set race skill too.")
                        .choice("list", "Which item list. drop when left out.", NpcEditor.LISTS)
                        .integer("group", "drop and herbs: the group, from 0, as npc_get lists them.")
                        .text("item", "An item: an itemdata.txt name or id.")
                        .integer("min", "Fewest pieces.")
                        .integer("max", "Most pieces.")
                        .number("chance", "Percent of the item, or of the group for group actions.")
                        .text("skill", "A skill as skilldata.txt names it.")
                        .choice(
                                "batch_action",
                                "batch: what to do.",
                                "scale_drop",
                                "scale_spoil",
                                "scale_exp",
                                "scale_sp",
                                "set",
                                "skill_add",
                                "skill_remove")
                        .list("npcs", "batch: the NPCs by id or name.")
                        .text("query", "batch: also the NPCs npc_find gives for this query.")
                        .choice("kind", "batch: with query, only this kind.", NpcLookup.KINDS)
                        .number("factor", "batch: multiply by this, such as 2 or 0.5.")
                        .required("action"),
                (args, viewer) -> npcEdit(args, desk(viewer)));
        tools.viewer(
                ToolSpec.named(
                                "spawn_find",
                                "Find spawns",
                                "Makers and zones near a target, of an NPC or of the panel's filters, with how many"
                                        + " spawn and how often. One maker as text, or lists. Unsaved edits count.")
                        .target("Where to look. The whole file when left out.")
                        .number(
                                "radius",
                                "Units around the target. 4000 when left out, and a tile takes the whole tile.")
                        .text("npc", "Only makers of this NPC: an id or a name, as npc_find gives it.")
                        .text("maker", "One maker in full, by name or number, with its npcpos.txt text.")
                        .choice("kind", "Filter: what a maker spawns most.", KIND_WORDS)
                        .choice("level", "Filter: the level band of its NPCs.", BAND_WORDS)
                        .text("ai", "Filter: the maker's AI. Narrows the AI lists too.")
                        .text("respawn", "Filter: an entry respawns so, such as 3hour.")
                        .text("event", "Filter: an event or spawn time.")
                        .flag("unknown", "Filter: an entry names an NPC npcdata.txt lacks.")
                        .choice("list", "A list in place of makers.", LISTS)
                        .integer("limit", "How many to list. 30 when left out.")
                        .text("scripts", SCRIPTS),
                (args, viewer) -> spawnFind(args, desk(viewer)),
                SpawnFiles.alone(SpawnFindTool::spawnFind));
        tools.viewer(
                ToolSpec.named(
                                "spawn_check",
                                "Check spawns",
                                "Checks an area: floors, walls, spawn points, zones and missing NPCs. Its findings can"
                                        + " go straight into findings_show as items.")
                        .target("The middle of the area.")
                        .number(
                                "radius",
                                "Units around the target. 4000 when left out, and a tile takes the whole tile.")
                        .integer("limit", "How many findings to list, the worst first. 30 when left out.")
                        .required("target"),
                (args, viewer) -> spawnCheck(args, desk(viewer)));
        tools.viewer(
                ToolSpec.named(
                                "spawn_edit",
                                "Edit spawns",
                                "Adds, changes, moves and removes makers, zones and NPC entries in memory, or undoes a"
                                        + " step. Each call is one Undo step. spawn_save writes them. The ground is"
                                        + " the geodata, else the floors of the client's map, which ground_from"
                                        + " names.")
                        .access(Access.EDIT)
                        .choice("action", "What to do. batch changes many makers in one step.", SpawnEditor.OPS)
                        .text("maker", "A maker: its name, or its number such as #1234, as spawn_find gives it.")
                        .text("zone", "A zone: its name, or its number such as #1234, as spawn_find gives it.")
                        .integer("slot", "Which NPC entry of the maker, counted from 0, as spawn_find lists them.")
                        .text(
                                "npc",
                                "An NPC by its npcdata.txt name or id. For add_maker and add_npc, or a new NPC for"
                                        + " change_npc.")
                        .target("A new place. add_npc and change_npc stand the NPC there, on the ground. add_zone and"
                                + " add_maker make a zone there. move_zone moves the middle of the zone there."
                                + " duplicate_maker and paste_maker put the copy there. batch takes the makers"
                                + " around it.")
                        .number(
                                "radius",
                                "add_zone and add_maker: half the width of a new zone, 400 when left out. batch: units"
                                        + " around the target, 4000 when left out.")
                        .integer("total", "How many NPCs the entry spawns. 1 for a new entry.")
                        .text(
                                "respawn",
                                "When an NPC comes back: 30sec, 5min, 2hour, 1day or no. 60sec for a new entry.")
                        .text("respawn_random", "A time the respawn can differ by, such as 30sec.")
                        .number("heading", "Which way a placed NPC looks, in degrees. 0 looks east and 90 south.")
                        .flag(
                                "anywhere",
                                "change_npc: spawn anywhere in the zones of the maker, in place of a fixed point.")
                        .text("name", "A name for a maker. add_maker finds one by itself.")
                        .text("zone_name", "A name for a zone. A new zone finds one by itself.")
                        .integer("maximum", "change_maker: maximum_npc.")
                        .list("zones", "change_maker: the zones it spawns in.")
                        .list("banned", "change_maker: the zones it leaves out.")
                        .text("ai", "change_maker or batch: the AI of the maker.")
                        .text("parameter", "change_maker or batch: an AI parameter to set.")
                        .text("value", "The value of that parameter. Left out, the parameter goes.")
                        .list("corners", "add_zone or change_zone: corners as x,y.")
                        .integer("bottom", "The lowest height of a zone.")
                        .integer("top", "The highest height of a zone.")
                        .integer("dx", "move_zone: units to move east.")
                        .integer("dy", "move_zone: units to move south.")
                        .integer("dz", "move_zone: units to move up.")
                        .flag(
                                "keep_npcs",
                                "move_zone: leave the fixed NPCs where they are. They move with the zone when left"
                                        + " out.")
                        .flag("with_zones", "remove_maker and batch remove: also remove zones only those makers use.")
                        .list("makers", "batch: the makers, by name or number.")
                        .choice(
                                "batch_action",
                                "batch: what to do to the makers.",
                                "scale_respawn",
                                "scale_counts",
                                "set_ai",
                                "set_ai_parameter",
                                "remove")
                        .number("factor", "batch: scale_respawn and scale_counts multiply by this, such as 2 or 0.5.")
                        .flag("random", "batch scale_respawn: also scale the random part. On when left out.")
                        .flag("totals", "batch scale_counts: scale the count of each entry. On when left out.")
                        .flag("scale_maximum", "batch scale_counts: scale maximum_npc too. On when left out.")
                        .text("text", "paste_maker: a maker as npcpos.txt text, as spawn_find with maker gives it.")
                        .required("action"),
                (args, viewer) -> spawnEdit(args, desk(viewer)));
        tools.viewer(
                ToolSpec.named(
                                "spawn_save",
                                "Save spawns",
                                "Checks the edits and writes npcpos.txt, or npcdata.txt with npc_pch.txt, into the"
                                        + " server folder with a dated backup. Refuses edits that broke something.")
                        .access(Access.WRITE)
                        .destructive()
                        .choice("file", "npcpos when left out, or npcdata.", "npcpos", "npcdata")
                        .integer("limit", "How many changes to list. 30 when left out."),
                (args, viewer) -> spawnSave(args, desk(viewer)));
    }

    private static Desk desk(ViewerContext viewer) {
        var spawns = viewer.world().spawns();
        return new Desk(
                viewer.system().getSpawnAccess(),
                spawns == null ? null : spawns.scriptPath(),
                viewer::locate,
                viewer.agent().isHidden(),
                viewer.agent().getAccess());
    }
}

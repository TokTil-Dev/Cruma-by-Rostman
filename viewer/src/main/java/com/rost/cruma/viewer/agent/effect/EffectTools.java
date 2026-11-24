package com.rost.cruma.viewer.agent.effect;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;

public final class EffectTools implements ToolGroup {
    @Override
    public void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "effect_play",
                                "Play an effect",
                                "Plays an effect, or a skill's cast between a caster and a target, in the Effects"
                                        + " tool, so view_look and view_clip show it. Answers what plays and where.")
                        .text(
                                "effect",
                                "An effect class or copy, such as LineageEffect.at_shock_blast_ta, or a part of its"
                                        + " name.")
                        .text(
                                "skill",
                                "A skill by id, name or class (l2_1239_skill, or skill.el.1239 up to Interlude), in"
                                        + " place of effect.")
                        .integer("level", "The skill's level. Another skill starts at 1.")
                        .integer(
                                "speed",
                                "The caster's Casting Spd for magic, else Atk. Spd, 100 to 3000. 333 casts for the hit"
                                        + " time.")
                        .integer(
                                "distance",
                                "Units between the bodies of caster and target: the skill's range at first, up to"
                                        + " twice it.")
                        .text(
                                "caster",
                                "Who casts, or what the effect hangs on: none (the ground), an NPC id or name, or a"
                                        + " race such as Elf, female.")
                        .text(
                                "target_character",
                                "The skill's target: self (the caster casts on itself, as in the game), copy (a copy of"
                                        + " the caster), an NPC id or name, or a race.")
                        .choice(
                                "spot",
                                "Where the effect hangs on the caster.",
                                "feet",
                                "chest",
                                "head",
                                "r_hand",
                                "l_hand")
                        .target("Where it stands, with a height. Where the middle of the view meets the ground when"
                                + " left out.")
                        .flag("ground", "A skill's caster and target stand on the floor under them, as Ground does.")
                        .flag("repeat", "Plays again after its end. On at first.")
                        .flag("frame", "Looks at it from close by once it stands, as Frame. true when left out."),
                EffectPlay::start);
        tools.viewer(
                ToolSpec.named(
                                "effect_edit",
                                "Edit effects",
                                "Reads and changes effects and skills as the editors of the Effects tool, each change"
                                        + " one Undo step, and exports, installs or restores them.")
                        .choice(
                                "action",
                                "read when left out. read without effect or skill gives the edits and their problems."
                                    + " script gives the effect's or skill's .uc text, with its edits, and with save"
                                    + " writes its file. scripts writes the .uc files of every class of the client's"
                                    + " effect and skill packages, as the client has them. discard is Discard, in a"
                                    + " viewer you opened. install, restore: Needs Write. The others but read, script,"
                                    + " scripts and export: Needs Edit.",
                                "read",
                                "script",
                                "scripts",
                                "change",
                                "add",
                                "remove",
                                "copy",
                                "rename",
                                "undo",
                                "redo",
                                "drop",
                                "discard",
                                "export",
                                "install",
                                "restore")
                        .text("effect", "An effect class or copy by path or name.")
                        .text("skill", "A skill by id, name or class, for its actions.")
                        .text("emitter", "An emitter key from read. Without it, change sets the whole effect.")
                        .text("key", "An action of the skill by its key from read.")
                        .schema("set", set())
                        .schema("properties", properties())
                        .text("query", "For read of an emitter, words of the property paths.")
                        .integer("limit", "For read of an emitter, how many properties. 40 when left out.")
                        .text(
                                "name",
                                "For copy, the name of the copy. For rename, the new name of a copy that is not"
                                        + " installed. It cannot end in a digit.")
                        .choice(
                                "copies",
                                "For export, where copies go: custom, LineageEffect_Custom.u, or source, the package"
                                        + " of each effect.",
                                "custom",
                                "source")
                        .flag(
                                "save",
                                "For script, also writes the file, as Save of the Script dialog: Package/Classes/"
                                        + "name.uc, or skill/id.t3d for a skill of skill.usk.")
                        .text(
                                "package",
                                "For scripts, one package by its file's name, such as LineageEffect, LineageEffect2,"
                                        + " LineageSkillEffect or skill. Every one when left out.")
                        .text(
                                "folder",
                                "For export, a new folder, the export folder when left out. For script with save and"
                                        + " for scripts, where the files go, the person's scripts folder when left"
                                        + " out: Effect scripts folder of Settings, Scripts in the export folder"
                                        + " unless they keep another. A folder for these: Needs Write. For install"
                                        + " and restore, the export, your last one when left out.")
                        .writes()
                        .destructive(),
                EffectEdit::start);
    }

    private static JSONObject set() {
        var schema = new JSONObject();
        schema.put("type", "object");
        schema.put(
                "description",
                "Fields to change. Effect or emitter: size, spread, speed, lifetime, count, opacity (factors 0.1 to"
                    + " 10), hue (-180 to 180), colour [r,g,b] from 0 to 1, reset, and of an emitter on, texture and"
                    + " image (a file to import). Skill action: effect, on (target or caster), multi, place, bone,"
                    + " offset [x,y,z], relative, scale, delay, phase. Skill: flight. add takes phase and effect, or"
                    + " from for an emitter.");
        return schema;
    }

    private static JSONObject properties() {
        var schema = new JSONObject();
        schema.put("type", "object");
        schema.put(
                "description",
                "All properties of the emitter: values as text by path, as read of an emitter lists them, such as"
                        + " {\"LifetimeRange.Min\": \"0.5\"}.");
        return schema;
    }
}

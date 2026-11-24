package com.rost.cruma.viewer.agent.effect;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.ui.EffectsPanel;
import com.rost.cruma.viewer.ui.EffectsPanel.Order;
import com.rost.cruma.viewer.ui.EffectsPanel.Spot;
import com.rost.cruma.viewer.ui.EffectsPanel.Who;
import com.rost.cruma.viewer.ui.SkillCasting;
import com.rost.cruma.viewer.ui.ToolWindows.Tool;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

final class EffectPlay implements Step {
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long TABLES_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final double WATCH_HEIGHT = 40;
    private static final long WATCH_NEAREST = 300;

    private final ViewerContext viewer;
    private final ToolArgs args;
    private final long started = System.nanoTime();
    private final List<String> others = new ArrayList<>();
    private boolean ordered;

    private EffectPlay(ViewerContext viewer, ToolArgs args) {
        this.viewer = viewer;
        this.args = args;
    }

    static Step start(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        if (args.has("effect") && args.has("skill")) {
            throw new ToolProblem("Give effect or skill, not both.");
        }
        if (args.has("speed")) {
            var speed = args.getInteger("speed", 0);
            if (speed < SkillCasting.MIN_SPEED || speed > SkillCasting.MAX_SPEED) {
                throw new ToolProblem("speed must be from " + SkillCasting.MIN_SPEED + " to " + SkillCasting.MAX_SPEED
                        + ". 333 casts for the hit time.");
            }
        }
        if (args.getInteger("distance", 0) < 0 || args.has("level") && args.getInteger("level", 1) < 1) {
            throw new ToolProblem("distance must be 0 or more, and level 1 or more.");
        }
        viewer.system().getWorkspace().tools.open(Tool.EFFECTS);
        return new EffectPlay(viewer, args);
    }

    static boolean areTablesRead(EffectsPanel panel, long waited) {
        var catalog = panel.getCasting().getCatalog();
        return catalog.getSkillCount() > 0 || catalog.getNpcCount() > 0 || waited > TABLES_NANOS;
    }

    static @Nullable String watch(@Nullable String caster, @Nullable String target) {
        if (caster == null || target == null) {
            return null;
        }
        var from = caster.split(",");
        var to = target.split(",");
        var dx = Double.parseDouble(to[0]) - Double.parseDouble(from[0]);
        var dy = Double.parseDouble(to[1]) - Double.parseDouble(from[1]);
        var x = Double.parseDouble(from[0]) + dx / 2;
        var y = Double.parseDouble(from[1]) + dy / 2;
        var z = (Double.parseDouble(from[2]) + Double.parseDouble(to[2])) / 2 + WATCH_HEIGHT;
        var distance = Math.max(WATCH_NEAREST, Math.round(Math.hypot(dx, dy) * 1.1));
        var yaw = Math.floorMod(Math.round(Math.toDegrees(Math.atan2(dy, dx))) - 90, 360);
        return "view_clip with target "
                + Math.round(x)
                + ","
                + Math.round(y)
                + ","
                + Math.round(z)
                + ", yaw "
                + yaw
                + " and distance "
                + distance
                + " sees both.";
    }

    @Override
    public @Nullable ToolResult step() throws ToolProblem {
        var panel = viewer.system().getEffects();
        var waited = System.nanoTime() - started;
        if (waited > WAIT_NANOS) {
            throw new ToolProblem(
                    "The Effects tool did not get ready in time. Call effect_play again to read what it plays now.");
        }
        if (!ordered) {
            if (panel.isListing() || !areTablesRead(panel, waited)) {
                return null;
            }
            if (!panel.getListProblem().isEmpty()) {
                throw new ToolProblem(panel.getListProblem());
            }
            panel.play(order(panel));
            ordered = true;
            return null;
        }
        var report = panel.report(viewer.backend());
        if (!Boolean.TRUE.equals(report.get("ready"))) {
            return null;
        }
        var data = new JSONObject(report);
        if (!others.isEmpty()) {
            data.put("others", others);
        }
        var watch = watch(data.getString("caster_at"), data.getString("target_at"));
        if (watch != null) {
            data.put("watch", watch);
        }
        return ToolResult.data(data)
                .saying("Plays "
                        + (report.containsKey("skill") ? report.get("name") : report.get("effect"))
                        + " in the Effects tool");
    }

    private Order order(EffectsPanel panel) throws ToolProblem {
        Effect effect = null;
        Skill skill = null;
        var effectWords = args.getText("effect");
        if (effectWords != null) {
            var listed = new ArrayList<>(panel.copyList());
            listed.addAll(panel.getEffectList());
            effect = EffectLookup.effect(listed, effectWords);
        }
        var skillWords = args.getText("skill");
        if (skillWords != null) {
            if (panel.getSkillList().isEmpty()) {
                throw new ToolProblem("This client has no skill package. Skills need System/LineageSkillEffect.u, or"
                        + " Animations/skill.usk from Chronicle 3 to Interlude. C1 and C2 have neither: they"
                        + " keep the effects of their skills in the code of Engine.dll. effect still plays each"
                        + " effect class.");
            }
            var found = EffectLookup.skill(panel.getSkillList(), panel.getCasting()::getSkillName, skillWords);
            skill = found.skill();
            others.addAll(found.others());
        }
        var catalog = panel.getCasting().getCatalog();
        var casterWords = args.getText("caster");
        var targetWords = args.getText("target_character");
        Who caster = casterWords == null ? null : EffectLookup.who(catalog, casterWords, "none");
        Who target = targetWords == null ? null : EffectLookup.target(catalog, targetWords);
        for (var words : new String[] {casterWords, targetWords}) {
            var race = words == null ? null : EffectLookup.findRace(words);
            var missing = race == null ? null : panel.findMissingRace(race);
            if (missing != null) {
                throw new ToolProblem(missing);
            }
        }
        var spotWords = args.getText("spot");
        Spot spot = spotWords == null ? null : EffectLookup.spot(spotWords);
        return new Order(
                effect,
                skill,
                args.has("level") ? args.getInteger("level", 1) : null,
                args.has("speed") ? args.getInteger("speed", 0) : null,
                args.has("distance") ? args.getInteger("distance", 0) : null,
                caster,
                target,
                spot,
                place(),
                args.has("ground") ? args.readFlag("ground", false) : null,
                args.has("repeat") ? args.readFlag("repeat", true) : null,
                args.readFlag("frame", true));
    }

    private @Nullable Vec3 place() throws ToolProblem {
        var text = args.getText("target");
        if (text == null) {
            return null;
        }
        var at = viewer.locate(text);
        if (at.z() == null) {
            throw new ToolProblem("The target " + text
                    + " has no height. Give a point x,y,z, as world_probe reads the ground, or leave target"
                    + " out to stand it where the middle of the view meets the ground.");
        }
        return new Vec3(at.x(), at.y(), (float) at.z());
    }
}

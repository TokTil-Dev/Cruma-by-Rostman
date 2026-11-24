package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.SceneClassWriter.Loss;
import com.rost.cruma.core.editing.SceneClassWriter.Report;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.unreal.UMeshAnimation.CameraMark.Kind;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneRig;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

public record SceneCheck(
        String scene,
        double seconds,
        double originalSeconds,
        List<Loss> lost,
        List<Lack> lacks,
        List<Mark> marks,
        List<PathError> path) {
    private static final double SAME_LENGTH = 1e-3;

    public SceneCheck {
        lost = List.copyOf(lost);
        lacks = List.copyOf(lacks);
        marks = List.copyOf(marks);
        path = List.copyOf(path);
    }

    public enum Need {
        NPC,
        SKILL,
        SEQUENCE
    }

    public record Lack(double seconds, Need need, String name) {}

    public record Mark(double seconds, int index, int npc, String sequence) {}

    public record PathError(
            int rate,
            double units,
            double unitsAt,
            double degrees,
            double degreesAt,
            double startDegrees,
            double rollDegrees,
            double fovDegrees,
            int steps) {}

    public interface Stock {
        boolean hasNpc(int npc);

        boolean hasSkill(int skill);

        boolean hasSequence(int npc, String sequence);
    }

    public static SceneCheck of(
            Scene original,
            SceneDocument written,
            Report report,
            List<Item> replaced,
            Stock stock,
            List<Mark> marks,
            List<PathError> path) {
        var lost = new ArrayList<>(report.losses());
        for (var item : replaced) {
            lost.add(new Loss(item.seconds(), "Shot", "CameraInfo", "the camera path takes its place"));
        }
        return new SceneCheck(
                report.scene(),
                written.seconds(),
                original.seconds(),
                lost,
                lacks(written.scene(), stock),
                marks,
                path);
    }

    public static List<Mark> marks(Scene scene, IntFunction<@Nullable SceneRig> rigs) {
        var result = new ArrayList<Mark>();
        var read = new HashMap<Integer, @Nullable SceneRig>();
        var npcs = new HashMap<Integer, Integer>();
        var times = scene.times();
        for (var i = 0; i < times.length; i++) {
            for (var order : scene.items().get(i).actors()) {
                if (order.player()) {
                    continue;
                }
                if (order.action().enters()) {
                    npcs.put(order.index(), order.npc());
                } else if (order.action() == Action.ANIMATION && npcs.containsKey(order.index())) {
                    var npc = npcs.get(order.index());
                    var rig = read.computeIfAbsent(npc, rigs::apply);
                    if (rig != null && takesCamera(rig, order.animation())) {
                        result.add(new Mark(times[i], order.index(), npc, order.animation()));
                    }
                }
            }
        }
        return result;
    }

    public static Stock stock(Path client, WorldVariant variant, IntFunction<@Nullable SceneRig> rigs)
            throws IOException {
        var looks = NpcGrpTable.read(system(client), variant);
        var catalog = ClientCatalog.read(client, variant);
        var read = new HashMap<Integer, @Nullable SceneRig>();
        return new Stock() {
            @Override
            public boolean hasNpc(int npc) {
                return looks.getAppearance(npc) != null;
            }

            @Override
            public boolean hasSkill(int skill) {
                return catalog.hasLevel(skill, 1);
            }

            @Override
            public boolean hasSequence(int npc, String sequence) {
                var rig = read.computeIfAbsent(npc, rigs::apply);
                return rig == null || rig.findSequence(sequence) >= 0;
            }
        };
    }

    public boolean runsLonger() {
        return seconds > originalSeconds + SAME_LENGTH;
    }

    private static boolean takesCamera(SceneRig rig, String name) {
        var sequence = rig.findSequence(name);
        if (sequence < 0) {
            return false;
        }
        for (var mark : rig.getMarks(sequence)) {
            if (mark.time() > 0 && !mark.ownCamera() && mark.kind() != Kind.RELEASE) {
                return true;
            }
        }
        return false;
    }

    private static List<Lack> lacks(Scene scene, Stock stock) {
        var result = new LinkedHashSet<Lack>();
        var seen = new LinkedHashSet<String>();
        var npcs = new HashMap<Integer, Integer>();
        var times = scene.times();
        for (var i = 0; i < times.length; i++) {
            for (var order : scene.items().get(i).actors()) {
                if (order.player()) {
                    continue;
                }
                if (order.action().enters()) {
                    npcs.put(order.index(), order.npc());
                    if (order.stands()
                            && order.action() != Action.AIRSHIP_SPAWN
                            && !stock.hasNpc(order.npc())
                            && seen.add("npc " + order.npc())) {
                        result.add(new Lack(times[i], Need.NPC, String.valueOf(order.npc())));
                    }
                } else if (order.action() == Action.SKILL
                        && !stock.hasSkill(order.skill())
                        && seen.add("skill " + order.skill())) {
                    result.add(new Lack(times[i], Need.SKILL, String.valueOf(order.skill())));
                } else if (order.action() == Action.ANIMATION && npcs.containsKey(order.index())) {
                    var npc = npcs.get(order.index());
                    var name = order.animation() + " of NPC " + npc;
                    if (stock.hasNpc(npc) && !stock.hasSequence(npc, order.animation()) && seen.add(name)) {
                        result.add(new Lack(times[i], Need.SEQUENCE, name));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static Path system(Path client) throws IOException {
        try (var entries = Files.list(client)) {
            return entries.filter(entry -> entry.getFileName().toString().equalsIgnoreCase("System"))
                    .findFirst()
                    .orElse(client.resolve("System"));
        }
    }
}

package com.rost.cruma.core.export;

import com.rost.cruma.core.export.LocationExport.Area;
import com.rost.cruma.core.export.NpcExport.Npc;
import com.rost.cruma.core.world.MapPawn;
import com.rost.cruma.core.world.MapPawnPlayer;
import com.rost.cruma.core.world.NpcAnimation;
import com.rost.cruma.core.world.NpcModel;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.UnrealLoader.NpcRig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Nullable;

final class MapFigures {
    static final String FOLDER = "figures";

    private final UnrealLoader loader;
    private final Map<String, Kind> kinds = new LinkedHashMap<>();
    private final Set<String> files = new HashSet<>();
    private final List<String> unreadable = new ArrayList<>();

    MapFigures(UnrealLoader loader) {
        this.loader = loader;
    }

    record Figure(
            MapPawn pawn,
            NpcModel model,
            float @Nullable [] positions,
            float @Nullable [] normals,
            String pose,
            String rig,
            boolean lobby,
            Map<String, Float> seconds) {
        Figure {
            seconds = Collections.unmodifiableMap(new LinkedHashMap<>(seconds));
        }
    }

    record Kind(
            String mesh,
            List<String> skins,
            String file,
            @Nullable NpcRig rig,
            @Nullable NpcAnimation animation) {
        Npc toNpc() {
            return new Npc(0, objectName(mesh), "", mesh, skins, 1f, false);
        }
    }

    static String findFirstSequence(MapPawn pawn, ToDoubleFunction<String> lengths, boolean lobby) {
        var moment = new MapPawnPlayer(pawn.animations(), lengths, pawn.getSeed(), lobby).findMoment(0);
        return moment == null ? "" : moment.sequence();
    }

    static String chooseFileName(String mesh, Set<String> taken) {
        var base = LocationExport.toSafeName(objectName(mesh));
        var candidate = base + ".glb";
        for (var number = 2; !taken.add(candidate.toLowerCase(Locale.ROOT)); number++) {
            candidate = base + "_" + number + ".glb";
        }
        return candidate;
    }

    List<Figure> collect(UMap map, @Nullable Area area) {
        var lobby = MapPawnPlayer.isLobby(map.name());
        var result = new ArrayList<Figure>();
        for (var pawn : map.pawns()) {
            if (area != null
                    && !area.contains(pawn.location().getX(), pawn.location().getY())) {
                continue;
            }
            var kind = findKind(pawn);
            var rig = kind.rig();
            if (rig == null) {
                unreadable.add(pawn.path());
                continue;
            }
            var animation = kind.animation();
            var pose = findFirstSequence(pawn, name -> computeLength(animation, name), lobby);
            NpcAnimation.Pose posed = null;
            if (animation != null) {
                posed = pose.isEmpty()
                        ? animation.getReferencePose()
                        : animation.getPose(animation.findSequence(pose), 0);
            }
            var seconds = new LinkedHashMap<String, Float>();
            var animations = pawn.animations();
            var names = new ArrayList<>(animations.list());
            names.add(animations.interval());
            names.add(animations.first());
            for (var name : names) {
                var length = computeLength(animation, name);
                if (length > 0) {
                    seconds.putIfAbsent(name, (float) length);
                }
            }
            result.add(new Figure(
                    pawn,
                    rig.model(),
                    posed == null ? null : posed.positions(),
                    posed == null ? null : posed.normals(),
                    pose,
                    FOLDER + "/" + kind.file(),
                    lobby,
                    seconds));
        }
        return List.copyOf(result);
    }

    List<Kind> getKinds() {
        return kinds.values().stream().filter(kind -> kind.rig() != null).toList();
    }

    List<String> getUnreadable() {
        return List.copyOf(unreadable);
    }

    private Kind findKind(MapPawn pawn) {
        var key = pawn.mesh() + "#" + String.join(",", pawn.skins());
        var known = kinds.get(key);
        if (known != null) {
            return known;
        }
        NpcRig rig;
        try {
            rig = loader.loadNpcRig(pawn.mesh(), pawn.skins());
        } catch (RuntimeException unreadableMesh) {
            rig = null;
        }
        var kind = new Kind(
                pawn.mesh(),
                pawn.skins(),
                rig == null ? "" : chooseFileName(pawn.mesh(), files),
                rig,
                rig == null ? null : NpcAnimation.of(rig));
        kinds.put(key, kind);
        return kind;
    }

    private static double computeLength(@Nullable NpcAnimation animation, String name) {
        if (animation == null) {
            return 0;
        }
        var sequence = animation.findSequence(name);
        return sequence < 0 ? 0 : animation.getSeconds(sequence);
    }

    private static String objectName(String reference) {
        return reference.substring(reference.lastIndexOf('.') + 1);
    }
}

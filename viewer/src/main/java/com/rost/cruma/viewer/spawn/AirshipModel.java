package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.world.NpcAnimation;
import com.rost.cruma.core.world.NpcModel;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.viewer.spawn.SpawnModels.Animated;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record AirshipModel(List<Part> parts) {
    private static final String TEXTURES = "L2_Ship_L01_T.fly_boat.fly_boat_";
    private static final List<Source> SOURCES = List.of(
            new Source("hull", "L2_ship_L01_S.fly_boat_body002", null, 0, 0, 0),
            new Source(
                    "mast",
                    "LineageVehicles.fly_boat_body004",
                    List.of(
                            TEXTURES + "airstone001_sh",
                            TEXTURES + "pillar001",
                            TEXTURES + "sail002_sh",
                            TEXTURES + "boby004_sh",
                            TEXTURES + "line001",
                            TEXTURES + "front002_sh",
                            TEXTURES + "boby001"),
                    0,
                    0,
                    0),
            new Source(
                    "tail",
                    "LineageVehicles.fly_boat_body003",
                    List.of(
                            TEXTURES + "still001_sh",
                            TEXTURES + "still002_sh",
                            TEXTURES + "still003",
                            TEXTURES + "back001_sh",
                            TEXTURES + "boby004_sh"),
                    -498,
                    0,
                    0),
            new Source("wheel", "L2_ship_L01_S.fly_boat_steering001", null, 363, 0, 102));

    public AirshipModel {
        parts = List.copyOf(parts);
    }

    public record Part(
            String label,
            String mesh,
            List<String> textures,
            SpawnModelData model,
            @Nullable Animated animated,
            float x,
            float y,
            float z) {}

    static @Nullable AirshipModel read(UnrealLoader loader) {
        var parts = new ArrayList<Part>();
        for (var source : SOURCES) {
            var part = source.textures() == null ? still(loader, source) : skeletal(loader, source);
            if (part != null) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : new AirshipModel(parts);
    }

    private static @Nullable Part still(UnrealLoader loader, Source source) {
        var model = loader.loadStaticModel(source.mesh());
        return model == null ? null : part(model, null, source);
    }

    private static @Nullable Part skeletal(UnrealLoader loader, Source source) {
        var rig = loader.loadNpcRig(source.mesh(), source.textures());
        return rig == null ? null : part(rig.model(), NpcAnimation.of(rig), source);
    }

    private static @Nullable Part part(NpcModel model, @Nullable NpcAnimation animation, Source source) {
        var data = SpawnModels.toModelData(0, model, 1f);
        if (data == null) {
            return null;
        }
        return new Part(
                source.label(),
                source.mesh(),
                source.textures() == null ? List.of() : source.textures(),
                data,
                animation == null ? null : new Animated(data, animation, 1f),
                source.x(),
                source.y(),
                source.z());
    }

    private record Source(
            String label, String mesh, @Nullable List<String> textures, float x, float y, float z) {}
}

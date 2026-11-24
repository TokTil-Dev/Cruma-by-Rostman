package com.rost.cruma.core.world;

import com.rost.cruma.core.world.Entity.Texture;
import glm_.vec3.Vec3;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public record USky(
        Map<String, List<Entity>> layers,
        List<Layer> drawLayers,
        List<String> activeSkyMaterials,
        List<String> cloudMaterials,
        Vec3 origin,
        @Nullable Celestial sun,
        List<Celestial> moons,
        List<Flare> flares,
        float texUPanSpeed,
        float texVPanSpeed) {
    public boolean isLayerActive(String name) {
        return activeSkyMaterials.isEmpty()
                || activeSkyMaterials.stream().anyMatch(configured -> configured.equalsIgnoreCase(name));
    }

    public boolean isLayerActive(Layer layer) {
        return layer.stateLook() || isLayerActive(layer.name());
    }

    public int cloudIndex(String name) {
        for (var i = 0; i < cloudMaterials.size(); i++) {
            if (cloudMaterials.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    public record Layer(String name, int bspSection, Entity entity, boolean stateLook) {
        public Layer(String name, int bspSection, Entity entity) {
            this(name, bspSection, entity, false);
        }

        public boolean showsInZoneState(ZoneStateChoice choice) {
            return entity.getZoneRender().shows(choice);
        }
    }

    public record Celestial(Texture sprite, float drawScale, float radius, int envType) {}

    public record Flare(Texture sprite, float offset, float scale) {}
}

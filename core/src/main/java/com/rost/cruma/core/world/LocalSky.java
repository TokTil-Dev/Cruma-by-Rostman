package com.rost.cruma.core.world;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record LocalSky(@Nullable USky sky, List<SpriteEmitterDescriptor> sprites, boolean ownSky, List<SkyZone> zones) {
    public LocalSky {
        sprites = List.copyOf(sprites);
        zones = List.copyOf(zones);
    }

    public LocalSky(USky sky, List<SpriteEmitterDescriptor> sprites) {
        this(sky, sprites, true, List.of());
    }
}

package com.rost.cruma.viewer.system;

import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.core.world.ZoneStateItem;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.application.WindowContext.Tile;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.render.api.SceneUploadSink;
import com.rost.cruma.viewer.system.LoadingSystem.MapName;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LevelSystem implements System {
    private static final Logger log = LogManager.getLogger(LevelSystem.class);
    private final Path rootPath;
    private final List<MapName> maps;
    private final SceneUploadSink renderer;
    private final WindowContext windowContext;
    private final Options dxtMipOptions;
    private final TextureQuality textureQuality;
    private final WorldVariant variant;

    public LevelSystem(
            World worldConfig, List<String> mapNames, SceneUploadSink renderer, WindowContext windowContext) {
        this.rootPath = worldConfig.clientPath();
        this.renderer = renderer;
        this.windowContext = windowContext;
        this.maps = LoadingSystem.sortMaps(mapNames, worldConfig);
        var rendering = worldConfig.rendering();
        this.dxtMipOptions = new Options(rendering.dxtMipCodecEnabled(), rendering.dxtMipMinSize());
        this.textureQuality = rendering.textureQuality();
        this.variant = worldConfig.variant();
    }

    @Override
    public void frameBegin(long frameTime) {
        if (isComplete()) {
            return;
        }
        if (windowContext.mapIndex >= 0 && windowContext.mapCaptureSubmitted < windowContext.mapIndex) {
            return;
        }

        var unrealLoader = new UnrealLoader(rootPath, dxtMipOptions, true, textureQuality);
        unrealLoader.worldVariant(variant);
        var mapName = maps.get(++windowContext.mapIndex);

        final UMap map;
        try {
            map = unrealLoader.loadMap(mapName.toString());
        } finally {
            unrealLoader.trimCaches();
            unrealLoader.getDxtChains().clear();
        }
        if (map != null) {
            log.info("Preparing {} map for rendering", mapName);
            renderer.renderMap(map);
            var position = map.position();
            var tile = new Tile(mapName.x(), mapName.y(), position.getX() + 32768f / 2, position.getY() + 32768f / 2);
            windowContext.tiles.add(tile);
            windowContext.pendingCaptureTile = tile;
            windowContext.pendingCaptureMap = ZoneStateItem.packageOf(map);
        } else {
            log.warn("Skipping capture for missing or unreadable map {}", mapName);
            windowContext.pendingCaptureTile = null;
            windowContext.pendingCaptureMap = "";
            windowContext.mapCaptureSubmitted = windowContext.mapIndex;
        }

        renderer.clearCache();
    }

    public boolean isComplete() {
        return maps.isEmpty() || windowContext.mapIndex >= maps.size() - 1;
    }

    public int getMapCount() {
        return maps.size();
    }

    public List<String> getMapNames() {
        return maps.stream().map(MapName::toString).toList();
    }
}

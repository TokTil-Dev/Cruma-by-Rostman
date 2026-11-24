package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.ULevel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class ActorRegions {
    static final ActorRegions NONE = new ActorRegions(null, new int[0]);

    private final @Nullable NativeBspRegions regions;
    private final int[] zoneActors;

    private ActorRegions(@Nullable NativeBspRegions regions, int[] zoneActors) {
        this.regions = regions;
        this.zoneActors = zoneActors.clone();
    }

    static ActorRegions read(Path map, PackageBytes source, List<Integer> actors) throws IOException {
        var directory = map.toAbsolutePath().getParent();
        try (var archive = new UArchive(
                source.packageName,
                ByteBuffer.wrap(source.body.clone()).order(ByteOrder.LITTLE_ENDIAN),
                new UArchiveLoader(directory, List.of()))) {
            var levels = new ArrayList<ULevel>();
            archive.loadObjects("Level", levels);
            if (levels.isEmpty() || levels.getFirst().getModel().isNull()) {
                return NONE;
            }
            var model = levels.getFirst().getModel().get();
            if (model == null || model.getZones() == null || model.getNodes() == null || model.getNodes().length == 0) {
                return NONE;
            }
            NativeBspRegions regions;
            try {
                regions = NativeBspRegions.shadowSnapshot(model);
            } catch (IllegalArgumentException unqualified) {
                return NONE;
            }
            var levelInfo = actors.isEmpty() ? 0 : actors.getFirst();
            var zoneActors = new int[model.getZones().length];
            for (var i = 0; i < zoneActors.length; i++) {
                var zone = model.getZones()[i].getZoneActor().getIndex();
                zoneActors[i] = zone != 0 ? zone : levelInfo;
            }
            return new ActorRegions(regions, zoneActors);
        } catch (RuntimeException unreadable) {
            throw new IOException(
                    unreadable.getMessage() == null ? unreadable.toString() : unreadable.getMessage(), unreadable);
        }
    }

    synchronized @Nullable LightRegion getRegion(Vector3 location) {
        if (regions == null) {
            return null;
        }
        var region = regions.pointRegion(new Vector(location.x(), location.y(), location.z()));
        var zone = region.zone() < zoneActors.length ? zoneActors[region.zone()] : 0;
        return zone == 0 ? null : new LightRegion(zone, region.leaf(), region.zone());
    }
}

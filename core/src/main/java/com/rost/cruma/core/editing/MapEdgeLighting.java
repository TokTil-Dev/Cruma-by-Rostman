package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.BspLightmapRebuild.TerrainChange;
import com.rost.cruma.core.editing.PackageEditDocument.LightingEdit;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.core.world.WorldTile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class MapEdgeLighting {
    public static final float REACH = ActorLightingRebuild.NEIGHBOR_RADIUS;

    private static final String[] MESH_ACTOR_CLASSES = {
        "StaticMeshActor", "MovableStaticMeshActor", "L2MovableStaticMeshActor"
    };

    private MapEdgeLighting() {}

    public static List<String> findNeighbors(String map, List<float[]> boxes) {
        WorldTile tile;
        try {
            tile = WorldTile.fromMapName(map);
        } catch (RuntimeException noTile) {
            return List.of();
        }
        var x0 = (float) (tile.x() - WorldDimensions.TILE_ZERO_COORD_X) * WorldDimensions.TILE_SIZE;
        var y0 = (float) (tile.y() - WorldDimensions.TILE_ZERO_COORD_Y) * WorldDimensions.TILE_SIZE;
        var x1 = x0 + WorldDimensions.TILE_SIZE;
        var y1 = y0 + WorldDimensions.TILE_SIZE;
        var result = new TreeSet<String>();
        for (var box : boxes) {
            if (box.length != 6 || !(box[0] <= box[3]) || !(box[1] <= box[4])) {
                continue;
            }
            var west = box[0] - x0 <= REACH;
            var east = x1 - box[3] <= REACH;
            var north = box[1] - y0 <= REACH;
            var south = y1 - box[4] <= REACH;
            for (var dx = -1; dx <= 1; dx++) {
                for (var dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    var nearX = dx == 0 || (dx < 0 ? west : east);
                    var nearY = dy == 0 || (dy < 0 ? north : south);
                    if (nearX && nearY) {
                        result.add((tile.x() + dx) + "_" + (tile.y() + dy));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    public static List<float[]> changedBoxes(
            PackageEditDocument document, UArchiveLoader archives, List<TerrainChange> terrain) throws IOException {
        var result = new ArrayList<float[]>();
        for (var change : terrain) {
            if (!change.isEmpty()) {
                result.add(change.box().clone());
            }
        }
        for (var edit : document.getLightPlacementEdits()) {
            for (var placement : edit.placements()) {
                var location = placement.location();
                var reach = placement.reach();
                result.add(new float[] {
                    location.x() - reach,
                    location.y() - reach,
                    location.z() - reach,
                    location.x() + reach,
                    location.y() + reach,
                    location.z() + reach
                });
            }
        }
        var edits = new ArrayList<LightingEdit>();
        for (var edit : document.getLightingEdits()) {
            if (edit.created() || edit.deleted() || !edit.original().equals(edit.current())) {
                edits.add(edit);
            }
        }
        if (edits.isEmpty()) {
            return result;
        }
        var file = document.getSource().getFileName().toString();
        var dot = file.lastIndexOf('.');
        var name = dot < 0 ? file : file.substring(0, dot);
        Map<String, UStaticMesh> meshes = new HashMap<>();
        try (var archive =
                new UArchive(name, ByteBuffer.wrap(document.previewBody()).order(ByteOrder.LITTLE_ENDIAN), archives)) {
            var actors = new ArrayList<StaticMeshActor>();
            for (var type : MESH_ACTOR_CLASSES) {
                archive.loadObjects(type, actors);
            }
            for (var actor : actors) {
                try {
                    var mesh = actor.getStaticMesh().get();
                    if (mesh != null) {
                        meshes.putIfAbsent(actor.getSourcePath(), mesh);
                    }
                } catch (RuntimeException missing) {
                }
            }
            for (var edit : edits) {
                var mesh = meshes.get(edit.path());
                if (!edit.created() && edit.original() != null) {
                    result.add(computeBox(mesh, edit.original()));
                }
                if (!edit.deleted()) {
                    result.add(computeBox(mesh, edit.current()));
                }
            }
        } catch (RuntimeException unreadable) {
            for (var edit : edits) {
                if (!edit.created() && edit.original() != null) {
                    result.add(toPointBox(edit.original()));
                }
                if (!edit.deleted()) {
                    result.add(toPointBox(edit.current()));
                }
            }
        }
        return result;
    }

    private static float[] computeBox(UStaticMesh mesh, ActorTransform transform) {
        if (mesh != null && mesh.getBoundingBox() != null) {
            var box = ActorLightingRebuild.worldBox(mesh, transform);
            if (box != null) {
                return box;
            }
        }
        return toPointBox(transform);
    }

    private static float[] toPointBox(ActorTransform transform) {
        var location = transform.location();
        return new float[] {location.x(), location.y(), location.z(), location.x(), location.y(), location.z()};
    }
}

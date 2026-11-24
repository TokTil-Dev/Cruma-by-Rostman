package com.rost.cruma.core.editing;

import com.rost.cruma.core.unreal.UActor.BrushActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.world.BrushTransforms;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BspBrushShapes {
    private BspBrushShapes() {}

    record Shape(List<double[][]> polygons, List<String> itemNames) {
        static final Shape NONE = new Shape(List.of(), List.of());
    }

    static Map<Integer, Shape> read(Path map, PackageBytes source, Collection<Integer> brushes) throws IOException {
        var directory = map.toAbsolutePath().getParent();
        try (var archive = new UArchive(
                source.packageName,
                ByteBuffer.wrap(source.body.clone()).order(ByteOrder.LITTLE_ENDIAN),
                new UArchiveLoader(directory, List.of()))) {
            var result = new HashMap<Integer, Shape>();
            for (var brush : brushes) {
                var shape = Shape.NONE;
                try {
                    if (archive.getObjectLoader().loadObject(brush) instanceof BrushActor actor) {
                        shape = of(actor);
                    }
                } catch (RuntimeException unreadable) {
                }
                result.put(brush, shape);
            }
            return result;
        } catch (RuntimeException unreadable) {
            throw new IOException(
                    unreadable.getMessage() == null ? unreadable.toString() : unreadable.getMessage(), unreadable);
        }
    }

    static Shape of(BrushActor actor) {
        var brushModel = actor.getBrush().get();
        var polys = brushModel == null ? null : brushModel.getPolys().get();
        if (polys == null) {
            return Shape.NONE;
        }
        var matrix = BrushTransforms.actorMatrix(
                        actor.getLocation(), actor.getPrePivot(), actor.getRotation(), actor.getScale())
                .to(new float[16]);
        var polygons = new ArrayList<double[][]>();
        var names = new ArrayList<String>();
        for (var poly : polys.getPolygons()) {
            var vertices = poly.vertices();
            var world = new double[vertices.size()][];
            for (var k = 0; k < vertices.size(); k++) {
                var vertex = vertices.get(k);
                double x = vertex.getX();
                double y = vertex.getY();
                double z = vertex.getZ();
                world[k] = new double[] {
                    matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12],
                    matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13],
                    matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
                };
            }
            polygons.add(world);
            names.add(poly.itemName() == null ? "None" : poly.itemName());
        }
        return new Shape(polygons, names);
    }
}

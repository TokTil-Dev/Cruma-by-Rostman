package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class P152BspTriangles {
    private P152BspTriangles() {}

    static List<Triangle> capture(P152FreshBakeContext context, BooleanSupplier cancelled) throws IOException {
        context.verifySourcesUnchanged(cancelled);
        var source = new PackageBytes(context.getMapPath(), context.getMapBytes());
        P152BspReceiverModel.decode(source, context.getModelReference());
        var loader = new UArchiveLoader(context.getClientRoot(), List.of()) {
            @Override
            public UArchive loadArchive(String name) {
                throw new IllegalStateException("Frozen BSP triangles cannot load external packages");
            }

            @Override
            public UArchive loadArchiveIfPresent(String name) {
                throw new IllegalStateException("Frozen BSP triangles cannot load external packages");
            }
        };
        try (var archive =
                new UArchive(source.packageName, ByteBuffer.wrap(source.body).order(ByteOrder.LITTLE_ENDIAN), loader)) {
            var object = archive.getObjectLoader().loadObject(context.getModelReference());
            if (!(object instanceof UModel model)) {
                throw new IOException("BSP triangles require a native Model");
            }
            var triangles = snapshot(model, cancelled);
            context.verifySourcesUnchanged(cancelled);
            return triangles;
        } catch (CancellationException cancelledCapture) {
            throw cancelledCapture;
        } catch (IllegalArgumentException | IllegalStateException malformed) {
            throw new IOException("Incomplete BSP polygon geometry", malformed);
        }
    }

    static List<Triangle> snapshot(UModel model, BooleanSupplier cancelled) {
        P152FreshHashQuery.checkCancelled(cancelled);
        NativeBspTrace.requireCompleteModel(model);
        var result = new ArrayList<Triangle>();
        var points = model.getPoints();
        var pool = model.getVertices();
        var nodes = model.getNodes();
        for (int n = 0; n < nodes.length; n++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var node = nodes[n];
            if (node == null) {
                throw new IllegalArgumentException("Missing BSP polygon node");
            }
            int count = node.getVertexCount(), start = node.getVertexPoolIndex();
            int surface = node.getSurfaceIndex();
            if (count < 0
                    || count > 255
                    || surface < 0
                    || surface >= model.getSurfaces().length
                    || model.getSurfaces()[surface] == null
                    || count > 0 && (start < 0 || (long) start + count > pool.length)) {
                throw new IllegalArgumentException("BSP polygon references are outside their arrays");
            }
            if ((long) result.size() + Math.max(0, count - 2) > 1_000_000) {
                throw new IllegalArgumentException("BSP triangle count exceeds editor bounds");
            }
            var polygon = new Vector[count];
            for (int v = 0; v < count; v++) {
                var vertex = pool[start + v];
                int index = vertex == null ? -1 : vertex.getVertexIndex();
                if (index < 0 || index >= points.length || points[index] == null) {
                    throw new IllegalArgumentException("Missing BSP polygon point");
                }
                var point = points[index];
                polygon[v] = new Vector(point.getX(), point.getY(), point.getZ());
                if (!Float.isFinite(point.getX()) || !Float.isFinite(point.getY()) || !Float.isFinite(point.getZ())) {
                    throw new IllegalArgumentException("Non-finite BSP polygon point");
                }
            }
            for (int v = 2; v < count; v++) {
                result.add(new Triangle(0, surface, n, polygon[0], polygon[v], polygon[v - 1]));
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return List.copyOf(result);
    }
}

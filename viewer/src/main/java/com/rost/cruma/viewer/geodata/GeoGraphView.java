package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.core.geodata.PathNodeGraph.Node;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public record GeoGraphView(List<Node> nodes, List<Edge> edges, boolean truncated, String fingerprint) {
    public GeoGraphView {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public record Edge(int fromIndex, int toIndex, GeoPoint from, GeoPoint to) {}

    public static GeoGraphView read(
            PathNodeGraph graph, GeoPoint center, int radius, int maximumNodes, BooleanSupplier cancelled)
            throws IOException {
        if (radius < 16 || radius > 4096 || maximumNodes < 1 || maximumNodes > 100000) {
            throw new IllegalArgumentException("Invalid graph display bounds");
        }
        if (!graph.isSourceUnchanged()) {
            throw new IOException("Path-node source changed before graph display");
        }
        var nodes = new ArrayList<Node>();
        var edges = new ArrayList<Edge>();
        for (var x = Math.floorDiv((int) center.x() - radius, 256) * 256; x <= center.x() + radius; x += 256) {
            for (var y = Math.floorDiv((int) center.y() - radius, 256) * 256; y <= center.y() + radius; y += 256) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("Graph display cancelled");
                }
                var section = graph.getSection(x, y);
                for (var index = section.firstIndex(); index < section.firstIndex() + section.count(); index++) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Graph display cancelled");
                    }
                    var node = graph.getNode(index);
                    if (Math.abs(node.position().x() - center.x()) > radius
                            || Math.abs(node.position().y() - center.y()) > radius) {
                        continue;
                    }
                    if (nodes.size() == maximumNodes) {
                        return finish(graph, nodes, edges, true, cancelled);
                    }
                    nodes.add(node);
                    for (var neighborIndex : node.neighbors()) {
                        if (neighborIndex == 0) {
                            break;
                        }
                        var neighbor = graph.getNode(neighborIndex);
                        edges.add(new Edge(index, neighborIndex, node.position(), neighbor.position()));
                    }
                }
            }
        }
        return finish(graph, nodes, edges, false, cancelled);
    }

    private static GeoGraphView finish(
            PathNodeGraph graph, List<Node> nodes, List<Edge> edges, boolean truncated, BooleanSupplier cancelled)
            throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Graph display cancelled");
        }
        if (!graph.isSourceUnchanged()) {
            throw new IOException("Path-node source changed during graph display");
        }
        return new GeoGraphView(nodes, edges, truncated, graph.getMetadata().fingerprint());
    }
}

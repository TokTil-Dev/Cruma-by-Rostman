package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoReachability.Access;
import com.rost.cruma.core.geodata.GeoReachability.Limits;
import com.rost.cruma.core.geodata.GeoReachability.Node;
import java.util.HashSet;
import java.util.Set;

public final class GeoConnectedSurface {
    private GeoConnectedSurface() {}

    public enum Connectivity {
        MUTUAL,
        OUTGOING,
        INCOMING,
        EITHER
    }

    public record Result(
            GeoCellRef seed, Connectivity connectivity, Set<Node> layers, GeoReachability.Result reachability) {
        public Result {
            layers = Set.copyOf(layers);
        }

        public boolean belongsTo(GeoDataAccess data) {
            return data != null && seed.belongsTo(data);
        }

        public boolean contains(Cell cell, Layer layer) {
            return layers.contains(new Node(cell.x(), cell.y(), layer.index(), layer.height()));
        }
    }

    public static Result select(GeoDataAccess data, GeoCellRef seed, Connectivity connectivity, Limits limits) {
        var reach = new GeoReachability(data).explore(seed, limits);
        var layers = new HashSet<Node>();
        for (var entry : reach.nodes().entrySet()) {
            var include = switch (connectivity) {
                case MUTUAL -> entry.getValue() == Access.MUTUAL;
                case OUTGOING -> entry.getValue() != Access.INBOUND_ONLY;
                case INCOMING -> entry.getValue() != Access.OUTBOUND_ONLY;
                case EITHER -> true;
            };
            if (include) {
                layers.add(entry.getKey());
            }
        }
        return new Result(seed, connectivity, layers, reach);
    }
}

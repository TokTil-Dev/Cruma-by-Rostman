package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class ClientGround implements GeoDataAccess {
    public static final String DETAIL = "Client floor";

    private static final String NO_TILE = "No client map tile here";
    private static final String NO_FLOOR = "No client floor here";
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int CELL = 16;
    private final @Nullable GeoDataAccess geodata;
    private final ClientFloors floors;

    private final long revision;

    private boolean readClient;

    public ClientGround(@Nullable GeoDataAccess geodata, ClientFloors floors) {
        this.geodata = geodata;
        this.floors = floors;
        revision = geodata == null ? floors.getRevision() : geodata.revision();
    }

    public static boolean isFromClient(Cell cell) {
        return DETAIL.equals(cell.detail());
    }

    public boolean hasReadClient() {
        return readClient;
    }

    @Override
    public Cell cellAt(int worldX, int worldY) {
        Cell server = null;
        if (geodata != null) {
            server = geodata.cellAt(worldX, worldY);
            if (server.availability() != Availability.MISSING) {
                return server;
            }
        }
        var x = Math.floorDiv(worldX, CELL) * CELL;
        var y = Math.floorDiv(worldY, CELL) * CELL;
        var client = findClientCell(x, y);
        if (client != null) {
            readClient = true;
            return client;
        }
        if (server != null) {
            return server;
        }
        var detail = floors.getFloors(x + CELL / 2.0, y + CELL / 2.0) == null ? NO_TILE : NO_FLOOR;
        return new Cell(x, y, Availability.MISSING, List.of(), detail, Storage.MULTI);
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public String getSourceId() {
        return geodata == null ? "client floors" : geodata.getSourceId();
    }

    private @Nullable Cell findClientCell(int x, int y) {
        var middleX = x + CELL / 2.0;
        var middleY = y + CELL / 2.0;
        var heights = floors.getFloors(middleX, middleY);
        if (heights == null || heights.length == 0) {
            return null;
        }
        var neighbors = new int[DIRECTIONS.length][];
        for (var i = 0; i < DIRECTIONS.length; i++) {
            neighbors[i] = floors.getFloors(middleX + DIRECTIONS[i].getDx(), middleY + DIRECTIONS[i].getDy());
        }
        var layers = new ArrayList<Layer>(heights.length);
        for (var i = heights.length - 1; i >= 0; i--) {
            var height = heights[i];
            var nswe = 0;
            for (var d = 0; d < DIRECTIONS.length; d++) {
                if (isOpen(neighbors[d], height)) {
                    nswe |= DIRECTIONS[d].getMask();
                }
            }
            layers.add(new Layer(layers.size(), height, nswe));
        }
        return new Cell(x, y, Availability.AVAILABLE, layers, DETAIL, Storage.MULTI);
    }

    private static boolean isOpen(int @Nullable [] next, int height) {
        if (next == null || next.length == 0) {
            return false;
        }
        var chosen = next[0];
        for (var i = next.length - 1; i >= 0; i--) {
            if (next[i] <= height + 26) {
                chosen = next[i];
                break;
            }
        }
        return Math.abs(chosen - height) <= ClientFloorGrid.STEP;
    }
}

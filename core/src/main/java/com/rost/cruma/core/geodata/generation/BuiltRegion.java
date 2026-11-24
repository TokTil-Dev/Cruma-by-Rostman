package com.rost.cruma.core.geodata.generation;

import java.io.IOException;
import java.util.Arrays;

public final class BuiltRegion {
    public static final int CELLS = 2048;
    public static final int CELL_SIZE = 16;
    public static final int REGION_SIZE = CELLS * CELL_SIZE;
    public static final int[] DIRECTION_X = {1, -1, 0, 0};
    public static final int[] DIRECTION_Y = {0, 0, 1, -1};

    private final int tileX;
    private final int tileY;
    private final int cellLimit;
    private final int minX;
    private final int minY;
    private final int[] head = new int[CELLS * CELLS];
    private short[] heights = new short[1 << 16];
    private float[] positions = new float[3 << 16];
    private byte[] flags = new byte[1 << 16];
    private int[] next = new int[1 << 16];
    private boolean[] dropped = new boolean[1 << 16];
    private int count;
    private int visited;

    public BuiltRegion(int tileX, int tileY, int cellLimit) {
        if (cellLimit < 1 || cellLimit > CELLS) {
            throw new IllegalArgumentException("Cell limit must be in 1.." + CELLS);
        }
        this.tileX = tileX;
        this.tileY = tileY;
        this.cellLimit = cellLimit;
        this.minX = (tileX - 20) * REGION_SIZE;
        this.minY = (tileY - 18) * REGION_SIZE;
        Arrays.fill(head, -1);
    }

    static int heightStep(float z) {
        return (int) z / 8;
    }

    public int getTileX() {
        return tileX;
    }

    public int getTileY() {
        return tileY;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getCellLimit() {
        return cellLimit;
    }

    public int getLayerTotal() {
        return count;
    }

    public int getVisitedCells() {
        return visited;
    }

    public int getLayerCount(int cellX, int cellY) {
        var layers = 0;
        for (var node = head[cellX * CELLS + cellY]; node >= 0; node = next[node]) {
            layers++;
        }
        return layers;
    }

    public int getFirstLayer(int cellX, int cellY) {
        return head[cellX * CELLS + cellY];
    }

    public int nextLayer(int layer) {
        return next[layer];
    }

    public int getHeight(int layer) {
        return heights[layer] * 8;
    }

    public float getX(int layer) {
        return positions[layer * 3];
    }

    public float getY(int layer) {
        return positions[layer * 3 + 1];
    }

    public float getZ(int layer) {
        return positions[layer * 3 + 2];
    }

    public boolean open(int layer, int direction) {
        return (flags[layer] & 1 << direction) != 0;
    }

    public int getNswe(int layer) {
        return flags[layer] & 15;
    }

    public boolean isDropped(int layer) {
        return dropped[layer];
    }

    public void writeText(Appendable out) throws IOException {
        out.append("total : ").append(Integer.toString(count)).append('\n');
        out.append("visited : ").append(Integer.toString(visited)).append('\n');
        out.append('(')
                .append(Integer.toString(minX))
                .append(", ")
                .append(Integer.toString(minY))
                .append(") ~ (")
                .append(Integer.toString(minX + REGION_SIZE - 1))
                .append(", ")
                .append(Integer.toString(minY + REGION_SIZE - 1))
                .append(")\n");
        out.append("cellsize : 16\nheightunit : 8\n");
        var lines = 0;
        for (var cellX = 0; cellX < CELLS; cellX++) {
            for (var cellY = 0; cellY < CELLS; cellY++) {
                out.append("\n[")
                        .append(Integer.toString(cellX))
                        .append(',')
                        .append(Integer.toString(cellY))
                        .append(']')
                        .append(Integer.toString(getLayerCount(cellX, cellY)));
                for (var node = head[cellX * CELLS + cellY]; node >= 0; node = next[node]) {
                    out.append('(').append(Integer.toString(heights[node] * 8)).append(':');
                    for (var direction = 0; direction < 4; direction++) {
                        out.append(open(node, direction) ? '1' : '0');
                    }
                    out.append(')');
                }
                lines++;
            }
        }
        out.append("\n\noutput : ").append(Integer.toString(lines)).append('\n');
    }

    int toCellX(float x) {
        var cell = ((int) x - minX) / CELL_SIZE;
        return cell < 0 || cell >= CELLS ? -1 : cell;
    }

    int toCellY(float y) {
        var cell = ((int) y - minY) / CELL_SIZE;
        return cell < 0 || cell >= CELLS ? -1 : cell;
    }

    int find(float x, float y, float z) {
        var cellX = toCellX(x);
        var cellY = toCellY(y);
        if (cellX < 0 || cellY < 0) {
            return -1;
        }
        var step = heightStep(z);
        for (var node = head[cellX * CELLS + cellY]; node >= 0; node = next[node]) {
            if (heights[node] == step) {
                return node;
            }
        }
        return -1;
    }

    int add(int cellX, int cellY, int step, float x, float y, float z, boolean drop) {
        if (cellX < 0 || cellY < 0 || cellX >= cellLimit || cellY >= cellLimit) {
            return -1;
        }
        var cell = cellX * CELLS + cellY;
        var previous = -1;
        var node = head[cell];
        while (node >= 0 && heights[node] > step) {
            previous = node;
            node = next[node];
        }
        if (node >= 0 && heights[node] == step) {
            return -1;
        }
        var added = allocateLayer(step, x, y, z, drop);
        next[added] = node;
        if (previous < 0) {
            if (head[cell] < 0) {
                visited++;
            }
            head[cell] = added;
        } else {
            next[previous] = added;
        }
        return added;
    }

    void setOpen(int layer, int direction, boolean open) {
        if (open) {
            flags[layer] |= (byte) (1 << direction);
        } else {
            flags[layer] &= (byte) ~(1 << direction);
        }
    }

    private int allocateLayer(int step, float x, float y, float z, boolean drop) {
        if (count == heights.length) {
            var capacity = heights.length * 2;
            heights = Arrays.copyOf(heights, capacity);
            positions = Arrays.copyOf(positions, capacity * 3);
            flags = Arrays.copyOf(flags, capacity);
            next = Arrays.copyOf(next, capacity);
            dropped = Arrays.copyOf(dropped, capacity);
        }
        var node = count++;
        heights[node] = (short) step;
        positions[node * 3] = x;
        positions[node * 3 + 1] = y;
        positions[node * 3 + 2] = z;
        flags[node] = 0;
        dropped[node] = drop;
        return node;
    }
}

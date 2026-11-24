package com.rost.cruma.core.geodata;

import java.util.List;

public class Geodata {
    private final List<Cell> cells;

    public Geodata(List<Cell> cells) {
        this.cells = cells;
    }

    public enum Direction {
        DIRECTION_N(0x8),
        DIRECTION_S(0x4),
        DIRECTION_W(0x2),
        DIRECTION_E(0x1);

        private final int id;

        Direction(int id) {
            this.id = id;
        }

        public int getId() {
            return this.id;
        }
    }

    public enum BlockType {
        BLOCK_SIMPLE,
        BLOCK_COMPLEX,
        BLOCK_MULTILAYER,
    }

    public record Cell(
            short x, short y, short z, BlockType type, boolean north, boolean south, boolean west, boolean east) {}

    public List<Cell> getCells() {
        return this.cells;
    }
}

package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.bgfx_dispatch;

final class BgfxComputeRows {
    static final int THREADS = 64;
    static final int ROW_GROUPS = 32_768;

    private BgfxComputeRows() {}

    static int columnsFor(int count) {
        return Math.min(groupsFor(count), ROW_GROUPS);
    }

    static int rowsFor(int count) {
        return Math.max(1, (groupsFor(count) + ROW_GROUPS - 1) / ROW_GROUPS);
    }

    static void dispatch(int view, BgfxProgram program, int count) {
        bgfx_dispatch(view, program.getHandle(), columnsFor(count), rowsFor(count), 1, BGFX_DISCARD_ALL);
    }

    static int groupColumnsFor(int groups) {
        return Math.clamp(groups, 1, ROW_GROUPS);
    }

    static int groupRowsFor(int groups) {
        return Math.max(1, (groups + ROW_GROUPS - 1) / ROW_GROUPS);
    }

    static void dispatchGroups(int view, BgfxProgram program, int groups) {
        bgfx_dispatch(view, program.getHandle(), groupColumnsFor(groups), groupRowsFor(groups), 1, BGFX_DISCARD_ALL);
    }

    private static int groupsFor(int count) {
        return Math.max(1, (count + THREADS - 1) / THREADS);
    }
}

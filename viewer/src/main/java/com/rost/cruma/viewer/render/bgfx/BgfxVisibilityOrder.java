package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_READ;
import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_WRITE;
import static org.lwjgl.bgfx.BGFX.bgfx_set_compute_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_compute_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class BgfxVisibilityOrder {
    static final int BLOCK_CANDIDATES = 1024;

    static final int COMMAND_WORDS = 4;

    private BgfxVisibilityOrder() {}

    static int blockCapacity(int candidateCapacity, int commandCapacity) {
        return Math.addExact(candidateCapacity / BLOCK_CANDIDATES, commandCapacity);
    }

    static int maskCapacity(int candidateCapacity, int commandCapacity) {
        return Math.addExact(candidateCapacity / Integer.SIZE, commandCapacity);
    }

    static Layout createLayout(int[] commandIndexes, int[] candidateCounts, int count) {
        if (count < 0 || count > commandIndexes.length || count > candidateCounts.length) {
            throw new IllegalArgumentException("invalid ordered command list");
        }
        var blockCount = 0;
        var commandCount = 0;
        for (var index = 0; index < count; index++) {
            if (candidateCounts[index] < 0) {
                throw new IllegalArgumentException("ordered command has negative candidates");
            }
            if (candidateCounts[index] > 0) {
                blockCount = Math.addExact(blockCount, blocksFor(candidateCounts[index]));
                commandCount++;
            }
        }
        var commands = ByteBuffer.allocateDirect(Math.multiplyExact(Math.max(1, commandCount), COMMAND_WORDS * 4))
                .order(ByteOrder.nativeOrder());
        var blocks = ByteBuffer.allocateDirect(Math.multiplyExact(Math.max(1, blockCount), 4))
                .order(ByteOrder.nativeOrder());
        var entry = 0;
        var firstBlock = 0;
        var maskWords = 0;
        for (var index = 0; index < count; index++) {
            var candidates = candidateCounts[index];
            if (candidates == 0) {
                continue;
            }
            commands.putInt(commandIndexes[index])
                    .putInt(firstBlock)
                    .putInt(candidates)
                    .putInt(maskWords);
            var blocksOfCommand = blocksFor(candidates);
            for (var block = 0; block < blocksOfCommand; block++) {
                blocks.putInt(entry);
            }
            entry++;
            firstBlock += blocksOfCommand;
            maskWords = Math.addExact(maskWords, (candidates + Integer.SIZE - 1) / Integer.SIZE);
        }
        commands.flip();
        blocks.flip();
        return new Layout(commands, blocks, blockCount, maskWords);
    }

    static void dispatch(
            int view,
            BgfxProgram count,
            BgfxProgram move,
            BgfxUniform config,
            float[] configData,
            Buffers buffers,
            int candidates,
            int commands,
            int blocks) {
        configData[0] = candidates;
        configData[1] = commands;
        configData[2] = blocks;
        configData[3] = 0f;
        bgfx_set_uniform(config.getHandle(), configData, 1);
        bgfx_set_compute_dynamic_index_buffer(0, buffers.commandData().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(1, buffers.counts().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_vertex_buffer(2, buffers.instancesOut().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(3, buffers.orderedCommands().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(4, buffers.blocks().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(5, buffers.masks().getHandle(), BGFX_ACCESS_WRITE);
        bgfx_set_compute_dynamic_index_buffer(6, buffers.blockCounts().getHandle(), BGFX_ACCESS_WRITE);
        BgfxComputeRows.dispatchGroups(view, count, blocks);
        bgfx_set_uniform(config.getHandle(), configData, 1);
        bgfx_set_compute_dynamic_vertex_buffer(0, buffers.bounds().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_vertex_buffer(1, buffers.instancesIn().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(2, buffers.commandData().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(3, buffers.counts().getHandle(), BGFX_ACCESS_WRITE);
        bgfx_set_compute_dynamic_vertex_buffer(4, buffers.instancesOut().getHandle(), BGFX_ACCESS_WRITE);
        bgfx_set_compute_dynamic_index_buffer(5, buffers.orderedCommands().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(6, buffers.blocks().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(7, buffers.masks().getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(8, buffers.blockCounts().getHandle(), BGFX_ACCESS_READ);
        BgfxComputeRows.dispatchGroups(view, move, blocks);
    }

    private static int blocksFor(int candidates) {
        return (candidates + BLOCK_CANDIDATES - 1) / BLOCK_CANDIDATES;
    }

    record Layout(ByteBuffer commands, ByteBuffer blocks, int blockCount, int maskWords) {}

    record Buffers(
            BgfxBuffer bounds,
            BgfxBuffer instancesIn,
            BgfxBuffer commandData,
            BgfxBuffer counts,
            BgfxBuffer instancesOut,
            BgfxBuffer orderedCommands,
            BgfxBuffer blocks,
            BgfxBuffer masks,
            BgfxBuffer blockCounts) {}
}

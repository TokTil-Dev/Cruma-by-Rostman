package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;

import com.rost.cruma.viewer.render.api.ReadbackRequest;
import com.rost.cruma.viewer.render.api.ReadbackRequest.Source;
import com.rost.cruma.viewer.render.api.ReadbackTicket;
import com.rost.cruma.viewer.render.api.ReadbackTicket.State;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

final class BgfxReadbackQueue implements AutoCloseable {
    private final Thread apiThread = Thread.currentThread();
    private final BgfxDevice device;
    private final BgfxResourceRegistry resources;
    private final int maxTickets;
    private final ArrayDeque<Pending> queued = new ArrayDeque<>();
    private final List<Slot> slots;
    private final ConcurrentLinkedQueue<ByteBuffer> returned = new ConcurrentLinkedQueue<>();

    private long nextId;
    private long completed;
    private long cancelled;
    private long failed;
    private boolean closed;

    BgfxReadbackQueue(BgfxDevice device, int stagingSlots, int maxTickets) {
        if (device == null || stagingSlots < 1 || maxTickets < stagingSlots) {
            throw new IllegalArgumentException("invalid readback queue limits");
        }
        this.device = device;
        this.resources = device.getResources();
        this.maxTickets = maxTickets;
        this.slots = new ArrayList<>(stagingSlots);
        for (var index = 0; index < stagingSlots; index++) {
            slots.add(new Slot(index));
        }
    }

    ReadbackTicket request(ReadbackRequest request) {
        checkThread();
        if (request == null) {
            throw new IllegalArgumentException("readback request cannot be null");
        }
        var ticket = new ReadbackTicket(nextId++, request.extent());
        if (closed) {
            ticket.fail("readback queue is closed");
            failed++;
        } else if (pendingCount() >= maxTickets) {
            ticket.fail("readback queue capacity " + maxTickets + " is exhausted");
            failed++;
        } else {
            queued.addLast(new Pending(request, ticket));
        }
        return ticket;
    }

    int submitWorld(BgfxTexture source, BgfxViewAllocator views) {
        checkThread();
        requireOpen();
        if (source == null || views == null) {
            throw new IllegalArgumentException("readback source and views are required");
        }
        var submitted = 0;
        while (!queued.isEmpty()) {
            var slot = freeSlot();
            if (slot == null) {
                break;
            }
            var pending = queued.removeFirst();
            if (pending.ticket.getState() != State.QUEUED) {
                cancelled++;
                continue;
            }
            if (pending.request.source() != Source.WORLD_COLOR) {
                pending.ticket.fail("unsupported readback source " + pending.request.source());
                failed++;
                continue;
            }
            var extent = pending.request.extent();
            var descriptor = source.getDescriptor();
            if (descriptor.width() < extent.width() || descriptor.height() < extent.height()) {
                pending.ticket.fail("source extent "
                        + descriptor.width()
                        + 'x'
                        + descriptor.height()
                        + " is smaller than requested "
                        + extent.width()
                        + 'x'
                        + extent.height());
                failed++;
                continue;
            }
            try {
                slot.ensure(extent);
                var view = views.allocate("readback-" + pending.request.label(), Domain.CAPTURE, false);
                bgfx_blit(
                        view,
                        slot.staging.getHandle(),
                        0,
                        0,
                        0,
                        0,
                        source.getHandle(),
                        0,
                        0,
                        0,
                        0,
                        extent.width(),
                        extent.height(),
                        1);
                slot.pixels.clear();
                if (!pending.ticket.markSubmitted()) {
                    cancelled++;
                    continue;
                }
                slot.pending = pending;
                slot.readyFrame =
                        Integer.toUnsignedLong(bgfx_read_texture(slot.staging.getHandle(), slot.pixels, 0, 0));
                submitted++;
            } catch (RuntimeException | Error e) {
                if (slot.pending != null) {
                    device.retainUntilShutdown(slot.pixels);
                }
                slot.pending = null;
                pending.ticket.fail(e.toString());
                failed++;
            }
        }
        return submitted;
    }

    int poll(long frameNumber) {
        checkThread();
        requireOpen();
        var ready = 0;
        for (var slot : slots) {
            if (slot.pending == null || !hasReached(frameNumber, slot.readyFrame)) {
                continue;
            }
            var source = slot.pixels.duplicate();
            source.clear();
            var result = buffer(source.remaining());
            result.put(source).flip();
            if (slot.pending.ticket.isComplete(result)) {
                completed++;
                ready++;
            } else {
                cancelled++;
            }
            slot.pending = null;
        }
        return ready;
    }

    void giveBack(ReadbackTicket ticket) {
        var buffer = ticket.release();
        if (buffer != null) {
            returned.add(buffer);
        }
    }

    Snapshot snapshot() {
        checkThread();
        var inFlight = 0;
        long stagingBytes = 0L;
        for (var slot : slots) {
            if (slot.pending != null) {
                inFlight++;
            }
            if (slot.staging != null) {
                stagingBytes += slot.staging.getEstimatedBytes();
            }
        }
        return new Snapshot(queued.size(), inFlight, completed, cancelled, failed, stagingBytes);
    }

    boolean hasPendingWork() {
        return !queued.isEmpty() || slots.stream().anyMatch(slot -> slot.pending != null);
    }

    @Override
    public void close() {
        checkThread();
        if (closed) {
            return;
        }
        closed = true;
        while (!queued.isEmpty()) {
            if (queued.removeFirst().ticket.cancel()) {
                cancelled++;
            }
        }
        RuntimeException failure = null;
        for (var slot : slots) {
            if (slot.pending != null && slot.pending.ticket.cancel()) {
                cancelled++;
            }
            try {
                slot.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private ByteBuffer buffer(int size) {
        var spare = returned.poll();
        return spare != null && spare.capacity() == size
                ? spare.clear()
                : ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    private Slot freeSlot() {
        for (var slot : slots) {
            if (slot.pending == null) {
                return slot;
            }
        }
        return null;
    }

    private int pendingCount() {
        var result = queued.size();
        for (var slot : slots) {
            if (slot.pending != null) {
                result++;
            }
        }
        return result;
    }

    private void checkThread() {
        if (Thread.currentThread() != apiThread) {
            throw new IllegalStateException("bgfx readback queue must run on the device API thread");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("bgfx readback queue is closed");
        }
    }

    record Snapshot(int queued, int inFlight, long completed, long cancelled, long failed, long stagingBytes) {
        int depth() {
            return queued + inFlight;
        }
    }

    private record Pending(ReadbackRequest request, ReadbackTicket ticket) {}

    private final class Slot implements AutoCloseable {
        private final int index;
        private Extent extent = new Extent(0, 0);
        private BgfxTexture staging;
        private ByteBuffer pixels;
        private Pending pending;
        private long readyFrame;

        private Slot(int index) {
            this.index = index;
        }

        @Override
        public void close() {
            if (pending != null) {
                device.retainUntilShutdown(pixels);
                pending = null;
            }
            if (staging != null) {
                staging.close();
                staging = null;
            }
            pixels = null;
            extent = new Extent(0, 0);
        }

        private void ensure(Extent requested) {
            if (requested.equals(extent)) {
                return;
            }
            close();
            staging = BgfxTexture.create2D(
                    resources,
                    "readback/staging-" + index,
                    requested.width(),
                    requested.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_READ_BACK | BGFX_TEXTURE_BLIT_DST,
                    null);
            pixels = ByteBuffer.allocateDirect(Math.toIntExact(Math.multiplyExact(requested.pixels(), 4L)))
                    .order(ByteOrder.nativeOrder());
            extent = requested;
        }
    }

    private static boolean hasReached(long current, long target) {
        return Integer.compareUnsigned((int) current, (int) target) >= 0;
    }
}

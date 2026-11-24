package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;

import com.rost.cruma.core.world.CoronaRules;
import com.rost.cruma.core.world.LightCorona;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.CoronaSystem;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class BgfxCoronaLineTests implements AutoCloseable {
    static final float LAMP_DEPTH = 24f;

    static final float FIRST_TEST_SECONDS = 0.1f;

    private static final int SLOT_COUNT = 3;
    private static final float NEAREST = 1f;

    private final BgfxDevice device;
    private final BgfxResourceRegistry resources;
    private final boolean originBottomLeft;
    private final boolean available;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private final float[] viewProjection = new float[16];
    private List<LightCorona> pending = List.of();
    private boolean pendingFirst;
    private double lastSubmit = Double.NEGATIVE_INFINITY;
    private boolean closed;

    record Probe(String actorPath, float ndcX, float ndcY, float depth) {}

    BgfxCoronaLineTests(BgfxDevice device) {
        this.device = device;
        this.resources = device.getResources();
        this.originBottomLeft = device.getCapabilities().originBottomLeft();
        this.available =
                device.getCapabilities().blit() && device.getCapabilities().readback();
        for (var index = 0; index < slots.length; index++) {
            slots[index] = new Slot(index);
        }
    }

    static boolean isClear(float lightDepth, float drawnDepth) {
        return !(drawnDepth < lightDepth - LAMP_DEPTH);
    }

    static boolean isDue(double sinceLast, boolean first) {
        return sinceLast >= (first ? FIRST_TEST_SECONDS : CoronaRules.RECHECK_SECONDS);
    }

    static Probe project(LightCorona corona, float[] matrix) {
        var place = corona.location();
        var x = place.getX();
        var y = place.getY();
        var z = place.getZ();
        var clipX = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12];
        var clipY = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13];
        var clipW = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15];
        if (!(clipW > NEAREST)) {
            return null;
        }
        var ndcX = clipX / clipW;
        var ndcY = clipY / clipW;
        if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) {
            return null;
        }
        return new Probe(corona.actorPath(), ndcX, ndcY, clipW);
    }

    boolean isDue(double now) {
        return !pending.isEmpty() && isDue(now - lastSubmit, pendingFirst);
    }

    void ask(CoronaSystem coronas, boolean depthToRead) {
        requireOpen();
        var wanted = coronas.takeLineTests();
        if (wanted.isEmpty()) {
            return;
        }
        if (!available || !depthToRead) {
            for (var corona : wanted) {
                coronas.acceptLineTest(corona.actorPath(), true);
            }
            return;
        }
        var waiting = new ArrayList<LightCorona>(pending);
        for (var corona : wanted) {
            if (waiting.size() >= CoronaRules.MOST_CORONAS) {
                coronas.dropLineTest(corona.actorPath());
                continue;
            }
            waiting.add(corona);
            pendingFirst |= !coronas.hasLineTest(corona.actorPath());
        }
        pending = List.copyOf(waiting);
    }

    void submit(
            BgfxTexture packedDepth,
            Extent extent,
            BgfxViewAllocator views,
            double now,
            Camera camera,
            CoronaSystem coronas) {
        requireOpen();
        if (!available || !isDue(now) || packedDepth == null || extent == null || extent.isZero()) {
            return;
        }
        var slot = freeSlot();
        if (slot == null) {
            return;
        }
        camera.copyViewProjection(viewProjection);
        var probes = new ArrayList<Probe>(pending.size());
        for (var corona : pending) {
            var probe = project(corona, viewProjection);
            if (probe == null) {
                coronas.dropLineTest(corona.actorPath());
            } else {
                probes.add(probe);
            }
        }
        pending = List.of();
        pendingFirst = false;
        lastSubmit = now;
        if (probes.isEmpty()) {
            return;
        }
        slot.ensure();
        var view = views.allocate("corona-line-tests", Domain.RENDER, false);
        for (var index = 0; index < probes.size(); index++) {
            var probe = probes.get(index);
            var sourceX = toPixel(probe.ndcX() * 0.5f + 0.5f, extent.width());
            var topDownY = toPixel(0.5f - probe.ndcY() * 0.5f, extent.height());
            var sourceY = originBottomLeft ? extent.height() - 1 - topDownY : topDownY;
            bgfx_blit(
                    view,
                    slot.staging.getHandle(),
                    0,
                    index,
                    0,
                    0,
                    packedDepth.getHandle(),
                    0,
                    sourceX,
                    sourceY,
                    0,
                    1,
                    1,
                    1);
        }
        slot.pixels.clear();
        slot.probes = List.copyOf(probes);
        slot.readyFrame = Integer.toUnsignedLong(bgfx_read_texture(slot.staging.getHandle(), slot.pixels, 0, 0));
    }

    void poll(long frameNumber, CoronaSystem coronas) {
        requireOpen();
        for (var slot : slots) {
            if (slot.probes == null || Long.compareUnsigned(frameNumber, slot.readyFrame) < 0) {
                continue;
            }
            var probes = slot.probes;
            slot.probes = null;
            for (var index = 0; index < probes.size(); index++) {
                var high = slot.pixels.get(index * 4) & 0xff;
                var low = slot.pixels.get(index * 4 + 1) & 0xff;
                var third = slot.pixels.get(index * 4 + 2) & 0xff;
                var background = high == 0xff && low == 0xff && third == 0xff;
                var depth = background ? Float.POSITIVE_INFINITY : high * 256f + low + third / 255f;
                var probe = probes.get(index);
                coronas.acceptLineTest(probe.actorPath(), isClear(probe.depth(), depth));
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending = List.of();
        pendingFirst = false;
        for (var slot : slots) {
            slot.close();
        }
    }

    private Slot freeSlot() {
        for (var slot : slots) {
            if (slot.probes == null) {
                return slot;
            }
        }
        return null;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("corona line tests are closed");
        }
    }

    private static int toPixel(float normalized, int extent) {
        return Math.max(0, Math.min(extent - 1, (int) Math.floor(normalized * extent)));
    }

    private final class Slot implements AutoCloseable {
        private final int index;
        private BgfxTexture staging;
        private ByteBuffer pixels;
        private List<Probe> probes;
        private long readyFrame;

        private Slot(int index) {
            this.index = index;
        }

        @Override
        public void close() {
            if (probes != null) {
                device.retainUntilShutdown(pixels);
                probes = null;
            }
            if (staging != null) {
                staging.close();
                staging = null;
            }
            pixels = null;
        }

        private void ensure() {
            if (staging != null) {
                return;
            }
            staging = BgfxTexture.create2D(
                    resources,
                    "coronas/depth-staging-" + index,
                    CoronaRules.MOST_CORONAS,
                    1,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_READ_BACK | BGFX_TEXTURE_BLIT_DST,
                    null);
            pixels = ByteBuffer.allocateDirect(CoronaRules.MOST_CORONAS * 4).order(ByteOrder.nativeOrder());
        }
    }
}

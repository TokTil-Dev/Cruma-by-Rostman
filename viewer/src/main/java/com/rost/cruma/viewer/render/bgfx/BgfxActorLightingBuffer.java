package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_READ;
import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_WRITE;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_COMPUTE_READ_WRITE;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.bgfx_set_compute_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;

import com.rost.cruma.core.world.Entity.ActorVertexLighting;
import com.rost.cruma.core.world.Entity.HardwareSunDirection;
import com.rost.cruma.core.world.Entity.HardwareSunEnvironment;
import com.rost.cruma.core.world.StaticMeshAmbient;
import com.rost.cruma.viewer.render.bgfx.BgfxNativeLightSources.Binding;
import com.rost.cruma.viewer.render.bgfx.BgfxNativeLightSources.Source;
import com.rost.cruma.viewer.render.bgfx.BgfxNativeLightState.Input;
import glm_.vec3.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

final class BgfxActorLightingBuffer implements AutoCloseable {
    private static final int MAX_WORDS = Integer.MAX_VALUE / Integer.BYTES;
    private static final int HEADER_WORDS = 3;
    private static final int RGB_FLAG = 1;
    private static final int VISIBILITY_FLAG = 2;
    private static final int NATIVE_SCENE_COLOR_FLAG = 4;
    private static final int COLOR_ALPHA_FLAG = 8;
    private static final int ANIMATED_AMBIENT_GLOW_FLAG = 16;
    private static final int HARDWARE_SUN_FLAG = 32;
    private static final int FLAT_SUN_FLAG = 64;
    private static final int BUFFER_STAGE = 11;
    private static final int COPY_CHUNK_WORDS = 1 << 23;

    private final BgfxDevice device;
    private BgfxBuffer buffer;
    private int capacityWords;
    private final List<GrowthCopy> frameCopies = new ArrayList<>();
    private BgfxProgram copyProgram;
    private BgfxUniform copyRange;
    private final float[] copyRangeData = new float[4];
    private final List<RetiredBuffer> retiredBuffers = new ArrayList<>();
    private final List<Range> free = new ArrayList<>();
    private final Map<SharedRange, CachedSun> cachedSuns = new LinkedHashMap<>();
    private final Map<Source, Input> frameSunInputs = new IdentityHashMap<>();
    private long cachedSunUploads;
    private final List<RetiredRange> retired = new ArrayList<>();
    private ByteBuffer scratch = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
    private int highWater;
    private long frame;
    private long liveWords;
    private long uploadedBytes;
    private long uploadSubmissions;
    private boolean closed;

    BgfxActorLightingBuffer(BgfxDevice device, int capacityWords) {
        if (device == null) {
            throw new IllegalArgumentException("actor lighting buffer requires a bgfx device");
        }
        if (capacityWords < 1024 || capacityWords > MAX_WORDS) {
            throw new IllegalArgumentException("actor lighting capacity must be 1024.." + MAX_WORDS + " words");
        }
        this.device = device;
        this.capacityWords = capacityWords;
        buffer = createBuffer(capacityWords);
    }

    static int wordCount(@Nullable ActorVertexLighting lighting) {
        if (lighting == null) {
            return HEADER_WORDS;
        }
        var alphaWords = lighting.colorAlpha() == null ? 0 : (lighting.vertexCount() + 3) / 4;
        return Math.addExact(HEADER_WORDS, Math.addExact(lighting.vertexCount(), alphaWords));
    }

    static int packRgbShiftedRight(Vec3 color) {
        return (toUnorm(color.getX()) >>> 1) | (toUnorm(color.getY()) >>> 1) << 8 | (toUnorm(color.getZ()) >>> 1) << 16;
    }

    Slice allocate(
            String label,
            ActorVertexLighting lighting,
            Vec3 instanceFloor,
            @Nullable Vec3 hardwareSun,
            boolean flatSun) {
        return allocate(label, lighting, instanceFloor, hardwareSun, flatSun, 1f);
    }

    Slice allocate(
            String label,
            ActorVertexLighting lighting,
            Vec3 instanceFloor,
            @Nullable Vec3 hardwareSun,
            boolean flatSun,
            float ambientIntensity) {
        return allocate(label, lighting, instanceFloor, hardwareSun, flatSun, ambientIntensity, null);
    }

    Slice allocate(
            String label,
            @Nullable ActorVertexLighting lighting,
            Vec3 instanceFloor,
            @Nullable Vec3 hardwareSun,
            boolean flatSun,
            float ambientIntensity,
            @Nullable StaticMeshAmbient ambient) {
        return allocate(label, lighting, instanceFloor, hardwareSun, flatSun, ambientIntensity, ambient, null);
    }

    Slice allocate(
            String label,
            @Nullable ActorVertexLighting lighting,
            Vec3 instanceFloor,
            @Nullable Vec3 hardwareSun,
            boolean flatSun,
            float ambientIntensity,
            @Nullable StaticMeshAmbient ambient,
            @Nullable HardwareSunDirection sunDirection) {
        return allocate(
                label, lighting, instanceFloor, hardwareSun, flatSun, ambientIntensity, ambient, sunDirection, null);
    }

    Slice allocate(
            String label,
            @Nullable ActorVertexLighting lighting,
            Vec3 instanceFloor,
            @Nullable Vec3 hardwareSun,
            boolean flatSun,
            float ambientIntensity,
            @Nullable StaticMeshAmbient ambient,
            @Nullable HardwareSunDirection sunDirection,
            @Nullable HardwareSunEnvironment sunEnvironment) {
        return allocate(
                label,
                lighting,
                instanceFloor,
                hardwareSun,
                flatSun,
                ambientIntensity,
                ambient,
                sunDirection,
                sunEnvironment,
                null);
    }

    Slice allocate(
            String label,
            @Nullable ActorVertexLighting lighting,
            Vec3 instanceFloor,
            @Nullable Vec3 hardwareSun,
            boolean flatSun,
            float ambientIntensity,
            @Nullable StaticMeshAmbient ambient,
            @Nullable HardwareSunDirection sunDirection,
            @Nullable HardwareSunEnvironment sunEnvironment,
            @Nullable Binding cachedSun) {
        requireOpen();
        if (lighting == null && ambient == null || instanceFloor == null) {
            throw new IllegalArgumentException("actor lighting allocation requires lighting or ambient, and floor");
        }
        if ((sunDirection != null || sunEnvironment != null) && hardwareSun == null) {
            throw new IllegalArgumentException("hardware sun metadata requires a sun colour");
        }
        if (cachedSun != null) {
            if (hardwareSun == null) {
                throw new IllegalArgumentException("cached hardware sun requires a colour payload");
            }
            hardwareSun = new Vec3();
            sunDirection = new HardwareSunDirection(false, new Vec3(1f, 0f, 0f));
            sunEnvironment = null;
        }
        var fixedSun = sunDirection != null && !sunDirection.movable();
        var words = wordCount(lighting)
                + (ambientIntensity == 1f ? 0 : 1)
                + (ambient == null ? 0 : 1)
                + (hardwareSun == null ? 0 : 3)
                + (fixedSun ? 3 : 0)
                + (sunEnvironment == null ? 0 : 2);
        var allocation = findAllocation(words);
        if (allocation == null) {
            grow(Math.addExact((long) highWater, words));
            allocation = new Allocation(highWater, false, -1);
        }
        var bytes = Math.multiplyExact(words, Integer.BYTES);
        ensureScratch(bytes);
        scratch.clear().limit(bytes);
        scratch.putInt(
                lighting != null && lighting.nativeSceneColor() && !lighting.animatedAmbientGlow()
                        ? packRgbShiftedRight(instanceFloor)
                        : packRgb(instanceFloor.getX(), instanceFloor.getY(), instanceFloor.getZ()));
        var flags = (lighting != null && lighting.hasRgb() ? RGB_FLAG : 0)
                | (lighting != null && lighting.hasVisibilityMasks() ? VISIBILITY_FLAG : 0)
                | (lighting != null && lighting.nativeSceneColor() ? NATIVE_SCENE_COLOR_FLAG : 0)
                | (lighting != null && lighting.colorAlpha() != null ? COLOR_ALPHA_FLAG : 0)
                | (lighting != null && lighting.animatedAmbientGlow() ? ANIMATED_AMBIENT_GLOW_FLAG : 0)
                | (hardwareSun != null ? HARDWARE_SUN_FLAG : 0)
                | (flatSun && ambient == null ? FLAT_SUN_FLAG : 0)
                | (ambientIntensity != 1f ? 128 : 0);
        var vertexCount = lighting == null ? 0 : lighting.vertexCount();
        if (vertexCount > 0x00ff_ffff) {
            throw new IllegalArgumentException("actor lighting vertex count exceeds packed header range");
        }
        scratch.putInt(flags | vertexCount << 8);
        var sunControl = (hardwareSun == null ? 0 : packRgb(hardwareSun.getX(), hardwareSun.getY(), hardwareSun.getZ()))
                | ((ambient == null ? 0 : ambient.control())
                                | (sunDirection == null ? 0 : sunDirection.movable() ? 32 : 16)
                                | (sunEnvironment == null ? 0 : 64)
                                | (cachedSun == null ? 0 : 128))
                        << 24;
        scratch.putInt(sunControl);
        var rgb = lighting == null ? null : lighting.rgb();
        var masks = lighting == null ? null : lighting.visibilityMasks();
        for (var vertex = 0; vertex < vertexCount; vertex++) {
            var rgbOffset = vertex * 3;
            var packed = rgb == null
                    ? 0
                    : Byte.toUnsignedInt(rgb[rgbOffset])
                            | Byte.toUnsignedInt(rgb[rgbOffset + 1]) << 8
                            | Byte.toUnsignedInt(rgb[rgbOffset + 2]) << 16;
            if (masks != null) {
                packed |= Byte.toUnsignedInt(masks[vertex]) << 24;
            }
            scratch.putInt(packed);
        }
        var alpha = lighting == null ? null : lighting.colorAlpha();
        if (alpha != null) {
            for (var first = 0; first < alpha.length; first += 4) {
                var packed = 0;
                for (var lane = 0; lane < 4 && first + lane < alpha.length; lane++) {
                    packed |= Byte.toUnsignedInt(alpha[first + lane]) << (lane * 8);
                }
                scratch.putInt(packed);
            }
        }
        if (ambientIntensity != 1f) {
            scratch.putFloat(ambientIntensity);
        }
        if (ambient != null) {
            scratch.putInt(ambient.actorRgb());
        }
        if (hardwareSun != null) {
            scratch.putFloat(hardwareSun.getX()).putFloat(hardwareSun.getY()).putFloat(hardwareSun.getZ());
        }
        if (fixedSun) {
            var direction = sunDirection.fixedDirection();
            scratch.putFloat(direction.getX()).putFloat(direction.getY()).putFloat(direction.getZ());
        }
        if (sunEnvironment != null) {
            scratch.putFloat(sunEnvironment.levelBrightness()).putFloat(sunEnvironment.lightIntensity());
        }
        scratch.flip();
        write(allocation.offset, scratch);
        apply(allocation, words);
        var range = new SharedRange(allocation.offset, words);
        if (cachedSun != null) {
            cachedSuns.put(
                    range, new CachedSun(cachedSun, allocation.offset + words - 6, allocation.offset + 2, sunControl));
        }
        liveWords = Math.addExact(liveWords, words);
        uploadedBytes = Math.addExact(uploadedBytes, bytes);
        uploadSubmissions = Math.incrementExact(uploadSubmissions);
        return new Slice(range, label);
    }

    void refreshSuns() {
        requireOpen();
        frameSunInputs.clear();
        for (var sun : cachedSuns.values()) {
            var source = sun.binding.source();
            var input = source == null ? null : frameSunInputs.computeIfAbsent(source, Source::construct);
            if (sun.initialized && sun.source == source && Objects.equals(sun.input, input)) {
                continue;
            }
            var color = input == null
                    ? new Vec3()
                    : input.color().times(sun.binding.intensity()).times(.875f).times(2f);
            var direction =
                    input == null ? new Vec3(1f, 0f, 0f) : input.direction().times(-1f);
            scratch.clear().limit(24);
            scratch.putFloat(color.getX()).putFloat(color.getY()).putFloat(color.getZ());
            scratch.putFloat(direction.getX()).putFloat(direction.getY()).putFloat(direction.getZ());
            scratch.flip();
            write(sun.offset, scratch);
            uploadedBytes = Math.addExact(uploadedBytes, 24);
            uploadSubmissions = Math.incrementExact(uploadSubmissions);
            cachedSunUploads++;
            var control = source == null ? sun.control | 0x8000_0000 : sun.control & 0x7fff_ffff;
            if (control != sun.control) {
                scratch.clear().limit(Integer.BYTES);
                scratch.putInt(control).flip();
                write(sun.controlOffset, scratch);
                uploadedBytes = Math.addExact(uploadedBytes, Integer.BYTES);
                uploadSubmissions = Math.incrementExact(uploadSubmissions);
                sun.control = control;
            }
            sun.source = source;
            sun.input = input;
            sun.initialized = true;
        }
    }

    int getCachedSunCount() {
        return cachedSuns.size();
    }

    long getCachedSunUploads() {
        return cachedSunUploads;
    }

    Slice allocateDirectional(String label, float[] coefficients) {
        requireOpen();
        var words = coefficients.length;
        if (words == 0 || words % 9 != 0) {
            throw new IllegalArgumentException("directional stream requires nine floats per vertex");
        }
        var allocation = findAllocation(words);
        if (allocation == null) {
            grow(Math.addExact((long) highWater, words));
            allocation = new Allocation(highWater, false, -1);
        }
        var bytes = Math.multiplyExact(words, Integer.BYTES);
        ensureScratch(bytes);
        scratch.clear().limit(bytes);
        for (var coefficient : coefficients) {
            scratch.putInt(Float.floatToRawIntBits(coefficient));
        }
        scratch.flip();
        write(allocation.offset, scratch);
        apply(allocation, words);
        liveWords = Math.addExact(liveWords, words);
        uploadedBytes = Math.addExact(uploadedBytes, bytes);
        uploadSubmissions = Math.incrementExact(uploadSubmissions);
        return new Slice(new SharedRange(allocation.offset, words), label);
    }

    void bind() {
        requireOpen();
        bgfx_set_compute_dynamic_index_buffer(BUFFER_STAGE, buffer.getHandle(), BGFX_ACCESS_READ);
    }

    void advanceFrame() {
        requireOpen();
        frame = Math.incrementExact(frame);
        frameCopies.clear();
        for (var index = retired.size() - 1; index >= 0; index--) {
            var range = retired.get(index);
            if (!BgfxGeometryPool.isReusableAtFrame(range.frame, frame)) {
                continue;
            }
            releaseRange(range.offset, range.count);
            retired.remove(index);
        }
        highWater = trimTail(highWater);
        for (var index = retiredBuffers.size() - 1; index >= 0; index--) {
            var old = retiredBuffers.get(index);
            if (BgfxGeometryPool.isReusableAtFrame(old.frame, frame)) {
                old.buffer.close();
                retiredBuffers.remove(index);
            }
        }
    }

    Snapshot snapshot() {
        return new Snapshot(capacityWords, highWater, liveWords, uploadSubmissions, uploadedBytes);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        free.clear();
        cachedSuns.clear();
        frameSunInputs.clear();
        retired.clear();
        frameCopies.clear();
        for (var old : retiredBuffers) {
            old.buffer.close();
        }
        retiredBuffers.clear();
        scratch = null;
        buffer.close();
        if (copyProgram != null) {
            copyProgram.close();
            copyRange.close();
        }
    }

    record Snapshot(
            int capacityWords, int highWaterWords, long liveWords, long uploadSubmissions, long uploadedBytes) {}

    final class Slice implements AutoCloseable {
        private final SharedRange range;
        private final String label;
        private boolean closed;

        private Slice(SharedRange range, String label) {
            this.range = range;
            this.label = label == null || label.isBlank() ? "actor-lighting" : label;
        }

        int getOffset() {
            if (closed || BgfxActorLightingBuffer.this.closed) {
                throw new IllegalStateException("actor lighting slice is closed: " + label);
            }
            return range.offset;
        }

        Slice retain() {
            if (closed || BgfxActorLightingBuffer.this.closed || range.references < 1) {
                throw new IllegalStateException("actor lighting slice is closed: " + label);
            }
            range.references = Math.incrementExact(range.references);
            return new Slice(range, label);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            release(range);
        }
    }

    private static final class SharedRange {
        final int offset;
        final int count;
        int references = 1;

        private SharedRange(int offset, int count) {
            this.offset = offset;
            this.count = count;
        }
    }

    private static final class CachedSun {
        final Binding binding;
        final int offset;
        final int controlOffset;
        int control;
        Source source;
        Input input;
        boolean initialized;

        CachedSun(Binding binding, int offset, int controlOffset, int control) {
            this.binding = binding;
            this.offset = offset;
            this.controlOffset = controlOffset;
            this.control = control;
        }
    }

    private record Range(int offset, int count) {}

    private record RetiredRange(int offset, int count, long frame) {}

    private record RetiredBuffer(BgfxBuffer buffer, long frame) {}

    private record GrowthCopy(BgfxBuffer source, int words) {}

    private record Allocation(int offset, boolean fromFree, int freeIndex) {}

    private Allocation findAllocation(int required) {
        for (var index = 0; index < free.size(); index++) {
            var range = free.get(index);
            if (range.count >= required) {
                return new Allocation(range.offset, true, index);
            }
        }
        return (long) highWater + required <= capacityWords ? new Allocation(highWater, false, -1) : null;
    }

    private BgfxBuffer createBuffer(int words) {
        return BgfxBuffer.dynamicIndex(
                device.getResources(),
                "scene/actor-vertex-lighting",
                words,
                true,
                BgfxBuffer.gpuResident(device.getCapabilities(), BGFX_BUFFER_COMPUTE_READ_WRITE));
    }

    private void grow(long requiredWords) {
        if (requiredWords > MAX_WORDS) {
            throw new IllegalStateException("actor lighting exceeds the supported buffer size: " + requiredWords
                    + " words, maximum " + MAX_WORDS);
        }
        var nextCapacity = (int) Math.min(MAX_WORDS, Math.max(requiredWords, capacityWords + (long) capacityWords / 2));
        var replacement = createBuffer(nextCapacity);
        try {
            copyOnGpu(buffer, replacement, highWater);
        } catch (RuntimeException | Error failure) {
            replacement.close();
            throw failure;
        }
        frameCopies.add(new GrowthCopy(buffer, highWater));
        retiredBuffers.add(new RetiredBuffer(buffer, frame));
        buffer = replacement;
        LogManager.getLogger(BgfxActorLightingBuffer.class)
                .info(
                        "Grew actor lighting buffer: {} -> {} words ({} MiB); preserved {} words",
                        capacityWords,
                        nextCapacity,
                        nextCapacity * 4L / (1024 * 1024),
                        highWater);
        capacityWords = nextCapacity;
    }

    private void write(int offset, ByteBuffer words) {
        var end = Math.addExact(offset, words.remaining() / Integer.BYTES);
        var position = words.position();
        var limit = words.limit();
        var cursor = offset;
        while (cursor < end) {
            var target = buffer;
            var segmentEnd = end;
            for (var copy : frameCopies) {
                if (cursor < copy.words()) {
                    target = copy.source();
                    segmentEnd = Math.min(end, copy.words());
                    break;
                }
            }
            words.limit(position + (segmentEnd - offset) * Integer.BYTES)
                    .position(position + (cursor - offset) * Integer.BYTES);
            target.update(cursor, words);
            cursor = segmentEnd;
        }
        words.limit(limit).position(position);
    }

    private void copyOnGpu(BgfxBuffer source, BgfxBuffer target, int words) {
        if (words == 0) {
            return;
        }
        if (copyProgram == null) {
            var resources = device.getResources();
            var capabilities = device.getCapabilities();
            copyProgram = BgfxProgram.compute(
                    resources,
                    capabilities,
                    "actor-lighting/copy",
                    BgfxShaderResources.load(capabilities, "cs_buffer_copy"));
            copyRange = BgfxUniform.create(resources, "u_copyRange", BGFX_UNIFORM_TYPE_VEC4, 1);
        }
        for (var first = 0; first < words; first += COPY_CHUNK_WORDS) {
            var count = Math.min(COPY_CHUNK_WORDS, words - first);
            copyRangeData[0] = first >>> 12;
            copyRangeData[1] = first & 4095;
            copyRangeData[2] = count;
            bgfx_set_uniform(copyRange.getHandle(), copyRangeData, 1);
            bgfx_set_compute_dynamic_index_buffer(0, source.getHandle(), BGFX_ACCESS_READ);
            bgfx_set_compute_dynamic_index_buffer(1, target.getHandle(), BGFX_ACCESS_WRITE);
            BgfxComputeRows.dispatch(BgfxDevice.BUFFER_COPY_VIEW, copyProgram, count);
        }
    }

    private void apply(Allocation allocation, int used) {
        if (!allocation.fromFree) {
            highWater = Math.addExact(allocation.offset, used);
            return;
        }
        var range = free.get(allocation.freeIndex);
        if (range.count == used) {
            free.remove(allocation.freeIndex);
        } else {
            free.set(allocation.freeIndex, new Range(Math.addExact(range.offset, used), range.count - used));
        }
    }

    private void release(SharedRange range) {
        if (--range.references > 0) {
            return;
        }
        if (range.references < 0) {
            throw new IllegalStateException("actor lighting range released more than once");
        }
        liveWords -= range.count;
        cachedSuns.remove(range);
        retired.add(new RetiredRange(range.offset, range.count, frame));
    }

    private void releaseRange(int offset, int count) {
        var insert = 0;
        while (insert < free.size() && free.get(insert).offset < offset) {
            insert++;
        }
        free.add(insert, new Range(offset, count));
        for (var index = Math.max(0, insert - 1); index + 1 < free.size(); ) {
            var left = free.get(index);
            var right = free.get(index + 1);
            if ((long) left.offset + left.count < right.offset) {
                index++;
                continue;
            }
            var end = Math.max(Math.addExact(left.offset, left.count), Math.addExact(right.offset, right.count));
            free.set(index, new Range(left.offset, end - left.offset));
            free.remove(index + 1);
        }
    }

    private int trimTail(int current) {
        while (!free.isEmpty()) {
            var last = free.getLast();
            if ((long) last.offset + last.count != current) {
                break;
            }
            current = last.offset;
            free.removeLast();
        }
        return current;
    }

    private void ensureScratch(int bytes) {
        if (scratch.capacity() >= bytes) {
            return;
        }
        var capacity = Integer.highestOneBit(bytes - 1);
        capacity = capacity <= 0 || capacity > (Integer.MAX_VALUE >>> 1) ? bytes : capacity << 1;
        scratch = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("actor lighting buffer is closed");
        }
    }

    private static int packRgb(float red, float green, float blue) {
        return toUnorm(red) | toUnorm(green) << 8 | toUnorm(blue) << 16;
    }

    private static int toUnorm(float value) {
        return Math.clamp(Math.round(value * 255f), 0, 255);
    }
}

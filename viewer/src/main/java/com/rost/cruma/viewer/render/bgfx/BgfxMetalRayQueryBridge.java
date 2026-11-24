package com.rost.cruma.viewer.render.bgfx;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class BgfxMetalRayQueryBridge {
    static final int ABI_VERSION = 18;
    private static final String RESOURCE = "/native/macos/libcruma_metal_ray_query.dylib";
    private static final Binding BINDING = load();

    private static final Logger log = LogManager.getLogger(BgfxMetalRayQueryBridge.class);

    private BgfxMetalRayQueryBridge() {}

    static Probe probe(long metalDevice) {
        var binding = BINDING;
        if (!binding.loaded()) {
            return new Probe(false, "", binding.failure());
        }
        if (metalDevice == 0L) {
            return new Probe(false, "", "bgfx did not expose a Metal device pointer");
        }
        try {
            var device = MemorySegment.ofAddress(metalDevice);
            var supported = (int) binding.supported().invokeExact(device) != 0;
            var deviceName = deviceName(binding, device);
            return supported
                    ? new Probe(true, deviceName, "Metal hardware ray queries available on " + deviceName)
                    : new Probe(
                            false,
                            deviceName,
                            deviceName.isBlank()
                                    ? "Metal device does not support ray tracing"
                                    : deviceName + " does not support Metal ray tracing");
        } catch (Throwable failure) {
            log.warn("Metal capability probe failed", failure);
            return new Probe(false, "", "Metal ray-query probe failed: " + messageOf(failure));
        }
    }

    static Probe probeSystemDeviceForTesting() {
        var binding = BINDING;
        if (!binding.loaded()) {
            return new Probe(false, "", binding.failure());
        }
        try {
            var supported = (int) binding.systemSupported().invokeExact() != 0;
            var deviceName = systemDeviceName(binding);
            return new Probe(
                    supported,
                    deviceName,
                    supported
                            ? "Metal hardware ray queries available on " + deviceName
                            : deviceName.isBlank()
                                    ? "No default Metal device"
                                    : deviceName + " does not support Metal ray tracing");
        } catch (Throwable failure) {
            return new Probe(false, "", "Metal system-device probe failed: " + messageOf(failure));
        }
    }

    static boolean isLibraryLoaded() {
        return BINDING.loaded();
    }

    static boolean isBgfxWaitHookLoadedForTesting() {
        if (!BINDING.loaded()) {
            return false;
        }
        try {
            return (int) BINDING.bgfxWaitHookLoaded().invokeExact() != 0;
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal bgfx wait-hook probe failed", failure);
        }
    }

    static Session create(long metalDevice) {
        if (!BINDING.loaded() || metalDevice == 0L) {
            return null;
        }
        try {
            var handle = (MemorySegment) BINDING.create().invokeExact(MemorySegment.ofAddress(metalDevice));
            if (handle.equals(MemorySegment.NULL)) {
                return null;
            }
            var session = new Session(handle);
            if (session.pollBuild() == BuildState.FAILED) {
                var nativeReason = session.getLastError();
                session.close();
                throw new IllegalStateException(
                        nativeReason.isBlank()
                                ? "Metal native context initialization failed without a diagnostic"
                                : nativeReason);
            }
            return session;
        } catch (Throwable failure) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Metal native context creation failed", failure);
        }
    }

    static String libraryFailure() {
        return BINDING.failure();
    }

    private static String deviceName(Binding binding, MemorySegment device) throws Throwable {
        var required = (long) binding.deviceName().invokeExact(device, MemorySegment.NULL, 0L);
        if (required <= 1L || required > 4_096L) {
            return "";
        }
        try (var arena = Arena.ofConfined()) {
            var destination = arena.allocate(required, 1L);
            var copied = (long) binding.deviceName().invokeExact(device, destination, required);
            if (copied != required) {
                return "";
            }
            return destination.getString(0L);
        }
    }

    private static String systemDeviceName(Binding binding) throws Throwable {
        var required = (long) binding.systemDeviceName().invokeExact(MemorySegment.NULL, 0L);
        if (required <= 1L || required > 4_096L) {
            return "";
        }
        try (var arena = Arena.ofConfined()) {
            var destination = arena.allocate(required, 1L);
            var copied = (long) binding.systemDeviceName().invokeExact(destination, required);
            return copied == required ? destination.getString(0L) : "";
        }
    }

    private static Binding load() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return Binding.failed("hardware ray queries currently require macOS/Metal");
        }
        try {
            var resource = BgfxMetalRayQueryBridge.class.getResourceAsStream(RESOURCE);
            if (resource == null) {
                return Binding.failed("Metal ray-query bridge resource is not packaged");
            }
            Path library;
            try (resource) {
                library = Files.createTempFile("cruma-metal-ray-query-", ".dylib");
                Files.copy(resource, library, StandardCopyOption.REPLACE_EXISTING);
            }
            library.toFile().deleteOnExit();
            var lookup = SymbolLookup.libraryLookup(library, Arena.global());
            var linker = Linker.nativeLinker();
            var abi = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_abi"), FunctionDescriptor.of(JAVA_INT));
            var actualAbi = (int) abi.invokeExact();
            if (actualAbi != ABI_VERSION) {
                return Binding.failed("Metal ray-query ABI mismatch: expected " + ABI_VERSION + ", got " + actualAbi);
            }
            var supported = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_supported"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var deviceName = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_device_name"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
            var systemSupported = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_system_supported"), FunctionDescriptor.of(JAVA_INT));
            var systemDeviceName = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_system_device_name"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
            var create = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_create"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            var destroy = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_destroy"), FunctionDescriptor.ofVoid(ADDRESS));
            var registerGeometry = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_register_geometry"),
                    FunctionDescriptor.of(
                            JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT,
                            ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            var registerInstances = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_register_instances"),
                    FunctionDescriptor.of(
                            JAVA_INT,
                            ADDRESS,
                            JAVA_LONG,
                            JAVA_LONG,
                            JAVA_LONG,
                            ADDRESS,
                            JAVA_INT,
                            JAVA_INT,
                            JAVA_FLOAT,
                            JAVA_FLOAT));
            var removeOwner = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_remove_owner"),
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
            var setFocus = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_set_focus"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
            var pollBuild = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_poll_build"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var instanceCount = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_instance_count"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var lastError = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_last_error"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
            var dispatch = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_dispatch"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
            var pollVisibility = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_poll_visibility"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var waitVisibility = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_wait_visibility"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var armBgfxWait = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_arm_bgfx_wait"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var bgfxWaitAvailable = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_bgfx_wait_available"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var bgfxWaitHookLoaded = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_bgfx_wait_hook_loaded"), FunctionDescriptor.of(JAVA_INT));
            var textureGeneration = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_texture_generation"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            var publishedSerial = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_published_serial"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            var publishedSlot = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_published_slot"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var textureWidth = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_texture_width"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var textureHeight = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_texture_height"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var publishedWidth = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_published_width"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var publishedHeight = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_published_height"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var publishedReprojection = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_published_reprojection"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            var texture = linker.downcallHandle(
                    require(lookup, "cruma_metal_ray_query_texture"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
            var giMaterials = linker.downcallHandle(
                    require(lookup, "cruma_metal_gi_materials"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
            var giDispatch = linker.downcallHandle(
                    require(lookup, "cruma_metal_gi_dispatch"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            var giRead = linker.downcallHandle(
                    require(lookup, "cruma_metal_gi_read"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
            var removeGeometry = lookup.find("cruma_metal_ray_query_remove_geometry")
                    .map(symbol -> linker.downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)))
                    .orElse(null);
            return new Binding(
                    true,
                    "",
                    supported,
                    deviceName,
                    systemSupported,
                    systemDeviceName,
                    create,
                    destroy,
                    registerGeometry,
                    registerInstances,
                    removeOwner,
                    setFocus,
                    pollBuild,
                    instanceCount,
                    lastError,
                    dispatch,
                    pollVisibility,
                    waitVisibility,
                    armBgfxWait,
                    bgfxWaitAvailable,
                    bgfxWaitHookLoaded,
                    textureGeneration,
                    publishedSerial,
                    publishedSlot,
                    textureWidth,
                    textureHeight,
                    publishedWidth,
                    publishedHeight,
                    publishedReprojection,
                    texture,
                    giMaterials,
                    giDispatch,
                    giRead,
                    removeGeometry);
        } catch (Throwable failure) {
            log.debug("Optional Metal bridge unavailable: {}", messageOf(failure));
            return Binding.failed("Metal ray-query bridge unavailable: " + messageOf(failure));
        }
    }

    private static MemorySegment require(SymbolLookup lookup, String symbol) {
        return lookup.find(symbol).orElseThrow(() -> new IllegalStateException("missing native symbol " + symbol));
    }

    private static String messageOf(Throwable failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    record Probe(boolean available, String deviceName, String reason) {
        Probe {
            deviceName = deviceName == null ? "" : deviceName;
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("ray-query capability reason cannot be blank");
            }
        }
    }

    private record Binding(
            boolean loaded,
            String failure,
            MethodHandle supported,
            MethodHandle deviceName,
            MethodHandle systemSupported,
            MethodHandle systemDeviceName,
            MethodHandle create,
            MethodHandle destroy,
            MethodHandle registerGeometry,
            MethodHandle registerInstances,
            MethodHandle removeOwner,
            MethodHandle setFocus,
            MethodHandle pollBuild,
            MethodHandle instanceCount,
            MethodHandle lastError,
            MethodHandle dispatch,
            MethodHandle pollVisibility,
            MethodHandle waitVisibility,
            MethodHandle armBgfxWait,
            MethodHandle bgfxWaitAvailable,
            MethodHandle bgfxWaitHookLoaded,
            MethodHandle textureGeneration,
            MethodHandle publishedSerial,
            MethodHandle publishedSlot,
            MethodHandle textureWidth,
            MethodHandle textureHeight,
            MethodHandle publishedWidth,
            MethodHandle publishedHeight,
            MethodHandle publishedReprojection,
            MethodHandle texture,
            MethodHandle giMaterials,
            MethodHandle giDispatch,
            MethodHandle giRead,
            MethodHandle removeGeometry) {
        private static Binding failed(String reason) {
            return new Binding(
                    false, reason, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);
        }
    }

    static final class Session implements AutoCloseable {
        private MemorySegment handle;

        private Session(MemorySegment handle) {
            this.handle = handle;
        }

        boolean uploadGiMaterials(long id, MemorySegment colors, int triangles) {
            requireOpen();
            if (triangles <= 0 || colors.byteSize() < triangles * 32L) {
                throw new IllegalArgumentException("GI materials require albedo and emission per triangle");
            }
            try {
                return (int) BINDING.giMaterials().invokeExact(handle, id, colors, triangles) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("GI materials", failure);
            }
        }

        boolean dispatchGi(MemorySegment parameters) {
            requireOpen();
            if (parameters.byteSize() < BgfxGlobalIllumination.PARAM_FLOATS * 4L) {
                throw new IllegalArgumentException("GI parameters require transport and local-light data");
            }
            try {
                return (int) BINDING.giDispatch().invokeExact(handle, parameters) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("GI dispatch", failure);
            }
        }

        boolean readGi(MemorySegment output, int floats, MemorySegment milliseconds) {
            requireOpen();
            try {
                return (int) BINDING.giRead().invokeExact(handle, output, floats, milliseconds) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("GI read", failure);
            }
        }

        boolean registerGeometry(
                long stableId,
                MemorySegment vertices,
                int vertexCount,
                int vertexStride,
                int uvOffset,
                MemorySegment indices,
                int indexCount,
                MemorySegment ranges,
                int rangeCount,
                MemorySegment alphaBytes,
                int alphaByteCount) {
            requireOpen();
            try {
                return (int) BINDING.registerGeometry()
                                .invokeExact(
                                        handle,
                                        stableId,
                                        vertices,
                                        vertexCount,
                                        vertexStride,
                                        uvOffset,
                                        indices,
                                        indexCount,
                                        ranges,
                                        rangeCount,
                                        alphaBytes,
                                        alphaByteCount)
                        != 0;
            } catch (Throwable failure) {
                throw nativeFailure("register geometry", failure);
            }
        }

        boolean registerInstances(
                long ownerId,
                long stableId,
                long geometryId,
                MemorySegment matrices,
                int instanceCount,
                int lodLevel,
                float lodDistance1,
                float lodDistance2) {
            requireOpen();
            try {
                return (int) BINDING.registerInstances()
                                .invokeExact(
                                        handle,
                                        ownerId,
                                        stableId,
                                        geometryId,
                                        matrices,
                                        instanceCount,
                                        lodLevel,
                                        lodDistance1,
                                        lodDistance2)
                        != 0;
            } catch (Throwable failure) {
                throw nativeFailure("register instances", failure);
            }
        }

        void removeOwner(long ownerId) {
            requireOpen();
            try {
                BINDING.removeOwner().invokeExact(handle, ownerId);
            } catch (Throwable failure) {
                throw nativeFailure("remove owner", failure);
            }
        }

        boolean supportsGeometryRetirement() {
            return BINDING.removeGeometry() != null;
        }

        boolean removeGeometry(long geometryId) {
            requireOpen();
            if (BINDING.removeGeometry() == null) {
                return false;
            }
            try {
                return (int) BINDING.removeGeometry().invokeExact(handle, geometryId) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("remove editor geometry", failure);
            }
        }

        boolean setFocus(float x, float y, float radius) {
            requireOpen();
            try {
                return (int) BINDING.setFocus().invokeExact(handle, x, y, radius) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("set local TLAS focus", failure);
            }
        }

        BuildState pollBuild() {
            requireOpen();
            try {
                var ordinal = (int) BINDING.pollBuild().invokeExact(handle);
                return ordinal >= 0 && ordinal < BuildState.values().length
                        ? BuildState.values()[ordinal]
                        : BuildState.FAILED;
            } catch (Throwable failure) {
                throw nativeFailure("poll acceleration-structure build", failure);
            }
        }

        int getInstanceCount() {
            requireOpen();
            try {
                return (int) BINDING.instanceCount().invokeExact(handle);
            } catch (Throwable failure) {
                throw nativeFailure("query instance count", failure);
            }
        }

        boolean dispatch(
                MemorySegment inverseViewProjection,
                MemorySegment rayBasis,
                MemorySegment camera,
                MemorySegment sun,
                int renderWidth,
                int renderHeight) {
            requireOpen();
            try {
                return (int) BINDING.dispatch()
                                .invokeExact(
                                        handle, inverseViewProjection, rayBasis, camera, sun, renderWidth, renderHeight)
                        != 0;
            } catch (Throwable failure) {
                throw nativeFailure("dispatch visibility", failure);
            }
        }

        boolean pollVisibility() {
            requireOpen();
            try {
                return (int) BINDING.pollVisibility().invokeExact(handle) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("poll visibility", failure);
            }
        }

        boolean waitVisibility() {
            requireOpen();
            try {
                return (int) BINDING.waitVisibility().invokeExact(handle) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("wait for same-frame visibility", failure);
            }
        }

        boolean armBgfxWait() {
            requireOpen();
            try {
                return (int) BINDING.armBgfxWait().invokeExact(handle) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("arm bgfx GPU visibility wait", failure);
            }
        }

        boolean isBgfxWaitAvailable() {
            requireOpen();
            try {
                return (int) BINDING.bgfxWaitAvailable().invokeExact(handle) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("query bgfx GPU wait capability", failure);
            }
        }

        long getTextureGeneration() {
            return invokeLong(BINDING.textureGeneration(), "query texture generation");
        }

        long getPublishedSerial() {
            return invokeLong(BINDING.publishedSerial(), "query published serial");
        }

        int getPublishedSlot() {
            return invokeInt(BINDING.publishedSlot(), "query published slot");
        }

        int getTextureWidth() {
            return invokeInt(BINDING.textureWidth(), "query texture width");
        }

        int getTextureHeight() {
            return invokeInt(BINDING.textureHeight(), "query texture height");
        }

        int getPublishedWidth() {
            return invokeInt(BINDING.publishedWidth(), "query published width");
        }

        int getPublishedHeight() {
            return invokeInt(BINDING.publishedHeight(), "query published height");
        }

        boolean copyPublishedReprojection(MemorySegment viewProjection, MemorySegment camera) {
            requireOpen();
            try {
                return (int) BINDING.publishedReprojection().invokeExact(handle, viewProjection, camera) != 0;
            } catch (Throwable failure) {
                throw nativeFailure("copy published reprojection", failure);
            }
        }

        long getTexturePointer(int slot) {
            requireOpen();
            try {
                var texture = (MemorySegment) BINDING.texture().invokeExact(handle, slot);
                return texture.equals(MemorySegment.NULL) ? 0L : texture.address();
            } catch (Throwable failure) {
                throw nativeFailure("query visibility texture", failure);
            }
        }

        String getLastError() {
            if (handle == null) {
                return "Metal ray-query session is closed";
            }
            try {
                var required = (long) BINDING.lastError().invokeExact(handle, MemorySegment.NULL, 0L);
                if (required <= 1L || required > 16_384L) {
                    return "";
                }
                try (var arena = Arena.ofConfined()) {
                    var destination = arena.allocate(required, 1L);
                    var copied = (long) BINDING.lastError().invokeExact(handle, destination, required);
                    return copied == required ? destination.getString(0L) : "";
                }
            } catch (Throwable failure) {
                return messageOf(failure);
            }
        }

        @Override
        public void close() {
            var current = handle;
            if (current == null) {
                return;
            }
            handle = null;
            try {
                BINDING.destroy().invokeExact(current);
            } catch (Throwable failure) {
                throw nativeFailure("destroy context", failure);
            }
        }

        private long invokeLong(MethodHandle method, String action) {
            requireOpen();
            try {
                return (long) method.invokeExact(handle);
            } catch (Throwable failure) {
                throw nativeFailure(action, failure);
            }
        }

        private int invokeInt(MethodHandle method, String action) {
            requireOpen();
            try {
                return (int) method.invokeExact(handle);
            } catch (Throwable failure) {
                throw nativeFailure(action, failure);
            }
        }

        private void requireOpen() {
            if (handle == null) {
                throw new IllegalStateException("Metal ray-query session is closed");
            }
        }

        private IllegalStateException nativeFailure(String action, Throwable failure) {
            var nativeError = getLastError();
            var suffix = nativeError.isBlank() ? messageOf(failure) : nativeError;
            return new IllegalStateException("Metal ray-query " + action + " failed: " + suffix, failure);
        }
    }

    enum BuildState {
        COLLECTING,
        BUILDING_BLAS,
        BUILDING_TLAS,
        READY,
        FAILED
    }
}

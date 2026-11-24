package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_create_compute_program;
import static org.lwjgl.bgfx.BGFX.bgfx_create_program;

import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Lease;
import java.nio.ByteBuffer;
import org.lwjgl.bgfx.BGFX;

public final class BgfxProgram implements AutoCloseable {
    private final Lease lease;
    private final BgfxShader vertexOrCompute;
    private final BgfxShader fragment;
    private final boolean ownsVertexOrCompute;
    private final boolean ownsFragment;

    private boolean closed;

    private BgfxProgram(
            Lease lease,
            BgfxShader vertexOrCompute,
            BgfxShader fragment,
            boolean ownsVertexOrCompute,
            boolean ownsFragment) {
        this.lease = lease;
        this.vertexOrCompute = vertexOrCompute;
        this.fragment = fragment;
        this.ownsVertexOrCompute = ownsVertexOrCompute;
        this.ownsFragment = ownsFragment;
    }

    public static BgfxProgram graphics(
            BgfxResourceRegistry registry, String label, ByteBuffer vertexBytecode, ByteBuffer fragmentBytecode) {
        BgfxShader vertex = null;
        BgfxShader fragment = null;
        try {
            vertex = BgfxShader.create(registry, label + "/vs", vertexBytecode);
            fragment = BgfxShader.create(registry, label + "/fs", fragmentBytecode);
            var handle = bgfx_create_program(vertex.getHandle(), fragment.getHandle(), false);
            var lease = registry.registerReference(Kind.PROGRAM, handle, label, 0L, BGFX::bgfx_destroy_program);
            return new BgfxProgram(lease, vertex, fragment, true, true);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(fragment, e);
            closeAfterFailure(vertex, e);
            throw e;
        }
    }

    static BgfxProgram graphics(
            BgfxResourceRegistry registry, String label, BgfxShader sharedVertex, ByteBuffer fragmentBytecode) {
        if (sharedVertex == null) {
            throw new IllegalArgumentException("shared vertex shader cannot be null");
        }
        BgfxShader fragment = null;
        try {
            fragment = BgfxShader.create(registry, label + "/fs", fragmentBytecode);
            var handle = bgfx_create_program(sharedVertex.getHandle(), fragment.getHandle(), false);
            var lease = registry.registerReference(Kind.PROGRAM, handle, label, 0L, BGFX::bgfx_destroy_program);
            return new BgfxProgram(lease, sharedVertex, fragment, false, true);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(fragment, e);
            throw e;
        }
    }

    static BgfxProgram graphics(
            BgfxResourceRegistry registry, String label, BgfxShader sharedVertex, BgfxShader sharedFragment) {
        if (sharedVertex == null || sharedFragment == null) {
            throw new IllegalArgumentException("shared graphics shaders cannot be null");
        }
        var handle = bgfx_create_program(sharedVertex.getHandle(), sharedFragment.getHandle(), false);
        var lease = registry.registerReference(Kind.PROGRAM, handle, label, 0L, BGFX::bgfx_destroy_program);
        return new BgfxProgram(lease, sharedVertex, sharedFragment, false, false);
    }

    public static BgfxProgram compute(
            BgfxResourceRegistry registry, BgfxCapabilities capabilities, String label, ByteBuffer computeBytecode) {
        if (!capabilities.compute()) {
            throw new UnsupportedOperationException("active bgfx renderer does not support compute shaders");
        }
        BgfxShader compute = null;
        try {
            compute = BgfxShader.create(registry, label + "/cs", computeBytecode);
            var handle = bgfx_create_compute_program(compute.getHandle(), false);
            var lease = registry.registerReference(Kind.PROGRAM, handle, label, 0L, BGFX::bgfx_destroy_program);
            return new BgfxProgram(lease, compute, null, true, false);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(compute, e);
            throw e;
        }
    }

    public short getHandle() {
        return lease.getHandle();
    }

    public boolean compute() {
        return fragment == null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            lease.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        if (ownsFragment) {
            failure = closeAndMerge(fragment, failure);
        }
        if (ownsVertexOrCompute) {
            failure = closeAndMerge(vertexOrCompute, failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
        } catch (Exception e) {
            var wrapped = new IllegalStateException("failed to close bgfx shader", e);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}

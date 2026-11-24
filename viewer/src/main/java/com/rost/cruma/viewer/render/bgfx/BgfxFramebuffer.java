package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_create_frame_buffer_from_handles;
import static org.lwjgl.bgfx.BGFX.bgfx_set_frame_buffer_name;

import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Lease;
import java.util.List;
import org.lwjgl.bgfx.BGFX;

public final class BgfxFramebuffer implements AutoCloseable {
    private final Lease lease;
    private final List<BgfxTexture> attachments;

    private BgfxFramebuffer(Lease lease, List<BgfxTexture> attachments) {
        this.lease = lease;
        this.attachments = attachments;
    }

    public static BgfxFramebuffer create(BgfxResourceRegistry registry, String label, List<BgfxTexture> attachments) {
        if (attachments == null || attachments.isEmpty() || attachments.size() > 8) {
            throw new IllegalArgumentException("framebuffer requires between one and eight attachments");
        }
        var stableAttachments = List.copyOf(attachments);
        var handles = new short[stableAttachments.size()];
        for (var i = 0; i < handles.length; i++) {
            handles[i] = stableAttachments.get(i).getHandle();
        }
        var handle = bgfx_create_frame_buffer_from_handles(handles, false);
        var lease = registry.register(Kind.FRAMEBUFFER, handle, label, 0L, BGFX::bgfx_destroy_frame_buffer);
        bgfx_set_frame_buffer_name(handle, lease.getLabel());
        return new BgfxFramebuffer(lease, stableAttachments);
    }

    public short getHandle() {
        for (var attachment : attachments) {
            attachment.getHandle();
        }
        return lease.getHandle();
    }

    public List<BgfxTexture> getAttachments() {
        return attachments;
    }

    @Override
    public void close() {
        lease.close();
    }
}

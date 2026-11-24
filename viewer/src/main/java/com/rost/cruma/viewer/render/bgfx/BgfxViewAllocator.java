package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_INVALID_HANDLE;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_DEFAULT;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public final class BgfxViewAllocator {
    public static final int END_OF_FRAME_RESERVE = 72;

    private static final int FIRST_DYNAMIC_VIEW = BgfxDevice.BUFFER_COPY_VIEW + 1;

    private final int uiViewId;
    private final int acceptedWorldViewLimit;

    private final List<View> views = new ArrayList<>();

    private int next = FIRST_DYNAMIC_VIEW;
    private int allocated;

    public BgfxViewAllocator(int maxViews, int acceptedWorldViewLimit) {
        if (maxViews < 3 || acceptedWorldViewLimit < 1 || acceptedWorldViewLimit >= maxViews) {
            throw new IllegalArgumentException("invalid bgfx view budget");
        }
        this.uiViewId = maxViews - 1;
        this.acceptedWorldViewLimit = Math.min(acceptedWorldViewLimit, uiViewId - FIRST_DYNAMIC_VIEW);
    }

    public static int countFitting(int remaining, int viewsEach, int reserve) {
        if (viewsEach < 1) {
            throw new IllegalArgumentException("a pass takes at least one view");
        }
        return Math.max(0, remaining - reserve) / viewsEach;
    }

    public void beginFrame() {
        allocated = 0;
        next = FIRST_DYNAMIC_VIEW;
    }

    public int allocate(String name, Domain domain, boolean sequential) {
        if (name == null || name.isBlank() || domain == null) {
            throw new IllegalArgumentException("view name/domain is required");
        }
        if (next >= uiViewId || allocated >= acceptedWorldViewLimit) {
            throw new IllegalStateException("bgfx view budget exhausted before '"
                    + name
                    + "': used="
                    + allocated
                    + ", accepted="
                    + acceptedWorldViewLimit
                    + ", ui="
                    + uiViewId
                    + ", taken by "
                    + describeAllocated());
        }
        var id = next++;
        bgfx_set_view_name(id, name);
        bgfx_set_view_clear(id, 0, 0, 1f, 0);
        bgfx_set_view_frame_buffer(id, BGFX_INVALID_HANDLE);
        bgfx_set_view_mode(id, sequential ? BGFX_VIEW_MODE_SEQUENTIAL : BGFX_VIEW_MODE_DEFAULT);
        bgfx_set_view_rect(id, 0, 0, 1, 1);
        var descriptor = allocated < views.size() ? views.get(allocated) : null;
        if (descriptor == null
                || descriptor.id() != id
                || !descriptor.name().equals(name)
                || descriptor.domain() != domain
                || descriptor.sequential() != sequential) {
            descriptor = new View(id, name, domain, sequential);
            if (allocated == views.size()) {
                views.add(descriptor);
            } else {
                views.set(allocated, descriptor);
            }
        }
        allocated++;
        return id;
    }

    public List<View> getAllocatedViews() {
        return List.copyOf(views.subList(0, allocated));
    }

    public int getRemaining() {
        return acceptedWorldViewLimit - allocated;
    }

    public int getUiViewId() {
        return uiViewId;
    }

    private String describeAllocated() {
        var counts = new TreeMap<String, Integer>();
        for (var view : views.subList(0, allocated)) {
            counts.merge(view.name().replaceAll("[0-9]+", "#"), 1, Integer::sum);
        }
        return counts.toString();
    }

    public record View(int id, String name, Domain domain, boolean sequential) {}

    public enum Domain {
        DISPLAY,
        RENDER,
        REFLECTION,
        BLOOM_QUARTER,
        CAPTURE
    }
}

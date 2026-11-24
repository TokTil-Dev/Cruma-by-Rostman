package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_COUNT;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_COUNT;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_HALF;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_INT16;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_UINT10;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_UINT8;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_type;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_add;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_begin;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_end;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_get_stride;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_skip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;

public final class BgfxVertexLayoutSpec {
    private final List<Element> elements;
    private final List<Attribute> attributes;
    private final int expectedStride;

    private BgfxVertexLayoutSpec(List<Element> elements) {
        var seen = new HashSet<Integer>();
        var attributes = new ArrayList<Attribute>(elements.size());
        var stride = 0;
        for (var element : elements) {
            if (element instanceof Attribute attribute) {
                if (!seen.add(attribute.semantic())) {
                    throw new IllegalArgumentException("duplicate vertex semantic " + attribute.semantic());
                }
                attributes.add(attribute);
            }
            stride = Math.addExact(stride, element.byteSize());
        }
        if (attributes.isEmpty()) {
            throw new IllegalArgumentException("vertex layout must contain at least one attribute");
        }
        this.elements = List.copyOf(elements);
        this.attributes = List.copyOf(attributes);
        this.expectedStride = stride;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Attribute> getAttributes() {
        return attributes;
    }

    public int getStride() {
        return expectedStride;
    }

    BGFXVertexLayout createNative(MemoryStack stack) {
        var nativeLayout = BGFXVertexLayout.calloc(stack);
        bgfx_vertex_layout_begin(nativeLayout, bgfx_get_renderer_type());
        for (var element : elements) {
            switch (element) {
                case Attribute attribute ->
                    bgfx_vertex_layout_add(
                            nativeLayout,
                            attribute.semantic(),
                            attribute.components(),
                            attribute.type(),
                            attribute.normalized(),
                            attribute.asInteger());
                case Padding padding -> bgfx_vertex_layout_skip(nativeLayout, padding.byteSize());
            }
        }
        bgfx_vertex_layout_end(nativeLayout);
        var actualStride = Short.toUnsignedInt(bgfx_vertex_layout_get_stride(nativeLayout));
        if (actualStride != expectedStride) {
            throw new IllegalStateException(
                    "bgfx vertex stride mismatch: expected " + expectedStride + ", got " + actualStride);
        }
        return nativeLayout;
    }

    sealed interface Element permits Attribute, Padding {
        int byteSize();
    }

    record Padding(int byteSize) implements Element {
        Padding {
            if (byteSize < 1 || byteSize > 255) {
                throw new IllegalArgumentException("vertex layout padding must be within 1..255 bytes");
            }
        }
    }

    public record Attribute(int semantic, int components, int type, boolean normalized, boolean asInteger)
            implements Element {
        public Attribute {
            if (semantic < 0 || semantic >= BGFX_ATTRIB_COUNT) {
                throw new IllegalArgumentException("invalid bgfx vertex semantic: " + semantic);
            }
            if (components < 1 || components > 4) {
                throw new IllegalArgumentException("vertex attribute component count must be within 1..4");
            }
            if (type < 0 || type >= BGFX_ATTRIB_TYPE_COUNT) {
                throw new IllegalArgumentException("invalid bgfx vertex attribute type: " + type);
            }
            if (type == BGFX_ATTRIB_TYPE_UINT10 && components != 4) {
                throw new IllegalArgumentException("UINT10 vertex attributes must contain four components");
            }
            if (normalized && type == BGFX_ATTRIB_TYPE_FLOAT) {
                throw new IllegalArgumentException("floating-point vertex attributes cannot be normalized");
            }
        }

        @Override
        public int byteSize() {
            return switch (type) {
                case BGFX_ATTRIB_TYPE_UINT8 -> components;
                case BGFX_ATTRIB_TYPE_INT16, BGFX_ATTRIB_TYPE_HALF -> components * 2;
                case BGFX_ATTRIB_TYPE_UINT10 -> 4;
                case BGFX_ATTRIB_TYPE_FLOAT -> components * 4;
                default -> throw new IllegalArgumentException("unsupported bgfx vertex attribute type: " + type);
            };
        }
    }

    public static final class Builder {
        private final List<Element> elements = new ArrayList<>();

        public Builder add(int semantic, int components, int type) {
            return add(semantic, components, type, false, false);
        }

        public Builder add(int semantic, int components, int type, boolean normalized, boolean asInteger) {
            elements.add(new Attribute(semantic, components, type, normalized, asInteger));
            return this;
        }

        public Builder skip(int bytes) {
            elements.add(new Padding(bytes));
            return this;
        }

        public BgfxVertexLayoutSpec build() {
            return new BgfxVertexLayoutSpec(elements);
        }
    }
}

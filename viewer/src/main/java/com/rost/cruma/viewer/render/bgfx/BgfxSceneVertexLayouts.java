package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_COLOR0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_COLOR1;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_NORMAL;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TANGENT;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD1;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD2;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD4;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD5;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD6;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD7;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_HALF;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_UINT8;

final class BgfxSceneVertexLayouts {
    static final BgfxVertexLayoutSpec FX_QUAD = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .build();

    static final BgfxVertexLayoutSpec FX_RIBBON = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .build();

    static final BgfxVertexLayoutSpec SKY = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .build();

    static final BgfxVertexLayoutSpec ENTITY = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_NORMAL, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TANGENT, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD1, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD2, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR1, 4, BGFX_ATTRIB_TYPE_HALF)
            .add(BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_UINT8, true, false)
            .build();

    static final float COMPACT_STATIC_MESH_MARKER = 2f;

    static final BgfxVertexLayoutSpec STATIC_MESH = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_NORMAL, 4, BGFX_ATTRIB_TYPE_HALF)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD2, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR1, 4, BGFX_ATTRIB_TYPE_HALF)
            .build();

    static final BgfxVertexLayoutSpec STATIC_MESH_COLOR = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_NORMAL, 4, BGFX_ATTRIB_TYPE_HALF)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD2, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR1, 4, BGFX_ATTRIB_TYPE_HALF)
            .add(BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_UINT8, true, false)
            .build();

    static final BgfxVertexLayoutSpec INSTANCE = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_TEXCOORD4, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD5, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD6, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD7, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR1, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .build();

    static final BgfxVertexLayoutSpec FX_BILLBOARD_INSTANCE = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_TEXCOORD4, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD5, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD6, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD7, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR1, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .build();

    static final BgfxVertexLayoutSpec MATERIAL_TABLE = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_TEXCOORD0, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .build();

    static final BgfxVertexLayoutSpec FX_ACTOR_TRANSFORM = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_TEXCOORD0, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .build();

    static final BgfxVertexLayoutSpec VISIBILITY_BOUNDS = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_TEXCOORD0, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD1, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD2, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .build();

    static final BgfxVertexLayoutSpec TERRAIN = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_NORMAL, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_HALF)
            .add(BGFX_ATTRIB_COLOR1, 4, BGFX_ATTRIB_TYPE_HALF)
            .add(BGFX_ATTRIB_TEXCOORD7, 4, BGFX_ATTRIB_TYPE_UINT8, true, false)
            .build();

    private BgfxSceneVertexLayouts() {}

    static BgfxVertexLayoutSpec followingFrame(BgfxVertexLayoutSpec frame) {
        var attributes = frame.getAttributes();
        if (attributes.size() < 2) {
            throw new IllegalArgumentException("frame layout has no position and normal");
        }
        var position = attributes.get(0);
        var normal = attributes.get(1);
        if (position.semantic() != BGFX_ATTRIB_POSITION
                || position.components() != 3
                || position.type() != BGFX_ATTRIB_TYPE_FLOAT
                || normal.semantic() != BGFX_ATTRIB_NORMAL) {
            throw new IllegalArgumentException("frame layout must begin with a float position and a normal");
        }
        var builder = BgfxVertexLayoutSpec.builder()
                .add(BGFX_ATTRIB_TEXCOORD6, 3, BGFX_ATTRIB_TYPE_FLOAT)
                .add(
                        BGFX_ATTRIB_TEXCOORD7,
                        normal.components(),
                        normal.type(),
                        normal.normalized(),
                        normal.asInteger());
        for (var rest = frame.getStride() - position.byteSize() - normal.byteSize(); rest > 0; rest -= 255) {
            builder.skip(Math.min(rest, 255));
        }
        return builder.build();
    }
}

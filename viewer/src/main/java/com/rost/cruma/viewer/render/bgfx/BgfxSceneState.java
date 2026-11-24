package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_DST_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_EQUATION_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FACTOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC_SEPARATE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_FACTOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ONE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ZERO;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_CULL_CCW;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_CULL_CW;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_ALWAYS;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_EQUAL;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LESS;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_MSAA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;

import com.rost.cruma.core.world.AuthoredFramebuffer;
import com.rost.cruma.core.world.AuthoredFramebuffer.Factor;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.viewer.render.ParticleEmitter;
import org.lwjgl.bgfx.BGFX;

final class BgfxSceneState {
    private BgfxSceneState() {}

    static long of(BgfxSceneMaterial material, boolean ortho) {
        return of(material, ortho, false);
    }

    static long of(BgfxSceneMaterial material, boolean ortho, boolean mirrored) {
        var state = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_DEPTH_TEST_LESS | BGFX_STATE_MSAA;
        if (material.hasDepthWrite()) {
            state |= BGFX_STATE_WRITE_Z;
        }
        state |= blend(material);
        state |= cull(material.getSurfaceType(), ortho, mirrored);
        return state;
    }

    static long terrain(boolean ortho, boolean mirrored) {
        return BGFX_STATE_WRITE_RGB
                | BGFX_STATE_WRITE_A
                | BGFX_STATE_WRITE_Z
                | BGFX_STATE_DEPTH_TEST_LESS
                | BGFX_STATE_MSAA
                | cull(SurfaceType.TERRAIN.getId(), ortho, mirrored);
    }

    static long terrainOverlay(boolean ortho, boolean mirrored) {
        return BGFX_STATE_WRITE_RGB
                | BGFX_STATE_DEPTH_TEST_EQUAL
                | BGFX_STATE_MSAA
                | BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_INV_SRC_ALPHA)
                | cull(SurfaceType.TERRAIN.getId(), ortho, mirrored);
    }

    static long faint(long state) {
        return state & ~(BGFX_STATE_BLEND_MASK | BGFX_STATE_BLEND_EQUATION_MASK | BGFX_STATE_WRITE_A)
                | BGFX_STATE_WRITE_Z
                | BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_FACTOR, BGFX_STATE_BLEND_INV_FACTOR);
    }

    static long fading(long state) {
        var result = state;
        if ((state & BGFX_STATE_DEPTH_TEST_MASK) == BGFX_STATE_DEPTH_TEST_LESS) {
            result = result & ~BGFX_STATE_DEPTH_TEST_MASK | BGFX_STATE_DEPTH_TEST_LEQUAL;
        }
        if (hasBlend(state)) {
            return result;
        }
        return result & ~(BGFX_STATE_BLEND_MASK | BGFX_STATE_BLEND_EQUATION_MASK)
                | BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_FACTOR, BGFX_STATE_BLEND_INV_FACTOR);
    }

    static boolean hasBlend(long state) {
        return (state & BGFX_STATE_BLEND_MASK) != 0;
    }

    static long water() {
        return BGFX_STATE_WRITE_RGB
                | BGFX_STATE_WRITE_A
                | BGFX_STATE_DEPTH_TEST_LEQUAL
                | BGFX_STATE_MSAA
                | BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_INV_SRC_ALPHA);
    }

    static long water(BgfxSceneMaterial material, boolean replacement, boolean ortho, boolean mirrored) {
        if (replacement) {
            return water();
        }
        var framebuffer = material.getFramebuffer();
        if (framebuffer != null) {
            return authoredFramebuffer(framebuffer, material.getSurfaceType(), ortho, mirrored);
        }
        return (of(material, ortho, mirrored) & ~BGFX_STATE_DEPTH_TEST_LESS) | BGFX_STATE_DEPTH_TEST_LEQUAL;
    }

    static long authoredFramebuffer(
            AuthoredFramebuffer framebuffer, long surfaceType, boolean ortho, boolean mirrored) {
        var state = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_MSAA;
        state |= framebuffer.depthTest() ? BGFX_STATE_DEPTH_TEST_LEQUAL : BGFX_STATE_DEPTH_TEST_ALWAYS;
        if (framebuffer.depthWrite()) {
            state |= BGFX_STATE_WRITE_Z;
        }
        if (!framebuffer.twoSided()) {
            state |= cull(surfaceType, ortho, mirrored);
        }
        if (framebuffer.blending()) {
            var color = framebuffer.color();
            var alpha = framebuffer.alpha();
            state |= alpha == null
                    ? BGFX_STATE_BLEND_FUNC(toBlendFactor(color.source()), toBlendFactor(color.destination()))
                    : BGFX_STATE_BLEND_FUNC_SEPARATE(
                            toBlendFactor(color.source()),
                            toBlendFactor(color.destination()),
                            toBlendFactor(alpha.source()),
                            toBlendFactor(alpha.destination()));
        }
        return state;
    }

    static long authoredPass(long materialState, int source, int destination) {
        return (materialState & ~BGFX.BGFX_STATE_BLEND_MASK)
                | BGFX_STATE_BLEND_FUNC(nativeFactor(source), nativeFactor(destination));
    }

    static long shadow(BgfxSceneMaterial material) {
        return BGFX_STATE_WRITE_Z | BGFX_STATE_DEPTH_TEST_LESS | cull(material.getSurfaceType(), false, false);
    }

    static long terrainShadow() {
        return BGFX_STATE_WRITE_Z | BGFX_STATE_DEPTH_TEST_LESS;
    }

    static long projector(int fusedBlendPair, long receiverSurfaceType, boolean ortho, boolean projectOnBackfaces) {
        var state = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_DEPTH_TEST_LEQUAL | BGFX_STATE_MSAA;
        state |= switch (fusedBlendPair) {
            case 0 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_DST_COLOR, BGFX_STATE_BLEND_SRC_COLOR);
            case 1 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_DST_COLOR, BGFX_STATE_BLEND_INV_SRC_ALPHA);
            case 2 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_DST_COLOR, BGFX_STATE_BLEND_ONE);
            default -> throw new IllegalArgumentException("unsupported fused projector blend pair: " + fusedBlendPair);
        };
        if (!projectOnBackfaces) {
            state |= cull(receiverSurfaceType, ortho, false);
        }
        return state;
    }

    static long particleFx(int blendMode) {
        var state = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_DEPTH_TEST_LEQUAL | BGFX_STATE_MSAA;
        return state
                | switch (blendMode) {
                    case 1 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_ONE);
                    case 2 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_DST_COLOR, BGFX_STATE_BLEND_SRC_COLOR);
                    case 3 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_INV_SRC_COLOR);
                    case 4 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ZERO, BGFX_STATE_BLEND_INV_SRC_COLOR);
                    default -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_INV_SRC_ALPHA);
                };
    }

    static long particleMesh(BgfxSceneMaterial material, ParticleEmitter emitter) {
        var state = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_DEPTH_TEST_LEQUAL | BGFX_STATE_MSAA;
        if (emitter.isUseMeshBlendMode()) {
            if (material.hasDepthWrite()) {
                state |= BGFX_STATE_WRITE_Z;
            }
            state |= blend(material);
        } else {
            var drawStyle = emitter.getBlend();
            if (drawStyle == 0) {
                state |= BGFX_STATE_WRITE_Z;
            }
            if (emitter.isBlackBackground() && drawStyle == 0) {
                state |= BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_INV_SRC_COLOR);
            } else {
                state |= switch (drawStyle) {
                    case 3 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_ONE);
                    case 6 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_INV_SRC_COLOR);
                    case 2 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_DST_COLOR, BGFX_STATE_BLEND_SRC_COLOR);
                    case 5 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ZERO, BGFX_STATE_BLEND_INV_SRC_COLOR);
                    case 1, 4 -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_INV_SRC_ALPHA);
                    default -> 0L;
                };
            }
        }
        state |= particleMeshCull(isParticleMeshTwoSided(material.getSurfaceType(), emitter));
        return state;
    }

    static boolean isParticleMeshTwoSided(long surfaceType, ParticleEmitter emitter) {
        return emitter.takesSidesFromMeshMaterial()
                ? (surfaceType & SurfaceType.MESH_TWO_SIDED.getId()) != 0
                : emitter.isRenderTwoSided();
    }

    static long particleMeshCull(boolean twoSided) {
        return twoSided ? 0L : BGFX_STATE_CULL_CCW;
    }

    static long cull(long surfaceType, boolean ortho, boolean mirrored) {
        if (!ortho
                && (surfaceType & SurfaceType.CSG.getId()) != 0
                && (surfaceType & SurfaceType.CSG_NATIVE_PLANE_SIDE.getId()) == 0
                && (surfaceType & SurfaceType.CSG_CULL_DISABLED.getId()) == 0) {
            return mirrored ? BGFX_STATE_CULL_CW : BGFX_STATE_CULL_CCW;
        }
        if (!ortho && (surfaceType & SurfaceType.TERRAIN.getId()) != 0) {
            return mirrored ? BGFX_STATE_CULL_CW : BGFX_STATE_CULL_CCW;
        }
        var turned = mirrored != ((surfaceType & SurfaceType.MESH_MIRRORED.getId()) != 0);
        if ((surfaceType & SurfaceType.TWO_SIDED_DOUBLED.getId()) != 0) {
            return turned ? BGFX_STATE_CULL_CCW : BGFX_STATE_CULL_CW;
        }
        if (!ortho
                && (surfaceType & SurfaceType.STATIC_MESH.getId()) != 0
                && (surfaceType & SurfaceType.MESH_TWO_SIDED.getId()) == 0) {
            return turned ? BGFX_STATE_CULL_CW : BGFX_STATE_CULL_CCW;
        }
        return 0L;
    }

    private static long nativeFactor(int factor) {
        return switch (factor) {
            case 1 -> BGFX_STATE_BLEND_ZERO;
            case 2 -> BGFX_STATE_BLEND_ONE;
            case 3 -> BGFX_STATE_BLEND_SRC_COLOR;
            case 4 -> BGFX_STATE_BLEND_INV_SRC_COLOR;
            case 5 -> BGFX_STATE_BLEND_SRC_ALPHA;
            case 6 -> BGFX_STATE_BLEND_INV_SRC_ALPHA;
            case 7 -> BGFX.BGFX_STATE_BLEND_DST_ALPHA;
            case 8 -> BGFX.BGFX_STATE_BLEND_INV_DST_ALPHA;
            case 9 -> BGFX_STATE_BLEND_DST_COLOR;
            case 10 -> BGFX.BGFX_STATE_BLEND_INV_DST_COLOR;
            default -> throw new IllegalArgumentException("unsupported native pass blend " + factor);
        };
    }

    private static long toBlendFactor(Factor factor) {
        return switch (factor) {
            case ZERO -> BGFX_STATE_BLEND_ZERO;
            case ONE -> BGFX_STATE_BLEND_ONE;
            case SOURCE_COLOR -> BGFX_STATE_BLEND_SRC_COLOR;
            case INVERSE_SOURCE_COLOR -> BGFX_STATE_BLEND_INV_SRC_COLOR;
            case SOURCE_ALPHA -> BGFX_STATE_BLEND_SRC_ALPHA;
            case INVERSE_SOURCE_ALPHA -> BGFX_STATE_BLEND_INV_SRC_ALPHA;
            case DESTINATION_COLOR -> BGFX_STATE_BLEND_DST_COLOR;
        };
    }

    private static long blend(BgfxSceneMaterial material) {
        return material.hasPremultipliedSpecular()
                ? BGFX_STATE_BLEND_FUNC_SEPARATE(
                        BGFX_STATE_BLEND_ONE,
                        BGFX_STATE_BLEND_INV_SRC_ALPHA,
                        BGFX_STATE_BLEND_SRC_ALPHA,
                        BGFX_STATE_BLEND_INV_SRC_ALPHA)
                : blend(material.getBlendMode());
    }

    private static long blend(BlendMode mode) {
        return switch (mode) {
            case OPAQUE, MASKED, UNLIT, INVISIBLE -> 0L;
            case TRANSLUCENT, EMISSIVE, MASKED_BLEND ->
                BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_INV_SRC_ALPHA);
            case ADDITIVE -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_ONE);
            case BRIGHTEN -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_ONE);
            case MODULATE -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_DST_COLOR, BGFX_STATE_BLEND_SRC_COLOR);
            case SCREEN -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_INV_SRC_COLOR);
            case DARKEN -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ZERO, BGFX_STATE_BLEND_INV_SRC_COLOR);
        };
    }
}

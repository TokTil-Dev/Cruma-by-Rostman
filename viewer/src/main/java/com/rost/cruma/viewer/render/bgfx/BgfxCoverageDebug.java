package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ONE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;

import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import java.util.Locale;

final class BgfxCoverageDebug {
    static final String PROPERTY = "cruma.viewer.bgfx.debug_coverage";

    private final Family family;
    private final boolean overdraw;

    private BgfxCoverageDebug(Family family, boolean overdraw) {
        this.family = family;
        this.overdraw = overdraw;
    }

    static BgfxCoverageDebug fromSystemProperty() {
        var raw = System.getProperty(PROPERTY, "off").trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty() || raw.equals("off")) {
            return new BgfxCoverageDebug(Family.OFF, false);
        }
        if (raw.equals("stable-id-reference")) {
            return new BgfxCoverageDebug(Family.STABLE_ID_REFERENCE, false);
        }
        var overdraw = raw.endsWith("-overdraw");
        var name = overdraw ? raw.substring(0, raw.length() - "-overdraw".length()) : raw;
        try {
            return new BgfxCoverageDebug(Family.valueOf(name.toUpperCase(Locale.ROOT)), overdraw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    PROPERTY + " must be off, terrain, opaque, masked, water, reflection or "
                            + "stable-id-reference. Coverage families may be suffixed -overdraw",
                    e);
        }
    }

    boolean isEnabled() {
        return family != Family.OFF;
    }

    boolean isOverdraw() {
        return overdraw;
    }

    boolean isUnculledStableIdReference() {
        return family == Family.STABLE_ID_REFERENCE;
    }

    boolean selects(RenderFamily candidate, boolean reflection) {
        if (!isEnabled()) {
            return false;
        }
        if (family == Family.REFLECTION) {
            return reflection;
        }
        if (reflection) {
            return false;
        }
        return switch (family) {
            case TERRAIN -> candidate == RenderFamily.TERRAIN_OPAQUE;
            case OPAQUE -> candidate == RenderFamily.CSG_OPAQUE || candidate == RenderFamily.STATIC_MESH_OPAQUE;
            case MASKED -> candidate == RenderFamily.MASKED_OPAQUE;
            case WATER -> candidate == RenderFamily.WATER;
            case STABLE_ID_REFERENCE -> candidate.isGpuDrivenEligible();
            case OFF, REFLECTION -> false;
        };
    }

    long getState(long normalState, boolean selected) {
        if (!isEnabled() || !selected || !overdraw) {
            return normalState;
        }
        return BGFX_STATE_WRITE_RGB
                | BGFX_STATE_WRITE_A
                | BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_ONE);
    }

    String getDescription() {
        if (!isEnabled()) {
            return "off";
        }
        if (family == Family.STABLE_ID_REFERENCE) {
            return "stable-id-reference";
        }
        return family.name().toLowerCase(Locale.ROOT) + (overdraw ? "-overdraw" : "-coverage");
    }

    private enum Family {
        OFF,
        TERRAIN,
        OPAQUE,
        MASKED,
        WATER,
        REFLECTION,
        STABLE_ID_REFERENCE
    }
}

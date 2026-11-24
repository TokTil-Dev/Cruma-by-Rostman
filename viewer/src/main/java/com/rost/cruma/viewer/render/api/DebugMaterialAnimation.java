package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.unreal.primitives.UColorFade;
import com.rost.cruma.core.world.AuthoredMaterialFactors;
import com.rost.cruma.core.world.AuthoredMaterialPassProgram;
import com.rost.cruma.core.world.AuthoredMaterialProgram;
import com.rost.cruma.core.world.Entity.AnimatedTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorModifierEntityMaterial;
import com.rost.cruma.core.world.Entity.CombinerEntityMaterial;
import com.rost.cruma.core.world.Entity.ConstantOpacityEntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.FadeColorSpec;
import com.rost.cruma.core.world.Entity.SpecularEntityMaterial;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.UvAnimation;
import com.rost.cruma.core.world.Entity.WaterEntityMaterial;
import com.rost.cruma.core.world.TextureCoordinates.Axis;
import com.rost.cruma.core.world.TextureCoordinates.Modifier;
import com.rost.cruma.core.world.TextureCoordinates.Oscillator;
import com.rost.cruma.core.world.TextureCoordinates.Panner;
import com.rost.cruma.core.world.TextureCoordinates.Rotator;
import com.rost.cruma.core.world.TextureCoordinates.Scaler;
import com.rost.cruma.core.world.TextureSequence;
import java.util.List;

final class DebugMaterialAnimation {
    private DebugMaterialAnimation() {}

    static boolean hasAnimation(Surface surface) {
        return isUvAnimated(surface.uvAnimation()) || isMaterialAnimated(surface.material());
    }

    private static boolean isMaterialAnimated(EntityMaterial material) {
        return switch (material) {
            case null -> false;
            case AnimatedTextureEntityMaterial m -> m.frames().size() > 1;
            case ColorModifierEntityMaterial m -> isMaterialAnimated(m.base());
            case ConstantOpacityEntityMaterial m -> isMaterialAnimated(m.base());
            case SpecularEntityMaterial m ->
                isMaterialAnimated(m.base())
                        || isUvAnimated(m.overlay().uvAnimation())
                        || isFadeAnimated(m.overlay().fade());
            case CombinerEntityMaterial m -> isFadeAnimated(m.material2Fade());
            case WaterEntityMaterial m ->
                isUvAnimated(m.material2Anim())
                        || isProgramAnimated(m.authoredProgram())
                        || isPassProgramAnimated(m.authoredPasses());
            default -> false;
        };
    }

    private static boolean isUvAnimated(UvAnimation uv) {
        if (uv == null) {
            return false;
        }
        return switch (uv.mode()) {
            case 1 -> uv.paramX() != 0 || uv.paramY() != 0;
            case 2 -> uv.paramZ() != 0 || uv.paramW() != 0 || uv.extra() != 0;
            case 3 -> varies(uv.paramX(), uv.paramY()) || varies(uv.paramZ(), uv.paramW());
            case 5 -> varies(uv.paramX(), uv.paramZ());
            default -> false;
        };
    }

    private static boolean isProgramAnimated(AuthoredMaterialProgram program) {
        return program != null
                && (isFadeAnimated(program.animatedFactor())
                        || areFactorsAnimated(program.factorVisitor())
                        || areCoordinatesAnimated(program.factorCoordinates())
                        || program.stages().stream()
                                .anyMatch(s -> isSequenceAnimated(s.bitmap())
                                        || areCoordinatesAnimated(
                                                s.coordinates().modifiers())));
    }

    private static boolean isPassProgramAnimated(AuthoredMaterialPassProgram program) {
        return program != null
                && (isFadeAnimated(program.animatedFactor())
                        || areFactorsAnimated(program.factorVisitor())
                        || areCoordinatesAnimated(program.factorCoordinates())
                        || program.compiledFactors().stream().anyMatch(f -> isFadeAnimated(f.animation()))
                        || program.boundSamples().stream()
                                .anyMatch(s -> isSequenceAnimated(s.bitmap())
                                        || areCoordinatesAnimated(
                                                s.coordinates().modifiers()))
                        || isSequenceAnimated(program.shaderMaps().normal())
                        || isSequenceAnimated(program.shaderMaps().specular()));
    }

    private static boolean isSequenceAnimated(TextureSequence sequence) {
        return sequence != null
                && sequence.frames().size() > 1
                && sequence.nextFrames().getFirst() != 0;
    }

    private static boolean areFactorsAnimated(AuthoredMaterialFactors factors) {
        return factors != null && factors.steps().stream().anyMatch(s -> isFadeAnimated(s.animation()));
    }

    private static boolean isFadeAnimated(UColorFade fade) {
        return fade != null && fade.isAnimated();
    }

    private static boolean isFadeAnimated(FadeColorSpec fade) {
        return fade != null
                && (fade.fadeType() == 0 || fade.fadeType() == 1)
                && fade.period() != 0
                && Float.isFinite(fade.period())
                && Float.isFinite(fade.phase());
    }

    private static boolean areCoordinatesAnimated(List<Modifier> modifiers) {
        return modifiers.stream().anyMatch(modifier -> switch (modifier) {
            case Scaler ignored -> false;
            case Panner p -> p.rate() != 0;
            case Oscillator o -> isAxisAnimated(o.u()) || isAxisAnimated(o.v());
            case Rotator r ->
                switch (r.type()) {
                    case 1 ->
                        r.rotation().pitch() != 0
                                || r.rotation().yaw() != 0
                                || r.rotation().roll() != 0;
                    case 2 ->
                        varies(r.rate().pitch(), r.amplitude().pitch())
                                || varies(r.rate().yaw(), r.amplitude().yaw())
                                || varies(r.rate().roll(), r.amplitude().roll());
                    default -> false;
                };
        });
    }

    private static boolean isAxisAnimated(Axis axis) {
        return axis.type() >= 0 && axis.type() <= 3 && varies(axis.rate(), axis.amplitude());
    }

    private static boolean varies(float rate, float amplitude) {
        return rate != 0 && amplitude != 0;
    }
}

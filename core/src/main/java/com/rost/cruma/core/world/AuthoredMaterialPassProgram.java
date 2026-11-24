package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.UColorFade;
import com.rost.cruma.core.world.AuthoredMaterialPasses.Sample;
import com.rost.cruma.core.world.AuthoredMaterialProgram.Compilation;
import com.rost.cruma.core.world.AuthoredMaterialProgram.CoordinateSource;
import com.rost.cruma.core.world.TextureCoordinates.Modifier;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record AuthoredMaterialPassProgram(
        AuthoredMaterialPasses passes,
        int textureFactor,
        @Nullable UColorFade animatedFactor,
        boolean colorModifier,
        int colorModifierFactor,
        List<Modifier> factorCoordinates,
        Compilation compilation,
        List<CoordinateSource> coordinateSources,
        List<Factor> compiledFactors,
        @Nullable AuthoredMaterialFactors factorVisitor,
        float specularPower,
        float specularScale,
        ShaderMaps shaderMaps) {
    public AuthoredMaterialPassProgram(
            AuthoredMaterialPasses passes,
            int textureFactor,
            @Nullable UColorFade animatedFactor,
            boolean colorModifier,
            int colorModifierFactor,
            List<Modifier> factorCoordinates,
            Compilation compilation,
            List<CoordinateSource> coordinateSources,
            List<Factor> compiledFactors,
            @Nullable AuthoredMaterialFactors factorVisitor,
            float specularPower,
            float specularScale) {
        this(
                passes,
                textureFactor,
                animatedFactor,
                colorModifier,
                colorModifierFactor,
                factorCoordinates,
                compilation,
                coordinateSources,
                compiledFactors,
                factorVisitor,
                specularPower,
                specularScale,
                ShaderMaps.EMPTY);
    }

    public AuthoredMaterialPassProgram(
            AuthoredMaterialPasses passes,
            int textureFactor,
            @Nullable UColorFade animatedFactor,
            boolean colorModifier,
            int colorModifierFactor,
            List<Modifier> factorCoordinates,
            Compilation compilation,
            List<CoordinateSource> coordinateSources,
            List<Factor> compiledFactors,
            @Nullable AuthoredMaterialFactors factorVisitor) {
        this(
                passes,
                textureFactor,
                animatedFactor,
                colorModifier,
                colorModifierFactor,
                factorCoordinates,
                compilation,
                coordinateSources,
                compiledFactors,
                factorVisitor,
                15f,
                1f);
    }

    public AuthoredMaterialPassProgram(
            AuthoredMaterialPasses passes,
            int textureFactor,
            @Nullable UColorFade animatedFactor,
            boolean colorModifier,
            int colorModifierFactor,
            List<Modifier> factorCoordinates,
            Compilation compilation,
            List<CoordinateSource> coordinateSources,
            List<Factor> compiledFactors) {
        this(
                passes,
                textureFactor,
                animatedFactor,
                colorModifier,
                colorModifierFactor,
                factorCoordinates,
                compilation,
                coordinateSources,
                compiledFactors,
                null);
    }

    public AuthoredMaterialPassProgram {
        Objects.requireNonNull(passes);
        Objects.requireNonNull(compilation);
        Objects.requireNonNull(shaderMaps);
        factorCoordinates = List.copyOf(factorCoordinates);
        coordinateSources = List.copyOf(coordinateSources);
        compiledFactors = List.copyOf(compiledFactors);
        if (compiledFactors.size() != passes.passes().size()) {
            throw new IllegalArgumentException("material pass factors");
        }
        if (animatedFactor != null && !animatedFactor.isSupported()) {
            throw new IllegalArgumentException("unsupported authored color factor");
        }
    }

    public int textureFactor(float seconds) {
        if (animatedFactor != null) {
            return animatedFactor.argb(seconds);
        }
        return factorVisitor == null ? textureFactor : factorVisitor.at(seconds);
    }

    public List<Sample> boundSamples() {
        return passes.samples().subList(0, Math.min(8, passes.samples().size()));
    }

    public boolean hasCompleteTextureBindings() {
        return passes.samples().size() <= 8;
    }

    public record ShaderMaps(
            @Nullable TextureSequence normal, @Nullable TextureSequence specular, float bumpScale, float bumpBias) {
        public static final ShaderMaps EMPTY = new ShaderMaps(null, null, 0f, 0f);
    }

    public record Factor(int color, @Nullable UColorFade animation) {
        public int at(float seconds) {
            return animation == null ? color : animation.argb(seconds);
        }
    }
}

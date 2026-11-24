package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.UColorFade;
import com.rost.cruma.core.world.TextureCoordinates.Modifier;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record AuthoredMaterialProgram(
        List<Stage> stages,
        int textureFactor,
        @Nullable UColorFade animatedFactor,
        boolean colorModifier,
        int colorModifierFactor,
        List<Modifier> factorCoordinates,
        Compilation compilation,
        List<CoordinateSource> coordinateSources,
        @Nullable AuthoredMaterialFactors factorVisitor) {
    public static final int MAX_STAGES = 8;

    public AuthoredMaterialProgram(List<Stage> stages) {
        this(stages, 0);
    }

    public AuthoredMaterialProgram(List<Stage> stages, int textureFactor) {
        this(stages, textureFactor, null, false);
    }

    public AuthoredMaterialProgram(List<Stage> stages, int textureFactor, @Nullable UColorFade animatedFactor) {
        this(stages, textureFactor, animatedFactor, false);
    }

    public AuthoredMaterialProgram(
            List<Stage> stages, int textureFactor, @Nullable UColorFade animatedFactor, boolean colorModifier) {
        this(stages, textureFactor, animatedFactor, colorModifier, colorModifier ? textureFactor : 0xffffffff);
    }

    public AuthoredMaterialProgram(
            List<Stage> stages,
            int textureFactor,
            @Nullable UColorFade animatedFactor,
            boolean colorModifier,
            int colorModifierFactor) {
        this(
                stages,
                textureFactor,
                animatedFactor,
                colorModifier,
                colorModifierFactor,
                List.of(),
                new Compilation(List.of()));
    }

    public AuthoredMaterialProgram(
            List<Stage> stages,
            int textureFactor,
            @Nullable UColorFade animatedFactor,
            boolean colorModifier,
            int colorModifierFactor,
            List<Modifier> factorCoordinates,
            Compilation compilation) {
        this(
                stages,
                textureFactor,
                animatedFactor,
                colorModifier,
                colorModifierFactor,
                factorCoordinates,
                compilation,
                List.of());
    }

    public AuthoredMaterialProgram(
            List<Stage> stages,
            int textureFactor,
            @Nullable UColorFade animatedFactor,
            boolean colorModifier,
            int colorModifierFactor,
            List<Modifier> factorCoordinates,
            Compilation compilation,
            List<CoordinateSource> coordinateSources) {
        this(
                stages,
                textureFactor,
                animatedFactor,
                colorModifier,
                colorModifierFactor,
                factorCoordinates,
                compilation,
                coordinateSources,
                null);
    }

    public AuthoredMaterialProgram {
        stages = List.copyOf(stages);
        factorCoordinates = List.copyOf(factorCoordinates);
        coordinateSources = List.copyOf(coordinateSources);
        Objects.requireNonNull(compilation);
        if (stages.isEmpty() || stages.size() > MAX_STAGES) {
            throw new IllegalArgumentException("invalid authored material stage count");
        }
        if (animatedFactor != null && !animatedFactor.isSupported()) {
            throw new IllegalArgumentException("unsupported authored color factor");
        }
        if (colorModifier) {
            var last = stages.getLast();
            if (last.bitmap() != null
                    || last.colorOperation() != 4
                    || last.colorArgument1() != 1
                    || last.colorArgument2() != 3
                    || last.alphaOperation() != 4
                    || last.alphaArgument1() != 1
                    || last.alphaArgument2() != 3) {
                throw new IllegalArgumentException("color modifier requires a final factor modulation");
            }
        }
    }

    public int textureFactor(float time) {
        if (animatedFactor != null) {
            return animatedFactor.argb(time);
        }
        return factorVisitor == null ? textureFactor : factorVisitor.at(time);
    }

    public record Compilation(
            List<Modifier> coordinates, @Nullable String sourceIdentity) {
        public Compilation(List<Modifier> coordinates) {
            this(coordinates, null);
        }

        public Compilation {
            coordinates = List.copyOf(coordinates);
        }
    }

    public record CoordinateSource(Modifier modifier, String sourceIdentity) {
        public CoordinateSource {
            Objects.requireNonNull(modifier);
            Objects.requireNonNull(sourceIdentity);
        }
    }

    public record Stage(
            @Nullable TextureSequence bitmap,
            TextureCoordinates coordinates,
            int colorOperation,
            int colorArgument1,
            int colorArgument2,
            int alphaOperation,
            int alphaArgument1,
            int alphaArgument2) {}
}

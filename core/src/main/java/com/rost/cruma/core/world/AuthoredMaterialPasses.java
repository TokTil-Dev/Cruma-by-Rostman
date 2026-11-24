package com.rost.cruma.core.world;

import com.rost.cruma.core.world.AuthoredFramebuffer.Factor;
import com.rost.cruma.core.world.AuthoredFramebuffer.Factors;
import com.rost.cruma.core.world.AuthoredMaterialProgram.Stage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record AuthoredMaterialPasses(List<Pass> passes, List<Sample> samples, boolean separateDraws) {
    public AuthoredMaterialPasses(List<Pass> passes, List<Sample> samples) {
        this(passes, samples, false);
    }

    public AuthoredMaterialPasses {
        passes = List.copyOf(passes);
        samples = List.copyOf(samples);
        if (passes.isEmpty()) {
            throw new IllegalArgumentException("material requires a pass");
        }
        for (var pass : passes) {
            for (var instruction : pass.instructions()) {
                if (instruction.sample() < -1 || instruction.sample() >= samples.size()) {
                    throw new IllegalArgumentException("invalid material sample index");
                }
                if (instruction.operation().samplesTexture() != (instruction.sample() >= 0)) {
                    throw new IllegalArgumentException("material texture argument has no matching sample");
                }
            }
        }
    }

    public float[] evaluate(float[] diffuse, float[] factor, List<float[]> texels, float modulateFactor) {
        if (drawCount() != 1) {
            throw new IllegalStateException("material requires a draw index");
        }
        return evaluate(diffuse, factor, texels, modulateFactor, 0);
    }

    public int drawCount() {
        return separateDraws ? passes.size() : 1;
    }

    public boolean hasReflectionCoordinates() {
        for (var sample : samples) {
            if (sample.coordinates().source() != TextureCoordinates.Source.MESH_UV) {
                return true;
            }
        }
        return false;
    }

    public boolean isDrawDefined(int draw) {
        Objects.checkIndex(draw, drawCount());
        return !separateDraws || draw == 0 || !passes.get(draw).instructions().isEmpty();
    }

    public float[] evaluate(float[] diffuse, float[] factor, List<float[]> texels, float modulateFactor, int draw) {
        return evaluateResult(diffuse, factor, texels, modulateFactor, draw).color();
    }

    public Result evaluateResult(
            float[] diffuse, float[] factor, List<float[]> texels, float modulateFactor, int draw) {
        if (!isDrawDefined(draw)) {
            throw new UnsupportedOperationException("undefined native pass result");
        }
        if (diffuse.length != 4 || factor.length != 4 || texels.size() != samples.size()) {
            throw new IllegalArgumentException("invalid material expression inputs");
        }
        for (var texel : texels) {
            if (texel.length != 4) {
                throw new IllegalArgumentException("invalid material texel");
            }
        }
        float[] accumulated = null;
        var specularMask = 0f;
        for (var index = 0; index < passes.size(); index++) {
            if (separateDraws && index != draw) {
                continue;
            }
            var pass = passes.get(index);
            if (pass.instructions().isEmpty()) {
                continue;
            }
            var current = diffuse.clone();
            for (var instruction : pass.instructions()) {
                var operation = instruction.operation();
                if (operation.result() != 1) {
                    throw new UnsupportedOperationException("material result register");
                }
                var texel = instruction.sample() < 0 ? new float[4] : texels.get(instruction.sample());
                var a = operation.color() == 3
                        ? new float[4]
                        : selectArgument(operation.color1(), current, texel, diffuse, factor);
                var b = operation.color() == 2
                        ? new float[4]
                        : selectArgument(operation.color2(), current, texel, diffuse, factor);
                var c = operation.color() == 25 || operation.color() == 26
                        ? selectArgument(operation.color0(), current, texel, diffuse, factor)
                        : new float[4];
                var aa = operation.alpha() == 3
                        ? 0f
                        : selectArgument(operation.alpha1(), current, texel, diffuse, factor)[3];
                var ab = operation.alpha() == 2
                        ? 0f
                        : selectArgument(operation.alpha2(), current, texel, diffuse, factor)[3];
                var result = new float[4];
                for (var channel = 0; channel < 3; channel++) {
                    result[channel] = switch (operation.color()) {
                        case 2 -> a[channel];
                        case 3 -> b[channel];
                        case 4 -> a[channel] * b[channel] * (operation.usesModulateFactor() ? modulateFactor : 1f);
                        case 5 -> a[channel] * b[channel] * 2f;
                        case 6 -> a[channel] * b[channel] * 4f;
                        case 7 -> a[channel] + b[channel];
                        case 10 -> a[channel] - b[channel];
                        case 12 -> mix(b[channel], a[channel], diffuse[3]);
                        case 13 -> mix(b[channel], a[channel], texel[3]);
                        case 14 -> mix(b[channel], a[channel], factor[3]);
                        case 16 -> mix(b[channel], a[channel], current[3]);
                        case 18 -> a[channel] + a[3] * b[channel];
                        case 25 -> c[channel] + a[channel] * b[channel];
                        case 26 -> c[channel] * a[channel] + (1f - c[channel]) * b[channel];
                        default ->
                            throw new UnsupportedOperationException("material color operation " + operation.color());
                    };
                }
                result[3] = switch (operation.alpha()) {
                    case 2 -> aa;
                    case 3 -> ab;
                    case 4 -> aa * ab;
                    case 7 -> aa + ab;
                    default -> throw new UnsupportedOperationException("material alpha operation " + operation.alpha());
                };
                current = result;
                if (operation.captureAlpha()) {
                    specularMask = current[3];
                } else if (operation.captureFactorAlpha()) {
                    specularMask = factor[3];
                }
            }
            if (separateDraws || accumulated == null || pass.sourceBlend() == 2 && pass.destinationBlend() == 1) {
                accumulated = current;
            } else if (pass.sourceBlend() == 5 && pass.destinationBlend() == 6) {
                var alpha = current[3];
                for (var channel = 0; channel < 4; channel++) {
                    accumulated[channel] = mix(accumulated[channel], current[channel], alpha);
                }
            } else if (pass.destinationBlend() == 2 && (pass.sourceBlend() == 2 || pass.sourceBlend() == 5)) {
                var amount = pass.sourceBlend() == 2 ? 1f : current[3];
                for (var channel = 0; channel < 4; channel++) {
                    accumulated[channel] = current[channel] * amount + accumulated[channel];
                }
            } else {
                throw new UnsupportedOperationException("material pass blend");
            }
        }
        return new Result(accumulated == null ? diffuse.clone() : accumulated, specularMask);
    }

    public record Result(float[] color, float specularMask) {}

    private static float mix(float first, float second, float amount) {
        return first * (1f - amount) + second * amount;
    }

    private static float[] selectArgument(int flags, float[] current, float[] bitmap, float[] diffuse, float[] factor) {
        var source = switch (flags & 15) {
            case 0 -> diffuse;
            case 1 -> current;
            case 2 -> bitmap;
            case 3 -> factor;
            case 5 -> new float[4];
            default -> throw new UnsupportedOperationException("material argument " + flags);
        };
        var result = new float[4];
        for (var channel = 0; channel < 4; channel++) {
            var value = source[(flags & 32) != 0 ? 3 : channel];
            result[channel] = (flags & 16) != 0 ? 1f - value : value;
        }
        return result;
    }

    public record Pass(
            List<Instruction> instructions,
            int sourceBlend,
            int destinationBlend,
            @Nullable AuthoredFramebuffer framebuffer) {
        public Pass(List<Instruction> instructions, int sourceBlend, int destinationBlend) {
            this(instructions, sourceBlend, destinationBlend, null);
        }

        public Pass {
            instructions = List.copyOf(instructions);
        }
    }

    public record Sample(TextureSequence bitmap, TextureCoordinates coordinates) {
        public Sample {
            Objects.requireNonNull(bitmap);
            Objects.requireNonNull(coordinates);
        }
    }

    public record Instruction(Operation operation, int sample) {
        public Instruction {
            Objects.requireNonNull(operation);
        }
    }

    public record Operation(
            int color,
            int alpha,
            int color0,
            int color1,
            int color2,
            int alpha0,
            int alpha1,
            int alpha2,
            int result,
            int factorGate,
            boolean captureAlpha,
            boolean captureFactorAlpha) {
        public Operation(
                int color,
                int alpha,
                int color0,
                int color1,
                int color2,
                int alpha0,
                int alpha1,
                int alpha2,
                int result,
                int factorGate) {
            this(color, alpha, color0, color1, color2, alpha0, alpha1, alpha2, result, factorGate, false, false);
        }

        public boolean samplesTexture() {
            return samplesTexture(color, color0, color1, color2) || samplesTexture(alpha, alpha0, alpha1, alpha2);
        }

        public boolean usesModulateFactor() {
            return color == 4 && factorGate == 0;
        }

        private static boolean samplesTexture(int operation, int argument0, int argument1, int argument2) {
            if (operation == 1) {
                return false;
            }
            if (operation == 2) {
                return (argument1 & 15) == 2;
            }
            if (operation == 3) {
                return (argument2 & 15) == 2;
            }
            return (argument1 & 15) == 2
                    || (argument2 & 15) == 2
                    || (operation == 25 || operation == 26) && (argument0 & 15) == 2;
        }
    }

    public static final class Builder {
        private static final Operation CURRENT = new Operation(2, 2, 1, 1, 1, 1, 1, 1, 1, 0);
        private final int capacity;
        private final List<Storage> storage = new ArrayList<>();
        private int pass;
        private int index;
        private int sourceBlend = 2;
        private int destinationBlend = 1;
        private boolean separateDraws;

        public Builder(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("invalid material pass capacity");
            }
            this.capacity = capacity;
            storage.add(new Storage(capacity));
        }

        public void write(@Nullable TextureSequence bitmap, TextureCoordinates coordinates, Operation operation) {
            write(new Slot(bitmap, coordinates, operation), pass);
        }

        public void setFramebuffer(int source, int destination, boolean blending) {
            sourceBlend = source;
            destinationBlend = destination;
            separateDraws = blending;
        }

        public int getCurrentPass() {
            return pass;
        }

        public int getRemainingStages() {
            return capacity - index;
        }

        public void captureSpecularMask(boolean fromFactor) {
            if (index == 0) {
                throw new IllegalStateException("specular capture requires a preceding stage");
            }
            var previous = storage.get(pass).slots[index - 1];
            var operation = previous.operation();
            storage.get(pass).slots[index - 1] = new Slot(
                    previous.bitmap(),
                    previous.coordinates(),
                    new Operation(
                            operation.color(),
                            operation.alpha(),
                            operation.color0(),
                            operation.color1(),
                            operation.color2(),
                            operation.alpha0(),
                            operation.alpha1(),
                            operation.alpha2(),
                            operation.result(),
                            operation.factorGate(),
                            !fromFactor,
                            fromFactor));
        }

        public void startSpecularPass(boolean masked, boolean twoSided) {
            storage.get(pass).count = index;
            pass++;
            index = 0;
            var next = new Storage(capacity);
            next.framebuffer = new AuthoredFramebuffer(
                    true,
                    new Factors(masked ? Factor.SOURCE_ALPHA : Factor.ONE, Factor.ONE),
                    null,
                    true,
                    false,
                    -1,
                    twoSided,
                    0);
            storage.add(next);
        }

        public void rollOverIfFull() {
            if (index < capacity) {
                return;
            }
            storage.get(pass).count = index;
            pass++;
            index = 0;
            storage.add(new Storage(capacity));
        }

        public void addColorStage(Stage stage, int factorGate) {
            write(
                    stage.bitmap(),
                    stage.coordinates(),
                    new Operation(
                            stage.colorOperation(),
                            stage.alphaOperation(),
                            1,
                            stage.colorArgument1(),
                            stage.colorArgument2(),
                            1,
                            stage.alphaArgument1(),
                            stage.alphaArgument2(),
                            1,
                            factorGate));
            rollOverIfFull();
        }

        public void addLighting(boolean shader) {
            addLighting(shader, false);
        }

        public void addLighting(boolean shader, boolean opacity) {
            if (index == 1) {
                var previous = storage.get(pass).slots[0];
                var operation = previous.operation();
                storage.get(pass).slots[0] = new Slot(
                        previous.bitmap(),
                        previous.coordinates(),
                        new Operation(
                                4,
                                4,
                                operation.color0(),
                                operation.color1(),
                                0,
                                operation.alpha0(),
                                operation.alpha1(),
                                0,
                                operation.result(),
                                operation.factorGate(),
                                operation.captureAlpha(),
                                operation.captureFactorAlpha()));
            } else if (index != 0 || !shader || opacity) {
                write(null, TextureCoordinates.IDENTITY, new Operation(4, 3, 1, 0, 1, 1, 1, 1, 1, 0));
                if (shader) {
                    rollOverIfFull();
                }
            }
        }

        public void addOpaqueAlpha() {
            if (pass != 0) {
                return;
            }
            write(null, TextureCoordinates.IDENTITY, new Operation(2, 2, 1, 1, 1, 1, 21, 1, 1, 0));
            rollOverIfFull();
        }

        public void addInternalColor() {
            write(new Slot(null, TextureCoordinates.IDENTITY, new Operation(4, 4, 1, 1, 3, 1, 1, 3, 1, 0)), 0);
            rollOverIfFull();
        }

        public AuthoredMaterialPasses build() {
            storage.get(pass).count = index;
            var passes = new ArrayList<Pass>();
            var samples = new ArrayList<Sample>();
            for (var source : storage) {
                var instructions = new ArrayList<Instruction>();
                for (var i = 0; i < source.count; i++) {
                    var slot = source.slots[i];
                    var sample = -1;
                    if (slot.operation().samplesTexture()) {
                        if (slot.bitmap() == null) {
                            throw new IllegalStateException("missing material texture");
                        }
                        sample = samples.size();
                        samples.add(new Sample(slot.bitmap(), slot.coordinates()));
                    }
                    instructions.add(new Instruction(slot.operation(), sample));
                }
                var framebuffer = source.framebuffer;
                passes.add(new Pass(
                        instructions,
                        framebuffer == null
                                ? passes.isEmpty() ? sourceBlend : 5
                                : framebuffer.color().source() == Factor.ONE ? 2 : 5,
                        framebuffer == null ? passes.isEmpty() ? destinationBlend : 6 : 2,
                        framebuffer));
            }
            return new AuthoredMaterialPasses(passes, samples, separateDraws);
        }

        private void write(Slot slot, int targetPass) {
            if (index >= capacity) {
                throw new IllegalStateException("material stage requires rollover");
            }
            storage.get(targetPass).slots[index++] = slot;
        }

        private record Slot(@Nullable TextureSequence bitmap, TextureCoordinates coordinates, Operation operation) {
            private Slot {
                Objects.requireNonNull(coordinates);
                Objects.requireNonNull(operation);
            }
        }

        private static final class Storage {
            private final Slot[] slots;
            private int count;
            private @Nullable AuthoredFramebuffer framebuffer;

            private Storage(int capacity) {
                slots = new Slot[capacity];
                Arrays.fill(slots, new Slot(null, TextureCoordinates.IDENTITY, CURRENT));
            }
        }
    }
}

package com.rost.cruma.core.editing;

import java.util.Objects;

final class ShadowColorBlend {
    static final State OPAQUE = new State(
            false, Factor.ONE, Factor.ZERO, Operation.ADD, false, Factor.ONE, Factor.ZERO, Operation.ADD, -1, 15);

    private ShadowColorBlend() {}

    enum Factor {
        ZERO(1),
        ONE(2),
        SOURCE_COLOR(3),
        INVERSE_SOURCE_COLOR(4),
        SOURCE_ALPHA(5),
        INVERSE_SOURCE_ALPHA(6),
        DESTINATION_ALPHA(7),
        INVERSE_DESTINATION_ALPHA(8),
        DESTINATION_COLOR(9),
        INVERSE_DESTINATION_COLOR(10),
        SOURCE_ALPHA_SATURATE(11),
        CONSTANT(14),
        INVERSE_CONSTANT(15);

        private final int nativeValue;

        Factor(int nativeValue) {
            this.nativeValue = nativeValue;
        }

        static Factor fromNative(int value) {
            for (var factor : values()) {
                if (factor.nativeValue == value) {
                    return factor;
                }
            }
            throw new IllegalArgumentException("Unsupported shadow blend factor: " + value);
        }
    }

    enum Operation {
        ADD,
        SUBTRACT,
        REVERSE_SUBTRACT,
        MINIMUM,
        MAXIMUM;

        static Operation fromNative(int value) {
            if (value < 1 || value > values().length) {
                throw new IllegalArgumentException("Unsupported shadow blend operation: " + value);
            }
            return values()[value - 1];
        }
    }

    record State(
            boolean enabled,
            Factor source,
            Factor destination,
            Operation operation,
            boolean separateAlpha,
            Factor alphaSource,
            Factor alphaDestination,
            Operation alphaOperation,
            int constantArgb,
            int writeMask) {
        State {
            Objects.requireNonNull(source);
            Objects.requireNonNull(destination);
            Objects.requireNonNull(operation);
            Objects.requireNonNull(alphaSource);
            Objects.requireNonNull(alphaDestination);
            Objects.requireNonNull(alphaOperation);
            if (writeMask < 0 || writeMask > 15) {
                throw new IllegalArgumentException("Invalid shadow color write mask");
            }
        }
    }

    static int apply(int source, int destination, State state) {
        Objects.requireNonNull(state);
        int result = destination;
        for (int channel = 0; channel < 4; channel++) {
            int writeBit = channel == 3 ? 8 : 1 << (2 - channel);
            if ((state.writeMask & writeBit) == 0) {
                continue;
            }
            int shift = channel * 8, s = source >>> shift & 255, d = destination >>> shift & 255;
            int value = s;
            if (state.enabled) {
                boolean alpha = channel == 3 && state.separateAlpha;
                var operation = alpha ? state.alphaOperation : state.operation;
                if (operation == Operation.MINIMUM || operation == Operation.MAXIMUM) {
                    value = operation == Operation.MINIMUM ? Math.min(s, d) : Math.max(s, d);
                } else {
                    int a = s
                            * computeFactor(
                                    alpha ? state.alphaSource : state.source,
                                    source,
                                    destination,
                                    state.constantArgb,
                                    shift);
                    int b = d
                            * computeFactor(
                                    alpha ? state.alphaDestination : state.destination,
                                    source,
                                    destination,
                                    state.constantArgb,
                                    shift);
                    int scaled = switch (operation) {
                        case ADD -> a + b;
                        case SUBTRACT -> a - b;
                        case REVERSE_SUBTRACT -> b - a;
                        default -> throw new AssertionError(operation);
                    };
                    value = Math.max(0, Math.min(255, (scaled + 127) / 255));
                }
            }
            result = result & ~(255 << shift) | value << shift;
        }
        return result;
    }

    private static int computeFactor(Factor factor, int source, int destination, int constant, int shift) {
        return switch (factor) {
            case ZERO -> 0;
            case ONE -> 255;
            case SOURCE_COLOR -> source >>> shift & 255;
            case INVERSE_SOURCE_COLOR -> 255 - (source >>> shift & 255);
            case SOURCE_ALPHA -> source >>> 24;
            case INVERSE_SOURCE_ALPHA -> 255 - (source >>> 24);
            case DESTINATION_ALPHA -> destination >>> 24;
            case INVERSE_DESTINATION_ALPHA -> 255 - (destination >>> 24);
            case DESTINATION_COLOR -> destination >>> shift & 255;
            case INVERSE_DESTINATION_COLOR -> 255 - (destination >>> shift & 255);
            case SOURCE_ALPHA_SATURATE -> shift == 24 ? 255 : Math.min(source >>> 24, 255 - (destination >>> 24));
            case CONSTANT -> constant >>> shift & 255;
            case INVERSE_CONSTANT -> 255 - (constant >>> shift & 255);
        };
    }
}

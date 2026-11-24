package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152BspReceiverModel.Receiver;
import com.rost.cruma.core.editing.ShadowMaskRasterizer.Cull;
import com.rost.cruma.core.editing.ShadowMaskRasterizer.WorldDraw;
import com.rost.cruma.core.editing.ShadowMaskReadback.SunSamples;
import com.rost.cruma.core.world.NativeBspShadowProjection;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import com.rost.cruma.core.world.NativeBspShadowProjection.Volume;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class P152BspSunVisibilityReplay {
    private static final int MAX_SLOTS = 64;

    private P152BspSunVisibilityReplay() {}

    @FunctionalInterface
    interface BoundsFilter {
        Set<Integer> findExcluded(Volume volume, BooleanSupplier cancelled);
    }

    @FunctionalInterface
    private interface ProjectedMask {
        byte[] render(int width, int height, float[] matrix, Set<Integer> exclusions, BooleanSupplier cancelled);
    }

    static final class Mask {
        private final int slot;
        private final byte[] bytes;
        private final boolean nonzero;

        private Mask(int slot, byte[] generated) {
            this.slot = slot;
            bytes = generated.clone();
            boolean any = false;
            for (byte value : bytes) {
                any |= value != 0;
            }
            nonzero = any;
        }

        int getSlot() {
            return slot;
        }

        byte[] getBytes() {
            return bytes.clone();
        }

        boolean isNonzero() {
            return nonzero;
        }
    }

    record MaskComparison(
            int ordinal, int slot, int differingBytes, int differingBits, int addedShadows, int missingShadows) {
        boolean exact() {
            return differingBytes == 0;
        }
    }

    record Comparison(int generatedRecords, int savedRecords, List<MaskComparison> masks) {
        Comparison {
            masks = List.copyOf(masks);
        }

        boolean aligned() {
            return generatedRecords == savedRecords;
        }

        boolean exact() {
            if (!aligned()) {
                return false;
            }
            for (var mask : masks) {
                if (!mask.exact()) {
                    return false;
                }
            }
            return true;
        }
    }

    static final class Batch {
        private final int byteCount;
        private final List<Mask> slots, retained;

        private Batch(int byteCount, List<Mask> slots) {
            this.byteCount = byteCount;
            this.slots = List.copyOf(slots);
            var nonempty = new ArrayList<Mask>();
            for (var mask : slots) {
                if (mask.isNonzero()) {
                    nonempty.add(mask);
                }
            }
            retained = List.copyOf(nonempty);
        }

        List<Mask> getSlots() {
            return slots;
        }

        List<Mask> getRetained() {
            return retained;
        }

        Comparison compare(List<byte[]> expected, BooleanSupplier cancelled) {
            Objects.requireNonNull(cancelled);
            if (expected.size() > MAX_SLOTS) {
                throw new IllegalArgumentException("Saved sunlight batch exceeds bounded slots");
            }
            var frozen = new ArrayList<byte[]>();
            for (var mask : expected) {
                if (mask.length != byteCount) {
                    throw new IllegalArgumentException("Saved sunlight mask dimensions differ");
                }
                frozen.add(mask.clone());
            }
            P152FreshHashQuery.checkCancelled(cancelled);
            if (frozen.size() != retained.size()) {
                return new Comparison(retained.size(), frozen.size(), List.of());
            }
            var comparisons = new ArrayList<MaskComparison>();
            for (int ordinal = 0; ordinal < retained.size(); ordinal++) {
                var mask = retained.get(ordinal);
                var saved = frozen.get(ordinal);
                int differingBytes = 0, differingBits = 0, added = 0, missing = 0;
                for (int i = 0; i < byteCount; i++) {
                    P152FreshHashQuery.checkCancelled(cancelled);
                    int difference = (saved[i] ^ mask.bytes[i]) & 255;
                    differingBytes += difference == 0 ? 0 : 1;
                    differingBits += Integer.bitCount(difference);
                    added += Integer.bitCount(saved[i] & ~mask.bytes[i] & 255);
                    missing += Integer.bitCount(mask.bytes[i] & ~saved[i] & 255);
                }
                comparisons.add(new MaskComparison(ordinal, mask.slot, differingBytes, differingBits, added, missing));
            }
            P152FreshHashQuery.checkCancelled(cancelled);
            return new Comparison(retained.size(), frozen.size(), comparisons);
        }
    }

    static Batch generate(
            Receiver receiver,
            Vector surfaceNormal,
            List<Vector> towardLights,
            ShadowMaskRasterizer rasterizer,
            Cull cull,
            BoundsFilter bounds,
            NativeBspRegions regions,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(rasterizer);
        Objects.requireNonNull(cull);
        return generate(
                receiver,
                surfaceNormal,
                towardLights,
                (width, height, matrix, exclusions, stop) ->
                        rasterizer.render(width, height, matrix, cull, exclusions, stop),
                bounds,
                regions,
                cancelled);
    }

    static Batch generateColored(
            Receiver receiver,
            Vector surfaceNormal,
            List<Vector> towardLights,
            List<WorldDraw> draws,
            BoundsFilter bounds,
            NativeBspRegions regions,
            BooleanSupplier cancelled) {
        if (draws.size() > 1_000_000) {
            throw new IllegalArgumentException("Sunlight color draws exceed editor bounds");
        }
        var frozen = List.copyOf(draws);
        return generate(
                receiver,
                surfaceNormal,
                towardLights,
                (width, height, matrix, exclusions, stop) -> ShadowMaskRasterizer.renderWorldDraws(
                                width, height, frozen, matrix, exclusions, stop)
                        .visibleBytes(),
                bounds,
                regions,
                cancelled);
    }

    private static Batch generate(
            Receiver receiver,
            Vector surfaceNormal,
            List<Vector> towardLights,
            ProjectedMask renderer,
            BoundsFilter bounds,
            NativeBspRegions regions,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(bounds);
        Objects.requireNonNull(regions);
        Objects.requireNonNull(cancelled);
        var directions = List.copyOf(towardLights);
        int width = receiver.projection().getWidth(),
                height = receiver.projection().getHeight();
        if (directions.isEmpty() || directions.size() > MAX_SLOTS || (long) width * height > 65536) {
            throw new IllegalArgumentException("Sunlight replay exceeds bounded slots or pixels");
        }
        var samples = new SunSamples(
                0,
                0,
                toVector(surfaceNormal),
                toVector(receiver.savedOrigin()),
                toVector(receiver.savedAxisU()),
                toVector(receiver.savedAxisV()));
        var slots = new ArrayList<Mask>();
        P152FreshHashQuery.checkCancelled(cancelled);
        for (int slot = 0; slot < directions.size(); slot++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var projection = NativeBspShadowProjection.sun(receiver.projection(), directions.get(slot));
            var exclusions = bounds.findExcluded(projection.getVolume(), cancelled);
            var raw = renderer.render(width, height, projection.getWorldToScreen(), exclusions, cancelled);
            var visible = ShadowMaskReadback.retainSunRegions(width, height, raw, samples, regions, cancelled);
            slots.add(new Mask(slot, visible));
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Batch((width + 7) / 8 * height, slots);
    }

    private static TerrainNativeCodec.Vector toVector(Vector vector) {
        return new TerrainNativeCodec.Vector(vector.x(), vector.y(), vector.z());
    }
}

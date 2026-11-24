package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152BspReceiverModel.TextureCapture;
import com.rost.cruma.core.editing.P152BspReceiverModel.TextureStorage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152BspLightmapCodec {
    private static final int COPY_CHUNK = 64 * 1024;

    private P152BspLightmapCodec() {}

    record Key(int atlas, int variant) {}

    record Texture(
            Key key,
            int format,
            int width,
            int height,
            int revision,
            int staticRevision,
            int fullBytes,
            int halfBytes) {
        String replacementRestriction() {
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
                return "Texture replacement requires a populated bounded atlas";
            }
            var blockBytes = switch (format) {
                case 3 -> 8;
                case 7, 8 -> 16;
                default -> 0;
            };
            if (blockBytes == 0) {
                return "Only existing DXT1, DXT3 and DXT5 atlas storage is supported";
            }
            if (revision != staticRevision) {
                return "Stale static lightmap revisions require a qualified native rebuild";
            }
            if (fullBytes != compressedBytes(width, height, blockBytes)
                    || halfBytes != 0
                            && halfBytes
                                    != compressedBytes(Math.max(1, width / 2), Math.max(1, height / 2), blockBytes)) {
                return "Saved lightmap byte counts do not match their compressed extents";
            }
            return "";
        }

        private static long compressedBytes(int width, int height, int blockBytes) {
            return ((long) width + 3) / 4 * (((long) height + 3) / 4) * blockBytes;
        }
    }

    record Replacement(Key key, byte[] full, byte[] half) {
        Replacement {
            Objects.requireNonNull(key);
            full = full.clone();
            half = half.clone();
        }

        @Override
        public byte[] full() {
            return full.clone();
        }

        @Override
        public byte[] half() {
            return half.clone();
        }
    }

    static final class Snapshot {
        private final String packageName, sourceBodySha256;
        private final int fileVersion, licenseVersion, modelReference, modelOffset;
        private final boolean beastLighting;
        private final byte[] payload;
        private final List<TextureStorage> storage;
        private final List<Texture> textures;
        private final Map<Key, Integer> textureIndices;

        private Snapshot(PackageBytes source, int modelReference, TextureCapture capture, BooleanSupplier cancelled) {
            this.packageName = source.packageName;
            this.sourceBodySha256 = P152CaptureIo.hash(source.body, cancelled);
            this.fileVersion = source.fileVersion;
            this.licenseVersion = source.licenseVersion;
            this.modelReference = modelReference;
            this.beastLighting = capture.model().isBeastLighting();
            var entry = source.exports.get(modelReference - 1);
            this.modelOffset = entry.serialOffset();
            this.payload = new byte[entry.serialSize()];
            copy(source.body, modelOffset, payload, 0, payload.length, cancelled);
            this.storage = capture.textures();
            var descriptions = new ArrayList<Texture>(storage.size());
            var indices = new HashMap<Key, Integer>();
            for (var texture : storage) {
                P152FreshHashQuery.checkCancelled(cancelled);
                indices.put(new Key(texture.atlas(), texture.variant()), descriptions.size());
                descriptions.add(new Texture(
                        new Key(texture.atlas(), texture.variant()),
                        texture.format(),
                        texture.width(),
                        texture.height(),
                        texture.revision(),
                        texture.staticRevision(),
                        texture.fullBytes(),
                        texture.halfBytes()));
            }
            this.textures = List.copyOf(descriptions);
            this.textureIndices = Map.copyOf(indices);
        }

        int getModelReference() {
            return modelReference;
        }

        boolean isBeastLighting() {
            return beastLighting;
        }

        List<Texture> getTextures() {
            return textures;
        }

        byte[] getMip(Key key, int mip, BooleanSupplier cancelled) throws IOException {
            P152FreshHashQuery.checkCancelled(cancelled);
            var index = index(key);
            var location = storage.get(index);
            if (mip != 0 && mip != 1) {
                throw new IOException("Native BSP atlas has only full and half resolution storage");
            }
            var offset = mip == 0 ? location.fullOffset() : location.halfOffset();
            var size = mip == 0 ? location.fullBytes() : location.halfBytes();
            var result = new byte[size];
            copy(payload, offset - modelOffset, result, 0, size, cancelled);
            return result;
        }

        byte[] replace(PackageBytes source, List<Replacement> replacements, BooleanSupplier cancelled)
                throws IOException {
            replacements = List.copyOf(replacements);
            requireSource(source, cancelled);
            if (!replacements.isEmpty() && (fileVersion < 128 || licenseVersion < 39)) {
                throw new IOException("BSP texel replacement requires the P152 file128..132/license39..40 profile");
            }
            var seen = new HashSet<Key>();
            for (var replacement : replacements) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var texture = textures.get(index(replacement.key));
                if (!seen.add(replacement.key)) {
                    throw new IOException("Duplicate BSP atlas variant replacement");
                }
                var restriction = texture.replacementRestriction();
                if (!restriction.isEmpty()) {
                    throw new IOException(restriction);
                }
                if (replacement.full.length != texture.fullBytes || replacement.half.length != texture.halfBytes) {
                    throw new IOException("BSP replacement must preserve both stored mip byte counts");
                }
            }
            var result = new byte[payload.length];
            copy(payload, 0, result, 0, payload.length, cancelled);
            for (var replacement : replacements) {
                var location = storage.get(index(replacement.key));
                copy(
                        replacement.full,
                        0,
                        result,
                        location.fullOffset() - modelOffset,
                        replacement.full.length,
                        cancelled);
                copy(
                        replacement.half,
                        0,
                        result,
                        location.halfOffset() - modelOffset,
                        replacement.half.length,
                        cancelled);
            }
            requireSource(source, cancelled);
            return result;
        }

        private void requireSource(PackageBytes source, BooleanSupplier cancelled) throws IOException {
            if (!packageName.equals(source.packageName)
                    || !sourceBodySha256.equals(P152CaptureIo.hash(source.body, cancelled))) {
                throw new IOException("BSP lightmap storage belongs to a different frozen source");
            }
        }

        private int index(Key key) throws IOException {
            Objects.requireNonNull(key);
            var index = textureIndices.get(key);
            if (index != null) {
                return index;
            }
            throw new IOException("Unknown BSP atlas variant " + key);
        }
    }

    static Snapshot capture(PackageBytes source, int modelReference, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        var capture = P152BspReceiverModel.captureTextures(source, modelReference, cancelled);
        var result = new Snapshot(source, modelReference, capture, cancelled);
        capture.model().requireSource(source);
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    private static void copy(
            byte[] source, int sourceOffset, byte[] target, int targetOffset, int count, BooleanSupplier cancelled) {
        for (int offset = 0; offset < count; ) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var chunk = Math.min(COPY_CHUNK, count - offset);
            System.arraycopy(source, sourceOffset + offset, target, targetOffset + offset, chunk);
            offset += chunk;
        }
        P152FreshHashQuery.checkCancelled(cancelled);
    }
}

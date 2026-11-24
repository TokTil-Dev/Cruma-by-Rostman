package com.rost.cruma.core.unreal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class MeshDecorationData {
    private static final Logger log = LogManager.getLogger(MeshDecorationData.class);
    private static final int TYPE_BOOL = 3;
    private static final int TYPE_ARRAY = 9;
    private static final int TYPE_STRUCT = 10;

    private MeshDecorationData() {}

    public record Instances(float[] locations, int[] rotations, float[] scales, int[] colors) {
        public static final Instances EMPTY = new Instances(new float[0], new int[0], new float[0], new int[0]);

        public Instances {
            var count = colors.length;
            if (locations.length != count * 3 || rotations.length != count * 3 || scales.length != count * 3) {
                throw new IllegalArgumentException("Decoration instance arrays do not match");
            }
        }

        public int size() {
            return colors.length;
        }
    }

    public record Places(int[] locations, int[] rotations, int[] scales, int[] quadLocations) {
        public Places {
            if (rotations.length != locations.length || scales.length != locations.length) {
                throw new IllegalArgumentException("Decoration places do not match");
            }
        }

        public int size() {
            return locations.length;
        }
    }

    public static List<Instances> readLayerData(byte[] bytes, int count, IntFunction<String> names) {
        return read(bytes, count, names, false);
    }

    public static List<Instances> readQuadData(byte[] bytes, int count, IntFunction<String> names) {
        return read(bytes, count, names, true);
    }

    public static Places findPlaces(byte[] value, IntFunction<String> names, boolean quads) {
        var buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        var places = new PlaceBuilder();
        try {
            var reader = new Reader(buffer, names, new Builder(), quads, places);
            var count = reader.readIndex();
            for (var layer = 0; layer < count; layer++) {
                reader.readLayer();
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unreadable mesh decorations: " + e.getMessage(), e);
        }
        if (buffer.hasRemaining()) {
            throw new IllegalStateException(
                    "Mesh decorations end " + buffer.remaining() + " bytes before their property does");
        }
        return places.build();
    }

    public static byte[] toBytes(short[] values) {
        var bytes = new byte[values.length];
        for (var index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }

    private static List<Instances> read(byte[] bytes, int count, IntFunction<String> names, boolean quads) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var layers = new ArrayList<Instances>(Math.max(0, count));
        try {
            for (var layer = 0; layer < count; layer++) {
                var builder = new Builder();
                new Reader(buffer, names, builder, quads, null).readLayer();
                layers.add(builder.build());
            }
            if (buffer.hasRemaining()) {
                log.warn("Mesh decorations end {} bytes before their property does", buffer.remaining());
            }
            return List.copyOf(layers);
        } catch (RuntimeException e) {
            log.warn("Unreadable mesh decorations: {}", e.toString());
            return List.of();
        }
    }

    private static final class Builder {
        private float[] locations = new float[48];
        private int[] rotations = new int[48];
        private float[] scales = new float[48];
        private int[] colors = new int[16];
        private int count;

        private void add(float[] location, int[] rotation, float[] scale, int color) {
            if (count == colors.length) {
                locations = Arrays.copyOf(locations, count * 6);
                rotations = Arrays.copyOf(rotations, count * 6);
                scales = Arrays.copyOf(scales, count * 6);
                colors = Arrays.copyOf(colors, count * 2);
            }
            System.arraycopy(location, 0, locations, count * 3, 3);
            System.arraycopy(rotation, 0, rotations, count * 3, 3);
            System.arraycopy(scale, 0, scales, count * 3, 3);
            colors[count++] = color;
        }

        private Instances build() {
            return count == 0
                    ? Instances.EMPTY
                    : new Instances(
                            Arrays.copyOf(locations, count * 3),
                            Arrays.copyOf(rotations, count * 3),
                            Arrays.copyOf(scales, count * 3),
                            Arrays.copyOf(colors, count));
        }
    }

    private static final class PlaceBuilder {
        private int[] locations = new int[16];
        private int[] rotations = new int[16];
        private int[] scales = new int[16];
        private int[] quadLocations = new int[4];
        private int count;
        private int quads;

        private void add(int location, int rotation, int scale) {
            if (count == locations.length) {
                locations = Arrays.copyOf(locations, count * 2);
                rotations = Arrays.copyOf(rotations, count * 2);
                scales = Arrays.copyOf(scales, count * 2);
            }
            locations[count] = location;
            rotations[count] = rotation;
            scales[count++] = scale;
        }

        private void addQuad(int location) {
            if (quads == quadLocations.length) {
                quadLocations = Arrays.copyOf(quadLocations, quads * 2);
            }
            quadLocations[quads++] = location;
        }

        private Places build() {
            return new Places(
                    Arrays.copyOf(locations, count),
                    Arrays.copyOf(rotations, count),
                    Arrays.copyOf(scales, count),
                    Arrays.copyOf(quadLocations, quads));
        }
    }

    private record Tag(String name, int type, int size, int valueStart) {}

    private static final class Reader {
        private final ByteBuffer buffer;
        private final IntFunction<String> names;
        private final Builder builder;
        private final boolean quads;
        private final @Nullable PlaceBuilder places;

        private Reader(
                ByteBuffer buffer,
                IntFunction<String> names,
                Builder builder,
                boolean quads,
                @Nullable PlaceBuilder places) {
            this.buffer = buffer;
            this.names = names;
            this.builder = builder;
            this.quads = quads;
            this.places = places;
        }

        private void readLayer() {
            for (var tag = nextTag(); tag != null; tag = nextTag()) {
                if (tag.type() == TYPE_ARRAY && tag.name().equals(quads ? "DecoQuadInfo" : "DecoInstances")) {
                    var elements = readIndex();
                    for (var element = 0; element < elements; element++) {
                        if (quads) {
                            readQuad();
                        } else {
                            readInstance();
                        }
                    }
                    requireEnd(tag);
                } else {
                    buffer.position(tag.valueStart() + tag.size());
                }
            }
        }

        private void readQuad() {
            var location = -1;
            for (var tag = nextTag(); tag != null; tag = nextTag()) {
                if (tag.name().equals("Location") && isVector(tag)) {
                    location = tag.valueStart();
                }
                if (tag.type() == TYPE_ARRAY && tag.name().equals("DecoInstances")) {
                    var elements = readIndex();
                    for (var element = 0; element < elements; element++) {
                        readInstance();
                    }
                    requireEnd(tag);
                } else {
                    buffer.position(tag.valueStart() + tag.size());
                }
            }
            if (places != null) {
                places.addQuad(location);
            }
        }

        private void readInstance() {
            var location = new float[3];
            var rotation = new int[3];
            var scale = new float[3];
            var color = 0;
            var locationAt = -1;
            var rotationAt = -1;
            var scaleAt = -1;
            for (var tag = nextTag(); tag != null; tag = nextTag()) {
                var start = tag.valueStart();
                switch (tag.name()) {
                    case "Location" -> {
                        readFloats(tag, location);
                        locationAt = isVector(tag) ? start : -1;
                    }
                    case "Scale" -> {
                        readFloats(tag, scale);
                        scaleAt = isVector(tag) ? start : -1;
                    }
                    case "Rotation" -> {
                        if (isVector(tag)) {
                            for (var axis = 0; axis < 3; axis++) {
                                rotation[axis] = buffer.getInt(start + axis * 4);
                            }
                            rotationAt = start;
                        }
                    }
                    case "Color", "DecoColor" -> {
                        if (tag.type() == TYPE_STRUCT && tag.size() == 4) {
                            color = (buffer.get(start + 2) & 0xff) << 16
                                    | (buffer.get(start + 1) & 0xff) << 8
                                    | buffer.get(start) & 0xff;
                        }
                    }
                    default -> {
                    }
                }
                buffer.position(start + tag.size());
            }
            builder.add(location, rotation, scale, color);
            if (places != null) {
                places.add(locationAt, rotationAt, scaleAt);
            }
        }

        private boolean isVector(Tag tag) {
            return tag.type() == TYPE_STRUCT && tag.size() == 12;
        }

        private void readFloats(Tag tag, float[] target) {
            if (isVector(tag)) {
                for (var axis = 0; axis < 3; axis++) {
                    target[axis] = buffer.getFloat(tag.valueStart() + axis * 4);
                }
            }
        }

        private void requireEnd(Tag tag) {
            if (buffer.position() != tag.valueStart() + tag.size()) {
                throw new IllegalStateException(tag.name() + " does not end where its tag says");
            }
        }

        private Tag nextTag() {
            var name = names.apply(readIndex());
            if (name == null || name.equals(UArchive.NONE_NAME)) {
                return null;
            }
            var info = buffer.get() & 0xff;
            var type = info & 0x0f;
            var sizeType = (info >> 4) & 0x07;
            var isArray = (info & 0x80) != 0;
            if (type == TYPE_STRUCT) {
                readIndex();
            }
            var size = switch (sizeType) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> buffer.get() & 0xff;
                case 6 -> buffer.getShort() & 0xffff;
                default -> buffer.getInt();
            };
            if (type == TYPE_BOOL) {
                return new Tag(name, type, 0, buffer.position());
            }
            if (isArray) {
                readArrayIndex();
            }
            if (size < 0 || buffer.position() + size > buffer.limit()) {
                throw new IllegalStateException(name + " is longer than its property");
            }
            return new Tag(name, type, size, buffer.position());
        }

        private void readArrayIndex() {
            var first = buffer.get() & 0xff;
            if ((first & 0x80) == 0) {
                return;
            }
            buffer.position(buffer.position() + ((first & 0x40) == 0 ? 1 : 3));
        }

        private int readIndex() {
            var b = buffer.get();
            var negative = (b & 0x80) != 0;
            var value = b & 0x3f;
            if ((b & 0x40) != 0) {
                var shift = 6;
                do {
                    b = buffer.get();
                    value |= (b & 0x7f) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0 && shift < 32);
            }
            return negative ? -value : value;
        }
    }
}

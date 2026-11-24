package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.PackageBytes.writeIndex;
import static com.rost.cruma.core.editing.PackageBytes.writeInt;

import com.rost.cruma.core.editing.ActorLightingChanges.Instance;
import com.rost.cruma.core.editing.ActorLightingChanges.Light;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.unreal.UStaticMeshInstance;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class StaticMeshInstanceCodec {
    private StaticMeshInstanceCodec() {}

    record Decoded(
            int prefixEnd,
            Instance instance,
            int lod1,
            int lod2,
            List<Integer> beastLightmaps,
            int beastWidth,
            int beastHeight) {}

    static byte[] write(int fileVersion, int licenseVersion, int noneName, Instance instance, int lod1, int lod2) {
        return write(fileVersion, licenseVersion, noneName, instance, lod1, lod2, new int[0], 0, 0);
    }

    static byte[] write(
            int fileVersion,
            int licenseVersion,
            int noneName,
            Instance instance,
            int lod1,
            int lod2,
            int[] beast,
            int beastWidth,
            int beastHeight) {
        var out = new ByteArrayOutputStream();
        writeIndex(out, noneName);
        out.writeBytes(writeNative(fileVersion, licenseVersion, instance, lod1, lod2, beast, beastWidth, beastHeight));
        return out.toByteArray();
    }

    static byte[] writeNative(int fileVersion, int licenseVersion, Instance instance, int lod1, int lod2) {
        return writeNative(fileVersion, licenseVersion, instance, lod1, lod2, new int[0], 0, 0);
    }

    static byte[] writeNative(
            int fileVersion,
            int licenseVersion,
            Instance instance,
            int lod1,
            int lod2,
            int[] beast,
            int beastWidth,
            int beastHeight) {
        var out = new ByteArrayOutputStream();
        writeIndex(out, instance.vertexCount());
        out.writeBytes(instance.rawColors());
        writeInt(out, instance.revision());
        if (fileVersion > 0x6d) {
            writeLights(out, instance.sceneLights());
        }
        if (licenseVersion > 3) {
            writeLights(out, instance.environmentLights());
        } else if (!instance.environmentLights().isEmpty()) {
            throw new IllegalArgumentException("This package version stores no environment lights");
        }
        if (licenseVersion > 11) {
            writeIndex(out, lod1);
            writeIndex(out, lod2);
        } else if (lod1 != 0 || lod2 != 0) {
            throw new IllegalArgumentException("This package version stores no LOD instances");
        }
        if (licenseVersion >= UStaticMeshInstance.BEAST_LIGHTMAP_LICENSE) {
            writeIndex(out, beast.length);
            for (var reference : beast) {
                writeIndex(out, reference);
            }
            writeInt(out, beastWidth);
            writeInt(out, beastHeight);
        } else if (beast.length != 0) {
            throw new IllegalArgumentException("This package version stores no Beast lightmaps");
        }
        return out.toByteArray();
    }

    static Decoded read(PackageBytes source, ExportEntry entry) throws IOException {
        var c = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        skipProperties(source, entry, c);
        var prefixEnd = c.position;
        var count = c.readIndex();
        if (count < 0 || count > 1_000_000 || (long) count * 4 > c.end - c.position) {
            throw malformed("Invalid StaticMeshInstance color count");
        }
        var colors = new byte[count * 4];
        System.arraycopy(source.body, c.position, colors, 0, colors.length);
        c.skip(colors.length);
        var revision = c.readI32();
        var scene = source.fileVersion > 0x6d ? readLights(c) : List.<Light>of();
        var environment = source.licenseVersion > 3 ? readLights(c) : List.<Light>of();
        var lod1 = 0;
        var lod2 = 0;
        if (source.licenseVersion > 11) {
            lod1 = c.readIndex();
            lod2 = c.readIndex();
        }
        var beast = new ArrayList<Integer>();
        var width = 0;
        var height = 0;
        if (source.licenseVersion >= UStaticMeshInstance.BEAST_LIGHTMAP_LICENSE && c.end - c.position >= 9) {
            var references = c.readIndex();
            if (references < 0 || references > 4096) {
                throw malformed("Invalid StaticMeshInstance Beast lightmap count");
            }
            for (var i = 0; i < references; i++) {
                beast.add(c.readIndex());
            }
            width = c.readI32();
            height = c.readI32();
        }
        if (c.position != c.end) {
            throw malformed("StaticMeshInstance has unread native bytes");
        }
        return new Decoded(
                prefixEnd,
                new Instance(
                        count, colors, revision, resizeLightBits(scene, count), resizeLightBits(environment, count)),
                lod1,
                lod2,
                List.copyOf(beast),
                width,
                height);
    }

    static void skipProperties(PackageBytes source, ExportEntry entry, Cursor c) throws IOException {
        if ((entry.flags() & 0x02000000) != 0) {
            var node = c.readIndex();
            c.readIndex();
            c.skip(12);
            if (node != 0) {
                c.readIndex();
            }
        }
        for (var tags = 0; tags < 65536; tags++) {
            var name = source.getName(c.readIndex());
            if (name.equalsIgnoreCase("None")) {
                return;
            }
            var info = c.readU8();
            var type = info & 15;
            if (type == 0) {
                throw malformed("Invalid property type");
            }
            if (type == 10) {
                c.readIndex();
            }
            var size = switch (info >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> c.readU8();
                case 6 -> c.readU16();
                default -> c.readI32();
            };
            if (type != 3 && (info & 128) != 0) {
                c.readArrayIndex();
            }
            c.skip(type == 3 ? 0 : size);
        }
        throw malformed("Too many tagged properties");
    }

    private static void writeLights(ByteArrayOutputStream out, List<Light> lights) {
        writeIndex(out, lights.size());
        for (var light : lights) {
            writeIndex(out, light.reference());
            var bits = light.rawBits();
            writeIndex(out, bits.length);
            out.writeBytes(bits);
            writeInt(out, light.applied());
        }
    }

    private static List<Light> resizeLightBits(List<Light> lights, int vertices) {
        var bytes = (vertices + 7) >>> 3;
        var result = new ArrayList<Light>(lights.size());
        for (var light : lights) {
            var bits = light.rawBits();
            if (bits.length != bytes) {
                bits = Arrays.copyOf(bits, bytes);
            }
            result.add(new Light(light.reference(), bits, light.applied()));
        }
        return result;
    }

    private static List<Light> readLights(Cursor c) throws IOException {
        var count = c.readIndex();
        if (count < 0 || count > 65536) {
            throw malformed("Invalid StaticMeshInstance light count");
        }
        var lights = new ArrayList<Light>(count);
        for (var i = 0; i < count; i++) {
            var reference = c.readIndex();
            var size = c.readIndex();
            if (size < 0 || size > c.end - c.position) {
                throw malformed("Invalid StaticMeshInstance light bitmap");
            }
            var bits = new byte[size];
            System.arraycopy(c.bytes, c.position, bits, 0, size);
            c.skip(size);
            var applied = c.readI32();
            if (reference == 0) {
                throw malformed("StaticMeshInstance light entry without a light");
            }
            lights.add(new Light(reference, bits, applied));
        }
        return lights;
    }
}

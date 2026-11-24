package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class TerrainNativeCodec {
    public static final int NO_SAVED_GEOMETRY_LICENSE = 17;

    private static final int MAX_SAVED_ENTRIES = 4096 * 4096;

    private TerrainNativeCodec() {}

    public record Vector(float x, float y, float z) {}

    public record Coords(Vector origin, Vector xAxis, Vector yAxis, Vector zAxis) {
        public Vector transform(float x, float y, float z) {
            x -= origin.x();
            y -= origin.y();
            z -= origin.z();
            return new Vector(dot(x, y, z, xAxis), dot(x, y, z, yAxis), dot(x, y, z, zAxis));
        }

        private static float dot(float x, float y, float z, Vector axis) {
            return x * axis.x() + y * axis.y() + z * axis.z();
        }
    }

    public record Box(Vector min, Vector max, int valid) {}

    public record Light(int reference, List<Integer> visibility) {
        public Light {
            visibility = List.copyOf(visibility);
        }
    }

    public record LightmapSize(int width, int height) {}

    public record BeastLightmaps(List<Integer> references, Optional<LightmapSize> size) {
        public BeastLightmaps {
            references = List.copyOf(references);
            size = Objects.requireNonNull(size);
        }
    }

    public record Sector(
            int owner,
            int sizeX,
            int sizeY,
            int offsetX,
            int offsetY,
            Box bounds,
            List<Light> lights,
            int hasTimeMaps,
            List<List<Integer>> timeMaps,
            List<Integer> layerUniformity,
            List<Integer> quadRenderPasses) {
        public Sector {
            lights = List.copyOf(lights);
            timeMaps = timeMaps.stream().map(List::copyOf).toList();
            layerUniformity = List.copyOf(layerUniformity);
            quadRenderPasses = List.copyOf(quadRenderPasses);
        }

        public boolean hasBakedLighting() {
            return !lights.isEmpty() || !timeMaps.isEmpty();
        }
    }

    public static final class SavedGeometry {
        private final float[] vertices;
        private final float[] faceNormals;

        public SavedGeometry(float[] vertices, float[] faceNormals) {
            if (vertices.length % 3 != 0 || faceNormals.length % 6 != 0) {
                throw new IllegalArgumentException("Saved Terrain geometry has a partial entry");
            }
            for (var value : vertices) {
                requireFinite(value);
            }
            for (var value : faceNormals) {
                requireFinite(value);
            }
            this.vertices = vertices.clone();
            this.faceNormals = faceNormals.clone();
        }

        public int getVertexCount() {
            return vertices.length / 3;
        }

        public int getFaceNormalCount() {
            return faceNormals.length / 6;
        }

        public Vector getVertex(int index) {
            return new Vector(vertices[index * 3], vertices[index * 3 + 1], vertices[index * 3 + 2]);
        }

        public float[] getFaceNormal(int index) {
            return Arrays.copyOfRange(faceNormals, index * 6, index * 6 + 6);
        }

        public SavedGeometry with(Map<Integer, Vector> newVertices, Map<Integer, float[]> newNormals) {
            var v = vertices.clone();
            var n = faceNormals.clone();
            for (var entry : newVertices.entrySet()) {
                var i = Objects.checkIndex(entry.getKey(), getVertexCount()) * 3;
                v[i] = entry.getValue().x();
                v[i + 1] = entry.getValue().y();
                v[i + 2] = entry.getValue().z();
            }
            for (var entry : newNormals.entrySet()) {
                if (entry.getValue().length != 6) {
                    throw new IllegalArgumentException("A face normal entry holds six floats");
                }
                System.arraycopy(
                        entry.getValue(), 0, n, Objects.checkIndex(entry.getKey(), getFaceNormalCount()) * 6, 6);
            }
            return new SavedGeometry(v, n);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SavedGeometry geometry
                    && Arrays.equals(vertices, geometry.vertices)
                    && Arrays.equals(faceNormals, geometry.faceNormals);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(vertices) + Arrays.hashCode(faceNormals);
        }

        @Override
        public String toString() {
            return "SavedGeometry[vertices=" + getVertexCount() + ", faceNormals=" + getFaceNormalCount() + "]";
        }

        private static void requireFinite(float value) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite saved Terrain geometry");
            }
        }
    }

    public record Info(
            List<Integer> sectors,
            int sectorsX,
            int sectorsY,
            Coords toWorld,
            Coords toHeightmap,
            int width,
            int height,
            List<Integer> vertexColors,
            Optional<BeastLightmaps> beastLightmaps,
            Optional<SavedGeometry> savedGeometry) {
        public Info {
            sectors = List.copyOf(sectors);
            vertexColors = List.copyOf(vertexColors);
            beastLightmaps = Objects.requireNonNull(beastLightmaps);
            savedGeometry = Objects.requireNonNull(savedGeometry);
        }

        public Info(
                List<Integer> sectors,
                int sectorsX,
                int sectorsY,
                Coords toWorld,
                Coords toHeightmap,
                int width,
                int height,
                List<Integer> vertexColors,
                Optional<BeastLightmaps> beastLightmaps) {
            this(
                    sectors,
                    sectorsX,
                    sectorsY,
                    toWorld,
                    toHeightmap,
                    width,
                    height,
                    vertexColors,
                    beastLightmaps,
                    Optional.empty());
        }

        public Info(
                List<Integer> sectors,
                int sectorsX,
                int sectorsY,
                Coords toWorld,
                Coords toHeightmap,
                int width,
                int height,
                List<Integer> vertexColors) {
            this(
                    sectors,
                    sectorsX,
                    sectorsY,
                    toWorld,
                    toHeightmap,
                    width,
                    height,
                    vertexColors,
                    Optional.empty(),
                    Optional.empty());
        }

        public Info withLighting(List<Integer> colors, Optional<SavedGeometry> geometry) {
            return new Info(
                    sectors, sectorsX, sectorsY, toWorld, toHeightmap, width, height, colors, beastLightmaps, geometry);
        }
    }

    public static boolean supports(int fileVersion, int licenseVersion) {
        return fileVersion == 123 && licenseVersion >= 17 && licenseVersion <= 40;
    }

    public static boolean supportsReadOnly(int fileVersion, int licenseVersion) {
        return fileVersion >= 118 && fileVersion <= 133 && licenseVersion >= 0 && licenseVersion <= 40;
    }

    public static boolean savesSectorTimeMaps(int licenseVersion) {
        return licenseVersion > 3;
    }

    public static boolean savesLayerUniformity(int licenseVersion) {
        return licenseVersion >= 8;
    }

    public static boolean savesQuadRenderPasses(int licenseVersion) {
        return licenseVersion >= 10;
    }

    public static boolean savesGeometry(int licenseVersion) {
        return licenseVersion < NO_SAVED_GEOMETRY_LICENSE;
    }

    public static Sector readSector(int fileVersion, int licenseVersion, byte[] tail) throws IOException {
        checkVersion(fileVersion, licenseVersion);
        var c = new Cursor(tail, 0, tail.length);
        var owner = c.readIndex();
        if (owner <= 0) {
            throw invalidTerrain("Sector owner must be a local TerrainInfo export");
        }
        var sx = checkDimension(c.readI32(), 16, "sector width");
        var sy = checkDimension(c.readI32(), 16, "sector height");
        var ox = c.readI32();
        var oy = c.readI32();
        if (ox < 0 || oy < 0 || ox % 16 != 0 || oy % 16 != 0) {
            throw invalidTerrain("Sector origin is not on the native 16-quad grid");
        }
        var min = readVector(c);
        var max = readVector(c);
        var valid = c.readU8();
        if (valid > 1 || valid == 1 && (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())) {
            throw invalidTerrain("Invalid terrain FBox");
        }
        var lights = new ArrayList<Light>();
        var lightCount = count(c.readIndex(), 65536, "static lights");
        for (var i = 0; i < lightCount; i++) {
            lights.add(new Light(c.readIndex(), readBytes(c, count(c.readIndex(), 37, "light visibility bytes"))));
        }
        var hasMaps = 0;
        var maps = new ArrayList<List<Integer>>();
        if (savesSectorTimeMaps(licenseVersion)) {
            hasMaps = c.readI32();
            if (hasMaps != 0 && hasMaps != 1) {
                throw invalidTerrain("Unknown time-map flag");
            }
            if (hasMaps != 0) {
                var count = count(c.readI32(), 64, "time maps");
                for (var i = 0; i < count; i++) {
                    if (c.readIndex() != 289) {
                        throw invalidTerrain("A native time map must contain 289 bytes");
                    }
                    maps.add(readBytes(c, 289));
                }
            }
        }
        var uniform = new ArrayList<Integer>(32);
        if (savesLayerUniformity(licenseVersion)) {
            for (var i = 0; i < 32; i++) {
                uniform.add(c.readU16());
            }
        }
        var passes = new ArrayList<Integer>();
        if (savesQuadRenderPasses(licenseVersion)) {
            var passCount = count(c.readIndex(), 256, "quad pass masks");
            for (var i = 0; i < passCount; i++) {
                passes.add(c.readU16());
            }
        }
        var result =
                new Sector(owner, sx, sy, ox, oy, new Box(min, max, valid), lights, hasMaps, maps, uniform, passes);
        finish(c, tail, writeSector(fileVersion, licenseVersion, result));
        return result;
    }

    public static Info readInfo(int fileVersion, int licenseVersion, byte[] tail) throws IOException {
        checkVersion(fileVersion, licenseVersion);
        var c = new Cursor(tail, 0, tail.length);
        var count = count(c.readIndex(), 4096, "sector references");
        var refs = new ArrayList<Integer>(count);
        for (var i = 0; i < count; i++) {
            var ref = c.readIndex();
            if (ref <= 0 || refs.contains(ref)) {
                throw invalidTerrain("Invalid or duplicate TerrainInfo sector reference");
            }
            refs.add(ref);
        }
        var geometry = savesGeometry(licenseVersion);
        var vertices = geometry ? readFloats(c, count(c.readIndex(), MAX_SAVED_ENTRIES, "saved vertices"), 3) : null;
        var sx = checkDimension(c.readI32(), 256, "sector grid width");
        var sy = checkDimension(c.readI32(), 256, "sector grid height");
        if (sx * sy != count) {
            throw invalidTerrain("TerrainInfo sector count differs from grid");
        }
        var normals = geometry ? readFloats(c, count(c.readIndex(), MAX_SAVED_ENTRIES, "saved face normals"), 6) : null;
        var world = readCoords(c);
        var heightmap = readCoords(c);
        var width = checkDimension(c.readI32(), 4096, "heightmap width");
        var height = checkDimension(c.readI32(), 4096, "heightmap height");
        var colorCount = count(c.readIndex(), (width + 1) * (height + 1), "vertex colors");
        var colors = readBytes(c, Math.multiplyExact(colorCount, 4));
        Optional<BeastLightmaps> beast = Optional.empty();
        if (fileVersion >= 124) {
            var textureCount = count(c.readIndex(), 65536, "Beast lightmap references");
            var textures = new ArrayList<Integer>(textureCount);
            for (var i = 0; i < textureCount; i++) {
                textures.add(c.readIndex());
            }
            var size = fileVersion >= 125
                    ? Optional.of(new LightmapSize(c.readI32(), c.readI32()))
                    : Optional.<LightmapSize>empty();
            beast = Optional.of(new BeastLightmaps(textures, size));
        }
        Optional<SavedGeometry> saved = Optional.empty();
        if (geometry) {
            try {
                saved = Optional.of(new SavedGeometry(vertices, normals));
            } catch (IllegalArgumentException invalidGeometry) {
                throw invalidTerrain(invalidGeometry.getMessage());
            }
        }
        var result = new Info(refs, sx, sy, world, heightmap, width, height, colors, beast, saved);
        finish(c, tail, writeInfo(fileVersion, licenseVersion, result));
        return result;
    }

    public static byte[] writeSector(Sector sector) {
        try {
            return writeSector(123, 40, sector);
        } catch (IOException impossible) {
            throw new IllegalArgumentException(impossible.getMessage(), impossible);
        }
    }

    public static byte[] writeSector(int fileVersion, int licenseVersion, Sector sector) throws IOException {
        checkVersion(fileVersion, licenseVersion);
        if (!savesSectorTimeMaps(licenseVersion)
                        && (sector.hasTimeMaps() != 0 || !sector.timeMaps().isEmpty())
                || !savesLayerUniformity(licenseVersion)
                        && !sector.layerUniformity().isEmpty()
                || !savesQuadRenderPasses(licenseVersion)
                        && !sector.quadRenderPasses().isEmpty()) {
            throw invalidTerrain("TerrainSector fields do not match licensee version " + licenseVersion);
        }
        var out = new ByteArrayOutputStream();
        PackageBytes.writeIndex(out, sector.owner());
        for (var value : new int[] {sector.sizeX(), sector.sizeY(), sector.offsetX(), sector.offsetY()}) {
            PackageBytes.writeInt(out, value);
        }
        writeVector(out, sector.bounds().min());
        writeVector(out, sector.bounds().max());
        out.write(sector.bounds().valid());
        PackageBytes.writeIndex(out, sector.lights().size());
        for (var light : sector.lights()) {
            PackageBytes.writeIndex(out, light.reference());
            writeByteArray(out, light.visibility());
        }
        if (savesSectorTimeMaps(licenseVersion)) {
            PackageBytes.writeInt(out, sector.hasTimeMaps());
            if (sector.hasTimeMaps() != 0) {
                PackageBytes.writeInt(out, sector.timeMaps().size());
                for (var map : sector.timeMaps()) {
                    writeByteArray(out, map);
                }
            }
        }
        for (var value : sector.layerUniformity()) {
            writeWord(out, value);
        }
        if (savesQuadRenderPasses(licenseVersion)) {
            PackageBytes.writeIndex(out, sector.quadRenderPasses().size());
            for (var value : sector.quadRenderPasses()) {
                writeWord(out, value);
            }
        }
        return out.toByteArray();
    }

    public static byte[] writeInfo(Info info) {
        if (info.beastLightmaps().isPresent() || info.savedGeometry().isPresent()) {
            throw new IllegalArgumentException("Extended TerrainInfo requires an explicit package revision");
        }
        return writeInfoFields(info);
    }

    public static byte[] writeInfo(int fileVersion, int licenseVersion, Info info) throws IOException {
        checkVersion(fileVersion, licenseVersion);
        if (info.beastLightmaps().isPresent() != (fileVersion >= 124)
                || info.beastLightmaps().isPresent()
                        && info.beastLightmaps().orElseThrow().size().isPresent() != (fileVersion >= 125)) {
            throw invalidTerrain("TerrainInfo extension does not match package revision " + fileVersion);
        }
        if (info.savedGeometry().isPresent() != savesGeometry(licenseVersion)) {
            throw invalidTerrain("TerrainInfo saved vertices do not match licensee version " + licenseVersion);
        }
        return writeInfoFields(info);
    }

    private static byte[] writeInfoFields(Info info) {
        var out = new ByteArrayOutputStream();
        PackageBytes.writeIndex(out, info.sectors().size());
        for (var ref : info.sectors()) {
            PackageBytes.writeIndex(out, ref);
        }
        info.savedGeometry().ifPresent(geometry -> {
            PackageBytes.writeIndex(out, geometry.getVertexCount());
            writeFloats(out, geometry.vertices);
        });
        PackageBytes.writeInt(out, info.sectorsX());
        PackageBytes.writeInt(out, info.sectorsY());
        info.savedGeometry().ifPresent(geometry -> {
            PackageBytes.writeIndex(out, geometry.getFaceNormalCount());
            writeFloats(out, geometry.faceNormals);
        });
        writeCoords(out, info.toWorld());
        writeCoords(out, info.toHeightmap());
        PackageBytes.writeInt(out, info.width());
        PackageBytes.writeInt(out, info.height());
        PackageBytes.writeIndex(out, info.vertexColors().size() / 4);
        for (var value : info.vertexColors()) {
            out.write(value);
        }
        info.beastLightmaps().ifPresent(beast -> {
            PackageBytes.writeIndex(out, beast.references().size());
            for (var reference : beast.references()) {
                PackageBytes.writeIndex(out, reference);
            }
            beast.size().ifPresent(size -> {
                PackageBytes.writeInt(out, size.width());
                PackageBytes.writeInt(out, size.height());
            });
        });
        return out.toByteArray();
    }

    private static void checkVersion(int file, int license) throws IOException {
        if (!supportsReadOnly(file, license)) {
            throw invalidTerrain("Unaudited terrain package revision " + file + "/" + license);
        }
    }

    private static IOException invalidTerrain(String message) {
        return new IOException(message);
    }

    private static int count(int count, int max, String what) throws IOException {
        if (count < 0 || count > max) {
            throw invalidTerrain("Invalid " + what + ": " + count);
        }
        return count;
    }

    private static int checkDimension(int value, int max, String what) throws IOException {
        if (value < 1) {
            throw invalidTerrain("Invalid " + what);
        }
        return count(value, max, what);
    }

    private static Vector readVector(Cursor c) throws IOException {
        return new Vector(readFiniteFloat(c), readFiniteFloat(c), readFiniteFloat(c));
    }

    private static float readFiniteFloat(Cursor c) throws IOException {
        var value = Float.intBitsToFloat(c.readI32());
        if (!Float.isFinite(value)) {
            throw invalidTerrain("Non-finite native terrain coordinate");
        }
        return value;
    }

    private static Coords readCoords(Cursor c) throws IOException {
        return new Coords(readVector(c), readVector(c), readVector(c), readVector(c));
    }

    private static float[] readFloats(Cursor c, int count, int perEntry) throws IOException {
        var values = Math.multiplyExact(count, perEntry);
        if ((long) values * 4 > c.end - c.position) {
            throw invalidTerrain("Truncated saved terrain geometry");
        }
        var result = new float[values];
        for (var i = 0; i < values; i++) {
            result[i] = Float.intBitsToFloat(c.readI32());
        }
        return result;
    }

    private static void writeFloats(ByteArrayOutputStream out, float[] values) {
        for (var value : values) {
            PackageBytes.writeInt(out, Float.floatToRawIntBits(value));
        }
    }

    private static List<Integer> readBytes(Cursor c, int count) throws IOException {
        if (count > c.end - c.position) {
            throw invalidTerrain("Truncated terrain byte array");
        }
        var result = new ArrayList<Integer>(count);
        for (var i = 0; i < count; i++) {
            result.add(c.readU8());
        }
        return result;
    }

    private static void finish(Cursor c, byte[] source, byte[] written) throws IOException {
        if (c.position != c.end) {
            throw invalidTerrain("Unaudited trailing terrain data: " + (c.end - c.position) + " bytes");
        }
        if (!Arrays.equals(source, written)) {
            throw invalidTerrain("Noncanonical terrain encoding cannot be rewritten losslessly");
        }
    }

    private static void writeVector(ByteArrayOutputStream out, Vector v) {
        PackageBytes.writeInt(out, Float.floatToRawIntBits(v.x()));
        PackageBytes.writeInt(out, Float.floatToRawIntBits(v.y()));
        PackageBytes.writeInt(out, Float.floatToRawIntBits(v.z()));
    }

    private static void writeCoords(ByteArrayOutputStream out, Coords c) {
        writeVector(out, c.origin());
        writeVector(out, c.xAxis());
        writeVector(out, c.yAxis());
        writeVector(out, c.zAxis());
    }

    private static void writeByteArray(ByteArrayOutputStream out, List<Integer> bytes) {
        PackageBytes.writeIndex(out, bytes.size());
        for (var value : bytes) {
            out.write(value);
        }
    }

    private static void writeWord(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
    }
}

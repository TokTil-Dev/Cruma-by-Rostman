package com.rost.cruma.core.unreal.edit;

import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UObject.ObjectFlags;
import com.rost.cruma.core.unreal.UObject.StateFrame;
import com.rost.cruma.core.unreal.UObjectExport;
import com.rost.cruma.core.unreal.UProperty;
import com.rost.cruma.core.unreal.UPropertyExtractor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class MapTileShift {
    public static final float TILE_SIZE = 32768f;
    private static final int HEADER_SIZE = 28;
    private static final byte VER111_KEY = (byte) 0xAC;
    private static final Set<String> WORLD_VECTORS = Set.of(
            "Location",
            "OldLocation",
            "ColLocation",
            "OrgLocation",
            "BasePos",
            "CameraLocationDynamic",
            "CameraLocationTop",
            "CameraLocationFront",
            "CameraLocationSide");
    private static final int VECTOR = 0;
    private static final int MAP_X = 1;
    private static final int MAP_Y = 2;
    private static final int COORDS_TO_WORLD = 3;
    private static final int DECORATION = 4;
    private static final int DECORATION_QUAD = 5;
    private static final Set<String> DECORATION_ARRAYS =
            Set.of("DecoLayerData", "DecoInstances", "DecoLayerDataForQuad", "DecoQuadInfo");

    private final UArchive archive;
    private final ByteBuffer buffer;
    private final float dx;
    private final float dy;
    private final int tileDx;
    private final int tileDy;
    private final float sourceMinX;
    private final float sourceMinY;
    private final Report report = new Report();
    private int[] layerToWorld;

    private MapTileShift(UArchive archive, ByteBuffer buffer, int fromX, int fromY, int toX, int toY) {
        this.archive = archive;
        this.buffer = buffer;
        this.dx = (toX - fromX) * TILE_SIZE;
        this.dy = (toY - fromY) * TILE_SIZE;
        this.tileDx = toX - fromX;
        this.tileDy = toY - fromY;
        this.sourceMinX = (fromX - 20) * TILE_SIZE;
        this.sourceMinY = (fromY - 18) * TILE_SIZE;
    }

    public static final class Report {
        public final Map<String, Integer> patched = new TreeMap<>();
        public final Map<String, Integer> keptVectors = new TreeMap<>();

        public final Map<String, Integer> suspicious = new TreeMap<>();

        public final Map<String, Integer> classes = new TreeMap<>();
        public final List<String> problems = new ArrayList<>();

        void count(String kind) {
            patched.merge(kind, 1, Integer::sum);
        }
    }

    public record Shifted(byte[] bytes, Report report) {}

    public static boolean isSupported(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return isVer111(input.readNBytes(HEADER_SIZE));
        }
    }

    public static Shifted shift(Path clientRoot, Path source, int fromX, int fromY, int toX, int toY)
            throws IOException {
        var raw = Files.readAllBytes(source);
        if (!isVer111(raw)) {
            throw new IOException("Only Lineage2Ver111 packages can be moved: " + source.getFileName());
        }
        var buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        applyXorKey(raw);
        buffer.position(HEADER_SIZE);
        var name = source.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        var loader = newLoader(clientRoot);
        var archive = new UArchive(name, buffer, loader);
        var shift = new MapTileShift(archive, buffer, fromX, fromY, toX, toY);
        try {
            shift.run();
        } finally {
            loader.clearArchives();
        }
        applyXorKey(raw);
        return new Shifted(raw, shift.report);
    }

    static UArchiveLoader newLoader(Path clientRoot) {
        return new UArchiveLoader(
                clientRoot,
                List.of(
                        new USearchConfig("Maps", "unr"),
                        new USearchConfig("StaticMeshes", "usx"),
                        new USearchConfig("Textures", "utx"),
                        new USearchConfig("SysTextures", "utx")));
    }

    private void run() {
        var levels = new ArrayList<ULevel>();
        archive.loadObjects("Level", levels);
        if (levels.size() != 1) {
            throw new IllegalStateException("Expected one Level, found " + levels.size());
        }
        var model = levels.getFirst().getModel().get();
        var levelModel = model == null ? null : model.getName();
        var levelPolys = model == null || model.getPolys().get() == null
                ? null
                : model.getPolys().get().getName();

        for (var export : archive.getExportMap()) {
            if (export.getSerialSize() <= 0) {
                continue;
            }
            var className = export.getClassName();
            report.classes.merge(className, 1, Integer::sum);
            try {
                switch (className) {
                    case "Model" -> {
                        if (export.getObjectName().equals(levelModel)) {
                            shiftModel(export);
                        } else {
                            shiftProperties(export);
                        }
                    }
                    case "Polys" -> {
                        if (export.getObjectName().equals(levelPolys)) {
                            shiftPolys(export);
                        }
                    }
                    case "TerrainInfo" -> shiftTerrainInfo(export);
                    case "TerrainSector" -> shiftTerrainSector(export);
                    default -> shiftProperties(export);
                }
            } catch (RuntimeException e) {
                report.problems.add(export.getObjectName() + " (" + className + "): " + e);
            }
            var end = export.getSerialOffset() + export.getSerialSize();
            if (archive.getOffset() > end) {
                report.problems.add(export.getObjectName()
                        + " ("
                        + className
                        + ") read past its end by "
                        + (archive.getOffset() - end));
            }
        }
    }

    private void shiftProperties(UObjectExport export) {
        archive.setPosition(export.getSerialOffset());
        if ((export.getObjectFlags() & ObjectFlags.RF_HasStack.getId()) != 0) {
            StateFrame.deserialize(archive);
        }
        var start = archive.getOffset();
        archive.getPropertyExtractor().extractProperties();
        var expectedEnd = archive.getOffset();
        archive.setPosition(start);
        var found = new ArrayList<int[]>();
        walkProperties(export.getClassName(), found);
        if (archive.getOffset() != expectedEnd) {
            throw new IllegalStateException(
                    "property walk ended at " + archive.getOffset() + ", reader at " + expectedEnd);
        }
        for (var patch : found) {
            switch (patch[0]) {
                case VECTOR -> {
                    addXy(patch[1]);
                    report.count("vector property");
                }
                case MAP_X -> {
                    buffer.putInt(toAbsolute(patch[1]), buffer.getInt(toAbsolute(patch[1])) + tileDx);
                    report.count("MapX");
                }
                case MAP_Y -> {
                    buffer.putInt(toAbsolute(patch[1]), buffer.getInt(toAbsolute(patch[1])) + tileDy);
                    report.count("MapY");
                }
                case COORDS_TO_WORLD -> {
                    shiftToWorld(patch[1], patch[2], patch[3], patch[4]);
                    report.count("terrain layer transform");
                }
                case DECORATION_QUAD -> {
                    addXy(patch[1]);
                    report.count("decoration quad");
                }
                default -> {
                    addXy(patch[1]);
                    report.count("decoration instance");
                }
            }
        }
        archive.setPosition(expectedEnd);
    }

    private void walkProperties(String className, List<int[]> found) {
        while (true) {
            var name = archive.readName();
            if (UArchive.NONE_NAME.equals(name)) {
                return;
            }
            var info = archive.readUInt8();
            var type = info & 0x0F;
            var sizeType = (info >> 4) & 0x07;
            var isArray = (info & 0x80) != 0;
            var structName = type == 10 ? archive.readName() : "";
            var size = switch (sizeType) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> archive.readUInt8();
                case 6 -> archive.readUInt16();
                default -> (int) archive.readUInt32();
            };
            if (type == 3) {
                continue;
            }
            var arrayIndex = isArray ? archive.readArrayIndex() : 0;
            var valueStart = archive.getOffset();
            var vector = type == 11 || (type == 10 && "Vector".equals(structName));
            if (vector) {
                if (WORLD_VECTORS.contains(name)
                        || (layerToWorld != null && "ToMaskmap".equals(name) && arrayIndex == 0)) {
                    found.add(new int[] {VECTOR, valueStart});
                } else if (layerToWorld != null && "ToWorld".equals(name) && arrayIndex < 4) {
                    layerToWorld[arrayIndex] = valueStart;
                } else {
                    report.keptVectors.merge(className + "." + name, 1, Integer::sum);
                    var x = buffer.getFloat(toAbsolute(valueStart));
                    var y = buffer.getFloat(toAbsolute(valueStart + 4));
                    if (x > sourceMinX
                            && x < sourceMinX + TILE_SIZE
                            && y > sourceMinY
                            && y < sourceMinY + TILE_SIZE
                            && Math.abs(x) + Math.abs(y) > 4096) {
                        report.suspicious.merge(className + "." + name, 1, Integer::sum);
                    }
                }
            } else if (type == 2 && "TerrainInfo".equals(className) && ("MapX".equals(name) || "MapY".equals(name))) {
                found.add(new int[] {"MapX".equals(name) ? MAP_X : MAP_Y, valueStart});
            }
            if (type == 9 && "ZoneRenderState".equals(name)) {
                var count = archive.readIndex();
                for (var i = 0; i < count; i++) {
                    walkProperties(className + ".ZoneRenderState", found);
                }
                continue;
            }
            if (type == 9 && DECORATION_ARRAYS.contains(name)) {
                walkDecorations(className + "." + name, valueStart, size, found);
                continue;
            }
            if (type == 10 && "PointRegion".equals(structName)) {
                archive.setPosition(regionEnd(valueStart, size));
                continue;
            }
            if (type == 10 && "TerrainLayer".equals(structName)) {
                walkTerrainLayer(className + "." + name, found);
                continue;
            }
            archive.setPosition(valueStart + size);
        }
    }

    private int regionEnd(int valueStart, int size) {
        try {
            var fields = archive.getPropertyExtractor().extractProperties().stream()
                    .map(UProperty::getName)
                    .toList();
            var consumed = archive.getOffset() - valueStart;
            if (consumed == size
                    || UPropertyExtractor.isRegionLongerThanItsTag("PointRegion", fields, consumed, size)) {
                return archive.getOffset();
            }
        } catch (RuntimeException ignored) {
        }
        return valueStart + size;
    }

    private void walkTerrainLayer(String path, List<int[]> found) {
        var outer = layerToWorld;
        layerToWorld = new int[] {-1, -1, -1, -1};
        walkProperties(path, found);
        if (Arrays.stream(layerToWorld).allMatch(at -> at >= 0)) {
            found.add(new int[] {COORDS_TO_WORLD, layerToWorld[0], layerToWorld[1], layerToWorld[2], layerToWorld[3]});
        }
        layerToWorld = outer;
    }

    private void walkDecorations(String path, int valueStart, int size, List<int[]> found) {
        var elements = new ArrayList<int[]>();
        var read = true;
        try {
            var count = archive.readIndex();
            for (var i = 0; i < count; i++) {
                walkProperties(path, elements);
            }
        } catch (RuntimeException e) {
            read = false;
        }
        if (read && archive.getOffset() == valueStart + size) {
            var kind = path.endsWith(".DecoQuadInfo") ? DECORATION_QUAD : DECORATION;
            for (var element : elements) {
                found.add(element[0] == VECTOR ? new int[] {kind, element[1]} : element);
            }
        } else {
            report.problems.add(path + " does not end where its tag says");
        }
        archive.setPosition(valueStart + size);
    }

    private void shiftModel(UObjectExport export) {
        shiftProperties(export);
        shiftBox("model bounds");
        shiftSphere("model bounds");
        skipVectors();
        var points = archive.readIndex();
        for (var i = 0; i < points; i++) {
            addXy(archive.getOffset());
            archive.skipBytes(12);
        }
        report.patched.merge("BSP point", points, Integer::sum);
        var nodes = archive.readIndex();
        var hullStarts = new TreeSet<Integer>();
        for (var i = 0; i < nodes; i++) {
            shiftPlane("BSP node plane");
            archive.skipBytes(8 + 1);
            for (var j = 0; j < 7; j++) {
                var index = archive.readIndex();
                if (j == 5 && index >= 0) {
                    hullStarts.add(index);
                }
            }
            shiftSphere("BSP node sphere");
            shiftSphere("BSP node sphere");
            archive.readIndex();
            archive.readIndex();
            archive.skipBytes(1 + 8 + 12);
        }
        var version = archive.getHeader().getFileVersion();
        var license = archive.getHeader().getLicenseVersion();
        var surfaces = archive.readIndex();
        for (var i = 0; i < surfaces; i++) {
            archive.readIndex();
            archive.skipBytes(4);
            for (var j = 0; j < 4; j++) {
                archive.readIndex();
            }
            if (version < 101) {
                archive.readIndex();
            }
            archive.readIndex();
            if (version < 78) {
                archive.skipBytes(4);
            }
            archive.readIndex();
            if (version > 86) {
                shiftPlane("BSP surface plane");
            }
            if (version >= 106) {
                archive.skipBytes(4);
            }
            if (license > 20) {
                archive.skipBytes(4);
            }
        }
        var vertices = archive.readIndex();
        for (var i = 0; i < vertices; i++) {
            archive.readIndex();
            archive.readIndex();
        }
        archive.skipBytes(4);
        var zones = archive.readInt();
        for (var i = 0; i < zones; i++) {
            archive.readIndex();
            archive.skipBytes(8 + 8 + 4);
        }
        archive.readIndex();
        var bounds = archive.readIndex();
        for (var i = 0; i < bounds; i++) {
            shiftBox("BSP render bound");
        }
        var hulls = archive.readIndex();
        shiftHullBoxes(archive.getOffset(), hulls, hullStarts);
        archive.skipBytes(hulls * 4);
        var leaves = archive.readIndex();
        for (var i = 0; i < leaves; i++) {
            archive.readIndex();
            archive.readIndex();
            archive.readIndex();
            archive.skipBytes(8);
        }
        var lights = archive.readIndex();
        for (var i = 0; i < lights; i++) {
            archive.readIndex();
        }
        archive.skipBytes(8);
        var sections = archive.readIndex();
        for (var i = 0; i < sections; i++) {
            var count = archive.readIndex();
            for (var j = 0; j < count; j++) {
                addXy(archive.getOffset());
                archive.skipBytes(12 + 16 + (version > 0x6c ? 12 : 0));
            }
            report.patched.merge("BSP section vertex", count, Integer::sum);
            archive.skipBytes(4);
            archive.readIndex();
            archive.skipBytes(12);
        }
        var lightmaps = archive.readIndex();
        for (var i = 0; i < lightmaps; i++) {
            for (var j = 0; j < 7; j++) {
                archive.readIndex();
            }
            shiftLightmapMatrix(archive.getOffset());
            addXy(archive.getOffset() + 64);
            archive.skipBytes(64 + 36);
            var subs = archive.readIndex();
            for (var j = 0; j < subs; j++) {
                archive.readIndex();
                archive.skipBytes(archive.readIndex());
                archive.skipBytes(28);
            }
            archive.readIndex();
            archive.skipBytes(4);
        }
        report.patched.merge("lightmap matrix", lightmaps, Integer::sum);
    }

    private void shiftHullBoxes(int packageOffset, int count, Set<Integer> starts) {
        var boxes = new TreeSet<Integer>();
        for (var start : starts) {
            var end = start;
            while (end < count && buffer.getInt(toAbsolute(packageOffset + end * 4)) != -1) {
                end++;
            }
            if (end + 6 < count) {
                boxes.add(end + 1);
            } else {
                report.problems.add("BSP collision hull " + start + " has no box");
            }
        }
        for (var box : boxes) {
            addXy(packageOffset + box * 4);
            addXy(packageOffset + (box + 3) * 4);
        }
        report.patched.merge("BSP collision box", boxes.size(), Integer::sum);
    }

    private void shiftPolys(UObjectExport export) {
        shiftProperties(export);
        var count = archive.readInt();
        archive.readInt();
        for (var i = 0; i < count; i++) {
            var vertexCount = archive.readIndex();
            addXy(archive.getOffset());
            archive.skipBytes(48);
            for (var j = 0; j < vertexCount; j++) {
                addXy(archive.getOffset());
                archive.skipBytes(12);
            }
            report.patched.merge("level poly vertex", vertexCount + 1, Integer::sum);
            skipPolyTail();
        }
    }

    private void skipPolyTail() {
        archive.skipBytes(4);
        archive.readIndex();
        archive.readIndex();
        archive.readIndex();
        archive.readIndex();
        archive.readIndex();
        if (archive.getHeader().getFileVersion() < 78) {
            archive.skipBytes(4);
        }
        if (archive.getHeader().getFileVersion() >= 106) {
            archive.skipBytes(4);
        }
        if (archive.getHeader().getLicenseVersion() > 21) {
            archive.skipBytes(4);
        }
    }

    private void shiftTerrainInfo(UObjectExport export) {
        shiftProperties(export);
        var sectors = archive.readIndex();
        for (var i = 0; i < sectors; i++) {
            archive.readIndex();
        }
        if (archive.getHeader().getLicenseVersion() < 17) {
            shiftOldTerrainVertices(export, sectors);
        } else {
            archive.skipBytes(8);
        }
        var toWorld = archive.getOffset();
        shiftToWorld(toWorld, toWorld + 12, toWorld + 24, toWorld + 36);
        addXy(toWorld + 48);
        report.count("terrain transform");
        archive.setPosition(toWorld + 96);
    }

    private void shiftOldTerrainVertices(UObjectExport export, int sectors) {
        var end = export.getSerialOffset() + export.getSerialSize();
        var vertices = archive.readIndex();
        var first = archive.getOffset();
        if (vertices < 0 || first + vertices * 12L + 8 > end) {
            throw new IllegalStateException("TerrainInfo has " + vertices + " vertices");
        }
        archive.skipBytes(vertices * 12);
        var sectorsX = archive.readInt();
        var sectorsY = archive.readInt();
        var normals = archive.readIndex();
        var toWorld = archive.getOffset() + normals * 24L;
        if (sectorsX * sectorsY != sectors || normals != vertices || toWorld + 96 > end) {
            throw new IllegalStateException(
                    "unknown licence " + archive.getHeader().getLicenseVersion() + " TerrainInfo layout");
        }
        var at = toAbsolute((int) toWorld);
        var originX = buffer.getFloat(toAbsolute((int) toWorld + 48));
        var originY = buffer.getFloat(toAbsolute((int) toWorld + 52));
        var worldX = -(buffer.getFloat(at) * buffer.getFloat(at + 12)
                + buffer.getFloat(at + 4) * buffer.getFloat(at + 16)
                + buffer.getFloat(at + 8) * buffer.getFloat(at + 20));
        var worldY = -(buffer.getFloat(at) * buffer.getFloat(at + 24)
                + buffer.getFloat(at + 4) * buffer.getFloat(at + 28)
                + buffer.getFloat(at + 8) * buffer.getFloat(at + 32));
        if (Math.abs(worldX - originX) > 1 || Math.abs(worldY - originY) > 1) {
            throw new IllegalStateException(
                    "licence " + archive.getHeader().getLicenseVersion() + " TerrainInfo transforms do not meet");
        }
        for (var i = 0; i < vertices; i++) {
            addXy(first + i * 12);
        }
        report.patched.merge("terrain vertex", vertices, Integer::sum);
        archive.setPosition((int) toWorld);
    }

    private void shiftToWorld(int origin, int xAxis, int yAxis, int zAxis) {
        var rows = new int[] {xAxis, yAxis, zAxis};
        float[][] axes = new float[3][3];
        for (var a = 0; a < 3; a++) {
            for (var c = 0; c < 3; c++) {
                axes[a][c] = buffer.getFloat(toAbsolute(rows[a] + c * 4));
            }
        }
        var inverse = invert(axes);
        for (var c = 0; c < 3; c++) {
            var at = toAbsolute(origin + c * 4);
            buffer.putFloat(at, buffer.getFloat(at) - (inverse[c][0] * dx + inverse[c][1] * dy));
        }
    }

    private void shiftTerrainSector(UObjectExport export) {
        shiftProperties(export);
        archive.readIndex();
        archive.skipBytes(16);
        shiftBox("terrain sector bounds");
    }

    private int toAbsolute(int packageOffset) {
        return HEADER_SIZE + packageOffset;
    }

    private void addXy(int packageOffset) {
        var at = toAbsolute(packageOffset);
        buffer.putFloat(at, buffer.getFloat(at) + dx);
        buffer.putFloat(at + 4, buffer.getFloat(at + 4) + dy);
    }

    private void shiftBox(String kind) {
        if (buffer.get(toAbsolute(archive.getOffset() + 24)) != 0) {
            addXy(archive.getOffset());
            addXy(archive.getOffset() + 12);
            report.count(kind);
        }
        archive.skipBytes(25);
    }

    private void shiftSphere(String kind) {
        addXy(archive.getOffset());
        archive.skipBytes(16);
        report.count(kind);
    }

    private void shiftPlane(String kind) {
        var at = toAbsolute(archive.getOffset());
        var w = buffer.getFloat(at + 12) + buffer.getFloat(at) * dx + buffer.getFloat(at + 4) * dy;
        buffer.putFloat(at + 12, w);
        archive.skipBytes(16);
        report.count(kind);
    }

    private void shiftLightmapMatrix(int packageOffset) {
        var at = toAbsolute(packageOffset);
        for (var row = 0; row < 4; row++) {
            var translation = at + (12 + row) * 4;
            var moved = buffer.getFloat(at + row * 4) * dx + buffer.getFloat(at + (4 + row) * 4) * dy;
            buffer.putFloat(translation, buffer.getFloat(translation) - moved);
        }
    }

    private void skipVectors() {
        archive.skipBytes(archive.readIndex() * 12);
    }

    private static boolean isVer111(byte[] header) {
        if (header.length < HEADER_SIZE) {
            return false;
        }
        var text = "Lineage2Ver111";
        for (var i = 0; i < text.length(); i++) {
            if (header[i * 2] != text.charAt(i) || header[i * 2 + 1] != 0) {
                return false;
            }
        }
        return true;
    }

    private static void applyXorKey(byte[] raw) {
        for (var i = HEADER_SIZE; i < raw.length; i++) {
            raw[i] ^= VER111_KEY;
        }
    }

    private static float[][] invert(float[][] m) {
        var det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        var r = new float[3][3];
        r[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det;
        r[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det;
        r[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det;
        r[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det;
        r[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
        r[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det;
        r[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det;
        r[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det;
        r[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det;
        return r;
    }
}

package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UActor.LightActor;
import com.rost.cruma.core.unreal.UActor.UInfoActor;
import com.rost.cruma.core.unreal.UMaterial.URenderedMaterial;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.UObjectRef.Required;
import com.rost.cruma.core.unreal.primitives.UBox;
import com.rost.cruma.core.unreal.primitives.UMatrix;
import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UTerrain {
    private static final float TERRAIN_ZSCALE = 1f / 128f;
    private static final Vec3 VEC_3_EMPTY = new Vec3(0f, 0f, 0f);

    public record TerrainCoords(Vec3 origin, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis) {
        public static TerrainCoords identity() {
            return new TerrainCoords(
                    new Vec3(0f, 0f, 0f), new Vec3(1f, 0f, 0f), new Vec3(0f, 1f, 0f), new Vec3(0f, 0f, 1f));
        }

        public Vec3 transformPoint(Vec3 point) {
            return transformVector(point.minus(origin));
        }

        public Vec3 transformVector(Vec3 vector) {
            return new Vec3(vector.dot(xAxis), vector.dot(yAxis), vector.dot(zAxis));
        }

        public TerrainCoords times(TerrainCoords other) {
            return new TerrainCoords(
                    other.transformPoint(origin),
                    other.transformVector(xAxis),
                    other.transformVector(yAxis),
                    other.transformVector(zAxis));
        }

        public TerrainCoords transpose() {
            return new TerrainCoords(
                    transformVector(origin.times(-1f)),
                    new Vec3(xAxis.getX(), yAxis.getX(), zAxis.getX()),
                    new Vec3(xAxis.getY(), yAxis.getY(), zAxis.getY()),
                    new Vec3(xAxis.getZ(), yAxis.getZ(), zAxis.getZ()));
        }

        public TerrainCoords inverseRotate(float pitch, float yaw, float roll) {
            var cr = (float) Math.cos(sineTableRadians((int) roll));
            var sr = (float) Math.sin(sineTableRadians((int) roll));
            var cp = (float) Math.cos(sineTableRadians((int) pitch));
            var sp = (float) Math.sin(sineTableRadians((int) pitch));
            var cy = (float) Math.cos(sineTableRadians((int) yaw));
            var sy = (float) Math.sin(sineTableRadians((int) yaw));
            var inverseRoll =
                    new TerrainCoords(VEC_3_EMPTY, new Vec3(1f, 0f, 0f), new Vec3(0f, cr, sr), new Vec3(0f, -sr, cr));
            var inversePitch =
                    new TerrainCoords(VEC_3_EMPTY, new Vec3(cp, 0f, -sp), new Vec3(0f, 1f, 0f), new Vec3(sp, 0f, cp));
            var inverseYaw =
                    new TerrainCoords(VEC_3_EMPTY, new Vec3(cy, -sy, 0f), new Vec3(sy, cy, 0f), new Vec3(0f, 0f, 1f));
            return times(inverseRoll).times(inversePitch).times(inverseYaw);
        }

        public TerrainCoords divideTextureScale(float uScale, float vScale) {
            return new TerrainCoords(origin, xAxis.times(1f / uScale), yAxis.times(1f / vScale), zAxis);
        }

        public TerrainCoords plusOrigin(Vec3 offset) {
            return new TerrainCoords(origin.plus(offset), xAxis, yAxis, zAxis);
        }

        public TerrainCoords mapAxis(TextureMapAxis axis) {
            return switch (axis) {
                case TEXMAPAXIS_XY -> this;
                case TEXMAPAXIS_XZ ->
                    new TerrainCoords(
                            new Vec3(origin.getY(), origin.getZ(), origin.getX()),
                            new Vec3(xAxis.getZ(), xAxis.getY(), xAxis.getX()),
                            new Vec3(yAxis.getY(), yAxis.getX(), yAxis.getZ()),
                            new Vec3(zAxis.getX(), zAxis.getZ(), zAxis.getY()));
                case TEXMAPAXIS_YZ ->
                    new TerrainCoords(
                            new Vec3(origin.getZ(), origin.getY(), origin.getX()),
                            new Vec3(xAxis.getZ(), xAxis.getY(), xAxis.getX()),
                            yAxis,
                            new Vec3(zAxis.getZ(), zAxis.getY(), zAxis.getX()));
                case TEXMAPAXIS_MAX -> throw new IllegalArgumentException("Invalid terrain texture axis MAX");
            };
        }

        private static TerrainCoords read(UArchive archive) {
            return new TerrainCoords(
                    archive.readVector(), archive.readVector(), archive.readVector(), archive.readVector());
        }

        private static double sineTableRadians(int units) {
            return (units >> 2 & 16383) * (2.0 * Math.PI / 16384.0);
        }
    }

    public enum TextureMapAxis {
        TEXMAPAXIS_XY(0),
        TEXMAPAXIS_XZ(1),
        TEXMAPAXIS_YZ(2),
        TEXMAPAXIS_MAX(3);
        private final int value;

        TextureMapAxis(int value) {
            this.value = value;
        }

        public static TextureMapAxis fromValue(int value) {
            return switch (value) {
                case 0 -> TEXMAPAXIS_XY;
                case 1 -> TEXMAPAXIS_XZ;
                case 2 -> TEXMAPAXIS_YZ;
                case 3 -> TEXMAPAXIS_MAX;
                default -> throw new IllegalArgumentException("Invalid value: " + value);
            };
        }

        public int getValue() {
            return this.value;
        }
    }

    public static class ULayer {
        private short textureMapAxis;
        private UObjectRef<URenderedMaterial, Optional> texture = UObjectRef.optional();
        private UObjectRef<UTexture, Optional> alphaMap = UObjectRef.optional();
        private float uScale;
        private float vScale;
        private float uPan;
        private float vPan;
        private float textureRotation;
        private URotator layerRotation = new URotator(0, 0, 0);
        private UMatrix terrainMatrix;
        private float friction;
        private float restitution;
        private UObjectRef<UTexture, Required> layerWeightMap = UObjectRef.required();
        private Vec3 scale;
        private int[] zoneRenderStates = new int[0];

        private ULayer(UArchive archive, UProperty property) {
            var layerProperties = property.getSubProperties().getFirst();
            if (layerProperties.get("Texture") instanceof UProperty material) {
                texture.fromProperty(archive, material);
            }
            if (layerProperties.get("AlphaMap") instanceof UProperty alpha) {
                alphaMap.fromProperty(archive, alpha);
            }
            if (layerProperties.get("UScale") instanceof UProperty u) {
                uScale = u.getFloatValue();
            }
            if (layerProperties.get("VScale") instanceof UProperty v) {
                vScale = v.getFloatValue();
            }
            scale = layerProperties.get("Scale") instanceof UProperty vector ? vector.getVectorValue() : new Vec3();
            if (layerProperties.get("UPan") instanceof UProperty pan) {
                uPan = pan.getFloatValue();
            }
            if (layerProperties.get("VPan") instanceof UProperty pan) {
                vPan = pan.getFloatValue();
            }
            if (layerProperties.get("TextureMapAxis") instanceof UProperty axis) {
                textureMapAxis = axis.getUInt8Value();
            }
            if (layerProperties.get("TextureRotation") instanceof UProperty rotation) {
                textureRotation = rotation.getFloatValue();
            }
            if (layerProperties.get("LayerRotation") instanceof UProperty rotation) {
                layerRotation = rotation.getRotatorValue();
            }
            zoneRenderStates = zoneStatesOf(layerProperties);
        }

        public short getTextureMapAxis() {
            return this.textureMapAxis;
        }

        public UObjectRef<URenderedMaterial, Optional> getTexture() {
            return this.texture;
        }

        public UObjectRef<UTexture, Optional> getAlphaMap() {
            return this.alphaMap;
        }

        public float getUScale() {
            return this.uScale;
        }

        public float getVScale() {
            return this.vScale;
        }

        public float getUPan() {
            return this.uPan;
        }

        public float getVPan() {
            return this.vPan;
        }

        public float getTextureRotation() {
            return this.textureRotation;
        }

        public URotator getLayerRotation() {
            return this.layerRotation;
        }

        public UMatrix getTerrainMatrix() {
            return this.terrainMatrix;
        }

        public float getFriction() {
            return this.friction;
        }

        public float getRestitution() {
            return this.restitution;
        }

        public UObjectRef<UTexture, Required> getLayerWeightMap() {
            return this.layerWeightMap;
        }

        public Vec3 getScale() {
            return this.scale;
        }

        public int[] getZoneRenderStates() {
            return zoneRenderStates.clone();
        }
    }

    public static class UDecorationLayer {
        private final UObjectRef<UTexture, Optional> scaleMap = UObjectRef.optional();
        private final UObjectRef<UTexture, Optional> densityMap = UObjectRef.optional();
        private final UObjectRef<UTexture, Optional> colorMap = UObjectRef.optional();
        private final UObjectRef<UStaticMesh, Optional> staticMesh = UObjectRef.optional();
        private int showOnTerrain = 1;
        private Vec3 scaleMin = new Vec3(1f, 1f, 1f);
        private Vec3 scaleMax = new Vec3(1f, 1f, 1f);
        private float fadeoutRadiusMin;
        private float fadeoutRadiusMax;
        private float densityMultiplierMin = 1f;
        private float densityMultiplierMax = 1f;
        private int maxPerQuad = 1;
        private int seed;
        private int alignToTerrain;
        private int drawOrder;
        private int showOnInvisibleTerrain;
        private int litDirectional;
        private int disregardTerrainLighting;
        private int randomYaw;
        private int detailMode;
        private final int[] zoneRenderStates;

        public UDecorationLayer(UArchive archive, Map<String, UProperty> properties) {
            readReference(archive, properties, "ScaleMap", scaleMap);
            readReference(archive, properties, "DensityMap", densityMap);
            readReference(archive, properties, "ColorMap", colorMap);
            readReference(archive, properties, "StaticMesh", staticMesh);
            showOnTerrain = readInteger(properties, "ShowOnTerrain", showOnTerrain);
            if (properties.get("ScaleMultiplier") instanceof UProperty scale) {
                scaleMin = rangeVector(scale, "Min", scaleMin);
                scaleMax = rangeVector(scale, "Max", scaleMax);
            }
            if (properties.get("FadeoutRadius") instanceof UProperty fade) {
                fadeoutRadiusMin = readRangeBound(fade, "Min", fadeoutRadiusMin);
                fadeoutRadiusMax = readRangeBound(fade, "Max", fadeoutRadiusMax);
            }
            if (properties.get("DensityMultiplier") instanceof UProperty density) {
                densityMultiplierMin = readRangeBound(density, "Min", densityMultiplierMin);
                densityMultiplierMax = readRangeBound(density, "Max", densityMultiplierMax);
            }
            maxPerQuad = readInteger(properties, "MaxPerQuad", maxPerQuad);
            seed = readInteger(properties, "Seed", seed);
            alignToTerrain = readInteger(properties, "AlignToTerrain", alignToTerrain);
            drawOrder = readInteger(properties, "DrawOrder", drawOrder);
            showOnInvisibleTerrain = readInteger(properties, "ShowOnInvisibleTerrain", showOnInvisibleTerrain);
            litDirectional = readInteger(properties, "LitDirectional", litDirectional);
            disregardTerrainLighting = readInteger(properties, "DisregardTerrainLighting", disregardTerrainLighting);
            randomYaw = readInteger(properties, "RandomYaw", randomYaw);
            detailMode = readInteger(properties, "DetailMode", detailMode);
            zoneRenderStates = zoneStatesOf(properties);
        }

        public UObjectRef<UTexture, Optional> getScaleMap() {
            return this.scaleMap;
        }

        public UObjectRef<UTexture, Optional> getDensityMap() {
            return this.densityMap;
        }

        public UObjectRef<UTexture, Optional> getColorMap() {
            return this.colorMap;
        }

        public UObjectRef<UStaticMesh, Optional> getStaticMesh() {
            return this.staticMesh;
        }

        public int getShowOnTerrain() {
            return this.showOnTerrain;
        }

        public Vec3 getScaleMin() {
            return this.scaleMin;
        }

        public Vec3 getScaleMax() {
            return this.scaleMax;
        }

        public float getFadeoutRadiusMin() {
            return this.fadeoutRadiusMin;
        }

        public float getFadeoutRadiusMax() {
            return this.fadeoutRadiusMax;
        }

        public float getDensityMultiplierMin() {
            return this.densityMultiplierMin;
        }

        public float getDensityMultiplierMax() {
            return this.densityMultiplierMax;
        }

        public int getMaxPerQuad() {
            return this.maxPerQuad;
        }

        public int getSeed() {
            return this.seed;
        }

        public int getAlignToTerrain() {
            return this.alignToTerrain;
        }

        public int getDrawOrder() {
            return this.drawOrder;
        }

        public int getShowOnInvisibleTerrain() {
            return this.showOnInvisibleTerrain;
        }

        public int getLitDirectional() {
            return this.litDirectional;
        }

        public int getDisregardTerrainLighting() {
            return this.disregardTerrainLighting;
        }

        public int getRandomYaw() {
            return this.randomYaw;
        }

        public int getDetailMode() {
            return this.detailMode;
        }

        public int[] getZoneRenderStates() {
            return zoneRenderStates.clone();
        }

        private static void readReference(
                UArchive archive, Map<String, UProperty> properties, String name, UObjectRef<?, Optional> target) {
            var property = properties.get(name);
            if (property != null) {
                target.fromProperty(archive, property);
            }
        }

        private static int readInteger(Map<String, UProperty> properties, String name, int fallback) {
            var property = properties.get(name);
            return property == null ? fallback : property.getInt32Value();
        }

        private static float readRangeBound(UProperty property, String bound, float fallback) {
            try {
                return property.subProperty(bound).getFloatValue();
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }

        private static Vec3 rangeVector(UProperty property, String bound, Vec3 fallback) {
            try {
                return new Vec3(
                        readRangeBound(property.subProperty("X"), bound, fallback.getX()),
                        readRangeBound(property.subProperty("Y"), bound, fallback.getY()),
                        readRangeBound(property.subProperty("Z"), bound, fallback.getZ()));
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
    }

    public static class UTerrainSector extends UInfoActor {
        private static final int SECTOR_QUADS = 16;
        private static final int SECTOR_VERTICES = SECTOR_QUADS + 1;

        private static final byte[][] EMPTY_LIGHT_MAPS = {new byte[0]};
        private long offsetX;
        private long offsetY;
        private int sectorX;
        private int sectorY;
        private long cellNum;
        private long sectorWidth;
        private int terrainInfoRef;
        private int sectorSizeX;
        private int sectorSizeY;
        private int terrainOffsetX;
        private int terrainOffsetY;

        private TerrainLightInfo[] staticLights = new TerrainLightInfo[0];
        private byte[][] lightMaps = EMPTY_LIGHT_MAPS;

        public UTerrainSector(UArchive archive) {
            super(archive);
        }

        public static final class TerrainLightInfo {
            private final UArchive archive;
            private final int lightIndex;
            private final String lightName;
            private final byte[] vertexVisibility;

            private TerrainLightInfo(UArchive archive) {
                this.archive = archive;
                lightIndex = archive.readIndex();
                lightName = archive.getObjectLoader().peekObjectFullName(lightIndex);
                vertexVisibility = archive.readByteArray();
            }

            public LightActor getLight() {
                var object = archive.getObjectLoader().loadObject(lightIndex);
                return object instanceof LightActor light ? light : null;
            }

            public boolean isVertexVisible(int vertex) {
                if (vertex < 0) {
                    return false;
                }
                var byteIndex = vertex >>> 3;
                return byteIndex < vertexVisibility.length
                        && (Byte.toUnsignedInt(vertexVisibility[byteIndex]) & (1 << (vertex & 7))) != 0;
            }

            public UArchive getArchive() {
                return this.archive;
            }

            public int getLightIndex() {
                return this.lightIndex;
            }

            public String getLightName() {
                return this.lightName;
            }

            public byte[] getVertexVisibility() {
                return this.vertexVisibility;
            }
        }

        public byte[] getLightMap0() {
            return lightMaps[0];
        }

        public byte[] getLightMap(int variant) {
            return variant >= 0 && variant < lightMaps.length ? lightMaps[variant] : lightMaps[0];
        }

        public int getLightMapCount() {
            return lightMaps.length;
        }

        @Override
        protected void deserialize() {
            super.deserialize();

            var fileVersion = archive.getHeader().getFileVersion();
            var licenseVersion = archive.getHeader().getLicenseVersion();
            if (fileVersion >= 117 && licenseVersion > 3) {
                deserializeHfNativeTail(licenseVersion);
                return;
            }

            deserializeLegacyHeuristic();
        }

        public long getOffsetX() {
            return this.offsetX;
        }

        public long getOffsetY() {
            return this.offsetY;
        }

        public int getSectorX() {
            return this.sectorX;
        }

        public int getSectorY() {
            return this.sectorY;
        }

        public long getCellNum() {
            return this.cellNum;
        }

        public long getSectorWidth() {
            return this.sectorWidth;
        }

        public int getTerrainInfoRef() {
            return this.terrainInfoRef;
        }

        public int getSectorSizeX() {
            return this.sectorSizeX;
        }

        public int getSectorSizeY() {
            return this.sectorSizeY;
        }

        public int getTerrainOffsetX() {
            return this.terrainOffsetX;
        }

        public int getTerrainOffsetY() {
            return this.terrainOffsetY;
        }

        public TerrainLightInfo[] getStaticLights() {
            return this.staticLights;
        }

        public byte[][] getLightMaps() {
            return this.lightMaps;
        }

        private void deserializeHfNativeTail(int licenseVersion) {
            terrainInfoRef = archive.readIndex();
            sectorSizeX = archive.readInt();
            sectorSizeY = archive.readInt();
            terrainOffsetX = archive.readInt();
            terrainOffsetY = archive.readInt();

            offsetX = terrainOffsetX * 256L;
            offsetY = terrainOffsetY * 256L;
            sectorX = Math.floorDiv(terrainOffsetX, SECTOR_QUADS);
            sectorY = Math.floorDiv(terrainOffsetY, SECTOR_QUADS);
            cellNum = (long) SECTOR_QUADS * SECTOR_QUADS;
            sectorWidth = SECTOR_QUADS * 128L;

            UBox.of(archive);

            var staticLightCount = archive.readIndex();
            if (staticLightCount < 0 || staticLightCount > 65_536) {
                throw new IllegalStateException("Invalid terrain static-light count: " + staticLightCount);
            }
            staticLights = new TerrainLightInfo[staticLightCount];
            for (var i = 0; i < staticLightCount; i++) {
                staticLights[i] = new TerrainLightInfo(archive);
            }

            var hasTimeMaps = archive.readInt();
            if (hasTimeMaps != 0 && terrainInfoRef != 0) {
                var mapCount = archive.readInt();
                if (mapCount < 0 || mapCount > 64) {
                    throw new IllegalStateException("Invalid terrain time-map count: " + mapCount);
                }
                var expectedSize = SECTOR_VERTICES * SECTOR_VERTICES;
                var maps = new ArrayList<byte[]>(mapCount);
                for (var i = 0; i < mapCount; i++) {
                    var map = archive.readByteArray();
                    if (map.length != expectedSize) {
                        throw new IllegalStateException(
                                "Invalid terrain time-map size " + map.length + ", expected " + expectedSize);
                    }
                    maps.add(map);
                }
                lightMaps = maps.isEmpty() ? EMPTY_LIGHT_MAPS : maps.toArray(new byte[0][]);
            }

            if (licenseVersion >= 8) {
                for (var i = 0; i < 32; i++) {
                    archive.readUInt16();
                }
            }
            if (licenseVersion >= 10) {
                archive.readUInt16Array();
            }
        }

        private void deserializeLegacyHeuristic() {

            archive.skipBytes(1);
            archive.skipBytes(7);

            offsetX = archive.readUInt32();
            offsetY = archive.readUInt32();

            sectorX = (int) (offsetX / 2 / 2048);
            sectorY = (int) (offsetY / 2 / 2048);

            archive.skipBytes(1);

            var boundingBox = UBox.of(archive);

            cellNum = archive.readUInt32();
            sectorWidth = archive.readUInt32();

            if (sectorX > 255 || sectorY > 255) {
                archive.skipBytes(-42);
                archive.skipBytes(1);

                offsetX = archive.readUInt32();
                offsetY = archive.readUInt32();
                sectorX = (int) (offsetX / 2 / 2048);
                sectorY = (int) (offsetY / 2 / 2048);

                archive.skipBytes(1);

                boundingBox = UBox.of(archive);

                cellNum = archive.readUInt32();
                sectorWidth = archive.readUInt32();

                if (sectorX > 255 || sectorY > 255) {
                    throw new IllegalStateException("Sector width is greater than 2048: " + sectorWidth);
                }
            }

            archive.skipBytes(1);

            int index = 0;
            for (int i = 0; i < 4000 && archive.hasMoreBytes(2); i++) {
                if (i > 0) {
                    archive.skipBytes(1);
                }
                if ((index = archive.readIndex()) == 289) {
                    archive.skipBytes(-2);
                    break;
                }
            }
            if (index != 289) {
                return;
            }

            var base = archive.readByteArray();
            var maps = new ArrayList<byte[]>(8);
            maps.add(base);
            while (maps.size() < 8 && archive.hasMoreBytes(2)) {
                var size = archive.readIndex();
                if (size != base.length || size <= 0 || !archive.hasMoreBytes(size)) {
                    break;
                }
                var variant = new byte[size];
                for (var j = 0; j < size; j++) {
                    variant[j] = archive.readByte();
                }
                maps.add(variant);
            }
            lightMaps = maps.toArray(new byte[0][]);
        }
    }

    public static class UTerrainInfoActor extends UInfoActor {
        private static final Vec3 DEFAULT_SCALE = new Vec3(128.0f, 128.0f, 76.0f / 256.0f);

        private final UObjectRef<UTexture, Required> terrainMap = UObjectRef.required();
        private Vec3 terrainScale;
        private final List<ULayer> layers = new ArrayList<>();
        private final List<UDecorationLayer> decoLayers = new ArrayList<>();
        private final List<UTerrainSector> sectors = new ArrayList<>();
        private final List<UObjectRef<UTerrainSector, Optional>> nativeSectorRefs = new ArrayList<>();
        private UBitSet quadVisibilityBitmap;
        private UBitSet edgeTurnBitmap;
        private float decoLayerOffset;
        private int mapX;
        private int mapY;
        private TerrainCoords toWorld;
        private TerrainCoords toHeightMap;
        private int nativeSectorsX;
        private int nativeSectorsY;
        private int nativeHeightmapX;
        private int nativeHeightmapY;
        private boolean inverted;

        public UTerrainInfoActor(UArchive archive) {
            super(archive);
            this.terrainScale = VEC_3_EMPTY;
        }

        @Override
        protected void deserialize() {
            super.deserialize();

            if (archive.getHeader().getLicenseVersion() < 17) {
                return;
            }
            var tailStart = archive.getOffset();
            try {
                var sectorCount = archive.readIndex();
                if (sectorCount <= 0 || sectorCount > 4096) {
                    throw new IllegalStateException("implausible TerrainInfo sector count " + sectorCount);
                }
                var parsedSectorRefs = new ArrayList<UObjectRef<UTerrainSector, Optional>>(sectorCount);
                for (var i = 0; i < sectorCount; i++) {
                    var sectorRef = UObjectRef.<UTerrainSector>optional();
                    sectorRef.fromArchive(archive);
                    parsedSectorRefs.add(sectorRef);
                }
                var sectorsX = archive.readInt();
                var sectorsY = archive.readInt();
                if (sectorsX <= 0
                        || sectorsY <= 0
                        || sectorsX > 256
                        || sectorsY > 256
                        || sectorsX * sectorsY != sectorCount) {
                    throw new IllegalStateException("invalid TerrainInfo sector grid " + sectorsX + "x" + sectorsY);
                }
                var parsedToWorld = TerrainCoords.read(archive);
                var parsedToHeightMap = TerrainCoords.read(archive);
                var heightmapX = archive.readInt();
                var heightmapY = archive.readInt();
                if (heightmapX <= 0 || heightmapY <= 0 || heightmapX > 4096 || heightmapY > 4096) {
                    throw new IllegalStateException("invalid TerrainInfo heightmap " + heightmapX + "x" + heightmapY);
                }
                nativeSectorsX = sectorsX;
                nativeSectorsY = sectorsY;
                nativeSectorRefs.clear();
                nativeSectorRefs.addAll(parsedSectorRefs);
                toWorld = parsedToWorld;
                toHeightMap = parsedToHeightMap;
                nativeHeightmapX = heightmapX;
                nativeHeightmapY = heightmapY;
            } catch (RuntimeException ignored) {
                archive.setPosition(tailStart);
                nativeSectorRefs.clear();
                nativeSectorsX = 0;
                nativeSectorsY = 0;
                toWorld = null;
                toHeightMap = null;
                nativeHeightmapX = 0;
                nativeHeightmapY = 0;
            }
        }

        public List<UTerrainSector> resolveNativeSectors() {
            if (nativeSectorRefs.isEmpty()) {
                return List.of();
            }
            var resolved = new ArrayList<UTerrainSector>(nativeSectorRefs.size());
            for (var ref : nativeSectorRefs) {
                var sector = ref.get();
                if (sector == null) {
                    return List.of();
                }
                resolved.add(sector);
            }
            return resolved;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "TerrainMap" -> {
                    terrainMap.fromProperty(archive, property);
                    yield true;
                }
                case "TerrainScale" -> {
                    terrainScale = property.getVectorValue();
                    yield true;
                }
                case "QuadVisibilityBitmap" -> {
                    quadVisibilityBitmap = new UBitSet(property.getDataValue());
                    yield true;
                }
                case "EdgeTurnBitmap" -> {
                    edgeTurnBitmap = new UBitSet(property.getDataValue());
                    yield true;
                }
                case "MapX" -> {
                    mapX = property.getInt32Value();
                    yield true;
                }
                case "MapY" -> {
                    mapY = property.getInt32Value();
                    yield true;
                }
                case "Layers" -> {
                    layers.add(new ULayer(archive, property));
                    yield true;
                }
                case "DecoLayers" -> {
                    for (var properties : property.getSubProperties()) {
                        decoLayers.add(new UDecorationLayer(archive, properties));
                    }
                    yield true;
                }
                case "DecoLayerOffset" -> {
                    decoLayerOffset = property.getFloatValue();
                    yield true;
                }
                case "Inverted", "bInverted" -> {
                    inverted = property.isTrue();
                    yield true;
                }
                default -> false;
            };
        }

        public boolean hasBrokenScale() {
            return terrainScale.getX() == 0.0f || terrainScale.getY() == 0.0f || terrainScale.getZ() == 0.0f;
        }

        public boolean hasHeightmap() {
            return !terrainMap.isNull();
        }

        public boolean hasSurface() {
            return !hasBrokenScale() && hasHeightmap();
        }

        public Vec3 getScale() {
            if (!hasBrokenScale()) {
                return new Vec3(terrainScale.getX(), terrainScale.getY(), terrainScale.getZ() / 256.0f);
            }
            return DEFAULT_SCALE;
        }

        public Vec3 getPosition() {
            var width = heightmapWidth();
            var height = heightmapHeight();
            var scale = getScale();
            if (hasSurface()) {
                return new Vec3(
                        location.getX() - width / 2.0f * scale.getX(),
                        location.getY() - height / 2.0f * scale.getY(),
                        location.getZ() - 32768.0f * scale.getZ());
            }
            return new Vec3((float) (mapX - 20) * width * 128.0f, (float) (mapY - 18) * height * 128.0f, 0.0f);
        }

        public UBox getBoundingBox() {
            var width = heightmapWidth();
            var height = heightmapHeight();
            var position = getPosition();
            var scale = getScale();
            return new UBox(
                    new Vec3(0f, 0f, -(16384.0f + position.getZ()) / scale.getZ()),
                    new Vec3(width, height, (16384.0f - position.getZ()) / scale.getZ()),
                    true);
        }

        public UObjectRef<UTexture, Required> getTerrainMap() {
            return this.terrainMap;
        }

        public Vec3 getTerrainScale() {
            return this.terrainScale;
        }

        public List<ULayer> getLayers() {
            return this.layers;
        }

        public List<UDecorationLayer> getDecoLayers() {
            return this.decoLayers;
        }

        public List<UTerrainSector> getSectors() {
            return this.sectors;
        }

        public List<UObjectRef<UTerrainSector, Optional>> getNativeSectorRefs() {
            return this.nativeSectorRefs;
        }

        public UBitSet getQuadVisibilityBitmap() {
            return this.quadVisibilityBitmap;
        }

        public UBitSet getEdgeTurnBitmap() {
            return this.edgeTurnBitmap;
        }

        public float getDecoLayerOffset() {
            return this.decoLayerOffset;
        }

        public int getMapX() {
            return this.mapX;
        }

        public int getMapY() {
            return this.mapY;
        }

        public TerrainCoords getToWorld() {
            return this.toWorld;
        }

        public TerrainCoords getToHeightMap() {
            return this.toHeightMap;
        }

        public TerrainCoords getNativeToWorld() {
            return toWorld != null && toHeightMap != null ? toWorld : calculatedToWorld();
        }

        public TerrainCoords getNativeToHeightMap() {
            if (toWorld != null && toHeightMap != null) {
                return toHeightMap;
            }
            var world = calculatedToWorld();
            var origin = world.origin();
            var scaleX = world.xAxis().getX();
            var scaleY = world.yAxis().getY();
            var scaleZ = world.zAxis().getZ();
            return new TerrainCoords(
                    new Vec3(-origin.getX() * scaleX, -origin.getY() * scaleY, -origin.getZ() * scaleZ),
                    new Vec3(1f / scaleX, 0f, 0f),
                    new Vec3(0f, 1f / scaleY, 0f),
                    new Vec3(0f, 0f, 1f / scaleZ));
        }

        public int getNativeSectorsX() {
            return this.nativeSectorsX;
        }

        public int getNativeSectorsY() {
            return this.nativeSectorsY;
        }

        public int getNativeHeightmapX() {
            return this.nativeHeightmapX;
        }

        public int getNativeHeightmapY() {
            return this.nativeHeightmapY;
        }

        public boolean isInverted() {
            return this.inverted;
        }

        private int heightmapWidth() {
            return hasHeightmap() ? terrainMap.get().getUSize() : 256;
        }

        private int heightmapHeight() {
            return hasHeightmap() ? terrainMap.get().getVSize() : 256;
        }

        private TerrainCoords calculatedToWorld() {
            var scale = getScale();
            var originX = -location.getX() / scale.getX();
            var originY = -location.getY() / scale.getY();
            var originZ = -location.getZ() / scale.getZ();
            if (hasHeightmap()) {
                originX += heightmapWidth() / 2;
                originY += heightmapHeight() / 2;
                originZ += 32767f;
            }
            return new TerrainCoords(
                    new Vec3(originX, originY, originZ),
                    new Vec3(scale.getX(), 0f, 0f),
                    new Vec3(0f, scale.getY(), 0f),
                    new Vec3(0f, 0f, scale.getZ()));
        }
    }

    private static int[] zoneStatesOf(Map<String, UProperty> properties) {
        var states = properties.get("ZoneRenderState") instanceof UProperty property ? property.getIntArray() : null;
        return states == null ? new int[0] : states.clone();
    }
}

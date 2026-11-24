package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UActor.LightActor;

public class UStaticMeshInstance extends UObject {
    public static final int BEAST_LIGHTMAP_LICENSE = 38;

    private ColorStream colorStream;
    private LightInfo[] sceneLights = new LightInfo[0];
    private LightInfo[] environmentLights = new LightInfo[0];
    private int lodInstance1;
    private int lodInstance2;

    private int unreadNativeBytes;

    private int[] beastLightmapIndices = new int[0];
    private int beastLightmapWidth;
    private int beastLightmapHeight;

    public UStaticMeshInstance(UArchive archive) {
        super(archive);
    }

    public static final class ColorStream {
        private final int size;
        private final byte[] colors;
        private final int revision;

        private ColorStream(UArchive archive) {
            size = archive.readIndex();
            colors = new byte[Math.multiplyExact(size, 4)];
            for (var i = 0; i < colors.length; i++) {
                colors[i] = archive.readByte();
            }
            revision = archive.readInt();
        }

        public int size() {
            return size;
        }

        public float getRed(int vertex) {
            return (colors[vertex * 4] & 0xff) / 255f;
        }

        public float getGreen(int vertex) {
            return (colors[vertex * 4 + 1] & 0xff) / 255f;
        }

        public float getBlue(int vertex) {
            return (colors[vertex * 4 + 2] & 0xff) / 255f;
        }

        public float getAlpha(int vertex) {
            return Byte.toUnsignedInt(getAlphaByte(vertex)) / 255f;
        }

        public byte getAlphaByte(int vertex) {
            return colors[vertex * 4 + 3];
        }

        public int getSize() {
            return this.size;
        }

        public byte[] getColors() {
            return this.colors;
        }

        public int getRevision() {
            return this.revision;
        }
    }

    public static final class LightInfo {
        private final UArchive archive;
        private final int lightIndex;
        private final String lightName;
        private final short[] vertexFlags;
        private final int applied;

        private LightInfo(UArchive archive) {
            this.archive = archive;
            lightIndex = archive.readIndex();
            lightName = archive.getObjectLoader().peekObjectFullName(lightIndex);
            vertexFlags = archive.readUInt8Array();
            applied = archive.readInt();
        }

        public LightActor getLight() {
            var object = archive.getObjectLoader().loadObject(lightIndex);
            return object instanceof LightActor light ? light : null;
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

        public short[] getVertexFlags() {
            return this.vertexFlags;
        }

        public boolean isApplied() {
            return this.applied != 0;
        }

        public int getAppliedValue() {
            return applied;
        }
    }

    @Override
    protected void deserialize() {
        super.deserialize();

        var fileVersion = archive.getHeader().getFileVersion();
        var licenseVersion = archive.getHeader().getLicenseVersion();
        if (fileVersion < 0x70) {
            throw new IllegalStateException("Unsupported pre-UE2 StaticMeshInstance version: " + fileVersion);
        }

        colorStream = new ColorStream(archive);
        if (fileVersion > 0x6d) {
            sceneLights = archive.readArray(LightInfo.class, LightInfo::new);
        }
        if (licenseVersion > 0x03) {
            environmentLights = archive.readArray(LightInfo.class, LightInfo::new);
        }
        if (licenseVersion > 0x0b) {
            lodInstance1 = archive.readIndex();
            lodInstance2 = archive.readIndex();
        }
        if (licenseVersion >= BEAST_LIGHTMAP_LICENSE && archive.getRemainingInObject() >= 9) {
            var count = archive.readIndex();
            beastLightmapIndices = new int[Math.max(0, count)];
            for (var i = 0; i < beastLightmapIndices.length; i++) {
                beastLightmapIndices[i] = archive.readIndex();
            }
            beastLightmapWidth = archive.readInt();
            beastLightmapHeight = archive.readInt();
        }
        unreadNativeBytes = archive.getRemainingInObject();
    }

    public UStaticMeshInstance resolveLodInstance1() {
        return resolveLodInstance(lodInstance1);
    }

    public UStaticMeshInstance resolveLodInstance2() {
        return resolveLodInstance(lodInstance2);
    }

    public ColorStream getColorStream() {
        return this.colorStream;
    }

    public LightInfo[] getSceneLights() {
        return this.sceneLights;
    }

    public LightInfo[] getEnvironmentLights() {
        return this.environmentLights;
    }

    public int getLodInstance1() {
        return this.lodInstance1;
    }

    public int getLodInstance2() {
        return this.lodInstance2;
    }

    public UArchive getArchive() {
        return archive;
    }

    public int[] getBeastLightmapIndices() {
        return beastLightmapIndices;
    }

    public boolean usesBeastLighting(boolean lightmapsEnabled) {
        return lightmapsEnabled && beastLightmapIndices.length != 0;
    }

    public int getBeastLightmapWidth() {
        return beastLightmapWidth;
    }

    public int getBeastLightmapHeight() {
        return beastLightmapHeight;
    }

    public int getUnreadNativeBytes() {
        return this.unreadNativeBytes;
    }

    private UStaticMeshInstance resolveLodInstance(int index) {
        if (index == 0) {
            return null;
        }
        var object = archive.getObjectLoader().loadObject(index);
        return object instanceof UStaticMeshInstance instance ? instance : null;
    }
}

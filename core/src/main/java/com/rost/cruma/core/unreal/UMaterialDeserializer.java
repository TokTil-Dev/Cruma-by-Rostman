package com.rost.cruma.core.unreal;

public class UMaterialDeserializer {
    public static class UMaterialStageProperty {
        private final String unk1;
        private final String[] unk2;

        private UMaterialStageProperty(UArchive archive) {
            unk1 = archive.readString();
            unk2 = archive.readArray(String.class, UArchive::readString);
        }

        public String getUnk1() {
            return this.unk1;
        }

        public String[] getUnk2() {
            return this.unk2;
        }
    }

    public static class UShaderProperty {
        private final short b1;
        private final short b2;
        private final short[] b3 = new short[5];
        private final short[] b4 = new short[5];
        private final short[] b5 = new short[5];
        private final short[] b6 = new short[5];
        private final short[] b7 = new short[5];
        private final short[] b8 = new short[5];

        private final long[] i1 = new long[5];
        private final long[] i2 = new long[5];
        private final long[] i3 = new long[5];
        private final short[] be = new short[8];
        private final long[] ie = new long[3];
        private final UMaterialStageProperty[] stages;

        private UShaderProperty(UArchive archive) {
            var fileVersion = archive.getHeader().getFileVersion();
            b1 = archive.readUInt8();
            b2 = archive.readUInt8();

            if (fileVersion < 129) {
                b3[0] = archive.readUInt8();
                b4[0] = archive.readUInt8();
                i1[0] = archive.readUInt32();
                i2[0] = archive.readUInt32();
                i3[0] = archive.readUInt32();
            } else if (fileVersion == 129) {
                for (var i = 0; i < 5; ++i) {
                    b3[i] = archive.readUInt8();
                    b4[i] = archive.readUInt8();
                    i1[i] = archive.readUInt32();
                    i2[i] = archive.readUInt32();
                    i3[i] = archive.readUInt32();
                }
            } else {
                for (var i = 0; i < 5; ++i) {
                    b3[i] = archive.readUInt8();
                    b4[i] = archive.readUInt8();
                    b5[i] = archive.readUInt8();
                    b6[i] = archive.readUInt8();
                    b7[i] = archive.readUInt8();
                    b8[i] = archive.readUInt8();
                    i2[i] = archive.readUInt32();
                    i3[i] = archive.readUInt32();
                }
            }

            for (var i = 0; i < 8; ++i) {
                be[i] = archive.readUInt8();
            }

            for (var i = 0; i < 3; ++i) {
                ie[i] = archive.readUInt32();
            }

            stages = archive.readArray(UMaterialStageProperty.class, UMaterialStageProperty::new);
        }

        public short getB1() {
            return this.b1;
        }

        public short getB2() {
            return this.b2;
        }

        public short[] getB3() {
            return this.b3;
        }

        public short[] getB4() {
            return this.b4;
        }

        public short[] getB5() {
            return this.b5;
        }

        public short[] getB6() {
            return this.b6;
        }

        public short[] getB7() {
            return this.b7;
        }

        public short[] getB8() {
            return this.b8;
        }

        public long[] getI1() {
            return this.i1;
        }

        public long[] getI2() {
            return this.i2;
        }

        public long[] getI3() {
            return this.i3;
        }

        public short[] getBe() {
            return this.be;
        }

        public long[] getIe() {
            return this.ie;
        }

        public UMaterialStageProperty[] getStages() {
            return this.stages;
        }
    }

    public void deserialize(UArchive archive) {
        var licenseVersion = archive.getHeader().getLicenseVersion();
        var fileVersion = archive.getHeader().getFileVersion();

        if (fileVersion >= 123 && licenseVersion >= 16 && licenseVersion < 37) {
            var unk1 = archive.readUInt32();
        }

        if (fileVersion >= 123 && licenseVersion >= 30 && licenseVersion < 37) {
            {
                short materialInfo = 0;
                if (licenseVersion >= 33 && licenseVersion < 36) {
                    materialInfo = archive.readUInt8();
                }
                var textureTransform = archive.readUInt8();
                var maxSamplerNum = archive.readUInt8();
                var maxTexmatNum = archive.readUInt8();
                var maxPassNum = archive.readUInt8();
                var twoPassRenderState = archive.readUInt8();
                var alphaRef = archive.readUInt8();

                var srcBlend = archive.readUInt32();
                var destBlend = archive.readUInt32();
                var overriddenFogColor = archive.readUInt32();

                for (var i = 0; i < 8; i++) {
                    var b1 = archive.readUInt8();
                    if (licenseVersion < 36) {
                        var b2 = archive.readUInt8();
                    }
                    for (var j = 0; j < 126; ++j) {
                        var b3 = archive.readUInt8();
                    }
                }

                {
                    var c = new short[8];
                    c[2] = archive.readUInt8();
                    c[1] = archive.readUInt8();
                    c[0] = archive.readUInt8();
                    c[3] = archive.readUInt8();
                    c[6] = archive.readUInt8();
                    c[5] = archive.readUInt8();
                    c[4] = archive.readUInt8();
                    c[7] = archive.readUInt8();

                    var fcFadePeriod = archive.readUInt32();
                    var fcFadePhase = archive.readUInt32();
                    var fcColorFadeType = archive.readUInt32();
                }

                for (var i = 0; i < 16; ++i) {
                    var strTex = archive.readString();
                }
            }

            var shaderCode = archive.readString();
        }

        if (fileVersion >= 123 && licenseVersion >= 37) {
            var shaderProperty = new UShaderProperty(archive);
            var shaderCode = archive.readString();
        }
        if (fileVersion >= 123 && licenseVersion >= 31) {
            var ver1 = archive.readUInt16();
            var ver2 = archive.readUInt16();
        }
    }
}

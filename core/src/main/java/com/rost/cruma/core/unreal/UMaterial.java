package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.UObjectRef.Required;
import com.rost.cruma.core.unreal.primitives.UColor;
import com.rost.cruma.core.unreal.primitives.UColorFade;
import com.rost.cruma.core.unreal.primitives.URotator;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class UMaterial extends UObject {
    private static final Set<String> REPORTED_MISSING_MIPS = ConcurrentHashMap.newKeySet();

    private static final Logger log = LogManager.getLogger(UMaterial.class);

    protected final UObjectRef<UMaterial, Optional> fallbackMaterial = UObjectRef.optional();
    protected final UObjectRef<UMaterial, Required> defaultMaterial = UObjectRef.required();

    public UMaterial(UArchive archive) {
        super(archive);
    }

    public enum EColorOperation {
        CO_Use_Color_From_Material1(0),
        CO_Use_Color_From_Material2(1),
        CO_Multiply(2),
        CO_Add(3),
        CO_Subtract(4),
        CO_AlphaBlend_With_Mask(5),
        CO_Add_With_Mask_Modulation(6),
        CO_Use_Color_From_Mask(7);
        private final int id;

        EColorOperation(int id) {
            this.id = id;
        }

        public static EColorOperation fromId(int id) {
            for (var type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown type id: " + id);
        }

        public int getId() {
            return this.id;
        }
    }

    public enum EAlphaOperation {
        AO_Use_Mask(0),
        AO_Multiply(1),
        AO_Add(2),
        AO_Use_Alpha_From_Material1(3),
        AO_Use_Alpha_From_Material2(4);
        private final int id;

        EAlphaOperation(int id) {
            this.id = id;
        }

        public static EAlphaOperation fromId(int id) {
            for (var type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown type id: " + id);
        }

        public int getId() {
            return this.id;
        }
    }

    public static class UCombiner extends UMaterial {
        private final UObjectRef<UMaterial, Optional> material1 = UObjectRef.optional();
        private final UObjectRef<UMaterial, Optional> material2 = UObjectRef.optional();
        private final UObjectRef<UMaterial, Optional> mask = UObjectRef.optional();
        private EColorOperation combineOperation = EColorOperation.CO_Use_Color_From_Material1;
        private EAlphaOperation alphaOperation = EAlphaOperation.AO_Use_Mask;
        private boolean invertMask;
        private boolean modulate2X;
        private boolean modulate4X;

        public UCombiner(UArchive archive) {
            super(archive);
        }

        public float getModulateFactor() {
            return modulate4X ? 4f : modulate2X ? 2f : 1f;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            if (property.getName().equals("Material1")) {
                material1.fromProperty(archive, property);
                return true;
            }
            if (property.getName().equals("Material2")) {
                material2.fromProperty(archive, property);
                return true;
            }
            if (property.getName().equals("AlphaOperation")) {
                alphaOperation = EAlphaOperation.fromId(property.getUInt8Value());
                return true;
            }
            if (property.getName().equals("CombineOperation")) {
                combineOperation = EColorOperation.fromId(property.getUInt8Value());
                return true;
            }
            if (property.getName().equals("Mask")) {
                mask.fromProperty(archive, property);
                return true;
            }
            if (property.getName().equals("InvertMask")) {
                invertMask = property.isTrue();
                return true;
            }
            if (property.getName().equals("Modulate2X")) {
                modulate2X = property.isTrue();
                return true;
            }
            if (property.getName().equals("Modulate4X")) {
                modulate4X = property.isTrue();
                return true;
            }
            return false;
        }

        public UObjectRef<UMaterial, Optional> getMaterial1() {
            return this.material1;
        }

        public UObjectRef<UMaterial, Optional> getMaterial2() {
            return this.material2;
        }

        public UObjectRef<UMaterial, Optional> getMask() {
            return this.mask;
        }

        public EColorOperation getCombineOperation() {
            return this.combineOperation;
        }

        public EAlphaOperation getAlphaOperation() {
            return this.alphaOperation;
        }

        public boolean isInvertMask() {
            return this.invertMask;
        }

        public boolean isModulate2X() {
            return this.modulate2X;
        }

        public boolean isModulate4X() {
            return this.modulate4X;
        }
    }

    public static class UModifier extends UMaterial {
        private final UObjectRef<UMaterial, Optional> material = UObjectRef.optional();
        private int texCoordSource = 15;
        private int texCoordCount;
        private boolean texCoordProjected;

        public UModifier(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            if (property.getName().equals("Material")) {
                material.fromProperty(archive, property);
                return true;
            }
            switch (property.getName()) {
                case "TexCoordSource" -> {
                    texCoordSource = property.getUInt8Value();
                    return true;
                }
                case "TexCoordCount" -> {
                    texCoordCount = property.getUInt8Value();
                    return true;
                }
                case "TexCoordProjected" -> {
                    texCoordProjected = property.isTrue();
                    return true;
                }
            }
            return false;
        }

        public UObjectRef<UMaterial, Optional> getMaterial() {
            return this.material;
        }

        public int getTexCoordSource() {
            return texCoordSource;
        }

        public int getTexCoordCount() {
            return texCoordCount;
        }

        public boolean isTexCoordProjected() {
            return texCoordProjected;
        }
    }

    public static class UOpacityModifier extends UModifier {
        private final UObjectRef<UMaterial, Optional> opacity = UObjectRef.optional();
        private boolean overrideTexModifier;

        public UOpacityModifier(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            switch (property.getName()) {
                case "Opacity" -> opacity.fromProperty(archive, property);
                case "bOverrideTexModifier" -> overrideTexModifier = property.isTrue();
                default -> {
                    return false;
                }
            }
            return true;
        }

        public UObjectRef<UMaterial, Optional> getOpacity() {
            return opacity;
        }

        public boolean isOverrideTexModifier() {
            return overrideTexModifier;
        }
    }

    public static class UTexPanner extends UModifier {
        private float panRate = 0.1f;
        private URotator panDirection = new URotator(0, 0, 0);

        public UTexPanner(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "PanRate" -> {
                    panRate = property.getFloatValue();
                    yield true;
                }
                case "PanDirection" -> {
                    panDirection = property.getRotatorValue();
                    yield true;
                }
                default -> false;
            };
        }

        public float getPanRate() {
            return this.panRate;
        }

        public URotator getPanDirection() {
            return this.panDirection;
        }
    }

    public static class UTexRotator extends UModifier {
        private URotator rotationRate = new URotator(0, 0, 0);
        private URotator rotation = new URotator(0, 0, 0);
        private int texRotationType;
        private float uOffset;
        private float vOffset;
        private URotator oscillationRate = new URotator(0, 0, 0);
        private URotator oscillationAmplitude = new URotator(0, 0, 0);
        private URotator oscillationPhase = new URotator(0, 0, 0);

        public UTexRotator(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "UOffset" -> {
                    uOffset = property.getFloatValue();
                    yield true;
                }
                case "VOffset" -> {
                    vOffset = property.getFloatValue();
                    yield true;
                }
                case "RotationRate" -> {
                    rotationRate = property.getRotatorValue();
                    yield true;
                }
                case "Rotation" -> {
                    rotation = property.getRotatorValue();
                    yield true;
                }
                case "TexRotationType" -> {
                    texRotationType = property.getUInt8Value();
                    yield true;
                }
                case "OscillationRate" -> {
                    oscillationRate = property.getRotatorValue();
                    yield true;
                }
                case "OscillationAmplitude" -> {
                    oscillationAmplitude = property.getRotatorValue();
                    yield true;
                }
                case "OscillationPhase" -> {
                    oscillationPhase = property.getRotatorValue();
                    yield true;
                }
                default -> false;
            };
        }

        public float getUOffset() {
            return uOffset;
        }

        public float getVOffset() {
            return vOffset;
        }

        public URotator getRotationRate() {
            return this.rotationRate;
        }

        public URotator getRotation() {
            return this.rotation;
        }

        public int getTexRotationType() {
            return this.texRotationType;
        }

        public URotator getOscillationRate() {
            return this.oscillationRate;
        }

        public URotator getOscillationAmplitude() {
            return this.oscillationAmplitude;
        }

        public URotator getOscillationPhase() {
            return this.oscillationPhase;
        }
    }

    public static class UTexOscillator extends UModifier {
        public static final int OT_PAN = 0;
        public static final int OT_STRETCH = 1;
        public static final int OT_STRETCH_REPEAT = 2;
        public static final int OT_JITTER = 3;

        private float uOscillationRate = 1f;
        private float vOscillationRate = 1f;
        private float uOscillationPhase;
        private float vOscillationPhase;
        private float uOscillationAmplitude = 0.1f;
        private float vOscillationAmplitude = 0.1f;
        private int uOscillationType;
        private int vOscillationType;
        private float uOffset;
        private float vOffset;
        private float lastSu;
        private float lastSv;
        private float currentUJitter;
        private float currentVJitter;

        public UTexOscillator(UArchive archive) {
            this(archive, false);
        }

        protected UTexOscillator(UArchive archive, boolean triggered) {
            super(archive);
            if (triggered) {
                uOscillationRate = 0f;
                uOscillationAmplitude = 0f;
                vOscillationRate = 0.5f;
                vOscillationAmplitude = 0.5f;
                vOscillationPhase = 0.25f;
            }
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            switch (property.getName()) {
                case "UOscillationRate" -> uOscillationRate = property.getFloatValue();
                case "VOscillationRate" -> vOscillationRate = property.getFloatValue();
                case "UOscillationPhase" -> uOscillationPhase = property.getFloatValue();
                case "VOscillationPhase" -> vOscillationPhase = property.getFloatValue();
                case "UOscillationAmplitude" -> uOscillationAmplitude = property.getFloatValue();
                case "VOscillationAmplitude" -> vOscillationAmplitude = property.getFloatValue();
                case "UOscillationType" -> uOscillationType = property.getUInt8Value();
                case "VOscillationType" -> vOscillationType = property.getUInt8Value();
                case "UOffset" -> uOffset = property.getFloatValue();
                case "VOffset" -> vOffset = property.getFloatValue();
                case "LastSu" -> lastSu = property.getFloatValue();
                case "LastSv" -> lastSv = property.getFloatValue();
                case "CurrentUJitter" -> currentUJitter = property.getFloatValue();
                case "CurrentVJitter" -> currentVJitter = property.getFloatValue();
                default -> {
                    return false;
                }
            }
            return true;
        }

        public float getUOscillationRate() {
            return uOscillationRate;
        }

        public float getVOscillationRate() {
            return vOscillationRate;
        }

        public float getUOscillationPhase() {
            return uOscillationPhase;
        }

        public float getVOscillationPhase() {
            return vOscillationPhase;
        }

        public float getUOscillationAmplitude() {
            return uOscillationAmplitude;
        }

        public float getVOscillationAmplitude() {
            return vOscillationAmplitude;
        }

        public int getUOscillationType() {
            return uOscillationType;
        }

        public int getVOscillationType() {
            return vOscillationType;
        }

        public float getUOffset() {
            return uOffset;
        }

        public float getVOffset() {
            return vOffset;
        }

        public float getLastSu() {
            return lastSu;
        }

        public float getLastSv() {
            return lastSv;
        }

        public float getCurrentUJitter() {
            return currentUJitter;
        }

        public float getCurrentVJitter() {
            return currentVJitter;
        }
    }

    public static class UTexPannerTriggered extends UTexPanner {
        private int retriggerAction = 3;
        private float stopAfterPeriod = 0.5f;
        private float triggeredTime;
        private boolean reverse;
        private boolean triggered;

        public UTexPannerTriggered(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            switch (property.getName()) {
                case "RetriggerAction" -> retriggerAction = property.getUInt8Value();
                case "StopAfterPeriod" -> stopAfterPeriod = property.getFloatValue();
                case "TriggeredTime" -> triggeredTime = property.getFloatValue();
                case "Reverse" -> reverse = property.isTrue();
                case "Triggered" -> triggered = property.isTrue();
                default -> {
                    return false;
                }
            }
            return true;
        }

        public int getRetriggerAction() {
            return retriggerAction;
        }

        public float getStopAfterPeriod() {
            return stopAfterPeriod;
        }

        public float getTriggeredTime() {
            return triggeredTime;
        }

        public boolean isReverse() {
            return reverse;
        }

        public boolean isTriggered() {
            return triggered;
        }
    }

    public static class UTexOscillatorTriggered extends UTexOscillator {
        private int retriggerAction = 3;
        private float stopAfterPeriod = 0.5f;
        private float triggeredTime;
        private boolean reverse;
        private boolean triggered;

        public UTexOscillatorTriggered(UArchive archive) {
            super(archive, true);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            switch (property.getName()) {
                case "RetriggerAction" -> retriggerAction = property.getUInt8Value();
                case "StopAfterPeriod" -> stopAfterPeriod = property.getFloatValue();
                case "TriggeredTime" -> triggeredTime = property.getFloatValue();
                case "Reverse" -> reverse = property.isTrue();
                case "Triggered" -> triggered = property.isTrue();
                default -> {
                    return false;
                }
            }
            return true;
        }

        public int getRetriggerAction() {
            return retriggerAction;
        }

        public float getStopAfterPeriod() {
            return stopAfterPeriod;
        }

        public float getTriggeredTime() {
            return triggeredTime;
        }

        public boolean isReverse() {
            return reverse;
        }

        public boolean isTriggered() {
            return triggered;
        }
    }

    public static class UTexScaler extends UModifier {
        private float uScale = 1f;
        private float vScale = 1f;
        private float uOffset;
        private float vOffset;

        public UTexScaler(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "UOffset" -> {
                    uOffset = property.getFloatValue();
                    yield true;
                }
                case "VOffset" -> {
                    vOffset = property.getFloatValue();
                    yield true;
                }
                case "UScale" -> {
                    uScale = property.getFloatValue();
                    yield true;
                }
                case "VScale" -> {
                    vScale = property.getFloatValue();
                    yield true;
                }
                default -> false;
            };
        }

        public float getUOffset() {
            return uOffset;
        }

        public float getVOffset() {
            return vOffset;
        }

        public float getUScale() {
            return this.uScale;
        }

        public float getVScale() {
            return this.vScale;
        }
    }

    public static class UTexCoordSource extends UModifier {
        private int sourceChannel;

        public UTexCoordSource(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            if (property.getName().equals("SourceChannel")) {
                sourceChannel = property.getInt32Value();
                return true;
            }
            return false;
        }

        public int getSourceChannel() {
            return this.sourceChannel;
        }
    }

    public static class UColorModifier extends UModifier {
        private UColor color = new UColor((byte) 255, (byte) 255, (byte) 255, (byte) 255);
        private boolean alphaBlend = true;
        private boolean renderTwoSided = true;

        public UColorModifier(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Color" -> {
                    color = property.getColorValue();
                    yield true;
                }
                case "AlphaBlend" -> {
                    alphaBlend = property.isTrue();
                    yield true;
                }
                case "RenderTwoSided" -> {
                    renderTwoSided = property.isTrue();
                    yield true;
                }
                default -> false;
            };
        }

        public UColor getColor() {
            return this.color;
        }

        public boolean isAlphaBlend() {
            return this.alphaBlend;
        }

        public boolean isRenderTwoSided() {
            return this.renderTwoSided;
        }
    }

    public enum ETexEnvMapType {
        EM_WorldSpace(0),
        EM_CameraSpace(1);
        private final int id;

        ETexEnvMapType(int id) {
            this.id = id;
        }

        public static ETexEnvMapType fromId(int id) {
            for (var type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            return EM_CameraSpace;
        }

        public int getId() {
            return this.id;
        }
    }

    public static class UTexEnvMap extends UModifier {
        private final UObjectRef<UMaterial, Optional> envMap = UObjectRef.optional();
        private ETexEnvMapType envMapType = ETexEnvMapType.EM_CameraSpace;
        private int envMapTypeId = ETexEnvMapType.EM_CameraSpace.getId();

        public UTexEnvMap(UArchive archive) {
            super(archive);
        }

        public @Nullable UMaterial getEnvMapMaterial() {
            var wrapped = getMaterial().get();
            return wrapped != null ? wrapped : envMap.get();
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            if (property.getName().equals("EnvMap")) {
                envMap.fromProperty(archive, property);
                return true;
            }
            if (property.getName().equals("EnvMapType")) {
                envMapTypeId = property.getUInt8Value();
                envMapType = ETexEnvMapType.fromId(envMapTypeId);
                return true;
            }
            return false;
        }

        public UObjectRef<UMaterial, Optional> getEnvMap() {
            return this.envMap;
        }

        public ETexEnvMapType getEnvMapType() {
            return this.envMapType;
        }

        public int getEnvMapTypeId() {
            return this.envMapTypeId;
        }
    }

    public enum FrameBufferBlending {
        FB_Overwrite(0),
        FB_Modulate(1),
        FB_AlphaBlend(2),
        FB_AlphaModulate_MightNotFogCorrectly(3),
        FB_Translucent(4),
        FB_Darken(5),
        FB_Brighten(6),
        FB_Invisible(7),
        FB_Add(8),
        FB_InWaterBlend(9),
        FB_Capture(10),
        FB_OverwriteAlpha(11),
        FB_DarkenInv(12);
        private final int id;

        FrameBufferBlending(int id) {
            this.id = id;
        }

        public static FrameBufferBlending fromId(int id) {
            for (var type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            return FB_Overwrite;
        }

        public int getId() {
            return this.id;
        }
    }

    public static class UFinalBlend extends UModifier {
        private FrameBufferBlending fbBlending;
        private boolean zWrite;
        private boolean zTest;
        private boolean alphaTest;
        private boolean twoSided;
        private short alphaRef;
        private boolean treatAsTwoSided;

        public UFinalBlend(UArchive archive) {
            super(archive);
            fbBlending = FrameBufferBlending.FB_Overwrite;
            zWrite = true;
            zTest = true;
            alphaTest = false;
            twoSided = false;
            alphaRef = 0;
            treatAsTwoSided = false;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            if (property.getName().equals("FrameBufferBlending")) {
                fbBlending = FrameBufferBlending.fromId(property.getUInt8Value());
                return true;
            }
            if (property.getName().equals("ZWrite")) {
                zWrite = property.isTrue();
                return true;
            }
            if (property.getName().equals("ZTest")) {
                zTest = property.isTrue();
                return true;
            }
            if (property.getName().equals("AlphaTest")) {
                alphaTest = property.isTrue();
                return true;
            }
            if (property.getName().equals("TwoSided")) {
                twoSided = property.isTrue();
                return true;
            }
            if (property.getName().equals("AlphaRef")) {
                alphaRef = property.getUInt8Value();
                return true;
            }
            if (property.getName().equals("TreatAsTwoSided")) {
                treatAsTwoSided = property.isTrue();
                return true;
            }
            return false;
        }

        public FrameBufferBlending getFbBlending() {
            return this.fbBlending;
        }

        public boolean isZWrite() {
            return this.zWrite;
        }

        public boolean isZTest() {
            return this.zTest;
        }

        public boolean isAlphaTest() {
            return this.alphaTest;
        }

        public boolean isTwoSided() {
            return this.twoSided;
        }

        public short getAlphaRef() {
            return this.alphaRef;
        }

        public boolean isTreatAsTwoSided() {
            return this.treatAsTwoSided;
        }
    }

    public static class URenderedMaterial extends UMaterial {
        public URenderedMaterial(UArchive archive) {
            super(archive);
        }
    }

    public enum UTextureFormat {
        TEXF_P8(0x00),
        TEXF_RGBA7(0x01),
        TEXF_RGB16(0x02),
        TEXF_DXT1(0x03),
        TEXF_RGB8(0x04),
        TEXF_RGBA8(0x05),
        TEXF_NODATA(0x06),
        TEXF_DXT3(0x07),
        TEXF_DXT5(0x08),
        TEXF_L8(0x09),
        TEXF_G16(0x0A),
        TEXF_RRRGGGBBB(0x0B),
        TEXF_MAX(0xFF);
        private final int id;

        UTextureFormat(int id) {
            this.id = id;
        }

        public static UTextureFormat fromId(int id) {
            for (var type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown type id: " + id);
        }

        public int getId() {
            return this.id;
        }
    }

    public enum TextureClampMode {
        TC_Wrap(0x00),
        TC_Clamp(0x01),
        TC_Mirror(0x02),
        TC_Stretch(0x03);
        private final int id;

        TextureClampMode(int id) {
            this.id = id;
        }

        public static TextureClampMode fromId(int id) {
            for (var type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown type id: " + id);
        }

        public int getId() {
            return this.id;
        }
    }

    public static class UBitMapMaterial extends URenderedMaterial {
        protected UTextureFormat format = UTextureFormat.TEXF_P8;
        protected TextureClampMode uClampMode;
        protected TextureClampMode vClampMode;
        protected short uBits;
        protected short vBits;

        protected int uSize;
        protected int vSize;

        protected int uClamp;
        protected int vClamp;
        protected int minFilter;
        protected int magFilter;
        protected int mipFilter;

        public UBitMapMaterial(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Format" -> {
                    format = UTextureFormat.fromId(property.getUInt8Value());
                    yield true;
                }
                case "UClampMode" -> {
                    uClampMode = TextureClampMode.fromId(property.getUInt8Value());
                    yield true;
                }
                case "VClampMode" -> {
                    vClampMode = TextureClampMode.fromId(property.getUInt8Value());
                    yield true;
                }
                case "UBits" -> {
                    uBits = property.getUInt8Value();
                    yield true;
                }
                case "VBits" -> {
                    vBits = property.getUInt8Value();
                    yield true;
                }
                case "USize" -> {
                    uSize = property.getInt32Value();
                    yield true;
                }
                case "VSize" -> {
                    vSize = property.getInt32Value();
                    yield true;
                }
                case "UClamp" -> {
                    uClamp = property.getInt32Value();
                    yield true;
                }
                case "VClamp" -> {
                    vClamp = property.getInt32Value();
                    yield true;
                }
                case "MinFilter" -> {
                    minFilter = property.getInt32Value();
                    yield true;
                }
                case "MagFilter" -> {
                    magFilter = property.getInt32Value();
                    yield true;
                }
                case "MipFilter" -> {
                    mipFilter = property.getInt32Value();
                    yield true;
                }
                default -> false;
            };
        }

        public int getMinFilter() {
            return minFilter;
        }

        public int getMagFilter() {
            return magFilter;
        }

        public int getMipFilter() {
            return mipFilter;
        }

        public UTextureFormat getFormat() {
            return this.format;
        }

        public TextureClampMode getUClampMode() {
            return this.uClampMode;
        }

        public TextureClampMode getVClampMode() {
            return this.vClampMode;
        }

        public short getUBits() {
            return this.uBits;
        }

        public short getVBits() {
            return this.vBits;
        }

        public int getUSize() {
            return this.uSize;
        }

        public int getVSize() {
            return this.vSize;
        }

        public int getUClamp() {
            return this.uClamp;
        }

        public int getVClamp() {
            return this.vClamp;
        }
    }

    public static class UPalette extends UObject {
        private UColor[] colors;

        public UPalette(UArchive archive) {
            super(archive);
        }

        public void deserialize() {
            super.deserialize();
            colors = archive.readArray(UColor.class, UColor::of);
        }

        public UColor[] getColors() {
            return this.colors;
        }
    }

    public static class UMipmap {
        private int unknown;
        private final byte[] data;
        private final int uSize, vSize;
        private short uBits, vBits;

        public UMipmap(UArchive archive) {
            unknown = archive.readInt();
            data = archive.readByteArray();
            uSize = archive.readInt();
            vSize = archive.readInt();
            uBits = archive.readUInt8();
            vBits = archive.readUInt8();
        }

        public UMipmap(byte[] data, int uSize, int vSize) {
            this.data = data;
            this.uSize = uSize;
            this.vSize = vSize;
        }

        public int getUnknown() {
            return this.unknown;
        }

        public byte[] getData() {
            return this.data;
        }

        public int getUSize() {
            return this.uSize;
        }

        public int getVSize() {
            return this.vSize;
        }

        public short getUBits() {
            return this.uBits;
        }

        public short getVBits() {
            return this.vBits;
        }
    }

    public enum LodSet {
        LODSET_None(0),
        LODSET_World(1),
        LODSET_PlayerSkin(2),
        LODSET_WeaponSkin(3),
        LODSET_Terrain(4),
        LODSET_Interface(5),
        LODSET_RenderMap(6),
        LODSET_Lightmap(7),
        LODSET_MAX(8);
        private final int id;

        LodSet(int id) {
            this.id = id;
        }

        public static LodSet fromId(int id) {
            for (var type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown type id: " + id);
        }

        public int getId() {
            return this.id;
        }
    }

    public static class UTexture extends UBitMapMaterial {
        private UObjectRef<UPalette, Optional> palette = UObjectRef.optional();

        private final UObjectRef<UMaterial, Required> detail = UObjectRef.required();
        private float detailScale;

        private UColor mipZero;
        private UColor maxColor;
        private double lastUpdateTime;

        private boolean masked;
        private boolean alphaTexture;
        private boolean twoSided;
        private boolean highColorQuality;
        private boolean highTextureQuality;
        private boolean realtime;
        private boolean parametric;
        private boolean realtimeChanged;

        private LodSet lodSet;
        private int normalLod;
        private int minLod;
        private int maxLod;

        private final UObjectRef<UTexture, Optional> animationNext = UObjectRef.optional();
        private UObjectRef<UTexture, Required> animationCurrent;
        private short primeCount;
        private short primeCurrent;
        private float minFrameRate;
        private float maxFrameRate;
        private int totalFrameNum;
        private boolean oneTimeAnimLoop;
        private float accumulator;

        private UMipmap[] mips;

        private boolean hasCompressed;
        private UTextureFormat compressedFormat;

        private int width;
        private int height;

        public UTexture(UArchive archive) {
            super(archive);
            detailScale = 8.0f;
            mipZero = new UColor((byte) 64, (byte) 128, (byte) 64, (byte) 0);
            maxColor = new UColor((byte) 255, (byte) 255, (byte) 255, (byte) 255);
            alphaTexture = false;
            twoSided = false;
            lodSet = LodSet.LODSET_World;
        }

        public static int mipDataSize(UTextureFormat format, int uSize, int vSize) {
            return switch (format) {
                case TEXF_RGBA8 -> uSize * vSize * 4;
                case TEXF_G16 -> uSize * vSize * 2;
                case TEXF_DXT1 -> blockCount(uSize) * blockCount(vSize) * 8;
                case TEXF_DXT3, TEXF_DXT5 -> blockCount(uSize) * blockCount(vSize) * 16;
                default -> 0;
            };
        }

        @Override
        protected void deserialize() {
            super.deserialize();

            var materialDeserializer = new UMaterialDeserializer();
            materialDeserializer.deserialize(archive);

            if (format != UTextureFormat.TEXF_DXT1
                    && format != UTextureFormat.TEXF_DXT3
                    && format != UTextureFormat.TEXF_DXT5
                    && format != UTextureFormat.TEXF_RGBA8
                    && format != UTextureFormat.TEXF_G16) {
                mips = archive.readArray(UMipmap.class, UMipmap::new);
                return;
            }

            var expectedSize = mipDataSize(format, uSize, vSize);
            var nativeMips = readNativeMipArray();
            if (nativeMips != null) {
                mips = nativeMips;
                return;
            }
            var size = 0;
            while (size != expectedSize) {
                if (!archive.hasMoreBytes()) {
                    var textureName = getFullName();
                    if (REPORTED_MISSING_MIPS.add(textureName)) {
                        log.warn(
                                "Could not locate mip data for texture {}; rendering it untextured "
                                        + "(identical failures are DEBUG-only)",
                                textureName);
                    } else {
                        log.debug("Repeated missing mip data for texture {}", textureName);
                    }
                    mips = new UMipmap[0];
                    return;
                }
                size = archive.readIndex();
            }

            var data = new byte[size];
            for (var i = 0; i < size; ++i) {
                data[i] = archive.readByte();
            }
            mips = readMipTail(new UMipmap(data, uSize, vSize));
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "bAlphaTexture" -> {
                    alphaTexture = property.isTrue();
                    yield true;
                }
                case "bTwoSided" -> {
                    twoSided = property.isTrue();
                    yield true;
                }
                case "MipZero" -> {
                    mipZero = property.getColorValue();
                    yield true;
                }
                case "Palette" -> {
                    palette.fromProperty(archive, property);
                    yield true;
                }
                case "AnimNext" -> {
                    animationNext.fromProperty(archive, property);
                    yield true;
                }
                case "MinFrameRate" -> {
                    minFrameRate = property.getFloatValue();
                    yield true;
                }
                case "MaxFrameRate" -> {
                    maxFrameRate = property.getFloatValue();
                    yield true;
                }
                case "TotalFrameNum" -> {
                    totalFrameNum = property.getInt32Value();
                    yield true;
                }
                case "PrimeCount" -> {
                    primeCount = property.getUInt8Value();
                    yield true;
                }
                case "OneTimeAnimLoop" -> {
                    oneTimeAnimLoop = property.isTrue();
                    yield true;
                }
                case "bMasked" -> {
                    masked = property.isTrue();
                    yield true;
                }
                case "Detail" -> {
                    detail.fromProperty(archive, property);
                    yield true;
                }
                case "DetailScale" -> {
                    detailScale = property.getFloatValue();
                    yield true;
                }
                case "LODSet" -> {
                    lodSet = LodSet.fromId(property.getUInt8Value());
                    yield true;
                }
                case "NormalLOD" -> {
                    normalLod = property.getInt32Value();
                    yield true;
                }
                case "MinLOD" -> {
                    minLod = property.getInt32Value();
                    yield true;
                }
                case "MaxLOD" -> {
                    maxLod = property.getInt32Value();
                    yield true;
                }
                case "MaxColor" -> {
                    maxColor = property.getColorValue();
                    yield true;
                }
                default -> false;
            };
        }

        public @Nullable UTexture getAnimationNextFrame() {
            return animationNext.get();
        }

        public UObjectRef<UPalette, Optional> getPalette() {
            return this.palette;
        }

        public UObjectRef<UMaterial, Required> getDetail() {
            return this.detail;
        }

        public float getDetailScale() {
            return this.detailScale;
        }

        public UColor getMipZero() {
            return this.mipZero;
        }

        public UColor getMaxColor() {
            return this.maxColor;
        }

        public double getLastUpdateTime() {
            return this.lastUpdateTime;
        }

        public boolean isMasked() {
            return this.masked;
        }

        public boolean isAlphaTexture() {
            return this.alphaTexture;
        }

        public boolean isTwoSided() {
            return this.twoSided;
        }

        public boolean isHighColorQuality() {
            return this.highColorQuality;
        }

        public boolean isHighTextureQuality() {
            return this.highTextureQuality;
        }

        public boolean isRealtime() {
            return this.realtime;
        }

        public boolean isParametric() {
            return this.parametric;
        }

        public boolean isRealtimeChanged() {
            return this.realtimeChanged;
        }

        public LodSet getLodSet() {
            return this.lodSet;
        }

        public int getNormalLod() {
            return this.normalLod;
        }

        public int getMinLod() {
            return this.minLod;
        }

        public int getMaxLod() {
            return this.maxLod;
        }

        public UObjectRef<UTexture, Optional> getAnimationNext() {
            return this.animationNext;
        }

        public UObjectRef<UTexture, Required> getAnimationCurrent() {
            return this.animationCurrent;
        }

        public short getPrimeCount() {
            return this.primeCount;
        }

        public short getPrimeCurrent() {
            return this.primeCurrent;
        }

        public float getMinFrameRate() {
            return this.minFrameRate;
        }

        public float getMaxFrameRate() {
            return this.maxFrameRate;
        }

        public int getTotalFrameNum() {
            return totalFrameNum;
        }

        public boolean isOneTimeAnimLoop() {
            return oneTimeAnimLoop;
        }

        public float getAccumulator() {
            return this.accumulator;
        }

        public UMipmap[] getMips() {
            return this.mips;
        }

        public boolean isHasCompressed() {
            return this.hasCompressed;
        }

        public UTextureFormat getCompressedFormat() {
            return this.compressedFormat;
        }

        public int getWidth() {
            return this.width;
        }

        public int getHeight() {
            return this.height;
        }

        void markUnreadable() {
            mips = new UMipmap[0];
        }

        private UMipmap[] readNativeMipArray() {
            var start = archive.getOffset();
            var complete = false;
            try {
                var count = archive.readIndex();
                var maximum = 32 - Integer.numberOfLeadingZeros(Math.max(uSize, vSize));
                if (count < 1 || count > maximum || uSize <= 0 || vSize <= 0) {
                    return null;
                }
                var result = new UMipmap[count];
                var u = uSize;
                var v = vSize;
                for (var level = 0; level < count; level++) {
                    var end = archive.readInt();
                    var length = archive.readIndex();
                    var expected = mipDataSize(format, u, v);
                    if (length != expected
                            || length < 1
                            || (long) length + 10 > archive.getRemainingInObject()
                            || end != archive.getOffset() + length) {
                        return null;
                    }
                    var pixels = new byte[length];
                    for (var i = 0; i < length; i++) {
                        pixels[i] = archive.readByte();
                    }
                    if (!readMipTrailer(u, v)) {
                        return null;
                    }
                    result[level] = new UMipmap(pixels, u, v);
                    u = Math.max(1, u >> 1);
                    v = Math.max(1, v >> 1);
                }
                complete = true;
                return result;
            } catch (RuntimeException unsupported) {
                return null;
            } finally {
                if (!complete) {
                    archive.setPosition(start);
                }
            }
        }

        private UMipmap[] readMipTail(UMipmap base) {
            var levels = new ArrayList<UMipmap>();
            levels.add(base);
            var resumeAt = archive.getOffset();
            try {
                if (readMipTrailer(uSize, vSize)) {
                    var u = uSize;
                    var v = vSize;
                    while (u > 1 || v > 1) {
                        u = Math.max(1, u >> 1);
                        v = Math.max(1, v >> 1);
                        var expected = mipDataSize(format, u, v);
                        if (archive.getRemainingInObject() < 4 + 1 + expected + 10) {
                            break;
                        }
                        archive.readInt();
                        if (archive.readIndex() != expected) {
                            break;
                        }
                        var data = new byte[expected];
                        for (var i = 0; i < expected; ++i) {
                            data[i] = archive.readByte();
                        }
                        if (!readMipTrailer(u, v)) {
                            break;
                        }
                        levels.add(new UMipmap(data, u, v));
                        resumeAt = archive.getOffset();
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Mip tail walk stopped for texture {}: {}", getName(), e.toString());
            }
            archive.setPosition(resumeAt);
            return levels.toArray(new UMipmap[0]);
        }

        private boolean readMipTrailer(int u, int v) {
            if (archive.getRemainingInObject() < 10) {
                return false;
            }
            var trailerU = archive.readInt();
            var trailerV = archive.readInt();
            archive.readUInt8();
            archive.readUInt8();
            return trailerU == u && trailerV == v;
        }

        private static int blockCount(int size) {
            return Math.max(1, (size + 3) / 4);
        }
    }

    public static class UCubemap extends UBitMapMaterial {
        private final UObjectRef<UTexture, Optional>[] faces = createFaces();

        public UCubemap(UArchive archive) {
            super(archive);
        }

        public @Nullable UTexture getFirstFace() {
            for (var ref : faces) {
                var face = ref.get();
                if (face != null) {
                    return face;
                }
            }
            return null;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            if (property.getName().equals("Faces")) {
                var index = property.getArrayIndex();
                if (index >= 0 && index < faces.length) {
                    faces[index].fromProperty(archive, property);
                }
                return true;
            }
            return false;
        }

        public UObjectRef<UTexture, Optional>[] getFaces() {
            return this.faces;
        }

        @SuppressWarnings("unchecked")
        private static UObjectRef<UTexture, Optional>[] createFaces() {
            var refs = (UObjectRef<UTexture, Optional>[]) new UObjectRef[6];
            for (int i = 0; i < refs.length; i++) {
                refs[i] = UObjectRef.optional();
            }
            return refs;
        }
    }

    public static class UConstantColor extends URenderedMaterial {
        private UColor color = new UColor((byte) 0, (byte) 0, (byte) 0, (byte) 0);

        public UConstantColor(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            if (property.getName().equals("Color")) {
                color = property.getColorValue();
                return true;
            }
            return false;
        }

        public UColor getColor() {
            return this.color;
        }
    }

    public static class UFadeColor extends URenderedMaterial {
        private static final UColor DEFAULT_COLOR2 = new UColor((byte) 0, (byte) 0, (byte) 0, (byte) 0);

        private UColor color1 = DEFAULT_COLOR2;
        private UColor color2 = DEFAULT_COLOR2;
        private boolean color2Present;
        private float fadePeriod;
        private float fadePhase;
        private int colorFadeType;

        public UFadeColor(UArchive archive) {
            super(archive);
        }

        public UColor getColor() {
            return color1;
        }

        public boolean isAnimated() {
            return getCurve().isAnimated();
        }

        public UColorFade getCurve() {
            return new UColorFade(
                    UColorFade.argb(color1), UColorFade.argb(color2), fadePeriod, fadePhase, colorFadeType);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Color1" -> {
                    color1 = property.getColorValue();
                    yield true;
                }
                case "Color2" -> {
                    color2 = property.getColorValue();
                    color2Present = true;
                    yield true;
                }
                case "FadePeriod" -> {
                    fadePeriod = property.getFloatValue();
                    yield true;
                }
                case "FadePhase" -> {
                    fadePhase = property.getFloatValue();
                    yield true;
                }
                case "ColorFadeType" -> {
                    colorFadeType = property.getUInt8Value();
                    yield true;
                }
                default -> false;
            };
        }

        public UColor getColor1() {
            return this.color1;
        }

        public UColor getColor2() {
            return this.color2;
        }

        public boolean isColor2Present() {
            return this.color2Present;
        }

        public float getFadePeriod() {
            return this.fadePeriod;
        }

        public float getFadePhase() {
            return this.fadePhase;
        }

        public int getColorFadeType() {
            return this.colorFadeType;
        }
    }

    public static class UVertexColor extends URenderedMaterial {
        public UVertexColor(UArchive archive) {
            super(archive);
        }
    }

    public enum OutputBlending {
        OB_Normal(0),
        OB_Masked(1),
        OB_Modulate(2),
        OB_Translucent(3),
        OB_Invisible(4),
        OB_Brighten(5),
        OB_Darken(6),
        OB_MAX(7);
        private final int id;

        OutputBlending(int id) {
            this.id = id;
        }

        public static OutputBlending fromId(int id) {
            for (var type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown type id: " + id);
        }

        public int getId() {
            return this.id;
        }
    }

    public static class UShader extends URenderedMaterial {
        private OutputBlending outputBlending = OutputBlending.OB_Normal;
        private final UObjectRef<UMaterial, Optional> diffuse = UObjectRef.optional();
        private final UObjectRef<UMaterial, Optional> opacity = UObjectRef.optional();
        private final UObjectRef<UMaterial, Optional> specular = UObjectRef.optional();
        private final UObjectRef<UMaterial, Optional> specularMask = UObjectRef.optional();
        private final UObjectRef<UMaterial, Optional> normalMap = UObjectRef.optional();
        private final UObjectRef<UMaterial, Optional> specularMap = UObjectRef.optional();
        private float bumpOffsetScaleFactor;
        private float bumpOffsetBiasFactor;
        private final UObjectRef<UMaterial, Optional> selfIllumination = UObjectRef.optional();
        private final UObjectRef<UMaterial, Optional> selfIlluminationMask = UObjectRef.optional();
        private final UObjectRef<UMaterial, Optional> detail = UObjectRef.optional();
        private float detailScale;
        private boolean twoSided;
        private boolean wireFrame;
        private boolean modulateStaticLighting2x;
        private boolean modulateStaticLighting2xSerialized;
        private boolean performLightingOnSpecularPass;
        private boolean modulateSpecular2x;
        private float specularPower;
        private float specularScale;
        private boolean treatAsTwoSided;
        private boolean zWrite;
        private boolean zWriteSerialized;
        private boolean alphaTest;
        private short alphaRef;

        public UShader(UArchive archive) {
            super(archive);
            detailScale = 8.0f;
            modulateStaticLighting2x = false;
            modulateStaticLighting2xSerialized = false;
            specularPower = 15.0f;
            specularScale = 1.0f;
            zWrite = true;
            alphaTest = false;
            alphaRef = 0;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Diffuse" -> {
                    diffuse.fromProperty(archive, property);
                    yield true;
                }
                case "OutputBlending" -> {
                    outputBlending = OutputBlending.fromId(property.getUInt8Value());
                    yield true;
                }
                case "AlphaTest" -> {
                    alphaTest = property.isTrue();
                    yield true;
                }
                case "AlphaRef" -> {
                    alphaRef = property.getUInt8Value();
                    yield true;
                }
                case "TreatAsTwoSided" -> {
                    treatAsTwoSided = property.isTrue();
                    yield true;
                }
                case "TwoSided" -> {
                    twoSided = property.isTrue();
                    yield true;
                }
                case "Opacity" -> {
                    opacity.fromProperty(archive, property);
                    yield true;
                }
                case "Specular" -> {
                    specular.fromProperty(archive, property);
                    yield true;
                }
                case "SpecularMask", "SpecularityMask" -> {
                    specularMask.fromProperty(archive, property);
                    yield true;
                }
                case "SelfIllumination" -> {
                    selfIllumination.fromProperty(archive, property);
                    yield true;
                }
                case "SelfIlluminationMask" -> {
                    selfIlluminationMask.fromProperty(archive, property);
                    yield true;
                }
                case "Detail" -> {
                    detail.fromProperty(archive, property);
                    yield true;
                }
                case "DetailScale" -> {
                    detailScale = property.getFloatValue();
                    yield true;
                }
                case "WireFrame" -> {
                    wireFrame = property.isTrue();
                    yield true;
                }
                case "ModulateStaticLighting2x" -> {
                    modulateStaticLighting2x = property.isTrue();
                    modulateStaticLighting2xSerialized = true;
                    yield true;
                }
                case "PerformLightingOnSpecularPass" -> {
                    performLightingOnSpecularPass = property.isTrue();
                    yield true;
                }
                case "ModulateSpecular2x" -> {
                    modulateSpecular2x = property.isTrue();
                    yield true;
                }
                case "SpecularPower" -> {
                    specularPower = property.getFloatValue();
                    yield true;
                }
                case "SpecularScale" -> {
                    specularScale = property.getFloatValue();
                    yield true;
                }
                case "NormalMap" -> {
                    normalMap.fromProperty(archive, property);
                    yield true;
                }
                case "SpecularMap" -> {
                    specularMap.fromProperty(archive, property);
                    yield true;
                }
                case "BumpOffsetScaleFactor" -> {
                    bumpOffsetScaleFactor = property.getFloatValue();
                    yield true;
                }
                case "BumpOffsetBiasFactor" -> {
                    bumpOffsetBiasFactor = property.getFloatValue();
                    yield true;
                }
                case "ZWrite" -> {
                    zWrite = property.isTrue();
                    zWriteSerialized = true;
                    yield true;
                }
                default -> false;
            };
        }

        public OutputBlending getOutputBlending() {
            return this.outputBlending;
        }

        public UObjectRef<UMaterial, Optional> getDiffuse() {
            return this.diffuse;
        }

        public UObjectRef<UMaterial, Optional> getOpacity() {
            return this.opacity;
        }

        public UObjectRef<UMaterial, Optional> getSpecular() {
            return this.specular;
        }

        public UObjectRef<UMaterial, Optional> getSpecularMask() {
            return this.specularMask;
        }

        public UObjectRef<UMaterial, Optional> getSelfIllumination() {
            return this.selfIllumination;
        }

        public UObjectRef<UMaterial, Optional> getSelfIlluminationMask() {
            return this.selfIlluminationMask;
        }

        public UObjectRef<UMaterial, Optional> getDetail() {
            return this.detail;
        }

        public float getDetailScale() {
            return this.detailScale;
        }

        public boolean isTwoSided() {
            return this.twoSided;
        }

        public boolean isWireFrame() {
            return this.wireFrame;
        }

        public boolean isModulateStaticLighting2x() {
            return this.modulateStaticLighting2x;
        }

        public boolean isModulateStaticLighting2xSerialized() {
            return this.modulateStaticLighting2xSerialized;
        }

        public boolean isPerformLightingOnSpecularPass() {
            return this.performLightingOnSpecularPass;
        }

        public boolean isModulateSpecular2x() {
            return this.modulateSpecular2x;
        }

        public float getSpecularPower() {
            return this.specularPower;
        }

        public float getSpecularScale() {
            return this.specularScale;
        }

        public UObjectRef<UMaterial, Optional> getNormalMap() {
            return normalMap;
        }

        public UObjectRef<UMaterial, Optional> getSpecularMap() {
            return specularMap;
        }

        public float getBumpOffsetScaleFactor() {
            return bumpOffsetScaleFactor;
        }

        public float getBumpOffsetBiasFactor() {
            return bumpOffsetBiasFactor;
        }

        public boolean isTreatAsTwoSided() {
            return this.treatAsTwoSided;
        }

        public boolean isZWrite() {
            return this.zWrite;
        }

        public boolean isZWriteSerialized() {
            return this.zWriteSerialized;
        }

        public boolean isAlphaTest() {
            return this.alphaTest;
        }

        public short getAlphaRef() {
            return this.alphaRef;
        }
    }

    @Override
    public boolean setProperty(UProperty property) {
        if (super.setProperty(property)) {
            return true;
        }
        if (property.getName().equals("FallbackMaterial")) {
            fallbackMaterial.fromProperty(archive, property);
            return true;
        }
        return false;
    }

    public UObjectRef<UMaterial, Optional> getFallbackMaterial() {
        return this.fallbackMaterial;
    }

    public UObjectRef<UMaterial, Required> getDefaultMaterial() {
        return this.defaultMaterial;
    }
}

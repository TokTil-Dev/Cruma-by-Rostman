package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UProperty.Type;
import com.rost.cruma.core.unreal.primitives.UColor;
import com.rost.cruma.core.unreal.primitives.URotator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class UPropertyExtractor {
    private static final Logger log = LogManager.getLogger(UPropertyExtractor.class);
    private static final Set<String> REGION_FIELDS = Set.of("Zone", "iLeaf", "ZoneNumber");
    private static final Set<String> SKILL_PHASES = Set.of(
            "CastingAction", "ChannelingAction", "PreshotAction", "ShotAction", "ExplosionAction", "UnionTargetAction");
    private static final Set<String> SKILL_VISUAL_PHASES =
            Set.of("CastingActions", "ChannelingActions", "PreshotActions", "ShotActions", "ExplosionActions");
    private static final Set<String> STRUCT_ARRAYS = Set.of(
            "ColorScale",
            "SizeScale",
            "RevolutionScale",
            "BeamEndPoints",
            "WeatherRangeTime",
            "AmbientSoundRangeTime",
            "DecoLayers");
    private static final Set<String> PLAIN_STRUCTS = Set.of("Vector", "Rotator", "Color");
    private static final int TAGGED_STRUCTS_FROM = 118;
    private static final int MOST_STRUCT_DEPTH = 4;
    private final UArchive archive;

    public UPropertyExtractor(UArchive archive) {
        this.archive = archive;
    }

    public static boolean isRegionLongerThanItsTag(
            String structName, Collection<String> fields, long consumed, long size) {
        return "PointRegion".equals(structName)
                && consumed > size
                && consumed - size <= 2
                && fields.contains("Zone")
                && REGION_FIELDS.containsAll(fields);
    }

    public List<UProperty> extractProperties() {
        var properties = new ArrayList<UProperty>();

        while (true) {
            var property = extractProperty();
            if (property == null) {
                break;
            }
            properties.add(property);
        }

        return properties;
    }

    private UProperty extractProperty() {
        var name = archive.readName();
        if (UArchive.NONE_NAME.equals(name)) {
            return null;
        }

        var builder = UProperty.builder().name(name);

        var info = archive.readUInt8();
        var type = Type.fromId(info & 0x0F);
        var sizeType = (info >> 4) & 0x07;
        var isArray = ((info >> 7) & 0x01) == 1;

        builder.info(info).type(type).sizeType(sizeType).array(isArray);

        var structName = "";
        if (type == Type.Struct) {
            structName = archive.readName();
            builder.structName(structName);
        }

        var size = extractSize(sizeType, archive);
        builder.size(size);

        if (type == Type.Bool) {
            return builder.build();
        }

        if (isArray) {
            builder.arrayIndex(archive.readArrayIndex());
        }

        var subProperties = new ArrayList<Map<String, UProperty>>();

        switch (type) {
            case Type.Byte -> builder.uInt8Value(archive.readUInt8());
            case Type.Int -> builder.int32Value(archive.readInt());
            case Type.Float -> builder.floatValue(archive.readFloat());
            case Type.Object, Type.Name -> builder.indexValue(archive.readIndex());
            case Type.Array -> {
                var startPosition = archive.getOffset();
                var propertyArraySize = archive.readIndex();
                builder.arraySize(propertyArraySize);

                int sizeSize = archive.getOffset() - startPosition;
                int arraySize = (int) (size - sizeSize);

                var plainElements = readPlainElements(name, propertyArraySize, arraySize, size);
                if (plainElements != null) {
                    subProperties.addAll(plainElements);
                } else if (name.equals("Materials")
                        || name.equals("NpcInfo")
                        || name.equals("PcInfo")
                        || name.equals("VisualItemList")) {
                    int arrayStartPosition = archive.getOffset();
                    try {
                        for (int i = 0; i < propertyArraySize; ++i) {
                            subProperties.add(extractPropertiesMap());
                        }
                    } catch (RuntimeException e) {
                        subProperties.clear();
                    }
                    if (archive.getOffset() - arrayStartPosition != arraySize) {
                        archive.setPosition(arrayStartPosition + arraySize);
                    }
                } else if (name.equals("Emitters")
                        || SKILL_PHASES.contains(name)
                        || name.equals("Skins")
                        || name.equals("CustomMaterials")
                        || name.equals("SpawnSound")
                        || name.equals("SoundList")
                        || name.equals("AmbientVolumeSounds")
                        || name.equals("ManualExcludes")
                        || name.equals("L2MovementTag")) {
                    int arrayStartPosition = archive.getOffset();
                    var refs = new int[propertyArraySize];
                    for (int i = 0; i < propertyArraySize; ++i) {
                        refs[i] = archive.readIndex();
                    }
                    int consumed = archive.getOffset() - arrayStartPosition;
                    if (consumed < arraySize) {
                        archive.skipBytes(arraySize - consumed);
                    }
                    builder.objectArray(refs);
                } else if (name.equals("ColorScale")
                        || SKILL_VISUAL_PHASES.contains(name)
                        || name.equals("BeamEndPoints")
                        || name.equals("SizeScale")
                        || name.equals("RevolutionScale")
                        || name.equals("WeatherRangeTime")
                        || name.equals("AmbientSoundRangeTime")
                        || name.equals("DecoLayers")
                        || (name.equals("Colors") && propertyArraySize > 0 && propertyArraySize <= arraySize / 6)) {
                    int arrayStartPosition = archive.getOffset();
                    try {
                        for (int i = 0; i < propertyArraySize; ++i) {
                            subProperties.add(extractPropertiesMap());
                        }
                    } catch (RuntimeException e) {
                        subProperties.clear();
                    }
                    int consumed = archive.getOffset() - arrayStartPosition;
                    var validL2FogColors = !name.equals("Colors")
                            || (consumed == arraySize
                                    && subProperties.size() == propertyArraySize
                                    && subProperties.stream()
                                            .allMatch(element -> element.containsKey("Time")
                                                    && element.containsKey("FogColor")
                                                    && element.containsKey("SkyColor")
                                                    && element.containsKey("CloudColor")
                                                    && element.containsKey("HazeRingColor")));
                    if (consumed != arraySize || !validL2FogColors) {
                        subProperties.clear();
                        archive.setPosition(arrayStartPosition + arraySize);
                    }
                    if (!name.equals("Colors") && propertyArraySize > 0 && subProperties.isEmpty()) {
                        archive.setPosition(arrayStartPosition);
                        var dataValue = new short[arraySize];
                        for (int i = 0; i < arraySize; ++i) {
                            dataValue[i] = archive.readUInt8();
                        }
                        builder.dataValue(dataValue);
                    }
                    if (name.equals("Colors") && !validL2FogColors) {
                        archive.setPosition(arrayStartPosition);
                        var dataValue = new short[arraySize];
                        for (int i = 0; i < arraySize; ++i) {
                            dataValue[i] = archive.readUInt8();
                        }
                        builder.dataValue(dataValue);
                    }
                } else if (name.equals("StateFog") || name.equals("ZoneMusicState") || name.equals("StateBlendInfo")) {
                    int arrayStartPosition = archive.getOffset();
                    try {
                        for (int i = 0; i < propertyArraySize; ++i) {
                            subProperties.add(extractPropertiesMap());
                        }
                    } catch (RuntimeException e) {
                        subProperties.clear();
                    }
                    if (archive.getOffset() - arrayStartPosition != arraySize
                            || subProperties.size() != propertyArraySize) {
                        subProperties.clear();
                        archive.setPosition(arrayStartPosition);
                        var dataValue = new short[arraySize];
                        for (int i = 0; i < arraySize; ++i) {
                            dataValue[i] = archive.readUInt8();
                        }
                        builder.dataValue(dataValue);
                    }
                } else if (name.equals("ZonePlayState")) {
                    int arrayStartPosition = archive.getOffset();
                    if (propertyArraySize > 0 && arraySize == propertyArraySize * 4L) {
                        var states = new int[propertyArraySize];
                        for (int i = 0; i < propertyArraySize; ++i) {
                            states[i] = archive.readInt();
                        }
                        builder.intArray(states);
                    } else {
                        var dataValue = new short[arraySize];
                        for (int i = 0; i < arraySize; ++i) {
                            dataValue[i] = archive.readUInt8();
                        }
                        builder.dataValue(dataValue);
                    }
                    archive.setPosition(arrayStartPosition + arraySize);
                } else if (name.equals("ZoneRenderState")) {
                    int arrayStartPosition = archive.getOffset();
                    try {
                        for (int i = 0; i < propertyArraySize; ++i) {
                            subProperties.add(extractPropertiesMap());
                        }
                    } catch (RuntimeException e) {
                        subProperties.clear();
                    }
                    var zoneStates = subProperties.size() == propertyArraySize
                            && subProperties.stream().allMatch(element -> element.containsKey("ZoneState"));
                    if (!zoneStates || archive.getRemainingInObject() < 0) {
                        subProperties.clear();
                        archive.setPosition(arrayStartPosition);
                        if (propertyArraySize > 0 && arraySize == propertyArraySize * 4L) {
                            var states = new int[propertyArraySize];
                            for (int i = 0; i < propertyArraySize; ++i) {
                                states[i] = archive.readInt();
                            }
                            builder.intArray(states);
                        }
                        archive.setPosition(arrayStartPosition + arraySize);
                    }
                } else {
                    var dataValue = new short[arraySize];
                    for (int i = 0; i < arraySize; ++i) {
                        dataValue[i] = archive.readUInt8();
                    }
                    builder.dataValue(dataValue);
                }
            }
            case Type.Struct -> {
                var plain = readPlainStruct(structName, size);
                if (plain != null) {
                    subProperties.add(plain);
                    return builder.subProperties(subProperties).build();
                }
                switch (structName) {
                    case "Rotator" ->
                        builder.rotatorValue(new URotator(archive.readInt(), archive.readInt(), archive.readInt()));
                    case "Vector" -> builder.vectorValue(archive.readVector());
                    case "TerrainLayer" -> subProperties.add(extractPropertiesMap());
                    case "Color" -> builder.colorValue(UColor.of(archive));
                    case "Range",
                            "RangeVector",
                            "PointRegion",
                            "Plane",
                            "CameraItem",
                            "ScreenItem",
                            "MusicItem",
                            "L2RotatorTime",
                            "RandomizerSoundDuration" -> {
                        var startPos = archive.getOffset();
                        try {
                            var map = extractPropertiesMap();
                            var consumed = archive.getOffset() - startPos;
                            if (consumed == size
                                    || isRegionLongerThanItsTag(structName, map.keySet(), consumed, size)) {
                                subProperties.add(map);
                            } else {
                                archive.skipBytes((int) (size - (archive.getOffset() - startPos)));
                            }
                        } catch (RuntimeException e) {
                            archive.skipBytes((int) (size - (archive.getOffset() - startPos)));
                        }
                    }
                    default -> {
                        log.debug("Unknown struct: {}", structName);
                        archive.skipBytes((int) size);
                    }
                }
            }
            case Type.Vector -> builder.vectorValue(archive.readVector());
            case Type.Rotator -> builder.rotatorValue(URotator.of(archive));
            case Type.Str -> {
                var startPos = archive.getOffset();
                try {
                    builder.stringValue(archive.readString());
                } catch (RuntimeException e) {
                    log.debug("Unreadable string: {}", name);
                }
                archive.setPosition(startPos + (int) size);
            }
            default -> {
                log.debug("Unknown property type: {}", type);
                archive.skipBytes((int) size);
            }
        }

        return builder.subProperties(subProperties).build();
    }

    private boolean hasPlainStructs() {
        return archive.getHeader().getFileVersion() < TAGGED_STRUCTS_FROM && archive.getArchiveLoader() != null;
    }

    private @Nullable Map<String, UProperty> readPlainStruct(String structName, long size) {
        if (!hasPlainStructs() || PLAIN_STRUCTS.contains(structName)) {
            return null;
        }
        var start = archive.getOffset();
        try {
            var fields = readPlainFields(structName, 0, size);
            archive.setPosition(start + (int) size);
            return fields;
        } catch (RuntimeException otherLayout) {
            archive.setPosition(start);
            return null;
        }
    }

    private @Nullable List<Map<String, UProperty>> readPlainElements(String name, int count, int size, long tagSize) {
        if (!hasPlainStructs() || !STRUCT_ARRAYS.contains(name) || count <= 0 || count > size) {
            return null;
        }
        var start = archive.getOffset();
        try {
            var structName = archive.getArchiveLoader().findArrayElementStruct(name);
            if (structName != null) {
                var elements = new ArrayList<Map<String, UProperty>>(count);
                for (var i = 0; i < count; i++) {
                    elements.add(readPlainFields(structName, 0, tagSize / count));
                }
                archive.setPosition(start + size);
                return elements;
            }
        } catch (RuntimeException otherLayout) {
        }
        archive.setPosition(start);
        return null;
    }

    private Map<String, UProperty> readPlainFields(String structName, int depth, long mostBytes) {
        var layout =
                depth > MOST_STRUCT_DEPTH ? null : archive.getArchiveLoader().findStructLayout(structName);
        if (layout == null) {
            throw new IllegalStateException("No layout of struct " + structName);
        }
        var start = archive.getOffset();
        var fields = new HashMap<String, UProperty>();
        for (var field : layout) {
            for (var index = 0; index < field.arrayDim(); index++) {
                if (mostBytes > 0 && archive.getOffset() - start >= mostBytes) {
                    continue;
                }
                var value = readPlainValue(field, index, depth);
                if (index == 0) {
                    fields.put(field.name(), value);
                }
                if (field.arrayDim() > 1) {
                    fields.put(field.name() + "[" + index + "]", value);
                }
            }
        }
        return fields;
    }

    private UProperty readPlainValue(StructLayouts.Field field, int index, int depth) {
        var builder = UProperty.builder().name(field.name()).type(field.type()).arrayIndex(index);
        switch (field.type()) {
            case Byte -> builder.uInt8Value(archive.readUInt8());
            case Int -> builder.int32Value(archive.readInt());
            case Bool -> builder.array(archive.readUInt8() != 0);
            case Float -> builder.floatValue(archive.readFloat());
            case Object, Name -> builder.indexValue(archive.readIndex());
            case Str -> builder.stringValue(archive.readString());
            case Struct -> {
                var structName = field.structName();
                builder.structName(structName);
                switch (structName) {
                    case "Vector" -> builder.vectorValue(archive.readVector());
                    case "Rotator" -> builder.rotatorValue(URotator.of(archive));
                    case "Color" -> builder.colorValue(UColor.of(archive));
                    default -> builder.subProperties(List.of(readPlainFields(structName, depth + 1, 0)));
                }
            }
            default -> throw new IllegalStateException("No plain layout of " + field.type() + " " + field.name());
        }
        return builder.build();
    }

    private Map<String, UProperty> extractPropertiesMap() {
        var propertiesByNames = new HashMap<String, UProperty>();
        var properties = extractProperties();
        for (var property : properties) {
            var name = property.getName();
            var earlier = propertiesByNames.put(name, property);
            if (property.getArrayIndex() > 0) {
                propertiesByNames.put(name + "[" + property.getArrayIndex() + "]", property);
                if (earlier != null && earlier.getArrayIndex() == 0) {
                    propertiesByNames.put(name + "[0]", earlier);
                }
            } else if (earlier != null) {
                log.debug("Property already exists: {}", name);
            }
        }
        return propertiesByNames;
    }

    private static long extractSize(int sizeType, UArchive archive) {
        return switch (sizeType) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 12;
            case 4 -> 16;
            case 5 -> archive.readUInt8();
            case 6 -> archive.readUInt16();
            case 7 -> archive.readUInt32();
            default -> throw new IllegalArgumentException("Unknown size type: " + sizeType);
        };
    }
}

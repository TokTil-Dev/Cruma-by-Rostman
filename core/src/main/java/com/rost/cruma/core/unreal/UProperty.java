package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.primitives.UColor;
import com.rost.cruma.core.unreal.primitives.UMatrix;
import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public class UProperty {
    private final String name;

    private final int info;
    private final Type type;
    private final int sizeType;
    private final boolean isArray;

    private final @Nullable String structName;
    private final long size;
    private final int arrayIndex;
    private final int arraySize;

    private short uInt8Value;
    private int int32Value;
    private float floatValue;
    private int indexValue;
    private Vec3 vectorValue;
    private URotator rotatorValue;
    private UMatrix matrixValue;
    private UColor colorValue;

    private final short[] dataValue;
    private final int[] objectArray;
    private final int[] intArray;
    private final List<Map<String, UProperty>> subProperties;
    private final String stringValue;

    UProperty(
            String name,
            int info,
            Type type,
            int sizeType,
            boolean isArray,
            String structName,
            long size,
            int arrayIndex,
            int arraySize,
            short uInt8Value,
            int int32Value,
            float floatValue,
            int indexValue,
            Vec3 vectorValue,
            URotator rotatorValue,
            UMatrix matrixValue,
            UColor colorValue,
            short[] dataValue,
            int[] objectArray,
            int[] intArray,
            List<Map<String, UProperty>> subProperties,
            String stringValue) {
        this.name = name;
        this.info = info;
        this.type = type;
        this.sizeType = sizeType;
        this.isArray = isArray;
        this.structName = structName;
        this.size = size;
        this.arrayIndex = arrayIndex;
        this.arraySize = arraySize;
        this.uInt8Value = uInt8Value;
        this.int32Value = int32Value;
        this.floatValue = floatValue;
        this.indexValue = indexValue;
        this.vectorValue = vectorValue;
        this.rotatorValue = rotatorValue;
        this.matrixValue = matrixValue;
        this.colorValue = colorValue;
        this.dataValue = dataValue;
        this.objectArray = objectArray;
        this.intArray = intArray;
        this.subProperties = subProperties;
        this.stringValue = stringValue;
    }

    public enum Type {
        Byte(1),
        Int(2),
        Bool(3),
        Float(4),
        Object(5),
        Name(6),
        Delegate(7),
        Class(8),
        Array(9),
        Struct(10),
        Vector(11),
        Rotator(12),
        Str(13),
        Map(14),
        FixedArray(15);
        private final int id;

        Type(int id) {
            this.id = id;
        }

        public static Type fromId(int id) {
            return switch (id) {
                case 1 -> Byte;
                case 2 -> Int;
                case 3 -> Bool;
                case 4 -> Float;
                case 5 -> Object;
                case 6 -> Name;
                case 7 -> Delegate;
                case 8 -> Class;
                case 9 -> Array;
                case 10 -> Struct;
                case 11 -> Vector;
                case 12 -> Rotator;
                case 13 -> Str;
                case 14 -> Map;
                case 15 -> FixedArray;
                default -> throw new IllegalArgumentException("Unknown type id: " + id);
            };
        }

        public int getId() {
            return this.id;
        }
    }

    public static UPropertyBuilder builder() {
        return new UPropertyBuilder();
    }

    public static final class UPropertyBuilder {
        private String name;
        private int info;
        private Type type;
        private int sizeType;
        private boolean isArray;
        private String structName;
        private long size;
        private int arrayIndex;
        private int arraySize;
        private short uInt8Value;
        private boolean uInt8ValueSet;
        private int int32Value;
        private boolean int32ValueSet;
        private float floatValue;
        private boolean floatValueSet;
        private int indexValue;
        private boolean indexValueSet;
        private Vec3 vectorValue;
        private URotator rotatorValue;
        private UMatrix matrixValue;
        private UColor colorValue;
        private short[] dataValue;
        private int[] objectArray;
        private int[] intArray;
        private List<Map<String, UProperty>> subProperties;
        private boolean subPropertiesSet;
        private String stringValue;

        private UPropertyBuilder() {}

        public UPropertyBuilder name(String name) {
            this.name = name;
            return this;
        }

        public UPropertyBuilder info(int info) {
            this.info = info;
            return this;
        }

        public UPropertyBuilder type(Type type) {
            this.type = type;
            return this;
        }

        public UPropertyBuilder sizeType(int sizeType) {
            this.sizeType = sizeType;
            return this;
        }

        public UPropertyBuilder array(boolean array) {
            this.isArray = array;
            return this;
        }

        public UPropertyBuilder structName(String structName) {
            this.structName = structName;
            return this;
        }

        public UPropertyBuilder size(long size) {
            this.size = size;
            return this;
        }

        public UPropertyBuilder arrayIndex(int arrayIndex) {
            this.arrayIndex = arrayIndex;
            return this;
        }

        public UPropertyBuilder arraySize(int arraySize) {
            this.arraySize = arraySize;
            return this;
        }

        public UPropertyBuilder uInt8Value(short uInt8Value) {
            this.uInt8Value = uInt8Value;
            this.uInt8ValueSet = true;
            return this;
        }

        public UPropertyBuilder int32Value(int int32Value) {
            this.int32Value = int32Value;
            this.int32ValueSet = true;
            return this;
        }

        public UPropertyBuilder floatValue(float floatValue) {
            this.floatValue = floatValue;
            this.floatValueSet = true;
            return this;
        }

        public UPropertyBuilder indexValue(int indexValue) {
            this.indexValue = indexValue;
            this.indexValueSet = true;
            return this;
        }

        public UPropertyBuilder vectorValue(Vec3 vectorValue) {
            this.vectorValue = vectorValue;
            return this;
        }

        public UPropertyBuilder rotatorValue(URotator rotatorValue) {
            this.rotatorValue = rotatorValue;
            return this;
        }

        public UPropertyBuilder matrixValue(UMatrix matrixValue) {
            this.matrixValue = matrixValue;
            return this;
        }

        public UPropertyBuilder colorValue(UColor colorValue) {
            this.colorValue = colorValue;
            return this;
        }

        public UPropertyBuilder dataValue(short[] dataValue) {
            this.dataValue = dataValue;
            return this;
        }

        public UPropertyBuilder objectArray(int[] objectArray) {
            this.objectArray = objectArray;
            return this;
        }

        public UPropertyBuilder intArray(int[] intArray) {
            this.intArray = intArray;
            return this;
        }

        public UPropertyBuilder subProperties(List<Map<String, UProperty>> subProperties) {
            this.subProperties = subProperties;
            this.subPropertiesSet = true;
            return this;
        }

        public UPropertyBuilder stringValue(String stringValue) {
            this.stringValue = stringValue;
            return this;
        }

        public UProperty build() {
            return new UProperty(
                    name,
                    info,
                    type,
                    sizeType,
                    isArray,
                    structName,
                    size,
                    arrayIndex,
                    arraySize,
                    uInt8ValueSet ? uInt8Value : -1,
                    int32ValueSet ? int32Value : -1,
                    floatValueSet ? floatValue : -1,
                    indexValueSet ? indexValue : Integer.MIN_VALUE,
                    vectorValue,
                    rotatorValue,
                    matrixValue,
                    colorValue,
                    dataValue,
                    objectArray,
                    intArray,
                    subPropertiesSet ? subProperties : List.of(),
                    stringValue);
        }
    }

    public boolean isTrue() {
        return type == Type.Bool && isArray;
    }

    public UProperty subProperty(String name) {
        return subProperty(name, 0);
    }

    public UProperty subProperty(String name, int index) {
        if (index < 0 || index >= subProperties.size()) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + index);
        }
        var property = subProperties.get(index).get(name);
        if (property == null) {
            throw new NullPointerException("Property not found: " + name);
        }
        return property;
    }

    public String getName() {
        return this.name;
    }

    public int getInfo() {
        return this.info;
    }

    public Type getType() {
        return this.type;
    }

    public int getSizeType() {
        return this.sizeType;
    }

    public boolean isArray() {
        return this.isArray;
    }

    public String getStructName() {
        return this.structName;
    }

    public long getSize() {
        return this.size;
    }

    public int getArrayIndex() {
        return this.arrayIndex;
    }

    public int getArraySize() {
        return this.arraySize;
    }

    public short getUInt8Value() {
        return this.uInt8Value;
    }

    public int getInt32Value() {
        return this.int32Value;
    }

    public float getFloatValue() {
        return this.floatValue;
    }

    public int getIndexValue() {
        return this.indexValue;
    }

    public Vec3 getVectorValue() {
        return this.vectorValue;
    }

    public URotator getRotatorValue() {
        return this.rotatorValue;
    }

    public UMatrix getMatrixValue() {
        return this.matrixValue;
    }

    public UColor getColorValue() {
        return this.colorValue;
    }

    public short[] getDataValue() {
        return this.dataValue;
    }

    public int[] getObjectArray() {
        return this.objectArray;
    }

    public int[] getIntArray() {
        return this.intArray;
    }

    public String getStringValue() {
        return stringValue;
    }

    public List<Map<String, UProperty>> getSubProperties() {
        return this.subProperties;
    }
}

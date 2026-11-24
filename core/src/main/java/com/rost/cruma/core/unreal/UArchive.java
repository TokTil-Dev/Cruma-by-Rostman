package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UDecryptor.DecryptedBuffer;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

public class UArchive implements AutoCloseable {
    public static final String NONE_NAME = "None";

    private final String name;
    private final ByteBuffer input;
    private final DecryptedBuffer inputOwner;
    private final @Nullable UArchiveLoader archiveLoader;
    private final UObjectLoader objectLoader;
    private final UPropertyExtractor propertyExtractor;
    private final int initialPosition;
    private final UPackageHeader header;
    private int objectEnd = -1;
    private final List<String> nameMap = new ArrayList<>();
    private final List<UObjectImport> importMap = new ArrayList<>();
    private final List<UObjectExport> exportMap = new ArrayList<>();
    private Map<String, List<UObjectExport>> exportsByClass;

    public UArchive(String name, ByteBuffer input, UArchiveLoader archiveLoader) {
        this(name, input, archiveLoader, null);
    }

    public UArchive(String name, DecryptedBuffer input, UArchiveLoader archiveLoader) {
        this(name, input.getBuffer(), archiveLoader, input);
    }

    private UArchive(String name, ByteBuffer input, UArchiveLoader archiveLoader, DecryptedBuffer inputOwner) {
        this.name = name;
        this.input = input;
        this.inputOwner = inputOwner;
        this.archiveLoader = archiveLoader;
        this.objectLoader = new UObjectLoader(this, archiveLoader);
        this.propertyExtractor = new UPropertyExtractor(this);
        this.initialPosition = input.position();
        this.header = readHeader(input);

        input.position(initialPosition + header.getNameOffset());
        for (int i = 0; i < header.getNameCount(); i++) {
            var headerName = readString(input);
            var flags = readUInt32(input);
            nameMap.add(headerName);
        }

        input.position(initialPosition + header.getImportOffset());
        for (int i = 0; i < header.getImportCount(); i++) {
            var objectImport = UObjectImport.builder()
                    .classPackage(readName(input))
                    .className(readName(input))
                    .packageIndex(input.getInt())
                    .objectName(readName(input))
                    .build();
            importMap.add(objectImport);
        }

        input.position(initialPosition + header.getExportOffset());
        record RawExport(
                int classIndex,
                int superIndex,
                int packageIndex,
                String objectName,
                long objectFlags,
                int serialSize,
                int serialOffset) {}
        var rawExports = new ArrayList<RawExport>(header.getExportCount());
        for (int i = 0; i < header.getExportCount(); i++) {
            var classIndex = readIndex(input);
            var superIndex = readIndex(input);
            var packageIndex = input.getInt();
            var objectName = readName(input);
            var objectFlags = readUInt32(input);
            var serialSize = readIndex(input);
            rawExports.add(new RawExport(
                    classIndex,
                    superIndex,
                    packageIndex,
                    objectName,
                    objectFlags,
                    serialSize,
                    serialSize > 0 ? readIndex(input) : 0));
        }

        IntFunction<String> resolveExportReference = index -> {
            if (index < 0) {
                if (-index > importMap.size()) {
                    throw new IllegalArgumentException("Index out of importMap bounds: " + index);
                }
                return importMap.get(-index - 1).getObjectName();
            }
            if (index > 0) {
                if (index > rawExports.size()) {
                    throw new IllegalArgumentException("Index out of exportMap bounds: " + index);
                }
                return rawExports.get(index - 1).objectName();
            }
            return NONE_NAME;
        };
        for (var raw : rawExports) {
            var objectExport = UObjectExport.builder()
                    .classIndex(raw.classIndex())
                    .className(resolveExportReference.apply(raw.classIndex()))
                    .superName(resolveExportReference.apply(raw.superIndex()))
                    .packageIndex(raw.packageIndex())
                    .objectName(raw.objectName())
                    .objectFlags(raw.objectFlags())
                    .serialSize(raw.serialSize())
                    .serialOffset(raw.serialOffset())
                    .build();
            exportMap.add(objectExport);
        }
    }

    static String readString(ByteBuffer buffer) {
        int length = readIndex(buffer);
        if (length < 0) {
            var characters = new char[-length - 1];
            for (var i = 0; i < characters.length; i++) {
                characters[i] = (char) (buffer.get() & 0xFF | (buffer.get() & 0xFF) << 8);
            }
            buffer.getShort();
            return new String(characters);
        }
        if (length == 0) {
            return "";
        }
        var bytes = new byte[length - 1];
        buffer.get(bytes);
        buffer.get();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (inputOwner != null) {
            inputOwner.close();
        }
    }

    public int getPosition() {
        return input.position();
    }

    public int getOffset() {
        return input.position() - initialPosition;
    }

    public int getObjectEnd() {
        return objectEnd;
    }

    public void setObjectEnd(int packageRelativeEnd) {
        this.objectEnd = packageRelativeEnd;
    }

    public int getRemainingInObject() {
        return objectEnd < 0 ? Integer.MAX_VALUE : objectEnd - getOffset();
    }

    public void setPosition(int newPosition) {
        input.position(initialPosition + newPosition);
    }

    public int readInt() {
        return input.getInt();
    }

    public float readFloat() {
        return input.getFloat();
    }

    public void skipBytes(int size) {
        input.position(input.position() + size);
    }

    public boolean hasMoreBytes(int size) {
        return input.remaining() >= size;
    }

    public int readArrayIndex() {
        var first = readUInt8();
        if ((first & 0x80) == 0) {
            return first;
        }

        if ((first & 0x40) == 0) {
            return ((first & 0x3f) << 8) | readUInt8();
        }
        return ((first & 0x3f) << 24) | (readUInt8() << 16) | (readUInt8() << 8) | readUInt8();
    }

    public boolean readBool() {
        return input.get() != 0;
    }

    public int readIndex() {
        return readIndex(input);
    }

    public Vec3 readVector() {
        return new Vec3(readFloat(), readFloat(), readFloat());
    }

    public Vec4 readVector4() {
        return new Vec4(readFloat(), readFloat(), readFloat(), readFloat());
    }

    public short readUInt8() {
        return (short) (input.get() & 0xFF);
    }

    public byte readByte() {
        return input.get();
    }

    public short readInt16() {
        return input.getShort();
    }

    public int readUInt16() {
        return readUInt16(input);
    }

    public long readUInt32() {
        return readUInt32(input);
    }

    public long readUInt64() {
        return readUInt64(input);
    }

    public String readName() {
        return readName(input);
    }

    public String resolveName(int index) {
        return index >= 0 && index < nameMap.size() ? nameMap.get(index) : NONE_NAME;
    }

    public String readString() {
        return readString(input);
    }

    public boolean hasMoreBytes() {
        return input.hasRemaining();
    }

    public <T> List<T> readList(Function<UArchive, T> factory) {
        var size = readIndex();
        var list = new ArrayList<T>(size);
        for (int i = 0; i < size; i++) {
            list.add(factory.apply(this));
        }
        return list;
    }

    public <T> T[] readArray(Class<T> clazz, Function<UArchive, T> factory) {
        var size = readIndex();
        @SuppressWarnings("unchecked")
        var array = (T[]) Array.newInstance(clazz, size);
        for (int i = 0; i < size; i++) {
            array[i] = factory.apply(this);
        }
        return array;
    }

    public int[] readUInt16Array() {
        var size = readIndex();
        var array = new int[size];
        for (int i = 0; i < size; i++) {
            array[i] = readUInt16();
        }
        return array;
    }

    public byte[] readByteArray() {
        var size = readIndex();
        var array = new byte[size];
        for (int i = 0; i < size; i++) {
            array[i] = readByte();
        }
        return array;
    }

    public short[] readUInt8Array() {
        var size = readIndex();
        var array = new short[size];
        for (int i = 0; i < size; i++) {
            array[i] = readUInt8();
        }
        return array;
    }

    public record ObjectWithOuter<T>(T object, @Nullable UObject outer) {}

    @SuppressWarnings("unchecked")
    public <T> void loadObjectsWithOuter(String className, List<ObjectWithOuter<T>> objects) {
        for (var objectExport : exportMap) {
            if (className.equals(objectExport.getClassName())) {
                var object = (T) objectLoader.exportObject(objectExport);
                var packageIndex = objectExport.getPackageIndex();
                var outer = packageIndex != 0 ? objectLoader.loadObject(packageIndex) : null;
                objects.add(new ObjectWithOuter<>(object, outer));
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void loadObjects(String className, List<T> objects) {
        for (var objectExport : exportsOfClass(className)) {
            objects.add((T) objectLoader.exportObject(objectExport));
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void loadEmitterActors(List<T> objects) {
        for (var objectExport : exportMap) {
            if (objectExport.getClassIndex() < 0 && objectLoader.isEmitterExport(objectExport)) {
                objects.add((T) objectLoader.exportObject(objectExport));
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void loadObjectsUnder(String className, String outerName, List<T> objects) {
        for (var objectExport : exportMap) {
            if (className.equals(objectExport.getClassName())
                    && outerName.equals(getObjectName(objectExport.getPackageIndex()))) {
                objects.add((T) objectLoader.exportObject(objectExport));
            }
        }
    }

    public String getName() {
        return this.name;
    }

    public UObjectLoader getObjectLoader() {
        return this.objectLoader;
    }

    public UPropertyExtractor getPropertyExtractor() {
        return this.propertyExtractor;
    }

    public @Nullable UArchiveLoader getArchiveLoader() {
        return this.archiveLoader;
    }

    public UPackageHeader getHeader() {
        return this.header;
    }

    public List<UObjectImport> getImportMap() {
        return this.importMap;
    }

    public List<UObjectExport> getExportMap() {
        return this.exportMap;
    }

    private List<UObjectExport> exportsOfClass(String className) {
        if (exportsByClass == null) {
            var index = new HashMap<String, List<UObjectExport>>();
            for (var objectExport : exportMap) {
                index.computeIfAbsent(objectExport.getClassName(), ignored -> new ArrayList<>())
                        .add(objectExport);
            }
            exportsByClass = index;
        }
        return exportsByClass.getOrDefault(className, List.of());
    }

    private String readName(ByteBuffer buffer) {
        var index = readIndex(buffer);
        return index < nameMap.size() ? nameMap.get(index) : NONE_NAME;
    }

    private String getObjectName(int index) {
        if (index < 0) {
            if (-index > importMap.size()) {
                throw new IllegalArgumentException("Index out of importMap bounds: " + index);
            }
            return importMap.get(-index - 1).getObjectName();
        }
        if (index > 0) {
            if (index > exportMap.size()) {
                throw new IllegalArgumentException("Index out of exportMap bounds: " + index);
            }
            return exportMap.get(index - 1).getObjectName();
        }
        return NONE_NAME;
    }

    private static UPackageHeader readHeader(ByteBuffer input) {
        var headerMagicValue = input.getInt();
        if (headerMagicValue != UPackageHeader.PACKAGE_MAGIC) {
            throw new IllegalArgumentException("Lineage 2 package magic " + headerMagicValue + " must be equal to "
                    + UPackageHeader.PACKAGE_MAGIC);
        }

        return UPackageHeader.builder()
                .magic(headerMagicValue)
                .fileVersion(input.getShort())
                .licenseVersion(input.getShort())
                .flags(readUInt32(input))
                .nameCount(input.getInt())
                .nameOffset(input.getInt())
                .exportCount(input.getInt())
                .exportOffset(input.getInt())
                .importCount(input.getInt())
                .importOffset(input.getInt())
                .guid(new UGuid(readUInt32(input), readUInt32(input), readUInt32(input), readUInt32(input)))
                .generations(readGenerations(input, input.getInt()))
                .build();
    }

    private static int readUInt16(ByteBuffer buffer) {
        return buffer.getShort() & 0xFFFF;
    }

    private static long readUInt32(ByteBuffer buffer) {
        return buffer.getInt() & 0xFFFFFFFFL;
    }

    private static long readUInt64(ByteBuffer buffer) {
        return buffer.getLong();
    }

    private static List<UGenerationInfo> readGenerations(ByteBuffer buffer, int count) {
        var generations = new ArrayList<UGenerationInfo>(count);
        for (int i = 0; i < count; i++) {
            generations.add(new UGenerationInfo(buffer.getInt(), buffer.getInt()));
        }
        return generations;
    }

    private static int readIndex(ByteBuffer buffer) {
        byte b = buffer.get();

        boolean negative = (b & (1 << 7)) != 0;
        int value = b & 0x3f;

        if ((b & (1 << 6)) != 0) {
            int shift = 6;

            do {
                b = buffer.get();
                int data = b & 0x7f;
                data <<= shift;
                value |= data;
                shift += 7;
            } while (((b & (1 << 7)) != 0) && (shift < 32));
        }
        return negative ? -value : value;
    }
}

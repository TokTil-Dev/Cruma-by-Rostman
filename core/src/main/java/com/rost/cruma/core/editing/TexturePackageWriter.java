package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.writeIndex;
import static com.rost.cruma.core.editing.PackageBytes.writeInt;

import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureFormat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.Nullable;

public final class TexturePackageWriter {
    private static final int RGBA8 = 5;
    private static final int DXT1 = 3;
    private static final int DXT3 = 7;
    private static final int DXT5 = 8;
    private static final int HEADER_SIZE = 64;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_SOURCE_BYTES = 128L << 20;
    private static final int TEXTURE_FLAGS = 0x000f0004;
    private static final int MAX_FILE_NAME = 48;

    private TexturePackageWriter() {}

    public record Result(Path packageFile, String objectPath, int width, int height, int mipCount, String format) {}

    record PackagedTexture(
            String name,
            int format,
            int width,
            int height,
            int clampU,
            int clampV,
            List<byte[]> levels,
            boolean alpha,
            boolean masked,
            boolean twoSided,
            int uClampMode,
            int vClampMode,
            @Nullable String animNext,
            float minFrameRate,
            float maxFrameRate,
            int totalFrameNum,
            int primeCount,
            boolean oneTimeAnimLoop) {
        PackagedTexture renamed(String newName, List<byte[]> newLevels) {
            return new PackagedTexture(
                    newName,
                    format,
                    width,
                    height,
                    clampU,
                    clampV,
                    newLevels,
                    alpha,
                    masked,
                    twoSided,
                    uClampMode,
                    vClampMode,
                    animNext,
                    minFrameRate,
                    maxFrameRate,
                    totalFrameNum,
                    primeCount,
                    oneTimeAnimLoop);
        }

        Texture entity() {
            var kind = switch (format) {
                case DXT1 -> TextureFormat.TEXTURE_DXT1;
                case DXT3 -> TextureFormat.TEXTURE_DXT3;
                case DXT5 -> TextureFormat.TEXTURE_DXT5;
                default -> TextureFormat.TEXTURE_RGBA;
            };
            return new Texture(kind, width, height, levels, alpha);
        }
    }

    private record Mip(int width, int height, byte[] pixels) {}

    private record Image(int format, List<Mip> mips, boolean alpha) {}

    public static Result importImage(Path source, Path directory, String packageName, String textureName)
            throws IOException {
        validateName(packageName);
        validateName(textureName);
        var image = read(source);
        var bytes = packageBytes(image, packageName, textureName);
        var output = directory.resolve(packageName + ".utx");
        Files.createDirectories(directory);
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
        var first = image.mips().getFirst();
        return new Result(
                output,
                packageName + "." + textureName,
                first.width(),
                first.height(),
                image.mips().size(),
                switch (image.format()) {
                    case DXT1 -> "DXT1";
                    case DXT3 -> "DXT3";
                    case DXT5 -> "DXT5";
                    default -> "RGBA8";
                });
    }

    public static String textureName(Path image) {
        var name = image.getFileName().toString();
        var dot = name.lastIndexOf('.');
        var base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isEmpty() || base.chars().allMatch(ch -> ch == '_')) {
            return "Texture0";
        }
        var kept = base.substring(0, Math.min(base.length(), MAX_FILE_NAME));
        return Character.isDigit(kept.charAt(0)) || kept.equalsIgnoreCase("None") ? "T_" + kept : kept;
    }

    static PackagedTexture readTexture(Path source) throws IOException {
        var image = read(source);
        var first = image.mips().getFirst();
        var levels = new ArrayList<byte[]>(image.mips().size());
        for (var mip : image.mips()) {
            levels.add(mip.pixels());
        }
        return new PackagedTexture(
                "",
                image.format(),
                first.width(),
                first.height(),
                first.width(),
                first.height(),
                List.copyOf(levels),
                image.alpha(),
                false,
                false,
                0,
                0,
                null,
                0f,
                0f,
                0,
                0,
                false);
    }

    static void addTextures(ClassPackageWriter writer, List<PackagedTexture> textures) throws IOException {
        var textureClass = writer.importObject(writer.importPackage("Engine"), "Core", "Class", "Texture");
        var indices = new HashMap<String, Integer>();
        var next = writer.getExportCount() + 1;
        var fresh = new ArrayList<PackagedTexture>();
        for (var index : writer.getChildren(0)) {
            indices.put(writer.exportName(index).toLowerCase(Locale.ROOT), index);
        }
        for (var texture : textures) {
            if (indices.putIfAbsent(texture.name().toLowerCase(Locale.ROOT), next) == null) {
                fresh.add(texture);
                next++;
            }
        }
        for (var texture : fresh) {
            var tags = textureTags(writer, texture, indices);
            writer.addPlacedExport(
                    textureClass, 0, 0, texture.name(), TEXTURE_FLAGS, offset -> texturePayload(tags, texture, offset));
        }
    }

    private static byte[] textureTags(
            ClassPackageWriter writer, PackagedTexture texture, Map<String, Integer> indices) {
        var tags = TaggedProperties.empty(writer);
        tags.add(writer.intern("Format"), TaggedProperties.BYTE).setByteValue(texture.format());
        tags.add(writer.intern("USize"), TaggedProperties.INT).setIntValue(texture.width());
        tags.add(writer.intern("VSize"), TaggedProperties.INT).setIntValue(texture.height());
        tags.add(writer.intern("UClamp"), TaggedProperties.INT).setIntValue(texture.clampU());
        tags.add(writer.intern("VClamp"), TaggedProperties.INT).setIntValue(texture.clampV());
        tags.add(writer.intern("UBits"), TaggedProperties.BYTE)
                .setByteValue(Integer.numberOfTrailingZeros(texture.width()));
        tags.add(writer.intern("VBits"), TaggedProperties.BYTE)
                .setByteValue(Integer.numberOfTrailingZeros(texture.height()));
        addFlag(writer, tags, "bAlphaTexture", texture.alpha());
        addFlag(writer, tags, "bMasked", texture.masked());
        addFlag(writer, tags, "bTwoSided", texture.twoSided());
        addFlag(writer, tags, "OneTimeAnimLoop", texture.oneTimeAnimLoop());
        if (texture.uClampMode() != 0) {
            tags.add(writer.intern("UClampMode"), TaggedProperties.BYTE).setByteValue(texture.uClampMode());
        }
        if (texture.vClampMode() != 0) {
            tags.add(writer.intern("VClampMode"), TaggedProperties.BYTE).setByteValue(texture.vClampMode());
        }
        if (texture.animNext() != null) {
            tags.add(writer.intern("AnimNext"), TaggedProperties.OBJECT)
                    .setIndex(indices.get(texture.animNext().toLowerCase(Locale.ROOT)));
        }
        if (texture.minFrameRate() != 0f) {
            tags.add(writer.intern("MinFrameRate"), TaggedProperties.FLOAT).setFloatValue(texture.minFrameRate());
        }
        if (texture.maxFrameRate() != 0f) {
            tags.add(writer.intern("MaxFrameRate"), TaggedProperties.FLOAT).setFloatValue(texture.maxFrameRate());
        }
        if (texture.totalFrameNum() != 0) {
            tags.add(writer.intern("TotalFrameNum"), TaggedProperties.INT).setIntValue(texture.totalFrameNum());
        }
        if (texture.primeCount() != 0) {
            tags.add(writer.intern("PrimeCount"), TaggedProperties.BYTE).setByteValue(texture.primeCount());
        }
        return tags.toBytes();
    }

    private static void addFlag(ClassPackageWriter writer, TaggedProperties tags, String name, boolean value) {
        if (value) {
            tags.add(writer.intern(name), TaggedProperties.BOOL).setBool(true);
        }
    }

    private static byte[] texturePayload(byte[] tags, PackagedTexture texture, int offset) {
        var payload = new ByteArrayOutputStream();
        payload.writeBytes(tags);
        writeIndex(payload, texture.levels().size());
        for (var level = 0; level < texture.levels().size(); level++) {
            var pixels = texture.levels().get(level);
            var width = Math.max(1, texture.width() >> level);
            var height = Math.max(1, texture.height() >> level);
            var size = new ByteArrayOutputStream(5);
            writeIndex(size, pixels.length);
            writeInt(payload, offset + payload.size() + 4 + size.size() + pixels.length);
            payload.writeBytes(size.toByteArray());
            payload.writeBytes(pixels);
            writeInt(payload, width);
            writeInt(payload, height);
            payload.write(Integer.numberOfTrailingZeros(width));
            payload.write(Integer.numberOfTrailingZeros(height));
        }
        return payload.toByteArray();
    }

    private static void validateName(String name) throws IOException {
        if ("None".equalsIgnoreCase(name)) {
            throw new IOException("None is a reserved Unreal name. Choose another package or texture name.");
        }
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IOException(
                    "Package and texture names must begin with a letter or underscore and contain at most 64 letters,"
                            + " digits or underscores.");
        }
    }

    private static void validateDimensions(int width, int height) throws IOException {
        if (width < 1
                || height < 1
                || width > MAX_DIMENSION
                || height > MAX_DIMENSION
                || Integer.bitCount(width) != 1
                || Integer.bitCount(height) != 1) {
            throw new IOException("Texture dimensions must be powers of two between 1 and 4096.");
        }
    }

    private static Image read(Path source) throws IOException {
        if (Files.size(source) > MAX_SOURCE_BYTES) {
            throw new IOException("Image exceeds the 128 MiB import limit.");
        }
        return source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dds")
                ? readDds(source)
                : readImage(source);
    }

    private static Image readImage(Path source) throws IOException {
        BufferedImage decoded;
        try (var input = ImageIO.createImageInputStream(source.toFile())) {
            if (input == null) {
                throw new IOException("Cannot open image.");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image. Use PNG, JPEG, BMP or a DXT1/3/5 DDS file.");
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                validateDimensions(reader.getWidth(0), reader.getHeight(0));
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        var width = decoded.getWidth();
        var height = decoded.getHeight();
        var argb = decoded.getRGB(0, 0, width, height, null, 0, width);
        var levels = new ArrayList<Mip>();
        var alpha = false;
        while (true) {
            var pixels = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
            for (var i = 0; i < argb.length; i++) {
                var value = argb[i];
                pixels[i * 4] = (byte) value;
                pixels[i * 4 + 1] = (byte) (value >>> 8);
                pixels[i * 4 + 2] = (byte) (value >>> 16);
                pixels[i * 4 + 3] = (byte) (value >>> 24);
                alpha |= (value >>> 24) != 255;
            }
            levels.add(new Mip(width, height, pixels));
            if (width == 1 && height == 1) {
                break;
            }
            var nextWidth = Math.max(1, width / 2);
            var nextHeight = Math.max(1, height / 2);
            var next = new int[nextWidth * nextHeight];
            for (var y = 0; y < nextHeight; y++) {
                for (var x = 0; x < nextWidth; x++) {
                    double red = 0, green = 0, blue = 0, coverage = 0;
                    for (var dy = 0; dy < 2; dy++) {
                        for (var dx = 0; dx < 2; dx++) {
                            var c = argb[Math.min(height - 1, y * 2 + dy) * width + Math.min(width - 1, x * 2 + dx)];
                            var a = (c >>> 24) / 255.0;
                            coverage += a;
                            red += toLinear((c >>> 16) & 255) * a;
                            green += toLinear((c >>> 8) & 255) * a;
                            blue += toLinear(c & 255) * a;
                        }
                    }
                    var a = Math.clamp((int) Math.round(coverage * 255 / 4), 0, 255);
                    next[y * nextWidth + x] = a << 24
                            | (coverage == 0
                                    ? 0
                                    : toSrgb(red / coverage) << 16
                                            | toSrgb(green / coverage) << 8
                                            | toSrgb(blue / coverage));
                }
            }
            width = nextWidth;
            height = nextHeight;
            argb = next;
        }
        return new Image(RGBA8, List.copyOf(levels), alpha);
    }

    private static double toLinear(int channel) {
        var c = channel / 255.0;
        return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
    }

    private static int toSrgb(double c) {
        return Math.clamp(
                (int) Math.round(255 * (c <= .0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - .055)), 0, 255);
    }

    private static Image readDds(Path path) throws IOException {
        var bytes = Files.readAllBytes(path);
        if (bytes.length < 128) {
            throw new IOException("Truncated DDS header.");
        }
        var in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt(0) != 0x20534444 || in.getInt(4) != 124 || in.getInt(76) != 32) {
            throw new IOException("Invalid DDS header.");
        }
        var height = in.getInt(12);
        var width = in.getInt(16);
        validateDimensions(width, height);
        if (in.getInt(24) > 1 || in.getInt(112) != 0) {
            throw new IOException("Only a single 2D DDS texture is supported.");
        }
        if ((in.getInt(80) & 4) == 0) {
            throw new IOException("DDS import requires DXT1, DXT3 or DXT5 compression.");
        }
        var format = switch (in.getInt(84)) {
            case 0x31545844 -> DXT1;
            case 0x33545844 -> DXT3;
            case 0x35545844 -> DXT5;
            default ->
                throw new IOException(
                        "DDS import supports legacy DXT1, DXT3 and DXT5. DX10, arrays and cubemaps are not supported.");
        };
        var declaredCount = in.getInt(28);
        var count = declaredCount == 0 ? 1 : declaredCount;
        var maxCount = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        if (count < 1 || count > maxCount) {
            throw new IOException("Invalid DDS mip count.");
        }
        var levels = new ArrayList<Mip>();
        var position = 128;
        for (var level = 0; level < count; level++) {
            var size = Math.multiplyExact(
                    Math.multiplyExact(Math.max(1, (width + 3) / 4), Math.max(1, (height + 3) / 4)),
                    format == DXT1 ? 8 : 16);
            if ((long) position + size > bytes.length) {
                throw new IOException("Truncated DDS mip " + level + ".");
            }
            levels.add(new Mip(width, height, Arrays.copyOfRange(bytes, position, position + size)));
            position += size;
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
        if (position != bytes.length) {
            throw new IOException("DDS has trailing data or additional surfaces.");
        }
        return new Image(
                format,
                List.copyOf(levels),
                format != DXT1
                        || (in.getInt(80) & 1) != 0
                        || levels.stream().anyMatch(TexturePackageWriter::doesDxt1UseTransparency));
    }

    private static boolean doesDxt1UseTransparency(Mip mip) {
        var bytes = ByteBuffer.wrap(mip.pixels()).order(ByteOrder.LITTLE_ENDIAN);
        var blocksX = Math.max(1, (mip.width() + 3) / 4);
        var blocksY = Math.max(1, (mip.height() + 3) / 4);
        for (var by = 0; by < blocksY; by++) {
            for (var bx = 0; bx < blocksX; bx++) {
                var first = Short.toUnsignedInt(bytes.getShort());
                var second = Short.toUnsignedInt(bytes.getShort());
                var indices = bytes.getInt();
                if (first > second) {
                    continue;
                }
                for (var y = 0; y < 4 && by * 4 + y < mip.height(); y++) {
                    for (var x = 0; x < 4 && bx * 4 + x < mip.width(); x++) {
                        if ((indices >>> ((y * 4 + x) * 2) & 3) == 3) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static byte[] packageBytes(Image image, String packageName, String objectName) throws IOException {
        var names = new LinkedHashMap<String, Integer>();
        for (var name : List.of(
                "None",
                "Core",
                "Package",
                "Class",
                "Engine",
                "Texture",
                objectName,
                "Format",
                "USize",
                "VSize",
                "UBits",
                "VBits",
                "UClamp",
                "VClamp",
                "bAlphaTexture")) {
            names.computeIfAbsent(name, _ -> names.size());
        }
        var first = image.mips().getFirst();
        var payload = new ByteArrayOutputStream();
        writeByteProperty(payload, names, "Format", image.format());
        writeIntProperty(payload, names, "USize", first.width());
        writeIntProperty(payload, names, "VSize", first.height());
        writeIntProperty(payload, names, "UClamp", first.width());
        writeIntProperty(payload, names, "VClamp", first.height());
        writeByteProperty(payload, names, "UBits", Integer.numberOfTrailingZeros(first.width()));
        writeByteProperty(payload, names, "VBits", Integer.numberOfTrailingZeros(first.height()));
        if (image.alpha()) {
            writeIndex(payload, names.get("bAlphaTexture"));
            payload.write(0x83);
        }
        writeIndex(payload, names.get("None"));
        writeIndex(payload, image.mips().size());
        var lazyOffsets = new ArrayList<int[]>();
        for (var mip : image.mips()) {
            var offset = payload.size();
            writeInt(payload, 0);
            writeIndex(payload, mip.pixels().length);
            payload.writeBytes(mip.pixels());
            lazyOffsets.add(new int[] {offset, HEADER_SIZE + payload.size()});
            writeInt(payload, mip.width());
            writeInt(payload, mip.height());
            payload.write(Integer.numberOfTrailingZeros(mip.width()));
            payload.write(Integer.numberOfTrailingZeros(mip.height()));
        }
        var data = payload.toByteArray();
        var dataBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (var offset : lazyOffsets) {
            dataBuffer.putInt(offset[0], offset[1]);
        }
        var body = new ByteArrayOutputStream();
        body.writeBytes(new byte[HEADER_SIZE]);
        body.writeBytes(data);
        var nameOffset = body.size();
        for (var name : names.keySet()) {
            var encoded = name.getBytes(StandardCharsets.US_ASCII);
            writeIndex(body, encoded.length + 1);
            body.writeBytes(encoded);
            body.write(0);
            writeInt(body, 0x00070010);
        }
        var importOffset = body.size();
        writeImport(body, names, "Package", 0, "Engine");
        writeImport(body, names, "Class", -1, "Texture");
        var exportOffset = body.size();
        writeIndex(body, -2);
        writeIndex(body, 0);
        writeInt(body, 0);
        writeIndex(body, names.get(objectName));
        writeInt(body, 0x000f0004);
        writeIndex(body, data.length);
        writeIndex(body, HEADER_SIZE);
        var plain = body.toByteArray();
        var header = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(PackageBytes.MAGIC)
                .putShort((short) 123)
                .putShort((short) 0)
                .putInt(0);
        header.putInt(names.size())
                .putInt(nameOffset)
                .putInt(1)
                .putInt(exportOffset)
                .putInt(2)
                .putInt(importOffset);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(packageName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(objectName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            header.put(Arrays.copyOf(digest.digest(data), 16));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        header.putInt(1).putInt(1).putInt(names.size());
        var file = packageName + ".utx";
        var result = PackageBytes.encodeNew(plain, file);
        new PackageBytes(Path.of(file), result);
        return result;
    }

    private static void writeByteProperty(
            ByteArrayOutputStream out, Map<String, Integer> names, String key, int value) {
        writeIndex(out, names.get(key));
        out.write(1);
        out.write(value);
    }

    private static void writeIntProperty(ByteArrayOutputStream out, Map<String, Integer> names, String key, int value) {
        writeIndex(out, names.get(key));
        out.write(0x22);
        writeInt(out, value);
    }

    private static void writeImport(
            ByteArrayOutputStream out, Map<String, Integer> names, String type, int outer, String name) {
        writeIndex(out, names.get("Core"));
        writeIndex(out, names.get(type));
        writeInt(out, outer);
        writeIndex(out, names.get(name));
    }
}

package com.rost.cruma.core.export;

import static com.rost.cruma.core.export.GlbWriter.ARRAY_BUFFER;
import static com.rost.cruma.core.export.GlbWriter.BIN_CHUNK;
import static com.rost.cruma.core.export.GlbWriter.ELEMENT_ARRAY_BUFFER;
import static com.rost.cruma.core.export.GlbWriter.FLOAT;
import static com.rost.cruma.core.export.GlbWriter.JSON_CHUNK;
import static com.rost.cruma.core.export.GlbWriter.MAGIC;
import static com.rost.cruma.core.export.GlbWriter.UNSIGNED_INT;
import static com.rost.cruma.core.export.GlbWriter.UNSIGNED_SHORT;
import static com.rost.cruma.core.export.GlbWriter.addTexture;
import static com.rost.cruma.core.export.GlbWriter.formatNumber;
import static com.rost.cruma.core.export.GlbWriter.pad;
import static com.rost.cruma.core.export.GlbWriter.toJsonString;
import static com.rost.cruma.core.export.GlbWriter.writeBounds;
import static com.rost.cruma.core.export.GlbWriter.writeFloats;
import static com.rost.cruma.core.export.GlbWriter.writeMaterial;

import com.rost.cruma.core.export.LocationModel.Wrap;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class RigGlbWriter implements Closeable {
    static final float SCENE_GAP_METERS = 1f;

    private static final long LARGEST_FILE = 0xFFFF_FFFFL;

    private final OutputStream binary;
    private final @Nullable ByteArrayOutputStream memory;
    private final @Nullable Path binaryFile;
    private long binarySize;
    private final List<String> views = new ArrayList<>();
    private final List<String> accessors = new ArrayList<>();
    private final Map<TimesKey, Integer> timeAccessors = new HashMap<>();
    private final List<@Nullable String> nodes = new ArrayList<>();
    private final List<Root> roots = new ArrayList<>();
    private final StringBuilder meshes = new StringBuilder();
    private final StringBuilder skins = new StringBuilder();
    private final StringBuilder animations = new StringBuilder();
    private final StringBuilder materials = new StringBuilder();
    private final StringBuilder textures = new StringBuilder();
    private final StringBuilder samplers = new StringBuilder();
    private final StringBuilder images = new StringBuilder();
    private final Map<List<Object>, Integer> textureIndices = new HashMap<>();
    private final Map<Wrap, Integer> samplerIndices = new HashMap<>();
    private int meshCount;
    private int materialCount;
    private int imageCount;
    private boolean unlit;
    private boolean ior;
    private boolean matte;
    private float metersPerUnit = 1;

    private RigGlbWriter(@Nullable Path binaryFile) throws IOException {
        this.binaryFile = binaryFile;
        if (binaryFile == null) {
            memory = new ByteArrayOutputStream(1 << 20);
            binary = memory;
        } else {
            memory = null;
            binary = new BufferedOutputStream(Files.newOutputStream(binaryFile), 1 << 16);
        }
    }

    private record Root(
            int node,
            String name,
            float[] matrix,
            String children,
            @Nullable String model,
            float low,
            float high) {}

    private record TimesKey(float[] times) {
        @Override
        public boolean equals(Object other) {
            return other instanceof TimesKey key && Arrays.equals(key.times, times);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(times);
        }
    }

    static void write(RigModel model, List<byte[]> png, Path file) throws IOException {
        try (var writer = new RigGlbWriter(null)) {
            writer.add(model, png, null);
            writer.finish(file, model.name());
        }
    }

    static RigGlbWriter scene(Path folder) throws IOException {
        return new RigGlbWriter(Files.createTempFile(folder, ".scene-", ".bin"));
    }

    static float[] reachAlongZ(RigModel model) {
        var joints = model.joints();
        var global = new float[joints.size()][];
        var skin = new float[joints.size()][];
        for (var index = 0; index < joints.size(); index++) {
            var inverseBind = Arrays.copyOfRange(model.inverseBindMatrices(), index * 16, index * 16 + 16);
            skin[index] = multiply(globalOf(index, model, global), inverseBind);
        }
        var points = model.positions();
        var near = Float.MAX_VALUE;
        var far = -Float.MAX_VALUE;
        for (var vertex = 0; vertex < model.vertexCount(); vertex++) {
            var x = points[vertex * 3];
            var y = points[vertex * 3 + 1];
            var z = points[vertex * 3 + 2];
            var placed = 0f;
            var total = 0f;
            for (var k = 0; k < 4; k++) {
                var weight = model.weights()[vertex * 4 + k];
                var joint = model.jointIndices()[vertex * 4 + k];
                if (weight <= 0 || joint < 0 || joint >= skin.length) {
                    continue;
                }
                var m = skin[joint];
                placed += weight * (m[2] * x + m[6] * y + m[10] * z + m[14]);
                total += weight;
            }
            if (total <= 0) {
                continue;
            }
            placed /= total;
            near = Math.min(near, placed);
            far = Math.max(far, placed);
        }
        return near > far ? new float[] {0, 0} : new float[] {near, far};
    }

    int getModels() {
        return roots.size();
    }

    long size() {
        return binarySize;
    }

    void add(RigModel model, List<byte[]> png, @Nullable String sceneName) throws IOException {
        metersPerUnit = model.metersPerUnit();
        var prefix = sceneName == null ? "" : sceneName + "|";
        var name = sceneName == null ? model.name() : sceneName;
        var vertexCount = model.vertexCount();
        var position = addAccessor(
                addBufferView(floatBytes(model.positions()), ARRAY_BUFFER),
                FLOAT,
                vertexCount,
                "VEC3",
                model.positions());
        var normal =
                addAccessor(addBufferView(floatBytes(model.normals()), ARRAY_BUFFER), FLOAT, vertexCount, "VEC3", null);
        var uv = addAccessor(addBufferView(floatBytes(model.uvs()), ARRAY_BUFFER), FLOAT, vertexCount, "VEC2", null);
        var joints = addAccessor(
                addBufferView(shortBytes(model.jointIndices()), ARRAY_BUFFER),
                UNSIGNED_SHORT,
                vertexCount,
                "VEC4",
                null);
        var weights =
                addAccessor(addBufferView(floatBytes(model.weights()), ARRAY_BUFFER), FLOAT, vertexCount, "VEC4", null);
        var materialBase = materialCount;
        var primitives = new StringBuilder();
        for (var primitive : model.primitives()) {
            var shortIndices = vertexCount <= 0xFFFF;
            var indices = addAccessor(
                    addBufferView(
                            shortIndices ? shortBytes(primitive.indices()) : intBytes(primitive.indices()),
                            ELEMENT_ARRAY_BUFFER),
                    shortIndices ? UNSIGNED_SHORT : UNSIGNED_INT,
                    primitive.indices().length,
                    "SCALAR",
                    null);
            primitives
                    .append(primitives.isEmpty() ? "" : ",")
                    .append("{\"attributes\":{\"POSITION\":")
                    .append(position)
                    .append(",\"NORMAL\":")
                    .append(normal)
                    .append(",\"TEXCOORD_0\":")
                    .append(uv)
                    .append(",\"JOINTS_0\":")
                    .append(joints)
                    .append(",\"WEIGHTS_0\":")
                    .append(weights)
                    .append("},\"indices\":")
                    .append(indices)
                    .append(",\"material\":")
                    .append(materialBase + primitive.material())
                    .append(",\"mode\":4}");
        }
        var inverseBind = addAccessor(
                addBufferView(floatBytes(model.inverseBindMatrices()), 0),
                FLOAT,
                model.joints().size(),
                "MAT4",
                null);

        var rootNode = nodes.size();
        var jointCount = model.joints().size();
        var meshNode = rootNode + jointCount + 1;
        var children = new ArrayList<List<Integer>>(jointCount);
        for (var index = 0; index < jointCount; index++) {
            children.add(new ArrayList<>());
        }
        var rootChildren = new StringBuilder();
        for (var index = 0; index < jointCount; index++) {
            var parent = model.joints().get(index).parent();
            if (parent >= 0) {
                children.get(parent).add(rootNode + index + 1);
            } else {
                rootChildren.append(rootChildren.isEmpty() ? "" : ",").append(rootNode + index + 1);
            }
        }
        rootChildren.append(rootChildren.isEmpty() ? "" : ",").append(meshNode);
        nodes.add(null);
        var jointList = new StringBuilder();
        for (var index = 0; index < jointCount; index++) {
            var joint = model.joints().get(index);
            var node = new StringBuilder("{\"name\":")
                    .append(toJsonString(joint.name()))
                    .append(",\"translation\":");
            writeFloats(node, joint.translation());
            node.append(",\"rotation\":");
            writeFloats(node, joint.rotation());
            if (!children.get(index).isEmpty()) {
                node.append(",\"children\":").append(children.get(index));
            }
            nodes.add(node.append('}').toString());
            jointList.append(jointList.isEmpty() ? "" : ",").append(rootNode + index + 1);
        }
        nodes.add("{\"name\":"
                + toJsonString(name + " mesh")
                + ",\"mesh\":"
                + meshCount
                + ",\"skin\":"
                + meshCount
                + "}");
        var reach = reachAlongZ(model);
        roots.add(new Root(rootNode, name, model.root(), rootChildren.toString(), sceneName, reach[0], reach[1]));

        for (var clip : model.clips()) {
            var clipSamplers = new StringBuilder();
            var channels = new StringBuilder();
            var samplerIndex = 0;
            for (var channel : clip.channels()) {
                var input = addTimesAccessor(channel.times());
                var rotation = channel.path() == RigModel.Path.ROTATION;
                var output = addAccessor(
                        addBufferView(floatBytes(channel.values()), 0),
                        FLOAT,
                        channel.times().length,
                        rotation ? "VEC4" : "VEC3",
                        null);
                clipSamplers
                        .append(clipSamplers.isEmpty() ? "" : ",")
                        .append("{\"input\":")
                        .append(input)
                        .append(",\"output\":")
                        .append(output)
                        .append(",\"interpolation\":\"LINEAR\"}");
                channels.append(channels.isEmpty() ? "" : ",")
                        .append("{\"sampler\":")
                        .append(samplerIndex++)
                        .append(",\"target\":{\"node\":")
                        .append(rootNode + channel.joint() + 1)
                        .append(",\"path\":\"")
                        .append(rotation ? "rotation" : "translation")
                        .append("\"}}");
            }
            animations
                    .append(animations.isEmpty() ? "" : ",")
                    .append("{\"name\":")
                    .append(toJsonString(prefix + clip.name()))
                    .append(",\"channels\":[")
                    .append(channels)
                    .append("],\"samplers\":[")
                    .append(clipSamplers)
                    .append("]}");
        }

        var imageBase = imageCount;
        for (var material : model.materials()) {
            unlit |= material.unlit();
            ior |= material.ior() > 0;
            matte |= material.matte();
            if (materialCount++ > 0) {
                materials.append(',');
            }
            writeMaterial(
                    materials,
                    material,
                    addTexture(
                            offset(material.baseImage(), imageBase),
                            material.wrap(),
                            textures,
                            samplers,
                            textureIndices,
                            samplerIndices),
                    addTexture(
                            offset(material.emissiveImage(), imageBase),
                            material.wrap(),
                            textures,
                            samplers,
                            textureIndices,
                            samplerIndices),
                    addTexture(
                            offset(material.normalImage(), imageBase),
                            Wrap.REPEAT,
                            textures,
                            samplers,
                            textureIndices,
                            samplerIndices));
        }
        for (var index = 0; index < model.images().size(); index++) {
            var imageView = addBufferView(png.get(index), 0);
            images.append(images.isEmpty() ? "" : ",")
                    .append("{\"name\":")
                    .append(toJsonString(model.images().get(index).name()))
                    .append(",\"bufferView\":")
                    .append(imageView)
                    .append(",\"mimeType\":\"image/png\"}");
            imageCount++;
        }
        meshes.append(meshes.isEmpty() ? "" : ",")
                .append("{\"name\":")
                .append(toJsonString(name))
                .append(",\"primitives\":[")
                .append(primitives)
                .append("]}");
        skins.append(skins.isEmpty() ? "" : ",")
                .append("{\"name\":")
                .append(toJsonString(name))
                .append(",\"inverseBindMatrices\":")
                .append(inverseBind)
                .append(",\"skeleton\":")
                .append(rootNode + 1)
                .append(",\"joints\":[")
                .append(jointList)
                .append("]}");
        meshCount++;
    }

    void finish(Path file, String name) throws IOException {
        binary.flush();
        var placed = placeInRow();
        for (var index = 0; index < roots.size(); index++) {
            var root = roots.get(index);
            var node = new StringBuilder("{\"name\":")
                    .append(toJsonString(root.name()))
                    .append(",\"matrix\":");
            writeFloats(node, placed.get(index));
            node.append(",\"children\":[").append(root.children()).append(']');
            if (root.model() != null) {
                node.append(",\"extras\":{\"cruma_model\":")
                        .append(toJsonString(root.model()))
                        .append('}');
            }
            nodes.set(root.node(), node.append('}').toString());
        }
        var sceneNodes = new StringBuilder();
        for (var root : roots) {
            sceneNodes.append(sceneNodes.isEmpty() ? "" : ",").append(root.node());
        }

        var json = new StringBuilder(1 << 16);
        json.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Cruma NPC export\"}");
        var extensions = new ArrayList<String>();
        if (unlit) {
            extensions.add("\"KHR_materials_unlit\"");
        }
        if (ior) {
            extensions.add("\"KHR_materials_ior\"");
        }
        if (matte) {
            extensions.add("\"KHR_materials_specular\"");
        }
        if (!extensions.isEmpty()) {
            json.append(",\"extensionsUsed\":[")
                    .append(String.join(",", extensions))
                    .append(']');
        }
        json.append(",\"scene\":0,\"scenes\":[{\"name\":")
                .append(toJsonString(name))
                .append(",\"nodes\":[")
                .append(sceneNodes)
                .append("]}]");
        json.append(",\"nodes\":[").append(String.join(",", nodes)).append(']');
        json.append(",\"meshes\":[").append(meshes).append(']');
        json.append(",\"skins\":[").append(skins).append(']');
        if (!animations.isEmpty()) {
            json.append(",\"animations\":[").append(animations).append(']');
        }
        json.append(",\"materials\":[").append(materials).append(']');
        if (!textures.isEmpty()) {
            json.append(",\"textures\":[")
                    .append(textures)
                    .append("],\"samplers\":[")
                    .append(samplers)
                    .append(']');
        }
        if (!images.isEmpty()) {
            json.append(",\"images\":[").append(images).append(']');
        }
        json.append(",\"accessors\":[").append(String.join(",", accessors)).append(']');
        json.append(",\"bufferViews\":[").append(String.join(",", views)).append(']');
        json.append(",\"buffers\":[{\"byteLength\":").append(binarySize).append("}]");
        json.append(",\"extras\":{\"cruma_metres_per_unit\":")
                .append(formatNumber(metersPerUnit))
                .append("}}");

        var jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        var jsonLength = pad(jsonBytes.length);
        var total = 12 + 8 + jsonLength + 8 + binarySize;
        if (total > LARGEST_FILE) {
            throw new IOException(
                    "The scene is larger than a glTF file can hold (4 GB). Export fewer models into one scene.");
        }
        var header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC)
                .putInt(2)
                .putInt((int) total)
                .putInt((int) jsonLength)
                .putInt(JSON_CHUNK);
        var binHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        binHeader.putInt((int) binarySize).putInt(BIN_CHUNK);
        try (var out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            out.write(header.array());
            out.write(jsonBytes);
            for (var i = jsonBytes.length; i < jsonLength; i++) {
                out.write(' ');
            }
            out.write(binHeader.array());
            if (memory != null) {
                memory.writeTo(out);
            } else {
                binary.close();
                Files.copy(binaryFile, out);
            }
        }
    }

    @Override
    public void close() throws IOException {
        binary.close();
        if (binaryFile != null) {
            Files.deleteIfExists(binaryFile);
        }
    }

    private List<float[]> placeInRow() {
        var placed = new ArrayList<float[]>(roots.size());
        if (roots.size() < 2 || roots.getFirst().model() == null) {
            for (var root : roots) {
                placed.add(root.matrix());
            }
            return placed;
        }
        var gap = SCENE_GAP_METERS / ExportScale.REAL_SIZE.metersPerUnit() * metersPerUnit;
        var length = gap * (roots.size() - 1);
        for (var root : roots) {
            length += root.high() - root.low();
        }
        var edge = length / 2;
        for (var root : roots) {
            var matrix = root.matrix().clone();
            matrix[14] += edge - root.high();
            placed.add(matrix);
            edge -= root.high() - root.low() + gap;
        }
        return placed;
    }

    private int addTimesAccessor(float[] times) throws IOException {
        var key = new TimesKey(times);
        var known = timeAccessors.get(key);
        if (known != null) {
            return known;
        }
        var min = Float.MAX_VALUE;
        var max = -Float.MAX_VALUE;
        for (var time : times) {
            min = Math.min(min, time);
            max = Math.max(max, time);
        }
        var view = addBufferView(floatBytes(times), 0);
        accessors.add("{\"bufferView\":"
                + view
                + ",\"componentType\":"
                + FLOAT
                + ",\"count\":"
                + times.length
                + ",\"type\":\"SCALAR\",\"min\":["
                + formatNumber(min)
                + "],\"max\":["
                + formatNumber(max)
                + "]}");
        timeAccessors.put(key, accessors.size() - 1);
        return accessors.size() - 1;
    }

    private int addAccessor(int view, int componentType, int count, String type, float[] positions) {
        var json = new StringBuilder("{\"bufferView\":")
                .append(view)
                .append(",\"componentType\":")
                .append(componentType)
                .append(",\"count\":")
                .append(count)
                .append(",\"type\":\"")
                .append(type)
                .append('"');
        if (positions != null) {
            writeBounds(json, positions);
        }
        accessors.add(json.append('}').toString());
        return accessors.size() - 1;
    }

    private int addBufferView(byte[] bytes, int target) throws IOException {
        while (binarySize % 4 != 0) {
            binary.write(0);
            binarySize++;
        }
        var offset = binarySize;
        binary.write(bytes);
        binarySize += bytes.length;
        var json = new StringBuilder("{\"buffer\":0,\"byteOffset\":")
                .append(offset)
                .append(",\"byteLength\":")
                .append(bytes.length);
        if (target != 0) {
            json.append(",\"target\":").append(target);
        }
        views.add(json.append('}').toString());
        return views.size() - 1;
    }

    private static float[] globalOf(int index, RigModel model, float[][] global) {
        if (global[index] == null) {
            var joint = model.joints().get(index);
            var local = compose(joint.translation(), joint.rotation());
            global[index] =
                    multiply(joint.parent() < 0 ? model.root() : globalOf(joint.parent(), model, global), local);
        }
        return global[index];
    }

    private static float[] compose(float[] t, float[] q) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        return new float[] {
            1 - 2 * (y * y + z * z),
            2 * (x * y + z * w),
            2 * (x * z - y * w),
            0,
            2 * (x * y - z * w),
            1 - 2 * (x * x + z * z),
            2 * (y * z + x * w),
            0,
            2 * (x * z + y * w),
            2 * (y * z - x * w),
            1 - 2 * (x * x + y * y),
            0,
            t[0],
            t[1],
            t[2],
            1
        };
    }

    private static float[] multiply(float[] a, float[] b) {
        var result = new float[16];
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                var sum = 0f;
                for (var k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[column * 4 + k];
                }
                result[column * 4 + row] = sum;
            }
        }
        return result;
    }

    private static int offset(int image, int base) {
        return image < 0 ? image : base + image;
    }

    private static byte[] floatBytes(float[] values) {
        var buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putFloat(Float.isFinite(value) ? value : 0f);
        }
        return buffer.array();
    }

    private static byte[] shortBytes(int[] values) {
        var buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    private static byte[] intBytes(int[] values) {
        var buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putInt(value);
        }
        return buffer.array();
    }
}

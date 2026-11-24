package com.rost.cruma.core.export;

import com.rost.cruma.core.export.LocationModel.AlphaMode;
import com.rost.cruma.core.export.LocationModel.Lamp;
import com.rost.cruma.core.export.LocationModel.Material;
import com.rost.cruma.core.export.LocationModel.MeshLight;
import com.rost.cruma.core.export.LocationModel.Node;
import com.rost.cruma.core.export.LocationModel.Primitive;
import com.rost.cruma.core.export.LocationModel.Projector;
import com.rost.cruma.core.export.LocationModel.Sound;
import com.rost.cruma.core.export.LocationModel.TerrainLayer;
import com.rost.cruma.core.export.LocationModel.Wrap;
import com.rost.cruma.core.world.NamedMapStart;
import java.io.BufferedOutputStream;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class GlbWriter {
    static final int MAGIC = 0x46546C67;
    static final int JSON_CHUNK = 0x4E4F534A;
    static final int BIN_CHUNK = 0x004E4942;
    static final int FLOAT = 5126;
    static final int UNSIGNED_BYTE = 5121;
    static final int UNSIGNED_SHORT = 5123;
    static final int UNSIGNED_INT = 5125;
    static final int ARRAY_BUFFER = 34962;
    static final int ELEMENT_ARRAY_BUFFER = 34963;
    private static final int LINEAR = 9729;
    private static final int LINEAR_MIPMAP_LINEAR = 9987;
    private static final int REPEAT = 10497;
    private static final int CLAMP_TO_EDGE = 33071;

    private GlbWriter() {}

    private record Block(
            @Nullable Primitive primitive,
            int part,
            @Nullable Path image,
            long length,
            float @Nullable [] floats,
            byte @Nullable [] bytes) {
        Block(@Nullable Primitive primitive, int part, @Nullable Path image, long length) {
            this(primitive, part, image, length, null, null);
        }

        Block(@Nullable Primitive primitive, int part, @Nullable Path image, long length, float @Nullable [] floats) {
            this(primitive, part, image, length, floats, null);
        }
    }

    static void write(LocationModel model, List<Path> png, Path file, BooleanSupplier cancelled) throws IOException {
        write(model, png, file, cancelled, null);
    }

    static void write(
            LocationModel model,
            List<Path> png,
            Path file,
            BooleanSupplier cancelled,
            @Nullable LocationEnvironment environment)
            throws IOException {
        write(model, png, file, cancelled, environment, null);
    }

    static void write(
            LocationModel model,
            List<Path> png,
            Path file,
            BooleanSupplier cancelled,
            @Nullable LocationEnvironment environment,
            @Nullable NamedMapStart view)
            throws IOException {
        var json = new StringBuilder(1 << 20);
        var blocks = new ArrayList<Block>();
        var views = new StringBuilder();
        var accessors = new StringBuilder();
        var offset = 0L;
        var viewCount = 0;
        var accessorCount = 0;
        var meshesJson = new StringBuilder();
        for (var mesh = 0; mesh < model.meshes().size(); mesh++) {
            var source = model.meshes().get(mesh);
            if (mesh > 0) {
                meshesJson.append(',');
            }
            meshesJson.append("{\"name\":").append(toJsonString(source.name())).append(",\"primitives\":[");
            for (var index = 0; index < source.primitives().size(); index++) {
                var primitive = source.primitives().get(index);
                if (index > 0) {
                    meshesJson.append(',');
                }
                var shortIndices = primitive.vertexCount() <= 0xFFFF;
                var tileUvs = primitive.tileUvs();
                var light = primitive.light();
                var facing = primitive.facing();
                var sizes = new long[] {
                    primitive.positions().length * 4L,
                    primitive.normals().length * 4L,
                    primitive.uvs().length * 4L,
                    primitive.indices().length * (shortIndices ? 2L : 4L),
                    tileUvs == null ? 0 : tileUvs.length * 4L,
                    light == null ? 0 : light.length * 4L,
                    facing == null ? 0 : facing.colors().length * 4L,
                    facing == null ? 0 : facing.centers().length * 4L,
                    facing == null ? 0 : facing.shapes().length * 4L,
                    facing == null ? 0 : facing.axes().length * 4L
                };
                var first = accessorCount;
                var accessorOfPart = new int[10];
                for (var part = 0; part < 10; part++) {
                    if (part == 4 && tileUvs == null || part == 5 && light == null || part > 5 && facing == null) {
                        continue;
                    }
                    accessorOfPart[part] = accessorCount;
                    if (viewCount > 0) {
                        views.append(',');
                    }
                    views.append("{\"buffer\":0,\"byteOffset\":")
                            .append(offset)
                            .append(",\"byteLength\":")
                            .append(sizes[part])
                            .append(",\"target\":")
                            .append(part == 3 ? ELEMENT_ARRAY_BUFFER : ARRAY_BUFFER)
                            .append('}');
                    if (accessorCount > 0) {
                        accessors.append(',');
                    }
                    accessors.append("{\"bufferView\":").append(viewCount).append(",\"componentType\":");
                    switch (part) {
                        case 0 -> {
                            accessors
                                    .append(FLOAT)
                                    .append(",\"count\":")
                                    .append(primitive.vertexCount())
                                    .append(",\"type\":\"VEC3\"");
                            writeBounds(accessors, primitive.positions());
                        }
                        case 1 ->
                            accessors
                                    .append(FLOAT)
                                    .append(",\"count\":")
                                    .append(primitive.vertexCount())
                                    .append(",\"type\":\"VEC3\"");
                        case 2, 4 ->
                            accessors
                                    .append(FLOAT)
                                    .append(",\"count\":")
                                    .append(primitive.vertexCount())
                                    .append(",\"type\":\"VEC2\"");
                        case 5, 6 ->
                            accessors
                                    .append(FLOAT)
                                    .append(",\"count\":")
                                    .append(primitive.vertexCount())
                                    .append(",\"type\":\"VEC4\"");
                        case 7, 8, 9 ->
                            accessors
                                    .append(FLOAT)
                                    .append(",\"count\":")
                                    .append(primitive.vertexCount())
                                    .append(",\"type\":\"VEC3\"");
                        default ->
                            accessors
                                    .append(shortIndices ? UNSIGNED_SHORT : UNSIGNED_INT)
                                    .append(",\"count\":")
                                    .append(primitive.indices().length)
                                    .append(",\"type\":\"SCALAR\"");
                    }
                    accessors.append('}');
                    blocks.add(new Block(primitive, part, null, 0));
                    offset += pad(sizes[part]);
                    viewCount++;
                    accessorCount++;
                }
                meshesJson
                        .append("{\"attributes\":{\"POSITION\":")
                        .append(first)
                        .append(",\"NORMAL\":")
                        .append(first + 1)
                        .append(",\"TEXCOORD_0\":")
                        .append(first + 2)
                        .append(tileUvs == null ? "" : ",\"TEXCOORD_1\":" + accessorOfPart[4])
                        .append(light == null ? "" : ",\"_CLIENT_LIGHT\":" + accessorOfPart[5])
                        .append(
                                facing == null
                                        ? ""
                                        : ",\"COLOR_0\":"
                                                + accessorOfPart[6]
                                                + ",\"_FX_CENTER\":"
                                                + accessorOfPart[7]
                                                + ",\"_FX_SHAPE\":"
                                                + accessorOfPart[8]
                                                + ",\"_FX_AXIS\":"
                                                + accessorOfPart[9])
                        .append("},\"indices\":")
                        .append(first + 3)
                        .append(",\"material\":")
                        .append(primitive.material())
                        .append(",\"mode\":4}");
            }
            meshesJson.append("]}");
        }
        var imagesJson = new StringBuilder();
        for (var image = 0; image < model.images().size(); image++) {
            var imageFile = png.get(image);
            var length = Files.size(imageFile);
            if (viewCount > 0) {
                views.append(',');
            }
            views.append("{\"buffer\":0,\"byteOffset\":")
                    .append(offset)
                    .append(",\"byteLength\":")
                    .append(length)
                    .append('}');
            if (image > 0) {
                imagesJson.append(',');
            }
            imagesJson
                    .append("{\"name\":")
                    .append(toJsonString(model.images().get(image).name()))
                    .append(",\"bufferView\":")
                    .append(viewCount)
                    .append(",\"mimeType\":\"image/png\"}");
            blocks.add(new Block(null, -1, imageFile, length));
            offset += pad(length);
            viewCount++;
        }

        var textures = new StringBuilder();
        var samplers = new StringBuilder();
        var textureIndices = new HashMap<List<Object>, Integer>();
        var samplerIndices = new HashMap<Wrap, Integer>();
        var materialsJson = new StringBuilder();
        var unlit = false;
        var ior = false;
        var matte = false;
        for (var index = 0; index < model.materials().size(); index++) {
            var material = model.materials().get(index);
            if (index > 0) {
                materialsJson.append(',');
            }
            unlit |= material.unlit();
            ior |= material.ior() > 0;
            matte |= material.matte();
            writeMaterial(
                    materialsJson,
                    material,
                    addTexture(
                            material.baseImage(), material.wrap(), textures, samplers, textureIndices, samplerIndices),
                    addTexture(
                            material.emissiveImage(),
                            material.wrap(),
                            textures,
                            samplers,
                            textureIndices,
                            samplerIndices),
                    addTexture(
                            material.normalImage(), Wrap.REPEAT, textures, samplers, textureIndices, samplerIndices));
        }

        var instancing = false;
        var instanceAccessors = new IdentityHashMap<Node, int[]>();
        for (var tile : model.tiles()) {
            for (var group : tile.groups()) {
                for (var node : group.nodes()) {
                    var instances = node.instances();
                    if (instances == null) {
                        continue;
                    }
                    instancing = true;
                    var parts = instances.light() == null
                            ? new float[][] {instances.translations(), instances.rotations(), instances.scales()}
                            : new float[][] {
                                instances.translations(), instances.rotations(), instances.scales(), instances.light()
                            };
                    var first = accessorCount;
                    for (var part = 0; part < parts.length; part++) {
                        var length = parts[part].length * 4L;
                        views.append(viewCount > 0 ? "," : "")
                                .append("{\"buffer\":0,\"byteOffset\":")
                                .append(offset)
                                .append(",\"byteLength\":")
                                .append(length)
                                .append('}');
                        accessors
                                .append(accessorCount > 0 ? "," : "")
                                .append("{\"bufferView\":")
                                .append(viewCount)
                                .append(",\"componentType\":")
                                .append(FLOAT)
                                .append(",\"count\":")
                                .append(instances.count())
                                .append(",\"type\":\"")
                                .append(part == 1 || part == 3 ? "VEC4" : "VEC3")
                                .append("\"}");
                        blocks.add(new Block(null, -1, null, length, parts[part]));
                        offset += pad(length);
                        viewCount++;
                        accessorCount++;
                    }
                    instanceAccessors.put(
                            node,
                            parts.length == 4
                                    ? new int[] {first, first + 1, first + 2, first + 3}
                                    : new int[] {first, first + 1, first + 2});
                }
            }
        }
        var lightAccessors = new IdentityHashMap<Node, Integer>();
        for (var tile : model.tiles()) {
            for (var group : tile.groups()) {
                for (var node : group.nodes()) {
                    var light = node.light();
                    if (light == null) {
                        continue;
                    }
                    views.append(viewCount > 0 ? "," : "")
                            .append("{\"buffer\":0,\"byteOffset\":")
                            .append(offset)
                            .append(",\"byteLength\":")
                            .append(light.vertices().length)
                            .append('}');
                    accessors
                            .append(accessorCount > 0 ? "," : "")
                            .append("{\"bufferView\":")
                            .append(viewCount)
                            .append(",\"componentType\":")
                            .append(UNSIGNED_BYTE)
                            .append(",\"normalized\":true,\"count\":")
                            .append(light.vertices().length / 4)
                            .append(",\"type\":\"VEC4\"}");
                    blocks.add(new Block(null, -1, null, light.vertices().length, null, light.vertices()));
                    offset += pad(light.vertices().length);
                    lightAccessors.put(node, accessorCount);
                    viewCount++;
                    accessorCount++;
                }
            }
        }
        if (offset > 0xFFFF_FFF0L - (1 << 24)) {
            throw new IOException("the tile is too large for one glTF file");
        }

        var meshNodes = new ArrayList<String>();
        var groupNodes = new ArrayList<String>();
        var tileNodes = new ArrayList<String>();
        var lampNodes = new ArrayList<String>();
        var lampLights = new ArrayList<String>();
        var meshNodeCount = 0;
        var groupCount = 0;
        for (var tile : model.tiles()) {
            groupCount += tile.groups().size();
            for (var group : tile.groups()) {
                meshNodeCount += group.nodes().size();
            }
        }
        var lampBase = meshNodeCount + groupCount + model.tiles().size() + (environment != null ? 1 : 0);
        var lightBase = environment != null ? 1 : 0;
        var roots = new StringBuilder();
        for (var tile : model.tiles()) {
            var groupChildren = new StringBuilder();
            if (!tile.lamps().isEmpty()) {
                var suffix = model.tiles().size() > 1 ? " " + tile.name() : "";
                var groupIndex = lampBase + lampNodes.size();
                groupChildren.append(groupIndex);
                var children = new StringBuilder();
                var lampNodesOfTile = new ArrayList<String>();
                for (var lamp : tile.lamps()) {
                    children.append(children.isEmpty() ? "" : ",").append(groupIndex + 1 + lampNodesOfTile.size());
                    lampNodesOfTile.add(lampNode(lamp, lamp.name() + suffix, lightBase + lampLights.size()));
                    lampLights.add(lampLight(lamp, lamp.name() + suffix));
                }
                lampNodes.add("{\"name\":" + toJsonString(LocationModelBuilder.LAMPS + suffix) + ",\"children\":["
                        + children + "]}");
                lampNodes.addAll(lampNodesOfTile);
            }
            if (!tile.sounds().isEmpty()) {
                var suffix = model.tiles().size() > 1 ? " " + tile.name() : "";
                var groupIndex = lampBase + lampNodes.size();
                groupChildren.append(groupChildren.isEmpty() ? "" : ",").append(groupIndex);
                var children = new StringBuilder();
                for (var index = 0; index < tile.sounds().size(); index++) {
                    children.append(index == 0 ? "" : ",").append(groupIndex + 1 + index);
                }
                lampNodes.add("{\"name\":" + toJsonString(LocationModelBuilder.SOUNDS + suffix) + ",\"children\":["
                        + children + "]}");
                for (var index = 0; index < tile.sounds().size(); index++) {
                    lampNodes.add(soundNode(tile.sounds().get(index), "Sound " + (index + 1) + suffix));
                }
            }
            for (var group : tile.groups()) {
                var children = new StringBuilder();
                for (var node : group.nodes()) {
                    var text = new StringBuilder("{\"name\":")
                            .append(toJsonString(node.name()))
                            .append(",\"mesh\":")
                            .append(node.mesh());
                    if (node.matrix() != null) {
                        text.append(",\"matrix\":");
                        writeFloats(text, node.matrix());
                    }
                    var instanced = instanceAccessors.get(node);
                    if (instanced != null) {
                        text.append(",\"extensions\":{\"EXT_mesh_gpu_instancing\":{\"attributes\":{\"TRANSLATION\":")
                                .append(instanced[0])
                                .append(",\"ROTATION\":")
                                .append(instanced[1])
                                .append(",\"SCALE\":")
                                .append(instanced[2])
                                .append(instanced.length > 3 ? ",\"_CLIENT_LIGHT\":" + instanced[3] : "")
                                .append("}}}");
                    }
                    var lightAccessor = lightAccessors.get(node);
                    var effect = model.meshes().get(node.mesh()).primitives().stream()
                            .anyMatch(primitive -> primitive.facing() != null);
                    if (!node.extras().isEmpty()
                            || instanced != null
                            || lightAccessor != null
                            || node.projectors() != null
                            || effect) {
                        text.append(",\"extras\":{");
                        var first = true;
                        for (var entry : new TreeMap<>(node.extras()).entrySet()) {
                            text.append(first ? "" : ",")
                                    .append(toJsonString(entry.getKey()))
                                    .append(':')
                                    .append(toJsonString(entry.getValue()));
                            first = false;
                        }
                        if (instanced != null) {
                            text.append(first ? "" : ",")
                                    .append("\"cruma_instances\":")
                                    .append(meshNodes.size());
                            first = false;
                        }
                        if (effect) {
                            text.append(first ? "" : ",")
                                    .append("\"cruma_effect\":")
                                    .append(meshNodes.size());
                            first = false;
                        }
                        if (lightAccessor != null) {
                            text.append(first ? "" : ",").append("\"cruma_mesh_light\":");
                            writeMeshLight(text, node.light(), lightAccessor);
                            first = false;
                        }
                        if (node.projectors() != null) {
                            text.append(first ? "" : ",").append("\"cruma_projected_by\":[");
                            for (var index = 0; index < node.projectors().length; index++) {
                                text.append(index == 0 ? "" : ",").append(node.projectors()[index]);
                            }
                            text.append(']');
                        }
                        text.append('}');
                    }
                    children.append(children.isEmpty() ? "" : ",").append(meshNodes.size());
                    meshNodes.add(text.append('}').toString());
                }
                groupChildren.append(groupChildren.isEmpty() ? "" : ",").append(meshNodeCount + groupNodes.size());
                var groupName = model.tiles().size() > 1 ? group.name() + " " + tile.name() : group.name();
                groupNodes.add("{\"name\":" + toJsonString(groupName) + ",\"children\":[" + children + "]}");
            }
            roots.append(roots.isEmpty() ? "" : ",").append(meshNodeCount + groupCount + tileNodes.size());
            tileNodes.add("{\"name\":" + toJsonString(tile.name()) + ",\"children\":[" + groupChildren + "]}");
        }
        var nodes = new StringBuilder();
        for (var list : List.of(meshNodes, groupNodes, tileNodes)) {
            for (var node : list) {
                nodes.append(nodes.isEmpty() ? "" : ",").append(node);
            }
        }
        if (environment != null) {
            roots.append(roots.isEmpty() ? "" : ",").append(meshNodes.size() + groupNodes.size() + tileNodes.size());
            nodes.append(nodes.isEmpty() ? "" : ",").append(sunNode(environment));
        }
        for (var node : lampNodes) {
            nodes.append(nodes.isEmpty() ? "" : ",").append(node);
        }
        var lights = new ArrayList<String>();
        if (environment != null) {
            lights.add(sunLight(environment));
        }
        lights.addAll(lampLights);

        json.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Cruma location export\"}");
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
        if (!lights.isEmpty()) {
            extensions.add("\"KHR_lights_punctual\"");
        }
        if (instancing) {
            extensions.add("\"EXT_mesh_gpu_instancing\"");
        }
        if (!extensions.isEmpty()) {
            json.append(",\"extensionsUsed\":[")
                    .append(String.join(",", extensions))
                    .append(']');
        }
        if (!lights.isEmpty()) {
            json.append(",\"extensions\":{\"KHR_lights_punctual\":{\"lights\":[")
                    .append(String.join(",", lights))
                    .append("]}}");
        }
        json.append(",\"scene\":0,\"scenes\":[{\"name\":")
                .append(toJsonString(model.name()))
                .append(",\"nodes\":[")
                .append(roots)
                .append("]}]");
        json.append(",\"nodes\":[").append(nodes).append(']');
        json.append(",\"meshes\":[").append(meshesJson).append(']');
        json.append(",\"materials\":[").append(materialsJson).append(']');
        if (!textures.isEmpty()) {
            json.append(",\"textures\":[").append(textures).append(']');
            json.append(",\"samplers\":[").append(samplers).append(']');
        }
        if (!imagesJson.isEmpty()) {
            json.append(",\"images\":[").append(imagesJson).append(']');
        }
        json.append(",\"accessors\":[").append(accessors).append(']');
        json.append(",\"bufferViews\":[").append(views).append(']');
        json.append(",\"buffers\":[{\"byteLength\":").append(offset).append("}]");
        json.append(",\"extras\":{\"cruma_metres_per_unit\":")
                .append(formatNumber(model.metersPerUnit()))
                .append(",\"cruma_origin\":");
        writeFloats(json, model.origin());
        if (environment != null) {
            json.append(",\"cruma_environment\":");
            writeEnvironment(json, environment, model.metersPerUnit());
        }
        if (view != null) {
            json.append(",\"cruma_view\":");
            writeView(json, view, model);
        }
        var first = true;
        for (var tile : model.tiles()) {
            for (var projector : tile.projectors()) {
                json.append(first ? ",\"cruma_projectors\":[" : ",");
                writeProjector(json, projector, tile.name());
                first = false;
            }
        }
        if (!first) {
            json.append(']');
        }
        json.append("}}");

        var jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        var jsonLength = pad(jsonBytes.length);
        var total = 12 + 8 + jsonLength + 8 + offset;
        try (var out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)) {
            var header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC)
                    .putInt(2)
                    .putInt((int) total)
                    .putInt((int) jsonLength)
                    .putInt(JSON_CHUNK);
            out.write(header.array());
            out.write(jsonBytes);
            for (var i = jsonBytes.length; i < jsonLength; i++) {
                out.write(' ');
            }
            var binHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            binHeader.putInt((int) offset).putInt(BIN_CHUNK);
            out.write(binHeader.array());
            var buffer = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
            for (var block : blocks) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("export cancelled");
                }
                long written;
                if (block.bytes() != null) {
                    out.write(block.bytes());
                    written = block.bytes().length;
                } else if (block.floats() != null) {
                    written = floatsOut(out, buffer, block.floats());
                } else if (block.primitive() == null) {
                    written = Files.copy(block.image(), out);
                } else {
                    written = switch (block.part()) {
                        case 0 -> floatsOut(out, buffer, block.primitive().positions());
                        case 1 -> floatsOut(out, buffer, block.primitive().normals());
                        case 2 -> floatsOut(out, buffer, block.primitive().uvs());
                        case 4 -> floatsOut(out, buffer, block.primitive().tileUvs());
                        case 5 -> floatsOut(out, buffer, block.primitive().light());
                        case 6 ->
                            floatsOut(out, buffer, block.primitive().facing().colors());
                        case 7 ->
                            floatsOut(out, buffer, block.primitive().facing().centers());
                        case 8 ->
                            floatsOut(out, buffer, block.primitive().facing().shapes());
                        case 9 ->
                            floatsOut(out, buffer, block.primitive().facing().axes());
                        default ->
                            indicesOut(
                                    out,
                                    buffer,
                                    block.primitive().indices(),
                                    block.primitive().vertexCount() <= 0xFFFF);
                    };
                }
                for (var i = written; i < pad(written); i++) {
                    out.write(0);
                }
            }
        }
    }

    static int addTexture(
            int image,
            Wrap wrap,
            StringBuilder textures,
            StringBuilder samplers,
            Map<List<Object>, Integer> textureIndices,
            Map<Wrap, Integer> samplerIndices) {
        if (image < 0) {
            return -1;
        }
        var sampler = samplerIndices.get(wrap);
        if (sampler == null) {
            sampler = samplerIndices.size();
            samplerIndices.put(wrap, sampler);
            if (!samplers.isEmpty()) {
                samplers.append(',');
            }
            samplers.append("{\"magFilter\":")
                    .append(LINEAR)
                    .append(",\"minFilter\":")
                    .append(LINEAR_MIPMAP_LINEAR)
                    .append(",\"wrapS\":")
                    .append(wrap.clampU() ? CLAMP_TO_EDGE : REPEAT)
                    .append(",\"wrapT\":")
                    .append(wrap.clampV() ? CLAMP_TO_EDGE : REPEAT)
                    .append('}');
        }
        var key = List.<Object>of(image, sampler);
        var known = textureIndices.get(key);
        if (known != null) {
            return known;
        }
        var index = textureIndices.size();
        textureIndices.put(key, index);
        if (!textures.isEmpty()) {
            textures.append(',');
        }
        textures.append("{\"sampler\":")
                .append(sampler)
                .append(",\"source\":")
                .append(image)
                .append('}');
        return index;
    }

    static float sunStrength(LocationEnvironment environment) {
        var light = environment.sunLinear();
        return (float) (Math.PI * Math.max(light[0], Math.max(light[1], light[2])));
    }

    static float[] sunColor(LocationEnvironment environment) {
        var light = environment.sunLinear();
        var brightest = Math.max(light[0], Math.max(light[1], light[2]));
        if (brightest <= 0) {
            return new float[] {1, 1, 1};
        }
        return new float[] {light[0] / brightest, light[1] / brightest, light[2] / brightest};
    }

    static String lampLight(Lamp lamp, String name) {
        var linear = new float[] {
            LocationEnvironment.toLinear(lamp.color()[0]),
            LocationEnvironment.toLinear(lamp.color()[1]),
            LocationEnvironment.toLinear(lamp.color()[2])
        };
        var brightest = Math.max(linear[0], Math.max(linear[1], linear[2]));
        var half = lamp.radius() / 2;
        var json = new StringBuilder("{\"name\":")
                .append(toJsonString(name))
                .append(",\"type\":")
                .append(lamp.direction() == null ? "\"point\"" : "\"spot\"")
                .append(",\"color\":");
        writeFloats(json, new float[] {linear[0] / brightest, linear[1] / brightest, linear[2] / brightest});
        json.append(",\"intensity\":")
                .append(formatNumber((float) (683 * Math.PI * brightest * half * half)))
                .append(",\"range\":")
                .append(formatNumber(lamp.radius()));
        if (lamp.direction() != null) {
            json.append(",\"spot\":{\"innerConeAngle\":0,\"outerConeAngle\":")
                    .append(formatNumber((float) Math.acos(lamp.cone())))
                    .append('}');
        }
        return json.append('}').toString();
    }

    static String lampNode(Lamp lamp, String name, int light) {
        var json = new StringBuilder("{\"name\":").append(toJsonString(name)).append(",\"translation\":");
        writeFloats(json, lamp.position());
        if (lamp.direction() != null) {
            var direction = lamp.direction();
            json.append(",\"rotation\":");
            writeFloats(json, turnMinusZ(direction[0], direction[1], direction[2]));
        }
        json.append(",\"extensions\":{\"KHR_lights_punctual\":{\"light\":")
                .append(light)
                .append("}},\"extras\":{\"cruma_lamp\":{\"radius\":")
                .append(formatNumber(lamp.radius()))
                .append(",\"color\":");
        writeFloats(json, lamp.color());
        json.append(",\"effect\":").append(lamp.effect()).append(",\"type\":").append(lamp.type());
        if (lamp.direction() != null) {
            json.append(",\"cone\":").append(formatNumber(lamp.cone()));
        }
        return json.append("}}}").toString();
    }

    static String soundNode(Sound sound, String name) {
        var json = new StringBuilder("{\"name\":").append(toJsonString(name)).append(",\"translation\":");
        writeFloats(json, sound.position());
        json.append(",\"extras\":{\"cruma_sound\":");
        LocationJson.writeSound(json, sound);
        return json.append("}}").toString();
    }

    static float[] turnMinusZ(double x, double y, double z) {
        var length = Math.sqrt(x * x + y * y + z * z);
        x /= length;
        y /= length;
        z /= length;
        var ax = y;
        var ay = -x;
        var w = 1 - z;
        if (w < 1e-6) {
            return new float[] {1, 0, 0, 0};
        }
        var norm = Math.sqrt(ax * ax + ay * ay + w * w);
        return new float[] {(float) (ax / norm), (float) (ay / norm), 0f, (float) (w / norm)};
    }

    static void writeView(StringBuilder json, NamedMapStart view, LocationModel model) {
        var origin = model.origin();
        var meters = model.metersPerUnit();
        var yaw = Math.toRadians(view.yawDegrees());
        var pitch = Math.toRadians(view.pitchDegrees());
        json.append("{\"position\":");
        writeFloats(
                json,
                new float[] {(view.x() - origin[0]) * meters, view.z() * meters, (view.y() - origin[1]) * meters});
        json.append(",\"direction\":");
        writeFloats(json, new float[] {
            (float) (Math.cos(pitch) * Math.cos(yaw)),
            (float) Math.sin(pitch),
            (float) (Math.cos(pitch) * Math.sin(yaw))
        });
        json.append('}');
    }

    static void writeMeshLight(StringBuilder json, MeshLight light, int accessor) {
        json.append("{\"vertices\":")
                .append(accessor)
                .append(",\"ambient\":")
                .append(light.ambient())
                .append(",\"actor\":");
        writeFloats(json, new float[] {
            (light.actorRgb() & 255) / 255f,
            (light.actorRgb() >>> 8 & 255) / 255f,
            (light.actorRgb() >>> 16 & 255) / 255f
        });
        json.append(",\"intensity\":")
                .append(formatNumber(light.intensity()))
                .append(",\"sun\":")
                .append(light.sun());
        if (light.hardwareSun() != null) {
            json.append(",\"hardware_sun\":");
            writeFloats(json, light.hardwareSun());
        }
        if (light.beastCells() != null) {
            var cells = light.beastCells();
            json.append(",\"beast\":{\"atlas\":")
                    .append(toJsonString(LocationModelBuilder.LIGHTMAP_FILE))
                    .append(",\"sun\":");
            writeFloats(json, Arrays.copyOfRange(cells, 0, 4));
            json.append(",\"static\":");
            writeFloats(json, Arrays.copyOfRange(cells, 4, 8));
            json.append('}');
        }
        json.append('}');
    }

    static void writeProjector(StringBuilder json, Projector projector, String tile) {
        json.append("{\"name\":")
                .append(toJsonString(projector.name()))
                .append(",\"tile\":")
                .append(toJsonString(tile))
                .append(",\"texture\":")
                .append(toJsonString(projector.texture()))
                .append(",\"blend\":")
                .append(projector.blend())
                .append(",\"location\":");
        writeFloats(json, projector.location());
        json.append(",\"direction\":");
        writeFloats(json, projector.direction());
        json.append(",\"left\":");
        writeFloats(json, projector.left());
        json.append(",\"up\":");
        writeFloats(json, projector.up());
        json.append(",\"u_size\":")
                .append(formatNumber(projector.uSize()))
                .append(",\"v_size\":")
                .append(formatNumber(projector.vSize()))
                .append(",\"fov\":")
                .append(projector.fov())
                .append(",\"max_trace\":")
                .append(formatNumber(projector.maxTrace()))
                .append(",\"terrain\":")
                .append(projector.terrain())
                .append(",\"level\":")
                .append(projector.level())
                .append(",\"meshes\":")
                .append(projector.meshes())
                .append(",\"backfaces\":")
                .append(projector.backfaces())
                .append('}');
    }

    static void writeMaterial(StringBuilder json, Material material, int base, int emissive, int normal) {
        json.append("{\"name\":")
                .append(toJsonString(material.name()))
                .append(",\"pbrMetallicRoughness\":{\"baseColorFactor\":");
        writeFloats(json, material.baseColor());
        if (base >= 0) {
            json.append(",\"baseColorTexture\":{\"index\":")
                    .append(base)
                    .append(material.baseTexCoord() == 0 ? "" : ",\"texCoord\":" + material.baseTexCoord())
                    .append('}');
        }
        json.append(",\"metallicFactor\":0,\"roughnessFactor\":")
                .append(formatNumber(material.roughness()))
                .append('}');
        if (emissive >= 0) {
            json.append(",\"emissiveTexture\":{\"index\":").append(emissive).append('}');
        }
        if (emissive >= 0 || material.emissive()[0] > 0 || material.emissive()[1] > 0 || material.emissive()[2] > 0) {
            json.append(",\"emissiveFactor\":");
            writeFloats(json, material.emissive());
        }
        if (material.alphaMode() != AlphaMode.OPAQUE) {
            json.append(",\"alphaMode\":\"").append(material.alphaMode().name()).append('"');
        }
        if (material.alphaMode() == AlphaMode.MASK) {
            json.append(",\"alphaCutoff\":").append(formatNumber(material.alphaCutoff()));
        }
        if (material.doubleSided()) {
            json.append(",\"doubleSided\":true");
        }
        if (normal >= 0) {
            json.append(",\"normalTexture\":{\"index\":").append(normal).append('}');
        }
        if (material.unlit() || material.ior() > 0 || material.matte()) {
            var extensions = new ArrayList<String>();
            if (material.unlit()) {
                extensions.add("\"KHR_materials_unlit\":{}");
            }
            if (material.ior() > 0) {
                extensions.add("\"KHR_materials_ior\":{\"ior\":" + formatNumber(material.ior()) + '}');
            }
            if (material.matte()) {
                extensions.add("\"KHR_materials_specular\":{\"specularFactor\":0}");
            }
            json.append(",\"extensions\":{")
                    .append(String.join(",", extensions))
                    .append('}');
        }
        if (!material.extras().isEmpty() || !material.terrainLayers().isEmpty()) {
            json.append(",\"extras\":{");
            var first = true;
            for (var entry : material.extras().entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append(toJsonString(entry.getKey())).append(':').append(toJsonString(entry.getValue()));
            }
            if (!material.terrainLayers().isEmpty()) {
                json.append(first ? "" : ",").append("\"cruma_terrain_layers\":");
                writeTerrainLayers(json, material.terrainLayers());
            }
            json.append('}');
        }
        json.append('}');
    }

    static void writeTerrainLayers(StringBuilder json, List<TerrainLayer> layers) {
        json.append('[');
        for (var index = 0; index < layers.size(); index++) {
            var layer = layers.get(index);
            json.append(index > 0 ? "," : "")
                    .append("{\"layer\":")
                    .append(layer.index())
                    .append(",\"name\":")
                    .append(toJsonString(layer.name()))
                    .append(",\"texture\":")
                    .append(toJsonString(layer.texture()));
            if (!layer.alphaMap().isEmpty()) {
                json.append(",\"alpha_map\":")
                        .append(toJsonString(layer.alphaMap()))
                        .append(",\"alpha_texcoord\":1");
            }
            json.append(",\"diffuse_alpha\":").append(layer.diffuseAlpha()).append(",\"u\":");
            writeFloats(json, layer.u());
            json.append(",\"v\":");
            writeFloats(json, layer.v());
            json.append(",\"repeat_metres\":");
            writeFloats(json, new float[] {repeatLength(layer.u()), repeatLength(layer.v())});
            json.append(",\"rotation_degrees\":")
                    .append(formatNumber((float) Math.toDegrees(Math.atan2(-layer.u()[2], layer.u()[0]))))
                    .append(",\"offset\":");
            writeFloats(json, new float[] {layer.u()[3], layer.v()[3]});
            json.append('}');
        }
        json.append(']');
    }

    static void writeBounds(StringBuilder json, float[] positions) {
        var min = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        var max = new float[] {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var i = 0; i < positions.length; i++) {
            min[i % 3] = Math.min(min[i % 3], positions[i]);
            max[i % 3] = Math.max(max[i % 3], positions[i]);
        }
        json.append(",\"min\":");
        writeFloats(json, min);
        json.append(",\"max\":");
        writeFloats(json, max);
    }

    static long pad(long length) {
        return (length + 3) & ~3L;
    }

    static void writeFloats(StringBuilder json, float[] values) {
        json.append('[');
        for (var i = 0; i < values.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(formatNumber(values[i]));
        }
        json.append(']');
    }

    static String formatNumber(float value) {
        if (!Float.isFinite(value)) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e7f) {
            return Integer.toString((int) value);
        }
        return Float.toString(value);
    }

    static String toJsonString(String value) {
        var result = new StringBuilder(value.length() + 2).append('"');
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static String sunLight(LocationEnvironment environment) {
        var json = new StringBuilder("{\"name\":\"Sun\",\"type\":\"directional\",\"color\":");
        writeFloats(json, sunColor(environment));
        json.append(",\"intensity\":")
                .append(formatNumber(sunStrength(environment) * 683f))
                .append('}');
        return json.toString();
    }

    private static String sunNode(LocationEnvironment environment) {
        var toSun = environment.toSun();
        var rotation = turnMinusZ(-toSun.getX(), -toSun.getZ(), -toSun.getY());
        var json = new StringBuilder("{\"name\":\"Sun\",\"rotation\":");
        writeFloats(json, rotation);
        json.append(",\"extensions\":{\"KHR_lights_punctual\":{\"light\":0}}}");
        return json.toString();
    }

    private static void writeEnvironment(StringBuilder json, LocationEnvironment environment, float metersPerUnit) {
        json.append("{\"hour\":").append(environment.hour());
        if (environment.skyZone() != null) {
            json.append(",\"sky_zone\":\"")
                    .append(environment.skyZone())
                    .append("\",\"sky_zone_hour\":")
                    .append(environment.lightHour());
        }
        json.append(",\"ambient\":");
        writeFloats(json, environment.ambientLinear());
        json.append(",\"sun_strength\":")
                .append(formatNumber(sunStrength(environment)))
                .append(",\"sun_color\":");
        writeFloats(json, sunColor(environment));
        json.append(",\"mesh_ambient\":");
        writeFloats(json, environment.meshAmbientLinear());
        var meshSun = environment.meshSunLinear();
        var brightest = Math.max(meshSun[0], Math.max(meshSun[1], meshSun[2]));
        json.append(",\"mesh_sun_strength\":")
                .append(formatNumber((float) (Math.PI * brightest)))
                .append(",\"mesh_sun_color\":");
        writeFloats(
                json,
                brightest <= 0
                        ? new float[] {1, 1, 1}
                        : new float[] {meshSun[0] / brightest, meshSun[1] / brightest, meshSun[2] / brightest});
        if (environment.sky() != null) {
            json.append(",\"sky\":").append(toJsonString(LocationExport.SKY));
        }
        var fog = environment.fogLinear();
        if (fog != null) {
            json.append(",\"fog\":{\"color\":");
            writeFloats(json, fog);
            json.append(",\"start\":")
                    .append(formatNumber(environment.fogStart() * metersPerUnit))
                    .append(",\"end\":")
                    .append(formatNumber(environment.fogEnd() * metersPerUnit))
                    .append('}');
        }
        json.append(",\"client\":{\"ambient\":");
        writeFloats(json, new float[] {
            environment.ambient().getX(),
            environment.ambient().getY(),
            environment.ambient().getZ()
        });
        json.append(",\"sun\":");
        writeFloats(json, new float[] {
            environment.sun().getX(),
            environment.sun().getY(),
            environment.sun().getZ()
        });
        json.append(",\"mesh_ambient\":");
        writeFloats(json, new float[] {
            environment.meshAmbient().getX(),
            environment.meshAmbient().getY(),
            environment.meshAmbient().getZ()
        });
        json.append(",\"mesh_sun\":");
        writeFloats(json, new float[] {
            environment.meshSun().getX(),
            environment.meshSun().getY(),
            environment.meshSun().getZ()
        });
        json.append(",\"beast_scales\":");
        writeFloats(json, new float[] {environment.beastSun(), environment.beastStatic()});
        json.append(",\"to_sun\":");
        var toSun = environment.toSun();
        writeFloats(json, new float[] {toSun.getX(), toSun.getZ(), toSun.getY()});
        json.append("}}");
    }

    private static float repeatLength(float[] row) {
        var length = Math.sqrt(row[0] * (double) row[0] + row[1] * (double) row[1] + row[2] * (double) row[2]);
        return length == 0 ? 0 : (float) (1 / length);
    }

    private static long floatsOut(OutputStream out, ByteBuffer buffer, float[] values) throws IOException {
        buffer.clear();
        for (var value : values) {
            if (buffer.remaining() < 4) {
                out.write(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            buffer.putFloat(Float.isFinite(value) ? value : 0f);
        }
        out.write(buffer.array(), 0, buffer.position());
        return values.length * 4L;
    }

    private static long indicesOut(OutputStream out, ByteBuffer buffer, int[] values, boolean shortIndices)
            throws IOException {
        buffer.clear();
        for (var value : values) {
            if (buffer.remaining() < 4) {
                out.write(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            if (shortIndices) {
                buffer.putShort((short) value);
            } else {
                buffer.putInt(value);
            }
        }
        out.write(buffer.array(), 0, buffer.position());
        return values.length * (shortIndices ? 2L : 4L);
    }
}

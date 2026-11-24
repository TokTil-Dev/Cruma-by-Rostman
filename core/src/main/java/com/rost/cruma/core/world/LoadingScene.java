package com.rost.cruma.core.world;

import com.rost.cruma.core.npc.ClientIcons;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.SkeletalPose;
import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UActor.EmitterActor;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UEmitter.UMeshEmitter;
import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.UFinalBlend;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UMeshAnimation;
import com.rost.cruma.core.unreal.UPackage;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.unreal.UTerrain.UTerrainInfoActor;
import com.rost.cruma.core.world.UnrealLoader.FoundNpcMesh;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class LoadingScene {
    public static final FigureLook VALAKAS = new FigureLook(
            "LineageMonsters2.valakas_m00",
            List.of(
                    "LineageMonstersTex2.valakas_t00",
                    "LineageMonstersTex2.valakas_t01",
                    "LineageMonstersTex2.valakas_t02"),
            "spatk05",
            14,
            50,
            3,
            8,
            true,
            1.25f,
            512);

    private static final int WHOLE_SEQUENCE = -1;

    public static final FigureLook ANTHARAS = new FigureLook(
            "LineageMonsters.antaras_m00",
            List.of("LineageMonstersTex.antaras_t00", "LineageMonstersTex.antaras_t01"),
            "Wait",
            0,
            WHOLE_SEQUENCE,
            5,
            6,
            false,
            2.2f,
            512);

    public static final FigureLook ORFEN = new FigureLook(
            "LineageMonsters.orfen_m00",
            List.of("LineageMonstersTex.orfen_t00"),
            "Walk",
            0,
            WHOLE_SEQUENCE,
            2,
            15,
            false,
            2.2f,
            256);

    public static final FigureLook QUEEN_ANT = new FigureLook(
            "LineageMonsters.queen_ant_m00",
            List.of("LineageMonstersTex.queen_ant_t00", "LineageMonstersTex.queen_ant_t01"),
            "Walk",
            0,
            WHOLE_SEQUENCE,
            2,
            15,
            false,
            1.3f,
            256);

    public static final FigureLook BAIUM = new FigureLook(
            "LineageMonsters.baium_m00",
            List.of("LineageMonstersTex.baium_t00", "LineageMonstersTex.baium_t01"),
            "Wait",
            0,
            WHOLE_SEQUENCE,
            5,
            6,
            false,
            1.15f,
            256);

    public static final FigureLook ZAKEN =
            new FigureLook("LineageMonsters.zaken_m00", List.of(), "Wait", 0, WHOLE_SEQUENCE, 5, 6, false, 1.3f, 256);

    private static final String CRUMA_TILE = "20_21", TOI_TILE = "23_18";
    private static final float CRUMA_X = 17728, CRUMA_Y = 114176, CRUMA_RADIUS = 8000, CRUMA_FLOOR = -3900;
    private static final float CRUMA_BSP_RADIUS = 1500, CRUMA_BSP_FLOOR = -3640;
    private static final float TOI_X = 114688, TOI_Y = 15968, TOI_RADIUS = 5100, TOI_FLOOR = -5100;
    private static final String CRUMA_MESH = "Curuma_Tower_Base",
            ROCK_MESH = "Curuma_tower_Deco04",
            GLOW_MESH = "curuma_tower_effect.*";
    private static final String UNDERGROUND_MESH = "oren_.*", BSP = "bsp";
    private static final String SMALL_TOWER_MESH = "g_04";
    private static final float GROUND_REACH = 13000, GROUND_TEXTURE_SIZE = 1024;
    private static final int GROUND_EVERY = 2;
    private static final float TURNED = 45;
    private static final Icon PLAIN = new Icon(1, 1, new byte[] {(byte) 150, (byte) 148, (byte) 144, (byte) 255});
    private static final int VERTEX_FLOATS = 8;
    private static final int ATLAS_CELLS = 2;
    private static final int[][] GROUND_CELL = {{0, 0, 1, 0, 1, 1}, {0, 0, 1, 1, 0, 1}};
    private static final float ATLAS_INSET = .002f;

    private final UnrealLoader loader;
    private final Map<UTexture, Icon> icons = new HashMap<>();
    private final Map<Icon, LoadedIcon> loadedIcons = new HashMap<>();

    public LoadingScene(UnrealLoader loader) {
        this.loader = loader;
    }

    public record Picture(int width, int height, byte[] rgba) {}

    public enum Group {
        TOWER,
        ROCKS_LEFT,
        ROCKS_RIGHT,
        GLOW,
        TOI,
        GROUND
    }

    public record Batch(Group group, float[] vertices, Picture texture) {}

    public record Landmarks(List<Batch> batches, float halfWidth, float @Nullable [] smallTower) {}

    public record FigureLook(
            String mesh,
            List<String> textures,
            String sequence,
            int first,
            int last,
            int step,
            float keysPerSecond,
            boolean bounce,
            float gain,
            int cell) {}

    public record Figure(
            float[][] keys,
            float keysPerSecond,
            boolean bounce,
            int[] wedgePoints,
            float[] uvs,
            int[] triangles,
            Picture atlas,
            float lowest) {
        static void pose(float[][] keys, float keysPerSecond, boolean bounce, float seconds, float[] out) {
            var count = keys.length;
            if (count == 1) {
                System.arraycopy(keys[0], 0, out, 0, out.length);
                return;
            }
            var position = seconds * keysPerSecond;
            int from, to;
            float between;
            if (bounce) {
                var period = 2 * (count - 1);
                var place = position % period;
                place = place > count - 1 ? period - place : place;
                from = Math.min((int) place, count - 2);
                to = from + 1;
                between = place - from;
            } else {
                var place = position % count;
                from = Math.min((int) place, count - 1);
                to = (from + 1) % count;
                between = place - from;
            }
            var first = keys[from];
            var second = keys[to];
            for (var i = 0; i < out.length; i++) {
                out[i] = first[i] + (second[i] - first[i]) * between;
            }
        }

        public void pose(float seconds, float[] out) {
            pose(keys, keysPerSecond, bounce, seconds, out);
        }
    }

    record Part(
            String mesh, float[] xyz, float[] uv, @Nullable Icon texture) {}

    record View(float x, float y, float radius, float floor, float turned, float lowest, float highest) {}

    record Field(int columns, int rows, float x, float y, float step, float[] heights) {
        float height(int column, int row) {
            return heights[row * columns + column];
        }

        float heightOr(int column, int row, float other) {
            if (column < 0 || row < 0 || column >= columns || row >= rows) {
                return other;
            }
            var height = height(column, row);
            return Float.isNaN(height) ? other : height;
        }
    }

    static Icon reduced(Icon icon, int largest) {
        var result = icon;
        while (result.width() > largest && result.width() > 1 && result.height() > 1) {
            int width = result.width() / 2, height = result.height() / 2;
            var pixels = new byte[width * height * 4];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    for (var channel = 0; channel < 4; channel++) {
                        var sum = 0;
                        for (var sample = 0; sample < 4; sample++) {
                            var from = ((y * 2 + sample / 2) * result.width() + x * 2 + sample % 2) * 4;
                            sum += result.rgba()[from + channel] & 255;
                        }
                        pixels[(y * width + x) * 4 + channel] = (byte) (sum / 4);
                    }
                }
            }
            result = new Icon(width, height, pixels);
        }
        return result;
    }

    static float[] groundVertices(Field field, View view, float reach) {
        var axes = Axes.of(view);
        var out = new float[(field.columns() - 1) * (field.rows() - 1) * 6 * VERTEX_FLOATS];
        var at = 0;
        var triangle = new float[9];
        var scene = new float[9];
        for (var row = 0; row + 1 < field.rows(); row++) {
            for (var column = 0; column + 1 < field.columns(); column++) {
                if (!isGroundCellKept(field, column, row, view, reach)) {
                    continue;
                }
                for (var corners : GROUND_CELL) {
                    for (var corner = 0; corner < 3; corner++) {
                        int c = column + corners[corner * 2], r = row + corners[corner * 2 + 1];
                        triangle[corner * 3] = field.x() + c * field.step();
                        triangle[corner * 3 + 1] = field.y() + r * field.step();
                        triangle[corner * 3 + 2] = field.height(c, r);
                    }
                    axes.place(triangle, 0, view, scene);
                    for (var corner = 0; corner < 3; corner++) {
                        int c = column + corners[corner * 2], r = row + corners[corner * 2 + 1];
                        var height = field.height(c, r);
                        var alongX = (field.heightOr(c + 1, r, height) - field.heightOr(c - 1, r, height))
                                / (2 * field.step());
                        var alongY = (field.heightOr(c, r + 1, height) - field.heightOr(c, r - 1, height))
                                / (2 * field.step());
                        var length = (float) Math.sqrt(alongX * alongX + alongY * alongY + 1);
                        out[at++] = scene[corner * 3];
                        out[at++] = scene[corner * 3 + 1];
                        out[at++] = scene[corner * 3 + 2];
                        out[at++] = (-alongX * axes.cos() + alongY * axes.sin()) / length;
                        out[at++] = 1 / length;
                        out[at++] = (alongX * axes.sin() + alongY * axes.cos()) / length;
                        out[at++] = triangle[corner * 3] / GROUND_TEXTURE_SIZE;
                        out[at++] = triangle[corner * 3 + 1] / GROUND_TEXTURE_SIZE;
                    }
                }
            }
        }
        return Arrays.copyOf(out, at);
    }

    static List<Batch> batches(List<Part> parts, View view, Grouping grouping) {
        var axes = Axes.of(view);
        var scene = new float[9];
        var groups = new HashMap<Part, Group>();
        var counts = new LinkedHashMap<BatchKey, Integer>();
        for (var part : parts) {
            var t = part.xyz();
            var across = 0f;
            var kept = 0;
            for (var i = 0; i + 8 < t.length; i += 9) {
                if (isInside(t, i, view.x(), view.y(), view.radius(), view.floor())) {
                    axes.place(t, i, view, scene);
                    across += scene[0] + scene[3] + scene[6];
                    kept++;
                }
            }
            if (kept == 0) {
                continue;
            }
            var group = grouping.of(part, across / (kept * 3));
            groups.put(part, group);
            counts.merge(new BatchKey(group, part.texture() == null ? PLAIN : part.texture()), kept, Integer::sum);
        }
        var vertices = new LinkedHashMap<BatchKey, float[]>();
        counts.forEach((key, count) -> vertices.put(key, new float[count * 3 * VERTEX_FLOATS]));
        var written = new HashMap<BatchKey, Integer>();
        for (var part : parts) {
            var group = groups.get(part);
            if (group == null) {
                continue;
            }
            var key = new BatchKey(group, part.texture() == null ? PLAIN : part.texture());
            var out = vertices.get(key);
            var at = written.getOrDefault(key, 0);
            var t = part.xyz();
            var uv = part.uv();
            for (int i = 0, ui = 0; i + 8 < t.length; i += 9, ui += 6) {
                if (!isInside(t, i, view.x(), view.y(), view.radius(), view.floor())) {
                    continue;
                }
                axes.place(t, i, view, scene);
                float ux = scene[3] - scene[0], uy = scene[4] - scene[1], uz = scene[5] - scene[2];
                float vx = scene[6] - scene[0], vy = scene[7] - scene[1], vz = scene[8] - scene[2];
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                var length = Math.max(1e-12f, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
                for (var corner = 0; corner < 3; corner++) {
                    out[at++] = scene[corner * 3];
                    out[at++] = scene[corner * 3 + 1];
                    out[at++] = scene[corner * 3 + 2];
                    out[at++] = nx / length;
                    out[at++] = ny / length;
                    out[at++] = nz / length;
                    out[at++] = uv[ui + corner * 2];
                    out[at++] = uv[ui + corner * 2 + 1];
                }
            }
            written.put(key, at);
        }
        var result = new ArrayList<Batch>(vertices.size());
        vertices.forEach((key, data) -> result.add(new Batch(
                key.group(),
                data,
                new Picture(
                        key.texture().width(),
                        key.texture().height(),
                        key.texture().rgba()))));
        return result;
    }

    public @Nullable Landmarks getLandmarks() {
        var cruma =
                mapParts(CRUMA_TILE, CRUMA_X, CRUMA_Y, CRUMA_RADIUS, CRUMA_FLOOR, CRUMA_BSP_RADIUS, CRUMA_BSP_FLOOR);
        if (cruma.stream().noneMatch(part -> part.mesh().equals(CRUMA_MESH))) {
            return null;
        }
        var toi = mapParts(TOI_TILE, TOI_X, TOI_Y, TOI_RADIUS, TOI_FLOOR, TOI_RADIUS, TOI_FLOOR);
        if (toi.isEmpty()) {
            return null;
        }
        var view = frame(cruma, CRUMA_X, CRUMA_Y, CRUMA_RADIUS, CRUMA_FLOOR, TURNED);
        var batches = new ArrayList<>(batches(cruma, view, LoadingScene::groupOfCruma));
        batches.addAll(
                batches(toi, frame(toi, TOI_X, TOI_Y, TOI_RADIUS, TOI_FLOOR, TURNED), (part, across) -> Group.TOI));
        var ground = ground(CRUMA_TILE, view);
        if (ground != null) {
            batches.add(ground);
        }
        return new Landmarks(batches, halfWidth(batches), smallTower(cruma, view));
    }

    public @Nullable Figure loadFigure(FigureLook look) {
        var model = loader.loadNpcModel(look.mesh(), look.textures());
        var found = model == null ? null : loader.findNpcMesh(look.mesh());
        if (found == null) {
            return null;
        }
        var mesh = found.mesh();
        var wedges = mesh.getWedges();
        var entity = model.entityMesh();
        if (entity.vertices().size() != wedges.length) {
            return null;
        }
        var keys = keys(look, found);
        var wedgePoints = new int[wedges.length];
        for (var i = 0; i < wedges.length; i++) {
            wedgePoints[i] = wedges[i].vertexIndex();
        }
        var atlasSize = ATLAS_CELLS * look.cell();
        var atlas = new byte[atlasSize * atlasSize * 4];
        var surfaces = entity.surfaces();
        var uvs = new float[wedges.length * 2];
        var triangles = new int[entity.indices().length];
        var written = 0;
        for (var s = 0; s < surfaces.size() && s < ATLAS_CELLS * ATLAS_CELLS; s++) {
            var surface = surfaces.get(s);
            var texture = surface.material().getTexture();
            var column = s % ATLAS_CELLS;
            var row = s / ATLAS_CELLS;
            if (texture != null) {
                var name = texture.format().name();
                var data = texture.data().get(0);
                var pixels = name.contains("DXT")
                        ? DxtMipCodec.decodeToRgba(texture.format(), texture.width(), texture.height(), data)
                        : data;
                paint(
                        atlas,
                        atlasSize,
                        look.cell(),
                        column,
                        row,
                        new Icon(texture.width(), texture.height(), pixels),
                        "OPAQUE".equals(surface.blendMode().name()),
                        look.gain());
            } else {
                paint(
                        atlas,
                        atlasSize,
                        look.cell(),
                        column,
                        row,
                        new Icon(1, 1, new byte[] {(byte) 150, (byte) 148, (byte) 144, (byte) 255}),
                        true,
                        1);
            }
            for (var i = 0; i < surface.indexCount(); i++) {
                var wedge = entity.indices()[surface.indexOffset() + i];
                var uv = entity.vertices().get(wedge).uv();
                uvs[wedge * 2] = atlasCoordinate(column, uv.getX());
                uvs[wedge * 2 + 1] = atlasCoordinate(row, uv.getY());
                triangles[written++] = wedge;
            }
        }
        return new Figure(
                keys,
                look.keysPerSecond(),
                look.bounce(),
                wedgePoints,
                uvs,
                Arrays.copyOf(triangles, written),
                new Picture(atlasSize, atlasSize, atlas),
                lowest(keys[0]));
    }

    private List<Part> mapParts(
            String tile, float x, float y, float radius, float floor, float bspRadius, float bspFloor) {
        var name = loader.getMapPackage(tile);
        var uPackage = loader.loadMapPackage(name);
        var parts = new ArrayList<Part>();
        if (uPackage == null) {
            return parts;
        }
        try {
            var level = UnrealLoader.loadPrimaryModel(uPackage);
            if (level != null) {
                addLevel(parts, level, x, y, bspRadius, bspFloor);
            }
            addActors(parts, uPackage, x, y, radius, floor);
            addEmitters(parts, uPackage, x, y, radius, floor);
        } finally {
            loader.releaseMapPackage(name);
        }
        return parts;
    }

    private @Nullable Batch ground(String tile, View view) {
        var name = loader.getMapPackage(tile);
        var uPackage = loader.loadMapPackage(name);
        if (uPackage == null) {
            return null;
        }
        try {
            var terrains = new ArrayList<UTerrainInfoActor>();
            uPackage.loadObjects("TerrainInfo", terrains);
            if (terrains.isEmpty() || terrains.getFirst().getTerrainMap().get() == null) {
                return null;
            }
            var terrain = terrains.getFirst();
            var vertices = groundVertices(field(terrain), view, GROUND_REACH);
            if (vertices.length == 0) {
                return null;
            }
            var texture = PLAIN;
            for (var layer : terrain.getLayers()) {
                var icon = icon(layer.getTexture().get());
                if (icon != null) {
                    texture = icon.icon();
                    break;
                }
            }
            return new Batch(
                    Group.GROUND, vertices, new Picture(texture.width(), texture.height(), opaque(texture.rgba())));
        } finally {
            loader.releaseMapPackage(name);
        }
    }

    private void addLevel(List<Part> parts, UModel model, float x, float y, float reach, float low) {
        var points = model.getPoints();
        var vectors = model.getVectors();
        var pool = model.getVertices();
        var surfaces = model.getSurfaces();
        var bySurface = new HashMap<Integer, List<float[]>>();
        for (var node : model.getNodes()) {
            int count = node.getVertexCount();
            if (count < 3
                    || count > 16
                    || !MapCollision.isBlocking(surfaces[node.getSurfaceIndex()].getPolygonFlags())) {
                continue;
            }
            var near = true;
            for (var i = 0; i < count; i++) {
                var point = points[pool[node.getVertexPoolIndex() + i].getVertexIndex()];
                near &= point.getZ() >= low && Math.hypot(point.getX() - x, point.getY() - y) <= reach;
            }
            if (!near) {
                continue;
            }
            var triangles = bySurface.computeIfAbsent(node.getSurfaceIndex(), key -> new ArrayList<>());
            var a = points[pool[node.getVertexPoolIndex()].getVertexIndex()];
            for (var i = 1; i + 1 < count; i++) {
                var b = points[pool[node.getVertexPoolIndex() + i].getVertexIndex()];
                var c = points[pool[node.getVertexPoolIndex() + i + 1].getVertexIndex()];
                triangles.add(new float[] {
                    a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ(), c.getX(), c.getY(), c.getZ()
                });
            }
        }
        for (var entry : bySurface.entrySet()) {
            var surface = surfaces[entry.getKey()];
            var texture = icon(surface.getMaterial().get());
            float usize = texture == null ? 256 : texture.original(),
                    vsize = texture == null ? 256 : texture.originalHeight();
            var base = points[surface.getBaseIndex()];
            var u = vectors[surface.getUIndex()];
            var v = vectors[surface.getVIndex()];
            var xyz = new float[entry.getValue().size() * 9];
            var uv = new float[entry.getValue().size() * 6];
            int at = 0, ut = 0;
            for (var triangle : entry.getValue()) {
                System.arraycopy(triangle, 0, xyz, at, 9);
                at += 9;
                for (var corner = 0; corner < 3; corner++) {
                    float dx = triangle[corner * 3] - base.getX(),
                            dy = triangle[corner * 3 + 1] - base.getY(),
                            dz = triangle[corner * 3 + 2] - base.getZ();
                    uv[ut++] = (dx * u.getX() + dy * u.getY() + dz * u.getZ()) / usize;
                    uv[ut++] = (dx * v.getX() + dy * v.getY() + dz * v.getZ()) / vsize;
                }
            }
            parts.add(new Part(BSP, xyz, uv, texture == null ? null : texture.icon()));
        }
    }

    private void addActors(List<Part> parts, UPackage uPackage, float x, float y, float radius, float floor) {
        var actors = new ArrayList<StaticMeshActor>();
        uPackage.loadObjects("StaticMeshActor", actors);
        uPackage.loadObjects("MovableStaticMeshActor", actors);
        uPackage.loadObjects("L2MovableStaticMeshActor", actors);
        for (var actor : actors) {
            var mesh = actor.isDeleteMe() ? null : actor.getStaticMesh().get();
            var location = actor.getLocation();
            if (mesh == null
                    || Math.hypot(location.getX() - x, location.getY() - y) > radius
                    || location.getZ() < floor - 500
                    || mesh.getName().matches(UNDERGROUND_MESH)) {
                continue;
            }
            var matrix = Entity.actorModelMatrix(
                            location, actor.getPrePivot(), actor.getRotation().vector(), actor.getScale())
                    .to(new float[16]);
            addMesh(parts, mesh, matrix);
        }
    }

    private void addEmitters(List<Part> parts, UPackage uPackage, float x, float y, float radius, float floor) {
        var emitters = new ArrayList<UActor>();
        uPackage.loadEmitterActors(emitters);
        for (var actor : emitters) {
            var at = actor.getLocation();
            if (!(actor instanceof EmitterActor emitter)
                    || Math.hypot(at.getX() - x, at.getY() - y) > radius
                    || at.getZ() < floor) {
                continue;
            }
            for (var child : emitter.getChildEmitters()) {
                if (child instanceof UMeshEmitter meshEmitter
                        && meshEmitter.getStaticMesh().get() instanceof UStaticMesh mesh
                        && mesh.getName().matches(GLOW_MESH)) {
                    addMesh(parts, mesh, placed(at, meshEmitter.getStartSizeMax()));
                }
            }
        }
    }

    private void addMesh(List<Part> parts, UStaticMesh mesh, float[] matrix) {
        var vertices = mesh.getVertexStream().getVertices();
        var indices = mesh.getIndexStream().getIndices();
        var streams = mesh.getUvStream();
        if (streams.length == 0) {
            return;
        }
        var uvs = streams[0].getUvs();
        var surfaces = mesh.getSurfaces();
        var materials = mesh.getMaterials();
        for (var s = 0; s < surfaces.length; s++) {
            var surface = surfaces[s];
            var texture = s < materials.length ? icon(materials[s].getMaterial().get()) : null;
            var xyz = new float[surface.getTriangleMax() * 9];
            var uv = new float[surface.getTriangleMax() * 6];
            int at = 0, ut = 0;
            for (var t = 0; t < surface.getTriangleMax(); t++) {
                for (var corner = 0; corner < 3; corner++) {
                    var index = indices[surface.getFirstIndex() + t * 3 + corner];
                    var point = vertices[index].getLocation();
                    for (var k = 0; k < 3; k++) {
                        xyz[at++] = matrix[k] * point.getX()
                                + matrix[4 + k] * point.getY()
                                + matrix[8 + k] * point.getZ()
                                + matrix[12 + k];
                    }
                    uv[ut++] = uvs[index].getX();
                    uv[ut++] = uvs[index].getY();
                }
            }
            parts.add(new Part(mesh.getName(), xyz, uv, texture == null ? null : texture.icon()));
        }
    }

    private record LoadedIcon(Icon icon, int original, int originalHeight) {}

    private @Nullable LoadedIcon icon(@Nullable UMaterial material) {
        var texture = UnrealLoader.firstTextureOf(material);
        if (texture == null) {
            return null;
        }
        var icon = icons.computeIfAbsent(texture, key -> decode(key, material));
        return icon == null ? null : loadedIcons.get(icon);
    }

    private @Nullable Icon decode(UTexture texture, @Nullable UMaterial material) {
        var decoded = ClientIcons.decodeIcon(texture);
        if (decoded == null) {
            return null;
        }
        var masked = texture.isMasked()
                || texture.isAlphaTexture()
                || material instanceof UFinalBlend
                || material instanceof UShader shader && shader.getOpacity().get() != null;
        var icon = reduced(masked ? decoded : new Icon(decoded.width(), decoded.height(), opaque(decoded.rgba())), 256);
        loadedIcons.put(icon, new LoadedIcon(icon, decoded.width(), decoded.height()));
        return icon;
    }

    private static Group groupOfCruma(Part part, float across) {
        if (part.mesh().matches(GLOW_MESH)) {
            return Group.GLOW;
        }
        if (part.mesh().equals(ROCK_MESH)) {
            return across < 0 ? Group.ROCKS_LEFT : Group.ROCKS_RIGHT;
        }
        return Group.TOWER;
    }

    private static float halfWidth(List<Batch> batches) {
        var half = 0f;
        for (var batch : batches) {
            if (batch.group() == Group.TOWER) {
                for (var i = 0; i < batch.vertices().length; i += VERTEX_FLOATS) {
                    half = Math.max(half, Math.abs(batch.vertices()[i]));
                }
            }
        }
        return half;
    }

    private static float @Nullable [] smallTower(List<Part> parts, View view) {
        float[] found = null;
        var axes = Axes.of(view);
        var scene = new float[9];
        for (var part : parts) {
            if (!part.mesh().equals(SMALL_TOWER_MESH)) {
                continue;
            }
            float left = Float.MAX_VALUE,
                    right = -Float.MAX_VALUE,
                    far = Float.MAX_VALUE,
                    near = -Float.MAX_VALUE,
                    top = -Float.MAX_VALUE;
            var t = part.xyz();
            for (var i = 0; i + 8 < t.length; i += 9) {
                if (!isInside(t, i, view.x(), view.y(), view.radius(), view.floor())) {
                    continue;
                }
                axes.place(t, i, view, scene);
                for (var corner = 0; corner < 3; corner++) {
                    left = Math.min(left, scene[corner * 3]);
                    right = Math.max(right, scene[corner * 3]);
                    top = Math.max(top, scene[corner * 3 + 1]);
                    far = Math.min(far, scene[corner * 3 + 2]);
                    near = Math.max(near, scene[corner * 3 + 2]);
                }
            }
            if (right > left && right < 0 && (found == null || top > found[1])) {
                found = new float[] {(left + right) * .5f, top, (far + near) * .5f};
            }
        }
        return found;
    }

    private static float lowest(float[] pose) {
        var lowest = Float.MAX_VALUE;
        for (var i = 2; i < pose.length; i += 3) {
            lowest = Math.min(lowest, pose[i]);
        }
        return lowest;
    }

    private static float atlasCoordinate(int cell, float coordinate) {
        var inside = Math.clamp(coordinate, 0f, 1f);
        return (cell + ATLAS_INSET + inside * (1 - 2 * ATLAS_INSET)) / ATLAS_CELLS;
    }

    private static float[][] keys(FigureLook look, FoundNpcMesh found) {
        var mesh = found.mesh();
        var animation =
                found.uPackage().loadObject(mesh.getAnimationIndex()) instanceof UMeshAnimation loaded ? loaded : null;
        var sequence = animation == null ? -1 : animation.findSequenceIndex(look.sequence());
        var motion = sequence < 0 ? null : animation.decodeMotion(sequence);
        var last = look.last() != WHOLE_SEQUENCE || motion == null ? look.last() : (int) motion.trackTime() - 1;
        var count = motion == null ? 1 : (last - look.first()) / look.step() + 1;
        var keys = new float[count][];
        var middleX = 0f;
        var middleY = 0f;
        var middleZ = 0f;
        var half = 1f;
        for (var key = 0; key < count; key++) {
            var points = motion == null
                    ? mesh.getPoints()
                    : SkeletalPose.pose(mesh, motion, animation, look.first() + key * look.step(), true);
            var pose = new float[points.length * 3];
            for (var i = 0; i < points.length; i++) {
                var local = mesh.convertMeshToLocal(points[i]);
                pose[i * 3] = local.getX();
                pose[i * 3 + 1] = local.getY();
                pose[i * 3 + 2] = local.getZ();
            }
            if (key == 0) {
                var low = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
                var high = new float[] {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                for (var i = 0; i < pose.length; i++) {
                    low[i % 3] = Math.min(low[i % 3], pose[i]);
                    high[i % 3] = Math.max(high[i % 3], pose[i]);
                }
                middleX = (low[0] + high[0]) * .5f;
                middleY = (low[1] + high[1]) * .5f;
                middleZ = (low[2] + high[2]) * .5f;
                half = Math.max(1, Math.max(high[2] - low[2], Math.max(high[0] - low[0], high[1] - low[1])) * .5f);
            }
            for (var i = 0; i < points.length; i++) {
                pose[i * 3] = (pose[i * 3] - middleX) / half;
                pose[i * 3 + 1] = (pose[i * 3 + 1] - middleY) / half;
                pose[i * 3 + 2] = (pose[i * 3 + 2] - middleZ) / half;
            }
            keys[key] = pose;
        }
        return keys;
    }

    private static void paint(
            byte[] atlas, int atlasSize, int cell, int column, int row, Icon source, boolean opaque, float gain) {
        var reduced = reduced(source, cell);
        for (var y = 0; y < cell; y++) {
            for (var x = 0; x < cell; x++) {
                var from = (y * reduced.height() / cell * reduced.width() + x * reduced.width() / cell) * 4;
                var to = ((row * cell + y) * atlasSize + column * cell + x) * 4;
                for (var channel = 0; channel < 3; channel++) {
                    atlas[to + channel] =
                            (byte) Math.min(255, Math.round((reduced.rgba()[from + channel] & 255) * gain));
                }
                atlas[to + 3] = opaque ? (byte) 255 : reduced.rgba()[from + 3];
            }
        }
    }

    private static Field field(UTerrainInfoActor terrain) {
        var map = terrain.getTerrainMap().get();
        int width = map.getUSize(), height = map.getVSize();
        var data = map.getMips()[0].getData();
        var scale = terrain.getTerrainScale();
        var location = terrain.getLocation();
        var originX = -(location.getX() / scale.getX()) + (float) (width / 2);
        var originY = -(location.getY() / scale.getY()) + (float) (height / 2);
        var originZ = location.getZ() * -256f / scale.getZ() + 32767f;
        var heightScale = scale.getZ() * 0.00390625f;
        var visibility = terrain.getQuadVisibilityBitmap();
        int columns = width / GROUND_EVERY, rows = height / GROUND_EVERY;
        var heights = new float[columns * rows];
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                int x = column * GROUND_EVERY, y = row * GROUND_EVERY, at = (x + y * width) * 2;
                var raw = data[at] & 255 | (data[at + 1] & 255) << 8;
                heights[row * columns + column] =
                        visibility.get(x + y * width) ? (raw - originZ) * heightScale : Float.NaN;
            }
        }
        return new Field(
                columns, rows, -originX * scale.getX(), -originY * scale.getY(), GROUND_EVERY * scale.getX(), heights);
    }

    private static boolean isGroundCellKept(Field field, int column, int row, View view, float reach) {
        for (var r = row; r <= row + 1; r++) {
            for (var c = column; c <= column + 1; c++) {
                if (Float.isNaN(field.height(c, r))
                        || Math.hypot(field.x() + c * field.step() - view.x(), field.y() + r * field.step() - view.y())
                                > reach) {
                    return false;
                }
            }
        }
        return true;
    }

    private static float[] placed(Vec3 at, Vec3 size) {
        var matrix = new float[16];
        matrix[0] = size.getX();
        matrix[5] = size.getY();
        matrix[10] = size.getZ();
        matrix[12] = at.getX();
        matrix[13] = at.getY();
        matrix[14] = at.getZ();
        matrix[15] = 1;
        return matrix;
    }

    private static byte[] opaque(byte[] rgba) {
        var result = rgba.clone();
        for (var i = 3; i < result.length; i += 4) {
            result[i] = (byte) 255;
        }
        return result;
    }

    private static View frame(List<Part> parts, float x, float y, float radius, float floor, float turned) {
        var lowest = Float.MAX_VALUE;
        var highest = -Float.MAX_VALUE;
        for (var part : parts) {
            var t = part.xyz();
            for (var i = 0; i + 8 < t.length; i += 9) {
                if (isInside(t, i, x, y, radius, floor)) {
                    lowest = Math.min(lowest, Math.min(t[i + 2], Math.min(t[i + 5], t[i + 8])));
                    highest = Math.max(highest, Math.max(t[i + 2], Math.max(t[i + 5], t[i + 8])));
                }
            }
        }
        return new View(x, y, radius, floor, turned, lowest, highest);
    }

    private static boolean isInside(float[] t, int i, float x, float y, float radius, float floor) {
        for (var corner = 0; corner < 3; corner++) {
            if (t[i + corner * 3 + 2] < floor
                    || Math.hypot(t[i + corner * 3] - x, t[i + corner * 3 + 1] - y) > radius) {
                return false;
            }
        }
        return true;
    }

    private record Axes(float cos, float sin, float lowest, float unit) {
        static Axes of(View view) {
            var turn = Math.toRadians(view.turned());
            return new Axes(
                    (float) Math.cos(turn),
                    (float) Math.sin(turn),
                    view.lowest(),
                    1 / Math.max(1, view.highest() - view.lowest()));
        }

        void place(float[] t, int i, View view, float[] out) {
            for (var corner = 0; corner < 3; corner++) {
                float x = t[i + corner * 3] - view.x(), y = t[i + corner * 3 + 1] - view.y();
                out[corner * 3] = (x * cos - y * sin) * unit;
                out[corner * 3 + 1] = (t[i + corner * 3 + 2] - lowest) * unit;
                out[corner * 3 + 2] = -(x * sin + y * cos) * unit;
            }
        }
    }

    @FunctionalInterface
    interface Grouping {
        Group of(Part part, float across);
    }

    private record BatchKey(Group group, Icon texture) {}
}

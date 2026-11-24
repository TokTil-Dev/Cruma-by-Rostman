package com.rost.cruma.core.export;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.export.EffectStill.Cards;
import com.rost.cruma.core.export.EffectStill.Effect;
import com.rost.cruma.core.export.EffectStill.MeshCopies;
import com.rost.cruma.core.export.EffectStill.Placement;
import com.rost.cruma.core.export.LocationExport.Figure;
import com.rost.cruma.core.export.LocationModel.EffectActor;
import com.rost.cruma.core.export.LocationModel.EffectEmitter;
import com.rost.cruma.core.export.LocationModel.Facing;
import com.rost.cruma.core.export.LocationModel.Group;
import com.rost.cruma.core.export.LocationModel.Instances;
import com.rost.cruma.core.export.LocationModel.Lamp;
import com.rost.cruma.core.export.LocationModel.LayerFile;
import com.rost.cruma.core.export.LocationModel.Material;
import com.rost.cruma.core.export.LocationModel.Mesh;
import com.rost.cruma.core.export.LocationModel.MeshLight;
import com.rost.cruma.core.export.LocationModel.MusicZone;
import com.rost.cruma.core.export.LocationModel.Node;
import com.rost.cruma.core.export.LocationModel.Primitive;
import com.rost.cruma.core.export.LocationModel.Projector;
import com.rost.cruma.core.export.LocationModel.Sound;
import com.rost.cruma.core.export.LocationModel.Tile;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.AmbientSound;
import com.rost.cruma.core.world.AmbientSoundHours;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.MeshVertexBuffer;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.UvAnimation;
import com.rost.cruma.core.world.Light;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Clock;
import com.rost.cruma.core.world.ProjectorDescriptor;
import com.rost.cruma.core.world.StaticMeshAmbient.Source;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.core.world.ZoneStateItem;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

final class LocationModelBuilder {
    static final String FIGURES = "NPCs";

    static final String MAP_FIGURES = "Map figures";

    static final String EFFECTS = "Effects";

    static final String DOOR = "cruma_door";

    static final String LAMPS = "Lamps";

    static final String SOUNDS = "Sounds";

    static final String SOUND_FOLDER = "sounds";

    static final int AUDIBLE_RADII = 50;

    static final String PROJECTORS = "projectors";

    static final String LIGHTMAPS = "lightmaps";

    static final String LIGHTMAP_FILE = LIGHTMAPS + "/lightmaps.png";

    private static final int FLOOR_SIZE = 512;

    private static final int LIGHTMAP_PAD = 2;

    private static final int LE_STATIC_SPOT = 8;
    private static final int LE_SPOTLIGHT = 12;
    private static final int LE_SUNLIGHT = 19;

    private static final long NOT_DRAWN = SurfaceType.FAKE_BACKDROP.getId()
            | SurfaceType.BOUNDING_BOX.getId()
            | SurfaceType.IMPORTED_GEODATA.getId()
            | SurfaceType.GENERATED_GEODATA.getId();

    private final String name;
    private final float metersPerUnit;
    private final LocationExport.@Nullable Area crop;
    private final int terrainSize;
    private final boolean decorations;
    private final BooleanSupplier cancelled;
    private final float originX;
    private final float originY;
    private final LocationMaterials materials;
    private final TerrainLayers terrainLayers = new TerrainLayers();
    private final List<Mesh> meshes = new ArrayList<>();
    private final Map<Object, Integer> sharedMeshes = new HashMap<>();
    private final Map<Integer, Integer> flatMeshes = new HashMap<>();
    private final Map<String, Map<String, List<Node>>> tiles = new LinkedHashMap<>();
    private final Map<String, List<Lamp>> lamps = new HashMap<>();
    private final Map<String, List<Sound>> sounds = new HashMap<>();
    private final Map<String, String> soundFiles = new HashMap<>();
    private final Set<String> soundFileNames = new HashSet<>();
    private final Map<String, Map<String, EffectParts>> effectActors = new HashMap<>();
    private final Map<String, List<Projector>> projectors = new HashMap<>();
    private final List<LayerFile> projectorFiles = new ArrayList<>();
    private final Map<Object, String> projectorPaths = new IdentityHashMap<>();
    private final Map<Projector, ProjectorDescriptor> projectorSources = new IdentityHashMap<>();
    private final Set<Node> refusingProjectors = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Texture> lightmapPages = new ArrayList<>();
    private final Map<Texture, Integer> lightmapIndices = new IdentityHashMap<>();
    private @Nullable LightmapPages lightmapSource;
    private boolean secondarySource;
    private int hour = LocationEnvironment.DEFAULT_HOUR;
    private boolean clientLight = true;
    private boolean withSounds;
    private @Nullable LocationMusic music;
    private final Map<String, List<MusicZone>> musicZones = new HashMap<>();
    private ZoneStateChoice zoneState = ZoneStateChoice.EMPTY;
    private final Map<String, Integer> zoneDefaults = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private MapEventState byTime = MapEventState.ALL_SHOWN;
    private final Map<Integer, int[]> meshSources = new HashMap<>();
    private String tile = "";
    private WaterDepth.@Nullable Result waterDepth;
    private TerrainBake.@Nullable Result floor;

    private final Map<Object, float[]> vertexBounds = new IdentityHashMap<>();

    private final Map<Integer, Integer> figureMeshes = new HashMap<>();

    private final Map<String, Integer> mapFigureMeshes = new HashMap<>();

    LocationModelBuilder(
            String name,
            float[] origin,
            int terrainSize,
            boolean decorations,
            @Nullable Texture waterNormal,
            BooleanSupplier cancelled) {
        this(name, origin, null, terrainSize, decorations, waterNormal, cancelled);
    }

    LocationModelBuilder(
            String name,
            float[] origin,
            LocationExport.@Nullable Area crop,
            int terrainSize,
            boolean decorations,
            @Nullable Texture waterNormal,
            ExportScale scale,
            BooleanSupplier cancelled) {
        this.materials = new LocationMaterials(waterNormal);
        this.name = name;
        this.metersPerUnit = scale.metersPerUnit();
        this.crop = crop;
        this.terrainSize = terrainSize;
        this.decorations = decorations;
        this.cancelled = cancelled;
        this.originX = origin[0];
        this.originY = origin[1];
    }

    LocationModelBuilder(
            String name,
            float[] origin,
            LocationExport.@Nullable Area crop,
            int terrainSize,
            boolean decorations,
            @Nullable Texture waterNormal,
            BooleanSupplier cancelled) {
        this(name, origin, crop, terrainSize, decorations, waterNormal, ExportScale.REAL_SIZE, cancelled);
    }

    private record SharedKey(Object vertices, Object indices, List<Surface> surfaces) {
        @Override
        public boolean equals(Object other) {
            return other instanceof SharedKey key
                    && key.vertices == vertices
                    && key.indices == indices
                    && key.surfaces.equals(surfaces);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(vertices) * 31 + System.identityHashCode(indices);
        }
    }

    private record SoundingActor(String name, int order, Vec3 location, List<EmitterSound> sounds) {}

    private record EffectParts(Placement placement, List<EffectNode> nodes) {}

    private record EffectNode(int order, String name, EffectEmitter emitter) {}

    private record TriangleKey(int a0, int a1, int a2, int b0, int b1, int b2, int c0, int c1, int c2) {}

    static LocationModel build(List<UMap> maps, int terrainSize, boolean decorations, BooleanSupplier cancelled) {
        return build(maps, terrainSize, decorations, ExportScale.REAL_SIZE, cancelled);
    }

    static LocationModel build(
            List<UMap> maps, int terrainSize, boolean decorations, ExportScale scale, BooleanSupplier cancelled) {
        return build(maps, terrainSize, decorations, scale, LocationEnvironment.DEFAULT_HOUR, cancelled);
    }

    static LocationModel build(
            List<UMap> maps,
            int terrainSize,
            boolean decorations,
            ExportScale scale,
            int hour,
            BooleanSupplier cancelled) {
        return build(maps, terrainSize, decorations, scale, hour, true, cancelled);
    }

    static LocationModel build(
            List<UMap> maps,
            int terrainSize,
            boolean decorations,
            ExportScale scale,
            int hour,
            boolean clientLight,
            BooleanSupplier cancelled) {
        return build(maps, terrainSize, decorations, scale, hour, clientLight, EffectStill.NONE, cancelled);
    }

    static LocationModel build(
            List<UMap> maps,
            int terrainSize,
            boolean decorations,
            ExportScale scale,
            int hour,
            boolean clientLight,
            EffectStill effects,
            BooleanSupplier cancelled) {
        return build(maps, terrainSize, decorations, scale, hour, clientLight, effects, 1, 0f, cancelled);
    }

    static LocationModel build(
            List<UMap> maps,
            int terrainSize,
            boolean decorations,
            ExportScale scale,
            int hour,
            boolean clientLight,
            EffectStill effects,
            int stills,
            float interval,
            BooleanSupplier cancelled) {
        return build(
                maps,
                terrainSize,
                decorations,
                scale,
                hour,
                clientLight,
                effects,
                stills,
                interval,
                false,
                Map.of(),
                cancelled);
    }

    static LocationModel build(
            List<UMap> maps,
            int terrainSize,
            boolean decorations,
            ExportScale scale,
            int hour,
            boolean clientLight,
            EffectStill effects,
            int stills,
            float interval,
            boolean sounds,
            Map<String, Clock> clocks,
            BooleanSupplier cancelled) {
        var names = new ArrayList<String>(maps.size());
        for (var map : maps) {
            names.add(map.name());
        }
        var builder = new LocationModelBuilder(
                        maps.getFirst().name(),
                        computeOrigin(names),
                        null,
                        terrainSize,
                        decorations,
                        null,
                        scale,
                        cancelled)
                .atHour(hour)
                .withClientLight(clientLight)
                .withSounds(sounds)
                .byTime(clocks);
        for (var map : maps) {
            builder.add(map);
            builder.addEffectLoop(
                    stills > 1 ? effects.takeLoop(map, stills, interval) : List.of(effects.take(map)), interval);
        }
        return builder.build();
    }

    static float[] computeOrigin(List<String> tileNames) {
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        for (var tileName : tileNames) {
            var coordinates = parseTile(tileName);
            if (coordinates == null) {
                throw new IllegalArgumentException("not a map tile: " + tileName);
            }
            minX = Math.min(minX, coordinates[0]);
            minY = Math.min(minY, coordinates[1]);
            maxX = Math.max(maxX, coordinates[0]);
            maxY = Math.max(maxY, coordinates[1]);
        }
        var size = (float) WorldDimensions.TILE_SIZE;
        return new float[] {
            (minX + maxX + 1 - 2 * WorldDimensions.TILE_ZERO_COORD_X) * size / 2,
            (minY + maxY + 1 - 2 * WorldDimensions.TILE_ZERO_COORD_Y) * size / 2,
            0
        };
    }

    static float[] clientLight(TerrainMesh terrain, int hour) {
        var vertices = terrain.vertexBuffer();
        var count = vertices.size();
        var result = new float[count * 4];
        var slot = Math.floorMod(hour, 24) / 3f;
        var first = (int) Math.floor(slot);
        var next = (first + 1) % 8;
        var fraction = slot - first;
        var variants = terrain.lightVariants();
        for (var vertex = 0; vertex < count; vertex++) {
            result[vertex * 4] = vertices.getSteadyLightRed(vertex) / 255f;
            result[vertex * 4 + 1] = vertices.getSteadyLightGreen(vertex) / 255f;
            result[vertex * 4 + 2] = vertices.getSteadyLightBlue(vertex) / 255f;
            float share;
            if (variants != null
                    && variants.length == 8
                    && variants[first].length == count
                    && variants[next].length == count) {
                var blended = variants[first][vertex] + (variants[next][vertex] - variants[first][vertex]) * fraction;
                share = Math.round(Math.clamp(blended, 0f, 1f) * 255f) / 255f;
            } else {
                share = vertices.getLightValue(vertex);
            }
            result[vertex * 4 + 3] = share;
        }
        return result;
    }

    static @Nullable MeshLight meshLight(Entity entity, EntityMesh mesh, int @Nullable [] sources, int hour) {
        var ambient = entity.getStaticMeshAmbient();
        if (ambient == null
                || sources == null
                || ambient.source() == Source.BEAST
                || ambient.source() == Source.BEAST_FALLBACK) {
            return null;
        }
        var count = mesh.vertices().size();
        var lighting = entity.getActorVertexLighting();
        if (lighting != null && lighting.vertexCount() != count) {
            return null;
        }
        var rgb = lighting == null ? null : lighting.rgb();
        var baked = mesh.vertexBuffer();
        var masks = lighting != null && lighting.hasVisibilityMasks()
                ? lighting.visibilityMasks()
                : entity.getEnvironmentVisibilityMasks();
        if (masks != null && masks.length != count) {
            masks = null;
        }
        var hardware = entity.getHardwareSunLight();
        var sun = masks != null ? 1 : hardware != null ? 2 : 0;
        var slot = Math.floorMod(hour, 24) / 3f;
        var first = (int) Math.floor(slot);
        var next = (first + 1) % 8;
        var fraction = slot - first;
        var bytes = new byte[sources.length * 4];
        for (var vertex = 0; vertex < sources.length; vertex++) {
            var source = sources[vertex];
            if (source < 0 || source >= count) {
                continue;
            }
            if (rgb != null) {
                bytes[vertex * 4] = rgb[source * 3];
                bytes[vertex * 4 + 1] = rgb[source * 3 + 1];
                bytes[vertex * 4 + 2] = rgb[source * 3 + 2];
            } else {
                bytes[vertex * 4] = toByte(baked.getBakedLightX(source));
                bytes[vertex * 4 + 1] = toByte(baked.getBakedLightY(source));
                bytes[vertex * 4 + 2] = toByte(baked.getBakedLightZ(source));
            }
            if (masks != null) {
                var bits = masks[source] & 255;
                var share = (bits >>> first & 1) * (1 - fraction) + (bits >>> next & 1) * fraction;
                bytes[vertex * 4 + 3] = (byte) Math.round(share * 255);
            }
        }
        return new MeshLight(
                ambient.source().getId(),
                ambient.actorRgb(),
                entity.getStaticMeshAmbientIntensity(),
                sun,
                hardware == null ? null : new float[] {hardware.getX(), hardware.getY(), hardware.getZ()},
                bytes);
    }

    static float @Nullable [] decorationLight(Entity entity, EntityMesh mesh, List<Integer> copies, int hour) {
        var shadows = mesh.instanceLighting();
        var steady = entity.getTerrainDecorationSteadyLighting();
        var count = mesh.instanceMatrices().size();
        if (shadows.size() != count || steady.size() != count) {
            return null;
        }
        var slot = Math.floorMod(hour, 24) / 3f;
        var first = (int) Math.floor(slot);
        var next = (first + 1) % 8;
        var fraction = slot - first;
        var result = new float[copies.size() * 4];
        for (var index = 0; index < copies.size(); index++) {
            var copy = copies.get(index);
            var color = steady.get(copy);
            result[index * 4] = color.getX();
            result[index * 4 + 1] = color.getY();
            result[index * 4 + 2] = color.getZ();
            var packed = shadows.get(copy);
            result[index * 4 + 3] = entity.isFullbrightDecoration()
                    ? -1f
                    : decorationShadow(packed, first)
                            + (decorationShadow(packed, next) - decorationShadow(packed, first)) * fraction;
        }
        return result;
    }

    static void decompose(float[] m, float[] rotation, float[] scale) {
        var columns = new double[3][3];
        for (var column = 0; column < 3; column++) {
            var length = Math.sqrt(m[column * 4] * (double) m[column * 4]
                    + m[column * 4 + 1] * (double) m[column * 4 + 1]
                    + m[column * 4 + 2] * (double) m[column * 4 + 2]);
            scale[column] = (float) length;
            for (var row = 0; row < 3; row++) {
                columns[column][row] = length == 0 ? (column == row ? 1 : 0) : m[column * 4 + row] / length;
            }
        }
        if (determinant(m) < 0) {
            scale[0] = -scale[0];
            for (var row = 0; row < 3; row++) {
                columns[0][row] = -columns[0][row];
            }
        }
        var xx = columns[0][0];
        var yy = columns[1][1];
        var zz = columns[2][2];
        var trace = xx + yy + zz;
        double x;
        double y;
        double z;
        double w;
        if (trace > 0) {
            var s = Math.sqrt(trace + 1) * 2;
            w = s / 4;
            x = (columns[1][2] - columns[2][1]) / s;
            y = (columns[2][0] - columns[0][2]) / s;
            z = (columns[0][1] - columns[1][0]) / s;
        } else if (xx > yy && xx > zz) {
            var s = Math.sqrt(1 + xx - yy - zz) * 2;
            w = (columns[1][2] - columns[2][1]) / s;
            x = s / 4;
            y = (columns[1][0] + columns[0][1]) / s;
            z = (columns[2][0] + columns[0][2]) / s;
        } else if (yy > zz) {
            var s = Math.sqrt(1 + yy - xx - zz) * 2;
            w = (columns[2][0] - columns[0][2]) / s;
            x = (columns[1][0] + columns[0][1]) / s;
            y = s / 4;
            z = (columns[2][1] + columns[1][2]) / s;
        } else {
            var s = Math.sqrt(1 + zz - xx - yy) * 2;
            w = (columns[0][1] - columns[1][0]) / s;
            x = (columns[2][0] + columns[0][2]) / s;
            y = (columns[2][1] + columns[1][2]) / s;
            z = s / 4;
        }
        var length = Math.sqrt(x * x + y * y + z * z + w * w);
        rotation[0] = (float) (x / length);
        rotation[1] = (float) (y / length);
        rotation[2] = (float) (z / length);
        rotation[3] = (float) (w / length);
    }

    static @Nullable List<Texture> lightmapsAt(Entity entity, int hour) {
        var variant = Math.round(Math.floorMod(hour, 24) / 3f) % 8;
        var atlases = entity.getLightmaps();
        if (atlases != null && !atlases.isEmpty()) {
            var variants = entity.getCsgLightmapVariants();
            var count = entity.getCsgLightmapVariantCount();
            if (variants != null && count >= 1 && variants.size() == atlases.size() * count) {
                var slot = Math.clamp(variant, 0, count - 1);
                return variants.subList(slot * atlases.size(), (slot + 1) * atlases.size());
            }
            return atlases;
        }
        if (entity.isBeastLightmap()) {
            return null;
        }
        var variants = entity.getLightmapVariants();
        if (variants != null && !variants.isEmpty()) {
            var slots = entity.getLightmapVariantSlots();
            var selected = Math.clamp(variant, 0, (slots.isEmpty() ? variants.size() : slots.size()) - 1);
            var index = slots.isEmpty() ? selected : slots.get(selected);
            return index < 0 || index >= variants.size() ? null : List.of(variants.get(index));
        }
        return entity.getLightmap() == null ? null : List.of(entity.getLightmap());
    }

    private record LightmapAtlas(int cell, int columns, int width, int height) {
        static LightmapAtlas of(List<Texture> pages) {
            var cell = 4;
            for (var page : pages) {
                cell = Math.max(cell, Math.max(page.width(), page.height()));
            }
            var cells = pages.size() + 1;
            var columns = (int) Math.ceil(Math.sqrt(cells));
            var rows = (cells + columns - 1) / columns;
            var step = cell + 2 * LIGHTMAP_PAD;
            return new LightmapAtlas(cell, columns, columns * step, rows * step);
        }

        int x(int cellIndex) {
            return cellIndex % columns * (cell + 2 * LIGHTMAP_PAD) + LIGHTMAP_PAD;
        }

        int y(int cellIndex) {
            return cellIndex / columns * (cell + 2 * LIGHTMAP_PAD) + LIGHTMAP_PAD;
        }
    }

    static int @Nullable [] parseTile(String name) {
        var parts = name.split("_");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException notTile) {
            return null;
        }
    }

    LocationModelBuilder atHour(int hour) {
        this.hour = hour;
        return this;
    }

    LocationModelBuilder withClientLight(boolean clientLight) {
        this.clientLight = clientLight;
        return this;
    }

    LocationModelBuilder withSounds(boolean sounds) {
        this.withSounds = sounds;
        return this;
    }

    LocationModelBuilder withMusic(@Nullable LocationMusic music) {
        this.music = music;
        return this;
    }

    LocationModelBuilder inZoneState(ZoneStateChoice zoneState) {
        this.zoneState = zoneState;
        return this;
    }

    LocationModelBuilder byTime(Map<String, Clock> clocks) {
        this.byTime = MapEventState.ALL_SHOWN.withClocks(clocks);
        return this;
    }

    void add(UMap map) {
        tile = map.name();
        var byDefault = ZoneStateItem.defaultOf(map);
        var pkg = ZoneStateItem.packageOf(map);
        if (byDefault != MapEventState.AS_THE_MAP && !zoneState.isChosen(pkg)) {
            zoneDefaults.put(pkg, byDefault);
        }
        tiles.computeIfAbsent(tile, ignored -> new LinkedHashMap<>());
        var coordinates = WorldTile.isMapName(tile) ? parseTile(tile) : null;
        var size = (float) WorldDimensions.TILE_SIZE;
        var minX = coordinates == null
                ? map.position().getX()
                : (coordinates[0] - WorldDimensions.TILE_ZERO_COORD_X) * size;
        var minY = coordinates == null
                ? map.position().getY()
                : (coordinates[1] - WorldDimensions.TILE_ZERO_COORD_Y) * size;
        floor = null;
        var water = false;
        for (var entity : map.entities()) {
            if (entity.getMesh() instanceof EntityMesh mesh) {
                for (var surface : mesh.surfaces()) {
                    water |= LocationMaterials.isWater(surface);
                }
            }
        }
        for (var entity : map.entities()) {
            if (entity.getMesh() instanceof TerrainMesh terrain) {
                addTerrain(entity, terrain, water);
            }
        }
        waterDepth = WaterDepth.bake(map, minX, minY, size, WaterDepth.RESOLUTION, LocationMaterials::isWater, floor);
        for (var entity : map.entities()) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            if (entity.getMesh() instanceof EntityMesh mesh
                    && entity.getZoneRender().shows(zoneState)) {
                addMesh(entity, mesh);
            }
        }
        addLamps(map);
        addProjectors(map);
        if (withSounds) {
            addSounds(map);
        }
        if (music != null) {
            musicZones
                    .computeIfAbsent(tile, ignored -> new ArrayList<>())
                    .addAll(music.findZones(map, crop, originX, originY, metersPerUnit));
        }
    }

    @Nullable
    Lamp createLamp(Light light, int number) {
        var color = light.color();
        var spot = light.lightEffect() == LE_SPOTLIGHT || light.lightEffect() == LE_STATIC_SPOT;
        var cone = 1f - light.cone() / 256f;
        if (light.lightEffect() == LE_SUNLIGHT
                || !(light.radius() > 0)
                || !(Math.max(color.getX(), Math.max(color.getY(), color.getZ())) > 0)
                || spot && !(cone < 1)) {
            return null;
        }
        var position = light.position();
        var direction = light.direction();
        return new Lamp(
                "Lamp " + number,
                new float[] {
                    (position.getX() - originX) * metersPerUnit,
                    position.getZ() * metersPerUnit,
                    (position.getY() - originY) * metersPerUnit
                },
                new float[] {color.getX(), color.getY(), color.getZ()},
                light.radius() * metersPerUnit,
                spot ? new float[] {direction.getX(), direction.getZ(), direction.getY()} : null,
                spot ? cone : 0f,
                light.lightEffect(),
                light.lightType());
    }

    void addFigures(List<Figure> figures) {
        for (var figure : figures) {
            var npc = figure.npc();
            var index = figureMeshes.get(npc.id());
            if (index == null) {
                index = figureMesh(figure);
                figureMeshes.put(npc.id(), index);
            }
            if (index < 0) {
                continue;
            }
            var cos = (float) Math.cos(figure.yaw());
            var sin = (float) Math.sin(figure.yaw());
            var placement = new float[] {
                cos,
                sin,
                0,
                0,
                -sin,
                cos,
                0,
                0,
                0,
                0,
                1,
                0,
                (float) figure.x(),
                (float) figure.y(),
                (float) figure.z(),
                1
            };
            var extras = new LinkedHashMap<String, String>();
            extras.put("cruma_npc_id", Integer.toString(npc.id()));
            extras.put("cruma_pose", figure.model().pose());
            extras.put("cruma_rig", LocationExport.NPCS + "/" + NpcExport.name(npc) + ".glb");
            addNode(FIGURES, new Node(npc.label() + " " + npc.id(), index, nodeMatrix(placement), Map.copyOf(extras)));
        }
    }

    void addMapFigures(List<MapFigures.Figure> figures) {
        for (var figure : figures) {
            var pawn = figure.pawn();
            var key = figure.rig() + "|" + figure.pose();
            var index = mapFigureMeshes.get(key);
            if (index == null) {
                var mesh = pawn.mesh();
                index = posedMesh(
                        mesh.substring(mesh.lastIndexOf('.') + 1),
                        figure.model().entityMesh(),
                        figure.positions(),
                        figure.normals(),
                        metersPerUnit);
                mapFigureMeshes.put(key, index);
            }
            if (index < 0) {
                continue;
            }
            var animations = pawn.animations();
            var extras = new LinkedHashMap<String, String>();
            extras.put("cruma_rig", figure.rig());
            extras.put("cruma_pose", figure.pose());
            extras.put("cruma_pawn_list", String.join(",", animations.list()));
            extras.put("cruma_pawn_interval", animations.interval());
            extras.put("cruma_pawn_first", animations.first());
            extras.put("cruma_pawn_random", Boolean.toString(animations.random()));
            extras.put("cruma_pawn_lobby", Boolean.toString(figure.lobby()));
            var seconds = new StringJoiner(",");
            figure.seconds().forEach((name, length) -> seconds.add(name + "=" + length));
            extras.put("cruma_pawn_seconds", seconds.toString());
            addNode(
                    MAP_FIGURES,
                    new Node(
                            pawn.path(),
                            index,
                            nodeMatrix(pawn.computeModelMatrix(figure.model().lowest())),
                            Map.copyOf(extras)));
        }
    }

    LocationModel build() {
        var files = new ArrayList<LayerFile>(terrainLayers.getFiles());
        files.addAll(projectorFiles);
        files.addAll(materials.getFiles());
        var pages = List.copyOf(lightmapPages);
        var atlas = LightmapAtlas.of(pages);
        var allProjectors = new ArrayList<Projector>();
        tiles.keySet().forEach(tileName -> allProjectors.addAll(projectors.getOrDefault(tileName, List.of())));
        var boxes = new HashMap<Integer, float @Nullable []>();
        var tileList = new ArrayList<Tile>();
        tiles.forEach((tileName, groups) -> {
            var groupList = new ArrayList<Group>();
            groups.forEach((groupName, nodes) -> groupList.add(new Group(
                    groupName,
                    nodes.stream()
                            .map(node -> withProjectors(node, groupName, allProjectors, boxes))
                            .map(node -> withBeastCells(node, atlas, pages))
                            .toList())));
            tileList.add(new Tile(
                    tileName,
                    List.copyOf(groupList),
                    List.copyOf(lamps.getOrDefault(tileName, List.of())),
                    List.copyOf(projectors.getOrDefault(tileName, List.of())),
                    List.copyOf(sounds.getOrDefault(tileName, List.of())),
                    effectActors(tileName),
                    List.copyOf(musicZones.getOrDefault(tileName, List.of()))));
        });
        return new LocationModel(
                name,
                new float[] {originX, originY, 0},
                metersPerUnit,
                List.copyOf(materials.getImages()),
                List.copyOf(materials.getMaterials()),
                List.copyOf(lightmapAtlas(meshes, files, atlas, pages)),
                List.copyOf(tileList),
                List.copyOf(files),
                Map.copyOf(zoneDefaults));
    }

    void addEffects(List<Effect> effects) {
        addEffectLoop(List.of(effects), 0f);
    }

    void addEffectLoop(List<List<Effect>> stills, float interval) {
        for (var still = 0; still < stills.size(); still++) {
            var loop = stills.size() < 2
                    ? Map.<String, String>of()
                    : Map.of(
                            "cruma_effect_still",
                            Integer.toString(still),
                            "cruma_effect_stills",
                            Integer.toString(stills.size()),
                            "cruma_effect_interval",
                            String.format(Locale.ROOT, "%.4f", interval));
            for (var effect : stills.get(still)) {
                switch (effect) {
                    case Cards cards -> addCards(cards, loop);
                    case MeshCopies copies -> addMeshCopies(copies, loop);
                }
            }
        }
    }

    private void addSounds(UMap map) {
        var list = sounds.computeIfAbsent(tile, ignored -> new ArrayList<>());
        for (var sound : map.ambientSounds()) {
            if (sound.soundList() != null) {
                continue;
            }
            var location = sound.position();
            if (!isHeard(location, sound.radius()) || !sound.playsInZoneState(zoneState)) {
                continue;
            }
            var when = sound.type().name().toLowerCase(Locale.ROOT);
            var hours = sound.hours();
            if (sound.type() == AmbientSound.Type.RANGE_TIME && !hours.isEmpty()) {
                var clock = byTime.clock(hours.label());
                if (clock == Clock.OFF) {
                    continue;
                }
                if (clock == Clock.ALWAYS) {
                    when = AmbientSound.Type.ALWAYS.name().toLowerCase(Locale.ROOT);
                    hours = AmbientSoundHours.NONE;
                }
            }
            var clip = sound.clip();
            list.add(new Sound(
                    clip.name(),
                    soundFile(clip),
                    false,
                    sound.actor(),
                    tile,
                    filePoint(location),
                    true,
                    sound.radius() * metersPerUnit,
                    sound.volume(),
                    sound.pitch(),
                    sound.pitch(),
                    sound.isLooping(),
                    sound.chance(),
                    sound.startTime(),
                    hours,
                    when,
                    0,
                    0,
                    0,
                    0,
                    clip.seconds(),
                    clip));
        }
        var actors = new LinkedHashMap<String, SoundingActor>();
        for (var emitter : map.spriteEmitters()) {
            actors.putIfAbsent(
                    emitter.actorName(),
                    new SoundingActor(
                            emitter.actorName(),
                            emitter.actorOrder(),
                            emitter.actorPos(),
                            emitter.visibility().sounds()));
        }
        for (var emitter : map.meshEmitters()) {
            actors.putIfAbsent(
                    emitter.actorName(),
                    new SoundingActor(
                            emitter.actorName(),
                            emitter.actorOrder(),
                            emitter.actorPos(),
                            emitter.visibility().sounds()));
        }
        for (var emitter : map.beamEmitters()) {
            actors.putIfAbsent(
                    emitter.actorName(),
                    new SoundingActor(
                            emitter.actorName(),
                            emitter.actorOrder(),
                            emitter.actorPos(),
                            emitter.visibility().sounds()));
        }
        var ordered = new ArrayList<>(actors.values());
        ordered.sort(Comparator.comparingInt(SoundingActor::order));
        for (var actor : ordered) {
            for (var sound : actor.sounds()) {
                if (!isHeard(actor.location(), sound.radius())) {
                    continue;
                }
                var clip = sound.clip();
                list.add(new Sound(
                        clip.name(),
                        soundFile(clip),
                        true,
                        actor.name(),
                        tile,
                        filePoint(actor.location()),
                        !sound.uiSound(),
                        sound.radius() * metersPerUnit,
                        sound.volume(),
                        sound.pitchMin(),
                        sound.pitchMax(),
                        sound.looping(),
                        AmbientSound.LOOPS,
                        0f,
                        AmbientSoundHours.NONE,
                        "always",
                        sound.delaySeconds(),
                        sound.fadeInSeconds(),
                        sound.fadeOutStartSeconds(),
                        sound.fadeOutSeconds(),
                        clip.seconds(),
                        clip));
            }
        }
    }

    private boolean isHeard(Vec3 location, float radius) {
        var reach = radius * AUDIBLE_RADII;
        return crop == null
                || crop.meets(
                        location.getX() - reach,
                        location.getY() - reach,
                        location.getX() + reach,
                        location.getY() + reach);
    }

    private String soundFile(SoundClip clip) {
        var known = soundFiles.get(clip.name());
        if (known != null) {
            return known;
        }
        var base = SOUND_FOLDER + "/" + LocationExport.toSafeName(clip.name());
        var path = base + ".wav";
        for (var number = 2; !soundFileNames.add(path.toLowerCase(Locale.ROOT)); number++) {
            path = base + "_" + number + ".wav";
        }
        soundFiles.put(clip.name(), path);
        return path;
    }

    private float[] filePoint(Vec3 location) {
        var result = new float[3];
        toFile(new float[] {location.getX(), location.getY(), location.getZ()}, result, 0);
        return result;
    }

    private void addProjectors(UMap map) {
        var list = projectors.computeIfAbsent(tile, ignored -> new ArrayList<>());
        if (!clientLight) {
            return;
        }
        for (var source : map.projectors()) {
            var texture = source.material().getTexture();
            if (!source.projectTerrain() && !source.projectBsp() && !source.projectStaticMesh()
                    || source.fusedBlendPair() < 0
                    || texture == null
                    || texture.data().isEmpty()
                    || texture.width() < 1
                    || texture.height() < 1) {
                continue;
            }
            var volume = source.volumeBounds();
            if (crop != null && !crop.meets(volume.minX(), volume.minY(), volume.maxX(), volume.maxY())) {
                continue;
            }
            var basis = source.basis();
            var location = source.location();
            var projector = new Projector(
                    source.name(),
                    projectorFile(source, texture),
                    source.fusedBlendPair(),
                    new float[] {
                        (location.getX() - originX) * metersPerUnit,
                        location.getZ() * metersPerUnit,
                        (location.getY() - originY) * metersPerUnit
                    },
                    axis(basis.direction()),
                    axis(basis.left()),
                    axis(basis.up()),
                    source.uSize() * metersPerUnit,
                    source.vSize() * metersPerUnit,
                    source.fovDegrees(),
                    source.maxTraceDistance() * metersPerUnit,
                    source.projectTerrain(),
                    source.projectBsp(),
                    source.projectStaticMesh(),
                    source.projectOnBackfaces());
            list.add(projector);
            projectorSources.put(projector, source);
        }
    }

    private String projectorFile(ProjectorDescriptor source, Texture texture) {
        var key = texture.data().getFirst();
        var known = projectorPaths.get(key);
        if (known != null) {
            return known;
        }
        var width = texture.width();
        var height = texture.height();
        var name = source.materialName().isBlank() ? source.name() : source.materialName();
        var base = PROJECTORS + "/" + LocationExport.toSafeName(name);
        var path = base + ".png";
        for (var number = 2; projectorPaths.containsValue(path); number++) {
            path = base + "_" + number + ".png";
        }
        projectorFiles.add(new LayerFile(
                path,
                width,
                height,
                false,
                () -> LocationMaterials.decode(texture, width, height).rgba()));
        projectorPaths.put(key, path);
        return path;
    }

    private void addLamps(UMap map) {
        var list = lamps.computeIfAbsent(tile, ignored -> new ArrayList<>());
        for (var source : List.of(map.lights(), map.animatedLights())) {
            for (var light : source) {
                var lamp = createLamp(light, list.size() + 1);
                var position = light.position();
                var radius = light.radius();
                if (lamp != null
                        && (crop == null
                                || crop.meets(
                                        position.getX() - radius,
                                        position.getY() - radius,
                                        position.getX() + radius,
                                        position.getY() + radius))) {
                    list.add(lamp);
                }
            }
        }
    }

    private int figureMesh(Figure figure) {
        var scale = (figure.npc().drawScale() > 0 ? figure.npc().drawScale() : 1f) * metersPerUnit;
        return posedMesh(figure.npc().label(), figure.model().entityMesh(), null, null, scale);
    }

    private int posedMesh(
            String label,
            EntityMesh mesh,
            float @Nullable [] posedPositions,
            float @Nullable [] posedNormals,
            float scale) {
        var vertices = mesh.vertexBuffer();
        var indices = mesh.indices();
        var posed = posedPositions != null
                && posedNormals != null
                && posedPositions.length == vertices.size() * 3
                && posedNormals.length == posedPositions.length;
        var primitives = new ArrayList<Primitive>();
        for (var surface : mesh.surfaces()) {
            if (surface.blendMode() == BlendMode.INVISIBLE || surface.indexCount() < 3) {
                continue;
            }
            var end = Math.min(indices.length, surface.indexOffset() + surface.indexCount());
            var remap = new HashMap<Integer, Integer>();
            var positions = new FloatArrayList();
            var normals = new FloatArrayList();
            var uvs = new FloatArrayList();
            var triangles = new IntArrayList();
            for (var offset = surface.indexOffset(); offset + 2 < end; offset += 3) {
                for (var source : new int[] {indices[offset], indices[offset + 2], indices[offset + 1]}) {
                    var known = remap.get(source);
                    if (known == null) {
                        known = positions.size() / 3;
                        remap.put(source, known);
                        if (posed) {
                            positions.add(posedPositions[source * 3] * scale);
                            positions.add(posedPositions[source * 3 + 2] * scale);
                            positions.add(posedPositions[source * 3 + 1] * scale);
                            normals.add(posedNormals[source * 3]);
                            normals.add(posedNormals[source * 3 + 2]);
                            normals.add(posedNormals[source * 3 + 1]);
                        } else {
                            positions.add(vertices.getPositionX(source) * scale);
                            positions.add(vertices.getPositionZ(source) * scale);
                            positions.add(vertices.getPositionY(source) * scale);
                            normals.add(vertices.getNormalX(source));
                            normals.add(vertices.getNormalZ(source));
                            normals.add(vertices.getNormalY(source));
                        }
                        uvs.add(vertices.getUvX(source));
                        uvs.add(vertices.getUvY(source));
                    }
                    triangles.add(known);
                }
            }
            if (triangles.notEmpty()) {
                primitives.add(new Primitive(
                        positions.toArray(),
                        normals.toArray(),
                        uvs.toArray(),
                        null,
                        triangles.toArray(),
                        materials.getMaterial(surface, true)));
            }
        }
        if (primitives.isEmpty()) {
            return -1;
        }
        meshes.add(new Mesh(label, List.copyOf(primitives)));
        return meshes.size() - 1;
    }

    private List<Primitive> clipToArea(List<Primitive> primitives) {
        if (crop == null) {
            return primitives;
        }
        var bounds = new float[] {
            (crop.minX() - originX) * metersPerUnit,
            (crop.minY() - originY) * metersPerUnit,
            (crop.maxX() - originX) * metersPerUnit,
            (crop.maxY() - originY) * metersPerUnit
        };
        var result = new ArrayList<Primitive>(primitives.size());
        for (var primitive : primitives) {
            var cut = PolygonClip.clip(primitive, bounds);
            if (cut != null) {
                result.add(cut);
            }
        }
        return List.copyOf(result);
    }

    private boolean meetsCrop(EntityMesh mesh, float[] m) {
        var box = vertexBounds.computeIfAbsent(mesh.vertices(), ignored -> {
            var vertices = mesh.vertexBuffer();
            var result = new float[] {
                Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
            };
            for (var vertex = 0; vertex < vertices.size(); vertex++) {
                result[0] = Math.min(result[0], vertices.getPositionX(vertex));
                result[1] = Math.min(result[1], vertices.getPositionY(vertex));
                result[2] = Math.min(result[2], vertices.getPositionZ(vertex));
                result[3] = Math.max(result[3], vertices.getPositionX(vertex));
                result[4] = Math.max(result[4], vertices.getPositionY(vertex));
                result[5] = Math.max(result[5], vertices.getPositionZ(vertex));
            }
            return result;
        });
        if (box[0] > box[3]) {
            return false;
        }
        var lowX = Float.MAX_VALUE;
        var lowY = Float.MAX_VALUE;
        var highX = -Float.MAX_VALUE;
        var highY = -Float.MAX_VALUE;
        for (var corner = 0; corner < 8; corner++) {
            var x = (corner & 1) == 0 ? box[0] : box[3];
            var y = (corner & 2) == 0 ? box[1] : box[4];
            var z = (corner & 4) == 0 ? box[2] : box[5];
            var wx = m[0] * x + m[4] * y + m[8] * z + m[12];
            var wy = m[1] * x + m[5] * y + m[9] * z + m[13];
            lowX = Math.min(lowX, wx);
            highX = Math.max(highX, wx);
            lowY = Math.min(lowY, wy);
            highY = Math.max(highY, wy);
        }
        return crop.meets(lowX, lowY, highX, highY);
    }

    private boolean meetsCrop(float[] m, MeshVertexBuffer vertices, int a, int b, int c) {
        var lowX = Double.MAX_VALUE;
        var lowY = Double.MAX_VALUE;
        var highX = -Double.MAX_VALUE;
        var highY = -Double.MAX_VALUE;
        for (var vertex : new int[] {a, b, c}) {
            var x = vertices.getPositionX(vertex);
            var y = vertices.getPositionY(vertex);
            var z = vertices.getPositionZ(vertex);
            var wx = m[0] * x + m[4] * y + m[8] * z + m[12];
            var wy = m[1] * x + m[5] * y + m[9] * z + m[13];
            lowX = Math.min(lowX, wx);
            highX = Math.max(highX, wx);
            lowY = Math.min(lowY, wy);
            highY = Math.max(highY, wy);
        }
        return crop.meets(lowX, lowY, highX, highY);
    }

    private void addTerrain(Entity entity, TerrainMesh terrain, boolean water) {
        if (!(terrain.surface().material() instanceof MultiTextureEntityMaterial multi)
                || terrain.indices().length < 3) {
            return;
        }
        var baked = TerrainBake.findBakeArea(terrain);
        var source = multi.multiTexture();
        if (water) {
            floor = TerrainBake.bake(terrain, source, FLOOR_SIZE, zoneState, cancelled);
        }
        float @Nullable [] local = null;
        if (crop != null) {
            local = localRectangle(entity.getModelMatrix().to(new float[16]), crop);
            var lowX = Math.max(baked.minX(), local[0]);
            var lowY = Math.max(baked.minY(), local[1]);
            var highX = Math.min(baked.maxX(), local[2]);
            var highY = Math.min(baked.maxY(), local[3]);
            if (lowX >= highX || lowY >= highY) {
                return;
            }
            baked = new TerrainBake.Area(lowX, lowY, highX, highY);
        }
        var bakeArea = baked;
        var vertices = terrain.vertexBuffer();
        var matrix = entity.getModelMatrix().to(new float[16]);
        var count = vertices.size();
        var positions = new float[count * 3];
        var normals = new float[count * 3];
        var uvs = new float[count * 2];
        var alphaUvs = new float[count * 2];
        var point = new float[3];
        for (var vertex = 0; vertex < count; vertex++) {
            toWorld(
                    matrix,
                    vertices.getPositionX(vertex),
                    vertices.getPositionY(vertex),
                    vertices.getPositionZ(vertex),
                    point);
            put(positions, vertex, point);
            transformNormal(
                    matrix,
                    vertices.getNormalX(vertex),
                    vertices.getNormalY(vertex),
                    vertices.getNormalZ(vertex),
                    point);
            put(normals, vertex, point);
            uvs[vertex * 2] = baked.u(vertices.getPositionX(vertex));
            uvs[vertex * 2 + 1] = baked.v(vertices.getPositionY(vertex));
            alphaUvs[vertex * 2] = vertices.getAlphaMapU(vertex);
            alphaUvs[vertex * 2 + 1] = vertices.getAlphaMapV(vertex);
        }
        var light = this.clientLight ? clientLight(terrain, hour) : null;
        var layers = terrainLayers.getLayers(tile, terrain, source, positions, zoneState);
        var material = materials.getTerrain(
                tile + " terrain",
                terrainSize,
                () -> TerrainBake.bake(terrain, source, terrainSize, bakeArea, zoneState, cancelled)
                        .image(),
                layers);
        var indices = terrain.indices();
        var flip = determinant(matrix) >= 0;
        var triangles = new int[indices.length - indices.length % 3];
        for (var offset = 0; offset < triangles.length; offset += 3) {
            triangles[offset] = indices[offset];
            triangles[offset + 1] = indices[offset + (flip ? 2 : 1)];
            triangles[offset + 2] = indices[offset + (flip ? 1 : 2)];
        }
        if (local != null) {
            var kept = cropTerrain(vertices, triangles, local, positions, normals, uvs, alphaUvs, light, material);
            var cut = kept == null ? List.<Primitive>of() : clipToArea(List.of(kept));
            if (cut.isEmpty()) {
                return;
            }
            meshes.add(new Mesh("Terrain " + tile, cut));
            addNode("Terrain", new Node("Terrain mesh " + tile, meshes.size() - 1, null));
            return;
        }
        meshes.add(new Mesh(
                "Terrain " + tile,
                List.of(new Primitive(positions, normals, uvs, alphaUvs, triangles, material, light))));
        addNode("Terrain", new Node("Terrain mesh " + tile, meshes.size() - 1, null));
    }

    private void addMesh(Entity entity, EntityMesh mesh) {
        if (entity.isWireframe()
                || entity.getStaticMeshLod() != null
                        && entity.getStaticMeshLod().level() > 0) {
            return;
        }
        var types = 0L;
        for (var surface : mesh.surfaces()) {
            types |= surface.type();
        }
        var decoration = mesh.decorationFadeMax() > 0;
        if (decoration && !decorations) {
            return;
        }
        var level = (types & SurfaceType.CSG.getId()) != 0;
        var water = false;
        for (var surface : mesh.surfaces()) {
            water |= LocationMaterials.isWater(surface);
        }
        var base = entity.getModelMatrix();
        var instances = entity.getInstanceMatrices();
        if (decoration && !level && !water) {
            addDecoration(entity, mesh, base, instances);
            return;
        }
        if (mesh.billboard() && (level || decoration || water)) {
            return;
        }
        if (level || decoration || water) {
            var builders = new LinkedHashMap<Integer, PrimitiveBuilder>();
            lightmapSource = level && clientLight ? lightmapPages(entity) : null;
            for (var instance : instances) {
                var matrix = base.times(instance).to(new float[16]);
                for (var surface : mesh.surfaces()) {
                    addSurface(mesh, surface, matrix, builders);
                }
            }
            lightmapSource = null;
            var primitives = clipToArea(finish(builders));
            if (primitives.isEmpty()) {
                return;
            }
            var group = level ? "Level geometry" : decoration ? "Grass and flowers" : "Static meshes";
            var name = level
                    ? "Level geometry " + (meshes.size() + 1)
                    : decoration ? "Grass " + displayName(entity) : nodeName(entity);
            meshes.add(new Mesh(name, primitives));
            var lightmapped = primitives.stream().anyMatch(primitive -> primitive.lightmap() != null);
            var node = new Node(
                    name, meshes.size() - 1, null, lightmapped ? Map.of("cruma_lightmap", LIGHTMAP_FILE) : Map.of());
            if (!level && !entity.isAcceptsProjectors()) {
                refusingProjectors.add(node);
            }
            addNode(group, node);
            return;
        }
        if (crop != null) {
            var reaching = new ArrayList<Mat4>();
            for (var instance : instances) {
                if (meetsCrop(mesh, base.times(instance).to(new float[16]))) {
                    reaching.add(instance);
                }
            }
            if (reaching.isEmpty()) {
                return;
            }
            instances = reaching;
        }
        var index = sharedMesh(entity, mesh);
        if (index < 0) {
            return;
        }
        if (mesh.billboard()) {
            index = flatMesh(index);
        }
        var group = entity.getMovement() != null ? "Moving objects" : "Static meshes";
        var light = clientLight ? meshLight(entity, mesh, meshSources.get(index), hour) : null;
        var beast = entity.getLightmapVariants();
        if (light != null && entity.isBeastLightmap() && beast != null && beast.size() == 2) {
            light = new MeshLight(
                    light.ambient(),
                    light.actorRgb(),
                    light.intensity(),
                    light.sun(),
                    light.hardwareSun(),
                    light.vertices(),
                    new int[] {lightmapIndex(beast.get(0)), lightmapIndex(beast.get(1))},
                    null);
        } else if (light != null && entity.isBeastLightmap()) {
            light = null;
        }
        for (var instance : instances) {
            var placed = base.times(instance).to(new float[16]);
            var node = new Node(
                    nodeName(entity),
                    index,
                    nodeMatrix(mesh.billboard() ? billboardMatrix(placed) : placed),
                    extras(entity, mesh),
                    null,
                    light);
            if (!entity.isAcceptsProjectors()) {
                refusingProjectors.add(node);
            }
            addNode(group, node);
        }
    }

    private void addCards(Cards cards, Map<String, String> loop) {
        var count = cards.vertexCount();
        if (count == 0 || cards.indices().length == 0) {
            return;
        }
        var centers = cards.centers();
        if (crop != null) {
            var inside = false;
            for (var vertex = 0; vertex < count && !inside; vertex++) {
                inside = crop.contains(centers[vertex * 3], centers[vertex * 3 + 1]);
            }
            if (!inside) {
                return;
            }
        }
        var shapes = cards.shapes();
        var axes = cards.axes();
        var positions = new float[count * 3];
        var normals = new float[count * 3];
        var fileCenters = new float[count * 3];
        var fileShapes = new float[count * 3];
        var fileAxes = new float[count * 3];
        var point = new float[3];
        for (var vertex = 0; vertex < count; vertex++) {
            var at = vertex * 3;
            var a = shapes[at];
            var b = shapes[at + 1];
            var kind = (int) shapes[at + 2];
            var ax = axes[at];
            var ay = axes[at + 1];
            var az = axes[at + 2];
            point[0] = centers[at];
            point[1] = centers[at + 1];
            point[2] = centers[at + 2];
            switch (kind) {
                case 0 -> {
                    point[0] += a;
                    point[2] += b;
                }
                case 1, 3 -> {
                    var length = (float) Math.sqrt(ax * ax + az * az);
                    var side = length < 1e-6f ? 0 : (kind == 1 ? a : -a) / length;
                    point[0] += -az * side + (kind == 1 ? ax * b : 0);
                    point[1] += kind == 1 ? ay * b : 0;
                    point[2] += ax * side + (kind == 1 ? az * b : 0);
                }
                default -> {}
            }
            toFile(point, positions, at);
            toFile(new float[] {centers[at], centers[at + 1], centers[at + 2]}, fileCenters, at);
            fileShapes[at] = a * metersPerUnit;
            fileShapes[at + 1] = b * metersPerUnit;
            fileShapes[at + 2] = kind;
            fileAxes[at] = ax;
            fileAxes[at + 1] = az;
            fileAxes[at + 2] = ay;
            normals[at + 2] = -1;
        }
        var material = materials.getEffect(cards.texture(), cards.textureName(), cards.blend());
        var beam = count > 0 && (int) shapes[2] == 3;
        addEffectPart(
                cards,
                loop,
                cards.name(),
                beam ? "beam" : "sprite",
                materialAt(material).extras().getOrDefault("cruma_fx_texture", ""),
                cards.blend());
        meshes.add(new Mesh(
                "Effect " + cards.name(),
                List.of(new Primitive(
                        positions,
                        normals,
                        cards.uvs().clone(),
                        null,
                        cards.indices().clone(),
                        material,
                        null,
                        null,
                        new Facing(cards.colors().clone(), fileCenters, fileShapes, fileAxes)))));
        addNode(EFFECTS, new Node(cards.name(), meshes.size() - 1, null, layer(cards, loop)));
    }

    private void addMeshCopies(MeshCopies copies, Map<String, String> loop) {
        var builders = new LinkedHashMap<Integer, PrimitiveBuilder>();
        var colors = new HashMap<Integer, FloatArrayList>();
        var matrices = copies.matrices();
        for (var copy = 0; copy < matrices.size(); copy++) {
            var matrix = matrices.get(copy);
            if (crop != null && !meetsCrop(copies.mesh(), matrix)) {
                continue;
            }
            var surfaces = copies.mesh().surfaces();
            var lights = copies.lights();
            for (var index = 0; index < surfaces.size(); index++) {
                var surface = lights == null ? surfaces.get(index) : LocationMaterials.drawnAsItIs(surfaces.get(index));
                addSurface(copies.mesh(), surface, matrix, builders);
                var blend = surface.blendMode();
                var red = lights == null ? 1f : LocationMaterials.bakedLight(blend, lights[index * 3]);
                var green = lights == null ? 1f : LocationMaterials.bakedLight(blend, lights[index * 3 + 1]);
                var blue = lights == null ? 1f : LocationMaterials.bakedLight(blend, lights[index * 3 + 2]);
                for (var entry : builders.entrySet()) {
                    var list = colors.computeIfAbsent(entry.getKey(), ignored -> new FloatArrayList());
                    while (list.size() / 4 < entry.getValue().positions.size() / 3) {
                        list.addAll(
                                copies.colors()[copy * 4] * red,
                                copies.colors()[copy * 4 + 1] * green,
                                copies.colors()[copy * 4 + 2] * blue,
                                copies.colors()[copy * 4 + 3]);
                    }
                }
            }
        }
        var primitives = new ArrayList<Primitive>();
        for (var entry : builders.entrySet()) {
            var builder = entry.getValue();
            if (builder.indices.size() == 0) {
                continue;
            }
            var built = builder.build();
            var count = built.vertexCount();
            var shapes = new float[count * 3];
            for (var vertex = 0; vertex < count; vertex++) {
                shapes[vertex * 3 + 2] = 2;
            }
            primitives.add(new Primitive(
                    built.positions(),
                    built.normals(),
                    built.uvs(),
                    built.tileUvs(),
                    built.indices(),
                    built.material(),
                    null,
                    null,
                    new Facing(
                            colors.get(entry.getKey()).toArray(),
                            built.positions().clone(),
                            shapes,
                            new float[count * 3])));
        }
        if (primitives.isEmpty()) {
            return;
        }
        meshes.add(new Mesh("Effect " + copies.name(), List.copyOf(primitives)));
        addEffectPart(copies, loop, copies.name(), "mesh", "", copies.blend());
        addNode(
                EFFECTS,
                new Node(copies.name(), meshes.size() - 1, null, withBlend(layer(copies, loop), copies.blend())));
    }

    private void addEffectPart(
            Effect effect, Map<String, String> loop, String node, String type, String texture, int blend) {
        if (!loop.getOrDefault("cruma_effect_still", "0").equals("0")) {
            return;
        }
        var parts = effectActors
                .computeIfAbsent(tile, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(effect.actor(), ignored -> new EffectParts(effect.placement(), new ArrayList<>()));
        parts.nodes()
                .add(new EffectNode(
                        effect.order(), node, new EffectEmitter(effect.name(), effect.order(), type, texture, blend)));
    }

    private List<EffectActor> effectActors(String tileName) {
        var actors = effectActors.getOrDefault(tileName, Map.of());
        var result = new ArrayList<EffectActor>(actors.size());
        actors.forEach((actor, parts) -> {
            var entries = new ArrayList<>(parts.nodes());
            entries.sort(Comparator.comparingInt(EffectNode::order));
            var nodes = new ArrayList<String>(entries.size());
            var emitters = new ArrayList<EffectEmitter>(entries.size());
            for (var entry : entries) {
                nodes.add(entry.name());
                if (!emitters.contains(entry.emitter())) {
                    emitters.add(entry.emitter());
                }
            }
            var matrix = nodeMatrix(parts.placement().matrix());
            var rotation = new float[4];
            var scale = new float[3];
            decompose(matrix, rotation, scale);
            result.add(new EffectActor(
                    actor,
                    tileName,
                    new float[] {matrix[12], matrix[13], matrix[14]},
                    rotation,
                    scale,
                    List.copyOf(nodes),
                    List.copyOf(emitters)));
        });
        return List.copyOf(result);
    }

    private void toFile(float[] point, float[] out, int offset) {
        out[offset] = (point[0] - originX) * metersPerUnit;
        out[offset + 1] = point[2] * metersPerUnit;
        out[offset + 2] = (point[1] - originY) * metersPerUnit;
    }

    private int flatMesh(int index) {
        var known = flatMeshes.get(index);
        if (known != null) {
            return known;
        }
        var source = meshes.get(index);
        var primitives = new ArrayList<Primitive>(source.primitives().size());
        for (var primitive : source.primitives()) {
            var positions = primitive.positions().clone();
            var normals = primitive.normals().clone();
            for (var at = 0; at + 2 < positions.length; at += 3) {
                positions[at + 2] = 0;
                normals[at] = 0;
                normals[at + 1] = 0;
                normals[at + 2] = 1;
            }
            var indices = primitive.indices().clone();
            for (var at = 0; at + 2 < indices.length; at += 3) {
                var a = indices[at] * 3;
                var b = indices[at + 1] * 3;
                var c = indices[at + 2] * 3;
                var facing = (positions[b] - positions[a]) * (positions[c + 1] - positions[a + 1])
                        - (positions[b + 1] - positions[a + 1]) * (positions[c] - positions[a]);
                if (facing < 0) {
                    var swap = indices[at + 1];
                    indices[at + 1] = indices[at + 2];
                    indices[at + 2] = swap;
                }
            }
            primitives.add(new Primitive(
                    positions,
                    normals,
                    primitive.uvs(),
                    primitive.tileUvs(),
                    indices,
                    primitive.material(),
                    primitive.light(),
                    primitive.lightmap()));
        }
        meshes.add(new Mesh(source.name() + " card", List.copyOf(primitives)));
        var flat = meshes.size() - 1;
        meshSources.put(flat, meshSources.get(index));
        flatMeshes.put(index, flat);
        return flat;
    }

    private void addDecoration(Entity entity, EntityMesh mesh, Mat4 base, List<Mat4> instances) {
        var placements = new ArrayList<float[]>(instances.size());
        var lit = new ArrayList<Integer>(instances.size());
        for (var copy = 0; copy < instances.size(); copy++) {
            var matrix = base.times(instances.get(copy)).to(new float[16]);
            if (crop == null || crop.contains(matrix[12], matrix[13])) {
                placements.add(nodeMatrix(matrix));
                lit.add(copy);
            }
        }
        if (placements.isEmpty()) {
            return;
        }
        var index = sharedMesh(entity, mesh);
        if (index < 0) {
            return;
        }
        var first = placements.getFirst();
        var anchor = new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, first[12], first[13], first[14], 1};
        var count = placements.size();
        var translations = new float[count * 3];
        var rotations = new float[count * 4];
        var scales = new float[count * 3];
        var scale = new float[3];
        var rotation = new float[4];
        for (var copy = 0; copy < count; copy++) {
            var placement = placements.get(copy);
            translations[copy * 3] = placement[12] - first[12];
            translations[copy * 3 + 1] = placement[13] - first[13];
            translations[copy * 3 + 2] = placement[14] - first[14];
            decompose(placement, rotation, scale);
            System.arraycopy(rotation, 0, rotations, copy * 4, 4);
            System.arraycopy(scale, 0, scales, copy * 3, 3);
        }
        addNode(
                "Grass and flowers",
                new Node(
                        "Grass " + displayName(entity),
                        index,
                        anchor,
                        Map.of(),
                        new Instances(
                                translations,
                                rotations,
                                scales,
                                clientLight ? decorationLight(entity, mesh, lit, hour) : null)));
    }

    private int sharedMesh(Entity entity, EntityMesh mesh) {
        var key = new SharedKey(mesh.vertices(), mesh.indices(), mesh.surfaces());
        var index = sharedMeshes.get(key);
        if (index == null) {
            var builders = new LinkedHashMap<Integer, PrimitiveBuilder>();
            secondarySource = clientLight;
            for (var surface : mesh.surfaces()) {
                addSurface(mesh, surface, null, builders);
            }
            secondarySource = false;
            var primitives = finish(builders);
            index = primitives.isEmpty() ? -1 : meshes.size();
            if (!primitives.isEmpty()) {
                meshes.add(new Mesh(displayName(entity), primitives));
                var sources = new IntArrayList();
                for (var builder : builders.values()) {
                    if (builder.indices.size() > 0) {
                        sources.addAll(builder.sources);
                    }
                }
                meshSources.put(index, sources.toArray());
            }
            sharedMeshes.put(key, index);
        }
        return index;
    }

    private void addSurface(
            EntityMesh mesh, Surface surface, float @Nullable [] matrix, Map<Integer, PrimitiveBuilder> builders) {
        if ((surface.type() & NOT_DRAWN) != 0
                || surface.blendMode() == BlendMode.INVISIBLE
                || surface.indexCount() < 3) {
            return;
        }
        var vertices = mesh.vertexBuffer();
        var indices = mesh.indices();
        var end = Math.min(indices.length, surface.indexOffset() + surface.indexCount());
        var planeSide = (surface.type() & SurfaceType.CSG_NATIVE_PLANE_SIDE.getId()) != 0;
        var level = (surface.type() & SurfaceType.CSG.getId()) != 0;
        var doubled = (surface.type() & SurfaceType.TWO_SIDED_DOUBLED.getId()) != 0;
        var noCull = level ? (surface.type() & SurfaceType.CSG_CULL_DISABLED.getId()) != 0 && !planeSide : !doubled;
        var kept = new IntArrayList();
        var both = new IntArrayList();
        var seen = new HashMap<TriangleKey, Integer>();
        for (var offset = surface.indexOffset(); offset + 2 < end; offset += 3) {
            var a = indices[offset];
            var b = indices[offset + 1];
            var c = indices[offset + 2];
            if (planeSide && vertices.hasBspPlane(a) && planeDot(vertices, a, b, c) < 0) {
                var swap = b;
                b = c;
                c = swap;
            }
            var reverse = seen.get(triangleKey(vertices, a, c, b));
            if (reverse != null) {
                both.set(reverse, 1);
                if (agreesWithNormal(vertices, a, b, c)
                        && !agreesWithNormal(
                                vertices,
                                kept.get(reverse * 3),
                                kept.get(reverse * 3 + 1),
                                kept.get(reverse * 3 + 2))) {
                    kept.set(reverse * 3, a);
                    kept.set(reverse * 3 + 1, b);
                    kept.set(reverse * 3 + 2, c);
                }
                continue;
            }
            var forward = triangleKey(vertices, a, b, c);
            if (seen.containsKey(forward)) {
                continue;
            }
            seen.put(forward, both.size());
            kept.add(a);
            kept.add(b);
            kept.add(c);
            both.add(noCull ? 1 : 0);
        }
        var scale = surface.uvAnimation().mode() == 4 ? surface.uvAnimation() : null;
        var flip = matrix == null || determinant(matrix) >= 0;
        for (var triangle = 0; triangle < both.size(); triangle++) {
            if (matrix != null
                    && crop != null
                    && !meetsCrop(
                            matrix,
                            vertices,
                            kept.get(triangle * 3),
                            kept.get(triangle * 3 + 1),
                            kept.get(triangle * 3 + 2))) {
                continue;
            }
            var doubleSided = both.get(triangle) != 0;
            var depth = matrix != null ? waterDepth : null;
            var material = materials.getMaterial(surface, doubleSided, depth);
            var tileUvs = depth != null && materialAt(material).baseTexCoord() == 1;
            var builder =
                    builders.computeIfAbsent(material, index -> new PrimitiveBuilder(index, tileUvs ? depth : null));
            var a = builder.addVertex(vertices, kept.get(triangle * 3), matrix, scale);
            var b = builder.addVertex(vertices, kept.get(triangle * 3 + 1), matrix, scale);
            var c = builder.addVertex(vertices, kept.get(triangle * 3 + 2), matrix, scale);
            builder.triangle(a, flip ? c : b, flip ? b : c);
        }
        for (var builder : builders.values()) {
            builder.endSource();
        }
    }

    private int lightmapIndex(Texture texture) {
        return lightmapIndices.computeIfAbsent(texture, ignored -> {
            lightmapPages.add(texture);
            return lightmapPages.size() - 1;
        });
    }

    private LightmapPages lightmapPages(Entity entity) {
        var textures = lightmapsAt(entity, hour);
        if (textures == null) {
            return new LightmapPages(new int[0], null);
        }
        var pages = new int[textures.size()];
        for (var index = 0; index < pages.length; index++) {
            pages[index] = lightmapIndex(textures.get(index));
        }
        return new LightmapPages(
                pages,
                entity.getLightmaps() != null && !entity.getLightmaps().isEmpty() ? entity.getLightmapLayers() : null);
    }

    private List<Mesh> lightmapAtlas(
            List<Mesh> source, List<LayerFile> files, LightmapAtlas atlas, List<Texture> pages) {
        if (pages.isEmpty()
                && source.stream()
                        .noneMatch(mesh ->
                                mesh.primitives().stream().anyMatch(primitive -> primitive.lightmap() != null))) {
            return source;
        }
        var result = new ArrayList<Mesh>(source.size());
        for (var mesh : source) {
            var primitives = new ArrayList<Primitive>(mesh.primitives().size());
            for (var primitive : mesh.primitives()) {
                var places = primitive.lightmap();
                if (places == null) {
                    primitives.add(primitive);
                    continue;
                }
                var uvs = new float[places.length / 3 * 2];
                for (var vertex = 0; vertex < uvs.length / 2; vertex++) {
                    var page = (int) places[vertex * 3 + 2];
                    float x;
                    float y;
                    if (page < 0 || page >= pages.size()) {
                        x = atlas.x(0) + atlas.cell() / 2f;
                        y = atlas.y(0) + atlas.cell() / 2f;
                    } else {
                        x = atlas.x(page + 1)
                                + places[vertex * 3] * pages.get(page).width();
                        y = atlas.y(page + 1)
                                + places[vertex * 3 + 1] * pages.get(page).height();
                    }
                    uvs[vertex * 2] = x / atlas.width();
                    uvs[vertex * 2 + 1] = y / atlas.height();
                }
                primitives.add(new Primitive(
                        primitive.positions(),
                        primitive.normals(),
                        primitive.uvs(),
                        uvs,
                        primitive.indices(),
                        primitive.material(),
                        primitive.light(),
                        null));
            }
            result.add(new Mesh(mesh.name(), List.copyOf(primitives)));
        }
        files.add(new LayerFile(LIGHTMAP_FILE, atlas.width(), atlas.height(), false, () -> atlasPixels(atlas, pages)));
        return result;
    }

    private Node withProjectors(Node node, String group, List<Projector> all, Map<Integer, float @Nullable []> boxes) {
        var level = group.equals("Level geometry");
        if (all.isEmpty()
                || !level && !group.equals("Static meshes") && !group.equals("Moving objects")
                || refusingProjectors.contains(node)) {
            return node;
        }
        Bounds3f bounds = null;
        var reached = new IntArrayList();
        for (var index = 0; index < all.size(); index++) {
            var projector = all.get(index);
            if (!(level ? projector.level() : projector.meshes())) {
                continue;
            }
            if (bounds == null) {
                var box = boxes.computeIfAbsent(node.mesh(), this::meshBox);
                if (box == null) {
                    return node;
                }
                bounds = clientBounds(box, node.matrix());
            }
            if (projectorSources.get(projector).intersects(bounds)) {
                reached.add(index);
            }
        }
        return reached.isEmpty()
                ? node
                : new Node(
                        node.name(),
                        node.mesh(),
                        node.matrix(),
                        node.extras(),
                        node.instances(),
                        node.light(),
                        reached.toArray());
    }

    private float @Nullable [] meshBox(int mesh) {
        var box = new float[] {
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        };
        for (var primitive : meshes.get(mesh).primitives()) {
            var positions = primitive.positions();
            for (var at = 0; at + 2 < positions.length; at += 3) {
                for (var axis = 0; axis < 3; axis++) {
                    box[axis] = Math.min(box[axis], positions[at + axis]);
                    box[axis + 3] = Math.max(box[axis + 3], positions[at + axis]);
                }
            }
        }
        return box[0] <= box[3] ? box : null;
    }

    private Bounds3f clientBounds(float[] box, float @Nullable [] matrix) {
        var low = new float[] {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        var high = new float[] {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var corner = 0; corner < 8; corner++) {
            var point = new float[] {
                box[(corner & 1) == 0 ? 0 : 3], box[(corner & 2) == 0 ? 1 : 4], box[(corner & 4) == 0 ? 2 : 5]
            };
            if (matrix != null) {
                var x = point[0];
                var y = point[1];
                var z = point[2];
                for (var axis = 0; axis < 3; axis++) {
                    point[axis] = matrix[axis] * x + matrix[4 + axis] * y + matrix[8 + axis] * z + matrix[12 + axis];
                }
            }
            for (var axis = 0; axis < 3; axis++) {
                low[axis] = Math.min(low[axis], point[axis]);
                high[axis] = Math.max(high[axis], point[axis]);
            }
        }
        return new Bounds3f(
                low[0] / metersPerUnit + originX,
                low[2] / metersPerUnit + originY,
                low[1] / metersPerUnit,
                high[0] / metersPerUnit + originX,
                high[2] / metersPerUnit + originY,
                high[1] / metersPerUnit);
    }

    private Material materialAt(int material) {
        return materials.getMaterials().get(material);
    }

    private void addNode(String group, Node node) {
        tiles.get(tile).computeIfAbsent(group, ignored -> new ArrayList<>()).add(node);
    }

    private void toLocal(float x, float y, float z, float[] out) {
        out[0] = x * metersPerUnit;
        out[1] = z * metersPerUnit;
        out[2] = y * metersPerUnit;
    }

    private void toWorld(float[] m, float x, float y, float z, float[] out) {
        var wx = m[0] * x + m[4] * y + m[8] * z + m[12];
        var wy = m[1] * x + m[5] * y + m[9] * z + m[13];
        var wz = m[2] * x + m[6] * y + m[10] * z + m[14];
        out[0] = (wx - originX) * metersPerUnit;
        out[1] = wz * metersPerUnit;
        out[2] = (wy - originY) * metersPerUnit;
    }

    private float[] nodeMatrix(float[] m) {
        var result = new float[16];
        int[] swap = {0, 2, 1};
        for (var column = 0; column < 3; column++) {
            for (var row = 0; row < 3; row++) {
                result[column * 4 + row] = m[swap[column] * 4 + swap[row]];
            }
        }
        result[12] = (m[12] - originX) * metersPerUnit;
        result[13] = m[14] * metersPerUnit;
        result[14] = (m[13] - originY) * metersPerUnit;
        result[15] = 1;
        return result;
    }

    private static float[] axis(Vec3 direction) {
        return new float[] {direction.getX(), direction.getZ(), direction.getY()};
    }

    private static @Nullable Primitive cropTerrain(
            TerrainVertexBuffer vertices,
            int[] triangles,
            float[] local,
            float[] positions,
            float[] normals,
            float[] uvs,
            float[] alphaUvs,
            float @Nullable [] light,
            int material) {
        var remap = new int[vertices.size()];
        Arrays.fill(remap, -1);
        var keptIndices = new IntArrayList();
        var keptPositions = new FloatArrayList();
        var keptNormals = new FloatArrayList();
        var keptUvs = new FloatArrayList();
        var keptAlphaUvs = new FloatArrayList();
        var keptLight = new FloatArrayList();
        for (var offset = 0; offset + 2 < triangles.length; offset += 3) {
            var lowX = Float.MAX_VALUE;
            var lowY = Float.MAX_VALUE;
            var highX = -Float.MAX_VALUE;
            var highY = -Float.MAX_VALUE;
            for (var corner = 0; corner < 3; corner++) {
                var vertex = triangles[offset + corner];
                lowX = Math.min(lowX, vertices.getPositionX(vertex));
                highX = Math.max(highX, vertices.getPositionX(vertex));
                lowY = Math.min(lowY, vertices.getPositionY(vertex));
                highY = Math.max(highY, vertices.getPositionY(vertex));
            }
            if (highX < local[0] || lowX > local[2] || highY < local[1] || lowY > local[3]) {
                continue;
            }
            for (var corner = 0; corner < 3; corner++) {
                var vertex = triangles[offset + corner];
                if (remap[vertex] < 0) {
                    remap[vertex] = keptPositions.size() / 3;
                    for (var axis = 0; axis < 3; axis++) {
                        keptPositions.add(positions[vertex * 3 + axis]);
                        keptNormals.add(normals[vertex * 3 + axis]);
                    }
                    keptUvs.add(uvs[vertex * 2]);
                    keptUvs.add(uvs[vertex * 2 + 1]);
                    keptAlphaUvs.add(alphaUvs[vertex * 2]);
                    keptAlphaUvs.add(alphaUvs[vertex * 2 + 1]);
                    for (var value = 0; light != null && value < 4; value++) {
                        keptLight.add(light[vertex * 4 + value]);
                    }
                }
                keptIndices.add(remap[vertex]);
            }
        }
        if (keptIndices.isEmpty()) {
            return null;
        }
        return new Primitive(
                keptPositions.toArray(),
                keptNormals.toArray(),
                keptUvs.toArray(),
                keptAlphaUvs.toArray(),
                keptIndices.toArray(),
                material,
                light == null ? null : keptLight.toArray());
    }

    private static float[] localRectangle(float[] m, LocationExport.Area area) {
        var inverse = inverseAffine(m);
        var result = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var corner = 0; corner < 4; corner++) {
            var x = corner % 2 == 0 ? area.minX() : area.maxX();
            var y = corner < 2 ? area.minY() : area.maxY();
            var localX = inverse[0] * x + inverse[4] * y + inverse[12];
            var localY = inverse[1] * x + inverse[5] * y + inverse[13];
            result[0] = Math.min(result[0], localX);
            result[1] = Math.min(result[1], localY);
            result[2] = Math.max(result[2], localX);
            result[3] = Math.max(result[3], localY);
        }
        return result;
    }

    private static float[] inverseAffine(float[] m) {
        var a = m[0];
        var b = m[4];
        var c = m[8];
        var d = m[1];
        var e = m[5];
        var f = m[9];
        var g = m[2];
        var h = m[6];
        var i = m[10];
        var det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        var inv = new float[16];
        inv[0] = (e * i - f * h) / det;
        inv[4] = (c * h - b * i) / det;
        inv[8] = (b * f - c * e) / det;
        inv[1] = (f * g - d * i) / det;
        inv[5] = (a * i - c * g) / det;
        inv[9] = (c * d - a * f) / det;
        inv[2] = (d * h - e * g) / det;
        inv[6] = (b * g - a * h) / det;
        inv[10] = (a * e - b * d) / det;
        inv[12] = -(inv[0] * m[12] + inv[4] * m[13] + inv[8] * m[14]);
        inv[13] = -(inv[1] * m[12] + inv[5] * m[13] + inv[9] * m[14]);
        inv[14] = -(inv[2] * m[12] + inv[6] * m[13] + inv[10] * m[14]);
        inv[15] = 1;
        return inv;
    }

    private static Map<String, String> layer(Effect effect, Map<String, String> loop) {
        var extras = new HashMap<>(loop);
        extras.put("cruma_effect_actor", effect.actor());
        extras.put("cruma_effect_order", Integer.toString(effect.order()));
        return Map.copyOf(extras);
    }

    private static Map<String, String> withBlend(Map<String, String> extras, int blend) {
        var result = new HashMap<>(extras);
        result.put("cruma_effect_blend", Integer.toString(blend));
        return Map.copyOf(result);
    }

    private static float[] billboardMatrix(float[] m) {
        var result = new float[16];
        result[0] = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
        result[5] = 1;
        result[10] = (float) Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]);
        result[12] = m[12];
        result[13] = m[13];
        result[14] = m[14];
        result[15] = 1;
        return result;
    }

    private static byte toByte(float value) {
        return (byte) Math.round(Math.clamp(value, 0f, 1f) * 255f);
    }

    private static float decorationShadow(Vec4 packed, int slot) {
        var value = (long) (slot < 3 ? packed.getX() : slot < 6 ? packed.getY() : packed.getZ());
        var shift = (slot < 3 ? slot : slot < 6 ? slot - 3 : slot - 6) * 8;
        return (value >>> shift & 0xFF) / 255f;
    }

    private static List<Primitive> finish(Map<Integer, PrimitiveBuilder> builders) {
        var result = new ArrayList<Primitive>(builders.size());
        for (var builder : builders.values()) {
            if (builder.indices.size() > 0) {
                result.add(builder.build());
            }
        }
        return result;
    }

    private final class PrimitiveBuilder {
        private final int material;
        private final WaterDepth.@Nullable Result tile;
        private final FloatArrayList positions = new FloatArrayList();
        private final FloatArrayList normals = new FloatArrayList();
        private final FloatArrayList uvs = new FloatArrayList();
        private final FloatArrayList tileUvs = new FloatArrayList();
        private final FloatArrayList lightmapPlaces = new FloatArrayList();
        private final IntArrayList sources = new IntArrayList();
        private final IntArrayList indices = new IntArrayList();
        private final HashMap<Integer, Integer> remap = new HashMap<>();
        private final float[] point = new float[3];
        private final @Nullable LightmapPages lightmap;
        private final boolean secondary;

        PrimitiveBuilder(int material, WaterDepth.@Nullable Result tile) {
            this.material = material;
            this.tile = tile;
            this.lightmap = tile == null ? lightmapSource : null;
            this.secondary = tile == null && lightmap == null && secondarySource;
        }

        int addVertex(MeshVertexBuffer vertices, int source, float @Nullable [] matrix, UvAnimation scale) {
            var known = remap.get(source);
            if (known != null) {
                return known;
            }
            var index = positions.size() / 3;
            if (matrix == null) {
                toLocal(
                        vertices.getPositionX(source),
                        vertices.getPositionY(source),
                        vertices.getPositionZ(source),
                        point);
                positions.addAll(point);
                normals.add(vertices.getNormalX(source));
                normals.add(vertices.getNormalZ(source));
                normals.add(vertices.getNormalY(source));
            } else {
                toWorld(
                        matrix,
                        vertices.getPositionX(source),
                        vertices.getPositionY(source),
                        vertices.getPositionZ(source),
                        point);
                positions.addAll(point);
                transformNormal(
                        matrix,
                        vertices.getNormalX(source),
                        vertices.getNormalY(source),
                        vertices.getNormalZ(source),
                        point);
                normals.addAll(point);
            }
            var u = vertices.getUvX(source);
            var v = vertices.getUvY(source);
            if (scale != null) {
                u = u / scale.paramX() + scale.paramZ();
                v = v / scale.paramY() + scale.paramW();
            }
            uvs.add(u);
            uvs.add(v);
            if (lightmap != null) {
                var lightmapU = vertices.getLightmapUvX(source);
                var lightmapV = vertices.getLightmapUvY(source);
                var page = lightmapU < 0 ? -1 : lightmap.page(source);
                lightmapPlaces.add(page < 0 ? 0 : lightmapU);
                lightmapPlaces.add(page < 0 ? 0 : lightmapV);
                lightmapPlaces.add(page);
            }
            if (secondary) {
                tileUvs.add(vertices.getSecondaryUvX(source));
                tileUvs.add(vertices.getSecondaryUvY(source));
            }
            if (tile != null && matrix != null) {
                var m = matrix;
                var x = vertices.getPositionX(source);
                var y = vertices.getPositionY(source);
                var z = vertices.getPositionZ(source);
                tileUvs.add(tile.u(m[0] * x + m[4] * y + m[8] * z + m[12]));
                tileUvs.add(tile.v(m[1] * x + m[5] * y + m[9] * z + m[13]));
            }
            remap.put(source, index);
            sources.add(source);
            return index;
        }

        void triangle(int a, int b, int c) {
            indices.add(a);
            indices.add(b);
            indices.add(c);
        }

        void endSource() {
            remap.clear();
        }

        Primitive build() {
            return new Primitive(
                    positions.toArray(),
                    normals.toArray(),
                    uvs.toArray(),
                    tile == null && !secondary ? null : tileUvs.toArray(),
                    indices.toArray(),
                    material,
                    null,
                    lightmap == null ? null : lightmapPlaces.toArray());
        }
    }

    private record LightmapPages(int[] pages, int @Nullable [] ordinals) {
        int page(int vertex) {
            if (pages.length == 0) {
                return -1;
            }
            var ordinal = ordinals == null || vertex >= ordinals.length ? 0 : ordinals[vertex];
            return ordinal < 0 || ordinal >= pages.length ? -1 : pages[ordinal];
        }
    }

    private static Node withBeastCells(Node node, LightmapAtlas atlas, List<Texture> pages) {
        var light = node.light();
        if (light == null || light.beastPages() == null) {
            return node;
        }
        var cells = new float[8];
        for (var bake = 0; bake < 2; bake++) {
            var page = light.beastPages()[bake];
            cells[bake * 4] = atlas.x(page + 1) / (float) atlas.width();
            cells[bake * 4 + 1] = atlas.y(page + 1) / (float) atlas.height();
            cells[bake * 4 + 2] = pages.get(page).width() / (float) atlas.width();
            cells[bake * 4 + 3] = pages.get(page).height() / (float) atlas.height();
        }
        return new Node(
                node.name(),
                node.mesh(),
                node.matrix(),
                node.extras(),
                node.instances(),
                new MeshLight(
                        light.ambient(),
                        light.actorRgb(),
                        light.intensity(),
                        light.sun(),
                        light.hardwareSun(),
                        light.vertices(),
                        light.beastPages(),
                        cells),
                node.projectors());
    }

    private static byte[] atlasPixels(LightmapAtlas atlas, List<Texture> pages) {
        var pixels = new byte[Math.multiplyExact(Math.multiplyExact(atlas.width(), atlas.height()), 4)];
        var gray = (byte) 128;
        for (var y = atlas.y(0) - LIGHTMAP_PAD; y < atlas.y(0) + atlas.cell() + LIGHTMAP_PAD; y++) {
            for (var x = atlas.x(0) - LIGHTMAP_PAD; x < atlas.x(0) + atlas.cell() + LIGHTMAP_PAD; x++) {
                var at = (y * atlas.width() + x) * 4;
                pixels[at] = gray;
                pixels[at + 1] = gray;
                pixels[at + 2] = gray;
                pixels[at + 3] = (byte) 255;
            }
        }
        for (var page = 0; page < pages.size(); page++) {
            var texture = pages.get(page);
            var width = texture.width();
            var height = texture.height();
            var rgba = LocationMaterials.decode(texture, width, height).rgba();
            var left = atlas.x(page + 1);
            var top = atlas.y(page + 1);
            for (var y = -LIGHTMAP_PAD; y < height + LIGHTMAP_PAD; y++) {
                var sourceY = Math.clamp(y, 0, height - 1);
                for (var x = -LIGHTMAP_PAD; x < width + LIGHTMAP_PAD; x++) {
                    var sourceX = Math.clamp(x, 0, width - 1);
                    var from = (sourceY * width + sourceX) * 4;
                    var to = ((top + y) * atlas.width() + left + x) * 4;
                    pixels[to] = rgba[from];
                    pixels[to + 1] = rgba[from + 1];
                    pixels[to + 2] = rgba[from + 2];
                    pixels[to + 3] = (byte) 255;
                }
            }
        }
        return pixels;
    }

    private static void transformNormal(float[] m, float x, float y, float z, float[] out) {
        var c00 = m[5] * m[10] - m[9] * m[6];
        var c01 = m[9] * m[2] - m[1] * m[10];
        var c02 = m[1] * m[6] - m[5] * m[2];
        var c10 = m[8] * m[6] - m[4] * m[10];
        var c11 = m[0] * m[10] - m[8] * m[2];
        var c12 = m[4] * m[2] - m[0] * m[6];
        var c20 = m[4] * m[9] - m[8] * m[5];
        var c21 = m[8] * m[1] - m[0] * m[9];
        var c22 = m[0] * m[5] - m[4] * m[1];
        var sign = determinant(m) < 0 ? -1f : 1f;
        var nx = (c00 * x + c10 * y + c20 * z) * sign;
        var ny = (c01 * x + c11 * y + c21 * z) * sign;
        var nz = (c02 * x + c12 * y + c22 * z) * sign;
        var length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-12f) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
            return;
        }
        out[0] = nx / length;
        out[1] = nz / length;
        out[2] = ny / length;
    }

    private static float determinant(float[] m) {
        return m[0] * (m[5] * m[10] - m[9] * m[6])
                - m[4] * (m[1] * m[10] - m[9] * m[2])
                + m[8] * (m[1] * m[6] - m[5] * m[2]);
    }

    private static float planeDot(MeshVertexBuffer v, int a, int b, int c) {
        var cross = cross(v, a, b, c);
        return cross[0] * v.getBspPlaneX(a) + cross[1] * v.getBspPlaneY(a) + cross[2] * v.getBspPlaneZ(a);
    }

    private static boolean agreesWithNormal(MeshVertexBuffer v, int a, int b, int c) {
        var cross = cross(v, a, b, c);
        var nx = v.getNormalX(a) + v.getNormalX(b) + v.getNormalX(c);
        var ny = v.getNormalY(a) + v.getNormalY(b) + v.getNormalY(c);
        var nz = v.getNormalZ(a) + v.getNormalZ(b) + v.getNormalZ(c);
        return cross[0] * nx + cross[1] * ny + cross[2] * nz > 0;
    }

    private static float[] cross(MeshVertexBuffer v, int a, int b, int c) {
        var ex = v.getPositionX(b) - v.getPositionX(a);
        var ey = v.getPositionY(b) - v.getPositionY(a);
        var ez = v.getPositionZ(b) - v.getPositionZ(a);
        var fx = v.getPositionX(c) - v.getPositionX(a);
        var fy = v.getPositionY(c) - v.getPositionY(a);
        var fz = v.getPositionZ(c) - v.getPositionZ(a);
        return new float[] {ey * fz - ez * fy, ez * fx - ex * fz, ex * fy - ey * fx};
    }

    private static TriangleKey triangleKey(MeshVertexBuffer v, int a, int b, int c) {
        var corners = new int[][] {positionBits(v, a), positionBits(v, b), positionBits(v, c)};
        var first = 0;
        for (var corner = 1; corner < 3; corner++) {
            if (Arrays.compare(corners[corner], corners[first]) < 0) {
                first = corner;
            }
        }
        var p = corners[first];
        var q = corners[(first + 1) % 3];
        var r = corners[(first + 2) % 3];
        return new TriangleKey(p[0], p[1], p[2], q[0], q[1], q[2], r[0], r[1], r[2]);
    }

    private static int[] positionBits(MeshVertexBuffer v, int vertex) {
        return new int[] {
            Float.floatToIntBits(v.getPositionX(vertex)),
            Float.floatToIntBits(v.getPositionY(vertex)),
            Float.floatToIntBits(v.getPositionZ(vertex))
        };
    }

    private static void put(float[] target, int vertex, float[] point) {
        target[vertex * 3] = point[0];
        target[vertex * 3 + 1] = point[1];
        target[vertex * 3 + 2] = point[2];
    }

    private static String displayName(Entity entity) {
        var name = entity.getMeshName() != null ? entity.getMeshName() : entity.getSourceAsset();
        return name == null || name.isBlank() ? "Mesh" : name;
    }

    private static Map<String, String> extras(Entity entity, EntityMesh mesh) {
        if (mesh.billboard()) {
            return Map.of("cruma_billboard", "1");
        }
        var actor = entity.getSourceActor();
        return entity.isDoor() && actor != null ? Map.of(DOOR, actor) : Map.of();
    }

    private static String nodeName(Entity entity) {
        var actor = entity.getSourceActor();
        if (actor != null && !actor.isBlank()) {
            var dot = actor.lastIndexOf('.');
            return dot >= 0 && dot < actor.length() - 1 ? actor.substring(dot + 1) : actor;
        }
        return displayName(entity);
    }
}

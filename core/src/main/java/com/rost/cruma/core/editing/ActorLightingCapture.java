package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingScene.Bake;
import com.rost.cruma.core.editing.ActorLightingScene.Light;
import com.rost.cruma.core.editing.ActorLightingScene.Mesh;
import com.rost.cruma.core.editing.ActorLightingScene.Receiver;
import com.rost.cruma.core.editing.ActorLightingScene.Settings;
import com.rost.cruma.core.editing.ActorLightingScene.Zone;
import com.rost.cruma.core.editing.ActorLightingTrace.Bsp;
import com.rost.cruma.core.editing.ActorLightingTrace.Caster;
import com.rost.cruma.core.editing.ActorLightingTrace.Triangles;
import com.rost.cruma.core.editing.TerrainNativeCodec.Coords;
import com.rost.cruma.core.env.UnrealHsv;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UActor.LightActor;
import com.rost.cruma.core.unreal.UActor.SkyZoneInfoActor;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.unreal.UStaticMesh.Collision;
import com.rost.cruma.core.unreal.UTerrain.UTerrainInfoActor;
import com.rost.cruma.core.world.BspLeafSelection;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.NativeActorTransformPair;
import com.rost.cruma.core.world.NativeActorTransformPair.Parameters;
import com.rost.cruma.core.world.NativeActorTransformPair.Rotation;
import com.rost.cruma.core.world.UnrealLoader;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class ActorLightingCapture {
    public static final int LOD_INSTANCE_LICENSE = 39;

    public static final int ENVIRONMENT_LIGHTS_LICENSE = 4;

    public static final int LOD_REFERENCE_LICENSE = 12;

    static final int BEAST_OPAQUE = 0;

    static final int BEAST_MASKED = 1;

    static final int BEAST_NONE = 2;

    private static final String[] MESH_ACTOR_CLASSES = {
        "StaticMeshActor", "MovableStaticMeshActor", "L2MovableStaticMeshActor"
    };

    private static final Collision NO_COLLISION = new Collision(new float[0], new int[0], new int[0], new float[0]);

    private ActorLightingCapture() {}

    public static Settings readSettings(Path clientRoot) {
        Path env = null;
        try (var system = Files.list(clientRoot)) {
            var directory = system.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("System"))
                    .findFirst()
                    .orElse(null);
            if (directory != null) {
                try (var files = Files.list(directory)) {
                    env = files.filter(path -> path.getFileName().toString().equalsIgnoreCase("Env.int"))
                            .findFirst()
                            .orElse(null);
                }
            }
        } catch (IOException failure) {
            return Settings.CLIENT_DEFAULTS;
        }
        if (env == null) {
            return Settings.CLIENT_DEFAULTS;
        }
        try {
            var buffer = UDecryptor.decrypt(env);
            var bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return parseSettings(new String(bytes, StandardCharsets.ISO_8859_1));
        } catch (RuntimeException failure) {
            return Settings.CLIENT_DEFAULTS;
        }
    }

    static Settings parseSettings(String text) {
        var values = new HashMap<String, String>();
        var section = "";
        for (var raw : text.split("\\R", -1)) {
            var line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                continue;
            }
            var comment = line.indexOf(';');
            if (comment >= 0) {
                line = line.substring(0, comment).trim();
            }
            var equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            values.putIfAbsent(
                    section + "." + line.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                    line.substring(equals + 1).trim());
        }
        var slots = (int) parseNumber(values.get("actorstaticlight.num"), 0);
        var offset = parseNumber(values.get("envsetup.staticlightingadjust"), 1);
        var slope = parseNumber(values.get("envsetup.slopesunangle"), 0);
        return new Settings(Math.clamp(slots, 0, 64), offset, 180f, slope, false);
    }

    public static boolean hasLightingData(UArchive map) {
        var levels = new ArrayList<ULevel>();
        map.loadObjects("Level", levels);
        if (levels.isEmpty() || levels.getFirst().getModel().isNull()) {
            return false;
        }
        try {
            var model = levels.getFirst().getModel().get();
            return model != null
                    && model.getLeaves() != null
                    && model.getLightActors() != null
                    && model.getNodes() != null;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    public static ActorLightingScene capture(UArchive map, Settings settings, BooleanSupplier cancelled) {
        return capture(map, settings, null, cancelled);
    }

    public static ActorLightingScene capture(
            UArchive map, Settings settings, @Nullable Set<String> receiverPaths, BooleanSupplier cancelled) {
        return capture(map, settings, receiverPaths, true, cancelled);
    }

    public static ActorLightingScene capture(
            UArchive map,
            Settings settings,
            @Nullable Set<String> receiverPaths,
            boolean beast,
            BooleanSupplier cancelled) {
        var notes = new ArrayList<String>();
        var levels = new ArrayList<ULevel>();
        map.loadObjects("Level", levels);
        if (levels.isEmpty()) {
            throw new IllegalStateException("Map " + map.getName() + " has no Level");
        }
        var level = levels.getFirst();
        var model = level.getModel().get();
        if (model == null) {
            throw new IllegalStateException("Map " + map.getName() + " has no level model");
        }
        var registered = new HashSet<String>();
        for (var reference : level.getActors()) {
            var path = reference.peekExportSourcePath();
            if (path != null) {
                registered.add(path);
            }
        }
        var levelInfo = level.getLevelInfo();
        if (levelInfo == null) {
            var infos = new ArrayList<ZoneInfoActor>();
            map.loadObjects("LevelInfo", infos);
            levelInfo = infos.stream()
                    .filter(info -> !info.isDeleteMe())
                    .findFirst()
                    .orElse(null);
        }
        var levelBrightness = levelInfo != null && Float.isFinite(levelInfo.getLevelBrightness())
                ? Math.max(0f, levelInfo.getLevelBrightness())
                : 1f;
        var levelZone = levelInfo == null
                ? new Zone(UArchive.NONE_NAME, true)
                : new Zone(levelInfo.getZoneTag(), levelInfo.isSunAffect());
        var license = map.getHeader().getLicenseVersion();
        var bake = license < ENVIRONMENT_LIGHTS_LICENSE ? Bake.EARLY : Bake.NATIVE;

        var lights = new ArrayList<Light>();
        var lightIndices = new HashMap<Integer, Integer>();
        var references = model.getLightActors();
        var slots = new int[references == null ? 0 : references.size()];
        for (var i = 0; i < slots.length; i++) {
            var reference = references.get(i);
            if (reference.isNull()) {
                slots[i] = ActorLightingScene.LIST_END;
                continue;
            }
            var known = lightIndices.get(reference.getIndex());
            if (known != null) {
                slots[i] = known;
                continue;
            }
            Object object;
            try {
                object = map.getObjectLoader().loadObject(reference.getIndex());
            } catch (RuntimeException failure) {
                object = null;
            }
            if (!(object instanceof LightActor light)) {
                slots[i] = ActorLightingScene.UNRESOLVED;
                continue;
            }
            var index = lights.size();
            lights.add(readLight(reference.getIndex(), light, levelBrightness, bake == Bake.EARLY));
            lightIndices.put(reference.getIndex(), index);
            slots[i] = index;
        }
        if (references == null) {
            notes.add("The level model has no light list");
        }
        var leaves = model.getLeaves();
        var leafZones = new int[leaves == null ? 0 : leaves.length];
        var leafLights = new int[leafZones.length];
        for (var i = 0; i < leafZones.length; i++) {
            leafZones[i] = leaves[i].zone();
            leafLights[i] = leaves[i].permeating();
        }
        var modelZones = model.getZones();
        var zones = new Zone[modelZones == null ? 0 : modelZones.length];
        for (var i = 0; i < zones.length; i++) {
            ZoneInfoActor actor;
            try {
                actor = modelZones[i].getZoneActor().get();
            } catch (RuntimeException failure) {
                actor = null;
            }
            zones[i] = actor == null ? null : new Zone(actor.getZoneTag(), actor.isSunAffect());
        }

        var actors = new ArrayList<StaticMeshActor>();
        for (var type : MESH_ACTOR_CLASSES) {
            map.loadObjects(type, actors);
        }
        var seen = new HashSet<String>();
        actors.removeIf(actor ->
                !registered.contains(actor.getSourcePath()) || actor.isDeleteMe() || !seen.add(actor.getSourcePath()));

        var geometry = new IdentityHashMap<UStaticMesh, CasterGeometry>();
        var meshes = new IdentityHashMap<UStaticMesh, Mesh>();
        var casters = new ArrayList<Caster>();
        var casterIndices = new HashMap<String, Integer>();
        var transforms = new HashMap<String, NativeActorTransformPair>();
        var skippedCylinder = 0;
        var rebuiltCollision = 0;
        for (var actor : actors) {
            checkCancelled(cancelled);
            var mesh = actor.getStaticMesh().get();
            if (mesh == null) {
                continue;
            }
            NativeActorTransformPair pair;
            try {
                pair = transform(actor);
            } catch (IllegalArgumentException invalid) {
                notes.add("Skipped " + actor.getSourcePath() + ": " + invalid.getMessage());
                continue;
            }
            transforms.put(actor.getSourcePath(), pair);
            if (bake == Bake.EARLY
                    || !actor.isShadowCast()
                    || !actor.isCollideActors()
                    || !actor.isBlockZeroExtentTraces()) {
                continue;
            }
            if (actor.isUseCylinderCollision()) {
                skippedCylinder++;
                continue;
            }
            var shape = geometry.computeIfAbsent(mesh, ActorLightingCapture::casterGeometry);
            if (shape.sceneTriangles() != null) {
                rebuiltCollision++;
            }
            var localToWorld = pair.getLocalToWorld();
            casterIndices.put(actor.getSourcePath(), casters.size());
            casters.add(new Caster(
                    actor.getSourcePath(),
                    shape.collision().planes(),
                    shape.collision().nodes(),
                    shape.collision().boxes(),
                    pair.getWorldToLocal(),
                    casterBounds(mesh, shape.collision(), localToWorld),
                    shape.sceneTriangles(),
                    shape.sunTriangles()));
        }
        if (skippedCylinder > 0) {
            notes.add(skippedCylinder + " actors with cylinder collision cast no shadow");
        }
        if (rebuiltCollision > 0) {
            notes.add(rebuiltCollision
                    + " actors use colliding render triangles because their mesh stores no readable collision");
        }

        var receivers = new LinkedHashMap<String, Receiver>();
        var nodeStack = new int[model.getNodes() == null ? 0 : model.getNodes().length];
        for (var actor : actors) {
            checkCancelled(cancelled);
            var path = actor.getSourcePath();
            if (receiverPaths != null && !receiverPaths.contains(path)) {
                continue;
            }
            var mesh = actor.getStaticMesh().get();
            var pair = transforms.get(path);
            if (mesh == null || pair == null) {
                continue;
            }
            var lods = new Mesh[3];
            lods[0] = meshes.computeIfAbsent(mesh, ActorLightingCapture::readMesh);
            lods[1] = lodMesh(mesh.getStaticMeshLod1().get(), meshes);
            lods[2] = lodMesh(mesh.getStaticMeshLod2().get(), meshes);
            var localToWorld = pair.getLocalToWorld();
            var sphere = transformSphere(
                    mesh.getBoundingSphere().location(),
                    mesh.getBoundingSphere().radius(),
                    localToWorld);
            var leafList = new int[0];
            var box = transformBox(
                    mesh.getBoundingBox().min(), mesh.getBoundingBox().max(), localToWorld);
            if (nodeStack.length > 0 && leaves != null) {
                var worldBox = new Box(new Vec3(box[0], box[1], box[2]), new Vec3(box[3], box[4], box[5]));
                leafList = BspLeafSelection.collect(
                        model, worldBox, nodeStack, actor.getForcedRegionTag(), actor.getForcedRegion());
                leafList = BspLeafSelection.filterVisibility(
                        model, leafList, actor.getForcedVisibilityZoneTag(), levelInfo);
            }
            var caster = casterIndices.getOrDefault(path, -1);
            var separateLods = lods[1] != null && lods[1] != lods[0] || lods[2] != null && lods[2] != lods[0];
            var eligible = isEligible(actor.isStatic(), actor.isAgitDefaultStaticMesh(), separateLods, license);
            if (license < LOD_REFERENCE_LICENSE) {
                lods[1] = null;
                lods[2] = null;
            }
            receivers.put(
                    path,
                    new Receiver(
                            path,
                            lods,
                            localToWorld,
                            sphere,
                            box,
                            leafList,
                            caster,
                            eligible,
                            actor.isHiddenEd() || actor.isHiddenEdGroup(),
                            actor.isSpecialLit(),
                            actor.isStaticLighting(),
                            actor.getForcedRegionTag(),
                            actor.getForcedRegion(),
                            actor.getScaleGlow()));
        }
        var terrains = new ArrayList<NativeTerrainTrace>();
        var terrainInfos = new ArrayList<UTerrainInfoActor>();
        map.loadObjects("TerrainInfo", terrainInfos);
        for (var info : terrainInfos) {
            if (info.isDeleteMe() || !registered.contains(info.getSourcePath())) {
                continue;
            }
            if (!(info.isShadowCast() && info.isCollideActors() && info.isBlockZeroExtentTraces())) {
                continue;
            }
            var terrain = terrainTraceOf(info);
            if (terrain != null) {
                terrains.add(terrain);
            } else {
                notes.add("Terrain " + info.getSourcePath() + " could not be traced");
            }
        }
        var beastLevel = model.getBeastLightingFlag() != null && model.getBeastLightingFlag() != 0;
        var beastBaked = beast && (beastLevel || hasBeastTextures(map));
        var beastOwners = new ArrayList<String>();
        var beastTrace = beastBaked
                ? beastTrace(model, actors, transforms, terrainInfos, registered, beastOwners, cancelled)
                : null;
        var beastLights = beastBaked ? levelLights(map, level, levelBrightness) : List.<Light>of();
        return new ActorLightingScene(
                settings,
                lights,
                leafZones,
                leafLights,
                slots,
                zones,
                levelZone,
                new ActorLightingTrace(readBsp(model), casters, terrains),
                receivers,
                bake,
                beastLevel,
                beastTrace,
                beastLights,
                beastOwners,
                notes);
    }

    static boolean hasBeastTextures(UArchive map) {
        for (var export : map.getExportMap()) {
            if (export.getClassName().equals("Texture")
                    && export.getObjectName().contains("_Lightmap_")) {
                return true;
            }
        }
        return false;
    }

    static List<Light> levelLights(UArchive map, ULevel level, float levelBrightness) {
        var result = new ArrayList<Light>();
        var seen = new HashSet<Integer>();
        for (var reference : level.getActors()) {
            var index = reference.getIndex();
            if (index <= 0 || !seen.add(index)) {
                continue;
            }
            Object object;
            try {
                object = map.getObjectLoader().loadObject(index);
            } catch (RuntimeException unreadable) {
                continue;
            }
            if (object instanceof LightActor light && !light.isDeleteMe()) {
                result.add(readLight(index, light, levelBrightness, false));
            }
        }
        return result;
    }

    static @Nullable Caster beastCaster(
            StaticMeshActor actor, NativeActorTransformPair pair, Map<String, Triangles[]> shapes) {
        if (!actor.isShadowCast()) {
            return null;
        }
        UStaticMesh mesh;
        try {
            mesh = actor.getStaticMesh().get();
        } catch (RuntimeException missing) {
            return null;
        }
        if (mesh == null || mesh.getBoundingBox() == null) {
            return null;
        }
        var classes = beastSectionClasses(actor, mesh);
        var key = System.identityHashCode(mesh) + ":" + mesh.getFullName() + ":" + Arrays.toString(classes);
        var pairOfShapes = shapes.computeIfAbsent(key, ignored -> {
            var opaque = new boolean[classes.length];
            var blocking = new boolean[classes.length];
            for (var i = 0; i < classes.length; i++) {
                opaque[i] = classes[i] == BEAST_OPAQUE;
                blocking[i] = classes[i] != BEAST_NONE;
            }
            return new Triangles[] {
                new Triangles(renderTriangles(mesh, opaque)), new Triangles(renderTriangles(mesh, blocking))
            };
        });
        if (pairOfShapes[1].getCount() == 0) {
            return null;
        }
        return new Caster(
                actor.getSourcePath(),
                new float[0],
                new int[0],
                new float[0],
                pair.getWorldToLocal(),
                transformBox(mesh.getBoundingBox().min(), mesh.getBoundingBox().max(), pair.getLocalToWorld()),
                pairOfShapes[0],
                pairOfShapes[1]);
    }

    static List<Caster> beastCasters(Iterable<StaticMeshActor> actors, Set<String> paths) {
        var shapes = new HashMap<String, Triangles[]>();
        var result = new ArrayList<Caster>();
        var seen = new HashSet<String>();
        for (var actor : actors) {
            if (!paths.contains(actor.getSourcePath()) || actor.isDeleteMe() || !seen.add(actor.getSourcePath())) {
                continue;
            }
            NativeActorTransformPair pair;
            try {
                pair = transform(actor);
            } catch (RuntimeException degenerate) {
                continue;
            }
            var caster = beastCaster(actor, pair, shapes);
            if (caster != null) {
                result.add(caster);
            }
        }
        return result;
    }

    static int[] beastSectionClasses(StaticMeshActor actor, UStaticMesh mesh) {
        var surfaces = mesh.getSurfaces();
        var materials = mesh.getMaterials();
        var result = new int[surfaces == null ? 0 : surfaces.length];
        for (var section = 0; section < result.length; section++) {
            UMaterial material = null;
            try {
                if (actor.getSkin(section) instanceof UMaterial skin) {
                    material = skin;
                } else if (materials != null && section < materials.length) {
                    material = materials[section].getMaterial().get();
                }
            } catch (RuntimeException missing) {
                material = null;
            }
            BlendMode blend;
            try {
                blend = UnrealLoader.blendModeOf(material);
            } catch (RuntimeException unreadable) {
                blend = BlendMode.OPAQUE;
            }
            result[section] = switch (blend) {
                case OPAQUE, UNLIT -> BEAST_OPAQUE;
                case MASKED, MASKED_BLEND -> BEAST_MASKED;
                default -> BEAST_NONE;
            };
        }
        return result;
    }

    record TerrainOwner(
            String path, String name, TerrainHeightField field, BitSet visible, BitSet turned, boolean inverted) {
        NativeTerrainTrace trace() {
            return new NativeTerrainTrace(field, visible, turned, inverted);
        }
    }

    static @Nullable NativeTerrainTrace terrainTraceOf(UTerrainInfoActor info) {
        var owner = terrainOwner(info);
        try {
            return owner == null ? null : owner.trace();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static @Nullable TerrainOwner terrainOwner(UTerrainInfoActor info) {
        var coords = info.getToWorld();
        if (!info.hasSurface() || coords == null) {
            return null;
        }
        var texture = info.getTerrainMap().get();
        if (texture == null || texture.getMips() == null || texture.getMips().length == 0) {
            return null;
        }
        var width = texture.getUSize();
        var height = texture.getVSize();
        var data = texture.getMips()[0].getData();
        if (width < 4 || height < 4 || width > 256 || height > 256 || data.length < width * height * 2) {
            return null;
        }
        var samples = new short[width * height];
        for (var i = 0; i < samples.length; i++) {
            samples[i] = (short) ((data[i * 2] & 0xff) | (data[i * 2 + 1] & 0xff) << 8);
        }
        var toWorld = new Coords(
                toVector(coords.origin()),
                toVector(coords.xAxis()),
                toVector(coords.yAxis()),
                toVector(coords.zAxis()));
        var visible = new BitSet();
        var turned = new BitSet();
        var quads = info.getQuadVisibilityBitmap();
        var turns = info.getEdgeTurnBitmap();
        for (var i = 0; i < samples.length; i++) {
            if (quads != null && i < quads.size() && quads.get(i)) {
                visible.set(i);
            }
            if (turns != null && i < turns.size() && turns.get(i)) {
                turned.set(i);
            }
        }
        try {
            var field = new TerrainHeightField(width, height, samples, toWorld);
            return new TerrainOwner(info.getSourcePath(), info.getName(), field, visible, turned, info.isInverted());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static Bsp readBsp(UModel model) {
        var nodes = model.getNodes();
        var surfaces = model.getSurfaces();
        if (nodes == null || surfaces == null) {
            return Bsp.EMPTY;
        }
        var planes = new float[nodes.length * 4];
        var data = new int[nodes.length * 5];
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var plane = node.getPlane();
            planes[i * 4] = plane.getX();
            planes[i * 4 + 1] = plane.getY();
            planes[i * 4 + 2] = plane.getZ();
            planes[i * 4 + 3] = plane.getW();
            data[i * 5] = node.getFlags() & 0xff;
            data[i * 5 + 1] = node.getVertexCount() & 0xff;
            data[i * 5 + 2] = node.getFrontIndex();
            data[i * 5 + 3] = node.getBackIndex();
            data[i * 5 + 4] = node.getSurfaceIndex();
        }
        var flags = new int[surfaces.length];
        for (var i = 0; i < surfaces.length; i++) {
            flags[i] = (int) surfaces[i].getPolygonFlags();
        }
        return new Bsp(planes, data, flags, model.isRootOutside());
    }

    static Light readLight(int reference, LightActor actor, float levelBrightness, boolean nativeDirection) {
        var base = UnrealHsv.toRgb(actor.getLightHue(), actor.getLightSaturation(), 255);
        var scale = actor.getLightBrightness() / 255f * levelBrightness;
        var zone = actor.getRegionZone();
        var rotation = actor.getRotation();
        var pitch = rotation.pitch() * (Math.PI * 2 / 65536.0);
        var yaw = rotation.yaw() * (Math.PI * 2 / 65536.0);
        var direction = nativeDirection
                ? TerrainLightingScene.rotationVector(rotation.pitch(), rotation.yaw())
                : new TerrainNativeCodec.Vector(
                        (float) (Math.cos(pitch) * Math.cos(yaw)), (float) (Math.cos(pitch) * Math.sin(yaw)), (float)
                                Math.sin(pitch));
        var location = actor.getLocation();
        return new Light(
                reference,
                actor.getFullName(),
                actor.getLightType(),
                actor.getLightEffect(),
                actor.isSpecialLit(),
                actor.isDeleteMe() || actor.isDynamicLight(),
                actor.isHiddenEd() || actor.isHiddenEdGroup(),
                Math.max(0, actor.getRegionZoneNumber()),
                zone instanceof SkyZoneInfoActor,
                location.getX(),
                location.getY(),
                location.getZ(),
                (actor.getLightRadius() + 1f) * 25f,
                base.getX() * scale,
                base.getY() * scale,
                base.getZ() * scale,
                direction.x(),
                direction.y(),
                direction.z(),
                actor.getLightCone());
    }

    static boolean isEligible(
            boolean isStatic, boolean agitDefaultStaticMesh, boolean separateLods, int licenseVersion) {
        var lodInstances = licenseVersion < LOD_REFERENCE_LICENSE || licenseVersion >= LOD_INSTANCE_LICENSE;
        return isStatic && !agitDefaultStaticMesh && (lodInstances || !separateLods);
    }

    static NativeActorTransformPair transform(UActor actor) {
        var location = actor.getLocation();
        var pivot = actor.getPrePivot();
        var rotation = actor.getRotation();
        var scale = actor.getDrawScale3d();
        return new NativeActorTransformPair(new Parameters(
                new NativeActorTransformPair.Vector(location.getX(), location.getY(), location.getZ()),
                new NativeActorTransformPair.Vector(pivot.getX(), pivot.getY(), pivot.getZ()),
                new Rotation(rotation.pitch(), rotation.yaw(), rotation.roll()),
                actor.getDrawScale(),
                new NativeActorTransformPair.Vector(scale.getX(), scale.getY(), scale.getZ())));
    }

    static Mesh readMesh(UStaticMesh mesh) {
        var vertices = mesh.getVertexStream().getVertices();
        var positions = new float[vertices.length * 3];
        var normals = new float[vertices.length * 3];
        for (var i = 0; i < vertices.length; i++) {
            var location = vertices[i].getLocation();
            var normal = vertices[i].getNormal();
            positions[i * 3] = location.getX();
            positions[i * 3 + 1] = location.getY();
            positions[i * 3 + 2] = location.getZ();
            normals[i * 3] = normal.getX();
            normals[i * 3 + 1] = normal.getY();
            normals[i * 3 + 2] = normal.getZ();
        }
        byte[] colors = null;
        var stream = mesh.getColorStream();
        if (stream != null && stream.getColors() != null && stream.getColors().length == vertices.length) {
            colors = new byte[vertices.length * 4];
            var source = stream.getColors();
            for (var i = 0; i < source.length; i++) {
                colors[i * 4] = source[i].r();
                colors[i * 4 + 1] = source[i].g();
                colors[i * 4 + 2] = source[i].b();
                colors[i * 4 + 3] = source[i].a();
            }
        }
        float[] beastUvs = null;
        var uvs = mesh.getBeastLightmapUvs();
        if (uvs != null && uvs.length == vertices.length) {
            beastUvs = new float[vertices.length * 2];
            for (var i = 0; i < uvs.length; i++) {
                beastUvs[i * 2] = uvs[i].getX();
                beastUvs[i * 2 + 1] = uvs[i].getY();
            }
        }
        return new Mesh(
                mesh.getFullName(),
                positions,
                normals,
                colors,
                mesh.isUseVertexColor(),
                triangleIndices(mesh),
                beastUvs,
                mesh.getBeastLightmapSize());
    }

    static int[] triangleIndices(UStaticMesh mesh) {
        var vertices = mesh.getVertexStream().getVertices().length;
        var indices =
                mesh.getIndexStream() == null ? null : mesh.getIndexStream().getIndices();
        var surfaces = mesh.getSurfaces();
        if (indices == null || surfaces == null) {
            return new int[0];
        }
        var count = 0;
        for (var surface : surfaces) {
            count += surface.getTriangleCount();
        }
        var result = new int[count * 3];
        var size = 0;
        for (var surface : surfaces) {
            for (var face = 0; face < surface.getTriangleCount(); face++) {
                var first = surface.getFirstIndex() + face * 3;
                if (first + 2 >= indices.length) {
                    break;
                }
                if (!isValidIndex(indices[first], vertices)
                        || !isValidIndex(indices[first + 1], vertices)
                        || !isValidIndex(indices[first + 2], vertices)) {
                    continue;
                }
                result[size++] = indices[first];
                result[size++] = indices[first + 1];
                result[size++] = indices[first + 2];
            }
        }
        return Arrays.copyOf(result, size);
    }

    static CasterGeometry casterGeometry(UStaticMesh mesh) {
        Collision collision;
        try {
            collision = mesh.readCollision();
        } catch (RuntimeException failure) {
            collision = null;
        }
        Triangles scene = null;
        boolean collides;
        if (collision == null) {
            collision = NO_COLLISION;
            scene = new Triangles(renderTriangles(mesh, true));
            collides = scene.getCount() > 0;
        } else {
            collides = collision.nodeCount() > 0;
        }
        Triangles sun = null;
        if (collides && hasNonCollidingSection(mesh)) {
            sun = new Triangles(renderTriangles(mesh, false));
        }
        return new CasterGeometry(collision, scene, sun);
    }

    static boolean hasNonCollidingSection(UStaticMesh mesh) {
        var materials = mesh.getMaterials();
        if (materials == null) {
            return false;
        }
        for (var material : materials) {
            if (!material.isEnableCollision()) {
                return true;
            }
        }
        return false;
    }

    static float[] renderTriangles(UStaticMesh mesh, boolean collidingOnly) {
        var surfaces = mesh.getSurfaces();
        var materials = mesh.getMaterials();
        var keep = new boolean[surfaces.length];
        for (var section = 0; section < surfaces.length; section++) {
            keep[section] = !collidingOnly
                    || materials != null && section < materials.length && materials[section].isEnableCollision();
        }
        return renderTriangles(mesh, keep);
    }

    static float[] renderTriangles(UStaticMesh mesh, boolean[] keep) {
        var vertices = mesh.getVertexStream().getVertices();
        var indices = mesh.getIndexStream().getIndices();
        var surfaces = mesh.getSurfaces();
        var count = 0;
        for (var surface : surfaces) {
            count += surface.getTriangleCount();
        }
        var result = new float[count * 9];
        var size = 0;
        for (var section = 0; section < surfaces.length; section++) {
            if (section >= keep.length || !keep[section]) {
                continue;
            }
            var surface = surfaces[section];
            for (var face = 0; face < surface.getTriangleCount(); face++) {
                var first = surface.getFirstIndex() + face * 3;
                if (first + 2 >= indices.length) {
                    break;
                }
                if (!isValidIndex(indices[first], vertices.length)
                        || !isValidIndex(indices[first + 1], vertices.length)
                        || !isValidIndex(indices[first + 2], vertices.length)) {
                    continue;
                }
                for (var corner = 0; corner < 3; corner++) {
                    var location = vertices[indices[first + corner]].getLocation();
                    result[size++] = location.getX();
                    result[size++] = location.getY();
                    result[size++] = location.getZ();
                }
            }
        }
        return Arrays.copyOf(result, size);
    }

    static float[] casterBounds(UStaticMesh mesh, Collision collision, float[] localToWorld) {
        var result =
                transformBox(mesh.getBoundingBox().min(), mesh.getBoundingBox().max(), localToWorld);
        var boxes = collision.boxes();
        for (var node = 0; node < boxes.length / 6; node++) {
            var o = node * 6;
            var box = transformBox(
                    new Vec3(boxes[o], boxes[o + 1], boxes[o + 2]),
                    new Vec3(boxes[o + 3], boxes[o + 4], boxes[o + 5]),
                    localToWorld);
            for (var axis = 0; axis < 3; axis++) {
                result[axis] = Math.min(result[axis], box[axis]);
                result[axis + 3] = Math.max(result[axis + 3], box[axis + 3]);
            }
        }
        return result;
    }

    static float[] transformBox(Vec3 min, Vec3 max, float[] m) {
        var result = new float[6];
        for (var corner = 0; corner < 8; corner++) {
            var x = (corner & 4) == 0 ? min.getX() : max.getX();
            var y = (corner & 2) == 0 ? min.getY() : max.getY();
            var z = (corner & 1) == 0 ? min.getZ() : max.getZ();
            for (var axis = 0; axis < 3; axis++) {
                var value = (m[axis + 8] * z + (m[axis] * x + m[axis + 4] * y)) + m[axis + 12];
                if (corner == 0) {
                    result[axis] = value;
                    result[axis + 3] = value;
                } else {
                    if (!(value >= result[axis])) {
                        result[axis] = value;
                    }
                    if (!(result[axis + 3] >= value)) {
                        result[axis + 3] = value;
                    }
                }
            }
        }
        return result;
    }

    static float[] transformSphere(Vec3 center, float radius, float[] m) {
        var x = center.getX();
        var y = center.getY();
        var z = center.getZ();
        var cx = ((m[0] * x + m[4] * y) + m[8] * z) + m[12];
        var cy = ((m[5] * y + m[1] * x) + m[9] * z) + m[13];
        var cz = ((m[6] * y + m[2] * x) + m[10] * z) + m[14];
        var ax = (m[1] * m[1] + m[0] * m[0]) + m[2] * m[2];
        var ay = (m[5] * m[5] + m[4] * m[4]) + m[6] * m[6];
        var az = (m[9] * m[9] + m[8] * m[8]) + m[10] * m[10];
        var largest = Integer.compareUnsigned(Float.floatToRawIntBits(az), Float.floatToRawIntBits(ay)) < 0 ? ay : az;
        largest = Integer.compareUnsigned(Float.floatToRawIntBits(ax), Float.floatToRawIntBits(largest)) < 0
                ? largest
                : ax;
        return new float[] {cx, cy, cz, (float) (Math.sqrt((double) largest) * (double) radius)};
    }

    private static float parseNumber(@Nullable String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private static ActorLightingTrace beastTrace(
            UModel model,
            Iterable<StaticMeshActor> actors,
            Map<String, NativeActorTransformPair> transforms,
            Iterable<UTerrainInfoActor> terrainInfos,
            Set<String> registered,
            List<String> terrainOwners,
            BooleanSupplier cancelled) {
        var shapes = new HashMap<String, Triangles[]>();
        var casters = new ArrayList<Caster>();
        for (var actor : actors) {
            checkCancelled(cancelled);
            var pair = transforms.get(actor.getSourcePath());
            if (pair == null) {
                continue;
            }
            var caster = beastCaster(actor, pair, shapes);
            if (caster != null) {
                casters.add(caster);
            }
        }
        var terrains = new ArrayList<NativeTerrainTrace>();
        for (var info : terrainInfos) {
            if (info.isDeleteMe() || !registered.contains(info.getSourcePath())) {
                continue;
            }
            var terrain = terrainTraceOf(info);
            if (terrain != null) {
                terrains.add(terrain);
                terrainOwners.add(info.getSourcePath());
            }
        }
        return new ActorLightingTrace(readBsp(model), casters, terrains);
    }

    private static TerrainNativeCodec.Vector toVector(Vec3 value) {
        return new TerrainNativeCodec.Vector(value.getX(), value.getY(), value.getZ());
    }

    private static @Nullable Mesh lodMesh(@Nullable UStaticMesh mesh, Map<UStaticMesh, Mesh> meshes) {
        return mesh == null ? null : meshes.computeIfAbsent(mesh, ActorLightingCapture::readMesh);
    }

    record CasterGeometry(Collision collision, Triangles sceneTriangles, Triangles sunTriangles) {}

    private static boolean isValidIndex(int index, int count) {
        return index >= 0 && index < count;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Lighting build cancelled");
        }
    }
}

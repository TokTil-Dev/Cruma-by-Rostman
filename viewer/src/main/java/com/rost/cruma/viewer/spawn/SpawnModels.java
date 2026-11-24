package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.export.NpcExport.Race;
import com.rost.cruma.core.npc.NpcSocials;
import com.rost.cruma.core.npc.script.ScriptedClass;
import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.spawn.ArmorGrpTable;
import com.rost.cruma.core.spawn.ItemNames;
import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcDataTable.Npc;
import com.rost.cruma.core.spawn.NpcGrpParts;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.spawn.NpcGrpTable.Appearance;
import com.rost.cruma.core.spawn.PlayerEquipment;
import com.rost.cruma.core.spawn.PlayerEquipment.Grip;
import com.rost.cruma.core.spawn.PlayerEquipment.Tables;
import com.rost.cruma.core.spawn.WeaponGrpTable;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.unreal.NpcClasses;
import com.rost.cruma.core.unreal.NpcClasses.Body;
import com.rost.cruma.core.unreal.SkeletalPose;
import com.rost.cruma.core.unreal.primitives.USphere;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularEntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularOverlay;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureEntityMaterialWithOpacity;
import com.rost.cruma.core.world.FigureBodies;
import com.rost.cruma.core.world.NpcAnimation;
import com.rost.cruma.core.world.NpcAnimation.Pose;
import com.rost.cruma.core.world.NpcModel;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.SceneOrders.NpcSpeed;
import com.rost.cruma.core.world.SceneRig;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.UnrealLoader.HeldItem;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.spawn.SpawnModelData.Bitmap;
import com.rost.cruma.viewer.spawn.SpawnModelData.Kind;
import com.rost.cruma.viewer.spawn.SpawnModelData.Part;
import com.rost.cruma.viewer.spawn.SpawnModelData.Specular;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class SpawnModels implements AutoCloseable {
    private static final int MAX_TEXTURE = 256;

    private static final float MIN_HEIGHT = 4;

    private static final int STAND_IN_TRIANGLES = 12;

    private static final int CLEAR_CHECK_SIZE = 64;

    private static final int MAX_MODELS = 384;

    private final Map<Integer, SpawnModelData> models =
            Collections.synchronizedMap(new LinkedHashMap<>(64, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Entry<Integer, SpawnModelData> eldest) {
                    return size() > MAX_MODELS;
                }
            });
    private final Map<Integer, Body> bodies = new ConcurrentHashMap<>();
    private final Map<String, ScriptedClass> scripts = new ConcurrentHashMap<>();
    private final Set<String> withoutScript = ConcurrentHashMap.newKeySet();
    private final Set<Integer> missing = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> invisible = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> unknown = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> pending = Collections.synchronizedSet(new HashSet<>());
    private final LinkedBlockingDeque<Integer> queue = new LinkedBlockingDeque<>();
    private final Thread worker;
    private volatile @Nullable Path client;
    private final WorldVariant variant;
    private volatile boolean closed;
    private volatile String message = "";
    private @Nullable Path loadedClient;
    private @Nullable UnrealLoader loader;
    private volatile NpcGrpTable table = NpcGrpTable.empty();
    private WeaponGrpTable weapons = WeaponGrpTable.empty();
    private ArmorGrpTable armors = ArmorGrpTable.empty();
    private NpcGrpParts parts = NpcGrpParts.empty();
    private FigureBodies figureBodies = FigureBodies.empty();
    private @Nullable NpcSocials classNames;
    private volatile @Nullable Set<Integer> created;
    private volatile Map<Integer, Npc> npcsById = Map.of();
    private volatile ItemNames items = ItemNames.empty();

    private final Map<Integer, Outfit> outfits = new ConcurrentHashMap<>();

    public SpawnModels(@Nullable Path client) {
        this(client, WorldVariant.MAIN);
    }

    public SpawnModels(@Nullable Path client, WorldVariant variant) {
        this.client = client;
        this.variant = variant;
        worker = new Thread(this::run, "npc-models");
        worker.setDaemon(true);
        worker.start();
    }

    public enum Status {
        LOADING,
        READY,
        MISSING,
        INVISIBLE,
        NOT_IN_CLIENT
    }

    public static boolean isPlayerLike(Appearance appearance) {
        return UnrealLoader.isPlayerFace(appearance.mesh());
    }

    static boolean isInvisible(NpcModel model, float scale) {
        var mesh = model.entityMesh();
        if (mesh.vertices().isEmpty() || mesh.indices().length == 0) {
            return false;
        }
        var low = Float.MAX_VALUE;
        var high = -Float.MAX_VALUE;
        for (var vertex : mesh.vertices()) {
            low = Math.min(low, vertex.position().getZ() * scale);
            high = Math.max(high, vertex.position().getZ() * scale);
        }
        if (high - low < MIN_HEIGHT && mesh.indices().length / 3 <= STAND_IN_TRIANGLES) {
            return true;
        }
        for (var surface : mesh.surfaces()) {
            var texture = surface.material().getTexture();
            if (surface.blendMode() == BlendMode.OPAQUE
                    || texture == null
                    || texture.data().isEmpty()) {
                return false;
            }
            var mip = 0;
            while (mip + 1 < texture.data().size()
                    && Math.max(texture.width() >> mip, texture.height() >> mip) > CLEAR_CHECK_SIZE) {
                mip++;
            }
            try {
                var rgba = TexturePixels.decodeMipRgba(texture, mip);
                applyOpacity(
                        rgba,
                        Math.max(1, texture.width() >> mip),
                        Math.max(1, texture.height() >> mip),
                        opacityOf(surface.material()));
                if (!clear(rgba)) {
                    return false;
                }
            } catch (RuntimeException undecodable) {
                return false;
            }
        }
        return true;
    }

    static @Nullable SpawnModelData toModelData(int id, NpcModel model, float scale) {
        return toModelData(id, model, scale, null);
    }

    static @Nullable SpawnModelData toModelData(int id, NpcModel model, float scale, @Nullable Body body) {
        var mesh = model.entityMesh();
        if (mesh.vertices().isEmpty() || mesh.indices().length == 0) {
            return null;
        }
        var vertices = new float[mesh.vertices().size() * SpawnModelData.FLOATS];
        var height = 0f;
        var bottom = Float.MAX_VALUE;
        for (var i = 0; i < mesh.vertices().size(); i++) {
            var vertex = mesh.vertices().get(i);
            var at = i * SpawnModelData.FLOATS;
            vertices[at] = vertex.position().getX() * scale;
            vertices[at + 1] = vertex.position().getY() * scale;
            vertices[at + 2] = vertex.position().getZ() * scale;
            vertices[at + 3] = vertex.normal().getX();
            vertices[at + 4] = vertex.normal().getY();
            vertices[at + 5] = vertex.normal().getZ();
            vertices[at + 6] = vertex.uv().getX();
            vertices[at + 7] = vertex.uv().getY();
            height = Math.max(height, vertices[at + 2]);
            bottom = Math.min(bottom, vertices[at + 2]);
        }
        if (height - bottom < MIN_HEIGHT && mesh.indices().length / 3 <= STAND_IN_TRIANGLES) {
            return null;
        }
        var parts = new ArrayList<Part>();
        for (var surfaceIndex = 0; surfaceIndex < mesh.surfaces().size(); surfaceIndex++) {
            var surface = mesh.surfaces().get(surfaceIndex);
            var passes = model.surfacePasses().get(surfaceIndex);
            var texture = surface.material().getTexture();
            byte[] rgba = null;
            var width = 0;
            var textureHeight = 0;
            if (texture != null && !texture.data().isEmpty()) {
                var mip = 0;
                while (mip + 1 < texture.data().size()
                        && Math.max(texture.width() >> mip, texture.height() >> mip) > MAX_TEXTURE) {
                    mip++;
                }
                try {
                    rgba = TexturePixels.decodeMipRgba(texture, mip);
                    width = Math.max(1, texture.width() >> mip);
                    textureHeight = Math.max(1, texture.height() >> mip);
                    applyOpacity(rgba, width, textureHeight, opacityOf(surface.material()));
                } catch (RuntimeException undecodable) {
                    rgba = null;
                }
            }
            var kind = switch (surface.blendMode()) {
                case OPAQUE -> surface.alphaRef() > 0 ? Kind.MASKED : Kind.OPAQUE;
                case MASKED, MASKED_BLEND -> Kind.MASKED;
                default -> Kind.BLENDED;
            };
            if (kind != Kind.OPAQUE && rgba != null && clear(rgba)) {
                continue;
            }
            if (kind == Kind.BLENDED
                    && surface.blendMode() == BlendMode.TRANSLUCENT
                    && surface.depthWrite()
                    && surface.alphaRef() >= 0) {
                parts.add(new Part(
                                surface.indexOffset(),
                                surface.indexCount(),
                                width,
                                textureHeight,
                                rgba,
                                kind,
                                (surface.alphaRef() + .5f) / 255f,
                                true,
                                passes)
                        .withSpecular(passes == null ? specularOf(surface.material()) : null));
                continue;
            }
            if (passes != null) {
                parts.add(new Part(
                        surface.indexOffset(),
                        surface.indexCount(),
                        width,
                        textureHeight,
                        rgba,
                        kind,
                        kind == Kind.OPAQUE ? 0f : Part.CUT,
                        kind != Kind.BLENDED,
                        passes));
                continue;
            }
            parts.add(new Part(surface.indexOffset(), surface.indexCount(), width, textureHeight, rgba, kind)
                    .withSpecular(specularOf(surface.material())));
        }
        if (parts.isEmpty()) {
            return null;
        }
        var bones = new HashMap<String, Vec3>();
        for (var bone : model.bones().entrySet()) {
            var at = bone.getValue();
            bones.put(bone.getKey(), new Vec3(at.getX() * scale, at.getY() * scale, at.getZ() * scale));
        }
        var effectBone = body == null ? null : NpcClasses.findEffectBone(body, model.boneNames());
        var sphere = model.litSphere();
        return new SpawnModelData(
                id + ":" + model.mesh(),
                vertices,
                mesh.indices(),
                parts,
                height,
                model.lowest() * scale,
                bones,
                effectBone == null ? "" : SkeletalPose.boneKey(effectBone),
                sphere == null ? null : new USphere(sphere.location().times(scale), sphere.radius() * scale));
    }

    static @Nullable Texture opacityOf(EntityMaterial material) {
        var base = material instanceof SpecularEntityMaterial specular ? specular.base() : material;
        return base instanceof TextureEntityMaterialWithOpacity(var texture, var opacity)
                        && opacity != null
                        && !opacity.data().isEmpty()
                        && opacity != texture
                ? opacity
                : null;
    }

    static @Nullable Specular specularOf(EntityMaterial material) {
        var overlay = specularOverlayOf(material);
        if (overlay == null
                || overlay.texture() == null
                || overlay.texture().data().isEmpty()
                || overlay.fade() != null
                || overlay.maskUvChannel() != 0) {
            return null;
        }
        try {
            var mask = overlay.mask() == null || overlay.mask().data().isEmpty() ? null : bitmapOf(overlay.mask());
            var environment = overlay.environment();
            return new Specular(
                    bitmapOf(overlay.texture()),
                    mask,
                    environment,
                    environment == null ? overlay.uvAnimation() : environment.animation(),
                    overlay.modulate2x(),
                    overlay.lit(),
                    overlay.texture().clampU(),
                    overlay.texture().clampV(),
                    overlay.separateAlphaPass());
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    static void applyOpacity(byte[] rgba, int width, int height, @Nullable Texture opacity) {
        if (opacity == null) {
            return;
        }
        var mip = 0;
        while (mip + 1 < opacity.data().size()
                && Math.max(opacity.width() >> mip, opacity.height() >> mip) > Math.max(width, height)) {
            mip++;
        }
        var alpha = TexturePixels.decodeMipRgba(opacity, mip);
        var alphaWidth = Math.max(1, opacity.width() >> mip);
        var alphaHeight = Math.max(1, opacity.height() >> mip);
        for (var y = 0; y < height; y++) {
            var sourceY = y * alphaHeight / height;
            for (var x = 0; x < width; x++) {
                var sourceX = x * alphaWidth / width;
                rgba[(y * width + x) * 4 + 3] = alpha[(sourceY * alphaWidth + sourceX) * 4 + 3];
            }
        }
    }

    static boolean clear(byte[] rgba) {
        for (var i = 3; i < rgba.length; i += 4) {
            if (Byte.toUnsignedInt(rgba[i]) > 8) {
                return false;
            }
        }
        return true;
    }

    public @Nullable SpawnModelData getModel(int npcId) {
        var model = models.get(npcId);
        if (model == null && !missing.contains(npcId) && pending.add(npcId)) {
            queue.addFirst(npcId);
        }
        return model;
    }

    public Status getStatus(int npcId) {
        if (models.containsKey(npcId)) {
            return Status.READY;
        }
        if (invisible.contains(npcId)) {
            return Status.INVISIBLE;
        }
        if (unknown.contains(npcId)) {
            return Status.NOT_IN_CLIENT;
        }
        return missing.contains(npcId) ? Status.MISSING : Status.LOADING;
    }

    public Appearance getAppearance(int npcId) {
        var appearance = table.getAppearance(npcId);
        var body = bodies.get(npcId);
        return appearance == null || body == null ? appearance : appearance.withDrawScale(scale(appearance, body));
    }

    public float getCollisionHeight(int id) {
        return ensureLoaded() ? body(id).collisionHeight() : 0;
    }

    public float getCollisionRadius(int id) {
        return ensureLoaded() ? body(id).collisionRadius() : 0;
    }

    public NpcSpeed getSpeed(int id) {
        var script = npcsById.get(id);
        var appearance = ensureLoaded() ? table.getAppearance(id) : null;
        var walk = script == null ? 0 : script.walkSpeed();
        var run = script == null ? 0 : script.runSpeed();
        return new NpcSpeed(
                walk > 0 || appearance == null ? walk : appearance.walkSpeed(),
                run > 0 || appearance == null ? run : appearance.runSpeed(),
                walk > 0 || run > 0);
    }

    public String getMessage() {
        return message;
    }

    public @Nullable SpawnModelData readDrawn(int id) {
        var model = readPosed(id);
        return model == null ? null : toModelData(id, model, scale(id), body(id));
    }

    public @Nullable NpcModel readPosed(int id) {
        var read = read(id, null, false);
        return read == null ? null : read.model();
    }

    public record Animated(SpawnModelData model, NpcAnimation animation, float scale) {
        public float[] vertices(Pose pose) {
            var still = model.vertices();
            var vertices = still.clone();
            var count = Math.min(vertices.length / SpawnModelData.FLOATS, pose.positions().length / 3);
            for (var i = 0; i < count; i++) {
                var at = i * SpawnModelData.FLOATS;
                vertices[at] = pose.positions()[i * 3] * scale;
                vertices[at + 1] = pose.positions()[i * 3 + 1] * scale;
                vertices[at + 2] = pose.positions()[i * 3 + 2] * scale;
                vertices[at + 3] = pose.normals()[i * 3];
                vertices[at + 4] = pose.normals()[i * 3 + 1];
                vertices[at + 5] = pose.normals()[i * 3 + 2];
            }
            return vertices;
        }

        public Map<String, Vec3> bones(Pose pose) {
            var bones = new HashMap<String, Vec3>();
            for (var bone : pose.bones().entrySet()) {
                var at = bone.getValue();
                bones.put(bone.getKey(), new Vec3(at.getX() * scale, at.getY() * scale, at.getZ() * scale));
            }
            return bones;
        }
    }

    public @Nullable Animated readAnimated(int id, @Nullable Outfit outfit) {
        return animated(id, outfit, false);
    }

    public @Nullable Animated readScenePawn(int id, @Nullable Outfit outfit) {
        return animated(id, outfit, true);
    }

    public @Nullable SceneRig readSceneRig(int id) {
        var appearance = ensureLoaded() ? table.getAppearance(id) : null;
        var rig = appearance == null ? null : loader.loadSceneRig(appearance.mesh(), body(id).drawScale());
        return rig == null ? null : rig.waiting(waitOf(appearance), fightWaitOf(appearance));
    }

    public @Nullable ScriptedClass getClassScript(int id) {
        var appearance = ensureLoaded() ? table.getAppearance(id) : null;
        var reader = loader;
        if (appearance == null || reader == null || appearance.npcClass().isEmpty()) {
            return null;
        }
        var key = appearance.npcClass().toLowerCase(Locale.ROOT);
        var read = scripts.get(key);
        if (read != null || withoutScript.contains(key)) {
            return read;
        }
        read = reader.loadNpcClassScript(appearance.npcClass());
        if (read == null) {
            withoutScript.add(key);
        } else {
            scripts.put(key, read);
        }
        return read;
    }

    public @Nullable SceneRig readCameraRig() {
        return ensureLoaded() ? loader.loadCameraRig() : null;
    }

    public @Nullable Animated buildAnimatedCharacter(String face, Outfit outfit) {
        if (!ensureLoaded()) {
            return null;
        }
        var rig = loader.loadPlayerRig(face, List.of(), outfit);
        var animation = rig == null ? null : NpcAnimation.of(rig, outfit.classWait(), outfit.stance());
        var body = playerBody(face, outfit);
        var data = animation == null ? null : toModelData(0, animation.getModel(), body.drawScale(), body);
        return data == null
                ? null
                : new Animated(
                        withKey(data, "character:" + face + "#" + Integer.toHexString(outfit.hashCode())),
                        animation,
                        body.drawScale());
    }

    public @Nullable AirshipModel readAirship() {
        return ensureLoaded() ? AirshipModel.read(loader) : null;
    }

    public @Nullable Outfit getDress(int id) {
        if (!ensureLoaded()) {
            return null;
        }
        var appearance = table.getAppearance(id);
        if (appearance == null) {
            return null;
        }
        return UnrealLoader.isPlayerFace(appearance.mesh()) ? getDress(id, appearance) : hands(id, appearance);
    }

    public boolean hasHand(int id, SpawnModelData model) {
        var body = bodies.getOrDefault(id, Body.UNKNOWN);
        return model.bones().containsKey(SkeletalPose.boneKey(body.rightHand()));
    }

    public @Nullable Outfit getOutfit(int id) {
        return outfits.get(id);
    }

    public @Nullable SpawnModelData buildCharacter(String face, Outfit outfit) {
        if (!ensureLoaded()) {
            return null;
        }
        var model = loader.loadPlayerLikeNpcModel(face, List.of(), outfit);
        var body = playerBody(face, outfit);
        return model == null ? null : toModelData(0, model, body.drawScale(), body);
    }

    public boolean hasFace(String face) {
        return ensureLoaded() && loader.hasPlayerFace(face);
    }

    public boolean offers(Race race) {
        var face = hasFace(race.face());
        var made = created;
        return PlayerFigures.isOffered(
                race.figure(),
                face,
                armors::hasLooks,
                figureBodies::hasRow,
                made == null ? _ -> false : made::contains);
    }

    public Tables getTables() {
        ensureLoaded();
        return new Tables(items, weapons, armors, parts, waits(), figureBodies);
    }

    public @Nullable NpcDataTable readScript(Path scripts) throws IOException {
        var folder = SpawnSource.resolveFolder(scripts);
        var npcFile = folder == null ? null : SpawnSource.find(folder, SpawnSource.NPCS);
        if (npcFile == null) {
            return null;
        }
        var table = NpcDataTable.read(npcFile);
        var itemFile = SpawnSource.find(folder, "item_pch.txt");
        setScript(table, itemFile == null ? ItemNames.empty() : ItemNames.read(itemFile));
        return table;
    }

    public void setScript(NpcDataTable npcs, ItemNames itemNames) {
        var byId = new HashMap<Integer, Npc>();
        for (var npc : npcs.getNpcs()) {
            byId.putIfAbsent(npc.id(), npc);
        }
        npcsById = Map.copyOf(byId);
        items = itemNames;
        models.clear();
        bodies.clear();
        missing.clear();
        invisible.clear();
        unknown.clear();
    }

    public void setClient(@Nullable Path root) {
        if (!Objects.equals(root, client)) {
            client = root;
            models.clear();
            bodies.clear();
            missing.clear();
            invisible.clear();
            unknown.clear();
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
    }

    public void trimCaches() {
        if (loader != null) {
            loader.trimCaches();
        }
    }

    private void run() {
        while (!closed) {
            int id;
            try {
                id = queue.takeFirst();
            } catch (InterruptedException interrupted) {
                return;
            }
            try {
                var data = load(id);
                if (data == null) {
                    missing.add(id);
                } else {
                    models.put(id, data);
                }
            } catch (RuntimeException | LinkageError failure) {
                missing.add(id);
                LogManager.getLogger(SpawnModels.class).warn("NPC {} model failed: {}", id, failure.toString());
            } finally {
                pending.remove(id);
            }
        }
    }

    private @Nullable SpawnModelData load(int id) {
        var model = readPosed(id);
        return model == null ? null : toModelData(id, model, scale(id), body(id));
    }

    private @Nullable Animated animated(int id, @Nullable Outfit outfit, boolean scenePawn) {
        var read = read(id, outfit, true);
        if (read == null || read.animation() == null) {
            return null;
        }
        var scale = scenePawn ? body(id).drawScale() : scale(id);
        var data = toModelData(id, read.model(), scale, body(id));
        return data == null
                ? null
                : new Animated(
                        withKey(
                                data,
                                data.key() + "#" + Objects.hashCode(outfits.get(id)) + (scenePawn ? "#scene" : "")),
                        read.animation(),
                        scale);
    }

    private @Nullable String waitOf(Appearance appearance) {
        if (classNames == null) {
            return null;
        }
        var kind = weapons.getHeldKind(appearance.rightHand(), appearance.leftHand());
        var held = kind == 0 ? null : classNames.getWait(appearance.npcClass(), kind, false);
        return held != null ? held : classNames.getKind(appearance.npcClass(), "wait");
    }

    private @Nullable String fightWaitOf(Appearance appearance) {
        return classNames == null ? null : classNames.getKind(appearance.npcClass(), "atkwait");
    }

    private @Nullable Read read(int id, @Nullable Outfit dressed, boolean animated) {
        if (!ensureLoaded()) {
            return null;
        }
        var appearance = table.getAppearance(id);
        if (appearance == null && loader != null && table.size() > 0) {
            unknown.add(id);
        }
        if (appearance == null || loader == null) {
            return null;
        }
        if (appearance.mesh().isBlank() || appearance.mesh().equalsIgnoreCase("None")) {
            invisible.add(id);
            return null;
        }
        if (!UnrealLoader.isPlayerFace(appearance.mesh())
                && !appearance.textures().isEmpty()
                && appearance.textures().stream()
                        .allMatch(texture -> texture.toLowerCase(Locale.ROOT).contains("clear_npc"))) {
            invisible.add(id);
            return null;
        }
        NpcModel model;
        NpcAnimation animation = null;
        if (UnrealLoader.isPlayerFace(appearance.mesh())) {
            var outfit = dressed != null ? dressed : getDress(id, appearance);
            outfits.put(id, outfit);
            if (animated) {
                var rig = loader.loadPlayerRig(appearance.mesh(), appearance.textures(), outfit);
                model = rig == null ? null : rig.model();
                animation = rig == null ? null : NpcAnimation.of(rig, outfit.classWait(), outfit.stance());
            } else {
                model = loader.loadPlayerLikeNpcModel(appearance.mesh(), appearance.textures(), outfit);
            }
        } else {
            var outfit = dressed != null ? dressed : hands(id, appearance);
            var held = outfit == null ? List.<HeldItem>of() : outfit.held();
            if (outfit == null) {
                outfits.remove(id);
            } else {
                outfits.put(id, outfit);
            }
            if (animated) {
                var rig = loader.loadNpcRig(appearance.mesh(), appearance.textures(), held, waitOf(appearance));
                model = rig == null ? null : rig.model();
                animation = rig == null ? null : NpcAnimation.of(rig, waitOf(appearance), fightWaitOf(appearance));
            } else {
                model = loader.loadNpcModel(appearance.mesh(), appearance.textures(), held, waitOf(appearance));
            }
        }
        if (model != null && isInvisible(model, scale(id))) {
            invisible.add(id);
            return null;
        }
        return model == null ? null : new Read(model, animation);
    }

    private float scale(int id) {
        return scale(table.getAppearance(id), body(id));
    }

    private float scale(Appearance appearance, Body body) {
        return table.getScale(appearance, body.drawScale());
    }

    private Body body(int id) {
        var appearance = table.getAppearance(id);
        var reader = loader;
        if (appearance == null || reader == null || appearance.npcClass().isEmpty()) {
            return Body.UNKNOWN;
        }
        return bodies.computeIfAbsent(id, _ -> reader.loadNpcBody(appearance.npcClass()));
    }

    private Body playerBody(String face, Outfit outfit) {
        var reader = loader;
        if (reader == null || !UnrealLoader.isPlayerFace(face)) {
            return Body.UNKNOWN;
        }
        var figure = outfit.figure() == null ? PlayerFigures.figureOf(face) : outfit.figure();
        return reader.loadNpcBody(PlayerFigures.getPawnClass(figure));
    }

    private Outfit getDress(int id, Appearance appearance) {
        var figure = PlayerFigures.figureOf(appearance.mesh(), appearance.npcClass());
        return PlayerEquipment.buildOutfit(
                        scriptNpc(id),
                        figure,
                        new Tables(items, weapons, armors, parts, waits(), figureBodies),
                        appearance.rightHand(),
                        appearance.leftHand())
                .withRowClass(appearance.npcClass().isEmpty() ? null : appearance.npcClass());
    }

    private @Nullable Outfit hands(int id, Appearance appearance) {
        var body = body(id);
        var scale = body.weaponScale() > 0 && body.drawScale() > 0 ? body.weaponScale() / body.drawScale() : 1;
        return PlayerEquipment.buildHands(
                scriptNpc(id),
                appearance.rightHand(),
                appearance.leftHand(),
                new Grip(body.rightHand(), body.leftHand(), body.shield(), scale),
                new Tables(items, weapons, armors, parts, waits(), figureBodies));
    }

    private PlayerEquipment.Waits waits() {
        var names = classNames;
        return names == null ? PlayerEquipment.Waits.NONE : names;
    }

    private Npc scriptNpc(int id) {
        var npc = npcsById.get(id);
        return npc != null ? npc : new Npc(id, "", "", 0, "", 0, 0, false);
    }

    private boolean ensureLoaded() {
        var root = client;
        if (root == null) {
            message = "No game client is open.";
            return false;
        }
        if (!Objects.equals(root, loadedClient)) {
            loadedClient = root;
            loader = new UnrealLoader(root, new Options(false, 0), true);
            loader.worldVariant(variant);
            try {
                table = NpcGrpTable.read(root.resolve("system"), variant);
                classNames = NpcSocials.read(root, variant);
                message = "";
                var file = TableFiles.find(root.resolve("system"), variant, "npcgrp.dat");
                LogManager.getLogger(SpawnModels.class)
                        .info(
                                "Read {}: {} NPCs ({} layout)",
                                file == null ? "npcgrp.dat" : file.getFileName(),
                                table.size(),
                                table.getLayout());
            } catch (Exception failure) {
                table = NpcGrpTable.empty();
                message = "NPC models are unavailable: " + failure.getMessage();
                LogManager.getLogger(SpawnModels.class).warn("NPC models are unavailable: {}", failure.toString());
            }
            created = PlayerFigures.readCreated(root, variant);
            figureBodies = FigureBodies.read(root, variant);
            try {
                weapons = WeaponGrpTable.read(root.resolve("system"), variant);
            } catch (Exception failure) {
                weapons = WeaponGrpTable.empty();
                LogManager.getLogger(SpawnModels.class).warn("Could not read Weapongrp.dat: {}", failure.toString());
            }
            try {
                armors = ArmorGrpTable.read(root.resolve("system"), variant);
            } catch (Exception failure) {
                armors = ArmorGrpTable.empty();
                LogManager.getLogger(SpawnModels.class).warn("Could not read Armorgrp.dat: {}", failure.toString());
            }
            try {
                parts = NpcGrpParts.read(root.resolve("system"), variant);
            } catch (Exception failure) {
                parts = NpcGrpParts.empty();
                LogManager.getLogger(SpawnModels.class)
                        .warn("Could not read AdditionalNpcGrpParts.dat: {}", failure.toString());
            }
        }
        return loader != null;
    }

    private static SpawnModelData withKey(SpawnModelData data, String key) {
        return new SpawnModelData(
                key,
                data.vertices(),
                data.indices(),
                data.parts(),
                data.height(),
                data.lowest(),
                data.bones(),
                data.effectBone(),
                data.litSphere());
    }

    private record Read(NpcModel model, @Nullable NpcAnimation animation) {}

    private static @Nullable SpecularOverlay specularOverlayOf(EntityMaterial material) {
        var current = material;
        for (var depth = 0; depth < 4 && current instanceof SpecularEntityMaterial(var base, var overlay); depth++) {
            if (!overlay.selfIllumination()) {
                return overlay;
            }
            current = base;
        }
        return null;
    }

    private static Bitmap bitmapOf(Texture texture) {
        var mip = 0;
        while (mip + 1 < texture.data().size()
                && Math.max(texture.width() >> mip, texture.height() >> mip) > MAX_TEXTURE) {
            mip++;
        }
        return new Bitmap(
                Math.max(1, texture.width() >> mip),
                Math.max(1, texture.height() >> mip),
                TexturePixels.decodeMipRgba(texture, mip));
    }
}

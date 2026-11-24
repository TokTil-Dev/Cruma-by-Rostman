package com.rost.cruma.core.world;

import com.rost.cruma.core.env.MovableSun;
import com.rost.cruma.core.env.UnrealHsv;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.Bsp.BspPolygonFlags;
import com.rost.cruma.core.unreal.Bsp.FZoneProperties;
import com.rost.cruma.core.unreal.Bsp.UBspLeaf;
import com.rost.cruma.core.unreal.Bsp.UBspNode;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.LightActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UObject;
import com.rost.cruma.core.unreal.UObjectRef;
import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.UStaticMeshInstance.LightInfo;
import com.rost.cruma.core.unreal.primitives.UBox;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.BspLightPublication.Node;
import com.rost.cruma.core.world.BspLightPublication.Tree;
import com.rost.cruma.core.world.BspVisibilityVolumes.View;
import com.rost.cruma.core.world.NativeLightTemporal.Palette;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class NativeLightScene {
    public static final int NO_SOURCE = -1;
    public static final int UNAVAILABLE_SOURCE = -2;
    private static final NativeLightScene EMPTY = new NativeLightScene(
            List.of(),
            new int[0],
            new int[0],
            new int[0],
            new UBspNode[0],
            new UBspLeaf[0],
            List.of(),
            null,
            1f,
            false);

    private final List<Source> sources;
    private final int[] actorSources;
    private final String[] actorIds;
    private final int[] frameSources;
    private final int[] staticReferences;
    private final UBspNode[] nodes;
    private final UBspLeaf[] leaves;
    private final UBox[] renderBounds;
    private final int zoneCount;
    private final NativePortalScene portals;
    private @Nullable Tree lightTree;
    private final List<String> unresolvedStaticSources;
    private final @Nullable String levelInfoId;
    private final float levelBrightness;
    private final boolean beastEnvironment;
    private final ZoneLight[] zoneLights;
    private final ZoneLight levelZone;

    private NativeLightScene(
            List<Source> sources,
            int[] actorSources,
            int[] frameSources,
            int[] staticReferences,
            UBspNode[] nodes,
            UBspLeaf[] leaves,
            List<String> unresolvedStaticSources,
            @Nullable String levelInfoId,
            float levelBrightness,
            boolean beastEnvironment) {
        this(
                sources,
                actorSources,
                frameSources,
                staticReferences,
                nodes,
                leaves,
                unresolvedStaticSources,
                levelInfoId,
                levelBrightness,
                beastEnvironment,
                new UBox[0],
                0,
                NativePortalScene.getEmpty(),
                new String[actorSources.length],
                new ZoneLight[0],
                ZoneLight.DEFAULT);
    }

    private NativeLightScene(
            List<Source> sources,
            int[] actorSources,
            int[] frameSources,
            int[] staticReferences,
            UBspNode[] nodes,
            UBspLeaf[] leaves,
            List<String> unresolvedStaticSources,
            @Nullable String levelInfoId,
            float levelBrightness,
            boolean beastEnvironment,
            UBox[] renderBounds,
            int zoneCount,
            NativePortalScene portals,
            String[] actorIds,
            ZoneLight[] zoneLights,
            ZoneLight levelZone) {
        this.sources = List.copyOf(sources);
        this.actorSources = actorSources;
        this.actorIds = actorIds.clone();
        this.frameSources = frameSources;
        this.staticReferences = staticReferences;
        this.nodes = nodes;
        this.leaves = leaves;
        this.zoneCount = zoneCount;
        this.portals = portals;
        this.renderBounds = new UBox[renderBounds.length];
        for (var i = 0; i < renderBounds.length; i++) {
            var box = renderBounds[i];
            this.renderBounds[i] = new UBox(new Vec3(box.min()), new Vec3(box.max()), box.isValid());
        }
        this.unresolvedStaticSources = List.copyOf(unresolvedStaticSources);
        this.levelInfoId = levelInfoId;
        this.levelBrightness = levelBrightness;
        this.beastEnvironment = beastEnvironment;
        this.zoneLights = zoneLights.clone();
        this.levelZone = levelZone;
    }

    public record Source(
            String actorId,
            String actorClass,
            Light light,
            URotator rotation,
            float authoredBrightness,
            float authoredRadius,
            int hue,
            int saturation,
            boolean staticActor,
            boolean dynamicLight,
            boolean lightChanged,
            boolean deleteMe,
            boolean specialLit,
            boolean sunlightColor,
            boolean movableSun,
            Temporal temporal) {
        public Source(
                String actorId,
                String actorClass,
                Light light,
                URotator rotation,
                float authoredBrightness,
                float authoredRadius,
                int hue,
                int saturation,
                boolean staticActor,
                boolean dynamicLight,
                boolean lightChanged,
                boolean deleteMe,
                boolean specialLit,
                boolean sunlightColor,
                boolean movableSun) {
            this(
                    actorId,
                    actorClass,
                    light,
                    rotation,
                    authoredBrightness,
                    authoredRadius,
                    hue,
                    saturation,
                    staticActor,
                    dynamicLight,
                    lightChanged,
                    deleteMe,
                    specialLit,
                    sunlightColor,
                    movableSun,
                    new Temporal(0, 0, 0, null));
        }
    }

    public record Temporal(
            float lifeSpan,
            float lightOnTime,
            float lightOffTime,
            @Nullable Palette palette) {}

    public record ZoneLight(Vec3 ambient, boolean sunAffect) {
        public static final ZoneLight DEFAULT = new ZoneLight(new Vec3(), true);

        public static ZoneLight of(ZoneInfoActor zone) {
            var vector = zone.getAmbientVector();
            var ambient = vector != null
                    ? new Vec3(vector)
                    : UnrealHsv.toRgb(zone.getAmbientHue(), zone.getAmbientSaturation(), zone.getAmbientBrightness());
            return new ZoneLight(ambient, zone.isSunAffect());
        }
    }

    public static NativeLightScene getEmpty() {
        return EMPTY;
    }

    static NativeLightScene capture(
            @Nullable ULevel level, @Nullable UModel model, float fallbackLevelBrightness, MaterialClient client) {
        return capture(level, model, fallbackLevelBrightness, client, List.of());
    }

    static NativeLightScene capture(
            @Nullable ULevel level,
            @Nullable UModel model,
            float fallbackLevelBrightness,
            MaterialClient client,
            List<LightInfo> instanceSources) {
        var info = level == null ? null : level.getLevelInfo();
        var brightness = info == null ? fallbackLevelBrightness : info.getLevelBrightness();
        var collector = new Collector(client);
        var actors = level == null ? List.<UObjectRef<UObject, Optional>>of() : level.getActors();
        var actorSources = new int[actors.size()];
        var actorIds = new String[actors.size()];
        Arrays.fill(actorSources, NO_SOURCE);
        var frameSources = new int[actors.size()];
        var frameCount = 0;
        for (var slot = 0; slot < actors.size(); slot++) {
            actorIds[slot] = actors.get(slot).peekFullName();
            var source = collector.resolve(actors.get(slot), false);
            actorSources[slot] = source;
            if (slot >= 2
                    && source != NO_SOURCE
                    && !collector.sources.get(source).staticActor()) {
                frameSources[frameCount++] = source;
            }
        }
        var references = model == null || model.getLightActors() == null
                ? List.<UObjectRef<LightActor, Optional>>of()
                : model.getLightActors();
        var staticReferences = new int[references.size()];
        for (var slot = 0; slot < references.size(); slot++) {
            staticReferences[slot] = collector.resolve(references.get(slot), true);
        }
        for (var reference : instanceSources) {
            collector.resolveInstance(reference);
        }
        return new NativeLightScene(
                collector.sources,
                actorSources,
                Arrays.copyOf(frameSources, frameCount),
                staticReferences,
                model == null || model.getNodes() == null
                        ? new UBspNode[0]
                        : model.getNodes().clone(),
                model == null || model.getLeaves() == null
                        ? new UBspLeaf[0]
                        : model.getLeaves().clone(),
                collector.unresolvedStaticSources,
                info == null ? null : info.getFullName(),
                brightness,
                model != null && model.getBeastLightingFlag() != null && model.getBeastLightingFlag() != 0,
                model == null || model.getBounds() == null ? new UBox[0] : model.getBounds(),
                model == null || model.getZones() == null ? 0 : model.getZones().length,
                NativePortalScene.capture(level, model),
                actorIds,
                captureZones(model, info),
                info == null ? ZoneLight.DEFAULT : ZoneLight.of(info));
    }

    private static final class Collector {
        private final MaterialClient client;
        private final IdentityHashMap<LightActor, Integer> identities = new IdentityHashMap<>();
        private final List<Source> sources = new ArrayList<>();
        private final List<String> unresolvedStaticSources = new ArrayList<>();

        private Collector(MaterialClient client) {
            this.client = client;
        }

        private int resolve(UObjectRef<? extends UObject, ?> reference, boolean staticChannel) {
            if (reference.isNull()) {
                return NO_SOURCE;
            }
            var type = reference.peekClassName();
            if (!"Light".equals(type) && !"Spotlight".equals(type) && !"NMovableSunLight".equals(type)) {
                if (staticChannel) {
                    unresolvedStaticSources.add(
                            type == null ? "<unresolved>" : Objects.toString(reference.peekFullName(), type));
                }
                return staticChannel ? UNAVAILABLE_SOURCE : NO_SOURCE;
            }
            if (!(reference.get() instanceof LightActor actor)) {
                if (staticChannel) {
                    unresolvedStaticSources.add(Objects.toString(reference.peekFullName(), type));
                }
                return staticChannel ? UNAVAILABLE_SOURCE : NO_SOURCE;
            }
            return capture(actor, type);
        }

        private void resolveInstance(LightInfo reference) {
            if (reference.getLightIndex() == 0) {
                return;
            }
            var type = reference.getArchive().getObjectLoader().peekObjectClassName(reference.getLightIndex());
            LightActor actor;
            try {
                actor = reference.getLight();
            } catch (RuntimeException ignored) {
                actor = null;
            }
            if (actor == null) {
                unresolvedStaticSources.add(Objects.toString(reference.getLightName(), "<unresolved root light>"));
                return;
            }
            capture(actor, Objects.requireNonNull(type));
        }

        private int capture(LightActor actor, String type) {
            var existing = identities.get(actor);
            if (existing != null) {
                return existing;
            }
            var rotation = actor.getRotation();
            var light = new Light(
                    new Vec3(actor.getLocation()),
                    UnrealLoader.lightColor(
                            actor.getLightHue(), actor.getLightSaturation(), actor.getLightBrightness(), client),
                    NativeLightTemporal.worldRadius(actor.getLightType(), actor.getLightRadius()),
                    actor.getLightEffect(),
                    MovableSun.incidentDirection(rotation).times(-1f),
                    actor.getLightCone(),
                    actor.getLightType(),
                    actor.getLightPeriod(),
                    actor.getLightPhase());
            var index = sources.size();
            sources.add(new Source(
                    actor.getFullName(),
                    type,
                    light,
                    rotation,
                    actor.getLightBrightness(),
                    actor.getLightRadius(),
                    actor.getLightHue(),
                    actor.getLightSaturation(),
                    actor.isStatic(),
                    actor.isDynamicLight(),
                    actor.isLightChanged(),
                    actor.isDeleteMe(),
                    actor.isSpecialLit(),
                    actor.isSunlightColor(),
                    actor.isMovableSun(),
                    new Temporal(
                            actor.getLifeSpan(), actor.getLightOnTime(), actor.getLightOffTime(), readPalette(actor))));
            identities.put(actor, index);
            return index;
        }

        private static @Nullable Palette readPalette(LightActor actor) {
            if (actor.getLightType() != 8 && actor.getLightType() != 9) {
                return null;
            }
            if (!(actor.getSkin(0) instanceof UTexture texture)) {
                return null;
            }
            var palette = texture.getPalette().get();
            if (palette == null || palette.getColors() == null || palette.getColors().length < 256) {
                return null;
            }
            var colors = new int[256];
            for (var i = 0; i < colors.length; i++) {
                var color = palette.getColors()[i];
                colors[i] = Byte.toUnsignedInt(color.a()) << 24
                        | Byte.toUnsignedInt(color.r()) << 16
                        | Byte.toUnsignedInt(color.g()) << 8
                        | Byte.toUnsignedInt(color.b());
            }
            return new Palette(colors);
        }
    }

    public int getSourceCount() {
        return sources.size();
    }

    public Source getSource(int index) {
        return sources.get(index);
    }

    public int getActorSlotCount() {
        return actorSources.length;
    }

    public int getActorSource(int slot) {
        return actorSources[slot];
    }

    public @Nullable String getActorId(int slot) {
        return actorIds[slot];
    }

    public int getFrameSourceCount() {
        return frameSources.length;
    }

    public int frameSource(int index) {
        return frameSources[index];
    }

    public int getStaticReferenceCount() {
        return staticReferences.length;
    }

    public int getStaticSource(int slot) {
        return staticReferences[slot];
    }

    public int getNodeCount() {
        return nodes.length;
    }

    public UBspNode getNode(int index) {
        return nodes[index];
    }

    public int getLeafCount() {
        return leaves.length;
    }

    public UBspLeaf getLeaf(int index) {
        return leaves[index];
    }

    public record PointRegion(int zone, int leaf) {}

    public PointRegion pointRegion(Vec3 point) {
        if (nodes.length == 0) {
            return new PointRegion(0, -1);
        }
        var index = 0;
        var guard = 0;
        while (index >= 0 && index < nodes.length && guard++ < nodes.length) {
            var node = nodes[index];
            var plane = node.getPlane();
            var distance = (plane.getY() * point.getY() + point.getX() * plane.getX() + plane.getZ() * point.getZ())
                    - plane.getW();
            var side = distance >= 0f ? 1 : 0;
            var next = side == 1 ? node.getFrontIndex() : node.getBackIndex();
            if (next == -1) {
                return new PointRegion(zoneCount == 0 ? 0 : node.getZone()[side], node.getLeaf()[side]);
            }
            index = next;
        }
        return new PointRegion(-1, -1);
    }

    public int getRenderBoundCount() {
        return renderBounds.length;
    }

    public UBox renderBound(int index) {
        return renderBounds[index];
    }

    public int getZoneCount() {
        return zoneCount;
    }

    public long getZoneMask(Box box, int[] nodeStack) {
        return BspLeafSelection.zoneMask(nodes, leaves, box, nodeStack);
    }

    public int[] collectLeaves(Box box, int[] nodeStack) {
        return BspLeafSelection.collect(nodes, leaves, box, nodeStack);
    }

    public ZoneLight getZoneLight(int zone) {
        return zone >= 0 && zone < zoneLights.length ? zoneLights[zone] : levelZone;
    }

    public NativePortalScene getPortals() {
        return portals;
    }

    public void visitPortalSurfaces(View view, Vec3 origin, int nodeIndex) {
        if (!view.isZoned()) {
            return;
        }
        var current = portals.getZone(view.getViewZone());
        if (!current.ordinary()) {
            return;
        }
        var invisiblePortal = BspPolygonFlags.PF_Portal | BspPolygonFlags.PF_Invisible;
        var guard = 0;
        for (var n = nodeIndex; n >= 0 && n < nodes.length && guard++ < nodes.length; n = nodes[n].getPlaneIndex()) {
            var portal = portals.getPortal(n);
            if (portal == null || (portal.flags() & invisiblePortal) != invisiblePortal) {
                continue;
            }
            var surface = portal.surface();
            if (surface.backZone() < 0
                    || surface.backZone() >= 64
                    || surface.frontZone() < 0
                    || surface.frontZone() >= 64) {
                continue;
            }
            if (!portals.getZone(surface.backZone()).ordinary()
                    || !portals.getZone(surface.frontZone()).ordinary()) {
                continue;
            }
            NativePortalVisibility.visit(
                    view, origin, surface, zone -> portals.getZone(zone).visibility());
        }
    }

    public synchronized Tree getLightTree() {
        if (lightTree == null) {
            var lightNodes = new ArrayList<Node>(nodes.length);
            for (var node : nodes) {
                lightNodes.add(new Node(
                        new Vec4(node.getPlane()),
                        node.getZoneMask(),
                        node.getBackIndex(),
                        node.getFrontIndex(),
                        node.getLeaf()[0],
                        node.getLeaf()[1],
                        node.getRenderBound()));
            }
            lightTree = new Tree(lightNodes, leaves.length);
        }
        return lightTree;
    }

    public List<String> getUnresolvedStaticSources() {
        return unresolvedStaticSources;
    }

    public @Nullable String getLevelInfoId() {
        return levelInfoId;
    }

    public float getLevelBrightness() {
        return levelBrightness;
    }

    public boolean isBeastEnvironment() {
        return beastEnvironment;
    }

    private static ZoneLight[] captureZones(@Nullable UModel model, @Nullable ZoneInfoActor levelInfo) {
        var zones = model == null || model.getZones() == null ? new FZoneProperties[0] : model.getZones();
        var result = new ZoneLight[zones.length];
        for (var zone = 0; zone < zones.length; zone++) {
            var actor = zones[zone].getZoneActor().get();
            result[zone] = actor != null
                    ? ZoneLight.of(actor)
                    : levelInfo != null ? ZoneLight.of(levelInfo) : ZoneLight.DEFAULT;
        }
        return result;
    }
}

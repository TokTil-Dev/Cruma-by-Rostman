package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.env.LobbyTimeEnv;
import com.rost.cruma.core.env.TimeEnvParser;
import com.rost.cruma.core.env.WorldTimeEnv;
import com.rost.cruma.core.export.EffectStill;
import com.rost.cruma.core.export.EffectStill.Placement;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import com.rost.cruma.core.world.BeamEmitterDescriptor;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Clock;
import com.rost.cruma.core.world.MaterialClient;
import com.rost.cruma.core.world.MeshEmitterDescriptor;
import com.rost.cruma.core.world.NativeLightScene;
import com.rost.cruma.core.world.NativeModelLighting;
import com.rost.cruma.core.world.SpriteEmitterDescriptor;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.viewer.render.BeamRenderer;
import com.rost.cruma.viewer.render.MapEmitters;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.ParticleSystem;
import glm_.vec3.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.function.Function;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

public final class ParticleStill implements EffectStill {
    private static final float[] CORNERS = {-.5f, -.5f, .5f, -.5f, .5f, .5f, -.5f, .5f};
    private static final float[] CORNER_UVS = {0, 1, 1, 1, 1, 0, 0, 0};
    private static final int RIBBON_FLOATS = 9;
    private static final float STEP = 1f / 60f;

    private final MapEventState events;
    private final int hour;
    private final @Nullable Function<String, Vec3 @Nullable []> ambients;

    public ParticleStill() {
        this(MapEventState.ALL_SHOWN);
    }

    public ParticleStill(MapEventState events) {
        this(events, -1);
    }

    public ParticleStill(MapEventState events, int hour) {
        this(events, hour, null);
    }

    private ParticleStill(MapEventState events, int hour, @Nullable Function<String, Vec3 @Nullable []> ambients) {
        this.events = events;
        this.hour = hour;
        this.ambients = ambients;
    }

    static float @Nullable [] surfaceLights(
            ParticleEmitter emitter,
            int surfaces,
            NativeLightScene scene,
            Box box,
            @Nullable Vec3 actorAmbient,
            @Nullable Vec3 beastActorAmbient) {
        var lit = false;
        for (var surface = 0; surface < surfaces; surface++) {
            lit |= emitter.takesLight(surface);
        }
        if (!lit || scene.getNodeCount() == 0) {
            return null;
        }
        var factor = BgfxFxRenderer.meshLightFactor(NativeModelLighting.getActorAmbient(
                scene, box, new int[scene.getNodeCount()], actorAmbient, beastActorAmbient, 0.0));
        if (factor == null) {
            return null;
        }
        var lights = new float[surfaces * 3];
        for (var surface = 0; surface < surfaces; surface++) {
            for (var channel = 0; channel < 3; channel++) {
                lights[surface * 3 + channel] = emitter.takesLight(surface) ? factor[channel] : 1f;
            }
        }
        return lights;
    }

    static int meshBlend(boolean useMeshBlendMode, boolean blackBackground, int drawStyle) {
        if (useMeshBlendMode) {
            return -1;
        }
        if (blackBackground && drawStyle == 0) {
            return 1;
        }
        return switch (drawStyle) {
            case 3, 6 -> 1;
            case 2 -> 2;
            case 5 -> 4;
            case 1, 4 -> 0;
            default -> -1;
        };
    }

    public ParticleStill litBy(Path client) {
        if (hour < 0) {
            return this;
        }
        var read = new HashMap<String, Vec3 @Nullable []>();
        return new ParticleStill(
                events, hour, map -> read.computeIfAbsent(map, name -> readAmbients(client, name, hour)));
    }

    ParticleStill litBy(Vec3 actorAmbient, Vec3 beastActorAmbient) {
        var both = new Vec3[] {actorAmbient, beastActorAmbient};
        return new ParticleStill(events, hour, map -> both);
    }

    @Override
    public List<Effect> take(UMap map) {
        return takeLoop(map, 1, 0f).getFirst();
    }

    @Override
    public List<List<Effect>> takeLoop(UMap map, int count, float interval) {
        var system = new ParticleSystem();
        var sprites = new IdentityHashMap<ParticleEmitter, SpriteEmitterDescriptor>();
        var meshes = new IdentityHashMap<ParticleEmitter, MeshEmitterDescriptor>();
        var beams = new IdentityHashMap<ParticleEmitter, BeamEmitterDescriptor>();
        for (var descriptor : map.spriteEmitters()) {
            if (descriptor.visibility().isHiddenByGameEvents(events) || isHiddenByTime(descriptor.emitter())) {
                continue;
            }
            var emitter = MapEmitters.sprite(descriptor);
            system.add(emitter);
            sprites.put(emitter, descriptor);
        }
        for (var descriptor : map.meshEmitters()) {
            if (descriptor.visibility().isHiddenByGameEvents(events) || isHiddenByTime(descriptor.emitter())) {
                continue;
            }
            var emitter = MapEmitters.mesh(descriptor);
            system.add(emitter);
            meshes.put(emitter, descriptor);
        }
        for (var descriptor : map.beamEmitters()) {
            if (descriptor.visibility().isHiddenByGameEvents(events) || isHiddenByTime(descriptor.emitter())) {
                continue;
            }
            var emitter = MapEmitters.beam(descriptor);
            system.add(emitter);
            beams.put(emitter, descriptor);
        }
        if (system.isEmpty()) {
            return List.of(List.of());
        }
        system.prepareDeterministicSnapshot(map.position());
        var emitters = new ArrayList<>(system.getEmitters());
        emitters.sort(Comparator.comparing(ParticleEmitter::getRenderActorName)
                .thenComparingInt(ParticleEmitter::getRenderChildOrder));
        var lighting = ambients == null ? null : ambients.apply(map.name());
        var stills = new ArrayList<List<Effect>>(count);
        for (var still = 0; still < Math.max(1, count); still++) {
            if (still > 0) {
                var steps = Math.max(1, Math.round(interval / STEP));
                for (var step = 0; step < steps; step++) {
                    for (var emitter : emitters) {
                        emitter.update(interval / steps);
                    }
                }
            }
            stills.add(
                    capture(emitters, sprites, meshes, beams, lighting == null ? null : map.nativeLights(), lighting));
        }
        return List.copyOf(stills);
    }

    private boolean isHiddenByTime(UParticleEmitter emitter) {
        var windows = emitter.getWeatherRangeTime();
        if (hour < 0) {
            return !windows.always() && events.clock(windows.label()) == Clock.OFF;
        }
        return !events.showsDuring(windows, hour, hour + 1);
    }

    private static Vec3 @Nullable [] readAmbients(Path client, String map, int hour) {
        try {
            var kind = MaterialClient.detect(client);
            var lobby = LobbyTimeEnv.files(client, map);
            var files = lobby.isEmpty() ? WorldTimeEnv.files(client) : lobby;
            if (files.isEmpty()) {
                return null;
            }
            var actor = TimeEnvParser.parse(files.getFirst(), kind)
                    .resolve(hour, kind)
                    .actorAmbient();
            var beast = actor;
            var beastFile = client.resolve("system").resolve("beasttimeenv0.int");
            if (lobby.isEmpty() && Files.isRegularFile(beastFile)) {
                beast = TimeEnvParser.parse(beastFile, kind).resolve(hour, kind).actorAmbient();
            }
            return new Vec3[] {actor, beast};
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static List<Effect> capture(
            List<ParticleEmitter> emitters,
            Map<ParticleEmitter, SpriteEmitterDescriptor> sprites,
            Map<ParticleEmitter, MeshEmitterDescriptor> meshes,
            Map<ParticleEmitter, BeamEmitterDescriptor> beams,
            @Nullable NativeLightScene scene,
            Vec3 @Nullable [] ambients) {
        var effects = new ArrayList<Effect>();
        var boxes = new HashMap<String, Box>();
        if (scene != null) {
            for (var emitter : emitters) {
                emitter.addNativeSortBounds(boxes.computeIfAbsent(emitter.getRenderActorName(), ignored -> new Box()));
            }
        }
        for (var emitter : emitters) {
            emitter.setOpacityRatio(1f);
            var sprite = sprites.get(emitter);
            if (sprite != null) {
                addSprites(emitter, sprite, effects);
                continue;
            }
            var mesh = meshes.get(emitter);
            if (mesh != null) {
                float[] lights = null;
                if (scene != null && ambients != null) {
                    var box = boxes.get(emitter.getRenderActorName());
                    if (!box.isValid()) {
                        var place = emitter.getActorPos();
                        box = new Box();
                        box.add(place.getX() - .5f, place.getY() - .5f, place.getZ() - .5f);
                        box.add(place.getX() + .5f, place.getY() + .5f, place.getZ() + .5f);
                    }
                    lights =
                            surfaceLights(emitter, mesh.mesh().surfaces().size(), scene, box, ambients[0], ambients[1]);
                }
                addMeshCopies(emitter, mesh, effects, lights);
                continue;
            }
            var beam = beams.get(emitter);
            if (beam != null) {
                addBeams(emitter, beam, effects);
            }
        }
        return List.copyOf(effects);
    }

    private static void addSprites(ParticleEmitter emitter, SpriteEmitterDescriptor descriptor, List<Effect> effects) {
        var vertices = new Vertices();
        var spatial = new float[6];
        var mode = emitter.getDirectionUsage();
        var blend = emitter.getBlend();
        for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
            if (!emitter.isActive(index)) {
                continue;
            }
            float red = emitter.getR(index),
                    green = emitter.getG(index),
                    blue = emitter.getB(index),
                    alpha = emitter.getA(index);
            if (blend == 0 ? alpha <= 0f : blend != 2 && red <= 0f && green <= 0f && blue <= 0f) {
                continue;
            }
            emitter.writeSpriteSpatial(index, spatial);
            var width = emitter.getSpriteRenderScaleX(index) * ParticleEmitter.SPRITE_QUAD_SCALE;
            var height = emitter.getSpriteRenderScaleY(index) * ParticleEmitter.SPRITE_QUAD_SCALE;
            var angle = emitter.getPitch(index);
            var cosine = (float) Math.cos(angle);
            var sine = (float) Math.sin(angle);
            var direction = new Vec3(spatial[3], spatial[4], spatial[5]);
            var hasDirection = direction.getX() * direction.getX()
                            + direction.getY() * direction.getY()
                            + direction.getZ() * direction.getZ()
                    > 1e-8f;
            Vec3 planeRight = null;
            Vec3 planeUp = null;
            if (mode == 4 && hasDirection) {
                var plane = emitter.getCardPlane();
                planeRight = plane.right();
                planeUp = plane.up();
                width = ParticleEmitter.getCardWidth(width, height);
                height *= plane.rise();
            }
            var kind = planeRight != null ? 2 : mode == 1 && hasDirection ? 1 : 0;
            var first = vertices.getCount();
            for (var corner = 0; corner < 4; corner++) {
                var x = CORNERS[corner * 2] * width;
                var y = CORNERS[corner * 2 + 1] * height;
                var across = x * cosine - y * sine;
                var up = x * sine + y * cosine;
                var u = emitter.getSubU0(index) + CORNER_UVS[corner * 2] * emitter.getSubUScale();
                var v = emitter.getSubV0(index) + CORNER_UVS[corner * 2 + 1] * emitter.getSubVScale();
                if (planeRight != null) {
                    vertices.add(
                            spatial[0] + across * planeRight.getX() + up * planeUp.getX(),
                            spatial[1] + across * planeRight.getY() + up * planeUp.getY(),
                            spatial[2] + across * planeRight.getZ() + up * planeUp.getZ(),
                            0,
                            0,
                            2,
                            0,
                            0,
                            0,
                            u,
                            v,
                            red,
                            green,
                            blue,
                            alpha);
                } else {
                    vertices.add(
                            spatial[0],
                            spatial[1],
                            spatial[2],
                            across,
                            up,
                            kind,
                            spatial[3],
                            spatial[4],
                            spatial[5],
                            u,
                            v,
                            red,
                            green,
                            blue,
                            alpha);
                }
            }
            vertices.quad(first, first + 1, first + 2, first + 3);
        }
        if (vertices.getCount() > 0) {
            effects.add(vertices.getCards(
                    descriptor.actorName() + " " + descriptor.name(),
                    descriptor.actorName(),
                    descriptor.childOrder(),
                    new Placement(descriptor.actorPos(), descriptor.rotation(), descriptor.scale()),
                    descriptor.sprite(),
                    descriptor.textureName(),
                    blend));
        }
    }

    private static void addMeshCopies(
            ParticleEmitter emitter,
            MeshEmitterDescriptor descriptor,
            List<Effect> effects,
            float @Nullable [] lights) {
        var position = new float[3];
        var buffer = ByteBuffer.allocate(16 * Float.BYTES).order(ByteOrder.nativeOrder());
        var actorRotation = emitter.doesMeshUseActorRenderRotation() ? emitter.getActorRotation() : null;
        var byFrame = new TreeMap<Integer, List<float[]>>();
        var tints = new TreeMap<Integer, FloatArrayList>();
        var tint = BgfxFxRenderer.meshColorVariant(emitter, 0);
        var doubled = lights != null && emitter.isLitByActor();
        for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
            if (!emitter.isActive(index)) {
                continue;
            }
            emitter.writeMeshWorldPos(index, position);
            buffer.clear();
            BgfxFxRenderer.putParticleMatrix(
                    buffer,
                    position[0],
                    position[1],
                    position[2],
                    actorRotation,
                    -emitter.getRoll(index),
                    -emitter.getYaw(index),
                    emitter.getPitch(index),
                    emitter.getScaleX(index),
                    emitter.getScaleY(index),
                    emitter.getScaleZ(index),
                    emitter.getMeshPrePivot());
            var matrix = new float[16];
            buffer.flip();
            buffer.asFloatBuffer().get(matrix);
            var frame = descriptor.isVertexAnimated()
                    ? Math.clamp(
                            emitter.getMeshAnimationFrame(index),
                            0,
                            descriptor.animationFrames().size() - 1)
                    : -1;
            byFrame.computeIfAbsent(frame, ignored -> new ArrayList<>()).add(matrix);
            tints.computeIfAbsent(frame, ignored -> new FloatArrayList())
                    .addAll(
                            BgfxFxRenderer.meshInstanceColor(emitter.getR(index), tint, doubled),
                            BgfxFxRenderer.meshInstanceColor(emitter.getG(index), tint, doubled),
                            BgfxFxRenderer.meshInstanceColor(emitter.getB(index), tint, doubled),
                            BgfxFxRenderer.meshInstanceAlpha(emitter.getA(index), tint));
        }
        var name = descriptor.actorName() + " " + descriptor.name();
        var blend = meshBlend(emitter.isUseMeshBlendMode(), emitter.isBlackBackground(), emitter.getBlend());
        for (Entry<Integer, List<float[]>> entry : byFrame.entrySet()) {
            var mesh = entry.getKey() < 0
                    ? descriptor.mesh()
                    : descriptor.animationFrames().get(entry.getKey());
            effects.add(new MeshCopies(
                    name,
                    descriptor.actorName(),
                    descriptor.childOrder(),
                    new Placement(descriptor.actorPos(), descriptor.rotation(), descriptor.scale()),
                    mesh,
                    List.copyOf(entry.getValue()),
                    tints.get(entry.getKey()).toArray(),
                    blend,
                    lights));
        }
    }

    private static void addBeams(ParticleEmitter emitter, BeamEmitterDescriptor descriptor, List<Effect> effects) {
        var vertices = new Vertices();
        var scratch = new float[BeamRenderer.SPINE_SCRATCH_FLOATS];
        var camera = new Vec3(
                descriptor.actorPos().getX(),
                descriptor.actorPos().getY() - 100000f,
                descriptor.actorPos().getZ() + 30000f);
        for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
            if (!emitter.isActive(index)) {
                continue;
            }
            var ribbon = BeamRenderer.buildRibbon(emitter, index, camera, scratch);
            if (ribbon == null || ribbon.length < 6 * RIBBON_FLOATS) {
                continue;
            }
            var segments = ribbon.length / (6 * RIBBON_FLOATS);
            var lefts = new int[segments + 1];
            var rights = new int[segments + 1];
            for (var point = 0; point <= segments; point++) {
                var segment = point == 0 ? 0 : point - 1;
                lefts[point] = (segment * 6 + (point == 0 ? 0 : 5)) * RIBBON_FLOATS;
                rights[point] = (segment * 6 + (point == 0 ? 1 : 2)) * RIBBON_FLOATS;
            }
            var spine = new float[(segments + 1) * 3];
            for (var point = 0; point <= segments; point++) {
                for (var axis = 0; axis < 3; axis++) {
                    spine[point * 3 + axis] = (ribbon[lefts[point] + axis] + ribbon[rights[point] + axis]) / 2;
                }
            }
            var first = vertices.getCount();
            for (var point = 0; point <= segments; point++) {
                var before = Math.max(point - 1, 0);
                var after = Math.min(point + 1, segments);
                var tangent = new Vec3(
                        spine[after * 3] - spine[before * 3],
                        spine[after * 3 + 1] - spine[before * 3 + 1],
                        spine[after * 3 + 2] - spine[before * 3 + 2]);
                var length = tangent.length();
                if (length > 1e-6f) {
                    tangent = tangent.div(length);
                }
                float halfX = (ribbon[rights[point]] - ribbon[lefts[point]]) / 2;
                float halfY = (ribbon[rights[point] + 1] - ribbon[lefts[point] + 1]) / 2;
                float halfZ = (ribbon[rights[point] + 2] - ribbon[lefts[point] + 2]) / 2;
                var half = (float) Math.sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ);
                for (var side = 0; side < 2; side++) {
                    var source = side == 0 ? lefts[point] : rights[point];
                    vertices.add(
                            spine[point * 3],
                            spine[point * 3 + 1],
                            spine[point * 3 + 2],
                            side == 0 ? -half : half,
                            0,
                            3,
                            tangent.getX(),
                            tangent.getY(),
                            tangent.getZ(),
                            ribbon[source + 3],
                            ribbon[source + 4],
                            ribbon[source + 5],
                            ribbon[source + 6],
                            ribbon[source + 7],
                            ribbon[source + 8]);
                }
            }
            for (var segment = 0; segment < segments; segment++) {
                var left = first + segment * 2;
                vertices.quad(left, left + 1, left + 3, left + 2);
            }
        }
        if (vertices.getCount() > 0) {
            effects.add(vertices.getCards(
                    descriptor.actorName() + " " + descriptor.name(),
                    descriptor.actorName(),
                    descriptor.childOrder(),
                    new Placement(descriptor.actorPos(), descriptor.rotation(), descriptor.scale()),
                    descriptor.texture(),
                    descriptor.textureName(),
                    emitter.getBlend()));
        }
    }

    private static final class Vertices {
        private final FloatArrayList centers = new FloatArrayList();
        private final FloatArrayList shapes = new FloatArrayList();
        private final FloatArrayList axes = new FloatArrayList();
        private final FloatArrayList uvs = new FloatArrayList();
        private final FloatArrayList colors = new FloatArrayList();
        private final IntArrayList indices = new IntArrayList();

        int getCount() {
            return centers.size() / 3;
        }

        void add(
                float x,
                float y,
                float z,
                float a,
                float b,
                float kind,
                float axisX,
                float axisY,
                float axisZ,
                float u,
                float v,
                float red,
                float green,
                float blue,
                float alpha) {
            centers.addAll(x, y, z);
            shapes.addAll(a, b, kind);
            axes.addAll(axisX, axisY, axisZ);
            uvs.addAll(u, v);
            colors.addAll(red, green, blue, alpha);
        }

        void quad(int first, int second, int third, int fourth) {
            indices.addAll(first, second, third, first, third, fourth);
        }

        Cards getCards(
                String name,
                String actor,
                int order,
                Placement placement,
                Texture texture,
                String textureName,
                int blend) {
            return new Cards(
                    name,
                    actor,
                    order,
                    placement,
                    texture,
                    textureName,
                    blend,
                    centers.toArray(),
                    shapes.toArray(),
                    axes.toArray(),
                    uvs.toArray(),
                    colors.toArray(),
                    indices.toArray());
        }
    }
}

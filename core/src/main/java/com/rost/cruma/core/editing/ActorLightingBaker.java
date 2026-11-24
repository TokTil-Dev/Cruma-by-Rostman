package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingScene.Bake;
import com.rost.cruma.core.editing.ActorLightingScene.Light;
import com.rost.cruma.core.editing.ActorLightingScene.Mesh;
import com.rost.cruma.core.editing.ActorLightingScene.Receiver;
import com.rost.cruma.core.editing.ActorLightingScene.Settings;
import com.rost.cruma.core.editing.ActorLightingTrace.Query;
import com.rost.cruma.core.unreal.UArchive;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class ActorLightingBaker {
    public static final int SUNLIGHT = 19;

    public static final float STATIC_MESH_LIGHT_SCALE = 0.5f;

    private static final int SELF_HIT_RETRIES = 64;

    private ActorLightingBaker() {}

    public enum Status {
        BAKED,
        NO_INSTANCE,
        HIDDEN
    }

    public record Entry(int light, byte[] bits) {
        public Entry {
            Objects.requireNonNull(bits);
        }

        public boolean isVisible(int vertex) {
            return (bits[vertex >>> 3] & (1 << (vertex & 7))) != 0;
        }
    }

    public record Instance(int vertexCount, List<Entry> sceneLights, List<Entry> environmentLights, boolean shared) {
        public Instance {
            sceneLights = List.copyOf(sceneLights);
            environmentLights = List.copyOf(environmentLights);
        }
    }

    public record Result(Status status, @Nullable Instance[] lods, long rays) {
        public Result {
            if (status == Status.BAKED && (lods.length != 3 || lods[0] == null)) {
                throw new IllegalArgumentException("A built result needs a LOD0 instance");
            }
        }

        public @Nullable Instance lod(int index) {
            return lods[index];
        }
    }

    public record Lights(List<Integer> scene, List<Integer> environment) {}

    public static Result bake(ActorLightingScene scene, Receiver receiver, Query query, BooleanSupplier cancelled) {
        if (receiver.hidden()) {
            return new Result(Status.HIDDEN, new Instance[3], 0);
        }
        if (!receiver.eligible()) {
            return new Result(Status.NO_INSTANCE, new Instance[3], 0);
        }
        var lights = collectLights(scene, receiver);
        var before = query.getTraces();
        var lods = receiver.lods();
        var instances = new Instance[3];
        var scenes = new ArrayList<List<Entry>>();
        var environments = new ArrayList<List<Entry>>();
        for (var lod = 0; lod < 3; lod++) {
            scenes.add(new ArrayList<>());
            environments.add(new ArrayList<>());
        }
        var hasInstance = new boolean[3];
        var shared = false;
        hasInstance[0] = true;
        hasInstance[1] = lods[1] != null;
        if (lods[2] != null) {
            hasInstance[2] = true;
            shared = lods[1] != null && lods[2] == lods[1];
        }
        var settings = scene.getSettings();
        var position = new float[3];
        var normal = new float[3];
        for (var lightIndex : lights.scene()) {
            var light = scene.getLights().get(lightIndex);
            var directional = light.effect() == SUNLIGHT;
            Mesh previous = null;
            for (var lod = 0; lod < 3; lod++) {
                var mesh = lods[lod];
                if (mesh == null || !hasInstance[lod] || lod == 2 && mesh == previous) {
                    continue;
                }
                previous = mesh;
                checkCancelled(cancelled);
                var bits = new byte[(mesh.vertexCount() + 7) >>> 3];
                for (var vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                    transform(receiver.localToWorld(), mesh, vertex, position, normal);
                    if (directional) {
                        if (isSceneSunVisible(settings, receiver, query, light, position, normal)) {
                            bits[vertex >>> 3] |= (byte) (1 << (vertex & 7));
                        }
                        continue;
                    }
                    var toLightX = light.x() - position[0];
                    var toLightY = light.y() - position[1];
                    var toLightZ = light.z() - position[2];
                    var facing = toLightX * normal[0] + toLightY * normal[1] + toLightZ * normal[2];
                    if (!(facing >= 0)) {
                        continue;
                    }
                    var visible = true;
                    if (receiver.staticLighting()) {
                        var length = toLightX * toLightX + toLightY * toLightY + toLightZ * toLightZ;
                        var dx = 0f;
                        var dy = 0f;
                        var dz = 0f;
                        if ((double) length >= 1.0e-8) {
                            var scale = 1f / (float) Math.sqrt(length);
                            dx = toLightX * scale;
                            dy = toLightY * scale;
                            dz = toLightZ * scale;
                        }
                        var offset = settings.traceOffset();
                        visible = query.clear(
                                light.x(),
                                light.y(),
                                light.z(),
                                offset * dx + position[0],
                                dy * offset + position[1],
                                dz * offset + position[2],
                                false);
                    }
                    if (visible) {
                        bits[vertex >>> 3] |= (byte) (1 << (vertex & 7));
                    }
                }
                scenes.get(lod).add(new Entry(lightIndex, bits));
            }
        }
        if (!lights.environment().isEmpty()) {
            var sun = lights.environment().getFirst();
            for (var slot = 0; slot < settings.environmentSlots(); slot++) {
                var direction = sunDirection(settings, slot);
                Mesh previous = null;
                for (var lod = 0; lod < 3; lod++) {
                    var mesh = lods[lod];
                    if (mesh == null || !hasInstance[lod] || lod == 2 && mesh == previous) {
                        continue;
                    }
                    previous = mesh;
                    checkCancelled(cancelled);
                    var bits = new byte[(mesh.vertexCount() + 7) >>> 3];
                    for (var vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                        transform(receiver.localToWorld(), mesh, vertex, position, normal);
                        if (!(direction[0] * normal[0] + direction[1] * normal[1] + direction[2] * normal[2] <= 0)) {
                            continue;
                        }
                        if (!receiver.staticLighting()
                                || isSunVisible(settings, receiver, query, lod, direction, position)) {
                            bits[vertex >>> 3] |= (byte) (1 << (vertex & 7));
                        }
                    }
                    environments.get(lod).add(new Entry(sun, bits));
                }
            }
        }
        for (var lod = 0; lod < 3; lod++) {
            if (!hasInstance[lod]) {
                continue;
            }
            if (lod == 2 && shared) {
                instances[2] = new Instance(lods[2].vertexCount(), scenes.get(1), environments.get(1), true);
            } else {
                instances[lod] = new Instance(lods[lod].vertexCount(), scenes.get(lod), environments.get(lod), false);
            }
        }
        return new Result(Status.BAKED, instances, query.getTraces() - before);
    }

    public static Lights collectLights(ActorLightingScene scene, Receiver receiver) {
        var sceneLights = new ArrayList<Integer>();
        var environmentLights = new ArrayList<Integer>();
        var sphere = receiver.sphere();
        var early = scene.getBake() == Bake.EARLY;
        for (var leaf : receiver.leaves()) {
            if (leaf < 0 || leaf >= scene.getLeafCount()) {
                continue;
            }
            var zone = scene.getLeafZone(leaf);
            var slot = scene.getLeafLights(leaf);
            if (slot < 0) {
                continue;
            }
            for (; slot < scene.getLightSlotCount(); slot++) {
                var index = scene.getLightSlot(slot);
                if (index == ActorLightingScene.LIST_END) {
                    break;
                }
                if (index == ActorLightingScene.UNRESOLVED) {
                    continue;
                }
                var light = scene.getLights().get(index);
                if (light.hidden()
                        || light.type() == 0
                        || light.specialLit() != receiver.specialLit()
                        || light.excluded()) {
                    continue;
                }
                if (sceneLights.contains(index) || environmentLights.contains(index)) {
                    continue;
                }
                if (light.effect() == SUNLIGHT) {
                    if (early) {
                        sceneLights.add(index);
                        continue;
                    }
                    if (!light.skyZone() && light.zone() != zone) {
                        continue;
                    }
                    var zoneActor = scene.getZone(zone);
                    if (hasName(receiver.forcedRegionTag())
                            && !isSameName(receiver.forcedRegionTag(), zoneActor.tag())) {
                        continue;
                    }
                    if (receiver.forcedRegion() != 0 && receiver.forcedRegion() != zone) {
                        continue;
                    }
                    if (zoneActor.sunAffect()) {
                        environmentLights.add(index);
                    }
                    continue;
                }
                var dx = light.x() - sphere[0];
                var dy = light.y() - sphere[1];
                var dz = light.z() - sphere[2];
                var distance = (dx * dx + dy * dy) + dz * dz;
                var reach = light.radius() + sphere[3];
                if (reach * reach > distance) {
                    sceneLights.add(index);
                }
            }
        }
        return new Lights(sceneLights, environmentLights);
    }

    public static float[] sunDirection(Settings settings, int slot) {
        var count = settings.environmentSlots();
        var time = ((float) slot * 24f) / (float) count + (24f / (float) count) * .5f;
        var direction = NativeTerrainTimeLighting.safeDirection(
                time, settings.sunLongitude(), settings.sunSlope(), settings.reverseSlope());
        return new float[] {direction.x(), direction.y(), direction.z()};
    }

    public static byte[] sceneColors(ActorLightingScene scene, Receiver receiver, Mesh mesh, Instance instance) {
        var count = mesh.vertexCount();
        var colors = new int[count * 4];
        if (!scene.isBeastLevel()) {
            var position = new float[3];
            var normal = new float[3];
            var scale = receiver.scaleGlow() * STATIC_MESH_LIGHT_SCALE;
            for (var entry : instance.sceneLights()) {
                var light = scene.getLights().get(entry.light());
                var red = light.red() * scale;
                var green = light.green() * scale;
                var blue = light.blue() * scale;
                for (var vertex = 0; vertex < count; vertex++) {
                    if (!entry.isVisible(vertex)) {
                        continue;
                    }
                    transform(receiver.localToWorld(), mesh, vertex, position, normal);
                    var intensity = sampleIntensity(light, position, normal);
                    var target = vertex * 4;
                    colors[target] = Math.min(255, colors[target] + colorByte(red * intensity));
                    colors[target + 1] = Math.min(255, colors[target + 1] + colorByte(green * intensity));
                    colors[target + 2] = Math.min(255, colors[target + 2] + colorByte(blue * intensity));
                }
            }
        }
        var asset = mesh.assetColors();
        var result = new byte[count * 4];
        for (var vertex = 0; vertex < count; vertex++) {
            var r = colors[vertex * 4];
            var g = colors[vertex * 4 + 1];
            var b = colors[vertex * 4 + 2];
            if (asset != null && mesh.useVertexColor()) {
                r = (Byte.toUnsignedInt(asset[vertex * 4]) * r) / 255;
                g = (Byte.toUnsignedInt(asset[vertex * 4 + 1]) * g) / 255;
                b = (Byte.toUnsignedInt(asset[vertex * 4 + 2]) * b) / 255;
            }
            result[vertex * 4] = (byte) r;
            result[vertex * 4 + 1] = (byte) g;
            result[vertex * 4 + 2] = (byte) b;
            result[vertex * 4 + 3] = asset == null ? 0 : asset[vertex * 4 + 3];
        }
        return result;
    }

    public static float sampleIntensity(Light light, float[] point, float[] normal) {
        var dx = light.x() - point[0];
        var dy = light.y() - point[1];
        var dz = light.z() - point[2];
        if (light.effect() == SUNLIGHT) {
            var dot = light.directionX() * normal[0] + light.directionY() * normal[1] + light.directionZ() * normal[2];
            return Math.max(-dot, 0f) * 2f;
        }
        var squared = dx * dx + dy * dy + dz * dz;
        var distance = (float) Math.sqrt(squared);
        var radius = light.radius();
        var incidence = normal[0] * dx + normal[1] * dy + normal[2] * dz;
        if (light.effect() == 17) {
            return distance < radius ? Math.max(0f, 1f - (dx * dx + dy * dy) / (radius * radius)) * 2f : 0f;
        }
        if (!(incidence > 0) || radius <= 0 || distance > radius) {
            return 0f;
        }
        if (light.effect() == 13) {
            return distance < radius ? (float) Math.sqrt(1.02 - distance / radius) * 2f : 0f;
        }
        if (light.effect() == 20) {
            return squared < radius * radius ? (float) ((1.02 - squared / (radius * radius)) * 2.0) : 0f;
        }
        var a = distance / radius;
        var base = Math.abs(incidence / radius) * ((a * 2f * a * a - a * 3f * a + 1f) / a) * 2f;
        if (!(base > 0) || light.effect() != 8 && light.effect() != 12) {
            return Math.max(0f, base);
        }
        var sine = 1f - light.cone() / 256f;
        var reciprocal = 1f / (1f - sine);
        var directionDot = -(dx * light.directionX() + dy * light.directionY() + dz * light.directionZ());
        if (!(directionDot > 0) || !(directionDot * directionDot > sine * sine * squared)) {
            return 0f;
        }
        var cone = directionDot * reciprocal / distance - reciprocal * sine;
        return cone * cone * base;
    }

    static void transform(float[] m, Mesh mesh, int vertex, float[] position, float[] normal) {
        var p = mesh.positions();
        var n = mesh.normals();
        var i = vertex * 3;
        var px = p[i];
        var py = p[i + 1];
        var pz = p[i + 2];
        position[0] = px * m[0] + py * m[4] + pz * m[8] + m[12];
        position[1] = px * m[1] + py * m[5] + pz * m[9] + m[13];
        position[2] = py * m[6] + px * m[2] + pz * m[10] + m[14];
        var nx = n[i] * m[0] + n[i + 1] * m[4] + n[i + 2] * m[8] + m[12] * 0f;
        var ny = n[i] * m[1] + n[i + 1] * m[5] + n[i + 2] * m[9] + m[13] * 0f;
        var nz = n[i] * m[2] + n[i + 1] * m[6] + n[i + 2] * m[10] + m[14] * 0f;
        var squared = (nx * nx + ny * ny) + nz * nz;
        if ((double) squared < 1.0e-8) {
            normal[0] = 0f;
            normal[1] = 0f;
            normal[2] = 0f;
            return;
        }
        var scale = 1f / (float) Math.sqrt(squared);
        normal[0] = nx * scale;
        normal[1] = ny * scale;
        normal[2] = nz * scale;
    }

    static int colorByte(float value) {
        if (!(value > 0)) {
            return 0;
        }
        return (int) Math.min(255, Math.floor(value * 255f));
    }

    private static boolean isSceneSunVisible(
            Settings settings, Receiver receiver, Query query, Light light, float[] position, float[] normal) {
        var dx = light.directionX();
        var dy = light.directionY();
        var dz = light.directionZ();
        if (!(dx * normal[0] + dy * normal[1] + dz * normal[2] <= 0)) {
            return false;
        }
        if (!receiver.staticLighting()) {
            return true;
        }
        var offset = settings.traceOffset();
        return query.clear(
                position[0] - dx * offset,
                position[1] - dy * offset,
                position[2] - dz * offset,
                position[0] - dx * 262144f * 1.375f,
                position[1] - dy * 262144f * 1.375f,
                position[2] - dz * 262144f * 1.375f,
                false);
    }

    private static boolean isSunVisible(
            Settings settings, Receiver receiver, Query query, int lod, float[] direction, float[] position) {
        var offset = settings.traceOffset();
        var farX = position[0] - direction[0] * 262144f * 1.375f;
        var farY = position[1] - direction[1] * 262144f * 1.375f;
        var farZ = position[2] - direction[2] * 262144f * 1.375f;
        var startX = position[0] - offset * direction[0];
        var startY = position[1] - offset * direction[1];
        var startZ = position[2] - offset * direction[2];
        if (lod == 0 || receiver.caster() < 0) {
            return query.clear(startX, startY, startZ, farX, farY, farZ, true);
        }
        var blocked = query.findFirstHit(startX, startY, startZ, farX, farY, farZ, true);
        var retries = 0;
        while (lod > 0
                && blocked
                && receiver.caster() >= 0
                && query.hitCaster == receiver.caster()
                && ++retries < SELF_HIT_RETRIES) {
            blocked = query.findFirstHit(
                    query.hitLocationX - offset * direction[0],
                    query.hitLocationY - offset * direction[1],
                    query.hitLocationZ - offset * direction[2],
                    farX,
                    farY,
                    farZ,
                    true);
        }
        return !blocked;
    }

    private static boolean hasName(String name) {
        return !name.isEmpty() && !isSameName(UArchive.NONE_NAME, name);
    }

    private static boolean isSameName(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        for (var i = 0; i < left.length(); i++) {
            var a = left.charAt(i);
            var b = right.charAt(i);
            if (a >= 'A' && a <= 'Z') {
                a += 'a' - 'A';
            }
            if (b >= 'A' && b <= 'Z') {
                b += 'a' - 'A';
            }
            if (a != b) {
                return false;
            }
        }
        return true;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Lighting build cancelled");
        }
    }
}

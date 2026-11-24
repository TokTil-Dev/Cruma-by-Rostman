package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeClassDefaults.Property;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Owner;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Slot;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Source;
import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class P152TerrainLightingInputs {
    private static final String ENV_HASH = "c024f6cc5204871133a5335e456a194fb15fa3e9f28a345c8f574d4490cb2c26";
    private static final int MAX_ENV_BYTES = 4 * 1024 * 1024;
    private final P152FreshBakeContext context;
    private final P152EffectiveInputs.Source environment;
    private final Terrain terrain;
    private final Owner owner;
    private final boolean inverted;
    private final BitSet visible, turns;
    private final List<Source> lights;
    private final List<PointLight> pointLights;
    private final List<Slot> slots;
    private final List<String> steadyCandidates;

    private P152TerrainLightingInputs(
            P152FreshBakeContext context,
            P152EffectiveInputs.Source environment,
            Terrain terrain,
            Owner owner,
            boolean inverted,
            BitSet visible,
            BitSet turns,
            List<Source> lights,
            List<Slot> slots,
            List<String> steadyCandidates,
            List<PointLight> pointLights) {
        this.context = context;
        this.environment = environment;
        this.terrain = terrain;
        this.owner = owner;
        this.inverted = inverted;
        this.visible = (BitSet) visible.clone();
        this.turns = (BitSet) turns.clone();
        this.lights = List.copyOf(lights);
        this.pointLights = List.copyOf(pointLights);
        this.slots = List.copyOf(slots);
        this.steadyCandidates = List.copyOf(steadyCandidates);
    }

    record PointLight(int reference, Vector position, float authoredRadius) {}

    static P152TerrainLightingInputs capture(
            P152FreshBakeContext context, P152FreshBspModel model, TerrainPackageSnapshot snapshot, String ownerPath)
            throws IOException {
        return capture(context, model, snapshot, ownerPath, () -> false);
    }

    static P152TerrainLightingInputs capture(
            P152FreshBakeContext context,
            P152FreshBspModel model,
            TerrainPackageSnapshot snapshot,
            String ownerPath,
            BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!context.getMapSha256().equals(snapshot.getOriginalSha256())
                || !context.getMapSha256().equals(model.getMapSha256())) {
            throw new IOException("Terrain light inputs do not share the frozen map");
        }
        var source = new PackageBytes(context.getMapPath(), context.getMapBytes());
        P152FreshHashQuery.checkCancelled(cancelled);
        var terrain = snapshot.getTerrains().stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown Terrain light owner"));
        P152ActorDomain.requireEngineClass(source, source.exports.get(terrain.exportIndex() - 1), "TerrainInfo");
        if (terrain.info().width() != 256 || terrain.info().height() != 256) {
            throw new IOException("Terrain light inputs require a 256 by 256 owner");
        }
        var properties = readPropertyMap(source, ownerPath, "TerrainInfo");
        var ownerLocation = readLocation(properties);
        var ownerZone = model.getRegions().pointRegion(ownerLocation).zone();
        var effective = context.getMapInputs();
        var facts = effective.getTerrain(ownerPath);
        var owner = new Owner(facts.specialLit().value() ? 0x20 : 0, ownerZone);
        if (!facts.staticLighting().value()) {
            throw new IOException("Terrain light inputs require native static lighting");
        }
        var skyZones = skyZones(source, model.getZoneActorReferences());
        var lights = new ArrayList<Source>();
        var points = new ArrayList<PointLight>();
        var steady = new ArrayList<String>();
        for (var actor : context.getActorDomain().getReport().actors()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var path = actor.identity().objectPath();
            var kind = effective.getLightKind(path);
            switch (kind) {
                case NONE -> {}
                case LIGHT -> {
                    var input = effective.getLight(path);
                    var position = readLocation(readPropertyMap(source, path, "Light"));
                    var zone = model.getRegions().pointRegion(position).zone();
                    var flags = input.admissionFlags();
                    points.add(new PointLight(
                            actor.identity().reference(),
                            position,
                            input.radius().value()));
                    lights.add(new Source(
                            actor.identity().reference(),
                            input.type().value(),
                            flags,
                            zone,
                            false,
                            input.effect().value()));
                    if (isAdmittedBySteadyLighting(
                            owner,
                            input.type().value(),
                            flags,
                            zone,
                            skyZones.get(zone),
                            false,
                            input.effect().value())) {
                        steady.add(path);
                    }
                }
                case MOVABLE_SUN -> {
                    var input = effective.getSun(path);
                    var position = readLocation(readPropertyMap(source, path, "NMovableSunLight"));
                    var zone = model.getRegions().pointRegion(position).zone();
                    lights.add(new Source(
                            actor.identity().reference(),
                            input.type().value(),
                            input.specialLit().value() ? 0x20 : 0,
                            zone,
                            true,
                            input.effect().value()));
                }
            }
        }
        var time = snapshot.findAuthoredTimeMaps(ownerPath)
                .orElseThrow(() -> new IOException("Terrain has no authored native sunlight maps"));
        if (time.getFrameCount() != 8
                || readInteger(properties, "NumIntMap") != 8
                || !readBool(properties, "bAutoTimeGeneration")) {
            throw new IOException("Only the authenticated eight-slot automatic sunlight profile is supported");
        }
        var envPath = context.getClientRoot()
                .resolve("System/env.int")
                .toAbsolutePath()
                .normalize();
        var envReal = envPath.toRealPath();
        if (!envReal.startsWith(context.getClientRoot().toRealPath()) || Files.size(envReal) > MAX_ENV_BYTES) {
            throw new IOException("Sunlight environment is outside the bounded client root");
        }
        var envBytes = P152CaptureIo.read(envReal, MAX_ENV_BYTES, cancelled);
        if (!P152CaptureIo.hash(envBytes, cancelled).equals(ENV_HASH)) {
            throw new IOException("Sunlight environment fingerprint is not qualified");
        }
        requireEnvironment(envBytes);
        var environment = new P152EffectiveInputs.Source(envPath, envReal, ENV_HASH);
        var result = new P152TerrainLightingInputs(
                context,
                environment,
                terrain,
                owner,
                facts.inverted().value(),
                readBitmap(properties, "QuadVisibilityBitmap"),
                readBitmap(properties, "EdgeTurnBitmap"),
                lights,
                buildSunSlots(time),
                steady,
                points);
        result.verifySourcesUnchanged(cancelled);
        return result;
    }

    static BitSet skyZones(PackageBytes source, List<Integer> references) throws IOException {
        if (references.size() != 64) {
            throw new IOException("Native Model must retain all 64 zone actor slots");
        }
        var result = new BitSet(64);
        for (var i = 0; i < references.size(); i++) {
            var ref = references.get(i);
            source.checkReference(ref);
            if (ref == 0) {
                continue;
            }
            if (ref < 0) {
                throw new IOException("Imported native zone actors are not qualified");
            }
            var entry = source.exports.get(ref - 1);
            var cls = source.getObjectPath(entry.classIndex());
            var name = switch (cls) {
                case "Engine.ZoneInfo" -> "ZoneInfo";
                case "Engine.LevelInfo" -> "LevelInfo";
                case "Engine.SkyZoneInfo" -> "SkyZoneInfo";
                default -> throw new IOException("Unqualified native zone class: " + cls);
            };
            P152ActorDomain.requireEngineClass(source, entry, name);
            result.set(i, name.equals("SkyZoneInfo"));
        }
        return result;
    }

    static boolean isAdmittedBySteadyLighting(
            Owner owner, int type, int flags, int zone, boolean skyZone, boolean movableSun, int effect) {
        return NativeTerrainSteadyLighting.isAdmitted(
                owner.actorFlags(), owner.zoneNumber(), type, flags, zone, skyZone, movableSun, effect);
    }

    static List<Slot> buildSunSlots(TerrainTimeMapCodec time) throws IOException {
        if (time.getFrameCount() != 8) {
            throw new IOException("Unqualified native sunlight frame count");
        }
        var slots = new ArrayList<Slot>();
        for (var slot = 0; slot < 8; slot++) {
            var hour = 1.5f + slot * 3f;
            if (Float.floatToRawIntBits(time.getTime(slot)) != Float.floatToRawIntBits(hour)) {
                throw new IOException("Native sunlight schedule differs from the fixed bake context");
            }
            slots.add(new Slot(hour, NativeTerrainTimeLighting.safeDirection(hour, 180f, 30f, false)));
        }
        return List.copyOf(slots);
    }

    static void requireEnvironment(byte[] bytes) throws IOException {
        if (bytes.length < 28
                || bytes.length > MAX_ENV_BYTES
                || !new String(bytes, 0, 28, StandardCharsets.UTF_16LE).equals("Lineage2Ver111")) {
            throw new IOException("Unqualified sunlight environment container");
        }
        var decoded = Arrays.copyOfRange(bytes, 28, bytes.length);
        for (var i = 0; i < decoded.length; i++) {
            decoded[i] ^= (byte) 0xac;
        }
        var values = new HashMap<String, String>();
        var section = "";
        for (var raw : new String(decoded, StandardCharsets.ISO_8859_1).split("\\R")) {
            var line = raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                continue;
            }
            var equals = line.indexOf('=');
            if (equals < 1) {
                continue;
            }
            var key = section + "." + line.substring(0, equals).trim();
            if (List.of("envsetup.slopesunangle", "bspsunlight.num", "terrainshadowmap.num")
                            .contains(key)
                    && values.put(key, line.substring(equals + 1).trim()) != null) {
                throw new IOException("Duplicate sunlight environment input: " + key);
            }
        }
        if (!values.equals(
                Map.of("envsetup.slopesunangle", "30", "bspsunlight.num", "8", "terrainshadowmap.num", "8"))) {
            throw new IOException("Sunlight environment parameters differ from the fixed bake context");
        }
    }

    static Map<String, Property> readPropertyMap(PackageBytes source, String path, String cls) throws IOException {
        var result = new HashMap<String, Property>();
        for (var value : P152EffectiveInputs.actorProperties(source, path, cls)) {
            var key = value.name().toLowerCase(Locale.ROOT);
            if (value.arrayIndex() != 0
                    && List.of("location", "numintmap", "bautotimegeneration", "quadvisibilitybitmap", "edgeturnbitmap")
                            .contains(key)) {
                throw new IOException("Unexpected array index on a scalar light input: " + value.name());
            }
            if (value.arrayIndex() == 0 && result.put(key, value) != null) {
                throw new IOException("Duplicate authored light-input property: " + value.name());
            }
        }
        return Map.copyOf(result);
    }

    static Vector readLocation(Map<String, Property> properties) throws IOException {
        var p = requireProperty(properties, "Location");
        if (p.type() != 10 || !p.structType().equalsIgnoreCase("Vector") || p.payload().length != 12) {
            throw new IOException("Light-input Location is not an authored FVector");
        }
        var c = new Cursor(p.payload(), 0, 12);
        var value = new Vector(
                Float.intBitsToFloat(c.readI32()),
                Float.intBitsToFloat(c.readI32()),
                Float.intBitsToFloat(c.readI32()));
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IOException("Light-input Location is not finite");
        }
        return value;
    }

    static BitSet readBitmap(Map<String, Property> properties, String name) throws IOException {
        var p = requireProperty(properties, name);
        if (p.type() != 9) {
            throw new IOException("Terrain topology input is not an array: " + name);
        }
        var bytes = p.payload();
        var c = new Cursor(bytes, 0, bytes.length);
        if (c.readIndex() != 2048 || c.end - c.position != 8192) {
            throw new IOException("Terrain topology array must contain exactly 2048 DWORDs");
        }
        return BitSet.valueOf(Arrays.copyOfRange(bytes, c.position, c.end));
    }

    Terrain getTerrain() {
        return terrain;
    }

    Owner getOwner() {
        return owner;
    }

    boolean isInverted() {
        return inverted;
    }

    BitSet getVisibleQuads() {
        return (BitSet) visible.clone();
    }

    BitSet getEdgeTurns() {
        return (BitSet) turns.clone();
    }

    List<Source> getLights() {
        return lights;
    }

    List<PointLight> getPointLights() {
        return pointLights;
    }

    float getSunLongitude() {
        return 180f;
    }

    float getSunSlope() {
        return 30f;
    }

    boolean isReverseSunSlope() {
        return false;
    }

    int getBspSunSlots() {
        return 8;
    }

    List<Slot> getSlots() {
        return slots;
    }

    List<String> getSteadyCandidates() {
        return steadyCandidates;
    }

    void verifyNoSteadyContributions() throws IOException {
        if (!steadyCandidates.isEmpty()) {
            throw new IOException(
                    "Terrain steady-light sources require a supported rebuild: " + steadyCandidates.getFirst());
        }
        for (var sector : terrain.sectors()) {
            if (!sector.lights().isEmpty()) {
                throw new IOException("Saved steady-light membership differs from the complete native registry");
            }
        }
        var colors = terrain.info().vertexColors();
        if (colors.size() != 256 * 256 * 4) {
            throw new IOException("Native steady-color raster is incomplete");
        }
        for (var i = 0; i < colors.size(); i += 4) {
            if (colors.get(i) != 0 || colors.get(i + 1) != 0 || colors.get(i + 2) != 0 || colors.get(i + 3) != 255) {
                throw new IOException("Saved steady color differs from native no-source initialization");
            }
        }
    }

    List<Input> getReceipt() {
        var result = new ArrayList<>(context.getRuntimeInputs());
        result.add(new Input("env.int", environment.sha256()));
        return List.copyOf(result);
    }

    void verifySourcesUnchanged() throws IOException {
        verifySourcesUnchanged(() -> false);
    }

    void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        environment.verifyUnchanged(cancelled);
        context.verifySourcesUnchanged(cancelled);
    }

    private static int readInteger(Map<String, Property> properties, String name) throws IOException {
        var p = requireProperty(properties, name);
        if (p.type() != 2 || p.payload().length != 4) {
            throw new IOException("Invalid native integer: " + name);
        }
        return new Cursor(p.payload(), 0, 4).readI32();
    }

    private static boolean readBool(Map<String, Property> properties, String name) throws IOException {
        var p = requireProperty(properties, name);
        if (p.type() != 3) {
            throw new IOException("Invalid native Boolean: " + name);
        }
        return p.booleanValue();
    }

    private static Property requireProperty(Map<String, Property> properties, String name) throws IOException {
        var value = properties.get(name.toLowerCase(Locale.ROOT));
        if (value == null) {
            throw new IOException("Missing authored light-input property: " + name);
        }
        return value;
    }
}

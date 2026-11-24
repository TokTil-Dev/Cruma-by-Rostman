package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152ActorDomain.Identity;
import com.rost.cruma.core.editing.P152EffectiveInputs.Source;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import com.rost.cruma.core.editing.TerrainG16Codec.Mip;
import com.rost.cruma.core.editing.TerrainNativeCodec.Coords;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class P152TerrainTriangles {
    private final P152FreshBakeContext context;
    private final Identity owner;
    private final List<Source> heights;
    private final Mip mip;
    private final Coords toWorld;
    private final BitSet turns, visible;

    private P152TerrainTriangles(
            P152FreshBakeContext context,
            Identity owner,
            List<Source> heights,
            Mip mip,
            Coords toWorld,
            BitSet turns,
            BitSet visible) {
        this.context = context;
        this.owner = owner;
        this.heights = List.copyOf(heights);
        this.mip = mip;
        this.toWorld = toWorld;
        this.turns = (BitSet) turns.clone();
        this.visible = (BitSet) visible.clone();
    }

    record Owner(int reference, int region, boolean deleted) {}

    static P152TerrainTriangles capture(
            P152FreshBakeContext context, P152FreshBspModel model, BooleanSupplier cancelled) throws IOException {
        context.verifySourcesUnchanged(cancelled);
        if (!context.getMapPath().equals(model.getMapPath())
                || !context.getMapSha256().equals(model.getMapSha256())
                || context.getModelReference() != model.getIdentity().reference()) {
            throw new IOException("Terrain render selection requires the same frozen world Model");
        }
        if (!context.getGaps().isEmpty()) {
            throw new IOException("Terrain render selection requires a complete captured actor registry");
        }
        var bytes = context.getMapBytes();
        var source = new PackageBytes(context.getMapPath(), bytes);
        int reference = select(context, model, source, cancelled);
        if (reference == 0) {
            throw new IOException("No selected Terrain grid in this replay profile");
        }
        var snapshot = TerrainPackageSnapshot.fromBytes(context.getMapPath(), bytes);
        var terrain = snapshot.getTerrains().stream()
                .filter(t -> t.exportIndex() == reference)
                .findFirst()
                .orElseThrow(() -> new IOException("Selected Terrain is missing from the frozen native snapshot"));
        var facts = context.getMapInputs().getTerrain(terrain.path());
        if (facts.inverted().value()) {
            throw new IOException("Inverted Terrain draw state is outside this replay profile");
        }
        if (terrain.info().width() != 256
                || terrain.info().height() != 256
                || terrain.info().sectorsX() != 16
                || terrain.info().sectorsY() != 16) {
            throw new IOException("Terrain replay requires a 256 by 256 grid");
        }
        requireSectorGrid(reference, 256, 256, terrain.sectors());
        var properties = P152TerrainLightingInputs.readPropertyMap(source, terrain.path(), "TerrainInfo");
        var turns = P152TerrainLightingInputs.readBitmap(properties, "EdgeTurnBitmap");
        var visible = P152TerrainLightingInputs.readBitmap(properties, "QuadVisibilityBitmap");
        var heightSources = new ArrayList<Source>();
        Mip mip;
        if (terrain.heightmapReference() > 0) {
            var captured = TerrainPackageSnapshot.capturedHeight(source, terrain.heightmapObjectPath());
            if (captured.size() != 1) {
                throw new IOException("Terrain replay requires one complete native height mip");
            }
            mip = captured.getFirst();
        } else {
            if (terrain.heightmapReference() == 0) {
                throw new IOException("Terrain replay requires a height texture binding");
            }
            var binding = source.imports.get(-terrain.heightmapReference() - 1);
            if (!source.getName(binding.classPackage()).equals("Engine")
                    || !source.getName(binding.className()).equals("Texture")) {
                throw new IOException("Terrain height import must declare Engine.Texture");
            }
            var heightPath = resolveHeight(context.getClientRoot(), terrain.heightmapObjectPath());
            var real = heightPath.toRealPath();
            if (!real.startsWith(context.getClientRoot().toRealPath())
                    || !heightPath
                            .getFileName()
                            .toString()
                            .equalsIgnoreCase(real.getFileName().toString())) {
                throw new IOException("Terrain height package has an unaudited path or package identity");
            }
            var heightBytes = P152CaptureIo.read(real, 128 * 1024 * 1024, cancelled);
            var captured = TerrainPackageSnapshot.capturedHeight(
                    new PackageBytes(heightPath, heightBytes), terrain.heightmapObjectPath());
            if (captured.size() != 1) {
                throw new IOException("Terrain replay requires one complete native height mip");
            }
            mip = captured.getFirst();
            heightSources.add(new Source(heightPath, real, P152CaptureIo.hash(heightBytes, cancelled)));
        }
        if (mip.width() != terrain.info().width()
                || mip.height() != terrain.info().height()) {
            throw new IOException("Terrain height dimensions differ from the native grid");
        }
        var result = new P152TerrainTriangles(
                context,
                new Identity(context.getMapSha256(), reference, terrain.path()),
                heightSources,
                mip,
                terrain.info().toWorld(),
                turns,
                visible);
        result.verifySourcesUnchanged(cancelled);
        return result;
    }

    static List<Triangle> snapshot(
            int owner, TerrainHeightField field, BitSet turns, BitSet visible, BooleanSupplier cancelled) {
        if (owner <= 0) {
            throw new IllegalArgumentException("Terrain triangles require a positive owner identity");
        }
        var all = new BitSet(field.getWidth() * field.getHeight());
        all.set(0, field.getWidth() * field.getHeight());
        var grid = NativeTerrainGridFootprint.capture(field, field, all, turns, visible, cancelled);
        var positions = new Vector[field.getWidth() * field.getHeight()];
        for (int vertex : grid.supportVertices()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            positions[vertex] = field.getWorldAt(vertex % field.getWidth(), vertex / field.getWidth());
        }
        var result = new ArrayList<Triangle>(grid.triangles().size());
        for (var triangle : grid.triangles()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            result.add(new Triangle(
                    owner,
                    triangle.quadIndex(),
                    triangle.triangleIndex(),
                    positions[triangle.a()],
                    positions[triangle.b()],
                    positions[triangle.c()]));
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return List.copyOf(result);
    }

    static void requireSectorGrid(int owner, int width, int height, List<Sector> sectors) throws IOException {
        if (owner <= 0
                || width < 2
                || height < 2
                || width > 256
                || height > 256
                || sectors.size() != ((width + 14) / 16) * ((height + 14) / 16)) {
            throw new IOException("Incomplete Terrain render sector grid");
        }
        var seen = new HashSet<Integer>();
        for (var sector : sectors) {
            int x = sector.offsetX(), y = sector.offsetY();
            if (sector.owner() != owner
                    || x < 0
                    || y < 0
                    || x >= width - 1
                    || y >= height - 1
                    || x % 16 != 0
                    || y % 16 != 0
                    || sector.sizeX() != Math.min(16, width - 1 - x)
                    || sector.sizeY() != Math.min(16, height - 1 - y)
                    || !seen.add(y * width + x)) {
                throw new IOException("Terrain render sector coverage differs from the sample grid");
            }
        }
    }

    static int selectRebuilt(List<Integer> zones, List<Owner> owners) {
        if (zones.size() != 64 || owners.size() > 65536) {
            throw new IllegalArgumentException("Invalid Terrain selection domain");
        }
        var arrays = new HashMap<Integer, LinkedHashSet<Integer>>();
        for (int zone : zones) {
            if (zone < 0) {
                throw new IllegalArgumentException("Terrain zones must be local actors");
            }
            if (zone > 0) {
                arrays.put(zone, new LinkedHashSet<>());
            }
        }
        for (var owner : owners) {
            if (owner.reference <= 0 || owner.region < 0 || owner.region >= 64) {
                throw new IllegalArgumentException("Invalid Terrain owner region");
            }
            if (!owner.deleted) {
                var array = arrays.get(zones.get(owner.region));
                if (array == null) {
                    throw new IllegalArgumentException("Terrain region has no native zone actor");
                }
                array.add(owner.reference);
            }
        }
        if (zones.getFirst() != 0) {
            arrays.get(zones.getFirst()).clear();
        }
        var frozen = new HashMap<Integer, List<Integer>>();
        arrays.forEach((ref, values) -> frozen.put(ref, List.copyOf(values)));
        return selectOwnerReference(zones, frozen, owners);
    }

    Identity getOwner() {
        return owner;
    }

    List<Source> getHeightSources() {
        return heights;
    }

    List<Triangle> collectTriangles(BooleanSupplier cancelled) throws IOException {
        return collectTriangles(null, cancelled);
    }

    List<Triangle> collectTriangles(TerrainHeightPatch patch, BooleanSupplier cancelled) throws IOException {
        verifySourcesUnchanged(cancelled);
        var field = new TerrainHeightField(mip, toWorld);
        if (patch != null) {
            field.apply(patch, false);
        }
        var result = snapshot(owner.reference(), field, turns, visible, cancelled);
        verifySourcesUnchanged(cancelled);
        return result;
    }

    void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        context.verifySourcesUnchanged(cancelled);
        for (var source : heights) {
            source.verifyUnchanged(cancelled);
        }
        P152FreshHashQuery.checkCancelled(cancelled);
    }

    private static int select(
            P152FreshBakeContext context, P152FreshBspModel model, PackageBytes source, BooleanSupplier cancelled)
            throws IOException {
        var owners = new ArrayList<Owner>();
        var registered = new HashSet<Integer>();
        var registry = NativeLevelEditor.read(source).actors;
        for (int ref : registry) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (ref == 0) {
                continue;
            }
            if (ref < 0 || ref > source.exports.size()) {
                throw new IOException("Terrain selection requires local actor identities");
            }
            var entry = source.exports.get(ref - 1);
            if (!source.getObjectPath(entry.classIndex()).equals("Engine.TerrainInfo")) {
                continue;
            }
            var path = source.getObjectPath(ref);
            var properties = P152TerrainLightingInputs.readPropertyMap(source, path, "TerrainInfo");
            var region = model.getRegions()
                    .pointRegion(P152TerrainLightingInputs.readLocation(properties))
                    .zone();
            owners.add(new Owner(
                    ref, region, context.getMapInputs().getTerrainDeleted(path).value()));
            registered.add(ref);
            if (owners.size() > 65536) {
                throw new IOException("Terrain owner registry exceeds replay bounds");
            }
        }
        var arrays = new HashMap<Integer, List<Integer>>();
        for (int zoneRef : model.getZoneActorReferences()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (zoneRef == 0 || arrays.containsKey(zoneRef)) {
                continue;
            }
            if (zoneRef < 0 || zoneRef > source.exports.size()) {
                throw new IOException("Terrain zones require local actor identities");
            }
            var entry = source.exports.get(zoneRef - 1);
            var cls = source.getClassName(entry);
            if (!List.of("LevelInfo", "ZoneInfo", "SkyZoneInfo").contains(cls)) {
                throw new IOException("Unsupported Terrain zone class");
            }
            var zonePath = source.getObjectPath(zoneRef);
            for (var p : P152EffectiveInputs.actorProperties(source, zonePath, cls)) {
                if (p.name().equalsIgnoreCase("Terrains") && p.arrayIndex() != 0) {
                    throw new IOException("Indexed zone Terrain array");
                }
            }
            var props = P152TerrainLightingInputs.readPropertyMap(source, zonePath, cls);
            var property = props.get("terrains");
            if (property == null) {
                arrays.put(zoneRef, List.of());
                continue;
            }
            if (property.type() != 9 || property.arrayIndex() != 0) {
                throw new IOException("Malformed zone Terrain array");
            }
            var payload = property.payload();
            var c = new Cursor(payload, 0, payload.length);
            int size = c.readIndex();
            if (size < 0 || size > 65536) {
                throw new IOException("Zone Terrain array exceeds replay bounds");
            }
            var values = new ArrayList<Integer>(size);
            for (int i = 0; i < size; i++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                int ref = c.readIndex();
                if (!registered.contains(ref)) {
                    throw new IOException("Zone array references an unregistered Terrain");
                }
                values.add(ref);
            }
            if (c.position != c.end) {
                throw new IOException("Trailing zone Terrain array data");
            }
            arrays.put(zoneRef, List.copyOf(values));
        }
        int saved = selectOwnerReference(model.getZoneActorReferences(), arrays, owners);
        int rebuilt = selectRebuilt(model.getZoneActorReferences(), owners);
        if (saved != rebuilt) {
            throw new IOException("Saved and rebuilt Terrain owner selection differ");
        }
        return saved;
    }

    private static int selectOwnerReference(
            List<Integer> zones, Map<Integer, List<Integer>> arrays, List<Owner> owners) {
        for (int zone : zones) {
            var array = arrays.getOrDefault(zone, List.of());
            if (!array.isEmpty()) {
                return array.getFirst();
            }
        }
        return owners.isEmpty() ? 0 : owners.getFirst().reference;
    }

    private static Path resolveHeight(Path root, String objectPath) throws IOException {
        int dot = objectPath.indexOf('.');
        if (dot < 1 || !objectPath.substring(0, dot).matches("[A-Za-z0-9_]{1,64}")) {
            throw new IOException("Invalid Terrain height package name");
        }
        var filename = objectPath.substring(0, dot) + ".utx";
        var matches = new ArrayList<Path>();
        for (var folder : List.of("Textures", "SysTextures")) {
            var directory = root.resolve(folder);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var files = Files.list(directory)) {
                matches.addAll(files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase(filename))
                        .toList());
            }
        }
        if (matches.size() != 1) {
            throw new IOException("Missing or ambiguous Terrain height package");
        }
        return matches.getFirst();
    }
}

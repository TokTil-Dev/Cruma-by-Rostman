package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Source;
import com.rost.cruma.core.editing.P152BspReceiverModel.Receiver;
import com.rost.cruma.core.editing.P152EffectiveInputs.Fact;
import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.P152TerrainLightingInputs.PointLight;
import com.rost.cruma.core.editing.TerrainHeightField.SectorCoordinate;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.world.NativeBspPointAdmission;
import com.rost.cruma.core.world.NativeBspPointAdmission.Node;
import com.rost.cruma.core.world.NativeBspShadowProjection;
import com.rost.cruma.core.world.NativeBspShadowProjection.Bounds;
import com.rost.cruma.core.world.NativeBspShadowProjection.Separation;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import com.rost.cruma.core.world.NativeBspShadowProjection.Volume;
import com.rost.cruma.core.world.NativeBspSunDirection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class P152BspReceiverExclusion {
    private final P152TerrainSunBake bake;
    private final Summary summary;
    private final List<SectorEnvelope> envelopes;
    private final List<Zone> zones;
    private final List<Input> inputs;

    private P152BspReceiverExclusion(
            P152TerrainSunBake bake,
            Summary summary,
            List<SectorEnvelope> envelopes,
            List<Zone> zones,
            List<Input> inputs) {
        this.bake = bake;
        this.summary = summary;
        this.envelopes = List.copyOf(envelopes);
        this.zones = List.copyOf(zones);
        this.inputs = List.copyOf(inputs);
    }

    record Zone(int number, int actorReference, Fact<Boolean> sunAffect) {}

    record Summary(
            int placements,
            int pointSources,
            int sunSlots,
            int sectors,
            long pointProjections,
            long pointPairsRejected,
            long sunProjections,
            double minimumPointMargin,
            double minimumSunMargin,
            double maximumFloatErrorBound) {}

    record SectorEnvelope(int offsetX, int offsetY, Bounds bounds) {}

    static P152BspReceiverExclusion capture(P152TerrainSunBake bake) throws IOException {
        return capture(bake, () -> false);
    }

    static P152BspReceiverExclusion capture(P152TerrainSunBake bake, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(bake);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        bake.verifySourcesUnchanged();
        P152TerrainSunBake.requireOperation(bake.getPatch());
        if (!bake.getQuery().getGaps().isEmpty()) {
            throw new IOException("BSP exclusion requires the complete frozen local P152 Model");
        }
        var context = bake.getQuery().getContext();
        var lighting = bake.getInputs();
        var domain = captureDomain(context, bake.getQuery().getModel(), lighting, cancelled);
        var field = new TerrainHeightField(
                bake.getHeightPackage().getCapturedHeightmap(),
                lighting.getTerrain().info().toWorld());
        var envelopes =
                computeEnvelopes(field, bake.getPatch(), lighting.getTerrain().sectors());
        var summary = evaluate(
                domain.placements(), domain.points(), domain.byPlacement(), domain.pointNodes(), envelopes, cancelled);
        var inputs = new ArrayList<>(lighting.getReceipt());
        inputs.add(new Input("Terrain height asset", bake.getHeightPackage().getOriginalSha256()));
        inputs.add(new Input("BSP receiver map", context.getMapSha256()));
        var result = new P152BspReceiverExclusion(bake, summary, envelopes, domain.zones(), inputs);
        result.verifySourcesUnchanged();
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    record Domain(
            List<Receiver> placements,
            List<PointLight> points,
            Map<Integer, List<Vector>> byPlacement,
            Map<Integer, List<Node>> pointNodes,
            List<Zone> zones) {}

    static Domain captureDomain(
            P152FreshBakeContext context,
            P152FreshBspModel model,
            P152TerrainLightingInputs lighting,
            BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var receiver = model.getReceiverModel();
        if (!context.getGaps().isEmpty()
                || !context.getMapSha256().equals(P152TerrainSunBake.MAP_HASH)
                || !context.getMapSha256().equals(model.getMapSha256())
                || context.getModelReference() != receiver.getModelReference()
                || receiver.isBeastLighting()) {
            throw new IOException("BSP exclusion requires the complete frozen local P152 Model");
        }
        var source = new PackageBytes(context.getMapPath(), context.getMapBytes());
        receiver.requireSource(source);
        requireAtlasVariants(receiver.getAtlasVariantCounts(), receiver.getPlacements(), lighting.getBspSunSlots());
        var registry = new HashSet<Integer>();
        for (var actor : context.getActorDomain().getReport().actors()) {
            registry.add(actor.identity().reference());
        }
        var zones = new ArrayList<Zone>();
        var seenZones = new HashSet<Integer>();
        for (var placement : receiver.getPlacements()) {
            if (seenZones.add(placement.zone())) {
                var reference = receiver.getZoneActorReferences().get(placement.zone());
                if (reference <= 0 || !registry.contains(reference)) {
                    throw new IOException("BSP placement zone is not a local frozen Level actor");
                }
                var fact = context.getMapInputs().getZoneSunAffect(source.getObjectPath(reference));
                zones.add(new Zone(placement.zone(), reference, fact));
            }
        }
        requireLightDomain(lighting.getLights(), lighting.getPointLights(), registry);
        var lightReferences = new HashSet<Integer>();
        lighting.getLights().forEach(light -> lightReferences.add(light.reference()));
        requireModelLights(
                receiver.getNativeLightReferences(),
                receiver.getPlacements(),
                lightReferences,
                context.getLevelIdentity().reference());
        if (receiver.getPlacements().size() != 13
                || lighting.getPointLights().size() != 731
                || lighting.getBspSunSlots() != 8) {
            throw new IOException("BSP source domain differs from the qualified frozen operation");
        }
        var directions = new ArrayList<Vector>();
        for (int i = 0; i < lighting.getBspSunSlots(); i++) {
            directions.add(NativeBspSunDirection.directionForSlot(
                            i,
                            lighting.getBspSunSlots(),
                            lighting.getSunLongitude(),
                            lighting.getSunSlope(),
                            lighting.isReverseSunSlope())
                    .towardLight());
        }
        var sun = lighting.getLights().stream()
                .filter(Source::movableSun)
                .findFirst()
                .orElseThrow();
        Vector authoredDirection = null;
        if (zones.stream().anyMatch(zone -> !zone.sunAffect().value())) {
            var rotation = context.getMapInputs()
                    .getSunRotation(source.getObjectPath(sun.reference()))
                    .value();
            authoredDirection = NativeBspSunDirection.fromRotation(rotation.pitch(), rotation.yaw());
        }
        var byZone = new HashMap<Integer, Boolean>();
        zones.forEach(zone -> byZone.put(zone.number(), zone.sunAffect().value()));
        var byPlacement = new HashMap<Integer, List<Vector>>();
        for (var placement : receiver.getPlacements()) {
            byPlacement.put(
                    placement.index(),
                    byZone.get(placement.zone()) ? List.copyOf(directions) : List.of(authoredDirection));
        }
        var pointNodes = new HashMap<Integer, List<Node>>();
        for (var placement : receiver.getPlacements()) {
            pointNodes.put(placement.index(), receiver.pointNodes(placement.surfaceIndex()));
        }
        return new Domain(receiver.getPlacements(), lighting.getPointLights(), byPlacement, pointNodes, zones);
    }

    static Map<SectorCoordinate, String> sectorAdvice(
            P152FreshBakeContext context,
            P152FreshBspModel model,
            P152TerrainLightingInputs lighting,
            TerrainHeightField field,
            float margin,
            BooleanSupplier cancelled)
            throws IOException {
        var domain = captureDomain(context, model, lighting, cancelled);
        var advice = new LinkedHashMap<SectorCoordinate, String>();
        for (var sector : lighting.getTerrain().sectors()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var sizeX = Math.min(sector.sizeX(), field.getWidth() - 1 - sector.offsetX());
            var sizeY = Math.min(sector.sizeY(), field.getHeight() - 1 - sector.offsetY());
            var box = toBounds(field.getBounds(sector.offsetX(), sector.offsetY(), sizeX, sizeY));
            var enlarged = new Bounds(
                    new Vector(box.min().x(), box.min().y(), box.min().z() - margin),
                    new Vector(box.max().x(), box.max().y(), box.max().z() + margin));
            var coordinate = new SectorCoordinate(sector.offsetX() / 16, sector.offsetY() / 16);
            try {
                evaluate(
                        domain.placements(),
                        domain.points(),
                        domain.byPlacement(),
                        domain.pointNodes(),
                        List.of(new SectorEnvelope(sector.offsetX(), sector.offsetY(), enlarged)),
                        cancelled);
                advice.put(coordinate, "");
            } catch (IOException shaded) {
                advice.put(coordinate, shaded.getMessage());
            }
        }
        return Collections.unmodifiableMap(advice);
    }

    static void requireLightDomain(List<Source> lights, List<PointLight> points, Set<Integer> registry)
            throws IOException {
        var pointReferences = new HashSet<Integer>();
        for (var point : points) {
            if (!registry.contains(point.reference()) || !pointReferences.add(point.reference())) {
                throw new IOException("BSP point source is absent or repeated in the frozen Level");
            }
        }
        var seen = new HashSet<Integer>();
        int suns = 0;
        for (var light : lights) {
            if (!registry.contains(light.reference()) || !seen.add(light.reference())) {
                throw new IOException("BSP light source is absent or repeated in the frozen Level");
            }
            if (light.movableSun()) {
                if (light.effect() != 19 || pointReferences.contains(light.reference())) {
                    throw new IOException("BSP movable sunlight branch is unqualified");
                }
                suns++;
            } else if (light.effect() == 19 || !pointReferences.remove(light.reference())) {
                throw new IOException(
                        "BSP ordinary light has an unqualified sunlight effect or missing projection input");
            }
        }
        if (suns != 1 || !pointReferences.isEmpty()) {
            throw new IOException("BSP complete point/sun source association differs");
        }
    }

    static void requireAtlasVariants(List<Integer> variants, List<Receiver> placements, int configuredSlots)
            throws IOException {
        for (var placement : placements) {
            var atlas = placement.atlasIndex();
            if (atlas < 0
                    || atlas >= variants.size()
                    || variants.get(atlas) < 1
                    || variants.get(atlas) > configuredSlots) {
                throw new IOException("Saved BSP atlas variants exceed the qualified sunlight direction domain");
            }
        }
    }

    static void requireModelLights(
            List<Integer> nativeReferences, List<Receiver> placements, Set<Integer> sources, int levelReference)
            throws IOException {
        for (var reference : nativeReferences) {
            if (reference != 0 && !sources.contains(reference)) {
                throw new IOException("Native BSP Model light is not an authenticated source: " + reference);
            }
        }
        for (var placement : placements) {
            if (placement.levelReference() != levelReference) {
                throw new IOException("Saved BSP placement belongs to a different source Level");
            }
            for (var reference : placement.lightReferences()) {
                if (!sources.contains(reference)) {
                    throw new IOException("Saved BSP placement light is not an authenticated source: " + reference);
                }
            }
        }
    }

    static List<SectorEnvelope> computeEnvelopes(
            TerrainHeightField field, TerrainHeightPatch patch, List<Sector> sectors) throws IOException {
        var coordinates = field.getAffectedSectors(patch);
        if (coordinates.isEmpty()) {
            throw new IOException("BSP exclusion has no changed sector");
        }
        var selected = new HashMap<SectorCoordinate, Sector>();
        for (var sector : sectors) {
            var coordinate = new SectorCoordinate(sector.offsetX() / 16, sector.offsetY() / 16);
            if (coordinates.contains(coordinate)) {
                if (sector.offsetX() % 16 != 0
                        || sector.offsetY() % 16 != 0
                        || sector.sizeX() != 16
                        || sector.sizeY() != 16
                        || selected.put(coordinate, sector) != null) {
                    throw new IOException("BSP affected Terrain sector topology is unaudited");
                }
            }
        }
        if (!selected.keySet().equals(coordinates)) {
            throw new IOException("BSP exclusion is missing an affected Terrain sector");
        }
        var before = new HashMap<SectorCoordinate, Bounds>();
        for (var entry : selected.entrySet()) {
            before.put(entry.getKey(), fullBounds(field, entry.getValue()));
        }
        return envelopePatch(field, patch, selected, before);
    }

    static Summary evaluate(
            List<Receiver> placements,
            List<PointLight> points,
            List<Vector> directions,
            List<SectorEnvelope> envelopes,
            BooleanSupplier cancelled)
            throws IOException {
        var byPlacement = new HashMap<Integer, List<Vector>>();
        for (var placement : placements) {
            byPlacement.put(placement.index(), List.copyOf(directions));
        }
        return evaluate(placements, points, byPlacement, envelopes, cancelled);
    }

    static Summary evaluate(
            List<Receiver> placements,
            List<PointLight> points,
            Map<Integer, List<Vector>> byPlacement,
            List<SectorEnvelope> envelopes,
            BooleanSupplier cancelled)
            throws IOException {
        return evaluate(placements, points, byPlacement, null, envelopes, cancelled);
    }

    static Summary evaluate(
            List<Receiver> placements,
            List<PointLight> points,
            Map<Integer, List<Vector>> byPlacement,
            Map<Integer, List<Node>> pointNodes,
            List<SectorEnvelope> envelopes,
            BooleanSupplier cancelled)
            throws IOException {
        Map<Integer, List<Node>> nodes = null;
        if (pointNodes != null) {
            nodes = new HashMap<>();
            for (var placement : placements) {
                var values = pointNodes.get(placement.index());
                if (values == null) {
                    throw new IOException("BSP point admission lacks source node metadata");
                }
                nodes.put(placement.index(), List.copyOf(values));
            }
        }
        placements = List.copyOf(placements);
        points = List.copyOf(points);
        envelopes = List.copyOf(envelopes);
        var frozen = new HashMap<Integer, List<Vector>>();
        byPlacement.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        int sunSlots = 0;
        for (var placement : placements) {
            var directions = frozen.get(placement.index());
            if (directions == null || directions.isEmpty() || directions.size() > 256) {
                throw new IOException("BSP placement lacks a complete sunlight direction branch");
            }
            sunSlots = Math.max(sunSlots, directions.size());
        }
        if (placements.isEmpty()
                || envelopes.isEmpty()
                || placements.size() > 65536
                || points.size() > 65536
                || envelopes.size() > 65536
                || (long) placements.size() * (points.size() + sunSlots) * envelopes.size() > 1_000_000) {
            throw new IOException("BSP projection domain is empty or exceeds its bounded work budget");
        }
        if (nodes != null) {
            long nodeChecks = 0;
            for (var list : nodes.values()) {
                nodeChecks += (long) list.size() * points.size();
                if (nodeChecks > 5_000_000) {
                    throw new IOException("BSP point admission exceeds its bounded node work budget");
                }
            }
        }
        long pointCount = 0, rejectedPoints = 0, sunCount = 0;
        double pointMargin = Double.POSITIVE_INFINITY, sunMargin = Double.POSITIVE_INFINITY, error = 0;
        for (var placement : placements) {
            var directions = frozen.get(placement.index());
            for (var point : points) {
                P152FreshHashQuery.checkCancelled(cancelled);
                try {
                    var position = new Vector(
                            point.position().x(),
                            point.position().y(),
                            point.position().z());
                    if (nodes != null) {
                        boolean admitted = false;
                        for (var node : nodes.get(placement.index())) {
                            P152FreshHashQuery.checkCancelled(cancelled);
                            if (NativeBspPointAdmission.mayReceive(node, position, point.authoredRadius())) {
                                admitted = true;
                                break;
                            }
                        }
                        if (!admitted) {
                            rejectedPoints++;
                            continue;
                        }
                    }
                    var crop = NativeBspShadowProjection.pointCrop(
                            placement.projection(),
                            placement.savedAxisU(),
                            placement.savedAxisV(),
                            position,
                            point.authoredRadius());
                    var projection = NativeBspShadowProjection.point(placement.projection(), position, crop);
                    for (var envelope : envelopes) {
                        var separated = requireSeparation(
                                projection.getVolume(),
                                envelope.bounds(),
                                "point " + point.reference() + " placement " + placement.index());
                        pointMargin = Math.min(pointMargin, separated.minimumMargin());
                        error = Math.max(error, separated.floatErrorBound());
                        pointCount++;
                    }
                } catch (IllegalArgumentException | ArithmeticException invalid) {
                    throw new IOException(
                            "Unaudited BSP point projection at placement " + placement.index() + " source "
                                    + point.reference(),
                            invalid);
                }
            }
            for (int slot = 0; slot < directions.size(); slot++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                try {
                    var projection = NativeBspShadowProjection.sun(placement.projection(), directions.get(slot));
                    for (var envelope : envelopes) {
                        var separated = requireSeparation(
                                projection.getVolume(),
                                envelope.bounds(),
                                "sun slot " + slot + " placement " + placement.index());
                        sunMargin = Math.min(sunMargin, separated.minimumMargin());
                        error = Math.max(error, separated.floatErrorBound());
                        sunCount++;
                    }
                } catch (IllegalArgumentException | ArithmeticException invalid) {
                    throw new IOException("Unaudited BSP sun projection at placement " + placement.index(), invalid);
                }
            }
        }
        return new Summary(
                placements.size(),
                points.size(),
                sunSlots,
                envelopes.size(),
                pointCount,
                rejectedPoints,
                sunCount,
                pointMargin,
                sunMargin,
                error);
    }

    Summary getSummary() {
        return summary;
    }

    List<SectorEnvelope> getEnvelopes() {
        return envelopes;
    }

    List<Zone> getZones() {
        return zones;
    }

    List<Input> getInputs() {
        return inputs;
    }

    void requireBake(P152TerrainSunBake expected) throws IOException {
        if (bake != expected) {
            throw new IOException("BSP receiver receipt belongs to a different frozen bake");
        }
        verifySourcesUnchanged();
    }

    void verifySourcesUnchanged() throws IOException {
        bake.verifySourcesUnchanged();
    }

    private static List<SectorEnvelope> envelopePatch(
            TerrainHeightField field,
            TerrainHeightPatch patch,
            HashMap<SectorCoordinate, Sector> selected,
            HashMap<SectorCoordinate, Bounds> before)
            throws IOException {
        var result = new ArrayList<SectorEnvelope>();
        field.apply(patch, false);
        try {
            for (var entry : selected.entrySet()) {
                var sector = entry.getValue();
                result.add(new SectorEnvelope(
                        sector.offsetX(),
                        sector.offsetY(),
                        union(before.get(entry.getKey()), fullBounds(field, sector))));
            }
        } finally {
            field.apply(patch, true);
        }
        result.sort(Comparator.comparingInt(SectorEnvelope::offsetY).thenComparingInt(SectorEnvelope::offsetX));
        return List.copyOf(result);
    }

    private static Bounds fullBounds(TerrainHeightField field, Sector sector) {
        var sizeX = Math.min(sector.sizeX(), field.getWidth() - 1 - sector.offsetX());
        var sizeY = Math.min(sector.sizeY(), field.getHeight() - 1 - sector.offsetY());
        return toBounds(field.getBounds(sector.offsetX(), sector.offsetY(), sizeX, sizeY));
    }

    private static Separation requireSeparation(Volume volume, Bounds bounds, String identity) throws IOException {
        return volume.findSeparatingPlane(bounds)
                .orElseThrow(() ->
                        new IOException("BSP reverse receiver is not excluded with a native float guard: " + identity));
    }

    private static Bounds toBounds(Box box) {
        return new Bounds(
                new Vector(box.min().x(), box.min().y(), box.min().z()),
                new Vector(box.max().x(), box.max().y(), box.max().z()));
    }

    private static Bounds union(Bounds a, Bounds b) {
        return new Bounds(
                new Vector(
                        Math.min(a.min().x(), b.min().x()),
                        Math.min(a.min().y(), b.min().y()),
                        Math.min(a.min().z(), b.min().z())),
                new Vector(
                        Math.max(a.max().x(), b.max().x()),
                        Math.max(a.max().y(), b.max().y()),
                        Math.max(a.max().z(), b.max().z())));
    }
}

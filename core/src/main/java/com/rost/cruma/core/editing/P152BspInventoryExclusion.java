package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152BspDormantCache.Receipt;
import com.rost.cruma.core.editing.P152BspLightingInputs.Domain;
import com.rost.cruma.core.editing.P152BspReceiverExclusion.SectorEnvelope;
import com.rost.cruma.core.editing.P152BspReceiverModel.ConsumerFacts;
import com.rost.cruma.core.editing.P152BspReceiverModel.Receiver;
import com.rost.cruma.core.editing.P152EffectiveInputs.BspSources;
import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.P152MapInventory.Entry;
import com.rost.cruma.core.world.NativeBspLightAssociation;
import com.rost.cruma.core.world.NativeBspPointAdmission;
import com.rost.cruma.core.world.NativeBspPointAdmission.Node;
import com.rost.cruma.core.world.NativeBspShadowProjection;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import com.rost.cruma.core.world.NativeBspShadowProjection.Volume;
import com.rost.cruma.core.world.NativeBspSunAdmission;
import com.rost.cruma.core.world.NativeBspSunDirection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152BspInventoryExclusion {
    private static final int MAX_MAP_RECEIVERS = 65536;
    private static final long MAX_PAIRS = 150_000_000;
    private static final long MAX_NODE_CHECKS = 500_000_000;
    private final P152TerrainSunBake bake;
    private final P152MapInventory inventory;
    private final BspSources sources;
    private final List<Receipt> dormantReceipts;
    private final Report report;

    private P152BspInventoryExclusion(
            P152TerrainSunBake bake,
            P152MapInventory inventory,
            BspSources sources,
            List<Receipt> dormantReceipts,
            Report report) {
        this.bake = bake;
        this.inventory = inventory;
        this.sources = sources;
        this.dormantReceipts = List.copyOf(dormantReceipts);
        this.report = report;
    }

    enum Status {
        EMPTY,
        ORPHAN_RESOURCES_EXCLUDED,
        DORMANT_SAVED_MASKS_EXCLUDED,
        EXCLUDED,
        GAP
    }

    enum Stage {
        PACKAGE,
        WORLD_MODEL,
        DORMANT_CONSUMERS,
        RECEIVER_MODEL,
        CONSUMERS,
        BEAST_DEPENDENCY,
        LIGHT_INPUTS,
        ASSOCIATIONS,
        PROJECTIONS,
        COMPLETE
    }

    record Counts(
            int placements,
            int pointSources,
            int sunSources,
            long pointPairs,
            long rejectedPointPairs,
            long pointProjections,
            long sunProjections,
            long sunPairs,
            long rejectedSunPairs,
            long sunSlots,
            long rejectedSunSlots,
            long nodeChecks,
            Double minimumPointMargin,
            Double minimumSunMargin,
            double maximumFloatErrorBound) {}

    record MapResult(
            String map,
            String sha256,
            int fileVersion,
            int licenseVersion,
            int levelReference,
            int modelReference,
            Status status,
            Stage stage,
            String reason,
            ConsumerFacts consumers,
            Counts counts) {}

    record Report(
            String profile,
            String operation,
            String editedMapSha256,
            String editedHeightSha256,
            List<Input> runtimeInputs,
            List<MapResult> maps) {
        Report {
            runtimeInputs = List.copyOf(runtimeInputs);
            maps = List.copyOf(maps);
        }
    }

    record ProjectionResult(boolean excluded, String reason, Counts counts) {}

    interface MapInspector {
        MapResult inspect(Entry entry, byte[] bytes) throws IOException;
    }

    static final class Budget {
        private final long maximumPairs, maximumNodeChecks;
        private long pairs, nodeChecks;

        Budget(long maximumPairs, long maximumNodeChecks) {
            if (maximumPairs < 0 || maximumNodeChecks < 0) {
                throw new IllegalArgumentException("Negative BSP inventory work budget");
            }
            this.maximumPairs = maximumPairs;
            this.maximumNodeChecks = maximumNodeChecks;
        }

        private void spendPair() throws IOException {
            spendPairs(1);
        }

        private void spendPairs(int count) throws IOException {
            if (count < 1 || count > maximumPairs - pairs) {
                throw new IOException("BSP inventory projection pair budget exhausted");
            }
            pairs += count;
        }

        private void spendNodeCheck() throws IOException {
            if (nodeChecks >= maximumNodeChecks) {
                throw new IOException("BSP inventory native node budget exhausted");
            }
            nodeChecks++;
        }
    }

    static P152BspInventoryExclusion inspect(P152TerrainSunBake bake, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(bake);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        bake.verifySourcesUnchanged(cancelled);
        P152TerrainSunBake.requireOperation(bake.getPatch());
        var root = bake.getQuery().getContext().getClientRoot();
        var sources = P152EffectiveInputs.open(root, cancelled).captureBspSources();
        sources.verifySourcesUnchanged(cancelled);
        var inventory = P152MapInventory.capture(root, cancelled);
        var field = new TerrainHeightField(
                bake.getHeightPackage().getCapturedHeightmap(),
                bake.getInputs().getTerrain().info().toWorld());
        var envelopes = P152BspReceiverExclusion.computeEnvelopes(
                field, bake.getPatch(), bake.getInputs().getTerrain().sectors());
        var directions = new ArrayList<Vector>();
        for (var slot = 0; slot < bake.getInputs().getBspSunSlots(); slot++) {
            directions.add(NativeBspSunDirection.directionForSlot(
                            slot,
                            bake.getInputs().getBspSunSlots(),
                            bake.getInputs().getSunLongitude(),
                            bake.getInputs().getSunSlope(),
                            bake.getInputs().isReverseSunSlope())
                    .towardLight());
        }
        var budget = new Budget(MAX_PAIRS, MAX_NODE_CHECKS);
        var dormantReceipts = new ArrayList<Receipt>();
        var outcomes = inspectMaps(
                inventory,
                (entry, bytes) ->
                        inspectMap(entry, bytes, sources, envelopes, directions, budget, dormantReceipts, cancelled),
                cancelled);
        var receipt = new ArrayList<>(sources.getReceipt());
        for (var dormant : dormantReceipts) {
            for (var input : dormant.getInputs()) {
                if (!receipt.contains(input)) {
                    receipt.add(input);
                }
            }
        }
        for (var input : bake.getInputs().getReceipt()) {
            if (!receipt.contains(input)) {
                receipt.add(input);
            }
        }
        var report = new Report(
                "P152 fresh non-precaching shadow scenes with captured env.int and installed saved receivers;"
                        + " historical editor state is not certified",
                "12_24.TerrainInfo0 height sample 190,230: 25461 to 25462; complete old/new affected sector envelopes",
                bake.getQuery().getContext().getMapSha256(),
                bake.getHeightPackage().getOriginalSha256(),
                receipt,
                outcomes);
        var result = new P152BspInventoryExclusion(bake, inventory, sources, dormantReceipts, report);
        result.verifySourcesUnchanged(cancelled);
        return result;
    }

    static List<MapResult> inspectMaps(P152MapInventory inventory, MapInspector inspector, BooleanSupplier cancelled)
            throws IOException {
        var results = new ArrayList<MapResult>();
        for (var entry : inventory.getMaps()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var bytes = inventory.readCaptured(entry, cancelled);
            results.add(inspector.inspect(entry, bytes));
        }
        inventory.verifyUnchanged(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return List.copyOf(results);
    }

    static ProjectionResult evaluate(
            List<Receiver> placements,
            Domain lights,
            Map<Integer, List<Node>> nodes,
            List<Vector> environmentDirections,
            List<SectorEnvelope> envelopes,
            Budget budget,
            BooleanSupplier cancelled) {
        return evaluate(placements, lights, nodes, null, environmentDirections, envelopes, budget, cancelled);
    }

    static ProjectionResult evaluate(
            List<Receiver> placements,
            Domain lights,
            Map<Integer, List<Node>> nodes,
            NativeBspLightAssociation association,
            List<Vector> environmentDirections,
            List<SectorEnvelope> envelopes,
            Budget budget,
            BooleanSupplier cancelled) {
        var progress = new Progress(
                placements.size(), lights.points().size(), lights.suns().size());
        if (association != null) {
            progress.nodes = association.getCandidateChecks();
        }
        String activeProjection = null;
        try {
            if (placements.isEmpty()
                    || placements.size() > MAX_MAP_RECEIVERS
                    || lights.points().size() > MAX_MAP_RECEIVERS
                    || lights.suns().size() > MAX_MAP_RECEIVERS
                    || environmentDirections.isEmpty()
                    || environmentDirections.size() > 256
                    || envelopes.isEmpty()
                    || envelopes.size() > 4) {
                throw new IOException("BSP numeric inventory domain exceeds its bounded profile");
            }
            var rawSunDirections = new HashMap<Integer, Vector>();
            for (var sun : lights.suns()) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var rotation = lights.authoredSunRotations().get(sun.reference());
                if (rotation == null) {
                    if (association != null) {
                        throw new IOException("BSP captured sunlight lacks initial authored rotation");
                    }
                } else {
                    rawSunDirections.put(
                            sun.reference(),
                            NativeBspSunDirection.rawTowardFromRotation(rotation.pitch(), rotation.yaw()));
                }
            }
            var byZone = new HashMap<Integer, Boolean>();
            lights.zones()
                    .forEach(zone -> byZone.put(zone.number(), zone.sunAffect().value()));
            for (var placement : placements) {
                activeProjection = null;
                P152FreshHashQuery.checkCancelled(cancelled);
                var pointNodes = nodes.get(placement.index());
                var sunAffect = byZone.get(placement.zone());
                if (pointNodes == null || sunAffect == null) {
                    throw new IOException("BSP projection lacks complete source node or zone inputs");
                }
                var savedLightReferences = new HashSet<>(placement.lightReferences());
                for (var point : lights.points()) {
                    activeProjection = null;
                    P152FreshHashQuery.checkCancelled(cancelled);
                    budget.spendPair();
                    progress.pairs++;
                    var savedMask = savedLightReferences.contains(point.reference());
                    if (!savedMask
                            && association != null
                            && !association.mayReceive(placement.surfaceIndex(), point.reference())) {
                        progress.rejected++;
                        continue;
                    }
                    var position = new Vector(
                            point.position().x(),
                            point.position().y(),
                            point.position().z());
                    var admitted = savedMask;
                    if (!savedMask) {
                        for (var node : pointNodes) {
                            P152FreshHashQuery.checkCancelled(cancelled);
                            budget.spendNodeCheck();
                            progress.nodes++;
                            if (NativeBspPointAdmission.mayReceive(node, position, point.authoredRadius())) {
                                admitted = true;
                                break;
                            }
                        }
                    }
                    if (!admitted) {
                        progress.rejected++;
                        continue;
                    }
                    activeProjection = "point " + point.reference() + " placement " + placement.index();
                    var crop = NativeBspShadowProjection.pointCrop(
                            placement.projection(),
                            placement.savedAxisU(),
                            placement.savedAxisV(),
                            position,
                            point.authoredRadius());
                    var projection = NativeBspShadowProjection.point(placement.projection(), position, crop);
                    progress.separate(projection.getVolume(), envelopes, true, cancelled);
                }
                for (var sun : lights.suns()) {
                    activeProjection = null;
                    P152FreshHashQuery.checkCancelled(cancelled);
                    var slots = sunAffect ? environmentDirections.size() : 1;
                    budget.spendPairs(slots);
                    progress.sunPairs++;
                    progress.sunSlots += slots;
                    var rotation = lights.authoredSunRotations().get(sun.reference());
                    var savedMask = savedLightReferences.contains(sun.reference());
                    var admitted = savedMask;
                    if (!savedMask
                            && (association == null
                                    || association.mayReceive(placement.surfaceIndex(), sun.reference()))) {
                        if (rotation == null) {
                            admitted = true;
                        } else {
                            var raw = rawSunDirections.get(sun.reference());
                            for (var node : pointNodes) {
                                P152FreshHashQuery.checkCancelled(cancelled);
                                budget.spendNodeCheck();
                                progress.nodes++;
                                if (NativeBspSunAdmission.mayReceive(node, raw)) {
                                    admitted = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!admitted) {
                        progress.rejectedSunPairs++;
                        progress.rejectedSunSlots += slots;
                        continue;
                    }
                    if (!sunAffect && rotation == null) {
                        throw new IOException("BSP false-zone sunlight lacks authored rotation");
                    }
                    for (var slot = 0; slot < slots; slot++) {
                        P152FreshHashQuery.checkCancelled(cancelled);
                        activeProjection = "sun "
                                + sun.reference()
                                + " placement "
                                + placement.index()
                                + (sunAffect ? " slot " + slot : " authored");
                        var direction = sunAffect
                                ? environmentDirections.get(slot)
                                : NativeBspSunDirection.fromRotation(rotation.pitch(), rotation.yaw());
                        var projection = NativeBspShadowProjection.sun(placement.projection(), direction);
                        progress.separate(projection.getVolume(), envelopes, false, cancelled);
                    }
                }
            }
            P152FreshHashQuery.checkCancelled(cancelled);
            return new ProjectionResult(
                    true,
                    "All captured native source projections exclude the complete old/new sector envelope",
                    progress.getCounts());
        } catch (IOException | IllegalArgumentException | ArithmeticException unsupported) {
            var reason = unsupported.getMessage();
            return new ProjectionResult(
                    false, activeProjection == null ? reason : activeProjection + ": " + reason, progress.getCounts());
        }
    }

    private static final class Progress {
        private final int placements, points, suns;
        private long pairs, rejected, pointProjections, sunProjections, nodes;
        private long sunPairs, rejectedSunPairs, sunSlots, rejectedSunSlots;
        private Double pointMargin, sunMargin;
        private double maximumError;

        Progress(int placements, int points, int suns) {
            this.placements = placements;
            this.points = points;
            this.suns = suns;
        }

        void separate(Volume volume, List<SectorEnvelope> envelopes, boolean point, BooleanSupplier cancelled)
                throws IOException {
            for (var envelope : envelopes) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var separation = volume.findSeparatingPlane(envelope.bounds())
                        .orElseThrow(() -> new IOException("No guarded native separating plane"));
                maximumError = Math.max(maximumError, separation.floatErrorBound());
                if (point) {
                    pointProjections++;
                    pointMargin = pointMargin == null
                            ? separation.minimumMargin()
                            : Math.min(pointMargin, separation.minimumMargin());
                } else {
                    sunProjections++;
                    sunMargin = sunMargin == null
                            ? separation.minimumMargin()
                            : Math.min(sunMargin, separation.minimumMargin());
                }
            }
        }

        Counts getCounts() {
            return new Counts(
                    placements,
                    points,
                    suns,
                    pairs,
                    rejected,
                    pointProjections,
                    sunProjections,
                    sunPairs,
                    rejectedSunPairs,
                    sunSlots,
                    rejectedSunSlots,
                    nodes,
                    pointMargin,
                    sunMargin,
                    maximumError);
        }
    }

    Report report(BooleanSupplier cancelled) throws IOException {
        verifySourcesUnchanged(cancelled);
        return report;
    }

    void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        bake.verifySourcesUnchanged(cancelled);
        sources.verifySourcesUnchanged(cancelled);
        for (var dormant : dormantReceipts) {
            P152FreshHashQuery.checkCancelled(cancelled);
            dormant.verifySourcesUnchanged();
        }
        inventory.verifyUnchanged(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
    }

    private static MapResult inspectMap(
            Entry entry,
            byte[] bytes,
            BspSources sources,
            List<SectorEnvelope> envelopes,
            List<Vector> directions,
            Budget budget,
            List<Receipt> dormantReceipts,
            BooleanSupplier cancelled) {
        var stage = Stage.PACKAGE;
        var fileVersion = 0;
        var licenseVersion = 0;
        var levelReference = 0;
        var modelReference = 0;
        ConsumerFacts consumers = null;
        var counts = emptyCounts();
        try {
            var inputs = sources.captureMap(entry.logicalPath(), entry.realPath(), entry.sha256(), bytes);
            var source = inputs.getPackageBytes();
            fileVersion = source.fileVersion;
            licenseVersion = source.licenseVersion;
            stage = Stage.WORLD_MODEL;
            var selection = P152BspWorldModel.select(source, cancelled);
            levelReference = selection.getLevelReference();
            modelReference = selection.getModelReference();
            if (P152BspDormantCache.SOURCE_SHA256.equals(entry.sha256())) {
                stage = Stage.DORMANT_CONSUMERS;
                var dormant = P152BspDormantCache.capture(sources, inputs, cancelled);
                dormant.requireSource(source);
                dormantReceipts.add(dormant);
                var links = dormant.getDanglingLinkCounts();
                return createResult(
                        entry,
                        fileVersion,
                        licenseVersion,
                        levelReference,
                        modelReference,
                        Status.DORMANT_SAVED_MASKS_EXCLUDED,
                        Stage.COMPLETE,
                        dormant.getProfile()
                                + ": saved masks have no ordinary fresh runtime consumer; retained dangling"
                                + " placement/section atlas links="
                                + links.placementAtlasLinks()
                                + "/"
                                + links.sectionAtlasLinks()
                                + "; no link repair or strict receiver admission",
                        null,
                        placementCounts(dormant.getPlacementsCount()));
            }
            stage = Stage.RECEIVER_MODEL;
            P152FreshHashQuery.checkCancelled(cancelled);
            var model = P152BspReceiverModel.decode(source, modelReference);
            model.requireSource(source);
            stage = Stage.CONSUMERS;
            consumers = model.getConsumerFacts();
            counts = placementCounts(model.getPlacements().size());
            if (consumers.noSavedReceivers()) {
                return createResult(
                        entry,
                        fileVersion,
                        licenseVersion,
                        levelReference,
                        modelReference,
                        Status.EMPTY,
                        Stage.COMPLETE,
                        "Strict native Model has no saved receiver or atlas consumer",
                        consumers,
                        counts);
            }
            if (model.areOrphanResourcesExcluded()) {
                return createResult(
                        entry,
                        fileVersion,
                        licenseVersion,
                        levelReference,
                        modelReference,
                        Status.ORPHAN_RESOURCES_EXCLUDED,
                        Stage.COMPLETE,
                        "One retained non-Beast atlas has no placement, node, section or variant consumer; native"
                                + " section rebuilding preserves this absence",
                        consumers,
                        counts);
            }
            if (model.isBeastLighting()) {
                return createResult(
                        entry,
                        fileVersion,
                        licenseVersion,
                        levelReference,
                        modelReference,
                        Status.GAP,
                        Stage.BEAST_DEPENDENCY,
                        "Beast saved receivers require a separately proved bake input domain",
                        consumers,
                        counts);
            }
            stage = Stage.LIGHT_INPUTS;
            var lighting = P152BspLightingInputs.capture(inputs, model, levelReference, cancelled);
            P152BspReceiverExclusion.requireAtlasVariants(
                    model.getAtlasVariantCounts(), model.getPlacements(), directions.size());
            var nodes = new HashMap<Integer, List<Node>>();
            for (var placement : model.getPlacements()) {
                nodes.put(placement.index(), model.pointNodes(placement.surfaceIndex()));
            }
            stage = Stage.ASSOCIATIONS;
            var association = lighting.getLightAssociation(budget::spendNodeCheck, cancelled);
            stage = Stage.PROJECTIONS;
            var projection = evaluate(
                    model.getPlacements(),
                    lighting.getDomain(),
                    nodes,
                    association,
                    directions,
                    envelopes,
                    budget,
                    cancelled);
            return createResult(
                    entry,
                    fileVersion,
                    licenseVersion,
                    levelReference,
                    modelReference,
                    projection.excluded() ? Status.EXCLUDED : Status.GAP,
                    projection.excluded() ? Stage.COMPLETE : stage,
                    projection.reason(),
                    consumers,
                    projection.counts());
        } catch (IOException | IllegalArgumentException | ArithmeticException unsupported) {
            var reason = unsupported.getMessage();
            if (reason == null) {
                reason = unsupported.getClass().getSimpleName();
            }
            reason = reason.replace(
                            entry.logicalPath().toString(),
                            entry.logicalPath().getFileName().toString())
                    .replace(
                            entry.realPath().toString(),
                            entry.logicalPath().getFileName().toString())
                    .replace('\n', ' ')
                    .replace('\r', ' ');
            if (reason.length() > 512) {
                reason = reason.substring(0, 512);
            }
            return createResult(
                    entry,
                    fileVersion,
                    licenseVersion,
                    levelReference,
                    modelReference,
                    Status.GAP,
                    stage,
                    reason,
                    consumers,
                    counts);
        }
    }

    private static MapResult createResult(
            Entry entry,
            int fileVersion,
            int licenseVersion,
            int levelReference,
            int modelReference,
            Status status,
            Stage stage,
            String reason,
            ConsumerFacts consumers,
            Counts counts) {
        return new MapResult(
                entry.logicalPath().getFileName().toString(),
                entry.sha256(),
                fileVersion,
                licenseVersion,
                levelReference,
                modelReference,
                status,
                stage,
                reason,
                consumers,
                counts);
    }

    private static Counts emptyCounts() {
        return placementCounts(0);
    }

    private static Counts placementCounts(int placements) {
        return new Counts(placements, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, 0);
    }
}

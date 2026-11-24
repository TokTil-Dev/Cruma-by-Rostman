package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.P152BspReceiverModel.Receiver;
import com.rost.cruma.core.editing.P152EffectiveInputs.BspLightInputs;
import com.rost.cruma.core.editing.P152EffectiveInputs.Fact;
import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.P152EffectiveInputs.MapInputs;
import com.rost.cruma.core.editing.P152EffectiveInputs.Source;
import com.rost.cruma.core.world.NativeBspLightAssociation;
import com.rost.cruma.core.world.NativeBspLightAssociation.CandidateFilter;
import com.rost.cruma.core.world.NativeBspPointAdmission;
import com.rost.cruma.core.world.NativeBspPointAdmission.Node;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import com.rost.cruma.core.world.NativeBspSunAdmission;
import com.rost.cruma.core.world.NativeBspSunDirection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152BspLightingInputs {
    private final Source source;
    private final P152BspReceiverModel model;
    private final int modelReference;
    private final Domain domain;
    private final List<Input> receipt;

    private P152BspLightingInputs(Source source, P152BspReceiverModel model, Domain domain, List<Input> receipt) {
        this.source = source;
        this.model = model;
        this.modelReference = model.getModelReference();
        this.domain = domain;
        this.receipt = List.copyOf(receipt);
    }

    record Point(int reference, String objectPath, String classPath, Vector3 position, float authoredRadius) {}

    record Sun(int reference, String objectPath) {}

    record Zone(int number, int actorReference, Fact<Boolean> sunAffect) {}

    record Domain(List<Point> points, List<Sun> suns, List<Zone> zones, Map<Integer, Rotator> authoredSunRotations) {
        Domain {
            points = List.copyOf(points);
            suns = List.copyOf(suns);
            zones = List.copyOf(zones);
            authoredSunRotations = Map.copyOf(authoredSunRotations);
        }
    }

    interface Resolver {
        BspLightInputs getLight(int reference) throws IOException;

        Fact<Boolean> getZoneSunAffect(int reference) throws IOException;

        Fact<Rotator> getSunRotation(int reference) throws IOException;

        default int getLevelInfo() throws IOException {
            throw new IOException("BSP null zone requires its source-bound native Actors[0]");
        }
    }

    @FunctionalInterface
    interface CandidateVisit {
        void visit() throws IOException;
    }

    private record Candidate(Vector vector, float radius, boolean sun) {}

    static P152BspLightingInputs capture(
            MapInputs inputs, P152BspReceiverModel model, int levelReference, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(model);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var source = inputs.getPackageBytes();
        model.requireSource(source);
        var selection = P152BspWorldModel.select(source, cancelled);
        if (selection.getLevelReference() != levelReference
                || selection.getModelReference() != model.getModelReference()) {
            throw new IOException("BSP lighting Model differs from the native Level selection");
        }
        var domain = resolve(
                source,
                model.getNativeLightReferences(),
                model.getPlacements(),
                model.getZoneActorReferences(),
                levelReference,
                new Resolver() {
                    @Override
                    public BspLightInputs getLight(int reference) throws IOException {
                        selection.requireLight(reference);
                        return inputs.getBspLight(reference);
                    }

                    @Override
                    public Fact<Boolean> getZoneSunAffect(int reference) throws IOException {
                        selection.requireZone(reference);
                        return inputs.getBspZoneSunAffect(reference);
                    }

                    @Override
                    public Fact<Rotator> getSunRotation(int reference) throws IOException {
                        return inputs.getBspSunRotation(reference);
                    }

                    @Override
                    public int getLevelInfo() throws IOException {
                        return selection.getLevelInfo();
                    }
                },
                cancelled);
        var receipt = new ArrayList<>(inputs.getReceipt());
        selection.requireSource(source);
        receipt.add(new Input(source.packageName + ".unr", inputs.getSourceSha256()));
        P152FreshHashQuery.checkCancelled(cancelled);
        return new P152BspLightingInputs(inputs.getMapIdentity(), model, domain, receipt);
    }

    static CandidateFilter candidateFilter(
            Domain domain, List<Node> indexedNodes, CandidateVisit visit, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(visit);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var nodes = List.copyOf(indexedNodes);
        var candidates = new HashMap<Integer, Candidate>();
        for (var point : domain.points()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var candidate = new Candidate(
                    new Vector(
                            point.position().x(),
                            point.position().y(),
                            point.position().z()),
                    point.authoredRadius(),
                    false);
            if (candidates.putIfAbsent(point.reference(), candidate) != null) {
                throw new IOException("Duplicate BSP point source in candidate domain");
            }
        }
        for (var sun : domain.suns()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var rotation = domain.authoredSunRotations().get(sun.reference());
            if (rotation == null) {
                throw new IOException("BSP candidate sunlight lacks initial authored rotation");
            }
            var candidate = new Candidate(
                    NativeBspSunDirection.rawTowardFromRotation(rotation.pitch(), rotation.yaw()), 0, true);
            if (candidates.putIfAbsent(sun.reference(), candidate) != null) {
                throw new IOException("Duplicate BSP sunlight source in candidate domain");
            }
        }
        var frozenCandidates = Map.copyOf(candidates);
        P152FreshHashQuery.checkCancelled(cancelled);
        return (nodeIndex, reference) -> {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
                throw new IOException("BSP candidate node is outside the frozen geometry domain");
            }
            var candidate = frozenCandidates.get(reference);
            if (candidate == null) {
                throw new IOException("BSP candidate source is absent from the complete light domain");
            }
            visit.visit();
            var node = nodes.get(nodeIndex);
            var admitted = candidate.sun()
                    ? NativeBspSunAdmission.mayReceive(node, candidate.vector())
                    : NativeBspPointAdmission.mayReceive(node, candidate.vector(), candidate.radius());
            P152FreshHashQuery.checkCancelled(cancelled);
            return admitted;
        };
    }

    static Domain resolve(
            PackageBytes source,
            List<Integer> nativeLights,
            List<Receiver> placements,
            List<Integer> zoneReferences,
            int levelReference,
            Resolver resolver,
            BooleanSupplier cancelled)
            throws IOException {
        if (zoneReferences.size() != 64) {
            throw new IOException("BSP lighting requires the fixed native 64-slot zone array");
        }
        var references = new LinkedHashSet<Integer>();
        for (var reference : nativeLights) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (reference != 0) {
                requireLocalReference(source, reference, "native Model light");
                references.add(reference);
            }
        }
        var zones = new ArrayList<Zone>();
        var seenZones = new LinkedHashSet<Integer>();
        for (var placement : placements) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (placement.levelReference() != levelReference) {
                throw new IOException("BSP placement belongs to a different native Level");
            }
            for (var reference : placement.lightReferences()) {
                if (!references.contains(reference)) {
                    throw new IOException("Saved BSP light is absent from the native Model light domain: " + reference);
                }
            }
            if (placement.zone() < 0 || placement.zone() >= 64) {
                throw new IOException("BSP placement has an invalid zone slot");
            }
            if (seenZones.add(placement.zone())) {
                var reference = zoneReferences.get(placement.zone());
                if (reference == 0) {
                    reference = resolver.getLevelInfo();
                }
                requireLocalReference(source, reference, "BSP placement zone");
                zones.add(new Zone(placement.zone(), reference, resolver.getZoneSunAffect(reference)));
            }
        }
        var points = new ArrayList<Point>();
        var suns = new ArrayList<Sun>();
        var rotations = new LinkedHashMap<Integer, Rotator>();
        for (var reference : references) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var light = resolver.getLight(reference);
            var path = source.getObjectPath(reference);
            if (light.movableSun()) {
                if (light.effect().value() != 19) {
                    throw new IOException("BSP movable sunlight effect is unaudited: " + path);
                }
                suns.add(new Sun(reference, path));
                rotations.put(reference, resolver.getSunRotation(reference).value());
            } else {
                if (light.effect().value() == 19
                        || light.location() == null
                        || light.radius() == null
                        || !Float.isFinite(light.radius().value())
                        || light.radius().value() < 0) {
                    throw new IOException("BSP point light projection is unaudited: " + path);
                }
                points.add(new Point(
                        reference,
                        path,
                        light.classPath(),
                        light.location().value(),
                        light.radius().value()));
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Domain(points, suns, zones, rotations);
    }

    NativeBspLightAssociation getLightAssociation(CandidateVisit visit, BooleanSupplier cancelled) throws IOException {
        return model.getLightAssociation(
                candidateFilter(domain, model.getPointNodesByIndex(), visit, cancelled), cancelled);
    }

    Domain getDomain() {
        return domain;
    }

    List<Input> getReceipt() {
        return receipt;
    }

    String getMapSha256() {
        return source.sha256();
    }

    int getModelReference() {
        return modelReference;
    }

    void verifyMapUnchanged() throws IOException {
        source.verifyUnchanged();
    }

    private static void requireLocalReference(PackageBytes source, int reference, String role) throws IOException {
        if (reference <= 0 || reference > source.exports.size()) {
            throw new IOException(role + " requires a local export: " + reference);
        }
        P152EffectiveInputs.localBspActor(source, reference);
    }
}

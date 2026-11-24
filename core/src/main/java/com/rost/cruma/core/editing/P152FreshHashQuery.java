package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeActorIndex.HashEnd;
import com.rost.cruma.core.editing.NativeActorIndex.HashVisit;
import com.rost.cruma.core.editing.NativeLevelShadowQuery.Result;
import com.rost.cruma.core.editing.NativeLevelShadowQuery.State;
import com.rost.cruma.core.editing.P152ActorDomain.Disposition;
import com.rost.cruma.core.editing.P152ActorDomain.Identity;
import com.rost.cruma.core.editing.P152ActorDomain.Report;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class P152FreshHashQuery {
    private static final int MAX_RAYS = 65536;
    private static final String PROFILE = "P152_FRESH_EDITOR_HASH_V1";
    private final P152FreshBakeContext context;
    private final long expectedMeshes;

    private P152FreshHashQuery(P152FreshBakeContext context) {
        this.context = context;
        expectedMeshes = context.getActorDomain().getReport().actors().stream()
                .filter(actor -> actor.disposition() == Disposition.CAPTURED_MESH)
                .count();
    }

    public record Ray(Vector start, Vector end) {
        public Ray {
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
        }
    }

    public record Witness(Identity actor, Identity asset, HashVisit hashVisit) {}

    public record RayResult(Ray ray, Result result, List<Witness> witnesses) {
        public RayResult {
            witnesses = List.copyOf(witnesses);
        }
    }

    public record Evaluation(String mapSha256, String context, List<RayResult> rays) {
        public Evaluation {
            rays = List.copyOf(rays);
        }
    }

    public static P152FreshHashQuery capture(Path clientRoot, Path map) throws IOException {
        return new P152FreshHashQuery(P152FreshBakeContext.capture(clientRoot, map));
    }

    static P152FreshHashQuery capture(P152FreshBakeContext context) {
        return new P152FreshHashQuery(Objects.requireNonNull(context));
    }

    static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Fresh hash query was cancelled");
        }
    }

    static void validate(Ray ray) {
        var a = ray.start();
        var b = ray.end();
        var dx = b.x() - a.x();
        var dy = b.y() - a.y();
        var dz = b.z() - a.z();
        var square = dx * dx + dy * dy + dz * dz;
        if (!Float.isFinite(a.x())
                || !Float.isFinite(a.y())
                || !Float.isFinite(a.z())
                || !Float.isFinite(b.x())
                || !Float.isFinite(b.y())
                || !Float.isFinite(b.z())
                || !Float.isFinite(square)
                || square == 0) {
            throw new IllegalArgumentException("Fresh hash rays must be finite nonzero segments");
        }
    }

    public Report getActorReport() {
        return context.getActorDomain().getReport();
    }

    public List<String> getGaps() {
        return context.getGaps();
    }

    public void verifySourcesUnchanged() throws IOException {
        context.verifySourcesUnchanged();
    }

    public Evaluation evaluate(List<Ray> rays) throws IOException {
        return evaluate(rays, () -> false);
    }

    public Evaluation evaluate(List<Ray> rays, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        if (rays.isEmpty() || rays.size() > MAX_RAYS) {
            throw new IllegalArgumentException("Fresh hash query requires one bounded finite ray set");
        }
        var frozen = List.copyOf(rays);
        for (var ray : frozen) {
            validate(ray);
        }
        checkCancelled(cancelled);
        context.verifySourcesUnchanged(cancelled);
        var results = new ArrayList<RayResult>(frozen.size());
        for (var ray : frozen) {
            checkCancelled(cancelled);
            results.add(evaluateCaptured(ray));
        }
        checkCancelled(cancelled);
        context.verifySourcesUnchanged(cancelled);
        checkCancelled(cancelled);
        return new Evaluation(context.getMapSha256(), PROFILE, results);
    }

    RayResult evaluateCaptured(Ray ray) {
        if (!context.getGaps().isEmpty()) {
            return unqualifiedResult(ray, context.getGaps().getFirst());
        }
        var query = context.getActorDomain().query(ray.start(), ray.end());
        if (query.testedMeshes() != expectedMeshes
                || query.meshMisses() + query.geometricHits().size() != query.testedMeshes()) {
            return unqualifiedResult(ray, "At least one native primitive query is incomplete");
        }
        if (query.geometricHits().isEmpty()) {
            return new RayResult(
                    ray, new Result(State.CLEAR, "Every registered native primitive is excluded or misses"), List.of());
        }
        for (var hit : query.geometricHits()) {
            if (!hit.bounds().simpleModelUnionCaptured()) {
                continue;
            }
            try {
                var visit = NativeActorIndex.hashVisit(hit.bounds().transformedSavedBox(), ray.start(), ray.end());
                if (visit.end() == HashEnd.BOX_CELL) {
                    return new RayResult(
                            ray,
                            new Result(State.BLOCKED, "An admitted native primitive blocks a visited hash cell"),
                            List.of(new Witness(hit.actor(), hit.asset(), visit)));
                }
            } catch (IllegalArgumentException unsupportedArithmetic) {
            }
        }
        return unqualifiedResult(ray, "Geometric hits have no complete positive native hash-cell witness");
    }

    private static RayResult unqualifiedResult(Ray ray, String reason) {
        return new RayResult(ray, new Result(State.UNQUALIFIED, reason), List.of());
    }
}

package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeLevelShadowQuery.Result;
import com.rost.cruma.core.editing.NativeLevelShadowQuery.State;
import com.rost.cruma.core.editing.P152ActorDomain.Report;
import com.rost.cruma.core.editing.P152FreshHashQuery.Ray;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class P152FreshLevelQuery {
    private static final int MAX_RAYS = 65536;
    private final P152FreshBakeContext context;
    private final P152FreshBspModel model;
    private final NativeLevelShadowQuery query;

    private P152FreshLevelQuery(P152FreshBakeContext context, P152FreshBspModel model) {
        this.context = context;
        this.model = model;
        var actors = P152FreshHashQuery.capture(context);
        query = new NativeLevelShadowQuery(model.getTrace(), false, List.of(), List.of((start, end, flags) -> {
            if (flags != 0x597) {
                throw new IllegalArgumentException("Fresh actor context requires native flags 0x597");
            }
            return actors.evaluateCaptured(new Ray(start, end)).result();
        }));
    }

    public record RayResult(Ray ray, Result result) {}

    public record Evaluation(String mapSha256, List<RayResult> rays) {
        public Evaluation {
            rays = List.copyOf(rays);
        }
    }

    public static P152FreshLevelQuery capture(Path clientRoot, Path map) throws IOException {
        return capture(clientRoot, map, () -> false);
    }

    public static P152FreshLevelQuery capture(Path clientRoot, Path map, BooleanSupplier cancelled) throws IOException {
        var context = P152FreshBakeContext.capture(clientRoot, map, cancelled);
        return capture(context, P152FreshBspModel.capture(context, cancelled), cancelled);
    }

    static P152FreshLevelQuery capture(P152FreshBakeContext context, P152FreshBspModel model) throws IOException {
        return capture(context, model, () -> false);
    }

    static P152FreshLevelQuery capture(P152FreshBakeContext context, P152FreshBspModel model, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(model);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!context.getMapPath().equals(model.getMapPath())
                || !context.getMapSha256().equals(model.getMapSha256())
                || context.getModelReference() != model.getIdentity().reference()) {
            throw new IOException("Frozen world Model differs from the fresh Level source");
        }
        context.verifySourcesUnchanged(cancelled);
        var result = new P152FreshLevelQuery(context, model);
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    public String getMapSha256() {
        return context.getMapSha256();
    }

    public Report getActorReport() {
        return context.getActorDomain().getReport();
    }

    public List<String> getGaps() {
        return context.getGaps();
    }

    public void verifySourcesUnchanged() throws IOException {
        verifySourcesUnchanged(() -> false);
    }

    public void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        context.verifySourcesUnchanged(cancelled);
    }

    public Evaluation evaluate(List<Ray> rays) throws IOException {
        return evaluate(rays, () -> false);
    }

    public Evaluation evaluate(List<Ray> rays, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        if (rays.isEmpty() || rays.size() > MAX_RAYS) {
            throw new IllegalArgumentException("Fresh Level query requires one bounded finite ray set");
        }
        var frozen = List.copyOf(rays);
        for (var ray : frozen) {
            P152FreshHashQuery.validate(ray);
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        context.verifySourcesUnchanged(cancelled);
        var results = new ArrayList<RayResult>(frozen.size());
        for (var ray : frozen) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var result = context.getGaps().isEmpty()
                    ? query.check(ray.start(), ray.end(), 0x197)
                    : new Result(State.UNQUALIFIED, context.getGaps().getFirst());
            results.add(new RayResult(ray, result));
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        context.verifySourcesUnchanged(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Evaluation(context.getMapSha256(), results);
    }

    P152FreshBakeContext getContext() {
        return context;
    }

    P152FreshBspModel getModel() {
        return model;
    }
}

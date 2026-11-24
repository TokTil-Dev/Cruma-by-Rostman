package com.rost.cruma.viewer.geodata;

import com.rost.cruma.viewer.geodata.GeoScenarioRunner.Context;
import com.rost.cruma.viewer.geodata.GeoScenarioRunner.Result;
import com.rost.cruma.viewer.geodata.GeoScenarioRunner.Verdict;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class GeoScenarioSuiteRunner {
    private GeoScenarioSuiteRunner() {}

    @FunctionalInterface
    public interface SourceFactory {
        Source open(GeoScenario scenario, BooleanSupplier cancelled) throws Exception;
    }

    public record Source(Context context, AutoCloseable resource) implements AutoCloseable {
        public Source(Context context) {
            this(context, null);
        }

        @Override
        public void close() throws Exception {
            if (resource != null) {
                resource.close();
            }
        }
    }

    public record Case(GeoScenario scenario, Result result) {}

    public record Report(List<Case> cases, Map<Verdict, Integer> counts) {
        public Report {
            cases = List.copyOf(cases);
            counts = Map.copyOf(counts);
        }

        public boolean hasPassed() {
            return !cases.isEmpty() && counts.getOrDefault(Verdict.PASS, 0) == cases.size();
        }
    }

    public static Report run(
            List<GeoScenario> cases,
            SourceFactory sources,
            int repetitions,
            boolean allowChanged,
            BooleanSupplier cancelled) {
        if (cases.isEmpty() || cases.size() > 256 || repetitions < 1 || repetitions > 100) {
            throw new IllegalArgumentException("Suite requires 1..256 cases and 1..100 repetitions");
        }
        var ids = new HashSet<String>();
        for (var scenario : cases) {
            if (!ids.add(scenario.id())) {
                throw new IllegalArgumentException("Duplicate scenario identifier: " + scenario.id());
            }
        }
        var completed = new ArrayList<Case>();
        var counts = new EnumMap<Verdict, Integer>(Verdict.class);
        for (var scenario : List.copyOf(cases)) {
            Result result;
            if (cancelled.getAsBoolean()) {
                result = unavailableResult(scenario, Verdict.CANCELLED, "Suite cancelled before this case ran");
            } else {
                try (var source = sources.open(scenario, cancelled)) {
                    var context = source == null ? null : source.context();
                    result =
                            new GeoScenarioRunner(alias -> context).run(scenario, repetitions, allowChanged, cancelled);
                } catch (Exception failure) {
                    result = unavailableResult(
                            scenario,
                            cancelled.getAsBoolean() ? Verdict.CANCELLED : Verdict.UNAVAILABLE,
                            "Local source could not be prepared ("
                                    + failure.getClass().getSimpleName() + ")");
                }
            }
            completed.add(new Case(scenario, result));
            counts.merge(result.verdict(), 1, Integer::sum);
        }
        return new Report(completed, counts);
    }

    private static Result unavailableResult(GeoScenario scenario, Verdict verdict, String message) {
        return new Result(scenario.id(), verdict, List.of(), message, false);
    }
}

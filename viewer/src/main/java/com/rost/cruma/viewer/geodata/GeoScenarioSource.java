package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataRepository;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoEditDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public record GeoScenarioSource(GeoDataAccess data, GeoDataSnapshot fingerprints, boolean editedPreview) {
    public GeoScenarioSource {
        Objects.requireNonNull(data);
        Objects.requireNonNull(fingerprints);
    }

    public static GeoScenarioSource prepare(
            GeoDataSnapshot original, GeoDataAccess frozenView, GeoEditDocument document) throws IOException {
        if (document == null) {
            return new GeoScenarioSource(original, original, false);
        }
        var revision = frozenView.revision();
        if (document.revision() != revision) {
            throw new IOException("Edits changed before case capture. Capture again.");
        }
        var materialized = document.getMaterializedSnapshot();
        synchronized (document) {
            if (materialized.revision() != revision || document.revision() != revision) {
                throw new IOException("Edits changed while preparing case fingerprints. Capture again.");
            }
            return new GeoScenarioSource(frozenView, materialized, document.isModified());
        }
    }

    public static GeoScenarioSource loadOriginal(Path path, GeoScenario saved, BooleanSupplier cancelled)
            throws IOException {
        var repository = new GeoDataRepository(
                path, Math.max(4, saved.fingerprints().regions().size()), 512L * 1024 * 1024);
        for (var region : saved.fingerprints().regions().keySet()) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("Cancelled");
            }
            var coordinates = region.split("_");
            repository.loadRegion(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]));
        }
        var snapshot = repository.snapshot();
        if (saved.fingerprints().regions().isEmpty()) {
            snapshot = repository.loadBounds(
                    (int) saved.a().point().x() - saved.profile().radius(),
                    (int) saved.a().point().y() - saved.profile().radius(),
                    (int) saved.a().point().x() + saved.profile().radius(),
                    (int) saved.a().point().y() + saved.profile().radius());
        }
        return new GeoScenarioSource(snapshot, snapshot, false);
    }
}

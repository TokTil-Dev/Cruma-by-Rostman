package com.rost.cruma.viewer.launch;

import com.rost.cruma.core.export.ExportScale;
import com.rost.cruma.core.export.LocationExport;
import com.rost.cruma.core.export.LocationExport.Area;
import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.export.LocationExport.Result;
import com.rost.cruma.core.export.LocationExport.Settings;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.render.bgfx.ParticleStill;
import com.rost.cruma.viewer.spawn.SpawnPopulation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class LocationExportRun {
    public static final int FIRST_TERRAIN_SIZE = 1;

    private LocationExportRun() {}

    public static Settings settings(
            int terrainSize,
            boolean grass,
            boolean npcs,
            @Nullable Area area,
            int hour,
            ExportScale scale,
            boolean clientLight,
            boolean effects,
            boolean movingEffects,
            boolean sounds,
            boolean music,
            MapEventState events) {
        return new Settings(
                terrainSize,
                grass,
                npcs,
                area,
                hour,
                scale,
                clientLight,
                effects,
                effects && movingEffects,
                sounds,
                effects && !events.isEmpty() ? events.describe() : "",
                events.zoneStates(),
                music,
                events.clocks());
    }

    public static Result tiles(
            Path client,
            WorldVariant world,
            List<String> tiles,
            Path parent,
            Settings settings,
            boolean replace,
            MapEventState events,
            @Nullable Path scripts,
            @Nullable Path geodata,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        SpawnPopulation population = null;
        try {
            if (scripts != null) {
                progress.update("Reading npcpos.txt and npcdata.txt", 0);
                population = SpawnPopulation.open(client, world, scripts, geodata);
            }
            return LocationExport.exportTiles(
                    client,
                    world,
                    tiles,
                    parent,
                    settings,
                    replace,
                    population,
                    new ParticleStill(events, settings.hour()).litBy(client),
                    progress,
                    cancelled);
        } finally {
            if (population != null) {
                population.close();
            }
        }
    }

    public static String describeMapEvents(MapEventState events, boolean effects, boolean sounds) {
        if (effects) {
            return events.isEmpty() ? "" : events.describe();
        }
        var parts = new ArrayList<String>();
        if (sounds && !events.clocks().isEmpty()) {
            parts.add("sounds by time of day: " + events.clockWords());
        }
        if (!events.zoneStates().isEmpty()) {
            parts.add(events.zoneStates().describe());
        }
        return String.join("; ", parts);
    }
}

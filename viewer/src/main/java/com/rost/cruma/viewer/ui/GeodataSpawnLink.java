package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.geodata.GeodataEditReview.SpawnCheck;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public interface GeodataSpawnLink {
    GeodataSpawnLink NONE = new GeodataSpawnLink() {
        @Override
        public boolean areSpawnsReady() {
            return false;
        }

        @Override
        public List<SpawnMark> findSpawnMarks(double minX, double minY, double maxX, double maxY, int limit) {
            return List.of();
        }

        @Override
        public @Nullable SpawnMark getSelectedSpawn() {
            return null;
        }

        @Override
        public @Nullable SpawnCheck getSpawnCheck() {
            return null;
        }
    };

    default @Nullable Path getScriptFolder() {
        return null;
    }

    record SpawnMark(double x, double y, double z, int argb, String npc, String maker, boolean fixed, int radius) {}

    boolean areSpawnsReady();

    List<SpawnMark> findSpawnMarks(double minX, double minY, double maxX, double maxY, int limit);

    @Nullable
    SpawnMark getSelectedSpawn();

    @Nullable
    SpawnCheck getSpawnCheck();
}

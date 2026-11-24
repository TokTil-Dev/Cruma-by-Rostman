package com.rost.cruma.viewer.ui;

import java.util.Set;
import org.jetbrains.annotations.Nullable;

public interface NpcSpawnLink {
    NpcSpawnLink NONE = new NpcSpawnLink() {
        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public @Nullable Nearest findNearestSpawn(String npc) {
            return null;
        }

        @Override
        public Summary getSummary(String npc) {
            return Summary.NONE;
        }

        @Override
        public Set<String> getSpawnedNames() {
            return Set.of();
        }

        @Override
        public boolean flyTo(String npc) {
            return false;
        }

        @Override
        public boolean hasUnsavedSpawns() {
            return false;
        }
    };

    record Nearest(double distance, double x, double y, double z, boolean loaded) {}

    record Summary(int entries, int npcs, double killsPerHour, String respawn) {
        public static final Summary NONE = new Summary(0, 0, 0, "");
    }

    boolean isReady();

    @Nullable
    Nearest findNearestSpawn(String npc);

    Summary getSummary(String npc);

    Set<String> getSpawnedNames();

    boolean flyTo(String npc);

    boolean hasUnsavedSpawns();

    enum HeldItems {
        UNKNOWN,
        WORN,
        PART_OF_MESH
    }

    default HeldItems getHeldItems(int npcId) {
        return HeldItems.UNKNOWN;
    }
}

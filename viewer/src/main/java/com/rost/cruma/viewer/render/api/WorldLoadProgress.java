package com.rost.cruma.viewer.render.api;

public final class WorldLoadProgress {
    public int tilesLoaded;
    public int tilesTotal;
    public String tile = "";
    public WorldLoadStage stage = WorldLoadStage.INDEXING;
    public long startedNanos;
    public long etaSeconds = -1L;
    public boolean active;
    public boolean reloading;
    public boolean reloadingForEditing;
    public int tilesReloaded;
    public int tilesToReload;

    public enum WorldLoadStage {
        INDEXING,
        ASSEMBLING,
        UPLOADING,
        FINALIZING,
        READY
    }
}

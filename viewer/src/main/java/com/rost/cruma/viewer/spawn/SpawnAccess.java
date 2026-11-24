package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.spawn.NpcGrpTable.Appearance;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.viewer.npc.NpcDataSource;
import com.rost.cruma.viewer.spawn.SpawnModels.Status;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public interface SpawnAccess {
    record Look(Status status, @Nullable Appearance appearance) {}

    SpawnSource getSource();

    NpcDataSource getNpcData();

    boolean isHeld();

    boolean isNpcHeld();

    List<String> getBookmarks();

    boolean bookmark(SpawnMaker maker, boolean on);

    boolean hasGeodata();

    @Nullable
    GeoDataAccess readArea(double minX, double minY, double maxX, double maxY);

    double findClientFloor(double x, double y, double z);

    @Nullable
    Look look(int npcId);
}

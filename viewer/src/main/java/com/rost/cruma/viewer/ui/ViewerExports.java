package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.ui.LocationExportDialog.Place;
import java.util.List;

public final class ViewerExports implements AutoCloseable {
    private final LocationExportDialog location;
    private final NpcExportDialog npc;
    private final World world;
    private final List<Location> locations;
    private final ViewerWorldMap map;

    public ViewerExports(
            World world,
            List<Location> locations,
            ViewerWorldMap map,
            ViewerUiRenderer renderer,
            ExportSettings settings,
            MapEventChoices mapEvents) {
        this.world = world;
        this.locations = locations;
        this.map = map;
        location = new LocationExportDialog(settings, mapEvents);
        npc = new NpcExportDialog(settings);
        npc.setRenderer(renderer);
    }

    public void openArea(float x, float y, int hour) {
        var installation = map.getInstallation();
        if (installation != null) {
            location.openArea(installation, x, y, scripts(), geodata(), places(), hour);
        }
    }

    public void openTile(String tile, int hour) {
        var installation = map.getInstallation();
        if (installation != null) {
            location.openTile(installation, tile, scripts(), geodata(), places(), hour);
        }
    }

    public void openMap(String namedMap, int hour) {
        var installation = map.getInstallation();
        if (installation != null) {
            location.openMap(installation, namedMap, hour);
        }
    }

    public void openNpc(int npcId) {
        var installation = map.getInstallation();
        if (installation != null) {
            npc.openNpc(installation, scripts(), npcId);
        }
    }

    public void openNpcList() {
        var installation = map.getInstallation();
        if (installation != null) {
            npc.open(installation, scripts());
        }
    }

    public boolean isOpen() {
        return location.isOpen() || npc.isOpen();
    }

    public void draw(FontManager fonts) {
        npc.draw(fonts);
        location.draw(fonts, location.isOpen() ? map.getPreviewTexture() : 0);
    }

    @Override
    public void close() {
        npc.release();
    }

    private String scripts() {
        var spawns = world.spawns();
        return spawns == null || spawns.scriptPath() == null
                ? ""
                : spawns.scriptPath().toString();
    }

    private String geodata() {
        var geodata = world.geodata();
        return geodata == null || geodata.path() == null ? "" : geodata.path().toString();
    }

    private List<Place> places() {
        return locations.stream()
                .map(place -> new Place(place.name(), place.x(), place.y()))
                .toList();
    }
}

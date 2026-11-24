package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.export.LocationExport.Area;
import com.rost.cruma.core.export.LocationExport.Figure;
import com.rost.cruma.core.export.LocationExport.Population;
import com.rost.cruma.core.export.NpcExport;
import com.rost.cruma.core.export.NpcExport.Npc;
import com.rost.cruma.core.geodata.GeoDataRepository;
import com.rost.cruma.core.spawn.ItemNames;
import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcPosFile;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.core.spawn.SpawnNpc;
import com.rost.cruma.core.spawn.SpawnSampler.Candidates;
import com.rost.cruma.core.spawn.SpawnTerritory;
import com.rost.cruma.core.world.NpcModel;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.geodata.ClientFloorGrid;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.spawn.SpawnGround.CandidateKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class SpawnPopulation implements Population, AutoCloseable {
    private static final Pattern TILE = Pattern.compile("(\\d+)_(\\d+)");

    private static final long SEED = 1;

    private final NpcPosFile positions;
    private final NpcDataTable npcs;
    private final @Nullable GeoDataRepository geodata;
    private final SpawnModels models;
    private final Map<Integer, Npc> clientNpcs = new HashMap<>();
    private final Map<Integer, Optional<NpcModel>> posed = new HashMap<>();
    private final PlacelessSpawns placeless = new PlacelessSpawns();

    private SpawnPopulation(
            NpcPosFile positions,
            NpcDataTable npcs,
            @Nullable GeoDataRepository geodata,
            SpawnModels models,
            List<Npc> clientNpcs) {
        this.positions = positions;
        this.npcs = npcs;
        this.geodata = geodata;
        this.models = models;
        for (var npc : clientNpcs) {
            this.clientNpcs.put(npc.id(), npc);
        }
    }

    public static SpawnPopulation open(Path client, WorldVariant variant, Path scripts, @Nullable Path geodata)
            throws IOException {
        var folder = SpawnSource.resolveFolder(scripts);
        var positionsFile = folder == null ? null : SpawnSource.find(folder, SpawnSource.POSITIONS);
        var npcFile = folder == null ? null : SpawnSource.find(folder, SpawnSource.NPCS);
        if (positionsFile == null || npcFile == null) {
            throw new IOException(
                    "The script folder needs " + SpawnSource.POSITIONS + " and " + SpawnSource.NPCS + ".");
        }
        var npcs = NpcDataTable.read(npcFile);
        var items = ItemNames.empty();
        var itemFile = SpawnSource.find(folder, "item_pch.txt");
        if (itemFile != null) {
            try {
                items = ItemNames.read(itemFile);
            } catch (IOException | RuntimeException unreadable) {
            }
        }
        var positions = NpcPosFile.read(positionsFile);
        var clientNpcs = NpcExport.list(client, variant);
        var repository = geodata != null && Files.isDirectory(geodata)
                ? new GeoDataRepository(geodata, 12, 512L * 1024 * 1024)
                : null;
        var models = new SpawnModels(client, variant);
        models.setScript(npcs, items);
        return new SpawnPopulation(positions, npcs, repository, models, clientNpcs);
    }

    @Override
    public List<Figure> collectFigures(UMap map, BooleanSupplier cancelled) {
        var matcher = TILE.matcher(map.name());
        if (!matcher.matches()) {
            return List.of();
        }
        var size = WorldDimensions.TILE_SIZE;
        var minX = (Integer.parseInt(matcher.group(1)) - WorldDimensions.TILE_ZERO_COORD_X) * size;
        var minY = (Integer.parseInt(matcher.group(2)) - WorldDimensions.TILE_ZERO_COORD_Y) * size;
        var floors = new ClientFloors();
        floors.put(ClientFloorGrid.build(map, cancelled));
        var result = new ArrayList<Figure>();
        for (var maker : positions.getMakers()) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            if (maker.isConditional()) {
                continue;
            }
            if (geodata == null) {
                placeless.add(
                        maker,
                        findTerritories(maker.territoryNames()),
                        findTerritories(maker.bannedTerritoryNames()),
                        minX,
                        minY,
                        size);
            }
            for (var placed : SpawnPreview.place(
                    maker, findCandidates(maker, minX, minY, size, cancelled), SEED + maker.index())) {
                if (placed.x() < minX || placed.x() >= minX + size || placed.y() < minY || placed.y() >= minY + size) {
                    continue;
                }
                var info = npcs.getNpc(placed.npcName());
                var npc = info == null ? null : clientNpcs.get(info.id());
                var model = npc == null ? null : posedModel(info.id());
                if (model == null) {
                    continue;
                }
                var ground = floors.getRestHeight(
                        placed.x(), placed.y(), placed.z(), info.collisionRadius(), info.collisionHeight());
                if (Double.isNaN(ground)) {
                    ground = placed.z();
                }
                var scale = npc.drawScale() > 0 ? npc.drawScale() : 1f;
                var base = ground + info.collisionHeight() - model.lowest() * scale;
                result.add(new Figure(
                        npc,
                        model,
                        placed.x(),
                        placed.y(),
                        base,
                        placed.heading() * Math.PI * 2 / 65536,
                        models.getOutfit(info.id())));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public int withoutPlace(@Nullable Area area) {
        return placeless.count(area);
    }

    @Override
    public void close() {
        models.close();
    }

    private @Nullable Candidates findCandidates(
            SpawnMaker maker, int minX, int minY, int size, BooleanSupplier cancelled) {
        if (geodata == null || maker.npcs().stream().noneMatch(SpawnNpc::isAnywhere)) {
            return null;
        }
        var territories = findTerritories(maker.territoryNames());
        if (!PlacelessSpawns.meets(territories, minX, minY, size)) {
            return null;
        }
        var key = new CandidateKey(
                territories, findTerritories(maker.bannedTerritoryNames()), maker.maximumNpc(), maker.flying());
        return SpawnGround.computeCandidates(geodata, key, cancelled);
    }

    private List<SpawnTerritory> findTerritories(List<String> names) {
        var result = new ArrayList<SpawnTerritory>();
        for (var name : names) {
            var territory = positions.getTerritory(name);
            if (territory != null) {
                result.add(territory);
            }
        }
        return result;
    }

    private @Nullable NpcModel posedModel(int id) {
        return posed.computeIfAbsent(id, ignored -> {
                    try {
                        return Optional.ofNullable(models.readPosed(id));
                    } catch (RuntimeException | LinkageError unreadable) {
                        return Optional.empty();
                    }
                })
                .orElse(null);
    }
}

package com.rost.cruma.core.audio;

import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableReport.State;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.unreal.L2DataFile;
import com.rost.cruma.core.unreal.L2TextTable;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.jetbrains.annotations.Nullable;

public final class MusicTable {
    private static final String FILE = "MusicInfo.dat";

    private final IntObjectMap<List<String>> playlists;

    private MusicTable(IntObjectMap<List<String>> playlists) {
        this.playlists = playlists;
    }

    public static MusicTable load(Path clientPath) throws IOException {
        return load(clientPath, WorldVariant.MAIN);
    }

    public static MusicTable load(Path clientPath, WorldVariant variant) throws IOException {
        var system = clientPath.resolve("system");
        var file = TableFiles.find(system, variant, "musicinfo.dat");
        if (file == null) {
            var text = variant.find(system, "musicinfo.txt");
            if (text != null) {
                return parseText(L2TextTable.read(text));
            }
        }
        var names = TableFiles.find(system, variant, "L2GameDataName.dat");
        var table = file == null ? system.resolve("musicinfo.dat") : file;
        return read(
                table,
                L2DataFile.read(table),
                names == null ? List.of() : ClientCatalog.gameDataNames(L2DataFile.read(names)),
                TableSchemas.getClientFolder(system));
    }

    static MusicTable parse(byte[] payload, List<String> names) {
        return read(Path.of(FILE), payload, names, null);
    }

    static MusicTable parseText(List<Map<String, String>> records) {
        var playlists = new IntObjectHashMap<List<String>>(records.size());
        for (var record : records) {
            var id = L2TextTable.integer(record, "id", -1);
            if (id >= 0) {
                playlists.put(id, List.copyOf(L2TextTable.names(record.getOrDefault("title", ""))));
            }
        }
        return new MusicTable(playlists);
    }

    public static MusicTable parse(byte[] payload) {
        return parse(payload, List.of());
    }

    static MusicTable fromRows(List<Map<String, Object>> rows, boolean numbered) {
        var playlists = new IntObjectHashMap<List<String>>(rows.size());
        for (var row : rows) {
            if (!(row.get("id") instanceof Integer id)) {
                continue;
            }
            var tracks = new ArrayList<String>();
            if (row.get("tracks") instanceof List<?> written) {
                for (var track : written) {
                    var name = String.valueOf(track);
                    if (numbered ? !name.isBlank() : !name.trim().isEmpty()) {
                        tracks.add(name.trim());
                    }
                }
            }
            playlists.put(id, List.copyOf(tracks));
        }
        return new MusicTable(playlists);
    }

    public List<String> getPlaylist(int musicId) {
        var tracks = playlists.get(musicId);
        return tracks == null ? List.of() : tracks;
    }

    public int size() {
        return playlists.size();
    }

    private static MusicTable read(Path file, byte[] payload, List<String> names, @Nullable Path clientSchemas) {
        var table = TableSchemas.read(Tables.MUSIC, file, payload, names, clientSchemas);
        var report = table.report();
        if (report.state() == State.NOT_READ) {
            var reasons = new ArrayList<String>();
            for (var attempt : report.attempts()) {
                reasons.add(attempt.title() + ": " + attempt.reason());
            }
            throw new IllegalArgumentException(file.getFileName() + " fits no schema: " + String.join("; ", reasons));
        }
        return fromRows(table.rows(), table.schema() != null && table.schema().needsGameDataNames());
    }
}

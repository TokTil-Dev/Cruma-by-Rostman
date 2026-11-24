package com.rost.cruma.core.export;

import com.rost.cruma.core.audio.MusicTable;
import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.audio.VorbisDecoder;
import com.rost.cruma.core.audio.WavWriter;
import com.rost.cruma.core.export.LocationExport.Area;
import com.rost.cruma.core.export.LocationModel.MusicTrack;
import com.rost.cruma.core.export.LocationModel.MusicZone;
import com.rost.cruma.core.world.MusicVolume;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class LocationMusic {
    static final String FOLDER = "music";

    static final Codec VORBIS = new Codec() {
        @Override
        public boolean hasOldFloor(Path file) throws IOException {
            try (var decoder = new VorbisDecoder(file)) {
                return decoder.hasOldFloor();
            }
        }

        @Override
        public SoundClip decode(Path file, String name) throws IOException {
            return VorbisDecoder.decode(file, name);
        }
    };

    private static final String SUFFIX = ".ogg";

    private static final String DECODED_SUFFIX = ".wav";

    private static final byte[] CLIENT_MARK = {'L', '2', 'S', 'D'};

    private static final byte[] OGG_MARK = {'O', 'g', 'g', 'S'};

    private final MusicTable table;
    private final Map<String, Path> files;
    private final Codec codec;
    private final Map<String, MusicTrack> tracks = new HashMap<>();
    private final Set<String> taken = new HashSet<>();
    private final Set<String> unreadable = new HashSet<>();

    LocationMusic(MusicTable table, Map<String, Path> files, Codec codec) {
        this.table = table;
        this.files = files;
        this.codec = codec;
    }

    interface Codec {
        boolean hasOldFloor(Path file) throws IOException;

        SoundClip decode(Path file, String name) throws IOException;
    }

    static @Nullable LocationMusic of(Path client, WorldVariant variant) {
        try {
            var folder = folder(client);
            return folder == null ? null : new LocationMusic(MusicTable.load(client, variant), files(folder), VORBIS);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    static byte[] copy(Path source) throws IOException {
        var bytes = Files.readAllBytes(source);
        if (bytes.length >= CLIENT_MARK.length
                && Arrays.equals(bytes, 0, CLIENT_MARK.length, CLIENT_MARK, 0, CLIENT_MARK.length)) {
            System.arraycopy(OGG_MARK, 0, bytes, 0, OGG_MARK.length);
        }
        return bytes;
    }

    static Map<String, Path> files(Path folder) throws IOException {
        var result = new HashMap<String, Path>();
        try (var entries = Files.list(folder)) {
            entries.forEach(file -> {
                var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(SUFFIX) && Files.isRegularFile(file)) {
                    result.putIfAbsent(name.substring(0, name.length() - SUFFIX.length()), file);
                }
            });
        } catch (UncheckedIOException unreadable) {
            throw unreadable.getCause();
        }
        return result;
    }

    List<MusicZone> findZones(UMap map, @Nullable Area crop, float originX, float originY, float metersPerUnit) {
        var result = new ArrayList<MusicZone>();
        for (var volume : map.musicVolumes()) {
            var bounds = volume.getShape().getWorldBounds();
            var min = bounds.getMin();
            var max = bounds.getMax();
            if (crop != null && !crop.meets(min.getX(), min.getY(), max.getX(), max.getY())) {
                continue;
            }
            result.add(zone(volume, map.name(), originX, originY, metersPerUnit));
        }
        return result;
    }

    private MusicZone zone(MusicVolume volume, String tile, float originX, float originY, float metersPerUnit) {
        var tree = volume.getShape().getWorldTree();
        var world = tree.planes();
        var planes = new float[world.length];
        for (var index = 0; index < world.length; index += 4) {
            double x = world[index];
            double y = world[index + 1];
            double z = world[index + 2];
            var length = Math.sqrt(x * x + y * y + z * z);
            if (length == 0) {
                continue;
            }
            planes[index] = (float) (x / length);
            planes[index + 1] = (float) (z / length);
            planes[index + 2] = (float) (y / length);
            planes[index + 3] = (float) ((world[index + 3] - x * originX - y * originY) / length * metersPerUnit);
        }
        var bounds = volume.getShape().getWorldBounds();
        var min = bounds.getMin();
        var max = bounds.getMax();
        var missing = new ArrayList<String>();
        return new MusicZone(
                volume.getName(),
                tile,
                volume.getMusicId(),
                tracks(volume.getMusicId(), missing),
                volume.hasNightMusic(),
                volume.getNightMusicId(),
                volume.hasNightMusic() ? tracks(volume.getNightMusicId(), missing) : List.of(),
                volume.isLoop(),
                volume.isForcePlay(),
                new float[] {
                    (min.getX() - originX) * metersPerUnit,
                    min.getZ() * metersPerUnit,
                    (min.getY() - originY) * metersPerUnit
                },
                new float[] {
                    (max.getX() - originX) * metersPerUnit,
                    max.getZ() * metersPerUnit,
                    (max.getY() - originY) * metersPerUnit
                },
                planes,
                tree.front(),
                tree.back(),
                tree.decides(),
                tree.rootOutside(),
                List.copyOf(missing));
    }

    private List<MusicTrack> tracks(int musicId, List<String> missing) {
        var result = new ArrayList<MusicTrack>();
        for (var name : table.getPlaylist(musicId)) {
            var key = name.toLowerCase(Locale.ROOT);
            var track = tracks.get(key);
            if (track == null && !unreadable.contains(key)) {
                track = read(name, files.get(key));
                if (track == null) {
                    unreadable.add(key);
                } else {
                    tracks.put(key, track);
                }
            }
            if (track != null) {
                result.add(track);
            } else if (!missing.contains(name)) {
                missing.add(name);
            }
        }
        return List.copyOf(result);
    }

    private @Nullable MusicTrack read(String name, @Nullable Path source) {
        if (source == null) {
            return null;
        }
        try {
            if (codec.hasOldFloor(source)) {
                return new MusicTrack(
                        name, file(name, DECODED_SUFFIX), true, () -> WavWriter.encode(codec.decode(source, name)));
            }
            return new MusicTrack(name, file(name, SUFFIX), false, () -> copy(source));
        } catch (IOException | RuntimeException damaged) {
            return null;
        }
    }

    private String file(String name, String suffix) {
        var base = FOLDER + "/" + LocationExport.toSafeName(name);
        var path = base + suffix;
        for (var number = 2; !taken.add(path.toLowerCase(Locale.ROOT)); number++) {
            path = base + "_" + number + suffix;
        }
        return path;
    }

    private static @Nullable Path folder(Path client) throws IOException {
        var plain = client.resolve(FOLDER);
        if (Files.isDirectory(plain)) {
            return plain;
        }
        try (var entries = Files.list(client)) {
            return entries.filter(Files::isDirectory)
                    .filter(entry -> entry.getFileName().toString().equalsIgnoreCase(FOLDER))
                    .findFirst()
                    .orElse(null);
        }
    }
}

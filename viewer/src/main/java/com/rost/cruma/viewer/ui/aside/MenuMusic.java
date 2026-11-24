package com.rost.cruma.viewer.ui.aside;

import com.rost.cruma.core.audio.MusicPlayer;
import com.rost.cruma.core.audio.MusicTable;
import com.rost.cruma.core.audio.OggMusicPlayer;
import com.rost.cruma.core.audio.VorbisDecoder;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UPackageLoader;
import com.rost.cruma.core.world.MusicVolume;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.system.MusicSystem;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class MenuMusic implements AutoCloseable {
    public static final Reader FILES = new Reader() {
        @Override
        public double getSeconds(Path file) {
            return VorbisDecoder.seconds(file);
        }

        @Override
        public boolean opens(Path file) {
            try (var decoder = new VorbisDecoder(file)) {
                return decoder.getChannels() > 0;
            } catch (IOException | RuntimeException unreadable) {
                return false;
            }
        }
    };

    private static final double END_SLACK = 2;

    private static final String MUSIC = "music";
    private static final String SUFFIX = ".ogg";

    public enum Phase {
        WAITING,
        STARTING,
        PLAYING,
        PAUSED,
        FAILED,
        NO_OUTPUT
    }

    public record Track(Path file, String name, String stem, double seconds) {}

    public record Library(List<Track> tracks, String problem, boolean loading) {
        static final Library LOADING = new Library(List.of(), "", true);
    }

    public record Theme(String tile, @Nullable Track track, String place, int musicId, String problem) {
        static Theme none(String tile, String problem) {
            return new Theme(tile, null, "", -1, problem);
        }
    }

    public interface Output {
        MusicPlayer open();
    }

    public interface Reader {
        double getSeconds(Path file);

        boolean opens(Path file);
    }

    private record Key(Path root, WorldVariant world) {}

    private record TileKey(Path root, WorldVariant world, String tile) {}

    private record Source(
            @Nullable MusicTable table, Map<String, String> packages, UPackageLoader loader, String problem) {}

    private record Checked(Track track, boolean opens, long request) {}

    private final Output output;
    private final Reader reader;
    private final DoubleSupplier volume;
    private final boolean silent;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), work -> {
                var thread = new Thread(work, "cruma-menu-music");
                thread.setDaemon(true);
                return thread;
            });

    {
        worker.allowCoreThreadTimeOut(true);
    }

    private final Map<Key, Library> libraries = new ConcurrentHashMap<>();
    private final Map<Key, Boolean> librariesAsked = new ConcurrentHashMap<>();
    private final Map<TileKey, Theme> themes = new ConcurrentHashMap<>();
    private final Map<TileKey, Boolean> themesAsked = new ConcurrentHashMap<>();
    private final Map<Key, Boolean> tables = new ConcurrentHashMap<>();
    private final Map<Key, Source> sources = new HashMap<>();

    private @Nullable MusicPlayer player;
    private @Nullable Track current;
    private @Nullable Theme currentTheme;
    private volatile Phase phase = Phase.WAITING;
    private volatile @Nullable Checked checked;
    private long request;
    private boolean following;
    private double from;
    private long startedNanos;
    private double heldAt = Double.NaN;

    public MenuMusic(DoubleSupplier volume) {
        this(OggMusicPlayer::new, FILES, volume, false);
    }

    private MenuMusic(Output output, Reader reader, DoubleSupplier volume, boolean silent) {
        this.output = output;
        this.reader = reader;
        this.volume = volume;
        this.silent = silent;
    }

    public static MenuMusic silent(Reader reader) {
        return new MenuMusic(SilentPlayer::new, reader, () -> 1, true);
    }

    static MenuMusic playingThrough(Output output, Reader reader) {
        return new MenuMusic(output, reader, () -> 1, false);
    }

    public static Theme themeOf(
            String tile, List<MusicVolume> volumes, List<Location> places, MusicTable table, Map<String, Track> files) {
        if (volumes.isEmpty()) {
            return Theme.none(tile, "This tile has no music zone.");
        }
        MusicVolume chosen = null;
        var place = "";
        for (var candidate : places) {
            var holding = MusicSystem.locate(volumes, new Vec3(candidate.x(), candidate.y(), candidate.z()));
            if (holding != null) {
                chosen = holding;
                place = candidate.name();
                break;
            }
        }
        if (chosen == null) {
            chosen = volumes.stream()
                    .max(Comparator.comparingDouble(MenuMusic::areaAcross))
                    .orElseThrow();
        }
        var musicId = chosen.getMusicIdAt(false);
        for (var name : table.getPlaylist(musicId)) {
            var track = files.get(name.toLowerCase(Locale.ROOT));
            if (track != null) {
                return new Theme(tile, track, place, musicId, "");
            }
        }
        return new Theme(tile, null, place, musicId, "The music of this tile has no track in the music folder.");
    }

    public static String nameOf(String stem) {
        return stem.replace('_', ' ').strip();
    }

    public static String clock(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) {
            return "";
        }
        var whole = (long) Math.floor(seconds);
        return whole / 60 + ":" + String.format(Locale.ROOT, "%02d", whole % 60);
    }

    public Library getLibrary(ClientInstallation client) {
        var key = new Key(client.root(), client.variant());
        var known = libraries.get(key);
        if (known != null) {
            return known;
        }
        if (librariesAsked.putIfAbsent(key, true) == null) {
            worker.execute(() -> libraries.put(key, readLibrary(client.root())));
        }
        return Library.LOADING;
    }

    public @Nullable Theme getTheme(ClientInstallation client, String tile, List<Location> places) {
        var key = new TileKey(client.root(), client.variant(), tile);
        var known = themes.get(key);
        if (known != null) {
            return known;
        }
        if (themesAsked.putIfAbsent(key, true) == null) {
            var named = List.copyOf(places);
            worker.execute(() -> themes.put(key, readTheme(client, tile, named)));
        }
        return null;
    }

    public boolean hasThemes(ClientInstallation client) {
        return Boolean.TRUE.equals(tables.get(new Key(client.root(), client.variant())));
    }

    void knowTheme(ClientInstallation client, Theme theme) {
        var key = new TileKey(client.root(), client.variant(), theme.tile());
        themes.put(key, theme);
        themesAsked.put(key, true);
        tables.put(new Key(client.root(), client.variant()), true);
    }

    public @Nullable Track getCurrent() {
        return current;
    }

    public @Nullable Theme getCurrentTheme() {
        return currentTheme;
    }

    public Phase getPhase() {
        return phase;
    }

    public boolean isPlaying() {
        return phase == Phase.STARTING || phase == Phase.PLAYING && player != null && player.isPlaying();
    }

    public boolean isFollowing() {
        return following;
    }

    public void setFollowing(boolean value) {
        following = value;
    }

    public void show(Track track, @Nullable Theme theme) {
        if (current == null || phase == Phase.WAITING || phase == Phase.FAILED) {
            current = track;
            currentTheme = theme;
            phase = Phase.WAITING;
            from = 0;
            heldAt = Double.NaN;
        }
    }

    public void stop() {
        if (player != null) {
            player.stop(0);
        }
        request++;
        checked = null;
        if (phase != Phase.WAITING) {
            phase = Phase.WAITING;
            from = 0;
            heldAt = Double.NaN;
        }
    }

    public void replace(Track track) {
        if (player != null) {
            player.stop(0);
        }
        request++;
        checked = null;
        phase = Phase.WAITING;
        current = track;
        currentTheme = null;
        from = 0;
        heldAt = Double.NaN;
    }

    public void play(Track track, @Nullable Theme theme) {
        playFrom(track, theme, 0);
    }

    public void toggle() {
        var track = current;
        if (track == null) {
            return;
        }
        switch (phase) {
            case PLAYING -> {
                heldAt = getPosition();
                Objects.requireNonNull(player).pause(true);
                phase = Phase.PAUSED;
            }
            case PAUSED -> {
                from = heldAt;
                startedNanos = System.nanoTime();
                heldAt = Double.NaN;
                Objects.requireNonNull(player).pause(false);
                phase = Phase.PLAYING;
            }
            case STARTING -> {}
            default -> playFrom(track, currentTheme, phase == Phase.WAITING && !Double.isNaN(heldAt) ? heldAt : 0);
        }
    }

    public void seek(double seconds) {
        var track = current;
        if (track == null) {
            return;
        }
        var at = Double.isNaN(track.seconds()) ? Math.max(0, seconds) : Math.clamp(seconds, 0, track.seconds());
        if (phase == Phase.PLAYING || phase == Phase.PAUSED) {
            var held = phase == Phase.PAUSED;
            Objects.requireNonNull(player).play(track.file(), 0, 0, at);
            from = at;
            startedNanos = System.nanoTime();
            heldAt = held ? at : Double.NaN;
            if (held) {
                player.pause(true);
            }
        } else {
            from = at;
            heldAt = at;
        }
    }

    public void step(List<Track> tracks, int by) {
        if (tracks.isEmpty()) {
            return;
        }
        var at = current == null ? -1 : indexOf(tracks, current);
        var next = tracks.get(Math.floorMod(at + by, tracks.size()));
        if (phase != Phase.WAITING && phase != Phase.NO_OUTPUT) {
            play(next, null);
        } else {
            current = next;
            currentTheme = null;
            phase = Phase.WAITING;
            from = 0;
            heldAt = Double.NaN;
        }
    }

    public double getPosition() {
        var track = current;
        if (track == null) {
            return 0;
        }
        var at = switch (phase) {
            case PLAYING -> from + (System.nanoTime() - startedNanos) / 1e9;
            case PAUSED, WAITING -> Double.isNaN(heldAt) ? from : heldAt;
            default -> from;
        };
        return Double.isNaN(track.seconds()) ? at : Math.min(at, track.seconds());
    }

    public double getOutputPosition() {
        var current = player;
        return current == null ? Double.NaN : current.getPosition();
    }

    public void tick(List<Track> tracks) {
        var found = checked;
        if (found != null && found.request() == request) {
            checked = null;
            start(found);
        }
        var track = current;
        if (phase != Phase.PLAYING || track == null || player == null) {
            return;
        }
        if (!player.isAvailable()) {
            phase = Phase.NO_OUTPUT;
            return;
        }
        var position = from + (System.nanoTime() - startedNanos) / 1e9;
        var past = !Double.isNaN(track.seconds()) && position >= track.seconds() + (silent ? 0 : END_SLACK);
        if (past || !player.isPlaying()) {
            if (currentTheme != null) {
                play(track, currentTheme);
            } else if (tracks.size() > 1) {
                play(tracks.get(Math.floorMod(indexOf(tracks, track) + 1, tracks.size())), null);
            } else {
                player.stop(0);
                phase = Phase.WAITING;
                from = 0;
                heldAt = Double.NaN;
            }
        }
    }

    @Override
    public void close() {
        var open = player;
        player = null;
        if (open != null) {
            open.stop(0);
            open.close();
        }
        if (phase != Phase.WAITING) {
            phase = Phase.WAITING;
            from = 0;
            heldAt = Double.NaN;
        }
        currentTheme = null;
        checked = null;
        request++;
        following = false;
        themes.clear();
        themesAsked.clear();
        worker.execute(sources::clear);
    }

    private void playFrom(Track track, @Nullable Theme theme, double start) {
        current = track;
        currentTheme = theme;
        from = start;
        heldAt = Double.NaN;
        phase = Phase.STARTING;
        var asked = ++request;
        worker.execute(() -> checked = new Checked(track, reader.opens(track.file()), asked));
    }

    private void start(Checked found) {
        if (!found.opens()) {
            if (player != null) {
                player.stop(0);
            }
            phase = Phase.FAILED;
            return;
        }
        if (player == null) {
            player = output.open();
        }
        player.setVolume((float) Math.clamp(volume.getAsDouble(), 0, 1));
        player.pause(false);
        player.play(found.track().file(), 0, 0, from);
        startedNanos = System.nanoTime();
        phase = Phase.PLAYING;
    }

    private Library readLibrary(Path root) {
        var folder = musicFolder(root);
        if (folder == null) {
            return new Library(List.of(), "This client has no music folder.", false);
        }
        var tracks = new ArrayList<Track>();
        try (var entries = Files.list(folder)) {
            entries.forEach(file -> {
                var name = file.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).endsWith(SUFFIX) && Files.isRegularFile(file)) {
                    var stem = name.substring(0, name.length() - SUFFIX.length());
                    tracks.add(new Track(file, nameOf(stem), stem, reader.getSeconds(file)));
                }
            });
        } catch (IOException | UncheckedIOException unreadable) {
            return new Library(List.of(), "The music folder cannot be read: " + unreadable.getMessage(), false);
        }
        if (tracks.isEmpty()) {
            return new Library(List.of(), "The music folder of this client has no tracks.", false);
        }
        tracks.sort(Comparator.comparing(Track::stem, String.CASE_INSENSITIVE_ORDER));
        return new Library(List.copyOf(tracks), "", false);
    }

    private Theme readTheme(ClientInstallation client, String tile, List<Location> places) {
        try {
            var key = new Key(client.root(), client.variant());
            var source = sources.computeIfAbsent(key, ignored -> readSource(client));
            tables.put(key, source.table() != null);
            if (source.table() == null) {
                return Theme.none(tile, source.problem());
            }
            var name = source.packages().get(tile);
            if (name == null) {
                return Theme.none(tile, "This client has no map of tile " + tile + ".");
            }
            var map = source.loader().loadPackageIfPresent(name);
            if (map.isEmpty()) {
                return Theme.none(tile, "This client has no map of tile " + tile + ".");
            }
            var volumes = MusicVolume.readAll(map.get());
            source.loader().removePackage(name);
            var library = libraries.computeIfAbsent(key, ignored -> readLibrary(client.root()));
            var files = new HashMap<String, Track>();
            for (var track : library.tracks()) {
                files.putIfAbsent(track.stem().toLowerCase(Locale.ROOT), track);
            }
            return themeOf(tile, volumes, places, source.table(), files);
        } catch (RuntimeException unreadable) {
            LogManager.getLogger(MenuMusic.class)
                    .warn("The music of tile {} cannot be read: {}", tile, unreadable.toString());
            return Theme.none(tile, "The music zones of this tile cannot be read.");
        }
    }

    private static int indexOf(List<Track> tracks, Track track) {
        for (var index = 0; index < tracks.size(); index++) {
            if (tracks.get(index).file().equals(track.file())) {
                return index;
            }
        }
        return -1;
    }

    private static double areaAcross(MusicVolume volume) {
        var bounds = volume.getShape().getWorldBounds();
        return (double) (bounds.getMaxX() - bounds.getMinX()) * (bounds.getMaxY() - bounds.getMinY());
    }

    private static @Nullable Path musicFolder(Path root) {
        var plain = root.resolve(MUSIC);
        if (Files.isDirectory(plain)) {
            return plain;
        }
        try (var entries = Files.list(root)) {
            return entries.filter(entry -> entry.getFileName().toString().equalsIgnoreCase(MUSIC))
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElse(null);
        } catch (IOException | UncheckedIOException unreadable) {
            return null;
        }
    }

    private static Source readSource(ClientInstallation client) {
        var loader = new UPackageLoader(
                client.root(),
                List.of(
                        new USearchConfig("Maps", "unr"),
                        new USearchConfig("StaticMeshes", "usx"),
                        new USearchConfig("Textures", "utx"),
                        new USearchConfig("SysTextures", "utx")));
        Map<String, String> packages;
        try {
            packages = WorldMapCatalog.findPackages(client.root(), client.variant());
        } catch (RuntimeException unreadable) {
            packages = Map.of();
        }
        try {
            return new Source(MusicTable.load(client.root(), client.variant()), packages, loader, "");
        } catch (IOException | RuntimeException unreadable) {
            return new Source(null, packages, loader, "The music table of this client cannot be read.");
        }
    }

    private static final class SilentPlayer implements MusicPlayer {
        private boolean playing;

        @Override
        public void play(Path file, float fadeInSeconds, float fadeOutSeconds, double fromSeconds) {
            playing = true;
        }

        @Override
        public void stop(float fadeOutSeconds) {
            playing = false;
        }

        @Override
        public void setVolume(float volume) {}

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void close() {
            playing = false;
        }
    }
}

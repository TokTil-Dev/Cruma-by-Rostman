package com.rost.cruma.core.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class OggMusicPlayer implements MusicPlayer {
    private static final short[] NOTHING = {};

    private static final int CHUNK_FRAMES = 2048;
    private static final int LINE_BUFFER_CHUNKS = 4;
    private static final long HOLD_MILLIS = 20L;
    private static final Set<String> REPORTED_MISSING = ConcurrentHashMap.newKeySet();

    private static final Logger log = LogManager.getLogger(OggMusicPlayer.class);

    private final Object lock = new Object();
    private final ArrayDeque<Command> commands = new ArrayDeque<>();
    private final Decoders decoders;
    private final Lines lines;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile float volume = 1f;
    private volatile boolean playing;
    private volatile boolean available = true;
    private volatile boolean paused;
    private volatile double position;

    private Track current;
    private Track outgoing;
    private SourceDataLine line;
    private byte[] bytes;

    public OggMusicPlayer() {
        this(VorbisDecoder::new, format -> AudioSystem.getSourceDataLine(format));
    }

    OggMusicPlayer(Decoders decoders, Lines lines) {
        this.decoders = decoders;
        this.lines = lines;
        thread = new Thread(this::run, "music-player");
        thread.setDaemon(true);
        thread.start();
    }

    interface Decoders {
        Pcm open(Path file) throws IOException;
    }

    interface Lines {
        SourceDataLine open(AudioFormat format) throws LineUnavailableException;
    }

    private record Command(Path file, float fadeIn, float fadeOut, double from) {
        static Command stop(float fadeOut) {
            return new Command(null, 0f, fadeOut, 0);
        }
    }

    private static final class Track {
        final Pcm decoder;
        final int channels;
        final int sampleRate;
        final short[] samples;
        float gain;
        float gainTarget;
        float gainPerSecond;
        boolean ended;

        Track(Pcm decoder) {
            this.decoder = decoder;
            this.channels = decoder.getChannels();
            this.sampleRate = decoder.getSampleRate();
            this.samples = new short[CHUNK_FRAMES * channels];
        }

        void start(float fadeIn, double from) {
            decoder.skip(Math.round(from * sampleRate));
            gain = fadeIn <= 0f ? 1f : (float) Math.min(1, from / fadeIn);
            fadeTo(1f, Math.max(0f, fadeIn - (float) from));
        }

        void fadeTo(float target, float seconds) {
            gainTarget = target;
            gainPerSecond = seconds <= 0f ? Float.MAX_VALUE : Math.abs(target - gain) / seconds;
            if (seconds <= 0f) {
                gain = target;
            }
        }

        int read() {
            var frames = decoder.read(samples);
            if (frames <= 0) {
                ended = true;
                return 0;
            }
            return frames;
        }

        void advance(float seconds) {
            if (gain == gainTarget) {
                return;
            }
            var step = gainPerSecond == Float.MAX_VALUE ? 1f : gainPerSecond * seconds;
            gain = gain < gainTarget ? Math.min(gainTarget, gain + step) : Math.max(gainTarget, gain - step);
        }

        boolean isSilent() {
            return gain <= 0f && gainTarget <= 0f;
        }

        void close() {
            decoder.close();
        }
    }

    static void mix(
            short[] current,
            int currentSamples,
            float currentScale,
            short[] outgoing,
            int outgoingSamples,
            float outgoingScale,
            int count,
            byte[] bytes) {
        for (var i = 0; i < count; i++) {
            var mixed = 0f;
            if (i < currentSamples) {
                mixed += current[i] * currentScale;
            }
            if (i < outgoingSamples) {
                mixed += outgoing[i] * outgoingScale;
            }
            var value = (int) Math.clamp(mixed, Short.MIN_VALUE, Short.MAX_VALUE);
            bytes[i * 2] = (byte) value;
            bytes[i * 2 + 1] = (byte) (value >> 8);
        }
    }

    @Override
    public void play(Path file, float fadeInSeconds, float fadeOutSeconds, double fromSeconds) {
        if (!Files.isRegularFile(file)) {
            if (REPORTED_MISSING.add(file.toString())) {
                log.warn("Missing track {}", file);
            }
            playing = false;
            return;
        }
        playing = true;
        submit(new Command(file, Math.max(0f, fadeInSeconds), Math.max(0f, fadeOutSeconds), Math.max(0, fromSeconds)));
    }

    @Override
    public void stop(float fadeOutSeconds) {
        submit(Command.stop(Math.max(0f, fadeOutSeconds)));
    }

    @Override
    public void setVolume(float volume) {
        this.volume = Math.clamp(volume, 0f, 1f);
    }

    @Override
    public void pause(boolean paused) {
        this.paused = paused;
    }

    @Override
    public boolean isPlaying() {
        return playing;
    }

    @Override
    public double getPosition() {
        return position;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public void close() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            thread.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void submit(Command command) {
        synchronized (lock) {
            commands.add(command);
            lock.notifyAll();
        }
    }

    private void run() {
        try {
            while (running) {
                var command = nextCommand(current == null && outgoing == null);
                if (command != null) {
                    apply(command);
                }
                if (paused) {
                    hold();
                    continue;
                }
                if (line != null && !line.isRunning()) {
                    line.start();
                }
                if (current != null || outgoing != null) {
                    mixNextChunk();
                }
            }
        } catch (RuntimeException | Error e) {
            log.error("Playback thread failed; music is off for this session", e);
            available = false;
        } finally {
            if (current != null) {
                current.close();
                current = null;
            }
            if (outgoing != null) {
                outgoing.close();
                outgoing = null;
            }
            closeLine();
        }
    }

    private void hold() {
        if (line != null && line.isRunning()) {
            line.stop();
        }
        synchronized (lock) {
            if (commands.isEmpty() && running) {
                try {
                    lock.wait(HOLD_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }

    private Command nextCommand(boolean idle) {
        synchronized (lock) {
            if (commands.isEmpty() && idle && running) {
                try {
                    lock.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
            return commands.poll();
        }
    }

    private void apply(Command command) {
        if (command.file() == null) {
            fadeOutCurrent(command.fadeOut());
            markIdle();
            return;
        }
        var track = open(command.file());
        if (track == null) {
            markIdle();
            return;
        }
        fadeOutCurrent(command.fadeOut());
        if (!openLine(track)) {
            track.close();
            markIdle();
            return;
        }
        track.start(command.fadeIn(), command.from());
        current = track;
        position = command.from();
        log.info(
                "Playing {} ({} Hz, {} ch) from {} s",
                command.file().getFileName(),
                track.sampleRate,
                track.channels,
                command.from());
    }

    private void fadeOutCurrent(float fadeOut) {
        if (current == null) {
            return;
        }
        if (outgoing != null) {
            outgoing.close();
        }
        outgoing = current;
        current = null;
        outgoing.fadeTo(0f, fadeOut);
    }

    private Track open(Path file) {
        try {
            return new Track(decoders.open(file));
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot decode {}: {}", file, e.toString());
            return null;
        }
    }

    private boolean openLine(Track track) {
        if (line != null
                && line.getFormat().getChannels() == track.channels
                && line.getFormat().getSampleRate() == track.sampleRate) {
            return true;
        }
        if (outgoing != null) {
            outgoing.close();
            outgoing = null;
        }
        closeLine();
        var format = new AudioFormat(track.sampleRate, 16, track.channels, true, false);
        try {
            line = lines.open(format);
            line.open(format, CHUNK_FRAMES * track.channels * 2 * LINE_BUFFER_CHUNKS);
            line.start();
            bytes = new byte[CHUNK_FRAMES * track.channels * 2];
            return true;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            log.warn("No audio output for {}: {}", format, e.toString());
            line = null;
            available = false;
            return false;
        }
    }

    private void mixNextChunk() {
        var channels = line.getFormat().getChannels();
        var sampleRate = line.getFormat().getSampleRate();
        var currentFrames = current == null ? 0 : current.read();
        var outgoingFrames = outgoing == null ? 0 : outgoing.read();
        var frames = Math.max(currentFrames, outgoingFrames);
        if (frames == 0) {
            line.drain();
            finishEnded();
            return;
        }
        var master = volume;
        var currentScale = current == null ? 0f : current.gain * master;
        var outgoingScale = outgoing == null ? 0f : outgoing.gain * master;
        var count = frames * channels;
        mix(
                current == null ? NOTHING : current.samples,
                currentFrames * channels,
                currentScale,
                outgoing == null ? NOTHING : outgoing.samples,
                outgoingFrames * channels,
                outgoingScale,
                count,
                bytes);
        line.write(bytes, 0, count * 2);
        var seconds = frames / sampleRate;
        if (current != null) {
            current.advance(seconds);
            position += currentFrames / sampleRate;
        }
        if (outgoing != null) {
            outgoing.advance(seconds);
            if (outgoing.isSilent() || outgoing.ended) {
                outgoing.close();
                outgoing = null;
            }
        }
        finishEnded();
    }

    private void finishEnded() {
        if (current != null && current.ended) {
            current.close();
            current = null;
            markIdle();
        }
        if (outgoing != null && outgoing.ended) {
            outgoing.close();
            outgoing = null;
        }
    }

    private void markIdle() {
        synchronized (lock) {
            if (commands.isEmpty()) {
                playing = false;
            }
        }
    }

    private void closeLine() {
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
    }
}

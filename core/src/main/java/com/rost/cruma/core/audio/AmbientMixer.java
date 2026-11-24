package com.rost.cruma.core.audio;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AmbientMixer implements AmbientOutput {
    static final int RATE = 44_100;
    static final int CHUNK_FRAMES = 1024;

    public static final float STOP_FADE_SECONDS = 0.15f;

    private static final int LINE_BUFFER_CHUNKS = 4;
    private static final long HOLD_MILLIS = 20L;

    private static final Logger log = LogManager.getLogger(AmbientMixer.class);

    private final List<ClipVoice> voices = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile float volume = 1f;
    private volatile boolean available = true;
    private volatile boolean paused;
    private SourceDataLine line;
    private final float[] mix = new float[CHUNK_FRAMES * 2];
    private final byte[] bytes = new byte[CHUNK_FRAMES * 4];

    public AmbientMixer() {
        thread = new Thread(this::run, "ambient-mixer");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public AmbientVoice play(SoundClip clip, boolean loop, float pitch, float gain, float pan) {
        return play(clip, loop, pitch, gain, pan, 0);
    }

    @Override
    public AmbientVoice play(SoundClip clip, boolean loop, float pitch, float gain, float pan, double fromSeconds) {
        var voice = new ClipVoice(clip, loop, pitch, gain, pan, fromSeconds);
        voices.add(voice);
        synchronized (lock) {
            lock.notifyAll();
        }
        return voice;
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
    public boolean isAvailable() {
        return available;
    }

    @Override
    public int getActiveVoices() {
        return voices.size();
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

    private void run() {
        try {
            while (running) {
                if (voices.isEmpty()) {
                    synchronized (lock) {
                        if (voices.isEmpty() && running) {
                            lock.wait(50L);
                        }
                    }
                    continue;
                }
                if (line == null && !openLine()) {
                    return;
                }
                if (paused) {
                    if (line.isRunning()) {
                        line.stop();
                    }
                    synchronized (lock) {
                        lock.wait(HOLD_MILLIS);
                    }
                    continue;
                }
                if (!line.isRunning()) {
                    line.start();
                }
                mixNextChunk();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            log.error("Mixer thread failed; ambient sound is off for this session", e);
            available = false;
        } finally {
            if (line != null) {
                line.stop();
                line.close();
                line = null;
            }
        }
    }

    private boolean openLine() {
        var format = new AudioFormat(RATE, 16, 2, true, false);
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, CHUNK_FRAMES * 4 * LINE_BUFFER_CHUNKS);
            line.start();
            return true;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            log.warn("No audio output for {}: {}", format, e.toString());
            available = false;
            return false;
        }
    }

    private void mixNextChunk() {
        Arrays.fill(mix, 0f);
        var master = volume;
        for (var voice : voices) {
            if (voice.render(mix, CHUNK_FRAMES, master)) {
                voices.remove(voice);
            }
        }
        for (var i = 0; i < CHUNK_FRAMES * 2; i++) {
            var value = (int) Math.clamp(mix[i], Short.MIN_VALUE, Short.MAX_VALUE);
            bytes[i * 2] = (byte) value;
            bytes[i * 2 + 1] = (byte) (value >> 8);
        }
        line.write(bytes, 0, bytes.length);
    }

    static final class ClipVoice implements AmbientVoice {
        private final SoundClip clip;
        private final boolean loop;
        private final double step;
        private volatile float targetGain;
        private volatile float targetPan;
        private volatile boolean stopping;
        private volatile boolean finished;
        private double cursor;
        private float gain;
        private float pan;

        ClipVoice(SoundClip clip, boolean loop, float pitch, float gain, float pan) {
            this(clip, loop, pitch, gain, pan, 0);
        }

        ClipVoice(SoundClip clip, boolean loop, float pitch, float gain, float pan, double fromSeconds) {
            this.clip = clip;
            this.loop = loop;
            this.step = (double) clip.sampleRate() * pitch / RATE;
            this.targetGain = gain;
            this.targetPan = pan;
            this.gain = gain;
            this.pan = pan;
            var at = fromSeconds * clip.sampleRate() * pitch;
            cursor = loop && clip.frames() > 0 ? at % clip.frames() : at;
        }

        @Override
        public void set(float gain, float pan) {
            targetGain = gain;
            targetPan = pan;
        }

        @Override
        public void stop() {
            stopping = true;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        boolean render(float[] mix, int frames, float master) {
            if (finished) {
                return true;
            }
            var frameCount = clip.frames();
            var channels = clip.channels();
            var samples = clip.samples();
            var goalGain = stopping ? 0f : targetGain;
            var gainStep = (goalGain - gain) / frames;
            var maxGainStep = stopping ? 1f / (STOP_FADE_SECONDS * RATE) : Float.MAX_VALUE;
            gainStep = Math.clamp(gainStep, -maxGainStep, maxGainStep);
            var panStep = (targetPan - pan) / frames;
            for (var frame = 0; frame < frames; frame++) {
                if (cursor >= frameCount) {
                    if (!loop) {
                        finished = true;
                        return true;
                    }
                    cursor -= frameCount;
                }
                var index = (int) cursor;
                var fraction = (float) (cursor - index);
                var next = index + 1 < frameCount ? index + 1 : (loop ? 0 : index);
                float left;
                float right;
                if (channels == 1) {
                    var value = samples[index] + (samples[next] - samples[index]) * fraction;
                    left = value;
                    right = value;
                } else {
                    var l0 = samples[index * 2];
                    var r0 = samples[index * 2 + 1];
                    left = l0 + (samples[next * 2] - l0) * fraction;
                    right = r0 + (samples[next * 2 + 1] - r0) * fraction;
                }
                gain += gainStep;
                pan += panStep;
                var angle = (pan + 1f) * 0.25f * (float) Math.PI;
                var scale = gain * master;
                mix[frame * 2] += left * scale * (float) Math.cos(angle);
                mix[frame * 2 + 1] += right * scale * (float) Math.sin(angle);
                cursor += step;
            }
            gain = Math.clamp(gain, 0f, 1f);
            if (stopping && gain <= 0.001f) {
                finished = true;
                return true;
            }
            return false;
        }
    }
}

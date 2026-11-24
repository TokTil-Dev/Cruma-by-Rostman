package com.rost.cruma.core.world;

import com.rost.cruma.core.world.AmbientVolume.Sound;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import org.jetbrains.annotations.Nullable;

public final class AmbientVolumePlayer {
    private static final Comparator<AmbientVolume> HIGHEST_PRIORITY_FIRST =
            Comparator.comparingInt(AmbientVolume::getPriority).reversed();

    private final IntSupplier rand;
    private final List<AmbientVolume> current = new ArrayList<>();
    private final Map<Sound, Wait> waits = new IdentityHashMap<>();
    private final List<Voice> voices = new ArrayList<>();
    private @Nullable Boolean lastDay;
    private int freeVoices;

    public AmbientVolumePlayer(IntSupplier rand) {
        this.rand = rand;
    }

    private static final class Wait {
        float seconds;
        boolean needed;
    }

    public static final class Voice {
        private final AmbientVolume volume;
        private final Sound sound;
        private int fade;
        private float fadeSeconds;
        private float fadeLength;

        private Voice(AmbientVolume volume, Sound sound) {
            this.volume = volume;
            this.sound = sound;
            if (volume.getFadeInSeconds() > 0f) {
                fade = 1;
                fadeLength = volume.getFadeInSeconds();
            }
        }

        public AmbientVolume getVolume() {
            return volume;
        }

        public Sound getSound() {
            return sound;
        }

        public float getGain() {
            var gain = sound.gain();
            return switch (fade) {
                case 1 -> Math.clamp(fadeSeconds / fadeLength, 0f, 1f) * gain;
                case 2 -> Math.clamp((fadeLength - fadeSeconds) / fadeLength, 0f, 1f) * gain;
                default -> gain;
            };
        }

        public boolean isFadingOut() {
            return fade == 2;
        }

        private boolean advance(double seconds) {
            if (fade == 1 && fadeSeconds >= fadeLength) {
                fade = 0;
            } else if (fade == 2 && fadeSeconds >= fadeLength) {
                return false;
            }
            if (fade != 0) {
                fadeSeconds = (float) (fadeSeconds + seconds);
            }
            return true;
        }
    }

    public List<Voice> getVoices() {
        return Collections.unmodifiableList(voices);
    }

    public void ended(Voice voice) {
        voices.remove(voice);
    }

    public void update(List<AmbientVolume> taken, boolean inWater, boolean day, double seconds, int maxVoices) {
        voices.removeIf(voice -> !voice.advance(seconds));
        freeVoices = maxVoices - voices.size();
        if (taken.isEmpty()) {
            current.forEach(this::stop);
            current.clear();
            return;
        }
        var iterator = current.iterator();
        while (iterator.hasNext()) {
            var volume = iterator.next();
            if (inWater || !contains(taken, volume)) {
                stop(volume);
                iterator.remove();
            }
        }
        if (inWater) {
            return;
        }
        var sorted = new ArrayList<>(taken);
        sorted.sort(HIGHEST_PRIORITY_FIRST);
        var topPriority = sorted.getFirst().getPriority();
        for (var volume : sorted) {
            if (volume.getPriority() >= topPriority) {
                countWaits(volume, seconds);
                play(volume, day);
                followDay(volume, day);
            } else {
                stop(volume);
            }
            if (!contains(current, volume)) {
                current.add(volume);
            }
        }
    }

    public void clear() {
        voices.clear();
        current.clear();
        waits.clear();
        lastDay = null;
    }

    float getWaitingSeconds(Sound sound) {
        var wait = waits.get(sound);
        return wait == null ? 0f : wait.seconds;
    }

    boolean isWaiting(Sound sound) {
        var wait = waits.get(sound);
        return wait != null && wait.needed;
    }

    private void countWaits(AmbientVolume volume, double seconds) {
        for (var sound : volume.getSounds()) {
            var wait = waits.get(sound);
            if (wait == null || sound.chance() == 100 || !wait.needed) {
                continue;
            }
            wait.seconds = (float) (wait.seconds + seconds);
            if (wait.seconds > sound.clip().seconds()) {
                wait.seconds = 0f;
                wait.needed = false;
            }
        }
    }

    private void play(AmbientVolume volume, boolean day) {
        for (var sound : volume.getSounds()) {
            var wait = waits.computeIfAbsent(sound, ignored -> new Wait());
            if (wait.seconds > 0f || voiceOf(sound) != null) {
                continue;
            }
            if (sound.chance() <= rand.getAsInt() % 100 || !isTime(sound.type(), day)) {
                wait.needed = true;
                continue;
            }
            if (freeVoices <= 0) {
                return;
            }
            freeVoices--;
            voices.add(new Voice(volume, sound));
        }
    }

    private void followDay(AmbientVolume volume, boolean day) {
        if (lastDay == null) {
            lastDay = day;
        }
        if (lastDay != day) {
            lastDay = day;
            stop(volume);
            play(volume, day);
        }
    }

    private void stop(AmbientVolume volume) {
        for (var sound : volume.getSounds()) {
            var voice = voiceOf(sound);
            if (voice == null) {
                continue;
            }
            var wait = waits.get(sound);
            if (wait != null) {
                wait.seconds = 0f;
            }
            if (volume.getFadeOutSeconds() > 0f) {
                if (voice.fade != 2) {
                    voice.fade = 2;
                    voice.fadeSeconds = 0f;
                    voice.fadeLength = volume.getFadeOutSeconds();
                }
            } else {
                voices.remove(voice);
            }
        }
    }

    private @Nullable Voice voiceOf(Sound sound) {
        for (var voice : voices) {
            if (voice.sound == sound) {
                return voice;
            }
        }
        return null;
    }

    private static boolean isTime(int type, boolean day) {
        return type == Sound.ALWAYS || type == Sound.DAY && day || type == Sound.NIGHT && !day;
    }

    private static boolean contains(List<AmbientVolume> volumes, AmbientVolume volume) {
        for (var each : volumes) {
            if (each == volume) {
                return true;
            }
        }
        return false;
    }
}

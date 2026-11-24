package com.rost.cruma.core.audio;

import com.rost.cruma.core.unreal.USound;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class SoundClipCache {
    private static final Logger log = LogManager.getLogger(SoundClipCache.class);
    private final ConcurrentHashMap<String, SoundClip> clips = new ConcurrentHashMap<>();
    private final KeySetView<String, Boolean> failed = ConcurrentHashMap.newKeySet();
    private final AtomicLong bytes = new AtomicLong();

    public SoundClip get(String fullName, Supplier<USound> loader) {
        var cached = clips.get(fullName);
        if (cached != null) {
            return cached;
        }
        if (failed.contains(fullName)) {
            return null;
        }
        try {
            var sound = loader.get();
            if (sound == null || sound.getData().length == 0) {
                throw new IllegalArgumentException("no sound data");
            }
            var clip = WavDecoder.decode(fullName, sound.getData());
            var previous = clips.putIfAbsent(fullName, clip);
            if (previous == null) {
                bytes.addAndGet(clip.bytes());
                return clip;
            }
            return previous;
        } catch (RuntimeException e) {
            if (failed.add(fullName)) {
                log.warn("Cannot decode {}: {}", fullName, e.toString());
            }
            return null;
        }
    }

    public int size() {
        return clips.size();
    }

    public long getBytes() {
        return bytes.get();
    }
}

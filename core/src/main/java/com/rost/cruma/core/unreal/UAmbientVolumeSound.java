package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UObjectRef.Optional;
import java.util.Set;

public class UAmbientVolumeSound extends UObject {
    private static final Set<String> CLASS_DEFAULTS = Set.of("Type", "Volume", "Pitch", "Random");

    private final UObjectRef<USound, Optional> sound = UObjectRef.optional();
    private int type;
    private int volume = 250;
    private float pitch = 1f;
    private int random = 100;

    public UAmbientVolumeSound(UArchive archive) {
        super(archive);
    }

    @Override
    public boolean setProperty(UProperty property) {
        return switch (property.getName()) {
            case "Type" -> {
                type = property.getUInt8Value() & 0xff;
                yield true;
            }
            case "Volume" -> {
                volume = property.getInt32Value();
                yield true;
            }
            case "Pitch" -> {
                pitch = property.getFloatValue();
                yield true;
            }
            case "Random" -> {
                random = property.getInt32Value();
                yield true;
            }
            case "AmbientSound" -> {
                sound.fromProperty(archive, property);
                yield true;
            }
            default -> false;
        };
    }

    @Override
    public Set<String> getClassDefaultNames() {
        return CLASS_DEFAULTS;
    }

    public int getType() {
        return type;
    }

    public int getVolume() {
        return volume;
    }

    public float getPitch() {
        return pitch;
    }

    public int getRandom() {
        return random;
    }

    public UObjectRef<USound, Optional> getSound() {
        return sound;
    }
}

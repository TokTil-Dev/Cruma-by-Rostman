package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.UObjectRef.Required;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public class ULevel extends UObject {
    private final List<UObjectRef<UObject, Optional>> objects = new ArrayList<>();
    private int actorStart;
    private int actorCount;
    private Url url;
    private ReachSpec[] reachSpecs;
    private final UObjectRef<UModel, Required> model = UObjectRef.required();

    public ULevel(UArchive archive) {
        super(archive);
    }

    public static class Url {
        public String protocol;
        public String host;

        public String map;
        public String portal;
        public String[] options;
        public int port;

        public boolean valid;

        public Url(UArchive archive) {
            protocol = archive.readString();
            host = archive.readString();
            map = archive.readString();
            portal = archive.readString();
            options = archive.readArray(String.class, UArchive::readString);
            port = archive.readInt();
            valid = archive.readBool();
        }

        public String getProtocol() {
            return this.protocol;
        }

        public String getHost() {
            return this.host;
        }

        public String getMap() {
            return this.map;
        }

        public String getPortal() {
            return this.portal;
        }

        public String[] getOptions() {
            return this.options;
        }

        public int getPort() {
            return this.port;
        }

        public boolean isValid() {
            return this.valid;
        }
    }

    public enum ReachSpecFlags {
        RS_WALK(1),
        RS_FLY(2),
        RS_SWIM(4),
        RS_JUMP(8),
        RS_DOOR(16),
        RS_SPECIAL(32),
        RS_LADDER(64),
        RS_PROSCRIBED(128),
        RS_FORCED(256),
        RS_PLAYERONLY(512);
        private final int value;

        ReachSpecFlags(int value) {
            this.value = value;
        }

        public static ReachSpecFlags fromValue(int value) {
            for (var flag : values()) {
                if (flag.value == value) {
                    return flag;
                }
            }
            throw new IllegalArgumentException("Invalid value: " + value);
        }

        public int getValue() {
            return this.value;
        }
    }

    public static class ReachSpec {
        public int distance;
        public final UObjectRef<UActor, Required> start = UObjectRef.required();
        public final UObjectRef<UActor, Required> end = UObjectRef.required();
        public int collisionRadius;
        public int collisionHeight;
        public int flags;
        public boolean pruned;

        public ReachSpec(UArchive archive) {
            distance = archive.readInt();
            start.fromArchive(archive);
            end.fromArchive(archive);
            collisionRadius = archive.readInt();
            collisionHeight = archive.readInt();
            flags = archive.readInt();
            pruned = archive.readBool();
        }

        public int getDistance() {
            return this.distance;
        }

        public UObjectRef<UActor, Required> getStart() {
            return this.start;
        }

        public UObjectRef<UActor, Required> getEnd() {
            return this.end;
        }

        public int getCollisionRadius() {
            return this.collisionRadius;
        }

        public int getCollisionHeight() {
            return this.collisionHeight;
        }

        public int getFlags() {
            return this.flags;
        }

        public boolean isPruned() {
            return this.pruned;
        }
    }

    public void deserialize() {
        super.deserialize();
        if (archive.getHeader().getLicenseVersion() >= 23) {
            readObjectArray();
        }
        actorStart = objects.size();
        actorCount = readObjectArray();

        url = new Url(archive);

        archive.skipBytes(2);

        reachSpecs = archive.readArray(ReachSpec.class, ReachSpec::new);
        model.fromArchive(archive);
    }

    public List<UObjectRef<UObject, Optional>> getObjects() {
        return this.objects;
    }

    public List<UObjectRef<UObject, Optional>> getActors() {
        return Collections.unmodifiableList(objects.subList(actorStart, actorStart + actorCount));
    }

    public @Nullable ZoneInfoActor getLevelInfo() {
        if (actorCount <= 0) {
            return null;
        }
        return objects.get(actorStart).get() instanceof ZoneInfoActor info ? info : null;
    }

    public Url getUrl() {
        return this.url;
    }

    public ReachSpec[] getReachSpecs() {
        return this.reachSpecs;
    }

    public UObjectRef<UModel, Required> getModel() {
        return this.model;
    }

    private int readObjectArray() {
        var count = archive.readInt();
        archive.readInt();
        if (count < 0) {
            throw new IllegalStateException("Negative level object count: " + count);
        }
        for (var i = 0; i < count; i++) {
            var objectRef = UObjectRef.<UObject>optional();
            objectRef.fromArchive(archive);
            objects.add(objectRef);
        }
        return count;
    }
}

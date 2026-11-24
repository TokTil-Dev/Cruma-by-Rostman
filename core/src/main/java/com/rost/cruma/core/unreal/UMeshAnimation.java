package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UMeshAnimSequences.Checks;
import glm_.vec3.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import org.eclipse.collections.api.factory.primitive.FloatLists;
import org.eclipse.collections.api.list.primitive.ImmutableFloatList;
import org.jetbrains.annotations.Nullable;

public final class UMeshAnimation extends UObject {
    private int version;
    private NamedBone[] bones = new NamedBone[0];
    private final List<byte[]> chunks = new ArrayList<>();
    private Sequence[] sequences = new Sequence[0];
    private @Nullable IntFunction<Motion> motionOf;

    public UMeshAnimation(UArchive archive) {
        super(archive);
    }

    public record NamedBone(String name, int parentIndex) {}

    public record Sequence(
            String name,
            int startFrame,
            int frameCount,
            float rate,
            float shotTime,
            List<CameraMark> cameraMarks,
            float preShotTime,
            ImmutableFloatList shotTimes,
            ImmutableFloatList preShotTimes,
            ImmutableFloatList channelTimes) {
        public Sequence {
            cameraMarks = List.copyOf(cameraMarks);
        }

        public Sequence(
                String name,
                int startFrame,
                int frameCount,
                float rate,
                float shotTime,
                List<CameraMark> cameraMarks,
                float preShotTime,
                ImmutableFloatList shotTimes,
                ImmutableFloatList preShotTimes) {
            this(
                    name,
                    startFrame,
                    frameCount,
                    rate,
                    shotTime,
                    cameraMarks,
                    preShotTime,
                    shotTimes,
                    preShotTimes,
                    FloatLists.immutable.empty());
        }

        public Sequence(
                String name,
                int startFrame,
                int frameCount,
                float rate,
                float shotTime,
                List<CameraMark> cameraMarks,
                float preShotTime) {
            this(
                    name,
                    startFrame,
                    frameCount,
                    rate,
                    shotTime,
                    cameraMarks,
                    preShotTime,
                    marks(shotTime),
                    marks(preShotTime));
        }

        public Sequence(
                String name, int startFrame, int frameCount, float rate, float shotTime, List<CameraMark> cameraMarks) {
            this(name, startFrame, frameCount, rate, shotTime, cameraMarks, -1f);
        }

        public Sequence(String name, int startFrame, int frameCount, float rate) {
            this(name, startFrame, frameCount, rate, -1f, List.of());
        }

        private static ImmutableFloatList marks(float time) {
            return time < 0f ? FloatLists.immutable.empty() : FloatLists.immutable.of(time);
        }
    }

    public record CameraMark(
            float time, Kind kind, int bone, String pawnSequence, float pawnScale, Vec3 pawnOffset, boolean ownCamera) {
        public enum Kind {
            RELEASE,
            AROUND_BONE,
            ON_BONE,
            BONE_TO_BONE,
            CAMERA_PAWN;

            static Kind of(int value) {
                return value > 0 && value < values().length ? values()[value] : RELEASE;
            }
        }
    }

    public record Track(float[] quaternions, float[] positions, float[] times) {
        public int quaternionKeys() {
            return quaternions.length / 4;
        }

        public int positionKeys() {
            return positions.length / 3;
        }
    }

    public record Motion(float trackTime, Track[] tracks) {}

    @Override
    public void deserialize() {
        super.deserialize();
        version = archive.readInt();
        bones = archive.readArray(NamedBone.class, in -> {
            var name = in.readName();
            in.readInt();
            return new NamedBone(name, in.readInt());
        });
        var header = archive.getHeader();
        if (header.getFileVersion() > 122 && header.getLicenseVersion() > 24) {
            var end = archive.readInt();
            var count = archive.readIndex();
            requireCount("motion chunks", count);
            for (var i = 0; i < count; i++) {
                var chunkEnd = archive.readInt();
                var start = archive.getOffset();
                if (chunkEnd < start || chunkEnd > end) {
                    throw malformedException(
                            "motion chunk " + i + " ends at " + chunkEnd + " outside " + start + ".." + end);
                }
                chunks.add(readBytes(start, chunkEnd - start));
                archive.setPosition(chunkEnd);
            }
            archive.setPosition(end);
        } else {
            var count = archive.readIndex();
            requireCount("motion chunks", count);
            for (var i = 0; i < count; i++) {
                var start = archive.getOffset();
                skipMotion();
                chunks.add(readBytes(start, archive.getOffset() - start));
            }
        }
        var checks = new Checks() {
            @Override
            public void remaining(String label, int bytes) {
                if (bytes < 0 || archive.getRemainingInObject() < bytes) {
                    throw malformedException("truncated " + label);
                }
            }

            @Override
            public void count(String label, int count) {
                requireCount(label, count);
            }
        };
        var sequenceCount = archive.readIndex();
        requireCount("sequences", sequenceCount);
        sequences = new Sequence[sequenceCount];
        for (var i = 0; i < sequenceCount; i++) {
            var sequence = UMeshAnimSequences.read(archive, checks);
            sequences[i] = new Sequence(
                    sequence.name(),
                    sequence.startFrame(),
                    sequence.frameCount(),
                    sequence.rate(),
                    sequence.shotTime(),
                    sequence.cameraMarks(),
                    sequence.preShotTime(),
                    sequence.shotTimes(),
                    sequence.preShotTimes(),
                    sequence.channelTimes());
        }
    }

    public UMeshAnimation select(Predicate<String> keep) {
        return select(keep, _ -> false);
    }

    public UMeshAnimation select(Predicate<String> keep, Predicate<String> first) {
        var front = new ArrayList<Integer>(sequences.length);
        var back = new ArrayList<Integer>(sequences.length);
        for (var index = 0; index < sequences.length; index++) {
            if (keep.test(sequences[index].name())) {
                (first.test(sequences[index].name()) ? front : back).add(index);
            }
        }
        front.addAll(back);
        var same = front.size() == sequences.length;
        for (var position = 0; same && position < front.size(); position++) {
            same = front.get(position) == position;
        }
        if (same) {
            return this;
        }
        var selected = new UMeshAnimation(archive);
        selected.setName(getName());
        selected.version = version;
        selected.bones = bones;
        var kept = new Sequence[front.size()];
        for (var position = 0; position < kept.length; position++) {
            int index = front.get(position);
            kept[position] = sequences[index];
            selected.chunks.add(index < chunks.size() ? chunks.get(index) : new byte[0]);
        }
        selected.sequences = kept;
        var motions = motionOf;
        if (motions != null) {
            var from = List.copyOf(front);
            selected.motionOf = position -> motions.apply(from.get(position));
        }
        return selected;
    }

    public UMeshAnimation join(UMeshAnimation mount, String prefix, String hold) {
        var root = bones.length == 0 ? "" : SkeletalPose.boneKey(bones[0].name());
        var added = new ArrayList<Integer>();
        var joinedBones = new ArrayList<>(List.of(bones));
        for (var index = 0; index < mount.bones.length; index++) {
            if (!SkeletalPose.boneKey(mount.bones[index].name()).equals(root)) {
                added.add(index);
                joinedBones.add(new NamedBone(prefix + mount.bones[index].name(), mount.bones[index].parentIndex()));
            }
        }
        var joined = new UMeshAnimation(archive);
        joined.setName(getName());
        joined.version = version;
        joined.bones = joinedBones.toArray(NamedBone[]::new);
        joined.sequences = sequences;
        joined.chunks.addAll(chunks);
        var held = Math.max(0, mount.findSequenceIndex(hold));
        joined.motionOf = sequence -> {
            var own = decodeMotion(sequence);
            var found = mount.findSequenceIndex(sequences[sequence].name());
            var carried = mount.decodeMotion(found >= 0 ? found : held);
            var tracks = new ArrayList<>(List.of(own.tracks()));
            while (tracks.size() < bones.length) {
                tracks.add(new Track(new float[0], new float[0], new float[0]));
            }
            var scale = carried.trackTime() > 0 ? own.trackTime() / carried.trackTime() : 1;
            for (var index : added) {
                var track = index < carried.tracks().length
                        ? carried.tracks()[index]
                        : new Track(new float[0], new float[0], new float[0]);
                tracks.add(found >= 0 ? scaled(track, scale) : firstKey(track));
            }
            return new Motion(own.trackTime(), tracks.toArray(Track[]::new));
        };
        return joined;
    }

    public int findSequenceIndex(String name) {
        for (var i = 0; i < sequences.length; i++) {
            if (sequences[i].name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    public Motion decodeMotion(int sequence) {
        var motions = motionOf;
        if (motions != null) {
            return motions.apply(sequence);
        }
        if (sequence < 0 || sequence >= chunks.size()) {
            throw new IndexOutOfBoundsException("sequence " + sequence + " of " + chunks.size());
        }
        var data = ByteBuffer.wrap(chunks.get(sequence)).order(ByteOrder.LITTLE_ENDIAN);
        data.position(12);
        var trackTime = data.getFloat();
        data.getInt();
        data.getInt();
        var boneIndices = compactIndex(data);
        data.position(data.position() + boneIndices * 4);
        var trackCount = compactIndex(data);
        var tracks = new Track[trackCount];
        for (var i = 0; i < trackCount; i++) {
            tracks[i] = track(data);
        }
        return new Motion(trackTime, tracks);
    }

    public int getVersion() {
        return version;
    }

    public NamedBone[] getBones() {
        return bones;
    }

    public Sequence[] getSequences() {
        return sequences;
    }

    private byte[] readBytes(int start, int length) {
        var saved = archive.getOffset();
        archive.setPosition(start);
        var data = new byte[length];
        for (var i = 0; i < length; i++) {
            data[i] = archive.readByte();
        }
        archive.setPosition(saved);
        return data;
    }

    private void skipMotion() {
        archive.skipBytes(12 + 4 + 4 + 4);
        skipArray(4);
        var tracks = archive.readIndex();
        requireCount("tracks", tracks);
        for (var i = 0; i <= tracks; i++) {
            archive.readInt();
            skipArray(16);
            skipArray(12);
            skipArray(4);
        }
    }

    private void skipArray(int elementSize) {
        var count = archive.readIndex();
        requireCount("keys", count);
        archive.skipBytes(Math.multiplyExact(count, elementSize));
    }

    private void requireCount(String label, int count) {
        if (count < 0 || count > 4_000_000) {
            throw malformedException("invalid " + label + " count " + count);
        }
    }

    private IllegalStateException malformedException(String detail) {
        return new IllegalStateException("Malformed MeshAnimation " + getName() + ": " + detail);
    }

    private static Track track(ByteBuffer data) {
        data.getInt();
        var quaternions = readFloats(data, 4);
        var positions = readFloats(data, 3);
        var times = readFloats(data, 1);
        return new Track(quaternions, positions, times);
    }

    private static float[] readFloats(ByteBuffer data, int perKey) {
        var count = compactIndex(data);
        if (count < 0 || count * perKey * 4 > data.remaining()) {
            throw new IllegalStateException("Malformed animation keys: " + count);
        }
        var values = new float[count * perKey];
        for (var i = 0; i < values.length; i++) {
            values[i] = data.getFloat();
        }
        return values;
    }

    private static int compactIndex(ByteBuffer data) {
        var first = data.get() & 0xff;
        var negative = (first & 0x80) != 0;
        var value = first & 0x3f;
        if ((first & 0x40) != 0) {
            var shift = 6;
            int next;
            do {
                next = data.get() & 0xff;
                value |= (next & 0x7f) << shift;
                shift += 7;
            } while ((next & 0x80) != 0 && shift < 32);
        }
        return negative ? -value : value;
    }

    private static Track scaled(Track track, float scale) {
        if (scale == 1) {
            return track;
        }
        var times = track.times().clone();
        for (var index = 0; index < times.length; index++) {
            times[index] *= scale;
        }
        return new Track(track.quaternions(), track.positions(), times);
    }

    private static Track firstKey(Track track) {
        return new Track(
                track.quaternions().length >= 4 ? Arrays.copyOf(track.quaternions(), 4) : new float[0],
                track.positions().length >= 3 ? Arrays.copyOf(track.positions(), 3) : new float[0],
                track.times().length > 0 ? new float[] {0} : new float[0]);
    }
}

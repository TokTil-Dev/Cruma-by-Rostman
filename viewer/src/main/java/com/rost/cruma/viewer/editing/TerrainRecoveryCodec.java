package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.P152TerrainExport;
import com.rost.cruma.core.editing.TerrainEditExport;
import com.rost.cruma.core.editing.TerrainEditHistory;
import com.rost.cruma.core.editing.TerrainEditHistory.Checkpoint;
import com.rost.cruma.core.editing.TerrainHeightPatch;
import com.rost.cruma.viewer.editing.EditingRecoveryStore.UnsupportedFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class TerrainRecoveryCodec {
    public static final String UNLIT_PROFILE = "native-unlit-v1";

    public static final int MOST_DOCUMENTS = 9;

    private static final int MAX_BYTES = 12 * 1024 * 1024;

    public static final int MAX_DRAFT_BYTES = MOST_DOCUMENTS * MAX_BYTES;

    private static final int MAGIC = 0x43525452;
    private static final int VERSION = 2;
    private static final int SHARED_VERSION = 3;
    private static final int HEADER_BYTES = 44;
    private static final int GRID = 256;
    private static final int MAX_SAMPLES = GRID * GRID;
    private static final int MAX_STRING_BYTES = 4096;

    private TerrainRecoveryCodec() {}

    public record State(
            String relativeMap,
            String owner,
            String originalSha256,
            Checkpoint checkpoint,
            String profileId,
            String heightSource,
            String heightSha256,
            Shared shared) {
        public State(
                String relativeMap,
                String owner,
                String originalSha256,
                Checkpoint checkpoint,
                String profileId,
                String heightSource,
                String heightSha256) {
            this(relativeMap, owner, originalSha256, checkpoint, profileId, heightSource, heightSha256, Shared.NONE);
        }

        public State(String relativeMap, String owner, String originalSha256, Checkpoint checkpoint) {
            this(relativeMap, owner, originalSha256, checkpoint, UNLIT_PROFILE, "", "");
        }

        public State {
            requirePortablePath(relativeMap);
            requireText(owner, "Terrain owner");
            if (originalSha256 == null || !originalSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid Terrain source fingerprint");
            }
            requireText(profileId, "Terrain profile");
            if (UNLIT_PROFILE.equals(profileId)) {
                if (!"".equals(heightSource) || !"".equals(heightSha256)) {
                    throw new IllegalArgumentException(
                            "Unlit Terrain recovery has unexpected external height metadata");
                }
            } else if (TerrainEditExport.PROFILE_ID.equals(profileId)
                    && "".equals(heightSource)
                    && "".equals(heightSha256)) {
            } else {
                requirePortablePath(heightSource);
                if (heightSha256 == null || !heightSha256.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid external height fingerprint");
                }
            }
            Objects.requireNonNull(checkpoint, "Terrain checkpoint");
            validate(checkpoint);
            Objects.requireNonNull(shared, "Other Terrains");
            var owners = new HashSet<String>();
            owners.add(owner);
            for (var other : shared.documents()) {
                if (!owners.add(other.owner())) {
                    throw new IllegalArgumentException("A Terrain recovery names one owner twice");
                }
            }
        }

        public List<State> documents() {
            if (shared.documents().isEmpty()) {
                return List.of(this);
            }
            var all = new ArrayList<State>();
            all.add(withShared(Shared.NONE));
            all.addAll(shared.documents());
            return List.copyOf(all);
        }

        public State withShared(Shared value) {
            return new State(
                    relativeMap, owner, originalSha256, checkpoint, profileId, heightSource, heightSha256, value);
        }
    }

    public record Shared(List<State> documents, List<List<Integer>> undo, List<List<Integer>> redo, int active) {
        public static final Shared NONE = new Shared(List.of(), List.of(), List.of(), 0);

        public Shared {
            documents = List.copyOf(documents);
            undo = copySteps(undo);
            redo = copySteps(redo);
            if (documents.size() + 1 > MOST_DOCUMENTS) {
                throw new IllegalArgumentException("A Terrain recovery holds more Terrains than can be open");
            }
            if (active < 0 || active > documents.size()) {
                throw new IllegalArgumentException("Invalid active Terrain");
            }
            for (var document : documents) {
                if (!document.shared().documents().isEmpty()
                        || !document.shared().undo().isEmpty()
                        || !document.shared().redo().isEmpty()) {
                    throw new IllegalArgumentException("A Terrain of a recovery holds other Terrains");
                }
            }
            var counts = new int[documents.size() + 1];
            for (var steps : List.of(undo, redo)) {
                for (var step : steps) {
                    if (step.isEmpty()) {
                        throw new IllegalArgumentException("A Terrain recovery step names no Terrain");
                    }
                    var previous = -1;
                    for (var index : step) {
                        if (index <= previous || index > documents.size()) {
                            throw new IllegalArgumentException("Invalid Terrain of a recovery step");
                        }
                        previous = index;
                        if (++counts[index] > TerrainEditHistory.MAX_COMMANDS) {
                            throw new IllegalArgumentException("Terrain recovery exceeds command limit");
                        }
                    }
                }
            }
        }

        private static List<List<Integer>> copySteps(List<List<Integer>> steps) {
            var copy = new ArrayList<List<Integer>>(steps.size());
            for (var step : steps) {
                copy.add(List.copyOf(step));
            }
            return List.copyOf(copy);
        }
    }

    public static byte[] encode(State state) throws IOException {
        Objects.requireNonNull(state, "Terrain recovery state");
        var shared = !state.shared().equals(Shared.NONE);
        var body = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(body)) {
            writeDocument(out, state);
            if (shared) {
                var others = state.shared();
                out.writeInt(others.documents().size());
                for (var document : others.documents()) {
                    writeDocument(out, document);
                }
                writeSteps(out, others.undo());
                writeSteps(out, others.redo());
                out.writeInt(others.active());
            }
        }
        var payload = body.toByteArray();
        if (payload.length > (shared ? MAX_DRAFT_BYTES : MAX_BYTES) - HEADER_BYTES) {
            throw invalid("Terrain recovery exceeds its size limit");
        }
        var bytes = new ByteArrayOutputStream(payload.length + HEADER_BYTES);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(shared ? SHARED_VERSION : VERSION);
            out.writeInt(payload.length);
            out.write(hash(payload));
            out.write(payload);
        }
        return bytes.toByteArray();
    }

    public static State decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 8 || bytes.length > MAX_DRAFT_BYTES) {
            throw invalid("Invalid Terrain recovery size");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw invalid("Invalid Terrain recovery format");
            }
            var version = input.readInt();
            if (version != 1 && version != VERSION && version != SHARED_VERSION) {
                throw new UnsupportedFormat(
                        "This Terrain recovery uses an unsupported version. It has been preserved.");
            }
            if (version != SHARED_VERSION && bytes.length > MAX_BYTES) {
                throw invalid("Invalid Terrain recovery size");
            }
            if (bytes.length < HEADER_BYTES) {
                throw invalid("Truncated Terrain recovery header");
            }
            var size = input.readInt();
            if (size != bytes.length - HEADER_BYTES) {
                throw invalid("Terrain recovery length differs");
            }
            var expected = input.readNBytes(32);
            var body = input.readNBytes(size);
            if (!MessageDigest.isEqual(expected, hash(body))) {
                throw invalid("Terrain recovery checksum differs");
            }
            try (var data = new DataInputStream(new ByteArrayInputStream(body))) {
                var first = readDocument(data, version);
                if (version == SHARED_VERSION) {
                    var count = count(data, MOST_DOCUMENTS - 1, 1);
                    var others = new ArrayList<State>(count);
                    for (var i = 0; i < count; i++) {
                        others.add(readDocument(data, version));
                    }
                    var undo = readSteps(data, count + 1);
                    var redo = readSteps(data, count + 1);
                    var active = data.readInt();
                    first = first.withShared(new Shared(others, undo, redo, active));
                }
                if (data.available() != 0) {
                    throw invalid("Trailing Terrain recovery bytes");
                }
                return first;
            }
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid Terrain recovery metadata: " + failure.getMessage(), failure);
        }
    }

    private static void writeDocument(DataOutputStream out, State state) throws IOException {
        writeString(out, state.relativeMap());
        writeString(out, state.owner());
        writeString(out, state.originalSha256());
        writeString(out, state.profileId());
        writeString(out, state.heightSource());
        writeString(out, state.heightSha256());
        patch(out, state.checkpoint().current());
        patch(out, state.checkpoint().saved());
        writePatches(out, state.checkpoint().undo());
        writePatches(out, state.checkpoint().redo());
    }

    private static State readDocument(DataInputStream data, int version) throws IOException {
        var relative = readString(data);
        var owner = readString(data);
        var fingerprint = readString(data);
        var profile = version == 1 ? UNLIT_PROFILE : readString(data);
        var heightSource = version == 1 ? "" : readString(data);
        var heightHash = version == 1 ? "" : readString(data);
        if (profile.equals(P152TerrainExport.SINGLE_SAMPLE_PROFILE_ID)) {
            profile = P152TerrainExport.PROFILE_ID;
        }
        if (!profile.equals(UNLIT_PROFILE)
                && !profile.equals(P152TerrainExport.PROFILE_ID)
                && !profile.equals(TerrainEditExport.PROFILE_ID)) {
            throw new UnsupportedFormat("This Terrain recovery profile is not supported. It has been preserved.");
        }
        var current = patch(data);
        var saved = patch(data);
        var budget = new long[] {TerrainEditHistory.MAX_PATCH_BYTES};
        var undo = readPatches(data, TerrainEditHistory.MAX_COMMANDS, budget);
        var redo = readPatches(data, TerrainEditHistory.MAX_COMMANDS - undo.size(), budget);
        return new State(
                relative,
                owner,
                fingerprint,
                new Checkpoint(current, saved, undo, redo),
                profile,
                heightSource,
                heightHash);
    }

    private static void writeSteps(DataOutputStream out, List<List<Integer>> steps) throws IOException {
        out.writeInt(steps.size());
        for (var step : steps) {
            out.writeInt(step.size());
            for (var index : step) {
                out.writeInt(index);
            }
        }
    }

    private static List<List<Integer>> readSteps(DataInputStream input, int documents) throws IOException {
        var count = count(input, MOST_DOCUMENTS * TerrainEditHistory.MAX_COMMANDS, 4);
        var steps = new ArrayList<List<Integer>>(count);
        for (var i = 0; i < count; i++) {
            var size = count(input, documents, 4);
            var step = new ArrayList<Integer>(size);
            for (var j = 0; j < size; j++) {
                step.add(input.readInt());
            }
            steps.add(step);
        }
        return steps;
    }

    private static void validate(Checkpoint checkpoint) {
        requireGrid(checkpoint.current());
        requireGrid(checkpoint.saved());
        if (checkpoint.undo().size() + checkpoint.redo().size() > TerrainEditHistory.MAX_COMMANDS) {
            throw new IllegalArgumentException("Terrain recovery exceeds command limit");
        }
        long retained = 0;
        for (var commands : List.of(checkpoint.undo(), checkpoint.redo())) {
            for (var patch : commands) {
                requireGrid(patch);
                retained += patch.getRetainedBytes();
            }
        }
        if (retained > TerrainEditHistory.MAX_PATCH_BYTES) {
            throw new IllegalArgumentException("Terrain recovery exceeds patch byte limit");
        }
    }

    private static void requireGrid(TerrainHeightPatch patch) {
        Objects.requireNonNull(patch, "Terrain patch");
        if (patch.getWidth() != GRID || patch.getHeight() != GRID || patch.size() > MAX_SAMPLES) {
            throw new IllegalArgumentException("Terrain recovery requires a 256x256 grid");
        }
    }

    private static void requirePortablePath(String path) {
        requireText(path, "Terrain map path");
        if (path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Terrain map path must be portable and relative");
        }
        for (var component : path.split("/", -1)) {
            if (component.isEmpty()
                    || component.equals(".")
                    || component.equals("..")
                    || component.endsWith(".")
                    || component.endsWith(" ")
                    || component.chars().anyMatch(value -> "<>\"|?*".indexOf(value) >= 0)) {
                throw new IllegalArgumentException("Terrain map path must stay inside the client");
            }
        }
    }

    private static void requireText(String value, String label) {
        if (value == null
                || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    private static void writePatches(DataOutputStream out, List<TerrainHeightPatch> values) throws IOException {
        out.writeInt(values.size());
        for (var value : values) {
            patch(out, value);
        }
    }

    private static List<TerrainHeightPatch> readPatches(DataInputStream input, int maximum, long[] budget)
            throws IOException {
        var count = count(input, maximum, 12);
        var values = new ArrayList<TerrainHeightPatch>(count);
        for (var i = 0; i < count; i++) {
            var value = patch(input);
            budget[0] -= value.getRetainedBytes();
            if (budget[0] < 0) {
                throw invalid("Terrain recovery exceeds patch byte limit");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static void patch(DataOutputStream out, TerrainHeightPatch patch) throws IOException {
        out.writeInt(patch.getWidth());
        out.writeInt(patch.getHeight());
        out.writeInt(patch.size());
        for (var i = 0; i < patch.size(); i++) {
            out.writeInt(patch.getIndex(i));
            out.writeShort(patch.getBefore(i));
            out.writeShort(patch.getAfter(i));
        }
    }

    private static TerrainHeightPatch patch(DataInputStream input) throws IOException {
        if (input.readInt() != GRID || input.readInt() != GRID) {
            throw invalid("Terrain recovery grid differs");
        }
        var count = count(input, MAX_SAMPLES, 8);
        var indices = new int[count];
        var before = new short[count];
        var after = new short[count];
        for (var i = 0; i < count; i++) {
            indices[i] = input.readInt();
            before[i] = input.readShort();
            after[i] = input.readShort();
        }
        return new TerrainHeightPatch(GRID, GRID, indices, before, after);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        var bytes = StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        out.writeInt(bytes.remaining());
        out.write(bytes.array(), bytes.position(), bytes.remaining());
    }

    private static String readString(DataInputStream input) throws IOException {
        var count = count(input, MAX_STRING_BYTES, 1);
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input.readNBytes(count)))
                .toString();
    }

    private static int count(DataInputStream input, int maximum, int minimumBytes) throws IOException {
        var value = input.readInt();
        if (value < 0 || value > maximum || (long) value * minimumBytes > input.available()) {
            throw invalid("Invalid Terrain recovery count");
        }
        return value;
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }
}

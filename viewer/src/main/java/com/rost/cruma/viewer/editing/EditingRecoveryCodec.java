package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.editing.PackageEditDocument.RecoveryBundle;
import com.rost.cruma.core.editing.PackageEditDocument.Snapshot;
import com.rost.cruma.core.editing.PackageEditDocument.UnsupportedRecoveryFormat;
import com.rost.cruma.viewer.editing.EditingRecoveryStore.UnsupportedFormat;
import com.rost.cruma.viewer.editing.EditingSession.Asset;
import com.rost.cruma.viewer.editing.EditingSession.Checkpoint;
import com.rost.cruma.viewer.editing.EditingSession.Command;
import com.rost.cruma.viewer.editing.EditingSession.DocumentCommand;
import com.rost.cruma.viewer.editing.EditingSession.PivotMode;
import com.rost.cruma.viewer.editing.EditingSession.RecoveryState;
import com.rost.cruma.viewer.editing.EditingSession.State;
import com.rost.cruma.viewer.editing.EditingSession.TransformCommand;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class EditingRecoveryCodec {
    private static final int MAGIC = 0x43524543;
    private static final int VERSION = 4;
    private static final int COPIED_ASSET = 0;
    private static final int CLIENT_ASSET = 1;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_BYTES = 256 * 1024 * 1024;

    record Document(Path relativeSource, RecoveryBundle bundle, IdentityHashMap<Snapshot, Integer> slots) {}

    record Workspace(RecoveryState state, TerrainRecoveryCodec.State terrain) {}

    record Capture(
            RecoveryState state,
            Map<String, Document> documents,
            List<Asset> assetFiles,
            Map<Asset, String> clientPackages,
            TerrainRecoveryCodec.State terrain) {
        int mapCount() {
            var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            names.addAll(documents.keySet());
            if (terrain != null) {
                for (var each : terrain.documents()) {
                    var filename = Path.of(each.relativeMap()).getFileName().toString();
                    var extension = filename.lastIndexOf('.');
                    names.add(extension > 0 ? filename.substring(0, extension) : filename);
                }
            }
            return names.size();
        }

        List<Asset> copiedAssets() {
            var result = new ArrayList<Asset>();
            for (var asset : assetFiles) {
                if (!clientPackages.containsKey(asset)) {
                    result.add(asset);
                }
            }
            return result;
        }

        byte[] encode(Path assetDirectory, long maximumBytes) throws IOException {
            Files.createDirectories(assetDirectory);
            var assetSlots = new LinkedHashMap<Asset, Integer>();
            var assetBytes = 0L;
            for (var index = 0; index < assetFiles.size(); index++) {
                var asset = assetFiles.get(index);
                assetSlots.put(asset, index);
                if (clientPackages.containsKey(asset)) {
                    continue;
                }
                assetBytes = Math.addExact(assetBytes, Files.size(asset.path()));
                if (assetBytes > maximumBytes) {
                    throw new IOException("Recovery assets exceed the configured disk limit.");
                }
                var data = Files.readAllBytes(asset.path());
                if (!hash(data).equals(asset.fingerprint())) {
                    throw new IOException("An imported recovery asset changed: "
                            + asset.path().getFileName());
                }
                var destination = assetDirectory
                        .resolve(Integer.toString(index))
                        .resolve(asset.path().getFileName().toString());
                Files.createDirectories(destination.getParent());
                try (var channel =
                        FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(data);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
            }
            var bytes = new ByteArrayOutputStream();
            var remaining = Math.min(MAX_BYTES, maximumBytes - assetBytes);
            try (var out = new DataOutputStream(new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    check(1);
                    bytes.write(value);
                }

                @Override
                public void write(byte[] values, int offset, int length) throws IOException {
                    check(length);
                    bytes.write(values, offset, length);
                }

                private void check(int length) throws IOException {
                    if ((long) bytes.size() + length > remaining) {
                        throw new IOException("Recovery state exceeds the configured disk limit.");
                    }
                }
            })) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(state.revision());
                out.writeLong(state.committedCommands());
                var terrainBytes = terrain == null ? new byte[0] : TerrainRecoveryCodec.encode(terrain);
                out.writeInt(terrainBytes.length);
                out.write(terrainBytes);
                out.writeUTF(state.pivot().name());
                out.writeInt(assetFiles.size());
                for (var asset : assetFiles) {
                    var relative = clientPackages.get(asset);
                    if (relative == null) {
                        out.writeByte(COPIED_ASSET);
                        out.writeUTF(asset.path().getFileName().toString());
                    } else {
                        out.writeByte(CLIENT_ASSET);
                        out.writeUTF(relative);
                    }
                    out.writeUTF(asset.fingerprint());
                }
                out.writeInt(documents.size());
                for (var entry : documents.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue().relativeSource().toString());
                    var body = entry.getValue().bundle().encode();
                    out.writeInt(body.length);
                    out.write(body);
                }
                writeStates(out, state.originals());
                assets(out, state.assets(), assetSlots);
                assets(out, state.savedAssets(), assetSlots);
                writeStrings(out, state.selection());
                writeCommands(out, state.undo(), documents, assetSlots);
                writeCommands(out, state.redo(), documents, assetSlots);
                writeGroups(out, state.groups());
            }
            return bytes.toByteArray();
        }
    }

    static Capture capture(Path clientRoot, RecoveryState state) {
        return capture(clientRoot, state, null);
    }

    static Capture capture(Path clientRoot, RecoveryState state, TerrainRecoveryCodec.State terrain) {
        var history = new LinkedHashMap<String, List<Snapshot>>();
        var slots = new LinkedHashMap<String, IdentityHashMap<Snapshot, Integer>>();
        state.documents().keySet().forEach(name -> {
            history.put(name, new ArrayList<>());
            slots.put(name, new IdentityHashMap<>());
        });
        var assets = new LinkedHashSet<Asset>();
        assets.addAll(state.assets().values());
        assets.addAll(state.savedAssets().values());
        for (var commands : List.of(state.undo(), state.redo())) {
            for (var command : commands) {
                if (command instanceof DocumentCommand document) {
                    for (var checkpoint : List.of(document.before(), document.after())) {
                        assets.addAll(checkpoint.assets().values());
                        checkpoint.documents().forEach((name, snapshot) -> {
                            if (!slots.get(name).containsKey(snapshot)) {
                                slots.get(name).put(snapshot, history.get(name).size());
                                history.get(name).add(snapshot);
                            }
                        });
                    }
                }
            }
        }
        var clientPackages = new LinkedHashMap<Asset, String>();
        for (var asset : assets) {
            var relative = EditingSession.clientRelative(clientRoot, asset.path());
            if (relative != null) {
                clientPackages.put(asset, relative.toString().replace('\\', '/'));
            }
        }
        var documents = new LinkedHashMap<String, Document>();
        state.documents()
                .forEach((name, document) -> documents.put(
                        name,
                        new Document(
                                clientRoot.relativize(document.getSource()),
                                document.captureRecovery(history.get(name)),
                                slots.get(name))));
        return new Capture(
                state,
                Collections.unmodifiableMap(documents),
                List.copyOf(assets),
                Collections.unmodifiableMap(clientPackages),
                terrain);
    }

    static RecoveryState decode(Path clientRoot, Path assetDirectory, Path restoredAssets, byte[] bytes)
            throws IOException {
        return decodeWorkspace(clientRoot, assetDirectory, restoredAssets, bytes)
                .state();
    }

    static Workspace decodeWorkspace(Path clientRoot, Path assetDirectory, Path restoredAssets, byte[] bytes)
            throws IOException {
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Recovery state exceeds its size limit.");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid recovery format.");
            }
            var version = input.readInt();
            if (version < 1 || version > VERSION) {
                throw new UnsupportedFormat(
                        "This recovery payload uses an unsupported version. It has been preserved.");
            }
            var revision = input.readLong();
            var committedCommands = input.readLong();
            if (revision < 0 || committedCommands < 0) {
                throw new IOException("Invalid recovery revision.");
            }
            var terrain = readTerrain(input, version);
            if (terrain != null) {
                try (var validator = new TerrainEditingController(clientRoot)) {
                    validator.restoreRecovery(terrain);
                }
            }
            var pivot = PivotMode.valueOf(input.readUTF());
            var assets = new ArrayList<Asset>();
            var assetCount = count(input, MAX_ENTRIES);
            for (var index = 0; index < assetCount; index++) {
                var kind = version < 3 ? COPIED_ASSET : input.readUnsignedByte();
                var name = input.readUTF();
                var fingerprint = input.readUTF();
                if (!fingerprint.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid recovery asset fingerprint.");
                }
                if (kind == CLIENT_ASSET) {
                    assets.add(new Asset(clientPackage(clientRoot, name, fingerprint), fingerprint));
                    continue;
                }
                if (kind != COPIED_ASSET) {
                    throw new IOException("Unknown recovery asset kind.");
                }
                if (!Path.of(name).getFileName().toString().equals(name) || name.contains("/") || name.contains("\\")) {
                    throw new IOException("Invalid recovery asset name.");
                }
                var file = assetDirectory
                        .resolve(Integer.toString(index))
                        .resolve(name)
                        .toAbsolutePath()
                        .normalize();
                if (!file.startsWith(assetDirectory.toAbsolutePath().normalize())
                        || !Files.isRegularFile(file)
                        || !hash(Files.readAllBytes(file)).equals(fingerprint)) {
                    throw new IOException("Recovery asset is missing or changed: " + name);
                }
                if (restoredAssets != null) {
                    var stable = restoredAssets
                            .resolve(fingerprint)
                            .resolve(name)
                            .toAbsolutePath()
                            .normalize();
                    if (!stable.startsWith(restoredAssets.toAbsolutePath().normalize())) {
                        throw new IOException("Invalid recovery asset hash.");
                    }
                    Files.createDirectories(stable.getParent());
                    if (!Files.isRegularFile(stable)
                            || !hash(Files.readAllBytes(stable)).equals(fingerprint)) {
                        Files.copy(file, stable, StandardCopyOption.REPLACE_EXISTING);
                    }
                    file = stable;
                }
                assets.add(new Asset(file, fingerprint));
            }
            var documents = new LinkedHashMap<String, PackageEditDocument>();
            var history = new LinkedHashMap<String, List<Snapshot>>();
            var documentCount = count(input, 4096);
            for (var index = 0; index < documentCount; index++) {
                var name = input.readUTF();
                var source =
                        clientRoot.resolve(input.readUTF()).toAbsolutePath().normalize();
                if (!source.startsWith(clientRoot.toAbsolutePath().normalize())) {
                    throw new IOException("Recovery source is outside the selected client.");
                }
                var length = count(input, Math.min(MAX_BYTES, input.available()));
                var bundle = input.readNBytes(length);
                validateNativeEnvelope(bundle);
                var restored = PackageEditDocument.restoreRecovery(source, bundle);
                var fileName = source.getFileName().toString();
                if (!fileName.equalsIgnoreCase(name + ".unr") || documents.put(name, restored.document()) != null) {
                    throw new IOException("Invalid recovery map identity.");
                }
                history.put(name, restored.historySnapshots());
            }
            var originals = readStates(input);
            var currentAssets = assets(input, assets);
            var savedAssets = assets(input, assets);
            var selection = readStrings(input);
            var undo = readCommands(input, history, assets);
            var redo = readCommands(input, history, assets);
            var groups = version < 4 ? Map.<String, List<String>>of() : readGroups(input);
            if (undo.size() + redo.size() > 256 || input.available() != 0) {
                throw new IOException("Invalid recovery history length.");
            }
            return new Workspace(
                    new RecoveryState(
                            Map.copyOf(documents),
                            originals,
                            currentAssets,
                            savedAssets,
                            selection,
                            undo,
                            redo,
                            pivot,
                            revision,
                            committedCommands,
                            groups),
                    terrain);
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new IOException("Invalid recovery records.", failure);
        }
    }

    static void verifyStoredAssets(Path assetDirectory, byte[] bytes, long limit) throws IOException {
        record StoredAsset(int kind, String name, String fingerprint) {}
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid recovery format.");
            }
            var version = input.readInt();
            if (version < 1 || version > VERSION) {
                throw new UnsupportedFormat(
                        "This recovery payload uses an unsupported version. It has been preserved.");
            }
            IOException knownFailure = null;
            var revision = input.readLong();
            var commands = input.readLong();
            if (revision < 0 || commands < 0) {
                knownFailure = new IOException("Invalid recovery revision.");
            }
            if (version != 1) {
                var length = count(input, Math.min(TerrainRecoveryCodec.MAX_DRAFT_BYTES, input.available()));
                var body = input.readNBytes(length);
                if (length != 0) {
                    try {
                        TerrainRecoveryCodec.decode(body);
                    } catch (UnsupportedFormat future) {
                        throw future;
                    } catch (IOException invalid) {
                        knownFailure = invalid;
                    }
                }
            }
            input.readUTF();
            var size = count(input, MAX_ENTRIES);
            var assets = new ArrayList<StoredAsset>();
            for (var index = 0; index < size; index++) {
                var kind = version < 3 ? COPIED_ASSET : input.readUnsignedByte();
                assets.add(new StoredAsset(kind, input.readUTF(), input.readUTF()));
            }
            var documents = count(input, 4096);
            for (var index = 0; index < documents; index++) {
                input.readUTF();
                input.readUTF();
                var length = count(input, Math.min(MAX_BYTES, input.available()));
                var body = input.readNBytes(length);
                try {
                    validateNativeEnvelope(body);
                } catch (UnsupportedFormat future) {
                    throw future;
                } catch (IOException invalid) {
                    if (knownFailure == null) {
                        knownFailure = invalid;
                    }
                }
            }
            if (knownFailure != null) {
                throw knownFailure;
            }
            var total = (long) bytes.length;
            for (var index = 0; index < assets.size(); index++) {
                var kind = assets.get(index).kind();
                var name = assets.get(index).name();
                var fingerprint = assets.get(index).fingerprint();
                if (kind == CLIENT_ASSET) {
                    if (!isClientRelative(name) || !fingerprint.matches("[0-9a-f]{64}")) {
                        throw new IOException("Invalid recovery asset identity.");
                    }
                    continue;
                }
                if (kind != COPIED_ASSET
                        || name.contains("/")
                        || name.contains("\\")
                        || !fingerprint.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid recovery asset identity.");
                }
                var file = assetDirectory
                        .resolve(Integer.toString(index))
                        .resolve(name)
                        .toAbsolutePath()
                        .normalize();
                if (!file.startsWith(assetDirectory.toAbsolutePath().normalize()) || !Files.isRegularFile(file)) {
                    throw new IOException("Missing recovery asset.");
                }
                total = Math.addExact(total, Files.size(file));
                if (total > limit || !hash(Files.readAllBytes(file)).equals(fingerprint)) {
                    throw new IOException("Recovery asset checksum or size is invalid.");
                }
            }
        }
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static Path clientPackage(Path clientRoot, String relative, String fingerprint) throws IOException {
        if (!isClientRelative(relative)) {
            throw new IOException("Invalid recovery client package path.");
        }
        var root = clientRoot.toAbsolutePath().normalize();
        var file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("Recovery client package is outside the selected client.");
        }
        var name = file.getFileName().toString();
        if (!Files.isRegularFile(file)) {
            throw new IOException(
                    "The client package " + name + " was not found. The draft needs the client it was written with.");
        }
        if (!hash(Files.readAllBytes(file)).equals(fingerprint)) {
            throw new IOException("The client package " + name + " changed since the draft was written.");
        }
        return file;
    }

    private static boolean isClientRelative(String relative) {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains("\\") || relative.contains(":")) {
            return false;
        }
        for (var part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static void validateNativeEnvelope(byte[] bytes) throws IOException {
        try {
            PackageEditDocument.validateRecoveryEnvelope(bytes);
        } catch (UnsupportedRecoveryFormat future) {
            throw new UnsupportedFormat(
                    "This native actor recovery uses an unsupported version. The draft has been preserved.");
        }
    }

    private static TerrainRecoveryCodec.State readTerrain(DataInputStream input, int version) throws IOException {
        if (version == 1) {
            return null;
        }
        var length = count(input, Math.min(TerrainRecoveryCodec.MAX_DRAFT_BYTES, input.available()));
        return length == 0 ? null : TerrainRecoveryCodec.decode(input.readNBytes(length));
    }

    private static void writeCommands(
            DataOutputStream out, List<Command> commands, Map<String, Document> documents, Map<Asset, Integer> assets)
            throws IOException {
        out.writeInt(commands.size());
        for (var command : commands) {
            if (command instanceof TransformCommand transform) {
                out.writeByte(0);
                writeStates(out, transform.before());
                writeStates(out, transform.after());
            } else if (command instanceof DocumentCommand document) {
                out.writeByte(1);
                writeCheckpoint(out, document.before(), documents, assets);
                writeCheckpoint(out, document.after(), documents, assets);
                writeStrings(out, document.affected());
            }
        }
    }

    private static List<Command> readCommands(
            DataInputStream input, Map<String, List<Snapshot>> documents, List<Asset> assets) throws IOException {
        var commands = new ArrayList<Command>();
        var size = count(input, 256);
        for (var index = 0; index < size; index++) {
            var type = input.readUnsignedByte();
            if (type == 0) {
                commands.add(new TransformCommand(readStates(input), readStates(input)));
            } else if (type == 1) {
                commands.add(new DocumentCommand(
                        readCheckpoint(input, documents, assets),
                        readCheckpoint(input, documents, assets),
                        readStrings(input)));
            } else {
                throw new IOException("Unknown recovery command.");
            }
        }
        return List.copyOf(commands);
    }

    private static void writeCheckpoint(
            DataOutputStream out, Checkpoint checkpoint, Map<String, Document> documents, Map<Asset, Integer> assets)
            throws IOException {
        out.writeInt(checkpoint.documents().size());
        for (var entry : checkpoint.documents().entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeInt(documents.get(entry.getKey()).slots().get(entry.getValue()));
        }
        writeStates(out, checkpoint.originals());
        writeStrings(out, checkpoint.selection());
        assets(out, checkpoint.assets(), assets);
    }

    private static Checkpoint readCheckpoint(
            DataInputStream input, Map<String, List<Snapshot>> documents, List<Asset> assets) throws IOException {
        var snapshots = new LinkedHashMap<String, Snapshot>();
        var size = count(input, 4096);
        for (var index = 0; index < size; index++) {
            var name = input.readUTF();
            var slot = input.readInt();
            var available = documents.get(name);
            if (available == null
                    || slot < 0
                    || slot >= available.size()
                    || snapshots.put(name, available.get(slot)) != null) {
                throw new IOException("Invalid native recovery snapshot.");
            }
        }
        return new Checkpoint(Map.copyOf(snapshots), readStates(input), readStrings(input), assets(input, assets));
    }

    private static void writeStates(DataOutputStream out, Map<String, State> states) throws IOException {
        out.writeInt(states.size());
        for (var entry : states.entrySet()) {
            out.writeUTF(entry.getKey());
            var state = entry.getValue();
            var transform = state.transform();
            writeVector(out, transform.location());
            out.writeInt(transform.rotation().pitch());
            out.writeInt(transform.rotation().yaw());
            out.writeInt(transform.rotation().roll());
            out.writeFloat(transform.drawScale());
            writeVector(out, transform.drawScale3d());
            writeVector(out, transform.prePivot());
            out.writeInt(state.skins().size());
            for (var skin : state.skins()) {
                out.writeInt(skin);
            }
        }
    }

    private static Map<String, State> readStates(DataInputStream input) throws IOException {
        var result = new LinkedHashMap<String, State>();
        var size = count(input, MAX_ENTRIES);
        for (var index = 0; index < size; index++) {
            var name = input.readUTF();
            var transform = new ActorTransform(
                    readVector(input),
                    new Rotator(input.readInt(), input.readInt(), input.readInt()),
                    input.readFloat(),
                    readVector(input),
                    readVector(input));
            var skins = new ArrayList<Integer>();
            var skinCount = count(input, 65536);
            for (var slot = 0; slot < skinCount; slot++) {
                skins.add(input.readInt());
            }
            if (result.put(name, new State(transform, List.copyOf(skins))) != null) {
                throw new IOException("Duplicate recovery actor state.");
            }
        }
        return Map.copyOf(result);
    }

    private static void writeVector(DataOutputStream out, Vector3 value) throws IOException {
        out.writeFloat(value.x());
        out.writeFloat(value.y());
        out.writeFloat(value.z());
    }

    private static Vector3 readVector(DataInputStream input) throws IOException {
        return new Vector3(input.readFloat(), input.readFloat(), input.readFloat());
    }

    private static void writeStrings(DataOutputStream out, Set<String> strings) throws IOException {
        out.writeInt(strings.size());
        for (var value : strings) {
            out.writeUTF(value);
        }
    }

    private static Set<String> readStrings(DataInputStream input) throws IOException {
        var result = new LinkedHashSet<String>();
        var size = count(input, MAX_ENTRIES);
        for (var index = 0; index < size; index++) {
            if (!result.add(input.readUTF())) {
                throw new IOException("Duplicate recovery identity.");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static void writeGroups(DataOutputStream out, Map<String, List<String>> groups) throws IOException {
        out.writeInt(groups.size());
        for (var entry : groups.entrySet()) {
            out.writeUTF(entry.getKey());
            writeStrings(out, new LinkedHashSet<>(entry.getValue()));
        }
    }

    private static Map<String, List<String>> readGroups(DataInputStream input) throws IOException {
        var result = new LinkedHashMap<String, List<String>>();
        var size = count(input, MAX_ENTRIES);
        for (var index = 0; index < size; index++) {
            var name = input.readUTF();
            if (name.isBlank() || result.put(name, List.copyOf(readStrings(input))) != null) {
                throw new IOException("Invalid recovery group.");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void assets(DataOutputStream out, Map<String, Asset> assets, Map<Asset, Integer> slots)
            throws IOException {
        out.writeInt(assets.size());
        for (var entry : assets.entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeInt(slots.get(entry.getValue()));
        }
    }

    private static Map<String, Asset> assets(DataInputStream input, List<Asset> files) throws IOException {
        var result = new LinkedHashMap<String, Asset>();
        var size = count(input, MAX_ENTRIES);
        for (var index = 0; index < size; index++) {
            var name = input.readUTF();
            var slot = input.readInt();
            if (slot < 0 || slot >= files.size() || result.put(name, files.get(slot)) != null) {
                throw new IOException("Invalid recovery asset reference.");
            }
        }
        return Map.copyOf(result);
    }

    private static int count(DataInputStream input, int limit) throws IOException {
        var value = input.readInt();
        if (value < 0 || value > limit) {
            throw new IOException("Recovery count exceeds its limit.");
        }
        return value;
    }
}

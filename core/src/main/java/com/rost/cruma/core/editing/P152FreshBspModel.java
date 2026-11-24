package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152ActorDomain.Identity;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class P152FreshBspModel {
    private final Identity identity;
    private final Path mapPath;
    private final NativeBspTrace trace;
    private final NativeBspRegions regions;
    private final P152BspReceiverModel receivers;

    private P152FreshBspModel(
            Identity identity,
            Path mapPath,
            NativeBspTrace trace,
            NativeBspRegions regions,
            P152BspReceiverModel receivers) {
        this.identity = identity;
        this.mapPath = mapPath;
        this.trace = trace;
        this.regions = regions;
        this.receivers = receivers;
    }

    static P152FreshBspModel capture(P152FreshBakeContext context) throws IOException {
        return capture(context, () -> false);
    }

    static P152FreshBspModel capture(P152FreshBakeContext context, BooleanSupplier cancelled) throws IOException {
        context.verifySourcesUnchanged(cancelled);
        var source = new PackageBytes(context.getMapPath(), context.getMapBytes());
        P152FreshHashQuery.checkCancelled(cancelled);
        var reference = context.getModelReference();
        if (reference <= 0 || reference > source.exports.size()) {
            throw new IOException("Fresh Level requires a local native world Model");
        }
        var receivers = P152BspReceiverModel.decode(source, reference);
        P152FreshHashQuery.checkCancelled(cancelled);
        var loader = new UArchiveLoader(context.getClientRoot(), List.of()) {
            @Override
            public UArchive loadArchive(String name) {
                throw new IllegalStateException("Frozen world Model cannot load external packages");
            }

            @Override
            public UArchive loadArchiveIfPresent(String name) {
                throw new IllegalStateException("Frozen world Model cannot load external packages");
            }
        };
        try (var archive =
                new UArchive(source.packageName, ByteBuffer.wrap(source.body).order(ByteOrder.LITTLE_ENDIAN), loader)) {
            var object = archive.getObjectLoader().loadObject(reference);
            P152FreshHashQuery.checkCancelled(cancelled);
            if (!(object instanceof UModel model)) {
                throw new IOException("Fresh Level model reference does not decode as native UModel");
            }
            var trace = NativeBspTrace.snapshot(model, null);
            P152FreshHashQuery.checkCancelled(cancelled);
            var regions = NativeBspRegions.snapshot(model);
            P152FreshHashQuery.checkCancelled(cancelled);
            var identity = new Identity(context.getMapSha256(), reference, source.getObjectPath(reference));
            var result = new P152FreshBspModel(identity, context.getMapPath(), trace, regions, receivers);
            context.verifySourcesUnchanged(cancelled);
            return result;
        } catch (CancellationException cancelledCapture) {
            throw cancelledCapture;
        } catch (IllegalArgumentException | IllegalStateException malformed) {
            throw new IOException("Frozen native world Model is not qualified", malformed);
        }
    }

    String getMapSha256() {
        return identity.packageSha256();
    }

    Identity getIdentity() {
        return identity;
    }

    Path getMapPath() {
        return mapPath;
    }

    NativeBspTrace getTrace() {
        return trace;
    }

    NativeBspRegions getRegions() {
        return regions;
    }

    List<Integer> getZoneActorReferences() {
        return receivers.getZoneActorReferences();
    }

    P152BspReceiverModel getReceiverModel() {
        return receivers;
    }
}

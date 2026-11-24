package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UMeshAnimation.CameraMark;
import com.rost.cruma.core.unreal.UMeshAnimation.CameraMark.Kind;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.eclipse.collections.api.list.primitive.ImmutableFloatList;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;

final class UMeshAnimSequences {
    private static final String ATTACK_SHOT = "AnimNotify_AttackShot";
    private static final String ATTACK_PRE_SHOT = "AnimNotify_AttackPreShot";
    private static final String CAMERA_LOCATION = "AnimNotify_CameraLocation";
    private static final String CHANNELING = "AnimNotify_Channeling";
    private static final int AROUND_BONE = 1;

    private UMeshAnimSequences() {}

    interface Checks {
        void remaining(String label, int bytes);

        void count(String label, int count);
    }

    record Sequence(
            String name,
            int startFrame,
            int frameCount,
            float rate,
            float shotTime,
            List<CameraMark> cameraMarks,
            float preShotTime,
            ImmutableFloatList shotTimes,
            ImmutableFloatList preShotTimes,
            ImmutableFloatList channelTimes) {}

    static Sequence read(UArchive archive, Checks checks) {
        var fileVersion = archive.getHeader().getFileVersion();
        if (fileVersion >= 115) {
            checks.remaining("animation bookmark", 4);
            archive.readFloat();
        }
        var name = archive.readName();
        var groupCount = archive.readIndex();
        checks.count("animation groups", groupCount);
        for (var group = 0; group < groupCount; group++) {
            archive.readName();
        }
        checks.remaining("animation frame range", 8);
        var startFrame = archive.readInt();
        var sequenceFrames = archive.readInt();
        var notifyCount = archive.readIndex();
        checks.count("animation notifies", notifyCount);
        var shotTime = -1f;
        var preShotTime = -1f;
        var shotTimes = new FloatArrayList();
        var preShotTimes = new FloatArrayList();
        var channelTimes = new FloatArrayList();
        var cameraMarks = new ArrayList<CameraMark>();
        for (var notify = 0; notify < notifyCount; notify++) {
            checks.remaining("animation notify", 4);
            var time = archive.readFloat();
            archive.readName();
            if (fileVersion >= 112) {
                var object = archive.readIndex();
                var notifyClass = className(archive, object);
                if (ATTACK_SHOT.equalsIgnoreCase(notifyClass)) {
                    shotTime = time;
                    shotTimes.add(time);
                } else if (ATTACK_PRE_SHOT.equalsIgnoreCase(notifyClass)) {
                    preShotTime = preShotTime < 0f ? time : Math.min(preShotTime, time);
                    preShotTimes.add(time);
                } else if (CHANNELING.equalsIgnoreCase(notifyClass)) {
                    channelTimes.add(time);
                } else if (CAMERA_LOCATION.equalsIgnoreCase(notifyClass) && object > 0) {
                    cameraMarks.add(cameraMark(archive, object, time));
                }
            }
            if (fileVersion > 130) {
                checks.remaining("animation notify text", 4);
                var characters = archive.readInt();
                if (characters > 0) {
                    checks.remaining("animation notify text", Math.multiplyExact(characters, 2));
                    archive.skipBytes(characters * 2);
                }
            }
        }
        checks.remaining("animation rate", 4);
        var rate = archive.readFloat();
        skipLicenseeFields(archive, checks);
        return new Sequence(
                name,
                startFrame,
                sequenceFrames,
                rate,
                shotTime,
                cameraMarks,
                preShotTime,
                shotTimes.sortThis().toImmutable(),
                preShotTimes.sortThis().toImmutable(),
                channelTimes.sortThis().toImmutable());
    }

    private static CameraMark cameraMark(UArchive archive, int object, float time) {
        var kind = 0;
        var bone = 0;
        var pawnSequence = "";
        var pawnScale = 1f;
        var pawnOffset = new Vec3();
        var ownCamera = false;
        var at = archive.getOffset();
        var end = archive.getObjectEnd();
        try {
            for (var property : archive.getObjectLoader()
                    .exportProperties(archive.getExportMap().get(object - 1))) {
                switch (property.getName().toLowerCase(Locale.ROOT)) {
                    case "ecameraloctype" -> kind = property.getUInt8Value();
                    case "bcalcwithbone" -> kind = property.isTrue() ? AROUND_BONE : kind;
                    case "boneindex" -> bone = property.getInt32Value();
                    case "camerapawnaniname" -> pawnSequence = property.getStringValue();
                    case "camerapawnaniscale" -> pawnScale = property.getFloatValue();
                    case "camerapawnspawnoffset" -> pawnOffset = property.getVectorValue();
                    case "bselftargetonly", "bplayeranimationonly" -> ownCamera |= property.isTrue();
                    default -> {
                    }
                }
            }
        } catch (RuntimeException unreadable) {
            LogManager.getLogger(UMeshAnimSequences.class)
                    .debug("A camera mark cannot be read: {}", unreadable.toString());
        } finally {
            archive.setObjectEnd(end);
            archive.setPosition(at);
        }
        return new CameraMark(time, Kind.of(kind), bone, pawnSequence, pawnScale, pawnOffset, ownCamera);
    }

    private static String className(UArchive archive, int index) {
        if (index > 0 && index <= archive.getExportMap().size()) {
            return archive.getExportMap().get(index - 1).getClassName();
        }
        if (index < 0 && -index <= archive.getImportMap().size()) {
            return archive.getImportMap().get(-index - 1).getClassName();
        }
        return "";
    }

    private static void skipLicenseeFields(UArchive archive, Checks checks) {
        var licensee = archive.getHeader().getLicenseVersion();
        if (licensee <= 0) {
            return;
        }
        if (licensee == 1) {
            checks.remaining("animation sequence fields", 8);
            archive.readInt();
            archive.readInt();
            archive.readIndex();
            return;
        }
        checks.remaining("animation sequence fields", 12);
        archive.readInt();
        archive.readInt();
        archive.readInt();
        archive.readIndex();
        if (licensee >= 20) {
            checks.remaining("animation sequence fields", 4);
            archive.readInt();
        }
        if (licensee >= 25) {
            checks.remaining("animation sequence fields", 4);
            archive.readInt();
        }
        if (licensee == 26) {
            skipArray(archive, checks, "animation sequence pairs", 8);
        } else if (licensee > 26) {
            checks.remaining("animation sequence flag", 1);
            archive.readByte();
            skipArray(archive, checks, "animation sequence pairs", 8);
            var groupCount = archive.readIndex();
            checks.count("animation sequence pair groups", groupCount);
            for (var group = 0; group < groupCount; group++) {
                checks.remaining("animation sequence pair group", 4);
                archive.readInt();
                skipArray(archive, checks, "animation sequence pair group pairs", 8);
            }
            checks.remaining("animation sequence fields", 8);
            archive.readInt();
            archive.readInt();
            skipArray(archive, checks, "animation sequence trailing pairs", 8);
        }
    }

    private static void skipArray(UArchive archive, Checks checks, String label, int elementSize) {
        var count = archive.readIndex();
        checks.count(label, count);
        checks.remaining(label, Math.multiplyExact(count, elementSize));
        archive.skipBytes(count * elementSize);
    }
}

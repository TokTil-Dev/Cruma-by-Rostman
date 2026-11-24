package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UObject.ObjectFlags;
import com.rost.cruma.core.unreal.UObject.StateFrame;
import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class SkillEffects {
    public static final String VISUAL_PACKAGE = "skill";

    public static final int AM_NONE = 0;

    public static final int AM_LOCATION = 1;
    public static final int AM_RH = 2;
    public static final int AM_LH = 3;
    public static final int AM_RA = 4;
    public static final int AM_LA = 5;
    public static final int AM_WING = 6;
    public static final int AM_BONE_SPECIFIED = 7;
    public static final int AM_ALIAS_SPECIFIED = 8;
    public static final int AM_TRAIL = 9;
    public static final int AM_AGATHION = 10;

    public static final int AM_RIGHT_FOOT = 101;

    public static final int AM_LEFT_FOOT = 102;

    private static final int[] VISUAL_ATTACH = {
        AM_NONE, AM_RH, AM_LH, AM_BONE_SPECIFIED, AM_ALIAS_SPECIFIED, AM_TRAIL, AM_RIGHT_FOOT, AM_LEFT_FOOT
    };

    private static final Pattern VISUAL_ID = Pattern.compile("(\\d+)(_\\w+)?");

    public record Action(
            String effect,
            int attachOn,
            String bone,
            boolean onTarget,
            boolean onMultiTarget,
            boolean relativeToCylinder,
            Vec3 offset,
            float scale,
            float delay,
            boolean absolute,
            URotator turn,
            Curve curve,
            boolean adjustLife,
            float forceLife,
            boolean channeling,
            boolean chaining) {
        public static final URotator NO_TURN = new URotator(0, 0, 0);

        public Action(
                String effect,
                int attachOn,
                String bone,
                boolean onTarget,
                boolean onMultiTarget,
                boolean relativeToCylinder,
                Vec3 offset,
                float scale,
                float delay,
                boolean absolute,
                URotator turn,
                Curve curve,
                boolean adjustLife,
                float forceLife) {
            this(
                    effect,
                    attachOn,
                    bone,
                    onTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scale,
                    delay,
                    absolute,
                    turn,
                    curve,
                    adjustLife,
                    forceLife,
                    false,
                    false);
        }

        public Action(
                String effect,
                int attachOn,
                String bone,
                boolean onTarget,
                boolean onMultiTarget,
                boolean relativeToCylinder,
                Vec3 offset,
                float scale,
                float delay,
                boolean absolute,
                URotator turn,
                Curve curve,
                boolean adjustLife) {
            this(
                    effect,
                    attachOn,
                    bone,
                    onTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scale,
                    delay,
                    absolute,
                    turn,
                    curve,
                    adjustLife,
                    0f);
        }

        public Action(
                String effect,
                int attachOn,
                String bone,
                boolean onTarget,
                boolean onMultiTarget,
                boolean relativeToCylinder,
                Vec3 offset,
                float scale,
                float delay,
                boolean absolute,
                URotator turn,
                Curve curve) {
            this(
                    effect,
                    attachOn,
                    bone,
                    onTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scale,
                    delay,
                    absolute,
                    turn,
                    curve,
                    true);
        }

        public Action(
                String effect,
                int attachOn,
                String bone,
                boolean onTarget,
                boolean onMultiTarget,
                boolean relativeToCylinder,
                Vec3 offset,
                float scale,
                float delay,
                boolean absolute,
                URotator turn) {
            this(
                    effect,
                    attachOn,
                    bone,
                    onTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scale,
                    delay,
                    absolute,
                    turn,
                    Curve.NONE);
        }

        public Action(
                String effect,
                int attachOn,
                String bone,
                boolean onTarget,
                boolean onMultiTarget,
                boolean relativeToCylinder,
                Vec3 offset,
                float scale,
                float delay) {
            this(
                    effect,
                    attachOn,
                    bone,
                    onTarget,
                    onMultiTarget,
                    relativeToCylinder,
                    offset,
                    scale,
                    delay,
                    false,
                    NO_TURN);
        }
    }

    public record Curve(boolean hermite, boolean bezier, List<Vec3> controlPoints) {
        public static final Curve NONE = new Curve(false, false, List.of());

        public Curve {
            controlPoints = List.copyOf(controlPoints);
        }
    }

    public record Skill(int skillId, String className, float flyingTime, Map<Phase, List<Action>> phases) {
        public Skill {
            var copy = new EnumMap<Phase, List<Action>>(Phase.class);
            phases.forEach((phase, actions) -> copy.put(phase, List.copyOf(actions)));
            phases = copy;
        }

        public List<Action> actions(Phase phase) {
            return phases.getOrDefault(phase, List.of());
        }

        public int count() {
            return phases.values().stream().mapToInt(List::size).sum();
        }

        public boolean isVisual() {
            return className.indexOf('.') >= 0;
        }

        public String getFullName() {
            return isVisual() ? className : "LineageSkillEffect." + className;
        }
    }

    public record Layout(boolean visual, Set<Phase> phases, List<Integer> places, boolean scale, boolean delay) {
        public static final Layout CLASSES = new Layout(
                false,
                EnumSet.allOf(Phase.class),
                List.of(
                        AM_NONE,
                        AM_LOCATION,
                        AM_RH,
                        AM_LH,
                        AM_RA,
                        AM_LA,
                        AM_WING,
                        AM_BONE_SPECIFIED,
                        AM_ALIAS_SPECIFIED,
                        AM_TRAIL,
                        AM_AGATHION),
                true,
                true);

        public Layout {
            phases = phases.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(phases));
            places = List.copyOf(places);
        }

        public static Layout visual(@Nullable UArchive engine) {
            var phases = EnumSet.noneOf(Phase.class);
            var delay = false;
            var places = 0;
            if (engine != null) {
                var exports = engine.getExportMap();
                for (var index = 1; index <= exports.size(); index++) {
                    var export = exports.get(index - 1);
                    var outer = export.getPackageIndex();
                    var owner = outer > 0 ? exports.get(outer - 1).getObjectName() : "";
                    if ("SkillVisualEffect".equals(owner)) {
                        for (var phase : Phase.values()) {
                            if (phase.getVisualProperty().equals(export.getObjectName())) {
                                phases.add(phase);
                            }
                        }
                    }
                    delay |= "SkillAction_LocateEffect".equals(owner) && "SpawnDelay".equals(export.getObjectName());
                    if ("Enum".equals(export.getClassName()) && "EAttachMethod".equals(export.getObjectName())) {
                        places = Math.max(places, enumCount(engine, index));
                    }
                }
            }
            if (phases.isEmpty()) {
                phases = EnumSet.of(Phase.CASTING, Phase.PRESHOT, Phase.SHOT, Phase.EXPLOSION);
            }
            var values = new ArrayList<Integer>();
            for (var value = 0; value < (places > 0 ? Math.min(places, VISUAL_ATTACH.length) : 6); value++) {
                values.add(VISUAL_ATTACH[value]);
            }
            return new Layout(true, phases, values, false, delay);
        }
    }

    private SkillEffects() {}

    public enum Phase {
        CASTING("CastingAction"),
        CHANNELING("ChannelingAction"),
        PRESHOT("PreshotAction"),
        SHOT("ShotAction"),
        EXPLOSION("ExplosionAction"),
        UNION_TARGET("UnionTargetAction");

        private final String property;

        Phase(String property) {
            this.property = property;
        }

        public String getProperty() {
            return property;
        }

        public String getVisualProperty() {
            return property + "s";
        }
    }

    public static List<Skill> list(UArchive archive) {
        var exports = archive.getExportMap();
        var skills = new ArrayList<Skill>();
        for (var index = 1; index <= exports.size(); index++) {
            var export = exports.get(index - 1);
            if (!"None".equals(export.getClassName()) || !"L2SkillEffect".equals(export.getSuperName())) {
                continue;
            }
            var skill = skill(archive, index);
            if (skill != null) {
                skills.add(skill);
            }
        }
        skills.sort(Comparator.comparingInt(Skill::skillId).thenComparing(Skill::className));
        return List.copyOf(skills);
    }

    public static List<Skill> listVisuals(UArchive archive) {
        var exports = archive.getExportMap();
        var skills = new ArrayList<Skill>();
        for (var index = 1; index <= exports.size(); index++) {
            var export = exports.get(index - 1);
            if (!"SkillVisualEffect".equals(export.getClassName())) {
                continue;
            }
            var skill = visual(archive, index);
            if (skill != null) {
                skills.add(skill);
            }
        }
        skills.sort(Comparator.comparingInt(Skill::skillId).thenComparing(Skill::className));
        return List.copyOf(skills);
    }

    public static int visualAttach(int attachOn, int count) {
        for (var value = 0; value < Math.min(count, VISUAL_ATTACH.length); value++) {
            if (VISUAL_ATTACH[value] == attachOn) {
                return value;
            }
        }
        return -1;
    }

    public static String objectPath(UArchive archive, int index) {
        var export = archive.getExportMap().get(index - 1);
        var outer = export.getPackageIndex();
        return (outer > 0 ? objectPath(archive, outer) : archive.getName()) + "." + export.getObjectName();
    }

    public static String key(String effectPath) {
        return effectPath.toLowerCase(Locale.ROOT);
    }

    private static @Nullable Skill skill(UArchive archive, int index) {
        var defaults = EffectClasses.defaults(archive, index);
        if (defaults == null) {
            return null;
        }
        var name = archive.getExportMap().get(index - 1).getObjectName();
        var skillId = idOf(name);
        var flyingTime = 0f;
        var phases = new EnumMap<Phase, List<Action>>(Phase.class);
        for (var property : defaults) {
            switch (property.getName()) {
                case "SkillID" -> {
                    if (skillId < 0) {
                        skillId = property.getInt32Value();
                    }
                }
                case "FlyingTime" -> flyingTime = property.getFloatValue();
                default -> {
                    for (var phase : Phase.values()) {
                        if (phase.property.equals(property.getName()) && property.getObjectArray() != null) {
                            var actions = new ArrayList<Action>();
                            for (var ref : property.getObjectArray()) {
                                var action = action(archive, ref);
                                if (action != null) {
                                    actions.add(action);
                                }
                            }
                            phases.put(phase, actions);
                        }
                    }
                }
            }
        }
        return skillId < 0 ? null : new Skill(skillId, name, flyingTime, phases);
    }

    private static @Nullable Skill visual(UArchive archive, int index) {
        var export = archive.getExportMap().get(index - 1);
        var id = VISUAL_ID.matcher(export.getObjectName());
        if (!id.matches()) {
            return null;
        }
        var properties = properties(archive, index);
        if (properties == null) {
            return null;
        }
        var flyingTime = 0f;
        var phases = new EnumMap<Phase, List<Action>>(Phase.class);
        for (var property : properties) {
            if ("FlyingTime".equals(property.getName())) {
                flyingTime = property.getFloatValue();
            }
            for (var phase : Phase.values()) {
                if (phase.getVisualProperty().equals(property.getName())) {
                    var actions = new ArrayList<Action>();
                    for (var element : property.getSubProperties()) {
                        var reference = element.get("Action");
                        var action = reference == null ? null : visualAction(archive, reference.getIndexValue());
                        if (action != null) {
                            actions.add(action);
                        }
                    }
                    phases.put(phase, actions);
                }
            }
        }
        return new Skill(Integer.parseInt(id.group(1)), objectPath(archive, index), flyingTime, phases);
    }

    private static @Nullable Action visualAction(UArchive archive, int ref) {
        if (ref <= 0 || ref > archive.getExportMap().size()) {
            return null;
        }
        var properties = properties(archive, ref);
        if (properties == null) {
            return null;
        }
        String effect = null;
        var attachOn = AM_NONE;
        var bone = "";
        var onTarget = false;
        var onMultiTarget = false;
        var relativeToCylinder = true;
        var offset = new Vec3();
        var delay = 0f;
        var absolute = false;
        for (var property : properties) {
            switch (property.getName()) {
                case "EffectClass" -> effect = path(archive, property.getIndexValue());
                case "AttachOn" -> {
                    var value = property.getUInt8Value() & 0xff;
                    attachOn = value < VISUAL_ATTACH.length ? VISUAL_ATTACH[value] : AM_NONE;
                }
                case "AttachBoneName" -> bone = archive.resolveName(property.getIndexValue());
                case "bSpawnOnTarget" -> onTarget = property.isTrue();
                case "bOnMultiTarget" -> onMultiTarget = property.isTrue();
                case "bRelativeToCylinder" -> relativeToCylinder = property.isTrue();
                case "offset" -> offset = property.getVectorValue() == null ? offset : property.getVectorValue();
                case "SpawnDelay" -> delay = property.getFloatValue();
                case "bAbsolute" -> absolute = property.isTrue();
                default -> {
                }
            }
        }
        return effect == null
                ? null
                : new Action(
                        effect,
                        attachOn,
                        bone,
                        onTarget,
                        onMultiTarget,
                        relativeToCylinder,
                        offset,
                        0f,
                        delay,
                        absolute,
                        Action.NO_TURN);
    }

    private static @Nullable List<UProperty> properties(UArchive archive, int index) {
        var export = archive.getExportMap().get(index - 1);
        var saved = archive.getObjectEnd();
        try {
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            if ((export.getObjectFlags() & ObjectFlags.RF_HasStack.getId()) != 0) {
                StateFrame.deserialize(archive);
            }
            return archive.getPropertyExtractor().extractProperties();
        } catch (RuntimeException failure) {
            return null;
        } finally {
            archive.setObjectEnd(saved);
        }
    }

    private static int enumCount(UArchive archive, int index) {
        var export = archive.getExportMap().get(index - 1);
        var saved = archive.getObjectEnd();
        try {
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            archive.getPropertyExtractor().extractProperties();
            archive.readIndex();
            archive.readIndex();
            var count = archive.readIndex();
            return count >= 0 && count < 256 ? count : 0;
        } catch (RuntimeException failure) {
            return 0;
        } finally {
            archive.setObjectEnd(saved);
        }
    }

    private static int idOf(String className) {
        var matcher = Pattern.compile("(?i)l2_(\\d+)_skill").matcher(className);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static @Nullable Action action(UArchive archive, int ref) {
        if (ref <= 0 || ref > archive.getExportMap().size()) {
            return null;
        }
        var properties = properties(archive, ref);
        if (properties == null) {
            return null;
        }
        String effect = null;
        var attachOn = AM_NONE;
        var bone = "";
        var onTarget = true;
        var onMultiTarget = false;
        var relativeToCylinder = true;
        var offset = new Vec3();
        var scale = 0f;
        var delay = 0f;
        var absolute = false;
        var turn = Action.NO_TURN;
        var hermite = false;
        var bezier = false;
        List<Vec3> controlPoints = List.of();
        var adjustLife = true;
        var forceLife = 0f;
        var channeling = false;
        var chaining = false;
        for (var property : properties) {
            switch (property.getName()) {
                case "EffectClass" -> effect = path(archive, property.getIndexValue());
                case "bAdjustLifeTime" -> adjustLife = property.isTrue();
                case "bChanneling" -> channeling = property.isTrue();
                case "bChaining" -> chaining = property.isTrue();
                case "BR_ForceLifeTime" -> forceLife = property.getFloatValue();
                case "bHermiteInterpolation" -> hermite = property.isTrue();
                case "bBezierCurve" -> bezier = property.isTrue();
                case "ControlPointOffset" -> controlPoints = vectors(property);
                case "AttachOn" -> attachOn = property.getUInt8Value() & 0xff;
                case "AttachBoneName" -> bone = archive.resolveName(property.getIndexValue());
                case "bSpawnOnTarget" -> onTarget = property.isTrue();
                case "bOnMultiTarget" -> onMultiTarget = property.isTrue();
                case "bRelativeToCylinder" -> relativeToCylinder = property.isTrue();
                case "offset" -> offset = property.getVectorValue() == null ? offset : property.getVectorValue();
                case "ScaleSize" -> scale = property.getFloatValue();
                case "SpawnDelay" -> delay = property.getFloatValue();
                case "bAbsolute" -> absolute = property.isTrue();
                case "RelativeRotation" ->
                    turn = property.getRotatorValue() == null ? turn : property.getRotatorValue();
                default -> {
                }
            }
        }
        return effect == null
                ? null
                : new Action(
                        effect,
                        attachOn,
                        bone,
                        onTarget,
                        onMultiTarget,
                        relativeToCylinder,
                        offset,
                        scale,
                        delay,
                        absolute,
                        turn,
                        hermite || bezier ? new Curve(hermite, bezier, controlPoints) : Curve.NONE,
                        adjustLife,
                        forceLife,
                        channeling,
                        chaining);
    }

    private static List<Vec3> vectors(UProperty property) {
        var raw = property.getDataValue();
        if (raw == null) {
            return List.of();
        }
        var vectors = new ArrayList<Vec3>();
        for (var at = 0; at + 12 <= raw.length; at += 12) {
            vectors.add(new Vec3(floatAt(raw, at), floatAt(raw, at + 4), floatAt(raw, at + 8)));
        }
        return vectors;
    }

    private static float floatAt(short[] raw, int at) {
        return Float.intBitsToFloat(
                (raw[at] & 0xff) | (raw[at + 1] & 0xff) << 8 | (raw[at + 2] & 0xff) << 16 | (raw[at + 3] & 0xff) << 24);
    }

    private static @Nullable String path(UArchive archive, int index) {
        if (index < 0) {
            var imported = archive.getImportMap().get(-index - 1);
            var outer = imported.getPackageIndex();
            var name = imported.getObjectName();
            return outer < 0 ? archive.getImportMap().get(-outer - 1).getObjectName() + "." + name : name;
        }
        if (index > 0) {
            return archive.getName() + "."
                    + archive.getExportMap().get(index - 1).getObjectName();
        }
        return null;
    }
}

package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.littleEndian;
import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.PackageBytes.writeIndex;

import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.Nullable;

final class EditableLight {
    static final Map<String, String> CLASSES = Map.of("Light", "Engine.Light", "Spotlight", "Gameplay.Spotlight");
    static final String CARRIED_LIT = "It lights the map, so only the Lights tool moves it";
    static final String NO_TYPE = "It has no light type, so it lights nothing";

    private static final List<String> TAGS = List.of(
            "LightBrightness",
            "LightHue",
            "LightSaturation",
            "LightRadius",
            "Location",
            "Region",
            "Rotation",
            "LightCone");

    final String path;
    final String className;
    final ExportEntry entry;
    final LightColor color;
    final Vector3 location;
    final float radius;
    final int lightType;
    final int brightnessType;
    final int radiusType;
    final boolean placed;
    final boolean relocatable;
    final String restriction;
    final LightShape shape;
    final boolean specialLit;
    final boolean dynamic;
    final String turnRestriction;
    final String coneRestriction;

    final @Nullable LightRegion region;

    private final List<Tag> tags;
    private final int terminator;
    private final int zoneAt;
    private final int zoneEnd;
    private final int leafAt;
    private final int numberAt;

    private record Tag(
            String name, int type, boolean truth, int start, int info, int sizeStart, int payload, int end) {}

    private EditableLight(
            String path,
            String className,
            ExportEntry entry,
            LightColor color,
            Vector3 location,
            float radius,
            int lightType,
            int brightnessType,
            int radiusType,
            boolean placed,
            boolean relocatable,
            String restriction,
            LightShape shape,
            boolean specialLit,
            boolean dynamic,
            String turnRestriction,
            String coneRestriction,
            @Nullable LightRegion region,
            int[] regionValues,
            List<Tag> tags,
            int terminator) {
        this.path = path;
        this.className = className;
        this.entry = entry;
        this.color = color;
        this.location = location;
        this.radius = radius;
        this.lightType = lightType;
        this.brightnessType = brightnessType;
        this.radiusType = radiusType;
        this.placed = placed;
        this.relocatable = relocatable;
        this.restriction = restriction;
        this.shape = shape;
        this.specialLit = specialLit;
        this.dynamic = dynamic;
        this.turnRestriction = turnRestriction;
        this.coneRestriction = coneRestriction;
        this.region = region;
        zoneAt = regionValues[0];
        zoneEnd = regionValues[1];
        leafAt = regionValues[2];
        numberAt = regionValues[3];
        this.tags = List.copyOf(tags);
        this.terminator = terminator;
    }

    static EditableLight read(PackageBytes source, ExportEntry entry, String path, String className)
            throws IOException {
        var cursor = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        if ((entry.flags() & 0x02000000) != 0) {
            var node = cursor.readIndex();
            cursor.readIndex();
            cursor.skip(12);
            if (node != 0) {
                cursor.readIndex();
            }
        }
        var tags = new ArrayList<Tag>();
        int terminator;
        int[] regionValues = null;
        var turnRestriction = "";
        var coneRestriction = "";
        while (true) {
            var start = cursor.position;
            var name = source.getName(cursor.readIndex());
            if (name.equalsIgnoreCase("None")) {
                terminator = start;
                break;
            }
            var infoAt = cursor.position;
            var info = cursor.readU8();
            var type = info & 15;
            if (type == 0) {
                throw malformed("Invalid property type");
            }
            var struct = "";
            if (type == 10) {
                struct = source.getName(cursor.readIndex());
            }
            var sizeStart = cursor.position;
            var size = switch (info >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> cursor.readU8();
                case 6 -> cursor.readU16();
                default -> cursor.readI32();
            };
            var arrayIndex = type != 3 && (info & 128) != 0 ? cursor.readArrayIndex() : 0;
            var payload = cursor.position;
            source.skipValue(cursor, name, type, size);
            if (name.equals("Rotation") && !(type == 10 && struct.equals("Rotator") && size == 12 && arrayIndex == 0)) {
                turnRestriction = "Its direction has an unusual layout";
            } else if (name.equals("LightCone") && !(type == 1 && size == 1 && arrayIndex == 0)) {
                coneRestriction = "Its cone has an unusual layout";
            }
            if (arrayIndex == 0) {
                tags.add(new Tag(
                        name,
                        type,
                        type == 3 && (info & 128) != 0,
                        start,
                        infoAt,
                        sizeStart,
                        payload,
                        cursor.position));
                if (name.equals("Region") && type == 10 && struct.equals("PointRegion") && (info & 128) == 0) {
                    regionValues = PointRegionTag.read(source, payload, cursor.position);
                }
            }
        }
        var input = littleEndian(source.body);
        var brightness = LightColor.DEFAULT.brightness();
        var hue = LightColor.DEFAULT.hue();
        var saturation = LightColor.DEFAULT.saturation();
        var brightnessType = 0;
        var radiusType = 0;
        var placed = false;
        var location = Vector3.ZERO;
        var radius = 64f;
        var lightType = 1;
        var lightEffect = className.equals("Spotlight") ? LightShape.SPOTLIGHT : 0;
        var cone = LightShape.DEFAULT.cone();
        var rotation = LightShape.DEFAULT.rotation();
        var specialLit = false;
        var dynamic = false;
        var deleted = false;
        var timeOfDay = false;
        var restriction = "";
        for (var tag : tags) {
            var length = tag.end - tag.payload;
            switch (tag.name) {
                case "LightBrightness" -> {
                    brightnessType = tag.type;
                    if (tag.type == 4 && length == 4) {
                        brightness = input.getFloat(tag.payload);
                    } else if (tag.type == 1 && length == 1) {
                        brightness = source.body[tag.payload] & 255;
                    } else {
                        restriction = "Its brightness has an unusual layout";
                    }
                }
                case "LightHue", "LightSaturation" -> {
                    if (tag.type != 1 || length != 1) {
                        restriction = "Its colour has an unusual layout";
                    } else if (tag.name.equals("LightHue")) {
                        hue = source.body[tag.payload] & 255;
                    } else {
                        saturation = source.body[tag.payload] & 255;
                    }
                }
                case "LightRadius" -> {
                    if (tag.type == 4 && length == 4) {
                        radius = input.getFloat(tag.payload);
                        radiusType = 4;
                    } else if (tag.type == 1 && length == 1) {
                        radius = source.body[tag.payload] & 255;
                        radiusType = 1;
                    } else {
                        radiusType = -1;
                    }
                }
                case "LightType" -> {
                    if (tag.type == 1 && length == 1) {
                        lightType = source.body[tag.payload] & 255;
                    }
                }
                case "LightEffect" -> {
                    if (tag.type == 1 && length == 1) {
                        lightEffect = source.body[tag.payload] & 255;
                    }
                }
                case "LightCone" -> {
                    if (tag.type == 1 && length == 1) {
                        cone = source.body[tag.payload] & 255;
                    }
                }
                case "Rotation" -> {
                    if (tag.type == 10 && length == 12) {
                        rotation = new Rotator(
                                input.getInt(tag.payload),
                                input.getInt(tag.payload + 4),
                                input.getInt(tag.payload + 8));
                    }
                }
                case "bSpecialLit" -> specialLit = tag.truth;
                case "bDynamicLight" -> dynamic = tag.truth;
                case "Location" -> {
                    if (tag.type == 10 && length == 12) {
                        var x = input.getFloat(tag.payload);
                        var y = input.getFloat(tag.payload + 4);
                        var z = input.getFloat(tag.payload + 8);
                        if (Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)) {
                            location = new Vector3(x, y, z);
                            placed = true;
                        }
                    }
                }
                case "bDeleteMe" -> deleted = tag.truth;
                case "bSunlightColor" -> timeOfDay = tag.truth;
                default -> {}
            }
        }
        var tail = cursor.end - cursor.position;
        if (!Float.isFinite(brightness) || !Float.isFinite(radius)) {
            restriction = "Its brightness or radius is not a number";
        }
        if (restriction.isEmpty()) {
            if (deleted) {
                restriction = "This light is marked as deleted";
            } else if (timeOfDay) {
                restriction = "Its colour comes from the time of day";
            } else if (lightType == 0) {
                restriction = NO_TYPE;
            } else if (lightEffect == 19) {
                restriction = "It is a sunlight, whose colour editing is not supported yet";
            }
        }
        var color = new LightColor(Float.isFinite(brightness) ? brightness : 0, hue, saturation);
        LightRegion region = null;
        if (regionValues != null) {
            region = PointRegionTag.getRegion(source, regionValues);
        } else {
            regionValues = new int[] {-1, -1, -1, -1};
        }
        return new EditableLight(
                path,
                className,
                entry,
                color,
                location,
                radius,
                lightType,
                brightnessType,
                radiusType,
                placed,
                tail == 0,
                restriction,
                new LightShape(lightType, lightEffect, cone, rotation),
                specialLit,
                dynamic,
                turnRestriction,
                coneRestriction,
                region,
                regionValues,
                tags,
                terminator);
    }

    boolean isEditable() {
        return restriction.isEmpty();
    }

    String getMoveEvent(PackageBytes source) throws IOException {
        for (var tag : tags) {
            if (tag.name().equalsIgnoreCase("L2MoveEvent") && tag.type() == 6) {
                return source.getName(new PackageBytes.Cursor(source.body, tag.payload(), tag.end()).readIndex());
            }
        }
        return "None";
    }

    @Nullable
    Boolean getSavedBool(String name) {
        for (var tag : tags) {
            if (tag.name().equalsIgnoreCase(name) && tag.type() == 3) {
                return tag.truth();
            }
        }
        return null;
    }

    boolean isCarryOnly() {
        return restriction.equals(NO_TYPE);
    }

    String getCarryRestriction(LightPlacement place, @Nullable LightRegion newRegion, Rotator rotation) {
        if (!isCarryOnly()) {
            return restriction.isEmpty() ? CARRIED_LIT : restriction;
        }
        if (Float.compare(place.radius(), radius) != 0) {
            return "Only the place and the direction of a carried light change";
        }
        if (newRegion != null && !newRegion.equals(region)) {
            if (region == null) {
                return "Its zone is saved in an unusual way, so it cannot move to another BSP leaf";
            }
            if (!relocatable && PointRegionTag.getZoneSize(newRegion) != zoneEnd - zoneAt) {
                return "This light keeps the size of its saved data, so it cannot move to this zone";
            }
        }
        if (!place.location().equals(location) && !placed) {
            return "This light has no saved place, so it cannot move";
        }
        if (!rotation.equals(shape.rotation()) && !turnRestriction.isEmpty()) {
            return turnRestriction;
        }
        if (!relocatable) {
            var aim = new LightShape(shape.type(), shape.effect(), shape.cone(), rotation);
            for (var name : List.of("Location", "Rotation", "Region")) {
                if (changes(name, color, place, newRegion, aim) && tag(name) == null) {
                    return "This light keeps the size of its saved data, so only its saved values can change";
                }
            }
        }
        return "";
    }

    EditableLight copy(ExportEntry entry, String path) {
        return new EditableLight(
                path,
                className,
                entry,
                color,
                location,
                radius,
                lightType,
                brightnessType,
                radiusType,
                placed,
                relocatable,
                restriction,
                shape,
                specialLit,
                dynamic,
                turnRestriction,
                coneRestriction,
                region,
                new int[] {zoneAt, zoneEnd, leafAt, numberAt},
                tags,
                terminator);
    }

    LightPlacement getPlacement() {
        return new LightPlacement(location, radius);
    }

    int getTemplateRank(boolean spot) {
        if (!isEditable()
                || !relocatable
                || !placed
                || shape.isSpot() != spot
                || shape.effect() == LightShape.SUNLIGHT) {
            return -1;
        }
        var unusual = shape.type() != LightShape.STEADY || specialLit || dynamic || !spot && shape.effect() != 0;
        var fixed = spot && !turnRestriction.isEmpty();
        return (unusual ? 1_000 : 0) + (fixed ? 500 : 0) + tags.size();
    }

    String getRestriction(LightColor value, int knownBrightnessType) {
        return getRestriction(value, getPlacement(), knownBrightnessType, 0);
    }

    String getRestriction(LightColor value, LightPlacement place, int knownBrightnessType, int knownRadiusType) {
        return getRestriction(value, place, null, shape, knownBrightnessType, knownRadiusType);
    }

    String getRestriction(
            LightColor value,
            LightPlacement place,
            @Nullable LightRegion newRegion,
            LightShape aim,
            int knownBrightnessType,
            int knownRadiusType) {
        if (newRegion != null && !newRegion.equals(region)) {
            if (region == null) {
                return "Its zone is saved in an unusual way, so it cannot move to another BSP leaf";
            }
            if (!relocatable && PointRegionTag.getZoneSize(newRegion) != zoneEnd - zoneAt) {
                return "This light keeps the size of its saved data, so it cannot move to this zone";
            }
        }
        if (!restriction.isEmpty()) {
            return restriction;
        }
        var turn = getShapeRestriction(aim);
        if (!turn.isEmpty()) {
            return turn;
        }
        if (value.brightness() < 0 || value.brightness() > LightColor.MAX_BRIGHTNESS) {
            return "Brightness must be between 0 and " + (int) LightColor.MAX_BRIGHTNESS;
        }
        if (!place.location().equals(location) && !placed) {
            return "This light has no saved place, so it cannot move";
        }
        var radiusChanges = Float.compare(place.radius(), radius) != 0;
        if (radiusChanges) {
            if (place.radius() < 0 || place.radius() > LightPlacement.MAX_RADIUS) {
                return "Radius must be between 0 and " + (int) LightPlacement.MAX_RADIUS;
            }
            var type = radiusType != 0 ? radiusType : knownRadiusType;
            if (type == 0) {
                return "No light of this map saves a radius, so its property type is not known";
            }
            if (type == -1) {
                return "Its radius has an unusual layout";
            }
            if (type == 1 && place.radius() != Math.rint(place.radius())) {
                return "This map saves the radius as a whole number from 0 to 255";
            }
        }
        if (!relocatable) {
            for (var name : TAGS) {
                if (changes(name, value, place, newRegion, aim) && tag(name) == null) {
                    return "This light keeps the size of its saved data, so only its saved values can change";
                }
            }
        }
        var type = brightnessType != 0 ? brightnessType : knownBrightnessType;
        if (value.brightness() != color.brightness()) {
            if (type == 0) {
                return "No light of this map saves a brightness, so its property type is not known";
            }
            if (type == 1 && (value.brightness() != Math.rint(value.brightness()) || value.brightness() > 255)) {
                return "This map saves brightness as a whole number from 0 to 255";
            }
        }
        return "";
    }

    String getShapeRestriction(LightShape aim) {
        if (aim.type() != shape.type() || aim.effect() != shape.effect()) {
            return "Only the direction and the cone of a light can change";
        }
        if (aim.rotation().equals(shape.rotation()) && aim.cone() == shape.cone()) {
            return "";
        }
        if (!shape.isSpot()) {
            return "Only a spotlight has a direction and a cone";
        }
        if (aim.cone() < 0 || aim.cone() > LightShape.MAX_CONE) {
            return "Cone must be between 0 and " + LightShape.MAX_CONE;
        }
        if (!aim.rotation().equals(shape.rotation()) && !turnRestriction.isEmpty()) {
            return turnRestriction;
        }
        return aim.cone() != shape.cone() ? coneRestriction : "";
    }

    byte[] write(PackageBytes source, LightColor value, int knownBrightnessType, ToIntFunction<String> intern)
            throws IOException {
        return write(source, value, getPlacement(), null, shape, knownBrightnessType, 0, intern);
    }

    byte[] write(
            PackageBytes source,
            LightColor value,
            LightPlacement place,
            @Nullable LightRegion newRegion,
            LightShape aim,
            int knownBrightnessType,
            int knownRadiusType,
            ToIntFunction<String> intern)
            throws IOException {
        return write(source, value, place, newRegion, aim, knownBrightnessType, knownRadiusType, intern, -1);
    }

    byte[] write(
            PackageBytes source,
            LightColor value,
            LightPlacement place,
            @Nullable LightRegion newRegion,
            LightShape aim,
            int knownBrightnessType,
            int knownRadiusType,
            ToIntFunction<String> intern,
            int moveEvent)
            throws IOException {
        var carried = isCarryOnly()
                && value.equals(color)
                && aim.type() == shape.type()
                && aim.effect() == shape.effect()
                && aim.cone() == shape.cone();
        var reason = carried
                ? getCarryRestriction(place, newRegion, aim.rotation())
                : getRestriction(value, place, newRegion, aim, knownBrightnessType, knownRadiusType);
        if (!reason.isEmpty()) {
            throw new IOException(reason + ": " + path);
        }
        var brightnessTag = brightnessType != 0 ? brightnessType : knownBrightnessType;
        var radiusTag = radiusType != 0 ? radiusType : knownRadiusType;
        var out = new ByteArrayOutputStream();
        var position = entry.serialOffset();
        var missing = new ArrayList<String>();
        for (var name : TAGS) {
            if (changes(name, value, place, newRegion, aim) && tag(name) == null) {
                missing.add(name);
            }
        }
        for (var tag : tags) {
            if (moveEvent >= 0 && tag.name.equalsIgnoreCase("L2MoveEvent")) {
                out.write(source.body, position, tag.start - position);
                var payload = new ByteArrayOutputStream();
                PackageBytes.writeIndex(payload, moveEvent);
                PackageBytes.writeIndex(out, intern.applyAsInt("L2MoveEvent"));
                var sizeType = switch (payload.size()) {
                    case 1 -> 0;
                    case 2 -> 1;
                    case 4 -> 2;
                    default -> 5;
                };
                out.write(6 | sizeType << 4);
                if (sizeType == 5) {
                    out.write(payload.size());
                }
                out.writeBytes(payload.toByteArray());
                position = tag.end;
                continue;
            }
            if (!TAGS.contains(tag.name) || !changes(tag.name, value, place, newRegion, aim)) {
                continue;
            }
            if (tag.name.equals("Region")) {
                out.write(source.body, position, tag.start - position);
                out.writeBytes(PointRegionTag.write(
                        source,
                        tag.start,
                        tag.info,
                        tag.sizeStart,
                        tag.end,
                        new int[] {zoneAt, zoneEnd, leafAt, numberAt},
                        region,
                        newRegion,
                        intern));
                position = tag.end;
                continue;
            }
            out.write(source.body, position, tag.payload - position);
            out.writeBytes(payload(tag.name, value, place, aim, tag.type));
            position = tag.end;
        }
        out.write(source.body, position, terminator - position);
        for (var name : missing) {
            writeIndex(out, intern.applyAsInt(name));
            if (name.equals("Rotation")) {
                out.write(10 | 3 << 4);
                writeIndex(out, intern.applyAsInt("Rotator"));
                out.writeBytes(payload(name, value, place, aim, 10));
                continue;
            }
            var type = switch (name) {
                case "LightBrightness" -> brightnessTag;
                case "LightRadius" -> radiusTag;
                default -> 1;
            };
            var payload = payload(name, value, place, aim, type);
            out.write(type | (payload.length == 4 ? 2 : 0) << 4);
            out.writeBytes(payload);
        }
        var end = entry.serialOffset() + entry.serialSize();
        out.write(source.body, terminator, end - terminator);
        return out.toByteArray();
    }

    private boolean changes(
            String name, LightColor value, LightPlacement place, @Nullable LightRegion newRegion, LightShape aim) {
        return switch (name) {
            case "LightBrightness" -> Float.compare(value.brightness(), color.brightness()) != 0;
            case "LightHue" -> value.hue() != color.hue();
            case "LightSaturation" -> value.saturation() != color.saturation();
            case "LightRadius" -> Float.compare(place.radius(), radius) != 0;
            case "Region" -> newRegion != null && !newRegion.equals(region);
            case "Location" -> !place.location().equals(location);
            case "Rotation" -> !aim.rotation().equals(shape.rotation());
            case "LightCone" -> aim.cone() != shape.cone();
            default -> throw new IllegalArgumentException("Not an editable light tag: " + name);
        };
    }

    private Tag tag(String name) {
        return tags.stream()
                .filter(tag -> tag.name.equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private static byte[] payload(String name, LightColor value, LightPlacement place, LightShape aim, int type) {
        return switch (name) {
            case "LightBrightness" -> number(value.brightness(), type);
            case "LightHue" -> new byte[] {(byte) value.hue()};
            case "LightSaturation" -> new byte[] {(byte) value.saturation()};
            case "LightRadius" -> number(place.radius(), type);
            case "LightCone" -> new byte[] {(byte) aim.cone()};
            case "Location" ->
                littleEndian(new byte[12])
                        .putFloat(place.location().x())
                        .putFloat(place.location().y())
                        .putFloat(place.location().z())
                        .array();
            case "Rotation" ->
                littleEndian(new byte[12])
                        .putInt(aim.rotation().pitch())
                        .putInt(aim.rotation().yaw())
                        .putInt(aim.rotation().roll())
                        .array();
            default -> throw new IllegalArgumentException("Not an editable light tag: " + name);
        };
    }

    private static byte[] number(float value, int type) {
        return type == 1
                ? new byte[] {(byte) Math.round(value)}
                : littleEndian(new byte[4]).putFloat(value).array();
    }
}

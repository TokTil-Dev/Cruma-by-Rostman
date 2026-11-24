package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import java.util.Objects;

public record LightShape(int type, int effect, int cone, Rotator rotation) {
    public static final LightShape DEFAULT = new LightShape(1, 0, 128, new Rotator(0, 0, 0));

    public static final int MAX_CONE = 255;

    public static final int STEADY = 1;

    public static final int STATIC_SPOT = 8;

    public static final int SPOTLIGHT = 12;

    public static final int SUNLIGHT = 19;

    public LightShape {
        Objects.requireNonNull(rotation);
    }

    public static int coneFor(double halfDegrees) {
        return (int) Math.clamp(Math.round(256 * (1 - Math.cos(Math.toRadians(halfDegrees)))), 0, MAX_CONE);
    }

    public boolean isSpot() {
        return effect == STATIC_SPOT || effect == SPOTLIGHT;
    }

    public LightShape withRotation(Rotator value) {
        return new LightShape(type, effect, cone, value);
    }

    public LightShape withCone(int value) {
        return new LightShape(type, effect, value, rotation);
    }

    public Rotator facing(Vector3 direction) {
        var level = Math.hypot(direction.x(), direction.y());
        var pitch = (int) Math.round(Math.atan2(direction.z(), level) * (65536 / (Math.PI * 2)));
        var yaw = level < 1e-6
                ? rotation.yaw()
                : (int) Math.round(Math.atan2(direction.y(), direction.x()) * (65536 / (Math.PI * 2)));
        return new Rotator(pitch, yaw, rotation.roll());
    }

    public Vector3 direction() {
        var pitch = rotation.pitch() * (Math.PI * 2 / 65536);
        var yaw = rotation.yaw() * (Math.PI * 2 / 65536);
        return new Vector3((float) (Math.cos(pitch) * Math.cos(yaw)), (float) (Math.cos(pitch) * Math.sin(yaw)), (float)
                Math.sin(pitch));
    }

    public float coneDegrees() {
        return (float) Math.toDegrees(Math.acos(Math.clamp(1 - cone / 256.0, -1, 1)));
    }

    public String describe() {
        var kind = switch (type) {
            case 0 -> "no light type";
            case 1 -> "steady";
            case 2 -> "pulse";
            case 3 -> "blink";
            case 4 -> "flicker";
            case 5 -> "strobe";
            case 6 -> "backdrop";
            case 7 -> "subtle pulse";
            default -> "light type " + type;
        };
        var shine = switch (effect) {
            case 0 -> "";
            case STATIC_SPOT -> "static spotlight";
            case SPOTLIGHT -> "spotlight";
            case 13 -> "no incidence";
            case 17 -> "cylinder";
            case SUNLIGHT -> "sunlight";
            case 20 -> "quadratic, no incidence";
            default -> "effect " + effect;
        };
        return shine.isEmpty() ? kind : kind + " " + shine;
    }
}

package com.rost.cruma.core.npc.script;

import java.util.Locale;

public final class ScriptValues {
    public static final Object SELF = new Object() {
        @Override
        public String toString() {
            return "Self";
        }
    };

    private ScriptValues() {}

    public record Vector(float x, float y, float z) {
        public static final Vector ZERO = new Vector(0, 0, 0);

        Vector plus(Vector other) {
            return new Vector(x + other.x, y + other.y, z + other.z);
        }

        Vector minus(Vector other) {
            return new Vector(x - other.x, y - other.y, z - other.z);
        }

        Vector times(float factor) {
            return new Vector(x * factor, y * factor, z * factor);
        }

        float length() {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }
    }

    public record Rotator(int pitch, int yaw, int roll) {
        public static final Rotator ZERO = new Rotator(0, 0, 0);
    }

    public record Coords(Vector origin, Vector xAxis, Vector yAxis, Vector zAxis) {}

    public record Name(String text) {
        public static final Name NONE = new Name("None");

        public Name {
            text = text.isEmpty() ? "None" : text;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Name name && name.text.equalsIgnoreCase(text);
        }

        @Override
        public int hashCode() {
            return text.toLowerCase(Locale.ROOT).hashCode();
        }

        @Override
        public String toString() {
            return text;
        }
    }

    public record Enum(String text) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Enum value && value.text.equalsIgnoreCase(text);
        }

        @Override
        public int hashCode() {
            return text.toLowerCase(Locale.ROOT).hashCode();
        }
    }

    public record ClassRef(String name) {}
}

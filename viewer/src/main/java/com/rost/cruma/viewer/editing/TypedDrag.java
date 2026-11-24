package com.rost.cruma.viewer.editing;

import java.util.Locale;

final class TypedDrag {
    private final StringBuilder digits = new StringBuilder();
    private boolean negative;

    enum Kind {
        MOVE,
        ROTATE,
        SCALE
    }

    static float[] moved(float[] start, float[] direction, float distance) {
        var result = start.clone();
        result[12] += direction[0] * distance;
        result[13] += direction[1] * distance;
        result[14] += direction[2] * distance;
        return result;
    }

    static float[] turned(float[] start, float[] direction, float degrees) {
        var angle = Math.toRadians(degrees);
        var c = (float) Math.cos(angle);
        var s = (float) Math.sin(angle);
        var t = 1 - c;
        var x = direction[0];
        var y = direction[1];
        var z = direction[2];
        var rotation = EditorTransforms.identity();
        rotation[0] = t * x * x + c;
        rotation[1] = t * x * y + s * z;
        rotation[2] = t * x * z - s * y;
        rotation[4] = t * x * y - s * z;
        rotation[5] = t * y * y + c;
        rotation[6] = t * y * z + s * x;
        rotation[8] = t * x * z + s * y;
        rotation[9] = t * y * z - s * x;
        rotation[10] = t * z * z + c;
        var result = EditorTransforms.multiply(rotation, start);
        result[12] = start[12];
        result[13] = start[13];
        result[14] = start[14];
        return result;
    }

    static float[] sized(float[] start, int axis, float factor) {
        var result = start.clone();
        for (var row = 0; row < 3; row++) {
            result[axis * 4 + row] *= factor;
        }
        return result;
    }

    static float[] direction(float[] matrix, int axis, boolean local) {
        if (!local) {
            var result = new float[3];
            result[axis] = 1;
            return result;
        }
        var x = matrix[axis * 4];
        var y = matrix[axis * 4 + 1];
        var z = matrix[axis * 4 + 2];
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-6f) {
            var result = new float[3];
            result[axis] = 1;
            return result;
        }
        return new float[] {x / length, y / length, z / length};
    }

    boolean type(char c) {
        if (c >= '0' && c <= '9') {
            digits.append(c);
            return true;
        }
        if (c == '.' || c == ',') {
            if (digits.indexOf(".") < 0) {
                digits.append('.');
            }
            return true;
        }
        if (c == '-') {
            negative = !negative;
            return true;
        }
        return false;
    }

    void backspace() {
        if (!digits.isEmpty()) {
            digits.setLength(digits.length() - 1);
        } else {
            negative = false;
        }
    }

    boolean isEmpty() {
        return digits.isEmpty() && !negative;
    }

    void clear() {
        digits.setLength(0);
        negative = false;
    }

    double getValue() {
        var text = digits.toString();
        if (text.isEmpty() || text.equals(".")) {
            return Double.NaN;
        }
        var number = Double.parseDouble(text.startsWith(".") ? "0" + text : text);
        return negative ? -number : number;
    }

    String getTag(Kind kind, int axis) {
        var name = "XYZ".charAt(axis);
        if (kind == Kind.SCALE) {
            return name + " ×" + (negative ? "-" : "") + digits;
        }
        var sign = negative ? "-" : digits.isEmpty() ? "" : "+";
        return name + " " + sign + digits + (kind == Kind.ROTATE && !digits.isEmpty() ? "°" : "");
    }

    float[] apply(Kind kind, float[] start, int axis, boolean local) {
        var value = getValue();
        if (Double.isNaN(value)) {
            return start.clone();
        }
        return switch (kind) {
            case MOVE -> moved(start, direction(start, axis, local), (float) value);
            case ROTATE -> turned(start, direction(start, axis, local), (float) value);
            case SCALE -> sized(start, axis, (float) value);
        };
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s%s", negative ? "-" : "", digits);
    }
}

package com.rost.cruma.viewer.application;

public final class OrthoSlice {
    public static final int LIMIT = 500_000;

    public static final int EMPTY_REACH = 32_768;

    public static final int SHORTEST_BAR = 1_024;

    public boolean cutNear;
    public final int[] near = {0};
    public boolean cutFar;
    public final int[] far = {0};

    public record Track(int nearEnd, int farEnd) {
        public static Track over(int min, int max, int away) {
            var low = Math.min(min, max);
            var high = Math.max(Math.max(min, max), low + SHORTEST_BAR);
            return away > 0 ? new Track(low, high) : new Track(high, low);
        }

        public static Track around(int place, int away) {
            return over(place - EMPTY_REACH, place + EMPTY_REACH, away);
        }

        public Track holding(boolean on, int coordinate) {
            if (!on) {
                return this;
            }
            var away = away();
            return new Track(
                    (coordinate - nearEnd) * away < 0 ? coordinate : nearEnd,
                    (coordinate - farEnd) * away > 0 ? coordinate : farEnd);
        }

        public float position(int coordinate) {
            return Math.clamp((coordinate - nearEnd) / (float) (farEnd - nearEnd), 0f, 1f);
        }

        public int coordinate(float position) {
            return nearEnd + Math.round(position * (farEnd - nearEnd));
        }

        public int away() {
            return farEnd >= nearEnd ? 1 : -1;
        }

        public int length() {
            return Math.abs(farEnd - nearEnd);
        }
    }

    public static void keepOrder(int[] near, int[] far, int away) {
        if ((far[0] - near[0]) * away < 1) {
            far[0] = near[0] + away;
        }
    }

    public static void move(boolean cutNear, int[] near, boolean cutFar, int[] far, int delta, int limit) {
        var allowed = delta;
        if (cutNear) {
            allowed = limited(allowed, near[0], limit);
        }
        if (cutFar) {
            allowed = limited(allowed, far[0], limit);
        }
        if (cutNear) {
            near[0] += allowed;
        }
        if (cutFar) {
            far[0] += allowed;
        }
    }

    public boolean on() {
        return cutNear || cutFar;
    }

    public void off() {
        cutNear = false;
        cutFar = false;
    }

    public void keepOrder(int away) {
        if (cutNear && cutFar) {
            keepOrder(near, far, away);
        }
    }

    private static int limited(int delta, int value, int limit) {
        return Math.clamp(delta, Math.min(0, -limit - value), Math.max(0, limit - value));
    }
}

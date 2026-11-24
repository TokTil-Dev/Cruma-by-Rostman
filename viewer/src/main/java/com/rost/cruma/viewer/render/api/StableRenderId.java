package com.rost.cruma.viewer.render.api;

import java.nio.charset.StandardCharsets;

public final class StableRenderId {
    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private StableRenderId() {}

    public static Builder builder(String namespace) {
        return new Builder().put(namespace);
    }

    public static final class Builder {
        private long value = OFFSET_BASIS;

        public Builder put(String text) {
            if (text == null) {
                return put(-1);
            }
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            put(bytes.length);
            for (var current : bytes) {
                mix(current & 0xff);
            }
            return this;
        }

        public Builder put(boolean current) {
            mix(current ? 1 : 0);
            return this;
        }

        public Builder put(int current) {
            mix(current);
            mix(current >>> 8);
            mix(current >>> 16);
            mix(current >>> 24);
            return this;
        }

        public Builder put(long current) {
            put((int) current);
            put((int) (current >>> 32));
            return this;
        }

        public Builder put(float current) {
            return put(Float.floatToIntBits(current));
        }

        public Builder put(byte[] bytes) {
            if (bytes == null) {
                return put(-1);
            }
            put(bytes.length);
            for (var current : bytes) {
                mix(current & 0xff);
            }
            return this;
        }

        public long build() {
            return value == 0L ? 1L : value;
        }

        private void mix(int current) {
            value ^= current & 0xffL;
            value *= PRIME;
        }
    }
}

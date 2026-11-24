package com.rost.cruma.viewer.render;

public class Context {
    public final Framebuffer framebuffer = new Framebuffer();
    public float time;

    public static class Framebuffer {
        public int width;
        public int height;
    }
}

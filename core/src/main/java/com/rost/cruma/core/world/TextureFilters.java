package com.rost.cruma.core.world;

public record TextureFilters(int min, int mag, int mip) {
    public static final TextureFilters INHERITED = new TextureFilters(0, 0, 0);
}

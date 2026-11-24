package com.rost.cruma.viewer.ui;

public record MapTransform(
        WorldMapCoordinates coordinates,
        float imageX,
        float imageY,
        float width,
        float height,
        float clipLeft,
        float clipTop,
        float clipRight,
        float clipBottom,
        float zoom) {
    public float x(double worldX) {
        return imageX + u(worldX) * width;
    }

    public float y(double worldY) {
        return imageY + v(worldY) * height;
    }

    public float u(double worldX) {
        return coordinates.toU((float) worldX);
    }

    public float v(double worldY) {
        return coordinates.toV((float) worldY);
    }

    public double worldX(float screenX) {
        return coordinates.toWorldX((screenX - imageX) / width);
    }

    public double worldY(float screenY) {
        return coordinates.toWorldY((screenY - imageY) / height);
    }

    public float scale() {
        return width / (coordinates.columns * 32768f);
    }

    public boolean isVisible(double minX, double minY, double maxX, double maxY) {
        return x(maxX) >= clipLeft && x(minX) <= clipRight && y(maxY) >= clipTop && y(minY) <= clipBottom;
    }

    public boolean isInView(float screenX, float screenY) {
        return screenX >= clipLeft && screenX <= clipRight && screenY >= clipTop && screenY <= clipBottom;
    }
}

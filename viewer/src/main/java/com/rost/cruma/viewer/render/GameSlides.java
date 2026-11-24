package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.GameScenes.Screen;
import java.util.List;

public final class GameSlides {
    private static final double HIDE_SECONDS = 1;
    private static final int ALPHA = 0;
    private static final int LEFT = 1;
    private static final int RIGHT = 2;
    private static final int UP = 3;
    private static final int DOWN = 4;
    private static final float BOX_SHAPE = 4f / 3;

    private final Slide slide = new Slide();
    private final Slide backSlide = new Slide();
    private final List<Slide> slides = List.of(slide, backSlide);
    private Slide current = slide;
    private Slide next = backSlide;
    private boolean shows;

    public GameSlides() {
        clear();
    }

    public static Box box(float width, float height) {
        var boxWidth = Math.min(width, height * BOX_SHAPE);
        var boxHeight = boxWidth / BOX_SHAPE;
        return new Box((width - boxWidth) * .5f, (height - boxHeight) * .5f, boxWidth, boxHeight);
    }

    public void run(Screen screen, double at) {
        advance(at);
        switch (screen.kind()) {
            case SLIDE_START -> start();
            case SLIDE_CHANGE -> change(screen, at);
            case SLIDE_MOVE -> move(screen, at);
            case SLIDE_END -> shows = false;
            default -> {
            }
        }
    }

    public void advance(double seconds) {
        slide.now = seconds;
        backSlide.now = seconds;
    }

    public void clear() {
        shows = false;
        current = slide;
        next = backSlide;
        slide.reset(1);
        backSlide.reset(0);
    }

    public boolean shows() {
        return shows;
    }

    public List<Slide> getSlides() {
        return slides;
    }

    public List<Shown> getShown() {
        return List.of(slide.getShown(), backSlide.getShown());
    }

    private void start() {
        slide.take("", 0, 0);
        backSlide.take("", 0, 0);
        shows = true;
    }

    private void change(Screen screen, double at) {
        if (screen.slideDirection() != ALPHA || screen.slide().isEmpty()) {
            return;
        }
        current.fade(0, at, HIDE_SECONDS);
        next.place(0, 0, 1, 1);
        next.take(screen.slide(), screen.slideWidth(), screen.slideHeight());
        next.show(at, screen.slideSeconds());
        var old = current;
        current = next;
        next = old;
    }

    private void move(Screen screen, double at) {
        if (screen.slide().isEmpty()) {
            return;
        }
        var size = screen.slideWindow() / 100.0;
        var travel = screen.slideMove() / 100.0;
        switch (screen.slideDirection()) {
            case LEFT -> {
                current.place(0, 0, size, 1);
                current.move(-travel, 0, at, screen.slideSeconds());
            }
            case RIGHT -> {
                current.place(1 - size, 0, size, 1);
                current.move(travel, 0, at, screen.slideSeconds());
            }
            case UP -> {
                current.place(0, 0, 1, size);
                current.move(0, -travel, at, screen.slideSeconds());
            }
            case DOWN -> {
                current.place(0, 1 - size, 1, size);
                current.move(0, travel, at, screen.slideSeconds());
            }
            default -> {
                return;
            }
        }
        current.take(screen.slide(), screen.slideWidth(), screen.slideHeight());
    }

    public record Crop(int width, int height) {
        public float u(int textureWidth) {
            return whole() ? 1 : Math.min(1, (float) width / textureWidth);
        }

        public float v(int textureHeight) {
            return whole() ? 1 : Math.min(1, (float) height / textureHeight);
        }

        private boolean whole() {
            return width == 0 || height == 0 || width == 1 && height == 1;
        }
    }

    public record Box(float x, float y, float width, float height) {}

    public record Shown(String picture, double x, double y, double width, double height, double alpha, Crop crop) {}

    public static final class Slide {
        private String picture = "";
        private int pictureWidth;
        private int pictureHeight;
        private double left;
        private double top;
        private double width;
        private double height;
        private double travelX;
        private double travelY;
        private double moveStart;
        private double moveSeconds;
        private double alphaFrom;
        private double alphaTo;
        private double alphaStart;
        private double alphaSeconds;
        private double now;

        public String getPicture() {
            return picture;
        }

        public double getX() {
            return left + travelX * moved();
        }

        public double getY() {
            return top + travelY * moved();
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }

        public double getAlpha() {
            return alphaFrom + (alphaTo - alphaFrom) * progress(alphaStart, alphaSeconds);
        }

        public Crop getCrop() {
            return new Crop(pictureWidth, pictureHeight);
        }

        Shown getShown() {
            return new Shown(picture, getX(), getY(), width, height, getAlpha(), getCrop());
        }

        public float getU(int textureWidth) {
            return getCrop().u(textureWidth);
        }

        public float getV(int textureHeight) {
            return getCrop().v(textureHeight);
        }

        private void reset(double alpha) {
            take("", 0, 0);
            place(0, 0, 1, 1);
            alphaFrom = alpha;
            alphaTo = alpha;
            alphaSeconds = 0;
            now = 0;
        }

        private void take(String name, int textureWidth, int textureHeight) {
            picture = name;
            pictureWidth = textureWidth;
            pictureHeight = textureHeight;
        }

        private void place(double x, double y, double across, double down) {
            left = x;
            top = y;
            width = across;
            height = down;
            travelX = 0;
            travelY = 0;
        }

        private void move(double x, double y, double at, double seconds) {
            travelX = x;
            travelY = y;
            moveStart = at;
            moveSeconds = seconds;
        }

        private void fade(double to, double at, double seconds) {
            alphaFrom = getAlpha();
            alphaTo = to;
            alphaStart = at;
            alphaSeconds = seconds;
        }

        private void show(double at, double seconds) {
            alphaFrom = 0;
            alphaTo = 1;
            alphaStart = at;
            alphaSeconds = seconds;
        }

        private double moved() {
            return progress(moveStart, moveSeconds);
        }

        private double progress(double start, double seconds) {
            return seconds <= 0 ? 1 : Math.clamp((now - start) / seconds, 0, 1);
        }
    }
}

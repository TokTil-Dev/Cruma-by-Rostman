package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.LoadingScene.Figure;
import com.rost.cruma.viewer.render.api.ViewerLoadingScene;
import com.rost.cruma.viewer.render.api.ViewerLoadingScene.Frame;
import com.rost.cruma.viewer.render.api.ViewerLoadingScene.Placement;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.ui.LoadingSceneSource.Made;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;

public final class CrumaBranding {
    private static final String ROOT = "/ui/branding/";
    private static final float[] MARK_X = {12, 22, 12, 2};
    private static final float[] MARK_Y = {1, 11, 23, 11};
    private static final int[] FACE_COLORS = {
        ImColor.rgb(67, 200, 195), ImColor.rgb(72, 187, 192),
        ImColor.rgb(34, 134, 143), ImColor.rgb(139, 239, 224)
    };
    private static final float[] JOIN_X = new float[4], JOIN_Y = new float[4];

    static {
        for (int i = 0; i < 4; i++) {
            int previous = (i + 3) % 4, next = (i + 1) % 4;
            float ax = MARK_Y[i] - MARK_Y[previous], ay = MARK_X[previous] - MARK_X[i];
            float bx = MARK_Y[next] - MARK_Y[i], by = MARK_X[i] - MARK_X[next];
            float al = (float) Math.hypot(ax, ay), bl = (float) Math.hypot(bx, by);
            ax /= al;
            ay /= al;
            bx /= bl;
            by /= bl;
            float denominator = 1f + ax * bx + ay * by;
            JOIN_X[i] = (ax + bx) / denominator;
            JOIN_Y[i] = (ay + by) / denominator;
        }
    }

    private static final ImVec2 WHITE_UV = new ImVec2();
    private static final float[] INNER_X = new float[4], INNER_Y = new float[4];
    private static final float[] OUTER_X = new float[4], OUTER_Y = new float[4];
    private static final float TOI_HEIGHT = .6f, TOI_PLACE = .7f, TOI_DEPTH = -1.3f;
    private static final float ROCK_DRIFT = .012f, ROCK_SPEED = .9f, EDGE_HEIGHT = .012f, EDGE_SPEED = 3f;
    private static final int STONE = ImColor.rgb(170, 175, 182),
            FILLED = ImColor.rgb(75, 237, 255),
            EDGE = ImColor.rgb(205, 250, 255);
    private static final Orbit VALAKAS_ORBIT = new Orbit(.52f, .4f, .6f + (float) Math.PI, .78f, .17f, false);
    private static final Orbit ORFEN_ORBIT = new Orbit(1.1f, -.2f, 2.4f, 0, .1f, false);
    private static final Orbit QUEEN_ANT_ORBIT = new Orbit(.8f, .24f, .3f, 0, .09f, true);
    private static final float UNDER_LEVEL = .015f;
    private static final float WALK_LEVEL = .025f, WALK_FAR_LIFT = .1f, WALK_NEAR = .12f;
    private static final float ORBIT_NEAR = .16f, FLIGHT_BOB = .012f;
    private static final float ANTHARAS_SIZE = .12f, ROOF_SINK = .007f;
    private static final float ZAKEN_SIZE = .095f, BAIUM_SIZE = .15f, BAIUM_SINK = .06f;
    private static final float LOOK_AROUND = .7f, LOOK_SPEED = .35f;
    private static final float FACING_THE_VIEWER = (float) Math.toRadians(270);
    private static final float HALF_TURNED_LEFT = (float) Math.toRadians(225);
    private static final int SKY_TOP = ImColor.rgb(11, 15, 24), SKY_HORIZON = ImColor.rgb(47, 52, 68);
    private static final int GROUND_TOP = ImColor.rgb(31, 34, 40), GROUND_BOTTOM = ImColor.rgb(16, 23, 30);
    private static final int HORIZON_LIGHT = ImColor.rgb(255, 186, 128);
    private static final float LIGHT_WIDTH = 1.5f, LIGHT_HEIGHT = .85f, LIGHT_STRENGTH = .42f;
    private static final float GROUND_FADE = .3f;
    private static final float HORIZON_LEVEL = .02f, HORIZON_SOFT = .08f;
    private static final int LIGHT_SIZE = 256;
    private static final float LONGEST_STEP = 1f / 30;
    private final ViewerUiRenderer renderer;
    private final List<Placement> placements = new ArrayList<>();
    private float clock;
    private boolean clientKnown;
    private @Nullable LoadingSceneSource source;
    private @Nullable Sprite ownTower;
    private long horizonLight;
    private @Nullable Made made;
    private @Nullable ViewerLoadingScene scene;
    private boolean sceneTried;

    public CrumaBranding(ViewerUiRenderer renderer) {
        this.renderer = renderer;
        CrumaCrystal.useRenderer(renderer);
    }

    public static void prepareScene(Path client) {
        LoadingSceneSource.forClient(client);
        CrumaCrystal.useClient(client);
    }

    public static boolean takeMarkMoved() {
        return CrumaCrystal.takeMoved();
    }

    public static void drawMark(ImDrawList draw, float x, float y, float size) {
        if (CrumaCrystal.draw(draw, x, y, size)) {
            return;
        }
        float s = size / 24f, cx = x + 12 * s, cy = y + 14 * s;
        float density = Math.max(
                1f,
                Math.max(
                        ImGui.getIO().getDisplayFramebufferScaleX(),
                        ImGui.getIO().getDisplayFramebufferScaleY()));
        float halfFringe = .5f / density;
        for (int i = 0; i < 4; i++) {
            float px = x + MARK_X[i] * s, py = y + MARK_Y[i] * s;
            INNER_X[i] = px - JOIN_X[i] * halfFringe;
            INNER_Y[i] = py - JOIN_Y[i] * halfFringe;
            OUTER_X[i] = px + JOIN_X[i] * halfFringe;
            OUTER_Y[i] = py + JOIN_Y[i] * halfFringe;
        }
        ImGui.getFontTexUvWhitePixel(WHITE_UV);
        draw.primReserve(36, 36);
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            int color = (FACE_COLORS[i] & 0x00ffffff)
                    | Math.round(255 * ImGui.getStyle().getAlpha()) << 24;
            int clear = color & 0x00ffffff;
            addVertex(draw, INNER_X[i], INNER_Y[i], color);
            addVertex(draw, INNER_X[next], INNER_Y[next], color);
            addVertex(draw, cx, cy, color);
            addVertex(draw, INNER_X[i], INNER_Y[i], color);
            addVertex(draw, OUTER_X[i], OUTER_Y[i], clear);
            addVertex(draw, OUTER_X[next], OUTER_Y[next], clear);
            addVertex(draw, INNER_X[i], INNER_Y[i], color);
            addVertex(draw, OUTER_X[next], OUTER_Y[next], clear);
            addVertex(draw, INNER_X[next], INNER_Y[next], color);
        }
    }

    static float groundLift(float near) {
        return WALK_LEVEL + (near < 0 ? near * near * WALK_FAR_LIFT : 0);
    }

    public static void setWindowIcon(long window) {
        if (Platform.get() == Platform.MACOSX) {
            return;
        }
        try (var image = Image.load("cruma-icon-64.png");
                var stack = MemoryStack.stackPush()) {
            var icons = GLFWImage.malloc(1, stack);
            icons.width(image.width).height(image.height).pixels(image.pixels);
            GLFW.glfwSetWindowIcon(window, icons);
        }
    }

    private record Orbit(float radius, float speed, float start, float height, float size, boolean under) {}

    private record Sprite(long texture, float u0, float v0, float u1, float v1, float aspect) {
        static Sprite load(ViewerUiRenderer renderer, String label, String name) {
            try (var image = Image.load(name)) {
                var pixels = image.pixels;
                int width = image.width, height = image.height;
                var texture = renderer.createTexture(label, width, height, pixels);
                int left = width, top = height, right = 0, bottom = 0;
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        if ((pixels.get((y * width + x) * 4 + 3) & 255) > 8) {
                            left = Math.min(left, x);
                            top = Math.min(top, y);
                            right = Math.max(right, x + 1);
                            bottom = Math.max(bottom, y + 1);
                        }
                    }
                }
                if (right <= left || bottom <= top) {
                    throw new IllegalStateException("Empty branding image: " + name);
                }
                return new Sprite(
                        texture,
                        left / (float) width,
                        top / (float) height,
                        right / (float) width,
                        bottom / (float) height,
                        (right - left) / (float) (bottom - top));
            }
        }
    }

    private record Image(int width, int height, ByteBuffer pixels) implements AutoCloseable {
        static Image load(String name) {
            try (var stream = CrumaBranding.class.getResourceAsStream(ROOT + name)) {
                if (stream == null) {
                    throw new IOException("Missing branding resource: " + name);
                }
                var decoded = ImageIO.read(stream);
                if (decoded == null) {
                    throw new IOException("Invalid branding PNG: " + name);
                }
                int w = decoded.getWidth(), h = decoded.getHeight();
                var pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(w, h), 4));
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int argb = decoded.getRGB(x, y);
                        pixels.put((byte) (argb >> 16))
                                .put((byte) (argb >> 8))
                                .put((byte) argb)
                                .put((byte) (argb >> 24));
                    }
                }
                return new Image(w, h, pixels.flip());
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot load Cruma branding", failure);
            }
        }

        @Override
        public void close() {
            MemoryUtil.memFree(pixels);
        }
    }

    public void useClient(Path client) {
        if (!clientKnown) {
            clientKnown = true;
            source = LoadingSceneSource.forClient(client);
            CrumaCrystal.useClient(client);
        }
    }

    public void releaseLoadingScene() {
        if (scene != null) {
            renderer.destroyLoadingScene(scene);
            scene = null;
        }
        sceneTried = false;
    }

    public void mark(ImDrawList draw, float x, float y, float size) {
        drawMark(draw, x, y, size);
    }

    public void drawLoadingBackground(
            ImDrawList draw,
            float left,
            float top,
            float right,
            float bottom,
            float centerX,
            float floor,
            float height) {
        var level = floor - height * HORIZON_LEVEL;
        draw.addRectFilledMultiColor(
                left,
                top,
                right,
                level,
                faded(SKY_TOP, 1),
                faded(SKY_TOP, 1),
                faded(SKY_HORIZON, 1),
                faded(SKY_HORIZON, 1));
        if (horizonLight == 0) {
            horizonLight = makeHorizonLight();
        }
        float reach = height * LIGHT_WIDTH, rise = height * LIGHT_HEIGHT;
        draw.addImage(
                horizonLight,
                centerX - reach,
                level - rise,
                centerX + reach,
                level,
                0,
                0,
                1,
                .5f,
                faded(HORIZON_LIGHT, LIGHT_STRENGTH));
        draw.addRectFilledMultiColor(
                left,
                level - height * HORIZON_SOFT,
                right,
                level,
                faded(GROUND_TOP, 0),
                faded(GROUND_TOP, 0),
                faded(GROUND_TOP, 1),
                faded(GROUND_TOP, 1));
        var fadeEnd = level + height * GROUND_FADE;
        draw.addRectFilledMultiColor(
                left,
                level,
                right,
                fadeEnd,
                faded(GROUND_TOP, 1),
                faded(GROUND_TOP, 1),
                faded(GROUND_BOTTOM, 1),
                faded(GROUND_BOTTOM, 1));
        draw.addRectFilled(left, fadeEnd, right, bottom, faded(GROUND_BOTTOM, 1));
    }

    public void drawLoadingTower(ImDrawList draw, float centerX, float y, float height, float progress) {
        if (isSceneStillComing()) {
            return;
        }
        if (Motion.isEnabled()) {
            clock += Math.min(ImGui.getIO().getDeltaTime(), LONGEST_STEP);
        }
        float bottom = y + height, fill = Math.clamp(progress, 0f, 1f);
        float edge = progress > 0 && progress < 1 ? EDGE_HEIGHT : 0;
        if (scene == null) {
            drawOwnTower(draw, centerX, y, height, bottom - height * fill, edge * height);
            return;
        }
        var density = ImGui.getIO().getDisplayFramebufferScaleX();
        var pixelWidth = Math.max(1, Math.round(2 * ViewerLoadingScene.HALF_WIDTH * height * density));
        var pixelHeight =
                Math.max(1, Math.round((ViewerLoadingScene.TOP - ViewerLoadingScene.BOTTOM) * height * density));
        var halfWidth = made.landmarks().halfWidth();
        var pulse = Motion.isEnabled() ? .65f + .35f * (float) Math.sin(clock * EDGE_SPEED) : 1;
        var glow = .6f + .2f * (float) Math.sin(clock * Math.PI) + .2f * (float) Math.sin(clock * Math.PI * 2 / 3);
        var drift = (float) Math.sin(clock * ROCK_SPEED) * ROCK_DRIFT;
        var toiX = halfWidth * TOI_PLACE;
        placeFigures(toiX);
        var image = scene.render(new Frame(
                pixelWidth,
                pixelHeight,
                fill,
                edge,
                pulse,
                glow,
                drift,
                clock,
                toiX,
                TOI_DEPTH,
                TOI_HEIGHT,
                placements));
        var flipped = scene.isFlipped();
        float left = centerX - ViewerLoadingScene.HALF_WIDTH * height, top = bottom - ViewerLoadingScene.TOP * height;
        draw.addImage(
                image,
                left,
                top,
                centerX + ViewerLoadingScene.HALF_WIDTH * height,
                bottom - ViewerLoadingScene.BOTTOM * height,
                0,
                flipped ? 1 : 0,
                1,
                flipped ? 0 : 1,
                faded(0xffffffff, 1));
    }

    private boolean isSceneStillComing() {
        if (source == null) {
            return false;
        }
        made = source.getMade();
        if (made == null) {
            return true;
        }
        if (!sceneTried) {
            sceneTried = true;
            scene = made.landmarks() == null ? null : renderer.createLoadingScene(made.landmarks(), made.figures());
        }
        return scene != null && !scene.prepare();
    }

    private long makeHorizonLight() {
        var pixels = MemoryUtil.memAlloc(LIGHT_SIZE * LIGHT_SIZE * 4);
        try {
            for (var y = 0; y < LIGHT_SIZE; y++) {
                for (var x = 0; x < LIGHT_SIZE; x++) {
                    float dx = (x + .5f) / LIGHT_SIZE * 2 - 1, dy = (y + .5f) / LIGHT_SIZE * 2 - 1;
                    var strength = Math.max(0, 1 - (float) Math.hypot(dx, dy));
                    pixels.put((byte) 255).put((byte) 255).put((byte) 255).put((byte)
                            Math.round(255 * strength * strength));
                }
            }
            return renderer.createTexture("ui/horizon-light", LIGHT_SIZE, LIGHT_SIZE, pixels.flip());
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private void placeFigures(float toiX) {
        placements.clear();
        var figures = made.figures();
        var look = LOOK_AROUND * (float) Math.sin(clock * LOOK_SPEED);
        stand(figures, LoadingSceneSource.ANTHARAS, 0, 1 - ROOF_SINK, 0, ANTHARAS_SIZE, HALF_TURNED_LEFT);
        stand(
                figures,
                LoadingSceneSource.BAIUM,
                toiX,
                TOI_HEIGHT * (1 - BAIUM_SINK),
                TOI_DEPTH,
                TOI_HEIGHT * BAIUM_SIZE,
                FACING_THE_VIEWER);
        var smallTower = made.landmarks().smallTower();
        if (smallTower != null) {
            stand(
                    figures,
                    LoadingSceneSource.ZAKEN,
                    smallTower[0],
                    smallTower[1],
                    smallTower[2],
                    ZAKEN_SIZE,
                    FACING_THE_VIEWER + look);
        }
        orbit(figures, LoadingSceneSource.VALAKAS, VALAKAS_ORBIT);
        orbit(figures, LoadingSceneSource.ORFEN, ORFEN_ORBIT);
        orbit(figures, LoadingSceneSource.QUEEN_ANT, QUEEN_ANT_ORBIT);
    }

    private void stand(
            List<@Nullable Figure> figures, int index, float x, float feet, float z, float scale, float turned) {
        var figure = figures.get(index);
        if (figure != null) {
            placements.add(new Placement(index, x, feet - figure.lowest() * scale, z, scale, turned, false));
        }
    }

    private void orbit(List<@Nullable Figure> figures, int index, Orbit orbit) {
        var figure = figures.get(index);
        if (figure == null) {
            return;
        }
        float angle = orbit.start + clock * orbit.speed, near = (float) Math.sin(angle);
        boolean walking = orbit.height == 0 && !orbit.under;
        float scale = orbit.size * (1 + (walking ? WALK_NEAR : ORBIT_NEAR) * near);
        float y;
        if (orbit.under) {
            y = UNDER_LEVEL + figure.lowest() * scale;
        } else if (walking) {
            y = groundLift(near) - figure.lowest() * scale;
        } else {
            y = orbit.height + (float) Math.sin(angle * 3) * FLIGHT_BOB;
        }
        float turned = FACING_THE_VIEWER - angle + (orbit.speed < 0 ? (float) Math.PI : 0);
        placements.add(new Placement(
                index, orbit.radius * (float) Math.cos(angle), y, orbit.radius * near, scale, turned, orbit.under));
    }

    private void drawOwnTower(ImDrawList draw, float centerX, float y, float height, float level, float edge) {
        if (ownTower == null) {
            ownTower = Sprite.load(renderer, "ui/cruma-tower", "cruma-tower.png");
        }
        var own = ownTower;
        float width = height * own.aspect, x = centerX - width * .5f;
        layer(draw, own.texture, x, y, x + width, y + height, own.u0, own.v0, own.u1, own.v1, level, edge);
    }

    private void layer(
            ImDrawList draw,
            long texture,
            float x0,
            float y0,
            float x1,
            float y1,
            float u0,
            float v0,
            float u1,
            float v1,
            float level,
            float edge) {
        draw.addImage(texture, x0, y0, x1, y1, u0, v0, u1, v1, faded(STONE, 1));
        band(draw, texture, x0, y0, x1, y1, u0, v0, u1, v1, level, y1, FILLED, 1);
        if (edge > 0) {
            float pulse = Motion.isEnabled() ? .65f + .35f * (float) Math.sin(clock * EDGE_SPEED) : 1;
            band(draw, texture, x0, y0, x1, y1, u0, v0, u1, v1, level - edge, level + edge, EDGE, pulse);
        }
    }

    private static void addVertex(ImDrawList draw, float x, float y, int color) {
        draw.primVtx(x, y, WHITE_UV.x, WHITE_UV.y, color);
    }

    private static void band(
            ImDrawList draw,
            long texture,
            float x0,
            float y0,
            float x1,
            float y1,
            float u0,
            float v0,
            float u1,
            float v1,
            float from,
            float to,
            int color,
            float alpha) {
        float top = Math.max(y0, from), bottom = Math.min(y1, to);
        if (bottom <= top) {
            return;
        }
        float scale = (v1 - v0) / (y1 - y0);
        draw.addImage(
                texture,
                x0,
                top,
                x1,
                bottom,
                u0,
                v0 + (top - y0) * scale,
                u1,
                v0 + (bottom - y0) * scale,
                faded(color, alpha));
    }

    private static int faded(int color, float alpha) {
        return color & 0x00ffffff | Math.round(255 * alpha * ImGui.getStyle().getAlpha()) << 24;
    }
}

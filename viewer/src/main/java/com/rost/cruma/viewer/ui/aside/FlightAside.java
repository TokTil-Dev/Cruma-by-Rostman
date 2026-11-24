package com.rost.cruma.viewer.ui.aside;

import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.DxtChainCache;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.render.bgfx.BgfxCapabilities;
import com.rost.cruma.viewer.render.bgfx.BgfxTileFlight;
import com.rost.cruma.viewer.render.bgfx.TileFlightGeometry;
import com.rost.cruma.viewer.render.bgfx.TileFlightLight;
import com.rost.cruma.viewer.system.SoftHeapLimit;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImDrawList;
import imgui.ImGui;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class FlightAside implements StartAside {
    static final long REST_NANOS = 500_000_000L;

    private static final Logger log = LogManager.getLogger(FlightAside.class);
    private static final int LARGEST_TEXTURE = 512;
    private static final long UPLOAD_NANOS = 3_000_000L;
    private static final float FIELD_OF_VIEW = 55;
    private static final float RADIUS = 8f;
    private static final float STRIP = HudWidgets.CONTROL_HEIGHT + 2 * GeodataWidgets.ROW;
    private static final float TAG = 26;
    private static final float TOP_ROOM = GeodataWidgets.BLOCK + TAG + GeodataWidgets.BLOCK;
    private static final int BACKING = HudWidgets.BACKGROUND & 0x00ffffff | 0xd0000000;

    private @Nullable BgfxTileFlight flight;
    private @Nullable String choice;
    private List<Location> places = List.of();
    private float nextHour = 13;
    private @Nullable String wanted;
    private long wantedSince;
    private @Nullable FlightPath wantedPath;
    private @Nullable Load load;
    private final List<Load> stopping = new ArrayList<>();
    private @Nullable String shown;
    private boolean uploaded;
    private @Nullable FlightPath shownPath;
    private FlightPath.@Nullable Framing shownFraming;
    private @Nullable TileFlightLight shownLight;
    private String failure = "";
    private boolean paused;
    private double seconds;
    private long lastNanos;
    private long loadStarted;
    private long firstPictureNanos = -1;

    public FlightAside() {}

    private record Taken(
            TileFlightGeometry geometry,
            FlightPath.Framing framing,
            float overGround,
            @Nullable TileFlightLight light) {}

    private static final class Load implements Runnable {
        final String key;
        final Path client;
        final WorldVariant variant;
        final FlightPath path;
        final String tile;
        final BgfxCapabilities capabilities;
        final float hour;
        final FlightPath.Picture picture;
        final Thread thread;
        volatile boolean cancelled;
        volatile boolean done;
        volatile String failure = "";
        private @Nullable TileFlightGeometry result;
        private FlightPath.@Nullable Framing framing;
        private float overGround;
        private @Nullable TileFlightLight light;

        Load(
                String key,
                Path client,
                WorldVariant variant,
                FlightPath path,
                BgfxCapabilities capabilities,
                float hour,
                FlightPath.Picture picture) {
            this.key = key;
            this.client = client;
            this.variant = variant;
            this.path = path;
            this.tile = path.tile();
            this.capabilities = capabilities;
            this.hour = hour;
            this.picture = picture;
            thread = new Thread(this, "start-flight-load");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
        }

        @Override
        public void run() {
            UnrealLoader loader = null;
            try {
                loader = new UnrealLoader(client, new DxtChainCache(new Options(false, 0)), true);
                loader.worldVariant(variant);
                var map = cancelled ? null : loader.loadMap(tile);
                if (map == null && !cancelled) {
                    failure = "This client has no map " + tile + ".";
                } else if (map != null) {
                    var geometry = TileFlightGeometry.build(map, capabilities, LARGEST_TEXTURE, hour, () -> cancelled);
                    if (geometry.isEmpty() && !cancelled) {
                        failure = "The map " + tile + " has nothing to fly over.";
                    } else if (!geometry.isEmpty()) {
                        hand(geometry);
                    }
                }
            } catch (RuntimeException | LinkageError | OutOfMemoryError broken) {
                if (!cancelled) {
                    log.warn("The flight over {} did not load", tile, broken);
                    failure = "Could not load " + tile + ": " + reasonOf(broken);
                }
            } finally {
                if (loader != null) {
                    loader.trimCaches();
                }
                done = true;
                SoftHeapLimit.collectSoon();
            }
        }

        synchronized void cancel() {
            cancelled = true;
            result = null;
        }

        synchronized @Nullable Taken take() {
            var geometry = result;
            var taken = framing;
            result = null;
            return cancelled || geometry == null || taken == null
                    ? null
                    : new Taken(geometry, taken, overGround, light);
        }

        private void hand(TileFlightGeometry geometry) {
            var ground = path.computeHeight(geometry::findGround);
            var framed = path.computeFraming(geometry::findGround, geometry.getObstacles(), picture);
            var clientLight = cancelled ? null : TileFlightLight.of(client, hour, framed.eyeZ());
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                framing = framed;
                overGround = framed.eyeZ() - ground;
                light = clientLight;
                result = geometry;
            }
        }

        private static String reasonOf(Throwable broken) {
            var message = broken.getMessage();
            return message == null || message.isBlank() ? broken.getClass().getSimpleName() : message;
        }
    }

    @Override
    public String getLabel() {
        return "Flight";
    }

    @Override
    public void draw(Context context, float width, float height) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, RADIUS);
        var now = System.nanoTime();
        follow(context, now);
        var path = wantedPath;
        if (context.client() == null || path == null) {
            line(draw, x, y, width, height, "Choose a client and a tile on the map to fly over it.", HudWidgets.MUTED);
            end(x, y, width, height);
            return;
        }
        stopping.removeIf(stopped -> !stopped.thread.isAlive());
        if (wanted != null
                && !wanted.equals(shown)
                && load == null
                && stopping.isEmpty()
                && failure.isEmpty()
                && now - wantedSince >= REST_NANOS) {
            start(context, path, now, height);
        }
        try {
            show(context, draw, x, y, width, height, now);
        } catch (RuntimeException | LinkageError broken) {
            log.warn("The flight over {} stopped", path.tile(), broken);
            dropFlight();
            failure = "Could not show " + path.tile() + ": " + Load.reasonOf(broken);
            line(draw, x, y, width, height, failure, GeodataWidgets.BLOCKED);
        }
        end(x, y, width, height);
    }

    @Override
    public boolean isAnimating() {
        return !paused && shown != null && shown.equals(wanted) && flight != null && flight.hasPicture();
    }

    @Override
    public void close() {
        if (load != null) {
            load.cancel();
            stopping.add(load);
            load = null;
        }
        stopping.removeIf(stopped -> !stopped.thread.isAlive());
        dropFlight();
        choice = null;
        wanted = null;
        wantedPath = null;
        failure = "";
        firstPictureNanos = -1;
        SoftHeapLimit.collectSoon();
    }

    public long getFirstPictureNanos() {
        return firstPictureNanos;
    }

    public boolean isLoading() {
        return load != null;
    }

    private void show(Context context, ImDrawList draw, float x, float y, float width, float height, long now) {
        var path = wantedPath;
        if (path == null) {
            return;
        }
        if (load != null && load.done) {
            finish(context);
        }
        var shownFlight = flight;
        if (shown != null && shownFlight != null && !uploaded) {
            uploaded = shownFlight.upload(UPLOAD_NANOS);
            if (uploaded) {
                SoftHeapLimit.collectSoon();
            }
        }
        if (shown != null && shownFlight != null && shownFlight.hasPicture()) {
            if (firstPictureNanos < 0) {
                firstPictureNanos = now - loadStarted;
                log.info(
                        "The flight over {} shows its first picture {} ms after its load began",
                        shownPath == null ? "" : shownPath.tile(),
                        firstPictureNanos / 1_000_000);
            }
            fly(context, draw, shownFlight, x, y, width, height, now);
        } else if (!failure.isEmpty()) {
            line(draw, x, y, width, height, failure, GeodataWidgets.BLOCKED);
        } else {
            line(draw, x, y, width, height, "Loading " + path.tile() + "…", HudWidgets.MUTED);
        }
    }

    private void follow(Context context, long now) {
        var client = context.client();
        var next = client == null
                ? null
                : client.root() + "|" + client.variant() + "|" + context.tiles() + "|" + context.title();
        if (Objects.equals(next, choice)) {
            return;
        }
        choice = next;
        var path = client == null ? null : choosePath(context.loader(), context.tiles(), context.title());
        var key = path == null
                ? null
                : client.root() + "|" + client.variant() + "|" + path.tile() + "|" + path.centerX() + "|"
                        + path.centerY();
        if (Objects.equals(key, wanted)) {
            return;
        }
        wanted = key;
        wantedPath = path;
        wantedSince = now;
        failure = "";
        if (load != null) {
            load.cancel();
            stopping.add(load);
            load = null;
        }
        if (flight != null && shown != null) {
            flight.show(null);
        }
        shown = null;
        shownPath = null;
    }

    private @Nullable FlightPath choosePath(ConfigLoader loader, List<String> tiles, String title) {
        try {
            var settings = loader.load();
            places = settings.viewer().camera().locations();
            nextHour = (float) settings.viewer().world().rendering().timeOfDay();
        } catch (RuntimeException unreadable) {
            log.debug("The flight reads no places: {}", unreadable.toString());
        }
        return FlightPath.choose(tiles, title, places);
    }

    private void dropFlight() {
        if (flight != null) {
            try {
                flight.close();
            } catch (RuntimeException failed) {
                log.warn("The flight's GPU resources did not close: {}", failed.toString());
            }
            flight = null;
        }
        shown = null;
        shownPath = null;
    }

    private void start(Context context, FlightPath path, long now, float height) {
        var client = context.client();
        if (client == null) {
            return;
        }
        loadStarted = now;
        firstPictureNanos = -1;
        var next = new Load(
                wanted,
                client.root(),
                client.variant(),
                path,
                context.device().getCapabilities(),
                nextHour,
                new FlightPath.Picture(FIELD_OF_VIEW, height, TOP_ROOM));
        load = next;
        next.thread.start();
    }

    private void finish(Context context) {
        var done = load;
        load = null;
        if (done == null || done.cancelled || !done.key.equals(wanted)) {
            return;
        }
        var taken = done.take();
        if (taken == null) {
            failure = done.failure.isEmpty() ? "Could not load " + done.tile + "." : done.failure;
            return;
        }
        if (flight == null) {
            flight = new BgfxTileFlight(context.device(), context.renderer());
        }
        flight.show(taken.geometry());
        uploaded = false;
        shown = done.key;
        shownPath = done.path;
        var framing = taken.framing();
        shownFraming = framing;
        shownLight = taken.light() != null ? taken.light() : FlightPath.lightAt(done.hour);
        log.info(
                "The flight over {} circles at {} and flies at {}, {} over what the ground alone asks for, looks at"
                        + " {}, {}, {}, in the light of {}",
                done.tile,
                Math.round(framing.radius()),
                Math.round(framing.eyeZ()),
                Math.round(taken.overGround()),
                Math.round(framing.lookX()),
                Math.round(framing.lookY()),
                Math.round(framing.lookZ()),
                taken.light() != null ? "the client" : "the panel");
        seconds = 0;
        lastNanos = System.nanoTime();
    }

    private void fly(
            Context context,
            ImDrawList draw,
            BgfxTileFlight shownFlight,
            float x,
            float y,
            float width,
            float height,
            long now) {
        var path = shownPath;
        var framing = shownFraming;
        if (path == null || framing == null) {
            return;
        }
        if (!paused) {
            seconds += Math.min(.1, (now - lastNanos) / 1e9);
        }
        lastNanos = now;
        var io = ImGui.getIO();
        var pixelsWide = Math.round(width * io.getDisplayFramebufferScaleX());
        var pixelsHigh = Math.round(height * io.getDisplayFramebufferScaleY());
        var eye = path.getEye(seconds, framing);
        var image = paused && shownFlight.hasImage(pixelsWide, pixelsHigh)
                ? shownFlight.getImageId()
                : shownFlight.render(
                        pixelsWide,
                        pixelsHigh,
                        new BgfxTileFlight.View(
                                eye[0],
                                eye[1],
                                eye[2],
                                framing.lookX(),
                                framing.lookY(),
                                framing.lookZ(),
                                FIELD_OF_VIEW,
                                Objects.requireNonNull(shownLight)));
        var flipped = shownFlight.isFlipped();
        draw.addImageRounded(
                image, x, y, x + width, y + height, 0, flipped ? 1 : 0, 1, flipped ? 0 : 1, 0xffffffff, RADIUS);
        tag(draw, x, y, "LIVE · FLYING OVER " + path.tile());
        strip(context, draw, x, y + height - STRIP - GeodataWidgets.BLOCK, width);
    }

    private void strip(Context context, ImDrawList draw, float x, float top, float width) {
        var left = x + GeodataWidgets.BLOCK;
        var right = x + width - GeodataWidgets.BLOCK;
        draw.addRectFilled(left, top, right, top + STRIP, BACKING, RADIUS);
        ImGui.setCursorScreenPos(left + GeodataWidgets.ROW, top + GeodataWidgets.ROW);
        if (HudWidgets.iconButton("flight-pause", paused ? Glyph.PLAY : Glyph.PAUSE, false, HudWidgets.TEXT)) {
            paused = !paused;
        }
        GeodataWidgets.tip(paused ? "Play the flight." : "Pause the flight. The menu then rests.");
        var textLeft = left + GeodataWidgets.ROW * 2 + HudWidgets.CONTROL_HEIGHT;
        var room = right - GeodataWidgets.BLOCK - textLeft;
        var title = ellipsize(context.title(), room);
        draw.addText(textLeft, top + (STRIP - ImGui.getTextLineHeight()) * .5f, HudWidgets.TEXT, title);
    }

    private static void tag(ImDrawList draw, float x, float y, String text) {
        var left = x + GeodataWidgets.BLOCK;
        var top = y + GeodataWidgets.BLOCK;
        var right = left + GeodataWidgets.ROW * 3 + ImGui.calcTextSizeX(text) + GeodataWidgets.ROW;
        draw.addRectFilled(left, top, right, top + TAG, BACKING, TAG * .5f);
        var textTop = top + (TAG - ImGui.getTextLineHeight()) * .5f;
        draw.addCircleFilled(
                left + GeodataWidgets.ROW * 1.5f, HudWidgets.textMiddle(textTop), 3.5f, GeodataWidgets.BLOCKED);
        draw.addText(left + GeodataWidgets.ROW * 3, textTop, HudWidgets.TEXT, text);
    }

    private static void line(ImDrawList draw, float x, float y, float width, float height, String text, int color) {
        var shown = ellipsize(text, width - 2 * GeodataWidgets.BLOCK);
        var textWidth = ImGui.calcTextSizeX(shown);
        draw.addText(x + (width - textWidth) * .5f, y + (height - ImGui.getTextLineHeight()) * .5f, color, shown);
    }

    private static void end(float x, float y, float width, float height) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, height);
    }

    private static String ellipsize(String text, float room) {
        if (ImGui.calcTextSizeX(text) <= room) {
            return text;
        }
        var end = text.length();
        while (end > 0 && ImGui.calcTextSizeX(text.substring(0, end) + "…") > room) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}

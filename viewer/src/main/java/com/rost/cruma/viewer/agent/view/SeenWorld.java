package com.rost.cruma.viewer.agent.view;

import com.rost.cruma.core.geodata.GeoDataRepository;
import com.rost.cruma.viewer.editing.GroundProbe;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class SeenWorld implements AutoCloseable {
    private static final int MOST_CAPTURES = 64;

    record View(int x, int y, int width, int height, Snapshot camera) {
        boolean holds(double pixelX, double pixelY) {
            return pixelX >= x && pixelY >= y && pixelX < x + width && pixelY < y + height;
        }

        float[] ndc(double pixelX, double pixelY) {
            return new float[] {(float) ((pixelX - x) / width * 2 - 1), (float) (1 - (pixelY - y) / height * 2)};
        }
    }

    record Capture(int id, Path file, int width, int height, List<View> views) {
        Capture {
            views = List.copyOf(views);
        }

        @Nullable
        View viewAt(double x, double y) {
            for (var view : views) {
                if (view.holds(x, y)) {
                    return view;
                }
            }
            return null;
        }
    }

    record Mark(int number, ScenePlacement placement) {}

    private final Map<Integer, Capture> captures = new LinkedHashMap<>();
    private List<Mark> marks = List.of();
    private int nextCapture = 1;
    private int lastCapture;
    private @Nullable GroundProbe ground;
    private @Nullable ActorBook book;
    private @Nullable GeoDataRepository regions;
    private @Nullable Path regionsFolder;

    synchronized GroundProbe getGround(Path client) {
        if (ground == null) {
            ground = new GroundProbe(client);
        }
        return ground;
    }

    synchronized ActorBook getBook(Path client) {
        if (book == null) {
            book = new ActorBook(client);
        }
        return book;
    }

    synchronized GeoDataRepository getRegions(Path folder) {
        if (regions == null || !folder.equals(regionsFolder)) {
            regions = new GeoDataRepository(folder);
            regionsFolder = folder;
        }
        return regions;
    }

    @Override
    public synchronized void close() {
        if (ground != null) {
            ground.close();
            ground = null;
        }
    }

    synchronized int nextCaptureId() {
        return nextCapture++;
    }

    synchronized void add(Capture capture) {
        captures.put(capture.id(), capture);
        lastCapture = capture.id();
        var oldest = captures.keySet().iterator();
        while (captures.size() > MOST_CAPTURES) {
            oldest.next();
            oldest.remove();
        }
    }

    synchronized @Nullable Capture capture(@Nullable Integer id) {
        return captures.get(id == null ? lastCapture : id);
    }

    synchronized void marks(List<Mark> numbered) {
        marks = new ArrayList<>(numbered);
    }

    public synchronized @Nullable ScenePlacement mark(int number) {
        for (var mark : marks) {
            if (mark.number() == number) {
                return mark.placement();
            }
        }
        return null;
    }

    public synchronized int getMarkCount() {
        return marks.size();
    }
}

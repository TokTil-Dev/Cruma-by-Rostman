package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.geodata.GeoPlaces;
import com.rost.cruma.viewer.geodata.GeoPlaces.Place;
import com.rost.cruma.viewer.geodata.GeoPlaces.Wanted;
import com.rost.cruma.viewer.ui.GuideHost.Geodata;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

final class GeodataGuideActions {
    static final int REACH = 2048;

    private static final long LOAD_WAIT_NANOS = 15_000_000_000L;
    private static final String TOOL = "geodata";
    private static final String NO_GEODATA = "This map has no geodata: it is loaded alone, without the server's world.";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "guide-places");
        thread.setDaemon(true);
        return thread;
    });

    private GeodataGuideActions() {}

    static void addTo(Map<String, GuideAction> actions) {
        add(actions, new OpenPanel());
        add(actions, new ShowCells());
        add(
                actions,
                new FindPlace(
                        "geodata-show-layers",
                        GeoPlaces::severalFloors,
                        (place, away) -> String.format(
                                Locale.ROOT,
                                "Selected a cell with %d floors, %,d units away. Inspect lists its floors.",
                                place.layers(),
                                away),
                        "No cell with two floors within " + REACH
                                + " units of the camera. Fly to a bridge, a building or a dungeon and try again."));
        add(
                actions,
                new FindPlace(
                        "geodata-show-closed-side",
                        GeoPlaces::closedSide,
                        (place, away) -> String.format(
                                Locale.ROOT,
                                "Selected a floor with a closed side, %,d units away. The compass in Inspect shows it"
                                        + " in red.",
                                away),
                        "No floor with a closed side within " + REACH
                                + " units of the camera. Fly near a wall or a rock and try again."));
    }

    private static void add(Map<String, GuideAction> actions, GuideAction action) {
        actions.put(action.getId(), action);
    }

    private static @Nullable String cellsBlocked(GuideHost host) {
        var missing = NotedGuideAction.withoutTool(host, TOOL, NO_GEODATA);
        if (missing != null) {
            return missing;
        }
        var geodata = host.getGeodata();
        return geodata == null ? "Open a world first." : geodata.getUnavailable();
    }

    private static final class OpenPanel implements GuideAction {
        @Override
        public String getId() {
            return "geodata-open-panel";
        }

        @Override
        public @Nullable String getBlocked(GuideHost host) {
            return NotedGuideAction.withoutTool(host, TOOL, NO_GEODATA);
        }

        @Override
        public void run(GuideHost host) {
            host.openTool(TOOL);
        }
    }

    private static final class ShowCells implements GuideAction {
        @Override
        public String getId() {
            return "geodata-show-cells";
        }

        @Override
        public @Nullable String getBlocked(GuideHost host) {
            return cellsBlocked(host);
        }

        @Override
        public void run(GuideHost host) {
            var geodata = host.getGeodata();
            if (geodata == null) {
                return;
            }
            geodata.show();
            var camera = geodata.getCamera();
            geodata.goTo(camera[0], camera[1], camera[2]);
        }
    }

    private static final class FindPlace implements GuideAction {
        private final String id;
        private final Wanted wanted;
        private final BiFunction<Place, Long, String> found;
        private final String missing;
        private @Nullable CompletableFuture<@Nullable Place> work;
        private @Nullable AtomicBoolean stop;
        private double[] from = new double[3];
        private @Nullable String note;
        private long waitingUntil;

        FindPlace(String id, Wanted wanted, BiFunction<Place, Long, String> found, String missing) {
            this.id = id;
            this.wanted = wanted;
            this.found = found;
            this.missing = missing;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public @Nullable String getBlocked(GuideHost host) {
            return cellsBlocked(host);
        }

        @Override
        public void run(GuideHost host) {
            var geodata = host.getGeodata();
            if (geodata == null) {
                return;
            }
            if (stop != null) {
                stop.set(true);
            }
            work = null;
            note = null;
            geodata.show();
            from = geodata.getCamera();
            waitingUntil = System.nanoTime() + LOAD_WAIT_NANOS;
            start(geodata);
        }

        @Override
        public @Nullable String getStatus(GuideHost host) {
            var geodata = host.getGeodata();
            if (waitingUntil != 0 && geodata != null) {
                start(geodata);
                if (work == null) {
                    if (System.nanoTime() < waitingUntil) {
                        return "Looking for a place…";
                    }
                    waitingUntil = 0;
                    note = "The regions around the camera did not load. Fly over a region the folder holds and try"
                            + " again.";
                }
            }
            var running = work;
            if (running == null) {
                return note;
            }
            if (!running.isDone()) {
                return "Looking for a place…";
            }
            work = null;
            Place place = null;
            try {
                place = running.join();
            } catch (RuntimeException failure) {
                LogManager.getLogger(GeodataGuideActions.class)
                        .warn("The search of {} failed: {}", id, failure.toString());
            }
            if (place == null || geodata == null) {
                note = missing;
                return note;
            }
            geodata.goTo(place.middleX(), place.middleY(), place.layer().height());
            var away = Math.round(Math.hypot(place.middleX() - from[0], place.middleY() - from[1]));
            note = found.apply(place, away);
            return note;
        }

        @Override
        public boolean isBusy() {
            return work != null || waitingUntil != 0;
        }

        @Override
        public void reset() {
            if (stop != null) {
                stop.set(true);
            }
            work = null;
            waitingUntil = 0;
            note = null;
        }

        private void start(Geodata geodata) {
            var cells = geodata.getCells();
            if (cells == null) {
                return;
            }
            waitingUntil = 0;
            var stopped = new AtomicBoolean();
            stop = stopped;
            var x = from[0];
            var y = from[1];
            work = CompletableFuture.supplyAsync(
                    () -> GeoPlaces.nearest(cells, x, y, REACH, wanted, stopped::get), WORKER);
        }
    }
}

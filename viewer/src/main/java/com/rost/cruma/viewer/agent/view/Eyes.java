package com.rost.cruma.viewer.agent.view;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.agent.Located;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.ViewerEndpoint;
import com.rost.cruma.viewer.agent.view.Lens.Area;
import com.rost.cruma.viewer.editing.EditingMaps;
import com.rost.cruma.viewer.editing.GroundProbe;
import com.rost.cruma.viewer.render.CameraView;
import com.rost.cruma.viewer.render.EditorEffectStage;
import com.rost.cruma.viewer.render.ScreenLens;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class Eyes {
    static final int SETTLE_FRAMES = 6;

    static final float DEPTH_REACH = 65_000f;

    static final double DEFAULT_PITCH = -20;

    static final double CLEARANCE = 80;

    private static final long READY_MILLIS = 120_000;
    private static final long FADE_MILLIS = 15_000;
    private static final long SHOT_MILLIS = 20_000;
    private static final long RAYS_MILLIS = 3_000;
    private static final long READ_MILLIS = 60_000;
    private static final long TERRAIN_MILLIS = 20_000;
    private static final int KEPT_PICTURES = 200;
    private static final double DEFAULT_DISTANCE = 1500;
    private static final double LEAST_FIT_DISTANCE = 150;
    private static final double FIT_FACTOR = 2.2;
    private static final int GRID = 16;
    private static final int SAMPLES_PER_OBJECT = 5;
    private static final int NEAREST_ROUNDS = 2;
    private static final double LEAST_SHARE = .012;
    private static final double MOST_AREA = .8;
    private static final double OBJECT_REACH = 15_000;
    private static final double ON_THE_TERRAIN = 4;
    private static final double NEAR_THE_TARGET = .85;
    private static final double PAST_THE_WALL = 60;
    private static final int CLEARING_ROUNDS = 6;
    private static final double[] CROSS = {0, 0, -.2, 0, .2, 0, 0, -.2, 0, .2, -.2, -.2, .2, -.2, -.2, .2, .2, .2};
    private static final double TILT_STEP = 20;
    private static final double HIGHEST_TILT = -80;
    private static final double STRAIGHT_DOWN = -89;
    private static final double STRAIGHT_YAW = 90;
    private static final double ABOVE_THE_SCENE = 500;
    private static final double GUESSED_DEPTH = 3000;
    private static final double GROUP_SQUARE = 4096;
    private static final double LEAST_VIEW_REACH = 64;
    private static final double VIEW_MARGIN = 1.5;
    private static final int OVERLAY_FRAMES = 20;
    private static final long OVERLAY_MILLIS = 8_000;
    private static final AtomicInteger nextFile = new AtomicInteger(1);

    private Eyes() {}

    record Ray(
            float originX,
            float originY,
            float originZ,
            float directionX,
            float directionY,
            float directionZ,
            float distance,
            float pictureX,
            float pictureY) {
        float hitX() {
            return originX + directionX * distance;
        }

        float hitY() {
            return originY + directionY * distance;
        }

        float hitZ() {
            return originZ + directionZ * distance;
        }

        boolean hit() {
            return Float.isFinite(distance);
        }
    }

    record Seen(ScenePlacement placement, Area area, float distance, double spotX, double spotY) {
        Seen with(ScenePlacement better) {
            return new Seen(better, area, distance, spotX, spotY);
        }
    }

    record Clearing(boolean moved, @Nullable String wall) {
        String label() {
            if (wall != null) {
                return ", blocked by " + wall;
            }
            return moved ? ", moved to see" : "";
        }
    }

    record Blocked(Ray ray, double share) {
        static @Nullable Blocked of(List<Ray> rays, double length, double reach) {
            Ray farthest = null;
            var stopped = 0;
            for (var ray : rays) {
                if (ray.hit() && ray.distance() < reach) {
                    stopped++;
                    if (farthest == null || ray.distance() > farthest.distance()) {
                        farthest = ray;
                    }
                }
            }
            if (farthest == null || stopped * 2 <= rays.size()) {
                return null;
            }
            var share = (farthest.distance() + PAST_THE_WALL) / length;
            return share >= 1 ? null : new Blocked(farthest, share);
        }
    }

    record Sight(List<Seen> seen, boolean tested) {}

    static void ready(ViewScript script) throws ToolProblem, InterruptedException {
        script.poll(
                viewer -> viewer.ready() ? Boolean.TRUE : null,
                READY_MILLIS,
                "The tiles are still loading. Call again in a moment.");
    }

    static void settled(ViewScript script) throws ToolProblem, InterruptedException {
        script.poll(
                viewer -> viewer.scene().zoneFades.isRunning()
                                || viewer.scene().skyZones.isWaiting()
                        ? null
                        : Boolean.TRUE,
                FADE_MILLIS,
                "A zone state still fades. Call again in a moment.");
    }

    static @Nullable Pose pose(ViewScript script, ToolArgs args) throws ToolProblem, InterruptedException {
        var text = args.getText("target");
        var yaw = args.getNumber("yaw");
        var pitch = args.getNumber("pitch");
        if (text == null) {
            if (yaw == null && pitch == null) {
                return null;
            }
            return script.read(viewer -> {
                var camera = viewer.scene().camera;
                var eye = camera.getPosition();
                return new Pose(
                        eye.getX(),
                        eye.getY(),
                        eye.getZ(),
                        yaw == null ? camera.getYawDegrees() : yaw,
                        pitch == null ? camera.getPitchDegrees() : pitch);
            });
        }
        var found = script.read(viewer -> viewer.located(text));
        var cameraYaw = script.read(viewer -> viewer.scene().camera.getYawDegrees());
        return aimed(
                script,
                found,
                args.getNumber("distance"),
                yaw == null ? cameraYaw : yaw,
                pitch == null ? DEFAULT_PITCH : pitch);
    }

    static Pose aimed(ViewScript script, Located found, @Nullable Double wanted, double yaw, double pitch)
            throws ToolProblem, InterruptedException {
        var target = found.target();
        var z = height(script, target);
        var distance = wanted != null ? wanted : fitDistance(found.bounds());
        var pose = Pose.around(target.x(), target.y(), z, distance, yaw, pitch);
        var ground = terrain(script, pose.x(), pose.y());
        if (ground != null && pose.z() < ground + CLEARANCE) {
            return Pose.looking(pose.x(), pose.y(), ground + CLEARANCE, target.x(), target.y(), z);
        }
        return pose;
    }

    static double fitDistance(@Nullable Bounds3f box) {
        if (box == null) {
            return DEFAULT_DISTANCE;
        }
        var dx = box.maxX() - box.minX();
        var dy = box.maxY() - box.minY();
        var dz = box.maxZ() - box.minZ();
        return Math.max(LEAST_FIT_DISTANCE, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2 * FIT_FACTOR);
    }

    static Clearing moveToSee(
            ViewScript script, Located found, @Nullable Double wanted, double yaw, double pitch, boolean raise)
            throws ToolProblem, InterruptedException {
        script.wantDepth();
        var target = found.target();
        var z = height(script, target);
        for (var tilt = pitch; tilt >= (raise ? HIGHEST_TILT : pitch); tilt -= TILT_STEP) {
            var pose = aimed(script, found, wanted, yaw, tilt);
            move(script, pose);
            script.frames(SETTLE_FRAMES);
            if (blockedAt(script, pose, target.x(), target.y(), z, found.bounds()) == null) {
                return new Clearing(tilt != pitch, null);
            }
        }
        var asked = aimed(script, found, wanted, yaw, pitch);
        if (goPast(script, asked, target.x(), target.y(), z, found.bounds())) {
            return new Clearing(true, null);
        }
        move(script, asked);
        script.frames(SETTLE_FRAMES);
        var blocked = blockedAt(script, asked, target.x(), target.y(), z, found.bounds());
        var wall = blocked == null ? null : hitObject(script, blocked.ray());
        return new Clearing(false, wall == null ? "something" : wall.name());
    }

    static float[] around(double x, double y) {
        var ndc = new float[CROSS.length];
        for (var index = 0; index < CROSS.length; index += 2) {
            ndc[index] = (float) Math.clamp(x + CROSS[index], -1, 1);
            ndc[index + 1] = (float) Math.clamp(y + CROSS[index + 1], -1, 1);
        }
        return ndc;
    }

    static @Nullable ScenePlacement hitObject(ViewScript script, Ray ray) throws ToolProblem, InterruptedException {
        var ground = terrain(script, ray.hitX(), ray.hitY());
        var onTerrain = ground != null && Math.abs(ground - ray.hitZ()) <= ON_THE_TERRAIN;
        var kinds = onTerrain ? EnumSet.of(Category.TERRAIN) : EnumSet.allOf(Category.class);
        var hit = script.read(viewer -> Optional.ofNullable(viewer.backend()
                .findObjectOnRay(
                        new Vec3(ray.originX(), ray.originY(), ray.originZ()),
                        new Vec3(ray.directionX(), ray.directionY(), ray.directionZ()),
                        ray.distance(),
                        kinds)));
        return hit.map(found -> ScenePlacement.of(found.object())).orElse(null);
    }

    static double height(ViewScript script, Target target) throws ToolProblem, InterruptedException {
        if (target.z() != null) {
            return target.z();
        }
        var ground = terrain(script, target.x(), target.y());
        return ground != null
                ? ground
                : script.read(
                        viewer -> (double) viewer.scene().camera.getPosition().getZ());
    }

    static @Nullable Double terrain(ViewScript script, double x, double y) throws ToolProblem, InterruptedException {
        var height = script.poll(
                viewer -> {
                    var probe = ground(viewer);
                    if (!probe.isReady(x, y)) {
                        return null;
                    }
                    return Optional.ofNullable(probe.getCurrent(x, y));
                },
                TERRAIN_MILLIS,
                "The Terrain of the map was not read in time.");
        return height.map(Float::doubleValue).orElse(null);
    }

    static double[] terrains(ViewScript script, double[] xs, double[] ys) throws ToolProblem, InterruptedException {
        return script.poll(
                viewer -> {
                    var probe = ground(viewer);
                    for (var index = 0; index < xs.length; index++) {
                        if (!probe.isReady(xs[index], ys[index])) {
                            return null;
                        }
                    }
                    var heights = new double[xs.length];
                    for (var index = 0; index < xs.length; index++) {
                        var height = probe.getCurrent(xs[index], ys[index]);
                        heights[index] = height == null ? Double.NaN : height;
                    }
                    return heights;
                },
                TERRAIN_MILLIS,
                "The Terrain of the map was not read in time.");
    }

    static void move(ViewScript script, Pose pose) throws ToolProblem, InterruptedException {
        script.run(viewer -> {
            script.holdCamera(viewer);
            var camera = viewer.scene().camera;
            if (camera.getView() != CameraView.PERSPECTIVE) {
                camera.setView(CameraView.PERSPECTIVE);
            }
            pose.applyTo(camera);
        });
    }

    static void overlay(ViewScript script) throws ToolProblem, InterruptedException {
        if (!script.read(viewer -> viewer.scene().geodata.enabled)) {
            return;
        }
        script.frames(OVERLAY_FRAMES);
        script.poll(
                viewer -> viewer.scene().geodata.loading ? null : Boolean.TRUE,
                OVERLAY_MILLIS,
                "The geodata around the view did not load in time.");
        script.frames(SETTLE_FRAMES);
    }

    static Path folder(ViewerContext viewer) throws ToolProblem {
        var directory = viewer.configDirectory();
        if (directory == null) {
            throw new ToolProblem("This viewer keeps no files.");
        }
        var folder = ViewerEndpoint.folder(directory).resolve("captures");
        try {
            Files.createDirectories(folder);
        } catch (IOException failed) {
            throw new ToolProblem("Cannot write to " + folder + ": " + failed.getMessage());
        }
        return folder;
    }

    static double below(ViewScript script, double x, double y, double z) throws ToolProblem, InterruptedException {
        move(script, new Pose(x, y, z, STRAIGHT_YAW, STRAIGHT_DOWN));
        script.frames(SETTLE_FRAMES);
        var lens = script.read(viewer -> Lens.of(viewer.scene().camera));
        var under = lens.project(x, y, z - DEPTH_REACH);
        if (under == null) {
            return Double.NaN;
        }
        var ray = rays(script, new float[] {(float) under[0], (float) under[1]}).getFirst();
        return ray.hit() ? ray.hitZ() : Double.NaN;
    }

    static double[] surfaces(ViewScript script, double[] xs, double[] ys) throws ToolProblem, InterruptedException {
        var result = new double[xs.length];
        Arrays.fill(result, Double.NaN);
        if (script.read(viewer -> viewer.agent().isHeadless())) {
            return result;
        }
        var top = script.read(viewer -> {
            var bounds = viewer.backend().getSceneBounds();
            return bounds == null ? Double.valueOf(0) : Double.valueOf(bounds.maxZ() + ABOVE_THE_SCENE);
        });
        var grounds = terrains(script, xs, ys);
        var narrower = script.read(viewer -> {
            var size = viewer.window().framebuffer;
            var shape = (double) Math.max(1, size.width) / Math.max(1, size.height);
            return Double.valueOf(narrowerThanWorld(viewer.scene().camera.getLens(), shape));
        });
        for (var group : groups(xs, ys)) {
            var middleX = 0.0;
            var middleY = 0.0;
            for (var index : group) {
                middleX += xs[index] / group.size();
                middleY += ys[index] / group.size();
            }
            var heights = new double[group.size()];
            var reach = LEAST_VIEW_REACH;
            for (var position = 0; position < group.size(); position++) {
                var index = group.get(position);
                var ground = grounds[index];
                heights[position] = Double.isFinite(ground) ? ground : top - GUESSED_DEPTH;
                reach = Math.max(reach, Math.hypot(xs[index] - middleX, ys[index] - middleY));
            }
            var lowest = Arrays.stream(heights).min().orElse(top - GUESSED_DEPTH);
            var fov = Math.toDegrees(2 * Math.atan(reach * VIEW_MARGIN / Math.max(1, top - lowest) / narrower));
            move(script, new Pose(middleX, middleY, top, STRAIGHT_YAW, STRAIGHT_DOWN));
            script.run(viewer -> viewer.scene().camera.setFov((float) fov));
            script.frames(SETTLE_FRAMES);
            var lens = script.read(viewer -> Lens.of(viewer.scene().camera));
            for (var round = 0; round < 2; round++) {
                var ndc = new float[group.size() * 2];
                for (var position = 0; position < group.size(); position++) {
                    var index = group.get(position);
                    var at = lens.project(xs[index], ys[index], heights[position]);
                    ndc[position * 2] = at == null ? 0 : (float) at[0];
                    ndc[position * 2 + 1] = at == null ? 0 : (float) at[1];
                }
                var found = rays(script, ndc);
                for (var position = 0; position < group.size(); position++) {
                    if (found.get(position).hit()) {
                        heights[position] = found.get(position).hitZ();
                        result[group.get(position)] = heights[position];
                    }
                }
            }
        }
        return result;
    }

    static Path newFile(ViewerContext viewer) throws ToolProblem {
        return folder(viewer).resolve(ProcessHandle.current().pid() + "-" + nextFile.getAndIncrement() + ".jpg");
    }

    static CompletableFuture<Path> startShot(ViewScript script, boolean withInterface)
            throws ToolProblem, InterruptedException {
        var done = new CompletableFuture<Path>();
        script.run(viewer -> {
            var file = newFile(viewer);
            viewer.backend().takeAgentShot(file, withInterface, (written, failure) -> {
                if (failure == null) {
                    done.complete(written);
                } else {
                    done.completeExceptionally(failure);
                }
            });
        });
        return done;
    }

    static Path shoot(ViewScript script, boolean withInterface) throws ToolProblem, InterruptedException {
        return awaitShot(script, startShot(script, withInterface));
    }

    static Path awaitShot(ViewScript script, CompletableFuture<Path> shot) throws ToolProblem, InterruptedException {
        return script.await(shot, SHOT_MILLIS, "The picture did not come back in time.");
    }

    static void tidy(Path file) {
        try {
            Picture.keepLast(file.getParent(), KEPT_PICTURES);
        } catch (IOException ignored) {
        }
    }

    static List<ScenePlacement> placements(ViewScript script, @Nullable Bounds3f box, Predicate<DebugSceneObject> keep)
            throws ToolProblem, InterruptedException {
        var read = new SceneRead(box, keep);
        return script.poll(
                viewer -> read.step(viewer.backend()),
                READ_MILLIS,
                "Reading the scene took too long. Ask for a smaller place.");
    }

    static List<Ray> rays(ViewScript script, float[] ndc) throws ToolProblem, InterruptedException {
        return script.poll(
                viewer -> {
                    var distances = viewer.backend().getSceneRayDistances(ndc, DEPTH_REACH);
                    if (distances == null) {
                        return null;
                    }
                    var camera = viewer.scene().camera;
                    var rays = new ArrayList<Ray>(distances.length);
                    for (var index = 0; index < distances.length; index++) {
                        var origin = camera.getCursorRayOrigin(ndc[index * 2], ndc[index * 2 + 1]);
                        var direction = camera.getCursorRayDirection(ndc[index * 2], ndc[index * 2 + 1]);
                        rays.add(new Ray(
                                origin.getX(),
                                origin.getY(),
                                origin.getZ(),
                                direction.getX(),
                                direction.getY(),
                                direction.getZ(),
                                distances[index],
                                (ndc[index * 2] + 1) / 2,
                                (1 - ndc[index * 2 + 1]) / 2));
                    }
                    return rays;
                },
                RAYS_MILLIS,
                "The depth of the scene did not come back. Is the camera moving?");
    }

    static Sight visible(ViewScript script, Lens lens, int limit) throws ToolProblem, InterruptedException {
        var untested = lens.ortho()
                || script.read(viewer -> !viewer.scene().getViewMode().picksObjects());
        var box = new Bounds3f(
                (float) (lens.x() - OBJECT_REACH),
                (float) (lens.y() - OBJECT_REACH),
                (float) (lens.z() - OBJECT_REACH),
                (float) (lens.x() + OBJECT_REACH),
                (float) (lens.y() + OBJECT_REACH),
                (float) (lens.z() + OBJECT_REACH));
        var placements = placements(
                script,
                lens.ortho() ? null : box,
                row -> ScenePlacement.isObject(row) || EditorEffectStage.isEffectRow(row));
        var candidates = candidates(placements, lens);
        if (untested) {
            candidates.sort(Comparator.comparingDouble(
                            (Candidate candidate) -> candidate.area.width() * candidate.area.height())
                    .reversed());
            var seen = new ArrayList<Seen>();
            for (var candidate : candidates) {
                seen.add(new Seen(
                        candidate.placement,
                        candidate.area,
                        Float.NaN,
                        candidate.area.middleX(),
                        candidate.area.middleY()));
            }
            return new Sight(seen, false);
        }
        credit(rays(script, grid()), candidates);
        for (var round = 0; round < NEAREST_ROUNDS && found(candidates) < limit; round++) {
            var waiting = new ArrayList<Candidate>();
            for (var candidate : candidates) {
                if (candidate.distance == Float.POSITIVE_INFINITY && !candidate.tried) {
                    candidate.tried = true;
                    waiting.add(candidate);
                    if (waiting.size() == ViewerRenderBackend.RAY_POINTS / SAMPLES_PER_OBJECT) {
                        break;
                    }
                }
            }
            if (waiting.isEmpty()) {
                break;
            }
            credit(rays(script, samples(waiting)), candidates);
        }
        var seen = new ArrayList<Seen>();
        for (var candidate : candidates) {
            if (candidate.distance < Float.POSITIVE_INFINITY) {
                seen.add(new Seen(
                        candidate.placement,
                        candidate.area,
                        candidate.distance,
                        candidate.spotX / candidate.hits,
                        candidate.spotY / candidate.hits));
            }
        }
        seen.sort(Comparator.comparingDouble(Seen::distance));
        return new Sight(seen, true);
    }

    private static boolean goPast(ViewScript script, Pose pose, double x, double y, double z, @Nullable Bounds3f box)
            throws ToolProblem, InterruptedException {
        var eye = pose;
        move(script, eye);
        script.frames(SETTLE_FRAMES);
        for (var round = 0; round < CLEARING_ROUNDS; round++) {
            var blocked = blockedAt(script, eye, x, y, z, box);
            if (blocked == null) {
                return true;
            }
            var share = blocked.share();
            eye = new Pose(
                    eye.x() + (x - eye.x()) * share,
                    eye.y() + (y - eye.y()) * share,
                    eye.z() + (z - eye.z()) * share,
                    eye.yaw(),
                    eye.pitch());
            move(script, eye);
            script.frames(SETTLE_FRAMES);
        }
        return blockedAt(script, eye, x, y, z, box) == null;
    }

    private static @Nullable Blocked blockedAt(
            ViewScript script, Pose eye, double x, double y, double z, @Nullable Bounds3f box)
            throws ToolProblem, InterruptedException {
        var lens = script.read(viewer -> Lens.of(viewer.scene().camera));
        var at = lens.project(x, y, z);
        if (at == null || Math.abs(at[0]) > 1 || Math.abs(at[1]) > 1) {
            return null;
        }
        var length = Math.sqrt(Math.pow(x - eye.x(), 2) + Math.pow(y - eye.y(), 2) + Math.pow(z - eye.z(), 2));
        var reach = box == null ? length * NEAR_THE_TARGET : length - fitDistance(box) / FIT_FACTOR;
        return Blocked.of(rays(script, around(at[0], at[1])), length, reach);
    }

    private static GroundProbe ground(ViewerContext viewer) {
        var probe = viewer.agent().getSeen().getGround(viewer.world().clientPath());
        probe.setLoadedMaps(
                EditingMaps.of(viewer.world().clientPath(), viewer.world().variant())
                        .getSources(viewer.loading().getLoadedMaps()));
        if (viewer.scene().hasTerrainEditingSession()) {
            viewer.scene().getTerrainEditing().updateProbe(probe);
        } else {
            probe.setOpen(null, null, null, null);
        }
        return probe;
    }

    private static List<List<Integer>> groups(double[] xs, double[] ys) {
        var squares = new LinkedHashMap<Long, List<Integer>>();
        for (var index = 0; index < xs.length; index++) {
            var key = Math.floorDiv((long) xs[index], (long) GROUP_SQUARE) * 1_000_003L
                    + Math.floorDiv((long) ys[index], (long) GROUP_SQUARE);
            squares.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
        }
        var groups = new ArrayList<List<Integer>>();
        for (var square : squares.values()) {
            for (var from = 0; from < square.size(); from += ViewerRenderBackend.RAY_POINTS) {
                groups.add(square.subList(from, Math.min(square.size(), from + ViewerRenderBackend.RAY_POINTS)));
            }
        }
        return groups;
    }

    private static int found(List<Candidate> candidates) {
        var count = 0;
        for (var candidate : candidates) {
            if (candidate.distance < Float.POSITIVE_INFINITY) {
                count++;
            }
        }
        return count;
    }

    private static List<Candidate> candidates(List<ScenePlacement> placements, Lens lens) {
        var candidates = new ArrayList<Candidate>();
        for (var placement : placements) {
            var area = lens.area(placement.bounds());
            if (area == null
                    || area.width() < LEAST_SHARE
                    || area.height() < LEAST_SHARE
                    || area.width() * area.height() > MOST_AREA) {
                continue;
            }
            candidates.add(new Candidate(placement, area, placement.distanceTo(lens.x(), lens.y(), lens.z())));
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.reach));
        return candidates;
    }

    private static float[] grid() {
        var ndc = new float[GRID * GRID * 2];
        var index = 0;
        for (var row = 0; row < GRID; row++) {
            for (var column = 0; column < GRID; column++) {
                ndc[index++] = (column + .5f) / GRID * 2 - 1;
                ndc[index++] = (row + .5f) / GRID * 2 - 1;
            }
        }
        return ndc;
    }

    private static float[] samples(List<Candidate> candidates) {
        var ndc = new float[candidates.size() * SAMPLES_PER_OBJECT * 2];
        var index = 0;
        double[][] offsets = {{0, 0}, {-.3, -.3}, {.3, -.3}, {-.3, .3}, {.3, .3}};
        for (var candidate : candidates) {
            var area = candidate.area;
            for (var offset : offsets) {
                ndc[index++] = (float) ((area.middleX() + offset[0] * area.width()) * 2 - 1);
                ndc[index++] = (float) (1 - (area.middleY() + offset[1] * area.height()) * 2);
            }
        }
        return ndc;
    }

    private static void credit(List<Ray> rays, List<Candidate> candidates) {
        for (var ray : rays) {
            if (!ray.hit()) {
                continue;
            }
            var tolerance = 2 + ray.distance() * .002f;
            Candidate smallest = null;
            for (var candidate : candidates) {
                if (candidate.holds(ray.hitX(), ray.hitY(), ray.hitZ(), tolerance)
                        && (smallest == null || candidate.volume < smallest.volume)) {
                    smallest = candidate;
                }
            }
            if (smallest != null) {
                smallest.distance = Math.min(smallest.distance, ray.distance());
                smallest.hits++;
                smallest.spotX += ray.pictureX();
                smallest.spotY += ray.pictureY();
            }
        }
    }

    private static double narrowerThanWorld(ScreenLens lens, double shape) {
        return Math.min(lens.computeHalfWidth(90, shape), lens.computeHalfHeight(90, shape))
                / Math.min(ScreenLens.WORLD.computeHalfWidth(90, shape), ScreenLens.WORLD.computeHalfHeight(90, shape));
    }

    private static final class Candidate {
        private final ScenePlacement placement;
        private final Area area;
        private final double reach;
        private final double volume;
        private float distance = Float.POSITIVE_INFINITY;
        private boolean tried;
        private int hits;
        private double spotX;
        private double spotY;

        Candidate(ScenePlacement placement, Area area, double reach) {
            this.placement = placement;
            this.area = area;
            this.reach = reach;
            var box = placement.bounds();
            volume = (double) (box.maxX() - box.minX() + 1)
                    * (box.maxY() - box.minY() + 1)
                    * (box.maxZ() - box.minZ() + 1);
        }

        boolean holds(float x, float y, float z, float tolerance) {
            var box = placement.bounds();
            return x >= box.minX() - tolerance
                    && x <= box.maxX() + tolerance
                    && y >= box.minY() - tolerance
                    && y <= box.maxY() + tolerance
                    && z >= box.minZ() - tolerance
                    && z <= box.maxZ() + tolerance;
        }
    }
}

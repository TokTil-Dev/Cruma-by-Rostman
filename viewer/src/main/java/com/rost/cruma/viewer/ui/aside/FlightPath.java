package com.rost.cruma.viewer.ui.aside;

import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.render.bgfx.TileFlightGeometry;
import com.rost.cruma.viewer.render.bgfx.TileFlightLight;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BinaryOperator;
import org.jetbrains.annotations.Nullable;

public record FlightPath(String tile, float centerX, float centerY, float centerZ, boolean place) {
    static final float RADIUS = 6500;

    static final float HEIGHT = 3800;

    static final float CLEARANCE = 2200;

    static final float ABOVE = 1200;

    static final float BESIDE = 900;

    static final float LOOK = 300;

    static final float FARTHEST = 2;

    static final double SECONDS_PER_TURN = 150;

    private static final float WIDEST = 2 * RADIUS;

    private static final float GROUNDED = 300;

    private static final float RESTING = 20;

    private static final int BOX = TileFlightGeometry.BOX, BOTTOM = 4, TOP = 5;

    private static final int STEPS = 16, SIDES = 48, DOUBLINGS = 12, HALVINGS = 14;

    private static final float NEAR = 40;

    private static final float FOG_START = 6_000, FOG_END = 90_000;

    private static final int FIRST_X = 20, FIRST_Y = 18;

    private static final float TILE = 32768;

    record Framing(float radius, float eyeZ, float lookX, float lookY, float lookZ) {}

    private record Sight(
            double eyeX,
            double eyeY,
            double eyeZ,
            double forwardX,
            double forwardY,
            double forwardZ,
            double upX,
            double upY,
            double upZ) {
        double computeRise(float x, float y, float z, double tangent) {
            double toX = x - eyeX, toY = y - eyeY, toZ = z - eyeZ;
            var depth = toX * forwardX + toY * forwardY + toZ * forwardZ;
            if (depth < NEAR) {
                return Double.POSITIVE_INFINITY;
            }
            return (toX * upX + toY * upY + toZ * upZ) / (depth * tangent);
        }
    }

    record Picture(float fieldOfView, float height, float topRoom) {}

    static @Nullable FlightPath choose(List<String> tiles, String title, List<Location> places) {
        for (var location : places) {
            if (!location.name().equalsIgnoreCase(title.strip())) {
                continue;
            }
            var tile = tileOf(location.x(), location.y());
            if (tiles.contains(tile)) {
                return new FlightPath(tile, location.x(), location.y(), location.z(), true);
            }
        }
        for (var tile : tiles) {
            var parts = tile.split("_");
            if (parts.length != 2) {
                continue;
            }
            try {
                var x = (Integer.parseInt(parts[0]) - FIRST_X + .5f) * TILE;
                var y = (Integer.parseInt(parts[1]) - FIRST_Y + .5f) * TILE;
                return new FlightPath(tile, x, y, Float.NaN, false);
            } catch (NumberFormatException notTile) {
            }
        }
        return null;
    }

    static String tileOf(float x, float y) {
        return String.format(
                Locale.ROOT, "%d_%d", (int) Math.floor(x / TILE) + FIRST_X, (int) Math.floor(y / TILE) + FIRST_Y);
    }

    static double angleAt(double seconds) {
        return seconds / SECONDS_PER_TURN * 2 * Math.PI;
    }

    static TileFlightLight lightAt(float hour) {
        var angle = (hour - 6) / 12 * Math.PI;
        var height = (float) Math.sin(angle);
        var day = Math.clamp(height * 2.5f, 0f, 1f);
        float x = (float) Math.cos(angle), y = .35f, z = Math.max(height, .25f);
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        float[] toward = {x / length, y / length, z / length, 0};
        var sun = mix(new float[] {.10f, .12f, .20f}, new float[] {.85f, .80f, .70f}, day);
        var ambient = mix(new float[] {.10f, .11f, .17f}, new float[] {.30f, .31f, .34f}, day);
        var fog = mix(new float[] {.06f, .07f, .11f}, new float[] {.62f, .69f, .78f}, day);
        return new TileFlightLight(
                toward,
                sun,
                ambient,
                sun,
                ambient,
                fog,
                FOG_START,
                FOG_END,
                mix(new float[] {.02f, .03f, .05f}, new float[] {38 / 255f, 56 / 255f, 64 / 255f}, day),
                fog,
                fog);
    }

    float computeHeight(BinaryOperator<Float> ground) {
        return computeHeight(ground, new float[0], 1);
    }

    float computeHeight(BinaryOperator<Float> ground, float[] boxes) {
        return computeHeight(ground, boxes, 1);
    }

    Framing computeFraming(BinaryOperator<Float> ground, float[] boxes, Picture picture) {
        var under = findUnder(ground);
        var usual = new Framing(RADIUS, computeHeight(ground, boxes, 1), centerX, centerY, under + LOOK);
        var things = findThings(ground, boxes, under);
        var tall = findTall(usual, things, picture);
        if (tall.length == 0) {
            return usual;
        }
        var look = findMiddle(tall, under);
        var framing = usual;
        for (var step = 1; step <= STEPS; step++) {
            var scale = 1 + (FARTHEST - 1) * step / STEPS;
            framing = new Framing(
                    RADIUS * scale, computeHeight(ground, boxes, scale), look[0], look[1], under + LOOK * scale);
            if (fits(framing, things, picture)) {
                return framing;
            }
        }
        float low = 0, high = HEIGHT;
        for (var doubling = 0; doubling < DOUBLINGS && !fits(climb(framing, high), things, picture); doubling++) {
            low = high;
            high *= 2;
        }
        for (var halving = 0; halving < HALVINGS; halving++) {
            var middle = (low + high) * .5f;
            if (fits(climb(framing, middle), things, picture)) {
                high = middle;
            } else {
                low = middle;
            }
        }
        var climbed = climb(framing, high);
        var clear = computeClearHeight(climbed.eyeZ(), boxes, climbed.radius());
        return climb(climbed, clear - climbed.eyeZ());
    }

    float[] getEye(double seconds, Framing framing) {
        var angle = angleAt(seconds);
        return new float[] {
            centerX + (float) Math.cos(angle) * framing.radius(),
            centerY + (float) Math.sin(angle) * framing.radius(),
            framing.eyeZ()
        };
    }

    private float findUnder(BinaryOperator<Float> ground) {
        var under = Float.isNaN(centerZ) ? ground.apply(centerX, centerY) : centerZ;
        return Float.isNaN(under) ? 0 : under;
    }

    private float computeHeight(BinaryOperator<Float> ground, float[] boxes, float scale) {
        var radius = RADIUS * scale;
        var height = findUnder(ground) + HEIGHT * scale;
        for (var step = 0; step < SIDES; step++) {
            var angle = step / (double) SIDES * 2 * Math.PI;
            var at = ground.apply(
                    centerX + (float) Math.cos(angle) * radius, centerY + (float) Math.sin(angle) * radius);
            if (!Float.isNaN(at)) {
                height = Math.max(height, at + CLEARANCE);
            }
        }
        return computeClearHeight(height, boxes, radius);
    }

    private float computeClearHeight(float height, float[] boxes, float radius) {
        var clear = height;
        var lifted = true;
        while (lifted) {
            lifted = false;
            for (var at = 0; at + BOX - 1 < boxes.length; at += BOX) {
                if (boxes[at + BOTTOM] < clear + ABOVE
                        && boxes[at + TOP] + ABOVE > clear
                        && passes(boxes[at], boxes[at + 1], boxes[at + 2], boxes[at + 3], radius)) {
                    clear = boxes[at + TOP] + ABOVE;
                    lifted = true;
                }
            }
        }
        return clear;
    }

    private float[] findThings(BinaryOperator<Float> ground, float[] boxes, float under) {
        var standing = findStanding(ground, boxes, under);
        var things = new float[boxes.length];
        var count = 0;
        for (var at = 0; at + BOX - 1 < boxes.length; at += BOX) {
            float west = boxes[at], north = boxes[at + 1], east = boxes[at + 2], south = boxes[at + 3];
            var middleX = (west + east) * .5f;
            var middleY = (north + south) * .5f;
            if (!standing[at / BOX]
                    || east - west > WIDEST
                    || south - north > WIDEST
                    || Math.hypot(middleX - centerX, middleY - centerY) > RADIUS) {
                continue;
            }
            var foot = ground.apply(middleX, middleY);
            if (boxes[at + TOP] - (Float.isNaN(foot) ? under : foot) >= HEIGHT) {
                System.arraycopy(boxes, at, things, count, BOX);
                count += BOX;
            }
        }
        return Arrays.copyOf(things, count);
    }

    private boolean[] findStanding(BinaryOperator<Float> ground, float[] boxes, float under) {
        var count = boxes.length / BOX;
        var order = new long[count];
        var near = 0;
        for (var box = 0; box < count; box++) {
            var at = box * BOX;
            var nearX = Math.max(0, Math.max(boxes[at] - centerX, centerX - boxes[at + 2]));
            var nearY = Math.max(0, Math.max(boxes[at + 1] - centerY, centerY - boxes[at + 3]));
            if (Math.hypot(nearX, nearY) <= RADIUS + WIDEST) {
                var bits = Float.floatToIntBits(boxes[at + BOTTOM]);
                bits ^= (bits >> 31) & 0x7fffffff;
                order[near++] = (long) bits << 32 | box;
            }
        }
        Arrays.sort(order, 0, near);
        var standing = new boolean[count];
        var holders = new int[near];
        var holderCount = 0;
        for (var index = 0; index < near; index++) {
            var box = (int) order[index];
            var at = box * BOX;
            var middleX = (boxes[at] + boxes[at + 2]) * .5f;
            var middleY = (boxes[at + 1] + boxes[at + 3]) * .5f;
            var foot = ground.apply(middleX, middleY);
            var stands = boxes[at + BOTTOM] - (Float.isNaN(foot) ? under : foot) <= GROUNDED;
            for (var holder = 0; !stands && holder < holderCount; holder++) {
                var below = holders[holder] * BOX;
                stands = boxes[below + TOP] >= boxes[at + BOTTOM] - RESTING
                        && middleX >= boxes[below]
                        && middleX <= boxes[below + 2]
                        && middleY >= boxes[below + 1]
                        && middleY <= boxes[below + 3];
            }
            standing[box] = stands;
            if (stands && boxes[at + 2] - boxes[at] <= WIDEST && boxes[at + 3] - boxes[at + 1] <= WIDEST) {
                holders[holderCount++] = box;
            }
        }
        return standing;
    }

    private float[] findTall(Framing framing, float[] things, Picture picture) {
        var tall = new float[things.length];
        var count = 0;
        var sights = computeSights(framing);
        for (var at = 0; at + BOX - 1 < things.length; at += BOX) {
            if (!fits(sights, things, at, picture, 0)) {
                System.arraycopy(things, at, tall, count, BOX);
                count += BOX;
            }
        }
        return Arrays.copyOf(tall, count);
    }

    private boolean fits(Framing framing, float[] things, Picture picture) {
        var sights = computeSights(framing);
        for (var at = 0; at + BOX - 1 < things.length; at += BOX) {
            if (!fits(sights, things, at, picture, picture.topRoom())) {
                return false;
            }
        }
        return true;
    }

    private Sight[] computeSights(Framing framing) {
        var sights = new Sight[SIDES];
        for (var side = 0; side < SIDES; side++) {
            var angle = side / (double) SIDES * 2 * Math.PI;
            var eyeX = centerX + Math.cos(angle) * framing.radius();
            var eyeY = centerY + Math.sin(angle) * framing.radius();
            double forwardX = framing.lookX() - eyeX;
            double forwardY = framing.lookY() - eyeY;
            double forwardZ = framing.lookZ() - framing.eyeZ();
            var length = Math.sqrt(forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ);
            forwardX /= length;
            forwardY /= length;
            forwardZ /= length;
            var level = Math.hypot(forwardX, forwardY);
            sights[side] = level < 1e-3
                    ? new Sight(eyeX, eyeY, framing.eyeZ(), 0, 0, 0, 0, 0, 0)
                    : new Sight(
                            eyeX,
                            eyeY,
                            framing.eyeZ(),
                            forwardX,
                            forwardY,
                            forwardZ,
                            -forwardZ * forwardX / level,
                            -forwardZ * forwardY / level,
                            level);
        }
        return sights;
    }

    private boolean passes(float west, float north, float east, float south, float radius) {
        var nearX = Math.max(0, Math.max(west - centerX, centerX - east));
        var nearY = Math.max(0, Math.max(north - centerY, centerY - south));
        var farX = Math.max(Math.abs(west - centerX), Math.abs(east - centerX));
        var farY = Math.max(Math.abs(north - centerY), Math.abs(south - centerY));
        return Math.hypot(nearX, nearY) <= radius + BESIDE && Math.hypot(farX, farY) >= radius - BESIDE;
    }

    private static float[] findMiddle(float[] tall, float under) {
        var highest = -Float.MAX_VALUE;
        for (var at = 0; at + BOX - 1 < tall.length; at += BOX) {
            highest = Math.max(highest, tall[at + TOP]);
        }
        var half = Math.min(highest, under + (highest - under) * .5f);
        float west = Float.MAX_VALUE, north = Float.MAX_VALUE, east = -Float.MAX_VALUE, south = -Float.MAX_VALUE;
        for (var at = 0; at + BOX - 1 < tall.length; at += BOX) {
            if (tall[at + TOP] >= half) {
                west = Math.min(west, tall[at]);
                north = Math.min(north, tall[at + 1]);
                east = Math.max(east, tall[at + 2]);
                south = Math.max(south, tall[at + 3]);
            }
        }
        return new float[] {(west + east) * .5f, (north + south) * .5f};
    }

    private static boolean fits(Sight[] sights, float[] things, int at, Picture picture, float room) {
        var tangent = Math.tan(Math.toRadians(picture.fieldOfView()) * .5);
        var highest = 1 - 2 * room / picture.height();
        var middleX = (things[at] + things[at + 2]) * .5f;
        var middleY = (things[at + 1] + things[at + 3]) * .5f;
        for (var sight : sights) {
            if (sight.computeRise(middleX, middleY, things[at + TOP], tangent) > highest) {
                return false;
            }
        }
        return true;
    }

    private static Framing climb(Framing framing, float rise) {
        return new Framing(
                framing.radius(), framing.eyeZ() + rise, framing.lookX(), framing.lookY(), framing.lookZ() + rise);
    }

    private static float[] mix(float[] night, float[] day, float share) {
        return new float[] {
            night[0] + (day[0] - night[0]) * share,
            night[1] + (day[1] - night[1]) * share,
            night[2] + (day[2] - night[2]) * share,
            0
        };
    }
}

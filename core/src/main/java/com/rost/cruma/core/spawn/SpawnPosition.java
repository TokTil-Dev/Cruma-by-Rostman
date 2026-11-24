package com.rost.cruma.core.spawn;

import java.util.List;

public sealed interface SpawnPosition {
    Anywhere ANYWHERE = new Anywhere();

    record Anywhere() implements SpawnPosition {}

    record Fixed(int x, int y, int z, int heading) implements SpawnPosition {}

    record Weighted(List<Point> points) implements SpawnPosition {
        public Weighted {
            points = List.copyOf(points);
        }
    }

    record Point(int x, int y, int z, int heading, int percent) {}
}

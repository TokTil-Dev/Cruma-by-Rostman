package com.rost.cruma.core.world;

import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Item;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class ShotSpans {
    private ShotSpans() {}

    public record Span(int item, int target, double start, double turnEnd, double holdEnd, boolean flies) {}

    public static List<Span> of(List<Item> items, double length) {
        var spans = new ArrayList<Span>();
        var standing = new HashSet<Integer>();
        var held = -1;
        var heldUntil = Double.NEGATIVE_INFINITY;
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            for (var order : item.actors()) {
                if (order.action().enters() && order.stands()) {
                    standing.add(order.index());
                } else if (order.action() == Action.DESPAWN || order.action() == Action.TELEPORT_BACK) {
                    standing.remove(order.index());
                }
            }
            var shot = item.camera();
            if (shot == null || !shot.specialView() || shot.blank() || !standing.contains(shot.target())) {
                continue;
            }
            var start = item.seconds();
            if (!spans.isEmpty()) {
                var last = spans.getLast();
                spans.set(
                        spans.size() - 1,
                        new Span(
                                last.item,
                                last.target,
                                last.start,
                                Math.min(last.turnEnd, start),
                                Math.min(last.holdEnd, start),
                                last.flies));
            }
            var same = held == shot.target() && heldUntil > start;
            var rel = shot.relYaw() != 0 || shot.relPitch() != 0 || shot.relRoll() != 0;
            var holdEnd = Math.min(length, start + shot.holdSeconds());
            spans.add(new Span(
                    index,
                    shot.target(),
                    start,
                    same || rel ? Math.min(holdEnd, start + shot.moveSeconds()) : start,
                    holdEnd,
                    !same && !shot.immediate() && !spans.isEmpty()));
            held = shot.target();
            heldUntil = start + shot.holdSeconds();
        }
        return spans;
    }
}

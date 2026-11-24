package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.CameraPath.Key;
import java.util.ArrayList;
import java.util.List;

final class PathShots {
    private static final String SPAN = "shot/";

    private PathShots() {}

    record Shot(int number, int first, int last, double start, double end) {
        int keys() {
            return last - first + 1;
        }

        boolean holds(int index) {
            return index >= first && index <= last;
        }
    }

    static List<Shot> of(List<Key> keys) {
        var shots = new ArrayList<Shot>();
        var first = 0;
        for (var index = 1; index <= keys.size(); index++) {
            if (index == keys.size()
                    || keys.get(index).seconds() == keys.get(index - 1).seconds()) {
                shots.add(new Shot(
                        shots.size() + 1,
                        first,
                        index - 1,
                        keys.get(first).seconds(),
                        keys.get(index - 1).seconds()));
                first = index;
            }
        }
        return shots;
    }

    static int at(List<Shot> shots, double seconds) {
        var number = shots.isEmpty() ? 0 : 1;
        for (var shot : shots) {
            if (shot.start() <= seconds) {
                number = shot.number();
            }
        }
        return number;
    }

    static String spanId(int number) {
        return SPAN + number;
    }

    static int numberOf(String spanId) {
        return spanId.startsWith(SPAN) ? Integer.parseInt(spanId.substring(SPAN.length())) : 0;
    }
}

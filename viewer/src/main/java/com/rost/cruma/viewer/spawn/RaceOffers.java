package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.export.NpcExport.Race;
import com.rost.cruma.core.spawn.ArmorGrpTable;
import com.rost.cruma.core.world.FigureBodies;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

public final class RaceOffers {
    private RaceOffers() {}

    public static List<WorldVariant> findWorlds(Race race, Path client, boolean hasFace) {
        var worlds = new ArrayList<WorldVariant>();
        for (var world : WorldVariant.findWorlds(client)) {
            var created = PlayerFigures.readCreated(client, world);
            Predicate<String> looks = figure -> hasLooks(client, world, figure);
            var bodies = FigureBodies.read(client, world);
            if (PlayerFigures.isOffered(race.figure(), hasFace, looks, bodies::hasRow, created::contains)) {
                worlds.add(world);
            }
        }
        return List.copyOf(worlds);
    }

    public static String describeMissing(Race race, WorldVariant world, List<WorldVariant> worlds) {
        var missing = "The " + world.getLabel() + " world has no " + race.label() + ".";
        if (worlds.isEmpty()) {
            return missing + " No world of this client has it.";
        }
        var names = new ArrayList<String>();
        for (var other : worlds) {
            names.add(other.getLabel());
        }
        var listed = names.size() == 1
                ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
        return missing + " The " + listed + (names.size() == 1 ? " world has" : " worlds have") + " it.";
    }

    public static @Nullable String findMissing(Race race, Path client, WorldVariant world) {
        var figure = race.figure();
        if (PlayerFigures.findClassFigure(figure) == null && !PlayerFigures.isEssenceFigure(figure)) {
            return null;
        }
        var created = PlayerFigures.readCreated(client, world);
        var bodies = FigureBodies.read(client, world);
        if (PlayerFigures.isOffered(
                figure, true, looks -> hasLooks(client, world, looks), bodies::hasRow, created::contains)) {
            return null;
        }
        return describeMissing(race, world, findWorlds(race, client, true));
    }

    private static boolean hasLooks(Path client, WorldVariant world, String figure) {
        try {
            return ArmorGrpTable.read(client.resolve("system"), world).hasLooks(figure);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}

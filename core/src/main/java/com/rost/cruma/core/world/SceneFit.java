package com.rost.cruma.core.world;

import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Model;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class SceneFit {
    private SceneFit() {}

    public record Lost(String what, String many, int count) {
        public String text() {
            return count + " " + (count == 1 ? what : many);
        }
    }

    public static boolean holdsVoices(@Nullable Model model) {
        return model == null || model.music().contains("IsVoice");
    }

    public static boolean holdsSlides(@Nullable Model model) {
        return model == null || model.screen().contains("SlideTexName");
    }

    public static boolean holdsPostEffects(@Nullable Model model) {
        return model == null || model.screen().contains("PostEffectID");
    }

    public static List<Lost> lost(List<Item> items, @Nullable Model model) {
        var lost = new LinkedHashMap<String, Lost>();
        if (model == null) {
            return List.of();
        }
        for (var item : items) {
            for (var actor : item.actors()) {
                if (actor.player() && model.pc().isEmpty()) {
                    count(lost, "order of a player character", "orders of player characters");
                }
            }
            if (item.music() != null) {
                music(item.music(), model, lost);
            }
            if (item.screen() != null) {
                screen(item.screen(), model, lost);
            }
        }
        return new ArrayList<>(lost.values());
    }

    private static void music(Music cue, Model model, LinkedHashMap<String, Lost> lost) {
        if (cue.voice() && !holdsVoices(model)) {
            count(lost, "voice line", "voice lines");
        }
        if (cue.stopAmbient() && !model.music().contains("StopMapAmbient")) {
            count(lost, "stop of the map's ambient sounds", "stops of the map's ambient sounds");
        }
        if (cue.stopEffects() && !model.music().contains("StopMapSoundFx")) {
            count(lost, "stop of the sounds of the map's effects", "stops of the sounds of the map's effects");
        }
        if (Music.volumeOf(cue.level()) < 1 && !model.music().contains("Volume")) {
            count(lost, "volume", "volumes");
        }
    }

    private static void screen(Screen screen, Model model, LinkedHashMap<String, Lost> lost) {
        var kind = screen.kind();
        if ((kind == ScreenKind.SLIDE_START
                        || kind == ScreenKind.SLIDE_CHANGE
                        || kind == ScreenKind.SLIDE_MOVE
                        || kind == ScreenKind.SLIDE_END)
                && !holdsSlides(model)) {
            count(lost, "slide", "slides");
        }
        if (kind == ScreenKind.POST_EFFECT && !holdsPostEffects(model)) {
            count(lost, "post effect", "post effects");
        }
        if (screen.delayed() != 0 && !model.screen().contains("DelayedType")) {
            count(lost, "fade that waits", "fades that wait");
        }
    }

    private static void count(LinkedHashMap<String, Lost> lost, String what, String many) {
        var known = lost.get(what);
        lost.put(what, new Lost(what, many, known == null ? 1 : known.count() + 1));
    }
}

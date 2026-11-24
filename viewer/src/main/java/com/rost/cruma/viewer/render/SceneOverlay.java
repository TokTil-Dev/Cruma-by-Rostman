package com.rost.cruma.viewer.render;

import com.rost.cruma.viewer.render.GameScenePlayer.Message;
import com.rost.cruma.viewer.render.GameSlides.Shown;
import java.util.List;

public record SceneOverlay(
        float fade, int fadeColor, boolean wide, boolean slidesShow, List<Shown> slides, List<Message> messages) {
    public SceneOverlay {
        slides = List.copyOf(slides);
        messages = List.copyOf(messages);
    }
}

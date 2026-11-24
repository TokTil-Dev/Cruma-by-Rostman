package com.rost.cruma.viewer.ui.aside;

import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.StartPlaces;
import com.rost.cruma.viewer.ui.aside.MenuMusic.Phase;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

final class MapFollower {
    static final long REST_NANOS = 400_000_000L;

    private @Nullable String restingTile;
    private long restingSince;
    private @Nullable String followedTile;
    private @Nullable String followedChoice;

    void reset() {
        restingTile = null;
        followedTile = null;
    }

    void follow(
            MenuMusic music,
            ClientInstallation client,
            @Nullable String pointed,
            @Nullable String choice,
            StartPlaces places,
            long now) {
        if (!music.isFollowing() || music.getPhase() == Phase.PAUSED) {
            return;
        }
        String wanted;
        if (pointed != null) {
            wanted = pointed;
        } else if (followedTile == null || !Objects.equals(choice, followedChoice)) {
            wanted = choice;
        } else {
            return;
        }
        followedChoice = choice;
        if (wanted == null || wanted.equals(followedTile)) {
            return;
        }
        if (!wanted.equals(restingTile)) {
            restingTile = wanted;
            restingSince = now;
        }
        if (now - restingSince < REST_NANOS) {
            return;
        }
        var theme = music.getTheme(client, wanted, places.findPlacesIn(wanted));
        if (theme == null) {
            return;
        }
        followedTile = wanted;
        var track = theme.track();
        if (track == null) {
            return;
        }
        var current = music.getCurrent();
        var same = current != null && current.file().equals(track.file()) && music.isPlaying();
        if (!same) {
            music.play(track, theme);
        }
    }
}

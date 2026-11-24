package com.rost.cruma.viewer.ui.aside;

import java.util.List;

public final class StartAsides {
    private StartAsides() {}

    public static List<StartAside> create() {
        return List.of(new PlaceAside(), new FlightAside(), new ClientAside(), new RadioAside());
    }
}

package com.rost.cruma.viewer.ui;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public interface InspectedView {
    record Header(String title, String identity, String type) {}

    static InspectedView firstOf(InspectedView... views) {
        var all = List.of(views);
        return new InspectedView() {
            @Override
            public @Nullable Header getInspectedHeader() {
                for (var view : all) {
                    var header = view.getInspectedHeader();
                    if (header != null) {
                        return header;
                    }
                }
                return null;
            }

            @Override
            public void drawInspectedDetails() {
                for (var view : all) {
                    if (view.getInspectedHeader() != null) {
                        view.drawInspectedDetails();
                        return;
                    }
                }
            }
        };
    }

    @Nullable
    Header getInspectedHeader();

    void drawInspectedDetails();
}

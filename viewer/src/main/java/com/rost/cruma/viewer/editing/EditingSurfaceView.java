package com.rost.cruma.viewer.editing;

import com.rost.cruma.viewer.editing.EditingSession.LoadedSurfaces;
import com.rost.cruma.viewer.editing.EditingSession.SurfacePreview;
import com.rost.cruma.viewer.editing.EditingSession.SurfaceShow;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class EditingSurfaceView {
    private EditingSurfaceView() {}

    public static SurfaceShow show(
            ViewerRenderBackend backend, String map, SurfacePreview surfaces, LoadedSurfaces loaded) {
        if (!backend.updateEditorBspSurfaces(map, loaded.layout(), surfaces.written(), surfaces.collapsed())) {
            return SurfaceShow.NOTHING;
        }
        var overlay = surfaces.overlay();
        if (overlay == null) {
            return SurfaceShow.ALL;
        }
        try {
            backend.replaceEditorBspSurfaces(map, loaded.overlay() == null ? List.of() : loaded.overlay());
            return SurfaceShow.ALL;
        } catch (RuntimeException failure) {
            try {
                backend.replaceEditorBspSurfaces(map, List.of());
            } catch (RuntimeException ignored) {
            }
            var drawn = new TreeSet<>(surfaces.collapsed());
            drawn.removeAll(overlay);
            backend.updateEditorBspSurfaces(map, loaded.layout(), overlay, Set.copyOf(drawn));
            return SurfaceShow.WITHOUT_OVERLAY;
        }
    }
}

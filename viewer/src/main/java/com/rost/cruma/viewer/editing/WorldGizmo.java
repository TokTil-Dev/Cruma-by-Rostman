package com.rost.cruma.viewer.editing;

import com.rost.cruma.viewer.render.Camera;
import imgui.ImGui;
import imgui.extension.imguizmo.ImGuizmo;
import org.jetbrains.annotations.Nullable;

public final class WorldGizmo {
    private final float[] view = new float[16];
    private final float[] projection = new float[16];

    public static boolean isPointerInside(float left, float top, float right, float bottom) {
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    public void manipulate(
            Camera camera,
            float left,
            float top,
            float right,
            float bottom,
            boolean active,
            int operation,
            int mode,
            float[] matrix,
            float @Nullable [] snap) {
        var cameraView = camera.getViewMatrix();
        var cameraProjection = camera.getProjectionMatrix();
        for (var i = 0; i < 16; i++) {
            view[i] = cameraView.get(i);
            projection[i] = cameraProjection.get(i);
        }
        EditorTransforms.adaptCameraForGizmo(view, projection);
        ImGuizmo.setOrthographic(camera.isOrtho());
        var drawList = ImGui.getBackgroundDrawList();
        drawList.pushClipRect(left, top, right, bottom, true);
        ImGuizmo.setDrawList(drawList);
        var io = ImGui.getIO();
        ImGuizmo.setRect(0, 0, io.getDisplaySizeX(), io.getDisplaySizeY());
        ImGuizmo.enable(active);
        ImGuizmo.manipulate(view, projection, operation, mode, matrix, null, snap);
        drawList.popClipRect();
    }
}

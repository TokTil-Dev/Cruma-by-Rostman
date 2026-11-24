package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.spawn.SpawnTerritory;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiMouseCursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class SpawnPanelWidgets {
    static final float ROW_PAD = 8;

    private static final int CARD = GeodataWidgets.CARD;

    private SpawnPanelWidgets() {}

    record Share(SpawnStyle style, long count) {}

    record MapPoint(double x, double y, double z, int argb, boolean fixed, int slot, String label) {}

    record MapPick(MapPoint point, boolean twice) {}

    record Part(int argb, int count) {}

    static @Nullable SpawnStyle composition(String id, List<Share> shares, Set<SpawnStyle> hidden) {
        return composition(id, shares, hidden, "in the world");
    }

    static @Nullable SpawnStyle composition(String id, List<Share> shares, Set<SpawnStyle> hidden, String place) {
        var slices = new ArrayList<Slice<SpawnStyle>>(shares.size());
        for (var share : shares) {
            var style = share.style();
            slices.add(
                    new Slice<>(style, style.label, style.label.toLowerCase(Locale.ROOT), style.argb, share.count()));
        }
        return compositionOf(id, slices, hidden, place);
    }

    record Slice<T>(T key, String label, String phrase, int argb, long count) {}

    static <T> @Nullable T compositionOf(String id, List<Slice<T>> slices, Set<T> hidden, String place) {
        var draw = ImGui.getWindowDrawList();
        var width = ImGui.getContentRegionAvailX();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var total = slices.stream().mapToLong(Slice::count).sum();
        var barHeight = 8f;
        draw.addRectFilled(x, y, x + width, y + barHeight, CARD, 4f);
        if (total > 0) {
            var left = x;
            for (var i = 0; i < slices.size(); i++) {
                var slice = slices.get(i);
                var right = i == slices.size() - 1
                        ? x + width
                        : Math.min(x + width, left + Math.max(3f, (float) (width * slice.count() / total)));
                var flags = slices.size() == 1
                        ? ImDrawFlags.RoundCornersAll
                        : i == 0
                                ? ImDrawFlags.RoundCornersLeft
                                : i == slices.size() - 1 ? ImDrawFlags.RoundCornersRight : ImDrawFlags.RoundCornersNone;
                draw.addRectFilled(
                        left,
                        y,
                        Math.max(left, right - (i == slices.size() - 1 ? 0 : 1)),
                        y + barHeight,
                        SpawnStyle.getImguiColor(slice.argb(), hidden.contains(slice.key()) ? .22f : 1f, 1f),
                        4,
                        flags);
                left = right;
            }
        }
        var lineX = x;
        var lineY = y + barHeight + 8;
        var chipHeight = 22f;
        T clicked = null;
        for (var slice : slices) {
            var text = slice.label() + "  " + compact(slice.count());
            var chipWidth = 22 + ImGui.calcTextSizeX(text) + 8;
            if (lineX > x && lineX + chipWidth > x + width) {
                lineX = x;
                lineY += chipHeight + 4;
            }
            ImGui.setCursorScreenPos(lineX, lineY);
            if (ImGui.invisibleButton(id + "/" + slice.key(), chipWidth, chipHeight)) {
                clicked = slice.key();
            }
            var off = hidden.contains(slice.key());
            var hovered = ImGui.isItemHovered();
            draw.addRectFilled(
                    lineX,
                    lineY,
                    lineX + chipWidth,
                    lineY + chipHeight,
                    hovered ? ImColor.rgba(34, 51, 63, 255) : CARD,
                    11f);
            var textTop = lineY + (chipHeight - ImGui.getFontSize()) * .5f;
            draw.addCircleFilled(
                    lineX + 11,
                    HudWidgets.textMiddle(textTop),
                    4,
                    SpawnStyle.getImguiColor(slice.argb(), off ? .3f : 1f, 1f),
                    12);
            if (off) {
                draw.addCircle(
                        lineX + 11,
                        HudWidgets.textMiddle(textTop),
                        4.5f,
                        SpawnStyle.getImguiColor(slice.argb(), .7f, 1f),
                        12,
                        1);
            }
            draw.addText(lineX + 20, textTop, off ? GeodataWidgets.FAINT : GeodataWidgets.MUTED, text);
            if (hovered) {
                HudWidgets.showTooltip((off ? "Show " : "Hide ") + slice.phrase() + " " + place + ".");
            }
            lineX += chipWidth + 4;
        }
        ImGui.setCursorScreenPos(x, lineY + chipHeight);
        ImGui.dummy(width, 0);
        return clicked;
    }

    static @Nullable MapPick zoneMap(
            String id,
            List<SpawnTerritory> zones,
            List<SpawnTerritory> banned,
            int argb,
            List<MapPoint> points,
            int selectedSlot,
            float height,
            double cameraX,
            double cameraY,
            double cameraYaw) {
        return zoneMap(id, zones, banned, argb, points, selectedSlot, height, cameraX, cameraY, cameraYaw, List.of());
    }

    static @Nullable MapPick zoneMap(
            String id,
            List<SpawnTerritory> zones,
            List<SpawnTerritory> banned,
            int argb,
            List<MapPoint> points,
            int selectedSlot,
            float height,
            double cameraX,
            double cameraY,
            double cameraYaw,
            List<GeoPoint> coverage) {
        var draw = ImGui.getWindowDrawList();
        var width = ImGui.getContentRegionAvailX();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton(id, width, height);
        var hovered = ImGui.isItemHovered();
        var clicked = ImGui.isItemClicked(ImGuiMouseButton.Left);
        var twice = hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left);
        draw.addRectFilled(x, y, x + width, y + height, ImColor.rgba(15, 22, 29, 255), 8f);
        draw.addRect(x, y, x + width, y + height, GeodataWidgets.DIVIDER, 8, 0, 1);
        var minX = Double.MAX_VALUE;
        var minY = Double.MAX_VALUE;
        var maxX = -Double.MAX_VALUE;
        var maxY = -Double.MAX_VALUE;
        for (var zone : zones) {
            minX = Math.min(minX, zone.minX());
            minY = Math.min(minY, zone.minY());
            maxX = Math.max(maxX, zone.maxX());
            maxY = Math.max(maxY, zone.maxY());
        }
        for (var point : points) {
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
        }
        if (minX > maxX) {
            ImGui.setCursorScreenPos(x + 12, y + height * .5f - ImGui.getFontSize() * .5f);
            ImGui.textColored(GeodataWidgets.FAINT, "No zone or position to show.");
            ImGui.setCursorScreenPos(x, y + height);
            ImGui.dummy(width, 0);
            return null;
        }
        var spanX = Math.max(600, maxX - minX);
        var spanY = Math.max(600, maxY - minY);
        var centerX = (minX + maxX) * .5;
        var centerY = (minY + maxY) * .5;
        var pad = 18f;
        var scale = Math.min((width - pad * 2) / spanX, (height - pad * 2) / spanY);
        var originX = x + width * .5f;
        var originY = y + height * .5f;
        draw.pushClipRect(x, y, x + width, y + height, true);
        var step = niceStep(width / scale / 4);
        var gridColor = ImColor.rgba(255, 255, 255, 11);
        for (var gx = Math.floor((centerX - width / scale) / step) * step; gx < centerX + width / scale; gx += step) {
            var sx = (float) (originX + (gx - centerX) * scale);
            draw.addLine(sx, y, sx, y + height, gridColor, 1);
        }
        for (var gy = Math.floor((centerY - height / scale) / step) * step; gy < centerY + height / scale; gy += step) {
            var sy = (float) (originY + (gy - centerY) * scale);
            draw.addLine(x, sy, x + width, sy, gridColor, 1);
        }
        for (var zone : banned) {
            drawPolygon(
                    draw,
                    zone,
                    centerX,
                    centerY,
                    scale,
                    originX,
                    originY,
                    ImColor.rgba(255, 97, 87, 26),
                    ImColor.rgba(255, 97, 87, 178));
        }
        for (var zone : zones) {
            drawPolygon(
                    draw,
                    zone,
                    centerX,
                    centerY,
                    scale,
                    originX,
                    originY,
                    SpawnStyle.getImguiColor(argb, .16f, 1f),
                    SpawnStyle.getImguiColor(argb, .95f, 1.1f));
        }
        var coverageColor = ImColor.rgba(255, 236, 160, coverage.size() > 1500 ? 90 : 150);
        for (var point : coverage) {
            var sx = (float) (originX + (point.x() - centerX) * scale);
            var sy = (float) (originY + (point.y() - centerY) * scale);
            draw.addRectFilled(sx - .8f, sy - .8f, sx + .8f, sy + .8f, coverageColor);
        }
        var hoveredPoint = -1;
        var nearest = 9f * 9f;
        var mouseX = ImGui.getMousePosX();
        var mouseY = ImGui.getMousePosY();
        for (var i = 0; i < points.size(); i++) {
            var point = points.get(i);
            var sx = (float) (originX + (point.x() - centerX) * scale);
            var sy = (float) (originY + (point.y() - centerY) * scale);
            if (point.fixed()) {
                draw.addCircleFilled(sx, sy, 5, ImColor.rgba(15, 23, 28, 255), 16);
                draw.addCircleFilled(sx, sy, 3.6f, SpawnStyle.getImguiColor(point.argb(), 1f, 1.1f), 16);
            } else {
                draw.addCircleFilled(sx, sy, 2.6f, SpawnStyle.getImguiColor(point.argb(), .9f, 1.1f), 10);
            }
            if (point.slot() == selectedSlot) {
                draw.addCircle(sx, sy, 7.5f, HudWidgets.ACCENT, 20, 1.6f);
            }
            var distance = (mouseX - sx) * (mouseX - sx) + (mouseY - sy) * (mouseY - sy);
            if (hovered && distance < nearest) {
                nearest = distance;
                hoveredPoint = i;
            }
        }
        var camX = (float) (originX + (cameraX - centerX) * scale);
        var camY = (float) (originY + (cameraY - centerY) * scale);
        if (camX > x + 4 && camX < x + width - 4 && camY > y + 4 && camY < y + height - 4) {
            var yaw = Math.toRadians(cameraYaw);
            var tipX = camX + (float) Math.cos(yaw) * 11;
            var tipY = camY + (float) Math.sin(yaw) * 11;
            draw.addLine(camX, camY, tipX, tipY, HudWidgets.ACCENT, 1.5f);
            draw.addCircleFilled(camX, camY, 3.5f, HudWidgets.ACCENT, 12);
        }
        var northX = x + width - 16;
        var northY = y + 10;
        draw.addTriangleFilled(northX, northY, northX - 4, northY + 7, northX + 4, northY + 7, GeodataWidgets.MUTED);
        draw.addText(northX - ImGui.calcTextSizeX("N") * .5f, northY + 8, GeodataWidgets.FAINT, "N");
        var barLength = (float) (step * scale);
        var barX = x + 10;
        var barY = y + height - 10;
        draw.addLine(barX, barY, barX + barLength, barY, GeodataWidgets.MUTED, 1.5f);
        draw.addLine(barX, barY - 3, barX, barY + 1, GeodataWidgets.MUTED, 1.5f);
        draw.addLine(barX + barLength, barY - 3, barX + barLength, barY + 1, GeodataWidgets.MUTED, 1.5f);
        draw.addText(
                barX + barLength + 6, barY - ImGui.getFontSize() * .5f - 1, GeodataWidgets.FAINT, compact((long) step));
        draw.popClipRect();
        if (hoveredPoint >= 0) {
            var point = points.get(hoveredPoint);
            HudWidgets.showTooltip(point.label()
                    + (point.fixed() ? " · fixed point" : " · one possible spawn")
                    + "\nDouble-click to fly the camera there.");
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }
        return clicked && hoveredPoint >= 0 ? new MapPick(points.get(hoveredPoint), twice) : null;
    }

    static double niceStep(double length) {
        var power = Math.pow(10, Math.floor(Math.log10(Math.max(1, length))));
        for (var factor : new int[] {1, 2, 5, 10}) {
            if (factor * power >= length) {
                return factor * power;
            }
        }
        return 10 * power;
    }

    static void fill(List<Part> parts, int maximum) {
        var draw = ImGui.getWindowDrawList();
        var width = ImGui.getContentRegionAvailX();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var total = parts.stream().mapToInt(Part::count).sum();
        var capacity = Math.max(1, total);
        var npcs = total == 1 ? "1 NPC" : total + " NPCs";
        var label = maximum < 0
                ? npcs + " · No cap"
                : total > maximum ? npcs + " · Only " + maximum + " spawn" : npcs + " · Cap " + maximum;
        draw.addText(x, y, total > maximum && maximum >= 0 ? GeodataWidgets.WARNING : HudWidgets.TEXT, label);
        var barY = y + ImGui.getFontSize() + 6;
        var barHeight = 6f;
        draw.addRectFilled(x, barY, x + width, barY + barHeight, CARD, 3f);
        var left = x;
        for (var part : parts) {
            var right = left + (float) (width * part.count() / (double) capacity);
            draw.addRectFilled(
                    left,
                    barY,
                    Math.max(left, right - 1),
                    barY + barHeight,
                    SpawnStyle.getImguiColor(part.argb(), 1f, 1f),
                    3f);
            left = right;
        }
        if (maximum >= 0 && maximum < capacity) {
            var limit = x + (float) (width * maximum / (double) capacity);
            draw.addRectFilled(limit, barY, x + width, barY + barHeight, ImColor.rgba(255, 199, 89, 89), 3f);
            draw.addLine(limit, barY - 3, limit, barY + barHeight + 3, GeodataWidgets.WARNING, 1.5f);
        }
        ImGui.dummy(width, ImGui.getFontSize() + 6 + barHeight);
    }

    static float badgeWidth(String text) {
        return Math.max(30, ImGui.calcTextSizeX(text) + 12);
    }

    static void badge(ImDrawList draw, float x, float y, float width, int argb, String text) {
        var height = ImGui.getFontSize() + 6;
        draw.addRectFilled(x, y, x + width, y + height, SpawnStyle.getImguiColor(argb, .18f, 1f), 6f);
        draw.addText(
                x + (width - ImGui.calcTextSizeX(text)) * .5f, y + 3, SpawnStyle.getImguiColor(argb, 1f, 1.15f), text);
    }

    static void checkBox(ImDrawList draw, float x, float y, boolean checked) {
        x = Math.round(x);
        y = Math.round(y);
        var box = 16f;
        draw.addRectFilled(x, y, x + box, y + box, checked ? GeodataWidgets.ACCENT : ImColor.rgb(20, 28, 36), 4f);
        draw.addRect(x, y, x + box, y + box, checked ? GeodataWidgets.ACCENT : HudWidgets.BORDER, 4, 0, 1);
        if (checked) {
            draw.pathLineTo(x + 4, y + 8.5f);
            draw.pathLineTo(x + 7, y + 11.5f);
            draw.pathLineTo(x + 12.5f, y + 5);
            draw.pathStroke(ImColor.rgb(16, 24, 30), 0, 2.2f);
        }
    }

    static void listRow(
            ImDrawList draw,
            float x,
            float y,
            float width,
            int argb,
            String title,
            String detail,
            String value,
            String query) {
        listRow(draw, x, y, width, argb, title, detail, value, query, false);
    }

    static void listRow(
            ImDrawList draw,
            float x,
            float y,
            float width,
            int argb,
            String title,
            String detail,
            String value,
            String query,
            boolean middle) {
        draw.addCircleFilled(
                x + ROW_PAD + 4, HudWidgets.textMiddle(y + 4), 4.5f, SpawnStyle.getImguiColor(argb, 1f, 1f), 14);
        rowText(draw, x + ROW_PAD + 14, x + width - ROW_PAD, y, title, detail, value, query, middle);
    }

    static void statusRow(
            ImDrawList draw,
            float x,
            float y,
            float width,
            int dot,
            String title,
            String detail,
            int detailColor,
            String value) {
        draw.addCircleFilled(x + ROW_PAD + 4, HudWidgets.textMiddle(y + 4), 4.5f, dot, 14);
        rowText(draw, x + listRowTextLeft(), x + width - ROW_PAD, y, title, detail, value, "", false, detailColor);
    }

    static float listRowTextLeft() {
        return ROW_PAD + 14;
    }

    static boolean groupRow(
            String id, boolean open, String title, String detail, String value, String query, float height) {
        return groupRow(id, open, title, detail, value, query, height, ImGui.getContentRegionAvailX());
    }

    static boolean groupRow(
            String id,
            boolean open,
            String title,
            String detail,
            String value,
            String query,
            float height,
            float width) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var clicked = ImGui.invisibleButton(id, width, height);
        var hovered = ImGui.isItemHovered();
        draw.addRectFilled(x, y, x + width, y + height, hovered ? GeodataWidgets.HOVER : CARD, 6f);
        HudWidgets.glyph(
                draw,
                open ? Glyph.CHEVRON : Glyph.CHEVRON_RIGHT,
                x + 4,
                Math.round(y + (height - 16) * .5f),
                16,
                hovered ? HudWidgets.TEXT : GeodataWidgets.MUTED);
        rowText(draw, x + 26, x + width - ROW_PAD, y, title, detail, value, query, false);
        return clicked;
    }

    static boolean sectionRow(String id, boolean open, String title, String count) {
        return sectionRow(id, open, title, count, null);
    }

    static boolean sectionRow(String id, boolean open, String title, String count, @Nullable String article) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = sectionRowHeight();
        if (article != null) {
            ImGui.setNextItemAllowOverlap();
        }
        var clicked = ImGui.invisibleButton(id, width, height);
        var hovered = ImGui.isItemHovered();
        if (hovered) {
            draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.HOVER, 6f);
        }
        var color = hovered ? HudWidgets.TEXT : GeodataWidgets.MUTED;
        HudWidgets.glyph(
                draw,
                open ? Glyph.CHEVRON : Glyph.CHEVRON_RIGHT,
                x + 4,
                Math.round(y + (height - 16) * .5f),
                16,
                color);
        var textTop = y + (height - ImGui.getFontSize()) * .5f - 1;
        var countWidth = ImGui.calcTextSizeX(count);
        draw.addText(x + width - ROW_PAD - countWidth, textTop, GeodataWidgets.FAINT, count);
        var shown =
                HudWidgets.truncateToWidth(title, width - 26 - ROW_PAD - countWidth - 10 - (article == null ? 0 : 18));
        draw.addText(x + 26, textTop, color, shown);
        if (article != null) {
            ImGui.setCursorScreenPos(x + 26 + ImGui.calcTextSizeX(shown) + 4, textTop);
            HudWidgets.helpMark(article);
            ImGui.setCursorScreenPos(x, y + height);
            ImGui.dummy(0, 0);
        }
        return clicked;
    }

    static float sectionRowHeight() {
        return ImGui.getFontSize() + 10;
    }

    static void iconListRow(
            ImDrawList draw,
            float x,
            float y,
            float width,
            long texture,
            Glyph fallback,
            String title,
            String detail,
            String value,
            String query) {
        var size = listRowHeight() - 8;
        NpcPanelWidgets.icon(draw, x + ROW_PAD - 2, y + 3, size, texture, fallback);
        var left = x + ROW_PAD + size + 6;
        var right = x + width - ROW_PAD;
        var line = ImGui.getFontSize();
        var valueWidth = value.isEmpty() ? 0 : ImGui.calcTextSizeX(value);
        if (!value.isEmpty()) {
            draw.addText(right - valueWidth, y + 4, GeodataWidgets.FAINT, value);
        }
        var titleRoom = right - left - (value.isEmpty() ? 0 : valueWidth + 10);
        drawHighlighted(draw, left, y + 4, HudWidgets.truncateToWidth(title, titleRoom), query, HudWidgets.TEXT);
        drawHighlighted(
                draw,
                left,
                y + line + 6,
                HudWidgets.truncateToWidth(detail, right - left),
                query,
                GeodataWidgets.MUTED);
    }

    static float listRowHeight() {
        return ImGui.getFontSize() * 2 + 10;
    }

    static void drawHighlighted(ImDrawList draw, float x, float y, String text, String query, int color) {
        var at = query.isEmpty() ? -1 : text.toLowerCase(Locale.ROOT).indexOf(query);
        if (at < 0) {
            draw.addText(x, y, color, text);
            return;
        }
        var before = text.substring(0, at);
        var match = text.substring(at, at + query.length());
        draw.addText(x, y, color, before);
        var matchX = x + ImGui.calcTextSizeX(before);
        var matchWidth = ImGui.calcTextSizeX(match);
        draw.addRectFilled(
                matchX - 1,
                y,
                matchX + matchWidth + 1,
                y + ImGui.getFontSize() + 1,
                SpawnStyle.getImguiColor(0xFF5CDBF1, .16f, 1f),
                3f);
        draw.addText(matchX, y, HudWidgets.ACCENT, match);
        draw.addText(matchX + matchWidth, y, color, text.substring(at + query.length()));
    }

    static String formatRespawnTime(String raw) {
        if (raw.isEmpty()) {
            return "";
        }
        if (raw.equals("no")) {
            return "no respawn";
        }
        var matcher = Pattern.compile("(\\d+)(sec|min|hour|day)").matcher(raw);
        if (!matcher.matches()) {
            return raw;
        }
        var unit = switch (matcher.group(2)) {
            case "sec" -> "s";
            case "min" -> "min";
            case "hour" -> "h";
            default -> "d";
        };
        return matcher.group(1) + " " + unit;
    }

    static String compact(long value) {
        if (value < 10_000) {
            return String.format(Locale.ROOT, "%,d", value);
        }
        return value < 1_000_000 ? Math.round(value / 1000.0) + "k" : String.format(Locale.ROOT, "%.1fM", value / 1e6);
    }

    private static void drawPolygon(
            ImDrawList draw,
            SpawnTerritory zone,
            double centerX,
            double centerY,
            double scale,
            float originX,
            float originY,
            int fill,
            int line) {
        draw.pathClear();
        for (var point : zone.points()) {
            draw.pathLineTo((float) (originX + (point.x() - centerX) * scale), (float)
                    (originY + (point.y() - centerY) * scale));
        }
        draw.pathFillConcave(fill);
        for (var point : zone.points()) {
            draw.pathLineTo((float) (originX + (point.x() - centerX) * scale), (float)
                    (originY + (point.y() - centerY) * scale));
        }
        draw.pathStroke(line, ImDrawFlags.Closed, 1.6f);
    }

    private static void rowText(
            ImDrawList draw,
            float left,
            float right,
            float y,
            String title,
            String detail,
            String value,
            String query,
            boolean middle) {
        rowText(draw, left, right, y, title, detail, value, query, middle, GeodataWidgets.MUTED);
    }

    private static void rowText(
            ImDrawList draw,
            float left,
            float right,
            float y,
            String title,
            String detail,
            String value,
            String query,
            boolean middle,
            int detailColor) {
        var line = ImGui.getFontSize();
        var valueWidth = value.isEmpty() ? 0 : ImGui.calcTextSizeX(value);
        if (!value.isEmpty()) {
            draw.addText(right - valueWidth, middle ? y + line * .5f + 5 : y + 4, GeodataWidgets.FAINT, value);
        }
        var titleRoom = right - left - (value.isEmpty() ? 0 : valueWidth + 10);
        drawHighlighted(draw, left, y + 4, HudWidgets.truncateToWidth(title, titleRoom), query, HudWidgets.TEXT);
        drawHighlighted(
                draw,
                left,
                y + line + 6,
                HudWidgets.truncateToWidth(detail, middle ? titleRoom : right - left),
                query,
                detailColor);
    }
}

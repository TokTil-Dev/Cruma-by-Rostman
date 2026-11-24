package com.rost.cruma.core.world;

import com.rost.cruma.core.schema.TableData;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class HairRows {
    static final int NEWER_ROWS = 11;

    private static final int ROW_UNDER_ARMOR = 10;

    private final Map<String, Figure> figures;

    private HairRows(Map<String, Figure> figures) {
        this.figures = Map.copyOf(figures);
    }

    public record Hair(String mesh, String texture) {}

    record Style(String front, String frontTexture, String back, String backTexture) {
        boolean isEmpty() {
            return front.isEmpty() && back.isEmpty();
        }
    }

    public record Figure(List<List<Style>> rows) {
        public Figure {
            rows = rows.stream().map(List::copyOf).toList();
        }

        public int pickRow(UnrealLoader.HairCover cover) {
            return HairRows.pickRow(rows.size(), cover.style(), cover.byArmor());
        }

        public List<Hair> findHair(int row, int style) {
            if (row < 0 || row >= rows.size()) {
                return List.of();
            }
            var base = rows.getFirst();
            var number = style >= 0 && style < base.size() && !base.get(style).isEmpty() ? style : 0;
            var styles = rows.get(row);
            if (number >= styles.size()) {
                return List.of();
            }
            var found = styles.get(number);
            var hair = new ArrayList<Hair>();
            if (!found.front().isEmpty()) {
                hair.add(new Hair(found.front(), found.frontTexture()));
            }
            if (!found.back().isEmpty()) {
                hair.add(new Hair(found.back(), found.backTexture()));
            }
            return List.copyOf(hair);
        }
    }

    public static HairRows empty() {
        return new HairRows(Map.of());
    }

    public static HairRows read(Path client, WorldVariant variant) {
        try {
            return from(TableSchemas.read(Tables.CHARACTERS, client.resolve("system"), variant));
        } catch (RuntimeException unreadable) {
            return empty();
        }
    }

    static HairRows fromRows(List<Map<String, Object>> records) {
        var figures = new HashMap<String, Figure>();
        for (var record : records) {
            var name = text(record.get("class"));
            if (name.isEmpty() || !(record.get("rows") instanceof List<?> hairRows) || hairRows.isEmpty()) {
                continue;
            }
            var rows = new ArrayList<List<Style>>();
            for (var hairRow : hairRows) {
                var styles = new ArrayList<Style>();
                if (hairRow instanceof Map<?, ?> row && row.get("styles") instanceof List<?> list) {
                    for (var style : list) {
                        var place = style instanceof Map<?, ?> found ? found : Map.of();
                        var front = place.get("front") instanceof Map<?, ?> found ? found : Map.of();
                        var back = place.get("back") instanceof Map<?, ?> found ? found : Map.of();
                        styles.add(new Style(
                                text(front.get("mesh")),
                                text(front.get("texture")),
                                text(back.get("mesh")),
                                text(back.get("texture"))));
                    }
                }
                rows.add(styles);
            }
            figures.put(name.toLowerCase(Locale.ROOT), new Figure(rows));
        }
        return new HairRows(figures);
    }

    public static int parseStyle(String mesh) {
        var at = mesh.indexOf("_m0");
        if (at < 0 || at + 4 >= mesh.length()) {
            return 0;
        }
        var tens = mesh.charAt(at + 3) - '0';
        var ones = mesh.charAt(at + 4) - '0';
        return tens < 0 || tens > 9 || ones < 0 || ones > 9 ? 0 : tens * 10 + ones;
    }

    static int pickRow(int rows, int style, boolean byArmor) {
        int row;
        if (rows >= NEWER_ROWS) {
            row = byArmor ? ROW_UNDER_ARMOR : style > ROW_UNDER_ARMOR ? 0 : style;
            if (row == 4 || row == 5) {
                return -1;
            }
        } else {
            if (byArmor || style >= 4) {
                return -1;
            }
            row = style;
        }
        return row < rows ? row : -1;
    }

    public @Nullable Figure find(String pawnClass) {
        return figures.get(pawnClass.toLowerCase(Locale.ROOT));
    }

    private static HairRows from(TableData table) {
        return table.schema() == null ? empty() : fromRows(table.rows());
    }

    private static String text(@Nullable Object value) {
        return value instanceof String name && !name.isBlank() && !name.equalsIgnoreCase("None") ? name.strip() : "";
    }
}

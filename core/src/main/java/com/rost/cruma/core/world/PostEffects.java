package com.rost.cruma.core.world;

import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.unreal.L2DataFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class PostEffects {
    public static final int YCBCR = 0;

    public static final int MOTION_BLUR = 3;

    private static final PostEffects NONE = new PostEffects(Map.of());
    private static final String FILE = "PostEffectData.dat";

    private final Map<Integer, Effect> effects;

    private PostEffects(Map<Integer, Effect> effects) {
        this.effects = effects;
    }

    public static PostEffects getNone() {
        return NONE;
    }

    public static PostEffects of(Effect... rows) {
        var effects = new HashMap<Integer, Effect>();
        for (var row : rows) {
            effects.put(row.id(), row);
        }
        return new PostEffects(Map.copyOf(effects));
    }

    public static PostEffects read(Path clientRoot, WorldVariant variant) {
        var system = clientRoot.resolve("system");
        var file = TableFiles.find(system, variant, FILE);
        if (file == null) {
            return NONE;
        }
        try {
            return fromRows(TableSchemas.read(
                            Tables.POST_EFFECTS,
                            file,
                            L2DataFile.read(file),
                            TableSchemas.readGameDataNames(system, variant),
                            TableSchemas.getClientFolder(system))
                    .rows());
        } catch (IOException | RuntimeException failure) {
            return NONE;
        }
    }

    static PostEffects parse(byte[] table) {
        return fromRows(TableSchemas.read(Tables.POST_EFFECTS, Path.of(FILE), table, List.of(), null)
                .rows());
    }

    static PostEffects fromRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return NONE;
        }
        var effects = new HashMap<Integer, Effect>();
        for (var row : rows) {
            var id = integer(row.get("id"));
            effects.put(
                    id,
                    new Effect(
                            id,
                            integer(row.get("type")),
                            integer(row.get("mode")),
                            number(row.get("seconds")),
                            integer(row.get("keepsChroma")) != 0,
                            values(row.get("first")),
                            values(row.get("second")),
                            row.get("name") instanceof String name ? name : ""));
        }
        return new PostEffects(Map.copyOf(effects));
    }

    public @Nullable Effect getEffect(int id) {
        return effects.get(id);
    }

    public int size() {
        return effects.size();
    }

    public List<Effect> getRows() {
        var rows = new ArrayList<>(effects.values());
        rows.sort(Comparator.comparingInt(Effect::id));
        return rows;
    }

    public record Effect(
            int id, int type, int mode, float seconds, boolean keepsChroma, Values first, Values second, String name) {
        public Effect(int id, int type, int mode, float seconds, boolean keepsChroma, Values first, Values second) {
            this(id, type, mode, seconds, keepsChroma, first, second, "");
        }

        public int blurAlpha() {
            return (int) first.x();
        }

        public float blurMillis() {
            return first.y();
        }
    }

    public record Values(float x, float y, float z) {}

    private static Values values(@Nullable Object value) {
        if (!(value instanceof List<?> list) || list.size() < 3) {
            return new Values(0f, 0f, 0f);
        }
        return new Values(number(list.get(0)), number(list.get(1)), number(list.get(2)));
    }

    private static int integer(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static float number(@Nullable Object value) {
        return value instanceof Number number ? number.floatValue() : 0f;
    }
}
